package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.theme.controlColor
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.FlatTranslation
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AnimeEpisodeDropdown(
    episodes: List<AnimeEpisode>,
    currentEpisode: Int?,
    hideBackground: Boolean,
    onEpisodeSelected: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(50),
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .height(45.dp)
            .clickable { showDialog = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.FormatListNumbered, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = if (currentEpisode != null) "Серия $currentEpisode" else "Выбор серии",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Выберите серию",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(episodes) { ep ->
                            val isSelected = ep.number == currentEpisode
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onEpisodeSelected(ep.number)
                                    showDialog = false
                                }
                            ) {
                                Text(
                                    text = "Серия ${ep.number}${if (!ep.title.isNullOrBlank()) " - ${ep.title}" else ""}",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimeTranslationDropdown(
    translations: List<FlatTranslation>,
    currentTranslationId: String?,
    hideBackground: Boolean,
    onTranslationSelected: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val currentTr = translations.find { it.translationId == currentTranslationId }

    Surface(
        shape = RoundedCornerShape(50),
        color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .height(45.dp)
            .clickable { showDialog = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = currentTr?.title ?: "Озвучка/Субтитры",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Выберите озвучку / субтитры",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(translations) { tr ->
                            val isSelected = tr.translationId == currentTranslationId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onTranslationSelected(tr.translationId)
                                    showDialog = false
                                }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = tr.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = "${tr.source.displayName} • ${if (tr.type == "voice") "Озвучка" else "Субтитры"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimeShaderControl(
    hideBackground: Boolean
) {
    val decoderPreferences = koinInject<DecoderPreferences>()
    val anime4kManager = koinInject<Anime4KManager>()
    val scope = rememberCoroutineScope()
    
    val anime4kMode by decoderPreferences.anime4kMode.collectAsState()
    val anime4kQuality by decoderPreferences.anime4kQuality.collectAsState()
    
    var showDialog by remember { mutableStateOf(false) }
    
    val isOff = anime4kMode == "OFF"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Toggle Button
        Surface(
            shape = CircleShape,
            color = if (hideBackground) Color.Transparent else if (!isOff) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            contentColor = if (!isOff) MaterialTheme.colorScheme.onPrimary else (if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface),
            border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier
                .size(45.dp)
                .clickable {
                    val newMode = if (isOff) "RESTORE_SOFT" else "OFF"
                    decoderPreferences.anime4kMode.set(newMode)
                    applyShaders(newMode, anime4kQuality, anime4kManager, scope)
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (!isOff) Icons.Default.BlurOn else Icons.Outlined.BlurOn,
                    contentDescription = "Anime4K Shader",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // Mode Selection Button
        if (!isOff) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (hideBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                border = if (hideBackground) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .height(45.dp)
                    .clickable { showDialog = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = anime4kMode.replace("RESTORE_", "").replace("UPSCALE_", ""),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Anime4K Mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(Anime4KManager.Mode.entries) { mode ->
                            val isSelected = anime4kMode == mode.name
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    decoderPreferences.anime4kMode.set(mode.name)
                                    applyShaders(mode.name, anime4kQuality, anime4kManager, scope)
                                    showDialog = false
                                },
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    mode.name,
                                    modifier = Modifier.padding(12.dp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Quality", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Anime4KManager.Quality.entries.forEach { q ->
                            val isSelected = anime4kQuality == q.name
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    decoderPreferences.anime4kQuality.set(q.name)
                                    applyShaders(anime4kMode, q.name, anime4kManager, scope)
                                },
                                label = { Text(q.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun applyShaders(modeStr: String, qualityStr: String, manager: Anime4KManager, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        val mode = try { Anime4KManager.Mode.valueOf(modeStr) } catch(e: Exception) { Anime4KManager.Mode.OFF }
        val quality = try { Anime4KManager.Quality.valueOf(qualityStr) } catch(e: Exception) { Anime4KManager.Quality.BALANCED }
        val chain = manager.getShaderChain(mode, quality)
        MPVLib.setPropertyString("glsl-shaders", chain)
    }
}
