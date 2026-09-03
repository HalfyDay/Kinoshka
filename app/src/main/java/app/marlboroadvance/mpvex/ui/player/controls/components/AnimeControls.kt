package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.AnimeSourceType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

/**
 * Single season+episode picker: the pill shows the current episode ("S2E3"), the dialog keeps a
 * season chip row on top and the ACTIVE season's episode list below it. Season filtering is done
 * locally on [episodes], so tapping a season re-filters the list instantly and deterministically.
 */
@Composable
fun AnimeSeriesDropdown(
    episodes: List<AnimeEpisode>,
    seasons: List<Int>,
    currentSeason: Int?,
    currentEpisode: Int?,
    hideBackground: Boolean,
    viewModel: PlayerViewModel,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableStateOf<Int?>(currentSeason) }
    val watchedEpisodesCount by viewModel.watchedEpisodesCount.collectAsState()
    val watchedSeasons by viewModel.watchedSeasons.collectAsState()
    val watchedPerSeason by viewModel.watchedPerSeason.collectAsState()

    LaunchedEffect(showDialog) {
        viewModel.setAnimeModalOpen(showDialog)
        if (showDialog) selectedSeason = currentSeason
    }

    val currentEp = episodes.firstOrNull { it.number == currentEpisode }
    val episodeLabel = when {
        currentEp?.season != null -> "S${currentEp.season}E${currentEp.number % 100_000}"
        // Filtered list without the current episode: recover S/E from the composite key.
        currentEpisode != null && currentEpisode > 100_000 ->
            "S${currentEpisode / 100_000}E${currentEpisode % 100_000}"
        currentEpisode != null -> "Эп. $currentEpisode"
        else -> "Серии"
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
                text = episodeLabel,
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
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        if (seasons.size > 1) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                items(seasons) { season ->
                                    val isSelected = season == selectedSeason
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.clickable {
                                            selectedSeason = season
                                            onSeasonSelected(season)
                                        }
                                    ) {
                                        Text(
                                            text = "Сезон $season",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Local season filter: the list always reflects the chip row above.
                        // Season falls back to the composite key (season*100000+episode) so a
                        // row that lost its metadata still lands in the right season bucket.
                        fun effectiveSeason(ep: AnimeEpisode): Int? =
                            ep.season ?: (ep.number.takeIf { it > 100_000 }?.let { it / 100_000 })
                        val visibleEpisodes = remember(episodes, selectedSeason) {
                            episodes.filter { effectiveSeason(it) == null || selectedSeason == null || effectiveSeason(it) == selectedSeason }
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(visibleEpisodes) { ep ->
                                val isSelected = ep.number == currentEpisode
                                val rowSeason = effectiveSeason(ep)
                                // Сериалы: watchedSeasons — текущий сезон из «Прогресса просмотра»,
                                // предыдущие сезоны просмотрены целиком, в текущем — серии до счётчика.
                                // Аниме (плоский список без сезонов) — старое правило по счётчику.
                                val isWatched = when {
                                    rowSeason != null && watchedPerSeason ->
                                        rowSeason < watchedSeasons ||
                                            (rowSeason == watchedSeasons && ep.number % 100_000 <= watchedEpisodesCount)
                                    rowSeason == null -> ep.number <= watchedEpisodesCount
                                    else -> false
                                }
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
                                            val seriesLabel = if (rowSeason != null) {
                                                "Сезон $rowSeason, серия ${ep.number % 100_000}"
                                            } else null
                                            val episodeName = ep.title?.takeIf { it.isNotBlank() }
                                            val subtitle = when {
                                                seriesLabel != null -> episodeName ?: "Смотреть серию"
                                                // Kodik auto-generates titles like "Серия 1"; repeating them
                                                // under the same primary label reads as a duplicated row.
                                                episodeName != null && !episodeName.equals(
                                                    "Серия ${ep.number}",
                                                    ignoreCase = true
                                                ) -> "Серия ${ep.number}"
                                                else -> null
                                            }
                                            Text(
                                                text = seriesLabel ?: (episodeName ?: "Серия ${ep.number % 100_000}"),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            // Fixed-height second line: tiles without a real episode
                                            // title keep the same size as titled ones instead of
                                            // collapsing into flat single-line rows.
                                            Box(modifier = Modifier.height(18.dp)) {
                                                if (subtitle != null) {
                                                    Text(
                                                        text = subtitle,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
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

    // Global preference memory: dubs/sources the user launches rise to the top of the list.
    val context = LocalContext.current
    val playbackUsage = remember { UserStateStore(context).getPlaybackUsage() }
    // Per-title ключ памяти (тот же формат, что пишет PlayerActivity): любимая озвучка тайтла
    // ранжируется по его собственной истории, глобальная память — только как fallback.
    val dubMediaKey = remember {
        (context as? android.app.Activity)?.intent?.let { intent ->
            intent.getIntExtra("movie_kinopoisk_id", 0).takeIf { it > 0 }?.let { "kp:$it" }
                ?: intent.getIntExtra("anime_shikimori_id", 0).takeIf { it > 0 }?.let { "sh:$it" }
        }
    }

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
            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
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

                        // Source chips — тот же паттерн, что чипы сезонов в «Выберите серию»:
                        // горизонтальная прокрутка, тап фильтрует; при одном источнике ряд скрыт.
                        val availableSources = translations.map { it.source }.distinct()
                        if (availableSources.size > 1) {
                            // "Все" first, then sources ranked by the user's own usage.
                            val filters = listOf(null) + availableSources.sortedWith(
                                compareByDescending<AnimeSourceType> { src ->
                                    playbackUsage.sources[src.name]?.lastUsedAt ?: 0L
                                }.thenByDescending { playbackUsage.sources[it.name]?.count ?: 0 }
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                items(filters) { src ->
                                    val isSelected = selectedSourceFilter == src
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.clickable { selectedSourceFilter = src }
                                    ) {
                                        Text(
                                            text = src?.displayName ?: "Все",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val filteredTranslations = remember(translations, selectedSourceFilter, playbackUsage, dubMediaKey) {
                            val base = if (selectedSourceFilter == null) translations
                            else translations.filter { it.source == selectedSourceFilter }
                            // Used-first ranking (recency, then frequency); stable sort keeps the
                            // provider order for everything the user never touched. Per-title
                            // память (любимая озвучка тайтла) важнее глобальной.
                            fun usageOf(tr: FlatTranslation) = dubMediaKey?.let { mk ->
                                playbackUsage.titleDubs["$mk|${tr.title.trim().lowercase()}"]
                            } ?: playbackUsage.dubs[tr.title.trim().lowercase()]
                            base.sortedWith(
                                compareByDescending<FlatTranslation> { usageOf(it)?.lastUsedAt ?: 0L }
                                    .thenByDescending { usageOf(it)?.count ?: 0 }
                            )
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
                                        // Preference badge: this dub team is in the usage memory.
                                        val usedOnThisTitle = dubMediaKey?.let { mk ->
                                            playbackUsage.titleDubs.containsKey("$mk|${tr.title.trim().lowercase()}")
                                        } ?: false
                                        if (usedOnThisTitle || playbackUsage.dubs.containsKey(tr.title.trim().lowercase())) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = "Вы часто смотрите с этой озвучкой",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = tr.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "${tr.source.displayName} • ${
                                                    when (tr.type) {
                                                        "voice" -> "Озвучка"
                                                        "orig" -> "Оригинал (без перевода)"
                                                        else -> "Субтитры"
                                                    }
                                                }",
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
    var menuExpanded by remember { mutableStateOf(false) }
    val resolution by viewModel.videoResolution.collectAsState()
    // Rung resolved by the activity from the active stream's ladder; bridges the gap before
    // mpv reports video-params so "Auto · 1080p" is visible immediately at launch.
    val rungHint by viewModel.autoQualityRungHint.collectAsState()

    LaunchedEffect(menuExpanded) {
        viewModel.setAnimeModalOpen(menuExpanded)
    }

    val qList = remember(qualities) {
        // "Auto" is always offered at the top regardless of whether the source shipped a literal
        // Auto entry; concrete variants follow, sorted descending (2160 → 240).
        listOf("Auto") + qualities.keys
            .filter { !it.equals("Auto", true) }
            .sortedWith(
                compareByDescending<String> { label -> label.filter(Char::isDigit).toIntOrNull() ?: Int.MIN_VALUE }
                    .thenBy { it }
            )
            .distinct()
    }
    val selectedQ = currentQualityId ?: "Auto"
    // Live video-params win once available; the resolver-derived hint covers launch/switching.
    val autoRung = effectiveAutoRung(resolution) ?: rungHint
    val autoLabel = if (autoRung != null) "Auto · $autoRung" else "Auto"
    // Даже при единственной concrete-ступени меню не бессмысленно: пользователь может
    // переключиться между фиксированной ступенью и Auto (и снова включить watchdog).
    // Отключаем пилюлю только когда в списке действительно один-единственный Auto.
    val canChoose = qList.size > 1

    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
            border = null,
            modifier = Modifier
                .height(45.dp)
                .clickable(enabled = canChoose) { menuExpanded = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = if (selectedQ.equals("Auto", true)) autoLabel else selectedQ,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            // Padding shrinks the colored surface within the popup window, so even when the
            // window clamps to screen edges the menu visually floats clear of every corner.
            modifier = Modifier.padding(12.dp).heightIn(max = 300.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            qList.forEach { q ->
                val isSelected = q == selectedQ
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (q.equals("Auto", true)) autoLabel else q,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Выбрано",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        menuExpanded = false
                        onQualitySelected(q)
                    }
                )
            }
        }
    }
}

/**
 * Maps an actual frame size to the closest standard ladder rung. The LONGER side is used because
 * widescreen content (1080p at 2.35:1 = 1920×818) would otherwise floor to the wrong rung by
 * height; width distinguishes renditions reliably for both landscape and rotated portrait video.
 */
private fun effectiveAutoRung(resolution: Pair<Int, Int>?): String? {
    val major = resolution?.let { maxOf(it.first, it.second) } ?: return null
    return when {
        major >= 3400 -> "2160p"
        major >= 2300 -> "1440p"
        major >= 1700 -> "1080p"
        major >= 1100 -> "720p"
        major >= 760 -> "480p"
        major >= 560 -> "360p"
        else -> "240p"
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
