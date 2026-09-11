package hd.kinoshka.app.ui.screens

import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.NameOnly
import hd.kinoshka.app.data.repo.FilmSearchPage
import hd.kinoshka.app.data.repo.GenreFanoutPaginator
import hd.kinoshka.app.data.repo.YearBucket
import hd.kinoshka.app.data.repo.filterCartoons
import hd.kinoshka.app.data.repo.filterMultiGenres
import hd.kinoshka.app.data.repo.hasMorePages
import hd.kinoshka.app.data.repo.sortForOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мультиселект жанров в «Фильтрах поиска»: повторный тап снимает выбор,
 * несколько жанров комбинируются (аниме — на сервере, кино — AND-фильтром).
 */
class SearchFilterGenresTest {

    @Test
    fun `toggle adds missing genre`() {
        assertEquals(setOf(24), toggleGenreId(emptySet(), 24))
    }

    @Test
    fun `toggle removes selected genre`() {
        assertEquals(emptySet<Int>(), toggleGenreId(setOf(24), 24))
    }

    @Test
    fun `toggle keeps other selected genres`() {
        assertEquals(setOf(8, 10), toggleGenreId(setOf(8, 10, 24), 24))
        assertEquals(setOf(8, 10, 24), toggleGenreId(setOf(8, 10), 24))
    }

    @Test
    fun `genre sets activate filter state`() {
        assertFalse(SearchFilterState().isActive)
        assertTrue(SearchFilterState(selectedGenreIds = setOf(1)).isActive)
        assertTrue(SearchFilterState(animeGenreIds = setOf(9)).isActive)
    }

    @Test
    fun `anime genre ids are unique`() {
        val ids = shikimoriGenres.map { it.id }
        assertEquals("дубли id жанров снова перепутают чипы", ids.size, ids.toSet().size)
    }

    @Test
    fun `ecchi has its own id distinct from sci-fi`() {
        val ecchi = shikimoriGenres.first { it.genre == "Этти" }
        val scifi = shikimoriGenres.first { it.genre == "Фантастика" }
        assertEquals(9, ecchi.id)
        assertEquals(24, scifi.id)
    }

    private fun film(id: Int, vararg genres: String) = FilmItem(
        kinopoiskId = id,
        nameRu = "Фильм $id",
        nameOriginal = null,
        posterUrlPreview = null,
        ratingKinopoisk = null,
        year = null,
        genres = genres.map { NameOnly(genre = it) }
    )

    @Test
    fun `single genre does not filter client-side`() {
        val items = listOf(film(1, "комедия"), film(2, "драма"))
        assertEquals(items, items.filterMultiGenres(setOf("Комедия")))
        assertEquals(items, items.filterMultiGenres(emptySet()))
    }

    @Test
    fun `multiple genres keep only titles having all of them`() {
        val items = listOf(
            film(1, "комедия", "фантастика"),
            film(2, "комедия"),
            film(3, "фантастика", "комедия", "драма")
        )
        assertEquals(
            listOf(1, 3),
            items.filterMultiGenres(setOf("комедия", "фантастика")).map { it.kinopoiskId }
        )
    }

    @Test
    fun `items without genres are kept`() {
        val noGenres = FilmItem(
            kinopoiskId = 7,
            nameRu = "Без жанров",
            nameOriginal = null,
            posterUrlPreview = null,
            ratingKinopoisk = null,
            year = null,
            genres = null
        )
        val items = listOf(noGenres, film(1, "комедия"))
        assertEquals(
            listOf(7),
            items.filterMultiGenres(setOf("комедия", "фантастика")).map { it.kinopoiskId }
        )
    }

    @Test
    fun `genre name matching is case-insensitive`() {
        val items = listOf(film(1, "Комедия", "ФАНТАСТИКА"))
        assertEquals(
            listOf(1),
            items.filterMultiGenres(setOf("кОмЕдИя", "фантастика")).map { it.kinopoiskId }
        )
    }

    @Test
    fun `cartoons filter keeps only cartoon genre`() {
        val items = listOf(
            film(1, "мультфильм", "комедия"),
            film(2, "драма"),
            film(3, "Мультфильм", "семейный")
        )
        assertEquals(listOf(1, 3), items.filterCartoons(true).map { it.kinopoiskId })
    }

    @Test
    fun `cartoons filter drops items without genres`() {
        val noGenres = FilmItem(
            kinopoiskId = 7,
            nameRu = "Без жанров",
            nameOriginal = null,
            posterUrlPreview = null,
            ratingKinopoisk = null,
            year = null,
            genres = null
        )
        assertEquals(emptyList<FilmItem>(), listOf(noGenres).filterCartoons(true))
    }

    @Test
    fun `cartoons filter off is no-op`() {
        val items = listOf(film(1, "драма"), film(2, "мультфильм"))
        assertEquals(items, items.filterCartoons(false))
    }

    @Test
    fun `cartoon type activates filter state`() {
        assertTrue(SearchFilterState(selectedType = "CARTOON").isActive)
    }

    @Test
    fun `hasMore follows server page count`() {
        // Середина выдачи: пустая после фильтров страница — не конец.
        assertTrue(hasMorePages(loadedPage = 6, totalPages = 120, loadedNonEmpty = false))
        assertTrue(hasMorePages(loadedPage = 6, totalPages = 120, loadedNonEmpty = true))
        // Последняя страница сервера — конец, даже если непустая.
        assertFalse(hasMorePages(loadedPage = 120, totalPages = 120, loadedNonEmpty = true))
        assertFalse(hasMorePages(loadedPage = 120, totalPages = 120, loadedNonEmpty = false))
    }

    @Test
    fun `hasMore respects api twenty page cap`() {
        // API отдаёт не более 20 страниц: дальше сервер отвечает 400 — не просим.
        assertTrue(hasMorePages(loadedPage = 19, totalPages = 250, loadedNonEmpty = true))
        assertFalse(hasMorePages(loadedPage = 20, totalPages = 250, loadedNonEmpty = true))
        assertFalse(hasMorePages(loadedPage = 20, totalPages = 20, loadedNonEmpty = true))
    }

    @Test
    fun `hasMore falls back to non-empty page when count unknown`() {
        assertTrue(hasMorePages(loadedPage = 3, totalPages = 0, loadedNonEmpty = true))
        assertFalse(hasMorePages(loadedPage = 3, totalPages = 0, loadedNonEmpty = false))
    }

    private fun rated(id: Int, rating: Double?, year: Int? = null) = FilmItem(
        kinopoiskId = id,
        nameRu = "Фильм $id",
        nameOriginal = null,
        posterUrlPreview = null,
        ratingKinopoisk = rating,
        year = year,
        genres = null
    )

    @Test
    fun `merge sorts chunk by rating with nulls last`() {
        val items = listOf(rated(1, 7.0), rated(2, null), rated(3, 9.0), rated(4, 9.0))
        assertEquals(listOf(3, 4, 1, 2), items.sortForOrder("RATING").map { it.kinopoiskId })
    }

    @Test
    fun `merge sorts chunk by year with nulls last`() {
        val items = listOf(rated(1, 9.0, 1990), rated(2, 5.0, null), rated(3, 6.0, 2020))
        assertEquals(listOf(3, 1, 2), items.sortForOrder("YEAR").map { it.kinopoiskId })
    }

    @Test
    fun `fanout merges buckets into one rating order`() = runBlocking {
        val pages = mapOf(
            2000 to listOf(rated(1, 9.0), rated(2, 7.0)),
            2010 to listOf(rated(3, 8.0))
        )
        val paginator = GenreFanoutPaginator(
            order = "RATING",
            buckets = listOf(YearBucket(2000, 2009), YearBucket(2010, 2019))
        ) { yf, _, _ -> FilmSearchPage(items = pages[yf].orEmpty(), totalPages = 1) }
        assertEquals(listOf(1, 3, 2), paginator.nextChunk().map { it.kinopoiskId })
        assertFalse(paginator.hasMore)
    }

    @Test
    fun `fanout keeps paginating while buckets have pages`() = runBlocking {
        val paginator = GenreFanoutPaginator(
            order = "RATING",
            buckets = listOf(YearBucket(2000, 2009))
        ) { _, _, page ->
            if (page <= 2) FilmSearchPage(items = listOf(rated(page, 10.0 - page)), totalPages = 2)
            else FilmSearchPage(items = emptyList(), totalPages = 2)
        }
        assertTrue(paginator.hasMore)
        paginator.nextChunk()
        assertTrue(paginator.hasMore)
        paginator.nextChunk()
        assertFalse(paginator.hasMore)
    }

    @Test
    fun `fanout survives failing bucket and gives up after three`() = runBlocking {
        var calls = 0
        val paginator = GenreFanoutPaginator(
            order = "RATING",
            buckets = listOf(YearBucket(2000, 2009), YearBucket(2010, 2019))
        ) { yf, _, _ ->
            calls++
            if (yf == 2000) throw RuntimeException("429")
            FilmSearchPage(items = listOf(rated(9, 8.0)), totalPages = 5)
        }
        assertEquals(listOf(9), paginator.nextChunk().map { it.kinopoiskId })
        assertTrue(paginator.hasMore)
        paginator.nextChunk()
        paginator.nextChunk()
        // Три провала подряд — ведро списано, но второе живо: всё ещё есть что листать.
        assertTrue(paginator.hasMore)
        assertTrue(calls >= 3)
    }
}
