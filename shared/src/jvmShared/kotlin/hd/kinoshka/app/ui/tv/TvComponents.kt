package hd.kinoshka.app.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * TV-тokens — Material 3 версия Lampa-тёмной эстетики.
 * Раньше были хардкоды (#0A0A0E, #8AB4F8), теперь читаем из MaterialTheme.colorScheme
 * (поддерживает AMOLED/Dynamic/Dark). Lampa-референс (lampa.mx):
 *  - bkg #1d1f20 ~ surface #10141D, карта 1em radius (16dp), постер 2:3 (150%),
 *  - фокус: внешняя рамка 0.3em + scale 1.05 (lampa .card__view::after), кнопка focus #fff
 *  - pill-кнопки rgba(0,0,0,0.3) -> Material surfaceContainerHigh -> focus primaryContainer
 *
 * Объект оставлен для обратной совместимости, но внутри Composable используем MaterialTheme.
 */
object TvTheme {
    val Background = Color(0xFF0A0A0E)
    val Surface = Color(0xFF16161D)
    val SurfaceHigh = Color(0xFF22222C)
    val Accent = Color(0xFF8AB4F8)
    val TextPrimary = Color(0xFFF2F2F5)
    val TextSecondary = Color(0xFF9F9FA8)
    val Scrim = Color(0xDD0A0A0E)
}

// Lampa: .card__img radius 1em, .card width 12.75em (~204px), view padding-bottom 150%
private val TvCardShape = RoundedCornerShape(16.dp)
private val TvChipShape = RoundedCornerShape(12.dp)
private val TvPillShape = RoundedCornerShape(24.dp)

/**
 * Режим навигации вокруг фокуса: рамка выделения рисуется только когда интерфейс
 * управляют клавиатурой/пультом (стрелки, D-pad). По умолчанию true — ТВ-пульт
 * не оставляет указателя, и рамка на ТВ ведёт себя как раньше.
 */
val LocalKeyboardNavigation = staticCompositionLocalOf { true }

/** Клавиши, переводящие интерфейс в режим клавиатурной навигации. */
private val KeyboardNavigationKeys = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.DirectionCenter, Key.PageUp, Key.PageDown, Key.Tab, Key.MoveHome, Key.MoveEnd,
)

/**
 * Трекер режима навигации на корне окна: навигационные клавиши включают
 * клавиатурный режим, любое событие мыши/тача возвращает мышиный. Состояние
 * должен предоставлять вызывающий ([remember { mutableStateOf(true) }]) —
 * оно же уходит в CompositionLocalProvider(LocalKeyboardNavigation).
 */
fun Modifier.inputModeTracker(keyboardMode: MutableState<Boolean>): Modifier = composed {
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key in KeyboardNavigationKeys) {
            keyboardMode.value = true
        }
        false
    }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val isPointer = event.type == PointerEventType.Move ||
                    event.type == PointerEventType.Press ||
                    event.type == PointerEventType.Release ||
                    event.type == PointerEventType.Scroll
                if (isPointer && keyboardMode.value) keyboardMode.value = false
            }
        }
    }
}

/**
 * Фокус-поведение для D-pad/стрелок/мыши — единое для ТВ, десктопа и планшета.
 *
 * Lampa-основа (.card__view::after border 0.3em #fff): скруглённая рамка только
 * в форме [shape] — никаких квадратных ripple-подсветок (indication = null везде).
 *
 * Анимация — мультяшный Squash and Stretch на пружине с недодемпфированием:
 * фокус/ховер — упругий stretch (scale + лёгкий overshoot), нажатие — squash
 * (плющит по вертикали, раздаёт вширь). Enter/Space на сфокусированном элементе
 * срабатывает через clickable, стрелки — через focusGroup корня экрана.
 *
 * @param focusBorder цвет скруглённой рамки в фокусе; null — без рамки (только scale).
 * @param hoverToFocus наведение мыши сразу переводит фокус (единая модель выделения
 * для мыши, стрелок и D-pad — от неё же едет блюр-фон).
 * @param bringIntoViewOnFocus подкрутить скролл родителей (LazyRow/LazyColumn),
 * чтобы сфокусированная карточка была видна.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvFocusable(
    onClick: () -> Unit,
    shape: Shape = TvCardShape,
    focusedScale: Float = 1.05f,
    enabled: Boolean = true,
    focusBorder: Color? = Color.White,
    hoverToFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    bringIntoViewOnFocus: Boolean = false,
    onFocusedChange: (Boolean) -> Unit = {},
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val requester = focusRequester ?: remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    // Живая пружина вместо линейного tween: stretch с отскоком, squash при нажатии.
    val squashSpring = remember { spring<Float>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium) }
    val active = focused || hovered
    val targetScaleX = when {
        pressed -> focusedScale + 0.03f
        active -> focusedScale
        else -> 1f
    }
    val targetScaleY = when {
        pressed -> 0.93f
        active -> focusedScale
        else -> 1f
    }
    val scaleX by animateFloatAsState(targetValue = targetScaleX, animationSpec = squashSpring, label = "tvSquashX")
    val scaleY by animateFloatAsState(targetValue = targetScaleY, animationSpec = squashSpring, label = "tvSquashY")
    // Рамка — признак клавиатурной/D-pad навигации: ховер мыши даёт фокус (блюр-фон,
    // squash), но не рисует рамку, пока пользователь не взял стрелки или пульт.
    val keyboardNavigation = LocalKeyboardNavigation.current
    val borderColor by animateColorAsState(
        targetValue = if (focused && keyboardNavigation) focusBorder ?: Color.Transparent else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "tvFocusBorder",
    )
    LaunchedEffect(hovered) {
        if (hovered && hoverToFocus && enabled) {
            try {
                requester.requestFocus()
            } catch (_: IllegalStateException) {
                // Элемент ещё не в фокус-иерархии (только скомпоновался) — пропускаем.
            }
        }
    }
    this
        .graphicsLayer {
            this.scaleX = scaleX
            this.scaleY = scaleY
        }
        .focusRequester(requester)
        .onFocusChanged { state ->
            onFocusedChange(state.isFocused)
            if (state.isFocused && bringIntoViewOnFocus) {
                scope.launch { bringIntoView.bringIntoView() }
            }
        }
        .hoverable(interaction, enabled = enabled)
        .then(if (bringIntoViewOnFocus) Modifier.bringIntoViewRequester(bringIntoView) else Modifier)
        .then(
            if (focused && keyboardNavigation && focusBorder != null) Modifier.border(3.dp, borderColor, shape)
            else Modifier
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/** Заголовок горизонтального ряда — как .items-line__head в Lampa (1.5em pad). */
@Composable
fun TvSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
        modifier = modifier.padding(horizontal = 36.dp),
    )
}

/**
 * Фоновый бэкдроп — Lampa .full-start__background: absolute, left 8em, width 113%,
 * mask-image gradient, opacity 0.5, dim 0.2. У нас — размытая обложка + вертикальный
 * градиент scrim (как в HeroHeader телефонной версии).
 */
@Composable
fun TvAnimatedBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    Crossfade(
        targetState = imageUrl,
        animationSpec = tween(durationMillis = 400),
        modifier = modifier,
        label = "tvBackdrop",
    ) { url ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (url != null) {
                KinoshkaAsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(36.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to bg.copy(alpha = 0.55f),
                            0.45f to bg.copy(alpha = 0.78f),
                            1f to bg.copy(alpha = 0.97f),
                        )
                    )
            )
        }
    }
}

/**
 * Постерная карточка — Lampa .card: width 12.75em, view 150% (2:3), radius 1em,
 * title 1.3em clamp 3, vote pill, new-episode зеленой пилюлей, в фокусе — внешняя
 * рамка. В Material 3: surfaceContainerHigh, primary для акцентов.
 */
@Composable
fun TvPosterCard(
    posterUrl: String?,
    title: String,
    metaText: String?,
    rating: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    status: UserFilmStatus? = null,
    newEpisodes: Int? = null,
    cardWidth: Dp = 156.dp,
    onFocused: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.width(cardWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
                .tvFocusable(onClick = onClick, hoverToFocus = true, bringIntoViewOnFocus = true)
                .clip(TvCardShape)
                .background(cs.surfaceContainerHigh)
        ) {
            KinoshkaAsyncImage(
                model = posterUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
            )
            // Lampa .card__new-episode — зелёная пилюля #57F570
            if (newEpisodes != null) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, bottomEnd = 12.dp),
                    color = Color(0xFF57F570),
                ) {
                    Text(
                        text = if (newEpisodes in 1..99) "+$newEpisodes эп." else "Новая серия",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF17491C),
                    )
                }
            }
            // Lampa .card__vote — тёмная пилюля с рейтингом справа снизу
            rating?.let {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xCC000000),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        text = "%.1f".format(Locale.US, it),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            status?.let {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = cs.primaryContainer.copy(alpha = 0.92f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = it.tvBadgeIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = cs.onPrimaryContainer,
                        )
                    }
                }
            }
            progress?.let { p ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x66FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(p.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(cs.primary)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = cs.onBackground,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildString {
            metaText?.takeIf { it.isNotBlank() }?.let { append(it) }
            // Lampa показывает возраст/год под тайтлом — у нас year уже в metaText
        }
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Горизонтальный ряд постеров — Lampa .items-line (padding 1.5em, gap). */
@Composable
fun <T> TvRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TvSectionTitle(title)
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 36.dp),
        ) {
            val keyFn = key
            if (keyFn == null) {
                itemsIndexed(items) { _, item -> itemContent(item) }
            } else {
                itemsIndexed(items, key = { _, item -> keyFn(item) }) { _, item -> itemContent(item) }
            }
        }
    }
}

/**
 * Баннер-герой — Lampa .full-start-new: слева poster 17em (272px), справа title 4em,
 * details, rate-line, description. У нас — compact-версия: постер 160dp + колонка.
 * Использует Material 3 typography, но пропорции как в Lampa.
 */
@Composable
fun TvHeroBanner(
    posterUrl: String?,
    title: String,
    metaText: String?,
    rating: Double?,
    watchLabel: String,
    onWatch: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(340.dp)
    ) {
        KinoshkaAsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(24.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to bg.copy(alpha = 0.45f),
                        0.55f to bg.copy(alpha = 0.72f),
                        1f to bg,
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 36.dp, end = 36.dp, bottom = 20.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(156.dp)
                    .aspectRatio(2f / 3f)
                    .tvFocusable(onClick = onOpen)
                    .clip(TvCardShape)
                    .background(cs.surfaceContainerHigh)
            ) {
                KinoshkaAsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = cs.onBackground,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    metaText?.takeIf { it.isNotBlank() }?.let { append(it) }
                    rating?.let {
                        if (isNotEmpty()) append("  •  ")
                        append("★ %.1f".format(Locale.US, it))
                    }
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(text = "▶  $watchLabel", primary = true, onClick = onWatch)
                    TvButton(text = "Подробнее", onClick = onOpen)
                }
            }
        }
    }
}

/** Кнопка — Lampa .full-start__button: pill 1em radius, 2.8em height, focus #fff. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    // Lampa: inactive rgba(0,0,0,0.3), focus #fff black text; Material: surfaceContainerHigh / primaryContainer
    val container = if (primary) cs.primary else cs.surfaceContainerHigh
    val content = if (primary) cs.onPrimary else cs.onSurface
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = shape, focusedScale = 1.04f, enabled = enabled)
            .clip(shape)
            .background(container)
            .border(
                width = if (primary) 0.dp else 1.dp,
                color = if (primary) Color.Transparent else cs.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            )
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        )
    }
}

/** Чип — Lampa .full-descr__tag: 0.6em radius, focus #fff. Material: FilterChip tokens. */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val background by animateColorAsState(
        targetValue = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        label = "tvChipBg",
    )
    val contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = shape, focusedScale = 1.04f)
            .clip(shape)
            .background(background)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp),
            maxLines = 1,
        )
    }
}

/** Карточка меню «Ещё» — ElevatedCard Material 3. */
@Composable
fun TvMenuCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .tvFocusable(onClick = onClick)
            .clip(TvCardShape)
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(
                text = title,
                color = cs.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Топ-бар — Lampa header: разделы слева, поиск центр, иконки справа.
 * Material: surface + searchBar (surfaceContainerHigh pill 24dp), focus ring.
 */
@Composable
fun TvTopBar(
    sections: List<String>,
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    searchPlaceholder: String,
    onAvatarClick: () -> Unit,
    showAvatar: Boolean = true,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surface.copy(alpha = 0.96f))
            .padding(horizontal = 36.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.forEachIndexed { index, label ->
            TvChip(
                text = label,
                selected = index == selectedSection,
                onClick = { onSectionSelected(index) },
            )
        }
        Spacer(Modifier.weight(0.6f))
        Row(
            modifier = Modifier
                .weight(1.2f)
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surfaceContainerHigh)
                .tvFocusable(
                    onClick = {},
                    shape = RoundedCornerShape(24.dp),
                    focusedScale = 1f,
                )
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp),
                cursorBrush = Brush.verticalGradient(listOf(cs.primary, cs.primary)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = searchPlaceholder,
                            color = cs.onSurfaceVariant,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
        }
        actions()
        if (showAvatar) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .tvFocusable(onClick = onAvatarClick, shape = RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .background(cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = "Профиль",
                    tint = cs.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/**
 * Каркас вторичных TV-экранов — центрированный контейнер на background.
 */
@Composable
fun TvScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxSize().background(cs.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .tvFocusable(onClick = onBack, shape = RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Назад", color = cs.onSurface, fontSize = 14.sp)
                }
            }
            Text(
                text = title,
                color = cs.onBackground,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .fillMaxHeight(),
            ) {
                content()
            }
        }
    }
}

/**
 * Центрирует телефонный экран вторичной страницы на TV-фоне.
 */
@Composable
fun TvSecondaryContainer(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 1040.dp)
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}

/** Иконка статуса библиотеки для TV-карточек. */
fun UserFilmStatus.tvBadgeIcon(): ImageVector = when (this) {    UserFilmStatus.WATCHING -> Icons.Rounded.Visibility
    UserFilmStatus.PLANNED -> Icons.Rounded.Star
    UserFilmStatus.COMPLETED -> Icons.Filled.Check
    UserFilmStatus.REWATCHING -> Icons.Filled.Refresh
    UserFilmStatus.ON_HOLD -> Icons.Filled.KeyboardArrowDown
    UserFilmStatus.DROPPED -> Icons.Filled.Close
}

/** Плейсхолдер пустого раздела. */
@Composable
fun TvEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
    }
}
