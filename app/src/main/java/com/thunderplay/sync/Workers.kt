package com.thunderplay.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thunderplay.diagnostics.DiagnosticsLog
import com.thunderplay.data.PlayDao
import com.thunderplay.data.TrackDao
import com.thunderplay.playback.DownloadsRepository
import com.thunderplay.playback.LocalState
import com.thunderplay.playlist.PlaylistRepository
import com.thunderplay.playlist.ShareService
import com.thunderplay.stats.FirestoreStats
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Re-lists Drive and pulls remote stats.
 *
 * Cheap and network-only, so it can run often; the heavy work is [SyncWorker].
 */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refresher: LibraryRefresher,
    private val stats: FirestoreStats,
    private val playlists: PlaylistRepository,
    private val trackDao: TrackDao,
    private val playDao: PlayDao,
    private val diagnostics: DiagnosticsLog,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = refresher.refresh()

            // Catalog first: pulling stats for tracks Room has not seen yet would drop them.
            if (stats.ensureSignedIn()) {
                stats.pullTrackStats(trackDao)
                stats.pullPlayHistory(playDao)
                playlists.pull()
            }

            Log.i(TAG, "Refreshed $result")
            Result.success()
        } catch (e: Exception) {
            diagnostics.error(TAG, "Scheduled refresh failed", e)
            // Retry rather than fail: the usual cause is a dropped connection mid-walk.
            Result.retry()
        }
    }

    companion object {
        const val NAME = "thunderplay-refresh"
        private const val TAG = "RefreshWorker"
    }
}

/**
 * Pre-fetches anything not already on disk.
 *
 * Enqueueing is all this does; Media3's DownloadManager owns the actual transfers, retries and
 * progress notification, and survives this worker finishing.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val downloads: DownloadsRepository,
    private val diagnostics: DiagnosticsLog,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val local = downloads.observeStates().first()
            val missing = trackDao.allActive().filter {
                local[it.driveId] != LocalState.Downloaded
            }
            if (missing.isNotEmpty()) {
                Log.i(TAG, "Queueing ${missing.size} download(s)")
                downloads.downloadAll(missing)
            }
            Result.success()
        } catch (e: Exception) {
            diagnostics.error(TAG, "Scheduled sync failed", e)
            Result.retry()
        }
    }

    companion object {
        const val NAME = "thunderplay-sync"
        private const val TAG = "SyncWorker"
    }
}

/**
 * Deletes shares whose expiry has passed.
 *
 * The rules already refuse to serve a lapsed share, but only deleting the objects actually stops
 * storing - and paying for - the audio, and removes it if a rule is ever loosened.
 */
@HiltWorker
class ShareCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val shares: ShareService,
    private val diagnostics: DiagnosticsLog,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val purged = shares.purgeExpired()
            if (purged > 0) Log.i(TAG, "Purged $purged expired share(s)")
            Result.success()
        } catch (e: Exception) {
            diagnostics.error(TAG, "Expired-share cleanup failed", e)
            Result.retry()
        }
    }

    companion object {
        const val NAME = "thunderplay-share-cleanup"
        private const val TAG = "ShareCleanup"
    }
}

/**
 * Audits Google Drive vs Firebase Firestore and purges orphan entries.
 *
 * Runs once a week at nighttime (03:00 AM).
 */
@HiltWorker
class FirebaseCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cleaner: FirebaseCleanupService,
    private val stats: FirestoreStats,
    private val diagnostics: DiagnosticsLog,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!stats.isAvailable || !stats.ensureSignedIn()) {
            Log.i(TAG, "Firebase not configured or sign-in unavailable; skipping cleanup")
            return Result.success()
        }

        return try {
            val summary = cleaner.cleanupOrphans()
            Log.i(TAG, "Orphan cleanup succeeded: $summary")
            Result.success()
        } catch (e: Exception) {
            diagnostics.error(TAG, "Orphan cleanup failed", e)
            Result.retry()
        }
    }

    companion object {
        const val NAME = "thunderplay-firebase-cleanup"
        private const val TAG = "FirebaseCleanup"
    }
}

