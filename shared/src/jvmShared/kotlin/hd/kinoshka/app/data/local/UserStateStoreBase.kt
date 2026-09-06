package hd.kinoshka.app.data.local


import hd.kinoshka.app.util.log.KLog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.AnimeSourceType
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

// UserFilmStatus и UserFilmProfile переехали в shared (jvmShared):
// hd.kinoshka.app.data.local.UserStateModels — пакет тот же, импорты не нужны.

enum class PlayerMode(val displayName: String) {
    DDBB("Веб-плеер"),
    SITE("Открыть сайт"),
    MPVEX("mpvEx (нативный)")
}

data class UserPreferences(
    val themeMode: AppThemeMode = AppThemeMode.CURRENT,
    val hideRussianContent: Boolean = false,
    val tileSize: FilmTileSize = FilmTileSize.MEDIUM,
    val discoverTileSize: FilmTileSize? = null,
    val libraryTileSize: FilmTileSize? = null,
    val showFpsCounter: Boolean = false,
    val contentType: hd.kinoshka.app.ui.screens.ContentType = hd.kinoshka.app.ui.screens.ContentType.FILMS,
    val playerMode: PlayerMode = PlayerMode.MPVEX
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

data class SearchHistoryRecord(
    val query: String,
    val contentType: String,
    val searchedAt: Long
)

/** Usage counters for one playback source (Kodik/AniLiberty/AniLib). */
data class SourceUsage(val count: Int = 0, val lastUsedAt: Long = 0)

/**
 * Usage counters for one dub team across ALL titles. Dubs are keyed by normalized team name —
 * ids differ per anime, names ("Studio Band", "AniLiberty") travel between titles.
 */
data class DubUsage(val count: Int = 0, val lastUsedAt: Long = 0)

/**
 * Сохранённая позиция просмотра одного media-файла. Ключ — стабильный идентификатор
 * ("ks_movie_<kp>", "ks_series_<kp>_s<season>e<ep>", "ks_anime_<key>_e<ep>" — те же схемы,
 * что в Android-плеере): URL потоков ротируются между запусками, поэтому ключуется
 * тайтл/серия, а не адрес.
 */
data class PlaybackPosition(
    val positionSeconds: Double,
    val durationSeconds: Double,
    val updatedAt: Long
)

/**
 * Global preference memory backing the used-first ranking of source/dub lists.
 *
 * [dubs] — глобальная память «эта озвучка играла» (ключ — нормализованное имя команды). Применяется
 * только к тайтлам, у которых ещё нет собственной любимой озвучки.
 * [titleDubs] — per-title память: ключ "<mediaKey>|<dubKey>" (mediaKey: "kp:<kinopoiskId>" /
 * "sh:<shikimoriId>"), значение — счётчики этой озвучки на этом тайтле. Последняя включённая
 * пользователем для просмотра тайтла озвучка — его любимая, она всегда и включается.
 */
data class PlaybackUsageStats(
    val sources: Map<String, SourceUsage> = emptyMap(),
    val dubs: Map<String, DubUsage> = emptyMap(),
    val titleDubs: Map<String, DubUsage> = emptyMap()
) {
    /** Любимая озвучка тайтла: последняя включённая для его просмотра (ключ команды), или null. */
    fun favoriteTitleDubKey(mediaKey: String): String? {
        if (mediaKey.isEmpty()) return null
        val prefix = "$mediaKey|"
        return titleDubs.entries
            .filter { it.key.startsWith(prefix) }
            .maxByOrNull { it.value.lastUsedAt }
            ?.key?.removePrefix(prefix)
    }

    fun titleDubUsage(mediaKey: String, dubKey: String): DubUsage? = titleDubs["$mediaKey|$dubKey"]
}

/**
 * One merged movie voiceover row persisted between launches: [id]/[title] feed the dropdown,
 * [link] is the ready-to-play url (turbo CDN or raw Kodik player page). Pure data for Gson.
 */
data class CachedMovieVoiceover(
    val id: String,
    val title: String,
    val link: String,
    /** AnimeSourceType.name of the row's provider; older caches deserialize as KODIK. */
    val source: String = "KODIK",
    /** "voice" | "orig" | "sub" — kind of track; older caches deserialize as voice. */
    val type: String = "voice"
)

/** Persisted merged voiceover list of one movie; [savedAtMs] bounds url validity. */
data class MovieVoiceoverCache(
    val savedAtMs: Long = 0,
    val rows: List<CachedMovieVoiceover> = emptyList()
)

data class LibraryBackup(
    val exportedAt: Long,
    val profileAvatar: String? = null,
    val preferences: UserPreferences? = null,
    val history: List<HistoryRecord>? = null,
    val profiles: List<UserFilmProfile>? = null,
    /**
     * Снапшот оценок Shikimori: после переустановки сессия мертва (ключ Keystore не бэкапится,
     * EncryptedSharedPreferences не расшифровываются), и без снапшота Shikimori-часть библиотеки
     * не восстанавливается до перелогина. В старых копиях поля нет — Gson даст null, импорт пропустит.
     */
    val shikimoriRates: List<hd.kinoshka.app.data.model.ShikimoriUserRate>? = null,
    val shikimoriUserId: Int = 0
)

data class ShikimoriAnimeCache(    val shikimoriId: Int,
    val name: String?,
    val russian: String?,
    val posterUrl: String?,
    val episodes: Int?,
    val episodesAired: Int?,
    val kind: String?,
    val score: String?,
    val status: String?,
    /** Год выхода (из aired_on): группировка библиотеки по году. Старые кэши — null. */
    val year: Int? = null,
    // Хентай-флаг по жанру/рейтингу Shikimori (вычисляется при дозагрузке деталей оценок).
    // Boolean?, а не Boolean: Gson не применяет Kotlin-дефолты — в старых кэшах поле
    // отсутствует и десериализуется как null.
    val isAdult: Boolean? = null,
    val savedAtMs: Long = System.currentTimeMillis()
) {
    val displayTitle: String get() = russian?.takeIf { it.isNotBlank() } ?: name ?: "Аниме #$shikimoriId"
}

/**
 * Дисковый снапшот оценок Shikimori: библиотека при старте строится до сетевого
 * фетча рейтов, и без него аниме из Shikimori появлялись в разделе с задержкой
 * (сеть + тяжёлая пересборка). Первый кадр — из снапшота, сеть освежает фоном
 * (тот же приём, что дисковый кэш Обзора). Привязка к userId — чтобы после
 * смены аккаунта не мигнуть чужим списком.
 */
data class ShikimoriRatesSnapshot(
    val userId: Int = 0,
    val savedAtMs: Long = 0,
    val rates: List<hd.kinoshka.app.data.model.ShikimoriUserRate> = emptyList()
)

enum class LibrarySortType(val label: String) {
    LAST_VIEWED("По последнему просмотру"),
    DATE_ADDED("По дате добавления"),
    ALPHABETICAL("По алфавиту"),
    RATING("По рейтингу"),
    RELEASE_DATE("По дате выхода")
}

/** Группировка библиотеки по общим признакам: внутри групп сохраняется выбранная сортировка. */
enum class LibraryGroupType(val label: String) {
    NONE("Без группировки"),
    TYPE("По типу"),
    RELEASE_STATUS("По статусу"),
    YEAR("По году"),
    SCORE("По оценке")
}

private const val MAX_PROFILES = 5000
private const val PROFILE_HARD_CEILING = 20_000
private const val MAX_DUB_USAGE_ENTRIES = 100
private const val MAX_TITLE_DUB_USAGE_ENTRIES = 400
private const val MAX_PLAYBACK_POSITION_ENTRIES = 300

/**
 * Пометка «эта озвучка играла» снимается сама: месяц без включения — и запись выпадает из
 * памяти (глобальной и per-title) при первом же чтении/записи. Без TTL память разрастается
 * мусором из давно заброшенных тайтлов и вечно тянет за собой дефолтную озвучку.
 */
private const val DUB_MARK_TTL_MS = 30L * 24 * 60 * 60 * 1000

private fun dubMarkIsFresh(lastUsedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
    lastUsedAt > 0L && now - lastUsedAt <= DUB_MARK_TTL_MS

internal fun UserFilmProfile.isCurated(): Boolean =
    status != null || userRating != null || !note.isNullOrBlank() ||
        (watchedEpisodes ?: 0) > 0 || (watchedSeasons ?: 0) > 0

private fun capProfiles(all: List<UserFilmProfile>): List<UserFilmProfile> {
    if (all.size <= MAX_PROFILES) return all
    val (curated, incidental) = all.partition { it.isCurated() }
    // Never silently evict user-authored entries. If curation alone exceeds the soft cap, keep it
    // all but still bound growth: SharedPreferences re-serializes this entire blob on every write.
    if (curated.size >= MAX_PROFILES) {
        return curated.sortedByDescending { it.updatedAt }.take(PROFILE_HARD_CEILING)
    }
    val keptIncidental = incidental.sortedByDescending { it.updatedAt }.take(MAX_PROFILES - curated.size)
    return (curated + keptIncidental).sortedByDescending { it.updatedAt }
}

open class UserStateStoreBase(private val prefs: KinoPrefs) {
    private companion object {
        // Guards every read-modify-write of the shared blobs across all instances. Must be static:
        // each call site constructs its own UserStateStore, but they all mutate the same prefs file.
        val BLOB_LOCK = Any()
    }

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
    private val preferredQualityKey = "preferred_quality"
    private val shikimoriAnimeCacheKey = "shikimori_anime_cache"
    private val shikimoriRatesSnapshotKey = "shikimori_rates_snapshot"
    private val librarySortKey = "library_sort_type"
    private val librarySortReversedKey = "library_sort_reversed"
    private val showHentaiInLibraryKey = "show_hentai_in_library"
    private val searchHistoryKey = "search_history_json"
    private val overviewFilmCacheKey = "overview_film_cache_json"
    private val overviewAnimeCacheKey = "overview_anime_cache_json"
    private val playbackUsageKey = "playback_usage_json"
    private val playbackPositionsKey = "playback_positions_json"
    private val movieVoiceoverKeyPrefix = "movie_voiceovers_"
    private val detailsCacheKeyPrefix = "details_cache_"

    private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun getPreferredQuality(): String {
        return prefs.getString(preferredQualityKey, "Auto") ?: "Auto"
    }

    fun setPreferredQuality(quality: String) {
        prefs.putString(preferredQualityKey, quality).apply()
    }

    // Shikimori anime cache methods
    fun getShikimoriAnimeCache(): Map<Int, ShikimoriAnimeCache> {
        val raw = prefs.getString(shikimoriAnimeCacheKey, null) ?: return emptyMap()
        val type = object : TypeToken<Map<Int, ShikimoriAnimeCache>>() {}.type
        return runCatching {
            gson.fromJson<Map<Int, ShikimoriAnimeCache>>(raw, type).orEmpty()
        }.getOrDefault(emptyMap())
    }

    fun saveShikimoriAnimeCache(cache: Map<Int, ShikimoriAnimeCache>) {
        prefs.putString(shikimoriAnimeCacheKey, gson.toJson(cache)).apply()
    }

    fun getShikimoriAnimeInfo(shikimoriId: Int): ShikimoriAnimeCache? {
        return getShikimoriAnimeCache()[shikimoriId]
    }

    fun saveShikimoriAnimeInfo(info: ShikimoriAnimeCache) = synchronized(BLOB_LOCK) {        val cache = getShikimoriAnimeCache().toMutableMap()
        cache[info.shikimoriId] = info
        // Keep only last 500 entries
        if (cache.size > 500) {
            val sorted = cache.entries.sortedBy { it.value.savedAtMs }
            val toRemove = sorted.take(cache.size - 500).map { it.key }
            toRemove.forEach { cache.remove(it) }
        }
        saveShikimoriAnimeCache(cache)
    }

    fun getShikimoriRatesSnapshot(): ShikimoriRatesSnapshot {
        val raw = prefs.getString(shikimoriRatesSnapshotKey, null) ?: return ShikimoriRatesSnapshot()
        return runCatching {
            gson.fromJson(raw, ShikimoriRatesSnapshot::class.java) ?: ShikimoriRatesSnapshot()
        }.getOrDefault(ShikimoriRatesSnapshot())
    }

    fun saveShikimoriRatesSnapshot(snapshot: ShikimoriRatesSnapshot) {
        prefs.putString(shikimoriRatesSnapshotKey, gson.toJson(snapshot)).apply()
    }

    /**
     * Last-write-wins сверка локальных профилей с серверными оценками Shikimori. Без неё локальный
     * профиль всегда теньет рейтинг: правка статуса на сайте Shikimori не доезжает до библиотеки,
     * а следующая правка в приложении уезжает на сервер и затирает сайт устаревшим значением.
     * Рейтинг новее профиля — его статус/оценка/заметка/прогресс перезаписывают профиль; профиль
     * новее — не трогается (его значения уже уехали на сервер при сохранении или это прогресс
     * плеера). Возвращает число обновлённых профилей.
     */
    fun adoptShikimoriRates(rates: List<hd.kinoshka.app.data.model.ShikimoriUserRate>): Int {
        if (rates.isEmpty()) return 0
        return synchronized(BLOB_LOCK) {
            val byId = (readProfilesOrNull() ?: return@synchronized 0)
                .associateBy { it.kinopoiskId }
                .toMutableMap()
            var updated = 0
            for (rate in rates) {
                if (rate.targetId <= 0) continue
                val rateTime = rate.getUpdatedEpochMillis()
                if (rateTime <= 0) continue
                val existing = byId[rate.targetId + ANIME_ID_OFFSET] ?: continue
                if (rateTime <= existing.updatedAt) continue
                val status = when (rate.status.lowercase()) {
                    "watching" -> UserFilmStatus.WATCHING
                    "planned" -> UserFilmStatus.PLANNED
                    "completed" -> UserFilmStatus.COMPLETED
                    "rewatching" -> UserFilmStatus.REWATCHING
                    "on_hold" -> UserFilmStatus.ON_HOLD
                    "dropped" -> UserFilmStatus.DROPPED
                    // Неизвестный статус сервера — не рискуем перезаписывать профиль.
                    else -> null
                } ?: continue
                byId[rate.targetId + ANIME_ID_OFFSET] = existing.copy(
                    status = status,
                    userRating = rate.score.takeIf { it > 0 },
                    note = rate.text?.trim()?.takeUnless { it.isBlank() },
                    watchedEpisodes = rate.episodes.takeIf { it > 0 },
                    watchedSeasons = rate.rewatches.takeIf { it > 0 },
                    updatedAt = rateTime
                )
                updated++
            }
            if (updated > 0) writeProfiles(capProfiles(byId.values.toList()))
            updated
        }
    }

    fun getLibrarySortType(): LibrarySortType {
        return readEnum(librarySortKey, LibrarySortType.LAST_VIEWED)
    }

    fun setLibrarySortType(sortType: LibrarySortType) {
        prefs.putString(librarySortKey, sortType.name).apply()
    }

    private val libraryGroupTypeKey = "library_group_type"

    /** Группировка библиотеки по общим признакам (поверх выбранной сортировки). */
    fun getLibraryGroupType(): LibraryGroupType {
        return readEnum(libraryGroupTypeKey, LibraryGroupType.NONE)
    }

    fun setLibraryGroupType(group: LibraryGroupType) {
        prefs.putString(libraryGroupTypeKey, group.name).apply()
    }

    /** «Обратный порядок» переворачивает естественное направление выбранной сортировки. */
    fun isLibrarySortReversed(): Boolean = prefs.getBoolean(librarySortReversedKey, false)

    fun setLibrarySortReversed(reversed: Boolean) {
        prefs.putBoolean(librarySortReversedKey, reversed).apply()
    }

    /** Переключатель «Показывать хентай» в библиотеке: по умолчанию включён (как раньше). */
    fun isHentaiVisibleInLibrary(): Boolean = prefs.getBoolean(showHentaiInLibraryKey, true)

    fun setHentaiVisibleInLibrary(visible: Boolean) {
        prefs.putBoolean(showHentaiInLibraryKey, visible).apply()
    }

    private val playerModeKey = "player_mode"

    fun getPlayerMode(): PlayerMode {
        return readEnum(playerModeKey, PlayerMode.MPVEX)
    }

    fun setPlayerMode(mode: PlayerMode) {
        prefs.putString(playerModeKey, mode.name).apply()
    }

    fun getViewMode(): SavedViewMode {
        return readEnum(viewModeKey, SavedViewMode.LIST)
    }

    fun setViewMode(mode: SavedViewMode) {
        prefs.putString(viewModeKey, mode.name).apply()
    }

    fun getThemeMode(): AppThemeMode {
        return readEnum(themeModeKey, AppThemeMode.CURRENT)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.putString(themeModeKey, mode.name).apply()
    }

    fun isHideRussianContentEnabled(): Boolean {
        return prefs.getBoolean(hideRussianKey, false)
    }

    fun setHideRussianContentEnabled(enabled: Boolean) {
        prefs.putBoolean(hideRussianKey, enabled).apply()
    }

    fun getTileSize(): FilmTileSize {
        return readEnum(tileSizeKey, FilmTileSize.MEDIUM)
    }

    fun setTileSize(size: FilmTileSize) {
        prefs.putString(tileSizeKey, size.name).apply()
    }

    fun getDiscoverTileSize(): FilmTileSize {
        val fallback = getTileSize()
        return readEnum(discoverTileSizeKey, fallback)
    }

    fun setDiscoverTileSize(size: FilmTileSize) {
        prefs.putString(discoverTileSizeKey, size.name).apply()
    }

    fun getLibraryTileSize(): FilmTileSize {
        val fallback = getTileSize()
        return readEnum(libraryTileSizeKey, fallback)
    }

    fun setLibraryTileSize(size: FilmTileSize) {
        prefs.putString(libraryTileSizeKey, size.name).apply()
    }

    fun isFpsCounterEnabled(): Boolean {
        return prefs.getBoolean(showFpsCounterKey, false)
    }

    fun setFpsCounterEnabled(enabled: Boolean) {
        prefs.putBoolean(showFpsCounterKey, enabled).apply()
    }

    fun getSavedContentType(): hd.kinoshka.app.ui.screens.ContentType {
        val name = prefs.getString("saved_content_type", null) ?: return hd.kinoshka.app.ui.screens.ContentType.FILMS
        return runCatching { hd.kinoshka.app.ui.screens.ContentType.valueOf(name) }.getOrDefault(hd.kinoshka.app.ui.screens.ContentType.FILMS)
    }

    fun setSavedContentType(type: hd.kinoshka.app.ui.screens.ContentType) {
        prefs.putString("saved_content_type", type.name).apply()
    }

    fun getUserPreferences(): UserPreferences {
        return UserPreferences(
            themeMode = getThemeMode(),
            hideRussianContent = isHideRussianContentEnabled(),
            tileSize = getTileSize(),
            discoverTileSize = getDiscoverTileSize(),
            libraryTileSize = getLibraryTileSize(),
            showFpsCounter = isFpsCounterEnabled(),
            contentType = getSavedContentType(),
            playerMode = getPlayerMode()
        )
    }

    fun getHistory(): List<HistoryRecord> = readHistory()

    fun getProfiles(): List<UserFilmProfile> = readProfiles()

    fun getProfileAvatar(): String = prefs.getString(avatarKey, "🎬").orEmpty().ifBlank { "🎬" }

    fun setProfileAvatar(value: String) {
        prefs.putString(avatarKey, value.ifBlank { "🎬" }).apply()
    }

    fun getProfile(kinopoiskId: Int): UserFilmProfile? {
        return readProfiles().firstOrNull { it.kinopoiskId == kinopoiskId }
    }

    /**
     * Быстрая пометка статуса без деталей — кнопка «В планах» во фиде.
     * Поверх существующего профиля, если он уже есть; status=null снимает пометку.
     */
    fun setFeedQuickStatus(
        kinopoiskId: Int,
        title: String,
        posterUrl: String?,
        status: UserFilmStatus?
    ) = synchronized(BLOB_LOCK) {
        val existing = readProfilesOrNull()?.firstOrNull { it.kinopoiskId == kinopoiskId }
        val base = existing ?: UserFilmProfile(
            kinopoiskId = kinopoiskId,
            title = title,
            subtitle = null,
            posterUrl = posterUrl,
            ratingText = null,
            type = null,
            status = null,
            userRating = null,
            note = null,
            watchedSeasons = null,
            watchedEpisodes = null,
            totalEpisodesInSeason = null,
            totalSeasons = null,
            totalEpisodes = null,
            updatedAt = System.currentTimeMillis()
        )
        upsertProfile(base.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    fun clearHistory() {
        prefs.remove(historyKey).apply()
    }

    fun removeFromHistory(kinopoiskId: Int) = synchronized(BLOB_LOCK) {
        val current = readHistory().toMutableList()
        if (current.removeAll { it.kinopoiskId == kinopoiskId }) {
            writeHistory(current)
        }
    }

    fun addFromFilmItem(item: FilmItem) = synchronized(BLOB_LOCK) {
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

    /**
     * Seeds/refreshes the library profile from details metadata WITHOUT touching history.
     * Merely pressing "Watch" must not surface the title in any library folder — it becomes
     * visible only after real playback is committed ([commitRealPlayback]).
     *
     * [seed] carries the effective profile (e.g. rebuilt from the Shikimori rate) for titles
     * whose local profile is missing or a statusless husk: without it the stored profile would
     * shadow the server-side status/rating/progress everywhere the profile is read directly.
     * The `updatedAt` of an existing profile is preserved — pressing "Watch" is not a user
     * edit, and bumping it floated the title to the top of the DATE_ADDED library sort.
     */
    fun addFromDetails(item: FilmDetails, seed: UserFilmProfile? = null) = synchronized(BLOB_LOCK) {
        val title = item.nameRu ?: item.nameOriginal ?: "Без названия"
        val subtitle = item.year?.toString()
        val rating = item.ratingKinopoisk?.let { "KP %.1f".format(Locale.US, it) }
        val isRussian = item.isRussianContent()

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
                status = existing?.status ?: seed?.status,
                userRating = existing?.userRating ?: seed?.userRating,
                note = existing?.note ?: seed?.note,
                watchedSeasons = existing?.watchedSeasons ?: seed?.watchedSeasons,
                watchedEpisodes = existing?.watchedEpisodes ?: seed?.watchedEpisodes,
                totalEpisodesInSeason = existing?.totalEpisodesInSeason ?: seed?.totalEpisodesInSeason,
                totalSeasons = existing?.totalSeasons ?: seed?.totalSeasons,
                totalEpisodes = existing?.totalEpisodes ?: seed?.totalEpisodes,
                updatedAt = existing?.updatedAt
                    ?: seed?.updatedAt?.takeIf { it > 0 }
                    ?: System.currentTimeMillis()
            )
        )
    }

    fun touch(kinopoiskId: Int) {
        // Block body + inner synchronized: the verbatim body early-returns, and Kotlin forbids
        // `return` in an expression-body function. synchronized is inline, so the non-local return works.
        synchronized(BLOB_LOCK) {
            val current = readHistory().toMutableList()
            val index = current.indexOfFirst { it.kinopoiskId == kinopoiskId }
            if (index < 0) return

            val updated = current[index].copy(viewedAt = System.currentTimeMillis())
            current.removeAt(index)
            current.add(0, updated)
            writeHistory(current)
        }
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
        totalEpisodes: Int?,
        // Editors that reconstruct FilmDetails locally (quick-progress sheet over library/
        // discover tiles) carry no countries list — without the override their derived
        // isRussian=false would clobber the stored flag on every save.
        isRussianOverride: Boolean? = null
    ): UserFilmProfile {
        // The whole read-modify-write must hold the lock, otherwise a concurrent writer on another
        // thread re-serializes a stale profile list and erases this edit. Returning the synchronized
        // block's own value (rather than a non-local return from inside it) keeps the flow obvious.
        return synchronized(BLOB_LOCK) {
            val title = item.nameRu ?: item.nameOriginal ?: "Без названия"
            val subtitle = item.year?.toString()
            val ratingText = item.ratingKinopoisk?.let { "★ %.1f".format(Locale.US, it) }
            val isRussian = isRussianOverride ?: item.isRussianContent()

            val existing = getProfile(item.kinopoiskId)
            val finalTotalEpisodes = totalEpisodes ?: existing?.totalEpisodes
            val finalTotalSeasons = totalSeasons ?: existing?.totalSeasons
            val isAnime = item.kinopoiskId >= ANIME_ID_OFFSET || item.type == "ANIME"
            // For anime this field is displayed as the number of rewatches.  Starting a
            // completed title again from the "Watching" state is a rewatch even if its
            // episode progress is reset for the new run.
            val isStartingAnimeRewatch = isAnime &&
                existing?.status == UserFilmStatus.COMPLETED &&
                status == UserFilmStatus.WATCHING

            val finalWatchedEpisodes = if (status == UserFilmStatus.COMPLETED) {
                finalTotalEpisodes?.coerceAtLeast(watchedEpisodes ?: 0) ?: (watchedEpisodes ?: 1)
            } else {
                // Null means "the editor has no opinion about this field" (non-series types submit
                // null), not "clear it" — keep whatever the player last recorded.
                watchedEpisodes ?: existing?.watchedEpisodes
            }

            val finalWatchedSeasons = if (status == UserFilmStatus.COMPLETED) {
                // For series, COMPLETED means all seasons watched.
                // However, we don't want to force it if it's being used as "Repeats" in UI or if not applicable.
                if (watchedSeasons != null && watchedSeasons > 0) {
                    finalTotalSeasons?.coerceAtLeast(watchedSeasons) ?: watchedSeasons
                } else {
                    // If it's a TV series and NOT anime (where watchedSeasons is "Repeats"), set it.
                    if (item.type == "TV_SERIES" && !isAnime) {
                        finalTotalSeasons?.coerceAtLeast(1) ?: watchedSeasons
                    } else {
                        watchedSeasons
                    }
                }
            } else if (isStartingAnimeRewatch) {
                // Do not carry the old episode position into a fresh viewing, but retain the
                // rewatch fact even when the editor submits a cleared/null progress value.
                maxOf(existing.watchedSeasons ?: 0, 1)
            } else {
                watchedSeasons ?: existing?.watchedSeasons
            }

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
                watchedSeasons = finalWatchedSeasons,
                watchedEpisodes = finalWatchedEpisodes,
                totalEpisodesInSeason = (totalEpisodesInSeason ?: existing?.totalEpisodesInSeason)?.coerceAtLeast(0),
                totalSeasons = finalTotalSeasons?.coerceAtLeast(0),
                totalEpisodes = finalTotalEpisodes?.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis()
            )
            upsertProfile(profile)
            profile
        }
    }

    fun updateSeriesProgress(kinopoiskId: Int, seasonNumber: Int, episodeNumber: Int, finished: Boolean = false) {
        // Block body + inner synchronized: the verbatim body early-returns when no profile exists.
        synchronized(BLOB_LOCK) {
            val existing = readProfiles().firstOrNull { it.kinopoiskId == kinopoiskId } ?: return
            val status = when {
                // User explicitly dropped/put the title on hold — playback must not override that.
                existing.status == UserFilmStatus.DROPPED || existing.status == UserFilmStatus.ON_HOLD -> existing.status
                finished -> UserFilmStatus.COMPLETED
                existing.status == UserFilmStatus.REWATCHING || existing.status == UserFilmStatus.COMPLETED -> existing.status
                else -> UserFilmStatus.WATCHING
            }
            upsertProfile(
                existing.copy(
                    watchedSeasons = seasonNumber,
                    watchedEpisodes = episodeNumber,
                    status = status,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Marks a movie (or any single-unit title) as fully watched: moves it to COMPLETED so it lands
     * in the library folder matching what actually happened in the player. Titles the user
     * explicitly dropped or put on hold are left untouched.
     */
    fun markTitleWatched(kinopoiskId: Int) {
        synchronized(BLOB_LOCK) {
            val existing = readProfiles().firstOrNull { it.kinopoiskId == kinopoiskId } ?: return
            if (existing.status == UserFilmStatus.COMPLETED) return
            val status = when (existing.status) {
                UserFilmStatus.DROPPED, UserFilmStatus.ON_HOLD -> existing.status
                else -> UserFilmStatus.COMPLETED
            }
            upsertProfile(existing.copy(status = status, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Full library exit: drops BOTH the profile and the history entry. Saving the progress editor
     * with an explicitly cleared (null) status means "remove from library" — otherwise a statusless
     * husk would keep surfacing in the История tab despite having no progress at all.
     */
    fun removeFromLibrary(kinopoiskId: Int) = synchronized(BLOB_LOCK) {
        readProfilesOrNull()?.let { current ->
            val filtered = current.filterNot { it.kinopoiskId == kinopoiskId }
            if (filtered.size != current.size) writeProfiles(filtered)
        }
        val history = readHistory()
        if (history.any { it.kinopoiskId == kinopoiskId }) {
            writeHistory(history.filterNot { it.kinopoiskId == kinopoiskId })
        }
    }

    fun updateWatchedEpisode(
        shikimoriId: Int,
        animeTitle: String,
        episodeNum: Int,
        totalEpisodes: Int
    ) {
        updateWatchedEpisodeByKey(shikimoriId + ANIME_ID_OFFSET, animeTitle, episodeNum, totalEpisodes)
    }

    /**
     * Same as [updateWatchedEpisode] but keyed by an explicit library id. Titles opened outside
     * the Shikimori section (Kinopoisk search hits tagged as anime by genre) have no shikimori
     * mapping — their profiles live under the raw Kinopoisk id, and playback must keep writing
     * there instead of silently dropping progress.
     */
    fun updateWatchedEpisodeByKey(
        kinopoiskId: Int,
        animeTitle: String,
        episodeNum: Int,
        totalEpisodes: Int,
        allowComplete: Boolean = true
    ) {
        // Block body + inner synchronized: the verbatim body aborts early on an unreadable blob.
        synchronized(BLOB_LOCK) {
            val current = readProfilesOrNull()?.toMutableList() ?: return
            val index = current.indexOfFirst { it.kinopoiskId == kinopoiskId }
            val existing = if (index >= 0) current[index] else null

            val currentStatus = existing?.status
            val newStatus = when {
                // User explicitly dropped/put the title on hold — playback must not override that.
                currentStatus == UserFilmStatus.DROPPED || currentStatus == UserFilmStatus.ON_HOLD -> currentStatus
                // Mid-episode progress commits must never complete a run: reaching the last
                // episode's 5th minute is not "watched through". A completed title being
                // re-watched stays completed until a watched-through commit says otherwise.
                !allowComplete -> when (currentStatus) {
                    UserFilmStatus.REWATCHING, UserFilmStatus.COMPLETED -> currentStatus
                    else -> UserFilmStatus.WATCHING
                }
                totalEpisodes > 0 && episodeNum >= totalEpisodes -> UserFilmStatus.COMPLETED
                currentStatus == UserFilmStatus.REWATCHING -> currentStatus
                else -> UserFilmStatus.WATCHING
            }

            // The player's episode list is not canonical (it can be shorter than the real run while
            // a season is airing). Never let it shrink the stored total — that made the progress
            // percentage and the watched checkmarks disagree.
            val mergedTotal = maxOf(
                existing?.totalEpisodes ?: 0,
                totalEpisodes.takeIf { it > 0 } ?: 0
            ).takeIf { it > 0 } ?: existing?.totalEpisodes

            val updated = if (existing != null) {
                existing.copy(
                    watchedEpisodes = episodeNum,
                    totalEpisodes = mergedTotal,
                    status = newStatus,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                UserFilmProfile(
                    kinopoiskId = kinopoiskId,
                    title = animeTitle,
                    subtitle = null,
                    posterUrl = null,
                    ratingText = null,
                    type = "ANIME",
                    isRussian = false,
                    status = newStatus,
                    userRating = null,
                    note = null,
                    watchedSeasons = null,
                    watchedEpisodes = episodeNum,
                    totalEpisodesInSeason = null,
                    totalSeasons = null,
                    totalEpisodes = totalEpisodes.takeIf { it > 0 },
                    updatedAt = System.currentTimeMillis()
                )
            }

            if (index >= 0) {
                current[index] = updated
            } else {
                current.add(0, updated)
            }
            writeProfiles(capProfiles(current))
        }
    }

    private fun upsert(newValue: HistoryRecord) = synchronized(BLOB_LOCK) {
        val current = readHistory().toMutableList()
        current.removeAll { it.kinopoiskId == newValue.kinopoiskId }
        current.add(0, newValue)
        writeHistory(current.take(200))
    }

    private fun upsertProfile(newValue: UserFilmProfile) {
        // Block body + inner synchronized: the verbatim body aborts early on an unreadable blob.
        synchronized(BLOB_LOCK) {
            // Abort rather than clobber: if the stored blob is unreadable, writing a fresh single-entry
            // list would replace the whole library with just this one title.
            val current = readProfilesOrNull()?.toMutableList() ?: return
            current.removeAll { it.kinopoiskId == newValue.kinopoiskId }
            current.add(0, newValue)
            writeProfiles(capProfiles(current))
        }
    }

    fun exportLibraryJson(): String {
        val ratesSnapshot = getShikimoriRatesSnapshot()
        val backup = LibraryBackup(
            exportedAt = System.currentTimeMillis(),
            profileAvatar = getProfileAvatar(),
            preferences = getUserPreferences(),
            history = readHistory(),
            profiles = readProfiles(),
            shikimoriRates = ratesSnapshot.rates.takeIf { it.isNotEmpty() },
            shikimoriUserId = ratesSnapshot.userId
        )
        return prettyGson.toJson(backup)
    }

    fun importLibraryJson(rawJson: String): Result<Unit> = synchronized(BLOB_LOCK) {
        runCatching {
            val backup = gson.fromJson(rawJson, LibraryBackup::class.java)
                ?: error("Файл пустой или поврежден")
            writeHistory(backup.history.orEmpty().take(200))
            writeProfiles(capProfiles(backup.profiles.orEmpty()))
            setProfileAvatar(backup.profileAvatar.orEmpty().ifBlank { "🎬" })
            backup.shikimoriRates?.takeIf { it.isNotEmpty() }?.let { rates ->
                saveShikimoriRatesSnapshot(
                    ShikimoriRatesSnapshot(
                        userId = backup.shikimoriUserId,
                        savedAtMs = System.currentTimeMillis(),
                        rates = rates
                    )
                )
            }
            backup.preferences?.let { preferences ->
                setThemeMode(preferences.themeMode)
                setHideRussianContentEnabled(preferences.hideRussianContent)
                val fallbackTileSize = runCatching { preferences.tileSize }.getOrDefault(FilmTileSize.MEDIUM)
                setTileSize(fallbackTileSize)
                setDiscoverTileSize(preferences.discoverTileSize ?: fallbackTileSize)
                setLibraryTileSize(preferences.libraryTileSize ?: fallbackTileSize)
                setFpsCounterEnabled(preferences.showFpsCounter)
            }
            Unit
        }
    }

    private fun readHistory(): List<HistoryRecord> {
        val raw = prefs.getString(historyKey, null) ?: return emptyList()
        val type = object : TypeToken<List<HistoryRecord>>() {}.type
        return runCatching {
            gson.fromJson<List<HistoryRecord>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Parses the stored profile list, or returns `null` when the blob exists but cannot be parsed.
     *
     * The distinction matters: every mutation is a read-modify-write, so treating a parse failure as
     * "empty library" would make the very next write persist that emptiness and destroy the library
     * permanently. Writers must abort on `null`; read-only callers can fall back to an empty list.
     */
    private fun readProfilesOrNull(): List<UserFilmProfile>? {
        val raw = prefs.getString(profileKey, null) ?: return emptyList()
        val type = object : TypeToken<List<UserFilmProfile>>() {}.type
        return runCatching {
            gson.fromJson<List<UserFilmProfile>>(raw, type).orEmpty()
        }.getOrNull()
    }

    private fun readProfiles(): List<UserFilmProfile> = readProfilesOrNull().orEmpty()

    private fun writeHistory(value: List<HistoryRecord>) {
        // commit() mirrors writeProfiles(): addFromDetails writes history+profile as a pair, and an
        // async apply() here could land after a crash, leaving history without a profile — which
        // then shows up as a phantom WATCHING entry in the library.
        prefs.putString(historyKey, gson.toJson(value)).commit()
    }

    private fun writeProfiles(value: List<UserFilmProfile>) {
        // commit() rather than apply(): the library is the one piece of state users cannot recreate,
        // and apply() only schedules the disk write. A crash immediately afterwards — the app was
        // SIGKILLed right after an episode pick — could lose the edit, which is how titles silently
        // disappeared from the library.
        prefs.putString(profileKey, gson.toJson(value)).commit()
    }

    /**
     * Forces every queued apply() on this SharedPreferences instance to disk. commit() writes the whole
     * current map synchronously, so it subsumes any pending async write. BLOCKING — never call from main.
     */
    fun flushToDisk() {
        runCatching {
            prefs.putLong("durability_flush_counter", System.currentTimeMillis()).commit()
        }.onFailure { KLog.e("UserStateStore", "flushToDisk failed", it) }
    }

    // ---- Playback usage memory (sources & dubs the user actually launches) ----

    fun getPlaybackUsage(): PlaybackUsageStats {
        val raw = prefs.getString(playbackUsageKey, null) ?: return PlaybackUsageStats()
        val stats = runCatching { gson.fromJson(raw, PlaybackUsageStats::class.java) }
            .getOrNull() ?: return PlaybackUsageStats()
        return dropExpiredDubs(stats)
    }

    /** TTL-очистка: пометки озвучек, которые давно не включались, из памяти уходят. */
    private fun dropExpiredDubs(stats: PlaybackUsageStats): PlaybackUsageStats {
        val dubs = stats.dubs.filterValues { dubMarkIsFresh(it.lastUsedAt) }
        val titleDubs = stats.titleDubs.filterValues { dubMarkIsFresh(it.lastUsedAt) }
        return if (dubs.size == stats.dubs.size && titleDubs.size == stats.titleDubs.size) stats
        else stats.copy(dubs = dubs, titleDubs = titleDubs)
    }

    fun recordSourceUsage(source: AnimeSourceType) = editPlaybackUsage { stats ->
        val current = stats.sources[source.name] ?: SourceUsage()
        stats.copy(
            sources = stats.sources + (
                source.name to SourceUsage(
                    count = current.count + 1,
                    lastUsedAt = System.currentTimeMillis()
                )
                )
        )
    }

    fun recordDubUsage(title: String) = editPlaybackUsage { stats ->
        val key = title.trim().lowercase()
        if (key.isEmpty()) return@editPlaybackUsage stats
        val current = stats.dubs[key] ?: DubUsage()
        stats.copy(
            dubs = stats.dubs + (
                key to DubUsage(
                    count = current.count + 1,
                    lastUsedAt = System.currentTimeMillis()
                )
                )
        )
    }

    /**
     * Per-title память: [dubKey] (уже свёрнутый splitDubTrack-ключом, lowercase) включили для
     * просмотра тайтла [mediaKey] — она становится его любимой озвучкой.
     */
    fun recordTitleDubUsage(mediaKey: String, dubKey: String) = editPlaybackUsage { stats ->
        val mk = mediaKey.trim()
        val dk = dubKey.trim().lowercase()
        if (mk.isEmpty() || dk.isEmpty()) return@editPlaybackUsage stats
        val key = "$mk|$dk"
        val current = stats.titleDubs[key] ?: DubUsage()
        stats.copy(
            titleDubs = stats.titleDubs + (
                key to DubUsage(
                    count = current.count + 1,
                    lastUsedAt = System.currentTimeMillis()
                )
                )
        )
    }

    private fun editPlaybackUsage(edit: (PlaybackUsageStats) -> PlaybackUsageStats) = synchronized(BLOB_LOCK) {
        val updated = edit(dropExpiredDubs(getPlaybackUsage()))
        // Bound growth: numeric Kodik labels accumulate fast across titles — keep the freshest.
        val capped = updated.copy(
            dubs = updated.dubs.entries
                .sortedByDescending { it.value.lastUsedAt }
                .take(MAX_DUB_USAGE_ENTRIES)
                .associate { it.toPair() },
            titleDubs = updated.titleDubs.entries
                .sortedByDescending { it.value.lastUsedAt }
                .take(MAX_TITLE_DUB_USAGE_ENTRIES)
                .associate { it.toPair() }
        )
        prefs.putString(playbackUsageKey, gson.toJson(capped)).apply()
    }

    // ---- Resume-позиции (cross-session playback state, ключ — стабильный media-идентификатор) ----

    private fun readPlaybackPositions(): Map<String, PlaybackPosition> {
        val raw = prefs.getString(playbackPositionsKey, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, PlaybackPosition>>() {}.type
        return runCatching { gson.fromJson<Map<String, PlaybackPosition>>(raw, type) }
            .getOrNull().orEmpty()
    }

    /** Сохранённая позиция [identifier]'а либо null (нет записи / досмотрено / позиция < 5 c). */
    fun getPlaybackPosition(identifier: String): Double? {
        val id = identifier.trim()
        if (id.isEmpty()) return null
        val entry = readPlaybackPositions()[id] ?: return null
        return entry.positionSeconds.takeIf { it > 5.0 }
    }

    /**
     * Сохраняет позицию просмотра. Досмотренный файл (позиция у конца) запись обнуляет —
     * resume на последней секунде не нужен. Записи ограничены freshest-first, как даб-память.
     */
    fun savePlaybackPosition(identifier: String, positionSeconds: Double, durationSeconds: Double) {
        val id = identifier.trim()
        if (id.isEmpty() || positionSeconds < 5.0) return
        synchronized(BLOB_LOCK) {
            val current = readPlaybackPositions().toMutableMap()
            if (durationSeconds > 1.0 && positionSeconds >= durationSeconds - 10.0) {
                current.remove(id)
            } else {
                current[id] = PlaybackPosition(positionSeconds, durationSeconds, System.currentTimeMillis())
            }
            val capped = current.entries
                .sortedByDescending { it.value.updatedAt }
                .take(MAX_PLAYBACK_POSITION_ENTRIES)
                .associate { it.toPair() }
            prefs.putString(playbackPositionsKey, gson.toJson(capped)).apply()
        }
    }

    // ---- Merged movie voiceover list cache (stable dropdown across launches) ----

    fun getMovieVoiceoverCache(key: String): MovieVoiceoverCache? {
        val raw = prefs.getString(movieVoiceoverKeyPrefix + key, null) ?: return null
        return runCatching { gson.fromJson(raw, MovieVoiceoverCache::class.java) }.getOrNull()
    }

    fun saveMovieVoiceoverCache(key: String, cache: MovieVoiceoverCache) {
        if (cache.rows.isEmpty()) return
        prefs.putString(movieVoiceoverKeyPrefix + key, gson.toJson(cache)).apply()
    }

    // Дисковый кэш карточки тайтла (FilmDetails): страница открывается офлайн, чтобы
    // нажать Смотреть и сыграть скачанные серии. Не разрушается Gson-ом: у FilmDetails
    // все поля либо @SerializedName-nullable, либо примитивы.
    fun getDetailsCache(id: Int): FilmDetails? {
        val raw = prefs.getString(detailsCacheKeyPrefix + id, null) ?: return null
        return runCatching { gson.fromJson(raw, FilmDetails::class.java) }.getOrNull()
    }

    fun saveDetailsCache(id: Int, details: FilmDetails) {
        if (id <= 0) return
        synchronized(BLOB_LOCK) {
            prefs.putString(detailsCacheKeyPrefix + id, gson.toJson(details)).apply()
        }
    }

    /**
     * Marks that the user REALLY watched this title (called once ≥5 minutes of playback accrued).
     * Flips a fresh/planned profile to WATCHING (explicit Dropped/On-hold/Completed/rewatch
     * statuses stay), and adds the history entry the library's "Смотрю"/"История" views need.
     * Metadata comes from the seeded profile; titles never pressed "Watch" on have no profile
     * and are silently skipped until some screen seeds one.
     */
    fun commitRealPlayback(kinopoiskId: Int): Unit = synchronized(BLOB_LOCK) {
        val now = System.currentTimeMillis()
        val profile = readProfiles().firstOrNull { it.kinopoiskId == kinopoiskId } ?: return
        if (profile.status == null || profile.status == UserFilmStatus.PLANNED) {
            upsertProfile(profile.copy(status = UserFilmStatus.WATCHING, updatedAt = now))
        }
        upsert(
            HistoryRecord(
                kinopoiskId = kinopoiskId,
                title = profile.title,
                subtitle = profile.subtitle,
                posterUrl = profile.posterUrl,
                ratingText = profile.ratingText,
                isRussian = profile.isRussian,
                viewedAt = now
            )
        )
    }

    // ---- Search query history ----
    fun getSearchHistory(): List<SearchHistoryRecord> {
        val raw = prefs.getString(searchHistoryKey, null) ?: return emptyList()
        val type = object : TypeToken<List<SearchHistoryRecord>>() {}.type
        return runCatching { gson.fromJson<List<SearchHistoryRecord>>(raw, type).orEmpty() }
            .getOrDefault(emptyList())
    }

    fun addSearchQuery(query: String, contentType: String) {
        // Block body + inner synchronized: the verbatim body early-returns on a blank query.
        synchronized(BLOB_LOCK) {
            val clean = query.trim()
            if (clean.isBlank()) return
            val current = getSearchHistory().toMutableList()
            // Dedup by query+contentType, most-recent-first, cap at 20.
            current.removeAll { it.query.equals(clean, ignoreCase = true) && it.contentType == contentType }
            current.add(0, SearchHistoryRecord(clean, contentType, System.currentTimeMillis()))
            prefs.putString(searchHistoryKey, gson.toJson(current.take(20))).apply()
        }
    }

    fun removeSearchQuery(query: String, contentType: String) = synchronized(BLOB_LOCK) {
        val current = getSearchHistory().toMutableList()
        current.removeAll { it.query.equals(query, ignoreCase = true) && it.contentType == contentType }
        prefs.putString(searchHistoryKey, gson.toJson(current)).apply()
    }

    fun clearSearchHistory() {
        prefs.remove(searchHistoryKey).apply()
    }

    // ---- Overview sections disk cache (stale-while-revalidate) ----
    // Лента Обзора рисуется из этого кэша мгновенно на холодном старте, сеть лишь
    // освежает фоном. Без него каждый рестарт = ~30 HTTP + скелетон на секунды.
    data class OverviewBranchCache(
        val sections: List<hd.kinoshka.app.ui.screens.OverviewSection> = emptyList(),
        val hero: List<FilmItem> = emptyList(),
        val savedAt: Long = 0L
    )

    private data class OverviewSectionDto(
        val id: String,
        val title: String,
        val items: List<FilmItem> = emptyList(),
        val seeAll: String? = null
    )

    private data class OverviewBranchDto(
        val sections: List<OverviewSectionDto> = emptyList(),
        val hero: List<FilmItem> = emptyList(),
        val savedAt: Long = 0L
    )

    private fun encodeSeeAll(seeAll: hd.kinoshka.app.ui.screens.OverviewSeeAll?): String? = when (seeAll) {
        null -> null
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.DiscoverCategoryTarget -> "cat|${seeAll.category.name}"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.FilmPopular -> "fpop"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.FilmGenreTarget -> "fg|${seeAll.genreId}|${seeAll.genreName}"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.FilmFresh -> "fresh"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeGenreTarget -> "ag|${seeAll.genreId}|${seeAll.genreName}"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeKindTarget -> "ak|${seeAll.kind}|${seeAll.title}"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeSeasonTarget -> "as|${seeAll.season}|${seeAll.title}|${seeAll.order}"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeOngoing -> "ao"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeOnAir -> "aon"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeRanked -> "ar"
        is hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimePopular -> "apop"
    }

    private fun decodeSeeAll(raw: String?): hd.kinoshka.app.ui.screens.OverviewSeeAll? {
        if (raw.isNullOrEmpty()) return null
        val p = raw.split("|")
        return runCatching {
            when (p[0]) {
                "cat" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.DiscoverCategoryTarget(
                    hd.kinoshka.app.ui.screens.DiscoverCategory.valueOf(p[1])
                )
                "fg" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.FilmGenreTarget(p[1].toInt(), p.getOrElse(2) { "" })
                "fpop" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.FilmPopular
                "fresh" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.FilmFresh
                "ag" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeGenreTarget(p[1].toInt(), p.getOrElse(2) { "" })
                "ak" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeKindTarget(p[1], p.getOrElse(2) { "" })
                "as" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeSeasonTarget(
                    p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "ranked" }
                )
                "ao" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeOngoing
                "aon" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeOnAir
                "ar" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimeRanked
                "apop" -> hd.kinoshka.app.ui.screens.OverviewSeeAll.AnimePopular
                else -> null
            }
        }.getOrNull()
    }

    fun saveOverviewCache(
        branch: String,
        sections: List<hd.kinoshka.app.ui.screens.OverviewSection>,
        hero: List<FilmItem>
    ) {
        val key = if (branch == "anime") overviewAnimeCacheKey else overviewFilmCacheKey
        val dto = OverviewBranchDto(
            sections = sections.map { s ->
                OverviewSectionDto(s.id, s.title, s.items, encodeSeeAll(s.seeAll))
            },
            hero = hero,
            savedAt = System.currentTimeMillis()
        )
        prefs.putString(key, gson.toJson(dto)).apply()
    }

    fun getOverviewCache(branch: String): OverviewBranchCache {
        val key = if (branch == "anime") overviewAnimeCacheKey else overviewFilmCacheKey
        val raw = prefs.getString(key, null) ?: return OverviewBranchCache()
        return runCatching {
            val dto = gson.fromJson(raw, OverviewBranchDto::class.java) ?: return OverviewBranchCache()
            OverviewBranchCache(
                sections = dto.sections.map { s ->
                    hd.kinoshka.app.ui.screens.OverviewSection(s.id, s.title, s.items, decodeSeeAll(s.seeAll))
                },
                hero = dto.hero,
                savedAt = dto.savedAt
            )
        }.getOrDefault(OverviewBranchCache())
    }

    private fun <T : Enum<T>> readEnum(key: String, fallback: T): T {
        return runCatching {
            java.lang.Enum.valueOf(fallback.declaringJavaClass, prefs.getString(key, fallback.name).orEmpty())
        }.getOrDefault(fallback)
    }
}

private fun FilmItem.isRussianContent(): Boolean {
    return countries.any { country ->
        when (country.country?.trim()?.lowercase(Locale.forLanguageTag("ru"))) {
            "россия", "ссср" -> true
            else -> false
        }
    }
}

private fun FilmDetails.isRussianContent(): Boolean {
    return countries.any { country ->
        when (country.country?.trim()?.lowercase(Locale.forLanguageTag("ru"))) {
            "россия", "ссср" -> true
            else -> false
        }
    }
}
