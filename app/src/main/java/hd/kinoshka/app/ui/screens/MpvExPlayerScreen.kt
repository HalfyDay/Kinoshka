package hd.kinoshka.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieSeriesContextStore
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.NativePlaybackMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun MpvExPlayerScreen(
    streamUrl: String,
    headers: Map<String, String> = emptyMap(),
    qualities: Map<String, String> = emptyMap(),
    animeTitle: String,
    episodeNumber: Int,
    episodeTitle: String,
    shikimoriId: Int = 0,
    // Kinopoisk id of a native movie (QUALITY_ONLY_MOVIE). The player writes watched/completed
    // progress back to the library under this key; without it movie playback updated nothing.
    kinopoiskId: Int = 0,
    sourceType: String = "KODIK",
    episodes: List<AnimeEpisode> = emptyList(),
    translations: List<FlatTranslation> = emptyList(),
    currentTranslationId: String? = null,
    movieSeriesContext: MovieSeriesPlaybackContext? = null,
    playbackMode: NativePlaybackMode = NativePlaybackMode.ANIME,
    onBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPrevEpisode: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val userStateStore = UserStateStore(context)

    LaunchedEffect(streamUrl) {
        // PENDING_MOVIE launches with no stream yet: PlayerActivity opens immediately, shows its
        // loading overlay and resolves the stream itself (setPendingMovieExtras).
        val pendingLaunch = playbackMode == NativePlaybackMode.PENDING_MOVIE
        val intent = Intent(context, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW

            val preferredQuality = userStateStore.getPreferredQuality()
            val effectiveQuality = preferredQuality.takeIf { it != "Auto" && qualities.containsKey(it) } ?: "Auto"
            // "Auto" must NOT load a literal Auto entry from the qualities map: turbo's Auto is an
            // adaptive/master URL mpv frequently cannot open. Auto means the resolver's own pick —
            // streamUrl is the best concrete variant per the resolver's ladder, and PlayerActivity's
            // stall watchdog steps it down when the network can't sustain it.
            val effectiveUrl = if (effectiveQuality == "Auto") streamUrl
                else qualities[effectiveQuality] ?: streamUrl

            if (!pendingLaunch) {
                data = Uri.parse(effectiveUrl)
                putExtra("uri", effectiveUrl)
                putExtra("anime_auto_url", streamUrl)
            } else {
                putExtra("movie_pending_request", true)
            }

            val displayTitle = when {
                playbackMode == NativePlaybackMode.QUALITY_ONLY_MOVIE || pendingLaunch -> animeTitle
                movieSeriesContext != null -> movieSeriesContext.currentEpisode.let { episode ->
                    "$animeTitle • S${episode.seasonNumber}E${episode.episodeNumber}"
                }
                episodeTitle.isNotEmpty() -> "$animeTitle • Серия $episodeNumber ($episodeTitle)"
                else -> "$animeTitle • Серия $episodeNumber"
            }
            putExtra("title", displayTitle)
            putExtra("playback_mode", playbackMode.name)

            // Convert headers to the flat array format expected by PlayerActivity
            val headersArray = mutableListOf<String>()
            val uaKey = headers.keys.firstOrNull { it.equals("user-agent", ignoreCase = true) }
            val uaValue = if (uaKey != null) headers[uaKey] ?: "" else ""
            // An empty UA pair would blank mpv's User-Agent entirely (some CDNs then refuse
            // segments); omit it when unknown so mpv keeps its own sensible default.
            if (uaValue.isNotBlank()) {
                headersArray.add("User-Agent")
                headersArray.add(uaValue)
            }

            headers.forEach { (key, value) ->
                if (!key.equals("user-agent", ignoreCase = true)) {
                    headersArray.add(key)
                    headersArray.add(value)
                }
            }
            putExtra("headers", headersArray.toTypedArray())

            putExtra("anime_shikimori_id", shikimoriId)
            putExtra("movie_kinopoisk_id", kinopoiskId)
            putExtra("anime_title", animeTitle)
            putExtra("anime_source_type", sourceType)
            putExtra("anime_disable_http_reuse", sourceType == "ANILIBERTY")
            // Трейлеры — конечное VOD: плеер не должен навешивать reconnect-харденинг
            // (см. PlayerActivity, демuxer-lavf-o).
            putExtra("vod_stream", sourceType == "Трейлер")
            putExtra("anime_current_episode", episodeNumber)
            putExtra("anime_current_translation_id", currentTranslationId)
            movieSeriesContext?.let { ctx ->
                // The full context is hundreds of KB (dub×episode lists) — serializing it into
                // the intent overflowed the binder transaction (TransactionTooLargeException →
                // web-player fallback) and janked the main thread. Hand it over in-process and
                // put only the lookup id (small contexts still ride as JSON for safety).
                MovieSeriesContextStore.put(ctx)
                putExtra("movie_series_kp_id", ctx.kinopoiskId)
                val json = runCatching { Json.encodeToString(ctx) }.getOrNull()
                if (json != null && json.length < 120_000) {
                    putExtra("movie_series_context", json)
                }
            }

            if (episodes.isNotEmpty()) {
                putExtra("anime_episodes", Json.encodeToString(episodes))
            }
            if (translations.isNotEmpty()) {
                putExtra("anime_translations", Json.encodeToString(translations))
            }
            if (qualities.isNotEmpty()) {
                putExtra("anime_qualities", Json.encodeToString(qualities))
                putExtra("anime_current_quality", effectiveQuality)
            }
            
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        onBack() // Dismiss the overlay overlay immediately once the player activity is started
    }
}
