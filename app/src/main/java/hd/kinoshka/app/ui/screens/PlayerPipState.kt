package hd.kinoshka.app.ui.screens

import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import java.lang.ref.WeakReference

object PlayerPipState {
    @Volatile
    var isPlayerScreenVisible: Boolean = false

    @Volatile
    var isPlaying: Boolean = false

    private var activeWebViewRef: WeakReference<WebView>? = null

    fun setActiveWebView(webView: WebView?) {
        activeWebViewRef = if (webView != null) WeakReference(webView) else null
    }

    /**
     * Dispatches a hardware MEDIA_PLAY_PAUSE key event directly to the WebView.
     * This works for ANY video inside the WebView including cross-origin iframes,
     * because Android routes media key events through the view's focus chain to
     * the active HTML5 media element.
     */
    fun togglePlayPause() {
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
