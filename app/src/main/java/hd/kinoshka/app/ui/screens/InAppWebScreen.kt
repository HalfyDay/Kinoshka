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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Data model for DDBB API player
data class DdbbPlayer(
    val id: String,
    val name: String,
    val iframeUrl: String
)

private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}

private suspend fun fetchDdbbPlayers(kinopoiskId: Int): List<DdbbPlayer> = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder()
            .url("https://p2.ddbb.lol/api/players?kinopoisk=$kinopoiskId")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Origin", "https://ddbb.lol")
            .addHeader("Referer", "https://ddbb.lol/")
            .build()

        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            val players = mutableListOf<DdbbPlayer>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val type = obj.optString("type", "Сервер ${i + 1}")
                val defaultUrl = obj.optString("iframeUrl", "")
                // Pick first working translation URL if available
                val transArr = obj.optJSONArray("translations")
                val resolvedUrl = if (transArr != null && transArr.length() > 0) {
                    transArr.optJSONObject(0)?.optString("iframeUrl", defaultUrl) ?: defaultUrl
                } else {
                    defaultUrl
                }
                if (resolvedUrl.isNotBlank()) {
                    players.add(DdbbPlayer(id = type.lowercase(), name = type, iframeUrl = resolvedUrl))
                }
            }
            players
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private const val HIDE_WEB_TOP_BAR_JS = """
(function () {
  try {
    var style = document.createElement('style');
    style.innerHTML = `
      .kinobox_section { padding-top: 0 !important; }
      .kbt_select, .kbt_button, select.kbt_select, .kbt_list, .kbt_grid { display: none !important; }
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
    val kinopoiskId = remember(url) { extractKinopoiskId(url) }

    // Controls visibility - touch anywhere to show/reset, auto-hides after 4s
    var showControls by remember { mutableStateOf(true) }
    var touchResetTrigger by remember { mutableStateOf(0L) }
    var showServerSheet by remember { mutableStateOf(false) }

    // DDBB server list state
    var ddbbPlayers by remember { mutableStateOf<List<DdbbPlayer>>(emptyList()) }
    var isLoadingPlayers by remember { mutableStateOf(false) }
    var selectedPlayer by remember { mutableStateOf<DdbbPlayer?>(null) }

    // Fetch DDBB players from API on launch
    LaunchedEffect(kinopoiskId) {
        if (kinopoiskId != null) {
            isLoadingPlayers = true
            val players = fetchDdbbPlayers(kinopoiskId)
            ddbbPlayers = players
            if (selectedPlayer == null && players.isNotEmpty()) {
                selectedPlayer = players.first()
            }
            isLoadingPlayers = false
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
            }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
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
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.javaScriptCanOpenWindowsAutomatically = true
                webView.settings.setSupportMultipleWindows(true)
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.loadsImagesAutomatically = true
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.allowFileAccess = true
                webView.settings.allowContentAccess = true
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
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

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        view?.evaluateJavascript(HIDE_WEB_TOP_BAR_JS, null)
                        val host = runCatching { Uri.parse(loadedUrl).host.orEmpty() }.getOrDefault("")
                        if (host.contains("ddbb.lol")) {
                            view?.evaluateJavascript(IFRAME_PIP_PATCH_JS, null)
                        }
                    }
                }

                // Load initial URL
                if (savedWebViewState == null) {
                    val initialUrl = if (kinopoiskId != null) {
                        "https://ddbb.lol?id=$kinopoiskId&n=0"
                    } else url
                    webView.loadUrl(initialUrl)
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
                        text = selectedPlayer?.name ?: "Серверы DDBB",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Native BottomSheet for DDBB Player Selection
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
                        text = "Выберите сервер DDBB",
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
                                        webViewRef?.loadUrl(player.iframeUrl)
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
