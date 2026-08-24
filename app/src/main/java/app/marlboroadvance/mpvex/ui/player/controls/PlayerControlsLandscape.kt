package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.PlayerButton
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.VideoAspect
import app.marlboroadvance.mpvex.ui.player.controls.components.AnimeEpisodeDropdown
import app.marlboroadvance.mpvex.ui.player.controls.components.AnimeQualityDropdown
import app.marlboroadvance.mpvex.ui.player.controls.components.AnimeSeasonDropdown
import app.marlboroadvance.mpvex.ui.player.controls.components.AnimeShaderControl
import app.marlboroadvance.mpvex.ui.player.controls.components.AnimeTranslationDropdown
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.spacing
import dev.vivvvek.seeker.Segment

@Composable
fun TopLeftPlayerControlsLandscape(
  mediaTitle: String?,
  hideBackground: Boolean,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  viewModel: PlayerViewModel,
) {
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val clickEvent = LocalPlayerButtonsClickEvent.current

  val animeEpisodes by viewModel.animeEpisodes.collectAsState()
  val animeTranslations by viewModel.animeTranslations.collectAsState()
  val animeQualities by viewModel.animeQualities.collectAsState()
  val currentEpisode by viewModel.currentAnimeEpisodeNumber.collectAsState()
  val currentTranslationId by viewModel.currentAnimeTranslationId.collectAsState()
  val currentQualityId by viewModel.currentAnimeQualityId.collectAsState()
  val animeSeasons by viewModel.animeSeasons.collectAsState()
  val currentAnimeSeason by viewModel.currentAnimeSeason.collectAsState()
  // Multi-season series show only the active season's episodes in the dropdown.
  val visibleEpisodes = animeEpisodes.filter { it.season == null || it.season == currentAnimeSeason }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    ControlsButton(
      icon = Icons.AutoMirrored.Default.ArrowBack,
      onClick = onBackPress,
      color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(45.dp),
    )

    if (animeSeasons.size > 1) {
      AnimeSeasonDropdown(
        seasons = animeSeasons,
        currentSeason = currentAnimeSeason,
        hideBackground = hideBackground,
        onSeasonSelected = { viewModel.onAnimeSeasonSelected?.invoke(it) }
      )
    }

    if (visibleEpisodes.isNotEmpty()) {
      AnimeEpisodeDropdown(
        episodes = visibleEpisodes,
        currentEpisode = currentEpisode,
        hideBackground = hideBackground,
        viewModel = viewModel,
        onEpisodeSelected = { viewModel.onAnimeEpisodeSelected?.invoke(it) }
      )
    }

    if (animeTranslations.isNotEmpty()) {
      AnimeTranslationDropdown(
        translations = animeTranslations,
        currentTranslationId = currentTranslationId,
        hideBackground = hideBackground,
        viewModel = viewModel,
        onTranslationSelected = { viewModel.onAnimeTranslationSelected?.invoke(it) }
      )
    }

    if (animeEpisodes.isEmpty()) {
      val titleInteractionSource = remember { MutableInteractionSource() }

      Box(
        modifier =
          Modifier
            .height(45.dp)
            .clip(RoundedCornerShape(50))
            .clickable(
              enabled = playlistModeEnabled,
              onClick = {
                clickEvent()
                onOpenSheet(Sheets.Playlist)
              },
            ),
      ) {
        Surface(
          shape = RoundedCornerShape(50),
          color =
            if (hideBackground) {
              Color.Transparent
            } else {
              MaterialTheme.colorScheme.surfaceContainer.copy(
                alpha = 0.55f,
              )
            },
          contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            modifier =
              Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.small,
              ),
          ) {
            viewModel.getPlaylistInfo()?.let { playlistInfo ->
              Text(
                text = playlistInfo,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = Typography.bullet.toString(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Clip,
              )
            }
            
            val isLink = mediaTitle?.startsWith("http") == true
            if (!isLink) {
                Text(
                  text = mediaTitle ?: "",
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.bodyMedium,
                  fontFamily = FontFamily.Monospace,
                  color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f, fill = false),
                )
            }
          }
        }
      }
    }
  }
}

@Composable
fun TopRightPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
) {
    Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    AnimeShaderControl(hideBackground = hideBackground, viewModel = viewModel)
    
    buttons.forEach { button ->
      RenderPlayerButton(
        button = button,
        chapters = chapters,
        currentChapter = currentChapter,
        isPortrait = false,
        isSpeedNonOne = isSpeedNonOne,
        currentZoom = currentZoom,
        aspect = aspect,
        mediaTitle = mediaTitle,
        hideBackground = hideBackground,
        decoder = decoder,
        playbackSpeed = playbackSpeed,
        onBackPress = onBackPress,
        onOpenSheet = onOpenSheet,
        onOpenPanel = onOpenPanel,
        viewModel = viewModel,
        activity = activity,
        buttonSize = 45.dp,
      )
    }
  }
}

@Composable
fun BottomRightPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
) {
    val animeQualities by viewModel.animeQualities.collectAsState()
    val currentQualityId by viewModel.currentAnimeQualityId.collectAsState()
    val showQuality = animeQualities.isNotEmpty() || currentQualityId != null
    Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    if (showQuality) {
      AnimeQualityDropdown(
        qualities = animeQualities,
        currentQualityId = currentQualityId,
        hideBackground = hideBackground,
        viewModel = viewModel,
        onQualitySelected = { viewModel.onAnimeQualitySelected?.invoke(it) }
      )
    }
    buttons.forEach { button ->
      RenderPlayerButton(
        button = button,
        chapters = chapters,
        currentChapter = currentChapter,
        isPortrait = false,
        isSpeedNonOne = isSpeedNonOne,
        currentZoom = currentZoom,
        aspect = aspect,
        mediaTitle = mediaTitle,
        hideBackground = hideBackground,
        decoder = decoder,
        playbackSpeed = playbackSpeed,
        onBackPress = onBackPress,
        onOpenSheet = onOpenSheet,
        onOpenPanel = onOpenPanel,
        viewModel = viewModel,
        activity = activity,
        buttonSize = 45.dp,
      )
    }
  }
}

@Composable
fun BottomLeftPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
) {
    Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    buttons.forEach { button ->
      RenderPlayerButton(
        button = button,
        chapters = chapters,
        currentChapter = currentChapter,
        isPortrait = false,
        isSpeedNonOne = isSpeedNonOne,
        currentZoom = currentZoom,
        aspect = aspect,
        mediaTitle = mediaTitle,
        hideBackground = hideBackground,
        decoder = decoder,
        playbackSpeed = playbackSpeed,
        onBackPress = onBackPress,
        onOpenSheet = onOpenSheet,
        onOpenPanel = onOpenPanel,
        viewModel = viewModel,
        activity = activity,
        buttonSize = 45.dp,
      )
    }
  }
}


