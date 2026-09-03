package hd.kinoshka.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.system.exitProcess

@Composable
actual fun rememberKinoPlatformActions(): KinoPlatformActions = remember {
    KinoPlatformActions(
        exitApp = { exitProcess(0) },
        showToast = { message -> println("[Kino] $message") },
        shareText = { text ->
            runCatching {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(text), null)
            }
        },
        copyText = { text ->
            runCatching {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(text), null)
            }
        },
        openInBrowser = { url ->
            runCatching {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }.onFailure { println("[Kino] Не удалось открыть ссылку: $url") }
        },
        cacheDir = File(System.getProperty("user.home"), ".kino-desktop").apply { mkdirs() }
    )
}

@Composable
actual fun KinoBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // На desktop системного «Назада» нет — сознательный no-op.
}

@Composable
actual fun KinoHideSystemBarsEffect() {
    // Системных баров на desktop нет — no-op.
}

@Composable
actual fun KinoKeepDialogNavBarEffect() {
    // Навбара активити на desktop нет — no-op.
}

@Composable
actual fun KinoFreeOrientationEffect() {
    // Ориентация окна на desktop не управляется — no-op.
}

@Composable
actual fun KinoFullscreenDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        content()
    }
}
