package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class VideoProbeResult(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean
)

data class ImageProbeResult(
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val gifFrames: Int = 1,
    val gifDurationSec: Double = 0.0
)

data class CapturedFrame(
    val bitmap: Bitmap,
    val fingerprint: ByteArray
)

data class AnalysisResult(
    val sourceKind: SourceKind,
    val framingMode: FramingMode,
    val fitMode: MixFitMode,
    val durationSec: Double,
    val visualDurationSec: Double,
    val audioDurationSec: Double,
    val frames: List<CapturedFrame>,
    val captureFps: Int,
    val captureSide: Int,
    val distinctCounts: Map<Int, Int>,
    val sampleBytes: Map<Int, Map<Float, Double>>,
    val cells: List<GridCell>,
    val recommended: GridCell?,
    val elapsedMs: Long
)

data class GeneratedSticker(
    val webpBytes: ByteArray,
    val oggBytes: ByteArray?,
    val previewFrames: List<Bitmap>,
    val side: Int,
    val fps: Int,
    val quality: Float,
    val isAnimated: Boolean,
    val durationSec: Double,
    val audioDurationSec: Double,
    val audioKbps: Int,
    val soundSha256: String,
    val estimatedWebpBytes: Int?,
    val elapsedMs: Long,
    val packName: String,
    val publisher: String,
    val mp4File: File? = null
)

object MediaEngine {

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    suspend fun probeVideo(context: Context, uri: Uri): VideoProbeResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val swapped = rot == 90 || rot == 270
            val hasAudio = AudioProcessor.hasAudioTrack(context, uri)
            VideoProbeResult(
                durationSec = (durMs / 1000.0).coerceAtLeast(0.1),
                width = if (swapped) h else w,
                height = if (swapped) w else h,
                hasAudio = hasAudio
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun probeImage(context: Context, uri: Uri): ImageProbeResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open image")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val isGif = bytes.size >= 6 &&
            (String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF87a") ||
                String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF89a"))
        if (isGif) {
            val delays = parseGifFrameDelaysMs(bytes)
            if (delays.size > 1) {
                val totalMs = delays.sum()
                return@withContext ImageProbeResult(
                    width = opts.outWidth,
                    height = opts.outHeight,
                    animated = true,
                    gifFrames = delays.size,
                    gifDurationSec = totalMs / 1000.0
                )
            }
        }
        ImageProbeResult(
            width = opts.outWidth.coerceAtLeast(1),
            height = opts.outHeight.coerceAtLeast(1),
            animated = false
        )
    }

    /**
     * Multi-core frame capture, fingerprint deduplication, and quality/FPS size matrix estimation.
     */
    suspend fun analyze(
        context: Context,
        sourceKind: SourceKind,
        visualUri: Uri,
        visualStartSec: Double,
        visualDurationSec: Double,
        audioDurationSec: Double,
        framingMode: FramingMode,
        fitMode: MixFitMode,
        onProgress: (Float) -> Unit
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val vDur = if (sourceKind == SourceKind.IMAGE) audioDurationSec else visualDurationSec
        val stickerDur = if (sourceKind == SourceKind.IMAGE || fitMode != MixFitMode.INDEPENDENT) {
            audioDurationSec
        } else {
            vDur
        }
        val capDur = min(vDur, stickerDur).coerceAtLeast(0.1)

        if (sourceKind == SourceKind.IMAGE) {
            onProgress(0.35f)
            val single = captureSingleImage(context, visualUri, StickerLimits.SIDE, framingMode)
            onProgress(1f)
            val cells = StickerLimits.SIDES.map { side ->
                GridCell(
                    side = side,
                    fps = 1,
                    estBytes = 40_000,
                    estMinBytes = 20_000,
                    status = CellStatus.OK,
                    score = side.toDouble()
                )
            }
            return@withContext AnalysisResult(
                sourceKind = sourceKind,
                framingMode = framingMode,
                fitMode = fitMode,
                durationSec = audioDurationSec,
                visualDurationSec = audioDurationSec,
                audioDurationSec = audioDurationSec,
                frames = listOf(single),
                captureFps = 1,
                captureSide = StickerLimits.SIDE,
                distinctCounts = mapOf(1 to 1),
                sampleBytes = emptyMap(),
                cells = cells,
                recommended = cells.first(),
                elapsedMs = System.currentTimeMillis() - t0
            )
        }

        val captureFps = StickerLimits.captureFpsFor(stickerDur)
        val slots = kotlin.math.ceil(capDur * captureFps).toInt().coerceAtLeast(1)
        val captureSide = floor(sqrt(StickerLimits.CAPTURE_MEM_BYTES.toDouble() / (4.0 * slots)))
            .toInt().coerceIn(64, StickerLimits.SIDE)

        val captured = when (sourceKind) {
            SourceKind.MIX_GIF -> captureGifFrames(
                context = context,
                uri = visualUri,
                capDurSec = capDur,
                fps = captureFps,
                side = captureSide,
                framing = framingMode,
                onProgress = { f -> onProgress(f * 0.72f) }
            )
            else -> captureVideoFramesParallel(
                context = context,
                uri = visualUri,
                startSec = visualStartSec,
                capDurSec = capDur,
                fps = captureFps,
                side = captureSide,
                framing = framingMode,
                onProgress = { f -> onProgress(f * 0.72f) }
            )
        }

        val frames = fitTimeline(captured, capDur, stickerDur, captureFps, fitMode)
        val sides = StickerLimits.SIDES.filter { it <= captureSide }
        val fpsList = StickerLimits.FPS_LIST.filter { it <= captureFps }

        // Distinct frame count per FPS
        val distinctCounts = mutableMapOf<Int, Int>()
        for (fps in fpsList) {
            val plan = buildPlan(frames.size, stickerDur, fps, captureFps)
            var d = 0
            for (k in plan.indices) {
                val a = frames[plan[k].index]
                val b = if (k > 0) frames[plan[k - 1].index] else null
                if (b == null || (a !== b && !a.fingerprint.contentEquals(b.fingerprint))) {
                    d++
                }
            }
            distinctCounts[fps] = d
        }

        // Collect unique frames for representative sampling (up to 20 evenly distributed frames)
        val uniq = mutableListOf<CapturedFrame>()
        for (i in frames.indices) {
            if (i == 0 || (frames[i] !== frames[i - 1] &&
                    !frames[i].fingerprint.contentEquals(frames[i - 1].fingerprint))
            ) {
                uniq.add(frames[i])
            }
        }
        val m = min(20, uniq.size).coerceAtLeast(1)
        val sampleFrames = List(m) { i ->
            val idx = floor((((i + ((i * 0.618) % 1.0)) * uniq.size) / m)).toInt()
                .coerceIn(0, uniq.size - 1)
            uniq[idx]
        }

        // Parallel WebP size sampling across CPU cores
        val semaphore = Semaphore(cpuCores)
        val sampleBytes = mutableMapOf<Int, Map<Float, Double>>()
        val sideResults = coroutineScope {
            sides.mapIndexed { sIdx, side ->
                async {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        val scaledSamples = sampleFrames.map { cf ->
                            if (cf.bitmap.width == side && cf.bitmap.height == side) {
                                cf.bitmap
                            } else {
                                Bitmap.createScaledBitmap(cf.bitmap, side, side, true)
                            }
                        }
                        val qMap = mutableMapOf<Float, Double>()
                        val qTargets = listOfNotNull(
                            StickerLimits.Q_BY_SIDE[side],
                            0.35f,
                            StickerLimits.Q_MIN
                        ).distinct()
                        for (q in qTargets) {
                            var sum = 0L
                            for (bmp in scaledSamples) {
                                coroutineContext.ensureActive()
                                sum += WebpMuxer.encodeStaticFrame(bmp, q).payload.size
                            }
                            qMap[q] = sum.toDouble() / scaledSamples.size
                        }
                        scaledSamples.forEachIndexed { idx, bmp ->
                            if (bmp !== sampleFrames[idx].bitmap) bmp.recycle()
                        }
                        onProgress(0.72f + (0.28f * (sIdx + 1) / sides.size))
                        side to qMap
                    }
                }
            }.awaitAll()
        }
        for ((side, map) in sideResults) {
            sampleBytes[side] = map
        }

        val overhead = 32
        val cells = mutableListOf<GridCell>()
        for (side in StickerLimits.SIDES) {
            for (fps in StickerLimits.FPS_LIST) {
                val qMap = sampleBytes[side]
                val n = distinctCounts[fps]
                if (qMap == null || n == null) {
                    cells.add(GridCell(side, fps, 0, 0, CellStatus.UNAVAILABLE, 0.0))
                    continue
                }
                val qDefault = StickerLimits.Q_BY_SIDE[side] ?: 0.5f
                val est = ((qMap[qDefault] ?: 0.0) + overhead) * n + 400
                val estMin = ((qMap[StickerLimits.Q_MIN] ?: 0.0) + overhead) * n + 400
                val status = when {
                    est <= StickerLimits.SAFE_BYTES -> CellStatus.OK
                    estMin <= StickerLimits.SAFE_BYTES -> CellStatus.LOW_QUALITY
                    else -> CellStatus.OVER_LIMIT
                }
                cells.add(
                    GridCell(
                        side = side,
                        fps = fps,
                        estBytes = est.roundToInt(),
                        estMinBytes = estMin.roundToInt(),
                        status = status,
                        score = StickerLimits.score(side, fps)
                    )
                )
            }
        }

        val recommended = cells.filter { it.status == CellStatus.OK }.maxByOrNull { it.score }
            ?: cells.filter { it.status == CellStatus.LOW_QUALITY }.maxByOrNull { it.score }

        onProgress(1f)
        AnalysisResult(
            sourceKind = sourceKind,
            framingMode = framingMode,
            fitMode = fitMode,
            durationSec = stickerDur,
            visualDurationSec = vDur,
            audioDurationSec = audioDurationSec,
            frames = frames,
            captureFps = captureFps,
            captureSide = captureSide,
            distinctCounts = distinctCounts,
            sampleBytes = sampleBytes,
            cells = cells,
            recommended = recommended,
            elapsedMs = System.currentTimeMillis() - t0
        )
    }

    /**
     * Generates animated/still WebP + Ogg/Opus concurrently across CPU cores.
     */
    suspend fun createSticker(
        context: Context,
        analysis: AnalysisResult,
        side: Int,
        fps: Int,
        audioUri: Uri,
        audioStartSec: Double,
        audioDurationSec: Double,
        syncOffsetMs: Int,
        stillExperimental: Boolean,
        packName: String,
        publisher: String,
        onProgress: (Float) -> Unit
    ): GeneratedSticker = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val isStill = stillExperimental && analysis.sourceKind == SourceKind.IMAGE
        val bitrate = StickerLimits.computeTargetAudioBitrate(audioDurationSec)

        coroutineScope {
            // Branch 1: Extract & encode audio to Ogg/Opus concurrently
            val audioJob = async {
                val fade = if (analysis.sourceKind == SourceKind.VIDEO) 0 else 8
                val pcm = AudioProcessor.extractPcm48kMono(
                    context = context,
                    uri = audioUri,
                    startSec = audioStartSec,
                    durationSec = audioDurationSec,
                    fadeMs = fade
                )
                val ogg = OggOpusMuxer.encodePcm48kMonoToOgg(pcm, bitrate)
                val sha = if (ogg != null) sha256Base64(ogg) else sha256Base64(pcm.size.toString().toByteArray())
                Triple(pcm, ogg, sha)
            }

            // Branch 2: Encode visual frames in parallel batches on CPU cores
            val (pcm, oggBytes, soundSha) = audioJob.await()
            onProgress(0.25f)

            val exif = WebpMuxer.exifWhatsApp(packName, publisher, soundSha)
            val plan = buildPlan(analysis.frames.size, analysis.durationSec, fps, analysis.captureFps)
            val qStart = StickerLimits.Q_BY_SIDE[side] ?: 0.5f
            val qList = buildList {
                var q = qStart
                while (q >= StickerLimits.Q_MIN - 1e-4f) {
                    add((q * 100f).roundToInt() / 100f)
                    q -= 0.10f
                }
                if (last() > StickerLimits.Q_MIN) add(StickerLimits.Q_MIN)
            }

            var bestBytes: ByteArray? = null
            var bestQ = qStart
            val semaphore = Semaphore(cpuCores)

            for (q in qList) {
                coroutineContext.ensureActive()
                // Deduplicate plan slots before parallel frame encoding
                val grouped = mutableListOf<Pair<CapturedFrame, Int>>()
                for (slot in plan) {
                    val cf = analysis.frames[slot.index]
                    if (grouped.isNotEmpty()) {
                        val prev = grouped.last()
                        if (prev.first === cf || prev.first.fingerprint.contentEquals(cf.fingerprint)) {
                            grouped[grouped.lastIndex] = prev.copy(second = prev.second + slot.durationMs)
                            continue
                        }
                    }
                    grouped.add(cf to slot.durationMs)
                }

                val uniqueRefs = grouped.map { it.first }.distinct()
                val encodedMap = coroutineScope {
                    uniqueRefs.map { cf ->
                        async {
                            semaphore.withPermit {
                                coroutineContext.ensureActive()
                                val bmp = if (cf.bitmap.width == side && cf.bitmap.height == side) {
                                    cf.bitmap
                                } else {
                                    Bitmap.createScaledBitmap(cf.bitmap, side, side, true)
                                }
                                val enc = WebpMuxer.encodeStaticFrame(bmp, q)
                                if (bmp !== cf.bitmap) bmp.recycle()
                                cf to enc
                            }
                        }
                    }.awaitAll().toMap()
                }

                val assembled = grouped.mapIndexed { idx, (cf, durMs) ->
                    val base = encodedMap.getValue(cf)
                    val finalDur = if (idx == 0 && syncOffsetMs > 0 && !isStill) {
                        durMs + syncOffsetMs
                    } else durMs
                    base.copy(durationMs = finalDur)
                }

                val webp = if (isStill) {
                    WebpMuxer.buildStillWebp(assembled.first(), exif, side)
                } else {
                    WebpMuxer.buildAnimatedWebp(assembled, exif, side)
                }
                bestBytes = webp
                bestQ = q
                onProgress(0.85f)
                if (webp.size <= StickerLimits.MAX_STICKER_BYTES) break
            }

            val finalWebp = bestBytes ?: error("Failed to encode WebP")
            require(finalWebp.size <= StickerLimits.MAX_STICKER_BYTES) {
                "Sticker exceeds 1 MB (${finalWebp.size / 1000} KB). Pick lower FPS or resolution."
            }

            val cell = analysis.cells.firstOrNull { it.side == side && it.fps == fps }
            val previewBitmaps = analysis.frames
                .filterIndexed { idx, _ -> idx % max(1, analysis.frames.size / 24) == 0 }
                .take(24)
                .map { it.bitmap }

            onProgress(1f)
            GeneratedSticker(
                webpBytes = finalWebp,
                oggBytes = oggBytes,
                previewFrames = previewBitmaps,
                side = side,
                fps = if (analysis.sourceKind == SourceKind.IMAGE) 1 else fps,
                quality = bestQ,
                isAnimated = !isStill,
                durationSec = analysis.durationSec,
                audioDurationSec = audioDurationSec,
                audioKbps = (bitrate / 1000),
                soundSha256 = soundSha,
                estimatedWebpBytes = cell?.estBytes,
                elapsedMs = System.currentTimeMillis() - t0,
                packName = packName.trim(),
                publisher = publisher.trim()
            )
        }
    }

    private suspend fun captureVideoFramesParallel(
        context: Context,
        uri: Uri,
        startSec: Double,
        capDurSec: Double,
        fps: Int,
        side: Int,
        framing: FramingMode,
        onProgress: (Float) -> Unit
    ): List<CapturedFrame> = coroutineScope {
        val totalFrames = (capDurSec * fps).roundToInt().coerceAtLeast(1)
        val workerCount = min(cpuCores, totalFrames)
        val slots = arrayOfNulls<CapturedFrame>(totalFrames)
        var completed = 0
        val progressLock = Any()

        // Partition frame indices across CPU workers, each owning a MediaMetadataRetriever
        val workers = (0 until workerCount).map { workerId ->
            async(Dispatchers.Default) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: ((startSec + capDurSec) * 1000).toLong()
                    val maxTimeUs = ((durMs - 20).coerceAtLeast(0)) * 1000L

                    for (i in workerId until totalFrames step workerCount) {
                        coroutineContext.ensureActive()
                        val targetUs = (((startSec + (i + 0.5) / fps) * 1_000_000.0).toLong())
                            .coerceIn(0L, maxTimeUs)
                        val raw = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (raw != null) {
                            val square = drawToSquareBitmap(raw, side, framing)
                            raw.recycle()
                            val fp = computeFingerprint(square)
                            slots[i] = CapturedFrame(square, fp)
                        }
                        synchronized(progressLock) {
                            completed++
                            if (completed % 4 == 0 || completed == totalFrames) {
                                onProgress(completed.toFloat() / totalFrames)
                            }
                        }
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
        workers.awaitAll()

        // Fill any rare null slot from nearest valid frame
        val valid = slots.filterNotNull()
        require(valid.isNotEmpty()) { "Could not decode any video frames" }
        var lastValid = valid.first()
        List(totalFrames) { idx ->
            val current = slots[idx]
            if (current != null) {
                lastValid = current
                current
            } else {
                lastValid
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun captureGifFrames(
        context: Context,
        uri: Uri,
        capDurSec: Double,
        fps: Int,
        side: Int,
        framing: FramingMode,
        onProgress: (Float) -> Unit
    ): List<CapturedFrame> = withContext(Dispatchers.Default) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open GIF")
        val movie = Movie.decodeStream(ByteArrayInputStream(bytes))
            ?: error("Unsupported GIF stream")
        val delays = parseGifFrameDelaysMs(bytes)
        val totalGifMs = (if (delays.isNotEmpty()) delays.sum() else movie.duration()).coerceAtLeast(100)
        val n = (capDurSec * fps).roundToInt().coerceAtLeast(1)
        val rawBmp = Bitmap.createBitmap(
            movie.width().coerceAtLeast(1),
            movie.height().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val rawCanvas = Canvas(rawBmp)
        val out = ArrayList<CapturedFrame>(n)
        for (i in 0 until n) {
            coroutineContext.ensureActive()
            val tMs = (((i + 0.5) * 1000.0 / fps).toInt()) % totalGifMs
            movie.setTime(tMs)
            rawCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            movie.draw(rawCanvas, 0f, 0f)
            val square = drawToSquareBitmap(rawBmp, side, framing)
            out.add(CapturedFrame(square, computeFingerprint(square)))
            if (i % 4 == 0 || i == n - 1) onProgress((i + 1).toFloat() / n)
        }
        rawBmp.recycle()
        out
    }

    private suspend fun captureSingleImage(
        context: Context,
        uri: Uri,
        side: Int,
        framing: FramingMode
    ): CapturedFrame = withContext(Dispatchers.IO) {
        val bmp = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: error("Unable to decode image")
        val square = drawToSquareBitmap(bmp, side, framing)
        if (square !== bmp) bmp.recycle()
        CapturedFrame(square, computeFingerprint(square))
    }

    private fun drawToSquareBitmap(src: Bitmap, side: Int, framing: FramingMode): Bitmap {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val sw = src.width.toFloat().coerceAtLeast(1f)
        val sh = src.height.toFloat().coerceAtLeast(1f)
        val scale = if (framing == FramingMode.COVER) {
            max(side / sw, side / sh)
        } else {
            min(side / sw, side / sh)
        }
        val dx = (side - sw * scale) / 2f
        val dy = (side - sh * scale) / 2f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    private fun computeFingerprint(bmp: Bitmap): ByteArray {
        val tiny = Bitmap.createScaledBitmap(bmp, 16, 16, false)
        val pixels = IntArray(256)
        tiny.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        tiny.recycle()
        val fp = ByteArray(256)
        for (i in 0 until 256) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val a = (c ushr 24) and 0xFF
            val lum = ((r * 30 + g * 59 + b * 11) / 100) and 0xF0
            fp[i] = (lum or (a ushr 4)).toByte()
        }
        return fp
    }

    private fun fitTimeline(
        captured: List<CapturedFrame>,
        capDurSec: Double,
        stickerDurSec: Double,
        captureFps: Int,
        fitMode: MixFitMode
    ): List<CapturedFrame> {
        val targetCount = (stickerDurSec * captureFps).roundToInt().coerceAtLeast(1)
        if (targetCount == captured.size) return captured
        return List(targetCount) { i ->
            val t = (i + 0.5) / captureFps
            val tv = if (fitMode == MixFitMode.REPEAT) t % capDurSec else min(t, capDurSec)
            val idx = (tv * captureFps).toInt().coerceIn(0, captured.size - 1)
            captured[idx]
        }
    }

    private data class PlanSlot(val index: Int, val durationMs: Int)

    private fun buildPlan(
        capturedCount: Int,
        durSec: Double,
        fps: Int,
        captureFps: Int
    ): List<PlanSlot> {
        val totalMs = (durSec * 1000.0).roundToInt().coerceAtLeast(1)
        val count = (durSec * fps).roundToInt().coerceAtLeast(1)
        return List(count) { k ->
            val startMs = ((k * 1000.0) / fps).roundToInt()
            val endMs = if (k == count - 1) totalMs else min(totalMs, (((k + 1) * 1000.0) / fps).roundToInt())
            val idx = ((k.toDouble() * captureFps) / fps).roundToInt().coerceIn(0, capturedCount - 1)
            PlanSlot(idx, max(1, endMs - startMs))
        }
    }

    private fun parseGifFrameDelaysMs(bytes: ByteArray): List<Int> {
        val delays = mutableListOf<Int>()
        var i = 0
        while (i + 6 < bytes.size) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte() && bytes[i + 2] == 0x04.toByte()) {
                val lo = bytes[i + 4].toInt() and 0xFF
                val hi = bytes[i + 5].toInt() and 0xFF
                val cs = lo or (hi shl 8)
                val ms = cs * 10
                delays.add(if (ms > 10) ms else 100)
                i += 7
            } else {
                i++
            }
        }
        return delays
    }

    private fun sha256Base64(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
    }
}
