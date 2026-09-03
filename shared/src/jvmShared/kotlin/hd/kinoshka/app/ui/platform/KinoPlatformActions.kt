package hd.kinoshka.app.ui.platform

import androidx.compose.runtime.Composable
import java.io.File

/**
 * Платформенно-нейтральные действия уровня приложения для общих экранов:
 * тост, завершение приложения, системный шер и директория кэша для ApiClient.
 * Живёт в jvmShared: commonMain обязан оставаться платформенно-чистым (нет java.io.File).
 */
class KinoPlatformActions(
    val exitApp: () -> Unit,
    val showToast: (message: String) -> Unit,
    /** Поделиться текстом/ссылкой: Android — системный chooser, desktop — буфер обмена. */
    val shareText: (text: String) -> Unit,
    /** Скопировать в буфер обмена без системного меню (тап по значению на экране деталей). */
    val copyText: (text: String) -> Unit,
    /** Открыть внешнюю ссылку: Android — ACTION_VIEW, desktop — системный браузер. */
    val openInBrowser: (url: String) -> Unit,
    /** Кэш-директория для ApiClient-фабрик (context.cacheDir / ~/.kino-desktop). */
    val cacheDir: File
)

@Composable
expect fun rememberKinoPlatformActions(): KinoPlatformActions
