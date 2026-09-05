package com.thunderplay.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thunderplay.diagnostics.DiagnosticsLog
import com.thunderplay.diagnostics.DiagnosticsExporter
import com.thunderplay.drive.ServiceAccountAuth
import com.thunderplay.playback.DownloadsRepository
import com.thunderplay.playback.MediaCache
import com.thunderplay.settings.AppSettings
import com.thunderplay.settings.SettingsRepository
import com.thunderplay.settings.SyncInterval
import com.thunderplay.stats.FirestoreStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val cacheBytes: Long = 0,
    val driveConfigured: Boolean = false,
    val firebaseConfigured: Boolean = false,
    val diagnosticsStatus: String? = null,
    /** Non-null while the log is being shown, so it can be read without a cable. */
    val logText: String? = null,
    val pendingShare: Intent? = null,
) {
    val cacheLabel: String
        get() = when {
            cacheBytes <= 0 -> "Nothing downloaded yet"
            cacheBytes < 1024 * 1024 -> "$cacheBytes bytes on this device"
            else -> String.format("%.1f MB on this device", cacheBytes / 1024f / 1024f)
        }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val downloads: DownloadsRepository,
    private val auth: ServiceAccountAuth,
    private val firestore: FirestoreStats,
    private val diagnostics: DiagnosticsLog,
    private val exporter: DiagnosticsExporter,
) : ViewModel() {

    // Cache size is a filesystem figure with no change notification, so it is sampled whenever
    // something that could move it happens rather than observed.
    private val cacheBytes = MutableStateFlow(0L)
    private val diagnosticsStatus = MutableStateFlow<String?>(null)
    private val logText = MutableStateFlow<String?>(null)
    private val pendingShare = MutableStateFlow<Intent?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settings.settings,
            cacheBytes,
            diagnosticsStatus,
            combine(logText, pendingShare, ::Pair),
        ) { current, bytes, status, viewing ->
            SettingsUiState(
                settings = current,
                cacheBytes = bytes,
                driveConfigured = auth.isConfigured(),
                firebaseConfigured = firestore.isAvailable,
                diagnosticsStatus = status,
                logText = viewing.first,
                pendingShare = viewing.second,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        sampleCache()
    }

    fun setAutoRefresh(on: Boolean) = viewModelScope.launch { settings.setAutoRefreshEnabled(on) }

    fun setRefreshInterval(interval: SyncInterval) =
        viewModelScope.launch { settings.setAutoRefreshInterval(interval) }

    fun setAutoSync(on: Boolean) = viewModelScope.launch { settings.setAutoSyncEnabled(on) }

    fun setSyncInterval(interval: SyncInterval) =
        viewModelScope.launch { settings.setAutoSyncInterval(interval) }

    fun setWifiOnly(on: Boolean) = viewModelScope.launch { settings.setSyncOnWifiOnly(on) }

    fun setChargingOnly(on: Boolean) =
        viewModelScope.launch { settings.setSyncOnlyWhenCharging(on) }

    fun setCrossfade(ms: Int) = viewModelScope.launch { settings.setCrossfadeMs(ms) }

    fun clearDownloads() {
        downloads.removeAllDownloads()
        sampleCache()
    }

    /** Lets the app play the untranscoded WAVs, at roughly ten times the size. */
    fun toggleSourceRoot() = viewModelScope.launch {
        val next = if (uiState.value.settings.sourceRoot == AppSettings.DEFAULT_SOURCE_ROOT) {
            AppSettings.WAV_SOURCE_ROOT
        } else {
            AppSettings.DEFAULT_SOURCE_ROOT
        }
        settings.setSourceRoot(next)
    }

    /**
     * Shows the log in the app.
     *
     * The app cannot write it to Drive itself: a service account has no storage quota, so Drive
     * rejects the content with 403 even though it accepts the empty file. Reading it here, or
     * sending it via the share sheet as yourself, both work.
     */
    fun viewDiagnostics() = viewModelScope.launch {
        logText.value = diagnostics.snapshot()
    }

    fun dismissDiagnostics() {
        logText.value = null
    }

    fun shareDiagnostics() = viewModelScope.launch {
        pendingShare.value = runCatching { exporter.shareIntent() }.getOrNull()
        if (pendingShare.value == null) diagnosticsStatus.value = "Could not prepare the log"
    }

    fun shareLaunched() {
        pendingShare.value = null
    }

    fun clearDiagnostics() = viewModelScope.launch {
        diagnostics.clear()
        logText.value = null
        diagnosticsStatus.value = "Log cleared"
    }

    private fun sampleCache() {
        cacheBytes.value = runCatching { MediaCache.sizeBytes(context) }.getOrDefault(0L)
    }
}
