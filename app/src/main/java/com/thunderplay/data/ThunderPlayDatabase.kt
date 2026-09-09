package com.thunderplay.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Version 2 adds the A/B judging columns to `tracks`; version 3 adds the ones read out of the
 * source WAV's header - prompt, genre, intensity, instruments and duration. They are all nullable
 * with no default, so Room generates plain ALTER TABLE ADD COLUMN and needs no migration spec.
 * There is no fallbackToDestructiveMigration anywhere, so every schema change must arrive with a
 * migration or existing installs throw on launch.
 */
@Database(
    entities = [TrackEntity::class, PlayRollupEntity::class, PlaylistEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
@TypeConverters(TrackIdListConverter::class)
abstract class ThunderPlayDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playDao(): PlayDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "thunderplay.db"
    }
}
