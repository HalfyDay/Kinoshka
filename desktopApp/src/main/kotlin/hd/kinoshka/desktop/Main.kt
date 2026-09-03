package hd.kinoshka.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.local.KinoPrefs
import hd.kinoshka.app.data.local.UserStateStoreBase
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.ui.screens.DetailsScreen
import hd.kinoshka.app.ui.screens.FilmsViewModel
import hd.kinoshka.app.ui.screens.HomeScreen
import kotlinx.coroutines.launch
import java.io.File
import java.util.Properties

/** Заголовок главного окна; используется и для поиска HWND в Win32. */
const val MAIN_WINDOW_TITLE = "Kino Desktop"

sealed interface Screen {
    data object Home : Screen
    data class Details(val film: FilmItem) : Screen
    data class Player(val film: FilmItem) : Screen
}

fun main(args: Array<String>) = application {
    val repository = remember { buildRepository() }
    // Общий FilmsViewModel (shared): тот же экран, что и на телефоне.
    val userStateStore = remember { UserStateStoreBase(KinoPrefs.createDefault()) }
    val viewModel = remember { buildViewModel(repository, userStateStore) }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(initialScreen(args, repository)) }

    Window(onCloseRequest = ::exitApplication, title = MAIN_WINDOW_TITLE) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            when (val current = screen) {
                is Screen.Home -> HomeScreen(
                    state = viewModel.uiState,
                    onQueryChange = viewModel::onQueryChange,
                    onInstantSearch = viewModel::onSearchQueryChanged,
                    onSubmitSearch = viewModel::submitSearch,
                    onRetry = viewModel::retryHome,
                    onTabSelected = viewModel::onTabSelected,
                    onContentTypeSelected = viewModel::onContentTypeSelected,
                    onOpenFilm = { film -> screen = Screen.Details(film) },
                    onOpenHistoryFilm = { id ->
                        // Из истории приходит только kp-Id — детали догружаем.
                        scope.launch {
                            runCatching { repository.details(id) }.onSuccess { d ->
                                screen = Screen.Details(
                                    FilmItem(
                                        d.kinopoiskId, d.nameRu, d.nameOriginal,
                                        d.posterUrlPreview, d.ratingKinopoisk, d.year
                                    )
                                )
                            }
                        }
                    },
                    onDiscoverCategorySelected = viewModel::onDiscoverCategorySelected,
                    onLoadMore = viewModel::loadMore,
                    onRemoveFromHistory = viewModel::removeFromHistory,
                    // Разделы, для которых на desktop ещё нет экранов (M5+): no-op.
                    onOpenProfile = {},
                    onOpenSettings = {},
                    onOpenAbout = {},
                    onUpdateFilters = viewModel::updateFilters,
                    onToggleFilterSheet = viewModel::setShowFilterSheet,
                    onLibrarySortSelected = viewModel::setLibrarySortType,
                    librarySortReversed = viewModel.uiState.librarySortReversed,
                    onLibrarySortReversedChanged = viewModel::setLibrarySortReversed,
                    onHentaiVisibilityChanged = viewModel::setHentaiVisibleInLibrary,
                    onRemoveSearchHistory = viewModel::removeSearchQueryFromHistory,
                    onClearSearchHistory = viewModel::clearSearchHistory
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
                    onBack = { screen = Screen.Home },
                    // Слоты скачивания/выбора источника и нативный плеер — null: «Смотреть»
                    // падает в веб-фолбэк, пока не готов общий плеер (M6).
                    userStateStore = userStateStore
                )
                is Screen.Player -> PlayerScreen(
                    film = current.film,
                    onBack = { screen = Screen.Details(current.film) },
                )
            }
        }
    }
}

private fun initialScreen(args: Array<String>, repository: FilmsRepository): Screen {
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
        return Screen.Player(
            picked ?: FilmItem(0, "Демо", null, null, null, null)
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
