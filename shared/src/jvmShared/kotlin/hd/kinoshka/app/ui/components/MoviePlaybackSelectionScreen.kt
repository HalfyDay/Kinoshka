package hd.kinoshka.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.ui.screens.SourceBrandIcon
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.KodikMovieCandidate
import hd.kinoshka.app.data.model.MovieCatalogResult
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.MovieStreamResult
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.data.model.SeasonItem
import hd.kinoshka.app.data.model.canonicalSeriesEpisodes
import hd.kinoshka.app.data.model.qualityBadgeLabel
import hd.kinoshka.app.data.playback.MovieNativeLauncher
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.data.source.CustomSource
import hd.kinoshka.app.data.source.DdbbStreamResolver
import hd.kinoshka.app.data.source.HdrezkaApi
import hd.kinoshka.app.data.source.MovieStreamResolver
import hd.kinoshka.app.data.source.PlaybackSources
import hd.kinoshka.app.ui.platform.rememberKinoPlatformActions
import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Страница выбора для фильмов и сериалов — тот же флоу, что у аниме:
 * сезон → серия → озвучка → источник. У фильмов без структуры серий шагов два:
 * озвучка → источник. Плитки повторяют стиль аниме-пикера (Surface-строки,
 * круглая иконка, бейджи озвучек/качества, пометки просмотренного и скачанного).
 *
 * Рисуется как полноэкранный оверлей внутри DetailsScreen (без Dialog — так же, как
 * страница 18+ источников): back-навигацию отдаёт родитель. Загрузка попрогрессивная:
 * Kodik-каталог и прямые разборы (Turbo/VideoCDN/Collaps/Voidboost/Alloha/Veoveo)
 * резолвятся параллельно. Выключенные в настройках источники не запрашиваются,
 * скрытые — не показываются.
 */
data class MoviePickerResult(
    val stream: AnimeMediaStream,
    /** Сериалы: выбранная серия; фильмы: null. */
    val episode: MovieEpisodeRef?,
    val dubTitle: String,
    val sourceId: String,
    /** Фильмы: строки выпадающего списка озвучек плеера (Kodik — ленивые, прямые — готовые). */
    val translations: List<FlatTranslation>,
    val preparedStreams: Map<String, AnimeMediaStream>,
    val currentTranslationId: String,
    /** Сериалы: контекст для переключения сезонов/серий/озвучек в плеере. */
    val seriesContext: MovieSeriesPlaybackContext?
)

/**
 * Цель скачивания из кино-пикера: самодостаточный набор для построения очереди
 * на платформе (Android собирает EpisodeDownloadRequest'ы, резолв ленивый).
 */
data class MovieDownloadTarget(
    val kinopoiskId: Int,
    val displayTitle: String,
    val posterUrl: String?,
    val request: MoviePlaybackRequest,
    val kind: MovieContentKind,
    /** true = вся озвучка (кнопка на строке даба / фильм), false = одна серия. */
    val wholeDub: Boolean,
    val season: Int,
    val number: Int,
    val dubTitle: String,
    val sourceId: String,
    /** Сырой id даба внутри источника (Kodik translationId / ddbb dubId). */
    val translationId: String,
    val isKodik: Boolean,
    val isDirect: Boolean,
    /** Сериалы: кандидаты семейства (Kodik-каталог или прямые per-dub кандидаты). */
    val candidates: List<KodikMovieCandidate> = emptyList(),
    /** Сериалы: серии даба для очереди. */
    val episodes: List<MovieEpisodeRef> = emptyList(),
    /** Фильм Kodik: player-url для HLS-извлечения. */
    val movieUrls: List<String> = emptyList(),
    /** Фильм прямой: готовый CDN-url + заголовки. */
    val directUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val directHeaders: Map<String, String> = emptyMap()
)

/** Состояние загрузки одного источника кино-пикера. */
private sealed interface MovieSourceLoadState {
    data object Loading : MovieSourceLoadState
    data class Ready(val options: List<MoviePickerOption>) : MovieSourceLoadState
    data object Empty : MovieSourceLoadState
    data class Failed(val message: String) : MovieSourceLoadState
}

/** Одна серия/фильм одного даба одного источника. */
private data class MoviePickerEpisode(
    val season: Int,
    val number: Int,
    val title: String?,
    /** Прямой CDN url либо Kodik player-url (требует HLS-извлечения при запуске). */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val ladder: Map<String, String> = emptyMap(),
    val isKodik: Boolean = false
)

/** Один даб одного источника: набор серий (сериал) или одна строка фильма. */
private data class MoviePickerOption(
    val sourceId: String,
    /** Сырой id даба внутри источника (Kodik translationId / ddbb dubId). */
    val translationId: String,
    val dubTitle: String,
    val episodes: List<MoviePickerEpisode>,
    /** Фильмы (Kodik): все кандидатые player-url даба для перебора при резолве. */
    val movieUrls: List<String> = emptyList()
)

/** Один даб поверх всех источников (строка шага озвучек). */
private data class MovieDubGroup(
    val key: String,
    val title: String,
    val episodeCount: Int,
    val sourcesCount: Int,
    val qualityBadge: String?,
    /** true, когда хоть один источник даба отдаёт настоящую лестницу (не голый Auto):
     *  такие дабы поднимаются выше Auto-only строк (мёртвый Collaps-мастер без бейджа
     *  больше не возглавляет список). */
    val hasQuality: Boolean = false
)

private const val MOVIE_SOURCE_TIMEOUT_MS = 25_000L
private const val HDREZKA_SOURCE_TIMEOUT_MS = 45_000L
private const val TAG = "MoviePicker"

private fun normalizeDubKey(title: String): String =
    title.trim().lowercase().replace(Regex("[^a-zа-яё0-9]+"), "")

private fun seasonEpisodeKey(season: Int, number: Int): Pair<Int, Int> = season to number

private enum class MovieStep { EPISODE, DUB, SOURCE }

private fun steps(index: Int, showEpisodes: Boolean): MovieStep {
    val order = if (showEpisodes) listOf(MovieStep.EPISODE, MovieStep.DUB, MovieStep.SOURCE)
    else listOf(MovieStep.DUB, MovieStep.SOURCE)
    return order.getOrNull(index) ?: order.last()
}

/** True, когда заголовок серии настоящий, а не синтезированный «Серия N». */
private fun hasRealTitle(title: String?, season: Int, number: Int): Boolean {
    val t = title?.trim() ?: return false
    if (t.isEmpty() || t.equals("null", ignoreCase = true)) return false
    if (t == "Серия $number") return false
    if (t.startsWith("Сезон ") && t.endsWith("Серия $number")) return false
    return true
}

@Composable
fun MoviePlaybackSelectionScreen(
    request: MoviePlaybackRequest,
    displayTitle: String,
    isSeries: Boolean,
    profile: UserFilmProfile?,
    seasons: List<SeasonItem> = emptyList(),
    posterUrl: String? = null,
    userStateStore: hd.kinoshka.app.data.local.UserStateStoreBase? = null,
    /** (сезон, серия), скачанные хотя бы в одной озвучке: пассивная пометка на плитках. */
    downloadedEpisodeKeys: Set<Pair<Int, Int>> = emptySet(),
    /** translationId (сырой id даба в источнике) → число скачанных серий. */
    downloadedByTranslation: Map<String, Int> = emptyMap(),
    /** null — кнопки скачивания скрыты (desktop). */
    onDownloadTarget: ((MovieDownloadTarget) -> Unit)? = null,
    onDismissRequest: () -> Unit,
    // Иконка источника: платформа подставляет реальные картинки, по умолчанию —
    // рисованный бейдж. Учитывается раздел «Фильмы».
    sourceIcon: @Composable (sourceId: String, size: Dp) -> Unit =
        { id, size -> SourceBrandIcon(sourceId = id, size = size) },
    onMovieSelected: (MoviePickerResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val platformActions = rememberKinoPlatformActions()
    // Настройки источников читаются при каждом открытии страницы — возврат из
    // «Настройки → Источники» сразу виден без перезахода в детали.
    // Учитывается раздел «Фильмы»: выключение в «Аниме»/«18+» сюда не влияет.
    val sourcePrefs by produceState<Pair<Set<String>, Set<String>>?>(
        initialValue = null,
        key1 = request
    ) {
        value = if (userStateStore == null) {
            emptySet<String>() to emptySet<String>()
        } else {
            withContext(Dispatchers.IO) {
                userStateStore.getDisabledSources(hd.kinoshka.app.data.source.SourceCategory.FILMS) to userStateStore.getHiddenSources()
            }
        }
    }
    val prefsReady = sourcePrefs != null
    val disabled = remember(sourcePrefs) { sourcePrefs?.first.orEmpty().map { it.uppercase() }.toSet() }
    val hidden = remember(sourcePrefs) { sourcePrefs?.second.orEmpty().map { it.uppercase() }.toSet() }
    // Свои источники грузятся тем же открытием страницы (дешёвое чтение prefs):
    // выключение для них работает через тот же [disabled]-сет.
    val customSources by produceState<List<CustomSource>>(
        initialValue = emptyList(),
        key1 = request
    ) {
        value = if (userStateStore == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { userStateStore.getCustomSources() }
        }
    }
    var currentStepIndex by remember { mutableIntStateOf(0) }
    var selectedSeason by remember { mutableIntStateOf(0) }
    var selectedEpisodeKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedDub by remember { mutableStateOf<MovieDubGroup?>(null) }
    var isResolvingStream by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    val sourceStatesFlow = remember(request) {
        MutableStateFlow<Map<String, MovieSourceLoadState>>(emptyMap())
    }
    var sourceStates by remember { mutableStateOf<Map<String, MovieSourceLoadState>>(emptyMap()) }
    LaunchedEffect(sourceStatesFlow) {
        sourceStatesFlow.collect { sourceStates = it }
    }

    // Сырые данные для финального резолва/контекста.
    var kodikCandidates by remember { mutableStateOf<List<KodikMovieCandidate>>(emptyList()) }
    var directParses by remember { mutableStateOf<List<DdbbStreamResolver.SourceParse>>(emptyList()) }
    var bulkStarted by remember { mutableStateOf(false) }

    val filmCustoms = remember(customSources) {
        customSources.filter {
            it.categories.isEmpty() || hd.kinoshka.app.data.source.SourceCategory.FILMS in it.categories
        }
    }
    val visibleSources = remember(disabled, filmCustoms) {
        (PlaybackSources.MOVIE_IDS + filmCustoms.map { it.id }).filter { it !in disabled }
    }

    fun applyDirectParses(parses: List<DdbbStreamResolver.SourceParse>) {
        directParses = parses
        val bySource = parses.groupBy { parse ->
            PlaybackSources.ddbbSourceNameToId(parse.sourceName) ?: parse.sourceName.uppercase()
        }
        sourceStatesFlow.update { current ->
            var next = current
            for ((sourceId, list) in bySource) {
                // Неизвестные ddbb-типы показываем как есть (сырым именем): реестр
                // пополняется отдельно, терять рабочие провайдеры нельзя.
                if (sourceId in disabled) continue
                if (next[sourceId] is MovieSourceLoadState.Ready) continue
                val options = list.flatMap { optionsFromParse(it, isSeries) }
                next = next + (sourceId to if (options.isEmpty()) MovieSourceLoadState.Empty else MovieSourceLoadState.Ready(options))
            }
            for (sourceId in visibleSources) {
                if (sourceId == PlaybackSources.KODIK || sourceId == PlaybackSources.HDREZKA) continue
                // bulk дождался ВСЕХ разборов: источника без парса в выдаче нет —
                // висящий Loading обязан стать Empty, иначе счётчик «n/8» стоит вечно.
                val state = next[sourceId]
                if (state is MovieSourceLoadState.Loading || state == null) {
                    next = next + (sourceId to MovieSourceLoadState.Empty)
                }
            }
            next
        }
    }

    fun startBulk() {
        if (bulkStarted) return
        bulkStarted = true
        scope.launch {
            val parses = withTimeoutOrNull(MOVIE_SOURCE_TIMEOUT_MS + 5_000L) {
                scope.async(Dispatchers.IO) {
                    DdbbStreamResolver.fetchSourceParses(
                        request.kinopoiskId ?: 0,
                        disabledIds = disabled,
                        imdbId = request.imdbId,
                        customSources = filmCustoms
                    )
                }.await()
            }
            if (parses == null) {
                // Таймаут bulk-запроса: висящие Loading помечаем провалом, повтор — по кнопке.
                sourceStatesFlow.update { current ->
                    var next = current
                    for (sourceId in visibleSources) {
                        if (sourceId != PlaybackSources.KODIK && next[sourceId] is MovieSourceLoadState.Loading) {
                            next = next + (sourceId to MovieSourceLoadState.Failed("Превышено время ожидания"))
                        }
                    }
                    next
                }
                bulkStarted = false
                return@launch
            }
            if (parses.isNotEmpty()) {
                val merged = DdbbStreamResolver.mergeSourceParses(parses)
                if ((merged.tracks.isNotEmpty() || merged.voiceRows.isNotEmpty()) && (request.kinopoiskId ?: 0) > 0) {
                    DdbbStreamResolver.registerTurboCatalog(
                        request.kinopoiskId ?: 0,
                        merged.headers,
                        DdbbStreamResolver.TurboSerialParse(merged.tracks, merged.ladders),
                        merged.voiceRows,
                        merged.headersByUrl
                    )
                }
            }
            applyDirectParses(parses)
        }
    }

    /** HDRezka идёт отдельным запросом: ему нужны названия из [request], а не только kp. */
    fun startHdrezka() {
        if (PlaybackSources.HDREZKA !in visibleSources) return
        if (sourceStatesFlow.value[PlaybackSources.HDREZKA] is MovieSourceLoadState.Loading) return
        sourceStatesFlow.update { it + (PlaybackSources.HDREZKA to MovieSourceLoadState.Loading) }
        scope.launch {
            // Свой лимит: поиск + Anubis + тайтл + до 60 ajax за сериями дольше bulk.
            val parse = withTimeoutOrNull(HDREZKA_SOURCE_TIMEOUT_MS) {
                scope.async(Dispatchers.IO) { HdrezkaApi.resolve(request) }.await()
            }
            if (parse != null) {
                applyDirectParses(listOf(parse))
            } else {
                sourceStatesFlow.update { current ->
                    if (current[PlaybackSources.HDREZKA] is MovieSourceLoadState.Ready) current
                    else current + (PlaybackSources.HDREZKA to MovieSourceLoadState.Empty)
                }
            }
        }
    }

    fun startKodik() {
        if (PlaybackSources.KODIK !in visibleSources) return
        if (sourceStatesFlow.value[PlaybackSources.KODIK] is MovieSourceLoadState.Loading) return
        sourceStatesFlow.update { it + (PlaybackSources.KODIK to MovieSourceLoadState.Loading) }
        scope.launch {
            // Таймаут не отменяет сам фетч: каталог кэшируется, повтор бьёт в кэш.
            val deferred = scope.async(Dispatchers.IO) { loadKodikOptions(request) }
            val loaded = withTimeoutOrNull(MOVIE_SOURCE_TIMEOUT_MS) { deferred.await() }
            if (loaded != null) kodikCandidates = loaded.second
            val newState = when {
                loaded == null -> MovieSourceLoadState.Failed("Превышено время ожидания")
                loaded.first.isEmpty() -> MovieSourceLoadState.Empty
                else -> MovieSourceLoadState.Ready(loaded.first)
            }
            sourceStatesFlow.update { current ->
                if (current[PlaybackSources.KODIK] is MovieSourceLoadState.Ready) current
                else current + (PlaybackSources.KODIK to newState)
            }
        }
    }

    /** Запуск ещё не загруженных источников — первое открытие и «Повторить». */
    fun startPendingSources() {
        visibleSources.forEach { id ->
            val state = sourceStatesFlow.value[id]
            if (state is MovieSourceLoadState.Loading || state is MovieSourceLoadState.Ready) return@forEach
            if (id == PlaybackSources.KODIK) startKodik()
            else if (id == PlaybackSources.HDREZKA) startHdrezka()
            else {
                sourceStatesFlow.update { it + (id to MovieSourceLoadState.Loading) }
                startBulk()
            }
        }
    }

    fun retrySource(sourceId: String) {
        if (sourceId == PlaybackSources.KODIK) {
            // startKodik игнорирует Loading — сбрасываем состояние перед повтором.
            sourceStatesFlow.update { it - sourceId }
            startKodik()
        } else if (sourceId == PlaybackSources.HDREZKA) {
            sourceStatesFlow.update { it - sourceId }
            startHdrezka()
        } else {
            bulkStarted = false
            sourceStatesFlow.update { it + (sourceId to MovieSourceLoadState.Loading) }
            startBulk()
        }
    }

    LaunchedEffect(request, prefsReady) {
        if (!prefsReady) return@LaunchedEffect
        // Помечаем Loading сразу, чтобы страница не мигала пустотой. Kodik и HDRezka
        // грузятся собственными стартерами (они сами ставят Loading + guard от повтора):
        // предмаркировка здесь глушила их guard, и загрузка не стартовала вообще.
        sourceStatesFlow.update { current ->
            var next = current
            for (id in visibleSources) {
                if (id == PlaybackSources.KODIK || id == PlaybackSources.HDREZKA) continue
                if (next[id] == null) next = next + (id to MovieSourceLoadState.Loading)
            }
            next
        }
        startKodik()
        startBulk()
        startHdrezka()
    }

    val allOptions = remember(sourceStates, hidden) {
        sourceStates.entries
            .filter { (id, _) -> id.uppercase() !in hidden }
            .flatMap { (id, state) ->
                (state as? MovieSourceLoadState.Ready)?.options.orEmpty().map { it.copy(sourceId = id.uppercase()) }
            }
    }
    val dubGroups = remember(allOptions) {
        allOptions.groupBy { normalizeDubKey(it.dubTitle) }.map { (key, variants) ->
            val bestTitle = variants.maxByOrNull { it.episodes.size }?.dubTitle?.trim().orEmpty()
            val bestQuality = variants.flatMap { it.episodes }.flatMap { it.ladder.keys }
                .maxByOrNull { q -> q.removeSuffix("p").toIntOrNull() ?: -1 }
            MovieDubGroup(
                key = key,
                title = bestTitle.ifBlank { "Озвучка" },
                episodeCount = variants.maxOf { it.episodes.size },
                sourcesCount = variants.map { it.sourceId }.distinct().size,
                qualityBadge = qualityBadgeLabel(bestQuality),
                hasQuality = variants.any { opt ->
                    opt.episodes.any { ep -> ep.ladder.keys.any { q -> !q.equals("Auto", ignoreCase = true) } }
                }
            )
        }.sortedWith(
            compareByDescending<MovieDubGroup> { it.episodeCount }
                .thenByDescending { it.hasQuality }
                .thenBy { it.title.lowercase() }
        )
    }

    val mergedSeasons = remember(allOptions) {
        allOptions.flatMap { it.episodes }.map { it.season }.distinct().sorted()
    }
    val mergedEpisodes = remember(allOptions, selectedSeason) {
        allOptions.flatMap { it.episodes }
            .filter { if (selectedSeason > 0) it.season == selectedSeason else true }
            .groupBy { seasonEpisodeKey(it.season, it.number) }
            .map { (_, list) -> list.firstOrNull { hasRealTitle(it.title, it.season, it.number) } ?: list.first() }
            .sortedWith(compareBy({ it.season }, { it.number }))
    }
    // Серия → сколько дабов её несут (бейдж «N озв.») и лучшее качество (бейдж FHD/HD).
    val episodeDubCounts = remember(allOptions, selectedSeason) {
        allOptions.flatMap { opt ->
            opt.episodes
                .filter { if (selectedSeason > 0) it.season == selectedSeason else true }
                .map { seasonEpisodeKey(it.season, it.number) to normalizeDubKey(opt.dubTitle) }
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, dubs) -> dubs.distinct().size }
    }
    val episodeBestQuality = remember(allOptions, selectedSeason) {
        allOptions.flatMap { it.episodes }
            .filter { if (selectedSeason > 0) it.season == selectedSeason else true }
            .groupBy { seasonEpisodeKey(it.season, it.number) }
            .mapValues { (_, list) ->
                list.flatMap { it.ladder.keys }.maxByOrNull { q -> q.removeSuffix("p").toIntOrNull() ?: -1 }
            }
    }
    val hasSeriesStructure = remember(allOptions) {
        allOptions.flatMap { it.episodes }.map { seasonEpisodeKey(it.season, it.number) }.distinct().size > 1
    }
    val showEpisodes = isSeries && hasSeriesStructure
    val totalSteps = if (showEpisodes) 3 else 2

    LaunchedEffect(mergedSeasons, profile) {
        if (mergedSeasons.isEmpty()) return@LaunchedEffect
        val wanted = profile?.takeIf { it.status != UserFilmStatus.COMPLETED }
            ?.watchedSeasons?.takeIf { it > 0 }
        selectedSeason = when {
            selectedSeason in mergedSeasons -> selectedSeason
            wanted != null && wanted in mergedSeasons -> wanted
            1 in mergedSeasons -> 1
            else -> mergedSeasons.first()
        }
    }

    val resumeEpisode: Pair<Int, Int>? = remember(allOptions, profile, showEpisodes) {
        if (!showEpisodes) return@remember null
        val keys = allOptions.flatMap { it.episodes }.map { seasonEpisodeKey(it.season, it.number) }.toSet()
        if (keys.isEmpty()) return@remember null
        val completed = profile?.status == UserFilmStatus.COMPLETED
        val wS = if (!completed) profile?.watchedSeasons?.takeIf { it > 0 } else null
        val wE = if (!completed) profile?.watchedEpisodes?.takeIf { it > 0 } else null
        if (wS != null && wE != null) {
            val sameSeason = keys.filter { it.first == wS }.sortedBy { it.second }
            sameSeason.firstOrNull { it.second >= wE } ?: sameSeason.firstOrNull()
            ?: keys.sortedWith(compareBy({ it.first }, { it.second })).firstOrNull()
        } else null
    }

    val visibleDubs = remember(dubGroups, allOptions, selectedEpisodeKey) {
        if (selectedEpisodeKey == null) dubGroups
        else dubGroups.filter { dub ->
            allOptions.any { opt ->
                normalizeDubKey(opt.dubTitle) == dub.key &&
                    opt.episodes.any { seasonEpisodeKey(it.season, it.number) == selectedEpisodeKey }
            }
        }
    }

    fun optionsForDub(dub: MovieDubGroup): List<MoviePickerOption> {
        val episodeFilter = selectedEpisodeKey
        return allOptions.filter { normalizeDubKey(it.dubTitle) == dub.key }
            .filter { opt ->
                episodeFilter == null || opt.episodes.any { seasonEpisodeKey(it.season, it.number) == episodeFilter }
            }
            // Один и тот же даб одного источника может прийти из двух разборов
            // (ddbb-эмбед Collaps + стендалон Collaps): схлопываем в одну строку,
            // иначе LazyColumn падает на дубле ключа.
            .groupBy { it.sourceId to it.translationId }
            .map { (_, opts) ->
                opts.reduce { acc, o ->
                    acc.copy(
                        episodes = (acc.episodes + o.episodes)
                            .distinctBy { seasonEpisodeKey(it.season, it.number) },
                        movieUrls = (acc.movieUrls + o.movieUrls).distinct()
                    )
                }
            }
    }

    /** Лучшая опция даба для скачивания целиком: больше серий, затем приоритет источника. */
    fun bestOptionForDub(dub: MovieDubGroup): MoviePickerOption? =
        optionsForDub(dub).maxWithOrNull(
            compareBy<MoviePickerOption> { it.episodes.size }.thenBy { -sourceRankForPicker(it.sourceId) }
        )

    fun downloadTargetFor(
        option: MoviePickerOption,
        season: Int,
        number: Int,
        wholeDub: Boolean
    ): MovieDownloadTarget {
        val kpId = request.kinopoiskId ?: 0
        return if (option.sourceId == PlaybackSources.KODIK) {
            if (showEpisodes) {
                MovieDownloadTarget(
                    kinopoiskId = kpId, displayTitle = displayTitle, posterUrl = posterUrl,
                    request = request, kind = MovieContentKind.SERIES, wholeDub = wholeDub,
                    season = season, number = number,
                    dubTitle = option.dubTitle, sourceId = option.sourceId, translationId = option.translationId,
                    isKodik = true, isDirect = false,
                    candidates = kodikCandidates,
                    episodes = option.episodes.map { MovieEpisodeRef(it.season, it.number, it.title, it.url) }
                )
            } else {
                MovieDownloadTarget(
                    kinopoiskId = kpId, displayTitle = displayTitle, posterUrl = posterUrl,
                    request = request, kind = MovieContentKind.MOVIE, wholeDub = true,
                    season = 1, number = 1,
                    dubTitle = option.dubTitle, sourceId = option.sourceId, translationId = option.translationId,
                    isKodik = true, isDirect = false,
                    movieUrls = option.movieUrls
                )
            }
        } else {
            if (showEpisodes) {
                val merged = DdbbStreamResolver.mergeSourceParses(
                    directParses.sortedBy { DdbbStreamResolver.sourceRank(it.sourceName) }
                )
                MovieDownloadTarget(
                    kinopoiskId = kpId, displayTitle = displayTitle, posterUrl = posterUrl,
                    request = request, kind = MovieContentKind.SERIES, wholeDub = wholeDub,
                    season = season, number = number,
                    dubTitle = option.dubTitle, sourceId = option.sourceId, translationId = option.translationId,
                    isKodik = false, isDirect = true,
                    candidates = directSeriesCandidates(directParses, displayTitle, request),
                    episodes = option.episodes.map { MovieEpisodeRef(it.season, it.number, it.title, it.url) },
                    directHeaders = merged.headers
                )
            } else {
                val url = option.movieUrls.firstOrNull() ?: option.episodes.firstOrNull()?.url.orEmpty()
                val ep = option.episodes.firstOrNull { it.url == url }
                MovieDownloadTarget(
                    kinopoiskId = kpId, displayTitle = displayTitle, posterUrl = posterUrl,
                    request = request, kind = MovieContentKind.MOVIE, wholeDub = true,
                    season = 1, number = 1,
                    dubTitle = option.dubTitle, sourceId = option.sourceId, translationId = option.translationId,
                    isKodik = false, isDirect = true,
                    directUrl = url,
                    headers = ep?.headers ?: DdbbStreamResolver.directHeaders(kpId, url)
                )
            }
        }
    }

    fun resolveAndPlay(option: MoviePickerOption, season: Int, number: Int) {
        resolveError = null
        isResolvingStream = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    resolveMoviePick(
                        request = request,
                        displayTitle = displayTitle,
                        isSeries = showEpisodes,
                        option = option,
                        season = season,
                        number = number,
                        kodikCandidates = kodikCandidates,
                        directParses = directParses,
                        allOptions = allOptions,
                        profile = profile
                    )
                }
                isResolvingStream = false
                if (result != null) {
                    onMovieSelected(result)
                    onDismissRequest()
                } else {
                    resolveError = "Не удалось получить видеопоток"
                    platformActions.showToast("Не удалось получить видеопоток")
                }
            } catch (e: Exception) {
                isResolvingStream = false
                resolveError = "Ошибка при запуске плеера: ${e.message}"
                KLog.w(TAG, "resolveAndPlay failed", e)
                platformActions.showToast("Ошибка при запуске плеера")
            }
        }
    }

    fun handleBack() {
        if (currentStepIndex > 0) {
            currentStepIndex--
            when (steps(currentStepIndex, showEpisodes)) {
                MovieStep.EPISODE -> selectedEpisodeKey = null
                MovieStep.DUB -> selectedDub = null
                MovieStep.SOURCE -> Unit
            }
        } else {
            onDismissRequest()
        }
    }

    val allSettled = prefsReady && visibleSources.all {
        val s = sourceStates[it]
        s is MovieSourceLoadState.Ready || s is MovieSourceLoadState.Empty || s is MovieSourceLoadState.Failed
    }
    val isLoadingSources = !allSettled
    val errorMessage = if (allSettled && allOptions.isEmpty()) {
        when {
            visibleSources.isEmpty() -> "Все источники выключены — включите их в настройках."
            hidden.isNotEmpty() -> "Источники скрыты в настройках — включите их отображение."
            else -> "Не удалось найти видео для этого тайтла."
        }
    } else null

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = ::handleBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (steps(currentStepIndex, showEpisodes)) {
                            MovieStep.EPISODE -> "Выбор сезона и серии"
                            MovieStep.DUB -> if (showEpisodes) {
                                selectedEpisodeKey?.let { (s, e) -> "Выбор озвучки • С${s} Е${e}" } ?: "Выбор озвучки"
                            } else "Выбор озвучки"
                            MovieStep.SOURCE -> "Выбор источника • ${selectedDub?.title.orEmpty()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            AnimatedVisibility(visible = isLoadingSources) {
                val settled = visibleSources.count {
                    val s = sourceStates[it]
                    s is MovieSourceLoadState.Ready || s is MovieSourceLoadState.Empty || s is MovieSourceLoadState.Failed
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Загрузка источников… $settled/${visibleSources.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (resolveError != null) {
                Text(
                    text = resolveError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                    textAlign = TextAlign.Center
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                when {
                    isResolvingStream -> {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            KinoLoadingIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Получение ссылки на видеопоток...", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                    errorMessage != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                            Text(text = errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            Button(
                                onClick = {
                                    currentStepIndex = 0
                                    selectedEpisodeKey = null
                                    selectedDub = null
                                    sourceStatesFlow.update { emptyMap() }
                                    directParses = emptyList()
                                    kodikCandidates = emptyList()
                                    bulkStarted = false
                                    startPendingSources()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("Повторить поиск") }
                        }
                    }
                    else -> {
                        AnimatedContent(
                            targetState = steps(currentStepIndex, showEpisodes),
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "movieWatchAnimation"
                        ) { step ->
                            when (step) {
                                MovieStep.EPISODE -> MovieEpisodeStep(
                                    seasons = mergedSeasons,
                                    selectedSeason = selectedSeason,
                                    onSeasonSelected = { selectedSeason = it },
                                    episodes = mergedEpisodes,
                                    dubCounts = episodeDubCounts,
                                    bestQualities = episodeBestQuality,
                                    kinopoiskSeasons = seasons,
                                    profile = profile,
                                    downloadedKeys = downloadedEpisodeKeys,
                                    resumeEpisode = resumeEpisode,
                                    onResumeSelected = { key ->
                                        selectedSeason = key.first
                                        selectedEpisodeKey = key
                                        currentStepIndex++
                                    },
                                    sourceStates = sourceStates,
                                    visibleSources = visibleSources,
                                    onRetrySource = ::retrySource,
                                    onEpisodeSelected = { key ->
                                        selectedEpisodeKey = key
                                        currentStepIndex++
                                    }
                                )
                                MovieStep.DUB -> MovieDubStep(
                                    dubs = visibleDubs,
                                    selectedEpisodeKey = selectedEpisodeKey,
                                    downloadedCountFor = { dub ->
                                        optionsForDub(dub).sumOf { downloadedByTranslation[it.translationId] ?: 0 }
                                    },
                                    onDownloadDub = onDownloadTarget?.let { download ->
                                        { dub: MovieDubGroup ->
                                            bestOptionForDub(dub)?.let { opt ->
                                                download(
                                                    downloadTargetFor(
                                                        opt,
                                                        selectedEpisodeKey?.first ?: 1,
                                                        selectedEpisodeKey?.second ?: 1,
                                                        wholeDub = true
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    onDubSelected = { dub ->
                                        selectedDub = dub
                                        // Источник всего один — страницу выбора не показываем,
                                        // а сразу запускаем из единственного источника.
                                        val single = optionsForDub(dub).singleOrNull()
                                        if (single != null) {
                                            if (showEpisodes) {
                                                val key = selectedEpisodeKey
                                                if (key != null) resolveAndPlay(single, key.first, key.second)
                                                else currentStepIndex++
                                            } else {
                                                resolveAndPlay(single, 1, 1)
                                            }
                                        } else {
                                            currentStepIndex++
                                        }
                                    }
                                )
                                MovieStep.SOURCE -> {
                                    val dub = selectedDub
                                    if (dub == null) {
                                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                            TextButton(onClick = ::handleBack) { Text("К выбору озвучки") }
                                        }
                                    } else {
                                        MovieSourceStep(
                                            options = optionsForDub(dub),
                                            selectedEpisodeKey = selectedEpisodeKey,
                                            sourceIcon = sourceIcon,
                                            onDownloadSingle = onDownloadTarget?.let { download ->
                                                { opt: MoviePickerOption ->
                                                    if (showEpisodes) {
                                                        val key = selectedEpisodeKey
                                                        if (key != null) download(downloadTargetFor(opt, key.first, key.second, wholeDub = false))
                                                    } else {
                                                        download(downloadTargetFor(opt, 1, 1, wholeDub = true))
                                                    }
                                                }
                                            },
                                            onSourceSelected = { option ->
                                                if (showEpisodes) {
                                                    val key = selectedEpisodeKey
                                                    if (key != null) resolveAndPlay(option, key.first, key.second)
                                                } else {
                                                    resolveAndPlay(option, 1, 1)
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Шаг ${currentStepIndex + 1} из $totalSteps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

// ---------- Загрузка ----------

/** Kodik-каталог → опции пикера + сырые кандидаты для финального резолва. Вызывать в IO. */
private suspend fun loadKodikOptions(request: MoviePlaybackRequest): Pair<List<MoviePickerOption>, List<KodikMovieCandidate>> {
    val catalog = MovieStreamResolver.loadCatalog(request)
    if (catalog !is MovieCatalogResult.Available) return emptyList<MoviePickerOption>() to emptyList()
    val candidates = catalog.candidates
    val isSeries = request.kind == MovieContentKind.SERIES
    val options = candidates.groupBy { it.translationId ?: it.translationTitle ?: "default" }
        .mapNotNull { (trId, rows) ->
            val title = rows.firstNotNullOfOrNull { it.translationTitle } ?: "Озвучка $trId"
            if (isSeries) {
                val episodes = rows.flatMap { it.episodes }.distinctBy { it.seasonNumber to it.episodeNumber }
                    .map {
                        MoviePickerEpisode(
                            season = it.seasonNumber, number = it.episodeNumber,
                            title = it.title, url = it.playerUrl, isKodik = true
                        )
                    }
                    .ifEmpty {
                        rows.mapNotNull { it.topLevelPlayerUrl }.firstOrNull()?.let { url ->
                            listOf(MoviePickerEpisode(1, 1, "Серия 1", url, isKodik = true))
                        }.orEmpty()
                    }
                if (episodes.isEmpty()) return@mapNotNull null
                MoviePickerOption(PlaybackSources.KODIK, trId, title, episodes.sortedWith(compareBy({ it.season }, { it.number })))
            } else {
                val urls = (rows.mapNotNull { it.topLevelPlayerUrl } + rows.flatMap { it.episodes }.map { it.playerUrl }).distinct()
                if (urls.isEmpty()) return@mapNotNull null
                MoviePickerOption(
                    sourceId = PlaybackSources.KODIK, translationId = trId, dubTitle = title,
                    episodes = listOf(MoviePickerEpisode(1, 1, title, urls.first(), isKodik = true)),
                    movieUrls = urls
                )
            }
        }.filter { it.episodes.isNotEmpty() }
    return options to candidates
}

private fun optionsFromParse(
    parse: DdbbStreamResolver.SourceParse,
    isSeries: Boolean
): List<MoviePickerOption> {
    val sourceId = PlaybackSources.ddbbSourceNameToId(parse.sourceName) ?: parse.sourceName.uppercase()
    fun headersFor(url: String): Map<String, String> = parse.headersByUrl[url] ?: parse.headers
    fun ladderFor(url: String): Map<String, String> =
        parse.ladders[url] ?: if (parse.url == url) parse.qualities else mapOf("Auto" to url)
    if (isSeries && parse.tracks.isNotEmpty()) {
        return parse.tracks.groupBy { it.dubId }.map { (dubId, rows) ->
            val sorted = rows.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            MoviePickerOption(
                sourceId = sourceId,
                translationId = dubId,
                dubTitle = sorted.first().dubTitle,
                episodes = sorted.map {
                    MoviePickerEpisode(
                        season = it.seasonNumber, number = it.episodeNumber,
                        title = it.title, url = it.playerUrl,
                        headers = headersFor(it.playerUrl), ladder = ladderFor(it.playerUrl)
                    )
                }
            )
        }
    }
    if (parse.voiceRows.isNotEmpty()) {
        return parse.voiceRows.map { (title, url) ->
            MoviePickerOption(
                sourceId = sourceId,
                translationId = title,
                dubTitle = title,
                episodes = listOf(
                    MoviePickerEpisode(1, 1, title, url, headersFor(url), ladderFor(url))
                ),
                movieUrls = listOf(url)
            )
        }
    }
    if (parse.tracks.isNotEmpty()) {
        // Сериальная структура при запросе фильма: первый трек даба как строка фильма.
        return parse.tracks.groupBy { it.dubId }.mapNotNull { (dubId, rows) ->
            val first = rows.minWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber })) ?: return@mapNotNull null
            MoviePickerOption(
                sourceId = sourceId,
                translationId = dubId,
                dubTitle = first.dubTitle,
                episodes = listOf(
                    MoviePickerEpisode(1, 1, first.dubTitle, first.playerUrl, headersFor(first.playerUrl), ladderFor(first.playerUrl))
                ),
                movieUrls = listOf(first.playerUrl)
            )
        }
    }
    return emptyList()
}

// ---------- Финальный резолв ----------

private suspend fun resolveMoviePick(
    request: MoviePlaybackRequest,
    displayTitle: String,
    isSeries: Boolean,
    option: MoviePickerOption,
    season: Int,
    number: Int,
    kodikCandidates: List<KodikMovieCandidate>,
    directParses: List<DdbbStreamResolver.SourceParse>,
    allOptions: List<MoviePickerOption>,
    profile: UserFilmProfile?
): MoviePickerResult? {
    val kpId = request.kinopoiskId ?: 0
    return if (option.sourceId == PlaybackSources.KODIK) {
        if (isSeries) {
            val ref = MovieEpisodeRef(season, number, null, "")
            val resolved = MovieStreamResolver.resolveEpisode(request, ref, kodikCandidates.ifEmpty { null }, option.translationId)
            val success = resolved as? MovieStreamResult.Success ?: run {
                // Мёртвая ссылка даба не роняет запуск: пробуем без привязки к дабу.
                MovieStreamResolver.resolveEpisode(request, ref, kodikCandidates.ifEmpty { null }) as? MovieStreamResult.Success
            } ?: return null
            // Выбранный даб — первым: union-список переуказывает серии на его ссылки,
            // иначе плеер стартовал бы с url алфавитно-первого даба («включилась другая озвучка»).
            val ordered = kodikCandidates.sortedBy { it.translationId != option.translationId }
            val raw = canonicalSeriesEpisodes(ordered).ifEmpty {
                listOf(MovieEpisodeRef(1, 1, "Серия 1", ordered.firstOrNull()?.topLevelPlayerUrl.orEmpty()))
            }
            val pickedEpUrls = ordered.filter { it.translationId == option.translationId }
                .flatMap { it.episodes }
                .associate { (it.seasonNumber to it.episodeNumber) to it.playerUrl }
            val episodes = raw.map { ref ->
                ref.copy(playerUrl = pickedEpUrls[ref.seasonNumber to ref.episodeNumber] ?: ref.playerUrl)
            }
            val current = episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == number }
                ?: episodes.firstOrNull() ?: return null
            val context = MovieSeriesPlaybackContext(
                request = request,
                candidates = ordered,
                episodes = episodes,
                currentEpisode = current,
                kinopoiskId = kpId,
                displayTitle = displayTitle
            )
            if (kpId > 0) hd.kinoshka.app.data.model.MovieSeriesContextStore.put(context)
            MoviePickerResult(
                stream = success.stream,
                episode = current,
                dubTitle = option.dubTitle,
                sourceId = PlaybackSources.KODIK,
                translations = emptyList(),
                preparedStreams = emptyMap(),
                currentTranslationId = option.translationId,
                seriesContext = context
            )
        } else {
            val stream = MovieStreamResolver.resolveMovieUrls(option.movieUrls) ?: return null
            val (translations, prepared) = buildQomLists(allOptions, picked = option to stream)
            if (kpId > 0) hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.put(kpId, prepared)
            MoviePickerResult(
                stream = stream,
                episode = null,
                dubTitle = option.dubTitle,
                sourceId = PlaybackSources.KODIK,
                translations = translations,
                preparedStreams = prepared,
                currentTranslationId = option.translationId,
                seriesContext = null
            )
        }
    } else {
        val targetUrl = if (isSeries) {
            option.episodes.firstOrNull { it.season == season && it.number == number }?.url
                ?: option.episodes.firstOrNull()?.url
        } else {
            option.movieUrls.firstOrNull() ?: option.episodes.firstOrNull()?.url
        } ?: return null
        val pickedEp = option.episodes.firstOrNull { it.url == targetUrl }
        val ladder = pickedEp?.ladder?.ifEmpty { null }
            ?: directParses.flatMap { it.ladders.entries }.firstOrNull { it.key == targetUrl }?.value
            ?: mapOf("Auto" to targetUrl)
        val headers = pickedEp?.headers?.ifEmpty { null }
            ?: directParses.firstNotNullOfOrNull { it.headersByUrl[targetUrl] }
            ?: directParses.firstOrNull { it.url == targetUrl }?.headers
            ?: DdbbStreamResolver.directHeaders(kpId, targetUrl).ifEmpty { null }
            ?: DdbbStreamResolver.directHeaders(kpId)
        val bestKey = QUALITY_PREFERENCE_DESC.firstOrNull { ladder.containsKey(it) } ?: ladder.keys.firstOrNull() ?: "Auto"
        val stream = AnimeMediaStream(
            url = ladder[bestKey] ?: targetUrl,
            qualities = ladder,
            headers = headers,
            quality = bestKey
        )
        if (isSeries) {
            val context = buildDirectSeriesContext(
                request, displayTitle, directParses, season, number, profile,
                pickedDubId = option.translationId
            ) ?: return null
            if (kpId > 0) hd.kinoshka.app.data.model.MovieSeriesContextStore.put(context)
            MoviePickerResult(
                stream = stream,
                episode = context.currentEpisode,
                dubTitle = option.dubTitle,
                sourceId = option.sourceId,
                translations = emptyList(),
                preparedStreams = emptyMap(),
                currentTranslationId = option.translationId,
                seriesContext = context
            )
        } else {
            val (translations, prepared) = buildQomLists(allOptions, picked = option to stream)
            if (kpId > 0) hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.put(kpId, prepared)
            MoviePickerResult(
                stream = stream,
                episode = null,
                dubTitle = option.dubTitle,
                sourceId = option.sourceId,
                translations = translations,
                preparedStreams = prepared,
                currentTranslationId = qomTranslationId(option),
                seriesContext = null
            )
        }
    }
}

/** Строки дропдауна озвучек плеера для фильма + подготовленные потоки (выбранный даб). */
private fun buildQomLists(
    allOptions: List<MoviePickerOption>,
    picked: Pair<MoviePickerOption, AnimeMediaStream>?
): Pair<List<FlatTranslation>, Map<String, AnimeMediaStream>> {
    val rows = allOptions.filter { it.movieUrls.isNotEmpty() || it.episodes.isNotEmpty() }
        .sortedWith(compareBy({ sourceRankForPicker(it.sourceId) }, { it.dubTitle.lowercase() }))
        .mapNotNull { opt ->
            val link = opt.movieUrls.firstOrNull() ?: opt.episodes.firstOrNull()?.url.orEmpty()
            // Пустая ссылка — строка, тап по которой молча ничего не делает («озвучка
            // не работает»): такие разборы в дропдаун не отдаём вообще.
            if (link.isBlank()) return@mapNotNull null
            val (display, kind) = MovieNativeLauncher.splitDubTrack(opt.dubTitle)
            FlatTranslation(
                source = PlaybackSources.animeSourceTypeFor(opt.sourceId),
                translationId = qomTranslationId(opt),
                title = display,
                type = kind,
                episodes = listOf(AnimeEpisode(number = 1, title = display, link = link))
            )
        }
        .distinctBy { it.source to it.translationId }
    val prepared = picked?.let { (opt, stream) -> mapOf(qomTranslationId(opt) to stream) }.orEmpty()
    val firstId = picked?.let { qomTranslationId(it.first) }
    val ordered = firstId?.let { id -> rows.sortedBy { it.translationId != id } } ?: rows
    return ordered to prepared
}

/**
 * Id строки QOM-дропдауна: Kodik — сырой translationId каталога, прямые — со скоупом
 * источника («TURBO|Дубляж»). Без скоупа один и тот же даб Turbo и Collaps схлопывался
 * в одну строку с чужой ссылкой (подготовленный поток затирал соседний по ключу),
 * а общий DDBB-тип прятал провайдера.
 */
private fun qomTranslationId(opt: MoviePickerOption): String =
    if (opt.sourceId == PlaybackSources.KODIK) opt.translationId
    else "${opt.sourceId}|${opt.dubTitle}"

private fun sourceRankForPicker(sourceId: String): Int = when (sourceId) {
    PlaybackSources.TURBO -> 0
    PlaybackSources.HDREZKA -> 1
    PlaybackSources.VIDEOCDN -> 2
    PlaybackSources.COLLAPS -> 3
    PlaybackSources.VOIDBOOST -> 4
    PlaybackSources.KODIK -> 5
    else -> 6
}

/** Per-dub кандидаты прямой семьи для контекста плеера и очереди скачивания. */
private fun directSeriesCandidates(
    parses: List<DdbbStreamResolver.SourceParse>,
    displayTitle: String,
    request: MoviePlaybackRequest
): List<KodikMovieCandidate> {
    if (parses.isEmpty()) return emptyList()
    val kpId = request.kinopoiskId ?: 0
    val merged = DdbbStreamResolver.mergeSourceParses(parses.sortedBy { DdbbStreamResolver.sourceRank(it.sourceName) })
    if (merged.tracks.isEmpty()) return emptyList()
    return merged.tracks.groupBy { it.dubId }.map { (_, rows) ->
        val sorted = rows.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        KodikMovieCandidate(
            sourceIndex = 0,
            kinopoiskId = kpId.takeIf { it > 0 },
            imdbId = request.imdbId,
            title = displayTitle,
            originalTitle = null,
            year = request.year,
            kind = MovieContentKind.SERIES,
            translationId = sorted.first().dubId,
            translationTitle = sorted.first().dubTitle,
            topLevelPlayerUrl = null,
            episodes = sorted.map {
                MovieEpisodeRef(it.seasonNumber, it.episodeNumber, it.title ?: "Серия ${it.episodeNumber}", it.playerUrl)
            }
        )
    }.sortedBy { it.translationTitle?.lowercase().orEmpty() }
}

private fun buildDirectSeriesContext(
    request: MoviePlaybackRequest,
    displayTitle: String,
    parses: List<DdbbStreamResolver.SourceParse>,
    season: Int,
    number: Int,
    profile: UserFilmProfile?,
    pickedDubId: String? = null
): MovieSeriesPlaybackContext? {
    val candidates = directSeriesCandidates(parses, displayTitle, request)
    if (candidates.isEmpty()) return null
    val kpId = request.kinopoiskId ?: 0
    // Выбранный даб — первым: union-список переуказывает серии на ЕГО ссылки.
    // Без этого плеер стартовал с url алфавитно-первого даба («включилась другая озвучка»).
    val ordered = if (pickedDubId != null) {
        candidates.sortedBy { it.translationId != pickedDubId }
    } else candidates
    val rawUnion = canonicalSeriesEpisodes(ordered)
    if (rawUnion.isEmpty()) return null
    val pickedUrls = ordered.firstOrNull { it.translationId == pickedDubId }
        ?.episodes?.associate { (it.seasonNumber to it.episodeNumber) to it.playerUrl }
        .orEmpty()
    val union = rawUnion.map { ref ->
        ref.copy(playerUrl = pickedUrls[ref.seasonNumber to ref.episodeNumber] ?: ref.playerUrl)
    }
    val picked = union.firstOrNull { it.seasonNumber == season && it.episodeNumber == number }
        ?: hd.kinoshka.app.data.model.selectInitialSeriesEpisode(union, profile)
        ?: union.first()
    val merged = DdbbStreamResolver.mergeSourceParses(parses.sortedBy { DdbbStreamResolver.sourceRank(it.sourceName) })
    DdbbStreamResolver.registerTurboCatalog(
        kpId,
        merged.headers,
        DdbbStreamResolver.TurboSerialParse(merged.tracks, merged.ladders),
        merged.voiceRows,
        merged.headersByUrl
    )
    return MovieSeriesPlaybackContext(
        request = request,
        candidates = ordered,
        episodes = union,
        currentEpisode = picked,
        kinopoiskId = kpId,
        displayTitle = displayTitle,
        isDirectSource = true,
        directHeaders = merged.headers
    )
}

// ---------- Шаги (плитки в стиле аниме-пикера) ----------

@Composable
private fun MovieEpisodeStep(
    seasons: List<Int>,
    selectedSeason: Int,
    onSeasonSelected: (Int) -> Unit,
    episodes: List<MoviePickerEpisode>,
    dubCounts: Map<Pair<Int, Int>, Int>,
    bestQualities: Map<Pair<Int, Int>, String?>,
    kinopoiskSeasons: List<SeasonItem>,
    profile: UserFilmProfile?,
    downloadedKeys: Set<Pair<Int, Int>>,
    resumeEpisode: Pair<Int, Int>?,
    onResumeSelected: (Pair<Int, Int>) -> Unit,
    sourceStates: Map<String, MovieSourceLoadState>,
    visibleSources: List<String>,
    onRetrySource: (String) -> Unit,
    onEpisodeSelected: (Pair<Int, Int>) -> Unit
) {
    var isSortAscending by remember { mutableStateOf(true) }
    val sortedEpisodes = remember(episodes, isSortAscending) {
        val sorted = episodes.sortedWith(compareBy({ it.season }, { it.number }))
        if (isSortAscending) sorted else sorted.asReversed()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (seasons.size > 1) {
            item {
                Text(
                    "Сезон:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    items(seasons.size) { index ->
                        val season = seasons[index]
                        FilterChip(
                            selected = season == selectedSeason,
                            onClick = { onSeasonSelected(season) },
                            label = { Text("Сезон $season") }
                        )
                    }
                }
            }
        }
        if (resumeEpisode != null) {
            item(key = "resume-suggestion") {
                Surface(
                    onClick = { onResumeSelected(resumeEpisode) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Продолжить", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "Сезон ${resumeEpisode.first}, серия ${resumeEpisode.second}",
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                val failed = visibleSources.filter { sourceStates[it] is MovieSourceLoadState.Failed }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (failed.isEmpty()) "Серии пока загружаются…" else "Часть источников недоступна",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    failed.forEach { id ->
                        TextButton(onClick = { onRetrySource(id) }) {
                            Text("Повторить: ${PlaybackSources.displayName(id)}")
                        }
                    }
                }
            }
        } else {
            items(
                count = sortedEpisodes.size,
                key = { index -> "ep:${sortedEpisodes[index].season}:${sortedEpisodes[index].number}:$index" }
            ) { index ->
                val ep = sortedEpisodes[index]
                val key = seasonEpisodeKey(ep.season, ep.number)
                val watched = isEpisodeWatched(profile, ep.season, ep.number)
                val kpTitle = kinopoiskSeasons.firstOrNull { it.number == ep.season }
                    ?.episodes?.firstOrNull { it.episodeNumber == ep.number }
                    ?.let { it.nameRu?.takeIf { n -> n.isNotBlank() } ?: it.nameEn }
                val title = kpTitle?.takeIf { it.isNotBlank() }
                    ?: ep.title?.takeIf { hasRealTitle(it, ep.season, ep.number) }
                Surface(
                    onClick = { onEpisodeSelected(key) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(32.dp).clip(CircleShape)
                                .background(
                                    if (watched) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (watched) Icons.Filled.Check else Icons.Filled.PlayArrow,
                                contentDescription = if (watched) "Просмотрено" else null,
                                tint = if (watched) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (seasons.size > 1) "С${ep.season} • Серия ${ep.number}" else "Серия ${ep.number}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                )
                                val dubCount = dubCounts[key] ?: 0
                                if (dubCount > 1) {
                                    Surface(
                                        modifier = Modifier.padding(start = 8.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                    ) {
                                        Text(
                                            text = "$dubCount озв.",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                val badge = qualityBadgeLabel(bestQualities[key])
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
                            if (!title.isNullOrBlank()) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (key in downloadedKeys) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = "Скачано",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isEpisodeWatched(profile: UserFilmProfile?, season: Int, number: Int): Boolean {
    if (profile == null || profile.status == UserFilmStatus.COMPLETED) return false
    val wS = profile.watchedSeasons ?: return false
    val wE = profile.watchedEpisodes ?: return false
    return season < wS || (season == wS && number <= wE)
}

/** Маленький бейдж «N озв.» / качества в строку плитки. */
@Composable
private fun TileBadge(text: String) {
    Surface(
        modifier = Modifier.padding(start = 8.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MovieDubStep(
    dubs: List<MovieDubGroup>,
    selectedEpisodeKey: Pair<Int, Int>?,
    downloadedCountFor: (MovieDubGroup) -> Int,
    onDownloadDub: ((MovieDubGroup) -> Unit)?,
    onDubSelected: (MovieDubGroup) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                "Выберите вариант озвучки:" + (selectedEpisodeKey?.let { " (С${it.first} Е${it.second})" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        if (dubs.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Озвучки пока загружаются…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(dubs, key = { it.key }) { dub ->
                val downloaded = downloadedCountFor(dub)
                Surface(
                    onClick = { onDubSelected(dub) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    dub.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                dub.qualityBadge?.let { TileBadge(it) }
                            }
                            Text(
                                buildString {
                                    append(if (dub.episodeCount > 1) "${dub.episodeCount} серий" else "Фильм")
                                    append(" • ${PlaybackSources.sourcesCountLabel(dub.sourcesCount)}")
                                    if (downloaded > 0) append(" • скачано: $downloaded")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (downloaded > 0) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = "Скачано",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        if (onDownloadDub != null) {
                            IconButton(
                                onClick = { onDownloadDub(dub) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Скачать озвучку",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieSourceStep(
    options: List<MoviePickerOption>,
    selectedEpisodeKey: Pair<Int, Int>?,
    onDownloadSingle: ((MoviePickerOption) -> Unit)?,
    onSourceSelected: (MoviePickerOption) -> Unit,
    sourceIcon: @Composable (sourceId: String, size: Dp) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                "Выберите источник:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        if (options.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Нет доступных источников",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(options, key = { it.sourceId + "|" + it.translationId }) { opt ->
                val info = PlaybackSources.info(opt.sourceId)
                val ep = selectedEpisodeKey?.let { key ->
                    opt.episodes.firstOrNull { seasonEpisodeKey(it.season, it.number) == key }
                } ?: opt.episodes.firstOrNull()
                val badge = qualityBadgeLabel(
                    ep?.ladder?.keys?.maxByOrNull { q -> q.removeSuffix("p").toIntOrNull() ?: -1 }
                )
                Surface(
                    onClick = { onSourceSelected(opt) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp).clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            sourceIcon(opt.sourceId, 42.dp)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    info?.displayName ?: opt.sourceId,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (info?.needsVpn == true) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)) {
                                        Text(
                                            "VPN",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                if (badge != null) TileBadge(badge)
                            }
                            Text(
                                text = info?.description ?: opt.dubTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onDownloadSingle != null) {
                            IconButton(
                                onClick = { onDownloadSingle(opt) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Скачать",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
