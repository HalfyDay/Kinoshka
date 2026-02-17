package hd.kinoshka.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
                                onWatch = {
                                    isInteractive = false
                                    onWatch(item)
                                    onOpenUrl(item.toWatchUrl())
                                },
                                showDisableAdsButton = !adGuardDnsActive,
                                onDisableAds = {
                                    openPrivateDnsWithAdGuard(context)
                                    adGuardDnsActive = isAdGuardDnsActive(context)
                                }
                            )
                        }
                    }
                    item {
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            UserProfileSummaryCard(
                                item = item,
                                profile = state.userProfile,
                                enabled = isInteractive,
                                onOpenEditor = { showProfileEditor = true }
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
                            HorizontalFilmsCard(
                                title = "Связанные фильмы",
                                items = state.relations,
                                onOpenFilm = { id ->
                                    isInteractive = false
                                    onOpenFilm(id)
                                }
                            )
                        }
                    }
                    if (state.similars.isNotEmpty()) {
                        item {
                            HorizontalFilmsCard(
                                title = "Похожие фильмы",
                                items = state.similars,
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
        AsyncImage(
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
                AsyncImage(
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
        AsyncImage(
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
        AsyncImage(
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
    onWatch: () -> Unit,
    showDisableAdsButton: Boolean,
    onDisableAds: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onWatch,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Смотреть", style = MaterialTheme.typography.titleSmall)
        }
        if (showDisableAdsButton) {
            OutlinedButton(
                onClick = onDisableAds,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
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
                Surface(
                    modifier = Modifier
                        .size(34.dp)
                        .clickable {
                            status = null
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Очистить статус",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
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

            if (item.type == "TV_SERIES") {
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

            OutlinedTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Заметка") },
                minLines = 1,
                maxLines = 2
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
    val ratingItems = listOf(
        FactEntry("Кинопоиск", item.ratingKinopoisk?.let { "${formatRating(it)} (${item.ratingKinopoiskVoteCount ?: 0})" }),
        FactEntry("IMDb", item.ratingImdb?.let { "${formatRating(it)} (${item.ratingImdbVoteCount ?: 0})" }),
        FactEntry("Критики", item.ratingFilmCritics?.let { "${formatRating(it)} (${item.ratingFilmCriticsVoteCount ?: 0})" }),
        FactEntry("Ожидание", item.ratingAwait?.let { "${formatRating(it)} (${item.ratingAwaitCount ?: 0})" }),
        FactEntry("РФ критики", item.ratingRfCritics?.let { "${formatRating(it)} (${item.ratingRfCriticsVoteCount ?: 0})" }),
        FactEntry("Позитив", item.ratingGoodReview?.let { "${formatRating(it)}% (${item.ratingGoodReviewVoteCount ?: 0})" })
    )

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
                        .width(132.dp)
                        .clickable { onOpenFilm(linked.id) },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        AsyncImage(
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
                        .width(164.dp)
                        .clickable {
                            onPreview(previewIndex)
                        },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    AsyncImage(
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
                AsyncImage(
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

