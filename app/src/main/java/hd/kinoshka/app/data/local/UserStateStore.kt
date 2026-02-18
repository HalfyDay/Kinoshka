package hd.kinoshka.app.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmItem
import java.util.Locale

enum class SavedViewMode {
    LIST,
    GRID
}

enum class AppThemeMode {
    CURRENT,
    DARK,
    AMOLED
}

enum class FilmTileSize {
    COMPACT,
    MEDIUM,
    LARGE,
    VERTICAL
}

enum class UserFilmStatus {
    WATCHING,
    PLANNED,
    COMPLETED,
    REWATCHING,
    ON_HOLD,
    DROPPED
}

data class UserPreferences(
    val themeMode: AppThemeMode = AppThemeMode.CURRENT,
    val hideRussianContent: Boolean = false,
    val tileSize: FilmTileSize = FilmTileSize.MEDIUM,
    val discoverTileSize: FilmTileSize? = null,
    val libraryTileSize: FilmTileSize? = null,
    val showFpsCounter: Boolean = false
)

data class HistoryRecord(
    val kinopoiskId: Int,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val ratingText: String?,
    val isRussian: Boolean? = null,
    val viewedAt: Long
)

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

data class LibraryBackup(
    val exportedAt: Long,
    val profileAvatar: String? = null,
    val preferences: UserPreferences? = null,
    val history: List<HistoryRecord>? = null,
    val profiles: List<UserFilmProfile>? = null
)

class UserStateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("kino_user_state", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val historyKey = "history_json"
    private val profileKey = "profiles_json"
    private val viewModeKey = "view_mode"
    private val avatarKey = "profile_avatar"
    private val themeModeKey = "theme_mode"
    private val hideRussianKey = "hide_russian_content"
    private val tileSizeKey = "tile_size" // legacy key for backward compatibility
    private val discoverTileSizeKey = "discover_tile_size"
    private val libraryTileSizeKey = "library_tile_size"
    private val showFpsCounterKey = "show_fps_counter"

    private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun getViewMode(): SavedViewMode {
        return readEnum(viewModeKey, SavedViewMode.LIST)
    }

    fun setViewMode(mode: SavedViewMode) {
        prefs.edit().putString(viewModeKey, mode.name).apply()
    }

    fun getThemeMode(): AppThemeMode {
        return readEnum(themeModeKey, AppThemeMode.CURRENT)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(themeModeKey, mode.name).apply()
    }

    fun isHideRussianContentEnabled(): Boolean {
        return prefs.getBoolean(hideRussianKey, false)
    }

    fun setHideRussianContentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(hideRussianKey, enabled).apply()
    }

    fun getTileSize(): FilmTileSize {
        return readEnum(tileSizeKey, FilmTileSize.MEDIUM)
    }

    fun setTileSize(size: FilmTileSize) {
        prefs.edit().putString(tileSizeKey, size.name).apply()
    }

    fun getDiscoverTileSize(): FilmTileSize {
        val fallback = getTileSize()
        return readEnum(discoverTileSizeKey, fallback)
    }

    fun setDiscoverTileSize(size: FilmTileSize) {
        prefs.edit().putString(discoverTileSizeKey, size.name).apply()
    }

    fun getLibraryTileSize(): FilmTileSize {
        val fallback = getTileSize()
        return readEnum(libraryTileSizeKey, fallback)
    }

    fun setLibraryTileSize(size: FilmTileSize) {
        prefs.edit().putString(libraryTileSizeKey, size.name).apply()
    }

    fun isFpsCounterEnabled(): Boolean {
        return prefs.getBoolean(showFpsCounterKey, false)
    }

    fun setFpsCounterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(showFpsCounterKey, enabled).apply()
    }

    fun getUserPreferences(): UserPreferences {
        return UserPreferences(
            themeMode = getThemeMode(),
            hideRussianContent = isHideRussianContentEnabled(),
            tileSize = getTileSize(),
            discoverTileSize = getDiscoverTileSize(),
            libraryTileSize = getLibraryTileSize(),
            showFpsCounter = isFpsCounterEnabled()
        )
    }

    fun getHistory(): List<HistoryRecord> = readHistory()

    fun getProfiles(): List<UserFilmProfile> = readProfiles()

    fun getProfileAvatar(): String = prefs.getString(avatarKey, "🎬").orEmpty().ifBlank { "🎬" }

    fun setProfileAvatar(value: String) {
        prefs.edit().putString(avatarKey, value.ifBlank { "🎬" }).apply()
    }

    fun getProfile(kinopoiskId: Int): UserFilmProfile? {
        return readProfiles().firstOrNull { it.kinopoiskId == kinopoiskId }
    }

    fun clearHistory() {
        prefs.edit().remove(historyKey).apply()
    }

    fun removeFromHistory(kinopoiskId: Int) {
        val current = readHistory().toMutableList()
        if (current.removeAll { it.kinopoiskId == kinopoiskId }) {
            writeHistory(current)
        }
    }

    fun addFromFilmItem(item: FilmItem) {
        val title = item.nameRu ?: item.nameOriginal ?: "Без названия"
        val subtitle = item.year?.toString()
        val rating = item.ratingKinopoisk?.let { "KP %.1f".format(Locale.US, it) }
        val isRussian = item.isRussianContent()

        upsert(
            HistoryRecord(
                kinopoiskId = item.kinopoiskId,
                title = title,
                subtitle = subtitle,
                posterUrl = item.posterUrlPreview,
                ratingText = rating,
                isRussian = isRussian,
                viewedAt = System.currentTimeMillis()
            )
        )
    }

    fun addFromDetails(item: FilmDetails) {
        val title = item.nameRu ?: item.nameOriginal ?: "Без названия"
        val subtitle = item.year?.toString()
        val rating = item.ratingKinopoisk?.let { "KP %.1f".format(Locale.US, it) }
        val isRussian = item.isRussianContent()

        upsert(
            HistoryRecord(
                kinopoiskId = item.kinopoiskId,
                title = title,
                subtitle = subtitle,
                posterUrl = item.posterUrlPreview ?: item.posterUrl,
                ratingText = rating,
                isRussian = isRussian,
                viewedAt = System.currentTimeMillis()
            )
        )

        val existing = getProfile(item.kinopoiskId)
        upsertProfile(
            UserFilmProfile(
                kinopoiskId = item.kinopoiskId,
                title = title,
                subtitle = subtitle,
                posterUrl = item.posterUrlPreview ?: item.posterUrl,
                ratingText = rating,
                type = item.type,
                isRussian = isRussian,
                status = existing?.status,
                userRating = existing?.userRating,
                note = existing?.note,
                watchedSeasons = existing?.watchedSeasons,
                watchedEpisodes = existing?.watchedEpisodes,
                totalEpisodesInSeason = existing?.totalEpisodesInSeason,
                totalSeasons = existing?.totalSeasons,
                totalEpisodes = existing?.totalEpisodes,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun touch(kinopoiskId: Int) {
        val current = readHistory().toMutableList()
        val index = current.indexOfFirst { it.kinopoiskId == kinopoiskId }
        if (index < 0) return

        val updated = current[index].copy(viewedAt = System.currentTimeMillis())
        current.removeAt(index)
        current.add(0, updated)
        writeHistory(current)
    }

    fun updateProfileFromDetails(
        item: FilmDetails,
        status: UserFilmStatus?,
        userRating: Int?,
        note: String?,
        watchedSeasons: Int?,
        watchedEpisodes: Int?,
        totalEpisodesInSeason: Int?,
        totalSeasons: Int?,
        totalEpisodes: Int?
    ): UserFilmProfile {
        val title = item.nameRu ?: item.nameOriginal ?: "Без названия"
        val subtitle = item.year?.toString()
        val ratingText = item.ratingKinopoisk?.let { "KP %.1f".format(Locale.US, it) }
        val isRussian = item.isRussianContent()
        val profile = UserFilmProfile(
            kinopoiskId = item.kinopoiskId,
            title = title,
            subtitle = subtitle,
            posterUrl = item.posterUrlPreview ?: item.posterUrl,
            ratingText = ratingText,
            type = item.type,
            isRussian = isRussian,
            status = status,
            userRating = userRating,
            note = note?.trim().takeUnless { it.isNullOrBlank() },
            watchedSeasons = watchedSeasons,
            watchedEpisodes = watchedEpisodes,
            totalEpisodesInSeason = totalEpisodesInSeason?.coerceAtLeast(0),
            totalSeasons = totalSeasons?.coerceAtLeast(0),
            totalEpisodes = totalEpisodes?.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis()
        )
        upsertProfile(profile)
        return profile
    }

    private fun upsert(newValue: HistoryRecord) {
        val current = readHistory().toMutableList()
        current.removeAll { it.kinopoiskId == newValue.kinopoiskId }
        current.add(0, newValue)
        writeHistory(current.take(200))
    }

    private fun upsertProfile(newValue: UserFilmProfile) {
        val current = readProfiles().toMutableList()
        current.removeAll { it.kinopoiskId == newValue.kinopoiskId }
        current.add(0, newValue)
        writeProfiles(current.take(500))
    }

    fun exportLibraryJson(): String {
        val backup = LibraryBackup(
            exportedAt = System.currentTimeMillis(),
            profileAvatar = getProfileAvatar(),
            preferences = getUserPreferences(),
            history = readHistory(),
            profiles = readProfiles()
        )
        return prettyGson.toJson(backup)
    }

    fun importLibraryJson(rawJson: String): Result<Unit> {
        return runCatching {
            val backup = gson.fromJson(rawJson, LibraryBackup::class.java)
                ?: error("Файл пустой или поврежден")
            writeHistory(backup.history.orEmpty().take(200))
            writeProfiles(backup.profiles.orEmpty().take(500))
            setProfileAvatar(backup.profileAvatar.orEmpty().ifBlank { "🎬" })
            backup.preferences?.let { preferences ->
                setThemeMode(preferences.themeMode)
                setHideRussianContentEnabled(preferences.hideRussianContent)
                val fallbackTileSize = runCatching { preferences.tileSize }.getOrDefault(FilmTileSize.MEDIUM)
                setTileSize(fallbackTileSize)
                setDiscoverTileSize(preferences.discoverTileSize ?: fallbackTileSize)
                setLibraryTileSize(preferences.libraryTileSize ?: fallbackTileSize)
                setFpsCounterEnabled(preferences.showFpsCounter)
            }
        }
    }

    private fun readHistory(): List<HistoryRecord> {
        val raw = prefs.getString(historyKey, null) ?: return emptyList()
        val type = object : TypeToken<List<HistoryRecord>>() {}.type
        return runCatching {
            gson.fromJson<List<HistoryRecord>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun readProfiles(): List<UserFilmProfile> {
        val raw = prefs.getString(profileKey, null) ?: return emptyList()
        val type = object : TypeToken<List<UserFilmProfile>>() {}.type
        return runCatching {
            gson.fromJson<List<UserFilmProfile>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun writeHistory(value: List<HistoryRecord>) {
        prefs.edit().putString(historyKey, gson.toJson(value)).apply()
    }

    private fun writeProfiles(value: List<UserFilmProfile>) {
        prefs.edit().putString(profileKey, gson.toJson(value)).apply()
    }

    private fun <T : Enum<T>> readEnum(key: String, fallback: T): T {
        return runCatching {
            java.lang.Enum.valueOf(fallback.declaringJavaClass, prefs.getString(key, fallback.name).orEmpty())
        }.getOrDefault(fallback)
    }
}

private fun FilmItem.isRussianContent(): Boolean {
    return countries.any { country ->
        when (country.country?.trim()?.lowercase(Locale("ru"))) {
            "россия", "ссср" -> true
            else -> false
        }
    }
}

private fun FilmDetails.isRussianContent(): Boolean {
    return countries.any { country ->
        when (country.country?.trim()?.lowercase(Locale("ru"))) {
            "россия", "ссср" -> true
            else -> false
        }
    }
}
