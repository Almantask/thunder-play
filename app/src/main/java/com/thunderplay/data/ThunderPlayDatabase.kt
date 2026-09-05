package com.thunderplay.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TrackEntity::class, PlayRollupEntity::class, PlaylistEntity::class],
    version = 1,
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
