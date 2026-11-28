package com.example.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri

object WhatsAppLauncher {
    /** Opens the native WhatsApp or WhatsApp Business app directly. */
    fun openWhatsApp(context: Context): Boolean {
        for (p in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val intent = context.packageManager.getLaunchIntentForPackage(p) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(intent) }.isSuccess
        }
        return false
    }

    /** Opens a chat for a phone number (use your own number to message yourself). */
    fun openChat(context: Context, phone: String): Boolean {
        val uri = Uri.parse("https://wa.me/${phone.filter { it.isDigit() }}")
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    /** Reads the saved self phone number used to auto-open "Message yourself" in WhatsApp Web. */
    fun getSelfNumber(context: Context): String =
        context.getSharedPreferences("wa_web", Context.MODE_PRIVATE)
            .getString("self_number", "") ?: ""

    /** Saves the user's own WhatsApp phone number (digits only). */
    fun setSelfNumber(context: Context, number: String) {
        context.getSharedPreferences("wa_web", Context.MODE_PRIVATE)
            .edit()
            .putString("self_number", number.filter { it.isDigit() })
            .apply()
    }
}
