package com.example.whatsapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
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

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun startIntentSafely(context: Context, intent: Intent): Result<Unit> {
        val activity = context.findActivity()
        val targetContext = activity ?: context
        if (activity == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            targetContext.startActivity(intent)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun startChooserSafely(context: Context, innerIntent: Intent, title: String): Result<Unit> {
        val activity = context.findActivity()
        val targetContext = activity ?: context
        if (activity == null) {
            innerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(innerIntent, title).apply {
            if (activity == null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            targetContext.startActivity(chooser)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launchAddPackToWhatsApp(context: Context, packId: String, packName: String): Result<Unit> {
        val waPkg = findInstalledWhatsAppPackage(context)
            ?: return Result.failure(ActivityNotFoundException("WhatsApp is not installed"))
        val pinned = buildEnableStickerPackIntent(packId, packName).apply {
            `package` = waPkg
        }
        val firstAttempt = startIntentSafely(context, pinned)
        if (firstAttempt.isSuccess) return firstAttempt

        return startIntentSafely(context, buildEnableStickerPackIntent(packId, packName))
    }

    fun shareMp4ToWhatsApp(context: Context, mp4File: File, chooserTitle: String): Result<Unit> {
        val uri = try {
            fileToContentUri(context, mp4File)
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        val waPkg = findInstalledWhatsAppPackage(context)
        if (waPkg != null) {
            val directIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage(waPkg)
            }
            val directRes = startIntentSafely(context, directIntent)
            if (directRes.isSuccess) return directRes
        }
        val generic = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooserSafely(context, generic, chooserTitle)
    }

    fun shareRawPair(context: Context, webpFile: File, oggFile: File, chooserTitle: String): Result<Unit> {
        val uris = try {
            arrayListOf(
                fileToContentUri(context, webpFile),
                fileToContentUri(context, oggFile)
            )
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooserSafely(context, intent, chooserTitle)
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

    suspend fun isRootAvailable(): Boolean = RootWhatsAppInjector.checkRootAccess()

    suspend fun injectSoundStickerWithRoot(
        context: Context,
        sticker: RootStickerInput,
        relaunchWhatsApp: Boolean = false
    ): Result<RootInjectionResult> = RootWhatsAppInjector.injectSingleSticker(
        context = context,
        sticker = sticker,
        relaunchWhatsApp = relaunchWhatsApp
    )

    suspend fun injectSoundPackWithRoot(
        context: Context,
        packMetadata: RootPackMetadata,
        stickers: List<RootStickerInput>,
        relaunchWhatsApp: Boolean = false
    ): Result<RootInjectionResult> = RootWhatsAppInjector.injectPack(
        context = context,
        packMetadata = packMetadata,
        stickers = stickers,
        relaunchWhatsApp = relaunchWhatsApp
    )

    private fun fileToContentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
    }
}
