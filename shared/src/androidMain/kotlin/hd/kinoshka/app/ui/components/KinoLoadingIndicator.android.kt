package hd.kinoshka.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** The expressive Material 3 loading animation used consistently by Kino. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
actual fun KinoLoadingIndicator(
    modifier: Modifier,
    size: Dp,
    color: Color,
) {
    LoadingIndicator(
        modifier = modifier.size(size),
        color = color,
    )
}
