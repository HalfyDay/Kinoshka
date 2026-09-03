package hd.kinoshka.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Feed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
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
    onClearSearchHistory: () -> Unit = {}
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvTheme.Background)
    ) {
        TvTopBar(
            sections = listOf(
                TvHomeSection.LIBRARY.label,
                TvHomeSection.DISCOVER.label,
                "Лента",
                TvHomeSection.MORE.label,
            ),
            selectedSection = when (section) {
                TvHomeSection.LIBRARY -> 0
                TvHomeSection.DISCOVER -> 1
                TvHomeSection.MORE -> 3
            },
            onSectionSelected = { index ->
                when (index) {
                    2 -> onOpenRecommendationsFeed()
                    else -> {
                        val target = when (index) {
                            0 -> TvHomeSection.LIBRARY
                            3 -> TvHomeSection.MORE
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
            )
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
) {
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
                color = TvTheme.TextSecondary,
                fontSize = 16.sp,
            )
            TvButton("Повторить", onClick = onRetry)
        }
        return
    }

    if (normalizedQuery.isNotBlank()) {
        // Результаты поиска — сетка, как в телефонном Обзоре.
        SearchResultsTvGrid(items = items, onOpenFilm = onOpenFilm)
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
                modifier = Modifier.padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                        newEpisodes = libraryItem.takeIf { it.hasNewEpisode() }?.episodesAired?.let { aired ->
                            (aired - (libraryItem.watchedEpisodes ?: 0)).coerceAtLeast(1)
                        },
                        onClick = { onOpenHistoryFilm(libraryItem.kinopoiskId) },
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
                    onClick = { onOpenFilm(film) },
                )
            }
        }
        item(key = "anime-links") {
            Row(
                modifier = Modifier.padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TvMenuCard(
                    title = "Календарь релизов",
                    subtitle = "Расписание выхода серий",
                    icon = Icons.Filled.CalendarMonth,
                    onClick = onOpenCalendar,
                )
                TvMenuCard(
                    title = "Лента релизов",
                    subtitle = "Новости аниме от Shikimori",
                    icon = Icons.Filled.Feed,
                    onClick = onOpenFeed,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsTvGrid(items: List<FilmItem>, onOpenFilm: (FilmItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 170.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(items, key = { _, film -> film.kinopoiskId }) { _, film ->
            TvPosterCard(
                posterUrl = film.posterUrlPreview,
                title = film.nameRu ?: film.nameOriginal ?: "",
                metaText = film.year?.toString(),
                rating = film.ratingKinopoisk,
                onClick = { onOpenFilm(film) },
            )
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
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            columns = GridCells.Adaptive(minSize = 170.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.kinopoiskId }) { _, item ->
                val progressUi = item.toWatchProgressUi()
                TvPosterCard(
                    posterUrl = item.posterUrl,
                    title = item.title,
                    metaText = progressUi?.progressLabel ?: item.libraryMetaParts().joinToString("  •  "),
                    rating = item.libraryRating(),
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvSectionTitle("Ещё")
        Spacer(Modifier.height(6.dp))
        // Симметричная сетка 2×N: карточки одного ряда всегда равной ширины.
        listOf(
            listOf(
                Triple("Загрузки", "Скачанные серии и очередь", Icons.Filled.CloudDownload) to onOpenDownloads,
                Triple("Профиль", "Аккаунты, облако и статистика", Icons.Filled.Person) to onOpenProfile,
            ),
            listOf(
                Triple("Настройки", "Тема, плеер и playback", Icons.Filled.Settings) to onOpenSettings,
                Triple("О приложении", "Версия и обновления", Icons.Filled.Info) to onOpenAbout,
            ),
        ).forEachIndexed { rowIndex, rowItems ->
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
