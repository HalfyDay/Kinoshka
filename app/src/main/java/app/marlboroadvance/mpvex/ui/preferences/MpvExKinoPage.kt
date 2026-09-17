package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.ui.screens.SettingsHeaderCard

/**
 * Страница настроек плеера в стиле основных настроек приложения: та же
 * шапка-пилюля [SettingsHeaderCard] (40dp-круг «назад», titleLarge + подпись),
 * те же боковые отступы (14dp/10dp) и те же плитки групп
 * ([PreferenceCard] = 28dp surfaceContainer, как [KinoSettingsCard]).
 *
 * Шапка парит поверх контента без фоновой полосы: контент занимает весь экран
 * и уходит под пилюлю, поэтому [content] обязан начинаться с верхнего отступа
 * [topPad] (обычно contentPadding списка). Обрезка скролла видна только за
 * самой пилюлей, глухих полос, режущих строки, нет.
 */
@Composable
fun MpvExKinoPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    estimatedTopPad: Dp = 120.dp,
    content: @Composable BoxScope.(topPad: Dp) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        var headerHPx by remember { mutableStateOf<Int?>(null) }
        val topPad = headerHPx?.let { with(LocalDensity.current) { it.toDp() } + 10.dp }
            ?: estimatedTopPad
        content(topPad)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHPx = it.height }
        ) {
            SettingsHeaderCard(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                action = actions
            )
        }
    }
}
