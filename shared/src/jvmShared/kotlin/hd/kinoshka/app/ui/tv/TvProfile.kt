package hd.kinoshka.app.ui.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.ui.screens.LibraryUiItem
import hd.kinoshka.app.ui.screens.WatchTimeSummary
import hd.kinoshka.app.ui.screens.calcWatchStreak
import hd.kinoshka.app.ui.screens.calcWatchTime
import hd.kinoshka.app.ui.screens.formatStreak
import hd.kinoshka.app.ui.screens.formatWatchTime
import hd.kinoshka.app.ui.screens.statusSegments
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Состояние облачного бэкапа для ТВ-профиля. Платформа (app-модуль) собирает
 * из CloudSyncStore + CloudBackupManager.status: общий код их не видит.
 */
data class TvCloudBackupState(
    /** Подпись подключения («Яндекс Диск • папка Kinoshka», «WebDAV • url»). Null — не подключено. */
    val connectedLabel: String?,
    val autoSync: Boolean = false,
    val busy: Boolean = false,
    /** «Последняя синхронизация: … • результат». */
    val statusLine: String = "",
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/** Секция ТВ-профиля: заголовок с иконкой + контент, в стиле остальных блоков. */
@Composable
fun TvProfileSection(
    title: String,
    icon: ImageVector? = null,
    hPad: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            headerAction?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * Полоса статусов «Список аниме» / «Список фильмов» из мобильной версии:
 * стековая полоса долей + легенда с числами. На ТВ пункты легенды — фокусируемые
 * чипы пульта: ОК открывает Библиотеку на этом статусе («Без статуса» не кликается).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvStatusStrip(
    title: String,
    icon: ImageVector,
    items: List<LibraryUiItem>,
    hPad: androidx.compose.ui.unit.Dp,
    onStatusClick: (UserFilmStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = items.size
    TvProfileSection(title = title, icon = icon, hPad = hPad, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = if (total == 0) "пусто" else "всего $total",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (total == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Text(
                    text = "Пока ничего нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val segments = remember(items) { items.statusSegments() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                segments.forEach { segment ->
                    val fraction = segment.count.toFloat() / total
                    Box(
                        modifier = Modifier
                            .weight(fraction)
                            .fillMaxHeight()
                            .background(Color(segment.colorArgb)),
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                segments.forEach { segment ->
                    val status = segment.status
                    val chipShape = RoundedCornerShape(10.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .then(
                                if (status != null) {
                                    Modifier.tvFocusable(
                                        onClick = { onStatusClick(status) },
                                        shape = chipShape,
                                        focusedScale = 1.04f,
                                    )
                                } else Modifier
                            )
                            .clip(chipShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(segment.colorArgb)),
                        )
                        Text(
                            text = segment.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${segment.count}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** «Потрачено на просмотр ≈ …» — та же честная оценка, что в мобильной версии. */
@Composable
fun TvWatchTimeRow(
    library: List<LibraryUiItem>,
    hPad: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val summary: WatchTimeSummary = remember(library) { library.calcWatchTime() }
    if (summary.totalMinutes <= 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = hPad),
    ) {
        Text(
            text = "Потрачено на просмотр",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "≈ ${formatWatchTime(summary.totalMinutes)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * График «Активность за 14 дней» из мобильной версии: те же столбцы, halo
 * выбранного дня и бейдж серии. Пульт: влево/вправо двигают выбор,
 * фокус подсвечивает весь график рамкой.
 */
@Composable
fun TvActivitySection(
    library: List<LibraryUiItem>,
    hPad: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    /** Фокус на графике: корень уступает ему стрелки влево/вправо (иначе край открывает меню). */
    onFocusedChange: (Boolean) -> Unit = {},
) {
    val values = remember(library) { buildTvActivityBars(library) }
    if (values.isEmpty()) return
    val streak = remember(library) { library.calcWatchStreak() }
    TvProfileSection(
        title = "Активность за 14 дней",
        hPad = hPad,
        modifier = modifier,
        headerAction = {
            if (streak >= 2) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFEF8E3C).copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = formatStreak(streak),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEF8E3C),
                    )
                }
            }
        },
    ) {
        var selectedIndex by remember(values) { mutableIntStateOf(values.lastIndex) }
        val max = values.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        var chartWidthPx by remember { mutableStateOf(0) }
        var headerWidthPx by remember { mutableStateOf(0) }
        val slotWidthPx = chartWidthPx.toFloat() / values.size
        val headerCenterPx = slotWidthPx * selectedIndex + slotWidthPx / 2f
        val maxHeaderOffsetPx = (chartWidthPx - headerWidthPx).coerceAtLeast(0).toFloat()
        val headerOffsetPx = (headerCenterPx - headerWidthPx / 2f).coerceIn(0f, maxHeaderOffsetPx)
        val selectedValue = values[selectedIndex].second
        val barColor = MaterialTheme.colorScheme.primary
        val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val haloColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { chartWidthPx = it.width },
            ) {
                Column(
                    modifier = Modifier
                        .onSizeChanged { headerWidthPx = it.width }
                        .offset { IntOffset(headerOffsetPx.roundToInt(), 0) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = values[selectedIndex].first,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = when {
                            selectedValue == 0 -> "нет просмотров"
                            selectedValue == 1 -> "1 просмотр"
                            selectedValue in 2..4 -> "$selectedValue просмотра"
                            else -> "$selectedValue просмотров"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val chartShape = RoundedCornerShape(16.dp)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .onKeyEvent { event ->
                        // D-pad/стрелки двигают выбор по дням, не уходя с графика.
                        // Только нажатие: отпускание тоже приходит событием и давало бы двойной шаг.
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.DirectionRight -> {
                                selectedIndex = (selectedIndex + 1).coerceAtMost(values.lastIndex)
                                true
                            }
                            else -> false
                        }
                    }
                    .tvFocusable(onClick = {}, shape = chartShape, focusedScale = 1f, bringIntoViewOnFocus = true, onFocusedChange = onFocusedChange)
                    .pointerInput(values) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.pressed) {
                                    val idx = ((change.position.x / size.width) * values.size)
                                        .toInt().coerceIn(0, values.size - 1)
                                    selectedIndex = idx
                                }
                            }
                        }
                    },
            ) {
                val slotWidth = size.width / values.size
                val barWidth = (slotWidth * 0.6f).coerceAtMost(24.dp.toPx())
                val corner = CornerRadius(4.dp.toPx())
                values.forEachIndexed { index, (_, value) ->
                    if (index == selectedIndex) {
                        drawRoundRect(
                            color = haloColor,
                            topLeft = Offset(index * slotWidth + 1.dp.toPx(), 0f),
                            size = Size(slotWidth - 2.dp.toPx(), size.height),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                        )
                    }
                    val barHeight = when {
                        value > 0 -> (size.height * (value.toFloat() / max.toFloat())).coerceAtLeast(6.dp.toPx())
                        else -> 3.dp.toPx()
                    }
                    val left = index * slotWidth + (slotWidth - barWidth) / 2f
                    drawRoundRect(
                        color = when {
                            value == 0 && index == selectedIndex -> barColor.copy(alpha = 0.45f)
                            value == 0 -> trackColor
                            else -> barColor
                        },
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = corner,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                values.forEachIndexed { index, (label, _) ->
                    val isSelected = index == selectedIndex
                    androidx.compose.material3.Text(
                        text = label.substringBefore('.'),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Те же 14 дней по viewedAtMillis, что в мобильном профиле. */
fun buildTvActivityBars(library: List<LibraryUiItem>): List<Pair<String, Int>> {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val days = (13 downTo 0).map { offset ->
        val c = calendar.clone() as Calendar
        c.add(Calendar.DAY_OF_YEAR, -offset)
        c.timeInMillis
    }
    val counts = mutableMapOf<Long, Int>()
    library.mapNotNull { it.viewedAtMillis }.forEach { ts ->
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val key = c.timeInMillis
        counts[key] = (counts[key] ?: 0) + 1
    }
    val labelFormat = SimpleDateFormat("dd.MM", Locale.forLanguageTag("ru"))
    return days.map { day -> labelFormat.format(Date(day)) to (counts[day] ?: 0) }
}

/**
 * «Резервная копия в облаке» из мобильной версии, адаптированная под пульт:
 * те же состояния (Яндекс Диск / WebDAV / не подключено, выгрузка, восстановление,
 * автосохранение), но TV-кнопки вместо material. Диалоги и тосты хостит платформа.
 */
@Composable
fun TvCloudBackupSection(
    backupState: TvCloudBackupState,
    hPad: androidx.compose.ui.unit.Dp,
    onConnectYandex: () -> Unit,
    onConnectWebDav: () -> Unit,
    onDisconnect: () -> Unit,
    onUpload: () -> Unit,
    onRestore: () -> Unit,
    onAutoSyncChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TvProfileSection(
        title = "Резервная копия в облаке",
        icon = Icons.Filled.Cloud,
        hPad = hPad,
        modifier = modifier,
    ) {
        // Фон-карточка как у аккаунтов: surfaceContainerHigh 16dp + padding 20dp.
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(20.dp),
        ) {
            if (backupState.connectedLabel != null) {
                Text(
                    text = backupState.connectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TvButton(text = "Отключить", onClick = onDisconnect, modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvButton(
                        text = "Выгрузить",
                        primary = true,
                        onClick = onUpload,
                        enabled = !backupState.busy,
                        modifier = Modifier.weight(1f),
                    )
                    TvButton(
                        text = "Восстановить",
                        onClick = onRestore,
                        enabled = !backupState.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Автосохранение после просмотра",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = backupState.autoSync, onCheckedChange = onAutoSyncChanged)
                }
            } else {
                TvButton(
                    text = "Подключить Яндекс Диск",
                    primary = true,
                    onClick = onConnectYandex,
                    modifier = Modifier.fillMaxWidth(),
                )
                TvButton(
                    text = "Подключить WebDAV",
                    onClick = onConnectWebDav,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (backupState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (backupState.statusLine.isNotBlank()) {
                Text(
                    text = backupState.statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            backupState.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (backupState.messageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * «Резервная копия в файл»: экспорт/импорт JSON, файл кладёт платформа (Загрузки).
 * Фон-карточка как у аккаунтов, кнопки — по одной в строку с иконками мобильной версии.
 */
@Composable
fun TvFileBackupSection(
    hPad: androidx.compose.ui.unit.Dp,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvProfileSection(
        title = "Резервная копия в файл",
        icon = Icons.Filled.Description,
        hPad = hPad,
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(20.dp),
        ) {
            TvButton(
                text = "Экспорт",
                primary = true,
                onClick = onExport,
                icon = Icons.Filled.FileDownload,
                modifier = Modifier.fillMaxWidth(),
            )
            TvButton(
                text = "Импорт",
                onClick = onImport,
                icon = Icons.Filled.FileUpload,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
