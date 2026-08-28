package hd.kinoshka.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hd.kinoshka.app.data.model.*
import hd.kinoshka.app.data.download.DownloadBridges
import hd.kinoshka.app.data.download.DownloadPhase
import hd.kinoshka.app.data.download.DownloadTaskState
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.animeItemKey
import hd.kinoshka.app.data.download.offlineKey
import hd.kinoshka.app.data.download.toAnimeMediaStream
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.ui.components.KinoLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull


private enum class FilterMode {
    VOICE,
    SUBTITLES
}

/** Load lifecycle of one media source on the progressive selection page. */
internal sealed interface SourceLoadState {
    data object Loading : SourceLoadState
    data class Ready(val translations: List<FlatTranslation>) : SourceLoadState
    data class Failed(val message: String) : SourceLoadState
}

/** Последний запуск тайтла из anime_playback_prefs: источник, озвучка, серия. */
private data class LastPlaybackPref(
    val source: AnimeSourceType,
    val translationId: String,
    val translationTitle: String?,
    val episodeNumber: Int
)

/** Hard cap for one source's prefetch: OkHttp timeouts bound each request, the cascade must stay bounded overall. */
private const val SOURCE_LOAD_TIMEOUT_MS = 25_000L

/** True if the episode carries a real (non-synthetic) title, e.g. from AniLiberty. */
private fun hasRealTitle(ep: AnimeEpisode): Boolean {
    val t = ep.title ?: return false
    if (t.isBlank() || t.equals("null", ignoreCase = true)) return false
    // Kodik synthesizes "Серия N" / "Сезон X, Серия N" — not real titles.
    if (t == "Серия ${ep.number}") return false
    if (t.startsWith("Сезон ") && t.endsWith("Серия ${ep.number}")) return false
    return true
}

/** Russian pluralization for "озвучка". */
private fun pluralDubs(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "озвучка"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "озвучки"
    else -> "озвучек"
}

/**
 * Best advertised quality of a dub for the picker badges: the pre-selected episode's own hint
 * when one was picked, otherwise the dub-wide maximum.
 */
private fun FlatTranslation.qualityForEpisode(selected: AnimeEpisode?): String? =
    selected?.let { sel -> episodes.firstOrNull { it.number == sel.number }?.maxQuality }
        ?: episodes.maxByOrNull { qualityRank(it.maxQuality) }?.maxQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimePlaybackSelectionScreen(
    shikimoriId: Int,
    animeTitle: String,
    playbackSequence: PlaybackSequenceOption,
    // Raw Kinopoisk id of the title (when opened outside the Shikimori section). Used as the
    // library profile key when shikimoriId is 0, keeping episode progress synced for such titles.
    kinopoiskId: Int = 0,
    onDismissRequest: () -> Unit,
    onStreamSelected: (
        stream: AnimeMediaStream,
        episodeNumber: Int,
        episodeTitle: String,
        source: AnimeSourceType,
        translationTitle: String,
        episodes: List<AnimeEpisode>,
        translations: List<FlatTranslation>,
        currentTranslationId: String
    ) -> Unit,
    /**
     * Shown when nothing is found. Lets the caller offer an alternative playback path
     * (e.g. open the WebView player for films that Kodik does not index).
     */
    onWebFallback: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val currentStep = remember(currentStepIndex, playbackSequence) {
        playbackSequence.steps.getOrNull(currentStepIndex) ?: SelectionStep.SOURCE
    }

    var selectedSourceType by remember { mutableStateOf<AnimeSourceType?>(null) }
    var filterSourceType by remember { mutableStateOf<AnimeSourceType?>(null) }
    var selectedTranslation by remember { mutableStateOf<FlatTranslation?>(null) }
    var selectedEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }

    var isResolvingStream by remember { mutableStateOf(false) }

    // Progressive load (same pattern as the 18+ source page): the page renders immediately and
    // every source fills its own section as soon as it answers — no full-screen blocking spinner
    // waiting for the slowest of three providers.
    val sourceStatesFlow = remember(shikimoriId) {
        MutableStateFlow<Map<AnimeSourceType, SourceLoadState>>(emptyMap())
    }
    val sourceStates by sourceStatesFlow.collectAsState()

    val allTranslations = remember(sourceStates) {
        sourceStates.values.filterIsInstance<SourceLoadState.Ready>().flatMap { it.translations }
    }

    // Офлайн-озвучки: скачанные серии видны в пикере всегда, даже когда сеть недоступна.
    // Дубликаты по (source, translationId) прячутся за сетевой строкой — local-first резолв
    // всё равно играет локальный файл.
    val itemKey = animeItemKey(shikimoriId, kinopoiskId)
    val library by EpisodeDownloadManager.library.collectAsState()
    val downloadTasks by EpisodeDownloadManager.tasks.collectAsState()
    val offlineTranslations = remember(library, allTranslations, itemKey) {
        EpisodeDownloadManager.offlineTranslations(itemKey, animeTitle).filter { off ->
            allTranslations.none { it.source == off.source && it.translationId == off.translationId }
        }
    }
    val effectiveTranslations = remember(allTranslations, offlineTranslations) {
        allTranslations + offlineTranslations
    }

    // Скачивание из пикера: кнопка на озвучке качает все её серии, кнопка на серии — одну.
    fun downloadedCountFor(tr: FlatTranslation): Int = tr.episodes.count { ep ->
        EpisodeDownloadManager.findLibraryEntry(offlineKey(itemKey, tr.source.name, tr.translationId, ep.number)) != null
    }
    fun activeCountFor(tr: FlatTranslation): Int = downloadTasks.values.count {
        it.itemKey == itemKey && it.translationId == tr.translationId && it.phase != DownloadPhase.FAILED
    }
    fun downloadTranslationAll(tr: FlatTranslation) {
        EpisodeDownloadManager.enqueueAll(
            DownloadBridges.animeRequests(shikimoriId, kinopoiskId, animeTitle, tr)
        )
    }
    fun downloadSingleEpisode(episode: AnimeEpisode, tr: FlatTranslation) {
        EpisodeDownloadManager.enqueue(
            EpisodeDownloadManager.EpisodeDownloadRequest(
                itemKey = itemKey,
                title = animeTitle,
                source = tr.source.name,
                translationId = tr.translationId,
                translationTitle = tr.title,
                episodeNumber = episode.number,
                episodeLabel = episode.title?.takeIf { it.isNotBlank() } ?: "Серия ${episode.number}",
                resolve = {
                    AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, tr.source, tr.translationId, episode.number)
                        ?.let { hd.kinoshka.app.data.download.MediaDownloader.MediaSource(it.url, it.headers) }
                }
            )
        )
    }

    fun startSource(source: AnimeSourceType) {
        if (sourceStatesFlow.value[source] is SourceLoadState.Loading) return
        sourceStatesFlow.update { it + (source to SourceLoadState.Loading) }
        scope.launch {
            val deferred = scope.async(Dispatchers.IO) {
                AnimeStreamResolver.fetchSourceMedia(shikimoriId, animeTitle, source)
                    .filter { it.episodes.isNotEmpty() }
            }
            // Timeout must not cancel the fetch itself: resolver's runCatching blocks swallow
            // TimeoutCancellationException. The loser keeps running and a retry hits its cache.
            val result = withTimeoutOrNull(SOURCE_LOAD_TIMEOUT_MS) { deferred.await() }
            val newState = when {
                result == null -> SourceLoadState.Failed("Превышено время ожидания")
                result.isEmpty() -> SourceLoadState.Failed("Ничего не найдено")
                else -> SourceLoadState.Ready(result)
            }
            sourceStatesFlow.update { current ->
                if (current[source] is SourceLoadState.Ready) current
                else current + (source to newState)
            }
        }
    }

    /** Launches sources that are neither loading nor loaded — initial open and «Повторить». */
    fun startPendingSources() {
        ANIME_PICKER_SOURCES.forEach { src ->
            val state = sourceStatesFlow.value[src]
            if (state !is SourceLoadState.Loading && state !is SourceLoadState.Ready) {
                startSource(src)
            }
        }
    }

    LaunchedEffect(shikimoriId) {
        startPendingSources()
    }

    // Global preference memory: which sources/dubs the user launches most recently and often.
    // Читается в IO — первый доступ к SharedPreferences бьёт в диск прямо во время композиции.
    val playbackUsage by androidx.compose.runtime.produceState(
        initialValue = hd.kinoshka.app.data.local.PlaybackUsageStats(),
        key1 = shikimoriId
    ) {
        withContext(Dispatchers.IO) {
            value = hd.kinoshka.app.data.local.UserStateStore(context).getPlaybackUsage()
        }
    }

    // Последняя просмотренная озвучка/источник/серия этого тайтла — для карточки «Продолжить с…».
    // Тот же per-title ключ, что пишет resolveAndPlay.
    var lastPlayback by remember { mutableStateOf<LastPlaybackPref?>(null) }
    val playbackPrefsKey = if (shikimoriId > 0) shikimoriId else kinopoiskId
    LaunchedEffect(playbackPrefsKey) {
        lastPlayback = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("anime_playback_prefs", Context.MODE_PRIVATE)
            val sourceName = prefs.getString("last_source_$playbackPrefsKey", null) ?: return@withContext null
            val translationId = prefs.getString("last_translation_id_$playbackPrefsKey", null) ?: return@withContext null
            val source = runCatching { AnimeSourceType.valueOf(sourceName) }.getOrNull() ?: return@withContext null
            LastPlaybackPref(
                source = source,
                translationId = translationId,
                translationTitle = prefs.getString("last_translation_title_$playbackPrefsKey", null),
                episodeNumber = prefs.getInt("last_episode_num_$playbackPrefsKey", 1)
            )
        }
    }

    // Сопоставляем запись с реально загруженными озвучками: точный id, затем совпадение по имени;
    // серия — сохранённая, ближайшая ниже неё или первая доступная.
    val resumeSuggestion = remember(lastPlayback, effectiveTranslations) {
        val last = lastPlayback ?: return@remember null
        val translation = (
            effectiveTranslations.firstOrNull { it.source == last.source && it.translationId == last.translationId }
                ?: effectiveTranslations.firstOrNull { it.source == last.source && it.title == last.translationTitle }
            )?.takeIf { it.episodes.isNotEmpty() } ?: return@remember null
        val episode = translation.episodes.firstOrNull { it.number == last.episodeNumber }
            ?: translation.episodes.lastOrNull { it.number < last.episodeNumber }
            ?: translation.episodes.first()
        translation to episode
    }

    // «Продолжить с…» живёт только на первом шаге последовательности — при «Сначала серии» это
    // список серий, при «Сначала озвучки» — страница озвучек.
    val activeResumeSuggestion = if (currentStepIndex == 0) resumeSuggestion else null

    // Full error state only when every source has settled and none produced usable content.
    val allSourcesSettled = ANIME_PICKER_SOURCES.all { sourceStates[it] is SourceLoadState.Ready || sourceStates[it] is SourceLoadState.Failed }
    val isLoadingSources = sourceStates.values.any { it is SourceLoadState.Loading }
    val errorMessage = if (allSourcesSettled && !isLoadingSources && effectiveTranslations.isEmpty()) {
        "Не удалось найти видео для этого аниме.\nДля 18+ тайтлов используйте веб-плеер."
    } else {
        null
    }

    // Pre-compute derived data once when translations load
    val episodeTranslationCountMap = remember(effectiveTranslations) {
        buildMap {
            for (tr in effectiveTranslations) {
                for (ep in tr.episodes) {
                    merge(ep.number, 1, Int::plus)
                }
            }
        }
    }

    val mergedEpisodes = remember(effectiveTranslations) {
        // Prefer a real episode title over the synthetic "Серия N" that Kodik emits. Kodik is
        // awaited/added before AniLiberty, so plain distinctBy{number} kept Kodik's synthetic
        // title and dropped AniLiberty's real name — which then got suppressed by the UI guard,
        // so the merged view showed no titles at all. Keep the entry with a real title per number.
        effectiveTranslations
            .flatMap { it.episodes }
            .groupBy { it.number }
            .map { (_, eps) -> eps.firstOrNull { hasRealTitle(it) } ?: eps.first() }
            .sortedBy { it.number }
    }

    val mergedEpisodesBySource = remember(effectiveTranslations) {
        effectiveTranslations
            .groupBy { it.source }
            .mapValues { (_, translations) ->
                translations
                    .flatMap { it.episodes }
                    .groupBy { it.number }
                    .map { (_, eps) -> eps.firstOrNull { hasRealTitle(it) } ?: eps.first() }
                    .sortedBy { it.number }
            }
    }

    // High-water-mark watched-episode count (per-anime), used to mark watched episodes in the
    // picker. Sourced from UserStateStore profiles (written by PlayerActivity on watched threshold).
    var watchedEpisodes by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(shikimoriId) {
        watchedEpisodes = withContext(Dispatchers.IO) {
            // Titles opened from regular search carry no shikimori mapping (shikimoriId == 0);
            // their profiles live under the raw Kinopoisk id passed by the caller.
            val profileKey = if (shikimoriId > 0) {
                shikimoriId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET
            } else {
                kinopoiskId
            }
            hd.kinoshka.app.data.local.UserStateStore(context)
                .getProfile(profileKey)
                ?.watchedEpisodes
        }
    }

    // Helper to resolve HLS stream and launch player
    fun resolveAndPlay(episode: AnimeEpisode, translation: FlatTranslation, source: AnimeSourceType) {
        isResolvingStream = true
        scope.launch {
            try {
                // Local-first: скачанная серия играется из офлайн-библиотеки без сети и резолва.
                val stream = EpisodeDownloadManager
                    .findLocal(shikimoriId, kinopoiskId, source.name, translation.translationId, episode.number)
                    ?.toAnimeMediaStream()
                    ?: AnimeStreamResolver.resolveStream(
                        shikimoriId,
                        animeTitle,
                        source,
                        translation.translationId,
                        episode.number
                    )
                isResolvingStream = false
                if (stream != null) {
                    // Save last watched position. Keyed per-title: shikimori id when known,
                    // otherwise the raw Kinopoisk id — a shared key would recommend one
                    // title's dub on a completely different show.
                    val prefsKey = if (shikimoriId > 0) shikimoriId else kinopoiskId
                    context.getSharedPreferences("anime_playback_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_source_$prefsKey", source.name)
                        .putString("last_translation_id_$prefsKey", translation.translationId)
                        .putString("last_translation_title_$prefsKey", translation.title)
                        .putInt("last_episode_num_$prefsKey", episode.number)
                        .apply()

                    // Feed the global preference memory: this source/dub rises to the top of
                    // future lists (sheet + player dropdown).
                    val usageStore = hd.kinoshka.app.data.local.UserStateStore(context)
                    usageStore.recordSourceUsage(source)
                    usageStore.recordDubUsage(translation.title)

                    onStreamSelected(
                        stream,
                        episode.number,
                        // Guard against JSON-null leaking as the literal string "null".
                        episode.title?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                            ?: "Серия ${episode.number}",
                        source,
                        translation.title,
                        translation.episodes,
                        effectiveTranslations,
                        translation.translationId
                    )
                    onDismissRequest()
                } else {
                    // Transient resolve failure: the page itself is fine, so surface a toast
                    // instead of the derived all-sources-failed error state.
                    android.widget.Toast.makeText(
                        context,
                        "Не удалось получить ссылку на видео для серии ${episode.number}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                isResolvingStream = false
                android.widget.Toast.makeText(
                    context,
                    "Ошибка при запуске плеера: ${e.localizedMessage}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun handleBack() {
        if (currentStepIndex > 0) {
            currentStepIndex--
            // Reset selections when going back
            val prevStep = playbackSequence.steps[currentStepIndex]
            when (prevStep) {
                SelectionStep.SOURCE -> {
                    selectedSourceType = null
                    selectedTranslation = null
                }
                SelectionStep.TRANSLATION -> {
                    selectedTranslation = null
                }
                SelectionStep.EPISODE -> {
                    selectedEpisode = null
                }
            }
        } else {
            onDismissRequest()
        }
    }

    BackHandler {
        handleBack()
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        // Рисуем окно под системными барами — без этого по краям виден фон Details-экрана.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Top Custom App Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = animeTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitleText = when (currentStep) {
                            SelectionStep.SOURCE -> "Выбор озвучки и источника"
                            SelectionStep.TRANSLATION -> "Выбор озвучки ${selectedSourceType?.let { "• ${it.displayName}" } ?: ""}"
                            SelectionStep.EPISODE -> if (selectedTranslation != null) "${selectedTranslation?.title} • ${selectedSourceType?.displayName}" else "Выбор серии"
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // Slim progress strip: the page is usable while sources are still resolving.
                androidx.compose.animation.AnimatedVisibility(visible = isLoadingSources) {
                    val settled = ANIME_PICKER_SOURCES.count { sourceStates[it] is SourceLoadState.Ready || sourceStates[it] is SourceLoadState.Failed }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Загрузка источников… $settled/${ANIME_PICKER_SOURCES.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Body content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    when {
                        isResolvingStream -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                KinoLoadingIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Получение ссылки на видеопоток...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        errorMessage != null -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = {
                                        currentStepIndex = 0
                                        selectedSourceType = null
                                        selectedTranslation = null
                                        selectedEpisode = null
                                        startPendingSources()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Повторить поиск")
                                }
                                if (onWebFallback != null) {
                                    OutlinedButton(onClick = {
                                        onWebFallback.invoke()
                                        onDismissRequest()
                                    }) {
                                        Text("Открыть в веб-плеере")
                                    }
                                }
                            }
                        }
                        else -> {
                            AnimatedContent(
                                targetState = currentStep,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "watchStateAnimation"
                            ) { step ->
                                when (step) {
                                    SelectionStep.SOURCE -> {
                                        SelectTranslationStep(
                                            selectedSource = filterSourceType,
                                            onSourceSelected = { filterSourceType = it },
                                            selectedEpisode = selectedEpisode,
                                            allTranslations = effectiveTranslations,
                                            sourceStates = sourceStates,
                                            onRetrySource = ::startSource,
                                            resumeSuggestion = activeResumeSuggestion,
                                            onResumeSelected = { tr, ep -> resolveAndPlay(ep, tr, tr.source) },
                                            onTranslationSelected = { tr ->
                                                selectedTranslation = tr
                                                selectedSourceType = tr.source

                                                // Skip redundant TRANSLATION step if it follows
                                                var nextIdx = currentStepIndex + 1
                                                while (nextIdx < playbackSequence.steps.size &&
                                                    playbackSequence.steps[nextIdx] == SelectionStep.TRANSLATION) {
                                                    nextIdx++
                                                }

                                                if (nextIdx < playbackSequence.steps.size) {
                                                    currentStepIndex = nextIdx
                                                } else {
                                                    val ep = selectedEpisode ?: tr.episodes.firstOrNull()
                                                    if (ep != null) {
                                                        resolveAndPlay(ep, tr, tr.source)
                                                    }
                                                }
                                            },
                                            playbackUsage = playbackUsage,
                                            downloadedCountFor = ::downloadedCountFor,
                                            activeCountFor = ::activeCountFor,
                                            onDownloadTranslation = ::downloadTranslationAll
                                        )
                                    }
                                    SelectionStep.TRANSLATION -> {
                                        SelectTranslationStep(
                                            selectedSource = selectedSourceType ?: filterSourceType,
                                            onSourceSelected = { selectedSourceType = it },
                                            selectedEpisode = selectedEpisode,
                                            allTranslations = effectiveTranslations,
                                            sourceStates = sourceStates,
                                            onRetrySource = ::startSource,
                                            onTranslationSelected = { tr ->
                                                selectedTranslation = tr
                                                selectedSourceType = tr.source

                                                // Skip redundant steps if we already have both source and translation
                                                var nextIdx = currentStepIndex + 1
                                                while (nextIdx < playbackSequence.steps.size &&
                                                    (playbackSequence.steps[nextIdx] == SelectionStep.SOURCE ||
                                                     playbackSequence.steps[nextIdx] == SelectionStep.TRANSLATION)) {
                                                    nextIdx++
                                                }

                                                if (nextIdx < playbackSequence.steps.size) {
                                                    currentStepIndex = nextIdx
                                                } else {
                                                    val ep = selectedEpisode ?: tr.episodes.firstOrNull()
                                                    if (ep != null) {
                                                        resolveAndPlay(ep, tr, tr.source)
                                                    }
                                                }
                                            },
                                            playbackUsage = playbackUsage,
                                            downloadedCountFor = ::downloadedCountFor,
                                            activeCountFor = ::activeCountFor,
                                            onDownloadTranslation = ::downloadTranslationAll
                                        )
                                    }
                                    SelectionStep.EPISODE -> {
                                        val episodesSource = when {
                                            selectedTranslation != null -> selectedTranslation!!.episodes
                                            selectedSourceType != null -> mergedEpisodesBySource[selectedSourceType!!] ?: mergedEpisodes
                                            else -> mergedEpisodes
                                        }

                                        SelectEpisodeStep(
                                            episodes = episodesSource,
                                            episodeTranslationCountMap = episodeTranslationCountMap,
                                            showDubCount = selectedTranslation == null,
                                            watchedEpisodes = watchedEpisodes,
                                            resumeSuggestion = activeResumeSuggestion,
                                            onResumeSelected = { tr, ep -> resolveAndPlay(ep, tr, tr.source) },
                                            onEpisodeSelected = { ep ->
                                                selectedEpisode = ep
                                                if (currentStepIndex < playbackSequence.steps.lastIndex) {
                                                    currentStepIndex++
                                                } else {
                                                    val tr = selectedTranslation ?: return@SelectEpisodeStep
                                                    resolveAndPlay(ep, tr, selectedSourceType ?: tr.source)
                                                }
                                            },
                                            // Кнопка «скачать» на серии видна, когда озвучка уже выбрана —
                                            // иначе непонятно, какую озвучку качать.
                                            onDownloadEpisode = selectedTranslation?.let { tr ->
                                                { ep: AnimeEpisode -> downloadSingleEpisode(ep, tr) }
                                            },
                                            isEpisodeDownloaded = { num ->
                                                selectedTranslation?.let { tr ->
                                                    EpisodeDownloadManager.findLibraryEntry(
                                                        offlineKey(itemKey, tr.source.name, tr.translationId, num)
                                                    ) != null
                                                } ?: false
                                            },
                                            episodeTaskFor = { num ->
                                                selectedTranslation?.let { tr ->
                                                    downloadTasks[offlineKey(itemKey, tr.source.name, tr.translationId, num)]
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Recency-then-frequency rank of a source in the global usage memory. */
private fun sourceUsageRank(
    usage: hd.kinoshka.app.data.local.PlaybackUsageStats,
    source: AnimeSourceType
): Pair<Long, Int> {
    val entry = usage.sources[source.name]
    return (entry?.lastUsedAt ?: 0L) to (entry?.count ?: 0)
}

/** Recency-then-frequency rank of a dub team in the global usage memory. */
private fun dubUsageRank(usage: hd.kinoshka.app.data.local.PlaybackUsageStats, title: String): Pair<Long, Int> {
    val entry = usage.dubs[title.trim().lowercase()]
    return (entry?.lastUsedAt ?: 0L) to (entry?.count ?: 0)
}

@Composable
private fun SelectTranslationStep(
    selectedSource: AnimeSourceType?,
    onSourceSelected: (AnimeSourceType?) -> Unit,
    selectedEpisode: AnimeEpisode?,
    allTranslations: List<FlatTranslation>,
    sourceStates: Map<AnimeSourceType, SourceLoadState> = emptyMap(),
    onRetrySource: (AnimeSourceType) -> Unit = {},
    // «Продолжить с…»: последняя озвучка/серия этого тайтла; показывается только на первом шаге.
    resumeSuggestion: Pair<FlatTranslation, AnimeEpisode>? = null,
    onResumeSelected: ((FlatTranslation, AnimeEpisode) -> Unit)? = null,
    onTranslationSelected: (FlatTranslation) -> Unit,
    playbackUsage: hd.kinoshka.app.data.local.PlaybackUsageStats = hd.kinoshka.app.data.local.PlaybackUsageStats(),
    // Офлайн-скачивание: кнопка на озвучке качает все её серии; счётчики питают бейджи.
    downloadedCountFor: (FlatTranslation) -> Int = { 0 },
    activeCountFor: (FlatTranslation) -> Int = { 0 },
    onDownloadTranslation: ((FlatTranslation) -> Unit)? = null
) {
    val sourceTranslations = remember(allTranslations, selectedSource, selectedEpisode) {
        var list = allTranslations
        if (selectedSource != null) {
            list = list.filter { it.source == selectedSource }
        }
        if (selectedEpisode != null) {
            list = list.filter { tr -> tr.episodes.any { it.number == selectedEpisode.number } }
        }
        list
    }

    var filterMode by remember { mutableStateOf(FilterMode.VOICE) }

    val filteredList = remember(sourceTranslations, filterMode) {
        when (filterMode) {
            FilterMode.VOICE -> sourceTranslations.filter { it.type != "sub" && it.type != "subtitles" }
            FilterMode.SUBTITLES -> sourceTranslations.filter { it.type == "sub" || it.type == "subtitles" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // Карточка продолжения просмотра — самый верх первого шага выбора.
        if (resumeSuggestion != null && onResumeSelected != null) {
            val (resumeTranslation, resumeEpisode) = resumeSuggestion
            item(key = "resume-suggestion") {
                ResumeWatchingCard(
                    translation = resumeTranslation,
                    episodeNumber = resumeEpisode.number,
                    onClick = { onResumeSelected(resumeTranslation, resumeEpisode) }
                )
            }
        }
        item {
            // Translation tabs (Voice / Subtitles)
            val filterOptions = listOf(FilterMode.VOICE, FilterMode.SUBTITLES)
            TabRow(
                selectedTabIndex = filterOptions.indexOf(filterMode),
                containerColor = Color.Transparent,
                indicator = { tabPositions ->
                    val idx = filterOptions.indexOf(filterMode)
                    if (idx < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[idx]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                divider = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                filterOptions.forEach { mode ->
                    val isSelected = filterMode == mode
                    Tab(
                        selected = isSelected,
                        onClick = { filterMode = mode },
                        text = {
                            Text(
                                text = when (mode) {
                                    FilterMode.VOICE -> "Озвучка"
                                    FilterMode.SUBTITLES -> "Субтитры"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            // Source chips (previous style). "Все" stays first; used sources rise by recency.
            val sources = remember(allTranslations, playbackUsage) {
                val available = ANIME_PICKER_SOURCES.filter { src ->
                    allTranslations.any { it.source == src }
                }
                val ranked = available.sortedWith(
                    compareByDescending<AnimeSourceType> { sourceUsageRank(playbackUsage, it).first }
                        .thenByDescending { sourceUsageRank(playbackUsage, it).second }
                )
                listOf<AnimeSourceType?>(null) + ranked
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEach { src ->
                    FilterChip(
                        selected = selectedSource == src,
                        onClick = { onSourceSelected(src) },
                        label = { Text(src?.displayName ?: "Все") }
                    )
                }
            }
        }

        // Sources still resolving or failed — shown as compact rows above the results so the
        // page explains itself instead of silently hiding providers (18+ source-page pattern).
        val pendingSources = ANIME_PICKER_SOURCES.mapNotNull { src ->
            when (val state = sourceStates[src]) {
                is SourceLoadState.Loading -> src to null
                is SourceLoadState.Failed -> src to state.message
                else -> null
            }
        }

        if (filteredList.isEmpty()) {
            if (pendingSources.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Нет доступных вариантов",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Group by source, then sort each group by episode count (desc) then title so the
            // most complete dubs surface first — makes every available dub/source visible rather
            // than lost in fetch order. Groups with used sources come first; inside a group,
            // dubs the user actually launches rank by recency before the default ordering.
            val groupOrder = filteredList.map { it.source }.distinct().sortedWith(
                compareByDescending<AnimeSourceType> { sourceUsageRank(playbackUsage, it).first }
                    .thenByDescending { sourceUsageRank(playbackUsage, it).second }
            )
            val grouped = groupOrder.associateWith { source ->
                filteredList.filter { it.source == source }.sortedWith(
                    compareByDescending<FlatTranslation> { dubUsageRank(playbackUsage, it.title).first }
                        .thenByDescending { dubUsageRank(playbackUsage, it.title).second }
                        .thenByDescending { it.episodes.size }
                        .thenBy { it.title }
                )
            }
            grouped.forEach { (source, translations) ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp, start = 20.dp, end = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = source.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${translations.size} ${pluralDubs(translations.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(
                    count = translations.size,
                    // Composite key: a translationId alone is not unique (the same dub studio can
                    // appear under several catalogue rows), and Compose hard-crashes on duplicates.
                    key = { index -> "${source.name}:${translations[index].translationId}:$index" }
                ) { index ->
                    val tr = translations[index]
                    val isSub = tr.type == "sub" || tr.type == "subtitles"
                    Surface(
                        onClick = { onTranslationSelected(tr) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSub) MaterialTheme.colorScheme.tertiaryContainer 
                                        else MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isSub) Icons.Default.ClosedCaption else Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = if (isSub) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    // Kodik rows carry the dub name as the title; AniLib teams carry
                                    // their own team names too. Only AniLiberty's release titles need
                                    // the source prefix to not read as a Kodik dub.
                                    text = if (tr.source == AnimeSourceType.KODIK || tr.source == AnimeSourceType.ANILIB) tr.title
                                    else "${tr.source.displayName} · ${tr.title}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val subText = if (isSub) "Субтитры" else "Голосовая озвучка"
                                Text(
                                    text = subText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Preference badge: this dub team is in the user's usage memory.
                            if (dubUsageRank(playbackUsage, tr.title).first > 0L) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "Вы часто смотрите с этой озвучкой",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            // Quality badge only after an episode is picked ("Сначала серии",
                            // second screen): shows that episode's quality under this dub.
                            if (selectedEpisode != null) {
                                val badge = qualityBadgeLabel(tr.qualityForEpisode(selectedEpisode))
                                if (badge != null) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                    ) {
                                        Text(
                                            text = badge,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                            // Кнопка скачивания всех серий озвучки в офлайн-библиотеку.
                            if (onDownloadTranslation != null && tr.episodes.isNotEmpty()) {
                                val downloaded = downloadedCountFor(tr)
                                val active = activeCountFor(tr)
                                val allDownloaded = downloaded >= tr.episodes.size
                                when {
                                    allDownloaded -> {
                                        Icon(
                                            imageVector = Icons.Default.DownloadDone,
                                            contentDescription = "Вся озвучка скачана",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    active > 0 -> {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    else -> {
                                        IconButton(
                                            onClick = { onDownloadTranslation(tr) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Скачать все серии озвучки",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                }
                            }
                            if (tr.episodes.isNotEmpty()) {
                                Text(
                                    text = "${tr.episodes.size} эп.",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Still-loading / failed sources sit BELOW the ready content: loaded dubs first,
        // progress at the bottom of the list.
        if (pendingSources.isNotEmpty()) {
            items(
                count = pendingSources.size,
                key = { index -> "pending:${pendingSources[index].first.name}" }
            ) { index ->
                val (source, failure) = pendingSources[index]
                SourceStatusRow(
                    sourceName = source.displayName,
                    loading = failure == null,
                    message = failure,
                    onRetry = { onRetrySource(source) }
                )
            }
        }
    }
}

/** Compact per-source progress/failure row shown while the page fills in progressively. */
@Composable
private fun SourceStatusRow(
    sourceName: String,
    loading: Boolean,
    message: String?,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (loading) "Поиск озвучек…" else (message ?: "Ошибка"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!loading) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Повторить", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Карточка «Продолжить с серии N»: последняя озвучка/источник тайтла, клик сразу запускает playback. */
@Composable
private fun ResumeWatchingCard(
    translation: FlatTranslation,
    episodeNumber: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "История просмотра",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Продолжить с серии $episodeNumber",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "${translation.source.displayName} · ${translation.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SelectEpisodeStep(
    episodes: List<AnimeEpisode>,
    episodeTranslationCountMap: Map<Int, Int> = emptyMap(),
    // Dub-count badges make sense only on the merged episode list (before a dub is picked);
    // once a dub is chosen the tile shows its quality instead.
    showDubCount: Boolean = true,
    watchedEpisodes: Int? = null,
    // «Продолжить с…»: последняя озвучка/серия; карточка показывается только на первом шаге.
    resumeSuggestion: Pair<FlatTranslation, AnimeEpisode>? = null,
    onResumeSelected: ((FlatTranslation, AnimeEpisode) -> Unit)? = null,
    onEpisodeSelected: (AnimeEpisode) -> Unit,
    // Офлайн-скачивание: кнопка на серии видна, когда озвучка выбрана (иначе неизвестно,
    // какую озвучку качать — скачивание «всё» живёт на строках озвучек).
    onDownloadEpisode: ((AnimeEpisode) -> Unit)? = null,
    isEpisodeDownloaded: (Int) -> Boolean = { false },
    episodeTaskFor: (Int) -> DownloadTaskState? = { null }
) {
    var isSortAscending by remember { mutableStateOf(true) }

    val sortedEpisodes = remember(episodes, isSortAscending) {
        if (isSortAscending) episodes.sortedBy { it.number } else episodes.sortedByDescending { it.number }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Карточка продолжения просмотра — самый верх первого шага (при «Сначала серии»).
        if (resumeSuggestion != null && onResumeSelected != null) {
            val (resumeTranslation, resumeEpisode) = resumeSuggestion
            item(key = "resume-suggestion") {
                ResumeWatchingCard(
                    translation = resumeTranslation,
                    episodeNumber = resumeEpisode.number,
                    onClick = { onResumeSelected(resumeTranslation, resumeEpisode) }
                )
            }
        }
        item {
            // Actions row (Sort options)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Всего серий: ${episodes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )

                TextButton(
                    onClick = { isSortAscending = !isSortAscending },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isSortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSortAscending) "По порядку" else "Сначала новые",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        if (sortedEpisodes.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Список серий пуст", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(
                count = sortedEpisodes.size,
                // Episode numbers can repeat once lists are merged across sources/seasons, so the
                // index is folded in to guarantee uniqueness.
                key = { index -> "ep:${sortedEpisodes[index].number}:$index" }
            ) { index ->
                val ep = sortedEpisodes[index]
                Surface(
                    onClick = { onEpisodeSelected(ep) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isWatched = watchedEpisodes != null && ep.number <= watchedEpisodes!! && ep.number < 10000
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isWatched) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isWatched) Icons.Filled.Check else Icons.Filled.PlayArrow,
                                contentDescription = if (isWatched) "Просмотрено" else null,
                                tint = if (isWatched) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Серия ${ep.number}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isWatched) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                                )

                                val trCount = episodeTranslationCountMap[ep.number] ?: 0
                                if (showDubCount && trCount > 1) {
                                    Surface(
                                        modifier = Modifier.padding(start = 8.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                    ) {
                                        Text(
                                            text = "$trCount озв.",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else if (!showDubCount) {
                                    val badge = qualityBadgeLabel(ep.maxQuality)
                                    if (badge != null) {
                                        Surface(
                                            modifier = Modifier.padding(start = 8.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                        ) {
                                            Text(
                                                text = badge,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                if (isWatched) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "просмотрено",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            if (hasRealTitle(ep)) {
                                Text(
                                    text = ep.title!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // Кнопка скачивания серии (когда озвучка выбрана).
                        if (onDownloadEpisode != null) {
                            val task = episodeTaskFor(ep.number)
                            when {
                                isEpisodeDownloaded(ep.number) -> {
                                    Icon(
                                        imageVector = Icons.Default.DownloadDone,
                                        contentDescription = "Скачано",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                task != null && task.phase == DownloadPhase.FAILED -> {
                                    IconButton(
                                        onClick = { EpisodeDownloadManager.retry(task.key) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Повторить скачивание",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                                task != null -> {
                                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { EpisodeDownloadManager.cancel(task.key) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Отменить скачивание",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                else -> {
                                    IconButton(
                                        onClick = { onDownloadEpisode(ep) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Скачать серию",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
