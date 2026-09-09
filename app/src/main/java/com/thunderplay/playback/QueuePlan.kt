package com.thunderplay.playback

import androidx.media3.common.Player

/**
 * The index arithmetic behind the queue, kept away from the player that performs it.
 *
 * [CrossfadePlayer] owns two ExoPlayers and a handler, none of which a unit test can have, so the
 * rules that are actually easy to get wrong - where playback goes when a track ends, and what
 * happens to the playing position when the list is edited underneath it - live here instead.
 */
object QueuePlan {

    /**
     * Where playback goes when a track ends by itself, or null when it should stop.
     *
     * Only the automatic transition consults the repeat mode. Pressing Next with repeat-one on
     * still moves to the following track, as it does in every other player: repeating one track is
     * about what happens when it runs out, not about disabling the button.
     */
    fun autoNext(currentIndex: Int, size: Int, repeatMode: Int): Int? = when {
        size <= 0 -> null
        repeatMode == Player.REPEAT_MODE_ONE -> currentIndex.coerceIn(0, size - 1)
        currentIndex + 1 < size -> currentIndex + 1
        repeatMode == Player.REPEAT_MODE_ALL -> 0
        else -> null
    }

    /**
     * Where the playing track ends up once [added] items are inserted at [at].
     *
     * Inserting at or before the current position pushes it down; "play next" inserts just after
     * it and so leaves it alone.
     */
    fun indexAfterAdd(currentIndex: Int, at: Int, added: Int): Int =
        if (at <= currentIndex) currentIndex + added else currentIndex

    /** What removing the half-open range `[from, to)` does to the playing position. */
    sealed interface Removal {
        /** Nothing is left to play. */
        data object Emptied : Removal

        /** The playing track itself went; [index] is what fell into its place. */
        data class Reload(val index: Int) : Removal

        /** The playing track survives, at [index]. */
        data class Keep(val index: Int) : Removal
    }

    fun afterRemoval(currentIndex: Int, from: Int, to: Int, sizeBefore: Int): Removal {
        val removed = (to - from).coerceAtLeast(0)
        val sizeAfter = sizeBefore - removed
        return when {
            sizeAfter <= 0 -> Removal.Emptied
            // Dropping what is playing moves on to whatever fell into its place, which is what
            // removing the current track from a queue is understood to mean.
            currentIndex in from until to -> Removal.Reload(from.coerceAtMost(sizeAfter - 1))
            currentIndex >= to -> Removal.Keep(currentIndex - removed)
            else -> Removal.Keep(currentIndex)
        }
    }
}
