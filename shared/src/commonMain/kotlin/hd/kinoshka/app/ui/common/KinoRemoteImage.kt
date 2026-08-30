package hd.kinoshka.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Общий примитив картинки с сетью фолбэков (buildImageUrlFallbacks).
 * Android — Coil (crossfade, оригинальный размер), desktop — Skia-загрузчик.
 * Для UI, которому нужен Coil-state в колбэке (DetailsScreen), остаётся
 * KinoshkaAsyncImage в app до полной миграции экранов.
 */
@Composable
expect fun KinoRemoteImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    useOriginalSize: Boolean = false,
    fadeDurationMs: Int = 520,
    fallbackModel: Any? = null,
)
