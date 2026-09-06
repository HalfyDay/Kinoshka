package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Extracts direct playable streams from video-source embed pages — the ddbb aggregator's
 * embeds (Turbo/Collaps/Alloha/Veoveo) PLUS the standalone webmaster sources (VideoCDN,
 * Collaps, Voidboost, see [WebmasterStreamSources]) — so the native mpvEx player can play
 * movies Kodik does not carry. All sources resolve concurrently and their dub rows, episode
 * tracks and quality ladders merge into one catalog.
 *
 * Supported embed formats (detected by page content, not by host — domains rotate constantly):
 *  - Collaps/VenomPlayer style: the embed HTML contains `hls: "<master.m3u8>"`.
 *  - Turbo style: the embed HTML contains `new Player("<base64>")`, where the payload is a JSON
 *    config whose `file[]` entries hold `[quality]url` lists. The blob is salted with comment-like
 *    junk segments and a short binary prefix, both stripped/brute-forced during decoding.
 */
object DdbbStreamResolver {
    private const val TAG = "DdbbStreamResolver"

    private const val PLAYERS_API = "https://p2.ddbb.lol/api/players?kinopoisk=%d"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            // Short connect budget: p2.ddbb.lol intermittently black-holes connections (live log:
            // 10s connect timeouts twice in a row, then instant success). Failing fast leaves
            // room for more attempts inside the same resolve deadline.
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Preferred order of ddbb sources for native playback; unknown types come last and are tried too.
     * Turbo first: it exposes plain progressive MP4s, while collaps hands out short-lived signed HLS
     * tokens whose segments intermittently 410 inside mpv even though the embed plays fine in a browser. */
    private fun typeRank(type: String): Int = when {
        type.equals("turbo", ignoreCase = true) -> 0
        type.equals("collaps", ignoreCase = true) -> 1
        else -> 2
    }

    data class DdbbStream(
        val url: String,
        val headers: Map<String, String>,
        val qualities: Map<String, String>,
        val sourceName: String,
        /** Voiceover tracks as (title, ready-to-play url); empty when the source has one dub. */
        val translations: List<Pair<String, String>> = emptyList(),
        /**
         * Structured turbo serial catalog: one entry per (dub × episode) with S/E numbers from
         * the t1 label. Empty for movies and embeds without episode structure.
         */
        val episodeTracks: List<hd.kinoshka.app.data.model.DdbbEpisodeTrack> = emptyList()
    )

    /** Sources whose embeds are worth re-resolving inside a real browser environment. */
    private val HARVESTABLE_TYPES = setOf("alloha", "veoveo", "collaps", "turbo")

    /**
     * Cached entry point: the details screen prefetches on open and the Watch button resolves
     * again on press — without this memo the same embed would be downloaded+decoded twice.
     * Short TTL keeps expiring CDN tokens from outliving their validity.
     */
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<DdbbStream>>()
    private const val RESOLVE_CACHE_TTL_MS = 3 * 60_000L

    /** Application-scoped executor for shared resolves. The details prefetch and the Watch-button
     * resolve overlap by seconds, and a duplicate full resolve re-downloads the same ~1MB turbo
     * embed over the same pipe (live log: 7.8s + 15.1s for one page — half the startup wait).
     * Late callers join the running job instead; the job's lifetime is decoupled from any single
     * caller, so one cancelled screen cannot kill the download another caller is awaiting. */
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightResolves = java.util.concurrent.ConcurrentHashMap<Int, Deferred<DdbbStream?>>()

    /**
     * [onLateSources] fires when sources still in flight finish after the winner returned:
     * the merged catalog (winner + late sources) re-registers, refreshes the 3-min cache and
     * lets the caller refresh its dropdown without a relaunch.
     */
    suspend fun resolveMovieStream(
        kinopoiskId: Int,
        onLateSources: ((DdbbStream) -> Unit)? = null,
    ): DdbbStream? = withContext(Dispatchers.IO) {
        if (kinopoiskId <= 0) return@withContext null
        resolveCache[kinopoiskId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < RESOLVE_CACHE_TTL_MS) return@withContext entry.data
            resolveCache.remove(kinopoiskId)
        }
        while (true) {
            val running = inFlightResolves[kinopoiskId]
            if (running != null && running.isActive) {
                try {
                    return@withContext running.await()
                } catch (e: CancellationException) {
                    // The shared job was cancelled under us (evictResolveCache on a dead-stream
                    // retry): re-run the loop and start our own resolve — unless the cancellation
                    // came from THIS caller going away, which must keep propagating.
                    currentCoroutineContext().ensureActive()
                }
            }
            val job = resolveScope.async {
                // The result is cached inside the job, not by the joining caller: the first
                // caller can be cancelled mid-await without losing the finished work.
                resolveMovieStreamInternal(kinopoiskId, onLateSources).also { resolved ->
                    if (resolved != null) {
                        resolveCache[kinopoiskId] = CacheEntry(resolved, System.currentTimeMillis())
                    }
                }
            }
            if (inFlightResolves.putIfAbsent(kinopoiskId, job) == null) {
                try {
                    return@withContext job.await()
                } finally {
                    inFlightResolves.remove(kinopoiskId, job)
                }
            }
            job.cancel()
        }
        // The loop above only exits via return/exception; this keeps the lambda's type explicit.
        return@withContext null
    }

    /**
     * One direct source's parse result, before the cross-source merge: the default stream +
     * its ladder, the dub rows (title → best-quality url), structured episode tracks, per-url
     * quality ladders and the headers its urls must be played with. Public for unit tests.
     */
    data class SourceParse(
        val sourceName: String,
        val url: String,
        val headers: Map<String, String>,
        val qualities: Map<String, String>,
        val voiceRows: List<Pair<String, String>> = emptyList(),
        val tracks: List<hd.kinoshka.app.data.model.DdbbEpisodeTrack> = emptyList(),
        val ladders: Map<String, Map<String, String>> = emptyMap(),
        val headersByUrl: Map<String, Map<String, String>> = emptyMap()
    )

    /** Playback priority across direct sources: turbo's multi-dub catalog first, then the
     *  webmaster API trio, then ddbb's single-stream embeds. Public for unit tests. */
    fun sourceRank(sourceName: String): Int = when {
        sourceName.equals("turbo", ignoreCase = true) -> 0
        sourceName.equals("videocdn", ignoreCase = true) -> 1
        sourceName.equals("collaps", ignoreCase = true) -> 2
        sourceName.equals("voidboost", ignoreCase = true) -> 3
        else -> 4
    }

    /**
     * Resolves [kinopoiskId] across ALL direct sources CONCURRENTLY — ddbb's embeds (Turbo,
     * Collaps, Alloha, Veoveo) and the webmaster trio (VideoCDN, Collaps, Voidboost).
     *
     * PLAYBACK RETURNS AS SOON AS ONE SOURCE'S URL ANSWERS A PROBE (one attempt per arriving
     * parse; the shared probe budget guards dead sources). Sources still in flight run on the
     * detached [resolveScope] and merge into the catalog late — blocking the launch on the
     * slowest source was the 23s-startup regression (live log kp=685246: turbo ready at 16s,
     * resolve waited for the failing webmaster trio until the deadline killed a fully-parsed
     * winner).
     */
    private suspend fun resolveMovieStreamInternal(
        kinopoiskId: Int,
        onLateSources: ((DdbbStream) -> Unit)?,
    ): DdbbStream? = withContext(Dispatchers.IO) {
        if (kinopoiskId <= 0) return@withContext null
        val players = fetchPlayers(kinopoiskId)
        KLog.i(TAG, "ddbb offered ${players.size} sources for kp=$kinopoiskId: ${players.map { it.first }}")
        val deadline = System.currentTimeMillis() + RESOLVE_DEADLINE_MS

        val pending = mutableListOf<Deferred<SourceParse?>>()
        for ((type, iframeUrl) in players) {
            pending += resolveScope.async {
                val html = fetchHtml(iframeUrl)
                if (html == null) {
                    KLog.w(TAG, "${type.lowercase()}: embed fetch failed")
                    return@async null
                }
                parseDdbbSource(kinopoiskId, type, iframeUrl, html)
            }
        }
        pending += resolveScope.async {
            runCatching { WebmasterStreamSources.resolveVideoCdn(kinopoiskId) }
                .onFailure { KLog.w(TAG, "videocdn: resolve failed", it) }
                .getOrNull()
        }
        pending += resolveScope.async {
            runCatching { WebmasterStreamSources.resolveCollaps(kinopoiskId) }
                .onFailure { KLog.w(TAG, "collaps: resolve failed", it) }
                .getOrNull()
        }
        pending += resolveScope.async {
            runCatching { WebmasterStreamSources.resolveVoidboost(kinopoiskId) }
                .onFailure { KLog.w(TAG, "voidboost: resolve failed", it) }
                .getOrNull()
        }

        val parses = ArrayList<SourceParse>()
        val budget = intArrayOf(LAUNCH_PROBE_MAX_ATTEMPTS)
        var early: DdbbStream? = null
        while (pending.isNotEmpty() && early == null) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val outcome = withTimeoutOrNull(remaining) {
                select<Pair<Deferred<SourceParse?>, SourceParse?>> {
                    for (deferred in pending) deferred.onAwait { deferred to it }
                }
            } ?: break
            val (done, parse) = outcome
            pending.remove(done)
            if (parse == null) continue
            parses += parse
            parses.sortBy { sourceRank(it.sourceName) }
            if (budget[0] > 0) {
                budget[0] -= 1
                if (validateDirectUrl(parse.url, parse.headers)) {
                    KLog.i(TAG, "launch probe OK: ${parse.url.take(90)}")
                    early = buildStream(
                        kinopoiskId, parse, parses.toList(),
                        LaunchChoice(parse.url, parse.qualities.ifEmpty { mapOf("Auto" to parse.url) }, verified = true),
                    )
                } else {
                    KLog.w(TAG, "launch probe dead: ${parse.url.take(90)}")
                }
            }
        }

        if (early == null) {
            if (parses.isEmpty()) {
                // Nothing parsed from any source: one headless-WebView harvest for sources
                // whose streams hide behind JS bootstrapping or region checks.
                harvestDdbbSource(players, deadline)?.let { harvested ->
                    parses += harvested
                    parses.sortBy { sourceRank(it.sourceName) }
                }
            }
            parses.minByOrNull { sourceRank(it.sourceName) }?.let { winner ->
                // Everything parsed, nothing verified: walk the priority winner's ladders/dubs
                // while budget and deadline remain, otherwise play it unverified — a full walk
                // must never end in "resolve failed" while a parsed stream exists.
                val choice = if (System.currentTimeMillis() < deadline && budget[0] > 0) {
                    pickPlayableLaunch(
                        defaultUrl = winner.url,
                        defaultLadder = winner.qualities.ifEmpty { mapOf("Auto" to winner.url) },
                        candidateLadders = winner.ladders,
                        headers = winner.headers,
                        budget = budget,
                    )
                } else {
                    LaunchChoice(winner.url, winner.qualities.ifEmpty { mapOf("Auto" to winner.url) }, verified = false)
                }
                early = buildStream(kinopoiskId, winner, parses.toList(), choice)
            }
        }

        if (pending.isNotEmpty()) {
            // Late continuation: the sources still in flight (or abandoned at the deadline)
            // merge into the catalog when they finish, refresh the cache and notify.
            val earlyParses = parses.toList()
            resolveScope.launch {
                val late = pending.awaitAll().filterNotNull()
                if (late.isEmpty()) return@launch
                // A dead-stream retry evicts the cache; don't resurface stale entries after it.
                if (resolveCache[kinopoiskId] == null) return@launch
                buildMergedStream(kinopoiskId, earlyParses + late)?.let { merged ->
                    resolveCache[kinopoiskId] = CacheEntry(merged, System.currentTimeMillis())
                    KLog.i(TAG, "late sources merged for kp=$kinopoiskId: +${late.map { it.sourceName }}, " +
                        "dubs=${merged.translations.size}, tracks=${merged.episodeTracks.size}")
                    onLateSources?.invoke(merged)
                }
            }
        }
        early
    }

    /**
     * Extraction of ONE ddbb embed into a [SourceParse] — no network probing and no catalog
     * registration here: all sources race first, the merged catalog is registered once.
     */
    private fun parseDdbbSource(kinopoiskId: Int, type: String, iframeUrl: String, html: String): SourceParse? {
        val lowerType = type.lowercase()
        val (headers, qualities) = extractFromEmbed(html, iframeUrl) ?: return null
        if (qualities.isEmpty()) return null
        // extractTurboTracks must receive the obfuscated config blob, not the whole embed page:
        // findTurboWindow scans a short base64 prefix, and feeding it the full HTML made the
        // window search fail → voiceover list silently empty.
        val turboBlob = if (lowerType == "turbo") TURBO_BLOB_REGEX.find(html)?.groupValues?.get(1) else null
        // Single decode feeds both consumers: flat dub rows for the movie dropdown and
        // structured dub×episode rows for series playback.
        val turboEntries = turboBlob?.let { extractTurboEntries(it) }.orEmpty()
        val translations = voiceoverRowsFromEntries(turboEntries)
            .ifEmpty { cachedVoiceoverRows(kinopoiskId).orEmpty() }
        val serialParse = buildSerialParse(turboEntries)
        val perDubLadders = buildLadders(turboEntries)
        if (serialParse.tracks.isEmpty() && translations.isEmpty()) {
            KLog.w(TAG, "$lowerType: no stream extracted")
            return null
        }
        if (serialParse.tracks.isNotEmpty()) {
            KLog.i(TAG, "$lowerType: structured serial catalog: " +
                "${serialParse.tracks.map { it.dubTitle }.distinct().size} dubs, " +
                "${serialParse.tracks.map { it.seasonNumber to it.episodeNumber }.distinct().size} episodes")
        }
        // The launch stream must be ONE dub's ladder, not a whole-blob scan (see buildLadders).
        val defaultDubUrl = translations.firstOrNull()?.second
        val defaultUrl = defaultDubUrl
            ?: qualities.getValue(qualityPreference.firstOrNull { qualities.containsKey(it) } ?: qualities.keys.first())
        KLog.i(TAG, "$lowerType: extracted ${qualities.size} qualities, using ${defaultLadderLabel(defaultUrl, perDubLadders, qualities)}")
        return SourceParse(
            sourceName = type.replaceFirstChar { it.uppercase() },
            url = defaultUrl,
            headers = headers,
            qualities = defaultDubUrl?.let { perDubLadders[it] } ?: qualities,
            voiceRows = translations,
            tracks = serialParse.tracks,
            ladders = perDubLadders
        )
    }

    private fun defaultLadderLabel(url: String, perDubLadders: Map<String, Map<String, String>>, embedQualities: Map<String, String>): String =
        perDubLadders[url]?.keys?.joinToString("/") ?: embedQualities.keys.firstOrNull() ?: "Auto"

    /** One headless-WebView harvest of the highest-ranked harvestable ddbb embed. */
    private suspend fun harvestDdbbSource(players: List<Pair<String, String>>, deadline: Long): SourceParse? {
        if (System.currentTimeMillis() >= deadline) return null
        val (type, iframeUrl) = players.firstOrNull { it.first.lowercase() in HARVESTABLE_TYPES } ?: return null
        KLog.i(TAG, "${type.lowercase()}: direct extraction failed, harvesting embed in a headless browser…")
        val harvested = DdbbHarvestBridge.harvest(
            embedUrl = iframeUrl,
            pageReferer = "https://ddbb.lol/",
            timeoutMs = HARVEST_TIMEOUT_MS,
        ) ?: run {
            KLog.w(TAG, "${type.lowercase()}: harvest found nothing")
            return null
        }
        val referer = harvested.referer
            ?: runCatching { java.net.URI(iframeUrl) }.getOrNull()?.let { "${it.scheme}://${it.host}/" }
            ?: "https://ddbb.lol/"
        KLog.i(TAG, "${type.lowercase()}: harvested ${harvested.url.take(100)}")
        return SourceParse(
            sourceName = type.replaceFirstChar { it.uppercase() },
            url = harvested.url,
            headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT),
            qualities = linkedMapOf("Auto" to harvested.url)
        )
    }

    /** Registers and builds the stream for an already-parsed set of sources (no probing). */
    private fun buildMergedStream(kinopoiskId: Int, parses: List<SourceParse>): DdbbStream? {
        if (parses.isEmpty()) return null
        val ordered = parses.sortedBy { sourceRank(it.sourceName) }
        val merged = mergeSourceParses(ordered)
        if (merged.voiceRows.isEmpty() && merged.tracks.isEmpty()) return null
        registerTurboCatalog(
            kinopoiskId,
            merged.headers,
            TurboSerialParse(merged.tracks, merged.ladders),
            merged.voiceRows,
            merged.headersByUrl
        )
        return DdbbStream(
            url = merged.url,
            headers = merged.headers,
            qualities = merged.qualities,
            sourceName = merged.sourceName,
            translations = merged.voiceRows,
            episodeTracks = merged.tracks
        )
    }

    private suspend fun buildStream(
        kinopoiskId: Int,
        winner: SourceParse,
        parses: List<SourceParse>,
        choice: LaunchChoice,
    ): DdbbStream {
        // mergeSourceParses keeps the FIRST parse's stream/headers — the verified winner must
        // lead even when a higher-rank parse is already in the list.
        val ordered = listOf(winner) + parses.filter { it !== winner }
        val merged = mergeSourceParses(ordered)
        if (merged.voiceRows.isNotEmpty() || merged.tracks.isNotEmpty()) {
            registerTurboCatalog(
                kinopoiskId,
                winner.headers,
                TurboSerialParse(merged.tracks, merged.ladders),
                merged.voiceRows,
                merged.headersByUrl
            )
        }
        val chosenLadder = linkedMapOf<String, String>().apply {
            directLadderPreference.forEach { q -> choice.ladder[q]?.let { put(q, it) } }
            choice.ladder.forEach { (q, u) -> if (!containsKey(q)) put(q, u) }
        }
        KLog.i(TAG, "direct catalog merged: winner=${winner.sourceName}, dubs=${merged.voiceRows.size}, " +
            "tracks=${merged.tracks.size}, ladders=${merged.ladders.size}, sources=${parses.map { it.sourceName }}")
        hd.kinoshka.app.data.diagnostics.SharedDiag.event(
            "direct sources: winner=${winner.sourceName}, dubs=${merged.voiceRows.size}, tracks=${merged.tracks.size} (${parses.joinToString { it.sourceName }})"
        )
        return DdbbStream(
            url = choice.url,
            headers = winner.headers,
            qualities = chosenLadder,
            sourceName = winner.sourceName,
            translations = merged.voiceRows,
            episodeTracks = merged.tracks
        )
    }

    /**
     * Unions [parses] into one catalog-shaped parse: the FIRST entry (the priority winner)
     * keeps its stream/headers, every source contributes its dub rows, episode tracks, quality
     * ladders and per-url playback headers. Public for unit tests.
     */
    fun mergeSourceParses(parses: List<SourceParse>): SourceParse {
        if (parses.size <= 1) return parses.firstOrNull() ?: SourceParse("none", "", emptyMap(), emptyMap())
        val winner = parses.first()
        val tracks = LinkedHashMap<Triple<String, Int, Int>, hd.kinoshka.app.data.model.DdbbEpisodeTrack>()
        val ladders = LinkedHashMap<String, Map<String, String>>()
        val voiceRows = LinkedHashMap<String, String>()
        val headersByUrl = LinkedHashMap<String, Map<String, String>>()
        for (parse in parses) {
            for (track in parse.tracks) {
                tracks.putIfAbsent(Triple(track.dubId, track.seasonNumber, track.episodeNumber), track)
            }
            for ((url, ladder) in parse.ladders) ladders.putIfAbsent(url, ladder)
            for ((title, url) in parse.voiceRows) voiceRows.putIfAbsent(title, url)
            // Headers are per-source (turbo needs its embed Referer, videocdn needs none):
            // index every url the source can serve so the player picks the right ones per stream.
            val urls = buildSet {
                add(parse.url)
                addAll(parse.qualities.values)
                addAll(parse.voiceRows.map { it.second })
                addAll(parse.tracks.map { it.playerUrl })
                addAll(parse.ladders.keys)
                parse.ladders.values.forEach { addAll(it.values) }
            }
            for (url in urls) if (url.isNotBlank()) headersByUrl.putIfAbsent(url, parse.headers)
        }
        return winner.copy(
            voiceRows = voiceRows.map { it.key to it.value },
            tracks = tracks.values.toList(),
            ladders = ladders,
            headersByUrl = headersByUrl
        )
    }

    private const val HARVEST_TIMEOUT_MS = 15_000L

    private const val RESOLVE_DEADLINE_MS = 20_000L

    /** Bounded probe budget: worst case (every candidate dead) adds a few seconds to startup. */
    private const val LAUNCH_PROBE_MAX_ATTEMPTS = 8
    // 2.5s cut live winners: the turbo CDN answered >2.5s on two cold probes and 0.9s once warm
    // (Rick and Morty, 13s startup) — the extra headroom turns that triple probe into one.
    private const val LAUNCH_PROBE_TIMEOUT_MS = 4_000L

    /**
     * 2-byte Range GET against a direct CDN url with the playback headers. Returns true on any
     * <400 answer — content-type/body are irrelevant, we only need the token to be alive.
     */
    private fun validateDirectUrl(url: String, headers: Map<String, String>): Boolean =
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1")
                .header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) ->
                if (!k.equals("User-Agent", ignoreCase = true)) {
                    try { builder.header(k, v) } catch (_: IllegalArgumentException) { }
                }
            }
            httpClient.newBuilder()
                .connectTimeout(LAUNCH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(LAUNCH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(LAUNCH_PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
                .build()
                .newCall(builder.build())
                .execute()
                .use { response -> response.code < 400 }
        }.getOrDefault(false)

    /** Public health probe for the player: is this direct CDN url (its token) still answering? */
    suspend fun isDirectUrlAlive(url: String, headers: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) { validateDirectUrl(url, headers) }

    /** Cheap local ranking mirroring [directLadderPreference] without re-encoding entries. */
    private val probeQualityOrder = QUALITY_PREFERENCE_DESC.filter { it != "1440p" }

    private data class LaunchChoice(val url: String, val ladder: Map<String, String>, val verified: Boolean)

    /**
     * Chooses the START stream by probing candidates in priority order:
     * 1) preferred default url (+ its ladder rungs top-down),
     * 2) every other dub's ladder rungs top-down.
     * The first candidate that answers HTTP<400 wins; the returned ladder stays the FULL
     * winner's ladder so quality switching keeps every rung visible. [budget] is a shared
     * one-element probe counter ([LAUNCH_PROBE_MAX_ATTEMPTS]) spent across every candidate
     * source of the resolve, so a dead first source cannot multiply the probe cost.
     */
    private suspend fun pickPlayableLaunch(
        defaultUrl: String,
        defaultLadder: Map<String, String>,
        candidateLadders: Map<String, Map<String, String>>,
        headers: Map<String, String>,
        budget: IntArray,
    ): LaunchChoice = withContext(Dispatchers.IO) {
        var attempts = 0
        // Default first: its own best-rung preference then walk down; identity handled via
        // visiting its ladder exactly once before the other dubs.
        val ordered = LinkedHashMap<String, Map<String, String>>()
        ordered[defaultUrl] = defaultLadder.ifEmpty { mapOf("Auto" to defaultUrl) }
        for ((bestUrl, ladder) in candidateLadders) {
            if (!ordered.containsKey(bestUrl)) ordered[bestUrl] = ladder
        }
        if (!ordered.containsKey(defaultUrl) || budget[0] <= 0) {
            return@withContext LaunchChoice(defaultUrl, defaultLadder, verified = false)
        }

        for ((base, ladder) in ordered) {
            if (budget[0] <= 0) break
            // Probe top-down through this dub's rungs, falling back to the base itself.
            val candidates = (probeQualityOrder.mapNotNull { q -> ladder[q] } + base).distinct()
            for (url in candidates) {
                if (budget[0] <= 0) break
                budget[0] -= 1
                attempts += 1
                if (validateDirectUrl(url, headers)) {
                    KLog.i(TAG, "launch probe OK after $attempts attempt(s): ${url.take(90)}")
                    return@withContext LaunchChoice(url, ladder, verified = true)
                }
                KLog.w(TAG, "launch probe dead ($attempts): ${url.take(90)}")
            }
        }
        // Everything dead (or budget spent): hand back the original default and let the
        // player's tracked retry handle it — probing cannot block playback entirely.
        KLog.w(TAG, "launch probe exhausted ($attempts attempts), using default url")
        LaunchChoice(defaultUrl, defaultLadder, verified = false)
    }

    // --- Session caches -------------------------------------------------------------------
    // Re-entering a title (Watch → back → Watch, or episode switches in the player) must not
    // re-download the players list or re-decode a ~1MB turbo blob every time.

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    private val playersCache = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<List<Pair<String, String>>>>()
    private val playersCacheTtlMs = 5 * 60_000L

    private class TurboCatalog(
        val headers: Map<String, String>,
        val tracks: List<hd.kinoshka.app.data.model.DdbbEpisodeTrack>,
        /** bestUrl -> (quality -> url) for the player's on-demand quality menu. */
        val ladders: Map<String, Map<String, String>>,
        /** Movie-path dub rows (cleaned title -> best-quality url) of this parse. */
        val voiceovers: List<Pair<String, String>> = emptyList(),
        /** url -> playback headers of the source serving it (merged multi-source catalogs). */
        val headersByUrl: Map<String, Map<String, String>> = emptyMap()
    )

    private val turboCatalogs = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<TurboCatalog>>()
    private const val TURBO_CATALOG_TTL_MS = 30 * 60_000L

    // internal: WebmasterStreamSources registers its own catalogs here so the
    // player's quality menus and the movie dropdown see new-source rows like turbo's.
    internal fun registerTurboCatalog(
        kinopoiskId: Int,
        headers: Map<String, String>,
        parse: TurboSerialParse,
        voiceovers: List<Pair<String, String>> = emptyList(),
        headersByUrl: Map<String, Map<String, String>> = emptyMap(),
    ) {
        if (kinopoiskId <= 0) return
        if (parse.tracks.isEmpty() && voiceovers.isEmpty()) return
        turboCatalogs[kinopoiskId] = CacheEntry(
            TurboCatalog(headers, parse.tracks, parse.ladders, voiceovers, headersByUrl),
            System.currentTimeMillis()
        )
    }

    /**
     * Voiceover rows remembered from a previous successful turbo parse for this title. A fresh
     * blob decode varies run-to-run (junk segments shift the base64 phase), so without this
     * fallback a worse decode silently SHRANK the dub list between launches of the same movie.
     */
    fun cachedVoiceoverRows(kinopoiskId: Int): List<Pair<String, String>>? =
        turboCatalogs[kinopoiskId]
            ?.takeIf { System.currentTimeMillis() - it.timestamp < TURBO_CATALOG_TTL_MS }
            ?.data?.voiceovers
            ?.takeIf { it.isNotEmpty() }

    /** Ladder of a direct url across any fresh turbo catalog (player quality menu on switches). */
    fun cachedLadderFor(url: String): Map<String, String>? =
        turboCatalogs.values.firstOrNull { entry ->
            System.currentTimeMillis() - entry.timestamp < TURBO_CATALOG_TTL_MS &&
                entry.data.ladders.containsKey(url)
        }?.data?.ladders?.get(url)

    /**
     * Drops the memoized embed resolve for [kinopoiskId]: a tracked-load retry after mpv reported
     * a dead stream must not be handed the SAME expired url back from the 3-minute cache — that
     * burned the whole retry budget on an identical failure ("фильм иногда не запускается").
     */
    fun evictResolveCache(kinopoiskId: Int) {
        if (kinopoiskId > 0) {
            resolveCache.remove(kinopoiskId)
            // The turbo catalog's ladders/voiceover links share the same dated CDN tokens —
            // serving them to a retry just re-hands the expired urls.
            turboCatalogs.remove(kinopoiskId)
            // A still-running shared resolve would hand its in-progress result to the retry via
            // the join path — cancel it so the next resolve starts fresh.
            inFlightResolves.remove(kinopoiskId)?.cancel()
        }
    }


    /**
     * Concrete quality variants of a direct turbo url from the cached serial catalog, or null
     * when the catalog is absent/expired — the player then offers plain Auto playback.
     */
    fun directQualities(kinopoiskId: Int, url: String): Map<String, String>? {
        val entry = turboCatalogs[kinopoiskId]?.takeIf {
            System.currentTimeMillis() - it.timestamp < TURBO_CATALOG_TTL_MS
        } ?: return null
        return entry.data.ladders[url]
    }

    /** Headers required to play direct turbo urls of the cached catalog for [kinopoiskId]. */
    fun directHeaders(kinopoiskId: Int): Map<String, String> =
        turboCatalogs[kinopoiskId]
            ?.takeIf { System.currentTimeMillis() - it.timestamp < TURBO_CATALOG_TTL_MS }
            ?.data?.headers
            .orEmpty()

    /**
     * Headers for ONE direct url of the cached catalog for [kinopoiskId]: the merged catalog
     * can carry dubs from several providers whose urls need different headers (turbo requires
     * its embed's Referer, VideoCDN needs none), so the lookup is per-url with the winner's
     * headers as fallback.
     */
    fun directHeaders(kinopoiskId: Int, url: String): Map<String, String> {
        val entry = turboCatalogs[kinopoiskId]
            ?.takeIf { System.currentTimeMillis() - it.timestamp < TURBO_CATALOG_TTL_MS }
            ?: return emptyMap()
        return entry.data.headersByUrl[url] ?: entry.data.headers
    }

    // Best-first: the resolver's default pick doubles as the player's "Auto" quality, and Auto
    // means "start at the best variant, step down if the network can't sustain it" (the player
    // runs a stall watchdog that walks this ladder downwards).
    private val qualityPreference = listOf("2160p", "1080p", "720p", "480p", "360p", "240p", "Auto")

    private fun fetchPlayers(kinopoiskId: Int): List<Pair<String, String>> {
        playersCache[kinopoiskId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < playersCacheTtlMs) return entry.data
            playersCache.remove(kinopoiskId)
        }
        val result = fetchDdbbPlayers(kinopoiskId)
        if (result.isNotEmpty()) {
            playersCache[kinopoiskId] = CacheEntry(result, System.currentTimeMillis())
        }
        return result
    }

    private fun fetchDdbbPlayers(kinopoiskId: Int): List<Pair<String, String>> {
        // Three quick attempts beat two slow ones: the host intermittently drops connects for
        // a few seconds at a time, so an extra try usually lands (live-verified on Rick&Morty).
        for (attempt in 0..2) {
            runCatching {
                val req = Request.Builder()
                    .url(String.format(java.util.Locale.US, PLAYERS_API, kinopoiskId))
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Origin", "https://ddbb.lol")
                    .addHeader("Referer", "https://ddbb.lol/")
                    .build()
                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching
                    val body = response.body.string()
                    val arr = org.json.JSONObject(body).optJSONArray("data") ?: return@runCatching
                    val seen = mutableSetOf<String>()
                    val result = mutableListOf<Pair<String, String>>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val type = obj.optString("type", "").trim()
                        val url = obj.optString("iframeUrl", "").trim()
                        if (!url.startsWith("http")) continue
                        if (type.isEmpty() || !seen.add(type.lowercase())) continue
                        result += type to url
                    }
                    if (result.isNotEmpty()) {
                        return result.sortedBy { typeRank(it.first) }
                    }
                }
            }.onFailure { KLog.w(TAG, "players api attempt $attempt failed", it) }
        }
        return emptyList()
    }

    private fun fetchHtml(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Referer", "https://ddbb.lol/")
            .build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body.string().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // --- Extraction -------------------------------------------------------

    private val COLLAPS_HLS_REGEX = Regex("""hls:\s*"([^"]+\.m3u8[^"]*)"""")
    private val TURBO_BLOB_REGEX = Regex("""new\s+Player\s*\(\s*"([A-Za-z0-9+/=\s]+)"\s*\)""")
    private val TURBO_JUNK_REGEX = Regex("""//[A-Za-z0-9+/]*=[A-Z]?""")
    // Turbo labels every stream "[240p]url,..." — and the urls are deliberately extension-less
    // obfuscated paths (the CDN only resolves them with the embed's Referer), so the match must
    // NOT require a .m3u8/.mp4 suffix; it just runs to the next separator.
    private val TURBO_FILE_REGEX = Regex("""\[([^\[\],]+)\]((?:https?:)(?:\\/|[^,"])+)""")
    private val QUALITY_MARKER_REGEX = Regex("""\[\d{3,4}p\]""")
    private val TURBO_LABEL_REGEX = Regex("""^(Auto|[0-9]{3,4}[pi])$""", RegexOption.IGNORE_CASE)

    /**
     * Returns `(headers, qualities)` for the first recognized embed format, where headers must be
     * sent alongside every request to the returned URLs (some CDNs check the embed's own origin).
     */
    // Public for unit tests (:app depends on :shared as a separate module, internal is invisible there).
    fun extractFromEmbed(html: String, embedUrl: String): Pair<Map<String, String>, Map<String, String>>? {
        COLLAPS_HLS_REGEX.find(html)?.let { match ->
            val hls = match.groupValues[1].trim()
            if (hls.startsWith("http")) return emptyMap<String, String>() to mapOf("Auto" to hls)
        }

        TURBO_BLOB_REGEX.find(html)?.let { match ->
            val qualities = extractTurboQualities(match.groupValues[1])
            if (qualities.isNotEmpty()) {
                val origin = runCatching { java.net.URI(embedUrl) }.getOrNull()
                    ?.let { "${it.scheme}://${it.host}/" }
                    ?: "https://${runCatching { java.net.URI(embedUrl).host }.getOrNull().orEmpty()}/"
                return mapOf(
                    "Referer" to origin,
                    "User-Agent" to USER_AGENT
                ) to qualities
            }
            KLog.w(TAG, "turbo blob present but no stream harvested")
        }
        return null
    }

    // Public for unit tests (see extractFromEmbed above).
    fun decodeTurboConfig(blob: String): String? = findTurboWindow(blob)

    /** True when a decoded window looks like a real player config (markers + plausible head). */
    private fun looksLikeTurboPayload(decoded: String?): Boolean =
        decoded != null &&
            QUALITY_MARKER_REGEX.containsMatchIn(decoded) &&
            decoded.contains("https")

    private fun isPlausibleJsonHead(decoded: String?): Boolean {
        val trimmed = decoded?.trimStart() ?: return false
        return trimmed.startsWith("[") || trimmed.startsWith("{")
    }

    private val BASE64_CLEAN_FILTER: (Char) -> Boolean = { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

    /** One base64 decode of [clean] from [offset] to the nearest 4-char boundary. */

    /**
     * Finds base64 alignments whose decodes contain stream markers.
     *
     * Junk-segment removal shifts the base64 phase mid-stream, so each of the four possible
     * phases (offset % 4) decodes DIFFERENT regions cleanly — verified live on The Boys, where
     * one phase yielded 30 episodes and the union of four yielded the complete 40-episode
     * catalog. Decoding is cheap (≤4 full passes), so every phase is always returned; callers
     * merge parsed entries across windows.
     */
    private fun findTurboWindows(blob: String): List<String> {
        val stripped = blob.replace(TURBO_JUNK_REGEX, "")
        val windows = ArrayList<String>(4)
        for (candidate in listOf(stripped, blob)) {
            val clean = candidate.filter(BASE64_CLEAN_FILTER)
            if (clean.length < 8) continue
            for (phase in 0..3) {
                val decoded = fullDecodeAt(clean, phase) ?: continue
                if (looksLikeTurboPayload(decoded) && decoded !in windows) windows += decoded
            }
            if (windows.isNotEmpty()) {
                // JSON-head windows first (structural parse succeeds there), then richest fuzzy.
                return windows.sortedWith(
                    compareByDescending<String> { isPlausibleJsonHead(it) }.thenByDescending { it.length }
                )
            }
        }
        return emptyList()
    }

    private fun findTurboWindow(blob: String): String? = findTurboWindows(blob).firstOrNull()

    /** One base64 decode of [clean] from [offset] to the nearest 4-char boundary. */
    private fun fullDecodeAt(clean: String, offset: Int): String? {
        val length = clean.length - offset
        if (length < 4) return null
        val usable = clean.substring(offset, offset + length / 4 * 4)
        return runCatching {
            String(java.util.Base64.getDecoder().decode(usable), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Harvests "[quality]url" pairs straight from the obfuscated blob: walks base64 alignments,
     * and for each decode window that contains quality markers regexes out every stream URL.
     * A window whose URLs were all corrupted by junk stripping is simply skipped in favour of
     * the next alignment, making the extraction resilient to single-byte corruption.
     */
    internal fun extractTurboQualities(blob: String): Map<String, String> {
        val decoded = findTurboWindow(blob) ?: return emptyMap()
        val qualities = linkedMapOf<String, String>()
        collectFileFieldQualities(decoded, qualities)
        return qualities
    }

    private val UNESCAPE_UNICODE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")
    private val TITLE_MARKER_REGEX = Regex("""\{"title":""")

    private fun unescapeJsonUnicode(value: String): String =
        UNESCAPE_UNICODE_REGEX.replace(value) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\/", "/")
            // Turbo wraps some dub labels in escaped quotes ("title":"\"(RU) DUB\""); left in,
            // they leak into the picker and break the "(RU)"-prefix cleanup.
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

    /**
     * Every voiceover track of a turbo config as (display title, best-quality url).
     *
     * Movie path: a movie blob holds one entry per dub with no episode labels, so every entry
     * becomes one dropdown row — now with human-readable names instead of raw "(RU) MVO | …".
     */
    internal fun extractTurboTracks(blob: String): List<Pair<String, String>> =
        voiceoverRowsFromEntries(extractTurboEntries(blob))

    /** Flat dub rows for the movie dropdown: cleaned name → best-quality url of its entry. */
    private fun voiceoverRowsFromEntries(entries: List<TurboEntry>): List<Pair<String, String>> {
        val rows = LinkedHashMap<String, String>()
        val seenUrls = HashSet<String>()
        var genericIndex = 0
        for (entry in entries) {
            val best = bestOfLadder(entry.ladder) ?: continue
            // Same stream under two labels = one voiceover, not two.
            if (!seenUrls.add(best.second.substringBefore('#').substringBefore('?'))) continue
            var cleaned = cleanDdbbDubTitle(entry.rawTitle)
            if (cleaned.isBlank()) {
                genericIndex += 1
                cleaned = "Озвучка $genericIndex"
            }
            rows.putIfAbsent(cleaned, best.second)
        }
        return rows.map { it.key to it.value }
    }

    /** One parsed entry of a turbo config: raw dub label, optional t1 episode label, quality ladder. */
    internal data class TurboEntry(val rawTitle: String, val label: String?, val ladder: Map<String, String>)

    /** Structured result of a turbo config parse: playable serial rows plus their full ladders. */
    internal data class TurboSerialParse(
        val tracks: List<hd.kinoshka.app.data.model.DdbbEpisodeTrack>,
        /** bestUrl -> (quality -> url); feeds the player's on-demand quality menu. */
        val ladders: Map<String, Map<String, String>>
    )

    private fun isValidStreamUrl(url: String): Boolean {
        if (url.contains('�')) return false
        if (url.any { it.code < 32 || it.code > 126 }) return false
        return runCatching { java.net.URI(url); true }.getOrDefault(false)
    }

    private fun parseLadder(fileField: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        TURBO_FILE_REGEX.findAll(fileField).forEach { match ->
            val label = match.groupValues[1].trim()
            if (!TURBO_LABEL_REGEX.matches(label)) return@forEach
            val url = match.groupValues[2].trim()
            if (!url.startsWith("http") || url.length < 20) return@forEach
            if (!isValidStreamUrl(url)) return@forEach
            if (!out.containsKey(label)) out[label] = url
        }
        return out
    }

    // Best-first rung order for direct episode ladders. Unlike [qualityPreference] (embed-level
    // "Auto" pick), this prefers 720p: the player's stall watchdog starts at stream.url and steps
    // down, so booting a phone at 2160p would stall before ever settling.
    // The direct URL chosen for a dub is also the key into its per-dub ladder.  It must agree
    // with the resolver's Auto policy: picking 720p here made a Turbo dub start at 720 even
    // though its ladder (and the embed's default) contained 1080p.
    private val directLadderPreference = listOf("2160p", "1080p", "720p", "480p", "360p", "240p")

    /** (quality, url) of a ladder's best rung per [directLadderPreference]. */
    private fun bestOfLadder(ladder: Map<String, String>): Pair<String, String>? =
        ladder.entries.minByOrNull { entry ->
            directLadderPreference.indexOf(entry.key).let { if (it < 0) Int.MAX_VALUE else it }
        }?.let { it.key to it.value }

    /**
     * Parses a turbo config into structured serial rows.
     *
     * Serial blobs are flat arrays of dub×episode entries: {"title":"(RU) MVO | GoShows",
     * "t1":"S05E07 - Name","file":"[240p]url,[720p]url,…"} — the previous parser collapsed this
     * to ONE row per dub (first entry won), which lost every episode beyond the first and showed
     * raw technical labels. Entries whose t1 carries no S/E numbers are ignored here (movie
     * configs go through [extractTurboTracks]).
     */
    internal fun extractTurboSerial(blob: String): TurboSerialParse =
        buildSerialParse(extractTurboEntries(blob))

    /**
     * Decodes a turbo blob and lists its raw entries (title, optional t1 label, quality ladder).
     *
     * Entries are parsed from EVERY phase window and merged by (title, t1): junk-segment removal
     * shifts the base64 phase mid-stream, so each window recovers different regions — the merge
     * is what makes the full episode catalog visible.
     */
    internal fun extractTurboEntries(blob: String): List<TurboEntry> {
        val windows = findTurboWindows(blob)
        if (windows.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, TurboEntry>()
        for (decoded in windows) {
            for (entry in parseEntriesFromWindow(decoded)) {
                if (entry.ladder.isEmpty()) continue
                val key = "${entry.rawTitle.lowercase()}\u0000${entry.label.orEmpty()}"
                val existing = merged[key]
                if (existing == null) {
                    merged[key] = entry
                } else if (existing.label.isNullOrBlank() && !entry.label.isNullOrBlank()) {
                    // Strictly-better metadata only: a corrupted window can stitch one entry's
                    // title to ANOTHER dub's file field (with a longer, concatenated ladder) —
                    // overriding by ladder size used to let that wrong pairing win, making two
                    // dropdown dubs play the same audio. First clean decode wins.
                    merged[key] = entry
                }
            }
        }
        KLog.i(TAG, "turbo entries: ${merged.size} merged from ${windows.size} phase window(s)")
        return merged.values.toList()
    }

    /** Structural JSON parse of one decoded window, falling back to positional scanning. */
    private fun parseEntriesFromWindow(decoded: String): List<TurboEntry> {
        // Preferred path: the JSON-first window decode parses as the player config — read
        // entries structurally so titles/labels survive unicode escapes and punctuation.
        val jsonEntries = runCatching {
            val root = org.json.JSONObject(decoded)
            val arr = root.optJSONArray("file")
            buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val title = unescapeJsonUnicode(obj.optString("title")).trim()
                        val label = unescapeJsonUnicode(obj.optString("t1")).trim().takeIf { it.isNotEmpty() }
                        val ladder = parseLadder(obj.optString("file").replace("\\/", "/"))
                        if (ladder.isNotEmpty()) add(TurboEntry(title, label, ladder))
                    }
                }
            }
        }.getOrNull().orEmpty()

        if (jsonEntries.isNotEmpty()) return jsonEntries
        return positionalScanEntries(decoded)
    }

    /**
     * Fallback for corrupted decodes (junk-stripped base64): walk segments delimited by
     * `{"title":"` markers and associate each segment's t1 label with its own `[q]url` ladder.
     */
    private fun positionalScanEntries(decoded: String): List<TurboEntry> {
        val markers = TITLE_MARKER_REGEX.findAll(decoded).toList()
        if (markers.isEmpty()) return emptyList()
        return buildList {
            for (i in markers.indices) {
                val start = markers[i].range.last + 1
                val end = markers.getOrNull(i + 1)?.range?.first ?: decoded.length
                val segment = decoded.substring(start, minOf(decoded.length, end))
                val title = unescapeJsonUnicode(
                    segment.substringBefore("\",").trim()
                ).trim()
                val label = Regex("""\"t1\"\s*:\s*\"((?:[^"\\]|\\.)*)\"""").find(segment)
                    ?.let { unescapeJsonUnicode(it.groupValues[1]).trim() }?.takeIf { it.isNotEmpty() }
                val ladder = parseLadder(segment.take(12_000).replace("\\/", "/"))
                if (ladder.isNotEmpty()) add(TurboEntry(title, label, ladder))
            }
        }
    }

    /**
     * Per-dub quality ladders of ANY turbo config (movie or serial), keyed by the entry's
     * best-quality url. Feeds the player's on-demand quality menu: after a voiceover switch the
     * active stream's ladder must come from THAT dub's own file field, not from a whole-blob
     * label scan that mixes urls across dubs.
     */
    private fun buildLadders(entries: List<TurboEntry>): Map<String, Map<String, String>> {
        val ladders = LinkedHashMap<String, Map<String, String>>()
        for (entry in entries) {
            val best = bestOfLadder(entry.ladder) ?: continue
            ladders.putIfAbsent(best.second, entry.ladder)
        }
        return ladders
    }

    private fun buildSerialParse(entries: List<TurboEntry>): TurboSerialParse {
        val labeled = entries.filter { parseEpisodeNumbers(it.label) != null }
        if (labeled.isEmpty()) return TurboSerialParse(emptyList(), emptyMap())

        val anyProperDub = labeled.any { cleanDdbbDubTitle(it.rawTitle).isNotBlank() }
        val tracks = LinkedHashMap<Triple<String, Int, Int>, hd.kinoshka.app.data.model.DdbbEpisodeTrack>()
        val ladders = HashMap<String, Map<String, String>>()
        // (season, episode) -> stream urls already registered under some dub. The provider lists
        // the same track under several labels, and a corrupted decode can stitch a title to
        // another dub's file — either way two dropdown entries would play IDENTICAL audio.
        // Different dubs always have different stream paths, so an url collision means duplicate.
        val seenStreamUrls = HashMap<Pair<Int, Int>, HashSet<String>>()
        var duplicateRows = 0
        var genericIndex = 0

        for (entry in labeled) {
            val (season, episode) = parseEpisodeNumbers(entry.label!!) ?: continue
            var cleaned = cleanDdbbDubTitle(entry.rawTitle)
            if (cleaned.isBlank()) {
                // Orphan episode rows without an attributable dub: drop them when real dubs
                // exist, otherwise surface under a generic name so nothing becomes unplayable.
                if (anyProperDub) continue
                genericIndex += 1
                cleaned = "Озвучка $genericIndex"
            }
            val (bestQ, bestUrl) = bestOfLadder(entry.ladder) ?: continue
            val streamKey = bestUrl.substringBefore('#').substringBefore('?')
            val seasonUrls = seenStreamUrls.getOrPut(season to episode) { HashSet() }
            if (!seasonUrls.add(streamKey)) {
                duplicateRows += 1
                continue
            }
            val dubId = "turbo|" + cleaned.lowercase().replace(Regex("[^a-zа-я0-9]+"), "-").trim('-')
            val key = Triple(dubId, season, episode)
            if (tracks.containsKey(key)) continue
            tracks[key] = hd.kinoshka.app.data.model.DdbbEpisodeTrack(
                dubId = dubId,
                dubTitle = cleaned,
                seasonNumber = season,
                episodeNumber = episode,
                title = episodeNameFromLabel(entry.label),
                playerUrl = bestUrl
            )
            ladders[bestUrl] = entry.ladder
        }
        if (duplicateRows > 0) KLog.i(TAG, "turbo serial parse: dropped $duplicateRows duplicate-stream rows")
        val seasons = tracks.values.map { it.seasonNumber }.distinct().sorted()
        KLog.i(TAG, "turbo serial parse: ${tracks.size} rows, ${ladders.size} ladders, seasons=$seasons")
        return TurboSerialParse(tracks.values.toList(), ladders)
    }

    /** S05E07 / 5x07 prefix of a t1 label → (season, episode); null when absent. */
    private fun parseEpisodeNumbers(label: String?): Pair<Int, Int>? {
        val text = label?.trim().orEmpty()
        if (text.isEmpty()) return null
        Regex("""(?i)^S(\d{1,2})E(\d{1,3})""").find(text)?.let {
            return (it.groupValues[1].toInt() to it.groupValues[2].toInt())
        }
        Regex("""(?i)^(\d{1,2})x(\d{1,3})""").find(text)?.let {
            return (it.groupValues[1].toInt() to it.groupValues[2].toInt())
        }
        return null
    }

    /** "S05E07 - Blood and Bone" → "Blood and Bone" (null when the label carries no name). */
    private fun episodeNameFromLabel(label: String): String? {
        val idx = label.indexOfFirst { it == '-' }
        if (idx < 0) return null
        return label.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
    }

    // --- Dub label cleanup ---------------------------------------------------------------

    private val DUB_KIND_LABELS = mapOf(
        "MVO" to "озвучка",
        "VO" to "закадровая",
        "DUB" to "дубляж",
        "DVO" to "двухголосая",
        "SUB" to "субтитры",
        "SUBTITLES" to "субтитры"
    )

    /** Kind suffix appended in parentheses when it adds meaning ("Кубик в Кубе (двухголосая)"). */
    private val DUB_KIND_SUFFIXES = mapOf(
        "MVO" to null,
        "VO" to "закадровая",
        "DUB" to "дубляж",
        "DVO" to "двухголосая",
        "SUB" to "субтитры",
        "SUBTITLES" to "субтитры"
    )

    /**
     * "(RU) DVO | Кубик в Кубе | Kubik³" → "Кубик в Кубе (двухголосая)";
     * "(RU) MVO | GoShows" → "GoShows". Strips language tags and provider jargon so the picker
     * shows names a viewer recognises; unknown formats pass through trimmed.
     */
    internal fun cleanDdbbDubTitle(raw: String): String {
        var text = raw.trim().trim('"', '\'').trim()
        if (text.isEmpty()) return ""
        text = text.replace(Regex("""^[\[(]\s*[A-Za-z]{2,3}\s*[\])]\s*"""), "")
        val parts = text.split('|', '/', '•')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        var kindKey: String? = null
        var studio = ""
        for (part in parts) {
            val upper = part.uppercase()
            if (kindKey == null && DUB_KIND_LABELS.containsKey(upper)) {
                kindKey = upper
                continue
            }
            if (studio.isEmpty() && !DUB_KIND_LABELS.containsKey(upper)) {
                studio = part
                // A second segment after the studio is usually a latin alias ("Kubik³") — ignore.
                break
            }
        }
        val suffix = kindKey?.let { DUB_KIND_SUFFIXES[it] }
        return when {
            studio.isNotBlank() && suffix != null -> "$studio ($suffix)"
            studio.isNotBlank() -> studio
            kindKey != null -> DUB_KIND_LABELS[kindKey]!!.replaceFirstChar(Char::uppercase)
            else -> parts.firstOrNull() ?: text
        }
    }

    private fun collectFileFieldQualities(fileField: String, qualities: MutableMap<String, String>) {
        TURBO_FILE_REGEX.findAll(fileField).forEach { match ->
            val label = match.groupValues[1].trim()
            val url = match.groupValues[2].replace("\\/", "/").trim()
            // Quality labels only ("720p", "Auto") — this skips subtitle tracks "[Russian]...srt"
            // and poster fields that share the same bracket syntax.
            if (!TURBO_LABEL_REGEX.matches(label)) return@forEach
            if (!isValidStreamUrl(url)) return@forEach
            if (url.startsWith("http") && url.length > 20 && !qualities.containsKey(label)) {
                qualities[label] = url
            }
        }
    }
}
