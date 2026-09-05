package com.thunderplay.stats

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.thunderplay.BuildConfig
import com.thunderplay.data.PlayDao
import com.thunderplay.data.PlayRollupEntity
import com.thunderplay.data.TrackDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Firestore paths. Data sits at a fixed path, not under a uid, so all devices share a library. */
object FirestorePaths {
    const val LIBRARY = "libraries/default"
    const val TRACKS = "tracks"
    const val PLAYS = "plays"
    const val PLAYLISTS = "playlists"
    const val SHARES = "shares"
}

/**
 * Remote mirror of ratings and play history.
 *
 * Every write here is deliberately fire-and-forget. On Android a Firestore write task does not
 * complete until the server acknowledges it, so awaiting one hangs forever in airplane mode -
 * exactly when someone is most likely to be tapping the lock-screen heart. The SDK's offline
 * queue persists the write and replays it on reconnect, and the local Room copy is what the UI
 * reads, so nothing is lost by not waiting.
 */
@Singleton
class FirestoreStats @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val available: Boolean by lazy {
        BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()
    }

    private val db: FirebaseFirestore? by lazy {
        if (!available) null else FirebaseFirestore.getInstance()
    }

    val isAvailable: Boolean get() = available

    /** Signs in anonymously. There is no login UI; this just gets past the security rules. */
    suspend fun ensureSignedIn(): Boolean {
        if (!available) return false
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) return true
        return runCatching { auth.signInAnonymously().await() }
            .onFailure { Log.w(TAG, "Anonymous sign-in failed; staying local-only", it) }
            .isSuccess
    }

    fun pushRating(driveId: String, rating: Int, ratedAt: Long, lastRating: Int) {
        val store = db ?: return
        store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.TRACKS).document(driveId)
            .set(
                mapOf(
                    "rating" to rating,
                    "ratedAt" to ratedAt,
                    "lastRating" to lastRating,
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .addOnFailureListener { Log.w(TAG, "Rating push failed for $driveId", it) }
    }

    /**
     * Appends the play and bumps the counter in one batch.
     *
     * The history log is the record; `playCount` exists only so the library list can sort without
     * aggregating. [FieldValue.increment] keeps the counter correct even if two devices play at
     * the same time.
     */
    fun recordPlay(driveId: String, startedAt: Long, msPlayed: Long, completed: Boolean) {
        val store = db ?: return
        val library = store.document(FirestorePaths.LIBRARY)
        val batch = store.batch()

        batch.set(
            library.collection(FirestorePaths.PLAYS).document(),
            mapOf(
                "trackId" to driveId,
                "startedAt" to startedAt,
                "msPlayed" to msPlayed,
                "completed" to completed,
            ),
        )
        batch.set(
            library.collection(FirestorePaths.TRACKS).document(driveId),
            mapOf(
                "playCount" to FieldValue.increment(1),
                "lastPlayedAt" to startedAt,
            ),
            com.google.firebase.firestore.SetOptions.merge(),
        )
        batch.commit().addOnFailureListener { Log.w(TAG, "Play push failed for $driveId", it) }
    }

    /** Records where a trashed track used to live, so a future restore needs no guesswork. */
    fun pushTrashed(driveId: String, originalFolderPath: String) {
        val store = db ?: return
        store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.TRACKS).document(driveId)
            .set(
                mapOf(
                    "trashedFrom" to originalFolderPath,
                    "trashedAt" to System.currentTimeMillis(),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .addOnFailureListener { Log.w(TAG, "Trash marker push failed for $driveId", it) }
    }

    /** Pulls remote rating/count values into Room so the library list reflects other devices. */
    suspend fun pullTrackStats(trackDao: TrackDao) {
        val store = db ?: return
        val snapshot = runCatching {
            store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.TRACKS).get().await()
        }.getOrElse {
            Log.w(TAG, "Track stat pull failed", it)
            return
        }

        for (doc in snapshot.documents) {
            val existing = trackDao.find(doc.id) ?: continue
            val rating = (doc.getLong("rating") ?: 0L).toInt()
            val lastRating = (doc.getLong("lastRating") ?: 0L).toInt()
            val ratedAt = doc.getLong("ratedAt")
            val playCount = (doc.getLong("playCount") ?: 0L).toInt()
            val lastPlayedAt = doc.getLong("lastPlayedAt")

            // Remote wins only where it is newer or higher; a local write that has not yet synced
            // must not be clobbered by the value it is about to replace.
            if ((ratedAt ?: 0) >= (existing.ratedAt ?: 0)) {
                trackDao.applyRating(doc.id, rating, ratedAt ?: existing.ratedAt ?: 0, lastRating)
            }
            if (playCount > existing.playCount) {
                trackDao.applyPlayStats(
                    doc.id,
                    playCount,
                    lastPlayedAt ?: existing.lastPlayedAt ?: 0,
                )
            }
        }
    }

    /**
     * Pulls play documents newer than what Room already holds.
     *
     * A cursor rather than a full read: the first sync reads the collection once and every sync
     * after that is a delta, so the history stays cheap as it grows.
     */
    suspend fun pullPlayHistory(playDao: PlayDao) {
        val store = db ?: return
        val since = playDao.latestStartedAt() ?: 0L
        val snapshot = runCatching {
            store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.PLAYS)
                .whereGreaterThan("startedAt", since)
                .orderBy("startedAt", Query.Direction.ASCENDING)
                .limit(PLAY_PAGE_SIZE)
                .get()
                .await()
        }.getOrElse {
            Log.w(TAG, "Play history pull failed", it)
            return
        }

        val rows = snapshot.documents.mapNotNull { doc ->
            val trackId = doc.getString("trackId") ?: return@mapNotNull null
            PlayRollupEntity(
                id = doc.id,
                trackId = trackId,
                startedAt = doc.getLong("startedAt") ?: return@mapNotNull null,
                msPlayed = doc.getLong("msPlayed") ?: 0L,
                completed = doc.getBoolean("completed") ?: false,
            )
        }
        if (rows.isNotEmpty()) playDao.insertAll(rows)
    }

    /** Returns all document IDs currently present in the remote tracks collection. */
    suspend fun getAllTrackIds(): List<String> {
        val store = db ?: return emptyList()
        val snapshot = runCatching {
            store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.TRACKS).get().await()
        }.getOrElse {
            Log.w(TAG, "Failed to get all track IDs from Firestore", it)
            return emptyList()
        }
        return snapshot.documents.map { it.id }
    }

    /** Deletes track stat documents from Firestore for the given track IDs. */
    suspend fun deleteTrackStats(driveIds: Set<String>) {
        val store = db ?: return
        if (driveIds.isEmpty()) return
        val library = store.document(FirestorePaths.LIBRARY)
        for (chunk in driveIds.chunked(BATCH_LIMIT)) {
            val batch = store.batch()
            for (id in chunk) {
                batch.delete(library.collection(FirestorePaths.TRACKS).document(id))
            }
            runCatching { batch.commit().await() }
                .onFailure { Log.w(TAG, "Failed to delete track batch from Firestore", it) }
        }
    }

    /** Deletes play documents in Firestore that reference any of the given track IDs. */
    suspend fun deletePlaysForTracks(trackIds: Set<String>): Int {
        val store = db ?: return 0
        if (trackIds.isEmpty()) return 0
        val library = store.document(FirestorePaths.LIBRARY)
        var deletedCount = 0
        // Firestore whereIn supports up to 30 values per query
        for (chunk in trackIds.chunked(30)) {
            val snapshot = runCatching {
                library.collection(FirestorePaths.PLAYS)
                    .whereIn("trackId", chunk)
                    .get()
                    .await()
            }.getOrElse {
                Log.w(TAG, "Failed to query plays for orphan tracks", it)
                continue
            }
            if (snapshot.isEmpty) continue
            for (batchDocs in snapshot.documents.chunked(BATCH_LIMIT)) {
                val batch = store.batch()
                for (doc in batchDocs) {
                    batch.delete(doc.reference)
                }
                runCatching { batch.commit().await() }
                    .onSuccess { deletedCount += batchDocs.size }
                    .onFailure { Log.w(TAG, "Failed to commit play deletion batch", it) }
            }
        }
        return deletedCount
    }

    private companion object {
        const val TAG = "FirestoreStats"
        const val PLAY_PAGE_SIZE = 500L
        const val BATCH_LIMIT = 450
    }
}

/**
 * Writes locally first, then mirrors remotely.
 *
 * Room is the read path, so the UI updates instantly and behaves identically whether or not
 * Firebase is configured or reachable.
 */
@Singleton
class SyncingStatsGateway @Inject constructor(
    private val local: LocalStatsGateway,
    private val remote: FirestoreStats,
    private val trackDao: TrackDao,
) : StatsGateway {

    override suspend fun rating(driveId: String): Int = local.rating(driveId)

    override suspend fun setRating(driveId: String, rating: Int) {
        local.setRating(driveId, rating)
        trackDao.find(driveId)?.let {
            remote.pushRating(driveId, it.rating, it.ratedAt ?: 0L, it.lastRating)
        }
    }

    override suspend fun toggleLike(driveId: String): Int {
        val next = local.toggleLike(driveId)
        trackDao.find(driveId)?.let {
            remote.pushRating(driveId, it.rating, it.ratedAt ?: 0L, it.lastRating)
        }
        return next
    }

    override suspend fun recordPlay(
        driveId: String,
        startedAt: Long,
        msPlayed: Long,
        completed: Boolean,
    ) {
        local.recordPlay(driveId, startedAt, msPlayed, completed)
        remote.recordPlay(driveId, startedAt, msPlayed, completed)
    }
}
