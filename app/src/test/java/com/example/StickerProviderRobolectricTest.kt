package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.StickerPackRepository
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
    fun contentProvider_servesPackMetadataAndStickersOnceThreeAdded() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repo = StickerPackRepository(context, db.stickerDao())
        val pack = repo.createPack("Test Pack", "Tester")

        repeat(3) { idx ->
            repo.saveGeneratedStickerToPack(
                packId = pack.packId,
                webpBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, idx.toByte()),
                oggBytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53),
                mp4File = null,
                thumbnailBitmap = null,
                sourceKind = "VIDEO",
                soundSha256 = "sha_$idx",
                durationMs = 2000L,
                audioDurationMs = 2000L,
                side = 512,
                fps = 20,
                isAnimated = true
            )
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
