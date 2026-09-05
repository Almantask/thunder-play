package com.thunderplay.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackPathTest {

    @Test
    fun `parses category, level and title`() {
        val path = TrackPath.parse("Beast Hunt/III/instrumental-hunt-tracking-90s-1660a792.m4a")
        assertThat(path.category).isEqualTo("Beast Hunt")
        assertThat(path.level).isEqualTo("III")
        assertThat(path.title).isEqualTo("instrumental-hunt-tracking-90s-1660a792")
    }

    @Test
    fun `keeps the librarys mis-encoded folder names byte-for-byte`() {
        // The real library has folders whose names contain U+00E2 U+20AC U+201D where an em dash
        // was intended. "Repairing" them here would stop paths matching Drive, so they must pass
        // through untouched.
        val mojibake = "Level I \u00E2\u20AC\u201D Quiet looping bed"
        val path = TrackPath.parse("Ancient Discovery/$mojibake/track.m4a")

        assertThat(path.category).isEqualTo("Ancient Discovery")
        assertThat(path.level).isEqualTo(mojibake)
        assertThat(path.level).doesNotContain("\u2014")
    }

    @Test
    fun `a file directly under the root has no level`() {
        val path = TrackPath.parse("Custom/loose-track.m4a")
        assertThat(path.category).isEqualTo("Custom")
        assertThat(path.level).isNull()
        assertThat(path.title).isEqualTo("loose-track")
    }

    @Test
    fun `deeper nesting collapses into the level`() {
        val path = TrackPath.parse("Custom/Demo/Take 2/track.m4a")
        assertThat(path.level).isEqualTo("Demo/Take 2")
    }

    @Test
    fun `a bare filename falls back to uncategorised`() {
        val path = TrackPath.parse("stray.m4a")
        assertThat(path.category).isEqualTo(TrackPath.UNCATEGORISED)
        assertThat(path.level).isNull()
        assertThat(path.title).isEqualTo("stray")
    }

    @Test
    fun `a dot in the track name is not mistaken for the extension`() {
        val path = TrackPath.parse("Ancient Discovery/Level I/ambient-39.5s-670a6ef0.m4a")
        assertThat(path.title).isEqualTo("ambient-39.5s-670a6ef0")
    }
}
