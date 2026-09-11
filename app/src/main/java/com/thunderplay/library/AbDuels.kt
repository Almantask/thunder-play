package com.thunderplay.library

import com.thunderplay.data.TrackEntity

/**
 * One head-to-head: two takes on screen, one decision.
 *
 * [a] is drawn on the left and [b] on the right, and the card is swiped towards the winner - so
 * which take is on which side is not cosmetic, and both are named rather than indexed.
 */
data class AbDuel(
    /** The take that has held the card so far, or the first take in round one. */
    val a: TrackEntity,
    /** The take challenging it. */
    val b: TrackEntity,
    /** 1-based, so a card can say "Round 2 of 3". */
    val round: Int,
    val rounds: Int,
)

/**
 * Judging one cue as a knockout ladder.
 *
 * A card can only ask about two takes, and a cue can have three or four. A ladder keeps every
 * question binary while asking the fewest of them - n takes, n-1 rounds - and never asks about a
 * pair that has already been ruled on. The take holding the card stays on the left, the next
 * challenger comes up on the right.
 *
 * A round can also end in a draw, which is its own answer rather than a deferral: the challenger is
 * kept alongside the take holding the card, and the ladder carries on. **A draw belongs to the take
 * it was drawn against**, so a leader that is later beaten takes everything it drew with down with
 * it - otherwise the app would be filing B as a keeper on the strength of a comparison with an A it
 * has since agreed was the worse take.
 *
 * The whole ladder is derived from [outcomes], one entry per round decided. That makes undo the
 * removal of the last entry rather than a second, reversed code path, and it lets a half-judged cue
 * be rebuilt from nothing but those ids after the candidate list has been re-queried.
 *
 * Nothing is filed until the last round: losing a round moves no files, so abandoning a cue halfway
 * leaves Drive exactly as it was.
 */
data class AbBracket(
    val group: AbGroup,
    /**
     * What each round decided, oldest first: the Drive id kept, or null for a draw.
     *
     * Only ever grown through [keep] and [draw], which is what lets everything below assume each
     * entry names one of the two takes that were actually on the card that round.
     */
    val outcomes: List<String?> = emptyList(),
) {
    /** Total decisions this cue needs. */
    val rounds: Int get() = group.takes.size - 1

    /** Decisions already made. */
    val decided: Int get() = outcomes.size

    /** The take still holding the card. */
    val leader: TrackEntity get() = standing().leader

    /** The pair to show, or null once the ladder has run out of challengers. */
    val duel: AbDuel?
        get() = group.takes.getOrNull(outcomes.size + 1)
            ?.let { AbDuel(a = leader, b = it, round = outcomes.size + 1, rounds = rounds) }

    /** True once every round has been decided and the cue is ready to file. */
    val finished: Boolean get() = duel == null

    /**
     * Once [finished], the take that beat another - or null when the cue was only ever drawn.
     *
     * A take that has merely held the card through a draw has won nothing, and calling it the
     * winner would put a preference in the record that the user declined to express. Null before
     * the end too: the take holding the card mid-ladder is a leader, not a result.
     */
    val winner: TrackEntity?
        get() {
            if (!finished) return null
            return leader.takeIf { held -> outcomes.any { it == held.driveId } }
        }

    /**
     * Once [finished], the keepers that did not win: every take drawn against the final leader,
     * plus the leader itself when it never won a round. Empty before the end.
     */
    val tied: List<TrackEntity>
        get() {
            if (!finished) return emptyList()
            val standing = standing()
            return if (winner == null) listOf(standing.leader) + standing.drawn else standing.drawn
        }

    /**
     * The ladder after keeping [driveId] this round, or null when that id is not on the card.
     *
     * Null rather than an exception because the caller is a gesture: a swipe can land after the
     * candidate list has moved underneath it, and that is a stale tap, not a broken invariant.
     */
    fun keep(driveId: String): AbBracket? {
        val duel = duel ?: return null
        if (driveId != duel.a.driveId && driveId != duel.b.driveId) return null
        return copy(outcomes = outcomes + driveId)
    }

    /** The ladder after calling this round a draw, keeping both takes. */
    fun draw(): AbBracket? {
        duel ?: return null
        return copy(outcomes = outcomes + null)
    }

    /** Takes back the last decision. Nothing is filed until the last round, so this is always safe. */
    fun undo(): AbBracket = if (outcomes.isEmpty()) this else copy(outcomes = outcomes.dropLast(1))

    private data class Standing(val leader: TrackEntity, val drawn: List<TrackEntity>)

    /**
     * Replays the rounds to find who is holding the card and what is being kept beside it.
     *
     * Recomputed on each read rather than stored: a cue is four takes at most, and one derivation
     * that cannot disagree with itself is worth more than three cached fields that can.
     */
    private fun standing(): Standing {
        var leader = group.takes.first()
        val drawn = mutableListOf<TrackEntity>()
        outcomes.forEachIndexed { index, outcome ->
            val challenger = group.takes[index + 1]
            when (outcome) {
                null -> drawn += challenger
                challenger.driveId -> {
                    // A new take holds the card, and the draws that belonged to the old one go
                    // with it: they were only ever "as good as that", and that has just lost.
                    leader = challenger
                    drawn.clear()
                }
                // The leader held; the challenger is beaten.
                else -> Unit
            }
        }
        return Standing(leader, drawn)
    }

    companion object {
        /**
         * Rebuilds a ladder from remembered [outcomes], keeping only the prefix that still holds.
         *
         * A refresh can change a cue's takes while it is being judged - another render lands, or
         * one is trashed from the library screen. Replaying the outcomes against the new group is
         * what turns that into "carry on from where the two agree" rather than a crash.
         */
        fun resume(group: AbGroup, outcomes: List<String?>): AbBracket {
            var bracket = AbBracket(group)
            for (outcome in outcomes) {
                bracket = (if (outcome == null) bracket.draw() else bracket.keep(outcome)) ?: break
            }
            return bracket
        }
    }
}

/** Which cue to judge next. */
object AbDeck {

    /**
     * The judging order: cues put off for later drop to the back rather than out.
     *
     * Skipping usually means "not now" - the ear is not fresh, or the two takes need a proper
     * listen - so a skipped cue has to stay reachable. Dropping it out of the deck instead would
     * make the count of what is left a lie, and the only way back would be to restart the app.
     *
     * [retired] is filtered out entirely: those cues are being filed or have been. Room drops them
     * a moment later when the verdict lands, and this covers the gap - without it the deck would
     * hand back the cue that was just judged and invite a second swipe on files already moved.
     */
    fun order(
        candidates: List<AbGroup>,
        skipped: List<String>,
        retired: Set<String>,
    ): List<AbGroup> {
        val live = candidates.filter { it.key !in retired }
        if (skipped.isEmpty()) return live
        val (putOff, fresh) = live.partition { it.key in skipped }
        // Back of the deck in the order they were skipped, so the first one put off comes back first.
        return fresh + putOff.sortedBy { skipped.indexOf(it.key) }
    }
}
