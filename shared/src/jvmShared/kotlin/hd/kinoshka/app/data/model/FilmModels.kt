package hd.kinoshka.app.data.model

import com.google.gson.annotations.SerializedName

data class FilmItem(
    @SerializedName("kinopoiskId") val kinopoiskId: Int,
    @SerializedName("nameRu") val nameRu: String?,
    @SerializedName("nameOriginal") val nameOriginal: String?,
    @SerializedName("posterUrlPreview") val posterUrlPreview: String?,
    @SerializedName("ratingKinopoisk") val ratingKinopoisk: Double?,
    @SerializedName("year") val year: Int?,
    @SerializedName("countries") val countries: List<NameOnly> = emptyList(),
    @SerializedName("genres") val genres: List<NameOnly> = emptyList()
)

const val ANIME_GENRE_NAME = "аниме"

/** Аниме у Kinopoisk помечается жанром «аниме» (в старых карточках встречается латиница). */
fun List<NameOnly>?.containsAnimeGenre(): Boolean {
    if (this == null) return false
    return any { g ->
        val n = g.genre?.trim()?.lowercase()
        n == ANIME_GENRE_NAME || n == "anime"
    }
}

data class FilmsResponse(
    @SerializedName("items") val items: List<FilmItem>
)

data class FilmSeasonsResponse(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("items") val items: List<SeasonItem> = emptyList()
)

data class SeasonItem(
    @SerializedName("number") val number: Int = 0,
    @SerializedName("episodes") val episodes: List<EpisodeItem> = emptyList()
)

data class EpisodeItem(
    @SerializedName("seasonNumber") val seasonNumber: Int = 0,
    @SerializedName("episodeNumber") val episodeNumber: Int = 0,
    @SerializedName("nameRu") val nameRu: String? = null,
    @SerializedName("nameEn") val nameEn: String? = null,
    @SerializedName("synopsis") val synopsis: String? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null
)

data class FilmLinksResponse(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("items") val items: List<FilmLinkItem> = emptyList()
)

data class FilmLinkItem(
    @SerializedName("filmId") val filmId: Int? = null,
    @SerializedName("kinopoiskId") val kinopoiskId: Int? = null,
    @SerializedName("nameRu") val nameRu: String? = null,
    @SerializedName("nameEn") val nameEn: String? = null,
    @SerializedName("nameOriginal") val nameOriginal: String? = null,
    @SerializedName("posterUrl") val posterUrl: String? = null,
    @SerializedName("posterUrlPreview") val posterUrlPreview: String? = null,
    @SerializedName("relationType") val relationType: String? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("type") val type: String? = null
) {
    val id: Int
        get() = kinopoiskId ?: filmId ?: 0
}

data class FilmImagesResponse(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("totalPages") val totalPages: Int = 0,
    @SerializedName("items") val items: List<FilmImageItem> = emptyList()
)

data class FilmImageItem(
    @SerializedName("imageUrl") val imageUrl: String? = null,
    @SerializedName("previewUrl") val previewUrl: String? = null
)

data class FilmDetails(
    @SerializedName("kinopoiskId") val kinopoiskId: Int,
    @SerializedName("kinopoiskHDId") val kinopoiskHDId: String? = null,
    @SerializedName("imdbId") val imdbId: String? = null,
    @SerializedName("nameRu") val nameRu: String? = null,
    @SerializedName("nameEn") val nameEn: String? = null,
    @SerializedName("nameOriginal") val nameOriginal: String? = null,
    @SerializedName("posterUrl") val posterUrl: String? = null,
    @SerializedName("posterUrlPreview") val posterUrlPreview: String? = null,
    @SerializedName("coverUrl") val coverUrl: String? = null,
    @SerializedName("logoUrl") val logoUrl: String? = null,
    @SerializedName("reviewsCount") val reviewsCount: Int? = null,
    @SerializedName("ratingGoodReview") val ratingGoodReview: Double? = null,
    @SerializedName("ratingGoodReviewVoteCount") val ratingGoodReviewVoteCount: Int? = null,
    @SerializedName("ratingKinopoisk") val ratingKinopoisk: Double? = null,
    @SerializedName("ratingKinopoiskVoteCount") val ratingKinopoiskVoteCount: Int? = null,
    @SerializedName("ratingImdb") val ratingImdb: Double? = null,
    @SerializedName("ratingImdbVoteCount") val ratingImdbVoteCount: Int? = null,
    @SerializedName("ratingFilmCritics") val ratingFilmCritics: Double? = null,
    @SerializedName("ratingFilmCriticsVoteCount") val ratingFilmCriticsVoteCount: Int? = null,
    @SerializedName("ratingAwait") val ratingAwait: Double? = null,
    @SerializedName("ratingAwaitCount") val ratingAwaitCount: Int? = null,
    @SerializedName("ratingRfCritics") val ratingRfCritics: Double? = null,
    @SerializedName("ratingRfCriticsVoteCount") val ratingRfCriticsVoteCount: Int? = null,
    @SerializedName("webUrl") val webUrl: String? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("filmLength") val filmLength: Int? = null,
    @SerializedName("slogan") val slogan: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("shortDescription") val shortDescription: String? = null,
    @SerializedName("editorAnnotation") val editorAnnotation: String? = null,
    @SerializedName("productionStatus") val productionStatus: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("ratingMpaa") val ratingMpaa: String? = null,
    @SerializedName("ratingAgeLimits") val ratingAgeLimits: String? = null,
    @SerializedName("hasImax") val hasImax: Boolean? = null,
    @SerializedName("has3D") val has3D: Boolean? = null,
    @SerializedName("startYear") val startYear: Int? = null,
    @SerializedName("endYear") val endYear: Int? = null,
    @SerializedName("serial") val serial: Boolean? = null,
    @SerializedName("shortFilm") val shortFilm: Boolean? = null,
    @SerializedName("completed") val completed: Boolean? = null,
    @SerializedName("genres") val genres: List<NameOnly> = emptyList(),
    @SerializedName("countries") val countries: List<NameOnly> = emptyList()
)

data class NameOnly(
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("country") val country: String? = null
)

data class FilterItem(
    @SerializedName("id") val id: Int,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("country") val country: String? = null
)

data class FiltersResponse(
    @SerializedName("genres") val genres: List<FilterItem> = emptyList(),
    @SerializedName("countries") val countries: List<FilterItem> = emptyList()
)

data class FilmVideosResponse(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("items") val items: List<FilmVideoItem> = emptyList()
)

data class FilmVideoItem(
    @SerializedName("url") val url: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("site") val site: String? = null,
    @SerializedName("official") val official: Boolean? = null
)

/**
 * Трейлер/превью для карточки «Кадры» на странице тайтла (блоки Кинопоиска/Shikimori).
 * Играет только mpvEx: YouTube-поток извлекается через InnerTube при нажатии
 * (YouTubeStreamResolver), Rutube HLS и HLS виджета Кинопоиска кладутся в nativeUrl сразу.
 * Веб-плееры (YouTube-страница, vk, sibnet, Yandex) не открываются вовсе.
 */
data class FilmTrailer(
    /** Страница трейлера на площадке (метаданные; воспроизведение — nativeUrl). */
    val url: String,
    /** Прямой поток (mp4/m3u8), если он известен сразу; null — mpvEx извлечёт при нажатии. */
    val nativeUrl: String? = null,
    val posterUrl: String? = null,
    val title: String? = null,
    /** Заголовки для нативного плеера (UA/Referer отдельных площадок). */
    val nativeHeaders: Map<String, String> = emptyMap(),
    /** Площадка недоступна из РФ без VPN (YouTube) — карточка помечается бейджем VPN. */
    val needsVpn: Boolean = false
)

