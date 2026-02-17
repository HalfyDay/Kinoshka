package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.ui.components.ExpressiveBlobLoadingIndicator
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.ceil

private enum class MainSection {
    LIBRARY,
    DISCOVER,
    MORE
}

private enum class LibraryTab(val title: String) {
    HISTORY("История"),
    WATCHING("Смотрю"),
    PLANNED("В планах"),
    WATCHED("Просмотрено"),
    REWATCHING("Пересматриваю"),
    ON_HOLD("Отложено"),
    DROPPED("Брошено")
}

private data class GridMetrics(
    val columns: Int
)

private val FloatingBottomContentPadding = 112.dp
private val SearchChromeHeight = 70.dp

@Composable
fun HomeScreen(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onRetry: () -> Unit,
    onTabSelected: (HomeTab) -> Unit,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onDiscoverCategorySelected: (DiscoverCategory) -> Unit,
    onLoadMore: () -> Unit,
    onRemoveFromHistory: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val libraryMetrics = state.libraryTileSize.toGridMetrics()
    val discoverMetrics = state.discoverTileSize.toGridMetrics()
    val statusByFilmId = remember(state.library) {
        state.library
            .mapNotNull { item -> item.status?.let { item.kinopoiskId to it } }
            .toMap()
    }
    val progressByFilmId = remember(state.library) {
        state.library
            .mapNotNull { item -> item.toWatchProgressUi()?.let { progress -> item.kinopoiskId to progress } }
            .toMap()
    }

    var section by rememberSaveable {
        mutableStateOf(
            if (state.tab == HomeTab.HISTORY) MainSection.LIBRARY else MainSection.DISCOVER
        )
    }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var discoverQuery by rememberSaveable { mutableStateOf("") }
    var moreQuery by rememberSaveable { mutableStateOf("") }
    val activeQuery = when (section) {
        MainSection.LIBRARY -> libraryQuery
        MainSection.DISCOVER -> discoverQuery
        MainSection.MORE -> moreQuery
    }
    var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.WATCHING) }
    val searchRowHeight = SearchChromeHeight
    val searchRowAlpha = 1f
    val normalizedQuery = state.query.trim()
    val libraryItemsByTab = remember(state.library, state.hideRussianContent, normalizedQuery) {
        LibraryTab.entries.associateWith { tab ->
            state.library
                .filterByTab(tab)
                .filterByRussian(state.hideRussianContent)
                .filterByQuery(normalizedQuery)
        }
    }
    val discoverItems = remember(state.items, state.hideRussianContent) {
        if (state.hideRussianContent) {
            state.items.filterNot { it.isRussianContent() }
        } else {
            state.items
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            BottomSectionBar(
                section = section,
                isAmoled = state.themeMode == AppThemeMode.AMOLED,
                onSectionSelected = { selected ->
                    when (section) {
                        MainSection.LIBRARY -> libraryQuery = state.query
                        MainSection.DISCOVER -> discoverQuery = state.query
                        MainSection.MORE -> moreQuery = state.query
                    }
                    section = selected
                    when (selected) {
                        MainSection.LIBRARY -> {
                            libraryTab = LibraryTab.WATCHING
                            onQueryChange(libraryQuery)
                            onTabSelected(HomeTab.HISTORY)
                        }
                        MainSection.DISCOVER -> {
                            onQueryChange(discoverQuery)
                            onTabSelected(HomeTab.CATALOG)
                            onDiscoverCategorySelected(DiscoverCategory.POPULAR)
                        }
                        MainSection.MORE -> {
                            onQueryChange(moreQuery)
                            onTabSelected(HomeTab.CATALOG)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(top = innerPadding.calculateTopPadding())
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(searchRowHeight)
                        .clipToBounds()
                ) {
                    SearchRow(
                        query = activeQuery,
                        avatar = state.profileAvatar,
                        placeholder = when (section) {
                            MainSection.LIBRARY -> "Поиск в библиотеке"
                            MainSection.DISCOVER -> "Поиск фильмов и сериалов"
                            MainSection.MORE -> "Поиск по разделу Ещё"
                        },
                        onQueryChange = { value ->
                            when (section) {
                                MainSection.LIBRARY -> libraryQuery = value
                                MainSection.DISCOVER -> discoverQuery = value
                                MainSection.MORE -> moreQuery = value
                            }
                            onQueryChange(value)
                        },
                        onSearch = {
                            focusManager.clearFocus()
                            if (section == MainSection.DISCOVER) {
                                onSubmitSearch()
                            }
                        },
                        onAvatarClick = onOpenProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(searchRowAlpha)
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (section) {
                        MainSection.LIBRARY -> {
                            val pagerState = rememberPagerState(
                                initialPage = libraryTab.ordinal,
                                pageCount = { LibraryTab.entries.size }
                            )

                            LaunchedEffect(pagerState) {
                                snapshotFlow {
                                    pagerState.currentPage
                                }.collect { page ->
                                    libraryTab = LibraryTab.entries[page]
                                }
                            }
                            LaunchedEffect(libraryTab) {
                                if (!pagerState.isScrollInProgress && pagerState.currentPage != libraryTab.ordinal) {
                                    pagerState.animateScrollToPage(libraryTab.ordinal)
                                }
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                LibraryTabs(
                                    pagerState = pagerState,
                                    onSelect = { target -> libraryTab = target }
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                HorizontalPager(
                                    state = pagerState,
                                    flingBehavior = PagerDefaults.flingBehavior(
                                        state = pagerState,
                                        snapAnimationSpec = tween(
                                            durationMillis = 330,
                                            easing = FastOutSlowInEasing
                                        )
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    val pageTab = LibraryTab.entries[page]
                                    val items = libraryItemsByTab[pageTab].orEmpty()
                                    LibraryPageGrid(
                                        items = items,
                                        historyMode = pageTab == LibraryTab.HISTORY,
                                        onOpenHistoryFilm = onOpenHistoryFilm,
                                        onRemoveFromHistory = onRemoveFromHistory,
                                        metrics = libraryMetrics
                                    )
                                }
                            }
                        }

                        MainSection.DISCOVER -> {
                            DiscoverContent(
                                state = state,
                                sourceItems = discoverItems,
                                metrics = discoverMetrics,
                                statusByFilmId = statusByFilmId,
                                progressByFilmId = progressByFilmId,
                                onRetry = onRetry,
                                onOpenFilm = onOpenFilm,
                                onLoadMore = onLoadMore,
                                onCategorySelected = onDiscoverCategorySelected
                            )
                        }

                        MainSection.MORE -> {
                            MoreContent(
                                query = state.query.trim(),
                                onOpenProfile = onOpenProfile,
                                onOpenSettings = onOpenSettings,
                                onOpenAbout = onOpenAbout
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    avatar: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = {
                Text(
                    text = "⌕",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(28.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Surface(
            modifier = Modifier
                .size(46.dp)
                .clickable(onClick = onAvatarClick),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            AvatarBadge(avatar = avatar)
        }
    }
}

@Composable
private fun AvatarBadge(avatar: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        if (avatar.startsWith("content://") || avatar.startsWith("file://") || avatar.startsWith("http")) {
            AsyncImage(
                model = avatar,
                contentDescription = "Аватар",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Text(
                text = avatar.ifBlank { "🎬" },
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTabs(
    pagerState: PagerState,
    onSelect: (LibraryTab) -> Unit
) {
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    PrimaryScrollableTabRow(
        selectedTabIndex = pagerState.currentPage,
        edgePadding = 12.dp,
        containerColor = Color.Transparent,
        divider = {},
        modifier = Modifier.fillMaxWidth()
    ) {
        LibraryTab.entries.forEachIndexed { index, tab ->
            val pageOffset = ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction)
                .absoluteValue
            val selectedFraction = (1f - pageOffset).coerceIn(0f, 1f)
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selectedFraction > 0.66f) FontWeight.SemiBold else FontWeight.Normal,
                        color = lerp(unselectedColor, selectedColor, selectedFraction)
                    )
                },
                selectedContentColor = selectedColor,
                unselectedContentColor = unselectedColor
            )
        }
    }
}

@Composable
private fun DiscoverTabs(
    selected: DiscoverCategory,
    isSearchResult: Boolean,
    onSelect: (DiscoverCategory) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = DiscoverCategory.entries,
            key = { it.name }
        ) { category ->
            FilterChip(
                selected = selected == category && !isSearchResult,
                onClick = { onSelect(category) },
                label = { Text(category.title) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPageGrid(
    items: List<LibraryUiItem>,
    historyMode: Boolean,
    onOpenHistoryFilm: (Int) -> Unit,
    onRemoveFromHistory: (Int) -> Unit,
    metrics: GridMetrics
) {
    var pendingDeleteId by remember { mutableIntStateOf(0) }

    if (items.isEmpty()) {
        EmptyCard(
            title = "Пусто",
            message = "Для этого раздела пока нет фильмов или сериалов."
        )
        return
    }

    if (metrics.columns == 1) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { it.kinopoiskId }, contentType = { "library_vertical_item" }) { item ->
                LibraryVerticalRow(
                    item = item,
                    onOpen = { onOpenHistoryFilm(item.kinopoiskId) },
                    onLongPress = {
                        if (historyMode && item.viewedAtMillis != null) {
                            pendingDeleteId = item.kinopoiskId
                        }
                    }
                )
            }
        }
    } else {
    LazyVerticalGrid(
        columns = GridCells.Fixed(metrics.columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(
            items = items,
            key = { it.kinopoiskId },
            contentType = { "library_item" }
        ) { item ->
            LibraryGridCard(
                item = item,
                compactText = metrics.columns >= 3,
                onOpen = { onOpenHistoryFilm(item.kinopoiskId) },
                onLongPress = {
                    if (historyMode && item.viewedAtMillis != null) {
                        pendingDeleteId = item.kinopoiskId
                    }
                }
            )
        }
    }
    }

    if (pendingDeleteId != 0) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = 0 },
            title = { Text("Удалить из истории?") },
            text = { Text("Фильм будет удален только из вкладки История.") },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveFromHistory(pendingDeleteId)
                        pendingDeleteId = 0
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDeleteId = 0 }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun DiscoverContent(
    state: HomeUiState,
    sourceItems: List<FilmItem>,
    metrics: GridMetrics,
    statusByFilmId: Map<Int, UserFilmStatus>,
    progressByFilmId: Map<Int, WatchProgressUi>,
    onRetry: () -> Unit,
    onOpenFilm: (FilmItem) -> Unit,
    onLoadMore: () -> Unit,
    onCategorySelected: (DiscoverCategory) -> Unit
) {
    when {
        state.loading -> LoadingCard()
        state.error != null -> ErrorCard(message = state.error, onRetry = onRetry)
        sourceItems.isEmpty() -> {
            val text = if (state.isSearchResult && state.query.isNotBlank()) {
                "Ничего не найдено по запросу: ${state.query}"
            } else {
                "По этой категории пока нет данных."
            }
            EmptyCard(title = "Пусто", message = text)
        }

        else -> {
            if (metrics.columns == 1) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        DiscoverTabs(
                            selected = state.discoverCategory,
                            isSearchResult = state.isSearchResult,
                            onSelect = onCategorySelected
                        )
                    }
                    items(
                        items = sourceItems,
                        key = { it.kinopoiskId },
                        contentType = { "discover_vertical_item" }
                    ) { film ->
                        DiscoverVerticalRow(
                            film = film,
                            status = statusByFilmId[film.kinopoiskId],
                            watchProgress = progressByFilmId[film.kinopoiskId],
                            onOpenFilm = onOpenFilm
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        if (state.loadingMore) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        } else if (state.hasMore) {
                            OutlinedButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Загрузить еще")
                            }
                        } else {
                            Text(
                                text = "Это конец списка",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                return
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(metrics.columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DiscoverTabs(
                        selected = state.discoverCategory,
                        isSearchResult = state.isSearchResult,
                        onSelect = onCategorySelected
                    )
                }
                items(
                    items = sourceItems,
                    key = { it.kinopoiskId },
                    contentType = { "discover_item" }
                ) { film ->
                    DiscoverGridCard(
                        film = film,
                        compactText = metrics.columns >= 3,
                        status = statusByFilmId[film.kinopoiskId],
                        watchProgress = progressByFilmId[film.kinopoiskId],
                        onOpenFilm = onOpenFilm
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (state.loadingMore) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (state.hasMore) {
                        OutlinedButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Загрузить еще")
                        }
                    } else {
                        Text(
                            text = "Это конец списка",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreContent(
    query: String,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val allItems = listOf(
        MoreMenuItem(
            title = "Профиль",
            subtitle = "Иконка профиля и график активности",
            onClick = onOpenProfile
        ),
        MoreMenuItem(
            title = "Настройки",
            subtitle = "Тема, фильтры и импорт/экспорт библиотеки",
            onClick = onOpenSettings
        ),
        MoreMenuItem(
            title = "О приложении",
            subtitle = "Версия, обновления и полезные ссылки",
            onClick = onOpenAbout
        )
    )
    val items = if (query.isBlank()) {
        allItems
    } else {
        allItems.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (items.isEmpty()) {
            item {
                EmptyCard(
                    title = "Ничего не найдено",
                    message = "По запросу \"$query\" в разделе Ещё ничего не найдено."
                )
            }
        } else {
            items(items, key = { it.title }) { item ->
                MenuCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    onClick = item.onClick
                )
            }
        }
    }
}

@Composable
private fun MenuCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiscoverGridCard(
    film: FilmItem,
    compactText: Boolean,
    status: UserFilmStatus?,
    watchProgress: WatchProgressUi?,
    onOpenFilm: (FilmItem) -> Unit
) {
    val titleText = remember(film.nameRu, film.nameOriginal) {
        film.nameRu ?: film.nameOriginal ?: "Без названия"
    }
    val metaText = remember(film.year, film.ratingKinopoisk) {
        listOfNotNull(
            film.year?.toString(),
            film.ratingKinopoisk?.let { "KP ${"%.1f".format(it)}" }
        ).joinToString(" • ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFilm(film) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            AsyncImage(
                model = film.posterUrlPreview,
                contentDescription = film.nameRu,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
            status?.let {
                UserStatusBadge(
                    status = it,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
            watchProgress?.let { progress ->
                PosterBottomProgressBar(
                    progress = progress.progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = titleText,
                style = if (compactText) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metaText,
                style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridCard(
    item: LibraryUiItem,
    compactText: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val watchProgress = remember(
        item.type,
        item.watchedSeasons,
        item.watchedEpisodes,
        item.totalEpisodesInSeason,
        item.totalSeasons,
        item.totalEpisodes
    ) {
        item.toWatchProgressUi()
    }
    val detailsText = remember(item.subtitle, item.ratingText, item.userRating) {
        listOfNotNull(
            item.subtitle,
            item.ratingText,
            item.userRating?.let { "Моя: $it/10" }
        ).joinToString(" • ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
            item.status?.let {
                UserStatusBadge(
                    status = it,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
            watchProgress?.let { progress ->
                PosterBottomProgressBar(
                    progress = progress.progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = item.title,
                style = if (compactText) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detailsText,
                style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val progressLabel = watchProgress?.progressLabel
            if (!progressLabel.isNullOrBlank()) {
                Text(
                    text = progressLabel,
                    style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!item.note.isNullOrBlank()) {
                Text(
                    text = item.note,
                    style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryVerticalRow(
    item: LibraryUiItem,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val watchProgress = remember(
        item.type,
        item.watchedSeasons,
        item.watchedEpisodes,
        item.totalEpisodesInSeason,
        item.totalSeasons,
        item.totalEpisodes
    ) {
        item.toWatchProgressUi()
    }
    val metaText = remember(item.subtitle, item.type, item.ratingText) {
        listOfNotNull(
            item.subtitle,
            item.type?.let { if (it == "TV_SERIES") "TV" else "Movie" },
            item.ratingText?.replace("KP ", "")
        ).joinToString(" · ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(92.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
            item.status?.let {
                UserStatusBadge(status = it, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metaText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (watchProgress != null) {
                LinearProgressIndicator(
                    progress = { watchProgress.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Text(
                    text = watchProgress.progressLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!item.note.isNullOrBlank()) {
                Text(
                    text = item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DiscoverVerticalRow(
    film: FilmItem,
    status: UserFilmStatus?,
    watchProgress: WatchProgressUi?,
    onOpenFilm: (FilmItem) -> Unit
) {
    val titleText = remember(film.nameRu, film.nameOriginal) {
        film.nameRu ?: film.nameOriginal ?: "Без названия"
    }
    val metaText = remember(film.year, film.ratingKinopoisk) {
        listOfNotNull(
            film.year?.toString(),
            "TV",
            film.ratingKinopoisk?.let { "%.2f★".format(it) }
        ).joinToString(" · ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFilm(film) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(92.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
        ) {
            AsyncImage(
                model = film.posterUrlPreview,
                contentDescription = film.nameRu,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
            status?.let {
                UserStatusBadge(status = it, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metaText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (watchProgress != null) {
                Text(
                    text = watchProgress.progressLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class WatchProgressUi(
    val progress: Float,
    val progressLabel: String
)

private fun LibraryUiItem.toWatchProgressUi(): WatchProgressUi? {
    if (type != "TV_SERIES") return null

    val watchedSeasonsSafe = (watchedSeasons ?: 0).coerceAtLeast(0)
    val watchedEpisodesSafe = (watchedEpisodes ?: 0).coerceAtLeast(0)
    val totalEpisodesInSeasonSafe = totalEpisodesInSeason?.takeIf { it > 0 }
    val totalSeasonsSafe = totalSeasons?.takeIf { it > 0 }
    val totalEpisodesSafe = totalEpisodes?.takeIf { it > 0 }
    val watchedSeasonsBounded = totalSeasonsSafe?.let { watchedSeasonsSafe.coerceAtMost(it) } ?: watchedSeasonsSafe
    if (watchedSeasonsSafe == 0 && watchedEpisodesSafe == 0 &&
        totalSeasonsSafe == null && totalEpisodesSafe == null
    ) {
        return null
    }

    // watchedEpisodes is treated as episodes watched in current season (not full series).
    val currentSeasonTotalEpisodes = when {
        totalEpisodesInSeasonSafe != null -> totalEpisodesInSeasonSafe
        totalSeasonsSafe == null || totalEpisodesSafe == null -> null
        totalSeasonsSafe == 1 -> totalEpisodesSafe
        else -> ceil(totalEpisodesSafe.toDouble() / totalSeasonsSafe.toDouble()).toInt().coerceAtLeast(1)
    }

    val watchedEpisodesInSeason = when {
        currentSeasonTotalEpisodes == null -> watchedEpisodesSafe
        totalSeasonsSafe == 1 && watchedSeasonsBounded >= 1 -> currentSeasonTotalEpisodes
        else -> watchedEpisodesSafe.coerceAtMost(currentSeasonTotalEpisodes)
    }

    val progress = when {
        totalSeasonsSafe != null && totalSeasonsSafe > 0 -> {
            if (totalSeasonsSafe == 1 && watchedSeasonsBounded >= 1) {
                1f
            } else {
                val completedSeasons = when {
                    watchedSeasonsBounded <= 0 -> 0
                    watchedEpisodesInSeason > 0 -> (watchedSeasonsBounded - 1).coerceAtLeast(0)
                    else -> watchedSeasonsBounded
                }
                val currentSeasonPart = if (currentSeasonTotalEpisodes != null && currentSeasonTotalEpisodes > 0) {
                    watchedEpisodesInSeason.toFloat() / currentSeasonTotalEpisodes.toFloat()
                } else {
                    0f
                }
                ((completedSeasons + currentSeasonPart) / totalSeasonsSafe.toFloat()).coerceIn(0f, 1f)
            }
        }

        watchedEpisodesSafe > 0 || watchedSeasonsSafe > 0 -> 1f
        else -> 0f
    }

    val progressLabel = buildList {
        totalSeasonsSafe?.let { add("S $watchedSeasonsBounded/$it") } ?: run {
            if (watchedSeasonsBounded > 0) add("S $watchedSeasonsBounded")
        }
        currentSeasonTotalEpisodes?.let { add("E $watchedEpisodesInSeason/$it") } ?: run {
            if (watchedEpisodesSafe > 0) add("E $watchedEpisodesSafe")
        }
    }.joinToString(" • ")

    return WatchProgressUi(
        progress = progress,
        progressLabel = progressLabel
    )
}

@Composable
private fun PosterBottomProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.95f))
        )
    }
}

@Composable
private fun UserStatusBadge(
    status: UserFilmStatus,
    modifier: Modifier = Modifier
) {
    val (iconRes, description) = status.toBadgeIconAndDescription()
    Surface(
        modifier = modifier.size(36.dp),
        shape = RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 0.dp,
            bottomStart = 0.dp,
            bottomEnd = 14.dp
        ),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = description,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = description,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun UserFilmStatus.toBadgeIconAndDescription(): Pair<Int?, String> {
    return when (this) {
        UserFilmStatus.WATCHING -> android.R.drawable.ic_menu_view to "Смотрю"
        UserFilmStatus.PLANNED -> android.R.drawable.ic_menu_my_calendar to "В планах"
        UserFilmStatus.COMPLETED -> null to "Просмотрено"
        UserFilmStatus.REWATCHING -> android.R.drawable.ic_popup_sync to "Пересматриваю"
        UserFilmStatus.ON_HOLD -> android.R.drawable.ic_media_pause to "Отложено"
        UserFilmStatus.DROPPED -> android.R.drawable.ic_menu_close_clear_cancel to "Брошено"
    }
}

@Composable
private fun BottomSectionBar(
    section: MainSection,
    isAmoled: Boolean,
    onSectionSelected: (MainSection) -> Unit
) {
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val librarySelected = section == MainSection.LIBRARY
    val discoverSelected = section == MainSection.DISCOVER
    val moreSelected = section == MainSection.MORE

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp)
            .padding(top = 2.dp, bottom = 20.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 256.dp, max = 320.dp),
            shape = CircleShape,
            color = containerColor,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomSectionButton(
                    selected = librarySelected,
                    onClick = { onSectionSelected(MainSection.LIBRARY) },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Библиотека",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
                BottomSectionButton(
                    selected = discoverSelected,
                    onClick = { onSectionSelected(MainSection.DISCOVER) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Обзор",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
                BottomSectionButton(
                    selected = moreSelected,
                    onClick = { onSectionSelected(MainSection.MORE) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Ещё",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun BottomSectionButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        NavItemGlyph(
            icon = icon,
            selected = selected
        )
    }
}

@Composable
private fun NavItemGlyph(
    icon: @Composable () -> Unit,
    selected: Boolean
) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(220),
        label = "nav_bg"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "nav_tint"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.9f,
        animationSpec = tween(220),
        label = "nav_scale"
    )
    val glyphSize by animateDpAsState(
        targetValue = if (selected) 46.dp else 42.dp,
        animationSpec = tween(220),
        label = "nav_glyph_size"
    )
    Surface(
        shape = CircleShape,
        color = bg
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            Box(
                modifier = Modifier
                    .size(glyphSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Не удалось загрузить данные",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onRetry) {
                Text("Повторить")
            }
        }
    }
}

@Composable
private fun EmptyCard(
    title: String,
    message: String
) {
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun List<LibraryUiItem>.filterByQuery(query: String): List<LibraryUiItem> {
    if (query.isBlank()) return this
    return filter {
        it.title.contains(query, ignoreCase = true) ||
            (it.note?.contains(query, ignoreCase = true) == true)
    }
}

private fun List<LibraryUiItem>.filterByRussian(hideRussian: Boolean): List<LibraryUiItem> {
    if (!hideRussian) return this
    return filterNot { it.isRussian }
}

private fun List<LibraryUiItem>.filterByTab(tab: LibraryTab): List<LibraryUiItem> {
    return when (tab) {
        LibraryTab.HISTORY -> this
            .filter { it.viewedAtMillis != null }
            .sortedByDescending { it.viewedAtMillis ?: 0L }

        LibraryTab.WATCHING -> filter { it.status == UserFilmStatus.WATCHING }
        LibraryTab.PLANNED -> filter { it.status == UserFilmStatus.PLANNED }
        LibraryTab.WATCHED -> filter { it.status == UserFilmStatus.COMPLETED }
        LibraryTab.REWATCHING -> filter { it.status == UserFilmStatus.REWATCHING }
        LibraryTab.ON_HOLD -> filter { it.status == UserFilmStatus.ON_HOLD }
        LibraryTab.DROPPED -> filter { it.status == UserFilmStatus.DROPPED }
    }
}

private fun UserFilmStatus.toUiLabel(): String {
    return when (this) {
        UserFilmStatus.WATCHING -> "Смотрю"
        UserFilmStatus.PLANNED -> "В планах"
        UserFilmStatus.COMPLETED -> "Просмотрено"
        UserFilmStatus.REWATCHING -> "Пересматриваю"
        UserFilmStatus.ON_HOLD -> "Отложено"
        UserFilmStatus.DROPPED -> "Брошено"
    }
}

private fun FilmItem.isRussianContent(): Boolean {
    return countries.any { country ->
        when (country.country?.trim()?.lowercase(Locale("ru"))) {
            "россия", "ссср" -> true
            else -> false
        }
    }
}

private fun FilmTileSize.toGridMetrics(): GridMetrics {
    return when (this) {
        FilmTileSize.COMPACT -> GridMetrics(columns = 4)
        FilmTileSize.MEDIUM -> GridMetrics(columns = 3)
        FilmTileSize.LARGE -> GridMetrics(columns = 2)
        FilmTileSize.VERTICAL -> GridMetrics(columns = 1)
    }
}

private data class MoreMenuItem(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

