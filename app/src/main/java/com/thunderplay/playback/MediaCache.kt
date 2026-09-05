package com.thunderplay.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.ServiceAccountAuth
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

/**
 * One cache backs both streaming and explicit downloads.
 *
 * [NoOpCacheEvictor] rather than an LRU: downloads must survive, and an LRU large enough to hold
 * the whole library would never evict anyway. The library is ~280 MB transcoded, so growth is
 * naturally bounded; Settings exposes the size and a way to clear it.
 */
object MediaCache {

    private const val DIRECTORY = "media"

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.filesDir, DIRECTORY),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context),
        ).also { instance = it }
    }

    fun sizeBytes(context: Context): Long = get(context).cacheSpace
}

/**
 * Builds the Drive download URL for a track.
 *
 * Note this is the API endpoint, not a shareable link - it only works with a bearer token, which
 * is why playback needs [DriveAuthResolver] rather than a plain HTTP source.
 */
fun TrackEntity.streamUri(): String =
    "https://www.googleapis.com/drive/v3/files/$driveId?alt=media"

/** A queue item. The custom cache key pins cached bytes to the file id, not the URL. */
fun TrackEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(driveId)
    .setUri(streamUri())
    .setCustomCacheKey(driveId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(category)
            .setAlbumTitle(level ?: category)
            .build(),
    )
    .build()

/**
 * Attaches a fresh bearer token as each request is opened.
 *
 * Tokens last an hour, so they cannot be baked into the factory: a long download or a queue that
 * outlives an hour would start 401-ing halfway through.
 */
class DriveAuthResolver(private val auth: ServiceAccountAuth) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val token = runBlocking { auth.accessToken() }
        return dataSpec.withRequestHeaders(mapOf("Authorization" to "Bearer $token"))
    }
}

/**
 * Cache-backed, authenticated source factory.
 *
 * Reads come from the cache when present and fall through to Drive otherwise, writing as they go -
 * so playing a track effectively downloads it.
 */
fun driveCacheDataSourceFactory(
    context: Context,
    client: OkHttpClient,
    auth: ServiceAccountAuth,
): DataSource.Factory {
    val upstream = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(client),
        DriveAuthResolver(auth),
    )
    return CacheDataSource.Factory()
        .setCache(MediaCache.get(context))
        .setUpstreamDataSourceFactory(upstream)
        // Never let a cache write failure kill playback; fall back to the network.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
