package com.thunderplay.library

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.TrackEntity
import org.junit.Test

class PromptInsightsTest {

    @Test
    fun `ranks a word by the average of the tracks using it`() {
        val insights = PromptInsights.summarise(
            listOf(
                track("tribal drums", rating = 5),
                track("tribal chant", rating = 4),
                track("tribal war horn", rating = 3),
                track("ambient drone", rating = 1),
                track("ambient pad", rating = 1),
                track("ambient wash", rating = 1),
            ),
        )

        val terms = insights.terms.filter { it.kind == TermKind.Word }.map { it.term }
        assertThat(terms.first()).isEqualTo("tribal")
        assertThat(terms.last()).isEqualTo("ambient")
        assertThat(insights.terms.first { it.term == "tribal" }.averageRating).isEqualTo(4.0)
    }

    @Test
    fun `unrated tracks are not counted as zero`() {
        // Unrated means unjudged everywhere else in the app. Averaging it in as zero would drag
        // every common word down in proportion to how much of the library is still unheard.
        val insights = PromptInsights.summarise(
            listOf(
                track("tribal drums", rating = 4),
                track("tribal chant", rating = 4),
                track("tribal horn", rating = 4),
                track("tribal wash", rating = 0),
                track("tribal bed", rating = 0),
            ),
        )

        val tribal = insights.terms.single { it.term == "tribal" }
        assertThat(tribal.averageRating).isEqualTo(4.0)
        assertThat(tribal.tracks).isEqualTo(5)
        assertThat(tribal.ratedTracks).isEqualTo(3)
    }

    @Test
    fun `a term with too few ratings is not ranked at all`() {
        // One rated track behind a word would put every word in that prompt joint first.
        val insights = PromptInsights.summarise(
            listOf(
                track("solitary bassoon", rating = 5),
                track("common drums", rating = 3),
                track("common chant", rating = 3),
                track("common horn", rating = 3),
            ),
        )

        assertThat(insights.terms.map { it.term }).doesNotContain("bassoon")
        assertThat(insights.terms.map { it.term }).contains("common")
    }

    @Test
    fun `a word repeated inside one prompt is still one track`() {
        val insights = PromptInsights.summarise(
            listOf(
                track("dark, dark strings, darker still", rating = 4),
                track("dark brass", rating = 4),
                track("dark choir", rating = 4),
            ),
        )

        assertThat(insights.terms.single { it.term == "dark" }.tracks).isEqualTo(3)
    }

    @Test
    fun `grammar and the generator's boilerplate are dropped`() {
        val insights = PromptInsights.summarise(
            listOf(
                track("TrackType: Music, the horn and the drum", rating = 4),
                track("TrackType: Music, the horn with a drum", rating = 4),
                track("TrackType: Music, the horn over a drum", rating = 4),
            ),
        )

        val terms = insights.terms.map { it.term }
        assertThat(terms).containsAtLeast("horn", "drum")
        assertThat(terms).containsNoneOf("the", "and", "with", "over", "music", "tracktype")
    }

    @Test
    fun `instruments and genre are separate evidence from prompt words`() {
        val insights = PromptInsights.summarise(
            List(3) {
                track(
                    prompt = "a bed",
                    rating = 4,
                    instruments = "low brass; sub-bass drone",
                    genre = "Ambience",
                )
            },
        )

        assertThat(insights.terms.map { it.kind to it.term })
            .containsAtLeast(
                TermKind.Instrument to "low brass",
                TermKind.Instrument to "sub-bass drone",
                TermKind.Genre to "ambience",
            )
    }

    @Test
    fun `A and B verdicts are tallied per term`() {
        val insights = PromptInsights.summarise(
            listOf(
                track("tribal drums", rating = 5, verdict = "good"),
                track("tribal chant", rating = 4, verdict = "bad"),
                track("tribal horn", rating = 4, verdict = "bad"),
            ),
        )

        val tribal = insights.terms.single { it.term == "tribal" }
        assertThat(tribal.abWins).isEqualTo(1)
        assertThat(tribal.abLosses).isEqualTo(2)
        assertThat(tribal.abJudged).isEqualTo(3)
    }

    @Test
    fun `the delta is measured against the rated part of the library`() {
        val insights = PromptInsights.summarise(
            listOf(
                track("tribal drums", rating = 5),
                track("tribal chant", rating = 5),
                track("tribal horn", rating = 5),
                track("quiet pad", rating = 1),
            ),
        )

        // Four rated tracks averaging 4.0; "tribal" sits a full point above that.
        assertThat(insights.overallAverage).isEqualTo(4.0)
        assertThat(insights.terms.single { it.term == "tribal" }.deltaFrom(insights.overallAverage))
            .isEqualTo(1.0)
    }

    @Test
    fun `a library with nothing indexed reports nothing rather than pretending`() {
        val insights = PromptInsights.summarise(listOf(track(prompt = null, rating = 5)))

        assertThat(insights.describedTracks).isEqualTo(0)
        assertThat(insights.overallAverage).isNull()
        assertThat(insights.isEmpty).isTrue()
    }

    private var next = 0

    private fun track(
        prompt: String?,
        rating: Int,
        instruments: String? = null,
        genre: String? = null,
        verdict: String? = null,
    ) = TrackEntity(
        driveId = "t${next++}",
        title = "track",
        relativePath = "Boss/I/track.wav",
        category = "Boss",
        level = "I",
        sizeBytes = null,
        md5Checksum = null,
        addedAt = null,
        modifiedAt = null,
        sourceWavDriveId = null,
        rating = rating,
        prompt = prompt,
        instruments = instruments,
        genre = genre,
        abVerdict = verdict,
    )
}
