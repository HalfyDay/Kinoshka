package hd.kinoshka.app.data.model

enum class AnimeSourceType(val displayName: String, val description: String) {
    KODIK("Kodik", "Большой каталог озвучек и субтитров"),
    ANILIBERTY("AniLiberty", "Релизы AniLiberty с качествами 1080p/720p/480p"),
    ANILIB("AniLib", "Каталог серий AniLib с выбором команды перевода")
}

data class AnimeSource(
    val type: AnimeSourceType,
    val isAvailable: Boolean = true,
    val episodesCount: Int? = null
)

data class AnimeTranslation(
    val id: String,
    val title: String,
    val type: String = "voice",
    val episodesCount: Int = 0
)

data class AnimeEpisode(
    val number: Int,
    val title: String? = null,
    val link: String? = null,
    val id: Int? = null
)

data class AnimeMediaStream(
    val url: String,
    val qualities: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "Auto",
    val title: String = ""
)

enum class SelectionStep {
    SOURCE,
    TRANSLATION,
    EPISODE
}

enum class PlaybackSequenceOption(val displayName: String, val steps: List<SelectionStep>) {
    EPISODES_TRANSLATIONS_SOURCES("Серии -> Озвучка -> Источники", listOf(SelectionStep.EPISODE, SelectionStep.TRANSLATION, SelectionStep.SOURCE)),
    TRANSLATIONS_EPISODES_SOURCES("Озвучка -> Серии -> Источники", listOf(SelectionStep.TRANSLATION, SelectionStep.EPISODE, SelectionStep.SOURCE)),
    SOURCES_TRANSLATIONS_EPISODES("Источники -> Озвучка -> Серии", listOf(SelectionStep.SOURCE, SelectionStep.TRANSLATION, SelectionStep.EPISODE)),
    SOURCES_EPISODES_TRANSLATIONS("Источники -> Серии -> Озвучка", listOf(SelectionStep.SOURCE, SelectionStep.EPISODE, SelectionStep.TRANSLATION)),
    EPISODES_SOURCES_TRANSLATIONS("Серии -> Источники -> Озвучка", listOf(SelectionStep.EPISODE, SelectionStep.SOURCE, SelectionStep.TRANSLATION)),
    TRANSLATIONS_SOURCES_EPISODES("Озвучка -> Источники -> Серии", listOf(SelectionStep.TRANSLATION, SelectionStep.SOURCE, SelectionStep.EPISODE));

    fun toUiLabel(): String = displayName
}

data class FlatTranslation(
    val source: AnimeSourceType,
    val translationId: String,
    val title: String,
    val type: String = "voice",
    val episodes: List<AnimeEpisode> = emptyList()
)

