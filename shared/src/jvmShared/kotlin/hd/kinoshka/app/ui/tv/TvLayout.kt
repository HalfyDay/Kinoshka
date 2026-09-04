package hd.kinoshka.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Форм-фактор большого экрана. До 600dp — телефон, 600..840 — планшет,
 * 840+ — expanded (десктоп/планшет-land), но переключение на TV-дизайн всё
 * равно по ориентации landscape как раньше (телефон заблокирован в портрете,
 * так что телефонная ветка не сломается). Значения повторяют Material 3
 * WindowSizeClass, но без зависимости от androidx.window.
 */
enum class TvWindowSize { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberTvWindowSize(): TvWindowSize {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val widthDp = with(density) { size.width.toDp() }
    return when {
        widthDp < 600.dp -> TvWindowSize.COMPACT
        widthDp < 840.dp -> TvWindowSize.MEDIUM
        else -> TvWindowSize.EXPANDED
    }
}

/**
 * Единая точка переключения телефонного и TV-дизайна: TV-стиль включается по
 * ориентации (ширина > высоты) — как раньше. На планшете в портрете (COMPACT/MEDIUM)
 * остаётся телефонный вид, на планшете-land / ПК / ТВ — новый дизайн.
 * Сохраняем обратную совместимость: все старые вызовы rememberTvLayout() работают.
 */
@Composable
fun rememberTvLayout(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return size.width > size.height
}

/** Удобный алиас: true только на «широком» большом экране (десктоп/TV). */
@Composable
fun rememberIsExpandedTv(): Boolean = rememberTvWindowSize() == TvWindowSize.EXPANDED && rememberTvLayout()

/**
 * Горизонтальные отступы контента: на обычных экранах фиксированные,
 * на широких (EXPANDED) растут вместе с окном — включая ультраширокие
 * мониторы 21:9/32:9, где фиксированные 36dp оставляли бы пустоту по краям.
 */
fun tvHPadFor(maxWidth: Dp, windowSize: TvWindowSize): Dp = when (windowSize) {
    TvWindowSize.COMPACT -> 16.dp
    TvWindowSize.MEDIUM -> 24.dp
    TvWindowSize.EXPANDED -> (maxWidth * 0.03f).coerceIn(36.dp, 72.dp)
}

/**
 * Ширина карточки постера: на широких экранах карточки увеличиваются вместе
 * с окном (ряд всегда заполняет ширину целиком), а не фиксируются на 164dp.
 */
fun tvCardWidthFor(maxWidth: Dp, hPad: Dp, windowSize: TvWindowSize): Dp = when (windowSize) {
    TvWindowSize.COMPACT -> 132.dp
    TvWindowSize.MEDIUM -> 148.dp
    TvWindowSize.EXPANDED -> ((maxWidth - hPad * 2) / 7.2f).coerceIn(164.dp, 236.dp)
}
