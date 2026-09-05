package app.marlboroadvance.mpvex.ui.preferences

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.R
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import androidx.preference.PreferenceManager
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals

@Serializable
object PreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_preferences),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          // Search bar - full width, prominent placement
          item {
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { backstack.add(SettingsSearchScreen) },
              shape = RoundedCornerShape(28.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              tonalElevation = 2.dp,
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Outlined.Search,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = stringResource(R.string.settings_search_hint),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            }
          }

          // Player Layout (тема плеера следует теме приложения, отдельной настройки нет)
          item {
            PreferenceSectionHeader(title = "Плеер")
          }

          item {
            PreferenceCard {
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_layout_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_layout_summary),
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.AutoMirrored.Outlined.ViewQuilt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { backstack.add(PlayerControlsPreferencesScreen) },
              )
            }
          }

          // Playback & Controls Section
          item {
            PreferenceSectionHeader(title = "Воспроизведение и управление")
          }

          item {
            PreferenceCard {
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_player)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_player_summary),
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { backstack.add(PlayerPreferencesScreen) },
              )

              PreferenceDivider()

              Preference(

                title = { Text(text = stringResource(id = R.string.pref_gesture)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_gesture_summary),
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.Gesture,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { backstack.add(GesturePreferencesScreen) },
              )
            }
          }

          // Media Settings Section
          item {
            PreferenceSectionHeader(title = "Медиа")
          }

          item {
            PreferenceCard {
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_decoder)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_decoder_summary),
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { backstack.add(DecoderPreferencesScreen) },
              )

              PreferenceDivider()

              Preference(

                title = { Text(text = stringResource(id = R.string.pref_subtitles)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_subtitles_summary),
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.Subtitles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { backstack.add(SubtitlesPreferencesScreen) },
              )

              PreferenceDivider()

              Preference(

                title = { Text(text = stringResource(id = R.string.pref_audio)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_audio_summary),
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.Audiotrack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { backstack.add(AudioPreferencesScreen) },
              )
            }
          }

          // Reset Section: возврат всех настроек плеера к значениям по умолчанию
          item {
            PreferenceSectionHeader(title = "Сброс")
          }

          item {
            PreferenceCard {
              Preference(
                title = { Text(text = "Сбросить настройки плеера") },
                summary = {
                  Text(
                    text = "Вернуть всё к значениям по умолчанию, как при установке приложения",
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { showResetConfirm = true },
              )
            }
          }
        }
      }
    }

    if (showResetConfirm) {
      AlertDialog(
        onDismissRequest = { showResetConfirm = false },
        title = { Text(text = "Сбросить настройки плеера?") },
        text = {
          Text(text = "Все настройки плеера вернутся к значениям по умолчанию. Это действие нельзя отменить.")
        },
        confirmButton = {
          TextButton(
            onClick = {
              showResetConfirm = false
              // В файле default SharedPreferences лежат только настройки mpvEx (остальной
              // код приложения пишет в именованные файлы), поэтому полная очистка
              // возвращает к дефолтам именно плеера. Слушатели keyFlow получают каждое
              // удаление — открытые экраны с живыми настройками перерисуются сами.
              runCatching {
                PreferenceManager
                  .getDefaultSharedPreferences(context)
                  .edit()
                  .clear()
                  .apply()
              }
                .onSuccess { Toast.makeText(context, "Настройки сброшены", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
            }
          ) { Text("Сбросить") }
        },
        dismissButton = {
          TextButton(onClick = { showResetConfirm = false }) { Text("Отмена") }
        },
      )
    }
  }
}
