package hd.kinoshka.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.local.KinoPrefs
import hd.kinoshka.app.data.local.UserStateStoreBase
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.ui.screens.AboutScreen
import hd.kinoshka.app.ui.screens.AnimeCalendarScreen
import hd.kinoshka.app.ui.screens.AnimeFeedScreen
import hd.kinoshka.app.ui.screens.DetailsScreen
import hd.kinoshka.app.ui.screens.FilmsViewModel
import hd.kinoshka.app.ui.screens.SettingsScreen
import hd.kinoshka.app.ui.tv.LocalKeyboardNavigation
import hd.kinoshka.app.ui.tv.TvSecondaryContainer
import hd.kinoshka.app.ui.tv.inputModeTracker
import kotlinx.coroutines.launch
import java.io.File
import java.util.Properties

/** Заголовок главного окна; используется и для поиска HWND в Win32. */
const val MAIN_WINDOW_TITLE = "Kino Desktop"

/** Версия desktop-сборки для экрана «О приложении». */
const val DESKTOP_VERSION = "desktop"

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object About : Screen
    data object Calendar : Screen
    data object Feed : Screen
    data object Profile : Screen
    data object Downloads : Screen
    data class Details(val film: FilmItem) : Screen
}

fun main(args: Array<String>) = application {
    val repository = remember { buildRepository() }
    // Общий FilmsViewModel (shared): та же модель состояния, что и на телефоне.
    val userStateStore = remember { UserStateStoreBase(KinoPrefs.createDefault()) }
    val viewModel = remember { buildViewModel(repository, userStateStore) }
    val scope = rememberCoroutineScope()
    val (initial, initialPlayer) = remember { initialScreenAndPlayer(args, repository) }
    var screen by remember { mutableStateOf(initial) }
    // Плеер открывается ПОВЕРХ текущего экрана в собственном окне: главное окно
    // сохраняет состояние (детали/главную) и показывается снова после закрытия.
    var playerArgs by remember { mutableStateOf(initialPlayer) }
    // Куда возвращаться из деталей: открыто из Новостей — назад на ленту.
    var detailsReturn by remember { mutableStateOf<Screen>(Screen.Home) }
    // Скролл ленты: живёт выше переключения screen, уход в детали позицию не сносит.
    val feedListState = remember { LazyListState() }
    // Рамка фокуса только для клавиатуры/пульта: стрелки включают режим, мышь гасит.
    val keyboardNavigation = remember { mutableStateOf(false) }

    fun openDetailsById(targetId: Int) {
        scope.launch {
            runCatching { repository.details(targetId) }.onSuccess { d ->
                screen = Screen.Details(
                    FilmItem(d.kinopoiskId, d.nameRu, d.nameOriginal, d.posterUrlPreview, d.ratingKinopoisk, d.year)
                )
            }
        }
    }

    val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = MAIN_WINDOW_TITLE,
        state = windowState,
    ) {
        KinoDesktopTheme(themeMode = viewModel.uiState.themeMode) {
            // Surface задаёт LocalContentColor (= onBackground): без него текст вне
            // карточек получает чёрный по умолчанию и исчезает на тёмном фоне.
            CompositionLocalProvider(LocalKeyboardNavigation provides keyboardNavigation.value) {
                Surface(modifier = Modifier.fillMaxSize().inputModeTracker(keyboardNavigation)) {
                    // KINO_DRAWER=1 — дебаг: боковое меню сразу открыто (скриншот-проверка).
                    var drawerOpen by remember { mutableStateOf(System.getenv("KINO_DRAWER") == "1") }
                    when (val current = screen) {
                        is Screen.Home -> PcLampaOverview(
                            state = viewModel.uiState,
                            onOpenFilm = { film ->
                                detailsReturn = Screen.Home
                                screen = Screen.Details(film)
                            },
                            onOpenHistoryFilm = { targetId ->
                                detailsReturn = Screen.Home
                                openDetailsById(targetId)
                            },
                            drawerOpen = drawerOpen,
                            onMenuToggle = { drawerOpen = !drawerOpen },
                            isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                            onBack = { screen = Screen.Home },
                            onFullscreenToggle = {
                                windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Fullscreen
                                }
                            },
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenAbout = { screen = Screen.About },
                            onOpenFeed = { screen = Screen.Feed },
                            onOpenCalendar = { screen = Screen.Calendar },
                            onOpenDownloads = { screen = Screen.Downloads },
                            onOpenProfile = { screen = Screen.Profile },
                            onQueryChange = viewModel::onQueryChange,
                            onSubmitSearch = viewModel::submitSearch,
                            onContentTypeSelected = viewModel::onContentTypeSelected,
                            onDiscoverCategorySelected = viewModel::onDiscoverCategorySelected,
                            onDiscoverReset = viewModel::resetDiscover,
                            onSearchGenre = { name, isAnime, title -> viewModel.searchGenre(name, isAnime, title) },
                            onRetry = viewModel::retryHome,
                            onLoadMore = viewModel::loadMore,
                            onOpenFilmEditor = {},
                            onRemoveFromHistory = viewModel::removeFromHistory,
                            onUpdateFilters = viewModel::updateFilters,
                            onToggleFilterSheet = viewModel::setShowFilterSheet,
                            onOpenTopic = { screen = Screen.Feed },
                            onRetryOverview = viewModel::retryOverview,
                            onSeeAll = viewModel::openOverviewSeeAll,
                        )
                        is Screen.Details -> DetailsScreen(
                            filmId = current.film.kinopoiskId,
                            state = viewModel.detailsState,
                            load = viewModel::loadDetails,
                            onWatch = viewModel::onWatch,
                            onSaveUserProfile = viewModel::saveUserProfile,
                            onOpenUrl = ::openInBrowser,
                            onOpenFilm = { targetId ->
                                // Детали догружаются по kp-Id, экран сам подтянет данные.
                                scope.launch {
                                    runCatching { repository.details(targetId) }.onSuccess { d ->
                                        screen = Screen.Details(
                                            FilmItem(
                                                d.kinopoiskId, d.nameRu, d.nameOriginal,
                                                d.posterUrlPreview, d.ratingKinopoisk, d.year
                                            )
                                        )
                                    }
                                }
                            },
                            onBack = { screen = detailsReturn },
                            // Слоты скачивания/выбора источника — null; нативный плеер: общий
                            // DetailsScreen передаёт полный payload, mpv играет его на ПК.
                            onOpenNativePlayer = { streamUrl, headers, qualities, title, epNum, epTitle, shikimoriId, kinopoiskId, srcType, episodes, translations, trId, seriesContext ->
                                playerArgs = PlayerLaunchArgs(
                                    streamUrl = streamUrl,
                                    headers = headers,
                                    qualities = qualities,
                                    title = title,
                                    episodeNumber = epNum,
                                    shikimoriId = shikimoriId,
                                    kinopoiskId = kinopoiskId,
                                    sourceType = srcType,
                                    episodes = episodes,
                                    translations = translations,
                                    currentTranslationId = trId,
                                    seriesContext = seriesContext
                                )
                            },
                            userStateStore = userStateStore
                        )
                        is Screen.Settings -> TvSecondaryContainer {
                            SettingsScreen(
                                onBack = { screen = Screen.Home },
                                selectedThemeMode = viewModel.uiState.themeMode,
                                hideRussianContent = viewModel.uiState.hideRussianContent,
                                selectedDiscoverTileSize = viewModel.uiState.discoverTileSize,
                                selectedLibraryTileSize = viewModel.uiState.libraryTileSize,
                                selectedShowFpsCounter = viewModel.uiState.showFpsCounter,
                                selectedPlaybackSequence = viewModel.uiState.playbackSequence,
                                onPlaybackSequenceSelected = viewModel::setPlaybackSequence,
                                selectedPlayerMode = viewModel.uiState.playerMode,
                                onPlayerModeSelected = viewModel::setPlayerMode,
                                onThemeModeSelected = viewModel::setThemeMode,
                                onHideRussianChanged = viewModel::setHideRussianContent,
                                onDiscoverTileSizeSelected = viewModel::setDiscoverTileSize,
                                onLibraryTileSizeSelected = viewModel::setLibraryTileSize,
                                onShowFpsCounterChanged = viewModel::setShowFpsCounter,
                                showDebugSettings = false
                            )
                        }
                        is Screen.About -> TvSecondaryContainer {
                            AboutScreen(
                                onBack = { screen = Screen.Home },
                                updateStatusText = "Проверка обновлений — в мобильной версии",
                                isUpdateCheckRunning = false,
                                onCheckUpdates = {},
                                onOpenGithub = { openInBrowser("https://github.com/HalfyDay/Kinoshka") },
                                onOpenTelegram = { openInBrowser("https://t.me/Kinoshka_HalfDay") },
                                onOpenShikimori = { openInBrowser("https://shikimori.io") },
                                appVersion = DESKTOP_VERSION
                            )
                        }
                        is Screen.Calendar -> TvSecondaryContainer {
                            AnimeCalendarScreen(
                                calendarItems = viewModel.uiState.calendarItems,
                                loading = viewModel.uiState.calendarLoading,
                                onBack = { screen = Screen.Home },
                                onOpenAnime = { targetId ->
                                    detailsReturn = Screen.Home
                                    openDetailsById(targetId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
                                }
                            )
                        }
                        is Screen.Feed -> TvSecondaryContainer {
                            AnimeFeedScreen(
                                topics = viewModel.uiState.topics,
                                loading = viewModel.uiState.topicsLoading,
                                onBack = { screen = Screen.Home },
                                onOpenAnime = { targetId ->
                                    detailsReturn = Screen.Feed
                                    openDetailsById(targetId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
                                },
                                loadComments = viewModel::loadTopicComments,
                                onOpenStudio = { studioId, studioName ->
                                    viewModel.searchStudio(studioId, studioName)
                                    screen = Screen.Home
                                },
                                listState = feedListState
                            )
                        }
                        is Screen.Profile -> TvSecondaryContainer {
                            ProfileScreen(
                                avatar = viewModel.uiState.profileAvatar,
                                shikimoriAuthState = viewModel.uiState.shikimoriAuthState,
                                library = viewModel.uiState.library,
                                onBack = { screen = Screen.Home },
                                onExportLibrary = { viewModel.exportLibraryJson() },
                                onImportLibrary = { raw -> viewModel.importLibraryJson(raw) },
                                onOpenSettings = { screen = Screen.Settings },
                                onOpenAbout = { screen = Screen.About },
                            )
                        }
                        is Screen.Downloads -> TvSecondaryContainer {
                            DownloadsScreen(onBack = { screen = Screen.Home })
                        }
                    }
                }
            }
        }
    }

    // Окно плеера mpvEx-стиля: полноэкранное, поверх главного окна.
    playerArgs?.let { args ->
        PlayerWindow(
            args = args,
            userStateStore = userStateStore,
            onClose = {
                playerArgs = null
                viewModel.refreshAfterPlayerClosed()
            },
        )
    }
}

/** Стартовый экран + плеер дебаг-запуска (KINO_SCREEN=player). */
private fun initialScreenAndPlayer(args: Array<String>, repository: FilmsRepository): Pair<Screen, PlayerLaunchArgs?> {
    // Дебаг-прогон конкретного экрана: KINO_SCREEN=settings|about|calendar|feed|profile|downloads
    // (или первым аргументом).
    when (flag(args)) {
        "settings" -> return Screen.Settings to null
        "about" -> return Screen.About to null
        "calendar" -> return Screen.Calendar to null
        "feed" -> return Screen.Feed to null
        "profile" -> return Screen.Profile to null
        "downloads" -> return Screen.Downloads to null
    }
    if (flag(args) == "player") {
        // Для проверки реального потока нужен ВЫШЕДШИЙ фильм: у анонсов (год >= текущего)
        // Kodik ещё ничего не индексировал — резолвер честно вернёт NO_MATCHING_RESULTS.
        // KINO_KP_ID=<id> задаёт конкретный фильм.
        val popular = runCatching {
            kotlinx.coroutines.runBlocking { repository.popular(page = 1) }
        }.getOrNull().orEmpty()
        val picked = if (System.getenv("KINO_DEMO") == "1") {
            FilmItem(0, "Демо", null, null, null, null)
        } else {
            System.getenv("KINO_KP_ID")?.trim()?.toIntOrNull()?.let { kpId ->
                popular.firstOrNull { it.kinopoiskId == kpId } ?: runCatching {
                    kotlinx.coroutines.runBlocking { repository.details(kpId) }.let { d ->
                        FilmItem(d.kinopoiskId, d.nameRu, d.nameOriginal, d.posterUrlPreview, d.ratingKinopoisk, d.year)
                    }
                }.getOrNull()
            } ?: popular.firstOrNull { (it.year ?: 0) in 1950..2025 }
        }
        println("Kino: выбранный фильм = ${picked?.nameRu} (kp=${picked?.kinopoiskId}, ${picked?.year})")
        val film = picked ?: FilmItem(0, "Демо", null, null, null, null)
        val playerArgs = if (film.kinopoiskId > 0) {
            // Полный запрос резолва: детали дают год/imdb/оригинальное название — без них
            // identity-матч каталога Kodik не работает (NO_MATCHING_RESULTS).
            val details = runCatching {
                kotlinx.coroutines.runBlocking { repository.details(film.kinopoiskId) }
            }.getOrNull()
            PlayerLaunchArgs(
                streamUrl = "",
                title = details?.nameRu ?: film.nameRu ?: film.nameOriginal ?: "Демо",
                kinopoiskId = film.kinopoiskId,
                sourceType = "PENDING",
                imdbId = details?.imdbId,
                year = details?.year ?: details?.startYear ?: film.year,
                nameEn = details?.nameEn,
                originalTitle = details?.nameOriginal,
                seriesKind = details?.serial == true,
            )
        } else {
            PlayerLaunchArgs(
                streamUrl = "",
                title = film.nameRu ?: "Демо",
                sourceType = "DEMO"
            )
        }
        return Screen.Home to playerArgs
    }
    if (flag(args) == "details") {
        val first = runCatching {
            kotlinx.coroutines.runBlocking { repository.popular(page = 1) }
        }.getOrNull()?.firstOrNull()
        if (first != null) return Screen.Details(first) to null
    }
    return Screen.Home to null
}

private fun flag(args: Array<String>): String =
    args.firstOrNull() ?: System.getenv("KINO_SCREEN") ?: ""

private fun buildRepository(): FilmsRepository {
    // Рабочая директория у run-задачи — папка модуля desktopApp, поэтому ищем
    // local.properties и в корне проекта тоже.
    val localProperties = listOf(File("local.properties"), File("../local.properties"))
        .firstOrNull { it.exists() }
    val apiKey = System.getenv("KP_API_KEY")
        ?: localProperties?.inputStream()?.use { Properties().apply { load(it) } }
            ?.getProperty("KP_API_KEY")?.trim().orEmpty()
    val cacheDir = File(System.getProperty("user.home"), ".kino-desktop").apply { mkdirs() }
    return FilmsRepository(ApiClient.kinopoiskApi(cacheDir, apiKey))
}

private fun buildViewModel(repository: FilmsRepository, userStateStore: UserStateStoreBase): FilmsViewModel {
    val cacheDir = File(System.getProperty("user.home"), ".kino-desktop").apply { mkdirs() }
    return FilmsViewModel(
        repository = repository,
        animeRepository = AnimeRepository(ApiClient.shikimoriApi(cacheDir)),
        userStateStore = userStateStore
    )
}

private fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        .onFailure { println("[Kino] Не удалось открыть ссылку: $url — ${it.message}") }
}
