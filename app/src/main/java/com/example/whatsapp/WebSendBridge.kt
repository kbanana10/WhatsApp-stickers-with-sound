package com.example.whatsapp

import android.net.Uri

/** Bridge holding prepared WebP/OGG/metadata payloads for WhatsApp Web audio-sticker injection. */
object WebSendBridge {
    @Volatile var pendingStickerUri: Uri? = null
    @Volatile var pendingStickerBase64: String? = null
    @Volatile var pendingOggBase64: String? = null
    @Volatile var pendingInfoJson: String? = null
    @Volatile var autoSend: Boolean = true
}
