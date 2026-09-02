package hd.kinoshka.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import hd.kinoshka.app.ui.common.KinoRemoteImage

@Composable
actual fun KinoshkaAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    filterQuality: FilterQuality,
    useOriginalSize: Boolean,
    fadeDurationMs: Int,
    fallbackModel: Any?,
    onSuccess: ((width: Int, height: Int) -> Unit)?
) {
    // filterQuality на desktop не применяется: Skia-декодер сам выбирает сэмплинг.
    KinoRemoteImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        useOriginalSize = useOriginalSize,
        fadeDurationMs = fadeDurationMs,
        fallbackModel = fallbackModel
    )
    if (onSuccess != null) {
        // KinoRemoteImage не отдаёт момент/размеры загрузки; вызывающие код защищён
        // проверкой (width > 0 && height > 0), так что (-1, -1) безопасен.
        LaunchedEffect(model) { onSuccess(-1, -1) }
    }
}
