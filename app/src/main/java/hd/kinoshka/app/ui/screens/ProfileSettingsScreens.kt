
package hd.kinoshka.app.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.R
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(
    avatar: String,
    library: List<LibraryUiItem>,
    onBack: () -> Unit,
    onAvatarSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val pickAvatar = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadBitmapFromUri(context, uri)
            }
            if (bitmap == null) {
                Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_LONG).show()
            } else {
                cropSourceBitmap = bitmap
            }
        }
    }

    val activity = remember(library) { buildActivityBars(library) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeaderCard(
                title = "Профиль",
                subtitle = "Локальный профиль просмотра",
                onBack = onBack
            )
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Аватар",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    AvatarPreview(
                        avatar = avatar,
                        onClick = { pickAvatar.launch(arrayOf("image/*")) }
                    )
                    Text(
                        text = "Нажмите на аватар, чтобы выбрать изображение",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Активность за 14 дней",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Нажмите на столбец, чтобы увидеть точное количество просмотров",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ActivityBars(activity)
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Статистика", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Всего в библиотеке: ${library.size}")
                    Text("Со статусом: ${library.count { it.status != null }}")
                    Text("С заметками: ${library.count { !it.note.isNullOrBlank() }}")
                }
            }
        }
    }

    cropSourceBitmap?.let { source ->
        AvatarCropDialog(
            sourceBitmap = source,
            onDismiss = { cropSourceBitmap = null },
            onCropped = { cropped ->
                scope.launch {
                    val savedUri = withContext(Dispatchers.IO) { saveAvatarBitmap(context, cropped) }
                    onAvatarSelected(savedUri.toString())
                    cropSourceBitmap = null
                }
            }
        )
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    selectedThemeMode: AppThemeMode,
    hideRussianContent: Boolean,
    selectedDiscoverTileSize: FilmTileSize,
    selectedLibraryTileSize: FilmTileSize,
    selectedShowFpsCounter: Boolean,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onHideRussianChanged: (Boolean) -> Unit,
    onDiscoverTileSizeSelected: (FilmTileSize) -> Unit,
    onLibraryTileSizeSelected: (FilmTileSize) -> Unit,
    onShowFpsCounterChanged: (Boolean) -> Unit,
    onExportLibrary: () -> String,
    onImportLibrary: (String) -> Result<Unit>
) {
    val context = LocalContext.current
    var showThemePicker by remember { mutableStateOf(false) }
    var showDiscoverTileSizePicker by remember { mutableStateOf(false) }
    var showLibraryTileSizePicker by remember { mutableStateOf(false) }
    val createExportFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = onExportLibrary()
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Не удалось открыть файл для записи")
        }
            .onSuccess { Toast.makeText(context, "Экспорт завершен", Toast.LENGTH_SHORT).show() }
            .onFailure { ex -> Toast.makeText(context, "Ошибка экспорта: ${ex.message}", Toast.LENGTH_LONG).show() }
    }

    val openImportFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Не удалось прочитать файл")
            onImportLibrary(text).getOrThrow()
        }
            .onSuccess { Toast.makeText(context, "Импорт завершен", Toast.LENGTH_SHORT).show() }
            .onFailure { ex -> Toast.makeText(context, "Ошибка импорта: ${ex.message}", Toast.LENGTH_LONG).show() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeaderCard(
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
                        value = selectedThemeMode.toUiLabel(),
                        onClick = { showThemePicker = true }
                    )
                    SettingsSelectRow(
                        title = "Размер плиток (Обзор)",
                        value = selectedDiscoverTileSize.toUiLabel(),
                        onClick = { showDiscoverTileSizePicker = true }
                    )
                    SettingsSelectRow(
                        title = "Размер плиток (Библиотека)",
                        value = selectedLibraryTileSize.toUiLabel(),
                        onClick = { showLibraryTileSizePicker = true }
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

                    if (BuildConfig.DEBUG) {
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
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Резервная копия", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Экспорт сохраняет историю, статусы, оценки, заметки, прогресс, аватар и настройки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val fileName = "kinoshka-library-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"
                            createExportFile.launch(fileName)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Экспорт библиотеки")
                    }
                    OutlinedButton(
                        onClick = { openImportFile.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Импорт библиотеки")
                    }
                }
            }
        }
    }

    if (showThemePicker) {
        SelectBottomSheet(
            title = "Тема",
            options = listOf(AppThemeMode.CURRENT, AppThemeMode.DARK, AppThemeMode.AMOLED),
            selected = selectedThemeMode,
            optionLabel = { it.toUiLabel() },
            onSelect = onThemeModeSelected,
            onDismiss = { showThemePicker = false }
        )
    }

    if (showDiscoverTileSizePicker) {
        SelectBottomSheet(
            title = "Размер плиток (Обзор)",
            options = FilmTileSize.entries.toList(),
            selected = selectedDiscoverTileSize,
            optionLabel = { it.toUiLabel() },
            onSelect = onDiscoverTileSizeSelected,
            onDismiss = { showDiscoverTileSizePicker = false }
        )
    }

    if (showLibraryTileSizePicker) {
        SelectBottomSheet(
            title = "Размер плиток (Библиотека)",
            options = FilmTileSize.entries.toList(),
            selected = selectedLibraryTileSize,
            optionLabel = { it.toUiLabel() },
            onSelect = onLibraryTileSizeSelected,
            onDismiss = { showLibraryTileSizePicker = false }
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun <T> SelectBottomSheet(
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
        KeepBottomSheetNavigationBarFromActivity()
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
private fun KeepBottomSheetNavigationBarFromActivity() {
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val activityWindow = view.context.findActivity()?.window
        if (dialogWindow == null || activityWindow == null) {
            onDispose { }
        } else {
            val oldNavColor = dialogWindow.navigationBarColor
            val oldLightNav =
                WindowCompat.getInsetsController(dialogWindow, view).isAppearanceLightNavigationBars
            val oldContrastEnforced =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced
                } else {
                    false
                }

            val activityController =
                WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
            val dialogController = WindowCompat.getInsetsController(dialogWindow, view)
            dialogWindow.navigationBarColor = activityWindow.navigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialogWindow.isNavigationBarContrastEnforced =
                    activityWindow.isNavigationBarContrastEnforced
            }
            dialogController.isAppearanceLightNavigationBars =
                activityController.isAppearanceLightNavigationBars

            onDispose {
                dialogWindow.navigationBarColor = oldNavColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced = oldContrastEnforced
                }
                dialogController.isAppearanceLightNavigationBars = oldLightNav
            }
        }
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
    onOpenShikimori: () -> Unit
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
            HeaderCard(
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
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Иконка приложения",
                        modifier = Modifier.size(90.dp)
                    )
                    Text(
                        text = "Киношка",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = BuildConfig.APPLICATION_ID,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            painter = painterResource(id = android.R.drawable.presence_online),
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
                }
            }
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

private fun AppThemeMode.toUiLabel(): String {
    return when (this) {
        AppThemeMode.CURRENT -> "Системная"
        AppThemeMode.DARK -> "Темная"
        AppThemeMode.AMOLED -> "AMOLED"
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? {
    return when (this) {
        is android.app.Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun FilmTileSize.toUiLabel(): String {
    return when (this) {
        FilmTileSize.COMPACT -> "4 в ряд"
        FilmTileSize.MEDIUM -> "3 в ряд"
        FilmTileSize.LARGE -> "2 в ряд"
        FilmTileSize.VERTICAL -> "Вертикальные"
    }
}

@Composable
private fun HeaderCard(
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
                modifier = Modifier.size(36.dp).clickable(onClick = onBack),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center) { Text("←", style = MaterialTheme.typography.titleMedium) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AvatarPreview(
    avatar: String,
    onClick: () -> Unit
) {
    val hasCustomAvatar = avatar.isCustomAvatarUri()

    Surface(
        modifier = Modifier.size(92.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (hasCustomAvatar) {
                AsyncImage(
                    model = avatar,
                    contentDescription = "Аватар",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Выбрать аватар",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(46.dp)
                )
            }

            if (!hasCustomAvatar) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Добавить",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun AvatarCropDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onCropped: (Bitmap) -> Unit
) {
    var zoom by remember(sourceBitmap) { mutableStateOf(1f) }
    var offset by remember(sourceBitmap) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val imageBitmap = remember(sourceBitmap) { sourceBitmap.asImageBitmap() }
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)),
            color = Color.Transparent
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Кадрирование аватарки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "Масштабируйте и перемещайте изображение внутри круга",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .onSizeChanged { size ->
                            viewportSize = size
                            offset = clampCropOffset(offset, zoom, viewportSize, sourceBitmap)
                        }
                        .pointerInput(sourceBitmap, viewportSize) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                val newOffset = clampCropOffset(
                                    offset = offset + pan,
                                    zoom = newZoom,
                                    viewportSize = viewportSize,
                                    sourceBitmap = sourceBitmap
                                )
                                zoom = newZoom
                                offset = newOffset
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val baseScale = calculateBaseScale(viewportSize, sourceBitmap)
                    val baseWidthDp = with(density) { (sourceBitmap.width * baseScale).toDp() }
                    val baseHeightDp = with(density) { (sourceBitmap.height * baseScale).toDp() }

                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Исходная аватарка",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(baseWidthDp, baseHeightDp)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    ) {
                        drawRect(Color.Black.copy(alpha = 0.45f))
                        val radius = size.minDimension / 2f
                        drawCircle(
                            color = Color.Transparent,
                            radius = radius,
                            center = center,
                            blendMode = BlendMode.Clear
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.92f),
                            radius = radius,
                            center = center,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Отмена") }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = {
                            if (viewportSize.width > 0 && viewportSize.height > 0) {
                                onCropped(
                                    cropAvatarCircle(
                                        sourceBitmap = sourceBitmap,
                                        viewportSize = viewportSize,
                                        zoom = zoom,
                                        offset = offset
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Применить")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityBars(values: List<Pair<String, Int>>) {
    if (values.isEmpty()) return

    var selectedIndex by remember(values) { mutableIntStateOf(values.lastIndex) }
    val max = values.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

    Row(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { index, (_, value) ->
            val ratio = value.toFloat() / max.toFloat()
            val selected = index == selectedIndex
            Column(
                modifier = Modifier.weight(1f).clickable { selectedIndex = index },
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(ratio.coerceIn(0.06f, 1f))
                        .background(
                            when {
                                selected -> MaterialTheme.colorScheme.secondary
                                value == 0 -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> MaterialTheme.colorScheme.primary
                            },
                            RoundedCornerShape(8.dp)
                        )
                )
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        values.take(1).forEach { (day, _) ->
            Text(day, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        values.drop(6).take(1).forEach { (day, _) ->
            Text(day, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        values.takeLast(1).forEach { (day, _) ->
            Text(day, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val selected = values[selectedIndex]
    Text(
        text = "${selected.first}: ${selected.second} просмотров",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}
private fun buildActivityBars(library: List<LibraryUiItem>): List<Pair<String, Int>> {
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

    val labelFormat = SimpleDateFormat("dd.MM", Locale("ru"))
    return days.map { day ->
        labelFormat.format(Date(day)) to (counts[day] ?: 0)
    }
}

private fun String.isCustomAvatarUri(): Boolean {
    return startsWith("content://") || startsWith("file://") || startsWith("http")
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            decoded
        }
    }.getOrNull()
}

private fun calculateBaseScale(viewportSize: IntSize, sourceBitmap: Bitmap): Float {
    if (viewportSize.width == 0 || viewportSize.height == 0) return 1f
    val sw = sourceBitmap.width.toFloat()
    val sh = sourceBitmap.height.toFloat()
    val vw = viewportSize.width.toFloat()
    val vh = viewportSize.height.toFloat()
    return max(vw / sw, vh / sh)
}

private fun clampCropOffset(
    offset: Offset,
    zoom: Float,
    viewportSize: IntSize,
    sourceBitmap: Bitmap
): Offset {
    if (viewportSize.width == 0 || viewportSize.height == 0) return Offset.Zero
    val baseScale = calculateBaseScale(viewportSize, sourceBitmap)
    val drawWidth = sourceBitmap.width * baseScale * zoom
    val drawHeight = sourceBitmap.height * baseScale * zoom
    val maxX = max(0f, (drawWidth - viewportSize.width) / 2f)
    val maxY = max(0f, (drawHeight - viewportSize.height) / 2f)
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}

private fun cropAvatarCircle(
    sourceBitmap: Bitmap,
    viewportSize: IntSize,
    zoom: Float,
    offset: Offset
): Bitmap {
    val outSize = 720
    val viewportW = viewportSize.width.coerceAtLeast(1)
    val viewportH = viewportSize.height.coerceAtLeast(1)
    val baseScale = calculateBaseScale(viewportSize, sourceBitmap)
    val drawWidth = sourceBitmap.width * baseScale * zoom
    val drawHeight = sourceBitmap.height * baseScale * zoom

    // First render exactly what user sees in crop viewport.
    val viewportBitmap = Bitmap.createBitmap(viewportW, viewportH, Bitmap.Config.ARGB_8888)
    val viewportCanvas = AndroidCanvas(viewportBitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    val left = (viewportW - drawWidth) / 2f + offset.x
    val top = (viewportH - drawHeight) / 2f + offset.y
    val dstRect = RectF(left, top, left + drawWidth, top + drawHeight)
    viewportCanvas.drawBitmap(sourceBitmap, null, dstRect, paint)

    val cropSize = min(viewportW, viewportH)
    val cropLeft = ((viewportW - cropSize) / 2f).toInt().coerceAtLeast(0)
    val cropTop = ((viewportH - cropSize) / 2f).toInt().coerceAtLeast(0)
    val srcCropRect = Rect(
        cropLeft,
        cropTop,
        (cropLeft + cropSize).coerceAtMost(viewportW),
        (cropTop + cropSize).coerceAtMost(viewportH)
    )
    val dstSquareRect = RectF(0f, 0f, outSize.toFloat(), outSize.toFloat())

    val square = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
    val squareCanvas = AndroidCanvas(square)
    squareCanvas.drawBitmap(viewportBitmap, srcCropRect, dstSquareRect, paint)
    viewportBitmap.recycle()

    val circle = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
    val circleCanvas = AndroidCanvas(circle)
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    circleCanvas.drawCircle(outSize / 2f, outSize / 2f, outSize / 2f, maskPaint)
    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    circleCanvas.drawBitmap(square, 0f, 0f, maskPaint)

    return circle
}

private fun saveAvatarBitmap(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    val file = File(dir, "avatar_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    }
    return Uri.fromFile(file)
}
