package hd.kinoshka.app.ui.screens

import android.util.Log
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
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImageItem
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.FilmLinkItem
import hd.kinoshka.app.data.model.FilterItem
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.SeasonItem
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.utils.SearchQueryUtils
import hd.kinoshka.app.data.model.PlaybackSequenceOption
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class ContentType {
    FILMS,
    ANIME
}

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
    val nextEpisodeAt: String? = null
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
    val animeScoreFrom: Int? = null
) {
    val isActive: Boolean
        get() = selectedCountryId != null || selectedGenreId != null || selectedOrder != "RATING" ||
                selectedType != "ALL" || ratingFrom != null || ratingTo != null || yearFrom != null || yearTo != null ||
                animeKind != null || animeStatus != null || animeRating != null || animeGenreId != null ||
                animeOrder != "popularity" || animeScoreFrom != null
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
    FilterItem(id = 42, genre = "Сэйнэн")
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
    val playerMode: hd.kinoshka.app.data.local.PlayerMode = hd.kinoshka.app.data.local.PlayerMode.DDBB,
    val searchHistory: List<hd.kinoshka.app.data.local.SearchHistoryRecord> = emptyList(),
    val isInstantSearch: Boolean = false
)

data class DetailsUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val item: FilmDetails? = null,
    val seasons: List<SeasonItem> = emptyList(),
    val similars: List<FilmLinkItem> = emptyList(),
    val relations: List<FilmLinkItem> = emptyList(),
    val fullChronology: List<FilmLinkItem> = emptyList(),
    val franchiseResponse: hd.kinoshka.app.data.model.ShikimoriFranchiseResponse? = null,
    val images: List<FilmImageItem> = emptyList(),
    val userProfile: UserFilmProfile? = null,
    val savingProfile: Boolean = false,
    val animeDetails: hd.kinoshka.app.data.model.ShikimoriAnimeDetails? = null,
    val animeCharacters: List<hd.kinoshka.app.data.model.ShikimoriRole> = emptyList()
)

class FilmsViewModel(
    private val repository: FilmsRepository,
    private val animeRepository: AnimeRepository,
    private val userStateStore: UserStateStore,
    private val shikimoriAuthStore: hd.kinoshka.app.data.local.ShikimoriAuthStore? = null
) : ViewModel() {

    private var cachedShikimoriRates: List<hd.kinoshka.app.data.model.ShikimoriUserRate> = emptyList()

    // Snapshot of the Shikimori calendar fetched by loadCalendar(). buildLibraryItems reads this
    // instead of uiState.calendarItems because the calendar arrives asynchronously and uiState is
    // still being constructed the first time buildLibraryItems runs (reading uiState then is a
    // NPE on the not-yet-initialized State delegate).
    private var cachedShikimoriCalendar: List<hd.kinoshka.app.data.model.ShikimoriCalendarItem> = emptyList()

    // In-flight search job. Cancelled + replaced on every new query so fast typing (instant
    // search) can't let an older, slower request clobber the newer results.
    private var searchJob: kotlinx.coroutines.Job? = null

    // Throttle for refreshAfterPlayerClosed(): ON_RESUME fires several times while navigating,
    // and rebuilding the library re-serializes the whole profile blob.
    private var lastResumeRefreshMs = 0L
    private companion object {
        const val RESUME_REFRESH_THROTTLE_MS = 1_000L
    }

    var uiState by mutableStateOf(buildInitialState())
        private set

    var detailsState by mutableStateOf(DetailsUiState())
        private set

    init {
        loadDiscoverFirstPage(uiState.discoverCategory)
        loadFilters()
        refreshShikimoriAuth()
        loadCalendar()
        loadTopics()
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

    fun refreshShikimoriAuth() {
        shikimoriAuthStore?.let { store ->
            val state = store.getAuthState()
            uiState = uiState.copy(shikimoriAuthState = state)
            if (state.isLoggedIn && state.userId > 0) {
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
                    uiState = uiState.copy(library = buildLibraryItems())

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
                                                status = details.status
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
                        uiState = uiState.copy(library = buildLibraryItems())
                    }
                }
            } else {
                cachedShikimoriRates = emptyList()
                uiState = uiState.copy(library = buildLibraryItems())
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

    fun saveShikimoriToken(code: String) {
        viewModelScope.launch {
            Log.d("ShikimoriSync", "=== Starting OAuth token exchange ===")
            Log.d("ShikimoriSync", "Authorization code: ${code.take(10)}...")
            Log.d("ShikimoriSync", "Client ID configured: ${hd.kinoshka.app.BuildConfig.SHIKIMORI_CLIENT_ID.isNotBlank()}")
            Log.d("ShikimoriSync", "Client Secret configured: ${hd.kinoshka.app.BuildConfig.SHIKIMORI_CLIENT_SECRET.isNotBlank()}")

            val tokenResponse = animeRepository.exchangeCodeForToken(code)
            if (tokenResponse != null) {
                Log.d("ShikimoriSync", "Token exchange SUCCESS!")
                Log.d("ShikimoriSync", "Access token: ${tokenResponse.accessToken.take(10)}...")
                Log.d("ShikimoriSync", "Refresh token: ${tokenResponse.refreshToken?.take(10)}...")

                Log.d("ShikimoriSync", "Fetching user info with new token...")
                val whoami = animeRepository.whoami(tokenResponse.accessToken)
                if (whoami != null) {
                    Log.d("ShikimoriSync", "User info fetched: id=${whoami.id}, nickname=${whoami.nickname}")
                    val rawAvatar = whoami.avatar ?: whoami.image?.original
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
                    Log.d("ShikimoriSync", "=== OAuth login successful! ===")
                } else {
                    Log.e("ShikimoriSync", "Failed to fetch user info")
                }
            } else {
                Log.e("ShikimoriSync", "=== Token exchange FAILED ===")
                Log.e("ShikimoriSync", "Check if SHIKIMORI_CLIENT_ID and SHIKIMORI_CLIENT_SECRET are configured in local.properties")
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
                }
        }
    }

    fun updateFilters(newFilters: SearchFilterState) {
        uiState = uiState.copy(filterState = newFilters)
        submitSearch()
    }

    fun searchGenre(genreName: String, isAnime: Boolean) {
        if (isAnime) {
            userStateStore.setSavedContentType(ContentType.ANIME)
            val matchedGenre = shikimoriGenres.firstOrNull { it.genre.equals(genreName, ignoreCase = true) }
            uiState = uiState.copy(
                tab = HomeTab.CATALOG,
                contentType = ContentType.ANIME,
                query = "",
                filterState = SearchFilterState(animeGenreId = matchedGenre?.id)
            )
        } else {
            userStateStore.setSavedContentType(ContentType.FILMS)
            val matchedGenre = uiState.availableGenres.firstOrNull { it.genre.equals(genreName, ignoreCase = true) }
            uiState = uiState.copy(
                tab = HomeTab.CATALOG,
                contentType = ContentType.FILMS,
                query = "",
                filterState = SearchFilterState(selectedGenreId = matchedGenre?.id)
            )
        }
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
            filterState = SearchFilterState()
        )
        loadDiscoverFirstPage(uiState.discoverCategory)
    }

    fun onDiscoverCategorySelected(category: DiscoverCategory) {
        if (uiState.discoverCategory == category && !uiState.isSearchResult) return
        uiState = uiState.copy(
            discoverCategory = category,
            isSearchResult = false,
            query = ""
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
        totalEpisodes: Int?
    ) {
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
            totalEpisodes = safeTotalEpisodes
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
            Log.d("ShikimoriSync", "saveUserProfile: kinopoiskId=${details.kinopoiskId}, shikimoriId=$shikimoriId, isLoggedIn=${authState.isLoggedIn}")
            if (authState.isLoggedIn && authState.accessToken != null) {
                viewModelScope.launch {
                    val shikiStatus = when (status) {
                        UserFilmStatus.WATCHING -> "watching"
                        UserFilmStatus.PLANNED -> "planned"
                        UserFilmStatus.COMPLETED -> "completed"
                        UserFilmStatus.REWATCHING -> "rewatching"
                        UserFilmStatus.ON_HOLD -> "on_hold"
                        UserFilmStatus.DROPPED -> "dropped"
                        else -> null
                    }
                    Log.d("ShikimoriSync", "shikiStatus=$shikiStatus, existingRate=${cachedShikimoriRates.firstOrNull { it.targetId == shikimoriId }?.id}")
                    if (shikiStatus != null) {
                        var token = authState.accessToken
                        val existingRate = cachedShikimoriRates.firstOrNull { it.targetId == shikimoriId }
                        var success = false

                        // Try with current token first
                        if (existingRate != null) {
                            Log.d("ShikimoriSync", "Updating existing rate id=${existingRate.id}")
                            val result = animeRepository.updateUserRate(
                                token = token,
                                rateId = existingRate.id,
                                status = shikiStatus,
                                episodes = safeEpisodes,
                                score = safeRating
                            )
                            success = result != null
                        } else {
                            Log.d("ShikimoriSync", "Creating new rate for targetId=$shikimoriId")
                            val result = animeRepository.createUserRate(
                                token = token,
                                userId = authState.userId,
                                targetId = shikimoriId,
                                status = shikiStatus,
                                episodes = safeEpisodes ?: 0,
                                score = safeRating ?: 0
                            )
                            success = result != null
                        }

                        // If failed with 401, try refreshing token
                        if (!success && authState.refreshToken != null) {
                            Log.d("ShikimoriSync", "Token expired, attempting refresh...")
                            val newTokenResponse = animeRepository.refreshToken(authState.refreshToken)
                            if (newTokenResponse != null) {
                                // Save new tokens
                                shikimoriAuthStore?.saveSession(
                                    token = newTokenResponse.accessToken,
                                    refresh = newTokenResponse.refreshToken,
                                    userId = authState.userId,
                                    nickname = authState.nickname,
                                    avatarUrl = authState.avatarUrl
                                )
                                token = newTokenResponse.accessToken
                                Log.d("ShikimoriSync", "Token refreshed, retrying...")

                                // Retry with new token
                                if (existingRate != null) {
                                    animeRepository.updateUserRate(
                                        token = token,
                                        rateId = existingRate.id,
                                        status = shikiStatus,
                                        episodes = safeEpisodes,
                                        score = safeRating
                                    )
                                } else {
                                    animeRepository.createUserRate(
                                        token = token,
                                        userId = authState.userId,
                                        targetId = shikimoriId,
                                        status = shikiStatus,
                                        episodes = safeEpisodes ?: 0,
                                        score = safeRating ?: 0
                                    )
                                }
                            } else {
                                Log.e("ShikimoriSync", "Failed to refresh token, user needs to re-login")
                            }
                        }
                    }
                }
            } else {
                Log.w("ShikimoriSync", "Not logged in or no access token")
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
        uiState = uiState.copy(library = buildLibraryItems())
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
                    val animeDetails = animeRepository.details(shikimoriId)
                    val baseState = DetailsUiState(
                        item = animeDetails.toFilmDetails(),
                        userProfile = getUserProfileForFilm(id),
                        animeDetails = animeDetails,
                        loading = false
                    )
                    detailsState = baseState

                    launch {
                        val screenshots = runCatching { animeRepository.screenshots(shikimoriId) }.getOrDefault(emptyList())
                        val imageItems = screenshots.map {
                            FilmImageItem(imageUrl = it.getFullOriginalUrl(), previewUrl = it.getFullPreviewUrl())
                        }
                        detailsState = detailsState.copy(images = imageItems)
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
                            .filter { it.character != null && !it.character.name.isNullOrBlank() }
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
                    val details = repository.details(id)
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
                }
            }.onFailure { ex ->
                detailsState = DetailsUiState(error = ex.toUiMessage())
            }
        }
    }

    /**
     * Keyed-списки (HomeScreen: key = { _, film -> film.kinopoiskId }) падают, если один
     * kinopoiskId встречается дважды. Страницы API это иногда допускают, а rankResults только
     * сортирует и такой дубликат не убирает — поэтому дедуп нужен на каждом присвоении items.
     */
    private fun List<FilmItem>.dedupe(): List<FilmItem> = distinctBy { it.kinopoiskId }

    private suspend fun fetchAnime(query: String?, page: Int): List<FilmItem> {
        val filters = uiState.filterState
        return animeRepository.search(
            query = query?.ifEmpty { null },
            kind = filters.animeKind,
            status = filters.animeStatus,
            rating = filters.animeRating,
            genreId = filters.animeGenreId,
            order = filters.animeOrder,
            scoreFrom = filters.animeScoreFrom,
            page = page
        ).map { it.toFilmItem() }.dedupe()
    }

    private fun loadDiscoverFirstPage(category: DiscoverCategory) {
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
        return HomeUiState(
            loading = true,
            library = buildLibraryItems(),
            profileAvatar = userStateStore.getProfileAvatar(),
            themeMode = preferences.themeMode,
            hideRussianContent = preferences.hideRussianContent,
            discoverTileSize = preferences.discoverTileSize ?: fallbackTileSize,
            libraryTileSize = preferences.libraryTileSize ?: fallbackTileSize,
            showFpsCounter = preferences.showFpsCounter,
            contentType = preferences.contentType,
            playbackSequence = preferences.playbackSequence,
            playerMode = preferences.playerMode
            // calendarItems is intentionally left default-empty: uiState is being constructed for
            // the first time here, and buildLibraryItems reads from cachedShikimoriCalendar (set
            // by loadCalendar) instead, so there is no read-during-init cycle.
        )
    }

    private fun refreshLibraryAndAvatar() {
        uiState = uiState.copy(
            library = buildLibraryItems(),
            profileAvatar = userStateStore.getProfileAvatar()
        )
    }

    /**
     * Re-reads the persisted state after returning from an external screen (the native player
     * writes progress straight to SharedPreferences from its own Activity, bypassing this
     * ViewModel). Without this the library folders, progress bars and the details header showed
     * stale values until the app was restarted.
     */
    fun refreshAfterPlayerClosed() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastResumeRefreshMs < RESUME_REFRESH_THROTTLE_MS) return
        lastResumeRefreshMs = now

        refreshLibraryAndAvatar()

        val item = detailsState.item
        if (!detailsState.loading && item != null) {
            detailsState = detailsState.copy(userProfile = getUserProfileForFilm(item.kinopoiskId))
        }
    }

    private fun refreshFromStore() {
        val preferences = userStateStore.getUserPreferences()
        val fallbackTileSize = preferences.tileSize
        uiState = uiState.copy(
            library = buildLibraryItems(),
            profileAvatar = userStateStore.getProfileAvatar(),
            themeMode = preferences.themeMode,
            hideRussianContent = preferences.hideRussianContent,
            discoverTileSize = preferences.discoverTileSize ?: fallbackTileSize,
            libraryTileSize = preferences.libraryTileSize ?: fallbackTileSize,
            showFpsCounter = preferences.showFpsCounter,
            playbackSequence = preferences.playbackSequence
        )
    }

    private fun buildLibraryItems(): List<LibraryUiItem> {
        val historyRecords = userStateStore.getHistory()
        val profileMap = userStateStore.getProfiles()
            .associateBy { it.kinopoiskId }
            .toMutableMap()
        val localAnimeCache = userStateStore.getShikimoriAnimeCache()

        val format = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale("ru")
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
        (cachedShikimoriRates ?: emptyList()).forEach { rate ->
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
                    // The local side had no opinion about this title (history-only entry): adopt the
                    // server status/rating/note instead of leaving a synthetic WATCHING default.
                    if (enriched.kinopoiskId in defaultedStatusIds && existing.status == UserFilmStatus.WATCHING) {
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

        // Sort based on user preference
        val sortType = userStateStore.getLibrarySortType()
        return when (sortType) {
            hd.kinoshka.app.data.local.LibrarySortType.LAST_VIEWED ->
                result.sortedByDescending { it.viewedAtMillis ?: it.updatedAt }
            hd.kinoshka.app.data.local.LibrarySortType.DATE_ADDED ->
                result.sortedByDescending { it.updatedAt }
            hd.kinoshka.app.data.local.LibrarySortType.ALPHABETICAL ->
                result.sortedBy { it.title.lowercase(Locale("ru")) }
            hd.kinoshka.app.data.local.LibrarySortType.RATING ->
                result.sortedByDescending {
                    it.ratingText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: 0.0
                }
            hd.kinoshka.app.data.local.LibrarySortType.RELEASE_DATE ->
                result.sortedByDescending { it.updatedAt } // Fallback, actual release date would need extra data
        }
    }

    private fun getUserProfileForFilm(id: Int): UserFilmProfile? {
        val local = userStateStore.getProfile(id)
        if (local != null) return local
        if (id >= hd.kinoshka.app.data.model.ANIME_ID_OFFSET) {
            val rawAnimeId = id - hd.kinoshka.app.data.model.ANIME_ID_OFFSET
            val rate = cachedShikimoriRates?.firstOrNull { 
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
        episodesAired = appEpisodesAired
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

class FilmsViewModelFactory(
    private val repository: FilmsRepository,
    private val animeRepository: AnimeRepository,
    private val userStateStore: UserStateStore,
    private val shikimoriAuthStore: hd.kinoshka.app.data.local.ShikimoriAuthStore? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FilmsViewModel(repository, animeRepository, userStateStore, shikimoriAuthStore) as T
    }
}
