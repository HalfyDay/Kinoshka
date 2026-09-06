package hd.kinoshka.app.ui.components

import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

// На desktop этот перегруженный вариант не депрекечен (material3 JetBrains старше androidx).
@Composable
actual fun rememberKinoSheetState(skipPartiallyExpanded: Boolean): SheetState =
    rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
