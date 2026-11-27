package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hardware-accelerated H.264 + AAC MP4 exporter for WhatsApp's native Sound Sticker creator.
 * Encodes video (via MediaCodec Surface input) and audio (AAC 48kHz mono) in parallel.
 */
object Mp4StickerExporter {

    private data class EncodedSample(
        val data: ByteArray,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int
    )

    suspend fun exportMp4(
        outFile: File,
        frames: List<CapturedFrame>,
        captureFps: Int,
        targetSide: Int,
        targetFps: Int,
        durationSec: Double,
        pcm48kMono: ShortArray
    ): File = withContext(Dispatchers.Default) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val evenSide = (targetSide / 2) * 2
        val safeSide = evenSide.coerceAtLeast(64)
        val safeFps = targetFps.coerceIn(1, 30)

        coroutineScope {
            val videoDeferred = async {
                encodeH264Track(frames, captureFps, safeSide, safeFps, durationSec)
            }
            val audioDeferred = async {
                encodeAacTrack(pcm48kMono)
            }
            val (videoFormat, videoSamples) = videoDeferred.await()
            val (audioFormat, audioSamples) = audioDeferred.await()

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val vTrack = muxer.addTrack(videoFormat)
                val aTrack = muxer.addTrack(audioFormat)
                muxer.start()

                val bufInfo = MediaCodec.BufferInfo()
                for (s in videoSamples) {
                    bufInfo.set(0, s.size, s.presentationTimeUs, s.flags)
                    muxer.writeSampleData(vTrack, ByteBuffer.wrap(s.data), bufInfo)
                }
                for (s in audioSamples) {
                    bufInfo.set(0, s.size, s.presentationTimeUs, s.flags)
                    muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), bufInfo)
                }
            } finally {
                runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        }
        outFile
    }

    private fun encodeH264Track(
        frames: List<CapturedFrame>,
        captureFps: Int,
        side: Int,
        fps: Int,
        durationSec: Double
    ): Pair<MediaFormat, List<EncodedSample>> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, side, side).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, (side * side * 8).coerceIn(800_000, 2_500_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val samples = mutableListOf<EncodedSample>()
        var outputFormat: MediaFormat? = null

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            val totalFrames = (durationSec * fps).toInt().coerceAtLeast(1)
            val stepUs = 1_000_000L / fps
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val destRect = Rect(0, 0, side, side)
            val info = MediaCodec.BufferInfo()

            // Feed frames via Surface Canvas, draining encoder after each frame
            for (k in 0 until totalFrames) {
                val idx = ((k.toDouble() * captureFps) / fps).toInt().coerceIn(0, frames.size - 1)
                val bmp = frames[idx].bitmap
                val canvas: Canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.parseColor("#111B21"))
                    canvas.drawBitmap(bmp, null, destRect, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drainEncoder(encoder, info, samples, endOfStream = false) { fmt ->
                    outputFormat = fmt
                }
            }
            // Extra tail frame to ensure full duration matching audio
            val lastBmp = frames.last().bitmap
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.parseColor("#111B21"))
                canvas.drawBitmap(lastBmp, null, destRect, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, info, samples, endOfStream = true) { fmt ->
                outputFormat = fmt
            }

            // Re-stamp deterministic monotonically increasing presentation times
            val restamped = samples.mapIndexed { idx, s ->
                val pts = if ((s.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) 0L else idx * stepUs
                s.copy(presentationTimeUs = pts)
            }
            surface.release()
            return Pair(outputFormat ?: encoder.outputFormat, restamped)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun encodeAacTrack(pcm: ShortArray): Pair<MediaFormat, List<EncodedSample>> {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            StickerLimits.SAMPLE_RATE,
            1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = mutableListOf<EncodedSample>()
        var outputFormat: MediaFormat? = null

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val info = MediaCodec.BufferInfo()
            var sampleOffset = 0
            var inputDone = false
            var outputDone = false
            val chunkSamples = 1024

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (sampleOffset >= pcm.size) {
                            encoder.queueInputBuffer(
                                inIdx, 0, 0,
                                (sampleOffset * 1_000_000L) / StickerLimits.SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val count = minOf(chunkSamples, pcm.size - sampleOffset)
                            inBuf.order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until count) {
                                inBuf.putShort(pcm[sampleOffset + i])
                            }
                            val pts = (sampleOffset * 1_000_000L) / StickerLimits.SAMPLE_RATE
                            encoder.queueInputBuffer(inIdx, 0, count * 2, pts, 0)
                            sampleOffset += count
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.outputFormat
                } else if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx)!!
                        val bytes = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(bytes)
                        samples.add(EncodedSample(bytes, info.size, info.presentationTimeUs, info.flags))
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
            return Pair(outputFormat ?: encoder.outputFormat, samples)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        samples: MutableList<EncodedSample>,
        endOfStream: Boolean,
        onFormat: (MediaFormat) -> Unit
    ) {
        val timeoutUs = if (endOfStream) 10_000L else 0L
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormat(encoder.outputFormat)
                }
                outIdx >= 0 -> {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx)!!
                        val bytes = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(bytes)
                        samples.add(EncodedSample(bytes, info.size, info.presentationTimeUs, info.flags))
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }
}
