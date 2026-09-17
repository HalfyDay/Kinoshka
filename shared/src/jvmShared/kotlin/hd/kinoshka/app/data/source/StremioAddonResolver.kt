package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Клиент Stremio-совместимых JSON-аддонов (вариант B своих источников).
 *
 * Протокол (stremio-addon-sdk): `GET {base}/manifest.json` описывает ресурсы,
 * потоки фильма — `GET {base}/stream/movie/{imdb}.json` → `{ streams: [...] }`.
 * Берём только прямые http(s)-ссылки (`url`); торренты (`infoHash`), YouTube
 * (`ytId`), архивы и `externalUrl` (открыть в браузере) играть нативом нечем —
 * пропускаются с подсчётом в лог. Заголовки — из
 * `behaviorHints.proxyHeaders.request`, если аддон их отдал.
 *
 * v1 ограничений два: нужен IMDb ID тайтла (у аниме/18+ его нет — эти разделы
 * пропускаются), а сериалам нужны и meta-ресурс (сетка серий), и stream-ресурс.
 */
object StremioAddonResolver {
    private const val TAG = "StremioAddon"

    private const val MANIFEST_TTL_MS = 60 * 60 * 1000L
    private const val MAX_BODY_CHARS = 512 * 1024

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .proxySelector(StreamProxySelector())
            .proxyAuthenticator(StreamProxyConfig.okHttpProxyAuthenticator())
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    private val manifestCache = ConcurrentHashMap<String, CacheEntry<StremioManifest>>()

    /** Распарсенный манифест: какие ресурсы под какие типы заявлены. */
    data class StremioManifest(
        val id: String,
        val version: String,
        val name: String,
        val hasMovieStream: Boolean,
        val hasSeriesStream: Boolean = false,
        val hasSeriesMeta: Boolean = false
    )

    /** Один играбельный поток: подпись для строки пикера + ссылка + заголовки. */
    data class StremioStream(
        val label: String,
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    /** `{base}/manifest.json` — base уже нормализован ([normalizeStremioEndpoint]). */
    fun manifestUrl(base: String): String = base.trimEnd('/') + "/manifest.json"

    /** `{base}/stream/movie/{imdb}.json`. */
    fun movieStreamUrl(base: String, imdbId: String): String =
        base.trimEnd('/') + "/stream/movie/" + imdbId.trim() + ".json"

    /** `{base}/meta/series/{imdb}.json` — список серий. */
    fun seriesMetaUrl(base: String, imdbId: String): String =
        base.trimEnd('/') + "/meta/series/" + imdbId.trim() + ".json"

    /** `{base}/stream/series/{imdb}:{season}:{episode}.json` — потоки серии. */
    fun seriesStreamUrl(base: String, imdbId: String, season: Int, episode: Int): String =
        base.trimEnd('/') + "/stream/series/" + imdbId.trim() + ":$season:$episode.json"

    /** Одна серия из meta-ответа. */
    data class MetaEpisode(val season: Int, val episode: Int, val title: String?)

    /**
     * Парс `{meta: {videos: [{season, episode, title}]}}`: только нумерованные
     * серии сезонов ≥1 (спешлы season 0 вне серийной сетки пикера), без дублей,
     * по порядку. Пусто — null нет, пустой список (различаем «нет series» и «0 серий»).
     */
    fun parseMetaVideos(raw: String): List<MetaEpisode> {
        val videos = runCatching { JSONObject(raw).optJSONObject("meta")?.optJSONArray("videos") }
            .getOrNull() ?: return emptyList()
        return (0 until videos.length()).mapNotNull { i ->
            val item = videos.optJSONObject(i) ?: return@mapNotNull null
            val season = item.optInt("season", -1)
            val episode = item.optInt("episode", -1)
            if (season < 1 || episode < 1) return@mapNotNull null
            MetaEpisode(season, episode, item.optString("title").trim().takeIf { it.isNotEmpty() })
        }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** Порядок рангов для выбора лучшего потока серии. */
    private val QUALITY_ORDER = listOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "240p")

    /** Ранг качества из подписи/имени потока ("1080p • Дубляж" → "1080p"). */
    fun qualityOf(label: String, name: String): String? =
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).findAll("$name $label")
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.let { "${it}p" }

    /**
     * Чистый IMDb ID из сырого поля каталога (у KP туда прилетает мусор:
     * пробелы, префиксы, списки). Сырая строка в URL роняет запрос до отправки —
     * без видимых следов, кроме отсутствующего потока.
     */
    fun cleanImdbId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("""tt\d{7,8}""").find(raw)?.value
    }

    private fun rankOf(quality: String): Int =
        QUALITY_ORDER.indexOf(quality).takeIf { it >= 0 }
            ?: (10_000 - (quality.removeSuffix("p").toIntOrNull() ?: 0))

    /**
     * Толерантный парс манифеста: resources — строки ("stream") или объекты
     * ({name, types, idPrefixes}); типы/префиксы по умолчанию — с верхнего уровня.
     * null — не JSON или нет id.
     */
    fun parseManifest(raw: String): StremioManifest? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = json.optString("id").trim().ifEmpty { return null }
        val version = json.optString("version").trim()
        val name = json.optString("name").trim().ifEmpty { id }
        val topTypes = json.optJSONArray("types")?.stringList().orEmpty()
        val topPrefixes = json.optJSONArray("idPrefixes")?.stringList().orEmpty()
        val resources = json.optJSONArray("resources")
            ?: return StremioManifest(id, version, name, false)
        var movieStream = false
        var seriesStream = false
        var seriesMeta = false
        for (i in 0 until resources.length()) {
            val entry = resources.opt(i) ?: continue
            val resName: String
            var types = topTypes
            var prefixes = topPrefixes
            when (entry) {
                is String -> resName = entry
                is JSONObject -> {
                    resName = entry.optString("name")
                    entry.optJSONArray("types")?.stringList()?.let { types = it }
                    entry.optJSONArray("idPrefixes")?.stringList()?.let { prefixes = it }
                }
                else -> continue
            }
            // Пустые types = все типы (по спеке ресурс без фильтров матчит всё).
            // Пустые idPrefixes = любые id. Непустые должны покрывать tt (IMDb).
            if (prefixes.isNotEmpty() && prefixes.none { "tt" in it.lowercase() }) continue
            when {
                resName.equals("stream", ignoreCase = true) -> {
                    if (types.isEmpty() || types.any { it.equals("movie", ignoreCase = true) }) {
                        movieStream = true
                    }
                    if (types.isEmpty() || types.any { it.equals("series", ignoreCase = true) }) {
                        seriesStream = true
                    }
                }
                resName.equals("meta", ignoreCase = true) -> {
                    if (types.isEmpty() || types.any { it.equals("series", ignoreCase = true) }) {
                        seriesMeta = true
                    }
                }
            }
        }
        return StremioManifest(id, version, name, movieStream, seriesStream, seriesMeta)
    }

    /**
     * Парс ответа streams: только прямые http(s)-ссылки. Пропуски
     * (торренты/yt/external/архивы/битые) молча считаются — итог в логе резолва.
     */
    fun parseStreams(raw: String): Pair<List<StremioStream>, Int> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList<StremioStream>() to 0
        val arr = json.optJSONArray("streams") ?: return emptyList<StremioStream>() to 0
        val out = mutableListOf<StremioStream>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: run { skipped++; continue }
            val url = item.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                skipped++
                continue
            }
            val title = item.optString("description").trim()
                .ifEmpty { item.optString("title").trim() }
            val name = item.optString("name").trim()
            val label = when {
                title.isNotBlank() && name.isNotBlank() -> "$name • $title"
                title.isNotBlank() -> title
                name.isNotBlank() -> name
                else -> "Поток ${out.size + 1}"
            }
            out.add(StremioStream(label, url, proxyRequestHeaders(item)))
        }
        return out to skipped
    }

    /** Заголовки из behaviorHints.proxyHeaders.request (плоский string→string). */
    fun proxyRequestHeaders(stream: JSONObject): Map<String, String> {
        val hints = stream.optJSONObject("behaviorHints") ?: return emptyMap()
        val proxy = hints.optJSONObject("proxyHeaders") ?: return emptyMap()
        val req = proxy.optJSONObject("request") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in req.keys()) {
            val value = req.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun JSONArray.stringList(): List<String> =
        (0 until length()).mapNotNull { (optString(it) ?: "").trim().takeIf { s -> s.isNotEmpty() } }

    private fun fetchText(url: String, referer: String): String? {
        val req = Request.Builder().url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Kinoshka/1.0")
            .addHeader("Accept", "application/json")
            .addHeader("Referer", referer)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "GET ${url.take(90)} -> HTTP ${response.code}")
                    return null
                }
                response.body.string().take(MAX_BODY_CHARS).takeIf { it.isNotBlank() }
            }
        }.onFailure {
            KLog.w(TAG, "GET ${url.take(90)} failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** Манифест аддона (кэш час, негатив не кэшируем — «Проверить» всегда идёт в сеть). */
    suspend fun fetchManifest(
        custom: CustomSource,
        fetch: suspend (String) -> String? = ::fetchTextDefault
    ): StremioManifest? = withContext(Dispatchers.IO) {
        val base = custom.stremioBase() ?: return@withContext null
        manifestCache[base]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < MANIFEST_TTL_MS) return@withContext entry.data
            manifestCache.remove(base)
        }
        val raw = fetch(manifestUrl(base)) ?: return@withContext null
        parseManifest(raw)?.also { manifestCache[base] = CacheEntry(it, System.currentTimeMillis()) }
    }

    /**
     * Потоки фильма как SourceParse (по строке на поток — как войс-ряды EMBED-парсов).
     * null — нет IMDb ID, нет манифеста/stream-ресурса или пусто.
     * [fetch] инжектится ради тестов.
     */
    suspend fun resolveMovieParse(
        custom: CustomSource,
        imdbId: String?,
        isSeries: Boolean,
        fetch: suspend (String) -> String? = ::fetchTextDefault
    ): DdbbStreamResolver.SourceParse? = withContext(Dispatchers.IO) {
        val base = custom.stremioBase() ?: run {
            KLog.i(TAG, "${custom.id}: no endpoint — skipped")
            return@withContext null
        }
        if (isSeries) {
            return@withContext resolveSeriesParse(custom, imdbId, fetch)
        }
        val imdb = cleanImdbId(imdbId) ?: run {
            KLog.i(TAG, "${custom.id}: title has no imdb id — skipped")
            return@withContext null
        }
        val manifest = fetchManifest(custom, fetch) ?: run {
            KLog.w(TAG, "${custom.id}: manifest not loaded")
            return@withContext null
        }
        if (!manifest.hasMovieStream) {
            KLog.i(TAG, "${custom.id}: addon '${manifest.name}' has no movie stream resource — skipped")
            return@withContext null
        }
        val raw = fetch(movieStreamUrl(base, imdb)) ?: run {
            KLog.w(TAG, "${custom.id}: stream request failed for $imdb")
            return@withContext null
        }
        val (streams, skipped) = parseStreams(raw)
        if (skipped > 0) KLog.i(TAG, "${custom.id}: $skipped non-direct stream(s) skipped")
        if (streams.isEmpty()) {
            KLog.i(TAG, "${custom.id}: no playable streams for $imdb")
            return@withContext null
        }
        val rows = streams.distinctBy { it.url }.map { it.label to it.url }
        val byUrl = streams.distinctBy { it.url }.associate { it.url to it.headers }
        val first = streams.first()
        KLog.i(TAG, "${custom.id}: ${rows.size} stream(s) for $imdb")
        DdbbStreamResolver.SourceParse(
            sourceName = custom.id,
            url = first.url,
            headers = first.headers,
            qualities = mapOf("Auto" to first.url),
            voiceRows = rows,
            headersByUrl = byUrl
        )
    }

    /** Потолок серий одного тайтла: защита от тысяч эпизодов (One Piece) и DDoS аддона. */
    const val MAX_SERIES_EPISODES = 120

    /**
     * Сериал как SourceParse с треками: meta даёт сетку серий, потоки каждой серии
     * тянутся параллельно (семафор 8) и складываются в лестницу (ранг из подписи).
     * Один даб на источник (dubId `custom|<id>|stremio`, имя — из манифеста).
     * null — нет endpoint/imdb, нет series-ресурсов, пустая meta или ни одной серии
     * с потоками. [fetch] инжектится ради тестов.
     */
    suspend fun resolveSeriesParse(
        custom: CustomSource,
        imdbId: String?,
        fetch: suspend (String) -> String? = ::fetchTextDefault
    ): DdbbStreamResolver.SourceParse? = withContext(Dispatchers.IO) {
        val base = custom.stremioBase() ?: run {
            KLog.i(TAG, "${custom.id}: no endpoint — skipped")
            return@withContext null
        }
        val imdb = cleanImdbId(imdbId) ?: run {
            KLog.i(TAG, "${custom.id}: title has no imdb id — skipped")
            return@withContext null
        }
        val manifest = fetchManifest(custom, fetch) ?: run {
            KLog.w(TAG, "${custom.id}: manifest not loaded")
            return@withContext null
        }
        if (!manifest.hasSeriesStream || !manifest.hasSeriesMeta) {
            KLog.i(TAG, "${custom.id}: addon '${manifest.name}' lacks series stream+meta — skipped")
            return@withContext null
        }
        val metaRaw = fetch(seriesMetaUrl(base, imdb)) ?: run {
            KLog.w(TAG, "${custom.id}: meta request failed for $imdb")
            return@withContext null
        }
        val allVideos = parseMetaVideos(metaRaw)
        if (allVideos.isEmpty()) {
            KLog.i(TAG, "${custom.id}: meta has no episodes for $imdb")
            return@withContext null
        }
        val videos = allVideos.take(MAX_SERIES_EPISODES).also {
            if (allVideos.size > it.size) {
                KLog.w(TAG, "${custom.id}: ${allVideos.size} episodes, capped at $MAX_SERIES_EPISODES")
            }
        }
        val dubId = "custom|${custom.id}|stremio"
        val dubTitle = manifest.name.takeIf { it.isNotBlank() } ?: custom.name
        val semaphore = Semaphore(8)
        val resolved = coroutineScope {
            videos.map { video ->
                async {
                    semaphore.withPermit {
                        val raw = fetch(seriesStreamUrl(base, imdb, video.season, video.episode))
                            ?: return@async null
                        val (streams, _) = parseStreams(raw)
                        if (streams.isEmpty()) return@async null
                        video to streams
                    }
                }
            }.mapNotNull { it.await() }
        }
        if (resolved.isEmpty()) {
            KLog.i(TAG, "${custom.id}: no episode streams for $imdb")
            return@withContext null
        }
        KLog.i(TAG, "${custom.id}: ${resolved.size}/${videos.size} episode(s) for $imdb")
        val ladders = LinkedHashMap<String, Map<String, String>>()
        val headersByUrl = LinkedHashMap<String, Map<String, String>>()
        val tracks = resolved.map { (video, streams) ->
            val ladder = LinkedHashMap<String, String>()
            for (stream in streams.distinctBy { it.url }) {
                val q = qualityOf(stream.label, "") ?: "Auto"
                ladder.putIfAbsent(q, stream.url)
                headersByUrl.putIfAbsent(stream.url, stream.headers)
            }
            val best = ladder.minByOrNull { (q, _) -> if (q == "Auto") Int.MAX_VALUE else rankOf(q) }!!
            ladders[best.value] = ladder.toMap()
            hd.kinoshka.app.data.model.DdbbEpisodeTrack(
                dubId = dubId,
                dubTitle = dubTitle,
                seasonNumber = video.season,
                episodeNumber = video.episode,
                title = video.title,
                playerUrl = best.value
            )
        }
        val first = tracks.first()
        val firstLadder = ladders[first.playerUrl] ?: mapOf("Auto" to first.playerUrl)
        DdbbStreamResolver.SourceParse(
            sourceName = custom.id,
            url = first.playerUrl,
            headers = headersByUrl[first.playerUrl].orEmpty(),
            qualities = firstLadder,
            tracks = tracks,
            ladders = ladders,
            headersByUrl = headersByUrl
        )
    }

    private suspend fun fetchTextDefault(url: String): String? =
        withContext(Dispatchers.IO) { fetchText(url, url.substringBefore("manifest.json", url)) }

    /** Сброс кэша манифестов (проверка черновика в диалоге идёт мимо кэша отдельно). */
    fun evictManifestCache() {
        manifestCache.clear()
    }
}
