package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.engine.WebpMuxer
import com.example.whatsapp.RootPackMetadata
import com.example.whatsapp.RootStickerInput
import com.example.whatsapp.RootWhatsAppInjector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootWhatsAppInjectorTest {

    @Test
    fun base64Filename_replacesSlashWithDashAndKeepsPlusAndPadding() {
        // 0xfb (11111011), 0xff (11111111) -> 62 (+), 63 (/), 60 (8), padding (=) -> "+/8="
        val sample = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        val std = RootWhatsAppInjector.toStandardBase64(sample)
        val fileBase = RootWhatsAppInjector.toWhatsAppFileNameBase(sample)

        assertEquals("+/8=", std)
        assertEquals("+-8=", fileBase)
        assertTrue(fileBase.contains('+'))
        assertTrue(fileBase.endsWith('='))
        assertFalse(fileBase.contains('/'))
    }

    @Test
    fun prepareSticker_injectsUserCreatedExifAndComputesConsistentHashes() {
        val fakePayload = byteArrayOf(
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
            4, 0, 0, 0,
            10, 20, 30, 40
        )
        val frame = WebpMuxer.EncodedFrame(payload = fakePayload, hasAlpha = false, durationMs = 100)
        // Build initial WebP WITHOUT sound-sha256 or is-from-user-created-pack
        val rawWebp = WebpMuxer.buildAnimatedWebp(
            frames = listOf(frame),
            exif = WebpMuxer.exifWhatsApp("TestPack", "Me", null),
            side = 512
        )
        val fakeOpus = "OggS-fake-opus-payload".toByteArray()
        val waveform = ByteArray(64) { (it % 100).toByte() }

        val prepared = RootWhatsAppInjector.prepareSticker(
            RootStickerInput(
                webpBytes = rawWebp,
                opusBytes = fakeOpus,
                durationSec = 2.6,
                side = 512,
                emojis = "🔊",
                packName = "TestPack",
                publisher = "Me",
                waveform = waveform
            )
        )

        val expectedOpusRaw = RootWhatsAppInjector.sha256Raw(fakeOpus)
        val expectedOpusB64 = RootWhatsAppInjector.toStandardBase64(expectedOpusRaw)
        assertArrayEquals(expectedOpusRaw, prepared.opusSha256Raw)
        assertEquals(expectedOpusB64, prepared.opusSha256StandardBase64)
        assertEquals(
            "${RootWhatsAppInjector.toWhatsAppFileNameBase(expectedOpusRaw)}.opus",
            prepared.audioFileName
        )

        val chunks = WebpMuxer.readChunks(prepared.webpBytes)
        val exifText = String(chunks.first { it.fourcc == "EXIF" }.data, Charsets.UTF_8)
        assertTrue(exifText.contains("\"sound-sha256\":\"$expectedOpusB64\""))
        assertTrue(exifText.contains("\"is-from-user-created-pack\":1"))

        val actualWebpHash = RootWhatsAppInjector.sha256Raw(prepared.webpBytes)
        assertEquals(RootWhatsAppInjector.toStandardBase64(actualWebpHash), prepared.webpPlaintextHash)
        assertEquals("${RootWhatsAppInjector.toWhatsAppFileNameBase(actualWebpHash)}.webp", prepared.webpFileName)
        assertEquals(3, prepared.durationSeconds)
    }

    @Test
    fun injectIntoDatabase_writesStickerAudioStarredAndPackTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.cacheDir, "test_stickers_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        val initDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        initDb.execSQL(
            """
            CREATE TABLE sticker_audio (
                plaintext_hash TEXT NOT NULL,
                file_sha256 BLOB NOT NULL,
                file_path TEXT NOT NULL,
                mimetype TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL,
                file_length INTEGER NOT NULL,
                waveform BLOB,
                PRIMARY KEY(plaintext_hash)
            )
            """.trimIndent()
        )
        initDb.execSQL(
            """
            CREATE TABLE starred_stickers (
                plaintext_hash TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                mimetype TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                emojis TEXT,
                is_first_party INTEGER DEFAULT 0,
                is_avatar INTEGER DEFAULT 0,
                is_lottie INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        initDb.execSQL(
            """
            CREATE TABLE installed_sticker_packs (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                publisher TEXT NOT NULL,
                description TEXT NOT NULL,
                size INTEGER NOT NULL,
                tray_image_id TEXT NOT NULL,
                image_data_hash TEXT NOT NULL,
                animated_pack INTEGER NOT NULL,
                is_created_by_me INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        initDb.execSQL(
            """
            CREATE TABLE stickers (
                plain_file_hash TEXT PRIMARY KEY,
                sticker_pack_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                emojis TEXT,
                order_in_pack INTEGER NOT NULL
            )
            """.trimIndent()
        )
        initDb.close()

        try {
            val fakePayload = byteArrayOf(
                'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'L'.code.toByte(),
                4, 0, 0, 0,
                1, 2, 3, 4
            )
            val rawWebp = WebpMuxer.buildAnimatedWebp(
                frames = listOf(WebpMuxer.EncodedFrame(fakePayload, hasAlpha = true, durationMs = 90)),
                exif = null,
                side = 512
            )
            val fakeOpus = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
            val waveform = ByteArray(64) { 42 }
            val prepared = RootWhatsAppInjector.prepareSticker(
                RootStickerInput(
                    webpBytes = rawWebp,
                    opusBytes = fakeOpus,
                    durationSec = 4.2,
                    side = 512,
                    emojis = "🔊",
                    packName = "RootPack",
                    publisher = "Tester",
                    waveform = waveform
                )
            )

            RootWhatsAppInjector.injectIntoDatabase(
                dbFile = dbFile,
                stickers = listOf(prepared),
                packMetadata = RootPackMetadata("pack_root_1", "RootPack", "Tester"),
                registerInStarred = true,
                nowMs = 1_700_000_000_000L
            )

            val verifyDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            verifyDb.rawQuery("SELECT * FROM sticker_audio", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(prepared.webpPlaintextHash, c.getString(c.getColumnIndexOrThrow("plaintext_hash")))
                assertArrayEquals(prepared.opusSha256Raw, c.getBlob(c.getColumnIndexOrThrow("file_sha256")))
                assertEquals(prepared.audioFileName, c.getString(c.getColumnIndexOrThrow("file_path")))
                assertEquals("audio/ogg; codecs=opus", c.getString(c.getColumnIndexOrThrow("mimetype")))
                assertEquals(4, c.getInt(c.getColumnIndexOrThrow("duration_seconds")))
                assertEquals(fakeOpus.size.toLong(), c.getLong(c.getColumnIndexOrThrow("file_length")))
                assertArrayEquals(waveform, c.getBlob(c.getColumnIndexOrThrow("waveform")))
            }

            verifyDb.rawQuery("SELECT * FROM starred_stickers", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(prepared.webpPlaintextHash, c.getString(c.getColumnIndexOrThrow("plaintext_hash")))
                assertEquals("image/webp", c.getString(c.getColumnIndexOrThrow("mimetype")))
                assertEquals(512, c.getInt(c.getColumnIndexOrThrow("width")))
            }

            verifyDb.rawQuery("SELECT * FROM installed_sticker_packs", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("pack_root_1", c.getString(c.getColumnIndexOrThrow("id")))
                assertEquals(1, c.getInt(c.getColumnIndexOrThrow("is_created_by_me")))
            }

            verifyDb.rawQuery("SELECT * FROM stickers", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(prepared.webpPlaintextHash, c.getString(c.getColumnIndexOrThrow("plain_file_hash")))
                assertEquals(prepared.webpFileName, c.getString(c.getColumnIndexOrThrow("file_path")))
                assertEquals("pack_root_1", c.getString(c.getColumnIndexOrThrow("sticker_pack_id")))
            }
            verifyDb.close()
        } finally {
            dbFile.delete()
        }
    }
}
