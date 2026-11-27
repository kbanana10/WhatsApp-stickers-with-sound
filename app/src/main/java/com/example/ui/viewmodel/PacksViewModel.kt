package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.StickerEntity
import com.example.data.local.StickerPackWithStickers
import com.example.data.repository.StickerPackRepository
import com.example.whatsapp.RootPackMetadata
import com.example.whatsapp.RootStickerInput
import com.example.whatsapp.WhatsAppBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PacksViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val repository = StickerPackRepository(
        appContext,
        AppDatabase.getInstance(appContext).stickerDao()
    )

    val packs: StateFlow<List<StickerPackWithStickers>> = repository.packsWithStickers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _playingStickerId = MutableStateFlow<Long?>(null)
    val playingStickerId: StateFlow<Long?> = _playingStickerId.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    private val _isSuccessFeedback = MutableStateFlow(false)
    val isSuccessFeedback: StateFlow<Boolean> = _isSuccessFeedback.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _isInjectingRoot = MutableStateFlow(false)
    val isInjectingRoot: StateFlow<Boolean> = _isInjectingRoot.asStateFlow()

    private var player: MediaPlayer? = null

    init {
        viewModelScope.launch {
            _isRootAvailable.value = runCatching { WhatsAppBridge.isRootAvailable() }.getOrDefault(false)
        }
    }

    fun observePack(packId: String) = repository.observePack(packId)

    fun createPack(name: String, publisher: String) {
        viewModelScope.launch {
            repository.createPack(name, publisher)
        }
    }

    fun deletePack(packId: String) {
        stopAudio()
        viewModelScope.launch {
            repository.deletePack(packId)
        }
    }

    fun deleteSticker(sticker: StickerEntity) {
        if (_playingStickerId.value == sticker.id) stopAudio()
        viewModelScope.launch {
            repository.deleteSticker(sticker)
        }
    }

    fun addPackToWhatsApp(uiContext: Context, packId: String, packName: String, notInstalledMsg: String) {
        WhatsAppBridge.launchAddPackToWhatsApp(uiContext, packId, packName)
            .onFailure { err ->
                val isNotInstalled = err is android.content.ActivityNotFoundException
                _feedbackMessage.value = if (isNotInstalled) {
                    notInstalledMsg
                } else {
                    err.message ?: notInstalledMsg
                }
            }
    }

    fun shareStickerMp4OrRaw(uiContext: Context, sticker: StickerEntity, chooserTitle: String) {
        val mp4Name = sticker.mp4FileName
        if (mp4Name != null) {
            val mp4File = repository.stickerFile(sticker.packId, mp4Name)
            if (mp4File.exists()) {
                WhatsAppBridge.shareMp4ToWhatsApp(uiContext, mp4File, chooserTitle)
                    .onFailure { err -> _feedbackMessage.value = err.message }
                return
            }
        }
        val webpFile = repository.stickerFile(sticker.packId, sticker.fileName)
        val oggFile = repository.stickerFile(sticker.packId, sticker.oggFileName)
        WhatsAppBridge.shareRawPair(uiContext, webpFile, oggFile, chooserTitle)
            .onFailure { err -> _feedbackMessage.value = err.message }
    }

    fun playStickerSound(sticker: StickerEntity) {
        if (_playingStickerId.value == sticker.id) {
            stopAudio()
            return
        }
        stopAudio()
        val oggFile: File = repository.stickerFile(sticker.packId, sticker.oggFileName)
        if (!oggFile.exists() || oggFile.length() == 0L) return
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(oggFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    _playingStickerId.value = null
                }
                start()
            }
            player = mp
            _playingStickerId.value = sticker.id
        }.onFailure {
            stopAudio()
        }
    }

    fun stopAudio() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
        _playingStickerId.value = null
    }

    fun injectStickerWithRoot(
        sticker: StickerEntity,
        packName: String,
        publisher: String,
        successTemplate: String,
        missingOpusMsg: String
    ) {
        if (_isInjectingRoot.value) return
        viewModelScope.launch {
            _isInjectingRoot.value = true
            _feedbackMessage.value = null
            _isSuccessFeedback.value = false
            val input = withContext(Dispatchers.IO) {
                val webpFile = repository.stickerFile(sticker.packId, sticker.fileName)
                val oggFile = repository.stickerFile(sticker.packId, sticker.oggFileName)
                if (!webpFile.exists() || !oggFile.exists() || oggFile.length() == 0L) {
                    null
                } else {
                    RootStickerInput(
                        webpBytes = webpFile.readBytes(),
                        opusBytes = oggFile.readBytes(),
                        durationSec = maxOf(sticker.audioDurationMs, sticker.durationMs) / 1000.0,
                        side = sticker.side,
                        emojis = sticker.emojis,
                        packName = packName.ifBlank { "Audio Stickers" },
                        publisher = publisher.ifBlank { "Audio Stickers" }
                    )
                }
            }
            if (input == null) {
                _isInjectingRoot.value = false
                _feedbackMessage.value = missingOpusMsg
                return@launch
            }
            WhatsAppBridge.injectSoundStickerWithRoot(appContext, input, relaunchWhatsApp = false)
                .onSuccess { res ->
                    _isInjectingRoot.value = false
                    _isRootAvailable.value = true
                    _isSuccessFeedback.value = true
                    _feedbackMessage.value = successTemplate.format(res.packageName)
                }
                .onFailure { err ->
                    _isInjectingRoot.value = false
                    _isSuccessFeedback.value = false
                    _feedbackMessage.value = err.message ?: "Root injection failed"
                }
        }
    }

    fun injectPackWithRoot(
        packWithStickers: StickerPackWithStickers,
        successTemplate: String,
        noAudioPackMsg: String
    ) {
        if (_isInjectingRoot.value) return
        viewModelScope.launch {
            _isInjectingRoot.value = true
            _feedbackMessage.value = null
            _isSuccessFeedback.value = false
            val pack = packWithStickers.pack
            val inputs = withContext(Dispatchers.IO) {
                packWithStickers.stickers.mapNotNull { sticker ->
                    val webpFile = repository.stickerFile(sticker.packId, sticker.fileName)
                    val oggFile = repository.stickerFile(sticker.packId, sticker.oggFileName)
                    if (!webpFile.exists() || !oggFile.exists() || oggFile.length() == 0L) {
                        null
                    } else {
                        RootStickerInput(
                            webpBytes = webpFile.readBytes(),
                            opusBytes = oggFile.readBytes(),
                            durationSec = maxOf(sticker.audioDurationMs, sticker.durationMs) / 1000.0,
                            side = sticker.side,
                            emojis = sticker.emojis,
                            packName = pack.name,
                            publisher = pack.publisher
                        )
                    }
                }
            }
            if (inputs.isEmpty()) {
                _isInjectingRoot.value = false
                _feedbackMessage.value = noAudioPackMsg
                return@launch
            }
            WhatsAppBridge.injectSoundPackWithRoot(
                context = appContext,
                packMetadata = RootPackMetadata(pack.packId, pack.name, pack.publisher),
                stickers = inputs,
                relaunchWhatsApp = false
            ).onSuccess { res ->
                _isInjectingRoot.value = false
                _isRootAvailable.value = true
                _isSuccessFeedback.value = true
                _feedbackMessage.value = successTemplate.format(res.injectedCount, res.packageName)
            }.onFailure { err ->
                _isInjectingRoot.value = false
                _isSuccessFeedback.value = false
                _feedbackMessage.value = err.message ?: "Root injection failed"
            }
        }
    }

    fun clearFeedback() {
        _feedbackMessage.update { null }
        _isSuccessFeedback.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }
}
