package hd.kinoshka.app.data.source

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
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object AnimeStreamResolver {

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

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

    private val client by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
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

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    private val prefetchAllMediaCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<List<FlatTranslation>>>()
    private val resolveStreamCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<AnimeMediaStream>>()
    private val aniLibertyReleaseCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache

    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchAvailableSources(shikimoriId: Int, animeTitle: String = ""): List<AnimeSource> = withContext(Dispatchers.IO) {
        listOf(
            AnimeSource(AnimeSourceType.KODIK, isAvailable = true),
            AnimeSource(AnimeSourceType.ANILIBERTY, isAvailable = true)
        )
    }

    private fun parseAniLibertyEpisodes(release: JSONObject): List<AnimeEpisode> {
        val episodes = release.optJSONArray("episodes") ?: return emptyList()
        return episodes.asSequenceObjects().mapNotNull { episode ->
            val number = episode.optInt("ordinal").takeIf { it > 0 }
                ?: episode.optInt("sort_order").takeIf { it > 0 }
                ?: episode.optInt("id").takeIf { it > 0 }
                ?: return@mapNotNull null
            val title = episode.optString("name").ifBlank {
                episode.optString("name_english").ifBlank { "Серия $number" }
            }
            AnimeEpisode(number = number, title = title, id = episode.optInt("id").takeIf { it > 0 })
        }.sortedBy { it.number }.toList()
    }

    suspend fun prefetchAllMedia(shikimoriId: Int, animeTitle: String): List<FlatTranslation> {
        val cacheKey = "$shikimoriId:$animeTitle"
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
        kotlinx.coroutines.coroutineScope {
            val deferredKodik = async {
                runCatching {
                    val results = kodikSearch(shikimoriId, animeTitle, null)
                    results.mapNotNull { result ->
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
                }.getOrElse { emptyList() }
            }

            val deferredAniLiberty = async {
                runCatching {
                    val release = findAniLibertyRelease(shikimoriId, animeTitle)
                    if (release != null) {
                        val episodes = parseAniLibertyEpisodes(release)
                        val title = getAniLibertyTitle(release)
                        listOf(
                            FlatTranslation(
                                source = AnimeSourceType.ANILIBERTY,
                                translationId = "default",
                                title = title,
                                type = "voice",
                                episodes = episodes
                            )
                        )
                    } else emptyList()
                }.getOrElse { emptyList() }
            }

            deferredKodik.await() + deferredAniLiberty.await()
        }
    }

    suspend fun fetchTranslations(
        shikimoriId: Int,
        animeTitle: String,
        sourceType: AnimeSourceType
    ): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.KODIK -> fetchKodikTranslations(shikimoriId, animeTitle)
            AnimeSourceType.ANILIBERTY -> fetchAniLibertyTranslations(shikimoriId, animeTitle)
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
        val result = kodikSearch(shikimoriId, animeTitle, translationId).firstOrNull() ?: return@withContext emptyList()
        extractKodikEpisodes(result)
    }

    private suspend fun resolveKodikStream(
        shikimoriId: Int,
        animeTitle: String,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? = withContext(Dispatchers.IO) {
        val episode = fetchKodikEpisodes(shikimoriId, animeTitle, translationId).firstOrNull { it.number == episodeNumber }
            ?: return@withContext null
        val episodeLink = episode.link?.takeIf { it.isNotBlank() } ?: return@withContext null
        val qualities = resolveKodikHls(absoluteKodikUrl(episodeLink))
        val url = qualities["720p"] ?: qualities["480p"] ?: qualities["360p"] ?: qualities.values.firstOrNull()
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
        val release = findAniLibertyRelease(shikimoriId, animeTitle, translationId) ?: return@withContext null
        val episodes = release.optJSONArray("episodes") ?: return@withContext null
        val episode = episodes.asSequenceObjects().firstOrNull { ep ->
            ep.optInt("ordinal") == episodeNumber || ep.optInt("sort_order") == episodeNumber
        } ?: return@withContext null

        val qualities = linkedMapOf<String, String>()
        episode.optString("hls_1080").takeIf { it.isNotBlank() }?.let { qualities["1080p"] = it }
        episode.optString("hls_720").takeIf { it.isNotBlank() }?.let { qualities["720p"] = it }
        episode.optString("hls_480").takeIf { it.isNotBlank() }?.let { qualities["480p"] = it }

        if (qualities.isEmpty()) {
            val fallbackUrl = episode.optString("external_player")
                .ifBlank { episode.optString("player_url") }
                .ifBlank { release.optString("external_player") }
                .ifBlank { release.optString("player_url") }
                .ifBlank { release.optString("playerUrl") }
            if (fallbackUrl.isNotBlank()) {
                return@withContext resolveAniLibertyExternalPlayer(fallbackUrl)
            }
        }

        val url = qualities["1080p"] ?: qualities["720p"] ?: qualities["480p"] ?: return@withContext null
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

        val url = qualities["1080p"] ?: qualities["720p"] ?: qualities["480p"] ?: qualities.values.firstOrNull() ?: return@withContext null
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
        val orderedTokens = lastWorkingKodikToken?.let { working ->
            listOf(working) + tokens.filter { it != working }
        } ?: tokens

        for (token in orderedTokens) {
            for (base in KODIK_API_BASES) {
                if (shikimoriId > 0) {
                    val shikimoriUrl = kodikSearchUrl(base, token, "shikimori_id", shikimoriId.toString(), translationId, limit)
                    val body = get(shikimoriUrl, referer = "https://kodik.info/")
                    if (body != null) {
                        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
                        if (results != null && results.length() > 0) {
                            lastWorkingKodikToken = token
                            return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                        }
                    }
                }

                val titleQueries = buildAnimeSearchQueries(animeTitle)
                for (query in titleQueries) {
                    val titleUrl = kodikSearchUrl(base, token, "title", query, translationId, limit)
                    val tBody = get(titleUrl, referer = "https://kodik.info/") ?: continue
                    val results = runCatching { JSONObject(tBody).optJSONArray("results") }.getOrNull() ?: continue
                    if (results.length() > 0) {
                        lastWorkingKodikToken = token
                        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                    }
                }
            }
        }
        if (shikimoriId > 0) {
            fetchKodikFromFindPlayer(shikimoriId)?.let { return listOf(it) }
        }
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

    private suspend fun resolveKodikHls(episodeUrl: String): Map<String, String> {
        val outerResult = fetchHtmlWithDomainFallbacks(episodeUrl, referer = "https://shikimori.one/")
            ?: return emptyMap()
        var html = outerResult.first
        var workingUrl = outerResult.second

        val iframeSrc = Regex("""<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!iframeSrc.isNullOrBlank()) {
            val absIframe = absoluteUrl(workingUrl, iframeSrc)
            val iframeResult = fetchHtmlWithDomainFallbacks(absIframe, referer = workingUrl)
            if (iframeResult != null) {
                html = iframeResult.first
                workingUrl = iframeResult.second
            }
        }

        val direct = extractM3u8Links(html)
        if (direct.isNotEmpty()) return direct

        val payload = extractKodikPayload(html) ?: return emptyMap()

        val scriptUrl = Regex("""<script[^>]+src=["']([^"']*assets/js[^"']*)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.let { absoluteUrl(workingUrl, it) }

        var dynamicApiPath: String? = null
        if (scriptUrl != null) {
            val js = get(scriptUrl, referer = workingUrl)
            if (js != null) {
                dynamicApiPath = extractKodikApiEndpoint(js)
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

        for (endpoint in candidateEndpoints) {
            val body = post(endpoint, payload, referer = workingUrl) ?: continue
            val links = parseKodikLinks(body)
            if (links.isNotEmpty()) return links
        }
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
        val cacheKey = if (!knownIdOrAlias.isNullOrBlank() && knownIdOrAlias != "default") knownIdOrAlias else "$shikimoriId:$animeTitle"
        aniLibertyReleaseCache[cacheKey]?.let { return it }

        if (!knownIdOrAlias.isNullOrBlank() && knownIdOrAlias != "default") {
            for (base in ANILIBERTY_API) {
                fetchAniLibertyReleaseDetail(base, knownIdOrAlias)?.let {
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
        for (base in ANILIBERTY_API) {
            for (alias in candidateAliases) {
                val summary = get("$base/api/v1/anime/releases/list?aliases=${enc(alias)}", referer = "https://anilibria.top/")
                    ?.let { parseAniLibertyRelease(it, alias) } ?: continue
                val detail = fetchAniLibertyReleaseDetail(base, summary.optString("alias").ifBlank { summary.optInt("id").toString() })
                if (detail != null) {
                    aniLibertyReleaseCache[cacheKey] = detail
                    return detail
                }
            }

            if (animeTitle.isNotBlank()) {
                val summary = get("$base/api/v1/app/search/releases?query=${enc(animeTitle)}", referer = "https://anilibria.top/")
                    ?.let { parseAniLibertyRelease(it, slugifyAnimeTitle(animeTitle)) } ?: continue
                val detail = fetchAniLibertyReleaseDetail(base, summary.optString("alias").ifBlank { summary.optInt("id").toString() })
                if (detail != null) {
                    aniLibertyReleaseCache[cacheKey] = detail
                    return detail
                }
            }
        }
        return null
    }

    private suspend fun fetchAniLibertyReleaseDetail(base: String, idOrAlias: String): JSONObject? {
        val body = get("$base/api/v1/anime/releases/$idOrAlias", referer = "https://anilibria.top/") ?: return null
        return runCatching {
            val root = JSONObject(body)
            root.optJSONObject("data") ?: root
        }.getOrNull()
    }

    private fun parseAniLibertyRelease(body: String, preferredAlias: String? = null): JSONObject? {
        if (body.isBlank() || body == "null") return null
        val trimmed = body.trim()
        return runCatching {
            when {
                trimmed.startsWith("[") -> firstReleaseMatchingAlias(JSONArray(trimmed), preferredAlias)
                else -> {
                    val json = JSONObject(trimmed)
                    val data = json.optJSONArray("data")
                        ?: json.optJSONObject("data")?.optJSONArray("items")
                        ?: json.optJSONObject("data")?.optJSONArray("releases")
                        ?: json.optJSONArray("items")
                        ?: json.optJSONArray("results")
                        ?: return@runCatching if (json.has("episodes")) json else null
                    firstReleaseMatchingAlias(data, preferredAlias)
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
            return if (clean.startsWith("http")) clean else if (clean.startsWith("//")) "https:$clean" else clean
        }
        return runCatching {
            val rot = decryptRot18(clean)
            val padded = if (rot.endsWith("==") || rot.endsWith("=")) rot else "$rot=="
            val decodedBytes = Base64.getDecoder().decode(padded)
            val decoded = String(decodedBytes, Charsets.UTF_8)
            if (decoded.startsWith("http")) decoded else if (decoded.startsWith("//")) "https:$decoded" else decoded
        }.getOrDefault(clean)
    }

    private fun decryptRot18(encoded: String): String {
        val sb = StringBuilder()
        for (ch in encoded) {
            when {
                ch in 'A'..'Z' -> sb.append(((ch.code - 65 + 18) % 26 + 65).toChar())
                ch in 'a'..'z' -> sb.append(((ch.code - 97 + 18) % 26 + 97).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun absoluteKodikUrl(value: String): String = when {
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

    private fun JSONArray.asSequenceObjects(): Sequence<JSONObject> = sequence {
        for (i in 0 until length()) {
            optJSONObject(i)?.let { yield(it) }
        }
    }

    private fun firstReleaseMatchingAlias(data: JSONArray, preferredAlias: String?): JSONObject? {
        if (data.length() == 0) return null
        val items = data.asSequenceObjects().toList()
        preferredAlias?.takeIf { it.isNotBlank() }?.let { alias ->
            items.firstOrNull { item ->
                item.optString("alias").equals(alias, ignoreCase = true) ||
                    getAniLibertyTitle(item, false).equals(alias, ignoreCase = true) ||
                    getAniLibertyTitle(item, true).equals(alias, ignoreCase = true) ||
                    slugifyAnimeTitle(getAniLibertyTitle(item, false)).equals(alias, ignoreCase = true) ||
                    slugifyAnimeTitle(getAniLibertyTitle(item, true)).equals(alias, ignoreCase = true)
            }?.let { return it }
        }
        return items.firstOrNull()
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
}
