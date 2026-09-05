package com.thunderplay.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackDescriptorsTest {

    @Test
    fun `parses a real generated name`() {
        val d = TrackDescriptors.parse("instrumental-ancient-ambient-awe-and-stillness-d-39.5s-670a6ef0")

        assertThat(d.styles).containsExactly("instrumental").inOrder()
        assertThat(d.phrase).isEqualTo("ancient ambient awe and stillness d")
        assertThat(d.encodedDurationMs).isEqualTo(39_500)
        assertThat(d.fingerprint).isEqualTo("670a6ef0")
    }

    @Test
    fun `takes several leading style words`() {
        val d = TrackDescriptors.parse("epic-orchestral-discovery-awe-struck-and-monumen-40s-0933b4b2")

        assertThat(d.styles).containsExactly("epic", "orchestral").inOrder()
        assertThat(d.phrase).isEqualTo("discovery awe struck and monumen")
        assertThat(d.encodedDurationMs).isEqualTo(40_000)
    }

    @Test
    fun `a style word mid-phrase stays description`() {
        // "pulled strings" is a phrase, not an instrument list; only leading words are styles.
        val d = TrackDescriptors.parse("ambient-tension-pulled-strings-20s-aabbccdd")

        assertThat(d.styles).containsExactly("ambient")
        assertThat(d.phrase).isEqualTo("tension pulled strings")
    }

    @Test
    fun `an integer duration parses as well as a fractional one`() {
        assertThat(TrackDescriptors.parse("ambient-x-110s-8f929976").encodedDurationMs)
            .isEqualTo(110_000)
        assertThat(TrackDescriptors.parse("ambient-x-7.25s-8f929976").encodedDurationMs)
            .isEqualTo(7_250)
    }

    @Test
    fun `a name that follows no convention still yields the words`() {
        val d = TrackDescriptors.parse("My Favourite Track")

        assertThat(d.styles).isEmpty()
        assertThat(d.phrase).isEqualTo("My Favourite Track")
        assertThat(d.fingerprint).isNull()
        assertThat(d.encodedDurationMs).isNull()
    }

    @Test
    fun `only an eight character hex tail counts as a fingerprint`() {
        assertThat(TrackDescriptors.parse("ambient-thing-670a6ef0").fingerprint)
            .isEqualTo("670a6ef0")
        // Too short, and "beef" is a plausible word - not an identifier.
        assertThat(TrackDescriptors.parse("ambient-thing-beef").fingerprint).isNull()
        assertThat(TrackDescriptors.parse("ambient-thing-670a6ef0z").fingerprint).isNull()
    }

    @Test
    fun `an empty name yields nothing rather than throwing`() {
        val d = TrackDescriptors.parse("")
        assertThat(d.hasAny).isFalse()
        assertThat(d.styles).isEmpty()
        assertThat(d.phrase).isEmpty()
    }

    @Test
    fun `a name that is only styles leaves an empty phrase`() {
        val d = TrackDescriptors.parse("instrumental-ambient-30s-11223344")

        assertThat(d.styles).containsExactly("instrumental", "ambient").inOrder()
        assertThat(d.phrase).isEmpty()
        assertThat(d.hasAny).isTrue()
    }

    @Test
    fun `instrument words are recognised, which is the closest thing to instrumentation data`() {
        val d = TrackDescriptors.parse("piano-strings-choral-lament-60s-deadbeef")
        assertThat(d.styles).containsExactly("piano", "strings", "choral").inOrder()
    }
}
