package hd.kinoshka.app.data.feed

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Прямой HLS-клип Rutube, найденный по названию тайтла. */
data class RutubeClip(
    val videoId: String,
    val hlsUrl: String,
    val thumbnailUrl: String?
)

/**
 * Источник видео-нарезок для фона карточек фида — открытый JSON API Rutube (без ключей).
 *
 * Поиск:  GET rutube.ru/api/search/video/?query=…          → results[] c id/thumbnail_src
 * Поток:  GET rutube.ru/api/play/options/{id}/?format=json → hls (прямой .m3u8)
 *
 * API полудокументированный: обязательно шлём браузерные UA/Referer (иначе 403/капча),
 * парсим защитно и всегда готовы отдать null — вызывающий код молча падает на постер.
 */
object RutubeClipSource {

    private const val TAG = "RutubeClipSource"
    private const val BASE = "https://rutube.ru"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Ищет нарезку/трейлер по названию тайтла. Перебирает варианты запросов от широкого
     * к узкому: голое русское название → оригинальное → с суффиксом «трейлер».
     * null = ничего подходящего не нашлось.
     */
    suspend fun findClip(title: String?, originalTitle: String? = null): RutubeClip? = withContext(Dispatchers.IO) {
        val queries = buildList {
            title?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            originalTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(title?.trim(), ignoreCase = true) }?.let { add(it) }
            // Суффикс — последняя попытка, часто сужает выдачу до нуля.
            title?.trim()?.takeIf { it.isNotBlank() }?.let { add("$it трейлер") }
        }.distinct()
        if (queries.isEmpty()) return@withContext null

        for (query in queries) {
            val videoId = searchVideoId(query) ?: continue
            val options = fetchPlayOptions(videoId) ?: continue
            KLog.i(TAG, "clip found for \"$query\": $videoId")
            return@withContext options
        }
        null
    }

    /** results[] → первый элемент с id и thumbnail_src. Структура ответа не стабильна,
     *  поэтому читаем и корневой массив, и обёртку results/items. */
    private fun searchVideoId(query: String): String? {
        val url = "$BASE/api/search/video/?query=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=8"
        val body = httpGet(url) ?: return null
        return runCatching {
            val json = JSONObject(body)
            val array = json.optJSONArray("results")
                ?: json.optJSONArray("items")
                ?: return@runCatching null
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim().ifBlank { item.optString("video_id").trim() }
                if (id.isNotEmpty()) return@runCatching id
            }
            null
        }.onFailure { KLog.w(TAG, "search parse failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Известная страница/embed-ссылка Rutube → HLS-манифест. null — не Rutube или резолв не удался. */
    suspend fun resolveClip(url: String): RutubeClip? {
        val videoId = videoIdFromUrl(url) ?: return null
        return fetchPlayOptions(videoId)
    }

    /** id видео из ссылок вида rutube.ru/video/&lt;id&gt;/… и rutube.ru/play/embed/&lt;id&gt;/… */
    fun videoIdFromUrl(url: String): String? =
        Regex("rutube\\.ru/(?:video|play/embed)/([0-9a-f]{32})", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)

    /** play/options → HLS-манифест + обложка. */
    private fun fetchPlayOptions(videoId: String): RutubeClip? {
        val body = httpGet("$BASE/api/play/options/$videoId/?format=json") ?: return null
        return runCatching {
            val json = JSONObject(body)
            val hls = json.optString("hls").trim()
            if (!hls.contains(".m3u8")) return@runCatching null
            RutubeClip(
                videoId = videoId,
                hlsUrl = hls,
                thumbnailUrl = json.optString("thumbnail_url").takeIf { it.startsWith("http") }
            )
        }.getOrNull()
    }

    private fun httpGet(url: String): String? = runCatching {
        httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$BASE/")
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                KLog.w(TAG, "GET $url -> ${response.code}")
                return@use null
            }
            response.body.string()
        }
    }.getOrNull()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
