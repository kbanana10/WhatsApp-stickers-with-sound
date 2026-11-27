package com.example

import com.example.engine.OggOpusMuxer
import com.example.engine.StickerLimits
import com.example.engine.WebpMuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxerUnitTest {

    @Test
    fun animatedWebp_containsVp8xAnimAnmfAndExif() {
        val fakePayload = byteArrayOf(
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
            4, 0, 0, 0,
            1, 2, 3, 4
        )
        val frame = WebpMuxer.EncodedFrame(payload = fakePayload, hasAlpha = true, durationMs = 50)
        val exif = WebpMuxer.exifWhatsApp("My Pack", "Me", "abc123")
        val webp = WebpMuxer.buildAnimatedWebp(listOf(frame, frame), exif, 512)

        assertEquals("RIFF", String(webp, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(webp, 8, 4, Charsets.US_ASCII))

        val chunks = WebpMuxer.readChunks(webp)
        val tags = chunks.map { it.fourcc }
        assertTrue(tags.contains("VP8X"))
        assertTrue(tags.contains("ANIM"))
        assertEquals(2, tags.count { it == "ANMF" })
        assertTrue(tags.contains("EXIF"))
    }

    @Test
    fun retagWebp_replacesExifWithoutDroppingFrames() {
        val fakePayload = byteArrayOf(
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'L'.code.toByte(),
            4, 0, 0, 0,
            5, 6, 7, 8
        )
        val frame = WebpMuxer.EncodedFrame(payload = fakePayload, hasAlpha = true, durationMs = 80)
        val orig = WebpMuxer.buildAnimatedWebp(listOf(frame), WebpMuxer.exifWhatsApp("Old", "A", "sha1"), 256)
        val retagged = WebpMuxer.retagWebp(orig, "NewPack", "NewPub", "sha1")
        val chunks = WebpMuxer.readChunks(retagged)
        val exifChunk = chunks.first { it.fourcc == "EXIF" }
        val exifStr = String(exifChunk.data, Charsets.UTF_8)
        assertTrue(exifStr.contains("NewPack"))
        assertTrue(exifStr.contains("NewPub"))
        assertEquals(1, chunks.count { it.fourcc == "ANMF" })
    }

    @Test
    fun oggOpus_buildsValidOggHeaderAndOpusTags() {
        val packets = listOf(byteArrayOf(0x01, 0x02, 0x03), byteArrayOf(0x04, 0x05))
        val ogg = OggOpusMuxer.buildOggOpus(packets, preSkip = 312, totalSamples48k = 1920)
        assertEquals("OggS", String(ogg, 0, 4, Charsets.US_ASCII))
        val asAscii = String(ogg, Charsets.ISO_8859_1)
        assertTrue(asAscii.contains("OpusHead"))
        assertTrue(asAscii.contains("OpusTags"))
        assertTrue(asAscii.contains("WAAudioStickers"))
    }

    @Test
    fun stickerLimits_scoringAndBitrateBounds() {
        assertTrue(StickerLimits.score(512, 20) > StickerLimits.score(512, 10))
        assertTrue(StickerLimits.score(256, 20) > StickerLimits.score(128, 20))
        assertEquals(32_000, StickerLimits.computeTargetAudioBitrate(5.0))
        assertEquals(12_000, StickerLimits.computeTargetAudioBitrate(800.0))
    }
}
