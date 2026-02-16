package hd.kinoshka.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import hd.kinoshka.app.ui.theme.KinoTheme
import kotlinx.coroutines.launch

@Composable
fun KinoApp() {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val updateManager = remember(appContext) { AppUpdateManager(appContext) }
    val scope = rememberCoroutineScope()
    var isUpdateFlowRunning by remember { mutableStateOf(false) }
    val releasesUrl = BuildConfig.GITHUB_RELEASES_URL
        .takeIf { it.isNotBlank() }
        ?: "https://github.com/HalfyDay/Kinoshka/releases"

    val openInBrowser: (String) -> Unit = { url ->
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    val vm: FilmsViewModel = viewModel(
        factory = FilmsViewModelFactory(
            FilmsRepository(ApiClient.kinopoiskApi(appContext)),
            UserStateStore(appContext)
        )
    )

    KinoTheme(themeMode = vm.uiState.themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        state = vm.uiState,
                        onQueryChange = vm::onQueryChange,
                        onSubmitSearch = vm::submitSearch,
                        onRetry = vm::retryHome,
                        onTabSelected = vm::onTabSelected,
                        onOpenFilm = { film ->
                            navController.navigate("details/${film.kinopoiskId}")
                        },
                        onOpenHistoryFilm = { id ->
                            navController.navigate("details/$id")
                        },
                        onDiscoverCategorySelected = vm::onDiscoverCategorySelected,
                        onLoadMore = vm::loadMore,
                        onRemoveFromHistory = vm::removeFromHistory,
                        onOpenProfile = { navController.navigate("profile") },
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenAbout = { navController.navigate("about") },
                        onOpenUpdates = {
                            if (isUpdateFlowRunning) {
                                Toast.makeText(
                                    appContext,
                                    "Проверка обновления уже выполняется.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                scope.launch {
                                    isUpdateFlowRunning = true
                                    try {
                                        Toast.makeText(
                                            appContext,
                                            "Проверяю наличие новой версии...",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        when (
                                            val checkResult = updateManager.checkForUpdate(
                                                releasesUrl = releasesUrl,
                                                currentVersionName = BuildConfig.VERSION_NAME
                                            )
                                        ) {
                                            is UpdateCheckResult.UpToDate -> {
                                                Toast.makeText(
                                                    appContext,
                                                    "Установлена актуальная версия (${BuildConfig.VERSION_NAME}).",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }

                                            is UpdateCheckResult.NoApkAsset -> {
                                                Toast.makeText(
                                                    appContext,
                                                    "В релизе ${checkResult.latestTag} нет APK. Открываю Releases.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                openInBrowser(checkResult.htmlUrl)
                                            }

                                            is UpdateCheckResult.Error -> {
                                                Toast.makeText(
                                                    appContext,
                                                    checkResult.message,
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                openInBrowser(releasesUrl)
                                            }

                                            is UpdateCheckResult.UpdateAvailable -> {
                                                Toast.makeText(
                                                    appContext,
                                                    "Найдена версия ${checkResult.release.tagName}. Скачиваю APK...",
                                                    Toast.LENGTH_LONG
                                                ).show()

                                                val downloadResult = updateManager.downloadApk(checkResult.release)
                                                downloadResult.fold(
                                                    onSuccess = { apkFile ->
                                                        if (!updateManager.canInstallPackages()) {
                                                            Toast.makeText(
                                                                appContext,
                                                                "Разрешите установку из этого источника и повторите обновление.",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                            updateManager.openUnknownSourcesSettings()
                                                            return@fold
                                                        }

                                                        val installResult =
                                                            updateManager.launchApkInstaller(apkFile)
                                                        if (installResult.isFailure) {
                                                            Toast.makeText(
                                                                appContext,
                                                                "Не удалось запустить установку APK.",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                            openInBrowser(checkResult.release.htmlUrl)
                                                        }
                                                    },
                                                    onFailure = { error ->
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
                    )
                }
                composable(
                    route = "details/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.IntType })
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getInt("id") ?: return@composable
                    DetailsScreen(
                        filmId = id,
                        state = vm.detailsState,
                        load = vm::loadDetails,
                        onWatch = vm::onWatch,
                        onSaveUserProfile = vm::saveUserProfile,
                        onOpenUrl = { rawUrl ->
                            val encoded = Uri.encode(rawUrl)
                            navController.navigate("web/$encoded")
                        },
                        onOpenFilm = { targetId ->
                            navController.navigate("details/$targetId")
                        },
                        onBack = {
                            navController.popBackStack()
                        }
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
                        selectedTileSize = vm.uiState.tileSize,
                        onThemeModeSelected = vm::setThemeMode,
                        onHideRussianChanged = vm::setHideRussianContent,
                        onTileSizeSelected = vm::setTileSize,
                        onExportLibrary = vm::exportLibraryJson,
                        onImportLibrary = vm::importLibraryJson
                    )
                }
                composable("about") {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = "web/{url}",
                    arguments = listOf(navArgument("url") { type = NavType.StringType })
                ) { backStackEntry ->
                    val encodedUrl = backStackEntry.arguments?.getString("url") ?: return@composable
                    val decodedUrl = Uri.decode(encodedUrl)
                    InAppWebScreen(url = decodedUrl)
                }
            }
        }
    }
}

