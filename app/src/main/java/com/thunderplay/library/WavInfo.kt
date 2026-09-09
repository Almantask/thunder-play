package com.thunderplay.library

/**
 * The generator's metadata, read out of a WAV's RIFF `LIST`/`INFO` chunk.
 *
 * This is the only place the real prompt exists. The filename carries a lowercased slug truncated
 * at 48 characters, and the transcoded .m4a carries nothing at all - so a track's prompt can only
 * be recovered from its source WAV, and only while `sourceWavDriveId` still points at one.
 *
 * Thunder FX inserts the chunk immediately after `fmt `, which puts it a few hundred bytes into the
 * file. [HEAD_BYTES] is therefore ample, and the whole point of parsing a head rather than a file:
 * a 4 KB range request beats downloading 40 MB to read one sentence.
 */
data class WavInfo(
    /** INAM - the prompt, capped by the generator at 120 characters. */
    val prompt: String? = null,
    /** IGNR - Instrumental, Ambience or Sound Effects. */
    val genre: String? = null,
    /** ISBJ */
    val category: String? = null,
    /** IART */
    val intensity: String? = null,
    /** ISFT - "Thunder FX" for anything this library generated. */
    val software: String? = null,
    /** IKEY, split on the semicolons the generator joins with. */
    val instruments: List<String> = emptyList(),
    /** ICMT */
    val comment: String? = null,
    /**
     * Playing time, computed from the header rather than read from a tag.
     *
     * WAV has no duration field: what it has is a byte rate and a count of sample bytes, and one
     * divided by the other is exact for the uncompressed PCM this library is made of. Both live in
     * the same few hundred bytes as the prompt, so this costs nothing on top of reading that.
     */
    val durationMs: Long? = null,
) {
    /**
     * Whether the generator wrote anything about the track.
     *
     * Deliberately about the prose fields only. [durationMs] comes from the format header, which
     * every WAV has, so counting it would make this true for a file that says nothing at all.
     */
    val hasAny: Boolean
        get() = prompt != null || genre != null || category != null || intensity != null ||
            software != null || instruments.isNotEmpty() || comment != null

    companion object {
        /** Enough to clear `fmt ` and the INFO chunk that follows it, with room to spare. */
        const val HEAD_BYTES = 4096

        /**
         * Reads the INFO fields from the first bytes of a WAV, or null if there are none.
         *
         * Never throws. A truncated, malformed or entirely unrelated file degrades to "no prompt";
         * failing here must not be able to fail a judgement.
         */
        fun parse(head: ByteArray): WavInfo? {
            if (head.size < 12) return null
            if (head.ascii(0) != "RIFF" || head.ascii(8) != "WAVE") return null

            var info: WavInfo? = null
            var byteRate = 0L
            var sampleBytes: Long? = null

            var offset = 12
            while (offset + 8 <= head.size) {
                val id = head.ascii(offset)
                val size = head.leU32(offset + 4)
                val body = offset + 8

                when {
                    // The byte rate sits 8 bytes into the fmt body and already folds in the
                    // channel count, sample rate and bit depth, so it is the only field the
                    // duration needs.
                    id == "fmt " && body + 12 <= head.size -> byteRate = head.leU32(body + 8)

                    id == "LIST" && body + 4 <= head.size && head.ascii(body) == "INFO" -> {
                        val end = minOf(body + size, head.size.toLong()).toInt()
                        info = readInfo(head, body + 4, end)
                    }
                }

                // The samples are far past a 4 KB read, but the header declaring how many there
                // are is not - and it is the last thing worth looking at, since the generator
                // writes INFO before it and scanning on would only walk a chunk we do not have.
                if (id == "data") {
                    sampleBytes = size
                    break
                }

                // Chunks pad to an even length. Getting this wrong desynchronises every chunk that
                // follows - the classic RIFF bug.
                val next = body + size + (size and 1L)
                // Sizes are unsigned, so garbage past a truncation point can be enormous. Refusing
                // to move forwards is what stops that becoming an infinite loop. Whatever was read
                // before that point still stands.
                if (next <= offset || next > head.size) break
                offset = next.toInt()
            }

            val durationMs = sampleBytes?.takeIf { byteRate > 0 }?.let { it * 1000 / byteRate }
            // A file that yielded neither prose nor a duration is reported as having nothing,
            // which is what tells the indexer there was no point in the read.
            if (info == null && durationMs == null) return null
            return (info ?: WavInfo()).copy(durationMs = durationMs)
        }

        private fun readInfo(head: ByteArray, start: Int, end: Int): WavInfo {
            val fields = mutableMapOf<String, String>()
            var cursor = start
            while (cursor + 8 <= end) {
                val tag = head.ascii(cursor)
                val size = head.leU32(cursor + 4)
                val payload = cursor + 8
                // A 4 KB read can stop mid-subchunk. Half a payload is worse than none, so keep
                // whatever was already parsed and stop.
                if (payload + size > end) break

                val text = head.text(payload, (payload + size).toInt())
                if (text.isNotEmpty()) fields[tag] = text

                val next = payload + size + (size and 1L)
                if (next <= cursor) break
                cursor = next.toInt()
            }

            return WavInfo(
                prompt = fields["INAM"],
                genre = fields["IGNR"],
                category = fields["ISBJ"],
                intensity = fields["IART"],
                software = fields["ISFT"],
                instruments = fields["IKEY"]
                    ?.split(';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                comment = fields["ICMT"],
            )
        }

        private fun ByteArray.ascii(at: Int): String = String(this, at, 4, Charsets.US_ASCII)

        private fun ByteArray.leU32(at: Int): Long =
            (this[at].toLong() and 0xFF) or
                ((this[at + 1].toLong() and 0xFF) shl 8) or
                ((this[at + 2].toLong() and 0xFF) shl 16) or
                ((this[at + 3].toLong() and 0xFF) shl 24)

        /**
         * Payload up to the first NUL. Malformed UTF-8 needs no handling - Kotlin substitutes
         * U+FFFD rather than throwing, so the generator's latin-1 fallback has no equivalent here
         * and none is wanted.
         */
        private fun ByteArray.text(from: Int, until: Int): String {
            val nul = (from until until).firstOrNull { this[it] == 0.toByte() } ?: until
            return String(this, from, nul - from, Charsets.UTF_8).trim()
        }
    }
}
