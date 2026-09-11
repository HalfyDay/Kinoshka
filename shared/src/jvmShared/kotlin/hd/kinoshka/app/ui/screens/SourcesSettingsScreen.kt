package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.source.PlaybackSourceInfo
import hd.kinoshka.app.data.source.PlaybackSources
import hd.kinoshka.app.data.source.SourceCategory
import hd.kinoshka.app.data.source.SourceHealth
import hd.kinoshka.app.data.source.SourceHealthChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Отдельная страница управления источниками (из «Настроек»): категории
 * Фильмы / Аниме / 18+, один выключатель на источник (выключен — значит
 * не запрашивается и не показывается) и проверка работоспособности каждого
 * и всех сразу. Шапка и фильтр категорий закреплены над списком.
 * Общая для Android и desktop.
 */
@Composable
fun SourcesSettingsScreen(
    onBack: () -> Unit,
    disabledSources: Set<String>,
    onSourceEnabledChanged: (String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf<SourceCategory?>(null) }
    var sourcesHealth by remember { mutableStateOf<Map<String, SourceHealth>>(emptyMap()) }
    var checkingAll by remember { mutableStateOf(false) }
    var checkingOne by remember { mutableStateOf<String?>(null) }

    fun checkOne(id: String) {
        if (checkingOne != null || checkingAll) return
        checkingOne = id
        scope.launch {
            val health = withContext(Dispatchers.IO) { SourceHealthChecker.check(id) }
            sourcesHealth = sourcesHealth + (health.id to health)
            checkingOne = null
        }
    }

    fun checkAll() {
        if (checkingAll) return
        checkingAll = true
        scope.launch {
            val map = withContext(Dispatchers.IO) {
                SourceHealthChecker.checkAll(PlaybackSources.ALL.map { it.id })
            }
            sourcesHealth = map
            checkingAll = false
        }
    }

    val visibleSources = remember(selectedCategory) {
        if (selectedCategory == null) PlaybackSources.ALL
        else PlaybackSources.ALL.filter { selectedCategory in it.categories }
    }
    val checkedCount = sourcesHealth.size
    val okCount = sourcesHealth.values.count { it.ok }

    // Шапка и фильтр категорий закреплены над списком (без stickyHeader API).
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsHeaderCard(
            title = "Источники",
            subtitle = "Что играет кино, сериалы и аниме",
            onBack = onBack,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("Все") }
                )
            }
            items(SourceCategory.entries) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category.title) }
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
            KinoSettingsCard {
                KinoSettingsRow(
                    title = "Проверить все источники",
                    summary = if (checkedCount == 0) {
                        "Kodik, прямые ссылки, аниме-каталоги и 18+"
                    } else {
                        "Проверено $checkedCount из ${PlaybackSources.ALL.size} · работают: $okCount"
                    },
                    trailing = {
                        if (checkingAll) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = ::checkAll) { Text("Проверить") }
                        }
                    }
                )
            }
            }
        if (selectedCategory == null) {
            SourceCategory.entries.forEach { category ->
                val inCategory = visibleSources.filter { category in it.categories }
                if (inCategory.isNotEmpty()) {
                    item {
                        KinoSettingsSectionHeader(category.title)
                    }
                    items(inCategory, key = { category.name + ":" + it.id }) { info ->
                        SourcePageRow(
                            info = info,
                            enabled = info.id !in disabledSources,
                            health = sourcesHealth[info.id],
                            checking = checkingOne == info.id || checkingAll,
                            onEnabledChanged = { onSourceEnabledChanged(info.id, it) },
                            onCheck = { checkOne(info.id) }
                        )
                    }
                }
            }
        } else {
            items(visibleSources, key = { it.id }) { info ->
                SourcePageRow(
                    info = info,
                    enabled = info.id !in disabledSources,
                    health = sourcesHealth[info.id],
                    checking = checkingOne == info.id || checkingAll,
                    onEnabledChanged = { onSourceEnabledChanged(info.id, it) },
                    onCheck = { checkOne(info.id) }
                )
            }
        }
    }
    }
}

/**
 * Строка источника на отдельной странице: Surface-плитка в стиле пикера —
 * точка статуса, название, описание, строка проверки и выключатель.
 * Тап по плитке (мимо выключателя) запускает проверку.
 */
@Composable
private fun SourcePageRow(
    info: PlaybackSourceInfo,
    enabled: Boolean,
    health: SourceHealth?,
    checking: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onCheck: () -> Unit
) {
    val statusColor = when {
        checking || !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        health == null -> MaterialTheme.colorScheme.outline
        health.ok -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        onClick = onCheck,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = info.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (info.needsVpn) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    "VPN",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        text = info.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        !enabled -> "Выключен: не запрашивается и не показывается"
                        checking -> "Проверка…"
                        health == null -> "Не проверен — нажмите, чтобы проверить"
                        health.ok -> "Работает · ${formatSourceLatency(health.latencyMs)} · ${health.message}"
                        else -> "Недоступен · ${health.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (health != null && !health.ok && enabled && !checking) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onCheck, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Проверить",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatSourceLatency(latencyMs: Long): String = when {
    latencyMs < 1000 -> "$latencyMs мс"
    else -> "%.1f c".format(latencyMs / 1000.0)
}
