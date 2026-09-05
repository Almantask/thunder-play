package com.thunderplay.playlist

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.DriveApi
import com.thunderplay.stats.FirestorePaths
import com.thunderplay.stats.FirestoreStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** How long a share stays live. Every share expires; there is no permanent option by design. */
val DEFAULT_SHARE_TTL_MS: Long = TimeUnit.DAYS.toMillis(7)

data class ShareLink(
    val shareId: String,
    val url: String,
    val expiresAt: Long,
    val trackCount: Int,
)

class ShareUnavailable(message: String) : IllegalStateException(message)

/**
 * Publishes a set of tracks as a link anyone can open in a phone browser.
 *
 * The audio is copied to Firebase Storage rather than linked from Drive: Google blocks Drive files
 * from being embedded on third-party sites, so a Drive URL in an `<audio>` tag simply 403s. Only
 * tracks in a live share are copied, and expiry deletes them again.
 */
@Singleton
class ShareService @Inject constructor(
    private val driveApi: DriveApi,
    private val stats: FirestoreStats,
    private val playlists: PlaylistRepository,
) {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()

    /**
     * Uploads [tracks] and writes the public share document.
     *
     * @param ttlMs how long the link stays live; clamped so a share can never outlive [MAX_TTL_MS].
     */
    suspend fun createShare(
        name: String,
        tracks: List<TrackEntity>,
        ttlMs: Long = DEFAULT_SHARE_TTL_MS,
    ): ShareLink = withContext(Dispatchers.IO) {
        if (!stats.isAvailable) {
            throw ShareUnavailable("Firebase is not configured; see docs/SETUP.md.")
        }
        if (tracks.isEmpty()) throw ShareUnavailable("Nothing to share.")

        // The Storage rules require an authenticated caller. Proceeding without checking turns a
        // setup problem into an opaque "permission denied" halfway through the upload.
        if (!stats.ensureSignedIn()) {
            throw ShareUnavailable(
                "Could not sign in to Firebase. Enable Anonymous authentication in the Firebase " +
                    "console (Build > Authentication > Sign-in method), then try again.",
            )
        }

        val shareId = newShareId()
        val expiresAt = System.currentTimeMillis() + ttlMs.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        val bucket = storage.reference.bucket

        val entries = tracks.map { track ->
            val path = "shares/$shareId/${track.driveId}.m4a"
            uploadTrack(track, path)
            mapOf(
                "id" to track.driveId,
                "title" to track.title,
                "category" to track.category,
                "level" to track.level,
                "url" to publicUrl(bucket, path),
            )
        }

        db.collection(FirestorePaths.SHARES).document(shareId).set(
            mapOf(
                "name" to name,
                "createdAt" to System.currentTimeMillis(),
                "expiresAt" to expiresAt,
                "revoked" to false,
                "tracks" to entries,
            ),
        ).await()

        ShareLink(shareId, shareUrl(shareId), expiresAt, tracks.size)
    }

    /** Publishes a playlist and remembers the link against it. */
    suspend fun sharePlaylist(
        playlistId: String,
        tracks: List<TrackEntity>,
        ttlMs: Long = DEFAULT_SHARE_TTL_MS,
    ): ShareLink {
        val playlist = playlists.find(playlistId)
            ?: throw ShareUnavailable("That playlist no longer exists.")
        // Replace rather than accumulate, so an old link cannot outlive the one just handed out.
        playlist.shareId?.let { revoke(it) }

        val link = createShare(playlist.name, tracks, ttlMs)
        playlists.setShare(playlistId, link.shareId, link.expiresAt)
        return link
    }

    /** Deletes the uploaded audio and the share document. This is the real off switch. */
    suspend fun revoke(shareId: String) = withContext(Dispatchers.IO) {
        if (!stats.isAvailable) return@withContext
        runCatching {
            val folder = storage.reference.child("shares/$shareId")
            folder.listAll().await().items.forEach { it.delete().await() }
        }.onFailure { Log.w(TAG, "Could not clear share audio for $shareId", it) }

        runCatching { db.collection(FirestorePaths.SHARES).document(shareId).delete().await() }
            .onFailure { Log.w(TAG, "Could not delete share doc $shareId", it) }
    }

    /**
     * Deletes every share whose expiry has passed.
     *
     * Expiry is enforced in three places - the rules refuse reads, the web player refuses to
     * render, and this actually removes the bytes - because only the last one stops paying for
     * storage and stops the audio being reachable if a rule is ever loosened.
     */
    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) {
        if (!stats.isAvailable) return@withContext 0
        stats.ensureSignedIn()

        val now = System.currentTimeMillis()
        val expired = runCatching {
            db.collection(FirestorePaths.SHARES)
                .whereLessThan("expiresAt", now)
                .get()
                .await()
        }.getOrElse {
            Log.w(TAG, "Could not list expired shares", it)
            return@withContext 0
        }

        expired.documents.forEach { revoke(it.id) }

        // Drop the now-dead link from any playlist still pointing at it.
        val stale = expired.documents.map { it.id }.toSet()
        for (playlistId in playlistsWithShareIn(stale)) {
            playlists.setShare(playlistId, null, null)
        }
        playlists.pull()
        expired.size()
    }

    private suspend fun playlistsWithShareIn(shareIds: Set<String>): List<String> =
        runCatching {
            db.document(FirestorePaths.LIBRARY).collection(FirestorePaths.PLAYLISTS).get().await()
                .documents
                .filter { it.getString("shareId") in shareIds }
                .map { it.id }
        }.getOrDefault(emptyList())

    private suspend fun uploadTrack(track: TrackEntity, path: String) {
        // No existence probe first. Each share gets a fresh id, so the object never exists yet:
        // the probe was a guaranteed-to-fail round trip per track whose "Object does not exist at
        // location" error surfaced to the user as the reason the whole share failed. putStream
        // overwrites anyway, so asking first bought nothing.
        val ref = storage.reference.child(path)
        try {
            val body = driveApi.download(track.driveId)
            body.byteStream().use { stream -> ref.putStream(stream).await() }
        } catch (e: Exception) {
            // Name the track, and translate the two failures that actually happen in practice -
            // otherwise a rules or billing problem reads as a mysterious upload error.
            val hint = when {
                e.message?.contains("not have permission", ignoreCase = true) == true ||
                    e.message?.contains("unauthorized", ignoreCase = true) == true ->
                    " - deploy the Storage rules: npx firebase-tools deploy --only storage"

                e.message?.contains("does not exist", ignoreCase = true) == true ->
                    " - the Storage bucket is not set up yet; see docs/SETUP.md step 5"

                else -> ""
            }
            throw ShareUnavailable("Could not upload \"${track.title}\": ${e.message}$hint")
        }
    }

    private fun publicUrl(bucket: String, path: String): String {
        val encoded = URLEncoder.encode(path, Charsets.UTF_8.name())
        return "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded?alt=media"
    }

    private fun shareUrl(shareId: String): String = "$HOSTING_ORIGIN/p/$shareId"

    private fun newShareId(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TAG = "ShareService"
        val MIN_TTL_MS: Long = TimeUnit.HOURS.toMillis(1)
        val MAX_TTL_MS: Long = TimeUnit.DAYS.toMillis(30)

        /**
         * Where the web player is hosted. Firebase Hosting serves every project at
         * `<project-id>.web.app`; this is resolved at runtime from the Firestore project id.
         */
        val HOSTING_ORIGIN: String
            get() = runCatching {
                "https://" + FirebaseFirestore.getInstance().app.options.projectId + ".web.app"
            }.getOrDefault("https://thunder-play.web.app")
    }
}
