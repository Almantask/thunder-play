package com.thunderplay.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /**
     * Filtering happens here; ordering does not.
     *
     * The library is ~185 rows, so sorting in Kotlin costs nothing and keeps every order —
     * including the seeded shuffle, which SQL cannot express — in one testable place.
     *
     * The filters are ANDed, and a null means that one is not applied. [minStars] is the odd one:
     * zero asks for unrated tracks specifically, since "at least zero stars" would match the whole
     * library and so could never be worth picking.
     *
     * A/B losers drop out; winners stay. The keeper is still a library track - it has simply moved
     * to a folder the refresher also walks - whereas an also-ran has been set aside on purpose.
     *
     * The search covers the prompt and the instrument list as well as the title. A title is a
     * lowercased slug the generator truncates at 48 characters, so searching it alone cannot find
     * "distant anvil" in a cue whose name stops before the words that matter.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE trashedAt IS NULL
          AND (abVerdict IS NULL OR abVerdict = 'good')
          AND (:category IS NULL OR category = :category)
          AND (:level IS NULL OR level = :level)
          AND (:genre IS NULL OR genre = :genre)
          AND (
            :minStars IS NULL
            OR (:minStars = 0 AND rating = 0)
            OR (:minStars > 0 AND rating >= :minStars)
          )
          AND (
            :query = ''
            OR title LIKE '%' || :query || '%'
            OR prompt LIKE '%' || :query || '%'
            OR instruments LIKE '%' || :query || '%'
          )
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeTracks(
        category: String?,
        level: String?,
        genre: String?,
        minStars: Int?,
        query: String,
    ): Flow<List<TrackEntity>>

    /**
     * Every live track, judged or not.
     *
     * Deliberately not narrowed by verdict: History uses this as the driveId-to-title map, so
     * hiding judged tracks here would quietly turn "Most played" rows back into bare Drive ids.
     */
    @Query("SELECT * FROM tracks WHERE trashedAt IS NULL ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT DISTINCT category FROM tracks
        WHERE trashedAt IS NULL AND (abVerdict IS NULL OR abVerdict = 'good')
        ORDER BY category
        """,
    )
    fun observeCategories(): Flow<List<String>>

    /** The levels the library actually has, so the intensity dropdown offers only real folders. */
    @Query(
        """
        SELECT DISTINCT level FROM tracks
        WHERE trashedAt IS NULL AND level IS NOT NULL
          AND (abVerdict IS NULL OR abVerdict = 'good')
        ORDER BY level
        """,
    )
    fun observeLevels(): Flow<List<String>>

    /** The genres the generator actually wrote, so the dropdown offers only real ones. */
    @Query(
        """
        SELECT DISTINCT genre FROM tracks
        WHERE trashedAt IS NULL AND genre IS NOT NULL
          AND (abVerdict IS NULL OR abVerdict = 'good')
        ORDER BY genre
        """,
    )
    fun observeGenres(): Flow<List<String>>

    /**
     * Tracks whose header has never been read, or was read from different content.
     *
     * A file with no tags at all still gets metadataReadAt set, so it appears here once and then
     * stops - otherwise every refresh would re-fetch the same silent headers for ever.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE trashedAt IS NULL
          AND (metadataReadAt IS NULL OR metadataMd5 IS NOT md5Checksum)
        ORDER BY title COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun needingMetadata(limit: Int): List<TrackEntity>

    @Query(
        """
        SELECT COUNT(*) FROM tracks
        WHERE trashedAt IS NULL
          AND (metadataReadAt IS NULL OR metadataMd5 IS NOT md5Checksum)
        """,
    )
    fun observeMetadataPending(): Flow<Int>

    /**
     * Stores what the WAV header said.
     *
     * Every field is overwritten, including with null: a re-read after the file changed should
     * leave the row describing the new content, not a blend of both. The two bookkeeping columns
     * are always set, which is what stops a file with no tags being fetched again.
     */
    @Query(
        """
        UPDATE tracks SET
            prompt = :prompt,
            genre = :genre,
            intensity = :intensity,
            instruments = :instruments,
            durationMs = :durationMs,
            metadataReadAt = :readAt,
            metadataMd5 = :md5Checksum
        WHERE driveId = :driveId
        """,
    )
    suspend fun applyMetadata(
        driveId: String,
        prompt: String?,
        genre: String?,
        intensity: String?,
        instruments: String?,
        durationMs: Long?,
        readAt: Long,
        md5Checksum: String?,
    )

    /**
     * Everything the insights are computed over.
     *
     * A/B losers are deliberately included: their verdict is the most explicit signal there is
     * about a prompt. Trashed tracks are not - removing one is a decision about the file rather
     * than a judgement of the words that made it.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE trashedAt IS NULL AND (prompt IS NOT NULL OR instruments IS NOT NULL)
        """,
    )
    fun observeDescribed(): Flow<List<TrackEntity>>

    /** Candidates for A/B judging: still live, and not yet judged. */
    @Query(
        """
        SELECT * FROM tracks
        WHERE trashedAt IS NULL AND abVerdict IS NULL
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeUnjudged(): Flow<List<TrackEntity>>

    /** The two judged batches, newest verdict first. */
    @Query("SELECT * FROM tracks WHERE abVerdict IS NOT NULL ORDER BY abJudgedAt DESC")
    fun observeJudged(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE driveId = :driveId")
    suspend fun find(driveId: String): TrackEntity?

    /**
     * The rows [deleteMissing] is allowed to drop.
     *
     * Judged tracks are excluded, and that exclusion is load-bearing. Losers live in
     * `_ThunderPlayAB/bad`, which no walk visits, so leaving them in here would let the first
     * refresh after the first judgement delete every verdict and empty the Results tab.
     */
    @Query("SELECT * FROM tracks WHERE trashedAt IS NULL AND abVerdict IS NULL")
    suspend fun allActive(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(tracks: List<TrackEntity>): List<Long>

    /**
     * Refreshes only the fields Drive owns.
     *
     * Ratings and play counts deliberately are not listed here. A plain upsert replaces the whole
     * row, so refreshing would reset every rating and play count to zero - the values would come
     * back on the next Firestore pull, but they would visibly disappear in between, and on a
     * device with no Firebase they would be gone for good.
     *
     * trashedAt is cleared because the walk skips the trash folder: appearing in it means the
     * track is live again.
     *
     * The verdict is cleared on the same reasoning, but only when [clearAbVerdict] is set. That is
     * true for the main library walk, where reappearing means someone dragged the file back and
     * wants it judged again; it is false for the `_ThunderPlayAB/good` walk, where reappearing is
     * simply the winner sitting where the judgement put it. Clearing there would un-judge every
     * keeper on every refresh. abPrompt survives either way - it is costly to re-read and harmless
     * to keep.
     */
    @Query(
        """
        UPDATE tracks SET
            title = :title,
            relativePath = :relativePath,
            category = :category,
            level = :level,
            sizeBytes = :sizeBytes,
            md5Checksum = :md5Checksum,
            modifiedAt = :modifiedAt,
            trashedAt = NULL,
            abVerdict = CASE WHEN :clearAbVerdict THEN NULL ELSE abVerdict END,
            abJudgedAt = CASE WHEN :clearAbVerdict THEN NULL ELSE abJudgedAt END,
            abWinnerDriveId = CASE WHEN :clearAbVerdict THEN NULL ELSE abWinnerDriveId END
        WHERE driveId = :driveId
        """,
    )
    suspend fun updateCatalogFields(
        driveId: String,
        title: String,
        relativePath: String,
        category: String,
        level: String?,
        sizeBytes: Long?,
        md5Checksum: String?,
        modifiedAt: Long?,
        clearAbVerdict: Boolean,
    )

    /** Inserts new tracks and refreshes existing ones without touching their stats. */
    @Transaction
    suspend fun upsertAll(tracks: List<TrackEntity>, clearAbVerdict: Boolean = true) {
        if (tracks.isEmpty()) return
        val inserted = insertIfNew(tracks)
        tracks.forEachIndexed { index, track ->
            // -1 means the row already existed, so only the catalog fields need refreshing.
            if (inserted.getOrNull(index) == -1L) {
                updateCatalogFields(
                    driveId = track.driveId,
                    title = track.title,
                    relativePath = track.relativePath,
                    category = track.category,
                    level = track.level,
                    sizeBytes = track.sizeBytes,
                    md5Checksum = track.md5Checksum,
                    modifiedAt = track.modifiedAt,
                    clearAbVerdict = clearAbVerdict,
                )
            }
        }
    }

    /** Links a playable track to its source WAV once the second tree walk finds it. */
    @Query(
        """
        UPDATE tracks SET sourceWavDriveId = :sourceWavDriveId, addedAt = COALESCE(:addedAt, addedAt)
        WHERE driveId = :driveId
        """,
    )
    suspend fun applySource(driveId: String, sourceWavDriveId: String, addedAt: Long?)

    @Query("DELETE FROM tracks WHERE driveId IN (:driveIds)")
    suspend fun deleteByIds(driveIds: List<String>)

    /**
     * Drops rows Drive no longer has, and reports how many went.
     *
     * Only safe once a walk has finished: called mid-walk it would delete everything the walk had
     * not reached yet.
     */
    @Transaction
    suspend fun deleteMissing(keep: List<String>): Int {
        val stale = allActive().map { it.driveId }.filterNot { it in keep.toSet() }
        if (stale.isNotEmpty()) deleteByIds(stale)
        return stale.size
    }

    @Query("UPDATE tracks SET trashedAt = :at WHERE driveId = :driveId")
    suspend fun markTrashed(driveId: String, at: Long)

    /**
     * Records one take's side of a judgement.
     *
     * The prompt is COALESCEd so a re-judge with nothing read does not wipe a prompt captured
     * earlier - reading it costs a network round trip and it never goes stale.
     */
    @Query(
        """
        UPDATE tracks SET
            abVerdict = :verdict,
            abJudgedAt = :judgedAt,
            abWinnerDriveId = :winnerDriveId,
            abPrompt = COALESCE(:prompt, abPrompt)
        WHERE driveId = :driveId
        """,
    )
    suspend fun applyAbVerdict(
        driveId: String,
        verdict: String,
        judgedAt: Long,
        winnerDriveId: String,
        prompt: String?,
    )

    /** Puts a judged take back in the running, for an undo or a manual restore. */
    @Query(
        """
        UPDATE tracks SET abVerdict = NULL, abJudgedAt = NULL, abWinnerDriveId = NULL
        WHERE driveId = :driveId
        """,
    )
    suspend fun clearAbVerdict(driveId: String)

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
