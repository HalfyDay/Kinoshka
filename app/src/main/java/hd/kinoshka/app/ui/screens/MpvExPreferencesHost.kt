package hd.kinoshka.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack

/**
 * Хост готовых экранов настроек mpvEx (ui/preferences) внутри навигации Киношки.
 *
 * Экраны mpvEx ходят по собственному стеку через [LocalBackStack] (navigation3): корень —
 * [PreferencesScreen], дальше pushes/pops изнутри самих экранов. Системный «назад» и опустевший
 * стек закрывают весь маршрут через [onExit].
 */
@Composable
fun MpvExPreferencesHost(onExit: () -> Unit) {
    val backStack = remember { NavBackStack<Screen>(PreferencesScreen) }

    CompositionLocalProvider(LocalBackStack provides backStack) {
        // Экран mpvEx снимает с себя последнюю запись кнопкой «назад» в своём топ-баре —
        // пустой стек означает выход из настроек целиком.
        LaunchedEffect(backStack.size) {
            if (backStack.isEmpty()) onExit()
        }
        BackHandler {
            if (backStack.size > 1) backStack.removeLastOrNull() else onExit()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            backStack.lastOrNull()?.Content()
        }
    }
}
