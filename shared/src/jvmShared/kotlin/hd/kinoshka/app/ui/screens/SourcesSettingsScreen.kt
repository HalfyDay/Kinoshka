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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.source.CustomSource
import hd.kinoshka.app.data.source.CustomSourceCheck
import hd.kinoshka.app.data.source.CustomSourceKind
import hd.kinoshka.app.data.source.JsPluginResolver
import hd.kinoshka.app.data.source.JsPluginStore
import hd.kinoshka.app.data.source.JsSandbox
import hd.kinoshka.app.data.source.PluginCatalog
import hd.kinoshka.app.data.source.PlaybackSourceInfo
import hd.kinoshka.app.data.source.PlaybackSources
import hd.kinoshka.app.data.source.SourceCategory
import hd.kinoshka.app.data.source.SourceHealth
import hd.kinoshka.app.data.source.SourceHealthChecker
import hd.kinoshka.app.data.source.buildCustomId
import hd.kinoshka.app.data.source.validateCustomSource
import hd.kinoshka.app.ui.platform.rememberKinoPlatformActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Инструкция для разработчиков JS-плагинов (контракт manifest/resolve). */
private const val JS_PLUGIN_DEV_DOCS_URL =
    "https://github.com/HalfyDay/Kinoshka/blob/main/docs/js-plugins/README.md"
/** Репозиторий-витрина JS-плагинов. */
private const val JS_PLUGIN_REPO_URL = "https://github.com/HalfyDay/kinoshka-plugins"
/** Telegram-чат: сюда присылать свои расширения на модерацию для каталога. */
private const val PLUGIN_MODERATION_CHAT_URL = "https://t.me/+uAYH589yppczMjIy"

/**
 * Отдельная страница управления источниками (из «Настроек»): три раздела
 * с горизонтальным перелистыванием — «Встроенные» (с подразделами Все /
 * Аниме / 18+), «Свои» (компактный список кастомных) и «Каталог» (витрина
 * JS-плагинов). У встроенных — выключатель на источник в каждом его разделе
 * (выключен — значит не запрашивается и не показывается в этом разделе)
 * и проверка работоспособности каждого и всех сразу. Шапка и пилюли разделов
 * закреплены над списком. Общая для Android и desktop.
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
    // JS-плагины (вариант C): сохранение с кодом; null — вид доступен только
    // для просмотра/проверки (сохранить нельзя, диалог честно скажет).
    onSavePluginSource: ((CustomSource, String) -> Unit)? = null,
    // Порядок своих (шевроны в строках): null прячет шевроны (старые коллеры).
    onMoveCustomSource: ((String, Int) -> Unit)? = null,
    // Обмен файлом JSON между устройствами (платформа открывает диалог
    // сохранения/выбора и дергает стор): null прячет свою кнопку.
    onExportCustomSourcesFile: (() -> Unit)? = null,
    onImportCustomSourcesFile: (() -> Unit)? = null,
    // Статус последнего обмена для платформ без тостов (desktop); Android — null.
    fileExchangeMessage: String? = null,
    // Каталог JS-плагинов (витрина index.json): null-колбэк обновления прячет секцию.
    catalogEntries: List<hd.kinoshka.app.data.source.PluginCatalog.Entry> = emptyList(),
    catalogFresh: Boolean = true,
    catalogLoading: Boolean = false,
    catalogBusyId: String? = null,
    catalogMessage: String? = null,
    appVersion: String = "",
    catalogUrl: String = "",
    onRefreshCatalog: (() -> Unit)? = null,
    onInstallCatalogEntry: ((hd.kinoshka.app.data.source.PluginCatalog.Entry) -> Unit)? = null,
    onCatalogUrlChanged: ((String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var sourcesHealth by remember { mutableStateOf<Map<String, SourceHealth>>(emptyMap()) }
    var checkingAll by remember { mutableStateOf(false) }
    var checkingOne by remember { mutableStateOf<String?>(null) }
    var editingCustom by remember { mutableStateOf<CustomSource?>(null) }
    var addingCustom by remember { mutableStateOf(false) }
    var deletingCustom by remember { mutableStateOf<CustomSource?>(null) }
    var updatingOne by remember { mutableStateOf<String?>(null) }
    // Диалог настроек каталога (свой URL витрины).
    var showCatalogSettings by remember { mutableStateOf(false) }
    val platformActions = rememberKinoPlatformActions()

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

    fun updatePlugin(custom: CustomSource) {
        if (checkingOne != null || checkingAll || updatingOne != null) return
        if (custom.kind != CustomSourceKind.PLUGIN || onSavePluginSource == null) return
        updatingOne = custom.id
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                JsPluginStore.installDraft(custom.endpoint)
            }
            if (outcome == null) {
                sourcesHealth = sourcesHealth + (custom.id to SourceHealth(
                    id = custom.id, ok = false, latencyMs = 0,
                    message = "Обновление не удалось: код не загрузился"
                ))
            } else {
                val old = custom.pluginVersion
                val new = outcome.manifest.version
                onSavePluginSource(
                    custom.copy(pluginVersion = new, name = custom.name.ifBlank { outcome.manifest.name }),
                    outcome.code
                )
                val msg = if (old.isBlank() || old == new) "Код обновлён: v$new"
                else "Обновлено: v$old → v$new"
                sourcesHealth = sourcesHealth + (custom.id to SourceHealth(
                    id = custom.id, ok = true, latencyMs = 0, message = msg
                ))
            }
            updatingOne = null
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

    // Три страницы с горизонтальным перелистыванием: встроенные (с подразделами
    // Все/Аниме/18+), свои и каталог. Свои живут отдельно от разрезов категорий,
    // чтобы не дублироваться и там, и там.
    val pagerState = rememberPagerState(pageCount = { 3 })
    // Скролл каждой страницы свой (пейджер выкидывает страницы из композиции),
    // состояния нужны и для градиентного перехода под шапкой (topFadingEdge).
    val builtInListState = rememberLazyListState()
    val customListState = rememberLazyListState()
    val catalogListState = rememberLazyListState()
    // Подраздел встроенных: null = Все (группировка по разделам), иначе плоский
    // список источников раздела.
    var builtInFilter by remember { mutableStateOf<SourceCategory?>(null) }
    val builtIns = remember { PlaybackSources.ALL }
    // Выключатель раздельный по разделам: голый id гасит везде, scoped — свой раздел.
    fun isEnabled(id: String, category: SourceCategory?): Boolean {
        val key = id.trim().uppercase()
        if (key in disabledKeys) return false
        if (category != null && "${category.name}:$key" in disabledKeys) return false
        return true
    }
    val checkedCount = sourcesHealth.size
    val okCount = sourcesHealth.values.count { it.ok }

    // Бейдж каталога: число установленных плагинов с доступным обновлением.
    val catalogUpdates = remember(catalogEntries, customSources) {
        catalogEntries.count { entry ->
            val installed = customSources.firstOrNull { PluginCatalog.matchesEntry(it, entry) }
            installed != null && installed.pluginVersion != entry.version
        }
    }
    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    // Закреплена только шапка-пилюля (парит без подложки).
    // Заголовки разделов — фиксированным рядом почти вплотную к пилюле
    // (как вкладки библиотеки): со свайпом страниц не перелистываются.
    // Сами разделы — HorizontalPager: переключаются и тапом, и свайпом.
    PinnedHeaderPage(
        title = "Источники",
        subtitle = "Наличие зависит от фильма · проба на «Матрице»",
        onBack = onBack,
    ) { topPad ->
        // topPad = высота пилюли + 10dp; пилюля внутри несёт свои 10dp нижнего
        // отступа — итого было 20dp воздуха. Оставляем 4dp: табы ближе к пилюле.
        Column(
            modifier = Modifier.fillMaxSize().padding(top = topPad - 16.dp)
        ) {
            SourcesTabs(
                pagerState = pagerState,
                customLabel = if (customSources.isNotEmpty()) "Свои • ${customSources.size}" else "Свои",
                catalogLabel = if (catalogUpdates > 0) "Каталог • $catalogUpdates" else "Каталог",
                onSelect = ::goToPage
            )
        HorizontalPager(
            state = pagerState,
            // Зазор между страницами, как в библиотеке: соседние страницы
            // визуально не слипаются в шве посередине свайпа.
            pageSpacing = 10.dp,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> LazyColumn(
                    state = builtInListState,
                    modifier = Modifier.fillMaxSize().topFadingEdge(builtInListState),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)
                ) {
                    // Подразделы встроенных: Все (группировка по разделам) /
                    // Аниме / 18+. Уезжают вверх вместе со списком.
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            item {
                                SourceFilterPill(
                                    label = "Все",
                                    selected = builtInFilter == null,
                                    onClick = { builtInFilter = null }
                                )
                            }
                            item {
                                SourceFilterPill(
                                    label = SourceCategory.ANIME.title,
                                    selected = builtInFilter == SourceCategory.ANIME,
                                    onClick = { builtInFilter = SourceCategory.ANIME }
                                )
                            }
                            item {
                                SourceFilterPill(
                                    label = SourceCategory.ADULT.title,
                                    selected = builtInFilter == SourceCategory.ADULT,
                                    onClick = { builtInFilter = SourceCategory.ADULT }
                                )
                            }
                        }
                    }
            item {
                FilledTonalButton(
                    onClick = ::checkAll,
                    enabled = !checkingAll,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    if (checkingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (checkingAll) "Проверка…" else "Проверить все источники")
                }
                if (checkedCount > 0) {
                    Text(
                        text = "Проверено $checkedCount из ${PlaybackSources.allInfos().size} · работают: $okCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }
            // Встроенные по подразделам: Все — группировка по разделам,
            // Аниме/18+ — плоские списки подраздела.
            if (builtInFilter == null) {
                SourceCategory.entries.forEach { category ->
                    val inCategory = builtIns.filter { category in it.categories }
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
            } else {
                val filtered = builtIns.filter { builtInFilter in it.categories }
                items(filtered, key = { it.id }) { info ->
                    SourcePageRow(
                        info = info,
                        // Вне разделов (подфильтр): горит, когда включён везде;
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
                } // конец страницы «Встроенные».
                1 -> LazyColumn(
                    state = customListState,
                    modifier = Modifier.fillMaxSize().topFadingEdge(customListState),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)
                ) {
                    // Свои: одна управляющая карточка (добавить + обмен файлом)
                    // и компактный список — без лишних плиток.
                    if (onSaveCustomSource != null) {
                        item {
                            KinoSettingsCard {
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Мои источники" +
                                                    (if (customSources.isNotEmpty()) " • ${customSources.size}" else ""),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Embed, Stremio и JS-плагины",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
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
                                    // Обмен с другим устройством: файл JSON (экспорт своих +
                                    // импорт слиянием: дубликаты пропускаются, коллизии
                                    // переименовываются). Статус — тут же, второй строкой.
                                    if (onExportCustomSourcesFile != null || onImportCustomSourcesFile != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (onExportCustomSourcesFile != null) {
                                                TextButton(
                                                    onClick = onExportCustomSourcesFile,
                                                    enabled = customSources.isNotEmpty()
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Upload,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("Экспорт")
                                                }
                                            }
                                            if (onImportCustomSourcesFile != null) {
                                                TextButton(onClick = onImportCustomSourcesFile) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Download,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("Импорт")
                                                }
                                            }
                                            if (fileExchangeMessage != null) {
                                                Text(
                                                    text = fileExchangeMessage,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                            }
                                        }
                                    } else if (fileExchangeMessage != null) {
                                        Text(
                                            text = fileExchangeMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    // Разработчикам: инструкция по JS-плагинам и репозиторий.
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = { platformActions.openInBrowser(JS_PLUGIN_DEV_DOCS_URL) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Description,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Инструкция")
                                        }
                                        TextButton(
                                            onClick = { platformActions.openInBrowser(JS_PLUGIN_REPO_URL) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Code,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Репозиторий")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (customSources.isEmpty()) {
                        item {
                            Text(
                                text = "Пока пусто — добавьте Embed, Stremio или JS-плагин кнопкой выше.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    itemsIndexed(customSources, key = { _, it -> it.id }) { index, custom ->
                        val info = PlaybackSources.customInfo(custom)
                        CustomSourceCompactRow(
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
                            onDelete = { deletingCustom = custom },
                            onUpdate = if (custom.kind == CustomSourceKind.PLUGIN && onSavePluginSource != null)
                                ({ updatePlugin(custom) }) else null,
                            updating = updatingOne == info.id,
                            onMoveUp = onMoveCustomSource
                                ?.takeIf { index > 0 }
                                ?.let { move -> { move(custom.id, -1) } },
                            onMoveDown = onMoveCustomSource
                                ?.takeIf { index < customSources.lastIndex }
                                ?.let { move -> { move(custom.id, 1) } }
                        )
                    }
            // Обмен файлом (экспорт/импорт) живёт в управляющей карточке выше.
                } // конец страницы «Свои».
                    else -> LazyColumn(
                        state = catalogListState,
                        modifier = Modifier.fillMaxSize().topFadingEdge(catalogListState),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)
                    ) {
            // Каталог JS-плагинов: витрина index.json, установка в один тап
            // (код сверяется с sha256 витрины), обновления подсвечиваются.
            if (onRefreshCatalog != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onRefreshCatalog,
                            enabled = !catalogLoading,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (catalogLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (catalogLoading) "Обновление…" else "Обновить каталог")
                        }
                        if (onCatalogUrlChanged != null) {
                            TextButton(onClick = { showCatalogSettings = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Настройки")
                            }
                        }
                    }
                    Text(
                        text = if (catalogEntries.isEmpty()) {
                            "Проверенные JS-плагины из общей витрины"
                        } else if (catalogFresh) {
                            "Плагинов: ${catalogEntries.size}"
                        } else {
                            "Плагинов: ${catalogEntries.size} · список устарел (офлайн)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    // Разработчикам: инструкция, репозиторий и чат для модерации.
                    Text(
                        text = "Свой плагин — присылайте в Telegram-чат, добавим в каталог после модерации.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { platformActions.openInBrowser(JS_PLUGIN_DEV_DOCS_URL) }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Инструкция")
                        }
                        TextButton(
                            onClick = { platformActions.openInBrowser(JS_PLUGIN_REPO_URL) }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Code,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Репозиторий")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { platformActions.openInBrowser(PLUGIN_MODERATION_CHAT_URL) }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Telegram-чат для модерации")
                        }
                    }
                }
                // Свой URL витрины — в диалоге настроек каталога (кнопка «Настройки» выше).
                if (catalogMessage != null) {
                    item {
                        Text(
                            text = catalogMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                items(catalogEntries, key = { it.id }) { entry ->
                    val installed = customSources.firstOrNull {
                        hd.kinoshka.app.data.source.PluginCatalog.matchesEntry(it, entry)
                    }
                    CatalogEntryRow(
                        entry = entry,
                        installedVersion = installed?.pluginVersion,
                        appVersion = appVersion,
                        busy = catalogBusyId == entry.id,
                        onInstall = { onInstallCatalogEntry?.invoke(entry) }
                    )
                }
            }
                    if (catalogEntries.isEmpty() && !catalogLoading && catalogMessage == null) {
                        item {
                            Text(
                                text = "Витрина пуста — нажмите «Обновить».",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    } // конец страницы «Каталог».
                } // when (page)
            } // HorizontalPager
        } // Column: заголовки разделов + страницы.

    if (addingCustom || editingCustom != null) {
        CustomSourceEditDialog(
            existing = editingCustom,
            customs = customSources,
            onDismiss = { addingCustom = false; editingCustom = null },
            onSave = { src ->
                onSaveCustomSource?.invoke(src)
                addingCustom = false
                editingCustom = null
            },
            onSavePlugin = { src, code ->
                onSavePluginSource?.invoke(src, code)
                addingCustom = false
                editingCustom = null
            }
        )
    }
    if (showCatalogSettings && onCatalogUrlChanged != null) {
        var draft by remember(catalogUrl) { mutableStateOf(catalogUrl) }
        AlertDialog(
            onDismissRequest = { showCatalogSettings = false },
            title = { Text("Настройки каталога") },
            text = {
                Column {
                    Text(
                        text = "Своя витрина: URL index.json в том же формате (пусто — официальная)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("https://…/index.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCatalogUrlChanged(draft.trim())
                    showCatalogSettings = false
                }) { Text("ОК") }
            },
            dismissButton = {
                TextButton(onClick = { showCatalogSettings = false }) { Text("Отмена") }
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
 * Плавный переход от шапки к контенту, как в Библиотеке/Обзоре/Профиле:
 * по мере скролла верхние 96dp списка гаснут в цвет фона страницы.
 * Та же логика, что `topFadingEdge` в HomeScreen (там private — копия здесь).
 * Чтение скролла внутри draw-блока даёт лишь перерисовку, без рекомпозиции.
 */
@Composable
private fun Modifier.topFadingEdge(
    state: LazyListState,
    fadeHeight: Dp = 96.dp
): Modifier {
    // Цвета темы читаем здесь (в DrawScope компоуз-чтения недоступны).
    val bg = MaterialTheme.colorScheme.background
    return drawWithContent {
    drawContent()
    val fadePx = fadeHeight.toPx()
    val offset = (if (state.firstVisibleItemIndex > 0) Int.MAX_VALUE else state.firstVisibleItemScrollOffset)
        .coerceAtLeast(0)
    if (offset > 0 && fadePx > 0f) {
        val t = (offset / fadePx).coerceIn(0f, 1f)
        val strength = t * t * t * (t * (t * 6f - 15f) + 10f)
        if (strength > 0.01f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to bg.copy(alpha = strength),
                    0.6f to bg.copy(alpha = strength * 0.45f),
                    1f to bg.copy(alpha = 0f),
                    startY = 0f,
                    endY = fadePx
                )
            )
        }
    }
    }
}

/**
 * Заголовки разделов в стиле вкладок библиотеки (История, Смотрю…):
 * текстовые табы без пилюль, активный — жирный primary. Тап дублирует свайп пейджера.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourcesTabs(
    pagerState: PagerState,
    customLabel: String,
    catalogLabel: String,
    onSelect: (Int) -> Unit
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = pagerState.currentPage,
        edgePadding = 10.dp,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {},
        modifier = Modifier.fillMaxWidth()
    ) {
        listOf("Встроенные", customLabel, catalogLabel).forEachIndexed { index, title ->
            val isSelected = pagerState.currentPage == index
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) }
                    )
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
 * серый — не проверен/выключен), название, описание и выключатель.
 * Во время проверки иконка остаётся, вокруг неё крутится кольцо прогресса.
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
    // Только свои источники: правка и удаление (+ обновление кода у плагинов).
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onUpdate: (() -> Unit)? = null,
    updating: Boolean = false,
    // Порядок своих: null прячет шеврон (края списка и встроенные).
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
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
            // ободок — статус проверки. Иконка не пропадает: во время проверки
            // вокруг неё крутится кольцо прогресса на месте ободка.
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(2.dp, statusColor, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
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
            if (onMoveUp != null || onMoveDown != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(28.dp)
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { onMoveUp?.invoke() },
                        enabled = onMoveUp != null,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Выше",
                            tint = if (onMoveUp != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    androidx.compose.material3.IconButton(
                        onClick = { onMoveDown?.invoke() },
                        enabled = onMoveDown != null,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Ниже",
                            tint = if (onMoveDown != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (onUpdate != null) {
                if (updating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    androidx.compose.material3.IconButton(
                        onClick = onUpdate,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Обновить код",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
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
 * Строка витрины: имя/автор/версия/разделы + кнопка по состоянию
 * (Взять / Обновить / Стоит / Нужна версия приложения).
 */
@Composable
private fun CatalogEntryRow(
    entry: PluginCatalog.Entry,
    installedVersion: String?,
    appVersion: String,
    busy: Boolean,
    onInstall: () -> Unit
) {
    val appOk = PluginCatalog.isAppVersionOk(entry, appVersion)
    val update = installedVersion != null && installedVersion != entry.version
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.name.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (entry.verified) {
                            Color(0xFF4CAF50).copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                        }
                    ) {
                        Text(
                            if (entry.verified) "Проверен" else "Новичок",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = buildString {
                        if (entry.author.isNotBlank()) append(entry.author).append(" · ")
                        append("v").append(entry.version)
                        append(" · ")
                        append(entry.sections.joinToString(", ") { it.title })
                        if (installedVersion != null) append(" · стоит v").append(installedVersion)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.description.isNotBlank()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                !appOk -> TextButton(onClick = {}, enabled = false) {
                    Text("Нужна v${entry.minAppVersion}")
                }
                installedVersion == entry.version -> TextButton(onClick = {}, enabled = false) {
                    Text("Стоит")
                }
                update -> FilledTonalButton(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("Обновить") }
                else -> FilledTonalButton(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("Взять") }
            }
        }
    }
}

/**
 * Компактная строка своего источника (страница «Свои»): та же плитка, что
 * [SourcePageRow], но ужатая по вертикали — иконка 36dp, одна строка описания,
 * кнопки 32dp, шевроны порядка 24dp. Тап по плитке (мимо выключателя) — проверка.
 */
@Composable
private fun CustomSourceCompactRow(
    info: PlaybackSourceInfo,
    enabled: Boolean,
    health: SourceHealth?,
    checking: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onCheck: () -> Unit,
    sourceIcon: @Composable (PlaybackSourceInfo) -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onUpdate: (() -> Unit)? = null,
    updating: Boolean = false,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    val statusColor = when {
        checking || !enabled || health == null -> MaterialTheme.colorScheme.outline
        health.ok -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        onClick = onCheck,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .border(2.dp, statusColor, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    sourceIcon(info)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (info.needsVpn) {
                        Spacer(modifier = Modifier.width(6.dp))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onMoveUp != null || onMoveDown != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(24.dp)
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { onMoveUp?.invoke() },
                        enabled = onMoveUp != null,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Выше",
                            tint = if (onMoveUp != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    androidx.compose.material3.IconButton(
                        onClick = { onMoveDown?.invoke() },
                        enabled = onMoveDown != null,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Ниже",
                            tint = if (onMoveDown != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (onUpdate != null) {
                if (updating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    androidx.compose.material3.IconButton(
                        onClick = onUpdate,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Обновить код",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (onEdit != null) {
                androidx.compose.material3.IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Изменить",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (onDelete != null) {
                androidx.compose.material3.IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
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
    onSave: (CustomSource) -> Unit,
    onSavePlugin: ((CustomSource, String) -> Unit)? = null
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: CustomSourceKind.EMBED) }
    val isStremio = kind == CustomSourceKind.STREMIO
    val isPlugin = kind == CustomSourceKind.PLUGIN
    val isEmbed = kind == CustomSourceKind.EMBED
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
    // Черновик плагина: скачанный код и manifest (между «Проверить» и «Сохранить»).
    var pluginCode by remember { mutableStateOf<String?>(null) }
    var pluginManifest by remember { mutableStateOf<JsSandbox.JsManifest?>(null) }
    var pluginVersion by remember { mutableStateOf(existing?.pluginVersion.orEmpty()) }
    val scope = rememberCoroutineScope()

    fun draft(id: String) = CustomSource(
        id = id,
        name = name.trim(),
        urlTemplate = if (isEmbed) template.trim() else "",
        referer = if (isEmbed) referer.trim() else "",
        useProxy = useProxy,
        webOnly = webOnly && isEmbed,
        categories = categories,
        kind = kind,
        endpoint = if (isEmbed) "" else endpoint.trim(),
        pluginVersion = if (isPlugin) pluginVersion else ""
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
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState())
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
                } else if (isPlugin) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it; error = null; pluginCode = null },
                        label = { Text("Адрес .js-файла") },
                        placeholder = { Text("https://host/plugin.js") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "JS-плагин исполняется в песочнице. «Проверить» качает код и показывает manifest.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val shownVersion = pluginManifest?.version ?: pluginVersion.takeIf { it.isNotBlank() }
                    if (shownVersion != null) {
                        Text(
                            text = "Версия кода: $shownVersion" +
                                (pluginManifest?.author?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
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
                if (isEmbed) {
                    OutlinedTextField(
                        value = referer,
                        onValueChange = { referer = it },
                        label = { Text("Referer (необязательно)") },
                        placeholder = { Text("origin embed-хоста") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = webOnly, onCheckedChange = { webOnly = it })
                        Text(
                            text = "Только веб-плеер (не извлекать поток)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    }
                    // Прокси доступен всем видам: через него идут запросы
                    // резолверов (manifest/stream/embed/код) и mpv-потоки с хоста.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useProxy, onCheckedChange = { useProxy = it })
                        Text(
                            text = "Через прокси",
                            style = MaterialTheme.typography.bodyMedium
                        )
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
                        // Двухшаговый гейт: первое «Сохранить» показывает варнинги,
                        // второе (тот же набор) — сохраняет. Смена полей сбрасывает гейт.
                        if (check.warnings.isNotEmpty() && warnings != check.warnings) {
                            warnings = check.warnings
                        } else {
                            val id = existing?.id
                                ?: buildCustomId(name, customs.map { it.id }.toSet())
                            if (isPlugin) {
                                if (onSavePlugin == null) {
                                    error = "Сохранение плагинов не поддерживается на этой платформе"
                                    return@TextButton
                                }
                                val code = pluginCode
                                if (code.isNullOrBlank()) {
                                    error = "Сначала нажмите «Проверить» — код ещё не загружен"
                                    return@TextButton
                                }
                                onSavePlugin(draft(id).copy(pluginVersion = pluginVersion), code)
                            } else {
                                onSave(draft(id))
                            }
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
                            if (isPlugin) {
                                val start = System.currentTimeMillis()
                                val outcome = withContext(Dispatchers.IO) {
                                    val downloaded = JsPluginStore.installDraft(endpoint.trim())
                                        ?: return@withContext null
                                    val (ok, message) = JsPluginResolver.probeWithCode(
                                        draft(id), downloaded.code
                                    )
                                    Triple(downloaded, ok, message)
                                }
                                probing = false
                                if (outcome == null) {
                                    probeResult = SourceHealth(
                                        id = id, ok = false,
                                        latencyMs = System.currentTimeMillis() - start,
                                        message = "Код не загрузился или manifest бит"
                                    )
                                } else {
                                    val (downloaded, ok, message) = outcome
                                    pluginCode = downloaded.code
                                    pluginManifest = downloaded.manifest
                                    pluginVersion = downloaded.manifest.version
                                    if (name.isBlank()) name = downloaded.manifest.name
                                    probeResult = SourceHealth(
                                        id = id, ok = ok,
                                        latencyMs = System.currentTimeMillis() - start,
                                        message = message
                                    )
                                }
                            } else {
                                probeResult = withContext(Dispatchers.IO) {
                                    SourceHealthChecker.checkCustomSource(draft(id))
                                }
                                probing = false
                            }
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
