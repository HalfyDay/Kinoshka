package hd.kinoshka.app.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberKinoPlatformActions(): KinoPlatformActions {
    val context = LocalContext.current
    return remember(context) {
        KinoPlatformActions(
            exitApp = { context.findActivity()?.finish() },
            showToast = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        )
    }
}

@Composable
actual fun KinoBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
