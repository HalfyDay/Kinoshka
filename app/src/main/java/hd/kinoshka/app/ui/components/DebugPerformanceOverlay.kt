package hd.kinoshka.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.BuildConfig
import java.util.Locale
import kotlinx.coroutines.isActive

@Composable
fun DebugPerformanceOverlay(
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!enabled || !BuildConfig.DEBUG) return

    var fps by remember { mutableIntStateOf(0) }
    var avgFrameMs by remember { mutableFloatStateOf(16.7f) }
    val frameHistory = remember {
        mutableStateListOf<Float>().apply {
            repeat(72) { add(16.7f) }
        }
    }

    LaunchedEffect(enabled) {
        var secondStartNanos = 0L
        var secondFrameCount = 0
        var previousFrameNanos = 0L

        var bucketStartNanos = 0L
        var bucketTotalMs = 0f
        var bucketFrameCount = 0

        while (isActive) {
            val frameNanos = withFrameNanos { it }

            if (secondStartNanos == 0L) {
                secondStartNanos = frameNanos
                bucketStartNanos = frameNanos
                previousFrameNanos = frameNanos
                continue
            }

            val frameMs = ((frameNanos - previousFrameNanos) / 1_000_000f).coerceIn(0f, 80f)
            previousFrameNanos = frameNanos

            secondFrameCount += 1
            val secondElapsed = frameNanos - secondStartNanos
            if (secondElapsed >= 1_000_000_000L) {
                fps = ((secondFrameCount * 1_000_000_000L) / secondElapsed).toInt()
                secondFrameCount = 0
                secondStartNanos = frameNanos
            }

            bucketTotalMs += frameMs
            bucketFrameCount += 1
            val bucketElapsed = frameNanos - bucketStartNanos
            if (bucketElapsed >= 120_000_000L && bucketFrameCount > 0) {
                val sample = (bucketTotalMs / bucketFrameCount).coerceIn(0f, 80f)
                avgFrameMs = sample
                frameHistory.add(sample)
                if (frameHistory.size > 72) {
                    frameHistory.removeAt(0)
                }
                bucketTotalMs = 0f
                bucketFrameCount = 0
                bucketStartNanos = frameNanos
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.7f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = "$fps FPS | ${String.format(Locale.US, "%.1f", avgFrameMs)} ms",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            Canvas(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(136.dp)
                    .height(34.dp)
            ) {
                val maxMs = 50f
                val w = size.width
                val h = size.height
                val stepX = if (frameHistory.size <= 1) 0f else w / (frameHistory.size - 1)

                fun y(ms: Float): Float = h - (ms.coerceIn(0f, maxMs) / maxMs) * h

                drawLine(
                    color = Color(0x66FFFFFF),
                    start = Offset(0f, y(16.7f)),
                    end = Offset(w, y(16.7f)),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color(0x44FFFFFF),
                    start = Offset(0f, y(33.3f)),
                    end = Offset(w, y(33.3f)),
                    strokeWidth = 1.dp.toPx()
                )

                for (index in 1 until frameHistory.size) {
                    val x1 = (index - 1) * stepX
                    val x2 = index * stepX
                    val y1 = y(frameHistory[index - 1])
                    val y2 = y(frameHistory[index])
                    val color = when {
                        frameHistory[index] <= 18f -> Color(0xFF6EE7A0)
                        frameHistory[index] <= 28f -> Color(0xFFFFD166)
                        else -> Color(0xFFFF6B6B)
                    }
                    drawLine(
                        color = color,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                drawRect(
                    color = Color(0x26FFFFFF),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
    }
}
