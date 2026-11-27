package com.example.whatsapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.util.Base64
import com.example.engine.WebpMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

data class WhatsAppRootTarget(
    val packageName: String,
    val accountDir: String,
    val uidGid: String
)

data class RootStickerInput(
    val webpBytes: ByteArray,
    val opusBytes: ByteArray,
    val durationSec: Double,
    val side: Int = 512,
    val emojis: String = "🔊",
    val packName: String = "Audio Stickers",
    val publisher: String = "Audio Stickers",
    val waveform: ByteArray? = null
)

data class RootPackMetadata(
    val packId: String,
    val name: String,
    val publisher: String
)

data class PreparedRootSticker(
    val webpBytes: ByteArray,
    val opusBytes: ByteArray,
    val webpPlaintextHash: String,
    val webpFileName: String,
    val opusSha256Raw: ByteArray,
    val opusSha256StandardBase64: String,
    val audioFileName: String,
    val durationSeconds: Int,
    val side: Int,
    val emojis: String,
    val waveform: ByteArray?
)

data class RootInjectionResult(
    val packageName: String,
    val accountDir: String,
    val injectedCount: Int,
    val primaryWebpFileName: String,
    val primaryAudioFileName: String
)

/**
 * Magisk Mount Master (`su -mm`) direct injector for WhatsApp native sound stickers.
 * Writes `.webp` + `.opus` files into WhatsApp's internal storage and registers them in `stickers.db`.
 */
object RootWhatsAppInjector {

    private val ALLOWED_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    private val SAFE_ACCOUNT_DIR_REGEX =
        Regex("""^/data/user/0/(com\.whatsapp|com\.whatsapp\.w4b)(/accounts/[A-Za-z0-9._-]+)?$""")
    private val SAFE_FILENAME_REGEX = Regex("""^[A-Za-z0-9+=-]+\.(webp|opus|db)$""")
    private val SAFE_UID_GID_REGEX = Regex("""^[0-9]+:[0-9]+$""")

    private data class ColumnMeta(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val hasDefault: Boolean
    )

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    /**
     * Computes raw 32-byte SHA-256 digest.
     */
    fun sha256Raw(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Standard Base64 (keeps `+`, `/`, and trailing `=`), used for SQLite `plaintext_hash`
     * and EXIF `"sound-sha256"`.
     */
    fun toStandardBase64(digest: ByteArray): String =
        Base64.encodeToString(digest, Base64.NO_WRAP)

    /**
     * WhatsApp on-device filename base: Standard Base64 with `/` replaced by `-` (keeps `+` and `=`).
     */
    fun toWhatsAppFileNameBase(digest: ByteArray): String =
        toStandardBase64(digest).replace('/', '-')

    fun prepareSticker(input: RootStickerInput): PreparedRootSticker {
        require(input.webpBytes.isNotEmpty()) { "Sticker WebP payload is empty" }
        require(input.opusBytes.isNotEmpty()) {
            "Ogg/Opus audio is required for WhatsApp sound sticker injection (Android 10+ Opus encoder)."
        }

        val opusDigest = sha256Raw(input.opusBytes)
        val opusStandardBase64 = toStandardBase64(opusDigest)
        val audioFileName = "${toWhatsAppFileNameBase(opusDigest)}.opus"

        val finalizedWebp = ensureUserCreatedSoundExif(
            webpBytes = input.webpBytes,
            packName = input.packName,
            publisher = input.publisher,
            opusSha256StandardBase64 = opusStandardBase64
        )
        val webpDigest = sha256Raw(finalizedWebp)
        val webpPlaintextHash = toStandardBase64(webpDigest)
        val webpFileName = "${toWhatsAppFileNameBase(webpDigest)}.webp"

        require(SAFE_FILENAME_REGEX.matches(webpFileName)) { "Unsafe WebP filename generated" }
        require(SAFE_FILENAME_REGEX.matches(audioFileName)) { "Unsafe Opus filename generated" }

        return PreparedRootSticker(
            webpBytes = finalizedWebp,
            opusBytes = input.opusBytes,
            webpPlaintextHash = webpPlaintextHash,
            webpFileName = webpFileName,
            opusSha256Raw = opusDigest,
            opusSha256StandardBase64 = opusStandardBase64,
            audioFileName = audioFileName,
            durationSeconds = input.durationSec.roundToInt().coerceAtLeast(1),
            side = input.side.coerceAtLeast(1),
            emojis = input.emojis.ifBlank { "🔊" },
            waveform = input.waveform
        )
    }

    fun ensureUserCreatedSoundExif(
        webpBytes: ByteArray,
        packName: String,
        publisher: String,
        opusSha256StandardBase64: String
    ): ByteArray {
        val existingExif = runCatching {
            WebpMuxer.readChunks(webpBytes).firstOrNull { it.fourcc == "EXIF" }
        }.getOrNull()

        if (existingExif != null) {
            val text = String(existingExif.data, Charsets.UTF_8)
            val hasMatchingSound = text.contains("\"sound-sha256\":\"$opusSha256StandardBase64\"")
            val hasUserCreatedFlag = text.contains("\"is-from-user-created-pack\":1")
            if (hasMatchingSound && hasUserCreatedFlag) {
                return webpBytes
            }
        }

        return WebpMuxer.retagWebp(
            webpBytes = webpBytes,
            packName = packName.ifBlank { "Audio Stickers" },
            publisher = publisher.ifBlank { "Audio Stickers" },
            soundSha256 = opusSha256StandardBase64
        )
    }

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val res = execMountMaster("id")
        res.exitCode == 0 && res.stdout.contains("uid=0")
    }

    suspend fun discoverWhatsAppTargets(): List<WhatsAppRootTarget> = withContext(Dispatchers.IO) {
        val probeScript = buildString {
            for (pkg in ALLOWED_PACKAGES) {
                append(
                    """
                    BASE='/data/user/0/$pkg'
                    if [ -d "${'$'}BASE" ]; then
                      UG=$(stat -c '%u:%g' "${'$'}BASE/files" 2>/dev/null || stat -c '%u:%g' "${'$'}BASE" 2>/dev/null)
                      if [ -d "${'$'}BASE/accounts" ]; then
                        for d in "${'$'}BASE/accounts"/*; do
                          if [ -f "${'$'}d/databases/stickers.db" ]; then
                            echo "$pkg|${'$'}d|${'$'}UG"
                          fi
                        done
                      fi
                      if [ -f "${'$'}BASE/databases/stickers.db" ]; then
                        echo "$pkg|${'$'}BASE|${'$'}UG"
                      fi
                    fi
                    """.trimIndent()
                )
                append('\n')
            }
        }

        val res = execMountMaster(probeScript)
        if (res.exitCode != 0) return@withContext emptyList()

        res.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 3) return@mapNotNull null
                val pkg = parts[0].trim()
                val dir = parts[1].trim().trimEnd('/')
                val uidGid = parts[2].trim()
                if (pkg !in ALLOWED_PACKAGES) return@mapNotNull null
                if (!SAFE_ACCOUNT_DIR_REGEX.matches(dir)) return@mapNotNull null
                if (!SAFE_UID_GID_REGEX.matches(uidGid)) return@mapNotNull null
                WhatsAppRootTarget(pkg, dir, uidGid)
            }
            .distinctBy { it.accountDir }
            .sortedWith(
                compareBy<WhatsAppRootTarget>(
                    { if (it.packageName == "com.whatsapp") 0 else 1 },
                    { if (it.accountDir.contains("/accounts/")) 0 else 1 }
                )
            )
            .toList()
    }

    suspend fun injectSingleSticker(
        context: Context,
        sticker: RootStickerInput,
        relaunchWhatsApp: Boolean = false
    ): Result<RootInjectionResult> = injectStickers(
        context = context,
        stickers = listOf(sticker),
        packMetadata = null,
        registerInStarred = true,
        relaunchWhatsApp = relaunchWhatsApp
    )

    suspend fun injectPack(
        context: Context,
        packMetadata: RootPackMetadata,
        stickers: List<RootStickerInput>,
        registerInStarred: Boolean = true,
        relaunchWhatsApp: Boolean = false
    ): Result<RootInjectionResult> = injectStickers(
        context = context,
        stickers = stickers,
        packMetadata = packMetadata,
        registerInStarred = registerInStarred,
        relaunchWhatsApp = relaunchWhatsApp
    )

    suspend fun injectStickers(
        context: Context,
        stickers: List<RootStickerInput>,
        packMetadata: RootPackMetadata?,
        registerInStarred: Boolean = true,
        relaunchWhatsApp: Boolean = false
    ): Result<RootInjectionResult> = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "root_tmp/${UUID.randomUUID()}")
        try {
            if (stickers.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No stickers to inject"))
            }
            if (!checkRootAccess()) {
                return@withContext Result.failure(
                    IllegalStateException("Root access (su -mm) was denied or is unavailable on this device.")
                )
            }

            val targets = discoverWhatsAppTargets()
            val target = targets.firstOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("Could not locate WhatsApp stickers.db under /data/user/0/com.whatsapp.")
                )

            val prepared = stickers.map { prepareSticker(it) }
            workDir.mkdirs()

            val stagedDir = File(workDir, "staged").apply { mkdirs() }
            for (item in prepared) {
                File(stagedDir, item.webpFileName).writeBytes(item.webpBytes)
                File(stagedDir, item.audioFileName).writeBytes(item.opusBytes)
            }

            val localDbDir = File(workDir, "db").apply { mkdirs() }
            val localDbFile = File(localDbDir, "stickers.db")
            val appUid = Process.myUid()

            val pullCmd = buildString {
                append("am force-stop ${target.packageName} && ")
                append("cp -f '${target.accountDir}/databases/stickers.db' '${localDbFile.absolutePath}' && ")
                append("if [ -f '${target.accountDir}/databases/stickers.db-wal' ]; then cp -f '${target.accountDir}/databases/stickers.db-wal' '${localDbDir.absolutePath}/stickers.db-wal'; fi && ")
                append("if [ -f '${target.accountDir}/databases/stickers.db-shm' ]; then cp -f '${target.accountDir}/databases/stickers.db-shm' '${localDbDir.absolutePath}/stickers.db-shm'; fi && ")
                append("chown $appUid:$appUid '${localDbDir.absolutePath}'/stickers.db* && ")
                append("chmod 600 '${localDbDir.absolutePath}'/stickers.db*")
            }
            val pullRes = execMountMaster(pullCmd)
            if (pullRes.exitCode != 0 || !localDbFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Failed to read WhatsApp stickers.db: ${pullRes.stderr.ifBlank { pullRes.stdout }}")
                )
            }

            injectIntoDatabase(
                dbFile = localDbFile,
                stickers = prepared,
                packMetadata = packMetadata,
                registerInStarred = registerInStarred
            )

            val pushCmd = buildString {
                val acct = target.accountDir
                val ug = target.uidGid
                append("am force-stop ${target.packageName} && ")
                append("mkdir -p '$acct/files/Stickers' '$acct/files/StickerAudio' '$acct/files/SavedStickerAudio' && ")
                append("chown $ug '$acct/files/Stickers' '$acct/files/StickerAudio' '$acct/files/SavedStickerAudio' && ")
                append("chmod 700 '$acct/files/Stickers' '$acct/files/StickerAudio' '$acct/files/SavedStickerAudio' && ")
                for (item in prepared) {
                    val srcWebp = File(stagedDir, item.webpFileName).absolutePath
                    val srcOpus = File(stagedDir, item.audioFileName).absolutePath
                    val dstWebp = "$acct/files/Stickers/${item.webpFileName}"
                    val dstAudio1 = "$acct/files/StickerAudio/${item.audioFileName}"
                    val dstAudio2 = "$acct/files/SavedStickerAudio/${item.audioFileName}"
                    append("cp -f '$srcWebp' '$dstWebp' && ")
                    append("cp -f '$srcOpus' '$dstAudio1' && ")
                    append("cp -f '$srcOpus' '$dstAudio2' && ")
                    append("chown $ug '$dstWebp' '$dstAudio1' '$dstAudio2' && ")
                    append("chmod 600 '$dstWebp' '$dstAudio1' '$dstAudio2' && ")
                    append("restorecon '$dstWebp' '$dstAudio1' '$dstAudio2' >/dev/null 2>&1 || true; ")
                }
                append("cp -f '${localDbFile.absolutePath}' '$acct/databases/stickers.db' && ")
                append("rm -f '$acct/databases/stickers.db-wal' '$acct/databases/stickers.db-shm' && ")
                append("chown $ug '$acct/databases/stickers.db' && ")
                append("chmod 600 '$acct/databases/stickers.db' && ")
                append("restorecon '$acct/databases/stickers.db' >/dev/null 2>&1 || true")
            }

            val pushRes = execMountMaster(pushCmd)
            if (pushRes.exitCode != 0) {
                return@withContext Result.failure(
                    IllegalStateException("Failed to write stickers to WhatsApp: ${pushRes.stderr.ifBlank { pushRes.stdout }}")
                )
            }

            if (relaunchWhatsApp) {
                runCatching {
                    val launch = context.packageManager.getLaunchIntentForPackage(target.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                    }
                }
            }

            Result.success(
                RootInjectionResult(
                    packageName = target.packageName,
                    accountDir = target.accountDir,
                    injectedCount = prepared.size,
                    primaryWebpFileName = prepared.first().webpFileName,
                    primaryAudioFileName = prepared.first().audioFileName
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * Inserts sound sticker metadata into `sticker_audio`, `starred_stickers`, and optional
     * `installed_sticker_packs` / `stickers` tables, then checkpoints WAL into the main `.db` file.
     */
    fun injectIntoDatabase(
        dbFile: File,
        stickers: List<PreparedRootSticker>,
        packMetadata: RootPackMetadata? = null,
        registerInStarred: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            db.beginTransaction()
            try {
                ensureStickerAudioTable(db)

                for ((index, item) in stickers.withIndex()) {
                    val audioValues = ContentValues().apply {
                        put("plaintext_hash", item.webpPlaintextHash)
                        put("file_sha256", item.opusSha256Raw)
                        put("file_path", item.audioFileName)
                        put("mimetype", "audio/ogg; codecs=opus")
                        put("duration_seconds", item.durationSeconds)
                        put("file_length", item.opusBytes.size.toLong())
                        if (item.waveform != null) {
                            put("waveform", item.waveform)
                        } else {
                            putNull("waveform")
                        }
                    }
                    db.insertWithOnConflict("sticker_audio", null, audioValues, SQLiteDatabase.CONFLICT_REPLACE)

                    if (registerInStarred) {
                        val starredCols = readTableColumns(db, "starred_stickers")
                        if (starredCols.isNotEmpty()) {
                            val starredMap = mapOf<String, Any?>(
                                "plaintext_hash" to item.webpPlaintextHash,
                                "timestamp" to (nowMs + index),
                                "mimetype" to "image/webp",
                                "file_size" to item.webpBytes.size.toLong(),
                                "width" to item.side,
                                "height" to item.side,
                                "emojis" to item.emojis,
                                "is_first_party" to 0,
                                "is_avatar" to 0,
                                "is_lottie" to 0,
                                "is_fun_sticker" to 0,
                                "is_ai_sticker" to 0,
                                "avatar_template_id" to "",
                                "accessibility_text" to ""
                            )
                            val cv = buildSafeContentValues(starredCols, starredMap)
                            db.insertWithOnConflict("starred_stickers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }
                }

                if (packMetadata != null) {
                    val packCols = readTableColumns(db, "installed_sticker_packs")
                    if (packCols.isNotEmpty()) {
                        val totalBytes = stickers.sumOf { it.webpBytes.size.toLong() + it.opusBytes.size.toLong() }
                        val trayId = stickers.first().webpFileName
                        val packMap = mapOf<String, Any?>(
                            "id" to packMetadata.packId,
                            "name" to packMetadata.name,
                            "publisher" to packMetadata.publisher,
                            "description" to "",
                            "size" to totalBytes,
                            "tray_image_id" to trayId,
                            "tray_image_preview_id" to trayId,
                            "image_data_hash" to "1",
                            "animated_pack" to 1,
                            "is_created_by_me" to 1,
                            "avoid_cache" to 0,
                            "installed_id" to packMetadata.packId,
                            "installed_name" to packMetadata.name,
                            "installed_publisher" to packMetadata.publisher,
                            "installed_description" to "",
                            "installed_size" to totalBytes,
                            "installed_tray_image_id" to trayId,
                            "installed_tray_image_preview_id" to trayId,
                            "installed_image_data_hash" to "1",
                            "installed_animated_pack" to 1,
                            "installed_is_avatar_pack" to 0
                        )
                        val packCv = buildSafeContentValues(packCols, packMap)
                        db.insertWithOnConflict("installed_sticker_packs", null, packCv, SQLiteDatabase.CONFLICT_REPLACE)
                    }

                    val stickerCols = readTableColumns(db, "stickers")
                    if (stickerCols.isNotEmpty()) {
                        for ((index, item) in stickers.withIndex()) {
                            val stickerMap = mapOf<String, Any?>(
                                "plain_file_hash" to item.webpPlaintextHash,
                                "plaintext_hash" to item.webpPlaintextHash,
                                "sticker_pack_id" to packMetadata.packId,
                                "file_path" to item.webpFileName,
                                "url" to "",
                                "enc_file_hash" to item.webpPlaintextHash,
                                "direct_path" to "",
                                "mime_type" to "image/webp",
                                "mimetype" to "image/webp",
                                "media_key" to "",
                                "file_size" to item.webpBytes.size.toLong(),
                                "width" to item.side,
                                "height" to item.side,
                                "emojis" to item.emojis,
                                "order_in_pack" to index,
                                "is_first_party" to 0,
                                "is_avatar" to 0,
                                "is_lottie" to 0
                            )
                            val stickerCv = buildSafeContentValues(stickerCols, stickerMap)
                            db.insertWithOnConflict("stickers", null, stickerCv, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }

    private fun ensureStickerAudioTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sticker_audio (
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
    }

    private fun readTableColumns(db: SQLiteDatabase, tableName: String): List<ColumnMeta> {
        val cols = mutableListOf<ColumnMeta>()
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val typeIdx = cursor.getColumnIndex("type")
            val notNullIdx = cursor.getColumnIndex("notnull")
            val dfltIdx = cursor.getColumnIndex("dflt_value")
            while (cursor.moveToNext()) {
                cols.add(
                    ColumnMeta(
                        name = cursor.getString(nameIdx),
                        type = cursor.getString(typeIdx) ?: "",
                        notNull = cursor.getInt(notNullIdx) == 1,
                        hasDefault = !cursor.isNull(dfltIdx)
                    )
                )
            }
        }
        return cols
    }

    private fun buildSafeContentValues(
        columns: List<ColumnMeta>,
        valuesMap: Map<String, Any?>
    ): ContentValues {
        val cv = ContentValues()
        for (col in columns) {
            if (valuesMap.containsKey(col.name)) {
                when (val v = valuesMap[col.name]) {
                    null -> cv.putNull(col.name)
                    is String -> cv.put(col.name, v)
                    is Int -> cv.put(col.name, v)
                    is Long -> cv.put(col.name, v)
                    is ByteArray -> cv.put(col.name, v)
                    is Boolean -> cv.put(col.name, if (v) 1 else 0)
                    else -> cv.put(col.name, v.toString())
                }
            } else if (col.notNull && !col.hasDefault) {
                val upper = col.type.uppercase()
                when {
                    upper.contains("INT") -> cv.put(col.name, 0)
                    upper.contains("REAL") || upper.contains("FLOA") || upper.contains("DOUB") -> cv.put(col.name, 0.0)
                    upper.contains("BLOB") -> cv.put(col.name, ByteArray(0))
                    else -> cv.put(col.name, "")
                }
            }
        }
        return cv
    }

    private fun execMountMaster(command: String): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-mm", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            ShellResult(code, stdout, stderr)
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message ?: "Failed to execute su -mm")
        }
    }
}
