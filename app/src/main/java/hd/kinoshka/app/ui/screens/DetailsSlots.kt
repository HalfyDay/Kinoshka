package hd.kinoshka.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import hd.kinoshka.app.data.cast.CastPlayback
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.download.DownloadBridges
import hd.kinoshka.app.data.download.DownloadPhase
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.MediaDownloader
import hd.kinoshka.app.data.download.animeItemKey
import hd.kinoshka.app.data.download.offlineKey
import hd.kinoshka.app.data.download.tryRequestNotificationPermission
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.source.HentaiProvider
import hd.kinoshka.app.data.source.MovieStreamResolver
import hd.kinoshka.app.ui.components.MovieDownloadTarget

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
            imageVector = Icons.Rounded.DownloadDone,
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
                imageVector = Icons.Rounded.Download,
                contentDescription = "Скачать серию",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * Кнопка скачивания серии своего 18+ источника: те же прямые ссылки, что у
 * [HentaiDownloadButton], но ключ — custom id (enum-провайдера у своих нет).
 * translationId зеркалит формат playCustomHentai ("hentai:custom:<id>[:<label>]"),
 * поэтому скачанное подхватывается local-first без сети.
 */
@Composable
internal fun CustomHentaiDownloadButton(
    title: String,
    kinopoiskId: Int,
    customId: String,
    customName: String,
    label: String,
    episodeNumber: Int,
    episodeUrl: String?,
    headers: Map<String, String>
) {
    if (kinopoiskId <= 0 || episodeUrl.isNullOrBlank()) return
    val context = LocalContext.current
    val itemKey = animeItemKey(0, kinopoiskId)
    val translationId = if (label == "Фильм") "hentai:custom:$customId" else "hentai:custom:$customId:$label"
    val key = offlineKey(itemKey, customId, translationId, episodeNumber)
    val tasks by EpisodeDownloadManager.tasks.collectAsState()
    val library by EpisodeDownloadManager.library.collectAsState()
    val task = tasks[key]
    val downloaded = library.any { it.key == key }
    when {
        downloaded -> Icon(
            imageVector = Icons.Rounded.DownloadDone,
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
                        source = customId,
                        translationId = translationId,
                        translationTitle = "$customName · $label",
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
                imageVector = Icons.Rounded.Download,
                contentDescription = "Скачать серию",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
/**
 * Постановка целей кино-пикера ([MovieDownloadTarget]) в очередь загрузок.
 * Сериалы идут через DownloadBridges.seriesRequests тем же путём, что переключение
 * серий в плеере (Kodik — HLS-резолв, прямые — готовые CDN-url); резолв ленивый,
 * выполняется очередью в момент скачивания.
 */
fun enqueueMovieDownload(context: Context, target: MovieDownloadTarget, preferredQuality: String? = null) {
    context.tryRequestNotificationPermission()
    if (target.kinopoiskId <= 0) {
        Toast.makeText(context, "Нет id тайтла для скачивания", Toast.LENGTH_SHORT).show()
        return
    }
    if (target.kind == MovieContentKind.SERIES) {
        val all = DownloadBridges.seriesRequests(
            target.kinopoiskId,
            target.displayTitle,
            target.request,
            target.candidates,
            target.translationId,
            target.dubTitle,
            target.episodes,
            target.isDirect,
            target.directHeaders,
            target.posterUrl,
            preferredQuality = preferredQuality
        )
        if (all.isEmpty()) {
            Toast.makeText(context, "Нет серий для скачивания", Toast.LENGTH_SHORT).show()
            return
        }
        // Офлайн-номер пакует сезон (зеркало DownloadBridges.offlineEpisodeNumber).
        val want = if (target.season > 0) target.season * 1000 + target.number else target.number
        val reqs = if (target.wholeDub) all else all.filter { it.episodeNumber == want }
        if (reqs.isEmpty()) {
            Toast.makeText(context, "Серия не найдена", Toast.LENGTH_SHORT).show()
            return
        }
        // Молчаливый no-op очереди (дубль/уже скачано) раньше всё равно рапортовал
        // «добавлено» — было похоже на зависшую очередь. Фильтруем и говорим честно.
        val fresh = reqs.filterNot { isAlreadyHandled(it) }
        if (fresh.isEmpty()) {
            Toast.makeText(
                context,
                if (reqs.all { isDownloaded(it) }) "Уже скачано" else "Уже в очереди",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        EpisodeDownloadManager.enqueueAll(fresh)
        Toast.makeText(
            context,
            if (target.wholeDub) "Озвучка добавлена в загрузки (${fresh.size} сер.)" else "Серия добавлена в загрузки",
            Toast.LENGTH_SHORT
        ).show()
    } else if (target.isKodik) {
        val req = EpisodeDownloadManager.EpisodeDownloadRequest(
            itemKey = animeItemKey(0, target.kinopoiskId),
            title = target.displayTitle,
            source = AnimeSourceType.KODIK.name,
            translationId = target.translationId,
            translationTitle = target.dubTitle,
            episodeNumber = 1,
            episodeLabel = "Фильм",
            posterUrl = target.posterUrl,
            resolve = {
                MovieStreamResolver.resolveMovieUrls(target.movieUrls)
                    ?.let { DownloadBridges.mediaSource(it, preferredQuality) }
            }
        )
        if (isAlreadyHandled(req)) {
            Toast.makeText(
                context,
                if (isDownloaded(req)) "Уже скачано" else "Уже в очереди",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        EpisodeDownloadManager.enqueue(req)
        Toast.makeText(context, "Фильм добавлен в загрузки", Toast.LENGTH_SHORT).show()
    } else {
        if (target.directUrl.isBlank()) {
            Toast.makeText(context, "Нет ссылки для скачивания", Toast.LENGTH_SHORT).show()
            return
        }
        val req = EpisodeDownloadManager.EpisodeDownloadRequest(
            itemKey = animeItemKey(0, target.kinopoiskId),
            title = target.displayTitle,
            source = AnimeSourceType.DDBB.name,
            translationId = target.translationId,
            translationTitle = target.dubTitle,
            episodeNumber = 1,
            episodeLabel = "Фильм",
            posterUrl = target.posterUrl,
            // Прямые URL тоже давим потолком качества (лестница даба из каталога,
            // иначе потолок внутри HLS-мастера) — раньше всегда качался максимум.
            resolve = {
                DownloadBridges.directCappedSource(target.request, target.directUrl, target.headers, preferredQuality)
            }
        )
        if (isAlreadyHandled(req)) {
            Toast.makeText(
                context,
                if (isDownloaded(req)) "Уже скачано" else "Уже в очереди",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        EpisodeDownloadManager.enqueue(req)
        Toast.makeText(context, "Фильм добавлен в загрузки", Toast.LENGTH_SHORT).show()
    }
}

/** Ключ очереди запроса — то же, что считает EpisodeDownloadManager внутри. */
private fun requestKey(req: EpisodeDownloadManager.EpisodeDownloadRequest): String =
    offlineKey(req.itemKey, req.source, req.translationId, req.episodeNumber)

private fun isDownloaded(req: EpisodeDownloadManager.EpisodeDownloadRequest): Boolean =
    EpisodeDownloadManager.findLibraryEntry(requestKey(req)) != null

/**
 * true — серия уже скачана либо висит в очереди (упавшие не в счёт: их можно
 * ставить заново). Честный тост вместо ложного «добавлено в загрузки».
 */
private fun isAlreadyHandled(req: EpisodeDownloadManager.EpisodeDownloadRequest): Boolean {
    if (isDownloaded(req)) return true
    val task = EpisodeDownloadManager.tasks.value[requestKey(req)] ?: return false
    return task.phase != DownloadPhase.FAILED
}

/**
 * Кнопка Chromecast в шапке страницы тайтла (слот castButton).
 * Устройство только подключается (диалог выбора ТВ), а серия/озвучка/источник
 * выбираются пользователем на странице пикера — той же, что открывает «Смотреть».
 * Никакого авто-выбора первого источника/озвучки/серии и авто-открытия пульта
 * с дефолтами здесь нет: onOpenCastPicker поднимает каст-пикер DetailsScreen,
 * результат пикера платформа льёт на ТВ и открывает пульт уже с явным выбором.
 */
@Composable
internal fun TitleCastButton(
    shikimoriId: Int,
    kinopoiskId: Int,
    title: String,
    isAnime: Boolean,
    onOpenCastPicker: () -> Unit
) {
    val context = LocalContext.current
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .build()
    }
    val owner = remember { Any() }
    var armed by remember { mutableStateOf(false) }

    // Одноразовое ожидание сессии после выбора ТВ в диалоге: при подключении
    // открываем пикер (выбор за пользователем), а не пульт с авто-выбором.
    DisposableEffect(armed) {
        if (armed) {
            runCatching { CastContext.getSharedInstance(context.applicationContext) }
            CastPlayback.init(
                context, owner,
                onSessionStarted = {
                    armed = false
                    onOpenCastPicker()
                },
                onSessionEnded = {}
            )
        }
        onDispose { CastPlayback.release(owner) }
    }

    IconButton(
        onClick = {
            // Сессия уже есть — сразу пикер, без повторного диалога ТВ.
            if (CastPlayback.isCasting) {
                onOpenCastPicker()
                return@IconButton
            }
            // Инициализация Cast (иначе диалог пуст) + обычный диалог без
            // фрагментов: MainActivity — ComponentActivity, а не FragmentActivity.
            runCatching { CastContext.getSharedInstance(context.applicationContext) }
            try {
                MediaRouteChooserDialog(context).apply {
                    routeSelector = selector
                    // Только отмена (назад/мимо): при выборе ТВ диалог
                    // закрывается сам через dismiss — ожидание оставляем.
                    setOnCancelListener { armed = false }
                }.also { armed = true }.show()
            } catch (e: Exception) {
                armed = false
                Toast.makeText(context, "Не удалось открыть выбор ТВ", Toast.LENGTH_SHORT).show()
            }
        }
    ) {
        Icon(
            imageVector = Icons.Filled.Cast,
            contentDescription = "Транслировать на ТВ",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}
