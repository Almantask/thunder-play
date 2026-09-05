package com.thunderplay.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class CrossfadeCurveTest {

    @Test
    fun `endpoints hand the signal fully from one track to the other`() {
        assertThat(CrossfadeCurve.outgoing(0f)).isWithin(TOLERANCE).of(1f)
        assertThat(CrossfadeCurve.incoming(0f)).isWithin(TOLERANCE).of(0f)
        assertThat(CrossfadeCurve.outgoing(1f)).isWithin(TOLERANCE).of(0f)
        assertThat(CrossfadeCurve.incoming(1f)).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun `power stays constant across the whole fade`() {
        // This is the point of an equal-power curve: a linear fade would dip to about 0.71 here,
        // which is audible as a hole in the middle of the transition.
        for (step in 0..100) {
            val t = step / 100f
            val power = CrossfadeCurve.outgoing(t).let { it * it } +
                CrossfadeCurve.incoming(t).let { it * it }
            assertThat(abs(power - 1f)).isLessThan(TOLERANCE)
        }
    }

    @Test
    fun `the midpoint is the equal-power value, not one half`() {
        assertThat(CrossfadeCurve.outgoing(0.5f)).isWithin(TOLERANCE).of(0.7071f)
        assertThat(CrossfadeCurve.incoming(0.5f)).isWithin(TOLERANCE).of(0.7071f)
    }

    @Test
    fun `gains are clamped outside the nominal range`() {
        assertThat(CrossfadeCurve.outgoing(-1f)).isWithin(TOLERANCE).of(1f)
        assertThat(CrossfadeCurve.incoming(2f)).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun `progress runs from zero to one as the remainder shrinks`() {
        assertThat(CrossfadeCurve.progress(remainingMs = 3_000, crossfadeMs = 3_000)).isEqualTo(0f)
        assertThat(CrossfadeCurve.progress(remainingMs = 1_500, crossfadeMs = 3_000))
            .isWithin(TOLERANCE).of(0.5f)
        assertThat(CrossfadeCurve.progress(remainingMs = 0, crossfadeMs = 3_000)).isEqualTo(1f)
        assertThat(CrossfadeCurve.progress(remainingMs = -500, crossfadeMs = 3_000)).isEqualTo(1f)
    }

    @Test
    fun `a disabled crossfade reports as already finished`() {
        assertThat(CrossfadeCurve.progress(remainingMs = 5_000, crossfadeMs = 0)).isEqualTo(1f)
    }

    @Test
    fun `a fade starts only once inside the window`() {
        val args = { position: Long ->
            CrossfadeCurve.shouldStart(position, durationMs = 60_000, crossfadeMs = 3_000, hasNext = true)
        }
        assertThat(args(56_000)).isFalse()
        assertThat(args(57_000)).isTrue()
        assertThat(args(59_500)).isTrue()
    }

    @Test
    fun `no fade without a next track`() {
        assertThat(
            CrossfadeCurve.shouldStart(59_000, durationMs = 60_000, crossfadeMs = 3_000, hasNext = false),
        ).isFalse()
    }

    @Test
    fun `no fade when the duration is unknown`() {
        // Streamed audio reports -1 until the container is parsed; there is nothing to schedule
        // against, so the transition falls back to a hard cut.
        assertThat(
            CrossfadeCurve.shouldStart(10_000, durationMs = -1, crossfadeMs = 3_000, hasNext = true),
        ).isFalse()
    }

    @Test
    fun `the fade window never exceeds half the track`() {
        // A 4s sting with a 6s crossfade would otherwise start fading before it began playing.
        assertThat(
            CrossfadeCurve.shouldStart(1_000, durationMs = 4_000, crossfadeMs = 6_000, hasNext = true),
        ).isFalse()
        assertThat(
            CrossfadeCurve.shouldStart(2_100, durationMs = 4_000, crossfadeMs = 6_000, hasNext = true),
        ).isTrue()
    }

    @Test
    fun `crossfade disabled means no fade at all`() {
        assertThat(
            CrossfadeCurve.shouldStart(59_000, durationMs = 60_000, crossfadeMs = 0, hasNext = true),
        ).isFalse()
    }

    @Test
    fun `manual skips are clamped so Next stays responsive`() {
        assertThat(CrossfadeCurve.manualSkipMs(12_000))
            .isEqualTo(CrossfadeCurve.MAX_MANUAL_SKIP_MS)
        assertThat(CrossfadeCurve.manualSkipMs(200)).isEqualTo(200)
        assertThat(CrossfadeCurve.manualSkipMs(0)).isEqualTo(0)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
