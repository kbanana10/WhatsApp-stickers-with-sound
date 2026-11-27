package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.i18n.AppLanguage
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "audio_stickers_prefs")

data class UserPreferences(
    val language: AppLanguage = AppLanguage.AR,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val defaultPackName: String = "",
    val defaultPublisher: String = "",
    val syncOffsetMs: Int = 0,
    val rootSafetyNoticeAccepted: Boolean = false
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PACK_NAME = stringPreferencesKey("default_pack_name")
        val PUBLISHER = stringPreferencesKey("default_publisher")
        val SYNC_OFFSET_MS = intPreferencesKey("sync_offset_ms")
        val ROOT_SAFETY_NOTICE_ACCEPTED = booleanPreferencesKey("root_safety_notice_accepted")
    }

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            language = prefs[Keys.LANGUAGE]?.let { code ->
                AppLanguage.entries.firstOrNull { it.code == code }
            } ?: AppLanguage.AR,
            themeMode = prefs[Keys.THEME_MODE]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: ThemeMode.AUTO,
            defaultPackName = prefs[Keys.PACK_NAME] ?: "",
            defaultPublisher = prefs[Keys.PUBLISHER] ?: "",
            syncOffsetMs = prefs[Keys.SYNC_OFFSET_MS] ?: 0,
            rootSafetyNoticeAccepted = prefs[Keys.ROOT_SAFETY_NOTICE_ACCEPTED] ?: false
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun saveMetadataDefaults(packName: String, publisher: String) {
        context.dataStore.edit {
            it[Keys.PACK_NAME] = packName.trim()
            it[Keys.PUBLISHER] = publisher.trim()
        }
    }

    suspend fun setSyncOffsetMs(ms: Int) {
        context.dataStore.edit { it[Keys.SYNC_OFFSET_MS] = ms.coerceIn(0, 1000) }
    }

    suspend fun setRootSafetyNoticeAccepted(accepted: Boolean) {
        context.dataStore.edit { it[Keys.ROOT_SAFETY_NOTICE_ACCEPTED] = accepted }
    }
}
