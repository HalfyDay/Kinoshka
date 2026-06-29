package hd.kinoshka.app.ui.screens

import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import `is`.xyz.mpv.MPVLib
import java.lang.ref.WeakReference

object PlayerPipState {
    @Volatile
    var isPlayerScreenVisible: Boolean = false

    @Volatile
    var isPlaying: Boolean = false

    private var activeWebViewRef: WeakReference<WebView>? = null
    private var activeMpvViewRef: WeakReference<KinoMPVView>? = null

    fun setActiveWebView(webView: WebView?) {
        activeWebViewRef = if (webView != null) WeakReference(webView) else null
    }

    fun setActiveMpvView(mpvView: KinoMPVView?) {
        activeMpvViewRef = if (mpvView != null) WeakReference(mpvView) else null
    }

    /**
     * Dispatches a play/pause action. Routes to MPV player if active,
     * otherwise dispatches hardware MEDIA_PLAY_PAUSE key event to the WebView.
     */
    fun togglePlayPause() {
        val mpvView = activeMpvViewRef?.get()
        if (mpvView != null) {
            mpvView.post {
                val paused = MPVLib.getPropertyBoolean("pause") ?: false
                MPVLib.setPropertyBoolean("pause", !paused)
            }
            isPlaying = !isPlaying
            return
        }

        val webView = activeWebViewRef?.get() ?: return
        webView.post {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            webView.dispatchKeyEvent(down)
            webView.dispatchKeyEvent(up)
        }
        isPlaying = !isPlaying
    }

    fun play() {
        val mpvView = activeMpvViewRef?.get()
        if (mpvView != null) {
            mpvView.post {
                MPVLib.setPropertyBoolean("pause", false)
            }
            isPlaying = true
            return
        }

        val webView = activeWebViewRef?.get() ?: return
        webView.post {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
            webView.dispatchKeyEvent(down)
            webView.dispatchKeyEvent(up)
        }
        isPlaying = true
    }

    fun pause() {
        val mpvView = activeMpvViewRef?.get()
        if (mpvView != null) {
            mpvView.post {
                MPVLib.setPropertyBoolean("pause", true)
            }
            isPlaying = false
            return
        }

        val webView = activeWebViewRef?.get() ?: return
        webView.post {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            webView.dispatchKeyEvent(down)
            webView.dispatchKeyEvent(up)
        }
        isPlaying = false
    }
}

