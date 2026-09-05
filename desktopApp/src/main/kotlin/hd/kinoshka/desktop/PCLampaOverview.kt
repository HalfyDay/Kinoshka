package hd.kinoshka.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.ui.screens.ContentType
import hd.kinoshka.app.ui.screens.DiscoverCategory
import hd.kinoshka.app.ui.screens.DiscoverPanel
import hd.kinoshka.app.ui.screens.HomeUiState
import hd.kinoshka.app.ui.screens.LibraryPanel
import hd.kinoshka.app.ui.screens.OverviewSeeAll
import hd.kinoshka.app.ui.screens.ProgressEditorSeed
import hd.kinoshka.app.ui.screens.SearchFilterSheetHost
import hd.kinoshka.app.ui.screens.SearchFilterState
import hd.kinoshka.app.ui.tv.TvAnimatedBackdrop
import hd.kinoshka.app.ui.tv.tvFocusable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Пункты бокового меню ПК. Секции контента (isAction = false) переключают
 * содержимое главной страницы, action-пункты открывают отдельные экраны/листы.
 */
enum class LampaNavItem(val label: String, val icon: ImageVector, val isAction: Boolean = false) {
    HOME("Главная", Icons.Filled.Home),
    LIBRARY("Библиотека", Icons.AutoMirrored.Filled.List),
    FILMS("Фильмы", Icons.Filled.Movie),
    SERIES("Сериалы", Icons.Filled.Tv),
    CARTOONS("Мультфильмы", Icons.Filled.FavoriteBorder),
    ANIME("Аниме", Icons.Filled.Language),
    NEWS("Новости", Icons.Filled.NotificationsNone, isAction = true),
    FILTER("Фильтр", Icons.Filled.FilterList, isAction = true),
    CALENDAR("Календарь", Icons.Filled.CalendarMonth, isAction = true),
    DOWNLOADS("Загрузки", Icons.Filled.Download, isAction = true),
    ABOUT("О приложении", Icons.Filled.Info, isAction = true),
}

/**
 * Круглая иконка топ-бара: скруглённая (CircleShape) подсветка + squash,
 * никаких квадратных ripple. Ховер мыши сразу даёт фокус.
 */
@Composable
private fun PcTopIcon(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
    iconSize: Dp = 22.dp,
    tint: Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(38.dp)
            .tvFocusable(
                onClick = onClick,
                shape = CircleShape,
                focusedScale = 1.15f,
                hoverToFocus = true,
            )
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = description,
            tint = tint ?: cs.onBackground,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun PcLampaTopBar(
    title: String,
    isFullscreen: Boolean = false,
    compactInfo: Boolean = false,
    onMenuClick: () -> Unit,
    onBack: () -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onFullscreenClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val now = remember { LocalDateTime.now() }
    val timeText = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    val ru = Locale.forLanguageTag("ru")
    val dateText = now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", ru))
        .replaceFirstChar { it.titlecase(ru) }
    val weekText = now.format(DateTimeFormatter.ofPattern("EEEE", ru))
        .replaceFirstChar { it.titlecase(ru) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.background.copy(alpha = 0.88f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PcTopIcon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack, iconSize = 22.dp)
        PcTopIcon(Icons.Filled.Menu, "Меню", onMenuClick, iconSize = 26.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            color = cs.onBackground,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.Normal),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            PcTopIcon(Icons.Filled.Search, "Поиск", onSearchClick)
            PcTopIcon(Icons.Filled.NotificationsNone, "Новости", onNotificationsClick)
            PcTopIcon(Icons.Filled.Settings, "Настройки", onSettingsClick)
            PcTopIcon(
                Icons.Filled.AccountCircle, "Профиль", onProfileClick,
                iconSize = 28.dp, tint = cs.onSurfaceVariant,
            )
            PcTopIcon(
                if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                "Во весь экран", onFullscreenClick, iconSize = 20.dp,
            )
            if (!compactInfo) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(5.dp).background(Color(0xFF4CAF50), CircleShape))
                    Box(Modifier.size(5.dp).background(cs.onSurfaceVariant.copy(alpha = 0.5f), CircleShape))
                }
            }
            Text(timeText, color = cs.onBackground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (!compactInfo) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(dateText, color = cs.onBackground, fontSize = 11.sp, lineHeight = 12.sp, maxLines = 1)
                    Text(weekText, color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PcLampaSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.background.copy(alpha = 0.88f))
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onBackground, fontSize = 15.sp),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Поиск фильмов и сериалов…", color = cs.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
                inner()
            },
            modifier = Modifier.weight(1f),
        )
        PcTopIcon(Icons.Filled.Close, "Закрыть поиск", onClose, iconSize = 18.dp)
    }
}

@Composable
fun PcLampaDrawer(
    selectedLabel: String,
    onSelect: (LampaNavItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(224.dp)
            .fillMaxSize()
            .background(cs.background)
            .padding(top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        LampaNavItem.entries.forEach { item ->
            val isSel = item.label == selectedLabel
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(pill)
                    .background(if (isSel) cs.onBackground else Color.Transparent)
                    .tvFocusable(
                        onClick = { onSelect(item) },
                        shape = pill,
                        focusedScale = 1.02f,
                        // У выбранного пункта контрастный фон — рамка не нужна, только squash.
                        focusBorder = if (isSel) null else Color.White,
                        hoverToFocus = true,
                    )
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = if (isSel) cs.background else cs.onBackground.copy(alpha = 0.88f),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = item.label,
                    color = if (isSel) cs.background else cs.onBackground.copy(alpha = 0.88f),
                    fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Главная страница ПК: шапка Lampa (часы, поиск, профиль), боковое меню и
 * контент из общего с Android кода — лента «Обзора» (DiscoverPanel) и
 * библиотека (LibraryPanel). Пункты меню Фильмы/Сериалы/Мультфильмы/Аниме
 * открывают те же серверные каталоги, что в мобильном приложении.
 */
@Composable
fun PcLampaOverview(
    state: HomeUiState,
    onOpenFilm: (FilmItem) -> Unit,
    onOpenHistoryFilm: (Int) -> Unit,
    onMenuToggle: () -> Unit = {},
    drawerOpen: Boolean = false,
    isFullscreen: Boolean = false,
    onBack: () -> Unit = {},
    onFullscreenToggle: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onSubmitSearch: () -> Unit = {},
    onContentTypeSelected: (ContentType) -> Unit = {},
    onDiscoverCategorySelected: (DiscoverCategory) -> Unit = {},
    onDiscoverReset: () -> Unit = {},
    onSearchGenre: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onOpenFilmEditor: (ProgressEditorSeed) -> Unit = {},
    onRemoveFromHistory: (Int) -> Unit = {},
    onUpdateFilters: (SearchFilterState) -> Unit = {},
    onToggleFilterSheet: (Boolean) -> Unit = {},
    onOpenTopic: (Int) -> Unit = {},
    onRetryOverview: () -> Unit = {},
    onSeeAll: (OverviewSeeAll) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var navSelected by remember { mutableStateOf(LampaNavItem.HOME) }
    var searchOpen by remember { mutableStateOf(false) }
    val query = state.query
    // Скролл ленты «Обзора»: живёт выше контента; уход в детали снимает экран
    // с композиции целиком, так что состояние не рассинхронизируется.
    val feedListState = rememberLazyListState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(cs.background)) {
        val dockDrawer = maxWidth >= 1100.dp
        val compactInfo = maxWidth < 850.dp

        fun handleSelect(item: LampaNavItem) {
            navSelected = item
            when (item) {
                LampaNavItem.HOME, LampaNavItem.FILMS, LampaNavItem.ANIME -> {
                    onDiscoverReset()
                    onContentTypeSelected(if (item == LampaNavItem.ANIME) ContentType.ANIME else ContentType.FILMS)
                }
                LampaNavItem.SERIES -> {
                    onDiscoverReset()
                    onContentTypeSelected(ContentType.FILMS)
                    onDiscoverCategorySelected(DiscoverCategory.SERIES)
                }
                LampaNavItem.CARTOONS -> {
                    onDiscoverReset()
                    onContentTypeSelected(ContentType.FILMS)
                    onSearchGenre("мультфильм", false, "Мультфильмы")
                }
                LampaNavItem.NEWS -> onOpenFeed()
                LampaNavItem.FILTER -> onToggleFilterSheet(true)
                LampaNavItem.CALENDAR -> onOpenCalendar()
                LampaNavItem.DOWNLOADS -> onOpenDownloads()
                LampaNavItem.ABOUT -> onOpenAbout()
                LampaNavItem.LIBRARY -> Unit
            }
            if (!dockDrawer) onMenuToggle()
        }

        // Блюр-фон: постер витрины/первого тайтла текущей секции.
        val backdropUrl = remember(state.overviewFilmHero, state.overviewAnimeHero, state.items, navSelected) {
            when (navSelected) {
                LampaNavItem.ANIME -> state.overviewAnimeHero.firstOrNull()?.posterUrlPreview
                    ?: state.items.firstOrNull()?.posterUrlPreview
                else -> state.overviewFilmHero.firstOrNull()?.posterUrlPreview
                    ?: state.items.firstOrNull()?.posterUrlPreview
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            TvAnimatedBackdrop(imageUrl = backdropUrl, modifier = Modifier.fillMaxSize())
            // Единая focusGroup: стрелки ходят по шапке/меню как D-pad; контент —
            // общий с Android, управляется мышью/скроллом.
            Column(modifier = Modifier.fillMaxSize().focusGroup()) {
                PcLampaTopBar(
                    title = navSelected.label,
                    isFullscreen = isFullscreen,
                    compactInfo = compactInfo,
                    onMenuClick = onMenuToggle,
                    onBack = onBack,
                    onSearchClick = { searchOpen = !searchOpen },
                    onNotificationsClick = { handleSelect(LampaNavItem.NEWS) },
                    onSettingsClick = onOpenSettings,
                    onProfileClick = onOpenProfile,
                    onFullscreenClick = onFullscreenToggle,
                )
                if (searchOpen) {
                    PcLampaSearchRow(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSubmit = onSubmitSearch,
                        onClose = {
                            searchOpen = false
                            if (query.isNotBlank()) onQueryChange("")
                            if (state.isSearchResult) onDiscoverReset()
                        },
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (dockDrawer && drawerOpen) {
                            PcLampaDrawer(selectedLabel = navSelected.label, onSelect = ::handleSelect)
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            when (navSelected) {
                                LampaNavItem.HOME, LampaNavItem.ANIME -> DiscoverPanel(
                                    state = state,
                                    sourceItems = state.items,
                                    feedListState = feedListState,
                                    onRetry = onRetry,
                                    onOpenFilm = onOpenFilm,
                                    onLoadMore = onLoadMore,
                                    onOpenCalendar = onOpenCalendar,
                                    onOpenFeed = onOpenFeed,
                                    onOpenTopic = onOpenTopic,
                                    onRetryOverview = onRetryOverview,
                                    onSeeAll = onSeeAll,
                                )
                                LampaNavItem.FILMS, LampaNavItem.SERIES, LampaNavItem.CARTOONS -> DiscoverPanel(
                                    state = state,
                                    sourceItems = state.items,
                                    feedListState = feedListState,
                                    forceGrid = true,
                                    onRetry = onRetry,
                                    onOpenFilm = onOpenFilm,
                                    onLoadMore = onLoadMore,
                                    onRetryOverview = onRetryOverview,
                                    onSeeAll = onSeeAll,
                                )
                                LampaNavItem.LIBRARY -> LibraryPanel(
                                    state = state,
                                    onOpenHistoryFilm = onOpenHistoryFilm,
                                    onOpenFilmEditor = onOpenFilmEditor,
                                    onRemoveFromHistory = onRemoveFromHistory,
                                )
                                // Action-пункты (Новости/Календарь/Загрузки/О приложении)
                                // открывают отдельные маршруты Main — здесь не рисуются.
                                LampaNavItem.NEWS, LampaNavItem.CALENDAR, LampaNavItem.DOWNLOADS,
                                LampaNavItem.ABOUT, LampaNavItem.FILTER -> Unit
                            }
                        }
                    }
                    if (!dockDrawer && drawerOpen) {
                        val scrimInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable(
                                    interactionSource = scrimInteraction,
                                    indication = null,
                                    onClick = onMenuToggle,
                                ),
                        )
                        Box(modifier = Modifier.align(Alignment.TopStart)) {
                            PcLampaDrawer(selectedLabel = navSelected.label, onSelect = ::handleSelect)
                        }
                    }
                }
            }
        }

        SearchFilterSheetHost(
            state = state,
            onApply = onUpdateFilters,
            onDismiss = { onToggleFilterSheet(false) },
        )
    }
}
