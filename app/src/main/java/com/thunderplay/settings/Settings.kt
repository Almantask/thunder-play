package com.thunderplay.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("thunderplay-settings")

/**
 * Intervals offered for the periodic refresh and sync jobs.
 *
 * 15 minutes is Android's hard floor for periodic WorkManager jobs, not a product choice; anything
 * tighter would need a permanently running foreground service.
 */
enum class SyncInterval(val minutes: Long, val label: String) {
    Quarter(15, "Every 15 minutes"),
    Half(30, "Every 30 minutes"),
    Hourly(60, "Hourly"),
    ThreeHourly(180, "Every 3 hours"),
    SixHourly(360, "Every 6 hours"),
    TwiceDaily(720, "Every 12 hours"),
    Daily(1440, "Daily"),
    ;

    companion object {
        val MINIMUM_MINUTES = Quarter.minutes
        fun fromMinutes(minutes: Int): SyncInterval =
            entries.firstOrNull { it.minutes.toInt() == minutes } ?: Hourly
    }
}

data class AppSettings(
    val libraryRootFolderId: String? = null,
    val sourceRoot: String = DEFAULT_SOURCE_ROOT,
    val autoRefreshEnabled: Boolean = true,
    val autoRefreshInterval: SyncInterval = SyncInterval.Hourly,
    val autoSyncEnabled: Boolean = false,
    val autoSyncInterval: SyncInterval = SyncInterval.SixHourly,
    val syncOnWifiOnly: Boolean = true,
    val syncOnlyWhenCharging: Boolean = false,
    val crossfadeMs: Int = DEFAULT_CROSSFADE_MS,
) {
    companion object {
        const val DEFAULT_SOURCE_ROOT = "music-mobile"
        const val WAV_SOURCE_ROOT = "music"
        const val LIBRARY_FOLDER_NAME = "Music-And-Fx-Generated-Library"
        const val TRASH_FOLDER_NAME = "_ThunderPlayTrash"
        const val DEFAULT_CROSSFADE_MS = 3_000
        const val MAX_CROSSFADE_MS = 12_000
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ROOT_FOLDER = stringPreferencesKey("library_root_folder_id")
        val SOURCE_ROOT = stringPreferencesKey("source_root")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh_enabled")
        val AUTO_REFRESH_MIN = intPreferencesKey("auto_refresh_minutes")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
        val AUTO_SYNC_MIN = intPreferencesKey("auto_sync_minutes")
        val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("sync_charging_only")
        val CROSSFADE_MS = intPreferencesKey("crossfade_ms")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            libraryRootFolderId = prefs[Keys.ROOT_FOLDER],
            sourceRoot = prefs[Keys.SOURCE_ROOT] ?: defaults.sourceRoot,
            autoRefreshEnabled = prefs[Keys.AUTO_REFRESH] ?: defaults.autoRefreshEnabled,
            autoRefreshInterval = prefs[Keys.AUTO_REFRESH_MIN]
                ?.let(SyncInterval::fromMinutes) ?: defaults.autoRefreshInterval,
            autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: defaults.autoSyncEnabled,
            autoSyncInterval = prefs[Keys.AUTO_SYNC_MIN]
                ?.let(SyncInterval::fromMinutes) ?: defaults.autoSyncInterval,
            syncOnWifiOnly = prefs[Keys.WIFI_ONLY] ?: defaults.syncOnWifiOnly,
            syncOnlyWhenCharging = prefs[Keys.CHARGING_ONLY] ?: defaults.syncOnlyWhenCharging,
            crossfadeMs = prefs[Keys.CROSSFADE_MS] ?: defaults.crossfadeMs,
        )
    }

    suspend fun setLibraryRootFolderId(id: String) = edit { it[Keys.ROOT_FOLDER] = id }
    suspend fun setSourceRoot(root: String) = edit { it[Keys.SOURCE_ROOT] = root }
    suspend fun setAutoRefreshEnabled(on: Boolean) = edit { it[Keys.AUTO_REFRESH] = on }
    suspend fun setAutoRefreshInterval(i: SyncInterval) =
        edit { it[Keys.AUTO_REFRESH_MIN] = i.minutes.toInt() }
    suspend fun setAutoSyncEnabled(on: Boolean) = edit { it[Keys.AUTO_SYNC] = on }
    suspend fun setAutoSyncInterval(i: SyncInterval) =
        edit { it[Keys.AUTO_SYNC_MIN] = i.minutes.toInt() }
    suspend fun setSyncOnWifiOnly(on: Boolean) = edit { it[Keys.WIFI_ONLY] = on }
    suspend fun setSyncOnlyWhenCharging(on: Boolean) = edit { it[Keys.CHARGING_ONLY] = on }
    suspend fun setCrossfadeMs(ms: Int) = edit {
        it[Keys.CROSSFADE_MS] = ms.coerceIn(0, AppSettings.MAX_CROSSFADE_MS)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
