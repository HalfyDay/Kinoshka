package hd.kinoshka.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import java.util.concurrent.TimeUnit

private val imageHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private const val IMAGE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private suspend fun decodeUrl(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder().url(url).header("User-Agent", IMAGE_UA).build()
        imageHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes()
        }
    }.getOrNull()?.let { bytes ->
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
}

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
    // На desktop модель — всегда строка-URL (ImageRequest-упаковка Coil здесь не существует).
    val rawUrl = model?.toString()
    val fallbackUrls = remember(rawUrl) { buildImageUrlFallbacks(rawUrl) }
    var attemptIndex by remember(rawUrl) { mutableIntStateOf(0) }

    var bitmap by remember(rawUrl) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(rawUrl) { mutableStateOf(false) }

    LaunchedEffect(rawUrl, attemptIndex) {
        if (rawUrl == null) {
            failed = true
            return@LaunchedEffect
        }
        val url = fallbackUrls.getOrElse(attemptIndex) { fallbackUrls.last() }
        val loaded = decodeUrl(url)
        if (loaded != null) {
            bitmap = loaded
        } else if (attemptIndex < fallbackUrls.size - 1) {
            attemptIndex++
        } else {
            failed = true
        }
    }

    Box(modifier = modifier.background(Color(0xFF1C1C22)), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) {
            var visible by remember(loaded) { mutableStateOf(false) }
            LaunchedEffect(loaded) { visible = true }
            val fadeProgress by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(
                    durationMillis = fadeDurationMs,
                    easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
                ),
                label = "imageFadeIn"
            )
            Image(
                bitmap = loaded,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fadeProgress },
                contentScale = contentScale,
            )
        } else if (!failed) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else if (fallbackModel != null && fallbackModel != model) {
            KinoRemoteImage(
                model = fallbackModel,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                fadeDurationMs = fadeDurationMs
            )
        }
        // Полный «нет постера»-заглушки нет: фон бокса уже служит плейсхолдером.
    }
}
