package hd.kinoshka.app.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.Popup
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.graphics.SolidColor
import hd.kinoshka.app.ui.components.AnimePlaybackSelectionSheet
import hd.kinoshka.app.data.model.PlaybackSequenceOption

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.CircularProgressIndicator
import coil.compose.SubcomposeAsyncImage
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImageItem
import hd.kinoshka.app.data.model.FilmLinkItem
import hd.kinoshka.app.data.model.SeasonItem
import hd.kinoshka.app.ui.components.ExpressiveBlobLoadingIndicator
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DetailsScreen(
    filmId: Int,
    state: DetailsUiState,
    load: (Int) -> Unit,
    onWatch: (FilmDetails) -> Unit,
    onSaveUserProfile: (
        details: FilmDetails,
        status: UserFilmStatus?,
        userRating: Int?,
        note: String,
        watchedSeasons: Int?,
        watchedEpisodes: Int?,
        totalEpisodesInSeason: Int?,
        totalSeasons: Int?,
        totalEpisodes: Int?
    ) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenFilm: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenGenre: ((genreName: String, isAnime: Boolean) -> Unit)? = null,
    onOpenNativePlayer: ((
        streamUrl: String,
        headers: Map<String, String>,
        qualities: Map<String, String>,
        animeTitle: String,
        episodeNumber: Int,
        episodeTitle: String,
        shikimoriId: Int,
        sourceType: String,
        episodes: List<hd.kinoshka.app.data.model.AnimeEpisode>,
        translations: List<hd.kinoshka.app.data.model.FlatTranslation>,
        currentTranslationId: String
    ) -> Unit)? = null,
    playbackSequence: PlaybackSequenceOption = PlaybackSequenceOption.EPISODES_TRANSLATIONS_SOURCES
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewPosterUrl by remember(filmId) { mutableStateOf<String?>(null) }
    var previewPosterOffset by remember(filmId) { mutableStateOf<Offset?>(null) }
    var imageViewerStartIndex by remember(filmId) { mutableIntStateOf(-1) }
    var showProfileEditor by remember(filmId) { mutableStateOf(false) }
    var selectedCharacterId by remember(filmId) { mutableStateOf<Int?>(null) }
    var adGuardDnsActive by remember { mutableStateOf(isAdGuardDnsActive(context)) }
    var isInteractive by remember { mutableStateOf(true) }
    var activePlaybackSelection by remember(filmId) { mutableStateOf(false) }

    LaunchedEffect(filmId) {
        load(filmId)
    }

    BackHandler {
        when {
            activePlaybackSelection -> activePlaybackSelection = false
            selectedCharacterId != null -> selectedCharacterId = null
            previewPosterUrl != null -> {
                previewPosterUrl = null
                previewPosterOffset = null
            }
            imageViewerStartIndex >= 0 -> imageViewerStartIndex = -1
            else -> {
                isInteractive = false
                onBack()
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    adGuardDnsActive = isAdGuardDnsActive(context)
                    isInteractive = true
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    isInteractive = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showLoadingIndicator by remember(filmId) { mutableStateOf(false) }
    LaunchedEffect(state.loading, filmId) {
        if (state.loading) {
            showLoadingIndicator = false
            delay(380)
            if (state.loading) {
                showLoadingIndicator = true
            }
        } else {
            showLoadingIndicator = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            state.loading -> {
                if (showLoadingIndicator) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            state.error != null -> {
                ElevatedCard(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Не удалось загрузить карточку",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { load(filmId) }) {
                            Text("Повторить")
                        }
                    }
                }
            }

            state.item != null -> {
                val item = state.item
                val isAnime = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET || item.type == "ANIME" || item.genres.any { it.genre?.lowercase() == "аниме" }
                val scrollState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isAnime) {
                    if (activePlaybackSelection) {
                        val shikimoriId = if (item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET) {
                            item.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET
                        } else {
                            0
                        }
                        AnimePlaybackSelectionScreen(
                            shikimoriId = shikimoriId,
                            animeTitle = item.nameRu ?: item.nameOriginal ?: "Аниме",
                            watchedEpisodes = state.userProfile?.watchedEpisodes ?: 0,
                            playbackSequence = playbackSequence,
                            onDismissRequest = { activePlaybackSelection = false },
                            onStreamSelected = { stream, epNum, epTitle, source, translationTitle, episodes, translations, trId ->
                                var normalizedUrl = stream.url
                                if (normalizedUrl.startsWith("//")) {
                                    normalizedUrl = "https:$normalizedUrl"
                                }
                                if (normalizedUrl.startsWith("http", ignoreCase = true)) {
                                    onOpenNativePlayer?.invoke(
                                        normalizedUrl,
                                        stream.headers,
                                        stream.qualities,
                                        item.nameRu ?: item.nameOriginal ?: "Аниме",
                                        epNum,
                                        epTitle,
                                        shikimoriId,
                                        source.name,
                                        episodes,
                                        translations,
                                        trId
                                    )
                                } else {
                                    throw IllegalArgumentException("Некорректная ссылка на видеопоток: $normalizedUrl")
                                }
                            },
                        )
                    } else {
                        AnimeDetailsLayout(
                            scrollState = scrollState,
                            state = state,
                            isInteractive = isInteractive,
                            onWatch = { filmDetails ->
                                onWatch(filmDetails)
                                activePlaybackSelection = true
                            },
                            onOpenUrl = onOpenUrl,
                            onOpenEditor = { showProfileEditor = true },
                            onOpenFilm = { filmId ->
                                isInteractive = false
                                onOpenFilm(filmId)
                            },
                            onPreviewImage = { index ->
                                imageViewerStartIndex = index
                            },
                            onPosterClick = { offset ->
                                previewPosterOffset = offset
                                previewPosterUrl = item.posterUrl ?: item.coverUrl ?: item.posterUrlPreview
                            },
                            onOpenCharacter = { charId -> selectedCharacterId = charId },
                            onOpenGenre = onOpenGenre
                        )
                    }
                } else {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            HeroHeader(
                                item = item,
                                onPosterClick = { offset ->
                                    previewPosterOffset = offset
                                    previewPosterUrl = item.posterUrl ?: item.posterUrlPreview
                                }
                            )
                        }
                        item {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                ActionPanel(
                                    enabled = isInteractive,
                                    profile = state.userProfile,
                                    seasons = state.seasons,
                                    onWatch = {
                                        isInteractive = false
                                        onWatch(item)
                                        onOpenUrl(item.toWatchUrl())
                                    },
                                    onOpenEditor = { showProfileEditor = true },
                                    showDisableAdsButton = !adGuardDnsActive,
                                    onDisableAds = {
                                        openPrivateDnsWithAdGuard(context)
                                        adGuardDnsActive = isAdGuardDnsActive(context)
                                    }
                                )
                            }
                        }

                        // Standalone Expandable Description
                        val movieDesc = item.description ?: item.shortDescription
                        if (!movieDesc.isNullOrBlank()) {
                            item {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    MovieExpandableDescription(description = movieDesc)
                                }
                            }
                        }

                        // Screenshots / Frames (Immediately after description)
                        if (state.images.isNotEmpty()) {
                            item {
                                ImagesCard(
                                    images = state.images,
                                    onPreview = { index ->
                                        imageViewerStartIndex = index
                                    }
                                )
                            }
                        }

                        // Genres horizontal row
                        if (item.genres.isNotEmpty()) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(item.genres) { g ->
                                        g.genre?.let { genreName ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                modifier = Modifier
                                                    .height(36.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { onOpenGenre?.invoke(genreName, false) }
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = genreName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // TV Series Seasons
                        if (state.item.type == "TV_SERIES" && state.seasons.isNotEmpty()) {
                            item {
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    SeasonsCard(state.seasons)
                                }
                            }
                        }

                        // Related films
                        if (state.relations.isNotEmpty()) {
                            item {
                                HorizontalFilmsCard(
                                    title = "Похожее",
                                    items = state.relations,
                                    onOpenFilm = { id ->
                                        isInteractive = false
                                        onOpenFilm(id)
                                    }
                                )
                            }
                        }

                        // Full details card at the very bottom
                        item {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                MovieFullDetailsCard(item = item)
                            }
                        }
                    }
                }

                if (!isAnime || !activePlaybackSelection) {
                    DetailsTopBar(
                        item = item,
                        isAnime = isAnime,
                        scrollState = scrollState,
                        onBack = onBack
                    )
                }
            }
        }
    }

        previewPosterUrl?.let { imageUrl ->
            PosterPreviewDialog(
                imageUrl = imageUrl,
                title = state.item?.nameRu ?: state.item?.nameOriginal ?: "Обложка",
                clickOffset = previewPosterOffset,
                onDismiss = {
                    previewPosterUrl = null
                    previewPosterOffset = null
                }
            )
        }
        if (state.images.isNotEmpty() && imageViewerStartIndex >= 0) {
            ImagesViewerDialog(
                images = state.images,
                startIndex = imageViewerStartIndex,
                onDismiss = { imageViewerStartIndex = -1 }
            )
        }

        if (showProfileEditor && state.item != null) {
            UserProfileEditorSheet(
                item = state.item,
                animeDetails = state.animeDetails,
                seasons = state.seasons,
                profile = state.userProfile,
                saving = state.savingProfile,
                onDismiss = { showProfileEditor = false },
                onSave = { status, rating, note, seasons, episodes ->
                    val totalSeasons = state.seasons.size.takeIf { it > 0 }
                        ?: if (state.animeDetails != null) 1 else null
                    val totalEpisodes = state.seasons.sumOf { it.episodes.size }.takeIf { it > 0 }
                        ?: state.animeDetails?.episodes?.takeIf { it > 0 }

                    onSaveUserProfile(
                        state.item,
                        status,
                        rating,
                        note,
                        seasons,
                        episodes,
                        seasons
                            ?.takeIf { it > 0 }
                            ?.let { seasonNumber ->
                                state.seasons.firstOrNull { it.number == seasonNumber }?.episodes?.size
                            },
                        totalSeasons,
                        totalEpisodes
                    )
                    showProfileEditor = false
                }
            )
        }

        selectedCharacterId?.let { charId ->
            CharacterDetailsSheet(
                characterId = charId,
                onOpenFilm = { targetFilmId ->
                    isInteractive = false
                    onOpenFilm(targetFilmId)
                },
                onDismiss = { selectedCharacterId = null }
            )
        }

        if (!isInteractive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
            )
        }
    }
}

@Composable
private fun HeroHeader(
    item: FilmDetails,
    onPosterClick: (Offset) -> Unit
) {
    val context = LocalContext.current
    val cover = item.coverUrl ?: item.posterUrl
    var posterAspectRatio by remember(item.kinopoiskId) { mutableStateOf(2f / 3f) }
    var isLoaded by remember(item.kinopoiskId) { mutableStateOf(false) }
    var posterBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(item.kinopoiskId) {
        isLoaded = true
    }

    val posterScale by animateFloatAsState(
        targetValue = if (isLoaded) 1f else 0.76f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "heroPosterScale"
    )
    val posterAlpha by animateFloatAsState(
        targetValue = if (isLoaded) 1f else 0f,
        animationSpec = tween(300),
        label = "heroPosterAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
    ) {
        KinoshkaAsyncImage(
            model = cover,
            contentDescription = item.nameRu,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            useOriginalSize = true,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.62f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .width(114.dp)
                    .aspectRatio(posterAspectRatio.coerceIn(0.52f, 0.95f))
                    .graphicsLayer {
                        scaleX = posterScale
                        scaleY = posterScale
                        alpha = posterAlpha
                    }
                    .onGloballyPositioned { coords ->
                        posterBounds = coords.boundsInWindow()
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        onPosterClick(posterBounds?.center ?: Offset.Zero)
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.56f)
            ) {
                KinoshkaAsyncImage(
                    model = item.posterUrl ?: item.posterUrlPreview,
                    contentDescription = item.nameRu,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    useOriginalSize = true,
                    onSuccess = { success ->
                        val width = success.result.drawable.intrinsicWidth
                        val height = success.result.drawable.intrinsicHeight
                        if (width > 0 && height > 0) {
                            posterAspectRatio = width.toFloat() / height.toFloat()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.nameRu ?: item.nameOriginal ?: "Без названия",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    item.nameOriginal?.takeIf { it.isNotBlank() && it != item.nameRu }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Rating Row (KP & IMDb with star)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item.ratingKinopoisk?.let { r ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "%.1f".format(Locale.US, r),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        item.ratingImdb?.let { imdb ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "IMDb %.1f".format(Locale.US, imdb),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // 4 Parameters Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Тип", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.type?.toLocalizedType() ?: "Фильм", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Column {
                            Text("Дата", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.year?.let { "$it г." } ?: "—", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Column {
                            Text("Время", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.filmLength?.let { "$it мин" } ?: "—", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Column {
                            Text("Возраст", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.ratingAgeLimits?.replace("age", "")?.let { "$it+" } ?: "—", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterPreviewDialog(
    imageUrl: String,
    title: String,
    clickOffset: Offset? = null,
    onDismiss: () -> Unit
) {
    HideStatusBarEffect()
    var isVisible by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val dynamicOrigin = remember(clickOffset, screenWidthPx, screenHeightPx) {
        if (clickOffset != null && screenWidthPx > 0f && screenHeightPx > 0f) {
            TransformOrigin(
                pivotFractionX = (clickOffset.x / screenWidthPx).coerceIn(0.05f, 0.95f),
                pivotFractionY = (clickOffset.y / screenHeightPx).coerceIn(0.05f, 0.95f)
            )
        } else {
            TransformOrigin(0.18f, 0.78f)
        }
    }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val scope = rememberCoroutineScope()
    val handleDismiss = {
        if (isVisible) {
            isVisible = false
            scope.launch {
                delay(280)
                onDismiss()
            }
        }
    }

    BackHandler(enabled = true, onBack = { handleDismiss() })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = { handleDismiss() })
    ) {
        // 1. Full-screen background blur & dark overlay: fades in/out on full screen separately
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(260)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                KinoshkaAsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    useOriginalSize = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(28.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                )
            }
        }

        // 2. Main Cover Image: physically expands and moves directly from the touch position!
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(200)) + scaleIn(
                initialScale = 0.32f,
                transformOrigin = dynamicOrigin,
                animationSpec = tween(340, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(tween(220)) + scaleOut(
                targetScale = 0.32f,
                transformOrigin = dynamicOrigin,
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                KinoshkaAsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    useOriginalSize = true,
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .clip(RoundedCornerShape(24.dp))
                )
            }
        }
    }
}

@Composable
private fun ActionPanel(
    enabled: Boolean,
    profile: UserFilmProfile?,
    seasons: List<hd.kinoshka.app.data.model.SeasonItem> = emptyList(),
    onWatch: () -> Unit,
    onOpenEditor: () -> Unit,
    showDisableAdsButton: Boolean,
    onDisableAds: () -> Unit
) {
    val status = profile?.status
    val watchedSeasons = profile?.watchedSeasons
    val watchedEp = profile?.watchedEpisodes
    val totalEp = profile?.totalEpisodes
    val progressText = remember(watchedSeasons, watchedEp, totalEp, seasons) {
        if (watchedEp == null || watchedEp <= 0) {
            if (watchedSeasons != null && watchedSeasons > 0) " с.$watchedSeasons" else ""
        } else {
            var cumulativeWatched = 0
            var cumulativeTotal = 0
            val currentSeasonNum = watchedSeasons ?: 1

            if (seasons.isNotEmpty()) {
                val sortedSeasons = seasons.filter { it.number > 0 }.sortedBy { it.number }
                for (season in sortedSeasons) {
                    val epCount = season.episodes.size
                    if (season.number < currentSeasonNum) {
                        cumulativeWatched += epCount
                        cumulativeTotal += epCount
                    } else if (season.number == currentSeasonNum) {
                        cumulativeWatched += watchedEp.coerceAtMost(epCount)
                        cumulativeTotal += epCount
                    } else {
                        cumulativeTotal += epCount
                    }
                }
            }

            if (cumulativeWatched > 0) {
                val totalStr = if (cumulativeTotal > 0) "/$cumulativeTotal" else (totalEp?.let { "/$it" } ?: "")
                " $cumulativeWatched$totalStr"
            } else {
                val totalStr = totalEp?.let { "/$it" } ?: ""
                " $watchedEp$totalStr"
            }
        }
    }

    val statusText = when (status) {
        UserFilmStatus.COMPLETED -> "Просмотрено"
        UserFilmStatus.WATCHING -> "Смотрю$progressText"
        UserFilmStatus.PLANNED -> "В планах"
        UserFilmStatus.REWATCHING -> "Пересматриваю$progressText"
        UserFilmStatus.ON_HOLD -> "Отложено$progressText"
        UserFilmStatus.DROPPED -> "Брошено$progressText"
        null -> "Добавить в список"
    }
    val statusIcon = when (status) {
        UserFilmStatus.COMPLETED -> Icons.Default.Check
        UserFilmStatus.WATCHING -> null
        UserFilmStatus.PLANNED -> Icons.Default.Star
        UserFilmStatus.REWATCHING -> Icons.Default.Refresh
        UserFilmStatus.ON_HOLD -> Icons.Default.KeyboardArrowDown
        UserFilmStatus.DROPPED -> Icons.Default.Close
        null -> Icons.Default.Add
    }

    val infiniteTransition = rememberInfiniteTransition(label = "watch_glow")
    val glowProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val animatedWatchColor = lerp(primaryColor, tertiaryColor, glowProgress)
    val leftGradientColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Add to List button with smooth right-gradient transition
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, onClick = onOpenEditor),
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    leftGradientColor,
                                    animatedWatchColor.copy(alpha = 0.45f)
                                )
                            )
                        )
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (status == UserFilmStatus.WATCHING) {
                            RoundedPlayIcon(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onSurface)
                        } else if (statusIcon != null) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Animated Watch Button
            Surface(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, onClick = onWatch),
                shape = RoundedCornerShape(14.dp),
                color = animatedWatchColor
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoundedPlayIcon(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Text(
                        text = "Смотреть",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        if (showDisableAdsButton) {
            OutlinedButton(
                onClick = onDisableAds,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Отключить рекламу", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RoundedPlayIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.25f, h * 0.18f)
            quadraticTo(w * 0.25f, h * 0.10f, w * 0.35f, h * 0.15f)
            lineTo(w * 0.82f, h * 0.45f)
            quadraticTo(w * 0.90f, h * 0.50f, w * 0.82f, h * 0.55f)
            lineTo(w * 0.35f, h * 0.85f)
            quadraticTo(w * 0.25f, h * 0.90f, w * 0.25f, h * 0.82f)
            close()
        }
        drawPath(path = path, color = color)
    }
}



@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun UserProfileEditorSheet(
    item: FilmDetails,
    animeDetails: hd.kinoshka.app.data.model.ShikimoriAnimeDetails?,
    seasons: List<SeasonItem>,
    profile: UserFilmProfile?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        status: UserFilmStatus?,
        userRating: Int?,
        note: String,
        watchedSeasons: Int?,
        watchedEpisodes: Int?
    ) -> Unit
) {
    var status by remember(item.kinopoiskId, profile?.updatedAt) { mutableStateOf(profile?.status) }
    var ratingValue by remember(item.kinopoiskId, profile?.updatedAt) {
        mutableIntStateOf(profile?.userRating ?: 0)
    }
    var noteInput by remember(item.kinopoiskId, profile?.updatedAt) {
        mutableStateOf(profile?.note.orEmpty())
    }
    var seasonsCount by remember(item.kinopoiskId, profile?.updatedAt) {
        mutableIntStateOf(profile?.watchedSeasons ?: 0)
    }
    var episodesCount by remember(item.kinopoiskId, profile?.updatedAt) {
        mutableIntStateOf(profile?.watchedEpisodes ?: 0)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        KeepBottomSheetNavigationBarFromActivity()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header with Save and Delete icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Прогресс просмотра",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = item.nameRu ?: item.nameOriginal ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (status != null) {
                        IconButton(
                            onClick = { 
                                status = null 
                                seasonsCount = 0
                                episodesCount = 0
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Очистить статус",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !saving) {
                                onSave(
                                    status,
                                    ratingValue.takeIf { it > 0 },
                                    noteInput,
                                    if (item.type == "TV_SERIES") seasonsCount else null,
                                    if (item.type == "TV_SERIES") episodesCount else null
                                )
                            },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Сохранить",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Season and Episodes Counter Section with Max Boundaries & Season Reset
            val isAnimeItem = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET || item.type == "ANIME" || item.genres.any { it.genre?.lowercase() == "аниме" }
            val maxAnimeEpisodes = maxOf(
                animeDetails?.episodes ?: 0,
                profile?.totalEpisodes ?: 0,
                profile?.watchedEpisodes ?: 0
            ).takeIf { it > 0 } ?: Int.MAX_VALUE
            val maxSeasons = seasons.size.takeIf { it > 0 } ?: Int.MAX_VALUE
            val currentSeasonObj = seasons.firstOrNull { it.number == seasonsCount } ?: seasons.firstOrNull()
            val maxEpisodesInSeason = currentSeasonObj?.episodes?.size?.takeIf { it > 0 } ?: Int.MAX_VALUE

            // Status Connected Segmented Row
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                LazyRow(
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(UserFilmStatus.entries) { option ->
                        val isSelected = status == option
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    status = option
                                    if (option == UserFilmStatus.COMPLETED) {
                                        if (isAnimeItem) {
                                            // seasonsCount = 1 (do not change repeats automatically)
                                            episodesCount = maxAnimeEpisodes.takeIf { it != Int.MAX_VALUE } ?: 1
                                        } else if (item.type == "TV_SERIES" && seasons.isNotEmpty()) {
                                            seasonsCount = seasons.size
                                            episodesCount = seasons.last().episodes.size
                                        } else {
                                            // seasonsCount = 1
                                            episodesCount = 1
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        ) {
                            Text(
                                text = option.toUiLabel(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isAnimeItem) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactCounterField(
                        label = "Серии",
                        value = episodesCount,
                        onValueChange = { episodesCount = it.coerceIn(0, maxAnimeEpisodes) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactCounterField(
                        label = "Повторы",
                        value = seasonsCount,
                        onValueChange = { seasonsCount = it.coerceAtLeast(0) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (item.type == "TV_SERIES") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactCounterField(
                        label = "Сезоны",
                        value = seasonsCount,
                        onValueChange = { targetSeason ->
                            val newSeason = targetSeason.coerceIn(0, maxSeasons)
                            if (newSeason != seasonsCount) {
                                seasonsCount = newSeason
                                episodesCount = 1
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CompactCounterField(
                        label = "Серии",
                        value = episodesCount,
                        onValueChange = { episodesCount = it.coerceIn(0, maxEpisodesInSeason) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Rating Slider Section (Available only when status is COMPLETED)
            val isCompleted = status == UserFilmStatus.COMPLETED
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "Моя оценка",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (!isCompleted) "Только для «Просмотрено»" else if (ratingValue > 0) "$ratingValue ★" else "Без оценки",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isCompleted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                Slider(
                    value = ratingValue.toFloat(),
                    onValueChange = { ratingValue = it.roundToInt() },
                    valueRange = 0f..10f,
                    steps = 9,
                    enabled = isCompleted
                )
            }

            // Note TextField
            TextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Комментарий", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun KeepBottomSheetNavigationBarFromActivity() {
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val activityWindow = view.context.findActivity()?.window
        if (dialogWindow == null || activityWindow == null) {
            onDispose { }
        } else {
            val oldNavColor = dialogWindow.navigationBarColor
            val oldLightNav =
                WindowCompat.getInsetsController(dialogWindow, view).isAppearanceLightNavigationBars
            val oldContrastEnforced =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced
                } else {
                    false
                }

            val activityController =
                WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
            val dialogController = WindowCompat.getInsetsController(dialogWindow, view)
            dialogWindow.navigationBarColor = activityWindow.navigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialogWindow.isNavigationBarContrastEnforced =
                    activityWindow.isNavigationBarContrastEnforced
            }
            dialogController.isAppearanceLightNavigationBars =
                activityController.isAppearanceLightNavigationBars

            onDispose {
                dialogWindow.navigationBarColor = oldNavColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced = oldContrastEnforced
                }
                dialogController.isAppearanceLightNavigationBars = oldLightNav
            }
        }
    }
}

@Composable
private fun CompactCounterField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showEditDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier
                .weight(1f)
                .clickable { showEditDialog = true }
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange((value - 1).coerceAtLeast(0)) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("−", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange(value + 1) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("+", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        val tempValue = remember { mutableStateOf(value.toString()) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = tempValue.value,
                    onValueChange = { tempValue.value = it.filter { c -> c.isDigit() }.take(5) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    tempValue.value.toIntOrNull()?.let { onValueChange(it) }
                    showEditDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun MovieExpandableDescription(
    description: String
) {
    var expanded by remember { mutableStateOf(false) }
    val bgColor = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis
        )
        if (!expanded) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                bgColor.copy(alpha = 0.7f),
                                bgColor
                            ),
                            startY = 30f
                        )
                    )
            )
        }
    }
}

@Composable
private fun MovieFullDetailsCard(item: FilmDetails) {
    val detailsList = remember(item) {
        buildList {
            item.slogan?.takeIf { it.isNotBlank() }?.let { add("Слоган" to "\"$it\"") }
            item.countries.mapNotNull { it.country }.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { add("Страны" to it) }
            item.ratingKinopoisk?.let { r -> add("Рейтинг Кинопоиск" to "%.1f ★ (%d голосов)".format(Locale.US, r, item.ratingKinopoiskVoteCount ?: 0)) }
            item.ratingImdb?.let { r -> add("Рейтинг IMDb" to "%.1f (%d голосов)".format(Locale.US, r, item.ratingImdbVoteCount ?: 0)) }
            item.ratingFilmCritics?.let { r -> add("Рейтинг критиков" to "%.1f (%d)".format(Locale.US, r, item.ratingFilmCriticsVoteCount ?: 0)) }
            item.ratingGoodReview?.let { r -> add("Положительные отзывы" to "%.0f%%".format(Locale.US, r)) }
            item.year?.let { add("Год выхода" to "$it г.") }
            item.filmLength?.let { add("Длительность" to "$it мин.") }
            item.ratingAgeLimits?.replace("age", "")?.let { add("Возраст" to "$it+") }
            item.nameOriginal?.takeIf { it.isNotBlank() }?.let { add("Оригинальное название" to it) }
        }
    }

    if (detailsList.isEmpty()) return

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Детали и информация",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            detailsList.forEach { (label, value) ->
                DetailRow(label, value)
            }
        }
    }
}

@Composable
private fun FactsGrid(items: List<FactEntry>) {
    val visibleItems = items.filterNot { it.value.isNullOrBlank() }
    if (visibleItems.isEmpty()) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val hasTwoColumns = maxWidth >= 420.dp
        val rows = if (hasTwoColumns) {
            visibleItems.chunked(2)
        } else {
            visibleItems.map { listOf(it) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { item ->
                        FactCell(
                            item = item,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (hasTwoColumns && row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FactCell(
    item: FactEntry,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = item.value.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SeasonsCard(
    seasons: List<SeasonItem>
) {
    val totalEpisodes = seasons.sumOf { it.episodes.size }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Сезоны",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Сезонов: ${seasons.size} • Серий: $totalEpisodes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it.number }) { season ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Сезон ${season.number}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${season.episodes.size} сер.",
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

@Composable
private fun HorizontalFilmsCard(
    title: String,
    items: List<FilmLinkItem>,
    itemWidth: Dp = 132.dp,
    onOpenFilm: (Int) -> Unit
) {
    val validItems = remember(items) {
        items.filter { film ->
            val poster = film.posterUrl ?: film.posterUrlPreview
            val isInvalidPoster = poster.isNullOrBlank() || 
                poster.contains("no-poster") || 
                poster.contains("missing") || 
                poster.contains("placeholder") ||
                poster.contains("static/posters/missing") ||
                poster.contains("image-not-found")
            film.id > 0 && !isInvalidPoster &&
            (!film.nameRu.isNullOrBlank() || !film.nameEn.isNullOrBlank() || !film.nameOriginal.isNullOrBlank())
        }
    }
    if (validItems.isEmpty()) return

    val listState = rememberLazyListState()
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(
            state = listState,
            flingBehavior = snapFling,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = validItems.take(20),
                key = { it.id }
            ) { linked ->
                var isFailed by remember(linked.id) { mutableStateOf(false) }
                if (!isFailed) {
                    ElevatedCard(
                        modifier = Modifier
                            .width(itemWidth)
                            .clickable { onOpenFilm(linked.id) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            KinoshkaAsyncImage(
                                model = linked.posterUrl ?: linked.posterUrlPreview,
                                contentDescription = linked.nameRu ?: linked.nameOriginal,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = linked.nameRu ?: linked.nameOriginal ?: linked.nameEn ?: "Без названия",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                minLines = 2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagesCard(
    images: List<FilmImageItem>,
    onPreview: (Int) -> Unit
) {
    if (images.isEmpty()) return
    val listState = rememberLazyListState()
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Кадры",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(
            state = listState,
            flingBehavior = snapFling,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = images.take(24),
                key = { it.previewUrl ?: it.imageUrl.orEmpty() }
            ) { image ->
                val previewIndex = images.indexOf(image).takeIf { it >= 0 } ?: 0
                ElevatedCard(
                    modifier = Modifier
                        .width(220.dp)
                        .clickable {
                            onPreview(previewIndex)
                        },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    KinoshkaAsyncImage(
                        model = image.previewUrl ?: image.imageUrl,
                        contentDescription = "Кадр",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagesViewerDialog(
    images: List<FilmImageItem>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return
    val fullUrls = remember(images) {
        images.map { it.imageUrl ?: it.previewUrl.orEmpty() }.filter { it.isNotBlank() }
    }
    if (fullUrls.isEmpty()) return

    val safeStart = startIndex.coerceIn(0, fullUrls.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeStart,
        pageCount = { fullUrls.size }
    )

    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    val animatedDismissOffsetY by animateFloatAsState(
        targetValue = dismissOffsetY,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dismissAnim"
    )
    val backgroundAlpha = (1f - (kotlin.math.abs(animatedDismissOffsetY) / 450f)).coerceIn(0.2f, 1f)

    HideStatusBarEffect()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Smoothly animated blurred current image background with dark overlay and dismiss alpha
        Crossfade(
            targetState = pagerState.currentPage,
            animationSpec = tween(400),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backgroundAlpha }
        ) { pageIndex ->
            val bgUrl = fullUrls.getOrNull(pageIndex)
            Box(modifier = Modifier.fillMaxSize()) {
                if (bgUrl != null) {
                    coil.compose.AsyncImage(
                        model = bgUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(40.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f))
                )
            }
        }

        // Main Image Pager with 2-finger Zoom & Smooth Double Tap & Image Swiping
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = dismissOffsetY == 0f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            var targetScale by remember(page) { mutableFloatStateOf(1f) }
            var offset by remember(page) { mutableStateOf(Offset.Zero) }
            var imageAspectRatio by remember(page) { mutableStateOf<Float?>(null) }

            val scale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                label = "scaleAnimation"
            )

            val zoomGestureModifier = Modifier.pointerInput(page) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        if (targetScale <= 1.05f) {
                            // Track vertical drag for dismiss when image is 1x
                            if (kotlin.math.abs(pan.y) > kotlin.math.abs(pan.x) && kotlin.math.abs(pan.y) > 4f) {
                                dismissOffsetY += pan.y
                            }
                        } else {
                            // Track pan when image is zoomed in via double tap
                            offset += pan
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (kotlin.math.abs(dismissOffsetY) > 130f) {
                        onDismiss()
                    } else {
                        dismissOffsetY = 0f
                    }
                }
            }.pointerInput(page) {
                detectTapGestures(
                    onDoubleTap = {
                        if (targetScale > 1.2f) {
                            targetScale = 1f
                            offset = Offset.Zero
                        } else {
                            targetScale = 2.5f
                        }
                    },
                    onTap = {
                        if (targetScale > 1.05f) {
                            targetScale = 1f
                            offset = Offset.Zero
                        } else {
                            onDismiss()
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .padding(vertical = 48.dp)
                        .then(zoomGestureModifier)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y + (if (targetScale <= 1.05f) animatedDismissOffsetY else 0f)
                        )
                        .then(
                            if (imageAspectRatio != null) Modifier.aspectRatio(imageAspectRatio!!) else Modifier
                        )
                        .clip(RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    SubcomposeAsyncImage(
                        model = fullUrls[page],
                        contentDescription = "Кадр ${page + 1}",
                        contentScale = ContentScale.Fit,
                        onSuccess = { resultState ->
                            val drawable = resultState.result.drawable
                            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                                imageAspectRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
                            }
                        },
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    strokeWidth = 3.dp
                                )
                            }
                        },
                        error = {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Ошибка загрузки",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                    )
                }
            }
        }

        // Top Close Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Bottom Counter Indicator
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
        ) {
            Text(
                text = "${pagerState.currentPage + 1} из ${fullUrls.size}",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun HideStatusBarEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activityWindow = view.context.findActivity()?.window
        val controller =
            activityWindow?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private data class FactEntry(
    val label: String,
    val value: String?
)

private fun UserFilmStatus.toUiLabel(): String {
    return when (this) {
        UserFilmStatus.WATCHING -> "Смотрю"
        UserFilmStatus.PLANNED -> "В планах"
        UserFilmStatus.COMPLETED -> "Просмотрено"
        UserFilmStatus.REWATCHING -> "Пересматриваю"
        UserFilmStatus.ON_HOLD -> "Отложено"
        UserFilmStatus.DROPPED -> "Брошено"
    }
}

private fun FilmDetails.toWatchUrl(): String {
    if (kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET) {
        val shikimoriId = kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET
        return "https://kodik.info/find-player?shikimori_id=$shikimoriId"
    }
    val web = webUrl.orEmpty().trim()
    if (web.isNotBlank()) {
        return web
            .replace("https://www.kinopoisk.ru", "https://www.kinopoisk.cx")
            .replace("http://www.kinopoisk.ru", "https://www.kinopoisk.cx")
    }
    val pathPrefix = when (type?.uppercase(Locale.US)) {
        "TV_SERIES", "MINI_SERIES", "TV_SHOW" -> "series"
        else -> "film"
    }
    return "https://www.kinopoisk.cx/$pathPrefix/$kinopoiskId/"
}

private fun String?.toLocalizedType(): String? {
    return when (this?.uppercase(Locale.US)) {
        "FILM" -> "Фильм"
        "TV_SERIES", "MINI_SERIES", "TV_SHOW" -> "Сериал"
        "VIDEO" -> "Видео"
        "SHORT_FILM" -> "Короткометражка"
        null -> null
        else -> this.replace('_', ' ').lowercase(Locale("ru"))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
    }
}

private fun openPrivateDnsWithAdGuard(context: Context) {
    val dnsHost = "dns.adguard.com"
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Private DNS", dnsHost))

    val privateDnsIntent = Intent("android.settings.PRIVATE_DNS_SETTINGS").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val opened = runCatching { context.startActivity(privateDnsIntent) }.isSuccess
    if (!opened) {
        val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallbackIntent) }
    }

    Toast.makeText(
        context,
        "Скопировано: dns.adguard.com. Вставьте в поле Private DNS hostname.",
        Toast.LENGTH_LONG
    ).show()
}

@android.annotation.SuppressLint("MissingPermission")
private fun isAdGuardDnsActive(context: Context): Boolean {
    val targetHost = "dns.adguard.com"
    val fromSettings = runCatching {
        val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
            ?.trim()
            ?.lowercase(Locale.US)
        val specifier = Settings.Global.getString(context.contentResolver, "private_dns_specifier")
            ?.trim()
            ?.lowercase(Locale.US)
        mode == "hostname" && specifier == targetHost
    }.getOrDefault(false)

    if (fromSettings) return true

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false

    return runCatching {
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivity?.activeNetwork
        val dnsServerName = connectivity
            ?.getLinkProperties(activeNetwork)
            ?.privateDnsServerName
            ?.trim()
            ?.lowercase(Locale.US)
        dnsServerName == targetHost
    }.getOrDefault(false)
}

@Composable
private fun AnimeDetailsLayout(
    scrollState: LazyListState,
    state: DetailsUiState,
    isInteractive: Boolean,
    onWatch: (FilmDetails) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenFilm: (Int) -> Unit,
    onPreviewImage: (Int) -> Unit,
    onPosterClick: (Offset) -> Unit,
    onOpenCharacter: (Int) -> Unit,
    onOpenGenre: ((genreName: String, isAnime: Boolean) -> Unit)? = null
) {
    val item = state.item ?: return
    val anime = state.animeDetails
    val context = LocalContext.current
    var showCharactersSheet by remember { mutableStateOf(false) }
    var showChronologySheet by remember { mutableStateOf(false) }
    var posterBounds by remember { mutableStateOf<Rect?>(null) }

    val kindStr = when (anime?.kind?.lowercase()) {
        "tv" -> "ТВ"
        "movie" -> "Фильм"
        "ova" -> "OVA"
        "ona" -> "ONA"
        "special" -> "Спешл"
        else -> "Аниме"
    }
    val statusStr = when (anime?.status?.lowercase()) {
        "released" -> "Вышло"
        "ongoing" -> "Онгоинг"
        "anons" -> "Анонс"
        else -> anime?.status.orEmpty()
    }
    val seasonStr = formatAnimeSeason(anime?.season, anime?.airedOn ?: item.year?.toString())
    val epStr = if (anime?.status == "ongoing" && anime.episodesAired != null && anime.episodesAired > 0) {
        "${anime.episodesAired} из ${if (anime.episodes != null && anime.episodes > 0) anime.episodes else "?"} эп."
    } else if (anime?.episodes != null && anime.episodes > 0) {
        "${anime.episodes} эп."
    } else "—"
    val ageRatingStr = anime?.rating?.uppercase()?.replace("R_17", "R-17")?.replace("PG_13", "PG-13")?.replace("R_PLUS", "R+") ?: "—"

    LazyColumn(
        state = scrollState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Hero Cover (520dp, clickable, with transparent top bar buttons)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .onGloballyPositioned { coords ->
                        posterBounds = coords.boundsInWindow()
                    }
                    .clickable { onPosterClick(posterBounds?.center ?: Offset.Zero) }
            ) {
                KinoshkaAsyncImage(
                    model = item.posterUrl ?: item.coverUrl ?: item.posterUrlPreview,
                    contentDescription = item.nameRu,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item.ratingKinopoisk?.let { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "%.1f".format(Locale.US, r),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Text(
                        text = item.nameRu ?: item.nameOriginal ?: "Без названия",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Тип", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$kindStr · $statusStr", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                        }
                        Column {
                            Text("Сезон", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(seasonStr, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                        }
                        Column {
                            Text("Эпизоды", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(epStr, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                        }
                        Column {
                            Text("Рейтинг", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(ageRatingStr, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
        }

        // Action Buttons Row (Unified ActionPanel)
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ActionPanel(
                    enabled = isInteractive,
                    profile = state.userProfile,
                    seasons = state.seasons,
                    onWatch = {
                        onWatch(item)
                    },
                    onOpenEditor = onOpenEditor,
                    showDisableAdsButton = false,
                    onDisableAds = {}
                )
            }
        }

        // Standalone Expandable Description with padding
        if (!item.description.isNullOrBlank()) {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    AnimeExpandableDescription(
                        description = item.description,
                        onOpenCharacter = onOpenCharacter,
                        onOpenFilm = onOpenFilm,
                        onOpenUrl = onOpenUrl
                    )
                }
            }
        }

        // Screenshots / Frames Section (Immediately after description)
        if (state.images.isNotEmpty()) {
            item {
                ImagesCard(
                    images = state.images,
                    onPreview = onPreviewImage
                )
            }
        }

        // Genres horizontally separated buttons
        if (item.genres.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(item.genres) { g ->
                        g.genre?.let { genreName ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenGenre?.invoke(genreName, true) }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = genreName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Characters Section
        if (state.animeCharacters.isNotEmpty()) {
            item {
                AnimeCharactersCard(
                    roles = state.animeCharacters,
                    onOpenFullCharacters = { showCharactersSheet = true },
                    onOpenCharacter = onOpenCharacter
                )
            }
        }

        // Separate Related Section (compact width)
        if (state.relations.isNotEmpty()) {
            item {
                HorizontalFilmsCard(
                    title = "Похожее",
                    items = state.relations,
                    itemWidth = 104.dp,
                    onOpenFilm = onOpenFilm
                )
            }
        }

        // Separate Chronology Section
        if (state.fullChronology.isNotEmpty() || state.relations.isNotEmpty()) {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AnimeChronologyCard(
                        chronology = if (state.fullChronology.isNotEmpty()) state.fullChronology else state.relations,
                        onOpenChronology = { showChronologySheet = true }
                    )
                }
            }
        }

        // Details Section
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                AnimeFullDetailsCard(anime = anime, item = item)
            }
        }
    }

    if (showCharactersSheet) {
        AnimeCharactersSheet(
            roles = state.animeCharacters,
            onOpenCharacter = onOpenCharacter,
            onDismiss = { showCharactersSheet = false }
        )
    }

    if (showChronologySheet) {
        AnimeChronologySheet(
            currentItem = item,
            chronology = if (state.fullChronology.isNotEmpty()) state.fullChronology else state.relations,
            franchiseResponse = state.franchiseResponse,
            animeDetails = state.animeDetails,
            onDismiss = { showChronologySheet = false },
            onOpenFilm = onOpenFilm
        )
    }
}

@Composable
private fun AnimeExpandableDescription(
    description: String,
    onOpenCharacter: (Int) -> Unit,
    onOpenFilm: (Int) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val bgColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary

    val annotatedText = remember(description, primaryColor) {
        parseShikimoriBbCode(description, primaryColor)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
    ) {
        ClickableText(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onClick = { offset ->
                val charAnnotations = annotatedText.getStringAnnotations("character_id", offset, offset)
                val charId = charAnnotations.firstOrNull()?.item?.toIntOrNull()

                val animeAnnotations = annotatedText.getStringAnnotations("anime_id", offset, offset)
                val animeId = animeAnnotations.firstOrNull()?.item?.toIntOrNull()

                val urlAnnotations = annotatedText.getStringAnnotations("url", offset, offset)
                val targetUrl = urlAnnotations.firstOrNull()?.item

                when {
                    charId != null -> onOpenCharacter(charId)
                    animeId != null -> onOpenFilm(animeId)
                    !targetUrl.isNullOrBlank() -> onOpenUrl(targetUrl)
                    else -> expanded = !expanded
                }
            }
        )
        if (!expanded) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                bgColor.copy(alpha = 0.7f),
                                bgColor
                            ),
                            startY = 30f
                        )
                    )
            )
        }
    }
}

@Composable
private fun AnimeCharactersCard(
    roles: List<hd.kinoshka.app.data.model.ShikimoriRole>,
    onOpenFullCharacters: () -> Unit,
    onOpenCharacter: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { onOpenFullCharacters() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Персонажи",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Все персонажи",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(roles.take(15)) { role ->
                val char = role.character ?: return@items
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(76.dp)
                        .clickable { onOpenCharacter(char.id) }
                ) {
                    Surface(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        KinoshkaAsyncImage(
                            model = char.image?.getFullPreviewUrl(),
                            contentDescription = char.russian ?: char.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = char.russian?.takeIf { it.isNotBlank() } ?: char.name ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimeCharactersSheet(
    roles: List<hd.kinoshka.app.data.model.ShikimoriRole>,
    onOpenCharacter: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        KeepBottomSheetNavigationBarFromActivity()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Персонажи (${roles.size})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxHeight(0.75f)
            ) {
                items(roles) { role ->
                    val char = role.character ?: return@items
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onOpenCharacter(char.id)
                            },
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            KinoshkaAsyncImage(
                                model = char.image?.getFullPreviewUrl(),
                                contentDescription = char.russian ?: char.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = char.russian?.takeIf { it.isNotBlank() } ?: char.name ?: "",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            if (!char.name.isNullOrBlank() && char.russian != null) {
                                Text(
                                    text = char.name,
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

@Composable
private fun AnimeChronologyCard(
    chronology: List<FilmLinkItem>,
    onOpenChronology: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChronology() },
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Хронология франшизы",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Список и схема связей всех частей (${chronology.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimeChronologySheet(
    currentItem: FilmDetails?,
    chronology: List<FilmLinkItem>,
    franchiseResponse: hd.kinoshka.app.data.model.ShikimoriFranchiseResponse?,
    animeDetails: hd.kinoshka.app.data.model.ShikimoriAnimeDetails?,
    onDismiss: () -> Unit,
    onOpenFilm: (Int) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Список, 1: Схема связей

    val fullTimeline = remember(currentItem, chronology, animeDetails) {
        val list = mutableListOf<FilmLinkItem>()
        if (chronology.none { it.id == currentItem?.kinopoiskId }) {
            currentItem?.let { item ->
                val kindStr = when (animeDetails?.kind?.lowercase()) {
                    "tv" -> "ТВ"
                    "movie" -> "Фильм"
                    "ova" -> "OVA"
                    "ona" -> "ONA"
                    "special" -> "Спешл"
                    else -> animeDetails?.kind?.uppercase()
                }
                list.add(
                    FilmLinkItem(
                        kinopoiskId = item.kinopoiskId,
                        nameRu = item.nameRu,
                        nameEn = item.nameOriginal,
                        nameOriginal = item.nameOriginal,
                        posterUrl = item.posterUrl,
                        posterUrlPreview = item.posterUrlPreview,
                        relationType = "Текущее",
                        year = item.year,
                        type = kindStr
                    )
                )
            }
        }
        list.addAll(chronology.filter { it.id > 0 })
        list.distinctBy { it.id }.sortedWith(compareBy<FilmLinkItem> { it.year ?: 9999 }.thenBy { it.id })
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight()
    ) {
        KeepBottomSheetNavigationBarFromActivity()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedTab == 0) "Хронология (${fullTimeline.size})" else "Схема франшизы",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("Список") }
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("Схема") }
                    )
                }
            }

            if (selectedTab == 0) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(fullTimeline) { index, item ->
                        val isCurrent = item.id == currentItem?.kinopoiskId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    if (!isCurrent) onOpenFilm(item.id)
                                },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(24.dp)
                                )
                                Surface(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    KinoshkaAsyncImage(
                                        model = item.posterUrl ?: item.posterUrlPreview,
                                        contentDescription = item.nameRu,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.nameRu ?: item.nameOriginal ?: item.nameEn ?: "Без названия",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val infoParts = listOfNotNull(
                                        item.year?.let { "$it г." },
                                        item.type,
                                        item.relationType?.takeIf { it.isNotBlank() && it != "Текущее" }
                                    ).joinToString(" · ")
                                    if (infoParts.isNotBlank()) {
                                        Text(
                                            text = infoParts,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    if (franchiseResponse != null && franchiseResponse.nodes.isNotEmpty()) {
                        FranchiseGraphView(
                            nodes = franchiseResponse.nodes,
                            links = franchiseResponse.links,
                            currentAnimeId = (currentItem?.kinopoiskId ?: 0) - hd.kinoshka.app.data.model.ANIME_ID_OFFSET,
                            onOpenAnime = { targetShikimoriId ->
                                onDismiss()
                                onOpenFilm(targetShikimoriId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
                            }
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("Схема франшизы недоступна", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FranchiseGraphView(
    nodes: List<hd.kinoshka.app.data.model.ShikimoriFranchiseNode>,
    links: List<hd.kinoshka.app.data.model.ShikimoriFranchiseLink>,
    currentAnimeId: Int,
    onOpenAnime: (Int) -> Unit
) {
    val density = LocalDensity.current
    val cardWidthDp = 72.dp
    val cardHeightDp = 104.dp
    val cardWidthPx = with(density) { cardWidthDp.toPx() }
    val cardHeightPx = with(density) { cardHeightDp.toPx() }
    val stepX = with(density) { 115.dp.toPx() }
    val stepY = with(density) { 135.dp.toPx() }

    // Compact chronological layer layout: clean grid sorted by year to prevent tangled links
    val initialPositionsPx = remember(nodes, density) {
        val sortedYears = nodes.mapNotNull { it.year }.distinct().sorted()
        val posMap = mutableMapOf<Int, Offset>()
        val yearCounts = mutableMapOf<Int, Int>()

        nodes.forEachIndexed { idx, node ->
            val yearIndex = if (node.year != null && sortedYears.contains(node.year))
                sortedYears.indexOf(node.year) else (idx % maxOf(1, sortedYears.size))
            val countInYear = yearCounts.getOrDefault(yearIndex, 0)
            yearCounts[yearIndex] = countInYear + 1
            posMap[node.id] = Offset(yearIndex * stepX, countInYear * stepY)
        }
        posMap
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        // Auto-center canvas viewport on the current anime card
        val initOffset = remember(nodes, currentAnimeId, containerW, containerH) {
            val currentPos = initialPositionsPx[currentAnimeId] ?: Offset.Zero
            Offset(containerW / 2f - currentPos.x - cardWidthPx / 2f, containerH / 2f - currentPos.y - cardHeightPx / 2f)
        }

        var canvasOffset by remember(nodes, currentAnimeId) { mutableStateOf(initOffset) }
        var scale by remember { mutableFloatStateOf(1f) }

        val primaryColor = MaterialTheme.colorScheme.primary
        val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Pan and pinch-zoom for entire canvas sheet
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.filter { it.pressed }
                            if (pointers.size >= 2) {
                                val prevCentroid = pointers.map { it.previousPosition }.let {
                                    it.fold(Offset.Zero) { acc, o -> acc + o } / it.size.toFloat()
                                }
                                val currCentroid = pointers.map { it.position }.let {
                                    it.fold(Offset.Zero) { acc, o -> acc + o } / it.size.toFloat()
                                }
                                val prevDist = (pointers[0].previousPosition - pointers[1].previousPosition).getDistance()
                                val currDist = (pointers[0].position - pointers[1].position).getDistance()
                                if (prevDist > 0f) {
                                    val zoom = (currDist / prevDist).coerceIn(0.9f, 1.1f)
                                    val newScale = (scale * zoom).coerceIn(0.35f, 3.5f)
                                    val focalInCanvas = (prevCentroid - canvasOffset) / scale
                                    canvasOffset = currCentroid - focalInCanvas * newScale
                                    scale = newScale
                                }
                                pointers.forEach { it.consume() }
                            } else if (pointers.size == 1) {
                                val pointer = pointers[0]
                                val drag = pointer.position - pointer.previousPosition
                                canvasOffset += drag
                                pointer.consume()
                            }
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = canvasOffset.x
                        translationY = canvasOffset.y
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                // Clean orthogonal/curved connection lines
                Canvas(modifier = Modifier.fillMaxSize()) {
                    links.forEach { link ->
                        val startPos = initialPositionsPx[link.sourceId]
                            ?: link.sourceIndex?.let { idx -> if (idx in nodes.indices) initialPositionsPx[nodes[idx].id] else null }
                        val endPos = initialPositionsPx[link.targetId]
                            ?: link.targetIndex?.let { idx -> if (idx in nodes.indices) initialPositionsPx[nodes[idx].id] else null }
                        if (startPos == null || endPos == null) return@forEach

                        val sc = Offset(startPos.x + cardWidthPx / 2f, startPos.y + cardHeightPx / 2f)
                        val ec = Offset(endPos.x + cardWidthPx / 2f, endPos.y + cardHeightPx / 2f)

                        drawLine(
                            color = outlineColor,
                            start = sc, end = ec,
                            strokeWidth = 2.2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        )
                        // Arrow head indicator
                        val angle = kotlin.math.atan2((ec.y - sc.y).toDouble(), (ec.x - sc.x).toDouble())
                        val ar = 14f
                        val aa = Math.toRadians(22.0)
                        val ap1 = Offset(
                            (ec.x - ar * kotlin.math.cos(angle - aa)).toFloat(),
                            (ec.y - ar * kotlin.math.sin(angle - aa)).toFloat()
                        )
                        val ap2 = Offset(
                            (ec.x - ar * kotlin.math.cos(angle + aa)).toFloat(),
                            (ec.y - ar * kotlin.math.sin(angle + aa)).toFloat()
                        )
                        drawPath(Path().apply {
                            moveTo(ec.x, ec.y); lineTo(ap1.x, ap1.y); lineTo(ap2.x, ap2.y); close()
                        }, color = outlineColor)
                    }
                }

                // Render Clean Fixed Cards (no dragging)
                nodes.forEach { node ->
                    val posPx = initialPositionsPx[node.id] ?: Offset.Zero
                    val isCurrent = node.id == currentAnimeId
                    val posXDp = with(density) { posPx.x.toDp() }
                    val posYDp = with(density) { posPx.y.toDp() }

                    Surface(
                        modifier = Modifier
                            .offset(x = posXDp, y = posYDp)
                            .width(cardWidthDp)
                            .height(cardHeightDp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenAnime(node.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = if (isCurrent) 12.dp else 3.dp
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            KinoshkaAsyncImage(
                                model = node.imageUrl ?: "https://smarthard.net/static/animes/${node.id}.jpeg",
                                contentDescription = node.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (isCurrent) {
                                Box(modifier = Modifier.fillMaxSize().background(primaryColor.copy(alpha = 0.28f)))
                            }
                            node.year?.let { yr ->
                                Surface(
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
                                    shape = RoundedCornerShape(5.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                ) {
                                    Text(
                                        text = "$yr",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp, fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
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
private fun AnimeFullDetailsCard(
    anime: hd.kinoshka.app.data.model.ShikimoriAnimeDetails?,
    item: FilmDetails
) {
    val detailsList = remember(anime, item) {
        buildList {
            val studiosStr = anime?.studios?.mapNotNull { it.name }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            studiosStr?.let { add("Студия" to it) }

            val sourceStr = formatAnimeSource(anime?.source)
            if (sourceStr != "—" && sourceStr.isNotBlank()) {
                add("Первоисточник" to sourceStr)
            }

            item.filmLength?.let { len ->
                if (len > 0) add("Длительность эпизода" to "$len мин.")
            }

            val licensorStr = anime?.licenseNameRu?.takeIf { it.isNotBlank() }
                ?: anime?.licensors?.joinToString(", ")?.takeIf { it.isNotBlank() }
            licensorStr?.let { add("Лицензировано" to it) }

            val nextEp = formatNextEpisode(anime?.nextEpisodeAt)
            if (nextEp != "—" && nextEp.isNotBlank()) {
                add("Следующий эпизод" to nextEp)
            }

            val aired = formatRussianDate(anime?.airedOn)
            if (aired != "—" && aired.isNotBlank()) {
                add("Начало показа" to aired)
            }

            item.nameOriginal?.takeIf { it.isNotBlank() }?.let { add("Ромадзи" to it) }
            item.nameRu?.takeIf { it.isNotBlank() }?.let { add("По-русски" to it) }
            anime?.english?.joinToString(", ")?.takeIf { it.isNotBlank() }?.let { add("По-английски" to it) }
            anime?.japanese?.joinToString(", ")?.takeIf { it.isNotBlank() }?.let { add("По-японски" to it) }
            anime?.synonyms?.joinToString(", ")?.takeIf { it.isNotBlank() }?.let { add("Другие названия" to it) }
        }
    }

    if (detailsList.isEmpty()) return

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Детали",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )

            detailsList.forEach { (label, value) ->
                DetailRow(label, value)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.2f)
        )
    }
}

private fun formatAnimeSeason(season: String?, airedOn: String?): String {
    if (!season.isNullOrBlank()) {
        val parts = season.split("_")
        val quarter = when (parts.getOrNull(0)?.lowercase() ?: parts.getOrNull(1)?.lowercase()) {
            "spring" -> "Весна"
            "summer" -> "Лето"
            "fall", "autumn" -> "Осень"
            "winter" -> "Зима"
            else -> null
        }
        val year = parts.find { it.toIntOrNull() != null } ?: airedOn?.take(4)
        if (quarter != null && year != null) return "$quarter $year"
    }
    return airedOn?.take(4) ?: "—"
}

private fun formatAnimeSource(source: String?): String {
    return when (source?.lowercase()) {
        "light_novel" -> "Ранобэ"
        "manga" -> "Манга"
        "original" -> "Оригинал"
        "game" -> "Игра"
        "visual_novel" -> "Визуальная новелла"
        "web_manga" -> "Веб-манга"
        "novel" -> "Новелла"
        else -> source ?: "—"
    }
}

private fun formatRussianDate(dateStr: String?): String {
    if (dateStr.isNullOrBlank()) return "—"
    val clean = dateStr.take(10)
    val parts = clean.split("-")
    if (parts.size < 3) return dateStr
    val year = parts[0].toIntOrNull() ?: return dateStr
    val monthInt = parts[1].toIntOrNull() ?: return dateStr
    val dayInt = parts[2].toIntOrNull() ?: return dateStr

    val monthRu = when (monthInt) {
        1 -> "января"
        2 -> "февраля"
        3 -> "марта"
        4 -> "апреля"
        5 -> "мая"
        6 -> "июня"
        7 -> "июля"
        8 -> "августа"
        9 -> "сентября"
        10 -> "октября"
        11 -> "ноября"
        12 -> "декабря"
        else -> return dateStr
    }
    return "$dayInt $monthRu $year г."
}

private fun formatNextEpisode(isoDate: String?): String {
    return formatRussianDate(isoDate)
}

private fun parseShikimoriBbCode(
    rawText: String,
    primaryColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        val regex = Regex(
            """\[(character|anime|manga|person)=(\d+)(?:[ \t]+([^]]+))?\](?:(.*?)\[/\1\])?|""" +
            """\[url=(.*?)\](.*?)\[/url\]|""" +
            """\[url\](.*?)\[/url\]|""" +
            """\[(b|i|spoiler)\](.*?)\[/\8\]|""" +
            """\[\[(?:(character|anime|manga|person)=)?(\d+)?\|?([^]]+)\]\]"""
        )
        var lastIndex = 0
        for (match in regex.findAll(rawText)) {
            append(rawText.substring(lastIndex, match.range.first))

            fun g(idx: Int): String? = match.groupValues.getOrNull(idx)?.ifBlank { null }

            val tagType = g(1) ?: g(11)
            val entityId = g(2)?.toIntOrNull() ?: g(12)?.toIntOrNull()
            val inlineName = g(3)
            val blockContent = g(4) ?: g(10) ?: g(13)

            val urlAttribute = g(5)
            val urlBlockContent = g(6) ?: g(7)

            val styleTag = g(8) ?: g(9)

            when {
                tagType != null && entityId != null -> {
                    val displayName = blockContent ?: inlineName ?: when (tagType) {
                        "character" -> "Персонаж"
                        "anime" -> "Аниме"
                        "manga" -> "Манга"
                        "person" -> "Персона"
                        else -> "Ссылка"
                    }
                    val start = length
                    append(displayName)
                    addStyle(
                        SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold),
                        start,
                        length
                    )
                    addStringAnnotation(
                        tag = "${tagType}_id",
                        annotation = entityId.toString(),
                        start = start,
                        end = length
                    )
                }
                urlAttribute != null || urlBlockContent != null -> {
                    val targetUrl = urlAttribute ?: urlBlockContent ?: ""
                    val displayText = urlBlockContent ?: urlAttribute ?: targetUrl
                    val start = length
                    append(displayText)
                    addStyle(
                        SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold),
                        start,
                        length
                    )
                    addStringAnnotation(
                        tag = "url",
                        annotation = targetUrl,
                        start = start,
                        end = length
                    )
                }
                styleTag != null -> {
                    val text = blockContent ?: g(10) ?: ""
                    val start = length
                    when (styleTag) {
                        "b" -> {
                            append(text)
                            addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                        }
                        "i" -> {
                            append(text)
                            addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, length)
                        }
                        "spoiler" -> {
                            append("[Спойлер: $text]")
                            addStyle(SpanStyle(color = primaryColor.copy(alpha = 0.8f)), start, length)
                        }
                    }
                }
                else -> {
                    append(match.value)
                }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < rawText.length) {
            append(rawText.substring(lastIndex))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterDetailsSheet(
    characterId: Int,
    onOpenFilm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var characterDetails by remember(characterId) { mutableStateOf<hd.kinoshka.app.data.model.ShikimoriCharacterDetails?>(null) }
    var isLoading by remember(characterId) { mutableStateOf(true) }

    LaunchedEffect(characterId) {
        val api = hd.kinoshka.app.data.api.ApiClient.shikimoriApi(context)
        val repo = hd.kinoshka.app.data.repo.AnimeRepository(api)
        characterDetails = repo.character(characterId)
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (characterDetails == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Не удалось загрузить данные персонажа", color = MaterialTheme.colorScheme.error)
                }
            } else {
                val char = characterDetails!!
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxHeight(0.85f)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                modifier = Modifier
                                    .width(110.dp)
                                    .aspectRatio(3f / 4f)
                                    .clip(RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                KinoshkaAsyncImage(
                                    model = char.imageUrl,
                                    contentDescription = char.displayTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = char.displayTitle,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                char.name?.takeIf { it != char.russian }?.let { originalName ->
                                    Text(
                                        text = originalName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                char.japanese?.let { jp ->
                                    Text(
                                        text = jp,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    char.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Описание",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val cleanDesc = desc.replace(Regex("""\[.*?\]"""), "").trim()
                                Text(
                                    text = cleanDesc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (!char.animes.isNullOrEmpty()) {
                        item {
                            Text(
                                text = "Участвует в аниме (${char.animes.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(char.animes) { animeItem ->
                            val filmId = animeItem.id + hd.kinoshka.app.data.model.ANIME_ID_OFFSET
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onDismiss()
                                        onOpenFilm(filmId)
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    KinoshkaAsyncImage(
                                        model = animeItem.posterUrl,
                                        contentDescription = animeItem.displayTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = animeItem.displayTitle,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    animeItem.airedOn?.take(4)?.let { yr ->
                                        Text(yr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
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
private fun DetailsTopBar(
    item: FilmDetails,
    isAnime: Boolean,
    scrollState: LazyListState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    
    val thresholdPx = with(density) { (if (isAnime) 380.dp else 200.dp).toPx() }
    val fadeRangePx = with(density) { 40.dp.toPx() }
    
    val currentScroll = if (scrollState.firstVisibleItemIndex > 0) {
        thresholdPx + fadeRangePx
    } else {
        scrollState.firstVisibleItemScrollOffset.toFloat()
    }
    
    val alpha = ((currentScroll - thresholdPx) / fadeRangePx).coerceIn(0f, 1f)

    val backgroundColor = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = alpha),
                        backgroundColor.copy(alpha = alpha * 0.85f),
                        backgroundColor.copy(alpha = alpha * 0.45f),
                        Color.Transparent
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Text(
                    text = item.nameRu ?: item.nameOriginal ?: "Без названия",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { this.alpha = alpha },
                    textAlign = TextAlign.Center
                )
                
                IconButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            val shareUrl = if (isAnime) {
                                item.webUrl ?: "https://shikimori.io/animes/${item.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET}"
                            } else {
                                item.webUrl ?: "https://www.kinopoisk.ru/film/${item.kinopoiskId}/"
                            }
                            putExtra(Intent.EXTRA_TEXT, shareUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Поделиться"))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Поделиться",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}


