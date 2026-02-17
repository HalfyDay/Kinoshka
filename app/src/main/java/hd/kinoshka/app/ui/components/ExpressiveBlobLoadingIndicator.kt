package hd.kinoshka.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ExpressiveBlobLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color
) {
    val transition = rememberInfiniteTransition(label = "blob_loading")
    val rotationDeg by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "blob_rotation"
    )

    Canvas(modifier = modifier.size(52.dp)) {
        rotate(degrees = rotationDeg, pivot = Offset(size.width / 2f, size.height / 2f)) {
            drawBlob(color = color.copy(alpha = 0.24f), radiusScale = 1.12f)
            drawBlob(color = color, radiusScale = 1f)
        }
    }
}

private fun DrawScope.drawBlob(
    color: Color,
    radiusScale: Float
) {
    val lobes = 9
    val samplesPerLobe = 8
    val pointCount = lobes * samplesPerLobe
    val baseRadius = size.minDimension * 0.50f * radiusScale
    val lobeAmplitude = baseRadius * 0.09f
    val subtleVariation = baseRadius * 0.02f
    val center = Offset(size.width / 2f, size.height / 2f)
    val points = ArrayList<Offset>(pointCount)

    for (index in 0 until pointCount) {
        val t = index.toFloat() / pointCount.toFloat()
        val angle = (2f * PI.toFloat()) * t
        val primaryWave = sin(lobes * angle)
        val secondaryWave = sin((lobes * 0.5f) * angle + 0.85f)
        val radius = baseRadius + (lobeAmplitude * primaryWave) + (subtleVariation * secondaryWave)
        points += Offset(
            x = center.x + radius * cos(angle),
            y = center.y + radius * sin(angle)
        )
    }

    val path = smoothClosedPath(points = points, smoothness = 0.18f)
    drawPath(path = path, color = color)
}

private fun smoothClosedPath(
    points: List<Offset>,
    smoothness: Float
): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size < 4) {
        path.moveTo(points.first().x, points.first().y)
        for (index in 1 until points.size) {
            path.lineTo(points[index].x, points[index].y)
        }
        path.close()
        return path
    }

    val count = points.size
    path.moveTo(points[0].x, points[0].y)
    for (index in 0 until count) {
        val p0 = points[(index - 1 + count) % count]
        val p1 = points[index]
        val p2 = points[(index + 1) % count]
        val p3 = points[(index + 2) % count]

        val cp1 = controlPoint(a = p0, b = p1, c = p2, scale = smoothness)
        val cp2 = controlPoint(a = p3, b = p2, c = p1, scale = smoothness)
        path.cubicTo(
            cp1.x,
            cp1.y,
            cp2.x,
            cp2.y,
            p2.x,
            p2.y
        )
    }
    path.close()
    return path
}

private fun controlPoint(
    a: Offset,
    b: Offset,
    c: Offset,
    scale: Float
): Offset {
    return Offset(
        x = b.x + ((c.x - a.x) * scale),
        y = b.y + ((c.y - a.y) * scale)
    )
}
