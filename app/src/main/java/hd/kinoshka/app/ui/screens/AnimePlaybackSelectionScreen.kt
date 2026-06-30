package hd.kinoshka.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.*
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.ui.components.ExpressiveBlobLoadingIndicator
import kotlinx.coroutines.launch

private enum class SelectionState {
    SELECT_SOURCE,
    SELECT_TRANSLATION,
    SELECT_EPISODE
}

private enum class FilterMode {
    ALL,
    VOICE,
    SUBTITLES
}

private data class LastPlaybackInfo(
    val source: String,
    val translationId: String,
    val translationTitle: String,
    val episodeNum: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimePlaybackSelectionScreen(
    shikimoriId: Int,
    animeTitle: String,
    watchedEpisodes: Int,
    playbackSequence: PlaybackSequenceOption, // Ignored, kept for signature compatibility
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
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userStateStore = remember { UserStateStore(context) }

    var currentSelectionState by remember { mutableStateOf(SelectionState.SELECT_SOURCE) }
    var selectedSourceType by remember { mutableStateOf<AnimeSourceType?>(null) }
    var selectedTranslation by remember { mutableStateOf<FlatTranslation?>(null) }

    var isLoading by remember { mutableStateOf(true) }
    var isResolvingStream by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var allTranslations by remember { mutableStateOf<List<FlatTranslation>>(emptyList()) }
    var watchedEpisodesState by remember { mutableIntStateOf(watchedEpisodes) }

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

    // SharedPreferences to save/load last watched info
    val lastPlayback = remember(shikimoriId, allTranslations) {
        val prefs = context.getSharedPreferences("anime_playback_prefs", Context.MODE_PRIVATE)
        val src = prefs.getString("last_source_$shikimoriId", null)
        val trId = prefs.getString("last_translation_id_$shikimoriId", null)
        val trTitle = prefs.getString("last_translation_title_$shikimoriId", null)
        val epNum = prefs.getInt("last_episode_num_$shikimoriId", -1)

        if (src != null && trId != null && trTitle != null && epNum != -1) {
            LastPlaybackInfo(src, trId, trTitle, epNum)
        } else null
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
                        allTranslations.filter { it.source == source },
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
        when (currentSelectionState) {
            SelectionState.SELECT_SOURCE -> onDismissRequest()
            SelectionState.SELECT_TRANSLATION -> {
                currentSelectionState = SelectionState.SELECT_SOURCE
                selectedSourceType = null
            }
            SelectionState.SELECT_EPISODE -> {
                if (selectedSourceType == AnimeSourceType.KODIK || selectedSourceType == AnimeSourceType.ANILIB) {
                    currentSelectionState = SelectionState.SELECT_TRANSLATION
                    selectedTranslation = null
                } else {
                    currentSelectionState = SelectionState.SELECT_SOURCE
                    selectedSourceType = null
                }
            }
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
                        val subtitleText = when (currentSelectionState) {
                            SelectionState.SELECT_SOURCE -> "Выбор источника видео"
                            SelectionState.SELECT_TRANSLATION -> "Выбор озвучки • ${selectedSourceType?.displayName}"
                            SelectionState.SELECT_EPISODE -> "${selectedTranslation?.title} • ${selectedSourceType?.displayName}"
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
                                        currentSelectionState = SelectionState.SELECT_SOURCE
                                        selectedSourceType = null
                                        selectedTranslation = null
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
                            }
                        }
                        else -> {
                            AnimatedContent(
                                targetState = currentSelectionState,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "watchStateAnimation"
                            ) { state ->
                                when (state) {
                                    SelectionState.SELECT_SOURCE -> {
                                        SelectSourceStep(
                                            allTranslations = allTranslations,
                                            lastPlayback = lastPlayback,
                                            onSourceSelected = { src ->
                                                selectedSourceType = src
                                                // If AniLiberty has only one translation, jump straight to episode selection
                                                if (src == AnimeSourceType.ANILIBERTY) {
                                                    val tr = allTranslations.firstOrNull { it.source == src }
                                                    if (tr != null) {
                                                        selectedTranslation = tr
                                                        currentSelectionState = SelectionState.SELECT_EPISODE
                                                    } else {
                                                        Toast.makeText(context, "Раздел пуст", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    currentSelectionState = SelectionState.SELECT_TRANSLATION
                                                }
                                            },
                                            onQuickContinue = { playbackInfo ->
                                                val tr = allTranslations.firstOrNull {
                                                    it.source.name == playbackInfo.source &&
                                                    (it.translationId == playbackInfo.translationId || it.title == playbackInfo.translationTitle)
                                                }
                                                val ep = tr?.episodes?.firstOrNull { it.number == playbackInfo.episodeNum }
                                                if (tr != null && ep != null) {
                                                    resolveAndPlay(ep, tr, tr.source)
                                                } else {
                                                    Toast.makeText(context, "Не удалось продолжить воспроизведение", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                    SelectionState.SELECT_TRANSLATION -> {
                                        SelectTranslationStep(
                                            selectedSource = selectedSourceType ?: AnimeSourceType.KODIK,
                                            allTranslations = allTranslations,
                                            onTranslationSelected = { tr ->
                                                selectedTranslation = tr
                                                currentSelectionState = SelectionState.SELECT_EPISODE
                                            }
                                        )
                                    }
                                    SelectionState.SELECT_EPISODE -> {
                                        SelectEpisodeStep(
                                            shikimoriId = shikimoriId,
                                            animeTitle = animeTitle,
                                            translation = selectedTranslation ?: FlatTranslation(
                                                source = AnimeSourceType.KODIK,
                                                translationId = "",
                                                title = "",
                                                type = "voice"
                                            ),
                                            watchedEpisodes = watchedEpisodesState,
                                            userStateStore = userStateStore,
                                            onEpisodeSelected = { ep ->
                                                resolveAndPlay(ep, selectedTranslation ?: return@SelectEpisodeStep, selectedSourceType ?: return@SelectEpisodeStep)
                                            },
                                            onWatchedEpisodesChanged = { newCount ->
                                                watchedEpisodesState = newCount
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Quick Continue watched history card if available
        if (lastPlayback != null) {
            val matchingTr = allTranslations.firstOrNull {
                it.source.name == lastPlayback.source &&
                (it.translationId == lastPlayback.translationId || it.title == lastPlayback.translationTitle)
            }
            if (matchingTr != null) {
                item {
                    Text(
                        text = "История просмотра",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        onClick = { onQuickContinue(lastPlayback) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Продолжить просмотр",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Серия ${lastPlayback.episodeNum} • ${lastPlayback.translationTitle} (${matchingTr.source.displayName})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Доступные источники",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        items(AnimeSourceType.values()) { src ->
            val count = allTranslations.count { it.source == src }
            val isEnabled = count > 0

            Surface(
                onClick = { if (isEnabled) onSourceSelected(src) },
                shape = RoundedCornerShape(16.dp),
                color = if (isEnabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (isEnabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp)
                    ),
                enabled = isEnabled
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (isEnabled) MaterialTheme.colorScheme.secondaryContainer 
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when (src) {
                            AnimeSourceType.KODIK -> Icons.Default.PlayArrow
                            AnimeSourceType.ANILIBERTY -> Icons.Default.Star
                            AnimeSourceType.ANILIB -> Icons.Default.Favorite
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = src.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            text = if (isEnabled) src.description else "Ничего не найдено",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    if (isEnabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val detailText = when (src) {
                                AnimeSourceType.KODIK -> "$count озв."
                                AnimeSourceType.ANILIBERTY -> {
                                    val episodesCount = allTranslations.firstOrNull { it.source == src }?.episodes?.size ?: 0
                                    "$episodesCount сер."
                                }
                                AnimeSourceType.ANILIB -> "$count озв."
                            }
                            Text(
                                text = detailText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectTranslationStep(
    selectedSource: AnimeSourceType,
    allTranslations: List<FlatTranslation>,
    onTranslationSelected: (FlatTranslation) -> Unit
) {
    val sourceTranslations = remember(allTranslations, selectedSource) {
        allTranslations.filter { it.source == selectedSource }
    }

    var filterMode by remember { mutableStateOf(FilterMode.ALL) }

    val filteredList = remember(sourceTranslations, filterMode) {
        when (filterMode) {
            FilterMode.ALL -> sourceTranslations
            FilterMode.VOICE -> sourceTranslations.filter { it.type != "sub" && it.type != "subtitles" }
            FilterMode.SUBTITLES -> sourceTranslations.filter { it.type == "sub" || it.type == "subtitles" }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Translation tabs chips (only for sources with lots of translators, e.g. Kodik)
        if (selectedSource == AnimeSourceType.KODIK) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterMode == FilterMode.ALL,
                    onClick = { filterMode = FilterMode.ALL },
                    label = { Text("Все") }
                )
                FilterChip(
                    selected = filterMode == FilterMode.VOICE,
                    onClick = { filterMode = FilterMode.VOICE },
                    label = { Text("Озвучка") }
                )
                FilterChip(
                    selected = filterMode == FilterMode.SUBTITLES,
                    onClick = { filterMode = FilterMode.SUBTITLES },
                    label = { Text("Субтитры") }
                )
            }
        }

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нет доступных вариантов перевода",
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
                items(filteredList) { tr ->
                    val isSub = tr.type == "sub" || tr.type == "subtitles"
                    Surface(
                        onClick = { onTranslationSelected(tr) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(16.dp)
                            )
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
                                    text = tr.title,
                                    style = MaterialTheme.typography.titleMedium,
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
    shikimoriId: Int,
    animeTitle: String,
    translation: FlatTranslation,
    watchedEpisodes: Int,
    userStateStore: UserStateStore,
    onEpisodeSelected: (AnimeEpisode) -> Unit,
    onWatchedEpisodesChanged: (Int) -> Unit
) {
    var isSortAscending by remember { mutableStateOf(true) }

    val sortedEpisodes = remember(translation, isSortAscending) {
        val eps = translation.episodes
        if (isSortAscending) eps.sortedBy { it.number } else eps.sortedByDescending { it.number }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Actions row (Sort options)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Всего серий: ${translation.episodes.size}",
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

        if (sortedEpisodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Список серий пуст", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedEpisodes) { ep ->
                    val isWatched = ep.number <= watchedEpisodes

                    Surface(
                        onClick = { onEpisodeSelected(ep) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 0.5.dp,
                                color = if (isWatched) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isWatched) Color(0xFF4CAF50).copy(alpha = 0.15f) 
                                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isWatched) Icons.Filled.Check else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isWatched) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Серия ${ep.number}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isWatched) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface
                                )
                                if (!ep.title.isNullOrBlank() && ep.title != "Серия ${ep.number}") {
                                    Text(
                                        text = ep.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Quick progress manager buttons
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isWatched) {
                                    // Watched status indicator + minus/delete progress button
                                    Text(
                                        text = "Просмотрено",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = {
                                            val newCount = ep.number - 1
                                            onWatchedEpisodesChanged(newCount)
                                            userStateStore.updateWatchedEpisode(
                                                shikimoriId,
                                                animeTitle,
                                                newCount,
                                                translation.episodes.size
                                            )
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Удалить отметку",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    // Mark as watched button (plus icon / check circle outline)
                                    IconButton(
                                        onClick = {
                                            val newCount = ep.number
                                            onWatchedEpisodesChanged(newCount)
                                            userStateStore.updateWatchedEpisode(
                                                shikimoriId,
                                                animeTitle,
                                                newCount,
                                                translation.episodes.size
                                            )
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = "Отметить просмотренной",
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                            modifier = Modifier.size(20.dp)
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
