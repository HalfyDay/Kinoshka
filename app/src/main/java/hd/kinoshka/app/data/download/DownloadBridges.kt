package hd.kinoshka.app.data.download

import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.KodikMovieCandidate
import hd.kinoshka.app.data.model.MovieStreamResult
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.data.source.HentaiProvider
import hd.kinoshka.app.data.source.HentaiStream
import hd.kinoshka.app.data.source.HentaiStreamResolver
import hd.kinoshka.app.data.source.MovieStreamResolver

/**
 * Фабрики запросов на скачивание из источников приложения. Держат ленивый резолв:
 * ссылки достаются только когда очередь дошла до задачи (подписи CDN живут часы).
 */
object DownloadBridges {

    private fun fromStream(stream: AnimeMediaStream) =
        MediaDownloader.MediaSource(url = stream.url, headers = stream.headers)

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

    /** QOM-фильм: prepared-потоки из MovieNativeLauncher.resolve, по запросу на озвучку. */
    fun qomRequests(
        kinopoiskId: Int,
        title: String,
        preparedStreams: Map<String, AnimeMediaStream>,
        voiceoverTitles: Map<String, String>
    ): List<EpisodeDownloadManager.EpisodeDownloadRequest> {
        val itemKey = animeItemKey(0, kinopoiskId)
        return preparedStreams.map { (trId, stream) ->
            EpisodeDownloadManager.EpisodeDownloadRequest(
                itemKey = itemKey,
                title = title,
                source = AnimeSourceType.KODIK.name,
                translationId = trId,
                translationTitle = voiceoverTitles[trId] ?: trId,
                episodeNumber = 1,
                episodeLabel = "Фильм",
                resolve = { fromStream(stream) }
            )
        }
    }

    /**
     * Сериал (Kodik-каталог): по запросу на (озвучка × серия). Резолв идёт тем же путём,
     * что и переключение серий в плеере (MovieStreamResolver.resolveEpisode).
     */
    fun seriesRequests(
        kinopoiskId: Int,
        title: String,
        request: MoviePlaybackRequest,
        candidates: List<KodikMovieCandidate>,
        translationId: String,
        translationTitle: String,
        episodes: List<MovieEpisodeRef>
    ): List<EpisodeDownloadManager.EpisodeDownloadRequest> {
        val itemKey = animeItemKey(0, kinopoiskId)
        return episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            .map { ep ->
                EpisodeDownloadManager.EpisodeDownloadRequest(
                    itemKey = itemKey,
                    title = title,
                    source = AnimeSourceType.KODIK.name,
                    translationId = translationId,
                    translationTitle = translationTitle,
                    episodeNumber = ep.episodeNumber,
                    episodeLabel = seriesEpisodeLabel(ep),
                    resolve = {
                        when (val result = MovieStreamResolver.resolveEpisode(request, ep, candidates, translationId)) {
                            is MovieStreamResult.Success -> fromStream(result.stream)
                            is MovieStreamResult.Unavailable -> null
                        }
                    }
                )
            }
    }

    private fun seriesEpisodeLabel(ep: MovieEpisodeRef): String =
        if (ep.seasonNumber > 0) "S%02dE%02d".format(ep.seasonNumber, ep.episodeNumber)
        else "Серия ${ep.episodeNumber}"
}
