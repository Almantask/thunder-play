package com.thunderplay.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Version 2 adds the A/B judging columns to `tracks`. They are all nullable with no default, so
 * Room generates plain ALTER TABLE ADD COLUMN and needs no migration spec. There is no
 * fallbackToDestructiveMigration anywhere, so every schema change must arrive with a migration or
 * existing installs throw on launch.
 */
@Database(
    entities = [TrackEntity::class, PlayRollupEntity::class, PlaylistEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
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
