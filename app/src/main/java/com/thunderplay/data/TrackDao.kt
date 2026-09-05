package com.thunderplay.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /**
     * Filtering happens here; ordering does not.
     *
     * The library is ~185 rows, so sorting in Kotlin costs nothing and keeps every order —
     * including the seeded shuffle, which SQL cannot express — in one testable place.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE trashedAt IS NULL
          AND (:category IS NULL OR category = :category)
          AND (:likedOnly = 0 OR rating >= 1)
          AND (:query = '' OR title LIKE '%' || :query || '%')
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeTracks(
        category: String?,
        likedOnly: Boolean,
        query: String,
    ): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE trashedAt IS NULL ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT category FROM tracks WHERE trashedAt IS NULL ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE driveId = :driveId")
    suspend fun find(driveId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE trashedAt IS NULL")
    suspend fun allActive(): List<TrackEntity>

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE driveId IN (:driveIds)")
    suspend fun deleteByIds(driveIds: List<String>)

    @Query("UPDATE tracks SET trashedAt = :at WHERE driveId = :driveId")
    suspend fun markTrashed(driveId: String, at: Long)

    @Query(
        """
        UPDATE tracks SET rating = :rating, ratedAt = :ratedAt, lastRating = :lastRating
        WHERE driveId = :driveId
        """,
    )
    suspend fun applyRating(driveId: String, rating: Int, ratedAt: Long, lastRating: Int)

    @Query(
        """
        UPDATE tracks SET playCount = :playCount, lastPlayedAt = :lastPlayedAt
        WHERE driveId = :driveId
        """,
    )
    suspend fun applyPlayStats(driveId: String, playCount: Int, lastPlayedAt: Long)

    /**
     * Replaces the catalog with what Drive currently holds.
     *
     * Rows absent from [current] are deleted rather than kept, so a track removed outside the app
     * disappears on the next refresh. Stats are preserved because [upsertAll] runs first and
     * Firestore is the authority for them anyway.
     */
    @Transaction
    suspend fun replaceCatalog(current: List<TrackEntity>) {
        upsertAll(current)
        val keep = current.map { it.driveId }.toSet()
        val stale = allActive().map { it.driveId }.filterNot { it in keep }
        if (stale.isNotEmpty()) deleteByIds(stale)
    }
}

@Dao
interface PlayDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(plays: List<PlayRollupEntity>)

    @Query("SELECT MAX(startedAt) FROM plays")
    suspend fun latestStartedAt(): Long?

    @Query("SELECT * FROM plays ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlayRollupEntity>>

    @Query(
        """
        SELECT trackId, COUNT(*) AS plays, SUM(msPlayed) AS totalMs
        FROM plays WHERE startedAt >= :since
        GROUP BY trackId ORDER BY plays DESC LIMIT :limit
        """,
    )
    fun observeTopSince(since: Long, limit: Int): Flow<List<TrackPlayTotals>>

    @Query("SELECT COALESCE(SUM(msPlayed), 0) FROM plays WHERE startedAt >= :since")
    fun observeListeningMsSince(since: Long): Flow<Long>
}

data class TrackPlayTotals(
    val trackId: String,
    val plays: Int,
    val totalMs: Long,
)
