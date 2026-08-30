package hd.kinoshka.app.data.source

import android.util.Log
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Extracts direct playable streams from video-source embed pages served by the ddbb aggregator
 * (the same sources the web player uses), so the native mpvEx player can play movies Kodik does
 * not carry.
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
     * Walks the ddbb player list for [kinopoiskId] and returns the first successfully extracted
     * stream, or null when every source fails / the aggregator has nothing.
     *
     * Per source: cheap HTML regex first, then a headless-WebView harvest for sources whose
     * streams hide behind JS bootstrapping or region checks (alloha/veoveo), or whose tokens
     * expire too fast to survive the round-trip (collaps).
     */
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<DdbbStream>>()
    private const val RESOLVE_CACHE_TTL_MS = 3 * 60_000L

    /**
     * Cached entry point: the details screen prefetches on open and the Watch button resolves
     * again on press — without this memo the same embed would be downloaded+decoded twice.
     * Short TTL keeps expiring CDN tokens from outliving their validity.
     */
    suspend fun resolveMovieStream(kinopoiskId: Int): DdbbStream? = withContext(Dispatchers.IO) {
        if (kinopoiskId <= 0) return@withContext null
        resolveCache[kinopoiskId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < RESOLVE_CACHE_TTL_MS) return@withContext entry.data
            resolveCache.remove(kinopoiskId)
        }
        val resolved = resolveMovieStreamInternal(kinopoiskId)
        if (resolved != null) {
            resolveCache[kinopoiskId] = CacheEntry(resolved, System.currentTimeMillis())
        }
        resolved
    }

    private suspend fun resolveMovieStreamInternal(kinopoiskId: Int): DdbbStream? = withContext(Dispatchers.IO) {
        if (kinopoiskId <= 0) return@withContext null
        val players = fetchPlayers(kinopoiskId)
        Log.i(TAG, "ddbb offered ${players.size} sources for kp=$kinopoiskId: ${players.map { it.first }}")
        // Hard budget: the movie race starts playback from the winner as soon as one source
        // succeeds, but a title where every source stalls must not hold the Watch button for
        // minutes — a single WebView harvest alone can burn HARVEST_TIMEOUT_MS.
        val deadline = System.currentTimeMillis() + RESOLVE_DEADLINE_MS
        var harvestAttempted = false
        players.forEach { (type, iframeUrl) ->
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "resolveMovieStream deadline hit, giving up before ${type.lowercase()}")
                return@forEach
            }
            val lowerType = type.lowercase()

            val html = fetchHtml(iframeUrl)
            if (html != null) {
                extractFromEmbed(html, iframeUrl)?.let { (headers, qualities) ->
                    if (qualities.isNotEmpty()) {
                        val bestKey = qualityPreference.firstOrNull { qualities.containsKey(it) } ?: qualities.keys.first()
                        Log.i(TAG, "$lowerType: extracted ${qualities.size} qualities, using $bestKey")
                        // extractTurboTracks must receive the obfuscated config blob, not the whole
                        // embed page: findTurboWindow scans a short base64 prefix, and feeding it the
                        // full HTML made the window search fail → voiceover list silently empty.
                        val turboBlob = if (lowerType == "turbo") TURBO_BLOB_REGEX.find(html)?.groupValues?.get(1) else null
                        // Single decode feeds both consumers: flat dub rows for the movie
                        // dropdown and structured dub×episode rows for series playback.
                        val turboEntries = turboBlob?.let { extractTurboEntries(it) }.orEmpty()
                        val translations = voiceoverRowsFromEntries(turboEntries)
                            .ifEmpty { cachedVoiceoverRows(kinopoiskId).orEmpty() }
                        val serialParse = buildSerialParse(turboEntries)
                        // Per-dub ladders keyed by best url — movies included. Without them the
                        // player's quality menu dies on the first voiceover switch (the catalog
                        // used to carry ladders for SERIES entries only).
                        val perDubLadders = buildLadders(turboEntries)
                        if (serialParse.tracks.isNotEmpty() || translations.isNotEmpty()) {
                            registerTurboCatalog(kinopoiskId, headers, serialParse.copy(ladders = perDubLadders), translations)
                        }
                        if (serialParse.tracks.isNotEmpty()) {
                            Log.i(TAG, "$lowerType: structured serial catalog: " +
                                "${serialParse.tracks.map { it.dubTitle }.distinct().size} dubs, " +
                                "${serialParse.tracks.map { it.seasonNumber to it.episodeNumber }.distinct().size} episodes")
                        }
                        // The launch stream must be ONE dub's ladder, not a whole-blob scan:
                        // collectFileFieldQualities keeps the FIRST url seen per quality label,
                        // so with several dubs the "720p" entry could belong to a DIFFERENT
                        // voiceover than the selected one — switching quality silently swapped
                        // the audio track. Default dub = first row of the merged list.
                        val defaultDubUrl = translations.firstOrNull()?.second
                        val defaultLadder = defaultDubUrl?.let { perDubLadders[it] }
                        // Corrupted base64 phase windows hand out urls that look valid but 404
                        // (live log kp=5457758: fresh token, instant HTTP 404). The winner is
                        // probed; on failure the search walks DOWN the ladder and ACROSS the
                        // other dubs until something actually answers.
                        val chosen = pickPlayableLaunch(
                            defaultUrl = defaultDubUrl ?: qualities.getValue(bestKey),
                            defaultLadder = defaultLadder ?: qualities,
                            candidateLadders = perDubLadders,
                            headers = headers
                        )
                        val chosenLadder = linkedMapOf<String, String>().apply {
                            directLadderPreference.forEach { q -> chosen.ladder[q]?.let { put(q, it) } }
                            chosen.ladder.forEach { (q, u) -> if (!containsKey(q)) put(q, u) }
                        }
                        return@withContext DdbbStream(
                            url = chosen.url,
                            headers = headers,
                            qualities = chosenLadder,
                            sourceName = type.replaceFirstChar { it.uppercase() },
                            translations = translations,
                            episodeTracks = serialParse.tracks
                        )
                    }
                }
            }

            if (lowerType in HARVESTABLE_TYPES && html != null && !harvestAttempted) {
                // One harvest attempt per resolve: serial headless-WebView runs over every
                // remaining source multiply latency without materially raising hit-rate.
                harvestAttempted = true
                Log.i(TAG, "$lowerType: direct extraction failed, harvesting embed in a headless browser…")
                WebViewStreamHarvester.harvest(
                    embedUrl = iframeUrl,
                    pageReferer = "https://ddbb.lol/",
                    timeoutMs = HARVEST_TIMEOUT_MS,
                )?.let { harvested ->
                    val referer = harvested.referer
                        ?: runCatching { java.net.URI(iframeUrl) }.getOrNull()?.let { "${it.scheme}://${it.host}/" }
                        ?: "https://ddbb.lol/"
                    Log.i(TAG, "$lowerType: harvested ${harvested.url.take(100)}")
                    return@withContext DdbbStream(
                        url = harvested.url,
                        headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT),
                        qualities = linkedMapOf("Auto" to harvested.url),
                        sourceName = type.replaceFirstChar { it.uppercase() }
                    )
                }
                Log.w(TAG, "$lowerType: harvest found nothing")
            } else {
                Log.w(TAG, "$lowerType: no stream extracted")
            }
        }
        null
    }

    private const val HARVEST_TIMEOUT_MS = 15_000L

    private const val RESOLVE_DEADLINE_MS = 20_000L

    /** Bounded probe budget: worst case (every candidate dead) adds ~2-3s to startup. */
    private const val LAUNCH_PROBE_MAX_ATTEMPTS = 8
    private const val LAUNCH_PROBE_TIMEOUT_MS = 2_500L

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

    private data class LaunchChoice(val url: String, val ladder: Map<String, String>)

    /**
     * Chooses the START stream by probing candidates in priority order:
     * 1) preferred default url (+ its ladder rungs top-down),
     * 2) every other dub's ladder rungs top-down.
     * The first candidate that answers HTTP<400 wins; the returned ladder stays the FULL
     * winner's ladder so quality switching keeps every rung visible.
     */
    private suspend fun pickPlayableLaunch(
        defaultUrl: String,
        defaultLadder: Map<String, String>,
        candidateLadders: Map<String, Map<String, String>>,
        headers: Map<String, String>,
    ): LaunchChoice = withContext(Dispatchers.IO) {
        var attempts = 0
        // Default first: its own best-rung preference then walk down; identity handled via
        // visiting its ladder exactly once before the other dubs.
        val ordered = LinkedHashMap<String, Map<String, String>>()
        ordered[defaultUrl] = defaultLadder.ifEmpty { mapOf("Auto" to defaultUrl) }
        for ((bestUrl, ladder) in candidateLadders) {
            if (!ordered.containsKey(bestUrl)) ordered[bestUrl] = ladder
        }
        if (!ordered.containsKey(defaultUrl)) return@withContext LaunchChoice(defaultUrl, defaultLadder)

        for ((base, ladder) in ordered) {
            if (attempts >= LAUNCH_PROBE_MAX_ATTEMPTS) break
            // Probe top-down through this dub's rungs, falling back to the base itself.
            val candidates = (probeQualityOrder.mapNotNull { q -> ladder[q] } + base).distinct()
            for (url in candidates) {
                if (attempts >= LAUNCH_PROBE_MAX_ATTEMPTS) break
                attempts += 1
                if (validateDirectUrl(url, headers)) {
                    Log.i(TAG, "launch probe OK after $attempts attempt(s): ${url.take(90)}")
                    return@withContext LaunchChoice(url, ladder)
                }
                Log.w(TAG, "launch probe dead ($attempts): ${url.take(90)}")
            }
        }
        // Everything dead (or budget spent): hand back the original default and let the
        // player's tracked retry handle it — probing cannot block playback entirely.
        Log.w(TAG, "launch probe exhausted ($attempts attempts), using default url")
        LaunchChoice(defaultUrl, defaultLadder)
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
        val voiceovers: List<Pair<String, String>> = emptyList()
    )

    private val turboCatalogs = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<TurboCatalog>>()
    private const val TURBO_CATALOG_TTL_MS = 30 * 60_000L

    private fun registerTurboCatalog(
        kinopoiskId: Int,
        headers: Map<String, String>,
        parse: TurboSerialParse,
        voiceovers: List<Pair<String, String>> = emptyList(),
    ) {
        if (kinopoiskId <= 0) return
        if (parse.tracks.isEmpty() && voiceovers.isEmpty()) return
        turboCatalogs[kinopoiskId] = CacheEntry(
            TurboCatalog(headers, parse.tracks, parse.ladders, voiceovers),
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

    // Best-first: the resolver's default pick doubles as the player's "Auto" quality, and Auto
    // means "start at the best variant, step down if the network can't sustain it" (the player
    // runs a stall watchdog that walks this ladder downwards).
    private val qualityPreference = listOf("2160p", "1080p", "720p", "480p", "360p", "240p", "Auto")

    private fun fetchPlayers(kinopoiskId: Int): List<Pair<String, String>> {
        playersCache[kinopoiskId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < playersCacheTtlMs) return entry.data
            playersCache.remove(kinopoiskId)
        }
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
                    val body = response.body?.string() ?: return@runCatching
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
                        val sorted = result.sortedBy { typeRank(it.first) }
                        playersCache[kinopoiskId] = CacheEntry(sorted, System.currentTimeMillis())
                        return sorted
                    }
                }
            }.onFailure { Log.w(TAG, "players api attempt $attempt failed", it) }
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
            response.body?.string()?.takeIf { it.isNotEmpty() }
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
    internal fun extractFromEmbed(html: String, embedUrl: String): Pair<Map<String, String>, Map<String, String>>? {
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
            Log.w(TAG, "turbo blob present but no stream harvested")
        }
        return null
    }

    internal fun decodeTurboConfig(blob: String): String? = findTurboWindow(blob)

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
        Log.i(TAG, "turbo entries: ${merged.size} merged from ${windows.size} phase window(s)")
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
        if (duplicateRows > 0) Log.i(TAG, "turbo serial parse: dropped $duplicateRows duplicate-stream rows")
        val seasons = tracks.values.map { it.seasonNumber }.distinct().sorted()
        Log.i(TAG, "turbo serial parse: ${tracks.size} rows, ${ladders.size} ladders, seasons=$seasons")
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
