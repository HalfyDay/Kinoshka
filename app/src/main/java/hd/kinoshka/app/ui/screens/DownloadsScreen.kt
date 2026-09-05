package hd.kinoshka.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.download.DownloadPhase
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.OfflineEpisode
import hd.kinoshka.app.data.download.downloadProgressText
import hd.kinoshka.app.data.download.formatBytes
import hd.kinoshka.app.data.download.progressPercent
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import java.io.File

/**
 * Офлайн-библиотека приложения: активные скачивания с прогрессом и скачанные серии
 * по тайтлам. Серия играется локальным файлом/плейлистом через PlayerActivity —
 * mpv читает и видеофайлы, и локальные index.m3u8.
 * Шапка тайтла открывает его страницу ([onOpenTitle] — id в конвенции деталей).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenTitle: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val tasks by EpisodeDownloadManager.tasks.collectAsState()
    val library by EpisodeDownloadManager.library.collectAsState()
    var confirmClearAll by remember { mutableStateOf(false) }

    fun play(entry: OfflineEpisode) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.fromFile(File(entry.filePath))
            setClass(context, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("title", "${entry.title} — ${entry.episodeLabel}")
        }
        runCatching { context.startActivity(intent) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = "Загрузки",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${library.size} сер. · ${formatBytes(library.sumOf { it.sizeBytes })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (library.isNotEmpty()) {
                IconButton(onClick = { confirmClearAll = true }) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "Очистить всё",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        val activeTasks = tasks.values.sortedBy { it.title + it.episodeNumber.toString() }
        val grouped = library
            .groupBy { it.itemKey to it.title }
            .map { (key, episodes) ->
                Triple(key, key.second, episodes.sortedBy { it.episodeNumber })
            }
            .sortedByDescending { it.third.maxOf { it.downloadedAt } }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (activeTasks.isNotEmpty()) {
                item(key = "active-header") {
                    Text(
                        text = "Скачиваются",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp)
                    )
                }
                items(activeTasks.size, key = { "task:${activeTasks[it].key}" }) { index ->
                    val task = activeTasks[index]
                    ActiveDownloadRow(
                        title = "${task.title} · ${task.episodeLabel}",
                        subtitle = "${task.translationTitle} · " + when (task.phase) {
                            DownloadPhase.QUEUED -> "в очереди"
                            DownloadPhase.RESOLVING -> "поиск ссылки…"
                            DownloadPhase.DOWNLOADING -> downloadProgressText(task)
                            DownloadPhase.DONE -> "готово"
                            DownloadPhase.FAILED -> task.error ?: "ошибка"
                        },
                        progress = task.progressPercent?.let { it / 100f }
                            .takeIf { task.phase == DownloadPhase.DOWNLOADING },
                        downloading = task.phase == DownloadPhase.DOWNLOADING,
                        failed = task.phase == DownloadPhase.FAILED,
                        onCancel = { EpisodeDownloadManager.cancel(task.key) },
                        onRetry = { EpisodeDownloadManager.retry(task.key) }
                    )
                }
            }

            if (grouped.isEmpty() && activeTasks.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Пока ничего не скачано",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Кнопка загрузки на странице тайтла:\nскачивайте озвучки и серии для офлайн-просмотра",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            grouped.forEach { (key, title, episodes) ->
                item(key = "group:${key.first}") {
                    val filmId = downloadItemFilmId(key.first)
                    val poster = episodes.firstOrNull { it.posterUrl != null }?.posterUrl
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)
                            .let { m -> if (filmId != null) m.clickable { onOpenTitle(filmId) } else m },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (poster != null) {
                            KinoshkaAsyncImage(
                                model = poster,
                                contentDescription = title,
                                modifier = Modifier
                                    .size(width = 44.dp, height = 66.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${episodes.size} сер. · ${formatBytes(episodes.sumOf { it.sizeBytes })}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { EpisodeDownloadManager.deleteItem(key.first) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить тайтл из загрузок",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                items(episodes.size, key = { "ep:${episodes[it].key}" }) { index ->
                    val entry = episodes[index]
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(onClick = { play(entry) }, modifier = Modifier.size(34.dp)) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Играть офлайн",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.episodeLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${entry.translationTitle} · ${formatBytes(entry.sizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { EpisodeDownloadManager.delete(entry.key) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Удалить серию",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Очистить загрузки?") },
            text = { Text("Все скачанные серии будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    EpisodeDownloadManager.clearAll()
                    confirmClearAll = false
                }) { Text("Удалить всё", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Отмена") }
            }
        )
    }
}

/**
 * Ключ тайтла («a<shikimoriId>»/«k<kinopoiskId>») в id конвенции деталей;
 * null — ключ не распознан, шапка некликабельна.
 */
private fun downloadItemFilmId(itemKey: String): Int? {
    val shikimoriId = itemKey.removePrefix("a").toIntOrNull()?.takeIf { itemKey.startsWith("a") }
    if (shikimoriId != null) return shikimoriId + ANIME_ID_OFFSET
    return itemKey.removePrefix("k").toIntOrNull()?.takeIf { itemKey.startsWith("k") }
}

@Composable
private fun ActiveDownloadRow(
    title: String,
    subtitle: String,
    progress: Float?,
    downloading: Boolean,
    failed: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    // Та же карточка, что у скачанной серии: круг play слева, тексты, действия
    // справа; прогресс — тонкой полосой снизу. Отличие только в иконке круга
    // (загрузка вместо play) и цвете подложки прогресса.
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (downloading && progress == null) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(19.dp)
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (failed) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Повторить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = if (failed) "Скрыть" else "Отменить", modifier = Modifier.size(18.dp))
                }
            }
            if (progress != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }
        }
    }
}
