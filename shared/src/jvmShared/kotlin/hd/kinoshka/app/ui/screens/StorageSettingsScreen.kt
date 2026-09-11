package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
    // Шапка закреплена над списком: остаётся поверх содержания при прокрутке.
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsHeaderCard(
            title = "Память и хранилище",
            subtitle = "Размер кэша, очистка, загрузки",
            onBack = onBack,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        )
        if (rows == null) {
            Column(
                modifier = Modifier.fillMaxSize().weight(1f),
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp)
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
