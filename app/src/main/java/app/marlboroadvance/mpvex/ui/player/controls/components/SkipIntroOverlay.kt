package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import hd.kinoshka.app.data.model.EpisodeSkips
import hd.kinoshka.app.data.source.AnimeStreamResolver

/**
 * Загрузка таймингов серии (кэш → догрузка релиза AniLiberty). Вынесена из пилюли,
 * чтобы те же данные рисовали метки на линии прогресса без двойной сети.
 */
@Composable
fun rememberEpisodeSkips(
    shikimoriId: Int,
    animeTitle: String,
    episode: Int
): EpisodeSkips? {
    if (shikimoriId <= 0) return null
    var skips by remember(shikimoriId, episode) { mutableStateOf<EpisodeSkips?>(null) }
    LaunchedEffect(shikimoriId, episode, animeTitle) {
        skips = AnimeStreamResolver.prefetchEpisodeSkips(shikimoriId, animeTitle, episode)
    }
    return skips
}

/** Первые N секунд отрезка показывают пилюлю (дальше пользователь уже смотрит осознанно). */
const val SKIP_PILL_VISIBLE_SEC = 10

/**
 * Цель пропуска на позиции [posSec]: (подпись, секунда конца) или null вне первых
 * 10 секунд отрезка. Общая для локальной пилюли и пульта каста.
 */
fun skipTargetAt(skips: EpisodeSkips?, posSec: Int): Pair<String, Int>? {
    if (skips == null) return null
    val opening = skips.opening?.takeIf { posSec >= it.startSec && posSec < it.startSec + SKIP_PILL_VISIBLE_SEC }
    val ending = skips.ending?.takeIf { posSec >= it.startSec && posSec < it.startSec + SKIP_PILL_VISIBLE_SEC }
    return when {
        opening != null -> "Пропустить опенинг" to opening.endSec
        ending != null -> "Пропустить эндинг" to ending.endSec
        else -> null
    }
}

/**
 * «Пропустить опенинг/эндинг» — пилюля над таймлайном в стиле NextEpisodeOverlay.
 * Тайминги берёт из AniLiberty для ЛЮБОГО источника: плеер знает shikimori id и серию.
 * [timingsOk] — гвард хронометража (поток совпал с источником таймингов), считает вызывающий.
 */
@Composable
fun SkipIntroOverlay(
    viewModel: PlayerViewModel,
    skips: EpisodeSkips?,
    timingsOk: Boolean,
    position: Int,
    modifier: Modifier = Modifier
) {
    if (!timingsOk || skips == null) return
    val (label, seekTo) = skipTargetAt(skips, position) ?: return
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { viewModel.seekTo(seekTo) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
