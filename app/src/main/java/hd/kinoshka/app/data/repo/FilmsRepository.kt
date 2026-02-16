package hd.kinoshka.app.data.repo

import hd.kinoshka.app.data.api.KinopoiskApi
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImageItem
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.FilmLinkItem
import hd.kinoshka.app.data.model.SeasonItem
import java.util.concurrent.ConcurrentHashMap

class FilmsRepository(private val api: KinopoiskApi) {
    private val ttlMs = 3 * 24 * 60 * 60 * 1000L

    private val popularCache = ConcurrentHashMap<String, CacheEntry<List<FilmItem>>>()
    private val searchCache = ConcurrentHashMap<String, CacheEntry<List<FilmItem>>>()
    private val detailsCache = ConcurrentHashMap<Int, CacheEntry<FilmDetails>>()
    private val seasonsCache = ConcurrentHashMap<Int, CacheEntry<List<SeasonItem>>>()
    private val similarsCache = ConcurrentHashMap<Int, CacheEntry<List<FilmLinkItem>>>()
    private val relationsCache = ConcurrentHashMap<Int, CacheEntry<List<FilmLinkItem>>>()
    private val imagesCache = ConcurrentHashMap<String, CacheEntry<List<FilmImageItem>>>()

    suspend fun popular(
        collectionType: String = "TOP_POPULAR_ALL",
        page: Int = 1
    ): List<FilmItem> {
        val key = "$collectionType:$page"
        getIfFresh(popularCache[key])?.let { return it }
        val loaded = api.popular(type = collectionType, page = page).items
        popularCache[key] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun search(
        query: String,
        page: Int = 1
    ): List<FilmItem> {
        val key = "${query.trim().lowercase()}:$page"
        getIfFresh(searchCache[key])?.let { return it }
        val loaded = api.search(keyword = query, page = page).items
        searchCache[key] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun details(id: Int): FilmDetails {
        getIfFresh(detailsCache[id])?.let { return it }
        val loaded = api.details(id)
        detailsCache[id] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun seasons(id: Int): List<SeasonItem> {
        getIfFresh(seasonsCache[id])?.let { return it }
        val loaded = api.seasons(id).items
        seasonsCache[id] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun similars(id: Int): List<FilmLinkItem> {
        getIfFresh(similarsCache[id])?.let { return it }
        val loaded = api.similars(id).items
        similarsCache[id] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun relations(id: Int): List<FilmLinkItem> {
        getIfFresh(relationsCache[id])?.let { return it }
        val loaded = api.relations(id).items
        relationsCache[id] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun images(id: Int, page: Int = 1): List<FilmImageItem> {
        val key = "$id:$page"
        getIfFresh(imagesCache[key])?.let { return it }
        val loaded = api.images(id = id, page = page).items
        imagesCache[key] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    private fun <T> getIfFresh(entry: CacheEntry<T>?): T? {
        if (entry == null) return null
        val age = System.currentTimeMillis() - entry.savedAtMs
        return if (age in 0..ttlMs) entry.value else null
    }
}

private data class CacheEntry<T>(
    val value: T,
    val savedAtMs: Long
)

