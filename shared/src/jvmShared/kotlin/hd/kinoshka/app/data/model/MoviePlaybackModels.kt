package hd.kinoshka.app.data.model

import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.data.local.UserFilmStatus
import kotlinx.serialization.Serializable

@Serializable
enum class NativePlaybackMode {
    ANIME,
    MOVIE_SERIES,
    QUALITY_ONLY_MOVIE,
    /**
     * Player opens instantly with metadata only; the stream is resolved in the background
     * (MovieNativeLauncher) while the player shows its loading indicator. On success the
     * activity re-applies itself as QUALITY_ONLY_MOVIE or MOVIE_SERIES; on failure it shows
     * a retryable error card.
     */
    PENDING_MOVIE
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

/**
 * One playable entry of a ddbb/turbo serial config: a single episode of a single dub.
 * The turbo blob is a flat (dub × episode) array; [dubId]/[dubTitle] come from the entry's
 * cleaned "title" and S/E numbers from its "t1" label ("S05E07 - Name").
 */
@Serializable
data class DdbbEpisodeTrack(
    val dubId: String,
    val dubTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    /** Episode name from the t1 label ("S05E07 - …"), null when the entry carries none. */
    val title: String? = null,
    /** Best-quality direct CDN url of this dub/episode. */
    val playerUrl: String
)

@Serializable
data class MovieSeriesPlaybackContext(
    val request: MoviePlaybackRequest,
    val candidates: List<KodikMovieCandidate>,
    val episodes: List<MovieEpisodeRef>,
    val currentEpisode: MovieEpisodeRef,
    val kinopoiskId: Int,
    val displayTitle: String,
    /**
     * Direct-link source (ddbb/turbo): episode refs carry ready CDN urls that must load as-is
     * with [directHeaders] instead of going through Kodik HLS extraction. Dub ids in candidates
     * match [DdbbEpisodeTrack.dubId]; concrete quality ladders are served on demand by
     * DdbbStreamResolver so the intent extra stays small.
     */
    val isDirectSource: Boolean = false,
    val directHeaders: Map<String, String> = emptyMap()
)

/**
 * Process-local handoff of series playback contexts. A full context (every dub's episode list)
 * serializes to hundreds of KB — Rick and Morty hit 496KB — which overflows the Intent binder
 * transaction (TransactionTooLargeException → the Watch button silently fell back to the web
 * player) and froze the main thread during JSON encoding. The context now travels through this
 * map keyed by kinopoisk id; the intent carries only the id (plus a small-JSON fallback).
 */
object MovieSeriesContextStore {
    private val contexts = java.util.concurrent.ConcurrentHashMap<Int, MovieSeriesPlaybackContext>()

    fun put(context: MovieSeriesPlaybackContext) {
        if (context.kinopoiskId > 0) contexts[context.kinopoiskId] = context
    }

    fun get(kinopoiskId: Int): MovieSeriesPlaybackContext? =
        kinopoiskId.takeIf { it > 0 }?.let { contexts[it] }

    fun remove(kinopoiskId: Int) {
        if (kinopoiskId > 0) contexts.remove(kinopoiskId)
    }
}

/**
 * Process-local handoff for PENDING_MOVIE launches: the player activity opens before any
 * stream exists, so the resolve request (titles/ids/kind) travels through this map instead
 * of the intent; the intent carries only the kinopoisk lookup id. Removed on consume.
 */
object PendingMovieRequestStore {
    data class PendingMovieLaunch(
        val request: MoviePlaybackRequest,
        val displayTitle: String
    )

    private val launches = java.util.concurrent.ConcurrentHashMap<Int, PendingMovieLaunch>()

    fun put(kinopoiskId: Int, launch: PendingMovieLaunch) {
        if (kinopoiskId > 0) launches[kinopoiskId] = launch
    }

    fun get(kinopoiskId: Int): PendingMovieLaunch? =
        kinopoiskId.takeIf { it > 0 }?.let { launches[it] }

    fun remove(kinopoiskId: Int) {
        if (kinopoiskId > 0) launches.remove(kinopoiskId)
    }
}

/**
 * In-process handoff of fully prepared movie dub streams. Keeping these out of Intent extras
 * avoids the binder-size limit while ensuring a picker row never triggers a second resolve.
 */
object MovieVoiceoverStreamStore {
    private val streams = java.util.concurrent.ConcurrentHashMap<Int, Map<String, AnimeMediaStream>>()

    fun put(kinopoiskId: Int, value: Map<String, AnimeMediaStream>) {
        if (kinopoiskId > 0) streams[kinopoiskId] = value
    }

    /** Drops a single prepared dub (dead-url retry): the rest keep their instant switches. */
    fun remove(kinopoiskId: Int, translationId: String) {
        if (kinopoiskId <= 0) return
        val remaining = streams[kinopoiskId] ?: return
        if (remaining.containsKey(translationId)) {
            streams[kinopoiskId] = remaining - translationId
        }
    }

    fun get(kinopoiskId: Int): Map<String, AnimeMediaStream> =
        kinopoiskId.takeIf { it > 0 }?.let { streams[it] }.orEmpty()
}

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
        val episode: MovieEpisodeRef? = null,
        /** Voiceover options for the player's dropdown: title + a resolvable link each. */
        val translations: List<FlatTranslation> = emptyList()
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
