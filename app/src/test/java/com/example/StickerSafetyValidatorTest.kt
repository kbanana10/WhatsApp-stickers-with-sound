package com.example

import com.example.engine.OggOpusMuxer
import com.example.engine.StickerLimits
import com.example.engine.StickerSafetyValidator
import com.example.engine.WebpMuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickerSafetyValidatorTest {

    private fun validWebp(soundSha: String): ByteArray {
        val fakePayload = byteArrayOf(
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
            4, 0, 0, 0,
            1, 2, 3, 4
        )
        val frame = WebpMuxer.EncodedFrame(payload = fakePayload, hasAlpha = false, durationMs = 500)
        return WebpMuxer.buildAnimatedWebp(
            frames = listOf(frame, frame),
            exif = WebpMuxer.exifWhatsApp("Pack", "Pub", soundSha),
            side = 512
        )
    }

    private fun validOpus(): ByteArray {
        return OggOpusMuxer.buildOggOpus(
            packets = listOf(byteArrayOf(0x01, 0x02, 0x03), byteArrayOf(0x04, 0x05)),
            preSkip = 312,
            totalSamples48k = 1920
        )
    }

    @Test
    fun validate_succeedsForCompliantWebpAndOpus() {
        val opus = validOpus()
        val sha = StickerSafetyValidator.computeOpusSha256Base64(opus)
        val webp = validWebp(sha)

        val result = StickerSafetyValidator.validate(webp, opus, 3.0)
        assertTrue(result.isSuccess)
    }

    @Test
    fun validate_rejectsOversizedOrInvalidMediaAndDuration() {
        val opus = validOpus()
        val webp = validWebp(StickerSafetyValidator.computeOpusSha256Base64(opus))

        val oversizedWebp = ByteArray(StickerLimits.SAFE_BYTES + 1)
        System.arraycopy(webp, 0, oversizedWebp, 0, webp.size)
        assertTrue(StickerSafetyValidator.validate(oversizedWebp, opus, 2.0).isFailure)

        val oversizedOpus = ByteArray(StickerLimits.SAFE_AUDIO_BYTES + 1)
        System.arraycopy(opus, 0, oversizedOpus, 0, opus.size)
        assertTrue(StickerSafetyValidator.validate(webp, oversizedOpus, 2.0).isFailure)

        assertTrue(StickerSafetyValidator.validate(webp, opus, 0.5).isFailure)
        assertTrue(StickerSafetyValidator.validate(webp, opus, 11.0).isFailure)
        assertTrue(StickerSafetyValidator.validate(ByteArray(32), opus, 2.0).isFailure)
        assertTrue(StickerSafetyValidator.validate(webp, ByteArray(32), 2.0).isFailure)
    }

    @Test
    fun filenames_replaceSlashesWithHyphensAndUseExpectedExtensions() {
        // Construct input whose SHA-256 contains '/'? Verify contract unconditionally
        val opus = validOpus()
        val sha = StickerSafetyValidator.computeOpusSha256Base64(opus)
        val opusName = StickerSafetyValidator.computeOpusFileName(opus)
        assertEquals("${sha.replace('/', '-')}.opus", opusName)
        assertFalse(opusName.contains('/'))

        val webp = validWebp(sha)
        val webpName = StickerSafetyValidator.computeWebpFileName(webp)
        assertTrue(webpName.endsWith(".webp"))
        assertFalse(webpName.contains('/'))
    }
}
