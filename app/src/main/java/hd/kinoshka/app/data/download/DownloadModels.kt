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
    val error: String? = null
)

/** Человекочитаемый размер («1,4 ГБ»). */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.1f ГБ", gb)
        mb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.0f МБ", mb)
        kb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.0f КБ", kb)
        else -> "$bytes Б"
    }
}

/** Локальный поток для плеера: mpv играет файл/локальный плейлист без HTTP-заголовков. */
fun OfflineEpisode.toAnimeMediaStream(): hd.kinoshka.app.data.model.AnimeMediaStream =
    hd.kinoshka.app.data.model.AnimeMediaStream(
        url = filePath,
        qualities = emptyMap(),
        headers = emptyMap(),
        quality = "Offline",
        title = "$title — $episodeLabel"
    )
