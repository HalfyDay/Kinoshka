package hd.kinoshka.app.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * TV-эстетика всегда тёмная (10-foot UI, как Netflix/YouTube TV) — независимо от темы
 * приложения: на больших экранах в тёмном оформлении контент читается лучше всего.
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

private val TvCardShape = RoundedCornerShape(14.dp)
private val TvChipShape = RoundedCornerShape(10.dp)

/**
 * Фокус-поведение для D-pad/клавиатуры: получает фокус (стрелки), центр = onClick,
 * в фокусе карточка масштабируется и подсвечивается рамкой. indication = null —
 * ripple в TV-стиле не используется, вся реакция — масштаб + рамка.
 */
fun Modifier.tvFocusable(
    onClick: () -> Unit,
    shape: Shape = TvCardShape,
    focusedScale: Float = 1.06f,
    enabled: Boolean = true,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "tvFocusScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) TvTheme.Accent else Color.Transparent,
        animationSpec = tween(durationMillis = 140),
        label = "tvFocusBorder",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(width = 3.dp, color = borderColor, shape = shape)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/** Заголовок горизонтального ряда / секции в TV-макете. */
@Composable
fun TvSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TvTheme.TextPrimary,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 36.dp),
    )
}

/**
 * Постерная карточка (2:3) с фокус-масштабом, прогрессом просмотра, статус-бейджем
 * и плашкой новых серий — TV-аналог плиток телефонного HomeScreen.
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
    cardWidth: Dp = 150.dp,
) {
    Column(modifier = modifier.width(cardWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .tvFocusable(onClick = onClick)
                .clip(TvCardShape)
                .background(TvTheme.SurfaceHigh)
        ) {
            KinoshkaAsyncImage(
                model = posterUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
            )
            if (newEpisodes != null) {
                Surface(
                    shape = RoundedCornerShape(topStart = 14.dp, bottomEnd = 10.dp),
                    color = TvTheme.Accent,
                ) {
                    Text(
                        text = if (newEpisodes in 1..99) "+$newEpisodes эп." else "Новая серия",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0A0A0E),
                    )
                }
            }
            status?.let {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(38.dp),
                    shape = RoundedCornerShape(topStart = 12.dp),
                    color = TvTheme.Accent.copy(alpha = 0.92f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = it.tvBadgeIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFF0A0A0E),
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
                            .background(TvTheme.Accent)
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            color = TvTheme.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp,
        )
        val meta = buildString {
            metaText?.takeIf { it.isNotBlank() }?.let { append(it) }
            rating?.let {
                if (isNotEmpty()) append("  ")
                append("★ %.1f".format(Locale.US, it))
            }
        }
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                color = TvTheme.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Горизонтальный ряд постеров с заголовком — базовый строительный блок ТВ-Обзора. */
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
 * Крупный баннер-герой (Netflix-style): фон-постер с блюром и градиентной завесой,
 * поверх — постер, название, мета и фокусируемые кнопки.
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp)
    ) {
        KinoshkaAsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(28.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to TvTheme.Background.copy(alpha = 0.55f),
                        0.6f to TvTheme.Background.copy(alpha = 0.75f),
                        1f to TvTheme.Background,
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 36.dp, end = 36.dp, bottom = 22.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(2f / 3f)
                    .tvFocusable(onClick = onOpen)
                    .clip(TvCardShape)
                    .background(TvTheme.SurfaceHigh)
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
                    color = TvTheme.TextPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
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
                        color = TvTheme.TextSecondary,
                        fontSize = 15.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton(text = "▶  $watchLabel", primary = true, onClick = onWatch)
                    TvButton(text = "Подробнее", onClick = onOpen)
                }
            }
        }
    }
}

/** Кнопка TV-стиля: filled accent (primary) либо outline. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = shape, focusedScale = 1.04f, enabled = enabled)
            .clip(shape)
            .background(if (primary) TvTheme.Accent else TvTheme.Surface)
            .border(
                width = if (primary) 0.dp else 1.dp,
                color = if (primary) Color.Transparent else TvTheme.TextSecondary.copy(alpha = 0.4f),
                shape = shape,
            )
            .padding(horizontal = 26.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary) Color(0xFF0A0A0E) else TvTheme.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Чип-кнопка (вкладки библиотеки, категории, пункты топ-бара). */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val background by animateColorAsState(
        targetValue = when {
            selected -> TvTheme.Accent
            else -> TvTheme.Surface
        },
        label = "tvChipBg",
    )
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = shape, focusedScale = 1.04f)
            .clip(shape)
            .background(background)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF0A0A0E) else TvTheme.TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Крупная карточка меню раздела «Ещё». */
@Composable
fun TvMenuCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 260.dp, max = 420.dp)
            .tvFocusable(onClick = onClick)
            .clip(TvCardShape)
            .background(TvTheme.Surface)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TvTheme.Accent,
            modifier = Modifier.size(30.dp),
        )
        Column {
            Text(
                text = title,
                color = TvTheme.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = TvTheme.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * Топ-бар TV-макета: разделы слева (D-pad ←/→), строка поиска по центру, аватар справа.
 * Строка поиска — обычный focusable TextField: на ПК/планшетах ввод с клавиатуры.
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
    avatarEmoji: String?,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TvTheme.Background.copy(alpha = 0.96f))
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
                .background(TvTheme.Surface)
                .tvFocusable(
                    onClick = {},
                    shape = RoundedCornerShape(24.dp),
                    focusedScale = 1f,
                )
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = TvTheme.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TvTheme.TextPrimary, fontSize = 15.sp),
                cursorBrush = Brush.verticalGradient(listOf(TvTheme.Accent, TvTheme.Accent)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = searchPlaceholder,
                            color = TvTheme.TextSecondary,
                            fontSize = 15.sp,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
        }
        actions()
        Box(
            modifier = Modifier
                .size(44.dp)
                .tvFocusable(onClick = onAvatarClick, shape = RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(TvTheme.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = avatarEmoji ?: "🎬", fontSize = 20.sp)
        }
    }
}

/**
 * Каркас вторичных TV-экранов (Профиль/Настройки/Загрузки/…): тёмный фон, кнопка «Назад»
 * в фокусе, контент центрирован и ограничен по ширине. Телефонный вид экранов не меняет.
 */
@Composable
fun TvScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(TvTheme.Background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .tvFocusable(onClick = onBack, shape = RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvTheme.Surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = TvTheme.TextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Назад", color = TvTheme.TextPrimary, fontSize = 14.sp)
                }
            }
            Text(
                text = title,
                color = TvTheme.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
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
                    .widthIn(max = 900.dp)
                    .fillMaxHeight(),
            ) {
                content()
            }
        }
    }
}

/** Иконка статуса библиотеки для TV-карточек (та же семантика, что на телефонных плитках). */
fun UserFilmStatus.tvBadgeIcon(): ImageVector = when (this) {
    UserFilmStatus.WATCHING -> Icons.Rounded.Visibility
    UserFilmStatus.PLANNED -> Icons.Rounded.Star
    UserFilmStatus.COMPLETED -> Icons.Filled.Check
    UserFilmStatus.REWATCHING -> Icons.Filled.Refresh
    UserFilmStatus.ON_HOLD -> Icons.Filled.KeyboardArrowDown
    UserFilmStatus.DROPPED -> Icons.Filled.Close
}

/** Контент-плейсхолдер пустого TV-раздела. */
@Composable
fun TvEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = TvTheme.TextSecondary, fontSize = 16.sp)
    }
}
