package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import hd.kinoshka.app.data.model.FilterItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import coil.compose.AsyncImage
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.ui.components.ExpressiveBlobLoadingIndicator
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.ui.components.SkeletonGridCard
import hd.kinoshka.app.ui.components.SkeletonGridLoading
import hd.kinoshka.app.ui.components.SkeletonListLoading
import hd.kinoshka.app.ui.components.SkeletonVerticalRow
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
    onContentTypeSelected: (ContentType) -> Unit = {},
    onOpenFilm: (FilmItem) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onDiscoverCategorySelected: (DiscoverCategory) -> Unit,
    onLoadMore: () -> Unit,
    onRemoveFromHistory: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onUpdateFilters: (SearchFilterState) -> Unit = {},
    onToggleFilterSheet: (Boolean) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    onLibrarySortSelected: (hd.kinoshka.app.data.local.LibrarySortType) -> Unit = {}
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

    var section by remember(state.tab) {
        mutableStateOf(
            when (state.tab) {
                HomeTab.HISTORY -> MainSection.LIBRARY
                HomeTab.MORE -> MainSection.MORE
                else -> MainSection.DISCOVER
            }
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
    var libraryFilter by rememberSaveable { mutableStateOf(LibraryFilterType.ALL) }
    var librarySort by rememberSaveable { mutableStateOf(hd.kinoshka.app.data.local.LibrarySortType.LAST_VIEWED) }
    val searchRowHeight = SearchChromeHeight
    val searchRowAlpha = 1f
    val normalizedQuery = state.query.trim()
    val libraryItemsByTab = remember(state.library, state.hideRussianContent, normalizedQuery, libraryFilter) {
        LibraryTab.entries.associateWith { tab ->
            state.library
                .filterByTab(tab)
                .filterByRussian(state.hideRussianContent)
                .filterByQuery(normalizedQuery)
                .filter { item ->
                    when (libraryFilter) {
                        LibraryFilterType.ALL -> true
                        LibraryFilterType.FILMS -> item.type != "ANIME"
                        LibraryFilterType.ANIME -> item.type == "ANIME"
                    }
                }
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
                            moreQuery = ""
                            onQueryChange("")
                            onTabSelected(HomeTab.MORE)
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
                            MainSection.DISCOVER -> if (state.contentType == ContentType.ANIME) "Поиск аниме" else "Поиск фильмов"
                            MainSection.MORE -> "Поиск по разделу Ещё"
                        },
                        section = section,
                        contentType = state.contentType,
                        onContentTypeSelected = onContentTypeSelected,
                        libraryFilter = libraryFilter,
                        onLibraryFilterSelected = { libraryFilter = it },
                        librarySort = librarySort,
                        onLibrarySortSelected = { sortType ->
                            librarySort = sortType
                            onLibrarySortSelected(sortType)
                        },
                        isFilterActive = state.filterState.isActive,
                        onFilterClick = { onToggleFilterSheet(true) },
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

                            LaunchedEffect(pagerState.settledPage) {
                                libraryTab = LibraryTab.entries[pagerState.settledPage]
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                LibraryTabs(
                                    pagerState = pagerState,
                                    onSelect = { target -> libraryTab = target }
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    val pageTab = LibraryTab.entries[page]
                                    val items = libraryItemsByTab[pageTab].orEmpty()
                                    AnimatedContent(
                                        targetState = state.contentType,
                                        transitionSpec = {
                                            fadeIn(animationSpec = tween(250)).togetherWith(fadeOut(animationSpec = tween(150)))
                                        },
                                        label = "libraryGridAnim"
                                    ) { _ ->
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
                        }

                        MainSection.DISCOVER -> {
                            AnimatedContent(
                                targetState = state.contentType,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(250)).togetherWith(fadeOut(animationSpec = tween(150)))
                                },
                                label = "discoverGridAnim"
                            ) { _ ->
                                DiscoverContent(
                                    state = state,
                                    sourceItems = discoverItems,
                                    metrics = discoverMetrics,
                                    statusByFilmId = statusByFilmId,
                                    progressByFilmId = progressByFilmId,
                                    onRetry = onRetry,
                                    onOpenFilm = onOpenFilm,
                                    onLoadMore = onLoadMore,
                                    onOpenCalendar = onOpenCalendar,
                                    onOpenFeed = onOpenFeed
                                )
                            }
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

    if (state.showFilterSheet) {
        SearchFilterBottomSheet(
            filterState = state.filterState,
            availableGenres = state.availableGenres,
            availableCountries = state.availableCountries,
            contentType = state.contentType,
            onApply = { newFilters -> onUpdateFilters(newFilters) },
            onDismiss = { onToggleFilterSheet(false) }
        )
    }
}

enum class LibraryFilterType(val label: String) {
    ALL("Все"),
    FILMS("Кино"),
    ANIME("Аниме")
}

@Composable
private fun SearchRow(
    query: String,
    avatar: String,
    placeholder: String,
    section: MainSection,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentType: ContentType = ContentType.FILMS,
    onContentTypeSelected: ((ContentType) -> Unit)? = null,
    libraryFilter: LibraryFilterType = LibraryFilterType.ALL,
    onLibraryFilterSelected: ((LibraryFilterType) -> Unit)? = null,
    librarySort: hd.kinoshka.app.data.local.LibrarySortType = hd.kinoshka.app.data.local.LibrarySortType.LAST_VIEWED,
    onLibrarySortSelected: ((hd.kinoshka.app.data.local.LibrarySortType) -> Unit)? = null,
    isFilterActive: Boolean = false,
    onFilterClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Поиск",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (section == MainSection.DISCOVER) {
            Spacer(modifier = Modifier.width(6.dp))

            // 1. Filter button matched to 48.dp height
            val filterBgColor by animateColorAsState(
                if (isFilterActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                animationSpec = tween(280), label = "filterBg"
            )
            val filterIconColor by animateColorAsState(
                if (isFilterActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                animationSpec = tween(280), label = "filterIcon"
            )
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onFilterClick?.invoke() },
                shape = CircleShape,
                color = filterBgColor
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    FilterTuneIcon(tint = filterIconColor)
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 2. Choice of anime or cinema (No Emojis)!
            val discoverBgColor by animateColorAsState(
                if (contentType == ContentType.ANIME) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                animationSpec = tween(280), label = "discoverBg"
            )
            val discoverTextColor by animateColorAsState(
                if (contentType == ContentType.ANIME) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                animationSpec = tween(280), label = "discoverText"
            )
            Surface(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        val next = if (contentType == ContentType.FILMS) ContentType.ANIME else ContentType.FILMS
                        onContentTypeSelected?.invoke(next)
                    },
                shape = RoundedCornerShape(24.dp),
                color = discoverBgColor
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                    AnimatedContent(
                        targetState = contentType,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.88f, animationSpec = tween(220))) togetherWith
                            (fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.88f, animationSpec = tween(180)))
                        },
                        label = "contentTypeAnim"
                    ) { targetType ->
                        Text(
                            text = if (targetType == ContentType.FILMS) "Кино" else "Аниме",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = discoverTextColor
                        )
                    }
                }
            }
        } else if (section == MainSection.LIBRARY) {
            Spacer(modifier = Modifier.width(6.dp))

            // Sort selector button
            var showSortMenu by remember { mutableStateOf(false) }
            val sortBgColor by animateColorAsState(
                targetValue = MaterialTheme.colorScheme.surfaceContainerHigh,
                animationSpec = tween(280), label = "sortBg"
            )
            Box {
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { showSortMenu = true },
                    shape = CircleShape,
                    color = sortBgColor
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Сортировка",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    hd.kinoshka.app.data.local.LibrarySortType.entries.forEach { sortType ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = sortType.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (librarySort == sortType) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                if (librarySort == sortType) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = {
                                onLibrarySortSelected?.invoke(sortType)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Library filter switcher [Все] [Кино] [Аниме]
            val libBgColor by animateColorAsState(
                when (libraryFilter) {
                    LibraryFilterType.ALL -> MaterialTheme.colorScheme.surfaceContainerHigh
                    LibraryFilterType.FILMS -> MaterialTheme.colorScheme.primaryContainer
                    LibraryFilterType.ANIME -> MaterialTheme.colorScheme.tertiaryContainer
                },
                animationSpec = tween(280), label = "libBg"
            )
            val libTextColor by animateColorAsState(
                when (libraryFilter) {
                    LibraryFilterType.ALL -> MaterialTheme.colorScheme.onSurface
                    LibraryFilterType.FILMS -> MaterialTheme.colorScheme.onPrimaryContainer
                    LibraryFilterType.ANIME -> MaterialTheme.colorScheme.onTertiaryContainer
                },
                animationSpec = tween(280), label = "libText"
            )
            Surface(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        val next = when (libraryFilter) {
                            LibraryFilterType.ALL -> LibraryFilterType.FILMS
                            LibraryFilterType.FILMS -> LibraryFilterType.ANIME
                            LibraryFilterType.ANIME -> LibraryFilterType.ALL
                        }
                        onLibraryFilterSelected?.invoke(next)
                    },
                shape = RoundedCornerShape(24.dp),
                color = libBgColor
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                    AnimatedContent(
                        targetState = libraryFilter,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.88f, animationSpec = tween(220))) togetherWith
                            (fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.88f, animationSpec = tween(180)))
                        },
                        label = "libFilterAnim"
                    ) { targetFilter ->
                        Text(
                            text = targetFilter.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = libTextColor
                        )
                    }
                }
            }
        } else if (section == MainSection.MORE) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                AvatarBadge(avatar = avatar)
            }
        }
    }
}

@Composable
private fun AvatarBadge(avatar: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        if (avatar.startsWith("content://") || avatar.startsWith("file://") || avatar.startsWith("http")) {
            KinoshkaAsyncImage(
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

@Composable
private fun LibraryTabs(
    pagerState: PagerState,
    onSelect: (LibraryTab) -> Unit
) {
    val scope = rememberCoroutineScope()

    ScrollableTabRow(
        selectedTabIndex = pagerState.currentPage,
        edgePadding = 10.dp,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            if (pagerState.currentPage < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        divider = {},
        modifier = Modifier.fillMaxWidth()
    ) {
        LibraryTab.entries.forEachIndexed { index, tab ->
            val isSelected = pagerState.currentPage == index
            Tab(
                selected = isSelected,
                onClick = {
                    onSelect(tab)
                    scope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
                modifier = Modifier.height(40.dp),
                text = {
                    Text(
                        text = tab.title,
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
private fun ContentTypeTabs(
    selectedType: ContentType,
    onSelect: (ContentType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val types = listOf(
            ContentType.FILMS to "🎬 Фильмы и сериалы",
            ContentType.ANIME to "⛩️ Аниме"
        )
        types.forEach { (type, label) ->
            val isSelected = selectedType == type
            val backgroundColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                label = "bgColor"
            )
            val contentColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "contentColor"
            )
            Surface(
                onClick = { onSelect(type) },
                shape = RoundedCornerShape(14.dp),
                color = backgroundColor,
                contentColor = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    )
                }
            }
        }
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
    onOpenCalendar: () -> Unit,
    onOpenFeed: () -> Unit
) {
    when {
        state.loading -> {
            if (metrics.columns == 1) {
                SkeletonListLoading(contentPadding = PaddingValues(bottom = FloatingBottomContentPadding))
            } else {
                SkeletonGridLoading(columns = metrics.columns, contentPadding = PaddingValues(bottom = FloatingBottomContentPadding))
            }
        }
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
                    if (state.contentType == ContentType.ANIME) {
                        item(key = "anime_header_buttons") {
                            AnimeHeaderButtonsRow(onOpenCalendar = onOpenCalendar, onOpenFeed = onOpenFeed)
                        }
                    }
                    itemsIndexed(
                        items = sourceItems,
                        key = { _, film -> film.kinopoiskId },
                        contentType = { _, _ -> "discover_vertical_item" }
                    ) { index, film ->
                        if (index >= sourceItems.size - 3 && state.hasMore && !state.loadingMore && !state.loading) {
                            LaunchedEffect(Unit) {
                                onLoadMore()
                            }
                        }
                        DiscoverVerticalRow(
                            film = film,
                            status = statusByFilmId[film.kinopoiskId],
                            watchProgress = progressByFilmId[film.kinopoiskId],
                            onOpenFilm = onOpenFilm
                        )
                    }
                    if (state.loadingMore) {
                        items(4, key = { "skeleton_more_$it" }) {
                            SkeletonVerticalRow()
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
                if (state.contentType == ContentType.ANIME) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "anime_header_buttons") {
                        AnimeHeaderButtonsRow(onOpenCalendar = onOpenCalendar, onOpenFeed = onOpenFeed)
                    }
                }
                gridItemsIndexed(
                    items = sourceItems,
                    key = { _, film -> film.kinopoiskId },
                    contentType = { _, _ -> "discover_item" }
                ) { index, film ->
                    if (index >= sourceItems.size - (metrics.columns * 2) && state.hasMore && !state.loadingMore && !state.loading) {
                        LaunchedEffect(Unit) {
                            onLoadMore()
                        }
                    }
                    DiscoverGridCard(
                        film = film,
                        compactText = metrics.columns >= 3,
                        status = statusByFilmId[film.kinopoiskId],
                        watchProgress = progressByFilmId[film.kinopoiskId],
                        onOpenFilm = onOpenFilm
                    )
                }
                if (state.loadingMore) {
                    items(metrics.columns * 2, key = { "skeleton_more_$it" }) {
                        SkeletonGridCard(compactText = metrics.columns >= 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeHeaderButtonsRow(
    onOpenCalendar: () -> Unit,
    onOpenFeed: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .clickable(onClick = onOpenCalendar),
            shape = RoundedCornerShape(21.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Календарь релизов",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .clickable(onClick = onOpenFeed),
            shape = RoundedCornerShape(21.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Лента релизов",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
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
            .clip(RoundedCornerShape(22.dp))
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
    val isAnime = film.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET
    val metaText = remember(film.year, film.countries, film.kinopoiskId) {
        val extra = film.countries.getOrNull(1)?.country
        listOfNotNull(
            film.year?.toString().takeUnless { isAnime },
            extra.takeIf { isAnime }
        ).joinToString(" • ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onOpenFilm(film) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            KinoshkaAsyncImage(
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RatingChip(rating = film.ratingKinopoisk, isAnime = isAnime)
            }
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
    val isAnime = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET || item.type == "ANIME"
    val detailsText = remember(item.type, item.totalEpisodes, item.ratingText, item.subtitle, isAnime) {
        if (isAnime) {
            val typeStr = if (item.totalEpisodes != null && item.totalEpisodes > 1) "TV" else "Фильм"
            val epStr = item.totalEpisodes?.let { "$it эп." }
            val rateStr = item.ratingText?.takeIf { it.isNotBlank() }?.let { "★ " + it.replace("KP ", "").replace("★", "").trim() }
            listOfNotNull(typeStr, epStr, rateStr).joinToString(" • ")
        } else {
            listOfNotNull(
                item.subtitle,
                item.ratingText?.replace("KP ", "★ ")
            ).joinToString(" • ")
        }
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
            KinoshkaAsyncImage(
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
            KinoshkaAsyncImage(
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
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {}
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
    val isAnime = film.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET
    val metaText = remember(film.year, film.countries, film.kinopoiskId) {
        val extra = film.countries.getOrNull(1)?.country
        listOfNotNull(
            film.year?.toString().takeUnless { isAnime },
            extra.takeIf { isAnime }
        ).joinToString(" · ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
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
            KinoshkaAsyncImage(
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RatingChip(rating = film.ratingKinopoisk, isAnime = isAnime)
            }
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

@Composable
private fun RatingChip(
    rating: Double?,
    isAnime: Boolean,
    modifier: Modifier = Modifier
) {
    val r = rating ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "%.1f".format(Locale.US, r),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private data class WatchProgressUi(
    val progress: Float,
    val progressLabel: String
)

private fun LibraryUiItem.toWatchProgressUi(): WatchProgressUi? {
    if (type != "TV_SERIES" && type != "ANIME") return null

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

        watchedEpisodesSafe > 0 || watchedSeasonsSafe > 0 -> {
            if (totalEpisodesSafe != null && totalEpisodesSafe > 0) {
                (watchedEpisodesSafe.toFloat() / totalEpisodesSafe.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
        else -> 0f
    }

    val progressLabel = buildList {
        totalSeasonsSafe?.let { add("Сезон $watchedSeasonsBounded из $it") } ?: run {
            if (watchedSeasonsBounded > 0) add("Сезон $watchedSeasonsBounded")
        }
        totalEpisodesSafe?.let { add("Серия $watchedEpisodesSafe из $it") } ?: currentSeasonTotalEpisodes?.let { add("Серия $watchedEpisodesInSeason из $it") } ?: run {
            if (watchedEpisodesSafe > 0) add("Серия $watchedEpisodesSafe")
        }
    }.joinToString(", ")

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
            modifier = Modifier.widthIn(min = 260.dp, max = 300.dp),
            shape = CircleShape,
            color = containerColor,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomSectionButton(
                    selected = librarySelected,
                    onClick = { onSectionSelected(MainSection.LIBRARY) },
                    icon = { selected ->
                        Icon(
                            painter = painterResource(
                                if (selected) hd.kinoshka.app.R.drawable.ic_nav_library_filled
                                else hd.kinoshka.app.R.drawable.ic_nav_library_outlined
                            ),
                            contentDescription = "Библиотека",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                )
                BottomSectionButton(
                    selected = discoverSelected,
                    onClick = { onSectionSelected(MainSection.DISCOVER) },
                    icon = { selected ->
                        Icon(
                            painter = painterResource(
                                if (selected) hd.kinoshka.app.R.drawable.ic_nav_discover_filled
                                else hd.kinoshka.app.R.drawable.ic_nav_discover_outlined
                            ),
                            contentDescription = "Обзор",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                )
                BottomSectionButton(
                    selected = moreSelected,
                    onClick = { onSectionSelected(MainSection.MORE) },
                    icon = { selected ->
                        Icon(
                            painter = painterResource(
                                if (selected) hd.kinoshka.app.R.drawable.ic_nav_more_filled
                                else hd.kinoshka.app.R.drawable.ic_nav_more_outlined
                            ),
                            contentDescription = "Ещё",
                            modifier = Modifier.size(28.dp)
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
    icon: @Composable (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        NavItemGlyph(
            icon = { icon(selected) },
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
        targetValue = if (selected) 50.dp else 46.dp,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Нет подключения",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Проверьте соединение с интернетом и попробуйте снова",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Повторить")
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

@Composable
private fun FilterTuneIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1.6.dp.toPx()
        val circleRadius = 2.5.dp.toPx()

        // Top line
        val y1 = h * 0.25f
        val x1 = w * 0.35f
        drawLine(tint, Offset(0f, y1), Offset(w, y1), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawCircle(tint, radius = circleRadius, center = Offset(x1, y1))

        // Middle line
        val y2 = h * 0.5f
        val x2 = w * 0.70f
        drawLine(tint, Offset(0f, y2), Offset(w, y2), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawCircle(tint, radius = circleRadius, center = Offset(x2, y2))

        // Bottom line
        val y3 = h * 0.75f
        val x3 = w * 0.45f
        drawLine(tint, Offset(0f, y3), Offset(w, y3), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawCircle(tint, radius = circleRadius, center = Offset(x3, y3))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterBottomSheet(
    filterState: SearchFilterState,
    availableGenres: List<FilterItem>,
    availableCountries: List<FilterItem>,
    contentType: ContentType,
    onApply: (SearchFilterState) -> Unit,
    onDismiss: () -> Unit
) {
    var tempState by remember(filterState) { mutableStateOf<SearchFilterState>(filterState) }
    var isGenresExpanded by remember { mutableStateOf(false) }
    var isCountriesExpanded by remember { mutableStateOf(false) }
    var countrySearchQuery by remember { mutableStateOf("") }

    val sortedGenres = remember(availableGenres) {
        availableGenres.sortedBy { it.genre.orEmpty().lowercase(Locale("ru")) }
    }
    val sortedCountries = remember(availableCountries) {
        availableCountries.sortedBy { it.country.orEmpty().lowercase(Locale("ru")) }
    }
    val filteredCountries = remember(sortedCountries, countrySearchQuery) {
        if (countrySearchQuery.isBlank()) sortedCountries
        else sortedCountries.filter { it.country.orEmpty().contains(countrySearchQuery.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (contentType == ContentType.ANIME) "Фильтры аниме" else "Фильтры поиска",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (tempState.isActive) {
                    TextButton(
                        onClick = {
                            tempState = SearchFilterState()
                            countrySearchQuery = ""
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Сбросить", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (contentType == ContentType.ANIME) {
                    // 1. Тип
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Тип", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        val rawKinds = listOf(null to "Все", "tv" to "ТВ", "movie" to "Фильм", "ova" to "OVA", "ona" to "ONA", "special" to "Спешл")
                        val kinds = remember(tempState.animeKind) {
                            if (tempState.animeKind == null) rawKinds
                            else listOf(rawKinds.first { it.first == tempState.animeKind }) + rawKinds.filter { it.first != tempState.animeKind }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(kinds) { (code, label) ->
                                FilterChip(
                                    selected = tempState.animeKind == code,
                                    onClick = { tempState = tempState.copy(animeKind = code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    // 2. Статус
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Статус выхода", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        val rawStatuses = listOf(null to "Все", "released" to "Вышло", "ongoing" to "Онгоинг", "anons" to "Анонс")
                        val statuses = remember(tempState.animeStatus) {
                            if (tempState.animeStatus == null) rawStatuses
                            else listOf(rawStatuses.first { it.first == tempState.animeStatus }) + rawStatuses.filter { it.first != tempState.animeStatus }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(statuses) { (code, label) ->
                                FilterChip(
                                    selected = tempState.animeStatus == code,
                                    onClick = { tempState = tempState.copy(animeStatus = code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    // 3. Сортировка
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Сортировка", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        val rawOrders = listOf(
                            "popularity" to "Популярность",
                            "ranked" to "Рейтинг",
                            "aired_on" to "Дата выхода",
                            "name" to "Алфавит",
                            "random" to "Случайно"
                        )
                        val orders = remember(tempState.animeOrder) {
                            val active = tempState.animeOrder
                            if (active == null) rawOrders
                            else listOf(rawOrders.first { it.first == active }) + rawOrders.filter { it.first != active }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(orders) { (code, label) ->
                                FilterChip(
                                    selected = tempState.animeOrder == code,
                                    onClick = { tempState = tempState.copy(animeOrder = code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    // 4. Оценка
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Минимальная оценка", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        val rawScores = listOf(null to "Любая", 6 to "6+ ★", 7 to "7+ ★", 8 to "8+ ★", 9 to "9+ ★")
                        val scores = remember(tempState.animeScoreFrom) {
                            if (tempState.animeScoreFrom == null) rawScores
                            else listOf(rawScores.first { it.first == tempState.animeScoreFrom }) + rawScores.filter { it.first != tempState.animeScoreFrom }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(scores) { (score, label) ->
                                FilterChip(
                                    selected = tempState.animeScoreFrom == score,
                                    onClick = { tempState = tempState.copy(animeScoreFrom = score) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    // 5. Возрастной рейтинг
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Возраст", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        val rawRatings = listOf(
                            null to "Все",
                            "g" to "0+",
                            "pg" to "6+",
                            "pg_13" to "13+",
                            "r" to "17+",
                            "r_plus" to "18+"
                        )
                        val ratings = remember(tempState.animeRating) {
                            if (tempState.animeRating == null) rawRatings
                            else listOf(rawRatings.first { it.first == tempState.animeRating }) + rawRatings.filter { it.first != tempState.animeRating }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ratings) { (code, label) ->
                                FilterChip(
                                    selected = tempState.animeRating == code,
                                    onClick = { tempState = tempState.copy(animeRating = code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    // 6. Жанр
                    Column(modifier = Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Жанр", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { isGenresExpanded = !isGenresExpanded }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (isGenresExpanded) "Свернуть ▲" else "Все (${shikimoriGenres.size}) ▼", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val sortedShikimoriGenres = remember(shikimoriGenres, tempState.animeGenreId) {
                            if (tempState.animeGenreId == null) shikimoriGenres
                            else shikimoriGenres.sortedByDescending { it.id == tempState.animeGenreId }
                        }
                        if (!isGenresExpanded) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    FilterChip(
                                        selected = tempState.animeGenreId == null,
                                        onClick = { tempState = tempState.copy(animeGenreId = null) },
                                        label = { Text("Все", style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                                items(sortedShikimoriGenres) { genre ->
                                    FilterChip(
                                        selected = tempState.animeGenreId == genre.id,
                                        onClick = { tempState = tempState.copy(animeGenreId = genre.id) },
                                        label = { Text(genre.genre.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = tempState.animeGenreId == null,
                                    onClick = { tempState = tempState.copy(animeGenreId = null) },
                                    label = { Text("Все", style = MaterialTheme.typography.bodySmall) }
                                )
                                sortedShikimoriGenres.forEach { genre ->
                                    FilterChip(
                                        selected = tempState.animeGenreId == genre.id,
                                        onClick = { tempState = tempState.copy(animeGenreId = genre.id) },
                                        label = { Text(genre.genre.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Фильмы / Сериалы
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Тип контента", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        val rawTypes = listOf("ALL" to "Все", "FILM" to "Фильмы", "TV_SERIES" to "Сериалы")
                        val types = remember(tempState.selectedType) {
                            val active = tempState.selectedType ?: "ALL"
                            listOf(rawTypes.first { it.first == active }) + rawTypes.filter { it.first != active }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(types) { (code, label) ->
                                FilterChip(
                                    selected = tempState.selectedType == code,
                                    onClick = { tempState = tempState.copy(selectedType = code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Сортировка", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        val rawOrders = listOf("RATING" to "Рейтинг", "NUM_VOTE" to "Популярность", "YEAR" to "Дата")
                        val orders = remember(tempState.selectedOrder) {
                            val active = tempState.selectedOrder
                            if (active == null) rawOrders
                            else listOf(rawOrders.first { it.first == active }) + rawOrders.filter { it.first != active }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(orders) { (code, label) ->
                                FilterChip(
                                    selected = tempState.selectedOrder == code,
                                    onClick = { tempState = tempState.copy(selectedOrder = code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Минимальный рейтинг", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(tempState.ratingFrom?.let { "от $it★" } ?: "Любой", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = (tempState.ratingFrom ?: 0).toFloat(),
                            onValueChange = { valFloat ->
                                val v = valFloat.toInt()
                                tempState = tempState.copy(ratingFrom = if (v <= 0) null else v)
                            },
                            valueRange = 0f..9f,
                            steps = 8,
                            thumb = {
                                Surface(
                                    modifier = Modifier.size(8.dp, 20.dp),
                                    shape = RoundedCornerShape(3.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    shadowElevation = 2.dp
                                ) {}
                            }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val currentYearFrom = (tempState.yearFrom ?: 1980).toFloat()
                        val currentYearTo = (tempState.yearTo ?: 2026).toFloat()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Годы выпуска", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (tempState.yearFrom == null && tempState.yearTo == null) "Все"
                                else "${currentYearFrom.toInt()} — ${currentYearTo.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        RangeSlider(
                            value = currentYearFrom..currentYearTo,
                            onValueChange = { range ->
                                val yFrom = if (range.start <= 1980f) null else range.start.toInt()
                                val yTo = if (range.endInclusive >= 2026f) null else range.endInclusive.toInt()
                                tempState = tempState.copy(yearFrom = yFrom, yearTo = yTo)
                            },
                            valueRange = 1980f..2026f,
                            steps = 45,
                            startThumb = {
                                Surface(
                                    modifier = Modifier.size(8.dp, 20.dp),
                                    shape = RoundedCornerShape(3.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    shadowElevation = 2.dp
                                ) {}
                            },
                            endThumb = {
                                Surface(
                                    modifier = Modifier.size(8.dp, 20.dp),
                                    shape = RoundedCornerShape(3.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    shadowElevation = 2.dp
                                ) {}
                            }
                        )
                    }

                    if (sortedGenres.isNotEmpty()) {
                        Column(modifier = Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Жанр", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { isGenresExpanded = !isGenresExpanded }, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (isGenresExpanded) "Свернуть ▲" else "Все (${sortedGenres.size}) ▼", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            val displayGenres = remember(sortedGenres, tempState.selectedGenreId) {
                                if (tempState.selectedGenreId == null) sortedGenres
                                else sortedGenres.sortedByDescending { it.id == tempState.selectedGenreId }
                            }
                            if (!isGenresExpanded) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item {
                                        FilterChip(
                                            selected = tempState.selectedGenreId == null,
                                            onClick = { tempState = tempState.copy(selectedGenreId = null) },
                                            label = { Text("Все", style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                    items(displayGenres) { genre ->
                                        FilterChip(
                                            selected = tempState.selectedGenreId == genre.id,
                                            onClick = { tempState = tempState.copy(selectedGenreId = genre.id) },
                                            label = { Text(genre.genre.orEmpty().replaceFirstChar { char -> char.uppercase() }, style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                }
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(
                                        selected = tempState.selectedGenreId == null,
                                        onClick = { tempState = tempState.copy(selectedGenreId = null) },
                                        label = { Text("Все", style = MaterialTheme.typography.bodySmall) }
                                    )
                                    displayGenres.forEach { genre ->
                                        FilterChip(
                                            selected = tempState.selectedGenreId == genre.id,
                                            onClick = { tempState = tempState.copy(selectedGenreId = genre.id) },
                                            label = { Text(genre.genre.orEmpty().replaceFirstChar { char -> char.uppercase() }, style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (sortedCountries.isNotEmpty()) {
                        Column(modifier = Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Страна", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { isCountriesExpanded = !isCountriesExpanded }, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (isCountriesExpanded) "Свернуть ▲" else "Все (${sortedCountries.size}) ▼", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (isCountriesExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            if (countrySearchQuery.isEmpty()) {
                                                Text(
                                                    text = "Поиск...",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                )
                                            }
                                            BasicTextField(
                                                value = countrySearchQuery,
                                                onValueChange = { countrySearchQuery = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
                                            )
                                        }
                                        if (countrySearchQuery.isNotEmpty()) {
                                            TextButton(
                                                onClick = { countrySearchQuery = "" },
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val displayCountries = remember(filteredCountries, tempState.selectedCountryId) {
                                if (tempState.selectedCountryId == null) filteredCountries
                                else filteredCountries.sortedByDescending { it.id == tempState.selectedCountryId }
                            }
                            if (!isCountriesExpanded) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item {
                                        FilterChip(
                                            selected = tempState.selectedCountryId == null,
                                            onClick = { tempState = tempState.copy(selectedCountryId = null) },
                                            label = { Text("Все", style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                    items(displayCountries) { country ->
                                        FilterChip(
                                            selected = tempState.selectedCountryId == country.id,
                                            onClick = { tempState = tempState.copy(selectedCountryId = country.id) },
                                            label = { Text(country.country.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                }
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(
                                        selected = tempState.selectedCountryId == null,
                                        onClick = { tempState = tempState.copy(selectedCountryId = null) },
                                        label = { Text("Все", style = MaterialTheme.typography.bodySmall) }
                                    )
                                    displayCountries.forEach { country ->
                                        FilterChip(
                                            selected = tempState.selectedCountryId == country.id,
                                            onClick = { tempState = tempState.copy(selectedCountryId = country.id) },
                                            label = { Text(country.country.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    onApply(tempState)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(23.dp)
            ) {
                Text("Показать результаты", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

