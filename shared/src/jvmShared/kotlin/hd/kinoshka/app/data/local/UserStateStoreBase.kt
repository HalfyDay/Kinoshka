package hd.kinoshka.app.data.local


import hd.kinoshka.app.util.log.KLog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.formatSyncTimeMs
import hd.kinoshka.app.data.source.embedHost
import java.util.Locale


enum class SavedViewMode {
    LIST,
    GRID
}

enum class AppThemeMode {
    CURRENT,
    LIGHT,
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
    val playerMode: PlayerMode = PlayerMode.MPVEX,
    // Состояние библиотеки и фильтров: раньше в облако не ездили вовсе.
    val librarySortType: LibrarySortType = LibrarySortType.LAST_VIEWED,
    val librarySortReversed: Boolean = false,
    val libraryGroupType: LibraryGroupType = LibraryGroupType.NONE,
    val showHentaiInLibrary: Boolean = true
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

/** Usage counters for one playback source (Kodik/AniLiberty/AnimeLib). */
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
    val shikimoriUserId: Int = 0,
    // Остальное состояние «всего»: история поиска, resume-позиции, память
    // источников/озвучек, кэш деталей аниме (включая 18+-вердикты). В старых
    // копиях полей нет — Gson даст null, импорт/слияние их пропускают.
    val searchHistory: List<SearchHistoryRecord>? = null,
    val playbackPositions: Map<String, PlaybackPosition>? = null,
    val playbackUsage: PlaybackUsageStats? = null,
    val animeCache: Map<Int, ShikimoriAnimeCache>? = null
)

/** Итог [UserStateStoreBase.mergeLibraryJson]: сколько записей реально обновилось. */
data class LibraryMergeReport(
    val profilesApplied: Int,
    val historyApplied: Int,
    /** Прочее состояние (позиции, usage, поиск, кэш, мета) — для строки статуса синка. */
    val extrasApplied: Int = 0
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
    // Жанровая проверка 18+ (батч ids+genre) пройдена. Краткий объект батча жанров не
    // несёт, поэтому его isAdult=false без жанрового сигнала недостоверен. Non-null
    // Boolean: в старых кэшах без поля Gson оставляет JVM-дефолт false — то есть
    // «нужна проверка», что и требуется для одноразовой перепроверки старых записей.
    val genreChecked: Boolean = false,
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
 * Потолок дискового кэша деталей аниме: библиотеки 600+ тайтлов (Anixart-пул)
 * не влезали в 500 — записи вытесняли друг друга, джобы добивки гонялись
 * по кругу (померяно в проде 2026-09-08: backfill 423 → вытеснение → backfill
 * 382 → …), плитки мигали, prefs переписывался сотнями полных сериализаций.
 * ~350 байт на запись: 2000 ≈ 700 КБ, для SharedPreferences нормально.
 */
private const val MAX_ANIME_CACHE_ENTRIES = 2000

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
    private val prefetchCooloffKey = "shikimori_prefetch_cooloff_until"
    private val librarySortKey = "library_sort_type"
    private val librarySortReversedKey = "library_sort_reversed"
    private val showHentaiInLibraryKey = "show_hentai_in_library"
    private val cloudMetaAppliedAtKey = "cloud_meta_applied_at"
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

    /**
     * Вес кэш-записи для облачного слияния: проверенная (жанровый флаг) и 18+
     * бьют непроверенные; при равенстве решает savedAtMs. См. mergeLibraryJson.
     */
    private fun animeCacheScore(entry: ShikimoriAnimeCache): Int =
        (if (entry.genreChecked) 2 else 0) + (if (entry.isAdult == true) 1 else 0)

    /** Кап кэша как в saveShikimoriAnimeInfos (последние MAX_ANIME_CACHE_ENTRIES по savedAtMs). */
    private fun capAnimeCache(cache: Map<Int, ShikimoriAnimeCache>): Map<Int, ShikimoriAnimeCache> {
        if (cache.size <= MAX_ANIME_CACHE_ENTRIES) return cache
        return cache.entries.sortedByDescending { it.value.savedAtMs }.take(MAX_ANIME_CACHE_ENTRIES).associate { it.toPair() }
    }

    fun saveShikimoriAnimeInfo(info: ShikimoriAnimeCache) = saveShikimoriAnimeInfos(listOf(info))

    /**
     * Пакетная запись кэша: ОДИН read-modify-write вместо поштучных. Поштучные
     * saveShikimoriAnimeInfo на джобах добивки (400+ записей) парсили
     * и сериализовали весь блоб на каждую запись — секунды GC-штопора и
     * сотни перезаписей prefs за один холодный старт.
     */
    fun saveShikimoriAnimeInfos(infos: Collection<ShikimoriAnimeCache>) = synchronized(BLOB_LOCK) {
        if (infos.isEmpty()) return
        val cache = getShikimoriAnimeCache().toMutableMap()
        for (info in infos) cache[info.shikimoriId] = info
        // Keep only last MAX_ANIME_CACHE_ENTRIES entries
        if (cache.size > MAX_ANIME_CACHE_ENTRIES) {
            val sorted = cache.entries.sortedBy { it.value.savedAtMs }
            val toRemove = sorted.take(cache.size - MAX_ANIME_CACHE_ENTRIES).map { it.key }
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

    private val adultRecheckV2Key = "adult_recheck_v2_done"

    /** Разовый перепрогон жанровой разметки 18+ уже выполнялся. */
    fun needsAdultRecheckV2(): Boolean = !prefs.getBoolean(adultRecheckV2Key, false)

    fun markAdultRecheckV2Done() {
        prefs.putBoolean(adultRecheckV2Key, true).apply()
    }

    /**
     * Разовый сброс жанровой разметки 18+: записи с genreChecked=true пропускаются
     * проверками навсегда, а старые вердикты могли быть ложными (нечёткий матчинг
     * названий — «Акира»). Сбрасываем только флаг (isAdult живёт до пересчёта),
     * батч markAdultByGenre в том же прогоне выставит вердикты авторитетно.
     * Возвращает число затронутых записей.
     */
    fun resetAdultGenreChecks(): Int = synchronized(BLOB_LOCK) {
        val cache = getShikimoriAnimeCache()
        if (cache.isEmpty()) return 0
        var touched = 0
        val updated = cache.mapValues { (_, entry) ->
            if (entry.genreChecked) {
                touched++
                entry.copy(genreChecked = false)
            } else entry
        }
        if (touched > 0) saveShikimoriAnimeCache(updated)
        touched
    }

    /**
     * Кулдаун фоновой добивки деталей Shikimori (wall-clock мс): после серии 429 префетч
     * останавливается и молчит до метки — иначе отравленные тайтлы (429 → ничего не
     * сохранено → повтор при следующем запуске) штормили бы API вечно.
     */
    fun getPrefetchCooloffUntilMs(): Long = prefs.getLong(prefetchCooloffKey, 0L)

    fun setPrefetchCooloffUntilMs(untilMs: Long) {
        prefs.putLong(prefetchCooloffKey, untilMs).apply()
    }

    /**
     * Сверка локальных профилей с серверными оценками Shikimori: чистый last-write-wins
     * по времени изменения. Серверный рейт новее локального профиля — забирается целиком
     * (статус, серии, оценка, заметка, повторы). Локальное новее или равно (эхо своего
     * пуша) — профиль не трогается, отправкой занимается пуш. Часы сервера — общая шкала:
     * после каждого пуша локальная метка якорится из server updated_at
     * (см. anchorProfileUpdatedAt), поэтому перекос часов устройств на решения не влияет.
     * Возвращает число обновлённых профилей.
     */
    fun adoptShikimoriRates(rates: List<hd.kinoshka.app.data.model.ShikimoriUserRate>): Int {
        if (rates.isEmpty()) return 0
        return synchronized(BLOB_LOCK) {
            val byId = (readProfilesOrNull() ?: return@synchronized 0)
                .associateBy { it.kinopoiskId }
                .toMutableMap()
            var updated = 0
            var skippedLocalNewer = 0
            var skippedUnparsable = 0
            for (rate in rates) {
                if (rate.targetId <= 0) continue
                val rateTime = rate.getUpdatedEpochMillis()
                // Серверное время неизвестно — решать не по чему, пропускаем.
                if (rateTime <= 0) {
                    skippedUnparsable++
                    KLog.w(
                        "ShikimoriSync",
                        "adopt: shikimoriId=${rate.targetId} SKIP unparsable time " +
                            "(updated_at='${rate.updatedAt}' created_at='${rate.createdAt}')"
                    )
                    continue
                }
                val key = rate.targetId + ANIME_ID_OFFSET
                val existing = byId[key] ?: continue
                // Пустая локалка (ни серий, ни оценки, ни заметки, ни повторов)
                // никогда не «новее» сервера с содержимым: её updatedAt — время
                // создания оболочки (Anixart-пул, restore, открытие карточки),
                // а не правка. Иначе сотни оболочек 09.09 вечно перевешивали
                // реальные серверные серии 2019–2025 и библиотека не показывала
                // прогресс (кейс 13.09: local=0 битый новее server=14).
                val localEp = existing.watchedEpisodes ?: 0
                val localHasContent = localEp > 0 || (existing.userRating ?: 0) > 0 ||
                    !existing.note.isNullOrBlank() || (existing.watchedSeasons ?: 0) > 0
                val serverEp = rate.episodes.coerceAtLeast(0)
                val serverHasContent = serverEp > 0 || rate.score > 0 || !rate.text.isNullOrBlank()
                val emptyShellLoses = !localHasContent && serverHasContent
                // Локальное новее или равно — побеждает локальное, серверное игнорируем.
                if (rateTime <= existing.updatedAt && !emptyShellLoses) {
                    skippedLocalNewer++
                    // Интересен только конфликт значений: молчаливое эхо пуша не логируем.
                    if (serverEp != localEp) {
                        KLog.d(
                            "ShikimoriSync",
                            "adopt: shikimoriId=${rate.targetId} SKIP local-newer " +
                                "ep(local=$localEp server=$serverEp) " +
                                "app=[${formatSyncTimeMs(existing.updatedAt)} ${existing.updatedAt}] " +
                                "site=[${formatSyncTimeMs(rateTime)} raw='${rate.updatedAt}']"
                        )
                    }
                    continue
                }
                val serverStatus = when (rate.status.lowercase()) {
                    "watching" -> UserFilmStatus.WATCHING
                    "planned" -> UserFilmStatus.PLANNED
                    "completed" -> UserFilmStatus.COMPLETED
                    "rewatching" -> UserFilmStatus.REWATCHING
                    "on_hold" -> UserFilmStatus.ON_HOLD
                    "dropped" -> UserFilmStatus.DROPPED
                    // Неизвестный статус сервера — не рискуем перезаписывать профиль.
                    else -> null
                }
                if (serverStatus == null) continue
                val merged = existing.copy(
                    watchedEpisodes = rate.episodes.coerceAtLeast(0),
                    status = serverStatus,
                    userRating = rate.score.takeIf { it > 0 },
                    note = rate.text?.trim()?.takeUnless { it.isBlank() },
                    watchedSeasons = rate.rewatches.takeIf { it > 0 },
                    // Сервер-авторитетное обновление (adopt): дальше транзитом на
                    // другие зеркала, как пользовательская правка. Импортная метка
                    // снимается — иначе Shikimori->Anixart перестал бы доезжать.
                    importSource = null,
                    updatedAt = rateTime
                )
                if (merged != existing) {
                    byId[key] = merged
                    updated++
                    KLog.d(
                        "ShikimoriSync",
                        "adopt: shikimoriId=${rate.targetId} ADOPT " +
                            (if (emptyShellLoses && rateTime <= existing.updatedAt) "empty-shell-fix " else "server-newer ") +
                            "ep(local=${existing.watchedEpisodes ?: 0} -> server=${rate.episodes}) " +
                            "status(${existing.status} -> $serverStatus) " +
                            "app=[${formatSyncTimeMs(existing.updatedAt)} ${existing.updatedAt}] " +
                            "site=[${formatSyncTimeMs(rateTime)} raw='${rate.updatedAt}']"
                    )
                }
            }
            if (updated > 0 || skippedUnparsable > 0) {
                KLog.i(
                    "ShikimoriSync",
                    "adopt: done adopted=$updated skippedLocalNewer=$skippedLocalNewer " +
                        "skippedUnparsable=$skippedUnparsable of ${rates.size}"
                )
            }
            if (updated > 0) writeProfiles(capProfiles(byId.values.toList()))
            updated
        }
    }

    /**
     * Якорение часов после успешного пуша на Shikimori: локальная метка выставляется
     * из server updated_at, значения не трогаются (полный adopt эха затёр бы заметку,
     * которая не пушится). Часы сервера — общая шкала для LWW-решений.
     */
    fun anchorProfileUpdatedAt(kinopoiskId: Int, timeMs: Long) {
        if (timeMs <= 0) return
        synchronized(BLOB_LOCK) {
            val profiles = readProfilesOrNull() ?: return
            val idx = profiles.indexOfFirst { it.kinopoiskId == kinopoiskId }
            if (idx < 0) return
            val existing = profiles[idx]
            if (timeMs <= existing.updatedAt) return
            val updated = profiles.toMutableList()
            updated[idx] = existing.copy(updatedAt = timeMs)
            writeProfiles(capProfiles(updated))
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

    /**
     * Нижнее навигационное меню (пилюля): порядок вкладок, скрытые вкладки
     * и вибрация pull-to-refresh. Имена секций — MainSection.name (UI-слой),
     * здесь только сырые строки: data-слой UI-тип не знает.
     */
    private val navOrderKey = "nav_order_csv"
    private val navHiddenKey = "nav_hidden_csv"
    private val navHapticsEnabledKey = "nav_haptics_enabled"
    private val navHapticScaleKey = "nav_haptic_scale_pct"

    /** Порядок вкладок по умолчанию (как исторически в пилюле). */
    fun defaultNavOrder(): List<String> = listOf("LIBRARY", "DISCOVER", "FEED", "PROFILE")

    fun getNavOrder(): List<String> {
        val raw = prefs.getString(navOrderKey, null)?.split(",")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (raw.isNullOrEmpty()) return defaultNavOrder()
        // Чиним битые записи: дубли и мусор выкидываем, потерянные секции дописываем.
        val deduped = raw.distinct().filter { it in defaultNavOrder() }
        return deduped + defaultNavOrder().filter { it !in deduped }
    }

    fun setNavOrder(order: List<String>) {
        val clean = order.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.putString(navOrderKey, clean.joinToString(",")).apply()
    }

    fun getNavHidden(): Set<String> {
        return prefs.getString(navHiddenKey, null)?.split(",")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    }

    fun setNavHidden(hidden: Set<String>) {
        prefs.putString(navHiddenKey, hidden.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")).apply()
    }

    /** Вибрация сияния pull-to-refresh Библиотеки: мастер-тумблер (по умолчанию вкл). */
    fun isNavHapticsEnabled(): Boolean = prefs.getBoolean(navHapticsEnabledKey, true)

    fun setNavHapticsEnabled(enabled: Boolean) {
        prefs.putBoolean(navHapticsEnabledKey, enabled).apply()
    }

    /** Сила вибрации 0..1 (слайдер настроек; KinoPrefs хранит проценты long). */
    fun getNavHapticScale(): Float =
        prefs.getLong(navHapticScaleKey, 100L).coerceIn(0L, 100L) / 100f

    fun setNavHapticScale(scale: Float) {
        prefs.putLong(navHapticScaleKey, (scale.coerceIn(0f, 1f) * 100).toLong()).apply()
    }

    /**
     * Метка последнего применённого слиянием облачного мета-состояния (аватар,
     * преференсы, снапшот рейтов): у них нет собственных меток, новее/старее
     * решает exportedAt копии. См. mergeLibraryJson.
     */
    private fun getCloudMetaAppliedAt(): Long = prefs.getLong(cloudMetaAppliedAtKey, 0L)

    private fun setCloudMetaAppliedAt(atMs: Long) {
        prefs.putLong(cloudMetaAppliedAtKey, atMs).apply()
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

    // ---- Источники видео: выключатель раздельно по разделам (экран «Источники»).
    // Ключ записи — либо голый id («ANISTAR» = выключен везде, в т.ч. старые
    // записи), либо «КАТЕГОРИЯ:ID» («ADULT:ANISTAR» = выключен только в 18+).
    // Отдельного «скрыть, но оставить работать» больше нет: старые скрытые
    // записи при чтении считаются выключенными.

    private val disabledSourcesKey = "disabled_sources_csv"
    private val hiddenSourcesKey = "hidden_sources_csv"

    private fun readIdSet(key: String): MutableSet<String> =
        (prefs.getString(key, null) ?: "")
            .split(',')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

    private fun writeIdSet(key: String, ids: Set<String>) {
        val canonical = ids.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.sorted()
        if (canonical.isEmpty()) prefs.remove(key).apply()
        else prefs.putString(key, canonical.joinToString(",")).apply()
    }

    /** Сырые ключи выключения (голые id + «КАТЕГОРИЯ:ID») — для экрана «Источники». */
    fun getDisabledSourceKeys(): Set<String> =
        readIdSet(disabledSourcesKey) + readIdSet(hiddenSourcesKey)

    /**
     * Id источников, выключенных для [category] (глобально выключенные входят
     * всегда). Без категории — старое поведение: только голые id.
     */
    fun getDisabledSources(category: hd.kinoshka.app.data.source.SourceCategory? = null): Set<String> {
        val keys = getDisabledSourceKeys()
        if (category == null) return keys.filter { ':' !in it }.toSet()
        val prefix = category.name + ":"
        return keys.filter { ':' !in it || it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    /** Устарело: скрытие сложено в выключение, метод оставлен для совместимости. */
    fun getHiddenSources(): Set<String> = readIdSet(hiddenSourcesKey)

    fun isSourceEnabled(id: String, category: hd.kinoshka.app.data.source.SourceCategory? = null): Boolean =
        id.trim().uppercase() !in getDisabledSources(category)

    /** Устарело: видимость теперь совпадает с работой. */
    fun isSourceVisible(id: String): Boolean = isSourceEnabled(id)

    /**
     * Выключатель источника. Без категории — глобально (голый id, как раньше);
     * с категорией — только для раздела («КАТЕГОРИЯ:ID»). Включение раздела при
     * глобально выключенном источнике разбивает глобальный флаг на остальные
     * его разделы, чтобы они остались выключенными.
     */
    fun setSourceEnabled(
        id: String,
        enabled: Boolean,
        category: hd.kinoshka.app.data.source.SourceCategory? = null
    ) = synchronized(BLOB_LOCK) {
        val key = id.trim().uppercase()
        if (key.isEmpty()) return
        if (category == null) {
            val ids = readIdSet(disabledSourcesKey)
            if (enabled) ids.remove(key) else ids.add(key)
            writeIdSet(disabledSourcesKey, ids)
            // Чистим устаревший флаг скрытия, чтобы сеты не расходились.
            if (enabled) {
                val hidden = readIdSet(hiddenSourcesKey)
                if (hidden.remove(key)) writeIdSet(hiddenSourcesKey, hidden)
            }
            return
        }
        val scoped = "${category.name}:$key"
        val ids = readIdSet(disabledSourcesKey)
        if (enabled) {
            ids.remove(scoped)
            if (ids.remove(key)) {
                // Был выключен везде — остальные разделы источника остаются
                // выключенными явно.
                hd.kinoshka.app.data.source.PlaybackSources.info(key)
                    ?.categories
                    ?.filter { it != category }
                    ?.forEach { ids.add("${it.name}:$key") }
            }
            writeIdSet(disabledSourcesKey, ids)
            val hidden = readIdSet(hiddenSourcesKey)
            if (hidden.remove(scoped)) writeIdSet(hiddenSourcesKey, hidden)
        } else {
            if (key !in ids) ids.add(scoped)
            writeIdSet(disabledSourcesKey, ids)
        }
    }

    /** Устарело: перенаправлено на [setSourceEnabled] (один выключатель). */
    fun setSourceVisible(id: String, visible: Boolean) = setSourceEnabled(id, visible)

    // ---- Свои источники (вариант A кастомных): CRUD поверх prefs, терпимое чтение.
    private val customSourcesKey = "custom_sources_json"

    fun getCustomSources(): List<hd.kinoshka.app.data.source.CustomSource> =
        hd.kinoshka.app.data.source.parseCustomSources(prefs.getString(customSourcesKey, null))

    /** Upsert по id + актуальная регистрация прокси-хоста и реестра в процессе. */
    fun saveCustomSource(source: hd.kinoshka.app.data.source.CustomSource) = synchronized(BLOB_LOCK) {
        val current = getCustomSources().toMutableList()
        val index = current.indexOfFirst { it.id == source.id }
        if (index >= 0) {
            val old = current[index]
            current[index] = source
            unregisterCustomProxyHost(old)
        } else {
            current += source
        }
        prefs.putString(
            customSourcesKey,
            hd.kinoshka.app.data.source.customSourcesToJson(current)
        ).apply()
        registerCustomProxyHost(source)
        refreshCustomRegistry(current)
    }

    /** Удаление + чистка выключателя + снятие прокси-хоста. */
    fun deleteCustomSource(id: String) = synchronized(BLOB_LOCK) {
        val key = id.trim().uppercase()
        if (key.isEmpty()) return
        val current = getCustomSources()
        val removed = current.firstOrNull { it.id == key } ?: return
        prefs.putString(
            customSourcesKey,
            hd.kinoshka.app.data.source.customSourcesToJson(current.filter { it.id != key })
        ).apply()
        unregisterCustomProxyHost(removed)
        refreshCustomRegistry(current.filter { it.id != key })
        for (storeKey in listOf(disabledSourcesKey, hiddenSourcesKey)) {
            val ids = readIdSet(storeKey)
            // or (не ||): все ветки обязаны выполниться.
            var changed = ids.remove(key)
            for (category in hd.kinoshka.app.data.source.SourceCategory.entries) {
                changed = ids.remove("${category.name}:$key") or changed
            }
            if (changed) writeIdSet(storeKey, ids)
        }
    }

    private fun registerCustomProxyHost(source: hd.kinoshka.app.data.source.CustomSource) {
        source.takeIf { it.useProxy }?.embedHost()?.let {
            hd.kinoshka.app.data.source.StreamProxyConfig.registerCustomHost(it)
        }
    }

    private fun refreshCustomRegistry(current: List<hd.kinoshka.app.data.source.CustomSource>) {
        hd.kinoshka.app.data.source.PlaybackSources.setCustomSourceInfos(
            current.map { hd.kinoshka.app.data.source.PlaybackSources.customInfo(it) }
        )
    }

    private fun unregisterCustomProxyHost(source: hd.kinoshka.app.data.source.CustomSource) {
        // Снимаем, только если хост не нужен другому кастомному источнику с прокси.
        val host = source.embedHost() ?: return
        val stillNeeded = getCustomSources().any { it.id != source.id && it.useProxy && it.embedHost() == host }
        if (!stillNeeded) hd.kinoshka.app.data.source.StreamProxyConfig.unregisterCustomHost(host)
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
            playerMode = getPlayerMode(),
            librarySortType = getLibrarySortType(),
            librarySortReversed = isLibrarySortReversed(),
            libraryGroupType = getLibraryGroupType(),
            showHentaiInLibrary = isHentaiVisibleInLibrary()
        )
    }

    /**
     * Применяет преференсы из облачной копии (импорт целиком и gated-слияние).
     * Вызывать внутри synchronized(BLOB_LOCK) — собственной синхронизации нет.
     */
    private fun applyPreferences(preferences: UserPreferences) {
        setThemeMode(preferences.themeMode)
        setHideRussianContentEnabled(preferences.hideRussianContent)
        val fallbackTileSize = runCatching { preferences.tileSize }.getOrDefault(FilmTileSize.MEDIUM)
        setTileSize(fallbackTileSize)
        setDiscoverTileSize(preferences.discoverTileSize ?: fallbackTileSize)
        setLibraryTileSize(preferences.libraryTileSize ?: fallbackTileSize)
        setFpsCounterEnabled(preferences.showFpsCounter)
        setLibrarySortType(runCatching { preferences.librarySortType }.getOrDefault(LibrarySortType.LAST_VIEWED))
        setLibrarySortReversed(preferences.librarySortReversed)
        setLibraryGroupType(runCatching { preferences.libraryGroupType }.getOrDefault(LibraryGroupType.NONE))
        setHentaiVisibleInLibrary(preferences.showHentaiInLibrary)
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
     * Пустую шелуху не плодим: снятие пометки с пустого профиля удаляет его
     * вовсе (иначе тайтл невидим ни в одной вкладке, но раздувает итоги),
     * а профиля без записи вообще не трогаем.
     */
    fun setFeedQuickStatus(
        kinopoiskId: Int,
        title: String,
        posterUrl: String?,
        status: UserFilmStatus?
    ) = synchronized(BLOB_LOCK) {
        val existing = readProfilesOrNull()?.firstOrNull { it.kinopoiskId == kinopoiskId }
        if (status == null) {
            if (existing == null || existing.status == null) return@synchronized
            if (isProfileEmpty(existing) && readHistory().none { it.kinopoiskId == kinopoiskId }) {
                removeFromLibrary(kinopoiskId)
            } else {
                upsertProfile(existing.copy(status = null, importSource = null, updatedAt = System.currentTimeMillis()))
            }
            return@synchronized
        }
        // Тот же статус повторно — не пользовательская правка, метку не двигаем
        // (иначе LWW считал бы профиль новее сайта и пуш затирал бы его).
        if (existing?.status == status) return@synchronized
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
        // Явная пометка (кнопка фида) и adopt серверного статуса — в обоих случаях
        // локальное состояние становится осознанным: импортная метка снимается,
        // дальше профиль пушится как обычно (в т.ч. транзитом на другие зеркала).
        upsertProfile(base.copy(status = status, importSource = null, updatedAt = System.currentTimeMillis()))
    }

    /** Профиль без данных: ни статуса, ни оценки, ни заметки, ни прогресса. */
    private fun isProfileEmpty(profile: UserFilmProfile): Boolean {
        return profile.status == null &&
            profile.userRating == null &&
            profile.note.isNullOrBlank() &&
            (profile.watchedSeasons ?: 0) <= 0 &&
            (profile.watchedEpisodes ?: 0) <= 0
    }

    /**
     * Точечный ремонт названия импортной оболочки (статус/метку/время не трогаем —
     * для LWW и пуша это невидимка). Только importSource != null, иначе молча нет.
     */
    fun renameImportedProfileTitle(kinopoiskId: Int, title: String): Boolean =
        synchronized(BLOB_LOCK) {
            val current = readProfilesOrNull() ?: return false
            val idx = current.indexOfFirst { it.kinopoiskId == kinopoiskId }
            if (idx < 0) return false
            val p = current[idx]
            if (p.importSource == null || p.title == title) return false
            val updated = current.toMutableList()
            updated[idx] = p.copy(title = title)
            writeProfiles(updated)
            true
        }

    /**
     * Удаление осиротевших импортных оболочек Anixart (пул их больше не
     * референсит после самолечения карты). Только importSource == "anixart":
     * тронутые пользователем профили метку уже сняли. Возвращает число удалённых.
     */
    fun removeImportedOrphans(shikimoriIds: Set<Int>): Int = synchronized(BLOB_LOCK) {
        if (shikimoriIds.isEmpty()) return 0
        val offset = hd.kinoshka.app.data.model.ANIME_ID_OFFSET
        val current = readProfilesOrNull() ?: return 0
        val doomed = current.filter {
            it.type == "ANIME" && it.importSource == "anixart" &&
                (it.kinopoiskId - offset) in shikimoriIds
        }
        if (doomed.isEmpty()) return 0
        val doomedIds = doomed.mapTo(mutableSetOf()) { it.kinopoiskId }
        writeProfiles(current.filterNot { it.kinopoiskId in doomedIds })
        doomed.size
    }

    /**
     * Разовая чистка пустой шелухи без статуса (снятия пометок и сиды до фикса):
     * такие записи невидимы ни в одной вкладке библиотеки, но раздували итоги.
     * Возвращает число удалённых.
     */
    fun pruneEmptyStatuslessProfiles(): Int = synchronized(BLOB_LOCK) {        val current = readProfilesOrNull() ?: return@synchronized 0
        val historyIds = readHistory().mapTo(mutableSetOf()) { it.kinopoiskId }
        val doomed = current.filter { isProfileEmpty(it) && it.kinopoiskId !in historyIds }
        if (doomed.isEmpty()) return@synchronized 0
        val doomedIds = doomed.mapTo(mutableSetOf()) { it.kinopoiskId }
        writeProfiles(current.filterNot { it.kinopoiskId in doomedIds })
        doomed.size
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
     * The `importSource` is preserved for the same reason: merely opening details must not
     * convert an Anixart/restore shell into a pushable profile (кейс 13.09: оболочка 63403
     * стала importSource=null открытием и затёрла серверный прогресс 1 серией в 0).
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
                importSource = existing?.importSource,
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

            val finalNote = note?.trim().takeUnless { it.isNullOrBlank() }
            val finalTotalInSeason = (totalEpisodesInSeason ?: existing?.totalEpisodesInSeason)?.coerceAtLeast(0)
            val finalSeasons = finalTotalSeasons?.coerceAtLeast(0)
            val finalTotal = finalTotalEpisodes?.coerceAtLeast(0)
            // Сохранение без изменений не двигает метку: иначе но-оп «Сохранить» делал бы
            // локальное новее сайта, и следующий пуш затирал бы серверные правки.
            val contentUnchanged = existing != null &&
                existing.status == status &&
                existing.userRating == userRating &&
                existing.note == finalNote &&
                existing.watchedSeasons == finalWatchedSeasons &&
                existing.watchedEpisodes == finalWatchedEpisodes &&
                existing.totalEpisodesInSeason == finalTotalInSeason &&
                existing.totalSeasons == finalSeasons &&
                existing.totalEpisodes == finalTotal
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
                note = finalNote,
                watchedSeasons = finalWatchedSeasons,
                watchedEpisodes = finalWatchedEpisodes,
                totalEpisodesInSeason = finalTotalInSeason,
                totalSeasons = finalSeasons,
                totalEpisodes = finalTotal,
                updatedAt = if (contentUnchanged) existing?.updatedAt ?: System.currentTimeMillis() else System.currentTimeMillis()
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
            // Повторный коммит той же серии метку не двигает: иначе плеер вечно
            // «омолаживал» бы профиль, и правки с сайта Shikimori никогда не побеждали в LWW.
            if (existing.watchedSeasons == seasonNumber &&
                existing.watchedEpisodes == episodeNumber &&
                existing.status == status
            ) return
            upsertProfile(
                existing.copy(
                    watchedSeasons = seasonNumber,
                    watchedEpisodes = episodeNumber,
                    status = status,
                    // Реальный прогресс плеера — пользовательское действие.
                    importSource = null,
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
            // Статус не меняется (уже COMPLETED/DROPPED/ON_HOLD) — метку не двигаем.
            if (existing.status == status) return
            upsertProfile(existing.copy(status = status, importSource = null, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Full library exit: drops BOTH the profile and the history entry. Saving the progress editor
     * with an explicitly cleared (null) status means "remove from library" — otherwise a statusless
     * husk would keep surfacing in the История tab despite having no progress at all.
     */
    /**
     * Точечное добавление профилей, которых ещё нет (пул Anixart): существующих
     * не трогает. Возвращает число добавленных.
     */
    fun addProfilesIfAbsent(profiles: List<UserFilmProfile>): Int = synchronized(BLOB_LOCK) {
        val current = readProfilesOrNull() ?: return@synchronized 0
        val ids = current.mapTo(mutableSetOf()) { it.kinopoiskId }
        val fresh = profiles.filter { it.kinopoiskId !in ids }
        if (fresh.isEmpty()) return@synchronized 0
        writeProfiles(capProfiles(current + fresh))
        fresh.size
    }

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
            // The player's episode list is not canonical (it can be shorter than the real run while
            // a season is airing). Never let it shrink the stored total — that made the progress
            // percentage and the watched checkmarks disagree.
            val mergedTotal = maxOf(
                existing?.totalEpisodes ?: 0,
                totalEpisodes.takeIf { it > 0 } ?: 0
            ).takeIf { it > 0 } ?: existing?.totalEpisodes
            // Онгоинг Shikimori нельзя завершить просмотром вышедших серий (10 из 10
            // вышедших при всего 12 — это WATCHING). Сигнал — кэшированный статус релиза.
            val knownOngoing = kinopoiskId >= ANIME_ID_OFFSET &&
                getShikimoriAnimeInfo(kinopoiskId - ANIME_ID_OFFSET)?.status
                    .equals("ongoing", ignoreCase = true)
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
                // Автокомплит только по полному числу серий: сравнение с mergedTotal
                // (максимум известного), а не с коротким списком плеера.
                !knownOngoing && mergedTotal != null && mergedTotal > 0 && episodeNum >= mergedTotal ->
                    UserFilmStatus.COMPLETED
                currentStatus == UserFilmStatus.REWATCHING -> currentStatus
                else -> UserFilmStatus.WATCHING
            }

            // Повторный коммит того же состояния метку не двигает: иначе прогресс
            // плеера вечно выглядел бы новее сайта и затирал его в LWW (тот самый откат).
            if (existing != null &&
                existing.watchedEpisodes == episodeNum &&
                existing.totalEpisodes == mergedTotal &&
                existing.status == newStatus
            ) return
            val updated = if (existing != null) {
                existing.copy(
                    watchedEpisodes = episodeNum,
                    totalEpisodes = mergedTotal,
                    status = newStatus,
                    // Прогресс плеера — пользовательское действие.
                    importSource = null,
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
            shikimoriUserId = ratesSnapshot.userId,
            searchHistory = getSearchHistory().takeIf { it.isNotEmpty() },
            playbackPositions = readPlaybackPositions().takeIf { it.isNotEmpty() },
            playbackUsage = getPlaybackUsage().takeIf {
                it.sources.isNotEmpty() || it.dubs.isNotEmpty() || it.titleDubs.isNotEmpty()
            },
            animeCache = getShikimoriAnimeCache().takeIf { it.isNotEmpty() }
        )
        return prettyGson.toJson(backup)
    }

    /**
     * Объединение облачной копии с локальной без потерь: по каждому тайтлу побеждает
     * более новая запись (updatedAt), история объединяется по максимуму. Ручной
     * импорт/восстановление по-прежнему заменяют целиком — это осознанное действие.
     * Ограничение: удалений без меток нет — локально удалённый тайтл со старой
     * облачной записью воскреснет (лечится повторным удалением).
     */
    fun mergeLibraryJson(rawJson: String): Result<LibraryMergeReport> = synchronized(BLOB_LOCK) {
        runCatching {
            val backup = gson.fromJson(rawJson, LibraryBackup::class.java)
                ?: error("Файл пустой или поврежден")
            var profilesApplied = 0
            val incomingProfiles = backup.profiles.orEmpty()
            if (incomingProfiles.isNotEmpty()) {
                // null вместо списка — битый блоб: как и везде, отменяем запись,
                // чтобы не затереть библиотеку пустотой.
                val current = readProfilesOrNull()
                    ?: error("Локальная библиотека не читается — слияние отменено")
                val byId = current.associateBy { it.kinopoiskId }.toMutableMap()
                for (incoming in incomingProfiles) {
                    val local = byId[incoming.kinopoiskId]
                    if (local == null || incoming.updatedAt > local.updatedAt) {
                        byId[incoming.kinopoiskId] = incoming
                        profilesApplied++
                    }
                }
                if (profilesApplied > 0) writeProfiles(capProfiles(byId.values.toList()))
            }
            var historyApplied = 0
            val incomingHistory = backup.history.orEmpty()
            if (incomingHistory.isNotEmpty()) {
                val byId = readHistory().associateBy { it.kinopoiskId }.toMutableMap()
                for (incoming in incomingHistory) {
                    val local = byId[incoming.kinopoiskId]
                    if (local == null || incoming.viewedAt > local.viewedAt) {
                        byId[incoming.kinopoiskId] = incoming
                        historyApplied++
                    }
                }
                if (historyApplied > 0) {
                    writeHistory(byId.values.sortedByDescending { it.viewedAt }.take(200))
                }
            }
            var extrasApplied = 0
            // Resume-позиции: новее по updatedAt побеждает (поключевно).
            val incomingPositions = backup.playbackPositions.orEmpty()
            if (incomingPositions.isNotEmpty()) {
                val merged = readPlaybackPositions().toMutableMap()
                var dirty = false
                for ((key, incoming) in incomingPositions) {
                    val local = merged[key]
                    if (local == null || incoming.updatedAt > local.updatedAt) {
                        merged[key] = incoming
                        dirty = true
                        extrasApplied++
                    }
                }
                if (dirty) savePlaybackPositions(merged)
            }
            // Память источников/озвучек: новее по lastUsedAt побеждает (поключевно).
            backup.playbackUsage?.let { incomingUsage ->
                val current = getPlaybackUsage()
                var dirty = false
                val sources = current.sources.toMutableMap()
                for ((key, incoming) in incomingUsage.sources) {
                    val local = sources[key]
                    if (local == null || incoming.lastUsedAt > local.lastUsedAt) {
                        sources[key] = incoming
                        dirty = true
                        extrasApplied++
                    }
                }
                val dubs = current.dubs.toMutableMap()
                for ((key, incoming) in incomingUsage.dubs) {
                    val local = dubs[key]
                    if (local == null || incoming.lastUsedAt > local.lastUsedAt) {
                        dubs[key] = incoming
                        dirty = true
                        extrasApplied++
                    }
                }
                val titleDubs = current.titleDubs.toMutableMap()
                for ((key, incoming) in incomingUsage.titleDubs) {
                    val local = titleDubs[key]
                    if (local == null || incoming.lastUsedAt > local.lastUsedAt) {
                        titleDubs[key] = incoming
                        dirty = true
                        extrasApplied++
                    }
                }
                if (dirty) savePlaybackUsageStats(
                    PlaybackUsageStats(sources = sources, dubs = dubs, titleDubs = titleDubs)
                )
            }
            // История поиска: объединение по запросу (свежее searchedAt побеждает), кап 20.
            val incomingQueries = backup.searchHistory.orEmpty()
            if (incomingQueries.isNotEmpty()) {
                val merged = getSearchHistory().associateBy { it.query.lowercase() to it.contentType }.toMutableMap()
                var dirty = false
                for (incoming in incomingQueries) {
                    val key = incoming.query.lowercase() to incoming.contentType
                    val local = merged[key]
                    if (local == null || incoming.searchedAt > local.searchedAt) {
                        merged[key] = incoming
                        dirty = true
                        extrasApplied++
                    }
                }
                if (dirty) saveSearchHistory(
                    merged.values.sortedByDescending { it.searchedAt }.take(20)
                )
            }
            // Кэш деталей аниме: побеждает проверенная запись (жанровый флаг, 18+-флаг),
            // при равенстве — новее по savedAtMs. Не даём свежей «пустой» записи затереть
            // готовый вердикт.
            val incomingCache = backup.animeCache.orEmpty()
            if (incomingCache.isNotEmpty()) {
                val merged = getShikimoriAnimeCache().toMutableMap()
                var dirty = false
                for ((id, incoming) in incomingCache) {
                    val local = merged[id]
                    if (local == null || animeCacheScore(incoming) > animeCacheScore(local) ||
                        (animeCacheScore(incoming) == animeCacheScore(local) &&
                            incoming.savedAtMs > local.savedAtMs)
                    ) {
                        merged[id] = incoming
                        dirty = true
                        extrasApplied++
                    }
                }
                if (dirty) saveShikimoriAnimeCache(capAnimeCache(merged))
            }
            // Мета без собственных меток (аватар, преференсы, снапшот рейтов): применяем
            // только из копии новее последней применённой — иначе два устройства
            // гоняли бы настройки туда-сюда каждым синком.
            if (backup.exportedAt > getCloudMetaAppliedAt()) {
                var metaTouched = false
                backup.profileAvatar?.takeIf { it.isNotBlank() }?.let {
                    setProfileAvatar(it)
                    metaTouched = true
                }
                backup.preferences?.let {
                    applyPreferences(it)
                    metaTouched = true
                }
                backup.shikimoriRates?.takeIf { it.isNotEmpty() }?.let { rates ->
                    saveShikimoriRatesSnapshot(
                        ShikimoriRatesSnapshot(
                            userId = backup.shikimoriUserId,
                            savedAtMs = System.currentTimeMillis(),
                            rates = rates
                        )
                    )
                    metaTouched = true
                }
                if (metaTouched) {
                    setCloudMetaAppliedAt(backup.exportedAt)
                    extrasApplied++
                }
            }
            LibraryMergeReport(profilesApplied, historyApplied, extrasApplied)
        }
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
            backup.preferences?.let { applyPreferences(it) }
            // Новое в схеме (старых копий нет — null пропускаем, локальное не трогаем).
            backup.searchHistory?.let { saveSearchHistory(it) }
            backup.playbackPositions?.let { savePlaybackPositions(it) }
            backup.playbackUsage?.let { savePlaybackUsageStats(it) }
            backup.animeCache?.let { saveShikimoriAnimeCache(capAnimeCache(it)) }
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

    private val anixartBaselineKey = "anixart_lists_baseline_json"

    /**
     * Последний известный сервер Anixart (releaseId -> списки): baseline для правила
     * «untouched → adopt сервера, diverged → пуш локального». In-memory карта ViewModel
     * умирает с процессом, и первый синк после рестарта видел пустой baseline — любое
     * расхождение решалось в пользу локального с пушем поверх правок сайта/другого
     * устройства. Персист чинит это.
     */
    fun getAnixartListsBaseline(): Map<Int, Set<Int>> {
        val raw = prefs.getString(anixartBaselineKey, null) ?: return emptyMap()
        val type = object : TypeToken<Map<Int, Set<Int>>>() {}.type
        return runCatching { gson.fromJson<Map<Int, Set<Int>>>(raw, type).orEmpty() }
            .getOrDefault(emptyMap())
    }

    fun setAnixartListsBaseline(value: Map<Int, Set<Int>>) {
        val capped = if (value.size > 10_000) {
            value.entries.take(10_000).associate { it.key to it.value }
        } else value
        prefs.putString(anixartBaselineKey, gson.toJson(capped)).apply()
    }

    fun clearAnixartListsBaseline() {
        // Сброс всего зеркального состояния Anixart (baseline + id-карта + мемоизация
        // несопоставимого): всё это привязано к аккаунту, чужому не товарищ.
        prefs.remove(anixartBaselineKey).remove(anixartIdMapKey).remove(anixartUnresolvableKey)
            .remove(anixartPullUnresolvableKey).remove(anixartContestedKey).apply()
    }

    /**
     * Сброс только baseline (членства релизов по аккаунту). Карты знаний
     * (idmap, мемоизация промахов) — глобальная истина «релиз->аниме», от аккаунта
     * не зависят: их храним, иначе каждый вход заново жёг бы сотни поисков.
     */
    fun clearAnixartBaselineOnly() {
        prefs.remove(anixartBaselineKey).apply()
    }

    private val anixartIdMapKey = "anixart_idmap_json"
    private val anixartUnresolvableKey = "anixart_unresolvable_json"
    private val anixartPullUnresolvableKey = "anixart_pull_unresolvable_json"
    private val anixartMatcherGenKey = "anixart_matcher_gen"
    /** Бампить при изменении правил матчинга pull-резолюции (см. getAnixartPullUnresolvable). */
    private val ANIXART_MATCHER_GEN = 5L

    /**
     * Карта релиз Anixart -> shikimoriId (итог матчинга пула и резолюций каталога).
     * Без персиста холодный старт до построения библиотеки матчил всё мимо
     * (exact=0) и заново дёргал поиск по уже известным тайтлам.
     */
    fun getAnixartIdMap(): Map<Int, Int> {
        val raw = prefs.getString(anixartIdMapKey, null) ?: return emptyMap()
        val type = object : TypeToken<Map<Int, Int>>() {}.type
        return runCatching { gson.fromJson<Map<Int, Int>>(raw, type).orEmpty() }
            .getOrDefault(emptyMap())
    }

    fun setAnixartIdMap(value: Map<Int, Int>) {
        val capped = if (value.size > 10_000) {
            value.entries.take(10_000).associate { it.key to it.value }
        } else value
        prefs.putString(anixartIdMapKey, gson.toJson(capped)).apply()
    }

    /**
     * ShikimoriId, честно не найденные в каталоге Anixart (exact-поиск отработал,
     * совпадения нет). Без персиста каждый рестарт заново жег по 1-3 POST на тайтл.
     * Ошибки сети сюда не попадают (их повторит следующий синк).
     */
    fun getAnixartUnresolvable(): Set<Int> {
        val raw = prefs.getString(anixartUnresolvableKey, null) ?: return emptySet()
        val type = object : TypeToken<Set<Int>>() {}.type
        return runCatching { gson.fromJson<Set<Int>>(raw, type).orEmpty() }
            .getOrDefault(emptySet())
    }

    fun setAnixartUnresolvable(value: Set<Int>) {
        val capped = if (value.size > 10_000) value.take(10_000).toSet() else value
        prefs.putString(anixartUnresolvableKey, gson.toJson(capped)).apply()
    }

    /**
     * Релизы Anixart (их id), честно не найденные поиском Shikimori при пуле.
     * Без персиста каждый рестарт заново жег поиск по тем же промахам — а их
     * сотни, и каждый foreground-пул упирался бы в кап резолюций одними
     * повторами вместо новых тайтлов.
     */
    fun getAnixartPullUnresolvable(): Set<Int> {
        // Поколение матчера: правила резолва улучшились (сезонные алиасы, ядра,
        // префиксы, омоглифы) — старые «честные промахи» перепроверяем один раз.
        if (prefs.getLong(anixartMatcherGenKey, 0L) < ANIXART_MATCHER_GEN) {
            prefs.putLong(anixartMatcherGenKey, ANIXART_MATCHER_GEN).apply()
            prefs.remove(anixartPullUnresolvableKey).apply()
            return emptySet()
        }
        val raw = prefs.getString(anixartPullUnresolvableKey, null) ?: return emptySet()
        val type = object : TypeToken<Set<Int>>() {}.type
        return runCatching { gson.fromJson<Set<Int>>(raw, type).orEmpty() }
            .getOrDefault(emptySet())
    }

    fun setAnixartPullUnresolvable(value: Set<Int>) {
        val capped = if (value.size > 10_000) value.take(10_000).toSet() else value
        prefs.putString(anixartPullUnresolvableKey, gson.toJson(capped)).apply()
    }

    private val anixartContestedKey = "anixart_contested_json"

    /**
     * ShikimoriId, встреченные пулом в ≥2 списках с разными статусами (дубли:
     * сезоны/спешлы отдельными релизами). Один статус на shiki их не представляет —
     * пуш такие тайтлы не трогает никогда (иначе echo давил бы сервер, инцидент
     * 09.09), adopt серверного — можно. Пересчитывается каждым пулом.
     */
    fun getAnixartContested(): Set<Int> {
        val raw = prefs.getString(anixartContestedKey, null) ?: return emptySet()
        val type = object : TypeToken<Set<Int>>() {}.type
        return runCatching { gson.fromJson<Set<Int>>(raw, type).orEmpty() }
            .getOrDefault(emptySet())
    }

    fun setAnixartContested(value: Set<Int>) {
        val capped = if (value.size > 10_000) value.take(10_000).toSet() else value
        prefs.putString(anixartContestedKey, gson.toJson(capped)).apply()
    }

    private val anixartRepairV1Key = "anixart_server_repair_v1_done"

    fun isAnixartServerRepairV1Done(): Boolean =
        prefs.getBoolean(anixartRepairV1Key, false)

    fun setAnixartServerRepairV1Done() {
        prefs.putBoolean(anixartRepairV1Key, true).apply()
    }

    private val importBackfillDoneKey = "import_source_backfill_v2_done"
    // Нижняя граница restore-окна 09.09: вайп 00:12 по часам устройства (UTC+8,
    // т.е. 16:12 UTC) — оболочки новее созданы импортом. v1 смотрела на 21:12 UTC
    // (ошибка на 5 часов: MSK вместо часов устройства) и пометила 0.
    private val RESTORE_WIPE_EPOCH_MS = 1788883920000L

    /**
     * Разовый бэкфилл провенанса после restore 09.09: оболочки без пользовательского
     * содержимого (ни оценки, ни заметки, ни прогресса), созданные после вайпа, —
     * импорт; помечаем "anixart". Иначе вход в Shikimori создал бы ~750 рейтов из
     * импортных оболочек, а пуш Anixart — эхо. Эвристика самозаживающая: следующая
     * явная правка метку снимает.
     */
    fun backfillImportSourceForRestore(): Int = synchronized(BLOB_LOCK) {
        if (prefs.getBoolean(importBackfillDoneKey, false)) return@synchronized 0
        prefs.putBoolean(importBackfillDoneKey, true).apply()
        val current = readProfilesOrNull() ?: return@synchronized 0
        var marked = 0
        val updated = current.map { p ->
            if (p.kinopoiskId >= ANIME_ID_OFFSET && p.importSource == null &&
                p.userRating == null && p.note.isNullOrBlank() &&
                (p.watchedSeasons ?: 0) <= 0 && (p.watchedEpisodes ?: 0) <= 0 &&
                p.updatedAt >= RESTORE_WIPE_EPOCH_MS
            ) {
                marked++
                p.copy(importSource = "anixart")
            } else p
        }
        if (marked > 0) writeProfiles(updated)
        KLog.i("AnixartSync", "import backfill: marked $marked profile(s) as anixart-imported")
        marked
    }

    /**
     * Чистка legacy: аниме-профили (id >= ANIME_ID_OFFSET), созданные до унификации
     * типов, лежат с type=TV_SERIES. Идемпотентно; возвращает число исправленных.
     */
    fun migrateStaleAnimeTypes(): Int = synchronized(BLOB_LOCK) {
        val profiles = readProfilesOrNull() ?: return 0
        var fixed = 0
        val updated = profiles.map {
            if (it.kinopoiskId >= ANIME_ID_OFFSET && it.type != "ANIME") {
                fixed++
                it.copy(type = "ANIME")
            } else it
        }
        if (fixed > 0) writeProfiles(capProfiles(updated))
        fixed
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
        prefs.putString(playbackUsageKey, gson.toJson(capPlaybackUsage(updated))).apply()
    }

    /** Запись статистики usage целиком (облачный импорт/слияние) с тем же капом роста. */
    fun savePlaybackUsageStats(stats: PlaybackUsageStats) = synchronized(BLOB_LOCK) {
        prefs.putString(playbackUsageKey, gson.toJson(capPlaybackUsage(dropExpiredDubs(stats)))).apply()
    }

    private fun capPlaybackUsage(updated: PlaybackUsageStats): PlaybackUsageStats {
        // Bound growth: numeric Kodik labels accumulate fast across titles — keep the freshest.
        return updated.copy(
            dubs = updated.dubs.entries
                .sortedByDescending { it.value.lastUsedAt }
                .take(MAX_DUB_USAGE_ENTRIES)
                .associate { it.toPair() },
            titleDubs = updated.titleDubs.entries
                .sortedByDescending { it.value.lastUsedAt }
                .take(MAX_TITLE_DUB_USAGE_ENTRIES)
                .associate { it.toPair() }
        )
    }

    // ---- Resume-позиции (cross-session playback state, ключ — стабильный media-идентификатор) ----

    private fun readPlaybackPositions(): Map<String, PlaybackPosition> {
        val raw = prefs.getString(playbackPositionsKey, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, PlaybackPosition>>() {}.type
        return runCatching { gson.fromJson<Map<String, PlaybackPosition>>(raw, type) }
            .getOrNull().orEmpty()
    }

    /** Все resume-позиции (для облачного экспорта/слияния). */
    fun getPlaybackPositions(): Map<String, PlaybackPosition> = readPlaybackPositions()

    /** Запись позиций целиком (облачный импорт/слияние) с тем же капом роста. */
    fun savePlaybackPositions(positions: Map<String, PlaybackPosition>) = synchronized(BLOB_LOCK) {
        val capped = positions.entries
            .sortedByDescending { it.value.updatedAt }
            .take(MAX_PLAYBACK_POSITION_ENTRIES)
            .associate { it.toPair() }
        prefs.putString(playbackPositionsKey, gson.toJson(capped)).apply()
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
            upsertProfile(profile.copy(status = UserFilmStatus.WATCHING, importSource = null, updatedAt = now))
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

    /** Запись истории поиска целиком (облачный импорт/слияние) с тем же капом 20. */
    fun saveSearchHistory(history: List<SearchHistoryRecord>) = synchronized(BLOB_LOCK) {
        prefs.putString(searchHistoryKey, gson.toJson(history.take(20))).apply()
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
    return countries.orEmpty().any { country ->
        when (country.country?.trim()?.lowercase(Locale.forLanguageTag("ru"))) {
            "россия", "ссср" -> true
            else -> false
        }
    }
}

private fun FilmDetails.isRussianContent(): Boolean {
    return countries.orEmpty().any { country ->
        when (country.country?.trim()?.lowercase(Locale.forLanguageTag("ru"))) {
            "россия", "ссср" -> true
            else -> false
        }
    }
}
