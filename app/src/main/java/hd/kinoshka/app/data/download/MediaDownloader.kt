package hd.kinoshka.app.data.download

import android.content.Context
import android.util.Log
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Движок скачивания медиа в офлайн-хранилище приложения. Два режима:
 *  - прямой файл (mp4/mkv/webm) — потоковое копирование с прогрессом;
 *  - HLS (m3u8): выбор лучшего варианта из мастер-плейлиста, скачивание сегментов
 *    (AES-128 расшифровывается на лету), локальный index.m3u8 с переписанными сегментами.
 *
 * Локальный плейлист играется самим mpv (ffmpeg hls demuxer читает файловые плейлисты),
 * поэтому пересборка контейнера не нужна. Диск — app-специфичное внешнее хранилище,
 * разрешений не требует.
 */
object MediaDownloader {
    private const val TAG = "MediaDownloader"

    data class MediaSource(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    data class MediaProgress(
        val bytesDone: Long = 0,
        val bytesTotal: Long = -1,
        val segmentsDone: Int = 0,
        val segmentsTotal: Int = 0
    )

    data class MediaFile(
        val filePath: String,
        val dirPath: String,
        val sizeBytes: Long,
        val isHls: Boolean
    )

    class DownloadException(message: String) : Exception(message)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun offlineRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "offline")

    /** Каталог серии: offline/<itemKey>/<source>/<trId>/ep_<n>/ — плейлист и сегменты живут там же. */
    fun episodeDir(context: Context, itemKey: String, source: String, translationId: String, episodeNumber: Int): File {
        val dir = File(
            offlineRoot(context),
            "${sanitize(itemKey)}/${sanitize(source)}/${sanitize(translationId)}/ep_$episodeNumber"
        )
        dir.mkdirs()
        return dir
    }

    private fun sanitize(raw: String): String =
        raw.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("").take(120)

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

    private fun httpGet(url: String, headers: Map<String, String>, range: String? = null): okhttp3.Response {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (!headers.containsKey("User-Agent")) builder.header("User-Agent", DEFAULT_UA)
        if (!range.isNullOrBlank()) builder.header("Range", range)
        return client.newCall(builder.build()).execute()
    }

    private fun fetchText(url: String, headers: Map<String, String>): String =
        httpGet(url, headers).use { resp ->
            if (!resp.isSuccessful) throw DownloadException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

    /**
     * Скачивает медиа в [dir]. Формат определяется автоматически: ссылка на .m3u8 — HLS;
     * иначе качаем как прямой файл, и если первые байты оказались плейлистом (#EXTM3U),
     * переключаемся на HLS (прямые ссылки ddbb иногда прячут HLS за «чистым» URL).
     */
    suspend fun download(
        source: MediaSource,
        dir: File,
        baseName: String,
        onProgress: (MediaProgress) -> Unit
    ): MediaFile = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        val looksHls = source.url.substringBefore('?').substringAfterLast('/').contains(".m3u8")
        if (looksHls) {
            val body = fetchText(source.url, source.headers)
            if (!body.contains("#EXTM3U")) throw DownloadException("Ожидался HLS-плейлист, получен другой ответ")
            downloadHls(body, source, dir, onProgress)
        } else {
            when (val direct = downloadDirect(source, dir, baseName, onProgress)) {
                is DirectOutcome.File -> direct.mediaFile
                is DirectOutcome.IsHls -> {
                    val body = fetchText(source.url, source.headers)
                    if (!body.contains("#EXTM3U")) throw DownloadException("Ожидался HLS-плейлист, получен другой ответ")
                    dir.listFiles()?.forEach { it.delete() }
                    downloadHls(body, source, dir, onProgress)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Прямой файл
    // ------------------------------------------------------------------

    private sealed interface DirectOutcome {
        data class File(val mediaFile: MediaFile) : DirectOutcome
        data object IsHls : DirectOutcome
    }

    private fun downloadDirect(
        source: MediaSource,
        dir: File,
        baseName: String,
        onProgress: (MediaProgress) -> Unit
    ): DirectOutcome {
        val ext = source.url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) && it != "m3u8" } ?: "mp4"
        val target = File(dir, "$baseName.$ext")
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                httpGet(source.url, source.headers).use { resp ->
                    if (!resp.isSuccessful) throw DownloadException("HTTP ${resp.code}")
                    val contentType = resp.header("Content-Type").orEmpty()
                    val input = resp.body?.byteStream() ?: throw DownloadException("Пустой ответ сервера")
                    val total = resp.body?.contentLength() ?: -1L
                    var done = 0L
                    var isPlaylist = false
                    input.use { stream ->
                        target.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                if (done == 0L) {
                                    // Direct-ссылки, прячущие плейлист: первые байты — "#EXTM3U".
                                    val head = String(buf, 0, n, Charsets.US_ASCII)
                                    isPlaylist = head.startsWith("#EXTM3U") ||
                                        (contentType.contains("mpegurl") && head.contains("#EXT"))
                                    if (isPlaylist) break
                                }
                                output.write(buf, 0, n)
                                done += n
                                onProgress(MediaProgress(bytesDone = done, bytesTotal = total))
                            }
                        }
                    }
                    if (isPlaylist) {
                        target.delete()
                        return DirectOutcome.IsHls
                    }
                    val size = target.length()
                    if (size <= 0) throw DownloadException("Пустой ответ сервера")
                    return DirectOutcome.File(
                        MediaFile(target.absolutePath, dir.absolutePath, size, isHls = false)
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "direct download attempt ${attempt + 1} failed: ${e.message}")
                target.delete()
            }
        }
        throw lastError ?: DownloadException("Скачивание не удалось")
    }

    // ------------------------------------------------------------------
    // HLS
    // ------------------------------------------------------------------

    private data class Variant(val url: String, val height: Int, val bandwidth: Long)

    private fun downloadHls(
        playlistBody: String,
        source: MediaSource,
        dir: File,
        onProgress: (MediaProgress) -> Unit
    ): MediaFile {
        val body = if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            val variantUrl = pickVariant(playlistBody, source.url)
            Log.i(TAG, "HLS master → variant $variantUrl")
            fetchText(variantUrl, source.headers)
        } else {
            playlistBody
        }
        val segments = parseSegments(body, source.url)
        if (segments.isEmpty()) throw DownloadException("Плейлист не содержит сегментов")

        // Ключ шифрования скачивается один раз на весь плейлист (в наших источниках ключ единый).
        val keyCache = HashMap<String, ByteArray>()
        var totalBytes = 0L
        val written = ArrayList<Pair<String, Double>>(segments.size)

        segments.forEachIndexed { index, seg ->
            val name = "seg_%04d%s".format(index, seg.ext)
            val target = File(dir, name)
            var lastError: Exception? = null
            var attempt = 0
            while (attempt < 3) {
                attempt += 1
                try {
                    httpGet(seg.url, source.headers, seg.rangeHeader).use { resp ->
                        if (!resp.isSuccessful) throw DownloadException("Сегмент $index: HTTP ${resp.code}")
                        var bytes = resp.body?.bytes() ?: ByteArray(0)
                        if (bytes.isEmpty()) throw DownloadException("Сегмент $index пуст")
                        val key = seg.key
                        if (key != null) {
                            bytes = decryptSegment(bytes, key, seg.iv, keyCache)
                        }
                        target.writeBytes(bytes)
                    }
                    lastError = null
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "segment $index attempt $attempt failed: ${e.message}")
                }
            }
            lastError?.let {
                target.delete()
                throw DownloadException("Сегмент ${index + 1}/${segments.size}: ${it.message}")
            }
            totalBytes += target.length()
            written += name to seg.durationSec
            onProgress(
                MediaProgress(
                    bytesDone = totalBytes,
                    bytesTotal = -1,
                    segmentsDone = index + 1,
                    segmentsTotal = segments.size
                )
            )
        }

        val initName = segments.first().mapUrl?.let {
            val initFile = File(dir, "init${segments.first().ext}")
            downloadInitSegment(segments.first(), source, initFile)
            initFile.name
        }

        val playlistFile = File(dir, "index.m3u8")
        writeLocalPlaylist(playlistFile, initName, written)
        return MediaFile(playlistFile.absolutePath, dir.absolutePath, totalBytes, isHls = true)
    }

    private fun downloadInitSegment(seg: Segment, source: MediaSource, target: File) {
        httpGet(seg.mapUrl!!, source.headers, seg.mapRange).use { resp ->
            if (!resp.isSuccessful) throw DownloadException("Init-сегмент: HTTP ${resp.code}")
            var bytes = resp.body?.bytes() ?: ByteArray(0)
            if (seg.mapKey != null) {
                bytes = decryptSegment(bytes, seg.mapKey, seg.mapIv, HashMap())
            }
            target.writeBytes(bytes)
        }
    }

    private fun pickVariant(masterBody: String, baseUrl: String): String {
        val variants = ArrayList<Variant>()
        val lines = masterBody.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(':')
                val height = Regex("""RESOLUTION=(\d+)x(\d+)""").find(attrs)
                    ?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var uri: String? = null
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) { uri = next; i = j; break }
                    if (next.startsWith("#EXT-X-STREAM-INF")) { uri = null; break }
                    j++
                }
                val resolved = uri?.let { resolveUrl(baseUrl, it) }
                if (resolved != null) variants += Variant(resolved, height, bandwidth)
            }
            i++
        }
        if (variants.isEmpty()) throw DownloadException("Мастер-плейлист без вариантов")
        // Лестница качества общая с плеером: точное совпадение по высоте из QUALITY_PREFERENCE_DESC,
        // иначе максимум по высоте, затем по bandwidth.
        val byPref = QUALITY_PREFERENCE_DESC.firstNotNullOfOrNull { pref ->
            val h = pref.substringBefore("p").toIntOrNull()
            variants.firstOrNull { it.height == h }
        }
        return (byPref ?: variants.maxWithOrNull(
            compareBy({ it.height }, { it.bandwidth })
        ) ?: variants.first()).url
    }

    private data class Segment(
        val url: String,
        val ext: String,
        val durationSec: Double,
        val rangeHeader: String?,
        val key: KeyRef?,
        val iv: ByteArray?,
        val mapUrl: String?,
        val mapRange: String?,
        val mapKey: KeyRef?,
        val mapIv: ByteArray?
    )

    private data class KeyRef(val uri: String, val ivHex: String?)

    private fun parseSegments(body: String, baseUrl: String): List<Segment> {
        data class Parsed(
            val url: String,
            val ext: String,
            val duration: Double,
            val rangeHeader: String?,
            val key: KeyRef?,
            val iv: ByteArray?
        )

        val mediaSequence = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val segments = ArrayList<Parsed>()
        var currentKey: KeyRef? = null
        var currentMapUrl: String? = null
        var currentMapRangeHeader: String? = null
        var pendingDuration = 0.0
        var pendingRange: String? = null
        var runningOffset = 0L
        var segmentIndex = 0L

        body.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD")
                    if (method == "NONE") {
                        currentKey = null
                    } else if (method == "AES-128") {
                        val uri = attr(line, "URI")?.let { resolveUrl(baseUrl, it) }
                        if (uri != null) currentKey = KeyRef(uri, attr(line, "IV"))
                    }
                    // SAMPLE-AES не расшифровываем — таких источников среди наших CDN нет.
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    currentMapUrl = attr(line, "URI")?.let { resolveUrl(baseUrl, it) }
                    val rawMapRange = attr(line, "BYTERANGE")
                    val mapLen = rawMapRange?.substringBefore('@')?.toLongOrNull()
                    val mapStart = rawMapRange?.substringAfter('@', "")?.toLongOrNull() ?: 0L
                    currentMapRangeHeader = if (rawMapRange != null && mapLen != null && mapLen > 0) {
                        "bytes=$mapStart-${mapStart + mapLen - 1}"
                    } else null
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':')
                        .substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = line.substringAfter(':').trim()
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val resolved = resolveUrl(baseUrl, line)
                    val rawRange = pendingRange
                    val rangeLen = rawRange?.substringBefore('@')?.toLongOrNull()
                    val rangeStart = rawRange?.substringAfter('@', "")?.toLongOrNull() ?: runningOffset
                    val rangeHeader = if (rawRange != null && rangeLen != null && rangeLen > 0) {
                        "bytes=$rangeStart-${rangeStart + rangeLen - 1}"
                    } else null
                    runningOffset = if (rangeHeader != null) rangeStart + rangeLen!! else 0L
                    segments += Parsed(
                        resolved,
                        segmentExt(resolved),
                        pendingDuration,
                        rangeHeader,
                        currentKey,
                        currentKey?.ivHex?.let(::ivFromHex) ?: ivFromSequence(mediaSequence + segmentIndex)
                    )
                    segmentIndex++
                    pendingDuration = 0.0
                    pendingRange = null
                }
            }
        }
        return segments.map { p ->
            Segment(
                url = p.url,
                ext = p.ext,
                durationSec = p.duration,
                rangeHeader = p.rangeHeader,
                key = p.key,
                iv = p.iv,
                mapUrl = currentMapUrl,
                mapRange = currentMapRangeHeader,
                mapKey = currentKey,
                mapIv = currentKey?.ivHex?.let(::ivFromHex) ?: ivFromSequence(0)
            )
        }
    }

    /** "#EXT-X-BYTERANGE:<len>[@<offset>]" → заголовок Range. Offset без @ — продолжение. */
    private var byterangeRunningOffset = 0L
    private fun byterangeHeader(byterange: String): String {
        val len = byterange.substringBefore('@').toLongOrNull() ?: return ""
        val offset = byterange.substringAfter('@', "").toLongOrNull()
        val start = offset ?: byterangeRunningOffset
        byterangeRunningOffset = start + len
        return if (len > 0) "bytes=$start-${start + len - 1}" else ""
    }

    private fun segmentExt(url: String): String {
        val path = url.substringBefore('?')
        return when {
            path.endsWith(".ts") -> ".ts"
            path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".cmf") -> ".m4s"
            path.endsWith(".aac") || path.endsWith(".mp3") -> ".aac"
            else -> ".ts"
        }
    }

    private fun attr(line: String, name: String): String? {
        val regex = Regex("""$name=("([^"]*)"|[^,]*)""")
        return regex.find(line)?.let { m ->
            (m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: m.groupValues.getOrNull(1))
        }
    }

    private fun ivFromHex(hex: String): ByteArray? = runCatching {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }.getOrNull()

    private fun ivFromSequence(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) {
            iv[15 - i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
        }
        return iv
    }

    private fun decryptSegment(bytes: ByteArray, key: KeyRef, iv: ByteArray?, keyCache: MutableMap<String, ByteArray>): ByteArray {
        val keyBytes = keyCache.getOrPut(key.uri) {
            httpGet(key.uri, emptyMap()).use { resp ->
                if (!resp.isSuccessful) throw DownloadException("Ключ HLS: HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }.also { if (it.size != 16) throw DownloadException("Ключ HLS неверной длины") }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv ?: ivFromSequence(0)))
        return cipher.doFinal(bytes)
    }

    private fun writeLocalPlaylist(target: File, initName: String?, written: List<Pair<String, Double>>) {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        val targetDuration = (written.maxOfOrNull { it.second } ?: 10.0).let { if (it < 1.0) 10.0 else it }.toInt() + 1
        sb.append("#EXT-X-TARGETDURATION:$targetDuration\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        if (initName != null) sb.append("#EXT-X-MAP:URI=\"$initName\"\n")
        written.forEach { (name, duration) ->
            sb.append("#EXTINF:${"%.3f".format(duration)}\n")
            sb.append(name).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        target.writeText(sb.toString())
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { java.net.URI(base).resolve(ref).toString() }
            .getOrElse { ref }
    }
}
