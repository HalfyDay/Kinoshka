package hd.kinoshka.app.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun rememberKinoPlatformActions(): KinoPlatformActions {
    val context = LocalContext.current
    return remember(context) {
        KinoPlatformActions(
            exitApp = { context.findActivity()?.finish() },
            showToast = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
            shareText = { text ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Поделиться"))
            },
            copyText = { text ->
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("value", text))
            },
            openInBrowser = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            },
            cacheDir = context.cacheDir
        )
    }
}

@Composable
actual fun KinoBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun KinoHideSystemBarsEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activityWindow = view.context.findActivity()?.window
        val controller =
            activityWindow?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

@Composable
// navigationBarColor deprecated с API 35 (там игнорируется из-за edge-to-edge), но на старых версиях замены нет.
@Suppress("DEPRECATION")
actual fun KinoKeepDialogNavBarEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val activityWindow = view.context.findActivity()?.window
        if (dialogWindow == null || activityWindow == null) {
            onDispose { }
        } else {
            val oldNavColor = dialogWindow.navigationBarColor
            val oldLightNav =
                WindowCompat.getInsetsController(dialogWindow, view).isAppearanceLightNavigationBars
            val oldContrastEnforced =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced
                } else {
                    false
                }

            val activityController =
                WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
            val dialogController = WindowCompat.getInsetsController(dialogWindow, view)
            dialogWindow.navigationBarColor = activityWindow.navigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialogWindow.isNavigationBarContrastEnforced =
                    activityWindow.isNavigationBarContrastEnforced
            }
            dialogController.isAppearanceLightNavigationBars =
                activityController.isAppearanceLightNavigationBars

            onDispose {
                dialogWindow.navigationBarColor = oldNavColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced = oldContrastEnforced
                }
                dialogController.isAppearanceLightNavigationBars = oldLightNav
            }
        }
    }
}

@Composable
actual fun KinoFreeOrientationEffect() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation = previousOrientation
        }
    }
}

@Composable
actual fun KinoFullscreenDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // Окно диалога должно рисоваться ПОД системными барами: с дефолтным
        // decorFitsSystemWindows=true сверху и снизу остаётся зазор с фоном Details-экрана.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        content()
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
