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
    fun `liked scope implies the liked filter`() {
        val view = LibraryView().withScope(LibraryView.Scope.Liked)
        assertThat(view.effectiveLikedOnly).isTrue()
        assertThat(view.categoryFilter).isNull()
    }

    @Test
    fun `a category can be combined with the liked toggle`() {
        val view = LibraryView(likedOnly = true)
            .withScope(LibraryView.Scope.Category("Beast Hunt"))
        assertThat(view.categoryFilter).isEqualTo("Beast Hunt")
        assertThat(view.effectiveLikedOnly).isTrue()
    }

    @Test
    fun `switching to the liked scope clears the now-redundant toggle`() {
        val view = LibraryView(likedOnly = true).withScope(LibraryView.Scope.Liked)
        assertThat(view.likedOnly).isFalse()
        assertThat(view.effectiveLikedOnly).isTrue()
    }

    @Test
    fun `liked means rated at least one star`() {
        assertThat(track("x", "x", rating = 0).isLiked).isFalse()
        assertThat(track("x", "x", rating = 1).isLiked).isTrue()
        assertThat(track("x", "x", rating = 5).isLiked).isTrue()
    }
}
