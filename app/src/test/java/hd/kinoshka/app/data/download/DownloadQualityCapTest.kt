package hd.kinoshka.app.data.download

import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.pickCappedQualityKey
import hd.kinoshka.app.data.model.pickCappedQualityUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Потолок качества из диалога «Качество загрузки»: null = максимум лестницы,
 * иначе лучший доступный ранг не выше выбранного.
 */
class DownloadQualityCapTest {

    private val ladder = linkedMapOf(
        "1080p" to "https://cdn/x/1080.m3u8",
        "720p" to "https://cdn/x/720.m3u8",
        "480p" to "https://cdn/x/480.m3u8"
    )

    @Test
    fun `null picks best rung`() {
        assertEquals("https://cdn/x/1080.m3u8", pickCappedQualityUrl(ladder, null))
    }

    @Test
    fun `exact cap rank is honored`() {
        assertEquals("https://cdn/x/720.m3u8", pickCappedQualityUrl(ladder, "720p"))
    }

    @Test
    fun `cap above ladder max falls back to ladder max`() {
        assertEquals("https://cdn/x/1080.m3u8", pickCappedQualityUrl(ladder, "2160p"))
    }

    @Test
    fun `cap below ladder min picks nearest available above`() {
        val hdOnly = linkedMapOf(
            "1080p" to "https://cdn/x/1080.m3u8",
            "720p" to "https://cdn/x/720.m3u8"
        )
        assertEquals("https://cdn/x/720.m3u8", pickCappedQualityUrl(hdOnly, "480p"))
    }

    @Test
    fun `empty ladder returns null`() {
        assertNull(pickCappedQualityUrl(emptyMap(), "720p"))
    }

    @Test
    fun `unknown cap label behaves like max`() {
        assertEquals("https://cdn/x/1080.m3u8", pickCappedQualityUrl(ladder, "Auto"))
    }

    @Test
    fun `key variant mirrors url variant`() {
        assertEquals("1080p", pickCappedQualityKey(ladder, null))
        assertEquals("720p", pickCappedQualityKey(ladder, "720p"))
        assertEquals("1080p", pickCappedQualityKey(ladder, "2160p"))
        assertEquals("480p", pickCappedQualityKey(ladder, "480p"))
        assertEquals(null, pickCappedQualityKey(emptyMap(), "720p"))
    }

    @Test
    fun `mediaSource keeps user cap when ladder falls back above it`() {
        // Лестница без 360p: URL — ближайший выше (720p), но потолок 360p едет дальше
        // и давит HLS-вариант внутри мастера, иначе скачался бы максимум.
        val stream = AnimeMediaStream(
            url = "https://cdn/x/1080.m3u8",
            qualities = ladder,
            quality = "1080p"
        )
        val capped = DownloadBridges.mediaSource(stream, "360p")
        assertEquals("https://cdn/x/480.m3u8", capped.url)
        assertEquals("480p", capped.quality)
        assertEquals("360p", capped.qualityCap)
    }

    @Test
    fun `mediaSource exact cap rung wins`() {
        val full = ladder + ("360p" to "https://cdn/x/360.m3u8")
        val stream = AnimeMediaStream(url = "https://cdn/x/1080.m3u8", qualities = full)
        val capped = DownloadBridges.mediaSource(stream, "360p")
        assertEquals("https://cdn/x/360.m3u8", capped.url)
        assertEquals("360p", capped.quality)
        assertEquals("360p", capped.qualityCap)
    }

    @Test
    fun `mediaSource without cap keeps max and null cap`() {
        val stream = AnimeMediaStream(url = "https://cdn/x/1080.m3u8", qualities = ladder)
        val best = DownloadBridges.mediaSource(stream, null)
        assertEquals("https://cdn/x/1080.m3u8", best.url)
        assertEquals("1080p", best.quality)
        assertNull(best.qualityCap)
    }

    @Test
    fun `directCappedSource without catalog keeps url but carries cap`() {
        // Без свежего turbo-каталога лестницы нет: отдаём исходный URL как есть,
        // но потолок сохраняем — HLS-мастер по нему выберет низкий вариант.
        val request = MoviePlaybackRequest(
            kinopoiskId = null,
            imdbId = null,
            titles = listOf("Тест"),
            year = null,
            kind = MovieContentKind.MOVIE
        )
        val src = DownloadBridges.directCappedSource(
            request,
            "https://cdn.example/stream/master.m3u8",
            emptyMap(),
            "360p"
        )
        assertEquals("https://cdn.example/stream/master.m3u8", src.url)
        assertNull(src.quality)
        assertEquals("360p", src.qualityCap)
    }

    @Test
    fun `expired signature errors are detected including 410`() {
        // Live-кейс: все сегменты фильма отвечали HTTP 410, а чистка кэша ловила
        // только префикс «HTTP 40» — очередь молотила мёртвые URL на 0%.
        assertEquals(true, MediaDownloader.isSignatureGoneError(MediaDownloader.DownloadException("Сегмент 0: HTTP 410")))
        assertEquals(true, MediaDownloader.isSignatureGoneError(MediaDownloader.DownloadException("Сегмент 5: HTTP 403")))
        assertEquals(true, MediaDownloader.isSignatureGoneError(MediaDownloader.DownloadException("HTTP 404")))
        assertEquals(false, MediaDownloader.isSignatureGoneError(MediaDownloader.DownloadException("Сегмент 2: HTTP 500")))
        assertEquals(false, MediaDownloader.isSignatureGoneError(MediaDownloader.DownloadException("Сегмент 3 пуст")))
    }

    @Test
    fun `any 4xx evicts cached resolve`() {
        assertEquals(true, MediaDownloader.isHttpClientError(MediaDownloader.DownloadException("HTTP 410")))
        assertEquals(true, MediaDownloader.isHttpClientError(MediaDownloader.DownloadException("Сегмент 1/100: Сегмент 0: HTTP 403")))
        assertEquals(false, MediaDownloader.isHttpClientError(MediaDownloader.DownloadException("HTTP 500")))
        assertEquals(false, MediaDownloader.isHttpClientError(MediaDownloader.DownloadException("Пустой ответ сервера")))
    }
}
