package hd.kinoshka.app.data.source

import android.util.Base64
import android.util.Log
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.FilmImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native-stream resolver for 18+ (hentai) titles.
 *
 * Providers, best-first (reachability verified Aug 2026 from a RU network):
 *  1. allhentai.fun — DLE video site, RF-accessible. GET search (?do=search&subaction=search&story=…)
 *     returns articles whose Playerjs config points at /pl/<cat>/<id>/playlist.txt — a plain JSON
 *     array [{title:"серияN", file:"https://cdn.allhentai.fun/video/.../N.mp4"}] of direct MP4s;
 *     multi-episode entries surface as a voiceover-style series switcher in mpvEx.
 *  2. hanime1.me — sister site of hanime.tv, RF-accessible. Search (?query=, requires session
 *     cookies from a warm-up visit) → watch pages embed <source src="…hembed.com/ID-720p.mp4">
 *     tags: direct MP4 renditions 480/720/1080p.
 *  3. oppai.stream — 4K catalog behind a VPN gate on RU networks. Client-rendered lists come
 *     from actions/results.php (sc=search is not query-bound, so the whole index is paged and
 *     matched client-side); watch pages embed a single <source src="…/<quality>/E<NN>.mp4"> —
 *     direct MP4s whose CDN requires a Referer header.
 *  4. hanime.tv — richest metadata; main domain TCP-blocked on many RU networks (works under
 *     VPN). Catalog dump stays reachable at guest.freeanimehentai.net (incl. Japanese alt-titles
 *     used above to improve hanime1/allhentai matching); playback via legacy /api/v8 or an
 *     AES-256-GCM handshake (key = SHA-256("htv-insecure-handshake-v1"), AAD =
 *     "htv-insecure-v1"), answer encrypted in the `x-token` response header.
 *
 * Removed: rule34.xxx — its dapi now demands a personal account + API key, and tag search
 * could never be title-verified anyway. animefox.org — its GDPlayer embeds (Yandex Disk /
 * VK Video) are crypto-gated and reportedly do not play even on the site itself.
 *
 * Kodik/AniLiberty do not index adult content and ddbb keys on kinopoiskId which shikimori-
 * derived anime ids do not carry (synthetic id = shikimoriId + ANIME_ID_OFFSET).
 */
/**
 * Resolver result: [AnimeMediaStream]-shaped plus optional episode list — when a provider
 * exposes a series, callers turn them into FlatTranslations so mpvEx's voiceover switcher
 * becomes an episode switcher (direct links play as-is through resolveVoiceoverLink).
 * Top-level so UI code can reference it without the object qualifier.
 */
data class HentaiStream(
    val url: String,
    val qualities: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "Auto",
    val title: String = "",
    /** (label, direct url) per episode; empty for single-video titles. */
    val episodes: List<Pair<String, String>> = emptyList()
)

object HentaiStreamResolver {
    private const val TAG = "HentaiStreamResolver"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** UA для прямых ссылок хентай-CDN, открываемых вне резолвера (превью в плеере). */
    val HENTAI_USER_AGENT: String = USER_AGENT
    private const val HANIME_REFERER = "https://hanime.tv/"
    private const val H1_BASE = "https://hanime1.me"
    private const val AH_BASE = "https://allhentai.fun"
    private const val HD_BASE = "https://hentaidream.fun"
    private const val OPPAI_BASE = "https://oppai.stream"

    private const val HANIME_CATALOG_URL = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    /** Preferred rendition order; anything outside falls back to max height. */
    private val QUALITY_LADDER = listOf("1080p", "720p", "480p")

    private const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L

    /** hanime1.me rejects cookie-less clients with 403; jar warms up once per process. */
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val bucket = store.getOrPut(url.host) { ConcurrentHashMap() }
            cookies.forEach { cookie ->
                if (cookie.name.isNotBlank() && cookie.value.isNotBlank()) {
                    bucket[cookie.name] = cookie.value
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.map { (name, value) ->
                Cookie.Builder().domain(url.host).path("/").name(name).value(value).build()
            } ?: emptyList()
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Plain client with system DNS — bypasses DoH fallback which can conflict with VPN tunnels.
     *  Browser-like headers defeat Cloudflare bot detection that blocks raw OkHttp TLS fingerprints. */
    private val oppaiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Watch pages answer with 302 → locked.php while the 302 body itself carries the real
     *  player markup — so redirects must NOT be followed on watch-page fetches (verified). */
    private val oppaiWatchClient: OkHttpClient by lazy {
        oppaiClient.newBuilder().followRedirects(false).build()
    }

    /** Full browser-like header set — needed to pass CF bot detection on oppai.stream. */
    private fun oppaiHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        put("Accept-Language", "en-US,en;q=0.9")
        put("Sec-Ch-Ua", "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
        put("Sec-Ch-Ua-Mobile", "?0")
        put("Sec-Ch-Ua-Platform", "\"Windows\"")
        put("Sec-Fetch-Dest", "document")
        put("Sec-Fetch-Mode", "navigate")
        put("Sec-Fetch-Site", "same-origin")
        put("Sec-Fetch-User", "?1")
        put("Upgrade-Insecure-Requests", "1")
        referer?.let { put("Referer", it) }
    }

    data class H1Item(val id: String, val title: String)
    data class AhArticle(val path: String, val label: String)

    /** videoId == hanime1 watch id (verified), cover/poster feed the frames fallback. */
    data class CatalogEntry(
        val slug: String,
        val name: String,
        val altTitles: String,
        val videoId: String = "",
        val coverUrl: String = "",
        val posterUrl: String = "",
        /** English tag slugs from the catalog dump ("bondage", "school girl", …). */
        val tags: List<String> = emptyList()
    )

    @Volatile private var h1WarmedUp = false
    @Volatile private var catalogCache: List<CatalogEntry>? = null
    @Volatile private var catalogFetchedAtMs: Long = 0L

    /** Last successfully resolved stream per provider+title. The source sheet seeds its episode
     *  list from this while a fresh (slow, VPN-dependent) resolution runs in the background. */
    private val seriesBackup = ConcurrentHashMap<String, HentaiStream>()

    private fun backupKey(provider: HentaiProvider, originalTitle: String?, russianTitle: String?): String =
        provider.name + "|" + titleQueries(originalTitle, russianTitle).joinToString("|").lowercase()

    /** Cached stream for this provider+title, or null on first ever open. */
    fun backupFor(provider: HentaiProvider, originalTitle: String?, russianTitle: String?): HentaiStream? =
        seriesBackup[backupKey(provider, originalTitle, russianTitle)]

    suspend fun resolve(originalTitle: String?, russianTitle: String?): HentaiStream? =
        withContext(Dispatchers.IO) {
            val queries = titleQueries(originalTitle, russianTitle)
            if (queries.isEmpty()) return@withContext null
            resolveViaAllHentai(queries)?.let { return@withContext it }
            resolveViaHentaiDream(queries)?.let { return@withContext it }
            resolveViaHanime1(queries)?.let { return@withContext it }
            resolveViaOppai(queries)?.let { return@withContext it }
            Log.i(TAG, "no hentai stream found for $queries")
            null
        }

    /**
     * Resolves through ONE chosen provider — the source sheet calls this so the user can see
     * which sources need a VPN and pick manually instead of relying on auto-fallback order.
     */
    suspend fun resolveFor(
        provider: HentaiProvider,
        originalTitle: String?,
        russianTitle: String?
    ): HentaiStream? = withContext(Dispatchers.IO) {
        val queries = titleQueries(originalTitle, russianTitle)
        if (queries.isEmpty()) return@withContext null
        Log.i(TAG, "resolveFor ${provider.name}: $queries")
        val stream = when (provider) {
            HentaiProvider.ALLHENTAI -> resolveViaAllHentai(queries)
            HentaiProvider.HENTAIDREAM -> resolveViaHentaiDream(queries)
            HentaiProvider.HANIME1 -> resolveViaHanime1(queries)
            HentaiProvider.OPPAI -> resolveViaOppai(queries)
        }
        if (stream != null) seriesBackup[backupKey(provider, originalTitle, russianTitle)] = stream
        stream
    }

    private fun titleQueries(originalTitle: String?, russianTitle: String?): List<String> =
        listOfNotNull(
            originalTitle?.trim()?.takeIf { it.isNotEmpty() },
            russianTitle?.trim()?.takeIf { it.isNotEmpty() }
        ).distinct()

    // Транслитерация кириллицы в стиле слагов DLE-каталога allhentai ("чёрная библия"
    // -> "chernaja biblija"): позволяет искать статью по русскому заголовку даже без
    // каталога hanime (когда его алиасы недоступны).
    private val CYR_MAP: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "j", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "shh", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private fun translitRu(query: String): String? {
        if (!query.any { it in 'а'..'я' || it == 'ё' || it in 'А'..'Я' || it == 'Ё' }) return null
        return buildString {
            for (ch in query.lowercase()) CYR_MAP[ch]?.let(::append) ?: append(ch)
        }.trim().takeIf { it.isNotEmpty() && it != query.lowercase() }
    }

    // ---- provider 1: allhentai.fun (RU CDN, episode playlists) ----
    private suspend fun resolveViaAllHentai(queries: List<String>): HentaiStream? {
        for (query in queries) {
            val articles = runCatching { searchAllHentai(query) }
                .onFailure { Log.w(TAG, "ah search failed for \"$query\": ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            Log.i(TAG, "allhentai \"$query\" -> ${articles.size} articles")
            pickBest(articles.map { CandidateView(it.path, "${it.label} ${slugWords(it.path)}") }, query)
                ?.let { picked ->
                    val matched = articles.first { it.path == picked }
                    Log.i(TAG, "allhentai matched \"${matched.label}\" (${matched.path}) for \"$query\"")
                    fetchPlaylistStream(AH_BASE, matched.path)?.let { return it }
                }
        }
        // Romaji/russian names often miss this site's transliterated catalog; retry with
        // Japanese aliases from the hanime catalog dump (host is RF-accessible).
        val catalog: List<CatalogEntry> = runCatching { loadCatalog() }.getOrDefault(emptyList())
        for (query in queries) {
            val entry = catalog.firstOrNull { titleMatches(it.name, it.altTitles, query) } ?: continue
            for (alias in aliasQueries(entry)) {
                val articles = runCatching { searchAllHentai(alias) }.getOrDefault(emptyList())
                Log.i(TAG, "allhentai(alias=\"$alias\") -> ${articles.size} articles")
                pickBest(
                    articles.map { CandidateView(it.path, "${it.label} ${slugWords(it.path)}") },
                    alias
                )?.let { picked ->
                    val matched = articles.first { it.path == picked }
                    Log.i(TAG, "allhentai(alias=\"$alias\") matched \"${matched.label}\"")
                    fetchPlaylistStream(AH_BASE, matched.path)?.let { return it }
                }
            }
            break // one catalog lookup is enough to gather aliases
        }
        return null
    }

    // ---- provider 2: hanime1.me (CJK-only titles — latin/russian queries always return 0) ----
    private suspend fun resolveViaHanime1(queries: List<String>): HentaiStream? {
        runCatching { warmH1() }
        // Go straight to catalog for CJK aliases only — hanime1 indexes CJK titles, so
        // romaji/russian searches are guaranteed empty and waste seconds on 403-prone GETs.
        val catalog: List<CatalogEntry> = runCatching { loadCatalog() }.getOrDefault(emptyList())
        for (query in queries) {
            val entry = catalog.firstOrNull { titleMatches(it.name, it.altTitles, query) } ?: continue
            Log.i(TAG, "catalog matched \"${entry.name}\" for \"$query\"")
            for (alias in aliasQueries(entry).filter { alias -> alias.any { it.code >= 0x2E80 } }) {
                val items: List<H1Item> = runCatching { searchH1(alias) }.getOrDefault(emptyList())
                Log.i(TAG, "h1(alias=\"$alias\") -> ${items.size} hits")
                // CJK aliases are pre-verified by the catalog dump — accept first hit without
                // strict scoring because JP/CN character variants defeat exact title matching.
                if (items.isNotEmpty()) {
                    Log.i(TAG, "h1 CJK alias \"$alias\" accepted first hit \"${items.first().title}\"")
                    resolveH1WithSeries(items, items.first().id)?.let { return it }
                }
            }
            break // one catalog lookup is enough to gather aliases
        }
        return null
    }

    // ---- provider 3: hentaidream.fun (RU CDN, same Playerjs/playlist pattern as allhentai) ----
    private suspend fun resolveViaHentaiDream(queries: List<String>): HentaiStream? {
        for (query in queries) {
            val articles = runCatching { searchAllHentaiOn(HD_BASE, query) }
                .onFailure { Log.w(TAG, "hd search failed for \"$query\": ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            Log.i(TAG, "hentaidream \"$query\" -> ${articles.size} articles")
            pickBest(articles.map { CandidateView(it.path, "${it.label} ${slugWords(it.path)}") }, query)
                ?.let { picked ->
                    val matched = articles.first { it.path == picked }
                    Log.i(TAG, "hentaidream matched \"${matched.label}\" (${matched.path}) for \"$query\"")
                    fetchPlaylistStream(HD_BASE, matched.path)?.let { return it }
                }
        }
        return null
    }

    // ---- provider 4: oppai.stream (4K catalog, VPN-gated, direct MP4 + Referer-locked CDN) ----
    private suspend fun resolveViaOppai(queries: List<String>): HentaiStream? {
        // Fast path: construct slug directly from title and try the watch page.
        // After first hit, probe for additional episodes (-2, -3, …) and collect them all.
        for (query in queries) {
            val baseSlug = query.trim()
            for (slugVariant in listOf(
                java.net.URLEncoder.encode(baseSlug, "UTF-8"),
                // Dash-variant must stay percent-encoded too — raw cyrillic in the Referer
                // header throws IllegalArgumentException inside OkHttp.
                java.net.URLEncoder.encode(baseSlug.replace(' ', '-'), "UTF-8")
            )) {
                // Try ep 1 first — if it works, probe for more episodes.
                val ep1Slug = "$slugVariant-1"
                Log.i(TAG, "oppai direct try: $ep1Slug")
                val ep1 = fetchOppaiStream(ep1Slug) ?: continue

                // Found ep1 — probe for siblings up to 20.
                Log.i(TAG, "oppai DIRECT hit: \"$ep1Slug\" — probing for more episodes")
                val eps = mutableListOf("Серия 1" to ep1.url)
                for (n in 2..20) {
                    val sib = fetchOppaiStream("$slugVariant-$n") ?: break
                    eps.add(("Серия $n") to sib.url)
                }
                Log.i(TAG, "oppai series: ${eps.size} episode(s)")
                return HentaiStream(
                    url = ep1.url,
                    qualities = ep1.qualities,
                    headers = ep1.headers,
                    quality = ep1.quality,
                    title = query,
                    episodes = eps.takeIf { it.size > 1 }.orEmpty()
                )
            }
        }

        // Slow path: paginate catalog and match with scoring.
        val entries = linkedMapOf<String, OppaiEntry>()
        for (offset in listOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270)) {
            val url = "$OPPAI_BASE/actions/results.php?sc=search&am=30&of=$offset&sts=1&ibt=1"
            val body = httpGet(url, client = oppaiClient, extraHeaders = oppaiHeaders("$OPPAI_BASE/"))
                ?: run { Log.w(TAG, "oppai page of=$offset: null response"); break }
            Regex("name='([^']{2,140})'[\\s\\S]{0,700}?href='https://oppai\\.stream/watch\\?e=([^'&]+)")
                .findAll(body)
                .forEach { m ->
                    val slug = m.groupValues[2].trim()
                    if (slug.isNotEmpty() && !entries.containsKey(slug)) {
                        entries[slug] = OppaiEntry(slug, unescapeHtml(m.groupValues[1].trim()))
                    }
                }
            Log.i(TAG, "oppai page of=$offset: body=${body.length} chars, total=${entries.size}")

            // Early exit: check if any loaded entry matches before fetching more pages.
            for (query in queries) {
                pickBest(entries.values.map { CandidateView(it.slug, "${it.name} ${slugWords("/${it.slug}")}") }, query)
                    ?.let { picked ->
                        val matched = entries[picked]!!
                        Log.i(TAG, "oppai matched \"${matched.name}\" (${matched.slug}) for \"$query\" at of=$offset")
                        fetchOppaiStream(matched.slug)?.let { return it }
                    }
            }
            if (body.length < 500) {
                Log.i(TAG, "oppai pagination exhausted at of=$offset")
                break
            }
        }
        Log.i(TAG, "oppai: no match in ${entries.size} entries")
        return null
    }

    data class OppaiEntry(val slug: String, val name: String)

    /** Watch pages embed one progressive 720p <source> MP4. HD renditions exist only as
     *  DASH manifests (AV1), which the bundled libmpv cannot demux — see fetchOppaiStream. */
    private fun fetchOppaiStream(slug: String): HentaiStream? {
        val body = httpGet(
            "$OPPAI_BASE/watch?e=$slug",
            client = oppaiWatchClient,
            extraHeaders = oppaiHeaders("$OPPAI_BASE/watch?e=$slug"),
            acceptRedirectBody = true
        ) ?: return null
        // Primary markup, then any raw mp4 URL as fallback (JS-built players).
        val rawSrc = Regex("<source src=\"([^\"]+)\" type=\"video/mp4\"").find(body)?.groupValues?.get(1)?.trim()
            ?: Regex("https://[a-z0-9.-]+/[^\"'\\s]+\\.mp4[^\"'\\s]*", RegexOption.IGNORE_CASE)
                .find(body)?.value?.trim()
            ?: run {
                Log.i(TAG, "oppai watch page has no mp4 source")
                return null
            }
        // CDN paths contain literal spaces ("Shoujo Ramune/E01.mp4") — illegal in URLs.
        val src = rawSrc.replace(" ", "%20")
        if (!src.startsWith("http")) return null
        // <source> is always the progressive 720p file. The site also wires up 1080p/4K, but
        // ONLY as MPEG-DASH manifests ("vsource[\"r-1080\"] = …/E01_dash.mpd", AV1+Opus) —
        // there are no progressive HD files on the CDN (verified 404). Our bundled libmpv
        // has neither dashdec nor dav1d, so advertising those entries would yield dead
        // buttons; log their presence for diagnostics instead of exposing them.
        val dashQualities = Regex("""vsource\["r-(1080|4k)"\]\s*=\s*"([^"]+)"""").findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        if (dashQualities.isNotEmpty()) {
            Log.i(TAG, "oppai has DASH-only ${dashQualities.joinToString("/")} (not exposed: libmpv lacks dashdec)")
        }
        return HentaiStream(
            url = src,
            qualities = linkedMapOf("720p" to src),
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$OPPAI_BASE/"),
            quality = "720p",
            title = slug.replace('-', ' ')
        )
    }

    private fun httpHead(url: String, referer: String? = null): Boolean =
        runCatching {
            val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Referer", referer ?: HANIME_REFERER)
            httpClient.newCall(builder.head().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)

    // ============================ allhentai.fun ============================

    private fun slugWords(path: String): String =
        path.substringAfterLast('/').substringBefore(".html").replace('-', ' ')

    private fun searchAllHentai(query: String): List<AhArticle> {
        return searchAllHentaiOn(AH_BASE, query)
    }

    /** Generic DLE search on any site sharing the allhentai engine (hentaidream etc.).
     * Title text is nested deep inside divs, not right after the anchor tag — so we
     * grab href + slug and reconstruct a searchable label from the slug words. */
    private fun searchAllHentaiOn(base: String, query: String): List<AhArticle> {
        val url =
            "$base/index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = httpGet(url, referer = "$base/") ?: return emptyList()
        val escapedBase = Regex.escape(base)
        return Regex("href=\"(?:$escapedBase)?(/[a-z0-9]+/(\\d{2,6}-[^\"]+\\.html))\"")
            .findAll(body)
            .map { m ->
                val path = m.groupValues[1]
                // Derive label from slug: "587-chernaja-biblija-novyj-zavet-bible-black.html" → "chernaja biblija..."
                val label = path.substringAfterLast('/')
                    .substringBefore(".html")
                    .replace(Regex("^\\d+-"), "")
                    .replace('-', ' ')
                    .trim()
                AhArticle(path, label.ifBlank { path })
            }
            .distinctBy { it.path }
            .toList()
    }

    /**
     * Generic Playerjs playlist extraction for DLE sites sharing the allhentai pattern:
     * article embeds `new Playerjs({..., file:"/pl/<cat>/<id>/playlist.txt", ...})` whose
     * target is a JSON array of {title,file} with direct MP4s.
     */
    private fun fetchPlaylistStream(base: String, path: String): HentaiStream? {
        val body = httpGet(base + path, referer = "$base/") ?: return null
        val playlistPath = Regex("file:\"(/pl/[^\"]+playlist\\.txt)").find(body)?.groupValues?.get(1)
            ?: run { Log.i(TAG, "article has no playerjs playlist"); return null }
        val playlistBody = httpGet(base + playlistPath, referer = "$base/") ?: return null
        val episodes = runCatching {
            val arr = JSONArray(playlistBody)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val file = item.optString("file").trim()
                    if (file.startsWith("http")) {
                        val raw = item.optString("title").trim()
                        // Normalize "серия1" → "Серия 1", "Episode 3" → "Episode 3"
                        val label = Regex("^(\\w+?)\\s*(\\d+)$").find(raw)?.let { m ->
                            m.groupValues[1].replaceFirstChar { it.uppercase() } + " " + m.groupValues[2]
                        } ?: raw.ifBlank { "Серия ${i + 1}" }
                        add(label to file)
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (episodes.isEmpty()) { Log.i(TAG, "playlist empty"); return null }
        Log.i(TAG, "playlist: ${episodes.size} episode(s)")
        val (firstLabel, firstUrl) = episodes.first()
        return HentaiStream(
            url = firstUrl,
            headers = mapOf("User-Agent" to USER_AGENT),
            quality = "Auto",
            title = firstLabel,
            episodes = episodes.takeIf { it.size > 1 }.orEmpty()
        )
    }

    /**
     * Article pages embed `new Playerjs({..., file:"/pl/<cat>/<id>/playlist.txt?a=N", ...})`;
     * that playlist is a JSON array of {title,file} with direct cdn.allhentai.fun MP4s.
     */

    // ============================== hanime1.me ==============================

    private fun warmH1() {
        if (h1WarmedUp) return
        synchronized(this) {
            if (h1WarmedUp) return
            runCatching {
                val builder = Request.Builder().url(H1_BASE).get()
                oppaiHeaders("$H1_BASE/").forEach { (k, v) -> builder.header(k, v) }
                httpClient.newCall(builder.build()).execute().close()
            }
            h1WarmedUp = true
        }
    }

    /** GET /search?query=… → cards look like <div title="NAME" class="video-item-container"> … watch?v=ID */
    private fun searchH1(query: String): List<H1Item> {
        warmH1()
        val url = "$H1_BASE/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = httpGet(url, referer = "$H1_BASE/", extraHeaders = oppaiHeaders("$H1_BASE/"))
            ?: return emptyList()
        return body.split("video-item-container").drop(1).mapNotNull { chunk ->
            val title = Regex("title=\"([^\"]{3,140})\"").find(chunk)?.groupValues?.get(1)
                ?.let(::unescapeHtml)
                ?: return@mapNotNull null
            val id = Regex("watch\\?v=(\\d+)").find(chunk)?.groupValues?.get(1)
                ?: return@mapNotNull null
            H1Item(id, title)
        }
    }

    /**
     * Watch pages carry plain progressive files:
     * <source src="https://vdownload-N.hembed.com/ID-720p.mp4?token=…" type="video/mp4" size="720">
     */
    private fun fetchH1Stream(videoId: String): HentaiStream? {
        val body = httpGet(
            "$H1_BASE/watch?v=$videoId",
            referer = "$H1_BASE/",
            extraHeaders = oppaiHeaders("$H1_BASE/")
        ) ?: return null
        val qualities = linkedMapOf<String, String>()
        var maxHeightKey: String? = null
        var maxHeight = -1
        for (match in Regex("<source src=\"([^\"]+)\" type=\"video/mp4\" size=\"(\\d+)\"").findAll(body)) {
            val url = match.groupValues[1].trim()
            val height = match.groupValues[2].toIntOrNull() ?: continue
            if (!url.startsWith("http") || height <= 0) continue
            val key = "${height}p"
            if (!qualities.containsKey(key)) qualities[key] = url
            if (height > maxHeight) {
                maxHeight = height
                maxHeightKey = key
            }
        }
        if (qualities.isEmpty()) {
            Log.i(TAG, "h1 watch page has no mp4 sources")
            return null
        }
        val bestKey = QUALITY_LADDER.firstOrNull { qualities.containsKey(it) }
            ?: maxHeightKey
            ?: return null
        return HentaiStream(
            url = qualities.getValue(bestKey),
            qualities = LinkedHashMap(qualities),
            headers = mapOf("User-Agent" to USER_AGENT),
            quality = bestKey
        )
    }

    /**
     * hanime1 hosts franchise episodes as separate videos ("Bible Black 1..6", "霸凌 ～復仇催眠～ 1..4").
     * When the picked hit has numbered siblings inside the same result page, resolve each sibling's
     * watch page (capped) and surface them as the series switcher; single hits stay untouched.
     */
    private fun resolveH1WithSeries(items: List<H1Item>, matchedId: String): HentaiStream? {
        val matched = items.firstOrNull { it.id == matchedId } ?: return null
        val main = fetchH1Stream(matchedId) ?: return null
        val base = normalizeTitle(matched.title).replace(Regex("\\s*\\d{1,3}$"), "").trim()
        if (base.length < 2) return main
        data class Sibling(val num: Int, val id: String)
        val siblings = items.mapNotNull { item ->
            if (item.id == matchedId) return@mapNotNull null
            val norm = normalizeTitle(item.title)
            val m = Regex("^(.*) (\\d{1,3})$").find(norm) ?: return@mapNotNull null
            if (m.groupValues[1].trim() != base) return@mapNotNull null
            Sibling(m.groupValues[2].toIntOrNull() ?: return@mapNotNull null, item.id)
        }.distinctBy { it.num }.sortedBy { it.num }.take(9)
        if (siblings.isEmpty()) return main
        Log.i(TAG, "h1 series \"${matched.title}\": +${siblings.size} sibling episode(s)")
        val currentNum = trailingEpisode(normalizeTitle(matched.title))
            .let { if (it == Int.MAX_VALUE) 1 else it }
        val entries = mutableListOf(currentNum to ("Серия $currentNum" to main.url))
        for (sibling in siblings) {
            val siblingStream = runCatching { fetchH1Stream(sibling.id) }.getOrNull() ?: continue
            entries += sibling.num to ("Серия ${sibling.num}" to siblingStream.url)
        }
        if (entries.size < 2) return main
        return main.copy(episodes = entries.sortedBy { it.first }.map { it.second })
    }

    /**
     * Synchronous check against the cached hanime catalog — no network needed if the catalog
     * was already fetched. Returns true when either title variant matches a known hentai entry.
     * Safe to call from composition; returns false when catalog isn't loaded yet.
     */
    fun isKnownHentai(originalTitle: String?, russianTitle: String?): Boolean {
        val cached = catalogCache ?: return false
        val queries = listOfNotNull(
            originalTitle?.trim()?.takeIf { it.isNotEmpty() },
            russianTitle?.trim()?.takeIf { it.isNotEmpty() }
        ).distinct()
        return queries.any { q ->
            cached.any { titleMatches(it.name, it.altTitles, q) }
        }
    }

    /** Pre-warms the catalog cache so [isKnownHentai] works synchronously. Call from IO scope. */
    suspend fun preloadCatalog() {
        withContext(Dispatchers.IO) { loadCatalog() }
    }

    // ====================== хентай-теги (жанры 18+ тайтлов) ======================

    /**
     * Русские подписи для тегов каталога hanime (английские слаги). Словарь покрывает
     * ходовую часть их закрытого набора; непереведённые теги уходят в UI как есть.
     */
    private val TAG_RU: Map<String, String> = mapOf(
        "3d" to "3D",
        "all girls" to "Только девушки",
        "anal" to "Анал",
        "ahegao" to "Ахэгао",
        "big boobs" to "Большая грудь",
        "blackmail" to "Шантаж",
        "blackmailed" to "Шантаж",
        "blindfold" to "Повязка на глазах",
        "blow job" to "Минет",
        "bondage" to "Бондаж",
        "bunny girl" to "Девушка-зайка",
        "cat girl" to "Девушка-кошка",
        "childhood friend" to "Друг детства",
        "comedy" to "Комедия",
        "cosplay" to "Косплей",
        "cow girl" to "Наездница",
        "creampie" to "Кремпай",
        "cum swallowing" to "Проглатывание",
        "dark skin" to "Тёмная кожа",
        "demon" to "Демоны",
        "dog girl" to "Девушка-собака",
        "double penetration" to "Двойное проникновение",
        "drama" to "Драма",
        "elf" to "Эльфы",
        "facial" to "Финиши на лицо",
        "fantasy" to "Фэнтези",
        "female teacher" to "Учительница",
        "femdom" to "Фемдом",
        "foot job" to "Стимуляция ногами",
        "futanari" to "Футанари",
        "gang bang" to "Групповуха",
        "glasses" to "Очки",
        "group sex" to "Групповой секс",
        "gym shorts" to "Спортивные шорты",
        "hand job" to "Стимуляция руками",
        "harem" to "Гарем",
        "historical" to "Историческое",
        "horror" to "Хоррор",
        "hypnosis" to "Гипноз",
        "incest" to "Инцест",
        "isekai" to "Исекай",
        "kimono" to "Кимоно",
        "kiss" to "Поцелуи",
        "lactation" to "Лактация",
        "magical girl" to "Волшебницы",
        "maid" to "Горничная",
        "masturbation" to "Мастурбация",
        "mind break" to "Слом разума",
        "monster girl" to "Монстр-девушки",
        "mother" to "Мать",
        "netorare" to "Нетораре (NTR)",
        "nurse" to "Медсестра",
        "office lady" to "Офисная сотрудница",
        "oral" to "Оральный секс",
        "paizuri" to "Пайдзури",
        "pantyhose" to "Колготки",
        "parody" to "Пародия",
        "plot" to "Сюжетное",
        "ponytail" to "Хвост",
        "pregnancy" to "Беременность",
        "public sex" to "Секс на людях",
        "romance" to "Романтика",
        "school girl" to "Школьница",
        "scat" to "Скат",
        "sister" to "Сестра",
        "stockings" to "Чулки",
        "supernatural" to "Сверхъестественное",
        "swimsuit" to "Купальник",
        "tentacles" to "Щупальца",
        "threesome" to "Втроём",
        "toys" to "Игрушки",
        "trap" to "Трап",
        "twintails" to "Два хвостика",
        "vanilla" to "Ваниль",
        "vampire" to "Вампиры",
        "virgin" to "Девственница",
        "voyeurism" to "Вуайеризм",
        "wolf girl" to "Девушка-волк",
        "x-ray" to "Просвечивание",
        "yaoi" to "Яой",
        "yuri" to "Юри"
    )

    /** Служебные слаги, не несущие смысла как жанр. */
    private val TAG_JUNK = setOf("hd")

    /** Переводит слаги каталога в чипы-жанры; порядок каталога сохраняется. */
    fun localizeTags(tags: List<String>): List<String> = tags.asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && it !in TAG_JUNK }
        .map { TAG_RU[it] ?: it.replaceFirstChar { c -> c.uppercase() } }
        .distinct()
        .toList()

    /**
     * Хентай-теги тайтла из кэша каталога hanime — уже переведённые на русский.
     * Пусто для тайтлов вне каталога (находятся только через allhentai/oppai).
     */
    suspend fun hentaiTags(originalTitle: String?, russianTitle: String?): List<String> =
        withContext(Dispatchers.IO) {
            val queries = titleQueries(originalTitle, russianTitle)
            if (queries.isEmpty()) return@withContext emptyList()
            val catalog = runCatching { loadCatalog() }.getOrDefault(emptyList())
            val entry = queries.firstNotNullOfOrNull { q ->
                catalog.firstOrNull { titleMatches(it.name, it.altTitles, q) }
            } ?: return@withContext emptyList()
            localizeTags(entry.tags)
        }

    // ============================ превью-клип («трейлер») ============================

    data class HentaiTrailer(
        /** Прямой mp4 минимального качества со страницы просмотра. */
        val previewUrl: String,
        /** Обложка каталога — постер первой ячейки «Кадров». */
        val posterUrl: String? = null
    )

    private const val TRAILER_TTL_MS = 30 * 60 * 1000L

    // hembed-ссылки содержат token/expires — живём недолго и перезапрашиваем.
    private val trailerCache = ConcurrentHashMap<String, Pair<Long, HentaiTrailer>>()

    /**
     * Превью-клип для страницы тайтла. Официальных трейлеров у хентая нет, поэтому берём
     * рендицию минимального качества (480p) с hanime1-страницы совпавшей записи каталога;
     * обложка каталога идёт постером ячейки. null — тайтла нет в каталоге/страница недоступна.
     */
    suspend fun hentaiTrailer(originalTitle: String?, russianTitle: String?): HentaiTrailer? =
        withContext(Dispatchers.IO) {
            val queries = titleQueries(originalTitle, russianTitle)
            if (queries.isEmpty()) return@withContext null
            val cacheKey = queries.joinToString("|").lowercase()
            trailerCache[cacheKey]?.let { (at, cached) ->
                if (System.currentTimeMillis() - at < TRAILER_TTL_MS) return@withContext cached
            }

            val catalog = runCatching { loadCatalog() }.getOrDefault(emptyList())
            val entry = queries.firstNotNullOfOrNull { q ->
                catalog.firstOrNull { titleMatches(it.name, it.altTitles, q) }
            } ?: return@withContext null
            if (entry.videoId.isEmpty()) return@withContext null

            val body = runCatching {
                warmH1()
                httpGet(
                    "$H1_BASE/watch?v=${entry.videoId}",
                    referer = "$H1_BASE/",
                    extraHeaders = oppaiHeaders("$H1_BASE/")
                )
            }.getOrNull()
            if (body.isNullOrEmpty()) return@withContext null

            // Та же разметка, что у fetchH1Stream: <source src="…" type="video/mp4" size="N">.
            // Для превью сознательно берём МИНИМАЛЬную высоту — трафик важнее резкости.
            val sources = Regex("<source src=\"([^\"]+)\" type=\"video/mp4\" size=\"(\\d+)\"")
                .findAll(body)
                .mapNotNull { m ->
                    val url = m.groupValues[1].trim()
                    val height = m.groupValues[2].toIntOrNull()
                    if (url.startsWith("http") && height != null && height > 0) height to url else null
                }
                .toList()
            val previewUrl = sources.minByOrNull { it.first }?.second
                ?: Regex("https://[a-z0-9.-]+/[^\"'\\s]+\\.mp4[^\"'\\s]*", RegexOption.IGNORE_CASE)
                    .find(body)?.value?.trim()?.replace(" ", "%20")
            if (previewUrl.isNullOrEmpty()) {
                Log.i(TAG, "hentaiTrailer ${entry.slug}: no mp4 source on watch page")
                return@withContext null
            }

            val trailer = HentaiTrailer(
                previewUrl = previewUrl,
                posterUrl = entry.coverUrl.takeIf { it.startsWith("http") }
                    ?: entry.posterUrl.takeIf { it.startsWith("http") }
            )
            Log.i(TAG, "hentaiTrailer \"${entry.name}\" -> ${trailer.previewUrl.takeLast(48)}")
            trailerCache[cacheKey] = System.currentTimeMillis() to trailer
            trailer
        }

    // ============================== hentai stills ("Кадры") ==============================

    /** Потолок блока «Кадры» — ImagesCard показывает ровно столько же (images.take(24)). */
    private const val FRAMES_LIMIT = 24

    private val framesCache = ConcurrentHashMap<String, List<FilmImageItem>>()

    /**
     * Кадры для блока «Кадры». Источники по надёжности:
     *  1. Галереи DLE-статей allhentai.fun/hentaidream.fun (/uploads/posts/star.webp) — РФ-доступны
     *     без VPN, отдают настоящие скриншоты (проверено). Совпадение статьи — тем же
     *     pickBest, что и при разрешении потока.
     *  2. Скрейп hanime1-страницы записи каталога (слайдер предпросмотров, затем вся страница);
     *     работает не из всех сетей — поэтому вторым шагом.
     *  3. Обложка и постер из каталога hanime — чтобы блок никогда не был пустым.
     */
    suspend fun hentaiFrames(originalTitle: String?, russianTitle: String?): List<FilmImageItem> =
        withContext(Dispatchers.IO) {
            val queries = titleQueries(originalTitle, russianTitle)
            if (queries.isEmpty()) return@withContext emptyList()
            val cacheKey = queries.joinToString("|").lowercase()
            framesCache[cacheKey]?.let { return@withContext it }

            val frames = mutableListOf<FilmImageItem>()
            fun addFrame(rawUrl: String) {
                val url = unescapeHtml(rawUrl).trim()
                if (url.startsWith("http") && frames.none { it.imageUrl == url }) {
                    frames.add(FilmImageItem(imageUrl = url, previewUrl = url))
                }
            }

            // Каталог нужен всем трём источникам: алиасы для DLE-поиска, videoId hanime1 и арт.
            val catalog: List<CatalogEntry> = runCatching { loadCatalog() }.getOrDefault(emptyList())
            if (catalog.isEmpty()) Log.w(TAG, "hentaiFrames: hanime catalog unavailable")
            val entry = queries.firstNotNullOfOrNull { q ->
                catalog.firstOrNull { titleMatches(it.name, it.altTitles, q) }
            }

            // 1. Галереи DLE-статей; промах прямых запросов добирается алиасами каталога:
            //    каталог allhentai транслитерирован, русский заголовок часто не матчится.
            runCatching {
                findDleGalleryImages(queries, entry?.let(::aliasQueries).orEmpty()).forEach(::addFrame)
            }.onFailure { Log.w(TAG, "dle gallery step failed: ${it.javaClass.simpleName}") }
            val dleCount = frames.size
            // 2. Страница просмотра hanime1 (когда доступна): сначала слайдер, потом вся страница.
            var h1Count = 0
            if (entry != null && entry.videoId.isNotEmpty() && frames.size < FRAMES_LIMIT) {
                val body = runCatching {
                    warmH1()
                    httpGet(
                        "$H1_BASE/watch?v=${entry.videoId}",
                        referer = "$H1_BASE/",
                        extraHeaders = oppaiHeaders("$H1_BASE/")
                    )
                }.getOrNull()
                if (!body.isNullOrEmpty()) {
                    val before = frames.size
                    val sliderScope = Regex("slider-preview-items.*?</ul>", RegexOption.DOT_MATCHES_ALL)
                        .find(body)?.value
                    for (scope in listOfNotNull(sliderScope, body)) {
                        Regex("""<img[^>]+(?:data-src|src)="(https://[^"]*hanime[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)""")
                            .findAll(scope)
                            .forEach { addFrame(it.groupValues[1]) }
                        if (frames.size >= FRAMES_LIMIT) break
                    }
                    h1Count = frames.size - before
                }
            }

            // 3. Арт из каталога — фолбэк, гарантирующий непустой блок.
            val beforeArt = frames.size
            entry?.coverUrl?.takeIf { it.startsWith("http") }?.let(::addFrame)
            entry?.posterUrl?.takeIf { it.startsWith("http") }?.let(::addFrame)

            val result = frames.take(FRAMES_LIMIT)
            Log.i(
                TAG,
                "hentaiFrames [${queries.firstOrNull()}] dle=${dleCount} h1=$h1Count art=${frames.size - beforeArt} " +
                    "-> ${result.size} imgs" + (entry?.let { " (catalog: \"${it.name}\")" } ?: "")
            )
            // Пустой результат не кэшируем: разовый сбой сети не должен убить «Кадры» до рестарта.
            if (result.isNotEmpty()) framesCache[cacheKey] = result
            result
        }

    /**
     * Ищет статью о тайтле на DLE-сайтах (движок allhentai) и вытаскивает её галерею
     * /uploads/posts/… — абсолютные URL картинок статьи. Пусто, если статья не найдена.
     * [aliases] — алиасы записи каталога hanime: статьи AH названы транслитом/ромадзи,
     * поэтому русский запрос добирается ими во второй волне поиска.
     */
    private fun findDleGalleryImages(queries: List<String>, aliases: List<String> = emptyList()): List<String> {
        val attempts = (queries + aliases + queries.mapNotNull(::translitRu)).distinct()
        for (base in listOf(AH_BASE, HD_BASE)) {
            for (query in attempts) {
                val articles = runCatching { searchAllHentaiOn(base, query) }.getOrDefault(emptyList())
                val picked = pickBest(
                    articles.map { CandidateView(it.path, "${it.label} ${slugWords(it.path)}") },
                    query
                ) ?: continue
                val path = articles.first { it.path == picked }.path
                val body = httpGet(base + path, referer = "$base/") ?: continue
                val urls = Regex("""(?:src|href)="((?:https?://[^"/]+)?/uploads/posts/[^"]+\.(?:webp|jpe?g|png)[^"]*)""", RegexOption.IGNORE_CASE)
                    .findAll(body)
                    .map { m ->
                        val raw = m.groupValues[1].trim()
                        if (raw.startsWith("http")) raw else base + raw
                    }
                    .distinct()
                    .toList()
                if (urls.isNotEmpty()) {
                    Log.i(TAG, "dle gallery \"$path\" @ $base (query=\"$query\") -> ${urls.size} imgs")
                    return urls
                }
            }
        }
        Log.w(TAG, "dle gallery: no article matched, attempts=$attempts")
        return emptyList()
    }

    // ============================== hanime.tv ==============================

    private fun loadCatalog(): List<CatalogEntry> {
        catalogCache?.let { cached ->
            if (System.currentTimeMillis() - catalogFetchedAtMs < CATALOG_TTL_MS) return cached
        }
        val parsed = runCatching {
            httpClient.newCall(
                Request.Builder()
                    .url(HANIME_CATALOG_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", HANIME_REFERER)
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "catalog http ${response.code}")
                    return@runCatching emptyList<CatalogEntry>()
                }
                val body = response.body?.string()
                    ?: return@runCatching emptyList<CatalogEntry>()
                val data = JSONObject(body).optJSONArray("data")
                    ?: return@runCatching emptyList<CatalogEntry>()
                buildList {
                    for (i in 0 until data.length()) {
                        val entry = data.optJSONObject(i) ?: continue
                        val slug = entry.optString("slug").trim()
                        if (slug.isEmpty()) continue
                        add(
                            CatalogEntry(
                                slug = slug,
                                name = entry.optString("name").trim(),
                                altTitles = entry.optString("search_titles").trim(),
                                videoId = entry.optString("id").trim(),
                                coverUrl = entry.optString("cover_url").trim(),
                                posterUrl = entry.optString("poster_url").trim(),
                                tags = entry.optJSONArray("tags")
                                    ?.let { arr -> (0 until arr.length()).mapNotNull { k -> arr.optString(k).trim().takeIf(String::isNotEmpty) } }
                                    .orEmpty()
                            )
                        )
                    }
                }
            }
        }.onFailure { Log.w(TAG, "catalog fetch failed: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        if (parsed.isNotEmpty()) {
            catalogCache = parsed
            catalogFetchedAtMs = System.currentTimeMillis()
        }
        return parsed
    }

    /**
     * Search aliases that are safe to query: the full canonical name (romaji) plus CJK runs
     * from alt titles. Single latin words ("Toki", "Time", "Ep") are poison — they match
     * unrelated videos — so fragments are never queried on their own.
     */
    private fun aliasQueries(entry: CatalogEntry): List<String> {
        val aliases = linkedSetOf<String>()
        if (entry.name.length >= 4) aliases.add(entry.name)
        Regex("[\\p{IsHan}\\p{InHiragana}\\p{InKatakana}]{2,}").findAll(entry.altTitles)
            .forEach { aliases.add(it.value) }
        return aliases.toList().take(4)
    }

    /** Normalized title view of a search hit, keyed for picking. */
    private data class CandidateView(val key: String, val title: String)

    /**
     * Scores every hit and returns the key of the best one (null when nothing is credible).
     *
     * Ranking guards against the failure mode "first result wins": exact equality > prefix with
     * a small episode number ("Bible Black 1" beats "Bible Black 5" and any spinoff) > weak
     * containment. Containment additionally requires the wanted phrase to be long enough
     * (≥6 latin chars or ≥3 CJK chars) so generic words can never produce a match.
     */
    private fun pickBest(items: List<CandidateView>, query: String): String? {
        val wanted = normalizeTitle(query)
        if (wanted.isEmpty()) return null
        val cjkWanted = wanted.any { it.code >= 0x2E80 }
        val minContain = if (cjkWanted) 3 else 6
        var bestKey: String? = null
        var bestScore = 0
        var bestLength = Int.MAX_VALUE
        var bestEpisode = Int.MAX_VALUE
        for (item in items) {
            val candidate = normalizeTitle(item.title)
            if (candidate.isEmpty()) continue
            val score = scoreCandidate(candidate, wanted, minContain)
            if (score <= 0) continue
            // Equal-score ties (a whole episode list) go to the lowest trailing episode number,
            // then to the shortest title — "… 1" must beat "… 3" regardless of site ordering.
            val episode = trailingEpisode(candidate)
            if (score > bestScore ||
                (score == bestScore && (episode < bestEpisode || (episode == bestEpisode && candidate.length < bestLength)))
            ) {
                bestScore = score
                bestLength = candidate.length
                bestEpisode = episode
                bestKey = item.key
            }
        }
        return bestKey
    }

    private fun trailingEpisode(normalizedTitle: String): Int =
        Regex("(^| )(\\d{1,3})$").find(normalizedTitle)?.groupValues?.get(2)?.toIntOrNull()
            ?: Int.MAX_VALUE

    private fun scoreCandidate(candidate: String, wanted: String, minContain: Int): Int {
        if (candidate == wanted) return 100
        // Candidate starts with the whole wanted phrase → series entry; prefer low episode numbers.
        if (candidate.startsWith(wanted)) {
            val rest = candidate.removePrefix(wanted)
            if (rest.isEmpty()) return 95
            if (rest.startsWith(" ")) {
                val tail = rest.trim()
                val episode = tail.toIntOrNull()
                return when {
                    episode != null && episode in 1..99 -> 90 - episode.coerceAtMost(20)
                    tail.length <= 12 -> 78
                    else -> 70
                }
            }
        }
        // Wanted extends the candidate ("Kowaku no Toki" vs earlier franchise entry) — usable.
        if (wanted.startsWith("$candidate ")) return 55
        // Whole-word containment, only for distinctive phrases.
        if (wanted.length >= minContain &&
            Regex("(^| )${Regex.escape(wanted)}( |$)").containsMatchIn(candidate)
        ) return 60
        return -1
    }

    // ============================== rule34.xxx — REMOVED ==============================
    // The dapi now requires a personal account + API key, which does not fit an anonymous app.
    // hanime.tv playback also removed: CF blocks most VPN exit IPs with 403.

    // ============================ shared helpers ============================

    private fun httpGet(
        url: String,
        referer: String? = null,
        client: OkHttpClient = httpClient,
        extraHeaders: Map<String, String>? = null,
        acceptRedirectBody: Boolean = false
    ): String? {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        extraHeaders?.forEach { (k, v) -> builder.header(k, v) }
        // Retry once on transient failure (VPN reconnect, DNS blip, mobile network switch).
        repeat(2) { attempt ->
            try {
                client.newCall(builder.get().build()).execute().use { response ->
                    if (response.isSuccessful || (acceptRedirectBody && response.isRedirect)) {
                        return response.body?.string()
                    }
                    Log.w(TAG, "GET $url -> ${response.code} (attempt ${attempt + 1})")
                    if (response.code >= 500 && attempt == 0) return@repeat // retry 5xx
                    return null
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "GET $url attempt ${attempt + 1} failed: ${e.javaClass.simpleName}")
                if (attempt == 1) return null
                Thread.sleep(2000) // brief pause before retry
            }
        }
        return null
    }

    internal fun normalizeTitle(raw: String): String =
        raw.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /** Exact normalized equality or whole-word containment against either side. */
    internal fun titleMatches(candidateTitle: String, query: String): Boolean =
        titleMatches(candidateTitle, "", query)

    internal fun titleMatches(name: String, altTitles: String, query: String): Boolean {
        val wanted = normalizeTitle(query)
        if (wanted.isEmpty()) return false
        for (raw in listOf(name, altTitles)) {
            val candidate = normalizeTitle(raw)
            if (candidate.isEmpty()) continue
            if (candidate == wanted) return true
            if (Regex("(^| )${Regex.escape(wanted)}( |$)").containsMatchIn(candidate)) return true
        }
        return false
    }

    private fun unescapeHtml(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
}
/**
 * Hentai stream sources exposed in the manual source sheet, grouped by [language].
 * [needsVpn] reflects Aug-2026 RF-network reachability.
 */
enum class HentaiProvider(
    val displayName: String,
    val description: String,
    val language: String,
    val needsVpn: Boolean
) {
    ALLHENTAI(
        "AllHentai",
        "Русская озвучка/сабы, нативные MP4, без VPN",
        "RU", false
    ),
    HENTAIDREAM(
        "HentaiDream",
        "Русская озвучка/сабы, нативные MP4, без VPN",
        "RU", false
    ),
    HANIME1(
        "Hanime1.me",
        "Оригинал с японскими титрами, потоки 480–1080p",
        "JA", true
    ),
    OPPAI(
        "Oppai.Stream",
        "Английский сайт 4K-мастеров, MP4 720/1080p",
        "JA", true
    );
}

