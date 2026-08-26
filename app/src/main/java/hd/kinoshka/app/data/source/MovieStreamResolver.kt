package hd.kinoshka.app.data.source

import android.util.Log
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.KodikMovieCandidate
import hd.kinoshka.app.data.model.MovieCatalogResult
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MoviePlaybackFailure
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.MovieStreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

object MovieStreamResolver {
    private const val TAG = "MovieStreamResolver"

    private data class CatalogCacheEntry(
        val candidates: List<KodikMovieCandidate>,
        val timestamp: Long
    )

    // Kodik's id lookups are stable for a title; caching the accepted candidate list keeps
    // Watch→player relaunches (and the series race) from repeating a multi-request cascade.
    private val catalogCache = java.util.concurrent.ConcurrentHashMap<String, CatalogCacheEntry>()
    private const val CATALOG_TTL_MS = 5 * 60_000L

    private fun catalogCacheKey(request: MoviePlaybackRequest): String = listOf(
        request.kinopoiskId?.takeIf { it > 0 }?.toString() ?: "-",
        request.imdbId ?: "-",
        request.titles.map(KodikMovieParser::normalizeTitle).sorted().joinToString(",").take(120)
    ).joinToString("|")

    suspend fun loadCatalog(request: MoviePlaybackRequest): MovieCatalogResult = withContext(Dispatchers.IO) {
        val cacheKey = catalogCacheKey(request)
        catalogCache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CATALOG_TTL_MS) {
                return@withContext MovieCatalogResult.Available(entry.candidates)
            }
            catalogCache.remove(cacheKey)
        }

        val result = loadCatalogInternal(request)
        if (result is MovieCatalogResult.Available) {
            catalogCache[cacheKey] = CatalogCacheEntry(result.candidates, System.currentTimeMillis())
        }
        result
    }

    private suspend fun loadCatalogInternal(request: MoviePlaybackRequest): MovieCatalogResult = withContext(Dispatchers.IO) {
        val searchResults = search(request)
        if (searchResults.raw.isEmpty()) {
            val failure = when (searchResults.failure) {
                AnimeStreamResolver.KodikSearchFailure.PROVIDER -> MoviePlaybackFailure.PROVIDER_ERROR
                AnimeStreamResolver.KodikSearchFailure.NETWORK -> MoviePlaybackFailure.NETWORK_ERROR
                AnimeStreamResolver.KodikSearchFailure.NONE -> MoviePlaybackFailure.NO_PROVIDER_RESULTS
            }
            return@withContext MovieCatalogResult.Unavailable(failure)
        }
        if (searchResults.accepted.isEmpty()) {
            Log.w(TAG, "Catalog rejected: ${searchResults.raw.size} provider results, no identity match (kp=${request.kinopoiskId}, imdb=${request.imdbId}, titles=$request.titles)")
            return@withContext MovieCatalogResult.Unavailable(MoviePlaybackFailure.NO_MATCHING_RESULTS)
        }
        // Kodik's `type` is unreliable, so the requested kind is a preference, not a filter:
        // keep anything playable and merely prefer rows matching the requested shape.
        val playable = searchResults.accepted.filter { candidate ->
            candidate.topLevelPlayerUrl != null || candidate.episodes.isNotEmpty()
        }
        if (playable.isEmpty()) {
            return@withContext MovieCatalogResult.Unavailable(MoviePlaybackFailure.NO_PLAYABLE_REFERENCES)
        }
        Log.i(TAG, "Catalog: ${searchResults.raw.size} results, ${playable.size} accepted playable candidates")
        MovieCatalogResult.Available(playable)
    }

    /**
     * Kodik-only movie resolution. The ddbb fallback lives in the CALLER ([DdbbStreamResolver]),
     * which races it in parallel — running it here serially added its full latency on top of
     * Kodik's for every title Kodik could not serve.
     */
    suspend fun resolveMovie(request: MoviePlaybackRequest): MovieStreamResult = withContext(Dispatchers.IO) {
        when (val catalog = loadCatalog(request)) {
            is MovieCatalogResult.Unavailable -> MovieStreamResult.Unavailable(catalog.reason)
            is MovieCatalogResult.Available -> {
                val references = catalog.candidates.flatMap { candidate -> movieReferences(candidate).map { candidate to it } }
                val base = resolveReferences(references, null)
                if (base is MovieStreamResult.Success && catalog.candidates.size > 1) {
                    // Voiceover options for the player dropdown: each dub's own player link is kept
                    // raw and extracted lazily on switch, so startup stays fast.
                    val translations = catalog.candidates
                        .filter { !it.topLevelPlayerUrl.isNullOrBlank() }
                        .distinctBy { it.translationId ?: it.translationTitle ?: "default" }
                        .map { candidate ->
                            hd.kinoshka.app.data.model.FlatTranslation(
                                source = hd.kinoshka.app.data.model.AnimeSourceType.KODIK,
                                translationId = candidate.translationId ?: candidate.translationTitle ?: "default",
                                title = candidate.translationTitle ?: "Озвучка ${candidate.translationId ?: "default"}",
                                episodes = listOf(
                                    hd.kinoshka.app.data.model.AnimeEpisode(
                                        number = 1,
                                        title = candidate.translationTitle,
                                        link = candidate.topLevelPlayerUrl
                                    )
                                )
                            )
                        }
                    base.copy(translations = translations)
                } else base
            }
        }
    }

    suspend fun resolveEpisode(
        request: MoviePlaybackRequest,
        episode: MovieEpisodeRef,
        candidates: List<KodikMovieCandidate>? = null,
        translationId: String? = null
    ): MovieStreamResult = withContext(Dispatchers.IO) {
        val available0 = candidates ?: when (val catalog = loadCatalog(request)) {
            is MovieCatalogResult.Available -> catalog.candidates
            is MovieCatalogResult.Unavailable -> return@withContext MovieStreamResult.Unavailable(catalog.reason)
        }
        // Voiceover switching narrows the candidate pool to one dub before episode matching.
        val available = translationId?.let { trId -> available0.filter { it.translationId == trId }.ifEmpty { available0 } } ?: available0
        val references = available.flatMap { candidate ->
            candidate.episodes
                .filter { it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber }
                .map { candidate to it.playerUrl }
        }
        if (references.isNotEmpty()) {
            return@withContext resolveReferences(references, episode)
        }

        // Rows discovered via find-player carry only a whole-title player link (no season/episode
        // map). Resolving that link yields the provider's default episode — a functional start for
        // series that would otherwise fall straight back to the web player.
        val wholeTitleLinks = available.mapNotNull { it.topLevelPlayerUrl }
        if (wholeTitleLinks.isNotEmpty()) {
            Log.w(TAG, "No episode refs for S${episode.seasonNumber}E${episode.episodeNumber}, resolving whole-title links")
            return@withContext resolveReferences(available.map { it to it.topLevelPlayerUrl!! }, episode)
        }
        resolveReferences(emptyList(), episode)
    }

    private data class SearchResults(
        val raw: List<JSONObject>,
        val accepted: List<KodikMovieCandidate>,
        val failure: AnimeStreamResolver.KodikSearchFailure = AnimeStreamResolver.KodikSearchFailure.NONE
    )

    private suspend fun search(request: MoviePlaybackRequest): SearchResults {
        val allRaw = mutableListOf<JSONObject>()
        val allAccepted = mutableListOf<KodikMovieCandidate>()
        var failure = AnimeStreamResolver.KodikSearchFailure.NONE

        fun evaluate(
            result: AnimeStreamResolver.KodikMovieSearchResult,
            origin: KodikMovieParser.MatchOrigin
        ) {
            allRaw += result.items
            if (result.failure != AnimeStreamResolver.KodikSearchFailure.NONE) failure = result.failure
            allAccepted += accept(request, result.items, origin)
        }

        // Both ID lookups are authoritative, so run both — but CONCURRENTLY: they are independent,
        // and serially paying two full token×base cascades doubled the catalog latency for every
        // title the first lookup could not serve (live-action series like The Boys among them).
        val idBatches: List<Pair<AnimeStreamResolver.KodikMovieSearchResult, KodikMovieParser.MatchOrigin>> =
            kotlinx.coroutines.coroutineScope {
                val byKp = async {
                    request.kinopoiskId?.takeIf { it > 0 }?.let { id ->
                        AnimeStreamResolver.kodikSearchMovieByKinopoiskId(id) to KodikMovieParser.MatchOrigin.KINOPOISK_ID
                    }
                }
                val byImdb = async {
                    KodikMovieParser.normalizeImdb(request.imdbId)?.let { imdb ->
                        AnimeStreamResolver.kodikSearchMovieByImdbId(imdb) to KodikMovieParser.MatchOrigin.IMDB_ID
                    }
                }
                listOfNotNull(byKp.await(), byImdb.await())
            }
        idBatches.forEach { (result, origin) -> evaluate(result, origin) }
        // Title queries are heuristic and cost one request each: only spend them when the
        // authoritative lookups produced nothing, and cap the variants — the ddbb race covers
        // titles Kodik's fuzzy search would only find deep into the list anyway.
        if (allAccepted.isEmpty()) {
            request.titles.distinctBy(KodikMovieParser::normalizeTitle).take(3).forEach { title ->
                evaluate(
                    AnimeStreamResolver.kodikSearchMovieByTitle(title),
                    KodikMovieParser.MatchOrigin.TITLE
                )
            }
        }
        // Last resort: kodik.info/find-player consults Kodik's site DB directly and serves rows
        // the public API hides (some live-action series index under ids the API search never
        // returns). The scraped player link is trusted — the provider itself joined it to our id.
        if (allAccepted.isEmpty()) {
            request.kinopoiskId?.takeIf { it > 0 }?.let { kpId ->
                val found = AnimeStreamResolver.kodikFindPlayerByExternalId("kinopoisk_id", kpId)
                Log.d(TAG, "find-player fallback for kp=$kpId: ${found?.optString("link")?.take(80) ?: "null"}")
                evaluate(
                    AnimeStreamResolver.KodikMovieSearchResult(
                        items = listOfNotNull(found),
                        failure = AnimeStreamResolver.KodikSearchFailure.NONE
                    ),
                    KodikMovieParser.MatchOrigin.KINOPOISK_ID
                )
            }
        }
        // rank() applies distinctBy(candidateKey), so merging batches cannot duplicate references.
        return SearchResults(allRaw, KodikMovieParser.rank(request, allAccepted), failure)
    }

    private fun accept(
        request: MoviePlaybackRequest,
        raw: List<JSONObject>,
        origin: KodikMovieParser.MatchOrigin
    ): List<KodikMovieCandidate> =
        KodikMovieParser.acceptedCandidates(request, KodikMovieParser.parseCandidates(raw), origin)

    // A film is sometimes published as a single-episode "serial" row with no top-level link.
    // Fall back to its episode links so such rows remain playable as a movie.
    private fun movieReferences(candidate: KodikMovieCandidate): List<String> =
        (listOfNotNull(candidate.topLevelPlayerUrl) + candidate.episodes.map { it.playerUrl }).distinct()

    private suspend fun resolveReferences(
        references: List<Pair<KodikMovieCandidate, String>>,
        episode: MovieEpisodeRef?
    ): MovieStreamResult {
        if (references.isEmpty()) return MovieStreamResult.Unavailable(MoviePlaybackFailure.NO_PLAYABLE_REFERENCES)
        references.forEachIndexed { index, (_, playerUrl) ->
            val qualities = runCatching {
                AnimeStreamResolver.resolveKodikHls(AnimeStreamResolver.absoluteKodikUrl(playerUrl))
            }.onFailure {
                Log.w(TAG, "Candidate ${index + 1} extraction failed: ${it.javaClass.simpleName}")
            }.getOrDefault(emptyMap())
            if (qualities.isNotEmpty()) {
                // Prefer 720p for bandwidth, consistent with the anime paths, then walk a deterministic ladder
                // so 2160p/360p/240p/Auto still resolve instead of depending on map insertion order.
                val preference = listOf("720p", "1080p", "480p", "360p", "2160p", "240p")
                val bestKey = preference.firstOrNull { qualities.containsKey(it) } ?: qualities.keys.first()
                val url = qualities.getValue(bestKey)
                Log.i(TAG, "Resolved candidate ${index + 1}/${references.size} at $bestKey")
                return MovieStreamResult.Success(
                    AnimeMediaStream(
                        url = url,
                        qualities = qualities,
                        headers = AnimeStreamResolver.kodikPlaybackHeaders(),
                        quality = bestKey
                    ),
                    episode
                )
            }
        }
        return MovieStreamResult.Unavailable(MoviePlaybackFailure.STREAM_EXTRACTION_FAILED)
    }
}
