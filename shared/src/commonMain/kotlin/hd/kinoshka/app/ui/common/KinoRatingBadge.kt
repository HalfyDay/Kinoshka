package hd.kinoshka.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Первый общий UI-компонент Android+desktop: бейдж рейтинга на постере.
 * Небольшие общие компоненты (M2) будут переезжать сюда по мере миграции экранов.
 */
@Composable
fun KinoRatingBadge(rating: Double, modifier: Modifier = Modifier, cornerRadius: Dp = 6.dp) {
    Text(
        text = String.format("%.1f", rating),
        color = Color(0xFFFFD54F),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(cornerRadius))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
