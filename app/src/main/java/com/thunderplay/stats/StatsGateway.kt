package com.thunderplay.stats

import com.thunderplay.data.TrackDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ratings and play recording.
 *
 * Room is the read path so the library list has one query source; a Firestore mirror is layered
 * behind this so the same data survives a reinstall and follows you between devices.
 */
interface StatsGateway {
    suspend fun rating(driveId: String): Int

    suspend fun setRating(driveId: String, rating: Int)

    /**
     * Flips a track between rated and unrated.
     *
     * Un-hearting stashes the current rating and re-hearting restores it, so unliking a 5-star
     * track from the lock screen and changing your mind does not silently demote it.
     */
    suspend fun toggleLike(driveId: String): Int

    suspend fun recordPlay(driveId: String, startedAt: Long, msPlayed: Long, completed: Boolean)
}

/** Rating applied by the lock-screen heart to a track that has never been rated. */
const val DEFAULT_LIKE_RATING = 4
const val MAX_RATING = 5

/**
 * Decides whether a listen counts as a play.
 *
 * 30 seconds or half the track, whichever comes first, so skipping through the library does not
 * inflate the counts that "most played" sorts on.
 */
object PlayQualifier {
    const val MIN_MS = 30_000L

    fun qualifies(msPlayed: Long, durationMs: Long?): Boolean {
        if (msPlayed >= MIN_MS) return true
        // A track shorter than the threshold can still qualify on the halfway rule.
        val duration = durationMs ?: return false
        if (duration <= 0) return false
        return msPlayed * 2 >= duration
    }
}

/**
 * Local-only implementation.
 *
 * [FirestoreStatsGateway] decorates this once Firebase is configured; keeping the local writes
 * here means playback and the UI behave identically whether or not Firestore is reachable.
 */
@Singleton
class LocalStatsGateway @Inject constructor(
    private val trackDao: TrackDao,
) : StatsGateway {

    /** Overridable in tests. Not a constructor parameter: Dagger ignores Kotlin defaults. */
    internal var clock: () -> Long = System::currentTimeMillis

    override suspend fun rating(driveId: String): Int = trackDao.find(driveId)?.rating ?: 0

    override suspend fun setRating(driveId: String, rating: Int) {
        val clamped = rating.coerceIn(0, MAX_RATING)
        val existing = trackDao.find(driveId) ?: return
        // Remember the outgoing rating only when clearing, so a restore has something to restore.
        val remembered = if (clamped == 0 && existing.rating > 0) existing.rating else existing.lastRating
        trackDao.applyRating(driveId, clamped, clock(), remembered)
    }

    override suspend fun toggleLike(driveId: String): Int {
        val existing = trackDao.find(driveId) ?: return 0
        val next = if (existing.rating >= 1) {
            0
        } else {
            existing.lastRating.takeIf { it >= 1 } ?: DEFAULT_LIKE_RATING
        }
        setRating(driveId, next)
        return next
    }

    override suspend fun recordPlay(
        driveId: String,
        startedAt: Long,
        msPlayed: Long,
        completed: Boolean,
    ) {
        val existing = trackDao.find(driveId) ?: return
        trackDao.applyPlayStats(driveId, existing.playCount + 1, startedAt)
    }
}
