package hd.kinoshka.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import hd.kinoshka.app.data.local.PlayerMode
import hd.kinoshka.app.data.model.PlaybackSequenceOption

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
    selectedPlaybackSequence: PlaybackSequenceOption,
    onPlaybackSequenceSelected: (PlaybackSequenceOption) -> Unit,
    selectedPlayerMode: PlayerMode,
    onPlayerModeSelected: (PlayerMode) -> Unit,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onHideRussianChanged: (Boolean) -> Unit,
    onDiscoverTileSizeSelected: (FilmTileSize) -> Unit,
    onLibraryTileSizeSelected: (FilmTileSize) -> Unit,
    onShowFpsCounterChanged: (Boolean) -> Unit,
    showDebugSettings: Boolean = false
) {
    var showThemePicker by remember { mutableStateOf(false) }
    var showDiscoverTileSizePicker by remember { mutableStateOf(false) }
    var showLibraryTileSizePicker by remember { mutableStateOf(false) }
    var showPlaybackSequencePicker by remember { mutableStateOf(false) }
    var showPlayerModePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsHeaderCard(
                title = "Настройки",
                subtitle = "Внешний вид и библиотека",
                onBack = onBack
            )
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsSelectRow(
                        title = "Тема",
                        value = selectedThemeMode.toSettingsLabel(),
                        onClick = { showThemePicker = true }
                    )
                    SettingsSelectRow(
                        title = "Размер плиток (Обзор)",
                        value = selectedDiscoverTileSize.toSettingsLabel(),
                        onClick = { showDiscoverTileSizePicker = true }
                    )
                    SettingsSelectRow(
                        title = "Размер плиток (Библиотека)",
                        value = selectedLibraryTileSize.toSettingsLabel(),
                        onClick = { showLibraryTileSizePicker = true }
                    )
                    SettingsSelectRow(
                        title = "Порядок выбора в плеере",
                        value = selectedPlaybackSequence.toUiLabel(),
                        onClick = { showPlaybackSequencePicker = true }
                    )
                    SettingsSelectRow(
                        title = "Плеер фильмов",
                        value = selectedPlayerMode.displayName,
                        onClick = { showPlayerModePicker = true }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Скрывать российские фильмы/сериалы",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Фильтр применяется к обзору и библиотеке",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = hideRussianContent, onCheckedChange = onHideRussianChanged)
                    }

                    if (showDebugSettings) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Показывать FPS",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Счетчик кадров поверх главного экрана (только debug).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = selectedShowFpsCounter,
                                onCheckedChange = onShowFpsCounterChanged
                            )
                        }
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

    if (showPlaybackSequencePicker) {
        SettingsSelectBottomSheet(
            title = "Порядок выбора в плеере",
            options = PlaybackSequenceOption.entries.toList(),
            selected = selectedPlaybackSequence,
            optionLabel = { it.toUiLabel() },
            onSelect = onPlaybackSequenceSelected,
            onDismiss = { showPlaybackSequencePicker = false }
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
    onReportProblem: (() -> Unit)? = null
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
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                        .animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SmartDisplay,
                        contentDescription = "Иконка приложения",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(90.dp)
                    )
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
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
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
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
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
private fun SettingsHeaderCard(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
