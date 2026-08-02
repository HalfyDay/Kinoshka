package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
                        Icons.Default.List,
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
                    items(topics) { topic ->
                        TopicFeedCard(topic = topic, onClick = {
                            topic.linked?.id?.let { id -> onOpenAnime(id) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicFeedCard(
    topic: ShikimoriTopic,
    onClick: () -> Unit
) {
    val linked = topic.linked

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
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

            // Body preview + inline hero image. htmlBody carries the real content (text + embedded
            // screenshots / video links); previously it was fetched and thrown away.
            val bodyPreview = topic.body?.takeIf { it.isNotBlank() } ?: stripHtml(topic.htmlBody)
            if (!bodyPreview.isNullOrBlank()) {
                Text(
                    text = bodyPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val heroImage = firstInlineImage(topic.htmlBody)
            if (heroImage != null) {
                KinoshkaAsyncImage(
                    model = heroImage,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            // Distinct Linked Anime Box
            if (linked != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onClick),
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

/** Strips HTML/BBCode tags from a Shikimori html_body, returning plain preview text. */
private fun stripHtml(html: String?): String? {
    if (html.isNullOrBlank()) return null
    return html
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\[/?[^]]+]"), " ")
        .replace(Regex("&nbsp;|&amp;|&lt;|&gt;|&quot;|&#39;"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
}

/**
 * Extracts the first inline image URL from a Shikimori html_body. Handles <img src="..."> and
 * BBCode [img]...[/img]; prefixes relative ('/...') paths with the shikimori host.
 */
private fun firstInlineImage(html: String?): String? {
    if (html.isNullOrBlank()) return null
    val raw = runCatching {
        // <img src="/system/..."> or [img]/system/...[/img]
        Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""\[img\]([^\[]+)\[/img\]""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    }.getOrNull() ?: return null
    if (raw.isBlank()) return null
    return if (raw.startsWith("/")) "https://shikimori.io$raw" else raw
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
    java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(date)
}.getOrNull()
