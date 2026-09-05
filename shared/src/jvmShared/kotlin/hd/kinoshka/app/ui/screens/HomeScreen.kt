package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.focus.onFocusChanged
import hd.kinoshka.app.ui.platform.KinoBackHandler
import hd.kinoshka.app.ui.platform.rememberKinoPlatformActions
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import hd.kinoshka.app.ui.components.BottomNavPill
import hd.kinoshka.app.ui.components.NavPillItem
import hd.kinoshka.app.ui.components.ScrollIntensityEffect
import hd.kinoshka.app.ui.components.sheetSquashStretch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import hd.kinoshka.app.data.model.FilterItem
import hd.kinoshka.app.data.model.ANIME_GENRE_NAME
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Feed
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import hd.kinoshka.app.data.local.LibraryGroupType
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.ui.components.KinoLoadingIndicator
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.ui.components.SkeletonGridCard
import hd.kinoshka.app.ui.components.SkeletonGridLoading
import hd.kinoshka.app.ui.components.SkeletonListLoading
import hd.kinoshka.app.ui.components.SkeletonVerticalRow
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.ceil

/** Разделы нижней пилюли. FEED — лента рекомендаций в том же окружении
 *  (та же композиция и пилюля), а не отдельным маршрутом: иначе переезд круга
 *  выделения нечем анимировать, а спам переключениями роняет приложение.
 *  Публичный — нужен слоту ленты и маппингу HomeTab. */
enum class MainSection {
    LIBRARY,
    DISCOVER,
    FEED,
    MORE
}

internal enum class LibraryTab(val title: String) {
    HISTORY("История"),
    WATCHING("Смотрю"),
    PLANNED("В планах"),
    WATCHED("Просмотрено"),
    REWATCHING("Пересматриваю"),
    ON_HOLD("Отложено"),
    DROPPED("Брошено")
}

/**
 * Everything the quick "Прогресс просмотра" sheet needs, assembled from tile data alone —
 * long-press must open it instantly without any network round-trip or details-page navigation.
 */
data class ProgressEditorSeed(
    val kinopoiskId: Int,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val type: String?,
    val ratingKinopoisk: Double?,
    val profile: UserFilmProfile?
)

private fun LibraryUiItem.toEditorProfile(): UserFilmProfile = UserFilmProfile(
    kinopoiskId = kinopoiskId,
    title = title,
    subtitle = subtitle,
    posterUrl = posterUrl,
    ratingText = ratingText,
    type = type,
    isRussian = isRussian,
    status = status,
    userRating = userRating,
    note = note,
    watchedSeasons = watchedSeasons,
    watchedEpisodes = watchedEpisodes,
    totalEpisodesInSeason = totalEpisodesInSeason,
    totalSeasons = totalSeasons,
    totalEpisodes = totalEpisodes,
    updatedAt = updatedAt
)

private fun LibraryUiItem.toProgressEditorSeed(): ProgressEditorSeed = ProgressEditorSeed(
    kinopoiskId = kinopoiskId,
    title = title,
    year = subtitle?.toIntOrNull(),
    posterUrl = posterUrl,
    type = type,
    ratingKinopoisk = ratingText?.replace("KP", "")?.replace("★", "")?.trim()?.toDoubleOrNull(),
    profile = toEditorProfile()
)

private fun FilmItem.toProgressEditorSeed(profile: UserFilmProfile?): ProgressEditorSeed = ProgressEditorSeed(
    kinopoiskId = kinopoiskId,
    title = nameRu ?: nameOriginal ?: "Без названия",
    year = year,
    posterUrl = posterUrlPreview,
    type = null,
    ratingKinopoisk = ratingKinopoisk,
    profile = profile
)

private data class GridMetrics(
    val columns: Int
)

private val FloatingBottomContentPadding = 112.dp
private val SearchChromeHeight = 70.dp
// Библиотека: 48dp поле поиска + 8dp до вкладок — тот же отступ, что под вкладками,
// чтобы после строки поиска не оставалось большого пустого резерва.
private val LibrarySearchChromeHeight = 56.dp
private val ExitConfirmWindowMs = 2_000L

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
    // Long-press on a library/discover cover opens the "Прогресс просмотра" sheet instantly
    // from tile-local data — no network, no details-page navigation.
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit = {},
    onDiscoverCategorySelected: (DiscoverCategory) -> Unit,
    onLoadMore: () -> Unit,
    onRemoveFromHistory: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    // Офлайн-библиотека: активные скачивания и скачанные серии.
    onOpenDownloads: () -> Unit = {},
    onUpdateFilters: (SearchFilterState) -> Unit = {},
    onToggleFilterSheet: (Boolean) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    // Тап по плитке новости в каруселе: открыть сам пост, а не всю ленту.
    onOpenTopic: (Int) -> Unit = {},
    // Тестовый TikTok-фид рекомендаций: кнопка «Лента» в нижней пилюле после «Обзора»
    // (TV-раскладка; телефон показывает ленту секцией ниже через feedContent).
    onOpenRecommendationsFeed: () -> Unit = {},
    // Лента рекомендаций как 4-я секция в том же окружении: один Scaffold, одна
    // пилюля, один дебаунс переключений. Слот привозит сам экран ленты
    // (Android-only, живёт в app-модуле); вызов получает переключатель секций,
    // которым лента пользуется для кнопок Библиотека/Обзор/Ещё.
    // Без слота (desktop) секция FEED недоступна, пилюля из трёх кнопок.
    feedContent: (@Composable (onSelectSection: (MainSection) -> Unit) -> Unit)? = null,
    // Интенсивность скролла ленты для физики пилюли (аналог contentScrollIntensity).
    feedIntensity: Float = 0f,
    // Лента «Обзора»: ретрай подборок и кнопка «Все» на секции (см. OverviewModels).
    onRetryOverview: () -> Unit = {},
    onSeeAll: (OverviewSeeAll) -> Unit = {},
    // Повторный тап «Обзора» в пилюле: сбросить поиск/фильтры, вернуть ленту секций.
    onDiscoverReset: () -> Unit = {},
    // Глифы кнопок пилюли: Android (KinoApp) инъекцией возвращает кастомные
    // drawable-иконки (как до KMP M4), без инъекции (desktop) рисуются
    // material-фолбэки. Общий код не зависит от res-ID приложения.
    feedGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    libraryGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    discoverGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    moreGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    onLibrarySortSelected: (hd.kinoshka.app.data.local.LibrarySortType) -> Unit = {},
    librarySortType: hd.kinoshka.app.data.local.LibrarySortType = hd.kinoshka.app.data.local.LibrarySortType.LAST_VIEWED,
    librarySortReversed: Boolean = false,
    onLibrarySortReversedChanged: (Boolean) -> Unit = {},
    libraryGroupType: LibraryGroupType = LibraryGroupType.NONE,
    onLibraryGroupSelected: (LibraryGroupType) -> Unit = {},
    onHentaiVisibilityChanged: (Boolean) -> Unit = {},
    onInstantSearch: (String) -> Unit = {},
    onRemoveSearchHistory: (String) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    // Android-only возможности (Загрузки/Профиль/ТикТок-лента): на desktop их экранов нет.
    androidFeaturesAvailable: Boolean = true
) {
    // TV-дизайн (ПК/планшет landscape/ТВ): полностью другой макет с той же моделью состояния.
    if (hd.kinoshka.app.ui.tv.rememberTvLayout()) {
        hd.kinoshka.app.ui.tv.HomeScreenTv(
            state = state,
            onQueryChange = onQueryChange,
            onSubmitSearch = onSubmitSearch,
            onRetry = onRetry,
            onTabSelected = onTabSelected,
            onContentTypeSelected = onContentTypeSelected,
            onOpenFilm = onOpenFilm,
            onOpenHistoryFilm = onOpenHistoryFilm,
            onOpenFilmEditor = onOpenFilmEditor,
            onDiscoverCategorySelected = onDiscoverCategorySelected,
            onLoadMore = onLoadMore,
            onRemoveFromHistory = onRemoveFromHistory,
            onOpenProfile = onOpenProfile,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onOpenDownloads = onOpenDownloads,
            onUpdateFilters = onUpdateFilters,
            onToggleFilterSheet = onToggleFilterSheet,
            onOpenCalendar = onOpenCalendar,
            onOpenFeed = onOpenFeed,
            onOpenRecommendationsFeed = onOpenRecommendationsFeed,
            onLibrarySortSelected = onLibrarySortSelected,
            librarySortReversed = librarySortReversed,
            onLibrarySortReversedChanged = onLibrarySortReversedChanged,
            onHentaiVisibilityChanged = onHentaiVisibilityChanged,
            onInstantSearch = onInstantSearch,
            onRemoveSearchHistory = onRemoveSearchHistory,
            onClearSearchHistory = onClearSearchHistory,
            androidFeaturesAvailable = androidFeaturesAvailable,
        )
        return
    }
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

    // Секция переживает снятие с композиции (уход на details/{id} и возврат):
    // иначе возврат из тайтла, открытого из ленты, сбрасывал бы на Библиотеку.
    val initialSection = when (state.tab) {
        HomeTab.HISTORY -> MainSection.LIBRARY
        HomeTab.MORE -> MainSection.MORE
        else -> MainSection.DISCOVER
    }
    // Секция переживает снятие с композиции (уход на details/{id} и возврат):
    // иначе возврат из тайтла, открытого из ленты, сбрасывал бы на Библиотеку.
    // rememberSaveable(enum) здесь не заводится, поэтому сохраняем флаг.
    var feedSectionSaved by rememberSaveable { mutableStateOf(false) }
    var section by remember(state.tab) {
        mutableStateOf(if (feedSectionSaved) MainSection.FEED else initialSection)
    }
    // Возврат Назад из секции ленты — в предыдущий раздел, а не на рабочий стол.
    var prevSection by remember { mutableStateOf(MainSection.LIBRARY) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var discoverQuery by rememberSaveable { mutableStateOf("") }
    var moreQuery by rememberSaveable { mutableStateOf("") }
    val activeQuery = when (section) {
        MainSection.LIBRARY -> libraryQuery
        MainSection.DISCOVER -> discoverQuery
        MainSection.MORE -> moreQuery
        // У ленты своего поиска нет, шапка в её секции скрыта.
        MainSection.FEED -> ""
    }
    // Instant search: debounce the DISCOVER query and fire a cancellable search while typing, so
    // results appear before the user hits the IME search key. ~350ms avoids hammering the API.
    LaunchedEffect(discoverQuery, section, state.contentType) {
        if (section == MainSection.DISCOVER && discoverQuery.trim().length >= 2) {
            kotlinx.coroutines.delay(350)
            onInstantSearch(discoverQuery)
        }
    }
    // Любое состояние «внутри раздела» Обзора: текст поиска, результаты поиска,
    // активные фильтры (жанр/сезон/топ через «Все»), открытая категория
    // (Топ-250/Сериалы/Сейчас смотрят) или именованный раздел (discoverTitle).
    // Назад отсюда — на главную ленту секций, а не на рабочий стол.
    val isCategoryBrowse = state.contentType == ContentType.FILMS &&
        (state.discoverCategory != DiscoverCategory.POPULAR || state.discoverTitle != null)
    val isInsideDiscoverSection = section == MainSection.DISCOVER &&
        (discoverQuery.isNotEmpty() || state.isSearchResult || state.filterState.isActive ||
            isCategoryBrowse || state.discoverTitle != null)
    // Root screen: a single Back gesture must not kill the app. The first Back shows a hint and
    // arms a short confirm window; a second Back inside it exits. While a Discover search is
    // active this is disabled so Back closes the search instead (handler below).
    var lastBackExitAttemptAt by remember { mutableStateOf(0L) }
    val platformActions = rememberKinoPlatformActions()
    KinoBackHandler(enabled = !isInsideDiscoverSection && section != MainSection.FEED) {
        val now = System.currentTimeMillis()
        if (now - lastBackExitAttemptAt < ExitConfirmWindowMs) {
            platformActions.exitApp()
        } else {
            lastBackExitAttemptAt = now
            platformActions.showToast("Повторите жест «Назад», чтобы закрыть приложение")
        }
    }
    // System Back внутри раздела Обзора: первый Back при поднятой клавиатуре только
    // прячет её; следующий — гасит поиск/фильтры и возвращает ленту секций
    // (onDiscoverReset), вместо выхода из приложения.
    KinoBackHandler(enabled = isInsideDiscoverSection) {
        if (isSearchFocused) {
            isSearchFocused = false
            focusManager.clearFocus()
            return@KinoBackHandler
        }
        isSearchFocused = false
        focusManager.clearFocus()
        discoverQuery = ""
        onQueryChange("")
        onDiscoverReset()
    }
    var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.WATCHING) }
    var libraryFilter by rememberSaveable { mutableStateOf(LibraryFilterType.ALL) }
    // topSignal прокручивает страницы библиотеки к началу (клик по вкладке, смена сортировки);
    // resetCount пересоздаёт пейджер на «Смотрю» (повторный тап «Библиотека», возврат из ленты).
    var libraryTopSignal by rememberSaveable { mutableStateOf(0) }
    var libraryResetCount by rememberSaveable { mutableStateOf(0) }
    // Удержание на заголовке раздела: диалог статистики вкладки.
    var libraryStatsTab by remember { mutableStateOf<LibraryTab?>(null) }
    // Скролл ленты «Обзора» по вкладкам Кино/Аниме: живёт на уровне экрана,
    // поэтому уход в раздел («Все»), детали и смена вкладок позицию не сносят.
    // Тот же приём, что libraryListStates/libraryGridStates ниже.
    val overviewListStates = rememberSaveable(
        saver = Saver(
            save = { map ->
                map.entries.sortedBy { it.key.ordinal }
                    .map { it.value.firstVisibleItemIndex to it.value.firstVisibleItemScrollOffset }
            },
            restore = { saved ->
                val restored = ContentType.entries.zip(saved) { ct, pos ->
                    ct to LazyListState(pos.first, pos.second)
                }.toMap()
                ContentType.entries.associateWith { restored[it] ?: LazyListState() }
            }
        )
    ) {
        ContentType.entries.associateWith { LazyListState() }
    }
    // Интенсивность вертикального скролла контента (0..1 по скорости) для физики
    // нижней пилюли: сетки сообщают сюда через колбэки. Горизонтальный свайп
    // вкладок и жесты без движения — не в счёт.
    var contentScrollIntensity by remember { mutableStateOf(0f) }
    val searchRowHeight =
        if (section == MainSection.LIBRARY) LibrarySearchChromeHeight else SearchChromeHeight
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
    // Заголовок открытого раздела Обзора: название секции («Все») или
    // категория. Только для разделов — при обычном текстовом поиске через
    // строку поиска шапки нет, поле ввода остаётся на месте.
    val sectionHeader: String? = if (section == MainSection.DISCOVER) {
        when {
            state.discoverTitle != null -> state.discoverTitle
            state.contentType == ContentType.FILMS && state.discoverCategory != DiscoverCategory.POPULAR ->
                state.discoverCategory.title
            else -> null
        }
    } else null
    // В разделе шапка заменяет строку поиска, фильтр и переключатель
    // Кино/Аниме. Пока набирается текст, поле ввода остаётся на месте.
    val showSectionHeader = sectionHeader != null && !isSearchFocused
    // Возврат из раздела на главную ленту секций: тот же сброс, что по Back.
    val backToOverviewFeed: () -> Unit = {
        isSearchFocused = false
        focusManager.clearFocus()
        discoverQuery = ""
        onQueryChange("")
        onDiscoverReset()
    }

    // Переключение раздела нижней пилюли: сохраняем поисковый запрос текущего раздела
    // и применяем прежнюю логику табов/категорий. Повторный тап по активному разделу
    // ничего не пересоздаёт: Библиотека лишь едет к началу, Обзор гасит поиск только
    // если он активен. Иначе каждый тап моргал полным ребилдом пейджера.
    // Дебаунс переключения разделов ЕДИНЫЙ для всех четырёх кнопок: раньше у ленты
    // был свой маршрут со своим дебаунсом, и кросс-спам (Лента→секция без паузы)
    // складывал тяжёлый фид с плеерами наперегонки с транзишенами — чёрный экран.
    var lastSectionSwitchMs by remember { mutableLongStateOf(0L) }
    val handleNav: (MainSection) -> Unit = { target ->
        if (target == section) {
            when (target) {
                MainSection.LIBRARY -> libraryTopSignal++
                MainSection.DISCOVER -> {
                    if (discoverQuery.isNotEmpty() || state.isSearchResult || state.filterState.isActive ||
                        state.discoverCategory != DiscoverCategory.POPULAR || state.discoverTitle != null
                    ) {
                        discoverQuery = ""
                        onQueryChange("")
                        onDiscoverReset()
                    }
                }
                MainSection.MORE -> Unit
                // Повторный тап по ленте ничего не делает (скролл наверх не проброшен).
                MainSection.FEED -> Unit
            }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastSectionSwitchMs >= 250) {
                lastSectionSwitchMs = now
                when (section) {
                    MainSection.LIBRARY -> libraryQuery = state.query
                    MainSection.DISCOVER -> discoverQuery = state.query
                    MainSection.MORE -> moreQuery = state.query
                    // У ленты поискового запроса нет — сохранять нечего.
                    MainSection.FEED -> Unit
                }
                if (target == MainSection.FEED && section != MainSection.FEED) {
                    prevSection = section
                }
                section = target
                feedSectionSaved = (target == MainSection.FEED)
                when (target) {
                    MainSection.LIBRARY -> {
                        libraryTab = LibraryTab.WATCHING
                        // Пересоздаём пейджер только при входе из другого раздела
                        // (включая возврат из ленты — как раньше по флагу).
                        libraryResetCount++
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
                    // Вход в ленту: запросы секций уже сохранены выше, таб модели
                    // не трогаем — возврат Назад/пилюлей идёт через prevSection.
                    MainSection.FEED -> Unit
                }
            }
        }
    }
    // Назад из секции ленты — в предыдущий раздел через общий handleNav
    // (с дебаунсом и восстановлением запроса), а не на рабочий стол.
    KinoBackHandler(enabled = section == MainSection.FEED) {
        handleNav(prevSection)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            // Единая плавающая пилюля (общий компонент с фидом рекомендаций).
            // Глифы: Android (KinoApp) инъекцией возвращает кастомные drawable-иконки,
            // без инъекции (desktop) рисуются material-фолбэки.
            BottomNavPill(
                items = listOf(
                    NavPillItem(
                        contentDescription = "Библиотека",
                        selected = section == MainSection.LIBRARY,
                        onClick = { handleNav(MainSection.LIBRARY) },
                        glyph = { sel ->
                            val custom = libraryGlyph
                            if (custom != null) {
                                custom(sel)
                            } else {
                                Icon(
                                    imageVector = if (sel) Icons.AutoMirrored.Filled.List else Icons.AutoMirrored.Outlined.List,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    ),
                    NavPillItem(
                        contentDescription = "Обзор",
                        selected = section == MainSection.DISCOVER,
                        onClick = { handleNav(MainSection.DISCOVER) },
                        glyph = { sel ->
                            val custom = discoverGlyph
                            if (custom != null) {
                                custom(sel)
                            } else {
                                Icon(
                                    imageVector = if (sel) Icons.Filled.Explore else Icons.Outlined.Explore,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    ),
                    NavPillItem(
                        contentDescription = "Лента",
                        selected = section == MainSection.FEED,
                        onClick = {
                            // Секция в том же окружении: круг переезжает общей
                            // анимацией пилюли под дебаунсом handleNav. Без слота
                            // (desktop) — старый колбэк отдельного экрана.
                            if (feedContent != null) {
                                handleNav(MainSection.FEED)
                            } else {
                                onOpenRecommendationsFeed()
                            }
                        },
                        glyph = { sel ->
                            val custom = feedGlyph
                            if (custom != null) {
                                custom(sel)
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Feed,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    ),
                    NavPillItem(
                        contentDescription = "Ещё",
                        selected = section == MainSection.MORE,
                        onClick = { handleNav(MainSection.MORE) },
                        glyph = { sel ->
                            val custom = moreGlyph
                            if (custom != null) {
                                custom(sel)
                            } else {
                                Icon(
                                    imageVector = if (sel) Icons.Filled.MoreHoriz else Icons.Outlined.MoreHoriz,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    )
                ),
                isAmoled = state.themeMode == AppThemeMode.AMOLED,
                scrollIntensity = if (section == MainSection.FEED) feedIntensity else contentScrollIntensity
            )
        }
    ) { innerPadding ->
        // Лента — четвёртая секция в том же Scaffold: полноэкранный чёрный слот
        // без домашней шапки и отступов, поверх — та же общая пилюля.
        val isFeedSection = section == MainSection.FEED && feedContent != null
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isFeedSection) {
                        SolidColor(Color.Black)
                    } else if (state.themeMode == AppThemeMode.AMOLED) {
                        SolidColor(MaterialTheme.colorScheme.background)
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.16f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    }
                )
                .then(
                    if (isFeedSection) {
                        // Слот ленты рисует себя сам во весь экран (как отдельным
                        // маршрутом): домашние отступы и ime здесь ни к чему.
                        Modifier
                    } else {
                        Modifier
                            .padding(top = innerPadding.calculateTopPadding())
                            .imePadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    }
                )
        ) {
            // Домашний контент под лентой не компонуем — не тратим кадры и память.
            if (!isFeedSection) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(searchRowHeight)
                    ) {
                        if (showSectionHeader && sectionHeader != null) {
                            DiscoverSectionHeader(
                                title = sectionHeader,
                                onBack = backToOverviewFeed,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                        SearchRow(
                            query = activeQuery,
                            avatar = state.profileAvatar,
                            placeholder = when (section) {
                                MainSection.LIBRARY -> "Поиск в библиотеке"
                                MainSection.DISCOVER -> if (state.contentType == ContentType.ANIME) "Поиск аниме" else "Поиск фильмов"
                                MainSection.MORE -> "Поиск по разделу Ещё"
                                // Шапка в секции ленты скрыта, ветка для exhaustive.
                                MainSection.FEED -> ""
                            },
                            section = section,
                            contentType = state.contentType,
                            onContentTypeSelected = onContentTypeSelected,
                            libraryFilter = libraryFilter,
                            onLibraryFilterSelected = { libraryFilter = it },
                            librarySort = librarySortType,
                            onLibrarySortSelected = { sortType ->
                                libraryTopSignal++
                                onLibrarySortSelected(sortType)
                            },
                            librarySortReversed = librarySortReversed,
                            onLibrarySortReversedChanged = { reversed ->
                                libraryTopSignal++
                                onLibrarySortReversedChanged(reversed)
                            },
                            libraryGroup = libraryGroupType,
                            onLibraryGroupSelected = { group ->
                                libraryTopSignal++
                                onLibraryGroupSelected(group)
                            },
                            showHentaiInLibrary = state.showHentaiInLibrary,
                            onHentaiVisibilityChanged = onHentaiVisibilityChanged,
                            isFilterActive = state.filterState.isActive,
                            onFilterClick = { onToggleFilterSheet(true) },
                            onQueryChange = { value ->
                                when (section) {
                                    MainSection.LIBRARY -> libraryQuery = value
                                    MainSection.DISCOVER -> discoverQuery = value
                                    MainSection.MORE -> moreQuery = value
                                    // В секции ленты шапки нет — ввод сюда не доходит.
                                    MainSection.FEED -> Unit
                                }
                                onQueryChange(value)
                            },
                            onSearch = {
                                isSearchFocused = false
                                focusManager.clearFocus()
                                if (section == MainSection.DISCOVER) {
                                    onSubmitSearch()
                                }
                            },
                            onFocusChanged = { focused ->
                                isSearchFocused = focused
                            },
                            onAvatarClick = onOpenProfile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(searchRowAlpha)
                        )
                        }
                    }

                    // Recent searches — overlay positioned right below the search bar
                }

                // Тап мимо интерактива (пустое место) гасит фокус поиска и клавиатуру.
                // Тапы по плиткам/кнопкам сюда не доходят — их забирают clickable.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(focusManager) {
                            detectTapGestures(onTap = { focusManager.clearFocus() })
                        }
                ) {
                    when (section) {
                        MainSection.LIBRARY -> key(libraryResetCount) {
                            val pagerState = rememberPagerState(
                                initialPage = libraryTab.ordinal,
                                pageCount = { LibraryTab.entries.size }
                            )
                            // Скролл каждой вкладки живёт выше пейджера: HorizontalPager выкидывает
                            // страницы вне вьюпорта из композиции. Обычный remember здесь не
                            // выживает даже уход на details/{id}: NavHost снимает весь home с
                            // композиции, и переживает это только rememberSaveable. Сносятся
                            // состояния только явным ресетом раздела (libraryResetCount в key).
                            val libraryListStates = rememberSaveable(
                                saver = Saver(
                                    save = { map ->
                                        map.entries.sortedBy { it.key.ordinal }
                                            .map { it.value.firstVisibleItemIndex to it.value.firstVisibleItemScrollOffset }
                                    },
                                    restore = { saved ->
                                        // Несовпадение размера (обновление с другим набором вкладок) —
                                        // недостающее добиваем свежими состояниями, а не крашем в getValue.
                                        val restored = LibraryTab.entries.zip(saved) { tab, pos ->
                                            tab to LazyListState(pos.first, pos.second)
                                        }.toMap()
                                        LibraryTab.entries.associateWith { restored[it] ?: LazyListState() }
                                    }
                                )
                            ) {
                                LibraryTab.entries.associateWith { LazyListState() }
                            }
                            val libraryGridStates = rememberSaveable(
                                saver = Saver(
                                    save = { map ->
                                        map.entries.sortedBy { it.key.ordinal }
                                            .map { it.value.firstVisibleItemIndex to it.value.firstVisibleItemScrollOffset }
                                    },
                                    restore = { saved ->
                                        val restored = LibraryTab.entries.zip(saved) { tab, pos ->
                                            tab to LazyGridState(pos.first, pos.second)
                                        }.toMap()
                                        LibraryTab.entries.associateWith { restored[it] ?: LazyGridState() }
                                    }
                                )
                            ) {
                                LibraryTab.entries.associateWith { LazyGridState() }
                            }

                            LaunchedEffect(pagerState.settledPage) {
                                libraryTab = LibraryTab.entries[pagerState.settledPage]
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                LibraryTabs(
                                    pagerState = pagerState,
                                    onSelect = { target ->
                                        libraryTab = target
                                        libraryTopSignal++
                                    },
                                    onTabLongClick = { tab -> libraryStatsTab = tab }
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                HorizontalPager(
                                    state = pagerState,
                                    // Without a gap the last column of one tab and the first
                                    // column of the next visually merge at the page seam
                                    // mid-swipe; 10.dp mirrors the grid's own column spacing.
                                    pageSpacing = 10.dp,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    val pageTab = LibraryTab.entries[page]
                                    val items = libraryItemsByTab[pageTab].orEmpty()
                                    // The library has its own content filter (libraryFilter).
                                    // Do not also filter it by the global Discover/Search type:
                                    // changing Kino/Anime in search must not hide library items.
                                    LibraryPageGrid(
                                        items = items,
                                        tab = pageTab,
                                        queryActive = normalizedQuery.isNotEmpty(),
                                        historyMode = pageTab == LibraryTab.HISTORY,
                                        onOpenHistoryFilm = onOpenHistoryFilm,
                                        onOpenFilmEditor = onOpenFilmEditor,
                                        onRemoveFromHistory = onRemoveFromHistory,
                                        metrics = libraryMetrics,
                                        listState = libraryListStates.getValue(pageTab),
                                        gridState = libraryGridStates.getValue(pageTab),
                                        scrollToTopSignal = libraryTopSignal,
                                        onScrollActivity = {
                                            contentScrollIntensity = it
                                            // Скролл контента гасит фокус поиска и клавиатуру.
                                            if (it > 0f && isSearchFocused) {
                                                isSearchFocused = false
                                                focusManager.clearFocus()
                                            }
                                        },
                                        group = libraryGroupType
                                    )
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
                            ) { targetContentType ->
                                DiscoverContent(
                                    state = state.copy(contentType = targetContentType),
                                    sourceItems = discoverItems,
                                    metrics = discoverMetrics,
                                    statusByFilmId = statusByFilmId,
                                    progressByFilmId = progressByFilmId,
                                    onRetry = onRetry,
                                    onOpenFilm = onOpenFilm,
                                    onOpenFilmEditor = onOpenFilmEditor,
                                    onLoadMore = onLoadMore,
                                    onOpenCalendar = onOpenCalendar,
                                    onOpenFeed = onOpenFeed,
                                    onOpenTopic = onOpenTopic,
                                    overviewSections = if (targetContentType == ContentType.ANIME) {
                                        state.overviewAnimeSections
                                    } else {
                                        state.overviewFilmSections
                                    },
                                    overviewLoading = state.overviewLoading,
                                    overviewError = state.overviewError,
                                    onRetryOverview = onRetryOverview,
                                    onSeeAll = onSeeAll,
                                    feedListState = overviewListStates.getValue(targetContentType),
                                    onScrollActivity = {
                                        contentScrollIntensity = it
                                        // Скролл контента гасит фокус поиска и клавиатуру.
                                        if (it > 0f && isSearchFocused) {
                                            isSearchFocused = false
                                            focusManager.clearFocus()
                                        }
                                    }
                                )
                            }
                        }

                        MainSection.MORE -> {
                            MoreContent(
                                query = state.query.trim(),
                                onOpenProfile = onOpenProfile,
                                onOpenSettings = onOpenSettings,
                                onOpenAbout = onOpenAbout,
                                onOpenDownloads = onOpenDownloads
                            )
                        }
                        // Секция ленты рисуется слотом ниже вместо Column — сюда не доходит.
                        MainSection.FEED -> Unit
                    }

                    // Удержание на заголовке раздела библиотеки: статистика по типам.
                    libraryStatsTab?.let { statsTab ->
                        LibraryTabStatsDialog(
                            tab = statsTab,
                            items = libraryItemsByTab[statsTab].orEmpty(),
                            onDismiss = { libraryStatsTab = null }
                        )
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = section == MainSection.DISCOVER && isSearchFocused && state.searchHistory.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .zIndex(20f)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
                            tonalElevation = 6.dp
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                SearchHistoryRow(
                                    history = state.searchHistory,
                                    contentType = state.contentType,
                                    onPick = { q ->
                                        discoverQuery = q
                                        onQueryChange(q)
                                        isSearchFocused = false
                                        focusManager.clearFocus()
                                        onSubmitSearch()
                                    },
                                    onRemove = onRemoveSearchHistory,
                                    onClear = onClearSearchHistory
                                )
                            }
                        }
                    }
                }
            }
            }
            // Секция ленты: полноэкранный слот вместо домашнего Column.
            if (isFeedSection) {
                feedContent?.invoke { target -> handleNav(target) }
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

/** Иконки переключателя контента в шапках Обзора и Библиотеки (текст заменён иконками). */
private fun LibraryFilterType.toSwitcherIcon(): ImageVector = when (this) {
    LibraryFilterType.ALL -> Icons.Filled.Apps
    LibraryFilterType.FILMS -> Icons.Filled.Movie
    LibraryFilterType.ANIME -> Icons.Filled.Animation
}

private fun ContentType.toSwitcherIcon(): ImageVector = when (this) {
    ContentType.FILMS -> Icons.Filled.Movie
    ContentType.ANIME -> Icons.Filled.Animation
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
    librarySortReversed: Boolean = false,
    onLibrarySortReversedChanged: ((Boolean) -> Unit)? = null,
    libraryGroup: LibraryGroupType = LibraryGroupType.NONE,
    onLibraryGroupSelected: (LibraryGroupType) -> Unit = {},
    showHentaiInLibrary: Boolean = true,
    onHentaiVisibilityChanged: ((Boolean) -> Unit)? = null,
    isFilterActive: Boolean = false,
    onFilterClick: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                onFocusChanged?.invoke(focusState.isFocused)
                            }
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

            // 2. Choice of anime or cinema (No Emojis!)
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
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {
                        val next = if (contentType == ContentType.FILMS) ContentType.ANIME else ContentType.FILMS
                        onContentTypeSelected?.invoke(next)
                    },
                shape = CircleShape,
                color = discoverBgColor
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = contentType,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.88f, animationSpec = tween(220))) togetherWith
                            (fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.88f, animationSpec = tween(180)))
                        },
                        label = "contentTypeAnim"
                    ) { targetType ->
                        Icon(
                            imageVector = targetType.toSwitcherIcon(),
                            contentDescription = if (targetType == ContentType.FILMS) "Кино" else "Аниме",
                            tint = discoverTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else if (section == MainSection.LIBRARY) {
            Spacer(modifier = Modifier.width(6.dp))

            // Сортировка/группировка — шитом: выпадающее меню перестало вмещать все настройки.
            var showLibrarySettingsSheet by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { showLibrarySettingsSheet = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "Настройки библиотеки",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (showLibrarySettingsSheet) {
                LibrarySettingsSheet(
                    librarySort = librarySort,
                    onLibrarySortSelected = onLibrarySortSelected,
                    libraryGroup = libraryGroup,
                    onLibraryGroupSelected = onLibraryGroupSelected,
                    librarySortReversed = librarySortReversed,
                    onLibrarySortReversedChanged = onLibrarySortReversedChanged,
                    showHentaiInLibrary = showHentaiInLibrary,
                    onHentaiVisibilityChanged = onHentaiVisibilityChanged,
                    onDismiss = { showLibrarySettingsSheet = false }
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Переключатель [Все] [Кино] [Аниме]: иконкой вместо текста, клик — циклически.
            val libBgColor by animateColorAsState(
                when (libraryFilter) {
                    LibraryFilterType.ALL -> MaterialTheme.colorScheme.surfaceContainerHigh
                    LibraryFilterType.FILMS -> MaterialTheme.colorScheme.primaryContainer
                    LibraryFilterType.ANIME -> MaterialTheme.colorScheme.tertiaryContainer
                },
                animationSpec = tween(280), label = "libBg"
            )
            val libIconColor by animateColorAsState(
                when (libraryFilter) {
                    LibraryFilterType.ALL -> MaterialTheme.colorScheme.onSurface
                    LibraryFilterType.FILMS -> MaterialTheme.colorScheme.onPrimaryContainer
                    LibraryFilterType.ANIME -> MaterialTheme.colorScheme.onTertiaryContainer
                },
                animationSpec = tween(280), label = "libIcon"
            )
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {
                        val next = when (libraryFilter) {
                            LibraryFilterType.ALL -> LibraryFilterType.FILMS
                            LibraryFilterType.FILMS -> LibraryFilterType.ANIME
                            LibraryFilterType.ANIME -> LibraryFilterType.ALL
                        }
                        onLibraryFilterSelected?.invoke(next)
                    },
                shape = CircleShape,
                color = libBgColor
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = libraryFilter,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.88f, animationSpec = tween(220))) togetherWith
                            (fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.88f, animationSpec = tween(180)))
                        },
                        label = "libFilterAnim"
                    ) { targetFilter ->
                        Icon(
                            imageVector = targetFilter.toSwitcherIcon(),
                            contentDescription = targetFilter.label,
                            tint = libIconColor,
                            modifier = Modifier.size(20.dp)
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

/** Строка-переключатель в шите настроек библиотеки: клик по всей строке. */
@Composable
private fun LibrarySheetSwitchRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Шит настроек библиотеки вместо выпадающего меню: сортировка, группировка по общим
 * признакам и переключатели (фильтр Все/Кино/Аниме — кнопкой в шапке, не здесь).
 * Выбор не закрывает шит — результат виден сразу за полупрозрачным фоном.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibrarySettingsSheet(
    librarySort: hd.kinoshka.app.data.local.LibrarySortType,
    onLibrarySortSelected: ((hd.kinoshka.app.data.local.LibrarySortType) -> Unit)?,
    libraryGroup: LibraryGroupType,
    onLibraryGroupSelected: (LibraryGroupType) -> Unit,
    librarySortReversed: Boolean,
    onLibrarySortReversedChanged: ((Boolean) -> Unit)?,
    showHentaiInLibrary: Boolean,
    onHentaiVisibilityChanged: ((Boolean) -> Unit)?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // skipPartiallyExpanded: только Hidden и Expanded (полураскрытия нет).
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetSquashStretch()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Настройки библиотеки",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Сортировка",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hd.kinoshka.app.data.local.LibrarySortType.entries.forEach { sortType ->
                        FilterChip(
                            selected = librarySort == sortType,
                            onClick = { onLibrarySortSelected?.invoke(sortType) },
                            label = { Text(sortType.label, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Группировка",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LibraryGroupType.entries.forEach { group ->
                        FilterChip(
                            selected = libraryGroup == group,
                            onClick = { onLibraryGroupSelected(group) },
                            label = { Text(group.label, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }

            LibrarySheetSwitchRow(
                label = "Обратный порядок",
                checked = librarySortReversed,
                onToggle = { onLibrarySortReversedChanged?.invoke(!librarySortReversed) }
            )
            LibrarySheetSwitchRow(
                label = "Показывать хентай",
                checked = showHentaiInLibrary,
                onToggle = { onHentaiVisibilityChanged?.invoke(!showHentaiInLibrary) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryRow(
    history: List<hd.kinoshka.app.data.local.SearchHistoryRecord>,
    contentType: ContentType,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    val filtered = history.filter { it.contentType == contentType.name }.take(10)
    if (filtered.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        filtered.forEach { item ->
            item(key = item.query) {
                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .widthIn(min = 112.dp, max = 240.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onPick(item.query) },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.query,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(
                            onClick = { onRemove(item.query) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Удалить",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
        // Clear all button at end
        item {
            TextButton(
                onClick = onClear,
                modifier = Modifier
                    .height(40.dp)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                Text(
                    text = "Очистить историю",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
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
            // Same generic avatar icon as the profile's AvatarPreview — no emoji fallbacks.
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Аватар",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTabs(
    pagerState: PagerState,
    onSelect: (LibraryTab) -> Unit,
    // Удержание на заголовке раздела: статистика вкладки (кол-во тайтлов + разбивка по типам).
    onTabLongClick: (LibraryTab) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    SecondaryScrollableTabRow(
        selectedTabIndex = pagerState.currentPage,
        edgePadding = 10.dp,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {},
        modifier = Modifier.fillMaxWidth()
    ) {
        LibraryTab.entries.forEachIndexed { index, tab ->
            val isSelected = pagerState.currentPage == index
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onSelect(tab)
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        onLongClick = { onTabLongClick(tab) }
                    )
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPageGrid(
    items: List<LibraryUiItem>,
    tab: LibraryTab,
    queryActive: Boolean,
    historyMode: Boolean,
    onOpenHistoryFilm: (Int) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit = {},
    onRemoveFromHistory: (Int) -> Unit,
    metrics: GridMetrics,
    listState: LazyListState,
    gridState: LazyGridState,
    scrollToTopSignal: Int = 0,
    onScrollActivity: (Float) -> Unit = {},
    group: LibraryGroupType = LibraryGroupType.NONE
) {
    var pendingDeleteId by remember { mutableIntStateOf(0) }
    // Интенсивность скролла по фактическому смещению активной сетки.
    val activeIndex = if (metrics.columns == 1) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
    val activeOffset = if (metrics.columns == 1) listState.firstVisibleItemScrollOffset else gridState.firstVisibleItemScrollOffset
    ScrollIntensityEffect(
        positionIndex = activeIndex,
        positionOffset = activeOffset,
        onIntensity = onScrollActivity
    )
    // Клик по вкладке или смена сортировки: список всегда показываем с начала. Lazy-контейнер
    // с ключами иначе «якорится» за первым видимым элементом и остаётся на старой позиции.
    // Первый запуск эффекта (вход в раздел / возврат из деталей) пропускаем: иначе
    // восстановленная из saveable позиция тут же сбрасывалась бы в 0.
    var topSignalArmed by remember { mutableStateOf(false) }
    LaunchedEffect(scrollToTopSignal) {
        if (topSignalArmed) {
            if (metrics.columns == 1) listState.scrollToItem(0) else gridState.scrollToItem(0)
        } else {
            topSignalArmed = true
        }
    }

    if (items.isEmpty()) {
        LibraryEmptyState(tab = tab, queryActive = queryActive)
        return
    }

    val sections = remember(items, group) { items.groupForLibrary(group) }

    if (metrics.columns == 1) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            sections.forEach { section ->
                if (section.showHeader) {
                    item(key = "group_${section.key}", contentType = { "library_group_header" }) {
                        LibraryGroupHeader(label = section.label, count = section.items.size)
                    }
                }
                items(section.items, key = { it.kinopoiskId }, contentType = { "library_vertical_item" }) { item ->
                    LibraryVerticalRow(
                        item = item,
                        onOpen = { onOpenHistoryFilm(item.kinopoiskId) },
                        onLongPress = {
                            if (historyMode && item.viewedAtMillis != null) {
                                pendingDeleteId = item.kinopoiskId
                            } else {
                                // Long-press outside История opens the progress editor.
                                onOpenFilmEditor(item.toProgressEditorSeed())
                            }
                        }
                    )
                }
            }
        }
    } else {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(metrics.columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        sections.forEach { section ->
            if (section.showHeader) {
                // Заголовок группы занимает всю ширину строки сетки.
                item(key = "group_${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = { "library_group_header" }) {
                    LibraryGroupHeader(label = section.label, count = section.items.size)
                }
            }
            items(
                items = section.items,
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
                        } else {
                            // Long-press outside История opens the progress editor.
                            onOpenFilmEditor(item.toProgressEditorSeed())
                        }
                    }
                )
            }
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

/** Заголовок группы в сетке/списке библиотеки: подпись признака + число тайтлов. */
@Composable
private fun LibraryGroupHeader(label: String, count: Int) {
    Text(
        text = "$label · $count",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    )
}

/**
 * Диалог удержания на вкладке библиотеки: сколько тайтлов в разделе и как они
 * делятся по типам (ТВ, Фильм, OVA, ONA, Спешл, …). Без сети — только по плиткам.
 */
@Composable
private fun LibraryTabStatsDialog(
    tab: LibraryTab,
    items: List<LibraryUiItem>,
    onDismiss: () -> Unit
) {
    val typeStats = remember(items) { items.groupForLibrary(LibraryGroupType.TYPE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tab.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Всего тайтлов",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${items.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                typeStats.forEach { section ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = section.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${section.items.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
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
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit = {},
    onLoadMore: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenTopic: (Int) -> Unit = {},
    overviewSections: List<OverviewSection> = emptyList(),
    overviewLoading: Boolean = false,
    overviewError: String? = null,
    onRetryOverview: () -> Unit = {},
    onSeeAll: (OverviewSeeAll) -> Unit = {},
    // Скролл ленты сверху (из HomeScreen): пережить уход в раздел обязано,
    // поэтому состояние хранится выше, а не внутри ленты.
    feedListState: LazyListState,
    onScrollActivity: (Float) -> Unit = {},
    // ПК-оболочка: рисовать плоскую сетку даже когда лента секций доступна
    // (локальные пункты меню — «Фильмы» и т.п. поверх популярного списка).
    forceGrid: Boolean = false
) {
    // Local profile snapshots for the quick progress editor: matched from the library list
    // the screen already holds, so long-press never needs to fetch anything.
    val libraryById = remember(state.library) {
        state.library.associateBy { it.kinopoiskId }
    }
    // Режим поиска/фильтров — старая плоская сетка; иначе лента секций-каруселей.
    // Отдельно: полноэкранная сетка категории кино (Топ-250/Сериалы через «Все»,
    // а также «Сейчас смотрят» с discoverTitle) — её тоже нельзя подменять лентой.
    // Категорий у аниме нет, там флаг категории игнорируется, но именованный
    // раздел (discoverTitle) сетку показывает всегда.
    val isSearchMode = state.isSearchResult || state.query.isNotBlank() || state.filterState.isActive || forceGrid
    val isCategoryBrowse = state.contentType == ContentType.FILMS &&
        (state.discoverCategory != DiscoverCategory.POPULAR || state.discoverTitle != null)
    val isNamedSection = state.discoverTitle != null
    // Витрина аниме: «Продолжить просмотр» из библиотеки (дубли каруселей
    // вычитаем, как в VM для «Скоро выйдет»); нечего продолжать — фолбэк
    // на «Скоро выйдет». У кино витрина без изменений («Обсуждаемое»).
    val animeHero: Pair<List<FilmItem>, String> = remember(
        state.library, state.overviewAnimeSections, state.overviewAnimeHero
    ) {
        val exclude = state.overviewAnimeSections.flatMapTo(mutableSetOf()) { s ->
            s.items.map { it.kinopoiskId }
        }
        val watching = state.library
            .filter {
                (it.status == UserFilmStatus.WATCHING || it.status == UserFilmStatus.REWATCHING) &&
                    it.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET &&
                    it.kinopoiskId !in exclude
            }
            .sortedByDescending { it.viewedAtMillis ?: 0L }
            .take(5)
            .map { it.toHeroFilmItem() }
        if (watching.isNotEmpty()) watching to "Продолжить просмотр"
        else state.overviewAnimeHero to "Скоро выйдет"
    }
    if (!isSearchMode && !isCategoryBrowse && !isNamedSection && (overviewSections.isNotEmpty() || overviewLoading || overviewError != null)) {
        OverviewFeed(
            state = state,
            sections = overviewSections,
            listState = feedListState,
            loading = overviewLoading,
            error = overviewError,
            metrics = metrics,
            statusByFilmId = statusByFilmId,
            progressByFilmId = progressByFilmId,
            libraryById = libraryById,
            onRetryOverview = onRetryOverview,
            onSeeAll = onSeeAll,
            onOpenFilm = onOpenFilm,
            onOpenFilmEditor = onOpenFilmEditor,
            onOpenCalendar = onOpenCalendar,
            onOpenFeed = onOpenFeed,
            onOpenTopic = onOpenTopic,
            onScrollActivity = onScrollActivity,
            heroItems = if (state.contentType == ContentType.ANIME) {
                animeHero.first
            } else {
                state.overviewFilmHero
            },
            heroTitle = if (state.contentType == ContentType.ANIME) animeHero.second else "Обсуждаемое"
        )
        return
    }
    // Скролл сеток разделов/поиска переживает уход в детали (NavHost снимает home с
    // композиции — выживает только rememberSaveable). Ключ — контекст сетки.
    val gridKeyQuery = state.query.trim()
    val listState = rememberSaveable(state.contentType, state.discoverCategory, gridKeyQuery, state.filterState, state.discoverTitle, saver = LazyListState.Saver) { LazyListState() }
    val gridState = rememberSaveable(state.contentType, state.discoverCategory, gridKeyQuery, state.filterState, state.discoverTitle, saver = LazyGridState.Saver) { LazyGridState() }
    // Интенсивность скролла по фактическому смещению активной сетки.
    val activeIndex = if (metrics.columns == 1) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
    val activeOffset = if (metrics.columns == 1) listState.firstVisibleItemScrollOffset else gridState.firstVisibleItemScrollOffset
    ScrollIntensityEffect(
        positionIndex = activeIndex,
        positionOffset = activeOffset,
        onIntensity = onScrollActivity
    )
    // Сетка раздела/поиска. Шапка с названием живёт в верхней панели
    // (вместо строки поиска), здесь только контент.
    Column(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
    when {
        state.loading -> {
            if (metrics.columns == 1) {
                SkeletonListLoading(contentPadding = PaddingValues(bottom = FloatingBottomContentPadding))
            } else {
                SkeletonGridLoading(columns = metrics.columns, contentPadding = PaddingValues(bottom = FloatingBottomContentPadding))
            }
        }
        state.error != null -> ErrorCard(message = state.error ?: "", onRetry = onRetry) // Локальный elvis: error объявлен в другом модуле (shared), smart cast невозможен
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Кнопки Календарь/Лента живут только на главной Обзора (OverviewFeed),
                    // в сетках разделов их нет.
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
                            onOpenFilm = onOpenFilm,
                            onLongPress = {
                                onOpenFilmEditor(film.toProgressEditorSeed(libraryById[film.kinopoiskId]?.toEditorProfile()))
                            }
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
                state = gridState,
                columns = GridCells.Fixed(metrics.columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Кнопки Календарь/Лента живут только на главной Обзора (OverviewFeed),
                // в сетках разделов их нет.
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
                        onOpenFilm = onOpenFilm,
                        onLongPress = {
                            onOpenFilmEditor(film.toProgressEditorSeed(libraryById[film.kinopoiskId]?.toEditorProfile()))
                        }
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
}

}

/**
 * Публичная обёртка DiscoverContent для ПК-оболочки (desktopApp): та же лента
 * «Обзора» и сетки разделов, что в телефоном HomeScreen, но без Scaffold и
 * нижней пилюли — шапку/навигацию рисует вызывающий.
 */
@Composable
fun DiscoverPanel(
    state: HomeUiState,
    sourceItems: List<FilmItem>,
    feedListState: LazyListState,
    forceGrid: Boolean = false,
    onRetry: () -> Unit = {},
    onOpenFilm: (FilmItem) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    onOpenTopic: (Int) -> Unit = {},
    onRetryOverview: () -> Unit = {},
    onSeeAll: (OverviewSeeAll) -> Unit = {},
    onScrollActivity: (Float) -> Unit = {},
) {
    val metrics = state.discoverTileSize.toGridMetrics()
    val statusByFilmId = remember(state.library) {
        state.library.mapNotNull { item -> item.status?.let { item.kinopoiskId to it } }.toMap()
    }
    val progressByFilmId = remember(state.library) {
        state.library.mapNotNull { item -> item.toWatchProgressUi()?.let { item.kinopoiskId to it } }.toMap()
    }
    val discoverItems = remember(sourceItems, state.hideRussianContent) {
        if (state.hideRussianContent) sourceItems.filterNot { it.isRussianContent() } else sourceItems
    }
    DiscoverContent(
        state = state,
        sourceItems = discoverItems,
        metrics = metrics,
        statusByFilmId = statusByFilmId,
        progressByFilmId = progressByFilmId,
        onRetry = onRetry,
        onOpenFilm = onOpenFilm,
        onOpenFilmEditor = onOpenFilmEditor,
        onLoadMore = onLoadMore,
        onOpenCalendar = onOpenCalendar,
        onOpenFeed = onOpenFeed,
        onOpenTopic = onOpenTopic,
        overviewSections = if (state.contentType == ContentType.ANIME) {
            state.overviewAnimeSections
        } else {
            state.overviewFilmSections
        },
        overviewLoading = state.overviewLoading,
        overviewError = state.overviewError,
        onRetryOverview = onRetryOverview,
        onSeeAll = onSeeAll,
        feedListState = feedListState,
        onScrollActivity = onScrollActivity,
        forceGrid = forceGrid,
    )
}

/**
 * Библиотека как самостоятельная секция для ПК-оболочки: вкладки + пейджер +
 * сетки — тот же UI, что в разделе «Библиотека» телефона, со своей фильтрацией
 * по вкладкам. Состояния пейджера/скролла живут внутри (ПК-оболочка снимает
 * экран с композиции при уходе в детали, хостить их выше незачем).
 */
@Composable
fun LibraryPanel(
    state: HomeUiState,
    onOpenHistoryFilm: (Int) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit = {},
    onRemoveFromHistory: (Int) -> Unit = {},
    onScrollActivity: (Float) -> Unit = {},
) {
    val metrics = state.libraryTileSize.toGridMetrics()
    val normalizedQuery = state.query.trim()
    var libraryTab by remember { mutableStateOf(LibraryTab.WATCHING) }
    // Удержание на заголовке раздела: диалог статистики вкладки (как в телефоне).
    var libraryStatsTab by remember { mutableStateOf<LibraryTab?>(null) }
    val itemsByTab = remember(state.library, state.hideRussianContent, normalizedQuery) {
        LibraryTab.entries.associateWith { tab ->
            state.library
                .filterByTab(tab)
                .filterByRussian(state.hideRussianContent)
                .filterByQuery(normalizedQuery)
        }
    }
    val pagerState = rememberPagerState(
        initialPage = libraryTab.ordinal,
        pageCount = { LibraryTab.entries.size }
    )
    val listStates = remember {
        LibraryTab.entries.associateWith { LazyListState() }
    }
    val gridStates = remember {
        LibraryTab.entries.associateWith { LazyGridState() }
    }
    LaunchedEffect(pagerState.settledPage) {
        libraryTab = LibraryTab.entries[pagerState.settledPage]
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LibraryTabs(
            pagerState = pagerState,
            onSelect = { target -> libraryTab = target },
            onTabLongClick = { tab -> libraryStatsTab = tab }
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalPager(
            state = pagerState,
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val pageTab = LibraryTab.entries[page]
            LibraryPageGrid(
                items = itemsByTab[pageTab].orEmpty(),
                tab = pageTab,
                queryActive = normalizedQuery.isNotEmpty(),
                historyMode = pageTab == LibraryTab.HISTORY,
                onOpenHistoryFilm = onOpenHistoryFilm,
                onOpenFilmEditor = onOpenFilmEditor,
                onRemoveFromHistory = onRemoveFromHistory,
                metrics = metrics,
                listState = listStates.getValue(pageTab),
                gridState = gridStates.getValue(pageTab),
                onScrollActivity = onScrollActivity,
            )
        }
    }
    // Удержание на заголовке раздела библиотеки: статистика по типам.
    libraryStatsTab?.let { statsTab ->
        LibraryTabStatsDialog(
            tab = statsTab,
            items = itemsByTab[statsTab].orEmpty(),
            onDismiss = { libraryStatsTab = null }
        )
    }
}

/** ПК-оболочка: тот же лист фильтров, что открывает телефоный HomeScreen. */
@Composable
fun SearchFilterSheetHost(
    state: HomeUiState,
    onApply: (SearchFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.showFilterSheet) {
        SearchFilterBottomSheet(
            filterState = state.filterState,
            availableGenres = state.availableGenres,
            availableCountries = state.availableCountries,
            contentType = state.contentType,
            onApply = onApply,
            onDismiss = onDismiss
        )
    }
}

/** Заголовок открытого раздела Обзора: кнопка назад + название + счётчик. */
@Composable
private fun DiscoverSectionHeader(
    title: String,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Назад к обзору"
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Лента «Обзора»: вертикальный скролл секций-каруселей с постерами.
 * Кино и аниме идут отдельными вкладками ([state.contentType]), но структура одна:
 * жанры-чипы, подборки, а для аниме — календарь онгоингов и новости Shikimori.
 */
@Composable
private fun OverviewFeed(
    state: HomeUiState,
    sections: List<OverviewSection>,
    loading: Boolean,
    error: String?,
    metrics: GridMetrics,
    statusByFilmId: Map<Int, UserFilmStatus>,
    progressByFilmId: Map<Int, WatchProgressUi>,
    libraryById: Map<Int, LibraryUiItem>,
    onRetryOverview: () -> Unit,
    onSeeAll: (OverviewSeeAll) -> Unit,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenTopic: (Int) -> Unit = {},
    // Скролл ленты сверху (из HomeScreen, по вкладкам Кино/Аниме): уход
    // в раздел, детали и смена вкладок позицию не сносят.
    listState: LazyListState,
    onScrollActivity: (Float) -> Unit = {},
    // Витрина сверху: широкие карточки без дублей каруселей (пусто — не показываем).
    heroItems: List<FilmItem> = emptyList(),
    heroTitle: String = ""
) {
    ScrollIntensityEffect(
        positionIndex = listState.firstVisibleItemIndex,
        positionOffset = listState.firstVisibleItemScrollOffset,
        onIntensity = onScrollActivity
    )
    val isAnime = state.contentType == ContentType.ANIME
    when {
        // Скелетона нет сознательно: первый кадр — сразу из дискового кэша, фоном тихий
        // рефреш. Пустой экран показываем только в самом первом запуске без кэша — тихий
        // пустой бокс вместо мигающего шиммера.
        loading && sections.isEmpty() && error == null -> {
            Spacer(modifier = Modifier.fillMaxSize())
        }
        sections.isEmpty() -> {
            ErrorCard(message = error ?: "Не удалось загрузить подборки.", onRetry = onRetryOverview)
        }
        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = FloatingBottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (isAnime && state.topics.isNotEmpty()) {
                    item(key = "news_hero") {
                        OverviewNewsHeroRow(
                            topics = state.topics.take(5),
                            onOpenFeed = onOpenFeed,
                            onOpenTopic = onOpenTopic
                        )
                    }
                } else if (heroItems.isNotEmpty() && heroTitle.isNotBlank()) {
                    item(key = "hero") {
                        OverviewHeroRow(
                            title = heroTitle,
                            items = heroItems,
                            onOpenFilm = onOpenFilm
                        )
                    }
                }
                if (isAnime && state.calendarItems.isNotEmpty()) {
                    item(key = "calendar_row") {
                        OverviewCalendarRow(
                            items = state.calendarItems,
                            onOpenFilm = onOpenFilm,
                            onOpenCalendar = onOpenCalendar
                        )
                    }
                }
                item(key = "genre_chips") {
                    OverviewGenreChips(
                        state = state,
                        onSeeAll = onSeeAll
                    )
                }
                sections.forEach { section ->
                    item(key = "section_${section.id}") {
                        OverviewSectionRow(
                            section = section,
                            compactText = metrics.columns >= 3,
                            statusByFilmId = statusByFilmId,
                            progressByFilmId = progressByFilmId,
                            libraryById = libraryById,
                            onSeeAll = onSeeAll,
                            onOpenFilm = onOpenFilm,
                            onOpenFilmEditor = onOpenFilmEditor
                        )
                    }
                }
            }
        }
    }
}

/** Чипы жанров: кино — из справочника KP, аниме — из статичного списка Shikimori. */
@Composable
private fun OverviewGenreChips(
    state: HomeUiState,
    onSeeAll: (OverviewSeeAll) -> Unit
) {
    val isAnime = state.contentType == ContentType.ANIME
    val filmGenres = remember(state.availableGenres) { state.availableGenres.take(12) }
    if (!isAnime && filmGenres.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        if (isAnime) {
            items(shikimoriGenres, key = { "anime_genre_${it.id}" }) { genre ->
                OverviewGenreChip(
                    label = genre.genre ?: "",
                    onClick = { onSeeAll(OverviewSeeAll.AnimeGenreTarget(genre.id, genre.genre ?: "")) }
                )
            }
        } else {
            items(filmGenres, key = { "film_genre_${it.id}" }) { genre ->
                OverviewGenreChip(
                    label = genre.genre ?: "",
                    onClick = { onSeeAll(OverviewSeeAll.FilmGenreTarget(genre.id, genre.genre ?: "")) }
                )
            }
        }
    }
}

/**
 * Кнопка жанра без рамки: тональная пилюля темнее фона, поэтому не сливается,
 * но и не обведена контуром как FilterChip.
 */
@Composable
private fun OverviewGenreChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

/**
 * Витрина сверху ленты: широкие горизонтальные карточки с картинкой (как на сайтах).
 * Контент витрины НЕ дублирует карусели: кино — самое обсуждаемое за вычетом
 * id каруселей, аниме — анонсы, которых нет ни в одной карусели.
 */
@Composable
private fun OverviewHeroRow(
    title: String,
    items: List<FilmItem>,
    onOpenFilm: (FilmItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(items.take(5), key = { "hero_${it.kinopoiskId}" }) { film ->
                val metaText = remember(film.year, film.ratingKinopoisk) {
                    listOfNotNull(
                        film.year?.toString(),
                        film.ratingKinopoisk?.let { "★ %.1f".format(java.util.Locale.US, it) }
                    ).joinToString(" • ")
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .width(300.dp)
                        .height(172.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenFilm(film) }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        KinoshkaAsyncImage(
                            model = film.posterUrlPreview,
                            contentDescription = film.nameRu,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.55f to Color.Black.copy(alpha = 0.45f),
                                        1f to Color.Black.copy(alpha = 0.88f)
                                    )
                                )
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = film.nameRu ?: film.nameOriginal ?: "Без названия",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                minLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (metaText.isNotBlank()) {
                                Text(
                                    text = metaText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Витрина «Новости» в стиле hero: широкие карточки с постером связанного
 * тайтла. Тап по плитке — сам пост, «Все» — на страницу новостей.
 */
@Composable
private fun OverviewNewsHeroRow(
    topics: List<hd.kinoshka.app.data.model.ShikimoriTopic>,
    onOpenFeed: () -> Unit,
    onOpenTopic: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = "Новости",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SectionArrowButton(onClick = onOpenFeed)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(topics, key = { "newshero_${it.id}" }) { topic ->
                val linked = topic.linked
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .width(300.dp)
                        .height(172.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenTopic(topic.id) }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (linked != null) {
                            KinoshkaAsyncImage(
                                model = linked.posterUrl,
                                contentDescription = linked.displayTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.55f to Color.Black.copy(alpha = 0.45f),
                                        1f to Color.Black.copy(alpha = 0.88f)
                                    )
                                )
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = topic.topicTitle?.takeIf { it.isNotBlank() } ?: "Новость",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Карусель календаря онгоингов: те же карточки, что на странице календаря
 * (постер + бейджи эпизода и времени). Заголовок и «Все» — на страницу календаря.
 */
@Composable
private fun OverviewCalendarRow(
    items: List<hd.kinoshka.app.data.model.ShikimoriCalendarItem>,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenCalendar: () -> Unit
) {
    // API отдаёт и уже вышедшие эпизоды — у них остатка нет и бейдж падал
    // на точное время. В карусели только будущее, ближайшее — первым.
    // Пустое будущее (сбой часов) — отсортированное как было, секция не прячется.
    val upcoming = remember(items) {
        val now = System.currentTimeMillis()
        val withTime = items.mapNotNull { item ->
            calendarEpisodeTimeMs(item.nextEpisodeAt)?.let { item to it }
        }.sortedBy { it.second }
        val future = withTime.filter { it.second > now }.map { it.first }
        (if (future.isNotEmpty()) future else withTime.map { it.first }.ifEmpty { items }).take(12)
    }
    if (upcoming.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = "Календарь",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SectionArrowButton(onClick = onOpenCalendar)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(upcoming, key = { "cal_${it.anime?.id}_${it.nextEpisode}" }) { item ->
                val anime = item.anime ?: return@items
                HorizontalCalendarCard(
                    item = item,
                    onClick = { onOpenFilm(anime.toFilmItem()) },
                    showRemainingTime = true
                )
            }
        }
    }
}

/** Стрелка «открыть раздел» вместо кнопки «Все» в шапках секций Обзора. */
@Composable
private fun SectionArrowButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Открыть раздел",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Одна карусель: заголовок + стрелка + горизонтальный ряд плакатиков 2:3. */
@Composable
private fun OverviewSectionRow(
    section: OverviewSection,
    compactText: Boolean,
    statusByFilmId: Map<Int, UserFilmStatus>,
    progressByFilmId: Map<Int, WatchProgressUi>,
    libraryById: Map<Int, LibraryUiItem>,
    onSeeAll: (OverviewSeeAll) -> Unit,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (section.seeAll != null) {
                SectionArrowButton(onClick = { onSeeAll(section.seeAll) })
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(
                items = section.items,
                key = { "${section.id}_${it.kinopoiskId}" },
                contentType = { "overview_poster" }
            ) { film ->
                Box(modifier = Modifier.width(132.dp)) {
                    DiscoverGridCard(
                        film = film,
                        compactText = compactText,
                        status = statusByFilmId[film.kinopoiskId],
                        watchProgress = progressByFilmId[film.kinopoiskId],
                        onOpenFilm = onOpenFilm,
                        fixedTitleLines = true,
                        onLongPress = {
                            onOpenFilmEditor(film.toProgressEditorSeed(libraryById[film.kinopoiskId]?.toEditorProfile()))
                        }
                    )
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
    onOpenAbout: () -> Unit,
    onOpenDownloads: () -> Unit = {}
) {
    val allItems = listOf(
        MoreMenuItem(
            title = "Загрузки",
            subtitle = "Скачанные серии и активные загрузки",
            icon = Icons.Rounded.Download,
            onClick = onOpenDownloads
        ),
        MoreMenuItem(
            title = "Профиль",
            subtitle = "Иконка профиля и график активности",
            icon = Icons.Filled.Person,
            onClick = onOpenProfile
        ),
        MoreMenuItem(
            title = "Настройки",
            subtitle = "Тема, фильтры и импорт/экспорт библиотеки",
            icon = Icons.Filled.Settings,
            onClick = onOpenSettings
        ),
        MoreMenuItem(
            title = "О приложении",
            subtitle = "Версия, обновления и полезные ссылки",
            icon = Icons.Filled.Info,
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
                    icon = item.icon,
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
    icon: ImageVector,
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
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
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
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverGridCard(
    film: FilmItem,
    compactText: Boolean,
    status: UserFilmStatus?,
    watchProgress: WatchProgressUi?,
    onOpenFilm: (FilmItem) -> Unit,
    onLongPress: () -> Unit = {},
    // Карусели Обзора: резервируем 2 строки под название всегда, иначе карточки
    // с 1-строчным названием ниже ростом и весь ряд ниже «прыгает».
    fixedTitleLines: Boolean = false
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
            .combinedClickable(
                onClick = { onOpenFilm(film) },
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
                model = film.posterUrlPreview,
                contentDescription = film.nameRu,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
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
                minLines = if (fixedTitleLines) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Без weight длинная мета («ТВ • 10/24 эп.») вытесняла чип
                        // рейтинга за край плитки.
                        modifier = Modifier.weight(1f, fill = false)
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
    // Мета — в стиле плиток Обзора: текст строки + рейтинг отдельным чипом со звездой
    val metaText = remember(item.type, item.totalEpisodes, item.subtitle, isAnime) {
        item.libraryMetaParts().joinToString(" • ")
    }
    val ratingValue = remember(item.ratingText) { item.libraryRating() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
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
            // New-episode badge (TopStart): shows when an ongoing anime has more episodes aired
            // than the user has watched. Only for active statuses, not completed/dropped.
            if (item.hasNewEpisode()) {
                NewEpisodeBadge(
                    newCount = (item.episodesAired ?: 0) - (item.watchedEpisodes ?: 0),
                    posterCorner = 14.dp,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
            if (item.status == UserFilmStatus.WATCHING) {
                watchProgress?.let { progress ->
                    PosterBottomProgressBar(
                        progress = progress.progress,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    )
                }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        style = if (compactText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                RatingChip(rating = ratingValue, isAnime = isAnime)
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
    val isAnime = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET || item.type == "ANIME"
    // Та же мета, что у сеточной плитки и Обзора: текст · текст + чип рейтинга
    val metaText = remember(item.type, item.totalEpisodes, item.subtitle, isAnime) {
        item.libraryMetaParts().joinToString(" · ")
    }
    val ratingValue = remember(item.ratingText) { item.libraryRating() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
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
            if (item.hasNewEpisode()) {
                NewEpisodeBadge(
                    newCount = (item.episodesAired ?: 0) - (item.watchedEpisodes ?: 0),
                    posterCorner = 16.dp,
                    modifier = Modifier.align(Alignment.TopStart)
                )
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
                RatingChip(rating = ratingValue, isAnime = isAnime)
            }

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
            } else {
                // Локальная копия: note объявлен в другом модуле (shared), smart cast невозможен.
                val note = item.note
                if (!note.isNullOrBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverVerticalRow(
    film: FilmItem,
    status: UserFilmStatus?,
    watchProgress: WatchProgressUi?,
    onOpenFilm: (FilmItem) -> Unit,
    onLongPress: () -> Unit = {}
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
            .combinedClickable(
                onClick = { onOpenFilm(film) },
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
                model = film.posterUrlPreview,
                contentDescription = film.nameRu,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize()
            )
            status?.let {
                UserStatusBadge(status = it, posterCorner = 16.dp, modifier = Modifier.align(Alignment.BottomEnd))
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
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

internal data class WatchProgressUi(
    val progress: Float,
    val progressLabel: String
)

/** Мета-строка плитки библиотеки в стиле Обзора: аниме — «ТВ • N эп.», фильмы — год. */
internal fun LibraryUiItem.libraryMetaParts(): List<String> {
    // Локальная копия: totalEpisodes объявлен в другом модуле (shared), smart cast невозможен.
    val episodes = totalEpisodes
    val isAnime = kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET || type == "ANIME"
    return if (isAnime) {
        val typeStr = if (episodes != null && episodes > 1) "ТВ" else "Фильм"
        listOfNotNull(typeStr, episodes?.takeIf { it > 1 }?.let { "$it эп." })
    } else {
        listOfNotNull(subtitle?.takeIf { it.isNotBlank() })
    }
}

/** «KP 8.1» / «★ 8.1» / «8.1» → 8.1 — для RatingChip, как у плиток Обзора. */
internal fun LibraryUiItem.libraryRating(): Double? =
    ratingText?.replace("KP", "")?.replace("★", "")?.trim()?.toDoubleOrNull()

/** Карточка витрины «Продолжить просмотр» из записи библиотеки: только витринные поля. */
internal fun LibraryUiItem.toHeroFilmItem(): FilmItem = FilmItem(
    kinopoiskId = kinopoiskId,
    nameRu = title,
    nameOriginal = null,
    posterUrlPreview = posterUrl,
    ratingKinopoisk = libraryRating(),
    year = subtitle?.toIntOrNull()
)

internal fun LibraryUiItem.toWatchProgressUi(): WatchProgressUi? {
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
    posterCorner: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    val (icon, description) = status.toBadgeIconAndDescription()
    Surface(
        modifier = modifier.size(36.dp),
        // Углы повторяют скругление постера: иначе клип постера подрезал иконку по диагонали
        shape = RoundedCornerShape(
            topStart = posterCorner,
            topEnd = 0.dp,
            bottomStart = 0.dp,
            bottomEnd = posterCorner
        ),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * True when this library item (anime) has new episodes the user hasn't watched yet.
 * Only for «Смотрю»/«Пересматриваю» — planned/on-hold titles would badge everything
 * the user hasn't started, and completed/dropped ones don't need a nudge.
 */
internal fun LibraryUiItem.hasNewEpisode(): Boolean {
    if (status != UserFilmStatus.WATCHING && status != UserFilmStatus.REWATCHING) return false
    val aired = episodesAired ?: return false
    val watched = watchedEpisodes ?: 0
    return aired > watched && aired < 10000
}

@Composable
private fun NewEpisodeBadge(
    newCount: Int,
    posterCorner: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        // Плашка прижата к верхнему углу постера и повторяет его скругление:
        // раньше висела с отступом, и клип постера подрезал текст на скруглении
        modifier = modifier,
        shape = RoundedCornerShape(topStart = posterCorner, bottomEnd = 10.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 2.dp
    ) {
        Text(
            text = if (newCount in 1..99) "+$newCount эп." else "Новая серия",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

// Бейдж статуса в библиотеке: только материальные иконки. painterResource(android.R.drawable.*)
// запрещён — на ряде прошивок системный drawable оказывается XML-формата, который Compose
// загрузить не может, и плитка библиотеки падает с IllegalArgumentException прямо в сетке.
// Иконки намеренно совпадают с пикером статуса на DetailsScreen.
private fun UserFilmStatus.toBadgeIconAndDescription(): Pair<ImageVector, String> {
    return when (this) {
        UserFilmStatus.WATCHING -> Icons.Rounded.Visibility to "Смотрю"
        UserFilmStatus.PLANNED -> Icons.Rounded.Star to "В планах"
        UserFilmStatus.COMPLETED -> Icons.Filled.Check to "Просмотрено"
        UserFilmStatus.REWATCHING -> Icons.Filled.Refresh to "Пересматриваю"
        UserFilmStatus.ON_HOLD -> Icons.Filled.KeyboardArrowDown to "Отложено"
        UserFilmStatus.DROPPED -> Icons.Filled.Close to "Брошено"
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
            KinoLoadingIndicator(color = MaterialTheme.colorScheme.primary)
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

/** Icon + copy shown by [LibraryEmptyState] for a library tab with nothing in it. */
private data class LibraryEmptyVisual(
    val icon: ImageVector,
    val title: String,
    val message: String
)

private fun libraryEmptyVisual(tab: LibraryTab, queryActive: Boolean): LibraryEmptyVisual =
    if (queryActive) {
        LibraryEmptyVisual(
            Icons.Filled.Search, "Ничего не найдено", "Попробуй другой запрос или фильтр."
        )
    } else when (tab) {
        LibraryTab.HISTORY -> LibraryEmptyVisual(
            Icons.Filled.History, "История пуста", "Посмотри что-нибудь — оно появится здесь."
        )
        LibraryTab.WATCHING -> LibraryEmptyVisual(
            Icons.Filled.PlayCircle, "В «Смотрю» пусто", "Начни смотреть — тайтл добавится сюда автоматически."
        )
        LibraryTab.PLANNED -> LibraryEmptyVisual(
            Icons.Filled.Bookmark, "В «В планах» пусто", "Добавляй тайтлы в план, чтобы ничего не забыть."
        )
        LibraryTab.WATCHED -> LibraryEmptyVisual(
            Icons.Filled.CheckCircle, "Нет просмотренного", "Завершённые тайтлы будут собираться здесь."
        )
        LibraryTab.REWATCHING -> LibraryEmptyVisual(
            Icons.Filled.Autorenew, "Нет пересмотров", "Решишь пересмотреть — тайтл окажется здесь."
        )
        LibraryTab.ON_HOLD -> LibraryEmptyVisual(
            Icons.Filled.PauseCircle, "Нет отложенных", "Отложенные тайтлы будут ждать тебя здесь."
        )
        LibraryTab.DROPPED -> LibraryEmptyVisual(
            Icons.Filled.RemoveCircle, "Нет брошенных", "Брошенное на полпути соберётся здесь."
        )
    }

/**
 * Centered empty state of a library tab: the tab's icon in a soft circle with a slow "breath"
 * and a fading radar-ping ring behind it, plus a per-tab title and hint. Replaces the old
 * top-left «Пусто» card, which read as a loading artifact rather than an empty section.
 */
@Composable
private fun LibraryEmptyState(tab: LibraryTab, queryActive: Boolean) {
    val visual = libraryEmptyVisual(tab, queryActive)
    val transition = rememberInfiniteTransition(label = "libraryEmpty")
    val pingScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pingScale"
    )
    val pingAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pingAlpha"
    )
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = FloatingBottomContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(pingScale)
                    .alpha(pingAlpha)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .scale(breathe)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = visual.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = visual.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

internal fun List<LibraryUiItem>.filterByQuery(query: String): List<LibraryUiItem> {
    if (query.isBlank()) return this
    return filter {
        it.title.contains(query, ignoreCase = true) ||
            (it.note?.contains(query, ignoreCase = true) == true)
    }
}

internal fun List<LibraryUiItem>.filterByRussian(hideRussian: Boolean): List<LibraryUiItem> {
    if (!hideRussian) return this
    return filterNot { it.isRussian }
}

internal fun List<LibraryUiItem>.filterByTab(tab: LibraryTab): List<LibraryUiItem> {
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

/** Тип тайтла для группировки/статистики: аниме — kind Shikimori (ТВ/Фильм/OVA/ONA/Спешл),
 *  кино — тип Кинопоиска (Фильм/Сериал/…). */
internal fun LibraryUiItem.libraryTypeLabel(): String {
    val isAnime = kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET || type == "ANIME"
    return if (isAnime) {
        when (animeKind?.lowercase()) {
            "tv" -> "ТВ"
            "movie" -> "Фильм"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special", "tv_special" -> "Спешл"
            "music" -> "Музыка"
            // Кэша Shikimori нет — тот же эвристик, что в libraryMetaParts.
            null -> if ((totalEpisodes ?: 0) > 1) "ТВ" else "Фильм"
            else -> "Аниме"
        }
    } else {
        when (type) {
            "FILM", "MULTI_PART_FILM" -> "Фильм"
            "TV_SERIES", "MINI_SERIES", "TV_SHOW" -> "Сериал"
            "MUSIC_VIDEO" -> "Клип"
            "VIDEO" -> "Видео"
            else -> "Другое"
        }
    }
}

/** Статус релиза для группировки: анонс/онгоинг/завершён; у кино и без данных — «Другое». */
internal fun LibraryUiItem.libraryReleaseStatusLabel(): String = when (releaseStatus?.lowercase()) {
    "anons" -> "Анонс"
    "ongoing" -> "Онгоинг"
    "released" -> "Завершён"
    else -> "Другое"
}

/** Год для группировки: у аниме — из Shikimori, у кино — из подзаголовка. */
internal fun LibraryUiItem.libraryGroupYear(): Int? =
    releaseYear ?: subtitle?.toIntOrNull()?.takeIf { it in 1900..2100 }

/** Диапазон оценки для группировки: по рейтингу Shikimori/КП, без оценки — отдельная группа. */
internal fun LibraryUiItem.libraryScoreGroupLabel(): String {
    val rating = libraryRating() ?: return "Без оценки"
    return when {
        rating >= 9.0 -> "9–10"
        rating >= 8.0 -> "8–8.9"
        rating >= 7.0 -> "7–7.9"
        rating >= 6.0 -> "6–6.9"
        else -> "Ниже 6"
    }
}

/** Секция группировки библиотеки: подпись-заголовок + тайтлы в сохранённом порядке сортировки. */
internal data class LibraryGroupSection(
    val key: String,
    val label: String,
    val items: List<LibraryUiItem>
) {
    val showHeader: Boolean get() = label.isNotEmpty()
}

/**
 * Разбивает отсортированный список вкладки на группы по выбранному признаку. Внутри групп
 * порядок не трогается (его задаёт сортировка), группы упорядочены естественно для
 * признака: тип/статус — по фиксированному списку, год/оценка — по убыванию.
 */
internal fun List<LibraryUiItem>.groupForLibrary(group: LibraryGroupType): List<LibraryGroupSection> {
    if (group == LibraryGroupType.NONE || isEmpty()) {
        return listOf(LibraryGroupSection(key = "", label = "", items = this))
    }
    return when (group) {
        LibraryGroupType.TYPE -> groupByFixedOrder(listOf("ТВ", "Фильм", "OVA", "ONA", "Спешл", "Музыка", "Сериал")) { it.libraryTypeLabel() }
        LibraryGroupType.RELEASE_STATUS -> groupByFixedOrder(listOf("Анонс", "Онгоинг", "Завершён", "Другое")) { it.libraryReleaseStatusLabel() }
        LibraryGroupType.SCORE -> groupByFixedOrder(listOf("9–10", "8–8.9", "7–7.9", "6–6.9", "Ниже 6", "Без оценки")) { it.libraryScoreGroupLabel() }
        LibraryGroupType.YEAR -> groupBy { it.libraryGroupYear() }
            .entries
            // Год по убыванию, «Без года» — последней группой.
            .sortedWith(
                compareBy<Map.Entry<Int?, List<LibraryUiItem>>> { it.key == null }
                    .thenByDescending { it.key ?: Int.MIN_VALUE }
            )
            .map { entry ->
                LibraryGroupSection(
                    key = entry.key?.toString() ?: "no_year",
                    label = entry.key?.toString() ?: "Без года",
                    items = entry.value
                )
            }
        LibraryGroupType.NONE -> listOf(LibraryGroupSection(key = "", label = "", items = this))
    }
}

/** Группы по [selector] в порядке фиксированного списка [order]; неперечисленные — в конце по алфавиту. */
private fun List<LibraryUiItem>.groupByFixedOrder(
    order: List<String>,
    selector: (LibraryUiItem) -> String
): List<LibraryGroupSection> {
    return groupBy(selector)
        .entries
        .sortedWith(
            compareBy(
                { entry -> order.indexOf(entry.key).let { if (it < 0) order.size else it } },
                { it.key }
            )
        )
        .map { LibraryGroupSection(key = it.key, label = it.key, items = it.value) }
}

internal fun FilmItem.isRussianContent(): Boolean {
    return countries.any { country ->
        when (country.country?.trim()?.lowercase(Locale.forLanguageTag("ru"))) {
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
    val icon: ImageVector,
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
        // «Аниме» — не жанр для Kinopoisk-поиска: такие результаты вырезаются в FilmsRepository,
        // чип лишь обещал бы пустую выдачу.
        availableGenres
            .filter { !it.genre.isNullOrBlank() && !it.genre.equals(ANIME_GENRE_NAME, ignoreCase = true) }
            .sortedBy { it.genre.orEmpty().lowercase(Locale.forLanguageTag("ru")) }
    }
    val sortedCountries = remember(availableCountries) {
        availableCountries.sortedBy { it.country.orEmpty().lowercase(Locale.forLanguageTag("ru")) }
    }
    val filteredCountries = remember(sortedCountries, countrySearchQuery) {
        if (countrySearchQuery.isBlank()) sortedCountries
        else sortedCountries.filter { it.country.orEmpty().contains(countrySearchQuery.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // skipPartiallyExpanded: только Hidden и Expanded (полураскрытия нет).
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetSquashStretch()
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
                            // animeOrder всегда задан (дефолт "popularity") — просто поднимаем активный вверх
                            val active = tempState.animeOrder
                            listOf(rawOrders.first { it.first == active }) + rawOrders.filter { it.first != active }
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
                            listOf(rawTypes.first { it.first == tempState.selectedType }) + rawTypes.filter { it.first != tempState.selectedType }
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
                            // selectedOrder всегда задан (дефолт "RATING") — просто поднимаем активный вверх
                            listOf(rawOrders.first { it.first == tempState.selectedOrder }) + rawOrders.filter { it.first != tempState.selectedOrder }
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


