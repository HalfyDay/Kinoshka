package hd.kinoshka.app.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouteSelector
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.controls.components.skipTargetAt
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import hd.kinoshka.app.data.cast.CastPlayback
import hd.kinoshka.app.data.cast.CastRelayServer
import hd.kinoshka.app.data.model.ANIME_PICKER_SOURCES
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.EpisodeSkips
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.qualityRank
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.ui.theme.KinoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Вертикальная страница пульта Chromecast: открывается вместо плеера при трансляции.
 * Транспорт как на пульте (play/pause, ±серия, ±10с, громкость ТВ, перемотка, стоп),
 * пропуск опенинга, компактные Серия/Озвучка/Источник/Качество, возврат в плеер.
 */
class CastRemoteActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Как в MainActivity: edge-to-edge, иначе строка уведомлений другого цвета.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val shikimoriId = intent.getIntExtra(EXTRA_SHIKIMORI_ID, 0)
        val animeTitle = intent.getStringExtra(EXTRA_ANIME_TITLE).orEmpty()
        val initEpisode = intent.getIntExtra(EXTRA_EPISODE, 1)
        val initTranslationId = intent.getStringExtra(EXTRA_TRANSLATION_ID).orEmpty()
        val initQuality = intent.getStringExtra(EXTRA_QUALITY).orEmpty().ifBlank { "Auto" }
        val displayTitle = intent.getStringExtra(EXTRA_DISPLAY_TITLE).orEmpty().ifBlank { animeTitle }
        // Обрыв сессии (упал приёмник/сеть) показываем текстом: иначе пульт молча
        // висит в старом состоянии — именно так выглядел провал LOAD 2100 в логах.
        var notifySessionLost: (() -> Unit)? = null
        CastPlayback.init(
            this,
            this,
            onSessionStarted = { /* свежий коннект грузит текущий выбор с нуля (см. экран) */ },
            onSessionEnded = { runOnUiThread { notifySessionLost?.invoke() } }
        )
        setContent {
            KinoTheme {
                CastRemoteScreen(
                    shikimoriId = shikimoriId,
                    animeTitle = animeTitle,
                    displayTitle = displayTitle,
                    initEpisode = initEpisode,
                    initTranslationId = initTranslationId,
                    initQuality = initQuality,
                    onBack = { finish() },
                    onSessionLostHook = { notifySessionLost = it }
                )
            }
        }
    }

    override fun onDestroy() {
        // Снимаем только свой слушатель (плеер свой уже снял или снимет —
        // чужой не трогаем). Реле гасим только если сессии нет: при живом
        // ТВ (уход Кнопкой назад) оно продолжает лить сегменты приёмнику.
        CastPlayback.release(this)
        if (!CastPlayback.hasSession()) {
            CastRelayServer.stopInstance()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SHIKIMORI_ID = "remote_shikimori_id"
        const val EXTRA_ANIME_TITLE = "remote_anime_title"
        const val EXTRA_EPISODE = "remote_episode"
        const val EXTRA_TRANSLATION_ID = "remote_translation_id"
        const val EXTRA_QUALITY = "remote_quality"
        const val EXTRA_DISPLAY_TITLE = "remote_display_title"
    }
}

/** Лучший конкретный ранг (без Auto), null — рангов нет. */
private fun bestRemoteRung(qualities: Map<String, String>): String? =
    qualities.keys.maxByOrNull { qualityRank(it) }

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Строка компактного селектора: подпись, значение, шеврон. */
@Composable
private fun RemoteSelectorRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Общий диалог выбора для компактных селекторов: id + подпись + подстрока. */
@Composable
private fun RemoteOptionDialog(
    title: String,
    options: List<Triple<String, String, String?>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(options, key = { it.first }) { (id, label, sub) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(id) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = id == selectedId,
                            onClick = { onSelect(id) }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (id == selectedId) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            sub?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastRemoteScreen(
    shikimoriId: Int,
    animeTitle: String,
    displayTitle: String,
    initEpisode: Int,
    initTranslationId: String,
    initQuality: String,
    onBack: () -> Unit,
    onSessionLostHook: (((() -> Unit)?) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaRouter = remember { MediaRouter.getInstance(context) }
    val castSelector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .build()
    }
    fun openChooser() {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }
        (context as? FragmentActivity)?.let { activity ->
            MediaRouteChooserDialogFragment().apply {
                routeSelector = castSelector
            }.show(activity.supportFragmentManager, "cast_chooser")
        }
    }

    // ---- транспорт ТВ (опрос) ----
    var casting by remember { mutableStateOf(CastPlayback.isCasting) }
    var remotePosMs by remember { mutableStateOf(0L) }
    var remoteDurMs by remember { mutableStateOf(0L) }
    var remotePlaying by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var tvVolume by remember { mutableStateOf(0) }
    var tvVolumeMax by remember { mutableStateOf(15) }
    var volScrub by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            casting = CastPlayback.isCasting
            try {
                CastPlayback.remoteClient()?.let { c ->
                    remotePosMs = c.approximateStreamPosition
                    remoteDurMs = c.streamDuration
                    remotePlaying = c.isPlaying
                }
                deviceName = CastPlayback.deviceName()
                mediaRouter.selectedRoute?.let { r ->
                    tvVolume = r.volume
                    tvVolumeMax = r.volumeMax.coerceAtLeast(1)
                }
            } catch (_: Exception) {
            }
            delay(1000)
        }
    }

    // ---- каталог и выбор ----
    var catalog by remember { mutableStateOf<Map<AnimeSourceType, List<FlatTranslation>>>(emptyMap()) }
    var source by remember { mutableStateOf<AnimeSourceType?>(null) }
    var translationId by remember { mutableStateOf(initTranslationId) }
    var episode by remember { mutableStateOf(initEpisode) }
    var quality by remember { mutableStateOf(initQuality) }
    var stream by remember { mutableStateOf<AnimeMediaStream?>(null) }
    var skips by remember { mutableStateOf<EpisodeSkips?>(null) }
    var catalogBusy by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var openDialog by remember { mutableStateOf<String?>(null) }
    // Сессию гасим сами (Стоп/В плеер) — тогда обрыв не показываем.
    var expectEnd by remember { mutableStateOf(false) }
    onSessionLostHook?.invoke { if (!expectEnd) error = "Соединение с ТВ потеряно" }

    fun currentTranslations(): List<FlatTranslation> =
        source?.let { catalog[it] }.orEmpty()

    fun currentEpisodes(): List<AnimeEpisode> =
        currentTranslations().firstOrNull { it.translationId == translationId }?.episodes.orEmpty()

    suspend fun refreshSkips() {
        skips = withContext(Dispatchers.IO) {
            AnimeStreamResolver.prefetchEpisodeSkips(shikimoriId, animeTitle, episode)
        }
    }

    fun remotePosNow(): Long = try {
        CastPlayback.remoteClient()?.approximateStreamPosition ?: 0L
    } catch (_: Exception) {
        0L
    }

    fun resolveAndCast(keepPosition: Boolean) {
        val src = source ?: return
        scope.launch {
            busy = true
            error = null
            try {
                val posMs = if (keepPosition) remotePosNow() else 0L
                val s = withContext(Dispatchers.IO) {
                    AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, src, translationId, episode)
                } ?: throw IllegalStateException("Поток не найден")
                stream = s
                // Auto/неизвестное → лучший конкретный ранг (Auto в пульте нет).
                bestRemoteRung(s.qualities)?.let { best ->
                    if (quality == "Auto" || !s.qualities.containsKey(quality)) quality = best
                }
                val relay = CastRelayServer.getInstance()
                    .register(context, s.url, s.headers, s.qualities, quality.takeIf { it != "Auto" })
                    ?: throw IllegalStateException("Нет Wi-Fi для трансляции")
                CastPlayback.load(relay, displayTitle, posMs / 1000, null)
                refreshSkips()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Не удалось загрузить поток"
            }
            busy = false
        }
    }

    fun applyQuality(rung: String) {
        val s = stream
        if (s == null) {
            quality = rung
            resolveAndCast(keepPosition = true)
            return
        }
        quality = rung
        scope.launch {
            busy = true
            error = null
            try {
                // Всегда явный ранг → пиннинг без ABR-мастера (Auto в пульте нет).
                val relay = CastRelayServer.getInstance()
                    .register(context, s.url, s.headers, s.qualities, rung.takeIf { it != "Auto" })
                    ?: throw IllegalStateException("Нет Wi-Fi для трансляции")
                CastPlayback.load(relay, displayTitle, remotePosNow() / 1000, null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Не удалось сменить качество"
            }
            busy = false
        }
    }

    /** Назад в плеер: свежая ссылка + позиция с ТВ, сессию гасим, пульт закрываем. */
    fun backToPlayer() {
        scope.launch {
            busy = true
            error = null
            try {
                val src = source ?: throw IllegalStateException("Нет потока")
                val posMs = remotePosNow()
                val s = withContext(Dispatchers.IO) {
                    AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, src, translationId, episode)
                } ?: throw IllegalStateException("Поток не найден")
                val rungUrl = quality.takeIf { it != "Auto" }?.let { s.qualities[it] } ?: s.url
                val eps = currentEpisodes()
                val trs = currentTranslations()
                val epTitle = eps.firstOrNull { it.number == episode }?.title.orEmpty()
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(rungUrl)
                    putExtra("uri", rungUrl)
                    putExtra("anime_auto_url", s.url)
                    putExtra(
                        "title",
                        if (epTitle.isNotBlank() && epTitle != "Серия $episode") {
                            "$displayTitle • Серия $episode ($epTitle)"
                        } else {
                            "$displayTitle • Серия $episode"
                        }
                    )
                    // Формат заголовков как у MpvExPlayerScreen: UA отдельно, остальные парами.
                    val headersArray = mutableListOf<String>()
                    val uaKey = s.headers.keys.firstOrNull { it.equals("user-agent", ignoreCase = true) }
                    val uaValue = if (uaKey != null) s.headers[uaKey] ?: "" else ""
                    if (uaValue.isNotBlank()) {
                        headersArray.add("User-Agent")
                        headersArray.add(uaValue)
                    }
                    s.headers.forEach { (key, value) ->
                        if (!key.equals("user-agent", ignoreCase = true)) {
                            headersArray.add(key)
                            headersArray.add(value)
                        }
                    }
                    putExtra("headers", headersArray.toTypedArray())
                    putExtra("anime_shikimori_id", shikimoriId)
                    putExtra("anime_title", animeTitle)
                    putExtra("anime_source_type", src.name)
                    putExtra("anime_disable_http_reuse", src == AnimeSourceType.ANILIBERTY)
                    putExtra("anime_current_episode", episode)
                    putExtra("anime_current_translation_id", translationId)
                    putExtra("anime_episodes", Json.encodeToString(eps))
                    putExtra("anime_translations", Json.encodeToString(trs))
                    if (s.qualities.isNotEmpty()) {
                        putExtra("anime_qualities", Json.encodeToString(s.qualities))
                        putExtra("anime_current_quality", quality)
                    }
                    putExtra("position", posMs.toInt())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                expectEnd = true
                // endSession внутри гасит и реле (локальный плеер льёт прямые URL).
                CastPlayback.endSession(context)
                context.startActivity(intent)
                (context as? android.app.Activity)?.finish()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Не удалось вернуться в плеер"
            }
            busy = false
        }
    }

    // Первичная загрузка каталога (кэши пикера обычно тёплые — мгновенно).
    LaunchedEffect(shikimoriId, animeTitle) {
        catalogBusy = true
        val map = withContext(Dispatchers.IO) {
            ANIME_PICKER_SOURCES.associateWith { src ->
                runCatching { AnimeStreamResolver.fetchSourceMedia(shikimoriId, animeTitle, src) }
                    .getOrDefault(emptyList())
            }
        }
        catalog = map
        val foundSource = map.entries.firstOrNull { (_, trs) -> trs.any { it.translationId == translationId } }?.key
            ?: map.entries.firstOrNull { it.value.isNotEmpty() }?.key
        source = foundSource
        val trs = foundSource?.let { map[it] }.orEmpty()
        if (trs.none { it.translationId == translationId }) {
            translationId = trs.firstOrNull()?.translationId.orEmpty()
        }
        val eps = trs.firstOrNull { it.translationId == translationId }?.episodes.orEmpty()
        if (eps.none { it.number == episode }) {
            episode = eps.firstOrNull()?.number ?: 1
        }
        catalogBusy = false
        refreshSkips()
    }

    val skip = if (casting) {
        skipTargetAt(skips, (remotePosMs / 1000).toInt())
    } else null

    val eps = currentEpisodes()
    val trs = currentTranslations()
    val currentTrTitle = trs.firstOrNull { it.translationId == translationId }?.title ?: "—"
    val rungs = stream?.qualities?.keys.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Пульт",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (casting) deviceName?.let { "ТВ · $it" } ?: "Трансляция" else displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (casting) Icons.Default.CastConnected else Icons.Default.Cast,
                contentDescription = null,
                tint = if (casting) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- пульт ----
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (casting) "Серия $episode · $currentTrTitle" else "ТВ не выбрано",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // D-pad: серии по бокам, play по центру.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        enabled = casting && eps.any { it.number == episode - 1 },
                        onClick = {
                            episode -= 1
                            resolveAndCast(keepPosition = false)
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Предыдущая серия",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .clickable(enabled = casting) { CastPlayback.toggle() }
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            if (busy) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(32.dp)
                                )
                            } else {
                                Icon(
                                    if (remotePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (remotePlaying) "Пауза" else "Играть",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(
                        enabled = casting && eps.any { it.number == episode + 1 },
                        onClick = {
                            episode += 1
                            resolveAndCast(keepPosition = false)
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Следующая серия",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Перемотка и громкость ТВ.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = casting,
                        onClick = { CastPlayback.seek((remotePosMs - 10_000).coerceAtLeast(0)) },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Назад 10 сек")
                    }
                    Icon(
                        Icons.Default.VolumeDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Slider(
                        value = volScrub ?: tvVolume.toFloat(),
                        onValueChange = { volScrub = it },
                        onValueChangeFinished = {
                            volScrub?.let {
                                try {
                                    mediaRouter.selectedRoute?.requestSetVolume(it.toInt())
                                } catch (_: Exception) {
                                }
                            }
                            volScrub = null
                        },
                        valueRange = 0f..tvVolumeMax.coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    IconButton(
                        enabled = casting,
                        onClick = { CastPlayback.seek(remotePosMs + 10_000) },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Вперёд 10 сек")
                    }
                }

                // Позиция.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatMs(scrub?.toLong() ?: remotePosMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp)
                    )
                    Slider(
                        value = scrub ?: remotePosMs.toFloat().coerceIn(0f, remoteDurMs.coerceAtLeast(1).toFloat()),
                        onValueChange = { scrub = it },
                        onValueChangeFinished = {
                            scrub?.let { CastPlayback.seek(it.toLong()) }
                            scrub = null
                        },
                        valueRange = 0f..remoteDurMs.coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatMs(remoteDurMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp)
                    )
                }

                skip?.let { (label, endSec) ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { CastPlayback.seek(endSec * 1000L) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!casting) {
                        Button(
                            onClick = {
                                (context as? FragmentActivity)?.let { activity ->
                                    MediaRouteChooserDialogFragment().apply {
                                        routeSelector = castSelector
                                    }.show(activity.supportFragmentManager, "cast_chooser")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Выбрать ТВ") }
                    } else {
                        OutlinedButton(
                            onClick = { backToPlayer() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("В плеер")
                        }
                        OutlinedButton(
                            onClick = {
                                expectEnd = true
                                // endSession внутри гасит и реле.
                                CastPlayback.endSession(context)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Стоп")
                        }
                    }
                }
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (catalogBusy) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            // ---- компактно: Серия / Озвучка / Источник / Качество ----
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    RemoteSelectorRow(
                        label = "Серия",
                        value = "$episode",
                        onClick = { openDialog = "series" }
                    )
                    RemoteSelectorRow(
                        label = "Озвучка",
                        value = currentTrTitle,
                        onClick = { openDialog = "dubs" }
                    )
                    RemoteSelectorRow(
                        label = "Источник",
                        value = source?.displayName ?: "—",
                        onClick = { openDialog = "sources" }
                    )
                    if (rungs.isNotEmpty()) {
                        RemoteSelectorRow(
                            label = "Качество",
                            value = quality.takeIf { it != "Auto" }
                                ?: bestRemoteRung(stream?.qualities.orEmpty()) ?: "—",
                            onClick = { openDialog = "quality" }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Переключения льются на ТВ автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    when (openDialog) {
        "series" -> RemoteOptionDialog(
            title = "Серия",
            options = eps.sortedBy { it.number }.map { ep ->
                val t = ep.title?.takeIf { it.isNotBlank() && it != "Серия ${ep.number}" }
                Triple(ep.number.toString(), "Серия ${ep.number}", t)
            },
            selectedId = episode.toString(),
            onSelect = {
                openDialog = null
                it.toIntOrNull()?.takeIf { n -> n != episode }?.let { n ->
                    episode = n
                    resolveAndCast(keepPosition = false)
                }
            },
            onDismiss = { openDialog = null }
        )
        "dubs" -> RemoteOptionDialog(
            title = "Озвучка",
            options = trs.map { tr ->
                Triple(tr.translationId, tr.title, "${tr.episodes.size} сер.")
            },
            selectedId = translationId,
            onSelect = { id ->
                openDialog = null
                if (id != translationId) {
                    translationId = id
                    val trEps = trs.firstOrNull { it.translationId == id }?.episodes.orEmpty()
                    if (trEps.none { it.number == episode }) {
                        episode = trEps.firstOrNull()?.number ?: 1
                    }
                    resolveAndCast(keepPosition = true)
                }
            },
            onDismiss = { openDialog = null }
        )
        "sources" -> RemoteOptionDialog(
            title = "Источник",
            options = ANIME_PICKER_SOURCES.mapNotNull { src ->
                val count = catalog[src]?.size ?: 0
                if (count == 0) null else Triple(src.name, src.displayName, "$count озвучек")
            },
            selectedId = source?.name.orEmpty(),
            onSelect = { name ->
                openDialog = null
                val src = ANIME_PICKER_SOURCES.firstOrNull { it.name == name } ?: return@RemoteOptionDialog
                if (src != source) {
                    val srcTrs = catalog[src].orEmpty()
                    if (srcTrs.isEmpty()) return@RemoteOptionDialog
                    val oldTitle = trs.firstOrNull { it.translationId == translationId }?.title
                    source = src
                    translationId = srcTrs.firstOrNull { it.title == oldTitle }?.translationId
                        ?: srcTrs.first().translationId
                    val srcEps = srcTrs.firstOrNull { it.translationId == translationId }?.episodes.orEmpty()
                    episode = if (srcEps.any { it.number == episode }) episode
                    else srcEps.firstOrNull()?.number ?: 1
                    resolveAndCast(keepPosition = true)
                }
            },
            onDismiss = { openDialog = null }
        )
        "quality" -> RemoteOptionDialog(
            title = "Качество",
            // Без Auto: мастер с ABR приёмник жуёт хуже явного ранга (обрыв LOAD).
            options = rungs.sortedByDescending { qualityRank(it) }.map { Triple(it, it, null) },
            selectedId = quality,
            onSelect = {
                openDialog = null
                if (it != quality) applyQuality(it)
            },
            onDismiss = { openDialog = null }
        )
    }
}
