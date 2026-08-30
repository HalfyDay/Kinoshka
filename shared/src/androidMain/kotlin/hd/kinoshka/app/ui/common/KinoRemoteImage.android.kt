package hd.kinoshka.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun KinoRemoteImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    useOriginalSize: Boolean,
    fadeDurationMs: Int,
    fallbackModel: Any?,
) {
    val context = LocalContext.current

    // Модель может прийти упакованной в ImageRequest с явным размером (кэш-матч с префетчем).
    val rawUrl = (model as? ImageRequest)?.data?.toString() ?: model?.toString()

    val fallbackUrls = remember(rawUrl) { buildImageUrlFallbacks(rawUrl) }
    var attemptIndex by remember(model) { mutableIntStateOf(0) }

    val currentUrl = if (fallbackUrls.isNotEmpty()) {
        fallbackUrls.getOrElse(attemptIndex) { fallbackUrls.last() }
    } else model

    val imageModel: Any? = remember(model, currentUrl, useOriginalSize) {
        when {
            model is ImageRequest -> model
            useOriginalSize && currentUrl != null -> ImageRequest.Builder(context)
                .data(currentUrl)
                .size(coil.size.Size.ORIGINAL)
                .build()
            else -> currentUrl
        }
    }

    SubcomposeAsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        loading = {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
        },
        success = { state: AsyncImagePainter.State.Success ->
            var visible by remember(state.painter) { mutableStateOf(false) }
            LaunchedEffect(state.painter) { visible = true }
            val fadeProgress by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(
                    durationMillis = fadeDurationMs,
                    easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
                ),
                label = "imageFadeIn"
            )
            SubcomposeAsyncImageContent(
                modifier = Modifier.graphicsLayer { alpha = fadeProgress }
            )
        },
        error = {
            if (attemptIndex < fallbackUrls.size - 1) {
                LaunchedEffect(attemptIndex) { attemptIndex++ }
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
            } else if (fallbackModel != null) {
                KinoRemoteImage(
                    model = fallbackModel,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    fadeDurationMs = fadeDurationMs
                )
            } else {
                // Заглушка ошибки — фон-плейсхолдер (иконка Warning осталась в app-версии
                // KinoshkaAsyncImage; здесь не тянем material-icons в общий граф).
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
        }
    )
}
