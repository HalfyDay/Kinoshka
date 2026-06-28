package hd.kinoshka.app.data.repo

import hd.kinoshka.app.data.api.ShikimoriApi
import hd.kinoshka.app.data.model.ShikimoriAnimeDetails
import hd.kinoshka.app.data.model.ShikimoriAnimeItem
import hd.kinoshka.app.data.model.ShikimoriScreenshot
import java.util.concurrent.ConcurrentHashMap

class AnimeRepository(private val api: ShikimoriApi) {
    private val ttlMs = 3 * 24 * 60 * 60 * 1000L

    private val popularCache = ConcurrentHashMap<Int, AnimeCacheEntry<List<ShikimoriAnimeItem>>>()
    private val searchCache = ConcurrentHashMap<String, AnimeCacheEntry<List<ShikimoriAnimeItem>>>()
    private val detailsCache = ConcurrentHashMap<Int, AnimeCacheEntry<ShikimoriAnimeDetails>>()
    private val screenshotsCache = ConcurrentHashMap<Int, AnimeCacheEntry<List<ShikimoriScreenshot>>>()
    private val relatedCache = ConcurrentHashMap<Int, AnimeCacheEntry<List<hd.kinoshka.app.data.model.ShikimoriRelatedItem>>>()
    private val rolesCache = ConcurrentHashMap<Int, AnimeCacheEntry<List<hd.kinoshka.app.data.model.ShikimoriRole>>>()

    suspend fun popular(page: Int = 1): List<ShikimoriAnimeItem> {
        return search(order = "popularity", page = page)
    }

    suspend fun search(
        query: String? = null,
        kind: String? = null,
        status: String? = null,
        rating: String? = null,
        genreId: Int? = null,
        order: String? = "popularity",
        scoreFrom: Int? = null,
        page: Int = 1
    ): List<ShikimoriAnimeItem> {
        val cleanQuery = query?.trim()?.ifEmpty { null }
        val genreStr = genreId?.toString()
        val key = "$cleanQuery:$kind:$status:$rating:$genreStr:$order:$scoreFrom:$page"
        getIfFresh(searchCache[key])?.let { return it }
        val loaded = api.search(
            search = cleanQuery,
            order = order,
            kind = kind,
            status = status,
            score = scoreFrom,
            rating = rating,
            genre = genreStr,
            limit = 20,
            page = page
        )
        searchCache[key] = AnimeCacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun details(shikimoriId: Int): ShikimoriAnimeDetails {
        getIfFresh(detailsCache[shikimoriId])?.let { return it }
        val loaded = api.details(shikimoriId)
        detailsCache[shikimoriId] = AnimeCacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun screenshots(shikimoriId: Int): List<ShikimoriScreenshot> {
        getIfFresh(screenshotsCache[shikimoriId])?.let { return it }
        val loaded = api.screenshots(shikimoriId)
        screenshotsCache[shikimoriId] = AnimeCacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun related(shikimoriId: Int): List<hd.kinoshka.app.data.model.ShikimoriRelatedItem> {
        getIfFresh(relatedCache[shikimoriId])?.let { return it }
        val loaded = runCatching { api.related(shikimoriId) }.getOrDefault(emptyList())
        relatedCache[shikimoriId] = AnimeCacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun roles(shikimoriId: Int): List<hd.kinoshka.app.data.model.ShikimoriRole> {
        getIfFresh(rolesCache[shikimoriId])?.let { return it }
        val loaded = runCatching { api.roles(shikimoriId) }.getOrDefault(emptyList())
        rolesCache[shikimoriId] = AnimeCacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    private fun <T> getIfFresh(entry: AnimeCacheEntry<T>?): T? {
        if (entry == null) return null
        val age = System.currentTimeMillis() - entry.savedAtMs
        return if (age in 0..ttlMs) entry.value else null
    }
}

private data class AnimeCacheEntry<T>(
    val value: T,
    val savedAtMs: Long
)
