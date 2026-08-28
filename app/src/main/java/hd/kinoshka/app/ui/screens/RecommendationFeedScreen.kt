package hd.kinoshka.app.ui.screens

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.feed.FeedChip
import hd.kinoshka.app.data.feed.FeedClipState
import hd.kinoshka.app.data.feed.FeedItem
import hd.kinoshka.app.ui.components.BottomNavPill
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.ui.components.NavPillItem

/**
 * Тестовый экран-фид рекомендаций в духе TikTok.
 *
 * Свайп вверх/вниз листает карточки; фон карточки — каскад (полный постер с Ken Burns →
 * кадры из KP поверх блюра → Rutube HLS / YouTube-трейлер). Тап по левой трети экрана
 * листает кадры назад, по правой — вперёд, по центру — разворачивает описание.
 * «Смотреть» открывает страницу тайтла. Показанное во фиде не повторяется.
 */
@Composable
fun RecommendationFeedScreen(
    state: FeedUiState,
    onOpened: () -> Unit,
    onChipSelected: (FeedChip) -> Unit,
    onLoadMore: () -> Unit,
    onToggleExpanded: (Int) -> Unit,
    onReact: (FeedItem, Boolean) -> Unit,
    onItemShown: (List<FeedItem>, Int) -> Unit,
    onOpenDetails: (Int) -> Unit,
    onPlan: (FeedItem) -> Unit,
    onToggleSound: () -> Unit,
    onSelectHomeSection: (HomeTab) -> Unit,
    onAdultGateConfirm: () -> Unit,
    onAdultGateDismiss: () -> Unit,
    onSaveTastes: (FeedChip, List<String>) -> Unit,
    onSkipTastes: (FeedChip) -> Unit,
    onResetSeen: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onLoadTastes: () -> List<Pair<String, Double>>,
    onLoadLiked: () -> List<hd.kinoshka.app.data.feed.LikedTitle>,
    onRemoveLiked: (hd.kinoshka.app.data.feed.LikedTitle) -> Unit
) {
    // Первый показ за сессию: обогащение интересов + визард вкусов.
    LaunchedEffect(Unit) { onOpened() }
    var showTastes by remember { mutableStateOf(false) }
    var showLiked by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading && state.items.isEmpty() -> FeedSkeletonLoader()
            else -> FeedPagerContent(
                state = state,
                onLoadMore = onLoadMore,
                onToggleExpanded = onToggleExpanded,
                onReact = onReact,
                onItemShown = onItemShown,
                onOpenDetails = onOpenDetails,
                onPlan = onPlan,
                onToggleSound = onToggleSound,
                onResetSeen = onResetSeen,
                onShareDiagnostics = onShareDiagnostics
            )
        }

        FeedChipsRow(
            state = state,
            onChipSelected = onChipSelected,
            onShowTastes = { showTastes = true },
            onShowLiked = { showLiked = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 6.dp)
        )

        if (showTastes) {
            TasteInsightsDialog(tastes = onLoadTastes(), onDismiss = { showTastes = false })
        }

        // Плавающая навигация — ТОТ ЖЕ общий компонент, что и на главном экране.
        BottomNavPill(
            items = listOf(
                NavPillItem(
                    filledRes = hd.kinoshka.app.R.drawable.ic_nav_library_filled,
                    outlinedRes = hd.kinoshka.app.R.drawable.ic_nav_library_outlined,
                    contentDescription = "Библиотека",
                    selected = false
                ) { onSelectHomeSection(HomeTab.HISTORY) },
                NavPillItem(
                    filledRes = hd.kinoshka.app.R.drawable.ic_nav_discover_filled,
                    outlinedRes = hd.kinoshka.app.R.drawable.ic_nav_discover_outlined,
                    contentDescription = "Обзор",
                    selected = false
                ) { onSelectHomeSection(HomeTab.CATALOG) },
                NavPillItem(
                    filledRes = hd.kinoshka.app.R.drawable.ic_nav_feed_filled,
                    outlinedRes = hd.kinoshka.app.R.drawable.ic_nav_feed_outlined,
                    contentDescription = "Лента",
                    selected = true
                ) { },
                NavPillItem(
                    filledRes = hd.kinoshka.app.R.drawable.ic_nav_more_filled,
                    outlinedRes = hd.kinoshka.app.R.drawable.ic_nav_more_outlined,
                    contentDescription = "Ещё",
                    selected = false
                ) { onSelectHomeSection(HomeTab.MORE) }
            ),
            isAmoled = false,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Полноценная страница «Мои лайки»: разделы как в рекомендациях, сетка постеров.
        if (showLiked) {
            LikedFeedPage(
                entries = onLoadLiked(),
                adultUnlocked = state.adultUnlocked,
                onClose = { showLiked = false },
                onOpen = onOpenDetails,
                onRemove = onRemoveLiked
            )
        }

        if (state.showAdultGate) {
            AdultGateDialog(onConfirm = onAdultGateConfirm, onDismiss = onAdultGateDismiss)
        }

        // Первичный опрос вкусов — по одному разделу за шаг.
        state.onboardingChip?.let { chip ->
            TastesOnboardingDialog(
                chip = chip,
                onSave = { liked -> onSaveTastes(chip, liked) },
                onSkip = { onSkipTastes(chip) }
            )
        }
    }
}

@Composable
private fun FeedPagerContent(
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onToggleExpanded: (Int) -> Unit,
    onReact: (FeedItem, Boolean) -> Unit,
    onItemShown: (List<FeedItem>, Int) -> Unit,
    onOpenDetails: (Int) -> Unit,
    onPlan: (FeedItem) -> Unit,
    onToggleSound: () -> Unit,
    onResetSeen: () -> Unit,
    onShareDiagnostics: () -> Unit
) {
    val items = state.items
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Позиция восстанавливается ДО первого onItemShown — иначе показ нулевой карточки
    // затрёт сохранённый индекс. Возврат на экран продолжает с места остановки.
    var pagerReady by remember { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty()) {
            val target = state.currentPageIndex.coerceIn(0, items.lastIndex)
            if (target > 0) runCatching { pagerState.scrollToPage(target) }
            pagerReady = true
        }
    }

    // Текущая карточка: обогащение, клип, отметка показанного, прогрев соседей; добор заранее.
    LaunchedEffect(pagerState.currentPage, items.size, pagerReady) {
        if (!pagerReady || items.isEmpty()) return@LaunchedEffect
        onItemShown(items, pagerState.currentPage)
        if (pagerState.currentPage >= items.size - 5) {
            onLoadMore()
        }
    }

    // Отложенные удаления позади текущей карточки: переставляем пейджер на скорректированный
    // индекс — список под пальцем не сдвигается, позиция остаётся на том же тайтле.
    LaunchedEffect(state.dropCommitToken) {
        if (state.dropCommitToken > 0 && items.isNotEmpty()) {
            val target = state.currentPageIndex.coerceIn(0, items.lastIndex)
            runCatching { pagerState.scrollToPage(target) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            FeedEmptyHint(onRetry = onLoadMore)
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 4,
                key = { page -> items[page].kinopoiskId },
                // Мягкое доводение: tween вместо пружины — свайп не «втыкается».
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = tween(320, easing = FastOutSlowInEasing)
                )
            ) { page ->
                val item = items[page]
                val isCurrent = page == pagerState.currentPage
                FeedCard(
                    item = item,
                    extras = state.extras[item.kinopoiskId],
                    clipState = state.clipStates[item.kinopoiskId] ?: FeedClipState.Idle,
                    expanded = item.kinopoiskId in state.expandedIds,
                    reaction = state.reactions[item.kinopoiskId],
                    planned = item.kinopoiskId in state.plannedIds,
                    soundOn = state.soundOn,
                    isActive = isCurrent,
                    onToggleExpanded = { onToggleExpanded(item.kinopoiskId) },
                    onReact = { liked -> onReact(item, liked) },
                    onOpenDetails = { onOpenDetails(item.kinopoiskId) },
                    onPlan = { onPlan(item) },
                    onToggleSound = onToggleSound
                )
            }

            // Тихая подгрузка: тонкая полоска прогресса внизу над пилюлей.
            if (state.loadingMore) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 108.dp)
                        .fillMaxWidth(0.4f),
                    color = Color.White.copy(alpha = 0.6f),
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
            }

            // Конец ленты: показанное не повторяем — только явный сброс.
            if (state.exhausted && !state.loadingMore) {
                FeedEndCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 116.dp),
                    onResetSeen = onResetSeen,
                    onShareDiagnostics = onShareDiagnostics
                )
            }
        }
    }
}

@Composable
private fun FeedEndCard(
    modifier: Modifier = Modifier,
    onResetSeen: () -> Unit,
    onShareDiagnostics: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.72f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                "Вы всё посмотрели",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onResetSeen) {
                Text("Показать всё заново", color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onShareDiagnostics) {
                Text(
                    "Поделиться диагностикой рекомендаций",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ============================ чипсы ============================

@Composable
private fun FeedChipsRow(
    state: FeedUiState,
    onChipSelected: (FeedChip) -> Unit,
    onShowTastes: () -> Unit,
    onShowLiked: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 18+ гейт остаётся только у Хентая; тап до подтверждения открывает диалог.
    val visibleChips = FeedChip.entries
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(visibleChips, key = { it.name }) { chip ->
            val selected = state.selectedChip == chip
            // Стеклянная пилюля: выбранная залита акцентом, остальные — тёмное стекло без рамок.
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
                shadowElevation = if (selected) 4.dp else 0.dp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onChipSelected(chip) }
            ) {
                Text(
                    chip.title,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.85f),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
        // Кнопка «мои вкусы»: что система выучила из голосов.
        item(key = "tastes") {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onShowTastes() }
            ) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = "Мои вкусы",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).size(18.dp)
                )
            }
        }
        // Кнопка «мои лайки»: все лайкнутые тайтлы по жанрам.
        item(key = "liked") {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onShowLiked() }
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Мои лайки",
                    tint = Color(0xFFEF5350).copy(alpha = 0.95f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).size(18.dp)
                )
            }
        }
    }
}

/**
 * Полноценная страница «Мои лайки» поверх ленты: та же структура разделов, что и в
 * рекомендациях («Все» + разделы с лайками, хентай — после подтверждения 18+),
 * сетка постеров как в библиотеке. Тап — страница тайтла, долгий тап — снять лайк.
 */
@Composable
private fun LikedFeedPage(
    entries: List<hd.kinoshka.app.data.feed.LikedTitle>,
    adultUnlocked: Boolean,
    onClose: () -> Unit,
    onOpen: (Int) -> Unit,
    onRemove: (hd.kinoshka.app.data.feed.LikedTitle) -> Unit
) {
    var list by remember(entries) { mutableStateOf(entries) }
    var pendingRemove by remember { mutableStateOf<hd.kinoshka.app.data.feed.LikedTitle?>(null) }

    // Разделы: «Все» + разделы рекомендаций, где есть лайки (порядок как в фиде).
    val sections = remember(list, adultUnlocked) {
        val bySection = list.groupBy { it.sectionOrNull() }
        buildList {
            add(null to list)
            FeedChip.ALL_MIX.forEach { chip -> bySection[chip]?.let { add(chip to it) } }
            if (adultUnlocked) bySection[FeedChip.HENTAI]?.let { add(FeedChip.HENTAI to it) }
        }
    }
    var selected by remember { mutableStateOf<FeedChip?>(null) }
    val shown = sections.firstOrNull { it.first == selected }?.second ?: list

    BackHandler(onBack = onClose)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp)
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    "Мои лайки · ${list.size}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (sections.size > 1) {
                val selectedIndex = sections.indexOfFirst { it.first == selected }.coerceAtLeast(0)
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 10.dp,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        if (selectedIndex < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    divider = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    sections.forEachIndexed { index, (chip, items) ->
                        val isSelected = index == selectedIndex
                        Tab(
                            selected = isSelected,
                            onClick = { selected = chip },
                            modifier = Modifier.height(40.dp),
                            text = {
                                Text(
                                    "${chip?.title ?: "Все"} · ${items.size}",
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

            if (shown.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Пока нет лайков. Жмите «нравится» на карточках ленты — они соберутся здесь по разделам.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    gridItems(shown, key = { it.id }, contentType = { "liked_item" }) { entry ->
                        LikedGridCard(
                            entry = entry,
                            onOpen = { onOpen(entry.id) },
                            onLongPress = { pendingRemove = entry }
                        )
                    }
                }
            }
        }
    }

    pendingRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Убрать лайк?") },
            text = { Text("«${entry.title}» пропадёт из списка и перестанет влиять на рекомендации.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    list = list.filterNot { it.id == entry.id }
                    onRemove(entry)
                }) { Text("Убрать") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Отмена") }
            }
        )
    }
}

/** Карточка лайка: постер 2:3, название и жанры; долгий тап — снятие лайка. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LikedGridCard(
    entry: hd.kinoshka.app.data.feed.LikedTitle,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (entry.posterUrl.isNullOrBlank()) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                KinoshkaAsyncImage(
                    model = entry.posterUrl,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        val genresLine = entry.genres.take(2).joinToString(" · ")
        if (genresLine.isNotBlank()) {
            Text(
                text = genresLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

/** Диалог «что система про вас поняла»: топ измерений вкуса по модулю вклада. */
@Composable
private fun TasteInsightsDialog(tastes: List<Pair<String, Double>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ваши вкусы") },
        text = {
            if (tastes.isEmpty()) {
                Text("Пока пусто: поставьте несколько лайков или дизлайков.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tastes.forEach { (dim, w) ->
                        val label = dim.substringAfter(':').let { name ->
                            when (dim.substringBefore(':')) {
                                "g" -> name
                                "c" -> "$name (страна)"
                                "d" -> "$name (эпоха)"
                                "t" -> when (name) {
                                    "ANIME" -> "аниме"
                                    "MOVIE" -> "фильмы"
                                    else -> "сериалы"
                                }
                                else -> name
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (w >= 0) Color(0xFF66BB6A) else DislikeRedFallback)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "%+.1f".format(w),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (w >= 0) Color(0xFF66BB6A) else DislikeRedFallback,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } }
    )
}

private val DislikeRedFallback = Color(0xFFEF5350)

// ============================ диалог 18+ ============================

@Composable
private fun AdultGateDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Контент для взрослых") },
        text = { Text("Этот раздел содержит материалы категории 18+. Вам есть 18 лет?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Да, мне 18+") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Нет") } }
    )
}

// ============================ визард вкусов ============================

/** Наборы жанров для первичного опроса — по разделу. */
private val FILM_TASTE_GENRES =
    listOf("Фантастика", "Боевик", "Комедия", "Драма", "Ужасы", "Триллер", "Мелодрама", "Детектив")
private val SERIES_TASTE_GENRES =
    listOf("Драма", "Криминал", "Фэнтези", "Детектив", "Триллер", "Исторический", "Биография", "Комедия")
private val ANIME_TASTE_GENRES =
    listOf("Сёнэн", "Романтика", "Исекай", "Повседневность", "Спорт", "Психологическое", "Фэнтези", "Комедия")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TastesOnboardingDialog(
    chip: FeedChip,
    onSave: (List<String>) -> Unit,
    onSkip: () -> Unit
) {
    val genres = when (chip) {
        FeedChip.SERIES -> SERIES_TASTE_GENRES
        FeedChip.ANIME -> ANIME_TASTE_GENRES
        else -> FILM_TASTE_GENRES
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val sectionTitle = when (chip) {
        FeedChip.SERIES -> "сериалов"
        FeedChip.ANIME -> "аниме"
        else -> "фильмов"
    }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Что вам нравится?") },
        text = {
            Column {
                Text(
                    "Выберите жанры $sectionTitle — подберём ленту под вас",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    genres.forEach { genre ->
                        val isSelected = genre in selected
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selected = if (isSelected) selected - genre else selected + genre
                            },
                            label = { Text(genre) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onSave(selected.toList()) }
            ) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Пропустить") }
        }
    )
}

// ============================ заглушки ============================

/** Анимированный скелетон первой загрузки: пульсирующая «плёнка» в духе коротких видео. */
@Composable
private fun FeedSkeletonLoader() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "feed_skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(750),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "skeleton_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // «Кадры плёнки»: три вертикальные карточки, средняя акцентная.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonStrip(alpha = pulse * 0.5f)
                SkeletonStrip(alpha = 0.35f + pulse * 0.65f, highlighted = true)
                SkeletonStrip(alpha = pulse * 0.5f)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Подбираем рекомендации по вашим интересам…",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SkeletonStrip(alpha: Float, highlighted: Boolean = false) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(if (highlighted) 128.dp else 104.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        if (highlighted) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.Black.copy(alpha = alpha.coerceAtMost(1f)),
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun FeedEmptyHint(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 130.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Лента догружается…",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text("Обновить", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
