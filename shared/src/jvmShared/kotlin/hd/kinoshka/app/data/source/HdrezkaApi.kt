package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.DdbbEpisodeTrack
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.util.log.KLog
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Нативный источник HDRezka (rezka.ag): поиск по названию, переводы, сезоны/серии
 * и прямые HLS/MP4-потоки под mpvEx. Тот же Rezka-бэкенд, что отдаёт Voidboost-эмбеды,
 * но через официальный сайт вместо мёртвых зеркал voidboost.net.
 *
 * Титульные страницы прикрыты Anubis Proof-of-Work (challenge + SHA-256 nonce,
 * difficulty 2): [fetchHtml] решает его прозрачно и держит куку в [cookieJar].
 * Потоки шифрованы тем же rezka-алгоритмом, что voidboost ([WebmasterStreamSources]:
 * `#h`-метки, `//_//`-разделители, мусорные base64-сегменты, 2-символьный сдвиг) —
 * декодер переиспользуется, а не дублируется.
 *
 * Возвращает [DdbbStreamResolver.SourceParse]: пикер и мердж каталогов видят HDRezka
 * как обычный прямой источник (озвучки — voiceRows, серии — tracks).
 */
object HdrezkaApi {
    private const val TAG = "HdrezkaApi"

    private val BASES = listOf("https://rezka.ag")

    /** Озвучек фильма резолвится не больше: дальше — лишние POST без пользы для старта. */
    private const val MAX_VOICES = 12

    /** Серий резолвится не больше (первый перевод × сезоны по порядку). */
    private const val MAX_EPISODES = 60

    /**
     * Параллельных ajax не больше: пачка из 60 одновременных POST уходит в HTTP 503
     * (проверено живьём на Breaking Bad) — то же троттлится и у людей без VPN.
     */
    private val ajaxSemaphore = kotlinx.coroutines.sync.Semaphore(4)

    /** Маркер качества "[1080p]" — признак готового (не закодированного) списка потоков. */
    private val QUALITY_MARKER_REGEX = Regex("""\[\d{3,4}p\]""")

    /** Best-first rung order, mirroring DdbbStreamResolver/Webmaster ladders. */
    private val LADDER_PREFERENCE = listOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "240p")

    private val cookieJar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (cookies.isEmpty()) return
            val jar = store.computeIfAbsent(url.host) { LinkedHashMap() }
            synchronized(jar) {
                // Замена по имени (как RFC-jar): дубли одного имени заставляют сервер
                // брать протухшее значение — Anubis резал такие проходки через раз.
                cookies.forEach { jar[it.name] = it }
                while (jar.size > 40) jar.remove(jar.keys.first())
            }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store[url.host]?.let { synchronized(it) { it.values.toList() } }.orEmpty()
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

    data class SearchHit(
        val title: String,
        val url: String,
        val year: Int?,
        val kind: MovieContentKind
    )

    data class Translator(val id: String, val name: String)

    data class EpisodeRef(val season: Int, val number: Int, val title: String?)

    data class TitlePage(
        val url: String,
        val postId: String,
        val translators: List<Translator>,
        val defaultTranslatorId: String?,
        val seasons: List<Int>,
        val episodes: Map<Int, List<EpisodeRef>>
    )

    /** Anubis PoW-challenge титульной страницы. Public for unit tests. */
    data class AnubisChallenge(
        val id: String,
        val randomData: String,
        val difficulty: Int,
        val basePrefix: String
    )

    // --- Entry point ------------------------------------------------------------------

    /**
     * Полный резолв тайтла: поиск по названиям запроса → сверка → переводы/серии →
     * потоки. Null, когда ничего не нашлось или всё упало (пикер покажет Empty).
     */
    suspend fun resolve(request: MoviePlaybackRequest): DdbbStreamResolver.SourceParse? =
        withContext(Dispatchers.IO) {
            val titles = request.titles.map { KodikMovieParser.normalizeTitle(it) }
                .filter { it.isNotBlank() }.distinct().take(3)
            if (titles.isEmpty()) return@withContext null
            for (base in BASES) {
                // Названия ищем параллельно: последовательные 3×(поиск+таймауты)
                // на мёртвой сети давали минуту только на этот шаг.
                val searched: List<Pair<String, List<SearchHit>?>> = coroutineScope {
                    titles.map { title -> async { title to search(base, title) } }.awaitAll()
                }
                for ((title, hits) in searched) {
                    // null = хост не отвечает: перебирать названия на мёртвом хосте
                    // бессмысленно (на телефоне без VPN это 3×connect-timeout).
                    if (hits == null) break
                    KLog.i(TAG, "search '$title': ${hits.size} hits")
                    val hit = hits.firstOrNull { matchHit(request, titles.toSet(), it) }
                    if (hit == null) {
                        KLog.i(TAG, "no identity match among ${hits.size} hits")
                        continue
                    }
                    KLog.i(TAG, "matched ${hit.url}")
                    val page = loadTitle(base, hit.url)
                    if (page == null) {
                        KLog.w(TAG, "title page failed: ${hit.url}")
                        continue
                    }
                    KLog.i(TAG, "title post=${page.postId} translators=${page.translators.size} seasons=${page.seasons}")
                    if (page.translators.isEmpty()) continue
                    val parse = if (hit.kind == MovieContentKind.SERIES || page.seasons.isNotEmpty()) {
                        resolveSeries(base, page)
                    } else {
                        resolveMovie(base, page)
                    }
                    if (parse != null) return@withContext parse
                    KLog.w(TAG, "streams failed for post=${page.postId}")
                }
            }
            KLog.w(TAG, "resolve: nothing found for kp=${request.kinopoiskId} titles=${request.titles}")
            null
        }

    /** Поиск тайтлов по названию (первая страница выдачи). Public for health check. */
    suspend fun searchTitles(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        for (base in BASES) {
            val hits = search(base, query)
            if (!hits.isNullOrEmpty()) return@withContext hits
        }
        emptyList()
    }

    // --- Search -----------------------------------------------------------------------

    private suspend fun search(base: String, query: String): List<SearchHit>? {
        val url = "$base/search/?do=search&subaction=search&q=${enc(query)}"
        // null = сеть/хост недоступен (отличаем от пустой выдачи, чтобы не долбить мёртвый хост).
        val html = fetchHtml(url, "$base/") ?: run {
            KLog.w(TAG, "search failed for '$query'")
            return null
        }
        return parseSearchHits(base, html)
    }

    /** Public for unit tests. */
    fun parseSearchHits(base: String, html: String): List<SearchHit> {
        val flat = html.replace("\n", " ")
        val out = LinkedHashMap<String, SearchHit>()
        // Карточки выдачи: <div class="b-content__inline_item" data-url="...">…<div
        // class="b-content__inline_item-link"><a>Название</a><div>1999, США, …</div>.
        // Якорь осознанно узкий: общий поиск ссылок цепляет навигацию/жанры раньше выдачи.
        val cardRegex =
            Regex("""b-content__inline_item"[^>]*data-url="([^"]+)"(.*?)b-content__inline_item-link">\s*<a[^>]*>(.*?)</a>\s*<div>(.*?)</div>""")
        for (m in cardRegex.findAll(flat)) {
            var url = m.groupValues[1].trim()
            if (url.startsWith("/")) url = base.trimEnd('/') + url
            if (!url.startsWith("http") || out.containsKey(url)) continue
            if (!url.contains("/series/") && !url.contains("/films/")) continue
            val title = m.groupValues[3].replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ").trim().take(140)
            if (title.length < 2) continue
            val kind = when {
                "/series/" in url -> MovieContentKind.SERIES
                else -> MovieContentKind.MOVIE
            }
            val meta = m.groupValues[4]
            val year = Regex("""((?:19|20)\d{2})""").find(meta)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-((?:19|20)\d{2})[-.]""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            out[url] = SearchHit(title, url, year, kind)
            if (out.size >= 12) break
        }
        return out.values.toList()
    }

    private fun matchHit(
        request: MoviePlaybackRequest,
        expectedTitles: Set<String>,
        hit: SearchHit
    ): Boolean {
        if (KodikMovieParser.normalizeTitle(hit.title) !in expectedTitles) return false
        if (request.kind != MovieContentKind.UNKNOWN && hit.kind != MovieContentKind.UNKNOWN &&
            request.kind != hit.kind
        ) return false
        val year = request.year
        if (year != null && hit.year != null && abs(year - hit.year) > 1) return false
        return true
    }

    // --- Title page -------------------------------------------------------------------

    private suspend fun loadTitle(base: String, url: String): TitlePage? {
        val html = fetchHtml(url, "$base/") ?: return null
        val postId = parsePostId(html) ?: run {
            KLog.w(TAG, "no post id at $url")
            return null
        }
        val translators = parseTranslators(html)
        val seasons = parseSeasons(html)
        val episodes = seasons.associateWith { parseEpisodes(html, it) }
            .filterValues { it.isNotEmpty() }
        return TitlePage(url, postId, translators, parseDefaultTranslator(html), seasons, episodes)
    }

    /** Public for unit tests. */
    fun parseTranslators(html: String): List<Translator> {
        val flat = html.replace("\n", " ")
        return Regex("""data-translator_id="(\d+)"[^>]*>([^<]{1,80})<""")
            .findAll(flat)
            .map { Translator(it.groupValues[1], it.groupValues[2].trim().replace(Regex("\\s+"), " ")) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.id }
            .toList()
    }

    /** Public for unit tests. */
    fun parsePostId(html: String): String? =
        Regex("""data-post_id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""initCDN(?:Series|Movies)Events\((\d+)""").find(html)?.groupValues?.get(1)

    /** Public for unit tests. */
    fun parseDefaultTranslator(html: String): String? =
        Regex("""initCDN(?:Series|Movies)Events\(\d+,\s*(\d+)""").find(html)?.groupValues?.get(1)

    /** Public for unit tests. */
    fun parseSeasons(html: String): List<Int> {
        val block = Regex("""simple-seasons-tabs[^>]*>(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""data-tab_id="(\d+)"""").findAll(block)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sorted().toList()
    }

    /** Public for unit tests. */
    fun parseEpisodes(html: String, season: Int): List<EpisodeRef> {
        val block = Regex(
            """id="simple-episodes-list-$season"[^>]*>(.*?)</ul>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""data-episode_id="(\d+)"[^>]*>([^<]{0,120})<""").findAll(block)
            .mapNotNull { m ->
                val num = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val title = m.groupValues[2].trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
                EpisodeRef(season, num, title)
            }
            .distinctBy { it.number }.sortedBy { it.number }.toList()
    }

    // --- Streams ----------------------------------------------------------------------

    private suspend fun resolveMovie(base: String, page: TitlePage): DdbbStreamResolver.SourceParse? =
        withContext(Dispatchers.IO) {
            val headers = mapOf("Referer" to "$base/", "User-Agent" to USER_AGENT)
            val voices = page.translators.take(MAX_VOICES)
            val results = coroutineScope {
                voices.map { tr ->
                    async {
                        val ladder = movieLadder(base, page, tr.id) ?: return@async null
                        Triple(tr, ladder.first, ladder.second)
                    }
                }.awaitAll()
            }.filterNotNull()
            if (results.isEmpty()) return@withContext null
            val voiceRows = LinkedHashMap<String, String>()
            val ladders = LinkedHashMap<String, Map<String, String>>()
            for ((tr, url, ladder) in results) {
                ladders.putIfAbsent(url, ladder)
                voiceRows.putIfAbsent(tr.name.ifBlank { "Озвучка" }, url)
            }
            val defaultUrl = voiceRows.values.first()
            KLog.i(TAG, "movie: ${voiceRows.size} dubs for post=${page.postId}")
            DdbbStreamResolver.SourceParse(
                sourceName = "HDRezka",
                url = defaultUrl,
                headers = headers,
                qualities = ladders[defaultUrl] ?: mapOf("Auto" to defaultUrl),
                voiceRows = voiceRows.map { it.key to it.value },
                ladders = ladders
            )
        }

    private suspend fun resolveSeries(base: String, page: TitlePage): DdbbStreamResolver.SourceParse? =
        withContext(Dispatchers.IO) {
            val headers = mapOf("Referer" to "$base/", "User-Agent" to USER_AGENT)
            val translator = page.translators.firstOrNull { it.id == page.defaultTranslatorId }
                ?: page.translators.firstOrNull() ?: return@withContext null
            val wanted = page.seasons.sorted().flatMap { season ->
                (page.episodes[season].orEmpty()).map { season to it }
            }.take(MAX_EPISODES)
            if (wanted.isEmpty()) return@withContext null
            val results = coroutineScope {
                wanted.map { (season, ep) ->
                    async {
                        val ladder = seriesLadder(base, page, translator.id, season, ep.number)
                            ?: return@async null
                        Triple(ep, ladder.first, ladder.second)
                    }
                }.awaitAll()
            }.filterNotNull()
            if (results.isEmpty()) return@withContext null
            val tracks = results.map { (ep, url, _) ->
                DdbbEpisodeTrack(
                    dubId = "hdrezka",
                    dubTitle = translator.name.ifBlank { "HDRezka" },
                    seasonNumber = ep.season,
                    episodeNumber = ep.number,
                    title = ep.title,
                    playerUrl = url
                )
            }
            val ladders = LinkedHashMap<String, Map<String, String>>()
            for ((_, url, ladder) in results) ladders.putIfAbsent(url, ladder)
            val defaultUrl = tracks.first().playerUrl
            KLog.i(TAG, "series: ${tracks.size} tracks (${translator.name}) for post=${page.postId}")
            // Пикер несёт ladders/headers внутри SourceParse — в общий ddbb-кэш не пишем,
            // чтобы не затирать чужой каталог того же kp (last-writer-wins там без версий).
            DdbbStreamResolver.SourceParse(
                sourceName = "HDRezka",
                url = defaultUrl,
                headers = headers,
                qualities = ladders[defaultUrl] ?: mapOf("Auto" to defaultUrl),
                tracks = tracks,
                ladders = ladders
            )
        }

    private suspend fun movieLadder(
        base: String,
        page: TitlePage,
        translatorId: String
    ): Pair<String, Map<String, String>>? {
        val raw = ajax(
            base, page.url, mapOf(
                "id" to page.postId,
                "translator_id" to translatorId,
                "action" to "get_movie"
            )
        )?.optString("url").orEmpty()
        return ladderFromUrl(raw)
    }

    private suspend fun seriesLadder(
        base: String,
        page: TitlePage,
        translatorId: String,
        season: Int,
        episode: Int
    ): Pair<String, Map<String, String>>? {
        val raw = ajax(
            base, page.url, mapOf(
                "id" to page.postId,
                "translator_id" to translatorId,
                "season" to season.toString(),
                "episode" to episode.toString(),
                "action" to "get_stream"
            )
        )?.optString("url").orEmpty()
        return ladderFromUrl(raw)
    }

    /**
     * Лестница качеств из ajax-ответа: готовый "[1080p]url,…" парсится напрямую,
     * закодированный file-blob — через rezka-декодер. Public for unit tests.
     */
    fun ladderFromUrl(raw: String): Pair<String, Map<String, String>>? {
        // "false" — штатный ответ ajax без потоков (гео-фильтр): тихо пропускаем.
        if (raw.isBlank() || raw.trim() == "false") return null
        // 1) Готовый список "[1080p]url, …" из ajax (декодировать нечего).
        if (QUALITY_MARKER_REGEX.containsMatchIn(raw)) {
            val ladder = WebmasterStreamSources.parseVoidboostQualityChunks(raw)
            if (ladder.isNotEmpty()) {
                val best = bestOfLadder(ladder)!!
                return best.second to ladder
            }
        }
        // 2) Закодированный file-blob (тот же rezka-алгоритм, что у voidboost).
        val decoded = WebmasterStreamSources.decodeVoidboostFile(raw)
        if (decoded.isNotBlank()) {
            val ladder = WebmasterStreamSources.parseVoidboostQualityChunks(decoded)
            if (ladder.isNotEmpty()) {
                val best = bestOfLadder(ladder)!!
                return best.second to ladder
            }
        }
        // 3) Незакодированная прямая ссылка (запасной формат).
        val direct = raw.trim().substringBefore(",").substringBefore(" ").trim()
        if (direct.startsWith("http")) return direct to mapOf("Auto" to direct)
        KLog.w(TAG, "unparsed stream url: ${raw.take(120)}")
        return null
    }

    private fun bestOfLadder(ladder: Map<String, String>): Pair<String, String>? =
        ladder.entries.minByOrNull { (quality, _) ->
            LADDER_PREFERENCE.indexOf(quality).let { if (it < 0) Int.MAX_VALUE else it }
        }?.let { it.key to it.value }

    private enum class AjaxFailure { ANUBIS, THROTTLE, OTHER }

    private suspend fun ajax(
        base: String,
        referer: String,
        params: Map<String, String>
    ): org.json.JSONObject? = withContext(Dispatchers.IO) {
        ajaxSemaphore.withPermit {
            val (first, failure) = ajaxPost(base, referer, params)
            if (first != null) return@withPermit first
            when (failure) {
                // Протухшая сессия: чиним куку и повторяем один раз.
                AjaxFailure.ANUBIS -> {
                    KLog.i(TAG, "ajax ${params["action"]} retrying after session refresh")
                    fetchHtml(referer, base)
                    kotlinx.coroutines.delay(1000)
                    ajaxPost(base, referer, params).first
                }
                // Троттлинг: не долбим, одна пауза и один повтор без лишней нагрузки.
                AjaxFailure.THROTTLE -> {
                    kotlinx.coroutines.delay(3000)
                    ajaxPost(base, referer, params).first
                }
                else -> null
            }
        }
    }

    private suspend fun ajaxPost(
        base: String,
        referer: String,
        params: Map<String, String>
    ): Pair<org.json.JSONObject?, AjaxFailure?> = withContext(Dispatchers.IO) {
        runCatching {
            val form = FormBody.Builder().apply {
                params.forEach { (k, v) -> add(k, v) }
            }.build()
            val req = Request.Builder()
                .url("$base/ajax/get_cdn_series/")
                .post(form)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", referer)
                .build()
            httpClient.newCall(req).execute().use { response ->
                val body = response.body.string()
                if (response.code == 503) {
                    KLog.w(TAG, "ajax ${params["action"]} throttled (503)")
                    return@runCatching null to AjaxFailure.THROTTLE
                }
                if (!response.isSuccessful) {
                    KLog.w(TAG, "ajax ${params["action"]} -> HTTP ${response.code}: ${body.take(200)}")
                    return@runCatching null to AjaxFailure.OTHER
                }
                if (isAnubisChallenge(body)) {
                    KLog.w(TAG, "ajax ${params["action"]} hit anubis")
                    return@runCatching null to AjaxFailure.ANUBIS
                }
                val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: run {
                    KLog.w(TAG, "ajax ${params["action"]} non-JSON: ${body.take(120)}")
                    return@runCatching null to AjaxFailure.ANUBIS
                }
                if (!root.optBoolean("success")) {
                    KLog.w(TAG, "ajax ${params["action"]} success=false: ${root.optString("message").take(120)}")
                    return@runCatching null to AjaxFailure.OTHER
                }
                root to null
            }
        }.onFailure {
            KLog.w(TAG, "ajax ${params["action"]} failed: ${it.javaClass.simpleName}")
        }.getOrNull() ?: (null to AjaxFailure.OTHER)
    }

    // --- HTTP + Anubis ----------------------------------------------------------------

    private suspend fun fetchHtml(url: String, referer: String?, attempts: Int = 3): String? =
        withContext(Dispatchers.IO) {
            repeat(attempts) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(1500)
                var html = get(url, referer)
                if (html != null && isAnubisChallenge(html)) {
                    KLog.i(TAG, "anubis challenge at ${hostOf(url)}, solving (attempt ${attempt + 1})…")
                    html = solveAnubis(url, html, referer)
                }
                if (html != null && !isAnubisChallenge(html)) return@withContext html
            }
            KLog.w(TAG, "fetch failed after $attempts attempts: $url")
            null
        }

    private fun get(url: String, referer: String?): String? {
        val host = hostOf(url)
        if (host.isNotEmpty() && HostCooldown.shouldSkip(host)) {
            KLog.i(TAG, "fetch $host skipped (cooldown)")
            return null
        }
        return runCatching {
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .apply { referer?.let { addHeader("Referer", it) } }
                .build()
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "fetch $host: HTTP ${response.code}")
                    return null
                }
                response.body.string().takeIf { it.isNotEmpty() }?.also {
                    if (host.isNotEmpty()) HostCooldown.recordSuccess(host)
                }
            }
        }.onFailure {
            KLog.w(TAG, "fetch $host: ${it.javaClass.simpleName}")
            if (host.isNotEmpty() && HostCooldown.isConnectivityFailure(it)) HostCooldown.recordFailure(host)
        }.getOrNull()
    }

    /** Public for unit tests. */
    fun isAnubisChallenge(html: String): Boolean = html.contains("anubis_challenge")

    /** Public for unit tests. */
    fun parseAnubisChallenge(html: String): AnubisChallenge? = runCatching {
        val json = Regex("""<script id="anubis_challenge"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val root = org.json.JSONObject(json)
        val rules = root.optJSONObject("rules") ?: return null
        val challenge = root.optJSONObject("challenge") ?: return null
        val basePrefix = Regex("""<script id="anubis_base_prefix"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.trim('"').orEmpty()
        AnubisChallenge(
            id = challenge.optString("id").takeIf { it.isNotEmpty() } ?: return null,
            randomData = challenge.optString("randomData").takeIf { it.isNotEmpty() } ?: return null,
            difficulty = rules.optInt("difficulty").takeIf { it in 1..16 } ?: return null,
            basePrefix = basePrefix
        )
    }.getOrNull()

    private val redirectlessClient: OkHttpClient by lazy {
        httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    private suspend fun solveAnubis(pageUrl: String, html: String, referer: String?): String? =
        withContext(Dispatchers.IO) {
            val challenge = parseAnubisChallenge(html) ?: return@withContext null
            if (challenge.difficulty > 6) {
                KLog.w(TAG, "anubis difficulty ${challenge.difficulty} too high, giving up")
                return@withContext null
            }
            val started = System.currentTimeMillis()
            val solved = solveAnubisNonce(challenge.randomData, challenge.difficulty)
                ?: return@withContext null
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            val base = BASES.firstOrNull { pageUrl.startsWith(it) } ?: return@withContext null
            val passUrl = anubisPassUrl(
                base, challenge.basePrefix, challenge.id,
                solved.second, solved.first, pageUrl, elapsed
            )
            KLog.i(TAG, "anubis solved nonce=${solved.first} in ${elapsed}ms")
            // pass-challenge вручную (без авто-редиректа): виден код, Location и куки.
            // Ответ на Location уже идёт со свежими куками — его и проверяем, лишний
            // запрос не тратим (каждый лишний GET — новая лотерея челленджа).
            val target = runCatching {
                val req = Request.Builder().url(passUrl)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Referer", pageUrl)
                    .build()
                redirectlessClient.newCall(req).execute().use { response ->
                    val location = response.header("Location")
                    val setCookies = response.headers("Set-Cookie")
                    KLog.i(TAG, "anubis pass -> HTTP ${response.code} location=${location?.take(120)} setCookies=${setCookies.size}")
                    if (response.code !in 300..399 || location.isNullOrEmpty()) {
                        KLog.w(TAG, "anubis pass failed: ${response.body.string().take(200)}")
                        return@runCatching null
                    }
                    location
                }
            }.getOrNull() ?: return@withContext null
            val fresh = get(target, pageUrl)
            if (fresh != null && !isAnubisChallenge(fresh)) return@withContext fresh
            KLog.w(TAG, "anubis still challenging after pass")
            null
        }

    /**
     * SHA-256 Proof-of-Work Anubis (алгоритм "fast": ведущие нулевые полубайты —
     * `difficulty`, см. sha256-webcrypto.mjs v1.25.0). Возвращает (nonce, hashHex).
     * Public for unit tests.
     */
    fun solveAnubisNonce(randomData: String, difficulty: Int, maxAttempts: Int = 2_000_000): Pair<Long, String>? {
        val fullZeroBytes = difficulty / 2
        val needsHalfNibble = difficulty % 2 == 1
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (nonce < maxAttempts) {
            digest.reset()
            val hash = digest.digest((randomData + nonce.toString()).toByteArray(Charsets.UTF_8))
            var ok = true
            for (i in 0 until fullZeroBytes) {
                if (hash[i] != 0.toByte()) {
                    ok = false
                    break
                }
            }
            if (ok && needsHalfNibble && (hash[fullZeroBytes].toInt() ushr 4) != 0) ok = false
            if (ok) {
                // toInt() and 0xFF обязателен: "%02x".format(signedByte) даёт "ffffffab"
                // вместо "ab" — сервер сверяет response побайтово и режет такое доказательство.
                return nonce to hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }
            nonce++
        }
        return null
    }

    /** Public for unit tests. */
    fun anubisPassUrl(
        base: String,
        basePrefix: String,
        id: String,
        hashHex: String,
        nonce: Long,
        redir: String,
        elapsedMs: Long
    ): String {
        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
        return "${base.trimEnd('/')}$basePrefix/.within.website/x/cmd/anubis/api/pass-challenge" +
            "?id=${enc(id)}&response=${enc(hashHex)}&nonce=$nonce" +
            "&redir=${enc(redir)}&elapsedTime=$elapsedMs"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: url.take(60)
}
