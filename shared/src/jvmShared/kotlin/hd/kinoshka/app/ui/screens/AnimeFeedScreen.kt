package hd.kinoshka.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.feed.YouTubeStreamResolver
import hd.kinoshka.app.data.model.FilmImageItem
import hd.kinoshka.app.data.model.ShikimoriComment
import hd.kinoshka.app.data.model.ShikimoriTopic
import hd.kinoshka.app.data.model.ShikimoriWhoami
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeFeedScreen(
    topics: List<ShikimoriTopic>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    /** Подгрузка комментариев поста; null — раздел комментариев скрыт. */
    loadComments: (suspend (Int) -> List<ShikimoriComment>)? = null,
    /** In-app воспроизведение YouTube-видео (как трейлеры тайтлов); null — открывать в браузере. */
    onPlayVideoStream: ((url: String, headers: Map<String, String>, title: String) -> Unit)? = null,
    /** Поиск по студии из ссылок /animes/studio/{id}. */
    onOpenStudio: (Int, String) -> Unit = { _, _ -> },
    /**
     * Скролл ленты: по умолчанию живёт в rememberSaveable, поэтому уход в детали/
     * поиск студии и возврат Назад восстанавливают позицию (запись навигации
     * остаётся в стеке). Десктоп без стека передаёт свой долгоживущий стейт.
     */
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
) {
    val platformActions = hd.kinoshka.app.ui.platform.rememberKinoPlatformActions()
    // Просмотрщик на уровне экрана: ImagesViewerDialog — на весь экран,
    // внутри карточки он ужался бы до её размеров.
    var galleryPreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var openCharacterId by remember { mutableStateOf<Int?>(null) }
    Scaffold(
        topBar = {
            // Просмотрщик кадров — на весь экран: шапка скрывается вместе с контентом.
            if (galleryPreview == null) {
                TopAppBar(
                    title = { Text("Новости", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (topics.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (loading) "Загрузка новостной ленты..." else "Лента пока пуста",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(topics, key = { it.id }) { topic ->
                        TopicFeedCard(
                            topic = topic,
                            onOpenAnime = onOpenAnime,
                            onOpenLink = { url -> platformActions.openInBrowser(url) },
                            loadComments = loadComments,
                            onPlayVideoStream = onPlayVideoStream,
                            onPreviewImage = { images, index -> galleryPreview = images to index },
                            onOpenCharacter = { openCharacterId = it },
                            onOpenStudio = onOpenStudio
                        )
                    }
                }
            }
            galleryPreview?.let { (images, index) ->
                ImagesViewerDialog(
                    images = images.map { FilmImageItem(imageUrl = it, previewUrl = it) },
                    startIndex = index.coerceIn(images.indices),
                    onDismiss = { galleryPreview = null }
                )
            }
            openCharacterId?.let { characterId ->
                CharacterDetailsSheet(
                    characterId = characterId,
                    // Sheet отдаёт kinopoisk-id (со смещением), ленте нужен shikimori-id.
                    onOpenFilm = { filmId ->
                        openCharacterId = null
                        onOpenAnime(filmId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
                    },
                    onDismiss = { openCharacterId = null }
                )
            }
        }
    }
}

/**
 * Отдельный пост из карусели новостей: та же карточка, но тело, видео
 * и комментарии раскрыты сразу. Топик берётся из уже загруженной ленты.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeTopicScreen(
    topic: ShikimoriTopic?,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    loadComments: (suspend (Int) -> List<ShikimoriComment>)? = null,
    onPlayVideoStream: ((url: String, headers: Map<String, String>, title: String) -> Unit)? = null,
    /** Поиск по студии из ссылок /animes/studio/{id}. */
    onOpenStudio: (Int, String) -> Unit = { _, _ -> }
) {
    val platformActions = hd.kinoshka.app.ui.platform.rememberKinoPlatformActions()
    var galleryPreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var openCharacterId by remember { mutableStateOf<Int?>(null) }
    Scaffold(
        topBar = {
            // Просмотрщик кадров — на весь экран: шапка скрывается вместе с контентом.
            if (galleryPreview == null) {
                TopAppBar(
                    title = { Text("Новость", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (topic == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Новость не найдена",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    TopicFeedCard(
                        topic = topic,
                        onOpenAnime = onOpenAnime,
                        onOpenLink = { url -> platformActions.openInBrowser(url) },
                        loadComments = loadComments,
                        onPlayVideoStream = onPlayVideoStream,
                        onPreviewImage = { images, index -> galleryPreview = images to index },
                        onOpenCharacter = { openCharacterId = it },
                        onOpenStudio = onOpenStudio,
                        startExpanded = true,
                        startCommentsExpanded = true
                    )
                }
            }
            galleryPreview?.let { (images, index) ->
                ImagesViewerDialog(
                    images = images.map { FilmImageItem(imageUrl = it, previewUrl = it) },
                    startIndex = index.coerceIn(images.indices),
                    onDismiss = { galleryPreview = null }
                )
            }
            openCharacterId?.let { characterId ->
                CharacterDetailsSheet(
                    characterId = characterId,
                    // Sheet отдаёт kinopoisk-id (со смещением), ленте нужен shikimori-id.
                    onOpenFilm = { filmId ->
                        openCharacterId = null
                        onOpenAnime(filmId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
                    },
                    onDismiss = { openCharacterId = null }
                )
            }
        }
    }
}

/**
 * Тело поста как описание тайтла: анимация высоты + затемнение снизу.
 * Свёрнуто — плоский текст (порядок абзацев и цитат как в полном виде,
 * ничего не прыгает при раскрытии, затемнение всегда при переполнении).
 * Развёрнуто — styled-блоки, цитаты с полосой. Тогглится тапом по посту.
 */
@Composable
private fun TopicBlocks(
    rich: RichTopicText,
    expanded: Boolean,
    textStyle: TextStyle,
    gap: androidx.compose.ui.unit.Dp = 10.dp,
    collapsedLines: Int = 5
) {
    var overflowed by remember(rich) { mutableStateOf(false) }
    // ElevatedCard по умолчанию красится в surfaceContainerLow.
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (overflowed) Modifier.animateContentSize() else Modifier)
    ) {
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                rich.blocks.forEach { block ->
                    when (block) {
                        // Описание — как обычный текст, без полосы цитаты.
                        is TopicBlock.Text -> {
                            Text(
                                text = block.text,
                                style = textStyle,
                                color = textColor
                            )
                        }
                        is TopicBlock.Description -> {
                            Text(
                                text = block.text,
                                style = textStyle,
                                color = textColor
                            )
                        }
                        is TopicBlock.Quote -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.height(IntrinsicSize.Min)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                )
                                Text(
                                    text = block.text,
                                    style = textStyle,
                                    color = textColor,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                text = rich.text,
                style = textStyle,
                color = textColor,
                maxLines = collapsedLines,
                // Без многоточия: обрез + затемнение, как просили.
                overflow = TextOverflow.Clip,
                onTextLayout = { overflowed = it.didOverflowHeight }
            )
        }
        if (!expanded && overflowed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                cardColor.copy(alpha = 0.7f),
                                cardColor
                            ),
                            startY = 30f
                        )
                    )
            )
        }
    }
}

@Composable
internal fun TopicFeedCard(
    topic: ShikimoriTopic,
    onOpenAnime: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
    loadComments: (suspend (Int) -> List<ShikimoriComment>)?,
    onPlayVideoStream: ((String, Map<String, String>, String) -> Unit)?,
    onPreviewImage: (List<String>, Int) -> Unit,
    onOpenCharacter: (Int) -> Unit,
    onOpenStudio: (Int, String) -> Unit,
    /** Экран отдельного поста: тело и комментарии раскрыты сразу. */
    startExpanded: Boolean = false,
    startCommentsExpanded: Boolean = false
) {
    val linked = topic.linked
    val platformActions = hd.kinoshka.app.ui.platform.rememberKinoPlatformActions()
    val scope = rememberCoroutineScope()
    // Тап по заголовку раскрывает пост целиком («открытие поста» без ухода с ленты).
    var bodyExpanded by remember(topic.id) { mutableStateOf(startExpanded) }
    // Видео-приложение: YouTube играет в приложении как трейлеры тайтлов,
    // остальное (sibnet/vk) — наружу в браузер.
    fun playTopicVideo(video: TopicVideo) {
        val youtubeId = video.youtubeId
        if (youtubeId != null && onPlayVideoStream != null) {
            scope.launch {
                val stream = runCatching {
                    withContext(Dispatchers.IO) { YouTubeStreamResolver.resolve(youtubeId) }
                }.getOrNull()
                if (stream == null) {
                    platformActions.showToast("Не удалось получить поток — YouTube недоступен без VPN")
                } else {
                    onPlayVideoStream(
                        stream.url,
                        stream.headers,
                        topic.topicTitle?.takeIf { it.isNotBlank() } ?: video.label
                    )
                }
            }
        } else {
            onOpenLink(video.url)
        }
    }
    // Ссылки на аниме (/animes/{id}), персонажей (/characters/{id}) и студии
    // (/animes/studio/{id}) открываем в приложении, остальное — наружу.
    val feedUriHandler = remember(onOpenAnime, onOpenCharacter, onOpenStudio, onOpenLink) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val animeId = shikimoriAnimeId(uri)
                if (animeId != null) {
                    onOpenAnime(animeId)
                    return
                }
                val characterId = shikimoriCharacterId(uri)
                if (characterId != null) {
                    onOpenCharacter(characterId)
                    return
                }
                val studio = shikimoriStudio(uri)
                if (studio != null) onOpenStudio(studio.first, studio.second) else onOpenLink(uri)
            }
        }
    }
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        CompositionLocalProvider(LocalUriHandler provides feedUriHandler) {
        // Тап по посту сворачивает/разворачивает тело. Вложенные клики
        // (галерея, видео, бокс аниме) срабатывают сами и тоггл не дёргают.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { bodyExpanded = !bodyExpanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Заголовок (тап — раскрыть/свернуть пост целиком).
            Text(
                text = topic.topicTitle?.takeIf { it.isNotBlank() } ?: "Без заголовка",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { bodyExpanded = !bodyExpanded }
            )
            // Теги под заголовком + дата в той же строке (не одна на строке).
            val tags = remember(topic.id, topic.topicTitle, topic.body) { topicTags(topic) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(tags, key = { "tag_$it" }) { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                        }
                    }
                }
                formatDateShort(topic.createdAt ?: "")?.let { date ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // Галерея считается первой: её URL нужны парсеру, чтобы не дублировать
            // ссылки-обёртки картинок/видео голым текстом.
            val inlineImages = remember(topic.id, topic.htmlBody, topic.body, topic.htmlFooter) {
                extractTopicImages(topic.htmlBody, topic.body, topic.htmlFooter)
            }
            // Текст поста со ссылками + «Читать далее», все картинки,
            // все видео и ссылки из футера (источник и т.п.).
            val richBody = remember(topic.id, topic.htmlBody, topic.body) {
                parseTopicRichText(topic.htmlBody, topic.body, linkStyle, inlineImages.toSet())
            }
            // Тело — как описание тайтла: анимация высоты + затемнение снизу.
            // Цитаты — отдельными блоками с полосой (пост тогглится тапом).
            if (richBody.blocks.isNotEmpty()) {
                TopicBlocks(
                    rich = richBody,
                    expanded = bodyExpanded,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
            val videos = remember(topic.id, topic.htmlBody, topic.body, topic.htmlFooter) {
                extractTopicVideos(topic.htmlBody, topic.body, topic.htmlFooter)
            }
            // Все медиа поста — одна карусель: картинки, затем видео.
            // Каждый элемент на всю ширину карточки 16:9.
            val media = remember(inlineImages, videos) {
                buildList {
                    inlineImages.forEach { add(PostMedia.Image(it)) }
                    videos.forEach { add(PostMedia.Video(it)) }
                }
            }
            if (media.isNotEmpty()) {
                val mediaState = rememberLazyListState()
                LazyRow(
                    state = mediaState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        media,
                        key = {
                            when (it) {
                                is PostMedia.Image -> "img_${it.url}"
                                is PostMedia.Video -> "vid_${it.video.url}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is PostMedia.Image -> KinoshkaAsyncImage(
                                model = item.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onPreviewImage(inlineImages, inlineImages.indexOf(item.url)) },
                                contentScale = ContentScale.Crop
                            )
                            // Видео в строке: fillMaxWidth не работает (бесконечные
                            // констрейнты), ширину задаёт fillParentMaxWidth-обёртка.
                            is PostMedia.Video -> Box(modifier = Modifier.fillParentMaxWidth()) {
                                TopicVideoCard(
                                    video = item.video,
                                    onClick = { playTopicVideo(item.video) }
                                )
                            }
                        }
                    }
                }
                if (media.size > 1) {
                    val mediaPage by remember {
                        derivedStateOf { mediaState.firstVisibleItemIndex.coerceIn(0, media.lastIndex) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        media.forEachIndexed { index, _ ->
                            val isCurrent = index == mediaPage
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (isCurrent) 7.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    )
                            )
                        }
                    }
                }
            }
            val richFooter = remember(topic.id, topic.htmlFooter) {
                parseTopicRichText(topic.htmlFooter, null, linkStyle, inlineImages.toSet())
            }
            if (richFooter.hasLinks) {
                Text(
                    text = richFooter.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Distinct Linked Anime Box
            if (linked != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenAnime(linked.id) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        KinoshkaAsyncImage(
                            model = linked.posterUrl,
                            contentDescription = linked.displayTitle,
                            modifier = Modifier
                                .width(48.dp)
                                .height(68.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = linked.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Открыть страницу аниме",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
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

            // Комментарии поста: раскрываются по тапу, грузятся один раз.
            // С экрана отдельного поста — раскрыты и грузятся сразу.
            // Контейнер гасит тап: комментарии — не часть поста, тоггл не дёргают.
            if (topic.commentsCount > 0 && loadComments != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                ) {
                    var commentsExpanded by remember(topic.id) { mutableStateOf(startCommentsExpanded) }
                    var comments by remember(topic.id) { mutableStateOf<List<ShikimoriComment>?>(null) }
                    var commentsLoading by remember(topic.id) { mutableStateOf(false) }
                    var commentsFailed by remember(topic.id) { mutableStateOf(false) }
                    // Гейт без commentsLoading: его установка внутри эффекта давала
                    // рекомпозицию, эффект уходил из композиции и сам себя отменял
                    // (в логе — HTTP Canceled на каждый тап).
                    if (commentsExpanded && comments == null) {
                        LaunchedEffect(topic.id) {
                            commentsLoading = true
                            commentsFailed = false
                            comments = runCatching { loadComments(topic.id) }
                                .onFailure { commentsFailed = true }
                                .getOrDefault(emptyList())
                            commentsLoading = false
                        }
                    }
                    // Приглушённая контурная кнопка вместо залитой: не спорит с контентом.
                    OutlinedButton(
                        onClick = { commentsExpanded = !commentsExpanded },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Forum,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (commentsExpanded) "Скрыть комментарии"
                                else "Комментарии • ${topic.commentsCount}"
                        )
                    }
                    if (commentsExpanded) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(12.dp)
                            ) {
                                when {
                                    commentsLoading -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = "Загрузка комментариев...",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    commentsFailed -> Text(
                                        text = "Не удалось загрузить комментарии",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    comments.isNullOrEmpty() -> Text(
                                        text = "Пока нет комментариев",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        comments!!.forEach { comment ->
                                            TopicCommentRow(comment = comment, linkStyle = linkStyle)
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
    }
}

@Composable
private fun TopicCommentRow(comment: ShikimoriComment, linkStyle: SpanStyle) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KinoshkaAsyncImage(
            model = shikiAvatarUrl(comment.user),
            contentDescription = comment.user?.nickname,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = comment.user?.nickname ?: "Shikimori",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                formatDateShort(comment.createdAt ?: "")?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val body = remember(comment.id, comment.htmlBody, comment.body) {
                parseTopicRichText(comment.htmlBody, comment.body, linkStyle)
            }
            if (body.blocks.isNotEmpty()) {
                TopicBlocks(
                    rich = body,
                    expanded = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    gap = 6.dp
                )
            }
        }
    }
}

/**
 * Аватар shikimori-юзера: API возвращает относительный путь
 * (напр. /system/users/...), Coil грузит только абсолютный.
 */
private fun shikiAvatarUrl(user: ShikimoriWhoami?): String? =
    user?.avatar?.let { if (it.startsWith("/")) "https://shikimori.io$it" else it }
        ?: user?.image?.getUrl(hd.kinoshka.app.data.model.ShikimoriImageQuality.ICON)

/** Видео-приложение к посту: превью + переход наружу по тапу. */
private data class TopicVideo(
    val url: String,
    val youtubeId: String?,
    val label: String
)

/** Элемент единой карусели медиа поста: картинки, затем видео. */
private sealed interface PostMedia {
    data class Image(val url: String) : PostMedia
    data class Video(val video: TopicVideo) : PostMedia
}

@Composable
private fun TopicVideoCard(video: TopicVideo, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Black,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center
        ) {
            if (video.youtubeId != null) {
                KinoshkaAsyncImage(
                    model = "https://img.youtube.com/vi/${video.youtubeId}/hqdefault.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = video.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Смотреть",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            // YouTube из РФ без VPN не резолвится — предупреждаем до тапа.
            // Бейдж нейтральный (не красный), справа.
            if (video.youtubeId != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "VPN",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = video.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 8.dp)
            )
        }
    }
}

/**
 * Теги поста: тип тайтла + статус + что случилось (трейлер/постер/премьера...).
 * Ключевые слова — из заголовка и тела (выборка 30 новостей shikimori).
 * Ничего не подошло — «Прочее».
 */
private fun topicTags(topic: ShikimoriTopic): List<String> {
    val linked = topic.linked
    val typeTag = when {
        linked == null -> "Новость"
        linked.kind?.lowercase() in setOf("manga", "manhwa", "manhua") -> "Манга"
        linked.kind?.lowercase() in setOf("novel", "light_novel") -> "Ранобэ"
        linked.kind?.lowercase() == "movie" -> "Фильм"
        else -> "Аниме"
    }
    val text = (topic.topicTitle.orEmpty() + "\n" + topic.body.orEmpty()).lowercase()
    val tags = linkedSetOf(typeTag)
    if (linked?.status == "anons" || "анонс" in text || "анонсирова" in text) tags += "Анонс"
    if ("трейлер" in text || "тизер" in text) tags += "Трейлер"
    if ("постер" in text) tags += "Постер"
    if ("премьер" in text) tags += "Премьера"
    if ("дата" in text || "дату" in text || "датой" in text || "дате" in text) tags += "Дата"
    if ("успех" in text || "кассов" in text || "млрд" in text || "собрал " in text ||
        "собрали " in text || "достижени" in text || "рекорд" in text
    ) tags += "Успех"
    if ("рекап" in text || "пересказ" in text) tags += "Рекап"
    if ("продолжени" in text || "возвращ" in text || "2-й сезон" in text || "2 сезон" in text ||
        "2-го сезона" in text || "второй сезон" in text || "вторая часть" in text ||
        "новый сезон" in text
    ) tags += "Возвращение"
    if (tags.size == 1) tags += "Прочее"
    return tags.toList()
}

/**
 * Shikimori-ID аниме из ссылки вида /animes/21-... (любое зеркало shikimori).
 * Манга/люди — null, у приложения для них нет экранов (персонажи — отдельно).
 */
private fun shikimoriAnimeId(url: String): Int? {
    if (!url.contains("shikimori", ignoreCase = true)) return null
    return Regex("""/animes/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
}

/** Shikimori-ID персонажа из ссылки вида /characters/143982-... */
private fun shikimoriCharacterId(url: String): Int? {
    if (!url.contains("shikimori", ignoreCase = true)) return null
    return Regex("""/characters/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
}

/** Студия из ссылки вида /animes/studio/103(-Slug...): id + человекочитаемое имя. */
private fun shikimoriStudio(url: String): Pair<Int, String>? {
    if (!url.contains("shikimori", ignoreCase = true)) return null
    val m = Regex("""/animes/studio/(\d+)(?:-([^/?#\s]+))?""").find(url) ?: return null
    val id = m.groupValues[1].toIntOrNull() ?: return null
    val name = m.groupValues[2].replace("-", " ").trim().takeIf { it.isNotBlank() } ?: "Студия"
    return id to name
}

/** Относительный shikimori-путь → абсолютный URL. */
private fun absShikiUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val u = raw.trim()
    return when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "https://shikimori.io$u"
        else -> u
    }
}

private fun decodeHtmlEntities(s: String): String {
    var t = s
    mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&laquo;" to "«",
        "&raquo;" to "»",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…"
    ).forEach { (entity, char) -> t = t.replace(entity, char) }
    t = Regex("&#(\\d+);").replace(t) { m ->
        m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
    }
    return t
}

/** Чистый текст из HTML-фрагмента: блочные теги → абзацы, остальное выкидывается. */
private fun cleanPlainText(fragment: String): String {
    val noTags = Regex("<[^>]+>").replace(fragment, " ")
    return decodeHtmlEntities(noTags)
        .split("\n")
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
}

/** Инлайн-чистка внутри абзаца: теги режутся, переносы строк сохраняются. */
private fun cleanHtmlInline(s: String): String {
    val noTags = Regex("<[^>]+>").replace(s, "")
    return decodeHtmlEntities(noTags).replace(Regex("[ \t\r]+"), " ")
}

/** То же + остатки BBCode-тегов (фолбэк-ветка без html_body). */
private fun cleanBbInline(s: String): String {
    val noBb = Regex("""\[/?[^\]]+\]""").replace(s, "")
    return cleanHtmlInline(noBb)
}

/** trim() для AnnotatedString: в этой версии Compose его нет — режем сабсеквенс. */
private fun AnnotatedString.trimmed(): AnnotatedString {
    val s = text
    val start = s.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return AnnotatedString("")
    val end = s.indexOfLast { !it.isWhitespace() }
    return subSequence(start, end + 1)
}

/** Блок поста: обычный текст, цитата чужих слов или синопсис-описание. */
private sealed interface TopicBlock {
    data class Text(val text: AnnotatedString) : TopicBlock
    /** Цитата с полосой: blockquote (b-quote-v2) или [quote=Автор]. */
    data class Quote(val text: AnnotatedString) : TopicBlock
    /** Синопсис из голого [quote]/div.b-quote — своим оформлением, без полосы. */
    data class Description(val text: AnnotatedString) : TopicBlock
}

private data class RichTopicText(
    val blocks: List<TopicBlock>,
    val hasLinks: Boolean
) {
    /** Плоский текст для мест без поддержки цитат (ссылки футера). */
    val text: AnnotatedString
        get() = buildAnnotatedString {
            blocks.map {
                when (it) {
                    is TopicBlock.Text -> it.text
                    is TopicBlock.Quote -> it.text
                    is TopicBlock.Description -> it.text
                }.trimmed()
            }.filter { it.text.isNotBlank() }.forEachIndexed { index, block ->
                if (index > 0) append("\n\n")
                append(block)
            }
        }
}

/**
 * Текст поста с кликабельными ссылками: `<a href>` из html_body (+ `[url]` из BBCode
 * как фолбэк). Собирается поблочно — спаны ссылок не сдвигаются пост-правками.
 * Цитаты — отдельными блоками для особого оформления; пустые/пробельные блоки
 * режутся сразу, иначе на стыке абзацев росли тройные отступы.
 * Медиа-теги `[video]`/`[img]` из текста выкидываются — они рисуются отдельными карточками.
 * Ссылки-обёртки картинок (`<a><img></a>`) в текст не дублируются: [galleryUrls] уже на экране.
 */
private fun parseTopicRichText(
    htmlBody: String?,
    body: String?,
    linkStyle: SpanStyle,
    galleryUrls: Set<String> = emptySet()
): RichTopicText {
    val linkStyles = TextLinkStyles(style = linkStyle)
    var hasLinks = false
    fun AnnotatedString.Builder.appendLink(url: String?, label: String) {
        val cleanLabel = label.ifBlank { url.orEmpty() }
        if (url == null) {
            if (cleanLabel.isNotBlank()) append(cleanLabel)
            return
        }
        hasLinks = true
        val start = length
        withStyle(linkStyle) { append(cleanLabel) }
        addLink(LinkAnnotation.Url(url, linkStyles), start, length)
    }
    val htmlLinkRe = Regex(
        """<a\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    fun parseHtmlFragment(fragment: String): AnnotatedString {
        // ￾ — наш технический разделитель: внутри фрагмента ему не место
        // (просачивался из цитат и рисовался битым квадратом). <br> внутри
        // цитат — в переводы строк (препроцессинг туда не дотягивается).
        val f = fragment
            .replace(Regex("(?i)<br\\b[^>]*>"), "\n")
            .replace("￾", "")
        return buildAnnotatedString {
            var last = 0
            htmlLinkRe.findAll(f).forEach { m ->
                val inner = m.groupValues[2]
                val label = cleanHtmlInline(inner)
                append(cleanHtmlInline(f.substring(last, m.range.first)))
                // Обёртка картинки/видео (<a><img></a>): медиа уже показано
                // карточками, голый URL в тексте не нужен.
                val mediaWrapper = label.isBlank() && inner.contains("<img", ignoreCase = true)
                if (!mediaWrapper) {
                    appendLink(absShikiUrl(m.groupValues[1]), label)
                }
                last = m.range.last + 1
            }
            append(cleanHtmlInline(f.substring(last)))
        }
    }
    val bbLinkRe = Regex(
        """\[url(?:=([^\]]+))?\](.*?)\[/url\]""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    fun parseBbFragment(fragment: String): AnnotatedString {
        // ￾ мог остаться от [video]/[img] внутри цитат — режем, иначе битый квадрат.
        val f = fragment.replace("￾", "")
        return buildAnnotatedString {
            var last = 0
            bbLinkRe.findAll(f).forEach { m ->
                append(cleanBbInline(f.substring(last, m.range.first)))
                val url = absShikiUrl(m.groupValues[1].ifBlank { m.groupValues[2] })
                val label = cleanBbInline(m.groupValues[2])
                // [url] вокруг [img]: картинка уже в галерее.
                if (!(label.isBlank() && galleryUrls.contains(url))) {
                    appendLink(url, label)
                }
                last = m.range.last + 1
            }
            append(cleanBbInline(f.substring(last)))
        }
    }
    fun MutableList<TopicBlock>.addText(fragment: AnnotatedString) {
        fragment.trimmed().takeIf { it.text.isNotBlank() }?.let { add(TopicBlock.Text(it)) }
    }
    /** bar=true — цитата с полосой, иначе синопсис-описание. */
    fun MutableList<TopicBlock>.addStyled(fragment: AnnotatedString, bar: Boolean) {
        fragment.trimmed().takeIf { it.text.isNotBlank() }?.let {
            add(if (bar) TopicBlock.Quote(it) else TopicBlock.Description(it))
        }
    }
    // ￾ — technical paragraph separator, never occurs in real posts.
    // Слоты цитат — uE000 id uE001: вынимаем цитаты ДО препроцессинга, он убивает
    // div-обёртки и детект по голому тексту уже не срабатывает (div-цитаты терялись).
    // Слоты consumятся парсером целиком и в текст никогда не попадают.
    val blocks = mutableListOf<TopicBlock>()
    if (!htmlBody.isNullOrBlank()) {
        // div.b-quote содержит вложенный div — нежадный матч встаёт на его
        // закрытии, текст уже внутри.
        val quoteRe = Regex(
            """<(div[^>]*class="[^"]*\bb-quote\b[^"]*"[^>]*|blockquote[^>]*)>(.*?)</(?:div|blockquote)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        data class QuoteSlot(val inner: String, val bar: Boolean)
        val quoteSlots = mutableListOf<QuoteSlot>()
        val slotted = quoteRe.replace(htmlBody) {
            // blockquote (b-quote-v2, чужие слова) — с полосой;
            // div.b-quote (синопсис из [quote]) — описанием без полосы.
            val bar = it.groupValues[1].startsWith("blockquote", ignoreCase = true)
            quoteSlots += QuoteSlot(it.groupValues[2], bar)
            "\ufffe\uE000${quoteSlots.lastIndex}\uE001\ufffe"
        }
        val t = slotted
            // <br class="br"> — штатная разметка shikimori, голый <br> почти не встречается.
            .replace(Regex("(?i)<br\\b[^>]*>"), "\n")
            .replace(Regex("(?i)<hr\\b[^>]*>"), "￾")
            .replace(Regex("(?i)</?(p|div|li|ul|ol|tr|h\\d)[^>]*>"), "￾")
        val slotRe = Regex("\\uE000(\\d+)\\uE001")
        fun MutableList<TopicBlock>.addChunk(chunk: String) {
            var rest = chunk
            while (true) {
                val m = slotRe.find(rest) ?: break
                addText(parseHtmlFragment(rest.substring(0, m.range.first)))
                quoteSlots.getOrNull(m.groupValues[1].toIntOrNull() ?: -1)?.let { slot ->
                    addStyled(parseHtmlFragment(slot.inner), slot.bar)
                }
                rest = rest.substring(m.range.last + 1)
            }
            addText(parseHtmlFragment(rest))
        }
        t.split("￾").forEach { blocks.addChunk(it) }
    } else if (!body.isNullOrBlank()) {
        val t = body
            .replace(
                Regex("""\[video\].*?\[/video\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                "￾"
            )
            .replace(
                Regex("""\[img\].*?\[/img\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                "￾"
            )
        val quoteRe = Regex(
            """\[quote(?:=([^\]]+))?\](.*?)\[/quote\]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        var last = 0
        quoteRe.findAll(t).forEach { m ->
            t.substring(last, m.range.first).split("￾").forEach { blocks.addText(parseBbFragment(it)) }
            // [quote=Автор] — чужие слова (полоса), голый [quote] — синопсис.
            blocks.addStyled(parseBbFragment(m.groupValues[2]), m.groupValues[1].isNotBlank())
            last = m.range.last + 1
        }
        t.substring(last).split("￾").forEach { blocks.addText(parseBbFragment(it)) }
    }
    return RichTopicText(blocks, hasLinks)
}

/**
 * ВСЕ картинки поста: `<img src>` (+ ленивый `data-src`) и BBCode `[img]`.
 * Видео/трейлеры и постеры живут в html_footer (стена вложений) — он тоже источник.
 * Превью user_images поднимаем до original (тот же файл). Смайлы, аватары из цитат
 * и ютуб-тамбы видео отсеиваются — это не контент галереи.
 */
private fun extractTopicImages(htmlBody: String?, body: String?, htmlFooter: String?): List<String> {
    if (htmlBody.isNullOrBlank() && body.isNullOrBlank() && htmlFooter.isNullOrBlank()) return emptyList()
    val src = listOfNotNull(htmlBody, body, htmlFooter).joinToString("\n")
    val raw = linkedSetOf<String>()
    Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(src).forEach { raw += it.groupValues[1] }
    Regex("""<img[^>]+data-src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(src).forEach { raw += it.groupValues[1] }
    Regex("""\[img\]([^\[]+)\[/img\]""", RegexOption.IGNORE_CASE)
        .findAll(src).forEach { raw += it.groupValues[1].trim() }
    return raw.mapNotNull { absShikiUrl(it) }
        .map { u ->
            if ("/user_images_h/preview/" in u) u.replace("/user_images_h/preview/", "/user_images_h/original/") else u
        }
        .filter { u ->
            !u.contains("smil", ignoreCase = true) &&
                !u.contains("/system/users/", ignoreCase = true) &&
                !u.contains("img.youtube.com", ignoreCase = true) &&
                !u.contains("ytimg.com", ignoreCase = true)
        }
        .distinct()
}

private fun youtubeVideoId(url: String): String? {
    return Regex(
        """(?:youtube\.com/(?:watch\?[^#\s]*?v=|embed/|shorts/|live/)|youtu\.be/)([\w-]{6,})""",
        RegexOption.IGNORE_CASE
    ).find(url)?.groupValues?.get(1)
}

/**
 * ВСЕ видео поста: BBCode `[video]`, iframe-эмбеды и рендер-блоки плеера shikimori
 * (`b-video`). Видео/трейлеры живут в html_footer (стена вложений) — он тоже источник.
 * Обычные текстовые ссылки на youtube остаются ссылками в тексте.
 */
private fun extractTopicVideos(htmlBody: String?, body: String?, htmlFooter: String?): List<TopicVideo> {
    if (htmlBody.isNullOrBlank() && body.isNullOrBlank() && htmlFooter.isNullOrBlank()) return emptyList()
    val src = listOfNotNull(htmlBody, body, htmlFooter).joinToString("\n")
    val found = linkedMapOf<String, TopicVideo>()
    fun add(rawUrl: String?, labelOverride: String? = null) {
        val url = absShikiUrl(rawUrl) ?: return
        val youtubeId = youtubeVideoId(url)
        val key = if (youtubeId != null) "yt:$youtubeId" else url
        if (found.containsKey(key)) return
        val host = runCatching {
            java.net.URI(url).host
        }.getOrNull()?.removePrefix("www.")?.removePrefix("m.")
        found[key] = TopicVideo(
            url = url,
            youtubeId = youtubeId,
            label = labelOverride?.takeIf { it.isNotBlank() }
                ?: if (youtubeId != null) "YouTube" else host ?: "Видео"
        )
    }
    Regex("""\[video\]([^\[]+)\[/video\]""", RegexOption.IGNORE_CASE)
        .findAll(src).forEach { add(it.groupValues[1].trim()) }
    Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(src).forEach { add(it.groupValues[1]) }
    // Стена вложений живёт в html_body/html_footer (b-shiki_wall): блоки плеера
    // b-video и прямые video-link якоря. Название видео — из span.name блока.
    val wallSrc = listOfNotNull(htmlBody, htmlFooter).joinToString("\n")
    if (wallSrc.isNotBlank()) {
        val videoBlocks = Regex(
            """<div[^>]*class="[^"]*\bb-video\b[^"]*"[^>]*>.*?</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(wallSrc)
        videoBlocks.forEach { block ->
            val name = Regex(
                """<span[^>]*class="[^"]*\bname\b[^"]*"[^>]*>(.*?)</span>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(block.value)?.groupValues?.get(1)?.let { cleanHtmlInline(it) }
            Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(block.value)
                .map { it.groupValues[1] }
                // data-href (embed) и href (watch) дублируют друг друга:
                // смотримое — первым, иначе в карточке сохранится embed-URL.
                .sortedBy { if ("embed" in it) 1 else 0 }
                .forEach { add(it, name) }
        }
        // Якоря плеера вне b-video блоков (другой рендер стены) — тоже видео.
        Regex(
            """<a[^>]*class="[^"]*video-link[^"]*"[^>]*href\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(wallSrc).forEach { add(it.groupValues[1]) }
    }
    return found.values.toList()
}

/** Formats a Shikimori ISO created_at (UTC) as a short local date "d MMM". */
private fun formatDateShort(iso: String): String? = runCatching {
    // minSdk 26 (O): java.time доступен всегда.
    val normalized = if (iso.endsWith("Z") || iso.contains("+")) iso else iso + "Z"
    val date = java.util.Date.from(java.time.OffsetDateTime.parse(normalized).toInstant())
    java.text.SimpleDateFormat("d MMM", java.util.Locale.forLanguageTag("ru")).format(date)
}.getOrNull()
