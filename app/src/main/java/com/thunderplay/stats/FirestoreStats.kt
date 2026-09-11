package com.thunderplay.stats

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.thunderplay.BuildConfig
import com.thunderplay.data.PlayDao
import com.thunderplay.data.PlayRollupEntity
import com.thunderplay.data.TrackDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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

    private val auth: FirebaseAuth? by lazy {
        if (!available) null else FirebaseAuth.getInstance()
    }

    private val _userEmail = MutableStateFlow<String?>(
        if (available) FirebaseAuth.getInstance().currentUser?.email else null
    )
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    val isAuthorized: Boolean
        get() = auth?.currentUser?.email?.equals(AUTHORIZED_EMAIL, ignoreCase = true) == true

    fun getGoogleSignInIntent(): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .setAccountName(AUTHORIZED_EMAIL)
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    suspend fun handleSignInResult(data: Intent?): Boolean = withContext(Dispatchers.IO) {
        val a = auth ?: return@withContext false
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).await()
            val email = account.email
            if (email == null || !email.equals(AUTHORIZED_EMAIL, ignoreCase = true)) {
                Log.w(TAG, "Sign-in rejected: $email is not authorized (expected $AUTHORIZED_EMAIL)")
                a.signOut()
                _userEmail.value = null
                false
            } else {
                val token = account.idToken ?: return@withContext false
                val cred = GoogleAuthProvider.getCredential(token, null)
                val result = a.signInWithCredential(cred).await()
                _userEmail.value = result.user?.email
                Log.i(TAG, "Signed in successfully with Google as ${result.user?.email}")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google Sign-In failed", e)
            if (a.currentUser == null) {
                runCatching { a.signInAnonymously().await() }
            }
            false
        }
    }

    /**
     * Signs in with the authorized Google account (almantusk@gmail.com).
     *
     * If already authenticated with that account, returns true immediately.
     * Otherwise, attempts silent sign-in so no UI prompt is needed if already authorized on the device.
     * Falls back to anonymous authentication if Google sign-in is not active, ensuring that
     * operations (ratings, play history, playlist sharing) are never broken.
     */
    suspend fun ensureSignedIn(): Boolean = withContext(Dispatchers.IO) {
        if (!available) return@withContext false
        val a = auth ?: return@withContext false
        val current = a.currentUser
        if (current != null && current.email.equals(AUTHORIZED_EMAIL, ignoreCase = true)) {
            _userEmail.value = current.email
            return@withContext true
        }

        val googleSignedIn = runCatching {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(WEB_CLIENT_ID)
                .requestEmail()
                .setAccountName(AUTHORIZED_EMAIL)
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            val account = client.silentSignIn().await()
            val email = account.email
            if (email != null && email.equals(AUTHORIZED_EMAIL, ignoreCase = true) && account.idToken != null) {
                val cred = GoogleAuthProvider.getCredential(account.idToken, null)
                val res = a.signInWithCredential(cred).await()
                _userEmail.value = res.user?.email
                true
            } else {
                false
            }
        }.getOrElse {
            Log.w(TAG, "Silent Google sign-in failed: ${it.message}")
            false
        }

        if (googleSignedIn) return@withContext true

        // Fallback: If we already have a valid signed-in user (e.g. anonymous), keep it.
        if (a.currentUser != null) {
            _userEmail.value = a.currentUser?.email
            return@withContext true
        }

        // Otherwise sign in anonymously so request.auth != null is satisfied.
        runCatching {
            a.signInAnonymously().await()
            _userEmail.value = null
            true
        }.getOrElse {
            Log.w(TAG, "Anonymous sign-in fallback failed", it)
            false
        }
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

    /**
     * Records one take's A/B verdict.
     *
     * The durable, queryable copy of a judgement. Drive file metadata carries the same facts on the
     * file itself, but only Firestore can answer "show me everything judged bad" without walking
     * the tree.
     */
    fun pushAbVerdict(
        driveId: String,
        verdict: String,
        winnerDriveId: String?,
        groupKey: String,
        prompt: String?,
        originalFolderPath: String,
    ) {
        val store = db ?: return
        store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.TRACKS).document(driveId)
            .set(
                mapOf(
                    "abVerdict" to verdict,
                    "abWinner" to winnerDriveId,
                    "abGroupKey" to groupKey,
                    "abPrompt" to prompt,
                    "abJudgedFrom" to originalFolderPath,
                    "abJudgedAt" to System.currentTimeMillis(),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .addOnFailureListener { Log.w(TAG, "A/B verdict push failed for $driveId", it) }
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

    companion object {
        const val AUTHORIZED_EMAIL = "almantusk@gmail.com"
        const val WEB_CLIENT_ID = "41460511094-897hudvk22h89the196gq2am5jtnn2gd.apps.googleusercontent.com"
        private const val TAG = "FirestoreStats"
        private const val PLAY_PAGE_SIZE = 500L
        private const val BATCH_LIMIT = 450
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
