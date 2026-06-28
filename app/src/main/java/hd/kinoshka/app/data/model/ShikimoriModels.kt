package hd.kinoshka.app.data.model

import com.google.gson.annotations.SerializedName
import java.util.Locale

const val ANIME_ID_OFFSET = 100_000_000

enum class ShikimoriImageQuality {
    ORIGINAL,   // Full size original poster
    PREVIEW,    // Medium preview poster
    SMALL,      // Thumbnail (x96)
    ICON        // Micro icon (x48)
}

data class ShikimoriImage(
    @SerializedName("original") val original: String? = null,
    @SerializedName("preview") val preview: String? = null,
    // Note: Shikimori API returns x96 and x48 only (no x100 field exists)
    @SerializedName("x96") val x96: String? = null,
    @SerializedName("x48") val x48: String? = null
) {
    /** Returns true if all image paths are Shikimori's generic "missing" placeholders */
    val isMissingPlaceholder: Boolean
        get() {
            val paths = listOfNotNull(original, preview, x96, x48)
            return paths.isEmpty() || paths.all { it.contains("missing") }
        }

    fun getUrl(quality: ShikimoriImageQuality = ShikimoriImageQuality.ORIGINAL, animeId: Int? = null): String? {
        val rawPath = when (quality) {
            ShikimoriImageQuality.ORIGINAL -> original ?: preview ?: x96 ?: x48
            ShikimoriImageQuality.PREVIEW  -> preview ?: original ?: x96 ?: x48
            ShikimoriImageQuality.SMALL    -> x96 ?: preview ?: original ?: x48
            ShikimoriImageQuality.ICON     -> x48 ?: x96 ?: preview ?: original
        }

        if ((rawPath == null || rawPath.contains("missing")) && animeId != null && animeId > 0) {
            return "https://smarthard.net/static/animes/$animeId.jpeg"
        }

        if (rawPath == null) return null

        return if (rawPath.startsWith("/")) "https://shikimori.io$rawPath" else rawPath
    }

    fun getFullOriginalUrl(animeId: Int? = null): String? = getUrl(ShikimoriImageQuality.ORIGINAL, animeId)
    fun getFullPreviewUrl(animeId: Int? = null): String? = getUrl(ShikimoriImageQuality.PREVIEW, animeId)
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

data class ShikimoriFranchiseNode(
    @SerializedName("id") val id: Int,
    @SerializedName("date") val date: Long? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("weight") val weight: Int? = null
)

data class ShikimoriFranchiseLink(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("source_id") val sourceId: Int,
    @SerializedName("target_id") val targetId: Int,
    @SerializedName("source") val sourceIndex: Int? = null,
    @SerializedName("target") val targetIndex: Int? = null,
    @SerializedName("weight") val weight: Int? = null,
    @SerializedName("relation") val relation: String? = null
)

data class ShikimoriFranchiseResponse(
    @SerializedName("links") val links: List<ShikimoriFranchiseLink> = emptyList(),
    @SerializedName("nodes") val nodes: List<ShikimoriFranchiseNode> = emptyList(),
    @SerializedName("current_id") val currentId: Int? = null
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
    val displayTitle: String get() = russian?.takeIf { it.isNotBlank() } ?: name ?: "Аниме"
    /** Poster URL — uses Shikimori URL, or falls back to smarthard.net HD poster if missing */
    val posterUrl: String? get() = image?.getFullOriginalUrl(id) ?: image?.getFullPreviewUrl(id) ?: "https://smarthard.net/static/animes/$id.jpeg"

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
            posterUrlPreview = image?.getFullOriginalUrl(id) ?: image?.getFullPreviewUrl(id) ?: "https://smarthard.net/static/animes/$id.jpeg",
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

        val smarthardHdUrl = "https://smarthard.net/static/animes/$id.jpeg"
        val shikiPreviewUrl = image?.getFullPreviewUrl(id)

        // For details page and cover viewer, ALWAYS prioritize high-resolution HD poster from smarthard.net (240KB+) for maximum clarity
        val bestPosterUrl = smarthardHdUrl

        return FilmDetails(
            kinopoiskId = id + ANIME_ID_OFFSET,
            kinopoiskHDId = null,
            imdbId = null,
            nameRu = appTitle,
            nameEn = null,
            nameOriginal = appOriginalTitle,
            posterUrl = bestPosterUrl,
            posterUrlPreview = shikiPreviewUrl ?: bestPosterUrl,
            coverUrl = bestPosterUrl,
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

data class ShikimoriWhoami(
    @SerializedName("id") val id: Int,
    @SerializedName("nickname") val nickname: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("image") val image: ShikimoriImage? = null
)

data class ShikimoriUserRate(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("target_id") val targetId: Int = 0,
    @SerializedName("target_type") val targetType: String = "Anime",
    @SerializedName("score") val score: Int = 0,
    @SerializedName("status") val status: String = "planned",
    @SerializedName("episodes") val episodes: Int = 0,
    @SerializedName("rewatches") val rewatches: Int = 0,
    @SerializedName("text") val text: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("anime") val anime: ShikimoriAnimeItem? = null
) {
    fun getUpdatedEpochMillis(): Long {
        val dateStr = updatedAt ?: createdAt ?: return 0L
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.OffsetDateTime.parse(dateStr).toInstant().toEpochMilli()
            } else {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(dateStr)?.time ?: 0L
            }
        }.getOrDefault(0L)
    }
}

data class ShikimoriCalendarItem(
    @SerializedName("next_episode") val nextEpisode: Int? = null,
    @SerializedName("next_episode_at") val nextEpisodeAt: String? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("anime") val anime: ShikimoriAnimeItem? = null
)

data class ShikimoriTopic(
    @SerializedName("id") val id: Int,
    @SerializedName("topic_title") val topicTitle: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("html_body") val htmlBody: String? = null,
    @SerializedName("html_footer") val htmlFooter: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("comments_count") val commentsCount: Int = 0,
    @SerializedName("user") val user: ShikimoriWhoami? = null,
    @SerializedName("linked") val linked: ShikimoriAnimeItem? = null
)

data class ShikimoriTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String = "Bearer",
    @SerializedName("expires_in") val expiresIn: Long = 0,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("scope") val scope: String? = null,
    @SerializedName("created_at") val createdAt: Long = 0
)

data class ShikimoriCharacterDetails(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("russian") val russian: String? = null,
    @SerializedName("image") val image: ShikimoriImage? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("alt_name") val altName: String? = null,
    @SerializedName("japanese") val japanese: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("description_html") val descriptionHtml: String? = null,
    @SerializedName("animes") val animes: List<ShikimoriAnimeItem>? = emptyList()
) {
    val displayTitle: String get() = russian?.takeIf { it.isNotBlank() } ?: name ?: "Персонаж"
    val imageUrl: String? get() = image?.getFullOriginalUrl() ?: image?.getFullPreviewUrl()
}
