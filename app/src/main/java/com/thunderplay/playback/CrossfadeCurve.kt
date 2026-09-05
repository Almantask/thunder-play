package com.thunderplay.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power crossfade curve.
 *
 * A linear fade dips in the middle: two uncorrelated signals at 0.5 gain sum to about 0.71 of the
 * original power, which is audible as a hole in the transition. Sine/cosine gains keep
 * `out^2 + in^2 == 1` throughout, so perceived loudness stays flat across the blend.
 */
object CrossfadeCurve {

    /** Gain for the track being faded out, at progress [t] in 0..1. */
    fun outgoing(t: Float): Float = cos(t.coerceIn(0f, 1f) * (PI / 2)).toFloat()

    /** Gain for the track being faded in, at progress [t] in 0..1. */
    fun incoming(t: Float): Float = sin(t.coerceIn(0f, 1f) * (PI / 2)).toFloat()

    /**
     * How far through the fade we are, given how much of the outgoing track is left.
     *
     * Returns 0 before the fade window opens and 1 once it has fully closed.
     */
    fun progress(remainingMs: Long, crossfadeMs: Int): Float {
        if (crossfadeMs <= 0) return 1f
        if (remainingMs >= crossfadeMs) return 0f
        if (remainingMs <= 0) return 1f
        return 1f - (remainingMs.toFloat() / crossfadeMs)
    }

    /**
     * Whether a fade should start now.
     *
     * A fade needs a known duration to be scheduled against, and enough of the track left to be
     * worth starting; both are why an unknown duration falls back to a hard cut.
     */
    fun shouldStart(
        positionMs: Long,
        durationMs: Long,
        crossfadeMs: Int,
        hasNext: Boolean,
    ): Boolean {
        if (!hasNext || crossfadeMs <= 0) return false
        if (durationMs <= 0) return false
        // A crossfade longer than the track itself would start before playback does.
        val window = minOf(crossfadeMs.toLong(), durationMs / 2)
        if (window <= 0) return false
        return durationMs - positionMs <= window
    }

    /**
     * Fade length for a manual skip.
     *
     * Skipping should feel immediate, so a long ambient crossfade is clamped hard - waiting six
     * seconds for Next to take effect reads as lag, not as a nice transition.
     */
    fun manualSkipMs(crossfadeMs: Int): Int = crossfadeMs.coerceAtMost(MAX_MANUAL_SKIP_MS)

    const val MAX_MANUAL_SKIP_MS = 400
}
