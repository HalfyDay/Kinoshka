package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Клиент плеера «Смотреть онлайн» Shikimori (страница /animes/{id}/watch): публичный API
 * cdnvideohub, который шикимориевский <video-player> дергает без авторизации и подписей.
 * Ключ — Shikimori anime id (агрегатор mali держит ту же нумерацию), видео хостится на
 * VK/OK CDN (okcdn.ru) и раздаётся прямыми HLS/DASH/MP4-ссылками с временными подписями.
 *
 * В каталоге только нелицензированные в РФ тайтлы: лицензированные (и весь хентай)
 * отвечают пустым 204 No Content — это нормальный отрицательный ответ, а не ошибка.
 * Параметр pub — id издателя Shikimori на cdnvideohub, зашит в разметку страницы просмотра.
 */
object ShikimoriVideoApi {
    private const val TAG = "ShikimoriVideoApi"

    private const val PLAYLIST_URL = "https://plapi.cdnvideohub.com/api/v1/player/sv/playlist"
    private const val VIDEO_URL = "https://plapi.cdnvideohub.com/api/v1/player/sv/video"
    private const val PUBLISHER_ID = "3058"
    private const val AGGREGATOR = "mali"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

    private data class CacheEntry<T>(val data: T, val timestamp: Long)
    private const val PLAYLIST_TTL_MS = 10 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 3 * 60 * 1000L

    private val playlistCache = ConcurrentHashMap<Int, CacheEntry<List<CvhItem>>>()
    private val resolvedCache = ConcurrentHashMap<String, CacheEntry<ResolvedVideo?>>()

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** Один элемент плейлиста: серия тайтла в конкретной озвучке/сабах. */
    data class CvhItem(
        val vkId: String,
        val voiceStudio: String,
        val voiceType: String,
        val season: Int,
        val episode: Int
    )

    /** Итог резолва vkId: играющая ссылка + лестница конкретных вариантов (пустая — если
     *  мастер-плейлист не скачался/не распарсился, тогда играем сам мастер). */
    data class ResolvedVideo(
        val url: String,
        val qualities: Map<String, String>
    )

    // ---- листинг ----

    /** Плейлист тайтла по Shikimori anime_id; кэш 10 мин (пустой — 3 мин). */
    suspend fun loadPlaylist(shikimoriId: Int): List<CvhItem> = withContext(Dispatchers.IO) {
        if (shikimoriId <= 0) return@withContext emptyList()
        playlistCache[shikimoriId]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val valid = if (entry.data.isEmpty()) age < NEGATIVE_TTL_MS else age < PLAYLIST_TTL_MS
            if (valid) return@withContext entry.data
            playlistCache.remove(shikimoriId)
        }

        val items = parsePlaylist(get("$PLAYLIST_URL?pub=$PUBLISHER_ID&id=$shikimoriId&aggr=$AGGREGATOR"))
        KLog.i(TAG, "loadPlaylist($shikimoriId): ${items.size} items")
        playlistCache[shikimoriId] = CacheEntry(items, System.currentTimeMillis())
        items
    }

    private fun parsePlaylist(body: String?): List<CvhItem> = runCatching {
        if (body.isNullOrBlank()) return emptyList() // 204 лицензированных/хентая — пустой ответ
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val vkId = item.optString("vkId").takeIf { it.isNotBlank() } ?: continue
                add(
                    CvhItem(
                        vkId = vkId,
                        voiceStudio = item.optString("voiceStudio"),
                        voiceType = item.optString("voiceType"),
                        season = item.optInt("season", 1),
                        // У полнометражек episode бывает 0 — считаем такой тайтл одной серией.
                        episode = item.optInt("episode", 0).takeIf { it > 0 } ?: 1
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    /** Ключ ряда озвучки: студия + тип перевода (у безымянных переводчиков студии нет). */
    fun rowKey(item: CvhItem): String = "${item.voiceStudio.lowercase()}|${item.voiceType}"

    /** Стабильный id ряда для persisted-ключей плеера (как у SmarthardApi). */
    fun translationIdOf(rowKey: String): String =
        "cvh" + (rowKey.hashCode() and 0x7fffffff)

    /** Название ряда: студия; без неё — тип перевода. */
    fun rowTitle(item: CvhItem): String = item.voiceStudio.ifBlank {
        when (item.voiceType) {
            "Субтитры" -> "Субтитры"
            "Оригинал" -> "Оригинал"
            else -> "Неизвестная озвучка"
        }
    }

    /** "sub" для сабов — пикер рисует иконку CC и подпись «Субтитры». */
    fun rowType(item: CvhItem): String =
        if (item.voiceType.equals("Субтитры", ignoreCase = true)) "sub" else "voice"

    // ---- резолв ссылки ----

    /**
     * vkId -> играющие ссылки. Основная — мастер-HLS c okcdn.ru; из него же собирается
     * лестница конкретных вариантов (RESOLUTION -> абсолютный URL варианта) для
     * переключателя качеств. okcdn не требует Referer — достаточно UA. Результат кэшируется
     * (успех 10 мин, неудача 3 мин): проба качества при листинге и последующее
     * воспроизведение серии резолвят один и тот же vkId — без кэша это дубль запросов.
     */
    suspend fun resolveVideo(vkId: String): ResolvedVideo? = withContext(Dispatchers.IO) {
        if (vkId.isBlank()) return@withContext null
        resolvedCache[vkId]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val valid = if (entry.data == null) age < NEGATIVE_TTL_MS else age < PLAYLIST_TTL_MS
            if (valid) return@withContext entry.data
            resolvedCache.remove(vkId)
        }
        val resolved = fetchVideo(vkId)
        resolvedCache[vkId] = CacheEntry(resolved, System.currentTimeMillis())
        resolved
    }

    /**
     * Выбрасывает кэш резолва: с конкретным vkId — только его, без — весь. Нужен авто-retry
     * плеера: переигранная из кэша подписанная ссылка (CDN отвечал 400) лечится только
     * свежим резолвом с новой подписью.
     */
    fun evictResolveCache(vkId: String?) {
        if (vkId == null) resolvedCache.clear() else resolvedCache.remove(vkId)
    }

    private suspend fun fetchVideo(vkId: String): ResolvedVideo? {
        val body = get("$VIDEO_URL/$vkId") ?: return null
        val hls = runCatching { JSONObject(body).optJSONObject("sources")?.optString("hlsUrl") }
            .getOrNull()?.takeIf { it.startsWith("http") }
            ?: run {
                KLog.w(TAG, "resolveVideo($vkId): no hlsUrl in response")
                return null
            }
        return ResolvedVideo(url = hls, qualities = parseMasterVariants(hls))
    }

    /** Варианты из мастер-плейлиста: "720p" -> URL уровня; одинаковые высоты — первый. */
    private suspend fun parseMasterVariants(masterUrl: String): Map<String, String> {
        val text = get(masterUrl) ?: return emptyMap()
        val variants = LinkedHashMap<String, String>()
        var pendingHeight = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingHeight = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val height = pendingHeight
                    pendingHeight = 0
                    if (height <= 0) continue
                    val absolute = runCatching { java.net.URI(masterUrl).resolve(line).toString() }
                        .getOrNull() ?: continue
                    variants.putIfAbsent("${height}p", absolute)
                }
            }
        }
        if (variants.isEmpty()) {
            KLog.d(TAG, "parseMasterVariants: no RESOLUTION levels in master")
        }
        return variants
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful) {
                KLog.w(TAG, "GET $url -> HTTP ${response.code}")
                return@use null
            }
            body.string()
        }
    }.getOrNull()
}
