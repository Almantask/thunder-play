package com.thunderplay.library

/**
 * Everything the filename itself can tell us about a track.
 *
 * The audio carries no tags at all - WAV has nowhere to put them and the transcode adds none - so
 * the generator's naming convention is the only description that exists. Names look like:
 *
 *     instrumental-ancient-ambient-awe-and-stillness-d-39.5s-670a6ef0
 *     epic-orchestral-discovery-awe-struck-and-monumen-40s-0933b4b2
 *
 * which is `<style words>-<scene and mood>-<duration>s-<fingerprint>`.
 */
data class TrackDescriptors(
    /** Recognised style and instrumentation words, in the order they appear. */
    val styles: List<String> = emptyList(),
    /** Whatever is left once styles, duration and fingerprint are removed. */
    val phrase: String = "",
    /** Duration encoded in the name. Not authoritative - the decoder's figure wins. */
    val encodedDurationMs: Long? = null,
    /** The generator's 8-character hex suffix; the closest thing to a stable identity. */
    val fingerprint: String? = null,
) {
    val hasAny: Boolean
        get() = styles.isNotEmpty() || phrase.isNotEmpty() || fingerprint != null

    companion object {
        /**
         * Words treated as style or instrumentation rather than description.
         *
         * Deliberately a fixed list: anything unrecognised belongs in [phrase], so a new word from
         * the generator shows up as description instead of being silently dropped.
         */
        val STYLE_WORDS = setOf(
            "instrumental", "orchestral", "cinematic", "epic", "ambient", "atmospheric",
            "acoustic", "electronic", "synth", "synthetic", "choral", "vocal", "percussive",
            "piano", "strings", "brass", "woodwind", "guitar", "drums", "drone", "pad",
            "folk", "jazz", "blues", "rock", "metal", "orchestra", "chiptune", "lofi",
            "hybrid", "trailer", "minimal", "industrial", "tribal", "medieval", "baroque",
        )

        private val FINGERPRINT = Regex("^[0-9a-f]{8}$")
        private val DURATION = Regex("^([0-9]+(?:\\.[0-9]+)?)s$")

        fun parse(title: String): TrackDescriptors {
            val tokens = title.split('-').filter { it.isNotBlank() }.toMutableList()
            if (tokens.isEmpty()) return TrackDescriptors()

            val fingerprint = tokens.lastOrNull()
                ?.takeIf { FINGERPRINT.matches(it) }
                ?.also { tokens.removeAt(tokens.lastIndex) }

            val durationMs = tokens.lastOrNull()
                ?.let { DURATION.find(it)?.groupValues?.get(1) }
                ?.toDoubleOrNull()
                ?.let { (it * 1000).toLong() }
                ?.also { tokens.removeAt(tokens.lastIndex) }

            // Styles are only taken from the front. A word like "strings" in the middle of a
            // phrase ("pulled strings") is description, not instrumentation.
            val styles = mutableListOf<String>()
            while (tokens.isNotEmpty() && tokens.first().lowercase() in STYLE_WORDS) {
                styles += tokens.removeAt(0).lowercase()
            }

            return TrackDescriptors(
                styles = styles,
                phrase = tokens.joinToString(" ").trim(),
                encodedDurationMs = durationMs,
                fingerprint = fingerprint,
            )
        }
    }
}
