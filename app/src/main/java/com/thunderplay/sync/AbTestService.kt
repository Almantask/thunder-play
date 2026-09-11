package com.thunderplay.sync

import android.util.Log
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.DriveRepository
import com.thunderplay.library.AbGroup
import com.thunderplay.library.AbGrouping
import com.thunderplay.library.AbVerdict
import com.thunderplay.library.WavInfo
import com.thunderplay.playback.DownloadsRepository
import com.thunderplay.settings.AppSettings
import com.thunderplay.settings.SettingsRepository
import com.thunderplay.stats.FirestoreStats
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class AbJudgeResult(
    val moved: Int,
    /** Titles whose playable file would not move; no verdict was recorded for these. */
    val failed: List<String>,
    /**
     * Titles whose separate source WAV would not move. Only possible on the transcoded layout;
     * on a WAV-only library a track is its own source and this is always empty.
     */
    val orphanedSources: List<String>,
)

/**
 * Files the takes of one cue into the two A/B batches.
 *
 * Built on [TrashService]'s shape, because the problem is the same one: move a track out of the
 * library without deleting anything, and take any second file it owns with it. On the transcoded
 * layout leaving a loser's WAV behind would let the next transcode run regenerate its .m4a and
 * bring the cue back as a candidate.
 *
 * The prompt is written into Drive's own file metadata rather than a sidecar. A service account has
 * no storage quota, so Drive accepts an empty file record and rejects any content with 403
 * storageQuotaExceeded - writing a .txt next to the track is simply not available. Metadata costs
 * nothing, moves with the file, and shows up in Drive's details pane.
 */
@Singleton
class AbTestService @Inject constructor(
    private val drive: DriveRepository,
    private val trackDao: TrackDao,
    private val downloads: DownloadsRepository,
    private val settings: SettingsRepository,
    private val stats: FirestoreStats,
) {

    /** The prompt and instrumentation the generator wrote into the source WAV, if it is reachable. */
    suspend fun readPrompt(track: TrackEntity): WavInfo? {
        val wavId = track.sourceWavDriveId ?: return null
        val head = drive.readHead(wavId, WavInfo.HEAD_BYTES) ?: return null
        return WavInfo.parse(head)
    }

    /**
     * Moves the keepers into `good/` and every beaten take into `bad/`.
     *
     * A drawn take is a keeper too, and lands in `good/` beside the winner: on the PC that folder
     * answers "what did I keep", and splitting it would make the answer live in two places. The
     * draw itself is not lost - it is on the row, on the Drive file's own properties and in
     * Firestore, which is where the question "was this picked or merely not rejected" is asked.
     *
     * Ordering is chosen so that any prefix of it leaves a state something can finish:
     *
     * - Folders first. Metadata-only, so quota-safe, and nothing has moved if it fails.
     * - Tag, then move. A crash in between leaves a file tagged but still in the library, which the
     *   next refresh re-lists as a candidate - self-healing. Moving first would leave it relocated
     *   but untagged, invisible to every walk.
     * - Room only after the move succeeded, so a row is never marked judged while its file is still
     *   sitting in the library.
     * - Firestore last and fire-and-forget, so an outage cannot block the local truth.
     *
     * Losers are judged before the keepers. A failure part-way then leaves the library in the
     * intended end state minus the keepers' relocation; the reverse would leave the winner gone and
     * the losers still competing, which reads as the cue having lost its best take.
     *
     * [winnerDriveId] is null when every round was drawn: nothing beat anything, and inventing a
     * winner would put a preference in the record that the user declined to express.
     */
    suspend fun judge(
        group: AbGroup,
        winnerDriveId: String?,
        prompts: Map<String, String> = emptyMap(),
        tiedDriveIds: Set<String> = emptySet(),
    ): AbJudgeResult {
        val verdicts = AbGrouping.verdicts(group, winnerDriveId, tiedDriveIds)

        val current = settings.settings.first()
        val rootId = current.libraryRootFolderId
            ?: drive.findFolderByName(AppSettings.LIBRARY_FOLDER_NAME)?.id
            ?: error("Library folder is not visible to the service account.")

        // Resolved from the library root, never from inside the catalog tree: within the walk the
        // next refresh would list these files again and un-judge the whole batch.
        val ab = drive.ensureChildFolder(rootId, AppSettings.AB_FOLDER_NAME)
        // Resolved by folder name rather than by verdict: two verdicts share `good/`, and asking
        // Drive to ensure the same folder twice is a wasted round trip on every judgement.
        val folderIds = AbVerdict.entries.map(::folderNameFor).distinct()
            .associateWith { name -> drive.ensureChildFolder(ab.id, name).id }
        val destinations = AbVerdict.entries.associateWith { folderIds.getValue(folderNameFor(it)) }

        val judgedAt = System.currentTimeMillis()
        val failed = mutableListOf<String>()
        val orphanedSources = mutableListOf<String>()
        var moved = 0

        // Spelled out rather than taken from the enum's ordinal, which orders by declaration and
        // would quietly reverse this the first time someone tidies the enum.
        val losersFirst = group.takes.sortedBy {
            when (verdicts.getValue(it.driveId)) {
                AbVerdict.Bad -> 0
                AbVerdict.Tie -> 1
                AbVerdict.Good -> 2
            }
        }
        for (take in losersFirst) {
            val verdict = verdicts.getValue(take.driveId)
            val destination = destinations.getValue(verdict)
            val prompt = prompts[take.driveId] ?: take.prompt ?: take.abPrompt

            tag(take, verdict, winnerDriveId, group.key, prompt)

            // The category folders are recreated under the batch. Without them the winner would
            // come back from the good/ walk as "Uncategorised", and neither batch would be
            // browsable on the PC.
            val folders = take.relativePath.split('/').dropLast(1)
            if (!move(take.driveId, destination, folders)) {
                failed += take.title
                continue
            }

            // Only the transcoded layout has a second file to bring along; on a WAV-only library a
            // track is its own source and has already moved.
            val wavId = take.sourceWavDriveId
            if (wavId != null && wavId != take.driveId && !move(wavId, destination, folders)) {
                orphanedSources += take.title
            }

            // A loser's local copy is dead weight; the winner stays in the library, so keep it.
            if (verdict == AbVerdict.Bad) downloads.removeDownload(take.driveId)
            trackDao.applyAbVerdict(
                driveId = take.driveId,
                verdict = verdict.stored,
                judgedAt = judgedAt,
                winnerDriveId = winnerDriveId,
                prompt = prompt,
            )
            stats.pushAbVerdict(
                driveId = take.driveId,
                verdict = verdict.stored,
                winnerDriveId = winnerDriveId,
                groupKey = group.key,
                prompt = prompt,
                originalFolderPath = take.relativePath.substringBeforeLast('/', ""),
            )
            moved++
        }

        return AbJudgeResult(moved, failed, orphanedSources)
    }

    private fun folderNameFor(verdict: AbVerdict) = when (verdict) {
        AbVerdict.Good, AbVerdict.Tie -> AppSettings.AB_GOOD_FOLDER_NAME
        AbVerdict.Bad -> AppSettings.AB_BAD_FOLDER_NAME
    }

    /** Best-effort: a track whose metadata will not write is still worth filing. */
    private suspend fun tag(
        take: TrackEntity,
        verdict: AbVerdict,
        winnerDriveId: String?,
        groupKey: String,
        prompt: String?,
    ) {
        // appProperties caps each key/value pair at 124 bytes, which a 120-character prompt can
        // exceed on its own - so prose goes in the description and only short values go here.
        val properties = buildMap {
            put("abVerdict", verdict.stored)
            // Left out rather than written empty when the cue was only ever drawn: Drive keeps
            // whatever is written, and "abWinner=" would read as a winner that could not be named.
            winnerDriveId?.let { put("abWinner", it) }
            put("abJudgedAt", System.currentTimeMillis().toString())
        }
        for (fileId in listOfNotNull(take.driveId, take.sourceWavDriveId).distinct()) {
            try {
                drive.writeMetadata(fileId, description = prompt, appProperties = properties)
            } catch (e: Exception) {
                Log.w(TAG, "Could not tag $fileId with its A/B verdict", e)
            }
        }
    }

    private suspend fun move(
        fileId: String,
        batchRootId: String,
        folders: List<String>,
    ): Boolean = try {
        val destination = ensurePath(batchRootId, folders)
        // Re-read the file first: the cached parent list can be stale, and moving with the wrong
        // removeParents leaves the file attached in two folders at once.
        drive.moveTo(drive.fileById(fileId), destination)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not move $fileId into the A/B batch", e)
        false
    }

    private suspend fun ensurePath(rootId: String, folders: List<String>): String =
        folders.fold(rootId) { parent, name -> drive.ensureChildFolder(parent, name).id }

    private companion object {
        const val TAG = "AbTestService"
    }
}
