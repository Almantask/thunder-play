package com.thunderplay.stats

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.PlayRollupEntity
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayQualifierTest {

    @Test
    fun `under thirty seconds of a long track does not count`() {
        assertThat(PlayQualifier.qualifies(msPlayed = 29_000, durationMs = 300_000)).isFalse()
    }

    @Test
    fun `thirty seconds counts however long the track is`() {
        assertThat(PlayQualifier.qualifies(msPlayed = 30_000, durationMs = 300_000)).isTrue()
        assertThat(PlayQualifier.qualifies(msPlayed = 31_000, durationMs = 9_000_000)).isTrue()
    }

    @Test
    fun `half of a short track counts even below thirty seconds`() {
        // A 20s sting can never reach 30s, so the halfway rule is what makes it countable.
        assertThat(PlayQualifier.qualifies(msPlayed = 10_000, durationMs = 20_000)).isTrue()
        assertThat(PlayQualifier.qualifies(msPlayed = 9_000, durationMs = 20_000)).isFalse()
    }

    @Test
    fun `an unknown duration falls back to the thirty second rule alone`() {
        assertThat(PlayQualifier.qualifies(msPlayed = 5_000, durationMs = null)).isFalse()
        assertThat(PlayQualifier.qualifies(msPlayed = 45_000, durationMs = null)).isTrue()
    }

    @Test
    fun `a zero duration cannot satisfy the halfway rule`() {
        assertThat(PlayQualifier.qualifies(msPlayed = 1, durationMs = 0)).isFalse()
    }
}

class LocalStatsGatewayTest {

    private class FakeTrackDao(seed: List<TrackEntity>) : TrackDao {
        val rows = seed.associateBy { it.driveId }.toMutableMap()

        override suspend fun find(driveId: String) = rows[driveId]

        override suspend fun applyRating(
            driveId: String,
            rating: Int,
            ratedAt: Long,
            lastRating: Int,
        ) {
            rows[driveId] = rows.getValue(driveId)
                .copy(rating = rating, ratedAt = ratedAt, lastRating = lastRating)
        }

        override suspend fun applyPlayStats(driveId: String, playCount: Int, lastPlayedAt: Long) {
            rows[driveId] = rows.getValue(driveId)
                .copy(playCount = playCount, lastPlayedAt = lastPlayedAt)
        }

        override fun observeTracks(
            category: String?,
            level: String?,
            minStars: Int?,
            query: String,
        ) = flowOf(rows.values.toList())

        override fun observeAll(): Flow<List<TrackEntity>> = flowOf(rows.values.toList())
        override fun observeCategories(): Flow<List<String>> =
            flowOf(rows.values.map { it.category }.distinct())

        override fun observeLevels(): Flow<List<String>> =
            flowOf(rows.values.mapNotNull { it.level }.distinct())

        override fun observeUnjudged(): Flow<List<TrackEntity>> =
            flowOf(rows.values.filter { it.abVerdict == null })

        override fun observeJudged(): Flow<List<TrackEntity>> =
            flowOf(rows.values.filter { it.abVerdict != null })

        override suspend fun applyAbVerdict(
            driveId: String,
            verdict: String,
            judgedAt: Long,
            winnerDriveId: String,
            prompt: String?,
        ) {
            rows[driveId] = rows.getValue(driveId).copy(
                abVerdict = verdict,
                abJudgedAt = judgedAt,
                abWinnerDriveId = winnerDriveId,
                abPrompt = prompt ?: rows.getValue(driveId).abPrompt,
            )
        }

        override suspend fun clearAbVerdict(driveId: String) {
            rows[driveId] = rows.getValue(driveId)
                .copy(abVerdict = null, abJudgedAt = null, abWinnerDriveId = null)
        }

        override suspend fun allActive() = rows.values.filter { it.abVerdict == null }
        override suspend fun insertIfNew(tracks: List<TrackEntity>): List<Long> =
            tracks.map { if (rows.putIfAbsent(it.driveId, it) == null) 1L else -1L }

        override suspend fun updateCatalogFields(
            driveId: String,
            title: String,
            relativePath: String,
            category: String,
            level: String?,
            sizeBytes: Long?,
            md5Checksum: String?,
            modifiedAt: Long?,
            clearAbVerdict: Boolean,
        ) {
            val existing = rows.getValue(driveId)
            rows[driveId] = existing.copy(
                title = title,
                relativePath = relativePath,
                category = category,
                level = level,
                sizeBytes = sizeBytes,
                md5Checksum = md5Checksum,
                modifiedAt = modifiedAt,
                trashedAt = null,
                abVerdict = if (clearAbVerdict) null else existing.abVerdict,
                abJudgedAt = if (clearAbVerdict) null else existing.abJudgedAt,
                abWinnerDriveId = if (clearAbVerdict) null else existing.abWinnerDriveId,
            )
        }

        override suspend fun applySource(
            driveId: String,
            sourceWavDriveId: String,
            addedAt: Long?,
        ) {
            rows[driveId] = rows.getValue(driveId).copy(
                sourceWavDriveId = sourceWavDriveId,
                addedAt = addedAt ?: rows.getValue(driveId).addedAt,
            )
        }

        override suspend fun deleteByIds(driveIds: List<String>) {
            driveIds.forEach { rows.remove(it) }
        }

        override suspend fun markTrashed(driveId: String, at: Long) {
            rows[driveId] = rows.getValue(driveId).copy(trashedAt = at)
        }
    }

    private fun track(rating: Int = 0, lastRating: Int = 0) = TrackEntity(
        driveId = "t1",
        title = "track",
        relativePath = "Boss/I/track.m4a",
        category = "Boss",
        level = "I",
        sizeBytes = null,
        md5Checksum = null,
        addedAt = null,
        modifiedAt = null,
        sourceWavDriveId = null,
        rating = rating,
        lastRating = lastRating,
    )

    private fun gateway(dao: TrackDao) = LocalStatsGateway(dao).apply { clock = { 1_000L } }

    @Test
    fun `hearting an unrated track applies the default rating`() = runTest {
        val dao = FakeTrackDao(listOf(track()))
        val next = gateway(dao).toggleLike("t1")

        assertThat(next).isEqualTo(DEFAULT_LIKE_RATING)
        assertThat(dao.rows.getValue("t1").isLiked).isTrue()
    }

    @Test
    fun `unhearting a five star track stashes the rating rather than losing it`() = runTest {
        val dao = FakeTrackDao(listOf(track(rating = 5)))
        val gateway = gateway(dao)

        assertThat(gateway.toggleLike("t1")).isEqualTo(0)
        assertThat(dao.rows.getValue("t1").lastRating).isEqualTo(5)

        // Re-hearting must restore 5, not demote the track to the default.
        assertThat(gateway.toggleLike("t1")).isEqualTo(5)
        assertThat(dao.rows.getValue("t1").rating).isEqualTo(5)
    }

    @Test
    fun `ratings are clamped to the supported range`() = runTest {
        val dao = FakeTrackDao(listOf(track()))
        val gateway = gateway(dao)

        gateway.setRating("t1", 99)
        assertThat(dao.rows.getValue("t1").rating).isEqualTo(MAX_RATING)

        gateway.setRating("t1", -3)
        assertThat(dao.rows.getValue("t1").rating).isEqualTo(0)
    }

    @Test
    fun `setting a new rating does not overwrite the stash`() = runTest {
        val dao = FakeTrackDao(listOf(track(rating = 0, lastRating = 5)))
        val gateway = gateway(dao)

        gateway.setRating("t1", 2)
        // Only clearing a rating updates the stash, so an explicit 2 leaves the remembered 5 alone.
        assertThat(dao.rows.getValue("t1").lastRating).isEqualTo(5)
    }

    @Test
    fun `an unknown track is ignored rather than crashing playback`() = runTest {
        val dao = FakeTrackDao(emptyList())
        assertThat(gateway(dao).toggleLike("missing")).isEqualTo(0)
    }

    @Test
    fun `recording a play increments the counter`() = runTest {
        val dao = FakeTrackDao(listOf(track()))
        val gateway = gateway(dao)

        gateway.recordPlay("t1", startedAt = 500, msPlayed = 40_000, completed = true)
        gateway.recordPlay("t1", startedAt = 900, msPlayed = 40_000, completed = true)

        assertThat(dao.rows.getValue("t1").playCount).isEqualTo(2)
        assertThat(dao.rows.getValue("t1").lastPlayedAt).isEqualTo(900)
    }

    @Suppress("unused")
    private fun unusedRollup() = PlayRollupEntity("id", "t1", 0, 0, false)

    @Test
    fun `refreshing the catalog does not wipe ratings or play counts`() = runTest {
        // A plain upsert replaces the whole row, so a refresh used to reset every rating and
        // play count to zero. Drive owns the catalog fields; the app owns the stats.
        val dao = FakeTrackDao(listOf(track(rating = 5).copy(playCount = 12, lastPlayedAt = 99)))
        val fromDrive = track().copy(title = "renamed-on-drive", sizeBytes = 4242)

        dao.upsertAll(listOf(fromDrive))

        val row = dao.rows.getValue("t1")
        assertThat(row.title).isEqualTo("renamed-on-drive")
        assertThat(row.sizeBytes).isEqualTo(4242)
        assertThat(row.rating).isEqualTo(5)
        assertThat(row.playCount).isEqualTo(12)
        assertThat(row.lastPlayedAt).isEqualTo(99)
    }

    @Test
    fun `a track reappearing in Drive is no longer marked trashed`() = runTest {
        val dao = FakeTrackDao(listOf(track().copy(trashedAt = 1_000)))
        dao.upsertAll(listOf(track()))
        assertThat(dao.rows.getValue("t1").trashedAt).isNull()
    }

    @Test
    fun `deleteMissing removes only what Drive no longer has, and counts it`() = runTest {
        val dao = FakeTrackDao(
            listOf(track(), track().copy(driveId = "t2"), track().copy(driveId = "t3")),
        )
        val removed = dao.deleteMissing(listOf("t1", "t3"))

        assertThat(removed).isEqualTo(1)
        assertThat(dao.rows.keys).containsExactly("t1", "t3")
    }
}
