package com.thunderplay.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An in-memory [TrackDao].
 *
 * Shared rather than re-declared per test: the interface is wide enough that a second hand-written
 * double would be mostly copies of this one, and the two would drift the moment a query is added.
 */
internal class FakeTrackDao(seed: List<TrackEntity>) : TrackDao {
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
        genre: String?,
        minStars: Int?,
        query: String,
    ) = flowOf(rows.values.toList())

    override fun observeAll(): Flow<List<TrackEntity>> = flowOf(rows.values.toList())
    override fun observeCategories(): Flow<List<String>> =
        flowOf(rows.values.map { it.category }.distinct())

    override fun observeLevels(): Flow<List<String>> =
        flowOf(rows.values.mapNotNull { it.level }.distinct())

    override fun observeGenres(): Flow<List<String>> =
        flowOf(rows.values.mapNotNull { it.genre }.distinct())

    override fun observeDescribed(): Flow<List<TrackEntity>> =
        flowOf(rows.values.filter { it.prompt != null || it.instruments != null })

    override suspend fun needingMetadata(limit: Int): List<TrackEntity> =
        rows.values.filter { pending(it) }.take(limit)

    override fun observeMetadataPending(): Flow<Int> = flowOf(rows.values.count(::pending))

    private fun pending(track: TrackEntity) =
        track.trashedAt == null &&
            (track.metadataReadAt == null || track.metadataMd5 != track.md5Checksum)

    override suspend fun applyMetadata(
        driveId: String,
        prompt: String?,
        genre: String?,
        intensity: String?,
        instruments: String?,
        durationMs: Long?,
        readAt: Long,
        md5Checksum: String?,
    ) {
        rows[driveId] = rows.getValue(driveId).copy(
            prompt = prompt,
            genre = genre,
            intensity = intensity,
            instruments = instruments,
            durationMs = durationMs,
            metadataReadAt = readAt,
            metadataMd5 = md5Checksum,
        )
    }

    override fun observeUnjudged(): Flow<List<TrackEntity>> =
        flowOf(rows.values.filter { it.abVerdict == null })

    override fun observeJudged(): Flow<List<TrackEntity>> =
        flowOf(rows.values.filter { it.abVerdict != null })

    override suspend fun applyAbVerdict(
        driveId: String,
        verdict: String,
        judgedAt: Long,
        winnerDriveId: String?,
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
