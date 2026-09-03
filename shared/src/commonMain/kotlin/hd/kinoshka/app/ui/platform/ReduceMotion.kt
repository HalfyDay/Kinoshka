package hd.kinoshka.app.ui.platform

import androidx.compose.runtime.Composable

/**
 * True, когда ОС просит уменьшить количество движений
 * (Android: шкала длительности аниматора = 0). На desktop всегда false.
 */
@Composable
expect fun rememberReduceMotion(): Boolean
