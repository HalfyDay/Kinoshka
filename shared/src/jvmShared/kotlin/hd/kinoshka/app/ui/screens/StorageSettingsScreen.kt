package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Отдельная страница «Память и хранилище» (из «Настроек»): замер — фоном при входе,
 * пересчёт после каждой очистки. Сам замер и очистка живут на платформе
 * (StorageUsageManager в app-модуле, только Android), экран общий.
 */
@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    rows: List<StorageUsageRow>?,
    limits: StorageLimits?,
    clearingKeys: Set<String>,
    onClearSelected: (List<String>) -> Unit,
    onImageLimitSelected: (Int) -> Unit,
    onRetentionSelected: (Int) -> Unit,
    onAutoCleanupChanged: (Boolean) -> Unit
) {
    // Шапка-пилюля парит поверх контента без подложки (с тенью и градиентом),
    // как на остальных страницах настроек: список уходит под неё.
    PinnedHeaderPage(
        title = "Память и хранилище",
        subtitle = "Размер кэша, очистка, загрузки",
        onBack = onBack
    ) { topPad ->
        if (rows == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topPad),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(
                        text = "Замеряем хранилище…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
            ) {
                item {
                    StorageSectionCard(
                        rows = rows,
                        limits = limits,
                        clearingKeys = clearingKeys,
                        onClearSelected = onClearSelected,
                        onImageLimitSelected = onImageLimitSelected,
                        onRetentionSelected = onRetentionSelected,
                        onAutoCleanupChanged = onAutoCleanupChanged
                    )
                }
            }
        }
    }
}
