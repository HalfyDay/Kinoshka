package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Клиент публичного API smarthard.net (shikivideos) — архива видео shikicinema, перенесённого
 * из дампа базы Shikimori. Без ключей и авторизации; ключ записи — Shikimori anime_id, поэтому
 * аниме резолвится напрямую по id, хентай — поиском по названию с кластеризацией по anime_id
 * (search-ответ названий не возвращает).
 *
 * Записи ссылаются на сторонние видеохосты разной степени живости, поэтому ссылки НЕ
 * фильтруются при листинге (решение с VPN-припиской — доступность хостов зависит от VPN и
 * проверяется отдельно): резолв выполняется лениво — прямые файлы проходят как есть,
 * sibnet-embed скрапится в MP4 (требует Referer), остальные embed-страницы снифаются на
 * предмет прямых .mp4/.m3u8. Неразрешённые ссылки вызывающий помечает « · VPN».
 */
object SmarthardApi {
    private const val TAG = "SmarthardApi"

    /** Суффикс рядов/серий, чьи ссылки ведут на embed-хосты, недоступные без VPN. */
    const val VPN_ROW_SUFFIX = " · VPN"

    private const val API_BASE = "https://smarthard.net/api"
    private const val SIBNET_REFERER = "https://video.sibnet.ru/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

    private data class CacheEntry<T>(val data: T, val timestamp: Long)
    private const val RECORDS_TTL_MS = 10 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 3 * 60 * 1000L

    /** Потолок листинга: длинносериалы (Наруто ~7k записей) не должны грузить десятки страниц. */
    private const val PAGE_SIZE = 1000
    private const val MAX_PAGES = 12

    private val recordsCache = ConcurrentHashMap<Int, CacheEntry<List<SmarthardRecord>>>()
    private val searchCache = ConcurrentHashMap<String, CacheEntry<List<Int>>>()

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** Сниф embed-страниц: недоступные без VPN хосты должны фейлиться быстро, не висеть 15с. */
    private val sniffClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Одна запись shikivideos; url очищен от html-экранирования (&amp; в дампе бывает битым). */
    data class SmarthardRecord(
        val id: Int,
        val animeId: Int,
        val episode: Int,
        val kind: String,
        val language: String,
        val author: String,
        val url: String
    )

    /** Итог ленивого резолва записи: играемая ссылка + заголовки (Sibnet требует Referer). */
    data class ResolvedLink(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    /** Группа записей одного (kind, author) — ряд «озвучки/сабов» в терминах приложения. */
    class SmarthardGroup internal constructor(
        val kind: String,
        val author: String,
        val translationId: String
    ) {
        /** Эпизод -> лучшая запись (прямой файл > sibnet > embed) — для листинга. */
        val episodeRecords = LinkedHashMap<Int, SmarthardRecord>()

        /** Эпизод -> ВСЕ записи по убыванию приоритета — резолв пробует их по очереди,
         *  пока одна не разрешилась (лучшая кандидатка может оказаться мёртвым хостом). */
        val episodeCandidates = HashMap<Int, List<SmarthardRecord>>()

        val displayTitle: String get() = author.ifBlank { kindLabel(kind) }
        val type: String get() = kindToType(kind)
    }

    // ---- листинг ----

    /** Все записи тайтла по Shikimori anime_id, постранично; кэш 10 мин (пустой — 3 мин). */
    suspend fun loadRecords(animeId: Int): List<SmarthardRecord> = withContext(Dispatchers.IO) {
        if (animeId <= 0) return@withContext emptyList()
        recordsCache[animeId]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val valid = if (entry.data.isEmpty()) age < NEGATIVE_TTL_MS else age < RECORDS_TTL_MS
            if (valid) return@withContext entry.data
            recordsCache.remove(animeId)
        }

        val all = mutableListOf<SmarthardRecord>()
        for (page in 0 until MAX_PAGES) {
            val body = get("$API_BASE/shikivideos/$animeId?limit=$PAGE_SIZE&offset=${page * PAGE_SIZE}") ?: break
            val batch = parseRecords(body)
            all += batch
            if (batch.size < PAGE_SIZE) break
        }
        KLog.i(TAG, "loadRecords($animeId): ${all.size} records")
        recordsCache[animeId] = CacheEntry(all.toList(), System.currentTimeMillis())
        all
    }

    /** Поиск по названию (рус/англ). search-ответ без названий, поэтому возвращаем
     *  anime_id-кластеры по убыванию числа записей — самый populous обычно и есть искомый. */
    suspend fun searchAnimeIds(query: String): List<Int> = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return@withContext emptyList()
        searchCache[key]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val valid = if (entry.data.isEmpty()) age < NEGATIVE_TTL_MS else age < RECORDS_TTL_MS
            if (valid) return@withContext entry.data
            searchCache.remove(key)
        }

        val enc = URLEncoder.encode(query, "UTF-8")
        val body = get("$API_BASE/shikivideos/search?title=$enc&limit=100") ?: return@withContext emptyList()
        val counts = parseRecords(body).groupingBy { it.animeId }.eachCount()
        val ids = counts.entries.sortedByDescending { it.value }.map { it.key }
        searchCache[key] = CacheEntry(ids, System.currentTimeMillis())
        ids
    }

    /**
     * Группировка записей в ряды (kind, author); внутри ряда на эпизод выживает лучшая запись:
     * прямой файл > sibnet-embed > остальной embed. Порядок рядов — по возрастанию id записей,
     * детерминированный между вызовами (translationId выживает между сессиями).
     */
    fun groupRecords(records: List<SmarthardRecord>): List<SmarthardGroup> {
        val groups = LinkedHashMap<String, SmarthardGroup>()
        for (record in records.sortedBy { it.id }) {
            if (record.url.isBlank()) continue
            val key = "${record.kind}|${record.author}"
            val group = groups.getOrPut(key) {
                SmarthardGroup(record.kind, record.author, translationIdOf(record.kind, record.author))
            }
            val existing = group.episodeRecords[record.episode]
            if (existing == null || hostScore(record.url) > hostScore(existing.url)) {
                group.episodeRecords[record.episode] = record
            }
        }
        // Кандидаты для резолва: все записи эпизода группы, лучшая — первой.
        val candidates = HashMap<SmarthardGroup, HashMap<Int, MutableList<SmarthardRecord>>>()
        for (record in records.sortedBy { it.id }) {
            if (record.url.isBlank()) continue
            val key = "${record.kind}|${record.author}"
            val group = groups[key] ?: continue
            candidates.getOrPut(group) { HashMap() }
                .getOrPut(record.episode) { mutableListOf() }
                .add(record)
        }
        for ((group, byEpisode) in candidates) {
            for ((ep, list) in byEpisode) {
                group.episodeCandidates[ep] = list.sortedByDescending { hostScore(it.url) }
            }
        }
        return groups.values.toList()
    }

    fun groupById(groups: List<SmarthardGroup>, translationId: String): SmarthardGroup? =
        groups.firstOrNull { it.translationId == translationId }

    fun translationIdOf(kind: String, author: String): String =
        "sh" + ((kind + "|" + author).hashCode() and 0x7fffffff)

    fun kindLabel(kind: String): String = when (kind) {
        "озвучка" -> "Озвучка"
        "субтитры" -> "Субтитры"
        "оригинал" -> "Оригинал"
        else -> kind.ifBlank { "Озвучка" }
    }

    fun kindToType(kind: String): String = when (kind) {
        "субтитры" -> "sub"
        "оригинал" -> "orig"
        else -> "voice"
    }

    /** Для хентай-ряда: русская озвучка важнее русских сабов, те важнее оригинала и прочего. */
    fun kindRank(record: SmarthardRecord): Int = when {
        record.kind == "озвучка" && record.language == "russian" -> 0
        record.kind == "озвучка" -> 1
        record.kind == "субтитры" && record.language == "russian" -> 2
        record.kind == "субтитры" -> 3
        record.kind == "оригинал" -> 4
        else -> 5
    }

    /** Прямая ссылка или sibnet-embed резолвятся без VPN; прочие embed-хосты — как повезёт. */
    fun needsVpnNote(url: String): Boolean = !isDirectFile(url) && !isSibnetShell(url)

    /** Приоритет записи эпизода: прямой файл > sibnet-embed > прочий embed. */
    private fun hostScore(url: String): Int = when {
        isDirectFile(url) -> 2
        isSibnetShell(url) -> 1
        else -> 0
    }

    // ---- резолв ссылки ----

    /**
     * Ленивый резолв записи: прямые файлы проходят как есть; sibnet-embed скрапится до MP4
     * (только с Referer); остальные embed-страницы снифаются на .mp4/.m3u8 с короткими
     * таймаутами. null = не разрешили (вызывающий оставляет сырую ссылку с пометкой VPN).
     */
    suspend fun resolveLink(url: String): ResolvedLink? = withContext(Dispatchers.IO) {
        val clean = cleanUrl(url)
        when {
            clean.isBlank() -> null
            isDirectFile(clean) -> ResolvedLink(clean)
            isSibnetShell(clean) -> scrapeSibnet(clean)
            else -> sniffEmbed(clean)
        }
    }

    /** Параллельный резолв набора ссылок (хентай-ряд эпизодов); null = запись не разрешилась. */
    suspend fun resolveLinks(urls: List<String>): List<ResolvedLink?> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            val semaphore = Semaphore(6)
            urls.map { url ->
                async {
                    semaphore.withPermit {
                        runCatching { resolveLink(url) }.getOrNull()
                    }
                }
            }.awaitAll()
        }
    }

    private fun isDirectFile(url: String): Boolean =
        Regex("""\.(mp4|m3u8|webm|mpd|mkv)([?#].*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun isSibnetShell(url: String): Boolean =
        url.contains("video.sibnet.ru") && url.contains("shell.php") && url.contains("videoid=")

    /**
     * Страница shell.php содержит player.src([{src: "/v/<hash>/<id>.mp4"...}]) — относительный
     * путь на video.sibnet.ru. Сам файл отдаётся только с Referer с video.sibnet.ru, поэтому
     * заголовок обязателен в результате.
     */
    private suspend fun scrapeSibnet(shellUrl: String): ResolvedLink? {
        val html = fetchHtml(shellUrl, SIBNET_REFERER, client)
        if (html == null) {
            KLog.w(TAG, "sibnet scrape: page fetch failed for $shellUrl")
            return null
        }
        val path = Regex("""player\.src\(\[\{src:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""(https?:[^"]*sibnet[^"]*\.(?:mp4|m3u8)[^"]*)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        if (path == null) {
            KLog.w(TAG, "sibnet scrape: no player.src found on $shellUrl")
            return null
        }
        val absolute = when {
            path.startsWith("http") -> path
            path.startsWith("/") -> "https://video.sibnet.ru$path"
            else -> null
        }
        if (absolute == null) {
            KLog.w(TAG, "sibnet scrape: unrecognizable src=$path")
            return null
        }
        KLog.i(TAG, "sibnet scrape: $shellUrl -> $absolute")
        return ResolvedLink(absolute, mapOf("Referer" to SIBNET_REFERER))
    }

    /** Один GET страницы embed-плеера с короткими таймаутами: ищем прямые .mp4/.m3u8. */
    private suspend fun sniffEmbed(url: String): ResolvedLink? {
        val html = fetchHtml(url, null, sniffClient)
        if (html == null) {
            KLog.i(TAG, "sniff: page unreachable (VPN?) $url")
            return null
        }
        val absolute = Regex(""""([^"]*\.(?:m3u8|mp4|webm)[^"]*)"""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { candidate ->
                val path = candidate.substringBefore('?')
                !path.contains("sibnet.ru/shell.php") &&
                    !path.contains("advast") && candidate.length > 20
            }
            ?: Regex("""file["']?\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (absolute == null) {
            KLog.i(TAG, "sniff: no direct media link on $url")
            return null
        }
        val full = when {
            absolute.startsWith("http") -> absolute
            absolute.startsWith("//") -> "https:$absolute"
            absolute.startsWith("/") -> "https://${java.net.URI(url).host}$absolute"
            else -> return null
        }
        KLog.i(TAG, "sniff: $url -> $full")
        return ResolvedLink(full, mapOf("Referer" to refererOf(url)))
    }

    private fun refererOf(url: String): String =
        runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault(SIBNET_REFERER)

    private fun fetchHtml(url: String, referer: String?, client: OkHttpClient): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { referer?.let { header("Referer", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                KLog.w(TAG, "fetchHtml $url -> HTTP ${response.code}")
                return@use null
            }
            // Sibnet отвечает в windows-1251; для снифа важны только ASCII-ссылки, поэтому
            // байты читаем как ISO-8859-1 без потерь и не зависим от charset страницы.
            String(body.bytes(), Charsets.ISO_8859_1)
        }
    }.getOrNull()

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                KLog.w(TAG, "GET $url -> HTTP ${response.code}")
                return@use null
            }
            body.string()
        }
    }.getOrNull()

    private fun parseRecords(body: String): List<SmarthardRecord> = runCatching {
        val array = org.json.JSONArray(body)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val episode = item.optInt("episode").takeIf { it > 0 } ?: 1
                add(
                    SmarthardRecord(
                        id = item.optInt("id"),
                        animeId = item.optInt("anime_id"),
                        episode = episode,
                        kind = item.optString("kind"),
                        language = item.optString("language"),
                        author = item.optString("author"),
                        url = cleanUrl(item.optString("url"))
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    /** В дампе встречаются html-экранированные амперсанды (&amp;); sibnet-записи лежат по
     *  http и редиректят на https — поднимаем схему сразу, чтобы не гонять 302. */
    private fun cleanUrl(url: String): String = url
        .trim()
        .replace("&amp;", "&")
        .replace("&amp", "&")
        .replace("http://video.sibnet.ru", "https://video.sibnet.ru", ignoreCase = true)
}
