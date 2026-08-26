package hd.kinoshka.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AnimeSourceType(val displayName: String, val description: String) {
    KODIK("Kodik", "Большой каталог озвучек и субтитров"),
    ANILIBERTY("AniLiberty", "Релизы AniLiberty с качествами 1080p/720p/480p"),
    ANILIB("AniLib", "Каталог AniLib (animelib.org), озвучки по командам")
}

@Serializable
data class AnimeSource(
    val type: AnimeSourceType,
    val isAvailable: Boolean = true,
    val episodesCount: Int? = null
)

@Serializable
data class AnimeTranslation(
    val id: String,
    val title: String,
    val type: String = "voice",
    val episodesCount: Int = 0
)

@Serializable
data class AnimeEpisode(
    val number: Int,
    val title: String? = null,
    val link: String? = null,
    val id: Int? = null,
    // Season number for multi-season series (movie-series mode). Null for plain anime episodes;
    // the player's season dropdown groups on this when more than one distinct value exists.
    val season: Int? = null,
    // Best quality advertised by the source at listing time (e.g. "1080p"), before any stream
    // resolution. Null when the source exposes no hint; the picker shows a short badge for it.
    val maxQuality: String? = null
)

/** Numeric rank of a "NNNNp" quality label; unknown/absent labels rank 0. */
fun qualityRank(quality: String?): Int =
    quality?.substringBefore("p")?.takeIf { it.length <= 4 }?.toIntOrNull() ?: 0

/**
 * Short badge label for the selection sheets: 2160p→"4К", 1440p→"2К", 1080p→"FHD", 720p→"HD".
 * Lower resolutions get no badge at all.
 */
fun qualityBadgeLabel(quality: String?): String? = when (quality) {
    "2160p" -> "4К"
    "1440p" -> "2К"
    "1080p" -> "FHD"
    "720p" -> "HD"
    else -> null
}

@Serializable
data class AnimeMediaStream(
    val url: String,
    val qualities: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "Auto",
    val title: String = ""
)

@Serializable
enum class SelectionStep {
    SOURCE,
    TRANSLATION,
    EPISODE
}

@Serializable
enum class PlaybackSequenceOption(val displayName: String, val steps: List<SelectionStep>) {
    EPISODES_FIRST("Сначала серии", listOf(SelectionStep.EPISODE, SelectionStep.SOURCE, SelectionStep.TRANSLATION)),
    SOURCES_FIRST("Сначала озвучки", listOf(SelectionStep.SOURCE, SelectionStep.TRANSLATION, SelectionStep.EPISODE));

    fun toUiLabel(): String = displayName
}

@Serializable
data class FlatTranslation(
    val source: AnimeSourceType,
    val translationId: String,
    val title: String,
    val type: String = "voice",
    val episodes: List<AnimeEpisode> = emptyList()
)
