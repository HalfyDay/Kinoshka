package hd.kinoshka.app.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
actual fun KinoLoadingIndicator(
    modifier: Modifier,
    size: Dp,
    color: Color,
) {
    // CMP-вариант material3 не содержит expressive LoadingIndicator — ближайший аналог.
    CircularProgressIndicator(
        modifier = modifier,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    )
}
