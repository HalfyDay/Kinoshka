package hd.kinoshka.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.data.update.AppUpdateManager
import hd.kinoshka.app.data.update.UpdateCheckResult
import hd.kinoshka.app.ui.screens.AboutScreen
import hd.kinoshka.app.ui.screens.DetailsScreen
import hd.kinoshka.app.ui.screens.FilmsViewModel
import hd.kinoshka.app.ui.screens.FilmsViewModelFactory
import hd.kinoshka.app.ui.screens.HomeScreen
import hd.kinoshka.app.ui.screens.InAppWebScreen
import hd.kinoshka.app.ui.screens.ProfileScreen
import hd.kinoshka.app.ui.screens.SettingsScreen
import hd.kinoshka.app.ui.components.DebugPerformanceOverlay
import hd.kinoshka.app.ui.theme.KinoTheme
import kotlinx.coroutines.launch

@Composable
fun KinoApp() {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val updateManager = remember(appContext) { AppUpdateManager(appContext) }
    val updatePrefs = remember(appContext) {
        appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    var isUpdateFlowRunning by remember { mutableStateOf(false) }
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
                                Toast.makeText(
                                    appContext,
                                    "Доступна версия ${checkResult.release.tagName}. Откройте «О приложении».",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

                            Toast.makeText(
                                appContext,
                                "Найдена версия ${checkResult.release.tagName}. Скачиваю APK...",
                                Toast.LENGTH_LONG
                            ).show()

                            val downloadResult = updateManager.downloadApk(checkResult.release)
                            downloadResult.fold(
                                onSuccess = { apkFile ->
                                    if (!updateManager.canInstallPackages()) {
                                        setUpdateStatus("APK скачан. Разрешите установку из этого источника.")
                                        Toast.makeText(
                                            appContext,
                                            "Разрешите установку из этого источника и повторите обновление.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        updateManager.openUnknownSourcesSettings()
                                        return@fold
                                    }

                                    val installResult = updateManager.launchApkInstaller(apkFile)
                                    if (installResult.isFailure) {
                                        setUpdateStatus("Не удалось запустить установку APK.")
                                        Toast.makeText(
                                            appContext,
                                            "Не удалось запустить установку APK.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        openInBrowser(checkResult.release.htmlUrl)
                                    } else {
                                        setUpdateStatus("Установка версии ${checkResult.release.tagName} запущена.")
                                    }
                                },
                                onFailure = { error ->
                                    setUpdateStatus("Не удалось скачать APK.")
                                    Toast.makeText(
                                        appContext,
                                        error.message ?: "Не удалось скачать APK.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    openInBrowser(checkResult.release.htmlUrl)
                                }
                            )
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
            FilmsRepository(ApiClient.kinopoiskApi(appContext)),
            UserStateStore(appContext)
        )
    )

    KinoTheme(themeMode = vm.uiState.themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(navController = navController, startDestination = "home") {
                    composable(
                        route = "home",
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(durationMillis = 210))
                        }
                    ) {
                        HomeScreen(
                            state = vm.uiState,
                            onQueryChange = vm::onQueryChange,
                            onSubmitSearch = vm::submitSearch,
                            onRetry = vm::retryHome,
                            onTabSelected = vm::onTabSelected,
                            onOpenFilm = { film -> navController.navigate(detailsRoute(film.kinopoiskId)) },
                            onOpenHistoryFilm = { id -> navController.navigate(detailsRoute(id)) },
                            onDiscoverCategorySelected = vm::onDiscoverCategorySelected,
                            onLoadMore = vm::loadMore,
                            onRemoveFromHistory = vm::removeFromHistory,
                            onOpenProfile = { navController.navigate("profile") },
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenAbout = { navController.navigate("about") }
                        )
                    }
                    composable(
                        route = "details/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.IntType }),
                        enterTransition = {
                            fadeIn(animationSpec = tween(durationMillis = 190))
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(durationMillis = 160))
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(durationMillis = 170))
                        },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(durationMillis = 150))
                        }
                    ) { backStackEntry ->
                        val id = backStackEntry.arguments?.getInt("id") ?: return@composable
                        DetailsScreen(
                            filmId = id,
                            state = vm.detailsState,
                            load = vm::loadDetails,
                            onWatch = vm::onWatch,
                            onSaveUserProfile = vm::saveUserProfile,
                            onOpenUrl = { rawUrl -> navController.navigate("web/${Uri.encode(rawUrl)}") },
                            onOpenFilm = { targetId -> navController.navigate(detailsRoute(targetId)) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("profile") {
                        ProfileScreen(
                            avatar = vm.uiState.profileAvatar,
                            library = vm.uiState.library,
                            onBack = { navController.popBackStack() },
                            onAvatarSelected = vm::setProfileAvatar
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            selectedThemeMode = vm.uiState.themeMode,
                            hideRussianContent = vm.uiState.hideRussianContent,
                            selectedDiscoverTileSize = vm.uiState.discoverTileSize,
                            selectedLibraryTileSize = vm.uiState.libraryTileSize,
                            selectedShowFpsCounter = vm.uiState.showFpsCounter,
                            onThemeModeSelected = vm::setThemeMode,
                            onHideRussianChanged = vm::setHideRussianContent,
                            onDiscoverTileSizeSelected = vm::setDiscoverTileSize,
                            onLibraryTileSizeSelected = vm::setLibraryTileSize,
                            onShowFpsCounterChanged = vm::setShowFpsCounter,
                            onExportLibrary = vm::exportLibraryJson,
                            onImportLibrary = vm::importLibraryJson
                        )
                    }
                    composable("about") {
                        AboutScreen(
                            onBack = { navController.popBackStack() },
                            updateStatusText = updateStatusText,
                            isUpdateCheckRunning = isUpdateFlowRunning,
                            onCheckUpdates = { runUpdateCheck(true, true) },
                            onOpenGithub = { openInBrowser("https://github.com/HalfyDay/Kinoshka") },
                            onOpenTelegram = { openInBrowser("https://t.me/Kinoshka_HalfDay") },
                            onOpenShikimori = { openInBrowser("https://shiki.one") }
                        )
                    }
                    composable(
                        route = "web/{url}",
                        arguments = listOf(navArgument("url") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val encodedUrl = backStackEntry.arguments?.getString("url") ?: return@composable
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
            }
        }
    }
}

private const val GITHUB_RELEASES_URL_DEFAULT = "https://github.com/HalfyDay/Kinoshka/releases"
private const val UPDATE_PREFS_NAME = "update_preferences"
private const val KEY_LAST_AUTO_CHECK_AT = "last_auto_check_at"
private const val KEY_LAST_UPDATE_STATUS = "last_update_status"
private const val AUTO_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

private fun detailsRoute(id: Int): String = "details/$id"
