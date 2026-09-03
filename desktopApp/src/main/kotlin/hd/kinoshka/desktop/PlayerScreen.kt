package hd.kinoshka.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.data.local.UserStateStoreBase
import hd.kinoshka.app.data.playback.MovieNativeLauncher
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.player.desktop.MpvPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.event.KeyEvent
import java.awt.Color as AwtColor

private const val DEMO_URL =
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"

/** Порог реального просмотра до коммита в библиотеку/историю — как в Android-плеере. */
private const val MIN_WATCH_SECONDS_FOR_LIBRARY = 300

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
)

private data class LoadCommand(val url: String, val headers: Map<String, String> = emptyMap())

/** Пункт dropdown'а серий: key — AnimeEpisode.number либо MovieEpisodeRef.playerEpisodeKey. */
private data class EpisodeChoice(val key: Int, val label: String)

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
    val scope = rememberCoroutineScope()
    val attachGuard = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

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
        // Читатели (rememberedDubId, ранжирование dropdown'а) сравнивают ключом splitDubTrack,
        // поэтому и пишем свёрнутый ключ — иначе «Original»/субтитровые треки не восстановятся.
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

    // Стартовый поток: прямой (anime/quality-only/трейлер/серия контекста), демо-клип,
    // либо PENDING — полный резолв гонкой Kodik↔ddbb (общий MovieNativeLauncher).
    LaunchedEffect(args.title, args.kinopoiskId, args.sourceType) {
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
                println("MPV: PENDING resolve kp=${args.kinopoiskId}")
                when (val payload = MovieNativeLauncher.resolve(
                    MoviePlaybackRequest(
                        kinopoiskId = args.kinopoiskId,
                        imdbId = null,
                        titles = listOf(args.title),
                        year = null,
                        kind = MovieContentKind.MOVIE,
                    ),
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
            }
            else -> resolveError = "Нет потока для воспроизведения"
        }
    }

    // Привязка mpv: опрашиваем HWND канваса до его появления — тяжеловесный AWT-канвас
    // создаётся асинхронно после первого кадра Compose, поэтому ждём именно HWND.
    LaunchedEffect(Unit) {
        if (!attachGuard.compareAndSet(false, true)) return@LaunchedEffect
        launch(Dispatchers.IO) {
            try {
                var hwnd: Long? = null
                repeat(150) {
                    hwnd = Win32.findChildCanvasHwnd(MAIN_WINDOW_TITLE)
                    if (hwnd != null) return@repeat
                    delay(100)
                }
                val handle = hwnd ?: error("HWND канваса не найден")
                println("MPV: hwnd=$handle")
                player = MpvPlayer.create(handle)
            } catch (t: Throwable) {
                attachGuard.set(false)
                attachError = t.message ?: "не удалось запустить mpv"
            }
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
        onDispose {
            flushPlaybackPosition()
            player?.close()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "← Назад",
                color = Color(0xFF9F9FA8),
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color(0xFF1C1C22), MaterialTheme.shapes.small)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                args.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (translations.size > 1) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(
                            text = "Озвучка: " + (
                                translations.firstOrNull { it.translationId == currentTranslationId }?.title
                                    ?: translations.firstOrNull()?.title
                                    ?: "по умолчанию"
                                ),
                            color = Color(0xFFB9B9C0),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        translations.forEach { translation ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        translation.title,
                                        color = if (translation.translationId == currentTranslationId) {
                                            Color(0xFF8AB4F8)
                                        } else {
                                            Color.White
                                        },
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    switchTranslation(translation)
                                },
                            )
                        }
                    }
                }
            }
            if (episodeChoices.isNotEmpty()) {
                var epMenuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { epMenuOpen = true }) {
                        Text(
                            text = "Серия: " + (
                                episodeChoices.firstOrNull { it.key == currentEpisodeKey }?.label
                                    ?: currentEpisodeKey.toString()
                                ),
                            color = Color(0xFFB9B9C0),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = epMenuOpen, onDismissRequest = { epMenuOpen = false }) {
                        episodeChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        choice.label,
                                        color = if (choice.key == currentEpisodeKey) Color(0xFF8AB4F8) else Color.White,
                                    )
                                },
                                onClick = {
                                    epMenuOpen = false
                                    switchEpisode(choice)
                                },
                            )
                        }
                    }
                }
            }
            if (qualities.size > 1) {
                var qualityMenuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { qualityMenuOpen = true }) {
                        Text(
                            text = "Качество: ${currentQuality ?: "Auto"}",
                            color = Color(0xFFB9B9C0),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = qualityMenuOpen, onDismissRequest = { qualityMenuOpen = false }) {
                        qualities.keys.forEach { name ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        color = if (name == currentQuality) Color(0xFF8AB4F8) else Color.White,
                                    )
                                },
                                onClick = {
                                    qualityMenuOpen = false
                                    switchQuality(name)
                                },
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            SwingPanel(
                background = Color.Black,
                modifier = Modifier.fillMaxSize(),
                factory = {
                    Canvas().apply {
                        background = AwtColor.BLACK
                        // Клавиатура плеера (пробел/стрелки/M) приходит на AWT-канвас: у mpv
                        // input-default-bindings=no, свои биндинги вешаем здесь.
                        isFocusable = true
                        addKeyListener(object : java.awt.event.KeyAdapter() {
                            override fun keyPressed(e: java.awt.event.KeyEvent) {
                                val current = player ?: return
                                when (e.keyCode) {
                                    KeyEvent.VK_SPACE -> scope.launch(Dispatchers.IO) {
                                        paused = current.togglePause()
                                    }
                                    KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> {
                                        val delta =
                                            if (e.keyCode == KeyEvent.VK_LEFT) -10.0 else 10.0
                                        scope.launch(Dispatchers.IO) {
                                            val target = (current.positionSeconds() ?: 0.0) + delta
                                            current.seekTo(target)
                                            position = target
                                        }
                                    }
                                    KeyEvent.VK_UP, KeyEvent.VK_DOWN -> {
                                        volume = (volume +
                                            if (e.keyCode == KeyEvent.VK_UP) 10 else -10)
                                            .coerceIn(0, 130)
                                        if (muted) {
                                            muted = false
                                            scope.launch(Dispatchers.IO) { current.setMuted(false) }
                                        }
                                        val v = volume
                                        scope.launch(Dispatchers.IO) { current.setVolume(v) }
                                    }
                                    KeyEvent.VK_M -> {
                                        val next = !muted
                                        muted = next
                                        scope.launch(Dispatchers.IO) { current.setMuted(next) }
                                    }
                                }
                            }
                        })
                    }
                },
            )
            (attachError ?: resolveError)?.let { message ->
                Text(
                    message,
                    color = Color(0xFFFF7B72),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = {
                scope.launch(Dispatchers.IO) { player?.let { paused = it.togglePause() } }
            }) {
                Text(if (paused) "▶" else "⏸")
            }
            TextButton(onClick = {
                val next = !muted
                muted = next
                scope.launch(Dispatchers.IO) { player?.setMuted(next) }
            }) {
                Text(if (muted) "🔇" else "🔊")
            }
            Slider(
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
                modifier = Modifier.width(110.dp),
            )
            Slider(
                value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(0.1f)),
                onValueChange = { position = it.toDouble() },
                onValueChangeFinished = {
                    scope.launch(Dispatchers.IO) { player?.seekTo(position) }
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(0.1f),
                modifier = Modifier.weight(1f),
            )
            Text(
                "${formatTime(position)} / ${formatTime(duration)}",
                color = Color(0xFF9F9FA8),
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0.0 || seconds.isNaN()) return "0:00"
    val total = seconds.toLong()
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
