package hd.kinoshka.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.repo.FilmsRepository

@Composable
fun DetailsScreen(
    film: FilmItem,
    repository: FilmsRepository,
    onBack: () -> Unit,
    onWatch: () -> Unit,
) {
    var details by remember { mutableStateOf<FilmDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(film.kinopoiskId) {
        try {
            details = repository.details(film.kinopoiskId)
        } catch (t: Throwable) {
            error = t.message ?: "Не удалось загрузить описание"
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "← Назад",
                color = Color(0xFF9F9FA8),
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .padding(4.dp),
            )
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(
                    modifier = Modifier
                        .width(210.dp)
                        .height(300.dp)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    hd.kinoshka.app.ui.common.KinoRemoteImage(
                        model = details?.posterUrl ?: film.posterUrlPreview,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = details?.nameRu ?: film.nameRu ?: film.nameOriginal ?: "Без названия",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    details?.nameOriginal?.let { original ->
                        if (original != details?.nameRu) {
                            Text(original, color = Color(0xFF8E8E96), fontSize = 14.sp)
                        }
                    }
                    val rating = details?.ratingKinopoisk ?: film.ratingKinopoisk
                    rating?.let {
                        Text(
                            "Кинопоиск: ${String.format("%.1f", it)}",
                            color = Color(0xFFFFD54F),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    val year = details?.year ?: film.year
                    val genres = (details?.genres ?: film.genres).mapNotNull { it.genre }
                    val countries = (details?.countries ?: film.countries).mapNotNull { it.country }
                    val meta = buildList {
                        year?.let { add(it.toString()) }
                        if (countries.isNotEmpty()) add(countries.joinToString(", "))
                        if (genres.isNotEmpty()) add(genres.joinToString(", "))
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" • "), color = Color(0xFFB9B9C0), fontSize = 13.sp)
                    }
                    details?.slogan?.let { slogan ->
                        Text("«$slogan»", color = Color(0xFF8E8E96), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onWatch) { Text("▶  Смотреть") }
                }
            }

            Spacer(Modifier.height(20.dp))

            when {
                error != null -> Text("Ошибка: $error", color = Color(0xFFFF7B72), fontSize = 13.sp)
                details == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val description = details?.description
                    if (description.isNullOrBlank()) {
                        Text(
                            "Описание недоступно",
                            color = Color(0xFF8E8E96),
                            fontSize = 14.sp,
                        )
                    } else {
                        // Локальная копия: description объявлен в другом модуле (shared).
                        val filmDescription = description
                        Text(
                            filmDescription,
                            color = Color(0xFFDDDDDE),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                    details?.shortDescription?.let { short ->
                        Spacer(Modifier.height(8.dp))
                        Text(short, color = Color(0xFF8E8E96), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
