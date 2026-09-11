package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import app.marlboroadvance.mpvex.ui.theme.controlColor
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import hd.kinoshka.app.data.cast.CastPlayback
import kotlinx.coroutines.delay

/**
 * Кнопка трансляции на ТВ: открывает системный диалог выбора Cast-устройства.
 * После выбора плеер закрывается и открывается страница пульта (CastRemoteActivity).
 */
@Composable
fun CastButton(
    hideBackground: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .build()
    }
    // Подсветка активности: фон как у включённого Anime4K.
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Дешёвый опрос без колбэков: кнопка живёт только пока открыт плеер.
        while (true) {
            active = CastPlayback.isCasting
            delay(1000)
        }
    }
    Surface(
        onClick = {
            // Прогрев Cast-контекста до открытия диалога, иначе список пуст.
            runCatching { CastContext.getSharedInstance(context.applicationContext) }
            val activity = context as? FragmentActivity ?: return@Surface
            MediaRouteChooserDialogFragment().apply {
                routeSelector = selector
            }.show(activity.supportFragmentManager, "cast_chooser")
        },
        shape = CircleShape,
        color = if (hideBackground) Color.Transparent
        else if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary
        else if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(45.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = "Трансляция на ТВ",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
