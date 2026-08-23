package hd.kinoshka.app.data.source

import android.util.Log
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSource
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.AnimeTranslation
import hd.kinoshka.app.data.model.FlatTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
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

    private val KODIK_TOKEN_FALLBACKS = listOf(
        "56a768d08f43091901c44b54fe970049",
        "41dd95f84c21719b09d6c71182237a25",
        "77b567ec164db6ca9162d2f3dc4948c3"
    )

    private val KODIK_API_BASES = listOf(
        "https://kodik-api.com",
        "https://kodikapi.com"
    )

    private val ANILIBERTY_API = listOf(
        "https://anilibria.top",
        "https://api.anilibria.pro",
        "https://api.anilibria.tv"
    )

    // AniLib (AniLibria v2 API) — a distinct source mirror set, kept separate from ANILIBERTY
    // (which uses the v1 API). Surfacing both gives the user a fallback when one is down.
    private val ANILIB_API = listOf(
        "https://api.anilibria.tv",
        "https://api.anilibria.pro",
        "https://anilibria.top"
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
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
    private val resolveStreamCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<AnimeMediaStream>>()
    private val aniLibertyReleaseCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache

    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchAvailableSources(shikimoriId: Int, animeTitle: String = ""): List<AnimeSource> = withContext(Dispatchers.IO) {
        listOf(
            AnimeSource(AnimeSourceType.KODIK, isAvailable = true),
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
            val title = episode.optString("name").ifBlank {
                episode.optString("name_english").ifBlank { "Серия $number" }
            }
            AnimeEpisode(number = number, title = title, id = episode.optInt("id").takeIf { it > 0 })
        }.distinctBy { it.number }.sortedBy { it.number }.toList()
    }

    suspend fun prefetchAllMedia(shikimoriId: Int, animeTitle: String): List<FlatTranslation> {
        val cacheKey = "$shikimoriId:${animeTitle.trim().lowercase()}"
        prefetchAllMediaCache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return entry.data
            } else {
                prefetchAllMediaCache.remove(cacheKey)
            }
        }

        val loaded = prefetchAllMediaInternal(shikimoriId, animeTitle)
        if (loaded.isNotEmpty()) {
            prefetchAllMediaCache[cacheKey] = CacheEntry(loaded, System.currentTimeMillis())
        }
        return loaded
    }

    private suspend fun prefetchAllMediaInternal(shikimoriId: Int, animeTitle: String): List<FlatTranslation> = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== prefetchAllMedia === id=$shikimoriId, title=\"$animeTitle\"")
        kotlinx.coroutines.coroutineScope {
            val deferredKodik = async {
                runCatching {
                    Log.i(TAG, "[Kodik] Starting search...")
                    val results = kodikSearch(shikimoriId, animeTitle, null)
                    Log.i(TAG, "[Kodik] Search returned ${results.size} results")
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
                    Log.i(TAG, "[Kodik] Parsed ${translations.size} translations: ${translations.joinToString { "${it.title} (${it.episodes.size} ep)" }}")
                    translations
                }.getOrElse { e ->
                    Log.e(TAG, "[Kodik] Search failed: ${e.message}", e)
                    emptyList()
                }
            }

            val deferredAniLiberty = async {
                runCatching {
                    Log.i(TAG, "[Aniliberty] Starting search...")
                    val release = findAniLibertyRelease(shikimoriId, animeTitle)
                    if (release != null) {
                        val episodes = parseAniLibertyEpisodes(release)
                        val title = getAniLibertyTitle(release)
                        Log.i(TAG, "[Aniliberty] Found: \"$title\" (${episodes.size} episodes), alias=${release.optString("alias")}, type=${release.optString("type")}")
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
                                title = title,
                                type = "voice",
                                episodes = episodes
                            )
                        )
                    } else {
                        Log.w(TAG, "[Aniliberty] No release found for \"$animeTitle\"")
                        emptyList()
                    }
                }.getOrElse { e ->
                    Log.e(TAG, "[Aniliberty] Search failed: ${e.message}", e)
                    emptyList()
                }
            }

            val deferredAniLib = async {
                runCatching {
                    Log.i(TAG, "[AniLib] Starting search...")
                    val release = findAniLibRelease(shikimoriId, animeTitle)
                    if (release != null) {
                        val episodes = parseAniLibEpisodes(release)
                        val title = getAniLibTitle(release)
                        Log.i(TAG, "[AniLib] Found: \"$title\" (${episodes.size} episodes), code=${release.optString("code")}")
                        listOf(
                            FlatTranslation(
                                source = AnimeSourceType.ANILIB,
                                // Distinct from the Kodik/AniLiberty fallback ids for the same reason
                                // (AnimeControls matches on translationId alone). AniLib playback
                                // re-resolves via findAniLibRelease and ignores this id, so any unique
                                // value is safe here.
                                translationId = release.optInt("id").takeIf { it > 0 }?.toString() ?: "default",
                                title = title,
                                type = "voice",
                                episodes = episodes
                            )
                        )
                    } else {
                        Log.w(TAG, "[AniLib] No release found for \"$animeTitle\"")
                        emptyList()
                    }
                }.getOrElse { e ->
                    Log.e(TAG, "[AniLib] Search failed: ${e.message}", e)
                    emptyList()
                }
            }

            val kodikResult = deferredKodik.await()
            val anilibertyResult = deferredAniLiberty.await()
            val anilibResult = deferredAniLib.await()
            Log.i(TAG, "=== prefetchAllMedia DONE === Kodik: ${kodikResult.size}, Aniliberty: ${anilibertyResult.size}, AniLib: ${anilibResult.size}")
            mergeTranslations(kodikResult + anilibertyResult + anilibResult)
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
                val episodes = (existing.episodes + translation.episodes)
                    .distinctBy { it.number }
                    .sortedBy { it.number }
                // Prefer whichever row carried a real episode list; titles are identical per id.
                existing.copy(episodes = episodes)
            }
        }
        if (merged.size != translations.size) {
            Log.i(TAG, "Merged ${translations.size} translations into ${merged.size} unique dubs")
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
            AnimeSourceType.ANILIBERTY -> fetchAniLibertyTranslations(shikimoriId, animeTitle)
            AnimeSourceType.ANILIB -> fetchAniLibTranslations(shikimoriId, animeTitle)
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
            AnimeSourceType.ANILIBERTY -> fetchAniLibertyEpisodes(shikimoriId, animeTitle, translationId)
            AnimeSourceType.ANILIB -> fetchAniLibEpisodes(shikimoriId, animeTitle, translationId)
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

    private suspend fun resolveStreamInternal(
        shikimoriId: Int,
        animeTitle: String,
        sourceType: AnimeSourceType,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.KODIK -> resolveKodikStream(shikimoriId, animeTitle, translationId, episodeNumber)
            AnimeSourceType.ANILIBERTY -> resolveAniLibertyStream(shikimoriId, animeTitle, episodeNumber, translationId)
            AnimeSourceType.ANILIB -> resolveAniLibStream(shikimoriId, animeTitle, episodeNumber, translationId)
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
        Log.i(TAG, "[Kodik] resolveStream: id=$shikimoriId, ep=$episodeNumber, tr=$translationId")
        val episode = fetchKodikEpisodes(shikimoriId, animeTitle, translationId).firstOrNull { it.number == episodeNumber }
        if (episode == null) {
            Log.w(TAG, "[Kodik] Episode $episodeNumber not found")
            return@withContext null
        }
        val episodeLink = episode.link?.takeIf { it.isNotBlank() }
        if (episodeLink == null) {
            Log.w(TAG, "[Kodik] Episode $episodeNumber has no link")
            return@withContext null
        }
        Log.d(TAG, "[Kodik] Episode link: ${absoluteKodikUrl(episodeLink)}")
        val qualities = resolveKodikHls(absoluteKodikUrl(episodeLink))
        Log.i(TAG, "[Kodik] HLS qualities: ${qualities.keys}")
        val url = qualities["720p"] ?: qualities["480p"] ?: qualities["360p"] ?: qualities.values.firstOrNull()
        if (url != null) {
            Log.i(TAG, "[Kodik] Stream URL selected: ${url.take(100)}...")
        } else {
            Log.e(TAG, "[Kodik] No stream URL found in qualities: $qualities")
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

    private suspend fun fetchAniLibertyTranslations(shikimoriId: Int, animeTitle: String): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        val release = findAniLibertyRelease(shikimoriId, animeTitle) ?: return@withContext emptyList()
        val alias = release.optString("alias").ifBlank { release.optInt("id").toString() }
        listOf(
            AnimeTranslation(
                id = alias,
                title = getAniLibertyTitle(release),
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
        Log.i(TAG, "[Aniliberty] resolveStream: id=$shikimoriId, ep=$episodeNumber, title=\"$animeTitle\"")
        val release = findAniLibertyRelease(shikimoriId, animeTitle, translationId)
        if (release == null) {
            Log.w(TAG, "[Aniliberty] resolveStream: release not found")
            return@withContext null
        }
        Log.d(TAG, "[Aniliberty] resolveStream: release title=${getAniLibertyTitle(release)}, alias=${release.optString("alias")}")
        val episodes = release.optJSONArray("episodes")
        if (episodes == null) {
            Log.w(TAG, "[Aniliberty] resolveStream: no episodes array in release")
            return@withContext null
        }
        val episode = episodes.asSequenceObjects().firstOrNull { ep ->
            ep.optInt("ordinal") == episodeNumber || ep.optInt("sort_order") == episodeNumber
        }
        if (episode == null) {
            Log.w(TAG, "[Aniliberty] resolveStream: episode $episodeNumber not found (episodes count=${episodes.length()})")
            return@withContext null
        }
        Log.d(TAG, "[Aniliberty] resolveStream: found episode, ordinal=${episode.optInt("ordinal")}, sort_order=${episode.optInt("sort_order")}")

        val qualities = linkedMapOf<String, String>()
        episode.optString("hls_1080").takeIf { it.isNotBlank() }?.let { qualities["1080p"] = it }
        episode.optString("hls_720").takeIf { it.isNotBlank() }?.let { qualities["720p"] = it }
        episode.optString("hls_480").takeIf { it.isNotBlank() }?.let { qualities["480p"] = it }

        if (qualities.isEmpty()) {
            Log.d(TAG, "[Aniliberty] resolveStream: no direct HLS, checking external_player...")
            val fallbackUrl = episode.optString("external_player")
                .ifBlank { episode.optString("player_url") }
                .ifBlank { release.optString("external_player") }
                .ifBlank { release.optString("player_url") }
                .ifBlank { release.optString("playerUrl") }
            if (fallbackUrl.isNotBlank()) {
                Log.d(TAG, "[Aniliberty] resolveStream: external_player=$fallbackUrl")
                return@withContext resolveAniLibertyExternalPlayer(fallbackUrl)
            }
        } else {
            Log.i(TAG, "[Aniliberty] resolveStream: direct HLS qualities: ${qualities.keys}")
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
                    if (file.isNotBlank()) {
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
        Log.i(TAG, "[Kodik] tokens loaded: ${tokens.size}, translationId=$translationId, limit=$limit")
        val orderedTokens = lastWorkingKodikToken?.let { working ->
            listOf(working) + tokens.filter { it != working }
        } ?: tokens

        for (token in orderedTokens) {
            for (base in KODIK_API_BASES) {
                if (shikimoriId > 0) {
                    val shikimoriUrl = kodikSearchUrl(base, token, "shikimori_id", shikimoriId.toString(), translationId, limit)
                    Log.d(TAG, "[Kodik] Trying shikimori_id=$shikimoriId on $base (token=${token.take(8)}...)")
                    val body = get(shikimoriUrl, referer = "https://kodik.info/")
                    if (body != null) {
                        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
                        if (results != null && results.length() > 0) {
                            Log.i(TAG, "[Kodik] FOUND by shikimori_id: ${results.length()} results on $base")
                            lastWorkingKodikToken = token
                            return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                        } else {
                            Log.d(TAG, "[Kodik] shikimori_id=$shikimoriId: no results on $base")
                        }
                    } else {
                        Log.d(TAG, "[Kodik] shikimori_id=$shikimoriId: request failed on $base")
                    }
                }

                val titleQueries = buildAnimeSearchQueries(animeTitle)
                for (query in titleQueries) {
                    val titleUrl = kodikSearchUrl(base, token, "title", query, translationId, limit)
                    Log.d(TAG, "[Kodik] Trying title=\"$query\" on $base")
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
                            Log.d(TAG, "[Kodik] title \"$query\": ${raw.size} results, none matched id=$shikimoriId/\"$animeTitle\"")
                            continue
                        }
                        Log.i(TAG, "[Kodik] FOUND by title \"$query\": ${relevant.size}/${raw.size} relevant results on $base")
                        lastWorkingKodikToken = token
                        return relevant
                    }
                }
            }
        }
        if (shikimoriId > 0) {
            Log.d(TAG, "[Kodik] Trying findPlayer fallback for shikimoriId=$shikimoriId")
            fetchKodikFromFindPlayer(shikimoriId)?.let {
                Log.i(TAG, "[Kodik] findPlayer fallback SUCCESS")
                return listOf(it)
            }
            Log.w(TAG, "[Kodik] findPlayer fallback also failed")
        }
        Log.w(TAG, "[Kodik] All search methods exhausted for id=$shikimoriId, title=\"$animeTitle\"")
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
                    episodes.add(AnimeEpisode(number = number, title = "${prefix}Серия $number", link = link))
                }
            }
        }

        val directEpisodes = item.optJSONObject("episodes")
        directEpisodes?.keys()?.asSequence()?.forEach directLoop@{ key ->
            val number = key.toIntOrNull() ?: return@directLoop
            val link = directEpisodes.optJSONObject(key)?.optString("link")
                .orEmpty()
                .ifBlank { directEpisodes.optString(key) }
            episodes.add(AnimeEpisode(number = number, title = "Серия $number", link = link))
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
                episodes.add(AnimeEpisode(number = specialNumber, title = "Special $number", link = link))
            }
        }

        return episodes.distinctBy { it.number }.sortedBy { it.number }
    }

    private fun countKodikEpisodes(item: JSONObject): Int = extractKodikEpisodes(item).size

    private suspend fun fetchHtmlWithDomainFallbacks(initialUrl: String, referer: String): Pair<String, String>? {
        val candidateDomains = listOf("https://vsh.my", "https://w.kdkonl.com", "https://kodik-api.com", "https://aniqit.com", "https://kodik.info", "https://kodikplayer.com", "https://kodi.my")
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

    internal suspend fun resolveKodikHls(episodeUrl: String): Map<String, String> {
        Log.d(TAG, "[Kodik] resolveHls: $episodeUrl")
        val outerResult = fetchHtmlWithDomainFallbacks(episodeUrl, referer = "https://shikimori.one/")
            ?: run {
                Log.e(TAG, "[Kodik] resolveHls: HTML fetch failed for all domains")
                return emptyMap()
            }
        var html = outerResult.first
        var workingUrl = outerResult.second
        Log.d(TAG, "[Kodik] resolveHls: working domain=$workingUrl, html length=${html.length}")

        val iframeSrc = Regex("""<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!iframeSrc.isNullOrBlank()) {
            val absIframe = absoluteUrl(workingUrl, iframeSrc)
            Log.d(TAG, "[Kodik] resolveHls: found iframe, loading $absIframe")
            val iframeResult = fetchHtmlWithDomainFallbacks(absIframe, referer = workingUrl)
            if (iframeResult != null) {
                html = iframeResult.first
                workingUrl = iframeResult.second
                Log.d(TAG, "[Kodik] resolveHls: iframe loaded from $workingUrl, html length=${html.length}")
            } else {
                Log.w(TAG, "[Kodik] resolveHls: iframe fetch failed")
            }
        } else {
            Log.d(TAG, "[Kodik] resolveHls: no iframe found, checking direct m3u8")
        }

        val direct = extractM3u8Links(html)
        if (direct.isNotEmpty()) {
            Log.i(TAG, "[Kodik] resolveHls: found ${direct.size} direct m3u8 links: ${direct.keys}")
            return direct
        }

        Log.d(TAG, "[Kodik] resolveHls: no direct m3u8, extracting payload...")
        val payload = extractKodikPayload(html)
        if (payload == null) {
            Log.e(TAG, "[Kodik] resolveHls: payload extraction failed")
            return emptyMap()
        }
        Log.d(TAG, "[Kodik] resolveHls: payload keys=${payload.keys}, id=${payload["id"]}, type=${payload["type"]}")

        val scriptUrl = Regex("""<script[^>]+src=["']([^"']*assets/js[^"']*)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.let { absoluteUrl(workingUrl, it) }

        var dynamicApiPath: String? = null
        if (scriptUrl != null) {
            Log.d(TAG, "[Kodik] resolveHls: fetching JS from $scriptUrl")
            val js = get(scriptUrl, referer = workingUrl)
            if (js != null) {
                dynamicApiPath = extractKodikApiEndpoint(js)
                Log.d(TAG, "[Kodik] resolveHls: dynamic API path = $dynamicApiPath")
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
        Log.d(TAG, "[Kodik] resolveHls: trying ${candidateEndpoints.size} endpoints")

        for (endpoint in candidateEndpoints) {
            Log.d(TAG, "[Kodik] resolveHls: POST $endpoint")
            val body = post(endpoint, payload, referer = workingUrl) ?: run {
                Log.d(TAG, "[Kodik] resolveHls: POST returned null for $endpoint")
                continue
            }
            Log.d(TAG, "[Kodik] resolveHls: POST response length=${body.length}, first 200 chars=${body.take(200)}")
            val links = parseKodikLinks(body)
            if (links.isNotEmpty()) {
                Log.i(TAG, "[Kodik] resolveHls: GOT ${links.size} links from $endpoint: ${links.keys}")
                return links
            }
        }
        Log.e(TAG, "[Kodik] resolveHls: all endpoints failed")
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
                if (url.isNotBlank()) map[normalizeQuality(key) ?: key] = decodeKodikUrl(url)
            }
            map
        }.getOrDefault(emptyMap())
    }

    private suspend fun findAniLibertyRelease(shikimoriId: Int, animeTitle: String, knownIdOrAlias: String? = null): JSONObject? {
        Log.i(TAG, "[Aniliberty] findRelease: id=$shikimoriId, title=\"$animeTitle\", knownId=$knownIdOrAlias")
        val cacheKey = if (!knownIdOrAlias.isNullOrBlank() && knownIdOrAlias != "default") knownIdOrAlias else "$shikimoriId:$animeTitle"
        aniLibertyReleaseCache[cacheKey]?.let {
            Log.d(TAG, "[Aniliberty] findRelease: cache hit for $cacheKey")
            return it
        }

        if (!knownIdOrAlias.isNullOrBlank() && knownIdOrAlias != "default") {
            for (base in ANILIBERTY_API) {
                Log.d(TAG, "[Aniliberty] findRelease: trying knownId=$knownIdOrAlias on $base")
                fetchAniLibertyReleaseDetail(base, knownIdOrAlias)?.let {
                    Log.i(TAG, "[Aniliberty] findRelease: found by knownId on $base")
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
        Log.d(TAG, "[Aniliberty] findRelease: ${candidateAliases.size} candidate aliases: ${candidateAliases.take(5)}")
        for (base in ANILIBERTY_API) {
            for (alias in candidateAliases) {
                Log.d(TAG, "[Aniliberty] findRelease: trying alias=\"$alias\" on $base")
                val summary = get("$base/api/v1/anime/releases/list?aliases=${enc(alias)}", referer = "https://anilibria.top/")
                    ?.let { parseAniLibertyRelease(it, alias, animeTitle) } ?: run {
                    Log.d(TAG, "[Aniliberty] findRelease: no results for alias=\"$alias\" on $base")
                    continue
                }
                val detail = fetchAniLibertyReleaseDetail(base, summary.optString("alias").ifBlank { summary.optInt("id").toString() })
                if (detail != null) {
                    Log.i(TAG, "[Aniliberty] findRelease: FOUND by alias=\"$alias\" on $base, title=${getAniLibertyTitle(detail)}")
                    aniLibertyReleaseCache[cacheKey] = detail
                    return detail
                }
            }

            if (animeTitle.isNotBlank()) {
                Log.d(TAG, "[Aniliberty] findRelease: free-text search \"$animeTitle\" on $base")
                val summary = get("$base/api/v1/app/search/releases?query=${enc(animeTitle)}", referer = "https://anilibria.top/")
                    ?.let { parseAniLibertyRelease(it, slugifyAnimeTitle(animeTitle), animeTitle) } ?: run {
                    Log.d(TAG, "[Aniliberty] findRelease: free-text search returned nothing on $base")
                    continue
                }
                val detail = fetchAniLibertyReleaseDetail(base, summary.optString("alias").ifBlank { summary.optInt("id").toString() })
                if (detail != null) {
                    Log.i(TAG, "[Aniliberty] findRelease: FOUND by free-text on $base, title=${getAniLibertyTitle(detail)}")
                    aniLibertyReleaseCache[cacheKey] = detail
                    return detail
                }
            }
        }
        Log.w(TAG, "[Aniliberty] findRelease: no release found for id=$shikimoriId, title=\"$animeTitle\"")
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

    private fun buildAnimeSearchQueries(animeTitle: String): List<String> {
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

    private suspend fun get(url: String, referer: String? = null): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (referer != null) builder.addHeader("Referer", referer)
        if (referer != null) builder.addHeader("Origin", originFrom(referer))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private suspend fun fetchKodikFromFindPlayer(shikimoriId: Int): JSONObject? = withContext(Dispatchers.IO) {
        val mirrors = listOf(
            "https://kodikapi.com/find-player?shikimori_id=$shikimoriId",
            "https://kodik-api.com/find-player?shikimori_id=$shikimoriId",
            "https://kodik.info/find-player?shikimori_id=$shikimoriId",
            "https://aniqit.com/find-player?shikimori_id=$shikimoriId"
        )
        for (url in mirrors) {
            val html = get(url, referer = "https://shikimori.one/animes/$shikimoriId") ?: continue
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
            if (!response.isSuccessful) null else response.body?.string()
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
            Log.d(TAG, "[Kodik] decodeUrl: already m3u8, returning as-is")
            return if (clean.startsWith("http")) clean else if (clean.startsWith("//")) "https:$clean" else clean
        }
        Log.d(TAG, "[Kodik] decodeUrl: input (${clean.length} chars) = ${clean.take(80)}...")
        val cachedStep = lastWorkingRotStep
        if (cachedStep != null) {
            val result = tryRotDecode(clean, cachedStep)
            if (result != null) {
                Log.d(TAG, "[Kodik] decodeUrl: decoded with cached ROT=$cachedStep -> ${result.take(80)}...")
                return result
            }
            Log.d(TAG, "[Kodik] decodeUrl: cached ROT=$cachedStep failed, trying all...")
        }
        for (rot in 0 until 26) {
            val result = tryRotDecode(clean, rot)
            if (result != null) {
                lastWorkingRotStep = rot
                Log.i(TAG, "[Kodik] decodeUrl: decoded with ROT=$rot -> ${result.take(80)}...")
                return result
            }
        }
        Log.w(TAG, "[Kodik] decodeUrl: ALL ROT values failed, returning raw input")
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

    internal fun absoluteKodikUrl(value: String): String = when {
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
            Log.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: empty data")
            return null
        }
        val items = data.asSequenceObjects().toList()
        Log.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: ${items.size} items, alias=$preferredAlias, expectedTitle=$expectedTitle")
        items.forEachIndexed { i, item ->
            val t = getAniLibertyTitle(item, false)
            val te = getAniLibertyTitle(item, true)
            val al = item.optString("alias")
            val tp = item.optString("type")
            Log.d(TAG, "  [$i] alias=$al, type=$tp, title=\"$t\", english=\"$te\"")
        }

        preferredAlias?.takeIf { it.isNotBlank() }?.let { alias ->
            items.firstOrNull { item ->
                item.optString("alias").equals(alias, ignoreCase = true) ||
                    getAniLibertyTitle(item, false).equals(alias, ignoreCase = true) ||
                    getAniLibertyTitle(item, true).equals(alias, ignoreCase = true) ||
                    slugifyAnimeTitle(getAniLibertyTitle(item, false)).equals(alias, ignoreCase = true) ||
                    slugifyAnimeTitle(getAniLibertyTitle(item, true)).equals(alias, ignoreCase = true)
            }?.let {
                Log.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: matched by alias=$alias -> ${getAniLibertyTitle(it)}")
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
                Log.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: matched by title+type -> ${getAniLibertyTitle(byType)}")
                return byType
            }

            val byTitle = items.firstOrNull { item ->
                titlesMatch(expectedTitle, getAniLibertyTitle(item, false)) ||
                titlesMatch(expectedTitle, getAniLibertyTitle(item, true))
            }
            if (byTitle != null) {
                Log.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: matched by title only -> ${getAniLibertyTitle(byTitle)}")
                return byTitle
            }
        }

        if (!expectedTitle.isNullOrBlank()) {
            Log.w(TAG, "[Aniliberty] firstReleaseMatchingAlias: no title match for expectedTitle=\"$expectedTitle\", refusing fallback")
            return null
        }

        Log.d(TAG, "[Aniliberty] firstReleaseMatchingAlias: falling back to first item -> ${getAniLibertyTitle(items.first())}")
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
    // AniLib (anilib.me) — a distinct source from AniLiberty/AniLibria.
    // Modeled after ShikiWatch's AniLib integration: search → title (shikiId/id),
    // getPlaylist(title.id) → episodes, getEpisode(episodeId) → players (teams) with
    // video[{quality,href}] and a videoHost; stream url = host + href.
    // ============================================================

    private val anilibReleaseCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    private suspend fun findAniLibRelease(shikimoriId: Int, animeTitle: String): JSONObject? {
        Log.i(TAG, "[AniLib] findRelease: id=$shikimoriId, title=\"$animeTitle\"")
        val cacheKey = "$shikimoriId:$animeTitle"
        anilibReleaseCache[cacheKey]?.let { return it }

        val queries = buildAnimeSearchQueries(animeTitle).ifEmpty { listOf(animeTitle) }
        for (base in ANILIB_API) {
            for (query in queries) {
                val body = get("$base/api/v2/searchTitles?search=${enc(query)}", referer = "https://anilib.me/")
                    ?: continue
                val title = pickAniLibTitle(body, shikimoriId, animeTitle) ?: continue
                // Resolve to a full playlist/release object so callers can read episodes + players.
                val playlist = get("$base/api/v2/playlist?id=${title.optInt("id")}", referer = "https://anilib.me/")
                    ?: continue
                val release = JSONObject().apply {
                    put("id", title.optInt("id"))
                    put("shikiId", title.optInt("shikiId", shikimoriId))
                    put("name", title.optString("name", animeTitle))
                    put("ruTitle", title.optString("ruTitle", title.optString("name", animeTitle)))
                    put("base", base)
                    try { put("playlist", JSONArray(playlist)) } catch (e: Exception) { put("playlistRaw", playlist) }
                }
                anilibReleaseCache[cacheKey] = release
                Log.i(TAG, "[AniLib] found title id=${title.optInt("id")} on $base")
                return release
            }
        }
        Log.w(TAG, "[AniLib] no release found for id=$shikimoriId, title=\"$animeTitle\"")
        return null
    }

    private fun pickAniLibTitle(body: String, shikimoriId: Int, expectedTitle: String): JSONObject? {
        if (body.isBlank() || body == "null") return null
        return runCatching {
            val root = JSONObject(body)
            val arr = root.optJSONArray("items")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("titles")
                ?: if (root.has("id")) JSONArray().also { it.put(root) } else JSONArray(body.trim().ifEmpty { "[]" })
            // Prefer a title whose shikiId matches; else first whose name loosely matches.
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { t ->
                t.optInt("shikiId", -1) == shikimoriId || t.optInt("shikimoriId", -1) == shikimoriId
            } ?: (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { t ->
                val n = (t.optString("name") + " " + t.optString("ruTitle") + " " + t.optString("enTitle")).lowercase()
                n.contains(expectedTitle.lowercase().take(5))
            } ?: arr.optJSONObject(0)
        }.getOrNull()
    }

    private fun parseAniLibEpisodes(release: JSONObject): List<AnimeEpisode> {
        val arr = release.optJSONArray("playlist") ?: return emptyList()
        val episodes = mutableListOf<AnimeEpisode>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val number = item.optInt("episode")
                .takeIf { it > 0 }
                ?: item.optInt("episodeNumber", -1).takeIf { it > 0 }
                ?: item.optInt("number", -1).takeIf { it > 0 }
                ?: continue
            val title = item.optString("name").ifBlank { item.optString("title") }.ifBlank { "Серия $number" }
            val id = item.optInt("id").takeIf { it > 0 } ?: item.optInt("episodeId").takeIf { it > 0 }
            episodes.add(AnimeEpisode(number = number, title = title, id = id))
        }
        return episodes.distinctBy { it.number }.sortedBy { it.number }
    }

    private fun getAniLibTitle(release: JSONObject): String =
        release.optString("ruTitle").ifBlank { release.optString("name") }.ifBlank { "AniLib" }

    private suspend fun fetchAniLibTranslations(shikimoriId: Int, animeTitle: String): List<AnimeTranslation> {
        val release = findAniLibRelease(shikimoriId, animeTitle) ?: return emptyList()
        val episodes = parseAniLibEpisodes(release)
        return listOf(
            AnimeTranslation(
                id = release.optInt("id").toString(),
                title = getAniLibTitle(release),
                type = "voice",
                episodesCount = episodes.size
            )
        )
    }

    private suspend fun fetchAniLibEpisodes(shikimoriId: Int, animeTitle: String, translationId: String): List<AnimeEpisode> {
        val release = findAniLibRelease(shikimoriId, animeTitle) ?: return emptyList()
        return parseAniLibEpisodes(release)
    }

    private suspend fun resolveAniLibStream(shikimoriId: Int, animeTitle: String, episodeNumber: Int, translationId: String): AnimeMediaStream? {
        Log.i(TAG, "[AniLib] resolveStream: id=$shikimoriId, ep=$episodeNumber")
        val release = findAniLibRelease(shikimoriId, animeTitle) ?: return null
        val base = release.optString("base").ifBlank { ANILIB_API.first() }
        val episodes = parseAniLibEpisodes(release)
        val ep = episodes.firstOrNull { it.number == episodeNumber } ?: return null
        val episodeId = ep.id ?: return null

        val epBody = get("$base/api/v2/episode?id=$episodeId", referer = "https://anilib.me/") ?: return null
        val players = runCatching {
            val root = JSONObject(epBody)
            root.optJSONArray("players") ?: root.optJSONObject("data")?.optJSONArray("players") ?: JSONArray()
        }.getOrNull() ?: return null
        if (players.length() == 0) return null

        // Pick the first voice player (ShikiWatch picks by team; we take the first voice one).
        val player = (0 until players.length())
            .mapNotNull { players.optJSONObject(it) }
            .firstOrNull { p ->
                val tt = p.optString("translationType").lowercase()
                tt == "voice" || tt == "озвучка" || (tt != "sub" && tt != "subtitles")
            } ?: players.optJSONObject(0) ?: return null

        val host = runCatching {
            JSONObject(epBody).optString("videoHost").ifBlank { JSONObject(epBody).optJSONObject("data")?.optString("videoHost") ?: "" }
        }.getOrDefault("")
        val videoArr = player.optJSONArray("video") ?: return null

        val qualities = linkedMapOf<String, String>()
        for (i in 0 until videoArr.length()) {
            val v = videoArr.optJSONObject(i) ?: continue
            val href = v.optString("href").ifBlank { v.optString("src") }
            if (href.isBlank()) continue
            val q = normalizeQuality(v.optString("quality")) ?: when {
                href.contains("1080", ignoreCase = true) -> "1080p"
                href.contains("720", ignoreCase = true) -> "720p"
                href.contains("480", ignoreCase = true) -> "480p"
                else -> null
            } ?: continue
            val url = if (href.startsWith("http")) href else host.trimEnd('/') + "/" + href.trimStart('/')
            qualities[q] = url
        }
        if (qualities.isEmpty()) return null
        val url = qualities["1080p"] ?: qualities["720p"] ?: qualities["480p"] ?: qualities.values.first()
        Log.i(TAG, "[AniLib] resolved qualities: ${qualities.keys}")
        return AnimeMediaStream(
            url = url,
            qualities = qualities,
            quality = qualities.entries.firstOrNull { it.value == url }?.key ?: "Auto",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://anilib.me/",
                "Origin" to "https://anilib.me"
            )
        )
    }

    // ============================================================
    // Torrents (offline download). AniLiberty releases carry a `torrents` array (quality,
    // seeders/leechers, size, magnet, .torrent url) that we currently discard. This surfaces it
    // so the details-screen download button can hand a magnet/.torrent to an external client.
    // No torrent engine is bundled — this only resolves links, it does not download.
    // ============================================================

    data class TorrentLink(
        val quality: String,
        val size: String,
        val seeders: Int,
        val leechers: Int,
        val magnet: String?,
        val torrentUrl: String?
    )

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
                    val body = it.body?.string().orEmpty()
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

    internal fun kodikPlaybackHeaders(): Map<String, String> = mapOf(
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
                val magnet = t.optString("magnet").ifBlank { t.optString("magnet_link") }.takeIf { it.isNotBlank() }
                val torrentUrl = t.optString("torrent_url").ifBlank { t.optString("url") }.takeIf { it.isNotBlank() }
                if (magnet == null && torrentUrl == null) return@mapNotNull null

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

                TorrentLink(
                    quality = quality,
                    size = t.optString("size").ifBlank { t.optString("total_size") }.ifBlank { "?" },
                    seeders = t.optInt("seeders", t.optInt("peers", 0)),
                    leechers = t.optInt("leechers", 0),
                    magnet = magnet,
                    torrentUrl = torrentUrl
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
                            html = resp.body?.string().orEmpty()
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
                        torrentUrl = fullTorrentUrl
                    )
                )
            }
            results
        }.getOrDefault(emptyList())
    }
}
