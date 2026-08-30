package hd.kinoshka.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.repo.FilmsRepository
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
    var screen by remember { mutableStateOf(initialScreen(args, repository)) }

    Window(onCloseRequest = ::exitApplication, title = MAIN_WINDOW_TITLE) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            when (val current = screen) {
                is Screen.Home -> HomeScreen(
                    repository = repository,
                    onOpen = { screen = Screen.Details(it) },
                )
                is Screen.Details -> DetailsScreen(
                    film = current.film,
                    repository = repository,
                    onBack = { screen = Screen.Home },
                    onWatch = { screen = Screen.Player(current.film) },
                )
                is Screen.Player -> PlayerScreen(
                    film = current.film,
                    onBack = { screen = Screen.Details(current.film) },
                )
            }
        }
    }
}

/**
 * Быстрая проверка экранов: KINO_SCREEN=details|player (env) или первый аргумент.
 * Без флага — главная. Env удобнее: JavaExec-задача run наследует окружение Gradle.
 */
private fun initialScreen(args: Array<String>, repository: FilmsRepository): Screen {
    val flag = args.firstOrNull() ?: System.getenv("KINO_SCREEN")
    if (flag == "player") {
        return Screen.Player(FilmItem(0, "Демо", null, null, null, null))
    }
    if (flag == "details") {
        val first = runCatching {
            kotlinx.coroutines.runBlocking { repository.popular(page = 1) }
        }.getOrNull()?.firstOrNull()
        if (first != null) return Screen.Details(first)
    }
    return Screen.Home
}

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
