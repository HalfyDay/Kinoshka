package hd.kinoshka.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.ui.common.preferHdAnimePosterUrl
import hd.kinoshka.app.ui.platform.rememberReduceMotion
import kotlinx.coroutines.delay

/**
 * Обложка на фоне за шитом «Прогресс просмотра»: живёт в корне экрана
 * (шит — отдельный диалог поверх с прозрачным сримом). Выезжает из-за
 * нижнего края, при закрытии быстро уходит вниз вместе с шитом.
 * Небольшая задержка старта — сначала трогается шит, обложка догоняет из-под него.
 * За обложкой — лёгкое затемнение, проявляется плавно вместе с выездом.
 *
 * Грузит ту же картинку, что страница тайтла: аниме — smarthard HD,
 * кино — полный размер (kp_small → kp). Держит последний кадр на время
 * exit-анимации, чтобы вместо обложки не мигал «?».
 *
 * Примитивы вместо FilmDetails: тип деталей живёт в jvmShared и недоступен
 * из commonMain, а бэкдроп нужен и странице деталей, и KinoApp (лонг-пресс).
 */
@Composable
fun ProfileEditorCoverBackdrop(
    id: Int,
    title: String?,
    posterUrl: String?,
    coverUrl: String?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val calm = rememberReduceMotion()
    // Та же картинка, что на странице тайтла: аниме — smarthard HD (как детали),
    // кино — полный размер вместо превью (kp_small → kp). Каталог отдаёт только
    // превью (FilmItem без posterUrl), в библиотеке тоже лежат превью — апгрейдим
    // прямо здесь, для обоих мест. Не загрузится полный — KinoshkaAsyncImage
    // покажет исходник через fallbackModel.
    val hdAnimeUrl = preferHdAnimePosterUrl(posterUrl)
    val fullPosterUrl = preferFullSizePoster(posterUrl)
    val currentModel = hdAnimeUrl ?: fullPosterUrl ?: coverUrl
    // Последний живой кадр: при закрытии шита seed обнуляется сразу, а слайд
    // ещё доигрывается — без ретеншна на это время подставляется «?».
    var lastModel by remember { mutableStateOf<String?>(null) }
    var lastTitle by remember { mutableStateOf<String?>(null) }
    if (currentModel != null) {
        lastModel = currentModel
        lastTitle = title
    }
    val displayModel = currentModel ?: lastModel
    val displayTitle = if (currentModel != null) title else lastTitle
    // Наклон догоняет слайд: стартует с задержкой синхронно с выездом.
    var entered by remember(displayModel) { mutableStateOf(calm) }
    LaunchedEffect(displayModel) {
        if (!calm) delay(90)
        entered = true
    }
    val tilt by animateFloatAsState(
        targetValue = if (entered) -2f else -8f,
        animationSpec = if (calm) snap() else tween(
            durationMillis = 450,
            easing = FastOutSlowInEasing
        ),
        label = "pe_cover_tilt"
    )
    Box(modifier = modifier.fillMaxSize()) {
        // Затемнение за обложкой: только fade, без слайда. Гаснет вместе с уходом шита.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = if (calm) ExitTransition.None else fadeOut(tween(durationMillis = 200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
        }
        AnimatedVisibility(
            visible = visible,
            // Строго из-за нижнего края: сдвиг на всю высоту — целиком за экраном.
            enter = if (calm) EnterTransition.None else slideInVertically(
                animationSpec = tween(
                    durationMillis = 450,
                    delayMillis = 90,
                    easing = FastOutSlowInEasing
                ),
                initialOffsetY = { it }
            ),
            // Закрытие — сразу вниз вместе с шитом: быстрый старт без задержки.
            exit = if (calm) ExitTransition.None else slideOutVertically(
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing
                ),
                targetOffsetY = { it }
            )
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                // Ниже на высоких экранах: отступ от высоты, а не фиксированный.
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .padding(top = maxHeight * 0.16f)
                        .width(216.dp)
                        .aspectRatio(2f / 3f)
                        .graphicsLayer {
                            rotationZ = tilt
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                ) {
                    if (displayModel != null) {
                        KinoshkaAsyncImage(
                            model = displayModel,
                            contentDescription = displayTitle,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            useOriginalSize = true,
                            fallbackModel = posterUrl,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = (displayTitle ?: "?").take(1).uppercase(),
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Полный размер вместо превью, остальное как есть:
 * - Kinopoisk Unofficial API: `.../posters/kp_small/123.jpg` → `.../posters/kp/123.jpg`;
 * - Shikimori: `.../animes/preview| x96 | x48 /123.jpg` → `.../animes/original/123.jpg`.
 */
private fun preferFullSizePoster(url: String?): String? {
    if (url == null) return null
    var full = url
    if ("/kp_small/" in full) full = full.replace("/kp_small/", "/kp/")
    if ("/animes/preview/" in full) full = full.replace("/animes/preview/", "/animes/original/")
    if ("/animes/x96/" in full) full = full.replace("/animes/x96/", "/animes/original/")
    if ("/animes/x48/" in full) full = full.replace("/animes/x48/", "/animes/original/")
    return full
}
