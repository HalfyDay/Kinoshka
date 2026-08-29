package hd.kinoshka.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import hd.kinoshka.app.data.feed.FeedClipState
import hd.kinoshka.app.data.feed.FeedItem
import hd.kinoshka.app.data.feed.FeedItemExtras
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage

/** Подсветка активного голоса на карточке. */
private val LikeGreen = Color(0xFF66BB6A)
private val DislikeRed = Color(0xFFEF5350)

/**
 * Запрос картинки с ЯВНЫМ размером экрана — тем же, каким греет кэш префетч.
 * Совпадение ключей memory-cache = мгновенный показ из памяти, без серого декода.
 */
@Composable
private fun sizedModel(url: String?): Any? {
    if (url.isNullOrBlank()) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val dm = context.resources.displayMetrics
    return remember(url, dm.widthPixels, dm.heightPixels) {
        coil.request.ImageRequest.Builder(context)
            .data(url)
            .size(dm.widthPixels, dm.heightPixels)
            .build()
    }
}

/**
 * Карточка фида на весь экран. Фон — каскад: полный постер (Ken Burns) → кадры из KP →
 * Rutube HLS / YouTube-трейлер. Тап-зоны: левая треть = кадр назад, правая = вперёд,
 * центр = развернуть описание. Нижний блок: свёрнутое описание + «Смотреть»/«Открыть».
 */
@Composable
internal fun FeedCard(
    item: FeedItem,
    extras: FeedItemExtras?,
    clipState: FeedClipState,
    expanded: Boolean,
    /** Голос по карточке: true = лайк, false = дизлайк, null = не голосовал. */
    reaction: Boolean?,
    soundOn: Boolean,
    isActive: Boolean,
    onToggleExpanded: () -> Unit,
    onReact: (Boolean) -> Unit,
    onOpenDetails: () -> Unit,
    onPlan: () -> Unit,
    planned: Boolean,
    onToggleSound: () -> Unit
) {
    val stills = extras?.stills.orEmpty()
    var stillIndex by remember(item.kinopoiskId) { mutableIntStateOf(0) }
    var manualStill by remember(item.kinopoiskId) { mutableStateOf(false) }
    var holdAutoUntil by remember(item.kinopoiskId) { mutableLongStateOf(0L) }

    // Автокарусель кадров; после ручного листания пауза 12 секунд.
    LaunchedEffect(stills.size, isActive) {
        while (stills.size > 1 && isActive) {
            kotlinx.coroutines.delay(4_000)
            if (System.currentTimeMillis() >= holdAutoUntil) {
                stillIndex = (stillIndex + 1) % stills.size
            }
        }
    }

    fun stepStill(delta: Int) {
        if (stills.isEmpty()) return
        manualStill = true
        holdAutoUntil = System.currentTimeMillis() + 12_000
        stillIndex = (stillIndex + delta + stills.size) % stills.size
    }

    val rutubeReady = clipState is FeedClipState.RutubeReady
    val youtubeKey = (clipState as? FeedClipState.YouTubeReady)?.videoKey
    // Видео скрывается, когда пользователь листает кадры вручную.
    val showRutube = isActive && rutubeReady && !manualStill
    val showYouTube = isActive && youtubeKey != null && !manualStill
    val showStills = stills.isNotEmpty() && !showRutube && !showYouTube
    val backgroundPoster = extras?.fullPosterUrl ?: item.posterUrl

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Слой 0: постер с медленным зумом — ВСЕГДА снизу. Пока кадры/видео не готовы,
        // пользователь видит живую картинку вместо серого/чёрного экрана.
        KenBurnsPoster(posterUrl = sizedModel(backgroundPoster))

        // Слой 2: карусель кадров — кадр целиком (Fit) поверх размытого дубля той же картинки.
        if (showStills) {
            val currentStill = stills.getOrNull(stillIndex.coerceIn(0, stills.lastIndex)) ?: stills.first()
            var stillsReady by remember(item.kinopoiskId) { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxSize().background(if (stillsReady) Color.Black else Color.Transparent)) {
                // Размытый фон из того же кадра; до его загрузки просвечивает постер.
                KinoshkaAsyncImage(
                    model = sizedModel(currentStill),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(24.dp),
                    contentScale = ContentScale.Crop,
                    onSuccess = { stillsReady = true }
                )
                if (stillsReady) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
                    // Сам кадр: без обрезки по краям.
                    Crossfade(
                        targetState = currentStill,
                        animationSpec = tween(400),
                        label = "still_crossfade"
                    ) { still ->
                        KinoshkaAsyncImage(
                            model = sizedModel(still),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                if (stills.size > 1 && stillsReady) {
                    StillDots(
                        count = stills.size,
                        current = stillIndex % stills.size,
                        // Без align ребёнок BoxScope прилипает к верху — точки уезжали на потолок.
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }

            // Стрелки листания: обе у самых краёв, симметрично.
            StillNavArrow(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp),
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                onClick = { stepStill(-1) }
            )
            StillNavArrow(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                onClick = { stepStill(+1) }
            )

            // Видео найдено, но пользователь ушёл в кадры — вернуть одним тапом.
            if ((rutubeReady || youtubeKey != null)) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 200.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { manualStill = false },
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("К видео", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Слой 3a: Rutube HLS нативно.
        if (showRutube) {
            key((clipState as? FeedClipState.RutubeReady)?.hlsUrl.orEmpty()) {
                RutubeHlsPlayer(
                    url = (clipState as FeedClipState.RutubeReady).hlsUrl,
                    soundOn = soundOn,
                    active = isActive
                )
            }
        }
        // Слой 3b: YouTube-трейлер.
        if (showYouTube) {
            key(youtubeKey!!) {
                YouTubeTrailerLayer(videoKey = youtubeKey)
            }
        }

        // Спиннер поиска клипа.
        if (isActive && clipState is FeedClipState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 180.dp, end = 24.dp)
                    .size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // Градиентные скримы для читаемости.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.4f),
                        0.22f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.85f)
                    )
                )
        )

        // Мягкие края: радиальное затемнение растворяет границы картинки в фоне.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                        radius = 1400f
                    )
                )
        )

        // Тап-зоны поверх медиа-слоёв, но под кнопками.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.kinopoiskId) {
                    detectTapGestures { offset ->
                        val fraction = offset.x / size.width.toFloat()
                        when {
                            fraction < 0.33f -> stepStill(-1)
                            fraction > 0.66f -> stepStill(+1)
                            else -> onToggleExpanded()
                        }
                    }
                }
        )

        // Правая панель реакций — опущена ниже, к нижней трети карточки.
        // Карточка не удаляется по голосу: можно вернуться и переголосовать.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FeedActionButton(icon = { tint ->
                Icon(
                    Icons.Filled.ThumbUp, "Нравится",
                    tint = if (reaction == true) LikeGreen else tint,
                    modifier = Modifier.size(22.dp)
                )
            }, onClick = { onReact(true) })
            FeedActionButton(icon = { tint ->
                Icon(
                    Icons.Filled.ThumbDown, "Не нравится",
                    tint = if (reaction == false) DislikeRed else tint,
                    modifier = Modifier.size(22.dp)
                )
            }, onClick = { onReact(false) })
            // «В планах» под голосами: тумблер, активная — залита акцентом.
            FeedActionButton(icon = { tint ->
                Icon(
                    if (planned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    "В планы",
                    tint = if (planned) MaterialTheme.colorScheme.primary else tint,
                    modifier = Modifier.size(22.dp)
                )
            }, onClick = onPlan)
            if (rutubeReady) {
                FeedActionButton(icon = { tint ->
                    Icon(
                        if (soundOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        "Звук",
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }, onClick = onToggleSound)
            }
        }

        // Нижний блок: свёрнутая информация + «Смотреть» (всегда страница тайтла).
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 76.dp, bottom = 118.dp)
        ) {
            // Инфо-блок НАД кнопкой: при раскрытии описания растёт вверх,
            // кнопка «Смотреть» стоит на месте.
            FeedInfoBlock(item = item, extras = extras, expanded = expanded)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenDetails,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Смотреть", fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

// ============================ слои фона ============================

@Composable
private fun KenBurnsPoster(posterUrl: Any?) {
    val transition = rememberInfiniteTransition(label = "ken_burns")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Reverse),
        label = "poster_scale"
    )
    KinoshkaAsyncImage(
        model = posterUrl,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun StillDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 168.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(count.coerceAtMost(6)) { dot ->
            val isCurrent = dot == current % count.coerceAtMost(6)
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (isCurrent) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) Color.White else Color.White.copy(alpha = 0.4f))
            )
        }
    }
}

/** Нативный плеер Rutube HLS: зациклен, звук управляется кнопкой панели. */
@Composable
private fun RutubeHlsPlayer(url: String, soundOn: Boolean, active: Boolean) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(active) { player.playWhenReady = active }
    LaunchedEffect(soundOn) { player.volume = if (soundOn) 1f else 0f }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        modifier = Modifier.fillMaxSize()
    )
}

/** Официальный трейлер через YouTube iframe: HTML грузится один раз в factory. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeTrailerLayer(videoKey: String) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                setBackgroundColor(android.graphics.Color.BLACK)
                loadDataWithBaseURL(
                    "https://www.youtube.com",
                    buildYouTubeEmbedHtml(videoKey),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        },
        update = { /* пусто: перезагрузка только через пересоздание по key(videoKey) */ },
        modifier = Modifier.fillMaxSize()
    )
}

private fun buildYouTubeEmbedHtml(videoKey: String): String =
    """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
       <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
       iframe{position:absolute;top:50%;left:50%;width:100vw;height:56.25vw;min-height:100vh;
       min-width:177.78vh;transform:translate(-50%,-50%);border:0}</style></head>
       <body><iframe src="https://www.youtube.com/embed/$videoKey?autoplay=1&mute=1&controls=0&loop=1&playlist=$videoKey&playsinline=1&modestbranding=1&rel=0&iv_load_policy=3"
       allow="autoplay; encrypted-media; playsinline" allowfullscreen></iframe></body></html>"""

// ============================ инфо-блок и панель ============================

@Composable
private fun FeedInfoBlock(item: FeedItem, extras: FeedItemExtras?, expanded: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        KinoshkaAsyncImage(
            model = sizedModel(extras?.fullPosterUrl ?: item.posterUrl),
            contentDescription = null,
            modifier = Modifier
                .width(72.dp)
                .height(106.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            // Заголовок крупный, как в коротких видео.
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = if (expanded) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.rating?.let { rating ->
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFF5C518)) {
                        Text(
                            text = String.format(java.util.Locale.US, "★ %.1f", rating),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                val typeLabel = when {
                    item.isAnime -> "Аниме"
                    item.isSeriesLike -> "Сериал"
                    item.contentType == "MOVIE" -> "Фильм"
                    else -> null
                }
                typeLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                item.year?.let {
                    Text(
                        text = "• $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            val genresLine = extras?.genres?.take(if (expanded) 6 else 3)?.joinToString(" · ")
            if (!genresLine.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = genresLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Теги хентая — отдельной строкой другим цветом, чтобы не путались с жанрами.
            val tagsLine = item.tags.ifEmpty { extras?.hentaiTags.orEmpty() }
                .take(if (expanded) 6 else 3).joinToString(" · ")
            if (item.isAdultContent && tagsLine.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tagsLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val description = extras?.description ?: item.shortDescription
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = if (expanded) 10 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (expanded) "Свернуть" else "Ещё",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun StillNavArrow(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.35f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun FeedActionButton(icon: @Composable (Color) -> Unit, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f)) {
        androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            icon(Color.White)
        }
    }
}
