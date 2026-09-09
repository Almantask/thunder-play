package com.thunderplay.sync

import android.util.Log
import com.thunderplay.diagnostics.DiagnosticsLog
import com.thunderplay.drive.DriveRepository
import com.thunderplay.playlist.PlaylistRepository
import com.thunderplay.settings.AppSettings
import com.thunderplay.settings.SettingsRepository
import com.thunderplay.stats.FirestoreStats
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class CleanupSummary(
    val driveTracksFound: Int,
    val firebaseTracksChecked: Int,
    val orphanTracksDeleted: Int,
    val orphanPlaysDeleted: Int,
    val playlistsUpdated: Int,
)

class EmptyDriveLibraryException(message: String) : IllegalStateException(message)

/**
 * Cross-references audio files in Google Drive against metadata in Firebase Firestore.
 *
 * If a track document in Firestore no longer exists in Google Drive (not in the catalog root,
 * nor in _ThunderPlayTrash, nor in either _ThunderPlayAB batch), it is
 * treated as an orphan and purged along with its associated play history and playlist
 * references.
 */
@Singleton
class FirebaseCleanupService @Inject constructor(
    private val drive: DriveRepository,
    private val stats: FirestoreStats,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
) {
    suspend fun cleanupOrphans(): CleanupSummary {
        val current = settings.settings.first()
        val rootId = current.libraryRootFolderId
            ?: drive.findFolderByName(AppSettings.LIBRARY_FOLDER_NAME)?.id
            ?: throw IllegalStateException("Library folder is not visible to the service account.")

        // Collect all file IDs in Drive under the catalog root (music/ by default)
        val playableRoot = drive.findChildFolder(rootId, current.sourceRoot)
            ?: throw IllegalStateException("No '${current.sourceRoot}' folder inside ${AppSettings.LIBRARY_FOLDER_NAME}.")
        val playableEntries = drive.walk(playableRoot.id).filter { it.isAudio }

        // Also collect file IDs in _ThunderPlayTrash/ so trashing a track does not delete its stats
        val trashRoot = drive.findChildFolder(rootId, AppSettings.TRASH_FOLDER_NAME)
        val trashEntries = trashRoot?.let { drive.walk(it.id).filter { it.isAudio } }.orEmpty()

        // And in _ThunderPlayAB/, so judging a take does not cost it its ratings and play history.
        // One walk covers both verdicts, since walk() recurses.
        val abRoot = drive.findChildFolder(rootId, AppSettings.AB_FOLDER_NAME)
        val abEntries = abRoot?.let { drive.walk(it.id).filter { it.isAudio } }.orEmpty()

        val driveFileIds = (playableEntries + trashEntries + abEntries).map { it.file.id }.toSet()

        // Critical safety check: if Drive returned 0 files, abort to prevent catastrophic wipe
        if (driveFileIds.isEmpty()) {
            throw EmptyDriveLibraryException("Drive returned 0 audio files; aborting cleanup to prevent data loss.")
        }

        // Query Firestore for all track IDs
        val firebaseTrackIds = stats.getAllTrackIds()
        if (firebaseTrackIds.isEmpty()) {
            return CleanupSummary(
                driveTracksFound = driveFileIds.size,
                firebaseTracksChecked = 0,
                orphanTracksDeleted = 0,
                orphanPlaysDeleted = 0,
                playlistsUpdated = 0,
            )
        }

        val confirmedOrphans = findConfirmedOrphans(
            driveFileIds = driveFileIds,
            firebaseTrackIds = firebaseTrackIds,
            checkExistsInDrive = { candidateId ->
                try {
                    val file = drive.fileById(candidateId)
                    !file.isFolder && (file.parents?.isNotEmpty() == true)
                } catch (e: Exception) {
                    false
                }
            },
        )

        var orphanPlaysDeleted = 0
        var playlistsUpdated = 0

        if (confirmedOrphans.isNotEmpty()) {
            Log.i(TAG, "Purging ${confirmedOrphans.size} orphan track(s) from Firebase: $confirmedOrphans")
            stats.deleteTrackStats(confirmedOrphans)
            orphanPlaysDeleted = stats.deletePlaysForTracks(confirmedOrphans)
            playlistsUpdated = playlists.removeTracksFromAllPlaylists(confirmedOrphans)
        }

        val summary = CleanupSummary(
            driveTracksFound = driveFileIds.size,
            firebaseTracksChecked = firebaseTrackIds.size,
            orphanTracksDeleted = confirmedOrphans.size,
            orphanPlaysDeleted = orphanPlaysDeleted,
            playlistsUpdated = playlistsUpdated,
        )
        diagnostics.info(TAG, "Completed orphan cleanup: $summary")
        return summary
    }

    /**
     * Identifies candidate orphan track IDs (present in Firebase but absent from Drive)
     * and confirms each against [checkExistsInDrive].
     */
    internal suspend fun findConfirmedOrphans(
        driveFileIds: Set<String>,
        firebaseTrackIds: List<String>,
        checkExistsInDrive: suspend (String) -> Boolean,
    ): Set<String> {
        if (driveFileIds.isEmpty()) {
            throw EmptyDriveLibraryException("Drive returned 0 audio files; aborting cleanup to prevent data loss.")
        }
        val candidates = firebaseTrackIds.filterNot { it in driveFileIds }
        if (candidates.isEmpty()) return emptySet()

        val confirmed = mutableSetOf<String>()
        for (candidate in candidates) {
            if (!checkExistsInDrive(candidate)) {
                confirmed += candidate
            }
        }
        return confirmed
    }

    companion object {
        private const val TAG = "FirebaseCleanup"
    }
}
