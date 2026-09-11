package com.thunderplay.library

import com.thunderplay.data.TrackEntity

/** Where a term came from, since a prompt word and a named instrument are not the same evidence. */
enum class TermKind(val label: String) {
    Word("Prompt word"),
    Instrument("Instrument"),
    Genre("Genre"),
}

/** How the tracks described by one term have actually fared. */
data class TermStat(
    val term: String,
    val kind: TermKind,
    /** Tracks the term appears in, rated or not. */
    val tracks: Int,
    val ratedTracks: Int,
    /** Mean of the ratings that exist, or null when none of the tracks have been rated. */
    val averageRating: Double?,
    val totalPlays: Int,
    val abWins: Int,
    val abLosses: Int,
) {
    val abJudged: Int get() = abWins + abLosses

    /** How far above or below the library's own average this term sits. */
    fun deltaFrom(overall: Double?): Double? =
        if (averageRating == null || overall == null) null else averageRating - overall
}

/**
 * What the ratings say about the prompts that produced them.
 *
 * The library is generated, so every track has a prompt behind it and the interesting question is
 * not which track is good but which *words* are - "tribal percussive averages 4.1, ambient drone
 * averages 1.8" is a thing to do differently next time, which no per-track rating can tell you.
 *
 * Two decisions shape the numbers:
 *
 * 1. Unrated tracks are not counted as zero. Zero stars means "not judged yet" everywhere else in
 *    the app, and averaging it in would drag every common word towards the bottom in proportion to
 *    how much of the library is still unheard.
 * 2. A term needs [MIN_RATED] rated tracks before it is ranked at all. With one rated track behind
 *    it, every word in that track's prompt would tie for first place.
 */
object PromptInsights {

    /** Below this a term's average says more about the sample than about the term. */
    const val MIN_RATED = 3

    fun summarise(tracks: List<TrackEntity>, minRated: Int = MIN_RATED): Insights {
        val described = tracks.filter { it.prompt != null || it.instruments != null }
        val rated = described.filter { it.rating > 0 }

        val accumulators = mutableMapOf<Pair<String, TermKind>, Accumulator>()
        described.forEach { track ->
            termsOf(track).forEach { (term, kind) ->
                accumulators.getOrPut(term to kind) { Accumulator(term, kind) }.add(track)
            }
        }

        val overall = rated.map { it.rating }.averageOrNull()
        val ranked = accumulators.values
            .map(Accumulator::toStat)
            .filter { it.ratedTracks >= minRated && it.averageRating != null }
            .sortedWith(
                compareByDescending<TermStat> { it.averageRating }
                    .thenByDescending { it.ratedTracks }
                    .thenBy { it.term },
            )

        return Insights(
            describedTracks = described.size,
            ratedTracks = rated.size,
            overallAverage = overall,
            terms = ranked,
        )
    }

    /**
     * Every term one track contributes, de-duplicated.
     *
     * A word repeated in a prompt is still one piece of evidence about that track, so "dark, dark
     * strings" must not count twice.
     */
    private fun termsOf(track: TrackEntity): List<Pair<String, TermKind>> {
        val words = track.prompt.orEmpty()
            .split(*SEPARATORS)
            .map { it.trim().lowercase() }
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
            .distinct()
            .map { it to TermKind.Word }

        val instruments = track.instrumentList
            .map { it.lowercase() }
            .distinct()
            .map { it to TermKind.Instrument }

        val genre = listOfNotNull(track.genre?.lowercase()?.let { it to TermKind.Genre })

        return words + instruments + genre
    }

    private class Accumulator(val term: String, val kind: TermKind) {
        private var tracks = 0
        private var plays = 0
        private var wins = 0
        private var losses = 0
        private val ratings = mutableListOf<Int>()

        fun add(track: TrackEntity) {
            tracks++
            plays += track.playCount
            if (track.rating > 0) ratings += track.rating
            when (AbVerdict.from(track.abVerdict)) {
                AbVerdict.Good -> wins++
                AbVerdict.Bad -> losses++
                // A draw is a statement that the difference did not matter, so it is evidence for
                // neither side. Counting it as a win would credit every word the two prompts share.
                AbVerdict.Tie -> Unit
                null -> Unit
            }
        }

        fun toStat() = TermStat(
            term = term,
            kind = kind,
            tracks = tracks,
            ratedTracks = ratings.size,
            averageRating = ratings.averageOrNull(),
            totalPlays = plays,
            abWins = wins,
            abLosses = losses,
        )
    }

    private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private val SEPARATORS = charArrayOf(' ', ',', '.', ';', ':', '/', '-', '(', ')', '\n', '\t')

    private const val MIN_WORD_LENGTH = 3

    /**
     * Words that describe every track equally, so they can only ever be noise.
     *
     * Deliberately a fixed list, like [TrackDescriptors.STYLE_WORDS]: an unrecognised word should
     * show up as evidence rather than be silently discarded, so this holds only grammar and the
     * generator's own boilerplate.
     */
    private val STOP_WORDS = setOf(
        "and", "the", "with", "for", "from", "that", "this", "into", "over", "under",
        "very", "more", "than", "then", "its", "his", "her", "their", "are", "was",
        "tracktype", "type", "music", "track", "sound", "audio", "effect", "effects",
        "style", "mood", "scene", "seconds", "second", "loop", "loopable",
    )
}

data class Insights(
    /** Tracks with a prompt or an instrument list; the rest are simply not indexed yet. */
    val describedTracks: Int,
    val ratedTracks: Int,
    val overallAverage: Double?,
    /** Terms that cleared the sample threshold, best average first. */
    val terms: List<TermStat>,
) {
    val isEmpty: Boolean get() = terms.isEmpty()

    fun best(limit: Int, kind: TermKind? = null): List<TermStat> =
        terms.filter { kind == null || it.kind == kind }.take(limit)

    fun worst(limit: Int, kind: TermKind? = null): List<TermStat> =
        terms.filter { kind == null || it.kind == kind }.takeLast(limit).reversed()
}
