package com.thunderplay.sync

import android.util.Log
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.DriveEntry
import com.thunderplay.drive.DriveRepository
import com.thunderplay.library.TrackPath
import com.thunderplay.settings.AppSettings
import com.thunderplay.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    /** Rows dropped because they are no longer in Drive. */
    val removed: Int,
)

/** Reported as the walk proceeds, so the UI can show the catalog filling up. */
sealed interface RefreshProgress {
    /** Tracks are already in the database by the time this is emitted. */
    data class Scanning(val tracksFound: Int, val foldersScanned: Int) : RefreshProgress

    /** Matching playable files to their source WAVs, which enables trashing originals. */
    data class Linking(val tracksFound: Int) : RefreshProgress

    data class Complete(val result: RefreshResult) : RefreshProgress
}

class LibraryNotShared(message: String) : IllegalStateException(message)

/**
 * Rebuilds the local catalog from Drive, writing as it goes.
 *
 * Two things make this incremental rather than one big swap at the end:
 *
 * 1. Tracks are upserted per folder, so the list fills in over a few seconds instead of staying
 *    empty for the whole walk. Room's Flow pushes each batch straight to the UI.
 * 2. The source-WAV join happens *after* the playable tree is in place. The join is only needed
 *    to trash originals later; making the visible catalog wait on a second full tree walk would
 *    double the time to first result for no benefit the user can see.
 *
 * Rows that have disappeared from Drive are deleted at the very end, once the walk is known to
 * have completed. Deleting earlier would mean a half-finished walk wiped tracks it simply had not
 * reached yet.
 */
@Singleton
class LibraryRefresher @Inject constructor(
    private val drive: DriveRepository,
    private val trackDao: TrackDao,
    private val settings: SettingsRepository,
) {

    /** Convenience for callers with nothing to report to, such as the periodic worker. */
    suspend fun refresh(): RefreshResult {
        var last: RefreshResult? = null
        refreshProgressively().collect { if (it is RefreshProgress.Complete) last = it.result }
        return last ?: RefreshResult(0, 0, 0, 0)
    }

    fun refreshProgressively(): Flow<RefreshProgress> = flow {
        val current = settings.settings.first()
        val rootId = resolveRootFolder(current)

        val playableRoot = drive.findChildFolder(rootId, current.sourceRoot)
            ?: throw LibraryNotShared(
                "No '${current.sourceRoot}' folder inside ${AppSettings.LIBRARY_FOLDER_NAME}. " +
                    "Run tools/transcode/transcode.ps1 to create it.",
            )

        val seenIds = mutableSetOf<String>()
        val byPathKey = mutableMapOf<String, String>()
        var found = 0

        // Walking the WAVs directly means every track is its own source; the join below only earns
        // its keep when the catalog is pointed at the transcoded mirror instead.
        val transcoded = current.sourceRoot != AppSettings.DEFAULT_SOURCE_ROOT

        // The A/B winners' folder is walked as a second root, so keeping a take does not exile it
        // from the library. Verdicts are cleared only for the main root: a file reappearing there
        // was put back by hand and wants judging again, whereas one in good/ is simply sitting
        // where the judgement left it.
        val playableRoots = listOfNotNull(
            playableRoot.id to true,
            abFolder(rootId, AppSettings.AB_GOOD_FOLDER_NAME)?.let { it to false },
        )

        // Each walk counts its own folders from one, so they are accumulated here - otherwise the
        // progress readout would jump backwards when the second root starts.
        var foldersScanned = 0
        for ((folderId, clearVerdict) in playableRoots) {
            var inThisRoot = 0
            drive.walkStreaming(folderId).collect { batch ->
                val audio = batch.files.filter { it.isAudio }
                if (audio.isNotEmpty()) {
                    // Written before emitting, so the count the UI shows is already on screen.
                    trackDao.upsertAll(
                        audio.map { toTrack(it, ownSource = !transcoded) },
                        clearAbVerdict = clearVerdict,
                    )
                    audio.forEach {
                        seenIds += it.file.id
                        byPathKey[it.pathKey] = it.file.id
                    }
                    found += audio.size
                }
                inThisRoot = batch.foldersScanned
                emit(RefreshProgress.Scanning(found, foldersScanned + inThisRoot))
            }
            foldersScanned += inThisRoot
        }

        emit(RefreshProgress.Linking(found))

        // Pairing a transcoded .m4a with its original is the only reason for a second walk, and
        // only the transcoded mirror needs it - the WAVs are already their own source.
        var awaitingTranscode = 0
        var linked = found
        if (transcoded) {
            linked = 0
            drive.findChildFolder(rootId, AppSettings.DEFAULT_SOURCE_ROOT)?.let { wavRoot ->
                drive.walkStreaming(wavRoot.id).collect { batch ->
                    for (wav in batch.files.filter { it.isAudio }) {
                        val playableId = byPathKey[wav.pathKey]
                        if (playableId == null) {
                            awaitingTranscode++
                            continue
                        }
                        trackDao.applySource(
                            driveId = playableId,
                            sourceWavDriveId = wav.file.id,
                            // The WAV's creation time is what "recently added" sorts on: it
                            // survives re-encoding, whereas the .m4a's timestamp resets each run.
                            addedAt = epochMillis(wav.file.createdTime),
                        )
                        linked++
                    }
                }
            }
        }

        val removed = trackDao.deleteMissing(seenIds.toList())

        val result = RefreshResult(
            tracks = found,
            awaitingTranscode = awaitingTranscode,
            orphanedOutputs = found - linked,
            removed = removed,
        )
        Log.i(TAG, "Refreshed: $result")
        emit(RefreshProgress.Complete(result))
    }

    /**
     * `_ThunderPlayAB/<verdict>/<leaf>`, or null before anything has been judged.
     *
     * Resolved from the library root rather than created, so a refresh never brings the A/B tree
     * into existence as a side effect.
     */
    private suspend fun abFolder(rootId: String, verdict: String): String? {
        val ab = drive.findChildFolder(rootId, AppSettings.AB_FOLDER_NAME) ?: return null
        return drive.findChildFolder(ab.id, verdict)?.id
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

    /**
     * [ownSource] is set when the catalog walks the WAVs themselves, so a track is its own
     * original and there is no second file to pair it with - or to move when it is trashed.
     */
    private fun toTrack(entry: DriveEntry, ownSource: Boolean): TrackEntity {
        val path = TrackPath.parse(entry.relativePath)
        return TrackEntity(
            driveId = entry.file.id,
            title = path.title,
            relativePath = entry.relativePath,
            category = path.category,
            level = path.level,
            sizeBytes = entry.file.size,
            md5Checksum = entry.file.md5Checksum,
            addedAt = epochMillis(entry.file.createdTime),
            modifiedAt = epochMillis(entry.file.modifiedTime),
            sourceWavDriveId = entry.file.id.takeIf { ownSource },
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
