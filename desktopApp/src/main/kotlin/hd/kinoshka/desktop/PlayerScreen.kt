package hd.kinoshka.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.UserStateStoreBase
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.source.KodikMovieParser
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.data.model.PendingMovieRequestStore
import hd.kinoshka.app.data.playback.MovieNativeLauncher
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.player.desktop.MpvPlayer
import hd.kinoshka.app.ui.components.KinoLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

private const val DEMO_URL =
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"

/** Порог реального просмотра до коммита в библиотеку/историю — как в Android-плеере. */
private const val MIN_WATCH_SECONDS_FOR_LIBRARY = 300

/** Заголовок окна плеера (по нему ищется HWND для z-порядка окна видео). */
const val PLAYER_WINDOW_TITLE = "Kino Player"

/** Заголовок отдельного окна, в которое рендерит mpv; живёт ПОД окном плеера. */
private const val VIDEO_WINDOW_TITLE = "KinoVideoSurface"

/** Задержка автоскрытия контролов — как playerTimeToDisappear mpvEx по умолчанию. */
private const val CONTROLS_HIDE_DELAY_MS = 3500L

/** Шаг перемотки стрелками — как в mpvEx. */
private const val SEEK_STEP_SECONDS = 10.0

/** Payload запуска воспроизведения — зеркально onOpenNativePlayer общего DetailsScreen. */
data class PlayerLaunchArgs(
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val qualities: Map<String, String> = emptyMap(),
    val title: String,
    val episodeNumber: Int = 1,
    val shikimoriId: Int = 0,
    val kinopoiskId: Int = 0,
    val sourceType: String = "DEMO",
    val episodes: List<AnimeEpisode> = emptyList(),
    val translations: List<FlatTranslation> = emptyList(),
    val currentTranslationId: String = "",
    val seriesContext: MovieSeriesPlaybackContext? = null,
    // Полнота запроса резолва: на Android их кладёт в PendingMovieRequestStore общий
    // DetailsScreen; для дебаг-запуска плеера напрямую (KINO_SCREEN=player) их же
    // заполняет Main из repository.details.
    val imdbId: String? = null,
    val year: Int? = null,
    val nameEn: String? = null,
    val originalTitle: String? = null,
    val seriesKind: Boolean = false,
)

private data class LoadCommand(val url: String, val headers: Map<String, String> = emptyMap())

/** Пункт dropdown'а серий: key — AnimeEpisode.number либо MovieEpisodeRef.playerEpisodeKey. */
private data class EpisodeChoice(val key: Int, val label: String)

private data class DropdownOption(val id: String, val label: String)

/**
 * Полноэкранное окно плеера в стиле mpvEx: безрамочное, прозрачное, с оверлей-контролами.
 * Видео рендерит mpv в отдельное окно, положенное ПОД это окно — поэтому Compose-контролы
 * остаются кликабельными поверх видео (SwingPanel такой режим не даёт).
 */
@Composable
fun PlayerWindow(
    args: PlayerLaunchArgs,
    userStateStore: UserStateStoreBase?,
    onClose: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screen = remember { Toolkit.getDefaultToolkit().screenSize }
    val widthDp = with(density) { screen.width.toFloat().toDp() }
    val heightDp = with(density) { screen.height.toFloat().toDp() }
    val state = remember(widthDp, heightDp) {
        WindowState(width = widthDp, height = heightDp, position = WindowPosition(0.dp, 0.dp))
    }
    Window(
        onCloseRequest = onClose,
        title = PLAYER_WINDOW_TITLE,
        state = state,
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        KinoDesktopTheme(themeMode = AppThemeMode.DARK) {
            PlayerScreen(args = args, userStateStore = userStateStore, onBack = onClose)
        }
    }
}

/**
 * Отдельное AWT-окно под окном плеера, в которое mpv пишет картинку (wid).
 * Каждую секунду переподтверждает z-порядок (видео ниже окна плеера) и геометрию.
 */
@Composable
private fun VideoBehindSurface(
    onPlayer: (MpvPlayer) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val screen = Toolkit.getDefaultToolkit().screenSize
        // Диалог без рамки с уникальным заголовком: FindWindow находит его HWND,
        // при этом диалог не появляется в панели задач и не забирает фокус.
        val videoWindow = Dialog(null as Dialog?, VIDEO_WINDOW_TITLE)
        videoWindow.isUndecorated = true
        videoWindow.focusableWindowState = false
        videoWindow.background = java.awt.Color.BLACK
        videoWindow.bounds = Rectangle(0, 0, screen.width, screen.height)
        videoWindow.isVisible = true

        var player: MpvPlayer? = null
        val attachJob = scope.launch(Dispatchers.IO) {
            var hwnd: Long? = null
            var waited = 0
            while (hwnd == null && waited < 50) {
                hwnd = Win32.findWindowHwnd(VIDEO_WINDOW_TITLE)
                if (hwnd == null) {
                    delay(100)
                    waited++
                }
            }
            val handle = hwnd ?: run {
                withContext(Dispatchers.Main) { onError("Окно видео не найдено") }
                return@launch
            }
            println("MPV: video window hwnd=$handle")
            try {
                val created = MpvPlayer.create(handle)
                player = created
                withContext(Dispatchers.Main) { onPlayer(created) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { onError(t.message ?: "не удалось запустить mpv") }
            }
        }
        val syncJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val playerHwnd = Win32.findWindowHwnd(PLAYER_WINDOW_TITLE)
                val videoHwnd = Win32.findWindowHwnd(VIDEO_WINDOW_TITLE)
                if (playerHwnd != null && videoHwnd != null) {
                    Win32.moveWindow(videoHwnd, 0, 0, screen.width, screen.height)
                    Win32.setZOrderBelow(videoHwnd, playerHwnd)
                }
                delay(1000)
            }
        }
        onDispose {
            attachJob.cancel()
            syncJob.cancel()
            runCatching { player?.close() }
            videoWindow.dispose()
        }
    }
}

@Composable
fun PlayerScreen(
    args: PlayerLaunchArgs,
    userStateStore: UserStateStoreBase? = null,
    onBack: () -> Unit,
) {
    var player by remember { mutableStateOf<MpvPlayer?>(null) }
    var pending by remember { mutableStateOf<LoadCommand?>(null) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolveRetry by remember { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    // Озвучки: приходят из Details (или из resolve для PENDING); выбор = ленивое извлечение
    // потока + перемотка на текущую позицию.
    var translations by remember { mutableStateOf(args.translations) }
    var currentTranslationId by remember { mutableStateOf(args.currentTranslationId) }
    // Качества активного потока (ladder от резолвера); выбор = смена URL + resume.
    var qualities by remember { mutableStateOf(args.qualities) }
    var currentQuality by remember { mutableStateOf<String?>(null) }
    var resumeAt by remember { mutableStateOf(0.0) }
    var volume by remember { mutableStateOf(100) }
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    // mpvEx-контролы: оверлей с автоскрытием, меню, перемотка.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var anyMenuOpen by remember { mutableStateOf(false) }
    var volumePopupOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun poke() {
        lastInteractionMs = System.currentTimeMillis()
        controlsVisible = true
    }

    // Автоскрытие контролов: как в mpvEx — при паузе и открытых меню не прячем.
    LaunchedEffect(controlsVisible, paused, isSeeking, anyMenuOpen, volumePopupOpen, lastInteractionMs) {
        if (controlsVisible && paused == false && !isSeeking && !anyMenuOpen && !volumePopupOpen) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    // Фокус окна плеера: клавиатура (пробел/стрелки/M/Esc) приходит в корень контента.
    LaunchedEffect(Unit) {
        while (true) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) break
            delay(100)
        }
    }

    fun scheduleLoad(url: String, headers: Map<String, String>, resume: Boolean) {
        if (resume && duration > 1.0) resumeAt = position
        pending = LoadCommand(url, headers)
    }

    // Список серий: movie-series контекст (готовые playerUrl) либо anime-эпизоды
    // (ленивый resolveStream по выбранной озвучке).
    val seriesEpisodes = args.seriesContext?.episodes
    val episodeChoices: List<EpisodeChoice> = when {
        seriesEpisodes != null -> seriesEpisodes.map { ep ->
            EpisodeChoice(
                ep.playerEpisodeKey,
                "S%02dE%02d".format(ep.seasonNumber, ep.episodeNumber) +
                    (ep.title?.let { " — $it" } ?: "")
            )
        }
        args.episodes.isNotEmpty() -> args.episodes.map { ep ->
            EpisodeChoice(
                ep.number,
                if ((ep.season ?: 0) > 1) "Сезон ${ep.season}, серия ${ep.number}" else "Серия ${ep.number}"
            )
        }
        else -> emptyList()
    }
    var currentEpisodeKey by remember(args.title) { mutableStateOf(args.episodeNumber) }

    // Per-title ключ памяти озвучек — как dubMemoryMediaKey в Android-плеере:
    // kp:<kinopoiskId> для фильмов/сериалов, sh:<shikimoriId> для аниме.
    val dubMediaKey: String? = when {
        args.kinopoiskId > 0 -> "kp:${args.kinopoiskId}"
        args.shikimoriId > 0 -> "sh:${args.shikimoriId}"
        else -> null
    }

    fun recordDubChoice(title: String) {
        val store = userStateStore ?: return
        val dubKey = MovieNativeLauncher.splitDubTrack(title).first
        store.recordDubUsage(dubKey)
        dubMediaKey?.let { store.recordTitleDubUsage(it, dubKey) }
    }

    // Стабильный идентификатор media-файла для resume-позиции — те же схемы, что
    // stableKinoshkaIdentifier в Android-плеере: URL потоков ротируются, позиция
    // ключуется тайтлом/серией.
    fun mediaIdFor(episodeKey: Int): String? = when {
        seriesEpisodes != null -> seriesEpisodes
            .firstOrNull { it.playerEpisodeKey == episodeKey }
            ?.let { "ks_series_${args.kinopoiskId}_s${it.seasonNumber}e${it.episodeNumber}" }
        args.episodes.isNotEmpty() -> {
            val key = args.shikimoriId.takeIf { it > 0 } ?: args.kinopoiskId.takeIf { it > 0 }
            key?.let { "ks_anime_${it}_e$episodeKey" }
        }
        args.kinopoiskId > 0 -> "ks_movie_${args.kinopoiskId}"
        else -> null
    }

    fun currentMediaId(): String? = mediaIdFor(currentEpisodeKey)

    fun savedResumeFor(episodeKey: Int): Double =
        userStateStore?.let { store -> mediaIdFor(episodeKey)?.let { store.getPlaybackPosition(it) } } ?: 0.0

    /** Сохранить позицию текущей серии: перед переключением, в периодическом тике и при закрытии. */
    fun flushPlaybackPosition(pos: Double = position, dur: Double = duration) {
        val store = userStateStore ?: return
        val id = currentMediaId() ?: return
        if (pos >= 10.0) store.savePlaybackPosition(id, pos, dur)
    }

    fun switchEpisode(choice: EpisodeChoice) {
        scope.launch {
            val contextEpisodes = seriesEpisodes
            if (contextEpisodes != null) {
                val ep = contextEpisodes.firstOrNull { it.playerEpisodeKey == choice.key } ?: return@launch
                // flush уходящей серии ДО переустановки currentEpisodeKey — идентификатор ещё старый.
                flushPlaybackPosition()
                currentEpisodeKey = choice.key
                val headers = if (args.seriesContext?.isDirectSource == true) {
                    args.seriesContext?.directHeaders ?: args.headers
                } else {
                    args.headers
                }
                scheduleLoad(ep.playerUrl, headers, resume = false)
            } else {
                val ep = args.episodes.firstOrNull { it.number == choice.key } ?: return@launch
                val sourceType = AnimeSourceType.entries.firstOrNull { it.name == args.sourceType }
                    ?: AnimeSourceType.KODIK
                val trId = currentTranslationId.ifEmpty { args.currentTranslationId }
                val stream = withContext(Dispatchers.IO) {
                    AnimeStreamResolver.resolveStream(args.shikimoriId, args.title, sourceType, trId, ep.number)
                }
                if (stream == null) {
                    resolveError = "Не удалось открыть ${choice.label.lowercase()}"
                    return@launch
                }
                flushPlaybackPosition()
                currentEpisodeKey = choice.key
                qualities = stream.qualities
                currentQuality = stream.quality
                scheduleLoad(stream.url, stream.headers, resume = false)
            }
        }
    }

    fun switchQuality(name: String) {
        val url = qualities[name] ?: return
        currentQuality = name
        scheduleLoad(url, args.headers, resume = true)
    }

    fun switchTranslation(translation: FlatTranslation) {
        val link = translation.episodes.firstOrNull()?.link ?: return
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    val ladder = AnimeStreamResolver.resolveKodikHls(
                        AnimeStreamResolver.absoluteKodikUrl(link)
                    )
                    val best = QUALITY_PREFERENCE_DESC.firstOrNull { ladder.containsKey(it) }
                        ?: ladder.keys.firstOrNull()
                    best?.let { ladder.getValue(it) }
                }.getOrNull()
            }
            if (resolved == null) {
                resolveError = "Не удалось извлечь поток озвучки «${translation.title}»"
                return@launch
            }
            currentTranslationId = translation.translationId
            recordDubChoice(translation.title)
            scheduleLoad(resolved, AnimeStreamResolver.kodikPlaybackHeaders(), resume = true)
        }
    }

    fun togglePause() {
        scope.launch(Dispatchers.IO) { player?.let { paused = it.togglePause() } }
    }

    fun seekBy(delta: Double) {
        scope.launch(Dispatchers.IO) {
            val current = player ?: return@launch
            val target = (current.positionSeconds() ?: 0.0) + delta
            current.seekTo(target)
            position = target
        }
    }

    fun changeVolume(delta: Int) {
        volume = (volume + delta).coerceIn(0, 130)
        if (muted) {
            muted = false
            scope.launch(Dispatchers.IO) { player?.setMuted(false) }
        }
        val v = volume
        scope.launch(Dispatchers.IO) { player?.setVolume(v) }
    }

    fun toggleMute() {
        val next = !muted
        muted = next
        scope.launch(Dispatchers.IO) { player?.setMuted(next) }
    }

    // Стартовый поток: прямой (anime/quality-only/трейлер/серия контекста), демо-клип,
    // либо PENDING — полный резолв гонкой Kodik↔ddbb (общий MovieNativeLauncher).
    // Полный request берём из PendingMovieRequestStore (его кладёт общий DetailsScreen —
    // год/imdb/оригинальное название нужны для identity-матча каталога), иначе собираем
    // из аргументов.
    LaunchedEffect(args.title, args.kinopoiskId, args.sourceType, resolveRetry) {
        when {
            args.sourceType == "DEMO" -> pending = LoadCommand(DEMO_URL)
            args.streamUrl.isNotBlank() -> {
                currentQuality = args.qualities.entries
                    .firstOrNull { it.value == args.streamUrl }?.key
                if (seriesEpisodes != null) {
                    // Контекст сериала: ключ стартовой серии — из контекста, не args.episodeNumber.
                    currentEpisodeKey = args.seriesContext?.currentEpisode?.playerEpisodeKey
                        ?: args.episodeNumber
                }
                resumeAt = savedResumeFor(currentEpisodeKey)
                pending = LoadCommand(args.streamUrl, args.headers)
            }
            args.sourceType == "PENDING" -> withContext(Dispatchers.IO) {
                resolving = true
                resolveError = null
                println("MPV: PENDING resolve kp=${args.kinopoiskId} (retry=$resolveRetry)")
                val stored = PendingMovieRequestStore.get(args.kinopoiskId)
                val request = stored?.request ?: MoviePlaybackRequest(
                    kinopoiskId = args.kinopoiskId.takeIf { it > 0 },
                    imdbId = KodikMovieParser.normalizeImdb(args.imdbId),
                    titles = listOfNotNull(args.title, args.nameEn, args.originalTitle)
                        .map(String::trim).filter(String::isNotEmpty),
                    year = args.year,
                    kind = if (args.seriesKind) MovieContentKind.SERIES else MovieContentKind.MOVIE,
                )
                when (val payload = MovieNativeLauncher.resolve(
                    request,
                    profile = null,
                    stateStore = userStateStore
                )) {
                    is MovieNativeLauncher.NativeLaunchPayload.QualityOnlyMovie -> {
                        println("MPV: PENDING → QualityOnlyMovie: ${payload.stream.url.take(90)}")
                        translations = payload.translations
                        qualities = payload.stream.qualities
                        currentQuality = payload.stream.quality
                        resumeAt = savedResumeFor(currentEpisodeKey)
                        pending = LoadCommand(payload.stream.url, payload.stream.headers)
                    }
                    is MovieNativeLauncher.NativeLaunchPayload.MovieSeries -> {
                        val episode = payload.context.currentEpisode
                        println("MPV: PENDING → MovieSeries ep=${episode.playerEpisodeKey}")
                        currentEpisodeKey = episode.playerEpisodeKey
                        qualities = payload.stream.qualities
                        currentQuality = payload.stream.quality
                        resumeAt = savedResumeFor(episode.playerEpisodeKey)
                        pending = LoadCommand(episode.playerUrl, payload.stream.headers)
                    }
                    is MovieNativeLauncher.NativeLaunchPayload.Failed -> {
                        println("MPV: PENDING resolve failed: ${payload.reason}")
                        resolveError = "Поток недоступен: ${payload.reason}"
                    }
                }
                resolving = false
            }
            else -> resolveError = "Нет потока для воспроизведения"
        }
    }

    // Загрузка: когда есть и плеер, и что грузить. Переключения озвучки/качества
    // resume-ятся на позицию, где стояли.
    LaunchedEffect(player, pending) {
        val current = player ?: return@LaunchedEffect
        val command = pending ?: return@LaunchedEffect
        if (loadedKey == command.url) return@LaunchedEffect
        loadedKey = command.url
        withContext(Dispatchers.IO) {
            current.setHeaders(command.headers)
            val rc = current.load(command.url)
            println("MPV: loadfile rc=$rc")
            val resume = resumeAt
            resumeAt = 0.0
            if (resume > 1.0) {
                // Ждём, пока mpv распознает файл (появится duration), затем перематываем.
                var waited = 0
                while (waited < 40 && (current.durationSeconds() ?: 0.0) <= 0.0) {
                    delay(250)
                    waited++
                }
                current.seekTo(resume)
                println("MPV: resume на $resume c после переключения")
            }
        }
    }

    // Опрос позиции/длительности для полосы прогресса.
    LaunchedEffect(player) {
        while (player != null) {
            val current = player
            if (current != null) {
                withContext(Dispatchers.IO) {
                    position = current.positionSeconds() ?: 0.0
                    duration = current.durationSeconds() ?: 0.0
                }
            }
            delay(500)
        }
    }

    // Прогресс просмотра — зеркало startPlaybackProgressLoop Android-плеера: после
    // MIN_WATCH_SECONDS_FOR_LIBRARY реального просмотра тайтл уходит в библиотеку и историю
    // (commitRealPlayback), сериал/аниме дополнительно фиксирует текущую серию. Один коммит
    // на media+серию: смена серии перевзводит, но новая должна сама досмотреть свои 5 минут.
    LaunchedEffect(player, userStateStore, dubMediaKey) {
        val store = userStateStore ?: return@LaunchedEffect
        var committedFor: String? = null
        var lastPositionSaveAt = 0L
        while (true) {
            delay(5000)
            val current = player ?: continue
            val mediaKey = dubMediaKey ?: continue
            val pos = withContext(Dispatchers.IO) { current.positionSeconds() } ?: continue
            // Периодический автосейв resume-позиции (раз в ~30 c), независимо от порога коммита.
            if (pos > 10.0) {
                val now = System.currentTimeMillis()
                if (now - lastPositionSaveAt >= 30_000) {
                    lastPositionSaveAt = now
                    val dur = withContext(Dispatchers.IO) { current.durationSeconds() } ?: 0.0
                    currentMediaId()?.let { id -> store.savePlaybackPosition(id, pos, dur) }
                }
            }
            val watchId = "$mediaKey#${currentEpisodeKey}"
            if (watchId == committedFor) continue
            if (pos < MIN_WATCH_SECONDS_FOR_LIBRARY) continue
            committedFor = watchId
            val libraryKey = if (args.shikimoriId > 0) args.shikimoriId + ANIME_ID_OFFSET else args.kinopoiskId
            withContext(Dispatchers.IO) {
                val contextEpisodes = seriesEpisodes
                if (contextEpisodes != null) {
                    contextEpisodes.firstOrNull { it.playerEpisodeKey == currentEpisodeKey }?.let { ep ->
                        store.updateSeriesProgress(args.kinopoiskId, ep.seasonNumber, ep.episodeNumber)
                    }
                } else if (args.shikimoriId > 0) {
                    store.updateWatchedEpisodeByKey(
                        kinopoiskId = libraryKey,
                        animeTitle = args.title,
                        episodeNum = currentEpisodeKey,
                        totalEpisodes = args.episodes.maxOfOrNull { it.number } ?: 0,
                        allowComplete = false,
                    )
                }
                store.commitRealPlayback(libraryKey)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { flushPlaybackPosition() }
    }

    // mpv привязывается к окну видео из VideoBehindSurface ниже.

    val keyboardHandler: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.Spacebar -> { poke(); togglePause(); true }
                Key.DirectionLeft -> { poke(); seekBy(-SEEK_STEP_SECONDS); true }
                Key.DirectionRight -> { poke(); seekBy(SEEK_STEP_SECONDS); true }
                Key.DirectionUp -> { poke(); changeVolume(10); true }
                Key.DirectionDown -> { poke(); changeVolume(-10); true }
                Key.M -> { poke(); toggleMute(); true }
                Key.Escape -> { onBack(); true }
                else -> false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Пока mpv не подключён (или упал) — фон чёрный; с живым видео окно
            // прозрачное: картинка приходит из окна, положенного под это.
            .background(if (player == null || attachError != null) Color.Black else Color.Transparent)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(keyboardHandler)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    controlsVisible = !controlsVisible
                    lastInteractionMs = System.currentTimeMillis()
                })
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) poke()
                    }
                }
            },
    ) {
        // resolveRetry пересоздаёт поверхность видео: «Повторить» после ошибки attach.
        key(resolveRetry) {
            VideoBehindSurface(onPlayer = { player = it }, onError = { attachError = it })
        }

        val error = attachError ?: resolveError
        if (error != null) {
            // mpvEx pendingOverlay: тёмный фон, причина, «Повторить»/«Назад».
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 40.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(44.dp))
                    Text("Не удалось открыть поток", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White)
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                                // Перезапуск: пересоздаёт поверхность видео (attach-ошибка)
                                // и повторяет PENDING-резолв.
                                attachError = null
                                resolveError = null
                                resolveRetry++
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Filled.Refresh, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Text("Повторить", color = Color.White, fontSize = 13.sp)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onBack() },
                        ) {
                            Text(
                                "Назад",
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        } else if (resolving) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                KinoLoadingIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // Оверлей контролов в стиле mpvEx.
        AnimatedVisibility(
            visible = controlsVisible && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.8f),
                            0.35f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        )
                    ),
            ) {
                // Верхняя строка: назад, серии, озвучки, название | скорость.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlayerIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onClick = onBack)
                    if (episodeChoices.isNotEmpty()) {
                        PlayerDropdownPill(
                            prefix = null,
                            options = episodeChoices.map { DropdownOption(it.key.toString(), it.label) },
                            selectedId = currentEpisodeKey.toString(),
                            onOpenChange = { anyMenuOpen = it },
                            onSelect = { id -> episodeChoices.firstOrNull { it.key.toString() == id }?.let(::switchEpisode) },
                        )
                    }
                    if (translations.size > 1) {
                        PlayerDropdownPill(
                            prefix = null,
                            options = translations.map { DropdownOption(it.translationId, it.title) },
                            selectedId = currentTranslationId.ifEmpty { translations.firstOrNull()?.translationId },
                            onOpenChange = { anyMenuOpen = it },
                            onSelect = { id ->
                                translations.firstOrNull { it.translationId == id }?.let(::switchTranslation)
                            },
                        )
                    }
                    PlayerTitlePill(title = args.title)
                    Spacer(Modifier.weight(1f))
                    PlayerDropdownPill(
                        prefix = null,
                        options = SPEED_OPTIONS.map { DropdownOption(it.toString(), "%.2fx".format(it)) },
                        selectedId = speed.toString(),
                        onOpenChange = { anyMenuOpen = it },
                        onSelect = { id ->
                            val next = id.toFloatOrNull() ?: 1f
                            speed = next
                            scope.launch(Dispatchers.IO) { player?.setSpeed(next) }
                        },
                    )
                }

                // Центр: пауза/плей + пред./след. серия (как в mpvEx — только с сериями).
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        val episodeIdx = episodeChoices.indexOfFirst { it.key == currentEpisodeKey }
                        val episodeNav = episodeChoices.size > 1
                        val canPrev = episodeNav && episodeIdx > 0
                        val canNext = episodeNav && episodeIdx < episodeChoices.lastIndex
                        PlayerRoundButton(
                            icon = Icons.Filled.SkipPrevious,
                            contentDescription = "Предыдущая серия",
                            size = 56.dp,
                            enabled = canPrev,
                            onClick = { if (canPrev) switchEpisode(episodeChoices[episodeIdx - 1]) },
                        )
                        PlayerRoundButton(
                            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (paused) "Играть" else "Пауза",
                            size = 64.dp,
                            enabled = true,
                            onClick = ::togglePause,
                        )
                        PlayerRoundButton(
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "Следующая серия",
                            size = 56.dp,
                            enabled = canNext,
                            onClick = { if (canNext) switchEpisode(episodeChoices[episodeIdx + 1]) },
                        )
                    }
                }

                // Низ: ряд кнопок над полосой прогресса с таймерами (SeekbarWithTimers).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PlayerIconButton(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            if (paused) "Играть" else "Пауза",
                            onClick = ::togglePause,
                        )
                        Box {
                            PlayerIconButton(
                                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                "Звук",
                                onClick = { volumePopupOpen = !volumePopupOpen },
                            )
                            if (volumePopupOpen) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF1C1C22).copy(alpha = 0.95f),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 52.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        androidx.compose.material3.Slider(
                                            value = volume / 130f,
                                            onValueChange = {
                                                volume = (it * 130).toInt()
                                                if (muted) {
                                                    muted = false
                                                    scope.launch(Dispatchers.IO) { player?.setMuted(false) }
                                                }
                                                val v = volume
                                                scope.launch(Dispatchers.IO) { player?.setVolume(v) }
                                            },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.width(160.dp),
                                        )
                                        Text("$volume%", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                        PlayerIconButton(
                            if (muted) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            if (muted) "Включить звук" else "Без звука",
                            onClick = ::toggleMute,
                        )
                        Spacer(Modifier.weight(1f))
                        if (qualities.keys.size > 1) {
                            PlayerDropdownPill(
                                prefix = null,
                                options = qualities.keys.map { DropdownOption(it, it) },
                                selectedId = currentQuality,
                                onOpenChange = { anyMenuOpen = it },
                                onSelect = { id -> switchQuality(id) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = formatTime(if (isSeeking) seekPreviewPosition else position),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        PlayerSeekbar(
                            position = (if (isSeeking) seekPreviewPosition else position).toFloat(),
                            duration = duration.toFloat().coerceAtLeast(0.1f),
                            onSeekStart = {
                                isSeeking = true
                                if (!paused) scope.launch(Dispatchers.IO) { player?.setPaused(true) }
                            },
                            onSeek = { target ->
                                seekPreviewPosition = target.toDouble()
                                lastInteractionMs = System.currentTimeMillis()
                            },
                            onSeekEnd = { target ->
                                scope.launch(Dispatchers.IO) {
                                    player?.seekTo(target.toDouble())
                                    if (!paused) player?.setPaused(false)
                                }
                                position = target.toDouble()
                                isSeeking = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "-${formatTime((duration - if (isSeeking) seekPreviewPosition else position).coerceAtLeast(0.0))}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

/** Целевая позиция во время перетаскивания полосы (отображается вместо текущей). */
private var seekPreviewPosition by mutableStateOf(0.0)

/** Круглая кнопка контролов mpvEx: Surface CircleShape surfaceContainer 0.55, иконка 20dp. */
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(45.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.padding(12.dp).size(20.dp))
    }
}

/** Центральная круглая кнопка (play/pause/prev/next) — mpvEx размеры 56/64dp. */
@Composable
private fun PlayerRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
            modifier = Modifier.fillMaxSize().padding(size / 4f),
        )
    }
}

/** Пилюля названия в стиле mpvEx: monospace, полупрозрачный фон. */
@Composable
private fun PlayerTitlePill(title: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.height(45.dp).widthIn(max = 320.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
    }
}

/** Dropdown-пилюля mpvEx (серии/озвучки/качество/скорость): полупрозрачная, monospace. */
@Composable
private fun PlayerDropdownPill(
    prefix: String?,
    options: List<DropdownOption>,
    selectedId: String?,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onOpenChange(open) }
    DisposableEffect(Unit) { onDispose { onOpenChange(false) } }
    val selected = options.firstOrNull { it.id == selectedId }
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            contentColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .height(45.dp)
                .clip(RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = { open = !open },
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                if (prefix != null) {
                    Text(prefix, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1)
                }
                Text(
                    text = selected?.label ?: options.first().label,
                    color = if (selected == null) Color.White.copy(alpha = 0.85f) else Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            color = if (option.id == selectedId) MaterialTheme.colorScheme.primary else Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}

/** Полоса прогресса mpvEx: тонкий трек, круглая ручка, драг с паузой и возвратом. */
@Composable
private fun PlayerSeekbar(
    position: Float,
    duration: Float,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var dragTarget by remember { mutableStateOf<Float?>(null) }
    var trackWidthPx by remember { mutableStateOf(0f) }
    val progress = ((dragTarget ?: position) / duration).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(26.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .pointerInput(duration) {
                detectTapGestures(
                    onTap = { offset ->
                        val ratio = (offset.x / trackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        onSeekStart()
                        onSeek(ratio * duration)
                        onSeekEnd(ratio * duration)
                    }
                )
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onSeekStart()
                        dragTarget = (offset.x / trackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f) * duration
                    },
                    onDragEnd = {
                        dragTarget?.let(onSeekEnd)
                        dragTarget = null
                    },
                    onDragCancel = { dragTarget = null },
                ) { change, _ ->
                    val ratio = (change.position.x / trackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    dragTarget = ratio * duration
                    onSeek(ratio * duration)
                    change.consume()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(cs.primary),
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((progress * trackWidthPx).roundToInt() - 7, 0) }
                .size(13.dp)
                .clip(CircleShape)
                .background(cs.primary),
        )
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0.0 || seconds.isNaN()) return "0:00"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}
