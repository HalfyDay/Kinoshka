package hd.kinoshka.app.data.model

enum class AnimeSourceType(val displayName: String, val description: String) {
    KODIK("Kodik", "Самый большой каталог (все озвучки и субтитры)"),
    ANILIBRIA("AniLibria", "Официальный дубляж AniLibria (высокая скорость, 1080p)")
}

data class AnimeSource(
    val type: AnimeSourceType,
    val isAvailable: Boolean = true,
    val episodesCount: Int? = null
)

data class AnimeTranslation(
    val id: String,
    val title: String,
    val type: String = "voice", // voice, sub, etc.
    val episodesCount: Int = 0
)

data class AnimeEpisode(
    val number: Int,
    val title: String? = null,
    val link: String? = null
)

data class AnimeMediaStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "Auto",
    val title: String = ""
)
