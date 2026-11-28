package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.StickerDao
import com.example.data.local.StickerEntity
import com.example.data.local.StickerPackEntity
import com.example.data.local.StickerPackWithStickers
import com.example.engine.StickerSafetyValidator
import com.example.engine.WebpMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class StickerPackRepository(
    private val context: Context,
    private val dao: StickerDao
) {
    val packsWithStickers: Flow<List<StickerPackWithStickers>> = dao.observePacksWithStickers()

    fun observePack(packId: String): Flow<StickerPackWithStickers?> = dao.observePackById(packId)

    fun stickersRootDir(): File = File(context.filesDir, "stickers").apply { mkdirs() }

    fun packDir(packId: String): File = File(stickersRootDir(), packId).apply { mkdirs() }

    fun stickerFile(packId: String, fileName: String): File = File(packDir(packId), fileName)

    suspend fun createPack(name: String, publisher: String): StickerPackEntity = withContext(Dispatchers.IO) {
        val packId = "pack_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val cleanName = name.trim().ifEmpty { "My Stickers" }.take(128)
        val cleanPub = publisher.trim().ifEmpty { "Audio Stickers" }.take(128)
        val trayName = "tray_icon.png"
        writeDefaultTrayIcon(packDir(packId), trayName)
        val pack = StickerPackEntity(
            packId = packId,
            name = cleanName,
            publisher = cleanPub,
            trayIconFileName = trayName,
            imageDataVersion = 1,
            animatedPack = true
        )
        dao.insertPack(pack)
        pack
    }

    suspend fun saveGeneratedStickerToPack(
        packId: String,
        webpBytes: ByteArray,
        oggBytes: ByteArray,
        mp4File: File?,
        thumbnailBitmap: Bitmap?,
        sourceKind: String,
        soundSha256: String,
        durationMs: Long,
        audioDurationMs: Long,
        side: Int,
        fps: Int,
        isAnimated: Boolean
    ): StickerEntity = withContext(Dispatchers.IO) {
        val existingPack = dao.getPackByIdSync(packId)
            ?: error("Pack not found: $packId")
        val effectiveDurationSec = maxOf(durationMs, audioDurationMs) / 1000.0
        val effectiveSha = if (oggBytes.isNotEmpty()) {
            StickerSafetyValidator.computeOpusSha256Base64(oggBytes)
        } else {
            soundSha256
        }
        val finalWebpBytes = if (webpBytes.size >= 12 &&
            String(webpBytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(webpBytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            WebpMuxer.retagWebp(
                webpBytes = webpBytes,
                packName = existingPack.pack.name,
                publisher = existingPack.pack.publisher,
                soundSha256 = effectiveSha.ifEmpty { null }
            )
        } else {
            webpBytes
        }

        StickerSafetyValidator.validate(finalWebpBytes, oggBytes, effectiveDurationSec).getOrThrow()

        val dir = packDir(packId)
        val webpName = StickerSafetyValidator.computeWebpFileName(finalWebpBytes)
        val opusName = StickerSafetyValidator.computeOpusFileName(oggBytes)
        val mp4Name = if (mp4File != null && mp4File.exists()) "stk_${System.currentTimeMillis()}.mp4" else null

        File(dir, webpName).writeBytes(finalWebpBytes)
        File(dir, opusName).writeBytes(oggBytes)
        if (mp4Name != null && mp4File != null) {
            mp4File.copyTo(File(dir, mp4Name), overwrite = true)
        }

        // Update tray icon from the first sticker if the pack had no stickers yet
        if (existingPack.stickers.isEmpty() && thumbnailBitmap != null) {
            writeTrayIconFromBitmap(dir, existingPack.pack.trayIconFileName, thumbnailBitmap)
        }

        val entity = StickerEntity(
            packId = packId,
            fileName = webpName,
            oggFileName = opusName,
            mp4FileName = mp4Name,
            sourceKind = sourceKind,
            soundSha256 = effectiveSha,
            durationMs = durationMs,
            audioDurationMs = audioDurationMs,
            side = side,
            fps = fps,
            isAnimated = isAnimated,
            webpBytes = finalWebpBytes.size.toLong(),
            oggBytes = oggBytes.size.toLong()
        )
        val rowId = dao.insertSticker(entity)
        val updatedAnimated = existingPack.stickers.any { it.isAnimated } || isAnimated
        dao.updatePack(
            existingPack.pack.copy(
                imageDataVersion = existingPack.pack.imageDataVersion + 1,
                animatedPack = updatedAnimated
            )
        )
        entity.copy(id = rowId)
    }

    suspend fun deleteSticker(sticker: StickerEntity) = withContext(Dispatchers.IO) {
        val dir = packDir(sticker.packId)
        dao.deleteSticker(sticker.id)
        val remaining = dao.getPackByIdSync(sticker.packId)
        val stillUsesWebp = remaining?.stickers?.any { it.fileName == sticker.fileName } == true
        val stillUsesAudio = remaining?.stickers?.any { it.oggFileName == sticker.oggFileName } == true
        if (!stillUsesWebp) File(dir, sticker.fileName).delete()
        if (!stillUsesAudio) File(dir, sticker.oggFileName).delete()
        sticker.mp4FileName?.let { File(dir, it).delete() }
        remaining?.let { packWithStickers ->
            dao.updatePack(
                packWithStickers.pack.copy(
                    imageDataVersion = packWithStickers.pack.imageDataVersion + 1
                )
            )
        }
    }

    suspend fun deletePack(packId: String) = withContext(Dispatchers.IO) {
        packDir(packId).deleteRecursively()
        dao.deletePack(packId)
    }

    suspend fun updatePackMeta(pack: StickerPackEntity, name: String, publisher: String) = withContext(Dispatchers.IO) {
        dao.updatePack(
            pack.copy(
                name = name.trim().ifEmpty { pack.name }.take(128),
                publisher = publisher.trim().ifEmpty { pack.publisher }.take(128),
                imageDataVersion = pack.imageDataVersion + 1
            )
        )
    }

    private fun writeDefaultTrayIcon(dir: File, fileName: String) {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_brand_logo)
        if (drawable != null) {
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
        }
        FileOutputStream(File(dir, fileName)).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
    }

    private fun writeTrayIconFromBitmap(dir: File, fileName: String, src: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(src, 96, 96, true)
        FileOutputStream(File(dir, fileName)).use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (scaled !== src) scaled.recycle()
    }

    fun loadTrayBitmap(pack: StickerPackEntity): Bitmap? {
        val f = stickerFile(pack.packId, pack.trayIconFileName)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }
}
