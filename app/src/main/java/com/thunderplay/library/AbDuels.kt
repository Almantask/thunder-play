package com.thunderplay.library

import com.thunderplay.data.TrackEntity

/**
 * One head-to-head: two takes on screen, one swipe.
 *
 * [a] is drawn on the left and [b] on the right, and the card is swiped towards the winner - so
 * which take is on which side is not cosmetic, and both are named rather than indexed.
 */
data class AbDuel(
    /** The take that has won every round so far, or the first take in round one. */
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
 * A swipe can only answer a question about two takes, and a cue can have three or four. A ladder
 * keeps every question binary while asking the fewest of them - n takes, n-1 swipes - and never
 * asks about a pair that has already been ruled on. The take kept so far stays on the left, the
 * next challenger comes up on the right.
 *
 * The whole ladder is derived from [picks], the ids kept in each round so far. That makes undo the
 * removal of the last pick rather than a second, reversed code path, and it lets a half-judged cue
 * be rebuilt from nothing but the ids after the candidate list has been re-queried.
 *
 * Nothing is filed until [winner] is non-null: losing a round moves no files, so abandoning a cue
 * halfway leaves Drive exactly as it was.
 */
data class AbBracket(
    val group: AbGroup,
    /** Drive ids kept, oldest round first. Only ever grown through [keep]. */
    val picks: List<String> = emptyList(),
) {
    /** Total swipes this cue needs. */
    val rounds: Int get() = group.takes.size - 1

    /** Swipes already made. */
    val decided: Int get() = picks.size

    /** The take still standing. */
    val leader: TrackEntity
        get() = picks.lastOrNull()
            ?.let { id -> group.takes.first { it.driveId == id } }
            ?: group.takes.first()

    /** The pair to show, or null once the ladder has run out of challengers. */
    val duel: AbDuel?
        get() = group.takes.getOrNull(picks.size + 1)
            ?.let { AbDuel(a = leader, b = it, round = picks.size + 1, rounds = rounds) }

    /** The cue's keeper, once every round has been decided. */
    val winner: TrackEntity? get() = if (duel == null) leader else null

    /**
     * The ladder after keeping [driveId] this round, or null when that id is not on the card.
     *
     * Null rather than an exception because the caller is a gesture: a swipe can land after the
     * candidate list has moved underneath it, and that is a stale tap, not a broken invariant.
     */
    fun keep(driveId: String): AbBracket? {
        val duel = duel ?: return null
        if (driveId != duel.a.driveId && driveId != duel.b.driveId) return null
        return copy(picks = picks + driveId)
    }

    /** Takes back the last swipe. Nothing has been filed yet, so this is always safe. */
    fun undo(): AbBracket = if (picks.isEmpty()) this else copy(picks = picks.dropLast(1))

    companion object {
        /**
         * Rebuilds a ladder from remembered [picks], keeping only the prefix that still holds.
         *
         * A refresh can change a cue's takes while it is being judged - another render lands, or
         * one is trashed from the library screen. Replaying the picks against the new group is
         * what turns that into "carry on from where the two agree" rather than a crash.
         */
        fun resume(group: AbGroup, picks: List<String>): AbBracket {
            var bracket = AbBracket(group)
            for (id in picks) bracket = bracket.keep(id) ?: break
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
