package com.example.whatsapp

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object WhatsAppBridge {

    private const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_PACK_ID = "sticker_pack_id"
    private const val EXTRA_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_PACK_NAME = "sticker_pack_name"

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    fun findInstalledWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in listOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE)) {
            if (isPackageInstalled(pm, pkg)) return pkg
        }
        return null
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun buildEnableStickerPackIntent(packId: String, packName: String): Intent {
        return Intent(ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(EXTRA_PACK_ID, packId)
            putExtra(EXTRA_AUTHORITY, StickerContentProvider.AUTHORITY)
            putExtra(EXTRA_PACK_NAME, packName)
        }
    }

    fun launchAddPackToWhatsApp(context: Context, packId: String, packName: String): Result<Unit> {
        val intent = buildEnableStickerPackIntent(packId, packName)
        val waPkg = findInstalledWhatsAppPackage(context)
        if (waPkg != null) {
            intent.`package` = waPkg
        }
        return try {
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            // Fallback without package pin in case chooser handles it
            try {
                context.startActivity(buildEnableStickerPackIntent(packId, packName))
                Result.success(Unit)
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    fun shareMp4ToWhatsApp(context: Context, mp4File: File, chooserTitle: String): Result<Unit> {
        val uri = fileToContentUri(context, mp4File)
        val waPkg = findInstalledWhatsAppPackage(context)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (waPkg != null) setPackage(waPkg)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            Result.success(Unit)
        } catch (e: Exception) {
            // Fallback generic share chooser
            try {
                val generic = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(generic, chooserTitle))
                Result.success(Unit)
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    fun shareRawPair(context: Context, webpFile: File, oggFile: File, chooserTitle: String): Result<Unit> {
        val uris = arrayListOf(
            fileToContentUri(context, webpFile),
            fileToContentUri(context, oggFile)
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveMp4ToGallery(context: Context, mp4File: File, baseName: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanName = baseName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifEmpty { "audio_sticker" }
                val fileName = "${cleanName}_${System.currentTimeMillis()}.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/AudioStickers")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Failed to allocate MediaStore entry")
                resolver.openOutputStream(itemUri)?.use { out ->
                    mp4File.inputStream().use { input -> input.copyTo(out) }
                } ?: error("Failed to open MediaStore output stream")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                }
                itemUri
            }
        }

    private fun fileToContentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
    }
}
