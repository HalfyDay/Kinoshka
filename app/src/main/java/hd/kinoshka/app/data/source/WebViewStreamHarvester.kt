package hd.kinoshka.app.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Extracts a direct stream URL by loading an embed page in an invisible real-browser environment.
 *
 * Some video sources (alloha, veoveo) guard their streams behind region/session checks and JS
 * bootstrapping that no HTTP-only extractor can reproduce; others (collaps) hand out HLS tokens
 * that expire within seconds of issuance. Loading the embed in a headless WebView defeats all
 * three at once: the player's own JavaScript resolves everything, and we merely eavesdrop on the
 * network layer for the first media-manifest request it makes.
 *
 * Must be initialized once with an application context ([init]) before first use.
 */
object WebViewStreamHarvester {
    private const val TAG = "StreamHarvester"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class Harvested(
        val url: String,
        /** Referer observed on the manifest request — required by most CDNs when playing. */
        val referer: String?,
    )

    // Only manifests are harvested: they are unambiguous playback entry points, unlike segment
    // requests which ad CDNs sometimes mimic.
    private val STREAM_HINTS = listOf(".m3u8", "/manifest.mpd", ".mpd")
    private val AD_HINTS = listOf(
        "doubleclick", "googlesyndication", "ads.", "/ads/", "adserver",
        "buzzoola", "bidderstack", "propeller", "clickadu", "vast."
    )

    private fun looksLikeStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (AD_HINTS.any { lower.contains(it) }) return false
        return STREAM_HINTS.any { lower.contains(it) }
    }

    /**
     * Loads [embedUrl] offscreen and returns the first captured stream manifest, or null on
     * timeout/failure. The embed's own origin is forwarded as [pageReferer] — sources served via
     * the ddbb aggregator reject frames without it.
     */
    suspend fun harvest(embedUrl: String, pageReferer: String? = null, timeoutMs: Long = 20_000L): Harvested? {
        val context = appContext ?: run {
            Log.w(TAG, "harvest() before init()")
            return null
        }
        return suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            val captured = AtomicReference<Harvested?>(null)

            fun finish(result: Harvested?) {
                if (finished) return
                finished = true
                main.post {
                    runCatching {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.destroy()
                    }
                }
                if (cont.isActive) cont.resume(result)
            }

            main.post {
                try {
                    val wv = createWebView(context) { url, referer ->
                        // Keep the earliest capture: later ones are usually segments/variants.
                        if (captured.compareAndSet(null, Harvested(url, referer))) finish(captured.get())
                    }
                    webView = wv
                    val headers = if (pageReferer.isNullOrBlank()) emptyMap() else mapOf("Referer" to pageReferer)
                    if (headers.isEmpty()) wv.loadUrl(embedUrl) else wv.loadUrl(embedUrl, headers)
                    main.postDelayed({ finish(captured.get()) }, timeoutMs)
                } catch (t: Throwable) {
                    Log.w(TAG, "harvest failed to start: ${t.message}")
                    finish(null)
                }
            }

            cont.invokeOnCancellation {
                main.post { runCatching { webView?.destroy() } }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, onStreamFound: (url: String, referer: String?) -> Unit): WebView =
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.blockNetworkImage = true // faster load, less tracking
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (looksLikeStreamUrl(url)) {
                        val referer = request.requestHeaders?.get("Referer")
                            ?: request.requestHeaders?.get("referer")
                            ?: runCatching { java.net.URI(url) }.getOrNull()
                                ?.let { "${it.scheme}://${it.host}/" }
                        Log.i(TAG, "Harvested stream: ${url.take(120)} (referer=$referer)")
                        onStreamFound(url, referer)
                    }
                    return null
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    // Same rationale as InAppWebScreen: these hosts ship broken cert chains but
                    // play fine; there is nothing sensitive about this throwaway session.
                    handler?.proceed()
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    // Non-fatal: players often 404 their own probes before finding the stream.
                }
            }
        }
}
