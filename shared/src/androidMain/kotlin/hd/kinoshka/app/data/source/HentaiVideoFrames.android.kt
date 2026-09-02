package hd.kinoshka.app.data.source

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Кадры из видео: доли длительности первой серии, по которым снимаются кадры. */
private val VIDEO_FRAME_FRACTIONS = listOf(0.08f, 0.22f, 0.36f, 0.50f, 0.64f, 0.78f)

/** retriever'ов работает параллельно: каждый seek — отдельное HTTP-соединение (~1.5с),
 *  и последовательные 6 кадров = 9+ секунд. 3 соединения на CDN — безопасно. */
private const val VIDEO_FRAME_PARALLELISM = 3

/** Длинная сторона сохраняемого кадра — 220dp-карточки и просмотрщика хватает. */
private const val VIDEO_FRAME_MAX_SIDE = 720

internal actual suspend fun grabVideoFrameFiles(
    stream: HentaiStream,
    dir: java.io.File,
    prefix: String
): List<java.io.File?> = withContext(Dispatchers.IO) {
    // Делим кадры между retriever'ами с сохранением исходного индекса: имя файла
    // (= порядок в UI) не зависит от параллелизма.
    val chunkSize = VIDEO_FRAME_FRACTIONS.size / VIDEO_FRAME_PARALLELISM + 1
    val chunks = VIDEO_FRAME_FRACTIONS
        .withIndex()
        .groupBy { it.index / chunkSize }
        .values
        .map { chunk -> chunk.sortedBy { it.index } }

    coroutineScope {
        chunks.map { chunk ->
            async(Dispatchers.IO) { grabFrames(stream, chunk, dir, prefix) }
        }.awaitAll()
    }.flatten()
}

/** Одна партия кадров своим retriever'ом: null — кадр снять не удалось. */
private fun grabFrames(
    stream: HentaiStream,
    chunk: List<IndexedValue<Float>>,
    dir: java.io.File,
    prefix: String
): List<java.io.File?> {
    val retriever = MediaMetadataRetriever()
    try {
        runCatching { retriever.setDataSource(stream.url, stream.headers) }.getOrElse {
            KLog.w(TAG, "video frames: setDataSource failed: ${it.javaClass.simpleName}")
            return List(chunk.size) { null }
        }
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0 } ?: return List(chunk.size) { null }
        // Декод сразу в целевом размере (API 27+): дешевле, чем полноразмерный кадр +
        // createScaledBitmap. Пропорции сохраняются по размерам видео.
        val srcW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val srcH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        var dstW = 0
        var dstH = 0
        if (srcW > 0 && srcH > 0 && maxOf(srcW, srcH) > VIDEO_FRAME_MAX_SIDE) {
            val scale = VIDEO_FRAME_MAX_SIDE.toFloat() / maxOf(srcW, srcH)
            dstW = (srcW * scale).toInt().coerceAtLeast(1)
            dstH = (srcH * scale).toInt().coerceAtLeast(1)
        }
        return chunk.map { (index, fraction) ->
            val timeUs = (durationMs * fraction).toLong() * 1000
            val bitmap = runCatching {
                if (dstW > 0 && Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        dstW,
                        dstH
                    )
                } else {
                    retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                }
            }.getOrNull() ?: return@map null
            val scaled = if (bitmap.width <= VIDEO_FRAME_MAX_SIDE && bitmap.height <= VIDEO_FRAME_MAX_SIDE) {
                bitmap
            } else {
                val resized = scaleDown(bitmap, VIDEO_FRAME_MAX_SIDE)
                if (resized !== bitmap) bitmap.recycle()
                resized
            }
            val file = java.io.File(dir, "$prefix$index.jpg")
            runCatching {
                java.io.FileOutputStream(file).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }.onFailure {
                file.delete()
                return@map null
            }
            scaled.recycle()
            file
        }
    } finally {
        runCatching { retriever.release() }
    }
}

private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxSide) return bitmap
    val scale = maxSide.toFloat() / largest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
    )
}

private const val TAG = "HentaiVideoFrames"
