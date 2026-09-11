package com.thunderplay.ui.abtest

/**
 * How big a prompt is drawn on the A/B card.
 *
 * Kept apart from the composable that measures the text, so the one rule that matters - never
 * smaller than [MIN_SP], however little room there is - is pinned by a test rather than by eye.
 */
internal object PromptSizing {

    /** Legible at arm's length without squinting. Below this the card cuts the text instead. */
    const val MIN_SP = 18f

    /** Past this a 120-character prompt stops reading as a sentence and starts reading as a sign. */
    const val MAX_SP = 40f

    const val STEP_SP = 2f

    /**
     * The size the full-prompt sheet reads at.
     *
     * A reading size rather than a fitted one: the sheet exists for when the card's size is wrong
     * for reading - cut short at the floor, or so large a sentence runs three words to a line.
     */
    const val READING_SP = 20f

    /**
     * The largest size from [MAX_SP] down to [MIN_SP] at which [fits] holds, or [MIN_SP] if none.
     *
     * Largest first, so the search stops at the answer instead of measuring every size.
     */
    fun largestFitting(fits: (Float) -> Boolean): Float {
        var size = MAX_SP
        while (size > MIN_SP && !fits(size)) size = (size - STEP_SP).coerceAtLeast(MIN_SP)
        return size
    }
}
