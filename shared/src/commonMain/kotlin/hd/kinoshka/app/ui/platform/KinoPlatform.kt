package hd.kinoshka.app.ui.platform

import androidx.compose.runtime.Composable

/**
 * Платформенно-нейтральные швы для общих экранов. KinoPlatformActions живёт в jvmShared
 * (ему нужен java.io.File для cacheDir), чистые expect-эффекты — здесь, в commonMain.
 */

/** Android: прячет системный статус-бар на время полноэкранного просмотрщика; desktop — no-op. */
@Composable
expect fun KinoHideSystemBarsEffect()

/** Android: диалоговое окно наследует цвет навбара активити (низ шита не белеет); desktop — no-op. */
@Composable
expect fun KinoKeepDialogNavBarEffect()

/** Android: пока просмотрщик кадров открыт, портретный лок активности отпускается датчикам; desktop — no-op. */
@Composable
expect fun KinoFreeOrientationEffect()

/**
 * Полноэкранный Dialog общих экранов: на Android окно рисуется ПОД системными барами
 * (decorFitsSystemWindows=false), у compose-multiplatform этот параметр недоступен.
 */
@Composable
expect fun KinoFullscreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

/**
 * Общий обработчик системной кнопки «Назад». Android — androidx.activity BackHandler,
 * desktop — no-op (системного «Назада» нет; перехват закрытия окна — отдельная задача).
 */
@Composable
expect fun KinoBackHandler(enabled: Boolean = true, onBack: () -> Unit)
