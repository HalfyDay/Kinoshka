package hd.kinoshka.app.data.repo

import hd.kinoshka.app.data.api.KinopoiskApi
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImageItem
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.FilmLinkItem
import hd.kinoshka.app.data.model.FiltersResponse
import hd.kinoshka.app.data.model.SeasonItem

class FilmsRepository(private val api: KinopoiskApi) {
    private val popularCache = BoundedCache<String, List<FilmItem>>()
    private val searchCache = BoundedCache<String, List<FilmItem>>()
    private val detailsCache = BoundedCache<Int, FilmDetails>()
    private val seasonsCache = BoundedCache<Int, List<SeasonItem>>()
    private val similarsCache = BoundedCache<Int, List<FilmLinkItem>>()
    private val relationsCache = BoundedCache<Int, List<FilmLinkItem>>()
    private val imagesCache = BoundedCache<String, List<FilmImageItem>>()
    private val filtersCache = BoundedCache<String, FiltersResponse>()

    suspend fun popular(
        collectionType: String = "TOP_POPULAR_ALL",
        page: Int = 1
    ): List<FilmItem> {
        val key = "$collectionType:$page"
        popularCache.get(key)?.let { return it }
        val loaded = api.popular(type = collectionType, page = page).items
        popularCache.put(key, loaded)
        return loaded
    }

    suspend fun search(
        query: String? = null,
        countryId: Int? = null,
        genreId: Int? = null,
        order: String = "RATING",
        type: String = "ALL",
        ratingFrom: Int? = null,
        ratingTo: Int? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        page: Int = 1
    ): List<FilmItem> {
        val cleanQuery = query?.trim()?.takeIf { it.isNotBlank() }
        val key = "$cleanQuery:$countryId:$genreId:$order:$type:$ratingFrom:$ratingTo:$yearFrom:$yearTo:$page"
        searchCache.get(key)?.let { return it }
        val loaded = api.search(
            keyword = cleanQuery,
            countries = countryId,
            genres = genreId,
            order = order,
            type = type,
            ratingFrom = ratingFrom,
            ratingTo = ratingTo,
            yearFrom = yearFrom,
            yearTo = yearTo,
            page = page
        ).items
        searchCache.put(key, loaded)
        return loaded
    }

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
        val loaded = api.seasons(id).items
        seasonsCache.put(id, loaded)
        return loaded
    }

    suspend fun similars(id: Int): List<FilmLinkItem> {
        similarsCache.get(id)?.let { return it }
        val loaded = api.similars(id).items
        similarsCache.put(id, loaded)
        return loaded
    }

    suspend fun relations(id: Int): List<FilmLinkItem> {
        relationsCache.get(id)?.let { return it }
        val loaded = api.relations(id).items
        relationsCache.put(id, loaded)
        return loaded
    }

    suspend fun images(id: Int, page: Int = 1): List<FilmImageItem> {
        val key = "$id:$page"
        imagesCache.get(key)?.let { return it }
        val loaded = api.images(id = id, page = page).items
        imagesCache.put(key, loaded)
        return loaded
    }
}
