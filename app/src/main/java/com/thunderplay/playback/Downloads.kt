package com.thunderplay.playback

import android.app.Notification
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.thunderplay.R
import com.thunderplay.data.TrackEntity
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

const val DOWNLOAD_CHANNEL_ID = "thunderplay_downloads"
private const val DOWNLOAD_NOTIFICATION_ID = 4711
private const val DOWNLOAD_WORK_NAME = "thunderplay-downloads"

/** How a track exists on this device. */
enum class LocalState { Remote, Downloading, Downloaded, Failed }

/**
 * Enqueues and reports on track downloads.
 *
 * Media3's [DownloadManager] is used rather than a hand-rolled downloader because it already
 * handles parallelism, retries, progress and a foreground notification - and it writes into the
 * very same cache that streaming fills, so playing a track and downloading it are the same bytes.
 */
@OptIn(UnstableApi::class)
@Singleton
class DownloadsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
) {

    fun request(track: TrackEntity): DownloadRequest =
        DownloadRequest.Builder(track.driveId, android.net.Uri.parse(track.streamUri()))
            // Must match the MediaItem's custom cache key, or a downloaded track would stream
            // again instead of playing from disk.
            .setCustomCacheKey(track.driveId)
            .build()

    fun download(track: TrackEntity) {
        DownloadService.sendAddDownload(
            context,
            TrackDownloadService::class.java,
            request(track),
            /* foreground = */ false,
        )
    }

    fun downloadAll(tracks: List<TrackEntity>) = tracks.forEach(::download)

    /** Removes the local copy. The track stays in the library and remains streamable. */
    fun removeDownload(driveId: String) {
        DownloadService.sendRemoveDownload(
            context,
            TrackDownloadService::class.java,
            driveId,
            /* foreground = */ false,
        )
    }

    fun removeAllDownloads() {
        DownloadService.sendRemoveAllDownloads(
            context,
            TrackDownloadService::class.java,
            /* foreground = */ false,
        )
    }

    /**
     * The download index is the single source of truth for what is on disk, so the UI never has
     * to keep a parallel copy that could drift.
     */
    fun observeStates(): Flow<Map<String, LocalState>> = callbackFlow {
        fun snapshot(): Map<String, LocalState> {
            val out = mutableMapOf<String, LocalState>()
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    out[download.request.id] = when (download.state) {
                        Download.STATE_COMPLETED -> LocalState.Downloaded
                        Download.STATE_FAILED -> LocalState.Failed
                        else -> LocalState.Downloading
                    }
                }
            }
            return out
        }

        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                manager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                trySend(snapshot())
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                trySend(snapshot())
            }
        }

        trySend(snapshot())
        downloadManager.addListener(listener)
        awaitClose { downloadManager.removeListener(listener) }
    }
}

/**
 * Foreground service that runs the download queue.
 *
 * [WorkManagerScheduler] restarts unfinished downloads after a reboot or when the network
 * requirement is met again.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class TrackDownloadService : DownloadService(
    DOWNLOAD_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {

    @Inject lateinit var downloads: DownloadManager

    @Inject lateinit var notifications: DownloadNotificationHelper

    override fun getDownloadManager(): DownloadManager = downloads

    override fun getScheduler(): Scheduler = WorkManagerScheduler(this, DOWNLOAD_WORK_NAME)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification = notifications.buildProgressNotification(
        /* context = */ this,
        /* smallIcon = */ android.R.drawable.stat_sys_download,
        /* contentIntent = */ null,
        /* message = */ null,
        /* downloads = */ downloads,
        /* notMetRequirements = */ notMetRequirements,
    )
}

/** Constraints applied to the download queue, mirroring the sync settings. */
@OptIn(UnstableApi::class)
fun downloadRequirements(wifiOnly: Boolean, chargingOnly: Boolean): Requirements {
    var flags = if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK
    if (chargingOnly) flags = flags or Requirements.DEVICE_CHARGING
    return Requirements(flags)
}
