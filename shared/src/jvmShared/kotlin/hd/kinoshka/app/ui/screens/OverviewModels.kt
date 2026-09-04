package hd.kinoshka.app.ui.screens

import hd.kinoshka.app.data.model.FilmItem

/**
 * Одна горизонтальная карусель ленты «Обзора»: заголовок + постеры.
 * [seeAll] описывает, куда ведёт кнопка «Все»: повтор старого discover-экрана
 * (категория/жанр), без новых маршрутов навигации.
 */
data class OverviewSection(
    val id: String,
    val title: String,
    val items: List<FilmItem> = emptyList(),
    val seeAll: OverviewSeeAll? = null
)

sealed interface OverviewSeeAll {
    data class DiscoverCategoryTarget(val category: DiscoverCategory) : OverviewSeeAll
    data object FilmPopular : OverviewSeeAll
    data class FilmGenreTarget(val genreId: Int, val genreName: String) : OverviewSeeAll
    data object FilmFresh : OverviewSeeAll
    data class AnimeGenreTarget(val genreId: Int, val genreName: String) : OverviewSeeAll
    data class AnimeKindTarget(val kind: String, val title: String) : OverviewSeeAll
    data class AnimeSeasonTarget(val season: String, val title: String, val order: String) : OverviewSeeAll
    data object AnimeOngoing : OverviewSeeAll
    data object AnimeOnAir : OverviewSeeAll
    data object AnimeRanked : OverviewSeeAll
    data object AnimePopular : OverviewSeeAll
}
