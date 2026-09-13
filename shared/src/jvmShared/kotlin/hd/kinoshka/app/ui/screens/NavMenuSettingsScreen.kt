package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Feed
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Управление нижней пилюлей: видимость вкладок, их порядок и вибрация нажатий.
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
    // Контурные, как невыбранные кнопки пилюли (строки всегда показывают покой).
    MainSection.LIBRARY.name -> Icons.AutoMirrored.Outlined.List
    MainSection.DISCOVER.name -> Icons.Outlined.Explore
    MainSection.FEED.name -> Icons.AutoMirrored.Outlined.Feed
    MainSection.PROFILE.name -> Icons.Outlined.PersonOutline
    else -> Icons.AutoMirrored.Outlined.List
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
    // Глифы вкладок как на телефоне (KinoApp inject'ит drawable-иконки,
    // без инъекции — material-фолбэк navSectionIcon). Невыбранный вариант.
    libraryGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    discoverGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    feedGlyph: (@Composable (selected: Boolean) -> Unit)? = null,
    profileGlyph: (@Composable (selected: Boolean) -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SettingsHeaderCard(
                title = "Навигационное меню",
                subtitle = "Вкладки пилюли и вибрация",
                onBack = onBack,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        item {
            KinoSettingsSectionHeader("Вкладки")
        }
        item {
            KinoSettingsCard {
                order.forEachIndexed { index, name ->
                    if (index > 0) KinoSettingsDivider()
                    val visible = name !in hidden
                    val toggleable = canNavSectionHide(name)
                    val injected = when (name) {
                        MainSection.LIBRARY.name -> libraryGlyph
                        MainSection.DISCOVER.name -> discoverGlyph
                        MainSection.FEED.name -> feedGlyph
                        MainSection.PROFILE.name -> profileGlyph
                        else -> null
                    }
                    KinoSettingsRow(
                        title = navSectionTitle(name),
                        summary = if (toggleable) {
                            "Позиция ${index + 1} из ${order.size}"
                        } else {
                            "Позиция ${index + 1} из ${order.size} · всегда в меню"
                        },
                        icon = if (injected == null) navSectionIcon(name) else null,
                        iconContent = injected?.let { glyph -> { glyph(false) } },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onMoveSection(index, index - 1) },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "Выше"
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveSection(index, index + 1) },
                                    enabled = index < order.size - 1
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Ниже"
                                    )
                                }
                                Switch(
                                    checked = visible,
                                    enabled = toggleable,
                                    onCheckedChange = { onToggleSection(name, it) }
                                )
                            }
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
                KinoSettingsRow(
                    title = "Вибрация меню",
                    summary = "Отклик при нажатии вкладок пилюли",
                    icon = Icons.Outlined.Vibration,
                    trailing = {
                        Switch(checked = hapticsEnabled, onCheckedChange = onHapticsEnabledChanged)
                    }
                )
                KinoSettingsDivider()
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
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
                            text = "${(hapticScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = hapticScale,
                        onValueChange = onHapticScaleChanged,
                        valueRange = 0f..1f,
                        enabled = hapticsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Ноль — бесшумно, системный тумблер тактильного отклика уважаем всегда",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
