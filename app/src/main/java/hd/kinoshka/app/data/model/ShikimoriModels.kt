package hd.kinoshka.app.data.model

import com.google.gson.annotations.SerializedName
import java.util.Locale

const val ANIME_ID_OFFSET = 100_000_000

data class ShikimoriImage(
    @SerializedName("original") val original: String? = null,
    @SerializedName("preview") val preview: String? = null,
    @SerializedName("x100") val x100: String? = null,
    @SerializedName("x48") val x48: String? = null
) {
    fun getFullOriginalUrl(): String? {
        val path = original ?: preview ?: return null
        return if (path.startsWith("/")) "https://shikimori.io$path" else path
    }

    fun getFullPreviewUrl(): String? {
        val path = preview ?: original ?: return null
        return if (path.startsWith("/")) "https://shikimori.io$path" else path
    }
}

data class ShikimoriStudio(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("filtered_name") val filteredName: String? = null,
    @SerializedName("real") val real: Boolean? = null,
    @SerializedName("image") val image: String? = null
)

data class ShikimoriGenre(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("russian") val russian: String? = null,
    @SerializedName("kind") val kind: String? = null
)

data class ShikimoriAnimeItem(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("russian") val russian: String?,
    @SerializedName("image") val image: ShikimoriImage?,
    @SerializedName("url") val url: String?,
    @SerializedName("kind") val kind: String?,
    @SerializedName("score") val score: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("episodes") val episodes: Int? = 0,
    @SerializedName("episodes_aired") val episodesAired: Int? = 0,
    @SerializedName("aired_on") val airedOn: String? = null,
    @SerializedName("released_on") val releasedOn: String? = null
) {
    fun toFilmItem(): FilmItem {
        val yearInt = airedOn?.take(4)?.toIntOrNull()
        val ratingDouble = score?.toDoubleOrNull()
        val appTitle = russian?.takeIf { it.isNotBlank() } ?: name ?: "Аниме"
        val appOriginalTitle = name?.takeIf { it != appTitle }
        
        val kindStr = when (kind?.lowercase()) {
            "tv" -> "ТВ"
            "movie" -> "Фильм"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special" -> "Спешл"
            else -> null
        }
        val epStr = if (status == "ongoing" && episodesAired != null && episodesAired > 0) {
            "$episodesAired/${if (episodes != null && episodes > 0) episodes else "?"} эп."
        } else if (episodes != null && episodes > 0) {
            "$episodes эп."
        } else null

        val extraInfo = listOfNotNull(kindStr, epStr).joinToString(" • ")

        val countriesList = mutableListOf(NameOnly(country = "Япония"))
        if (extraInfo.isNotBlank()) {
            countriesList.add(NameOnly(country = extraInfo))
        }

        return FilmItem(
            kinopoiskId = id + ANIME_ID_OFFSET,
            nameRu = appTitle,
            nameOriginal = appOriginalTitle,
            posterUrlPreview = image?.getFullOriginalUrl() ?: image?.getFullPreviewUrl(),
            ratingKinopoisk = ratingDouble,
            year = yearInt,
            countries = countriesList
        )
    }
}

data class ShikimoriAnimeDetails(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("russian") val russian: String?,
    @SerializedName("image") val image: ShikimoriImage?,
    @SerializedName("url") val url: String?,
    @SerializedName("kind") val kind: String?,
    @SerializedName("score") val score: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("episodes") val episodes: Int? = 0,
    @SerializedName("episodes_aired") val episodesAired: Int? = 0,
    @SerializedName("aired_on") val airedOn: String? = null,
    @SerializedName("released_on") val releasedOn: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("description_html") val descriptionHtml: String? = null,
    @SerializedName("franchise") val franchise: String? = null,
    @SerializedName("studios") val studios: List<ShikimoriStudio> = emptyList(),
    @SerializedName("genres") val genres: List<ShikimoriGenre> = emptyList(),
    @SerializedName("japanese") val japanese: List<String>? = emptyList(),
    @SerializedName("english") val english: List<String>? = emptyList(),
    @SerializedName("synonyms") val synonyms: List<String>? = emptyList(),
    @SerializedName("license_name_ru") val licenseNameRu: String? = null,
    @SerializedName("licensors") val licensors: List<String>? = emptyList(),
    @SerializedName("next_episode_at") val nextEpisodeAt: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("season") val season: String? = null,
    @SerializedName("rates_scores_stats") val ratesScoresStats: List<ShikimoriStat>? = emptyList(),
    @SerializedName("rates_statuses_stats") val ratesStatusesStats: List<ShikimoriStat>? = emptyList()
) {
    fun toFilmDetails(): FilmDetails {
        val yearInt = airedOn?.take(4)?.toIntOrNull()
        val ratingDouble = score?.toDoubleOrNull()
        val appTitle = russian?.takeIf { it.isNotBlank() } ?: name ?: "Аниме"
        val appOriginalTitle = name?.takeIf { it != appTitle }

        val cleanDescription = description
            ?.replace(Regex("\\[/?(anime|character|manga|person|entry|comment|topic|club|user)=?\\d*]"), "")
            ?.replace(Regex("\\[/?(b|i|u|s|url)]"), "")
            ?.trim()

        val genresList = genres.map { NameOnly(genre = it.russian ?: it.name) }
        val studioNames = studios.mapNotNull { it.name }.joinToString(", ")
        val kindStr = when (kind?.lowercase()) {
            "tv" -> "ТВ"
            "movie" -> "Фильм"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special" -> "Спешл"
            else -> "Аниме"
        }
        val epInfo = if (status == "ongoing" && episodesAired != null && episodesAired > 0) {
            "$episodesAired из ${if (episodes != null && episodes > 0) episodes else "?"} эп."
        } else if (episodes != null && episodes > 0) {
            "$episodes эп."
        } else null

        val countriesList = mutableListOf(NameOnly(country = "Япония"))
        countriesList.add(NameOnly(country = kindStr))
        if (epInfo != null) countriesList.add(NameOnly(country = "Серии: $epInfo"))
        if (studioNames.isNotBlank()) countriesList.add(NameOnly(country = "Студия: $studioNames"))
        if (!franchise.isNullOrBlank()) countriesList.add(NameOnly(country = "Франшиза: ${franchise.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"))

        val mappedType = when (kind?.lowercase()) {
            "movie" -> "FILM"
            "tv", "ova", "ona", "special" -> "TV_SERIES"
            else -> "TV_SERIES"
        }

        val statusFormatted = when (status?.lowercase()) {
            "released" -> "Вышло"
            "ongoing" -> "Онгоинг (выходит)"
            "anons" -> "Анонс"
            else -> status.orEmpty()
        }
        val sloganStr = listOfNotNull(
            "Статус: $statusFormatted",
            epInfo?.let { "Серии: $it" }
        ).joinToString(" • ")

        return FilmDetails(
            kinopoiskId = id + ANIME_ID_OFFSET,
            kinopoiskHDId = null,
            imdbId = null,
            nameRu = appTitle,
            nameEn = null,
            nameOriginal = appOriginalTitle,
            posterUrl = image?.getFullOriginalUrl(),
            posterUrlPreview = image?.getFullPreviewUrl(),
            coverUrl = image?.getFullOriginalUrl(),
            logoUrl = null,
            reviewsCount = null,
            ratingGoodReview = null,
            ratingGoodReviewVoteCount = null,
            ratingKinopoisk = ratingDouble,
            ratingKinopoiskVoteCount = null,
            ratingImdb = null,
            ratingImdbVoteCount = null,
            ratingFilmCritics = null,
            ratingFilmCriticsVoteCount = null,
            ratingAwait = null,
            ratingAwaitCount = null,
            ratingRfCritics = null,
            ratingRfCriticsVoteCount = null,
            webUrl = "https://shikimori.io${url ?: "/animes/$id"}",
            year = yearInt,
            filmLength = duration,
            slogan = sloganStr,
            description = cleanDescription,
            shortDescription = null,
            editorAnnotation = null,
            productionStatus = status,
            type = mappedType,
            ratingMpaa = rating,
            ratingAgeLimits = rating,
            hasImax = false,
            has3D = false,
            startYear = yearInt,
            endYear = releasedOn?.take(4)?.toIntOrNull(),
            serial = mappedType == "TV_SERIES",
            shortFilm = kind?.lowercase() == "special",
            completed = status == "released",
            genres = genresList,
            countries = countriesList
        )
    }

    private fun formatStatus(status: String): String {
        return when (status.lowercase()) {
            "released" -> "Вышло"
            "ongoing" -> "Онгоинг (выходит)"
            "anons" -> "Анонс"
            else -> status
        }
    }
}

data class ShikimoriScreenshot(
    @SerializedName("original") val original: String,
    @SerializedName("preview") val preview: String
) {
    fun getFullOriginalUrl(): String = if (original.startsWith("/")) "https://shikimori.io$original" else original
    fun getFullPreviewUrl(): String = if (preview.startsWith("/")) "https://shikimori.io$preview" else preview
}

data class ShikimoriRelatedItem(
    @SerializedName("relation") val relation: String? = null,
    @SerializedName("relation_russian") val relationRussian: String? = null,
    @SerializedName("anime") val anime: ShikimoriAnimeItem? = null
)

data class ShikimoriStat(
    @SerializedName("name") val name: Any? = null,
    @SerializedName("value") val value: Int? = 0
)

data class ShikimoriRole(
    @SerializedName("roles") val roles: List<String>? = emptyList(),
    @SerializedName("roles_russian") val rolesRussian: List<String>? = emptyList(),
    @SerializedName("character") val character: ShikimoriCharacter? = null
)

data class ShikimoriCharacter(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("russian") val russian: String? = null,
    @SerializedName("image") val image: ShikimoriImage? = null
)
