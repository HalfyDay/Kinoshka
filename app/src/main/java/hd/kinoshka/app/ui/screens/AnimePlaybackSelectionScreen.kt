package hd.kinoshka.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.model.*
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.ui.components.ExpressiveBlobLoadingIndicator
import kotlinx.coroutines.launch

@Composable
fun AnimePlaybackSelectionScreen(
    shikimoriId: Int,
    animeTitle: String,
    watchedEpisodes: Int,
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
    onSaveWatchedEpisode: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allTranslations by remember { mutableStateOf<List<FlatTranslation>>(emptyList()) }

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val steps = playbackSequence.steps

    var selectedEpisodeNumber by remember { mutableStateOf<Int?>(null) }
    var selectedTranslation by remember { mutableStateOf<FlatTranslation?>(null) }
    var selectedSource by remember { mutableStateOf<AnimeSourceType?>(null) }

    var isResolvingStream by remember { mutableStateOf(false) }

    // Load available media on start
    LaunchedEffect(shikimoriId) {
        isLoading = true
        errorMessage = null
        try {
            allTranslations = AnimeStreamResolver.prefetchAllMedia(shikimoriId, animeTitle)
            if (allTranslations.isEmpty()) {
                errorMessage = "Не удалось найти источники для данного аниме."
            }
            isLoading = false
            // Auto-select step 0 if it has only one option
            checkAndAutoSelect(
                step = steps[0],
                allTranslations = allTranslations,
                selectedEpisode = selectedEpisodeNumber,
                selectedTranslation = selectedTranslation,
                selectedSource = selectedSource,
                onSelectEpisode = { selectedEpisodeNumber = it },
                onSelectTranslation = {
                    selectedTranslation = it
                    selectedSource = it.source
                },
                onSelectSource = { selectedSource = it },
                onAdvance = {
                    if (currentStepIndex < steps.lastIndex) {
                        currentStepIndex++
                    }
                }
            )
        } catch (e: Exception) {
            errorMessage = "Ошибка предзагрузки источников: ${e.localizedMessage}"
            isLoading = false
        }
    }

    // Helper to resolve and play
    fun resolveAndPlay(epNum: Int, tr: FlatTranslation, src: AnimeSourceType) {
        isResolvingStream = true
        errorMessage = null
        scope.launch {
            try {
                val stream = AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, src, tr.translationId, epNum)
                isResolvingStream = false
                if (stream != null) {
                    onSaveWatchedEpisode(epNum)
                    onStreamSelected(stream, epNum, "Серия $epNum", src, tr.title, tr.episodes, allTranslations, tr.translationId)
                    onDismissRequest()
                } else {
                    errorMessage = "Не удалось получить видеопоток для серии $epNum"
                }
            } catch (e: Exception) {
                isResolvingStream = false
                errorMessage = "Ошибка воспроизведения: ${e.localizedMessage}"
            }
        }
    }

    // Handle back press within steps
    fun handleBackStep() {
        if (currentStepIndex > 0) {
            // Clear current step's selection and step back
            currentStepIndex--
            val prevStep = steps[currentStepIndex]
            when (prevStep) {
                SelectionStep.EPISODE -> selectedEpisodeNumber = null
                SelectionStep.TRANSLATION -> {
                    selectedTranslation = null
                    selectedSource = null
                }
                SelectionStep.SOURCE -> selectedSource = null
            }
        } else {
            onDismissRequest()
        }
    }

    BackHandler {
        handleBackStep()
    }

    // Advance step helper
    fun onOptionSelected() {
        // Check if we are done with all steps
        val ep = selectedEpisodeNumber
        val tr = selectedTranslation
        val src = selectedSource

        if (ep != null && tr != null && src != null) {
            resolveAndPlay(ep, tr, src)
        } else if (currentStepIndex < steps.lastIndex) {
            currentStepIndex++
            // Check next step auto-selection
            val nextStep = steps[currentStepIndex]
            checkAndAutoSelect(
                step = nextStep,
                allTranslations = allTranslations,
                selectedEpisode = selectedEpisodeNumber,
                selectedTranslation = selectedTranslation,
                selectedSource = selectedSource,
                onSelectEpisode = { selectedEpisodeNumber = it },
                onSelectTranslation = {
                    selectedTranslation = it
                    selectedSource = it.source
                },
                onSelectSource = { selectedSource = it },
                onAdvance = {
                    onOptionSelected()
                }
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = ::handleBackStep) {
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
                    if (!isLoading && errorMessage == null && !isResolvingStream) {
                        Text(
                            text = "Шаг ${currentStepIndex + 1} из ${steps.size}: ${
                                when (steps[currentStepIndex]) {
                                    SelectionStep.EPISODE -> "Выбор серии"
                                    SelectionStep.TRANSLATION -> "Выбор озвучки / субтитров"
                                    SelectionStep.SOURCE -> "Выбор источника"
                                }
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body
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
                            Text("Подготовка видеопотока...", style = MaterialTheme.typography.bodyMedium)
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
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = {
                                isLoading = true
                                errorMessage = null
                                currentStepIndex = 0
                                selectedEpisodeNumber = null
                                selectedTranslation = null
                                selectedSource = null
                                scope.launch {
                                    try {
                                        allTranslations = AnimeStreamResolver.prefetchAllMedia(shikimoriId, animeTitle)
                                        if (allTranslations.isEmpty()) {
                                            errorMessage = "Не удалось найти источники для данного аниме."
                                        }
                                        isLoading = false
                                    } catch (e: Exception) {
                                        errorMessage = e.localizedMessage
                                        isLoading = false
                                    }
                                }
                            }) {
                                Text("Повторить")
                            }
                        }
                    }
                    else -> {
                        AnimatedContent(
                            targetState = steps[currentStepIndex],
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "playbackStepAnimation"
                        ) { step ->
                            when (step) {
                                SelectionStep.EPISODE -> {
                                    // Render episodes list
                                    val availableEpisodes = remember(selectedTranslation, selectedSource) {
                                        getAvailableEpisodes(allTranslations, selectedTranslation, selectedSource)
                                    }
                                    EpisodeSelectionContent(
                                        episodes = availableEpisodes,
                                        watchedEpisodes = watchedEpisodes,
                                        onSelect = { epNum ->
                                            selectedEpisodeNumber = epNum
                                            onOptionSelected()
                                        }
                                    )
                                }
                                SelectionStep.TRANSLATION -> {
                                    // Render voice acting / subtitles tabs
                                    val availableTranslations = remember(selectedEpisodeNumber, selectedSource) {
                                        getAvailableTranslations(allTranslations, selectedEpisodeNumber, selectedSource)
                                    }
                                    TranslationSelectionContent(
                                        translations = availableTranslations,
                                        onSelect = { tr ->
                                            selectedTranslation = tr
                                            selectedSource = tr.source
                                            onOptionSelected()
                                        }
                                    )
                                }
                                SelectionStep.SOURCE -> {
                                    // Render sources list
                                    val availableSources = remember(selectedEpisodeNumber, selectedTranslation) {
                                        getAvailableSources(allTranslations, selectedEpisodeNumber, selectedTranslation)
                                    }
                                    SourceSelectionContent(
                                        sources = availableSources,
                                        onSelect = { src ->
                                            selectedSource = src
                                            onOptionSelected()
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

@Composable
private fun EpisodeSelectionContent(
    episodes: List<Int>,
    watchedEpisodes: Int,
    onSelect: (Int) -> Unit
) {
    if (episodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Список серий пуст", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(episodes) { epNum ->
                val isWatched = epNum <= watchedEpisodes
                Surface(
                    onClick = { onSelect(epNum) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isWatched) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow,
                            contentDescription = if (isWatched) "Просмотрено" else "Воспроизвести",
                            tint = if (isWatched) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Серия $epNum",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (isWatched) {
                            Text(
                                text = "Просмотрено",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationSelectionContent(
    translations: List<FlatTranslation>,
    onSelect: (FlatTranslation) -> Unit
) {
    val voiceTranslations = remember(translations) {
        translations.filter { it.type != "sub" && it.type != "subtitles" }
    }
    val subTranslations = remember(translations) {
        translations.filter { it.type == "sub" || it.type == "subtitles" }
    }

    var selectedTabIndex by remember { mutableIntStateOf(if (voiceTranslations.isNotEmpty()) 0 else 1) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab row styled similar to library tabs
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                if (selectedTabIndex < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            divider = {}
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text("Озвучка", fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Medium) }
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text("Субтитры", fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Medium) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val currentList = if (selectedTabIndex == 0) voiceTranslations else subTranslations

        if (currentList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (selectedTabIndex == 0) "Озвучки не найдены" else "Субтитры не найдены",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(currentList) { tr ->
                    Surface(
                        onClick = { onSelect(tr) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Источник: ${tr.source.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (tr.episodes.isNotEmpty()) {
                                Text(
                                    text = "${tr.episodes.size} эп.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun SourceSelectionContent(
    sources: List<AnimeSourceType>,
    onSelect: (AnimeSourceType) -> Unit
) {
    if (sources.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Источники не найдены", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sources) { src ->
                Surface(
                    onClick = { onSelect(src) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = src.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = src.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// Filtering Helpers
private fun getAvailableEpisodes(
    allTranslations: List<FlatTranslation>,
    selectedTranslation: FlatTranslation?,
    selectedSource: AnimeSourceType?
): List<Int> {
    val sourceList = when {
        selectedTranslation != null -> listOf(selectedTranslation)
        selectedSource != null -> allTranslations.filter { it.source == selectedSource }
        else -> allTranslations
    }
    return sourceList.flatMap { it.episodes.map { ep -> ep.number } }.distinct().sorted()
}

private fun getAvailableTranslations(
    allTranslations: List<FlatTranslation>,
    selectedEpisodeNumber: Int?,
    selectedSource: AnimeSourceType?
): List<FlatTranslation> {
    return allTranslations.filter { tr ->
        (selectedEpisodeNumber == null || tr.episodes.any { ep -> ep.number == selectedEpisodeNumber }) &&
                (selectedSource == null || tr.source == selectedSource)
    }
}

private fun getAvailableSources(
    allTranslations: List<FlatTranslation>,
    selectedEpisodeNumber: Int?,
    selectedTranslation: FlatTranslation?
): List<AnimeSourceType> {
    val sourceList = when {
        selectedTranslation != null -> listOf(selectedTranslation)
        selectedEpisodeNumber != null -> allTranslations.filter { tr -> tr.episodes.any { ep -> ep.number == selectedEpisodeNumber } }
        else -> allTranslations
    }
    return sourceList.map { it.source }.distinct()
}

// Auto selection helper
private fun checkAndAutoSelect(
    step: SelectionStep,
    allTranslations: List<FlatTranslation>,
    selectedEpisode: Int?,
    selectedTranslation: FlatTranslation?,
    selectedSource: AnimeSourceType?,
    onSelectEpisode: (Int) -> Unit,
    onSelectTranslation: (FlatTranslation) -> Unit,
    onSelectSource: (AnimeSourceType) -> Unit,
    onAdvance: () -> Unit
) {
    when (step) {
        SelectionStep.EPISODE -> {
            if (selectedEpisode == null) {
                val available = getAvailableEpisodes(allTranslations, selectedTranslation, selectedSource)
                if (available.size == 1) {
                    onSelectEpisode(available.first())
                    onAdvance()
                }
            }
        }
        SelectionStep.TRANSLATION -> {
            if (selectedTranslation == null) {
                val available = getAvailableTranslations(allTranslations, selectedEpisode, selectedSource)
                if (available.size == 1) {
                    onSelectTranslation(available.first())
                    onAdvance()
                }
            }
        }
        SelectionStep.SOURCE -> {
            if (selectedSource == null) {
                val available = getAvailableSources(allTranslations, selectedEpisode, selectedTranslation)
                if (available.size == 1) {
                    onSelectSource(available.first())
                    onAdvance()
                }
            }
        }
    }
}
