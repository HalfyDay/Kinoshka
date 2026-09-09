package hd.kinoshka.app.ui.screens

import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import java.util.Calendar

/**
 * Чистые подсчёты для статистики профиля: серия дней подряд и примерное
 * потраченное время. Платформы (Android/desktop) только рисуют результат.
 */

/** Плюрализация русских существительных: forms = (1, 2-4, 5+). */
fun pluralRu(count: Long, one: String, few: String, many: String): String = when {
    count % 10 == 1L && count % 100 != 11L -> one
    count % 10 in 2..4 && count % 100 !in 12..14 -> few
    else -> many
}

fun pluralDay(count: Long): String = pluralRu(count, "день", "дня", "дней")
fun pluralHour(count: Long): String = pluralRu(count, "час", "часа", "часов")
fun pluralMinute(count: Long): String = pluralRu(count, "минуту", "минуты", "минут")
fun pluralEpisode(count: Long): String = pluralRu(count, "эпизод", "эпизода", "эпизодов")
fun pluralFilm(count: Long): String = pluralRu(count, "фильм", "фильма", "фильмов")

private fun startOfDayMillis(ts: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

/**
 * Серия дней подряд с просмотрами. Считается назад от сегодня; если сегодня
 * просмотров ещё нет, стартует со вчера. 0 — серии нет, 1 — смотрел только сегодня/вчера.
 */
fun List<LibraryUiItem>.calcWatchStreak(nowMillis: Long = System.currentTimeMillis()): Int {
    if (isEmpty()) return 0
    val days = mapNotNullTo(mutableSetOf()) { item ->
        item.viewedAtMillis?.let(::startOfDayMillis)
    }
    if (days.isEmpty()) return 0
    val cursor = Calendar.getInstance().apply { timeInMillis = startOfDayMillis(nowMillis) }
    if (cursor.timeInMillis !in days) cursor.add(Calendar.DAY_OF_YEAR, -1)
    var streak = 0
    while (cursor.timeInMillis in days) {
        streak++
        cursor.add(Calendar.DAY_OF_YEAR, -1)
    }
    return streak
}

/** Итог примерного времени: минуты + из чего сложилось (для честной подписи). */
data class WatchTimeSummary(
    val totalMinutes: Long,
    val episodes: Long,
    val fullLengths: Long
)

private const val ANIME_EPISODE_MINUTES = 24L
private const val SERIES_EPISODE_MINUTES = 45L
private const val FULL_LENGTH_MINUTES = 110L

/**
 * Грубая оценка времени. Эпизоды с известным прогрессом — по минутам за серию,
 * полные метры со статусом «Просмотрено» — по [FULL_LENGTH_MINUTES] за штуку.
 * Это оценка, а не точный учёт: длительности конкретных тайтлов не хранятся.
 */
fun List<LibraryUiItem>.calcWatchTime(): WatchTimeSummary {
    var minutes = 0L
    var episodes = 0L
    var fullLengths = 0L
    forEach { item ->
        val isAnimeSeries = (item.kinopoiskId >= ANIME_ID_OFFSET || item.type == "ANIME") &&
            item.animeKind != "movie"
        val watched = item.watchedEpisodes?.toLong()
        when {
            isAnimeSeries -> {
                val count = watched
                    ?: (if (item.status == UserFilmStatus.COMPLETED) item.totalEpisodes?.toLong() else null)
                    ?: 0L
                episodes += count
                minutes += count * ANIME_EPISODE_MINUTES
            }
            watched != null && watched > 0 -> {
                // Сериал (кино или полный метр с посчитанными сериями).
                episodes += watched
                minutes += watched * SERIES_EPISODE_MINUTES
            }
            item.status == UserFilmStatus.COMPLETED -> {
                fullLengths++
                minutes += FULL_LENGTH_MINUTES
            }
        }
    }
    return WatchTimeSummary(minutes, episodes, fullLengths)
}

/** «3 дн. 5 ч» / «7 ч 20 мин» / «45 мин» / «меньше минуты». */
fun formatWatchTime(totalMinutes: Long): String {
    if (totalMinutes <= 0) return "меньше минуты"
    val days = totalMinutes / 1440
    val hours = (totalMinutes % 1440) / 60
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0) append("$days дн. ")
        if (hours > 0 || days > 0) append("$hours ч")
        if (days == 0L) {
            if (hours > 0) append(" ")
            append("$minutes мин")
        }
    }.trim()
}

/** «4 дня подряд» — подпись бейджа серии. */
fun formatStreak(streak: Int): String = "$streak ${pluralDay(streak.toLong())} подряд"

/**
 * Сегменты стековой полосы статусов (как списки Shikimori).
 * Тайтлы без статуса (только история, без папки) идут отдельным серым
 * сегментом «Без статуса» — иначе сумма легенды не сходится с итогом.
 * Цвет — ARGB, платформы заворачивают в свой Color. Пустые отсеяны.
 */
data class StatusSegment(
    val status: UserFilmStatus?,
    val count: Int,
    val colorArgb: Long,
    val label: String
)

fun List<LibraryUiItem>.statusSegments(): List<StatusSegment> = listOf(
    StatusSegment(UserFilmStatus.PLANNED, count { it.status == UserFilmStatus.PLANNED }, 0xFF8E7CE0, "Запланировано"),
    StatusSegment(UserFilmStatus.WATCHING, count { it.status == UserFilmStatus.WATCHING }, 0xFFE0485C, "Смотрю"),
    StatusSegment(UserFilmStatus.REWATCHING, count { it.status == UserFilmStatus.REWATCHING }, 0xFFEF8E3C, "Пересматриваю"),
    StatusSegment(UserFilmStatus.COMPLETED, count { it.status == UserFilmStatus.COMPLETED }, 0xFF4CAF50, "Просмотрено"),
    StatusSegment(UserFilmStatus.ON_HOLD, count { it.status == UserFilmStatus.ON_HOLD }, 0xFF4A90E2, "Отложено"),
    StatusSegment(UserFilmStatus.DROPPED, count { it.status == UserFilmStatus.DROPPED }, 0xFF8E8E93, "Брошено"),
    StatusSegment(null, count { it.status == null }, 0xFFB0B0B0, "Без статуса")
).filter { it.count > 0 }
