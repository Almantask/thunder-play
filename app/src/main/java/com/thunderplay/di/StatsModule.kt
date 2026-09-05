package com.thunderplay.di

import com.thunderplay.stats.StatsGateway
import com.thunderplay.stats.SyncingStatsGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StatsModule {

    /**
     * Local write plus a fire-and-forget remote mirror. Falls back to local-only automatically
     * when Firebase is not configured, so the app is usable before setup is finished.
     */
    @Binds
    @Singleton
    abstract fun statsGateway(impl: SyncingStatsGateway): StatsGateway
}
