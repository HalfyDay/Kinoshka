package hd.kinoshka.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class PlayerResizeMode(val label: String, val mode: Int) {
    FIT("Вписать", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    CROP("Обрезать", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("Растянуть", AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

@OptIn(UnstableApi::class)
@Composable
fun MpvExPlayerScreen(
    streamUrl: String,
    headers: Map<String, String> = emptyMap(),
    animeTitle: String,
    episodeNumber: Int,
    episodeTitle: String,
    onBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPrevEpisode: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }

    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var isSpeedBoosted by remember { mutableStateOf(false) }

    var resizeMode by remember { mutableStateOf(PlayerResizeMode.FIT) }

    // Gesture indicator overlays
    var gestureVolumeValue by remember { mutableStateOf<Float?>(null) }
    var gestureBrightnessValue by remember { mutableStateOf<Float?>(null) }
    var gestureSeekDelta by remember { mutableStateOf<Long?>(null) }

    // Setup Landscape and Fullscreen
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val window = activity?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = originalOrientation
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Auto-hide controls timer
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying && !isLocked) {
            delay(4000)
            isControlsVisible = false
        }
    }

    // ExoPlayer Instance
    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .setDefaultRequestProperties(headers)

        val mediaItem = MediaItem.fromUri(streamUrl)
        val mediaSource: MediaSource = if (streamUrl.contains(".m3u8")) {
            HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        }

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    // Player Listeners
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Continuous position updates
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked) {
                detectTapGestures(
                    onTap = {
                        isControlsVisible = !isControlsVisible
                    },
                    onDoubleTap = { offset ->
                        if (isLocked) return@detectTapGestures
                        val width = size.width
                        if (offset.x < width / 2) {
                            // Seek -10s
                            val newPos = (exoPlayer.currentPosition - 10000).coerceAtLeast(0)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        } else {
                            // Seek +10s
                            val newPos = (exoPlayer.currentPosition + 10000).coerceAtMost(duration)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        }
                    },
                    onLongPress = {
                        if (isLocked) return@detectTapGestures
                        isSpeedBoosted = true
                        exoPlayer.setPlaybackParameters(PlaybackParameters(2.0f))
                    }
                )
            }
            .pointerInput(isLocked) {
                var initialVol = 0
                var initialBright = 0f
                var startX = 0f
                var startY = 0f
                var isHorizontalDrag = false
                var isVerticalDrag = false

                detectDragGestures(
                    onDragStart = { offset ->
                        if (isLocked) return@detectDragGestures
                        startX = offset.x
                        startY = offset.y
                        initialVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val act = context as? Activity
                        initialBright = act?.window?.attributes?.screenBrightness ?: 0.5f
                        if (initialBright < 0) initialBright = 0.5f
                        isHorizontalDrag = false
                        isVerticalDrag = false
                    },
                    onDragEnd = {
                        if (isLocked) return@detectDragGestures
                        if (isSpeedBoosted) {
                            isSpeedBoosted = false
                            exoPlayer.setPlaybackParameters(PlaybackParameters(currentSpeed))
                        }
                        if (gestureSeekDelta != null) {
                            val newPos = (exoPlayer.currentPosition + gestureSeekDelta!!).coerceIn(0, duration)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        }
                        gestureVolumeValue = null
                        gestureBrightnessValue = null
                        gestureSeekDelta = null
                    },
                    onDragCancel = {
                        if (isSpeedBoosted) {
                            isSpeedBoosted = false
                            exoPlayer.setPlaybackParameters(PlaybackParameters(currentSpeed))
                        }
                        gestureVolumeValue = null
                        gestureBrightnessValue = null
                        gestureSeekDelta = null
                    },
                    onDrag = { change, dragAmount ->
                        if (isLocked) return@detectDragGestures
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY

                        if (!isHorizontalDrag && !isVerticalDrag) {
                            if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 20) {
                                isHorizontalDrag = true
                            } else if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 20) {
                                isVerticalDrag = true
                            }
                        }

                        if (isHorizontalDrag) {
                            // Horizontal seeking
                            val deltaMs = (dx * 200).toLong() // 200ms per pixel
                            gestureSeekDelta = deltaMs
                        } else if (isVerticalDrag) {
                            // Vertical swipes: Left = Brightness, Right = Volume
                            val width = size.width
                            val height = size.height
                            val deltaFactor = -dy / height

                            if (startX < width / 2) {
                                // Brightness
                                val newBright = (initialBright + deltaFactor).coerceIn(0.01f, 1.0f)
                                val act = context as? Activity
                                act?.window?.let { win ->
                                    val lp = win.attributes
                                    lp.screenBrightness = newBright
                                    win.attributes = lp
                                }
                                gestureBrightnessValue = newBright
                            } else {
                                // Volume
                                val deltaVol = (deltaFactor * maxVolume).toInt()
                                val newVol = (initialVol + deltaVol).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                gestureVolumeValue = newVol.toFloat() / maxVolume
                            }
                        }
                    }
                )
            }
    ) {
        // Player AndroidView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setResizeMode(resizeMode.mode)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.setResizeMode(resizeMode.mode)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading Indicator
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
        }

        // Speed boost indicator (2.0x Hold)
        if (isSpeedBoosted) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("2.0x Ускорение", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Gesture Badge Overlay (Volume / Brightness / Seek)
        GestureBadgeOverlay(
            volume = gestureVolumeValue,
            brightness = gestureBrightnessValue,
            seekDeltaMs = gestureSeekDelta,
            modifier = Modifier.align(Alignment.Center)
        )

        // Player Controls UI Overlay
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top Bar
                PlayerTopBar(
                    animeTitle = animeTitle,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    isLocked = isLocked,
                    resizeMode = resizeMode,
                    onBack = onBack,
                    onToggleLock = { isLocked = !isLocked },
                    onToggleResize = {
                        resizeMode = when (resizeMode) {
                            PlayerResizeMode.FIT -> PlayerResizeMode.CROP
                            PlayerResizeMode.CROP -> PlayerResizeMode.FILL
                            PlayerResizeMode.FILL -> PlayerResizeMode.FIT
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )

                // Center Play/Pause & Skip Buttons
                if (!isLocked) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val newPos = (exoPlayer.currentPosition - 10000).coerceAtLeast(0)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "-10 сек", tint = Color.White, modifier = Modifier.size(32.dp))
                        }

                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            if (isPlaying) {
                                Text("❚❚", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val newPos = (exoPlayer.currentPosition + 10000).coerceAtMost(duration)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "+10 сек", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }

                // Bottom Bar
                if (!isLocked) {
                    PlayerBottomBar(
                        currentPosition = currentPosition,
                        duration = duration,
                        currentSpeed = currentSpeed,
                        onSeek = { newPos ->
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        },
                        onSpeedSelected = { spd ->
                            currentSpeed = spd
                            exoPlayer.setPlaybackParameters(PlaybackParameters(spd))
                        },
                        onPrevEpisode = onPrevEpisode,
                        onNextEpisode = onNextEpisode,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    animeTitle: String,
    episodeNumber: Int,
    episodeTitle: String,
    isLocked: Boolean,
    resizeMode: PlayerResizeMode,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleResize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "$animeTitle • Серия $episodeNumber",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = episodeTitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleResize) {
                Icon(Icons.Default.Settings, contentDescription = resizeMode.label, tint = Color.White)
            }
            IconButton(onClick = onToggleLock) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Заблокировать",
                    tint = if (isLocked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun PlayerBottomBar(
    currentPosition: Long,
    duration: Long,
    currentSpeed: Float,
    onSeek: (Long) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onPrevEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var showSpeedMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Slider & Timers
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(currentPosition),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                onValueChange = { factor ->
                    onSeek((factor * duration).toLong())
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Text(
                text = formatTime(duration),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPrevEpisode != null) {
                    IconButton(onClick = onPrevEpisode) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Пред. серия", tint = Color.White)
                    }
                }
                if (onNextEpisode != null) {
                    IconButton(onClick = onNextEpisode) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "След. серия", tint = Color.White)
                    }
                }
            }

            Box {
                Surface(
                    onClick = { showSpeedMenu = !showSpeedMenu },
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (currentSpeed == 1.0f) "1.0x" else "${currentSpeed}x",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (showSpeedMenu) {
                    Surface(
                        color = Color.DarkGray,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(bottom = 36.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                Text(
                                    text = "${spd}x",
                                    color = if (spd == currentSpeed) MaterialTheme.colorScheme.primary else Color.White,
                                    fontWeight = if (spd == currentSpeed) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .pointerInput(Unit) {
                                            detectTapGestures {
                                                onSpeedSelected(spd)
                                                showSpeedMenu = false
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureBadgeOverlay(
    volume: Float?,
    brightness: Float?,
    seekDeltaMs: Long?,
    modifier: Modifier = Modifier
) {
    if (volume == null && brightness == null && seekDeltaMs == null) return

    Surface(
        color = Color.Black.copy(alpha = 0.75f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                volume != null -> {
                    Text("🔊", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("${(volume * 100).toInt()}%", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                brightness != null -> {
                    Text("☀️", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("${(brightness * 100).toInt()}%", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                seekDeltaMs != null -> {
                    val prefix = if (seekDeltaMs >= 0) "+" else ""
                    Text("$prefix${seekDeltaMs / 1000} сек", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
