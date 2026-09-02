package hd.kinoshka.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.system.exitProcess

@Composable
actual fun rememberKinoPlatformActions(): KinoPlatformActions = remember {
    KinoPlatformActions(
        exitApp = { exitProcess(0) },
        showToast = { message -> println("[Kino] $message") }
    )
}

@Composable
actual fun KinoBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // На desktop системного «Назада» нет — сознательный no-op.
}
