package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Разделение настроек по группам в стиле mpvEx: плоские карточки 28dp
 * surfaceContainer без elevation, заголовки секций labelLarge/primary,
 * разделители outlineVariant 50%, иконка 24dp primary в контейнере 56dp.
 * Типографика строк — старая, kinoshka (заголовок bodyMedium SemiBold,
 * подпись bodySmall onSurfaceVariant).
 */

/** Групповая карточка: 28dp, surfaceContainer, 0 elevation, внутренний отступ 8dp по вертикали. */
@Composable
fun KinoSettingsCard(
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            content()
        }
    }
}

/** Заголовок секции вне карточек: labelLarge, primary, выравнен по левому краю иконок. */
@Composable
fun KinoSettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** Разделитель между строками внутри карточки. */
@Composable
fun KinoSettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * Страница настроек с закреплённой шапкой: [headerCard] (по умолчанию
 * [SettingsHeaderCard]) и, опционально, [extraHeader] — например, ряд
 * фильтров — парят ПОВЕРХ контента без фоновой полосы. Контент занимает весь
 * экран и уходит под шапку, поэтому [content] обязан начинаться с верхнего
 * отступа [topPad] (обычно contentPadding списка) — иначе первый элемент
 * окажется под шапкой. Обрезка скролла видна только за самой шапкой, глухих
 * полос, режущих строки в открытом виде, нет.
 */
@Composable
fun PinnedHeaderPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    headerActions: (@Composable () -> Unit)? = null,
    extraHeader: (@Composable ColumnScope.() -> Unit)? = null,
    estimatedTopPad: Dp = 120.dp,
    headerCard: @Composable ColumnScope.() -> Unit = {
        SettingsHeaderCard(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            action = headerActions
        )
    },
    content: @Composable BoxScope.(topPad: Dp) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        var headerHPx by remember { mutableStateOf<Int?>(null) }
        val topPad = headerHPx?.let { with(LocalDensity.current) { it.toDp() } + 10.dp }
            ?: estimatedTopPad
        content(topPad)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHPx = it.height }
        ) {
            headerCard()
            if (extraHeader != null) extraHeader()
        }
    }
}

/** Строка настройки: иконка primary 24dp в контейнере 56dp, title + summary, опциональный виджет справа. */
@Composable
fun KinoSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    // Кастомная иконка вместо ImageVector (например, drawable-глиф вкладки
    // с телефона): рисуется в том же контейнере без primary-тинта.
    iconContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconContent != null) {
            Box(
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                iconContent()
            }
        } else if (icon != null) {
            Box(
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (icon != null || iconContent != null) 0.dp else 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(end = 16.dp)) { trailing() }
        }
    }
}
