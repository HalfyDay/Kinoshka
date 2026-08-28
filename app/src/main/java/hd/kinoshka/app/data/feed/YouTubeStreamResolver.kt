package hd.kinoshka.app.data.feed

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Прямой поток YouTube-ролика для mpvEx: сборка mpv без yt-dlp, поэтому извлекаем URL
 * сами через InnerTube API (тот же механизм, что у yt-dlp) — клиент ANDROID. Muxed-формат
 * у YouTube теперь только 360p (itag 18), поэтому для HD берём раздельные дорожки из
 * adaptiveFormats (1080p/720p mp4 + аудио) и склеиваем их mpv-EDL-URI (!new_stream).
 * Запасной профиль IOS на случай, если ANDROID-профиль отвалится. Ссылки googlevideo
 * живут ~6ч — резолвим при нажатии. YouTube борется с нестандартными клиентами: любой
 * шаг может не сработать, вызывающий код обязан молча переживать null.
 */
object YouTubeStreamResolver {

    private const val TAG = "YouTubeStreamResolver"
    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    private data class ClientProfile(
        val name: String,
        val version: String,
        val userAgent: String,
        val clientNameHeader: Int,
        val extraClientFields: String
    )

    private val CLIENT_PROFILES = listOf(
        ClientProfile(
            name = "ANDROID",
            version = "20.10.38",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            clientNameHeader = 3,
            extraClientFields = "\"androidSdkVersion\":30,\"osName\":\"Android\",\"osVersion\":\"11\""
        ),
        ClientProfile(
            name = "IOS",
            version = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            clientNameHeader = 5,
            extraClientFields = "\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\",\"osVersion\":\"18.3.2.22D82\""
        )
    )

    data class DirectStream(val url: String, val headers: Map<String, String>)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** videoId → прямой mp4/HLS для mpv. null — поток извлечь не удалось. */
    suspend fun resolve(videoId: String): DirectStream? = withContext(Dispatchers.IO) {
        for (profile in CLIENT_PROFILES) {
            val body = """{"context":{"client":{"clientName":"${profile.name}",""" +
                """"clientVersion":"${profile.version}","hl":"ru",${profile.extraClientFields}}},""" +
                """"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
            val json = httpPost(body, profile.userAgent, profile) ?: continue
            val stream = parsePlayerResponse(json, profile.userAgent)
            if (stream != null) {
                Log.i(TAG, "resolved $videoId via ${profile.name}: …${stream.url.takeLast(40)}")
                return@withContext stream
            }
        }
        Log.i(TAG, "no stream for $videoId")
        null
    }

    private fun parsePlayerResponse(json: String, userAgent: String): DirectStream? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // geo/age-ограничения и прочие блокировки видны в playabilityStatus.
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val streamingData = root.optJSONObject("streamingData") ?: return null

        // Muxed mp4 (видео+звук, itag 18/22) — одиночный URL, который умеет mpv.
        // Максимальный muxed-формат у YouTube сегодня — 360p (itag 18), это фолбэк.
        var bestMuxedUrl: String? = null
        var bestMuxedBitrate = -1
        var bestMuxedWidth = 0
        streamingData.optJSONArray("formats")?.let { formats ->
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val url = f.optString("url").takeIf { it.startsWith("http") } ?: continue
                if (!f.optString("mimeType").startsWith("video/mp4")) continue
                val bitrate = f.optInt("bitrate", 0)
                if (bitrate > bestMuxedBitrate) {
                    bestMuxedBitrate = bitrate
                    bestMuxedUrl = url
                    bestMuxedWidth = f.optInt("width", 0)
                }
            }
        }

        // HD (720p/1080p+): adaptiveFormats дают раздельные видео/аудио дорожки.
        // mpv склеивает их нативно через EDL-URI с заголовком !new_stream
        // (см. DOCS/edl-mpv.rst) — тот же приём, что использует ytdl_hook.
        var bestVideoUrl: String? = null
        var bestVideoBitrate = -1
        var bestVideoWidth = 0
        var bestAudioUrl: String? = null
        var bestAudioBitrate = -1
        streamingData.optJSONArray("adaptiveFormats")?.let { adaptive ->
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                val url = f.optString("url").takeIf { it.startsWith("http") } ?: continue
                val mime = f.optString("mimeType")
                val bitrate = f.optInt("bitrate", 0)
                when {
                    mime.startsWith("video/mp4") && bitrate > bestVideoBitrate -> {
                        bestVideoBitrate = bitrate
                        bestVideoUrl = url
                        bestVideoWidth = f.optInt("width", 0)
                    }
                    mime.startsWith("audio/mp4") && bitrate > bestAudioBitrate -> {
                        bestAudioBitrate = bitrate
                        bestAudioUrl = url
                    }
                }
            }
        }
        if (bestVideoUrl != null && bestAudioUrl != null && bestVideoWidth > bestMuxedWidth &&
            !bestVideoUrl!!.contains(';') && !bestAudioUrl!!.contains(';')
        ) {
            val edl = "edl://%${bestVideoUrl!!.length}%$bestVideoUrl;!new_stream;%${bestAudioUrl!!.length}%$bestAudioUrl"
            return DirectStream(edl, mapOf("User-Agent" to userAgent))
        }

        bestMuxedUrl?.let { return DirectStream(it, mapOf("User-Agent" to userAgent)) }

        // Фолбэк — HLS-манифест: mpv играет m3u8; DASH-плейлисты из раздельных дорожек
        // одним URL не собрать, их не рассматриваем.
        streamingData.optString("hlsManifestUrl")
            .takeIf { it.startsWith("http") }
            ?.let { return DirectStream(it, mapOf("User-Agent" to userAgent)) }
        return null
    }

    private fun httpPost(body: String, userAgent: String, profile: ClientProfile): String? = runCatching {
        httpClient.newCall(
            Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$API_KEY&prettyPrint=false")
                .header("User-Agent", userAgent)
                .header("X-YouTube-Client-Name", profile.clientNameHeader.toString())
                .header("X-YouTube-Client-Version", profile.version)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "${profile.name} POST -> ${response.code}")
                return@use null
            }
            response.body?.string()
        }
    }.getOrNull()
}
