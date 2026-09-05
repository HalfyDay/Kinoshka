package hd.kinoshka.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.Modifier
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
import hd.kinoshka.app.data.diagnostics.AppDiagnostics
import java.io.File
import hd.kinoshka.app.data.model.PlaybackSequenceOption
import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.ShikimoriAuthStore
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.data.update.AppUpdateManager
import hd.kinoshka.app.data.update.AppRelease
import hd.kinoshka.app.ui.screens.DownloadsScreen
import hd.kinoshka.app.ui.screens.DiscoverCategory
import hd.kinoshka.app.data.update.UpdateCheckResult
import hd.kinoshka.app.ui.screens.AboutScreen
import hd.kinoshka.app.ui.screens.AnimeCalendarScreen
import hd.kinoshka.app.ui.screens.AnimeFeedScreen
import hd.kinoshka.app.ui.screens.AnimeTopicScreen
import hd.kinoshka.app.ui.screens.DetailsScreen
import hd.kinoshka.app.ui.screens.HentaiDownloadButton
import hd.kinoshka.app.ui.screens.TitleDownloadSheet
import hd.kinoshka.app.ui.screens.AnimePlaybackSelectionScreen
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.toPlayableUriString
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
import hd.kinoshka.app.ui.screens.ProfileScreen
import hd.kinoshka.app.ui.screens.RecommendationFeedScreen
import hd.kinoshka.app.ui.screens.SettingsScreen
import hd.kinoshka.app.ui.screens.ProgressEditorSeed
import hd.kinoshka.app.ui.screens.UserProfileEditorSheet
import hd.kinoshka.app.ui.components.DebugPerformanceOverlay
import hd.kinoshka.app.ui.components.ProfileEditorCoverBackdrop
import hd.kinoshka.app.ui.components.UpdateAvailableSheet
import hd.kinoshka.app.ui.theme.KinoTheme
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.NativePlaybackMode
import kotlinx.coroutines.launch

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
        val updateManager = remember(appContext) { AppUpdateManager(appContext) }
        val updatePrefs = remember(appContext) {
            appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
        }
        val scope = rememberCoroutineScope()
        var isUpdateFlowRunning by remember { mutableStateOf(false) }
        var activeNativePlayerArgs by remember { mutableStateOf<NativePlayerArgs?>(null) }
        var showUpdateSheet by remember { mutableStateOf(false) }
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
                ShikimoriAuthStore(appContext)
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
        val activityLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(activityLifecycleOwner, vm) {
            val resumeObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    vm.refreshAfterPlayerClosed()
                    resumePendingInstallIfReady()
                }
            }
            activityLifecycleOwner.lifecycle.addObserver(resumeObserver)
            onDispose { activityLifecycleOwner.lifecycle.removeObserver(resumeObserver) }
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
                                onOpenProfile = { navController.navigate("profile") },
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenAbout = { navController.navigate("about") },
                                onOpenDownloads = { navController.navigate("downloads") },
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
                                                    HomeTab.MORE -> MainSection.MORE
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
                                    Icon(
                                        painter = painterResource(
                                            if (sel) hd.kinoshka.app.R.drawable.ic_nav_feed_filled
                                            else hd.kinoshka.app.R.drawable.ic_nav_feed_outlined
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp)
                                    )
                                },
                                libraryGlyph = { sel ->
                                    Icon(
                                        painter = painterResource(
                                            if (sel) hd.kinoshka.app.R.drawable.ic_nav_library_filled
                                            else hd.kinoshka.app.R.drawable.ic_nav_library_outlined
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp)
                                    )
                                },
                                discoverGlyph = { sel ->
                                    Icon(
                                        painter = painterResource(
                                            if (sel) hd.kinoshka.app.R.drawable.ic_nav_discover_filled
                                            else hd.kinoshka.app.R.drawable.ic_nav_discover_outlined
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp)
                                    )
                                },
                                moreGlyph = { sel ->
                                    Icon(
                                        painter = painterResource(
                                            if (sel) hd.kinoshka.app.R.drawable.ic_nav_more_filled
                                            else hd.kinoshka.app.R.drawable.ic_nav_more_outlined
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp)
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
                                playbackSequence = vm.uiState.playbackSequence,
                                playerMode = vm.uiState.playerMode,
                                // Платформенные слоты DetailsScreen: скачивание и выбор источника
                                // живут в app (Android-механика), сам экран теперь общий.
                                userStateStore = UserStateStore(LocalContext.current),
                                animeSelectionScreen = { shikimoriId, kinopoiskId, animeTitle, sequence, onDismissRequest, onStreamSelected ->
                                    AnimePlaybackSelectionScreen(
                                        shikimoriId = shikimoriId,
                                        kinopoiskId = kinopoiskId,
                                        animeTitle = animeTitle,
                                        playbackSequence = sequence,
                                        onDismissRequest = onDismissRequest,
                                        onStreamSelected = onStreamSelected
                                    )
                                },
                                downloadSheet = { item, isAnime, onDismiss ->
                                    TitleDownloadSheet(item = item, isAnime = isAnime, onDismiss = onDismiss)
                                },
                                hentaiDownloadButton = { title, kinopoiskId, provider, label, episodeNumber, episodeUrl, headers ->
                                    HentaiDownloadButton(title, kinopoiskId, provider, label, episodeNumber, episodeUrl, headers)
                                },
                                findLocalHentai = { kinopoiskId, providerName, translationId, episodeNumber ->
                                    EpisodeDownloadManager.findLocal(
                                        0, kinopoiskId, providerName, translationId, episodeNumber
                                    )?.toPlayableUriString()
                                }
                            )
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
                                    isAmoled = vm.uiState.themeMode == AppThemeMode.AMOLED
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
                                SettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    selectedThemeMode = vm.uiState.themeMode,
                                    hideRussianContent = vm.uiState.hideRussianContent,
                                    selectedDiscoverTileSize = vm.uiState.discoverTileSize,
                                    selectedLibraryTileSize = vm.uiState.libraryTileSize,
                                    selectedShowFpsCounter = vm.uiState.showFpsCounter,
                                    selectedPlaybackSequence = vm.uiState.playbackSequence,
                                    onPlaybackSequenceSelected = vm::setPlaybackSequence,
                                    selectedPlayerMode = vm.uiState.playerMode,
                                    onPlayerModeSelected = vm::setPlayerMode,
                                    onThemeModeSelected = vm::setThemeMode,
                                    onHideRussianChanged = vm::setHideRussianContent,
                                    onDiscoverTileSizeSelected = vm::setDiscoverTileSize,
                                    onLibraryTileSizeSelected = vm::setLibraryTileSize,
                                    onShowFpsCounterChanged = vm::setShowFpsCounter,
                                    showDebugSettings = BuildConfig.DEBUG,
                                    onOpenPlayerSettings = { navController.navigate("player_settings") }
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

private fun detailsRoute(id: Int): String = "details/$id"

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

/**
 * Событие «открыть страницу Загрузки»: ставится из MainActivity при тапе по уведомлению
 * скачивания (cold start через extra, живой процесс — через onNewIntent). KinoApp читает
 * счётчик и навигирует; [androidx.compose.runtime.mutableStateOf] делает изменение наблюдаемым.
 */
object DownloadsNav {
    var openRequest by androidx.compose.runtime.mutableStateOf(0)
}
