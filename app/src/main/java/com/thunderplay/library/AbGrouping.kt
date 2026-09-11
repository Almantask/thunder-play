package com.thunderplay.library

import com.thunderplay.data.TrackEntity

/** Which side of an A/B judgement a take landed on. Stored as the string, mapped here. */
enum class AbVerdict(val stored: String) {
    Good("good"),
    Bad("bad"),

    /**
     * Kept, but not because it beat anything: nothing separated it from the take it was up against.
     *
     * A separate verdict rather than a second [Good] because the two are different claims. "This
     * one won" is evidence about a prompt; "these two are indistinguishable" is evidence that the
     * difference between them does not matter, and counting it as a win would put a thumb on the
     * scale of every word both prompts share.
     */
    Tie("tie"),
    ;

    companion object {
        fun from(stored: String?): AbVerdict? = entries.firstOrNull { it.stored == stored }
    }
}

/** The rival takes of one cue, competing for a single keeper. */
data class AbGroup(
    val key: String,
    val slug: String,
    /** The raw duration token from the filename, e.g. "120s" or "39.5s". */
    val durationLabel: String,
    val category: String,
    val level: String?,
    val takes: List<TrackEntity>,
)

/**
 * Groups the rival takes of one cue so they can be judged against each other.
 *
 * The generator writes `{prompt slug}-{duration}s-{8 hex}.wav`, where the hex is a fresh
 * `uuid4().hex[:8]` per render rather than a content hash. So two files agreeing on everything but
 * that suffix are two takes of the same cue, and the suffix is the only thing distinguishing them
 * on screen.
 *
 * This deliberately does not go through [TrackDescriptors]. That parser lowercases, lifts leading
 * style words out into their own list, rejoins the remainder with spaces and turns "39.5s" into
 * 39500L - all useful for describing a track, all lossy for reconstructing the slug. The raw title
 * is parsed instead, because the generator's format is exact.
 *
 * **Known limit:** the generator truncates the slug at 48 characters, and real names are visibly
 * cut mid-word ("instrumental-concept-study-ambient-expansive-and" is 47). Two different prompts
 * sharing their first 48 slug characters and a duration will group as rivals. Nothing here can
 * detect that - the caller must show each take's real prompt, read from the source WAV, and let a
 * human notice. Never judge a group automatically.
 */
object AbGrouping {

    private val FINGERPRINT = Regex("^[0-9a-f]{8}$")
    private val DURATION = Regex("^[0-9]+(?:\\.[0-9]+)?s$")

    /** The grouping key, or null when the name is not generator-shaped and so never a candidate. */
    fun keyOf(track: TrackEntity): String? = parse(track)?.key

    /** Every cue with two or more takes, ordered for display. */
    fun candidates(tracks: List<TrackEntity>): List<AbGroup> =
        tracks.mapNotNull { parse(it) }
            .groupBy { it.key }
            .values
            .filter { it.size >= 2 }
            .map { members ->
                val first = members.first()
                AbGroup(
                    key = first.key,
                    slug = first.slug,
                    durationLabel = first.durationLabel,
                    category = first.track.category,
                    level = first.track.level,
                    // Stable order, so the take labelled "1" stays "1" across recompositions.
                    takes = members.map { it.track }.sortedBy { it.title },
                )
            }
            .sortedWith(compareBy({ it.category }, { it.level.orEmpty() }, { it.slug }))

    /**
     * Winner keeps the cue, drawn takes are kept beside it, everything else is an also-ran.
     *
     * [winnerDriveId] is null for a cue that was only ever drawn - nothing beat anything, so
     * naming a winner would be inventing one. A cue with neither a winner nor a draw is rejected:
     * that would file every take as bad, which no judgement ever means.
     *
     * The pure core of a judgement.
     */
    fun verdicts(
        group: AbGroup,
        winnerDriveId: String?,
        tiedDriveIds: Set<String> = emptySet(),
    ): Map<String, AbVerdict> {
        val ids = group.takes.mapTo(mutableSetOf(), TrackEntity::driveId)
        require(winnerDriveId == null || winnerDriveId in ids) {
            "Winner $winnerDriveId is not one of the takes in group ${group.key}"
        }
        require(tiedDriveIds.all { it in ids }) {
            "Drawn takes ${tiedDriveIds - ids} are not in group ${group.key}"
        }
        require(winnerDriveId != null || tiedDriveIds.isNotEmpty()) {
            "Group ${group.key} has no keeper, so there is nothing to file"
        }
        return group.takes.associate { take ->
            take.driveId to when (take.driveId) {
                winnerDriveId -> AbVerdict.Good
                in tiedDriveIds -> AbVerdict.Tie
                else -> AbVerdict.Bad
            }
        }
    }

    private data class Parsed(
        val key: String,
        val slug: String,
        val durationLabel: String,
        val track: TrackEntity,
    )

    private fun parse(track: TrackEntity): Parsed? {
        val tokens = track.title.split('-')
        // slug + duration + fingerprint, and the slug is never empty.
        if (tokens.size < 3) return null
        if (!FINGERPRINT.matches(tokens.last())) return null

        val durationLabel = tokens[tokens.size - 2]
        if (!DURATION.matches(durationLabel)) return null

        val slug = tokens.subList(0, tokens.size - 2).joinToString("-")
        if (slug.isEmpty()) return null

        // Category and level are part of the key: the same slug generated into another folder is a
        // different cue, not a rival take. The duration stays a raw string - the generator formats
        // it with %g, so "39.5s" and "40s" are already canonical and parsing to millis would only
        // reintroduce a rounding question.
        return Parsed(
            key = "${track.category}/${track.level.orEmpty()}|$slug|$durationLabel",
            slug = slug,
            durationLabel = durationLabel,
            track = track,
        )
    }
}
