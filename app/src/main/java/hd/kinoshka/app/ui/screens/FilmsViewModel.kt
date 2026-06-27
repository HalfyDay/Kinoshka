package hd.kinoshka.app.ui.screens

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
    HISTORY
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
    val updatedAt: Long
)

data class SearchFilterState(
    val selectedCountryId: Int? = null,
    val selectedGenreId: Int? = null,
    val selectedOrder: String = "RATING",
    val selectedType: String = "ALL",
    val ratingFrom: Int? = null,
    val ratingTo: Int? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null
) {
    val isActive: Boolean
        get() = selectedCountryId != null || selectedGenreId != null || selectedOrder != "RATING" ||
                selectedType != "ALL" || ratingFrom != null || ratingTo != null || yearFrom != null || yearTo != null
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
    val discoverTileSize: FilmTileSize = FilmTileSize.MEDIUM,
    val libraryTileSize: FilmTileSize = FilmTileSize.MEDIUM,
    val showFpsCounter: Boolean = false,
    val filterState: SearchFilterState = SearchFilterState(),
    val availableGenres: List<FilterItem> = emptyList(),
    val availableCountries: List<FilterItem> = emptyList(),
    val showFilterSheet: Boolean = false,
    val contentType: ContentType = ContentType.FILMS
)

data class DetailsUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val item: FilmDetails? = null,
    val seasons: List<SeasonItem> = emptyList(),
    val similars: List<FilmLinkItem> = emptyList(),
    val relations: List<FilmLinkItem> = emptyList(),
    val images: List<FilmImageItem> = emptyList(),
    val userProfile: UserFilmProfile? = null,
    val savingProfile: Boolean = false,
    val animeDetails: hd.kinoshka.app.data.model.ShikimoriAnimeDetails? = null,
    val animeCharacters: List<hd.kinoshka.app.data.model.ShikimoriRole> = emptyList()
)

class FilmsViewModel(
    private val repository: FilmsRepository,
    private val animeRepository: AnimeRepository,
    private val userStateStore: UserStateStore
) : ViewModel() {

    var uiState by mutableStateOf(buildInitialState())
        private set

    var detailsState by mutableStateOf(DetailsUiState())
        private set

    init {
        loadDiscoverFirstPage(uiState.discoverCategory)
        loadFilters()
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
        uiState = uiState.copy(
            contentType = contentType,
            isSearchResult = false,
            query = ""
        )
        if (uiState.query.isNotBlank() || uiState.filterState.isActive) {
            submitSearch()
        } else {
            loadDiscoverFirstPage(uiState.discoverCategory)
        }
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

        if (snapshot.isSearchResult) {
            val query = snapshot.query.trim()
            if (query.isBlank()) return
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

    fun setShowFpsCounter(enabled: Boolean) {
        userStateStore.setFpsCounterEnabled(enabled)
        uiState = uiState.copy(showFpsCounter = enabled)
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
                    val screenshotsDeferred = async {
                        runCatching { animeRepository.screenshots(shikimoriId) }.getOrDefault(emptyList())
                    }
                    val relatedDeferred = async {
                        runCatching { animeRepository.related(shikimoriId) }.getOrDefault(emptyList())
                    }
                    val rolesDeferred = async {
                        runCatching { animeRepository.roles(shikimoriId) }.getOrDefault(emptyList())
                    }
                    val screenshots = screenshotsDeferred.await()
                    val relatedList = relatedDeferred.await()
                    val rolesList = rolesDeferred.await()

                    val validCharacters = rolesList
                        .filter { it.character != null && !it.character.name.isNullOrBlank() }
                        .distinctBy { it.character?.id }

                    val imageItems = screenshots.map {
                        FilmImageItem(imageUrl = it.getFullOriginalUrl(), previewUrl = it.getFullPreviewUrl())
                    }
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
                            posterUrl = a.image?.getFullOriginalUrl(),
                            posterUrlPreview = a.image?.getFullOriginalUrl() ?: a.image?.getFullPreviewUrl(),
                            relationType = rel.relationRussian ?: rel.relation,
                            year = yearInt,
                            type = kindStr
                        )
                    }
                    DetailsUiState(
                        item = animeDetails.toFilmDetails(),
                        seasons = emptyList(),
                        similars = emptyList(),
                        relations = relationItems,
                        images = imageItems,
                        userProfile = userStateStore.getProfile(id),
                        animeDetails = animeDetails,
                        animeCharacters = validCharacters
                    )
                } else {
                    val details = repository.details(id)
                    val seasonsDeferred = async {
                        if (details.type == "TV_SERIES") {
                            runCatching { repository.seasons(id) }.getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }
                    }
                    val relationsDeferred = async {
                        runCatching { repository.relations(id) }.getOrDefault(emptyList())
                    }
                    val imagesDeferred = async {
                        runCatching { repository.images(id = id, page = 1) }.getOrDefault(emptyList())
                    }
                    val seasons = seasonsDeferred.await()
                    val relations = relationsDeferred.await()
                    val images = imagesDeferred.await()
                    DetailsUiState(
                        item = details,
                        seasons = seasons,
                        similars = emptyList(),
                        relations = relations
                            .filter { it.id > 0 }
                            .distinctBy { it.id },
                        images = images
                            .filter { !it.previewUrl.isNullOrBlank() || !it.imageUrl.isNullOrBlank() },
                        userProfile = userStateStore.getProfile(id)
                    )
                }
            }
                .onSuccess { loaded ->
                    detailsState = loaded
                }
                .onFailure { ex ->
                    detailsState = DetailsUiState(error = ex.toUiMessage())
                }
        }
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
                    animeRepository.popular(page = 1).map { it.toFilmItem() }
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
                        items = items,
                        isSearchResult = false,
                        currentPage = 1,
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
                    animeRepository.popular(page = nextPage).map { it.toFilmItem() }
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

    private fun loadSearchFirstPage(query: String) {
        viewModelScope.launch {
            uiState = uiState.copy(
                loading = true,
                loadingMore = false,
                error = null,
                isSearchResult = true,
                currentPage = 1,
                hasMore = true
            )
            val filters = uiState.filterState
            val cleanQuery = query.trim()
            runCatching {
                if (uiState.contentType == ContentType.ANIME) {
                    animeRepository.search(query = cleanQuery, page = 1).map { it.toFilmItem() }
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
                                animeRepository.search(query = fixedQuery, page = 1).map { it.toFilmItem() }
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
                                items = fallbackItems,
                                isSearchResult = true,
                                currentPage = 1,
                                hasMore = fallbackItems.isNotEmpty()
                            )
                            return@launch
                        }
                    }
                }
                uiState = uiState.copy(
                    loading = false,
                    items = items,
                    isSearchResult = true,
                    currentPage = 1,
                    hasMore = items.isNotEmpty()
                )
            }.onFailure { ex ->
                uiState = uiState.copy(
                    loading = false,
                    error = ex.toUiMessage(),
                    isSearchResult = true
                )
            }
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
                    animeRepository.search(query = cleanQuery, page = nextPage).map { it.toFilmItem() }
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
            showFpsCounter = preferences.showFpsCounter
        )
    }

    private fun refreshLibraryAndAvatar() {
        uiState = uiState.copy(
            library = buildLibraryItems(),
            profileAvatar = userStateStore.getProfileAvatar()
        )
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
            showFpsCounter = preferences.showFpsCounter
        )
    }

    private fun buildLibraryItems(): List<LibraryUiItem> {
        val historyRecords = userStateStore.getHistory()
        val profileMap = userStateStore.getProfiles()
            .associateBy { it.kinopoiskId }
            .toMutableMap()

        val format = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale("ru")
        )
        val result = mutableListOf<LibraryUiItem>()

        historyRecords.sortedByDescending { it.viewedAt }.forEach { history ->
            val profile = profileMap.remove(history.kinopoiskId)
            result += history.toLibraryUiItem(profile, format)
        }

        profileMap.values
            .sortedByDescending { it.updatedAt }
            .forEach { profile ->
                result += profile.toLibraryUiItem()
            }

        return result
    }
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
    return LibraryUiItem(
        kinopoiskId = kinopoiskId,
        title = profile?.title ?: title,
        subtitle = profile?.subtitle ?: subtitle,
        posterUrl = profile?.posterUrl ?: posterUrl,
        ratingText = profile?.ratingText ?: ratingText,
        type = profile?.type,
        isRussian = profile?.isRussian ?: (isRussian == true),
        viewedAtMillis = viewedAt,
        viewedAtLabel = format.format(Date(viewedAt)),
        status = profile?.status,
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
        type = type,
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
    private val userStateStore: UserStateStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FilmsViewModel(repository, animeRepository, userStateStore) as T
    }
}
