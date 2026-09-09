package com.thunderplay.library

import com.thunderplay.data.TrackEntity
import kotlin.random.Random

/**
 * What the library list is currently showing.
 *
 * The four filters are independent and combine with AND, so "Beast Hunt, level III, three stars
 * or better" is a single state rather than a mode you have to switch between. Each one has a
 * neutral value - [Stars.Any], and null for the three folder and tag filters - meaning "no
 * restriction".
 */
data class LibraryView(
    val stars: Stars = Stars.Any,
    /** Category to restrict to; null means every category. */
    val category: String? = null,
    /** Intensity level (the folder inside the category) to restrict to; null means every level. */
    val level: String? = null,
    /**
     * Generator genre to restrict to; null means every genre.
     *
     * Unlike category and level this does not come from the folder tree - it is read out of the
     * WAV header, so it can separate music from sound effects that sit in the same folder.
     */
    val genre: String? = null,
    val order: Order = Order.Name,
    val query: String = "",
    /** Fixed for the life of a shuffle so the list does not reorder as you scroll. */
    val shuffleSeed: Long = 0L,
) {
    /**
     * The star filter, as the single value its dropdown carries.
     *
     * [Unrated] is "exactly none" rather than "at least none": picking zero stars is how you find
     * the tracks you have not judged yet, which a plain minimum of zero could not express - that
     * would just be [Any] again.
     */
    enum class Stars(val minimum: Int?) {
        Any(null),
        Unrated(0),
        One(1),
        Two(2),
        Three(3),
        Four(4),
        Five(5),
    }

    enum class Order { Name, RecentlyAdded, MostPlayed, HighestRated, Random }

    /** True when the dropdowns are hiding part of the library, which is what Clear undoes. */
    val isFiltered: Boolean
        get() = stars != Stars.Any || category != null || level != null || genre != null

    fun cleared() = copy(stars = Stars.Any, category = null, level = null, genre = null)

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
