package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.local.KinoPrefs
import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Поиск раздач Rutracker для шита «Торренты» на странице тайтла.
 *
 * Rutracker (в отличие от Rutor) требует логин: без куки `bb_session`
 * `tracker.php` отдаёт форму входа. Поэтому здесь же живут login/logout и
 * персистентность сессии ([attachPrefs] — один раз на старте процесса).
 *
 * Отдача во внешний клиент — magnet-ссылками: прямая `dl.php?t=` требует
 * куки авторизации, которой у внешнего торрент-клиента нет, а magnet работает
 * без неё. Хэш для magnet достаётся со страницы топика (топ-N по сидам,
 * параллельно); если хэш не извлёкся — остаётся только `dl.php`-ссылка
 * (откроется в браузере, где пользователь залогинен).
 *
 * Страницы трекера в кодировке windows-1251 — OkHttp декодирует их по
 * charset из Content-Type, а поисковый запрос кодируется в windows-1251,
 * как это делает сама HTML-форма трекера.
 */
object RutrackerResolver {
    private const val TAG = "RutrackerResolver"
    const val SOURCE_NAME = "Rutracker"

    val MIRRORS = listOf(
        "https://rutracker.org",
        "https://rutracker.net",
        "https://rutracker.nl"
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val SEARCH_TTL_MS = 10 * 60 * 1000L
    private const val MAX_RESULTS = 30
    private const val MAGNET_ENRICH_TOP = 10
    private const val MAGNET_CONCURRENCY = 4

    private const val PREFS_USERNAME = "rutracker_username"
    private const val PREFS_COOKIES = "rutracker_cookies"

    // ------------------------------------------------------------------
    // Куки и сессия
    // ------------------------------------------------------------------

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val slot = cookieStore.getOrPut(url.host.lowercase()) { mutableListOf() }
            synchronized(slot) {
                cookies.forEach { fresh ->
                    slot.removeAll { it.name == fresh.name }
                    slot.add(fresh)
                }
            }
            persistSession()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val host = url.host.lowercase()
            return cookieStore.values.flatMap { slot -> synchronized(slot) { slot.toList() } }
                .filter { it.expiresAt > now && (it.matches(url) || isSharedRutrackerCookie(it, host)) }
        }
    }

    /**
     * Кука, выставленная одним зеркалом, подходит и остальным: домены разные
     * (org/net/nl), а сессия серверная. Строгий [Cookie.matches] такое режет,
     * поэтому для рутрекерных хостов матчим по вхождению.
     */
    private fun isSharedRutrackerCookie(cookie: Cookie, requestHost: String): Boolean =
        requestHost.contains("rutracker") && cookie.domain.contains("rutracker")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var prefs: KinoPrefs? = null
    @Volatile private var username: String? = null
    @Volatile private var lastWorkingMirror: String? = null

    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<AnimeStreamResolver.TorrentLink>>>()

    /** Привязка хранилища + восстановление сессии. Вызывать раз на старте процесса. */
    fun attachPrefs(prefs: KinoPrefs) {
        this.prefs = prefs
        username = prefs.getString(PREFS_USERNAME, null)?.takeIf { it.isNotBlank() }
        val raw = prefs.getString(PREFS_COOKIES, null) ?: return
        val restored = raw.lines()
            .mapNotNull { line -> restoreCookie(line) }
            .filter { it.expiresAt > System.currentTimeMillis() }
        if (restored.isEmpty()) return
        restored.groupBy { it.domain }.forEach { (domain, cookies) ->
            cookieStore.getOrPut(domain) { mutableListOf() }.addAll(cookies)
        }
        KLog.i(TAG, "session restored for ${username ?: "?"} (${restored.size} cookies)")
    }

    fun isLoggedIn(): Boolean = hasSessionCookie()

    fun savedUsername(): String? = username

    private fun hasSessionCookie(): Boolean {
        val now = System.currentTimeMillis()
        return cookieStore.values.any { slot ->
            synchronized(slot) {
                slot.any { it.name == "bb_session" && it.value.isNotBlank() && it.expiresAt > now }
            }
        }
    }

    private fun persistSession() {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        val serialized = cookieStore.values
            .flatMap { slot -> synchronized(slot) { slot.toList() } }
            .filter { it.expiresAt > now }
            .joinToString("\n") { "${it.name}=${it.value}; Domain=${it.domain}; Path=${it.path}" }
        if (serialized.isBlank()) {
            p.remove(PREFS_COOKIES)
        } else {
            p.putString(PREFS_COOKIES, serialized)
        }
        p.apply()
    }

    private fun restoreCookie(line: String): Cookie? = runCatching {
        val parts = line.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val nameValue = parts[0]
        val eq = nameValue.indexOf('=')
        if (eq <= 0) return null
        val domain = parts.firstNotNullOfOrNull { part ->
            part.split("=", limit = 2).takeIf { it.size == 2 && it[0].trim().equals("Domain", ignoreCase = true) }
                ?.let { it[1].trim().trimStart('.') }
        } ?: return null
        val path = parts.firstNotNullOfOrNull { part ->
            part.split("=", limit = 2).takeIf { it.size == 2 && it[0].trim().equals("Path", ignoreCase = true) }
                ?.let { it[1].trim() }
        } ?: "/"
        Cookie.Builder()
            .name(nameValue.substring(0, eq))
            .value(nameValue.substring(eq + 1))
            .domain(domain)
            .path(path)
            .build()
    }.getOrNull()

    // ------------------------------------------------------------------
    // Логин / логаут
    // ------------------------------------------------------------------

    data class LoginResult(val ok: Boolean, val message: String)

    /**
     * Вход по логину/паролю. Перебирает зеркала: POST login.php, затем
     * проверочный GET tracker.php (без него «успех» не засчитывается —
     * неверный пароль тоже выставляет часть кук). Пароль не хранится,
     * сохраняется только сессия ([persistSession]).
     */
    suspend fun login(login: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val u = login.trim()
        if (u.isEmpty() || password.isEmpty()) {
            return@withContext LoginResult(false, "Введите логин и пароль")
        }
        var captchaSeen = false
        var networkFailures = 0
        for (mirror in MIRRORS) {
            // Чистая попытка на каждое зеркало: иначе куки одного мешают другому.
            cookieStore.clear()
            try {
                val form = FormBody.Builder()
                    .add("login_username", u)
                    .add("login_password", password)
                    .add("login", "Вход")
                    .build()
                val loginBody = post("$mirror/forum/login.php", form, referer = "$mirror/forum/login.php")
                    ?: run { networkFailures++; continue }
                if (isLoginPage(loginBody) && loginBody.contains("captcha", ignoreCase = true)) {
                    captchaSeen = true
                    break
                }
                if (!hasSessionCookie()) continue
                // Сессия есть — проверяем, что трекер её принимает.
                val probe = get("$mirror/forum/tracker.php", referer = "$mirror/forum/")
                if (probe != null && !isLoginPage(probe)) {
                    username = u
                    prefs?.putString(PREFS_USERNAME, u)?.apply()
                    persistSession()
                    lastWorkingMirror = mirror
                    searchCache.clear()
                    KLog.i(TAG, "login ok as $u via $mirror")
                    return@withContext LoginResult(true, "Вход выполнен: $u")
                }
                // Кука выставлена, но трекер не принял — чистим и пробуем дальше.
                cookieStore.clear()
            } catch (e: Exception) {
                KLog.w(TAG, "login via $mirror failed: ${e.javaClass.simpleName}")
                networkFailures++
            }
        }
        cookieStore.clear()
        persistSession()
        return@withContext when {
            captchaSeen -> LoginResult(false, "Rutracker требует капчу — войдите через браузер и попробуйте позже")
            networkFailures >= MIRRORS.size -> LoginResult(false, "Нет соединения с Rutracker (зеркала недоступны)")
            else -> LoginResult(false, "Неверный логин или пароль")
        }
    }

    fun logout() {
        cookieStore.clear()
        username = null
        searchCache.clear()
        prefs?.remove(PREFS_USERNAME)?.remove(PREFS_COOKIES)?.apply()
        KLog.i(TAG, "logout")
    }

    /** Сервер перестал принимать сессию (трекер отдал форму входа): сбрасываем куки, логин сохраняем. */
    private fun expireSession() {
        cookieStore.clear()
        searchCache.clear()
        prefs?.remove(PREFS_COOKIES)?.apply()
        KLog.i(TAG, "session expired")
    }

    // ------------------------------------------------------------------
    // Поиск
    // ------------------------------------------------------------------

    /** Несколько запросов (название/алиасы): слияние с дедупом по топику, топ — по сидам. */
    suspend fun searchAll(queries: List<String>): List<AnimeStreamResolver.TorrentLink> =
        withContext(Dispatchers.IO) {
            if (!hasSessionCookie()) return@withContext emptyList()
            val attempts = queries.map { it.trim() }.filter { it.length >= 2 }.distinct().take(4)
            if (attempts.isEmpty()) return@withContext emptyList()
            val merged = linkedMapOf<String, AnimeStreamResolver.TorrentLink>()
            attempts.forEach { q ->
                search(q).forEach { link ->
                    merged.putIfAbsent(link.torrentUrl ?: link.magnet ?: return@forEach, link)
                }
            }
            merged.values.sortedByDescending { it.seeders }.take(MAX_RESULTS)
        }

    suspend fun search(query: String): List<AnimeStreamResolver.TorrentLink> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2 || !hasSessionCookie()) return@withContext emptyList()
            val cacheKey = q.lowercase()
            searchCache[cacheKey]?.let { (at, cached) ->
                if (System.currentTimeMillis() - at < SEARCH_TTL_MS) return@withContext cached
                searchCache.remove(cacheKey)
            }
            for (mirror in orderedMirrors()) {
                val html = get(
                    "$mirror/forum/tracker.php?nm=${encodeQuery(q)}",
                    referer = "$mirror/forum/"
                ) ?: continue
                if (isLoginPage(html)) {
                    expireSession()
                    return@withContext emptyList()
                }
                if (!html.contains("tor-tbl") && !html.contains("hl-tr")) continue
                lastWorkingMirror = mirror
                val rows = parseTrackerPage(html, mirror)
                val enriched = enrichWithMagnets(rows, mirror)
                searchCache[cacheKey] = System.currentTimeMillis() to enriched
                return@withContext enriched
            }
            emptyList()
        }

    private fun orderedMirrors(): List<String> {
        val last = lastWorkingMirror
        return if (last != null) listOf(last) + MIRRORS.filter { it != last } else MIRRORS
    }

    /**
     * Magnet из страниц топиков (топ по сидам): только magnet работает во
     * внешнем клиенте без авторизации. Остальным строкам остаётся dl.php.
     */
    private suspend fun enrichWithMagnets(
        rows: List<AnimeStreamResolver.TorrentLink>,
        mirror: String
    ): List<AnimeStreamResolver.TorrentLink> = supervisorScope {
        if (rows.isEmpty()) return@supervisorScope rows
        val semaphore = Semaphore(MAGNET_CONCURRENCY)
        val top = rows.sortedByDescending { it.seeders }.take(MAGNET_ENRICH_TOP).toSet()
        val jobs = rows.map { row ->
            if (row.magnet != null || row !in top) {
                null
            } else {
                async(Dispatchers.IO) {
                    semaphore.withPermit { fetchTopicMagnet(row, mirror) }
                }
            }
        }
        rows.mapIndexed { index, row ->
            val magnet = jobs[index]?.let { runCatching { it.await() }.getOrNull() }
            if (magnet != null) row.copy(magnet = magnet) else row
        }
    }

    /** Magnet со страницы топика; null — хэша нет или страница недоступна. */
    private fun fetchTopicMagnet(row: AnimeStreamResolver.TorrentLink, mirror: String): String? {
        val topicId = row.torrentUrl
            ?.let { Regex("""[?&]t=(\d+)""").find(it)?.groupValues?.get(1) }
            ?: return null
        val page = get("$mirror/forum/viewtopic.php?t=$topicId", referer = "$mirror/forum/") ?: return null
        if (isLoginPage(page)) return null
        return Regex("""magnet:\?xt=urn:btih:[a-zA-Z0-9]{32}[^"'\s<]*""").find(page)?.value
    }

    // ------------------------------------------------------------------
    // Парсинг (чистые функции — покрыты unit-тестами)
    // ------------------------------------------------------------------

    /** true — страница формы входа (не залогинен / сессия протухла). */
    fun isLoginPage(html: String): Boolean =
        html.contains("name=\"login_username\"") || html.contains("name=\"login_password\"")

    fun parseTrackerPage(html: String, baseUrl: String): List<AnimeStreamResolver.TorrentLink> {
        val base = baseUrl.trimEnd('/')
        val results = linkedMapOf<String, AnimeStreamResolver.TorrentLink>()
        Regex("""<tr[^>]*class="hl-tr"[^>]*>([\s\S]*?)</tr>""")
            .findAll(html)
            .take(MAX_RESULTS)
            .forEach { rowMatch ->
                val row = rowMatch.groupValues[1]
                val topic = Regex(
                    """<a\s+class="tLink"[^>]*href="(?:\./)?viewtopic\.php\?t=(\d+)[^"]*"[^>]*>([\s\S]*?)</a>"""
                ).find(row) ?: return@forEach
                val topicId = topic.groupValues[1]
                val title = unescapeHtml(topic.groupValues[2].replace(Regex("""<[^>]+>"""), " "))
                    .replace(Regex("""\s+"""), " ").trim()
                if (title.isEmpty()) return@forEach

                val size = Regex("""<a[^>]*href="(?:\./)?dl\.php\?t=\d+"[^>]*>([^<]+)</a>""")
                    .find(row)?.groupValues?.get(1)
                    ?.replace("&nbsp;", " ")?.replace(" ", " ")?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "?"
                val seeders = Regex("""seedmed"[^>]*>([^<]*)</""")
                    .find(row)?.groupValues?.get(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val leechers = Regex("""leechmed"[^>]*>([^<]*)</""")
                    .find(row)?.groupValues?.get(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0

                val quality = when {
                    title.contains("2160p", ignoreCase = true) || title.contains("4K") -> "4K UHD"
                    title.contains("1080p", ignoreCase = true) -> "1080p"
                    title.contains("720p", ignoreCase = true) -> "720p"
                    title.contains("480p", ignoreCase = true) -> "480p"
                    else -> title.take(35)
                }
                val link = AnimeStreamResolver.TorrentLink(
                    quality = quality,
                    size = size,
                    seeders = seeders,
                    leechers = leechers,
                    magnet = null,
                    torrentUrl = "$base/forum/dl.php?t=$topicId",
                    label = title.take(200),
                    source = SOURCE_NAME,
                    codec = if (AnimeStreamResolver.hasHevcMarker(title)) "HEVC" else null
                )
                results.putIfAbsent("$base/forum/dl.php?t=$topicId", link)
            }
        return results.values.toList()
    }

    fun unescapeHtml(value: String): String {
        var s = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        s = Regex("""&#(\d+);""").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return s
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun encodeQuery(query: String): String = runCatching {
        java.net.URLEncoder.encode(query, "windows-1251")
    }.getOrElse {
        java.net.URLEncoder.encode(query, "UTF-8")
    }

    private fun get(url: String, referer: String? = null): String? = runCatching {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        referer?.let { builder.header("Referer", it) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                KLog.w(TAG, "GET $url -> ${response.code}")
                null
            } else {
                response.body.string()
            }
        }
    }.onFailure { KLog.w(TAG, "GET $url failed: ${it.javaClass.simpleName}") }
        .getOrNull()

    private fun post(url: String, form: FormBody, referer: String? = null): String? = runCatching {
        val builder = Request.Builder().url(url).post(form).header("User-Agent", USER_AGENT)
        referer?.let {
            builder.header("Referer", it)
            builder.header("Origin", it.substringBefore("/forum/"))
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            response.body.string()
        }
    }.onFailure { KLog.w(TAG, "POST $url failed: ${it.javaClass.simpleName}") }
        .getOrNull()
}
