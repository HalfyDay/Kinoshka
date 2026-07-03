package hd.kinoshka.app.data.repo

import android.util.Log
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.data.api.ShikimoriApi
import hd.kinoshka.app.data.model.ShikimoriAnimeDetails
import hd.kinoshka.app.data.model.ShikimoriAnimeItem
import hd.kinoshka.app.data.model.ShikimoriScreenshot
import hd.kinoshka.app.data.model.ShikimoriTokenResponse
import hd.kinoshka.app.data.model.UserRateData
import hd.kinoshka.app.data.model.UserRateRequest
import hd.kinoshka.app.data.model.UserRateUpdateData
import hd.kinoshka.app.data.model.UserRateUpdateRequest
import java.util.concurrent.ConcurrentHashMap

class AnimeRepository(private val api: ShikimoriApi) {
    private val ttlMs = 3 * 24 * 60 * 60 * 1000L

    suspend fun refreshToken(refreshToken: String): ShikimoriTokenResponse? {
        val clientId = BuildConfig.SHIKIMORI_CLIENT_ID
        val clientSecret = BuildConfig.SHIKIMORI_CLIENT_SECRET
        if (clientId.isBlank() || clientSecret.isBlank()) {
            Log.e("ShikimoriSync", "OAuth credentials not configured")
            return null
        }
        return runCatching {
            val result = api.refreshToken(
                clientId = clientId,
                clientSecret = clientSecret,
                refreshToken = refreshToken
            )
            Log.d("ShikimoriSync", "Token refreshed successfully")
            result
        }.onFailure { e ->
            Log.e("ShikimoriSync", "Failed to refresh token: ${e.message}", e)
        }.getOrNull()
    }

    suspend fun exchangeCodeForToken(code: String): ShikimoriTokenResponse? {
        val clientId = BuildConfig.SHIKIMORI_CLIENT_ID
        val clientSecret = BuildConfig.SHIKIMORI_CLIENT_SECRET
        Log.d("ShikimoriSync", "exchangeCodeForToken: clientId='$clientId', clientSecret='${clientSecret.take(5)}...'")
        if (clientId.isBlank() || clientSecret.isBlank()) {
            Log.e("ShikimoriSync", "OAuth credentials not configured! Check local.properties")
            return null
        }
        return runCatching {
            Log.d("ShikimoriSync", "Calling API to exchange code...")
            val result = api.exchangeCodeForToken(
                clientId = clientId,
                clientSecret = clientSecret,
                code = code
            )
            Log.d("ShikimoriSync", "API call successful! Token received.")
            result
        }.onFailure { e ->
            Log.e("ShikimoriSync", "API call FAILED: ${e.message}", e)
            if (e is retrofit2.HttpException) {
                Log.e("ShikimoriSync", "HTTP error code: ${e.code()}")
                Log.e("ShikimoriSync", "HTTP error body: ${e.response()?.errorBody()?.string()}")
            }
        }.getOrNull()
    }

    private val popularCache = ConcurrentHashMap<Int, AnimeCacheEntry<List<ShikimoriAnimeItem>>>()
    private val searchCache = ConcurrentHashMap<String, AnimeCacheEntry<List<ShikimoriAnimeItem>>>()
    private val detailsCache = ConcurrentHashMap<Int, AnimeCacheEntry<ShikimoriAnimeDetails>>()
    private val screenshotsCache = ConcurrentHashMap<Int, AnimeCacheEntry<List<ShikimoriScreenshot>>>()
    private val relatedCache = ConcurrentHashMap<Int, AnimeCacheEntry<List<hd.kinoshka.app.data.model.ShikimoriRelatedItem>>>()
    private val franchiseCache = ConcurrentHashMap<Int, AnimeCacheEntry<hd.kinoshka.app.data.model.ShikimoriFranchiseResponse>>()
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

    suspend fun franchise(shikimoriId: Int): hd.kinoshka.app.data.model.ShikimoriFranchiseResponse {
        getIfFresh(franchiseCache[shikimoriId])?.let { return it }
        val loaded = runCatching { api.franchise(shikimoriId) }.getOrDefault(hd.kinoshka.app.data.model.ShikimoriFranchiseResponse())
        franchiseCache[shikimoriId] = AnimeCacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun roles(shikimoriId: Int): List<hd.kinoshka.app.data.model.ShikimoriRole> {
        getIfFresh(rolesCache[shikimoriId])?.let { return it }
        val loaded = runCatching { api.roles(shikimoriId) }.getOrDefault(emptyList())
        rolesCache[shikimoriId] = AnimeCacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    suspend fun calendar(): List<hd.kinoshka.app.data.model.ShikimoriCalendarItem> {
        return runCatching { api.calendar() }.getOrDefault(emptyList())
    }

    suspend fun topics(): List<hd.kinoshka.app.data.model.ShikimoriTopic> {
        return runCatching { api.topics() }.getOrDefault(emptyList())
    }

    suspend fun character(characterId: Int): hd.kinoshka.app.data.model.ShikimoriCharacterDetails? {
        return runCatching { api.getCharacter(characterId) }.getOrNull()
    }

    suspend fun whoami(token: String): hd.kinoshka.app.data.model.ShikimoriWhoami? {
        return runCatching { api.whoami(if (token.startsWith("Bearer ")) token else "Bearer $token") }.getOrNull()
    }

    suspend fun getUserRates(userId: Int): List<hd.kinoshka.app.data.model.ShikimoriUserRate> {
        val list1 = runCatching { api.getUserAnimeRates(userId) }.getOrDefault(emptyList())
        if (list1.isNotEmpty()) return list1
        return runCatching { api.getUserRates(userId) }.getOrDefault(emptyList())
    }

    suspend fun createUserRate(token: String, userId: Int, targetId: Int, status: String, episodes: Int = 0, score: Int = 0): hd.kinoshka.app.data.model.ShikimoriUserRate? {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        val request = UserRateRequest(
            userRate = UserRateData(
                userId = userId,
                targetId = targetId,
                targetType = "Anime",
                status = status,
                episodes = episodes,
                score = score
            )
        )
        Log.d("ShikimoriSync", "Creating user rate: targetId=$targetId, status=$status, episodes=$episodes, score=$score")
        return runCatching {
            val result = api.createUserRate(authHeader, request)
            Log.d("ShikimoriSync", "Created user rate successfully: id=${result.id}")
            result
        }.onFailure { e ->
            Log.e("ShikimoriSync", "Failed to create user rate: ${e.message}", e)
        }.getOrNull()
    }

    suspend fun updateUserRate(token: String, rateId: Int, status: String? = null, episodes: Int? = null, score: Int? = null): hd.kinoshka.app.data.model.ShikimoriUserRate? {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        val request = UserRateUpdateRequest(
            userRate = UserRateUpdateData(
                status = status,
                episodes = episodes,
                score = score
            )
        )
        Log.d("ShikimoriSync", "Updating user rate: rateId=$rateId, status=$status, episodes=$episodes, score=$score")
        return runCatching {
            val result = api.updateUserRate(authHeader, rateId, request)
            Log.d("ShikimoriSync", "Updated user rate successfully: id=${result.id}")
            result
        }.onFailure { e ->
            Log.e("ShikimoriSync", "Failed to update user rate: ${e.message}", e)
        }.getOrNull()
    }

    suspend fun deleteUserRate(token: String, rateId: Int) {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        runCatching { api.deleteUserRate(authHeader, rateId) }
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
