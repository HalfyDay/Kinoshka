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
import java.util.concurrent.TimeUnit

// Data model for player
data class DdbbPlayer(
    val id: String,
    val name: String,
    val iframeUrl: String
)

// Standard HTTP client (for DDBB API - no SSL issues)
private val httpClient by lazy {
    OkHttpClient.Builder()
        .dns(hd.kinoshka.app.utils.DohFallbackDns)
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

// HTTP client for Kodik API calls
private val kodikClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
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

                val response = kodikClient.newCall(request).execute()
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
    // kodik.cc/aniqit.com/kodik.info are NXDOMAIN globally now; these hosts still answer.
    val mirrors = listOf(
        "https://w.kdkonl.com/find-player?shikimori_id=$shikimoriId",
        "https://kodikplayer.com/find-player?shikimori_id=$shikimoriId"
    )
    for (url in mirrors) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://shikimori.io/animes/$shikimoriId")
                .build()
            val response = kodikClient.newCall(request).execute()
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

private fun isSafeEmbedUrl(value: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && host != "localhost" && host != "127.0.0.1" && host != "0.0.0.0"
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
            val seenTypes = mutableSetOf<String>()
            // One menu entry per source. Translations (озвучки) are deliberately NOT flattened
            // into duplicate rows: every embed player exposes its own voiceover picker, and the
            // aggregator's per-translation iframeUrls often repeat the same player URL, which is
            // how "Перевод N" entries ended up opening identical/mismatched streams.
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val type = obj.optString("type", "Источник ${i + 1}").trim()
                val defaultUrl = obj.optString("iframeUrl", "")
                if (!isSafeEmbedUrl(defaultUrl)) continue
                val key = type.lowercase().ifBlank { "source_$i" }
                if (!seenTypes.add(key)) continue
                players.add(DdbbPlayer(id = key, name = type.replaceFirstChar { it.uppercase() }, iframeUrl = defaultUrl))
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
      #player, .player-container, [id*="player"], [class*="player"] { 
        position: fixed !important; top: 0 !important; left: 0 !important; 
        width: 100vw !important; height: 100vh !important; 
        z-index: 999999 !important; padding: 0 !important; margin: 0 !important;
        background: black !important;
      }
      html, body, .video-container, .player-wrapper, .embed-responsive,
      .video-js, video {
        height: 100vh !important; max-height: 100vh !important; overflow: hidden !important;
      }
      iframe { max-height: 100vh !important; }
      [class*="ad"], [id*="ad"], [class*="banner"], [id*="banner"],
      [class*="popup"], [id*="popup"], [class*="overlay"],
      iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
      iframe[src*="adnxs"], iframe[src*="propeller"], iframe[src*="clickadu"],
      iframe[src*="buzzoola"], iframe[src*="bidderstack"],
      iframe[src*="timing-js"], iframe[src*="targetads"], iframe[src*="adriver"],
      iframe[src*="traffaret"], iframe[src*="adtec"], iframe[src*="video-mech"],
      iframe[src*="moe.video"], iframe[src*="mail.ru/vast"],
      .preloader-overlay, .ad-block, .adblock, .ads-block { display: none !important; }
    `;
    document.head.appendChild(style);
  } catch (e) {}
})();
"""

// Lighter variant for known video-source embeds (alloha/turbo/veoveo/collaps): stretches the
// player to fill the screen but does NOT hide by wildcard class matches ([class*="popup"],
// [class*="overlay"], [class*="banner"]...) — those nuke the players' own UI (quality menus,
// voiceover pickers, control popups) and were part of why these sources looked broken.
private const val PLAYER_EMBED_CSS_JS = """
(function () {
  try {
    var style = document.createElement('style');
    style.innerHTML = `
      html, body {
        overflow: hidden !important;
        width: 100% !important; height: 100% !important;
        margin: 0 !important; padding: 0 !important;
        background: black !important;
      }
      #player, .player-container, [id*="player"]:not([id*="player-ad"]),
      [class*="player-wrapper"], [class*="player-container"],
      .video-js, .jwplayer, video {
        position: fixed !important; top: 0 !important; left: 0 !important;
        width: 100vw !important; height: 100vh !important;
        max-height: 100vh !important;
        z-index: 2147483000 !important;
        margin: 0 !important; padding: 0 !important;
        background: black !important;
      }
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
    val context = LocalContext.current
    val activity = context.findActivity()
    val playerMode = remember {
        val prefs = context.getSharedPreferences("kino_user_state", android.content.Context.MODE_PRIVATE)
        val modeName = prefs.getString("player_mode", null) ?: "MPVEX"
        runCatching { hd.kinoshka.app.data.local.PlayerMode.valueOf(modeName) }
            .getOrDefault(hd.kinoshka.app.data.local.PlayerMode.MPVEX)
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var inVideoFullscreen by remember { mutableStateOf(false) }
    var savedWebViewState by rememberSaveable(url) { mutableStateOf<Bundle?>(null) }
    val shikimoriId = remember(url) { extractShikimoriId(url) }
    val kinopoiskId = remember(url) { extractKinopoiskId(url) }
    // Tracks whether we've already fallen back from .cx to .ws (one-shot, per url) so a network
    // failure on the primary mirror retries the alternate mirror exactly once.
    var wsFallbackAttempted by remember(url) { mutableStateOf(false) }

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
    // Auto-advance budget: a dead first source walks down the list instead of showing an error.
    // Reset whenever the user acts (manual pick / retry / new url).
    var autoSourceFallbackCount by remember { mutableStateOf(0) }

    // Fetch players on launch
    LaunchedEffect(url, retryTrigger) {
        autoSourceFallbackCount = 0
        if (shikimoriId != null) {
            isLoadingPlayers = true
            // Try fetching real Kodik embed URL natively (bypasses SSL issues)
            val kodikUrl = fetchKodikEmbedUrl(shikimoriId)
            val animePlayers = buildList {
                if (kodikUrl != null) {
                    add(DdbbPlayer("kodik_native", "Kodik (рекомендуется)", kodikUrl))
                }
                // NOTE: aniqit.com / kodik.cc / kodik.info are NXDOMAIN globally now (the network
                // moved to new player hosts), so static find-player links to them are gone.
                if (isEmpty()) {
                    add(DdbbPlayer("kodik_find_player", "Kodik (find-player)", "https://kodikplayer.com/find-player?shikimori_id=$shikimoriId"))
                }
            }
            ddbbPlayers = animePlayers
            if (selectedPlayer == null) {
                selectedPlayer = animePlayers.firstOrNull()
            }
            isLoadingPlayers = false
        } else if (kinopoiskId != null) {
            isLoadingPlayers = true
            if (playerMode == hd.kinoshka.app.data.local.PlayerMode.SITE) {
                // Load mirror site directly — it hosts all players
                val mirrorUrl = buildMirrorUrl(url, kinopoiskId)
                val mirrorPlayer = DdbbPlayer("mirror", "Зеркало (kinopoisk.ws)", mirrorUrl)
                ddbbPlayers = listOf(mirrorPlayer)
                selectedPlayer = mirrorPlayer
            } else {
                // Fetch ddbb players
                val fetched = fetchDdbbPlayers(kinopoiskId)
                val ddbbList = if (fetched.isNotEmpty()) {
                    fetched.sortedBy { player ->
                        when {
                            player.id.contains("collaps") -> 0
                            player.id.contains("alloha") -> 1
                            player.id.contains("turbo") -> 2
                            player.id.contains("veoveo") -> 3
                            else -> 4
                        }
                    }
                } else {
                    // Fallback — try Kodik embed for this kinopoisk ID, then the live find-player page.
                    // vibix.cc / cdnmovies.net are dead domains (NXDOMAIN) and only produced error
                    // pages; kodikplayer.com is Kodik's current player host.
                    val kodikEmbed = hd.kinoshka.app.data.source.AnimeStreamResolver.fetchKodikEmbedForKinopoisk(kinopoiskId)
                    buildList {
                        if (kodikEmbed != null) {
                            add(DdbbPlayer("kodik_native", "Kodik (рекомендуется)", kodikEmbed))
                        }
                        add(DdbbPlayer("kodik_find_player", "Kodik (find-player)", "https://kodikplayer.com/find-player?kinopoisk_id=$kinopoiskId"))
                    }
                }
                ddbbPlayers = ddbbList
                selectedPlayer = ddbbList.firstOrNull()
            }
            isLoadingPlayers = false
        }
    }

    // When user picks a different source from the sheet, load its URL
    LaunchedEffect(selectedPlayer) {
        val sel = selectedPlayer
        val wv = webViewRef
        if (sel != null && wv != null) {
            val currentUrl = wv.url ?: ""
            // Don't reload if already on this URL
            if (sel.iframeUrl != currentUrl) {
                wv.loadUrl(sel.iframeUrl, playerLoadHeaders(sel))
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

    // Safety timeout — mirror site can be slow behind protection
    LaunchedEffect(isPageLoading, retryTrigger) {
        if (isPageLoading) {
            delay(30_000)
            if (isPageLoading && webViewError == null) {
                isPageLoading = false
            }
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

    // Only intercept back when in fullscreen video — otherwise let NavHost popBackStack
    BackHandler(enabled = inVideoFullscreen) {
        webViewRef?.webChromeClient?.onHideCustomView()
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
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                webView.settings.setGeolocationEnabled(false)
                webView.settings.defaultTextEncodingName = "UTF-8"
                val enableZoom = playerMode == hd.kinoshka.app.data.local.PlayerMode.SITE
                webView.settings.setSupportZoom(enableZoom)
                webView.settings.builtInZoomControls = enableZoom
                webView.settings.displayZoomControls = false
                webView.settings.userAgentString = webView.settings.userAgentString
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (isAdUrl(url)) {
                            return android.webkit.WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                        }
                        return null
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        // Known video-source hosts frequently ship broken/expired cert chains that
                        // desktop browsers tolerate; cancelling here made alloha/turbo/veoveo look
                        // dead while collaps worked. Proceed only for whitelisted players.
                        val failedHost = runCatching { Uri.parse(error?.url.orEmpty()).host?.lowercase().orEmpty() }
                            .getOrDefault("")
                            .ifBlank { runCatching { Uri.parse(view?.url.orEmpty()).host?.lowercase().orEmpty() }.getOrDefault("") }
                        if (PLAYER_HOST_WHITELIST.any { failedHost.contains(it) }) {
                            handler?.proceed()
                        } else {
                            handler?.cancel()
                            webViewError = "Источник отклонён из-за ошибки защищённого соединения"
                            isPageLoading = false
                        }
                    }

                    // Don't treat HTTP errors as fatal — pages often handle redirects via JS
                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                        if (request?.isForMainFrame != true) return
                    }

                    // Network-level failures on the .cx main frame: fall back to the .ws mirror once.
                    // HTTP 404 is NOT a failure here — it's the expected interstitial that JS-redirects to
                    // the real player page (handled by onReceivedHttpError staying a no-op).
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame != true) return
                        android.util.Log.w("InAppWeb", "WebView error: ${error?.errorCode} ${error?.description} for ${request.url}")
                        val failedUrl = request.url?.toString() ?: return

                        // Web-player mode: a dead source advances to the next one automatically.
                        if (
                            playerMode == hd.kinoshka.app.data.local.PlayerMode.DDBB &&
                            shikimoriId == null &&
                            autoSourceFallbackCount < MAX_AUTO_SOURCE_FALLBACKS
                        ) {
                            val idx = ddbbPlayers.indexOfFirst { it.iframeUrl == failedUrl }
                            if (idx >= 0 && idx + 1 < ddbbPlayers.size) {
                                autoSourceFallbackCount++
                                val next = ddbbPlayers[idx + 1]
                                android.util.Log.i("InAppWeb", "Source failed, falling back to ${next.name}")
                                view?.post {
                                    selectedPlayer = next
                                    webViewRef?.loadUrl(next.iframeUrl, playerLoadHeaders(next))
                                }
                                return
                            }
                        }

                        if (!wsFallbackAttempted && failedUrl.contains("kinopoisk.cx")) {
                            wsFallbackAttempted = true
                            val wsUrl = failedUrl.replace("www.kinopoisk.cx", KINOPOISK_WS_HOST)
                            android.util.Log.i("InAppWeb", "Falling back to .ws mirror: $wsUrl")
                            view?.loadUrl(wsUrl)
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
                        val host = runCatching { Uri.parse(loadedUrl).host.orEmpty() }.getOrDefault("")
                        val isKinopoiskMirror = host.contains("kinopoisk.ws") || host.contains("kinopoisk.cx") || host.contains("peq.pkvbn.xyz")
                        if (host.contains("peq.pkvbn.xyz")) {
                            // Mirror site — hide everything except the player
                            view?.evaluateJavascript(PEQ_MIRROR_CSS_JS, null)
                            view?.postDelayed({ isPageLoading = false }, 1000L)
                        } else {
                            // kinopoisk mirrors (.ws/.cx) host a real player page that redirects in;
                            // don't force overflow:hidden there (it freezes info/404 interstitials and
                            // is the root cause of "opens the site but doesn't scroll"). Just strip ads.
                            if (isVideoSourceHost(host)) {
                                // Embed players: fullscreen stretch without wildcard UI nukes,
                                // plus the ad-overlay watchdog.
                                view?.evaluateJavascript(PLAYER_EMBED_CSS_JS, null)
                                view?.evaluateJavascript(REMOVE_ADS_JS, null)
                                view?.evaluateJavascript(PLAYER_ANTIADS_JS, null)
                            } else if (!isKinopoiskMirror) {
                                view?.evaluateJavascript(HIDE_WEB_TOP_BAR_JS, null)
                                view?.evaluateJavascript(REMOVE_ADS_JS, null)
                            }
                            if (isKinopoiskMirror && playerMode != hd.kinoshka.app.data.local.PlayerMode.SITE) {
                                // Try to isolate the video player on the mirror page for a fullscreen feel.
                                view?.evaluateJavascript(PEQ_MIRROR_CSS_JS, null)
                            }
                            view?.postDelayed({ isPageLoading = false }, 1500L)
                        }
                        if (host.contains("ddbb.lol")) {
                            view?.evaluateJavascript(IFRAME_PIP_PATCH_JS, null)
                        }
                    }
                }

                // Load initial URL — always use direct loadUrl (not HTML wrapper)
                if (savedWebViewState == null) {
                    val target = when {
                        // Anime sources resolve asynchronously (LaunchedEffect above); the dead
                        // static find-player hosts would just render an error before the swap.
                        shikimoriId != null -> "about:blank"
                        kinopoiskId != null && playerMode == hd.kinoshka.app.data.local.PlayerMode.SITE -> buildMirrorUrl(url, kinopoiskId)
                        kinopoiskId != null && playerMode == hd.kinoshka.app.data.local.PlayerMode.DDBB -> selectedPlayer?.iframeUrl ?: "about:blank"
                        else -> normalizeKinopoiskUrl(url)
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

        // Loading Overlay
        AnimatedVisibility(
            visible = isPageLoading,
            enter = fadeIn(),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
            )
        }

        // Floating source selector button — auto-hides, tap screen to show again
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
                            contentDescription = "Источники",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = selectedPlayer?.name ?: "Источники",
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
                            text = "Не удалось загрузить список источников",
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
                                        autoSourceFallbackCount = 0
                                        val wv = webViewRef
                                        if (wv != null) {
                                            wv.loadUrl(player.iframeUrl, playerLoadHeaders(player))
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

// Convert kinopoisk.ru URL to kinopoisk.ws mirror
// https://www.kinopoisk.ru/series/460586/ → https://kinopoisk.ws/series/460586/
private fun buildMirrorUrl(originalUrl: String, kinopoiskId: Int): String {
    val uri = runCatching { Uri.parse(originalUrl) }.getOrNull()
    val type = uri?.pathSegments?.firstOrNull()
    return if (type == "film" || type == "series") {
        "https://kinopoisk.ws/$type/$kinopoiskId/"
    } else {
        "https://kinopoisk.ws/film/$kinopoiskId/"
    }
}

// Rewrite a kinopoisk.ru URL to a mirror the app can actually load (.cx, falling back to .ws).
// kinopoisk.ru is geo/CDN-blocked for in-WebView playback; kinopoisk.cx returns a 404 page that
// then redirects (via JS/meta) to the real player page — WebView follows that chain natively
// because shouldOverrideUrlLoading returns false. Path is preserved so extractKinopoiskId still
// works. Anything not on kinopoisk.ru is returned unchanged.
private fun normalizeKinopoiskUrl(url: String): String {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    if (!host.endsWith("kinopoisk.ru")) return url
    val path = uri.path ?: ""
    val query = uri.query?.let { "?$it" } ?: ""
    // Prefer .cx (404→player redirect). Caller can fall back to .ws if .cx is unreachable.
    return "https://www.kinopoisk.cx$path$query"
}

// The .ws mirror host, used as the 404-fallback target.
private const val KINOPOISK_WS_HOST = "www.kinopoisk.ws"

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

// Known ad/tracking hosts blocked in WebView. Matched against the URL HOST only.
private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "ads.", "ad.", "analytics.", "tracking.", "pixel.", "beacon.",
    "popunder.", "popads.", "adnxs.com", "adskeeper.",
    "propellerads.", "clickadu.", "hilltopads.", "exoclick.",
    "juicyads.", "trafficjunky.", "eroadvertising.",
    "adbrite.", "advertising.com", "adroll.com",
    "mc.yandex.ru", "an.yandex.ru",
    "ssp.io", "adfox.", "bannerbook.", "rnet.plus",
    "tns-counter.ru", "mediascope.net",
    "imasdk.googleapis.com", "pubmatic.com", "rubiconproject.com",
    "sharethrough.com", "outbrain.com", "taboola.com",
    "criteo.com", "casalemedia.com", "indexexchange.com",
    "smartadserver.com", "adform.net", "serving-sys.com",
    "track-us.ru", "5775.info", "winline", "spix.agl011.art",
    "buzzoola.com", "bidderstack.com",
    "stun.fastscr.cc", "turn.zcvh.net",
    // New domains from logs
    "timing-js-menu.xyz", "targetads.io", "adriver.ru",
    "otm-r.com", "betweendigital.com", "traffaret.com",
    "adtec.ru", "video-mech.ru", "ad.mail.ru", "ad.moe.video"
)

// Video sources whose CDN paths legitimately contain things like "/ads/" or "banner" (creative
// assets, pre-roll config). Blocking by substring over the full URL used to cut their players'
// own requests and break playback — alloha/turbo/veoveo died exactly this way while collaps
// survived. Never intercept anything on these hosts.
private val PLAYER_HOST_WHITELIST = listOf(
    "alloha", "collaps", "kodik", "aniqit", "kdkonl", "vsh.my",
    "turbo", "veoveo", "vibix", "cdnmovies", "ddbb.lol",
    "kinopoisk.ws", "kinopoisk.cx", "pkvbn.xyz", "s3.turbovi.ru", "allohatv"
)

private fun isAdUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
    if (host.isEmpty()) return false
    if (PLAYER_HOST_WHITELIST.any { host.contains(it) }) return false
    return AD_HOSTS.any { host.contains(it) }
}

// True when the loaded page is one of the video-source embeds we inject the anti-ad watchdog into.
private fun isVideoSourceHost(host: String): Boolean {
    val h = host.lowercase()
    if (h.isEmpty()) return false
    return PLAYER_HOST_WHITELIST.any { h.contains(it) } && !h.contains("kinopoisk") && !h.contains("pkvbn")
}

// Referer sent with ddbb-sourced embed loads. Several embed players check that the frame was
// opened from an aggregator page and refuse to play on a cold referer.
private fun sourceLoadHeaders(): Map<String, String> = mapOf(
    "Referer" to "https://ddbb.lol/",
    "Origin" to "https://ddbb.lol"
)

/**
 * Per-source load headers. The ddbb aggregator's own embeds (collaps/turbo/veoveo) want the
 * aggregator referer; everything else — the kinopoisk mirror, kodik find-player pages, vibix,
 * cdnmovies, and especially ALLOHA — must load CLEAN: sending a foreign Referer/Origin to the
 * mirror trips its anti-bot wall, and alloha gates playback ("контент недоступен в вашем
 * регионе") when it sees an unexpected referrer.
 */
private fun playerLoadHeaders(player: DdbbPlayer): Map<String, String> {
    val id = player.id.lowercase()
    return if (id.contains("collaps") || id.contains("turbo") || id.contains("veoveo")) {
        sourceLoadHeaders()
    } else {
        emptyMap()
    }
}

private const val MAX_AUTO_SOURCE_FALLBACKS = 3

private const val REMOVE_ADS_JS = """
(function () {
  try {
    // Neutralize popups/popunders/redirects before anything can hook clicks
    window.open = function () { return null; };

    // Remove iframes that look like ads
    var frames = document.querySelectorAll('iframe');
    for (var i = frames.length - 1; i >= 0; i--) {
      var src = (frames[i].src || '').toLowerCase();
      if (src.includes('doubleclick') || src.includes('googlesyndication') ||
          src.includes('adnxs') || src.includes('propeller') ||
          src.includes('clickadu') || src.includes('popunder') ||
          src.includes('exoclick') || src.includes('advertising') ||
          src.includes('adskeeper') || src.includes('hilltopads') ||
          src.includes('buzzoola') ||
          src.includes('bidderstack') || src.includes('timing-js') ||
          src.includes('targetads') || src.includes('adriver') ||
          src.includes('traffaret') || src.includes('adtec') ||
          src.includes('video-mech') || src.includes('moe.video') ||
          src.includes('ad.mail.ru')) {
        frames[i].remove();
      }
    }
    // Remove explicit ad containers only. Deliberately NOT using [class*="ad"] /
    // [class*="banner"] wildcards here: they match "header", "download", player chrome and
    // wiped legit UI on alloha/turbo/veoveo embeds.
    var selectors = '.adsbygoogle, [id*="adBanner"], [class*="ad-banner"], ' +
      '[id*="google_ads"], .ad-block, .ads-block, .adblock-detected, ' +
      '[class*="popunder"], [class*="popup-ad"], [data-ad], [data-adv]';
    document.querySelectorAll(selectors).forEach(function(el) { el.remove(); });
  } catch (e) {}
})();
"""

// Injected on video-source hosts (alloha/turbo/veoveo/collaps). Kills fullscreen ad overlays:
// popups are already disabled by REMOVE_ADS_JS, this one removes fixed-position overlays that
// sit ABOVE the player and re-appears them via MutationObserver.
private const val PLAYER_ANTIADS_JS = """
(function () {
  try {
    if (window.__kinoAntiAdsInstalled) return;
    window.__kinoAntiAdsInstalled = true;

    function isAdOverlay(el) {
      if (!el || el === document.body || el === document.documentElement) return false;
      var st;
      try { st = getComputedStyle(el); } catch (e) { return false; }
      if (st.position !== 'fixed' && st.position !== 'absolute') return false;
      if (st.display === 'none' || st.visibility === 'hidden') return false;
      var z = parseInt(st.zIndex, 10);
      if (isNaN(z) || z < 100) return false;
      var r = el.getBoundingClientRect();
      if (r.width < innerWidth * 0.6 || r.height < innerHeight * 0.5) return false;
      // Player wrappers themselves are usually marked as such — keep them
      var id = ((el.id || '') + ' ' + (el.className || '')).toLowerCase();
      if (/player|video|jw|plyr|vjs/.test(id)) return false;
      // Contains a clickable close hint? still an ad, remove it anyway.
      return true;
    }

    function sweep() {
      try {
        var all = document.body ? document.body.querySelectorAll('div, iframe, a') : [];
        for (var i = 0; i < all.length; i++) {
          if (isAdOverlay(all[i])) all[i].remove();
        }
      } catch (e) {}
    }

    sweep();
    setInterval(sweep, 1500);

    new MutationObserver(sweep).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();
"""


private const val PEQ_MIRROR_CSS_JS = """
(function () {
  try {
    var style = document.createElement('style');
    style.id = 'kino-app-mirror';
    style.textContent = `
      /* Hide header, title, poster, footer, ads, disclaimer */
      .site-header, .h2, .footer, .disclaimer, .social,
      #film > img, .spacer-md,
      .player-block__label,
      /* Ad iframes/scripts */
      iframe[src*="moviead"], iframe[src*="vak345"],
      script[src*="vak345"], script[src*="moviead"],
      /* Offline banner, liveinternet counter */
      #offline-banner,
      div[id^="iDaZY"],
      /* Anything with ad/tracking classes */
      [class*="ad"], [id*="ad-"], [class*="banner"] {
        display: none !important;
      }
      /* Hide background images */
      body { background: #111 !important; }
    `;
    document.head.appendChild(style);
  } catch (e) {}
})();
"""

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
