package hd.kinoshka.app.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import hd.kinoshka.app.ui.platform.rememberReduceMotion

/**
 * Squash & stretch входа контента bottom sheet: вырастает снизу с лёгким
 * перерастяжением (шире и ниже → норма) в такт выезду шторки.
 * Точка опоры — низ. Модификатор, а не обёртка: безопасен для weight-корней.
 * При calm — мгновенное появление без анимации.
 */
fun Modifier.sheetSquashStretch(): Modifier = composed {
    val calm = rememberReduceMotion()
    var entered by remember { mutableStateOf(calm) }
    LaunchedEffect(Unit) { entered = true }
    val spec: AnimationSpec<Float> = if (calm) {
        snap()
    } else {
        spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    }
    val scaleX by animateFloatAsState(
        targetValue = if (entered) 1f else 1.07f,
        animationSpec = spec,
        label = "sheet_enter_x"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (entered) 1f else 0.88f,
        animationSpec = spec,
        label = "sheet_enter_y"
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = if (calm) snap() else tween(durationMillis = 180),
        label = "sheet_enter_alpha"
    )
    graphicsLayer {
        this.scaleX = scaleX
        this.scaleY = scaleY
        this.alpha = alpha
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}
