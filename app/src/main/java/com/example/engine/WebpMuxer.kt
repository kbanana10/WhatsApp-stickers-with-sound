package com.example.engine

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Pure Kotlin RIFF/WebP container builder & WhatsApp EXIF metadata injector.
 * Produces animated and static WebP payloads identical to WhatsApp's sticker specification.
 */
object WebpMuxer {

    data class RiffChunk(val fourcc: String, val data: ByteArray)

    data class EncodedFrame(
        val payload: ByteArray,
        val hasAlpha: Boolean,
        val durationMs: Int = 100
    )

    fun readChunks(bytes: ByteArray): List<RiffChunk> {
        require(bytes.size >= 12) { "Invalid WebP: too small" }
        val riffTag = String(bytes, 0, 4, Charsets.US_ASCII)
        val webpTag = String(bytes, 8, 4, Charsets.US_ASCII)
        require(riffTag == "RIFF" && webpTag == "WEBP") { "Invalid WebP RIFF header" }

        val chunks = mutableListOf<RiffChunk>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val fourcc = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buf.getInt(offset + 4)
            if (size < 0 || offset + 8 + size > bytes.size) break
            val data = bytes.copyOfRange(offset + 8, offset + 8 + size)
            chunks.add(RiffChunk(fourcc, data))
            offset += 8 + size + (size and 1)
        }
        return chunks
    }

    fun encodeStaticFrame(bitmap: Bitmap, quality: Float): EncodedFrame {
        val qInt = (quality * 100f).roundToInt().coerceIn(1, 100)
        val baos = ByteArrayOutputStream(16 * 1024)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        check(bitmap.compress(format, qInt, baos)) { "Bitmap WebP compression failed" }
        val raw = baos.toByteArray()
        val imageChunks = readChunks(raw).filter {
            it.fourcc == "ALPH" || it.fourcc == "VP8 " || it.fourcc == "VP8L"
        }
        check(imageChunks.any { it.fourcc == "VP8 " || it.fourcc == "VP8L" }) {
            "WebP frame contains no VP8/VP8L image data"
        }
        val payload = concatChunks(imageChunks)
        val hasAlpha = imageChunks.any { it.fourcc == "ALPH" || it.fourcc == "VP8L" }
        return EncodedFrame(payload = payload, hasAlpha = hasAlpha)
    }

    fun buildAnimatedWebp(frames: List<EncodedFrame>, exif: ByteArray?, side: Int): ByteArray {
        require(frames.isNotEmpty()) { "Animated WebP requires at least one frame" }
        val anyAlpha = frames.any { it.hasAlpha }
        val vp8x = ByteArray(10)
        vp8x[0] = (0x02 or (if (anyAlpha) 0x10 else 0) or (if (exif != null) 0x08 else 0)).toByte()
        writeU24(vp8x, 4, side - 1)
        writeU24(vp8x, 7, side - 1)

        val anim = ByteArray(6) // transparent bg (0), infinite loop (0)
        val parts = mutableListOf<ByteArray>()
        parts.add(makeChunk("VP8X", vp8x))
        parts.add(makeChunk("ANIM", anim))

        for (frame in frames) {
            val hdr = ByteArray(16)
            writeU24(hdr, 0, 0)
            writeU24(hdr, 3, 0)
            writeU24(hdr, 6, side - 1)
            writeU24(hdr, 9, side - 1)
            writeU24(hdr, 12, frame.durationMs.coerceIn(1, 0xFFFFFF))
            hdr[15] = 0x02 // do not blend, do not dispose
            val anmfData = ByteArray(hdr.size + frame.payload.size)
            System.arraycopy(hdr, 0, anmfData, 0, hdr.size)
            System.arraycopy(frame.payload, 0, anmfData, hdr.size, frame.payload.size)
            parts.add(makeChunk("ANMF", anmfData))
        }
        if (exif != null) {
            parts.add(makeChunk("EXIF", exif))
        }
        return wrapRiffWebp(parts)
    }

    fun buildStillWebp(frame: EncodedFrame, exif: ByteArray?, side: Int): ByteArray {
        val vp8x = ByteArray(10)
        vp8x[0] = ((if (frame.hasAlpha) 0x10 else 0) or (if (exif != null) 0x08 else 0)).toByte()
        writeU24(vp8x, 4, side - 1)
        writeU24(vp8x, 7, side - 1)
        val parts = mutableListOf<ByteArray>()
        parts.add(makeChunk("VP8X", vp8x))
        parts.add(frame.payload)
        if (exif != null) {
            parts.add(makeChunk("EXIF", exif))
        }
        return wrapRiffWebp(parts)
    }

    fun exifWhatsApp(packName: String, publisher: String, soundSha256: String?): ByteArray {
        val cleanPack = packName.trim().take(128)
        val cleanPub = publisher.trim().take(128)
        val packId = packIdFor(cleanPack, cleanPub)
        val json = buildString {
            append("{\"sticker-pack-id\":")
            append(escapeJson(packId))
            if (cleanPack.isNotEmpty()) {
                append(",\"sticker-pack-name\":")
                append(escapeJson(cleanPack))
            }
            if (cleanPub.isNotEmpty()) {
                append(",\"sticker-pack-publisher\":")
                append(escapeJson(cleanPub))
            }
            append(",\"emojis\":[\"\\uD83D\\uDD0A\"]")
            if (!soundSha256.isNullOrEmpty()) {
                append(",\"sound-sha256\":")
                append(escapeJson(soundSha256))
                append(",\"is-from-user-created-pack\":1")
            }
            append('}')
        }
        val js = json.toByteArray(Charsets.UTF_8)
        val out = ByteArray(26 + js.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        out[0] = 0x4D; out[1] = 0x4D; out[2] = 0x00; out[3] = 0x2A
        buf.putInt(4, 8)
        buf.putShort(8, 1)
        buf.putShort(10, 0x5741.toShort()) // "WA" tag
        buf.putShort(12, 7)
        buf.putInt(14, js.size)
        buf.putInt(18, 26)
        buf.putInt(22, 0)
        System.arraycopy(js, 0, out, 26, js.size)
        return out
    }

    private fun escapeJson(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    fun retagWebp(webpBytes: ByteArray, packName: String, publisher: String, soundSha256: String?): ByteArray {
        val chunks = readChunks(webpBytes).filter { it.fourcc != "EXIF" }.toMutableList()
        val vp8xIndex = chunks.indexOfFirst { it.fourcc == "VP8X" }
        if (vp8xIndex >= 0) {
            val d = chunks[vp8xIndex].data.copyOf()
            d[0] = (d[0].toInt() or 0x08).toByte()
            chunks[vp8xIndex] = RiffChunk("VP8X", d)
        }
        val exif = exifWhatsApp(packName, publisher, soundSha256)
        val parts = chunks.map { makeChunk(it.fourcc, it.data) } + makeChunk("EXIF", exif)
        return wrapRiffWebp(parts)
    }

    private fun packIdFor(packName: String, publisher: String): String {
        if (packName.isEmpty() && publisher.isEmpty()) return UUID.randomUUID().toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("stickers-con-sonido|$packName|$publisher".toByteArray(Charsets.UTF_8))
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun wrapRiffWebp(parts: List<ByteArray>): ByteArray {
        val bodySize = parts.sumOf { it.size }
        val out = ByteArray(12 + bodySize)
        System.arraycopy("RIFF".toByteArray(Charsets.US_ASCII), 0, out, 0, 4)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 4 + bodySize)
        System.arraycopy("WEBP".toByteArray(Charsets.US_ASCII), 0, out, 8, 4)
        var offset = 12
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    private fun concatChunks(chunks: List<RiffChunk>): ByteArray {
        val encoded = chunks.map { makeChunk(it.fourcc, it.data) }
        val total = encoded.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (c in encoded) {
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        return out
    }

    private fun makeChunk(fourcc: String, data: ByteArray): ByteArray {
        val pad = data.size and 1
        val out = ByteArray(8 + data.size + pad)
        System.arraycopy(fourcc.toByteArray(Charsets.US_ASCII), 0, out, 0, 4)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, data.size)
        System.arraycopy(data, 0, out, 8, data.size)
        return out
    }

    private fun writeU24(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }
}
