package hd.kinoshka.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import hd.kinoshka.app.data.model.PlaybackSequenceOption
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSource
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.AnimeTranslation
import hd.kinoshka.app.data.source.AnimeStreamResolver
import kotlinx.coroutines.launch

enum class SelectionStep {
    SOURCE,
    TRANSLATION,
    EPISODE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimePlaybackSelectionSheet(
    shikimoriId: Int,
    animeTitle: String,
    playbackSequence: PlaybackSequenceOption = PlaybackSequenceOption.SOURCES_FIRST,
    onDismissRequest: () -> Unit,
    onStreamSelected: (stream: AnimeMediaStream, episodeNumber: Int, episodeTitle: String, source: AnimeSourceType, translationTitle: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val currentStep = remember(currentStepIndex, playbackSequence) {
        playbackSequence.steps.getOrNull(currentStepIndex) ?: SelectionStep.SOURCE
    }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var sources by remember { mutableStateOf<List<AnimeSource>>(emptyList()) }
    var selectedSource by remember { mutableStateOf<AnimeSource?>(null) }

    var translations by remember { mutableStateOf<List<AnimeTranslation>>(emptyList()) }
    var selectedTranslation by remember { mutableStateOf<AnimeTranslation?>(null) }

    var episodes by remember { mutableStateOf<List<AnimeEpisode>>(emptyList()) }
    var selectedEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }

    var isResolvingStream by remember { mutableStateOf(false) }

    // Load everything needed
    LaunchedEffect(shikimoriId) {
        isLoading = true
        errorMessage = null
        try {
            sources = AnimeStreamResolver.fetchAvailableSources(shikimoriId, animeTitle)
            isLoading = false
        } catch (e: Exception) {
            errorMessage = "Ошибка загрузки: ${e.localizedMessage}"
            isLoading = false
        }
    }

    // Navigation and selection logic
    fun nextStep() {
        if (currentStepIndex < playbackSequence.steps.lastIndex) {
            currentStepIndex++
        }
    }

    fun onSelectSource(source: AnimeSource) {
        if (!source.isAvailable) return
        selectedSource = source
        isLoading = true
        scope.launch {
            try {
                val list = AnimeStreamResolver.fetchTranslations(shikimoriId, animeTitle, source.type)
                translations = list
                isLoading = false
                nextStep()
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
                isLoading = false
            }
        }
    }

    fun onSelectTranslation(tr: AnimeTranslation) {
        selectedTranslation = tr
        isLoading = true
        scope.launch {
            try {
                val src = selectedSource?.type ?: AnimeSourceType.KODIK
                episodes = AnimeStreamResolver.fetchEpisodes(shikimoriId, animeTitle, src, tr.id)
                isLoading = false
                nextStep()
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
                isLoading = false
            }
        }
    }

    fun onSelectEpisode(ep: AnimeEpisode) {
        selectedEpisode = ep
        val src = selectedSource?.type
        val tr = selectedTranslation
        
        if (src != null && tr != null) {
            isResolvingStream = true
            scope.launch {
                try {
                    val stream = AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, src, tr.id, ep.number)
                    isResolvingStream = false
                    if (stream != null) {
                        onStreamSelected(stream, ep.number, ep.title ?: "Серия ${ep.number}", src, tr.title)
                        onDismissRequest()
                    }
                } catch (e: Exception) {
                    isResolvingStream = false
                    errorMessage = e.localizedMessage
                }
            }
        } else {
            nextStep()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentStepIndex > 0) {
                        IconButton(
                            onClick = {
                                currentStepIndex--
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                    Column(modifier = Modifier.padding(start = if (currentStepIndex > 0) 0.dp else 8.dp)) {
                        Text(
                            text = animeTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when (currentStep) {
                                SelectionStep.SOURCE -> "Выбор источника"
                                SelectionStep.TRANSLATION -> "Выбор озвучки"
                                SelectionStep.EPISODE -> "Выбор серии"
                                else -> "Выбор"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isResolvingStream) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Подготовка видеопотока...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            currentStepIndex = 0
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                sources = AnimeStreamResolver.fetchAvailableSources(shikimoriId)
                                isLoading = false
                            }
                        }) {
                            Text("Повторить")
                        }
                    }
                }
            } else {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "stepAnimation"
                ) { step ->
                    when (step) {
                        SelectionStep.SOURCE -> StepSourceContent(sources = sources, onSelect = ::onSelectSource)
                        SelectionStep.TRANSLATION -> {
                            if (translations.isEmpty()) {
                                EmptyStepContent(
                                    message = "Переводы не найдены",
                                    retryLabel = "Повторить",
                                    onRetry = {
                                        selectedSource?.let { onSelectSource(it) }
                                    }
                                )
                            } else {
                                StepTranslationContent(translations = translations, onSelect = ::onSelectTranslation)
                            }
                        }
                        SelectionStep.EPISODE -> {
                            if (episodes.isEmpty()) {
                                EmptyStepContent(
                                    message = "Серии не найдены",
                                    retryLabel = "Повторить",
                                    onRetry = {
                                        selectedTranslation?.let { onSelectTranslation(it) }
                                    }
                                )
                            } else {
                                StepEpisodeContent(episodes = episodes, onSelect = ::onSelectEpisode)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepSourceContent(
    sources: List<AnimeSource>,
    onSelect: (AnimeSource) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Доступные плееры и базы:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sources) { src ->
                Surface(
                    onClick = { onSelect(src) },
                    enabled = src.isAvailable,
                    shape = RoundedCornerShape(16.dp),
                    color = if (src.isAvailable) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                    border = borderForSelected(false),
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
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(src.type.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(src.type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTranslationContent(
    translations: List<AnimeTranslation>,
    onSelect: (AnimeTranslation) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Выберите вариант перевода / озвучки:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(translations) { tr ->
                Surface(
                    onClick = { onSelect(tr) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (tr.episodesCount > 0) {
                                Text("${tr.episodesCount} серий", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepEpisodeContent(
    episodes: List<AnimeEpisode>,
    onSelect: (AnimeEpisode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Выберите серию:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(12.dp))
        if (episodes.isEmpty()) {
            Text("Серии не найдены", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                items(episodes) { ep ->
                    Surface(
                        onClick = { onSelect(ep) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = ep.number.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStepContent(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(retryLabel)
            }
        }
    }
}

@Composable
private fun borderForSelected(selected: Boolean) = if (selected) {
    androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
} else null
