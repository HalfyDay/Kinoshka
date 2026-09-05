package hd.kinoshka.app.ui.screens

import hd.kinoshka.app.util.log.KLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import hd.kinoshka.app.data.local.AppThemeMode
import hd.kinoshka.app.data.local.FilmTileSize
import hd.kinoshka.app.data.local.HistoryRecord
import hd.kinoshka.app.data.local.UserFilmProfile
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
import hd.kinoshka.app.data.model.containsAnimeGenre
import hd.kinoshka.app.data.model.SeasonItem
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.utils.SearchQueryUtils
import hd.kinoshka.app.data.model.PlaybackSequenceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val calendarItems: List<hd.kinoshka.app.data.model.ShikimoriCalendarItem> = emptyList(),
    val topics: List<hd.kinoshka.app.data.model.ShikimoriTopic> = emptyList(),
    val calendarLoading: Boolean = false,
    val topicsLoading: Boolean = false,
    val playbackSequence: PlaybackSequenceOption = PlaybackSequenceOption.SOURCES_FIRST,
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
    val discoverTitle: String? = null
)

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
    private val shikimoriAuthStore: ShikimoriAuthProvider? = null
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

    /** In-flight job ленты «Обзора»: один за раз, повторные вызовы — no-op пока активен. */
    private var overviewJob: kotlinx.coroutines.Job? = null

    // Throttle for refreshAfterPlayerClosed(): ON_RESUME fires several times while navigating,
    // and rebuilding the library re-serializes the whole profile blob.
    private var lastResumeRefreshMs = 0L
    private companion object {
        const val RESUME_REFRESH_THROTTLE_MS = 1_000L

        /** Пауза между стартом кино- и аниме-веток Обзора — не упираемся в RPS обоих API. */
        const val OVERVIEW_STAGGER_MS = 400L

        /** Ступенчатая задержка перед запросами секций внутри ветки (лимит Shikimori: 5rps).
         *  Важно: слип всегда ДО semaphore.withPermit, а не внутри — иначе сон занимает
         *  пермит и сериализует всю ветку (хвост 2.8–3.5с держал 1 из 3 пермитов). */
        const val OVERVIEW_REQUEST_GAP_MS = 250L

        /** Добрасывающий проход 18+-вердиктов (до ~120 details) стартует с задержкой после
         *  init, чтобы не отъедать 5 rps Shikimori у секций первого экрана Обзора. */
        const val ADULT_VERDICT_DEFER_MS = 8_000L

        /** «Новинки» кино: фильмы/сериалы начиная с этого года. */
        const val FRESH_YEAR_FROM = 2024

        /** Витрина сверху ленты: столько карточек без дублей каруселей. */
        const val OVERVIEW_HERO_TAKE = 5

        /** Превью-клип hanime1 на 18+-страницах выключен (протухающий токен, чужие тайтлы);
         *  переключение обратно включает фетч + карточку без прочих правок. */
        const val HENTAI_PREVIEW_ENABLED = false
    }

    var uiState by mutableStateOf(buildInitialState())
        private set

    var detailsState by mutableStateOf(DetailsUiState())
        private set

    init {
        loadDiscoverFirstPage(uiState.discoverCategory)
        loadFilters()
        refreshShikimoriAuth()
        // Тяжёлый добрасывающий проход вердиктов — после первого экрана, не вместе со штормом init.
        viewModelScope.launch {
            kotlinx.coroutines.delay(ADULT_VERDICT_DEFER_MS)
            ensureLibraryAdultVerdicts()
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

    fun refreshShikimoriAuth() {
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
                    val ratesResult = animeRepository.getUserRates(state.userId)
                    // Если оба эндпоинта Shikimori упали — сохраняем последний известный список.
                    // Иначе одна временная ошибка сети стирала бы всю Shikimori-часть библиотеки.
                    val rates = ratesResult.getOrNull()
                    if (rates == null) {
                        uiState = uiState.copy(error = "Не удалось обновить список Shikimori. Показаны последние данные.")
                        return@launch
                    }
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
                    persistShikimoriRatesSnapshot(state.userId, ratesWithLocalCache)
                    // Тяжёлая пересборка (парсинг JSON-блобов) — вне main, иначе дроп кадров.
                    val library = withContext(Dispatchers.Default) { buildLibraryItems() }
                    uiState = uiState.copy(library = library)

                    // Fetch missing details from API in parallel
                    val missingDetailsRates = ratesWithLocalCache.filter { it.anime == null && it.targetId > 0 }
                    if (missingDetailsRates.isNotEmpty()) {
                        val updatedRates = ratesWithLocalCache.toMutableList()
                        val semaphore = kotlinx.coroutines.sync.Semaphore(5)
                        val deferreds = missingDetailsRates.take(40).map { rate ->
                            async {
                                semaphore.acquire()
                                try {
                                    runCatching {
                                        val details = animeRepository.details(rate.targetId)
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
                                        userStateStore.saveShikimoriAnimeInfo(
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
                                        )
                                    }
                                } finally {
                                    semaphore.release()
                                }
                            }
                        }
                        deferreds.forEach { it.await() }
                        cachedShikimoriRates = updatedRates
                        persistShikimoriRatesSnapshot(state.userId, updatedRates)
                        val refreshedLibrary = withContext(Dispatchers.Default) { buildLibraryItems() }
                        uiState = uiState.copy(library = refreshedLibrary)
                        ensureLibraryAdultVerdicts()
                    }
                }
            } else {
                cachedShikimoriRates = emptyList()
                viewModelScope.launch {
                    val emptyLibrary = withContext(Dispatchers.Default) { buildLibraryItems() }
                    uiState = uiState.copy(library = emptyLibrary)
                }
                ensureLibraryAdultVerdicts()
            }
        }
    }

    private var adultVerdictJob: kotlinx.coroutines.Job? = null

    /**
     * Дозагрузка 18+-вердиктов для аниме из библиотеки без кэша деталей (история/профили
     * без оценки Shikimori). Фильтр «Показывать хентай» синхронный, а каталог hanime
     * сопоставляет названия ненадёжно — добираем детали Shikimori и сохраняем isAdult
     * в кэш, после чего пересобираем библиотеку.
     */
    private fun ensureLibraryAdultVerdicts() {
        if (userStateStore.isHentaiVisibleInLibrary()) return
        if (adultVerdictJob?.isActive == true) return
        adultVerdictJob = viewModelScope.launch(Dispatchers.IO) {
            val offset = ANIME_ID_OFFSET
            val semaphore = kotlinx.coroutines.sync.Semaphore(4)
            val totalSaved = java.util.concurrent.atomic.AtomicInteger(0)
            // Порции по 40 (как в дозагрузке деталей оценок); раунд продолжается, только
            // если предыдущий дал хотя бы один новый вердикт — иначе тайтлы недоступны.
            // Список тайтлов пересчитываем в каждом раунде: оценки Shikimori приезжают
            // асинхронно и расширяют библиотеку.
            var rounds = 0
            while (rounds < 3) {
                rounds++
                val libraryIds = buildSet {
                    userStateStore.getHistory().forEach { if (it.kinopoiskId >= offset) add(it.kinopoiskId) }
                    userStateStore.getProfiles().forEach { if (it.kinopoiskId >= offset) add(it.kinopoiskId) }
                    cachedShikimoriRates.forEach { if (it.targetId > 0) add(it.targetId + offset) }
                }
                val cache = userStateStore.getShikimoriAnimeCache()
                val pending = libraryIds.filter { cache[it - offset]?.isAdult == null }
                if (pending.isEmpty()) break
                val savedInRound = java.util.concurrent.atomic.AtomicInteger(0)
                pending.take(40).map { kpId ->
                    async {
                        semaphore.acquire()
                        try {
                            val shikimoriId = kpId - offset
                            val details = runCatching { animeRepository.details(shikimoriId) }.getOrNull()
                                ?: return@async
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
                            userStateStore.saveShikimoriAnimeInfo(
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
                                    isAdult = isAdultAnime(details)
                                )
                            )
                            savedInRound.incrementAndGet()
                        } finally {
                            semaphore.release()
                        }
                    }
                }.forEach { it.await() }
                totalSaved.addAndGet(savedInRound.get())
                if (savedInRound.get() == 0) break
            }
            if (totalSaved.get() > 0) {
                // Уже на Dispatchers.IO: сборку делаем здесь, на Main — только публикацию.
                val library = buildLibraryItems()
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(library = library)
                }
            }
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
                    refreshShikimoriAuth()
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
        refreshShikimoriAuth()
    }

    fun logoutShikimori() {
        shikimoriAuthStore?.clearSession()
        cachedShikimoriRates = emptyList()
        snapshotUserId = 0
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { userStateStore.clearShikimoriRatesSnapshot() }
        }
        refreshShikimoriAuth()
    }

    private fun loadFilters() {
        viewModelScope.launch {
            runCatching { repository.filters() }
                .onSuccess { res ->
                    uiState = uiState.copy(
                        availableGenres = res.genres.filter { !it.genre.isNullOrBlank() },
                        availableCountries = res.countries.filter { !it.country.isNullOrBlank() }
                    )
                    // Жанровые карусели кино зависят от справочника: если Обзор уже загрузился
                    // без них — догружаем только жанры, а не всю ветку.
                    if (uiState.overviewFilmSections.none { it.id.startsWith("film_genre_") }) {
                        refillFilmGenres(res.genres.filter { !it.genre.isNullOrBlank() })
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
        userStateStore.addFromDetails(details)
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
                            refreshShikimoriAuth()
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

        // Sync with Shikimori if it's an anime
        if (details.kinopoiskId >= ANIME_ID_OFFSET) {
            val shikimoriId = details.kinopoiskId - ANIME_ID_OFFSET
            val authState = uiState.shikimoriAuthState
            KLog.d("ShikimoriSync", "saveUserProfile: kinopoiskId=${details.kinopoiskId}, shikimoriId=$shikimoriId, isLoggedIn=${authState.isLoggedIn}")
            if (authState.isLoggedIn && authState.accessToken != null) {
                viewModelScope.launch {
                    val shikiStatus = when (status) {
                        UserFilmStatus.WATCHING -> "watching"
                        UserFilmStatus.PLANNED -> "planned"
                        UserFilmStatus.COMPLETED -> "completed"
                        UserFilmStatus.REWATCHING -> "rewatching"
                        UserFilmStatus.ON_HOLD -> "on_hold"
                        UserFilmStatus.DROPPED -> "dropped"
                    }
                    KLog.d("ShikimoriSync", "shikiStatus=$shikiStatus, existingRate=${cachedShikimoriRates.firstOrNull { it.targetId == shikimoriId }?.id}")
                    var token = authState.accessToken
                    val existingRate = cachedShikimoriRates.firstOrNull { it.targetId == shikimoriId }
                    // Для аниме watchedSeasons в шите — это «Повторы», у Shikimori это rewatches.
                    val rewatches = safeSeasons?.takeIf { it > 0 }

                    // Try with current token first
                    var result: hd.kinoshka.app.data.model.ShikimoriUserRate? = null
                    if (existingRate != null) {
                        KLog.d("ShikimoriSync", "Updating existing rate id=${existingRate.id}")
                        result = animeRepository.updateUserRate(
                            token = token,
                            rateId = existingRate.id,
                            status = shikiStatus,
                            episodes = safeEpisodes,
                            score = safeRating,
                            rewatches = rewatches
                        )
                    } else {
                        KLog.d("ShikimoriSync", "Creating new rate for targetId=$shikimoriId")
                        result = animeRepository.createUserRate(
                            token = token,
                            userId = authState.userId,
                            targetId = shikimoriId,
                            status = shikiStatus,
                            episodes = safeEpisodes ?: 0,
                            score = safeRating ?: 0,
                            rewatches = rewatches
                        )
                    }

                    // Create при существующей серверной оценке даёт 422: подтягиваем свежие
                    // рейты и повторяем как update, иначе прогресс «не сохраняется».
                    if (result == null && existingRate == null && authState.userId > 0) {
                        animeRepository.getUserRates(authState.userId).getOrNull()?.let { fresh ->
                            cachedShikimoriRates = fresh
                            fresh.firstOrNull { it.targetId == shikimoriId }?.let { serverRate ->
                                KLog.d("ShikimoriSync", "Found server rate id=${serverRate.id} after create failed, updating")
                                result = animeRepository.updateUserRate(
                                    token = token,
                                    rateId = serverRate.id,
                                    status = shikiStatus,
                                    episodes = safeEpisodes,
                                    score = safeRating,
                                    rewatches = rewatches
                                )
                            }
                        }
                    }

                    // If failed with 401, try refreshing token
                    if (result == null && authState.refreshToken != null) {
                        KLog.d("ShikimoriSync", "Token expired, attempting refresh...")
                        val newTokenResponse = animeRepository.refreshToken(authState.refreshToken)
                        if (newTokenResponse != null) {
                            persistFreshShikimoriTokens(authState, newTokenResponse.accessToken, newTokenResponse.refreshToken)
                            token = newTokenResponse.accessToken
                            KLog.d("ShikimoriSync", "Token refreshed, retrying...")

                            // Retry with new token
                            result = if (existingRate != null) {
                                animeRepository.updateUserRate(
                                    token = token,
                                    rateId = existingRate.id,
                                    status = shikiStatus,
                                    episodes = safeEpisodes,
                                    score = safeRating,
                                    rewatches = rewatches
                                )
                            } else {
                                animeRepository.createUserRate(
                                    token = token,
                                    userId = authState.userId,
                                    targetId = shikimoriId,
                                    status = shikiStatus,
                                    episodes = safeEpisodes ?: 0,
                                    score = safeRating ?: 0,
                                    rewatches = rewatches
                                )
                            }
                        } else {
                            KLog.e("ShikimoriSync", "Failed to refresh token, user needs to re-login")
                        }
                    }

                    if (result != null) {
                        // Раньше кэш оценок не обновлялся после успеха: библиотека строилась
                        // из протухшего кэша, а следующий рефреш с сервера затирал локальный
                        // прогресс. Вписываем серверный ответ сразу.
                        val fresh = result
                        val current = cachedShikimoriRates.toMutableList()
                        val idx = current.indexOfFirst { it.targetId == shikimoriId }
                        if (idx >= 0) current[idx] = fresh else current.add(fresh)
                        cachedShikimoriRates = current
                        refreshLibraryAndAvatar()
                    } else {
                        KLog.e("ShikimoriSync", "Sync failed for shikimoriId=$shikimoriId, resyncing from server")
                        refreshShikimoriAuth()
                    }
                }
            } else {
                KLog.w("ShikimoriSync", "Not logged in or no access token")
            }
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

    fun setPlaybackSequence(option: PlaybackSequenceOption) {
        userStateStore.setPlaybackSequence(option)
        uiState = uiState.copy(playbackSequence = option)
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
                                        addAll(current.genres.filterNot { it.genre?.equals("хентай", ignoreCase = true) == true })
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
                                "special" -> "Спешл"
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
                                "special" -> "Спешл"
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
        val hasAdultGenre = details.genres.any { g ->
            val n = (g.russian ?: g.name).lowercase()
            n.contains("хентай") || n.contains("hentai") || n.contains("эротик") || n.contains("ecchi")
        }
        return hasAdultGenre ||
            hd.kinoshka.app.data.source.HentaiStreamResolver.isKnownHentai(details.name, details.russian)
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
            playbackSequence = preferences.playbackSequence,
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
    }

    /**
     * Re-reads the persisted state after returning from an external screen (the native player
     * writes progress straight to SharedPreferences from its own Activity, bypassing this
     * ViewModel). Without this the library folders, progress bars and the details header showed
     * stale values until the app was restarted.
     */
    fun refreshAfterPlayerClosed() {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastResumeRefreshMs < RESUME_REFRESH_THROTTLE_MS) return
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
                showFpsCounter = preferences.showFpsCounter,
                playbackSequence = preferences.playbackSequence
            )
        }
    }

    private fun buildLibraryItems(): List<LibraryUiItem> {
        // Первый кадр библиотеки — из дискового снапшота рейтов: сеть с фетчем ещё
        // в пути, а раздел должен показать аниме Shikimori сразу. Фоновая
        // refreshShikimoriAuth() освежит данные следом.
        if (!shikimoriRatesSnapshotHydrated) {
            shikimoriRatesSnapshotHydrated = true
            val currentUserId = shikimoriAuthStore?.getAuthState()?.userId ?: 0
            if (currentUserId > 0) {
                runCatching { userStateStore.getShikimoriRatesSnapshot() }.getOrNull()
                    ?.takeIf { it.userId == currentUserId && it.rates.isNotEmpty() }
                    ?.let {
                        cachedShikimoriRates = it.rates
                        snapshotUserId = it.userId
                    }
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
                    // Update poster if missing
                    var enriched = existing
                    if (enriched.posterUrl == null && item.posterUrl != null) {
                        enriched = enriched.copy(posterUrl = item.posterUrl)
                    }
                    // Update total episodes if missing
                    if (enriched.totalEpisodes == null && item.totalEpisodes != null) {
                        enriched = enriched.copy(totalEpisodes = item.totalEpisodes)
                    }
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

        // Группировка/статистика библиотеки: тайтлы, построенные из истории/профилей (без
        // встроенного anime у рейта), добирают kind/status/год из дискового кэша Shikimori.
        if (localAnimeCache.isNotEmpty()) {
            for (i in result.indices) {
                val existing = result[i]
                if (existing.kinopoiskId < hd.kinoshka.app.data.model.ANIME_ID_OFFSET) continue
                if (existing.animeKind != null && existing.releaseStatus != null && existing.releaseYear != null) continue
                val cached = localAnimeCache[existing.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET] ?: continue
                result[i] = existing.copy(
                    animeKind = existing.animeKind ?: cached.kind,
                    releaseStatus = existing.releaseStatus ?: cached.status,
                    releaseYear = existing.releaseYear ?: cached.year
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
        if (local != null) return local
        if (id >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET) {
            val rawAnimeId = id - hd.kinoshka.app.data.model.ANIME_ID_OFFSET
            val rate = cachedShikimoriRates.firstOrNull { 
                it.targetId == rawAnimeId || it.anime?.id == rawAnimeId 
            }
            if (rate != null) {
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
        return null
    }
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
        updatedAt = rateTime
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
