package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Видео-источник Anixart для аниме-пикера: агрегатор озвучек
 * (Kodik/Sibnet/Libria/...) поверх цепочки release → озвучки → источники → серии.
 *
 * Цепочка (сверено живьём 09.09.2026, гостевой доступ без токена работает):
 * - GET release/{id}?extended_mode=true — счётчики серий, is_play_disabled
 * - GET episode/{id} — types[] озвучек (id, name, is_sub, view_count, pinned)
 * - GET episode/{id}/{dubber} — sources[] (id, name)
 * - GET episode/{id}/{dubber}/{source}?sort=1 — episodes[] (position, name, url, iframe)
 * - GET episode/target/{id}/{source}/{position} — подписанная ссылка (?d &s &ip,
 *   живёт минуты: резолвить в момент воспроизведения, листинг её не кэширует)
 * - POST search/releases/{page} {query, searchBy:0} + header Api-Version: v2
 *   (без заголовка зеркало отвечает 404) — резолюция anixart id по названию
 *
 * Токен опционален: [tokenProvider] отдаёт сессию залогиненного юзера
 * (ставится из KinoApp), без него — гостевой режим. Зеркала ротируются по
 * [ApiClient.ANIXART_HOSTS] при IOException и retryable HTTP (геоблок/рассинхрон).
 */
object AnixartVideoResolver {
    private const val TAG = "AnixartVideo"

    /** Поставщик токена сессии (null — гость). Ставится один раз из KinoApp. */
    @Volatile
    var tokenProvider: (() -> String?)? = null

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val APP_USER_AGENT =
        "AnixartApp/9.0 BETA 7-25082901 (Android 9; SDK 28; x86_64; ROG ASUS AI2201_B; ru)"

    /** HTTP-коды, при которых пробуем следующее зеркало. */
    private val RETRYABLE_CODES = setOf(403, 404, 408, 429, 500, 502, 503, 504)

    private const val LISTING_TTL_MS = 10 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 3 * 60 * 1000L
    private const val MAX_PARALLEL = 5

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    private val releaseIdCache = ConcurrentHashMap<String, CacheEntry<Int?>>()
    private val dubbersCache = ConcurrentHashMap<Int, CacheEntry<List<Dubber>>>()
    private val sourcesCache = ConcurrentHashMap<String, CacheEntry<List<VideoSource>>>()
    private val episodesCache = ConcurrentHashMap<String, CacheEntry<List<Episode>>>()

    /** Озвучка релиза. */
    data class Dubber(
        val id: Int,
        val name: String,
        val isSub: Boolean,
        val viewCount: Int,
        val pinned: Boolean
    )

    /** Источник озвучки (Kodik, Sibnet, Libria, ...). */
    data class VideoSource(
        val id: Int,
        val name: String
    )

    /** Серия источника. */
    data class Episode(
        val position: Int,
        val name: String,
        val url: String,
        val iframe: Boolean
    )

    // ---- translationId: "releaseId:dubberId" (v2; v1 "releaseId:dubberId:sourceId" тоже читается) ----
    //
    // Один translation на озвучку: хосты (Kodik/Sibnet/Libria/...) сливаются, конкретный
    // рабочий подбирается авто-фолбэком в момент резолва. Отдельный ряд на каждый хост
    // плодил одинаковые строки «Anixart» на шаге источников и дубли «Озвучка · Kodik»,
    // ломавшие группировку DubGroup на шаге озвучек (суффикс менял ключ группы).

    fun encodeTranslationId(releaseId: Int, dubberId: Int): String =
        "$releaseId:$dubberId"

    data class TranslationRef(
        val releaseId: Int,
        val dubberId: Int,
        /** null — все источники озвучки с авто-фолбэком (v2); иначе один хост (legacy v1). */
        val sourceId: Int? = null
    )

    fun decodeTranslationId(translationId: String): TranslationRef? {
        val parts = translationId.split(":").map { it.toIntOrNull() ?: return null }
        return when {
            parts.size == 2 && parts.all { it > 0 } -> TranslationRef(parts[0], parts[1])
            parts.size == 3 && parts.all { it > 0 } -> TranslationRef(parts[0], parts[1], parts[2])
            else -> null
        }
    }

    /** Anixart помечает мёртвые источники в имени («Источник 3 (не работает)»). */
    fun isUnusableSourceName(name: String?): Boolean =
        Regex("""не\s*работает""", RegexOption.IGNORE_CASE).containsMatchIn(name.orEmpty())

    fun dubberType(isSub: Boolean): String = if (isSub) "sub" else "voice"

    // ---- сеть с ротацией зеркал ----

    private fun token(): String? = runCatching { tokenProvider?.invoke() }.getOrNull()

    private fun urlFor(host: String, path: String, query: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("https://").append(host).append(path)
        val params = query.toMutableMap()
        token()?.takeIf { it.isNotBlank() }?.let { params["token"] = it }
        if (params.isNotEmpty()) {
            sb.append("?")
            sb.append(params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            })
        }
        return sb.toString()
    }

    private fun executeWithMirrors(requestFor: (host: String) -> Request): String? {
        var lastError: Exception? = null
        for (host in ApiClient.ANIXART_HOSTS) {
            val request = runCatching { requestFor(host) }.getOrNull() ?: continue
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in RETRYABLE_CODES) return@use
                    if (!response.isSuccessful) {
                        KLog.w(TAG, "$host -> HTTP ${response.code}")
                        return@use
                    }
                    return response.body?.string()?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                lastError = e
                KLog.w(TAG, "$host failed: ${e.javaClass.simpleName}")
            }
        }
        if (lastError != null) KLog.w(TAG, "all mirrors failed, last: ${lastError.javaClass.simpleName}")
        return null
    }

    private fun getJson(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val body = executeWithMirrors { host ->
            Request.Builder()
                .url(urlFor(host, path, query))
                .header("User-Agent", APP_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
        } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun postSearchJson(query: String): JSONObject? {
        val payload = JSONObject().put("query", query).put("searchBy", 0).toString()
        val body = executeWithMirrors { host ->
            Request.Builder()
                .url(urlFor(host, "/search/releases/0"))
                .header("User-Agent", APP_USER_AGENT)
                .header("Accept", "application/json")
                .header("Api-Version", "v2")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
        } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    // ---- резолюция anixart id ----

    /**
     * Точный нормализованный матч названия (пуш пишет в чужой аккаунт — тут тоже
     * не гадаем: только точное совпадение ru/original/en с кандидатом).
     */
    suspend fun findReleaseId(titles: List<String>): Int? = withContext(Dispatchers.IO) {
        val candidates = titles.mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }
            .distinct().take(3)
        if (candidates.isEmpty()) return@withContext null
        val cacheKey = candidates.joinToString("|").lowercase()
        releaseIdCache[cacheKey]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val valid = if (entry.data == null) age < NEGATIVE_TTL_MS else age < LISTING_TTL_MS
            if (valid) return@withContext entry.data
            releaseIdCache.remove(cacheKey)
        }

        val normed = candidates.map { TitleMatching.normalizeTitle(it) }.filter { it.isNotEmpty() }
        if (normed.isEmpty()) return@withContext null
        var found: Int? = null
        for (query in candidates) {
            val resp = postSearchJson(query) ?: continue
            if (resp.optInt("code", -1) != 0) continue
            val hits = resp.optJSONArray("releases") ?: resp.optJSONArray("content") ?: continue
            for (i in 0 until hits.length()) {
                val hit = hits.optJSONObject(i) ?: continue
                val id = hit.optInt("id", 0)
                if (id <= 0) continue
                val hitTitles = listOf(
                    hit.optString("title_ru"), hit.optString("title_original"), hit.optString("title_en")
                ).map { TitleMatching.normalizeTitle(it) }
                if (hitTitles.any { it.isNotEmpty() && it in normed }) {
                    found = id
                    break
                }
            }
            if (found != null) break
        }
        releaseIdCache[cacheKey] = CacheEntry(found, System.currentTimeMillis())
        found
    }

    // ---- листинг ----

    /** Озвучки релиза: сначала закреплённые, потом по просмотрам (как в anixapp). */
    suspend fun fetchDubbers(releaseId: Int): List<Dubber> = withContext(Dispatchers.IO) {
        if (releaseId <= 0) return@withContext emptyList()
        dubbersCache[releaseId]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val valid = if (entry.data.isEmpty()) age < NEGATIVE_TTL_MS else age < LISTING_TTL_MS
            if (valid) return@withContext entry.data
            dubbersCache.remove(releaseId)
        }
        val resp = getJson("/episode/$releaseId") ?: run {
            dubbersCache[releaseId] = CacheEntry(emptyList(), System.currentTimeMillis())
            return@withContext emptyList()
        }
        if (resp.optInt("code", -1) != 0) {
            dubbersCache[releaseId] = CacheEntry(emptyList(), System.currentTimeMillis())
            return@withContext emptyList()
        }
        val out = mutableListOf<Dubber>()
        val types = resp.optJSONArray("types") ?: JSONArray()
        for (i in 0 until types.length()) {
            val t = types.optJSONObject(i) ?: continue
            val id = t.optInt("id", 0)
            if (id <= 0) continue
            out += Dubber(
                id = id,
                name = t.optString("name").ifBlank { "Озвучка $id" },
                isSub = t.optBoolean("is_sub", false),
                viewCount = t.optInt("view_count", 0),
                pinned = t.optBoolean("pinned", false)
            )
        }
        val sorted = out.sortedWith(compareByDescending<Dubber> { it.pinned }.thenByDescending { it.viewCount })
        dubbersCache[releaseId] = CacheEntry(sorted, System.currentTimeMillis())
        sorted
    }

    /**
     * Источники озвучки. Официальный список иногда пуст (Anixart прячет мёртвые) —
     * тогда добираем id из episode/updates (первые 5 страниц), как делает anixapp.
     */
    suspend fun fetchSources(releaseId: Int, dubberId: Int): List<VideoSource> = withContext(Dispatchers.IO) {
        if (releaseId <= 0 || dubberId <= 0) return@withContext emptyList()
        val cacheKey = "$releaseId:$dubberId"
        sourcesCache[cacheKey]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val valid = if (entry.data.isEmpty()) age < NEGATIVE_TTL_MS else age < LISTING_TTL_MS
            if (valid) return@withContext entry.data
            sourcesCache.remove(cacheKey)
        }
        val official = mutableListOf<VideoSource>()
        val resp = getJson("/episode/$releaseId/$dubberId")
        if (resp != null && resp.optInt("code", -1) == 0) {
            val sources = resp.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val id = s.optInt("id", 0)
                if (id <= 0) continue
                val name = s.optString("name").ifBlank { "Источник $id" }
                if (isUnusableSourceName(name)) continue
                official += VideoSource(id, name)
            }
        }
        val result = if (official.isNotEmpty()) official else fetchSourcesFromUpdates(releaseId, dubberId)
        sourcesCache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        result
    }

    private fun fetchSourcesFromUpdates(releaseId: Int, dubberId: Int): List<VideoSource> {
        val found = linkedMapOf<Int, VideoSource>()
        for (page in 0 until 5) {
            val resp = getJson("/episode/updates/$releaseId/$page") ?: break
            if (resp.optInt("code", -1) != 0) break
            val rows = resp.optJSONArray("content") ?: break
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optInt("last_episode_type_update_id", 0) != dubberId) continue
                val id = row.optInt("last_episode_source_update_id", 0)
                if (id <= 0 || found.containsKey(id)) continue
                val name = row.optString("last_episode_source_update_name").ifBlank { "Источник $id" }
                if (isUnusableSourceName(name)) continue
                found[id] = VideoSource(id, name)
            }
            if (resp.optInt("total_page_count", 1) <= page + 1) break
        }
        return found.values.toList()
    }

    /** Серии источника (листинговые URL не подписываются — подпись даёт target). */
    suspend fun fetchEpisodes(releaseId: Int, dubberId: Int, sourceId: Int): List<Episode> =
        withContext(Dispatchers.IO) {
            if (releaseId <= 0 || dubberId <= 0 || sourceId <= 0) return@withContext emptyList()
            val cacheKey = "$releaseId:$dubberId:$sourceId"
            episodesCache[cacheKey]?.let { entry ->
                val age = System.currentTimeMillis() - entry.timestamp
                val valid = if (entry.data.isEmpty()) age < NEGATIVE_TTL_MS else age < LISTING_TTL_MS
                if (valid) return@withContext entry.data
                episodesCache.remove(cacheKey)
            }
            val resp = getJson("/episode/$releaseId/$dubberId/$sourceId", mapOf("sort" to "1"))
            if (resp == null || resp.optInt("code", -1) != 0) {
                episodesCache[cacheKey] = CacheEntry(emptyList(), System.currentTimeMillis())
                return@withContext emptyList()
            }
            val out = mutableListOf<Episode>()
            val episodes = resp.optJSONArray("episodes") ?: JSONArray()
            for (i in 0 until episodes.length()) {
                val e = episodes.optJSONObject(i) ?: continue
                val url = e.optString("url").takeUnless { it.isBlank() } ?: continue
                out += Episode(
                    position = e.optInt("position", -1).takeIf { it >= 0 } ?: continue,
                    name = e.optString("name").ifBlank { "Серия" },
                    url = url,
                    iframe = e.optBoolean("iframe", false)
                )
            }
            val sorted = out.distinctBy { it.position }.sortedBy { it.position }
            episodesCache[cacheKey] = CacheEntry(sorted, System.currentTimeMillis())
            sorted
        }

    /** Подписанная ссылка серии для воспроизведения (живёт минуты — не кэшировать). */
    suspend fun fetchTargetUrl(releaseId: Int, sourceId: Int, position: Int): Episode? =
        withContext(Dispatchers.IO) {
            if (releaseId <= 0 || sourceId <= 0 || position < 0) return@withContext null
            val resp = getJson("/episode/target/$releaseId/$sourceId/$position") ?: return@withContext null
            if (resp.optInt("code", -1) != 0) return@withContext null
            val e = resp.optJSONObject("episode") ?: return@withContext null
            val url = e.optString("url").takeUnless { it.isBlank() } ?: return@withContext null
            Episode(
                position = e.optInt("position", position),
                name = e.optString("name").ifBlank { "Серия" },
                url = url,
                iframe = e.optBoolean("iframe", false)
            )
        }

    /**
     * Порядок перебора хостов при резолве: быстрые одностраничные (Libria/Sibnet/прямые)
     * раньше медленного Kodik-скрапа (3–25 запросов). Эвристика по имени — только порядок,
     * не фильтр: Kodik-варианты остаются фолбэком, когда нативный Kodik-поиск playlist не нашёл.
     */
    fun orderSourcesForResolve(sources: List<VideoSource>): List<VideoSource> =
        sources.sortedBy { if (it.name.contains("kodik", ignoreCase = true)) 1 else 0 }

    /**
     * Объединение серий хостов озвучки: список — от самого полного хоста, подсказка
     * качества каждой позиции — лучшая по всем хостам.
     */
    data class UnitedEpisodes(
        val episodes: List<Episode>,
        val bestQuality: Map<Int, String> = emptyMap()
    )

    fun unionEpisodes(perSource: List<List<Episode>>): UnitedEpisodes {
        val bestQuality = mutableMapOf<Int, String>()
        val names = mutableMapOf<Int, String>()
        for (episodes in perSource) {
            for (ep in episodes) {
                AnimeStreamResolver.qualityFromLink(ep.url)?.let { q ->
                    val cur = bestQuality[ep.position]
                    if (cur == null || hd.kinoshka.app.data.model.qualityRank(q) > hd.kinoshka.app.data.model.qualityRank(cur)) {
                        bestQuality[ep.position] = q
                    }
                }
                // Имя серии — первое непустое по хостам (подписаны по-разному).
                if (!names.containsKey(ep.position)) {
                    ep.name.takeIf { n -> n.isNotBlank() && n != "Серия" }?.let { names[ep.position] = it }
                }
            }
        }
        val richest = perSource.maxByOrNull { it.size } ?: return UnitedEpisodes(emptyList())
        val episodes = richest.distinctBy { it.position }.sortedBy { it.position }.map { ep ->
            names[ep.position]?.let { ep.copy(name = it) } ?: ep
        }
        return UnitedEpisodes(episodes, bestQuality)
    }

    // ---- фасад для AnimeStreamResolver ----

    /**
     * Все озвучки тайтла как FlatTranslation — строго по одной на озвучку:
     * title — имя команды без хоста (группировка DubGroup на шаге озвучек тогда
     * схлопывается с одноимёнными рядами других провайдеров), серии — объединение
     * хостов, конкретный хост подбирается в [resolveStream] авто-фолбэком.
     */
    suspend fun fetchFlatTranslations(shikimoriId: Int, animeTitle: String): List<FlatTranslation> =
        withContext(Dispatchers.IO) {
            val queries = linkedSetOf(animeTitle.trim())
            runCatching { queries += AnimeStreamResolver.buildAnimeSearchQueries(animeTitle) }
            val releaseId = findReleaseId(queries.toList()) ?: run {
                KLog.i(TAG, "no anixart release for \"$animeTitle\"")
                return@withContext emptyList()
            }
            val info = getJson("/release/$releaseId", mapOf("extended_mode" to "true"))
                ?.optJSONObject("release")
            if (info != null && info.optBoolean("is_play_disabled", false)) {
                KLog.i(TAG, "release $releaseId play disabled")
                return@withContext emptyList()
            }
            val dubbers = fetchDubbers(releaseId)
            if (dubbers.isEmpty()) return@withContext emptyList()
            coroutineScope {
                val semaphore = Semaphore(MAX_PARALLEL)
                dubbers.map { dubber ->
                    async {
                        semaphore.withPermit {
                            val sources = runCatching { fetchSources(releaseId, dubber.id) }.getOrDefault(emptyList())
                            if (sources.isEmpty()) return@withPermit null
                            val perSource = orderSourcesForResolve(sources).map { source ->
                                runCatching { fetchEpisodes(releaseId, dubber.id, source.id) }
                                    .getOrDefault(emptyList())
                            }.filter { it.isNotEmpty() }
                            if (perSource.isEmpty()) return@withPermit null
                            val united = unionEpisodes(perSource)
                            if (united.episodes.isEmpty()) return@withPermit null
                            FlatTranslation(
                                source = hd.kinoshka.app.data.model.AnimeSourceType.ANIXART,
                                translationId = encodeTranslationId(releaseId, dubber.id),
                                title = dubber.name,
                                type = dubberType(dubber.isSub),
                                episodes = united.episodes.map { ep ->
                                    AnimeEpisode(
                                        number = ep.position,
                                        title = ep.name,
                                        id = ep.position,
                                        maxQuality = united.bestQuality[ep.position]
                                            ?: AnimeStreamResolver.qualityFromLink(ep.url)
                                    )
                                }
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    /**
     * Играемая ссылка серии: хосты озвучки перебираются по [orderSourcesForResolve],
     * подписанный target каждого резолвится в момент вызова (подпись живёт минуты —
     * листинг её не кэширует). Первый успешно разрешившийся хост побеждает.
     */
    suspend fun resolveStream(translationId: String, episodeNumber: Int): AnimeMediaStream? =
        withContext(Dispatchers.IO) {
            val ref = decodeTranslationId(translationId) ?: return@withContext null
            val allSources = fetchSources(ref.releaseId, ref.dubberId)
            if (allSources.isEmpty()) return@withContext null
            // Legacy v1-id с конкретным хостом: пробуем только его.
            val candidates = ref.sourceId?.let { only -> allSources.filter { it.id == only } }
                ?.takeIf { it.isNotEmpty() } ?: orderSourcesForResolve(allSources)
            for (source in candidates) {
                val target = fetchTargetUrl(ref.releaseId, source.id, episodeNumber) ?: continue
                val stream = resolveEpisodeUrl(target.url, target.iframe)
                if (stream != null) return@withContext stream
                KLog.w(TAG, "host ${source.name} failed r=${ref.releaseId} ep=$episodeNumber, next")
            }
            KLog.w(TAG, "all hosts failed r=${ref.releaseId} d=${ref.dubberId} ep=$episodeNumber")
            null
        }

    private suspend fun resolveEpisodeUrl(episodeUrl: String, iframe: Boolean): AnimeMediaStream? {
        var url = if (episodeUrl.startsWith("http")) episodeUrl else "https:$episodeUrl"
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        val isKodik = host.contains("kodikplayer.com") || host.contains("kodik.info") ||
            host.contains("aniqit.com") || host.contains("anixis.com") || host.contains("aniqart.com")
        val isSibnet = host.contains("sibnet.ru")

        if (isKodik) {
            // Подпись Anixart (?d &s &ip) Kodik-механике мешает — чистим до path,
            // как делает anixapp (stripKodikQueryParams).
            val clean = url.substringBefore("?")
            val ladder = runCatching { AnimeStreamResolver.resolveKodikHls(clean) }.getOrNull()
            if (ladder.isNullOrEmpty()) return null
            val pick = QUALITY_PREFERENCE_DESC.firstOrNull { ladder.containsKey(it) }
                ?: ladder.keys.firstOrNull() ?: return null
            return AnimeMediaStream(
                url = ladder.getValue(pick),
                qualities = ladder,
                headers = AnimeStreamResolver.kodikPlaybackHeaders(),
                quality = pick
            )
        }

        if (!iframe && isDirectFile(url)) {
            return AnimeMediaStream(url = url, qualities = mapOf("Auto" to url))
        }

        // Sibnet shell.php и Libria iframe.php: скрап/сниф прямых ссылок.
        // Sibnet требует Referer — он приходит внутри ResolvedLink.headers.
        val resolved = runCatching { SmarthardApi.resolveLink(url) }.getOrNull()
        if (resolved != null) {
            val qualities = linkedMapOf<String, String>()
            AnimeStreamResolver.qualityFromLink(resolved.url, "720p")?.let { qualities[it] = resolved.url }
            if (qualities.isEmpty()) qualities["Auto"] = resolved.url
            return AnimeMediaStream(
                url = resolved.url,
                qualities = qualities,
                headers = resolved.headers,
                quality = qualities.keys.first()
            )
        }

        // Сырая ссылка без iframe-флага — честно отдаём как есть (плеер/WebView
        // разберётся); iframe-страницы без прямого видео не играем нативом.
        if (!iframe && !isSibnet) {
            KLog.w(TAG, "unresolved direct-ish url, passing through: $host")
            return AnimeMediaStream(url = url, qualities = mapOf("Auto" to url))
        }
        KLog.w(TAG, "unplayable embed without direct video: $host iframe=$iframe")
        return null
    }

    private fun isDirectFile(url: String): Boolean =
        Regex("""\.(mp4|m3u8|webm|mpd|mkv)([?#].*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** Сброс кэшей листинга (авто-retry плеера после смены озвучки/зеркала). */
    fun evictCaches() {
        releaseIdCache.clear()
        dubbersCache.clear()
        sourcesCache.clear()
        episodesCache.clear()
    }
}
