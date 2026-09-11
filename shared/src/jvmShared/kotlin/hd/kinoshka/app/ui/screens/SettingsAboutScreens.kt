package hd.kinoshka.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import hd.kinoshka.app.data.local.PlayerMode
import kotlinx.coroutines.launch

/**
 * Настройки и «О приложении» — общие для Android и desktop (переехали из app-модуля в рамках
 * TV-дизайна: обе страницы нужны на больших экранах). Платформенные вещи приходят параметрами:
 * [showDebugSettings] (BuildConfig.DEBUG), [appVersion]/[appPackage] (BuildConfig) и
 * [onReportProblem] (сбор diagnostics — только на Android).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    selectedThemeMode: AppThemeMode,
    hideRussianContent: Boolean,
    selectedDiscoverTileSize: FilmTileSize,
    selectedLibraryTileSize: FilmTileSize,
    selectedShowFpsCounter: Boolean,
    selectedPlayerMode: PlayerMode,
    onPlayerModeSelected: (PlayerMode) -> Unit,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onHideRussianChanged: (Boolean) -> Unit,
    onDiscoverTileSizeSelected: (FilmTileSize) -> Unit,
    onLibraryTileSizeSelected: (FilmTileSize) -> Unit,
    onShowFpsCounterChanged: (Boolean) -> Unit,
    showDebugSettings: Boolean = false,
    // Прокси для заблокированных источников (Android-отладка): начальное значение и писатель.
    proxyUrl: String = "",
    onProxyUrlChanged: (String) -> Unit = {},
    // Настройки плеера mpvEx есть только на Android: их UI живёт в app-модуле, desktop-плеер их не читает.
    onOpenPlayerSettings: (() -> Unit)? = null,
    // «О приложении» живёт внутри настроек (раньше — отдельный пункт меню).
    onOpenAbout: (() -> Unit)? = null,
    // Управление источниками живёт на отдельной странице.
    onOpenSources: (() -> Unit)? = null,
    // Память и хранилище (только Android: замеры и очистка живут в app-модуле;
    // null — строка скрыта, как на desktop).
    onOpenStorage: (() -> Unit)? = null
) {
    var showThemePicker by remember { mutableStateOf(false) }
    var showDiscoverTileSizePicker by remember { mutableStateOf(false) }
    var showLibraryTileSizePicker by remember { mutableStateOf(false) }
    var showPlayerModePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsHeaderCard(
                title = "Настройки",
                subtitle = "Внешний вид и библиотека",
                onBack = onBack,
                // Боковые поля раньше давал контентный паддинг страницы, теперь он
                // у карточек настроек — шапке задаём отступы явно.
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        item {
            KinoSettingsSectionHeader("Внешний вид")
        }
        item {
            KinoSettingsCard {
                KinoSettingsRow(
                    title = "Тема",
                    summary = selectedThemeMode.toSettingsLabel(),
                    icon = Icons.Outlined.Palette,
                    onClick = { showThemePicker = true }
                )
                KinoSettingsDivider()
                KinoSettingsRow(
                    title = "Размер плиток (Обзор)",
                    summary = selectedDiscoverTileSize.toSettingsLabel(),
                    icon = Icons.Outlined.GridView,
                    onClick = { showDiscoverTileSizePicker = true }
                )
                KinoSettingsDivider()
                KinoSettingsRow(
                    title = "Размер плиток (Библиотека)",
                    summary = selectedLibraryTileSize.toSettingsLabel(),
                    icon = Icons.Outlined.Dashboard,
                    onClick = { showLibraryTileSizePicker = true }
                )
            }
        }
        item {
            KinoSettingsSectionHeader("Плеер")
        }
        item {
            KinoSettingsCard {
                KinoSettingsRow(
                    title = "Плеер фильмов",
                    summary = selectedPlayerMode.displayName,
                    icon = Icons.Outlined.PlayCircle,
                    onClick = { showPlayerModePicker = true }
                )
                if (onOpenPlayerSettings != null) {
                    KinoSettingsDivider()
                    KinoSettingsRow(
                        title = "Настройки плеера mpvEx",
                        summary = "Скорость, жесты, субтитры, декодер, сброс",
                        icon = Icons.Outlined.Tune,
                        onClick = onOpenPlayerSettings
                    )
                }
                if (onOpenSources != null) {
                    KinoSettingsDivider()
                    KinoSettingsRow(
                        title = "Источники видео",
                        summary = "Фильмы, аниме и 18+: вкл/выкл и проверка",
                        icon = Icons.Outlined.VideoLibrary,
                        onClick = onOpenSources
                    )
                }
            }
        }
        item {
            KinoSettingsSectionHeader("Фильтры и отладка")
        }
        item {
            KinoSettingsCard {
                KinoSettingsRow(
                    title = "Скрывать российские фильмы/сериалы",
                    summary = "Фильтр применяется к обзору и библиотеке",
                    icon = Icons.Outlined.VisibilityOff,
                    trailing = {
                        Switch(checked = hideRussianContent, onCheckedChange = onHideRussianChanged)
                    }
                )
                if (showDebugSettings) {
                    KinoSettingsDivider()
                    KinoSettingsRow(
                        title = "Показывать FPS",
                        summary = "Счётчик кадров поверх главного экрана (только debug)",
                        icon = Icons.Outlined.Speed,
                        trailing = {
                            Switch(checked = selectedShowFpsCounter, onCheckedChange = onShowFpsCounterChanged)
                        }
                    )
                    KinoSettingsDivider()
                    StreamProxySettingRow(initial = proxyUrl, onChanged = onProxyUrlChanged)
                }
            }
        }
        if (onOpenAbout != null || onOpenStorage != null) {
            item {
                KinoSettingsSectionHeader("Приложение")
            }
            item {
                KinoSettingsCard {
                    if (onOpenStorage != null) {
                        KinoSettingsRow(
                            title = "Память и хранилище",
                            summary = "Размер кэша, очистка, загрузки",
                            icon = Icons.Outlined.Storage,
                            onClick = onOpenStorage
                        )
                    }
                    if (onOpenAbout != null) {
                        if (onOpenStorage != null) KinoSettingsDivider()
                        KinoSettingsRow(
                            title = "О приложении",
                            summary = "Версия, обновления и полезные ссылки",
                            icon = Icons.Outlined.Info,
                            onClick = onOpenAbout
                        )
                    }
                }
            }
        }
    }

    if (showThemePicker) {
        SettingsSelectBottomSheet(
            title = "Тема",
            options = listOf(AppThemeMode.CURRENT, AppThemeMode.DARK, AppThemeMode.AMOLED),
            selected = selectedThemeMode,
            optionLabel = { it.toSettingsLabel() },
            onSelect = onThemeModeSelected,
            onDismiss = { showThemePicker = false }
        )
    }

    if (showDiscoverTileSizePicker) {
        SettingsSelectBottomSheet(
            title = "Размер плиток (Обзор)",
            options = FilmTileSize.entries.toList(),
            selected = selectedDiscoverTileSize,
            optionLabel = { it.toSettingsLabel() },
            onSelect = onDiscoverTileSizeSelected,
            onDismiss = { showDiscoverTileSizePicker = false }
        )
    }

    if (showLibraryTileSizePicker) {
        SettingsSelectBottomSheet(
            title = "Размер плиток (Библиотека)",
            options = FilmTileSize.entries.toList(),
            selected = selectedLibraryTileSize,
            optionLabel = { it.toSettingsLabel() },
            onSelect = onLibraryTileSizeSelected,
            onDismiss = { showLibraryTileSizePicker = false }
        )
    }

    if (showPlayerModePicker) {
        SettingsSelectBottomSheet(
            title = "Плеер фильмов",
            options = PlayerMode.entries.toList(),
            selected = selectedPlayerMode,
            optionLabel = { it.displayName },
            onSelect = onPlayerModeSelected,
            onDismiss = { showPlayerModePicker = false }
        )
    }
}

/**
 * Строка использования хранилища для секции «Память и хранилище».
 * [key] — ключ категории (см. StorageUsageManager в app-модуле),
 * [destructive] — удаление необратимо (загрузки, история) → спрашиваем подтверждение.
 */
data class StorageUsageRow(
    val key: String,
    val title: String,
    val bytes: Long,
    val destructive: Boolean = false
)

/** Лимиты из секции «Память и хранилище» (хранятся в kinoshka_app_settings). */
data class StorageLimits(
    val imageLimitMb: Int,
    val retentionDays: Int,
    /** 0 = бессрочно. */
    val autoCleanup: Boolean,
    /** Доступные ступени слайдера лимита (МБ), по возрастанию. */
    val imageOptions: List<Int> = listOf(32, 80, 150)
)

fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return if (bytes < 1024) "$bytes Б" else "%.1f КБ".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f МБ".format(mb)
    return "%.2f ГБ".format(mb / 1024.0)
}

/** Один сегмент кольцевой диаграммы хранилища. */
private data class StorageDonutSegment(
    val key: String,
    val title: String,
    val bytes: Long,
    val color: androidx.compose.ui.graphics.Color
)

/** Фиксированная палитра диаграммы и точек строк (как в Telegram). */
private fun storagePalette(): List<androidx.compose.ui.graphics.Color> = listOf(
    androidx.compose.ui.graphics.Color(0xFF5EA3F7),
    androidx.compose.ui.graphics.Color(0xFF3BC5C3),
    androidx.compose.ui.graphics.Color(0xFF4A7FD4),
    androidx.compose.ui.graphics.Color(0xFFF2994A),
    androidx.compose.ui.graphics.Color(0xFFF2C94C),
    androidx.compose.ui.graphics.Color(0xFF9AA4B2)
)

private fun storageShareText(bytes: Long, total: Long): String {
    if (total <= 0 || bytes <= 0) return "0%"
    val percent = bytes * 100.0 / total
    if (percent < 1.0) return "<1%"
    return "${Math.round(percent)}%"
}

/** «1,8 ГБ» → ("1,8" to "ГБ") для крупного центра диаграммы. */
private fun formatStorageParts(bytes: Long): Pair<String, String> {
    if (bytes <= 0) return "0" to "Б"
    val text = formatStorageBytes(bytes)
    val split = text.indexOf(' ')
    if (split < 0) return text to ""
    return text.substring(0, split) to text.substring(split + 1)
}

/**
 * Содержимое страницы «Память и хранилище» в стиле Telegram: кольцевая диаграмма
 * с долями сверху, строки разделов с размером, большая кнопка очистки кэша,
 * ниже — автоудаление временных файлов и лимит кэша картинок.
 */
@Composable
fun StorageSectionCard(
    rows: List<StorageUsageRow>,
    limits: StorageLimits?,
    clearingKeys: Set<String>,
    onClearSelected: (List<String>) -> Unit,
    onImageLimitSelected: (Int) -> Unit,
    onRetentionSelected: (Int) -> Unit,
    onAutoCleanupChanged: (Boolean) -> Unit
) {
    var confirmDestructive by remember { mutableStateOf(false) }
    var otherExpanded by remember { mutableStateOf(false) }
    // Выбранные для очистки разделы (как чекбоксы в Telegram). Дефолт — всё неудаляемое
    // ненулевое; после обновлений сбрасываем обнулившиеся ключи, остальное живёт дальше.
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    val busy = clearingKeys.isNotEmpty()
    LaunchedEffect(rows) {
        val alive = rows.filter { it.bytes > 0 }.map { it.key }.toSet()
        selection = selection?.intersect(alive)
            ?: rows.filter { !it.destructive && it.bytes > 0 }.map { it.key }.toSet()
    }
    val selected = selection.orEmpty()
    val selectedBytes = rows.filter { it.key in selected }.sumOf { it.bytes }
    val destructiveSelected = rows.filter { it.key in selected && it.destructive }
    val total = rows.sumOf { it.bytes }
    val sorted = rows.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    val top = sorted.take(4)
    val rest = sorted.drop(4)
    // Нулевые разделы в диаграмму не попадают, но в списке показываем все —
    // иначе пункты пропадают со страницы без объяснений.
    val zeroRows = rows.filter { it.bytes <= 0 }
    val restBytes = rest.sumOf { it.bytes }
    val palette = storagePalette()
    val rowColors = buildMap<String, androidx.compose.ui.graphics.Color> {
        top.forEachIndexed { index, row -> put(row.key, palette[index % palette.size]) }
        rest.forEachIndexed { index, row -> put(row.key, palette[(top.size + index) % palette.size]) }
    }
    val segments = buildList {
        // В диаграмме — только выбранное; цвета стабильны по общему рангу.
        val selTop = top.filter { it.key in selected }
        val selRestBytes = rest.filter { it.key in selected }.sumOf { it.bytes }
        selTop.forEach { row ->
            add(StorageDonutSegment(row.key, row.title, row.bytes, rowColors[row.key] ?: palette.first()))
        }
        if (selRestBytes > 0) {
            add(StorageDonutSegment("other", "Другое", selRestBytes, palette[selTop.size % palette.size]))
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        StorageDonut(
            segments = segments,
            totalBytes = total,
            centerBytes = selectedBytes,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        )
        Text(
            text = "Использование памяти",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        Text(
            text = "Занято приложением: ${formatStorageBytes(total)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp)
        )

        KinoSettingsCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                fun toggle(key: String) {
                    if (busy) return
                    selection = selection?.let {
                        if (it.contains(key)) it - key else it + key
                    } ?: setOf(key)
                }
                top.forEachIndexed { index, row ->
                    if (index > 0) KinoSettingsDivider()
                    StorageCategoryRow(
                        color = rowColors[row.key] ?: palette.first(),
                        title = row.title,
                        shareText = storageShareText(row.bytes, total),
                        bytesText = formatStorageBytes(row.bytes),
                        clearing = row.key in clearingKeys,
                        selected = row.key in selected,
                        onToggle = { toggle(row.key) }
                    )
                }
                if (rest.isNotEmpty()) {
                    KinoSettingsDivider()
                    StorageOtherRow(
                        bytes = restBytes,
                        total = total,
                        expanded = otherExpanded,
                        onToggle = { otherExpanded = !otherExpanded }
                    )
                    if (otherExpanded) {
                        rest.forEach { row ->
                            KinoSettingsDivider()
                            StorageCategoryRow(
                                color = rowColors[row.key] ?: palette.last(),
                                title = row.title,
                                shareText = storageShareText(row.bytes, total),
                                bytesText = formatStorageBytes(row.bytes),
                                clearing = row.key in clearingKeys,
                                selected = row.key in selected,
                                onToggle = { toggle(row.key) }
                            )
                        }
                    }
                }
                zeroRows.forEach { row ->
                    KinoSettingsDivider()
                    StorageCategoryRow(
                        color = MaterialTheme.colorScheme.outline,
                        title = row.title,
                        shareText = "0%",
                        bytesText = formatStorageBytes(row.bytes),
                        clearing = row.key in clearingKeys,
                        selected = row.key in selected,
                        onToggle = { toggle(row.key) }
                    )
                }
            }
        }

        Button(
            onClick = {
                if (destructiveSelected.isNotEmpty()) confirmDestructive = true
                else onClearSelected(selected.toList())
            },
            enabled = !busy && selectedBytes > 0,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp)
        ) {
            Text("Очистить кэш ${formatStorageBytes(selectedBytes)}")
        }

        KinoSettingsSectionHeader("Автоудаление временных файлов")
        KinoSettingsCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                val currentRetention = limits?.retentionDays ?: 30
                listOf(7 to "7 дней", 30 to "30 дней", 0 to "Бессрочно").forEachIndexed { index, (days, label) ->
                    if (index > 0) KinoSettingsDivider()
                    KinoSettingsRow(
                        title = label,
                        onClick = { onRetentionSelected(days) },
                        trailing = {
                            RadioButton(
                                selected = currentRetention == days,
                                onClick = { onRetentionSelected(days) }
                            )
                        }
                    )
                }
            }
        }
        KinoSettingsSectionHeader("Максимальный размер кэша")
        KinoSettingsCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                val imageOptions = limits?.imageOptions?.takeIf { it.size >= 2 }
                    ?: listOf(32, 80, 150)
                val maxIndex = imageOptions.size - 1
                val selectedIndex = imageOptions.indexOf(limits?.imageLimitMb ?: 80).takeIf { it >= 0 } ?: 1
                Slider(
                    value = selectedIndex.toFloat(),
                    onValueChange = { onImageLimitSelected(imageOptions[Math.round(it).coerceIn(0, maxIndex)]) },
                    valueRange = 0f..maxIndex.toFloat(),
                    steps = (maxIndex - 1).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    imageOptions.forEachIndexed { index, mb ->
                        Text(
                            text = "$mb МБ",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == selectedIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Text(
                    text = "Кэш изображений сверх лимита подрезается. Применится после перезапуска.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                KinoSettingsDivider(modifier = Modifier.padding(top = 12.dp))
                KinoSettingsRow(
                    title = "Автоочистка при старте",
                    summary = "Подрезать кэш до лимитов при запуске приложения",
                    trailing = {
                        Switch(
                            checked = limits?.autoCleanup ?: false,
                            onCheckedChange = onAutoCleanupChanged
                        )
                    }
                )
            }
        }
    }
    if (confirmDestructive) {
        val doomed = destructiveSelected
        AlertDialog(
            onDismissRequest = { confirmDestructive = false },
            title = { Text("Удалить выбранное?") },
            text = {
                Text(
                    buildString {
                        doomed.forEach { row ->
                            appendLine("• ${row.title} — ${formatStorageBytes(row.bytes)}")
                        }
                        if (doomed.any { it.key == "offline" }) {
                            appendLine("Все скачанные серии будут удалены с устройства.")
                        }
                        append("Освободится около ${formatStorageBytes(selectedBytes)}.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDestructive = false; onClearSelected(selected.toList()) }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDestructive = false }) { Text("Отмена") }
            }
        )
    }
}

/**
 * Кольцевая диаграмма хранилища: только выбранные разделы, доли от общего объёма
 * (стабильны при переключениях), в центре — выбранное для очистки.
 * Сегменты плавно перетекают при изменениях: углы анимируются по ключу категории,
 * удалённые тают в ноль.
 */
@Composable
private fun StorageDonut(
    segments: List<StorageDonutSegment>,
    totalBytes: Long,
    centerBytes: Long,
    modifier: Modifier = Modifier
) {
    val total = totalBytes.coerceAtLeast(1L)
    val targets = remember(segments, total) {
        segments.associate { it.key to (it.bytes.toFloat() / total * 360f) }
    }
    // По одной анимации на категорию: новые вырастают из нуля, удалённые тают.
    val animatables = remember { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(targets) {
        targets.forEach { (key, target) ->
            val anim = animatables.getOrPut(key) {
                Animatable(0f)
            }
            launch {
                anim.animateTo(
                    target,
                    tween(
                        durationMillis = 700,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }
        (animatables.keys - targets.keys).forEach { gone ->
            launch {
                animatables[gone]?.animateTo(
                    0f,
                    tween(
                        durationMillis = 700,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }
    }
    val drawn = remember(targets) {
        // Порядок — как в targets, затем тающие (чтобы не прыгали поверх).
        (targets.keys.toList() + animatables.keys.filter { it !in targets }).distinct()
    }
    val animatedTotal by animateFloatAsState(
        targetValue = centerBytes.toFloat(),
        animationSpec = tween(
            durationMillis = 700,
            easing = FastOutSlowInEasing
        ),
        label = "storageTotal"
    )
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val chartSize = 160.dp
        val ringWidth = 36.dp
        Box(
            modifier = Modifier.size(chartSize),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = ringWidth.toPx()
                // Дуга со Stroke центрируется на окружности: без отступа внешняя
                // половина линии обрезалась краем канваса, а подписи висели на кромке.
                val arcTopLeft = Offset(strokePx / 2f, strokePx / 2f)
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                // Подложка кольца.
                drawArc(
                    color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.25f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx)
                )
                var startAngle = -90f
                drawn.forEach { key ->
                    val sweep = animatables[key]?.value ?: 0f
                    if (sweep > 0.5f) {
                        val gap = if (sweep > 5f) 2f else 0f
                        val color = segments.firstOrNull { it.key == key }?.color
                            ?: androidx.compose.ui.graphics.Color.Gray
                        drawArc(
                            color = color,
                            startAngle = startAngle + gap / 2f,
                            sweepAngle = sweep - gap,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx)
                        )
                    }
                    startAngle += sweep
                }
            }
            // Подписи долей поверх сегментов (только крупные, как в Telegram).
            var labelAngle = -90.0
            drawn.forEach { key ->
                val sweep = (animatables[key]?.value ?: 0f).toDouble()
                val share = sweep / 360.0
                if (share >= 0.07 && sweep > 1.0) {
                    val midRad = Math.toRadians(labelAngle + sweep / 2.0)
                    val radiusPx = with(density) { (chartSize.toPx() - ringWidth.toPx()) / 2f }
                    val dx = (radiusPx * kotlin.math.cos(midRad)).toFloat()
                    val dy = (radiusPx * kotlin.math.sin(midRad)).toFloat()
                    val label = "${Math.round(share * 100)}%"
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.ui.graphics.Color.White,
                        // align(Center) уже ставит центр текста в центр диаграммы —
                        // сдвиг ровно на полярный вектор, без вычета полтекста
                        // (он и уводил все подписи вверх-влево от сегментов).
                        modifier = Modifier.align(Alignment.Center).graphicsLayer {
                            translationX = dx
                            translationY = dy
                        }
                    )
                }
                labelAngle += sweep
            }
            // Центр: общий объём.
            val (value, unit) = formatStorageParts(animatedTotal.toLong().coerceAtLeast(0L))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Строка раздела хранилища: чекбокс цвета категории, название с долей, размер справа. Тап — выбрать/снять. */
@Composable
private fun StorageCategoryRow(
    color: androidx.compose.ui.graphics.Color,
    title: String,
    shareText: String,
    bytesText: String,
    clearing: Boolean,
    selected: Boolean,
    onToggle: () -> Unit
) {
    // Плавное появление/исчезновение галочки.
    val checkProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "storageCheck"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = checkProgress))
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 1f - checkProgress),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = checkProgress),
                modifier = Modifier.size(15.dp).graphicsLayer {
                    val scale = 0.5f + 0.5f * checkProgress
                    scaleX = scale
                    scaleY = scale
                }
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = "$title $shareText",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (clearing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(
                text = bytesText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Раскрывающаяся строка «Другое»: мелкие разделы прячутся под шеврон, как в Telegram. */
@Composable
private fun StorageOtherRow(
    bytes: Long,
    total: Long,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) {
                Icons.Filled.KeyboardArrowDown
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = "Другое ${storageShareText(bytes, total)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatStorageBytes(bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Строка настроек для поиска на странице Профиля: тап открывает сами настройки.
 */
data class SettingsSearchEntry(
    val title: String,
    val subtitle: String,
    val keywords: String
)

/** Все строки экрана настроек, видимые при данных флагах (для поиска с Профиля). */
fun settingsSearchEntries(
    hasPlayerSettings: Boolean,
    showDebugSettings: Boolean
): List<SettingsSearchEntry> = buildList {
    add(SettingsSearchEntry("Тема", "Внешний вид приложения", "тема тёмная светлая amoled оформление внешность"))
    add(SettingsSearchEntry("Размер плиток (Обзор)", "Крупные или компактные обложки", "плитки размер обзор сетка крупные мелкие"))
    add(SettingsSearchEntry("Размер плиток (Библиотека)", "Крупные или компактные обложки", "плитки размер библиотека сетка крупные мелкие"))
    add(SettingsSearchEntry("Плеер фильмов", "Какой плеер открывает кино", "плеер mpv ex внешний внутренний кино"))
    if (hasPlayerSettings) {
        add(SettingsSearchEntry("Настройки плеера mpvEx", "Скорость, жесты, субтитры, декодер", "mpvex скорость жесты субтитры декодер сброс плеер"))
    }
    add(SettingsSearchEntry("Скрывать российские фильмы", "Фильтр обзора и библиотеки", "скрыть российские русские фильтр"))
    add(SettingsSearchEntry("Источники", "Включение и проверка Kodik, Turbo, VideoCDN", "источники kodik turbo videocdn collaps voidboost alloha veoveo shikimori aniliberty anilib anistar smarthard хентай фильмы сериалы мультфильмы аниме проверка вкл выкл"))
    if (showDebugSettings) {
        add(SettingsSearchEntry("Показывать FPS", "Счётчик кадров (только debug)", "fps кадры счётчик отладка debug"))
        add(SettingsSearchEntry("Прокси для источников", "VideoCDN, хентай, YouTube", "прокси proxy заблокированные источники"))
    }
    add(SettingsSearchEntry("Память и хранилище", "Размер кэша, очистка, загрузки", "память хранилище кэш cache очистить загрузки вес размер лимит telegram"))
    add(SettingsSearchEntry("О приложении", "Версия, обновления и ссылки", "о приложении версия обновление github telegram шикимори"))
}

/**
 * Совпадение поискового запроса: все слова запроса должны встретиться
 * хотя бы в одном из полей. Пустой запрос совпадает со всем.
 */
fun matchesSearchQuery(query: String, vararg fields: String): Boolean {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    val haystacks = fields.map { it.lowercase() }
    return tokens.all { token -> haystacks.any { it.contains(token) } }
}

/**
 * Текстовое поле прокси в отладочных настройках: через него ходят вебмастер-источники
 * (VideoCDN/Collaps/Voidboost), хентай-каталоги и YouTube — OkHttp по хосту запроса
 * (StreamProxySelector) и mpv перед loadfile (http-proxy). Остальной трафик — напрямую.
 */
@Composable
private fun StreamProxySettingRow(initial: String, onChanged: (String) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            "Прокси для заблокированных источников",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; onChanged(it) },
            placeholder = { Text("http://host:port или socks5://host:port") },
            supportingText = { Text("VideoCDN / Collaps / Voidboost, хентай, YouTube. Пусто — напрямую") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    updateStatusText: String,
    isUpdateCheckRunning: Boolean,
    onCheckUpdates: () -> Unit,
    onOpenGithub: () -> Unit,
    onOpenTelegram: () -> Unit,
    onOpenShikimori: () -> Unit,
    appVersion: String,
    appPackage: String? = null,
    onReportProblem: (() -> Unit)? = null,
    // Настоящая иконка приложения в круге (Android передаёт лаунчер-иконку);
    // null — запасной SmartDisplay (desktop без ресурса иконки).
    appIcon: (@Composable () -> Unit)? = null
) {
    val isUpdateAvailable = updateStatusText.contains("Доступна", ignoreCase = true)
    val statusColor = if (isUpdateAvailable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingsHeaderCard(
                title = "О приложении",
                subtitle = "Версия, обновления и ссылки",
                onBack = onBack
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                        .animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (appIcon != null) {
                        appIcon()
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.SmartDisplay,
                            contentDescription = "Иконка приложения",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(90.dp)
                        )
                    }
                    Text(
                        text = "Киношка",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = appVersion,
                        style = MaterialTheme.typography.titleSmall
                    )
                    appPackage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Circle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = updateStatusText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                    }
                    Button(
                        onClick = onCheckUpdates,
                        modifier = Modifier.fillMaxWidth(0.9f),
                        enabled = !isUpdateCheckRunning
                    ) {
                        Text(if (isUpdateCheckRunning) "Проверка..." else "Проверить обновления")
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AboutLinkRow(
                        badge = "GH",
                        title = "GitHub",
                        subtitle = "Исходный код приложения",
                        onClick = onOpenGithub
                    )
                    AboutLinkRow(
                        badge = "TG",
                        title = "Telegram",
                        subtitle = "Новые версии, обсуждение и новости",
                        onClick = onOpenTelegram
                    )
                    AboutLinkRow(
                        badge = "SH",
                        title = "Shikimori",
                        subtitle = "Энциклопедия аниме и манги",
                        onClick = onOpenShikimori
                    )
                    if (onReportProblem != null) {
                        AboutLinkRow(
                            badge = "!",
                            title = "Собрать отчёт о проблеме",
                            subtitle = "Устройство, события плеера и логи — отправить в любое приложение",
                            onClick = onReportProblem
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsSelectBottomSheet(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        hd.kinoshka.app.ui.platform.KinoKeepDialogNavBarEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onSelect(option)
                            onDismiss()
                        }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(
                        selected = selected == option,
                        onClick = {
                            onSelect(option)
                            onDismiss()
                        }
                    )
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SettingsSelectRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutLinkRow(
    badge: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun AppThemeMode.toSettingsLabel(): String {
    return when (this) {
        AppThemeMode.CURRENT -> "Системная"
        AppThemeMode.DARK -> "Темная"
        AppThemeMode.AMOLED -> "AMOLED"
    }
}

private fun FilmTileSize.toSettingsLabel(): String {
    return when (this) {
        FilmTileSize.COMPACT -> "4 в ряд"
        FilmTileSize.MEDIUM -> "3 в ряд"
        FilmTileSize.LARGE -> "2 в ряд"
        FilmTileSize.VERTICAL -> "Вертикальные"
    }
}

@Composable
fun SettingsHeaderCard(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    // Действие справа от заголовка (например, «Очистить всё» на Загрузках).
    action: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp).clickable(onClick = onBack),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                action()
            }
        }
    }
}
