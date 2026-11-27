package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.StickerPackEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.StickerPackRepository
import com.example.engine.AnalysisResult
import com.example.engine.AudioProcessor
import com.example.engine.FramingMode
import com.example.engine.GeneratedSticker
import com.example.engine.MediaEngine
import com.example.engine.MixFitMode
import com.example.engine.Mp4StickerExporter
import com.example.engine.SourceKind
import com.example.engine.WebpMuxer
import com.example.whatsapp.WhatsAppBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class StudioMode {
    VIDEO,
    IMAGE,
    MIX
}

data class StudioUiState(
    val mode: StudioMode = StudioMode.VIDEO,
    val isProbingVisual: Boolean = false,
    val isProbingAudio: Boolean = false,

    // Visual source
    val visualUri: Uri? = null,
    val visualName: String = "",
    val visualWidth: Int = 0,
    val visualHeight: Int = 0,
    val visualDurationSec: Double = 0.0,
    val visualHasAudio: Boolean = false,
    val isGif: Boolean = false,
    val gifFrames: Int = 0,
    val isAnimatedImageWarning: Boolean = false,
    val visualStartSec: Double = 0.0,
    val visualEndSec: Double = 0.0,
    val visualCursorSec: Double = 0.0,
    val visualPreviewBitmap: Bitmap? = null,

    // Audio source (for VIDEO mode, mirrors visualUri; for IMAGE/MIX, separate audioUri)
    val audioUri: Uri? = null,
    val audioName: String = "",
    val audioDurationSec: Double = 0.0,
    val audioPeaks: FloatArray = FloatArray(0),
    val audioPeakMax: Float = 0f,
    val audioStartSec: Double = 0.0,
    val audioEndSec: Double = 0.0,
    val audioCursorSec: Double = 0.0,

    // Playback state
    val isPlayingAudio: Boolean = false,
    val isPlayingStickerPreview: Boolean = false,

    // Mix & format options
    val mixFitMode: MixFitMode = MixFitMode.INDEPENDENT,
    val framingMode: FramingMode = FramingMode.CONTAIN,
    val analysis: AnalysisResult? = null,
    val analysisKey: String? = null,
    val selectedSide: Int? = null,
    val selectedFps: Int? = null,
    val showMatrix: Boolean = false,
    val syncOffsetMs: Int = 0,
    val stillExperimental: Boolean = false,

    // Metadata
    val packName: String = "",
    val publisher: String = "",

    // Processing & result
    val isAnalyzing: Boolean = false,
    val analyzeProgress: Float = 0f,
    val isCreating: Boolean = false,
    val createProgress: Float = 0f,
    val isExportingMp4: Boolean = false,
    val generatedSticker: GeneratedSticker? = null,
    val generatedKey: String? = null,

    val errorMessage: String? = null,
    val statusBanner: String? = null
) {
    val visualClipLen: Double get() = (visualEndSec - visualStartSec).coerceAtLeast(0.0)
    val audioClipLen: Double get() = (audioEndSec - audioStartSec).coerceAtLeast(0.0)

    val effectiveAudioUri: Uri?
        get() = if (mode == StudioMode.VIDEO) visualUri else audioUri

    val effectiveAudioStart: Double
        get() = if (mode == StudioMode.VIDEO) visualStartSec else audioStartSec

    val effectiveAudioLen: Double
        get() = if (mode == StudioMode.VIDEO) visualClipLen else audioClipLen

    val sourcesReady: Boolean
        get() = when (mode) {
            StudioMode.VIDEO -> visualUri != null && visualClipLen >= 0.3
            StudioMode.IMAGE -> visualUri != null && audioUri != null && audioClipLen >= 0.3
            StudioMode.MIX -> visualUri != null && audioUri != null && audioClipLen >= 0.3 &&
                (isGif || visualClipLen >= 0.3)
        }

    val isAnalysisFresh: Boolean
        get() = analysis != null && analysisKey == computeAnalysisKey()

    val isResultFresh: Boolean
        get() = generatedSticker != null && generatedKey == computeFullKey()

    fun computeAnalysisKey(): String {
        val vPart = "${mode.name}|$visualUri|${"%.2f".format(visualStartSec)}|${"%.2f".format(visualClipLen)}|$isGif"
        val followsAudio = mode == StudioMode.IMAGE || mixFitMode != MixFitMode.INDEPENDENT
        val aPart = if (followsAudio) "${mixFitMode.name}|${"%.2f".format(effectiveAudioLen)}" else ""
        return "$vPart|$aPart|${framingMode.name}"
    }

    fun computeFullKey(): String {
        return "${computeAnalysisKey()}|$effectiveAudioUri|${"%.2f".format(effectiveAudioStart)}|${"%.2f".format(effectiveAudioLen)}|$selectedSide|$selectedFps|$syncOffsetMs|$stillExperimental"
    }
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val prefsRepo = UserPreferencesRepository(appContext)
    private val packRepo = StickerPackRepository(
        appContext,
        AppDatabase.getInstance(appContext).stickerDao()
    )

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    val packs = packRepo.packsWithStickers

    private var activeJob: Job? = null
    private var scrubJob: Job? = null
    private var playTickerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch {
            val prefs = prefsRepo.preferences.first()
            _uiState.update {
                it.copy(
                    packName = prefs.defaultPackName,
                    publisher = prefs.defaultPublisher,
                    syncOffsetMs = prefs.syncOffsetMs
                )
            }
        }
    }

    fun selectMode(mode: StudioMode) {
        if (_uiState.value.isAnalyzing || _uiState.value.isCreating) return
        stopPlayback()
        _uiState.update {
            it.copy(
                mode = mode,
                visualUri = null,
                visualName = "",
                visualWidth = 0,
                visualHeight = 0,
                visualDurationSec = 0.0,
                visualStartSec = 0.0,
                visualEndSec = 0.0,
                visualCursorSec = 0.0,
                visualPreviewBitmap = null,
                isGif = false,
                gifFrames = 0,
                isAnimatedImageWarning = false,
                analysis = null,
                analysisKey = null,
                selectedSide = null,
                selectedFps = null,
                generatedSticker = null,
                generatedKey = null,
                errorMessage = null,
                statusBanner = null
            )
        }
    }

    fun onVisualPicked(uri: Uri) {
        stopPlayback()
        val mode = _uiState.value.mode
        val fileName = resolveFileName(uri)
        _uiState.update {
            it.copy(
                isProbingVisual = true,
                errorMessage = null,
                statusBanner = null,
                analysis = null,
                generatedSticker = null
            )
        }

        viewModelScope.launch {
            runCatching {
                when (mode) {
                    StudioMode.VIDEO -> {
                        val vProbe = MediaEngine.probeVideo(appContext, uri)
                        val aProbe = if (vProbe.hasAudio) {
                            runCatching { AudioProcessor.probeAudio(appContext, uri) }.getOrNull()
                        } else null
                        val defaultEnd = min(vProbe.durationSec, 10.0)
                        val thumb = extractVideoFrame(uri, 0.0)
                        _uiState.update {
                            it.copy(
                                isProbingVisual = false,
                                visualUri = uri,
                                visualName = fileName,
                                visualWidth = vProbe.width,
                                visualHeight = vProbe.height,
                                visualDurationSec = vProbe.durationSec,
                                visualHasAudio = vProbe.hasAudio,
                                isGif = false,
                                visualStartSec = 0.0,
                                visualEndSec = defaultEnd,
                                visualCursorSec = 0.0,
                                visualPreviewBitmap = thumb,
                                audioPeaks = aProbe?.peaks ?: FloatArray(0),
                                audioPeakMax = aProbe?.peakMax ?: 0f
                            )
                        }
                    }
                    StudioMode.IMAGE -> {
                        val imgProbe = MediaEngine.probeImage(appContext, uri)
                        val preview = loadStaticPreviewBitmap(uri)
                        _uiState.update {
                            it.copy(
                                isProbingVisual = false,
                                visualUri = uri,
                                visualName = fileName,
                                visualWidth = imgProbe.width,
                                visualHeight = imgProbe.height,
                                isAnimatedImageWarning = imgProbe.animated,
                                isGif = false,
                                visualPreviewBitmap = preview
                            )
                        }
                    }
                    StudioMode.MIX -> {
                        val mime = appContext.contentResolver.getType(uri).orEmpty()
                        val isImageOrGif = mime.startsWith("image/") ||
                            fileName.endsWith(".gif", true) ||
                            fileName.endsWith(".webp", true)
                        if (isImageOrGif) {
                            val imgProbe = MediaEngine.probeImage(appContext, uri)
                            require(imgProbe.animated) { "Selected image is static; choose an animated GIF or video." }
                            val preview = loadStaticPreviewBitmap(uri)
                            _uiState.update {
                                it.copy(
                                    isProbingVisual = false,
                                    visualUri = uri,
                                    visualName = fileName,
                                    visualWidth = imgProbe.width,
                                    visualHeight = imgProbe.height,
                                    visualDurationSec = imgProbe.gifDurationSec,
                                    isGif = true,
                                    gifFrames = imgProbe.gifFrames,
                                    visualStartSec = 0.0,
                                    visualEndSec = imgProbe.gifDurationSec,
                                    visualCursorSec = 0.0,
                                    visualPreviewBitmap = preview
                                )
                            }
                        } else {
                            val vProbe = MediaEngine.probeVideo(appContext, uri)
                            val defaultEnd = min(vProbe.durationSec, 10.0)
                            val thumb = extractVideoFrame(uri, 0.0)
                            _uiState.update {
                                it.copy(
                                    isProbingVisual = false,
                                    visualUri = uri,
                                    visualName = fileName,
                                    visualWidth = vProbe.width,
                                    visualHeight = vProbe.height,
                                    visualDurationSec = vProbe.durationSec,
                                    isGif = false,
                                    visualStartSec = 0.0,
                                    visualEndSec = defaultEnd,
                                    visualCursorSec = 0.0,
                                    visualPreviewBitmap = thumb
                                )
                            }
                        }
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isProbingVisual = false, errorMessage = err.message ?: "Failed to read media")
                }
            }
        }
    }

    fun onAudioPicked(uri: Uri) {
        stopPlayback()
        val fileName = resolveFileName(uri)
        _uiState.update {
            it.copy(
                isProbingAudio = true,
                errorMessage = null,
                statusBanner = null
            )
        }
        viewModelScope.launch {
            runCatching {
                val probe = AudioProcessor.probeAudio(appContext, uri)
                val defaultEnd = min(probe.durationSec, 10.0)
                _uiState.update {
                    it.copy(
                        isProbingAudio = false,
                        audioUri = uri,
                        audioName = fileName,
                        audioDurationSec = probe.durationSec,
                        audioPeaks = probe.peaks,
                        audioPeakMax = probe.peakMax,
                        audioStartSec = 0.0,
                        audioEndSec = defaultEnd,
                        audioCursorSec = 0.0
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isProbingAudio = false, errorMessage = err.message ?: "Failed to decode audio")
                }
            }
        }
    }

    fun updateVisualTrim(startSec: Double, endSec: Double, cursorSec: Double) {
        val dur = _uiState.value.visualDurationSec
        if (dur <= 0.0) return
        val s = startSec.coerceIn(0.0, (dur - 0.3).coerceAtLeast(0.0))
        val e = endSec.coerceIn((s + 0.3).coerceAtMost(dur), dur)
        val c = cursorSec.coerceIn(0.0, dur)
        _uiState.update { it.copy(visualStartSec = s, visualEndSec = e, visualCursorSec = c) }
        scheduleFrameScrub(c)
    }

    fun updateVisualCursor(cursorSec: Double) {
        val dur = _uiState.value.visualDurationSec
        if (dur <= 0.0) return
        val c = cursorSec.coerceIn(0.0, dur)
        _uiState.update { it.copy(visualCursorSec = c) }
        scheduleFrameScrub(c)
    }

    fun updateAudioTrim(startSec: Double, endSec: Double, cursorSec: Double) {
        val dur = _uiState.value.audioDurationSec
        if (dur <= 0.0) return
        val s = startSec.coerceIn(0.0, (dur - 0.3).coerceAtLeast(0.0))
        val e = endSec.coerceIn((s + 0.3).coerceAtMost(dur), dur)
        val c = cursorSec.coerceIn(0.0, dur)
        _uiState.update { it.copy(audioStartSec = s, audioEndSec = e, audioCursorSec = c) }
    }

    fun updateAudioCursor(cursorSec: Double) {
        val dur = _uiState.value.audioDurationSec
        if (dur <= 0.0) return
        _uiState.update { it.copy(audioCursorSec = cursorSec.coerceIn(0.0, dur)) }
    }

    fun setMixFitMode(mode: MixFitMode) {
        _uiState.update { it.copy(mixFitMode = mode) }
    }

    fun setFramingMode(mode: FramingMode) {
        _uiState.update { it.copy(framingMode = mode) }
    }

    fun toggleMatrixVisible() {
        _uiState.update { it.copy(showMatrix = !it.showMatrix) }
    }

    fun selectGridCell(side: Int, fps: Int) {
        _uiState.update { it.copy(selectedSide = side, selectedFps = fps) }
    }

    fun setSyncOffsetMs(ms: Int) {
        val clamped = ms.coerceIn(0, 1000)
        _uiState.update { it.copy(syncOffsetMs = clamped) }
        viewModelScope.launch { prefsRepo.setSyncOffsetMs(clamped) }
    }

    fun setStillExperimental(enabled: Boolean) {
        _uiState.update { it.copy(stillExperimental = enabled) }
    }

    fun updateMetadata(packName: String, publisher: String) {
        _uiState.update { it.copy(packName = packName, publisher = publisher) }
        viewModelScope.launch { prefsRepo.saveMetadataDefaults(packName, publisher) }
    }

    fun playAudioRange(fromSec: Double, toSec: Double, forVisualTrim: Boolean) {
        val st = _uiState.value
        val uri = if (forVisualTrim) st.visualUri else st.audioUri
        if (uri == null) return
        stopPlayback()
        val startMs = (fromSec * 1000).toInt().coerceAtLeast(0)
        val endMs = (toSec * 1000).toInt().coerceAtLeast(startMs + 100)
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(appContext, uri)
                prepare()
                seekTo(startMs)
                start()
            }
            mediaPlayer = mp
            _uiState.update { it.copy(isPlayingAudio = true) }
            playTickerJob = viewModelScope.launch {
                while (mp.isPlaying) {
                    val curSec = mp.currentPosition / 1000.0
                    if (forVisualTrim) {
                        _uiState.update { it.copy(visualCursorSec = curSec) }
                    } else {
                        _uiState.update { it.copy(audioCursorSec = curSec) }
                    }
                    if (mp.currentPosition >= endMs) {
                        stopPlayback()
                        break
                    }
                    delay(33L)
                }
            }
        }.onFailure {
            stopPlayback()
        }
    }

    fun playGeneratedAudioPreview() {
        val st = _uiState.value
        val result = st.generatedSticker ?: return
        stopPlayback()
        viewModelScope.launch {
            runCatching {
                val tempAudio = File(appContext.cacheDir, "preview_sticker.ogg")
                if (result.oggBytes != null) {
                    tempAudio.writeBytes(result.oggBytes)
                    val mp = MediaPlayer().apply {
                        setDataSource(tempAudio.absolutePath)
                        prepare()
                        setOnCompletionListener {
                            _uiState.update { it.copy(isPlayingStickerPreview = false) }
                        }
                        start()
                    }
                    mediaPlayer = mp
                    _uiState.update { it.copy(isPlayingStickerPreview = true) }
                } else if (st.effectiveAudioUri != null) {
                    playAudioRange(
                        st.effectiveAudioStart,
                        st.effectiveAudioStart + st.effectiveAudioLen,
                        forVisualTrim = st.mode == StudioMode.VIDEO
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isPlayingStickerPreview = false) }
            }
        }
    }

    fun stopPlayback() {
        playTickerJob?.cancel()
        playTickerJob = null
        runCatching {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        mediaPlayer = null
        _uiState.update { it.copy(isPlayingAudio = false, isPlayingStickerPreview = false) }
    }

    fun runAnalysis() {
        val st = _uiState.value
        val vUri = st.visualUri ?: return
        if (!st.sourcesReady || st.isAnalyzing || st.isCreating) return
        stopPlayback()
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzing = true,
                    analyzeProgress = 0.02f,
                    errorMessage = null,
                    statusBanner = null
                )
            }
            val key = st.computeAnalysisKey()
            runCatching {
                val sourceKind = when (st.mode) {
                    StudioMode.VIDEO -> SourceKind.VIDEO
                    StudioMode.IMAGE -> SourceKind.IMAGE
                    StudioMode.MIX -> if (st.isGif) SourceKind.MIX_GIF else SourceKind.MIX_VIDEO
                }
                val result = MediaEngine.analyze(
                    context = appContext,
                    sourceKind = sourceKind,
                    visualUri = vUri,
                    visualStartSec = st.visualStartSec,
                    visualDurationSec = st.visualClipLen,
                    audioDurationSec = st.effectiveAudioLen,
                    framingMode = st.framingMode,
                    fitMode = st.mixFitMode,
                    onProgress = { p -> _uiState.update { it.copy(analyzeProgress = p) } }
                )
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analyzeProgress = 1f,
                        analysis = result,
                        analysisKey = key,
                        selectedSide = result.recommended?.side,
                        selectedFps = result.recommended?.fps
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analyzeProgress = 0f,
                        errorMessage = err.message ?: "Analysis failed"
                    )
                }
            }
        }
    }

    fun createSticker() {
        val st = _uiState.value
        val vUri = st.visualUri ?: return
        val aUri = st.effectiveAudioUri ?: return
        if (!st.sourcesReady || st.isAnalyzing || st.isCreating) return
        stopPlayback()
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCreating = true,
                    createProgress = 0.05f,
                    errorMessage = null,
                    statusBanner = null
                )
            }
            runCatching {
                // Auto-run fast multi-core analysis if not yet computed or stale
                val currentAnalysis: AnalysisResult
                val side: Int
                val fps: Int
                if (st.isAnalysisFresh && st.analysis != null && st.selectedSide != null && st.selectedFps != null) {
                    currentAnalysis = st.analysis
                    side = st.selectedSide
                    fps = st.selectedFps
                } else {
                    val key = st.computeAnalysisKey()
                    val sourceKind = when (st.mode) {
                        StudioMode.VIDEO -> SourceKind.VIDEO
                        StudioMode.IMAGE -> SourceKind.IMAGE
                        StudioMode.MIX -> if (st.isGif) SourceKind.MIX_GIF else SourceKind.MIX_VIDEO
                    }
                    val analyzed = MediaEngine.analyze(
                        context = appContext,
                        sourceKind = sourceKind,
                        visualUri = vUri,
                        visualStartSec = st.visualStartSec,
                        visualDurationSec = st.visualClipLen,
                        audioDurationSec = st.effectiveAudioLen,
                        framingMode = st.framingMode,
                        fitMode = st.mixFitMode,
                        onProgress = { p -> _uiState.update { it.copy(createProgress = p * 0.45f) } }
                    )
                    val rec = analyzed.recommended ?: error("No option fits under 1 MB. Shorten clip.")
                    currentAnalysis = analyzed
                    side = rec.side
                    fps = rec.fps
                    _uiState.update {
                        it.copy(
                            analysis = analyzed,
                            analysisKey = key,
                            selectedSide = side,
                            selectedFps = fps
                        )
                    }
                }

                val generated = MediaEngine.createSticker(
                    context = appContext,
                    analysis = currentAnalysis,
                    side = side,
                    fps = fps,
                    audioUri = aUri,
                    audioStartSec = st.effectiveAudioStart,
                    audioDurationSec = st.effectiveAudioLen,
                    syncOffsetMs = st.syncOffsetMs,
                    stillExperimental = st.stillExperimental,
                    packName = st.packName,
                    publisher = st.publisher,
                    onProgress = { p -> _uiState.update { it.copy(createProgress = 0.45f + p * 0.55f) } }
                )

                val latestState = _uiState.value
                _uiState.update {
                    it.copy(
                        isCreating = false,
                        createProgress = 1f,
                        generatedSticker = generated,
                        generatedKey = latestState.computeFullKey()
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCreating = false,
                        createProgress = 0f,
                        errorMessage = err.message ?: "Creation failed"
                    )
                }
            }
        }
    }

    fun cancelActiveTask() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update {
            it.copy(
                isAnalyzing = false,
                isCreating = false,
                analyzeProgress = 0f,
                createProgress = 0f
            )
        }
    }

    fun saveToPack(pack: StickerPackEntity, successMessage: String) {
        val st = _uiState.value
        val generated = st.generatedSticker ?: return
        viewModelScope.launch {
            runCatching {
                val updatedWebp = if (pack.name != generated.packName || pack.publisher != generated.publisher) {
                    WebpMuxer.retagWebp(generated.webpBytes, pack.name, pack.publisher, generated.soundSha256)
                } else {
                    generated.webpBytes
                }
                val ogg = generated.oggBytes ?: ByteArray(0)
                val thumb = generated.previewFrames.firstOrNull()
                packRepo.saveGeneratedStickerToPack(
                    packId = pack.packId,
                    webpBytes = updatedWebp,
                    oggBytes = ogg,
                    mp4File = generated.mp4File,
                    thumbnailBitmap = thumb,
                    sourceKind = st.mode.name,
                    soundSha256 = generated.soundSha256,
                    durationMs = (generated.durationSec * 1000).toLong(),
                    audioDurationMs = (generated.audioDurationSec * 1000).toLong(),
                    side = generated.side,
                    fps = generated.fps,
                    isAnimated = generated.isAnimated
                )
            }.onSuccess {
                _uiState.update { it.copy(statusBanner = successMessage) }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun createNewPackAndSave(name: String, publisher: String, successMessage: String) {
        viewModelScope.launch {
            runCatching {
                val pack = packRepo.createPack(name, publisher)
                saveToPack(pack, successMessage)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun exportAndShareMp4(chooserTitle: String, shareDirectlyToWhatsApp: Boolean, galleryMsg: String) {
        val st = _uiState.value
        val generated = st.generatedSticker ?: return
        val analysis = st.analysis ?: return
        val aUri = st.effectiveAudioUri ?: return
        if (st.isExportingMp4) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExportingMp4 = true, errorMessage = null, statusBanner = null) }
            runCatching {
                val mp4File = generated.mp4File?.takeIf { it.exists() } ?: run {
                    val out = File(appContext.cacheDir, "exports/wa_sound_sticker_${System.currentTimeMillis()}.mp4")
                    val pcm = AudioProcessor.extractPcm48kMono(
                        context = appContext,
                        uri = aUri,
                        startSec = st.effectiveAudioStart,
                        durationSec = st.effectiveAudioLen,
                        fadeMs = 8
                    )
                    Mp4StickerExporter.exportMp4(
                        outFile = out,
                        frames = analysis.frames,
                        captureFps = analysis.captureFps,
                        targetSide = max(generated.side, 256),
                        targetFps = max(generated.fps, 15),
                        durationSec = max(generated.durationSec, generated.audioDurationSec),
                        pcm48kMono = pcm
                    )
                }
                _uiState.update {
                    it.copy(
                        isExportingMp4 = false,
                        generatedSticker = generated.copy(mp4File = mp4File)
                    )
                }
                if (shareDirectlyToWhatsApp) {
                    WhatsAppBridge.shareMp4ToWhatsApp(appContext, mp4File, chooserTitle)
                        .onFailure { err -> _uiState.update { it.copy(errorMessage = err.message) } }
                } else {
                    WhatsAppBridge.saveMp4ToGallery(
                        appContext,
                        mp4File,
                        st.packName.ifEmpty { "audio_sticker" }
                    ).onSuccess {
                        _uiState.update { it.copy(statusBanner = galleryMsg) }
                    }.onFailure { err ->
                        _uiState.update { it.copy(errorMessage = err.message) }
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isExportingMp4 = false, errorMessage = err.message ?: "MP4 export failed")
                }
            }
        }
    }

    fun shareRawPair(chooserTitle: String, doneMsg: String) {
        val generated = _uiState.value.generatedSticker ?: return
        viewModelScope.launch {
            runCatching {
                val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                val webpFile = File(exportDir, "sticker_${System.currentTimeMillis()}.webp").apply {
                    writeBytes(generated.webpBytes)
                }
                val oggFile = File(exportDir, "sticker_${System.currentTimeMillis()}.ogg").apply {
                    writeBytes(generated.oggBytes ?: ByteArray(0))
                }
                WhatsAppBridge.shareRawPair(appContext, webpFile, oggFile, chooserTitle)
                    .getOrThrow()
                _uiState.update { it.copy(statusBanner = doneMsg) }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, statusBanner = null) }
    }

    private fun scheduleFrameScrub(cursorSec: Double) {
        val st = _uiState.value
        if (st.mode == StudioMode.IMAGE || st.isGif || st.visualUri == null) return
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            delay(40L)
            val bmp = extractVideoFrame(st.visualUri, cursorSec)
            if (bmp != null) {
                _uiState.update { it.copy(visualPreviewBitmap = bmp) }
            }
        }
    }

    private suspend fun extractVideoFrame(uri: Uri, sec: Double): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val timeUs = (sec * 1_000_000.0).toLong().coerceAtLeast(0L)
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun loadStaticPreviewBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    private fun resolveFileName(uri: Uri): String {
        val cursor = appContext.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) {
                return it.getString(idx) ?: "media"
            }
        }
        return uri.lastPathSegment ?: "media"
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        activeJob?.cancel()
    }
}
