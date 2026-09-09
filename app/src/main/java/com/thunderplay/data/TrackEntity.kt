package com.thunderplay.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One playable track, keyed by the Drive file id of its .m4a.
 *
 * Download state is deliberately absent: Media3's DownloadIndex is the single source of truth for
 * what is on disk, so the two can never disagree. Ratings and play counts are mirrored here from
 * Firestore so the library list can filter and sort with one query.
 */
@Entity(
    tableName = "tracks",
    indices = [Index("category"), Index("rating"), Index("playCount"), Index("addedAt")],
)
data class TrackEntity(
    @PrimaryKey val driveId: String,

    /** File name without extension; WAVs carry no tags, so this is the track title. */
    val title: String,
    /** Slash-separated path under the source root, e.g. "Beast Hunt/III/track.m4a". */
    val relativePath: String,
    val category: String,
    val level: String?,

    val sizeBytes: Long?,
    val md5Checksum: String?,
    /** Drive createdTime of the source WAV, in epoch millis; stable across transcode re-runs. */
    val addedAt: Long?,
    val modifiedAt: Long?,

    /** Drive id of the source .wav. Needed to trash the original alongside the .m4a. */
    val sourceWavDriveId: String?,

    // --- mirrored from Firestore ---
    @ColumnInfo(defaultValue = "0") val rating: Int = 0,
    val ratedAt: Long? = null,
    /** Rating to restore when the lock-screen heart is re-tapped, so 5 stars survive an unlike. */
    @ColumnInfo(defaultValue = "0") val lastRating: Int = 0,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val lastPlayedAt: Long? = null,

    /** Set when the track has been moved to the Drive trash folder. */
    val trashedAt: Long? = null,

    // --- read out of the source WAV's RIFF header ---
    /**
     * INAM - what the track was generated from.
     *
     * The filename only carries a lowercased slug of this, truncated at 48 characters, so this is
     * the only full copy outside the file itself. [abPrompt] is the same sentence captured at
     * judging time; this one is filled in for the whole library, judged or not.
     */
    val prompt: String? = null,
    /** IGNR - Instrumental, Ambience or Sound Effects. */
    val genre: String? = null,
    /** IART - the generator's own intensity, which is not always the folder it landed in. */
    val intensity: String? = null,
    /**
     * IKEY, rejoined with "; ".
     *
     * Stored as the delimited string the generator wrote rather than a converted list: SQL can
     * then LIKE across it, which is what makes an instrument searchable at all.
     */
    val instruments: String? = null,
    /** Playing time computed from the WAV header. */
    val durationMs: Long? = null,

    /** When the header was last read, so a file with no tags is not re-fetched for ever. */
    val metadataReadAt: Long? = null,
    /**
     * The checksum the header was read from.
     *
     * Compared against [md5Checksum] to decide whether a re-read is due: a regenerated take keeps
     * its Drive id but changes its content, and its prompt changes with it.
     */
    val metadataMd5: String? = null,

    // --- A/B judging ---
    /**
     * "good" or "bad" once this cue's takes have been judged; null while it is still a candidate.
     *
     * Kept as a plain String and mapped in Kotlin by `AbVerdict.from`. A Room TypeConverter would
     * have to be registered on the database and would then apply to every entity, for no gain.
     */
    val abVerdict: String? = null,
    val abJudgedAt: Long? = null,
    /** Drive id of the take that won, recorded on the winner and the also-rans alike. */
    val abWinnerDriveId: String? = null,
    /** INAM from the source WAV, captured at judging time so the batch stays readable later. */
    val abPrompt: String? = null,
) {
    val isLiked: Boolean get() = rating >= 1

    /**
     * [instruments] split back into the list the generator wrote.
     *
     * A computed property, so Room stores the delimited string and every reader sees the parts.
     */
    val instrumentList: List<String>
        get() = instruments?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
