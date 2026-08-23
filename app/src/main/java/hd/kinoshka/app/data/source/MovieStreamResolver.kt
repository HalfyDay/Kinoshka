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
import kotlinx.coroutines.withContext
import org.json.JSONObject

object MovieStreamResolver {
    private const val TAG = "MovieStreamResolver"

    suspend fun loadCatalog(request: MoviePlaybackRequest): MovieCatalogResult = withContext(Dispatchers.IO) {
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

    suspend fun resolveMovie(request: MoviePlaybackRequest): MovieStreamResult = withContext(Dispatchers.IO) {
        val kodikResult: MovieStreamResult = when (val catalog = loadCatalog(request)) {
            is MovieCatalogResult.Unavailable -> MovieStreamResult.Unavailable(catalog.reason)
            is MovieCatalogResult.Available -> {
                val references = catalog.candidates.flatMap { candidate -> movieReferences(candidate).map { candidate to it } }
                resolveReferences(references, null)
            }
        }
        if (kodikResult is MovieStreamResult.Success) return@withContext kodikResult

        // Kodik does not index most live-action films, and its player obfuscation breaks now and
        // then; the ddbb aggregator (same sources the in-app web player uses) covers the gap with
        // collaps/turbo embeds that expose direct HLS/MP4.
        request.kinopoiskId?.takeIf { it > 0 }?.let { kpId ->
            runCatching { DdbbStreamResolver.resolveMovieStream(kpId) }
                .onFailure { Log.w(TAG, "ddbb fallback failed", it) }
                .getOrNull()
                ?.let { stream ->
                    Log.i(TAG, "ddbb fallback succeeded via ${stream.sourceName}")
                    return@withContext MovieStreamResult.Success(
                        AnimeMediaStream(
                            url = stream.url,
                            qualities = stream.qualities,
                            headers = stream.headers,
                            quality = stream.qualities.keys.firstOrNull() ?: "Auto"
                        ),
                        null
                    )
                }
        }
        kodikResult
    }

    suspend fun resolveEpisode(
        request: MoviePlaybackRequest,
        episode: MovieEpisodeRef,
        candidates: List<KodikMovieCandidate>? = null
    ): MovieStreamResult = withContext(Dispatchers.IO) {
        val available = candidates ?: when (val catalog = loadCatalog(request)) {
            is MovieCatalogResult.Available -> catalog.candidates
            is MovieCatalogResult.Unavailable -> return@withContext MovieStreamResult.Unavailable(catalog.reason)
        }
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

        // Both ID lookups are authoritative, so run both: a row Kodik joined to IMDb but not to the
        // Kinopoisk id still contributes a reference when the first batch fails stream extraction.
        request.kinopoiskId?.takeIf { it > 0 }?.let { id ->
            evaluate(
                AnimeStreamResolver.kodikSearchMovieByKinopoiskId(id),
                KodikMovieParser.MatchOrigin.KINOPOISK_ID
            )
        }
        KodikMovieParser.normalizeImdb(request.imdbId)?.let { imdb ->
            evaluate(
                AnimeStreamResolver.kodikSearchMovieByImdbId(imdb),
                KodikMovieParser.MatchOrigin.IMDB_ID
            )
        }
        // Title queries are heuristic and cost one request each: only spend them when the
        // authoritative lookups produced nothing.
        if (allAccepted.isEmpty()) {
            request.titles.distinctBy(KodikMovieParser::normalizeTitle).forEach { title ->
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
