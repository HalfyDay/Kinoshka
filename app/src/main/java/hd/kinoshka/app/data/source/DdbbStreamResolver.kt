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
        val sourceName: String
    )

    /**
     * Walks the ddbb player list for [kinopoiskId] and returns the first successfully extracted
     * stream, or null when every source fails / the aggregator has nothing.
     */
    suspend fun resolveMovieStream(kinopoiskId: Int): DdbbStream? = withContext(Dispatchers.IO) {
        if (kinopoiskId <= 0) return@withContext null
        val players = fetchPlayers(kinopoiskId)
        Log.i(TAG, "ddbb offered ${players.size} sources for kp=$kinopoiskId: ${players.map { it.first }}")
        players.forEach { (type, iframeUrl) ->
            val html = fetchHtml(iframeUrl) ?: run {
                Log.w(TAG, "$type: embed unreachable ($iframeUrl)")
                return@forEach
            }
            extractFromEmbed(html, iframeUrl)?.let { (headers, qualities) ->
                if (qualities.isNotEmpty()) {
                    val bestKey = qualityPreference.firstOrNull { qualities.containsKey(it) } ?: qualities.keys.first()
                    Log.i(TAG, "$type: extracted ${qualities.size} qualities, using $bestKey")
                    return@withContext DdbbStream(
                        url = qualities.getValue(bestKey),
                        headers = headers,
                        qualities = qualities,
                        sourceName = type.replaceFirstChar { it.uppercase() }
                    )
                }
            }
            Log.w(TAG, "$type: no stream extracted")
        }
        null
    }

    private val qualityPreference = listOf("720p", "1080p", "480p", "360p", "2160p", "240p", "Auto")

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

    internal fun decodeTurboConfig(blob: String): String? {
        val stripped = blob.replace(TURBO_JUNK_REGEX, "")
        val candidates = listOf(stripped, blob)
        for (candidate in candidates) {
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
                    return decoded
                }
                offset++
            }
        }
        return null
    }

    /**
     * Harvests "[quality]url" pairs straight from the obfuscated blob: walks base64 alignments,
     * and for each decode window that contains quality markers regexes out every stream URL.
     * A window whose URLs were all corrupted by junk stripping is simply skipped in favour of
     * the next alignment, making the extraction resilient to single-byte corruption.
     */
    internal fun extractTurboQualities(blob: String): Map<String, String> {
        val stripped = blob.replace(TURBO_JUNK_REGEX, "")
        val candidates = listOf(stripped, blob)
        var fallback: Map<String, String>? = null
        for (candidate in candidates) {
            val clean = candidate.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            var offset = 0
            while (offset < clean.length && offset < 250) {
                val sub = clean.substring(offset)
                val usable = sub.substring(0, sub.length / 4 * 4)
                val decoded = runCatching {
                    String(java.util.Base64.getDecoder().decode(usable), Charsets.UTF_8)
                }.getOrNull()
                if (decoded != null && QUALITY_MARKER_REGEX.containsMatchIn(decoded) && decoded.contains("https")) {
                    val qualities = linkedMapOf<String, String>()
                    collectFileFieldQualities(decoded, qualities)
                    if (qualities.isNotEmpty()) {
                        if (qualities.size >= 2 || offset <= 100) return qualities
                        if (fallback == null) fallback = qualities
                    }
                }
                offset++
            }
        }
        return fallback ?: emptyMap()
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
