package com.thunderplay.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueuePlanAutoNextTest {

    @Test
    fun `plays the following track`() {
        assertThat(QueuePlan.autoNext(0, size = 3, repeatMode = Player.REPEAT_MODE_OFF))
            .isEqualTo(1)
    }

    @Test
    fun `stops at the end of the queue`() {
        assertThat(QueuePlan.autoNext(2, size = 3, repeatMode = Player.REPEAT_MODE_OFF)).isNull()
    }

    @Test
    fun `repeat all wraps to the start`() {
        assertThat(QueuePlan.autoNext(2, size = 3, repeatMode = Player.REPEAT_MODE_ALL))
            .isEqualTo(0)
    }

    @Test
    fun `repeat one returns the same track, which is what makes a bed loop`() {
        // The player fades the track into itself from here, so an ambience bed loops seamlessly
        // rather than restarting with a gap.
        assertThat(QueuePlan.autoNext(1, size = 3, repeatMode = Player.REPEAT_MODE_ONE))
            .isEqualTo(1)
    }

    @Test
    fun `repeat one on a single-track queue still repeats it`() {
        assertThat(QueuePlan.autoNext(0, size = 1, repeatMode = Player.REPEAT_MODE_ONE))
            .isEqualTo(0)
    }

    @Test
    fun `an empty queue has nowhere to go in any mode`() {
        assertThat(QueuePlan.autoNext(0, size = 0, repeatMode = Player.REPEAT_MODE_OFF)).isNull()
        assertThat(QueuePlan.autoNext(0, size = 0, repeatMode = Player.REPEAT_MODE_ALL)).isNull()
        assertThat(QueuePlan.autoNext(0, size = 0, repeatMode = Player.REPEAT_MODE_ONE)).isNull()
    }
}

class QueuePlanEditsTest {

    @Test
    fun `inserting after the playing track leaves it where it is`() {
        // This is the "play next" case, and moving the playing index would skip a track.
        assertThat(QueuePlan.indexAfterAdd(currentIndex = 2, at = 3, added = 1)).isEqualTo(2)
    }

    @Test
    fun `inserting before the playing track pushes it down`() {
        assertThat(QueuePlan.indexAfterAdd(currentIndex = 2, at = 0, added = 3)).isEqualTo(5)
    }

    @Test
    fun `removing later tracks leaves the playing position alone`() {
        assertThat(QueuePlan.afterRemoval(currentIndex = 1, from = 3, to = 5, sizeBefore = 6))
            .isEqualTo(QueuePlan.Removal.Keep(1))
    }

    @Test
    fun `removing earlier tracks shifts the playing position back`() {
        assertThat(QueuePlan.afterRemoval(currentIndex = 4, from = 1, to = 3, sizeBefore = 6))
            .isEqualTo(QueuePlan.Removal.Keep(2))
    }

    @Test
    fun `removing the playing track moves to whatever takes its place`() {
        assertThat(QueuePlan.afterRemoval(currentIndex = 2, from = 2, to = 3, sizeBefore = 6))
            .isEqualTo(QueuePlan.Removal.Reload(2))
    }

    @Test
    fun `removing the playing last track falls back to the new last one`() {
        // from would be past the end of the shortened queue, and seeking there would throw.
        assertThat(QueuePlan.afterRemoval(currentIndex = 5, from = 5, to = 6, sizeBefore = 6))
            .isEqualTo(QueuePlan.Removal.Reload(4))
    }

    @Test
    fun `removing everything empties the queue`() {
        assertThat(QueuePlan.afterRemoval(currentIndex = 1, from = 0, to = 3, sizeBefore = 3))
            .isEqualTo(QueuePlan.Removal.Emptied)
    }
}
