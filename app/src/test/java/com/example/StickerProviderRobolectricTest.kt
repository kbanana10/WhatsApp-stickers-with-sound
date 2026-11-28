package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.StickerPackRepository
import com.example.engine.OggOpusMuxer
import com.example.engine.StickerSafetyValidator
import com.example.engine.WebpMuxer
import com.example.whatsapp.StickerContentProvider
import com.example.whatsapp.WhatsAppBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickerProviderRobolectricTest {

    @Test
    fun contentProvider_servesPackMetadataWebpAndOpusAudioOnceThreeAdded() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repo = StickerPackRepository(context, db.stickerDao())
        val pack = repo.createPack("Test Pack", "Tester")

        val fakeVp8Payload = byteArrayOf(
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
            4, 0, 0, 0,
            1, 2, 3, 4
        )

        var lastOpusFileName = ""
        repeat(3) { idx ->
            val opusBytes = OggOpusMuxer.buildOggOpus(
                packets = listOf(byteArrayOf(0x01, 0x02, (idx + 1).toByte()), byteArrayOf(0x04, 0x05)),
                preSkip = 312,
                totalSamples48k = 1920
            )
            val sha = StickerSafetyValidator.computeOpusSha256Base64(opusBytes)
            val frame = WebpMuxer.EncodedFrame(payload = fakeVp8Payload, hasAlpha = false, durationMs = 1000)
            val webpBytes = WebpMuxer.buildAnimatedWebp(
                frames = listOf(frame, frame),
                exif = WebpMuxer.exifWhatsApp(pack.name, pack.publisher, sha),
                side = 512
            )
            val saved = repo.saveGeneratedStickerToPack(
                packId = pack.packId,
                webpBytes = webpBytes,
                oggBytes = opusBytes,
                mp4File = null,
                thumbnailBitmap = null,
                sourceKind = "VIDEO",
                soundSha256 = sha,
                durationMs = 2000L,
                audioDurationMs = 2000L,
                side = 512,
                fps = 20,
                isAnimated = true
            )
            lastOpusFileName = saved.oggFileName
            assertTrue(saved.fileName.endsWith(".webp"))
            assertTrue(saved.oggFileName.endsWith(".opus"))
        }

        val controller = Robolectric.buildContentProvider(StickerContentProvider::class.java)
            .create()
        val provider = controller.get()

        val metaUri = Uri.parse("content://${StickerContentProvider.AUTHORITY}/metadata")
        val cursor = withContext(Dispatchers.IO) {
            provider.query(metaUri, null, null, null, null)
        }
        assertNotNull(cursor)
        cursor!!.use {
            assertTrue(it.moveToFirst())
            val idIdx = it.getColumnIndex("sticker_pack_identifier")
            val nameIdx = it.getColumnIndex("sticker_pack_name")
            assertEquals(pack.packId, it.getString(idIdx))
            assertEquals("Test Pack", it.getString(nameIdx))
        }

        val stickersUri = Uri.parse("content://${StickerContentProvider.AUTHORITY}/stickers/${pack.packId}")
        var firstStickerFileName = ""
        val sCursor = withContext(Dispatchers.IO) {
            provider.query(stickersUri, null, null, null, null)
        }
        assertNotNull(sCursor)
        sCursor!!.use {
            assertEquals(3, it.count)
            assertTrue(it.moveToFirst())
            firstStickerFileName = it.getString(it.getColumnIndex("sticker_file_name"))
        }

        val trayUri = Uri.parse("content://${StickerContentProvider.AUTHORITY}/stickers_asset/${pack.packId}/${pack.trayIconFileName}")
        assertEquals("image/png", provider.getType(trayUri))
        provider.openAssetFile(trayUri, "r").use { afd ->
            assertNotNull(afd)
        }

        val stickerAssetUri = Uri.parse("content://${StickerContentProvider.AUTHORITY}/stickers_asset/${pack.packId}/$firstStickerFileName")
        assertEquals("image/webp", provider.getType(stickerAssetUri))
        provider.openAssetFile(stickerAssetUri, "r").use { afd ->
            assertNotNull(afd)
        }

        val opusAssetUri = Uri.parse("content://${StickerContentProvider.AUTHORITY}/stickers_asset/${pack.packId}/$lastOpusFileName")
        assertEquals("audio/ogg; codecs=opus", provider.getType(opusAssetUri))
        provider.openAssetFile(opusAssetUri, "r").use { afd ->
            assertNotNull(afd)
        }

        val intent = WhatsAppBridge.buildEnableStickerPackIntent(pack.packId, pack.name)
        assertEquals("com.whatsapp.intent.action.ENABLE_STICKER_PACK", intent.action)
        assertEquals(pack.packId, intent.getStringExtra("sticker_pack_id"))
        assertEquals(StickerContentProvider.AUTHORITY, intent.getStringExtra("sticker_pack_authority"))

        // Verify calling bridge with Application context never throws AndroidRuntimeException
        val tempMp4 = java.io.File(context.cacheDir, "exports/test_share.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0, 0, 0, 24))
        }
        val shareResult = WhatsAppBridge.shareMp4ToWhatsApp(context, tempMp4, "Share")
        assertTrue(shareResult.isSuccess)
        val addResult = WhatsAppBridge.launchAddPackToWhatsApp(context, pack.packId, pack.name)
        assertTrue(addResult.isFailure) // WhatsApp not installed in Robolectric, must return clean Result.failure without crashing
    }
}
