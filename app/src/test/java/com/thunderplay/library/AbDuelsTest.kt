package com.thunderplay.library

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.TrackEntity
import org.junit.Test

class AbDuelsTest {

    @Test
    fun `two takes are decided in a single swipe`() {
        val bracket = AbBracket(group("a", "b"))

        assertThat(bracket.rounds).isEqualTo(1)
        assertThat(bracket.duel!!.a.driveId).isEqualTo("a")
        assertThat(bracket.duel!!.b.driveId).isEqualTo("b")
        assertThat(bracket.winner).isNull()

        val decided = bracket.keep("b")!!

        assertThat(decided.duel).isNull()
        assertThat(decided.winner!!.driveId).isEqualTo("b")
    }

    @Test
    fun `four takes are three duels and the keeper carries forward on the left`() {
        // The point of the ladder: never more than two takes on screen, and never a pair the user
        // has already ruled on.
        var bracket = AbBracket(group("a", "b", "c", "d"))
        assertThat(bracket.rounds).isEqualTo(3)

        assertThat(bracket.duel).isEqualTo(AbDuel(bracket.group.takes[0], bracket.group.takes[1], 1, 3))
        bracket = bracket.keep("b")!!

        // "b" won, so it holds the left-hand side against the next challenger.
        assertThat(bracket.duel!!.a.driveId).isEqualTo("b")
        assertThat(bracket.duel!!.b.driveId).isEqualTo("c")
        assertThat(bracket.duel!!.round).isEqualTo(2)
        bracket = bracket.keep("c")!!

        assertThat(bracket.duel!!.a.driveId).isEqualTo("c")
        assertThat(bracket.duel!!.b.driveId).isEqualTo("d")
        assertThat(bracket.winner).isNull()
        bracket = bracket.keep("c")!!

        assertThat(bracket.winner!!.driveId).isEqualTo("c")
        assertThat(bracket.decided).isEqualTo(3)
    }

    @Test
    fun `a take that is not on the card is a stale swipe, not a crash`() {
        val bracket = AbBracket(group("a", "b", "c"))

        // "c" has not come up yet, and there is nothing to keep once the ladder is finished.
        assertThat(bracket.keep("c")).isNull()
        assertThat(bracket.keep("elsewhere")).isNull()
        assertThat(bracket.keep("a")!!.keep("c")!!.keep("c")).isNull()
    }

    @Test
    fun `undo puts the previous pair back`() {
        val bracket = AbBracket(group("a", "b", "c")).keep("a")!!

        val back = bracket.undo()

        assertThat(back.duel).isEqualTo(AbDuel(back.group.takes[0], back.group.takes[1], 1, 2))
        assertThat(back.decided).isEqualTo(0)
        // Undoing at the start is a no-op rather than an error: the button is a gesture too.
        assertThat(back.undo()).isEqualTo(back)
    }

    @Test
    fun `resuming keeps only the picks that still hold after a refresh`() {
        // Judged "a" over "b", then "b" was trashed from the library screen and the group came
        // back as two takes. The first pick still names a real take, so round one stands.
        val bracket = AbBracket.resume(group("a", "c"), picks = listOf("a", "c"))

        assertThat(bracket.picks).isEqualTo(listOf("a"))
        assertThat(bracket.winner!!.driveId).isEqualTo("a")
    }

    @Test
    fun `resuming a cue whose takes have all changed starts over`() {
        val bracket = AbBracket.resume(group("x", "y"), picks = listOf("a"))

        assertThat(bracket.picks).isEmpty()
        assertThat(bracket.duel!!.round).isEqualTo(1)
    }

    @Test
    fun `a skipped cue goes to the back of the deck rather than out of it`() {
        val deck = listOf(group("a"), group("b"), group("c"))
            .mapIndexed { index, group -> group.copy(key = "key$index", slug = "slug$index") }

        val ordered = AbDeck.order(deck, skipped = listOf("key0"), retired = emptySet())

        assertThat(ordered.map { it.key }).isEqualTo(listOf("key1", "key2", "key0"))
    }

    @Test
    fun `skipped cues come back in the order they were put off`() {
        val deck = (0..3).map { group("a").copy(key = "key$it") }

        val ordered = AbDeck.order(deck, skipped = listOf("key2", "key0"), retired = emptySet())

        assertThat(ordered.map { it.key }).isEqualTo(listOf("key1", "key3", "key2", "key0"))
    }

    @Test
    fun `a cue being filed leaves the deck before Room has caught up`() {
        // Room drops a judged cue a moment after the verdict lands. Without this the deck would
        // hand the cue straight back and invite a second swipe on files that have already moved.
        val deck = (0..2).map { group("a").copy(key = "key$it") }

        val ordered = AbDeck.order(deck, skipped = listOf("key0"), retired = setOf("key0", "key1"))

        assertThat(ordered.map { it.key }).isEqualTo(listOf("key2"))
    }

    private fun group(vararg driveIds: String) = AbGroup(
        key = "Aqua/I|ancient-ambient-awe|90s",
        slug = "ancient-ambient-awe",
        durationLabel = "90s",
        category = "Aqua",
        level = "I",
        takes = driveIds.map(::track),
    )

    private fun track(driveId: String) = TrackEntity(
        driveId = driveId,
        title = "ancient-ambient-awe-90s-$driveId",
        relativePath = "Aqua/I/ancient-ambient-awe-90s-$driveId.m4a",
        category = "Aqua",
        level = "I",
        sizeBytes = null,
        md5Checksum = null,
        addedAt = null,
        modifiedAt = null,
        sourceWavDriveId = null,
    )
}
