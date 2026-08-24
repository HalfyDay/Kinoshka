package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hd.kinoshka.app.data.model.AnimeSourceType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.domain.anime4k.applyShaderChainRuntime
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.theme.controlColor
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.FlatTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AnimeEpisodeDropdown(
    episodes: List<AnimeEpisode>,
    currentEpisode: Int?,
    hideBackground: Boolean,
    viewModel: PlayerViewModel,
    onEpisodeSelected: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val watchedEpisodesCount by viewModel.watchedEpisodesCount.collectAsState()

    LaunchedEffect(showDialog) {
        viewModel.setAnimeModalOpen(showDialog)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        border = null,
        modifier = Modifier
            .height(45.dp)
            .clickable { showDialog = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.FormatListNumbered, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = if (currentEpisode != null) "Эп. $currentEpisode" else "Серия",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDialog = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(360.dp)
                        .clickable(enabled = false) {}
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Text(
                            "Выберите серию",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(episodes) { ep ->
                                val isSelected = ep.number == currentEpisode
                                val isWatched = ep.number <= watchedEpisodesCount
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onEpisodeSelected(ep.number)
                                        showDialog = false
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ep.title ?: "Серия ${ep.number}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (ep.title != null) "Серия ${ep.number}" else "Смотреть серию",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Выбрано",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else if (isWatched) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Просмотрено",
                                                tint = Color(0xFF4CAF50),
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
}

@Composable
fun AnimeSeasonDropdown(
    seasons: List<Int>,
    currentSeason: Int?,
    hideBackground: Boolean,
    onSeasonSelected: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(50),
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        border = null,
        modifier = Modifier
            .height(45.dp)
            .clickable { showDialog = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = if (currentSeason != null) "Сезон $currentSeason" else "Сезон",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDialog = false },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clickable(enabled = false) {}
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Выберите сезон",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons) { season ->
                                val isSelected = season == currentSeason
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onSeasonSelected(season)
                                        showDialog = false
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Сезон $season",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Выбрано",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
}

@Composable
fun AnimeTranslationDropdown(
    translations: List<FlatTranslation>,
    currentTranslationId: String?,
    hideBackground: Boolean,
    viewModel: PlayerViewModel,
    onTranslationSelected: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val currentTr = translations.find { it.translationId == currentTranslationId }
    var selectedSourceFilter by remember { mutableStateOf<AnimeSourceType?>(null) }

    LaunchedEffect(showDialog) {
        viewModel.setAnimeModalOpen(showDialog)
        if (!showDialog) {
            selectedSourceFilter = null
        }
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        border = null,
        modifier = Modifier
            .height(45.dp)
            .clickable { showDialog = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = currentTr?.title ?: "Озвучка",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 100.dp)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDialog = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(360.dp)
                        .clickable(enabled = false) {}
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Text(
                            "Варианты озвучки",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Source filter row
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Источник:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            
                            val availableSources = translations.map { it.source }.distinct()
                            val filters = listOf(null) + availableSources
                            
                            filters.forEach { src ->
                                val label = src?.displayName ?: "Все"
                                val isSelected = selectedSourceFilter == src
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clickable { selectedSourceFilter = src }
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        val filteredTranslations = remember(translations, selectedSourceFilter) {
                            if (selectedSourceFilter == null) translations else translations.filter { it.source == selectedSourceFilter }
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(filteredTranslations) { tr ->
                                val isSelected = tr.translationId == currentTranslationId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onTranslationSelected(tr.translationId)
                                        showDialog = false
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = tr.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "${tr.source.displayName} • ${if (tr.type == "voice") "Озвучка" else "Субтитры"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Выбрано",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
}

@Composable
fun AnimeQualityDropdown(
    qualities: Map<String, String>,
    currentQualityId: String?,
    hideBackground: Boolean,
    viewModel: PlayerViewModel,
    onQualitySelected: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showDialog) {
        viewModel.setAnimeModalOpen(showDialog)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        border = null,
        modifier = Modifier
            .height(45.dp)
            .clickable { showDialog = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = currentQualityId ?: qualities.keys.firstOrNull() ?: "Auto",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }

    val qList = remember(qualities) {
        // Descending numeric quality first (2160 → 240), "Auto" last — users expect 1080 at the
        // top of the sheet, not whatever order the provider's map happened to ship in.
        val labeled = qualities.keys
            .filter { !it.equals("Auto", true) }
            .sortedWith(
                compareByDescending<String> { label -> label.filter(Char::isDigit).toIntOrNull() ?: Int.MIN_VALUE }
                    .thenBy { it }
            )
        if (qualities.keys.any { it.equals("Auto", true) }) labeled + "Auto" else labeled
    }
    val selectedQ = currentQualityId ?: "Auto"

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDialog = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(360.dp)
                        .clickable(enabled = false) {}
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                "Качество видео",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(qList) { q ->
                                val isSelected = q == selectedQ
                                val subtitle = when (q) {
                                    "Auto" -> "Автоматический выбор лучшего потока"
                                    "1080p" -> "Full HD · Высочайшая четкость"
                                    "720p" -> "HD · Оптимальное качество"
                                    "480p", "360p" -> "SD · Экономия трафика"
                                    else -> "Вариант качества"
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onQualitySelected(q)
                                            showDialog = false
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = q,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Выбрано",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
}

@Composable
fun AnimeShaderControl(
    hideBackground: Boolean,
    viewModel: PlayerViewModel
) {
    val decoderPreferences = koinInject<DecoderPreferences>()
    val anime4kManager = koinInject<Anime4KManager>()
    val scope = rememberCoroutineScope()
    
    val anime4kMode by decoderPreferences.anime4kMode.collectAsState()
    val anime4kQuality by decoderPreferences.anime4kQuality.collectAsState()
    
    var showDialog by remember { mutableStateOf(false) }
    
    val isOff = anime4kMode == "OFF"

    LaunchedEffect(showDialog) {
        viewModel.setAnimeModalOpen(showDialog)
    }

    Surface(
        shape = CircleShape,
        color = if (hideBackground) Color.Transparent else if (!isOff) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (!isOff) MaterialTheme.colorScheme.onPrimary else (if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface),
        border = null,
        modifier = Modifier
            .size(45.dp)
            .clickable { showDialog = true }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (!isOff) Icons.Default.AutoFixHigh else Icons.Default.AutoFixNormal,
                contentDescription = "Anime4K Shader",
                modifier = Modifier.size(24.dp)
            )
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDialog = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(380.dp)
                        .align(Alignment.CenterEnd)
                        .clickable(enabled = false) {}
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Anime4K Улучшение",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            // Compact quality selector
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Anime4KManager.Quality.entries.forEach { q ->
                                    val isSelected = anime4kQuality == q.name
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.clickable {
                                            decoderPreferences.anime4kQuality.set(q.name)
                                            applyShaders(anime4kMode, q.name, anime4kManager, decoderPreferences, scope)
                                        }
                                    ) {
                                        Text(
                                            text = q.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text("Режим (Preset)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        val modes = listOf(
                            ShaderModeInfo("OFF", "Выключено", "Оригинальное изображение"),
                            ShaderModeInfo("A", "Mode A (Fast)", "Оптимизировано для большинства аниме"),
                            ShaderModeInfo("B", "Mode B (Restore)", "Для старых аниме с артефактами"),
                            ShaderModeInfo("C", "Mode C (Sharp)", "Для современных аниме, фокус на четкости"),
                            ShaderModeInfo("A_PLUS", "Mode A+", "Улучшенное восстановление деталей"),
                            ShaderModeInfo("B_PLUS", "Mode B+", "Глубокое восстановление мягких линий"),
                            ShaderModeInfo("C_PLUS", "Mode C+", "Максимальная четкость и контуры")
                        )

                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(modes) { mode ->
                                val isSelected = anime4kMode == mode.id
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        decoderPreferences.anime4kMode.set(mode.id)
                                        applyShaders(mode.id, anime4kQuality, anime4kManager, decoderPreferences, scope)
                                    },
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(mode.title, fontWeight = FontWeight.Bold)
                                        Text(mode.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

private data class ShaderModeInfo(val id: String, val title: String, val description: String)

private fun applyShaders(
    modeStr: String,
    qualityStr: String,
    manager: Anime4KManager,
    decoderPreferences: DecoderPreferences,
    scope: kotlinx.coroutines.CoroutineScope
) {
    decoderPreferences.enableAnime4K.set(modeStr != "OFF")
    scope.launch(Dispatchers.IO) {
        val mode = try { Anime4KManager.Mode.valueOf(modeStr) } catch(e: Exception) { Anime4KManager.Mode.OFF }
        val quality = try { Anime4KManager.Quality.valueOf(qualityStr) } catch(e: Exception) { Anime4KManager.Quality.BALANCED }
        manager.applyShaderChainRuntime(mode, quality)
    }
}
