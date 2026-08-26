package hd.kinoshka.app.data.playback

import android.util.Log
import hd.kinoshka.app.data.local.CachedMovieVoiceover
import hd.kinoshka.app.data.local.MovieVoiceoverCache
import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieCatalogResult
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MoviePlaybackFailure
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.KodikMovieCandidate
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MovieStreamResult
import hd.kinoshka.app.data.model.canonicalSeriesEpisodes
import hd.kinoshka.app.data.model.selectInitialSeriesEpisode
import hd.kinoshka.app.data.source.DdbbStreamResolver
import hd.kinoshka.app.data.source.DdbbStreamResolver.DdbbStream
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.data.source.MovieStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll

/**
 * Единый резолв нативного запуска фильма/сериала: гонка Kodik ↔ ddbb и сборка всего,
 * что нужно плееру для старта. Извлечён из DetailsScreen (там логика была зашита в
 * composable), чтобы и страница деталей, и фоновый резолв в уже открытом плеере
 * (PENDING_MOVIE) шли через один код вместо расползающихся копий.
 */
object MovieNativeLauncher {

    private const val TAG = "MovieNativeLauncher"

    /** Всё, что плееру нужно для немедленного старта воспроизведения. */
    sealed interface NativeLaunchPayload {
        /** Фильм одним потоком + озвучки для выпадающего списка (Kodik / ddbb / без структуры серий). */
        data class QualityOnlyMovie(
            val stream: AnimeMediaStream,
            val translations: List<FlatTranslation>,
            /** Prepared, per-dub stream ladders. Only these rows are shown in the picker. */
            val preparedStreams: Map<String, AnimeMediaStream> = emptyMap(),
        ) : NativeLaunchPayload

        /** Сериал со структурой сезон×серия×озвучка — контекст несёт готовые ссылки. */
        data class MovieSeries(
            val context: MovieSeriesPlaybackContext,
            val stream: AnimeMediaStream
        ) : NativeLaunchPayload

        data class Failed(val reason: MoviePlaybackFailure) : NativeLaunchPayload
    }

    /**
     * Полный резолв: сериалы — каталог Kodik против структурированного turbo, остальное —
     * гонка потоков. Возвращает проигрываемый результат или причину отказа.
     *
     * [stateStore] включает персистентный кэш объединённого списка озвучек: без него состав
     * dropdown'а зависел от того, какой провайдер успел за grace-окно ("разный список озвучек
     * при разных запусках").
     */
    suspend fun resolve(
        request: MoviePlaybackRequest,
        profile: UserFilmProfile?,
        stateStore: UserStateStore? = null,
    ): NativeLaunchPayload =
        withContext(Dispatchers.IO) {
            if (request.kind == MovieContentKind.SERIES) {
                resolveSeries(request, profile)
            } else {
                resolveMovie(request, stateStore)
            }
        }

    private suspend fun resolveSeries(request: MoviePlaybackRequest, profile: UserFilmProfile?): NativeLaunchPayload = coroutineScope {
        val ddbbSeriesDeferred = async {
            request.kinopoiskId?.takeIf { it > 0 }?.let { kpId ->
                runCatching { DdbbStreamResolver.resolveMovieStream(kpId) }
                    .onFailure { Log.w(TAG, "ddbb series race failed", it) }
                    .getOrNull()
            }
        }
        val catalogDeferred = async { MovieStreamResolver.loadCatalog(request) }
        when (val outcome = awaitFirstSeriesOutcome(catalogDeferred, ddbbSeriesDeferred)) {
            is SeriesOutcome.FromKodik -> {
                val catalog = outcome.catalog
                val episodes = canonicalSeriesEpisodes(catalog.candidates)
                // find-player-discovered rows expose only a whole-title player link; present them
                // as a single S1E1 entry so native playback can start.
                val effectiveEpisodes = episodes.ifEmpty {
                    listOf(MovieEpisodeRef(1, 1, "Серия 1", catalog.candidates.firstOrNull()?.topLevelPlayerUrl.orEmpty()))
                }
                val initialEpisode = selectInitialSeriesEpisode(effectiveEpisodes, profile)
                val result = initialEpisode?.let {
                    MovieStreamResolver.resolveEpisode(request, it, catalog.candidates)
                }
                if (result is MovieStreamResult.Success && initialEpisode != null) {
                    // One voiceover entry per Kodik dub found in the catalog.
                    val voiceovers = catalog.candidates
                        .filter { !it.translationId.isNullOrBlank() }
                        .distinctBy { it.translationId }
                        .map { candidate ->
                            FlatTranslation(
                                source = AnimeSourceType.KODIK,
                                translationId = candidate.translationId!!,
                                title = candidate.translationTitle ?: "Озвучка ${candidate.translationId}",
                                episodes = emptyList()
                            )
                        }
                    val seriesContext = MovieSeriesPlaybackContext(
                        request = request,
                        candidates = catalog.candidates,
                        episodes = effectiveEpisodes,
                        currentEpisode = initialEpisode,
                        kinopoiskId = request.kinopoiskId ?: 0,
                        displayTitle = request.titles.firstOrNull() ?: "Фильм"
                    )
                    NativeLaunchPayload.MovieSeries(seriesContext, result.stream)
                } else {
                    val reason = (result as? MovieStreamResult.Unavailable)?.reason
                        ?: MoviePlaybackFailure.NO_PLAYABLE_REFERENCES
                    Log.i(TAG, "series kodik unplayable: $reason")
                    NativeLaunchPayload.Failed(reason)
                }
            }
            is SeriesOutcome.FromDdbb -> {
                val harvested = outcome.stream
                Log.i(TAG, "Series native via ddbb/${harvested.sourceName}")
                val context = buildDdbbSeriesContext(request, harvested, profile)
                if (context != null) {
                    val ep = context.currentEpisode
                    val stream = AnimeMediaStream(
                        url = ep.playerUrl,
                        headers = harvested.headers,
                        qualities = DdbbStreamResolver.directQualities(context.kinopoiskId, ep.playerUrl).orEmpty()
                    )
                    NativeLaunchPayload.MovieSeries(context, stream)
                } else {
                    // Embed without episode structure: voiceover-only playback (ddbb rows only —
                    // the series race never carried a Kodik stream to merge).
                    NativeLaunchPayload.QualityOnlyMovie(
                        AnimeMediaStream(
                            url = harvested.url,
                            headers = harvested.headers,
                            qualities = harvested.qualities
                        ),
                        stableMovieTranslations(request, harvested, emptyList())
                    )
                }
            }
            is SeriesOutcome.Failed -> NativeLaunchPayload.Failed(outcome.reason)
        }
    }

    private suspend fun resolveMovie(request: MoviePlaybackRequest, stateStore: UserStateStore?): NativeLaunchPayload = coroutineScope {
        val ddbbDeferred = async {
            request.kinopoiskId?.takeIf { it > 0 }?.let { kpId ->
                runCatching { DdbbStreamResolver.resolveMovieStream(kpId) }
                    .onFailure { Log.w(TAG, "ddbb race failed", it) }
                    .getOrNull()
            }
        }
        val kodikDeferred = async { MovieStreamResolver.resolveMovie(request) }
        when (val outcome = awaitFirstMovieOutcome(kodikDeferred, ddbbDeferred)) {
            is MovieOutcome.FromKodik -> {
                // The loser is NOT cancelled by the race anymore: a short grace window lets BOTH
                // dub catalogs feed the dropdown. Winner-dependent lists (kodik-only on one
                // launch, merged on the next) were the "different voiceovers every run" bug.
                // A session cache is only a fallback, never a reason to skip the other provider:
                // doing so left a previously Kodik-only list frozen at 720p even when Turbo's
                // 1080p ladder was available on this launch.
                val ddbbStream = kotlinx.coroutines.withTimeoutOrNull(DDBB_GRACE_MS) {
                    runCatching { ddbbDeferred.await() }.getOrNull()
                }
                ddbbDeferred.cancel()
                val translations = stableMovieTranslations(request, ddbbStream, outcome.result.translations, stateStore)
                readyQualityMovie(outcome.result.stream, translations, request.kinopoiskId)
            }
            is MovieOutcome.FromDdbb -> {
                val kodikTranslations = kotlinx.coroutines.withTimeoutOrNull(KODIK_GRACE_MS) {
                    runCatching { kodikDeferred.await() }.getOrNull()
                }?.let { result -> (result as? MovieStreamResult.Success)?.translations.orEmpty() }.orEmpty()
                kodikDeferred.cancel()
                val translations = stableMovieTranslations(request, outcome.stream, kodikTranslations, stateStore)
                readyQualityMovie(
                    AnimeMediaStream(url = outcome.stream.url, headers = outcome.stream.headers, qualities = outcome.stream.qualities),
                    translations,
                    request.kinopoiskId
                )
            }
            is MovieOutcome.Failed -> {
                ddbbDeferred.cancel()
                kodikDeferred.cancel()
                NativeLaunchPayload.Failed(outcome.failure.reason)
            }
        }
    }

    /** Resolve every offered dub before opening PlayerActivity; failed rows are not advertised. */
    private suspend fun readyQualityMovie(
        launchStream: AnimeMediaStream,
        translations: List<FlatTranslation>,
        kinopoiskId: Int?,
    ): NativeLaunchPayload.QualityOnlyMovie = coroutineScope {
        val ready = translations.map { translation ->
            async {
                val rawUrl = translation.episodes.firstOrNull()?.link.orEmpty()
                if (rawUrl.isBlank()) return@async null
                // Turbo CDN paths are often extensionless; the resolver's per-dub ladder is
                // the authoritative marker that this is already a direct stream.
                val turboLadder = DdbbStreamResolver.cachedLadderFor(rawUrl)
                val direct = turboLadder != null || rawUrl.contains(".mp4", true) || rawUrl.contains(".m3u8", true) || rawUrl.contains(".webm", true)
                val stream = if (direct) {
                    val ladder = turboLadder.orEmpty()
                    AnimeMediaStream(rawUrl, ladder, launchStream.headers)
                } else {
                    val ladder = runCatching {
                        AnimeStreamResolver.resolveKodikHls(AnimeStreamResolver.absoluteKodikUrl(rawUrl))
                    }.getOrDefault(emptyMap())
                    val url = listOf("1080p", "720p", "480p", "360p", "240p")
                        .firstNotNullOfOrNull { ladder[it] } ?: ladder.values.firstOrNull()
                    url?.let { AnimeMediaStream(it, ladder, AnimeStreamResolver.kodikPlaybackHeaders()) }
                } ?: return@async null
                translation to stream
            }
        }.awaitAll().filterNotNull()
        val streams = ready.associate { it.first.translationId to it.second }
        val rows = ready.map { it.first }
        val initialRow = rows.firstOrNull { streams[it.translationId]?.url == launchStream.url }
        val initial = initialRow?.let { streams[it.translationId] }
            ?: streams[rows.firstOrNull()?.translationId]
            ?: launchStream
        val orderedRows = initialRow?.let { first -> listOf(first) + rows.filterNot { it.translationId == first.translationId } } ?: rows
        NativeLaunchPayload.QualityOnlyMovie(initial, orderedRows, streams)
    }

    /** Merged voiceover list served verbatim within the session TTL — runs stay identical. */
    private fun cachedMovieTranslations(request: MoviePlaybackRequest): List<FlatTranslation>? =
        translationCacheKey(request)?.let { key ->
            translationCache[key]
                ?.takeIf { System.currentTimeMillis() - it.second < MOVIE_TRANSLATIONS_TTL_MS }
                ?.first
        }

    /**
     * Deterministic voiceover list for a movie, independent of which provider won the race:
     * ddbb turbo rows first (cleaned titles, ready CDN urls), then unique Kodik rows sorted by
     * title. Within a session cache window relaunches serve the EXACT same list — fresh blob
     * decodes and race timing can no longer reshuffle the dropdown between runs.
     *
     * With [stateStore] the merge is UNIONED with the persisted list of previous launches
     * (24h freshness): a provider that missed its grace window this time no longer shrinks the
     * dropdown — rows only ever get added, never lost between launches.
     */
    private fun stableMovieTranslations(
        request: MoviePlaybackRequest,
        ddbbStream: DdbbStream?,
        kodikTranslations: List<FlatTranslation>,
        stateStore: UserStateStore? = null,
    ): List<FlatTranslation> {
        val persistKey = translationCacheKey(request)
        val sessionTranslations = persistKey?.let { key ->
            translationCache[key]?.takeIf {
                System.currentTimeMillis() - it.second < MOVIE_TRANSLATIONS_TTL_MS
            }?.first
        }.orEmpty()
        val persistedCache = persistKey?.let { pk ->
            stateStore?.getMovieVoiceoverCache(pk)?.takeIf { cache ->
                cache.rows.isNotEmpty() && System.currentTimeMillis() - cache.savedAtMs < PERSISTED_VOICEOVERS_TTL_MS
            }
        }
        val persistedTranslations = persistedCache?.rows.orEmpty().map { row ->
            FlatTranslation(
                source = AnimeSourceType.KODIK,
                translationId = row.id,
                title = row.title,
                episodes = listOf(AnimeEpisode(number = 1, title = row.title, link = row.link))
            )
        }

        val ddbbRows = ddbbStream?.translations.orEmpty().map { (title, url) ->
            FlatTranslation(
                source = AnimeSourceType.KODIK,
                translationId = title,
                title = title,
                episodes = listOf(AnimeEpisode(number = 1, title = title, link = url))
            )
        }
        val merged = (sessionTranslations + persistedTranslations + ddbbRows + kodikTranslations.sortedBy { it.title.lowercase() })
            .distinctBy { normalizeDubKey(it.title) }
        if (persistKey != null) {
            translationCache[persistKey] = merged to System.currentTimeMillis()
        }
        if (stateStore != null && persistKey != null && merged.isNotEmpty()) {
            stateStore.saveMovieVoiceoverCache(
                persistKey,
                MovieVoiceoverCache(
                    savedAtMs = System.currentTimeMillis(),
                    rows = merged.take(MAX_PERSISTED_VOICEOVER_ROWS).mapNotNull { tr ->
                        val link = tr.episodes.firstOrNull()?.link.orEmpty()
                        if (link.isBlank()) null else CachedMovieVoiceover(
                            id = tr.translationId,
                            title = tr.title,
                            link = link
                        )
                    }
                )
            )
        }
        return merged
    }

    private fun normalizeDubKey(title: String): String =
        title.trim().lowercase().replace(Regex("[^a-zа-яё0-9]+"), "")

    private fun translationCacheKey(request: MoviePlaybackRequest): String? {
        val kpId = request.kinopoiskId?.takeIf { it > 0 }
        if (kpId != null) return "kp:$kpId"
        val titles = request.titles.map { normalizeDubKey(it) }.filter { it.isNotEmpty() }.sorted()
        return titles.takeIf { it.isNotEmpty() }?.joinToString("|")?.let { "t:$it" }
    }

    // Session-level memo of merged movie voiceover lists: repeated Watch presses within the TTL
    // must not re-race providers into a differently-composed dropdown.
    private val translationCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<List<FlatTranslation>, Long>>()
    private const val MOVIE_TRANSLATIONS_TTL_MS = 10 * 60_000L

    /** Freshness window of the persisted (cross-launch) merged voiceover list. */
    private const val PERSISTED_VOICEOVERS_TTL_MS = 24 * 60 * 60_000L
    private const val MAX_PERSISTED_VOICEOVER_ROWS = 40

    /** How long the winner waits for the loser's dub catalog before starting playback. */
    private const val DDBB_GRACE_MS = 3_500L
    private const val KODIK_GRACE_MS = 4_000L

    /** Outcome of the parallel Kodik-catalog-vs-ddbb series race. */
    internal sealed interface SeriesOutcome {
        data class FromKodik(val catalog: MovieCatalogResult.Available) : SeriesOutcome
        data class FromDdbb(val stream: DdbbStream) : SeriesOutcome
        data class Failed(val reason: MoviePlaybackFailure) : SeriesOutcome
    }

    internal suspend fun awaitFirstSeriesOutcome(
        catalogDeferred: kotlinx.coroutines.Deferred<MovieCatalogResult>,
        ddbbDeferred: kotlinx.coroutines.Deferred<DdbbStream?>,
    ): SeriesOutcome {
        while (true) {
            if (catalogDeferred.isCompleted) {
                when (val catalog = catalogDeferred.await()) {
                    is MovieCatalogResult.Available -> {
                        ddbbDeferred.cancel()
                        return SeriesOutcome.FromKodik(catalog)
                    }
                    is MovieCatalogResult.Unavailable -> {
                        if (ddbbDeferred.isCompleted) {
                            val stream = ddbbDeferred.await()
                            return if (stream != null) SeriesOutcome.FromDdbb(stream)
                            else SeriesOutcome.Failed(catalog.reason)
                        }
                    }
                }
            }
            if (ddbbDeferred.isCompleted && ddbbDeferred.await() != null) {
                catalogDeferred.cancel()
                return SeriesOutcome.FromDdbb(ddbbDeferred.await()!!)
            }
            if (catalogDeferred.isCompleted && ddbbDeferred.isCompleted) {
                return when {
                    catalogDeferred.await() is MovieCatalogResult.Available ->
                        SeriesOutcome.FromKodik(catalogDeferred.await() as MovieCatalogResult.Available)
                    ddbbDeferred.await() != null -> SeriesOutcome.FromDdbb(ddbbDeferred.await()!!)
                    catalogDeferred.await() is MovieCatalogResult.Unavailable ->
                        SeriesOutcome.Failed((catalogDeferred.await() as MovieCatalogResult.Unavailable).reason)
                    else -> SeriesOutcome.Failed(MoviePlaybackFailure.NO_PROVIDER_RESULTS)
                }
            }
            select<Unit> {
                catalogDeferred.onAwait { }
                ddbbDeferred.onAwait { }
            }
        }
    }

    /** Outcome of the parallel Kodik-vs-ddbb movie race. */
    internal sealed interface MovieOutcome {
        data class FromKodik(val result: MovieStreamResult.Success) : MovieOutcome
        data class FromDdbb(val stream: DdbbStream) : MovieOutcome
        data class Failed(val failure: MovieStreamResult.Unavailable) : MovieOutcome
    }

    /**
     * True race between the Kodik catalog and the ddbb aggregator: resolves as soon as EITHER
     * source produces a playable stream. Previously Kodik's full search cascade ran to
     * completion before ddbb was even consulted, serially paying both latencies on every
     * fallback (15-45s worst case before the player opened).
     *
     * The LOSER IS NOT CANCELLED here anymore: callers hold a grace window over it so both dub
     * catalogs can merge into the voiceover dropdown (winner-dependent lists were the "different
     * translations every launch" bug). Callers cancel after their window.
     */
    internal suspend fun awaitFirstMovieOutcome(
        kodikDeferred: kotlinx.coroutines.Deferred<MovieStreamResult>,
        ddbbDeferred: kotlinx.coroutines.Deferred<DdbbStream?>,
    ): MovieOutcome {
        while (true) {
            if (kodikDeferred.isCompleted && kodikDeferred.await() is MovieStreamResult.Success) {
                @Suppress("UNCHECKED_CAST")
                return MovieOutcome.FromKodik(kodikDeferred.await() as MovieStreamResult.Success)
            }
            if (ddbbDeferred.isCompleted && ddbbDeferred.await() != null) {
                return MovieOutcome.FromDdbb(ddbbDeferred.await()!!)
            }
            if (kodikDeferred.isCompleted && ddbbDeferred.isCompleted) {
                val kodik = kodikDeferred.await()
                return if (kodik is MovieStreamResult.Success) {
                    MovieOutcome.FromKodik(kodik)
                } else {
                    MovieOutcome.Failed(kodik as MovieStreamResult.Unavailable)
                }
            }
            select<Unit> {
                kodikDeferred.onAwait { }
                ddbbDeferred.onAwait { }
            }
        }
    }

    /**
     * Builds a MOVIE_SERIES playback context from a structured ddbb/turbo serial catalog.
     *
     * One candidate per dub preserves every dub's own episode urls, so the player switches seasons,
     * episodes and dubs without re-resolving anything; [MovieSeriesPlaybackContext.isDirectSource]
     * makes it load those urls as-is instead of scraping Kodik HLS. Returns null when the embed
     * carries no episode structure (caller falls back to voiceover-only playback).
     */
    internal fun buildDdbbSeriesContext(
        request: MoviePlaybackRequest,
        stream: DdbbStream,
        profile: UserFilmProfile?,
    ): MovieSeriesPlaybackContext? {
        if (stream.episodeTracks.isEmpty()) return null

        val kinopoiskId = request.kinopoiskId ?: 0
        val displayTitle = request.titles.firstOrNull() ?: "Фильм"
        val candidates = stream.episodeTracks
            .groupBy { it.dubId }
            .map { (_, rows) ->
                val sorted = rows.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
                KodikMovieCandidate(
                    sourceIndex = 0,
                    kinopoiskId = kinopoiskId.takeIf { it > 0 },
                    imdbId = request.imdbId,
                    title = displayTitle,
                    originalTitle = null,
                    year = request.year,
                    kind = MovieContentKind.SERIES,
                    translationId = sorted.first().dubId,
                    translationTitle = sorted.first().dubTitle,
                    topLevelPlayerUrl = null,
                    episodes = sorted.map {
                        MovieEpisodeRef(
                            it.seasonNumber, it.episodeNumber, it.title ?: "Серия ${it.episodeNumber}", it.playerUrl
                        )
                    }
                )
            }
            .sortedBy { it.translationTitle?.lowercase().orEmpty() }

        val union = canonicalSeriesEpisodes(candidates)
        if (union.isEmpty()) return null
        val picked = selectInitialSeriesEpisode(union, profile) ?: return null

        // Start under the dub with the best coverage of the resume episode; its urls re-point the
        // union list so every preselected entry plays from that dub without a switch.
        val initialDub = candidates
            .filter { candidate ->
                candidate.episodes.any {
                    it.seasonNumber == picked.seasonNumber && it.episodeNumber == picked.episodeNumber
                }
            }
            .maxByOrNull { it.episodes.size }
            ?: candidates.first()
        val dubUrls = initialDub.episodes.associate { (it.seasonNumber to it.episodeNumber) to it.playerUrl }
        val effectiveEpisodes = union.map { ref ->
            ref.copy(playerUrl = dubUrls[ref.seasonNumber to ref.episodeNumber] ?: ref.playerUrl)
        }
        val currentEpisode = effectiveEpisodes.firstOrNull {
            it.seasonNumber == picked.seasonNumber && it.episodeNumber == picked.episodeNumber
        } ?: effectiveEpisodes.first()

        return MovieSeriesPlaybackContext(
            request = request,
            candidates = candidates,
            episodes = effectiveEpisodes,
            currentEpisode = currentEpisode,
            kinopoiskId = kinopoiskId,
            displayTitle = displayTitle,
            isDirectSource = true,
            directHeaders = stream.headers
        )
    }
}
