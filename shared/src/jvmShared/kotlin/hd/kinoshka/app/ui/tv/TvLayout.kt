package hd.kinoshka.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Единая точка переключения телефонного и TV-дизайна: TV-стиль включается по ориентации
 * (ширина контейнера больше высоты). Телефон заблокирован в портрете, поэтому там ветка
 * никогда не активируется; ПК/планшет в landscape и телевизоры получают новый дизайн.
 */
@Composable
fun rememberTvLayout(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return size.width > size.height
}
