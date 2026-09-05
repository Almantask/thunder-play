package com.thunderplay.sync

import android.util.Log
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.DriveEntry
import com.thunderplay.drive.DriveRepository
import com.thunderplay.library.TrackPath
import com.thunderplay.settings.AppSettings
import com.thunderplay.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

data class RefreshResult(
    val tracks: Int,
    /** WAVs with no transcoded counterpart yet - the PC script has not caught up. */
    val awaitingTranscode: Int,
    /** Playable files whose source WAV is missing; they cannot be fully trashed. */
    val orphanedOutputs: Int,
)

class LibraryNotShared(message: String) : IllegalStateException(message)

/**
 * Rebuilds the local catalog from Drive.
 *
 * Both `music-mobile/` and `music/` are walked and joined on their relative path minus extension.
 * The join is not cosmetic: trashing a track has to move the source .wav as well as the .m4a, and
 * that needs the WAV's Drive *file id*. It also supplies the WAV's createdTime, which is what
 * "recently added" sorts on - the .m4a's own timestamp resets every time the file is re-encoded.
 */
@Singleton
class LibraryRefresher @Inject constructor(
    private val drive: DriveRepository,
    private val trackDao: TrackDao,
    private val settings: SettingsRepository,
) {

    suspend fun refresh(): RefreshResult {
        val current = settings.settings.first()
        val rootId = resolveRootFolder(current)

        val playableRoot = drive.findChildFolder(rootId, current.sourceRoot)
            ?: throw LibraryNotShared(
                "No '${current.sourceRoot}' folder inside ${AppSettings.LIBRARY_FOLDER_NAME}. " +
                    "Run tools/transcode/transcode.ps1 to create it.",
            )
        // Drop non-audio: music-mobile/ also holds the transcode script's .manifest.json.
        val playable = drive.walk(playableRoot.id).filter { it.isAudio }

        // The WAV tree is optional: without it the app still plays, it just cannot trash originals.
        val sources = drive.findChildFolder(rootId, AppSettings.WAV_SOURCE_ROOT)
            ?.let { root -> drive.walk(root.id).filter { it.isAudio } }
            .orEmpty()
        val sourcesByKey = sources.associateBy { it.pathKey }

        val tracks = playable.map { entry -> toTrack(entry, sourcesByKey[entry.pathKey]) }
        trackDao.replaceCatalog(tracks)

        val playableKeys = playable.mapTo(mutableSetOf()) { it.pathKey }
        val result = RefreshResult(
            tracks = tracks.size,
            awaitingTranscode = sourcesByKey.keys.count { it !in playableKeys },
            orphanedOutputs = playable.count { sourcesByKey[it.pathKey] == null },
        )
        Log.i(TAG, "Refreshed: $result")
        return result
    }

    private suspend fun resolveRootFolder(current: AppSettings): String {
        current.libraryRootFolderId?.let { return it }
        val found = drive.findFolderByName(AppSettings.LIBRARY_FOLDER_NAME)
            ?: throw LibraryNotShared(
                "Could not see a folder named ${AppSettings.LIBRARY_FOLDER_NAME}. Share it with " +
                    "the service account as Editor (see docs/SETUP.md).",
            )
        settings.setLibraryRootFolderId(found.id)
        return found.id
    }

    private fun toTrack(entry: DriveEntry, source: DriveEntry?): TrackEntity {
        val path = TrackPath.parse(entry.relativePath)
        return TrackEntity(
            driveId = entry.file.id,
            title = path.title,
            relativePath = entry.relativePath,
            category = path.category,
            level = path.level,
            sizeBytes = entry.file.size,
            md5Checksum = entry.file.md5Checksum,
            // Prefer the source WAV's creation time; it survives re-encoding of the .m4a.
            addedAt = epochMillis(source?.file?.createdTime ?: entry.file.createdTime),
            modifiedAt = epochMillis(entry.file.modifiedTime),
            sourceWavDriveId = source?.file?.id,
        )
    }

    private fun epochMillis(rfc3339: String?): Long? = rfc3339?.let {
        try {
            Instant.parse(it).toEpochMilli()
        } catch (e: DateTimeParseException) {
            Log.w(TAG, "Unparseable Drive timestamp: $it", e)
            null
        }
    }

    private companion object {
        const val TAG = "LibraryRefresh"
    }
}
