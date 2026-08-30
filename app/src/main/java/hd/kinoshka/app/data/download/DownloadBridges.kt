package hd.kinoshka.app.data.download

import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.KodikMovieCandidate
import hd.kinoshka.app.data.model.MovieStreamResult
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.data.source.DdbbStreamResolver
import hd.kinoshka.app.data.source.HentaiProvider
import hd.kinoshka.app.data.source.HentaiStream
import hd.kinoshka.app.data.source.HentaiStreamResolver
import hd.kinoshka.app.data.source.MovieStreamResolver

/**
 * Фабрики запросов на скачивание из источников приложения. Держат ленивый резолв:
 * ссылки достаются только когда очередь дошла до задачи (подписи CDN живут часы).
 */
object DownloadBridges {

    /**
     * Резолвер отдаёт свой дефолт в url (у Kodik и AniLiberty это 720p — так настроен
     * онлайн-плей) и полную лестницу в qualities. Скачивание должно брать максимум
     * лестницы, а не дефолт.
     */
    fun mediaSource(stream: AnimeMediaStream): MediaDownloader.MediaSource {
        val bestKey = QUALITY_PREFERENCE_DESC.firstOrNull { stream.qualities.containsKey(it) }
        val url = bestKey?.let { stream.qualities[it] } ?: stream.url
        return MediaDownloader.MediaSource(url = url, headers = stream.headers)
    }

    private fun fromStream(stream: AnimeMediaStream) = mediaSource(stream)

    // ------------------------------------------------------------------
    // Аниме (Kodik / AniLiberty / AniLib / AniStar)
    // ------------------------------------------------------------------

    fun animeRequests(
        shikimoriId: Int,
        kinopoiskId: Int,
        animeTitle: String,
        translation: FlatTranslation
    ): List<EpisodeDownloadManager.EpisodeDownloadRequest> {
        val itemKey = animeItemKey(shikimoriId, kinopoiskId)
        return translation.episodes
            .sortedBy { it.number }
            .map { ep ->
                EpisodeDownloadManager.EpisodeDownloadRequest(
                    itemKey = itemKey,
                    title = animeTitle,
                    source = translation.source.name,
                    translationId = translation.translationId,
                    translationTitle = translation.title,
                    episodeNumber = ep.number,
                    episodeLabel = episodeLabel(ep),
                    resolve = {
                        AnimeStreamResolver.resolveStream(
                            shikimoriId, animeTitle, translation.source,
                            translation.translationId, ep.number
                        )?.let(::fromStream)
                    }
                )
            }
    }

    private fun episodeLabel(ep: AnimeEpisode): String =
        ep.title?.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: "Серия ${ep.number}"

    // ------------------------------------------------------------------
    // Хентай (прямые ссылки HentaiStream; каждая серия = своя озвучка-дорожка)
    // ------------------------------------------------------------------

    fun hentaiRequests(
        kinopoiskId: Int,
        title: String,
        provider: HentaiProvider,
        stream: HentaiStream
    ): List<EpisodeDownloadManager.EpisodeDownloadRequest> {
        val itemKey = animeItemKey(0, kinopoiskId)
        if (stream.episodes.isNotEmpty()) {
            return stream.episodes.mapIndexed { index, ep ->
                EpisodeDownloadManager.EpisodeDownloadRequest(
                    itemKey = itemKey,
                    title = title,
                    source = provider.name,
                    translationId = "hentai:${provider.name}:${ep.label}",
                    translationTitle = "${provider.displayName} · ${ep.label}",
                    episodeNumber = index + 1,
                    episodeLabel = ep.label,
                    resolve = { MediaDownloader.MediaSource(ep.url, stream.headers) }
                )
            }
        }
        return listOf(
            EpisodeDownloadManager.EpisodeDownloadRequest(
                itemKey = itemKey,
                title = title,
                source = provider.name,
                translationId = "hentai:${provider.name}",
                translationTitle = "${provider.displayName} · Фильм",
                episodeNumber = 1,
                episodeLabel = "Фильм",
                resolve = { MediaDownloader.MediaSource(stream.url, stream.headers) }
            )
        )
    }

    // ------------------------------------------------------------------
    // Фильмы и сериалы (Kodik-каталог; QOM-фильмы — готовые потоки из гонки резолверов)
    // ------------------------------------------------------------------

    /**
     * QOM-фильм: prepared-поток из MovieNativeLauncher.resolve, по запросу НА ОДНУ озвучку.
     * Раньше фабрика отдавала запросы на весь [AnimeMediaStream]-каталог разом, и скачивание
     * любой озвучки ставило в очередь все (фильм путался с сериальной моделью «озвучка×серия»).
     */
    fun qomRequest(
        kinopoiskId: Int,
        title: String,
        translationId: String,
        stream: AnimeMediaStream,
        voiceoverTitle: String
    ): EpisodeDownloadManager.EpisodeDownloadRequest {
        val itemKey = animeItemKey(0, kinopoiskId)
        return EpisodeDownloadManager.EpisodeDownloadRequest(
            itemKey = itemKey,
            title = title,
            source = AnimeSourceType.KODIK.name,
            translationId = translationId,
            translationTitle = voiceoverTitle,
            episodeNumber = 1,
            episodeLabel = "Фильм",
            resolve = { fromStream(stream) }
        )
    }

    /**
     * Сериал: по запросу на (озвучка × серия). Для Kodik-каталога резолв идёт тем же путём,
     * что и переключение серий в плеере (MovieStreamResolver.resolveEpisode); для прямого
     * ddbb-каталога ([isDirectSource]) ссылки серий — готовые CDN-url, и гнать их через
     * Kodik-скрапер нельзя (live kp=460586: «payload extraction failed» на каждой серии).
     */
    fun seriesRequests(
        kinopoiskId: Int,
        title: String,
        request: MoviePlaybackRequest,
        candidates: List<KodikMovieCandidate>,
        translationId: String,
        translationTitle: String,
        episodes: List<MovieEpisodeRef>,
        isDirectSource: Boolean = false,
        directHeaders: Map<String, String> = emptyMap()
    ): List<EpisodeDownloadManager.EpisodeDownloadRequest> {
        val itemKey = animeItemKey(0, kinopoiskId)
        val headers = directHeaders.ifEmpty { DdbbStreamResolver.directHeaders(kinopoiskId) }
        return episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            .map { ep ->
                EpisodeDownloadManager.EpisodeDownloadRequest(
                    itemKey = itemKey,
                    title = title,
                    source = AnimeSourceType.KODIK.name,
                    translationId = translationId,
                    translationTitle = translationTitle,
                    episodeNumber = offlineEpisodeNumber(ep),
                    episodeLabel = seriesEpisodeLabel(ep),
                    resolve = {
                        if (isDirectSource) {
                            candidates.firstOrNull { it.translationId == translationId }
                                ?.episodes?.firstOrNull {
                                    it.seasonNumber == ep.seasonNumber && it.episodeNumber == ep.episodeNumber
                                }?.playerUrl?.takeIf { it.isNotBlank() }
                                ?.let { MediaDownloader.MediaSource(it, headers) }
                        } else {
                            when (val result = MovieStreamResolver.resolveEpisode(request, ep, candidates, translationId)) {
                                is MovieStreamResult.Success -> fromStream(result.stream)
                                is MovieStreamResult.Unavailable -> null
                            }
                        }
                    }
                )
            }
    }

    private fun seriesEpisodeLabel(ep: MovieEpisodeRef): String =
        if (ep.seasonNumber > 0) "S%02dE%02d".format(ep.seasonNumber, ep.episodeNumber)
        else "Серия ${ep.episodeNumber}"

    /**
     * Офлайн-ключ серии не знает про сезоны, а «скачать все серии» качает все сезоны сразу:
     * S1E1 и S2E1 склеились бы в один ключ очереди и молча терялись. Пакуем сезон в номер
     * эпизода тем же способом, каким шит загрузки сортирует список серий.
     */
    private fun offlineEpisodeNumber(ep: MovieEpisodeRef): Int =
        if (ep.seasonNumber > 0) ep.seasonNumber * 1000 + ep.episodeNumber else ep.episodeNumber
}
