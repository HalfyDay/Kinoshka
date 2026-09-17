package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.local.FilmTileSize

/**
 * Страница настроек раздела (Обзор / Библиотека): пока здесь только размер
 * плиток, но уже отдельным экраном с визуальным выбором вместо текстового
 * шита — схема показывает реальную геометрию сетки (число колонок 2:3,
 * вертикальный список — строки). Общая для Android и desktop.
 */
@Composable
fun TileSizeSettingsScreen(
    onBack: () -> Unit,
    title: String,
    subtitle: String,
    selected: FilmTileSize,
    onSelected: (FilmTileSize) -> Unit
) {
    // Шапка-пилюля закреплена и парит без подложки (с тенью и градиентом),
    // как на остальных страницах настроек.
    PinnedHeaderPage(
        title = title,
        subtitle = subtitle,
        onBack = onBack
    ) { topPad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
        ) {
        item {
            KinoSettingsSectionHeader("Размер плиток")
        }
        item {
            KinoSettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val options = FilmTileSize.entries.toList()
                    options.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { size ->
                                TileSizeOption(
                                    size = size,
                                    selected = size == selected,
                                    onClick = { onSelected(size) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                            // Нечётный хвост (сейчас не бывает — опций 4) добиваем пустотой.
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Text(
                        text = "Применяется к сетке сразу",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        }
    }
}

/** Карточка варианта: схема сетки + подпись, выбранная подсвечена. */
@Composable
private fun TileSizeOption(
    size: FilmTileSize,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        modifier = modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TileScheme(size = size, modifier = Modifier.fillMaxWidth().height(108.dp))
            Text(
                text = size.toSettingsLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Схема геометрии: колонки постеров 2:3 или строки вертикального списка. */
@Composable
private fun TileScheme(size: FilmTileSize, modifier: Modifier = Modifier) {
    val columns = when (size) {
        FilmTileSize.COMPACT -> 4
        FilmTileSize.MEDIUM -> 3
        FilmTileSize.LARGE -> 2
        FilmTileSize.VERTICAL -> 1
    }
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    if (columns == 1) {
        // Три строки по 30dp + промежутки 2x6 = 102 <= 108: ничего не слипается
        // и подпись под схемой не перекрывается (было 26dp -> 39dp высота и налезание).
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
        ) {
            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = block,
                        modifier = Modifier.width(20.dp).aspectRatio(2f / 3f)
                    ) {}
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = line,
                            modifier = Modifier.fillMaxWidth().height(5.dp)
                        ) {}
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = line,
                            modifier = Modifier.fillMaxWidth(0.6f).height(5.dp)
                        ) {}
                    }
                }
            }
        }
    } else {
        // Постеры ужаты под доступную высоту: у «2 в ряд» широкие постеры 2:3
        // раньше выталкивали строки названий за край схемы (их не было видно).
        // Линии той же ширины, что постеры, и отцентрированы вместе с ними.
        BoxWithConstraints(modifier = modifier) {
            val gap = 4.dp
            val linesH = 11.dp // 5dp линия + 6dp отступ
            val maxPosterW = (maxWidth - gap * (columns - 1)) / columns
            val maxPosterH = maxHeight - linesH
            val posterH = minOf(maxPosterW * 1.5f, maxPosterH)
            val posterW = posterH * (2f / 3f)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(columns) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = block,
                            modifier = Modifier.width(posterW).height(posterH)
                        ) {}
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                ) {
                    repeat(columns) {
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = line,
                            modifier = Modifier.width(posterW).height(5.dp)
                        ) {}
                    }
                }
            }
        }
    }
}
