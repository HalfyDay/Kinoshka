package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSource
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.AnimeTranslation
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.data.model.qualityRank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

object AnimeStreamResolver {

    private const val TAG = "AnimeStreamResolver"

    // AniStar: одна озвучка на статью, стабильные id для persisted-ключей плеера.
    private const val ANISTAR_TRANSLATION_ID = "anistar"
    private const val ANISTAR_TITLE = "Русская озвучка"
    // Kodik's de-facto top variant (resolveStream also prefers the 720p track); used as the
    // quality-badge fallback when a player link carries no explicit quality tag.
    private const val KODIK_DEFAULT_QUALITY = "720p"

    private val KODIK_TOKEN_FALLBACKS = listOf(
        "56a768d08f43091901c44b54fe970049",
        "41dd95f84c21719b09d6c71182237a25",
        "77b567ec164db6ca9162d2f3dc4948c3"
    )

    // Живые API-домены Kodik (сент. 2026): kodikapi.com, kodik.info, kodik.cc, aniqit.com
    // и kodi.my NXDOMAIN глобально — каждый мёртвый домен сжигал до ~18 c на DoH-фоллбеки
    // перед системным DNS (UnknownHostException) в каждом поиске.
    private val KODIK_API_BASES = listOf(
        "https://kodik-api.com"
    )

    private val ANILIBERTY_API = listOf(
        "https://anilibria.top",
        "https://api.anilibria.pro",
        "https://api.anilibria.tv"
    )

    // AniLib (animelib.org) — отдельный источник, НЕ путать с AniLiberty (anilibria.top, v1 API выше).
    // Старый домен anilib.me закрыт в РФ на TLS-уровне, зеркала anilib.top/anilib.club мертвы,
    // а сам сайт переехал на animelib.org. Его Cloudflare WAF гео-блокирует РФ, но API-домен
    // api.animelib.org живёт на DDoS-Guard и отвечает (проверено 2026-08).
    // Новое REST API: /api/anime?search= → /api/episodes?anime_id= → /api/episodes/<id>
    // c players[] (team, translation_type Озвучка/Субтитры, src = ссылка на Kodik-плеер),
    // HLS достаётся уже имеющимся resolveKodikHls.
    private val ANILIB_API = listOf(
        "https://api.animelib.org"
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            // Private-DNS blockers sink kodik/aniqit domains at the DNS level; DoH fallback
            // restores them for every HTTP path (API, find-player, HLS extraction).
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var kodikTokensCache: List<String>? = null

    @Volatile
    private var lastWorkingKodikToken: String? = null

    @Volatile
    private var lastWorkingRotStep: Int? = null

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    private val prefetchAllMediaCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<List<FlatTranslation>>>()
    // Per-source prefetch results ("KODIK:12:violet"), feeding the progressive selection page:
    // each source renders as soon as its own network roundtrip finishes instead of waiting for
    // the slowest of the three.
    private val sourceMediaCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<List<FlatTranslation>>>()
    private val resolveStreamCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<AnimeMediaStream>>()
    private val aniLibertyReleaseCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    // AniLib: episode details (/api/v2/episode) fetched during prefetch. resolveStream reads the
    // SAME cached json, so "v<i>|<label>"/"s<i>|<label>" translation ids stay aligned with the
    // players array that produced them.
    private val anilibEpisodeCache = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<JSONObject>>()
    // Resolved Kodik HLS link sets keyed by episode link: re-picking an episode previously cost
    // the whole 3-25 request scrape again.
    private val kodikHlsCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<Map<String, String>>>()

    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache
    private const val NEGATIVE_CACHE_TTL_MS = 3 * 60 * 1000L
    private const val HLS_CACHE_TTL_MS = 30 * 60 * 1000L

    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchAvailableSources(shikimoriId: Int, animeTitle: String = ""): List<AnimeSource> = withContext(Dispatchers.IO) {
        listOf(
            AnimeSource(AnimeSourceType.KODIK, isAvailable = true),
            AnimeSource(AnimeSourceType.SHIKIMORI, isAvailable = true),
            AnimeSource(AnimeSourceType.ANILIBERTY, isAvailable = true),
            AnimeSource(AnimeSourceType.ANILIB, isAvailable = true)
        )
    }

    private fun parseAniLibertyEpisodes(release: JSONObject): List<AnimeEpisode> {
        val episodes = release.optJSONArray("episodes") ?: return emptyList()
        // distinctBy is required, not cosmetic: two records can collapse onto the same `number`
        // because org.json's optInt truncates a fractional ordinal (7.5 -> 7), and an episode with no
        // ordinal/sort_order falls through to `id`, which lives in a different numbering space than
        // its siblings. This list feeds a keyed LazyColumn, which rejects duplicate keys.
        return episodes.asSequenceObjects().mapNotNull { episode ->
            val number = episode.optInt("ordinal").takeIf { it > 0 }
                ?: episode.optInt("sort_order").takeIf { it > 0 }
                ?: episode.optInt("id").takeIf { it > 0 }
                ?: return@mapNotNull null
            val title = episode.optCleanString("name").ifBlank {
                episode.optCleanString("name_english").ifBlank { "Серия $number" }
            }
            val maxQuality = when {
                episode.optCleanString("hls_1080").isNotBlank() -> "1080p"
                episode.optCleanString("hls_720").isNotBlank() -> "720p"
                else -> null
            }
            AnimeEpisode(number = number, title = title, id = episode.optInt("id").takeIf { it > 0 }, maxQuality = maxQuality)
        }.distinctBy { it.number }.sortedBy { it.number }.toList()
    }

    /**
     * Best-quality hint carried in a Kodik player link path ("…/seria/…/720p"): the tag is
     * the provider's top variant for that entry. Returns e.g. "720p"; [fallback] applies when
     * the path carries no quality segment (some API rows return bare links).
     */
    internal fun qualityFromLink(link: String?, fallback: String? = null): String? {
        val qualityTag = Regex("""^(2160|1440|1080|720|480|360|240)p?$""", RegexOption.IGNORE_CASE)
        return link?.substringBefore('?')?.substringBefore('#')
            ?.split('/')?.lastOrNull { qualityTag.matches(it) }
            ?.let { "${it.lowercase().removeSuffix("p")}p" }
            ?: fallback
    }

    /** True when the title is a generated placeholder ("Серия N", "Special N") rather than a name. */
    private fun isSyntheticEpisodeTitle(title: String?, number: Int): Boolean {
        val t = title?.trim() ?: return true
        if (t.isEmpty() || t.equals("null", ignoreCase = true)) return true
        if (t == "Серия $number" || t == "Special $number") return true
        return t.startsWith("Сезон ") && t.endsWith("Серия $number")
    }

    /** optString that treats JSON null, the literal "null" and blanks as absent. */
    private fun JSONObject.optCleanString(key: String): String =
        optString(key).takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }.orEmpty()

    suspend fun prefetchAllMedia(shikimoriId: Int, animeTitle: String): List<FlatTranslation> {
        val cacheKey = "$shikimoriId:${animeTitle.trim().lowercase()}"
        prefetchAllMediaCache[cacheKey]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val stillValid = if (entry.data.isEmpty()) age < NEGATIVE_CACHE_TTL_MS else age < CACHE_TTL_MS
            if (stillValid) {
                return entry.data
            } else {
                prefetchAllMediaCache.remove(cacheKey)
            }
        }

        val loaded = prefetchAllMediaInternal(shikimoriId, animeTitle)
        // Cache empty results too (with a shorter TTL): without this, every re-entry into a
        // title with no sources re-ran the entire three-provider cascade.
        prefetchAllMediaCache[cacheKey] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    /** Snapshot of a fresh prefetch result for [shikimoriId]/[animeTitle], or null on miss. */
    private fun cachedPrefetchedTranslations(shikimoriId: Int, animeTitle: String): List<FlatTranslation>? {
        val cacheKey = "$shikimoriId:${animeTitle.trim().lowercase()}"
        val entry = prefetchAllMediaCache[cacheKey]
        // The progressive selection page only fills the per-source cache; resolveKodikStream's
        // fast path must see it too, otherwise every episode click re-ran the search cascade.
        val sourceEntry = sourceMediaCache["${AnimeSourceType.KODIK.name}:$shikimoriId:${animeTitle.trim().lowercase()}"]
        for (candidate in listOf(entry, sourceEntry)) {
            if (candidate == null) continue
            val age = System.currentTimeMillis() - candidate.timestamp
            val stillValid = if (candidate.data.isEmpty()) age < NEGATIVE_CACHE_TTL_MS else age < CACHE_TTL_MS
            if (stillValid) return candidate.data
        }
        return null
    }

    private fun getCachedKodikHls(episodeLinkAbsolute: String): Map<String, String>? {
        kodikHlsCache[episodeLinkAbsolute]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < HLS_CACHE_TTL_MS) return entry.data
            kodikHlsCache.remove(episodeLinkAbsolute)
        }
        return null
    }

    /**
     * Translations of ONE source, cached independently so the progressive selection page can
     * render each provider as soon as it answers instead of waiting for the slowest one.
     * Empty results are negative-cached with the shorter TTL, like the merged prefetch.
     */
    suspend fun fetchSourceMedia(shikimoriId: Int, animeTitle: String, source: AnimeSourceType): List<FlatTranslation> {
        val cacheKey = "${source.name}:$shikimoriId:${animeTitle.trim().lowercase()}"
        sourceMediaCache[cacheKey]?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val stillValid = if (entry.data.isEmpty()) age < NEGATIVE_CACHE_TTL_MS else age < CACHE_TTL_MS
            if (stillValid) {
                return entry.data
            } else {
                sourceMediaCache.remove(cacheKey)
            }
        }

        val loaded = when (source) {
            AnimeSourceType.KODIK -> fetchKodikFlatTranslations(shikimoriId, animeTitle)
            AnimeSourceType.SHIKIMORI -> fetchShikimoriFlatTranslations(shikimoriId)
            AnimeSourceType.ANILIBERTY -> fetchAniLibertyFlatTranslations(shikimoriId, animeTitle)
            AnimeSourceType.ANILIB -> fetchAniLibFlatTranslations(shikimoriId, animeTitle)
            AnimeSourceType.ANISTAR -> fetchAniStarFlatTranslations(animeTitle)
            AnimeSourceType.SMARTHARD -> fetchSmarthardFlatTranslations(shikimoriId)
            // ddbb/hentai rows exist only in movie/QOM playback lists, never in the anime picker.
            AnimeSourceType.DDBB,
            AnimeSourceType.HENTAI_ALLHENTAI,
            AnimeSourceType.HENTAI_HENTAIDREAM,
            AnimeSourceType.HENTAI_HENTAIZ,
            AnimeSourceType.HENTAI_HANIME1,
            AnimeSourceType.HENTAI_OPPAI -> emptyList()
        }
        sourceMediaCache[cacheKey] = CacheEntry(loaded, System.currentTimeMillis())
        return loaded
    }

    private suspend fun fetchKodikFlatTranslations(shikimoriId: Int, animeTitle: String): List<FlatTranslation> = withContext(Dispatchers.IO) {
        runCatching {
            KLog.i(TAG, "[Kodik] Starting search...")
            val results = kodikSearch(shikimoriId, animeTitle, null)
            KLog.i(TAG, "[Kodik] Search returned ${results.size} results")
            val translations = results.mapNotNull { result ->
                val translation = result.optJSONObject("translation")
                if (translation != null) {
                    val id = translation.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val title = translation.optString("title").ifBlank { "Озвучка $id" }
                    val type = translation.optString("type").ifBlank { "voice" }
                    val episodes = extractKodikEpisodes(result)
                    FlatTranslation(
                        source = AnimeSourceType.KODIK,
                        translationId = id,
                        title = title,
                        type = type,
                        episodes = episodes
                    )
                } else if (result.has("link") || result.has("player_url")) {
                    val id = "default"
                    FlatTranslation(
                        source = AnimeSourceType.KODIK,
                        translationId = id,
                        title = "Основной плеер Kodik",
                        type = "voice",
                        episodes = extractKodikEpisodes(result)
                    )
                } else null
            }
            KLog.i(TAG, "[Kodik] Parsed ${translations.size} translations: ${translations.joinToString { "${it.title} (${it.episodes.size} ep)" }}")
            mergeTranslations(translations)
        }.getOrElse { e ->
            KLog.e(TAG, "[Kodik] Search failed: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun fetchAniLibertyFlatTranslations(shikimoriId: Int, animeTitle: String): List<FlatTranslation> = withContext(Dispatchers.IO) {
        runCatching {
            KLog.i(TAG, "[Aniliberty] Starting search...")
            val release = findAniLibertyRelease(shikimoriId, animeTitle)
            if (release != null) {
                val episodes = parseAniLibertyEpisodes(release)
                val title = getAniLibertyTitle(release)
                KLog.i(TAG, "[Aniliberty] Found: \"$title\" (${episodes.size} episodes), alias=${release.optString("alias")}, type=${release.optString("type")}")
                listOf(
                    FlatTranslation(
                        source = AnimeSourceType.ANILIBERTY,
                        // A literal "default" collides with every other source's fallback id:
                        // AnimeControls compares translationId alone and would highlight both
                        // rows as selected. The alias is also the id findAniLibertyRelease can
                        // fetch directly, so it doubles as a fast-path key.
                        translationId = release.optString("alias")
                            .ifBlank { release.optInt("id").takeIf { it > 0 }?.toString().orEmpty() }
                            .ifBlank { "default" },
                        // AniLiberty hosts a single studio dub, so the release title here read as
                        // the anime name — the same title the page was opened from. Label the
                        // voiceover by the source itself instead.
                        title = AnimeSourceType.ANILIBERTY.displayName,
                        type = "voice",
                        episodes = episodes
                    )
                )
            } else {
                KLog.w(TAG, "[Aniliberty] No release found for \"$animeTitle\"")
                emptyList()
            }
        }.getOrElse { e ->
            KLog.e(TAG, "[Aniliberty] Search failed: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun fetchAniLibFlatTranslations(shikimoriId: Int, animeTitle: String): List<FlatTranslation> = withContext(Dispatchers.IO) {
        runCatching {
            buildAniLibTeamTranslations(shikimoriId, animeTitle)
        }.getOrElse { e ->
            KLog.e(TAG, "[AniLib] Search failed: ${e.message}", e)
            emptyList()
        }
    }

    // Smarthard (shikivideos-архив shikicinema): записи берутся напрямую по Shikimori id,
    // группируются по (kind, author). Ссылки хостов разной живости — не фильтруются: ряды,
    // у которых ВСЕ серии только на embed-хостах, получают суффикс « · VPN» (без VPN они
    // не заиграют), серии с embed-ссылкой — «Серия N · VPN».
    private suspend fun fetchSmarthardFlatTranslations(shikimoriId: Int): List<FlatTranslation> = withContext(Dispatchers.IO) {
        runCatching {
            val groups = SmarthardApi.groupRecords(SmarthardApi.loadRecords(shikimoriId))
            KLog.i(TAG, "[Smarthard] id=$shikimoriId -> ${groups.size} groups")
            groups.map { group ->
                val rowVpn = group.episodeRecords.values.all { SmarthardApi.needsVpnNote(it.url) }
                FlatTranslation(
                    source = AnimeSourceType.SMARTHARD,
                    translationId = group.translationId,
                    title = group.displayTitle + if (rowVpn) SmarthardApi.VPN_ROW_SUFFIX else "",
                    type = group.type,
                    episodes = group.episodeRecords.map { (number, record) ->
                        val epVpn = SmarthardApi.needsVpnNote(record.url)
                        AnimeEpisode(
                            number = number,
                            title = "Серия $number" + if (epVpn) SmarthardApi.VPN_ROW_SUFFIX else "",
                            link = record.url
                        )
                    }.sortedBy { it.number }
                )
            }
        }.getOrElse { e ->
            KLog.e(TAG, "[Smarthard] Fetch failed: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun fetchSmarthardTranslations(shikimoriId: Int): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        SmarthardApi.groupRecords(SmarthardApi.loadRecords(shikimoriId)).map { group ->
            val rowVpn = group.episodeRecords.values.all { SmarthardApi.needsVpnNote(it.url) }
            AnimeTranslation(
                id = group.translationId,
                title = group.displayTitle + if (rowVpn) SmarthardApi.VPN_ROW_SUFFIX else "",
                type = group.type,
                episodesCount = group.episodeRecords.size
            )
        }
    }

    private suspend fun fetchSmarthardEpisodes(shikimoriId: Int, translationId: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        val group = SmarthardApi.groupById(SmarthardApi.groupRecords(SmarthardApi.loadRecords(shikimoriId)), translationId)
            ?: return@withContext emptyList()
        group.episodeRecords.map { (number, _) -> AnimeEpisode(number = number, title = "Серия $number") }.sortedBy { it.number }
    }

    private suspend fun resolveSmarthardStream(shikimoriId: Int, translationId: String, episodeNumber: Int): AnimeMediaStream? = withContext(Dispatchers.IO) {
        val group = SmarthardApi.groupById(SmarthardApi.groupRecords(SmarthardApi.loadRecords(shikimoriId)), translationId)
        if (group == null) {
            KLog.w(TAG, "[Smarthard] resolve: group $translationId not found for id=$shikimoriId")
            return@withContext null
        }
        // Точная серия; у рядов со сдвинутой нумерацией (Cuba77: эпизоды 27+) — ближайшая ниже.
        val candidates = group.episodeCandidates[episodeNumber].orEmpty().ifEmpty {
            group.episodeCandidates.entries.filter { it.key < episodeNumber }
                .maxByOrNull { it.key }?.value.orEmpty()
        }
        if (candidates.isEmpty()) {
            KLog.w(TAG, "[Smarthard] resolve: no records for ep=$episodeNumber in \"${group.displayTitle}\" (${group.episodeCandidates.keys.minOrNull()}..${group.episodeCandidates.keys.maxOrNull()})")
            return@withContext null
        }
        // Лучшая запись может вести на мёртвый/гео-блокированный хост: резолвим ВСЕ
        // кандидаты параллельно и берём первый успешный по приоритету — отказ ряда
        // занимает время одной попытки, а не суммы по всем хостам.
        val resolved = SmarthardApi.resolveLinks(candidates.map { it.url })
        val hit = resolved.withIndex().firstOrNull { it.value != null }
        if (hit != null) {
            KLog.i(TAG, "[Smarthard] resolve \"${group.displayTitle}\" ep=$episodeNumber -> ${hit.value!!.url} (record ${candidates[hit.index].id})")
            return@withContext AnimeMediaStream(
                url = hit.value!!.url,
                headers = hit.value!!.headers,
                quality = "Auto",
                title = group.displayTitle
            )
        }
        KLog.w(TAG, "[Smarthard] resolve: all ${candidates.size} candidates failed for \"${group.displayTitle}\" ep=$episodeNumber")
        null
    }

    // ============================ Shikimori ============================
    // Официальный плеер «Смотреть онлайн» (cdnvideohub): плейлист по Shikimori id без поиска,
    // vkId серии лежит в AnimeEpisode.link и резолвится в HLS при старте воспроизведения.
    // Лицензированные тайтлы и хентай отвечают пустым 204 — для них источник просто пуст.

    private suspend fun fetchShikimoriFlatTranslations(shikimoriId: Int): List<FlatTranslation> = withContext(Dispatchers.IO) {
        runCatching {
            val rows = ShikimoriVideoApi.loadPlaylist(shikimoriId)
                .filter { it.vkId.isNotBlank() }
                .groupBy { ShikimoriVideoApi.rowKey(it) }
                .map { (key, rowItems) ->
                    FlatTranslation(
                        source = AnimeSourceType.SHIKIMORI,
                        translationId = ShikimoriVideoApi.translationIdOf(key),
                        title = ShikimoriVideoApi.rowTitle(rowItems.first()),
                        type = ShikimoriVideoApi.rowType(rowItems.first()),
                        episodes = rowItems.map { item ->
                            AnimeEpisode(
                                number = item.episode,
                                title = if (item.season > 1) "Сезон ${item.season}, Серия ${item.episode}" else "Серия ${item.episode}",
                                link = item.vkId,
                                season = item.season.takeIf { it > 0 }
                            )
                        }.distinctBy { it.number }.sortedBy { it.number }
                    )
                }
            if (rows.isEmpty()) return@runCatching rows
            // Плейлист качество не отдаёт: меряем по первой серии ряда (рип внутри ряда
            // консистентен) резолвом vkId в мастер-HLS — лучший вариант становится бейджем
            // всех серий ряда. Резолвы кэшируются в ShikimoriVideoApi и переигрываются
            // resolveStream'ом без повторных запросов.
            val probeQualities = kotlinx.coroutines.coroutineScope {
                rows.map { row -> async { probeShikimoriRowQuality(row) } }.awaitAll()
            }
            rows.zip(probeQualities) { row, quality ->
                quality?.let { q ->
                    row.copy(episodes = row.episodes.map { it.copy(maxQuality = q) })
                } ?: row
            }
        }.getOrElse { e ->
            KLog.e(TAG, "[Shikimori] playlist failed: ${e.message}", e)
            emptyList()
        }
    }

    /** Бейдж-качество ряда: лучший вариант мастер-HLS его первой серии. */
    private suspend fun probeShikimoriRowQuality(row: FlatTranslation): String? {
        val vkId = row.episodes.firstOrNull()?.link?.takeIf { it.isNotBlank() } ?: return null
        val video = runCatching { ShikimoriVideoApi.resolveVideo(vkId) }.getOrNull() ?: return null
        return video.qualities.keys.maxByOrNull { qualityRank(it) }
    }

    private suspend fun fetchShikimoriTranslations(shikimoriId: Int): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        fetchShikimoriFlatTranslations(shikimoriId).map { row ->
            AnimeTranslation(
                id = row.translationId,
                title = row.title,
                type = row.type,
                episodesCount = row.episodes.size
            )
        }
    }

    private suspend fun fetchShikimoriEpisodes(shikimoriId: Int, translationId: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        fetchShikimoriFlatTranslations(shikimoriId)
            .firstOrNull { it.translationId == translationId }?.episodes ?: emptyList()
    }

    private suspend fun resolveShikimoriStream(
        shikimoriId: Int,
        animeTitle: String,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? = withContext(Dispatchers.IO) {
        KLog.i(TAG, "[Shikimori] resolveStream: id=$shikimoriId, ep=$episodeNumber, tr=$translationId")
        // Быстрый путь — плейлист, уже скачанный префетчем или страницей выбора источника.
        val vkId = cachedPrefetchedTranslations(shikimoriId, animeTitle)
            ?.filter { it.source == AnimeSourceType.SHIKIMORI && it.translationId == translationId }
            ?.flatMap { it.episodes }
            ?.firstOrNull { it.number == episodeNumber }?.link?.takeIf { it.isNotBlank() }
            ?: fetchShikimoriFlatTranslations(shikimoriId)
                .firstOrNull { it.translationId == translationId }
                ?.episodes?.firstOrNull { it.number == episodeNumber }?.link?.takeIf { it.isNotBlank() }
        if (vkId == null) {
            KLog.w(TAG, "[Shikimori] Episode $episodeNumber not found in row $translationId")
            return@withContext null
        }

        val video = ShikimoriVideoApi.resolveVideo(vkId)
        if (video == null) {
            KLog.w(TAG, "[Shikimori] resolveVideo failed for vkId=$vkId")
            return@withContext null
        }
        // Дефолт по QUALITY_PREFERENCE_DESC («max», соглашение резолверов с плеером);
        // пустая лестница = играем сам мастер-плейлист.
        val url = QUALITY_PREFERENCE_DESC.firstNotNullOfOrNull { video.qualities[it] } ?: video.url
        KLog.i(TAG, "[Shikimori] Stream URL selected: ${url.take(100)}..., qualities=${video.qualities.keys}")
        AnimeMediaStream(
            url = url,
            qualities = video.qualities,
            quality = video.qualities.entries.firstOrNull { it.value == url }?.key ?: "Auto",
            headers = mapOf("User-Agent" to USER_AGENT)
        )
    }

    private suspend fun prefetchAllMediaInternal(shikimoriId: Int, animeTitle: String): List<FlatTranslation> = withContext(Dispatchers.IO) {
        KLog.i(TAG, "=== prefetchAllMedia === id=$shikimoriId, title=\"$animeTitle\"")
        kotlinx.coroutines.coroutineScope {
            // Each branch goes through the per-source cache, so a progressive page that already
            // fetched some providers never repeats their network roundtrips here.
            val kodikResult = async { fetchSourceMedia(shikimoriId, animeTitle, AnimeSourceType.KODIK) }
            val shikimoriResult = async { fetchSourceMedia(shikimoriId, animeTitle, AnimeSourceType.SHIKIMORI) }
            val anilibertyResult = async { fetchSourceMedia(shikimoriId, animeTitle, AnimeSourceType.ANILIBERTY) }
            val anilibResult = async { fetchSourceMedia(shikimoriId, animeTitle, AnimeSourceType.ANILIB) }
            val kodik = kodikResult.await()
            val shikimori = shikimoriResult.await()
            val aniliberty = anilibertyResult.await()
            val anilib = anilibResult.await()
            KLog.i(TAG, "=== prefetchAllMedia DONE === Kodik: ${kodik.size}, Shikimori: ${shikimori.size}, Aniliberty: ${aniliberty.size}, AniLib: ${anilib.size}")
            mergeTranslations(kodik + shikimori + aniliberty + anilib)
        }
    }

    /**
     * Collapses translations that share a (source, translationId) pair.
     *
     * Kodik returns one row per catalogue entry, so a single studio appears many times over — an
     * unfiltered search for one show yielded translation id 609 ("AniDUB") eighteen times. Rendering
     * that list crashed the app, because the selection UI keys its LazyColumn by translationId and
     * Compose requires keys to be unique ("Key \"609\" was already used").
     *
     * Rows for the same dub are merged rather than dropped: their episode lists are unioned so the
     * most complete variant survives, which also fixes dubs that were previously split across rows.
     */
    private fun mergeTranslations(translations: List<FlatTranslation>): List<FlatTranslation> {
        if (translations.size < 2) return translations

        val merged = LinkedHashMap<Pair<AnimeSourceType, String>, FlatTranslation>()
        for (translation in translations) {
            val key = translation.source to translation.translationId
            val existing = merged[key]
            merged[key] = if (existing == null) {
                translation
            } else {
                // Same episode from two rows: keep the copy advertising the higher quality, but
                // preserve a real episode title if only the lower-quality copy carried one.
                val episodes = (existing.episodes + translation.episodes)
                    .groupBy { it.number }
                    .map { (_, eps) ->
                        eps.reduce { a, b ->
                            val best = if (qualityRank(b.maxQuality) > qualityRank(a.maxQuality)) b else a
                            val other = if (best === b) a else b
                            if (isSyntheticEpisodeTitle(best.title, best.number) &&
                                !isSyntheticEpisodeTitle(other.title, other.number)
                            ) best.copy(title = other.title) else best
                        }
                    }
                    .sortedBy { it.number }
                // Prefer whichever row carried a real episode list; titles are identical per id.
                existing.copy(episodes = episodes)
            }
        }
        if (merged.size != translations.size) {
            KLog.i(TAG, "Merged ${translations.size} translations into ${merged.size} unique dubs")
        }
        return merged.values.toList()
    }

    suspend fun fetchTranslations(
        shikimoriId: Int,
        animeTitle: String,
        sourceType: AnimeSourceType
    ): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.KODIK -> fetchKodikTranslations(shikimoriId, animeTitle)
            AnimeSourceType.SHIKIMORI -> fetchShikimoriTranslations(shikimoriId)
            AnimeSourceType.ANILIBERTY -> fetchAniLibertyTranslations(shikimoriId, animeTitle)
            AnimeSourceType.ANILIB -> fetchAniLibTranslations(shikimoriId, animeTitle)
            AnimeSourceType.ANISTAR -> fetchAniStarTranslations(animeTitle)
            AnimeSourceType.SMARTHARD -> fetchSmarthardTranslations(shikimoriId)
            // ddbb/hentai rows are QOM voiceovers with direct links — nothing to fetch here.
            AnimeSourceType.DDBB,
            AnimeSourceType.HENTAI_ALLHENTAI,
            AnimeSourceType.HENTAI_HENTAIDREAM,
            AnimeSourceType.HENTAI_HENTAIZ,
            AnimeSourceType.HENTAI_HANIME1,
            AnimeSourceType.HENTAI_OPPAI -> emptyList()
        }
    }

    suspend fun fetchEpisodes(
        shikimoriId: Int,
        animeTitle: String,
        sourceType: AnimeSourceType,
        translationId: String
    ): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.KODIK -> fetchKodikEpisodes(shikimoriId, animeTitle, translationId)
            AnimeSourceType.SHIKIMORI -> fetchShikimoriEpisodes(shikimoriId, translationId)
            AnimeSourceType.ANILIBERTY -> fetchAniLibertyEpisodes(shikimoriId, animeTitle, translationId)
            AnimeSourceType.ANILIB -> fetchAniLibEpisodes(shikimoriId, animeTitle, translationId)
            AnimeSourceType.ANISTAR -> fetchAniStarEpisodes(animeTitle)
            AnimeSourceType.SMARTHARD -> fetchSmarthardEpisodes(shikimoriId, translationId)
            // ddbb/hentai rows are QOM voiceovers with direct links — nothing to fetch here.
            AnimeSourceType.DDBB,
            AnimeSourceType.HENTAI_ALLHENTAI,
            AnimeSourceType.HENTAI_HENTAIDREAM,
            AnimeSourceType.HENTAI_HENTAIZ,
            AnimeSourceType.HENTAI_HANIME1,
            AnimeSourceType.HENTAI_OPPAI -> emptyList()
        }
    }

    suspend fun resolveStream(
        shikimoriId: Int,
        animeTitle: String,
        sourceType: AnimeSourceType,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? {
        val cacheKey = "$shikimoriId:$animeTitle:${sourceType.name}:$translationId:$episodeNumber"
        resolveStreamCache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return entry.data
            } else {
                resolveStreamCache.remove(cacheKey)
            }
        }

        val stream = resolveStreamInternal(shikimoriId, animeTitle, sourceType, translationId, episodeNumber)
        if (stream != null) {
            resolveStreamCache[cacheKey] = CacheEntry(stream, System.currentTimeMillis())
        }
        return stream
    }

    /**
     * Сбрасывает кэш резолва одной серии (resolveStream + HLS-кэш Kodik): авто-retry плеера
     * не должен переигрывать мёртвую ссылку из кэша — живой кейс: подписанный okcdn-url,
     * отвечавший 400, лечится только свежим резолвом с новой подписью.
     */
    fun evictEpisodeResolveCache(
        shikimoriId: Int,
        animeTitle: String,
        sourceType: AnimeSourceType,
        translationId: String,
        episodeNumber: Int
    ) {
        resolveStreamCache.remove("$shikimoriId:$animeTitle:${sourceType.name}:$translationId:$episodeNumber")
        if (sourceType == AnimeSourceType.KODIK) kodikHlsCache.clear()
    }

    private suspend fun resolveStreamInternal(
        shikimoriId: Int,
        animeTitle: String,
        sourceType: AnimeSourceType,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.KODIK -> resolveKodikStream(shikimoriId, animeTitle, translationId, episodeNumber)
            AnimeSourceType.SHIKIMORI -> resolveShikimoriStream(shikimoriId, animeTitle, translationId, episodeNumber)
            AnimeSourceType.ANILIBERTY -> resolveAniLibertyStream(shikimoriId, animeTitle, episodeNumber, translationId)
            AnimeSourceType.ANISTAR -> resolveAniStarStream(animeTitle, episodeNumber)
            AnimeSourceType.ANILIB ->
                if (translationId.startsWith("L")) {
                    resolveAniLibLegacyStream(shikimoriId, animeTitle, translationId, episodeNumber)
                } else {
                    resolveAniLibStream(shikimoriId, animeTitle, episodeNumber, translationId)
                }
            AnimeSourceType.DDBB,
            // Hentai tracks carry direct links played by the QOM path — never resolved here.
            AnimeSourceType.HENTAI_ALLHENTAI,
            AnimeSourceType.HENTAI_HENTAIDREAM,
            AnimeSourceType.HENTAI_HENTAIZ,
            AnimeSourceType.HENTAI_HANIME1,
            AnimeSourceType.HENTAI_OPPAI -> null
            AnimeSourceType.SMARTHARD -> resolveSmarthardStream(shikimoriId, translationId, episodeNumber)
        }
    }

    private suspend fun fetchKodikTranslations(shikimoriId: Int, animeTitle: String): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        val translations = linkedMapOf<String, AnimeTranslation>()
        val results = kodikSearch(shikimoriId, animeTitle, translationId = null)
        for (result in results) {
            val translation = result.optJSONObject("translation")
            if (translation != null) {
                val id = translation.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = translation.optString("title").ifBlank { "Озвучка $id" }
                val type = translation.optString("type").ifBlank { "voice" }
                val count = result.optInt("episodes_count", 0)
                    .takeIf { it > 0 }
                    ?: countKodikEpisodes(result)
                translations[id] = AnimeTranslation(id = id, title = title, type = type, episodesCount = count)
            } else if (result.has("link") || result.has("player_url")) {
                val id = "default"
                translations[id] = AnimeTranslation(id = id, title = "Основной плеер Kodik", type = "voice", episodesCount = 1)
            }
        }
        val list = translations.values.sortedWith(compareByDescending<AnimeTranslation> { it.episodesCount }.thenBy { it.title })
        if (list.isEmpty()) {
            throw IllegalStateException("Озвучки не найдены в базе Kodik для \"$animeTitle\" (Shikimori ID: $shikimoriId)")
        }
        list
    }

    private suspend fun fetchKodikEpisodes(shikimoriId: Int, animeTitle: String, translationId: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        // A Kodik translation.id identifies a *studio*, not a release, so one id spans several
        // catalogue rows (seasons, OVAs, re-uploads) and the search returns up to 10 of them.
        // prefetchAllMedia advertises the union of their episodes (see mergeTranslations), so looking
        // at only the first row made resolveKodikStream report "episode not found" for every episode
        // outside that row. Union all rows, preferring whichever copy actually carries a link.
        kodikSearch(shikimoriId, animeTitle, translationId)
            .flatMap { extractKodikEpisodes(it) }
            .groupBy { it.number }
            .map { (_, eps) -> eps.firstOrNull { !it.link.isNullOrBlank() } ?: eps.first() }
            .sortedBy { it.number }
    }

    private suspend fun resolveKodikStream(
        shikimoriId: Int,
        animeTitle: String,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? = withContext(Dispatchers.IO) {
        KLog.i(TAG, "[Kodik] resolveStream: id=$shikimoriId, ep=$episodeNumber, tr=$translationId")
        // Fast path: prefetchAllMedia already downloaded every translation's episode links —
        // reuse them instead of re-running the whole kodikSearch cascade on every episode click.
        val episodeLink = cachedPrefetchedTranslations(shikimoriId, animeTitle)
            ?.filter { it.translationId == translationId }
            ?.flatMap { it.episodes }
            ?.groupBy { it.number }
            ?.map { (_, eps) -> eps.firstOrNull { !it.link.isNullOrBlank() } ?: eps.first() }
            ?.firstOrNull { it.number == episodeNumber }
            ?.link?.takeIf { it.isNotBlank() }
            ?: run {
                KLog.d(TAG, "[Kodik] prefetch cache miss, falling back to search")
                val episode = fetchKodikEpisodes(shikimoriId, animeTitle, translationId)
                    .firstOrNull { it.number == episodeNumber }
                if (episode == null) {
                    KLog.w(TAG, "[Kodik] Episode $episodeNumber not found")
                    return@withContext null
                }
                episode.link?.takeIf { it.isNotBlank() } ?: run {
                    KLog.w(TAG, "[Kodik] Episode $episodeNumber has no link")
                    return@withContext null
                }
            }

        val absoluteLink = absoluteKodikUrl(episodeLink)
        KLog.d(TAG, "[Kodik] Episode link: $absoluteLink")
        val qualities = getCachedKodikHls(absoluteLink) ?: resolveKodikHls(absoluteLink).also {
            if (it.isNotEmpty()) kodikHlsCache[absoluteLink] = CacheEntry(it, System.currentTimeMillis())
        }
        KLog.i(TAG, "[Kodik] HLS qualities: ${qualities.keys}")
        val url = qualities["720p"] ?: qualities["480p"] ?: qualities["360p"] ?: qualities.values.firstOrNull()
        if (url != null) {
            KLog.i(TAG, "[Kodik] Stream URL selected: ${url.take(100)}...")
        } else {
            KLog.e(TAG, "[Kodik] No stream URL found in qualities: $qualities")
        }
        url?.let {
            AnimeMediaStream(
                url = it,
                qualities = qualities,
                quality = qualities.entries.firstOrNull { entry -> entry.value == it }?.key ?: "Auto",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://kodik.info/"
                )
            )
        }
    }

    // ============================ AniStar ============================
    // v30.astar.bz: своя студия озвучки, статьи DLE с плеером-iframe (см. AniStarResolver).
    // Одна озвучка на статью → один FlatTranslation "Русская озвучка"; номера серий — из меток.

    private suspend fun fetchAniStarFlatTranslations(animeTitle: String): List<FlatTranslation> = withContext(Dispatchers.IO) {
        runCatching {
            val episodes = AniStarResolver.findEpisodes(buildAnimeSearchQueries(animeTitle))
            if (episodes == null || episodes.isEmpty()) {
                KLog.w(TAG, "[AniStar] no episodes found for \"$animeTitle\"")
                return@runCatching emptyList<FlatTranslation>()
            }
            KLog.i(TAG, "[AniStar] found \"${episodes.first().label}\"… ${episodes.size} episodes for \"$animeTitle\"")
            listOf(
                FlatTranslation(
                    source = AnimeSourceType.ANISTAR,
                    translationId = ANISTAR_TRANSLATION_ID,
                    title = ANISTAR_TITLE,
                    type = "voice",
                    episodes = episodes.map { ep ->
                        AnimeEpisode(
                            number = ep.number,
                            title = ep.label,
                            maxQuality = ep.bestQuality
                        )
                    }
                )
            )
        }.getOrElse { e ->
            KLog.e(TAG, "[AniStar] search failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchAniStarTranslations(animeTitle: String): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        val episodes = runCatching { AniStarResolver.findEpisodes(buildAnimeSearchQueries(animeTitle)) }.getOrNull()
        if (episodes.isNullOrEmpty()) return@withContext emptyList()
        listOf(
            AnimeTranslation(
                id = ANISTAR_TRANSLATION_ID,
                title = ANISTAR_TITLE,
                type = "voice",
                episodesCount = episodes.size
            )
        )
    }

    private suspend fun fetchAniStarEpisodes(animeTitle: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        runCatching { AniStarResolver.findEpisodes(buildAnimeSearchQueries(animeTitle)) }.getOrNull()
            ?.map { ep -> AnimeEpisode(number = ep.number, title = ep.label, maxQuality = ep.bestQuality) }
            ?.sortedBy { it.number }
            ?: emptyList()
    }

    private suspend fun resolveAniStarStream(animeTitle: String, episodeNumber: Int): AnimeMediaStream? = withContext(Dispatchers.IO) {
        KLog.i(TAG, "[AniStar] resolveStream: \"$animeTitle\", ep=$episodeNumber")
        val episodes = runCatching { AniStarResolver.findEpisodes(buildAnimeSearchQueries(animeTitle)) }.getOrNull()
            ?: return@withContext null
        val episode = episodes.firstOrNull { it.number == episodeNumber }
            ?: episodes.getOrNull(episodeNumber - 1)
            ?: run {
                KLog.w(TAG, "[AniStar] episode $episodeNumber not found (${episodes.size} available)")
                return@withContext null
            }
        val quality = episode.bestQuality
        val url = episode.bestUrl ?: return@withContext null
        AnimeMediaStream(
            url = url,
            qualities = episode.qualities,
            quality = quality ?: "Auto",
            headers = AniStarResolver.streamHeaders()
        )
    }

    private suspend fun fetchAniLibertyTranslations(shikimoriId: Int, animeTitle: String): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        val release = findAniLibertyRelease(shikimoriId, animeTitle) ?: return@withContext emptyList()
        val alias = release.optString("alias").ifBlank { release.optInt("id").toString() }
        listOf(
            AnimeTranslation(
                id = alias,
                title = AnimeSourceType.ANILIBERTY.displayName,
                type = "voice",
                episodesCount = release.optJSONArray("episodes")?.length() ?: release.optInt("episodes_total", 0)
            )
        )
    }

    private suspend fun fetchAniLibertyEpisodes(shikimoriId: Int, animeTitle: String, translationId: String = "default"): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        val release = findAniLibertyRelease(shikimoriId, animeTitle, translationId) ?: return@withContext emptyList()
        parseAniLibertyEpisodes(release)
    }

    private suspend fun resolveAniLibertyStream(shikimoriId: Int, animeTitle: String, episodeNumber: Int, translationId: String = "default"): AnimeMediaStream? = withContext(Dispatchers.IO) {
        KLog.i(TAG, "[Aniliberty] resolveStream: id=$shikimoriId, ep=$episodeNumber, title=\"$animeTitle\"")
        val release = findAniLibertyRelease(shikimoriId, animeTitle, translationId)
        if (release == null) {
            KLog.w(TAG, "[Aniliberty] resolveStream: release not found")
            return@withContext null
        }
        KLog.d(TAG, "[Aniliberty] resolveStream: release title=${getAniLibertyTitle(release)}, alias=${release.optString("alias")}")
        val episodes = release.optJSONArray("episodes")
        if (episodes == null) {
            KLog.w(TAG, "[Aniliberty] resolveStream: no episodes array in release")
            return@withContext null
        }
        val episode = episodes.asSequenceObjects().firstOrNull { ep ->
            ep.optInt("ordinal") == episodeNumber || ep.optInt("sort_order") == episodeNumber
        }
        if (episode == null) {
            KLog.w(TAG, "[Aniliberty] resolveStream: episode $episodeNumber not found (episodes count=${episodes.length()})")
            return@withContext null
        }
        KLog.d(TAG, "[Aniliberty] resolveStream: found episode, ordinal=${episode.optInt("ordinal")}, sort_order=${episode.optInt("sort_order")}")

        val qualities = linkedMapOf<String, String>()
        // optCleanString, а не optString: при JSON null (hls_1080: null) optString возвращает
        // буквальную строку "null", она проходила isNotBlank() и попадала в лестницу качеств —
        // video-add/mpv открывали файл "null", плеер падал в цикл переинициализаций.
        episode.optCleanString("hls_1080").takeIf { it.isNotBlank() }?.let { qualities["1080p"] = it }
        episode.optCleanString("hls_720").takeIf { it.isNotBlank() }?.let { qualities["720p"] = it }
        episode.optCleanString("hls_480").takeIf { it.isNotBlank() }?.let { qualities["480p"] = it }

        if (qualities.isEmpty()) {
            KLog.d(TAG, "[Aniliberty] resolveStream: no direct HLS, checking external_player...")
            val fallbackUrl = episode.optString("external_player")
                .ifBlank { episode.optString("player_url") }
                .ifBlank { release.optString("external_player") }
                .ifBlank { release.optString("player_url") }
                .ifBlank { release.optString("playerUrl") }
            if (fallbackUrl.isNotBlank()) {
                KLog.d(TAG, "[Aniliberty] resolveStream: external_player=$fallbackUrl")
                return@withContext resolveAniLibertyExternalPlayer(fallbackUrl)
            }
        } else {
            KLog.i(TAG, "[Aniliberty] resolveStream: direct HLS qualities: ${qualities.keys}")
        }

        val url = qualities["720p"] ?: qualities["1080p"] ?: qualities["480p"] ?: return@withContext null
        AnimeMediaStream(
            url = url,
            qualities = qualities,
            quality = qualities.entries.firstOrNull { it.value == url }?.key ?: "Auto",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://anilibria.top/",
                "Origin" to "https://anilibria.top"
            )
        )
    }

    private suspend fun resolveAniLibertyExternalPlayer(fallbackUrl: String): AnimeMediaStream? = withContext(Dispatchers.IO) {
        val resolvedUrl = if (fallbackUrl.startsWith("//")) "https:$fallbackUrl" else absoluteUrl("https://anilibria.top/", fallbackUrl)
        
        // Handle Kodik mirrors directly if possible
        if (resolvedUrl.contains("kodik") || resolvedUrl.contains("aniqit") || resolvedUrl.contains("adsterratechnology") || resolvedUrl.contains("vsh.my")) {
            val kodikQualities = resolveKodikHls(resolvedUrl)
            if (kodikQualities.isNotEmpty()) {
                val url = kodikQualities["720p"] ?: kodikQualities["480p"] ?: kodikQualities.values.firstOrNull() ?: return@withContext null
                return@withContext AnimeMediaStream(
                    url = url,
                    qualities = kodikQualities,
                    quality = kodikQualities.entries.firstOrNull { it.value == url }?.key ?: "Auto",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to resolvedUrl
                    )
                )
            }
        }

        val html = get(resolvedUrl, referer = "https://anilibria.top/") ?: return@withContext null
        val direct = extractM3u8Links(html)
        val qualities = if (direct.isNotEmpty()) {
            direct
        } else {
            // Check for file: "[...]" pattern
            val fileArrayMatch = Regex("""file:\s*(\[[\s\S]*?\])""").find(html)
            if (fileArrayMatch != null) {
                val fileArrayText = fileArrayMatch.groupValues[1]
                val items = JSONArray(fileArrayText)
                val map = linkedMapOf<String, String>()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val file = item.optString("file")
                    if (file.isNotBlank() && file.startsWith("http")) {
                        map[item.optString("title").ifBlank { "Auto" }] = file
                    }
                }
                map
            } else emptyMap()
        }

        val url = qualities["720p"] ?: qualities["1080p"] ?: qualities["480p"] ?: qualities.values.firstOrNull() ?: return@withContext null
        AnimeMediaStream(
            url = url,
            qualities = qualities,
            quality = qualities.entries.firstOrNull { it.value == url }?.key ?: "Auto",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to resolvedUrl,
                "Origin" to originFrom(resolvedUrl)
            )
        )
    }

    private suspend fun kodikSearch(shikimoriId: Int, animeTitle: String, translationId: String?): List<JSONObject> {
        val limit = if (translationId == null) 100 else 10
        val tokens = loadKodikTokens()
        KLog.i(TAG, "[Kodik] tokens loaded: ${tokens.size}, translationId=$translationId, limit=$limit")
        val orderedTokens = lastWorkingKodikToken?.let { working ->
            listOf(working) + tokens.filter { it != working }
        } ?: tokens

        for (token in orderedTokens) {
            for (base in KODIK_API_BASES) {
                if (shikimoriId > 0) {
                    val shikimoriUrl = kodikSearchUrl(base, token, "shikimori_id", shikimoriId.toString(), translationId, limit)
                    KLog.d(TAG, "[Kodik] Trying shikimori_id=$shikimoriId on $base (token=${token.take(8)}...)")
                    val body = get(shikimoriUrl, referer = "https://kodik.info/")
                    if (body != null) {
                        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
                        if (results != null && results.length() > 0) {
                            KLog.i(TAG, "[Kodik] FOUND by shikimori_id: ${results.length()} results on $base")
                            lastWorkingKodikToken = token
                            return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                        } else {
                            KLog.d(TAG, "[Kodik] shikimori_id=$shikimoriId: no results on $base")
                        }
                    } else {
                        KLog.d(TAG, "[Kodik] shikimori_id=$shikimoriId: request failed on $base")
                    }
                }

                val titleQueries = buildAnimeSearchQueries(animeTitle)
                for (query in titleQueries) {
                    val titleUrl = kodikSearchUrl(base, token, "title", query, translationId, limit)
                    KLog.d(TAG, "[Kodik] Trying title=\"$query\" on $base")
                    val tBody = get(titleUrl, referer = "https://kodik.info/") ?: continue
                    val results = runCatching { JSONObject(tBody).optJSONArray("results") }.getOrNull() ?: continue
                    if (results.length() > 0) {
                        // A Kodik title search is fuzzy: querying "Лимонные девочки" returns ~100 rows
                        // spanning 30+ unrelated shows ("Девушки и танки", "Вторжение Кальмарки", …).
                        // Keeping them all showed dubs from the wrong anime and produced duplicate
                        // translation ids in the UI list. Restrict to rows that actually belong to
                        // this title before accepting the response.
                        val raw = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                        val relevant = filterKodikResultsForTitle(raw, shikimoriId, animeTitle)
                        if (relevant.isEmpty()) {
                            KLog.d(TAG, "[Kodik] title \"$query\": ${raw.size} results, none matched id=$shikimoriId/\"$animeTitle\"")
                            continue
                        }
                        KLog.i(TAG, "[Kodik] FOUND by title \"$query\": ${relevant.size}/${raw.size} relevant results on $base")
                        lastWorkingKodikToken = token
                        return relevant
                    }
                }
            }
        }
        if (shikimoriId > 0) {
            KLog.d(TAG, "[Kodik] Trying findPlayer fallback for shikimoriId=$shikimoriId")
            fetchKodikFromFindPlayer("shikimori_id", shikimoriId)?.let {
                KLog.i(TAG, "[Kodik] findPlayer fallback SUCCESS")
                return listOf(it)
            }
            KLog.w(TAG, "[Kodik] findPlayer fallback also failed")
        }
        KLog.w(TAG, "[Kodik] All search methods exhausted for id=$shikimoriId, title=\"$animeTitle\"")
        return emptyList()
    }

    private suspend fun loadKodikTokens(): List<String> = withContext(Dispatchers.IO) {
        kodikTokensCache?.let { return@withContext it }

        val remote = runCatching {
            val body = get("https://raw.githubusercontent.com/YaNesyTortiK/AnimeParsers/main/kdk_tokns/tokens.json")
            if (body.isNullOrBlank()) emptyList() else parseKodikTokens(body)
        }.getOrDefault(emptyList())

        val tokens = (remote + KODIK_TOKEN_FALLBACKS).distinct()
        kodikTokensCache = tokens
        tokens
    }

    private fun parseKodikTokens(body: String): List<String> {
        val root = JSONObject(body)
        val groups = listOf("stable", "unstable")
        return groups.flatMap { group ->
            root.optJSONArray(group)?.asSequenceObjects()?.mapNotNull { item ->
                item.optString("tokn").takeIf { it.isNotBlank() }?.let { decodeKodikToken(it) }
            }?.toList().orEmpty()
        }.distinct()
    }

    private fun decodeKodikToken(value: String): String {
        val half = value.length / 2
        val first = value.substring(0, half).reversed()
        val second = value.substring(half).reversed()
        val decoder = Base64.getDecoder()
        return runCatching {
            decoder.decode(first).toString(Charsets.UTF_8) + decoder.decode(second).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun kodikSearchUrl(
        base: String,
        token: String,
        key: String,
        value: String,
        translationId: String?,
        limit: Int = 100
    ): String {
        val encoded = enc(value)
        return buildString {
            append(base.trimEnd('/')).append("/search?token=").append(token)
            append("&").append(key).append("=").append(encoded)
            append("&with_episodes=true")
            append("&with_material_data=true")
            append("&limit=").append(limit)
            append("&with_episodes_data=true")
            append("&with_page_links=true")
            if (!translationId.isNullOrBlank()) append("&translation_id=").append(enc(translationId))
        }
    }

    private fun extractKodikEpisodes(item: JSONObject): List<AnimeEpisode> {
        val episodes = mutableListOf<AnimeEpisode>()
        val seasons = item.optJSONObject("seasons")
        if (seasons != null) {
            seasons.keys().asSequence().forEach { seasonKey ->
                val season = seasons.optJSONObject(seasonKey)
                val seasonNumber = seasonKey.toIntOrNull()
                val items = season?.optJSONObject("episodes")
                items?.keys()?.asSequence()?.forEach episodeLoop@{ episodeKey ->
                    val number = episodeKey.toIntOrNull() ?: return@episodeLoop
                    val link = items.optJSONObject(episodeKey)?.optString("link")
                        .orEmpty()
                        .ifBlank { items.optString(episodeKey) }
                    val prefix = if (seasonNumber != null && seasonNumber > 1) "Сезон $seasonNumber, " else ""
                    episodes.add(AnimeEpisode(number = number, title = "${prefix}Серия $number", link = link, maxQuality = qualityFromLink(link, fallback = KODIK_DEFAULT_QUALITY)))
                }
            }
        }

        val directEpisodes = item.optJSONObject("episodes")
        directEpisodes?.keys()?.asSequence()?.forEach directLoop@{ key ->
            val number = key.toIntOrNull() ?: return@directLoop
            val link = directEpisodes.optJSONObject(key)?.optString("link")
                .orEmpty()
                .ifBlank { directEpisodes.optString(key) }
            episodes.add(AnimeEpisode(number = number, title = "Серия $number", link = link, maxQuality = qualityFromLink(link, fallback = KODIK_DEFAULT_QUALITY)))
        }

        // Add specials
        item.optJSONObject("specials")?.let { specials ->
            specials.keys().asSequence().forEach { key ->
                val number = key.toIntOrNull() ?: return@forEach
                val link = specials.optJSONObject(key)?.optString("link")
                    .orEmpty()
                    .ifBlank { specials.optString(key) }
                // Use a high number for specials to avoid conflict with regular episodes
                // and keep them at the end of the list.
                val specialNumber = number + 10000 
                episodes.add(AnimeEpisode(number = specialNumber, title = "Special $number", link = link, maxQuality = qualityFromLink(link, fallback = KODIK_DEFAULT_QUALITY)))
            }
        }

        return episodes.distinctBy { it.number }.sortedBy { it.number }
    }

    private fun countKodikEpisodes(item: JSONObject): Int = extractKodikEpisodes(item).size

    private suspend fun fetchHtmlWithDomainFallbacks(initialUrl: String, referer: String): Pair<String, String>? {
        // aniqit.com / kodik.info / kodi.my are NXDOMAIN globally (network moved hosts) —
        // keeping them only wasted three timeouts per extraction.
        val candidateDomains = listOf("https://kodikplayer.com", "https://w.kdkonl.com", "https://kodik-api.com")
        val parsedUrl = runCatching { URL(initialUrl) }.getOrNull()
        val pathAndQuery = if (parsedUrl != null) {
            parsedUrl.path + if (parsedUrl.query != null) "?${parsedUrl.query}" else ""
        } else initialUrl

        val urlsToTry = (listOf(initialUrl) + candidateDomains.map { "$it$pathAndQuery" }).distinct()
        for (u in urlsToTry) {
            val res = get(u, referer = referer)
            if (!res.isNullOrBlank()) {
                return Pair(res, u)
            }
        }
        return null
    }

    suspend fun resolveKodikHls(episodeUrl: String): Map<String, String> {
        KLog.d(TAG, "[Kodik] resolveHls: $episodeUrl")
        val outerResult = fetchHtmlWithDomainFallbacks(episodeUrl, referer = "https://shikimori.one/")
            ?: run {
                KLog.e(TAG, "[Kodik] resolveHls: HTML fetch failed for all domains")
                return emptyMap()
            }
        var html = outerResult.first
        var workingUrl = outerResult.second
        KLog.d(TAG, "[Kodik] resolveHls: working domain=$workingUrl, html length=${html.length}")

        val iframeSrc = Regex("""<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!iframeSrc.isNullOrBlank()) {
            val absIframe = absoluteUrl(workingUrl, iframeSrc)
            KLog.d(TAG, "[Kodik] resolveHls: found iframe, loading $absIframe")
            val iframeResult = fetchHtmlWithDomainFallbacks(absIframe, referer = workingUrl)
            if (iframeResult != null) {
                html = iframeResult.first
                workingUrl = iframeResult.second
                KLog.d(TAG, "[Kodik] resolveHls: iframe loaded from $workingUrl, html length=${html.length}")
            } else {
                KLog.w(TAG, "[Kodik] resolveHls: iframe fetch failed")
            }
        } else {
            KLog.d(TAG, "[Kodik] resolveHls: no iframe found, checking direct m3u8")
        }

        val direct = extractM3u8Links(html)
        if (direct.isNotEmpty()) {
            KLog.i(TAG, "[Kodik] resolveHls: found ${direct.size} direct m3u8 links: ${direct.keys}")
            return direct
        }

        KLog.d(TAG, "[Kodik] resolveHls: no direct m3u8, extracting payload...")
        val payload = extractKodikPayload(html)
        if (payload == null) {
            KLog.e(TAG, "[Kodik] resolveHls: payload extraction failed")
            return emptyMap()
        }
        KLog.d(TAG, "[Kodik] resolveHls: payload keys=${payload.keys}, id=${payload["id"]}, type=${payload["type"]}")

        val scriptUrl = Regex("""<script[^>]+src=["']([^"']*assets/js[^"']*)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.let { absoluteUrl(workingUrl, it) }

        var dynamicApiPath: String? = null
        if (scriptUrl != null) {
            KLog.d(TAG, "[Kodik] resolveHls: fetching JS from $scriptUrl")
            val js = get(scriptUrl, referer = workingUrl)
            if (js != null) {
                dynamicApiPath = extractKodikApiEndpoint(js)
                KLog.d(TAG, "[Kodik] resolveHls: dynamic API path = $dynamicApiPath")
            }
        }

        val candidateEndpoints = buildList {
            if (!dynamicApiPath.isNullOrBlank()) {
                add(absoluteUrl(workingUrl, dynamicApiPath))
            }
            extractQuotedPaths(html).filter { it.contains("video-links") || it.contains("/gvi") || it.contains("/ftor") || it.contains("/tri") }.forEach { add(absoluteUrl(workingUrl, it)) }
            add(absoluteUrl(workingUrl, "/ftor"))
            add(absoluteUrl(workingUrl, "/tri"))
            add(absoluteUrl(workingUrl, "/gvi"))
            add(absoluteUrl(workingUrl, "/ftor/video-links"))
            add(absoluteUrl(workingUrl, "/video-links"))
        }.distinct()
        KLog.d(TAG, "[Kodik] resolveHls: trying ${candidateEndpoints.size} endpoints")

        for (endpoint in candidateEndpoints) {
            KLog.d(TAG, "[Kodik] resolveHls: POST $endpoint")
            val body = post(endpoint, payload, referer = workingUrl) ?: run {
                KLog.d(TAG, "[Kodik] resolveHls: POST returned null for $endpoint")
                continue
            }
            KLog.d(TAG, "[Kodik] resolveHls: POST response length=${body.length}, first 200 chars=${body.take(200)}")
            val links = parseKodikLinks(body)
            if (links.isNotEmpty()) {
                KLog.i(TAG, "[Kodik] resolveHls: GOT ${links.size} links from $endpoint: ${links.keys}")
                return links
            }
        }
        KLog.e(TAG, "[Kodik] resolveHls: all endpoints failed")
        return emptyMap()
    }

    private fun extractKodikPayload(html: String): Map<String, String>? {
        val d = Regex("""var\s*domain\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""var\s*d\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return null
        val dSign = Regex("""var\s*d_sign\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val pd = Regex("""var\s*pd\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val pdSign = Regex("""var\s*pd_sign\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val ref = Regex("""var\s*ref\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val refSign = Regex("""var\s*ref_sign\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val type = Regex("""vInfo\.type\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val hash = Regex("""vInfo\.hash\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        val id = Regex("""vInfo\.id\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""

        return mapOf(
            "d" to d,
            "d_sign" to dSign,
            "pd" to pd,
            "pd_sign" to pdSign,
            "ref" to ref,
            "ref_sign" to refSign,
            "type" to type,
            "hash" to hash,
            "id" to id,
            "bad_user" to "false",
            "cdn_is_working" to "true",
            "info" to "{}"
        )
    }

    private fun extractKodikApiEndpoint(jsText: String): String? {
        val match = Regex("""\$\.ajax[^)]+atob\([\"'](\w+=*)[\"']\)""").find(jsText)?.groupValues?.getOrNull(1)
            ?: return null
        return runCatching {
            String(Base64.getDecoder().decode(match), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun parseKodikLinks(body: String): Map<String, String> {
        val extracted = extractM3u8Links(body)
        if (extracted.isNotEmpty()) return extracted

        return runCatching {
            val json = JSONObject(body)
            val links = json.optJSONObject("links") ?: json.optJSONObject("result")?.optJSONObject("links")
            val map = linkedMapOf<String, String>()
            links?.keys()?.asSequence()?.forEach { key ->
                val value = links.opt(key)
                val url = when (value) {
                    is JSONArray -> value.optJSONObject(0)?.optString("src") ?: value.optString(0)
                    is JSONObject -> value.optString("src").ifBlank { value.optString("link") }
                    is String -> value
                    else -> ""
                }
                // Только http: JSON-null "src" даёт literal "null", который не должен попадать в лестницу.
                if (url.isNotBlank() && url.startsWith("http")) map[normalizeQuality(key) ?: key] = decodeKodikUrl(url)
            }
            map
        }.getOrDefault(emptyMap())
    }

    private suspend fun findAniLibertyRelease(shikimoriId: Int, animeTitle: String, knownIdOrAlias: String? = null): JSONObject? {
        KLog.i(TAG, "[Aniliberty] findRelease: id=$shikimoriId, title=\"$animeTitle\", knownId=$knownIdOrAlias")
        val cacheKey = if (!knownIdOrAlias.isNullOrBlank() && knownIdOrAlias != "default") knownIdOrAlias else "$shikimoriId:$animeTitle"
        aniLibertyReleaseCache[cacheKey]?.let {
            KLog.d(TAG, "[Aniliberty] findRelease: cache hit for $cacheKey")
            return it
        }

        if (!knownIdOrAlias.isNullOrBlank() && knownIdOrAlias != "default") {
            for (base in ANILIBERTY_API) {
                KLog.d(TAG, "[Aniliberty] findRelease: trying knownId=$knownIdOrAlias on $base")
                fetchAniLibertyReleaseDetail(base, knownIdOrAlias)?.let {
                    KLog.i(TAG, "[Aniliberty] findRelease: found by knownId on $base")
                    aniLibertyReleaseCache[cacheKey] = it
                    return it
                }
            }
        }

        val candidateAliases = buildAnimeSearchQueries(animeTitle)
            .flatMap { query ->
                listOfNotNull(
                    slugifyAnimeTitle(query).takeIf { it.isNotBlank() },
                    query.takeIf { it.isNotBlank() }
                )
            }
            .distinct()
        KLog.d(TAG, "[Aniliberty] findRelease: ${candidateAliases.size} candidate aliases: ${candidateAliases.take(5)}")
        for (base in ANILIBERTY_API) {
            // Probe every alias candidate concurrently instead of one-by-one: a miss costs a full
            // round-trip, and 8 sequential misses dominated the episode-sheet load time.
            val byAlias = kotlinx.coroutines.coroutineScope {
                candidateAliases.map { alias ->
                    async {
                        KLog.d(TAG, "[Aniliberty] findRelease: trying alias=\"$alias\" on $base")
                        val summary = get("$base/api/v1/anime/releases/list?aliases=${enc(alias)}", referer = "https://anilibria.top/")
                            ?.let { parseAniLibertyRelease(it, alias, animeTitle) } ?: return@async null
                        fetchAniLibertyReleaseDetail(base, summary.optString("alias").ifBlank { summary.optInt("id").toString() })
                            ?.also {
                                KLog.i(TAG, "[Aniliberty] findRelease: FOUND by alias=\"$alias\" on $base, title=${getAniLibertyTitle(it)}")
                                aniLibertyReleaseCache[cacheKey] = it
                            }
                    }
                }.firstNotNullOfOrNull { it.await() }
            }
            if (byAlias != null) return byAlias

            if (animeTitle.isNotBlank()) {
                KLog.d(TAG, "[Aniliberty] findRelease: free-text search \"$animeTitle\" on $base")
                val summary = get("$base/api/v1/app/search/releases?query=${enc(animeTitle)}", referer = "https://anilibria.top/")
                    ?.let { parseAniLibertyRelease(it, slugifyAnimeTitle(animeTitle), animeTitle) } ?: run {
                    KLog.d(TAG, "[Aniliberty] findRelease: free-text search returned nothing on $base")
                    continue
                }
                val detail = fetchAniLibertyReleaseDetail(base, summary.optString("alias").ifBlank { summary.optInt("id").toString() })
                if (detail != null) {
                    KLog.i(TAG, "[Aniliberty] findRelease: FOUND by free-text on $base, title=${getAniLibertyTitle(detail)}")
                    aniLibertyReleaseCache[cacheKey] = detail
                    return detail
                }
            }
        }
        KLog.w(TAG, "[Aniliberty] findRelease: no release found for id=$shikimoriId, title=\"$animeTitle\"")
        return null
    }

    private suspend fun fetchAniLibertyReleaseDetail(base: String, idOrAlias: String): JSONObject? {
        val body = get("$base/api/v1/anime/releases/$idOrAlias", referer = "https://anilibria.top/") ?: return null
        return runCatching {
            val root = JSONObject(body)
            root.optJSONObject("data") ?: root
        }.getOrNull()
    }

    private fun parseAniLibertyRelease(body: String, preferredAlias: String? = null, expectedTitle: String? = null): JSONObject? {
        if (body.isBlank() || body == "null") return null
        val trimmed = body.trim()
        return runCatching {
            when {
                trimmed.startsWith("[") -> firstReleaseMatchingAlias(JSONArray(trimmed), preferredAlias, expectedTitle)
                else -> {
                    val json = JSONObject(trimmed)
                    val data = json.optJSONArray("data")
                        ?: json.optJSONObject("data")?.optJSONArray("items")
                        ?: json.optJSONObject("data")?.optJSONArray("releases")
                        ?: json.optJSONArray("items")
                        ?: json.optJSONArray("results")
                        ?: return@runCatching if (json.has("episodes")) json else null
                    firstReleaseMatchingAlias(data, preferredAlias, expectedTitle)
                }
            }
        }.getOrNull()
    }

    fun buildAnimeSearchQueries(animeTitle: String): List<String> {
        val raw = animeTitle.trim()
        if (raw.isBlank()) return emptyList()

        val compact = raw
            .replace(Regex("""[\[\]{}()|/,.-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val queries = mutableListOf<String>()
        queries.add(compact)

        // Try to strip season tags or trailing season numbers
        val seasonRegex = Regex("""(?i)\b(сезон|\d+\s*сезон|\b\d+\b)\s*$""")
        val cleanTitle = compact.replace(seasonRegex, "").trim()
        if (cleanTitle.length >= 3) {
            queries.add(cleanTitle)
        }

        // Add prefix queries before separators
        val separators = listOf(" / ", " | ", " - ", " — ", ": ", " (", ",", " 1 ", " 2 ", " 3 ", " 4 ", " 5 ", " 6 ", " 7 ", " 8 ", " 9 ", " 10 ")
        for (sep in separators) {
            if (raw.contains(sep)) {
                val prefix = raw.substringBefore(sep).trim()
                    .replace(Regex("""[\[\]{}()|/,.-]+"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (prefix.length >= 3) {
                    queries.add(prefix)
                }
                
                // Also add the second part
                val suffix = raw.substringAfter(sep).trim()
                    .replace(Regex("""[\[\]{}()|/,.-]+"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (suffix.length >= 3) {
                    queries.add(suffix)
                }
            }
        }

        // Add slugified version
        val slug = slugifyAnimeTitle(compact).replace("-", " ").trim()
        if (slug.length >= 3) {
            queries.add(slug)
        }

        // Filter out any short query to avoid broad searches
        return queries.distinct().filter { it.length >= 3 }
    }

    /**
     * Kodik's `title=` search is a fuzzy substring match across its whole catalogue, so a query for
     * one show routinely returns rows for dozens of unrelated ones. Narrow a raw response down to
     * rows that plausibly belong to [shikimoriId] / [animeTitle].
     *
     * Preference order:
     *  1. Rows whose `shikimori_id` equals the requested one — authoritative, so nothing else is needed.
     *  2. Otherwise rows whose title matches ours after normalisation (used when the catalogue row
     *     carries no shikimori id at all).
     */
    private fun filterKodikResultsForTitle(
        results: List<JSONObject>,
        shikimoriId: Int,
        animeTitle: String
    ): List<JSONObject> {
        if (results.isEmpty()) return emptyList()

        if (shikimoriId > 0) {
            val byId = results.filter { it.optString("shikimori_id").toIntOrNull() == shikimoriId }
            if (byId.isNotEmpty()) return byId
        }

        val expected = normalizeAnimeTitleForMatch(animeTitle)
        if (expected.isBlank()) return emptyList()

        return results.filter { item ->
            val material = item.optJSONObject("material_data")
            sequenceOf(
                item.optString("title"),
                item.optString("title_orig"),
                material?.optString("title"),
                material?.optString("anime_title"),
                material?.optString("title_en")
            ).any { candidate ->
                val normalized = normalizeAnimeTitleForMatch(candidate.orEmpty())
                normalized.isNotBlank() && (normalized == expected || normalized.startsWith("$expected ") || expected.startsWith("$normalized "))
            }
        }
    }

    /**
     * Normalises a title for equality comparison: lower-cases, folds ё→е, drops season/bracket tags
     * ("[ТВ-1]", "(фильм)") and collapses every non-alphanumeric run to a single space.
     */
    private fun normalizeAnimeTitleForMatch(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

    private suspend fun get(url: String, referer: String? = null, logTag: String? = null): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (referer != null) builder.addHeader("Referer", referer)
        if (referer != null) builder.addHeader("Origin", originFrom(referer))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                if (logTag != null) KLog.w(TAG, "$logTag HTTP ${response.code} for $url")
                null
            } else response.body.string()
        }
    }.onFailure { e ->
        if (logTag != null) KLog.w(TAG, "$logTag ${e.javaClass.simpleName}: ${e.message} for $url")
    }.getOrNull()

    /**
     * Scrapes kodik.info/find-player by an external id. The public API refuses to return some
     * rows (notably certain live-action series) that the site DB still serves through find-player,
     * so this is the last-resort lookup for both movies and series.
     */
    suspend fun kodikFindPlayerByExternalId(idType: String, id: Int): JSONObject? = fetchKodikFromFindPlayer(idType, id)

    private suspend fun fetchKodikFromFindPlayer(idType: String, id: Int): JSONObject? = withContext(Dispatchers.IO) {
        // Site domains serve a real player page (this is also the only path that works for 18+
        // titles, which the public API refuses to index). kodik.info/aniqit.com/kodik.cc are
        // NXDOMAIN globally (hosts moved) — kodikplayer.com/w.kdkonl.com are the live ones.
        val mirrors = listOf(
            "https://kodikplayer.com/find-player?$idType=$id",
            "https://w.kdkonl.com/find-player?$idType=$id"
        )
        for (url in mirrors) {
            val html = get(url, referer = "https://shikimori.one/") ?: continue
            extractKodikPlayerLink(html)?.let { link ->
                return@withContext JSONObject().apply {
                    put("link", link)
                    put("player_url", link)
                    put("episodes", JSONArray())
                }
            }
        }
        null
    }

    private fun extractKodikPlayerLink(html: String): String? {
        val iframe = Regex("""<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { absoluteUrl("https://kodik.info/", it) }
        if (!iframe.isNullOrBlank()) return iframe

        return Regex("""(https?://[^"' ]+/serial/[^"' ]+)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
    }

    private suspend fun post(url: String, params: Map<String, String>, referer: String): String? = runCatching {
        val form = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(form)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .addHeader("Origin", URL(referer).protocol + "://" + URL(referer).host)
            .addHeader("Referer", referer)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string()
        }
    }.getOrNull()

    private fun extractM3u8Links(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val regex = Regex("""(?i)(?:["']?(2160|1080|720|480|360|240)p?["']?\s*[:=]\s*)?["'](https?:\\/\\/[^"']+?\.m3u8[^"']*)["']""")
        regex.findAll(text).forEach { match ->
            val quality = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { "${it}p" }
                ?: inferQuality(match.groupValues[2])
            val url = match.groupValues[2].replace("\\/", "/")
            map[quality] = url
        }
        return map
    }

    private fun extractQuotedPaths(text: String): List<String> {
        return Regex("""["']([^"']{1,160})["']""")
            .findAll(text)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.startsWith("/") || it.startsWith("http") }
            .toList()
    }

    private fun decodeKodikUrl(value: String): String {
        val clean = value.replace("\\/", "/")
        if (clean.endsWith(".m3u8")) {
            KLog.d(TAG, "[Kodik] decodeUrl: already m3u8, returning as-is")
            return if (clean.startsWith("http")) clean else if (clean.startsWith("//")) "https:$clean" else clean
        }
        KLog.d(TAG, "[Kodik] decodeUrl: input (${clean.length} chars) = ${clean.take(80)}...")
        val cachedStep = lastWorkingRotStep
        if (cachedStep != null) {
            val result = tryRotDecode(clean, cachedStep)
            if (result != null) {
                KLog.d(TAG, "[Kodik] decodeUrl: decoded with cached ROT=$cachedStep -> ${result.take(80)}...")
                return result
            }
            KLog.d(TAG, "[Kodik] decodeUrl: cached ROT=$cachedStep failed, trying all...")
        }
        for (rot in 0 until 26) {
            val result = tryRotDecode(clean, rot)
            if (result != null) {
                lastWorkingRotStep = rot
                KLog.i(TAG, "[Kodik] decodeUrl: decoded with ROT=$rot -> ${result.take(80)}...")
                return result
            }
        }
        KLog.w(TAG, "[Kodik] decodeUrl: ALL ROT values failed, returning raw input")
        return clean
    }

    private fun tryRotDecode(encoded: String, rot: Int): String? {
        return runCatching {
            val shifted = String(encoded.map { ch ->
                when {
                    ch in 'A'..'Z' -> ((ch.code - 65 + rot) % 26 + 65).toChar()
                    ch in 'a'..'z' -> ((ch.code - 97 + rot) % 26 + 97).toChar()
                    else -> ch
                }
            }.toCharArray())
            val padding = (4 - (shifted.length % 4)) % 4
            val padded = shifted + "=".repeat(padding)
            val decodedBytes = Base64.getDecoder().decode(padded)
            val decoded = String(decodedBytes, Charsets.UTF_8)
            if (decoded.startsWith("http") || decoded.startsWith("//")) {
                if (decoded.startsWith("http")) decoded else "https:$decoded"
            } else if (decoded.contains("mp4") || decoded.contains(".m3u8")) {
                decoded
            } else null
        }.getOrNull()
    }

    fun absoluteKodikUrl(value: String): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> "https://w.kdkonl.com$value"
        else -> "https://w.kdkonl.com/$value"
    }

    private fun absoluteUrl(base: String, value: String): String {
        if (value.startsWith("http")) return value
        val parsed = base.toHttpUrlOrNull() ?: return value
        return parsed.resolve(value)?.toString() ?: value
    }

    private fun normalizeQuality(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            "2160" in lower || "4k" in lower || "uhd" in lower -> "2160p"
            "1080" in lower || "full" in lower || "fhd" in lower -> "1080p"
            "720" in lower || lower == "hd" -> "720p"
            "480" in lower || lower == "sd" -> "480p"
            "360" in lower || "low" in lower -> "360p"
            "240" in lower -> "240p"
            else -> null
        }
    }

    private fun inferQuality(url: String): String {
        return normalizeQuality(url) ?: "Auto"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun originFrom(url: String): String {
        return runCatching {
            val parsed = URL(url)
            "${parsed.protocol}://${parsed.host}" + parsed.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        }.getOrDefault(url)
    }

    private fun slugifyAnimeTitle(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""[^\p{L}\p{Nd}]+"""), "-")
            .trim('-')
    }

    private fun titlesMatch(expected: String, actual: String): Boolean {
        val normalize = { s: String ->
            s.lowercase().replace(Regex("""[^\p{L}\p{Nd}]+"""), " ").trim()
        }
        val nExpected = normalize(expected)
        val nActual = normalize(actual)
        return nExpected == nActual ||
            nExpected.contains(nActual) ||
            nActual.contains(nExpected)
    }

    private fun isAnimeRelease(release: JSONObject): Boolean {
        val type = release.optString("type").lowercase().trim()
        return type.contains("anime") || type.contains("serial")
    }

    private fun JSONArray.asSequenceObjects(): Sequence<JSONObject> = sequence {
        for (i in 0 until length()) {
            optJSONObject(i)?.let { yield(it) }
        }
    }

    private fun firstReleaseMatchingAlias(data: JSONArray, preferredAlias: String?, expectedTitle: String? = null): JSONObject? {
        if (data.length() == 0) {
            KLog.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: empty data")
            return null
        }
        val items = data.asSequenceObjects().toList()
        KLog.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: ${items.size} items, alias=$preferredAlias, expectedTitle=$expectedTitle")
        items.forEachIndexed { i, item ->
            val t = getAniLibertyTitle(item, false)
            val te = getAniLibertyTitle(item, true)
            val al = item.optString("alias")
            val tp = item.optString("type")
            KLog.d(TAG, "  [$i] alias=$al, type=$tp, title=\"$t\", english=\"$te\"")
        }

        preferredAlias?.takeIf { it.isNotBlank() }?.let { alias ->
            items.firstOrNull { item ->
                item.optString("alias").equals(alias, ignoreCase = true) ||
                    getAniLibertyTitle(item, false).equals(alias, ignoreCase = true) ||
                    getAniLibertyTitle(item, true).equals(alias, ignoreCase = true) ||
                    slugifyAnimeTitle(getAniLibertyTitle(item, false)).equals(alias, ignoreCase = true) ||
                    slugifyAnimeTitle(getAniLibertyTitle(item, true)).equals(alias, ignoreCase = true)
            }?.let {
                KLog.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: matched by alias=$alias -> ${getAniLibertyTitle(it)}")
                return it
            }
        }

        if (!expectedTitle.isNullOrBlank()) {
            val byType = items.firstOrNull { item ->
                isAnimeRelease(item) && (
                    titlesMatch(expectedTitle, getAniLibertyTitle(item, false)) ||
                    titlesMatch(expectedTitle, getAniLibertyTitle(item, true))
                )
            }
            if (byType != null) {
                KLog.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: matched by title+type -> ${getAniLibertyTitle(byType)}")
                return byType
            }

            val byTitle = items.firstOrNull { item ->
                titlesMatch(expectedTitle, getAniLibertyTitle(item, false)) ||
                titlesMatch(expectedTitle, getAniLibertyTitle(item, true))
            }
            if (byTitle != null) {
                KLog.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: matched by title only -> ${getAniLibertyTitle(byTitle)}")
                return byTitle
            }
        }

        if (!expectedTitle.isNullOrBlank()) {
            KLog.w(TAG, "[Aniliberty] firstReleaseMatchingAlias: no title match for expectedTitle=\"$expectedTitle\", refusing fallback")
            return null
        }

        KLog.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: falling back to first item -> ${getAniLibertyTitle(items.first())}")
        return items.first()
    }

    private fun getAniLibertyTitle(release: JSONObject, useEnglish: Boolean = false): String {
        val nameObj = release.optJSONObject("name")
        return if (useEnglish) {
            nameObj?.optString("english")?.ifBlank { nameObj.optString("main") }
                ?: release.optString("name").ifBlank { "AniLiberty" }
        } else {
            nameObj?.optString("main")
                ?: release.optString("name").ifBlank { "AniLiberty" }
        }
    }

    // ============================================================
    // AniLib (animelib.org) — отдельный источник, НЕ путать с AniLiberty/AniLibria.
    // Новое REST API на api.animelib.org (DDoS-Guard, доступен из РФ, в отличие от
    // Cloudflare-фронта animelib.org и мёртвого anilib.me):
    //   /api/anime?search=<q>        → тайтлы c shikimori_href (id Shikimori внутри)
    //   /api/episodes?anime_id=<id>  → серии
    //   /api/episodes/<episodeId>    → players[]: team, translation_type (Озвучка/Субтитры),
    //                                  src = ссылка на Kodik-плеер → HLS через resolveKodikHls
    // Каждая команда — отдельная озвучка ("v<i>|<метка>" / "s<i>|<метка>").
    // ============================================================

    private val anilibReleaseCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val anilibEpisodesCache = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<List<AnimeEpisode>>>()

    // ------------------------------------------------------------
    // AniLib legacy (api.anilib.me v3) — прямые HLS-ссылки команд с
    // качествами до 4K, в отличие от Kodik-эмбедов нового API.
    // Домен TLS-блокирован в РФ без VPN, поэтому каждый запрос короткий
    // по таймауту, а любой сбой откатывает на новый api.animelib.org.
    // Форматы ответов не документированы — парсинг максимально терпимый.
    // ------------------------------------------------------------

    private val ANILIB_LEGACY_API = listOf("https://api.anilib.me")

    private const val LEGACY_REQUEST_TIMEOUT_MS = 9_000L

    private val anilibLegacyCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<List<LegacyRow>>>()
    private val anilibLegacyDetailCache = java.util.concurrent.ConcurrentHashMap<Int, CacheEntry<JSONObject>>()

    /** hls-key → "NNNNp": терпимо к именованию sd/hd/fullHd/4k разных поколений API. */
    private fun legacyQualityFromKey(key: String): String? {
        val k = key.lowercase()
        return when {
            "2160" in k || "4k" in k || "uhd" in k -> "2160p"
            "1440" in k || k == "2k" -> "1440p"
            "1080" in k || "fullhd" in k || "full_hd" in k || "fhd" in k -> "1080p"
            "720" in k || k == "hd" -> "720p"
            "480" in k || k == "sd" -> "480p"
            else -> null
        }
    }

    /** Корневой data-объект ответа: терпит {"data": {...}} и прямой объект. */
    private fun legacyDataObject(body: String?): JSONObject? {
        if (body.isNullOrBlank() || body == "null") return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return root.optJSONObject("data") ?: root.takeIf { it.has("playlist") || it.has("hls") || it.has("id") }
    }

    private fun legacyArray(root: JSONObject?, vararg names: String): JSONArray? =
        names.firstNotNullOfOrNull { root?.optJSONArray(it)?.takeIf { arr -> arr.length() > 0 } }

    /** Название команды/студии из записи плейлиста; поля гуляют между версиями API. */
    private fun legacyEntryLabel(entry: JSONObject): String {
        val fromNested = sequenceOf("studio", "team", "translation", "voice")
            .mapNotNull { entry.optJSONObject(it) }
            .mapNotNull { it.optCleanString("name").ifBlank { it.optCleanString("title") }.ifBlank { null } }
            .firstOrNull()
        return (fromNested
            ?: entry.optCleanString("studio_name").ifBlank {
                entry.optCleanString("team_name").ifBlank { entry.optCleanString("name") }
            })
            .ifBlank { "Озвучка AniLib" }
    }

    private fun legacyEntryIsSub(entry: JSONObject): Boolean {
        val tt = entry.optJSONObject("translation_type")?.optCleanString("label")
        val type = entry.optCleanString("type")
        return listOf(tt, type).any { it?.contains("субтитр", true) == true || it?.contains("sub", true) == true }
    }

    /** Карта качество→m3u8 одной записи плейлиста. */
    private fun legacyEntryQualities(entry: JSONObject): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val hls = entry.optJSONObject("hls")
        if (hls != null) {
            hls.keys().asSequence().forEach { key ->
                val url = hls.optString(key)
                val q = legacyQualityFromKey(key)
                if (q != null && url.startsWith("http")) map[q] = url
            }
        }
        if (map.isEmpty()) {
            sequenceOf("url", "src", "link", "file")
                .mapNotNull { entry.optString(it).takeIf { u -> u.startsWith("http") } }
                .firstOrNull()
                ?.let { direct -> map[inferQuality(direct)] = direct }
        }
        return map
    }

    private suspend fun findAniLibLegacyTitle(shikimoriId: Int, animeTitle: String): JSONObject? {
        val queries = buildAnimeSearchQueries(animeTitle).ifEmpty { listOf(animeTitle) }
        for (base in ANILIB_LEGACY_API) {
            for (query in queries.take(3)) {
                val body = withTimeoutOrNull(LEGACY_REQUEST_TIMEOUT_MS) {
                    get("$base/api/v3/anime/search?search=${enc(query)}&limit=20", referer = "https://anilib.me/", logTag = "[AniLib.Legacy]")
                } ?: continue
                pickAniLibTitle(body, shikimoriId, animeTitle)?.let { found ->
                    return JSONObject().apply {
                        put("id", found.optInt("id"))
                        put("name", found.optString("name", animeTitle))
                        put("ruTitle", found.optString("rus_name").ifBlank { found.optString("name", animeTitle) })
                        put("base", base)
                    }
                }
            }
        }
        return null
    }

    /**
     * Полный легаси-конвейер: поиск тайтла → серии → детали с плейлистами → строки по командам,
     * где для каждой серии хранится ВСЯ карта качеств. Пустой результат на любом звене означает
     * «легаси недоступен» — вызывающий код откатывается на новый api.animelib.org.
     */
    private data class LegacyRow(
        val kind: String,
        val label: String,
        /** номер серии → карта качество→m3u8 */
        val episodes: MutableMap<Int, LinkedHashMap<String, String>> = LinkedHashMap()
    )

    private suspend fun buildAniLibLegacyRows(shikimoriId: Int, animeTitle: String): List<LegacyRow> = withContext(Dispatchers.IO) {
        val title = findAniLibLegacyTitle(shikimoriId, animeTitle) ?: return@withContext emptyList()
        val base = title.optString("base")
        val animeId = title.optInt("id")

        val episodeRoot = withTimeoutOrNull(LEGACY_REQUEST_TIMEOUT_MS) {
            get("$base/api/v3/anime/$animeId/episodes", referer = "https://anilib.me/", logTag = "[AniLib.Legacy]")
        } ?: return@withContext emptyList()
        val episodesArr = legacyArray(legacyDataObject(episodeRoot), "data", "items", "episodes")
            ?: return@withContext emptyList()

        data class LegacyEp(val number: Int, val id: Int?)
        val eps = episodesArr.asSequenceObjects().mapNotNull { item ->
            val number = item.optInt("item_number").takeIf { it > 0 }
                ?: item.optString("number").toIntOrNull().takeIf { (it ?: 0) > 0 }
                ?: item.optInt("ordinal").takeIf { it > 0 }
                ?: return@mapNotNull null
            LegacyEp(number, item.optInt("id").takeIf { it > 0 })
        }.distinctBy { it.number }.sortedBy { it.number }.toList()
        if (eps.isEmpty()) return@withContext emptyList()

        val rows = LinkedHashMap<String, LegacyRow>()
        for (ep in eps) {
            val detailId = ep.id ?: continue
            val detail = fetchAniLibLegacyDetail(base, animeId, detailId) ?: continue
            val playlist = legacyArray(detail, "playlist", "videos", "players") ?: continue
            playlist.asSequenceObjects().forEach { entry ->
                val qualities = legacyEntryQualities(entry)
                if (qualities.isEmpty()) return@forEach
                val label = legacyEntryLabel(entry)
                val isSub = legacyEntryIsSub(entry)
                val key = "${if (isSub) 's' else 'v'}|${label.lowercase().replace('|', ' ').trim()}"
                val row = rows.getOrPut(key) {
                    LegacyRow(if (isSub) "sub" else "voice", label)
                }
                row.episodes.putIfAbsent(ep.number, LinkedHashMap(qualities))
            }
        }
        val result = rows.values.filter { it.episodes.isNotEmpty() }
        if (result.isNotEmpty()) {
            KLog.i(TAG, "[AniLib.Legacy] built ${result.size} rows (${result.sumOf { it.episodes.size }} episode entries)")
        }
        result
    }

    private suspend fun fetchAniLibLegacyDetail(base: String, animeId: Int, episodeId: Int): JSONObject? {
        anilibLegacyDetailCache[episodeId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) return entry.data
            anilibLegacyDetailCache.remove(episodeId)
        }
        // Точная форма эндпоинта деталей не документирована — пробуем оба расклада.
        val candidates = listOf(
            "$base/api/v3/episode/$episodeId",
            "$base/api/v3/anime/$animeId/episode/$episodeId"
        )
        for (url in candidates) {
            val body = withTimeoutOrNull(LEGACY_REQUEST_TIMEOUT_MS) {
                get(url, referer = "https://anilib.me/", logTag = "[AniLib.Legacy]")
            } ?: continue
            val obj = legacyDataObject(body) ?: continue
            if (legacyArray(obj, "playlist", "videos", "players") != null) {
                anilibLegacyDetailCache[episodeId] = CacheEntry(obj, System.currentTimeMillis())
                return obj
            }
        }
        return null
    }

    private suspend fun cachedAniLibLegacyRows(shikimoriId: Int, animeTitle: String): List<LegacyRow> {
        val cacheKey = "$shikimoriId:${animeTitle.trim().lowercase()}"
        anilibLegacyCache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) return entry.data
            anilibLegacyCache.remove(cacheKey)
        }
        val rows = buildAniLibLegacyRows(shikimoriId, animeTitle)
        if (rows.isNotEmpty()) {
            anilibLegacyCache[cacheKey] = CacheEntry(rows, System.currentTimeMillis())
        }
        return rows
    }

    private fun LegacyRow.toFlatTranslation(index: Int): FlatTranslation = FlatTranslation(
        source = AnimeSourceType.ANILIB,
        translationId = "L$index|$label",
        title = label,
        type = kind,
        episodes = episodes.map { (number, qualities) ->
            val best = qualities.maxByOrNull { qualityRank(it.key) }!!
            AnimeEpisode(number = number, link = best.value, maxQuality = best.key)
        }.sortedBy { it.number }
    )

    /** Легаси-озвучки для листа выбора; пусто, если старый API недоступен. */
    private suspend fun buildAniLibLegacyTeamTranslations(shikimoriId: Int, animeTitle: String): List<FlatTranslation> =
        cachedAniLibLegacyRows(shikimoriId, animeTitle).mapIndexed { i, row -> row.toFlatTranslation(i) }

    /** Стрим из легаси-плейлиста: все качества команды, лучший — дефолтным URL. */
    private suspend fun resolveAniLibLegacyStream(
        shikimoriId: Int,
        animeTitle: String,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? = withContext(Dispatchers.IO) {
        val rows = cachedAniLibLegacyRows(shikimoriId, animeTitle)
        val index = translationId.removePrefix("L").substringBefore('|').toIntOrNull() ?: return@withContext null
        val labelKey = translationId.substringAfter('|').lowercase()
        val row = rows.getOrNull(index)?.takeIf { it.label.lowercase() == labelKey } ?: return@withContext null
        val qualities = row.episodes[episodeNumber]?.takeIf { it.isNotEmpty() } ?: return@withContext null

        val best = qualities.maxByOrNull { qualityRank(it.key) }!!
        AnimeMediaStream(
            url = best.value,
            qualities = LinkedHashMap(qualities),
            quality = best.key,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://anilib.me/",
                "Origin" to "https://anilib.me"
            )
        )
    }


    /** Episode-detail json (with players[]) by episode id; TTL-shared with resolveStream for id stability. */
    private suspend fun fetchAniLibEpisodeDetail(base: String, episodeId: Int): JSONObject? {
        anilibEpisodeCache[episodeId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < HLS_CACHE_TTL_MS) return entry.data
            anilibEpisodeCache.remove(episodeId)
        }
        val body = get("$base/api/episodes/$episodeId", referer = "https://animelib.org/", logTag = "[AniLib]") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        anilibEpisodeCache[episodeId] = CacheEntry(json, System.currentTimeMillis())
        return json
    }

    /** Shikimori id parsed from the title's shikimori_href ("https://shikimori.io/animes/30276"). */
    private fun aniLibShikiId(item: JSONObject): Int =
        Regex("""/animes/(\d+)""").find(item.optString("shikimori_href"))
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private suspend fun findAniLibRelease(shikimoriId: Int, animeTitle: String): JSONObject? {
        KLog.i(TAG, "[AniLib] findRelease: id=$shikimoriId, title=\"$animeTitle\"")
        val cacheKey = "$shikimoriId:$animeTitle"
        anilibReleaseCache[cacheKey]?.let { return it }

        val queries = buildAnimeSearchQueries(animeTitle).ifEmpty { listOf(animeTitle) }
        for (base in ANILIB_API) {
            // Concurrent query probes (see findAniLibertyRelease): sequential misses dominated
            // the episode sheet's wall time.
            val found = kotlinx.coroutines.coroutineScope {
                queries.map { query ->
                    async {
                        // The current API ignores "search=" and always answers with a fixed
                        // popular-top list; only "q=" performs a real title lookup.
                        val body = get("$base/api/anime?q=${enc(query)}", referer = "https://animelib.org/", logTag = "[AniLib]")
                            ?: return@async null
                        pickAniLibTitle(body, shikimoriId, animeTitle) ?: return@async null
                    }
                }.firstNotNullOfOrNull { it.await() }
            }
            if (found != null) {
                val release = JSONObject().apply {
                    put("id", found.optInt("id"))
                    put("name", found.optString("name", animeTitle))
                    put("ruTitle", found.optString("rus_name").ifBlank { found.optString("name", animeTitle) })
                    put("base", base)
                }
                anilibReleaseCache[cacheKey] = release
                KLog.i(TAG, "[AniLib] found title id=${release.optInt("id")} \"${getAniLibTitle(release)}\" on $base")
                return release
            }
        }
        KLog.w(TAG, "[AniLib] no release found for id=$shikimoriId, title=\"$animeTitle\"")
        return null
    }

    private fun pickAniLibTitle(body: String, shikimoriId: Int, expectedTitle: String): JSONObject? {
        if (body.isBlank() || body == "null") return null
        val titles = runCatching {
            val root = JSONObject(body.trim())
            val arr = when {
                root.optJSONArray("data") != null -> root.optJSONArray("data")
                root.optJSONArray("items") != null -> root.optJSONArray("items")
                root.has("id") -> JSONArray().put(root)
                else -> null
            } ?: return@runCatching emptyList<JSONObject>()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrDefault(emptyList())
        if (titles.isEmpty()) return null
        // Authoritative: the title whose shikimori_href carries our shikimori id.
        if (shikimoriId > 0) {
            titles.firstOrNull { aniLibShikiId(it) == shikimoriId }?.let { return it }
        }
        // Fuzzy fallback by name, then first (the search is fuzzy and may not contain the title).
        return titles.firstOrNull { t ->
            titlesMatch(expectedTitle, t.optString("rus_name")) ||
                titlesMatch(expectedTitle, t.optString("eng_name")) ||
                titlesMatch(expectedTitle, t.optString("name"))
        } ?: titles.firstOrNull().also {
            KLog.d(TAG, "[AniLib] pickAniLibTitle: no shiki/title match, taking first result id=${it?.optInt("id")}")
        }
    }

    /** Episode list of an AniLib title: GET /api/episodes?anime_id=<id>. */
    private suspend fun fetchAniLibEpisodeList(base: String, animeId: Int): List<AnimeEpisode> {
        anilibEpisodesCache[animeId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) return entry.data
            anilibEpisodesCache.remove(animeId)
        }
        val body = get("$base/api/episodes?anime_id=$animeId", referer = "https://animelib.org/", logTag = "[AniLib]")
        val episodes = runCatching {
            val root = JSONObject(body.orEmpty())
            val arr = root.optJSONArray("data") ?: return@runCatching emptyList<AnimeEpisode>()
            arr.asSequenceObjects().mapNotNull { item ->
                val number = item.optInt("item_number").takeIf { it > 0 }
                    ?: item.optString("number").toIntOrNull().takeIf { (it ?: 0) > 0 }
                    ?: return@mapNotNull null
                val title = item.optCleanString("name").ifBlank { "Серия $number" }
                AnimeEpisode(number = number, title = title, id = item.optInt("id").takeIf { it > 0 })
            }.distinctBy { it.number }.sortedBy { it.number }.toList()
        }.getOrDefault(emptyList())
        if (episodes.isNotEmpty()) {
            anilibEpisodesCache[animeId] = CacheEntry(episodes, System.currentTimeMillis())
        }
        return episodes
    }

    private fun getAniLibTitle(release: JSONObject): String =
        release.optString("ruTitle").ifBlank { release.optString("name") }.ifBlank { "AniLib" }

    /** players[] of an episode-detail json; tolerates a {"data": {...}} wrapper. */
    private fun aniLibPlayers(detail: JSONObject): List<JSONObject> {
        val direct = detail.optJSONArray("players")
        if (direct != null) return direct.asSequenceObjects().toList()
        val data = detail.optJSONObject("data") ?: return emptyList()
        return data.optJSONArray("players")?.asSequenceObjects().orEmpty().toList()
    }

    /** Team display label ("AniLibria.TV", "OnWave", …); falls back to slug, then generic. */
    private fun aniLibPlayerLabel(player: JSONObject): String {
        val team = player.optJSONObject("team")
        val label = sequenceOf(
            team?.optString("name"),
            team?.optString("slug"),
            player.optString("name")
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        return label.ifBlank { "Озвучка AniLib" }
    }

    /** translation_type: {id: 2, label: "Озвучка"} / {id: 1, label: "Субтитры"}; unknown → voice. */
    private fun aniLibPlayerIsVoice(player: JSONObject): Boolean {
        val tt = player.optJSONObject("translation_type")
        val label = tt?.optString("label").orEmpty().lowercase()
        if (label.contains("субтитр") || label.contains("sub")) return false
        if (label.contains("озвучк") || label.contains("dub") || label.contains("voice")) return true
        return tt?.optInt("id", 2) != 1
    }

    /** Kodik player link of a player ("//kodikplayer.com/seria/…/720p"), made absolute. */
    private fun aniLibPlayerSrc(player: JSONObject): String? {
        val raw = player.optString("src").replace("\\/", "/").trim()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> "https://kodikplayer.com/${raw.trimStart('/')}"
        }
    }

    /**
     * One FlatTranslation per AniLib voice/sub team (like Kodik dubs). Episode details are fetched
     * once here and land in [anilibEpisodeCache]; resolveStream reads the same cached json, so
     * "v<i>|<label>" ids keep matching the players arrays they were built from. A label is embedded
     * in the id because player ordering can shift between refetches — matching goes by label first,
     * index second. If no details expose players, fall back to a single row with all episodes.
     */
    private suspend fun buildAniLibTeamTranslations(shikimoriId: Int, animeTitle: String): List<FlatTranslation> = withContext(Dispatchers.IO) {
        KLog.i(TAG, "[AniLib] buildTeamTranslations: id=$shikimoriId, title=\"$animeTitle\"")
        // Легаси anilib.me первым: прямые HLS команд до 4K. Недоступен (нет VPN/блок) — тихий
        // откат на новый api.animelib.org с Kodik-эмбедами (максимум 720p).
        val legacy = runCatching {
            withTimeoutOrNull(15_000L) { buildAniLibLegacyTeamTranslations(shikimoriId, animeTitle) }
        }.getOrNull().orEmpty()
        if (legacy.isNotEmpty()) return@withContext legacy
        KLog.i(TAG, "[AniLib] legacy unavailable, falling back to api.animelib.org")

        val release = findAniLibRelease(shikimoriId, animeTitle)
        if (release == null) {
            KLog.w(TAG, "[AniLib] No release found for \"$animeTitle\"")
            return@withContext emptyList()
        }
        val base = release.optString("base").ifBlank { ANILIB_API.first() }
        val episodes = fetchAniLibEpisodeList(base, release.optInt("id"))
        KLog.i(TAG, "[AniLib] Found: \"${getAniLibTitle(release)}\" (${episodes.size} episodes)")
        if (episodes.isEmpty()) return@withContext emptyList()

        val detailByIds = kotlinx.coroutines.coroutineScope {
            val sem = Semaphore(6)
            episodes.mapNotNull { it.id }.map { id ->
                async { sem.withPermit { id to fetchAniLibEpisodeDetail(base, id) } }
            }.awaitAll()
        }
        val detailById = HashMap<Int, JSONObject>()
        for ((id, detail) in detailByIds) detail?.let { detailById[id] = it }

        data class TeamRow(val kind: String, val label: String, val eps: MutableList<AnimeEpisode>)
        val rows = LinkedHashMap<String, TeamRow>()

        for (ep in episodes) {
            val detail = ep.id?.let { detailById[it] } ?: continue
            val players = aniLibPlayers(detail)
            // Only teams that actually carry a Kodik link for this episode produce playable rows.
            val usable = players.filter { !aniLibPlayerSrc(it).isNullOrBlank() }
            for (player in usable) {
                val voice = aniLibPlayerIsVoice(player)
                val label = aniLibPlayerLabel(player)
                val key = "${if (voice) 'v' else 's'}|${label.lowercase()}"
                // The episode object is shared across teams: stamp each row with the quality of
                // its own player link (Kodik embeds carry their top variant in the path).
                val rowEpisode = ep.copy(maxQuality = qualityFromLink(aniLibPlayerSrc(player), fallback = KODIK_DEFAULT_QUALITY))
                rows.getOrPut(key) { TeamRow(if (voice) "voice" else "sub", label, mutableListOf()) }
                    .eps.add(rowEpisode)
            }
        }

        if (rows.isEmpty()) {
            KLog.i(TAG, "[AniLib] no team players resolved, falling back to single row")
            return@withContext listOf(
                FlatTranslation(
                    source = AnimeSourceType.ANILIB,
                    translationId = "default",
                    title = getAniLibTitle(release),
                    type = "voice",
                    episodes = episodes
                )
            )
        }

        val translations = rows.values.mapIndexed { i, row ->
            FlatTranslation(
                source = AnimeSourceType.ANILIB,
                translationId = "${if (row.kind == "voice") 'v' else 's'}$i|${row.label.replace('|', ' ')}",
                title = row.label,
                type = row.kind,
                episodes = row.eps.distinctBy { it.number }.sortedBy { it.number }
            )
        }
        KLog.i(TAG, "[AniLib] teams: ${translations.joinToString { "${it.title}[${it.translationId}](${it.episodes.size})" }}")
        translations
    }

    private suspend fun fetchAniLibTranslations(shikimoriId: Int, animeTitle: String): List<AnimeTranslation> =
        buildAniLibTeamTranslations(shikimoriId, animeTitle).map {
            AnimeTranslation(id = it.translationId, title = it.title, type = it.type, episodesCount = it.episodes.size)
        }

    private suspend fun fetchAniLibEpisodes(shikimoriId: Int, animeTitle: String, translationId: String): List<AnimeEpisode> {
        val release = findAniLibRelease(shikimoriId, animeTitle) ?: return emptyList()
        val base = release.optString("base").ifBlank { ANILIB_API.first() }
        return fetchAniLibEpisodeList(base, release.optInt("id"))
    }

    private suspend fun resolveAniLibStream(shikimoriId: Int, animeTitle: String, episodeNumber: Int, translationId: String): AnimeMediaStream? {
        KLog.i(TAG, "[AniLib] resolveStream: id=$shikimoriId, ep=$episodeNumber, tr=$translationId")
        val release = findAniLibRelease(shikimoriId, animeTitle) ?: return null
        val base = release.optString("base").ifBlank { ANILIB_API.first() }
        val episodes = fetchAniLibEpisodeList(base, release.optInt("id"))
        val ep = episodes.firstOrNull { it.number == episodeNumber } ?: run {
            KLog.w(TAG, "[AniLib] Episode $episodeNumber not found (${episodes.size} known)")
            return null
        }
        val episodeId = ep.id ?: return null

        // Cache-first: the same json the team list was built from, so label/index ids align.
        val detail = fetchAniLibEpisodeDetail(base, episodeId) ?: return null
        val players = aniLibPlayers(detail)
        if (players.isEmpty()) {
            KLog.w(TAG, "[AniLib] episode $episodeId has no players")
            return null
        }

        val wantSubs = translationId.startsWith("s") && translationId != "default"
        val pool = players.filter { aniLibPlayerIsVoice(it) != wantSubs }.ifEmpty { players }
        val requestedLabel = translationId.substringAfter('|', "").takeIf { it.isNotBlank() }
        val selected = requestedLabel?.let { lbl ->
            pool.firstOrNull { aniLibPlayerLabel(it).equals(lbl, ignoreCase = true) }
        } ?: translationId.drop(1).substringBefore('|').toIntOrNull()?.let { pool.getOrNull(it) } ?: pool.first()

        // Players carry a Kodik embed link ("//kodikplayer.com/seria/…/720p"); reuse the
        // existing Kodik HLS extraction instead of a bespoke scraper.
        val src = aniLibPlayerSrc(selected) ?: run {
            KLog.w(TAG, "[AniLib] player \"${aniLibPlayerLabel(selected)}\" has no src link")
            return null
        }
        KLog.d(TAG, "[AniLib] resolving kodik hls for \"${aniLibPlayerLabel(selected)}\": $src")
        val qualities = getCachedKodikHls(src) ?: resolveKodikHls(src).also {
            if (it.isNotEmpty()) kodikHlsCache[src] = CacheEntry(it, System.currentTimeMillis())
        }
        if (qualities.isEmpty()) {
            KLog.w(TAG, "[AniLib] kodik hls extraction failed for $src")
            return null
        }
        val url = qualities["720p"] ?: qualities["1080p"] ?: qualities["480p"] ?: qualities["360p"] ?: qualities.values.first()
        KLog.i(TAG, "[AniLib] resolved \"${aniLibPlayerLabel(selected)}\" qualities: ${qualities.keys}")
        return AnimeMediaStream(
            url = url,
            qualities = qualities,
            quality = qualities.entries.firstOrNull { it.value == url }?.key ?: "Auto",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://kodik.info/"
            )
        )
    }

    // ============================================================
    // Torrents (offline download). AniLiberty releases carry a `torrents` array (quality,
    // seeders/leechers, size, magnet, .torrent url) that we currently discard. This surfaces it
    // so the details-screen download button can hand a magnet/.torrent to an external client.
    // No torrent engine is bundled — this only resolves links, it does not download.
    // ============================================================

    /** Маркер HEVC в текстовых полях раздачи (hevc/x265/h265/h.265, регистр не важен). */
    internal fun hasHevcMarker(vararg texts: String?): Boolean {
        val combined = texts.filterNotNull().joinToString(" ").lowercase()
        return "hevc" in combined || "x265" in combined ||
            "h265" in combined || "h.265" in combined
    }

    data class TorrentLink(
        val quality: String,
        val size: String,
        val seeders: Int,
        val leechers: Int,
        val magnet: String?,
        val torrentUrl: String?,
        /** Диапазон серий / название раздачи («1-12», имя релиза у Rutor). */
        val label: String? = null,
        /** btih-хэш — из него строится magnet, когда ссылка на .torrent умерла. */
        val hash: String? = null,
        /** Дата залития («2024-01-02»). */
        val uploadedAt: String? = null,
        /** Каталог-источник раздачи («AniLiberty», «AniStar», «Rutor»). */
        val source: String = "AniLiberty",
        /** Видеокодек («HEVC»), если источник его сообщает; null — неизвестен. */
        val codec: String? = null
    ) {
        /** Основная ссылка для отдачи в ОС: magnet предпочтительнее — живёт дольше .torrent. */
        val primaryUri: String? get() = magnet ?: torrentUrl
        /** Дополнительная ссылка (когда есть обе). */
        val secondaryUri: String? get() = if (magnet != null) torrentUrl else null
    }

    internal suspend fun kodikSearchByKinopoiskId(kinopoiskId: Int): List<JSONObject> {
        val tokens = loadKodikTokens()
        for (token in tokens) {
            for (base in KODIK_API_BASES) {
                val url = kodikSearchUrl(base, token, "kinopoisk_id", kinopoiskId.toString(), null, 10)
                val body = get(url, referer = "https://kodik.info/") ?: continue
                val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: continue
                if (results.length() > 0) {
                    return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                }
            }
        }
        return emptyList()
    }

    /**
     * Searches Kodik by kinopoisk_id and returns the embed player URL (iframe link).
     * Used as a fallback when DDBB API is unreachable.
     */
    internal enum class KodikSearchFailure { NONE, PROVIDER, NETWORK }

    internal data class KodikMovieSearchResult(
        val items: List<JSONObject> = emptyList(),
        val failure: KodikSearchFailure = KodikSearchFailure.NONE
    )

    internal suspend fun kodikSearchMovieByTitle(title: String): KodikMovieSearchResult =
        kodikSearchMovieField("title", title, 20)

    internal suspend fun kodikSearchMovieByImdbId(imdbId: String): KodikMovieSearchResult =
        kodikSearchMovieField("imdb_id", imdbId, 10)

    internal suspend fun kodikSearchMovieByKinopoiskId(id: Int): KodikMovieSearchResult =
        kodikSearchMovieField("kinopoisk_id", id.toString(), 10)

    private suspend fun kodikSearchMovieField(key: String, value: String, limit: Int): KodikMovieSearchResult {
        val tokens = loadKodikTokens()
        var sawProviderFailure = false
        var sawNetworkFailure = false
        for (token in tokens) {
            for (base in KODIK_API_BASES) {
                val url = kodikSearchUrl(base, token, key, value, null, limit)
                val request = Request.Builder().url(url).addHeader("User-Agent", USER_AGENT).build()
                val response = runCatching { client.newCall(request).execute() }.getOrElse {
                    sawNetworkFailure = true
                    continue
                }
                response.use {
                    val body = it.body.string()
                    if (!it.isSuccessful) {
                        val error = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                        if (error.contains("токен", ignoreCase = true) || it.code >= 500) sawProviderFailure = true
                        continue
                    }
                    val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
                    if (results != null) {
                        val items = (0 until results.length()).mapNotNull(results::optJSONObject)
                        return KodikMovieSearchResult(items)
                    }
                }
            }
        }
        return KodikMovieSearchResult(
            failure = when {
                sawProviderFailure -> KodikSearchFailure.PROVIDER
                sawNetworkFailure -> KodikSearchFailure.NETWORK
                else -> KodikSearchFailure.NONE
            }
        )
    }

    fun kodikPlaybackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://kodik.info/"
    )

    suspend fun fetchKodikEmbedForKinopoisk(kinopoiskId: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val results = kodikSearchByKinopoiskId(kinopoiskId)
            val first = results.firstOrNull() ?: return@runCatching null
            // The result has a "link" field which is the embed iframe URL
            var link = first.optString("link", "")
            if (link.isBlank()) link = first.optString("iframe_url", "")
            if (link.isBlank()) return@runCatching null
            if (link.startsWith("//")) link = "https:$link"
            link
        }.getOrNull()
    }

    suspend fun fetchTorrents(shikimoriId: Int, animeTitle: String): List<TorrentLink> = withContext(Dispatchers.IO) {
        runCatching {
            // findAniLibertyRelease already fetches the full release JSON (which includes torrents).
            val release = findAniLibertyRelease(shikimoriId, animeTitle) ?: return@runCatching emptyList()
            // torrents may live at top-level or nested under data.
            val torrents = release.optJSONArray("torrents")
                ?: release.optJSONObject("data")?.optJSONArray("torrents")
                ?: return@runCatching emptyList()
            (0 until torrents.length()).mapNotNull { i ->
                val t = torrents.optJSONObject(i) ?: return@mapNotNull null

                // Parse quality string / object (AniLiberty sometimes formats quality as {"value":"720p","description":"720p"})
                val qObj = t.optJSONObject("quality")
                val rawQuality = if (qObj != null) {
                    qObj.optString("description").ifBlank { qObj.optString("value") }
                } else {
                    val str = t.optString("quality")
                    if (str.startsWith("{")) {
                        runCatching {
                            val parsed = org.json.JSONObject(str)
                            parsed.optString("description").ifBlank { parsed.optString("value") }
                        }.getOrNull() ?: str
                    } else str
                }
                val quality = rawQuality.ifBlank { t.optString("resolution") }.ifBlank { "?" }

                // Размер приходит либо готовой строкой («1.36 GB»), либо числом байт.
                val sizeStr = t.optString("size").ifBlank { t.optString("total_size") }
                val size = sizeStr.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { hd.kinoshka.app.data.download.formatBytes(it) }
                    ?: sizeStr.ifBlank { "?" }

                val hash = t.optString("hash").takeIf { it.isNotBlank() }
                // magnet строится из btih-хэша: у AniLiberty поля magnet часто нет вовсе.
                val magnet = t.optString("magnet").ifBlank { t.optString("magnet_link") }.takeIf { it.isNotBlank() }
                    ?: hash?.takeIf { it.length == 40 || it.length == 32 }
                        ?.let { "magnet:?xt=urn:btih:${it.lowercase()}" }
                var torrentUrl = t.optString("torrent_url").ifBlank { t.optString("url") }.takeIf { it.isNotBlank() }
                if (torrentUrl?.startsWith("/") == true) torrentUrl = "https://anilibria.top$torrentUrl"

                if (magnet == null && torrentUrl == null) return@mapNotNull null

                // Кодек явно полем отдают редко — ищем маркеры HEVC по всем текстовым
                // полям раздачи (качество, описание, имя файла из metadata).
                val metadataName = t.optJSONObject("metadata")?.optString("name")
                val codec = if (hasHevcMarker(
                        t.optString("codec"), rawQuality,
                        t.optString("description"), metadataName
                    )
                ) "HEVC" else null

                TorrentLink(
                    quality = quality,
                    size = size,
                    seeders = t.optInt("seeders", t.optInt("peers", 0)),
                    leechers = t.optInt("leechers", 0),
                    magnet = magnet,
                    torrentUrl = torrentUrl,
                    label = t.optString("description").ifBlank { null },
                    hash = hash,
                    uploadedAt = t.optString("uploaded_datetime").takeIf { it.isNotBlank() }?.substringBefore("T"),
                    source = "AniLiberty"
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchFilmTorrents(title: String, year: String?): List<TorrentLink> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanTitle = title.trim()
            if (cleanTitle.isBlank()) return@runCatching emptyList()
            val searchQuery = if (!year.isNullOrBlank()) "$cleanTitle $year" else cleanTitle
            val encoded = java.net.URLEncoder.encode(searchQuery, "UTF-8")
            val mirrors = listOf("https://rutor.info", "https://rutor.is")
            var html = ""
            var baseUrl = ""
            for (mirror in mirrors) {
                runCatching {
                    val req = Request.Builder()
                        .url("$mirror/search/0/0/000/0/$encoded")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            html = resp.body.string().orEmpty()
                            baseUrl = mirror
                        }
                    }
                }
                if (html.isNotBlank()) break
            }
            if (html.isBlank()) return@runCatching emptyList()

            val results = mutableListOf<TorrentLink>()
            val rowRegex = Regex("""<tr class="(?:gai|tum)">.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
            val magnetRegex = Regex("""href="(magnet:\?[^"]+)"""")
            val torrentUrlRegex = Regex("""href="(/torrent/[^"]+)"""")
            val titleRegex = Regex("""<a href="/torrent/[^"]+">([^<]+)</a>""")
            val sizeRegex = Regex("""<td align="right">([0-9\.\s]+(?:GB|MB|MiB|GiB|TB))</td>""", RegexOption.IGNORE_CASE)
            val seedsRegex = Regex("""<span class="green">(\d+)</span>""")
            val leechesRegex = Regex("""<span class="red">(\d+)</span>""")

            rowRegex.findAll(html).take(20).forEach { match ->
                val row = match.value
                val magnet = magnetRegex.find(row)?.groupValues?.get(1)
                val torrentPath = torrentUrlRegex.find(row)?.groupValues?.get(1)
                val fullTorrentUrl = torrentPath?.let { "$baseUrl$it" }
                val tTitle = titleRegex.find(row)?.groupValues?.get(1)?.trim() ?: ""
                if (magnet == null && fullTorrentUrl == null) return@forEach

                val quality = when {
                    tTitle.contains("2160p", true) || tTitle.contains("4K", true) -> "4K UHD"
                    tTitle.contains("1080p", true) -> "1080p"
                    tTitle.contains("720p", true) -> "720p"
                    tTitle.contains("480p", true) -> "480p"
                    else -> tTitle.take(35)
                }

                val size = sizeRegex.find(row)?.groupValues?.get(1)?.trim() ?: "?"
                val seeders = seedsRegex.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val leechers = leechesRegex.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                results.add(
                    TorrentLink(
                        quality = quality,
                        size = size,
                        seeders = seeders,
                        leechers = leechers,
                        magnet = magnet,
                        torrentUrl = fullTorrentUrl,
                        label = tTitle.takeIf { it.isNotBlank() },
                        source = "Rutor",
                        codec = if (hasHevcMarker(tTitle)) "HEVC" else null
                    )
                )
            }
            results
        }.getOrDefault(emptyList())
    }
}
