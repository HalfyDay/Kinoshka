package hd.kinoshka.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hd.kinoshka.app.data.download.DownloadNotifications
import hd.kinoshka.app.ui.DownloadsNav
import hd.kinoshka.app.ui.KinoApp
import hd.kinoshka.app.ui.screens.PlayerPipState

class MainActivity : ComponentActivity() {

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> {
                    PlayerPipState.togglePlayPause()
                    updatePipParams()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Телефон остаётся портретным (android:screenOrientation="portrait"); на планшетах,
        // ТВ и foldable (smallestScreenWidthDp >= 600) ориентацию отпускаем — landscape
        // включает TV-дизайн (rememberTvLayout).
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        enableEdgeToEdge()
        maybeOpenDownloads(intent)

        // Register PiP action receiver
        val filter = IntentFilter(ACTION_PIP_PLAY_PAUSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipReceiver, filter)
        }

        // NOTE: no audio focus here. Grabbing AUDIOFOCUS_GAIN at cold start pauses whatever
        // music is playing in the background; media keys are routed either to the foreground
        // activity (onKeyDown below, focus-independent) or to MediaPlaybackService's
        // MediaSession while playback is running.

        setContent {
            KinoApp()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                PlayerPipState.togglePlayPause()
                updatePipParams()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                PlayerPipState.play()
                updatePipParams()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                PlayerPipState.pause()
                updatePipParams()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isInPictureInPictureMode) return
        if (!PlayerPipState.isPlayerScreenVisible) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: тап по уведомлению скачивания при живом процессе приходит сюда.
        maybeOpenDownloads(intent)
    }

    /** Тап по уведомлению скачивания → открыть страницу «Загрузки» (см. [DownloadsNav]). */
    private fun maybeOpenDownloads(intent: Intent?) {
        if (intent?.getBooleanExtra(DownloadNotifications.EXTRA_OPEN_DOWNLOADS, false) == true) {
            DownloadsNav.openRequest++
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            updatePipParams()
        }
    }

    private fun updatePipParams() {
        if (isInPictureInPictureMode) {
            runCatching { setPictureInPictureParams(buildPipParams()) }
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val ratio = Rational(16, 9)
        val decor = window?.decorView
        val width = decor?.width ?: 0
        val height = decor?.height ?: 0
        val sourceRectHint = calculateCenterCropRect(width, height, ratio)

        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        sourceRectHint?.let {
            builder.setSourceRectHint(it)
        }

        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val intent = Intent(ACTION_PIP_PLAY_PAUSE)
        val pi = PendingIntent.getBroadcast(this, 0, intent, pendingFlags)

        val iconRes =
            if (PlayerPipState.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = if (PlayerPipState.isPlaying) "Пауза" else "Воспроизведение"
        val action = RemoteAction(Icon.createWithResource(this, iconRes), title, title, pi)
        builder.setActions(arrayListOf(action))

        return builder.build()
    }

    private fun calculateCenterCropRect(width: Int, height: Int, targetRatio: Rational): Rect? {
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

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "hd.kinoshka.app.ACTION_PIP_PLAY_PAUSE"
    }
}
