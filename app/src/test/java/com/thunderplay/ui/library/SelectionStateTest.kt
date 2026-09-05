package com.thunderplay.ui.library

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.TrackEntity
import com.thunderplay.playback.LocalState
import org.junit.Test

class SelectionStateTest {

    private fun track(id: String) = TrackEntity(
        driveId = id,
        title = "track-$id",
        relativePath = "Boss/I/track-$id.m4a",
        category = "Boss",
        level = "I",
        sizeBytes = 1_500_000,
        md5Checksum = null,
        addedAt = null,
        modifiedAt = null,
        sourceWavDriveId = "wav-$id",
    )

    private fun state(
        visible: List<String> = listOf("a", "b", "c"),
        selection: Set<String> = emptySet(),
        downloaded: Set<String> = emptySet(),
    ) = LibraryUiState(
        tracks = visible.map(::track),
        selection = selection,
        localStates = downloaded.associateWith { LocalState.Downloaded },
        libraryTotal = 185,
    )

    @Test
    fun `an empty selection means the list is not in selection mode`() {
        assertThat(state().selectionMode).isFalse()
        assertThat(state(selection = setOf("a")).selectionMode).isTrue()
    }

    @Test
    fun `the split between downloaded and not is reported accurately`() {
        val ui = state(selection = setOf("a", "b", "c"), downloaded = setOf("a", "c"))

        assertThat(ui.selectedDownloaded).isEqualTo(2)
        assertThat(ui.selectedNotDownloaded).isEqualTo(1)
    }

    @Test
    fun `a selection made under another filter still counts`() {
        // Selections survive filter changes, so tracks that are no longer visible must still be
        // counted - otherwise the toolbar would understate what the actions are about to touch.
        val ui = state(
            visible = listOf("a"),
            selection = setOf("a", "z"),
            downloaded = setOf("z"),
        )
        assertThat(ui.selection).hasSize(2)
        assertThat(ui.selectedDownloaded).isEqualTo(1)
        assertThat(ui.selectedNotDownloaded).isEqualTo(1)
    }

    @Test
    fun `allVisibleSelected is only true once every visible row is ticked`() {
        assertThat(state(selection = setOf("a", "b")).allVisibleSelected).isFalse()
        assertThat(state(selection = setOf("a", "b", "c")).allVisibleSelected).isTrue()
    }

    @Test
    fun `an empty list is never reported as fully selected`() {
        // Otherwise the "select all visible" action would vanish from an empty filter.
        assertThat(state(visible = emptyList()).allVisibleSelected).isFalse()
    }

    @Test
    fun `a superset selection still counts the visible rows as covered`() {
        val ui = state(selection = setOf("a", "b", "c", "elsewhere"))
        assertThat(ui.allVisibleSelected).isTrue()
    }

    @Test
    fun `isSelected reflects membership`() {
        val ui = state(selection = setOf("b"))
        assertThat(ui.isSelected("b")).isTrue()
        assertThat(ui.isSelected("a")).isFalse()
    }

    @Test
    fun `the library total is independent of what is filtered into view`() {
        // "Select all in library" has to advertise the real total, not the filtered count.
        val ui = state(visible = listOf("a"))
        assertThat(ui.tracks).hasSize(1)
        assertThat(ui.libraryTotal).isEqualTo(185)
    }
}
