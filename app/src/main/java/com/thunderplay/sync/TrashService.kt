package com.thunderplay.sync

import android.util.Log
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.DriveRepository
import com.thunderplay.playback.DownloadsRepository
import com.thunderplay.settings.AppSettings
import com.thunderplay.settings.SettingsRepository
import com.thunderplay.stats.FirestoreStats
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class TrashResult(
    val movedPlayable: Boolean,
    val movedSource: Boolean,
)

/**
 * Moves tracks out of the library without ever deleting anything.
 *
 * The trash folder sits at the library root, deliberately *outside* `music/` and `music-mobile/`:
 * inside either one, the next refresh would simply list the tracks again.
 *
 * Both the playable `.m4a` and its source `.wav` are moved. Moving only the derived file would let
 * the next transcode run regenerate it from the surviving original, so the track would come back.
 */
@Singleton
class TrashService @Inject constructor(
    private val drive: DriveRepository,
    private val trackDao: TrackDao,
    private val downloads: DownloadsRepository,
    private val settings: SettingsRepository,
    private val stats: FirestoreStats,
) {

    suspend fun moveToTrash(track: TrackEntity): TrashResult {
        val current = settings.settings.first()
        val rootId = current.libraryRootFolderId
            ?: drive.findFolderByName(AppSettings.LIBRARY_FOLDER_NAME)?.id
            ?: error("Library folder is not visible to the service account.")

        val trash = drive.ensureChildFolder(rootId, AppSettings.TRASH_FOLDER_NAME)

        val movedPlayable = move(track.driveId, trash.id)
        val movedSource = track.sourceWavDriveId?.let { move(it, trash.id) } ?: false

        if (movedPlayable) {
            // The local copy is dead weight once the track has left the library.
            downloads.removeDownload(track.driveId)
            trackDao.markTrashed(track.driveId, System.currentTimeMillis())
            recordOrigin(track)
        }

        if (!movedSource && track.sourceWavDriveId != null) {
            Log.w(
                TAG,
                "Moved ${track.title} but not its source WAV; a transcode run may restore it.",
            )
        }
        return TrashResult(movedPlayable, movedSource)
    }

    private suspend fun move(fileId: String, trashFolderId: String): Boolean = try {
        // Re-read the file first: the cached parent list can be stale, and moving with the wrong
        // removeParents leaves the file attached in two folders at once.
        val file = drive.fileById(fileId)
        drive.moveTo(file, trashFolderId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not move $fileId to trash", e)
        false
    }

    private fun recordOrigin(track: TrackEntity) {
        // Keeping the original folder makes a future "restore" possible without guesswork.
        stats.pushTrashed(track.driveId, track.relativePath.substringBeforeLast('/', ""))
    }

    private companion object {
        const val TAG = "TrashService"
    }
}
