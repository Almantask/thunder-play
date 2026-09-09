package com.thunderplay.library

import com.google.common.truth.Truth.assertThat
import com.thunderplay.data.TrackEntity
import org.junit.Test

class LibraryViewTest {

    private fun track(
        id: String,
        title: String,
        rating: Int = 0,
        playCount: Int = 0,
        addedAt: Long? = null,
        category: String = "Beast Hunt",
    ) = TrackEntity(
        driveId = id,
        title = title,
        relativePath = "$category/I/$title.m4a",
        category = category,
        level = "I",
        sizeBytes = 1_500_000,
        md5Checksum = null,
        addedAt = addedAt,
        modifiedAt = null,
        sourceWavDriveId = "wav-$id",
        rating = rating,
        playCount = playCount,
    )

    private val fixture = listOf(
        track("1", "charlie", rating = 5, playCount = 2, addedAt = 300),
        track("2", "alpha", rating = 0, playCount = 9, addedAt = 100),
        track("3", "Bravo", rating = 3, playCount = 9, addedAt = 200),
    )

    private fun order(o: LibraryView.Order) =
        fixture.ordered(LibraryView(order = o)).map { it.title }

    @Test
    fun `name order is case-insensitive`() {
        assertThat(order(LibraryView.Order.Name))
            .containsExactly("alpha", "Bravo", "charlie").inOrder()
    }

    @Test
    fun `most played sorts descending and breaks ties by name`() {
        // alpha and Bravo both have 9 plays; neither has a lastPlayedAt, so name decides.
        assertThat(order(LibraryView.Order.MostPlayed))
            .containsExactly("alpha", "Bravo", "charlie").inOrder()
    }

    @Test
    fun `highest rated puts unrated last`() {
        assertThat(order(LibraryView.Order.HighestRated))
            .containsExactly("charlie", "Bravo", "alpha").inOrder()
    }

    @Test
    fun `recently added sorts newest first and tolerates a missing timestamp`() {
        val withUnknown = fixture + track("4", "delta", addedAt = null)
        val sorted = withUnknown.ordered(LibraryView(order = LibraryView.Order.RecentlyAdded))
        assertThat(sorted.map { it.title })
            .containsExactly("charlie", "Bravo", "alpha", "delta").inOrder()
    }

    @Test
    fun `the same seed always yields the same shuffle`() {
        val a = fixture.ordered(LibraryView(order = LibraryView.Order.Random, shuffleSeed = 42))
        val b = fixture.shuffled().ordered(
            LibraryView(order = LibraryView.Order.Random, shuffleSeed = 42),
        )
        // Stable regardless of the order rows arrived in, so recomposition cannot reshuffle.
        assertThat(a.map { it.driveId }).isEqualTo(b.map { it.driveId })
    }

    @Test
    fun `a different seed generally yields a different shuffle`() {
        val many = (1..40).map { track("id$it", "t$it") }
        val a = many.ordered(LibraryView(order = LibraryView.Order.Random, shuffleSeed = 1))
        val b = many.ordered(LibraryView(order = LibraryView.Order.Random, shuffleSeed = 2))
        assertThat(a.map { it.driveId }).isNotEqualTo(b.map { it.driveId })
        assertThat(a.map { it.driveId }).containsExactlyElementsIn(b.map { it.driveId })
    }

    @Test
    fun `an untouched view restricts nothing`() {
        val view = LibraryView()
        assertThat(view.isFiltered).isFalse()
        assertThat(view.stars.minimum).isNull()
        assertThat(view.category).isNull()
        assertThat(view.level).isNull()
    }

    @Test
    fun `the three filters are independent`() {
        val view = LibraryView(
            stars = LibraryView.Stars.Three,
            category = "Beast Hunt",
            level = "III",
        )
        assertThat(view.stars.minimum).isEqualTo(3)
        assertThat(view.category).isEqualTo("Beast Hunt")
        assertThat(view.level).isEqualTo("III")
        assertThat(view.isFiltered).isTrue()
    }

    @Test
    fun `zero stars asks for unrated rather than for everything`() {
        // The query reads a minimum of 0 as "exactly none"; Any is what means "no restriction".
        assertThat(LibraryView.Stars.Unrated.minimum).isEqualTo(0)
        assertThat(LibraryView.Stars.Any.minimum).isNull()
    }

    @Test
    fun `each star step is its own minimum`() {
        assertThat(LibraryView.Stars.entries.mapNotNull { it.minimum })
            .containsExactly(0, 1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun `clearing drops the filters but keeps the sort and the search`() {
        val view = LibraryView(
            stars = LibraryView.Stars.Five,
            category = "Beast Hunt",
            level = "I",
            order = LibraryView.Order.RecentlyAdded,
            query = "storm",
        ).cleared()

        assertThat(view.isFiltered).isFalse()
        assertThat(view.order).isEqualTo(LibraryView.Order.RecentlyAdded)
        assertThat(view.query).isEqualTo("storm")
    }

    @Test
    fun `the search box alone does not count as a filter`() {
        // Clear only resets the dropdowns, so a typed query must not keep the chip on screen.
        assertThat(LibraryView(query = "storm").isFiltered).isFalse()
    }

    @Test
    fun `liked means rated at least one star`() {
        assertThat(track("x", "x", rating = 0).isLiked).isFalse()
        assertThat(track("x", "x", rating = 1).isLiked).isTrue()
        assertThat(track("x", "x", rating = 5).isLiked).isTrue()
    }
}
