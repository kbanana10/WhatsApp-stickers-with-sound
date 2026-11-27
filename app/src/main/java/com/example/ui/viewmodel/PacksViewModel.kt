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
import com.example.whatsapp.WhatsAppBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    private var player: MediaPlayer? = null

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

    fun clearFeedback() {
        _feedbackMessage.update { null }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }
}
