package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSource
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.AnimeTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object AnimeStreamResolver {

    private val KODIK_TOKENS = listOf(
        "qwe456asd123zxc789",
        "09d6c71182237a2541dfd1f84c21719b"
    )

    private val client by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient()
        }
    }

    suspend fun fetchAvailableSources(shikimoriId: Int): List<AnimeSource> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeSource>()
        list.add(AnimeSource(AnimeSourceType.KODIK, isAvailable = true))
        
        // Check AniLibria availability
        val aniLibriaAvailable = checkAniLibriaAvailability(shikimoriId)
        if (aniLibriaAvailable) {
            list.add(0, AnimeSource(AnimeSourceType.ANILIBRIA, isAvailable = true))
        } else {
            list.add(AnimeSource(AnimeSourceType.ANILIBRIA, isAvailable = false))
        }
        list
    }

    private suspend fun checkAniLibriaAvailability(shikimoriId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.anilibria.tv/v3/title?shikimori_id=$shikimoriId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNull_or_blank() && body != "null") {
                    val json = JSONObject(body)
                    return@withContext json.has("id")
                }
            }
        } catch (_: Exception) {}
        false
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()

    suspend fun fetchTranslations(shikimoriId: Int, sourceType: AnimeSourceType): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.ANILIBRIA -> {
                listOf(AnimeTranslation("anilibria_main", "AniLibria (Официальный дубляж)", "voice"))
            }
            AnimeSourceType.KODIK -> {
                fetchKodikTranslations(shikimoriId)
            }
        }
    }

    private suspend fun fetchKodikTranslations(shikimoriId: Int): List<AnimeTranslation> = withContext(Dispatchers.IO) {
        val translationsMap = mutableMapOf<String, AnimeTranslation>()
        for (token in KODIK_TOKENS) {
            try {
                val apiUrl = "https://kodikapi.com/search?token=$token&shikimori_id=$shikimoriId&with_episodes=true"
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue
                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: continue

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val translationObj = item.optJSONObject("translation") ?: continue
                    val trId = translationObj.optString("id")
                    val trTitle = translationObj.optString("title", "Неизвестная озвучка")
                    val trType = translationObj.optString("type", "voice")
                    val epCount = item.optInt("episodes_count", 0)

                    if (trId.isNotEmpty() && !translationsMap.containsKey(trId)) {
                        translationsMap[trId] = AnimeTranslation(trId, trTitle, trType, epCount)
                    }
                }
                if (translationsMap.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        translationsMap.values.toList().sortedByDescending { it.episodesCount }
    }

    suspend fun fetchEpisodes(shikimoriId: Int, sourceType: AnimeSourceType, translationId: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.ANILIBRIA -> {
                fetchAniLibriaEpisodes(shikimoriId)
            }
            AnimeSourceType.KODIK -> {
                fetchKodikEpisodes(shikimoriId, translationId)
            }
        }
    }

    private suspend fun fetchAniLibriaEpisodes(shikimoriId: Int): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        val episodes = mutableListOf<AnimeEpisode>()
        try {
            val url = "https://api.anilibria.tv/v3/title?shikimori_id=$shikimoriId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val playerObj = json.optJSONObject("player")
                val listObj = playerObj?.optJSONObject("list")
                if (listObj != null) {
                    val keys = listObj.keys()
                    while (keys.hasNext()) {
                        val epNumStr = keys.next()
                        val epNum = epNumStr.toIntOrNull() ?: continue
                        val epObj = listObj.optJSONObject(epNumStr)
                        val name = epObj?.optString("name")
                        episodes.add(AnimeEpisode(epNum, title = if (name.isNull_or_blank()) "Серия $epNum" else "Серия $epNum: $name"))
                    }
                }
            }
        } catch (_: Exception) {}
        episodes.sortedBy { it.number }
    }

    private suspend fun fetchKodikEpisodes(shikimoriId: Int, translationId: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        val episodes = mutableListOf<AnimeEpisode>()
        for (token in KODIK_TOKENS) {
            try {
                val apiUrl = "https://kodikapi.com/search?token=$token&shikimori_id=$shikimoriId&with_episodes=true&translation_id=$translationId"
                val request = Request.Builder().url(apiUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue
                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: continue

                if (results.length() > 0) {
                    val item = results.getJSONObject(0)
                    val seasonsObj = item.optJSONObject("seasons")
                    if (seasonsObj != null) {
                        val seasonKeys = seasonsObj.keys()
                        if (seasonKeys.hasNext()) {
                            val firstSeasonKey = seasonKeys.next()
                            val seasonObj = seasonsObj.getJSONObject(firstSeasonKey)
                            val episodesObj = seasonObj.optJSONObject("episodes")
                            if (episodesObj != null) {
                                val epKeys = episodesObj.keys()
                                while (epKeys.hasNext()) {
                                    val epNumStr = epKeys.next()
                                    val epNum = epNumStr.toIntOrNull() ?: continue
                                    val link = episodesObj.optString(epNumStr)
                                    episodes.add(AnimeEpisode(epNum, title = "Серия $epNum", link = link))
                                }
                            }
                        }
                    }
                    if (episodes.isEmpty()) {
                        val lastEp = item.optInt("last_episode", 1)
                        for (e in 1..lastEp) {
                            episodes.add(AnimeEpisode(e, title = "Серия $e"))
                        }
                    }
                    if (episodes.isNotEmpty()) break
                }
            } catch (_: Exception) {}
        }
        episodes.sortedBy { it.number }
    }

    suspend fun resolveStream(
        shikimoriId: Int,
        sourceType: AnimeSourceType,
        translationId: String,
        episodeNumber: Int
    ): AnimeMediaStream? = withContext(Dispatchers.IO) {
        when (sourceType) {
            AnimeSourceType.ANILIBRIA -> resolveAniLibriaStream(shikimoriId, episodeNumber)
            AnimeSourceType.KODIK -> resolveKodikStream(shikimoriId, translationId, episodeNumber)
        }
    }

    private suspend fun resolveAniLibriaStream(shikimoriId: Int, episodeNumber: Int): AnimeMediaStream? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.anilibria.tv/v3/title?shikimori_id=$shikimoriId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val host = json.optJSONObject("player")?.optString("host", "cache.libria.fun") ?: "cache.libria.fun"
                val listObj = json.optJSONObject("player")?.optJSONObject("list")
                val epObj = listObj?.optJSONObject(episodeNumber.toString()) ?: listObj?.optJSONObject(listObj.keys().asSequence().firstOrNull() ?: "")
                if (epObj != null) {
                    val hlsObj = epObj.optJSONObject("hls")
                    val fhd = hlsObj?.optString("fhd")
                    val hd = hlsObj?.optString("hd")
                    val sd = hlsObj?.optString("sd")
                    val streamPath = fhd ?: hd ?: sd
                    if (streamPath != null && streamPath.isNotBlank()) {
                        val streamUrl = if (streamPath.startsWith("http")) streamPath else "https://$host$streamPath"
                        return@withContext AnimeMediaStream(url = streamUrl, quality = "1080p (FHD)")
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }

    private suspend fun resolveKodikStream(shikimoriId: Int, translationId: String, episodeNumber: Int): AnimeMediaStream? = withContext(Dispatchers.IO) {
        for (token in KODIK_TOKENS) {
            try {
                val apiUrl = "https://kodikapi.com/search?token=$token&shikimori_id=$shikimoriId&with_episodes=true&translation_id=$translationId"
                val request = Request.Builder().url(apiUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue
                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: continue
                if (results.length() > 0) {
                    val item = results.getJSONObject(0)
                    var embedLink = item.optString("link", "")
                    if (embedLink.startsWith("//")) embedLink = "https:$embedLink"
                    
                    // Modify link to include episode if needed
                    if (!embedLink.contains("episode=")) {
                        embedLink = if (embedLink.contains("?")) "$embedLink&episode=$episodeNumber" else "$embedLink?episode=$episodeNumber"
                    }

                    // Attempt to fetch stream from page or return embed URL as fallback
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to "https://shikimori.io/"
                    )
                    return@withContext AnimeMediaStream(url = embedLink, headers = headers, quality = "720p")
                }
            } catch (_: Exception) {}
        }
        null
    }
}
