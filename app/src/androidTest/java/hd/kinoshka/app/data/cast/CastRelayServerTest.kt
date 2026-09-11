package hd.kinoshka.app.data.cast

import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Реле Chromecast гоняем как приёмник (Shaka): мастер → вариант → сегменты,
 * плюс обрыв edge на полусегменте (докачка Range) и CORS-префлайт.
 * Фейковый апстрим живёт в том же процессе на localhost.
 */
class CastRelayServerTest {

    private lateinit var upstream: FakeUpstream
    private lateinit var appContext: android.content.Context

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        upstream = FakeUpstream().apply { start() }
    }

    @After
    fun tearDown() {
        CastRelayServer.stopInstance()
        runCatching { upstream.stop() }
    }

    @Test
    fun masterServesLadderAndRewritesSegments() {
        val ladder = mapOf(
            "720p" to "${upstream.base()}/v720.m3u8",
            "1080p" to "${upstream.base()}/v1080.m3u8"
        )
        val master = CastRelayServer.getInstance()
            .register(appContext, ladder.getValue("1080p"), emptyMap(), ladder, null)
        assertNotNull(master)
        requireNotNull(master)
        assertFalse(master.contains("?u="))

        val (code, _, body) = get(master)
        assertEquals(200, code)
        val text = body.toString(Charsets.UTF_8)
        assertEquals(2, text.lines().count { it.startsWith("#EXT-X-STREAM-INF") })
        // Ранги по убыванию: первая ссылка — 1080p (у апстрима без ENDLIST).
        val variantLines = text.lines().filter { it.startsWith("http") }
        assertEquals(2, variantLines.size)
        assertTrue(variantLines.all { it.startsWith(master.substringBefore("/c/")) })

        val (vcode, _, vbody) = get(variantLines[0])
        assertEquals(200, vcode)
        val vtext = vbody.toString(Charsets.UTF_8)
        // ENDLIST принудительно добавлен — приёмник видит VOD, а не лайв.
        assertTrue(vtext.contains("#EXT-X-ENDLIST"))
        // Абсолютный URL сегмента у апстрима переписан на реле.
        val segLine = vtext.lines().first { it.startsWith("http") }
        assertTrue(segLine.startsWith(master.substringBefore("/c/")))

        val (scode, sheaders, sbody) = get(segLine)
        assertEquals(200, scode)
        assertArrayEquals(upstream.segBytes, sbody)
        assertTrue(sheaders.contentType().contains("video/MP2T", ignoreCase = true))
        assertEquals("*", sheaders.cors())
        assertEquals("bytes", sheaders.acceptRanges())
    }

    @Test
    fun upstreamEndlistPreserved() {
        val ladder = mapOf("720p" to "${upstream.base()}/v720.m3u8")
        // Один ранг — не мастер, но плейлист всё равно чинится одинаково.
        val single = CastRelayServer.getInstance()
            .register(appContext, ladder.getValue("720p"), emptyMap(), ladder, "720p")
        assertNotNull(single)
        requireNotNull(single)
        val (code, _, body) = get(single)
        assertEquals(200, code)
        assertTrue(body.toString(Charsets.UTF_8).contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun rangeRequestReturns206Slice() {
        val single = CastRelayServer.getInstance()
            .register(appContext, "${upstream.base()}/seg0.ts", emptyMap())
        assertNotNull(single)
        requireNotNull(single)
        val (code, headers, body) = get(single, range = "bytes=100-199")
        assertEquals(206, code)
        assertArrayEquals(upstream.segBytes.copyOfRange(100, 200), body)
        assertEquals("bytes 100-199/${upstream.segBytes.size}", headers.contentRange())
    }

    @Test
    fun upstreamCutIsResumedSilently() {
        // Апстрим отдаёт половину и рвёт тело: реле обязано добрать остаток
        // Range-запросом, клиент получает все байты одним 200-ответом.
        val single = CastRelayServer.getInstance()
            .register(appContext, "${upstream.base()}/cut.ts", emptyMap())
        assertNotNull(single)
        requireNotNull(single)
        val (code, _, body) = get(single)
        assertEquals(200, code)
        assertArrayEquals(upstream.cutBytes, body)
        val half = upstream.cutBytes.size / 2
        assertTrue(upstream.seenRanges.contains("bytes=$half-"))
    }

    @Test
    fun pinnedQualitySkipsMaster() {
        val ladder = mapOf(
            "720p" to "${upstream.base()}/v720.m3u8",
            "1080p" to "${upstream.base()}/v1080.m3u8"
        )
        val single = CastRelayServer.getInstance()
            .register(appContext, ladder.getValue("1080p"), emptyMap(), ladder, "720p")
        assertNotNull(single)
        requireNotNull(single)
        assertTrue(single.contains("?u="))
        val (code, _, body) = get(single)
        assertEquals(200, code)
        val text = body.toString(Charsets.UTF_8)
        assertFalse(text.contains("#EXT-X-STREAM-INF"))
        assertTrue(text.contains("#EXTINF"))
    }

    @Test
    fun corsPreflight() {
        val single = CastRelayServer.getInstance()
            .register(appContext, "${upstream.base()}/seg0.ts", emptyMap())
        assertNotNull(single)
        requireNotNull(single)
        val (code, headers, _) = get(single, method = "OPTIONS")
        assertEquals(200, code)
        assertEquals("*", headers.cors())
    }

    // ---- helpers ----

    // Сырые сокеты, а не HttpURLConnection/OkHttp: у приложения запрещён
    // cleartext политикой сети, а реле Chromecast — всегда http (как у ТВ).
    private fun get(
        url: String,
        range: String? = null,
        method: String = "GET"
    ): Triple<Int, Map<String, List<String>>, ByteArray> {
        val u = java.net.URI(url)
        val port = if (u.port == -1) 80 else u.port
        val path = (u.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
            (u.rawQuery?.let { "?$it" } ?: "")
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(u.host, port), 10000)
            s.soTimeout = 15000
            buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${u.host}:$port\r\n")
                append("Connection: close\r\n")
                if (range != null) append("Range: $range\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII).let { s.getOutputStream().write(it) }
            val inp = s.getInputStream()
            val head = readHead(inp)
            val status = head.first().substringAfter(' ').substringBefore(' ').toInt()
            val headers = mutableMapOf<String, MutableList<String>>()
            head.drop(1).forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) {
                    headers.getOrPut(line.substring(0, i).trim()) { mutableListOf() }
                        .add(line.substring(i + 1).trim())
                }
            }
            val body = if (headers.first("Transfer-Encoding") == "chunked") {
                readChunked(inp)
            } else {
                val len = headers.first("Content-Length")?.toLongOrNull() ?: 0L
                readExact(inp, len)
            }
            return Triple(status, headers, body)
        }
    }

    private fun readHead(inp: java.io.InputStream): List<String> {
        val buf = java.io.ByteArrayOutputStream()
        val tail = ByteArray(4)
        var n = 0
        while (true) {
            val b = inp.read()
            if (b < 0) break
            buf.write(b)
            tail[n % 4] = b.toByte()
            n++
            if (n >= 4 && tail[(n - 4) % 4] == '\r'.code.toByte() &&
                tail[(n - 3) % 4] == '\n'.code.toByte() &&
                tail[(n - 2) % 4] == '\r'.code.toByte() &&
                tail[(n - 1) % 4] == '\n'.code.toByte()
            ) break
        }
        return buf.toString(Charsets.US_ASCII.name()).split("\r\n")
    }

    private fun readExact(inp: java.io.InputStream, len: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(16384)
        var left = len
        while (left > 0) {
            val r = inp.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r <= 0) break
            out.write(tmp, 0, r)
            left -= r
        }
        return out.toByteArray()
    }

    private fun readChunked(inp: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val line = String(readLine(inp)).trim()
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: break
            if (size <= 0) break
            out.write(readExact(inp, size))
            readLine(inp) // CRLF
        }
        return out.toByteArray()
    }

    private fun readLine(inp: java.io.InputStream): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = inp.read()
            if (b < 0) break
            if (prev == '\r'.code && b == '\n'.code) break
            if (prev >= 0) buf.write(prev)
            prev = b
        }
        return buf.toByteArray()
    }

    private fun Map<String, List<String>>.first(name: String): String =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull().orEmpty()

    private fun Map<String, List<String>>.contentType(): String = first("Content-Type")
    private fun Map<String, List<String>>.contentRange(): String = first("Content-Range")
    private fun Map<String, List<String>>.cors(): String = first("Access-Control-Allow-Origin")
    private fun Map<String, List<String>>.acceptRanges(): String = first("Accept-Ranges")

    /** Фейковый CDN: плейлисты, сегменты, режущийся на половине cut.ts. */
    private class FakeUpstream : NanoHTTPD("127.0.0.1", 0) {
        val segBytes: ByteArray = ByteArray(188 * 64) { i ->
            if (i % 188 == 0) 0x47 else (i % 251).toByte()
        }
        val cutBytes: ByteArray = ByteArray(256 * 1024) { (it % 251).toByte() }
        val seenRanges = CopyOnWriteArrayList<String?>()

        fun base() = "http://127.0.0.1:$listeningPort"

        override fun serve(session: IHTTPSession): Response = when {
            session.uri.endsWith("v720.m3u8") -> text(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n" +
                    "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:9.0,\nseg0.ts\n" +
                    "#EXTINF:9.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
            )
            // Без ENDLIST + абсолютный URL сегмента: реле чинит и переписывает.
            session.uri.endsWith("v1080.m3u8") -> text(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n" +
                    "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:9.0,\n${base()}/seg0.ts\n" +
                    "#EXTINF:9.0,\nseg1.ts\n"
            )
            session.uri.endsWith("seg0.ts") || session.uri.endsWith("seg1.ts") ->
                serveBytes(segBytes, "video/MP2T", session.headers["range"])
            session.uri.endsWith("cut.ts") -> {
                val range = session.headers["range"]?.takeIf { it.startsWith("bytes=") }
                seenRanges.add(range)
                if (range == null) {
                    // Обрыв edge: заявлена полная длина, тело — половина.
                    val half = cutBytes.size / 2
                    newFixedLengthResponse(
                        Response.Status.OK, "video/MP2T",
                        ByteArrayInputStream(cutBytes, 0, half), cutBytes.size.toLong()
                    )
                } else {
                    val from = range.substringAfter("bytes=").substringBefore('-').toLong().toInt()
                    val rest = cutBytes.size - from
                    val r = newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT, "video/MP2T",
                        ByteArrayInputStream(cutBytes, from, rest), rest.toLong()
                    )
                    r.addHeader("Content-Range", "bytes $from-${cutBytes.size - 1}/${cutBytes.size}")
                    r
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "nope")
        }

        private fun text(s: String): Response =
            newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", s)

        private fun serveBytes(b: ByteArray, mime: String, range: String?): Response {
            val rBytes = range?.takeIf { it.startsWith("bytes=") }
            seenRanges.add(rBytes)
            if (rBytes != null) {
                val from = rBytes.substringAfter("bytes=").substringBefore('-').toLong().toInt()
                val to = rBytes.substringAfter('-').takeIf { it.isNotEmpty() }?.toLong()?.toInt()
                    ?: (b.size - 1)
                val slice = b.copyOfRange(from, to + 1)
                val r = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mime,
                    ByteArrayInputStream(slice), slice.size.toLong()
                )
                r.addHeader("Content-Range", "bytes $from-$to/${b.size}")
                r.addHeader("Accept-Ranges", "bytes")
                return r
            }
            val r = newFixedLengthResponse(
                Response.Status.OK, mime, ByteArrayInputStream(b), b.size.toLong()
            )
            r.addHeader("Accept-Ranges", "bytes")
            return r
        }
    }
}
