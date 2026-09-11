package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Варианты диалога «Качество загрузки», по убыванию. Дефолт — первый (максимум). */
val DOWNLOAD_QUALITY_OPTIONS: List<String> = listOf("1080p", "720p", "480p", "360p")

fun downloadQualityLabel(quality: String?): String = quality ?: "1080p"

/**
 * Окошко выбора качества перед постановкой в очередь загрузок.
 * [onConfirm] получает потолок качества; резолв возьмёт лучший
 * доступный ранг не выше выбранного (см. pickCappedQualityUrl).
 */
@Composable
fun DownloadQualityDialog(
    onDismiss: () -> Unit,
    onConfirm: (quality: String?) -> Unit
) {
    var selected by remember { mutableStateOf(DOWNLOAD_QUALITY_OPTIONS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Качество загрузки") },
        text = {
            Column {
                Text(
                    text = "Будет взято лучшее доступное качество не выше выбранного.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DOWNLOAD_QUALITY_OPTIONS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = option }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == option,
                            onClick = { selected = option }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = downloadQualityLabel(option),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected == option) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Скачать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
