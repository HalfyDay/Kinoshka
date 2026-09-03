package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.download.DownloadPhase
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.MediaDownloader
import hd.kinoshka.app.data.download.animeItemKey
import hd.kinoshka.app.data.download.offlineKey
import hd.kinoshka.app.data.download.tryRequestNotificationPermission
import hd.kinoshka.app.data.source.HentaiProvider

/**
 * Кнопка скачивания хентай-серии в офлайн-библиотеку (состояние: скачать/прогресс/скачано/ошибка).
 * Вынесена из общего DetailsScreen при его переезде в shared: она завязана на EpisodeDownloadManager
 * и системные уведомления, поэтому экран получает её платформенным слотом hentaiDownloadButton.
 */
@Composable
internal fun HentaiDownloadButton(
    title: String,
    kinopoiskId: Int,
    provider: HentaiProvider,
    label: String,
    episodeNumber: Int,
    episodeUrl: String?,
    headers: Map<String, String>
) {
    if (kinopoiskId <= 0 || episodeUrl.isNullOrBlank()) return
    val context = LocalContext.current
    val itemKey = animeItemKey(0, kinopoiskId)
    val translationId = if (label == "Фильм") "hentai:${provider.name}" else "hentai:${provider.name}:$label"
    val key = offlineKey(itemKey, provider.name, translationId, episodeNumber)
    val tasks by EpisodeDownloadManager.tasks.collectAsState()
    val library by EpisodeDownloadManager.library.collectAsState()
    val task = tasks[key]
    val downloaded = library.any { it.key == key }
    when {
        downloaded -> Icon(
            imageVector = Icons.Default.DownloadDone,
            contentDescription = "Скачано",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        task != null && task.phase == DownloadPhase.FAILED -> IconButton(
            onClick = { EpisodeDownloadManager.retry(key) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Повторить скачивание",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
        task != null -> IconButton(
            onClick = { EpisodeDownloadManager.cancel(key) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Отменить скачивание",
                modifier = Modifier.size(18.dp)
            )
        }
        else -> IconButton(
            onClick = {
                context.tryRequestNotificationPermission()
                EpisodeDownloadManager.enqueue(
                    EpisodeDownloadManager.EpisodeDownloadRequest(
                        itemKey = itemKey,
                        title = title,
                        source = provider.name,
                        translationId = translationId,
                        translationTitle = "${provider.displayName} · $label",
                        episodeNumber = episodeNumber,
                        episodeLabel = label,
                        resolve = {
                            MediaDownloader.MediaSource(episodeUrl, headers)
                        }
                    )
                )
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Скачать серию",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
