package hd.kinoshka.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.ui.platform.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Один пункт плавающей нижней навигации (общей для главного экрана и фида рекомендаций).
 *  Глиф — слот, а не res-ID: Android рисует кастомные drawable-иконки (painterResource),
 *  desktop — material-иконки, общий код не зависит от ресурсов приложения. */
class NavPillItem(
    val contentDescription: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val glyph: @Composable (selected: Boolean) -> Unit
)

private val NavButtonSize = 60.dp
private val NavRowHPadding = 12.dp
private val NavRowVPadding = 6.dp
private val NavPillHeight = NavButtonSize + NavRowVPadding * 2
private val NavGlyphSelectedSize = 50.dp
private val NavGlyphUnselectedSize = 44.dp

/**
 * Плавающая круглая «пилюля» нижней навигации — ЕДИНСТВЕННЫЙ источник её визуала.
 * Раньше главный экран и фид рисовали свои версии и формы расходились; теперь оба используют
 * этот компонент (кнопки 60dp + ripple, скользящий желейный blob secondaryContainer у выбранного).
 *
 * Живость — только фон (squash & stretch): поверхность и тень деформируются
 * от физики (скролл, переключение), контент поверх вообще
 * не масштабируется и стоит неподвижно. Круг выделения иконки физика не касается:
 * только скольжение позиции и импульс переключения. Сила деформации при скролле зависит
 * от скорости ([scrollIntensity]): слабый скролл — едва заметно, флинг — в полную силу.
 * Плюс «дыхание» фона в простое (тень + blob).
 * Двухступенчатая тактильность: лёгкий тик на нажатие + подтверждение на выбор.
 * При системном «уменьшить анимацию» ([rememberReduceMotion]) — только быстрые fade
 * без bounce ([reduceMotion] форсит).
 */
@Composable
fun BottomNavPill(
    items: List<NavPillItem>,
    isAmoled: Boolean,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    scrollIntensity: Float = 0f
) {
    val calm = reduceMotion || rememberReduceMotion()
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val count = items.size
    val selectedIndex = items.indexOfFirst { it.selected }.coerceAtLeast(0)

    // «Дыхание» фона в простое: один медленный цикл на тень пилюли и размер blob.
    // Создаётся всегда (кроме calm), но читают его только Surface и blob — кнопки скипаются.
    val idleBreath: Float = if (!calm) {
        val infinite = rememberInfiniteTransition(label = "nav_idle")
        val b by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "nav_idle_phase"
        )
        b
    } else {
        0f
    }

    // Squash & stretch при скролле следует за интенсивностью: мягкое следование
    // сглаживает рывки скорости, на остановке интенсивность сама гаснет в ноль.
    // Читают его только слои фона — кнопки стоят неподвижно.
    val scrollStretch by animateFloatAsState(
        targetValue = if (calm) 0f else scrollIntensity.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "nav_scroll_stretch"
    )

    // Растяжение blob в полёте: выраженный, но мягкий импульс при смене выбора.
    var stretchPulse by remember { mutableStateOf(0f) }
    val stretch by animateFloatAsState(
        targetValue = stretchPulse,
        animationSpec = if (calm) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        },
        label = "nav_blob_stretch"
    )
    LaunchedEffect(selectedIndex) {
        if (!calm && count > 0) {
            stretchPulse = 1f
            delay(140)
            stretchPulse = 0f
        } else {
            stretchPulse = 0f
        }
    }

    // Blob тянется вместе с фоном; альфа слегка гаснет на сильном скролле.

    val shadowDp = (8f + 2.5f * idleBreath - 4f * scrollStretch).coerceAtLeast(0f).dp
    // Деформация единого фона (поверхность + blob): ужатия всего контейнера нет,
    // только направленный squash & stretch + микро-импульс переключения.
    val bgScaleX = (1f - 0.03f * scrollStretch) * (1f + 0.015f * stretch)
    val bgScaleY = (1f + 0.035f * scrollStretch) * (1f - 0.018f * stretch)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp)
            .padding(top = 2.dp, bottom = 20.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Жёсткий размер пилюли: ширина widthIn, высота фиксирована ниже у слоёв.
        // Фон деформируется, круг и кнопки — статичные соседи поверх.
        Box(
            modifier = Modifier.widthIn(min = 260.dp, max = 300.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // Центры кнопок детерминированы: Row на всю ширину + SpaceEvenly,
                // поэтому позиция blob считается математически, без onGloballyPositioned.
                val rowWidth = maxWidth
                val gap = if (count > 0) {
                    ((rowWidth - NavRowHPadding * 2 - NavButtonSize * count) / (count + 1))
                        .coerceAtLeast(0.dp)
                } else {
                    0.dp
                }
                fun centerX(index: Int): androidx.compose.ui.unit.Dp =
                    NavRowHPadding + gap + NavButtonSize / 2 + (NavButtonSize + gap) * index

                // Переезд мягкий и выраженный: спокойная пружина без перелёта.
                val blobTravelSpec: AnimationSpec<androidx.compose.ui.unit.Dp> = if (calm) {
                    tween(durationMillis = 160, easing = FastOutSlowInEasing)
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                }
                val blobCenter by animateDpAsState(
                    targetValue = centerX(selectedIndex),
                    animationSpec = blobTravelSpec,
                    label = "nav_blob_center"
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    // Фон-поверхность фиксированной высоты: деформируется squash & stretch.
                    Surface(
                        shape = CircleShape,
                        color = containerColor,
                        tonalElevation = 3.dp,
                        shadowElevation = shadowDp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(NavPillHeight)
                            .graphicsLayer {
                                scaleX = bgScaleX
                                scaleY = bgScaleY
                            }
                    ) {}
                    // Скользящий круг выделения: физика фона его не касается —
                    // только позиция переезда и собственный импульс переключения.
                    if (count > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = blobCenter - NavGlyphSelectedSize / 2)
                                .size(NavGlyphSelectedSize)
                                .graphicsLayer {
                                    scaleX = 1f + 0.2f * stretch
                                    scaleY = 1f - 0.12f * stretch
                                    // В полёте круг бледнеет: пролёт под чужими кнопками
                                    // не читается как их выбор. В покое — полная яркость.
                                    alpha = 1f - 0.3f * stretch
                                }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(NavPillHeight)
                            .padding(horizontal = NavRowHPadding, vertical = NavRowVPadding),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items.forEach { item ->
                            NavPillButton(item = item, reduceMotion = calm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPillButton(item: NavPillItem, reduceMotion: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // Первая ступень тактильности: лёгкий тик в момент нажатия.
    LaunchedEffect(isPressed) {
        if (isPressed) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    // Желейный squish: при нажатии кнопка шире и ниже, пружина возвращает с bounce.
    // Темп быстрый: жёсткая пружина (~320ms на settle).
    val jellySpec: AnimationSpec<Float> = if (reduceMotion) {
        tween(durationMillis = 120)
    } else {
        spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        )
    }
    val pressScaleX by animateFloatAsState(
        targetValue = if (isPressed && !reduceMotion) 1.12f else 1f,
        animationSpec = jellySpec,
        label = "nav_press_scale_x"
    )
    val pressScaleY by animateFloatAsState(
        targetValue = if (isPressed && !reduceMotion) 0.88f else 1f,
        animationSpec = jellySpec,
        label = "nav_press_scale_y"
    )

    Box(
        modifier = Modifier
            .size(NavButtonSize)
            .graphicsLayer {
                scaleX = pressScaleX
                scaleY = pressScaleY
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    radius = 28.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                ),
                onClick = {
                    // Вторая ступень: подтверждение только при реальной смене выбора.
                    if (!item.selected) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                    item.onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        NavItemGlyph(
            icon = { item.glyph(item.selected) },
            selected = item.selected,
            reduceMotion = reduceMotion
        )
    }
}

@Composable
private fun NavItemGlyph(
    icon: @Composable () -> Unit,
    selected: Boolean,
    reduceMotion: Boolean
) {
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = if (reduceMotion) {
            tween(durationMillis = 150)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        },
        label = "nav_tint"
    )
    // Размер едет гладко без перелёта: раньше тут была bounce-пружина,
    // которая складывалась с popScale и давала двойной «кивок» — отсюда лаг.
    val glyphSize by animateDpAsState(
        targetValue = if (selected) NavGlyphSelectedSize else NavGlyphUnselectedSize,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "nav_glyph_size"
    )
    val popScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.85f,
        animationSpec = if (reduceMotion) {
            tween(durationMillis = 150, easing = FastOutSlowInEasing)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            )
        },
        label = "nav_scale"
    )

    // Покачивание при выборе: заметный наклон и мягкий возврат без рывка.
    val tilt = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected && !reduceMotion) {
            tilt.snapTo(-7f)
            tilt.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            tilt.snapTo(0f)
        }
    }

    CompositionLocalProvider(LocalContentColor provides tint) {
        Box(
            modifier = Modifier
                .size(glyphSize)
                .graphicsLayer {
                    scaleX = popScale
                    scaleY = popScale
                    rotationZ = tilt.value
                },
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

/**
 * Интенсивность вертикального скролла 0..1 по фактическому смещению позиции.
 * Жест без движения (край списка) ничего не сообщает; слабый скролл даёт слабое
 * значение, флинг — единицу. После [idleTimeoutMs] тишины гаснет в ноль.
 * Первый запуск лишь фиксирует стартовую позицию.
 *
 * @param positionIndex индекс первого видимого элемента (или страница пейджера).
 * @param positionOffset смещение в px внутри него (для пейджера — доля смещения ×10000).
 * @param fullStrengthPxPerSec скорость, считающаяся полной единицей.
 */
@Composable
fun ScrollIntensityEffect(
    positionIndex: Int,
    positionOffset: Int,
    fullStrengthPxPerSec: Float = 6500f,
    idleTimeoutMs: Long = 130,
    onIntensity: (Float) -> Unit
) {
    var lastPos by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lastMark by remember { mutableStateOf<TimeMark?>(null) }
    LaunchedEffect(positionIndex, positionOffset) {
        val mark = TimeSource.Monotonic.markNow()
        val pos = positionIndex to positionOffset
        val prevPos = lastPos
        val prevMark = lastMark
        lastPos = pos
        lastMark = mark
        if (prevPos == null || prevMark == null) return@LaunchedEffect
        val dtMs = prevMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        val dPos = abs(
            (pos.first.toLong() * 100000L + pos.second) -
                (prevPos.first.toLong() * 100000L + prevPos.second)
        )
        onIntensity((dPos.toFloat() / dtMs * 1000f / fullStrengthPxPerSec).coerceIn(0f, 1f))
        delay(idleTimeoutMs)
        onIntensity(0f)
    }
}
