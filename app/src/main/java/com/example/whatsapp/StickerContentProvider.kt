package com.example.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.StickerPackWithStickers
import java.io.File

/**
 * Official WhatsApp Third-Party Sticker Pack ContentProvider.
 * Exposes local packs stored in Room + filesDir/stickers/ to WhatsApp & WhatsApp Business.
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.stickercontentprovider"

        private const val CODE_METADATA = 1
        private const val CODE_METADATA_ITEM = 2
        private const val CODE_STICKERS = 3
        private const val CODE_STICKER_ASSET = 4

        private val METADATA_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "publisher_email",
            "publisher_website",
            "privacy_policy_website",
            "license_agreement_website",
            "image_data_version",
            "avoid_cache",
            "animated_sticker_pack"
        )

        private val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji"
        )
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "metadata", CODE_METADATA)
        addURI(AUTHORITY, "metadata/*", CODE_METADATA_ITEM)
        addURI(AUTHORITY, "stickers/*", CODE_STICKERS)
        addURI(AUTHORITY, "stickers_asset/*/*", CODE_STICKER_ASSET)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val dao = AppDatabase.getInstance(ctx).stickerDao()
        return when (matcher.match(uri)) {
            CODE_METADATA -> {
                val packs = dao.getAllPacksSync().filter { it.stickers.size >= 3 }
                buildPacksCursor(uri, packs)
            }
            CODE_METADATA_ITEM -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = dao.getPackByIdSync(packId)
                buildPacksCursor(uri, listOfNotNull(pack))
            }
            CODE_STICKERS -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = dao.getPackByIdSync(packId) ?: return MatrixCursor(STICKER_COLUMNS)
                buildStickersCursor(uri, pack)
            }
            else -> null
        }
    }

    private fun buildPacksCursor(uri: Uri, packs: List<StickerPackWithStickers>): Cursor {
        val cursor = MatrixCursor(METADATA_COLUMNS)
        for (item in packs) {
            val p = item.pack
            cursor.addRow(
                arrayOf<Any>(
                    p.packId,
                    p.name,
                    p.publisher,
                    p.trayIconFileName,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    p.imageDataVersion.toString(),
                    0,
                    if (p.animatedPack) 1 else 0
                )
            )
        }
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    private fun buildStickersCursor(uri: Uri, pack: StickerPackWithStickers): Cursor {
        val cursor = MatrixCursor(STICKER_COLUMNS)
        for (s in pack.stickers.take(30)) {
            cursor.addRow(arrayOf<Any>(s.fileName, s.emojis))
        }
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != CODE_STICKER_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size < 3) return null
        val packId = segments[1]
        val fileName = segments[2]
        val ctx = context ?: return null
        val packDir = File(ctx.filesDir, "stickers/$packId").canonicalFile
        val file = File(packDir, fileName).canonicalFile
        if (!file.path.startsWith(packDir.path + File.separator) || !file.exists()) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_METADATA -> "vnd.android.cursor.dir/vnd.$AUTHORITY.metadata"
        CODE_METADATA_ITEM -> "vnd.android.cursor.item/vnd.$AUTHORITY.metadata"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.stickers"
        CODE_STICKER_ASSET -> {
            val name = uri.lastPathSegment.orEmpty()
            if (name.endsWith(".png", ignoreCase = true)) "image/png" else "image/webp"
        }
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
