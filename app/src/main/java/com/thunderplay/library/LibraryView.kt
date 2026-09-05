package com.thunderplay.library

import com.thunderplay.data.TrackEntity
import kotlin.random.Random

/**
 * What the library list is currently showing.
 *
 * "Liked" is both a scope of its own and a toggle that combines with a real category, so both
 * "everything I like" and "Beast Hunt, but only the ones I like" are expressible.
 */
data class LibraryView(
    val scope: Scope = Scope.All,
    val likedOnly: Boolean = false,
    val order: Order = Order.Name,
    val query: String = "",
    /** Fixed for the life of a shuffle so the list does not reorder as you scroll. */
    val shuffleSeed: Long = 0L,
) {
    sealed interface Scope {
        data object All : Scope
        data object Liked : Scope
        data class Category(val name: String) : Scope
    }

    enum class Order { Name, MostPlayed, HighestRated, RecentlyAdded, Random }

    /** The category to filter on in SQL; null means "no category restriction". */
    val categoryFilter: String? get() = (scope as? Scope.Category)?.name

    /** Liked scope implies the toggle, which is why the chip is hidden in that mode. */
    val effectiveLikedOnly: Boolean get() = likedOnly || scope is Scope.Liked

    fun withScope(next: Scope) = copy(
        scope = next,
        // The toggle is meaningless under the Liked scope; clear it so returning to All is clean.
        likedOnly = if (next is Scope.Liked) false else likedOnly,
    )

    fun reshuffled(seed: Long = Random.nextLong()) = copy(order = Order.Random, shuffleSeed = seed)
}

/**
 * Applies [LibraryView.order] to an already-filtered list.
 *
 * Ordering lives here rather than in SQL because a seeded shuffle cannot be expressed in SQLite,
 * and keeping every order in one pure function makes them all trivially testable.
 */
fun List<TrackEntity>.ordered(view: LibraryView): List<TrackEntity> = when (view.order) {
    LibraryView.Order.Name ->
        sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    LibraryView.Order.MostPlayed ->
        sortedWith(
            compareByDescending<TrackEntity> { it.playCount }
                .thenByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    LibraryView.Order.HighestRated ->
        sortedWith(
            compareByDescending<TrackEntity> { it.rating }
                .thenByDescending { it.ratedAt ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    LibraryView.Order.RecentlyAdded ->
        sortedWith(
            compareByDescending<TrackEntity> { it.addedAt ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    // Shuffle from a fixed start order so the same seed always yields the same permutation,
    // regardless of what order the database happened to hand rows back in.
    LibraryView.Order.Random ->
        sortedBy { it.driveId }.shuffled(Random(view.shuffleSeed))
}
