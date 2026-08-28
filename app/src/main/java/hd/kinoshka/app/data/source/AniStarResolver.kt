package hd.kinoshka.app.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Резолвер каталога AniStar — общий для аниме-пикера (AnimeStreamResolver) и хентай-щитка
 * (HentaiStreamResolver, провайдер ANISTAR).
 *
 * Статья (DLE; поиск ?do=search&subaction=search&story=…) встраивает плеер iframe-ом
 * /test/player2/videoas_p2p_new.php?id=N&hash=H, чей скрипт — JS-массив
 * var playlst=[{title:"Серия N", files:[{title:"360"|"720", file:HLS}], files_mp4:[… прогрессивные MP4]}].
 * Предпочтение — files_mp4 (sfhd.an-media.org, подпись живёт ~сутки); HLS-варианты
 * (sf2/sfv.an-media.org) идут фолбэком. Весь CDN требует Referer страницы AniStar —
 * без него 403, поэтому ссылки играются только вместе с [streamHeaders].
 *
 * Адрес сайта мигрирует при блокировках; актуальный публикуется на
 * https://anistar2.ew.r.appspot.com/ (страница «актуальный адрес»), откуда он
 * вытаскивается регэкспом и кэшируется.
 */
object AniStarResolver {
    private const val TAG = "AniStarResolver"

    private const val DEFAULT_BASE = "https://v30.astar.bz"
    private const val ADDRESS_PAGE = "https://anistar2.ew.r.appspot.com/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Подписанные ссылки живут ~сутки, HLS-токены меньше — кэш короче их жизни. */
    private const val EPISODES_TTL_MS = 30 * 60 * 1000L

    /** Как часто перечитывать страницу актуального адреса (см. refreshBase). */
    private const val ADDRESS_CHECK_TTL_MS = 10 * 60 * 1000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Одна серия: номер и карта «360p/720p → прямая ссылка» (MP4 приоритетнее HLS). */
    data class AniStarEpisode(
        val number: Int,
        val label: String,
        val qualities: Map<String, String>
    ) {
        val bestQuality: String? get() = qualities.keys.maxByOrNull { qualityRankOf(it) }
        val bestUrl: String? get() = bestQuality?.let { qualities[it] }
    }

    @Volatile private var cachedBase: String? = null
    @Volatile private var addressCheckedAtMs = 0L
    private val episodesCache = ConcurrentHashMap<String, Pair<Long, List<AniStarEpisode>>>()

    /** Заголовки для проигрывания ссылок an-media.org (Referer обязателен, иначе 403). */
    fun streamHeaders(): Map<String, String> =
        mapOf("User-Agent" to USER_AGENT, "Referer" to "${currentBaseKey()}/")

    private fun currentBaseKey(): String = cachedBase ?: DEFAULT_BASE

    /**
     * Эпизоды тайтла по списку поисковых запросов (ромадзи / русский / транслит / алиасы).
     * null — статья не найдена или плеер не распарсился. Результат кэшируется.
     */
    suspend fun findEpisodes(queries: List<String>): List<AniStarEpisode>? =
        withContext(Dispatchers.IO) {
            val attempts = queries.filter { it.isNotBlank() }.distinct().take(6)
            if (attempts.isEmpty()) return@withContext null
            val cacheKey = attempts.joinToString("|").lowercase()
            episodesCache[cacheKey]?.let { (at, cached) ->
                if (System.currentTimeMillis() - at < EPISODES_TTL_MS) return@withContext cached
                episodesCache.remove(cacheKey)
            }

            var episodes = findEpisodesInternal(attempts, currentBaseKey())
            // Основной адрес мог переехать — раз за вызов пробуем адрес со страницы-заглушки.
            if (episodes == null && refreshBase()) {
                episodes = findEpisodesInternal(attempts, currentBaseKey())
            }
            if (episodes != null) {
                episodesCache[cacheKey] = System.currentTimeMillis() to episodes
            }
            episodes
        }

    private fun findEpisodesInternal(attempts: List<String>, base: String): List<AniStarEpisode>? {
        for (query in attempts) {
            val articles = runCatching { searchArticles(base, query) }
                .onFailure { Log.w(TAG, "search failed for \"$query\": ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            Log.i(TAG, "anistar \"$query\" -> ${articles.size} articles")
            val picked = HentaiStreamResolver.pickBest(
                articles.map { HentaiStreamResolver.CandidateView(it.path, "${it.label} ${HentaiStreamResolver.slugWords(it.path)}") },
                query
            ) ?: continue
            val matched = articles.first { it.path == picked }
            Log.i(TAG, "anistar matched \"${matched.label}\" (${matched.path}) for \"$query\"")
            // Статья лишь встраивает плеер iframe-ом — сам playlst живёт на его странице.
            // Предпочтение p2p-плееру (прогрессивные MP4), легаси videoas.php — фолбэк.
            val articleBody = httpGet(base + matched.path, referer = "$base/") ?: continue
            val playerPath = Regex("""/test/player2/videoas(?:_p2p_new)?\.php\?id=\d+&(?:amp;)?hash=[a-f0-9]+""")
                .findAll(articleBody)
                .map { it.value.replace("&amp;", "&") }
                .distinct()
                .let { paths -> paths.firstOrNull { it.contains("_p2p_new") } ?: paths.firstOrNull() }
                ?: run { Log.i(TAG, "article ${matched.path} has no player iframe"); continue }
            val playerBody = httpGet(base + playerPath, referer = base + matched.path) ?: continue
            val episodes = parsePlaylistPage(playerBody, base) ?: continue
            if (episodes.isNotEmpty()) return episodes
        }
        return null
    }

    /**
     * Страница-заглушка с актуальным адресом. true — адрес реально сменился
     * (иначе повторять поиск с тем же хостом бессмысленно). Проверяется не чаще
     * раза в 10 минут: тайтлов вне каталога AniStar много, и без троттлинга каждый
     * промах платил лишним запросом к appspot.
     */
    private fun refreshBase(): Boolean {
        val now = System.currentTimeMillis()
        if (now - addressCheckedAtMs < ADDRESS_CHECK_TTL_MS) return false
        addressCheckedAtMs = now
        val body = httpGet(ADDRESS_PAGE) ?: run {
            Log.w(TAG, "address page unreachable")
            return false
        }
        val host = Regex("""(?:https?://)?([a-z0-9.-]*astar[a-z0-9.-]*)(?:/|\s|"|<)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.lowercase()
            ?: run { Log.w(TAG, "no astar address on the address page"); return false }
        val base = "https://$host"
        if (base == currentBaseKey()) return false
        Log.i(TAG, "anistar moved: ${currentBaseKey()} -> $base")
        cachedBase = base
        episodesCache.clear()
        return true
    }

    // ============================ разбор страниц ============================

    private data class Article(val path: String, val label: String)

    /** Статьи лежат в корне: /<id>-<slug>.html (без категории, в отличие от allhentai). */
    private fun searchArticles(base: String, query: String): List<Article> {
        val url = "$base/index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = httpGet(url, referer = "$base/") ?: return emptyList()
        return Regex("""href="(?:https?://[a-z0-9.-]+)?/(\d{2,6}-[a-z0-9-]+\.html)"""")
            .findAll(body)
            .map { m ->
                val path = "/${m.groupValues[1]}"
                val label = path.substringAfterLast('/')
                    .substringBefore(".html")
                    .replace(Regex("""^\d+-"""), "")
                    .replace('-', ' ')
                    .trim()
                Article(path, label)
            }
            .distinctBy { it.path }
            .toList()
    }

    /**
     * var playlst=[{title:"Серия N", media_id:"…", files:[{title:"360",file:HLS},…],
     * files_mp4:[{title:"720",file:MP4},…]}, …] — элементы режутся по паре
     * title+media_id: внутренние {title:"360", file:…} media_id не имеют и в сплиттинг
     * не попадают, иначе чанк серии обрывался бы до её массивов файлов.
     *
     * Легаси-плеер (videoas.php) вместо массивов держит file:"/test/player2/playlist_hls.php?360=…&720=…":
     * обёртка — master-HLS, но её 720-вариант по мёртвому токену роняет выбор ffmpeg,
     * поэтому декодируем только живой статичный 360 (sf2, Referer-зависимый).
     */
    private fun parsePlaylistPage(body: String, base: String): List<AniStarEpisode>? {
        if (!body.contains("playlst")) return null
        val starts = Regex("""\{\s*title:"([^"]+)"\s*,\s*media_id:""").findAll(body).toList()
        if (starts.isEmpty()) return null
        val episodes = mutableListOf<AniStarEpisode>()
        for ((index, start) in starts.withIndex()) {
            val end = starts.getOrNull(index + 1)?.range?.first ?: body.length
            val chunk = body.substring(start.range.first, end)
            val qualities = linkedMapOf<String, String>()
            parseFileArray(chunk, "files_mp4").forEach { (q, u) -> if (u.startsWith("http")) qualities.putIfAbsent(q, u) }
            parseFileArray(chunk, "files").forEach { (q, u) -> if (u.startsWith("http")) qualities.putIfAbsent(q, u) }
            if (qualities.isEmpty()) {
                val raw = Regex("""\bfile:"([^"]+)"""").find(chunk)?.groupValues?.get(1)?.trim() ?: continue
                val decoded360 = Regex("""[?&]360=([^&"]+)""").find(raw)?.groupValues?.get(1)
                    ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                when {
                    decoded360 != null && decoded360.startsWith("http") -> qualities["360p"] = decoded360
                    raw.startsWith("http") -> qualities["Auto"] = raw
                    raw.startsWith("/") -> qualities["Auto"] = base + raw
                    else -> continue
                }
            }
            val label = start.groupValues[1].trim()
            val number = Regex("""(\d{1,3})""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: (episodes.size + 1)
            episodes.add(AniStarEpisode(number = number, label = label, qualities = qualities))
        }
        return episodes
    }

    /** [{title:"720", file:"https://…"}] → map("720p" to url). Прогрессивные MP4 важнее HLS. */
    private fun parseFileArray(chunk: String, arrayName: String): List<Pair<String, String>> {
        val arrayBody = Regex("""$arrayName\s*:\s*\[([\s\S]*?)\]""").find(chunk)?.groupValues?.get(1)
            ?: return emptyList()
        return Regex("""\{\s*title:\s*"([^"]*)"\s*,\s*file:\s*"([^"]+)""").findAll(arrayBody)
            .map { m ->
                val quality = m.groupValues[1].trim().removeSuffix("p").let { if (it.isEmpty()) "Auto" else "${it}p" }
                quality to m.groupValues[2].trim()
            }
            .toList()
    }

    private fun qualityRankOf(quality: String): Int =
        hd.kinoshka.app.data.model.qualityRank(quality)

    // ============================ торренты со статей ============================

    /** Раздача со страницы статьи AniStar (DLE-блок «Скачать»). */
    data class AniStarTorrent(
        val label: String,
        val torrentUrl: String?,
        val magnet: String?
    ) {
        val primaryUri: String? get() = magnet ?: torrentUrl
    }

    /**
     * Торренты тайтла: поиск статьи тем же каскадом, что и серии, затем выемка ссылок
     * .torrent / magnet / DLE-«скачать» из её HTML (тело статьи уже скачивается резолвером
     * серий — здесь оно просто читается заново под другим углом). Пусто — блока нет.
     */
    suspend fun fetchTorrents(queries: List<String>): List<AniStarTorrent> =
        withContext(Dispatchers.IO) {
            val attempts = queries.filter { it.isNotBlank() }.distinct().take(6)
            if (attempts.isEmpty()) return@withContext emptyList()
            var torrents = fetchTorrentsInternal(attempts, currentBaseKey())
            if (torrents.isEmpty() && refreshBase()) {
                torrents = fetchTorrentsInternal(attempts, currentBaseKey())
            }
            torrents
        }

    private fun fetchTorrentsInternal(attempts: List<String>, base: String): List<AniStarTorrent> {
        for (query in attempts) {
            val articles = runCatching { searchArticles(base, query) }.getOrDefault(emptyList())
            val picked = HentaiStreamResolver.pickBest(
                articles.map { HentaiStreamResolver.CandidateView(it.path, "${it.label} ${HentaiStreamResolver.slugWords(it.path)}") },
                query
            ) ?: continue
            val matched = articles.first { it.path == picked }
            val articleBody = httpGet(base + matched.path, referer = "$base/") ?: continue
            val found = extractTorrents(articleBody, base)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Мультипаттерн: прямые .torrent, magnet, DLE-/engine/download.php только с «торрент»-текстом. */
    internal fun extractTorrents(articleBody: String, base: String): List<AniStarTorrent> {
        val results = linkedMapOf<String, AniStarTorrent>()

        fun add(label: String?, url: String?, magnet: String?) {
            val key = (url ?: magnet) ?: return
            val cleanLabel = label
                ?.replace(Regex("""<[^>]+>"""), " ")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            results.putIfAbsent(
                key,
                AniStarTorrent(
                    label = cleanLabel ?: url?.substringAfterLast('/')?.substringBefore(".torrent") ?: "Торрент",
                    torrentUrl = url,
                    magnet = magnet
                )
            )
        }

        Regex("""<a[^>]+href="([^"]+\.torrent(?:\?[^"]*)?)"""", RegexOption.IGNORE_CASE)
            .findAll(articleBody)
            .forEach { m ->
                val href = m.groupValues[1]
                val anchor = anchorText(articleBody, m.range.first)
                add(anchor, if (href.startsWith("//")) "https:$href" else if (href.startsWith("/")) base + href else href, null)
            }

        Regex("""href="(magnet:\?[^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(articleBody)
            .forEach { m -> add(anchorText(articleBody, m.range.first), null, m.groupValues[1]) }

        Regex("""<a[^>]+href="((?:https?://[^"]+)?/engine/download\.php\?id=\d+[^"]*)"[^>]*>([\s\S]{0,120}?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(articleBody)
            .forEach { m ->
                val text = m.groupValues[2]
                // DLE-скачивание ведёт и на скриншоты/архивы: берём только явно торрентовые подписи.
                if (text.contains("торрент", ignoreCase = true) || text.contains("torrent", ignoreCase = true)) {
                    val href = m.groupValues[1]
                    add(text, if (href.startsWith("/")) base + href else href, null)
                }
            }

        return results.values.toList()
    }

    private fun anchorText(body: String, anchorStart: Int): String? {
        val region = body.substring(anchorStart, minOf(body.length, anchorStart + 600))
        return Regex(""">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(region)?.groupValues?.get(1)
    }

    private fun httpGet(url: String, referer: String? = null): String? = runCatching {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        referer?.let { builder.header("Referer", it) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "GET $url -> ${response.code}")
                null
            } else {
                response.body?.string()
            }
        }
    }.onFailure { Log.w(TAG, "GET $url failed: ${it.javaClass.simpleName}") }
        .getOrNull()
}
