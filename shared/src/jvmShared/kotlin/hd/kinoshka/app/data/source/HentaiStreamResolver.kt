package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.FilmImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** Per episode; empty for single-video titles. */
    val episodes: List<HentaiEpisode> = emptyList()
)

/** One episode of a hentai series: label, direct url, best advertised quality (e.g. "720p"). */
data class HentaiEpisode(
    val label: String,
    val url: String,
    val maxQuality: String? = null
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

    /** Зеркало hentaiz: основной домен hentaiz.org РФ-недоступен (SNI-блок), зеркало отдаёт
     *  страницы, скриншоты и видео старых тайтлов; видео новых лежит только на hentaiz.org. */
    private const val HZ_BASE = "https://ru.hentaiiz.org"

    private const val HANIME_CATALOG_URL = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    /** Дисковая копия каталога hanime; mtime файла = время загрузки (TTL тот же). */
    private const val CATALOG_DISK_FILE = "hanime_catalog.json"

    /** Потолок на весь шаг извлечения кадров: зависший retriever не должен держать «Кадры». */
    private const val VIDEO_FRAMES_TIMEOUT_MS = 60 * 1000L

    /** Каталог кэша приложения: кадры из видео и дисковая копия каталога hanime.
     *  Платформенно-нейтральная замена прежнему android.content.Context. */
    @Volatile private var cacheDir: java.io.File? = null

    fun init(cacheDir: java.io.File?) {
        this.cacheDir = cacheDir
    }

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

    /**
     * Нормализованные (name, altTitles) каталога. isKnownHentai вызывается синхронно на
     * main-потоке (загрузка страницы аниме, кнопка «Смотреть»), а normalizeTitle на каждую
     * запись каталога — секунды регэкспов: считаем их один раз здесь, при загрузке, на IO.
     */
    @Volatile private var catalogIndex: List<Pair<String, String>> = emptyList()

    /** Мемо isKnownHentai по паре заголовков: фид/страница деталей спрашивают одни и те же. */
    private val knownHentaiMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Мемо тегов по паре заголовков: страница ленты хентая спрашивает их пачками. */
    private val tagsMemo = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun rebuildCatalogIndex(entries: List<CatalogEntry>) {
        catalogIndex = entries.map { normalizeTitle(it.name) to normalizeTitle(it.altTitles) }
    }

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
            resolveViaAniStar(queries)?.let { return@withContext it }
            resolveViaHentaiz(queries)?.let { return@withContext it }
            resolveViaHanime1(queries)?.let { return@withContext it }
            resolveViaSmarthard(queries, shikimoriId = 0)?.let { return@withContext it }
            resolveViaOppai(queries)?.let { return@withContext it }
            KLog.i(TAG, "no hentai stream found for $queries")
            null
        }

    /**
     * Resolves through ONE chosen provider — the source sheet calls this so the user can see
     * which sources need a VPN and pick manually instead of relying on auto-fallback order.
     * [shikimoriId] (когда тайтл приходит со страницы Shikimori) позволяет smarthard искать
     * записи напрямую по id вместо нечёткого поиска по названию.
     */
    suspend fun resolveFor(
        provider: HentaiProvider,
        originalTitle: String?,
        russianTitle: String?,
        shikimoriId: Int = 0
    ): HentaiStream? = withContext(Dispatchers.IO) {
        val queries = titleQueries(originalTitle, russianTitle)
        if (queries.isEmpty()) return@withContext null
        KLog.i(TAG, "resolveFor ${provider.name}: $queries")
        val stream = when (provider) {
            HentaiProvider.ALLHENTAI -> resolveViaAllHentai(queries)
            HentaiProvider.HENTAIDREAM -> resolveViaHentaiDream(queries)
            HentaiProvider.ANISTAR -> resolveViaAniStar(queries)
            HentaiProvider.HENTAIZ -> resolveViaHentaiz(queries)
            HentaiProvider.HANIME1 -> resolveViaHanime1(queries)
            HentaiProvider.SMARTHARD -> resolveViaSmarthard(queries, shikimoriId)
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

    /** Видимость internal: слаги DLE-статей AniStar тоже транслит, ищет AniStarResolver. */
    internal fun translitRu(query: String): String? {
        if (!query.any { it in 'а'..'я' || it == 'ё' || it in 'А'..'Я' || it == 'Ё' }) return null
        return buildString {
            for (ch in query.lowercase()) CYR_MAP[ch]?.let(::append) ?: append(ch)
        }.trim().takeIf { it.isNotEmpty() && it != query.lowercase() }
    }

    // ---- provider 1: allhentai.fun (RU CDN, episode playlists) ----
    private suspend fun resolveViaAllHentai(queries: List<String>): HentaiStream? {
        for (query in queries) {
            val articles = runCatching { searchAllHentai(query) }
                .onFailure { KLog.w(TAG, "ah search failed for \"$query\": ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            KLog.i(TAG, "allhentai \"$query\" -> ${articles.size} articles")
            pickBest(articles.map { CandidateView(it.path, "${it.label} ${slugWords(it.path)}") }, query)
                ?.let { picked ->
                    val matched = articles.first { it.path == picked }
                    KLog.i(TAG, "allhentai matched \"${matched.label}\" (${matched.path}) for \"$query\"")
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
                KLog.i(TAG, "allhentai(alias=\"$alias\") -> ${articles.size} articles")
                pickBest(
                    articles.map { CandidateView(it.path, "${it.label} ${slugWords(it.path)}") },
                    alias
                )?.let { picked ->
                    val matched = articles.first { it.path == picked }
                    KLog.i(TAG, "allhentai(alias=\"$alias\") matched \"${matched.label}\"")
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
            KLog.i(TAG, "catalog matched \"${entry.name}\" for \"$query\"")
            for (alias in aliasQueries(entry).filter { alias -> alias.any { it.code >= 0x2E80 } }) {
                val items: List<H1Item> = runCatching { searchH1(alias) }.getOrDefault(emptyList())
                KLog.i(TAG, "h1(alias=\"$alias\") -> ${items.size} hits")
                if (items.isEmpty()) continue
                // CJK aliases are pre-verified by the catalog dump, but short/fragment aliases
                // still bring back unrelated hits — accept a hit only when its title actually
                // carries the alias (equality or the alias followed by an episode suffix).
                val wanted = foldKana(normalizeTitle(alias))
                val hit = items.firstOrNull { item ->
                    foldKana(normalizeTitle(item.title)).startsWith(wanted)
                } ?: run {
                    KLog.i(TAG, "h1 CJK alias \"$alias\": no hit title carries the alias, skipping")
                    continue
                }
                KLog.i(TAG, "h1 CJK alias \"$alias\" accepted \"${hit.title}\"")
                resolveH1WithSeries(items, hit.id)?.let { return it }
            }
            break // one catalog lookup is enough to gather aliases
        }
        return null
    }

    // ---- provider 3: hentaidream.fun (RU CDN, same Playerjs/playlist pattern as allhentai) ----
    private suspend fun resolveViaHentaiDream(queries: List<String>): HentaiStream? {
        for (query in queries) {
            val articles = runCatching { searchAllHentaiOn(HD_BASE, query) }
                .onFailure { KLog.w(TAG, "hd search failed for \"$query\": ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            KLog.i(TAG, "hentaidream \"$query\" -> ${articles.size} articles")
            pickBest(articles.map { CandidateView(it.path, "${it.label} ${slugWords(it.path)}") }, query)
                ?.let { picked ->
                    val matched = articles.first { it.path == picked }
                    KLog.i(TAG, "hentaidream matched \"${matched.label}\" (${matched.path}) for \"$query\"")
                    fetchPlaylistStream(HD_BASE, matched.path)?.let { return it }
                }
        }
        return null
    }

    // ---- provider 4: AniStar (аниме-каталог с хентаем; см. AniStarResolver) ----
    private suspend fun resolveViaAniStar(queries: List<String>): HentaiStream? {
        val attempts = (queries + queries.mapNotNull(::translitRu)).distinct()
        val episodes = AniStarResolver.findEpisodes(attempts) ?: return null
        KLog.i(TAG, "anistar: ${episodes.size} episode(s) for \"$queries\"")
        return anistarEpisodesToStream(queries.firstOrNull() ?: "AniStar", episodes)
    }

    private fun anistarEpisodesToStream(
        query: String,
        episodes: List<AniStarResolver.AniStarEpisode>
    ): HentaiStream {
        val first = episodes.first()
        val eps = episodes.mapIndexedNotNull { index, ep ->
            val url = ep.bestUrl ?: return@mapIndexedNotNull null
            HentaiEpisode(ep.label.ifBlank { "Серия ${index + 1}" }, url, ep.bestQuality)
        }
        return HentaiStream(
            url = first.bestUrl ?: first.qualities.values.first(),
            qualities = first.qualities,
            headers = AniStarResolver.streamHeaders(),
            quality = first.bestQuality ?: "Auto",
            title = query,
            episodes = eps.takeIf { it.size > 1 }.orEmpty()
        )
    }

    // ---- provider 5: hentaiz.org / ru.hentaiiz.org (DLE; источники кадров «СКРИНШОТЫ») ----
    private suspend fun resolveViaHentaiz(queries: List<String>): HentaiStream? {
        for (query in queries) {
            val found = findHentaizEntry(query) ?: continue
            KLog.i(TAG, "hentaiz matched ${found.first} for \"$query\" (track=${found.second?.key})")
            hentaizEntryToStream(found)?.let { return it }
        }
        return null
    }

    /**
     * Статья hentaiz: путь + распарсенный allData-плеер — дорожка (translator/subtitles/original)
     * со списком (метка серии, лестница качеств).
     */
    private data class HentaizTrack(val key: String, val episodes: List<Pair<String, List<Pair<String, String>>>>)

    /**
     * Статья hentaiz по поисковому запросу: путь + HTML страницы. Общая для видео
     * (allData-плеер) и кадров (блок «СКРИНШОТЫ»).
     */
    private fun findHentaizPage(query: String): Pair<String, String>? {
        // Одиночный токен (ромадзи-слово «Imouto», «Overflow») никогда не пройдёт матчинг,
        // но успевает сходить в сеть за дефолтной лентой — отсекаем до запроса.
        if (reducedHentaizTokens(query).size < 2) return null
        val url = "$HZ_BASE/index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = runCatching { httpGet(url, referer = "$HZ_BASE/") }.getOrNull() ?: return null
        val articles = Regex("""href="(?:https?://[a-z0-9.-]+)?/(\d{2,6}-[a-z0-9-]+\.html)"""")
            .findAll(body)
            .map { m ->
                val path = "/${m.groupValues[1]}"
                val label = path.substringAfterLast('/')
                    .substringBefore(".html")
                    .replace(Regex("""^\d+-"""), "")
                    .replace('-', ' ')
                    .trim()
                AhArticle(path, label.ifBlank { path })
            }
            .distinctBy { it.path }
            .toList()
        if (articles.isEmpty()) return null
        // Слаги каталога hentaiz — транслит русского названия диалектом DLE (я→ja, й→y),
        // не совпадающий с таблицей резолвера (я→ya, й→j), поэтому общий pickBest здесь
        // не матчит: «абсолютный» даёт absolyutnyj против слага absolyutnyy. Статьи ранжируются
        // релевантностью самим поиском — берём первую, чьи редуцированные токены покрывают запрос.
        val matched = articles.firstOrNull { hentaizArticleMatches(query, it) } ?: return null
        val page = runCatching { httpGet(HZ_BASE + matched.path, referer = "$HZ_BASE/") }.getOrNull()
            ?: return null
        return matched.path to page
    }

    /**
     * Редукция к общему виду для матчинга hentaiz: транслит кириллицы (любая таблица),
     * не-буквы в пробелы, из токенов выкидываются j/y («ja/ya», «absolyutnyj/absolyutnyy»
     * дают одинаковые токены), «shh» схлопывается в «sh» (щ: shh против sha). Короткие
     * токены (<3) отбрасываются.
     */
    private fun reducedHentaizTokens(text: String): Set<String> {
        val translit = buildString {
            for (ch in text.lowercase()) CYR_MAP[ch]?.let(::append) ?: append(ch)
        }.replace("shh", "sh")
        return translit.replace(Regex("[^a-z0-9]+"), " ")
            .split(" ")
            .mapNotNull { token -> token.filter { it != 'j' && it != 'y' }.takeIf { it.length >= 3 } }
            .toSet()
    }

    /** Запрос покрывает статью, когда все его значимые токены есть в слаге; одиночный
     *  токен не допускается — ловит чужие статьи из дефолтной ленты пустого поиска. */
    private fun hentaizArticleMatches(query: String, article: AhArticle): Boolean {
        val wanted = reducedHentaizTokens(query)
        if (wanted.size < 2) return false
        val candidate = reducedHentaizTokens(slugWords(article.path))
        return wanted.all { it in candidate }
    }

    private fun findHentaizEntry(query: String): Pair<String, HentaizTrack?>? =
        findHentaizPage(query)?.let { (path, page) -> path to parseHentaizAllData(page) }

    /**
     * const allData = {translator|subtitles|original:[{title:"1 серия",file:"[720p]URL"},…], …};
     * Дорожки без списка серий не считаются дорожкой. URL не переписываются: старые тайтлы
     * раздаёт videos.hentaiz.org (РФ-доступен), новые лежат на hentaiz.org — зеркало их
     * не хостит (404), так что под РФ-блокировкой эта дорожка просто недоступна.
     */
    private fun parseHentaizAllData(page: String): HentaizTrack? {
        val m = Regex("""const\s+allData\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(page)
            ?: return null
        val json = runCatching { org.json.JSONObject(m.groupValues[1]) }.getOrNull() ?: return null
        for (key in listOf("translator", "subtitles", "original", "player")) {
            val arr = json.optJSONArray(key) ?: continue
            val episodes = (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val ladder = parseHentaizFile(item.optString("file").trim())
                if (ladder.isEmpty()) return@mapNotNull null
                val label = item.optString("title").trim().ifBlank { "Серия ${i + 1}" }
                label to ladder
            }
            if (episodes.isNotEmpty()) return HentaizTrack(key, episodes)
        }
        return null
    }

    /** Playerjs-формат file: "[720p]url" или мульти-качество "[360p]url1,[720p]url2". */
    private fun parseHentaizFile(file: String): List<Pair<String, String>> {
        val ladder = Regex("""\[(\d+p)\](https?://[^,\]]+)""").findAll(file)
            .map { it.groupValues[1] to it.groupValues[2].trim() }
            .toList()
        if (ladder.isNotEmpty()) return ladder
        val url = file.trim()
        return if (url.startsWith("http")) listOf("Auto" to url) else emptyList()
    }

    private fun hentaizEntryToStream(entry: Pair<String, HentaizTrack?>): HentaiStream? {
        val (path, track) = entry
        if (track == null) {
            KLog.i(TAG, "hentaiz $path: no allData player")
            return null
        }
        // Серии с разным качеством: каждая играет своим лучшим качеством; лестница первой
        // серии идёт в качества потока (переключатель плеера для текущей серии).
        val episodes = track.episodes.mapNotNull { (label, ladder) ->
            val best = ladder.maxByOrNull { (q, _) -> q.removeSuffix("p").toIntOrNull() ?: 0 }
            best?.let { (q, url) -> HentaiEpisode(label, url, q.takeIf { key -> key != "Auto" }) }
        }
        if (episodes.isEmpty()) return null
        val qualities = linkedMapOf<String, String>()
        track.episodes.first().second.forEach { (q, u) -> qualities.putIfAbsent(q, u) }
        return HentaiStream(
            url = episodes.first().url,
            qualities = qualities,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$HZ_BASE/"),
            quality = qualities.keys.maxByOrNull { it.removeSuffix("p").toIntOrNull() ?: 0 } ?: "Auto",
            title = path,
            episodes = episodes.takeIf { it.size > 1 }.orEmpty()
        )
    }

    // ---- provider: smarthard.net (архив shikicinema, ключ — Shikimori anime_id) ----
    // API и постеры доступны из РФ без VPN, но сами видео лежат на разнородных хостах: прямые
    // файлы играются сразу, sibnet-embed скрапится, прочие embed-страницы снифаются и часто
    // требуют VPN. Неразрешённые записи НЕ отбрасываются — остаются в списке с пометкой « · VPN».
    private suspend fun resolveViaSmarthard(queries: List<String>, shikimoriId: Int): HentaiStream? {
        val animeId = if (shikimoriId > 0) {
            shikimoriId
        } else {
            queries.firstNotNullOfOrNull { SmarthardApi.searchAnimeIds(it).firstOrNull() } ?: return null
        }
        val records = SmarthardApi.loadRecords(animeId)
        if (records.isEmpty()) return null
        KLog.i(TAG, "smarthard: id=$animeId -> ${records.size} records")

        // На эпизод — одна лучшая запись: русская озвучка > русские сабы > оригинал > прочее,
        // среди равных — прямой файл > sibnet > embed.
        val best = records.groupBy { it.episode }.mapValues { (_, rs) ->
            rs.minWith(
                compareBy<SmarthardApi.SmarthardRecord> { SmarthardApi.kindRank(it) }
                    .thenByDescending { record -> record.url.let { if (it.contains("video.sibnet.ru")) 1 else 0 } }
                    .thenBy { it.id }
            )
        }

        val ordered = best.entries.sortedBy { it.key }
        if (ordered.isEmpty()) return null
        val resolved = SmarthardApi.resolveLinks(ordered.map { it.value.url })
        var sibnetReferer = false
        val episodes = ordered.mapIndexed { index, (number, record) ->
            val link = resolved[index]
            if (link?.headers?.containsKey("Referer") == true) sibnetReferer = true
            val kindTag = when (record.kind) {
                "субтитры" -> " (сабы)"
                "оригинал" -> " (ориг)"
                else -> ""
            }
            val vpnTag = if (link == null) " · VPN" else ""
            HentaiEpisode(label = "Серия $number$kindTag$vpnTag", url = link?.url ?: record.url)
        }
        KLog.i(TAG, "smarthard: ${episodes.count { !it.label.contains("VPN") }}/${episodes.size} episodes playable")

        return HentaiStream(
            url = episodes.first().url,
            headers = if (sibnetReferer) mapOf("Referer" to "https://video.sibnet.ru/") else emptyMap(),
            quality = "Auto",
            // Название тайтла, а не «Smarthard»: источник и так подписан на каждой строке.
            title = queries.firstOrNull().orEmpty(),
            episodes = episodes
        )
    }

    // ---- provider 6: oppai.stream (4K catalog, VPN-gated, direct MP4 + Referer-locked CDN) ----
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
                KLog.i(TAG, "oppai direct try: $ep1Slug")
                val ep1 = fetchOppaiStream(ep1Slug) ?: continue

                // Found ep1 — probe for siblings up to 20.
                KLog.i(TAG, "oppai DIRECT hit: \"$ep1Slug\" — probing for more episodes")
                val eps = mutableListOf(HentaiEpisode("Серия 1", ep1.url, ep1.quality))
                for (n in 2..20) {
                    val sib = fetchOppaiStream("$slugVariant-$n") ?: break
                    eps.add(HentaiEpisode("Серия $n", sib.url, sib.quality))
                }
                KLog.i(TAG, "oppai series: ${eps.size} episode(s)")
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
                ?: run { KLog.w(TAG, "oppai page of=$offset: null response"); break }
            Regex("name='([^']{2,140})'[\\s\\S]{0,700}?href='https://oppai\\.stream/watch\\?e=([^'&]+)")
                .findAll(body)
                .forEach { m ->
                    val slug = m.groupValues[2].trim()
                    if (slug.isNotEmpty() && !entries.containsKey(slug)) {
                        entries[slug] = OppaiEntry(slug, unescapeHtml(m.groupValues[1].trim()))
                    }
                }
            KLog.i(TAG, "oppai page of=$offset: body=${body.length} chars, total=${entries.size}")

            // Early exit: check if any loaded entry matches before fetching more pages.
            for (query in queries) {
                pickBest(entries.values.map { CandidateView(it.slug, "${it.name} ${slugWords("/${it.slug}")}") }, query)
                    ?.let { picked ->
                        val matched = entries[picked]!!
                        KLog.i(TAG, "oppai matched \"${matched.name}\" (${matched.slug}) for \"$query\" at of=$offset")
                        fetchOppaiStream(matched.slug)?.let { return it }
                    }
            }
            if (body.length < 500) {
                KLog.i(TAG, "oppai pagination exhausted at of=$offset")
                break
            }
        }
        KLog.i(TAG, "oppai: no match in ${entries.size} entries")
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
                KLog.i(TAG, "oppai watch page has no mp4 source")
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
            KLog.i(TAG, "oppai has DASH-only ${dashQualities.joinToString("/")} (not exposed: libmpv lacks dashdec)")
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

    internal fun slugWords(path: String): String =
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
    /** DLE CDN filenames often embed the rendition ("…_720p.mp4") — surfaces as episode badge. */
    private fun qualityFromUrl(url: String): String? =
        Regex("(\\d{3,4})p").findAll(url).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.let { "${it}p" }

    /** Кэш пробинга разрешения файлов (url → "720p"): по одному range-запросу на серию. */
    private val mp4QualityCache = ConcurrentHashMap<String, String>()

    /**
     * Разрешение видеофайла без его загрузки: faststart-MP4 держат moov в голове, а tkhd несёт
     * width/height. Один range-запрос 128 КБ; null, когда moov в хвосте или парсинг не удался.
     */
    private fun probeMp4Quality(url: String, referer: String?): String? {
        mp4QualityCache[url]?.let { return it }
        val height = runCatching {
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-131071")
            referer?.let { builder.header("Referer", it) }
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val head = response.body.bytes()
                parseMp4VideoHeight(head)
            }
        }.getOrNull() ?: return null
        val quality = "${height}p"
        mp4QualityCache[url] = quality
        return quality
    }

    /** Высота видеодорожки из атомов moov→trak→tkhd (16.16 fixed-point, старшие 16 бит). */
    private fun parseMp4VideoHeight(data: ByteArray): Int? {
        var best = 0
        fun walk(from: Int, until: Int) {
            var pos = from
            while (pos + 8 <= until) {
                val bb = java.nio.ByteBuffer.wrap(data, pos, 8)
                val size = bb.int
                if (size < 8 || pos + size > until) return
                val type = String(data, pos + 4, 4, Charsets.US_ASCII)
                when (type) {
                    "moov", "trak", "mdia", "minf", "stbl" -> walk(pos + 8, pos + size)
                    "tkhd" -> {
                        val bodyLen = if (data[pos + 8].toInt() == 1) 96 else 84
                        val end = pos + 8 + bodyLen
                        if (end <= pos + size && end <= until) {
                            val tail = java.nio.ByteBuffer.wrap(data, end - 8, 8)
                            val width = tail.int ushr 16
                            val height = tail.int ushr 16
                            if (width in 100..8192 && height in 100..8192 && height > best) best = height
                        }
                    }
                }
                pos += size
            }
        }
        walk(0, data.size)
        return best.takeIf { it > 0 }
    }

    private fun fetchPlaylistStream(base: String, path: String): HentaiStream? {
        val body = httpGet(base + path, referer = "$base/") ?: return null
        val playlistPath = Regex("file:\"(/pl/[^\"]+playlist\\.txt)").find(body)?.groupValues?.get(1)
            ?: run { KLog.i(TAG, "article has no playerjs playlist"); return null }
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
                        add(HentaiEpisode(label, file, qualityFromUrl(file)))
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (episodes.isEmpty()) { KLog.i(TAG, "playlist empty"); return null }
        KLog.i(TAG, "playlist: ${episodes.size} episode(s)")
        // Плейлисты DLE не публикуют ренду — серии без метки пробиваются по голове файла
        // (faststart-MP4 отдают moov первыми 128 КБ).
        val withQuality = episodes.map { ep ->
            if (ep.maxQuality == null) ep.copy(maxQuality = probeMp4Quality(ep.url, "$base/")) else ep
        }
        val first = withQuality.first()
        return HentaiStream(
            url = first.url,
            headers = mapOf("User-Agent" to USER_AGENT),
            quality = first.maxQuality ?: "Auto",
            title = first.label,
            episodes = withQuality.takeIf { it.size > 1 }.orEmpty()
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
            KLog.i(TAG, "h1 watch page has no mp4 sources")
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
    /** Katakana→hiragana fold so "タイム" matches "たいむ" — dump aliases and site titles mix scripts. */
    private fun foldKana(raw: String): String = buildString {
        for (ch in raw) append(if (ch in '\u30A1'..'\u30F6') ch - 0x60 else ch)
    }

    /**
     * Splits a normalized h1 title into (series base, episode number). hanime1 names franchise
     * episodes "Title 7", "Title7" and "Title 第7話"; the base must not end in a digit so
     * 4-digit numbers ("2024") never become an episode. Null when there is no episode suffix.
     */
    private fun h1SeriesSplit(normalized: String): Pair<String, Int>? {
        Regex("^(.+?)\\s*第(\\d{1,3})話$").find(normalized)?.let { m ->
            val base = m.groupValues[1].trim()
            val num = m.groupValues[2].toIntOrNull()
            if (base.length >= 2 && num != null) return base to num
        }
        Regex("^(.+?\\D)\\s*(\\d{1,3})$").find(normalized)?.let { m ->
            val base = m.groupValues[1].trim()
            val num = m.groupValues[2].toIntOrNull()
            if (base.length >= 2 && num != null) return base to num
        }
        return null
    }

    private fun resolveH1WithSeries(items: List<H1Item>, matchedId: String): HentaiStream? {
        val matched = items.firstOrNull { it.id == matchedId } ?: return null
        val main = fetchH1Stream(matchedId) ?: return null
        val matchedSplit = h1SeriesSplit(normalizeTitle(matched.title))
        val base = (matchedSplit?.first ?: normalizeTitle(matched.title)).trim()
        if (base.length < 2) return main
        val currentNum = matchedSplit?.second ?: 1
        // Siblings from the same search page: same series base, any episode suffix form.
        data class Sibling(val num: Int, val id: String)
        val siblings = items.mapNotNull { item ->
            if (item.id == matchedId) return@mapNotNull null
            val split = h1SeriesSplit(normalizeTitle(item.title)) ?: return@mapNotNull null
            if (split.first != base) return@mapNotNull null
            Sibling(split.second, item.id)
        }.distinctBy { it.num }.sortedBy { it.num }.take(9)
        KLog.i(TAG, "h1 series \"${matched.title}\": base=\"$base\" cur=$currentNum, ${siblings.size} sibling(s) on page")
        val seen = mutableSetOf(currentNum)
        val entries = mutableListOf(currentNum to HentaiEpisode("Серия $currentNum", main.url, main.quality))
        for (sibling in siblings) {
            if (sibling.num in seen) continue
            val siblingStream = runCatching { fetchH1Stream(sibling.id) }.getOrNull() ?: continue
            seen += sibling.num
            entries += sibling.num to HentaiEpisode("Серия ${sibling.num}", siblingStream.url, siblingStream.quality)
        }
        // Search pages cap results and fragment aliases may miss part of the franchise, so
        // probe numbers directly ("base N") until two consecutive misses.
        if (entries.size < 2) {
            var misses = 0
            for (n in 1..12) {
                if (n == currentNum || n in seen) continue
                if (misses >= 2) break
                val hit = searchH1("$base $n").firstOrNull { item ->
                    val norm = foldKana(normalizeTitle(item.title))
                    norm == foldKana("$base $n") || norm == foldKana("$base$n") ||
                        norm == foldKana("$base 第${n}話")
                } ?: run { misses++; continue }
                misses = 0
                val stream = runCatching { fetchH1Stream(hit.id) }.getOrNull() ?: continue
                seen += n
                KLog.i(TAG, "h1 series probe: +\"${hit.title}\"")
                entries += n to HentaiEpisode("Серия $n", stream.url, stream.quality)
            }
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
        val key = "${originalTitle.orEmpty().trim()}|${russianTitle.orEmpty().trim()}"
        knownHentaiMemo[key]?.let { return it }
        val index = catalogIndex
        // Каталог ещё грузится — не отвечаем и НЕ мемоизируем: после прогрева ответ изменится.
        if (index.isEmpty()) return false
        val queries = listOfNotNull(
            originalTitle?.trim()?.takeIf { it.isNotEmpty() },
            russianTitle?.trim()?.takeIf { it.isNotEmpty() }
        ).distinct()
        val result = queries.any { q ->
            val wanted = normalizeTitle(q)
            wanted.isNotEmpty() && index.any { (normName, normAlt) ->
                normalizedContains(normName, wanted) || normalizedContains(normAlt, wanted)
            }
        }
        if (knownHentaiMemo.size > 512) knownHentaiMemo.clear()
        knownHentaiMemo[key] = result
        return result
    }

    /** Целословное вхождение [wanted] в преднормализованной строке — без регэкспов на запись. */
    private fun normalizedContains(candidate: String, wanted: String): Boolean {
        if (candidate.isEmpty()) return false
        if (candidate == wanted) return true
        var idx = candidate.indexOf(wanted)
        while (idx >= 0) {
            val beforeOk = idx == 0 || candidate[idx - 1] == ' '
            val after = idx + wanted.length
            val afterOk = after == candidate.length || candidate[after] == ' '
            if (beforeOk && afterOk) return true
            idx = candidate.indexOf(wanted, idx + 1)
        }
        return false
    }

    /** Pre-warms the catalog cache so [isKnownHentai] works synchronously. Call from IO scope. */
    suspend fun preloadCatalog() {
        withContext(Dispatchers.IO) { loadCatalog() }
    }

    /**
     * Фоновый прогрев каталога при старте приложения: теги/трейлер/кадры первой открытой
     * 18+-страницы не ждут загрузки 4.3 МБ (диск отдаёт копию мгновенно, сеть обновляет).
     */
    fun warmCatalogAsync() {
        if (catalogCache != null) return
        Thread {
            runCatching { loadCatalog() }
        }.apply {
            name = "hentai-catalog-warm"
            isDaemon = true
        }.start()
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
            val key = "${originalTitle.orEmpty().trim()}|${russianTitle.orEmpty().trim()}"
            tagsMemo[key]?.let { return@withContext it }
            val queries = titleQueries(originalTitle, russianTitle)
            if (queries.isEmpty()) return@withContext emptyList()
            val catalog = runCatching { loadCatalog() }.getOrDefault(emptyList())
            val entry = queries.firstNotNullOfOrNull { q ->
                catalog.firstOrNull { titleMatches(it.name, it.altTitles, q) }
            }
            val tags = entry?.let { localizeTags(it.tags) } ?: emptyList()
            // Пустой каталог (сбой загрузки) не мемоизируем: ответ изменится после ретрая.
            if (catalog.isNotEmpty()) {
                if (tagsMemo.size > 2048) tagsMemo.clear()
                tagsMemo[key] = tags
            }
            tags
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
                KLog.i(TAG, "hentaiTrailer ${entry.slug}: no mp4 source on watch page")
                return@withContext null
            }

            val trailer = HentaiTrailer(
                previewUrl = previewUrl,
                posterUrl = entry.coverUrl.takeIf { it.startsWith("http") }
                    ?: entry.posterUrl.takeIf { it.startsWith("http") }
            )
            KLog.i(TAG, "hentaiTrailer \"${entry.name}\" -> ${trailer.previewUrl.takeLast(48)}")
            trailerCache[cacheKey] = System.currentTimeMillis() to trailer
            trailer
        }

    // ============================== hentai stills ("Кадры") ==============================

    /** Потолок блока «Кадры» — ImagesCard показывает ровно столько же (images.take(24)). */
    private const val FRAMES_LIMIT = 24

    private val framesCache = ConcurrentHashMap<String, List<FilmImageItem>>()

    /**
     * Кадры для блока «Кадры». Источники по надёжности:
     *  0. Блок «СКРИНШОТЫ» на страницах тайтлов hentaiz (зеркало ru.hentaiiz.org) — быстрый
     *     путь, при наличии скриншотов фолбэки не запускаются.
     *  1. Кадры из первой серии стабильного DLE-источника (hentaidream → allhentai):
     *     MediaMetadataRetriever по ключевым кадрам, файлы кэшируются на диск.
     *  2. Скрейп hanime1-страницы записи каталога (слайдер предпросмотров, затем вся страница);
     *     работает не из всех сетей.
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

            // −1. Дисковый кэш кадров из видео: повторное открытие тайтла — мгновенно,
            //     без единого запроса (раньше кэш проверялся лишь после промаха hentaiz-поиска,
            //     что стоило ~4с). Сетевые источники не трогаем вовсе.
            val context = cacheDir
            if (context != null) {
                val dir = java.io.File(context, "hentai_frames")
                val cached = dir.listFiles { f -> f.name.startsWith(cacheKeySha(cacheKey) + "-") }
                    ?.sortedBy { it.name }
                    .orEmpty()
                if (cached.isNotEmpty()) {
                    val instant = cached.map(::fileFrame)
                    KLog.i(TAG, "hentaiFrames [${queries.firstOrNull()}] disk-cache ${instant.size} imgs -> instant")
                    framesCache[cacheKey] = instant
                    return@withContext instant
                }
            }

            // 0. Блок «СКРИНШОТЫ» на страницах тайтлов hentaiz — настоящие кадры тайтла
            //    (screen_*-галерея), а не обложки соседних блоков. Быстрый путь: два запроса
            //    (~1-2с) — и результат сразу в UI; фолбэки ниже в сумме могут висеть минуты
            //    на таймаутах недоступных сетей, поэтому при наличии скриншотов они не
            //    запускаются вовсе (и не подмешивают обложки). Транслит-запрос не нужен:
            //    DLE ищет по тексту статьи (русскому), а не по слагам.
            val hentaizScreens = runCatching {
                findHentaizScreens(queries)
            }.onFailure { KLog.w(TAG, "hentaiz screens step failed: ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            if (hentaizScreens.isNotEmpty()) {
                hentaizScreens.forEach(::addFrame)
                val result = frames.take(FRAMES_LIMIT)
                KLog.i(TAG, "hentaiFrames [${queries.firstOrNull()}] hz=${result.size} -> fast path")
                framesCache[cacheKey] = result
                return@withContext result
            }

            // Каталог нужен фолбэк-шагам: videoId hanime1 и арт.
            val catalog: List<CatalogEntry> = runCatching { loadCatalog() }.getOrDefault(emptyList())
            if (catalog.isEmpty()) KLog.w(TAG, "hentaiFrames: hanime catalog unavailable")
            val entry = queries.firstNotNullOfOrNull { q ->
                catalog.firstOrNull { titleMatches(it.name, it.altTitles, q) }
            }

            // 1. Кадры из первой серии стабильного DLE-источника (hentaidream → allhentai):
            //    у тайтлов без блока СКРИНШОТЫ это единственный источник настоящих кадров.
            //    Готовые файлы кэша переиспользуются без сети.
            val videoFrames = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(VIDEO_FRAMES_TIMEOUT_MS) {
                    extractVideoFrames(cacheKey, queries)
                }
            }.onFailure { KLog.w(TAG, "video frames step failed: ${it.javaClass.simpleName}") }
                .getOrNull()
                .orEmpty()
            videoFrames.forEach { frame ->
                if (frames.none { it.imageUrl == frame.imageUrl }) frames.add(frame)
            }
            if (frames.isNotEmpty()) {
                val result = frames.take(FRAMES_LIMIT)
                KLog.i(TAG, "hentaiFrames [${queries.firstOrNull()}] vf=${result.size} -> video frames")
                framesCache[cacheKey] = result
                return@withContext result
            }

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
            KLog.i(
                TAG,
                "hentaiFrames [${queries.firstOrNull()}] h1=$h1Count art=${frames.size - beforeArt} " +
                    "-> ${result.size} imgs" + (entry?.let { " (catalog: \"${it.name}\")" } ?: "")
            )
            // Пустой результат не кэшируем: разовый сбой сети не должен убить «Кадры» до рестарта.
            if (result.isNotEmpty()) framesCache[cacheKey] = result
            result
        }

    /**
     * Кадры из первой серии стабильного DLE-источника. Файлы складываются в
     * cacheDir/hentai_frames/<ключ>-N.jpg и живут до очистки кэша системой — повторное
     * открытие тайтла не трогает сеть. Декодирование видео платформенно-зависимо
     * ([grabVideoFrameFiles]): на Android — MediaMetadataRetriever по ключевым кадрам,
     * на desktop кадров из видео нет (остаются скриншоты страниц и обложки каталога).
     */
    private suspend fun extractVideoFrames(
        cacheKey: String,
        queries: List<String>
    ): List<FilmImageItem> = withContext(Dispatchers.IO) {
        val dir = cacheDir?.let { java.io.File(it, "hentai_frames") }?.apply { mkdirs() }
            ?: return@withContext emptyList()
        val prefix = cacheKeySha(cacheKey) + "-"

        // Только hentaidream: движок и каталог у него общие с allhentai, а allhentai на
        // части РФ-сетей блокирован — его поисковые таймауты (2×30с на запрос) подвешивали
        // шаг кадров на минуты после обычного промаха матчинга. Для ПОТОКА фолбэк остаётся.
        val stream = resolveViaHentaiDream(queries) ?: run {
            KLog.i(TAG, "video frames: no hentaidream stream for $queries")
            return@withContext emptyList()
        }

        val written = grabVideoFrameFiles(stream, dir, prefix).filterNotNull()
        KLog.i(TAG, "video frames [${stream.url.takeLast(48)}] -> ${written.size} imgs")
        written.map(::fileFrame)
    }

    private fun fileFrame(file: java.io.File) = FilmImageItem(
        imageUrl = file.toURI().toString(),
        previewUrl = file.toURI().toString()
    )

    private fun cacheKeySha(cacheKey: String): String =
        MessageDigest.getInstance("MD5").digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    /**
     * Кадры из блока «СКРИНШОТЫ» hentaiz: на странице тайтла галерея data-fancybox с
     * /uploads/posts/<date>/screen_<id>.jpg (у постеров соседних тайтлов префикс poster_ —
     * их не берём). Поисковые запросы гоняются параллельно (последовательный перебор стоил
     * ~2с на каждый промах), приоритет — исходный порядок запросов.
     */
    private suspend fun findHentaizScreens(queries: List<String>): List<String> =
        kotlinx.coroutines.coroutineScope {
            val pages = queries.map { query ->
                async(Dispatchers.IO) {
                    query to runCatching { findHentaizPage(query)?.second }.getOrNull()
                }
            }.awaitAll()
            for ((query, page) in pages) {
                if (page == null) continue
                val urls = Regex("""(?:href|data-src)="([^"]*/uploads/posts/[^"/]+/screen_[^"]+\.(?:jpe?g|png|webp)[^"]*)""")
                    .findAll(page)
                    .map { m ->
                        val raw = unescapeHtml(m.groupValues[1]).trim()
                        if (raw.startsWith("http")) raw else HZ_BASE + raw
                    }
                    .distinct()
                    .toList()
                if (urls.isNotEmpty()) {
                    KLog.i(TAG, "hentaiz screens (query=\"$query\") -> ${urls.size} imgs")
                    return@coroutineScope urls
                }
            }
            KLog.w(TAG, "hentaiz screens: no article with СКРИНШОТЫ matched, attempts=$queries")
            emptyList()
        }

    // ============================== hanime.tv ==============================

    /**
     * Каталог hanime (4.3 МБ JSON) нужен тегам/трейлеру/кадрам одновременно: без блокировки
     * холодное открытие страницы 18+ качало его трижды параллельно. synchronized на IO-потоках
     * — осознанно: ждущие корутины получают ОДИН результат; fetch монополизирован.
     * Свежая копия хранится на диске (mtime = время загрузки) — рестарт приложения не
     * перекачивает каталог, а сетевой сбой откатывается к устаревшей дисковой копии.
     */
    private fun loadCatalog(): List<CatalogEntry> {
        catalogCache?.let { cached ->
            if (System.currentTimeMillis() - catalogFetchedAtMs < CATALOG_TTL_MS) return cached
        }
        synchronized(this) {
            catalogCache?.let { cached ->
                if (System.currentTimeMillis() - catalogFetchedAtMs < CATALOG_TTL_MS) return cached
            }
            val diskFile = cacheDir?.let { java.io.File(it, CATALOG_DISK_FILE) }
            val diskFresh = diskFile?.takeIf { it.isFile }?.let { file ->
                System.currentTimeMillis() - file.lastModified() < CATALOG_TTL_MS
            } ?: false
            if (diskFile != null && diskFresh && readCatalogDisk(diskFile).also { parsed ->
                    if (parsed.isNotEmpty()) {
                        catalogCache = parsed
                        catalogFetchedAtMs = System.currentTimeMillis()
                        rebuildCatalogIndex(parsed)
                    }
                }.isNotEmpty()) {
                KLog.i(TAG, "catalog: fresh disk copy loaded (${catalogCache?.size ?: 0} entries)")
                return catalogCache.orEmpty()
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
                        KLog.i(TAG, "catalog http ${response.code}")
                        return@runCatching emptyList<CatalogEntry>()
                    }
                    val body = response.body.string()

                    if (diskFile != null) writeCatalogDisk(diskFile, body)
                    parseCatalogBody(body)
                }
            }.onFailure { KLog.w(TAG, "catalog fetch failed: ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            if (parsed.isNotEmpty()) {
                catalogCache = parsed
                catalogFetchedAtMs = System.currentTimeMillis()
                rebuildCatalogIndex(parsed)
                return parsed
            }
            // Сеть не отдала каталог — устаревшая дисковая копия лучше пустоты (теги/кадры живы).
            if (diskFile != null && diskFile.isFile) {
                readCatalogDisk(diskFile).takeIf { it.isNotEmpty() }?.let { stale ->
                    catalogCache = stale
                    rebuildCatalogIndex(stale)
                    KLog.w(TAG, "catalog: network failed, using stale disk copy (${stale.size} entries)")
                    return stale
                }
            }
            return emptyList()
        }
    }

    /** Сохраняет/читает сырой JSON каталога; запись отдельной функцией (см. writeCatalogDisk). */
    private fun readCatalogDisk(file: java.io.File): List<CatalogEntry> = runCatching {
        parseCatalogBody(file.readText())
    }.onFailure { KLog.w(TAG, "catalog disk read failed: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyList())

    private fun parseCatalogBody(body: String): List<CatalogEntry> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return buildList {
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

    private fun writeCatalogDisk(file: java.io.File, body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.writeText(body)
                tmp.delete()
            }
        }.onFailure { KLog.w(TAG, "catalog disk write failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Search aliases that are safe to query: the full canonical name (romaji) plus CJK runs
     * from alt titles. Single latin words ("Toki", "Time", "Ep") are poison — they match
     * unrelated videos — so fragments are never queried on their own.
     */
    private fun aliasQueries(entry: CatalogEntry): List<String> {
        val aliases = linkedSetOf<String>()
        if (entry.name.length >= 4) aliases.add(entry.name)
        val runs = Regex("[\\p{IsHan}\\p{InHiragana}\\p{InKatakana}]{2,}").findAll(entry.altTitles)
            .map { it.value }.toList()
        // hanime1 indexes titles without word separators; a spaced alt title ("はつこい たいむ")
        // fragments into short per-word runs that over-match unrelated shows ("たいむ" = "time"),
        // so the glued full form goes first and per-word runs stay as fallbacks.
        if (runs.size > 1) {
            val glued = runs.joinToString("")
            if (glued.length >= 4) aliases.add(glued)
        }
        aliases.addAll(runs)
        return aliases.toList().take(6)
    }

    /** Normalized title view of a search hit, keyed for picking. */
    internal data class CandidateView(val key: String, val title: String)

    /**
     * Scores every hit and returns the key of the best one (null when nothing is credible).
     *
     * Ranking guards against the failure mode "first result wins": exact equality > prefix with
     * a small episode number ("Bible Black 1" beats "Bible Black 5" and any spinoff) > weak
     * containment. Containment additionally requires the wanted phrase to be long enough
     * (≥6 latin chars or ≥3 CJK chars) so generic words can never produce a match.
     *
     * internal: тот же скоринг матчит статьи на AniStar (AniStarResolver).
     */
    internal fun pickBest(items: List<CandidateView>, query: String): String? {
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
        // Ромадзи-тире: Shikimori «Oneechan» против каталога «Onee-chan» — после нормализации
        // это одно слово против двух, фразовые проверки выше рвутся. Сравниваем без пробелов.
        val solidWanted = wanted.replace(" ", "")
        if (solidWanted.length >= minContain && candidate.replace(" ", "").contains(solidWanted)) return 50
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
                        return response.body.string()
                    }
                    KLog.w(TAG, "GET $url -> ${response.code} (attempt ${attempt + 1})")
                    if (response.code >= 500 && attempt == 0) return@repeat // retry 5xx
                    return null
                }
            } catch (e: java.io.IOException) {
                KLog.w(TAG, "GET $url attempt ${attempt + 1} failed: ${e.javaClass.simpleName}")
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
        "Русская озвучка/сабы, нативные MP4",
        "RU", true
    ),
    HENTAIDREAM(
        "HentaiDream",
        "Русская озвучка/сабы, нативные MP4, без VPN",
        "RU", false
    ),
    ANISTAR(
        "AniStar",
        "Русская озвучка AniStar, MP4/HLS до 720p, без VPN",
        "RU", false
    ),
    HENTAIZ(
        "HentaiZ",
        "Оригинал и озвучки, нативные MP4, без VPN; источники кадров",
        "RU", false
    ),
    HANIME1(
        "Hanime1.me",
        "Оригинал с японскими титрами, потоки 480–1080p",
        "JA", false
    ),
    SMARTHARD(
        "Smarthard",
        "Архив shikicinema: сабы и озвучки; часть ссылок требует VPN",
        "RU", true
    ),
    OPPAI(
        "Oppai.Stream",
        "Английский сайт 4K-мастеров, MP4 720/1080p",
        "JA", true
    );
}

/** Источник строки хентая для шита озвучек плеера: реальное имя провайдера, а не «Kodik». */
fun HentaiProvider.toAnimeSourceType(): hd.kinoshka.app.data.model.AnimeSourceType =
    when (this) {
        HentaiProvider.ALLHENTAI -> hd.kinoshka.app.data.model.AnimeSourceType.HENTAI_ALLHENTAI
        HentaiProvider.HENTAIDREAM -> hd.kinoshka.app.data.model.AnimeSourceType.HENTAI_HENTAIDREAM
        HentaiProvider.ANISTAR -> hd.kinoshka.app.data.model.AnimeSourceType.ANISTAR
        HentaiProvider.HENTAIZ -> hd.kinoshka.app.data.model.AnimeSourceType.HENTAI_HENTAIZ
        HentaiProvider.HANIME1 -> hd.kinoshka.app.data.model.AnimeSourceType.HENTAI_HANIME1
        HentaiProvider.SMARTHARD -> hd.kinoshka.app.data.model.AnimeSourceType.SMARTHARD
        HentaiProvider.OPPAI -> hd.kinoshka.app.data.model.AnimeSourceType.HENTAI_OPPAI
    }

