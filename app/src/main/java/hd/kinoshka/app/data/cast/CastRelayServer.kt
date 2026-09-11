package hd.kinoshka.app.data.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * LAN-реле для Chromecast: приёмник не умеет в наши заголовки (Referer/Origin) и
 * подписанные URL, поэтому HLS и сегменты идут через телефон с подстановкой заголовков.
 * Плейлисты переписываются на реле (варианты, сегменты, ключи, AUDIO/SUBTITLES-группы
 * через URI="..."), байты проксируются с пробросом Range. Слушает 0.0.0.0 —
 * доступен ТВ в том же Wi-Fi. Живёт только пока идёт трансляция.
 */
class CastRelayServer private constructor() : NanoHTTPD("0.0.0.0", 0) {

    companion object {
        private const val TAG = "CastRelay"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

        @Volatile
        private var instance: CastRelayServer? = null

        /** IP телефона в Wi-Fi; null — нет Wi-Fi (каст недоступен). */
        fun lanIp(context: Context): String? {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            // ACCESS_WIFI_STATE — normal-разрешение из манифеста, рантайм-запрос не нужен.
            val raw = runCatching { wm.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
            if (raw == 0) return null
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array()
            return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                .takeIf { it != "0.0.0.0" }
        }

        fun getInstance(): CastRelayServer {
            return instance ?: synchronized(this) {
                instance ?: CastRelayServer().also {
                    it.start()
                    instance = it
                }
            }
        }

        fun stopInstance() {
            synchronized(this) {
                runCatching { instance?.stop() }
                instance?.upstreams?.clear()
                instance = null
            }
        }
    }

    private sealed interface Upstream {
        val headers: Map<String, String>
    }

    /** Один URL (вариант/файл). */
    private data class SingleUpstream(val url: String, override val headers: Map<String, String>) : Upstream

    /**
     * Лестница рангов: приёмнику отдаём синтезированный мастер (Shaka ABR сам
     * стартует с низкого ранга и ползёт вверх — медленный старт 1080p больше
     * не роняет LOAD). Значения — media-плейлисты, обслуживаются общим путём.
     */
    private data class LadderUpstream(val ladder: Map<String, String>, override val headers: Map<String, String>) : Upstream

    private val upstreams = ConcurrentHashMap<String, Upstream>()

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        // Приёмник тянет сегменты пачками в 4-6 коннектов на хост: дефолтные 5
        // душили старт и каскадом роняли LOAD. Плюс куки сессионных CDN.
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        // Часть CDN привязывает сегменты к сессии (кука ставится на плейлисте):
        // без jar сегменты уходили бы в 403.
        .cookieJar(object : okhttp3.CookieJar {
            private val store = ConcurrentHashMap<String, List<okhttp3.Cookie>>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                store[url.host] = cookies
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                store[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
        })
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Высота ранга ("1080p" → 1080). */
    private fun rungHeight(rung: String): Int =
        rung.substringBefore("p").toIntOrNull() ?: 0

    /** Грубая оценка битрейта ранга для BANDWIDTH мастера (ABR стартует по ней, дальше замер). */
    private fun estimateBandwidth(rung: String): Long = when (rungHeight(rung)) {
        in 2000..Int.MAX_VALUE -> 12_000_000L
        in 1400..1999 -> 7_000_000L
        in 900..1399 -> 4_500_000L
        in 600..899 -> 2_200_000L
        in 400..599 -> 1_100_000L
        in 300..399 -> 700_000L
        in 200..299 -> 400_000L
        else -> 250_000L
    }

    private fun isPlaylistUrl(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /**
     * Регистрирует поток, возвращает LAN-URL для приёмника; null — нет Wi-Fi.
     * [ladder] + Auto ([preferred] null/"Auto") → синтезированный мастер с ABR;
     * явный ранг → одиночный URL ранга (пиннинг без ABR).
     */
    fun register(
        context: Context,
        url: String,
        headers: Map<String, String>,
        ladder: Map<String, String> = emptyMap(),
        preferred: String? = null
    ): String? {
        val host = lanIp(context) ?: return null
        val wantMaster = ladder.size >= 2 &&
            ladder.values.all(::isPlaylistUrl) &&
            (preferred.isNullOrBlank() || preferred == "Auto")
        val relay = if (wantMaster) {
            val id = UUID.randomUUID().toString().take(8)
            upstreams[id] = LadderUpstream(ladder, headers)
            Log.i(TAG, "register master $id <- ${ladder.keys.sortedByDescending(::rungHeight)}")
            "http://$host:$listeningPort/c/$id"
        } else {
            val finalUrl = if (!preferred.isNullOrBlank() && preferred != "Auto") {
                ladder[preferred] ?: url
            } else url
            val id = UUID.randomUUID().toString().take(8)
            upstreams[id] = SingleUpstream(finalUrl, headers)
            Log.i(TAG, "register http://$host:$listeningPort/c/$id <- ${finalUrl.take(120)}")
            "http://$host:$listeningPort/c/$id?u=${b64(finalUrl)}"
        }
        // Прогрев апстрима (DNS/TLS/куки/edge) до прихода приёмника: его первый
        // запрос иначе упирается в холодный коннект и LOAD падает по таймауту.
        // Греем исходный url напрямую (тот же хост/сессия, что и варианты).
        Thread({
            runCatching {
                val b = Request.Builder().url(url)
                headers.forEach { (k, v) -> b.header(k, v) }
                b.header("User-Agent", UA)
                // Дешёвый прогрев: первый байт греет всё, тело не тянем.
                b.header("Range", "bytes=0-0")
                http.newCall(b.build()).execute().use { resp ->
                    Log.i(TAG, "prewarm ${shortTarget(url)} ← ${resp.code}")
                }
            }.onFailure {
                Log.w(TAG, "prewarm failed: ${it.message}")
            }
        }, "cast-prewarm").apply { isDaemon = true }.start()
        return relay
    }

    fun unregisterAll() = upstreams.clear()

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun unb64(s: String): String? = runCatching {
        String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrNull()

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no such cast stream")

    private fun resolve(base: String, ref: String): String =
        runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)

    private val uriAttr = Regex("""URI="([^"]+)"""")

    private fun shortTarget(target: String): String =
        runCatching {
            val u = URL(target)
            u.host + u.path
        }.getOrDefault(target).take(110)

    /**
     * MIME для приёмника: верим апстриму, кроме текстовых заглушек; иначе по расширению.
     * Неверный тип на init/сегментах fMP4 роняет MSE приёмника (NETWORK_ERROR на LOAD).
     */
    private fun pickMime(target: String, upstreamType: String): String {
        val clean = upstreamType.substringBefore(';').trim()
        if (clean.isNotEmpty() && !clean.equals("text/html", true) && !clean.equals("text/plain", true)) {
            return clean
        }
        val path = target.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".cmf") -> "video/mp4"
            path.endsWith(".ts") -> "video/MP2T"
            path.endsWith(".aac") || path.endsWith(".mp3") -> "audio/aac"
            path.endsWith(".vtt") -> "text/vtt"
            else -> "application/octet-stream"
        }
    }

    /** "bytes 0-1023/4096" → 1024. */
    private fun rangeLength(contentRange: String): Long? {
        val bounds = contentRange.substringAfter("bytes", "").trim().substringBefore('/').split('-')
        val a = bounds.getOrNull(0)?.toLongOrNull()
        val b = bounds.getOrNull(1)?.toLongOrNull()
        return if (a != null && b != null && b >= a) b - a + 1 else null
    }

    /** "bytes 123-1023/4096" → 123. */
    private fun rangeStart(contentRange: String): Long? =
        contentRange.substringAfter("bytes", "").trim().substringBefore('/').substringBefore('-')
            .toLongOrNull()

    /** "bytes=123-" (запрос приёмника) → 123. */
    private fun requestRangeStart(rangeHeader: String?): Long? =
        rangeHeader?.takeIf { it.startsWith("bytes=") }
            ?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()
    private fun rewritePlaylist(body: String, base: String, hostHeader: String, id: String): String {
        fun relay(target: String) = "http://$hostHeader/c/$id?u=${b64(resolve(base, target))}"
        return buildString {
            body.lines().forEach { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> appendLine(raw)
                    line.startsWith("#") -> appendLine(uriAttr.replace(raw) { m -> "URI=\"${relay(m.groupValues[1])}\"" })
                    else -> appendLine(relay(line))
                }
            }
            // Наш контент — всегда VOD (фильмы/сериалы), но часть CDN отдаёт EVENT-плейлисты
            // без #EXT-X-ENDLIST: приёмник считает такой стрим лайвом, seek в середину и LOAD
            // валятся NETWORK_ERROR. Нормализуем в VOD принудительным ENDLIST.
            if (!body.contains("#EXT-X-ENDLIST")) {
                appendLine("#EXT-X-ENDLIST")
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        // Preflight приёмника (fetch HLS): без CORS плейлисты/сегменты режутся.
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }
        return cors(serveRelay(session))
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "Range, Content-Type, Origin")
        r.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        r.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
        return r
    }

    private fun serveRelay(session: IHTTPSession): Response {
        // GET /c/<id>?u=<b64url>; мастер лестницы — голый /c/<id>.
        val parts = session.uri.removePrefix("/").split("/")
        if (parts.size < 2 || parts[0] != "c") return notFound()
        val id = parts[1]
        val upstream = upstreams[id] ?: return notFound()
        // Хост для переписанных URL — из Host-заголовка запроса приёмника (там уже LAN-IP:порт).
        val hostHeader = session.headers["host"]?.takeIf { it.isNotBlank() } ?: return notFound()
        if (upstream is LadderUpstream) {
            val u = session.parameters["u"]?.firstOrNull()?.let(::unb64)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            if (u == null) return serveMaster(hostHeader, id, upstream)
            return fetchAndServe(u, upstream.headers, hostHeader, id, session)
        }
        val single = upstream as SingleUpstream
        val target = session.parameters["u"]?.firstOrNull()?.let(::unb64)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: single.url
        return fetchAndServe(target, single.headers, hostHeader, id, session)
    }

    /** Синтезированный мастер лестницы: ранги по убыванию, ABR приёмника разруливает. */
    private fun serveMaster(hostHeader: String, id: String, upstream: LadderUpstream): Response {
        val rungs = upstream.ladder.entries.sortedByDescending { rungHeight(it.key) }
        val body = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            rungs.forEach { (rung, variantUrl) ->
                val h = rungHeight(rung).takeIf { it > 0 } ?: 720
                appendLine("#EXT-X-STREAM-INF:BANDWIDTH=${estimateBandwidth(rung)},RESOLUTION=${h * 16 / 9}x$h")
                appendLine("http://$hostHeader/c/$id?u=${b64(variantUrl)}")
            }
        }
        Log.i(TAG, "master $id: ${rungs.map { it.key }}")
        return newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", body)
    }

    /**
     * GET апстрима с одной повторной попыткой: edge-CDN периодически рвут первый
     * коннект (холодный TLS/keep-alive), а приёмник такой 500 считает фатальным
     * для всего LOAD. Возвращает открытый ответ (закрывает вызывающий путь).
     */
    private fun openUpstream(target: String, headers: Map<String, String>, range: String?): okhttp3.Response? {
        repeat(2) { attempt ->
            val b = Request.Builder().url(target)
            headers.forEach { (k, v) -> b.header(k, v) }
            if (!headers.containsKey("User-Agent")) b.header("User-Agent", UA)
            if (range != null) b.header("Range", range)
            val r = runCatching { http.newCall(b.build()).execute() }.getOrNull()
            if (r != null && (r.isSuccessful || r.code == 206)) return r
            val code = r?.code
            runCatching { r?.close() }
            Log.w(TAG, "upstream ${shortTarget(target)} range=${range ?: "-"} <- ${code ?: "io-error"} (try ${attempt + 1}/2)")
            if (attempt == 0) Thread.sleep(400)
        }
        return null
    }

    /**
     * Тело апстрима с докачкой: если edge оборвал отдачу раньше Content-Length,
     * добираем остаток новым GET с Range (до 2 докачек), а не отдаём приёмнику
     * рваный сегмент — рваный TS он бросает вместе со всем LOAD (2100 FAILED).
     * Считает отданные байты: лог делит диагнозы «апстрим порезал» (SHORT)
     * и «байты целы, приёмник не распарсил» (complete + всё равно 2100).
     */
    private inner class RangedUpstreamBody(
        private val target: String,
        private val headers: Map<String, String>,
        private var absStart: Long,
        private val total: Long?,
        first: okhttp3.Response,
        private val id: String,
        private val seg: String,
    ) : FilterInputStream(first.body!!.byteStream()) {
        private var current: okhttp3.Response = first
        private var read: Long = 0
        private var resumes = 0
        private var doneLogged = false

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val n = try {
                    super.read(b, off, len)
                } catch (_: java.io.IOException) {
                    -1
                }
                if (n > 0) {
                    read += n
                    if (total != null && read >= total && !doneLogged) {
                        doneLogged = true
                        Log.i(TAG, "media <$id> $seg served sent=$read/$total complete")
                    }
                    return n
                }
                if (total == null || read >= total) return -1
                if (resumes >= 2 || !resume()) {
                    Log.w(TAG, "media <$id> $seg served sent=$read/$total SHORT (upstream cut)")
                    return -1
                }
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        private fun resume(): Boolean {
            resumes++
            runCatching { current.close() }
            val from = absStart + read
            val next = openUpstream(target, headers, "bytes=$from-")
            if (next == null) return false
            val nb = next.body
            if (nb == null) {
                runCatching { next.close() }
                return false
            }
            // Позиционируемся ровно на `from`: 206 даёт старт в Content-Range,
            // 200 (Range проигнорирован) — тело с нуля ресурса, пропускаем `from`.
            val s = if (next.code == 206) {
                next.header("Content-Range")?.let(::rangeStart)
            } else null
            val assumedStart = s ?: 0L
            current = next
            this.`in` = nb.byteStream()
            if (assumedStart > from) {
                runCatching { current.close() }
                return false
            }
            var skip = from - assumedStart
            if (skip > 0) {
                val tmp = ByteArray(8192)
                while (skip > 0) {
                    val n = try {
                        this.`in`.read(tmp, 0, minOf(tmp.size.toLong(), skip).toInt())
                    } catch (_: java.io.IOException) {
                        -1
                    }
                    if (n <= 0) {
                        runCatching { current.close() }
                        return false
                    }
                    skip -= n
                }
            }
            absStart = from - read
            Log.i(TAG, "media <$id> $seg resume#$resumes from=$from <- ${next.code}")
            return true
        }

        override fun close() {
            // NanoHTTPD закрывает стрим только при успешной отправке — на обрыве
            // приёмником сюда не попадаем (там свой лог "Could not send response").
            if (!doneLogged) {
                doneLogged = true
                Log.i(TAG, "media <$id> $seg closed sent=$read/${total?.toString() ?: "?"}")
            }
            runCatching { super.close() }
            runCatching { current.close() }
        }
    }

    private fun fetchAndServe(
        target: String,
        headers: Map<String, String>,
        hostHeader: String,
        id: String,
        session: IHTTPSession
    ): Response {
        val receiverRange = session.headers["range"]?.takeIf { it.startsWith("bytes=") }
        val resp = openUpstream(target, headers, receiverRange)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "upstream unreachable")
        try {
            val body = resp.body ?: run {
                resp.close()
                return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            }
            val contentType = resp.header("Content-Type").orEmpty()
            val looksPlaylist = target.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                contentType.contains("mpegurl", ignoreCase = true)
            if (looksPlaylist) {
                val text = body.string()
                resp.close()
                if (!text.contains("#EXTM3U")) {
                    // Ложный m3u8 (ключ/сегмент за таким URL) — отдаём байты как есть.
                    val raw = text.toByteArray(Charsets.ISO_8859_1)
                    val r = newFixedLengthResponse(
                        Response.Status.OK,
                        contentType.ifBlank { "application/octet-stream" },
                        java.io.ByteArrayInputStream(raw),
                        raw.size.toLong()
                    )
                    r.addHeader("Accept-Ranges", "bytes")
                    Log.i(TAG, "serve ${shortTarget(target)} ← ${resp.code} non-playlist ${raw.size}b")
                    return r
                }
                val rewritten = rewritePlaylist(text, resp.request.url.toString(), hostHeader, id)
                val hadEndlist = text.contains("#EXT-X-ENDLIST")
                val servedEndlist = rewritten.contains("#EXT-X-ENDLIST")
                Log.i(TAG, "playlist <$id> ${shortTarget(target)}: ${text.lines().size} lines " +
                    "upstreamEndlist=$hadEndlist servedEndlist=$servedEndlist")
                // Телеметрия парсинга приёмником: начало + хвост того, что реально отдаём.
                rewritten.lines().take(8).forEachIndexed { i, line ->
                    Log.i(TAG, "playlist[$i] ${line.take(160)}")
                }
                rewritten.lines().takeLast(3).forEachIndexed { i, line ->
                    Log.i(TAG, "playlist[tail$i] ${line.take(160)}")
                }
                return newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", rewritten)
            }
            // Сегменты/ключи: фиксированная длина, когда известна (на медленных чанках
            // приёмник рвёт keep-alive и сыплет NETWORK_ERROR), иначе чанками.
            // Рваную отдачу edge добираем Range-запросами внутри тела (см. класс выше).
            val mime = pickMime(target, contentType)
            val status = if (resp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val totalLen: Long? = resp.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                ?: resp.header("Content-Range")?.let(::rangeLength)
            // Абсолютный старт апстрима: 206 — из Content-Range, 200 — тело с нуля
            // (Range проигнорирован). НЕ путать с Range приёмника: при 200 тело
            // всегда полное, старт 0, иначе докачка уйдёт не на тот оффсет.
            val upstreamStart = if (resp.code == 206) {
                resp.header("Content-Range")?.let(::rangeStart)
                    ?: requestRangeStart(receiverRange) ?: 0L
            } else 0L
            val seg = target.substringAfterLast('/').take(48)
            Log.i(TAG, "media <$id> $seg range=${receiverRange ?: "-"} <- ${resp.code} " +
                "len=${totalLen?.toString() ?: "chunked"} mime=$mime")
            val upstream = RangedUpstreamBody(target, headers, upstreamStart, totalLen, resp, id, seg)
            var out: java.io.InputStream = upstream
            if (mime == "video/MP2T") {
                // Первые байты настоящего TS: 0x47 каждые 188. Если edge подсунул
                // HTML-заглушку/мусор — увидим tsSync=false до жалоб приёмника.
                val prefix = ByteArray(3 * 188)
                var got = 0
                while (got < prefix.size) {
                    val n = try {
                        upstream.read(prefix, got, prefix.size - got)
                    } catch (_: java.io.IOException) {
                        -1
                    }
                    if (n <= 0) break
                    got += n
                }
                val syncOk = got >= 188 && (0..2)
                    .map { it * 188 }
                    .filter { it < got }
                    .all { prefix[it] == 0x47.toByte() }
                Log.i(TAG, "media <$id> $seg tsSync=$syncOk peek=$got")
                if (got > 0) {
                    out = java.io.SequenceInputStream(java.io.ByteArrayInputStream(prefix, 0, got), upstream)
                }
            }
            val r = if (totalLen != null) {
                newFixedLengthResponse(status, mime, out, totalLen)
            } else {
                newChunkedResponse(status, mime, out)
            }
            resp.header("Content-Range")?.let { r.addHeader("Content-Range", it) }
            if (totalLen == null) resp.header("Content-Length")?.let { r.addHeader("Content-Length", it) }
            r.addHeader("Accept-Ranges", "bytes")
            return r
        } catch (e: Exception) {
            Log.w(TAG, "serve failed: ${e.message}")
            runCatching { resp.close() }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "relay error")
        }
    }
}
