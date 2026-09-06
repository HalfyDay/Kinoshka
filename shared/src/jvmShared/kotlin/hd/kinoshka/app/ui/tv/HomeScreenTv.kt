package hd.kinoshka.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.local.LibrarySortType
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.ui.platform.KinoBackHandler
import hd.kinoshka.app.ui.platform.rememberKinoPlatformActions
import hd.kinoshka.app.ui.screens.ContentType
import hd.kinoshka.app.ui.screens.DiscoverCategory
import hd.kinoshka.app.ui.screens.HomeScreen
import hd.kinoshka.app.ui.screens.HomeTab
import hd.kinoshka.app.ui.screens.HomeUiState
import hd.kinoshka.app.ui.screens.LibraryTab
import hd.kinoshka.app.ui.screens.LibraryUiItem
import hd.kinoshka.app.ui.screens.ProgressEditorSeed
import hd.kinoshka.app.ui.screens.SearchFilterState
import hd.kinoshka.app.ui.screens.filterByQuery
import hd.kinoshka.app.ui.screens.filterByRussian
import hd.kinoshka.app.ui.screens.filterByTab
import hd.kinoshka.app.ui.screens.hasNewEpisode
import hd.kinoshka.app.ui.screens.isRussianContent
import hd.kinoshka.app.ui.screens.libraryMetaParts
import hd.kinoshka.app.ui.screens.libraryRating
import hd.kinoshka.app.ui.screens.toWatchProgressUi
import kotlinx.coroutines.delay

/** Разделы TV-главной: топ-бар вместо нижней пилюли. Лента — переход на отдельный маршрут. */
private enum class TvHomeSection(val label: String) {
    LIBRARY("Библиотека"),
    DISCOVER("Обзор"),
    MORE("Ещё")
}

/**
 * Главная в TV-стиле (ПК/планшет landscape/ТВ): топ-навигация, баннер-герой с продолжением
 * просмотра, горизонтальные ряды постеров, крупная сетка библиотеки. Состояние и колбэки —
 * те же, что у телефонного [HomeScreen]: экран только перерисовывает HomeUiState.
 */
@Composable
fun HomeScreenTv(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onRetry: () -> Unit,
    onTabSelected: (HomeTab) -> Unit,
    onContentTypeSelected: (ContentType) -> Unit = {},
    onOpenFilm: (FilmItem) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit = {},
    onDiscoverCategorySelected: (DiscoverCategory) -> Unit,
    onLoadMore: () -> Unit,
    onRemoveFromHistory: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onUpdateFilters: (SearchFilterState) -> Unit = {},
    onToggleFilterSheet: (Boolean) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    onOpenRecommendationsFeed: () -> Unit = {},
    onLibrarySortSelected: (LibrarySortType) -> Unit = {},
    librarySortReversed: Boolean = false,
    onLibrarySortReversedChanged: (Boolean) -> Unit = {},
    onHentaiVisibilityChanged: (Boolean) -> Unit = {},
    onInstantSearch: (String) -> Unit = {},
    onRemoveSearchHistory: (String) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    // Android-only возможности (Загрузки, Профиль, TikTok-лента): на desktop их экранов нет,
    // соответствующие точки входа скрываются.
    androidFeaturesAvailable: Boolean = true
) {
    var section by remember { mutableStateOf(TvHomeSection.DISCOVER) }
    var discoverQuery by rememberSaveable { mutableStateOf("") }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.WATCHING) }
    var libraryFilterAll by rememberSaveable { mutableStateOf(true) }
    var sortIndex by rememberSaveable { mutableStateOf(0) }
    val platformActions = rememberKinoPlatformActions()

    val normalizedQuery = state.query.trim()
    val isSearchActive = section == TvHomeSection.DISCOVER && discoverQuery.isNotBlank()

    // Instant search в Обзоре — как на телефоне: дебаунс ~350 мс при наборе.
    LaunchedEffect(discoverQuery, section) {
        if (section == TvHomeSection.DISCOVER && discoverQuery.trim().length >= 2) {
            delay(350)
            onInstantSearch(discoverQuery)
        }
    }

    // Root-экран: первый «Назад» показывает подсказку, второй в окне подтверждения закрывает.
    var lastBackExitAttemptAt by remember { mutableStateOf(0L) }
    KinoBackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        if (now - lastBackExitAttemptAt < 2_000L) {
            platformActions.exitApp()
        } else {
            lastBackExitAttemptAt = now
            platformActions.showToast("Повторите «Назад», чтобы закрыть приложение")
        }
    }

    val libraryItems = remember(state.library, state.hideRussianContent, normalizedQuery, libraryTab, libraryFilterAll) {
        state.library
            .filterByRussian(state.hideRussianContent)
            .filterByTab(libraryTab)
            .filterByQuery(normalizedQuery)
            .filter { libraryFilterAll || it.type != "ANIME" }
    }
    val discoverItems = remember(state.items, state.hideRussianContent) {
        if (state.hideRussianContent) state.items.filterNot { it.isRussianContent() } else state.items
    }
    val continueWatching = remember(state.library, state.hideRussianContent) {
        state.library
            .filterByRussian(state.hideRussianContent)
            .firstOrNull { it.status == UserFilmStatus.WATCHING || it.status == UserFilmStatus.REWATCHING }
            ?: state.library.firstOrNull { it.viewedAtMillis != null }
    }

    // Фоновый бэкдроп Обзора: постер тайтла под фокусом (Lampa .full-start__background).
    var discoverBackdropUrl by remember { mutableStateOf<String?>(null) }
    val windowSize = rememberTvWindowSize()
    val hPad = when (windowSize) {
        TvWindowSize.COMPACT -> 16.dp
        TvWindowSize.MEDIUM -> 24.dp
        TvWindowSize.EXPANDED -> 36.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TvAnimatedBackdrop(
            imageUrl = discoverBackdropUrl.takeIf { section == TvHomeSection.DISCOVER },
            modifier = Modifier.fillMaxSize(),
        )
        // Единая focusGroup на весь экран: стрелки клавиатуры (ПК/планшет с
        // клавиатурой) ходят по фокусу так же, как D-pad на ТВ — вверх/вниз между
        // топ-баром, рядами и сетками, влево/вправо внутри рядов.
        Column(modifier = Modifier.fillMaxSize().focusGroup()) {
        // Индексы зависят от того, есть ли Android-only «Лента» между «Обзор» и «Ещё».
        val feedIndex = if (androidFeaturesAvailable) 2 else null
        val moreIndex = if (androidFeaturesAvailable) 3 else 2
        TvTopBar(
            sections = buildList {
                add(TvHomeSection.LIBRARY.label)
                add(TvHomeSection.DISCOVER.label)
                if (androidFeaturesAvailable) add("Лента")
                add(TvHomeSection.MORE.label)
            },
            selectedSection = when (section) {
                TvHomeSection.LIBRARY -> 0
                TvHomeSection.DISCOVER -> 1
                TvHomeSection.MORE -> moreIndex
            },
            onSectionSelected = { index ->
                if (index == feedIndex) {
                    onOpenRecommendationsFeed()
                } else {
                    val target = when (index) {
                        0 -> TvHomeSection.LIBRARY
                        moreIndex -> TvHomeSection.MORE
                        else -> TvHomeSection.DISCOVER
                    }
                    if (target != section) {
                        if (target != TvHomeSection.DISCOVER) {
                            discoverQuery = ""
                            onQueryChange("")
                            onTabSelected(if (target == TvHomeSection.LIBRARY) HomeTab.HISTORY else HomeTab.MORE)
                        } else {
                            onQueryChange(discoverQuery)
                            onTabSelected(HomeTab.CATALOG)
                        }
                        section = target
                    }
                }
            },
            query = when (section) {
                TvHomeSection.DISCOVER -> discoverQuery
                TvHomeSection.LIBRARY -> libraryQuery
                TvHomeSection.MORE -> ""
            },
            onQueryChange = { value ->
                when (section) {
                    TvHomeSection.DISCOVER -> {
                        discoverQuery = value
                        onQueryChange(value)
                    }
                    TvHomeSection.LIBRARY -> {
                        libraryQuery = value
                        onQueryChange(value)
                    }
                    TvHomeSection.MORE -> {}
                }
            },
            onSearchSubmit = onSubmitSearch,
            searchPlaceholder = when (section) {
                TvHomeSection.DISCOVER -> "Поиск фильмов и аниме"
                TvHomeSection.LIBRARY -> "Поиск в библиотеке"
                TvHomeSection.MORE -> "Поиск"
            },
            onAvatarClick = onOpenProfile,
            showAvatar = androidFeaturesAvailable,
        )

        when (section) {
            TvHomeSection.DISCOVER -> DiscoverTvContent(
                state = state,
                items = discoverItems,
                continueWatching = continueWatching,
                query = discoverQuery,
                normalizedQuery = normalizedQuery,
                onOpenFilm = onOpenFilm,
                onOpenHistoryFilm = onOpenHistoryFilm,
                onDiscoverCategorySelected = onDiscoverCategorySelected,
                onLoadMore = onLoadMore,
                onOpenCalendar = onOpenCalendar,
                onOpenFeed = onOpenFeed,
                onRetry = onRetry,
                onContentTypeSelected = onContentTypeSelected,
                onUpdateFilters = onUpdateFilters,
                onToggleFilterSheet = onToggleFilterSheet,
                onItemFocused = { url -> discoverBackdropUrl = url },
            )
            TvHomeSection.LIBRARY -> LibraryTvContent(
                state = state,
                items = libraryItems,
                selectedTab = libraryTab,
                onTabSelected = {
                    libraryTab = it
                    libraryQuery = ""
                    onQueryChange(libraryQuery)
                },
                filterAll = libraryFilterAll,
                onFilterAllChanged = { libraryFilterAll = it },
                sort = LibrarySortType.entries[sortIndex.coerceIn(0, LibrarySortType.entries.lastIndex)],
                onSortSelected = { sortType ->
                    sortIndex = LibrarySortType.entries.indexOf(sortType)
                    onLibrarySortSelected(sortType)
                },
                onOpenHistoryFilm = onOpenHistoryFilm,
                onOpenFilmEditor = onOpenFilmEditor,
                onRemoveFromHistory = onRemoveFromHistory,
                onHentaiVisibilityChanged = onHentaiVisibilityChanged,
            )
            TvHomeSection.MORE -> MoreTvContent(
                onOpenProfile = onOpenProfile,
                onOpenSettings = onOpenSettings,
                onOpenAbout = onOpenAbout,
                onOpenDownloads = onOpenDownloads,
                androidFeaturesAvailable = androidFeaturesAvailable,
            )
        }
        }
    }
}

@Composable
private fun DiscoverTvContent(
    state: HomeUiState,
    items: List<FilmItem>,
    continueWatching: LibraryUiItem?,
    query: String,
    normalizedQuery: String,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onDiscoverCategorySelected: (DiscoverCategory) -> Unit,
    onLoadMore: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenFeed: () -> Unit,
    onRetry: () -> Unit,
    onContentTypeSelected: (ContentType) -> Unit,
    onUpdateFilters: (SearchFilterState) -> Unit,
    onToggleFilterSheet: (Boolean) -> Unit,
    onItemFocused: (String?) -> Unit,
) {
    // Адаптив под ширину окна (включая ультраширокие мониторы): карточки и
    // отступы растут вместе с экраном, ряды всегда заполняют ширину целиком.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowSize = rememberTvWindowSize()
        val hPad = tvHPadFor(maxWidth, windowSize)
        val cardWidth = tvCardWidthFor(maxWidth, hPad, windowSize)
        DiscoverTvBody(
            state = state,
            items = items,
            continueWatching = continueWatching,
            query = query,
            normalizedQuery = normalizedQuery,
            hPad = hPad,
            cardWidth = cardWidth,
            onOpenFilm = onOpenFilm,
            onOpenHistoryFilm = onOpenHistoryFilm,
            onDiscoverCategorySelected = onDiscoverCategorySelected,
            onLoadMore = onLoadMore,
            onOpenCalendar = onOpenCalendar,
            onOpenFeed = onOpenFeed,
            onRetry = onRetry,
            onContentTypeSelected = onContentTypeSelected,
            onUpdateFilters = onUpdateFilters,
            onToggleFilterSheet = onToggleFilterSheet,
            onItemFocused = onItemFocused,
        )
    }
}

@Composable
private fun DiscoverTvBody(
    state: HomeUiState,
    items: List<FilmItem>,
    continueWatching: LibraryUiItem?,
    query: String,
    normalizedQuery: String,
    hPad: androidx.compose.ui.unit.Dp,
    cardWidth: androidx.compose.ui.unit.Dp,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onDiscoverCategorySelected: (DiscoverCategory) -> Unit,
    onLoadMore: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenFeed: () -> Unit,
    onRetry: () -> Unit,
    onContentTypeSelected: (ContentType) -> Unit,
    onUpdateFilters: (SearchFilterState) -> Unit,
    onToggleFilterSheet: (Boolean) -> Unit,
    onItemFocused: (String?) -> Unit,
) {
    // При входе в Обзор фон = постер героя (продолжение просмотра), пока фокус
    // не перейдёт на конкретную карточку.
    LaunchedEffect(continueWatching?.kinopoiskId) {
        if (continueWatching != null) onItemFocused(continueWatching.posterUrl)
    }
    if (state.loading && items.isEmpty()) {
        TvEmpty("Загрузка…", Modifier.fillMaxSize())
        return
    }
    if (state.error != null && items.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
            )
            TvButton("Повторить", onClick = onRetry)
        }
        return
    }

    if (normalizedQuery.isNotBlank()) {
        // Результаты поиска — сетка, как в телефонном Обзоре.
        SearchResultsTvGrid(items = items, onOpenFilm = onOpenFilm, onItemFocused = onItemFocused)
        return
    }

    val listState = rememberLazyListState()
    // Догрузка страниц каталога: как только конец ряда виден за 6 элементов — грузим ещё.
    val shouldLoadMore by derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        state.hasMore && last >= info.totalItemsCount - 2
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item(key = "hero") {
            // Баннер-герой — только реальное «продолжение просмотра»; каталог без истории
            // начинается сразу с ряда категорий, без «случайно выбранного» тайтла.
            if (continueWatching != null) {
                TvHeroBanner(
                    posterUrl = continueWatching.posterUrl,
                    title = continueWatching.title,
                    metaText = continueWatching.libraryMetaParts().joinToString("  •  "),
                    rating = continueWatching.libraryRating(),
                    watchLabel = "Продолжить",
                    onWatch = { onOpenHistoryFilm(continueWatching.kinopoiskId) },
                    onOpen = { onOpenHistoryFilm(continueWatching.kinopoiskId) },
                )
            }
        }
        item(key = "categories") {
            Row(
                modifier = Modifier.padding(horizontal = hPad),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiscoverCategory.entries.forEach { category ->
                    TvChip(
                        text = category.title,
                        selected = category == state.discoverCategory,
                        onClick = { onDiscoverCategorySelected(category) },
                    )
                }
                Spacer(Modifier.weight(1f))
                TvChip(
                    text = if (state.contentType == ContentType.ANIME) "Аниме" else "Кино",
                    selected = false,
                    onClick = {
                        onContentTypeSelected(
                            if (state.contentType == ContentType.ANIME) ContentType.FILMS else ContentType.ANIME
                        )
                    },
                )
                TvChip(
                    text = "Фильтры",
                    selected = state.filterState.isActive,
                    onClick = { onToggleFilterSheet(true) },
                )
            }
        }
        if (state.items.isNotEmpty() && continueWatching != null) {
            item(key = "continue-row") {
                TvRow(
                    title = "Продолжить просмотр",
                    items = state.library
                        .filterByRussian(state.hideRussianContent)
                        .filter { it.status == UserFilmStatus.WATCHING || it.status == UserFilmStatus.REWATCHING }
                        .take(20),
                    key = { it.kinopoiskId },
                ) { libraryItem ->
                    val progressUi = libraryItem.toWatchProgressUi()
                    TvPosterCard(
                        posterUrl = libraryItem.posterUrl,
                        title = libraryItem.title,
                        metaText = progressUi?.progressLabel ?: libraryItem.libraryMetaParts().joinToString("  •  "),
                        rating = libraryItem.libraryRating(),
                        progress = progressUi?.progress,
                        status = libraryItem.status,
                        cardWidth = cardWidth,
                        newEpisodes = libraryItem.takeIf { it.hasNewEpisode() }?.episodesAired?.let { aired ->
                            (aired - (libraryItem.watchedEpisodes ?: 0)).coerceAtLeast(1)
                        },
                        onClick = { onOpenHistoryFilm(libraryItem.kinopoiskId) },
                        onFocused = { onItemFocused(libraryItem.posterUrl) },
                    )
                }
            }
        }
        item(key = "catalog-row") {
            TvRow(
                title = state.discoverCategory.title,
                items = items.take(30),
                key = { it.kinopoiskId },
            ) { film ->
                TvPosterCard(
                    posterUrl = film.posterUrlPreview,
                    title = film.nameRu ?: film.nameOriginal ?: "",
                    metaText = film.year?.toString(),
                    rating = film.ratingKinopoisk,
                    cardWidth = cardWidth,
                    onClick = { onOpenFilm(film) },
                    onFocused = { onItemFocused(film.posterUrlPreview) },
                )
            }
        }
        // Лента «Обзора»: те же карусели, что на телефоне (топы + жанры кино/аниме).
        val overviewSections = if (state.contentType == ContentType.ANIME) {
            state.overviewAnimeSections
        } else {
            state.overviewFilmSections
        }
        overviewSections.forEach { section ->
            item(key = "overview_${section.id}") {
                TvRow(
                    title = section.title,
                    items = section.items,
                    key = { "overview_${section.id}_${it.kinopoiskId}" },
                ) { film ->
                    TvPosterCard(
                        posterUrl = film.posterUrlPreview,
                        title = film.nameRu ?: film.nameOriginal ?: "",
                        metaText = film.year?.toString(),
                        rating = film.ratingKinopoisk,
                        cardWidth = cardWidth,
                        onClick = { onOpenFilm(film) },
                        onFocused = { onItemFocused(film.posterUrlPreview) },
                    )
                }
            }
        }
        item(key = "anime-links") {
            Row(
                modifier = Modifier.padding(horizontal = hPad),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TvMenuCard(
                    title = "Календарь релизов",
                    subtitle = "Расписание выхода серий",
                    icon = Icons.Filled.CalendarMonth,
                    onClick = onOpenCalendar,
                )
                TvMenuCard(
                    title = "Новости",
                    subtitle = "Новости аниме от Shikimori",
                    icon = Icons.AutoMirrored.Filled.Feed,
                    onClick = onOpenFeed,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsTvGrid(
    items: List<FilmItem>,
    onOpenFilm: (FilmItem) -> Unit,
    onItemFocused: (String?) -> Unit,
) {
    val windowSize = rememberTvWindowSize()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val hPad = tvHPadFor(maxWidth, windowSize)
        val minCell = tvCardWidthFor(maxWidth, hPad, windowSize)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCell),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = hPad, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(items, key = { _, film -> film.kinopoiskId }) { _, film ->
                TvPosterCard(
                    posterUrl = film.posterUrlPreview,
                    title = film.nameRu ?: film.nameOriginal ?: "",
                    metaText = film.year?.toString(),
                    rating = film.ratingKinopoisk,
                    cardWidth = minCell,
                    onClick = { onOpenFilm(film) },
                    onFocused = { onItemFocused(film.posterUrlPreview) },
                )
            }
        }
    }
}

@Composable
private fun LibraryTvContent(
    state: HomeUiState,
    items: List<LibraryUiItem>,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    filterAll: Boolean,
    onFilterAllChanged: (Boolean) -> Unit,
    sort: LibrarySortType,
    onSortSelected: (LibrarySortType) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit,
    onRemoveFromHistory: (Int) -> Unit,
    onHentaiVisibilityChanged: (Boolean) -> Unit,
) {
    val windowSize = rememberTvWindowSize()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val hPad = tvHPadFor(maxWidth, windowSize)
        val minCell = tvCardWidthFor(maxWidth, hPad, windowSize)
        LibraryTvBody(
            state = state,
            items = items,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            filterAll = filterAll,
            onFilterAllChanged = onFilterAllChanged,
            sort = sort,
            onSortSelected = onSortSelected,
            onOpenHistoryFilm = onOpenHistoryFilm,
            onOpenFilmEditor = onOpenFilmEditor,
            onRemoveFromHistory = onRemoveFromHistory,
            onHentaiVisibilityChanged = onHentaiVisibilityChanged,
            hPad = hPad,
            minCell = minCell,
        )
    }
}

@Composable
private fun LibraryTvBody(
    state: HomeUiState,
    items: List<LibraryUiItem>,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    filterAll: Boolean,
    onFilterAllChanged: (Boolean) -> Unit,
    sort: LibrarySortType,
    onSortSelected: (LibrarySortType) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit,
    onRemoveFromHistory: (Int) -> Unit,
    onHentaiVisibilityChanged: (Boolean) -> Unit,
    hPad: androidx.compose.ui.unit.Dp,
    minCell: androidx.compose.ui.unit.Dp,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryTab.entries.forEach { tab ->
                TvChip(
                    text = tab.title,
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                )
            }
            Spacer(Modifier.width(12.dp))
            TvChip(
                text = if (filterAll) "Всё" else "Кроме аниме",
                selected = false,
                onClick = { onFilterAllChanged(!filterAll) },
            )
            TvChip(
                text = if (state.showHentaiInLibrary) "Hentai: вкл" else "Hentai: выкл",
                selected = false,
                onClick = { onHentaiVisibilityChanged(!state.showHentaiInLibrary) },
            )
            TvChip(
                text = "Сортировка: ${sort.label}",
                selected = false,
                onClick = {
                    val next = LibrarySortType.entries[(LibrarySortType.entries.indexOf(sort) + 1) % LibrarySortType.entries.size]
                    onSortSelected(next)
                },
            )
        }
        if (items.isEmpty()) {
            TvEmpty("Здесь пока пусто", Modifier.fillMaxSize())
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCell),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = hPad, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.kinopoiskId }) { _, item ->
                val progressUi = item.toWatchProgressUi()
                TvPosterCard(
                    posterUrl = item.posterUrl,
                    title = item.title,
                    metaText = progressUi?.progressLabel ?: item.libraryMetaParts().joinToString("  •  "),
                    rating = item.libraryRating(),
                    cardWidth = minCell,
                    progress = progressUi?.progress,
                    status = item.status,
                    newEpisodes = item.takeIf { it.hasNewEpisode() }?.episodesAired?.let { aired ->
                        (aired - (item.watchedEpisodes ?: 0)).coerceAtLeast(1)
                    },
                    onClick = { onOpenHistoryFilm(item.kinopoiskId) },
                )
            }
        }
    }
}

@Composable
private fun MoreTvContent(
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDownloads: () -> Unit,
    androidFeaturesAvailable: Boolean,
) {
    val hPad = when (rememberTvWindowSize()) {
        TvWindowSize.COMPACT -> 16.dp
        TvWindowSize.MEDIUM -> 24.dp
        TvWindowSize.EXPANDED -> 36.dp
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = hPad, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvSectionTitle("Ещё", modifier = Modifier.padding(horizontal = 0.dp))
        Spacer(Modifier.height(6.dp))
        // Симметричная сетка 2×N: карточки одного ряда всегда равной ширины.
        // Загрузки/Профиль — только там, где есть Android-механика (desktop их скрывает).
        val rows: List<List<Pair<Triple<String, String, ImageVector>, () -> Unit>>> = buildList {
            add(
                listOf(
                    Triple("Настройки", "Тема, плеер и playback", Icons.Filled.Settings) to onOpenSettings,
                    Triple("О приложении", "Версия и обновления", Icons.Filled.Info) to onOpenAbout,
                )
            )
            if (androidFeaturesAvailable) {
                add(
                    listOf(
                        Triple("Загрузки", "Скачанные серии и очередь", Icons.Rounded.Download) to onOpenDownloads,
                        Triple("Профиль", "Аккаунты, облако и статистика", Icons.Filled.Person) to onOpenProfile,
                    )
                )
            }
        }
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rowItems.forEach { (card, onClick) ->
                    val (title, subtitle, icon) = card
                    TvMenuCard(
                        title = title,
                        subtitle = subtitle,
                        icon = icon,
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
