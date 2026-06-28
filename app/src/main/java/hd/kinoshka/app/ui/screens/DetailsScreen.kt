package hd.kinoshka.app.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewPosterUrl by remember(filmId) { mutableStateOf<String?>(null) }
    var imageViewerStartIndex by remember(filmId) { mutableIntStateOf(-1) }
    var showProfileEditor by remember(filmId) { mutableStateOf(false) }
    var adGuardDnsActive by remember { mutableStateOf(isAdGuardDnsActive(context)) }
    var isInteractive by remember { mutableStateOf(true) }

    LaunchedEffect(filmId) {
        load(filmId)
    }

    BackHandler {
        when {
            previewPosterUrl != null -> previewPosterUrl = null
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            state.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveBlobLoadingIndicator(color = MaterialTheme.colorScheme.primary)
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
                val isAnime = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET
                if (isAnime) {
                    AnimeDetailsLayout(
                        state = state,
                        isInteractive = isInteractive,
                        onWatch = { filmDetails ->
                            isInteractive = false
                            onWatch(filmDetails)
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
                        onPosterClick = {
                            previewPosterUrl = item.posterUrl ?: item.posterUrlPreview
                        },
                        onBack = onBack
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            HeroHeader(
                                item = item,
                                onPosterClick = {
                                    previewPosterUrl = item.posterUrl ?: item.posterUrlPreview
                                }
                            )
                        }
                    item {
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            ActionPanel(
                                enabled = isInteractive,
                                profile = state.userProfile,
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
                    if (state.item.type == "TV_SERIES" && state.seasons.isNotEmpty()) {
                        item {
                            Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                                SeasonsCard(state.seasons)
                            }
                        }
                    }
                    item {
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            ExpandableDescriptionInfoCard(item = item)
                        }
                    }
                    if (state.relations.isNotEmpty()) {
                        item {
                            val relTitle = if (item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET) "Связаное аниме и хронология" else "Связанные фильмы"
                            HorizontalFilmsCard(
                                title = relTitle,
                                items = state.relations,
                                onOpenFilm = { id ->
                                    isInteractive = false
                                    onOpenFilm(id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

        previewPosterUrl?.let { imageUrl ->
            PosterPreviewDialog(
                imageUrl = imageUrl,
                title = state.item?.nameRu ?: state.item?.nameOriginal ?: "Обложка",
                onDismiss = { previewPosterUrl = null }
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
                profile = state.userProfile,
                saving = state.savingProfile,
                onDismiss = { showProfileEditor = false },
                onSave = { status, rating, note, seasons, episodes ->
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
                        state.seasons.size.takeIf { it > 0 },
                        state.seasons.sumOf { it.episodes.size }.takeIf { it > 0 }
                    )
                    showProfileEditor = false
                }
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
    onPosterClick: () -> Unit
) {
    val cover = item.coverUrl ?: item.posterUrl
    var posterAspectRatio by remember(item.kinopoiskId) { mutableStateOf(2f / 3f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) {
        KinoshkaAsyncImage(
            model = cover,
            contentDescription = item.nameRu,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.18f),
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
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onPosterClick),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.56f)
            ) {
                KinoshkaAsyncImage(
                    model = item.posterUrlPreview ?: item.posterUrl,
                    contentDescription = item.nameRu,
                    contentScale = ContentScale.Crop,
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
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.84f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.nameRu ?: item.nameOriginal ?: "Без названия",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    item.nameOriginal?.takeIf { it.isNotBlank() && it != item.nameRu }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = listOfNotNull(
                            item.year?.toString(),
                            item.filmLength?.let { "$it мин" },
                            item.type.toLocalizedType(),
                            item.ratingAgeLimits?.replace("age", "")?.let { "$it+" }
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )

                    val isAnime = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET
                    if (isAnime) {
                        item.ratingKinopoisk?.let { r ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = formatRating(r),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else {
                        Text(
                            text = listOfNotNull(
                                item.ratingKinopoisk?.let { "KP ${formatRating(it)}" },
                                item.ratingImdb?.let { "IMDb ${formatRating(it)}" }
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
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
    onDismiss: () -> Unit
) {
    HideStatusBarEffect()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))
            .clickable(onClick = onDismiss)
    ) {
        KinoshkaAsyncImage(
            model = imageUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(24.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.42f))
        )
        KinoshkaAsyncImage(
            model = imageUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(24.dp))
        )
    }
}

@Composable
private fun ActionPanel(
    enabled: Boolean,
    profile: UserFilmProfile?,
    onWatch: () -> Unit,
    onOpenEditor: () -> Unit,
    showDisableAdsButton: Boolean,
    onDisableAds: () -> Unit
) {
    val status = profile?.status
    val statusText = when (status) {
        UserFilmStatus.COMPLETED -> "Просмотрено"
        UserFilmStatus.WATCHING -> "Смотрю"
        UserFilmStatus.PLANNED -> "В планах"
        UserFilmStatus.REWATCHING -> "Пересматриваю"
        UserFilmStatus.ON_HOLD -> "Отложено"
        UserFilmStatus.DROPPED -> "Брошено"
        null -> "Добавить в список"
    }
    val statusIcon = when (status) {
        UserFilmStatus.COMPLETED -> Icons.Default.Check
        UserFilmStatus.WATCHING -> Icons.Default.PlayArrow
        UserFilmStatus.PLANNED -> Icons.Default.Star
        UserFilmStatus.REWATCHING -> Icons.Default.Refresh
        UserFilmStatus.ON_HOLD -> Icons.Default.KeyboardArrowDown
        UserFilmStatus.DROPPED -> Icons.Default.Close
        null -> Icons.Default.Add
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onOpenEditor,
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (status != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (status != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = statusIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            FilledIconButton(
                onClick = onWatch,
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Смотреть", modifier = Modifier.size(26.dp))
            }
        }

        if (showDisableAdsButton) {
            OutlinedButton(
                onClick = onDisableAds,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Отключить рекламу", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun UserProfileSummaryCard(
    item: FilmDetails,
    profile: UserFilmProfile?,
    enabled: Boolean,
    onOpenEditor: () -> Unit
) {
    val status = profile?.status?.toUiLabel() ?: "Без статуса"
    val progress = if (item.type == "TV_SERIES") {
        " • S${profile?.watchedSeasons ?: 0} E${profile?.watchedEpisodes ?: 0}"
    } else {
        ""
    }
    Button(
        onClick = onOpenEditor,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Text(
            text = "Моя библиотека: $status$progress",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UserProfileEditorSheet(
    item: FilmDetails,
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
        mutableStateOf((profile?.userRating ?: 6).toFloat())
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
        onDismissRequest = onDismiss
    ) {
        KeepBottomSheetNavigationBarFromActivity()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Прогресс",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { status = null }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Очистить статус",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(UserFilmStatus.entries) { option ->
                    FilterChip(
                        selected = status == option,
                        onClick = { status = option },
                        label = { Text(option.toUiLabel()) }
                    )
                }
            }

            val isAnimeItem = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET
            if (isAnimeItem) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StepperField(
                        label = "Серии",
                        value = episodesCount,
                        onValueChange = { episodesCount = it.coerceAtLeast(0) },
                        modifier = Modifier.weight(1f)
                    )
                    StepperField(
                        label = "Повторения",
                        value = seasonsCount,
                        onValueChange = { seasonsCount = it.coerceAtLeast(0) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (item.type == "TV_SERIES") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StepperField(
                        label = "Сезоны",
                        value = seasonsCount,
                        onValueChange = { seasonsCount = it.coerceAtLeast(0) },
                        modifier = Modifier.weight(1f)
                    )
                    StepperField(
                        label = "Серии",
                        value = episodesCount,
                        onValueChange = { episodesCount = it.coerceAtLeast(0) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = "Оценка: ${ratingValue.roundToInt()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = ratingValue,
                onValueChange = { ratingValue = it },
                valueRange = 1f..10f,
                steps = 8
            )

            TextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Заметка") },
                minLines = 1,
                maxLines = 2,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onDismiss
                ) {
                    Text("Отмена")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(
                            status,
                            ratingValue.roundToInt(),
                            noteInput,
                            if (item.type == "TV_SERIES") seasonsCount else null,
                            if (item.type == "TV_SERIES") episodesCount else null
                        )
                    },
                    enabled = !saving,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(if (saving) "..." else "Сохранить")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
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
private fun StepperField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { onValueChange((value - 1).coerceAtLeast(0)) },
                    modifier = Modifier.size(36.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("−")
                }
                OutlinedTextField(
                    value = value.toString(),
                    onValueChange = { text ->
                        onValueChange(text.toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 64.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(
                    onClick = { onValueChange(value + 1) },
                    modifier = Modifier.size(36.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text("+")
                }
            }
        }
    }
}

@Composable
private fun ExpandableDescriptionInfoCard(item: FilmDetails) {
    var expanded by remember(item.kinopoiskId) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "meta_expand_rotation"
    )
    val infoItems = listOf(
        FactEntry("Жанры", item.genres.mapNotNull { it.genre }.takeIf { it.isNotEmpty() }?.joinToString(", ")),
        FactEntry("Страны", item.countries.mapNotNull { it.country }.takeIf { it.isNotEmpty() }?.joinToString(", ")),
        FactEntry("Год", item.year?.toString()),
        FactEntry("Длительность", item.filmLength?.let { "$it мин" }),
        FactEntry("Тип", item.type.toLocalizedType()),
        FactEntry("Возраст", item.ratingAgeLimits?.replace("age", "")?.let { "$it+" })
    )
    val isAnime = item.kinopoiskId >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET
    val ratingItems = if (isAnime) {
        listOfNotNull(
            FactEntry("Оценка", item.ratingKinopoisk?.let { "★ ${formatRating(it)}" })
        )
    } else {
        listOf(
            FactEntry("Кинопоиск", item.ratingKinopoisk?.let { "${formatRating(it)} (${item.ratingKinopoiskVoteCount ?: 0})" }),
            FactEntry("IMDb", item.ratingImdb?.let { "${formatRating(it)} (${item.ratingImdbVoteCount ?: 0})" }),
            FactEntry("Критики", item.ratingFilmCritics?.let { "${formatRating(it)} (${item.ratingFilmCriticsVoteCount ?: 0})" }),
            FactEntry("Ожидание", item.ratingAwait?.let { "${formatRating(it)} (${item.ratingAwaitCount ?: 0})" }),
            FactEntry("РФ критики", item.ratingRfCritics?.let { "${formatRating(it)} (${item.ratingRfCriticsVoteCount ?: 0})" }),
            FactEntry("Позитив", item.ratingGoodReview?.let { "${formatRating(it)}% (${item.ratingGoodReviewVoteCount ?: 0})" })
        )
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Описание и детали",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    modifier = Modifier.size(28.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
            }

            if (expanded) {
                item.slogan?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "\"$it\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = item.shortDescription ?: item.description ?: "Описание отсутствует",
                    style = MaterialTheme.typography.bodyMedium
                )
                item.editorAnnotation?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val compactTwoColumns = maxWidth >= 320.dp
                    if (compactTwoColumns) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetaSectionCard(
                                title = "Информация",
                                items = infoItems,
                                modifier = Modifier.weight(1f)
                            )
                            MetaSectionCard(
                                title = "Рейтинги",
                                items = ratingItems,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetaSectionCard(
                                title = "Информация",
                                items = infoItems
                            )
                            MetaSectionCard(
                                title = "Рейтинги",
                                items = ratingItems
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaSectionCard(
    title: String,
    items: List<FactEntry>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            FactsGrid(items = items)
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
                items = items.filter { it.id > 0 }.take(20),
                key = { it.id }
            ) { linked ->
                ElevatedCard(
                    modifier = Modifier
                        .width(itemWidth)
                        .clickable { onOpenFilm(linked.id) },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        KinoshkaAsyncImage(
                            model = linked.posterUrlPreview ?: linked.posterUrl,
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

    HideStatusBarEffect()
    val horizontalPaddingPx = with(LocalDensity.current) { 16.dp.toPx() }
    val verticalPaddingPx = with(LocalDensity.current) { 24.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            var imageSize by remember(page) { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(imageSize, horizontalPaddingPx, verticalPaddingPx) {
                        detectTapGestures { tapOffset ->
                            val containerWidth = size.width.toFloat()
                            val containerHeight = size.height.toFloat()
                            val viewportWidth =
                                (containerWidth - horizontalPaddingPx * 2f).coerceAtLeast(1f)
                            val viewportHeight =
                                (containerHeight - verticalPaddingPx * 2f).coerceAtLeast(1f)
                            val imageWidth = imageSize.width.toFloat()
                            val imageHeight = imageSize.height.toFloat()
                            val imageAspect = if (imageWidth > 0f && imageHeight > 0f) {
                                imageWidth / imageHeight
                            } else {
                                null
                            }

                            if (imageAspect == null) {
                                onDismiss()
                                return@detectTapGestures
                            }

                            val viewportAspect = viewportWidth / viewportHeight
                            val drawWidth: Float
                            val drawHeight: Float
                            val drawLeft: Float
                            val drawTop: Float

                            if (imageAspect >= viewportAspect) {
                                drawWidth = viewportWidth
                                drawHeight = viewportWidth / imageAspect
                                drawLeft = horizontalPaddingPx
                                drawTop = verticalPaddingPx + (viewportHeight - drawHeight) / 2f
                            } else {
                                drawWidth = viewportHeight * imageAspect
                                drawHeight = viewportHeight
                                drawLeft = horizontalPaddingPx + (viewportWidth - drawWidth) / 2f
                                drawTop = verticalPaddingPx
                            }

                            val isOutsideImage =
                                tapOffset.x < drawLeft ||
                                    tapOffset.x > drawLeft + drawWidth ||
                                    tapOffset.y < drawTop ||
                                    tapOffset.y > drawTop + drawHeight
                            if (isOutsideImage) onDismiss()
                        }
                    }
            ) {
                KinoshkaAsyncImage(
                    model = fullUrls[page],
                    contentDescription = "Кадр ${page + 1}",
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val drawable = state.result.drawable
                        val width = drawable.intrinsicWidth
                        val height = drawable.intrinsicHeight
                        if (width > 0 && height > 0) {
                            imageSize = IntSize(width, height)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clickable(onClick = onDismiss),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
        ) {
            Text(
                text = "${pagerState.currentPage + 1}/${fullUrls.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
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

private fun formatRating(value: Double): String = "%.1f".format(Locale.US, value)

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
    state: DetailsUiState,
    isInteractive: Boolean,
    onWatch: (FilmDetails) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenFilm: (Int) -> Unit,
    onPreviewImage: (Int) -> Unit,
    onPosterClick: () -> Unit,
    onBack: () -> Unit
) {
    val item = state.item ?: return
    val anime = state.animeDetails
    val context = LocalContext.current
    var showCharactersSheet by remember { mutableStateOf(false) }
    var showChronologySheet by remember { mutableStateOf(false) }

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
                    .clickable { onPosterClick() }
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

                // Top Buttons Row (Back & Share - No Circle Background)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, item.webUrl ?: "https://shikimori.io/animes/${item.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET}")
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
                    onWatch = {
                        onWatch(item)
                        onOpenUrl(item.toWatchUrl())
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
                    AnimeExpandableDescription(description = item.description)
                }
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
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.height(36.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = genreName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
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
                    onOpenFullCharacters = { showCharactersSheet = true }
                )
            }
        }

        // Separate Related Section (compact width)
        if (state.relations.isNotEmpty()) {
            item {
                HorizontalFilmsCard(
                    title = "Связанное (${state.relations.size})",
                    items = state.relations,
                    itemWidth = 104.dp,
                    onOpenFilm = onOpenFilm
                )
            }
        }

        // Separate Chronology Section
        if (state.relations.isNotEmpty()) {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AnimeChronologyCard(
                        relations = state.relations,
                        onOpenChronology = { showChronologySheet = true }
                    )
                }
            }
        }

        // Screenshots / Frames Section
        if (state.images.isNotEmpty()) {
            item {
                ImagesCard(
                    images = state.images,
                    onPreview = onPreviewImage
                )
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
            onDismiss = { showCharactersSheet = false }
        )
    }

    if (showChronologySheet) {
        AnimeChronologySheet(
            currentItem = item,
            relations = state.relations,
            animeDetails = state.animeDetails,
            onDismiss = { showChronologySheet = false },
            onOpenFilm = onOpenFilm
        )
    }
}

@Composable
private fun AnimeExpandableDescription(description: String) {
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun AnimeCharactersCard(
    roles: List<hd.kinoshka.app.data.model.ShikimoriRole>,
    onOpenFullCharacters: () -> Unit
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
                        .clickable { onOpenFullCharacters() }
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
                        modifier = Modifier.fillMaxWidth(),
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
    relations: List<FilmLinkItem>,
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
                    text = "Хронология",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Список всех частей по порядку (${relations.size + 1})",
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
    relations: List<FilmLinkItem>,
    animeDetails: hd.kinoshka.app.data.model.ShikimoriAnimeDetails?,
    onDismiss: () -> Unit,
    onOpenFilm: (Int) -> Unit
) {
    val fullTimeline = remember(currentItem, relations, animeDetails) {
        val list = mutableListOf<FilmLinkItem>()
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
        list.addAll(relations.filter { it.id > 0 })
        list.distinctBy { it.id }.sortedBy { it.year ?: it.id }
    }

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
                text = "Хронология просмотра (${fullTimeline.size})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxHeight(0.75f)
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
                                    model = item.posterUrlPreview ?: item.posterUrl,
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
        }
    }
}

@Composable
private fun AnimeFullDetailsCard(
    anime: hd.kinoshka.app.data.model.ShikimoriAnimeDetails?,
    item: FilmDetails
) {
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

            val studiosStr = anime?.studios?.mapNotNull { it.name }?.joinToString(", ")
            DetailRow("Студия", studiosStr?.takeIf { it.isNotBlank() } ?: "—")

            DetailRow("Первоисточник", formatAnimeSource(anime?.source))
            DetailRow("Длительность эпизода", item.filmLength?.let { "$it мин." } ?: "—")
            val licensorStr = anime?.licenseNameRu ?: anime?.licensors?.joinToString(", ")
            DetailRow("Лицензировано", licensorStr?.takeIf { it.isNotBlank() } ?: "—")
            DetailRow("Следующий эпизод", formatNextEpisode(anime?.nextEpisodeAt))
            DetailRow("Начало показа", formatDateRu(anime?.airedOn))
            DetailRow("Ромадзи", item.nameOriginal ?: "—")
            DetailRow("По-русски", item.nameRu ?: "—")
            DetailRow("По-английски", anime?.english?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: "—")
            DetailRow("По-японски", anime?.japanese?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: "—")
            DetailRow("Другие названия", anime?.synonyms?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: "—")
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

private fun formatDateRu(dateStr: String?): String {
    return formatRussianDate(dateStr)
}

