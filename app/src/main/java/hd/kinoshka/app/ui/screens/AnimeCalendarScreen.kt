package hd.kinoshka.app.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.model.ShikimoriCalendarItem
import hd.kinoshka.app.ui.components.KinoshkaAsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class DayCalendarGroup(
    val dayKey: String,
    val title: String,
    val items: List<ShikimoriCalendarItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeCalendarScreen(
    calendarItems: List<ShikimoriCalendarItem>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit
) {
    val groupedDays = remember(calendarItems) {
        groupCalendarItemsByDay(calendarItems)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Календарь релизов", fontWeight = FontWeight.Bold) },
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
            if (calendarItems.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (loading) "Загрузка расписания..." else "Расписание пока пусто",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(groupedDays) { dayGroup ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = dayGroup.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(dayGroup.items) { item ->
                                    HorizontalCalendarCard(
                                        item = item,
                                        onClick = { item.anime?.id?.let { id -> onOpenAnime(id) } }
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
private fun HorizontalCalendarCard(
    item: ShikimoriCalendarItem,
    onClick: () -> Unit
) {
    val anime = item.anime ?: return
    val timeStr = formatReleaseExactTime(item.nextEpisodeAt)

    Column(
        modifier = Modifier
            .width(112.dp)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            KinoshkaAsyncImage(
                model = anime.posterUrl,
                contentDescription = anime.displayTitle,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Top gradient overlay for badge contrast
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Episode Badge (Top Left)
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, bottomEnd = 12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text(
                    text = item.nextEpisode?.let { "$it эп." } ?: "Новый",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // Time Badge (Top Right)
            if (timeStr != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                ) {
                    Text(
                        text = timeStr,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        Text(
            text = anime.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun groupCalendarItemsByDay(items: List<ShikimoriCalendarItem>): List<DayCalendarGroup> {
    val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val displayDateFormat = SimpleDateFormat("d MMMM", Locale("ru"))
    val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale("ru"))

    val now = Date()
    val todayKey = dayKeyFormat.format(now)
    val tomorrowKey = dayKeyFormat.format(Date(now.time + 86400000L))

    val groupedMap = LinkedHashMap<String, MutableList<ShikimoriCalendarItem>>()

    for (item in items) {
        val dateStr = item.nextEpisodeAt
        val date = if (dateStr != null) {
            runCatching { utcFormat.parse(dateStr) }.getOrNull()
        } else null

        val key = if (date != null) dayKeyFormat.format(date) else "unknown"
        groupedMap.getOrPut(key) { mutableListOf() }.add(item)
    }

    return groupedMap.map { (key, itemList) ->
        val sortedItems = itemList.sortedBy { item ->
            item.nextEpisodeAt?.let { str ->
                runCatching { utcFormat.parse(str)?.time }.getOrNull()
            } ?: Long.MAX_VALUE
        }

        val title = when (key) {
            todayKey -> {
                val sampleDate = sortedItems.firstOrNull()?.nextEpisodeAt?.let { runCatching { utcFormat.parse(it) }.getOrNull() } ?: now
                "Сегодня, ${displayDateFormat.format(sampleDate)}"
            }
            tomorrowKey -> {
                val sampleDate = sortedItems.firstOrNull()?.nextEpisodeAt?.let { runCatching { utcFormat.parse(it) }.getOrNull() } ?: Date(now.time + 86400000L)
                "Завтра, ${displayDateFormat.format(sampleDate)}"
            }
            "unknown" -> "Скоро в эфире"
            else -> {
                val sampleDate = sortedItems.firstOrNull()?.nextEpisodeAt?.let { runCatching { utcFormat.parse(it) }.getOrNull() }
                if (sampleDate != null) {
                    val dayOfWeek = dayOfWeekFormat.format(sampleDate).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
                    "$dayOfWeek, ${displayDateFormat.format(sampleDate)}"
                } else {
                    key
                }
            }
        }
        DayCalendarGroup(dayKey = key, title = title, items = sortedItems)
    }.sortedBy { group ->
        if (group.dayKey == "unknown") "9999-99-99" else group.dayKey
    }
}

private fun formatReleaseExactTime(isoDate: String?): String? {
    if (isoDate == null) return null
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = sdf.parse(isoDate) ?: return null
        val outFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        outFormat.format(date)
    } catch (e: Exception) {
        null
    }
}
