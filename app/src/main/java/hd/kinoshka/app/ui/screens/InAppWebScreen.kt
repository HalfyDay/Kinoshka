package hd.kinoshka.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Data model for player
data class DdbbPlayer(
    val id: String,
    val name: String,
    val iframeUrl: String
)

// Standard HTTP client (for DDBB API - no SSL issues)
private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}

// Max response body size to prevent OOM (256KB)
private const val MAX_RESPONSE_BODY_BYTES = 256L * 1024

private fun readBodyLimited(response: okhttp3.Response, maxBytes: Long = MAX_RESPONSE_BODY_BYTES): String? {
    val source = response.body?.source() ?: return null
    return try {
        val buffer = okio.Buffer()
        var totalBytes = 0L
        val readBuffer = okio.Buffer()
        while (true) {
            val read = source.read(readBuffer, 65536)
            if (read == -1L) break
            totalBytes += read
            if (totalBytes > maxBytes) {
                response.close()
                return null
            }
            buffer.write(readBuffer, read)
        }
        buffer.readUtf8()
    } catch (_: Exception) {
        response.close()
        null
    }
}

// SSL-bypass HTTP client for Kodik and other Russian CDNs with untrusted certificates
private val sslBypassClient by lazy {
    try {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    } catch (e: Exception) {
        httpClient
    }
}

// Known Kodik API tokens (decoded from AnimeParsers tokens.json using reversed-base64 scheme)
private val KODIK_TOKENS = listOf(
    "01c44b54fe97004956a768d08f430919", // stable
    "09d6c71182237a2541dfd1f84c21719b"  // fallback (unstable)
)

// Fetch anime episode data from Kodik API natively (SSL-bypassed)
private suspend fun fetchKodikEmbedUrl(shikimoriId: Int): String? = withContext(Dispatchers.IO) {
    for (attempt in 0..1) {
        for (token in KODIK_TOKENS) {
            try {
                // Query Kodik API
                val apiUrl = "https://kodikapi.com/search" +
                        "?token=$token" +
                        "&shikimori_id=$shikimoriId" +
                        "&with_episodes=true" +
                        "&limit=1"
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Referer", "https://shikimori.io/")
                    .build()

                val response = sslBypassClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }
                val body = readBodyLimited(response) ?: continue

                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: continue
                if (results.length() == 0) continue

                val first = results.optJSONObject(0) ?: continue
                var link = first.optString("link", "") ?: continue
                if (link.isBlank()) continue

                // Normalize URL: //aniqit.com/... → https://aniqit.com/...
                if (link.startsWith("//")) link = "https:$link"
                if (!link.startsWith("http")) link = "https://$link"

                return@withContext link
            } catch (e: Exception) {
                continue
            }
        }
        if (attempt == 0) delay(2000)
    }

    // Fallback: try scraping kodik.cc find-player page directly
    fetchKodikEmbedFromPage(shikimoriId)
}

// Fallback: scrape the actual iframe src from kodik find-player page
private suspend fun fetchKodikEmbedFromPage(shikimoriId: Int): String? = withContext(Dispatchers.IO) {
    val mirrors = listOf(
        "https://kodik.cc/find-player?shikimori_id=$shikimoriId",
        "https://aniqit.com/find-player?shikimori_id=$shikimoriId",
        "https://kodik.info/find-player?shikimori_id=$shikimoriId"
    )
    for (url in mirrors) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://shikimori.io/animes/$shikimoriId")
                .build()
            val response = sslBypassClient.newCall(request).execute()
            if (!response.isSuccessful) continue
            val html = readBodyLimited(response, 512L * 1024) ?: continue

            // Parse iframe src
            val iframeRegex = Regex("""<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
            val match = iframeRegex.find(html)
            if (match != null) {
                var src = match.groupValues[1]
                if (src.startsWith("//")) src = "https:$src"
                return@withContext src
            }
            // The page itself might be the player
            return@withContext url
        } catch (e: Exception) {
            continue
        }
    }
    null
}

private suspend fun fetchDdbbPlayers(kinopoiskId: Int): List<DdbbPlayer> = withContext(Dispatchers.IO) {
    for (attempt in 0..2) {
        try {
            val req = Request.Builder()
                .url("https://p2.ddbb.lol/api/players?kinopoisk=$kinopoiskId")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Origin", "https://ddbb.lol")
                .addHeader("Referer", "https://ddbb.lol/")
                .build()

            val response = httpClient.newCall(req).execute()
            if (!response.isSuccessful) {
                response.close()
                if (attempt < 2) {
                    delay(2000L * (attempt + 1))
                    continue
                }
                return@withContext emptyList()
            }
            val body = readBodyLimited(response) ?: return@withContext emptyList()
            val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            val players = mutableListOf<DdbbPlayer>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val type = obj.optString("type", "Сервер ${i + 1}")
                val defaultUrl = obj.optString("iframeUrl", "")
                val transArr = obj.optJSONArray("translations")
                val resolvedUrl = if (transArr != null && transArr.length() > 0) {
                    transArr.optJSONObject(0)?.optString("iframeUrl", defaultUrl) ?: defaultUrl
                } else {
                    defaultUrl
                }
                if (resolvedUrl.isNotBlank()) {
                    players.add(DdbbPlayer(id = "${type.lowercase()}_$i", name = type, iframeUrl = resolvedUrl))
                }
            }
            if (players.isNotEmpty()) return@withContext players
        } catch (e: Exception) {
            if (attempt < 2) {
                delay(2000L * (attempt + 1))
                continue
            }
        }
    }
    emptyList()
}

private suspend fun fetchKinoboxPlayers(kinopoiskId: Int): List<DdbbPlayer> = withContext(Dispatchers.IO) {
    for (attempt in 0..1) {
        try {
            val req = Request.Builder()
                .url("https://kinobox.tv/api/players?kinopoisk=$kinopoiskId")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(req).execute()
            if (!response.isSuccessful) {
                response.close()
                if (attempt == 0) delay(1500)
                continue
            }
            val body = readBodyLimited(response) ?: continue
            val jsonArr = JSONArray(body)
            val players = mutableListOf<DdbbPlayer>()
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.optJSONObject(i) ?: continue
                val source = obj.optString("source", "Источник ${i + 1}")
                val iframeUrl = obj.optString("iframeUrl", "")
                if (iframeUrl.isNotBlank()) {
                    players.add(DdbbPlayer(id = "kinobox_${source.lowercase()}", name = "Kinobox: $source", iframeUrl = iframeUrl))
                }
            }
            if (players.isNotEmpty()) return@withContext players
        } catch (e: Exception) {
            if (attempt == 0) delay(1500)
        }
    }
    emptyList()
}

private const val HIDE_WEB_TOP_BAR_JS = """
(function () {
  try {
    var style = document.createElement('style');
    style.innerHTML = `
      * { -webkit-overflow-scrolling: none !important; }
      html, body { overflow: hidden !important; position: fixed !important; width: 100% !important; height: 100% !important; }
      header, footer, .header, .footer, .sidebar, .ads, .navigation, .top-menu, .footer-menu, 
      .kinopoisk-header, .kinopoisk-footer, #header, #footer, .bottom-menu { display: none !important; }
      body { background: black !important; margin: 0 !important; padding: 0 !important; }
      #player, .player-container, .kinobox_section, [id*="player"], [class*="player"] { 
        position: fixed !important; top: 0 !important; left: 0 !important; 
        width: 100vw !important; height: 100vh !important; 
        z-index: 999999 !important; padding: 0 !important; margin: 0 !important;
        background: black !important;
      }
      .kbt_select, .kbt_button, select.kbt_select, .kbt_list, .kbt_grid, .kinobox-controls { display: none !important; }
    `;
    document.head.appendChild(style);
  } catch (e) {}
})();
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppWebScreen(
    url: String
) {
    val activity = LocalContext.current.findActivity()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var inVideoFullscreen by remember { mutableStateOf(false) }
    var savedWebViewState by rememberSaveable(url) { mutableStateOf<Bundle?>(null) }
    val shikimoriId = remember(url) { extractShikimoriId(url) }
    val kinopoiskId = remember(url) { extractKinopoiskId(url) }

    // Controls visibility - touch anywhere to show/reset, auto-hides after 4s
    var showControls by remember { mutableStateOf(true) }
    var touchResetTrigger by remember { mutableStateOf(0L) }
    var showServerSheet by remember { mutableStateOf(false) }

    // Server list state
    var ddbbPlayers by remember { mutableStateOf<List<DdbbPlayer>>(emptyList()) }
    var isLoadingPlayers by remember { mutableStateOf(false) }
    var selectedPlayer by remember { mutableStateOf<DdbbPlayer?>(null) }
    var webViewError by remember { mutableStateOf<String?>(null) }
    var isPageLoading by remember { mutableStateOf(true) }
    var autoRetryCount by remember { mutableStateOf(0) }
    var retryTrigger by remember { mutableStateOf(0) }

    // Fetch players on launch
    LaunchedEffect(url, retryTrigger) {
        if (shikimoriId != null) {
            isLoadingPlayers = true
            // Try fetching real Kodik embed URL natively (bypasses SSL issues)
            val kodikUrl = fetchKodikEmbedUrl(shikimoriId)
            val animePlayers = buildList {
                if (kodikUrl != null) {
                    add(DdbbPlayer("kodik_native", "Kodik (рекомендуется)", kodikUrl))
                }
                // Direct player URLs as fallback — loaded in main WebView frame, SSL bypass works
                add(DdbbPlayer("aniqit", "Aniqit / Kodik (прямой)", "https://aniqit.com/find-player?shikimori_id=$shikimoriId"))
                add(DdbbPlayer("kodik_cc", "Kodik.cc (прямой)", "https://kodik.cc/find-player?shikimori_id=$shikimoriId"))
                add(DdbbPlayer("kodik_info", "Kodik.info (прямой)", "https://kodik.info/find-player?shikimori_id=$shikimoriId"))
            }
            ddbbPlayers = animePlayers
            if (selectedPlayer == null) {
                selectedPlayer = animePlayers.first()
            }
            isLoadingPlayers = false
        } else if (kinopoiskId != null) {
            isLoadingPlayers = true
            val ddbbList = fetchDdbbPlayers(kinopoiskId)
            val kboxList = fetchKinoboxPlayers(kinopoiskId)
            val combined = (kboxList + ddbbList).distinctBy { it.iframeUrl }
            ddbbPlayers = combined
            if (selectedPlayer == null && combined.isNotEmpty()) {
                selectedPlayer = combined.first()
            }
            isLoadingPlayers = false
        }
    }

    // Auto-switch to direct player if mirror was loading
    LaunchedEffect(selectedPlayer) {
        val sel = selectedPlayer
        val wv = webViewRef
        if (sel != null && wv != null) {
            val currentUrl = wv.url ?: ""
            // Only auto-switch if we are on the mirror site or it's an initial blank
            if (currentUrl.contains("kinopoisk.cx") || currentUrl.isEmpty() || currentUrl == "about:blank") {
                wv.loadUrl(sel.iframeUrl)
            }
        }
    }

    // Auto-hide controls after 4 seconds of idle (re-triggers on screen touch)
    LaunchedEffect(showControls, touchResetTrigger) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    DisposableEffect(activity) {
        PlayerPipState.isPlayerScreenVisible = true
        val previousOrientation = activity?.requestedOrientation
        val window = activity?.window
        val decorView = window?.decorView
        val insetsController = if (window != null && decorView != null) {
            WindowCompat.getInsetsController(window, decorView)
        } else null

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            PlayerPipState.isPlayerScreenVisible = false
            PlayerPipState.setActiveWebView(null)
            val currentWebView = webViewRef
            if (currentWebView != null) {
                savedWebViewState = Bundle().also { currentWebView.saveState(it) }
                // Clear WebView memory to prevent OOM
                currentWebView.loadUrl("about:blank")
                currentWebView.clearHistory()
                currentWebView.clearCache(true)
                currentWebView.removeAllViews()
            }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    BackHandler(enabled = inVideoFullscreen || webViewRef?.canGoBack() == true) {
        val webView = webViewRef
        if (inVideoFullscreen && webView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else {
            webView?.goBack()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val root = object : FrameLayout(context) {
                    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
                        if (ev?.action == MotionEvent.ACTION_DOWN) {
                            showControls = true
                            touchResetTrigger = System.currentTimeMillis()
                        }
                        return super.dispatchTouchEvent(ev)
                    }
                }
                val webView = WebView(context)
                val fullscreenContainer = FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    visibility = View.GONE
                }
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                root.addView(webView, lp)
                root.addView(fullscreenContainer, lp)

                webViewRef = webView
                PlayerPipState.setActiveWebView(webView)

                webView.setBackgroundColor(Color.BLACK)
                webView.keepScreenOn = true
                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.databaseEnabled = true
                webView.settings.javaScriptCanOpenWindowsAutomatically = true
                webView.settings.setSupportMultipleWindows(true)
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.loadsImagesAutomatically = true
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.allowFileAccess = true
                webView.settings.allowContentAccess = true
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                webView.settings.setGeolocationEnabled(false)
                webView.settings.defaultTextEncodingName = "UTF-8"
                // Limit WebView memory usage
                webView.settings.setSupportZoom(false)
                webView.settings.builtInZoomControls = false
                webView.settings.userAgentString = webView.settings.userAgentString
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.webChromeClient = object : WebChromeClient() {
                    private var customView: View? = null
                    private var customCallback: CustomViewCallback? = null

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view == null) return
                        if (customView != null) { callback?.onCustomViewHidden(); return }
                        customView = view
                        customCallback = callback
                        fullscreenContainer.addView(view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                        fullscreenContainer.visibility = View.VISIBLE
                        webView.visibility = View.GONE
                        inVideoFullscreen = true
                    }

                    override fun onHideCustomView() {
                        val view = customView ?: return
                        fullscreenContainer.removeView(view)
                        fullscreenContainer.visibility = View.GONE
                        webView.visibility = View.VISIBLE
                        customCallback?.onCustomViewHidden()
                        customView = null
                        customCallback = null
                        inVideoFullscreen = false
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                    // Bypass all SSL errors — needed for Kodik/Russian CDN certificates
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed()
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            val description = error?.description?.toString() ?: "Unknown error"
                            // Auto-retry once on connection issues
                            if (autoRetryCount < 2) {
                                autoRetryCount++
                                view?.postDelayed({ view.reload() }, 1500L)
                            } else {
                                webViewError = description
                                isPageLoading = false
                            }
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        isPageLoading = true
                        if (webViewError != null) webViewError = null
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        autoRetryCount = 0
                        webViewError = null
                        view?.evaluateJavascript(HIDE_WEB_TOP_BAR_JS, null)
                        val host = runCatching { Uri.parse(loadedUrl).host.orEmpty() }.getOrDefault("")
                        if (host.contains("ddbb.lol")) {
                            view?.evaluateJavascript(IFRAME_PIP_PATCH_JS, null)
                        }
                        // Hide loading screen after a small delay to ensure CSS applies
                        view?.postDelayed({ isPageLoading = false }, 300L)
                    }
                }

                // Load initial URL — always use direct loadUrl (not HTML wrapper)
                if (savedWebViewState == null) {
                    val sel = selectedPlayer
                    val target = when {
                        sel != null -> sel.iframeUrl
                        shikimoriId != null -> "https://aniqit.com/find-player?shikimori_id=$shikimoriId"
                        kinopoiskId != null -> url // Fallback to mirror site immediately
                        else -> url
                    }
                    webView.loadUrl(target)
                } else {
                    webView.restoreState(savedWebViewState!!)
                }
                root
            },
            update = { root ->
                val webView = (root as? ViewGroup)?.getChildAt(0) as? WebView ?: return@AndroidView
                webViewRef = webView
                PlayerPipState.setActiveWebView(webView)
            }
        )

        // Loading Overlay to hide website rendering
        AnimatedVisibility(
            visible = isPageLoading,
            enter = fadeIn(),
            exit = fadeOut(tween(400))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Подготовка плеера...",
                        style = MaterialTheme.typography.labelLarge,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Floating top-left back button to exit player
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                contentColor = androidx.compose.ui.graphics.Color.White,
                onClick = {
                    val webView = webViewRef
                    if (inVideoFullscreen && webView != null) {
                        webView.webChromeClient?.onHideCustomView()
                    } else {
                        (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: activity?.finish()
                    }
                }
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Floating server selector button — auto-hides, tap screen to show again
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                contentColor = androidx.compose.ui.graphics.Color.White,
                onClick = {
                    showServerSheet = true
                    showControls = true // keep visible while sheet is open
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isLoadingPlayers) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Серверы",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = selectedPlayer?.name ?: "Серверы",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (webViewError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Ошибка подключения",
                        style = MaterialTheme.typography.titleMedium,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    Text(
                        text = webViewError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        onClick = {
                            webViewError = null
                            autoRetryCount = 0
                            retryTrigger++
                            webViewRef?.reload()
                        }
                    ) {
                        Text(
                            text = "Повторить",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Native BottomSheet for Player Selection
        if (showServerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showServerSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Выберите источник / плеер",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (ddbbPlayers.isEmpty() && isLoadingPlayers) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else if (ddbbPlayers.isEmpty()) {
                        Text(
                            text = "Не удалось загрузить список серверов",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ddbbPlayers) { player ->
                                val isSelected = player.id == selectedPlayer?.id
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    onClick = {
                                        selectedPlayer = player
                                        showServerSheet = false
                                        val wv = webViewRef
                                        if (wv != null) {
                                            wv.loadUrl(player.iframeUrl)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = player.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Выбрано",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun extractKinopoiskId(url: String): Int? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val segments = uri.pathSegments
    if (segments.size < 2) return null
    val type = segments[0]
    val id = segments[1].toIntOrNull() ?: return null
    return if (type == "film" || type == "series") id else null
}

private fun extractShikimoriId(url: String): Int? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val param = uri.getQueryParameter("shikimori_id") ?: uri.getQueryParameter("shikimori")
    if (param != null) return param.toIntOrNull()
    val segments = uri.pathSegments
    val last = segments.lastOrNull()?.toIntOrNull()
    return if (url.contains("shikimori")) last else null
}

private const val IFRAME_PIP_PATCH_JS = """
(function () {
  try {
    if (window.__kinoPipPatchInstalled) return;
    window.__kinoPipPatchInstalled = true;
    var patchIframe = function(frame) {
      if (!frame || frame.tagName !== 'IFRAME') return;
      frame.allowFullscreen = true;
      frame.setAttribute('allow', 'autoplay; fullscreen; picture-in-picture; encrypted-media');
      frame.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
    };
    document.querySelectorAll('iframe').forEach(patchIframe);
    var observer = new MutationObserver(function(mutations) {
      mutations.forEach(function(mutation) {
        mutation.addedNodes.forEach(function(node) {
          if (node.tagName === 'IFRAME') {
            patchIframe(node);
          } else if (node.querySelectorAll) {
            node.querySelectorAll('iframe').forEach(patchIframe);
          }
        });
      });
    });
    observer.observe(document.body, { childList: true, subtree: true });
  } catch (e) {}
})();
"""
