package hd.kinoshka.app.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.size.Size
import hd.kinoshka.app.ui.common.buildImageUrlFallbacks

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
    val context = LocalContext.current

    // Модель может прийти упакованной в ImageRequest с явным размером (кэш-матч
    // с префетчем) — для цепочки фолбэков достаём сырую ссылку.
    val rawUrl = (model as? coil.request.ImageRequest)?.data?.toString() ?: model?.toString()

    val fallbackUrls = remember(rawUrl) {
        buildImageUrlFallbacks(rawUrl)
    }

    var attemptIndex by remember(model) { mutableIntStateOf(0) }

    val currentUrl = if (fallbackUrls.isNotEmpty()) {
        fallbackUrls.getOrElse(attemptIndex) { fallbackUrls.last() }
    } else model

    // Пришедший ImageRequest (с размером под кэш префетча) сохраняем как есть;
    // иначе строим запрос сами — оригинал или строку по умолчанию.
    val imageModel: Any? = remember(model, currentUrl, useOriginalSize) {
        when {
            model is ImageRequest -> model
            useOriginalSize && currentUrl != null -> ImageRequest.Builder(context)
                .data(currentUrl)
                .size(Size.ORIGINAL)
                .build()
            else -> currentUrl
        }
    }

    SubcomposeAsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        filterQuality = filterQuality,
        loading = {
            Box(modifier = Modifier.fillMaxSize().shimmerEffect())
        },
        success = { state ->
            val drawable = state.result.drawable
            onSuccess?.invoke(drawable.intrinsicWidth, drawable.intrinsicHeight)
            var visible by remember(currentUrl) { mutableStateOf(false) }
            LaunchedEffect(currentUrl) {
                visible = true
            }
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
                LaunchedEffect(attemptIndex) {
                    attemptIndex++
                }
                Box(modifier = Modifier.fillMaxSize().shimmerEffect())
            } else if (fallbackModel != null) {
                KinoshkaAsyncImage(
                    model = fallbackModel,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    fadeDurationMs = fadeDurationMs
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    )
}
