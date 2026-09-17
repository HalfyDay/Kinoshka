package app.marlboroadvance.mpvex.ui.preferences

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }

    MpvExKinoPage(
      title = "Настройки плеера",
      subtitle = "mpvEx · скорость, жесты, субтитры, декодер",
      onBack = backstack::removeLastOrNull
    ) { topPad ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
        ) {
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
