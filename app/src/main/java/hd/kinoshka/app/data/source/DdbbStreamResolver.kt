package hd.kinoshka.app.data.source

import android.util.Log
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
            .connectTimeout(10, TimeUnit.SECONDS)
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
        val translations: List<Pair<String, String>> = emptyList()
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
    suspend fun resolveMovieStream(kinopoiskId: Int): DdbbStream? = withContext(Dispatchers.IO) {
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
                        val translations = if (lowerType == "turbo") extractTurboTracks(html).map { it.first to it.second } else emptyList()
                        return@withContext DdbbStream(
                            url = qualities.getValue(bestKey),
                            headers = headers,
                            qualities = qualities,
                            sourceName = type.replaceFirstChar { it.uppercase() },
                            translations = translations
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

    // Best-first: the resolver's default pick doubles as the player's "Auto" quality, and Auto
    // means "start at the best variant, step down if the network can't sustain it" (the player
    // runs a stall watchdog that walks this ladder downwards).
    private val qualityPreference = listOf("2160p", "1080p", "720p", "480p", "360p", "240p", "Auto")

    private fun fetchPlayers(kinopoiskId: Int): List<Pair<String, String>> {
        for (attempt in 0..1) {
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
                    if (result.isNotEmpty()) return result.sortedBy { typeRank(it.first) }
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

    /** Finds the base64 alignment whose decode actually contains stream markers. */
    private fun findTurboWindow(blob: String): String? {
        val stripped = blob.replace(TURBO_JUNK_REGEX, "")
        var fuzzyFallback: String? = null
        for (candidate in listOf(stripped, blob)) {
            val clean = candidate.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            var offset = 0
            // The real payload starts within the first ~100 base64 chars (short random prefix);
            // scanning past that only wastes time.
            while (offset < clean.length && offset < 250) {
                val sub = clean.substring(offset)
                val usable = sub.substring(0, sub.length / 4 * 4)
                val decoded = runCatching {
                    String(java.util.Base64.getDecoder().decode(usable), Charsets.UTF_8)
                }.getOrNull()
                if (
                    decoded != null &&
                    QUALITY_MARKER_REGEX.containsMatchIn(decoded) &&
                    decoded.contains("https")
                ) {
                    // A decode at the TRUE alignment parses as the original JSON config and yields
                    // the full quality set; a false-positive offset decodes to garbage that still
                    // happens to contain a marker but only a partial/rotating subset — the cause of
                    // the quality menu changing between launches. Prefer the clean JSON decode and
                    // keep the first fuzzy match only as a fallback.
                    val trimmed = decoded.trimStart()
                    if (trimmed.startsWith("[") || trimmed.startsWith("{")) return decoded
                    if (fuzzyFallback == null) fuzzyFallback = decoded
                }
                offset++
            }
        }
        return fuzzyFallback
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

    /**
     * Every voiceover track of a turbo config as (display title, best-quality url).
     *
     * The config is one JSON array where each element carries its own "title" and a "[q]url"
     * file field. After decoding we lose strict JSON validity to junk-stripping corruption, so
     * instead of parsing we associate each [quality]url match with the nearest preceding title
     * marker positionally.
     */
    internal fun extractTurboTracks(blob: String): List<Pair<String, String>> {
        val decoded = findTurboWindow(blob) ?: return emptyList()

        val titlePositions = TITLE_MARKER_REGEX.findAll(decoded).map { marker ->
            val start = marker.range.last + 1
            val rawTail = decoded.substring(start, minOf(decoded.length, start + 300))
            val end = Regex("""\\"|\\""").find(rawTail)?.range?.first ?: rawTail.length
            start to unescapeJsonUnicode(rawTail.take(end)).trim()
        }.toList()

        val best = linkedMapOf<String, Pair<Int, String>>() // title -> (ladder rank, url)
        for (match in TURBO_FILE_REGEX.findAll(decoded)) {
            val label = match.groupValues[1].trim()
            if (!TURBO_LABEL_REGEX.matches(label)) continue
            val url = match.groupValues[2].replace("\\/", "/").trim()
            if (!url.startsWith("http") || url.length < 20) continue

            val owningTitle = titlePositions
                .filter { it.first < match.range.first }
                .maxByOrNull { it.first }
                ?.second
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val rank = qualityPreference.indexOf(label).let { if (it < 0) Int.MAX_VALUE else it }
            val current = best[owningTitle]
            if (current == null || rank < current.first) best[owningTitle] = rank to url
        }
        return best.entries.map { it.key to it.value.second }
    }

    private fun collectFileFieldQualities(fileField: String, qualities: MutableMap<String, String>) {
        TURBO_FILE_REGEX.findAll(fileField).forEach { match ->
            val label = match.groupValues[1].trim()
            val url = match.groupValues[2].replace("\\/", "/").trim()
            // Quality labels only ("720p", "Auto") — this skips subtitle tracks "[Russian]...srt"
            // and poster fields that share the same bracket syntax.
            if (!TURBO_LABEL_REGEX.matches(label)) return@forEach
            if (url.startsWith("http") && url.length > 20 && !qualities.containsKey(label)) {
                qualities[label] = url
            }
        }
    }
}
