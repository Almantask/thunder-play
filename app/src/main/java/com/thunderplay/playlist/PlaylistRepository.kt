package com.thunderplay.playlist

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.thunderplay.data.PlaylistDao
import com.thunderplay.data.PlaylistEntity
import com.thunderplay.stats.FirestorePaths
import com.thunderplay.stats.FirestoreStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists, stored in Firestore and mirrored into Room.
 *
 * Room is the read path so the list renders offline and instantly; Firestore is what makes a
 * playlist survive a reinstall and appear on another device.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val stats: FirestoreStats,
) {
    private val db: FirebaseFirestore? get() =
        if (stats.isAvailable) FirebaseFirestore.getInstance() else null

    fun observeAll(): Flow<List<PlaylistEntity>> = dao.observeAll()

    fun observe(id: String): Flow<PlaylistEntity?> = dao.observe(id)

    suspend fun find(id: String): PlaylistEntity? = dao.find(id)

    suspend fun create(name: String, trackIds: List<String> = emptyList()): PlaylistEntity {
        val now = System.currentTimeMillis()
        val playlist = PlaylistEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
            trackIds = trackIds,
        )
        dao.upsert(playlist)
        push(playlist)
        return playlist
    }

    suspend fun rename(id: String, name: String) = mutate(id) { it.copy(name = name) }

    suspend fun addTracks(id: String, trackIds: List<String>) = mutate(id) { playlist ->
        // Adding a track already present is a no-op rather than a duplicate entry.
        val additions = trackIds.filterNot { it in playlist.trackIds }
        playlist.copy(trackIds = playlist.trackIds + additions)
    }

    suspend fun removeTrack(id: String, trackId: String) = mutate(id) {
        it.copy(trackIds = it.trackIds - trackId)
    }

    /** Strips dead/orphan track IDs from all playlists in Room and Firestore. */
    suspend fun removeTracksFromAllPlaylists(deadTrackIds: Set<String>): Int {
        if (deadTrackIds.isEmpty()) return 0
        var updatedCount = 0
        val all = dao.observeAll().first()
        for (pl in all) {
            val remaining = pl.trackIds.filterNot { it in deadTrackIds }
            if (remaining.size != pl.trackIds.size) {
                mutate(pl.id) { it.copy(trackIds = remaining) }
                updatedCount++
            }
        }
        return updatedCount
    }

    suspend fun move(id: String, from: Int, to: Int) = mutate(id) { playlist ->
        val ids = playlist.trackIds.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return@mutate playlist
        ids.add(to, ids.removeAt(from))
        playlist.copy(trackIds = ids)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        db?.document(FirestorePaths.LIBRARY)?.collection(FirestorePaths.PLAYLISTS)?.document(id)
            ?.delete()
            ?.addOnFailureListener { Log.w(TAG, "Playlist delete push failed for $id", it) }
    }

    internal suspend fun setShare(id: String, shareId: String?, expiresAt: Long?) =
        mutate(id) { it.copy(shareId = shareId, shareExpiresAt = expiresAt) }

    /** Pulls remote playlists into Room, dropping any deleted elsewhere. */
    suspend fun pull() {
        val store = db ?: return
        val snapshot = runCatching {
            store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.PLAYLISTS).get().await()
        }.getOrElse {
            Log.w(TAG, "Playlist pull failed", it)
            return
        }

        val remote = snapshot.documents.mapNotNull { doc ->
            val name = doc.getString("name") ?: return@mapNotNull null
            // Firestore hands arrays back as List<*>; filter rather than cast so one bad element
            // cannot throw while reading the whole playlist collection.
            val trackIds = (doc.get("trackIds") as? List<*>)
                .orEmpty()
                .filterIsInstance<String>()
            PlaylistEntity(
                id = doc.id,
                name = name,
                createdAt = doc.getLong("createdAt") ?: 0L,
                updatedAt = doc.getLong("updatedAt") ?: 0L,
                trackIds = trackIds,
                shareId = doc.getString("shareId"),
                shareExpiresAt = doc.getLong("shareExpiresAt"),
            )
        }
        if (remote.isNotEmpty()) dao.upsertAll(remote)
        dao.deleteMissing(remote.map { it.id })
    }

    private suspend fun mutate(id: String, block: (PlaylistEntity) -> PlaylistEntity) {
        val existing = dao.find(id) ?: return
        val updated = block(existing).copy(updatedAt = System.currentTimeMillis())
        dao.upsert(updated)
        push(updated)
    }

    private fun push(playlist: PlaylistEntity) {
        val store = db ?: return
        store.document(FirestorePaths.LIBRARY).collection(FirestorePaths.PLAYLISTS)
            .document(playlist.id)
            .set(
                mapOf(
                    "name" to playlist.name,
                    "createdAt" to playlist.createdAt,
                    "updatedAt" to playlist.updatedAt,
                    "trackIds" to playlist.trackIds,
                    "shareId" to playlist.shareId,
                    "shareExpiresAt" to playlist.shareExpiresAt,
                ),
            )
            .addOnFailureListener { Log.w(TAG, "Playlist push failed for ${playlist.id}", it) }
    }

    private companion object {
        const val TAG = "PlaylistRepo"
    }
}
