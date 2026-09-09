package hd.kinoshka.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.local.ShikimoriAuthState
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.ui.screens.WatchTimeSummary
import hd.kinoshka.app.ui.screens.calcWatchStreak
import hd.kinoshka.app.ui.screens.calcWatchTime
import hd.kinoshka.app.ui.screens.formatStreak
import hd.kinoshka.app.ui.screens.formatWatchTime
import hd.kinoshka.app.ui.screens.statusSegments
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.ui.tv.tvFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Профиль пользователя на ПК: аватар/аккаунт Shikimori, статистика библиотеки,
 * экспорт/импорт библиотеки и переходы в настройки. OAuth-вход и облачная
 * синхронизация остаются мобильными (WebView), здесь только их статус.
 */
@Composable
fun ProfileScreen(
    avatar: String,
    shikimoriAuthState: ShikimoriAuthState,
    library: List<hd.kinoshka.app.ui.screens.LibraryUiItem>,
    onBack: () -> Unit,
    onExportLibrary: () -> String,
    onImportLibrary: (String) -> Result<Unit>,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun exportLibrary() {
        scope.launch {
            val json = onExportLibrary()
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val chooser = JFileChooser()
                    chooser.dialogTitle = "Экспорт библиотеки"
                    chooser.selectedFile = File("kinoshka-library.json")
                    chooser.fileFilter = FileNameExtensionFilter("JSON", "json")
                    var path: String? = null
                    java.awt.EventQueue.invokeAndWait {
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            path = chooser.selectedFile.absolutePath
                        }
                    }
                    path?.let { p ->
                        val file = if (p.endsWith(".json", true)) File(p) else File("$p.json")
                        file.writeText(json, Charsets.UTF_8)
                        "Экспорт завершён: ${file.name}"
                    } ?: "Экспорт отменён"
                }.getOrElse { "Ошибка экспорта: ${it.message}" }
            }
            statusMessage = outcome
        }
    }

    fun importLibrary() {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val chooser = JFileChooser()
                    chooser.dialogTitle = "Импорт библиотеки"
                    chooser.fileFilter = FileNameExtensionFilter("JSON", "json")
                    var text: String? = null
                    java.awt.EventQueue.invokeAndWait {
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            text = chooser.selectedFile.readText(Charsets.UTF_8)
                        }
                    }
                    text?.let { payload ->
                        onImportLibrary(payload).fold(
                            onSuccess = { "Импорт завершён" },
                            onFailure = { "Ошибка импорта: ${it.message}" }
                        )
                    } ?: "Импорт отменён"
                }.getOrElse { "Ошибка импорта: ${it.message}" }
            }
            statusMessage = outcome
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.size(38.dp).tvFocusable(onClick = onBack, shape = CircleShape, hoverToFocus = true)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = cs.onBackground, modifier = Modifier.size(22.dp))
                    }
                }
                Text("Профиль", color = cs.onBackground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            // Hero-карточка: аватар + аккаунт
            Surface(shape = RoundedCornerShape(28.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    cs.primaryContainer.copy(alpha = 0.7f),
                                    cs.surfaceVariant.copy(alpha = 0.4f),
                                    cs.surface
                                )
                            )
                        )
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(96.dp).clip(CircleShape).background(cs.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (avatar.startsWith("http") || avatar.startsWith("file")) {
                                KinoshkaAsyncImage(model = avatar, contentDescription = "Аватар", modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(Icons.Filled.Person, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(48.dp))
                            }
                        }
                        Text(
                            text = if (shikimoriAuthState.isLoggedIn) (shikimoriAuthState.nickname ?: "Пользователь") else "Пользователь Kinoshka",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = cs.onBackground,
                        )
                        if (shikimoriAuthState.isLoggedIn) {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF4CAF50).copy(alpha = 0.15f)) {
                                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                                    Text("Shikimori синхронизирован", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            Text(
                                "Вход через Shikimori доступен в мобильной версии",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            // Статистика библиотеки: разбивка аниме/фильмы, как на мобиле —
            // тонкая полоса долей без цифр внутри + легенда с цветными точками.
            val watchStreak = remember(library) { library.calcWatchStreak() }
            val watchTime = remember(library) { library.calcWatchTime() }
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Статистика библиотеки", style = MaterialTheme.typography.titleMedium, color = cs.onBackground, modifier = Modifier.weight(1f))
                        if (watchStreak >= 2) {
                            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFEF8E3C).copy(alpha = 0.15f)) {
                                Text(
                                    formatStreak(watchStreak),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF8E3C),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    DesktopLibraryStatusStrip(
                        title = "Список аниме",
                        items = library.filter { it.kinopoiskId >= ANIME_ID_OFFSET }
                    )
                    DesktopLibraryStatusStrip(
                        title = "Список фильмов",
                        items = library.filter { it.kinopoiskId < ANIME_ID_OFFSET }
                    )
                    DesktopWatchTimeBlock(summary = watchTime)
                }
            }
        }
        item {
            // Экспорт/импорт
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Библиотека", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionRow(Icons.Filled.FileDownload, "Экспорт в JSON", Modifier.weight(1f)) { exportLibrary() }
                        ActionRow(Icons.Filled.FileUpload, "Импорт из JSON", Modifier.weight(1f)) { importLibrary() }
                    }
                    statusMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    LinkRow(Icons.Filled.Settings, "Настройки") { onOpenSettings() }
                    HorizontalDivider(color = cs.surfaceContainerHigh.copy(alpha = 0.5f))
                    LinkRow(Icons.Filled.Info, "О приложении") { onOpenAbout() }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopLibraryStatusStrip(
    title: String,
    items: List<hd.kinoshka.app.ui.screens.LibraryUiItem>
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val total = items.size
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(10.dp), color = cs.surfaceContainerHigh.copy(alpha = 0.6f)) {
                Text(
                    text = if (total == 0) "пусто" else "всего $total",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (total == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(cs.surfaceContainerHigh.copy(alpha = 0.6f))
            )
            Text("Пока ничего нет", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            return@Column
        }
        val segments = remember(items) { items.statusSegments() }
        // Полоса — только доли, без цифр внутри: узкие сегменты больше ничего не режут.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(cs.surfaceContainerHigh.copy(alpha = 0.6f))
        ) {
            segments.forEach { segment ->
                Box(
                    modifier = Modifier
                        .weight(segment.count.toFloat() / total)
                        .fillMaxHeight()
                        .background(Color(segment.colorArgb))
                )
            }
        }
        // Легенда с цветными точками: цвет точки = цвет сегмента полосы.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            segments.forEach { segment ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(segment.colorArgb)))
                    Text(segment.label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text("${segment.count}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onBackground)
                }
            }
        }
    }
}

@Composable
private fun DesktopWatchTimeBlock(summary: WatchTimeSummary) {
    if (summary.totalMinutes <= 0) return
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Потрачено на просмотр", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, modifier = Modifier.weight(1f))
        Text("≈ ${formatWatchTime(summary.totalMinutes)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.primary)
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(14.dp)
    Surface(shape = pill, color = cs.surfaceContainerHigh.copy(alpha = 0.6f), modifier = modifier.tvFocusable(onClick = onClick, shape = pill, hoverToFocus = true)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = cs.onBackground, modifier = Modifier.size(18.dp))
            Text(label, color = cs.onBackground, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(14.dp)
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).tvFocusable(onClick = onClick, shape = pill, hoverToFocus = true)) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = cs.onBackground, modifier = Modifier.size(20.dp))
            Text(label, color = cs.onBackground, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }
    }
}
