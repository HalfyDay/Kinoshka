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
import hd.kinoshka.app.data.model.SeasonItem
import hd.kinoshka.app.data.repo.FilmsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class HomeTab {
    CATALOG,
    HISTORY
}

enum class DiscoverCategory(val title: String, val apiType: String) {
    POPULAR("Популярное", "TOP_POPULAR_ALL"),
    TOP_250("Топ 250", "TOP_250_MOVIES"),
    SERIES("Сериалы", "TOP_250_TV_SHOWS"),
    AWAIT("Ожидаемые", "CLOSES_RELEASES")
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
    val updatedAt: Long
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
    val tileSize: FilmTileSize = FilmTileSize.MEDIUM
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
    val savingProfile: Boolean = false
)

class FilmsViewModel(
    private val repository: FilmsRepository,
    private val userStateStore: UserStateStore
) : ViewModel() {

    var uiState by mutableStateOf(buildInitialState())
        private set

    var detailsState by mutableStateOf(DetailsUiState())
        private set

    init {
        loadDiscoverFirstPage(uiState.discoverCategory)
    }

    fun onQueryChange(query: String) {
        uiState = uiState.copy(query = query)
    }

    fun submitSearch() {
        val query = uiState.query.trim()
        if (query.isBlank()) {
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
        watchedEpisodes: Int?
    ) {
        val safeRating = userRating?.coerceIn(1, 10)
        val safeSeasons = watchedSeasons?.coerceAtLeast(0)
        val safeEpisodes = watchedEpisodes?.coerceAtLeast(0)

        detailsState = detailsState.copy(savingProfile = true)
        val updated = userStateStore.updateProfileFromDetails(
            item = details,
            status = status,
            userRating = safeRating,
            note = note,
            watchedSeasons = safeSeasons,
            watchedEpisodes = safeEpisodes
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

    fun setTileSize(size: FilmTileSize) {
        userStateStore.setTileSize(size)
        uiState = uiState.copy(tileSize = size)
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
                val details = repository.details(id)
                val seasonsDeferred = async {
                    if (details.type == "TV_SERIES") {
                        runCatching { repository.seasons(id) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
                val similarsDeferred = async {
                    runCatching { repository.similars(id) }.getOrDefault(emptyList())
                }
                val relationsDeferred = async {
                    runCatching { repository.relations(id) }.getOrDefault(emptyList())
                }
                val imagesDeferred = async {
                    runCatching { repository.images(id = id, page = 1) }.getOrDefault(emptyList())
                }
                val seasons = seasonsDeferred.await()
                val similars = similarsDeferred.await()
                val relations = relationsDeferred.await()
                val images = imagesDeferred.await()
                DetailsUiState(
                    item = details,
                    seasons = seasons,
                    similars = similars
                        .filter { it.id > 0 }
                        .distinctBy { it.id },
                    relations = relations
                        .filter { it.id > 0 }
                        .distinctBy { it.id },
                    images = images
                        .filter { !it.previewUrl.isNullOrBlank() || !it.imageUrl.isNullOrBlank() },
                    userProfile = userStateStore.getProfile(id)
                )
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
                repository.popular(
                    collectionType = category.apiType,
                    page = 1
                )
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
                repository.popular(
                    collectionType = category.apiType,
                    page = nextPage
                )
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
            runCatching { repository.search(query = query, page = 1) }
                .onSuccess { items ->
                    uiState = uiState.copy(
                        loading = false,
                        items = items,
                        isSearchResult = true,
                        currentPage = 1,
                        hasMore = items.isNotEmpty()
                    )
                }
                .onFailure { ex ->
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
            runCatching { repository.search(query = query, page = nextPage) }
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

    private fun buildInitialState(): HomeUiState {
        val preferences = userStateStore.getUserPreferences()
        return HomeUiState(
            loading = true,
            library = buildLibraryItems(),
            profileAvatar = userStateStore.getProfileAvatar(),
            themeMode = preferences.themeMode,
            hideRussianContent = preferences.hideRussianContent,
            tileSize = preferences.tileSize
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
        uiState = uiState.copy(
            library = buildLibraryItems(),
            profileAvatar = userStateStore.getProfileAvatar(),
            themeMode = preferences.themeMode,
            hideRussianContent = preferences.hideRussianContent,
            tileSize = preferences.tileSize
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
        updatedAt = updatedAt
    )
}

class FilmsViewModelFactory(
    private val repository: FilmsRepository,
    private val userStateStore: UserStateStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FilmsViewModel(repository, userStateStore) as T
    }
}
