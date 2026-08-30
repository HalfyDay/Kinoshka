package hd.kinoshka.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.repo.FilmsRepository
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(repository: FilmsRepository, onOpen: (FilmItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var films by remember { mutableStateOf<List<FilmItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nextPage by remember { mutableStateOf(2) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    fun load(reset: Boolean, page: Int) {
        scope.launch {
            loading = true
            error = null
            try {
                val cleanQuery = query.trim()
                val loaded = if (cleanQuery.isEmpty()) {
                    repository.popular(page = page)
                } else {
                    repository.search(query = cleanQuery, page = page)
                }
                films = if (reset) loaded else (films + loaded).distinctBy { it.kinopoiskId }
                nextPage = page + 1
            } catch (t: Throwable) {
                error = t.message ?: "Ошибка сети"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(reset = true, page = 1) }

    // Бесконечный скролл: приблизились к концу списка — догружаем следующую страницу.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (!loading && films.isNotEmpty() && lastVisible >= films.size - 12) {
                    load(reset = false, page = nextPage)
                }
            }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Кино",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Поиск фильмов и сериалов", color = Color(0xFF6E6E76)) },
                singleLine = true,
            )
            Button(onClick = { load(reset = true, page = 1) }) { Text("Найти") }
        }

        error?.let { message ->
            Text(
                "Ошибка: $message",
                color = Color(0xFFFF7B72),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(films, key = { it.kinopoiskId }) { film ->
                FilmCard(film = film, onClick = { onOpen(film) })
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmCard(film: FilmItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            hd.kinoshka.app.ui.common.KinoRemoteImage(
                model = film.posterUrlPreview,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            film.ratingKinopoisk?.let { rating ->
                hd.kinoshka.app.ui.common.KinoRatingBadge(
                    rating = rating,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
        Text(
            text = film.nameRu ?: film.nameOriginal ?: "Без названия",
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        film.year?.let { year ->
            Text(color = Color(0xFF8E8E96), fontSize = 11.sp, text = year.toString())
        }
    }
}
