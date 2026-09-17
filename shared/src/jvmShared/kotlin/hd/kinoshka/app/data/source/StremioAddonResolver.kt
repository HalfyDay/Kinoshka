package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
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
 * v1: только фильмы по IMDb ID. Сериалам нужен поэпизодный запрос
 * (`stream/series/{id}:{s}:{e}`), а списка серий ресурс stream не даёт —
 * такие тайтлы пропускаются (см. [resolveMovieParse]).
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

    /** Распарсенный манифест: интересен только stream-ресурс для фильмов. */
    data class StremioManifest(
        val id: String,
        val version: String,
        val name: String,
        val hasMovieStream: Boolean
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
        val resources = json.optJSONArray("resources") ?: return StremioManifest(id, version, name, false)
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
            if (!resName.equals("stream", ignoreCase = true)) continue
            // Пустые types = все типы (по спеке ресурс без фильтров матчит всё).
            val movieOk = types.isEmpty() || types.any { it.equals("movie", ignoreCase = true) }
            if (!movieOk) continue
            // Пустые idPrefixes = любые id. Непустые должны покрывать tt (IMDb).
            if (prefixes.isNotEmpty() && prefixes.none { "tt" in it.lowercase() }) continue
            return StremioManifest(id, version, name, true)
        }
        return StremioManifest(id, version, name, false)
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
     * null — сериал (v1), нет IMDb ID, нет манифеста/stream-ресурса или пусто.
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
            KLog.i(TAG, "${custom.id}: series titles not supported in v1 — skipped")
            return@withContext null
        }
        val imdb = imdbId?.trim()?.takeIf { it.isNotEmpty() } ?: run {
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

    private suspend fun fetchTextDefault(url: String): String? =
        withContext(Dispatchers.IO) { fetchText(url, url.substringBefore("manifest.json", url)) }

    /** Сброс кэша манифестов (проверка черновика в диалоге идёт мимо кэша отдельно). */
    fun evictManifestCache() {
        manifestCache.clear()
    }
}
