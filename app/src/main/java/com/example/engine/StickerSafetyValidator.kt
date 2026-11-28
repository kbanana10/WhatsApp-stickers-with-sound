package com.example.engine

import android.util.Base64
import java.security.MessageDigest

/**
 * Pre-export validation to ensure stickers meet WhatsApp's strict limits.
 * Call this BEFORE saving files to prevent rejection by WhatsApp.
 */
object StickerSafetyValidator {

    fun validate(webpBytes: ByteArray, opusBytes: ByteArray, durationSec: Double): Result<Unit> {
        if (webpBytes.size > StickerLimits.SAFE_BYTES) {
            return Result.failure(IllegalArgumentException(
                "حجم صورة WebP يتجاوز الحد الآمن (${StickerLimits.SAFE_BYTES / 1000} كيلوبايت). الحجم الحالي: ${webpBytes.size / 1000} كيلوبايت"
            ))
        }
        if (opusBytes.size > StickerLimits.SAFE_AUDIO_BYTES) {
            return Result.failure(IllegalArgumentException(
                "حجم ملف الصوت يتجاوز الحد الآمن (${StickerLimits.SAFE_AUDIO_BYTES / 1000} كيلوبايت). الحجم الحالي: ${opusBytes.size / 1000} كيلوبايت"
            ))
        }
        if (durationSec > StickerLimits.MAX_DURATION_SECONDS) {
            return Result.failure(IllegalArgumentException(
                "مدة الصوت تتجاوز ${StickerLimits.MAX_DURATION_SECONDS} ثوانٍ المسموحة من واتساب"
            ))
        }
        if (durationSec < StickerLimits.MIN_DURATION_SECONDS) {
            return Result.failure(IllegalArgumentException(
                "مدة الصوت أقل من ${StickerLimits.MIN_DURATION_SECONDS} ثانية"
            ))
        }

        // Verify OGG Opus header
        if (opusBytes.size < 28) {
            return Result.failure(IllegalArgumentException("ملف الصوت صغير جداً أو تالف"))
        }
        val oggHeader = String(opusBytes, 0, 4, Charsets.US_ASCII)
        if (oggHeader != "OggS") {
            return Result.failure(IllegalArgumentException("ملف الصوت يجب أن يكون بصيغة OGG Opus"))
        }

        // Verify WebP header
        if (webpBytes.size < 12) {
            return Result.failure(IllegalArgumentException("ملف الصورة صغير جداً أو تالف"))
        }
        val riff = String(webpBytes, 0, 4, Charsets.US_ASCII)
        val webp = String(webpBytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || webp != "WEBP") {
            return Result.failure(IllegalArgumentException("ملف الصورة يجب أن يكون بصيغة WebP"))
        }

        return Result.success(Unit)
    }

    /** Compute SHA-256 of Opus bytes and return Standard Base64 (for EXIF tag) */
    fun computeOpusSha256Base64(opusBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(opusBytes)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /** Compute WhatsApp-compatible filename: Base64(SHA-256) with '/' replaced by '-' */
    fun computeOpusFileName(opusBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(opusBytes)
        val base64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return "${base64.replace('/', '-')}.opus"
    }

    /** Compute WhatsApp-compatible WebP filename */
    fun computeWebpFileName(webpBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(webpBytes)
        val base64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return "${base64.replace('/', '-')}.webp"
    }
}
