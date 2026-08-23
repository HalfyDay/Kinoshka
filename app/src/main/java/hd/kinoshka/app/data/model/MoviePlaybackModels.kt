package hd.kinoshka.app.data.model

import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.data.local.UserFilmStatus
import kotlinx.serialization.Serializable

@Serializable
enum class NativePlaybackMode {
    ANIME,
    MOVIE_SERIES,
    QUALITY_ONLY_MOVIE
}

@Serializable
enum class MovieContentKind {
    MOVIE,
    SERIES,
    UNKNOWN
}

@Serializable
data class MoviePlaybackRequest(
    val kinopoiskId: Int?,
    val imdbId: String?,
    val titles: List<String>,
    val year: Int?,
    val kind: MovieContentKind
)

@Serializable
data class MovieEpisodeRef(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val playerUrl: String
) {
    val playerEpisodeKey: Int get() = seasonNumber * 100_000 + episodeNumber
}

@Serializable
data class KodikMovieCandidate(
    val sourceIndex: Int,
    val kinopoiskId: Int?,
    val imdbId: String?,
    val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val kind: MovieContentKind,
    val translationId: String?,
    val translationTitle: String?,
    val topLevelPlayerUrl: String?,
    val episodes: List<MovieEpisodeRef>
)

@Serializable
data class MovieSeriesPlaybackContext(
    val request: MoviePlaybackRequest,
    val candidates: List<KodikMovieCandidate>,
    val episodes: List<MovieEpisodeRef>,
    val currentEpisode: MovieEpisodeRef,
    val kinopoiskId: Int,
    val displayTitle: String
)

enum class MoviePlaybackFailure {
    NO_PROVIDER_RESULTS,
    NO_MATCHING_RESULTS,
    NO_PLAYABLE_REFERENCES,
    STREAM_EXTRACTION_FAILED,
    NETWORK_ERROR,
    PROVIDER_ERROR;

    fun userMessage(): String = when (this) {
        NO_PROVIDER_RESULTS -> "Kodik не нашёл подходящий источник"
        NO_MATCHING_RESULTS -> "Не найдено подходящее видео"
        NO_PLAYABLE_REFERENCES -> "У источника нет ссылки для нативного плеера"
        STREAM_EXTRACTION_FAILED -> "Не удалось подготовить видеопоток Kodik"
        NETWORK_ERROR -> "Ошибка сети при обращении к Kodik"
        PROVIDER_ERROR -> "Kodik временно недоступен"
    }
}

sealed interface MovieCatalogResult {
    data class Available(val candidates: List<KodikMovieCandidate>) : MovieCatalogResult
    data class Unavailable(val reason: MoviePlaybackFailure) : MovieCatalogResult
}

sealed interface MovieStreamResult {
    data class Success(
        val stream: AnimeMediaStream,
        val episode: MovieEpisodeRef? = null
    ) : MovieStreamResult

    data class Unavailable(val reason: MoviePlaybackFailure) : MovieStreamResult
}

fun canonicalSeriesEpisodes(candidates: List<KodikMovieCandidate>): List<MovieEpisodeRef> =
    candidates.flatMap { it.episodes }
        .distinctBy { it.seasonNumber to it.episodeNumber }
        .sortedWith(compareBy(MovieEpisodeRef::seasonNumber, MovieEpisodeRef::episodeNumber))

fun selectInitialSeriesEpisode(
    episodes: List<MovieEpisodeRef>,
    profile: UserFilmProfile?
): MovieEpisodeRef? {
    if (episodes.isEmpty()) return null
    val sorted = episodes.sortedWith(compareBy(MovieEpisodeRef::seasonNumber, MovieEpisodeRef::episodeNumber))
    val completed = profile?.status == UserFilmStatus.COMPLETED
    val requestedSeason = if (!completed) profile?.watchedSeasons?.takeIf { it > 0 } ?: 1 else 1
    val requestedEpisode = if (!completed) profile?.watchedEpisodes?.takeIf { it > 0 } ?: 1 else 1

    return sorted.firstOrNull { it.seasonNumber == requestedSeason && it.episodeNumber == requestedEpisode }
        ?: sorted.filter { it.seasonNumber == requestedSeason }
            .minWithOrNull(compareBy<MovieEpisodeRef> { kotlin.math.abs(it.episodeNumber - requestedEpisode) }.thenBy { it.episodeNumber })
        ?: sorted.firstOrNull { it.seasonNumber == 1 && it.episodeNumber == 1 }
        ?: sorted.first()
}
