package com.thunderplay.sync

import android.util.Log
import com.thunderplay.data.TrackDao
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.DriveRepository
import com.thunderplay.library.WavInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

data class IndexResult(
    /** Headers successfully fetched, whether or not they turned out to say anything. */
    val read: Int,
    /** Of those, the ones that carried a prompt or an instrument list. */
    val described: Int,
    /** Reads that failed outright; these stay pending and are retried next time. */
    val failed: Int,
) {
    val attempted: Int get() = read + failed
}

sealed interface IndexProgress {
    data class Reading(val done: Int, val total: Int) : IndexProgress
    data class Complete(val result: IndexResult) : IndexProgress
}

/**
 * Fills in what the catalog walk cannot see: the prompt, genre, intensity, instrument list and
 * playing time the generator wrote into each source WAV.
 *
 * Drive's file listing carries none of it, and the audio itself is 40 MB a track, so this reads
 * the first [WavInfo.HEAD_BYTES] of each file with a range request instead - about four kilobytes
 * for a sentence that exists nowhere else. Once read, a track is never read again unless its
 * checksum changes, so this is a one-time cost per file rather than a per-refresh one.
 *
 * Failures are deliberately not recorded as reads. A dropped connection leaves the track pending
 * and the next run picks it up; only a header that was actually fetched marks the track done, even
 * when it turned out to contain nothing.
 */
@Singleton
class MetadataIndexer @Inject constructor(
    private val drive: DriveRepository,
    private val trackDao: TrackDao,
) {

    /** Convenience for callers with nothing to report to, such as the periodic worker. */
    suspend fun index(limit: Int = DEFAULT_LIMIT): IndexResult {
        var last = IndexResult(0, 0, 0)
        indexProgressively(limit).collect { if (it is IndexProgress.Complete) last = it.result }
        return last
    }

    fun indexProgressively(limit: Int = DEFAULT_LIMIT): Flow<IndexProgress> = flow {
        val pending = trackDao.needingMetadata(limit)
        if (pending.isEmpty()) {
            emit(IndexProgress.Complete(IndexResult(0, 0, 0)))
            return@flow
        }

        var read = 0
        var described = 0
        var failed = 0
        var done = 0

        emit(IndexProgress.Reading(0, pending.size))

        // A few at a time: each read is a whole round trip for four kilobytes, so doing them one
        // after another is dominated by latency, while opening one per track would be a burst of
        // ~185 simultaneous requests at a quota that has better uses.
        for (batch in pending.chunked(PARALLELISM)) {
            val outcomes = coroutineScope {
                batch.map { track -> async { readOne(track) } }.awaitAll()
            }
            outcomes.forEach { outcome ->
                when (outcome) {
                    Outcome.Failed -> failed++
                    Outcome.Silent -> read++
                    Outcome.Described -> {
                        read++
                        described++
                    }
                }
            }
            done += batch.size
            emit(IndexProgress.Reading(done, pending.size))
        }

        val result = IndexResult(read = read, described = described, failed = failed)
        Log.i(TAG, "Indexed metadata: $result")
        emit(IndexProgress.Complete(result))
    }

    private enum class Outcome { Described, Silent, Failed }

    private suspend fun readOne(track: TrackEntity): Outcome {
        // The tags live in the WAV. On the transcoded layout the playable .m4a carries nothing at
        // all, so a track with no known source has nothing worth reading.
        val sourceId = track.sourceWavDriveId ?: track.driveId
        val head = drive.readHead(sourceId, WavInfo.HEAD_BYTES) ?: return Outcome.Failed

        val info = WavInfo.parse(head)
        trackDao.applyMetadata(
            driveId = track.driveId,
            prompt = info?.prompt,
            genre = info?.genre,
            intensity = info?.intensity,
            instruments = info?.instruments
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(INSTRUMENT_SEPARATOR),
            durationMs = info?.durationMs,
            readAt = System.currentTimeMillis(),
            md5Checksum = track.md5Checksum,
        )
        return if (info?.hasAny == true) Outcome.Described else Outcome.Silent
    }

    companion object {
        /** How the instrument list is stored, and how it reads back out. */
        const val INSTRUMENT_SEPARATOR = "; "

        /**
         * Enough to finish a fresh library in one pass, since the whole catalog is a couple of
         * hundred tracks. The cap only matters as a guard against a runaway walk.
         */
        const val DEFAULT_LIMIT = 2_000

        private const val PARALLELISM = 6
        private const val TAG = "MetadataIndexer"
    }
}
