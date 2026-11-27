package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

data class AudioProbeResult(
    val durationSec: Double,
    val channels: Int,
    val sampleRate: Int,
    val peaks: FloatArray,
    val peakMax: Float
)

object AudioProcessor {

    suspend fun probeAudio(context: Context, uri: Uri, buckets: Int = 240): AudioProbeResult =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val trackIndex = findAudioTrack(extractor)
                require(trackIndex >= 0) { "No audio track found in media" }
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else 0L
                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 44100
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 1

                val rawPeaks = FloatArray(buckets)
                var peakMax = 0f
                var decodedSamples = 0L
                var actualRate = sampleRate
                var actualChannels = channels

                val decoder = MediaCodec.createDecoderByType(mime)
                try {
                    decoder.configure(format, null, null, 0)
                    decoder.start()

                    val totalExpectedSamples = ((durationUs / 1_000_000.0) * sampleRate)
                        .toLong().coerceAtLeast(1L)
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false

                    while (!outputDone) {
                        coroutineContext.ensureActive()
                        if (!inputDone) {
                            val inIdx = decoder.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = decoder.getInputBuffer(inIdx)!!
                                val read = extractor.readSampleData(buf, 0)
                                if (read < 0) {
                                    decoder.queueInputBuffer(
                                        inIdx, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val outFmt = decoder.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                actualRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                actualChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        } else if (outIdx >= 0) {
                            if (info.size > 0) {
                                val outBuf = decoder.getOutputBuffer(outIdx)!!
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                val shortBuf = outBuf.asShortBuffer()
                                val totalShorts = shortBuf.remaining()
                                val frameCount = totalShorts / actualChannels.coerceAtLeast(1)
                                for (f in 0 until frameCount step 4) {
                                    var framePeak = 0f
                                    for (c in 0 until actualChannels) {
                                        val s = shortBuf.get(f * actualChannels + c)
                                        val amp = abs(s / 32768f)
                                        if (amp > framePeak) framePeak = amp
                                    }
                                    val pos = decodedSamples + f
                                    val bucket = ((pos.toDouble() / totalExpectedSamples) * buckets)
                                        .toInt().coerceIn(0, buckets - 1)
                                    if (framePeak > rawPeaks[bucket]) rawPeaks[bucket] = framePeak
                                    if (framePeak > peakMax) peakMax = framePeak
                                }
                                decodedSamples += frameCount
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                } finally {
                    runCatching { decoder.stop() }
                    runCatching { decoder.release() }
                }

                val computedDur = if (durationUs > 0) {
                    durationUs / 1_000_000.0
                } else {
                    decodedSamples.toDouble() / actualRate.coerceAtLeast(1)
                }

                AudioProbeResult(
                    durationSec = computedDur,
                    channels = actualChannels,
                    sampleRate = actualRate,
                    peaks = rawPeaks,
                    peakMax = peakMax
                )
            } finally {
                extractor.release()
            }
        }

    suspend fun extractPcm48kMono(
        context: Context,
        uri: Uri,
        startSec: Double,
        durationSec: Double,
        fadeMs: Int = 8
    ): ShortArray = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found" }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            val startUs = (startSec * 1_000_000.0).toLong().coerceAtLeast(0L)
            val endUs = ((startSec + durationSec) * 1_000_000.0).toLong()
            if (startUs > 50_000L) {
                extractor.seekTo(startUs - 50_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val monoFloat = ArrayList<Float>((durationSec * sampleRate).toInt().coerceAtLeast(1024))
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val read = extractor.readSampleData(buf, 0)
                            val pts = extractor.sampleTime
                            if (read < 0 || pts > endUs + 100_000L) {
                                decoder.queueInputBuffer(
                                    inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, read, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outFmt = decoder.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    } else if (outIdx >= 0) {
                        if (info.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val shortBuf = outBuf.asShortBuffer()
                            val ch = channels.coerceAtLeast(1)
                            val frameCount = shortBuf.remaining() / ch
                            val bufStartUs = info.presentationTimeUs
                            val usPerSample = 1_000_000.0 / sampleRate

                            for (f in 0 until frameCount) {
                                val sampleTimeUs = bufStartUs + (f * usPerSample).toLong()
                                if (sampleTimeUs < startUs) continue
                                if (sampleTimeUs > endUs) break
                                var sum = 0f
                                for (c in 0 until ch) {
                                    sum += shortBuf.get(f * ch + c) / 32768f
                                }
                                monoFloat.add((sum / ch).coerceIn(-1f, 1f))
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }

            // Resample to 48000 Hz mono & enforce exact target sample count
            val targetCount = (durationSec * StickerLimits.SAMPLE_RATE).toInt().coerceAtLeast(960)
            val resampled = ShortArray(targetCount)
            if (monoFloat.isNotEmpty()) {
                val ratio = sampleRate.toDouble() / StickerLimits.SAMPLE_RATE
                val srcSize = monoFloat.size
                for (i in 0 until targetCount) {
                    val srcPos = i * ratio
                    val idx0 = srcPos.toInt().coerceIn(0, srcSize - 1)
                    val idx1 = min(srcSize - 1, idx0 + 1)
                    val frac = (srcPos - idx0).toFloat()
                    val v = monoFloat[idx0] * (1f - frac) + monoFloat[idx1] * frac
                    resampled[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }

            // Smooth edge fade to prevent audio clicks at cut points
            if (fadeMs > 0) {
                val fadeSamples = min(
                    resampled.size / 2,
                    ((fadeMs / 1000.0) * StickerLimits.SAMPLE_RATE).toInt()
                )
                for (i in 0 until fadeSamples) {
                    val g = i.toFloat() / fadeSamples.coerceAtLeast(1)
                    resampled[i] = (resampled[i] * g).toInt().toShort()
                    val tailIdx = resampled.size - 1 - i
                    resampled[tailIdx] = (resampled[tailIdx] * g).toInt().toShort()
                }
            }
            resampled
        } finally {
            extractor.release()
        }
    }

    fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            findAudioTrack(extractor) >= 0
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
