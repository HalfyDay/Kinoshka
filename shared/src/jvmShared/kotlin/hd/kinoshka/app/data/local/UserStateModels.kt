package hd.kinoshka.app.data.local

/**
 * Модели пользовательского состояния, общие для Android и desktop.
 * Извлечены из UserStateStore (app), чтобы playback-модели в shared могли на них ссылаться.
 */
enum class UserFilmStatus {
    WATCHING,
    PLANNED,
    COMPLETED,
    REWATCHING,
    ON_HOLD,
    DROPPED
}

data class UserFilmProfile(
    val kinopoiskId: Int,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val ratingText: String?,
    val type: String?,
    val isRussian: Boolean? = null,
    val status: UserFilmStatus?,
    val userRating: Int?,
    val note: String?,
    val watchedSeasons: Int?,
    val watchedEpisodes: Int?,
    val totalEpisodesInSeason: Int?,
    val totalSeasons: Int?,
    val totalEpisodes: Int?,
    val updatedAt: Long
)
