package hd.kinoshka.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.marlboroadvance.mpvex.ui.player.PlayerActivity

@Composable
fun MpvExPlayerScreen(
    streamUrl: String,
    headers: Map<String, String> = emptyMap(),
    qualities: Map<String, String> = emptyMap(),
    animeTitle: String,
    episodeNumber: Int,
    episodeTitle: String,
    onBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPrevEpisode: (() -> Unit)? = null
) {
    val context = LocalContext.current

    LaunchedEffect(streamUrl) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(streamUrl)
            putExtra("uri", streamUrl)
            
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
            
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        onBack() // Dismiss the overlay overlay immediately once the player activity is started
    }
}
