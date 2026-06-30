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
    sourceType: String = "KODIK",
    episodes: List<AnimeEpisode> = emptyList(),
    translations: List<FlatTranslation> = emptyList(),
    currentTranslationId: String? = null,
    onBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPrevEpisode: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val userStateStore = UserStateStore(context)

    LaunchedEffect(streamUrl) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            
            val preferredQuality = userStateStore.getPreferredQuality()
            val effectiveUrl = if (qualities.containsKey(preferredQuality)) {
                qualities[preferredQuality] ?: streamUrl
            } else {
                streamUrl
            }

            data = Uri.parse(effectiveUrl)
            putExtra("uri", effectiveUrl)
            
            val displayTitle = if (episodeTitle.isNotEmpty()) {
                "$animeTitle • Серия $episodeNumber ($episodeTitle)"
            } else {
                "$animeTitle • Серия $episodeNumber"
            }
            putExtra("title", displayTitle)

            // Convert headers to the flat array format expected by PlayerActivity
            val headersArray = mutableListOf<String>()
            val uaKey = headers.keys.firstOrNull { it.equals("user-agent", ignoreCase = true) }
            val uaValue = if (uaKey != null) headers[uaKey] ?: "" else ""
            headersArray.add("User-Agent")
            headersArray.add(uaValue)

            headers.forEach { (key, value) ->
                if (!key.equals("user-agent", ignoreCase = true)) {
                    headersArray.add(key)
                    headersArray.add(value)
                }
            }
            putExtra("headers", headersArray.toTypedArray())

            putExtra("anime_shikimori_id", shikimoriId)
            putExtra("anime_title", animeTitle)
            putExtra("anime_source_type", sourceType)
            putExtra("anime_current_episode", episodeNumber)
            putExtra("anime_current_translation_id", currentTranslationId)

            if (episodes.isNotEmpty()) {
                putExtra("anime_episodes", Json.encodeToString(episodes))
            }
            if (translations.isNotEmpty()) {
                putExtra("anime_translations", Json.encodeToString(translations))
            }
            if (qualities.isNotEmpty()) {
                putExtra("anime_qualities", Json.encodeToString(qualities))
                putExtra("anime_current_quality", if (qualities.containsKey(preferredQuality)) preferredQuality else "Auto")
            }
            
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        onBack() // Dismiss the overlay overlay immediately once the player activity is started
    }
}
