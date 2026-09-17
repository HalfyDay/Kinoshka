package hd.kinoshka.app.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Feed
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Управление нижней пилюлей: порядок вкладок и вибрация нажатий.
 * Имена секций — MainSection.name; отключается только Лента (см. [canNavSectionHide]),
 * Библиотека/Обзор/Профиль — несущие разделы и всегда в меню.
 */
fun navSectionTitle(name: String): String = when (name) {
    MainSection.LIBRARY.name -> "Библиотека"
    MainSection.DISCOVER.name -> "Обзор"
    MainSection.FEED.name -> "Лента"
    MainSection.PROFILE.name -> "Профиль"
    else -> name
}

fun navSectionIcon(name: String): ImageVector = when (name) {
    // Контурные, как невыбранные кнопки пилюли (превью всегда показывает покой).
    // Библиотека — книги на полке, как кастомный drawable пилюли, а не список.
    MainSection.LIBRARY.name -> LibraryShelfIcon
    MainSection.DISCOVER.name -> Icons.Outlined.Explore
    MainSection.FEED.name -> Icons.AutoMirrored.Outlined.Feed
    MainSection.PROFILE.name -> Icons.Outlined.PersonOutline
    else -> Icons.Outlined.PersonOutline
}

/** Единственная отключаемая вкладка — Лента. */
fun canNavSectionHide(name: String): Boolean = name == MainSection.FEED.name

@Composable
fun NavMenuSettingsScreen(
    onBack: () -> Unit,
    order: List<String>,
    hidden: Set<String>,
    hapticsEnabled: Boolean,
    hapticScale: Float,
    onMoveSection: (fromIndex: Int, toIndex: Int) -> Unit,
    onToggleSection: (name: String, visible: Boolean) -> Unit,
    onHapticsEnabledChanged: (Boolean) -> Unit,
    onHapticScaleChanged: (Float) -> Unit,
    // Живой тест силы (платформа вибрирует заданной силой; desktop — no-op).
    // Вызывается троттленно во время движения слайдера, финально — при отпускании,
    // коротко — при захвате иконки пилюли, по кнопке «Проверить».
    onHapticPreview: (Float) -> Unit = {},
    // Цвет пилюли как у настоящей ([BottomNavPill]: AMOLED — чёрная, иначе surfaceContainer).
    isAmoled: Boolean = false,
    // Глифы вкладок как на телефоне (KinoApp inject'ит drawable-иконки,
    // без инъекции — material-фолбэк navSectionIcon). Невыбранный вариант.
    libraryGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    discoverGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    feedGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    profileGlyph: (@Composable (selected: Boolean) -> Unit)? = null
) {
    // Тумблера вкл/выкл больше нет: сила — единственный регулятор, 0% = без вибрации.
    // Кто раньше выключал тумблером — увидит 0% и включит силой (тумблер досинкается сам).
    val effectiveScale = if (hapticsEnabled) hapticScale else 0f
    // Шапка-пилюля закреплена и парит без подложки (с тенью и градиентом),
    // как на остальных страницах настроек.
    PinnedHeaderPage(
        title = "Навигационное меню",
        subtitle = "Вкладки пилюли и вибрация",
        onBack = onBack
    ) { topPad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
        ) {
        item {
            KinoSettingsSectionHeader("Вкладки")
        }
        item {
            KinoSettingsCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TabsPillPreview(
                        order = order,
                        hidden = hidden,
                        isAmoled = isAmoled,
                        onMoveSection = onMoveSection,
                        onGrabTick = { onHapticPreview(0.45f) },
                        iconFor = { name ->
                            val injected = when (name) {
                                MainSection.LIBRARY.name -> libraryGlyph
                                MainSection.DISCOVER.name -> discoverGlyph
                                MainSection.FEED.name -> feedGlyph
                                MainSection.PROFILE.name -> profileGlyph
                                else -> null
                            }
                            if (injected != null) {
                                injected(false)
                            } else {
                                Icon(
                                    imageVector = navSectionIcon(name),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    )
                    Text(
                        text = "Удерживайте иконку и тяните, чтобы изменить порядок",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                if (MainSection.FEED.name in order) {
                    KinoSettingsDivider()
                    KinoSettingsRow(
                        title = "Лента в меню",
                        summary = "Библиотека, Обзор и Профиль всегда в меню",
                        icon = if (feedGlyph == null) navSectionIcon(MainSection.FEED.name) else null,
                        iconContent = feedGlyph?.let { glyph -> { glyph(false) } },
                        trailing = {
                            Switch(
                                checked = MainSection.FEED.name !in hidden,
                                onCheckedChange = { onToggleSection(MainSection.FEED.name, it) }
                            )
                        }
                    )
                }
            }
        }
        item {
            KinoSettingsSectionHeader("Вибрация")
        }
        item {
            KinoSettingsCard {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    // Черновик на время жеста: ползунок идёт за пальцем локально,
                    // в стор пишем один раз при отпускании — иначе каждый тик
                    // дёргает запись в префы и рекомпозицию всего экрана.
                    var draftScale by remember { mutableStateOf<Float?>(null) }
                    val shownScale = draftScale ?: effectiveScale
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Сила вибрации",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(shownScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val latestEffective by rememberUpdatedState(effectiveScale)
                    var lastPreviewMs by remember { mutableLongStateOf(0L) }
                    var lastPreviewValue by remember { mutableFloatStateOf(effectiveScale) }
                    Slider(
                        value = shownScale,
                        onValueChange = { v ->
                            val clean = v.coerceIn(0f, 1f)
                            draftScale = clean
                            // Живой тест силы прямо во время движения — троттled,
                            // иначе мотор захлебнётся десятками срабатываний в секунду.
                            val now = System.currentTimeMillis()
                            if (abs(clean - lastPreviewValue) >= 0.04f && now - lastPreviewMs >= 90L) {
                                lastPreviewMs = now
                                lastPreviewValue = clean
                                onHapticPreview(clean)
                            }
                        },
                        onValueChangeFinished = {
                            val final = draftScale ?: latestEffective
                            draftScale = null
                            onHapticScaleChanged(final)
                            val shouldEnable = final > 0.01f
                            if (shouldEnable != hapticsEnabled) onHapticsEnabledChanged(shouldEnable)
                            lastPreviewMs = System.currentTimeMillis()
                            lastPreviewValue = final
                            onHapticPreview(final)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ноль — без вибрации",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onHapticPreview(latestEffective) }) {
                            Text("Проверить")
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * Превью пилюли как на главном экране (та же круглая Surface, те же иконки,
 * кнопки 60dp): порядок слева направо = [order], скрытые приглушены.
 * Перестановка — удержанием с горизонтальным перетаскиванием: во время жеста
 * компоновка frozen (поэтому жест никогда не отменяется перезапуском детектора),
 * иконка идёт за пальцем, соседи плавно разъезжаются, в стор пишем один раз
 * при отпускании — одним удержанием доезжает до любого края без лагов.
 */
@Composable
private fun TabsPillPreview(
    order: List<String>,
    hidden: Set<String>,
    isAmoled: Boolean,
    onMoveSection: (fromIndex: Int, toIndex: Int) -> Unit,
    onGrabTick: () -> Unit,
    iconFor: @Composable (String) -> Unit
) {
    val latestOrder by rememberUpdatedState(order)
    val latestMove by rememberUpdatedState(onMoveSection)
    val latestGrab by rememberUpdatedState(onGrabTick)
    var draggingName by remember { mutableStateOf<String?>(null) }
    var grabIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    // Точный шаг центров при SpaceEvenly: кнопки 60dp, боковые отступы 12dp.
    // Шаг = кнопка + зазор = (внутр. ширина + кнопка) / (n + 1), а не ширина/n.
    val density = LocalDensity.current
    val count = order.size
    val spacingPx = if (count == 0 || rowWidthPx <= 0f) 0f else with(density) {
        (rowWidthPx - 12.dp.toPx() * 2 + 60.dp.toPx()) / (count + 1)
    }
    val latestSpacing by rememberUpdatedState(spacingPx)
    // Цель из абсолютного смещения от точки захвата (для разъезда соседей в кадре).
    val dragTarget = if (draggingName == null || grabIndex < 0 || spacingPx <= 1f) {
        -1
    } else {
        (grabIndex + (dragOffsetX / spacingPx).roundToInt()).coerceIn(0, count - 1)
    }
    // Итог одним ходом remove+add (родитель делает ровно ту же операцию);
    // цель считаем из свежих значений, без кадра отставания композиции.
    fun commitDrag() {
        val from = grabIndex
        val spacing = latestSpacing
        val base = latestOrder
        if (draggingName == null || from !in base.indices || spacing <= 1f) return
        val to = (from + (dragOffsetX / spacing).roundToInt()).coerceIn(0, base.size - 1)
        if (to != from) latestMove(from, to)
    }
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 260.dp, max = 300.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .onSizeChanged { rowWidthPx = it.width.toFloat() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                order.forEachIndexed { index, name ->
                    val isDragging = draggingName == name
                    val dimmed = name in hidden
                    // Соседи разъезжаются, освобождая целевую дырку; сама дырка
                    // едет за пальцем. Плавность — пружиной, без перелёта.
                    val sideShift = when {
                        isDragging || draggingName == null || dragTarget < 0 -> 0f
                        grabIndex < dragTarget && index in (grabIndex + 1)..dragTarget -> -spacingPx
                        grabIndex > dragTarget && index in dragTarget until grabIndex -> spacingPx
                        else -> 0f
                    }
                    val sideAnim by animateFloatAsState(
                        targetValue = sideShift,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "pill_shift"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(60.dp)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragging) dragOffsetX else sideAnim
                                val s = if (isDragging) 1.1f else 1f
                                scaleX = s
                                scaleY = s
                                alpha = if (dimmed) 0.38f else 1f
                            }
                            .pointerInput(name) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        grabIndex = latestOrder.indexOf(name)
                                        draggingName = name
                                        dragOffsetX = 0f
                                        latestGrab()
                                    },
                                    onDragCancel = {
                                        commitDrag()
                                        draggingName = null
                                        grabIndex = -1
                                        dragOffsetX = 0f
                                    },
                                    onDragEnd = {
                                        commitDrag()
                                        draggingName = null
                                        grabIndex = -1
                                        dragOffsetX = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetX += dragAmount.x
                                    }
                                )
                            }
                            .semantics { contentDescription = navSectionTitle(name) }
                    ) {
                        iconFor(name)
                    }
                }
            }
        }
    }
}
