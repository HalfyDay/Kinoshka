package hd.kinoshka.app

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.graphics.Rect
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hd.kinoshka.app.ui.KinoApp
import hd.kinoshka.app.ui.screens.PlayerPipState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KinoApp()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) return
        if (!PlayerPipState.isPlayerScreenVisible) return

        val decor = window?.decorView
        val width = decor?.width ?: 0
        val height = decor?.height ?: 0
        val ratio = Rational(16, 9)
        val sourceRectHint = calculateCenterCropRect(width, height, ratio)

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
        if (sourceRectHint != null) {
            builder.setSourceRectHint(sourceRectHint)
        }
        val params = builder.build()

        runCatching { enterPictureInPictureMode(params) }
    }

    private fun calculateCenterCropRect(
        width: Int,
        height: Int,
        targetRatio: Rational
    ): Rect? {
        if (width <= 0 || height <= 0) return null

        val target = targetRatio.toFloat()
        val current = width.toFloat() / height.toFloat()

        return if (current > target) {
            val targetWidth = (height * target).toInt()
            val left = (width - targetWidth) / 2
            Rect(left, 0, left + targetWidth, height)
        } else {
            val targetHeight = (width / target).toInt()
            val top = (height - targetHeight) / 2
            Rect(0, top, width, top + targetHeight)
        }
    }
}

