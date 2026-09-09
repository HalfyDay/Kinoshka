
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
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.PlaybackSequenceOption
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.data.download.DownloadPhase
import hd.kinoshka.app.data.download.EpisodeDownloadManager
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
import kotlin.math.roundToInt
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
    anixartAuthState: hd.kinoshka.app.data.local.AnixartAuthState = hd.kinoshka.app.data.local.AnixartAuthState(),
    onLoginAnixart: (String, String, (Boolean, String?) -> Unit) -> Unit = { _, _, _ -> },
    onLogoutAnixart: () -> Unit = {},
    onOpenLibraryStatus: (UserFilmStatus, Boolean) -> Unit = { _, _ -> },
    anixartImportProgress: hd.kinoshka.app.ui.screens.AnixartImportProgress? = null,
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    showBack: Boolean = true,
    sectionBottomPadding: Dp = 0.dp,
    isAmoled: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showWebLoginDialog by remember { mutableStateOf(false) }
    var showAnixartLoginDialog by remember { mutableStateOf(false) }

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

    if (showAnixartLoginDialog) {
        AnixartLoginDialog(
            onDismiss = { showAnixartLoginDialog = false },
            onLogin = { login, password, onResult ->
                onLoginAnixart(login, password) { ok, message ->
                    if (ok) showAnixartLoginDialog = false
                    onResult(ok, message)
                }
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
    val watchStreak = remember(library) { library.calcWatchStreak() }
    val watchTime = remember(library) { library.calcWatchTime() }

    // Поиск по профилю и настройкам (как строка поиска на Обзоре):
    // фильтрует блоки страницы, совпадения в настройках открывают их экран.
    var profileQuery by remember { mutableStateOf("") }
    val isSearching = profileQuery.isNotBlank()
    val showHero = matchesSearchQuery(
        profileQuery,
        "Профиль Аватар",
        "Аватар, имя пользователя, Shikimori",
        "аватар фото профиль имя пользователь шикимори shikimori"
    )
    val showStats = matchesSearchQuery(
        profileQuery,
        "Статистика Активность",
        "Списки аниме и фильмов, активность за 14 дней, время просмотра",
        "статистика список аниме фильмы статусы активность график серия время просмотр потрачено запланировано смотрю пересматриваю просмотрено отложено брошено без статуса"
    )
    val showAccounts = matchesSearchQuery(
        profileQuery,
        "Аккаунт Копии",
        "Shikimori, облако, резервная копия, экспорт, импорт",
        "аккаунт шикимори shikimori синхронизация вход облако яндекс диск webdav копия экспорт импорт файл json библиотека восстановить сохранить"
    )
    val settingsMatches = remember(profileQuery) {
        if (profileQuery.isBlank()) {
            emptyList()
        } else {
            settingsSearchEntries(hasPlayerSettings = true, showDebugSettings = BuildConfig.DEBUG)
                .filter { matchesSearchQuery(profileQuery, it.title, it.subtitle, it.keywords) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp + sectionBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ProfileHeader(
                query = profileQuery,
                onQueryChange = { profileQuery = it },
                showBack = showBack,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
                onOpenDownloads = onOpenDownloads
            )
        }

        if (isSearching && !showHero && !showStats && !showAccounts && settingsMatches.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Ничего не найдено",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "По запросу «$profileQuery» на этой странице ничего нет",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Hero Profile Header Card
        if (showHero) {
            item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                        // Локальная копия: avatarUrl объявлен в другом модуле (shared), smart cast невозможен.
                        // Офлайн-копия: без интернета грузится файл, а не URL. Запасной источник —
                        // profileAvatar, куда логин зеркалит шики-аватар (только http; content:// —
                        // уже пользовательская аватарка, её не подменяем).
                        val shikiAvatar = shikimoriAuthState.avatarUrl
                            ?: avatar.takeIf { it.startsWith("http") }
                        val shikiAvatarModel = rememberShikimoriAvatarModel(shikiAvatar)
                        Box(contentAlignment = Alignment.BottomEnd) {
                            AvatarPreview(
                                avatar = if (shikimoriAuthState.isLoggedIn && shikiAvatarModel is String) shikiAvatarModel else avatar,
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
        }

        // Статистика библиотеки + активность одной карточкой под шапкой профиля
        if (showStats) {
            item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    LibraryStatusStrip(
                        title = "Список аниме",
                        icon = Icons.Filled.SmartToy,
                        items = library.filter { it.kinopoiskId >= ANIME_ID_OFFSET },
                        onStatusClick = { onOpenLibraryStatus(it, true) }
                    )
                    LibraryStatusStrip(
                        title = "Список фильмов",
                        icon = Icons.Filled.Movie,
                        items = library.filter { it.kinopoiskId < ANIME_ID_OFFSET },
                        onStatusClick = { onOpenLibraryStatus(it, false) }
                    )
                    WatchTimeBlock(summary = watchTime)
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Активность за 14 дней",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            // Серия дней подряд: видна, только когда есть чем гордиться.
                            if (watchStreak >= 2) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFEF8E3C).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = formatStreak(watchStreak),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFEF8E3C),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        ActivityBars(activity)
                    }
                }
            }
        }
        }

        // Accounts & backups: Shikimori binding, cloud sync and local file backup in one place
        if (showAccounts) {
            item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProfileSectionHeader(
                        brandIcon = {
                            Image(
                                painter = painterResource(hd.kinoshka.app.R.drawable.ic_src_shikimori),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                            )
                        },
                        title = "Аккаунт Shikimori"
                    )
                    // Только широкая кнопка: кто подключён — видно в шапке профиля выше.
                    if (shikimoriAuthState.isLoggedIn) {
                        OutlinedButton(
                            onClick = onLogoutShikimori,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Выйти из Shikimori")
                        }
                    } else {
                        Button(
                            onClick = { showWebLoginDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Войти через Shikimori", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    HorizontalDivider()

                    ProfileSectionHeader(
                        brandIcon = {
                            Image(
                                painter = painterResource(hd.kinoshka.app.R.drawable.ic_src_anixart),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                            )
                        },
                        title = "Аккаунт Anixart"
                    )
                    // Так же только кнопка; списки синхронизируются, серии — нет (v1).
                    if (anixartAuthState.isLoggedIn) {
                        OutlinedButton(
                            onClick = onLogoutAnixart,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Выйти из Anixart")
                        }
                        // Живой прогресс импорта: catch-up после логина идёт минуты,
                        // пользователь ждёт прямо на этом экране.
                        if (anixartImportProgress != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            hd.kinoshka.app.ui.screens.AnixartImportProgressCard(
                                progress = anixartImportProgress
                            )
                        }
                    } else {
                        Button(
                            onClick = { showAnixartLoginDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Войти в Anixart", fontWeight = FontWeight.SemiBold)
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val fileName = "kinoshka-library-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"
                                createExportFile.launch(fileName)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Экспорт")
                        }
                        OutlinedButton(
                            onClick = { openImportFile.launch(arrayOf("application/json", "text/plain")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Импорт")
                        }
                    }
                }
            }
        }
        }

        // Совпадения в настройках: тап открывает экран настроек.
        if (settingsMatches.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Настройки",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        settingsMatches.forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = onOpenSettings)
                                    .padding(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = entry.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
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
private fun ProfileHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    // Активные загрузки: кнопка крутится, пока что-то качается.
    val downloadTasks by EpisodeDownloadManager.tasks.collectAsState()
    val isDownloading = downloadTasks.values.any {
        it.phase == DownloadPhase.QUEUED ||
            it.phase == DownloadPhase.RESOLVING ||
            it.phase == DownloadPhase.DOWNLOADING
    }
    // Без фона-карточки — как шапки Библиотеки и Обзора: поле + круглые кнопки.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
                Surface(
                    modifier = Modifier.size(48.dp).clickable(onClick = onBack),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            // Строка поиска по профилю и настройкам — как поле поиска на Обзоре.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Поиск",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Поиск",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Очистить",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onQueryChange("") }
                        )
                    }
                }
            }
            // Загрузки — круглая кнопка в стиле фильтров; крутится при активной загрузке.
            Surface(
                modifier = Modifier.size(48.dp).clickable(onClick = onOpenDownloads),
                shape = CircleShape,
                color = if (isDownloading) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (isDownloading) {
                        DownloadingArrowIcon(
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Загрузки",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            // Настройки — круглая кнопка в стиле переключателя Кино/Аниме.
            Surface(
                modifier = Modifier.size(48.dp).clickable(onClick = onOpenSettings),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Настройки",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
}

/**
 * Анимация скачивания кнопки загрузок в шапке профиля — той же иконкой
 * (Icons.Rounded.Download): она съезжает вниз и гаснет, затем цикл заново.
 * Отдельно отрисованная стрелка выглядела слишком худой; вращение всей иконки
 * целиком — сломанно. Это компромисс: родной глиф + классическое движение вниз.
 */
@Composable
private fun DownloadingArrowIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "downloadsArrow")
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "downloadsArrowFraction"
    )
    Icon(
        imageVector = Icons.Rounded.Download,
        contentDescription = "Загрузки",
        tint = tint.copy(alpha = 1f - fraction * 0.45f),
        modifier = modifier.graphicsLayer { translationY = fraction * 7.dp.toPx() }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryStatusStrip(
    title: String,
    icon: ImageVector,
    items: List<LibraryUiItem>,
    onStatusClick: ((UserFilmStatus) -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val total = items.size
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Итог всегда виден: масштаб списков считывается без подсчёта сегментов.
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = if (total == 0) "пусто" else "всего $total",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
            Text(
                text = "Пока ничего нет",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        val segments = remember(items) { items.statusSegments() }
        // Полоса — только доли, без цифр внутри: узкие сегменты больше ничего не режут.
        // Все числа живут в легенде ниже, где для них всегда есть место.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            segments.forEach { segment ->
                val fraction = segment.count.toFloat() / total
                Box(
                    modifier = Modifier
                        .weight(fraction)
                        .fillMaxHeight()
                        .background(Color(segment.colorArgb))
                )
            }
        }
        // Легенда с цветными точками: цвет точки = цвет сегмента полосы.
        // Тап открывает Библиотеку на этом статусе (Кино/Аниме подставляется сама);
        // «Без статуса» никуда не ведёт — такой вкладки в Библиотеке нет.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            segments.forEach { segment ->
                val status = segment.status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (onStatusClick != null && status != null) {
                                Modifier.clickable { onStatusClick(status) }
                            } else Modifier
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(segment.colorArgb))
                    )
                    Text(
                        text = segment.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${segment.count}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Примерное время за просмотрами: эпизоды по средним длительностям,
 * полные метры — поштучно. Точных хронометражей тайтлов нет,
 * поэтому подпись честно говорит «примерно».
 */
@Composable
private fun WatchTimeBlock(summary: WatchTimeSummary) {
    if (summary.totalMinutes <= 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Потрачено на просмотр",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "≈ ${formatWatchTime(summary.totalMinutes)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ProfileSectionHeader(
    icon: ImageVector? = null,
    // Фирменная иконка раздела (логотип Shikimori и т.п.) вместо material-иконки.
    brandIcon: (@Composable () -> Unit)? = null,
    iconTint: Color? = null,
    title: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (brandIcon != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(20.dp)
                ) {
                    brandIcon()
                }
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Длинный заголовок («Резервная копия в облаке») переносится, а не
            // выдавливает кнопку действия за край экрана.
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
    var chartWidthPx by remember { mutableIntStateOf(0) }
    var headerWidthPx by remember { mutableIntStateOf(0) }

    val pickIndex: (Float, Int) -> Unit = { x, width ->
        selectedIndex = (x / width.coerceAtLeast(1) * values.size).toInt().coerceIn(0, values.size - 1)
    }

    // Дата и число просмотров над графиком, прижаты к выбранному столбцу,
    // но не уезжают за края: смещение клампится под измеренную ширину хедера.
    val slotWidthPx = chartWidthPx.toFloat() / values.size
    val headerCenterPx = slotWidthPx * selectedIndex + slotWidthPx / 2f
    val maxHeaderOffsetPx = (chartWidthPx - headerWidthPx).coerceAtLeast(0).toFloat()
    val headerOffsetPx = (headerCenterPx - headerWidthPx / 2f).coerceIn(0f, maxHeaderOffsetPx)
    val selectedValue = values[selectedIndex].second
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val haloColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { chartWidthPx = it.width }
        ) {
            Column(
                modifier = Modifier
                    .onSizeChanged { headerWidthPx = it.width }
                    .offset { IntOffset(headerOffsetPx.roundToInt(), 0) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = values[selectedIndex].first,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        selectedValue == 0 -> "нет просмотров"
                        selectedValue == 1 -> "1 просмотр"
                        selectedValue in 2..4 -> "$selectedValue просмотра"
                        else -> "$selectedValue просмотров"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .pointerInput(values) {
                    detectTapGestures { offset -> pickIndex(offset.x, size.width) }
                }
                .pointerInput(values) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> pickIndex(offset.x, size.width) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            pickIndex(change.position.x, size.width)
                        }
                    )
                }
        ) {
            val slotWidth = size.width / values.size
            val barWidth = (slotWidth * 0.6f).coerceAtMost(24.dp.toPx())
            val corner = CornerRadius(4.dp.toPx())
            values.forEachIndexed { index, (_, value) ->
                // Halo под выбранным столбцом: выделение видно и на пустом дне
                if (index == selectedIndex) {
                    drawRoundRect(
                        color = haloColor,
                        topLeft = Offset(index * slotWidth + 1.dp.toPx(), 0f),
                        size = Size(slotWidth - 2.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                }
                val barHeight = when {
                    value > 0 -> (size.height * (value.toFloat() / max.toFloat())).coerceAtLeast(6.dp.toPx())
                    else -> 3.dp.toPx()
                }
                val left = index * slotWidth + (slotWidth - barWidth) / 2f
                drawRoundRect(
                    color = when {
                        value == 0 && index == selectedIndex -> barColor.copy(alpha = 0.45f)
                        value == 0 -> trackColor
                        else -> barColor
                    },
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = corner
                )
            }
        }

        // Под столбцами только число дня без месяца: «дд.мм» в слот не влезает
        // и режется эллипсисом. Число короткое — помещаются все 14, месяц виден в хедере.
        Row(modifier = Modifier.fillMaxWidth()) {
            values.forEachIndexed { index, (label, _) ->
                val isSelected = index == selectedIndex
                Text(
                    text = label.substringBefore('.'),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
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

    val labelFormat = SimpleDateFormat("dd.MM", Locale.forLanguageTag("ru"))
    return days.map { day ->
        labelFormat.format(Date(day)) to (counts[day] ?: 0)
    }
}

private fun String.isCustomAvatarUri(): Boolean {
    // file: без //.toURI() — File.toURI() на Android даёт «file:/…» с одним слешем.
    return startsWith("content://") || startsWith("file:") || startsWith("http")
}

/** file://-модель для Coil: всегда с //, в отличие от File.toURI() («file:/…»). */
private fun avatarFileModel(file: File): String = "file://" + file.absolutePath

/** Сериализует докачку аватара: шапка и строка аккаунта дергают её одновременно. */
private val shikimoriAvatarDownloadLock = Any()

/**
 * Локальная офлайн-копия аватара Shikimori (`filesDir/avatars/shikimori_<hash>.png`):
 * без интернета Coil удалённый URL не грузит, а файл — грузит. Возвращает `file://`
 * модель, если копия уже скачана, иначе исходный URL; докачка идёт в фоне при каждом
 * показе. Хэш URL в имени: смена аватара на сайте не отдаёт stale-копию, соседи под
 * другие URL чистятся.
 */
@Composable
private fun rememberShikimoriAvatarModel(remoteUrl: String?): Any? {
    val context = LocalContext.current
    val targetName = remember(remoteUrl) {
        remoteUrl?.takeIf { it.isNotBlank() }
            ?.let { "shikimori_${it.hashCode().toUInt().toString(36)}.png" }
    }
    var localModel by remember(remoteUrl) {
        mutableStateOf(
            targetName?.let { name ->
                runCatching {
                    File(File(context.filesDir, "avatars"), name)
                        .takeIf { it.exists() }?.let(::avatarFileModel)
                }.getOrNull()
            }
        )
    }
    LaunchedEffect(remoteUrl) {
        if (targetName == null || remoteUrl.isNullOrBlank()) return@LaunchedEffect
        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "avatars").apply { mkdirs() }
                // Всё файловое — под локом: два показа (шапка + строка аккаунта)
                // иначе чистят чужой tmp и пишут один файл параллельно.
                synchronized(shikimoriAvatarDownloadLock) {
                    dir.listFiles { f ->
                        f.isFile && f.name.startsWith("shikimori_") &&
                            (f.name.endsWith(".tmp") || (f.name.endsWith(".png") && f.name != targetName))
                    }?.forEach { it.delete() }
                    val target = File(dir, targetName)
                    if (!target.exists()) {
                        downloadToFile(remoteUrl, File(dir, "$targetName.tmp"), target)
                    }
                    target.takeIf { it.exists() }?.let(::avatarFileModel)
                }
            }.getOrNull()
        }
        if (fresh != null) localModel = fresh
    }
    return localModel ?: remoteUrl?.takeIf { it.isNotBlank() }
}

/** Скачивание по URL в [target] через временный файл, с ручным обходом редиректов. */
private fun downloadToFile(url: String, tmp: File, target: File) {
    var current = java.net.URL(url)
    var redirects = 0
    while (true) {
        val conn = (current.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "KinoshkaApp")
        }
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw java.io.IOException("redirect without Location")
                if (redirects++ >= 5) throw java.io.IOException("too many redirects")
                current = java.net.URL(current, location)
                continue
            }
            if (code != java.net.HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("http $code")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        break
    }
    if (tmp.length() == 0L) {
        tmp.delete()
        throw java.io.IOException("empty body")
    }
    if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
    }
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
    // Без скоупа user_rates Shikimori отклоняет создание/обновление/удаление оценок (403):
    // библиотека тогда только читалась, а любые правки «не сохранялись». Существующим
    // пользователям нужен перелогин — токены со старым (пустым) скоупом права не получат.
    val oauthUrl = "https://shikimori.io/oauth/authorize?client_id=$clientId&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=user_rates"

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
            // Логотип Яндекс Диска вместо облака, когда подключён именно он.
            brandIcon = if (config.type == hd.kinoshka.app.data.local.CloudSyncType.YANDEX) {
                {
                    Image(
                        painter = painterResource(hd.kinoshka.app.R.drawable.ic_src_yadisk),
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                    )
                }
            } else null,
            icon = Icons.Filled.Cloud,
            title = "Резервная копия в облаке"
        )

        when {
            config.type == hd.kinoshka.app.data.local.CloudSyncType.YANDEX -> {
                Text(
                    text = "Яндекс Диск • папка Kinoshka",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Отключить")
                }
            }
            config.type == hd.kinoshka.app.data.local.CloudSyncType.WEBDAV -> {
                Text(
                    text = "WebDAV • ${config.webDavUrl.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Отключить")
                }
            }
            else -> {
                // Same one-tap UX as the Shikimori login: the button is always visible,
                // the OAuth webview does the rest (login, permissions, code capture).
                Button(
                    onClick = onConnectYandex,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Image(
                        painter = painterResource(hd.kinoshka.app.R.drawable.ic_src_yadisk),
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
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
    // Как хосты в Termius: адрес, порт, путь и доступ — URL собирается сам.
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var path by remember { mutableStateOf("/") }
    var useHttps by remember { mutableStateOf(true) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val cleanHost = host.trim().trimEnd('/')
    val cleanPath = path.trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
    val previewUrl = if (cleanHost.isEmpty()) {
        ""
    } else {
        "${if (useHttps) "https" else "http"}://$cleanHost${if (portNumber != null) ":$portNumber" else ""}$cleanPath"
    }
    val canSave = cleanHost.isNotEmpty() && portNumber != null && user.isNotBlank() && password.isNotBlank()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подключение WebDAV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Адрес (IP или домен)") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Порт") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("Папка") },
                        placeholder = { Text("/") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "HTTPS", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                }
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
                if (previewUrl.isNotEmpty()) {
                    Text(
                        text = "Копия: $previewUrl/Kinoshka/library_backup.json",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(previewUrl, user, password) },
                enabled = canSave && previewUrl.isNotEmpty()
            ) { Text("Подключить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun AnixartLoginDialog(
    onDismiss: () -> Unit,
    onLogin: (login: String, password: String, onResult: (Boolean, String?) -> Unit) -> Unit
) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Вход в Anixart") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Синхронизируются списки (статусы). Пароль нигде не сохраняется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it; error = null },
                    label = { Text("Логин") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    onLogin(login, password) { ok, message ->
                        busy = false
                        if (!ok) error = message ?: "Вход не удался"
                    }
                },
                enabled = !busy && login.isNotBlank() && password.isNotEmpty()
            ) { Text("Войти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
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
