package hd.kinoshka.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.data.local.LibrarySortType
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
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
import hd.kinoshka.app.ui.screens.FilterSlidersVerticalIcon
import hd.kinoshka.app.ui.screens.OverviewSeeAll
import hd.kinoshka.app.ui.screens.ProgressEditorSeed
import hd.kinoshka.app.ui.screens.SearchFilterBottomSheet
import hd.kinoshka.app.ui.screens.SearchFilterState
import hd.kinoshka.app.ui.screens.toLibraryTab
import hd.kinoshka.app.ui.screens.calendarEpisodeTimeMs
import hd.kinoshka.app.ui.screens.extractTopicImages
import hd.kinoshka.app.ui.screens.filterByQuery
import hd.kinoshka.app.ui.screens.filterByRussian
import hd.kinoshka.app.ui.screens.filterByTab
import hd.kinoshka.app.ui.screens.formatRemainingTime
import hd.kinoshka.app.ui.screens.formatReleaseExactTime
import hd.kinoshka.app.ui.screens.hasNewEpisode
import hd.kinoshka.app.ui.screens.isRussianContent
import hd.kinoshka.app.ui.screens.libraryMetaParts
import hd.kinoshka.app.ui.screens.libraryRating
import hd.kinoshka.app.ui.screens.shikimoriGenres
import hd.kinoshka.app.ui.screens.toSwitcherIcon
import hd.kinoshka.app.ui.screens.toWatchProgressUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Разделы TV-главной: навигация — боковое меню слева за бургером. */
private enum class TvHomeSection(val label: String) {
    LIBRARY("Библиотека"),
    DISCOVER("Обзор"),
    PROFILE("Профиль")
}

/** Фильтр библиотеки в TV-раскладке: чип переключает по кругу. */
private enum class TvLibraryFilter(val label: String) {
    ALL("Всё"),
    FILMS("Только кино"),
    ANIME("Только аниме"),
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
    onOpenTopic: (Int) -> Unit = {},
    onSeeAll: (OverviewSeeAll) -> Unit = {},
    onLibrarySortSelected: (LibrarySortType) -> Unit = {},
    librarySortReversed: Boolean = false,
    onLibrarySortReversedChanged: (Boolean) -> Unit = {},
    onHentaiVisibilityChanged: (Boolean) -> Unit = {},
    onInstantSearch: (String) -> Unit = {},
    onRemoveSearchHistory: (String) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    // Аккаунты профиля (диалоги входа хостит платформа).
    onShikimoriLogin: () -> Unit = {},
    onLogoutShikimori: () -> Unit = {},
    onAnixartLogin: () -> Unit = {},
    onLogoutAnixart: () -> Unit = {},
    // Android-only возможности (аккаунты, аватар): на desktop их экранов нет,
    // соответствующие точки входа скрываются.
    androidFeaturesAvailable: Boolean = true,
    // Загрузки серий: на Android TV скачивание отключено — точки входа скрываются.
    downloadsAvailable: Boolean = true,
    // Облачный и файловый бэкапы ТВ-профиля (состояние и диалоги хостит
    // платформа; null — секции скрыты, как на desktop).
    cloudBackup: TvCloudBackupState? = null,
    onCloudConnectYandex: () -> Unit = {},
    onCloudConnectWebDav: () -> Unit = {},
    onCloudDisconnect: () -> Unit = {},
    onCloudUpload: () -> Unit = {},
    onCloudRestore: () -> Unit = {},
    onCloudAutoSyncChanged: (Boolean) -> Unit = {},
    onExportLibraryToFile: (() -> Unit)? = null,
    onImportLibraryFromFile: (() -> Unit)? = null,
) {
    var section by remember { mutableStateOf(TvHomeSection.DISCOVER) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // После закрытия меню фокус возвращаем на бургер — иначе он остаётся
    // на скрытом пункте и пульт «замирает» (стрелки ничего не двигают).
    // Эффект ловит ВСЕ пути закрытия (выбор, вправо, Назад, тап по скриму).
    val burgerFocus = remember { FocusRequester() }
    // Первый пункт меню: при открытии забирает фокус явно — иначе пульт
    // остаётся на контенте за скримом и хождение по меню не начинается.
    val firstDrawerItemFocus = remember { FocusRequester() }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            runCatching { firstDrawerItemFocus.requestFocus() }
        } else {
            // Фокус мог застрять на скрытом пункте меню — выбиваем принудительно,
            // иначе requestFocus молча возвращает false.
            focusManager.clearFocus(force = true)
            runCatching { burgerFocus.requestFocus() }
        }
    }
    fun focusBurgerAfterClose() {
        drawerScope.launch {
            drawerState.close()
            focusManager.clearFocus(force = true)
            runCatching { burgerFocus.requestFocus() }
        }
    }
    // Текстовое поле поиска в фокусе (открыта клавиатура): edge-навигацию не трогаем,
    // стрелки принадлежат вводу. Шит фильтров — тоже модальный слой поверх.
    var searchInputFocused by remember { mutableStateOf(false) }
    // Фокус на графике активности Профиля: стрелки влево/вправо двигают выбор
    // по дням, край их не перехватывает (иначе влево открывало бы меню).
    var activityChartFocused by remember { mutableStateOf(false) }
    var discoverQuery by rememberSaveable { mutableStateOf("") }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.WATCHING) }
    var libraryFilter by rememberSaveable { mutableStateOf(TvLibraryFilter.ALL) }
    var sortIndex by rememberSaveable { mutableStateOf(0) }
    // Выбор раздела из drawer/топ-бара: синхронизируем вкладку VM и закрываем меню.
    fun selectSection(target: TvHomeSection) {
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
        focusBurgerAfterClose()
    }
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

    // Root-экран: открытый drawer закрывается первым; иначе первый «Назад»
    // показывает подсказку, второй в окне подтверждения закрывает.
    var lastBackExitAttemptAt by remember { mutableStateOf(0L) }
    KinoBackHandler(enabled = true) {
        if (drawerState.isOpen) {
            drawerScope.launch { drawerState.close() }
            return@KinoBackHandler
        }
        val now = System.currentTimeMillis()
        if (now - lastBackExitAttemptAt < 2_000L) {
            platformActions.exitApp()
        } else {
            lastBackExitAttemptAt = now
            platformActions.showToast("Повторите «Назад», чтобы закрыть приложение")
        }
    }

    val libraryItems = remember(state.library, state.hideRussianContent, normalizedQuery, libraryTab, libraryFilter) {
        state.library
            .filterByRussian(state.hideRussianContent)
            .filterByTab(libraryTab)
            .filterByQuery(normalizedQuery)
            .filter { item ->
                when (libraryFilter) {
                    TvLibraryFilter.ALL -> true
                    TvLibraryFilter.FILMS -> item.type != "ANIME"
                    TvLibraryFilter.ANIME -> item.type == "ANIME"
                }
            }
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
    // С дебаунсом: блюр на весь экран при каждом шаге фокуса ронял кадры
    // и дёргал карусели, обновление идёт только после остановки на карточке.
    // 450мс (а не 250): на удержании стрелки шаги идут чаще паузы — тяжёлый
    // Crossfade+blur не должен стартовать, пока пользователь реально листает.
    var discoverBackdropUrl by remember { mutableStateOf<String?>(null) }
    var pendingBackdropUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingBackdropUrl, section) {
        val pending = pendingBackdropUrl
        delay(450)
        if (section == TvHomeSection.DISCOVER && discoverBackdropUrl != pending) {
            discoverBackdropUrl = pending
        }
    }
    val windowSize = rememberTvWindowSize()
    val hPad = when (windowSize) {
        TvWindowSize.COMPACT -> 16.dp
        TvWindowSize.MEDIUM -> 24.dp
        TvWindowSize.EXPANDED -> 36.dp
    }

    // Боковое меню слева за бургером: разделы + системные экраны.
    // Ленты здесь нет — в горизонтальном режиме она отключена.
    // Край экрана — выше drawer, чтобы видеть фокус и в меню, и в контенте:
    // влево в тупике открывает меню, вправо при открытом меню закрывает.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (searchInputFocused || state.showFilterSheet) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (drawerState.isOpen) return@onPreviewKeyEvent false
                        if (activityChartFocused) return@onPreviewKeyEvent false
                        if (focusManager.moveFocus(FocusDirection.Left)) true
                        else {
                            drawerScope.launch { drawerState.open() }
                            true
                        }
                    }
                    Key.DirectionRight -> {
                        if (!drawerState.isOpen) return@onPreviewKeyEvent false
                        focusBurgerAfterClose()
                        true
                    }
                    else -> false
                }
            },
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TvNavDrawer(
                selected = section,
                // Скрытое меню не должно красть D-pad фокус: иначе стрелки
                // уходят в невидимые пункты и интерфейс «замирает».
                enabled = drawerState.isOpen,
                firstItemFocusRequester = firstDrawerItemFocus,
                androidFeaturesAvailable = androidFeaturesAvailable,
                downloadsAvailable = downloadsAvailable,
                onSelectSection = ::selectSection,
                onOpenDownloads = {
                    drawerScope.launch { drawerState.close() }
                    onOpenDownloads()
                },
                onOpenSettings = {
                    drawerScope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onOpenAbout = {
                    drawerScope.launch { drawerState.close() }
                    onOpenAbout()
                },
            )
        },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TvAnimatedBackdrop(
            imageUrl = discoverBackdropUrl.takeIf { section == TvHomeSection.DISCOVER },
            modifier = Modifier.fillMaxSize(),
        )
        // Лёгкая вуаль за прозрачной шапкой — везде, кроме Обзора:
        // там шапка без фона, текст читается за счёт блюра бэкдропа.
        // Без вуали текст топ-бара терялся на ярком бэкдропе и сливался с контентом.
        if (section != TvHomeSection.DISCOVER) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
        // Единая focusGroup на весь экран: стрелки клавиатуры (ПК/планшет с
        // клавиатурой) ходят по фокусу так же, как D-pad на ТВ — вверх/вниз между
        // топ-баром, рядами и сетками, влево/вправо внутри рядов.
        Column(modifier = Modifier.fillMaxSize().focusGroup()) {
        TvTopBar(
            title = section.label,
            onBurgerClick = { drawerScope.launch { drawerState.open() } },
            burgerFocusRequester = burgerFocus,
            query = when (section) {
                TvHomeSection.DISCOVER -> discoverQuery
                TvHomeSection.LIBRARY -> libraryQuery
                TvHomeSection.PROFILE -> ""
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
                    TvHomeSection.PROFILE -> {}
                }
            },
            onSearchSubmit = onSubmitSearch,
            searchPlaceholder = when (section) {
                TvHomeSection.DISCOVER -> "Поиск фильмов и аниме"
                TvHomeSection.LIBRARY -> "Поиск в библиотеке"
                TvHomeSection.PROFILE -> "Поиск"
            },
            showSearch = section != TvHomeSection.PROFILE,
            onAvatarClick = { selectSection(TvHomeSection.PROFILE) },
            // В самом Профиле иконка лишняя; поиска там тоже нет.
            showAvatar = androidFeaturesAvailable && section != TvHomeSection.PROFILE,
            onSearchFocusChanged = { focused -> searchInputFocused = focused },
            actions = {
                // Быстрые переключатели Обзора переехали из контентного ряда сюда.
                // Иконки — те же, что в мобильной версии.
                if (section == TvHomeSection.DISCOVER) {
                    TvChip(
                        text = if (state.contentType == ContentType.ANIME) "Аниме" else "Кино",
                        selected = false,
                        onClick = {
                            onContentTypeSelected(
                                if (state.contentType == ContentType.ANIME) ContentType.FILMS else ContentType.ANIME
                            )
                        },
                        icon = state.contentType.toSwitcherIcon(),
                        shape = RoundedCornerShape(24.dp),
                    )
                    TvChip(
                        text = "Фильтры",
                        selected = state.filterState.isActive,
                        onClick = { onToggleFilterSheet(true) },
                        icon = FilterSlidersVerticalIcon,
                        shape = RoundedCornerShape(24.dp),
                    )
                }
            },
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
                onOpenTopic = onOpenTopic,
                onSeeAll = onSeeAll,
                onRetry = onRetry,
                onItemFocused = { url -> pendingBackdropUrl = url },
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
                filter = libraryFilter,
                onFilterChanged = { libraryFilter = it },
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
            TvHomeSection.PROFILE -> {
                TvProfileContent(
                    state = state,
                    // Легенда списков: вкладка статуса + фильтр Кино/Аниме, как deep-link мобильной версии.
                    onLibraryStatusSelected = { status, animeOnly ->
                        libraryTab = status.toLibraryTab()
                        libraryFilter = if (animeOnly) TvLibraryFilter.ANIME else TvLibraryFilter.FILMS
                        libraryQuery = ""
                        onQueryChange("")
                        selectSection(TvHomeSection.LIBRARY)
                    },
                    onShikimoriLogin = onShikimoriLogin,
                    onLogoutShikimori = onLogoutShikimori,
                    onAnixartLogin = onAnixartLogin,
                    onLogoutAnixart = onLogoutAnixart,
                    androidFeaturesAvailable = androidFeaturesAvailable,
                    cloudBackup = cloudBackup,
                    onCloudConnectYandex = onCloudConnectYandex,
                    onCloudConnectWebDav = onCloudConnectWebDav,
                    onCloudDisconnect = onCloudDisconnect,
                    onCloudUpload = onCloudUpload,
                    onCloudRestore = onCloudRestore,
                    onCloudAutoSyncChanged = onCloudAutoSyncChanged,
                    onExportLibraryToFile = onExportLibraryToFile,
                    onImportLibraryFromFile = onImportLibraryFromFile,
                    onActivityChartFocusChanged = { activityChartFocused = it },
                )
            }
        }
        }
    }
    }
    }

    // Шит фильтров: телефонная ветка его рисует ниже, TV возвращается раньше —
    // без этого блока кнопка «Фильтры» в топ-баре ничего не открывала.
    if (state.showFilterSheet) {
        SearchFilterBottomSheet(
            filterState = state.filterState,
            availableGenres = state.availableGenres,
            availableCountries = state.availableCountries,
            contentType = state.contentType,
            onApply = onUpdateFilters,
            onDismiss = { onToggleFilterSheet(false) },
        )
    }
}

/**
 * Боковое меню слева: разделы + системные экраны. Открывается бургером в топ-баре.
 * Ленты в меню нет — в горизонтальном режиме она отключена.
 */
@Composable
private fun TvNavDrawer(
    selected: TvHomeSection,
    enabled: Boolean,
    androidFeaturesAvailable: Boolean,
    downloadsAvailable: Boolean,
    onSelectSection: (TvHomeSection) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Кино",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        )
        TvDrawerItem(
            icon = Icons.Filled.VideoLibrary,
            label = TvHomeSection.LIBRARY.label,
            selected = selected == TvHomeSection.LIBRARY,
            onClick = { onSelectSection(TvHomeSection.LIBRARY) },
            enabled = enabled,
            focusRequester = firstItemFocusRequester,
        )
        TvDrawerItem(
            icon = Icons.Filled.Explore,
            label = TvHomeSection.DISCOVER.label,
            selected = selected == TvHomeSection.DISCOVER,
            onClick = { onSelectSection(TvHomeSection.DISCOVER) },
            enabled = enabled,
        )
        TvDrawerItem(
            icon = Icons.Filled.Person,
            label = TvHomeSection.PROFILE.label,
            selected = selected == TvHomeSection.PROFILE,
            onClick = { onSelectSection(TvHomeSection.PROFILE) },
            enabled = enabled,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        if (androidFeaturesAvailable && downloadsAvailable) {
            TvDrawerItem(
                icon = Icons.Rounded.Download,
                label = "Загрузки",
                selected = false,
                onClick = onOpenDownloads,
                enabled = enabled,
            )
        }
        TvDrawerItem(
            icon = Icons.Filled.Settings,
            label = "Настройки",
            selected = false,
            onClick = onOpenSettings,
            enabled = enabled,
        )
        TvDrawerItem(
            icon = Icons.Filled.Info,
            label = "О приложении",
            selected = false,
            onClick = onOpenAbout,
            enabled = enabled,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Строка drawer с TV-фокусом: скруглённая рамка выделения вместо штатной
 * подсветки NavigationDrawerItem, которую на ТВ почти не видно.
 */
@Composable
private fun TvDrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(
        targetValue = if (selected) cs.primaryContainer else Color.Transparent,
        label = "tvDrawerBg",
    )
    val contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .tvFocusable(
                onClick = onClick,
                shape = shape,
                focusedScale = 1.02f,
                enabled = enabled,
                focusRequester = focusRequester,
            )
            // Закрытое меню — вне фокуса физически: строго ПОСЛЕ tvFocusable,
            // чей clickable внутри переоткрывает фокусируемость. Иначе стрелка
            // влево с края контента уводит фокус в невидимые пункты вместо меню.
            .focusProperties { canFocus = enabled }
            .clip(shape)
            .background(background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
        )
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
    onOpenTopic: (Int) -> Unit,
    onSeeAll: (OverviewSeeAll) -> Unit,
    onRetry: () -> Unit,
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
            onOpenTopic = onOpenTopic,
            onSeeAll = onSeeAll,
            onRetry = onRetry,
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
    onOpenTopic: (Int) -> Unit,
    onSeeAll: (OverviewSeeAll) -> Unit,
    onRetry: () -> Unit,
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
    // «Продолжить просмотр» — из библиотеки, без привязки к наполнению каталога.
    val watchingRowItems = remember(state.library, state.hideRussianContent) {
        state.library
            .filterByRussian(state.hideRussianContent)
            .filter { it.status == UserFilmStatus.WATCHING || it.status == UserFilmStatus.REWATCHING }
            .take(20)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        // Верхний hero-баннер убран: он дублировал карусель «Продолжить просмотр»
        // ниже (тот же тайтл и та же кнопка). Осталась только карусель.
        if (watchingRowItems.isNotEmpty()) {
            item(key = "continue-row") {
                TvRow(
                    title = "Продолжить просмотр",
                    items = watchingRowItems,
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
        // Как в вертикальном Обзоре: новости и календарь аниме + чипы жанров.
        if (state.contentType == ContentType.ANIME) {
            if (state.topics.isNotEmpty()) {
                item(key = "news-row") {
                    TvNewsRow(
                        topics = state.topics.take(10),
                        hPad = hPad,
                        onOpenTopic = onOpenTopic,
                        onOpenFeed = onOpenFeed,
                    )
                }
            }
            if (state.calendarItems.isNotEmpty()) {
                item(key = "calendar-row") {
                    TvCalendarRow(
                        items = state.calendarItems,
                        hPad = hPad,
                        onOpenFilm = onOpenFilm,
                        onOpenCalendar = onOpenCalendar,
                    )
                }
            }
        }
        item(key = "genre-row") {
            TvGenreRow(
                state = state,
                hPad = hPad,
                onSeeAll = onSeeAll,
            )
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

/**
 * Новости аниме (hero-блок вертикального Обзора TV-карточками):
 * заголовок топика + счётчик комментариев, тап — сам пост, «Все» — лента новостей.
 */
@Composable
private fun TvNewsRow(
    topics: List<hd.kinoshka.app.data.model.ShikimoriTopic>,
    hPad: androidx.compose.ui.unit.Dp,
    onOpenTopic: (Int) -> Unit,
    onOpenFeed: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = hPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Новости",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvButton(text = "Все", onClick = onOpenFeed)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Вертикальный запас под кольцо фокуса — иначе каждый шаг пульта
            // вбок дёргает страницу вверх-вниз (см. TvRow).
            contentPadding = PaddingValues(horizontal = hPad, vertical = 12.dp),
        ) {
            items(topics, key = { "tvnews_${it.id}" }) { topic ->
                TvNewsCard(topic = topic, onClick = { onOpenTopic(topic.id) })
            }
        }
    }
}

@Composable
private fun TvNewsCard(
    topic: hd.kinoshka.app.data.model.ShikimoriTopic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Картинка — как на телефоне: вложение из самого поста, иначе постер тайтла.
    val cardImage = remember(topic.id, topic.htmlBody, topic.body, topic.htmlFooter) {
        extractTopicImages(topic.htmlBody, topic.body, topic.htmlFooter).firstOrNull()
    } ?: topic.linked?.posterUrl
    Box(
        modifier = modifier
            .width(300.dp)
            .height(172.dp)
            .tvFocusable(onClick = onClick, hoverToFocus = true, bringIntoViewOnFocus = true)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh),
    ) {
        if (cardImage != null) {
            KinoshkaAsyncImage(
                model = cardImage,
                contentDescription = topic.topicTitle?.takeIf { it.isNotBlank() } ?: "Новость",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
            Text(
                text = topic.topicTitle?.takeIf { it.isNotBlank() } ?: "Новость",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${topic.commentsCount} комм.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Календарь онгоингов (ряд вертикального Обзора): компактные плитки как на
 * телефоне, на обложке — бейдж «через…». «Все» ведёт на полный календарь.
 */
@Composable
private fun TvCalendarRow(
    items: List<hd.kinoshka.app.data.model.ShikimoriCalendarItem>,
    hPad: androidx.compose.ui.unit.Dp,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenCalendar: () -> Unit,
) {
    // Как в вертикальном Обзоре: сначала будущее, прошедшие эпизоды не показываем.
    val upcoming = remember(items) {
        val now = System.currentTimeMillis()
        val withTime = items.mapNotNull { item ->
            calendarEpisodeTimeMs(item.nextEpisodeAt)?.let { item to it }
        }.sortedBy { it.second }
        val future = withTime.filter { it.second > now }.map { it.first }
        (if (future.isNotEmpty()) future else withTime.map { it.first }.ifEmpty { items }).take(20)
    }
    if (upcoming.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = hPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Календарь",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvButton(text = "Все", onClick = onOpenCalendar)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Вертикальный запас под кольцо фокуса — иначе каждый шаг пульта
            // вбок дёргает страницу вверх-вниз (см. TvRow).
            contentPadding = PaddingValues(horizontal = hPad, vertical = 12.dp),
        ) {
            items(upcoming, key = { "tvcal_${it.anime?.id}_${it.nextEpisode}" }) { item ->
                val anime = item.anime ?: return@items
                TvCalendarCard(
                    posterUrl = anime.posterUrl,
                    title = anime.displayTitle,
                    // Как в карусели телефона: оставшееся время, иначе точное.
                    timeBadge = formatRemainingTime(item.nextEpisodeAt)
                        ?: formatReleaseExactTime(item.nextEpisodeAt),
                    onClick = { onOpenFilm(anime.toFilmItem()) },
                )
            }
        }
    }
}

/** Компактная плитка календаря (112dp, как на телефоне) с TV-фокусом. */
@Composable
private fun TvCalendarCard(
    posterUrl: String?,
    title: String,
    timeBadge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier.width(112.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .tvFocusable(onClick = onClick, hoverToFocus = true, bringIntoViewOnFocus = true)
                .clip(RoundedCornerShape(14.dp))
                .background(cs.surfaceContainerHigh),
        ) {
            KinoshkaAsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Затемнение снизу для контраста бейджа «через…».
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.65f),
                            ),
                        ),
                    ),
            )
            timeBadge?.let { badge ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp),
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurface,
        )
    }
}

/**
 * Чипы жанров (как в вертикальном Обзоре): аниме — статичный список Shikimori,
 * кино — первые 12 из справочника. Тап открывает подборку через onSeeAll.
 */
@Composable
private fun TvGenreRow(
    state: HomeUiState,
    hPad: androidx.compose.ui.unit.Dp,
    onSeeAll: (OverviewSeeAll) -> Unit,
) {
    val isAnime = state.contentType == ContentType.ANIME
    val filmGenres = remember(state.availableGenres) { state.availableGenres.take(12) }
    if (!isAnime && filmGenres.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = hPad),
    ) {
        if (isAnime) {
            items(shikimoriGenres, key = { "tvgenre_anime_${it.id}" }) { genre ->
                TvChip(
                    text = genre.genre ?: "",
                    selected = false,
                    onClick = { onSeeAll(OverviewSeeAll.AnimeGenreTarget(genre.id, genre.genre ?: "")) },
                )
            }
        } else {
            items(filmGenres, key = { "tvgenre_film_${it.id}" }) { genre ->
                TvChip(
                    text = genre.genre ?: "",
                    selected = false,
                    onClick = { onSeeAll(OverviewSeeAll.FilmGenreTarget(genre.id, genre.genre ?: "")) },
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
    filter: TvLibraryFilter,
    onFilterChanged: (TvLibraryFilter) -> Unit,
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
            filter = filter,
            onFilterChanged = onFilterChanged,
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
    filter: TvLibraryFilter,
    onFilterChanged: (TvLibraryFilter) -> Unit,
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
                text = filter.label,
                selected = false,
                onClick = {
                    val values = TvLibraryFilter.entries
                    onFilterChanged(values[(values.indexOf(filter) + 1) % values.size])
                },
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

/**
 * «Мой профиль» в TV-раскладке: герой с аватаром, статистика библиотеки
 * (тап — вкладка Библиотеки) и аккаунты. Без поиска/шестерёнки/аватара в шапке —
 * они живут в топ-баре и drawer. Резервные копии и облако — только в мобильной версии.
 */
/**
 * «Мой профиль» в TV-раскладке: статистика библиотеки (тап — вкладка),
 * списки аниме/фильмов с полосами статусов и графиком активности
 * (тап по легенде — Библиотека на статусе), аккаунты и резервные копии.
 * Hero с аватаром/именем убран — это дубль аккаунтов ниже.
 * Без поиска/шестерёнки/аватара в шапке — они живут в топ-баре и drawer.
 */
@Composable
private fun TvProfileContent(
    state: HomeUiState,
    onLibraryStatusSelected: (UserFilmStatus, Boolean) -> Unit,
    onShikimoriLogin: () -> Unit,
    onLogoutShikimori: () -> Unit,
    onAnixartLogin: () -> Unit,
    onLogoutAnixart: () -> Unit,
    androidFeaturesAvailable: Boolean,
    cloudBackup: TvCloudBackupState? = null,
    onCloudConnectYandex: () -> Unit = {},
    onCloudConnectWebDav: () -> Unit = {},
    onCloudDisconnect: () -> Unit = {},
    onCloudUpload: () -> Unit = {},
    onCloudRestore: () -> Unit = {},
    onCloudAutoSyncChanged: (Boolean) -> Unit = {},
    onExportLibraryToFile: (() -> Unit)? = null,
    onImportLibraryFromFile: (() -> Unit)? = null,
    onActivityChartFocusChanged: (Boolean) -> Unit = {},
) {
    val hPad = when (rememberTvWindowSize()) {
        TvWindowSize.COMPACT -> 16.dp
        TvWindowSize.MEDIUM -> 24.dp
        TvWindowSize.EXPANDED -> 36.dp
    }
    val shikimori = state.shikimoriAuthState
    val anixart = state.anixartAuthState
    // Списки как в мобильной версии: аниме — kinopoiskId за оффсетом Shikimori.
    val (animeItems, filmItems) = remember(state.library) {
        state.library.partition { it.kinopoiskId >= ANIME_ID_OFFSET }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "anime_list") {
            TvStatusStrip(
                title = "Список аниме",
                icon = Icons.Filled.SmartToy,
                items = animeItems,
                hPad = hPad,
                onStatusClick = { status -> onLibraryStatusSelected(status, true) },
            )
        }
        item(key = "films_list") {
            TvStatusStrip(
                title = "Список фильмов",
                icon = Icons.Filled.Movie,
                items = filmItems,
                hPad = hPad,
                onStatusClick = { status -> onLibraryStatusSelected(status, false) },
            )
        }
        item(key = "activity") {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TvWatchTimeRow(library = state.library, hPad = hPad)
                TvActivitySection(
                    library = state.library,
                    hPad = hPad,
                    onFocusedChange = onActivityChartFocusChanged,
                )
            }
        }
        if (androidFeaturesAvailable) {
            item(key = "accounts") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Аккаунты",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = hPad),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = hPad),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TvAccountCard(
                            title = "Shikimori",
                            status = if (shikimori.isLoggedIn) {
                                shikimori.nickname ?: "Подключено"
                            } else {
                                "Не подключено"
                            },
                            avatarUrl = shikimori.avatarUrl.takeIf { shikimori.isLoggedIn },
                            actionLabel = if (shikimori.isLoggedIn) "Выйти" else "Войти",
                            primaryAction = !shikimori.isLoggedIn,
                            onAction = { if (shikimori.isLoggedIn) onLogoutShikimori() else onShikimoriLogin() },
                            modifier = Modifier.weight(1f),
                        )
                        TvAccountCard(
                            title = "Anixart",
                            status = if (anixart.isLoggedIn) {
                                anixart.nickname ?: "Подключено"
                            } else {
                                "Не подключено"
                            },
                            avatarUrl = null,
                            actionLabel = if (anixart.isLoggedIn) "Выйти" else "Войти",
                            primaryAction = !anixart.isLoggedIn,
                            onAction = { if (anixart.isLoggedIn) onLogoutAnixart() else onAnixartLogin() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        // Резервные копии — в 2 столбика, как Аккаунты: облако слева, файл справа.
        val cloud = cloudBackup
        val onExportFile = onExportLibraryToFile
        val onImportFile = onImportLibraryFromFile
        if (cloud != null && onExportFile != null && onImportFile != null) {
            item(key = "backups") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = hPad),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TvCloudBackupSection(
                            backupState = cloud,
                            hPad = 0.dp,
                            onConnectYandex = onCloudConnectYandex,
                            onConnectWebDav = onCloudConnectWebDav,
                            onDisconnect = onCloudDisconnect,
                            onUpload = onCloudUpload,
                            onRestore = onCloudRestore,
                            onAutoSyncChanged = onCloudAutoSyncChanged,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TvFileBackupSection(
                            hPad = 0.dp,
                            onExport = onExportFile,
                            onImport = onImportFile,
                        )
                    }
                }
            }
        } else if (cloud != null) {
            item(key = "cloud_backup") {
                TvCloudBackupSection(
                    backupState = cloud,
                    hPad = hPad,
                    onConnectYandex = onCloudConnectYandex,
                    onConnectWebDav = onCloudConnectWebDav,
                    onDisconnect = onCloudDisconnect,
                    onUpload = onCloudUpload,
                    onRestore = onCloudRestore,
                    onAutoSyncChanged = onCloudAutoSyncChanged,
                )
            }
        } else if (onExportFile != null && onImportFile != null) {
            item(key = "file_backup") {
                TvFileBackupSection(
                    hPad = hPad,
                    onExport = onExportFile,
                    onImport = onImportFile,
                )
            }
        }
    }
}

/** Карточка аккаунта: статус + кнопка входа/выхода. */
@Composable
private fun TvAccountCard(
    title: String,
    status: String,
    avatarUrl: String?,
    actionLabel: String,
    primaryAction: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl != null) {
                    KinoshkaAsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = cs.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TvButton(text = actionLabel, primary = primaryAction, onClick = onAction)
    }
}
