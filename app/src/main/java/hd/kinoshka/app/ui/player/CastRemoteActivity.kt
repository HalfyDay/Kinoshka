package hd.kinoshka.app.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
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
import hd.kinoshka.app.data.source.DdbbStreamResolver
import hd.kinoshka.app.data.playback.MovieNativeLauncher
import hd.kinoshka.app.data.model.MovieSeriesContextStore
import hd.kinoshka.app.data.model.MovieVoiceoverStreamStore
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.data.model.EpisodeSkips
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.qualityRank
import hd.kinoshka.app.ui.screens.SettingsHeaderCard
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
        // Ранги из плеера: строка качества видна сразу, без ожидания резолва.
        val intentQualities: Map<String, String> = runCatching {
            Json.decodeFromString<Map<String, String>>(
                intent.getStringExtra(EXTRA_ANIME_QUALITIES).orEmpty().ifBlank { "{}" }
            )
        }.getOrDefault(emptyMap())
        val initQualityCoerced = bestRemoteRung(intentQualities)?.let { best ->
            initQuality.takeIf { it != "Auto" && intentQualities.containsKey(it) } ?: best
        } ?: initQuality
        // Фильмы: тот же набор (эпизоды с сезонами, озвучки, лестница) из плеера.
        val filmMode = intent.getStringExtra(EXTRA_MODE) == "film"
        val filmKpId = intent.getIntExtra(EXTRA_FILM_KP_ID, 0)
        val filmIsSeries = intent.getBooleanExtra(EXTRA_FILM_IS_SERIES, false)
        val filmEpisodes: List<AnimeEpisode> = runCatching {
            Json.decodeFromString<List<AnimeEpisode>>(
                intent.getStringExtra(EXTRA_FILM_EPISODES).orEmpty().ifBlank { "[]" }
            )
        }.getOrDefault(emptyList())
        val filmTranslations: List<FlatTranslation> = runCatching {
            Json.decodeFromString<List<FlatTranslation>>(
                intent.getStringExtra(EXTRA_FILM_TRANSLATIONS).orEmpty().ifBlank { "[]" }
            )
        }.getOrDefault(emptyList())
        @Suppress("DEPRECATION")
        val playerIntent: android.content.Intent? =
            intent.getParcelableExtra(EXTRA_PLAYER_INTENT) as? android.content.Intent
        // Обрыв сессии (упал приёмник/сеть) показываем текстом: иначе пульт молча
        // висит в старом состоянии — именно так выглядел провал LOAD 2100 в логах.
        var notifySessionLost: (() -> Unit)? = null
        var notifySessionStarted: (() -> Unit)? = null
        CastPlayback.init(
            this,
            this,
            // Свежая сессия (перевыбор ТВ после Стоп): экран льёт текущий выбор с нуля.
            // Resume сюда не попадает (см. onSessionResumed в CastPlayback): приёмник
            // сам продолжил — переливать и ронять позицию не надо, опрос всё подхватит.
            onSessionStarted = { runOnUiThread { notifySessionStarted?.invoke() } },
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
                    initQuality = initQualityCoerced,
                    initQualities = intentQualities,
                    filmMode = filmMode,
                    filmKpId = filmKpId,
                    filmIsSeries = filmIsSeries,
                    filmEpisodes = filmEpisodes,
                    filmTranslations = filmTranslations,
                    playerIntent = playerIntent,
                    onBack = { finish() },
                    onSessionLostHook = { notifySessionLost = it },
                    onSessionStartedHook = { notifySessionStarted = it }
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
        const val EXTRA_ANIME_QUALITIES = "remote_qualities"
        const val EXTRA_DISPLAY_TITLE = "remote_display_title"
        const val EXTRA_MODE = "remote_mode" // "anime" | "film"
        const val EXTRA_FILM_KP_ID = "remote_film_kp"
        const val EXTRA_FILM_IS_SERIES = "remote_film_series"
        const val EXTRA_FILM_DIRECT = "remote_film_direct"
        const val EXTRA_FILM_EPISODES = "remote_film_episodes"
        const val EXTRA_FILM_TRANSLATIONS = "remote_film_translations"
        const val EXTRA_PLAYER_INTENT = "remote_player_intent"
    }
}

/** Лучший конкретный ранг (без Auto), null — рангов нет. */
private fun bestRemoteRung(qualities: Map<String, String>): String? =
    qualities.keys.maxByOrNull { qualityRank(it) }

/** Сезон серии как в плеере: поле season или композит season*100000+episode. */
private fun epSeason(ep: AnimeEpisode): Int? =
    ep.season ?: (ep.number.takeIf { it >= 100_000 }?.let { it / 100_000 })

/** Номер серии без упаковки сезона: композит season*100000+episode → младшая часть. */
private fun epEpisodeNumber(ep: AnimeEpisode): Int {
    if (epSeason(ep) == null) return ep.number
    return if (ep.number >= 100_000) ep.number % 100_000 else ep.number
}

/** Человекочитаемая подпись серии: «Сезон S, серия E» либо «Серия N». */
private fun epDisplayLong(ep: AnimeEpisode): String {
    val season = epSeason(ep)
    return if (season != null) "Сезон $season, серия ${epEpisodeNumber(ep)}"
    else "Серия ${ep.number}"
}

/** Подпись текущего выбора по ключу: ищет серию в списке, иначе декодирует композит. */
private fun episodeDisplayByKey(key: Int, eps: List<AnimeEpisode>): String {
    val found = eps.firstOrNull { it.number == key }
    if (found != null) return epDisplayLong(found)
    if (key >= 100_000) return "Сезон ${key / 100_000}, серия ${key % 100_000}"
    return "Серия $key"
}

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

/**
 * Общий диалог выбора — bottom-sheet: шторка снизу со скруглением 28dp,
 * ручкой, жирным заголовком и строками 12dp (выбранная — primaryContainer).
 * Слот header — чипы сезонов и т.п.
 */
@Composable
private fun RemoteOptionDialog(
    title: String,
    options: List<Triple<String, String, String?>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp)
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 24.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(width = 32.dp, height = 4.dp)
                        ) {}
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    header()
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(options, key = { it.first }) { (id, label, sub) ->
                            val selected = id == selectedId
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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
                    }
                }
            }
        }
    }
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
    initQualities: Map<String, String> = emptyMap(),
    filmMode: Boolean = false,
    filmKpId: Int = 0,
    filmIsSeries: Boolean = false,
    filmEpisodes: List<AnimeEpisode> = emptyList(),
    filmTranslations: List<FlatTranslation> = emptyList(),
    playerIntent: Intent? = null,
    onBack: () -> Unit,
    onSessionLostHook: (((() -> Unit)?) -> Unit)? = null,
    onSessionStartedHook: (((() -> Unit)?) -> Unit)? = null
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
    var tvMuted by remember { mutableStateOf(false) }
    // Оптимистичная громкость: тап/слайдер рисуются сразу, а опрос ниже подхватывает
    // реальное значение маршрута. Пока идёт drag слайдера или свежий коммит (<1.5с) —
    // опрос громкость не перезаписывает, иначе ползунок дёргается назад.
    var volumeDragging by remember { mutableStateOf(false) }
    var lastVolumeCommitMs by remember { mutableStateOf(0L) }
    var lastUnmutedVolume by remember { mutableStateOf(5) }
    LaunchedEffect(Unit) {
        while (true) {
            casting = CastPlayback.isCasting
            try {
                CastPlayback.remoteClient()?.let { c ->
                    remotePosMs = c.approximateStreamPosition
                    remoteDurMs = c.streamDuration
                    remotePlaying = c.isPlaying
                    tvMuted = runCatching { c.mediaStatus?.isMute == true }.getOrDefault(tvMuted)
                }
                deviceName = CastPlayback.deviceName()
                mediaRouter.selectedRoute?.let { r ->
                    tvVolumeMax = r.volumeMax.coerceAtLeast(1)
                    if (!volumeDragging && System.currentTimeMillis() - lastVolumeCommitMs > 1500) {
                        tvVolume = r.volume.coerceIn(0, tvVolumeMax)
                    }
                    if (!tvMuted && tvVolume > 0) lastUnmutedVolume = tvVolume
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
    // Фильмы: статичный набор из плеера (каталог не фетчим), озвучки серийного
    // фильма перефильтровываем по эпизоду из in-process контекста.
    var filmTrs by remember { mutableStateOf(filmTranslations) }
    var quality by remember { mutableStateOf(initQuality) }
    var stream by remember { mutableStateOf<AnimeMediaStream?>(null) }
    var skips by remember { mutableStateOf<EpisodeSkips?>(null) }
    var catalogBusy by remember { mutableStateOf(!filmMode) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var openDialog by remember { mutableStateOf<String?>(null) }
    var seasonFilter by remember { mutableStateOf<Int?>(null) }
    // Сессию гасим сами (Стоп/В плеер) — тогда обрыв не показываем.
    var expectEnd by remember { mutableStateOf(false) }
    onSessionLostHook?.invoke { if (!expectEnd) error = "Соединение с ТВ потеряно" }

    fun currentTranslations(): List<FlatTranslation> =
        if (filmMode) filmTrs
        else source?.let { catalog[it] }.orEmpty()

    fun currentEpisodes(): List<AnimeEpisode> =
        if (filmMode) filmEpisodes
        else currentTranslations().firstOrNull { it.translationId == translationId }?.episodes.orEmpty()

    suspend fun refreshSkips() {
        skips = if (shikimoriId > 0) withContext(Dispatchers.IO) {
            AnimeStreamResolver.prefetchEpisodeSkips(shikimoriId, animeTitle, episode)
        } else null
    }

    /** Озвучки серийного фильма, покрывающие эпизод — как seriesTranslationsFor плеера. */
    fun filmTranslationsForEpisode(epKey: Int): List<FlatTranslation> {
        val ctx = MovieSeriesContextStore.get(filmKpId) ?: return filmTrs
        val ep = ctx.episodes.firstOrNull { it.playerEpisodeKey == epKey } ?: return filmTrs
        return ctx.candidates
            .filter { c ->
                !c.translationId.isNullOrBlank() && c.episodes.any {
                    it.seasonNumber == ep.seasonNumber && it.episodeNumber == ep.episodeNumber
                }
            }
            .map { dub ->
                val rawTitle = dub.translationTitle ?: dub.translationId.orEmpty()
                val (dubTitle, kind) = MovieNativeLauncher.splitDubTrack(rawTitle)
                FlatTranslation(
                    source = if (ctx.isDirectSource) AnimeSourceType.DDBB else AnimeSourceType.KODIK,
                    translationId = dub.translationId ?: rawTitle,
                    title = if (rawTitle.isBlank()) "Озвучка" else dubTitle,
                    type = kind,
                    episodes = emptyList()
                )
            }
            .takeIf { it.isNotEmpty() } ?: filmTrs
    }

    /** Резолв ссылки фильма: прямые файлы как есть, Kodik — через HLS-экстрактор. */
    suspend fun resolveFilmLink(link: String): Triple<String, Map<String, String>, Map<String, String>> {
        val direct = link.contains(".mp4") || link.contains(".m3u8") ||
            link.contains(".webm") || link.contains("/stream/UTN")
        val looksKodik = listOf(
            "kodik", "vsh.my", "kdkonl", "aniqit", "kodi.my", "obrut.show", "/seria/", "/video/"
        ).any { link.contains(it, ignoreCase = true) }
        if (looksKodik && !direct) {
            val qualities = runCatching {
                AnimeStreamResolver.resolveKodikHls(AnimeStreamResolver.absoluteKodikUrl(link))
            }.getOrDefault(emptyMap())
            val url = QUALITY_PREFERENCE_DESC.firstOrNull { qualities.containsKey(it) }
                ?.let { qualities[it] } ?: qualities.values.firstOrNull() ?: link
            return Triple(url, qualities, AnimeStreamResolver.kodikPlaybackHeaders())
        }
        val ladder = DdbbStreamResolver.cachedLadderFor(link).orEmpty()
        val headers = DdbbStreamResolver.directHeaders(filmKpId).takeIf { it.isNotEmpty() }
            ?: AnimeStreamResolver.kodikPlaybackHeaders()
        return Triple(link, ladder, headers)
    }

    suspend fun resolveFilmStream(): AnimeMediaStream? {
        // Готовый стрим из in-process каталога (положил пикер фильма).
        if (filmKpId > 0) {
            MovieVoiceoverStreamStore.get(filmKpId)[translationId]?.let { return it }
        }
        val link = if (filmIsSeries) {
            filmEpisodes.firstOrNull { it.number == episode }?.link?.takeIf { it.isNotBlank() }
                ?: MovieSeriesContextStore.get(filmKpId)?.episodes
                    ?.firstOrNull { it.playerEpisodeKey == episode }?.playerUrl
        } else {
            filmTrs.firstOrNull { it.translationId == translationId }
                ?.episodes?.firstOrNull()?.link
        }.orEmpty()
        if (link.isBlank()) return null
        val (url, ladder, headers) = resolveFilmLink(link)
        return AnimeMediaStream(url = url, qualities = ladder, headers = headers)
    }

    fun remotePosNow(): Long = try {
        CastPlayback.remoteClient()?.approximateStreamPosition ?: 0L
    } catch (_: Exception) {
        0L
    }

    /** Коммит уровня в маршрут (один IPC); UI уже обновлён оптимистично. */
    fun commitTvVolume(v: Int) {
        lastVolumeCommitMs = System.currentTimeMillis()
        try {
            mediaRouter.selectedRoute?.requestSetVolume(v.coerceIn(0, tvVolumeMax.coerceAtLeast(1)))
        } catch (_: Exception) {
        }
    }

    fun setTvMuted(muted: Boolean) {
        val client = try {
            CastPlayback.remoteClient()
        } catch (_: Exception) {
            null
        }
        if (client != null) {
            tvMuted = muted
            lastVolumeCommitMs = System.currentTimeMillis()
            try {
                client.setStreamMute(muted)
            } catch (_: Exception) {
            }
        } else {
            // Клиента нет (не кастим — кнопки и так disabled): дефенсивно через маршрут.
            // Прямая запись состояния + один коммит (без рекурсии в setTvVolume).
            if (muted) {
                if (tvVolume > 0) lastUnmutedVolume = tvVolume
                tvVolume = 0
            } else {
                tvVolume = lastUnmutedVolume.coerceIn(1, tvVolumeMax.coerceAtLeast(1))
            }
            tvMuted = muted
            commitTvVolume(tvVolume)
        }
    }

    fun setTvVolume(v: Int) {
        tvVolume = v.coerceIn(0, tvVolumeMax.coerceAtLeast(1))
        if (tvMuted) setTvMuted(false)
        commitTvVolume(tvVolume)
    }

    fun adjustTvVolume(delta: Int) {
        setTvVolume(tvVolume + delta)
    }

    fun resolveFilmAndCast(keepPosition: Boolean) {
        scope.launch {
            busy = true
            error = null
            try {
                val posMs = if (keepPosition) remotePosNow() else 0L
                val s = withContext(Dispatchers.IO) {
                    resolveFilmStream()
                } ?: throw IllegalStateException("Поток не найден")
                stream = s
                bestRemoteRung(s.qualities)?.let { best ->
                    if (quality == "Auto" || !s.qualities.containsKey(quality)) quality = best
                }
                val relay = CastRelayServer.getInstance()
                    .register(context, s.url, s.headers, s.qualities, quality.takeIf { it != "Auto" })
                    ?: throw IllegalStateException("Нет Wi-Fi для трансляции")
                CastPlayback.load(relay, displayTitle, posMs / 1000, null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Не удалось загрузить поток"
            }
            busy = false
        }
    }

    /** Смена серии фильма: озвучки перефильтровываем под эпизод, льём заново. */
    fun onFilmEpisode(n: Int, keepPosition: Boolean) {
        episode = n
        if (filmIsSeries) {
            filmTrs = filmTranslationsForEpisode(n)
            if (filmTrs.none { it.translationId == translationId }) {
                translationId = filmTrs.firstOrNull()?.translationId.orEmpty()
            }
        }
        resolveFilmAndCast(keepPosition)
    }

    fun resolveAndCast(keepPosition: Boolean) {
        if (filmMode) {
            resolveFilmAndCast(keepPosition)
            return
        }
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
        // Фильмы: перезапуск исходного интента плеера с позиции ТВ.
        val launch = playerIntent
        // Каст со страницы тайтла (через пикер «Смотреть»): плеер никогда не открывался,
        // playerIntent нет — просто гасим сессию и возвращаемся к деталям,
        // откуда пользователь при желании запустит локальный просмотр кнопкой Play.
        if (filmMode && launch == null) {
            expectEnd = true
            CastPlayback.endSession(context)
            (context as? android.app.Activity)?.finish()
            return
        }
        if (filmMode && launch != null) {
            scope.launch {
                busy = true
                error = null
                try {
                    // "position" — миллисекунды, как в аниме-ветке ниже.
                    val posMs = remotePosNow().toInt().coerceAtLeast(0)
                    expectEnd = true
                    CastPlayback.endSession(context)
                    context.startActivity(launch.apply { putExtra("position", posMs) })
                    (context as? android.app.Activity)?.finish()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = e.message ?: "Не удалось вернуться в плеер"
                }
                busy = false
            }
            return
        }
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
                val epLabel = episodeDisplayByKey(episode, eps)
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(rungUrl)
                    putExtra("uri", rungUrl)
                    putExtra("anime_auto_url", s.url)
                    putExtra(
                        "title",
                        if (epTitle.isNotBlank() && epTitle != epLabel && epTitle != "Серия $episode") {
                            "$displayTitle • $epLabel ($epTitle)"
                        } else {
                            "$displayTitle • $epLabel"
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

    // Свежая сессия (перевыбор ТВ после Стоп): сам пульт ничего не грузит, поэтому
    // без этого приёмник оставался на заставке. Льём текущий выбор с нуля с явным
    // резолвом (реле после endSession остановлено — resolve заново его поднимет).
    onSessionStartedHook?.invoke {
        expectEnd = false
        error = null
        if (filmMode) resolveFilmAndCast(keepPosition = false)
        else resolveAndCast(keepPosition = false)
    }

    // Первичная загрузка каталога (кэши пикера обычно тёплые — мгновенно).
    // Фильмы: набор статичен из плеера, фетчить нечего.
    LaunchedEffect(shikimoriId, animeTitle) {
        if (!filmMode) {
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
        }
        refreshSkips()
    }

    val skip = if (casting) {
        skipTargetAt(skips, (remotePosMs / 1000).toInt())
    } else null

    val eps = currentEpisodes()
    val trs = currentTranslations()
    val currentTrTitle = trs.firstOrNull { it.translationId == translationId }?.title ?: "—"
    // Шаг по позиции в списке, а не ±1: номера бывают составными (сезон*100000+эпизод).
    val sortedEps = remember(eps) { eps.sortedBy { it.number } }
    val epIdx = sortedEps.indexOfFirst { it.number == episode }
    // Ранги: свежие из резолва, иначе из интента плеера — строка качества
    // видна сразу, а не только после смены источника.
    val rungs = stream?.qualities?.keys?.takeIf { it.isNotEmpty() } ?: initQualities.keys

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 32.dp)
    ) {
        // Шапка как на Памяти/Загрузках: та же карточка, те же отступы.
        // Название — только здесь: из тела пульта заголовок убран, осталась
        // строка статуса (серия/озвучка или «ТВ не выбрано»).
        SettingsHeaderCard(
            title = "Пульт",
            subtitle = if (casting) deviceName?.let { "ТВ · $it" } ?: "Трансляция" else displayTitle,
            onBack = onBack,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            action = {
                Icon(
                    if (casting) Icons.Default.CastConnected else Icons.Default.Cast,
                    contentDescription = null,
                    tint = if (casting) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        )

        Spacer(modifier = Modifier.height(2.dp))

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
                    // Сериалы: композит season*100000+episode показываем как «Сезон S, серия E»,
                    // а не сырым ключом (300009 вместо «3 сезон 9 серия»).
                    text = if (casting) "${episodeDisplayByKey(episode, eps)} · $currentTrTitle" else "ТВ не выбрано",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // D-pad как на настоящем пульте: вверх — следующая серия,
                // вниз — предыдущая, влево/вправо — ∓10 сек, центр — play/pause.
                // Громкость ТВ — горизонтальной полосой под крестовиной.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(248.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(248.dp)
                        ) {}
                        IconButton(
                            enabled = casting && epIdx >= 0 && epIdx < sortedEps.lastIndex,
                            onClick = {
                                sortedEps.getOrNull(epIdx + 1)?.let {
                                    if (filmMode) onFilmEpisode(it.number, keepPosition = false)
                                    else {
                                        episode = it.number
                                        resolveAndCast(keepPosition = false)
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.TopCenter).size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Следующая серия",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(
                            enabled = casting && epIdx > 0,
                            onClick = {
                                sortedEps.getOrNull(epIdx - 1)?.let {
                                    if (filmMode) onFilmEpisode(it.number, keepPosition = false)
                                    else {
                                        episode = it.number
                                        resolveAndCast(keepPosition = false)
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "Предыдущая серия",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(
                            enabled = casting,
                            onClick = { CastPlayback.seek((remotePosMs - 10_000).coerceAtLeast(0)) },
                            modifier = Modifier.align(Alignment.CenterStart).size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.FastRewind,
                                contentDescription = "Назад 10 сек",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(
                            enabled = casting,
                            onClick = { CastPlayback.seek(remotePosMs + 10_000) },
                            modifier = Modifier.align(Alignment.CenterEnd).size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.FastForward,
                                contentDescription = "Вперёд 10 сек",
                                modifier = Modifier.size(32.dp)
                            )
                        }
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
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Громкость ТВ — горизонтальная полоса под крестовиной:
                // тише (удержание — без звука) / слайдер / громче.
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        // Тише: тап −1, удержание — без звука (отдельной кнопки нет).
                        // Мьют подсвечивается primary; снимается плюсом, слайдером и тапом.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    enabled = casting,
                                    onClick = { adjustTvVolume(-1) },
                                    onLongClick = { if (!tvMuted) setTvMuted(true) }
                                )
                        ) {
                            Icon(
                                Icons.Default.VolumeDown,
                                contentDescription = "Тише (удержание — без звука)",
                                tint = when {
                                    !casting -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    tvMuted -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Slider(
                            value = tvVolume.toFloat().coerceIn(0f, tvVolumeMax.coerceAtLeast(1).toFloat()),
                            onValueChange = {
                                volumeDragging = true
                                tvVolume = it.toInt().coerceIn(0, tvVolumeMax.coerceAtLeast(1))
                            },
                            onValueChangeFinished = {
                                volumeDragging = false
                                if (tvMuted) setTvMuted(false)
                                commitTvVolume(tvVolume)
                            },
                            valueRange = 0f..tvVolumeMax.coerceAtLeast(1).toFloat(),
                            enabled = casting,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "$tvVolume",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        IconButton(
                            enabled = casting,
                            onClick = { adjustTvVolume(1) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = "Громче",
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // Позиция: строка на всю ширину, значения фиксированной ширины
                // по краям (справа — по правому краю), слайдер ровно между ними.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatMs(scrub?.toLong() ?: remotePosMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
                        maxLines = 1,
                        textAlign = TextAlign.End,
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
                    // Фильмы без серий (QOM): ряда серий нет; источник скрыт —
                    // озвучки и так подписаны источником.
                    if (eps.isNotEmpty()) {
                        RemoteSelectorRow(
                            label = "Серия",
                            value = episodeDisplayByKey(episode, eps),
                            onClick = { openDialog = "series" }
                        )
                    }
                    RemoteSelectorRow(
                        label = "Озвучка",
                        value = currentTrTitle,
                        onClick = { openDialog = "dubs" }
                    )
                    if (!filmMode) {
                        RemoteSelectorRow(
                            label = "Источник",
                            value = source?.displayName ?: "—",
                            onClick = { openDialog = "sources" }
                        )
                    }
                    if (rungs.isNotEmpty()) {
                        RemoteSelectorRow(
                            label = "Качество",
                            value = quality.takeIf { it != "Auto" && rungs.contains(it) }
                                ?: rungs.maxByOrNull { qualityRank(it) } ?: "—",
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
        "series" -> {
            val seasons = remember(eps) { eps.mapNotNull(::epSeason).distinct().sorted() }
            val selSeason = seasonFilter.takeIf { it in seasons }
                ?: eps.firstOrNull { it.number == episode }?.let(::epSeason)?.takeIf { it in seasons }
            val visibleEps = remember(eps, selSeason) {
                (if (selSeason == null) eps else eps.filter { epSeason(it) == selSeason })
                    .sortedBy { it.number }
            }
            RemoteOptionDialog(
                title = "Серия",
                options = visibleEps.map { ep ->
                    val label = epDisplayLong(ep)
                    val epNum = epEpisodeNumber(ep)
                    val t = ep.title?.takeIf {
                        it.isNotBlank() && it != "Серия $epNum" && it != "Серия ${ep.number}"
                    }
                    Triple(ep.number.toString(), label, t)
                },
                selectedId = episode.toString(),
                onSelect = {
                    openDialog = null
                    it.toIntOrNull()?.takeIf { n -> n != episode }?.let { n ->
                        if (filmMode) onFilmEpisode(n, keepPosition = false)
                        else {
                            episode = n
                            resolveAndCast(keepPosition = false)
                        }
                    }
                },
                onDismiss = { openDialog = null },
                header = {
                    if (seasons.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            items(seasons) { s ->
                                val isSel = s == selSeason
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    contentColor = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clickable { seasonFilter = s }
                                ) {
                                    Text(
                                        text = "Сезон $s",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
        "dubs" -> RemoteOptionDialog(
            title = "Озвучка",
            options = trs.map { tr ->
                Triple(
                    tr.translationId, tr.title,
                    if (filmMode) tr.source.displayName else "${tr.episodes.size} сер."
                )
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
