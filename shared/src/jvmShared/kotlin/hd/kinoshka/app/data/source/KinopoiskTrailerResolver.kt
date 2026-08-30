package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Трейлеры Кинопоиска: блок /videos у большинства тайтлов состоит из ссылок вида
 *   https://widgets.kinopoisk.ru/discovery/trailer/<id>?onlyPlayer=1&autoplay=1&cover=1
 * (site=KINOPOISK_WIDGET) — youtube/rutube там встречаются редко. Страница виджета — это
 * серверный рендер с JSON-состоянием, в котором уже лежат готовые значения:
 *   "streamUrl":"https://strm.yandex.ru/vod/.../master.m3u8?ottsessionid=...&packager=1"
 *   "img":{ "previewUrl"/"mediumPreviewUrl"/"bigPreviewUrl": { x1/x2: "//avatars.mds.yandex.net/..." } }
 * HLS с strm.yandex.ru отдаётся без сессий/печенек и играет mpv напрямую; обложка лежит
 * на avatars.mds.yandex.net (доступны из РФ без VPN).
 * JSON-состояние частично percent-encoded (%22 = "), поэтому значения вырезаем до первого
 * терминатора "%22" либо '"', с каким из них встретимся раньше.
 */
object KinopoiskTrailerResolver {

    private const val TAG = "KpTrailerResolver"

    data class WidgetTrailer(
        /** Прямой HLS-манифест для mpv. */
        val hlsUrl: String,
        /** Обложка трейлера (Yandex CDN). */
        val posterUrl: String?
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** id трейлера из ссылок виджета Кинопоиска. null — не виджет-ссылка. */
    fun trailerIdFromUrl(url: String): String? =
        Regex("widgets\\.kinopoisk\\.ru/discovery/trailer/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)

    /** Ссылка виджета → прямой поток + обложка. null — не виджет или резолв не удался. */
    suspend fun resolve(url: String): WidgetTrailer? = withContext(Dispatchers.IO) {
        val id = trailerIdFromUrl(url) ?: return@withContext null
        val html = httpGet("https://widgets.kinopoisk.ru/discovery/trailer/$id?onlyPlayer=1&autoplay=1&cover=1")
            ?: return@withContext null
        val hlsUrl = extractValue(html, marker = "streamUrl", startToken = "https://", httpsPrefix = false)
            ?.takeIf { it.contains(".m3u8") }
        if (hlsUrl == null) {
            KLog.w(TAG, "no streamUrl on widget page $id")
            return@withContext null
        }
        // bigPreviewUrl: x2 (двойная плотность) предпочтительнее x1 для карточки ~220dp.
        val afterBig = html.substringAfter("bigPreviewUrl", "")
        val posterUrl = extractValue(afterBig, marker = "x2", startToken = "//avatars", httpsPrefix = true)
            ?: extractValue(afterBig, marker = "", startToken = "//avatars", httpsPrefix = true)
        WidgetTrailer(hlsUrl = hlsUrl, posterUrl = posterUrl)
    }

    /**
     * После [marker] ищет значение, начинающееся с [startToken], и вырезает его (включая
     * токен) до ближайшего терминатора ("%22" или '"'). [httpsPrefix] добавляет "https:"
     * к значению, начинающемуся с "//" (protocol-relative URL из meta-тегов Яндекса).
     */
    private fun extractValue(html: String, marker: String, startToken: String, httpsPrefix: Boolean): String? {
        val afterMarker = if (marker.isEmpty()) html else html.substringAfter(marker, "")
        if (afterMarker.isEmpty()) return null
        val start = afterMarker.indexOf(startToken)
        if (start < 0) return null
        val rest = afterMarker.substring(start)
        var end = 0
        while (end < rest.length) {
            val c = rest[end]
            if (c == '"') break
            if (c == '%' && end + 2 < rest.length && rest[end + 1] == '2' && rest[end + 2] == '2') break
            end++
        }
        val value = rest.substring(0, end)
        if (value.length <= startToken.length) return null
        return if (httpsPrefix && value.startsWith("//")) "https:$value" else value
    }

    private fun httpGet(url: String): String? = runCatching {
        httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .header("Referer", "https://www.kinopoisk.ru/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                KLog.w(TAG, "GET $url -> ${response.code}")
                return@use null
            }
            response.body?.string()
        }
    }.getOrNull()
}
