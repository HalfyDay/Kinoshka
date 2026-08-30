package hd.kinoshka.app.data.download

import kotlinx.serialization.Serializable

/**
 * Ключ единицы офлайн-библиотеки. [itemKey] однозначно определяет тайтл внутри приложения
 * («a<shikimoriId>» для аниме из шики-секции, «k<kinopoiskId>» для всего остального —
 * фильмов, сериалов и хентая), остальное — озвучка и серия внутри неё.
 */
fun offlineKey(itemKey: String, source: String, translationId: String, episodeNumber: Int): String =
    "$itemKey|$source|$translationId|$episodeNumber"

/** Ключ тайтла: приоритет — shikimori id (аниме), иначе сырой kinopoisk id. */
fun animeItemKey(shikimoriId: Int, kinopoiskId: Int): String =
    if (shikimoriId > 0) "a$shikimoriId" else "k$kinopoiskId"

/** Скачанная серия, готовая к офлайн-проигрыванию. */
@Serializable
data class OfflineEpisode(
    val itemKey: String,
    val title: String,
    /** AnimeSourceType.name либо имя хентай-провайдера. */
    val source: String,
    val translationId: String,
    val translationTitle: String,
    val episodeNumber: Int,
    /** Человекочитаемая метка («Серия 3», «Фильм», лейбл хентай-серии). */
    val episodeLabel: String,
    /** Каталог с медиа; для HLS совпадает с каталогом index.m3u8. */
    val dirPath: String,
    /** Играбельный путь: видеофайл либо локальный index.m3u8. */
    val filePath: String,
    val sizeBytes: Long,
    val downloadedAt: Long,
    val isHls: Boolean
) {
    val key: String get() = offlineKey(itemKey, source, translationId, episodeNumber)
}

/** Фаза активной задачи скачивания (список активных задач живёт только в памяти процесса). */
enum class DownloadPhase { QUEUED, RESOLVING, DOWNLOADING, DONE, FAILED }

/** Снимок активной задачи для UI (прогресс, отмена). */
data class DownloadTaskState(
    val key: String,
    val itemKey: String,
    val title: String,
    val source: String,
    val translationId: String,
    val translationTitle: String,
    val episodeNumber: Int,
    val episodeLabel: String,
    val phase: DownloadPhase,
    val bytesDone: Long = 0,
    val bytesTotal: Long = -1,
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    /** EMA-скорость скачивания; 0 — пока нет замера. */
    val speedBytesPerSec: Long = 0,
    /** true, когда bytesTotal для HLS оценён по среднему размеру сегмента, а не отдан сервером. */
    val sizeEstimated: Boolean = false,
    val error: String? = null
)

/** Процент выполнения 0..100: по сегментам (точнее на старте), иначе по байтам; null — total неизвестен. */
val DownloadTaskState.progressPercent: Int?
    get() = when {
        segmentsTotal > 0 -> (segmentsDone * 100 / segmentsTotal).coerceIn(0, 100)
        bytesTotal > 0 -> (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100)
        else -> null
    }

/** Строка «текущий размер / общий»: с «~» перед оценённым общим размером HLS. */
fun DownloadTaskState.sizeProgressText(): String? = when {
    bytesTotal > 0 -> "${formatBytes(bytesDone)} / ${if (sizeEstimated) "~" else ""}${formatBytes(bytesTotal)}"
    bytesDone > 0 -> formatBytes(bytesDone)
    else -> null
}

/** «5,3 МБ/с» — десятично-двоичный вывод в стиле [formatBytes]. */
fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.1f МБ/с", mb)
        kb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.0f КБ/с", kb)
        else -> "$bytesPerSec Б/с"
    }
}

/**
 * Единый текст прогресса для UI и уведомления: «45% · 1,2 ГБ / ~2,7 ГБ · 5,3 МБ/с».
 * Проценты — по сегментам, размер — из задачи, скорость — EMA-замер менеджера.
 */
fun downloadProgressText(task: DownloadTaskState): String = buildList {
    task.progressPercent?.let { add("$it%") }
    task.sizeProgressText()?.let { add(it) }
    formatSpeed(task.speedBytesPerSec).takeIf { it.isNotEmpty() }?.let { add(it) }
}.joinToString(" · ").ifEmpty { formatBytes(task.bytesDone) }

// formatBytes переехала в shared (jvmShared): hd.kinoshka.app.data.download.FormatBytes.kt —
// пакет тот же, все использования продолжают резолвиться.

/**
 * Локальный поток для плеера: mpv играет файл/локальный плейлист без HTTP-заголовков.
 * file://-URI обязателен: голый абсолютный путь через Uri.parse теряет схему и
 * отбрасывается валидацией запуска плеера (а пути с «#» ломаются без кодирования).
 */
fun OfflineEpisode.toAnimeMediaStream(): hd.kinoshka.app.data.model.AnimeMediaStream =
    hd.kinoshka.app.data.model.AnimeMediaStream(
        url = android.net.Uri.fromFile(java.io.File(filePath)).toString(),
        qualities = emptyMap(),
        headers = emptyMap(),
        quality = "Offline",
        title = "$title — $episodeLabel"
    )

/** file://-URI скачанной серии — для мест, строящих интент запуска плеера вручную. */
fun OfflineEpisode.toPlayableUriString(): String =
    android.net.Uri.fromFile(java.io.File(filePath)).toString()
