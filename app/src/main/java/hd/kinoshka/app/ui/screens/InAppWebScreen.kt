package hd.kinoshka.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun InAppWebScreen(
    url: String
) {
    val activity = LocalContext.current.findActivity()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var inVideoFullscreen by remember { mutableStateOf(false) }
    var savedWebViewState by rememberSaveable(url) { mutableStateOf<Bundle?>(null) }
    val kinopoiskId = remember(url) { extractKinopoiskId(url) }

    DisposableEffect(activity) {
        PlayerPipState.isPlayerScreenVisible = true
        val previousOrientation = activity?.requestedOrientation
        val window = activity?.window
        val decorView = window?.decorView
        val insetsController = if (window != null && decorView != null) {
            WindowCompat.getInsetsController(window, decorView)
        } else {
            null
        }

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
            val currentWebView = webViewRef
            if (currentWebView != null) {
                savedWebViewState = Bundle().also { bundle ->
                    currentWebView.saveState(bundle)
                }
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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val root = FrameLayout(context)
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
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customCallback = callback
                    fullscreenContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
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
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (loadedUrl.isNullOrBlank()) return
                    val host = runCatching { Uri.parse(loadedUrl).host.orEmpty() }.getOrDefault("")
                    if (host.contains("ddbb.lol")) {
                        view?.evaluateJavascript(IFRAME_PIP_PATCH_JS, null)
                    }
                }
            }

            if (savedWebViewState == null) {
                if (kinopoiskId != null) {
                    webView.loadUrl("https://ddbb.lol?id=$kinopoiskId&n=0")
                } else {
                    webView.loadUrl(url)
                }
            } else {
                webView.restoreState(savedWebViewState!!)
            }

            root
        },
        update = { root ->
            val webView = (root as? ViewGroup)?.getChildAt(0) as? WebView ?: return@AndroidView
            webViewRef = webView
            if (kinopoiskId == null && webView.url != url && savedWebViewState == null) {
                webView.loadUrl(url)
            }
        }
    )
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
    observer.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();
"""

