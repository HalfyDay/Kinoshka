package hd.kinoshka.app.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import hd.kinoshka.app.data.feed.FeedChip
import hd.kinoshka.app.data.feed.FeedClipState
import hd.kinoshka.app.data.feed.FeedDiagnostics
import hd.kinoshka.app.data.feed.FeedItem
import hd.kinoshka.app.data.feed.TasteFeatures
import hd.kinoshka.app.data.feed.TasteVectorStore
import hd.kinoshka.app.data.feed.franchiseKeyOf
import hd.kinoshka.app.data.feed.FeedRepository
import hd.kinoshka.app.data.feed.InterestProfileStore
import hd.kinoshka.app.data.feed.RutubeClipSource
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import hd.kinoshka.app.data.source.HentaiStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Дообогащённые по текущему тайтлу данные: жанры, описание, кадры, полный постер. */
data class FeedItemExtras(
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val stills: List<String> = emptyList(),
    /** Полноразмерный постер из details(): подменяет превью в фоне карточки. */
    val fullPosterUrl: String? = null
)

data class FeedUiState(
    val selectedChip: FeedChip = FeedChip.ALL,
    /** Чипсы, доступные к показу: без 18+/Хентай до подтверждения возраста. */
    val adultUnlocked: Boolean = false,
    val showAdultGate: Boolean = false,
    /** Раздел, для которого сейчас показывается визард «что вам нравится» (первый вход). */
    val onboardingChip: FeedChip? = null,
    /** Лента исчерпана: показанное не повторяем, предлагаем ручной сброс. */
    val exhausted: Boolean = false,
    val items: List<FeedItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val extras: Map<Int, FeedItemExtras> = emptyMap(),
    val clipStates: Map<Int, FeedClipState> = emptyMap(),
    val expandedIds: Set<Int> = emptySet(),
    /** Голос по карточке: true = лайк, false = дизлайк, null/нет ключа = не голосовал. */
    val reactions: Map<Int, Boolean> = emptyMap(),
    /** Тайтлы, добавленные в «В планах» из фида (визуальное состояние сессии). */
    val plannedIds: Set<Int> = emptySet(),
    /** Позиция пейджера — для продолжения с места при возврате на экран. */
    val currentPageIndex: Int = 0,
    /** Карточки, помеченные гардианами к удалению: убираются только когда пролистаны. */
    val pendingDropIds: Set<Int> = emptySet(),
    /** Счётчик коммитов удалений — сигнал экрану скорректировать позицию пейджера. */
    val dropCommitToken: Int = 0,
    val errorMessage: String? = null,
    /** Звук включён для нативного Rutube-клипа (YouTube всегда muted). */
    val soundOn: Boolean = false
)

/**
 * ViewModel тестового фида: добор страниц до батча свежих карточек, память показанного
 * между сессиями (без повторов), трейлерные клипы для фона карточек.
 */
class FeedViewModel(
    context: Context,
    private val films: FilmsRepository,
    private val anime: AnimeRepository,
    private val userState: UserStateStore
) : ViewModel() {

    private val appContext = context.applicationContext
    private val interests = InterestProfileStore(context)
    /** Свой вектор вкуса на раздел: аниме-лайки не тянут за собой фильмы. */
    private val tastes = FeedChip.entries.associateWith { TasteVectorStore(appContext, it.name) }
    private val repository = FeedRepository(films, anime, userState, interests) { chip ->
        tastes[chip] ?: tastes.getValue(FeedChip.ALL)
    }

    var uiState by mutableStateOf(FeedUiState(adultUnlocked = interests.isAdultConfirmed()))
        private set

    private var pageIndex = 0
    private var rescuePageIndex = 0
    private var consecutiveEmptyRuns = 0
    /** Готовая к мгновенной отдаче партия, обогретая фоном заранее. */
    @Volatile private var pendingBatch: List<FeedItem> = emptyList()
    private var prefetchJob: kotlinx.coroutines.Job? = null
    private val seenCandidateIds = mutableSetOf<Int>()
    private val inFlightClips = mutableSetOf<Int>()
    private val inFlightExtras = mutableSetOf<Int>()
    /** Соседи, чьи детали уже прогреты — повторный свайп не бьёт по сети. */
    private val warmedIds = mutableSetOf<Int>()
    private var loadJob: kotlinx.coroutines.Job? = null
    private var openedOnce = false

    /**
     * Первый показ экрана: обогащение интересов идёт ПАРАЛЛЕЛЬНО с загрузкой ленты,
     * чтобы экран не висел на «Собираем рекомендации» — первые карточки приходят из
     * популярного/ранжированного, следующие уже учитывают веса жанров.
     *
     * При самом первом открытии вместо автозагрузки запускается визард вкусов:
     * по разделам Фильмы → Сериалы → Аниме спрашиваем любимые жанры.
     */
    fun onScreenOpened() {
        if (openedOnce) return
        openedOnce = true
        // Показанное раньше — сразу в локальный дедуп.
        seenCandidateIds.addAll(interests.seenFeedIds())
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(ENRICH_TIMEOUT_MS) { repository.refreshInterests() }
            }
            // Каталог хентая для гарда аниме-подачи: грузится в фоне, к моменту свайпов готов.
            runCatching { HentaiStreamResolver.preloadCatalog() }
            FeedDiagnostics.maybeAutoWrite(appContext, interests)
        }
        val nextOnboarding = ONBOARDING_ORDER.firstOrNull { !interests.isChipOnboarded(it.name) }
        if (nextOnboarding != null) {
            // Лента подождёт ответа: выбранные жанры должны попасть в первую выдачу.
            uiState = uiState.copy(onboardingChip = nextOnboarding)
        } else if (uiState.items.isEmpty()) {
            selectChip(uiState.selectedChip)
        }
    }

    /** Пользователь отметил жанры в визарде: сразу в профиль весов и в ленту. */
    fun saveTastes(chip: FeedChip, likedGenres: List<String>) {
        interests.markChipOnboarded(chip.name)
        viewModelScope.launch(Dispatchers.IO) {
            likedGenres.forEach { genre -> interests.applyFeedback(listOf(genre), liked = true) }
        }
        advanceOnboarding()
    }

    /** Пропустил визард — больше не показываем для этого раздела. */
    fun skipTastes(chip: FeedChip) {
        interests.markChipOnboarded(chip.name)
        advanceOnboarding()
    }

    private fun advanceOnboarding() {
        val next = ONBOARDING_ORDER.firstOrNull { !interests.isChipOnboarded(it.name) }
        if (next != null) {
            uiState = uiState.copy(onboardingChip = next)
        } else {
            uiState = uiState.copy(onboardingChip = null)
            if (uiState.items.isEmpty()) selectChip(uiState.selectedChip)
        }
    }

    fun selectChip(chip: FeedChip) {
        if (chip.isAdultChip && !uiState.adultUnlocked) {
            uiState = uiState.copy(showAdultGate = true)
            return
        }
        if (chip == uiState.selectedChip && uiState.items.isNotEmpty()) return
        loadJob?.cancel()
        uiState = uiState.copy(
            selectedChip = chip,
            items = emptyList(),
            loading = true,
            canLoadMore = true,
            exhausted = false,
            extras = emptyMap(),
            clipStates = emptyMap(),
            expandedIds = emptySet(),
            reactions = emptyMap(),
            currentPageIndex = 0,
            pendingDropIds = emptySet(),
            errorMessage = null
        )
        pageIndex = 0
        rescuePageIndex = 0
        prefetchJob?.cancel()
        pendingBatch = emptyList()
        loadMoreInternal()
    }

    fun dismissAdultGate() {
        uiState = uiState.copy(showAdultGate = false)
    }

    fun confirmAdultGate() {
        interests.setAdultConfirmed()
        uiState = uiState.copy(adultUnlocked = true, showAdultGate = false)
    }

    fun loadMore() {
        if (uiState.loading || uiState.loadingMore || !uiState.canLoadMore) return
        loadMoreInternal()
    }

    /**
     * Добор до батча свежих карточек. Повторов НЕТ: показанное исключается и в сессии,
     * и между запусками. Когда лента честно исчерпана — ставим [FeedUiState.exhausted],
     * повторный просмотр возможен только по явной кнопке сброса ([resetSeenAndRestart]).
     */
    private fun loadMoreInternal() {
        val chip = uiState.selectedChip
        if (pageIndex == 0) uiState = uiState.copy(loading = true) else uiState = uiState.copy(loadingMore = true)

        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // Готовая партия из фонового префетча — прикладывается мгновенно.
            val pre = pendingBatch
            if (pre.isNotEmpty()) {
                pendingBatch = emptyList()
                val freshNow = pre.filter { live ->
                    seenCandidateIds.add(live.kinopoiskId) &&
                        uiState.items.none { it.kinopoiskId == live.kinopoiskId }
                }
                if (freshNow.isNotEmpty()) {
                    interests.markSeenInFeed(freshNow.map { it.kinopoiskId })
                    FeedDiagnostics.record("партия «${chip.title}» из префетча: +${freshNow.size}")
                    uiState = uiState.copy(
                        items = uiState.items + freshNow,
                        loading = false,
                        loadingMore = false,
                        exhausted = false,
                        errorMessage = null
                    )
                    scheduleRefill(chip)
                    return@launch
                }
            }

            val collected = mutableListOf<FeedItem>()
            var attempts = 0
            val rawCandidates = mutableListOf<FeedItem>()
            while (collected.size < MIN_BATCH && attempts < MAX_PAGES_PER_LOAD) {
                val nextPage = pageIndex + 1
                val loaded = runCatching { repository.page(chip, nextPage, rememberSeedIds()) }
                    .onFailure { Log.w(TAG, "page($nextPage) failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
                attempts++
                pageIndex = nextPage
                if (loaded == null) continue

                loaded.filter { item ->
                    item.kinopoiskId !in seenCandidateIds &&
                        rawCandidates.none { it.kinopoiskId == item.kinopoiskId } &&
                        uiState.items.none { it.kinopoiskId == item.kinopoiskId }
                }.forEach { rawCandidates += it }

                // Накопили кандидатов на проверку — валидируем пачкой.
                if (rawCandidates.size >= VALIDATION_BATCH) break
            }

            // Валидация ДО показа: рейтинг ≥ порога, живой постер (проверка греет кэш),
            // жанры по разделу, не хентай в аниме. Принятые карточки уже обогащены.
            val validated = validateBatch(chip, rawCandidates)
            seenCandidateIds.addAll(validated.map { it.kinopoiskId })
            collected += validated

            // Спасательный круг: ротация источников упёрлась в виденное — добираем
            // чистым популярным со своим счётчиком, чтобы не крутить одни и те же слоты.
            if (collected.size < MIN_BATCH) {
                var rescueAttempts = 0
                while (collected.size < MIN_BATCH && rescueAttempts < RESCUE_PAGES_MAX) {
                    rescueAttempts++
                    rescuePageIndex++
                    val loaded = runCatching { repository.rescuePopular(rescuePageIndex) }.getOrNull()
                    if (loaded.isNullOrEmpty()) break // популярное кончилось/недоступно
                    val freshRaw = loaded.filter { item ->
                        item.kinopoiskId !in seenCandidateIds &&
                            collected.none { c -> c.kinopoiskId == item.kinopoiskId } &&
                            uiState.items.none { it.kinopoiskId == item.kinopoiskId }
                    }
                    if (freshRaw.isEmpty()) continue
                    val freshValidated = validateBatch(chip, freshRaw.take(VALIDATION_BATCH))
                    freshValidated.forEach { seenCandidateIds.add(it.kinopoiskId) }
                    collected += freshValidated
                }
                if (rescueAttempts > 0) {
                    FeedDiagnostics.record("спас-добор «${chip.title}»: +${collected.size} всего после $rescueAttempts стр. популярного")
                }
            }

            // Отменённая загрузка (переключение чипса) — не трогаем состояние.
            if (!coroutineContext.isActive) return@launch

            if (collected.isNotEmpty()) {
                interests.markSeenInFeed(collected.map { it.kinopoiskId })
                consecutiveEmptyRuns = 0
                FeedDiagnostics.record("партия «${chip.title}»: +${collected.size} карточек за $attempts стр.")
                uiState = uiState.copy(
                    items = uiState.items + collected,
                    loading = false,
                    loadingMore = false,
                    canLoadMore = true,
                    exhausted = false,
                    errorMessage = null
                )
                // Сразу греем следующую: к моменту добора она уже готова.
                scheduleRefill(chip)
                return@launch
            }

            consecutiveEmptyRuns++
            // Исчерпание фиксируем после двух пустых забегов подряд: без повторов.
            val exhaustedNow = consecutiveEmptyRuns >= 2 || uiState.items.isEmpty()
            uiState = uiState.copy(
                loading = false,
                loadingMore = false,
                canLoadMore = !exhaustedNow,
                exhausted = exhaustedNow && uiState.items.isNotEmpty(),
                errorMessage = if (uiState.items.isEmpty()) "Не удалось загрузить рекомендации. Проверьте сеть." else null
            )
        }
    }

    /** Явный сброс по кнопке на конце ленты: чистим «виденное» и начинаем заново. */
    fun resetSeenAndRestart() {
        interests.clearSeenFeed()
        seenCandidateIds.clear()
        consecutiveEmptyRuns = 0
        pageIndex = 0
        rescuePageIndex = 0
        prefetchJob?.cancel()
        pendingBatch = emptyList()
        selectChip(uiState.selectedChip)
    }

    /**
     * Фоновый префетч следующей партии: страницы + валидация + прогрев картинок
     * заранее, чтобы добор не заставлял ждать у конца ленты.
     */
    private fun scheduleRefill(chip: FeedChip) {
        if (prefetchJob?.isActive == true || pendingBatch.isNotEmpty()) return
        if (!uiState.canLoadMore) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val raw = mutableListOf<FeedItem>()
            var attempts = 0
            while (raw.size < VALIDATION_BATCH && attempts < 4) {
                val nextPage = pageIndex + 1
                val loaded = runCatching { repository.page(chip, nextPage, rememberSeedIds()) }.getOrNull()
                attempts++
                pageIndex = nextPage
                if (loaded == null) continue
                loaded.filter { item ->
                    item.kinopoiskId !in seenCandidateIds &&
                        raw.none { it.kinopoiskId == item.kinopoiskId } &&
                        uiState.items.none { it.kinopoiskId == item.kinopoiskId }
                }.forEach { raw += it }
            }
            val validated = validateBatch(chip, raw)
            pendingBatch = validated
            FeedDiagnostics.record("префетч «${chip.title}» готов: ${validated.size} карточек")
        }
    }

    /** Кнопка «В планах»: тумблер — повторное нажатие снимает пометку. [onDone] даёт
     *  библиотеке (FilmsViewModel) сигнал пересобрать списки из префов. */
    fun planForLater(item: FeedItem, onDone: () -> Unit = {}) {
        val nowPlanned = item.kinopoiskId !in uiState.plannedIds
        uiState = uiState.copy(
            plannedIds = if (nowPlanned) uiState.plannedIds + item.kinopoiskId
            else uiState.plannedIds - item.kinopoiskId
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                userState.setFeedQuickStatus(
                    kinopoiskId = item.kinopoiskId,
                    title = item.title,
                    posterUrl = item.posterUrl,
                    status = if (nowPlanned) hd.kinoshka.app.data.local.UserFilmStatus.PLANNED else null
                )
            }.onFailure { Log.w(TAG, "plan failed: ${it.javaClass.simpleName}") }
            withContext(Dispatchers.Main) { onDone() }
        }
        if (nowPlanned) FeedDiagnostics.record("в планах: «${item.title}»")
    }

    /** Диалог вкусов: топ измерений вектора ТЕКУЩЕГО раздела. */
    fun tasteSnapshot(): List<Pair<String, Double>> =
        tastes.getValue(uiState.selectedChip).centroidTop()

    /** Диалог «мои лайки»: тайтлы с жанрами, свежие сверху. */
    fun likedTitles(): List<hd.kinoshka.app.data.feed.LikedTitle> =
        interests.likedTitles()

    private var seedIdsCache: List<Int>? = null

    /** Сиды для «похожих»: сначала лайкнутые (свежий вкус), затем недавняя история. */
    private fun rememberSeedIds(): List<Int> {
        seedIdsCache?.let { return it }
        val liked = interests.likedSeedIds()
        val history = runCatching {
            userState.getHistory()
                .sortedByDescending { it.viewedAt }
                .map { it.kinopoiskId }
        }.getOrDefault(emptyList())
        val ids = LinkedHashSet(liked).apply { addAll(history) }.toList().take(SEED_MAX)
        seedIdsCache = ids
        return ids
    }

    // ============================ карточка ============================

    fun toggleExpanded(itemId: Int) {
        val current = uiState.expandedIds
        uiState = uiState.copy(
            expandedIds = if (itemId in current) current - itemId else current + itemId
        )
    }

    /** Карточка стала текущей: позиция в стейт + коммит отложенных удалений + обогащение. */
    fun onItemShown(items: List<FeedItem>, index: Int) {
        val item = items.getOrNull(index) ?: return
        if (uiState.currentPageIndex != index) {
            uiState = uiState.copy(currentPageIndex = index)
        }
        commitPendingDrops(items, index)
        interests.markSeenInFeed(setOf(item.kinopoiskId))
        ensureExtras(item)
        ensureClip(item)
        prefetchNeighbors(items, index)
    }

    /**
     * Отложенные гарды убираем только ПОЗАДИ текущей карточки — список под пальцем
     * не двигается. Индекс корректируется на число удалённых до него, экран по токену
     * переставляет пейджер на скорректированную позицию.
     */
    private fun commitPendingDrops(items: List<FeedItem>, currentIndex: Int) {
        val pending = uiState.pendingDropIds
        if (pending.isEmpty()) return
        var removedBefore = 0
        val removedIds = mutableSetOf<Int>()
        items.forEachIndexed { i, item ->
            if (i < currentIndex && item.kinopoiskId in pending) {
                removedBefore++
                removedIds += item.kinopoiskId
            }
        }
        if (removedBefore == 0) return
        uiState = uiState.copy(
            items = items.filterIndexed { i, it -> !(i < currentIndex && it.kinopoiskId in pending) },
            pendingDropIds = pending - removedIds,
            currentPageIndex = (currentIndex - removedBefore).coerceAtLeast(0),
            dropCommitToken = uiState.dropCommitToken + 1
        )
    }

    /**
     * Прогрев вокруг текущей карточки: постеры соседей ±2 — сразу в кэши Coil;
     * детали/кадры ближайших ±1 — прогрев кэшей репозитория, их картинки тоже.
     * Серые экраны при свайпе исчезают: картинка уже лежит в памяти.
     */
    private fun prefetchNeighbors(items: List<FeedItem>, index: Int) {
        val near = listOfNotNull(items.getOrNull(index - 1), items.getOrNull(index + 1))
        val far = listOfNotNull(items.getOrNull(index - 2), items.getOrNull(index + 2))
        // Постеры соседей греем сразу; ошибка загрузки = карточка без картинки не дойдёт до пользователя.
        (near + far).forEach { neighbor ->
            preloadImages(listOf(neighbor.posterUrl), neighbor.kinopoiskId)
        }
        // Детали/кадры ближайших — прогрев кэшей репозитория и Coil; клип резолвим заранее.
        near.forEach { neighbor ->
            ensureClip(neighbor)
            if (warmedIds.add(neighbor.kinopoiskId)) {
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val extras = loadExtras(neighbor) ?: return@runCatching
                        preloadImages(listOf(extras.fullPosterUrl) + extras.stills)
                    }
                }
            }
        }
    }

    /** Прогрев картинок в кэшах Coil (память+диск); [itemId] — ловим битые ссылки. */
    private fun preloadImages(urls: List<String?>, itemId: Int? = null) {
        val dm = appContext.resources.displayMetrics
        urls.filterNotNull().forEach { url ->
            runCatching {
                val builder = ImageRequest.Builder(appContext)
                    .data(url)
                    .size(dm.widthPixels, dm.heightPixels)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                if (itemId != null) {
                    builder.listener(onError = { _, _ -> onPosterLoadFailed(itemId) })
                }
                appContext.imageLoader.enqueue(builder.build())
            }
        }
    }

    /** Постер так и не загрузился — тихо помечаем карточку, пользователь её не увидит. */
    private fun onPosterLoadFailed(itemId: Int) {
        val item = uiState.items.firstOrNull { it.kinopoiskId == itemId } ?: return
        if (itemId in uiState.pendingDropIds) return
        viewModelScope.launch(Dispatchers.IO) { dropQuietly(item, "битый постер") }
    }

    fun toggleSound() {
        uiState = uiState.copy(soundOn = !uiState.soundOn)
    }

    /** Жанры/описание/кадры/полный постер; null = источники недоступны — карточку не хвалить вслепую. */
    fun ensureExtras(item: FeedItem) {
        if (item.kinopoiskId in uiState.extras || item.kinopoiskId in inFlightExtras) return
        inFlightExtras.add(item.kinopoiskId)
        val chip = uiState.selectedChip
        viewModelScope.launch(Dispatchers.IO) {
            val extras = loadExtras(item)
            inFlightExtras.remove(item.kinopoiskId)
            if (extras == null) return@launch

            // Гард вкуса раздела: жанры пришли из details — можно проверить честно.
            if (violatesSectionTaste(chip, extras.genres)) {
                dropQuietly(item, "гард раздела $chip")
                return@launch
            }
            // Хентай, протёкший в обычную аниме-подачу по каталогу — тоже тихо убираем.
            if (chip == FeedChip.ANIME && HentaiStreamResolver.isKnownHentai(item.originalTitle, item.title)) {
                dropQuietly(item, "гард хентая в Аниме")
                return@launch
            }

            uiState = uiState.copy(extras = uiState.extras + (item.kinopoiskId to extras))
            // Кадры/полный постер этой карточки — в кэш Coil, карусель не будет мигать.
            preloadImages(listOf(extras.fullPosterUrl) + extras.stills)
        }
    }

    /**
     * Гард вкуса раздела, усиленный после полевых жалоб:
     *  - Фильмы/Сериалы/Всё: ни мультфильмов, ни аниме;
     *  - Мультики: только мульт-жанры, аниме не проходит;
     *  - Аниме: мультфильмы не проходят (этти разрешён);
     *  - Хентай: без ограничений по жанрам.
     * Пустые жанры теперь НЕ пропускаем для мультиков — нет данных, нет показа.
     */
    private fun violatesSectionTaste(chip: FeedChip, genres: List<String>): Boolean {
        val g = genres.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val isAnime = "аниме" in g
        val isCartoon = g.any { it.contains("мульт") }
        return when (chip) {
            FeedChip.FILMS, FeedChip.SERIES, FeedChip.ALL -> isAnime || isCartoon
            FeedChip.CARTOONS -> !isCartoon || isAnime
            FeedChip.ANIME -> g.contains("мультфильм") && !isAnime
            FeedChip.HENTAI -> false
        }
    }

    /**
     * Валидация кандидатов ДО показа, параллельно по [VALIDATION_CONCURRENCY]:
     *  1. рейтинг: без оценки или ниже [MIN_KP_RATING] — мимо (Shikimori уже score≥7);
     *  2. постер реально загружается — проверка сама греет кэш Coil, нулевой перерасход;
     *  3. жанры из details: гард раздела и каталог хентая срабатывают ЗАРАНЕЕ,
     *     а не когда пользователь долистал до карточки.
     * Принятые карточки сразу кладут extras в стейт — появляются готовыми.
     */
    private suspend fun validateBatch(chip: FeedChip, raw: List<FeedItem>): List<FeedItem> =
        kotlinx.coroutines.coroutineScope {
            val mutex = Mutex()
            val accepted = mutableListOf<FeedItem>()
            raw.take(VALIDATION_BATCH).chunked(VALIDATION_CONCURRENCY).forEach { chunk ->
                chunk.map { item ->
                    async(Dispatchers.IO) {
                        val ok = runCatching { passesValidation(chip, item) }.getOrDefault(false)
                        if (ok) mutex.withLock { accepted += item }
                    }
                }.joinAll()
            }
            accepted
        }

    private suspend fun passesValidation(chip: FeedChip, item: FeedItem): Boolean {
        // Синтетические аниме-id не проходят в живые разделы ни под каким видом.
        if (chip != FeedChip.ANIME && chip != FeedChip.HENTAI && item.isAnime) return false
        if (!item.isAnime && (item.rating == null || item.rating < MIN_KP_RATING)) return false
        if (item.posterUrl.isNullOrBlank()) return false

        // Постер обязан загрузиться; тот же запрос = префетч под размер экрана.
        val dm = appContext.resources.displayMetrics
        val posterResult = runCatching {
            appContext.imageLoader.execute(
                ImageRequest.Builder(appContext)
                    .data(item.posterUrl)
                    .size(dm.widthPixels, dm.heightPixels)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            )
        }.getOrNull()
        if (posterResult !is coil.request.SuccessResult) return false

        // Детали обязательны: без жанров валидация бессмысленна — не рискуем.
        val extras = loadExtras(item) ?: return false
        if (extras.genres.isEmpty() && extras.fullPosterUrl == null && extras.stills.isEmpty()) return false
        if (violatesSectionTaste(chip, extras.genres)) return false
        // Каталог хентая (включая новые теги от ИИ) чистит аниме-подачу ДО показа.
        if (chip == FeedChip.ANIME && HentaiStreamResolver.isKnownHentai(item.originalTitle, item.title)) return false

        // Карточка войдёт в ленту уже обогащённой: кадры/постер в кэш немедленно.
        uiState = uiState.copy(extras = uiState.extras + (item.kinopoiskId to extras))
        preloadImages(listOf(extras.fullPosterUrl) + extras.stills)
        return true
    }

    /** Тихо пометить карточку (гард/битый постер): уйдёт из ленты, когда её пролистают. */
    private fun dropQuietly(item: FeedItem, why: String) {
        if (item.kinopoiskId in uiState.pendingDropIds) return
        interests.markSeenInFeed(setOf(item.kinopoiskId))
        seenCandidateIds.add(item.kinopoiskId)
        // НЕ вырезаем из середины списка: пейджер под пальцем сдвигать нельзя.
        uiState = uiState.copy(pendingDropIds = uiState.pendingDropIds + item.kinopoiskId)
        FeedDiagnostics.record("$why: «${item.title}» отложена к удалению")
    }

    private suspend fun loadExtras(item: FeedItem): FeedItemExtras? {
        val shikimoriId = item.kinopoiskId - hd.kinoshka.app.data.model.ANIME_ID_OFFSET
        val extras = if (item.isAnime && shikimoriId > 0) {
            val details = runCatching { anime.details(shikimoriId) }.getOrNull()
            val screenshots = runCatching { anime.screenshots(shikimoriId) }.getOrDefault(emptyList())
            FeedItemExtras(
                genres = details?.genres?.mapNotNull { it.russian?.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                description = details?.description?.let(::stripMarkup),
                // Полные оригиналы кадров, не превью.
                stills = screenshots.mapNotNull { it.getFullOriginalUrl() ?: it.getFullPreviewUrl() }.take(5),
                fullPosterUrl = details?.image?.getFullOriginalUrl(shikimoriId)
            )
        } else {
            val details = runCatching { films.details(item.kinopoiskId) }.getOrNull()
            val images = runCatching { films.images(item.kinopoiskId) }.getOrDefault(emptyList())
            FeedItemExtras(
                genres = details?.genres?.mapNotNull { it.genre?.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                description = details?.shortDescription ?: details?.description?.let(::stripMarkup),
                stills = images.mapNotNull { it.imageUrl ?: it.previewUrl }.take(5),
                fullPosterUrl = details?.posterUrl ?: details?.coverUrl
            )
        }
        // Совсем пусто (сбой сети/лимит API) — валидация и обогащение честно проваливаются.
        val empty = extras.genres.isEmpty() && extras.stills.isEmpty() &&
            extras.description == null && extras.fullPosterUrl == null
        return if (empty) null else extras
    }

    /** Видео-слой текущей карточки: Rutube → YouTube-трейлер → только постер. Для 18+ сразу постер. */
    fun ensureClip(item: FeedItem) {
        val id = item.kinopoiskId
        if (id in uiState.clipStates || id in inFlightClips) return
        if (item.isAdultContent) {
            uiState = uiState.copy(clipStates = uiState.clipStates + (id to FeedClipState.PosterOnly))
            return
        }
        inFlightClips.add(id)
        uiState = uiState.copy(clipStates = uiState.clipStates + (id to FeedClipState.Loading))
        viewModelScope.launch(Dispatchers.IO) {
            val state = resolveClip(item)
            inFlightClips.remove(id)
            uiState = uiState.copy(clipStates = uiState.clipStates + (id to state))
        }
    }

    private suspend fun resolveClip(item: FeedItem): FeedClipState = withContext(Dispatchers.IO) {
        // 1) Rutube: нативный HLS без WebView; перебираем оба названия.
        val rutube = runCatching {
            RutubeClipSource.findClip(title = item.title, originalTitle = item.originalTitle)
        }.getOrElse { Log.w(TAG, "rutube failed: ${it.javaClass.simpleName}"); null }
        if (rutube != null) return@withContext FeedClipState.RutubeReady(rutube.hlsUrl, rutube.thumbnailUrl)

        // 2) Официальный трейлер из KP /videos — только для настоящих KP-id: у синтетических
        //    аниме-id (>=ANIME_ID_OFFSET) эндпоинт отвечает 400, это чистый спам запросов.
        if (!hd.kinoshka.app.data.feed.isAnimeId(item.kinopoiskId)) {
            val videos = runCatching { films.videos(item.kinopoiskId) }.getOrDefault(emptyList())
            val ytKey = videos
                .mapNotNull { it.url }
                .firstOrNull { it.contains("youtu", ignoreCase = true) }
                ?.let { url -> youTubeKey(url) ?: extractLooseYouTubeKey(url) }
            if (ytKey != null) return@withContext FeedClipState.YouTubeReady(ytKey)
        }

        // 3) Фолбэк — анимированный постер.
        FeedClipState.PosterOnly
    }

    /** Ленивый фолбэк: последний путь сегмента, похожий на YouTube-ключ (11 символов). */
    private fun extractLooseYouTubeKey(url: String): String? =
        url.substringAfterLast('/').substringBefore('?')
            .takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }

    /**
     * Лайк/дизлайк с тумблером: повторный тот же голос ОТМЕНЯЕТ его (реверс вклада).
     * Смена голоса реверсирует прежний и применяет новый. Карточка остаётся в ленте.
     * Вкус пишется в вектор СВОЕГО раздела — аниме-лайки не путаются с фильмами.
     */
    fun react(item: FeedItem, liked: Boolean) {
        val previous = uiState.reactions[item.kinopoiskId]
        val turningOff = previous == liked
        val chip = uiState.selectedChip
        val taste = tastes.getValue(chip)
        val cachedGenres = uiState.extras[item.kinopoiskId]?.genres.orEmpty()
        uiState = uiState.copy(
            reactions = if (turningOff) uiState.reactions - item.kinopoiskId
            else uiState.reactions + (item.kinopoiskId to liked)
        )
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = runCatching { repository.tasteContextFor(item.kinopoiskId) }
                .onFailure { Log.w(TAG, "react context failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            val genres = cachedGenres.ifEmpty { ctx?.genres.orEmpty() }
                .map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
            val countries = ctx?.countries.orEmpty()
            val decade = ctx?.year?.let(interests::decadeOf)

            // Реверс прежнего голоса (лайк вносил +0.5, дизлайк −0.8).
            if (previous != null) {
                interests.applyVoteDelta(genres, countries, decade, if (previous) -InterestProfileStore.LIKE_DELTA else -InterestProfileStore.DISLIKE_DELTA)
            }
            if (!turningOff) {
                interests.applyVoteDelta(genres, countries, decade, if (liked) InterestProfileStore.LIKE_DELTA else InterestProfileStore.DISLIKE_DELTA)
            }

            // Вектор вкуса (SAR + центроид): голос по КОНКРЕТНОМУ тайтлу, не по жанрам в среднем.
            val features = TasteFeatures(
                dims = buildSet {
                    genres.forEach { add("g:$it") }
                    countries.forEach { add("c:$it") }
                    decade?.let { add("d:$it") }
                    add(if (item.isAnime) "t:ANIME" else if (item.contentType == "MOVIE") "t:MOVIE" else "t:SERIES")
                },
                franchiseKey = franchiseKeyOf(item.title),
                ratingPrior = item.rating?.let { ((it - 6.5) / 5.0).coerceIn(-0.4, 0.4) } ?: 0.0
            )
            if (turningOff && previous != null) {
                taste.undoVote(item.kinopoiskId, features, previous)
            } else {
                taste.applyVote(item.kinopoiskId, features, liked)
            }

            // Лайк = тайтл источник «Похожих» + запись для списка «мои лайки».
            if (!turningOff && liked) {
                interests.addLikedTitle(hd.kinoshka.app.data.feed.LikedTitle(item.kinopoiskId, item.title, genres.take(4)))
            } else if (turningOff || !liked) {
                interests.removeLikedTitle(item.kinopoiskId)
            }
            seedIdsCache = null

            FeedDiagnostics.record(
                when {
                    turningOff -> "голос снят «${item.title}»"
                    else -> "голос ${if (liked) "+" else "−"} «${item.title}» [$chip]" +
                        genres.take(3).joinToString(prefix=" [", postfix="]")
                }
            )
            FeedDiagnostics.maybeAutoWrite(appContext, interests)
        }
    }

    /** Отчёт о вкусах и решениях движка текстом + запись файла; открывает системный шаринг. */
    fun shareDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { FeedDiagnostics.buildReport(appContext, interests) }
                .getOrElse { "Не удалось собрать диагностику: ${it.message}" }
            runCatching {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(Intent.createChooser(intent, "Диагностика рекомендаций"))
            }
        }
    }

    fun clearError() {
        uiState = uiState.copy(errorMessage = null)
    }

    private fun stripMarkup(raw: String): String = raw
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\[[^]]+]"), "")
        .replace(Regex("&nbsp;|&amp;|&lt;|&gt;|&quot;|#39;"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    companion object {
        private const val TAG = "FeedViewModel"
        private const val SEED_MAX = 8
        private const val MIN_BATCH = 12
        private const val MAX_PAGES_PER_LOAD = 10
        private const val RESCUE_PAGES_MAX = 4

        /** Жёсткий порог рейтинга Кинопоиска: пустышки и мусор в ленту не проходят. */
        private const val MIN_KP_RATING = 6.5

        /** Сколько кандидатов проверяется за один цикл добора. */
        private const val VALIDATION_BATCH = 28

        /** Параллельная валидация: детали+постер одновременно. */
        private const val VALIDATION_CONCURRENCY = 8

        private const val ENRICH_TIMEOUT_MS = 25_000L

        /** Порядок первичного опроса вкусов по разделам. */
        private val ONBOARDING_ORDER = listOf(FeedChip.FILMS, FeedChip.SERIES, FeedChip.ANIME)

        /** Достаёт ключ YouTube-ролика из любых форматов ссылок KP API. */
        fun youTubeKey(url: String): String? {
            val patterns = listOf(
                Regex("[?&]v=([A-Za-z0-9_-]{6,})"),
                Regex("youtu\\.be/([A-Za-z0-9_-]{6,})"),
                Regex("embed/([A-Za-z0-9_-]{6,})")
            )
            for (pattern in patterns) {
                pattern.find(url)?.groupValues?.get(1)?.let { return it }
            }
            return null
        }
    }
}

class FeedViewModelFactory(
    private val context: Context,
    private val films: FilmsRepository,
    private val anime: AnimeRepository,
    private val userState: UserStateStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FeedViewModel(context, films, anime, userState) as T
}
