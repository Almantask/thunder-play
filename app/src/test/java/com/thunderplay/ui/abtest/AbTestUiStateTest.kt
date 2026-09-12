package com.thunderplay.ui.abtest

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.TrackEntity
import com.thunderplay.library.AbBracket
import com.thunderplay.library.AbGroup
import org.junit.Test

class AbTestUiStateTest {

    @Test
    fun `the card always carries two prompt slots, one per take`() {
        val state = state(prompts = mapOf("a" to "only A has been read"))

        assertThat(state.cardPrompts).containsExactly("only A has been read", null).inOrder()
    }

    @Test
    fun `matching prompts still occupy both slots`() {
        val state = state(prompts = mapOf("a" to "the same sentence", "b" to "the same sentence"))

        assertThat(state.cardPrompts).containsExactly("the same sentence", "the same sentence").inOrder()
        assertThat(state.mixedPrompts).isFalse()
    }

    @Test
    fun `different prompts are mixed, even when they share a filename slug`() {
        // The 48-character slug collision: two cues grouped as rivals whose real prompts disagree.
        val state = state(
            prompts = mapOf(
                "a" to "tribal percussive bed for the court approach",
                "b" to "tribal percussive bed for the court retreat",
            ),
        )

        assertThat(state.cardPrompts).hasSize(2)
        assertThat(state.mixedPrompts).isTrue()
    }

    @Test
    fun `a pair is not mixed until both prompts have been read`() {
        // The previous card collapsed to the one known sentence here, so a mixed pair looked
        // shared until the second header landed.
        val state = state(prompts = mapOf("a" to "tribal percussive bed for the court approach"))

        assertThat(state.mixedPrompts).isFalse()
        assertThat(state.cardPrompts).hasSize(2)
    }

    @Test
    fun `an empty deck has no card prompts`() {
        assertThat(AbTestUiState().cardPrompts).isNull()
        assertThat(AbTestUiState().mixedPrompts).isFalse()
    }

    private fun state(prompts: Map<String, String>) = AbTestUiState(
        bracket = AbBracket(
            AbGroup(
                key = "Aqua/I|tribal-percussive-bed-for-the-court|90s",
                slug = "tribal-percussive-bed-for-the-court",
                durationLabel = "90s",
                category = "Aqua",
                level = "I",
                takes = listOf(track("a"), track("b")),
            ),
        ),
        prompts = prompts,
    )

    private fun track(driveId: String) = TrackEntity(
        driveId = driveId,
        title = "tribal-percussive-bed-for-the-court-90s-$driveId",
        relativePath = "Aqua/I/tribal-percussive-bed-for-the-court-90s-$driveId.m4a",
        category = "Aqua",
        level = "I",
        sizeBytes = null,
        md5Checksum = null,
        addedAt = null,
        modifiedAt = null,
        sourceWavDriveId = null,
        prompt = null,
    )
}
