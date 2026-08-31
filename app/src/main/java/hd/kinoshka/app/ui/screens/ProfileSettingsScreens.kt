
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
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.PlaybackSequenceOption
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.data.diagnostics.AppDiagnostics
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
    onAvatarSelected: (String) -> Unit,
    onExportLibrary: () -> String,
    onImportLibrary: (String) -> Result<Unit>,
    shikimoriAuthState: hd.kinoshka.app.data.local.ShikimoriAuthState = hd.kinoshka.app.data.local.ShikimoriAuthState(),
    onSaveShikimoriToken: (String) -> Unit = {},
    onSaveShikimoriSession: (token: String, userId: Int, nickname: String, avatarUrl: String?) -> Unit = { _, _, _, _ -> },
    onLogoutShikimori: () -> Unit = {},
    isAmoled: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showWebLoginDialog by remember { mutableStateOf(false) }

    // Cloud library backup (Yandex Disk / WebDAV) — reinstall survival for the whole library.
    LaunchedEffect(context) { hd.kinoshka.app.data.cloud.CloudBackupManager.init(context) }
    var showYandexLogin by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var cloudConfig by remember {
        mutableStateOf(hd.kinoshka.app.data.local.CloudSyncStore(context).getConfig())
    }
    val cloudStatus by hd.kinoshka.app.data.cloud.CloudBackupManager.status.collectAsState()

    if (showWebLoginDialog) {
        ShikimoriWebLoginDialog(
            onDismiss = { showWebLoginDialog = false },
            onSuccess = { code, userId, nickname, avatarUrl ->
                // With OAuth2 flow, 'code' is actually the authorization code
                // Call the proper handler to exchange it for tokens
                onSaveShikimoriToken(code)
            }
        )
    }

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

    val activity = remember(library) { buildActivityBars(library) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeaderCard(
                title = "Профиль пользователя",
                subtitle = "Настройки аккаунта, синхронизация и статистика",
                onBack = onBack
            )
        }

        // Hero Profile Header Card
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isAmoled) {
                                SolidColor(MaterialTheme.colorScheme.surface)
                            } else {
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                            }
                        )
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            AvatarPreview(
                                avatar = if (shikimoriAuthState.isLoggedIn && !shikimoriAuthState.avatarUrl.isNullOrBlank()) shikimoriAuthState.avatarUrl else avatar,
                                onClick = { pickAvatar.launch(arrayOf("image/*")) }
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (shikimoriAuthState.isLoggedIn) (shikimoriAuthState.nickname ?: "Пользователь") else "Пользователь Kinoshka",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (shikimoriAuthState.isLoggedIn) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF4CAF50))
                                        )
                                        Text(
                                            text = "Shikimori Синхронизирован",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF4CAF50),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "Нажмите на аватар, чтобы сменить фото",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Stats Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Общая статистика",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.VideoLibrary,
                        label = "Всего",
                        value = "${library.size}"
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Visibility,
                        label = "Смотрю",
                        value = "${library.count { it.status == UserFilmStatus.WATCHING }}",
                        valueColor = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.CheckCircle,
                        label = "Завершено",
                        value = "${library.count { it.status == UserFilmStatus.COMPLETED }}",
                        valueColor = Color(0xFF4CAF50)
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.AutoMirrored.Filled.Notes,
                        label = "С заметками",
                        value = "${library.count { !it.note.isNullOrBlank() }}"
                    )
                }
            }
        }

        // Accounts & backups: Shikimori binding, cloud sync and local file backup in one place
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProfileSectionHeader(
                        icon = Icons.Filled.AccountCircle,
                        title = "Аккаунт Shikimori",
                        action = if (shikimoriAuthState.isLoggedIn) {
                            {
                                OutlinedButton(
                                    onClick = onLogoutShikimori,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Отключить")
                                }
                            }
                        } else null
                    )
                    if (shikimoriAuthState.isLoggedIn) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            KinoshkaAsyncImage(
                                model = shikimoriAuthState.avatarUrl,
                                contentDescription = shikimoriAuthState.nickname,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = shikimoriAuthState.nickname ?: "Пользователь",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Списки и оценки аниме синхронизируются",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Авторизуйтесь через официальный сайт Shikimori для автоматической синхронизации ваших списков просмотров.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { showWebLoginDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Войти через сайт Shikimori", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    HorizontalDivider()

                    CloudBackupSection(
                        config = cloudConfig,
                        status = cloudStatus,
                        onConnectYandex = {
                            if (hd.kinoshka.app.data.cloud.CloudBackupManager.yandexConfigured()) {
                                showYandexLogin = true
                            } else {
                                Toast.makeText(
                                    context,
                                    "Создайте приложение на oauth.yandex.ru (тип «Доступ к API», доступы Яндекс Диска: «информация о Диске» и «папка приложения») и добавьте YANDEX_DISK_CLIENT_ID и YANDEX_DISK_CLIENT_SECRET в local.properties",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onConnectWebDav = { showWebDavDialog = true },
                        onDisconnect = {
                            hd.kinoshka.app.data.cloud.CloudBackupManager.disconnect(context)
                            cloudConfig = hd.kinoshka.app.data.local.CloudSyncStore(context).getConfig()
                        },
                        onUpload = { hd.kinoshka.app.data.cloud.CloudBackupManager.uploadBackup(context) },
                        onRestore = { showRestoreConfirm = true },
                        onAutoSyncChanged = { enabled ->
                            hd.kinoshka.app.data.cloud.CloudBackupManager.setAutoSync(context, enabled)
                            cloudConfig = hd.kinoshka.app.data.local.CloudSyncStore(context).getConfig()
                        }
                    )

                    HorizontalDivider()

                    ProfileSectionHeader(
                        icon = Icons.Filled.Backup,
                        title = "Резервная копия в файл"
                    )
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Экспорт библиотеки")
                    }
                    OutlinedButton(
                        onClick = { openImportFile.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Импорт библиотеки")
                    }
                }
            }
        }

        // Activity Chart Section
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Активность за 14 дней",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Нажмите на столбец, чтобы увидеть количество просмотров",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ActivityBars(activity)
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

    if (showYandexLogin) {
        OAuthWebLoginDialog(
            title = "Вход через Яндекс ID",
            authorizeUrl = buildYandexAuthorizeUrl(),
            redirectUri = YANDEX_VERIFICATION_REDIRECT,
            onDismiss = { showYandexLogin = false },
            onCode = { code ->
                showYandexLogin = false
                scope.launch {
                    hd.kinoshka.app.data.cloud.CloudBackupManager.loginYandex(context, code)
                        .onSuccess { Toast.makeText(context, "Яндекс Диск подключен", Toast.LENGTH_LONG).show() }
                        .onFailure {
                            Toast.makeText(context, "Ошибка входа: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    cloudConfig = hd.kinoshka.app.data.local.CloudSyncStore(context).getConfig()
                }
            }
        )
    }

    if (showWebDavDialog) {
        WebDavConfigDialog(
            onDismiss = { showWebDavDialog = false },
            onSave = { url, user, pass ->
                runCatching { hd.kinoshka.app.data.cloud.CloudBackupManager.saveWebDav(context, url, user, pass) }
                    .onSuccess {
                        showWebDavDialog = false
                        Toast.makeText(context, "WebDAV подключен", Toast.LENGTH_LONG).show()
                    }
                    .onFailure {
                        Toast.makeText(context, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                cloudConfig = hd.kinoshka.app.data.local.CloudSyncStore(context).getConfig()
            }
        )
    }

    if (showRestoreConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Восстановить из облака?") },
            text = {
                Text("Локальная библиотека (статусы, прогресс, оценки, история) будет заменена содержимым резервной копии.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        hd.kinoshka.app.data.cloud.CloudBackupManager.restoreFromCloud(context)
                    }
                ) { Text("Восстановить") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Отмена") }
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
    selectedPlaybackSequence: PlaybackSequenceOption,
    onPlaybackSequenceSelected: (PlaybackSequenceOption) -> Unit,
    selectedPlayerMode: hd.kinoshka.app.data.local.PlayerMode,
    onPlayerModeSelected: (hd.kinoshka.app.data.local.PlayerMode) -> Unit,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onHideRussianChanged: (Boolean) -> Unit,
    onDiscoverTileSizeSelected: (FilmTileSize) -> Unit,
    onLibraryTileSizeSelected: (FilmTileSize) -> Unit,
    onShowFpsCounterChanged: (Boolean) -> Unit
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

    if (showPlaybackSequencePicker) {
        SelectBottomSheet(
            title = "Порядок выбора в плеере",
            options = PlaybackSequenceOption.entries.toList(),
            selected = selectedPlaybackSequence,
            optionLabel = { it.toUiLabel() },
            onSelect = onPlaybackSequenceSelected,
            onDismiss = { showPlaybackSequencePicker = false }
        )
    }

    if (showPlayerModePicker) {
        SelectBottomSheet(
            title = "Плеер фильмов",
            options = hd.kinoshka.app.data.local.PlayerMode.entries.toList(),
            selected = selectedPlayerMode,
            optionLabel = { it.displayName },
            onSelect = onPlayerModeSelected,
            onDismiss = { showPlayerModePicker = false }
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
    val reportActivity = LocalContext.current.findActivity()

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
                        text = BuildConfig.VERSION_NAME,
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
                        // presence_online — framework-drawable: painterResource(android.R.*)
                        // не грузит системные XML и падает (тот же класс краша, что бейджи
                        // библиотеки). Материальный кружок с тем же тинтом — эквивалент точки.
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
                    AboutLinkRow(
                        badge = "!",
                        title = "Собрать отчёт о проблеме",
                        subtitle = "Устройство, события плеера и логи — отправить в любое приложение",
                        onClick = { reportActivity?.let { AppDiagnostics.shareReport(it) } }
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

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color? = null
) {
    ElevatedCard(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = valueColor ?: Color.Unspecified
            )
        }
    }
}

@Composable
private fun ProfileSectionHeader(
    icon: ImageVector,
    title: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        action?.invoke()
    }
}

@Composable
private fun AvatarPreview(
    avatar: String,
    onClick: () -> Unit
) {
    val hasCustomAvatar = avatar.isCustomAvatarUri()

    // Badge lives outside the circular Surface: inside it the circle clip cuts the "+" in half.
    Box(contentAlignment = Alignment.BottomEnd) {
        Surface(
            modifier = Modifier.size(92.dp).clickable(onClick = onClick),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (hasCustomAvatar) {
                    KinoshkaAsyncImage(
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
            }
        }

        if (!hasCustomAvatar) {
            Surface(
                modifier = Modifier.size(28.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShikimoriWebLoginDialog(
    onDismiss: () -> Unit,
    onSuccess: (token: String, userId: Int, nickname: String, avatarUrl: String?) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    val clientId = hd.kinoshka.app.BuildConfig.SHIKIMORI_CLIENT_ID
    val oauthUrl = "https://shikimori.io/oauth/authorize?client_id=$clientId&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope="

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Вход через Shikimori (OAuth2)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            val webView = WebView(ctx)
                            webView.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webView.settings.javaScriptEnabled = true
                            webView.settings.domStorageEnabled = true
                            webView.settings.userAgentString = "KinoshkaApp"

                            webView.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    // With OOB redirect, the code is displayed as text on the page
                                    // Try to extract it using JavaScript
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var body = document.body.innerText;
                                            var match = body.match(/Код авторизации[:\s]*([a-zA-Z0-9_-]+)/i) || body.match(/Authorization code[:\s]*([a-zA-Z0-9_-]+)/i);
                                            if (match && match[1]) {
                                                console.log('AUTH_CODE:' + match[1]);
                                            }
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    // Check if the URL contains the authorization code
                                    if (url.contains("code=")) {
                                        val code = url.substringAfter("code=").substringBefore("&")
                                        if (code.isNotBlank()) {
                                            onSuccess(code, 0, "", null)
                                            onDismiss()
                                            return true
                                        }
                                    }
                                    return false
                                }
                            }
                            webView.webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    val msg = consoleMessage?.message() ?: ""
                                    if (msg.startsWith("AUTH_CODE:")) {
                                        val code = msg.removePrefix("AUTH_CODE:").trim()
                                        if (code.isNotBlank()) {
                                            onSuccess(code, 0, "", null)
                                            onDismiss()
                                        }
                                    }
                                    return super.onConsoleMessage(consoleMessage)
                                }
                            }
                            webView.loadUrl(oauthUrl)
                            webView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

// Yandex's built-in "confirmation code" redirect (always registered, can't be removed):
// after consent the webview lands on this page carrying ?code=..., which the login dialog
// captures — no custom redirect URI needs to be registered in the OAuth console.
private const val YANDEX_VERIFICATION_REDIRECT = "https://oauth.yandex.ru/verification_code"

private fun buildYandexAuthorizeUrl(): String {
    // No redirect_uri: Yandex serves the code on its verification page (OOB-style flow).
    return "https://oauth.yandex.ru/authorize?response_type=code" +
        "&client_id=${hd.kinoshka.app.BuildConfig.YANDEX_DISK_CLIENT_ID}"
}

@Composable
private fun CloudBackupSection(
    config: hd.kinoshka.app.data.local.CloudSyncConfig,
    status: hd.kinoshka.app.data.cloud.CloudBackupManager.SyncStatus,
    onConnectYandex: () -> Unit,
    onConnectWebDav: () -> Unit,
    onDisconnect: () -> Unit,
    onUpload: () -> Unit,
    onRestore: () -> Unit,
    onAutoSyncChanged: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileSectionHeader(
            icon = Icons.Filled.Cloud,
            title = "Резервная копия в облаке",
            action = if (config.isConnected) {
                {
                    OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(12.dp)) {
                        Text("Отключить")
                    }
                }
            } else null
        )
        Text(
            text = "Вся библиотека (статусы, просмотренные эпизоды, оценки, история) выгружается одним файлом. После переустановки приложения прогресс восстанавливается из облака.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when {
            config.type == hd.kinoshka.app.data.local.CloudSyncType.YANDEX -> {
                Text(
                    text = "Хранилище: Яндекс Диск (папка приложения Kinoshka)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            config.type == hd.kinoshka.app.data.local.CloudSyncType.WEBDAV -> {
                Text(
                    text = "Хранилище: WebDAV — ${config.webDavUrl.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                // Same one-tap UX as the Shikimori login: the button is always visible,
                // the OAuth webview does the rest (login, permissions, code capture).
                Button(
                    onClick = onConnectYandex,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Подключить Яндекс Диск", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onConnectWebDav,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Подключить WebDAV")
                }
            }
        }

        if (config.isConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onUpload,
                    enabled = !status.busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Выгрузить")
                }
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !status.busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Восстановить")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Автосохранение после просмотра",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(checked = config.autoSync, onCheckedChange = onAutoSyncChanged)
            }
        }

        if (status.busy) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val lastDate = if (status.lastSyncAt > 0) {
            java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(status.lastSyncAt))
        } else null
        val statusLine = buildString {
            lastDate?.let { append("Последняя синхронизация: $it") }
            status.lastResult?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(" • ")
                append(it)
            }
        }
        if (statusLine.isNotBlank()) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!status.message.isNullOrBlank()) {
            Text(
                text = status.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.message.orEmpty().startsWith("Ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WebDavConfigDialog(
    onDismiss: () -> Unit,
    onSave: (url: String, user: String, password: String) -> Unit
) {
    var url by remember { mutableStateOf("https://") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подключение WebDAV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Файл копии будет сохранён как <адрес>/Kinoshka/library_backup.json",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Адрес сервера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Логин") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url, user, password) },
                enabled = url.trim().length > "https://".length && user.isNotBlank() && password.isNotBlank()
            ) { Text("Подключить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OAuthWebLoginDialog(
    title: String,
    authorizeUrl: String,
    redirectUri: String,
    onDismiss: () -> Unit,
    onCode: (String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            val webView = WebView(ctx)
                            webView.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webView.settings.javaScriptEnabled = true
                            webView.settings.domStorageEnabled = true
                            var codeHandled = false

                            fun deliverCode(code: String?): Boolean {
                                if (codeHandled || code.isNullOrBlank()) return false
                                codeHandled = true
                                onCode(code)
                                return true
                            }

                            webView.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (url?.startsWith(redirectUri) != true) return
                                    // Primary path: ?code=... in the URL. Fallback: some providers
                                    // (Yandex verification_code) render the code only in the page
                                    // body — scrape it like the Shikimori dialog does.
                                    val fromUrl = Uri.parse(url).getQueryParameter("code")
                                    if (deliverCode(fromUrl)) return
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var m = window.location.search.match(/code=([a-zA-Z0-9_-]+)/);
                                            if (m && m[1]) return m[1];
                                            var text = (document.body && document.body.innerText) || '';
                                            var t = text.match(/(?:код подтверждения|verification code|confirmation code)[^a-zA-Z0-9]*([a-zA-Z0-9_-]{4,})/i);
                                            return t ? t[1] : '';
                                        })();
                                        """.trimIndent()
                                    ) { result ->
                                        val scraped = result?.trim()?.removeSurrounding("\"")
                                        deliverCode(scraped?.ifBlank { null })
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.startsWith(redirectUri)) {
                                        val code = Uri.parse(url).getQueryParameter("code")
                                        if (deliverCode(code)) return true
                                    }
                                    return false
                                }
                            }
                            webView.loadUrl(authorizeUrl)
                            webView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}
