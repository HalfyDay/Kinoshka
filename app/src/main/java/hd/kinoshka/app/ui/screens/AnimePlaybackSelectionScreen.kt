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
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.ui.components.ExpressiveBlobLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private enum class FilterMode {
    VOICE,
    SUBTITLES
}

private data class LastPlaybackInfo(
    val source: String,
    val translationId: String,
    val translationTitle: String,
    val episodeNum: Int
)

/** True if the episode carries a real (non-synthetic) title, e.g. from AniLiberty. */
private fun hasRealTitle(ep: AnimeEpisode): Boolean {
    val t = ep.title ?: return false
    if (t.isBlank()) return false
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimePlaybackSelectionScreen(
    shikimoriId: Int,
    animeTitle: String,
    playbackSequence: PlaybackSequenceOption,
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

    var isLoading by remember { mutableStateOf(true) }
    var isResolvingStream by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var allTranslations by remember { mutableStateOf<List<FlatTranslation>>(emptyList()) }

    // Load available media on start
    LaunchedEffect(shikimoriId) {
        isLoading = true
        errorMessage = null
        try {
            allTranslations = AnimeStreamResolver.prefetchAllMedia(shikimoriId, animeTitle)
            if (allTranslations.isEmpty()) {
                errorMessage = "Не удалось найти видео для этого аниме."
            }
            isLoading = false
        } catch (e: Exception) {
            errorMessage = "Ошибка при загрузке: ${e.localizedMessage}"
            isLoading = false
        }
    }

    // Pre-compute derived data once when translations load
    val episodeTranslationCountMap = remember(allTranslations) {
        buildMap {
            for (tr in allTranslations) {
                for (ep in tr.episodes) {
                    merge(ep.number, 1, Int::plus)
                }
            }
        }
    }

    val mergedEpisodes = remember(allTranslations) {
        // Prefer a real episode title over the synthetic "Серия N" that Kodik emits. Kodik is
        // awaited/added before AniLiberty, so plain distinctBy{number} kept Kodik's synthetic
        // title and dropped AniLiberty's real name — which then got suppressed by the UI guard,
        // so the merged view showed no titles at all. Keep the entry with a real title per number.
        allTranslations
            .flatMap { it.episodes }
            .groupBy { it.number }
            .map { (_, eps) -> eps.firstOrNull { hasRealTitle(it) } ?: eps.first() }
            .sortedBy { it.number }
    }

    val mergedEpisodesBySource = remember(allTranslations) {
        allTranslations
            .groupBy { it.source }
            .mapValues { (_, translations) ->
                translations
                    .flatMap { it.episodes }
                    .groupBy { it.number }
                    .map { (_, eps) -> eps.firstOrNull { hasRealTitle(it) } ?: eps.first() }
                    .sortedBy { it.number }
            }
    }

    // SharedPreferences to save/load last watched info (async to avoid blocking composition)
    var lastPlayback by remember { mutableStateOf<LastPlaybackInfo?>(null) }
    // High-water-mark watched-episode count (per-anime), used to mark watched episodes in the
    // picker. Sourced from UserStateStore profiles (written by PlayerActivity on watched threshold).
    var watchedEpisodes by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(shikimoriId) {
        watchedEpisodes = withContext(Dispatchers.IO) {
            hd.kinoshka.app.data.local.UserStateStore(context)
                .getProfile(shikimoriId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
                ?.watchedEpisodes
        }
    }
    LaunchedEffect(shikimoriId, allTranslations) {
        lastPlayback = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("anime_playback_prefs", Context.MODE_PRIVATE)
            val src = prefs.getString("last_source_$shikimoriId", null)
            val trId = prefs.getString("last_translation_id_$shikimoriId", null)
            val trTitle = prefs.getString("last_translation_title_$shikimoriId", null)
            val epNum = prefs.getInt("last_episode_num_$shikimoriId", -1)
            if (src != null && trId != null && trTitle != null && epNum != -1) {
                LastPlaybackInfo(src, trId, trTitle, epNum)
            } else null
        }
    }

    // Helper to resolve HLS stream and launch player
    fun resolveAndPlay(episode: AnimeEpisode, translation: FlatTranslation, source: AnimeSourceType) {
        isResolvingStream = true
        errorMessage = null
        scope.launch {
            try {
                val stream = AnimeStreamResolver.resolveStream(
                    shikimoriId,
                    animeTitle,
                    source,
                    translation.translationId,
                    episode.number
                )
                isResolvingStream = false
                if (stream != null) {
                    // Save last watched position
                    context.getSharedPreferences("anime_playback_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_source_$shikimoriId", source.name)
                        .putString("last_translation_id_$shikimoriId", translation.translationId)
                        .putString("last_translation_title_$shikimoriId", translation.title)
                        .putInt("last_episode_num_$shikimoriId", episode.number)
                        .apply()

                    onStreamSelected(
                        stream,
                        episode.number,
                        episode.title ?: "Серия ${episode.number}",
                        source,
                        translation.title,
                        translation.episodes,
                        allTranslations,
                        translation.translationId
                    )
                    onDismissRequest()
                } else {
                    errorMessage = "Не удалось получить ссылку на видео для серии ${episode.number}"
                }
            } catch (e: Exception) {
                isResolvingStream = false
                errorMessage = "Ошибка при запуске плеера: ${e.localizedMessage}"
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
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
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

                Divider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

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
                                ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Получение ссылки на видеопоток...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
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
                                        isLoading = true
                                        errorMessage = null
                                        currentStepIndex = 0
                                        selectedSourceType = null
                                        selectedTranslation = null
                                        selectedEpisode = null
                                        scope.launch {
                                            try {
                                                allTranslations = AnimeStreamResolver.prefetchAllMedia(shikimoriId, animeTitle)
                                                if (allTranslations.isEmpty()) {
                                                    errorMessage = "Не удалось найти видео для этого аниме."
                                                }
                                                isLoading = false
                                            } catch (e: Exception) {
                                                errorMessage = e.localizedMessage
                                                isLoading = false
                                            }
                                        }
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
                                            allTranslations = allTranslations,
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
                                            lastPlayback = if (currentStepIndex == 0) lastPlayback else null,
                                            onQuickContinue = { playbackInfo ->
                                                val tr = allTranslations.firstOrNull {
                                                    it.source.name == playbackInfo.source &&
                                                    (it.translationId == playbackInfo.translationId || it.title == playbackInfo.translationTitle)
                                                }
                                                val ep = tr?.episodes?.firstOrNull { it.number == playbackInfo.episodeNum }
                                                if (tr != null && ep != null) {
                                                    resolveAndPlay(ep, tr, tr.source)
                                                } else {
                                                    Toast.makeText(context, "Не удалось продолжить просмотр", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                    SelectionStep.TRANSLATION -> {
                                        SelectTranslationStep(
                                            selectedSource = selectedSourceType ?: filterSourceType,
                                            onSourceSelected = { selectedSourceType = it },
                                            selectedEpisode = selectedEpisode,
                                            allTranslations = allTranslations,
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
                                            }
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
                                            lastPlayback = if (currentStepIndex == 0) lastPlayback else null,
                                            watchedEpisodes = watchedEpisodes,
                                            onQuickContinue = { playbackInfo ->
                                                val tr = allTranslations.firstOrNull {
                                                    it.source.name == playbackInfo.source &&
                                                    (it.translationId == playbackInfo.translationId || it.title == playbackInfo.translationTitle)
                                                }
                                                val ep = tr?.episodes?.firstOrNull { it.number == playbackInfo.episodeNum }
                                                if (tr != null && ep != null) {
                                                    resolveAndPlay(ep, tr, tr.source)
                                                } else {
                                                    Toast.makeText(context, "Не удалось продолжить просмотр", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onEpisodeSelected = { ep ->
                                                selectedEpisode = ep
                                                if (currentStepIndex < playbackSequence.steps.lastIndex) {
                                                    currentStepIndex++
                                                } else {
                                                    val tr = selectedTranslation ?: return@SelectEpisodeStep
                                                    resolveAndPlay(ep, tr, selectedSourceType ?: tr.source)
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

@Composable
private fun SelectSourceStep(
    allTranslations: List<FlatTranslation>,
    lastPlayback: LastPlaybackInfo?,
    onSourceSelected: (AnimeSourceType) -> Unit,
    onQuickContinue: (LastPlaybackInfo) -> Unit
) {
    // This step is now merged into SelectTranslationStep
}

@Composable
private fun SelectTranslationStep(
    selectedSource: AnimeSourceType?,
    onSourceSelected: (AnimeSourceType?) -> Unit,
    selectedEpisode: AnimeEpisode?,
    allTranslations: List<FlatTranslation>,
    lastPlayback: LastPlaybackInfo? = null,
    onQuickContinue: ((LastPlaybackInfo) -> Unit)? = null,
    onTranslationSelected: (FlatTranslation) -> Unit
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
            // Source chips (previous style)
            val sources = remember(allTranslations) {
                listOf<AnimeSourceType?>(null) + AnimeSourceType.entries.filter { src ->
                    allTranslations.any { it.source == src }
                }
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

        if (filteredList.isEmpty()) {
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
        } else {
            // Group by source, then sort each group by episode count (desc) then title so the
            // most complete dubs surface first — makes every available dub/source visible rather
            // than lost in fetch order.
            val grouped = filteredList.groupBy { it.source }
                .mapValues { (_, translations) ->
                    translations.sortedWith(
                        compareByDescending<FlatTranslation> { it.episodes.size }.thenBy { it.title }
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
                items(translations, key = { it.translationId }) { tr ->
                    val isSub = tr.type == "sub" || tr.type == "subtitles"
                    Surface(
                        onClick = { onTranslationSelected(tr) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(16.dp)
                            )
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
                                    text = if (tr.source == AnimeSourceType.ANILIBERTY) "AniLiberty" else tr.title,
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
    }
}

@Composable
private fun SelectEpisodeStep(
    episodes: List<AnimeEpisode>,
    episodeTranslationCountMap: Map<Int, Int> = emptyMap(),
    lastPlayback: LastPlaybackInfo? = null,
    watchedEpisodes: Int? = null,
    onQuickContinue: ((LastPlaybackInfo) -> Unit)? = null,
    onEpisodeSelected: (AnimeEpisode) -> Unit
) {
    var isSortAscending by remember { mutableStateOf(true) }

    val sortedEpisodes = remember(episodes, isSortAscending) {
        if (isSortAscending) episodes.sortedBy { it.number } else episodes.sortedByDescending { it.number }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ... (Quick Continue block)
        // Quick Continue watched history card if available
        if (lastPlayback != null && onQuickContinue != null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "История просмотра",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        onClick = { onQuickContinue(lastPlayback) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Серия ${lastPlayback.episodeNum} • ${lastPlayback.translationTitle}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = lastPlayback.source,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
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
            items(sortedEpisodes, key = { it.number }) { ep ->
                Surface(
                    onClick = { onEpisodeSelected(ep) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(16.dp)
                        )
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
                                if (trCount > 1) {
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
                    }
                }
            }
        }
    }
}
