package hd.kinoshka.app.ui

import android.app.UiModeManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.data.cloud.CloudBackupManager
import hd.kinoshka.app.data.diagnostics.AppDiagnostics
import hd.kinoshka.app.data.local.CloudSyncStore
import hd.kinoshka.app.data.local.CloudSyncType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.PlayerMode
import hd.kinoshka.app.data.local.ShikimoriAuthStore
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.storage.StorageUsageManager
import hd.kinoshka.app.ui.screens.StorageLimits
import hd.kinoshka.app.ui.screens.StorageUsageRow
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.data.update.AppUpdateManager
import hd.kinoshka.app.data.update.AppRelease
import hd.kinoshka.app.ui.screens.DownloadsScreen
import hd.kinoshka.app.ui.screens.DiscoverCategory
import hd.kinoshka.app.data.update.UpdateCheckResult
import hd.kinoshka.app.ui.screens.AboutScreen
import hd.kinoshka.app.ui.screens.AnixartLoginDialog
import hd.kinoshka.app.ui.screens.AnimeCalendarScreen
import hd.kinoshka.app.ui.screens.AnimeFeedScreen
import hd.kinoshka.app.ui.screens.AnimeTopicScreen
import hd.kinoshka.app.ui.screens.DetailsScreen
import hd.kinoshka.app.ui.screens.DownloadQualityDialog
import hd.kinoshka.app.ui.screens.HentaiDownloadButton
import hd.kinoshka.app.ui.screens.CustomHentaiDownloadButton
import hd.kinoshka.app.ui.screens.ShikimoriWebLoginDialog
import hd.kinoshka.app.data.download.tryRequestNotificationPermission
import hd.kinoshka.app.ui.screens.TitleCastButton
import hd.kinoshka.app.ui.screens.TitleDownloadSheet
import hd.kinoshka.app.ui.screens.AnimePlaybackSelectionScreen
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.toPlayableUriString
import hd.kinoshka.app.ui.screens.enqueueMovieDownload
import hd.kinoshka.app.ui.screens.FeedViewModel
import hd.kinoshka.app.ui.screens.FeedViewModelFactory
import hd.kinoshka.app.ui.screens.FilmsViewModel
import hd.kinoshka.app.ui.screens.FilmsViewModelFactory
import hd.kinoshka.app.ui.screens.HomeScreen
import hd.kinoshka.app.ui.screens.HomeTab
import hd.kinoshka.app.ui.screens.MainSection
import hd.kinoshka.app.ui.screens.InAppWebScreen
import hd.kinoshka.app.ui.screens.MpvExPlayerScreen
import hd.kinoshka.app.ui.screens.MpvExPreferencesHost
import hd.kinoshka.app.ui.screens.OAuthWebLoginDialog
import hd.kinoshka.app.ui.screens.ProfileScreen
import hd.kinoshka.app.ui.screens.RecommendationFeedScreen
import hd.kinoshka.app.ui.screens.WebDavConfigDialog
import hd.kinoshka.app.ui.screens.YANDEX_VERIFICATION_REDIRECT
import hd.kinoshka.app.ui.screens.buildYandexAuthorizeUrl
import hd.kinoshka.app.ui.screens.SettingsScreen
import hd.kinoshka.app.ui.screens.NavMenuSettingsScreen
import hd.kinoshka.app.ui.screens.SourcesSettingsScreen
import hd.kinoshka.app.ui.screens.TileSizeSettingsScreen
import hd.kinoshka.app.ui.screens.StorageSettingsScreen
import hd.kinoshka.app.ui.screens.ProgressEditorSeed
import hd.kinoshka.app.ui.screens.UserProfileEditorSheet
import hd.kinoshka.app.ui.components.DebugPerformanceOverlay
import hd.kinoshka.app.ui.components.MovieDownloadTarget
import hd.kinoshka.app.ui.components.ProfileEditorCoverBackdrop
import hd.kinoshka.app.ui.components.AppSourceIcon
import hd.kinoshka.app.ui.components.UpdateAvailableSheet
import hd.kinoshka.app.ui.theme.KinoTheme
import hd.kinoshka.app.ui.tv.TvCloudBackupState
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.NativePlaybackMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NativePlayerArgs(
    val streamUrl: String,
    val headers: Map<String, String>,
    val qualities: Map<String, String>,
    val animeTitle: String,
    val episodeNumber: Int,
    val episodeTitle: String,
    val shikimoriId: Int = 0,
    val kinopoiskId: Int = 0,
    val sourceType: String = "KODIK",
    val episodes: List<AnimeEpisode> = emptyList(),
    val translations: List<FlatTranslation> = emptyList(),
    val currentTranslationId: String? = null,
    val movieSeriesContext: MovieSeriesPlaybackContext? = null,
    val playbackMode: NativePlaybackMode = NativePlaybackMode.ANIME
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KinoApp() {
    val context = LocalContext.current
    val viewModelStoreOwner = remember(context) { context.findActivity() ?: context as ViewModelStoreOwner }

    CompositionLocalProvider(
        LocalViewModelStoreOwner provides viewModelStoreOwner
    ) {
        val navController = rememberNavController()

        // Тап по уведомлению скачивания → страница «Загрузки» (счётчик-событие из MainActivity).
        LaunchedEffect(DownloadsNav.openRequest) {
            if (DownloadsNav.openRequest > 0 &&
                navController.currentDestination?.route != "downloads"
            ) {
                navController.navigate("downloads") { launchSingleTop = true }
            }
        }

        val appContext = LocalContext.current.applicationContext
        // Android TV: скачивание серий отключено, страница Загрузки скрыта.
        val isTv = remember(appContext) { isTvDevice(appContext) }
        // Anixart-видео: гость без входа, персонализировано со входом (токен читается
        // лениво в момент запроса — после логина/выхода подхватывается сам).
        remember(appContext) {
            val authStore = hd.kinoshka.app.data.local.AnixartAuthStore(appContext)
            hd.kinoshka.app.data.source.AnixartVideoResolver.tokenProvider =
                { authStore.getAuthState().token }
            // YouTube-ключ живёт в local.properties (BuildConfig), в коде его нет.
            hd.kinoshka.app.data.feed.YouTubeStreamResolver.apiKeyProvider =
                { hd.kinoshka.app.BuildConfig.YOUTUBE_API_KEY }
        }
        val updateManager = remember(appContext) { AppUpdateManager(appContext) }
        val updatePrefs = remember(appContext) {
            appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        }
        val scope = rememberCoroutineScope()
        var isUpdateFlowRunning by remember { mutableStateOf(false) }
        var activeNativePlayerArgs by remember { mutableStateOf<NativePlayerArgs?>(null) }
        var showUpdateSheet by remember { mutableStateOf(false) }
        // TV-профиль: диалоги входа в аккаунты (мобильные шиты ProfileScreen на ТВ не используются).
        var showTvShikimoriLogin by remember { mutableStateOf(false) }
        var showTvAnixartLogin by remember { mutableStateOf(false) }
        // TV-профиль: облачный бэкап и копия в файл. init только читает lastSync —
        // идемпотентен, мобильный профиль делает то же самое при открытии.
        LaunchedEffect(appContext) { CloudBackupManager.init(appContext) }
        val tvCloudStore = remember(appContext) { CloudSyncStore(appContext) }
        var tvCloudConfig by remember { mutableStateOf(tvCloudStore.getConfig()) }
        var showTvYandexLogin by remember { mutableStateOf(false) }
        var showTvWebDavDialog by remember { mutableStateOf(false) }
        var showTvRestoreConfirm by remember { mutableStateOf(false) }
        var availableRelease by remember { mutableStateOf<AppRelease?>(null) }
        var isDownloading by remember { mutableStateOf(false) }
        var updateDownloadProgress by remember { mutableIntStateOf(-1) } // -1 = не качаем
        var updateStatusText by remember(updatePrefs) {
            mutableStateOf(
                updatePrefs.getString(KEY_LAST_UPDATE_STATUS, "Проверка версии...")
                    ?: "Проверка версии..."
            )
        }

        val releasesUrl = BuildConfig.GITHUB_RELEASES_URL
            .takeIf { it.isNotBlank() }
            ?: GITHUB_RELEASES_URL_DEFAULT

        val openInBrowser: (String) -> Unit = { url ->
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }

        val setUpdateStatus: (String) -> Unit = { text ->
            updateStatusText = text
            updatePrefs.edit().putString(KEY_LAST_UPDATE_STATUS, text).apply()
        }

        // Единый путь установки (кнопка «Проверить обновления» и лист обновления): кэшированный
        // APK не перекачивается, процент скачивания виден в статусе, а при отсутствии разрешения
        // «неизвестные источники» поток останавливается и сам продолжается после возврата из
        // настроек (ON_RESUME-обработчик ниже) — раньше установка просто отменялась.
        val performInstall: suspend (AppRelease) -> Unit = { release ->
            if (!isDownloading) {
                isDownloading = true
                try {
                    if (updateManager.findCachedApk(release) != null) {
                        setUpdateStatus("APK уже скачан. Запускаю установку…")
                    } else {
                        updateDownloadProgress = 0
                        updateStatusText = "Скачивание APK… 0%"
                    }
                    val download = updateManager.downloadApk(release) { percent ->
                        updateDownloadProgress = percent
                        updateStatusText = "Скачивание APK… $percent%"
                    }
                    if (download.isSuccess) {
                        updateDownloadProgress = -1
                        val apkFile = download.getOrThrow()
                        if (!updateManager.canInstallPackages()) {
                            updatePrefs.edit()
                                .putString(KEY_PENDING_APK_PATH, apkFile.absolutePath)
                                .putString(KEY_PENDING_APK_TAG, release.tagName)
                                .apply()
                            setUpdateStatus("Разрешите установку — обновление продолжится автоматически.")
                            Toast.makeText(
                                appContext,
                                "Разрешите установку из этого источника — обновление продолжится после возврата.",
                                Toast.LENGTH_LONG
                            ).show()
                            updateManager.openUnknownSourcesSettings()
                        } else {
                            updatePrefs.edit()
                                .remove(KEY_PENDING_APK_PATH)
                                .remove(KEY_PENDING_APK_TAG)
                                .apply()
                            if (updateManager.launchApkInstaller(apkFile).isFailure) {
                                setUpdateStatus("Не удалось запустить установку APK.")
                                Toast.makeText(
                                    appContext,
                                    "Не удалось запустить установку APK.",
                                    Toast.LENGTH_LONG
                                ).show()
                                openInBrowser(release.htmlUrl)
                            } else {
                                setUpdateStatus("Установка версии ${release.tagName} запущена.")
                            }
                        }
                    } else {
                        updateDownloadProgress = -1
                        setUpdateStatus("Не удалось скачать APK.")
                        Toast.makeText(
                            appContext,
                            download.exceptionOrNull()?.message ?: "Не удалось скачать APK.",
                            Toast.LENGTH_LONG
                        ).show()
                        openInBrowser(release.htmlUrl)
                    }
                } finally {
                    isDownloading = false
                }
            }
        }

        // Возврат из настроек/установщика: если разрешение уже выдано — сразу запускаем
        // установку скачанного APK, если файл пропал (система вычистила кэш) — забываем его.
        val resumePendingInstallIfReady: () -> Unit = {
            updatePrefs.getString(KEY_PENDING_APK_PATH, null)?.let { path ->
                val apkFile = File(path)
                when {
                    !apkFile.exists() ->
                        updatePrefs.edit().remove(KEY_PENDING_APK_PATH).remove(KEY_PENDING_APK_TAG).apply()
                    !updateManager.canInstallPackages() -> Unit
                    else -> {
                        val tag = updatePrefs.getString(KEY_PENDING_APK_TAG, null).orEmpty()
                        updatePrefs.edit().remove(KEY_PENDING_APK_PATH).remove(KEY_PENDING_APK_TAG).apply()
                        if (updateManager.launchApkInstaller(apkFile).isSuccess) {
                            setUpdateStatus(
                                if (tag.isBlank()) "Установка обновления запущена."
                                else "Установка версии $tag запущена."
                            )
                        } else {
                            setUpdateStatus("Не удалось запустить установку APK.")
                        }
                    }
                }
            }
        }

        val runUpdateCheck: (Boolean, Boolean) -> Unit = { fromUserAction, installIfAvailable ->
            if (isUpdateFlowRunning) {
                if (fromUserAction) {
                    Toast.makeText(
                        appContext,
                        "Проверка обновления уже выполняется.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                scope.launch {
                    isUpdateFlowRunning = true
                    try {
                        setUpdateStatus("Проверяю наличие новой версии...")
                        if (fromUserAction) {
                            Toast.makeText(
                                appContext,
                                "Проверяю наличие новой версии...",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        when (
                            val checkResult = updateManager.checkForUpdate(
                                releasesUrl = releasesUrl,
                                currentVersionName = BuildConfig.VERSION_NAME
                            )
                        ) {
                            is UpdateCheckResult.UpToDate -> {
                                setUpdateStatus("Установлена последняя версия")
                                if (fromUserAction) {
                                    Toast.makeText(
                                        appContext,
                                        "Установлена актуальная версия (${BuildConfig.VERSION_NAME}).",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            is UpdateCheckResult.NoApkAsset -> {
                                setUpdateStatus("Доступна версия ${checkResult.latestTag}, но в релизе нет APK.")
                                if (fromUserAction) {
                                    Toast.makeText(
                                        appContext,
                                        "В релизе ${checkResult.latestTag} нет APK. Открываю Releases.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    openInBrowser(checkResult.htmlUrl)
                                } else {
                                    Toast.makeText(
                                        appContext,
                                        "Найдена новая версия ${checkResult.latestTag}, но без APK.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            is UpdateCheckResult.Error -> {
                                setUpdateStatus("Ошибка проверки обновления.")
                                if (fromUserAction) {
                                    Toast.makeText(
                                        appContext,
                                        checkResult.message,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    openInBrowser(releasesUrl)
                                }
                            }

                            is UpdateCheckResult.UpdateAvailable -> {
                                setUpdateStatus("Доступна новая версия ${checkResult.release.tagName}.")
                                if (!installIfAvailable) {
                                    availableRelease = checkResult.release
                                    showUpdateSheet = true
                                } else {
                                    performInstall(checkResult.release)
                                }
                            }
                        }
                    } finally {
                        isUpdateFlowRunning = false
                    }
                }
            }
        }

        LaunchedEffect(updatePrefs, releasesUrl) {
            val now = System.currentTimeMillis()
            val lastAutoCheckAt = updatePrefs.getLong(KEY_LAST_AUTO_CHECK_AT, 0L)
            if (now - lastAutoCheckAt < AUTO_UPDATE_INTERVAL_MS) return@LaunchedEffect

            updatePrefs.edit().putLong(KEY_LAST_AUTO_CHECK_AT, now).apply()
            runUpdateCheck(false, false)
        }

        val vm: FilmsViewModel = viewModel(
            factory = FilmsViewModelFactory(
                FilmsRepository(ApiClient.kinopoiskApi(appContext.cacheDir, hd.kinoshka.app.BuildConfig.KP_API_KEY)),
                AnimeRepository(
                    ApiClient.shikimoriApi(appContext.cacheDir),
                    hd.kinoshka.app.BuildConfig.SHIKIMORI_CLIENT_ID,
                    hd.kinoshka.app.BuildConfig.SHIKIMORI_CLIENT_SECRET,
                ),
                UserStateStore(appContext),
                ShikimoriAuthStore(appContext),
                hd.kinoshka.app.data.repo.AnixartRepository(
                    hd.kinoshka.app.data.api.ApiClient.anixartApi(appContext.cacheDir)
                ),
                hd.kinoshka.app.data.local.AnixartAuthStore(appContext),
                // Мутации библиотеки из ViewModel (редактор, синки) — в облачную
                // выгрузку: раньше триггер был только в плеере и правки не уезжали.
                onLibraryMutated = {
                    hd.kinoshka.app.data.cloud.CloudBackupManager.onLibraryChanged(appContext)
                }
            )
        )

        // Тестовый фид рекомендаций (TikTok-стиль): изолированная ViewModel, удаляется одним коммитом
        val feedVm: FeedViewModel = viewModel(
            factory = FeedViewModelFactory(
                appContext,
                FilmsRepository(ApiClient.kinopoiskApi(appContext.cacheDir, hd.kinoshka.app.BuildConfig.KP_API_KEY)),
                AnimeRepository(
                    ApiClient.shikimoriApi(appContext.cacheDir),
                    hd.kinoshka.app.BuildConfig.SHIKIMORI_CLIENT_ID,
                    hd.kinoshka.app.BuildConfig.SHIKIMORI_CLIENT_SECRET,
                ),
                UserStateStore(appContext)
            )
        )
        // Интенсивность свайпов ленты для физики общей пилюли (слот ниже).
        var feedIntensity by remember { mutableFloatStateOf(0f) }

        // The native player (its own Activity) writes watch progress straight into
        // SharedPreferences. Re-read it whenever the app comes back to the foreground so the
        // library folders, progress bars and details header never lag behind what was watched.
        // Тут же дотягиваем облака: Shikimori пул+пуш (прогресс с других устройств),
        // Яндекс Диск/WebDAV — скачать и объединить (см. syncFromCloudIfNeeded).
        val activityLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(activityLifecycleOwner, vm) {
            val resumeObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    vm.refreshAfterPlayerClosed()
                    vm.syncShikimoriOnForeground()
                    vm.syncAnixartOnForeground()
                    hd.kinoshka.app.data.cloud.CloudBackupManager.syncFromCloudIfNeeded(
                        appContext,
                        onMerged = vm::refreshAfterRestore
                    )
                    resumePendingInstallIfReady()
                }
            }
            activityLifecycleOwner.lifecycle.addObserver(resumeObserver)
            onDispose { activityLifecycleOwner.lifecycle.removeObserver(resumeObserver) }
        }

        // Восстановление из облачного бэкапа перезаписывает хранилище напрямую (мимо ViewModel) —
        // после успеха пересобираем библиотеку, иначе раздел показывает данные до восстановления.
        val cloudSyncStatus by hd.kinoshka.app.data.cloud.CloudBackupManager.status.collectAsState()
        LaunchedEffect(cloudSyncStatus.restoreCount) {
            if (cloudSyncStatus.restoreCount > 0) vm.refreshAfterRestore()
        }

        KinoTheme(themeMode = vm.uiState.themeMode) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Without host-level defaults navigation-compose falls back to its built-in
                    // 700 ms fades for every direction a route doesn't spell out (e.g. home's
                    // exit) — that lingering cross-fade is what made opening pages feel slow.
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        enterTransition = { fadeIn(animationSpec = tween(140)) },
                        exitTransition = { fadeOut(animationSpec = tween(120)) },
                        popEnterTransition = { fadeIn(animationSpec = tween(140)) },
                        popExitTransition = { fadeOut(animationSpec = tween(120)) }
                    ) {
                        composable(
                            route = "home",
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(durationMillis = 210))
                            }
                        ) {
                            // Long-press on a library/discover cover hosts the progress editor
                            // sheet right here. The seed is built from tile data alone — instant,
                            // no network, and the details page never opens.
                            var progressEditorSeed by remember { mutableStateOf<ProgressEditorSeed?>(null) }
                            // Состояние шита поднято: бэкдроп гаснет по targetValue (старт hide),
                            // а не по onDismiss (конец анимации) — уход строго вместе с шитом.
                            val progressSheetState = rememberBottomSheetState(
                                initialValue = SheetValue.Hidden
                            )

                            // Лёгкий блюр главной за шитом «Прогресс просмотра» (лонг-пресс).
                            // Гаснет по старту hide шита (targetValue), а не по onDismiss —
                            // быстро и плавно вместе с уходом, а не после него.
                            val homeEditorOpen = progressEditorSeed != null &&
                                progressSheetState.targetValue != SheetValue.Hidden
                            val homeEditorBlur by animateDpAsState(
                                targetValue = if (homeEditorOpen) 8.dp else 0.dp,
                                animationSpec = if (homeEditorOpen) tween(300) else tween(150),
                                label = "homeEditorBlur"
                            )
                            Box(modifier = Modifier.fillMaxSize().blur(homeEditorBlur)) {
                            HomeScreen(
                                state = vm.uiState,
                                onQueryChange = vm::onQueryChange,
                                onInstantSearch = vm::onSearchQueryChanged,
                                onSubmitSearch = vm::submitSearch,
                                onRetry = vm::retryHome,
                                onTabSelected = vm::onTabSelected,
                                onContentTypeSelected = vm::onContentTypeSelected,
                                onOpenFilm = { film ->
                                    // Открываем тайтл прямо из места нажатия (лента, сетка раздела,
                                    // поиск): без чистки фильтров и без прыжка на главную Обзора.
                                    // Назад обычным pop возвращает в тот же раздел на то же место.
                                    navController.navigate(detailsRoute(film.kinopoiskId))
                                },
                                onOpenHistoryFilm = { id -> navController.navigate(detailsRoute(id)) },
                                // Long-press: instant local progress editor, no navigation.
                                onOpenFilmEditor = { seed -> progressEditorSeed = seed },
                                onDiscoverCategorySelected = vm::onDiscoverCategorySelected,
                                onLoadMore = vm::loadMore,
                                onRemoveFromHistory = vm::removeFromHistory,
                                onRefreshLibrary = vm::refreshLibrary,
                                navOrder = vm.uiState.navOrder,
                                navHidden = vm.uiState.navHidden,
                                navHapticsEnabled = vm.uiState.navHapticsEnabled,
                                // Тактильность пилюли из настроек меню: тики 0.35/0.8
                                // масштабируем силой и уважаем системный тумблер.
                                // Импульс — точечный системный примитив (тик/клик),
                                // а не сырой oneShot: прямоугольные 20мс гудят мотором.
                                onNavHaptic = { intensity ->
                                    if (!vm.uiState.navHapticsEnabled) return@HomeScreen
                                    val scale = vm.uiState.navHapticScale
                                    if (scale <= 0.01f) return@HomeScreen
                                    val vibrator = appContext.getSystemService(
                                        android.os.Vibrator::class.java
                                    )
                                    val hapticsOn = android.provider.Settings.System.getInt(
                                        appContext.contentResolver,
                                        android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
                                        1
                                    ) == 1
                                    if (vibrator != null && vibrator.hasVibrator() && hapticsOn) {
                                        performCrispHaptic(vibrator, intensity * scale)
                                    }
                                },
                                onLibraryRefreshHaptic = { intensity ->
                                    // Тот же точечный примитив за жестом: тики натяжения,
                                    // клик срыва, тик конца. Уважаем системный тумблер.
                                    val vibrator = appContext.getSystemService(
                                        android.os.Vibrator::class.java
                                    )
                                    val hapticsOn = android.provider.Settings.System.getInt(
                                        appContext.contentResolver,
                                        android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
                                        1
                                    ) == 1
                                    if (vibrator != null && vibrator.hasVibrator() && hapticsOn) {
                                        performCrispHaptic(vibrator, intensity)
                                    }
                                },
                                onOpenProfile = { navController.navigate("profile") },
                                onConsumeLibraryDeepLink = vm::consumeLibraryDeepLink,
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenAbout = { navController.navigate("about") },
                                onOpenDownloads = { if (!isTv) navController.navigate("downloads") },
                                downloadsAvailable = !isTv,
                                onShikimoriLogin = { showTvShikimoriLogin = true },
                                onLogoutShikimori = vm::logoutShikimori,
                                onAnixartLogin = { showTvAnixartLogin = true },
                                onLogoutAnixart = vm::logoutAnixart,
                                // Бэкапы ТВ-профиля: только Android TV (на desktop секции скрыты).
                                cloudBackup = if (isTv) {
                                    TvCloudBackupState(
                                        connectedLabel = when (tvCloudConfig.type) {
                                            CloudSyncType.YANDEX -> "Яндекс Диск • папка Kinoshka"
                                            CloudSyncType.WEBDAV -> "WebDAV • ${tvCloudConfig.webDavUrl.orEmpty()}"
                                            CloudSyncType.NONE -> null
                                        },
                                        autoSync = tvCloudConfig.autoSync,
                                        busy = cloudSyncStatus.busy,
                                        statusLine = buildString {
                                            if (cloudSyncStatus.lastSyncAt > 0) {
                                                append("Последняя синхронизация: ")
                                                append(
                                                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                                        .format(Date(cloudSyncStatus.lastSyncAt))
                                                )
                                            }
                                            cloudSyncStatus.lastResult?.takeIf { it.isNotBlank() }?.let {
                                                if (isNotEmpty()) append(" • ")
                                                append(it)
                                            }
                                        },
                                        message = cloudSyncStatus.message,
                                        messageIsError = cloudSyncStatus.message?.startsWith("Ошибка") == true,
                                    )
                                } else null,
                                onCloudConnectYandex = {
                                    if (CloudBackupManager.yandexConfigured()) {
                                        showTvYandexLogin = true
                                    } else {
                                        Toast.makeText(
                                            appContext,
                                            "Создайте приложение на oauth.yandex.ru и добавьте YANDEX_DISK_CLIENT_ID и YANDEX_DISK_CLIENT_SECRET в local.properties",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onCloudConnectWebDav = { showTvWebDavDialog = true },
                                onCloudDisconnect = {
                                    CloudBackupManager.disconnect(appContext)
                                    tvCloudConfig = tvCloudStore.getConfig()
                                },
                                onCloudUpload = { CloudBackupManager.uploadBackup(appContext) },
                                onCloudRestore = { showTvRestoreConfirm = true },
                                onCloudAutoSyncChanged = { enabled ->
                                    CloudBackupManager.setAutoSync(appContext, enabled)
                                    tvCloudConfig = tvCloudStore.getConfig()
                                },
                                onExportLibraryToFile = {
                                    runCatching {
                                        val json = vm.exportLibraryJson()
                                        val fileName = "kinoshka-library-${
                                            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                        }.json"
                                        exportLibraryToDownloads(appContext, fileName, json).getOrThrow()
                                    }
                                        .onSuccess { path ->
                                            Toast.makeText(appContext, "Сохранено: $path", Toast.LENGTH_LONG).show()
                                        }
                                        .onFailure { ex ->
                                            Toast.makeText(
                                                appContext,
                                                "Ошибка экспорта: ${ex.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                },
                                onImportLibraryFromFile = {
                                    runCatching {
                                        val text = importLatestBackupFromDownloads(appContext).getOrThrow()
                                        vm.importLibraryJson(text).getOrThrow()
                                    }
                                        .onSuccess {
                                            Toast.makeText(appContext, "Импорт завершен", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { ex ->
                                            Toast.makeText(
                                                appContext,
                                                "Ошибка импорта: ${ex.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                },
                                onUpdateFilters = vm::updateFilters,
                                onToggleFilterSheet = vm::setShowFilterSheet,
                                onOpenCalendar = { navController.navigate("anime_calendar") },
                                onOpenFeed = { navController.navigate("anime_feed") },
                                onOpenTopic = { topicId -> navController.navigate("anime_topic/$topicId") },
                                onOpenRecommendationsFeed = {
                                    // Только TV-раскладка: телефон показывает ленту
                                    // секцией через feedContent ниже, без навигации.
                                    navController.navigate("recommendations_feed") {
                                        launchSingleTop = true
                                    }
                                },
                                // Лента рекомендаций — 4-я секция в том же окружении:
                                // одна пилюля, один дебаунс, круг переезжает общей
                                // анимацией с какой бы секции ни пришли. Отдельный
                                // маршрут остался только для TV (см. ниже).
                                feedContent = { select ->
                                    // Жизненный цикл как у маршрута: уход из секции
                                    // гасит фоновые джобы ленты.
                                    androidx.compose.runtime.DisposableEffect(Unit) {
                                        onDispose { feedVm.onScreenClosed() }
                                    }
                                    RecommendationFeedScreen(
                                        state = feedVm.uiState,
                                        onOpened = { feedVm.onScreenOpened() },
                                        onChipSelected = feedVm::selectChip,
                                        onLoadMore = feedVm::loadMore,
                                        onToggleExpanded = feedVm::toggleExpanded,
                                        onReact = feedVm::react,
                                        onItemShown = feedVm::onItemShown,
                                        onOpenDetails = { id -> navController.navigate(detailsRoute(id)) },
                                        onToggleSound = feedVm::toggleSound,
                                        onSelectGenre = feedVm::selectFeedGenre,
                                        onSurprise = feedVm::surpriseMe,
                                        showNavPill = false,
                                        onScrollIntensity = { feedIntensity = it },
                                        onSelectHomeSection = { tab ->
                                            select(
                                                when (tab) {
                                                    HomeTab.HISTORY -> MainSection.LIBRARY
                                                    HomeTab.CATALOG -> MainSection.DISCOVER
                                                    HomeTab.MORE -> MainSection.PROFILE
                                                }
                                            )
                                        },
                                        onAdultGateConfirm = feedVm::confirmAdultGate,
                                        onAdultGateDismiss = feedVm::dismissAdultGate,
                                        onSaveTastes = feedVm::saveTastes,
                                        onSkipTastes = feedVm::skipTastes,
                                        onResetSeen = feedVm::resetSeenAndRestart,
                                        onShareDiagnostics = { feedVm.shareDiagnostics() },
                                        onLoadTastes = feedVm::tasteSnapshot,
                                        onLoadLiked = feedVm::likedTitles,
                                        onRemoveLiked = feedVm::removeLikedEntry,
                                        onPlan = { item -> feedVm.planForLater(item) { vm.refreshAfterPlayerClosed() } }
                                    )
                                },
                                feedIntensity = feedIntensity,
                                onRetryOverview = vm::retryOverview,
                                onSeeAll = vm::openOverviewSeeAll,
                                onDiscoverReset = {
                                    // Поиск студии поверх Новостей: Назад из результатов —
                                    // на ленту (pop второй home-записи), а не сброс на месте.
                                    if (vm.consumeSearchFromFeed()) {
                                        navController.popBackStack()
                                    } else {
                                        vm.resetDiscover()
                                    }
                                },
                                // Кастомные иконки пилюли (как до KMP M4);
                                // общий HomeScreen без инъекции рисует material-фолбэк на desktop.
                                feedGlyph = { sel ->
                                    NavGlyph(
                                        filled = hd.kinoshka.app.R.drawable.ic_nav_feed_filled,
                                        outlined = hd.kinoshka.app.R.drawable.ic_nav_feed_outlined,
                                        selected = sel
                                    )
                                },
                                libraryGlyph = { sel ->
                                    NavGlyph(
                                        filled = hd.kinoshka.app.R.drawable.ic_nav_library_filled,
                                        outlined = hd.kinoshka.app.R.drawable.ic_nav_library_outlined,
                                        selected = sel
                                    )
                                },
                                discoverGlyph = { sel ->
                                    NavGlyph(
                                        filled = hd.kinoshka.app.R.drawable.ic_nav_discover_filled,
                                        outlined = hd.kinoshka.app.R.drawable.ic_nav_discover_outlined,
                                        selected = sel
                                    )
                                },
                                // Секция «Профиль» вместо старого «Ещё»: тот же экран,
                                // но без кнопки Назад и с отступом под плавающую пилюлю
                                // (112.dp = FloatingBottomContentPadding в общем HomeScreen).
                                profileContent = {
                                    ProfileScreen(
                                        avatar = vm.uiState.profileAvatar,
                                        library = vm.uiState.library,
                                        onBack = {},
                                        showBack = false,
                                        sectionBottomPadding = 112.dp,
                                        onAvatarSelected = vm::setProfileAvatar,
                                        onExportLibrary = vm::exportLibraryJson,
                                        onImportLibrary = vm::importLibraryJson,
                                        shikimoriAuthState = vm.uiState.shikimoriAuthState,
                                        onSaveShikimoriToken = vm::saveShikimoriToken,
                                        onSaveShikimoriSession = vm::saveShikimoriSession,
                                        onLogoutShikimori = vm::logoutShikimori,
                                        onOpenLibraryStatus = { status, isAnime ->
                                            vm.requestLibraryDeepLink(status, isAnime)
                                        },
                                        anixartAuthState = vm.uiState.anixartAuthState,
                                        onLoginAnixart = { login, password, onResult ->
                                            vm.loginAnixart(login, password, onResult)
                                        },
                                        onSignUpAnixart = vm::signUpAnixart,
                                        onVerifySignUpAnixart = vm::verifyAnixartSignUp,
                                        onRestoreAnixart = vm::restoreAnixart,
                                        onVerifyRestoreAnixart = vm::verifyAnixartRestore,
                                        onLogoutAnixart = vm::logoutAnixart,
                                        anixartImportProgress = vm.uiState.anixartImportProgress,
                                        onOpenSettings = { navController.navigate("settings") },
                                    onOpenSettingsEntry = { entry -> navController.navigate(entry.route) },
                                        onOpenDownloads = { if (!isTv) navController.navigate("downloads") },
                                        isAmoled = vm.uiState.themeMode == AppThemeMode.AMOLED,
                                        showDownloads = !isTv
                                    )
                                },
                                onLibrarySortSelected = vm::setLibrarySortType,
                                librarySortType = vm.uiState.librarySortType,
                                librarySortReversed = vm.uiState.librarySortReversed,
                                onLibrarySortReversedChanged = vm::setLibrarySortReversed,
                                libraryGroupType = vm.uiState.libraryGroupType,
                                onLibraryGroupSelected = vm::setLibraryGroupType,
                                onHentaiVisibilityChanged = vm::setHentaiVisibleInLibrary,
                                onRemoveSearchHistory = vm::removeSearchQueryFromHistory,
                                onClearSearchHistory = vm::clearSearchHistory
                            )
                            }

                            // TV-профиль: вход в аккаунты (диалоги работают с ТВ-клавиатурой).
                            if (showTvShikimoriLogin) {
                                ShikimoriWebLoginDialog(
                                    onDismiss = { showTvShikimoriLogin = false },
                                    onSuccess = { code, _, _, _ ->
                                        showTvShikimoriLogin = false
                                        vm.saveShikimoriToken(code)
                                    }
                                )
                            }
                            if (showTvAnixartLogin) {
                                AnixartLoginDialog(
                                    onDismiss = { showTvAnixartLogin = false },
                                    onLogin = { login, password, onResult ->
                                        context.tryRequestNotificationPermission()
                                        vm.loginAnixart(login, password) { ok, message ->
                                            if (ok) showTvAnixartLogin = false
                                            onResult(ok, message)
                                        }
                                    },
                                    onSignUp = vm::signUpAnixart,
                                    onVerifySignUp = { login, email, password, hash, code, onResult ->
                                        context.tryRequestNotificationPermission()
                                        vm.verifyAnixartSignUp(login, email, password, hash, code) { ok, message ->
                                            if (ok) showTvAnixartLogin = false
                                            onResult(ok, message)
                                        }
                                    },
                                    onRestore = vm::restoreAnixart,
                                    onVerifyRestore = { login, newPassword, hash, code, onResult ->
                                        context.tryRequestNotificationPermission()
                                        vm.verifyAnixartRestore(login, newPassword, hash, code) { ok, message ->
                                            if (ok) showTvAnixartLogin = false
                                            onResult(ok, message)
                                        }
                                    }
                                )
                            }
                            // TV-профиль: облачный бэкап (те же диалоги, что в мобильном профиле).
                            if (showTvYandexLogin) {
                                OAuthWebLoginDialog(
                                    title = "Вход через Яндекс ID",
                                    authorizeUrl = buildYandexAuthorizeUrl(),
                                    redirectUri = YANDEX_VERIFICATION_REDIRECT,
                                    onDismiss = { showTvYandexLogin = false },
                                    onCode = { code ->
                                        showTvYandexLogin = false
                                        scope.launch {
                                            CloudBackupManager.loginYandex(appContext, code)
                                                .onSuccess {
                                                    Toast.makeText(
                                                        appContext,
                                                        "Яндекс Диск подключен",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                                .onFailure {
                                                    Toast.makeText(
                                                        appContext,
                                                        "Ошибка входа: ${it.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            tvCloudConfig = tvCloudStore.getConfig()
                                        }
                                    }
                                )
                            }
                            if (showTvWebDavDialog) {
                                WebDavConfigDialog(
                                    onDismiss = { showTvWebDavDialog = false },
                                    onSave = { url, user, pass ->
                                        runCatching {
                                            CloudBackupManager.saveWebDav(appContext, url, user, pass)
                                        }
                                            .onSuccess {
                                                showTvWebDavDialog = false
                                                Toast.makeText(
                                                    appContext,
                                                    "WebDAV подключен",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                            .onFailure {
                                                Toast.makeText(
                                                    appContext,
                                                    "Ошибка: ${it.message}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        tvCloudConfig = tvCloudStore.getConfig()
                                    }
                                )
                            }
                            if (showTvRestoreConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showTvRestoreConfirm = false },
                                    title = { Text("Восстановить из облака?") },
                                    text = {
                                        Text("Локальная библиотека (статусы, прогресс, оценки, история) будет заменена содержимым резервной копии.")
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showTvRestoreConfirm = false
                                                CloudBackupManager.restoreFromCloud(appContext)
                                            }
                                        ) { Text("Восстановить") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showTvRestoreConfirm = false }) { Text("Отмена") }
                                    }
                                )
                            }

                            // Обложка на фоне за шитом (тот же общий компонент, что на странице деталей).
                            val editorSeed = progressEditorSeed
                            ProfileEditorCoverBackdrop(
                                id = editorSeed?.kinopoiskId ?: 0,
                                title = editorSeed?.title,
                                posterUrl = editorSeed?.posterUrl,
                                coverUrl = null,
                                visible = editorSeed != null && progressSheetState.targetValue != SheetValue.Hidden
                            )
                            progressEditorSeed?.let { seed ->
                                // Minimal locally-built details: the editor only reads identity
                                // fields (id/name/type/genres) and saves through the same path as
                                // the details page, Shikimori sync included.
                                val editorDetails = FilmDetails(
                                    kinopoiskId = seed.kinopoiskId,
                                    nameRu = seed.title,
                                    posterUrl = seed.posterUrl,
                                    posterUrlPreview = seed.posterUrl,
                                    ratingKinopoisk = seed.ratingKinopoisk,
                                    year = seed.year,
                                    type = seed.type
                                )
                                UserProfileEditorSheet(
                                    item = editorDetails,
                                    animeDetails = null,
                                    seasons = emptyList(),
                                    profile = seed.profile,
                                    saving = false,
                                    sheetState = progressSheetState,
                                    onDismiss = { progressEditorSeed = null },
                                    onSave = { status, rating, note, watchedSeasons, watchedEpisodes ->
                                        vm.saveUserProfile(
                                            editorDetails,
                                            status,
                                            rating,
                                            note,
                                            watchedSeasons,
                                            watchedEpisodes,
                                            totalEpisodesInSeason = null,
                                            totalSeasons = null,
                                            totalEpisodes = null,
                                            isRussianOverride = seed.profile?.isRussian
                                        )
                                        progressEditorSeed = null
                                    }
                                )
                            }
                        }
                        composable(
                            route = "downloads",
                            // Same easing fade the details page uses — the app's standard for
                            // secondary pages, unlike the flat 140 ms fade elsewhere.
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                DownloadsScreen(
                                    onBack = { navController.popBackStack() },
                                    // Запись загрузок остаётся в стеке: Назад из деталей
                                    // возвращается сюда обычным pop.
                                    onOpenTitle = { id -> navController.navigate(detailsRoute(id)) }
                                )
                            }
                        }
                        composable(
                            route = "anime_calendar",
                            enterTransition = { fadeIn(animationSpec = tween(140)) },
                            exitTransition = { fadeOut(animationSpec = tween(120)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(140)) },
                            popExitTransition = { fadeOut(animationSpec = tween(120)) }
                        ) {
                            TvAdaptiveSecondary {
                                AnimeCalendarScreen(
                                    calendarItems = vm.uiState.calendarItems,
                                    loading = vm.uiState.calendarLoading,
                                    onBack = { navController.popBackStack() },
                                    onOpenAnime = { targetId ->
                                        // Тайтл из Календаря/Ленты релизов: Назад должен
                                        // вернуть на главную Обзора, а не в этот экран.
                                        vm.clearDiscoverFilters()
                                        vm.markDetailsFromOverview()
                                        navController.navigate(detailsRoute(targetId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET))
                                    }
                                )
                            }
                        }
                        composable(
                            route = "anime_feed",
                            enterTransition = { fadeIn(animationSpec = tween(140)) },
                            exitTransition = { fadeOut(animationSpec = tween(120)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(140)) },
                            popExitTransition = { fadeOut(animationSpec = tween(120)) }
                        ) {
                            TvAdaptiveSecondary {
                                AnimeFeedScreen(
                                    topics = vm.uiState.topics,
                                    loading = vm.uiState.topicsLoading,
                                    onBack = { navController.popBackStack() },
                                    onOpenAnime = { targetId ->
                                        // Тайтл из Новостей: запись ленты остаётся в стеке,
                                        // Назад обычным pop возвращает на неё с прокруткой.
                                        navController.navigate(detailsRoute(targetId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET))
                                    },
                                    loadComments = vm::loadTopicComments,
                                    onOpenStudio = { studioId, studioName ->
                                        // Каталог студии — второй home поверх ленты: Назад из
                                        // результатов возвращается на Новости (onDiscoverReset выше).
                                        vm.searchStudio(studioId, studioName)
                                        vm.markSearchFromFeed(studioName)
                                        navController.navigate("home")
                                    },
                                    // Видео из постов играет нативный плеер, как трейлеры
                                    // тайтлов (YouTube-поток извлекается здесь же).
                                    onPlayVideoStream = { streamUrl, headers, title ->
                                        activeNativePlayerArgs = NativePlayerArgs(
                                            streamUrl,
                                            headers,
                                            emptyMap(),
                                            title,
                                            1,
                                            "Видео",
                                            0,
                                            0,
                                            "Видео",
                                            emptyList(),
                                            emptyList(),
                                            "",
                                            null,
                                            NativePlaybackMode.ANIME
                                        )
                                    }
                                )
                            }
                        }
                        composable(
                            route = "anime_topic/{topicId}",
                            arguments = listOf(navArgument("topicId") { type = NavType.IntType }),
                            enterTransition = { fadeIn(animationSpec = tween(140)) },
                            exitTransition = { fadeOut(animationSpec = tween(120)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(140)) },
                            popExitTransition = { fadeOut(animationSpec = tween(120)) }
                        ) { backStackEntry ->
                            val topicId = backStackEntry.arguments?.getInt("topicId") ?: 0
                            TvAdaptiveSecondary {
                                AnimeTopicScreen(
                                    topic = vm.uiState.topics.find { it.id == topicId },
                                    onBack = { navController.popBackStack() },
                                    onOpenAnime = { targetId ->
                                        // Тайтл из поста: Назад возвращает в пост, затем на ленту.
                                        navController.navigate(detailsRoute(targetId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET))
                                    },
                                    loadComments = vm::loadTopicComments,
                                    onOpenStudio = { studioId, studioName ->
                                        // Каталог студии поверх поста: Назад — в пост,
                                        // затем на ленту (onDiscoverReset выше).
                                        vm.searchStudio(studioId, studioName)
                                        vm.markSearchFromFeed(studioName)
                                        navController.navigate("home")
                                    },
                                    // Видео из постов играет нативный плеер, как трейлеры
                                    // тайтлов (YouTube-поток извлекается здесь же).
                                    onPlayVideoStream = { streamUrl, headers, title ->
                                        activeNativePlayerArgs = NativePlayerArgs(
                                            streamUrl,
                                            headers,
                                            emptyMap(),
                                            title,
                                            1,
                                            "Видео",
                                            0,
                                            0,
                                            "Видео",
                                            emptyList(),
                                            emptyList(),
                                            "",
                                            null,
                                            NativePlaybackMode.ANIME
                                        )
                                    }
                                )
                            }
                        }
                        composable(
                            // Только TV-раскладка: телефон показывает ленту секцией
                            // HomeScreen через feedContent (общая пилюля и дебаунс).
                            route = "recommendations_feed",
                            enterTransition = { fadeIn(animationSpec = tween(160)) },
                            exitTransition = { fadeOut(animationSpec = tween(120)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(160)) },
                            popExitTransition = { fadeOut(animationSpec = tween(120)) }
                        ) {
                            // Уход с ленты гасит её фоновые джобы (сеть/декод), иначе они
                            // продолжают долбить под входную анимацию home — чёрный экран.
                            androidx.compose.runtime.DisposableEffect(Unit) {
                                onDispose { feedVm.onScreenClosed() }
                            }
                            RecommendationFeedScreen(
                                state = feedVm.uiState,
                                onOpened = { feedVm.onScreenOpened() },
                                onChipSelected = feedVm::selectChip,
                                onLoadMore = feedVm::loadMore,
                                onToggleExpanded = feedVm::toggleExpanded,
                                onReact = feedVm::react,
                                onItemShown = feedVm::onItemShown,
                                onOpenDetails = { id -> navController.navigate(detailsRoute(id)) },
                                onToggleSound = feedVm::toggleSound,
                                onSelectGenre = feedVm::selectFeedGenre,
                                onSurprise = feedVm::surpriseMe,
                                onSelectHomeSection = { tab ->
                                    vm.onTabSelected(tab)
                                    navController.popBackStack()
                                },
                                onAdultGateConfirm = feedVm::confirmAdultGate,
                                onAdultGateDismiss = feedVm::dismissAdultGate,
                                onSaveTastes = feedVm::saveTastes,
                                onSkipTastes = feedVm::skipTastes,
                                onResetSeen = feedVm::resetSeenAndRestart,
                                onShareDiagnostics = { feedVm.shareDiagnostics() },
                                onLoadTastes = feedVm::tasteSnapshot,
                                onLoadLiked = feedVm::likedTitles,
                                onRemoveLiked = feedVm::removeLikedEntry,
                                onPlan = { item -> feedVm.planForLater(item) { vm.refreshAfterPlayerClosed() } }
                            )
                        }
                        composable(
                            route = "details/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.IntType }),
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getInt("id") ?: return@composable
                            // Пометки скачанного для кино-пикера: серии (сезон×1000+номер,
                            // зеркало упаковки DownloadBridges) и счётчики по дабам.
                            val movieItemKey = hd.kinoshka.app.data.download.animeItemKey(0, id)
                            val downloadLibrary by EpisodeDownloadManager.library.collectAsState()
                            val movieDownloadedEpisodes = remember(downloadLibrary, movieItemKey) {
                                downloadLibrary.filter { it.itemKey == movieItemKey }.mapNotNull { entry ->
                                    val n = entry.episodeNumber
                                    if (n >= 1000) (n / 1000) to (n % 1000) else null
                                }.toSet()
                            }
                            val movieDownloadedByTranslation = remember(downloadLibrary, movieItemKey) {
                                downloadLibrary.filter { it.itemKey == movieItemKey }
                                    .groupingBy { it.translationId }.eachCount()
                            }
                            val detailsContext = LocalContext.current
                            // Любая кино-цель ждёт выбора качества в диалоге: прямые ссылки
                            // тоже давятся потолком (лестница даба из каталога, иначе
                            // вариант внутри HLS-мастера) — см. directCappedSource.
                            var pendingMovieDownload by remember { mutableStateOf<MovieDownloadTarget?>(null) }
                            DetailsScreen(
                                filmId = id,
                                state = vm.detailsState,
                                load = vm::loadDetails,
                                onWatch = vm::onWatch,
                                onSaveUserProfile = vm::saveUserProfile,
                                onOpenUrl = { rawUrl -> navController.navigate("web?url=${Uri.encode(rawUrl)}") },
                                onOpenFilm = { targetId -> navController.navigate(detailsRoute(targetId)) },
                                onBack = {
                                    // Детали из контекста Обзора: возвращаемся сразу на home,
                                    // минуя промежуточные экраны (сетка раздела, календарь,
                                    // лента релизов). Остальные входы — обычный pop.
                                    if (vm.consumeDetailsFromOverview()) {
                                        navController.popBackStack("home", false)
                                    } else {
                                        navController.popBackStack()
                                    }
                                },
                                onOpenGenre = { genreName, isAnime ->
                                    vm.searchGenre(genreName, isAnime)
                                    vm.consumeDetailsFromOverview()
                                    navController.popBackStack("home", false)
                                },
                                 onOpenNativePlayer = { streamUrl, headers, qualities, title, epNum, epTitle, shikimoriId, kinopoiskId, srcType, episodes, translations, trId, seriesContext ->
                                     val mode = when {
                                         seriesContext != null -> NativePlaybackMode.MOVIE_SERIES
                                         // Movies/series launched unresolved from the details page: the
                                         // player opens at once and resolves its stream in the background.
                                         srcType == "PENDING" -> NativePlaybackMode.PENDING_MOVIE
                                         // Voiceover-only launches (kodik/ddbb movies and ddbb series
                                         // fallbacks): translations carry direct or lazily-resolved
                                         // links, handled by setQualityOnlyMovieExtras. Episodes stay
                                         // the ANIME/MOVIE_SERIES discriminator, not translations.
                                         episodes.isEmpty() && shikimoriId == 0 -> NativePlaybackMode.QUALITY_ONLY_MOVIE
                                         else -> NativePlaybackMode.ANIME
                                     }
                                     activeNativePlayerArgs = NativePlayerArgs(streamUrl, headers, qualities, title, epNum, epTitle, shikimoriId, kinopoiskId, srcType, episodes, translations, trId, seriesContext, mode)
                                 },
                                // Плеер всегда mpvEx (выбора в настройках больше нет).
                                playerMode = PlayerMode.MPVEX,
                                // Платформенные слоты DetailsScreen: скачивание и выбор источника
                                // живут в app (Android-механика), сам экран теперь общий.
                                userStateStore = UserStateStore(LocalContext.current),
                                // Реальные иконки источников в кино-пикере и хентай-выборе.
                                sourceIcon = { id, size ->
                                    AppSourceIcon(id, Modifier.size(size))
                                },
                                animeSelectionScreen = { shikimoriId, kinopoiskId, animeTitle, imdbId, onDismissRequest, onStreamSelected ->
                                    AnimePlaybackSelectionScreen(
                                        shikimoriId = shikimoriId,
                                        kinopoiskId = kinopoiskId,
                                        animeTitle = animeTitle,
                                        imdbId = imdbId,
                                        onDismissRequest = onDismissRequest,
                                        onStreamSelected = onStreamSelected
                                    )
                                },
                                // Android TV: скачивание отключено — слоты загрузки не даём.
                                downloadSheet = if (isTv) null else { item, isAnime, onDismiss ->
                                    TitleDownloadSheet(item = item, isAnime = isAnime, onDismiss = onDismiss)
                                },
                                onOpenCastPlayer = { streamUrl, headers, qualities, title, epNum, epTitle, shikimoriId, kinopoiskId, srcType, episodes, translations, trId, seriesContext ->
                                    castAnimeFromPicker(
                                        detailsContext, streamUrl, headers, qualities, title,
                                        epNum, trId, shikimoriId, kinopoiskId
                                    )
                                },
                                onCastMovieSelected = { result, displayTitle, kinopoiskId ->
                                    castMovieFromPicker(detailsContext, result, displayTitle, kinopoiskId)
                                },
                                castButton = { item, isAnime, onOpenCastPicker ->
                                    TitleCastButton(
                                        shikimoriId = if (isAnime && item.kinopoiskId > hd.kinoshka.app.data.model.ANIME_ID_OFFSET) {
                                            item.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET
                                        } else 0,
                                        kinopoiskId = item.kinopoiskId,
                                        title = item.nameRu ?: item.nameOriginal ?: item.nameEn ?: "",
                                        isAnime = isAnime,
                                        onOpenCastPicker = onOpenCastPicker
                                    )
                                },
                                hentaiDownloadButton = if (isTv) null else { title, kinopoiskId, provider, label, episodeNumber, episodeUrl, headers ->
                                    HentaiDownloadButton(title, kinopoiskId, provider, label, episodeNumber, episodeUrl, headers)
                                },
                                customHentaiDownloadButton = if (isTv) null else { title, kinopoiskId, customId, customName, label, episodeNumber, episodeUrl, headers ->
                                    CustomHentaiDownloadButton(title, kinopoiskId, customId, customName, label, episodeNumber, episodeUrl, headers)
                                },
                                findLocalHentai = { kinopoiskId, providerName, translationId, episodeNumber ->
                                    EpisodeDownloadManager.findLocal(
                                        0, kinopoiskId, providerName, translationId, episodeNumber
                                    )?.toPlayableUriString()
                                },
                                movieDownloadedEpisodes = movieDownloadedEpisodes,
                                movieDownloadedByTranslation = movieDownloadedByTranslation,
                                onMovieDownload = if (isTv) null else { target ->
                                    pendingMovieDownload = target
                                }
                            )
                            pendingMovieDownload?.let { target ->
                                DownloadQualityDialog(
                                    onDismiss = { pendingMovieDownload = null },
                                    onConfirm = { quality ->
                                        pendingMovieDownload = null
                                        enqueueMovieDownload(detailsContext, target, quality)
                                    }
                                )
                            }
                        }
                        composable(
                            route = "profile",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                ProfileScreen(
                                    avatar = vm.uiState.profileAvatar,
                                    library = vm.uiState.library,
                                    onBack = { navController.popBackStack() },
                                    onAvatarSelected = vm::setProfileAvatar,
                                    onExportLibrary = vm::exportLibraryJson,
                                    onImportLibrary = vm::importLibraryJson,
                                    shikimoriAuthState = vm.uiState.shikimoriAuthState,
                                    onSaveShikimoriToken = vm::saveShikimoriToken,
                                    onSaveShikimoriSession = vm::saveShikimoriSession,
                                    onLogoutShikimori = vm::logoutShikimori,
                                    onOpenLibraryStatus = { status, isAnime ->
                                        vm.requestLibraryDeepLink(status, isAnime)
                                        navController.popBackStack()
                                    },
                                    anixartAuthState = vm.uiState.anixartAuthState,
                                    onLoginAnixart = { login, password, onResult ->
                                        vm.loginAnixart(login, password, onResult)
                                    },
                                    onSignUpAnixart = vm::signUpAnixart,
                                    onVerifySignUpAnixart = vm::verifyAnixartSignUp,
                                    onRestoreAnixart = vm::restoreAnixart,
                                    onVerifyRestoreAnixart = vm::verifyAnixartRestore,
                                    onLogoutAnixart = vm::logoutAnixart,
                                    anixartImportProgress = vm.uiState.anixartImportProgress,
                                    onOpenSettings = { navController.navigate("settings") },
                                    onOpenSettingsEntry = { entry -> navController.navigate(entry.route) },
                                    onOpenDownloads = { if (!isTv) navController.navigate("downloads") },
                                    isAmoled = vm.uiState.themeMode == AppThemeMode.AMOLED,
                                    showDownloads = !isTv
                                )
                            }
                        }
                        composable(
                            route = "settings",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                // Контекст для писателей настроек: лямбды некомпозабельны,
                                // LocalContext.current внутри них нельзя.
                                val settingsContext = LocalContext.current.applicationContext
                                SettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    selectedThemeMode = vm.uiState.themeMode,
                                    hideRussianContent = vm.uiState.hideRussianContent,
                                    selectedDiscoverTileSize = vm.uiState.discoverTileSize,
                                    selectedLibraryTileSize = vm.uiState.libraryTileSize,
                                    selectedShowFpsCounter = vm.uiState.showFpsCounter,
                                    onThemeModeSelected = vm::setThemeMode,
                                    onHideRussianChanged = vm::setHideRussianContent,
                                    onShowFpsCounterChanged = vm::setShowFpsCounter,
                                    showDebugSettings = BuildConfig.DEBUG,
                                    onOpenPlayerSettings = { navController.navigate("player_settings") },
                                    onOpenAbout = { navController.navigate("about") },
                                    onOpenSources = { navController.navigate("sources") },
                                    onOpenStorage = { navController.navigate("storage") },
                                    onOpenNavMenu = { navController.navigate("nav_menu") },
                                    onOpenOverview = { navController.navigate("settings_overview") },
                                    onOpenLibrary = { navController.navigate("settings_library") },
                                    // Те же глифы, что в пилюле (книги/компас), а не material.
                                    overviewIconContent = {
                                        NavGlyph(
                                            filled = hd.kinoshka.app.R.drawable.ic_nav_discover_filled,
                                            outlined = hd.kinoshka.app.R.drawable.ic_nav_discover_outlined,
                                            selected = false,
                                            size = 24.dp
                                        )
                                    },
                                    libraryIconContent = {
                                        NavGlyph(
                                            filled = hd.kinoshka.app.R.drawable.ic_nav_library_filled,
                                            outlined = hd.kinoshka.app.R.drawable.ic_nav_library_outlined,
                                            selected = false,
                                            size = 24.dp
                                        )
                                    }
                                )
                            }
                        }
                        composable(
                            route = "settings_overview",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                TileSizeSettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    title = "Обзор",
                                    subtitle = "Внешний вид страницы",
                                    selected = vm.uiState.discoverTileSize,
                                    onSelected = vm::setDiscoverTileSize
                                )
                            }
                        }
                        composable(
                            route = "settings_library",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                TileSizeSettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    title = "Библиотека",
                                    subtitle = "Внешний вид страницы",
                                    selected = vm.uiState.libraryTileSize,
                                    onSelected = vm::setLibraryTileSize
                                )
                            }
                        }
                        composable(
                            route = "nav_menu",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                NavMenuSettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    order = vm.uiState.navOrder,
                                    hidden = vm.uiState.navHidden,
                                    hapticsEnabled = vm.uiState.navHapticsEnabled,
                                    hapticScale = vm.uiState.navHapticScale,
                                    onMoveSection = vm::moveNavSection,
                                    onToggleSection = vm::setNavSectionVisible,
                                    onHapticsEnabledChanged = vm::setNavHapticsEnabled,
                                    onHapticScaleChanged = vm::setNavHapticScale,
                                    // Живой тест силы прямо в настройках: тот же точечный
                                    // примитив, что у пилюли, с выбранной силой.
                                    onHapticPreview = { scale ->
                                        if (scale > 0.01f) {
                                            val vibrator = appContext.getSystemService(
                                                android.os.Vibrator::class.java
                                            )
                                            val hapticsOn = android.provider.Settings.System.getInt(
                                                appContext.contentResolver,
                                                android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
                                                1
                                            ) == 1
                                            if (vibrator != null && vibrator.hasVibrator() && hapticsOn) {
                                                performCrispHaptic(vibrator, scale)
                                            }
                                        }
                                    },
                                    isAmoled = vm.uiState.themeMode == AppThemeMode.AMOLED,
                                    libraryGlyph = { sel ->
                                        NavGlyph(
                                            filled = hd.kinoshka.app.R.drawable.ic_nav_library_filled,
                                            outlined = hd.kinoshka.app.R.drawable.ic_nav_library_outlined,
                                            selected = sel,
                                            size = 24.dp
                                        )
                                    },
                                    discoverGlyph = { sel ->
                                        NavGlyph(
                                            filled = hd.kinoshka.app.R.drawable.ic_nav_discover_filled,
                                            outlined = hd.kinoshka.app.R.drawable.ic_nav_discover_outlined,
                                            selected = sel,
                                            size = 24.dp
                                        )
                                    },
                                    feedGlyph = { sel ->
                                        NavGlyph(
                                            filled = hd.kinoshka.app.R.drawable.ic_nav_feed_filled,
                                            outlined = hd.kinoshka.app.R.drawable.ic_nav_feed_outlined,
                                            selected = sel,
                                            size = 24.dp
                                        )
                                    }
                                )
                            }
                        }
                        composable(
                            route = "storage",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                val storageContext = LocalContext.current.applicationContext
                                // Замер — фоном при входе, пересчёт после каждой очистки.
                                var storageRows by remember { mutableStateOf<List<StorageUsageRow>?>(null) }
                                var storageLimits by remember { mutableStateOf<StorageLimits?>(null) }
                                var storageClearingKeys by remember { mutableStateOf(emptySet<String>()) }
                                fun refreshStorage() {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val breakdown = runCatching {
                                            StorageUsageManager.scan(storageContext)
                                        }.getOrNull()
                                        val rows = breakdown?.let {
                                            listOf(
                                                StorageUsageRow(StorageUsageManager.CAT_OFFLINE, "Загрузки", it.bytesOf(StorageUsageManager.CAT_OFFLINE), destructive = true),
                                                StorageUsageRow(StorageUsageManager.CAT_IMAGES, "Изображения", it.bytesOf(StorageUsageManager.CAT_IMAGES)),
                                                StorageUsageRow(StorageUsageManager.CAT_API, "Кэш API", it.bytesOf(StorageUsageManager.CAT_API)),
                                                StorageUsageRow(StorageUsageManager.CAT_HENTAI, "Кадры и каталог 18+", it.bytesOf(StorageUsageManager.CAT_HENTAI)),
                                                StorageUsageRow(StorageUsageManager.CAT_UPDATES, "Файлы обновлений", it.bytesOf(StorageUsageManager.CAT_UPDATES)),
                                                StorageUsageRow(StorageUsageManager.CAT_THUMBS, "Миниатюры видео", it.bytesOf(StorageUsageManager.CAT_THUMBS)),
                                                StorageUsageRow(StorageUsageManager.CAT_WEBVIEW, "WebView", it.bytesOf(StorageUsageManager.CAT_WEBVIEW)),
                                                StorageUsageRow(StorageUsageManager.CAT_DATA, "Данные и история", it.bytesOf(StorageUsageManager.CAT_DATA), destructive = true)
                                            )
                                        }
                                        val limits = StorageLimits(
                                            imageLimitMb = StorageUsageManager.getImageLimitMb(storageContext),
                                            retentionDays = StorageUsageManager.getRetentionDays(storageContext),
                                            autoCleanup = StorageUsageManager.isAutoCleanup(storageContext),
                                            imageOptions = StorageUsageManager.IMAGE_LIMIT_OPTIONS_MB
                                        )
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            storageRows = rows
                                            storageLimits = limits
                                        }
                                    }
                                }
                                // Пакетная очистка выбранных разделов: спиннеры идут по строкам
                                // по мере удаления, один пересчёт в конце.
                                fun clearStorages(keys: List<String>) {
                                    if (storageClearingKeys.isNotEmpty() || keys.isEmpty()) return
                                    storageClearingKeys = keys.toSet()
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        var ok = true
                                        keys.forEach { key ->
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                storageClearingKeys = setOf(key)
                                            }
                                            ok = runCatching {
                                                StorageUsageManager.clearCategory(storageContext, key)
                                            }.isSuccess && ok
                                        }
                                        refreshStorage()
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            storageClearingKeys = emptySet()
                                            Toast.makeText(
                                                storageContext,
                                                if (ok) "Очищено" else "Не удалось очистить",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                LaunchedEffect(Unit) { refreshStorage() }
                                StorageSettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    rows = storageRows,
                                    limits = storageLimits,
                                    clearingKeys = storageClearingKeys,
                                    onClearSelected = ::clearStorages,
                                    onImageLimitSelected = { mb ->
                                        StorageUsageManager.setImageLimitMb(storageContext, mb)
                                        refreshStorage()
                                    },
                                    onRetentionSelected = { days ->
                                        StorageUsageManager.setRetentionDays(storageContext, days)
                                        refreshStorage()
                                    },
                                    onAutoCleanupChanged = { enabled ->
                                        StorageUsageManager.setAutoCleanup(storageContext, enabled)
                                        refreshStorage()
                                    }
                                )
                            }
                        }
                        composable(
                            route = "sources",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                val sourcesContext = LocalContext.current.applicationContext
                                val sourcesStore = remember(sourcesContext) {
                                    UserStateStore(sourcesContext)
                                }
                                var disabledKeys by remember { mutableStateOf(emptySet<String>()) }
                                var customSources by remember {
                                    mutableStateOf(emptyList<hd.kinoshka.app.data.source.CustomSource>())
                                }
                                // Обмен своими источниками файлом JSON (шаринг между
                                // устройствами): те же контракты, что у экспорта библиотеки.
                                val exportCustomsFile = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.CreateDocument("application/json")
                                ) { uri ->
                                    if (uri == null) return@rememberLauncherForActivityResult
                                    runCatching {
                                        val json = sourcesStore.exportCustomSourcesJson()
                                        sourcesContext.contentResolver.openOutputStream(uri)?.use {
                                            it.write(json.toByteArray(Charsets.UTF_8))
                                        } ?: error("Не удалось открыть файл для записи")
                                    }
                                        .onSuccess { Toast.makeText(sourcesContext, "Экспорт завершён", Toast.LENGTH_SHORT).show() }
                                        .onFailure { ex -> Toast.makeText(sourcesContext, "Ошибка экспорта: ${ex.message}", Toast.LENGTH_LONG).show() }
                                }
                                val importCustomsFile = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.OpenDocument()
                                ) { uri ->
                                    if (uri == null) return@rememberLauncherForActivityResult
                                    runCatching {
                                        val text = sourcesContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                            ?: error("Не удалось прочитать файл")
                                        sourcesStore.importCustomSourcesJson(text).getOrThrow()
                                    }
                                        .onSuccess { report ->
                                            scope.launch {
                                                customSources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    sourcesStore.getCustomSources()
                                                }
                                            }
                                            Toast.makeText(sourcesContext, report.summary(), Toast.LENGTH_LONG).show()
                                        }
                                        .onFailure { ex -> Toast.makeText(sourcesContext, "Ошибка импорта: ${ex.message}", Toast.LENGTH_LONG).show() }
                                }
                                LaunchedEffect(sourcesStore) {
                                    disabledKeys = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        sourcesStore.getDisabledSourceKeys()
                                    }
                                    customSources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        sourcesStore.getCustomSources()
                                    }
                                }
                                SourcesSettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    disabledKeys = disabledKeys,
                                    onSourceEnabledChanged = { id, category, enabled ->
                                        sourcesStore.setSourceEnabled(id, enabled, category)
                                        scope.launch {
                                            disabledKeys = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                sourcesStore.getDisabledSourceKeys()
                                            }
                                        }
                                    },
                                    customSources = customSources,
                                    onSaveCustomSource = { src ->
                                        sourcesStore.saveCustomSource(src)
                                        scope.launch {
                                            customSources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                sourcesStore.getCustomSources()
                                            }
                                        }
                                    },
                                    onDeleteCustomSource = { id ->
                                        sourcesStore.deleteCustomSource(id)
                                        scope.launch {
                                            customSources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                sourcesStore.getCustomSources()
                                            }
                                        }
                                    },
                                    onMoveCustomSource = { id, delta ->
                                        scope.launch {
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                sourcesStore.moveCustomSource(id, delta)
                                            }
                                            customSources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                sourcesStore.getCustomSources()
                                            }
                                        }
                                    },
                                    onExportCustomSourcesFile = {
                                        exportCustomsFile.launch("kinoshka-custom-sources.json")
                                    },
                                    onImportCustomSourcesFile = {
                                        importCustomsFile.launch(arrayOf("application/json"))
                                    },
                                    // Реальные иконки: аниме — те же drawable, что в пикере
                                    // озвучек/источников, остальные — PNG-логотипы.
                                    sourceIcon = { info ->
                                        AppSourceIcon(
                                            sourceId = info.id,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                )
                            }
                        }
                        composable(
                            route = "about",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                AboutScreen(
                                    onBack = { navController.popBackStack() },
                                    updateStatusText = updateStatusText,
                                    isUpdateCheckRunning = isUpdateFlowRunning,
                                    onCheckUpdates = { runUpdateCheck(true, true) },
                                    onOpenGithub = { openInBrowser("https://github.com/HalfyDay/Kinoshka") },
                                    onOpenTelegram = { openInBrowser("https://t.me/Kinoshka_HalfDay") },
                                    onOpenShikimori = { openInBrowser("https://shikimori.io") },
                                    // Настоящая лаунчер-иконка: адаптивный icon нельзя отдать
                                    // painterResource, рисуем drawable в bitmap и режем круг.
                                    appIcon = {
                                        val iconContext = LocalContext.current
                                        val iconBitmap = remember(iconContext) {
                                            runCatching {
                                                val drawable = iconContext.packageManager.getApplicationIcon(iconContext.packageName)
                                                val size = 256
                                                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                                                val canvas = android.graphics.Canvas(bmp)
                                                drawable.setBounds(0, 0, size, size)
                                                drawable.draw(canvas)
                                                bmp.asImageBitmap()
                                            }.getOrNull()
                                        }
                                        if (iconBitmap != null) {
                                            Image(
                                                bitmap = iconBitmap,
                                                contentDescription = "Иконка приложения",
                                                modifier = Modifier.size(90.dp).clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.SmartDisplay,
                                                contentDescription = "Иконка приложения",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(90.dp)
                                            )
                                        }
                                    },
                                    appVersion = BuildConfig.VERSION_NAME,
                                    appPackage = BuildConfig.APPLICATION_ID,
                                    onReportProblem = {
                                        context.findActivity()?.let { AppDiagnostics.shareReport(it) }
                                    }
                                )
                            }
                        }
                        composable(
                            route = "player_settings",
                            enterTransition = {
                                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(160))
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            TvAdaptiveSecondary {
                                MpvExPreferencesHost(onExit = { navController.popBackStack() })
                            }
                        }
                        composable(
                            route = "web?url={url}",
                            arguments = listOf(
                                navArgument("url") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = ""
                                }
                            )
                        ) { backStackEntry ->
                            val encodedUrl = backStackEntry.arguments?.getString("url").orEmpty()
                            InAppWebScreen(url = Uri.decode(encodedUrl))
                        }
                    }

                    DebugPerformanceOverlay(
                        enabled = vm.uiState.showFpsCounter && BuildConfig.DEBUG,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 8.dp, top = 8.dp)
                    )

                    if (activeNativePlayerArgs != null) {
                        val args = activeNativePlayerArgs!!
                        MpvExPlayerScreen(
                            streamUrl = args.streamUrl,
                            headers = args.headers,
                            qualities = args.qualities,
                            animeTitle = args.animeTitle,
                            episodeNumber = args.episodeNumber,
                            episodeTitle = args.episodeTitle,
                            shikimoriId = args.shikimoriId,
                            kinopoiskId = args.kinopoiskId,
                            sourceType = args.sourceType,
                            episodes = args.episodes,
                            translations = args.translations,
                            currentTranslationId = args.currentTranslationId,
                            movieSeriesContext = args.movieSeriesContext,
                            playbackMode = args.playbackMode,
                            onBack = { activeNativePlayerArgs = null }
                        )
                    }

                    if (showUpdateSheet && availableRelease != null) {
                        val release = availableRelease!!
                        UpdateAvailableSheet(
                            release = release,
                            isDownloading = isDownloading,
                            downloadProgress = updateDownloadProgress,
                            currentVersion = BuildConfig.VERSION_NAME,
                            onDismiss = {
                                showUpdateSheet = false
                                availableRelease = null
                            },
                            onUpdate = {
                                scope.launch { performInstall(release) }
                            }
                        )
                    }
                }
            }
        }
    }
}

private const val GITHUB_RELEASES_URL_DEFAULT = "https://github.com/HalfyDay/Kinoshka/releases"
private const val UPDATE_PREFS_NAME = "update_preferences"
private const val KEY_LAST_AUTO_CHECK_AT = "last_auto_check_at"
private const val KEY_LAST_UPDATE_STATUS = "last_update_status"
private const val KEY_PENDING_APK_PATH = "pending_apk_path"
private const val KEY_PENDING_APK_TAG = "pending_apk_tag"
private const val AUTO_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

/**
 * Аниме-каст из пикера страницы тайтла: устройство уже подключено (диалог ТВ отработал
 * в TitleCastButton), серия/озвучка/источник — явный выбор пользователя в пикере.
 * Льём выбранный поток на ТВ сразу и открываем пульт уже с этим выбором —
 * никакого авто-выбора первого источника/озвучки/серии.
 */
private fun castAnimeFromPicker(
    context: Context,
    streamUrl: String,
    headers: Map<String, String>,
    qualities: Map<String, String>,
    title: String,
    episodeNumber: Int,
    translationId: String,
    shikimoriId: Int,
    kinopoiskId: Int = 0
) {
    // Без ABR-мастера: приёмник жуёт явный ранг лучше (см. пульт) — пиним лучший.
    val pinned = qualities.keys.maxByOrNull { hd.kinoshka.app.data.model.qualityRank(it) }
    val relay = hd.kinoshka.app.data.cast.CastRelayServer.getInstance()
        .register(context, streamUrl, headers, qualities, pinned)
    if (relay == null) {
        Toast.makeText(context, "Каст: подключите телефон к Wi-Fi", Toast.LENGTH_SHORT).show()
        return
    }
    hd.kinoshka.app.data.cast.CastPlayback.load(relay, title, 0, null)
    val intent = Intent(context, hd.kinoshka.app.ui.player.CastRemoteActivity::class.java).apply {
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_MODE, "anime")
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_SHIKIMORI_ID, shikimoriId)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_ANIME_KP_ID, kinopoiskId)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_ANIME_TITLE, title)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_EPISODE, episodeNumber)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_TRANSLATION_ID, translationId)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_QUALITY, pinned ?: "Auto")
        putExtra(
            hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_ANIME_QUALITIES,
            runCatching { kotlinx.serialization.json.Json.encodeToString(qualities) }.getOrDefault("{}")
        )
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_DISPLAY_TITLE, title)
    }
    runCatching { context.startActivity(intent) }
    Toast.makeText(context, "Трансляция на ТВ", Toast.LENGTH_SHORT).show()
}

/**
 * Кино-каст из пикера страницы тайтла: тот же набор, что плеер отдаёт пульту
 * (эпизоды с сезонами, озвучки, лестница), но источник выбора — пикер «Смотреть»,
 * а не авто-выбор. Поток уже зарезолвлен пикером — льём сразу и открываем пульт.
 */
private fun castMovieFromPicker(
    context: Context,
    result: hd.kinoshka.app.ui.components.MoviePickerResult,
    displayTitle: String,
    kinopoiskId: Int
) {
    val stream = result.stream
    val pinned = stream.qualities.keys.maxByOrNull { hd.kinoshka.app.data.model.qualityRank(it) }
        ?: qualitiesBestFallback(stream.qualities)
    val relay = hd.kinoshka.app.data.cast.CastRelayServer.getInstance()
        .register(context, stream.url, stream.headers, stream.qualities, pinned)
    if (relay == null) {
        Toast.makeText(context, "Каст: подключите телефон к Wi-Fi", Toast.LENGTH_SHORT).show()
        return
    }
    hd.kinoshka.app.data.cast.CastPlayback.load(relay, displayTitle, 0, null)
    val seriesContext = result.seriesContext
    val isSeries = seriesContext != null
    // Эпизоды для пульта: тот же маппинг, что делает плеер (playerEpisodeKey + сезон).
    val filmEpisodes: List<hd.kinoshka.app.data.model.AnimeEpisode> = if (isSeries && seriesContext != null) {
        seriesContext.episodes.map { episode ->
            hd.kinoshka.app.data.model.AnimeEpisode(
                number = episode.playerEpisodeKey,
                title = episode.title?.takeIf { it.isNotBlank() },
                link = episode.playerUrl,
                season = episode.seasonNumber
            )
        }
    } else emptyList()
    // Озвучки для пульта: сериалы — дабы текущей серии из контекста (как seriesTranslationsFor
    // плеера), фильмы — готовые строки QOM из пикера.
    val filmTranslations: List<hd.kinoshka.app.data.model.FlatTranslation> = if (isSeries && seriesContext != null) {
        val current = result.episode ?: seriesContext.currentEpisode
        seriesContext.candidates
            .filter { candidate ->
                !candidate.translationId.isNullOrBlank() && candidate.episodes.any {
                    it.seasonNumber == current.seasonNumber && it.episodeNumber == current.episodeNumber
                }
            }
            .map { dub ->
                val rawTitle = dub.translationTitle ?: dub.translationId.orEmpty()
                val split = hd.kinoshka.app.data.playback.MovieNativeLauncher.splitDubTrack(rawTitle)
                hd.kinoshka.app.data.model.FlatTranslation(
                    source = if (seriesContext.isDirectSource)
                        hd.kinoshka.app.data.source.PlaybackSources.animeSourceTypeForDubId(dub.translationId.orEmpty())
                    else hd.kinoshka.app.data.model.AnimeSourceType.KODIK,
                    translationId = dub.translationId ?: rawTitle,
                    title = if (rawTitle.isBlank()) "Озвучка" else split.first,
                    type = split.second,
                    episodes = emptyList()
                )
            }
            .takeIf { it.isNotEmpty() } ?: result.translations
    } else {
        result.translations
    }
    // Контекст/подготовленные потоки уже лежат в сторах (пикер положил), но дублируем
    // на случай прямого вызова: пульт перефильтровывает озвучки по эпизоду через стор.
    if (kinopoiskId > 0) {
        seriesContext?.let { hd.kinoshka.app.data.model.MovieSeriesContextStore.put(it) }
        if (result.preparedStreams.isNotEmpty()) {
            val merged = hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(kinopoiskId) + result.preparedStreams
            hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.put(kinopoiskId, merged)
        }
    }
    val episodeKey = result.episode?.playerEpisodeKey ?: 1
    val intent = Intent(context, hd.kinoshka.app.ui.player.CastRemoteActivity::class.java).apply {
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_MODE, "film")
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_FILM_KP_ID, kinopoiskId)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_FILM_IS_SERIES, isSeries)
        putExtra(
            hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_FILM_DIRECT,
            seriesContext?.isDirectSource == true
        )
        putExtra(
            hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_FILM_EPISODES,
            runCatching {
                kotlinx.serialization.json.Json.encodeToString(filmEpisodes)
            }.getOrDefault("[]")
        )
        putExtra(
            hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_FILM_TRANSLATIONS,
            runCatching {
                kotlinx.serialization.json.Json.encodeToString(filmTranslations)
            }.getOrDefault("[]")
        )
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_SHIKIMORI_ID, 0)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_ANIME_TITLE, displayTitle)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_EPISODE, episodeKey)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_TRANSLATION_ID, result.currentTranslationId)
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_QUALITY, pinned ?: "Auto")
        putExtra(
            hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_ANIME_QUALITIES,
            runCatching {
                kotlinx.serialization.json.Json.encodeToString(stream.qualities)
            }.getOrDefault("{}")
        )
        putExtra(hd.kinoshka.app.ui.player.CastRemoteActivity.EXTRA_DISPLAY_TITLE, displayTitle)
    }
    runCatching { context.startActivity(intent) }
    Toast.makeText(context, "Трансляция на ТВ", Toast.LENGTH_SHORT).show()
}

/** Лучший ранг лестницы без qualityRank-импорта в сигнатуре: пустая лестница → null. */
private fun qualitiesBestFallback(qualities: Map<String, String>): String? =
    hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC.firstOrNull { qualities.containsKey(it) }
        ?: qualities.keys.firstOrNull()

private fun detailsRoute(id: Int): String = "details/$id"

/**
 * Резервная копия библиотеки в Загрузки (для ТВ-профиля, где нет файлового
 * пикера): Q+ — MediaStore без разрешений, ниже — публичные Загрузки.
 * Возвращает путь для тоста. SAF CreateDocument на Android TV обычно нечем открыть.
 */
private fun exportLibraryToDownloads(context: Context, fileName: String, json: String): Result<String> =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл")
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("Не удалось записать файл")
            "Загрузки/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(json, Charsets.UTF_8)
            file.absolutePath
        }
    }

/** Импорт: самая свежая kinoshka-library-*.json из Загрузок. Возвращает сырой JSON. */
private fun importLatestBackupFromDownloads(context: Context): Result<String> = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val uri = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("kinoshka-library-%.json"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            } else null
        } ?: error("В Загрузках нет резервных копий kinoshka-library-*.json")
        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Не удалось прочитать файл")
    } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = dir.listFiles { f -> f.name.startsWith("kinoshka-library-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
            ?: error("В Загрузках нет резервных копий kinoshka-library-*.json")
        file.readText(Charsets.UTF_8)
    }
}

/**
 * Точечный тактильный тык (0..1): системный shaped-примитив вместо сырого
 * `createOneShot(20мс)` — прямоугольный гул мотора на 20мс воспринимается
 * как жужжание, а тик/клик — как короткий «тык».
 *
 * API 30+: композиция с силой (тик — слабое, клик — среднее/сильное).
 * API 29: предустановленный эффект без силы (железо само держит длительность).
 * API 26–28: короткий oneShot 12мс — точечнее прежних 20мс.
 */
private fun performCrispHaptic(vibrator: android.os.Vibrator, intensity: Float) {
    val x = intensity.coerceIn(0f, 1f)
    if (x <= 0.01f) return
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
        vibrator.areAllPrimitivesSupported(
            android.os.VibrationEffect.Composition.PRIMITIVE_TICK,
            android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
        )
    ) {
        val primitive = if (x < 0.6f) {
            android.os.VibrationEffect.Composition.PRIMITIVE_TICK
        } else {
            android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
        }
        val effect = android.os.VibrationEffect.startComposition()
            .addPrimitive(primitive, x)
            .compose()
        vibrator.vibrate(effect)
    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        // Примитивы композиции поддерживает не каждое железо (напр. OnePlus 7T —
        // mSupportedPrimitives пуст, и система молча дропает эффект как unsupported).
        // Зато предустановленные тик/клик там есть — идём через них.
        val predefined = when {
            x < 0.6f -> android.os.VibrationEffect.EFFECT_TICK
            x < 0.9f -> android.os.VibrationEffect.EFFECT_CLICK
            else -> android.os.VibrationEffect.EFFECT_HEAVY_CLICK
        }
        vibrator.vibrate(android.os.VibrationEffect.createPredefined(predefined))
    } else {
        val amplitude = (30 + 225 * x).toInt()
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(12, amplitude))
    }
}

/**
 * Кастомный глиф пилюли/меню (как до KMP M4): один хелпер на пилюлю HomeScreen
 * и страницу «Навигационное меню» — иконки в обоих местах строго одинаковые.
 */
@Composable
private fun NavGlyph(
    filled: Int,
    outlined: Int,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp = 28.dp
) {
    Icon(
        painter = painterResource(if (selected) filled else outlined),
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

/**
 * Вторичный экран в TV-режиме: тот же телефонный композабл, центрированный на TV-фоне
 * (см. TvSecondaryContainer). В портрете рендерит как есть.
 */
@Composable
private fun TvAdaptiveSecondary(content: @Composable () -> Unit) {
    if (hd.kinoshka.app.ui.tv.rememberTvLayout()) {
        hd.kinoshka.app.ui.tv.TvSecondaryContainer(content = content)
    } else {
        content()
    }
}


private fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** true только на Android TV (leanback): скачивание серий и Загрузки там отключены. */
private fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/**
 * Событие «открыть страницу Загрузки»: ставится из MainActivity при тапе по уведомлению
 * скачивания (cold start через extra, живой процесс — через onNewIntent). KinoApp читает
 * счётчик и навигирует; [androidx.compose.runtime.mutableStateOf] делает изменение наблюдаемым.
 */
object DownloadsNav {
    var openRequest by androidx.compose.runtime.mutableStateOf(0)
}
