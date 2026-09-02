package hd.kinoshka.app.ui.platform

import androidx.compose.runtime.Composable

/**
 * Платформенно-нейтральные действия уровня приложения для общих экранов:
 * системный тост и завершение приложения (двойной «Назад» на Android).
 */
class KinoPlatformActions(
    val exitApp: () -> Unit,
    val showToast: (message: String) -> Unit
)

@Composable
expect fun rememberKinoPlatformActions(): KinoPlatformActions

/**
 * Общий обработчик системной кнопки «Назад». Android — androidx.activity BackHandler,
 * desktop — no-op (системного «Назада» нет; перехват закрытия окна — отдельная задача).
 */
@Composable
expect fun KinoBackHandler(enabled: Boolean = true, onBack: () -> Unit)
