package com.thunderplay.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A local mirror of one Firestore `plays` document.
 *
 * History screens read from here rather than aggregating in Firestore, so "most played this
 * month" costs nothing. [PlayRollupSync] pulls only rows newer than a stored cursor.
 */
@Entity(tableName = "plays", indices = [Index("trackId"), Index("startedAt")])
data class PlayRollupEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val startedAt: Long,
    val msPlayed: Long,
    val completed: Boolean,
)
