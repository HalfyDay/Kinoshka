package hd.kinoshka.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.ui.screens.HomeUiState
import hd.kinoshka.app.ui.tv.TvAnimatedBackdrop
import hd.kinoshka.app.ui.tv.tvFocusable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class LampaNavItem(val label: String, val icon: ImageVector)

private val lampaNavItems = listOf(
    LampaNavItem("Главная", Icons.Filled.Home),
    LampaNavItem("Лента", Icons.Filled.Star),
    LampaNavItem("Фильмы", Icons.Filled.Movie),
    LampaNavItem("Мультфильмы", Icons.Filled.FavoriteBorder),
    LampaNavItem("Сериалы", Icons.Filled.Tv),
    LampaNavItem("Персоны", Icons.Filled.Person),
    LampaNavItem("Каталог", Icons.Filled.VideoLibrary),
    LampaNavItem("Фильтр", Icons.Filled.FilterList),
    LampaNavItem("Релизы", Icons.Filled.Hd),
    LampaNavItem("Аниме", Icons.Filled.Language),
    LampaNavItem("Избранное", Icons.Filled.BookmarkBorder),
    LampaNavItem("История", Icons.Filled.History),
    LampaNavItem("Подписки", Icons.Filled.Subscriptions),
    LampaNavItem("Расписание", Icons.Filled.CalendarMonth),
    LampaNavItem("Торренты", Icons.Filled.Download),
    LampaNavItem("Спорт", Icons.Filled.SportsSoccer),
    LampaNavItem("Shots", Icons.Filled.FlashOn),
)

/** Пункты с локальным жанровым фильтром каталога; остальные — навигация/заглушка с видимым откликом. */
private enum class LampaFilter { ALL, FILMS, CARTOONS, SERIES, ANIME }

private fun filterFor(label: String): LampaFilter? = when (label) {
    "Главная" -> LampaFilter.ALL
    "Фильмы" -> LampaFilter.FILMS
    "Мультфильмы" -> LampaFilter.CARTOONS
    "Сериалы" -> LampaFilter.SERIES
    "Аниме" -> LampaFilter.ANIME
    else -> null
}

private fun FilmItem.matches(filter: LampaFilter): Boolean {
    val genres = genres.mapNotNull { it.genre?.lowercase() }
    return when (filter) {
        LampaFilter.ALL -> true
        LampaFilter.CARTOONS -> genres.any { it.contains("мульт") || it.contains("анимац") || it.contains("cartoon") || it.contains("animation") }
        LampaFilter.ANIME -> genres.any { it.contains("аниме") || it == "anime" }
        LampaFilter.SERIES -> genres.any { it.contains("сериал") || it.contains("series") }
        LampaFilter.FILMS -> genres.none { it.contains("мульт") || it.contains("аниме") || it == "anime" || it.contains("сериал") }
    }
}

private fun FilmItem.matchesQuery(q: String): Boolean {
    if (q.isBlank()) return true
    val n = q.trim().lowercase()
    return (nameRu ?: "").lowercase().contains(n) || (nameOriginal ?: "").lowercase().contains(n)
}

/**
 * Единая модель выделения плиток: ховер мыши, стрелки клавиатуры и D-pad
 * пишут в одно состояние — от него едут скруглённая подсветка и блюр-фон.
 */
private class LampaGridNav {
    val requesters = mutableStateMapOf<Int, FocusRequester>()
    val rows = mutableStateMapOf<Int, List<FilmItem>>()
    val rowStates = mutableStateMapOf<Int, LazyListState>()
    var focusedId by mutableStateOf<Int?>(null)
    var pendingId by mutableStateOf<Int?>(null)
}

@Composable
private fun rememberClock(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(20_000)
        }
    }
    return now
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
    title: String = "Главная - TMDB",
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
    val now = rememberClock()
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
            PcTopIcon(Icons.Filled.NotificationsNone, "Уведомления", onNotificationsClick)
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
    selectedLabel: String = "Мультфильмы",
    onSelect: (String) -> Unit = {},
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
        lampaNavItems.forEach { item ->
            val isSel = item.label == selectedLabel
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(pill)
                    .background(if (isSel) Color.White else Color.Transparent)
                    .tvFocusable(
                        onClick = { onSelect(item.label) },
                        shape = pill,
                        focusedScale = 1.02f,
                        // У выбранного пункта белый фон — белая рамка не видна,
                        // оставляем только squash-scale без квадратов.
                        focusBorder = null,
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
                    tint = if (isSel) Color.Black else cs.onBackground.copy(alpha = 0.88f),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = item.label,
                    color = if (isSel) Color.Black else cs.onBackground.copy(alpha = 0.88f),
                    fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Плитка фильма: ховер мыши = фокус, стрелки/D-pad ходят по ряду,
 * выделение — скруглённая рамка + squash, фон экрана — блюр постера.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PcFocusableCard(
    film: FilmItem,
    cardWidth: Dp,
    nav: LampaGridNav,
    onOpenFilm: (FilmItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val id = film.kinopoiskId
    val cardShape = RoundedCornerShape(12.dp)
    val focusRequester = remember(id) { FocusRequester().also { nav.requesters[id] = it } }
    DisposableEffect(id) { onDispose { nav.requesters.remove(id) } }
    val bringIntoView = remember { BringIntoViewRequester() }
    val pending = nav.pendingId
    LaunchedEffect(pending) {
        if (pending == id) {
            bringIntoView.bringIntoView()
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
            }
            if (nav.pendingId == id) nav.pendingId = null
        }
    }
    Column(modifier = Modifier.width(cardWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .bringIntoViewRequester(bringIntoView)
                .tvFocusable(
                    onClick = { onOpenFilm(film) },
                    shape = cardShape,
                    focusedScale = 1.06f,
                    hoverToFocus = true,
                    focusRequester = focusRequester,
                    onFocusedChange = { focused -> if (focused) nav.focusedId = id },
                )
                .clip(cardShape)
                .background(cs.surfaceContainerHigh),
        ) {
            KinoshkaAsyncImage(
                model = film.posterUrlPreview,
                contentDescription = film.nameRu ?: film.nameOriginal,
                modifier = Modifier.fillMaxSize(),
            )
            film.ratingKinopoisk?.let {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xB3000000),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f", it),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = film.nameRu ?: film.nameOriginal ?: "",
            color = cs.onBackground,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
        film.year?.let {
            Spacer(Modifier.height(2.dp))
            Text(text = it.toString(), color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun PcLampaRow(
    title: String,
    items: List<FilmItem>,
    rowIndex: Int,
    cardWidth: Dp,
    nav: LampaGridNav,
    onPreviewKey: (rowIndex: Int, event: KeyEvent) -> Boolean,
    onOpenFilm: (FilmItem) -> Unit,
    onMore: () -> Unit = {},
    loadingMore: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val rowState = rememberLazyListState()
    val pill = RoundedCornerShape(14.dp)
    LaunchedEffect(rowState) { nav.rowStates[rowIndex] = rowState }
    DisposableEffect(rowIndex) { onDispose { nav.rowStates.remove(rowIndex) } }
    LaunchedEffect(items) { nav.rows[rowIndex] = items }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = title, color = cs.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loadingMore) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Surface(
                    shape = pill,
                    color = cs.surfaceContainerHigh.copy(alpha = 0.9f),
                    modifier = Modifier
                        .clip(pill)
                        .tvFocusable(onClick = onMore, shape = pill, focusedScale = 1.06f, hoverToFocus = true),
                ) {
                    Text(
                        "Еще",
                        color = cs.onBackground.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("Ничего не найдено", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
        } else {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
                modifier = Modifier.onPreviewKeyEvent { event -> onPreviewKey(rowIndex, event) },
            ) {
                items(items, key = { it.kinopoiskId }) { film ->
                    PcFocusableCard(film = film, cardWidth = cardWidth, nav = nav, onOpenFilm = onOpenFilm)
                }
            }
        }
    }
}

@Composable
fun PcLampaOverview(
    state: HomeUiState,
    onOpenFilm: (FilmItem) -> Unit,
    onMenuToggle: () -> Unit = {},
    drawerOpen: Boolean = false,
    isFullscreen: Boolean = false,
    onBack: () -> Unit = {},
    onFullscreenToggle: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onSubmitSearch: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var navSelected by remember { mutableStateOf("Главная") }
    var searchOpen by remember { mutableStateOf(false) }
    val query = state.query
    val nav = remember { LampaGridNav() }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(cs.background)) {
        // Широкий формат по-настоящему: карточки растут вместе с окном
        // (вплоть до ультрашироких 21:9/32:9), ряды — во всю ширину без
        // центрирующей «колонки», блюр-фон — full-bleed.
        val cardWidth = ((maxWidth - 48.dp) / 7.5f).coerceIn(132.dp, 220.dp)
        val dockDrawer = maxWidth >= 1100.dp
        val compactInfo = maxWidth < 850.dp

        val activeFilter = filterFor(navSelected) ?: LampaFilter.ALL
        val visible = state.items.filter { it.matches(activeFilter) && it.matchesQuery(query) }
        val rowCount = 2 + if (visible.size > 40) 1 else 0
        val firstRow = visible.take(20)
        val secondRow = visible.drop(20).take(20)
        val thirdRow = if (visible.size > 40) visible.drop(40).take(20) else emptyList()
        val topTitle = if (navSelected == "Главная") "Главная - TMDB" else "$navSelected - TMDB"

        // Блюр-фон — постер текущей выделенной плитки (ховер/стрелки/D-pad).
        val backdropUrl = remember(visible, nav.focusedId) {
            val id = nav.focusedId
            if (id == null) visible.firstOrNull()?.posterUrlPreview
            else visible.find { it.kinopoiskId == id }?.posterUrlPreview
        }

        // Автофокус первой плитки при загрузке: стрелки и блюр работают сразу.
        var autofocusDone by remember { mutableStateOf(false) }
        val firstId = visible.firstOrNull()?.kinopoiskId
        LaunchedEffect(firstId) {
            if (!autofocusDone && firstId != null) {
                autofocusDone = true
                nav.pendingId = firstId
            }
        }

        fun handleDrawer(label: String) {
            navSelected = label
            when (label) {
                "Лента" -> onOpenFeed()
                "Расписание" -> onOpenCalendar()
            }
            if (!dockDrawer) onMenuToggle()
        }

        val outerListState = rememberLazyListState()

        fun moveToCell(row: Int, col: Int) {
            scope.launch {
                val target = nav.rows[row] ?: return@launch
                if (target.isEmpty()) return@launch
                // Ряды — первые элементы внешнего LazyColumn: индексы совпадают.
                if (outerListState.layoutInfo.visibleItemsInfo.none { it.index == row }) {
                    outerListState.scrollToItem(row)
                }
                val clamped = col.coerceIn(0, target.lastIndex)
                nav.rowStates[row]?.let { rs ->
                    if (rs.layoutInfo.visibleItemsInfo.none { it.index == clamped }) {
                        rs.scrollToItem(clamped)
                    }
                }
                nav.pendingId = target[clamped].kinopoiskId
            }
        }

        /** Стрелки внутри рядов: лево/право со скроллом, верх/низ между рядами. */
        fun handleRowKey(rowIndex: Int, event: KeyEvent): Boolean {
            if (event.type != KeyEventType.KeyDown) return false
            val current = nav.focusedId ?: return false
            val rowItems = nav.rows[rowIndex] ?: return false
            val col = rowItems.indexOfFirst { it.kinopoiskId == current }
            if (col == -1) return false
            when (event.key) {
                Key.DirectionLeft -> {
                    // На краю — отдаём системе (уход в док-панель).
                    if (col == 0) return false
                    nav.pendingId = rowItems[col - 1].kinopoiskId
                    return true
                }
                Key.DirectionRight -> {
                    if (col == rowItems.lastIndex) return false
                    val target = col + 1
                    scope.launch {
                        nav.rowStates[rowIndex]?.let { rs ->
                            if (rs.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                                rs.scrollToItem(target)
                            }
                        }
                        nav.pendingId = rowItems[target].kinopoiskId
                    }
                    return true
                }
                Key.DirectionUp -> {
                    // Из первого ряда — наверх в поиск/топбар системной навигацией.
                    if (rowIndex == 0) return false
                    moveToCell(rowIndex - 1, col)
                    return true
                }
                Key.DirectionDown -> {
                    if (rowIndex == rowCount - 1) return false
                    moveToCell(rowIndex + 1, col)
                    return true
                }
                else -> return false
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            TvAnimatedBackdrop(imageUrl = backdropUrl, modifier = Modifier.fillMaxSize())
            // Единая focusGroup: стрелки ходят по всему экрану как D-pad —
            // топ-бар, сайдбар, ряды; Enter открывает выделенный тайтл.
            Column(modifier = Modifier.fillMaxSize().focusGroup()) {
                PcLampaTopBar(
                    title = topTitle,
                    isFullscreen = isFullscreen,
                    compactInfo = compactInfo,
                    onMenuClick = onMenuToggle,
                    onBack = onBack,
                    onSearchClick = { searchOpen = !searchOpen },
                    onNotificationsClick = onOpenFeed,
                    onSettingsClick = onOpenSettings,
                    onProfileClick = onOpenAbout,
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
                        },
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (dockDrawer && drawerOpen) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            PcLampaDrawer(selectedLabel = navSelected, onSelect = ::handleDrawer)
                            OverviewRows(
                                firstRow = firstRow,
                                secondRow = secondRow,
                                thirdRow = thirdRow,
                                rowCount = rowCount,
                                cardWidth = cardWidth,
                                nav = nav,
                                outerListState = outerListState,
                                onRowKey = ::handleRowKey,
                                loading = state.loading,
                                isEmpty = state.items.isEmpty(),
                                error = state.error,
                                loadingMore = state.loadingMore,
                                onOpenFilm = onOpenFilm,
                                onRetry = onRetry,
                                onLoadMore = onLoadMore,
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            OverviewRows(
                                firstRow = firstRow,
                                secondRow = secondRow,
                                thirdRow = thirdRow,
                                rowCount = rowCount,
                                cardWidth = cardWidth,
                                nav = nav,
                                outerListState = outerListState,
                                onRowKey = ::handleRowKey,
                                loading = state.loading,
                                isEmpty = state.items.isEmpty(),
                                error = state.error,
                                loadingMore = state.loadingMore,
                                onOpenFilm = onOpenFilm,
                                onRetry = onRetry,
                                onLoadMore = onLoadMore,
                            )
                            if (drawerOpen) {
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
                                    PcLampaDrawer(selectedLabel = navSelected, onSelect = ::handleDrawer)
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
private fun OverviewRows(
    firstRow: List<FilmItem>,
    secondRow: List<FilmItem>,
    thirdRow: List<FilmItem>,
    rowCount: Int,
    cardWidth: Dp,
    nav: LampaGridNav,
    outerListState: LazyListState,
    onRowKey: (rowIndex: Int, event: KeyEvent) -> Boolean,
    loading: Boolean,
    isEmpty: Boolean,
    error: String?,
    loadingMore: Boolean,
    onOpenFilm: (FilmItem) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val retryShape = RoundedCornerShape(12.dp)
    LazyColumn(
        state = outerListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(30.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 32.dp),
    ) {
        item(key = "row1") {
            PcLampaRow(
                title = "Сейчас смотрят", items = firstRow, rowIndex = 0,
                cardWidth = cardWidth, nav = nav, onPreviewKey = onRowKey,
                onOpenFilm = onOpenFilm, onMore = onLoadMore, loadingMore = loadingMore,
            )
        }
        item(key = "row2") {
            PcLampaRow(
                title = "Сегодня в тренде", items = secondRow, rowIndex = 1,
                cardWidth = cardWidth, nav = nav, onPreviewKey = onRowKey,
                onOpenFilm = onOpenFilm, onMore = onLoadMore, loadingMore = loadingMore,
            )
        }
        if (thirdRow.isNotEmpty()) {
            item(key = "row3") {
                PcLampaRow(
                    title = "Популярное", items = thirdRow, rowIndex = 2,
                    cardWidth = cardWidth, nav = nav, onPreviewKey = onRowKey,
                    onOpenFilm = onOpenFilm, onMore = onLoadMore, loadingMore = loadingMore,
                )
            }
        }
        if (loading && isEmpty) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp)) { Text("Загрузка…", color = cs.onSurfaceVariant) } }
        }
        val err = error
        if (err != null && isEmpty) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(err, color = cs.onSurfaceVariant)
                    Surface(
                        shape = retryShape,
                        color = cs.surfaceContainerHigh.copy(alpha = 0.9f),
                        modifier = Modifier
                            .clip(retryShape)
                            .tvFocusable(onClick = onRetry, shape = retryShape, hoverToFocus = true),
                    ) {
                        Text("Повторить", color = cs.onBackground, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}
