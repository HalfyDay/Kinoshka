package hd.kinoshka.app.data.repo

import hd.kinoshka.app.data.api.KinopoiskApi
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImageItem
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.FilmLinkItem
import hd.kinoshka.app.data.model.FilmVideoItem
import hd.kinoshka.app.data.model.FiltersResponse
import hd.kinoshka.app.data.model.SeasonItem
import hd.kinoshka.app.data.model.CARTOON_CONTENT_TYPE
import hd.kinoshka.app.data.model.CARTOON_GENRE_NAME
import hd.kinoshka.app.data.model.containsAnimeGenre
import hd.kinoshka.app.util.log.KLog

/**
 * Клиентский AND-фильтр мультиселекта жанров: тайтл проходит, если содержит ВСЕ
 * выбранные жанры. Айтемы без жанров в выдаче не проверяем — оставляем, чтобы
 * неполные ответы API не давали пустую выдачу. Один жанр и меньше — no-op
 * (одиночный выбор уже отфильтрован сервером).
 */
fun List<FilmItem>.filterMultiGenres(genreNames: Set<String>): List<FilmItem> {
    if (genreNames.size <= 1) return this
    val wanted = genreNames.map { it.lowercase() }.toSet()
    return filter { item ->
        val have = item.genres.orEmpty().mapNotNull { it.genre?.lowercase() }.toSet()
        have.isEmpty() || wanted.all { it in have }
    }
}

/**
 * Вид «Мультфильмы»: в API Кинопоиска отдельного типа нет, отбираем по жанру.
 * Без жанров в выдаче — выкидываем (в отличие от [filterMultiGenres]: здесь
 * неподтверждённый тайтл показывать нельзя). Выключено — no-op.
 */
fun List<FilmItem>.filterCartoons(onlyCartoons: Boolean): List<FilmItem> {
    if (!onlyCartoons) return this
    return filter { item ->
        item.genres.orEmpty().any { it.genre?.trim()?.lowercase() == CARTOON_GENRE_NAME }
    }
}

/**
 * Страница поиска КП: видимые айтемы + число страниц сервера. totalPages нужен
 * для hasMore: конец пагинации определяется сервером, а не пустотой страницы
 * после клиентских фильтров (аниме/мультижанр/мультфильмы вырезают строки —
 * пустая страница НЕ означает конец выдачи).
 */
data class FilmSearchPage(
    val items: List<FilmItem> = emptyList(),
    val totalPages: Int = 0
)

class FilmsRepository(private val api: KinopoiskApi) {
    // Аниме в приложении — задача Shikimori; Kinopoisk отдаёт его в поиске и подборках,
    // поэтому аниме-строки вырезаются до кэша, а не в точках использования.
    private fun List<FilmItem>.withoutAnime(): List<FilmItem> = filterNot { it.genres.containsAnimeGenre() }
    private val popularCache = BoundedCache<String, List<FilmItem>>()
    private val searchCache = BoundedCache<String, FilmSearchPage>()
    private val detailsCache = BoundedCache<Int, FilmDetails>()
    private val seasonsCache = BoundedCache<Int, List<SeasonItem>>()
    private val similarsCache = BoundedCache<Int, List<FilmLinkItem>>()
    private val relationsCache = BoundedCache<Int, List<FilmLinkItem>>()
    private val imagesCache = BoundedCache<String, List<FilmImageItem>>()
    private val filtersCache = BoundedCache<String, FiltersResponse>()
    private val videosCache = BoundedCache<Int, List<FilmVideoItem>>()

    suspend fun popular(
        collectionType: String = "TOP_POPULAR_ALL",
        page: Int = 1
    ): List<FilmItem> {
        val key = "$collectionType:$page"
        popularCache.get(key)?.let { return it }
        // Дедупликация на входе: одна страница Kinopoisk может содержать повторяющийся
        // kinopoiskId, а keyed-списки в UI (key = { film.kinopoiskId }) на дубликате падают.
        // Делаем это ДО cache.put, иначе битая страница живёт в кэше.
        val loaded = api.popular(type = collectionType, page = page).items.orEmpty().distinctBy { it.kinopoiskId }.withoutAnime()
        popularCache.put(key, loaded)
        return loaded
    }

    suspend fun search(
        query: String? = null,
        countryId: Int? = null,
        genreId: Int? = null,
        genreIds: Set<Int> = emptySet(),
        genreNames: Set<String> = emptySet(),
        order: String = "RATING",
        type: String? = "ALL",
        ratingFrom: Int? = null,
        ratingTo: Int? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        page: Int = 1
    ): List<FilmItem> = searchPaged(
        query = query, countryId = countryId, genreId = genreId, genreIds = genreIds,
        genreNames = genreNames, order = order, type = type, ratingFrom = ratingFrom,
        ratingTo = ratingTo, yearFrom = yearFrom, yearTo = yearTo, page = page
    ).items

    /**
     * Поиск со счётчиком страниц сервера (см. [FilmSearchPage]): точки пагинации
     * фильтров используют его для hasMore, остальным хватает [search].
     */
    suspend fun searchPaged(
        query: String? = null,
        countryId: Int? = null,
        genreId: Int? = null,
        /** Мультиселект жанров: API принимает только ОДИН genres («на данный момент
         *  можно указать не более одного жанра», дока /v2.2/films) — первый выбранный
         *  идёт на сервер, остальные проверяются клиентским AND-фильтром по [genreNames]. */
        genreIds: Set<Int> = emptySet(),
        /** Имена выбранных жанров (lowercase не обязателен) для [filterMultiGenres]. */
        genreNames: Set<String> = emptySet(),
        order: String = "RATING",
        type: String? = "ALL",
        ratingFrom: Int? = null,
        ratingTo: Int? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        page: Int = 1
    ): FilmSearchPage {
        val cleanQuery = query?.trim()?.takeIf { it.isNotBlank() }
        val serverGenreId = genreId ?: genreIds.firstOrNull()
        // Псевдо-тип CARTOON API не знает: серверу отдаём ALL, отбираем сами.
        val onlyCartoons = type == CARTOON_CONTENT_TYPE
        val serverType = if (onlyCartoons) "ALL" else type
        val key = "$cleanQuery:$countryId:$serverGenreId:${genreIds.sorted()}:${genreNames.map { it.lowercase() }.sorted()}:$order:$type:$ratingFrom:$ratingTo:$yearFrom:$yearTo:$page"
        searchCache.get(key)?.let { return it }
        val response = api.search(
            keyword = cleanQuery,
            countries = countryId,
            genres = serverGenreId,
            order = order,
            type = serverType,
            ratingFrom = ratingFrom,
            ratingTo = ratingTo,
            yearFrom = yearFrom,
            yearTo = yearTo,
            page = page
        )
        val loaded = FilmSearchPage(
            items = response.items.orEmpty().distinctBy { it.kinopoiskId }.filterMultiGenres(genreNames).filterCartoons(onlyCartoons).withoutAnime(), // см. popular(): дубликат id ломает keyed-списки UI
            totalPages = response.totalPages
        )
        KLog.i(
            "FilmSearch",
            "paged genres=$serverGenreId order=$order type=$type page=$page " +
                "total=${response.total} totalPages=${response.totalPages} " +
                "raw=${response.items.orEmpty().size} visible=${loaded.items.size}"
        )
        searchCache.put(key, loaded)
        return loaded
    }

    /** Подборки для ленты «Обзора»: топы, свежее по годам, жанры. */
    suspend fun topMovies(page: Int = 1): List<FilmItem> =
        popular(collectionType = "TOP_250_MOVIES", page = page)

    suspend fun topShows(page: Int = 1): List<FilmItem> =
        popular(collectionType = "TOP_250_TV_SHOWS", page = page)

    suspend fun freshSince(yearFrom: Int, page: Int = 1): List<FilmItem> =
        search(order = "YEAR", type = "ALL", yearFrom = yearFrom, page = page)

    suspend fun byGenre(genreId: Int, page: Int = 1): List<FilmItem> =
        search(genreId = genreId, order = "RATING", type = "ALL", page = page)

    /** Самое обсуждаемое (по числу оценок) — пул витрины, без дублей каруселей. */
    suspend fun mostDiscussed(page: Int = 1): List<FilmItem> =
        search(order = "NUM_VOTE", type = "ALL", page = page)

    suspend fun filters(): FiltersResponse {
        filtersCache.get("filters")?.let { return it }
        val loaded = api.filters()
        filtersCache.put("filters", loaded)
        return loaded
    }

    suspend fun details(id: Int): FilmDetails {
        detailsCache.get(id)?.let { return it }
        val loaded = api.details(id)
        detailsCache.put(id, loaded)
        return loaded
    }

    suspend fun seasons(id: Int): List<SeasonItem> {
        seasonsCache.get(id)?.let { return it }
        // SeasonsCard рисует items(seasons, key = { it.number }); number — non-null Int со
        // значением по умолчанию 0, поэтому два битых элемента дают одинаковый ключ -> краш.
        // Фильтровать number > 0 НЕЛЬЗЯ: сезон 0 у Kinopoisk — это спешлы, их потеря испортит
        // seasons.size / totalSeasons / totalEpisodes в профиле пользователя.
        val loaded = api.seasons(id).items.orEmpty().distinctBy { it.number }
        seasonsCache.put(id, loaded)
        return loaded
    }

    suspend fun similars(id: Int): List<FilmLinkItem> {
        similarsCache.get(id)?.let { return it }
        val loaded = api.similars(id).items.orEmpty()
        similarsCache.put(id, loaded)
        return loaded
    }

    suspend fun relations(id: Int): List<FilmLinkItem> {
        relationsCache.get(id)?.let { return it }
        val loaded = api.relations(id).items.orEmpty()
        relationsCache.put(id, loaded)
        return loaded
    }

    suspend fun images(id: Int, page: Int = 1): List<FilmImageItem> {
        val key = "$id:$page"
        imagesCache.get(key)?.let { return it }
        val loaded = api.images(id = id, page = page).items.orEmpty()
        imagesCache.put(key, loaded)
        return loaded
    }

    suspend fun videos(id: Int): List<FilmVideoItem> {
        videosCache.get(id)?.let { return it }
        val loaded = runCatching { api.videos(id).items.orEmpty() }.getOrDefault(emptyList())
        if (loaded.isNotEmpty()) videosCache.put(id, loaded)
        return loaded
    }
}
