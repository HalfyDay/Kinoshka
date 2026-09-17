package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.source.CustomSource
import hd.kinoshka.app.data.source.CustomSourceCheck
import hd.kinoshka.app.data.source.CustomSourceKind
import hd.kinoshka.app.data.source.PlaybackSourceInfo
import hd.kinoshka.app.data.source.PlaybackSources
import hd.kinoshka.app.data.source.SourceCategory
import hd.kinoshka.app.data.source.SourceHealth
import hd.kinoshka.app.data.source.SourceHealthChecker
import hd.kinoshka.app.data.source.buildCustomId
import hd.kinoshka.app.data.source.validateCustomSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Отдельная страница управления источниками (из «Настроек»): категории
 * Фильмы / Аниме / 18+, выключатель на источник в каждом его разделе
 * (выключен — значит не запрашивается и не показывается в этом разделе)
 * и проверка работоспособности каждого и всех сразу. Шапка и фильтр
 * категорий закреплены над списком. Общая для Android и desktop.
 */
@Composable
fun SourcesSettingsScreen(
    onBack: () -> Unit,
    // Сырые ключи выключения («ID» = везде, «КАТЕГОРИЯ:ID» = только раздел):
    // один источник можно гасить раздельно по разделам.
    disabledKeys: Set<String>,
    onSourceEnabledChanged: (String, SourceCategory?, Boolean) -> Unit,
    // Слот иконки: платформа подставляет реальные картинки (Android — те же
    // drawable, что в пикере озвучек/источников, + PNG-логотипы; desktop —
    // PNG из ресурсов). По умолчанию — фирменный рисованный бейдж.
    sourceIcon: @Composable (PlaybackSourceInfo) -> Unit = { DefaultSourceAvatar(it) },
    // Свои источники (вариант A): null-колбэки прячут секцию (старые коллеры).
    customSources: List<CustomSource> = emptyList(),
    onSaveCustomSource: ((CustomSource) -> Unit)? = null,
    onDeleteCustomSource: ((String) -> Unit)? = null,
    // Обмен файлом JSON между устройствами (платформа открывает диалог
    // сохранения/выбора и дергает стор): null прячет свою кнопку.
    onExportCustomSourcesFile: (() -> Unit)? = null,
    onImportCustomSourcesFile: (() -> Unit)? = null,
    // Статус последнего обмена для платформ без тостов (desktop); Android — null.
    fileExchangeMessage: String? = null
) {
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf<SourceCategory?>(null) }
    var sourcesHealth by remember { mutableStateOf<Map<String, SourceHealth>>(emptyMap()) }
    var checkingAll by remember { mutableStateOf(false) }
    var checkingOne by remember { mutableStateOf<String?>(null) }
    var editingCustom by remember { mutableStateOf<CustomSource?>(null) }
    var addingCustom by remember { mutableStateOf(false) }
    var deletingCustom by remember { mutableStateOf<CustomSource?>(null) }

    // Удаление извне (или протухший health) — чистим статусы удалённых своих.
    LaunchedEffect(customSources) {
        val ids = customSources.map { it.id }.toSet()
        sourcesHealth = sourcesHealth.filterKeys { key ->
            !CustomSource.isCustomId(key) || key in ids
        }
    }

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
                SourceHealthChecker.checkAll(PlaybackSources.allInfos().map { it.id })
            }
            sourcesHealth = map
            checkingAll = false
        }
    }

    // Свои источники живут на отдельной вкладке «Свои» (справа в ряду фильтров),
    // а не в разрезах категорий — иначе они дублировались бы и там, и там.
    var customTab by remember { mutableStateOf(false) }
    val customSectionVisible = onSaveCustomSource != null && customTab

    val visibleSources = remember(selectedCategory, customTab) {
        val builtIns = PlaybackSources.allInfos().filter { !CustomSource.isCustomId(it.id) }
        if (customTab) {
            PlaybackSources.allInfos().filter { CustomSource.isCustomId(it.id) }
        } else if (selectedCategory == null) {
            builtIns
        } else {
            builtIns.filter { selectedCategory in it.categories }
        }
    }
    // Выключатель раздельный по разделам: голый id гасит везде, scoped — свой раздел.
    fun isEnabled(id: String, category: SourceCategory?): Boolean {
        val key = id.trim().uppercase()
        if (key in disabledKeys) return false
        if (category != null && "${category.name}:$key" in disabledKeys) return false
        return true
    }
    val checkedCount = sourcesHealth.size
    val okCount = sourcesHealth.values.count { it.ok }

    // Закреплена только шапка-пилюля (парит без подложки, с тенью и градиентом).
    // Фильтр категорий откреплён: уезжает вверх вместе со списком, первым рядом.
    PinnedHeaderPage(
        title = "Источники",
        subtitle = "Наличие зависит от фильма · проба на «Матрице»",
        onBack = onBack
    ) { topPad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
        ) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    item {
                        SourceFilterPill(
                            label = "Все",
                            selected = selectedCategory == null && !customTab,
                            onClick = { selectedCategory = null; customTab = false }
                        )
                    }
                    items(SourceCategory.entries) { category ->
                        SourceFilterPill(
                            label = category.title,
                            selected = selectedCategory == category && !customTab,
                            onClick = { selectedCategory = category; customTab = false }
                        )
                    }
                    // Свои — крайняя справа: отдельная страница кастомных источников.
                    item {
                        val count = customSources.size
                        SourceFilterPill(
                            label = if (count > 0) "Свои • $count" else "Свои",
                            selected = customTab,
                            onClick = { customTab = true }
                        )
                    }
                }
            }
            item {
            KinoSettingsCard {
                KinoSettingsRow(
                    title = "Проверить все источники",
                    summary = if (checkedCount == 0) {
                        "Kodik, прямые ссылки, аниме-каталоги и 18+"
                    } else {
                        "Проверено $checkedCount из ${PlaybackSources.allInfos().size} · работают: $okCount"
                    },
                    trailing = {
                        if (checkingAll) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            FilledTonalButton(
                                onClick = ::checkAll,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Проверить")
                            }
                        }
                    }
                )
            }
            }
        // Свои источники — над встроенными, чтобы секция не терялась внизу.
        if (customSectionVisible) {
            item {
                KinoSettingsSectionHeader("Свои источники")
            }
            item {
                KinoSettingsCard {
                    KinoSettingsRow(
                        title = "Добавить источник",
                        summary = "Embed-ссылки и Stremio-аддоны: ${customSources.size}",
                        trailing = {
                            FilledTonalButton(
                                onClick = { addingCustom = true },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Добавить")
                            }
                        }
                    )
                }
            }
            // Обмен с другим устройством: файл JSON (экспорт своих + импорт
            // слиянием: дубликаты пропускаются, коллизии переименовываются).
            if (onExportCustomSourcesFile != null || onImportCustomSourcesFile != null) {
                item {
                    KinoSettingsCard {
                        KinoSettingsRow(
                            title = "Поделиться источниками",
                            summary = "Файл JSON для переноса на другое устройство",
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (onExportCustomSourcesFile != null) {
                                        TextButton(
                                            onClick = onExportCustomSourcesFile,
                                            enabled = customSources.isNotEmpty()
                                        ) { Text("Экспорт") }
                                    }
                                    if (onImportCustomSourcesFile != null) {
                                        TextButton(onClick = onImportCustomSourcesFile) { Text("Импорт") }
                                    }
                                }
                            }
                        )
                    }
                }
            }
            if (fileExchangeMessage != null) {
                item {
                    Text(
                        text = fileExchangeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            items(customSources, key = { it.id }) { custom ->
                val info = PlaybackSources.customInfo(custom)
                SourcePageRow(
                    info = info,
                    // Вне разрезов: горит, когда включён во всех своих разделах;
                    // переключение применяется сразу ко всем (как у встроенных).
                    enabled = info.categories.all { isEnabled(info.id, it) },
                    health = sourcesHealth[info.id],
                    checking = checkingOne == info.id || checkingAll,
                    onEnabledChanged = { v ->
                        info.categories.forEach { onSourceEnabledChanged(info.id, it, v) }
                    },
                    onCheck = { checkOne(info.id) },
                    sourceIcon = sourceIcon,
                    onEdit = { editingCustom = custom },
                    onDelete = { deletingCustom = custom }
                )
            }
        }
        if (!customTab && selectedCategory == null) {
            SourceCategory.entries.forEach { category ->
                val inCategory = visibleSources.filter { category in it.categories }
                if (inCategory.isNotEmpty()) {
                    item {
                        KinoSettingsSectionHeader(category.title)
                    }
                    items(inCategory, key = { category.name + ":" + it.id }) { info ->
                        SourcePageRow(
                            info = info,
                            enabled = isEnabled(info.id, category),
                            health = sourcesHealth[info.id],
                            checking = checkingOne == info.id || checkingAll,
                            onEnabledChanged = { onSourceEnabledChanged(info.id, category, it) },
                            onCheck = { checkOne(info.id) },
                            sourceIcon = sourceIcon
                        )
                    }
                }
            }
        } else if (!customTab) {
            items(visibleSources, key = { it.id }) { info ->
                SourcePageRow(
                    info = info,
                    // Вне разделов (фильтр категории): горит, когда включён везде;
                    // переключение применяется сразу ко всем разделам источника.
                    enabled = info.categories.all { isEnabled(info.id, it) },
                    health = sourcesHealth[info.id],
                    checking = checkingOne == info.id || checkingAll,
                    onEnabledChanged = { v ->
                        info.categories.forEach { onSourceEnabledChanged(info.id, it, v) }
                    },
                    onCheck = { checkOne(info.id) },
                    sourceIcon = sourceIcon
                )
            }
        }
    }

    if (addingCustom || editingCustom != null) {
        CustomSourceEditDialog(
            existing = editingCustom,
            customs = customSources,
            onDismiss = { addingCustom = false; editingCustom = null },
            onSave = { src ->
                onSaveCustomSource?.invoke(src)
                addingCustom = false
                editingCustom = null
            }
        )
    }
    deletingCustom?.let { custom ->
        AlertDialog(
            onDismissRequest = { deletingCustom = null },
            title = { Text("Удалить источник?") },
            text = { Text("«${custom.name}» будет удалён из списка.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCustomSource?.invoke(custom.id)
                    deletingCustom = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deletingCustom = null }) { Text("Отмена") }
            }
        )
    }
    }
}

/**
 * Кнопка фильтра категорий без рамок: залитая пилюля (без outline-границы
 * FilterChip), выбранная — primaryContainer, остальные — surfaceContainerHigh.
 */
@Composable
private fun SourceFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shadowElevation = if (selected) 2.dp else 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * Строка источника на отдельной странице: Surface-плитка в стиле пикера —
 * слева иконка с ободком статуса (зелёный — работает, красный — недоступен,
 * серый — не проверен/выключен/проверяется), название, описание и выключатель.
 * Подписей статуса снизу нет. Тап по плитке (мимо выключателя) запускает проверку.
 */
@Composable
private fun SourcePageRow(
    info: PlaybackSourceInfo,
    enabled: Boolean,
    health: SourceHealth?,
    checking: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onCheck: () -> Unit,
    sourceIcon: @Composable (PlaybackSourceInfo) -> Unit,
    // Только свои источники: правка и удаление.
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val statusColor = when {
        checking || !enabled || health == null -> MaterialTheme.colorScheme.outline
        health.ok -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        onClick = onCheck,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Иконка источника: платформа подставляет реальные картинки через
            // слот sourceIcon (по умолчанию — фирменный рисованный бейдж),
            // ободок — статус проверки.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(2.dp, statusColor, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    sourceIcon(info)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
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
            if (onEdit != null) {
                androidx.compose.material3.IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Изменить",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (onDelete != null) {
                androidx.compose.material3.IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChanged)
        }
    }
}

/**
 * Диалог добавления/правки своего источника. Валидация — общая
 * ([validateCustomSource]), «Проверить» гоняет health-пробу по черновику
 * (сохранять для проверки не нужно).
 */
@Composable
private fun CustomSourceEditDialog(
    existing: CustomSource?,
    customs: List<CustomSource>,
    onDismiss: () -> Unit,
    onSave: (CustomSource) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: CustomSourceKind.EMBED) }
    val isStremio = kind == CustomSourceKind.STREMIO
    var template by remember { mutableStateOf(existing?.urlTemplate.orEmpty()) }
    var endpoint by remember {
        mutableStateOf(
            existing?.endpoint?.ifBlank { null }
                ?: existing?.takeIf { it.kind == CustomSourceKind.STREMIO }?.urlTemplate.orEmpty()
        )
    }
    var referer by remember { mutableStateOf(existing?.referer.orEmpty()) }
    var useProxy by remember { mutableStateOf(existing?.useProxy == true) }
    var webOnly by remember { mutableStateOf(existing?.webOnly == true) }
    var categories by remember {
        mutableStateOf(
            existing?.categories?.ifEmpty { setOf(SourceCategory.FILMS) }
                ?: setOf(SourceCategory.FILMS)
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var warnings by remember { mutableStateOf(emptyList<String>()) }
    var probeResult by remember { mutableStateOf<SourceHealth?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun draft(id: String) = CustomSource(
        id = id,
        name = name.trim(),
        urlTemplate = if (isStremio) "" else template.trim(),
        referer = if (isStremio) "" else referer.trim(),
        useProxy = useProxy && !isStremio,
        webOnly = webOnly && !isStremio,
        categories = categories,
        kind = kind,
        endpoint = if (isStremio) endpoint.trim() else ""
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Новый источник" else "Изменить источник") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Вид источника",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    CustomSourceKind.entries.forEach { entry ->
                        FilterChip(
                            selected = kind == entry,
                            onClick = { kind = entry; error = null; probeResult = null },
                            label = { Text(entry.title) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (isStremio) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it; error = null },
                        label = { Text("Адрес аддона") },
                        placeholder = { Text("https://host:port[/path]") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Stremio: фильмы, сериалы, аниме и 18+ по IMDb ID. Хвост /manifest.json необязателен.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    OutlinedTextField(
                        value = template,
                        onValueChange = { template = it; error = null },
                        label = { Text("Шаблон ссылки") },
                        placeholder = { Text("https://host/embed/kp/{kp}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "{kp} — Kinopoisk ID, {imdb} — IMDb ID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (!isStremio) {
                    OutlinedTextField(
                        value = referer,
                        onValueChange = { referer = it },
                        label = { Text("Referer (необязательно)") },
                        placeholder = { Text("origin embed-хоста") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useProxy, onCheckedChange = { useProxy = it })
                        Text(
                            text = "Через прокси",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = webOnly, onCheckedChange = { webOnly = it })
                        Text(
                            text = "Только веб-плеер (не извлекать поток)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    }
                    Text(
                        text = "Разделы",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        SourceCategory.entries.forEach { category ->
                            FilterChip(
                                selected = category in categories,
                                onClick = {
                                    categories = if (category in categories) {
                                        categories - category
                                    } else {
                                        categories + category
                                    }
                                    error = null
                                },
                                label = { Text(category.title) }
                            )
                        }
                    }
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                warnings.forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                probeResult?.let { result ->
                    Text(
                        text = "${if (result.ok) "✓" else "✗"} ${result.message} (${result.latencyMs} мс)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (categories.isEmpty()) {
                    error = "Выберите хотя бы один раздел"
                    return@TextButton
                }
                when (val check = validateCustomSource(
                    name = name,
                    urlTemplate = template,
                    existing = customs,
                    selfId = existing?.id,
                    builtInNames = PlaybackSources.ALL.map { it.displayName },
                    categories = categories,
                    webOnly = webOnly,
                    kind = kind,
                    endpoint = endpoint
                )) {
                    is CustomSourceCheck.Failed -> error = check.message
                    is CustomSourceCheck.Ok -> {
                        warnings = check.warnings
                        if (check.warnings.isEmpty()) {
                            val id = existing?.id
                                ?: buildCustomId(name, customs.map { it.id }.toSet())
                            onSave(draft(id))
                        }
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        val id = existing?.id ?: "CUSTOM_DRAFT"
                        scope.launch {
                            probing = true
                            probeResult = withContext(Dispatchers.IO) {
                                SourceHealthChecker.checkCustomSource(draft(id))
                            }
                            probing = false
                        }
                    },
                    enabled = !probing
                ) { Text(if (probing) "Проверка…" else "Проверить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

/**
 * Иконка по умолчанию для слота [SourcesSettingsScreen]: буквенный аватар.
 * Платформы перекрывают её реальными картинками.
 */
@Composable
private fun DefaultSourceAvatar(info: PlaybackSourceInfo) {
    Text(
        text = info.displayName.take(1).uppercase(),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}
