package hd.kinoshka.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AnimeSourceType(val displayName: String, val description: String) {
    KODIK("Kodik", "Большой каталог озвучек и субтитров"),
    ANILIBERTY("AniLiberty", "Релизы AniLiberty с качествами 1080p/720p/480p")
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
    val id: Int? = null
)

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
