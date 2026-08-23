package hd.kinoshka.app.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** Preferred order of ddbb sources for native playback; unknown types come last and are tried too. */
    private fun typeRank(type: String): Int = when {
        type.equals("collaps", ignoreCase = true) -> 0
        type.equals("turbo", ignoreCase = true) -> 1
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
    private val TURBO_FILE_REGEX = Regex("""\[([^\[\],]+)\]((?:https?:)(?:\\/|[^,"])+?\.(?:m3u8|mp4))""")

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
            decodeTurboConfig(match.groupValues[1])?.let { config ->
                val qualities = parseTurboQualities(config)
                if (qualities.isNotEmpty()) {
                    val origin = runCatching { java.net.URI(embedUrl) }.getOrNull()
                        ?.let { "${it.scheme}://${it.host}/" }
                        ?: "https://${runCatching { java.net.URI(embedUrl).host }.getOrNull().orEmpty()}/"
                    return mapOf(
                        "Referer" to origin,
                        "User-Agent" to USER_AGENT
                    ) to qualities
                }
            }
        }
        return null
    }

    /**
     * Decodes the obfuscated `new Player("...")` payload into its JSON config.
     *
     * The blob is plain base64 JSON with three obstacles: a short random prefix before the real
     * payload starts, and comment-like junk segments (`//<base64>=X`) sprinkled inside it, and a
     * trailing newline. We strip the junk, then brute-force the start offset until the decode
     * yields something beginning with `{`, and cut the JSON out with balanced-brace scanning.
     */
    internal fun decodeTurboConfig(blob: String): String? {
        val stripped = blob.replace(TURBO_JUNK_REGEX, "")
        val candidates = listOf(stripped, blob)
        for (candidate in candidates) {
            val clean = candidate.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            var offset = 0
            while (offset < clean.length && offset < 600) {
                val sub = clean.substring(offset)
                val usable = sub.substring(0, sub.length / 4 * 4)
                val decoded = runCatching {
                    String(java.util.Base64.getDecoder().decode(usable), Charsets.UTF_8)
                }.getOrNull()
                if (decoded != null) {
                    val start = decoded.indexOf('{')
                    if (start >= 0 && !decoded.take(start).contains('\uFFFD')) {
                        balancedJsonObject(decoded, start)?.let { json ->
                            if (json.contains("\"file\"")) return json
                        }
                    }
                }
                offset++
            }
        }
        return null
    }

    /** Cuts a balanced `{...}` block starting at [start], ignoring braces inside strings. */
    private fun balancedJsonObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            when {
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun parseTurboQualities(configJson: String): Map<String, String> {
        val qualities = linkedMapOf<String, String>()
        runCatching {
            // kotlinx.serialization rather than org.json: this path is covered by JVM unit tests
            // where the org.json android stubs throw "not mocked".
            val config = json.parseToJsonElement(configJson).jsonObject
            val files = config["file"]?.jsonArray ?: return@runCatching
            for (element in files) {
                val entry = element as? JsonObject ?: continue
                val fileField = entry["file"]?.jsonPrimitive?.contentOrNull ?: continue
                TURBO_FILE_REGEX.findAll(fileField).forEach { match ->
                    val label = match.groupValues[1].trim()
                    val url = match.groupValues[2].replace("\\/", "/").trim()
                    if ((url.endsWith(".m3u8") || url.endsWith(".mp4")) && !qualities.containsKey(label)) {
                        qualities[label] = url
                    }
                }
                if (qualities.isNotEmpty()) return@runCatching
            }
        }.onFailure { Log.w(TAG, "turbo config parse failed", it) }
        return qualities
    }

    private val json = Json { ignoreUnknownKeys = true }
}
