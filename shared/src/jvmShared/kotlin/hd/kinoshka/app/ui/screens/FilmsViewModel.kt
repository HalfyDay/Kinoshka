package hd.kinoshka.app.ui.screens

import hd.kinoshka.app.util.log.KLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.sync.withLock
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import hd.kinoshka.app.data.local.HistoryRecord
import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.data.repo.anixartListToStatus
import hd.kinoshka.app.data.repo.toAnixartList
import hd.kinoshka.app.data.local.isCurated
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.local.ShikimoriAuthProvider
import hd.kinoshka.app.data.local.UserStateStoreBase
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImageItem
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.FilmLinkItem
import hd.kinoshka.app.data.model.FilmTrailer
import hd.kinoshka.app.data.model.FilterItem
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.formatSyncTimeMs
import hd.kinoshka.app.data.model.containsAnimeGenre
import hd.kinoshka.app.data.model.SeasonItem
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.utils.SearchQueryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// ContentType переехал в shared (jvmShared): hd.kinoshka.app.ui.screens.ContentType —
// пакет тот же, все использования продолжают резолвиться.

enum class HomeTab {
    CATALOG,
    HISTORY,
    MORE
}

enum class DiscoverCategory(val title: String, val apiType: String) {
    POPULAR("Популярное", "TOP_POPULAR_ALL"),
    TOP_250("Топ 250", "TOP_250_MOVIES"),
    SERIES("Сериалы", "TOP_250_TV_SHOWS")
}

data class LibraryUiItem(
    val kinopoiskId: Int,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val ratingText: String?,
    val type: String?,
    val isRussian: Boolean,
    val viewedAtMillis: Long?,
    val viewedAtLabel: String?,
    val status: UserFilmStatus?,
    val userRating: Int?,
    val note: String?,
    val watchedSeasons: Int?,
    val watchedEpisodes: Int?,
    val totalEpisodesInSeason: Int?,
    val totalSeasons: Int?,
    val totalEpisodes: Int?,
    val updatedAt: Long,
    // New-episode detection signals (anime). episodesAired = how many have aired so far for an
    // ongoing series; the badge shows when episodesAired > watchedEpisodes. nextEpisodeAt = ISO
    // UTC of the next scheduled episode (for "airs soon"). Null for films / unavailable.
    val episodesAired: Int? = null,
    val nextEpisodeAt: String? = null,
    // Shikimori metadata for grouping/stats (anime): raw kind ("tv"/"movie"/"ova"/"ona"/"special"),
    // release status ("anons"/"ongoing"/"released") and release year. Null for films / no cache.
    val animeKind: String? = null,
    val releaseStatus: String? = null,
    val releaseYear: Int? = null
)

data class SearchFilterState(
    val selectedCountryId: Int? = null,
    val selectedGenreId: Int? = null,
    val selectedOrder: String = "RATING",
    val selectedType: String = "ALL",
    val ratingFrom: Int? = null,
    val ratingTo: Int? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    // Anime specific filters (Shikimori API)
    val animeKind: String? = null,
    val animeStatus: String? = null,
    val animeRating: String? = null,
    val animeGenreId: Int? = null,
    val animeOrder: String = "popularity",
    val animeScoreFrom: Int? = null,
    /** Студия Shikimori (id из ссылок /animes/studio/{id} в новостях). */
    val animeStudioId: Int? = null,
    /** Сезон Shikimori: fall_2026, summer_2026 или год целиком (2026). */
    val animeSeason: String? = null
) {
    val isActive: Boolean
        get() = selectedCountryId != null || selectedGenreId != null || selectedOrder != "RATING" ||
                selectedType != "ALL" || ratingFrom != null || ratingTo != null || yearFrom != null || yearTo != null ||
                animeKind != null || animeStatus != null || animeRating != null || animeGenreId != null ||
                animeOrder != "popularity" || animeScoreFrom != null || animeSeason != null ||
                animeStudioId != null
}

val shikimoriGenres = listOf(
    FilterItem(id = 1, genre = "Экшен"),
    FilterItem(id = 2, genre = "Приключения"),
    FilterItem(id = 4, genre = "Комедия"),
    FilterItem(id = 8, genre = "Драма"),
    FilterItem(id = 10, genre = "Фэнтези"),
    FilterItem(id = 14, genre = "Ужасы"),
    FilterItem(id = 7, genre = "Детектив"),
    FilterItem(id = 22, genre = "Романтика"),
    FilterItem(id = 24, genre = "Фантастика"),
    FilterItem(id = 36, genre = "Повседневность"),
    FilterItem(id = 30, genre = "Спорт"),
    FilterItem(id = 37, genre = "Сверхъестественное"),
    FilterItem(id = 41, genre = "Триллер"),
    FilterItem(id = 62, genre = "Исэкай"),
    FilterItem(id = 18, genre = "Меха"),
    FilterItem(id = 19, genre = "Музыка"),
    FilterItem(id = 23, genre = "Школа"),
    FilterItem(id = 27, genre = "Сёнэн"),
    FilterItem(id = 25, genre = "Сёдзе"),
    FilterItem(id = 42, genre = "Сэйнэн"),
    FilterItem(id = 24, genre = "Этти"),
    FilterItem(id = 64, genre = "Хентай")
)

/**
 * Разовый запрос «открыть Библиотеку на статусе»: тап по легенде статистики
 * в Профиле. Потребляется экраном один раз (см. consumeLibraryDeepLink).
 */
data class LibraryDeepLink(
    val status: UserFilmStatus,
    val animeOnly: Boolean
)

/** Локальный статус -> строка статуса Shikimori API. */
private fun UserFilmStatus.toShikiStatus(): String = when (this) {
    UserFilmStatus.WATCHING -> "watching"
    UserFilmStatus.PLANNED -> "planned"
    UserFilmStatus.COMPLETED -> "completed"
    UserFilmStatus.REWATCHING -> "rewatching"
    UserFilmStatus.ON_HOLD -> "on_hold"
    UserFilmStatus.DROPPED -> "dropped"
}

/** Строка статуса рейта Shikimori -> локальный статус (зеркало Anixart для
 *  rate-backed тайтлов; неизвестное — null, не рискуем). */
private fun shikiRateStatusToUserStatus(status: String): UserFilmStatus? = when (status.lowercase()) {
    "watching" -> UserFilmStatus.WATCHING
    "planned" -> UserFilmStatus.PLANNED
    "completed" -> UserFilmStatus.COMPLETED
    "rewatching" -> UserFilmStatus.REWATCHING
    "on_hold" -> UserFilmStatus.ON_HOLD
    "dropped" -> UserFilmStatus.DROPPED
    else -> null
}

data class HomeUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val items: List<FilmItem> = emptyList(),
    val query: String = "",
    val isSearchResult: Boolean = false,
    val tab: HomeTab = HomeTab.CATALOG,
    val library: List<LibraryUiItem> = emptyList(),
    val profileAvatar: String = "🎬",
    val discoverCategory: DiscoverCategory = DiscoverCategory.POPULAR,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val loadingMore: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.CURRENT,
    val hideRussianContent: Boolean = false,
    val showHentaiInLibrary: Boolean = true,
    val librarySortReversed: Boolean = false,
    val librarySortType: hd.kinoshka.app.data.local.LibrarySortType = hd.kinoshka.app.data.local.LibrarySortType.LAST_VIEWED,
    val libraryGroupType: hd.kinoshka.app.data.local.LibraryGroupType = hd.kinoshka.app.data.local.LibraryGroupType.NONE,
    val discoverTileSize: FilmTileSize = FilmTileSize.MEDIUM,
    val libraryTileSize: FilmTileSize = FilmTileSize.MEDIUM,
    val showFpsCounter: Boolean = false,
    val filterState: SearchFilterState = SearchFilterState(),
    val availableGenres: List<FilterItem> = emptyList(),
    val availableCountries: List<FilterItem> = emptyList(),
    val showFilterSheet: Boolean = false,
    val contentType: ContentType = ContentType.FILMS,
    val shikimoriAuthState: hd.kinoshka.app.data.local.ShikimoriAuthState = hd.kinoshka.app.data.local.ShikimoriAuthState(),
    val anixartAuthState: hd.kinoshka.app.data.local.AnixartAuthState = hd.kinoshka.app.data.local.AnixartAuthState(),
    val calendarItems: List<hd.kinoshka.app.data.model.ShikimoriCalendarItem> = emptyList(),
    val topics: List<hd.kinoshka.app.data.model.ShikimoriTopic> = emptyList(),
    val calendarLoading: Boolean = false,
    val topicsLoading: Boolean = false,
    val playerMode: hd.kinoshka.app.data.local.PlayerMode = hd.kinoshka.app.data.local.PlayerMode.MPVEX,
    val searchHistory: List<hd.kinoshka.app.data.local.SearchHistoryRecord> = emptyList(),
    val isInstantSearch: Boolean = false,
    /** Лента «Обзора»: карусели кино и аниме одновременно (см. OverviewModels). */
    val overviewFilmSections: List<OverviewSection> = emptyList(),
    val overviewAnimeSections: List<OverviewSection> = emptyList(),
    /** Витрины сверху ленты: кино — обсуждаемое, аниме — скоро выйдет. */
    val overviewFilmHero: List<FilmItem> = emptyList(),
    val overviewAnimeHero: List<FilmItem> = emptyList(),
    val overviewLoading: Boolean = false,
    val overviewError: String? = null,
    /** Заголовок открытого раздела Обзора (кнопка «Все»): виден в сетке раздела. Null — главная лента. */
    val discoverTitle: String? = null,
    /** Разовый deep-link в Библиотеку из Профиля (тап по легенде). Null — нет запроса. */
    val libraryDeepLink: LibraryDeepLink? = null,
    /** Pull-to-refresh Библиотеки в процессе (индикатор PullToRefreshBox). */
    val libraryRefreshing: Boolean = false,
    /** Живой прогресс импорта библиотеки Anixart (catch-up после логина/вайпа).
     *  Null — тихо (steady-state). */
    val anixartImportProgress: AnixartImportProgress? = null
)

/** Живой прогресс импорта библиотеки Anixart: фаза + счётчик.
 *  total=0 — неопределённый (крутилка). */
data class AnixartImportProgress(
    val phase: String,
    val done: Int,
    val total: Int
)

/**
 * Шина прогресса импорта для платформенного слоя: системное уведомление и
 * foreground-сервис живут в app-модуле и ViewModel не видят — читают отсюда.
 * Дублирует uiState.anixartImportProgress (баннер внутри приложения).
 */
object AnixartImportBus {
    val flow = kotlinx.coroutines.flow.MutableStateFlow<AnixartImportProgress?>(null)
}

data class DetailsUiState(
    val loading: Boolean = false,
    val error: String? = null,
    /** true, когда страница не открыта, потому что Kinopoisk-тайтл оказался аниме (смотрится только через Shikimori). */
    val animeBlocked: Boolean = false,
    val item: FilmDetails? = null,
    val seasons: List<SeasonItem> = emptyList(),
    val similars: List<FilmLinkItem> = emptyList(),
    val relations: List<FilmLinkItem> = emptyList(),
    val fullChronology: List<FilmLinkItem> = emptyList(),
    val franchiseResponse: hd.kinoshka.app.data.model.ShikimoriFranchiseResponse? = null,
    val images: List<FilmImageItem> = emptyList(),
    /** YouTube-трейлер страницы из блока Кинопоиска/Shikimori; прямой поток mpvEx извлекает при нажатии. */
    val trailer: FilmTrailer? = null,
    val userProfile: UserFilmProfile? = null,
    val savingProfile: Boolean = false,
    val animeDetails: hd.kinoshka.app.data.model.ShikimoriAnimeDetails? = null,
    val animeCharacters: List<hd.kinoshka.app.data.model.ShikimoriRole> = emptyList()
)

class FilmsViewModel(
    private val repository: FilmsRepository,
    private val animeRepository: AnimeRepository,
    private val userStateStore: UserStateStoreBase,
    private val shikimoriAuthStore: ShikimoriAuthProvider? = null,
    private val anixartRepository: hd.kinoshka.app.data.repo.AnixartRepository? = null,
    private val anixartAuthStore: hd.kinoshka.app.data.local.AnixartAuthProvider? = null,
    /**
     * Хук облачной выгрузки (Яндекс Диск/WebDAV): платформа инжектит сюда свой
     * auto-upload. Дёргается из единой воронки пересборки библиотеки — через неё
     * проходят все мутации (редактор прогресса, adopts синков, пулы, удаления),
     * которые раньше до облака не доезжали вовсе (триггер был только в плеере).
     * Сам колбэк дебаунсится получателем; desktop — no-op по умолчанию.
     */
    private val onLibraryMutated: () -> Unit = {}
) : ViewModel() {

    // Пересборка библиотеки уходит на Dispatchers.Default (см. refreshAfterPlayerClosed),
    // поэтому поля, которые она читает, должны быть volatile для видимости между потоками.
    @Volatile
    private var cachedShikimoriRates: List<hd.kinoshka.app.data.model.ShikimoriUserRate> = emptyList()

    // Дисковый снапшот рейтов уже подтянут в cachedShikimoriRates (один раз за жизнь VM).
    // Без него первый кадр библиотеки строился с пустым кэшем и аниме из Shikimori
    // появлялись только после сетевого фетча.
    @Volatile
    private var shikimoriRatesSnapshotHydrated = false
    // userId владельца подтянутого снапшота: смена аккаунта гасит чужой список сразу.
    @Volatile
    private var snapshotUserId = 0

    // Snapshot of the Shikimori calendar fetched by loadCalendar(). buildLibraryItems reads this
    // instead of uiState.calendarItems because the calendar arrives asynchronously and uiState is
    // still being constructed the first time buildLibraryItems runs (reading uiState then is a
    // NPE on the not-yet-initialized State delegate).
    @Volatile
    private var cachedShikimoriCalendar: List<hd.kinoshka.app.data.model.ShikimoriCalendarItem> = emptyList()

    // Несортированная база библиотеки (последний результат buildLibraryItems). Пересборка
    // парсит большие JSON-блобы истории/профилей и вешает main-поток, поэтому смена
    // сортировки пересортирует кэш вместо полной пересборки.
    @Volatile private var libraryBaseCache: List<LibraryUiItem>? = null

    // In-flight search job. Cancelled + replaced on every new query so fast typing (instant
    // search) can't let an older, slower request clobber the newer results.
    private var searchJob: kotlinx.coroutines.Job? = null

    // Сериализация Shikimori-синка: фоновый рефреш (пул→пуш), точечные пуши из
    // saveUserProfile и пакетный пуш делят in-memory кэш рейтов. Без мьютекса точечный
    // пуш мог читать кэш посреди чужого фетча и перезаписывать сайт протухшими данными.
    // Всегда вызывать с захваченным мьютексом: refreshShikimoriRatesLocked,
    // pushDirtyAnimeRates, pushSingleAnimeRate.
    private val shikimoriSyncMutex = kotlinx.coroutines.sync.Mutex()

    // Общий темп фоновых префетчей деталей (добивка anime к рейтам + 18+-вердикты):
    // лимит Shikimori — 5rps, а два префетчера с семафорами 5 и 4 лупили до 9 запросов
    // параллельно и топили лог в 429 с ретраями. Пейсер держит ≤4rps на оба префетчера
    // разом; открытие карточки идёт мимо него (там важна скорость, дальше — кэш).
    private val detailsPrefetchMutex = kotlinx.coroutines.sync.Mutex()
    private var lastPrefetchNs = 0L
    private var prefetchBackoffUntilNs = 0L
    private var consecutivePrefetch429 = 0
    private var prefetchHalted = false

    /** In-flight дедуп деталей: параллельные запросы одного id ждут один общий. */
    private val detailsInFlight =
        java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Deferred<hd.kinoshka.app.data.model.ShikimoriAnimeDetails?>>()

    private suspend fun prefetchDetails(
        shikimoriId: Int
    ): hd.kinoshka.app.data.model.ShikimoriAnimeDetails? {
        // Кулдаун после 429-шторма (переживает рестарт): префетч молчит до метки.
        // Кулдаун истёк, а флаг жив (долгая сессия) — пробуем снова.
        if (System.currentTimeMillis() < userStateStore.getPrefetchCooloffUntilMs()) return null
        if (prefetchHalted) {
            prefetchHalted = false
            consecutivePrefetch429 = 0
        }
        val deferred = synchronized(detailsInFlight) {
            detailsInFlight.getOrPut(shikimoriId) {
                viewModelScope.async(kotlinx.coroutines.Dispatchers.IO) {
                    detailsPrefetchMutex.withLock {
                        val now = System.nanoTime()
                        var waitMs = 250L - (now - lastPrefetchNs) / 1_000_000L
                        val backoffMs = (prefetchBackoffUntilNs - now) / 1_000_000L
                        if (backoffMs > waitMs) waitMs = backoffMs
                        if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                        lastPrefetchNs = System.nanoTime()
                    }
                    val result = runCatching { animeRepository.details(shikimoriId) }
                    val is429 = (result.exceptionOrNull() as? retrofit2.HttpException)?.code() == 429
                    detailsPrefetchMutex.withLock {
                        if (is429) {
                            consecutivePrefetch429++
                            // Экспоненциальный откат общей паузой + стоп после серии: 429-шторм
                            // самоподдерживается (ретраи → новые 429 → кэш не пополняется → повтор).
                            val shift = minOf(consecutivePrefetch429, 4)
                            prefetchBackoffUntilNs = System.nanoTime() + (5L shl shift) * 1_000_000_000L
                            if (consecutivePrefetch429 >= 3 && !prefetchHalted) {
                                prefetchHalted = true
                                KLog.w(
                                    "ShikimoriSync",
                                    "prefetch: halting for 1h after $consecutivePrefetch429 consecutive 429s"
                                )
                                userStateStore.setPrefetchCooloffUntilMs(
                                    System.currentTimeMillis() + PREFETCH_COOLOFF_MS
                                )
                            }
                        } else if (result.isSuccess) {
                            consecutivePrefetch429 = 0
                        }
                    }
                    result.getOrNull()
                }
            }
        }
        return try {
            deferred.await()
        } finally {
            detailsInFlight.remove(shikimoriId, deferred)
        }
    }

    /** In-flight job ленты «Обзора»: один за раз, повторные вызовы — no-op пока активен. */
    private var overviewJob: kotlinx.coroutines.Job? = null

    // Состояние Anixart-синка — ДО init: init уже запускает refreshAnixartAuth(), а поля,
    // объявленные ниже init, в этот момент ещё null (краш Mutex.lock на null, 2026-09-07).
    // Правило: всё, до чего дотягивается init (прямо или через refresh), живёт выше него.
    /** Карта релиз Anixart -> shikimoriId из последнего пула (для пуша). */
    @Volatile
    private var anixartIdToShiki: Map<Int, Int> = emptyMap()

    /** Релиз Anixart -> списки, где он лежит (из последнего пула). */
    @Volatile
    private var anixartReleaseLists: Map<Int, Set<Int>> = emptyMap()

    @Volatile
    private var pushingAnixart = false

    @Volatile
    private var lastAnixartSyncMs = 0L

    /**
     * Shikimori-id без точного матча в каталоге Anixart (честный «нет в каталоге»).
     * Сессионное: не дёргаем поиск каждый синк. Ошибки сети сюда не пишем — их
     * повторит следующий синк. Трогать только под anixartSyncMutex.
     */
    private val anixartUnresolvable = mutableSetOf<Int>()

    /**
     * Релизы Anixart без точного матча в поиске Shikimori (честный «не нашли»).
     * Сессионное; ошибки сети не пишем. Трогать только под anixartSyncMutex.
     */
    private val anixartPullUnresolvable = mutableSetOf<Int>()

    /**
     * ShikimoriId, встреченные пулом в ≥2 списках с разными статусами (контест).
     * Пересчитывается каждым пулом, персистится во flush. Трогать под anixartSyncMutex.
     */
    private val anixartContestedShiki = mutableSetOf<Int>()

    /**
     * Сериализация Anixart-синка (паритет с shikimoriSyncMutex): пул, пуши и точечные
     * сверки делят in-memory карты. Всегда вызывать перечисленное под этим мьютексом.
     */
    private val anixartSyncMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Офлайн-индекс Shikimori (бандл assets): ленивая загрузка один раз за
     * жизнь VM. Нет бандла — null, пул идёт старым живым поиском.
     */
    private var offlineIndex: hd.kinoshka.app.data.source.ShikiOfflineIndex? = null
    private var offlineIndexTried = false

    private suspend fun loadOfflineIndex(): hd.kinoshka.app.data.source.ShikiOfflineIndex? {
        offlineIndex?.let { return it }
        if (offlineIndexTried) return null
        offlineIndexTried = true
        val t0 = System.nanoTime() / 1_000_000L
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                hd.kinoshka.app.data.source.ShikiIndexBridge.provider?.invoke()
            }.getOrNull()
        } ?: return null
        val parsed = withContext(Dispatchers.Default) {
            hd.kinoshka.app.data.source.ShikiOfflineIndex.parse(bytes)
        } ?: return null
        offlineIndex = parsed
        KLog.i(
            "AnixartSync",
            "offline-index: loaded in ${System.nanoTime() / 1_000_000L - t0}ms"
        )
        return parsed
    }

    /** In-flight job вердиктов (до init — его трогает добрасывающий проход из refresh). */
    private var adultVerdictJob: kotlinx.coroutines.Job? = null
    private var animeMetaJob: kotlinx.coroutines.Job? = null

    // Throttle for refreshAfterPlayerClosed(): ON_RESUME fires several times while navigating,
    // and rebuilding the library re-serializes the whole profile blob.
    private var lastResumeRefreshMs = 0L
    private companion object {
        const val RESUME_REFRESH_THROTTLE_MS = 1_000L

        /** Пауза между фоновыми синками Shikimori при возврате в приложение. */
        const val FOREGROUND_SYNC_THROTTLE_MS = 15 * 60_000L

        /** Молчание префетча деталей после серии 429 (сохраняется на диск, переживает рестарт). */
        const val PREFETCH_COOLOFF_MS = 60 * 60_000L

        /** Пауза между точечными сверками одного тайтла при открытии карточки. */
        const val DETAILS_RATE_CHECK_THROTTLE_MS = 60_000L

        /** Потолок пуша рейтов Shikimori: окно 25 голодало хвост (импортированные
         *  Anixart-оболочки append'ятся в конец и не доходили до сверки никогда).
         *  Дорога только сеть на различающихся (verify-GET/create), совпавшие —
         *  локальное сравнение. */
        const val MAX_SHIKI_PUSH_PER_SYNC = 200

        /** Потолок резолюций каталога Anixart за один синк (поиск несматченных
         *  тайтлов — до 3 POST на тайтл; дальше оставим следующим синкам).
         *  Поднят под первичную сходимость библиотек (~800 rate-backed тайтлов);
         *  честные промахи мемоизируются (персист) и повторно не ищутся. */
        const val MAX_ANIXART_RESOLVE_PER_SYNC = 100

        /** Потолок обратной резолюции за один пул (поиск Shikimori для релизов,
         *  не сматчившихся с библиотекой, — 1 запрос на тайтл с паузой 300мс). */
        const val MAX_ANIXART_PULL_RESOLVE_PER_SYNC = 40

        /** Ручные склейки релиз Anixart -> shikimoriId (разбор 09.09: сезоны/фильмы
         *  под одинаковыми названиями + синонимы честных промахов). Все id сверены
         *  с shikimori.io/api 09.09. Пин поверх id-карты и мемоизации промахов. */
        val ANIXART_PINNED_MAP: Map<Int, Int> = mapOf(
            // Сезоны, склеенные в S1-запись.
            1275 to 21881, // SAO TV-2 (2014)
            2625 to 36474, // SAO Alicization TV-1 (2018)
            467 to 8937, // Index II (2010)
            444 to 7791, // K-On! 2 = S2 (2010)
            637 to 10495, // Yuru Yuri S1 (2011)
            1095 to 16894, // Kuroko TV-2 = S2 (2013)
            1251 to 23327, // Space Dandy TV-2 = S2 (2014)
            2605 to 37510, // Mob Psycho TV-2 = S2 (2019)
            2034 to 5341, // Spice and Wolf II (2009)
            2611 to 37999, // Kaguya S1 (2019)
            // Фильмы/OVA, склеенные в TV-запись.
            390 to 7059, // Black Rock Shooter OVA (2010)
            1047 to 11577, // Steins;Gate Deja vu (2013)
            1522 to 28675, // Kyoukai no Kanata Mirai-hen (2015)
            1842 to 31989, // Euphonium Movie 1 (2016)
            16291 to 40080, // Quanzhi Gaoshou: Dianfeng Rongyao (2019)
            2984 to 38329, // Bunny Girl film (2019)
            1689 to 32380, // KonoSuba OVA (2016)
            2060 to 34626, // KonoSuba OVA-2 (2017)
            3009 to 38040, // KonoSuba Kurenai Densetsu (2019)
            2101 to 9260, // Kizumonogatari лотом (3 эп.) -> часть 1
            // Честные промахи поиска (синонимы/релевантность).
            379 to 1575, // Code Geass S1
            2774 to 1575, // Code Geass S1 (дубль релиза на Anixart)
            1173 to 13659, // OreImo TV-2 = S2 (2013)
            1185 to 19111, // Love Live TV-2 = S2 (2014)
            2275 to 32, // End of Evangelion (1997)
            2301 to 34612, // Saiki S2 (2018)
            2637 to 38249, // Saiki Kanketsuhen (финал, 2018)
            1919 to 30016, // Nanbaka TV-1 (2016)
            889 to 8769, // OreImo TV-1 = S1 (2010); был склеен с S2 через exact-путь без сверки года
            1611 to 31043, // Erased: дубль релиза (год тот же)
            2190 to 31043, // Erased: дубль релиза (год опечатан 2017 — та же запись)
            // Пограничные годы (тот же тайтл, топ-1 выдачи, спор только о годе):
            1627 to 27829, // Heavy Object: сплит-кур 10.2015–03.2016 (Anixart 2016 vs Shiki 2015)
            2741 to 762, // Bleach: Memories in the Rain OVA (Anixart 2005 vs Shiki 2004)
            2966 to 37522, // Pet (Anixart 2019 vs Shiki 2020)
            14611 to 36999, // Zoku Owarimonogatari (Anixart 2018 vs Shiki 2019)
            // 18913 Rick and Morty: The Anime — записи в Shikimori нет, честный пропуск.
        )

        /** Бюджет сетевых правок списков Anixart за один синк (перенос/добавление).
         *  Проверка «уже на месте» сети не требует и в бюджет не входит. */
        const val MAX_ANIXART_PUSH_OPS_PER_SYNC = 200

        /** Пауза между стартом кино- и аниме-веток Обзора — не упираемся в RPS обоих API. */
        const val OVERVIEW_STAGGER_MS = 400L

        /** Ступенчатая задержка перед запросами секций внутри ветки (лимит Shikimori: 5rps).
         *  Важно: слип всегда ДО semaphore.withPermit, а не внутри — иначе сон занимает
         *  пермит и сериализует всю ветку (хвост 2.8–3.5с держал 1 из 3 пермитов). */
        const val OVERVIEW_REQUEST_GAP_MS = 250L

        /** Добрасывающий проход 18+-вердиктов стартует с задержкой после init, чтобы
         *  не отъедать 5 rps Shikimori у секций первого экрана Обзора. Батч дешёвый
         *  (1 запрос на 50 тайтлов), поэтому пауза символическая. */
        const val ADULT_VERDICT_DEFER_MS = 3_000L

        /** «Новинки» кино: фильмы/сериалы начиная с этого года. */
        const val FRESH_YEAR_FROM = 2024

        /** Витрина сверху ленты: столько карточек без дублей каруселей. */
        const val OVERVIEW_HERO_TAKE = 5

        /** Превью-клип hanime1 на 18+-страницах выключен (протухающий токен, чужие тайтлы);
         *  переключение обратно включает фетч + карточку без прочих правок. */
        const val HENTAI_PREVIEW_ENABLED = false

        /** Жанры Shikimori, считающиеся 18+ для тумблера «Показывать хентай»
         *  (проверено по /api/genres: 12 Hentai, 539 Erotica, 9 Ecchi — паритет
         *  с подстроками isAdultBrief/isAdultAnime). */
        val ADULT_GENRE_IDS = listOf(12, 539, 9)
    }

    var uiState by mutableStateOf(buildInitialState())
        private set

    var detailsState by mutableStateOf(DetailsUiState())
        private set

    init {
        loadDiscoverFirstPage(uiState.discoverCategory)
        loadFilters()
        // Холодный старт = вход: пул + пуш локального (офлайн-правки улетают сразу,
        // с verify-gate это безопасно). Иначе правки перед убийством приложения
        // ждали бы следующего foreground-синка.
        refreshShikimoriAuth(pushLocalNewer = true, caller = "init")
        // Прогрев hanime-каталога для синхронного фильтра хентая: первые кадры библиотеки
        // строятся до окончания фонового warmup-а Application (isKnownHentai = false) и
        // пропускают 18+. Как только каталог готов — одна пересборка прячет известное сразу,
        // не дожидаясь отложенных сетевых вердиктов. Только при выключенном показе.
        viewModelScope.launch {
            if (!userStateStore.isHentaiVisibleInLibrary()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        hd.kinoshka.app.data.source.HentaiStreamResolver.preloadCatalog()
                    }
                }
                refreshLibraryAndAvatar()
            }
        }
        // Холодный старт = вход (паритет с Shikimori): пул + пуш локального.
        refreshAnixartAuth(pushLocalNewer = true)
        // Чистка legacy type=TV_SERIES у аниме-профилей (до унификации типов).
        viewModelScope.launch(Dispatchers.IO) {
            if (userStateStore.migrateStaleAnimeTypes() > 0) refreshLibraryAndAvatar()
        }
        // Тяжёлый добрасывающий проход вердиктов — после первого экрана, не вместе со штормом init.
        viewModelScope.launch {
            kotlinx.coroutines.delay(ADULT_VERDICT_DEFER_MS)
            ensureLibraryAdultVerdicts()
            ensureLibraryAnimeMeta()
        }
        loadCalendar()
        loadTopics()
        ensureOverviewLoaded()
        uiState = uiState.copy(searchHistory = userStateStore.getSearchHistory())
    }

    /** Reloads search history from storage (call after add/remove/clear to refresh the UI). */
    fun refreshSearchHistory() {
        uiState = uiState.copy(searchHistory = userStateStore.getSearchHistory())
    }

    fun addSearchQueryToHistory(query: String) {
        if (query.trim().isBlank()) return
        userStateStore.addSearchQuery(query, uiState.contentType.name)
        refreshSearchHistory()
    }

    fun removeSearchQueryFromHistory(query: String) {
        userStateStore.removeSearchQuery(query, uiState.contentType.name)
        refreshSearchHistory()
    }

    fun clearSearchHistory() {
        userStateStore.clearSearchHistory()
        refreshSearchHistory()
    }

    /**
     * Instant search entry point called on every keystroke. Debounced by the caller (Compose
     * LaunchedEffect) to avoid hammering the API. Cancels any in-flight search first.
     */
    fun onSearchQueryChanged(query: String) {
        uiState = uiState.copy(query = query)
        val clean = query.trim()
        if (clean.length < 2) {
            searchJob?.cancel()
            // Clear instant results when the query is too short, but don't wipe a discover feed.
            if (uiState.isInstantSearch) {
                uiState = uiState.copy(items = emptyList(), isSearchResult = false, isInstantSearch = false, loading = false)
            }
            return
        }
        loadSearchFirstPage(clean, instant = true)
    }

    private fun persistFreshShikimoriTokens(
        authState: hd.kinoshka.app.data.local.ShikimoriAuthState,
        accessToken: String,
        refreshToken: String?
    ) {
        shikimoriAuthStore?.saveSession(
            token = accessToken,
            refresh = refreshToken,
            userId = authState.userId,
            nickname = authState.nickname,
            avatarUrl = authState.avatarUrl
        )
        // Хранилище само uiState не трогает: без этого следующая Shikimori-операция
        // уходила бы со старым протухшим токеном.
        uiState = uiState.copy(
            shikimoriAuthState = authState.copy(accessToken = accessToken, refreshToken = refreshToken)
        )
    }

    /**
     * Сохраняет авторитетный список рейтов на диск для мгновенного первого кадра
     * библиотеки (см. гидратацию в buildLibraryItems). Сериализация — на IO:
     * на сотни рейтов с вложенным аниме это заметные миллисекунды для main.
     */
    private fun persistShikimoriRatesSnapshot(
        userId: Int,
        rates: List<hd.kinoshka.app.data.model.ShikimoriUserRate>
    ) {
        if (userId <= 0) return
        snapshotUserId = userId
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                userStateStore.saveShikimoriRatesSnapshot(
                    hd.kinoshka.app.data.local.ShikimoriRatesSnapshot(
                        userId = userId,
                        savedAtMs = System.currentTimeMillis(),
                        rates = rates
                    )
                )
            }
        }
    }

    /**
     * @param pushLocalNewer после пула отправить на сервер локально-более-свежий
     * прогресс (вход/возврат в сеть): иначе правки, сделанные офлайн или на другом
     * устройстве без пуша, молча расходились бы с сервером навсегда.
     */
    fun refreshShikimoriAuth(pushLocalNewer: Boolean = false, caller: String = "") {
        shikimoriAuthStore?.let { store ->
            val state = store.getAuthState()
            uiState = uiState.copy(shikimoriAuthState = state)
            if (state.isLoggedIn && state.userId > 0) {
                // Смена аккаунта: подтянутый из снапшота чужой список гасим сразу,
                // правильный приедет с фетчем ниже.
                if (snapshotUserId != 0 && snapshotUserId != state.userId) {
                    snapshotUserId = 0
                    cachedShikimoriRates = emptyList()
                }
                viewModelScope.launch {
                    // Пул и пуш — атомарно под мьютексом: точечный пуш из saveUserProfile
                    // ждёт конца фетча и сравнивается уже со свежими рейтами, а не со снапшотом.
                    shikimoriSyncMutex.withLock {
                    // Пока ждали мьютекс, полный пул уже мог отработать (холодный
                    // старт: init держит мьютекс, ON_RESUME встаёт в очередь) —
                    // проверять надо здесь, а не только в syncShikimoriOnForeground
                    // до ожидания. Иначе второй полный пул (та же гонка, что была
                    // у Anixart). Неудачный пул lastFullSyncMs не штампует —
                    // ретрай честно пойдёт полным путём.
                    if (caller == "foreground" &&
                        System.nanoTime() / 1_000_000L - lastFullSyncMs < FOREGROUND_SYNC_THROTTLE_MS
                    ) {
                        if (pushLocalNewer) pushDirtyAnimeRates()
                        return@withLock
                    }
                    val ratesResult = animeRepository.getUserRates(state.userId)
                    // Если оба эндпоинта Shikimori упали — сохраняем последний известный список.
                    // Иначе одна временная ошибка сети стирала бы всю Shikimori-часть библиотеки.
                    val rates = ratesResult.getOrNull()
                    if (rates == null) {
                        uiState = uiState.copy(error = "Не удалось обновить список Shikimori. Показаны последние данные.")
                        return@withLock
                    }
                    // Пул успешен: foreground-вызов следом (холодный старт: init + ON_RESUME)
                    // пойдёт коротким путём (только пуш) вместо повторного bulk-пула.
                    lastFullSyncMs = System.nanoTime() / 1_000_000L
                    // First, populate from local cache
                    val localCache = userStateStore.getShikimoriAnimeCache()
                    val ratesWithLocalCache = rates.map { rate ->
                        if (rate.anime == null && rate.targetId > 0) {
                            val cached = localCache[rate.targetId]
                            if (cached != null) {
                                val animeItem = hd.kinoshka.app.data.model.ShikimoriAnimeItem(
                                    id = cached.shikimoriId,
                                    name = cached.name,
                                    russian = cached.russian,
                                    image = null,
                                    url = null,
                                    kind = cached.kind,
                                    score = cached.score,
                                    status = cached.status,
                                    episodes = cached.episodes,
                                    episodesAired = cached.episodesAired
                                )
                                rate.copy(anime = animeItem)
                            } else rate
                        } else rate
                    }
                    cachedShikimoriRates = ratesWithLocalCache
                    // Диагностика часов: если время телефона убежало вперёд, локальные метки
                    // выглядят новее серверных и пуш затирает сайт. Сравни phone= с site= в
                    // строках adopt/push ниже — они должны идти в ногу с реальным временем.
                    val phoneNow = System.currentTimeMillis()
                    KLog.i(
                        "ShikimoriSync",
                        "sync: fetched ${ratesWithLocalCache.size} rates " +
                            "phone=[${formatSyncTimeMs(phoneNow)} $phoneNow " +
                            "${java.time.ZoneId.systemDefault()}]" +
                            (caller.takeIf { it.isNotBlank() }?.let { " src=$it" } ?: "")
                    )
                    persistShikimoriRatesSnapshot(state.userId, ratesWithLocalCache)
                    // Сверка с локальными профилями до пересборки (чистый last-write-wins:
                    // серверно-новое забирается целиком, локально-новое не трогается — его
                    // отправит пуш ниже). Иначе правки с сайта Shikimori не доезжали бы до библиотеки.
                    withContext(Dispatchers.IO) { userStateStore.adoptShikimoriRates(ratesWithLocalCache) }
                    // Тяжёлая пересборка (парсинг JSON-блобов) — вне main, иначе дроп кадров.
                    val library = withContext(Dispatchers.Default) { buildLibraryItems() }
                    uiState = uiState.copy(library = library)
                    if (pushLocalNewer) pushDirtyAnimeRates()

                    // Fetch missing details: сначала батч кратких объектов (1 запрос
                    // на 50 тайтлов), непокрытое — поштучно с пейсингом (см. prefetchDetails).
                    val missingDetailsRates = ratesWithLocalCache.filter { it.anime == null && it.targetId > 0 }
                    if (missingDetailsRates.isNotEmpty()) {
                        val updatedRates = ratesWithLocalCache.toMutableList()
                        val batch = missingDetailsRates.take(200)
                        // Сеть, парсинг батча и запись дискового кэша — вне main: блок рефреша
                        // выполняется на Main-потоке и вешал бы кадры (updatedRates — локальный
                        // список, его правка на IO безопасна).
                        val brief = withContext(Dispatchers.IO) {
                            val fetched = fetchAnimeBrief(batch.map { it.targetId })
                            val diskCache = userStateStore.getShikimoriAnimeCache()
                            // Одна запись кэша на весь батч: поштучные read-modify-write
                            // парсили и сериализовали весь блоб на каждую запись.
                            val toSave = mutableListOf<hd.kinoshka.app.data.local.ShikimoriAnimeCache>()
                            for (rate in batch) {
                                val item = fetched[rate.targetId] ?: continue
                                val idx = updatedRates.indexOfFirst { it.id == rate.id || (it.targetId == rate.targetId && it.targetId > 0) }
                                if (idx >= 0) {
                                    updatedRates[idx] = updatedRates[idx].copy(anime = item)
                                }
                                toSave.add(briefToCache(rate.targetId, item, diskCache[rate.targetId]))
                            }
                            userStateStore.saveShikimoriAnimeInfos(toSave)
                            fetched
                        }
                        val leftovers = batch.filter { brief[it.targetId] == null }
                        if (leftovers.isNotEmpty()) {
                            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
                            val deferreds = leftovers.map { rate ->
                                async {
                                    semaphore.acquire()
                                    try {
                                        runCatching {
                                            // Пейсинг + дедуп (см. prefetchDetails): без них параллельная
                                            // добивка упиралась в 429 и держала мьютекс синка ретраями.
                                            val details = prefetchDetails(rate.targetId) ?: return@runCatching null
                                            val animeItem = hd.kinoshka.app.data.model.ShikimoriAnimeItem(
                                                id = details.id,
                                                name = details.name,
                                                russian = details.russian,
                                                image = details.image,
                                                url = details.url,
                                                kind = details.kind,
                                                score = details.score,
                                                status = details.status,
                                                episodes = details.episodes,
                                                episodesAired = details.episodesAired
                                            )
                                            val idx = updatedRates.indexOfFirst { it.id == rate.id || (it.targetId == rate.targetId && it.targetId > 0) }
                                            if (idx >= 0) {
                                                updatedRates[idx] = updatedRates[idx].copy(anime = animeItem)
                                            }
                                            hd.kinoshka.app.data.local.ShikimoriAnimeCache(
                                                shikimoriId = details.id,
                                                name = details.name,
                                                russian = details.russian,
                                                posterUrl = animeItem.posterUrl,
                                                episodes = details.episodes,
                                                episodesAired = details.episodesAired,
                                                kind = details.kind,
                                                score = details.score,
                                                status = details.status,
                                                year = details.airedOn?.take(4)?.toIntOrNull(),
                                                isAdult = isAdultAnime(details)
                                            )
                                        }.getOrNull()
                                    } finally {
                                        semaphore.release()
                                    }
                                }
                            }
                            // Одна запись кэша на всю добивку (см. батч выше); сериализация
                            // двух тысяч записей — вне Main, иначе дроп кадров.
                            val leftoverEntries = deferreds.mapNotNull { it.await() }
                            withContext(Dispatchers.IO) {
                                userStateStore.saveShikimoriAnimeInfos(leftoverEntries)
                            }
                        }
                        cachedShikimoriRates = updatedRates
                        persistShikimoriRatesSnapshot(state.userId, updatedRates)
                        val refreshedLibrary = withContext(Dispatchers.Default) { buildLibraryItems() }
                        uiState = uiState.copy(library = refreshedLibrary)
                        ensureLibraryAdultVerdicts()
                    }
                    } // withLock
                }
            } else {
                // Разлогин не стирает библиотеку: тайтлы Shikimori остаются из in-memory списка
                // и из снапшота (гидратация в buildLibraryItems даёт их и после перезапуска).
                viewModelScope.launch {
                    val offlineLibrary = withContext(Dispatchers.Default) { buildLibraryItems() }
                    uiState = uiState.copy(library = offlineLibrary)
                }
                ensureLibraryAdultVerdicts()
            }
        }
    }

    @Volatile
    private var lastForegroundSyncMs = 0L

    /** Монотонные мс последнего успешного пула рейтов (любым путём: init/логин/foreground). */
    @Volatile
    private var lastFullSyncMs = 0L

    /**
     * Синк при возврате приложения на передний план (троттлинг 15 минут):
     * пул серверных рейтов + пуш локально-более-свежего прогресса.
     * Закрывает рассинхрон «посмотрел на телефоне 1 — на телефоне 2 не приехало».
     */
    fun syncShikimoriOnForeground() {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastForegroundSyncMs < FOREGROUND_SYNC_THROTTLE_MS) return
        lastForegroundSyncMs = now
        val auth = uiState.shikimoriAuthState
        if (!auth.isLoggedIn || auth.userId <= 0) return
        // Полный пул только что отработал (init/логин при холодном старте): bulk свежий —
        // только допихиваем локальное, без повторного тяжёлого пула и добивки деталей.
        if (now - lastFullSyncMs < FOREGROUND_SYNC_THROTTLE_MS) {
            viewModelScope.launch {
                shikimoriSyncMutex.withLock { pushDirtyAnimeRates() }
            }
            return
        }
        refreshShikimoriAuth(pushLocalNewer = true, caller = "foreground")
    }

    /**
     * Точечный пуш одного аниме-рейта по правилу last-write-wins.
     * Вызывать только под shikimoriSyncMutex.
     *
     * Сначала — свежий рейт с сервера точечным запросом: in-memory кэш на холодном
     * старте это дисковый снапшот и мог протухнуть, а сохранение карточки, открытой
     * до фонового пула, иначе перезаписывало сайт старыми данными (тот самый откат).
     * Сервер новее локального — забираем его и не пушим. Локальное новее (или рейта
     * нет) — пушим локальное. Значения совпали — ничего не делаем.
     */
    private suspend fun pushSingleAnimeRate(
        kinopoiskId: Int,
        shikiStatus: String,
        episodes: Int?,
        rating: Int?,
        rewatches: Int?
    ) {
        val shikimoriId = kinopoiskId - ANIME_ID_OFFSET
        val authState = uiState.shikimoriAuthState
        var token = authState.accessToken
        if (!authState.isLoggedIn || token == null || authState.userId <= 0) {
            KLog.w("ShikimoriSync", "pushSingle: not logged in or no access token")
            return
        }
        // Свежая серверная правда вместо кэша; провал запроса — работаем с кэшем как раньше.
        val serverRate = freshestRateForTarget(authState.userId, shikimoriId)
        if (serverRate == null) {
            KLog.w("ShikimoriSync", "pushSingle: fresh fetch failed for shikimoriId=$shikimoriId, falling back to cache")
        }
        val profile = withContext(Dispatchers.IO) { userStateStore.getProfile(kinopoiskId) }
        val localUpdatedAt = profile?.updatedAt ?: 0L
        val serverTime = serverRate?.getUpdatedEpochMillis() ?: 0L
        if (serverRate != null && serverTime > localUpdatedAt) {
            // Сайт (или другое устройство) новее: забираем, пуш отменяется.
            KLog.d(
                "ShikimoriSync",
                "pushSingle: shikimoriId=$shikimoriId server-newer, adopting " +
                    "ep(local=${profile?.watchedEpisodes ?: 0} server=${serverRate.episodes}) " +
                    "app=[${formatSyncTimeMs(localUpdatedAt)} $localUpdatedAt] " +
                    "site=[${formatSyncTimeMs(serverTime)} raw='${serverRate.updatedAt}']"
            )
            withContext(Dispatchers.IO) { userStateStore.adoptShikimoriRates(listOf(serverRate)) }
            val current = cachedShikimoriRates.filterNot { it.targetId == shikimoriId } + serverRate
            cachedShikimoriRates = current
            persistShikimoriRatesSnapshot(authState.userId, current)
            refreshLibraryAndAvatar()
            if (detailsState.item?.kinopoiskId == kinopoiskId) {
                detailsState = detailsState.copy(userProfile = getUserProfileForFilm(kinopoiskId))
            }
            return
        }
        if (serverRate != null &&
            (episodes == null || episodes == serverRate.episodes) &&
            shikiStatus.equals(serverRate.status, ignoreCase = true) &&
            (rating ?: 0) == serverRate.score
        ) {
            // Значения уже совпали (эхо прошлого пуша) — лишний запрос не нужен.
            KLog.d("ShikimoriSync", "pushSingle: already in sync for shikimoriId=$shikimoriId")
            return
        }
        KLog.d(
            "ShikimoriSync",
            "pushSingle: shikimoriId=$shikimoriId PUSH local-newer " +
                "ep(local=${profile?.watchedEpisodes} server=${serverRate?.episodes}) " +
                "app=[${formatSyncTimeMs(localUpdatedAt)} $localUpdatedAt] " +
                "site=[${formatSyncTimeMs(serverTime)} raw='${serverRate?.updatedAt}']"
        )
        suspend fun attempt(t: String): hd.kinoshka.app.data.model.ShikimoriUserRate? =
            if (serverRate != null) {
                KLog.d("ShikimoriSync", "pushSingle: updating rate id=${serverRate.id}")
                animeRepository.updateUserRate(
                    token = t,
                    rateId = serverRate.id,
                    status = shikiStatus,
                    episodes = episodes,
                    score = rating,
                    rewatches = rewatches
                )
            } else {
                KLog.d("ShikimoriSync", "pushSingle: creating rate for targetId=$shikimoriId")
                animeRepository.createUserRate(
                    token = t,
                    userId = authState.userId,
                    targetId = shikimoriId,
                    status = shikiStatus,
                    episodes = episodes ?: 0,
                    score = rating ?: 0,
                    rewatches = rewatches
                )
            }
        var result = attempt(token)
        // Create при существующей серверной оценке может дать 422 (а может молча сделать
        // upsert поверх неё — см. инцидент 2026-09-07): подтягиваем свежие рейты и повторяем
        // как update, иначе прогресс «не сохраняется».
        if (result == null && serverRate == null && authState.userId > 0) {
            animeRepository.getUserRates(authState.userId).getOrNull()?.let { rates ->
                cachedShikimoriRates = rates
                rates.firstOrNull { it.targetId == shikimoriId }?.let { found ->
                    KLog.d("ShikimoriSync", "pushSingle: found server rate id=${found.id} after create failed, updating")
                    result = animeRepository.updateUserRate(
                        token = token,
                        rateId = found.id,
                        status = shikiStatus,
                        episodes = episodes,
                        score = rating,
                        rewatches = rewatches
                    )
                }
            }
        }
        if (result == null && authState.refreshToken != null) {
            KLog.d("ShikimoriSync", "pushSingle: token expired, attempting refresh...")
            val newTokenResponse = animeRepository.refreshToken(authState.refreshToken)
            if (newTokenResponse != null) {
                persistFreshShikimoriTokens(authState, newTokenResponse.accessToken, newTokenResponse.refreshToken)
                token = newTokenResponse.accessToken
                KLog.d("ShikimoriSync", "pushSingle: token refreshed, retrying...")
                result = attempt(token)
            } else {
                KLog.e("ShikimoriSync", "pushSingle: failed to refresh token, user needs to re-login")
            }
        }
        if (result != null) {
            val pushed = result
            // Контроль эха: сервер должен вернуть отправленное. Расхождение — признак того,
            // что create/update задел не ту строку (upsert вместо 422).
            if (episodes != null && pushed.episodes != episodes) {
                KLog.w(
                    "ShikimoriSync",
                    "pushSingle: shikimoriId=$shikimoriId server echo ep=${pushed.episodes} " +
                        "!= sent $episodes (rate id=${pushed.id}) — check the site value"
                )
            }
            val current = cachedShikimoriRates.toMutableList()
            val idx = current.indexOfFirst { it.targetId == shikimoriId }
            if (idx >= 0) current[idx] = pushed else current.add(pushed)
            cachedShikimoriRates = current
            // Якорение часов (только метка — значения только что отправлены).
            withContext(Dispatchers.IO) {
                userStateStore.anchorProfileUpdatedAt(kinopoiskId, pushed.getUpdatedEpochMillis())
            }
            refreshLibraryAndAvatar()
        } else {
            KLog.e("ShikimoriSync", "pushSingle: sync failed for shikimoriId=$shikimoriId, resyncing from server")
            refreshShikimoriAuth(caller = "pushSingle-retry")
        }
    }

    /** Последняя точечная сверка карточки (kpId → монотонные мс): повороты и переоткрытия сеть не дёргают. */
    private val detailsRateCheckAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * Свежайший рейт из точечного запроса и in-memory union-кэша (без полного bulk-пула:
     * consult v1+v2 на каждое открытие карточки стоил 2 тяжёлых запроса). Кэш тут —
     * последний union-пул (секунды/минуты давности), point — только что с сервера.
     */
    private suspend fun freshestRateForTarget(
        userId: Int,
        shikimoriId: Int
    ): hd.kinoshka.app.data.model.ShikimoriUserRate? {
        val point = animeRepository.getUserRatePoint(userId, shikimoriId)
        val cached = cachedShikimoriRates.firstOrNull { it.targetId == shikimoriId }
        if (point != null && cached != null &&
            (point.getUpdatedEpochMillis() != cached.getUpdatedEpochMillis() ||
                point.episodes != cached.episodes)
        ) {
            KLog.d(
                "ShikimoriSync",
                "rate target=$shikimoriId: point(ep=${point.episodes} '${point.updatedAt}') vs " +
                    "cached(ep=${cached.episodes} '${cached.updatedAt}'), taking fresher"
            )
        }
        return listOfNotNull(point, cached).maxByOrNull { it.getUpdatedEpochMillis() }
    }

    /**
     * Точечная сверка тайтла с Shikimori при открытии карточки (1 дешёвый GET):
     * посмотрел на телефоне 1 → открыл карточку на телефоне 2 → прогресс уже свежий,
     * не дожидаясь 15-минутного фонового синка. LWW как везде: серверно-новое забирается
     * (adopt + обновление профиля карточки), локально-новое не трогается (его отправит пуш).
     */
    fun refreshRateForDetails(kinopoiskId: Int) {
        if (kinopoiskId < ANIME_ID_OFFSET) return
        val auth = uiState.shikimoriAuthState
        if (!auth.isLoggedIn || auth.userId <= 0) return
        val now = System.nanoTime() / 1_000_000L
        if (now - (detailsRateCheckAt[kinopoiskId] ?: 0L) < DETAILS_RATE_CHECK_THROTTLE_MS) return
        detailsRateCheckAt[kinopoiskId] = now
        viewModelScope.launch {
            shikimoriSyncMutex.withLock {
                val shikimoriId = kinopoiskId - ANIME_ID_OFFSET
                val fresh = freshestRateForTarget(auth.userId, shikimoriId)
                    ?: return@withLock
                // Кэш: протухшее не вписываем, совпавшее не переписываем (иначе каждая
                // открытая карточка пересохраняла бы мегабайтный снапшот в prefs).
                val current = cachedShikimoriRates.toMutableList()
                val idx = current.indexOfFirst { it.targetId == shikimoriId }
                if (idx >= 0) {
                    val old = current[idx]
                    if (fresh.getUpdatedEpochMillis() < old.getUpdatedEpochMillis()) return@withLock
                    if (fresh.updatedAt == old.updatedAt && fresh.episodes == old.episodes &&
                        fresh.status.equals(old.status, ignoreCase = true) && fresh.score == old.score
                    ) return@withLock
                    current[idx] = fresh
                } else {
                    current.add(fresh)
                }
                cachedShikimoriRates = current
                persistShikimoriRatesSnapshot(auth.userId, current)
                val adopted = withContext(Dispatchers.IO) { userStateStore.adoptShikimoriRates(listOf(fresh)) }
                if (adopted > 0) {
                    KLog.d("ShikimoriSync", "details: shikimoriId=$shikimoriId adopted fresh rate on open")
                    refreshLibraryAndAvatar()
                }
                // Профиль открытой карточки — из свежих данных (adopt мог пропустить, если
                // локальное новее: тогда на экране и так уже локальное, обновляем для верности).
                if (detailsState.item?.kinopoiskId == kinopoiskId) {
                    val profile = withContext(Dispatchers.Default) { getUserProfileForFilm(kinopoiskId) }
                    detailsState = detailsState.copy(userProfile = profile)
                }
            }
        }
    }

    @Volatile
    private var pushingAnimeRates = false

    /**
     * Отправляет на Shikimori локальный прогресс, которого нет на сервере:
     * просмотры из плеера (пишутся мимо ViewModel и сами не пушатся) и правки,
     * сделанные офлайн/после выхода. Вызывать только под shikimoriSyncMutex и
     * только после свежего пула: пул уже применил серверно-новое
     * (adoptShikimoriRates), кэш свежий. Правило last-write-wins по датам:
     * пушится только локально-более-новое; серверно-новое пропускается.
     * Ничего не затирает молча: при неуспехе только лог, без рефреша (иначе цикл).
     */
    private suspend fun pushDirtyAnimeRates() {
        // Провенанс restore: иначе вход после вайпа создавал бы сотни рейтов
        // из импортных оболочек. One-shot, дальше флаг в персисте.
        withContext(Dispatchers.IO) { userStateStore.backfillImportSourceForRestore() }
        val auth = uiState.shikimoriAuthState
        val token0 = auth.accessToken ?: return
        if (!auth.isLoggedIn || auth.userId <= 0 || pushingAnimeRates) return
        pushingAnimeRates = true
            try {
                var token = token0
                val profiles = withContext(Dispatchers.IO) { userStateStore.getProfiles() }
                    // Импортные оболочки (restore) не пушим, пока их не коснулась явная
                    // правка — иначе создавали бы рейты из серверного эха.
                    .filter { it.kinopoiskId >= ANIME_ID_OFFSET && it.importSource == null && (it.status != null || (it.watchedEpisodes ?: 0) > 0) }
                if (profiles.isEmpty()) return
                val serverByTarget = cachedShikimoriRates.associateBy { it.targetId }
                var tokenRefreshed = false
                var pushed = 0
                // Пакетный пуш ограничиваем: первая синхронизация большой локальной
                // библиотеки не должна спамить API сотнями запросов — остаток уедет
                // следующими синками.
                // Ленивая контрольная сверка для кандидатов без bulk-рейта: bulk-список мог
                // отставать (серверный кэш после свежей правки на сайте — инцидент 2026-09-07:
                // «рейта нет» → POST → upsert поверх свежего). Один общий union-запрос на всех
                // вместо каскада фолбэков на каждый тайтл.
                var unionByTarget: Map<Int, hd.kinoshka.app.data.model.ShikimoriUserRate>? = null
                suspend fun unionRate(targetId: Int): hd.kinoshka.app.data.model.ShikimoriUserRate? {
                    if (unionByTarget == null) {
                        val union = animeRepository.getUserRates(auth.userId).getOrNull()
                        if (union != null) {
                            withContext(Dispatchers.IO) { userStateStore.adoptShikimoriRates(union) }
                            val merged = cachedShikimoriRates.associateBy { it.targetId }.toMutableMap()
                            for (r in union) {
                                val old = merged[r.targetId]
                                if (old == null || r.getUpdatedEpochMillis() >= old.getUpdatedEpochMillis()) {
                                    merged[r.targetId] = r
                                }
                            }
                            cachedShikimoriRates = merged.values.toList()
                            persistShikimoriRatesSnapshot(auth.userId, cachedShikimoriRates)
                        }
                        unionByTarget = union?.associateBy { it.targetId } ?: emptyMap()
                    }
                    return unionByTarget?.get(targetId)
                }
                suspend fun upsertCached(rate: hd.kinoshka.app.data.model.ShikimoriUserRate) {
                    val current = cachedShikimoriRates.toMutableList()
                    val idx = current.indexOfFirst { it.targetId == rate.targetId }
                    if (idx >= 0) current[idx] = rate else current.add(rate)
                    cachedShikimoriRates = current
                }
                for (profile in profiles.take(MAX_SHIKI_PUSH_PER_SYNC)) {
                    val shikimoriId = profile.kinopoiskId - ANIME_ID_OFFSET
                    val server = serverByTarget[shikimoriId]
                    val localEp = profile.watchedEpisodes ?: 0
                    val localStatus = profile.status?.toShikiStatus()
                    val localScore = profile.userRating?.takeIf { it > 0 } ?: 0
                    // Быстрый предфильтр по bulk (без сети): сервер строго новее — adopt его
                    // уже применил, эхо пуша — делать нечего. Остальное — на сверку ниже.
                    if (server != null) {
                        val serverTime = server.getUpdatedEpochMillis()
                        if (serverTime > profile.updatedAt) {
                            if (localEp != server.episodes) {
                                KLog.d(
                                    "ShikimoriSync",
                                    "push: shikimoriId=$shikimoriId SKIP server-newer " +
                                        "ep(local=$localEp server=${server.episodes}) " +
                                        "app=[${formatSyncTimeMs(profile.updatedAt)} ${profile.updatedAt}] " +
                                        "site=[${formatSyncTimeMs(serverTime)} raw='${server.updatedAt}']"
                                )
                            }
                            continue
                        }
                        if (serverTime == profile.updatedAt &&
                            localEp == server.episodes &&
                            (localStatus == null || localStatus == server.status.lowercase()) &&
                            localScore == server.score
                        ) continue
                    } else if (profile.status == null && localEp <= 0 && localScore <= 0) {
                        continue
                    }
                    // Контрольная сверка перед ЛЮБОЙ записью: bulk мог не содержать свежий
                    // рейт (отставание) или содержать протухший. Пишем только если локальное
                    // новее свежепроверенного — иначе забираем серверное и молчим.
                    var effective = server ?: unionRate(shikimoriId)?.also {
                        KLog.d(
                            "ShikimoriSync",
                            "push: shikimoriId=$shikimoriId verify: bulk missed, union found " +
                                "ep=${it.episodes} '${it.updatedAt}'"
                        )
                    }
                    animeRepository.getUserRatePoint(auth.userId, shikimoriId)?.let { point ->
                        val cur = effective
                        if (cur == null || point.getUpdatedEpochMillis() >= cur.getUpdatedEpochMillis()) {
                            if (cur == null || point.id != cur.id || point.episodes != cur.episodes ||
                                point.updatedAt != cur.updatedAt
                            ) {
                                KLog.d(
                                    "ShikimoriSync",
                                    "push: shikimoriId=$shikimoriId verify: point ep=${point.episodes} " +
                                        "'${point.updatedAt}' vs known ep=${cur?.episodes} '${cur?.updatedAt}', taking point"
                                )
                            }
                            effective = point
                        }
                    }
                    val eff = effective
                    if (eff != null) {
                        val effTime = eff.getUpdatedEpochMillis()
                        if (effTime > profile.updatedAt) {
                            KLog.d(
                                "ShikimoriSync",
                                "push: shikimoriId=$shikimoriId SKIP verify-server-newer, adopting " +
                                    "ep(local=$localEp server=${eff.episodes}) " +
                                    "app=[${formatSyncTimeMs(profile.updatedAt)} ${profile.updatedAt}] " +
                                    "site=[${formatSyncTimeMs(effTime)} raw='${eff.updatedAt}']"
                            )
                            withContext(Dispatchers.IO) { userStateStore.adoptShikimoriRates(listOf(eff)) }
                            upsertCached(eff)
                            continue
                        }
                        if (localEp == eff.episodes &&
                            (localStatus == null || localStatus == eff.status.lowercase()) &&
                            localScore == eff.score
                        ) {
                            // Значения совпали со свежепроверенными — только освежаем кэш.
                            upsertCached(eff)
                            continue
                        }
                        if (effTime <= 0L) {
                            KLog.w(
                                "ShikimoriSync",
                                "push: shikimoriId=$shikimoriId pushing with unparsable server time " +
                                    "'${eff.updatedAt}' — values differ, local is assumed newer"
                            )
                        }
                    }
                    // Локальное новее свежепроверенного (или рейта точно нет) — пушим как есть,
                    // включая осознанное уменьшение серий (сброс при пересмотре).
                    KLog.d(
                        "ShikimoriSync",
                        "push: shikimoriId=$shikimoriId PUSH " +
                            "ep(local=$localEp server=${eff?.episodes}) " +
                            "status(local=$localStatus server=${eff?.status}) " +
                            "score(local=$localScore server=${eff?.score}) " +
                            "app=[${formatSyncTimeMs(profile.updatedAt)} ${profile.updatedAt}] " +
                            "site=[${formatSyncTimeMs(eff?.getUpdatedEpochMillis() ?: 0L)} raw='${eff?.updatedAt}']"
                    )
                    val episodesToSend: Int
                    // Создание COMPLETED-рейта с нулевыми сериями (оболочка из
                    // Anixart-импорта без прогресса) гадит на сайт: completed 0/N.
                    // Завершённое значит просмотренное целиком — отправляем итог,
                    // если известен; иначе ждём мета-добивки следующим синком.
                    if (eff == null && localStatus == "completed" && localEp <= 0) {
                        val total = profile.totalEpisodes ?: 0
                        if (total <= 0) {
                            KLog.d(
                                "ShikimoriSync",
                                "push: shikimoriId=$shikimoriId SKIP completed-shell without totals, " +
                                    "waiting for meta backfill"
                            )
                            continue
                        }
                        episodesToSend = total
                    } else {
                        episodesToSend = localEp
                    }
                    val rewatches = profile.watchedSeasons?.takeIf { it > 0 }
                    suspend fun attempt(t: String): hd.kinoshka.app.data.model.ShikimoriUserRate? =
                        if (eff != null) {
                            animeRepository.updateUserRate(t, eff.id, localStatus, episodesToSend, localScore, rewatches)
                        } else {
                            animeRepository.createUserRate(t, auth.userId, shikimoriId, localStatus ?: "watching", episodesToSend, localScore, rewatches)
                        }
                    var result = attempt(token)
                    if (result == null && !tokenRefreshed && auth.refreshToken != null) {
                        val fresh = animeRepository.refreshToken(auth.refreshToken)
                        if (fresh != null) {
                            persistFreshShikimoriTokens(auth, fresh.accessToken, fresh.refreshToken)
                            token = fresh.accessToken
                            tokenRefreshed = true
                            result = attempt(token)
                        }
                    }
                    if (result != null) {
                        // Контроль эха: сервер должен вернуть то, что мы отправили. Расхождение
                        // (старый id + чужие значения) — признак upsert поверх невидимого рейта.
                        if (result.episodes != episodesToSend) {
                            KLog.w(
                                "ShikimoriSync",
                                "push: shikimoriId=$shikimoriId server echo ep=${result.episodes} " +
                                    "!= sent $episodesToSend (rate id=${result.id}) — check the site value"
                            )
                        }
                        upsertCached(result)
                        // Якорение часов (только метка, значения не трогаем — они только
                        // что отправлены; полный adopt эха затёр бы заметку, которая не пушится).
                        withContext(Dispatchers.IO) {
                            userStateStore.anchorProfileUpdatedAt(
                                profile.kinopoiskId,
                                result.getUpdatedEpochMillis()
                            )
                        }
                        pushed++
                    } else {
                        KLog.e("ShikimoriSync", "Push failed for shikimoriId=$shikimoriId, will retry next sync")
                    }
                }
                if (pushed > 0) {
                    persistShikimoriRatesSnapshot(auth.userId, cachedShikimoriRates)
                    refreshLibraryAndAvatar()
                }
            } finally {
                pushingAnimeRates = false
            }
    }

    /**
     * Дозагрузка 18+-вердиктов для аниме из библиотеки без кэша деталей (история/профили
     * без оценки Shikimori). Фильтр «Показывать хентай» синхронный, а каталог hanime
     * сопоставляет названия ненадёжно — добираем детали Shikimori и сохраняем isAdult
     * в кэш, после чего пересобираем библиотеку.
     *
     * Три этапа: сначала батч кратких объектов (1 запрос на 50 тайтлов — имена
     * и поля для вердикта; жанров батч не несёт), затем жанровая разметка 18+
     * (ids+genre — сервер режет выборку жанром), затем поштучный добор непокрытых
     * (рейтинг rx и пр.) тем же пейсером.
     */
    private fun ensureLibraryAdultVerdicts() {
        if (userStateStore.isHentaiVisibleInLibrary()) return
        if (adultVerdictJob?.isActive == true) return
        adultVerdictJob = viewModelScope.launch(Dispatchers.IO) {
            val offset = ANIME_ID_OFFSET
            val libraryIds = buildSet {
                userStateStore.getHistory().forEach { if (it.kinopoiskId >= offset) add(it.kinopoiskId) }
                userStateStore.getProfiles().forEach { if (it.kinopoiskId >= offset) add(it.kinopoiskId) }
                cachedShikimoriRates.forEach { if (it.targetId > 0) add(it.targetId + offset) }
            }
            val cache = userStateStore.getShikimoriAnimeCache()
            val pending = libraryIds.filter { cache[it - offset]?.isAdult == null }
            if (pending.isEmpty()) return@launch
            var totalSaved = 0
            // Этап 1: батч — весь pending чанками по 50 (одна запись кэша:
            // поштучные read-modify-write сериализовали весь блоб на запись).
            val ids = pending.take(500).map { it - offset }
            val brief = fetchAnimeBrief(ids)
            val stageEntries = mutableListOf<hd.kinoshka.app.data.local.ShikimoriAnimeCache>()
            for (kpId in pending.take(500)) {
                val id = kpId - offset
                val item = brief[id] ?: continue
                stageEntries.add(briefToCache(id, item, cache[id]))
            }
            userStateStore.saveShikimoriAnimeInfos(stageEntries)
            totalSaved += stageEntries.size
            // Этап 1.5: жанровая разметка 18+ (см. markAdultByGenre).
            totalSaved += markAdultByGenre(libraryIds)
            // Этап 2: поштучный добор непокрытых батчем (порезка ids, цензура, провал запроса).
            val leftovers = pending
                .filter { brief[it - offset] == null }
                .take(40)
            if (leftovers.isNotEmpty()) {
                val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                val leftoverEntries = leftovers.map { kpId ->
                    async {
                        semaphore.acquire()
                        try {
                            val shikimoriId = kpId - offset
                            // Тот же пейсер и дедуп, что у добивки рейтов: два префетчера делят
                            // лимит 5rps, параллельные дубли одного id ждут один запрос.
                            val details = prefetchDetails(shikimoriId)
                                ?: return@async null
                            val animeItem = hd.kinoshka.app.data.model.ShikimoriAnimeItem(
                                id = details.id,
                                name = details.name,
                                russian = details.russian,
                                image = details.image,
                                url = details.url,
                                kind = details.kind,
                                score = details.score,
                                status = details.status,
                                episodes = details.episodes,
                                episodesAired = details.episodesAired
                            )
                            hd.kinoshka.app.data.local.ShikimoriAnimeCache(
                                shikimoriId = shikimoriId,
                                name = details.name,
                                russian = details.russian,
                                posterUrl = animeItem.posterUrl,
                                episodes = details.episodes,
                                episodesAired = details.episodesAired,
                                kind = details.kind,
                                score = details.score,
                                status = details.status,
                                year = details.airedOn?.take(4)?.toIntOrNull(),
                                // Полные details несут жанры и рейтинг rx — вердикт
                                // авторитетный, жанровая проверка больше не нужна.
                                isAdult = isAdultAnime(details),
                                genreChecked = true
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                }.mapNotNull { it.await() }
                // Одна запись кэша на всю добивку (уже на Dispatchers.IO).
                userStateStore.saveShikimoriAnimeInfos(leftoverEntries)
                totalSaved += leftoverEntries.size
            }
            if (totalSaved > 0) {
                // Уже на Dispatchers.IO: сборку делаем здесь, на Main — только публикацию.
                val library = buildLibraryItems()
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(library = library)
                }
            }
        }
    }

    /**
     * Добор подробностей Shikimori для оболочек библиотеки без кэша деталей
     * (импорт из пула Anixart: статус есть, а kind/серий/рейтинга нет — плитки
     * врали «Фильм» и молчали про серии/оценку). Anixart говорит КАКОЕ аниме,
     * подробности — Shikimori: батч кратких объектов по shikimoriId (1 запрос
     * на 50 тайтлов) в дисковый кэш, затем пересборка (пост-проход
     * buildLibraryItems разложит кэш по плиткам сам). В отличие от вердиктов,
     * работает и при включённом хентае — это не про 18+, а про тип/серии/рейтинг.
     * 18+-вердикт батча не пишем (оставляем prev/isAdult=null): его healing —
     * дело ensureLibraryAdultVerdicts, иначе предположение батча скрыло бы
     * тайтл от жанровой проверки при выключенном тумблере.
     */
    private fun ensureLibraryAnimeMeta() {
        if (animeMetaJob?.isActive == true) return
        animeMetaJob = viewModelScope.launch(Dispatchers.IO) {
            val offset = ANIME_ID_OFFSET
            val libraryIds = buildSet {
                userStateStore.getHistory().forEach { if (it.kinopoiskId >= offset) add(it.kinopoiskId) }
                userStateStore.getProfiles().forEach { if (it.kinopoiskId >= offset) add(it.kinopoiskId) }
                cachedShikimoriRates.forEach { if (it.targetId > 0) add(it.targetId + offset) }
            }
            val cache = userStateStore.getShikimoriAnimeCache()
            val pending = libraryIds.filter { cache[it - offset]?.kind == null }.take(500)
            if (pending.isEmpty()) return@launch
            // Долгая добивка (сотни оболочек после catch-up) — неопределённая фаза
            // баннера; мелочь (<50) молча, без мигания.
            val showMetaProgress = pending.size > 50
            if (showMetaProgress) setImportProgress("Добираем обложки", 0, 0)
            val brief = fetchAnimeBrief(pending.map { it - offset })
            val fresh = userStateStore.getShikimoriAnimeCache()
            // Одна запись кэша на весь батч (см. вердикты выше).
            val metaEntries = pending.mapNotNull { kpId ->
                val id = kpId - offset
                val item = brief[id] ?: return@mapNotNull null
                val prev = fresh[id]
                briefToCache(id, item, prev).copy(isAdult = prev?.isAdult)
            }
            userStateStore.saveShikimoriAnimeInfos(metaEntries)
            if (metaEntries.isNotEmpty()) {
                KLog.i("ShikimoriSync", "anime-meta: backfilled ${metaEntries.size} title(s), rebuilding library")
                val library = buildLibraryItems()
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(library = library)
                }
            }
            if (showMetaProgress) clearImportProgress()
        }
    }

    fun loadCalendar() {
        viewModelScope.launch {
            uiState = uiState.copy(calendarLoading = true)
            val items = animeRepository.calendar()
            cachedShikimoriCalendar = items
            uiState = uiState.copy(calendarItems = items, calendarLoading = false)
            // Re-enrich the library with the just-arrived next-episode data.
            refreshLibraryAndAvatar()
        }
    }

    fun loadTopics() {
        viewModelScope.launch {
            uiState = uiState.copy(topicsLoading = true)
            val items = animeRepository.topics()
            uiState = uiState.copy(topics = items, topicsLoading = false)
        }
    }

    // ============================ лента «Обзора» ============================

    /** Жанровые карусели аниме: фиксированные id Shikimori (см. shikimoriGenres выше). */
    private val overviewAnimeGenres = listOf(
        "Экшен" to 1,
        "Романтика" to 22,
        "Исэкай" to 62,
        "Повседневность" to 36,
        "Спорт" to 30
    )

    /** Сезонные карусели: вычисляются от текущей даты (текущий сезон → прошлый →
     *  текущий год → прошлый год). Сезон Shikimori отдаёт как есть: и fall_2026,
     *  и год целиком (2026). */
    private data class AnimeSeason(val title: String, val season: String, val order: String)

    private fun overviewAnimeSeasons(): List<AnimeSeason> {
        val current = seasonKey()
        val prior = seasonKey(backSeasons = 1)
        val year = currentYear()
        fun seasonTitle(key: String): String {
            val parts = key.split("_")
            val ru = when (parts.getOrNull(0)) {
                "winter" -> "Зима"
                "spring" -> "Весна"
                "summer" -> "Лето"
                else -> "Осень"
            }
            return "$ru ${parts.getOrNull(1).orEmpty()}"
        }
        return listOf(
            AnimeSeason(seasonTitle(current), current, "popularity"),
            AnimeSeason(seasonTitle(prior), prior, "ranked"),
            AnimeSeason(year.toString(), year.toString(), "ranked"),
            AnimeSeason((year - 1).toString(), (year - 1).toString(), "ranked")
        )
    }

    /** Кандидаты жанровых каруселей кино: сопоставляются с availableGenres по имени. */
    private val overviewFilmGenreNames = listOf(
        "Фантастика", "Боевик", "Комедия", "Драма", "Ужасы", "Триллер", "Детектив", "Мелодрама"
    )

    /**
     * Лента «Обзора»: кино и аниме грузятся одновременно, посекционно.
     * Падение одной секции не роняет остальные (ошибка видна только в [HomeUiState.overviewError]
     * когда пусто вообще всё). Параллелизм ограничен семафором + стартовым стаггером —
     * у KP лимит запросов в секунду. Повторный вызов при активной загрузке — no-op;
     * догрузка жанровых секций кино — после приезда `availableGenres` из loadFilters.
     */
    /** Одноразовый тихий рефреш после дискового кэша: секции уже на экране. */
    private var overviewCacheRefreshed = false

    fun ensureOverviewLoaded() {
        if (overviewJob?.isActive == true) return
        // Жанровые карусели кино появляются только после справочника filters():
        // если его не было на старте — перезагружаем кино-ветку при его приезде.
        val needGenreRefill = uiState.overviewFilmSections.isNotEmpty() &&
            uiState.overviewFilmSections.none { it.id.startsWith("film_genre_") } &&
            uiState.availableGenres.isNotEmpty()
        // Stale-while-revalidate: секции из дискового кэша уже на экране — обновляем их
        // фоном без скелетона, один раз за сессию.
        val silentRefresh = !overviewCacheRefreshed &&
            (uiState.overviewFilmSections.isNotEmpty() || uiState.overviewAnimeSections.isNotEmpty())
        val needFilms = uiState.overviewFilmSections.isEmpty() || needGenreRefill || silentRefresh
        val needAnime = uiState.overviewAnimeSections.isEmpty() || silentRefresh
        if (!needFilms && !needAnime) return
        overviewJob = viewModelScope.launch {
            if (!silentRefresh) uiState = uiState.copy(overviewLoading = true, overviewError = null)
            val semaphore = Semaphore(3)
            val filmGenres = uiState.availableGenres
            val animeSeasons = overviewAnimeSeasons()
            val currentSeason = animeSeasons.firstOrNull()?.season.orEmpty()
            val priorSeason = animeSeasons.getOrNull(1)?.season.orEmpty()
            val filmJob = if (needFilms) async(Dispatchers.IO) { loadFilmSections(semaphore, filmGenres) } else null
            // Стаггер старта аниме-ветки: не упираемся в RPS-лимиты обоих API разом.
            if (needAnime && needFilms) delay(OVERVIEW_STAGGER_MS)
            val animeJob = if (needAnime) async(Dispatchers.IO) {
                loadAnimeSections(semaphore, currentSeason, priorSeason, animeSeasons)
            } else null
            // Прогрессивная публикация: кино-ветка показывается, не дожидаясь аниме.
            val films = awaitBranch(filmJob)
            if (films != null) {
                uiState = uiState.copy(overviewFilmSections = films.sections)
            }
            val anime = awaitBranch(animeJob)
            if (anime != null) {
                uiState = uiState.copy(overviewAnimeSections = anime.sections)
            }
            // Витрины: дубли каруселей вычитаем — карточки сверху не повторяют плакаты.
            val filmSections = films?.sections ?: uiState.overviewFilmSections
            val animeSections = anime?.sections ?: uiState.overviewAnimeSections
            val filmIds = filmSections.flatMap { s -> s.items.map { it.kinopoiskId } }.toSet()
            val animeIds = animeSections.flatMap { s -> s.items.map { it.kinopoiskId } }.toSet()
            val filmHero = (films?.heroPool.orEmpty())
                .filter { it.kinopoiskId !in filmIds }.take(OVERVIEW_HERO_TAKE)
            val animeHero = (anime?.heroPool.orEmpty())
                .filter { it.kinopoiskId !in animeIds }.take(OVERVIEW_HERO_TAKE)
            uiState = uiState.copy(
                overviewFilmHero = filmHero.ifEmpty { uiState.overviewFilmHero },
                overviewAnimeHero = animeHero.ifEmpty { uiState.overviewAnimeHero },
                overviewLoading = false,
                overviewError = if (filmSections.isEmpty() && animeSections.isEmpty()) {
                    "Не удалось загрузить подборки. Проверьте сеть."
                } else null
            )
            // Кэшируем свежие ветки на диск: следующий холодный старт рисуется мгновенно.
            if (films != null && filmSections.isNotEmpty()) {
                userStateStore.saveOverviewCache("films", filmSections, filmHero)
            }
            if (anime != null && animeSections.isNotEmpty()) {
                userStateStore.saveOverviewCache("anime", animeSections, animeHero)
            }
            overviewCacheRefreshed = true
        }
    }

    /** Ожидание ветки без проглатывания отмены: CancellationException идёт дальше. */
    private suspend fun <T> awaitBranch(job: kotlinx.coroutines.Deferred<T?>?): T? {
        if (job == null) return null
        return try {
            job.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.w("Overview", "branch failed: ${e.message}")
            null
        }
    }

    fun retryOverview() {
        overviewJob?.cancel()
        overviewJob = null
        uiState = uiState.copy(
            overviewFilmSections = emptyList(),
            overviewAnimeSections = emptyList(),
            overviewFilmHero = emptyList(),
            overviewAnimeHero = emptyList()
        )
        ensureOverviewLoaded()
    }

    /**
     * Повторный тап «Обзора» в навигации: гасим поиск и фильтры, возвращаем ленту секций.
     * Подборки уже в стейте — пересобираем только старую плоскую сетку под ними.
     */
    fun resetDiscover() {
        clearDiscoverFilters()
        loadDiscoverFirstPage(uiState.discoverCategory)
    }

    /**
     * Тихий сброс разделов Обзора без перезагрузки: открытие тайтла из сетки раздела
     * чистит состояние просмотра, поэтому системный Назад из деталей приземляется
     * на главную ленту секций, а не в покинутую сетку.
     */
    fun clearDiscoverFilters() {
        searchJob?.cancel()
        uiState = uiState.copy(
            query = "",
            isSearchResult = false,
            isInstantSearch = false,
            filterState = SearchFilterState(),
            discoverCategory = DiscoverCategory.POPULAR,
            discoverTitle = null
        )
    }

    /**
     * Детали открыты из отдельного маршрута (календарь, лента релизов): Назад должен
     * вернуть на главную Обзора (pop до home), минуя промежуточный экран. Открытия из
     * ленты/секций/поиска/библиотеки идут обычным pop — возвращают на место открытия.
     */
    private var detailsFromOverview = false

    fun markDetailsFromOverview() {
        detailsFromOverview = true
    }

    /** Однократное чтение флага (уход через жанр из деталей его тоже гасит). */
    fun consumeDetailsFromOverview(): Boolean {
        val v = detailsFromOverview
        detailsFromOverview = false
        return v
    }

    /**
     * Поиск студии открыт из Новостей (поверх ленты): Назад из результатов должен
     * вернуть на ленту, а не гасить поиск на месте. Маркер — заголовок поиска:
     * любой другой поиск/раздел/сброс сам гасит совпадение, протухший флаг
     * на чужие экраны не срабатывает. Чтение одноразовое.
     */
    private var searchFromFeedTitle: String? = null

    fun markSearchFromFeed(title: String) {
        searchFromFeedTitle = title
    }

    fun consumeSearchFromFeed(): Boolean {
        val marked = searchFromFeedTitle
        searchFromFeedTitle = null
        return marked != null && marked == uiState.discoverTitle
    }

    /** Профиль: открыть Библиотеку на вкладке статуса (тап по легенде статистики). */
    fun requestLibraryDeepLink(status: UserFilmStatus, animeOnly: Boolean) {
        uiState = uiState.copy(libraryDeepLink = LibraryDeepLink(status, animeOnly))
    }

    /** Библиотека применила deep-link: гасим запрос, чтобы не срабатывал повторно. */
    fun consumeLibraryDeepLink() {
        if (uiState.libraryDeepLink != null) {
            uiState = uiState.copy(libraryDeepLink = null)
        }
    }

    /** Кнопка «Все» на секции: сводится к существующим механизмам discover/поиска. */
    fun openOverviewSeeAll(target: OverviewSeeAll) {
        when (target) {
            is OverviewSeeAll.DiscoverCategoryTarget -> openDiscoverCategorySection(
                target.category, title = target.category.title
            )
            OverviewSeeAll.FilmPopular -> {
                userStateStore.setSavedContentType(ContentType.FILMS)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.FILMS,
                    query = "",
                    isSearchResult = false,
                    isInstantSearch = false,
                    filterState = SearchFilterState(),
                    discoverCategory = DiscoverCategory.POPULAR,
                    discoverTitle = "Сейчас смотрят"
                )
                loadDiscoverFirstPage(DiscoverCategory.POPULAR)
            }
            is OverviewSeeAll.FilmGenreTarget -> searchGenre(target.genreName, isAnime = false)
            OverviewSeeAll.FilmFresh -> {
                userStateStore.setSavedContentType(ContentType.FILMS)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.FILMS,
                    query = "",
                    filterState = SearchFilterState(selectedOrder = "YEAR", yearFrom = FRESH_YEAR_FROM),
                    discoverTitle = "Новинки"
                )
                submitSearch()
            }
            is OverviewSeeAll.AnimeGenreTarget -> searchGenre(target.genreName, isAnime = true)
            is OverviewSeeAll.AnimeKindTarget -> {
                userStateStore.setSavedContentType(ContentType.ANIME)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.ANIME,
                    query = "",
                    filterState = SearchFilterState(animeKind = target.kind, animeOrder = "ranked"),
                    discoverTitle = target.title
                )
                submitSearch()
            }
            OverviewSeeAll.AnimeOngoing -> {
                userStateStore.setSavedContentType(ContentType.ANIME)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.ANIME,
                    query = "",
                    filterState = SearchFilterState(animeStatus = "ongoing", animeOrder = "popularity"),
                    discoverTitle = "Онгоинги"
                )
                submitSearch()
            }
            OverviewSeeAll.AnimeOnAir -> {
                userStateStore.setSavedContentType(ContentType.ANIME)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.ANIME,
                    query = "",
                    filterState = SearchFilterState(animeStatus = "ongoing", animeOrder = "ranked", animeScoreFrom = 7),
                    discoverTitle = "Сейчас на экранах"
                )
                submitSearch()
            }
            is OverviewSeeAll.AnimeSeasonTarget -> {
                userStateStore.setSavedContentType(ContentType.ANIME)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.ANIME,
                    query = "",
                    filterState = SearchFilterState(animeSeason = target.season, animeOrder = target.order),
                    discoverTitle = target.title
                )
                submitSearch()
            }
            OverviewSeeAll.AnimeRanked -> {
                userStateStore.setSavedContentType(ContentType.ANIME)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.ANIME,
                    query = "",
                    filterState = SearchFilterState(animeOrder = "ranked"),
                    discoverTitle = "Топ по рейтингу"
                )
                submitSearch()
            }
            OverviewSeeAll.AnimePopular -> {
                userStateStore.setSavedContentType(ContentType.ANIME)
                uiState = uiState.copy(
                    tab = HomeTab.CATALOG,
                    contentType = ContentType.ANIME,
                    query = "",
                    filterState = SearchFilterState(animeOrder = "popularity"),
                    discoverTitle = "Популярное аниме"
                )
                submitSearch()
            }
        }
    }

    /** Ветка Обзора: секции + сырой пул витрины (дубли каруселей вычитаются позже). */
    private data class FilmBranch(
        val sections: List<OverviewSection>,
        val heroPool: List<FilmItem> = emptyList()
    )

    private data class AnimeBranch(
        val sections: List<OverviewSection>,
        val heroPool: List<FilmItem> = emptyList()
    )

    private suspend fun loadFilmSections(
        semaphore: Semaphore,
        genres: List<FilterItem>
    ): FilmBranch = kotlinx.coroutines.coroutineScope {
        val base = listOf(
            async {
                delay(OVERVIEW_REQUEST_GAP_MS)
                semaphore.withPermit {
                    runCatching { repository.popular("TOP_POPULAR_ALL", 1) }.getOrNull()?.let {
                        OverviewSection("film_popular", "Сейчас смотрят", it.dedupe(), OverviewSeeAll.FilmPopular)
                    }
                }
            },
            async {
                delay(OVERVIEW_REQUEST_GAP_MS * 2)
                semaphore.withPermit {
                    runCatching { repository.topMovies(1) }.getOrNull()?.let {
                        OverviewSection("film_top250", "Топ-250 фильмов", it.dedupe(), OverviewSeeAll.DiscoverCategoryTarget(DiscoverCategory.TOP_250))
                    }
                }
            },
            async {
                delay(OVERVIEW_REQUEST_GAP_MS * 3)
                semaphore.withPermit {
                    runCatching { repository.topShows(1) }.getOrNull()?.let {
                        OverviewSection("film_series", "Топ сериалов", it.dedupe(), OverviewSeeAll.DiscoverCategoryTarget(DiscoverCategory.SERIES))
                    }
                }
            },
            async {
                delay(OVERVIEW_REQUEST_GAP_MS * 4)
                semaphore.withPermit {
                    runCatching { repository.freshSince(FRESH_YEAR_FROM, 1) }.getOrNull()
                        ?.dedupe()
                        // Только вышедшее и с постером: поиск по году отдаёт и анонсы без обложек.
                        ?.filter { !it.posterUrlPreview.isNullOrBlank() && (it.year ?: Int.MAX_VALUE) <= currentYear() }
                        ?.sortedByDescending { it.year }
                        ?.let {
                            if (it.isEmpty()) null else OverviewSection("film_fresh", "Новинки", it, OverviewSeeAll.FilmFresh)
                        }
                }
            }
        )
        val genreJobs = overviewFilmGenreNames.mapNotNull { name ->
            val match = genres.firstOrNull { it.genre.equals(name, ignoreCase = true) } ?: return@mapNotNull null
            async { filmGenreSection(semaphore, match, name) }
        }
        // Витрина «Обсуждаемое»: самое оценённое, 2 страницы — дубли каруселей
        // вычитает вызывающий, витрина никогда не повторяет плакаты ниже.
        val heroPoolJobs = listOf(1, 2).map { page ->
            async {
                delay(OVERVIEW_REQUEST_GAP_MS * (4 + page))
                semaphore.withPermit {
                    runCatching { repository.mostDiscussed(page) }.getOrNull()?.dedupe()
                }
            }
        }
        val sections = (base + genreJobs).mapNotNull { runCatching { it.await() }.getOrNull() }
            .filter { it.items.isNotEmpty() }
        val heroPool = heroPoolJobs.flatMap { runCatching { it.await() }.getOrNull().orEmpty() }
            .dedupe()
        FilmBranch(sections, heroPool)
    }

    private suspend fun filmGenreSection(
        semaphore: Semaphore,
        match: FilterItem,
        name: String
    ): OverviewSection? {
        delay(OVERVIEW_REQUEST_GAP_MS * 5)
        return semaphore.withPermit {
            runCatching { repository.byGenre(match.id, 1) }.getOrNull()?.let {
                val items = it.dedupe()
                if (items.isEmpty()) null else OverviewSection(
                    "film_genre_${match.id}", name,
                    items, OverviewSeeAll.FilmGenreTarget(match.id, match.genre ?: name)
                )
            }
        }
    }

    /**
     * Догрузка только жанровых каруселей кино, когда справочник filters() приехал позже
     * секций. Раньше здесь перезапускалась вся кино-ветка (до +10 лишних KP-запросов).
     */
    private fun refillFilmGenres(genres: List<FilterItem>) {
        if (uiState.overviewFilmSections.isEmpty()) {
            overviewJob = null
            ensureOverviewLoaded()
            return
        }
        if (uiState.overviewFilmSections.any { it.id.startsWith("film_genre_") }) return
        viewModelScope.launch(Dispatchers.IO) {
            val genreSections = kotlinx.coroutines.coroutineScope {
                overviewFilmGenreNames.mapNotNull { name ->
                    val match = genres.firstOrNull { it.genre.equals(name, ignoreCase = true) }
                        ?: return@mapNotNull null
                    async { filmGenreSection(Semaphore(3), match, name) }
                }.mapNotNull { runCatching { it.await() }.getOrNull() }
            }.filter { it.items.isNotEmpty() }
            if (genreSections.isNotEmpty()) {
                val merged = uiState.overviewFilmSections + genreSections
                uiState = uiState.copy(overviewFilmSections = merged)
                userStateStore.saveOverviewCache("films", merged, uiState.overviewFilmHero)
            }
        }
    }

    private suspend fun loadAnimeSections(
        semaphore: Semaphore,
        currentSeason: String,
        priorSeason: String,
        seasons: List<AnimeSeason>
    ): AnimeBranch =
        kotlinx.coroutines.coroutineScope {
            val base = listOf(
                async {
                    delay(OVERVIEW_REQUEST_GAP_MS)
                    semaphore.withPermit {
                        runCatching { animeRepository.ongoing(1) }.getOrNull()
                            ?.map { it.toFilmItem() }?.dedupe()?.let {
                                OverviewSection("anime_ongoing", "Онгоинги", it, OverviewSeeAll.AnimeOngoing)
                            }
                    }
                },
                async {
                    delay(OVERVIEW_REQUEST_GAP_MS * 2)
                    semaphore.withPermit {
                        // «Сейчас на экранах» — формула главной Shikimori, не наша выдумка:
                        // онгоинги текущего+прошлого сезонов с оценкой > 7.3.
                        runCatching { animeRepository.nowOnScreens(currentSeason, priorSeason) }.getOrNull()
                            ?.map { it.toFilmItem() }?.dedupe()?.let {
                                if (it.isEmpty()) null else OverviewSection("anime_onair", "Сейчас на экранах", it, OverviewSeeAll.AnimeOnAir)
                            }
                    }
                },
                async {
                    delay(OVERVIEW_REQUEST_GAP_MS * 3)
                    semaphore.withPermit {
                        runCatching { animeRepository.topRanked(1) }.getOrNull()
                            ?.map { it.toFilmItem() }?.dedupe()?.let {
                                OverviewSection("anime_ranked", "Топ по рейтингу", it, OverviewSeeAll.AnimeRanked)
                            }
                    }
                },
                async {
                    delay(OVERVIEW_REQUEST_GAP_MS * 4)
                    semaphore.withPermit {
                        runCatching { animeRepository.popular(1) }.getOrNull()
                            ?.map { it.toFilmItem() }?.dedupe()?.let {
                                OverviewSection("anime_popular", "Популярное аниме", it, OverviewSeeAll.AnimePopular)
                            }
                    }
                },
                async {
                    delay(OVERVIEW_REQUEST_GAP_MS * 5)
                    semaphore.withPermit {
                        runCatching { animeRepository.byKind("movie", 1) }.getOrNull()
                            ?.map { it.toFilmItem() }?.dedupe()?.let {
                                OverviewSection(
                                    "anime_movies", "Полнометражные фильмы", it,
                                    OverviewSeeAll.AnimeKindTarget("movie", "Полнометражные фильмы")
                                )
                            }
                    }
                }
            )
            // Сезоны Shikimori: анонсы без постеров выкидываем, иначе карусель в заглушках.
            val seasonJobs = seasons.mapIndexed { index, s ->
                async {
                    delay(OVERVIEW_REQUEST_GAP_MS * (6 + index))
                    semaphore.withPermit {
                        runCatching { animeRepository.bySeason(s.season, s.order, page = 1) }.getOrNull()
                            ?.filter { it.image?.isMissingPlaceholder != true }
                            ?.map { it.toFilmItem() }?.dedupe()?.let {
                                if (it.isEmpty()) null else OverviewSection(
                                    "anime_season_${s.season}", s.title, it,
                                    OverviewSeeAll.AnimeSeasonTarget(s.season, s.title, s.order)
                                )
                            }
                    }
                }
            }
            val genreJobs = overviewAnimeGenres.map { (name, id) ->
                async {
                    delay(OVERVIEW_REQUEST_GAP_MS * 5)
                    semaphore.withPermit {
                        runCatching { animeRepository.byGenreId(id, 1) }.getOrNull()
                            ?.map { it.toFilmItem() }?.dedupe()?.let {
                                if (it.isEmpty()) null else OverviewSection(
                                    "anime_genre_$id", name, it, OverviewSeeAll.AnimeGenreTarget(id, name)
                                )
                            }
                    }
                }
            }
            // Витрина «Скоро выйдет»: анонсы такого пула нет ни в одной карусели.
            val heroPoolJob = async {
                delay(OVERVIEW_REQUEST_GAP_MS * 6)
                semaphore.withPermit {
                    runCatching { animeRepository.comingSoon(1) }.getOrNull()
                        ?.filter { it.image?.isMissingPlaceholder != true }
                        ?.map { it.toFilmItem() }?.dedupe().orEmpty()
                }
            }
            val sections = (base + seasonJobs + genreJobs)
                .mapNotNull { runCatching { it.await() }.getOrNull() }
                .filter { it.items.isNotEmpty() }
            AnimeBranch(sections, runCatching { heroPoolJob.await() }.getOrDefault(emptyList()))
        }

    fun saveShikimoriToken(code: String) {
        viewModelScope.launch {
            KLog.d("ShikimoriSync", "=== Starting OAuth token exchange ===")
            KLog.d("ShikimoriSync", "Authorization code: ${code.take(10)}...")

            val tokenResponse = animeRepository.exchangeCodeForToken(code)
            if (tokenResponse != null) {
                KLog.d("ShikimoriSync", "Token exchange SUCCESS!")
                KLog.d("ShikimoriSync", "Access token: ${tokenResponse.accessToken.take(10)}...")
                KLog.d("ShikimoriSync", "Refresh token: ${tokenResponse.refreshToken?.take(10)}...")

                KLog.d("ShikimoriSync", "Fetching user info with new token...")
                val whoami = animeRepository.whoami(tokenResponse.accessToken)
                if (whoami != null) {
                    KLog.d("ShikimoriSync", "User info fetched: id=${whoami.id}, nickname=${whoami.nickname}")
                    // whoami.avatar is always the tiny x48 version; image.x160 is the largest one
                    val rawAvatar = whoami.image?.x160 ?: whoami.avatar ?: whoami.image?.original
                    val fullAvatar = if (rawAvatar?.startsWith("/") == true) "https://shikimori.io$rawAvatar" else rawAvatar
                    shikimoriAuthStore?.saveSession(
                        token = tokenResponse.accessToken,
                        refresh = tokenResponse.refreshToken,
                        userId = whoami.id,
                        nickname = whoami.nickname,
                        avatarUrl = fullAvatar
                    )
                    if (!fullAvatar.isNullOrBlank()) {
                        setProfileAvatar(fullAvatar)
                    }
                    refreshShikimoriAuth(pushLocalNewer = true, caller = "oauth-login")
                    KLog.d("ShikimoriSync", "=== OAuth login successful! ===")
                } else {
                    KLog.e("ShikimoriSync", "Failed to fetch user info")
                }
            } else {
                KLog.e("ShikimoriSync", "=== Token exchange FAILED ===")
                KLog.e("ShikimoriSync", "Check if SHIKIMORI_CLIENT_ID and SHIKIMORI_CLIENT_SECRET are configured in local.properties")
            }
        }
    }

    fun saveShikimoriSession(token: String, userId: Int, nickname: String, avatarUrl: String?) {
        val fullAvatar = if (avatarUrl?.startsWith("/") == true) "https://shikimori.io$avatarUrl" else avatarUrl
        shikimoriAuthStore?.saveSession(
            token = token,
            refresh = null,
            userId = userId,
            nickname = nickname,
            avatarUrl = fullAvatar
        )
        if (!fullAvatar.isNullOrBlank()) {
            setProfileAvatar(fullAvatar)
        }
        refreshShikimoriAuth(pushLocalNewer = true, caller = "token-login")
    }

    // ------------------------------------------------------------------
    // Anixart: вход по логину+паролю (пароль не храним), двусторонний синк
    // списков (статусы; посерийного прогресса в v1 нет — нужен sourceId их
    // парсеров). Существующие локальные профили пул не перезаписывает
    // (у записей Anixart нет меток времени) — только создаёт недостающие.
    // ------------------------------------------------------------------

    fun refreshAnixartAuth(pushLocalNewer: Boolean = false) {
        val state = anixartAuthStore?.getAuthState()
            ?: hd.kinoshka.app.data.local.AnixartAuthState()
        uiState = uiState.copy(anixartAuthState = state)
        if (state.isLoggedIn && state.token != null) {
            viewModelScope.launch {
                anixartSyncMutex.withLock { syncAnixartLists(state.token, pushLocalNewer, caller = "auth") }
            }
        } else {
            anixartIdToShiki = emptyMap()
            anixartReleaseLists = emptyMap()
            viewModelScope.launch(Dispatchers.IO) { userStateStore.clearAnixartBaselineOnly() }
        }
    }

    fun loginAnixart(login: String, password: String, onDone: (ok: Boolean, message: String?) -> Unit) {
        val repo = anixartRepository
        if (repo == null) {
            onDone(false, "Anixart недоступен на этой платформе")
            return
        }
        if (login.isBlank() || password.isEmpty()) {
            onDone(false, "Введите логин и пароль")
            return
        }
        viewModelScope.launch {
            // Сеть — вне Main (иначе вход вешает UI на время signIn).
            withContext(Dispatchers.IO) { repo.signIn(login, password) }
                .onSuccess { session ->
                    applyAnixartSession(session, caller = "login")
                    onDone(true, null)
                }
                .onFailure { e ->
                    onDone(false, e.message ?: "Вход не удался")
                }
        }
    }

    /**
     * Общая финализация входа (логин / подтверждение регистрации / восстановление):
     * сохранение сессии — быстро, возврат сразу (диалог входа закрывается
     * onDone(true) без ожидания); тяжёлый первый синк — фоном под мьютексом.
     * Раньше sync src=login жил внутри onDone и держал диалог все ~9 минут
     * catch-up (кейс 09.09). Гард от протухшей сессии: разлогин/смена аккаунта
     * во время импорта отменяют фоновый синк, а не пишут чужие профили.
     */
    @Volatile
    private var anixartLoginToken: String? = null

    private suspend fun applyAnixartSession(
        session: hd.kinoshka.app.data.repo.AnixartRepository.Session,
        caller: String
    ) {
        anixartAuthStore?.saveSession(session.token, session.userId, session.nickname)
        anixartLoginToken = session.token
        uiState = uiState.copy(
            anixartAuthState = hd.kinoshka.app.data.local.AnixartAuthState(
                isLoggedIn = true,
                token = session.token,
                userId = session.userId,
                nickname = session.nickname
            )
        )
        // Чужой baseline (прошлый аккаунт) первому синку не товарищ:
        // иначе расхождения решались бы против свежего сервера.
        anixartIdToShiki = emptyMap()
        anixartReleaseLists = emptyMap()
        anixartUnresolvable.clear()
        anixartPullUnresolvable.clear()
        // Только baseline: карты знаний (idmap, промахи) — глобальная истина,
        // переживают вход и ускоряют restore.
        withContext(Dispatchers.IO) { userStateStore.clearAnixartBaselineOnly() }
        val token = session.token
        viewModelScope.launch {
            if (anixartLoginToken != token) {
                KLog.i("AnixartSync", "login sync skipped: session changed before start")
                return@launch
            }
            anixartSyncMutex.withLock {
                if (anixartLoginToken != token) {
                    KLog.i("AnixartSync", "login sync skipped: session changed while waiting")
                    return@withLock
                }
                syncAnixartLists(token, pushLocalNewer = true, caller = caller)
            }
        }
    }

    /** Регистрация Anixart, шаг 1: отправка кода на почту. Успех — hash для verify. */
    fun signUpAnixart(
        login: String,
        email: String,
        password: String,
        onDone: (ok: Boolean, message: String?, hash: String?) -> Unit
    ) {
        val repo = anixartRepository
        if (repo == null) {
            onDone(false, "Anixart недоступен на этой платформе", null)
            return
        }
        if (login.isBlank() || email.isBlank() || password.isEmpty()) {
            onDone(false, "Заполните логин, почту и пароль", null)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.signUp(login, email, password) }
                .onSuccess { hash -> onDone(true, null, hash) }
                .onFailure { e -> onDone(false, e.message ?: "Регистрация не удалась", null) }
        }
    }

    /** Регистрация Anixart, шаг 2: код из письма — автовход и синк. */
    fun verifyAnixartSignUp(
        login: String,
        email: String,
        password: String,
        hash: String,
        code: String,
        onDone: (ok: Boolean, message: String?) -> Unit
    ) {
        val repo = anixartRepository
        if (repo == null) {
            onDone(false, "Anixart недоступен на этой платформе")
            return
        }
        if (code.isBlank()) {
            onDone(false, "Введите код из письма")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.verifySignUp(login, email, password, hash, code) }
                .onSuccess { session ->
                    applyAnixartSession(session, caller = "register")
                    onDone(true, null)
                }
                .onFailure { e -> onDone(false, e.message ?: "Подтверждение не удалось") }
        }
    }

    /** Восстановление пароля, шаг 1: отправка кода на почту по логину. */
    fun restoreAnixart(login: String, onDone: (ok: Boolean, message: String?, hash: String?) -> Unit) {
        val repo = anixartRepository
        if (repo == null) {
            onDone(false, "Anixart недоступен на этой платформе", null)
            return
        }
        if (login.isBlank()) {
            onDone(false, "Введите логин", null)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.restore(login) }
                .onSuccess { hash -> onDone(true, null, hash) }
                .onFailure { e -> onDone(false, e.message ?: "Не удалось отправить код", null) }
        }
    }

    /** Восстановление пароля, шаг 2: код + новый пароль — автовход и синк. */
    fun verifyAnixartRestore(
        login: String,
        newPassword: String,
        hash: String,
        code: String,
        onDone: (ok: Boolean, message: String?) -> Unit
    ) {
        val repo = anixartRepository
        if (repo == null) {
            onDone(false, "Anixart недоступен на этой платформе")
            return
        }
        if (code.isBlank() || newPassword.isEmpty()) {
            onDone(false, "Введите код и новый пароль")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.verifyRestore(login, newPassword, hash, code) }
                .onSuccess { session ->
                    applyAnixartSession(session, caller = "restore")
                    onDone(true, null)
                }
                .onFailure { e -> onDone(false, e.message ?: "Смена пароля не удалась") }
        }
    }

    fun logoutAnixart() {
        anixartAuthStore?.clearSession()
        // Гасим и фоновый синк входа, если он ещё ждёт мьютекса.
        anixartLoginToken = null
        anixartIdToShiki = emptyMap()
        anixartReleaseLists = emptyMap()
        anixartPullUnresolvable.clear()
        viewModelScope.launch(Dispatchers.IO) { userStateStore.clearAnixartBaselineOnly() }
        // Локальная библиотека остаётся — та же философия, что у Shikimori.
        uiState = uiState.copy(anixartAuthState = hd.kinoshka.app.data.local.AnixartAuthState())
    }

    /** Синк при возврате в приложение (троттлинг общий с Shikimori). */
    fun syncAnixartOnForeground() {
        // Быстрая проверка до запуска корутины; авторитетная — внутри мьютекса:
        // ON_RESUME за холодным стартом вставал в очередь ЗА пулом init и,
        // дождавшись мьютекса, гнал второй полный пул (проверка была до ожидания).
        if (throttledAnixartForeground()) return
        val auth = uiState.anixartAuthState
        if (!auth.isLoggedIn || auth.token == null) return
        viewModelScope.launch {
            anixartSyncMutex.withLock {
                if (throttledAnixartForeground()) return@withLock
                lastAnixartSyncMs = System.nanoTime() / 1_000_000L
                syncAnixartLists(auth.token, pushLocalNewer = true, caller = "foreground")
            }
        }
    }

    /** Троттл foreground-пула Anixart со штампом (см. syncAnixartOnForeground). */
    private fun throttledAnixartForeground(): Boolean {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastAnixartSyncMs < FOREGROUND_SYNC_THROTTLE_MS) return true
        lastAnixartSyncMs = now
        return false
    }

    /**
     * Одноразовый ремонт echo-пуша restore 09.09: пуш перенёс 9 релизов из
     * «Завершено» (список 3) локальными импортными оболочками. Карта релиз->верный
     * список и релиз->испорченный список (куда утащил пуш).
     */
    private val SERVER_REPAIR_V1_TARGET = mapOf(
        1531 to 3, 2724 to 3, 3043 to 3, 16869 to 3, 1471 to 3,
        17254 to 3, 19298 to 3, 16912 to 3, 3050 to 3
    )
    private val SERVER_REPAIR_V1_CORRUPTED = mapOf(
        1531 to 1, 2724 to 1, 3043 to 2, 16869 to 1, 1471 to 1,
        17254 to 1, 19298 to 1, 16912 to 1, 3050 to 1
    )

    /**
     * Возвращает релизы из ремонта на место (мутирует buckets [releaseLists]).
     * Чиним только если сервер всё ещё в испорченном виде; релиз в правильном
     * списке (пользователь вернул вручную) или в третьем месте (пользователь
     * переложил сам) не трогаем. Сервер в «нигде», а baseline помнит испорченный —
     * наш недожим add: добавляем. Идемпотентно, флаг — в персисте.
     * Возвращает (возможно) пересобранные списки для сверки ниже.
     * Вызывать под anixartSyncMutex; сеть — на IO внутри.
     */
    private suspend fun runServerRepairV1(
        token: String,
        repo: hd.kinoshka.app.data.repo.AnixartRepository,
        lists: Map<Int, List<hd.kinoshka.app.data.model.AnixartRelease>>,
        releaseLists: MutableMap<Int, MutableSet<Int>>
    ): Map<Int, List<hd.kinoshka.app.data.model.AnixartRelease>> {
        if (withContext(Dispatchers.IO) { userStateStore.isAnixartServerRepairV1Done() }) return lists
        val buckets = lists.mapValues { it.value.toMutableList() }.toMutableMap()
        var repaired = 0
        for ((releaseId, correctList) in SERVER_REPAIR_V1_TARGET) {
            val corruptedList = SERVER_REPAIR_V1_CORRUPTED[releaseId] ?: continue
            val current = buckets.entries.firstOrNull { (_, rs) -> rs.any { it.id == releaseId } }?.key
            if (current == correctList) continue
            if (current != null && current != corruptedList) continue
            if (current == null && corruptedList !in anixartReleaseLists[releaseId].orEmpty()) continue
            val rel = current?.let { c -> buckets[c]?.firstOrNull { it.id == releaseId } }
            if (current != null && rel == null) continue
            var ok = true
            withContext(Dispatchers.IO) {
                if (current != null && !repo.removeFromList(token, current, releaseId)) ok = false
                if (ok && !repo.addToList(token, correctList, releaseId)) ok = false
            }
            if (!ok) {
                KLog.w("AnixartSync", "repairV1: release=$releaseId FAILED, will retry next sync")
                return buckets
            }
            if (current != null) buckets[current]?.removeAll { it.id == releaseId }
            if (rel != null) buckets.getOrPut(correctList) { mutableListOf() }.add(rel)
            releaseLists[releaseId] = mutableSetOf(correctList)
            repaired++
            KLog.i("AnixartSync", "repairV1: release=$releaseId back to list $correctList")
        }
        withContext(Dispatchers.IO) { userStateStore.setAnixartServerRepairV1Done() }
        if (repaired > 0) KLog.i("AnixartSync", "repairV1: moved back $repaired title(s) to COMPLETED")
        return buckets
    }

    /**
     * Пул списков Anixart (паритет с Shikimori-пулом). Вызывать под anixartSyncMutex.
     *
     * У записей Anixart нет меток времени, поэтому вместо LWW по датам — правило baseline:
     * локальный статус совпадает с последним известным сервером → серверное перенимаем
     * (adopt: смена на сайте/другом устройстве); локальный разошёлся (явная правка или
     * незапушенный пуш) → побеждает локальное, его отправит пуш ниже. Пустой baseline
     * (самый первый пул, персиста ещё нет) — всё считается разошедшимся, как свежие
     * локальные правки у Shikimori.
     *
     * Склейка релиз→аниме — титул + год выхода (Anixart год знает, Shikimori
     * aired_on — тоже): известное расхождение годов ветоит кандидата на любом
     * уровне титульного каскада. Иначе сезоны/спешлы под одним названием
     * схлопываются в первую запись (инцидент 09.09: S2→S1, OVA→TV).
     */
    private suspend fun syncAnixartLists(token: String, pushLocalNewer: Boolean, caller: String = "") {
        val repo = anixartRepository ?: return
        KLog.d("AnixartSync", "sync start src=$caller")
        // Сеть — IO: пул на сотни релизов (десятки запросов) на Main давал ANR.
        var lists = withContext(Dispatchers.IO) { repo.getLists(token).getOrNull() } ?: run {
            KLog.e("AnixartSync", "Pull failed, keeping local data")
            return
        }
        KLog.i(
            "AnixartSync",
            "pull: " + lists.entries.joinToString(" ") { (listId, releases) -> "$listId=${releases.size}" } +
                " total=${lists.values.sumOf { it.size }}"
        )
        // Самолечение залипшего баннера: прошлый пул мог умереть исключением
        // до гашения прогресса — следующий пул начинает с чистого экрана.
        clearImportProgress()
        // Карты для пуша и матчинга.
        val releaseLists = mutableMapOf<Int, MutableSet<Int>>()
        lists.forEach { (listId, releases) ->
            releases.forEach { release ->
                releaseLists.getOrPut(release.id) { mutableSetOf() }.add(listId)
            }
        }
        // Baseline — прошлый известный сервер: свежая карта встанет только после сверки,
        // иначе конфликт «локальное vs серверное» решать не с чем. Персист переживает
        // рестарт: иначе первый пул видел пустой baseline и любое расхождение пушил
        // локальным поверх правок сайта/другого устройства.
        if (anixartReleaseLists.isEmpty()) {
            anixartReleaseLists =
                withContext(Dispatchers.IO) { userStateStore.getAnixartListsBaseline() }
        }
        val baseline = anixartReleaseLists
        // Карта и мемоизация тоже переживают рестарт (персист ниже): иначе холодный
        // старт до построения библиотеки матчил всё мимо и заново жег поиск.
        if (anixartIdToShiki.isEmpty()) {
            anixartIdToShiki =
                withContext(Dispatchers.IO) { userStateStore.getAnixartIdMap() }
        }
        if (anixartUnresolvable.isEmpty()) {
            anixartUnresolvable.addAll(
                withContext(Dispatchers.IO) { userStateStore.getAnixartUnresolvable() }
            )
        }
        if (anixartPullUnresolvable.isEmpty()) {
            anixartPullUnresolvable.addAll(
                withContext(Dispatchers.IO) { userStateStore.getAnixartPullUnresolvable() }
            )
        }
        // Кэш метаданных кандидатов: сезон/вид/год без сети (exact-сверка,
        // самолечение карты). Грузим раз на пул.
        val anixartPullCache =
            withContext(Dispatchers.IO) { userStateStore.getShikimoriAnimeCache() }
        // Контест пересчитываем каждым пулом с нуля (ниже, в reconcile); персист
        // нужен точечным пушам между пулами.
        // Одноразовые догонялки restore 09.09: ремонт сервера + провенанс оболочек.
        lists = runServerRepairV1(token, repo, lists, releaseLists)
        withContext(Dispatchers.IO) { userStateStore.backfillImportSourceForRestore() }
        // Отравленные прошлой склейкой: контест прошлого пула перерезолвится
        // новыми правилами (годовая сверка) — старые записи карты сносим.
        val prevContested = withContext(Dispatchers.IO) { userStateStore.getAnixartContested() }
        if (prevContested.isNotEmpty()) {
            val poisoned = anixartIdToShiki.filterValues { it in prevContested }.keys
            if (poisoned.isNotEmpty()) {
                val cleaned = anixartIdToShiki.toMutableMap()
                poisoned.forEach { cleaned.remove(it) }
                anixartIdToShiki = cleaned
                KLog.i(
                    "AnixartSync",
                    "pull: dropped ${poisoned.size} contested idmap entrie(s) for re-resolve"
                )
            }
        }
        // Ручные пины поверх карты и мемоизации промахов: сезоны/синонимы,
        // разобранные вручную (см. ANIXART_PINNED_MAP). Расходящиеся записи карты
        // переписываем, из промахов вычищаем — иначе поиск не отработает.
        if (ANIXART_PINNED_MAP.isNotEmpty()) {
            val seeded = anixartIdToShiki.toMutableMap()
            var seededChanged = false
            ANIXART_PINNED_MAP.forEach { (rel, shiki) ->
                if (seeded[rel] != shiki) {
                    seeded[rel] = shiki
                    seededChanged = true
                }
                anixartPullUnresolvable.remove(rel)
            }
            if (seededChanged) {
                anixartIdToShiki = seeded
                KLog.i(
                    "AnixartSync",
                    "pull: seeded ${ANIXART_PINNED_MAP.size} pinned release->shiki entrie(s)"
                )
            }
            // Одноразовый ремонт названия: профиль OreImo S2 (13659) создан из
            // релиза 889 (S1), который пином уехал на 8769. Чиним название
            // с релиза 1173 (S2); сходится само, дальше молчит.
            val oreimoS2Title = lists.values.flatten().firstOrNull { it.id == 1173 }
                ?.let { it.titleRu ?: it.titleOriginal ?: it.titleEn }
            if (oreimoS2Title != null) {
                val renamed = withContext(Dispatchers.IO) {
                    userStateStore.renameImportedProfileTitle(
                        13659 + ANIME_ID_OFFSET, oreimoS2Title
                    )
                }
                if (renamed) {
                    KLog.i("AnixartSync", "pull: renamed shell 13659 to S2 title")
                }
            }
        }
        // Самолечение карты: старые склейки, ЯВНО противоречащие сезону/виду
        // (ядро инцидентов 09.09), сносим на перерезолв. Пины и релизы вне пула
        // не трогаем; без кэша кандидата судить нечем — оставляем. Год здесь
        // НЕ валидируем осознанно: опечатки года в дублях — та же запись.
        val relById = lists.values.flatten().associateBy { it.id }
        val droppedShikis = mutableSetOf<Int>()
        run {
            val matcher = hd.kinoshka.app.data.source.TitleMatching
            val cleanedMap = anixartIdToShiki.toMutableMap()
            var dropped = 0
            for ((relId, shiki) in anixartIdToShiki) {
                if (relId in ANIXART_PINNED_MAP) continue
                val rel = relById[relId] ?: continue
                val brief = anixartPullCache[shiki] ?: continue
                val (rs, rk, ry) = anixartReleaseSKY(rel)
                val (cs, ck) = shikiCandidateSK(brief.name, brief.russian, brief.kind)
                if (matcher.seasonKindVeto(rs, rk, ry, cs, ck, brief.year)) {
                    cleanedMap.remove(relId)
                    droppedShikis.add(shiki)
                    dropped++
                    KLog.i(
                        "AnixartSync",
                        "pull: invalidated idmap release=$relId shiki=$shiki " +
                            "season-kind mismatch (rel=$rs/$rk/$ry cand=$cs/$ck/${brief.year})"
                    )
                }
            }
            if (dropped > 0) {
                anixartIdToShiki = cleanedMap
                KLog.i("AnixartSync", "pull: invalidated $dropped stale idmap entrie(s) for re-resolve")
            }
        }
        // Счётчик годовых вето (фазы 1.5 и 2).
        val vetoes = YearVetoes()
        // Эффективные локальные статусы rate-backed тайтлов (профиля нет, рейт
        // Shikimori есть): пуш и сверка пула их иначе не видят. Снапшот чужого
        // аккаунта не берём, чтобы не залить чужое на Anixart.
        val rateStatusByShiki: Map<Int, UserFilmStatus> =
            withContext(Dispatchers.IO) {
                val snapshot = userStateStore.getShikimoriRatesSnapshot()
                val authUserId = uiState.shikimoriAuthState.userId
                if (snapshot.rates.isEmpty() || (authUserId > 0 && snapshot.userId != authUserId)) {
                    emptyMap()
                } else {
                    snapshot.rates.mapNotNull { rate ->
                        if (rate.targetId <= 0) return@mapNotNull null
                        val st = shikiRateStatusToUserStatus(rate.status) ?: return@mapNotNull null
                        rate.targetId to st
                    }.toMap()
                }
            }
        // Матчинг релизов с библиотекой: shikimori_id, карта прошлых резолюций,
        // иначе ТОЧНОЕ название. Нечёткого матчинга здесь нет осознанно: правило
        // «wanted начинается с кандидата» схлопывало S2-релизы в S1-записи
        // («One Punch Man 2nd Season»→S1, инцидент 09.09) и травило id-карту.
        // Несматченное уходит в поисковый каскад с годовой сверкой.
        // Контест пересчитываем с нуля каждый пул (персист — для точечных пушей между пулами).
        anixartContestedShiki.clear()
        // Релиз, не сматчившийся ни с чем из библиотеки (новый тайтл с сайта/другого
        // устройства), отдельно добираем поиском Shikimori (фаза 2): иначе он не
        // импортируется никогда — матчить его против библиотеки не с чем.
        val libraryAnime = uiState.library.filter { it.kinopoiskId >= ANIME_ID_OFFSET }
        val libraryIdSet = libraryAnime.mapTo(mutableSetOf()) { it.kinopoiskId }
        val profilesById = withContext(Dispatchers.IO) { userStateStore.getProfiles() }
            .associateBy { it.kinopoiskId }
        val idMap = mutableMapOf<Int, Int>()
        val toCreate = mutableListOf<UserFilmProfile>()
        // shiki -> первый встреченный в пуле статус: повтор с другим статусом = контест.
        val seenShikiStatus = mutableMapOf<Int, hd.kinoshka.app.data.local.UserFilmStatus>()
        fun notePullStatus(shikimoriId: Int, status: hd.kinoshka.app.data.local.UserFilmStatus) {
            val prevPullStatus = seenShikiStatus[shikimoriId]
            if (prevPullStatus != null && prevPullStatus != status) {
                if (anixartContestedShiki.add(shikimoriId)) {
                    KLog.d(
                        "AnixartSync",
                        "contested: shikimoriId=$shikimoriId statuses $prevPullStatus vs $status"
                    )
                }
            } else {
                seenShikiStatus.putIfAbsent(shikimoriId, status)
            }
        }
        class PullTally { var adopted = 0; var diverged = 0 }
        suspend fun reconcilePulledRelease(
            releaseId: Int,
            shikimoriId: Int,
            status: hd.kinoshka.app.data.local.UserFilmStatus,
            way: String,
            titleFallback: String,
            subtitleFallback: String?,
            posterFallback: String?,
            tally: PullTally
        ) {
            // Контест: тот же shiki уже встречался в этом пуле с другим статусом
            // (дубли релизов по спискам). Пуш такие shiki не трогает никогда.
            notePullStatus(shikimoriId, status)
            idMap[releaseId] = shikimoriId
            val kpId = shikimoriId + ANIME_ID_OFFSET
            val existing = profilesById[kpId]
            if (!libraryIdSet.contains(kpId) && existing?.status == null) {
                toCreate.add(
                    UserFilmProfile(
                        kinopoiskId = kpId,
                        title = titleFallback,
                        subtitle = subtitleFallback,
                        posterUrl = posterFallback,
                        ratingText = null,
                        type = "ANIME",
                        isRussian = false,
                        status = status,
                        userRating = null,
                        note = null,
                        // Импортная оболочка: серверное содержимое, пуши молчат,
                        // пока не коснётся явная правка (см. importSource).
                        importSource = "anixart",
                        watchedSeasons = null,
                        watchedEpisodes = null,
                        totalEpisodesInSeason = null,
                        totalSeasons = null,
                        totalEpisodes = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else if (existing != null && existing.status != null && existing.status != status) {
                // Конфликт статусов: untouched (совпадает с baseline) → adopt сервера,
                // иначе локальное новее по смыслу → оставляем, пуш ниже отправит его.
                val curList = existing.status.toAnixartList()
                if (curList in baseline[releaseId].orEmpty()) {
                    if (shikimoriId in anixartContestedShiki) {
                        // Контест: побеждает ПЕРВЫЙ статус пула (списки идут 1→5,
                        // активное выше завершённого). Поздние списки локальное
                        // не перетирают — иначе «Смотрю» вечно проигрывало
                        // «Завершено» дубля (инцидент 09.09: 6 тайтлов).
                        KLog.d(
                            "AnixartSync",
                            "adopt: shikimoriId=$shikimoriId release=$releaseId " +
                                "SKIP contested, keep first (${existing.status} vs $status)"
                        )
                    } else {
                        userStateStore.setFeedQuickStatus(kpId, existing.title, existing.posterUrl, status)
                        tally.adopted++
                        KLog.d(
                            "AnixartSync",
                            "adopt: shikimoriId=$shikimoriId release=$releaseId " +
                                "status(${existing.status} -> $status) via=$way"
                        )
                    }
                } else if (existing.importSource != null) {
                    // Импортная оболочка никогда не «новее сервера»: расхождение —
                    // stale-импорт, а не правка (иначе diverged+mute цементирует
                    // неверный статус навсегда — инцидент 09.09: 6 тайтлов «Смотрю»
                    // показывались «Завершено»). Контест → чиним к первому статусу
                    // пула (активный список); одиночный → к серверному.
                    val fix = if (shikimoriId in anixartContestedShiki) {
                        seenShikiStatus[shikimoriId]
                    } else {
                        status
                    }
                    if (fix != null && fix != existing.status) {
                        userStateStore.setFeedQuickStatus(kpId, existing.title, existing.posterUrl, fix)
                        tally.adopted++
                        KLog.d(
                            "AnixartSync",
                            "adopt: shikimoriId=$shikimoriId release=$releaseId " +
                                "stale-import fix (${existing.status} -> $fix) via=$way"
                        )
                    } else {
                        tally.diverged++
                        KLog.d(
                            "AnixartSync",
                            "adopt: shikimoriId=$shikimoriId release=$releaseId SKIP diverged " +
                                "local=${existing.status} server=$status, will push local"
                        )
                    }
                }
            } else if (existing?.status == null) {
                // Профиля нет (или он без статуса), но тайтл уже в библиотеке через
                // рейт Shikimori: эффективное локальное = статус рейта. То же правило
                // baseline: untouched → adopt сервера (профиль создаёт
                // setFeedQuickStatus, дальше его подхватит обычный пуш Shikimori
                // через verify-gate), diverged → пуш ниже отправит состояние рейта.
                val rateStatus = rateStatusByShiki[shikimoriId]
                if (rateStatus != null && rateStatus != status) {
                    val curList = rateStatus.toAnixartList()
                    if (curList in baseline[releaseId].orEmpty()) {
                        if (shikimoriId in anixartContestedShiki) {
                            KLog.d(
                                "AnixartSync",
                                "adopt: shikimoriId=$shikimoriId release=$releaseId " +
                                    "SKIP contested, keep first (rate $rateStatus vs $status)"
                            )
                        } else {
                            userStateStore.setFeedQuickStatus(kpId, titleFallback, posterFallback, status)
                            tally.adopted++
                            KLog.d(
                                "AnixartSync",
                                "adopt: shikimoriId=$shikimoriId release=$releaseId " +
                                    "rate-backed status($rateStatus -> $status) via=$way"
                            )
                        }
                    } else {
                        tally.diverged++
                        KLog.d(
                            "AnixartSync",
                            "adopt: shikimoriId=$shikimoriId release=$releaseId SKIP diverged " +
                                "rate-backed local=$rateStatus server=$status, will push rate"
                        )
                    }
                }
            }
        }
        data class PullCounts(val byId: Int, val exact: Int, val fuzzy: Int, val unmatched: Int)
        // Матчинг и сверка — на Default: нечёткий скоринг сотен релизов по сотням
        // кандидатов жрёт CPU (тот же ANR). Кандидаты нормализуются один раз.
        // Несматченные релизы собираем для фазы 2 (поиск Shikimori на IO).
        val unmatchedReleases = mutableListOf<Pair<Int, hd.kinoshka.app.data.model.AnixartRelease>>()
        val tally1 = PullTally()
        var knownMiss = 0
        val pass1 = withContext(Dispatchers.Default) {
            val matcher = hd.kinoshka.app.data.source.TitleMatching
            val titleToShiki = mutableMapOf<String, Int>()
            libraryAnime.forEach { item ->
                titleToShiki[matcher.normalizeTitle(item.title)] = item.kinopoiskId - ANIME_ID_OFFSET
            }
            // Нечёткого матчинга здесь нет (см. комментарий выше): кандидаты для него
            // больше не готовим.
            var byId = 0; var exact = 0; var fuzzy = 0; var unmatched = 0
            // Предпроход контеста по уже-известным склейкам (byId/idmap/exact):
            // решения adopt/diverged ниже должны знать о дублях ДО первой сверки
            // (списки идут 1→5, а конфликт виден лишь на втором вхождении).
            // Поисковые склейки добавятся по ходу фаз (тот же notePullStatus).
            lists.forEach { (listId, releases) ->
                val preStatus = hd.kinoshka.app.data.repo.anixartListToStatus(listId) ?: return@forEach
                releases.forEach { release ->
                    val preShiki = release.shikimoriId?.takeIf { it > 0 }
                        ?: anixartIdToShiki[release.id]?.takeIf { it > 0 }
                        ?: listOfNotNull(release.titleRu, release.titleOriginal, release.titleEn)
                            .firstNotNullOfOrNull { titleToShiki[matcher.normalizeTitle(it)] }
                        ?: return@forEach
                    notePullStatus(preShiki, preStatus)
                }
            }
            lists.forEach { (listId, releases) ->
                val status = hd.kinoshka.app.data.repo.anixartListToStatus(listId) ?: return@forEach
                releases.forEach { release ->
                    var shikimoriId = release.shikimoriId?.takeIf { it > 0 }
                    var way = "byId"
                    if (shikimoriId == null) {
                        // Карта прошлых резолюций (персист): повторный restore и ресты
                        // без единого поиска.
                        val mapped = anixartIdToShiki[release.id]?.takeIf { it > 0 }
                        if (mapped != null) {
                            shikimoriId = mapped
                            way = "idmap"
                        }
                    }
                    if (shikimoriId == null) {
                        val titles = listOfNotNull(release.titleRu, release.titleOriginal, release.titleEn)
                        val exactHit = titles.firstNotNullOfOrNull { titleToShiki[matcher.normalizeTitle(it)] }
                        if (exactHit != null) {
                            // Exact по библиотеке без сверки сезона/вида клеил S1-релиз
                            // в S2-запись (инцидент 889): сверяем с кэшем кандидата,
                            // без кэша — как раньше. Вето → дальше в поиск, не в пропуск.
                            val brief = anixartPullCache[exactHit]
                            val vetoed = if (brief != null) {
                                val (rs, rk, ry) = anixartReleaseSKY(release)
                                val (cs, ck) = shikiCandidateSK(brief.name, brief.russian, brief.kind)
                                matcher.seasonKindVeto(rs, rk, ry, cs, ck, brief.year)
                            } else false
                            if (vetoed) {
                                KLog.d(
                                    "AnixartSync",
                                    "pull: exact-vs-library season-kind-veto release=${release.id} shiki=$exactHit"
                                )
                            } else {
                                shikimoriId = exactHit
                                way = "exact"
                            }
                        }
                    }
                    if (shikimoriId == null) {
                        if (release.id in anixartPullUnresolvable) {
                            // Честный промах с прошлого синка: поиск Shikimori уже
                            // отработал вхолостую, повтор жег бы кап резолюций.
                            knownMiss++
                            return@forEach
                        }
                        unmatched++
                        unmatchedReleases.add(listId to release)
                        return@forEach
                    }
                    when (way) {
                        "byId", "idmap" -> byId++
                        "exact" -> exact++
                        else -> fuzzy++
                    }
                    reconcilePulledRelease(
                        release.id, shikimoriId, status, way,
                        release.titleRu ?: release.titleOriginal ?: release.titleEn ?: "Без названия",
                        null, null, tally1
                    )
                }
            }
            PullCounts(byId, exact, fuzzy, unmatched)
        }
        // Фаза 1.5 (IO) — ФОЛБЭК после поиска, не прелюдия: полный объект релиза
        // иногда несёт shikimori_id (в списках он всегда 0), но дёргать детали
        // по всем 800+ релизам upfront — ~50 c тишины (замер 09.09: hits=0).
        // Поиск идёт первым, сюда — только его остатки (обычно единицы).
        // Id Anixart франшизного уровня (S2-релиз ссылается на S1-запись,
        // инцидент 09.09), поэтому принимаем только со сверками: годовой +
        // сезонно-видовой. Нет id — тихо в пропуск, без мемоизации.
        var detailHits = 0
        val detailTally = PullTally()
        suspend fun runDetailFallback(targets: List<Pair<Int, hd.kinoshka.app.data.model.AnixartRelease>>) {
            if (targets.isEmpty()) return
            val detailOutcomes = withContext(Dispatchers.IO) {
                val semaphore = kotlinx.coroutines.sync.Semaphore(5)
                targets.map { (listId, release) ->
                    async {
                        semaphore.acquire()
                        try {
                            Triple(
                                listId,
                                release,
                                runCatching { repo.releaseInfo(token, release.id) }.getOrNull()
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
            // Годовая сверка id Anixart одним батчем (50/запрос).
            val detailPairs = detailOutcomes.mapNotNull { (listId, release, info) ->
                val sid = info?.shikimoriId?.takeIf { it > 0 } ?: return@mapNotNull null
                Triple(listId, release, sid)
            }
            val briefById = mutableMapOf<Int, hd.kinoshka.app.data.model.ShikimoriAnimeItem>()
            for (chunk in detailPairs.map { it.third }.distinct().chunked(500)) {
                briefById += fetchAnimeBrief(chunk)
            }
            for ((listId, release, sid) in detailPairs) {
                val status = hd.kinoshka.app.data.repo.anixartListToStatus(listId) ?: continue
                val anixYear = release.releaseYear()
                val shikiYear = briefById[sid]?.airedOn?.take(4)?.toIntOrNull()
                if (anixYear != null && shikiYear != null && anixYear != shikiYear) {
                    vetoes.veto(release.id)
                    KLog.d(
                        "AnixartSync",
                        "pull-resolve: release=${release.id} shikimoriId=$sid " +
                            "detail year-veto (anixart=$anixYear shiki=$shikiYear)"
                    )
                    continue
                }
                // Сезонно-видовая сверка и здесь: id Anixart франшизного уровня,
                // титульный каскад — нет.
                val brief = briefById[sid]
                val skVetoed = if (brief != null) {
                    val (rs, rk, _) = anixartReleaseSKY(release)
                    val (cs, ck) = shikiCandidateSK(brief.name, brief.russian, brief.kind)
                    hd.kinoshka.app.data.source.TitleMatching.seasonKindVeto(
                        rs, rk, anixYear, cs, ck, shikiYear
                    )
                } else false
                if (skVetoed) {
                    KLog.d(
                        "AnixartSync",
                        "pull-resolve: release=${release.id} shikimoriId=$sid " +
                            "detail season-kind-veto"
                    )
                    continue
                }
                val info = detailOutcomes.firstOrNull { it.second.id == release.id }?.third
                val title = info?.titleRu ?: info?.titleOriginal ?: info?.titleEn
                    ?: release.titleRu ?: release.titleOriginal ?: release.titleEn
                    ?: "Без названия"
                detailHits++
                reconcilePulledRelease(release.id, sid, status, "anixart-detail", title, null, null, detailTally)
            }
            KLog.i("AnixartSync", "pull: detail-resolve hits=$detailHits of ${detailOutcomes.size}")
        }
        val remainingUnmatched = unmatchedReleases.size
        // Фаза 2 (IO): обратная резолюция несматченных через поиск Shikimori —
        // только точный матч (импорт чужого тайтла хуже пропуска).
        // Догоняющий режим: несматченных в разы больше капа (свежий логин, вайп) —
        // резолвим всё за один синк, иначе восстановление ~850 тайтлов тянулось бы
        // ~20 foreground-синков по 40. Steady-state (единицы новинок) идёт старым капом.
        val catchUp = remainingUnmatched > MAX_ANIXART_PULL_RESOLVE_PER_SYNC
        val pullResolveLimit =
            if (catchUp) Int.MAX_VALUE else MAX_ANIXART_PULL_RESOLVE_PER_SYNC
        if (catchUp) {
            KLog.i(
                "AnixartSync",
                "pull: catch-up mode, resolving $remainingUnmatched unmatched " +
                    "(knownMiss=$knownMiss skipped)"
            )
            // Живой прогресс с первых секунд: дальше баннер движется каждым хитом.
            setImportProgress("Сопоставление", 0, remainingUnmatched)
        }
        anixartReleaseLists = releaseLists
        var createdTotal = 0
        var flushedSinceUiRefresh = 0
        suspend fun flushPullProgress() {
            // Карту пула сливаем с ранее зарезолвленными (поиск каталога): иначе записи,
            // добавленные пушем, терялись бы каждый синк и поиск повторялся бы вечно.
            // releaseLists при этом всегда свежие — решения пуша сверяются с ними.
            // Слияние ДО персиста: иначе резолюции текущего пула ложились бы на диск
            // только следующим синком.
            val mergedIdMap = idMap.toMutableMap()
            anixartIdToShiki.forEach { (anixartId, shikimoriId) ->
                mergedIdMap.putIfAbsent(anixartId, shikimoriId)
            }
            anixartIdToShiki = mergedIdMap
            idMap.clear()
            withContext(Dispatchers.IO) {
                userStateStore.setAnixartListsBaseline(releaseLists)
                userStateStore.setAnixartIdMap(anixartIdToShiki)
                userStateStore.setAnixartUnresolvable(anixartUnresolvable)
                userStateStore.setAnixartPullUnresolvable(anixartPullUnresolvable)
                userStateStore.setAnixartContested(anixartContestedShiki)
                if (toCreate.isNotEmpty()) {
                    userStateStore.addProfilesIfAbsent(toCreate)
                }
            }
            createdTotal += toCreate.size
            flushedSinceUiRefresh += toCreate.size
            toCreate.clear()
            // Catch-up видно сразу: библиотека добирается чанками (~100), а не в конце.
            // Первая сотня — уже через ~минуту после логина (детали больше
            // не держат старт: фолбэк 1.5 отрабатывает после поиска).
            if (catchUp && flushedSinceUiRefresh >= 100) {
                flushedSinceUiRefresh = 0
                val rebuilt = withContext(Dispatchers.Default) { buildLibraryItems() }
                uiState = uiState.copy(library = rebuilt)
            }
        }
        var resolved = 0
        val tally2 = PullTally()
        var firstSearch = true
        var sinceFlush = 0
        // Жертвы шторма 429: ни один запрос не получил ответа — не промахи,
        // а отложенный повтор после прохода (иначе троттлинг тихо хоронит
        // десятки резолвящихся тайтлов: кейс 09.09, 17 штук за один catch-up).
        val rateLimited = mutableListOf<Pair<Int, hd.kinoshka.app.data.model.AnixartRelease>>()
        var consecutiveNetFail = 0
        // Резолвы поиска: фолбэк 1.5 ниже добирает только НЕ покрытые поиском.
        val searchResolvedIds = mutableSetOf<Int>()
        // Фаза 0 (офлайн): локальный индекс Shikimori вместо сотен поисковых
        // запросов (лимит 5rps/90rpm — потолок живого поиска, ~9 мин catch-up).
        // Сверки те же (общий tryPullHit по brief-записям); непокрытое уходит
        // в живой поиск ниже как раньше.
        var offlineHits = 0
        val offlineIndex = loadOfflineIndex()
        if (offlineIndex != null && unmatchedReleases.isNotEmpty()) {
            if (catchUp) setImportProgress("Быстрое сопоставление", 0, remainingUnmatched)
            val matcher = hd.kinoshka.app.data.source.TitleMatching
            data class OfflinePlan(
                val listId: Int,
                val release: hd.kinoshka.app.data.model.AnixartRelease,
                val entryIds: List<Int>,
                val level: String
            )
            val plan = mutableListOf<OfflinePlan>()
            for ((listId, release) in unmatchedReleases) {
                if (release.id in anixartPullUnresolvable) continue
                val queries = listOfNotNull(release.titleOriginal, release.titleRu, release.titleEn)
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
                val norms = queries.map { matcher.normalizeTitle(it) }.filter { it.isNotEmpty() }
                if (norms.isEmpty()) continue
                val solids = norms.map { it.replace(" ", "") }
                    .filter { it.length >= 8 }.toSet()
                val cores = norms.map { matcher.stripDecorativeMarkers(it) }.toSet()
                val m = offlineIndex.match(norms, solids, cores) ?: continue
                plan.add(OfflinePlan(listId, release, m.entryIds, m.level))
            }
            if (plan.isNotEmpty()) {
                // Brief-записи батчами ids= (50/chunk): постеры/названия для
                // оболочек едут отсюда же, отдельным добором не нужны.
                val briefById = mutableMapOf<Int, hd.kinoshka.app.data.model.ShikimoriAnimeItem>()
                for (ids in plan.flatMap { it.entryIds }.distinct().chunked(500)) {
                    briefById += fetchAnimeBrief(ids)
                }
                for ((listId, release, entryIds, level) in plan) {
                    val status = hd.kinoshka.app.data.repo.anixartListToStatus(listId) ?: continue
                    val year = release.releaseYear()
                    val (relSeason, relKind, _) = anixartReleaseSKY(release)
                    val hit = entryIds.firstNotNullOfOrNull { id ->
                        briefById[id]?.let { tryPullHit(release, year, vetoes, relSeason, relKind, it) }
                    } ?: continue
                    resolved++
                    offlineHits++
                    searchResolvedIds.add(release.id)
                    if (catchUp) setImportProgress("Быстрое сопоставление", resolved, remainingUnmatched)
                    KLog.d(
                        "AnixartSync",
                        "pull-resolve: release=${release.id} shikimoriId=${hit.shikimoriId} via offline-$level"
                    )
                    reconcilePulledRelease(
                        release.id, hit.shikimoriId, status, "offline-index",
                        hit.title, hit.subtitle, hit.poster, tally2
                    )
                    if (++sinceFlush >= 50) {
                        flushPullProgress()
                        sinceFlush = 0
                    }
                }
            }
            KLog.i("AnixartSync", "pull: offline-index hits=$offlineHits of ${plan.size} planned")
        }
        // Живой поиск — только непокрытое офлайн-фазой (searchResolvedIds);
        // кап применяется к живой работе, а не к тейку (иначе офлайн-хиты
        // съедали бы лимит steady-state, а в catch-up шёл двойной резолв
        // всех 820 с формулой unmatched=-820: кейс 10.09).
        val liveTargets = unmatchedReleases.filter { it.second.id !in searchResolvedIds }
        for ((listId, release) in liveTargets.take(pullResolveLimit)) {
            if (release.id <= 0) continue
            val status = hd.kinoshka.app.data.repo.anixartListToStatus(listId) ?: continue
            if (!firstSearch) kotlinx.coroutines.delay(300L)
            firstSearch = false
            val outcome = searchShikimoriForAnixart(release, release.releaseYear(), vetoes)
            if (outcome.hit == null && !outcome.searchedOk) {
                rateLimited.add(listId to release)
                // Шторм подряд: новые запросы всё равно упрутся в то же окно
                // лимита — одна длинная пауза дешевле цепочки ретраев 1+2+4с
                // на каждом запросе.
                if (++consecutiveNetFail >= 2) {
                    consecutiveNetFail = 0
                    KLog.i("AnixartSync", "pull: 429-storm cooldown 10s")
                    if (catchUp) setImportProgress("Пауза (лимит Shikimori)", resolved, remainingUnmatched)
                    kotlinx.coroutines.delay(10_000L)
                }
                continue
            }
            consecutiveNetFail = 0
            val hit = outcome.hit ?: continue
            resolved++
            searchResolvedIds.add(release.id)
            if (catchUp) setImportProgress("Сопоставление", resolved, remainingUnmatched)
            KLog.d(
                "AnixartSync",
                "pull-resolve: release=${release.id} shikimoriId=${hit.shikimoriId} via shiki-search"
            )
            reconcilePulledRelease(
                release.id, hit.shikimoriId, status, "shiki-search",
                hit.title, hit.subtitle, hit.poster, tally2
            )
            // Чанковый flush: длинный catch-up переживает убийство процесса —
            // следующий синк продолжит с созданных профилей, а не с нуля.
            if (++sinceFlush >= 50) {
                flushPullProgress()
                sinceFlush = 0
            }
        }
        // Отложенный повтор жертв 429: окно лимита уже провернулось, шаг мягче
        // (2с) — добираем поиском, а не деталями (у деталей id франшизного
        // уровня, каскад им не товарищ). Неудача здесь — не мемоизируется:
        // следующий синк попробует снова, как и раньше.
        var deferredHits = 0
        if (rateLimited.isNotEmpty()) {
            KLog.i("AnixartSync", "pull: deferred retry for ${rateLimited.size} rate-limited release(s)")
            if (catchUp) setImportProgress("Повторные запросы", resolved, remainingUnmatched)
            for ((listId, release) in rateLimited) {
                val status = hd.kinoshka.app.data.repo.anixartListToStatus(listId) ?: continue
                kotlinx.coroutines.delay(2_000L)
                val hit = searchShikimoriForAnixart(release, release.releaseYear(), vetoes).hit ?: continue
                resolved++
                deferredHits++
                searchResolvedIds.add(release.id)
                if (catchUp) setImportProgress("Повторные запросы", resolved, remainingUnmatched)
                KLog.d(
                    "AnixartSync",
                    "pull-resolve: release=${release.id} shikimoriId=${hit.shikimoriId} via shiki-search-retry"
                )
                reconcilePulledRelease(
                    release.id, hit.shikimoriId, status, "shiki-search-retry",
                    hit.title, hit.subtitle, hit.poster, tally2
                )
                if (++sinceFlush >= 50) {
                    flushPullProgress()
                    sinceFlush = 0
                }
            }
            KLog.i("AnixartSync", "pull: deferred retry hits=$deferredHits of ${rateLimited.size}")
        }
        // Фолбэк 1.5: детали только для остатков поиска (см. выше) — upfront
        // он держал старт ~50 c при hits=0.
        val detailLeftovers = unmatchedReleases.filter { it.second.id !in searchResolvedIds }
        if (detailLeftovers.isNotEmpty()) {
            if (catchUp) setImportProgress("Уточнение", resolved, remainingUnmatched)
            KLog.i("AnixartSync", "pull: detail fallback for ${detailLeftovers.size} leftover(s)")
            runDetailFallback(detailLeftovers)
        }
        flushPullProgress()
        // Сироты самолечения: shiki, сброшенные валидацией и не подобранные
        // перерезолвом (промах каскада), — их оболочки удаляем. Только shiki из
        // droppedShikis этого пула (снятия на сайте сюда не попадают) и только
        // нетронутые оболочки (importSource == "anixart").
        if (droppedShikis.isNotEmpty()) {
            val orphans = droppedShikis - anixartIdToShiki.values.toSet()
            if (orphans.isNotEmpty()) {
                val removed = withContext(Dispatchers.IO) {
                    userStateStore.removeImportedOrphans(orphans)
                }
                if (removed > 0) {
                    KLog.i("AnixartSync", "pull: removed $removed orphan shell profile(s)")
                    val rebuilt = withContext(Dispatchers.Default) { buildLibraryItems() }
                    uiState = uiState.copy(library = rebuilt)
                }
            }
        }
        val adopted = tally1.adopted + tally2.adopted + detailTally.adopted
        val diverged = tally1.diverged + tally2.diverged + detailTally.diverged
        // Диагностика контеста: реальные названия релизов из пула (без них разбор
        // «дубль vs разные сезоны» слепой — id одни, а контент может различаться).
        if (anixartContestedShiki.isNotEmpty()) {
            val relById = lists.values.flatten().associateBy { it.id }
            for (sid in anixartContestedShiki.sorted()) {
                val rids = anixartIdToShiki.filterValues { it == sid }.keys.sorted()
                for (rid in rids) {
                    val r = relById[rid]
                    KLog.i(
                        "AnixartSync",
                        "contested-detail: shikimoriId=$sid release=$rid lists=${releaseLists[rid]} " +
                            "ru=${r?.titleRu} orig=${r?.titleOriginal} en=${r?.titleEn} " +
                            "year=${r?.yearRaw} season=${r?.seasonRaw} eps=${r?.episodesTotalRaw} country=${r?.countryRaw}"
                    )
                }
            }
        }
        if (createdTotal > 0 || adopted > 0) {
            val rebuilt = withContext(Dispatchers.Default) { buildLibraryItems() }
            uiState = uiState.copy(library = rebuilt)
        }
        // Импорт отработал — баннер гаснет до итоговой строки (метаданные ниже,
        // если их много, поднимут свою неопределённую фазу сами).
        clearImportProgress()
        KLog.i(
            "AnixartSync",
            "pull: matched byId=${pass1.byId} exact=${pass1.exact} fuzzy=${pass1.fuzzy} " +
                "resolved=$resolved unmatched=${remainingUnmatched - resolved - detailHits} " +
                "knownMiss=$knownMiss detailHits=$detailHits yearVeto=${vetoes.releases.size} " +
                "created=$createdTotal " +
                "adopted=$adopted diverged=$diverged"
        )
        // Полный пул только что отработал — штампуем троттл foreground-синка:
        // иначе ON_RESUME следом за init/логином гнал второй полный пул
        // (померяно в проде: два пула по ~30 запросов с разницей 10 c).
        lastAnixartSyncMs = System.nanoTime() / 1_000_000L
        // Catch-up только что массово импортировал с сервера: локальные «новее»
        // из этого синка — эхо импорта, пушить нечего (следующий обычный синк
        // отправит настоящие правки, если они появятся).
        if (pushLocalNewer && !catchUp) pushDirtyAnixartLists(token)
        // Импортированные оболочки (статус без подробностей) добираем из Shikimori.
        ensureLibraryAnimeMeta()
    }

    /** Хит обратного поиска Shikimori для релиза Anixart (точный матч названия). */
    private data class ShikiPullHit(
        val shikimoriId: Int,
        val title: String,
        val subtitle: String?,
        val poster: String?
    )

    /**
     * Исход searchShikimoriForAnixart: hit=null при searchedOk=true — честный
     * промах (мемоизируется в pullUnresolvable); searchedOk=false — сеть/429
     * не дали ни одного ответа, релиз идёт в отложенный повтор, а не в промахи
     * (иначе шторм 429 тихо хоронил бы десятки резолвящихся тайтлов).
     */
    private data class ShikiSearchOutcome(
        val hit: ShikiPullHit?,
        val searchedOk: Boolean
    )

    /** Сезон/вид/год релиза Anixart: консенсус по всем названиям (ru/orig/en) —
     *  маркер обычно лишь в одном («...: Фильм» vs bare orig). Разные номера
     *  в разных названиях → неоднозначность (null): не знаем — не мешаем. */
    private fun anixartReleaseSKY(
        release: hd.kinoshka.app.data.model.AnixartRelease
    ): Triple<Int?, String?, Int?> {
        val matcher = hd.kinoshka.app.data.source.TitleMatching
        val infos = listOfNotNull(release.titleOriginal, release.titleRu, release.titleEn)
            .map { matcher.parseSeasonKind(matcher.normalizeTitle(it)) }
        return Triple(
            infos.mapNotNull { it.season }.toSet().singleOrNull(),
            infos.mapNotNull { it.kind }.toSet().singleOrNull(),
            release.releaseYear()
        )
    }

    /** Сезон/вид кандидата Shikimori: kind из brief авторитетен, иначе из названий. */
    private fun shikiCandidateSK(
        name: String?, russian: String?, kind: String?
    ): Pair<Int?, String?> {        val matcher = hd.kinoshka.app.data.source.TitleMatching
        val infos = listOfNotNull(name, russian)
            .map { matcher.parseSeasonKind(matcher.normalizeTitle(it)) }
        val ck = kind?.lowercase()?.takeIf { it.isNotBlank() }
            ?: infos.mapNotNull { it.kind }.toSet().singleOrNull()
        return infos.mapNotNull { it.season }.toSet().singleOrNull() to ck
    }

    /** Живой прогресс catch-up: баннер Библиотеки. Дешёвый state-copy,
     *  список под ним не перестраивается (тот же reference). */
    private fun setImportProgress(phase: String, done: Int, total: Int) {
        val p = AnixartImportProgress(phase, done, total)
        uiState = uiState.copy(anixartImportProgress = p)
        AnixartImportBus.flow.value = p
    }

    /** Гасим баннер (no-op, если его нет). */
    private fun clearImportProgress() {
        if (uiState.anixartImportProgress != null) {
            uiState = uiState.copy(anixartImportProgress = null)
        }
        if (AnixartImportBus.flow.value != null) {
            AnixartImportBus.flow.value = null
        }
    }

    /** Счётчик годовых вето за пул (сверка сезонов Anixart↔Shikimori). */
    private class YearVetoes {
        var checks = 0
        val releases = mutableSetOf<Int>()
        fun veto(releaseId: Int) {
            checks++
            releases.add(releaseId)
        }
    }

    /**
     * Обратная резолюция: shikimoriId для релиза Anixart через поиск Shikimori.
     * Каскад точного нормализованного матча: сначала полное название
     * (name/russian результатов любому названию релиза), затем алиасы сезонных
     * хвостов («TV-2»→«2nd Season» — сезон сохраняют), затем беспробельное
     * («To aru»→«Toaru»), затем обратный префикс («Maou Gakuin» vs полное имя
     * с субтитром; хвост-«продолжение» — отказ), затем алиас в кавычках
     * («Shomin Sample»), затем ядро без маркеров («K-On! 2»→«K-On»),
     * затем префикс («Sakugan» vs «Sakugan Labyrinth Marker»).
     * Фолбэки — только против результатов поиска ПОЛНОГО запроса, так что
     * релевантность Shikimori остаётся гардом, а импорт чужого тайтла
     * по-прежнему хуже пропуска. Честный «не нашли» мемоизируем
     * в [anixartPullUnresolvable] (сессия), ошибки сети — нет, их повторит синк.
     * Вызывать под anixartSyncMutex; сеть — на IO.
     */
    /**
     * Единая воронка всех проходов каскада (живой поиск, офлайн-индекс):
     * годовая + сезонно-видовая сверка кандидата. null — кандидат отклонён.
     */
    private fun tryPullHit(
        release: hd.kinoshka.app.data.model.AnixartRelease,
        expectedYear: Int?,
        vetoes: YearVetoes?,
        relSeason: Int?,
        relKind: String?,
        item: hd.kinoshka.app.data.model.ShikimoriAnimeItem
    ): ShikiPullHit? {
        val matcher = hd.kinoshka.app.data.source.TitleMatching
        // Годовая сверка: Anixart год выхода знает (17/17 сверенных), Shikimori
        // aired_on — тоже. Известное расхождение = разные сезоны/записи:
        // пропускаем кандидата (veto), а не импортируем чужое. Неизвестный
        // год с любой стороны — не вето (пропускаем).
        val itemYear = item.airedOn?.take(4)?.toIntOrNull()
        if (expectedYear != null && itemYear != null && itemYear != expectedYear) {
            vetoes?.veto(release.id)
            KLog.d(
                "AnixartSync",
                "pull-resolve: release=${release.id} shikimoriId=${item.id} " +
                    "year-veto (anixart=$expectedYear shiki=$itemYear)"
            )
            return null
        }
        // Сезонно-видовая сверка (09.09): точность строк склейки сезонов не ловит
        // («Space Dandy TV-2»→S1 при совпадении годов), маркеры — ловят.
        val (candSeason, candKind) = shikiCandidateSK(item.name, item.russian, item.kind)
        if (matcher.seasonKindVeto(relSeason, relKind, expectedYear, candSeason, candKind, itemYear)) {
            KLog.d(
                "AnixartSync",
                "pull-resolve: release=${release.id} shikimoriId=${item.id} " +
                    "season-kind-veto (rel=$relSeason/$relKind/$expectedYear " +
                    "cand=$candSeason/$candKind/$itemYear)"
            )
            return null
        }
        return ShikiPullHit(
            shikimoriId = item.id,
            title = item.russian?.takeIf { it.isNotBlank() }
                ?: item.name?.takeIf { it.isNotBlank() }
                ?: release.titleRu ?: release.titleOriginal ?: release.titleEn ?: "Без названия",
            subtitle = item.name,
            poster = item.posterUrl
        )
    }

    private suspend fun searchShikimoriForAnixart(
        release: hd.kinoshka.app.data.model.AnixartRelease,
        expectedYear: Int? = null,
        vetoes: YearVetoes? = null
    ): ShikiSearchOutcome {
        if (release.id in anixartPullUnresolvable) return ShikiSearchOutcome(null, true)
        val matcher = hd.kinoshka.app.data.source.TitleMatching
        val queries = listOfNotNull(release.titleOriginal, release.titleRu, release.titleEn)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
        if (queries.isEmpty()) return ShikiSearchOutcome(null, true)
        val normed = queries.map { matcher.normalizeTitle(it) }.filter { it.isNotEmpty() }
        if (normed.isEmpty()) return ShikiSearchOutcome(null, true)
        val normedCores = normed.map { matcher.stripDecorativeMarkers(it) }
            .filter { it.isNotEmpty() }.toSet()
        // Беспробельные слепки для класса «To aru»→«Toaru»: точное равенство
        // modulo пробелы, сезонность не трогает.
        val normedSolid = normed.map { it.replace(" ", "") }
            .filter { it.length >= 8 }.toSet()
        // Сезон/вид/год релиза один раз на вызов: все проходы ниже сверяются с ними.
        val (relSeason, relKind, _) = anixartReleaseSKY(release)
        fun tryHit(item: hd.kinoshka.app.data.model.ShikimoriAnimeItem): ShikiPullHit? =
            tryPullHit(release, expectedYear, vetoes, relSeason, relKind, item)
        var searchedOk = false
        // Проход 1: полное точное по всем запросам (результаты копим для прохода 2).
        val seenHits = linkedMapOf<Int, hd.kinoshka.app.data.model.ShikimoriAnimeItem>()
        for (query in queries) {
            val hits = withContext(Dispatchers.IO) {
                runCatching {
                    animeRepository.search(query = query, censored = false, limit = 10)
                }.getOrNull()
            } ?: continue
            searchedOk = true
            for (item in hits) {
                if (item.id <= 0) continue
                seenHits.putIfAbsent(item.id, item)
                val itemTitles = listOfNotNull(item.name, item.russian)
                    .map { matcher.normalizeTitle(it) }
                if (itemTitles.any { it.isNotEmpty() && it in normed }) {
                    tryHit(item)?.let { return ShikiSearchOutcome(it, true) }
                }
            }
        }
        // Проход 2: алиасы сезонных хвостов («TV-2»→«2nd Season») — сезон сохраняют,
        // схлопнуть S2 в S1 не могут. Затем 2б (spaceless), 2в (reverse-prefix),
        // 2г (quoted), 2д (ядро), 2е (префикс) — см. ниже.
        val normedAliases = normed.flatMap { matcher.seasonAliases(it) }.toSet()
        if (normedAliases.isNotEmpty()) {
            for (item in seenHits.values) {
                if (item.id <= 0) continue
                val itemTitles = listOfNotNull(item.name, item.russian)
                    .map { matcher.normalizeTitle(it) }
                if (itemTitles.any { it.isNotEmpty() && it in normedAliases }) {
                    KLog.d(
                        "AnixartSync",
                        "pull-resolve: release=${release.id} shikimoriId=${item.id} via season-alias match"
                    )
                    tryHit(item)?.let { return ShikiSearchOutcome(it, true) }
                }
            }
        }
        // Проход 2б: беспробельное точное («To aru»→«Toaru»).
        for (item in seenHits.values) {
            if (item.id <= 0) continue
            val itemSolid = listOfNotNull(item.name, item.russian)
                .map { matcher.normalizeTitle(it).replace(" ", "") }
            if (itemSolid.any { it.isNotEmpty() && it in normedSolid }) {
                KLog.d(
                    "AnixartSync",
                    "pull-resolve: release=${release.id} shikimoriId=${item.id} via spaceless match"
                )
                tryHit(item)?.let { return ShikiSearchOutcome(it, true) }
            }
        }
        // Проход 2в: обратный префикс (запрос — начало имени: «Maou Gakuin» vs
        // полное «Maou Gakuin: Shijou Saikyou …»). Хвост-«продолжение» — отказ
        // (см. TitleMatching.isSequelTail), хвост-субтитр — матч.
        // Дальше фолбэки идут от сильного к слабому: ядро (2г), затем префикс (2д).
        for (item in seenHits.values) {
            if (item.id <= 0) continue
            val itemTitles = listOfNotNull(item.name, item.russian)
                .map { matcher.normalizeTitle(it) }
            val reverseHit = itemTitles.any { t ->
                t.isNotEmpty() && (t.length >= 6 || t.any { c -> c.code >= 0x2E80 }) &&
                    normed.any { q ->
                        t.startsWith("$q ") && !matcher.isContinuationTail(t.removePrefix(q).trim())
                    }
            }
            if (reverseHit) {
                KLog.d(
                    "AnixartSync",
                    "pull-resolve: release=${release.id} shikimoriId=${item.id} via reverse-prefix match"
                )
                tryHit(item)?.let { return ShikiSearchOutcome(it, true) }
            }
        }
        // Проход 2г: алиас в кавычках официального имени («… "Shomin Sample" …»).
        // Кавычки в названиях маркируют обиходное имя — точное равенство с запросом.
        for (item in seenHits.values) {
            if (item.id <= 0) continue
            // Кавычки гибнут в нормализации — извлекаем из СЫРОГО имени.
            val quoted = listOfNotNull(item.name, item.russian)
                .flatMap { matcher.quotedAliases(it) }
                .toSet()
            if (quoted.isEmpty()) continue
            val quotedHit = normed.any { it in quoted }
            if (quotedHit) {
                KLog.d(
                    "AnixartSync",
                    "pull-resolve: release=${release.id} shikimoriId=${item.id} via quoted-alias match"
                )
                tryHit(item)?.let { return ShikiSearchOutcome(it, true) }
            }
        }
        // Проход 2д: ядро без декоративных маркеров — только по уже найденному.
        for (item in seenHits.values) {
            val itemTitles = listOfNotNull(item.name, item.russian)
                .map { matcher.normalizeTitle(it) }
            if (itemTitles.any { it.isNotEmpty() && it in normedCores }) {
                KLog.d(
                    "AnixartSync",
                    "pull-resolve: release=${release.id} shikimoriId=${item.id} via stripped-core match"
                )
                tryHit(item)?.let { return ShikiSearchOutcome(it, true) }
            }
        }
        // Проход 2е: префикс (кандидат — начало запроса: «Sakugan» vs
        // «Sakugan Labyrinth Marker»). Длина ядра ≥6 (≥3 для CJK), иначе короткие
        // слова («C», «K») давали бы ложные срабатывания.
        for (item in seenHits.values) {
            val itemTitles = listOfNotNull(item.name, item.russian)
                .map { matcher.normalizeTitle(it) }
            val prefixHit = itemTitles.any { t ->
                t.isNotEmpty() && (t.length >= 6 || t.any { c -> c.code >= 0x2E80 }) &&
                    normed.any { q -> q.startsWith("$t ") }
            }
            if (prefixHit) {
                KLog.d(
                    "AnixartSync",
                    "pull-resolve: release=${release.id} shikimoriId=${item.id} via prefix match"
                )
                tryHit(item)?.let { return ShikiSearchOutcome(it, true) }
            }
        }
        if (searchedOk) {
            anixartPullUnresolvable.add(release.id)
            // Диагностика для будущих промахов: что искали (нормы), что вернул топ
            // (сырьём и нормами — расхождение видно посимвольно) — без этого разбор
            // «почему не сматчилось» слепой.
            val topRaw = seenHits.values.take(3).mapNotNull { it.name ?: it.russian }
            val topNorm = topRaw.map { matcher.normalizeTitle(it) }
            KLog.d(
                "AnixartSync",
                "pull-resolve: release=${release.id} no exact shiki match, skip " +
                    "queries=$normed top=$topRaw topNorm=$topNorm"
            )
        }
        return ShikiSearchOutcome(null, searchedOk)
    }

    /**
     * Резолюция релиза Anixart для локального тайтла: сначала карты пула, иначе
     * поиск по каталогу (точный матч). Успех сразу кладём в карты, честный
     * «нет в каталоге» — в сессионное множество; ошибки сети не мемоизируем.
     * Вызывать под anixartSyncMutex; сеть — на IO.
     */
    private suspend fun resolveAnixartId(
        token: String?,
        shikimoriId: Int,
        extraTitles: List<String?> = emptyList(),
        nameCache: Map<Int, hd.kinoshka.app.data.local.ShikimoriAnimeCache>? = null
    ): Int? {
        anixartIdToShiki.entries.firstOrNull { it.value == shikimoriId }?.key?.let { return it }
        if (shikimoriId in anixartUnresolvable) return null
        val repo = anixartRepository ?: return null
        val cached = nameCache?.get(shikimoriId)
            ?: withContext(Dispatchers.IO) { userStateStore.getShikimoriAnimeCache()[shikimoriId] }
        val titles = listOfNotNull(cached?.name, cached?.russian) + extraTitles.filterNotNull()
        val outcome = withContext(Dispatchers.IO) { repo.findReleaseId(token, titles, cached?.year) }
        if (outcome.releaseId != null && outcome.releaseId > 0) {
            val updated = anixartIdToShiki.toMutableMap()
            updated[outcome.releaseId] = shikimoriId
            anixartIdToShiki = updated
            KLog.d(
                "AnixartSync",
                "resolve: shikimoriId=$shikimoriId release=${outcome.releaseId} via catalog search"
            )
            return outcome.releaseId
        }
        if (outcome.searchedOk && !outcome.yearVetoed) {
            anixartUnresolvable.add(shikimoriId)
            KLog.d("AnixartSync", "resolve: shikimoriId=$shikimoriId no exact catalog match, skip")
        }
        if (outcome.yearVetoed) {
            // Год не сошёлся: не мемоизируем (как сеть — повторит следующий синк).
            KLog.d("AnixartSync", "resolve: shikimoriId=$shikimoriId catalog year-veto, retry later")
        }
        return null
    }

    /**
     * Пуш локальных статусов в списки Anixart: переносы сматченных при пуле тайтлов
     * + добавление несматченных (резолюция через поиск каталога — иначе
     * Shikimori-тайтлы никогда не доедут до Anixart и общего списка не получится).
     * Удаление из чужих списков + добавление в целевой — перенос между списками.
     * Вызывать под anixartSyncMutex. Сеть — на IO (пачка переносов на Main вешала UI).
     */
    private suspend fun pushDirtyAnixartLists(token: String, onlyKpId: Int? = null) {
        val repo = anixartRepository ?: return
        // Контест для точечных пушей между пулами (пул держит свежий in-memory).
        if (anixartContestedShiki.isEmpty()) {
            anixartContestedShiki.addAll(withContext(Dispatchers.IO) { userStateStore.getAnixartContested() })
        }
        if (pushingAnixart) return
        pushingAnixart = true
        try {
            val shikiToAnixart = mutableMapOf<Int, Int>()
            anixartIdToShiki.forEach { (anixartId, shikimoriId) ->
                shikiToAnixart.putIfAbsent(shikimoriId, anixartId)
            }
            // Один тайтл — несколько релизов (сезоны/спешлы отдельными записями):
            // переносим каждый, иначе дубли вечно числятся diverged.
            val releasesByShiki = mutableMapOf<Int, MutableList<Int>>()
            anixartIdToShiki.forEach { (anixartId, shikimoriId) ->
                releasesByShiki.getOrPut(shikimoriId) { mutableListOf() }.add(anixartId)
            }
            var pushed = 0
            var resolved = 0
            var rateOnlyPushed = 0
            var ops = 0
            val nameCache = withContext(Dispatchers.IO) { userStateStore.getShikimoriAnimeCache() }
            // Кандидаты пуша: профили + rate-backed (рейт Shikimori без профиля —
            // раньше были невидимы пушу, ~850 тайтлов никогда не доезжали до Anixart).
            // Активные статусы первыми; take() больше нет — голодания хвоста нет,
            // сеть лимитирует только бюджет операций.
            data class AnixartPushCandidate(
                val shikimoriId: Int,
                val status: UserFilmStatus,
                val extraTitles: List<String>,
                val fromRate: Boolean
            )
            fun statusPushPriority(s: UserFilmStatus): Int = when (s) {
                UserFilmStatus.WATCHING, UserFilmStatus.PLANNED,
                UserFilmStatus.ON_HOLD, UserFilmStatus.REWATCHING -> 0
                UserFilmStatus.COMPLETED -> 1
                UserFilmStatus.DROPPED -> 2
            }
            val storedProfiles = withContext(Dispatchers.IO) { userStateStore.getProfiles() }
            val profileByShiki = storedProfiles
                .filter { it.kinopoiskId >= ANIME_ID_OFFSET && it.status != null }
                .associateBy { it.kinopoiskId - ANIME_ID_OFFSET }
            val rateOnlyStatuses = withContext(Dispatchers.IO) {
                val snapshot = userStateStore.getShikimoriRatesSnapshot()
                val authUserId = uiState.shikimoriAuthState.userId
                if (snapshot.rates.isEmpty() || (authUserId > 0 && snapshot.userId != authUserId)) {
                    emptyList()
                } else {
                    snapshot.rates.mapNotNull { rate ->
                        if (rate.targetId <= 0 || rate.targetId in profileByShiki) return@mapNotNull null
                        val st = shikiRateStatusToUserStatus(rate.status) ?: return@mapNotNull null
                        rate.targetId to st
                    }
                }
            }
            val allCandidates = (
                profileByShiki.map { (sid, p) ->
                    AnixartPushCandidate(sid, p.status!!, listOfNotNull(p.title, p.subtitle), false)
                } + rateOnlyStatuses.map { (sid, st) ->
                    AnixartPushCandidate(sid, st, emptyList(), true)
                }
                ).filter { onlyKpId == null || it.shikimoriId + ANIME_ID_OFFSET == onlyKpId }
                .sortedWith(compareBy({ statusPushPriority(it.status) }, { it.shikimoriId }))
            // Импортные оболочки (restore) не пушим, пока их не коснулась явная
            // правка: иначе echo давит сервер (инцидент 09.09). Контест (дубли по
            // спискам) не пушим никогда — даже точечным пушем из редактора: одна
            // запись у нас против двух на сервере, перенос снёс бы вторую.
            val candidates = allCandidates.filter { c ->
                val profile = profileByShiki[c.shikimoriId]
                (profile == null || profile.importSource == null) &&
                    c.shikimoriId !in anixartContestedShiki
            }
            if (candidates.size != allCandidates.size) {
                KLog.d(
                    "AnixartSync",
                    "push: muted ${allCandidates.size - candidates.size} echo/contested candidate(s)"
                )
            }
            // Перенос между списками: удаление из чужих + добавление в целевой.
            // Возвращает true при «уже на месте» или успешном переносе.
            suspend fun moveToList(
                shikimoriId: Int,
                anixartId: Int,
                target: Int,
                status: UserFilmStatus,
                via: String
            ): Boolean {
                val current = anixartReleaseLists[anixartId].orEmpty()
                if (target in current) return true
                if (ops >= MAX_ANIXART_PUSH_OPS_PER_SYNC) return false
                ops++
                KLog.d(
                    "AnixartSync",
                    "push: shikimoriId=$shikimoriId release=$anixartId " +
                        "$via, lists($current -> $target) status=$status"
                )
                var ok = true
                current.forEach { if (!repo.removeFromList(token, it, anixartId)) ok = false }
                if (ok && repo.addToList(token, target, anixartId)) {
                    val updated = anixartReleaseLists.toMutableMap()
                    updated[anixartId] = setOf(target)
                    anixartReleaseLists = updated
                    return true
                }
                KLog.w(
                    "AnixartSync",
                    "push FAILED shikimoriId=$shikimoriId release=$anixartId " +
                        "target=$target, will retry next sync"
                )
                return false
            }
            withContext(Dispatchers.IO) {
                for (c in candidates) {
                    val releaseIds = releasesByShiki[c.shikimoriId] ?: continue
                    val target = c.status.toAnixartList()
                    for (anixartId in releaseIds) {
                        if (target in anixartReleaseLists[anixartId].orEmpty()) continue
                        if (ops >= MAX_ANIXART_PUSH_OPS_PER_SYNC) break
                        if (moveToList(c.shikimoriId, anixartId, target, c.status, "lists")) {
                            pushed++
                            if (c.fromRate) rateOnlyPushed++
                        }
                    }
                    if (ops >= MAX_ANIXART_PUSH_OPS_PER_SYNC) break
                }
                // Несматченные пулом тайтлы: резолюция в каталоге + добавление в целевой
                // список. Поиск — пачкой параллельно (зеркало без жёстких лимитов): пачка
                // последовательных POST по ~250 мс держала мьютекс синка секундами.
                // Политика resolveAnixartId: только точный матч, честный промах
                // мемоизируем (персист), ошибки сети — нет (повторит следующий синк).
                val unmatched = candidates
                    .filter { it.shikimoriId !in shikiToAnixart }
                    .filter { it.shikimoriId !in anixartUnresolvable }
                    .take(MAX_ANIXART_RESOLVE_PER_SYNC)
                val resolveSemaphore = kotlinx.coroutines.sync.Semaphore(3)
                val resolveOutcomes = unmatched.map { c ->
                    async {
                        resolveSemaphore.acquire()
                        try {
                            c to resolveAnixartId(token, c.shikimoriId, c.extraTitles, nameCache)
                        } finally {
                            resolveSemaphore.release()
                        }
                    }
                }.awaitAll()
                for ((c, anixartId) in resolveOutcomes) {
                    if (anixartId == null) continue
                    val target = c.status.toAnixartList()
                    if (target in anixartReleaseLists[anixartId].orEmpty()) continue
                    if (ops >= MAX_ANIXART_PUSH_OPS_PER_SYNC) break
                    if (moveToList(c.shikimoriId, anixartId, target, c.status, "resolved")) {
                        resolved++
                        if (c.fromRate) rateOnlyPushed++
                    }
                }
            }
            val stillUnmatched = candidates.count { it.shikimoriId !in anixartIdToShiki.values }
            if (pushed > 0 || resolved > 0 || stillUnmatched > 0) {
                KLog.i(
                    "AnixartSync",
                    "push: moved $pushed title(s) (rate-backed $rateOnlyPushed), " +
                        "resolved+added $resolved title(s), still-unmatched $stillUnmatched title(s)"
                )
            }
            // Пост-пуш состояние — новый baseline: иначе рестарт до следующего пула
            // откатывал бы вердикт untouched/diverged на допереносное и пуш заново
            // давил бы уже принятые сервером правки.
            if (pushed > 0 || resolved > 0) {
                withContext(Dispatchers.IO) {
                    userStateStore.setAnixartListsBaseline(anixartReleaseLists)
                    userStateStore.setAnixartIdMap(anixartIdToShiki)
                    userStateStore.setAnixartUnresolvable(anixartUnresolvable)
                }
            }
            if (pushed > 0) {
                refreshLibraryAndAvatar()
            }
        } finally {
            pushingAnixart = false
        }
    }

    /**
     * Точечный пуш одного тайтла (сохранение в редакторе прогресса): сначала verify
     * свежего серверного статуса (1 GET) — уже в целевом списке, писать нечего.
     */
    fun pushAnixartTitle(kinopoiskId: Int) {
        val auth = uiState.anixartAuthState
        val token = auth.token
        val repo = anixartRepository
        if (!auth.isLoggedIn || token == null || repo == null) return
        if (kinopoiskId < ANIME_ID_OFFSET) return
        viewModelScope.launch {
            anixartSyncMutex.withLock {
                val profile = withContext(Dispatchers.IO) { userStateStore.getProfile(kinopoiskId) }
                val target = profile?.status?.toAnixartList() ?: return@withLock
                val shikimoriId = kinopoiskId - ANIME_ID_OFFSET
                // Несматченного пулом тайтла здесь раньше просто не было (return):
                // ищем в каталоге, иначе сохранение из карточки до Anixart не доезжает.
                val anixartId = resolveAnixartId(
                    token, shikimoriId, listOf(profile?.title, profile?.subtitle)
                ) ?: return@withLock
                val fresh = withContext(Dispatchers.IO) { repo.releaseListStatus(token, anixartId) }
                if (fresh != null) {
                    val updated = anixartReleaseLists.toMutableMap()
                    updated[anixartId] = if (fresh > 0) setOf(fresh) else emptySet()
                    anixartReleaseLists = updated
                    if (target == fresh) {
                        KLog.d("AnixartSync", "push: shikimoriId=$shikimoriId already in list $target, skip")
                        return@withLock
                    }
                }
                pushDirtyAnixartLists(token, onlyKpId = kinopoiskId)
            }
        }
    }

    /**
     * Удаление тайтла из списков Anixart (удаление из библиотеки, status=null):
     * паритет с удалением Shikimori-рейта в saveUserProfile. Без этого следующий
     * пул воскресил бы тайтл локально (reconcilePulledRelease создаёт профиль
     * под каждый релиз из списков). Трогаем только релиз, реально лежащий
     * в списках юзера (свежий verify через releaseInfo + сверка shikimori_id):
     * точный матч по названию чужой тайтл снести не должен. Возвращает успех;
     * вызывать под anixartSyncMutex, сеть — на IO.
     */
    private suspend fun deleteAnixartTitle(
        token: String,
        kinopoiskId: Int,
        titles: List<String?>
    ): Boolean {
        val repo = anixartRepository ?: return true
        val shikimoriId = kinopoiskId - ANIME_ID_OFFSET
        // Резолюция: карты пула, иначе точный поиск каталога (та же политика, что у пуша).
        // Честный «нет в каталоге» (сессионная мемоизация) — удалять нечего, успех;
        // немемоизированный промах (сеть) — не успех, вызывающая сторона честно ресинкнется.
        val anixartId = anixartIdToShiki.entries.firstOrNull { it.value == shikimoriId }?.key
            ?: resolveAnixartId(token, shikimoriId, titles)
            ?: return shikimoriId in anixartUnresolvable
        val knownLists = anixartReleaseLists[anixartId].orEmpty()
        // Свежий verify: profile_list_status + shikimori_id релиза. null — сеть/код,
        // успехом не считаем (как lookupFailed у Shikimori): ресинк вернёт правду.
        val info = withContext(Dispatchers.IO) { repo.releaseInfo(token, anixartId) }
            ?: return false
        if (info.shikimoriId != null && info.shikimoriId != shikimoriId) {
            KLog.w(
                "AnixartSync",
                "delete: shikimoriId=$shikimoriId release=$anixartId shikimori_id mismatch " +
                    "(${info.shikimoriId}), skip"
            )
            return true
        }
        val targetLists = knownLists +
            (info.profileListStatus.takeIf { it > 0 }?.let { setOf(it) } ?: emptySet())
        if (targetLists.isEmpty()) {
            KLog.d(
                "AnixartSync",
                "delete: shikimoriId=$shikimoriId release=$anixartId not in any list, nothing to delete"
            )
            val updated = anixartReleaseLists.toMutableMap()
            updated[anixartId] = emptySet()
            anixartReleaseLists = updated
            return true
        }
        var ok = true
        withContext(Dispatchers.IO) {
            targetLists.forEach { if (!repo.removeFromList(token, it, anixartId)) ok = false }
        }
        if (!ok) return false
        val updated = anixartReleaseLists.toMutableMap()
        updated[anixartId] = emptySet()
        anixartReleaseLists = updated
        KLog.i("AnixartSync", "delete: shikimoriId=$shikimoriId release=$anixartId removed from $targetLists")
        return true
    }

    /** Последняя точечная сверка карточки (kpId → монотонные мс): повороты и переоткрытия сеть не дёргают. */
    private val anixartDetailsCheckAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * Точечная сверка тайтла со списками Anixart при открытии карточки (1 GET release info):
     * паритет с refreshRateForDetails. Без меток времени у API — то же правило baseline,
     * что в пуле: локальное совпадает с последним известным сервером → серверное перенимаем
     * (adopt); локальное разошлось → побеждает локальное, пушим его. Вне списков (0) —
     * локальное не трогаем (как Shikimori-absent: удаляет только явное действие юзера).
     */
    fun refreshAnixartForDetails(kinopoiskId: Int) {
        if (kinopoiskId < ANIME_ID_OFFSET) return
        val auth = uiState.anixartAuthState
        val token = auth.token
        val repo = anixartRepository
        if (!auth.isLoggedIn || token == null || repo == null) return
        val now = System.nanoTime() / 1_000_000L
        if (now - (anixartDetailsCheckAt[kinopoiskId] ?: 0L) < DETAILS_RATE_CHECK_THROTTLE_MS) return
        anixartDetailsCheckAt[kinopoiskId] = now
        viewModelScope.launch {
            anixartSyncMutex.withLock {
                val shikimoriId = kinopoiskId - ANIME_ID_OFFSET
                val anixartId = anixartIdToShiki.entries.firstOrNull { it.value == shikimoriId }?.key
                    ?: return@withLock
                val knownBefore = anixartReleaseLists[anixartId].orEmpty()
                val freshList = withContext(Dispatchers.IO) { repo.releaseListStatus(token, anixartId) }
                    ?: return@withLock
                val updated = anixartReleaseLists.toMutableMap()
                updated[anixartId] = if (freshList > 0) setOf(freshList) else emptySet()
                anixartReleaseLists = updated
                val freshStatus = anixartListToStatus(freshList) ?: return@withLock
                val profile = withContext(Dispatchers.IO) { userStateStore.getProfile(kinopoiskId) }
                if (profile?.status == freshStatus) {
                    if (detailsState.item?.kinopoiskId == kinopoiskId) {
                        val p = withContext(Dispatchers.Default) { getUserProfileForFilm(kinopoiskId) }
                        detailsState = detailsState.copy(userProfile = p)
                    }
                    return@withLock
                }
                val localList = profile?.status?.toAnixartList()
                if (profile?.status == null || (localList != null && localList in knownBefore)) {
                    // Untouched — забираем серверное (adopt через setFeedQuickStatus: метка = сейчас).
                    withContext(Dispatchers.IO) {
                        userStateStore.setFeedQuickStatus(
                            kinopoiskId,
                            profile?.title ?: "Без названия",
                            profile?.posterUrl,
                            freshStatus
                        )
                    }
                    KLog.d(
                        "AnixartSync",
                        "details: shikimoriId=$shikimoriId adopted list $freshList " +
                            "(${profile?.status} -> $freshStatus)"
                    )
                    refreshLibraryAndAvatar()
                    if (detailsState.item?.kinopoiskId == kinopoiskId) {
                        val p = withContext(Dispatchers.Default) { getUserProfileForFilm(kinopoiskId) }
                        detailsState = detailsState.copy(userProfile = p)
                    }
                } else {
                    // Разошлось — побеждает локальное, пушим его.
                    KLog.d(
                        "AnixartSync",
                        "details: shikimoriId=$shikimoriId diverged " +
                            "local=${profile.status} server=$freshStatus, pushing local"
                    )
                    pushDirtyAnixartLists(token, onlyKpId = kinopoiskId)
                }
            }
        }
    }

    fun logoutShikimori() {
        shikimoriAuthStore?.clearSession()
        // Библиотека Shikimori остаётся на устройстве и после выхода: снапшот рейтов на диске не
        // чистим, in-memory список не гасим — buildLibraryItems продолжает показывать тайтлы.
        // Чужой список при входе в другой аккаунт гасится в refreshShikimoriAuth
        // (snapshotUserId != userId), поэтому хвосты от старого аккаунта не мигнут.
        refreshShikimoriAuth(caller = "logout")
    }

    private fun loadFilters() {
        viewModelScope.launch {
            runCatching { repository.filters() }
                .onSuccess { res ->
                    uiState = uiState.copy(
                        availableGenres = res.genres.orEmpty().filter { !it.genre.isNullOrBlank() },
                        availableCountries = res.countries.orEmpty().filter { !it.country.isNullOrBlank() }
                    )
                    // Жанровые карусели кино зависят от справочника: если Обзор уже загрузился
                    // без них — догружаем только жанры, а не всю ветку.
                    if (uiState.overviewFilmSections.none { it.id.startsWith("film_genre_") }) {
                        refillFilmGenres(res.genres.orEmpty().filter { !it.genre.isNullOrBlank() })
                    }
                }
        }
    }

    fun updateFilters(newFilters: SearchFilterState) {
        uiState = uiState.copy(filterState = newFilters, discoverTitle = null)
        submitSearch()
    }

    fun searchGenre(genreName: String, isAnime: Boolean, title: String? = null) {        if (isAnime) {
            userStateStore.setSavedContentType(ContentType.ANIME)
            val matchedGenre = shikimoriGenres.firstOrNull { it.genre.equals(genreName, ignoreCase = true) }
            // Жанра нет в статичном списке Shikimori (например, хентай-тег из каталога hanime) —
            // ищем его текстом, а не открываем неотфильтрованный каталог.
            uiState = uiState.copy(
                tab = HomeTab.CATALOG,
                contentType = ContentType.ANIME,
                query = if (matchedGenre == null) genreName else "",
                filterState = SearchFilterState(animeGenreId = matchedGenre?.id),
                discoverTitle = title ?: genreName
            )
        } else {
            userStateStore.setSavedContentType(ContentType.FILMS)
            val matchedGenre = uiState.availableGenres.firstOrNull { it.genre.equals(genreName, ignoreCase = true) }
            uiState = uiState.copy(
                tab = HomeTab.CATALOG,
                contentType = ContentType.FILMS,
                query = if (matchedGenre == null) genreName else "",
                filterState = SearchFilterState(selectedGenreId = matchedGenre?.id),
                discoverTitle = title ?: genreName
            )
        }
        submitSearch()
    }

    /**
     * Поиск аниме студии из ссылок /animes/studio/{id} в новостях:
     * каталог аниме с фильтром студии, заголовок — её название.
     */
    fun searchStudio(studioId: Int, studioName: String) {
        userStateStore.setSavedContentType(ContentType.ANIME)
        uiState = uiState.copy(
            tab = HomeTab.CATALOG,
            contentType = ContentType.ANIME,
            query = "",
            filterState = SearchFilterState(animeStudioId = studioId),
            discoverTitle = studioName
        )
        submitSearch()
    }

    fun setShowFilterSheet(show: Boolean) {
        uiState = uiState.copy(showFilterSheet = show)
    }

    fun onQueryChange(query: String) {
        uiState = uiState.copy(query = query)
    }

    fun submitSearch() {
        val query = uiState.query.trim()
        if (query.isBlank() && !uiState.filterState.isActive) {
            loadDiscoverFirstPage(uiState.discoverCategory)
            return
        }
        loadSearchFirstPage(query)
    }

    fun retryHome() {
        if (uiState.isSearchResult && uiState.query.trim().isNotBlank()) {
            loadSearchFirstPage(uiState.query.trim())
        } else {
            loadDiscoverFirstPage(uiState.discoverCategory)
        }
    }

    fun onContentTypeSelected(contentType: ContentType) {
        if (uiState.contentType == contentType) return
        userStateStore.setSavedContentType(contentType)
        uiState = uiState.copy(
            contentType = contentType,
            isSearchResult = false,
            query = "",
            filterState = SearchFilterState(),
            discoverCategory = DiscoverCategory.POPULAR,
            discoverTitle = null
        )
        loadDiscoverFirstPage(uiState.discoverCategory)
    }

    fun onDiscoverCategorySelected(category: DiscoverCategory) {
        if (uiState.discoverCategory == category && !uiState.isSearchResult && uiState.discoverTitle == null) return
        uiState = uiState.copy(
            discoverCategory = category,
            isSearchResult = false,
            query = "",
            discoverTitle = null
        )
        loadDiscoverFirstPage(category)
    }

    /**
     * Открытие категории из ленты Обзора (кнопка «Все»): в отличие от
     * [onDiscoverCategorySelected] всегда перезагружает и ставит заголовок
     * раздела — поэтому «Сейчас смотрят» (POPULAR) тоже открывается сеткой,
     * а не считается главной лентой.
     */
    private fun openDiscoverCategorySection(category: DiscoverCategory, title: String) {
        uiState = uiState.copy(
            discoverCategory = category,
            isSearchResult = false,
            isInstantSearch = false,
            query = "",
            discoverTitle = title
        )
        loadDiscoverFirstPage(category)
    }

    fun loadMore() {
        val snapshot = uiState
        if (snapshot.loading || snapshot.loadingMore || !snapshot.hasMore) return

        if (snapshot.isSearchResult || snapshot.filterState.isActive) {
            val query = snapshot.query.trim()
            loadSearchNextPage(query)
        } else {
            loadDiscoverNextPage(snapshot.discoverCategory)
        }
    }

    fun onTabSelected(tab: HomeTab) {
        uiState = uiState.copy(tab = tab)
    }

    fun removeFromHistory(kinopoiskId: Int) {
        userStateStore.removeFromHistory(kinopoiskId)
        refreshLibraryAndAvatar()
    }

    fun onWatch(details: FilmDetails) {
        // For anime, seed the stored profile with the rate-backed profile: otherwise pressing
        // "Watch" persists a statusless husk that shadows the Shikimori rate in the
        // «Прогресс просмотра» editor, and the husk's updatedAt bump floated the title to
        // the top of the «По дате добавления» sort. In-memory only — no extra prefs parsing.
        val seed = rateProfileForFilm(details.kinopoiskId)
        userStateStore.addFromDetails(details, seed)
        refreshLibraryAndAvatar()
    }

    fun saveUserProfile(
        details: FilmDetails,
        status: UserFilmStatus?,
        userRating: Int?,
        note: String,
        watchedSeasons: Int?,
        watchedEpisodes: Int?,
        totalEpisodesInSeason: Int?,
        totalSeasons: Int?,
        totalEpisodes: Int?,
        isRussianOverride: Boolean? = null
    ) {
        // Cleared status in the progress editor = explicit "remove from library": the title has
        // no status and no progress, so drop both the profile and its history entry instead of
        // persisting a statusless husk that would still surface in the История tab.
        if (status == null) {
            userStateStore.removeFromLibrary(details.kinopoiskId)
            // Shikimori rates are a library source in buildLibraryItems: an anime with a
            // server rate would resurrect on the rebuild right below. Drop the cached rate
            // now and delete the server one (token-refresh retry mirrors the update path).
            if (details.kinopoiskId >= ANIME_ID_OFFSET) {
                val shikimoriId = details.kinopoiskId - ANIME_ID_OFFSET
                // Элемент библиотеки строится по anime.id с фолбэком на targetId
                // (toLibraryUiItemWithCache), поэтому матчим рейт по обоим полям —
                // иначе stale-запись остаётся в кэше и тайтл висит в разделе.
                val rateId = cachedShikimoriRates.firstOrNull {
                    it.targetId == shikimoriId || it.anime?.id == shikimoriId
                }?.id
                cachedShikimoriRates = cachedShikimoriRates.filterNot {
                    it.targetId == shikimoriId || it.anime?.id == shikimoriId
                }
                val authState = uiState.shikimoriAuthState
                if (authState.isLoggedIn && authState.accessToken != null) {
                    viewModelScope.launch {
                        var token = authState.accessToken
                        // rateId может быть неизвестен (кэш ещё не загружен): резолвим точечно
                        // с сервера, иначе удаление молча считалось успехом и до Shikimori не доходило.
                        var targetRateId = rateId
                        var lookupFailed = false
                        if (targetRateId == null && authState.userId > 0) {
                            // Только резолвим id — кэш не трогаем: он уже оптимистично
                            // отфильтрован выше, а пересборка библиотеки уже летит.
                            // Любая запись сюда гонялась бы с ней и возвращала тайтл в раздел.
                            val lookup = animeRepository.getUserRateForTarget(authState.userId, shikimoriId)
                            lookupFailed = lookup.isFailure
                            targetRateId = lookup.getOrNull()?.id
                        }
                        // Серверный рейт не найден, а его поиск не падал: удалять нечего — успех.
                        // Поиск упал (сеть): успехом не считаем, иначе тайтл «воскреснет» при ресинке.
                        val rateIdToDelete = targetRateId
                        var success = if (rateIdToDelete != null) {
                            animeRepository.deleteUserRate(token, rateIdToDelete)
                        } else {
                            // Серверный рейт не найден, а его поиск не падал: удалять нечего — успех.
                            // Поиск упал (сеть): успехом не считаем, иначе тайтл «воскреснет» при ресинке.
                            !lookupFailed && !(rateId == null && authState.userId <= 0)
                        }
                        if (!success && rateIdToDelete != null && authState.refreshToken != null) {
                            animeRepository.refreshToken(authState.refreshToken)?.let { fresh ->
                                persistFreshShikimoriTokens(authState, fresh.accessToken, fresh.refreshToken)
                                token = fresh.accessToken
                                success = animeRepository.deleteUserRate(token, rateIdToDelete)
                            }
                        }
                        if (success) {
                            // Удаление на сервере подтверждено: фиксируем в кэше и в текущем
                            // списке раздела, затем подтверждаем полной пересборкой.
                            cachedShikimoriRates = cachedShikimoriRates.filterNot {
                                it.targetId == shikimoriId || it.anime?.id == shikimoriId
                            }
                            persistShikimoriRatesSnapshot(authState.userId, cachedShikimoriRates)
                            libraryBaseCache = libraryBaseCache?.filterNot { it.kinopoiskId == details.kinopoiskId }
                            uiState = uiState.copy(
                                library = uiState.library.filterNot { it.kinopoiskId == details.kinopoiskId }
                            )
                            if (targetRateId != null) {
                                KLog.d("ShikimoriSync", "Deleted rate id=$targetRateId for shikimoriId=$shikimoriId")
                            } else {
                                KLog.d("ShikimoriSync", "No server rate for shikimoriId=$shikimoriId, nothing to delete")
                            }
                            refreshLibraryAndAvatar()
                        } else {
                            // Сервер не удалил: молчаливый рассинхрон — причина «удалил, а оно
                            // вернулось». Перечитываем серверную правду, чтобы библиотека не врала.
                            KLog.e("ShikimoriSync", "Delete failed for rate id=$targetRateId, resyncing from server")
                            refreshShikimoriAuth(caller = "delete-retry")
                        }
                    }
                }
                // Anixart: удаление из списков (паритет с Shikimori выше) — иначе
                // следующий пул воскресит тайтл локально через reconcilePulledRelease.
                val anixartAuth = uiState.anixartAuthState
                if (anixartAuth.isLoggedIn && anixartAuth.token != null && anixartRepository != null) {
                    val anixartToken = anixartAuth.token
                    viewModelScope.launch {
                        val deleted = anixartSyncMutex.withLock {
                            deleteAnixartTitle(
                                anixartToken,
                                details.kinopoiskId,
                                listOf(details.nameRu, details.nameOriginal)
                            )
                        }
                        if (!deleted) {
                            // Сервер не удалил: та же честная политика, что у Shikimori, —
                            // перечитываем серверную правду, пусть тайтл вернётся, чем врёт.
                            KLog.e(
                                "AnixartSync",
                                "Delete failed for kinopoiskId=${details.kinopoiskId}, resyncing from server"
                            )
                            anixartSyncMutex.withLock {
                                syncAnixartLists(anixartToken, pushLocalNewer = true, caller = "delete-retry")
                            }
                        }
                    }
                }
            }
            // Мгновенный отклик раздела для любого типа тайтла: полная пересборка
            // парсит тяжёлые блобы (секунды) и едет асинхронно — без этого удаление
            // «не пропадает в реальном времени». Пересборка ниже это подтвердит.
            val removedKpId = details.kinopoiskId
            libraryBaseCache = libraryBaseCache?.filterNot { it.kinopoiskId == removedKpId }
            uiState = uiState.copy(library = uiState.library.filterNot { it.kinopoiskId == removedKpId })
            detailsState = detailsState.copy(userProfile = null, savingProfile = false)
            refreshLibraryAndAvatar()
            return
        }

        val safeRating = userRating?.coerceIn(1, 10)
        val safeSeasons = watchedSeasons?.coerceAtLeast(0)
        val safeEpisodes = watchedEpisodes?.coerceAtLeast(0)
        val safeTotalEpisodesInSeason = totalEpisodesInSeason?.coerceAtLeast(0)
        val safeTotalSeasons = totalSeasons?.coerceAtLeast(0)
        val safeTotalEpisodes = totalEpisodes?.coerceAtLeast(0)

        detailsState = detailsState.copy(savingProfile = true)
        val updated = userStateStore.updateProfileFromDetails(
            item = details,
            status = status,
            userRating = safeRating,
            note = note,
            watchedSeasons = safeSeasons,
            watchedEpisodes = safeEpisodes,
            totalEpisodesInSeason = safeTotalEpisodesInSeason,
            totalSeasons = safeTotalSeasons,
            totalEpisodes = safeTotalEpisodes,
            isRussianOverride = isRussianOverride
        )
        detailsState = detailsState.copy(
            userProfile = updated,
            savingProfile = false
        )
        refreshLibraryAndAvatar()

        // Sync with Shikimori if it's an anime (точечный пуш под мьютексом, LWW внутри).
        if (details.kinopoiskId >= ANIME_ID_OFFSET) {
            val shikiStatus = when (status) {
                UserFilmStatus.WATCHING -> "watching"
                UserFilmStatus.PLANNED -> "planned"
                UserFilmStatus.COMPLETED -> "completed"
                UserFilmStatus.REWATCHING -> "rewatching"
                UserFilmStatus.ON_HOLD -> "on_hold"
                UserFilmStatus.DROPPED -> "dropped"
                else -> null
            }
            // Для аниме watchedSeasons в шите — это «Повторы», у Shikimori это rewatches.
            val rewatches = safeSeasons?.takeIf { it > 0 }
            if (shikiStatus != null) {
                viewModelScope.launch {
                    shikimoriSyncMutex.withLock {
                        pushSingleAnimeRate(
                            kinopoiskId = details.kinopoiskId,
                            shikiStatus = shikiStatus,
                            episodes = safeEpisodes,
                            rating = safeRating,
                            rewatches = rewatches
                        )
                    }
                }
            }
        }

        // Anixart: точечный пуш статуса (по кэшу карт из последнего пула).
        if (details.kinopoiskId >= ANIME_ID_OFFSET && status != null) {
            pushAnixartTitle(details.kinopoiskId)
        }
    }

    fun setProfileAvatar(avatar: String) {
        userStateStore.setProfileAvatar(avatar)
        uiState = uiState.copy(profileAvatar = userStateStore.getProfileAvatar())
    }

    fun setThemeMode(mode: AppThemeMode) {
        userStateStore.setThemeMode(mode)
        uiState = uiState.copy(themeMode = mode)
    }

    fun setHideRussianContent(enabled: Boolean) {
        userStateStore.setHideRussianContentEnabled(enabled)
        uiState = uiState.copy(hideRussianContent = enabled)
    }

    fun setDiscoverTileSize(size: FilmTileSize) {
        userStateStore.setDiscoverTileSize(size)
        uiState = uiState.copy(discoverTileSize = size)
    }

    fun setLibraryTileSize(size: FilmTileSize) {
        userStateStore.setLibraryTileSize(size)
        uiState = uiState.copy(libraryTileSize = size)
    }

    fun setLibrarySortType(sortType: hd.kinoshka.app.data.local.LibrarySortType) {
        userStateStore.setLibrarySortType(sortType)
        uiState = uiState.copy(librarySortType = sortType, library = resortLibrary())
    }

    fun setLibrarySortReversed(reversed: Boolean) {
        userStateStore.setLibrarySortReversed(reversed)
        uiState = uiState.copy(librarySortReversed = reversed, library = resortLibrary())
    }

    /** Группировка не влияет на порядок внутри групп — пересортировка не нужна. */
    fun setLibraryGroupType(group: hd.kinoshka.app.data.local.LibraryGroupType) {
        userStateStore.setLibraryGroupType(group)
        uiState = uiState.copy(libraryGroupType = group)
    }

    private fun resortLibrary(): List<LibraryUiItem> =
        libraryBaseCache?.let(::applyLibrarySort) ?: buildLibraryItems()

    fun setHentaiVisibleInLibrary(visible: Boolean) {
        userStateStore.setHentaiVisibleInLibrary(visible)
        uiState = uiState.copy(showHentaiInLibrary = visible)
        viewModelScope.launch {
            val library = withContext(Dispatchers.Default) { buildLibraryItems() }
            uiState = uiState.copy(library = library)
        }
        ensureLibraryAdultVerdicts()
    }

    fun setShowFpsCounter(enabled: Boolean) {
        userStateStore.setFpsCounterEnabled(enabled)
        uiState = uiState.copy(showFpsCounter = enabled)
    }

    fun setPlayerMode(mode: hd.kinoshka.app.data.local.PlayerMode) {
        userStateStore.setPlayerMode(mode)
        uiState = uiState.copy(playerMode = mode)
    }

    fun exportLibraryJson(): String = userStateStore.exportLibraryJson()

    fun importLibraryJson(rawJson: String): Result<Unit> {
        return userStateStore.importLibraryJson(rawJson)
            .onSuccess {
                refreshFromStore()
            }
    }

    fun loadDetails(id: Int) {
        viewModelScope.launch {
            detailsState = DetailsUiState(loading = true)
            runCatching {
                if (id >= ANIME_ID_OFFSET) {
                    val shikimoriId = id - ANIME_ID_OFFSET
                    val animeDetails = try {
                        animeRepository.details(shikimoriId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Офлайн: открываем страницу из дискового кэша карточки — достаточно,
                        // чтобы нажать Смотреть и сыграть скачанные серии. Шики-блоки (кадры,
                        // персонажи, хронология) офлайн остаются пустыми.
                        val cached = userStateStore.getDetailsCache(id)
                        if (cached == null) throw e
                        detailsState = DetailsUiState(
                            item = cached,
                            userProfile = getUserProfileForFilm(id),
                            loading = false
                        )
                        return@launch
                    }
                    userStateStore.saveDetailsCache(id, animeDetails.toFilmDetails())
                    val baseState = DetailsUiState(
                        item = animeDetails.toFilmDetails(),
                        userProfile = getUserProfileForFilm(id),
                        animeDetails = animeDetails,
                        loading = false
                    )
                    detailsState = baseState
                    // Свежий прогресс с сервера (второй телефон): точечно, без полного синка.
                    refreshRateForDetails(id)
                    refreshAnixartForDetails(id)

                    if (isAdultAnime(animeDetails)) {
                        launch {
                            // Shikimori для 18+ отдаёт единственный жанр «хентай»; дополняем
                            // его настоящими тегами из каталога hanime (RU-словарь, фолбэк — слаг).
                            val tags = runCatching {
                                hd.kinoshka.app.data.source.HentaiStreamResolver.hentaiTags(
                                    animeDetails.name,
                                    animeDetails.russian
                                )
                            }.getOrDefault(emptyList())
                            if (tags.isNotEmpty()) {
                                detailsState.item?.let { current ->
                                    val merged = buildList {
                                        add(hd.kinoshka.app.data.model.NameOnly(genre = "Хентай"))
                                        addAll(current.genres.orEmpty().filterNot { it.genre?.equals("хентай", ignoreCase = true) == true })
                                        addAll(tags.map { hd.kinoshka.app.data.model.NameOnly(genre = it) })
                                    }.distinctBy { it.genre?.lowercase() }
                                    detailsState = detailsState.copy(item = current.copy(genres = merged))
                                }
                            }
                        }
                        // Превью-клип hanime1 на 18+-страницах отключён: токен hembed живёт
                        // недолго и к моменту нажатия Play часто уже протухал, а матчинг
                        // каталога периодически отдавал превью чужого тайтла. Технология
                        // (HentaiStreamResolver.hentaiTrailer + карточка в ImagesCard)
                        // сохранена для переиспользования на аниме/кино.
                        if (HENTAI_PREVIEW_ENABLED) {
                            launch {
                                val trailer = runCatching {
                                    hd.kinoshka.app.data.source.HentaiStreamResolver.hentaiTrailer(
                                        animeDetails.name,
                                        animeDetails.russian
                                    )
                                }.getOrNull()
                                if (trailer != null) {
                                    detailsState = detailsState.copy(
                                        trailer = FilmTrailer(
                                            url = trailer.previewUrl,
                                            nativeUrl = trailer.previewUrl,
                                            posterUrl = trailer.posterUrl,
                                            nativeHeaders = mapOf(
                                                "User-Agent" to hd.kinoshka.app.data.source.HentaiStreamResolver.HENTAI_USER_AGENT
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }

                    launch {
                        val screenshots = runCatching { animeRepository.screenshots(shikimoriId) }.getOrDefault(emptyList())
                        val imageItems = screenshots.map {
                            FilmImageItem(imageUrl = it.getFullOriginalUrl(), previewUrl = it.getFullPreviewUrl())
                        }
                        if (imageItems.isNotEmpty()) {
                            detailsState = detailsState.copy(images = imageItems)
                        } else {
                            // Shikimori хранит кадры только для обычных аниме; у 18+ тайтлов их
                            // нет и в Кинопоиске — берём превью со страницы hanime1 (каталог
                            // хентая уже замаплен на неё), фолбэк — обложка из каталога.
                            val hentaiFrames = runCatching {
                                hd.kinoshka.app.data.source.HentaiStreamResolver.hentaiFrames(
                                    animeDetails.name,
                                    animeDetails.russian
                                )
                            }.getOrDefault(emptyList())
                            detailsState = detailsState.copy(images = hentaiFrames)
                        }
                    }
                    launch {
                        val trailer = loadShikimoriTrailer(shikimoriId)
                        if (trailer != null) detailsState = detailsState.copy(trailer = trailer)
                    }
                    launch {
                        val relatedList = runCatching { animeRepository.related(shikimoriId) }.getOrDefault(emptyList())
                        val relationItems = relatedList.mapNotNull { rel ->
                            val a = rel.anime ?: return@mapNotNull null
                            val yearInt = a.airedOn?.take(4)?.toIntOrNull()
                            val kindStr = when (a.kind?.lowercase()) {
                                "tv" -> "ТВ"
                                "movie" -> "Фильм"
                                "ova" -> "OVA"
                                "ona" -> "ONA"
                                "special", "tv_special" -> "Спешл"
                                "music" -> "Музыка"
                                else -> a.kind?.uppercase()
                            }
                            FilmLinkItem(
                                filmId = a.id + ANIME_ID_OFFSET,
                                kinopoiskId = a.id + ANIME_ID_OFFSET,
                                nameRu = a.russian?.takeIf { it.isNotBlank() } ?: a.name,
                                nameOriginal = a.name,
                                posterUrl = a.image?.getFullOriginalUrl(a.id) ?: "https://smarthard.net/static/animes/${a.id}.jpeg",
                                posterUrlPreview = a.image?.getFullPreviewUrl(a.id) ?: "https://smarthard.net/static/animes/${a.id}.jpeg",
                                relationType = rel.relationRussian ?: rel.relation,
                                year = yearInt,
                                type = kindStr
                            )
                        }
                        // Shikimori может вернуть одно и то же аниме под двумя relation-связями;
                        // все они мапятся в один id (a.id + ANIME_ID_OFFSET), а HorizontalFilmsCard
                        // использует key = { it.id } -> краш на дубликате ключа.
                        detailsState = detailsState.copy(
                            relations = relationItems.filter { it.id > 0 }.distinctBy { it.id }
                        )
                    }
                    launch {
                        val rolesList = runCatching { animeRepository.roles(shikimoriId) }.getOrDefault(emptyList())
                        val validCharacters = rolesList
                            // Локальная копия: character объявлен в другом модуле (shared), smart cast невозможен.
                            .filter { it.character?.name?.isNotBlank() == true }
                            .distinctBy { it.character?.id }
                        detailsState = detailsState.copy(animeCharacters = validCharacters)
                    }
                    launch {
                        val franchiseData = runCatching { animeRepository.franchise(shikimoriId) }.getOrNull()
                        val fullChronologyItems = franchiseData?.nodes?.mapNotNull { node ->
                            val kindStr = when (node.kind?.lowercase()) {
                                "tv" -> "ТВ"
                                "movie" -> "Фильм"
                                "ova" -> "OVA"
                                "ona" -> "ONA"
                                "special", "tv_special" -> "Спешл"
                                "music" -> "Музыка"
                                else -> node.kind?.uppercase()
                            }
                            FilmLinkItem(
                                filmId = node.id + ANIME_ID_OFFSET,
                                kinopoiskId = node.id + ANIME_ID_OFFSET,
                                nameRu = node.name?.takeIf { it.isNotBlank() && it != "" } ?: "Аниме #${node.id}",
                                nameOriginal = node.name,
                                posterUrl = node.imageUrl ?: "https://smarthard.net/static/animes/${node.id}.jpeg",
                                posterUrlPreview = node.imageUrl ?: "https://smarthard.net/static/animes/${node.id}.jpeg",
                                relationType = node.kind,
                                year = node.year,
                                type = kindStr
                            )
                        }?.sortedWith(compareBy<FilmLinkItem> { it.year ?: 9999 }.thenBy { it.id }) ?: emptyList()
                        detailsState = detailsState.copy(franchiseResponse = franchiseData, fullChronology = fullChronologyItems)
                    }
                } else {
                    val details = try {
                        repository.details(id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Офлайн-фолбэк: кэш карточки даёт кнопку Смотреть и офлайн-плей.
                        val cached = userStateStore.getDetailsCache(id)
                        if (cached == null) throw e
                        detailsState = DetailsUiState(
                            item = cached,
                            userProfile = getUserProfileForFilm(id),
                            loading = false
                        )
                        return@launch
                    }
                    userStateStore.saveDetailsCache(id, details)
                    // Страницы аниме открываются только через Shikimori (id >= ANIME_ID_OFFSET).
                    // Списки (поиск, подборки) уже отфильтрованы в FilmsRepository — это барьер
                    // для остальных путей до Kinopoisk-аниме: история, «похожие», лента.
                    if (details.genres.containsAnimeGenre()) {
                        val title = details.nameRu ?: details.nameOriginal ?: details.nameEn ?: "Этот тайтл"
                        detailsState = DetailsUiState(
                            error = "«$title» — аниме. Аниме открываются только через Shikimori — найдите его в разделе «Аниме».",
                            animeBlocked = true
                        )
                    } else {
                        detailsState = DetailsUiState(
                            item = details,
                            userProfile = getUserProfileForFilm(id),
                            loading = false
                        )

                        launch {
                            if (details.type == "TV_SERIES") {
                                val seasons = runCatching { repository.seasons(id) }.getOrDefault(emptyList())
                                detailsState = detailsState.copy(seasons = seasons)
                            }
                        }
                        launch {
                            val relations = runCatching { repository.relations(id) }.getOrDefault(emptyList())
                                .filter { it.id > 0 }
                                .distinctBy { it.id }
                            detailsState = detailsState.copy(relations = relations)
                        }
                        launch {
                            val images = runCatching { repository.images(id = id, page = 1) }.getOrDefault(emptyList())
                                .filter { !it.previewUrl.isNullOrBlank() || !it.imageUrl.isNullOrBlank() }
                            detailsState = detailsState.copy(images = images)
                        }
                        launch {
                            val trailer = loadKinopoiskTrailer(id)
                            if (trailer != null) detailsState = detailsState.copy(trailer = trailer)
                        }
                    }
                }
            }.onFailure { ex ->
                detailsState = DetailsUiState(error = ex.toUiMessage())
            }
        }
    }

    /** Элемент блока трейлеров (KP /videos или Shikimori «Видео») до выбора лучшей площадки. */
    private data class TrailerCandidate(
        val url: String,
        val posterUrl: String?,
        val title: String?,
        val official: Boolean
    )

    /**
     * Трейлер для KP-страниц (фильмы/сериалы/мультфильмы): берём из блока /videos.
     * Площадку выбирает pickTrailer: виджет КП или Rutube (HLS сразу) либо
     * YouTube (извлечение при нажатии).
     */
    private suspend fun loadKinopoiskTrailer(id: Int): FilmTrailer? = withContext(Dispatchers.IO) {
        val videos = runCatching { repository.videos(id) }.getOrDefault(emptyList())
        pickTrailer(
            videos.mapNotNull { v ->
                val url = v.url?.trim()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                TrailerCandidate(url, posterUrl = null, title = v.name, official = v.official == true)
            }
        )
    }

    /**
     * Комментарии новостного поста для ленты (Shikimori /api/comments).
     * Вызывает экран по раскрытию раздела, результат кэширует вызывающая сторона.
     */
    suspend fun loadTopicComments(topicId: Int): List<hd.kinoshka.app.data.model.ShikimoriComment> =
        withContext(Dispatchers.IO) {
            runCatching { animeRepository.topicComments(topicId) }.getOrDefault(emptyList())
        }

    /**
     * Трейлер для аниме: блок «Видео» Shikimori. Площадку выбирает pickTrailer:
     * Rutube (HLS сразу) или YouTube (извлечение при нажатии); vk/sibnet не подходят.
     */
    private suspend fun loadShikimoriTrailer(shikimoriId: Int): FilmTrailer? = withContext(Dispatchers.IO) {
        val videos = runCatching { animeRepository.videos(shikimoriId) }.getOrDefault(emptyList())
        pickTrailer(
            videos.mapNotNull { v ->
                val url = normalizeHttpUrl(v.playerUrl ?: v.url) ?: return@mapNotNull null
                // Shikimori отдаёт image_url в вида http://… или //… — приводим к https,
                // иначе Coil не грузит превью трейлера.
                TrailerCandidate(url, posterUrl = normalizeHttpUrl(v.imageUrl), title = v.name, official = false)
            }
        )
    }

    /** Строка → абсолютный https-URL ("//host", "http://host"). null — не похоже на URL. */
    private fun normalizeHttpUrl(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        return when {
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("http://") -> "https://${trimmed.removePrefix("http://")}"
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> null
        }
    }

    /**
     * Выбор трейлера из блоков Кинопоиска / Shikimori. Играет только mpvEx, поэтому
     * берём кандидатов с прямым потоком:
     *  — Rutube: HLS резолвится сразу через RutubeClipSource (как клипы фида) — без VPN;
     *  — виджет Кинопоиска (site=KINOPOISK_WIDGET, основная масса трейлеров в /videos):
     *    HLS из страницы виджета через KinopoiskTrailerResolver — без VPN;
     *  — YouTube: поток извлекается при нажатии через InnerTube, но площадка недоступна
     *    из РФ без VPN — карточка помечается бейджем (needsVpn).
     * Официальные — вперёд; для каждого кандидата резолв неудался — идём к следующему.
     */
    private suspend fun pickTrailer(candidates: List<TrailerCandidate>): FilmTrailer? {
        val ranked = candidates
            .sortedWith(compareBy { !it.official })
            .distinctBy { it.url }
        var youtube: TrailerCandidate? = null
        // Капы на резолвы: у KP почти все кандидаты — виджет-ссылки; если механизм
        // не работает, не молотим всю строку подряд (каждый резолв — сетевой запрос).
        var rutubeTries = 0
        var widgetTries = 0
        for (candidate in ranked) {
            val rutubeId = hd.kinoshka.app.data.feed.RutubeClipSource.videoIdFromUrl(candidate.url)
            if (rutubeId != null) {
                if (rutubeTries < 2) {
                    rutubeTries++
                    val clip = hd.kinoshka.app.data.feed.RutubeClipSource.resolveClip(candidate.url)
                    if (clip != null) {
                        return FilmTrailer(
                            url = candidate.url,
                            nativeUrl = clip.hlsUrl,
                            posterUrl = candidate.posterUrl ?: clip.thumbnailUrl,
                            title = candidate.title
                        )
                    }
                }
                continue
            }
            if (hd.kinoshka.app.data.source.KinopoiskTrailerResolver.trailerIdFromUrl(candidate.url) != null) {
                if (widgetTries < 2) {
                    widgetTries++
                    val widget = hd.kinoshka.app.data.source.KinopoiskTrailerResolver.resolve(candidate.url)
                    if (widget != null) {
                        return FilmTrailer(
                            url = candidate.url,
                            nativeUrl = widget.hlsUrl,
                            posterUrl = candidate.posterUrl ?: widget.posterUrl,
                            title = candidate.title
                        )
                    }
                }
                continue
            }
            if (youtube == null && youTubeVideoId(candidate.url) != null) youtube = candidate
        }
        val candidate = youtube ?: return null
        val videoId = youTubeVideoId(candidate.url).orEmpty()
        return FilmTrailer(
            url = candidate.url,
            posterUrl = candidate.posterUrl ?: youTubeThumbUrl(videoId),
            title = candidate.title,
            needsVpn = true
        )
    }

    /** videoId из любых форматов YouTube-ссылок (watch?v=, youtu.be/, embed/, shorts/, live/). */
    private fun youTubeVideoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|embed/|shorts/|live/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    /** Обложка YouTube-ролика, когда площадка не отдала свою. */
    private fun youTubeThumbUrl(videoId: String): String =
        "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

    /**
     * Adult-детект для ветки загрузки деталей: рейтинг Shikimori, жанр или совпадение
     * с каталогом hanime. Синхронный по кэшу каталога — false, пока каталог не загружен.
     */
    private fun isAdultAnime(details: hd.kinoshka.app.data.model.ShikimoriAnimeDetails): Boolean {
        val rating = details.rating?.lowercase().orEmpty()
        if (rating.contains("18") || rating.startsWith("rx") || rating == "x" || rating.contains("nc17")) return true
        val hasAdultGenre = details.genres.orEmpty().any { g ->
            val n = (g.russian ?: g.name).lowercase()
            n.contains("хентай") || n.contains("hentai") || n.contains("эротик") || n.contains("ecchi")
        }
        return hasAdultGenre ||
            hd.kinoshka.app.data.source.HentaiStreamResolver.isKnownHentai(details.name, details.russian)
    }

    /**
     * 18+-вердикт по краткому объекту батча (без полных details): жанры + каталог hanime.
     * Сигнал рейтинга rx тут недоступен — непокрытое добивается поштучным details.
     */
    private fun isAdultBrief(item: hd.kinoshka.app.data.model.ShikimoriAnimeItem): Boolean {
        val hasAdultGenre = item.genres.orEmpty().any { g ->
            val n = (g.russian ?: g.name).lowercase()
            n.contains("хентай") || n.contains("hentai") || n.contains("эротик") || n.contains("ecchi")
        }
        return hasAdultGenre ||
            hd.kinoshka.app.data.source.HentaiStreamResolver.isKnownHentai(item.name, item.russian)
    }

    /** Кэш-запись из краткого объекта батча; отсутствующие поля добираются из прошлой записи. */
    private fun briefToCache(
        shikimoriId: Int,
        item: hd.kinoshka.app.data.model.ShikimoriAnimeItem,
        prev: hd.kinoshka.app.data.local.ShikimoriAnimeCache?
    ): hd.kinoshka.app.data.local.ShikimoriAnimeCache {
        return hd.kinoshka.app.data.local.ShikimoriAnimeCache(
            shikimoriId = shikimoriId,
            name = item.name ?: prev?.name,
            russian = item.russian ?: prev?.russian,
            posterUrl = item.posterUrl,
            episodes = item.episodes ?: prev?.episodes,
            episodesAired = item.episodesAired ?: prev?.episodesAired,
            kind = item.kind ?: prev?.kind,
            score = item.score ?: prev?.score,
            status = item.status ?: prev?.status,
            year = item.airedOn?.take(4)?.toIntOrNull() ?: prev?.year,
            // Краткий объект жанров не несёт: его false без жанрового сигнала
            // недостоверен и не смеет затирать уже установленный true.
            isAdult = if (prev?.isAdult == true) true else isAdultBrief(item),
            genreChecked = prev?.genreChecked ?: false
        )
    }

    /**
     * Жанровая разметка 18+ батчами ids+genre ([ADULT_GENRE_IDS]). Краткий объект
     * жанров не несёт, поэтому его isAdult=false без этого этапа недостоверен
     * (хентай вне каталога hanime кэшировался «чистым» и показывался при выключенном
     * тумблере). Сервер режет ids-выборку жанром: вернувшийся id входит в жанр —
     * по 1 запросу на 50 id и жанр. Проверяем всё без [ShikimoriAnimeCache.genreChecked]
     * (включая старые false — у них флаг дефолтный): объём ограничен кэпом 500,
     * повторные прогоны видят флаг и пропускают. Возвращает число обновлённых записей.
     */
    private suspend fun markAdultByGenre(libraryKpIds: Set<Int>): Int {
        val offset = ANIME_ID_OFFSET
        val cache = userStateStore.getShikimoriAnimeCache()
        val unchecked = libraryKpIds.map { it - offset }.distinct()
            .filter { (cache[it]?.genreChecked ?: false) != true }
            .take(500)
        if (unchecked.isEmpty()) return 0
        val adultIds = mutableSetOf<Int>()
        var allOk = true
        for (genreId in ADULT_GENRE_IDS) {
            var first = true
            for (chunk in unchecked.chunked(50)) {
                if (!first) kotlinx.coroutines.delay(300L)
                first = false
                val got = runCatching { animeRepository.animesByIds(chunk, genreId) }.getOrNull()
                if (got == null) {
                    allOk = false
                    continue
                }
                for (item in got) if (item.id > 0) adultIds.add(item.id)
            }
            KLog.d(
                "ShikimoriSync",
                "adult-genre: genre=$genreId checked ${unchecked.size} adultHits=${adultIds.size}"
            )
        }
        val fresh = userStateStore.getShikimoriAnimeCache()
        // Одна запись кэша на всю разметку (см. батчи выше).
        val genreEntries = unchecked.mapNotNull { id ->
            val prev = fresh[id]
            if (prev == null && id !in adultIds) return@mapNotNull null
            val adult = id in adultIds || prev?.isAdult == true
            if (prev != null && prev.isAdult == adult && prev.genreChecked == allOk) return@mapNotNull null
            val base = prev ?: hd.kinoshka.app.data.local.ShikimoriAnimeCache(
                shikimoriId = id,
                name = null,
                russian = null,
                posterUrl = null,
                episodes = null,
                episodesAired = null,
                kind = null,
                score = null,
                status = null
            )
            base.copy(isAdult = adult, genreChecked = allOk)
        }
        userStateStore.saveShikimoriAnimeInfos(genreEntries)
        if (genreEntries.isNotEmpty()) {
            KLog.i("ShikimoriSync", "adult-genre: updated ${genreEntries.size} (allOk=$allOk)")
        }
        return genreEntries.size
    }

    /**
     * Батч кратких объектов: 1 запрос на 50 id вместо 50 поштучных details.
     * Возвращает найденное по id; несовпадение asked/got (порезка ids, цензура) видно
     * в логе — непокрытое вызывающая сторона добирает поштучно через prefetchDetails.
     */
    private suspend fun fetchAnimeBrief(ids: List<Int>): Map<Int, hd.kinoshka.app.data.model.ShikimoriAnimeItem> {
        val out = mutableMapOf<Int, hd.kinoshka.app.data.model.ShikimoriAnimeItem>()
        var first = true
        for (chunk in ids.distinct().take(500).chunked(50)) {
            if (!first) kotlinx.coroutines.delay(300L)
            first = false
            val list = runCatching { animeRepository.animesByIds(chunk) }.getOrNull().orEmpty()
            for (item in list) if (item.id > 0) out[item.id] = item
            KLog.d("ShikimoriSync", "brief: asked ${chunk.size} got ${list.size}")
        }
        return out
    }

    /**
     * Keyed-списки (HomeScreen: key = { _, film -> film.kinopoiskId }) падают, если один
     * kinopoiskId встречается дважды. Страницы API это иногда допускают, а rankResults только
     * сортирует и такой дубликат не убирает — поэтому дедуп нужен на каждом присвоении items.
     */
    private fun List<FilmItem>.dedupe(): List<FilmItem> = distinctBy { it.kinopoiskId }

    private fun currentYear(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    /**
     * Ключ сезона Shikimori (fall_2026) для даты: зима = 01–03, весна = 04–06,
     * лето = 07–09, осень = 10–12. [backSeasons] — на сколько сезонов назад.
     */
    private fun seasonKey(backSeasons: Int = 0): String {
        val cal = java.util.Calendar.getInstance()
        var quarter = when (cal.get(java.util.Calendar.MONTH)) {
            in 0..2 -> 0
            in 3..5 -> 1
            in 6..8 -> 2
            else -> 3
        }
        var year = cal.get(java.util.Calendar.YEAR)
        repeat(backSeasons) {
            quarter--
            if (quarter < 0) {
                quarter = 3
                year--
            }
        }
        val name = when (quarter) {
            0 -> "winter"
            1 -> "spring"
            2 -> "summer"
            else -> "fall"
        }
        return "${name}_$year"
    }

    private suspend fun fetchAnime(query: String?, page: Int): List<FilmItem> {
        val filters = uiState.filterState
        return animeRepository.search(
            query = query?.ifEmpty { null },
            kind = filters.animeKind,
            status = filters.animeStatus,
            rating = filters.animeRating,
            genreId = filters.animeGenreId,
            studioId = filters.animeStudioId,
            order = filters.animeOrder,
            scoreFrom = filters.animeScoreFrom,
            season = filters.animeSeason,
            page = page
        ).map { it.toFilmItem() }.dedupe()
    }

    private fun loadDiscoverFirstPage(category: DiscoverCategory) {
        // Cancel any in-flight search so a slow older request can't clobber the discover feed
        // (mirrors loadSearchFirstPage; matters when Back dismisses a search mid-flight).
        searchJob?.cancel()
        viewModelScope.launch {
            uiState = uiState.copy(
                loading = true,
                loadingMore = false,
                error = null,
                isSearchResult = false,
                currentPage = 1,
                hasMore = true
            )
            runCatching {
                if (uiState.contentType == ContentType.ANIME) {
                    fetchAnime(null, 1)
                } else {
                    repository.popular(
                        collectionType = category.apiType,
                        page = 1
                    )
                }
            }
                .onSuccess { items ->
                    uiState = uiState.copy(
                        loading = false,
                        items = items.dedupe(),
                        isSearchResult = false,
                        currentPage = 1,
                        // hasMore считается по СЫРОЙ странице сервера — это верный признак пагинации.
                        hasMore = items.isNotEmpty()
                    )
                }
                .onFailure { ex ->
                    uiState = uiState.copy(
                        loading = false,
                        error = ex.toUiMessage(),
                        isSearchResult = false
                    )
                }
        }
    }

    private fun loadDiscoverNextPage(category: DiscoverCategory) {
        viewModelScope.launch {
            val nextPage = uiState.currentPage + 1
            uiState = uiState.copy(loadingMore = true, error = null)
            runCatching {
                if (uiState.contentType == ContentType.ANIME) {
                    fetchAnime(null, nextPage)
                } else {
                    repository.popular(
                        collectionType = category.apiType,
                        page = nextPage
                    )
                }
            }
                .onSuccess { nextItems ->
                    val merged = (uiState.items + nextItems).distinctBy { it.kinopoiskId }
                    uiState = uiState.copy(
                        loadingMore = false,
                        items = merged,
                        currentPage = if (nextItems.isEmpty()) uiState.currentPage else nextPage,
                        hasMore = nextItems.isNotEmpty()
                    )
                }
                .onFailure { ex ->
                    uiState = uiState.copy(
                        loadingMore = false,
                        error = ex.toUiMessage()
                    )
                }
        }
    }

    private fun loadSearchFirstPage(query: String, instant: Boolean = false) {
        // Cancel any in-flight search so a slow older request can't clobber newer results
        // (the race that surfaces most with instant/debounced typing).
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            uiState = uiState.copy(
                loading = true,
                loadingMore = false,
                error = null,
                isSearchResult = true,
                isInstantSearch = instant,
                currentPage = 1,
                hasMore = true
            )
            val filters = uiState.filterState
            val cleanQuery = query.trim()
            runCatching {
                if (uiState.contentType == ContentType.ANIME) {
                    fetchAnime(cleanQuery, 1)
                } else {
                    repository.search(
                        query = cleanQuery.ifEmpty { null },
                        countryId = filters.selectedCountryId,
                        genreId = filters.selectedGenreId,
                        order = filters.selectedOrder,
                        type = filters.selectedType,
                        ratingFrom = filters.ratingFrom,
                        ratingTo = filters.ratingTo,
                        yearFrom = filters.yearFrom,
                        yearTo = filters.yearTo,
                        page = 1
                    )
                }
            }.onSuccess { items ->
                if (items.isEmpty() && cleanQuery.isNotBlank()) {
                    val fixedQuery = SearchQueryUtils.fixKeyboardLayout(cleanQuery)
                    if (fixedQuery != cleanQuery) {
                        val fallbackItems = runCatching {
                            if (uiState.contentType == ContentType.ANIME) {
                                fetchAnime(fixedQuery, 1)
                            } else {
                                repository.search(
                                    query = fixedQuery,
                                    countryId = filters.selectedCountryId,
                                    genreId = filters.selectedGenreId,
                                    order = filters.selectedOrder,
                                    type = filters.selectedType,
                                    ratingFrom = filters.ratingFrom,
                                    ratingTo = filters.ratingTo,
                                    yearFrom = filters.yearFrom,
                                    yearTo = filters.yearTo,
                                    page = 1
                                )
                            }
                        }.getOrDefault(emptyList())
                        if (fallbackItems.isNotEmpty()) {
                            uiState = uiState.copy(
                                loading = false,
                                items = rankResults(fallbackItems, cleanQuery).dedupe(),
                                isSearchResult = true,
                                isInstantSearch = instant,
                                currentPage = 1,
                                // hasMore — по сырой странице сервера, а не по дедуплицированной.
                                hasMore = fallbackItems.isNotEmpty()
                            )
                            return@launch
                        }
                    }
                }
                uiState = uiState.copy(
                    loading = false,
                    items = rankResults(items, cleanQuery).dedupe(),
                    isSearchResult = true,
                    isInstantSearch = instant,
                    currentPage = 1,
                    // hasMore — по сырой странице сервера, а не по дедуплицированной.
                    hasMore = items.isNotEmpty()
                )
                // Persist non-blank successful searches to history (only for explicit submits,
                // not every instant keystroke — instant calls go through onSearchQueryChanged).
                if (!instant && cleanQuery.isNotBlank() && items.isNotEmpty()) {
                    addSearchQueryToHistory(cleanQuery)
                }
            }.onFailure { ex ->
                uiState = uiState.copy(
                    loading = false,
                    error = ex.toUiMessage(),
                    isSearchResult = true,
                    isInstantSearch = instant
                )
            }
        }
    }

    /**
     * Client-side relevance ranking applied on top of the server order. Only re-ranks when the
     * user has NOT chosen an explicit order via filters (so a deliberate sort is respected).
     * Boosts exact/prefix/contains title matches above raw rating order, tie-broken by rating
     * then year — so the right series surfaces first instead of "as the API returned it".
     */
    private fun rankResults(items: List<FilmItem>, query: String): List<FilmItem> {
        val q = query.trim()
        if (q.isBlank() || uiState.filterState.isActive) return items
        return items.sortedByDescending {
            SearchQueryUtils.relevanceScore(q, it.nameRu, it.nameOriginal, it.ratingKinopoisk, it.year)
        }
    }

    private fun loadSearchNextPage(query: String) {
        viewModelScope.launch {
            val nextPage = uiState.currentPage + 1
            uiState = uiState.copy(loadingMore = true, error = null)
            val filters = uiState.filterState
            val cleanQuery = query.trim()
            runCatching {
                if (uiState.contentType == ContentType.ANIME) {
                    fetchAnime(cleanQuery, nextPage)
                } else {
                    repository.search(
                        query = cleanQuery.ifEmpty { null },
                        countryId = filters.selectedCountryId,
                        genreId = filters.selectedGenreId,
                        order = filters.selectedOrder,
                        type = filters.selectedType,
                        ratingFrom = filters.ratingFrom,
                        ratingTo = filters.ratingTo,
                        yearFrom = filters.yearFrom,
                        yearTo = filters.yearTo,
                        page = nextPage
                    )
                }
            }.onSuccess { nextItems ->
                val merged = (uiState.items + nextItems).distinctBy { it.kinopoiskId }
                uiState = uiState.copy(
                    loadingMore = false,
                    items = merged,
                    currentPage = if (nextItems.isEmpty()) uiState.currentPage else nextPage,
                    hasMore = nextItems.isNotEmpty()
                )
            }.onFailure { ex ->
                uiState = uiState.copy(
                    loadingMore = false,
                    error = ex.toUiMessage()
                )
            }
        }
    }

    private fun buildInitialState(): HomeUiState {
        val preferences = userStateStore.getUserPreferences()
        val fallbackTileSize = preferences.tileSize
        // Дисковый кэш Обзора: первый кадр сразу с контентом, без скелетона; сеть освежит фоном.
        val cachedFilms = userStateStore.getOverviewCache("films")
        val cachedAnime = userStateStore.getOverviewCache("anime")
        return HomeUiState(
            loading = true,
            overviewFilmSections = cachedFilms.sections,
            overviewAnimeSections = cachedAnime.sections,
            overviewFilmHero = cachedFilms.hero,
            overviewAnimeHero = cachedAnime.hero,
            library = buildLibraryItems(),
            profileAvatar = userStateStore.getProfileAvatar(),
            themeMode = preferences.themeMode,
            hideRussianContent = preferences.hideRussianContent,
            discoverTileSize = preferences.discoverTileSize ?: fallbackTileSize,
            libraryTileSize = preferences.libraryTileSize ?: fallbackTileSize,
            showFpsCounter = preferences.showFpsCounter,
            showHentaiInLibrary = userStateStore.isHentaiVisibleInLibrary(),
            librarySortReversed = userStateStore.isLibrarySortReversed(),
            librarySortType = userStateStore.getLibrarySortType(),
            libraryGroupType = userStateStore.getLibraryGroupType(),
            contentType = preferences.contentType,
            playerMode = preferences.playerMode
            // calendarItems is intentionally left default-empty: uiState is being constructed for
            // the first time here, and buildLibraryItems reads from cachedShikimoriCalendar (set
            // by loadCalendar) instead, so there is no read-during-init cycle.
        )
    }

    private fun refreshLibraryAndAvatar() {
        // Тот же тяжёлый buildLibraryItems, что и в refreshAfterPlayerClosed — тоже вне main.
        viewModelScope.launch {
            val library = withContext(Dispatchers.Default) { buildLibraryItems() }
            uiState = uiState.copy(
                library = library,
                profileAvatar = userStateStore.getProfileAvatar()
            )
        }
        // Единая воронка мутаций библиотеки → облачная выгрузка (дебаунс у получателя).
        // Чистые рефреши без изменений тоже сюда доходят — лишние вызовы гасит дебаунс.
        onLibraryMutated()
    }

    /**
     * Полная перечитка после восстановления библиотеки из облачного бэкапа (или установки поверх
     * старой): импорт перезаписывает хранилище целиком мимо ViewModel, включая снапшот рейтингов
     * Shikimori. Сбрасываем флаг гидратации — пересборка перечитает снапшот; при логине
     * refreshShikimoriAuth параллельно перезапросит рейты с сервера и сверит профили.
     */
    fun refreshAfterRestore() {
        shikimoriRatesSnapshotHydrated = false
        refreshShikimoriAuth(caller = "restore")
        viewModelScope.launch {
            val library = withContext(Dispatchers.Default) { buildLibraryItems() }
            val avatar = withContext(Dispatchers.Default) { userStateStore.getProfileAvatar() }
            uiState = uiState.copy(library = library, profileAvatar = avatar)
            // Импорт из облака привозит оболочки без кэша деталей (тот же формат,
            // что пул Anixart): добиваем из Shikimori, иначе плитки висят
            // «Фильмом» без серий/рейтинга до следующего синка.
            ensureLibraryAdultVerdicts()
            ensureLibraryAnimeMeta()
        }
    }

    /**
     * Pull-to-refresh Библиотеки (явный жест): локальная пересборка без троттла
     * возврата + обычные фоновые синки Shikimori/Anixart с их троттлами (лимиты
     * API штормом свайпов пробивать нельзя). Индикатор гаснет по концу локального
     * обновления; серверные пулы подтянут раздел сами, когда данные приедут.
     */
    fun refreshLibrary() {
        if (uiState.libraryRefreshing) return
        uiState = uiState.copy(libraryRefreshing = true)
        refreshAfterPlayerClosed(
            forced = true,
            onDone = { uiState = uiState.copy(libraryRefreshing = false) }
        )
        syncShikimoriOnForeground()
        syncAnixartOnForeground()
    }

    /**
     * Re-reads the persisted state after returning from an external screen (the native player
     * writes progress straight to SharedPreferences from its own Activity, bypassing this
     * ViewModel). Without this the library folders, progress bars and the details header showed
     * stale values until the app was restarted.
     *
     * @param forced явный жест обновления: троттл возврата пропускаем.
     * @param onDone разовый колбэк конца локального обновления (гашение индикатора).
     */
    fun refreshAfterPlayerClosed(forced: Boolean = false, onDone: (() -> Unit)? = null) {
        val now = System.nanoTime() / 1_000_000L
        if (!forced && now - lastResumeRefreshMs < RESUME_REFRESH_THROTTLE_MS) return
        lastResumeRefreshMs = now

        viewModelScope.launch {
            // Полная пересборка библиотеки парсит большие JSON-блобы (десятки МБ мусора, live-лог:
            // Davey 2.5s при ON_RESUME). На main-потоке она замораживала возврат из плеера и давала
            // чёрный экран при включении дисплея. Строим список вне main, применяем готовый.
            val library = withContext(Dispatchers.Default) { buildLibraryItems() }
            val avatar = withContext(Dispatchers.Default) { userStateStore.getProfileAvatar() }
            uiState = uiState.copy(library = library, profileAvatar = avatar)

            val item = detailsState.item
            if (!detailsState.loading && item != null) {
                val profile = withContext(Dispatchers.Default) { getUserProfileForFilm(item.kinopoiskId) }
                detailsState = detailsState.copy(userProfile = profile)
            }
            ensureLibraryAdultVerdicts()
            ensureLibraryAnimeMeta()
            // Прогресс плеера — сразу на сервер (дешёво, если чисто): второй телефон подтянет
            // его при открытии карточки, не дожидаясь 15-минутного фонового синка.
            shikimoriSyncMutex.withLock { pushDirtyAnimeRates() }
            // Anixart: тот же prompt-push статусов (паритет с Shikimori).
            val anixartAuth = uiState.anixartAuthState
            if (anixartAuth.isLoggedIn && anixartAuth.token != null && anixartRepository != null) {
                anixartSyncMutex.withLock { pushDirtyAnixartLists(anixartAuth.token) }
            }
            onDone?.invoke()
        }
    }

    private fun refreshFromStore() {
        viewModelScope.launch {
            val preferences = withContext(Dispatchers.Default) { userStateStore.getUserPreferences() }
            val fallbackTileSize = preferences.tileSize
            val library = withContext(Dispatchers.Default) { buildLibraryItems() }
            val avatar = withContext(Dispatchers.Default) { userStateStore.getProfileAvatar() }
            uiState = uiState.copy(
                library = library,
                profileAvatar = avatar,
                themeMode = preferences.themeMode,
                hideRussianContent = preferences.hideRussianContent,
                discoverTileSize = preferences.discoverTileSize ?: fallbackTileSize,
                libraryTileSize = preferences.libraryTileSize ?: fallbackTileSize,
                showFpsCounter = preferences.showFpsCounter
            )
        }
    }

    @Volatile
    private var statuslessHusksPruned = false

    private fun buildLibraryItems(): List<LibraryUiItem> {
        // Разовая чистка пустой шелухи без статуса (считалась в итогах профиля,
        // но не видна ни в одной вкладке). Дальше такая не копится: снятие
        // пометки с пустого профиля удаляет его (см. setFeedQuickStatus).
        if (!statuslessHusksPruned) {
            statuslessHusksPruned = true
            runCatching { userStateStore.pruneEmptyStatuslessProfiles() }
        }
        // Первый кадр библиотеки — из дискового снапшота рейтов: сеть с фетчем ещё
        // в пути, а раздел должен показать аниме Shikimori сразу. Фоновая
        // refreshShikimoriAuth() освежит данные следом.
        if (!shikimoriRatesSnapshotHydrated) {
            shikimoriRatesSnapshotHydrated = true
            // Гидратация и без логина: после переустановки Google-бэкап возвращает prefs, но не ключ
            // Keystore — EncryptedSharedPreferences не расшифровываются, сессия мертва, и снапшот
            // остаётся единственным источником Shikimori-части библиотеки до перелогина. При логине
            // чужой аккаунт гасится в refreshShikimoriAuth (snapshotUserId != userId).
            val currentUserId = shikimoriAuthStore?.getAuthState()?.userId ?: 0
            runCatching { userStateStore.getShikimoriRatesSnapshot() }.getOrNull()
                ?.takeIf { it.rates.isNotEmpty() && (currentUserId == 0 || currentUserId == it.userId) }
                ?.let {
                    cachedShikimoriRates = it.rates
                    snapshotUserId = it.userId
                }
        }
        val historyRecords = userStateStore.getHistory()
        val profileMap = userStateStore.getProfiles()
            .associateBy { it.kinopoiskId }
            .toMutableMap()
        val localAnimeCache = userStateStore.getShikimoriAnimeCache()

        val format = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.forLanguageTag("ru")
        )
        val result = mutableListOf<LibraryUiItem>()
        val addedIds = mutableSetOf<Int>()

        // History entries without a local profile get a placeholder WATCHING status for display.
        // If a Shikimori rate exists for such an id we later swap that placeholder for the real
        // server-side status; otherwise merely pressing "Watch" would yank titles out of their
        // Planned/Completed folders.
        val defaultedStatusIds = mutableSetOf<Int>()

        // First: history records (highest priority for display)
        historyRecords.forEach { history ->
            val profile = profileMap.remove(history.kinopoiskId)
            val item = history.toLibraryUiItem(profile, format)
            if (profile == null) defaultedStatusIds.add(item.kinopoiskId)
            if (addedIds.add(item.kinopoiskId)) {
                result += item
            }
        }

        // Second: remaining profiles (not in history)
        profileMap.values.forEach { profile ->
            val item = profile.toLibraryUiItem()
            if (addedIds.add(item.kinopoiskId)) {
                result += item
            }
        }

        // Third: Shikimori rates (only if not already added from local sources)
        // Also merge with local cache to ensure poster/title data is available
        cachedShikimoriRates.forEach { rate ->
            val item = rate.toLibraryUiItemWithCache(localAnimeCache) ?: return@forEach
            if (addedIds.add(item.kinopoiskId)) {
                result.add(item)
            } else {
                // Item already exists, try to enrich it with Shikimori data
                val existingIdx = result.indexOfFirst { it.kinopoiskId == item.kinopoiskId }
                if (existingIdx >= 0) {
                    val existing = result[existingIdx]
                    // Единый формат (см. fillAnimeGaps): метаданные рейта добирают
                    // пустоты истории/профиля — иначе ТВ-онгоинг без total
                    // показывал бы тип по эвристику эпизодов («Фильм»).
                    var enriched = existing.fillAnimeGaps(
                        posterUrl = item.posterUrl,
                        totalEpisodes = item.totalEpisodes,
                        animeKind = item.animeKind,
                        releaseStatus = item.releaseStatus,
                        releaseYear = item.releaseYear,
                        episodesAired = item.episodesAired,
                        ratingText = null
                    )
                    // The local side had no opinion about this title: a history-only
                    // entry carries a synthetic WATCHING default, a profile seeded by
                    // addFromDetails (merely pressing «Watch») carries status null.
                    // Adopt the server status/rating/note instead — otherwise a
                    // Shikimori-list anime vanishes from every library tab the moment
                    // it is watched (the seeded profile shadows the rate below).
                    if ((enriched.kinopoiskId in defaultedStatusIds && existing.status == UserFilmStatus.WATCHING) ||
                        existing.status == null
                    ) {
                        enriched = enriched.copy(
                            status = item.status,
                            userRating = enriched.userRating ?: item.userRating,
                            note = enriched.note?.takeIf { it.isNotBlank() } ?: item.note,
                            watchedEpisodes = enriched.watchedEpisodes ?: item.watchedEpisodes,
                            totalEpisodes = enriched.totalEpisodes ?: item.totalEpisodes
                        )
                        defaultedStatusIds.remove(enriched.kinopoiskId)
                    }
                    result[existingIdx] = enriched
                }
            }
        }

        // New-episode detection: join the already-loaded Shikimori calendar to library items by
        // shikimori id (calendar.anime.id + ANIME_ID_OFFSET == kinopoiskId). The calendar carries
        // nextEpisode + nextEpisodeAt for every ongoing anime; we also fill episodesAired from it
        // for items built from history/profiles (the rates path already sets it). We read from
        // cachedShikimoriCalendar (not uiState.calendarItems) because uiState isn't safely
        // readable while buildInitialState() is mid-construction.
        val calendarByKpId = cachedShikimoriCalendar
            .filter { it.anime?.id != null }
            .associate { it.anime!!.id + hd.kinoshka.app.data.model.ANIME_ID_OFFSET to it }
        if (calendarByKpId.isNotEmpty()) {
            for (i in result.indices) {
                val cal = calendarByKpId[result[i].kinopoiskId] ?: continue
                val existing = result[i]
                result[i] = existing.copy(
                    nextEpisodeAt = cal.nextEpisodeAt ?: existing.nextEpisodeAt,
                    episodesAired = existing.episodesAired ?: cal.anime?.episodesAired
                )
            }
        }

        // Группировка/статистика/плитки библиотеки: тот же единый формат
        // (см. fillAnimeGaps), источник — дисковый кэш Shikimori. Anixart
        // говорит КАКОЕ аниме, подробности — Shikimori.
        if (localAnimeCache.isNotEmpty()) {
            for (i in result.indices) {
                val existing = result[i]
                if (existing.kinopoiskId < hd.kinoshka.app.data.model.ANIME_ID_OFFSET) continue
                if (existing.animeKind != null && existing.releaseStatus != null &&
                    existing.releaseYear != null && existing.totalEpisodes != null &&
                    existing.ratingText != null
                ) continue
                val cached = localAnimeCache[existing.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET] ?: continue
                result[i] = existing.fillAnimeGaps(
                    posterUrl = null,
                    totalEpisodes = cached.episodes,
                    animeKind = cached.kind,
                    releaseStatus = cached.status,
                    releaseYear = cached.year,
                    episodesAired = cached.episodesAired,
                    ratingText = cached.score
                )
            }
        }

        // Переключатель «Показывать хентай»: выкл — прячем 18+ аниме. Вердикт Shikimori
        // (жанр «хентай»/рейтинг rx из кэша деталей) приоритетнее; пока флага нет —
        // синхронная проверка по каталогу hanime.
        if (!userStateStore.isHentaiVisibleInLibrary()) {
            result.removeAll { item ->
                if (item.kinopoiskId < hd.kinoshka.app.data.model.ANIME_ID_OFFSET) return@removeAll false
                val cachedAdult = localAnimeCache[item.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET]?.isAdult
                cachedAdult == true ||
                    (cachedAdult == null &&
                        hd.kinoshka.app.data.source.HentaiStreamResolver.isKnownHentai(item.title, item.subtitle))
            }
        }

        // Sort based on user preference. The unsorted base goes to the cache so switching
        // the sort type / direction doesn't re-parse the history and profile blobs.
        libraryBaseCache = result
        return applyLibrarySort(result)
    }

    /** Сортирует готовую базу библиотеки сохранённым типом и направлением. */
    private fun applyLibrarySort(base: List<LibraryUiItem>): List<LibraryUiItem> {
        val sorted = when (userStateStore.getLibrarySortType()) {
            hd.kinoshka.app.data.local.LibrarySortType.LAST_VIEWED ->
                base.sortedByDescending { it.viewedAtMillis ?: it.updatedAt }
            hd.kinoshka.app.data.local.LibrarySortType.DATE_ADDED ->
                base.sortedByDescending { it.updatedAt }
            hd.kinoshka.app.data.local.LibrarySortType.ALPHABETICAL ->
                base.sortedBy { it.title.lowercase(Locale.forLanguageTag("ru")) }
            hd.kinoshka.app.data.local.LibrarySortType.RATING ->
                base.sortedByDescending {
                    it.ratingText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: 0.0
                }
            hd.kinoshka.app.data.local.LibrarySortType.RELEASE_DATE ->
                base.sortedByDescending { it.updatedAt } // Fallback, actual release date would need extra data
        }
        return if (userStateStore.isLibrarySortReversed()) sorted.asReversed() else sorted
    }

    private fun getUserProfileForFilm(id: Int): UserFilmProfile? {
        val local = userStateStore.getProfile(id)
        // A status/progress-free "Watch"-seed husk (not curated) must not shadow the
        // Shikimori rate: for such titles the rate is what actually holds the user's
        // status, and the husk made the status vanish from the «Прогресс просмотра» editor.
        if (local != null && local.isCurated()) return local
        return rateProfileForFilm(id)
    }

    /**
     * Rate-backed profile for an anime id (>= ANIME_ID_OFFSET), built purely from the
     * in-memory rate cache — no prefs parsing. Used for the «Прогресс просмотра» fallback
     * and as the [UserStateStoreBase.addFromDetails] seed when pressing «Смотреть».
     */
    private fun rateProfileForFilm(id: Int): UserFilmProfile? {
        if (id < hd.kinoshka.app.data.model.ANIME_ID_OFFSET) return null
        val rawAnimeId = id - hd.kinoshka.app.data.model.ANIME_ID_OFFSET
        val rate = cachedShikimoriRates.firstOrNull {
            it.targetId == rawAnimeId || it.anime?.id == rawAnimeId
        } ?: return null
        val filmStatus = when (rate.status.lowercase()) {
            "watching" -> UserFilmStatus.WATCHING
            "planned" -> UserFilmStatus.PLANNED
            "completed" -> UserFilmStatus.COMPLETED
            "rewatching" -> UserFilmStatus.REWATCHING
            "on_hold" -> UserFilmStatus.ON_HOLD
            "dropped" -> UserFilmStatus.DROPPED
            else -> UserFilmStatus.WATCHING
        }
        return UserFilmProfile(
            kinopoiskId = id,
            title = rate.anime?.displayTitle ?: "Аниме #$rawAnimeId",
            subtitle = rate.anime?.name,
            posterUrl = rate.anime?.posterUrl,
            ratingText = rate.anime?.score ?: if (rate.score > 0) rate.score.toString() else null,
            type = "ANIME",
            status = filmStatus,
            userRating = if (rate.score > 0) rate.score else null,
            note = rate.text,
            watchedSeasons = null,
            watchedEpisodes = if (rate.episodes > 0) rate.episodes else null,
            totalEpisodesInSeason = null,
            totalSeasons = null,
            totalEpisodes = rate.anime?.episodes,
            updatedAt = rate.getUpdatedEpochMillis()
        )
    }
}

/**
 * Единый формат аниме-плитки: добивка пустых полей из источника метаданных
 * (рейт Shikimori / дисковый кэш). Один список полей на ВСЕ пути сборки
 * (рейты, история, профили, импорт Anixart, хентай — у всех shikimori-id
 * и один LibraryUiItem): расхождение форматов чинится здесь, а не в N местах.
 * Только заполнение пустот — локальные данные всегда приоритетнее.
 * ratingText из рейта осознанно не тянем (там смесь community score и своей
 * оценки) — рейтинг плитки даёт только свой источник/кэш.
 */
private fun LibraryUiItem.fillAnimeGaps(
    posterUrl: String?,
    totalEpisodes: Int?,
    animeKind: String?,
    releaseStatus: String?,
    releaseYear: Int?,
    episodesAired: Int?,
    ratingText: String?
): LibraryUiItem {
    if (kinopoiskId < ANIME_ID_OFFSET) return this
    return copy(
        posterUrl = this.posterUrl ?: posterUrl,
        totalEpisodes = this.totalEpisodes ?: totalEpisodes,
        animeKind = this.animeKind ?: animeKind,
        releaseStatus = this.releaseStatus ?: releaseStatus,
        releaseYear = this.releaseYear ?: releaseYear,
        episodesAired = this.episodesAired ?: episodesAired,
        ratingText = this.ratingText ?: ratingText
    )
}

private fun hd.kinoshka.app.data.model.ShikimoriUserRate.toLibraryUiItem(): LibraryUiItem? {
    val animeItem = anime
    val actualTargetId = if (animeItem != null) animeItem.id else targetId
    if (actualTargetId <= 0) return null

    val appFilmId = actualTargetId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET
    val appTitle = animeItem?.displayTitle ?: "Аниме #$actualTargetId"
    val appPoster = animeItem?.posterUrl
    val filmStatus = when (status.lowercase()) {
        "watching" -> UserFilmStatus.WATCHING
        "planned" -> UserFilmStatus.PLANNED
        "completed" -> UserFilmStatus.COMPLETED
        "rewatching" -> UserFilmStatus.REWATCHING
        "on_hold" -> UserFilmStatus.ON_HOLD
        "dropped" -> UserFilmStatus.DROPPED
        else -> UserFilmStatus.WATCHING
    }
    val rateTime = getUpdatedEpochMillis().takeIf { it > 0 } ?: System.currentTimeMillis()
    return LibraryUiItem(
        kinopoiskId = appFilmId,
        title = appTitle,
        subtitle = animeItem?.name,
        posterUrl = appPoster,
        ratingText = animeItem?.score ?: if (score > 0) score.toString() else null,
        type = "ANIME",
        isRussian = false,
        viewedAtMillis = rateTime,
        viewedAtLabel = null,
        status = filmStatus,
        userRating = if (score > 0) score else null,
        note = text,
        watchedSeasons = null,
        watchedEpisodes = if (episodes > 0) episodes else null,
        totalEpisodesInSeason = null,
        totalSeasons = null,
        totalEpisodes = animeItem?.episodes,
        updatedAt = rateTime,
        episodesAired = animeItem?.episodesAired,
        animeKind = animeItem?.kind,
        releaseStatus = animeItem?.status,
        releaseYear = animeItem?.airedOn?.take(4)?.toIntOrNull()
    )
}

private fun hd.kinoshka.app.data.model.ShikimoriUserRate.toLibraryUiItemWithCache(
    localCache: Map<Int, hd.kinoshka.app.data.local.ShikimoriAnimeCache>
): LibraryUiItem? {
    val animeItem = anime
    val actualTargetId = if (animeItem != null) animeItem.id else targetId
    if (actualTargetId <= 0) return null

    // Try to get cached info if anime data is missing
    val cachedInfo = if (animeItem == null) localCache[actualTargetId] else null

    val appFilmId = actualTargetId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET
    val appTitle = animeItem?.displayTitle ?: cachedInfo?.displayTitle ?: "Аниме #$actualTargetId"
    val appPoster = animeItem?.posterUrl ?: cachedInfo?.posterUrl ?: "https://smarthard.net/static/animes/$actualTargetId.jpeg"
    val appEpisodes = animeItem?.episodes ?: cachedInfo?.episodes
    val appScore = animeItem?.score ?: cachedInfo?.score

    val filmStatus = when (status.lowercase()) {
        "watching" -> UserFilmStatus.WATCHING
        "planned" -> UserFilmStatus.PLANNED
        "completed" -> UserFilmStatus.COMPLETED
        "rewatching" -> UserFilmStatus.REWATCHING
        "on_hold" -> UserFilmStatus.ON_HOLD
        "dropped" -> UserFilmStatus.DROPPED
        else -> UserFilmStatus.WATCHING
    }
    val rateTime = getUpdatedEpochMillis().takeIf { it > 0 } ?: System.currentTimeMillis()
    val appEpisodesAired = animeItem?.episodesAired ?: cachedInfo?.episodesAired
    return LibraryUiItem(
        kinopoiskId = appFilmId,
        title = appTitle,
        subtitle = animeItem?.name ?: cachedInfo?.name,
        posterUrl = appPoster,
        ratingText = appScore ?: if (score > 0) score.toString() else null,
        type = "ANIME",
        isRussian = false,
        viewedAtMillis = rateTime,
        viewedAtLabel = null,
        status = filmStatus,
        userRating = if (score > 0) score else null,
        note = text,
        watchedSeasons = null,
        watchedEpisodes = if (episodes > 0) episodes else null,
        totalEpisodesInSeason = null,
        totalSeasons = null,
        totalEpisodes = appEpisodes,
        updatedAt = rateTime,
        episodesAired = appEpisodesAired,
        animeKind = animeItem?.kind ?: cachedInfo?.kind,
        releaseStatus = animeItem?.status ?: cachedInfo?.status,
        releaseYear = animeItem?.airedOn?.take(4)?.toIntOrNull() ?: cachedInfo?.year
    )
}

private fun Throwable.toUiMessage(): String {
    return if (this is HttpException) {
        when (code()) {
            401 -> "Ошибка 401: проверьте валидность KP_API_KEY в local.properties"
            429 -> "Слишком много запросов к API. Подождите и повторите попытку."
            else -> "Ошибка API (${code()})"
        }
    } else {
        message ?: "Ошибка запроса к сети"
    }
}

private fun HistoryRecord.toLibraryUiItem(
    profile: UserFilmProfile?,
    format: DateFormat
): LibraryUiItem {
    val actualType = if (kinopoiskId >= ANIME_ID_OFFSET) "ANIME" else profile?.type
    return LibraryUiItem(
        kinopoiskId = kinopoiskId,
        title = profile?.title ?: title,
        subtitle = profile?.subtitle ?: subtitle,
        posterUrl = profile?.posterUrl ?: posterUrl,
        ratingText = profile?.ratingText ?: ratingText,
        type = actualType,
        isRussian = profile?.isRussian ?: (isRussian == true),
        viewedAtMillis = viewedAt,
        viewedAtLabel = format.format(Date(viewedAt)),
        // History entries created before a profile was persisted must still be visible in
        // the default library tab. They represent an actively watched title.
        status = profile?.status ?: UserFilmStatus.WATCHING,
        userRating = profile?.userRating,
        note = profile?.note,
        watchedSeasons = profile?.watchedSeasons,
        watchedEpisodes = profile?.watchedEpisodes,
        totalEpisodesInSeason = profile?.totalEpisodesInSeason,
        totalSeasons = profile?.totalSeasons,
        totalEpisodes = profile?.totalEpisodes,
        updatedAt = profile?.updatedAt ?: viewedAt
    )
}

private fun UserFilmProfile.toLibraryUiItem(): LibraryUiItem {
    return LibraryUiItem(
        kinopoiskId = kinopoiskId,
        title = title,
        subtitle = subtitle,
        posterUrl = posterUrl,
        ratingText = ratingText,
        type = if (kinopoiskId >= ANIME_ID_OFFSET) "ANIME" else type,
        isRussian = isRussian == true,
        viewedAtMillis = null,
        viewedAtLabel = null,
        status = status,
        userRating = userRating,
        note = note,
        watchedSeasons = watchedSeasons,
        watchedEpisodes = watchedEpisodes,
        totalEpisodesInSeason = totalEpisodesInSeason,
        totalSeasons = totalSeasons,
        totalEpisodes = totalEpisodes,
        updatedAt = updatedAt
    )
}

// FilmsViewModelFactory переехал обратно в app: сигнатура ViewModelProvider.Factory.create(Class)
// есть только в android-варианте lifecycle, на desktop нужен create(KClass, extras). Desktop-UI
// конструирует FilmsViewModel напрямую, без ViewModelProvider.
