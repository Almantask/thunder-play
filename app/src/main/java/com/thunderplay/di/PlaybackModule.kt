package com.thunderplay.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.thunderplay.drive.ServiceAccountAuth
import com.thunderplay.playback.DOWNLOAD_CHANNEL_ID
import com.thunderplay.playback.MediaCache
import com.thunderplay.playback.driveCacheDataSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun cache(@ApplicationContext context: Context): Cache = MediaCache.get(context)

    @Provides
    @Singleton
    fun databaseProvider(@ApplicationContext context: Context): DatabaseProvider =
        StandaloneDatabaseProvider(context)

    @Provides
    @Singleton
    fun downloaderFactory(
        @ApplicationContext context: Context,
        cache: Cache,
        client: OkHttpClient,
        auth: ServiceAccountAuth,
    ): DownloaderFactory {
        // Downloads go through the same authenticated, cache-writing stack as playback, so a
        // streamed track and a downloaded one end up as the same cache entry.
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                driveCacheDataSourceFactory(context, client, auth),
            )
        return DefaultDownloaderFactory(factory, Executors.newFixedThreadPool(3))
    }

    @Provides
    @Singleton
    fun downloadManager(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
        cache: Cache,
        downloaderFactory: DownloaderFactory,
    ): DownloadManager = DownloadManager(
        context,
        DefaultDownloadIndex(databaseProvider),
        downloaderFactory,
    ).apply {
        maxParallelDownloads = 3
    }

    @Provides
    @Singleton
    fun downloadNotificationHelper(
        @ApplicationContext context: Context,
    ): DownloadNotificationHelper = DownloadNotificationHelper(context, DOWNLOAD_CHANNEL_ID)
}
