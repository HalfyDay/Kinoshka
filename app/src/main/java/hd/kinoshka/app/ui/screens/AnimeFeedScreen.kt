package hd.kinoshka.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.model.ShikimoriTopic
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeFeedScreen(
    topics: List<ShikimoriTopic>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Лента релизов и новостей", fontWeight = FontWeight.Bold) },
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(topics, key = { it.id }) { topic ->
                        TopicFeedCard(
                            topic = topic,
                            onOpenAnime = onOpenAnime,
                            onOpenLink = { url -> openExternalLink(context, url) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicFeedCard(
    topic: ShikimoriTopic,
    onOpenAnime: (Int) -> Unit,
    onOpenLink: (String) -> Unit
) {
    val linked = topic.linked
    // Ссылки на аниме (/animes/{id}) открываем в приложении, остальное — наружу.
    val feedUriHandler = remember(onOpenAnime, onOpenLink) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val animeId = shikimoriAnimeId(uri)
                if (animeId != null) onOpenAnime(animeId) else onOpenLink(uri)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Author and Tag Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Avatar: Shikimori returns a RELATIVE path (e.g. /system/users/...). Prefix it
                    // with the host so Coil can actually load it (previously it always fell through
                    // to the error placeholder).
                    val avatarUrl = topic.user?.avatar?.let { if (it.startsWith("/")) "https://shikimori.io$it" else it }
                        ?: topic.user?.image?.getUrl(hd.kinoshka.app.data.model.ShikimoriImageQuality.ICON)
                    KinoshkaAsyncImage(
                        model = avatarUrl,
                        contentDescription = topic.user?.nickname,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                    )
                    Column {
                        Text(
                            text = topic.user?.nickname ?: "Shikimori",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // Meta line: comment count + created date. Both are already parsed on the
                        // model; this just surfaces them (previously discarded entirely).
                        val meta = buildList {
                            topic.commentsCount.takeIf { it > 0 }?.let { add("$it комм.") }
                            topic.createdAt?.let { formatDateShort(it) }?.let { add(it) }
                        }.joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (linked != null) "Релиз" else "Новость",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Topic Title
            Text(
                text = topic.topicTitle ?: "Без заголовка",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

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
            if (richBody.text.isNotBlank()) {
                var bodyExpanded by remember(topic.id) { mutableStateOf(false) }
                var bodyOverflowed by remember(topic.id) { mutableStateOf(false) }
                Text(
                    text = richBody.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (bodyExpanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { bodyOverflowed = it.didOverflowHeight }
                )
                if (bodyExpanded) {
                    Text(
                        text = "Свернуть",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { bodyExpanded = false }
                    )
                } else if (bodyOverflowed) {
                    Text(
                        text = "Читать далее",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { bodyExpanded = true }
                    )
                }
            }
            if (inlineImages.isNotEmpty()) {
                // Как Кадры на странице тайтла: каждый элемент на всю ширину карточки 16:9.
                val galleryState = rememberLazyListState()
                LazyRow(
                    state = galleryState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(inlineImages, key = { it }) { imageUrl ->
                        KinoshkaAsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onOpenLink(imageUrl) },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                if (inlineImages.size > 1) {
                    val galleryPage by remember {
                        derivedStateOf { galleryState.firstVisibleItemIndex.coerceIn(0, inlineImages.lastIndex) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        inlineImages.forEachIndexed { index, _ ->
                            val isCurrent = index == galleryPage
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
            val videos = remember(topic.id, topic.htmlBody, topic.body, topic.htmlFooter) {
                extractTopicVideos(topic.htmlBody, topic.body, topic.htmlFooter)
            }
            videos.forEach { video ->
                TopicVideoCard(video = video, onClick = { onOpenLink(video.url) })
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
        }
        }
    }
}

/** Видео-приложение к посту: превью + переход наружу по тапу. */
private data class TopicVideo(
    val url: String,
    val youtubeId: String?,
    val label: String
)

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
 * Shikimori-ID аниме из ссылки вида /animes/21-... (любое зеркало shikimori).
 * Персонажи/манга/люди — null, у приложения для них нет экранов.
 */
private fun shikimoriAnimeId(url: String): Int? {
    if (!url.contains("shikimori", ignoreCase = true)) return null
    return Regex("""/animes/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
}

private fun openExternalLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
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

private data class RichTopicText(val text: AnnotatedString, val hasLinks: Boolean)

/**
 * Текст поста с кликабельными ссылками: `<a href>` из html_body (+ `[url]` из BBCode
 * как фолбэк). Собирается поблочно — спаны ссылок не сдвигаются пост-правками.
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
    // ￾ — technical paragraph separator, never occurs in real posts.
    val blocks: List<AnnotatedString> = if (!htmlBody.isNullOrBlank()) {
        val t = htmlBody
            // <br class="br"> — штатная разметка shikimori, голый <br> почти не встречается.
            .replace(Regex("(?i)<br\\b[^>]*>"), "\n")
            .replace(Regex("(?i)<hr\\b[^>]*>"), "￾")
            .replace(Regex("(?i)</?(p|div|li|ul|ol|blockquote|tr|h\\d)[^>]*>"), "￾")
        val linkRe = Regex(
            """<a\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        t.split("￾").map { block ->
            buildAnnotatedString {
                var last = 0
                linkRe.findAll(block).forEach { m ->
                    val inner = m.groupValues[2]
                    val label = cleanHtmlInline(inner)
                    append(cleanHtmlInline(block.substring(last, m.range.first)))
                    // Обёртка картинки/видео (<a><img></a>): медиа уже показано
                    // карточками, голый URL в тексте не нужен.
                    val mediaWrapper = label.isBlank() && inner.contains("<img", ignoreCase = true)
                    if (!mediaWrapper) {
                        appendLink(absShikiUrl(m.groupValues[1]), label)
                    }
                    last = m.range.last + 1
                }
                append(cleanHtmlInline(block.substring(last)))
            }
        }
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
            .replace(Regex("""\[/?quote[^\]]*\]""", RegexOption.IGNORE_CASE), "￾")
        val linkRe = Regex(
            """\[url(?:=([^\]]+))?\](.*?)\[/url\]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        t.split("￾").map { block ->
            buildAnnotatedString {
                var last = 0
                linkRe.findAll(block).forEach { m ->
                    append(cleanBbInline(block.substring(last, m.range.first)))
                    val url = absShikiUrl(m.groupValues[1].ifBlank { m.groupValues[2] })
                    val label = cleanBbInline(m.groupValues[2])
                    // [url] вокруг [img]: картинка уже в галерее.
                    if (!(label.isBlank() && galleryUrls.contains(url))) {
                        appendLink(url, label)
                    }
                    last = m.range.last + 1
                }
                append(cleanBbInline(block.substring(last)))
            }
        }
    } else {
        emptyList()
    }
    val text = buildAnnotatedString {
        blocks.filter { it.text.isNotBlank() }.forEachIndexed { index, block ->
            if (index > 0) append("\n\n")
            append(block)
        }
    }
    return RichTopicText(text, hasLinks)
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
    fun add(rawUrl: String?) {
        val url = absShikiUrl(rawUrl) ?: return
        val youtubeId = youtubeVideoId(url)
        val key = if (youtubeId != null) "yt:$youtubeId" else url
        if (found.containsKey(key)) return
        val host = runCatching { Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")?.removePrefix("m.")
        found[key] = TopicVideo(
            url = url,
            youtubeId = youtubeId,
            label = if (youtubeId != null) "YouTube" else host ?: "Видео"
        )
    }
    Regex("""\[video\]([^\[]+)\[/video\]""", RegexOption.IGNORE_CASE)
        .findAll(src).forEach { add(it.groupValues[1].trim()) }
    Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(src).forEach { add(it.groupValues[1]) }
    if (!htmlBody.isNullOrBlank()) {
        val videoBlocks = Regex(
            """<div[^>]*class="[^"]*\bb-video\b[^"]*"[^>]*>.*?</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(htmlBody)
        videoBlocks.forEach { block ->
            Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(block.value).forEach { add(it.groupValues[1]) }
        }
    }
    return found.values.toList()
}

/** Formats a Shikimori ISO created_at (UTC) as a short local date "d MMM". */
private fun formatDateShort(iso: String): String? = runCatching {
    val date = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val normalized = if (iso.endsWith("Z") || iso.contains("+")) iso else iso + "Z"
        java.util.Date.from(java.time.OffsetDateTime.parse(normalized).toInstant())
    } else {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(iso.substringBefore('.'))
    } ?: return null
    java.text.SimpleDateFormat("d MMM", java.util.Locale.forLanguageTag("ru")).format(date)
}.getOrNull()
