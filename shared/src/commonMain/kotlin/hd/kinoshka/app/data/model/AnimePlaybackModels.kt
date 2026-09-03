package hd.kinoshka.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AnimeSourceType(val displayName: String, val description: String, val needsVpn: Boolean = false) {
    KODIK("Kodik", "Большой каталог озвучек и субтитров"),
    ANILIBERTY("AniLiberty", "Релизы AniLiberty с качествами 1080p/720p/480p"),
    ANILIB("AniLib", "Каталог AniLib (animelib.org), озвучки по командам"),
    ANISTAR("AniStar", "Свои озвучки AniStar, MP4/HLS 360–720p"),
    /** Официальный плеер «Смотреть онлайн» на Shikimori (агрегатор cdnvideohub, хостинг
     *  VK/OK CDN). Ключ — Shikimori id, без поиска по названию. В каталоге только
     *  нелицензированные в РФ тайтлы (лицензированные отвечают пустым 204), хентая нет. */
    SHIKIMORI("Shikimori", "Плеер Shikimori: озвучки и субтитры, HLS до 1080p"),
    /** Архив shikicinema (smarthard.net), ключ записей — Shikimori id. Часть ссылок ведёт на
     *  embed-хосты, недоступные без VPN: листинг не фильтруется, резолв ленивый (SmarthardApi).
     *  В аниме-пикере не участвует — источник остался только в хентай-флоу. */
    SMARTHARD("Smarthard", "Архив shikicinema: озвучки и субтитры; часть ссылок требует VPN", needsVpn = true),
    /** Direct CDN links (ddbb aggregator: turbo/collaps/alloha/veoveo). Movie/QOM rows only —
     *  never offered by the anime picker (see ANIME_PICKER_SOURCES). */
    DDBB("DDBB", "Прямые ссылки Turbo/Collaps/Alloha/Veoveo"),
    /** Hentai provider rows for the player's voiceover switcher (hentai flow in DetailsScreen);
     *  never offered by the anime picker (not in ANIME_PICKER_SOURCES). */
    HENTAI_ALLHENTAI("AllHentai", "Хентай-источник: русские озвучки"),
    HENTAI_HENTAIDREAM("HentaiDream", "Хентай-источник: русские озвучки"),
    HENTAI_HENTAIZ("HentaiZ", "Хентай-источник: оригинал и озвучки"),
    HENTAI_HANIME1("Hanime1.me", "Хентай-источник: оригинал с японскими титрами"),
    HENTAI_OPPAI("Oppai.Stream", "Хентай-источник: MP4 720/1080p")
}

/** Sources the anime selection screen races/loads; DDBB is movie-playback-only. */
val ANIME_PICKER_SOURCES: List<AnimeSourceType> =
    listOf(AnimeSourceType.KODIK, AnimeSourceType.SHIKIMORI, AnimeSourceType.ANILIBERTY, AnimeSourceType.ANILIB, AnimeSourceType.ANISTAR)

/**
 * Shared quality preference, best first: the default pick for any ladder (Kodik HLS, ddbb
 * direct, lazy voiceover resolve). One constant so resolvers and the player agree on "max".
 */
val QUALITY_PREFERENCE_DESC: List<String> =
    listOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "240p")

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
 * Short badge label for the selection sheets: 2160p→"4К", 1440p→"2К", 1080p→"FHD", 720p→"HD";
 * всё, что ниже 720p, →"SD", ниже 240p →"LD"; нестандартные высоты выше 720 — как "Np".
 */
fun qualityBadgeLabel(quality: String?): String? {
    val height = quality?.substringBefore("p")?.takeIf { it.length <= 4 }?.toIntOrNull() ?: return null
    if (height < 100) return null
    return when {
        height == 2160 -> "4К"
        height == 1440 -> "2К"
        height == 1080 -> "FHD"
        height == 720 -> "HD"
        height < 240 -> "LD"
        height < 720 -> "SD"
        else -> "${height}p"
    }
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
