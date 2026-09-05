package com.thunderplay.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.thunderplay.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the periodic jobs in step with Settings.
 *
 * Refresh and sync are separate jobs on purpose: refresh is a handful of cheap API calls that can
 * run on any connection, while sync moves audio and should usually wait for Wi-Fi.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun apply(settings: AppSettings) {
        scheduleRefresh(settings)
        scheduleSync(settings)
        scheduleShareCleanup()
        scheduleFirebaseCleanup()
    }

    private fun scheduleRefresh(settings: AppSettings) {
        if (!settings.autoRefreshEnabled) {
            workManager.cancelUniqueWork(RefreshWorker.NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            settings.autoRefreshInterval.minutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()

        // UPDATE rather than KEEP, so changing the interval in Settings actually reschedules
        // instead of silently keeping the old cadence.
        workManager.enqueueUniquePeriodicWork(
            RefreshWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun scheduleSync(settings: AppSettings) {
        if (!settings.autoSyncEnabled) {
            workManager.cancelUniqueWork(SyncWorker.NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.syncOnWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresCharging(settings.syncOnlyWhenCharging)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(
                settings.autoSyncInterval.minutes,
                TimeUnit.MINUTES,
            ).setConstraints(constraints).build(),
        )
    }

    /** Always on: an expired share should stop costing storage whatever the sync settings say. */
    private fun scheduleShareCleanup() {
        workManager.enqueueUniquePeriodicWork(
            ShareCleanupWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ShareCleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }

    /**
     * Weekly audit of Drive vs Firebase: purges metadata for tracks that no longer exist in Drive.
     * Scheduled once a week at nighttime (03:00 AM).
     */
    private fun scheduleFirebaseCleanup() {
        val initialDelay = calculateDelayToNighttime()
        val request = PeriodicWorkRequestBuilder<FirebaseCleanupWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            FirebaseCleanupWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

/**
 * Calculates the delay in milliseconds from [now] to the next occurrence of [targetHour]:00.
 */
internal fun calculateDelayToNighttime(
    now: ZonedDateTime = ZonedDateTime.now(),
    targetHour: Int = 3,
): Long {
    var target = now.withHour(targetHour).withMinute(0).withSecond(0).withNano(0)
    if (!target.isAfter(now)) {
        target = target.plusDays(1)
    }
    return Duration.between(now, target).toMillis()
}

