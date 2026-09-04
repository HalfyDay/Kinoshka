package hd.kinoshka.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import hd.kinoshka.app.ui.tv.TvSecondaryContainer
import kotlinx.coroutines.launch
import java.io.File
import java.util.Properties

/** Заголовок главного окна; используется и для поиска HWND в Win32. */
const val MAIN_WINDOW_TITLE = "Kino Desktop"

/** Версия desktop-сборки для экрана «О приложении». */
const val DESKTOP_VERSION = "desktop (TV UI)"

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object About : Screen
    data object Calendar : Screen
    data object Feed : Screen
    data class Details(val film: FilmItem) : Screen
    data class Player(val args: PlayerLaunchArgs) : Screen
}

fun main(args: Array<String>) = application {
    val repository = remember { buildRepository() }
    // Общий FilmsViewModel (shared): тот же экран, что и на телефоне.
    val userStateStore = remember { UserStateStoreBase(KinoPrefs.createDefault()) }
    val viewModel = remember { buildViewModel(repository, userStateStore) }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(initialScreen(args, repository)) }
    // Куда возвращаться из деталей: открыто из Новостей — назад на ленту.
    var detailsReturn by remember { mutableStateOf<Screen>(Screen.Home) }
    // Скролл ленты: живёт выше переключения screen, уход в детали позицию не сносит.
    val feedListState = remember { LazyListState() }

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
        // Lampa-тёмная палитра (#1D1F20) через Material3-токены: фон/поверхности нейтральные,
        // текст белый/серый — как на скринах "Главная - TMDB".
        val lampaDark = darkColorScheme(
            background = androidx.compose.ui.graphics.Color(0xFF1D1F20),
            surface = androidx.compose.ui.graphics.Color(0xFF1D1F20),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF242628),
            surfaceContainer = androidx.compose.ui.graphics.Color(0xFF242628),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A2C2D),
            onBackground = androidx.compose.ui.graphics.Color.White,
            onSurface = androidx.compose.ui.graphics.Color.White,
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
        )
        MaterialTheme(colorScheme = lampaDark) {
            // Surface задаёт LocalContentColor (= onBackground): без него текст вне
            // карточек (заголовки секций и т.п.) получает чёрный по умолчанию и
            // исчезает на тёмном фоне.
            Surface(modifier = Modifier.fillMaxSize()) {
                var drawerOpen by remember { mutableStateOf(false) }
                when (val current = screen) {
                is Screen.Home -> PcLampaOverview(
                    state = viewModel.uiState,
                    onOpenFilm = { film ->
                        detailsReturn = Screen.Home
                        screen = Screen.Details(film)
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
                    onQueryChange = viewModel::onQueryChange,
                    onSubmitSearch = viewModel::submitSearch,
                    onRetry = viewModel::retryHome,
                    onLoadMore = viewModel::loadMore,
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
                        screen = Screen.Player(
                            PlayerLaunchArgs(
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
                        )
                    },
                    userStateStore = userStateStore
                )
                is Screen.Player -> PlayerScreen(
                    args = current.args,
                    userStateStore = userStateStore,
                    onBack = {
                        screen = if (current.args.kinopoiskId > 0) {
                            Screen.Details(
                                FilmItem(current.args.kinopoiskId, current.args.title, null, null, null, null)
                            )
                        } else {
                            Screen.Home
                        }
                    },
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
            }
            }
        }
    }
}

private fun initialScreen(args: Array<String>, repository: FilmsRepository): Screen {
    // Дебаг-прогон конкретного экрана: KINO_SCREEN=settings|about|calendar|feed (или первым аргументом).
    when (flag(args)) {
        "settings" -> return Screen.Settings
        "about" -> return Screen.About
        "calendar" -> return Screen.Calendar
        "feed" -> return Screen.Feed
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
                    kotlinx.coroutines.runBlocking {
                        val d = repository.details(kpId)
                        FilmItem(d.kinopoiskId, d.nameRu, d.nameOriginal, d.posterUrlPreview, d.ratingKinopoisk, d.year)
                    }
                }.getOrNull()
            } ?: popular.firstOrNull { (it.year ?: 0) in 1950..2025 }
        }
        println("Kino: выбранный фильм = ${picked?.nameRu} (kp=${picked?.kinopoiskId}, ${picked?.year})")
        val film = picked ?: FilmItem(0, "Демо", null, null, null, null)
        // С реальным kp-id играем через полный PENDING-резолв (гонка Kodik↔ddbb), без — демо-клип.
        return Screen.Player(
            PlayerLaunchArgs(
                streamUrl = "",
                title = film.nameRu ?: film.nameOriginal ?: "Демо",
                kinopoiskId = film.kinopoiskId,
                sourceType = if (film.kinopoiskId > 0) "PENDING" else "DEMO"
            )
        )
    }
    if (flag(args) == "details") {
        val first = runCatching {
            kotlinx.coroutines.runBlocking { repository.popular(page = 1) }
        }.getOrNull()?.firstOrNull()
        if (first != null) return Screen.Details(first)
    }
    return Screen.Home
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
