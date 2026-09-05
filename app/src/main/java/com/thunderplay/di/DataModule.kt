package com.thunderplay.di

import android.content.Context
import androidx.room.Room
import com.thunderplay.data.PlayDao
import com.thunderplay.data.PlaylistDao
import com.thunderplay.data.ThunderPlayDatabase
import com.thunderplay.data.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ThunderPlayDatabase =
        Room.databaseBuilder(context, ThunderPlayDatabase::class.java, ThunderPlayDatabase.NAME)
            .build()

    @Provides
    fun trackDao(db: ThunderPlayDatabase): TrackDao = db.trackDao()

    @Provides
    fun playDao(db: ThunderPlayDatabase): PlayDao = db.playDao()

    @Provides
    fun playlistDao(db: ThunderPlayDatabase): PlaylistDao = db.playlistDao()
}
