package com.example.whatsapp

import android.net.Uri

/** Simple in-memory bridge holding a sticker Uri queued from Studio for WhatsApp Web file chooser. */
object WebSendBridge {
    @Volatile
    var pendingStickerUri: Uri? = null
}
