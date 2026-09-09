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
    val updatedAt: Long,
    /**
     * Провенанс оболочки: "anixart" — создана импортом пула (серверное содержимое,
     * пользователь не трогал). Такие оболочки пуши не отправляют никуда, пока их
     * не коснётся явная правка (редактор/плеер/adopt) — иначе restore эхом давил бы
     * сервер (инцидент 09.09: 9 тайтлов уехали из «Завершено» в «Смотрю»).
     * null — локальное/пользовательское, пушится как раньше.
     */
    val importSource: String? = null
)
