package hd.kinoshka.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Единый лоадер Kino: Android — expressive Material3 LoadingIndicator, desktop — кружок. */
@Composable
expect fun KinoLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    color: Color = Color.Unspecified,
)
