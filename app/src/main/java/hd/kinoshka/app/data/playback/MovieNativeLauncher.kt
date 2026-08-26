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
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
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
        val raceStartMs = System.currentTimeMillis()
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
                ddbbSeriesDeferred.cancel()
                Log.i(TAG, "series race winner=kodik at ${System.currentTimeMillis() - raceStartMs}ms")
                kodikSeriesPayload(request, outcome.catalog, profile)
            }
            is SeriesOutcome.FromDdbb -> {
                val harvested = outcome.stream
                // Episode-coverage guard: Kodik often indexes more seasons/episodes than ddbb's
                // turbo config. If Kodik's catalog is ALREADY resolved (no extra wait) and its
                // episode union is strictly broader, keep the Kodik structure.
                val kodikCatalog = catalogDeferred
                    .takeIf { it.isCompleted }
                    ?.let { runCatching { it.await() }.getOrNull() as? MovieCatalogResult.Available }
                catalogDeferred.cancel()
                val ddbbEpisodeCount = harvested.episodeTracks
                    .distinctBy { it.seasonNumber to it.episodeNumber }.size
                val kodikEpisodeCount = kodikCatalog?.let { canonicalSeriesEpisodes(it.candidates).size } ?: 0
                if (kodikCatalog != null && kodikEpisodeCount > ddbbEpisodeCount) {
                    Log.i(TAG, "series race winner=ddbb at ${System.currentTimeMillis() - raceStartMs}ms, but kodik structure broader ($kodikEpisodeCount > $ddbbEpisodeCount eps) → kodik")
                    kodikSeriesPayload(request, kodikCatalog, profile)
                } else {
                    Log.i(TAG, "series race winner=ddbb/${harvested.sourceName} at ${System.currentTimeMillis() - raceStartMs}ms (ddbb eps=$ddbbEpisodeCount, kodik eps=$kodikEpisodeCount)")
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
            }
            is SeriesOutcome.Failed -> {
                catalogDeferred.cancel()
                ddbbSeriesDeferred.cancel()
                NativeLaunchPayload.Failed(outcome.reason)
            }
        }
    }

    private suspend fun kodikSeriesPayload(
        request: MoviePlaybackRequest,
        catalog: MovieCatalogResult.Available,
        profile: UserFilmProfile?,
    ): NativeLaunchPayload = coroutineScope {
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

    private suspend fun resolveMovie(request: MoviePlaybackRequest, stateStore: UserStateStore?): NativeLaunchPayload = coroutineScope {
        val raceStartMs = System.currentTimeMillis()
        val ddbbDeferred = async {
            request.kinopoiskId?.takeIf { it > 0 }?.let { kpId ->
                runCatching { DdbbStreamResolver.resolveMovieStream(kpId) }
                    .onFailure { Log.w(TAG, "ddbb race failed", it) }
                    .getOrNull()
            }
        }
        val kodikDeferred = async { MovieStreamResolver.resolveMovie(request) }
        val outcome = awaitFirstMovieOutcome(kodikDeferred, ddbbDeferred)
        Log.i(TAG, "movie race winner=${when (outcome) { is MovieOutcome.FromDdbb -> "ddbb"; is MovieOutcome.FromKodik -> "kodik"; is MovieOutcome.Failed -> "none" }} at ${System.currentTimeMillis() - raceStartMs}ms")
        when (outcome) {
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

    /**
     * Resolve every offered dub before opening PlayerActivity; failed rows are not advertised.
     * A Kodik row whose HLS extraction yields nothing is KEPT (unprepared): the player's lazy
     * [hd.kinoshka.app...loadQomVoiceover] path re-resolves it on switch instead of the row
     * silently vanishing from the dropdown.
     */
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
                if (direct) {
                    val ladder = turboLadder.orEmpty().ifEmpty { mapOf("Auto" to rawUrl) }
                    translation to AnimeMediaStream(rawUrl, ladder, launchStream.headers)
                } else {
                    val ladder = runCatching {
                        AnimeStreamResolver.resolveKodikHls(AnimeStreamResolver.absoluteKodikUrl(rawUrl))
                    }.getOrDefault(emptyMap())
                    val url = QUALITY_PREFERENCE_DESC.firstNotNullOfOrNull { ladder[it] } ?: ladder.values.firstOrNull()
                    if (url != null) {
                        translation to AnimeMediaStream(url, ladder, AnimeStreamResolver.kodikPlaybackHeaders())
                    } else {
                        // No ladder this time: keep the row unprepared — the player lazy-resolves
                        // the raw player link when the user actually picks this dub.
                        translation to null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        val streams = ready.mapNotNull { it.second?.let { stream -> it.first.translationId to stream } }.toMap()
        val rows = ready.map { it.first }
        val initialRow = rows.firstOrNull { streams[it.translationId]?.url == launchStream.url }
        // CRITICAL: no prepared match → play the WINNER stream itself, never an arbitrary
        // prepared row. Falling back to the first prepared row started a stale cached Kodik
        // dub (yesterday's url, wrong headers) even though ddbb had just won with a fresh
        // 1080p turbo link (kp=5457758 ended in END_FILE error loops).
        val initial = initialRow?.let { streams[it.translationId] } ?: launchStream
        val orderedRows = initialRow?.let { first -> listOf(first) + rows.filterNot { it.translationId == first.translationId } } ?: rows
        val ladderSummary = rows.joinToString { row ->
            "${row.source.name}:${row.title.take(24)}=${streams[row.translationId]?.qualities?.keys?.joinToString("/") ?: "lazy"}"
        }
        Log.i(TAG, "readyQualityMovie: rows=${rows.size} (ddbb=${rows.count { it.source == AnimeSourceType.DDBB }}, kodik=${rows.count { it.source == AnimeSourceType.KODIK }}), kp=$kinopoiskId, ladders: $ladderSummary")
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
                source = runCatching { AnimeSourceType.valueOf(row.source) }.getOrDefault(AnimeSourceType.KODIK),
                translationId = row.id,
                title = row.title,
                type = row.type,
                episodes = listOf(AnimeEpisode(number = 1, title = row.title, link = row.link))
            )
        }

        // Fresh inputs pass the classifier BEFORE dedup so "Original"/"…Subt" rows from
        // different providers collapse into one relabeled row instead of competing as dubs.
        val ddbbRows = relabelDubTracks(ddbbStream?.translations.orEmpty().map { (title, url) ->
            FlatTranslation(
                source = AnimeSourceType.DDBB,
                translationId = title,
                title = title,
                episodes = listOf(AnimeEpisode(number = 1, title = title, link = url))
            )
        })
        val freshKodikRows = relabelDubTracks(kodikTranslations.sortedBy { it.title.lowercase() })
        // Dedup keeps the FIRST row per normalized title, so FRESH sources must come first:
        // putting caches ahead let yesterday's KODIK-marked copies shadow today's turbo rows
        // entirely (kp=5457758 logged "ddbb=0" with 4 turbo dubs harvested). Fresh-first also
        // replaces expired cached links with just-resolved ones; the list stays deterministic
        // because the session cache is merged into the same union afterwards.
        val merged = (ddbbRows + freshKodikRows + sessionTranslations + persistedTranslations)
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
                            link = link,
                            source = tr.source.name,
                            type = tr.type
                        )
                    }
                )
            )
        }
        return merged
    }

    private fun normalizeDubKey(title: String): String =
        title.trim().lowercase().replace(Regex("[^a-zа-яё0-9]+"), "")

    // Provider labels that actually mean a non-dub track: turbo marks subtitle dubs with a
    // ".Subt"/"·субтитры" suffix, and both Kodik and turbo name the undubbed audio "Original".
    private val SUB_TRACK_HINTS = listOf(".subt", "субтитр", "subtitle", "(subs", " subs")
    private val ORIG_TRACK_HINTS = listOf("orig", "оригинал")

    /**
     * Splits a merged dub title into (display title, FlatTranslation.type). Undubbed audio and
     * subtitle tracks used to sit in the dropdown among regular dubs ("Original",
     * "FSG Baddest Females.Subt") and read as voiceovers.
     */
    internal fun classifyDubTrack(title: String): Pair<String, String> {
        val lower = title.lowercase()
        return when {
            SUB_TRACK_HINTS.any { it in lower } -> {
                val base = title
                    .replace(Regex("(?i)[.·\\s–—-]*\\b(subtitles?|subt(?:itres|itolo)?|субтитры?)\\b"), "")
                    .replace(Regex("\\s*[(·|]\\s*(subs|субт)\\s*[)]"), "")
                    .trim('.', ' ', '-', '·', '|')
                "Субтитры · ${base.ifBlank { title.trim() }}" to "sub"
            }
            ORIG_TRACK_HINTS.any { it in lower } -> {
                val base = title.replace(Regex("(?i)\\b(originals?|оригинал)\\b"), "")
                    .trim(' ', '-', '|', '(', ')')
                if (base.isBlank()) "Оригинал (без перевода)" to "orig"
                else "$base · оригинал (без перевода)" to "orig"
            }
            else -> title to "voice"
        }
    }

    /** Applies [classifyDubTrack] over the whole list — one choke point for movie dropdown rows. */
    private fun relabelDubTracks(rows: List<FlatTranslation>): List<FlatTranslation> =
        rows.map { tr ->
            val (display, kind) = classifyDubTrack(tr.title)
            if (kind == tr.type && display == tr.title) tr else tr.copy(title = display, type = kind)
        }

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

    /** kp=5457758: Kodik's cascade finished ~600ms AFTER the old 4s window and its dubs were
     *  then reconstructed from yesterday's persisted cache. 6.5s lets the fresh catalog join
     *  the dropdown directly; the player shows its loading overlay meanwhile anyway. */
    private const val KODIK_GRACE_MS = 6_500L

    /** ddbb's absolute-priority window: how long a ready Kodik result waits for ddbb to still win. */
    internal const val DDBB_WIN_GRACE_MS = 3_000L

    /** Outcome of the parallel Kodik-catalog-vs-ddbb series race. */
    internal sealed interface SeriesOutcome {
        data class FromKodik(val catalog: MovieCatalogResult.Available) : SeriesOutcome
        data class FromDdbb(val stream: DdbbStream) : SeriesOutcome
        data class Failed(val reason: MoviePlaybackFailure) : SeriesOutcome
    }

    /**
     * ddbb-first series race (same priority as [awaitFirstMovieOutcome]): ddbb wins whenever it
     * yields a stream; a ready Kodik catalog waits [DDBB_WIN_GRACE_MS] for ddbb before it may
     * take over. Episode-coverage comparison against Kodik happens in the caller. The loser is
     * not cancelled here so the caller can still consult it.
     */
    internal suspend fun awaitFirstSeriesOutcome(
        catalogDeferred: kotlinx.coroutines.Deferred<MovieCatalogResult>,
        ddbbDeferred: kotlinx.coroutines.Deferred<DdbbStream?>,
    ): SeriesOutcome {
        while (true) {
            if (ddbbDeferred.isCompleted && ddbbDeferred.await() != null) {
                return SeriesOutcome.FromDdbb(ddbbDeferred.await()!!)
            }
            if (catalogDeferred.isCompleted) {
                when (val catalog = catalogDeferred.await()) {
                    is MovieCatalogResult.Available -> {
                        // Same absolute-priority window as the movie race: a ready Kodik catalog
                        // waits briefly for ddbb before it may take over.
                        val ddbb = kotlinx.coroutines.withTimeoutOrNull(DDBB_WIN_GRACE_MS) {
                            runCatching { ddbbDeferred.await() }.getOrNull()
                        }
                        if (ddbb != null) return SeriesOutcome.FromDdbb(ddbb)
                        return SeriesOutcome.FromKodik(catalog)
                    }
                    is MovieCatalogResult.Unavailable -> {
                        // Kodik already failed: await ddbb directly instead of busy-spinning.
                        val stream = runCatching { ddbbDeferred.await() }.getOrNull()
                        return if (stream != null) SeriesOutcome.FromDdbb(stream)
                        else SeriesOutcome.Failed(catalog.reason)
                    }
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
     * ddbb-first race: DDBB wins whenever it produces a stream within its window. Only when
     * Kodik is already successful AND ddbb still hasn't finished does Kodik get the outcome —
     * after waiting [DDBB_WIN_GRACE_MS] more for ddbb (direct links with 1080p beat Kodik's
     * 720p-first HLS). The LOSER IS NOT CANCELLED here: callers hold a grace window over it so
     * both dub catalogs can merge into the voiceover dropdown (winner-dependent lists were the
     * "different translations every launch" bug). Callers cancel after their window.
     */
    internal suspend fun awaitFirstMovieOutcome(
        kodikDeferred: kotlinx.coroutines.Deferred<MovieStreamResult>,
        ddbbDeferred: kotlinx.coroutines.Deferred<DdbbStream?>,
    ): MovieOutcome {
        while (true) {
            if (ddbbDeferred.isCompleted && ddbbDeferred.await() != null) {
                return MovieOutcome.FromDdbb(ddbbDeferred.await()!!)
            }
            if (kodikDeferred.isCompleted) {
                val kodik = kodikDeferred.await()
                if (kodik is MovieStreamResult.Success) {
                    // Kodik ready but ddbb still pending: ddbb keeps absolute priority for a short
                    // window before Kodik may take over.
                    val ddbb = kotlinx.coroutines.withTimeoutOrNull(DDBB_WIN_GRACE_MS) {
                        runCatching { ddbbDeferred.await() }.getOrNull()
                    }
                    @Suppress("UNCHECKED_CAST")
                    return if (ddbb != null) MovieOutcome.FromDdbb(ddbb)
                    else MovieOutcome.FromKodik(kodik as MovieStreamResult.Success)
                }
                // Kodik already failed: awaiting ddbb directly — looping back through select
                // would busy-spin on the already-completed Kodik deferral.
                val ddbb = runCatching { ddbbDeferred.await() }.getOrNull()
                @Suppress("UNCHECKED_CAST")
                return ddbb?.let { MovieOutcome.FromDdbb(it) }
                    ?: MovieOutcome.Failed(kodik as MovieStreamResult.Unavailable)
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
