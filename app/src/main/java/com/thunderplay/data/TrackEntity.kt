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
) {
    val isLiked: Boolean get() = rating >= 1
}
