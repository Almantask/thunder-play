package com.thunderplay.library

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.TrackEntity
import org.junit.Assert.assertThrows
import org.junit.Test

class AbGroupingTest {

    @Test
    fun `the four real takes of one cue form a single group`() {
        // Straight from the library: four renders of the same cue, differing only in the suffix.
        val takes = listOf(
            track("instrumental-concept-study-ambient-expansive-and-120s-4689f1c8"),
            track("instrumental-concept-study-ambient-expansive-and-120s-85904681"),
            track("instrumental-concept-study-ambient-expansive-and-120s-c52a573a"),
            track("instrumental-concept-study-ambient-expansive-and-120s-067906bc"),
        )

        val groups = AbGrouping.candidates(takes)

        assertThat(groups).hasSize(1)
        assertThat(groups.single().takes).hasSize(4)
        assertThat(groups.single().slug).isEqualTo("instrumental-concept-study-ambient-expansive-and")
        assertThat(groups.single().durationLabel).isEqualTo("120s")
    }

    @Test
    fun `a different duration is a different cue`() {
        val groups = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-4689f1c8"),
                track("ancient-ambient-awe-90s-85904681"),
                track("ancient-ambient-awe-120s-c52a573a"),
            ),
        )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().durationLabel).isEqualTo("90s")
    }

    @Test
    fun `the same slug in another category or level does not group`() {
        // The generator writes into mode/category/subcategory, so the folder is part of the cue's
        // identity - the same prompt rendered for Aqua is not a rival take of the Court one.
        val groups = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-4689f1c8", category = "Aqua", level = "I"),
                track("ancient-ambient-awe-90s-85904681", category = "Court", level = "I"),
                track("ancient-ambient-awe-90s-c52a573a", category = "Aqua", level = "II"),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun `a name with no eight-hex suffix is never a candidate`() {
        // Hand-dropped files and anything not written by the generator must stay out of A/B.
        assertThat(AbGrouping.keyOf(track("My Own Recording"))).isNull()
        assertThat(AbGrouping.keyOf(track("ancient-ambient-awe-90s"))).isNull()
        assertThat(AbGrouping.keyOf(track("ancient-ambient-awe-90s-NOTHEX01"))).isNull()
        assertThat(AbGrouping.keyOf(track("ancient-ambient-awe-90s-4689f1c"))).isNull()
    }

    @Test
    fun `a slug ending in something duration-shaped keys on the real duration`() {
        // "…-30s-45s-abcdef01" must read 45s as the duration and keep "-30s" inside the slug,
        // because the duration is positional - it is always the token before the fingerprint.
        val groups = AbGrouping.candidates(
            listOf(
                track("countdown-from-30s-45s-abcdef01"),
                track("countdown-from-30s-45s-abcdef02"),
            ),
        )

        assertThat(groups.single().durationLabel).isEqualTo("45s")
        assertThat(groups.single().slug).isEqualTo("countdown-from-30s")
    }

    @Test
    fun `a lone take is not a candidate group`() {
        val groups = AbGrouping.candidates(listOf(track("ancient-ambient-awe-90s-4689f1c8")))

        assertThat(groups).isEmpty()
    }

    @Test
    fun `fractional and whole durations are never conflated`() {
        // The generator formats with %g, so "39.5s" and "40s" are already canonical. Parsing them
        // to millis to compare would invent a rounding question that does not exist.
        val groups = AbGrouping.candidates(
            listOf(
                track("dusk-bed-39.5s-4689f1c8"),
                track("dusk-bed-40s-85904681"),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun `the winner is good and every other take is bad`() {
        val group = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-aaaaaaaa", driveId = "a"),
                track("ancient-ambient-awe-90s-bbbbbbbb", driveId = "b"),
                track("ancient-ambient-awe-90s-cccccccc", driveId = "c"),
            ),
        ).single()

        val verdicts = AbGrouping.verdicts(group, winnerDriveId = "b")

        assertThat(verdicts).containsExactly(
            "a", AbVerdict.Bad,
            "b", AbVerdict.Good,
            "c", AbVerdict.Bad,
        )
    }

    @Test
    fun `drawn takes are kept as ties beside the winner`() {
        val group = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-aaaaaaaa", driveId = "a"),
                track("ancient-ambient-awe-90s-bbbbbbbb", driveId = "b"),
                track("ancient-ambient-awe-90s-cccccccc", driveId = "c"),
            ),
        ).single()

        val verdicts = AbGrouping.verdicts(group, winnerDriveId = "b", tiedDriveIds = setOf("c"))

        assertThat(verdicts).containsExactly(
            "a", AbVerdict.Bad,
            "b", AbVerdict.Good,
            "c", AbVerdict.Tie,
        )
    }

    @Test
    fun `a cue that was only tied names no winner`() {
        val group = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-aaaaaaaa", driveId = "a"),
                track("ancient-ambient-awe-90s-bbbbbbbb", driveId = "b"),
            ),
        ).single()

        val verdicts = AbGrouping.verdicts(group, winnerDriveId = null, tiedDriveIds = setOf("a", "b"))

        assertThat(verdicts).containsExactly("a", AbVerdict.Tie, "b", AbVerdict.Tie)
    }

    @Test
    fun `a judgement that keeps nothing is rejected`() {
        // No winner and no tie would file every take as bad, which no judgement ever means.
        val group = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-aaaaaaaa", driveId = "a"),
                track("ancient-ambient-awe-90s-bbbbbbbb", driveId = "b"),
            ),
        ).single()

        assertThrows(IllegalArgumentException::class.java) {
            AbGrouping.verdicts(group, winnerDriveId = null)
        }
    }

    @Test
    fun `a tie from outside the group is rejected`() {
        val group = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-aaaaaaaa", driveId = "a"),
                track("ancient-ambient-awe-90s-bbbbbbbb", driveId = "b"),
            ),
        ).single()

        assertThrows(IllegalArgumentException::class.java) {
            AbGrouping.verdicts(group, winnerDriveId = "a", tiedDriveIds = setOf("elsewhere"))
        }
    }

    @Test
    fun `a winner from outside the group is rejected`() {
        val group = AbGrouping.candidates(
            listOf(
                track("ancient-ambient-awe-90s-aaaaaaaa", driveId = "a"),
                track("ancient-ambient-awe-90s-bbbbbbbb", driveId = "b"),
            ),
        ).single()

        assertThrows(IllegalArgumentException::class.java) {
            AbGrouping.verdicts(group, winnerDriveId = "somewhere-else")
        }
    }

    @Test
    fun `takes are ordered stably so the on-screen numbering does not shuffle`() {
        val shuffled = listOf(
            track("ancient-ambient-awe-90s-cccccccc"),
            track("ancient-ambient-awe-90s-aaaaaaaa"),
            track("ancient-ambient-awe-90s-bbbbbbbb"),
        )

        val titles = AbGrouping.candidates(shuffled).single().takes.map { it.title }

        assertThat(titles).isInStrictOrder()
    }

    private fun track(
        title: String,
        category: String = "Aqua",
        level: String? = "I",
        driveId: String = title,
    ) = TrackEntity(
        driveId = driveId,
        title = title,
        relativePath = "$category/$level/$title.m4a",
        category = category,
        level = level,
        sizeBytes = null,
        md5Checksum = null,
        addedAt = null,
        modifiedAt = null,
        sourceWavDriveId = null,
    )
}
