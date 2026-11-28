package com.example.whatsapp

import android.net.Uri

/** Bridge holding a prepared sticker for automatic paste/send or fallback file-chooser attachment in WhatsApp Web. */
object WebSendBridge {
    @Volatile var pendingStickerUri: Uri? = null
    @Volatile var pendingStickerBase64: String? = null
    @Volatile var autoSend: Boolean = true
    @Volatile var selfNumber: String = ""
    @Volatile var navigatedToSelf: Boolean = false
}
