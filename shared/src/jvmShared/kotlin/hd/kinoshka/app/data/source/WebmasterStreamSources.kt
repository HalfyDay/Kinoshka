package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.DdbbEpisodeTrack
import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Standalone "webmaster" sources resolved CONCURRENTLY with the ddbb aggregator — the same
 * APIs the free-TV web-app ecosystem (Lampa & co) calls:
 *
 *  - VideoCDN — a JSON API (svetacdn/cdnmovies domains wrap one backend) whose per-translation
 *    quality ladders hide in the title's embed page under `id="files"`.
 *  - Collaps — an embed keyed by kinopoisk id whose `makePlayer({...})` config carries a ready
 *    HLS per episode (series) or per movie.
 *  - Voidboost — the Rezka backend: an embed with per-voice tokens; each voice/episode needs
 *    one iframe fetch whose `file` blob is junk-salted base64.
 *
 * All three are looked up by kinopoisk id and return [DdbbStreamResolver.SourceParse] results,
 * which DdbbStreamResolver merges with the ddbb parses into ONE catalog — the voiceover
 * dropdown then lists dubs of every source, and any source can win playback.
 */
object WebmasterStreamSources {
    private const val TAG = "WebmasterStreamSources"

    /**
     * Public tokens of the Lampa web app (github.com/yumata/lampa-source, plugins/online) —
     * they serve that whole ecosystem. Hardcoded like the Kodik fallback pool; when one dies,
     * the other base still answers and the resolve falls through to the next source.
     */
    private val VIDEOCDN_APIS = listOf(
        Triple("https://cdn.svetacdn.in/api/", "api_token", "3i40G5TSECmLF77oAqnEgbx61ZWaOYaE"),
        Triple("https://cdnmovies.net/api/short/", "token", "02d56099082ad5ad586d7fe4e2493dd9")
    )

    /** Per-host cookie jar: voidboost's embed 301s once and 403s the cookieless retry — the jar
     *  carries the set-cookie across fetchHtmlTracked's manual redirect hops. */
    private val cookieJar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (cookies.isNotEmpty()) store[url.host] = cookies
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = store[url.host].orEmpty()
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .proxySelector(StreamProxySelector())
            .proxyAuthenticator(StreamProxyConfig.okHttpProxyAuthenticator())
            .cookieJar(cookieJar)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Best-first rung order, mirroring DdbbStreamResolver.directLadderPreference plus rezka's labels. */
    private val LADDER_PREFERENCE = listOf("2160p", "1440p", "1080p Ultra", "1080p", "720p", "480p", "360p", "240p")

    // A movie never carries more than a dozen dubs; a season rarely exceeds ~50 episodes.
    private const val VOIDBOOST_MAX_VOICES = 12
    private const val VOIDBOOST_MAX_EPISODES = 60

    private fun bestOfLadder(ladder: Map<String, String>): Pair<String, String>? =
        ladder.entries.minByOrNull { LADDER_PREFERENCE.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            ?.let { it.key to it.value }

    private fun normalizeProtocolUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> ""
    }

    /** OkHttp's auto-follow dies on the live mirrors: voidboost.net 302s its embed to a cleartext
     *  http:// host, which the network security policy refuses (UnknownServiceException). Redirects
     *  are followed manually instead — every hop lands in the log and http:// targets are upgraded
     *  to https://. */
    private val redirectlessClient: OkHttpClient by lazy {
        httpClient.newBuilder().followRedirects(false).build()
    }

    private fun buildGet(url: String, referer: String?): Request =
        Request.Builder().url(url).addHeader("User-Agent", USER_AGENT)
            // Bare-UA requests get refused by the WAFs in front of these hosts; the Accept /
            // Accept-Language pair is what Lampa's browser context sends implicitly.
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { referer?.let { addHeader("Referer", it) } }
            .build()

    /** Public for unit tests: relative Location resolution + forced cleartext-to-https upgrade. */
    fun redirectTargetUrl(from: String, location: String): String? =
        runCatching { java.net.URI(from).resolve(location) }.getOrNull()
            ?.toString()
            ?.let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }

    private fun fetchHtml(url: String, referer: String?): String? = fetchHtmlTracked(url, referer)?.second

    /** Returns (final post-redirect URL, html). Callers that build follow-up paths against the
     *  serving host (voidboost ladder iframes) need the final URL, not the requested one. */
    private fun fetchHtmlTracked(url: String, referer: String?, depth: Int = 0): Pair<String, String>? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url.take(60)
        return try {
            redirectlessClient.newCall(buildGet(url, referer)).execute().use { response ->
                when {
                    response.isRedirect -> {
                        val location = response.header("Location")
                        when {
                            location.isNullOrEmpty() ->
                                KLog.w(TAG, "fetch $host: HTTP ${response.code} redirect without Location")
                            depth >= 3 ->
                                KLog.w(TAG, "fetch $host: redirect chain longer than 3 hops")
                            else -> {
                                val target = redirectTargetUrl(url, location)
                                if (target == null) {
                                    KLog.w(TAG, "fetch $host: unparsable redirect Location $location")
                                } else {
                                    KLog.i(TAG, "fetch $host: HTTP ${response.code} -> " +
                                        (runCatching { java.net.URI(target).host }.getOrNull() ?: target))
                                    return fetchHtmlTracked(target, referer, depth + 1)
                                }
                            }
                        }
                        null
                    }
                    !response.isSuccessful -> {
                        KLog.w(TAG, "fetch $host: HTTP ${response.code}")
                        null
                    }
                    else -> response.body.string().takeIf { it.isNotEmpty() }?.let { url to it }
                }
            }
        } catch (e: Exception) {
            KLog.w(TAG, "fetch $host: ${e.javaClass.simpleName}: ${e.message?.take(140)}")
            null
        }
    }

    private val NUMERIC_ENTITY_REGEX = Regex("&#(\\d+);")

    private fun entityDecode(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace(NUMERIC_ENTITY_REGEX) { m -> m.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&")

    // --- VideoCDN ---------------------------------------------------------------------------

    private val VIDEOCDN_FILES_REGEX = Regex("""id="files"\s+value="([^"]*)"""")

    /**
     * Parsed videocdn `files` payload: per translation id, one ladder under the null key
     * (movie) or one ladder per "s_e" folder id (series).
     */
    data class VideocdnFiles(val perTranslation: Map<String, Map<String?, Map<String, String>>>)

    internal suspend fun resolveVideoCdn(kinopoiskId: Int): DdbbStreamResolver.SourceParse? =
        withContext(Dispatchers.IO) {
            if (kinopoiskId <= 0) return@withContext null
            for ((base, param, token) in VIDEOCDN_APIS) {
                // Kind is unknown at this layer (the resolver interface carries only the id),
                // so both endpoints are queried — two cheap JSON calls.
                val row = videocdnRow(base, param, token, "movies", kinopoiskId)
                    ?: videocdnRow(base, param, token, "tv", kinopoiskId)
                if (row == null) continue
                val iframeSrc = row.optString("iframe_src").trim()
                if (iframeSrc.isEmpty()) continue
                val embedUrl = normalizeProtocolUrl(iframeSrc)
                val embedHtml = fetchHtml(embedUrl, embedOrigin(embedUrl))
                if (embedHtml == null) {
                    KLog.w(TAG, "videocdn: embed fetch failed for kp=$kinopoiskId")
                    continue
                }
                val filesValue = VIDEOCDN_FILES_REGEX.find(embedHtml.replace("\n", ""))
                    ?.groupValues?.get(1)
                if (filesValue == null) {
                    KLog.w(TAG, "videocdn: files payload not found in embed for kp=$kinopoiskId")
                    continue
                }
                val files = parseVideocdnFiles(filesValue)
                if (files.perTranslation.isEmpty()) {
                    KLog.w(TAG, "videocdn: files payload empty for kp=$kinopoiskId")
                    continue
                }

                val headers = mapOf("User-Agent" to USER_AGENT)
                val tracks = mutableListOf<DdbbEpisodeTrack>()
                val voiceRows = LinkedHashMap<String, String>()
                val ladders = LinkedHashMap<String, Map<String, String>>()
                val episodeTitles = videocdnEpisodeTitles(row)

                for ((trId, byFolder) in files.perTranslation) {
                    val title = videocdnTranslationTitle(row, trId)
                    val maxQuality = videocdnMaxQuality(row, trId)
                    for ((folderKey, rawLadder) in byFolder) {
                        val ladder = videocdnCapLadder(rawLadder, maxQuality)
                        val best = bestOfLadder(ladder) ?: continue
                        ladders.putIfAbsent(best.second, ladder)
                        if (folderKey == null) {
                            voiceRows.putIfAbsent(title, best.second)
                        } else {
                            val (season, episode) = parseVideocdnFolderKey(folderKey) ?: continue
                            tracks += DdbbEpisodeTrack(
                                dubId = "videocdn|$trId",
                                dubTitle = title,
                                seasonNumber = season,
                                episodeNumber = episode,
                                title = episodeTitles[season to episode],
                                playerUrl = best.second
                            )
                        }
                    }
                }
                if (voiceRows.isEmpty() && tracks.isEmpty()) continue

                val defaultUrl = voiceRows.values.firstOrNull()
                    ?: tracks.firstOrNull()?.playerUrl
                    ?: continue
                KLog.i(TAG, "videocdn: ${voiceRows.size} dub rows, ${tracks.size} episode tracks for kp=$kinopoiskId")
                return@withContext DdbbStreamResolver.SourceParse(
                    sourceName = "VideoCDN",
                    url = defaultUrl,
                    headers = headers,
                    qualities = ladders[defaultUrl] ?: mapOf("Auto" to defaultUrl),
                    voiceRows = voiceRows.map { it.key to it.value },
                    tracks = tracks,
                    ladders = ladders
                )
            }
            null
        }

    /** One API row for [kinopoiskId]; an id query is authoritative, a row carrying a DIFFERENT
     *  kinopoisk id is a fuzzy-backend stray and is rejected. Every null path logs its reason —
     *  fetch failures are logged by [fetchHtml] itself. */
    private fun videocdnRow(
        base: String,
        param: String,
        token: String,
        type: String,
        kinopoiskId: Int,
    ): org.json.JSONObject? {
        // field=global mirrors Lampa's query shape; the backend ignores unknown extras.
        val url = "${base.trimEnd('/')}/$type?$param=$token&kinopoisk_id=$kinopoiskId&field=global"
        val body = fetchHtml(url, referer = null) ?: return null
        val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: run {
            KLog.w(TAG, "videocdn: non-JSON api response from $base$type (waf page?)")
            return null
        }
        val rows = videocdnRows(root)
        if (rows.isEmpty()) {
            KLog.w(TAG, "videocdn: empty data on $base$type for kp=$kinopoiskId " +
                "(keys=${root.keys().asSequence().take(6).toList()})")
            return null
        }
        for (row in rows) {
            val rowKp = row.optInt("kinopoisk_id")
            if (rowKp > 0 && rowKp != kinopoiskId) continue
            if (row.optString("iframe_src").trim().isNotEmpty()) return row
        }
        KLog.w(TAG, "videocdn: ${rows.size} row(s) on $base$type, none matched kp=$kinopoiskId " +
            "(first row kp=${rows.first().optInt("kinopoisk_id")}, imdb=${rows.first().optString("imdb_id")})")
        return null
    }

    /** Public for unit tests: `data` arrives as an array (svetacdn) or an id-keyed object
     *  (cdnmovies short — Lampa converts it with `for key in json.data` the same way). */
    fun videocdnRows(root: org.json.JSONObject): List<org.json.JSONObject> {
        root.optJSONArray("data")?.let { arr ->
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        root.optJSONObject("data")?.let { obj ->
            return obj.keys().asSequence().mapNotNull { obj.optJSONObject(it) }.toList()
        }
        return emptyList()
    }

    fun parseVideocdnFiles(filesValue: String): VideocdnFiles {
        val outer = runCatching { org.json.JSONObject(filesValue.replace("&quot;", "\"")) }.getOrNull()
            ?: return VideocdnFiles(emptyMap())
        val perTranslation = LinkedHashMap<String, Map<String?, Map<String, String>>>()
        for (key in outer.keys().asSequence().toList()) {
            if (key == "0") continue
            // Lampa's two-layer decode: the outer JSON's values are entity-encoded JSON strings.
            val inner = entityDecode(outer.optString(key))
            val arr = runCatching { org.json.JSONArray(inner) }.getOrNull() ?: continue
            val byFolder = LinkedHashMap<String?, Map<String, String>>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val folders = item.optJSONArray("folder")
                if (folders != null) {
                    for (j in 0 until folders.length()) {
                        val f = folders.optJSONObject(j) ?: continue
                        val ladder = parseVideocdnQualityList(f.optString("file"))
                        if (ladder.isNotEmpty()) byFolder[f.optString("id").trim().takeIf(String::isNotEmpty)] = ladder
                    }
                } else {
                    val ladder = parseVideocdnQualityList(item.optString("file"))
                    if (ladder.isNotEmpty()) byFolder[item.optString("id").trim().takeIf(String::isNotEmpty)] = ladder
                }
            }
            if (byFolder.isNotEmpty()) perTranslation[key] = byFolder
        }
        return VideocdnFiles(perTranslation)
    }

    /** "[1080p]//host/a.mp4 or [720p]//host/b.mp4,…" → ordered ladder; Lampa keeps the FIRST
     *  or-alternative for videocdn. Protocol-relative urls get pinned to https. */
    fun parseVideocdnQualityList(raw: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (segment in raw.split(',')) {
            val m = Regex("""\[(\d{3,4})p\](.+)""").find(segment.trim()) ?: continue
            val quality = m.groupValues[1] + "p"
            var url = m.groupValues[2].trim()
            if (url.contains(" or ")) url = url.substringBefore(" or ").trim()
            url = normalizeProtocolUrl(url)
            if (url.isNotEmpty() && !out.containsKey(quality)) out[quality] = url
        }
        return out
    }

    /** "1_2" → (1, 2); null when the folder id carries no season/episode numbers. */
    private fun parseVideocdnFolderKey(key: String): Pair<Int, Int>? {
        val parts = key.split('_')
        if (parts.size != 2) return null
        val season = parts[0].toIntOrNull() ?: return null
        val episode = parts[1].toIntOrNull() ?: return null
        return season to episode
    }

    private fun videocdnTranslationTitle(row: org.json.JSONObject, translationId: String): String {
        row.optJSONArray("translations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                if (t.optString("id") == translationId) {
                    val title = t.optString("title").trim()
                    if (title.isNotEmpty()) return title
                }
            }
        }
        row.optJSONArray("media")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (m.optString("translation_id") == translationId) {
                    val t = m.optJSONObject("translation")
                    val shorter = t?.optString("shorter_title")?.trim().orEmpty()
                    val name = if (shorter.isNotEmpty()) shorter else t?.optString("title")?.trim().orEmpty()
                    if (name.isNotEmpty()) return name
                }
            }
        }
        return "Озвучка $translationId"
    }

    private fun videocdnMaxQuality(row: org.json.JSONObject, translationId: String): Int? {
        row.optJSONArray("media")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (m.optString("translation_id") == translationId) {
                    val q = m.optInt("max_quality")
                    if (q > 0) return q
                }
            }
        }
        row.optJSONArray("translations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                if (t.optString("id") == translationId) {
                    val q = t.optInt("max_quality")
                    if (q > 0) return q
                }
            }
        }
        return null
    }

    /** The files payload lists variants above a dub's real max_quality — Lampa filters by it
     *  (a filter that empties the ladder is relaxed back to the uncapped one here). */
    private fun videocdnCapLadder(ladder: Map<String, String>, maxQuality: Int?): Map<String, String> {
        val max = maxQuality?.takeIf { it > 0 } ?: return ladder
        val capped = ladder.filter { (label, _) ->
            label.removeSuffix("p").toIntOrNull()?.let { it <= max } == true
        }
        return capped.ifEmpty { ladder }
    }

    private fun videocdnEpisodeTitles(row: org.json.JSONObject): Map<Pair<Int, Int>, String> {
        val titles = HashMap<Pair<Int, Int>, String>()
        row.optJSONArray("episodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val season = e.optInt("season_num")
                val episode = e.optInt("num")
                val title = e.optString("ru_title").trim()
                if (season > 0 && episode > 0 && title.isNotEmpty()) titles[season to episode] = title
            }
        }
        return titles
    }

    private fun embedOrigin(url: String): String? =
        runCatching { java.net.URI(url) }.getOrNull()?.let { "${it.scheme}://${it.host}/" }

    // --- Collaps ----------------------------------------------------------------------------

    /** Public for unit tests. */
    data class CollapsEpisode(val number: Int, val hls: String, val title: String?, val audioNames: List<String>)

    /** Public for unit tests. */
    data class CollapsSeason(val number: Int, val episodes: List<CollapsEpisode>)

    /** Public for unit tests. */
    data class CollapsParse(
        val seasons: List<CollapsSeason>,
        val movieHls: String?,
        val movieAudio: List<String>
    )

    private val MAKEPLAYER_ARG_REGEX = Regex("""seasons\s*:\s*\[""")

    internal suspend fun resolveCollaps(kinopoiskId: Int): DdbbStreamResolver.SourceParse? =
        withContext(Dispatchers.IO) {
            if (kinopoiskId <= 0) return@withContext null
            val embedUrl = "https://api.delivembd.ws/embed/kp/$kinopoiskId"
            val headers = mapOf("Referer" to "https://api.delivembd.ws/", "User-Agent" to USER_AGENT)
            val html = fetchHtml(embedUrl, "https://api.delivembd.ws/")
            if (html == null) {
                KLog.w(TAG, "collaps: embed fetch failed for kp=$kinopoiskId")
                return@withContext null
            }
            val parse = parseCollapsMakePlayer(html)
            if (parse == null) {
                // The embed answers but carries no makePlayer(): geo-fence / WAF page / "not found" —
                // the page head usually says which, so keep it in the log.
                KLog.w(TAG, "collaps: makePlayer config not found for kp=$kinopoiskId; page head: " +
                    html.take(240).replace(Regex("\\s+"), " "))
                return@withContext null
            }

            val tracks = mutableListOf<DdbbEpisodeTrack>()
            val ladders = LinkedHashMap<String, Map<String, String>>()
            for (season in parse.seasons) {
                for (ep in season.episodes) {
                    ladders.putIfAbsent(ep.hls, mapOf("Auto" to ep.hls))
                    tracks += DdbbEpisodeTrack(
                        dubId = "collaps",
                        dubTitle = ep.audioNames.firstOrNull()?.takeIf(String::isNotEmpty) ?: "Collaps",
                        seasonNumber = season.number,
                        episodeNumber = ep.number,
                        title = ep.title,
                        playerUrl = ep.hls
                    )
                }
            }
            val voiceRows = if (tracks.isEmpty() && parse.movieHls != null) {
                listOf(
                    (parse.movieAudio.firstOrNull()?.takeIf(String::isNotEmpty) ?: "Collaps") to parse.movieHls
                )
            } else emptyList()
            if (tracks.isEmpty() && voiceRows.isEmpty()) return@withContext null

            val defaultUrl = voiceRows.firstOrNull()?.second
                ?: tracks.firstOrNull()?.playerUrl
                ?: return@withContext null
            KLog.i(TAG, "collaps: ${tracks.size} episode tracks, movie=${parse.movieHls != null} for kp=$kinopoiskId")
            DdbbStreamResolver.SourceParse(
                sourceName = "Collaps",
                url = defaultUrl,
                headers = headers,
                qualities = ladders[defaultUrl] ?: mapOf("Auto" to defaultUrl),
                voiceRows = voiceRows,
                tracks = tracks,
                ladders = ladders
            )
        }

    /**
     * Parses the embed's `makePlayer({...});` config. The 2026 embed wraps the data in raw JS
     * (tracker `function(){...}` values, `30 * 1000` arithmetic), so the whole argument never
     * parses as JSON any more — the brace-balanced argument is captured first and only the
     * data-bearing subtrees (`seasons:[...]`, movie `source:{...}`) are parsed individually.
     * Both remain lenient-JSON: unquoted keys and single-quoted strings are accepted.
     */
    fun parseCollapsMakePlayer(html: String): CollapsParse? {
        val flat = html.replace("\n", "")
        val arg = makePlayerArg(flat) ?: return null
        val seasonsArr = MAKEPLAYER_ARG_REGEX.find(arg)
            ?.let { m -> balancedSpan(arg, m.range.last, '[', ']') }
            ?.let { text -> runCatching { org.json.JSONArray(text) }.getOrNull() }

        val seasons = mutableListOf<CollapsSeason>()
        seasonsArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val episodes = mutableListOf<CollapsEpisode>()
                s.optJSONArray("episodes")?.let { epsArr ->
                    for (j in 0 until epsArr.length()) {
                        val e = epsArr.optJSONObject(j) ?: continue
                        val hls = e.optString("hls").trim()
                        if (!hls.startsWith("http")) continue
                        episodes += CollapsEpisode(
                            number = e.optInt("episode"),
                            hls = hls,
                            title = e.optString("title").trim().takeIf(String::isNotEmpty),
                            audioNames = audioNames(e)
                        )
                    }
                }
                if (episodes.isNotEmpty()) seasons += CollapsSeason(s.optInt("season"), episodes)
            }
        }
        val source = Regex("""source\s*:\s*\{""").find(arg)
            ?.let { m -> balancedSpan(arg, m.range.last, '{', '}') }
            ?.let { text -> runCatching { org.json.JSONObject(text) }.getOrNull() }
        val movieHls = source?.optString("hls")?.trim()?.takeIf { it.startsWith("http") }
        if (seasons.isEmpty() && movieHls == null) return null
        return CollapsParse(seasons, movieHls, source?.let { audioNames(it) } ?: emptyList())
    }

    /** The `makePlayer({...})` call's argument — the config call, not the `function makePlayer(opts)`
     *  definition the same page carries (that one's argument is not a brace literal). */
    private fun makePlayerArg(flat: String): String? {
        val start = flat.indexOf("makePlayer({")
        if (start < 0) return null
        return balancedSpan(flat, start + "makePlayer(".length, '{', '}')
    }

    /** The substring from the bracket at [start] to its matching pair — string- and escape-aware
     *  (quotes may contain braces), null when unbalanced or the char at [start] is not [open]. */
    private fun balancedSpan(s: String, start: Int, open: Char, close: Char): String? {
        if (s.getOrNull(start) != open) return null
        var depth = 0
        var quote: Char? = null
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                when {
                    c == '\\' -> i += 1
                    c == quote -> quote = null
                }
            } else when {
                c == '\'' || c == '"' -> quote = c
                c == open -> depth += 1
                c == close -> {
                    depth -= 1
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
            i += 1
        }
        return null
    }

    private fun audioNames(obj: org.json.JSONObject): List<String> =
        obj.optJSONObject("audio")?.optJSONArray("names")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }
        } ?: emptyList()

    // --- Voidboost (Rezka backend) ----------------------------------------------------------

    /** Public for unit tests. */
    data class VoidboostVoice(val token: String, val name: String)

    /** Public for unit tests. */
    data class VoidboostEpisodeRef(val number: Int, val title: String?)

    /** Public for unit tests. */
    data class VoidboostEmbed(
        val voices: List<VoidboostVoice>,
        val seasons: List<Int>,
        val episodes: List<VoidboostEpisodeRef>
    )

    private val VOIDBOOST_FILE_REGEX = Regex("""file': '(.*?)'""", RegexOption.DOT_MATCHES_ALL)

    // voidboost.cc (legacy gidonline-era domain) has no A record any more (verified 2026-09);
    // voidboost.net answers and 302s to the current host, which fetchHtmlTracked follows. The
    // resolve runs detached from the launch, so a dead mirror costs nothing user-visible.
    private val VOIDBOOST_BASES = listOf("https://voidboost.net")

    internal suspend fun resolveVoidboost(kinopoiskId: Int): DdbbStreamResolver.SourceParse? =
        withContext(Dispatchers.IO) {
            if (kinopoiskId <= 0) return@withContext null
            val fetched = VOIDBOOST_BASES.firstNotNullOfOrNull { base ->
                fetchHtmlTracked("$base/embed/$kinopoiskId?s=1", "$base/")
            }
            if (fetched == null) {
                KLog.w(TAG, "voidboost: embed fetch failed for kp=$kinopoiskId")
                return@withContext null
            }
            // Ladder iframes must go to the host that actually served the embed — redirects move it.
            val (_, html) = fetched
            val base = embedOrigin(fetched.first)?.trimEnd('/') ?: run {
                KLog.w(TAG, "voidboost: unparsable embed host for kp=$kinopoiskId")
                return@withContext null
            }
            val embed = parseVoidboostEmbed(html)
            if (embed.voices.isEmpty()) {
                KLog.w(TAG, "voidboost: no voice tokens for kp=$kinopoiskId")
                return@withContext null
            }
            val headers = mapOf("Referer" to "https://voidboost.net/", "User-Agent" to USER_AGENT)

            val tracks = mutableListOf<DdbbEpisodeTrack>()
            val voiceRows = LinkedHashMap<String, String>()
            val ladders = LinkedHashMap<String, Map<String, String>>()

            if (embed.seasons.isEmpty()) {
                // Movie: one iframe per voice, resolved in parallel.
                val voices = embed.voices.take(VOIDBOOST_MAX_VOICES)
                val results = coroutineScope {
                    voices.map { voice -> async {
                        val ladder = fetchVoidboostLadder(base, "movie/${voice.token}/iframe?h=gidonline.io")
                            ?: return@async null
                        val best = bestOfLadder(ladder) ?: return@async null
                        Triple(voice, best.second, ladder)
                    } }.awaitAll()
                }
                for ((voice, url, ladder) in results.filterNotNull()) {
                    ladders.putIfAbsent(url, ladder)
                    voiceRows.putIfAbsent(voice.name, url)
                }
            } else {
                // Series: one iframe per EPISODE — a full multi-voice catalog costs
                // n_voices×n_episodes requests, so only the embed's first voice is resolved.
                val season = embed.seasons.first()
                val voice = embed.voices.first()
                val episodes = embed.episodes.take(VOIDBOOST_MAX_EPISODES)
                val results = coroutineScope {
                    episodes.map { ep -> async {
                        val ladder = fetchVoidboostLadder(base, "serial/${voice.token}/iframe?s=$season&e=${ep.number}&h=gidonline.io")
                            ?: return@async null
                        val best = bestOfLadder(ladder) ?: return@async null
                        Triple(ep, best.second, ladder)
                    } }.awaitAll()
                }
                for ((ep, url, ladder) in results.filterNotNull()) {
                    ladders.putIfAbsent(url, ladder)
                    tracks += DdbbEpisodeTrack(
                        dubId = "voidboost|" + voice.name.lowercase().replace(Regex("[^a-zа-я0-9]+"), "-").trim('-'),
                        dubTitle = voice.name,
                        seasonNumber = season,
                        episodeNumber = ep.number,
                        title = ep.title,
                        playerUrl = url
                    )
                }
            }
            if (voiceRows.isEmpty() && tracks.isEmpty()) return@withContext null

            val defaultUrl = voiceRows.values.firstOrNull()
                ?: tracks.firstOrNull()?.playerUrl
                ?: return@withContext null
            KLog.i(TAG, "voidboost: ${voiceRows.size} dub rows, ${tracks.size} episode tracks for kp=$kinopoiskId")
            DdbbStreamResolver.SourceParse(
                sourceName = "Voidboost",
                url = defaultUrl,
                headers = headers,
                qualities = ladders[defaultUrl] ?: mapOf("Auto" to defaultUrl),
                voiceRows = voiceRows.map { it.key to it.value },
                tracks = tracks,
                ladders = ladders
            )
        }

    private fun fetchVoidboostLadder(base: String, path: String): Map<String, String>? {
        val html = fetchHtml("$base/$path", "$base/") ?: return null
        val raw = VOIDBOOST_FILE_REGEX.find(html.replace("\n", ""))?.groupValues?.get(1) ?: return null
        return parseVoidboostQualityChunks(decodeVoidboostFile(raw)).ifEmpty { null }
    }

    fun parseVoidboostEmbed(html: String): VoidboostEmbed {
        val flat = html.replace("\n", "")
        fun selectContent(name: String): String? =
            Regex("""<select name="$name"[^>]*>(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
                .find(flat)?.groupValues?.get(1)

        val optionRegex = Regex("""<option([^>]*)>([^<]*)</option>""")
        val attrRegex = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")
        fun parseOptions(content: String?): List<Pair<Map<String, String>, String>> =
            optionRegex.findAll(content ?: return emptyList()).map { m ->
                attrRegex.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] } to
                    m.groupValues[2].trim()
            }.toList()

        val voices = parseOptions(selectContent("translator")).mapNotNull { (attrs, text) ->
            val token = attrs["data-token"]?.trim().takeIf { !it.isNullOrEmpty() } ?: return@mapNotNull null
            VoidboostVoice(token, text.ifEmpty { "Озвучка" })
        }
        val seasons = parseOptions(selectContent("season"))
            .mapNotNull { (attrs, _) -> attrs["value"]?.trim()?.toIntOrNull() }
        val episodes = parseOptions(selectContent("episode")).mapNotNull { (attrs, text) ->
            val number = attrs["value"]?.trim()?.toIntOrNull() ?: return@mapNotNull null
            VoidboostEpisodeRef(number, text.ifEmpty { null })
        }
        return VoidboostEmbed(voices, seasons.distinct(), episodes)
    }

    /** Junk base64 markers (2–3 char products of "@#!^$", base64-encoded) stripped before decode. */
    private val VOIDBOOST_TRASH_CODES: List<String> by lazy {
        val alphabet = listOf('@', '#', '!', '^', '$')
        val two = alphabet.flatMap { a -> alphabet.map { b -> "$a$b" } }
        val three = alphabet.flatMap { a -> alphabet.flatMap { b -> alphabet.map { c -> "$a$b$c" } } }
        (two + three).map { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    }

    /** Port of the Lampa rezka decoder: the `file` blob is `#h`-marked base64 with `//_//`
     *  separators, junk-marker segments and a 2-char prefix phase shift. */
    fun decodeVoidboostFile(raw: String): String {
        var s = raw.replace("#h", "").split("//_//").joinToString("")
        for (code in VOIDBOOST_TRASH_CODES) s = s.replace(code, "")
        if (s.length <= 2) return ""
        return runCatching {
            String(java.util.Base64.getDecoder().decode(s.substring(2)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /**
     * "[1080p]//a/x.mp4 or //b/x.mp4,[720p]//c/y.mp4" → ladder. Mirrors the Lampa rezka massage:
     * an or-alternative list keeps the LAST alternative, and "1080p Ultra"-style labels fold
     * onto their plain "NNNNp" key (first url wins).
     */
    fun parseVoidboostQualityChunks(decoded: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (chunk in decoded.trim().removePrefix("[").split(",[")) {
            val idx = chunk.indexOf(']')
            if (idx <= 0) continue
            val label = chunk.substring(0, idx).trim()
            var url = chunk.substring(idx + 1).trim()
            if (url.contains(" or ")) url = url.substringAfterLast(" or ").trim()
            url = normalizeProtocolUrl(url)
            if (url.isEmpty()) continue
            val m = Regex("""(\d{3,4})p""").find(label) ?: continue
            val key = m.groupValues[1] + "p"
            if (!out.containsKey(key)) out[key] = url
        }
        return out
    }
}
