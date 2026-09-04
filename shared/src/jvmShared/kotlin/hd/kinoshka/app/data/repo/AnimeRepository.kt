package hd.kinoshka.app.data.repo

import hd.kinoshka.app.util.log.KLog

import hd.kinoshka.app.data.api.ShikimoriApi
import hd.kinoshka.app.data.model.ShikimoriAnimeDetails
import hd.kinoshka.app.data.model.ShikimoriAnimeItem
import hd.kinoshka.app.data.model.ShikimoriScreenshot
import hd.kinoshka.app.data.model.ShikimoriTokenResponse
import hd.kinoshka.app.data.model.UserRateData
import hd.kinoshka.app.data.model.UserRateRequest
import hd.kinoshka.app.data.model.UserRateUpdateData
import hd.kinoshka.app.data.model.UserRateUpdateRequest

class AnimeRepository(
    private val api: ShikimoriApi,
    // OAuth-креды Shikimori: на Android приходят из BuildConfig приложения,
    // на desktop — из local.properties/env (Main.kt).
    private val shikimoriClientId: String = "",
    private val shikimoriClientSecret: String = "",
) {
    private val searchCache = BoundedCache<String, List<ShikimoriAnimeItem>>()
    private val detailsCache = BoundedCache<Int, ShikimoriAnimeDetails>()
    private val screenshotsCache = BoundedCache<Int, List<ShikimoriScreenshot>>()
    private val videosCache = BoundedCache<Int, List<hd.kinoshka.app.data.model.ShikimoriVideoItem>>()
    private val relatedCache = BoundedCache<Int, List<hd.kinoshka.app.data.model.ShikimoriRelatedItem>>()
    @Volatile private var genresCache: List<hd.kinoshka.app.data.model.ShikimoriGenre>? = null
    private val franchiseCache = BoundedCache<Int, hd.kinoshka.app.data.model.ShikimoriFranchiseResponse>()
    private val rolesCache = BoundedCache<Int, List<hd.kinoshka.app.data.model.ShikimoriRole>>()

    suspend fun refreshToken(refreshToken: String): ShikimoriTokenResponse? {
        val clientId = shikimoriClientId
        val clientSecret = shikimoriClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            KLog.e("ShikimoriSync", "OAuth credentials not configured")
            return null
        }
        return runCatching {
            val result = api.refreshToken(
                clientId = clientId,
                clientSecret = clientSecret,
                refreshToken = refreshToken
            )
            KLog.d("ShikimoriSync", "Token refreshed successfully")
            result
        }.onFailure { e ->
            KLog.e("ShikimoriSync", "Failed to refresh token: ${e.message}", e)
        }.getOrNull()
    }

    suspend fun exchangeCodeForToken(code: String): ShikimoriTokenResponse? {
        val clientId = shikimoriClientId
        val clientSecret = shikimoriClientSecret
        KLog.d("ShikimoriSync", "exchangeCodeForToken: clientId='$clientId', clientSecret='${clientSecret.take(5)}...'")
        if (clientId.isBlank() || clientSecret.isBlank()) {
            KLog.e("ShikimoriSync", "OAuth credentials not configured! Check local.properties")
            return null
        }
        return runCatching {
            KLog.d("ShikimoriSync", "Calling API to exchange code...")
            val result = api.exchangeCodeForToken(
                clientId = clientId,
                clientSecret = clientSecret,
                code = code
            )
            KLog.d("ShikimoriSync", "API call successful! Token received.")
            result
        }.onFailure { e ->
            KLog.e("ShikimoriSync", "API call FAILED: ${e.message}", e)
            if (e is retrofit2.HttpException) {
                KLog.e("ShikimoriSync", "HTTP error code: ${e.code()}")
                KLog.e("ShikimoriSync", "HTTP error body: ${e.response()?.errorBody()?.string()}")
            }
        }.getOrNull()
    }

    suspend fun popular(page: Int = 1): List<ShikimoriAnimeItem> {
        return search(order = "popularity", censored = false, page = page)
    }

    /** Подборки для ленты «Обзора»: топ по оценке, онгоинги, форматы, жанры. */
    suspend fun topRanked(page: Int = 1): List<ShikimoriAnimeItem> =
        search(order = "ranked", censored = false, page = page)

    suspend fun ongoing(page: Int = 1): List<ShikimoriAnimeItem> =
        search(order = "popularity", status = "ongoing", censored = false, page = page)

    /** «Сейчас на экранах»: онгоинги текущего+прошлого сезонов, score > 7.3 — формула
     *  главной Shikimori (DashboardView: ONGOINGS_FETCH → shuffle → take 8 → sort by ranked).
     *  Долгоиграющий мусор главной (вечные онгоинги) вырезан тем же IGNORE-списком. */
    suspend fun nowOnScreens(currentSeason: String, priorSeason: String): List<ShikimoriAnimeItem> {
        val pool = (
            search(order = "popularity", status = "ongoing", season = currentSeason, scoreFrom = 7, censored = false, limit = 50, page = 1) +
                search(order = "popularity", status = "ongoing", season = priorSeason, scoreFrom = 7, censored = false, limit = 50, page = 1)
            )
            .distinctBy { it.id }
            .filter { it.id !in IGNORED_ONGOING_IDS }
            .filter { (it.score?.toDoubleOrNull() ?: 0.0) > NOW_ON_SCREENS_MIN_SCORE }
        return pool.shuffled().take(NOW_ON_SCREENS_TAKE)
            .sortedByDescending { it.score?.toDoubleOrNull() ?: 0.0 }
    }

    /** Скоро выйдет: анонсы по популярности — пул витрины (без дублей каруселей). */
    suspend fun comingSoon(page: Int = 1): List<ShikimoriAnimeItem> =
        search(order = "popularity", status = "anons", censored = false, page = page)

    companion object {
        const val NOW_ON_SCREENS_MIN_SCORE = 7.3
        const val NOW_ON_SCREENS_TAKE = 8

        /** Вечные онгоинги, которые главная Shikimori прячет (DashboardView::IGNORE_ONGOING_IDS). */
        private val IGNORED_ONGOING_IDS = setOf(
            31592, 32585, 35517, 32977, 8687, 36231, 38008, 38427, 39003, 40368, 48753, 49520
        )
    }

    /** Сезон Shikimori: fall_2026, summer_2026, а также год целиком (2026, 2025). */
    suspend fun bySeason(
        season: String,
        order: String = "ranked",
        scoreFrom: Int? = null,
        page: Int = 1
    ): List<ShikimoriAnimeItem> =
        search(order = order, season = season, scoreFrom = scoreFrom, censored = false, page = page)

    suspend fun byKind(kind: String, page: Int = 1): List<ShikimoriAnimeItem> =
        search(kind = kind, order = "ranked", censored = false, page = page)

    suspend fun byGenreId(genreId: Int, page: Int = 1): List<ShikimoriAnimeItem> =
        search(genreId = genreId, order = "ranked", censored = false, page = page)

    suspend fun search(
        query: String? = null,
        kind: String? = null,
        status: String? = null,
        rating: String? = null,
        genreId: Int? = null,
        studioId: Int? = null,
        order: String? = "popularity",
        scoreFrom: Int? = null,
        season: String? = null,
        censored: Boolean? = null,
        limit: Int = 20,
        page: Int = 1
    ): List<ShikimoriAnimeItem> {
        val cleanQuery = query?.trim()?.ifEmpty { null }
        val genreStr = genreId?.toString()
        val key = "$cleanQuery:$kind:$status:$rating:$genreStr:$studioId:$order:$scoreFrom:$season:$censored:$limit:$page"
        searchCache.get(key)?.let { return it }
        val loaded = api.search(
            search = cleanQuery,
            order = order,
            kind = kind,
            status = status,
            season = season,
            score = scoreFrom,
            rating = rating,
            genre = genreStr,
            studio = studioId,
            censored = censored,
            limit = limit,
            page = page
        )
        searchCache.put(key, loaded)
        return loaded
    }

    suspend fun details(shikimoriId: Int): ShikimoriAnimeDetails {
        detailsCache.get(shikimoriId)?.let { return it }
        val loaded = api.details(shikimoriId)
        detailsCache.put(shikimoriId, loaded)
        return loaded
    }

    suspend fun screenshots(shikimoriId: Int): List<ShikimoriScreenshot> {
        screenshotsCache.get(shikimoriId)?.let { return it }
        val loaded = api.screenshots(shikimoriId)
        screenshotsCache.put(shikimoriId, loaded)
        return loaded
    }

    suspend fun videos(shikimoriId: Int): List<hd.kinoshka.app.data.model.ShikimoriVideoItem> {
        videosCache.get(shikimoriId)?.let { return it }
        val loaded = runCatching { api.videos(shikimoriId) }.getOrDefault(emptyList())
        videosCache.put(shikimoriId, loaded)
        return loaded
    }

    suspend fun related(shikimoriId: Int): List<hd.kinoshka.app.data.model.ShikimoriRelatedItem> {
        relatedCache.get(shikimoriId)?.let { return it }
        val loaded = runCatching { api.related(shikimoriId) }.getOrDefault(emptyList())
        relatedCache.put(shikimoriId, loaded)
        return loaded
    }

    suspend fun franchise(shikimoriId: Int): hd.kinoshka.app.data.model.ShikimoriFranchiseResponse {
        franchiseCache.get(shikimoriId)?.let { return it }
        val loaded = runCatching { api.franchise(shikimoriId) }.getOrDefault(hd.kinoshka.app.data.model.ShikimoriFranchiseResponse())
        franchiseCache.put(shikimoriId, loaded)
        return loaded
    }

    suspend fun roles(shikimoriId: Int): List<hd.kinoshka.app.data.model.ShikimoriRole> {
        rolesCache.get(shikimoriId)?.let { return it }
        val loaded = runCatching { api.roles(shikimoriId) }.getOrDefault(emptyList())
        rolesCache.put(shikimoriId, loaded)
        return loaded
    }

    suspend fun calendar(): List<hd.kinoshka.app.data.model.ShikimoriCalendarItem> {
        return runCatching { api.calendar() }.getOrDefault(emptyList())
    }

    /**
     * Справочник жанров для sheet фильтра аниме-ленты. Тип записи — entry_type
     * (kind всегда "genre"). Без хентая (у него свой раздел 18+).
     * Кэш на сессию — справочник статичен.
     */
    suspend fun animeGenres(): List<hd.kinoshka.app.data.model.ShikimoriGenre> {
        genresCache?.let { return it }
        val loaded = runCatching { api.genres() }.getOrDefault(emptyList())
            .filter { it.entryType.equals("anime", ignoreCase = true) }
            .filterNot { it.name.equals("hentai", ignoreCase = true) }
        if (loaded.isNotEmpty()) genresCache = loaded
        return loaded
    }

    suspend fun topics(): List<hd.kinoshka.app.data.model.ShikimoriTopic> {
        return runCatching { api.topics() }.getOrDefault(emptyList())
    }

    /** Комментарии новостного поста. Ошибка сети — пустой список, карточка скроет раздел. */
    suspend fun topicComments(topicId: Int): List<hd.kinoshka.app.data.model.ShikimoriComment> {
        return runCatching { api.topicComments(commentableId = topicId) }.getOrDefault(emptyList())
    }

    suspend fun character(characterId: Int): hd.kinoshka.app.data.model.ShikimoriCharacterDetails? {
        return runCatching { api.getCharacter(characterId) }.getOrNull()
    }

    suspend fun whoami(token: String): hd.kinoshka.app.data.model.ShikimoriWhoami? {
        return runCatching { api.whoami(if (token.startsWith("Bearer ")) token else "Bearer $token") }.getOrNull()
    }

    /**
     * Возвращает Result, а не список: раньше сетевая ошибка сворачивалась в emptyList(), вызывающая
     * сторона писала это в cachedShikimoriRates и пересобирала библиотеку — одна временная ошибка
     * сети молча удаляла все отслеживаемые в Shikimori аниме из библиотеки.
     */
    suspend fun getUserRates(userId: Int): Result<List<hd.kinoshka.app.data.model.ShikimoriUserRate>> {
        val r1 = runCatching { api.getUserAnimeRates(userId) }
        r1.getOrNull()?.let { if (it.isNotEmpty()) return Result.success(it) }
        val r2 = runCatching { api.getUserRates(userId) }
        // Об ошибке сообщаем только если упали ОБА эндпоинта; реально пустая библиотека — это успех.
        return if (r2.isFailure && r1.isFailure) r2 else Result.success(r2.getOrDefault(emptyList()))
    }

    suspend fun getUserRateForTarget(userId: Int, targetId: Int): Result<hd.kinoshka.app.data.model.ShikimoriUserRate?> {
        // Точечный запрос надёжнее полного списка: кэш/пагинация полного списка могли
        // не содержать рейт, и удаление молча считалось успехом (id=null), не доходя до сервера.
        runCatching { api.getUserRates(userId, targetId = targetId) }.getOrNull()
            ?.firstOrNull { it.targetId == targetId }
            ?.let { return Result.success(it) }
        return getUserRates(userId).map { list -> list.firstOrNull { it.targetId == targetId } }
    }

    suspend fun createUserRate(token: String, userId: Int, targetId: Int, status: String, episodes: Int = 0, score: Int = 0, rewatches: Int? = null): hd.kinoshka.app.data.model.ShikimoriUserRate? {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        val request = UserRateRequest(
            userRate = UserRateData(
                userId = userId,
                targetId = targetId,
                targetType = "Anime",
                status = status,
                episodes = episodes,
                score = score,
                rewatches = rewatches
            )
        )
        KLog.d("ShikimoriSync", "Creating user rate: targetId=$targetId, status=$status, episodes=$episodes, score=$score, rewatches=$rewatches")
        return runCatching {
            val result = api.createUserRate(authHeader, request)
            KLog.d("ShikimoriSync", "Created user rate successfully: id=${result.id}")
            result
        }.onFailure { e ->
            logShikimoriError("create", e)
        }.getOrNull()
    }

    suspend fun updateUserRate(token: String, rateId: Int, status: String? = null, episodes: Int? = null, score: Int? = null, rewatches: Int? = null): hd.kinoshka.app.data.model.ShikimoriUserRate? {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        val request = UserRateUpdateRequest(
            userRate = UserRateUpdateData(
                status = status,
                episodes = episodes,
                score = score,
                rewatches = rewatches
            )
        )
        KLog.d("ShikimoriSync", "Updating user rate: rateId=$rateId, status=$status, episodes=$episodes, score=$score, rewatches=$rewatches")
        return runCatching {
            val result = api.updateUserRate(authHeader, rateId, request)
            KLog.d("ShikimoriSync", "Updated user rate successfully: id=${result.id}")
            result
        }.onFailure { e ->
            logShikimoriError("update", e)
        }.getOrNull()
    }

    suspend fun deleteUserRate(token: String, rateId: Int): Boolean {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        return runCatching { api.deleteUserRate(authHeader, rateId) }
            .fold(
                onSuccess = { true },
                // Рейт уже отсутствует на сервере — цель удаления достигнута, не провал.
                // Ошибкой не логируем, иначе каждая повторная чистка спамит E Failed + 404.
                onFailure = { e ->
                    val code = (e as? retrofit2.HttpException)?.code()
                    if (code == 404) {
                        KLog.d("ShikimoriSync", "delete rateId=$rateId: already gone on server (404), treating as success")
                        true
                    } else {
                        logShikimoriError("delete rateId=$rateId", e)
                        false
                    }
                }
            )
    }

    private fun logShikimoriError(op: String, e: Throwable) {
        val http = e as? retrofit2.HttpException
        val body = http?.let { runCatching { it.response()?.errorBody()?.string() }.getOrNull() }
        KLog.e("ShikimoriSync", "Failed to $op user rate: ${e.message}, http=${http?.code()}, body=$body", e)
    }
}
