package hd.kinoshka.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.ui.tv.tvFocusable

/**
 * Загрузки на ПК: офлайн-движок (DownloadManager) есть только в Android-приложении,
 * на десктопе экран объясняет это и остаётся точкой входа из бокового меню.
 */
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.size(38.dp).tvFocusable(onClick = onBack, shape = CircleShape, hoverToFocus = true)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = cs.onBackground, modifier = Modifier.size(22.dp))
                }
            }
            Text("Загрузки", color = cs.onBackground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = cs.surfaceContainerHigh.copy(alpha = 0.6f)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp).padding(0.dp)) {
                    Icon(Icons.Filled.Download, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(36.dp))
                }
            }
            Text(
                "Пока пусто",
                color = cs.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Офлайн-загрузки серий доступны в мобильной версии приложения. " +
                    "На ПК смотрите тайтлы напрямую — стриминг работает без загрузки.",
                color = cs.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.6f),
            )
        }
    }
}
