package hd.kinoshka.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.player.desktop.MpvNative
import hd.kinoshka.app.player.desktop.MpvPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Color as AwtColor

private const val DEMO_URL =
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"

@Composable
fun PlayerScreen(film: FilmItem, onBack: () -> Unit) {
    var player by remember { mutableStateOf<MpvPlayer?>(null) }
    var attachFailed by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var url by remember { mutableStateOf(DEMO_URL) }
    val scope = rememberCoroutineScope()
    val attachGuard = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Привязка mpv: опрашиваем HWND канваса до его появления — тяжеловесный AWT-канвас
    // создаётся асинхронно после первого кадра Compose, поэтому ждём именно HWND.
    LaunchedEffect(Unit) {
        if (!attachGuard.compareAndSet(false, true)) return@LaunchedEffect
        launch(Dispatchers.IO) {
            try {
                var hwnd: Long? = null
                repeat(150) {
                    hwnd = Win32.findChildCanvasHwnd(MAIN_WINDOW_TITLE)
                    if (hwnd != null) return@repeat
                    delay(100)
                }
                val handle = hwnd ?: error("HWND канваса не найден")
                println("MPV: hwnd=$handle")
                val created = MpvPlayer.create(handle)
                val rc = created.load(url)
                println("MPV: loadfile rc=$rc")
                player = created
            } catch (t: Throwable) {
                attachGuard.set(false)
                attachFailed = t.message ?: "не удалось запустить mpv"
            }
        }
    }

    // Опрос позиции/длительности для полосы прогресса.
    LaunchedEffect(player) {
        while (player != null) {
            val current = player
            if (current != null) {
                withContext(Dispatchers.IO) {
                    position = current.positionSeconds() ?: 0.0
                    duration = current.durationSeconds() ?: 0.0
                }
            }
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.close() }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "← Назад",
                color = Color(0xFF9F9FA8),
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color(0xFF1C1C22), MaterialTheme.shapes.small)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                film.nameRu ?: film.nameOriginal ?: "Просмотр",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            SwingPanel(
                background = Color.Black,
                modifier = Modifier.fillMaxSize(),
                factory = {
                    Canvas().apply { background = AwtColor.BLACK }
                },
            )
            attachFailed?.let { message ->
                Text(
                    "mpv: $message",
                    color = Color(0xFFFF7B72),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = {
                scope.launch(Dispatchers.IO) { player?.let { paused = it.togglePause() } }
            }) {
                Text(if (paused) "▶" else "⏸")
            }
            Slider(
                value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(0.1f)),
                onValueChange = { position = it.toDouble() },
                onValueChangeFinished = {
                    scope.launch(Dispatchers.IO) { player?.seekTo(position) }
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(0.1f),
                modifier = Modifier.weight(1f),
            )
            Text(
                "${formatTime(position)} / ${formatTime(duration)}",
                color = Color(0xFF9F9FA8),
                fontSize = 12.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("URL:", color = Color(0xFF9F9FA8), fontSize = 12.sp)
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFB9B9C0)),
                singleLine = true,
            )
            Button(onClick = {
                scope.launch(Dispatchers.IO) { player?.load(url) }
            }) { Text("Играть") }
        }
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0.0 || seconds.isNaN()) return "0:00"
    val total = seconds.toLong()
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
