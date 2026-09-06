package hd.kinoshka.app.ui.components

import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable

// material3 расходится между таргетами: androidx (Android) депрекейтит
// rememberModalBottomSheetState(skipPartiallyExpanded) в пользу rememberBottomSheetState,
// а в JetBrains material3 (desktop) нового API ещё нет. Разницу прячем сюда.
@Composable
expect fun rememberKinoSheetState(skipPartiallyExpanded: Boolean = false): SheetState
