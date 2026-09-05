package com.thunderplay.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A user-made playlist, mirrored from Firestore so the list works offline.
 *
 * Track order is meaningful, so this stores an ordered id list rather than a join table.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trackIds: List<String>,
    /** Set while a public share link exists for this playlist. */
    val shareId: String? = null,
    /** Every share expires; null only when there is no share. */
    val shareExpiresAt: Long? = null,
) {
    val size: Int get() = trackIds.size

    fun isShareLive(now: Long): Boolean =
        shareId != null && (shareExpiresAt ?: 0L) > now
}

/**
 * Drive file ids are URL-safe base64-ish and never contain a newline, so a delimited string is a
 * safe and greppable representation - no JSON dependency in the database layer.
 */
class TrackIdListConverter {
    @TypeConverter
    fun fromList(ids: List<String>): String = ids.joinToString(SEPARATOR)

    @TypeConverter
    fun toList(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split(SEPARATOR)

    private companion object {
        const val SEPARATOR = "\n"
    }
}

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun find(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE shareId IS NOT NULL")
    suspend fun allShared(): List<PlaylistEntity>

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity)

    @Upsert
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM playlists WHERE id NOT IN (:keep)")
    suspend fun deleteMissing(keep: List<String>)
}
