package com.thunderplay.ui.abtest

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptSizingTest {

    @Test
    fun `a prompt with room to spare is drawn at the largest size`() {
        assertThat(PromptSizing.largestFitting { true }).isEqualTo(PromptSizing.MAX_SP)
    }

    @Test
    fun `the size steps down to the largest one that fits`() {
        val size = PromptSizing.largestFitting { it <= 27f }

        assertThat(size).isEqualTo(26f)
    }

    @Test
    fun `however little room there is the text never drops below the readable floor`() {
        // The card cuts the text and offers the full view instead - squinting is not an option.
        assertThat(PromptSizing.largestFitting { false }).isEqualTo(PromptSizing.MIN_SP)
    }

    @Test
    fun `the search stops at the first size that fits`() {
        val tried = mutableListOf<Float>()

        PromptSizing.largestFitting { size -> tried += size; size <= 36f }

        assertThat(tried).containsExactly(40f, 38f, 36f).inOrder()
    }

    @Test
    fun `the full view reads at a size no smaller than the card ever uses`() {
        assertThat(PromptSizing.READING_SP).isAtLeast(PromptSizing.MIN_SP)
    }
}
