package hd.kinoshka.app.data.feed

import android.util.Log
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.data.model.ShikimoriAnimeItem
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Движок рекомендательного фида (тестовая функция), v3.
 *
 * Персонализация ПО РАЗДЕЛАМ: у фильмов, сериалов, мультиков, аниме и хентая свой
 * вектор вкуса и свои источники; голоса пишутся в вектор раздела тайтла.
 *
 *  - [refreshInterests] раз в 12ч дообогащает историю/библиотеку жанрами через details()
 *    и учит веса (статус + оценка) в InterestProfileStore; лайки/дизлайки корректируют их же.
 *  - Страницы строятся РОТАЦИЕЙ: топ-жанры профиля × порядки сортировки × окна годов,
 *    поэтому последовательные страницы почти не повторяются, а пул не ограничен одной выдачей.
 *  - Чипс ALL — не отдельный раздел, а общий показ: партии фильмов/сериалов/мультиков/
 *    аниме ранжируются векторами СВОИХ разделов и чередуются в одном списке (хентай —
 *    только в своей вкладке 18+). Собственного рейтинга у ALL нет.
 *  - Исключается всё из истории/библиотеки пользователя и ранее показанное во фиде.
 */
class FeedRepository(
    private val films: FilmsRepository,
    private val anime: AnimeRepository,
    private val userState: UserStateStore,
    private val interests: InterestProfileStore,
    /** Вектор вкуса своего раздела у каждого чипса — аниме-вкусы не путаются с фильмами. */
    private val tasteOf: (FeedChip) -> TasteVectorStore
) {

    // ============================ интересы ============================

    /**
     * Обогащает профиль интересов жанрами недавней истории/библиотеки. Запросы деталей
     * идут ПАРАЛЛЕЛЬНО (по 4), чтобы первый экран фида не ждал десятки секунд.
     */
    suspend fun refreshInterests(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - interests.lastEnrichedAt() < ENRICH_TTL_MS) return@withContext

        val recentIds = LinkedHashSet<Int>()
        userState.getHistory().sortedByDescending { it.viewedAt }.forEach { recentIds.add(it.kinopoiskId) }
        userState.getProfiles().sortedByDescending { it.updatedAt }.forEach { recentIds.add(it.kinopoiskId) }
        if (recentIds.isEmpty()) {
            interests.setEnrichedAt(now)
            return@withContext
        }

        var learned = learnTastes(recentIds.take(ENRICH_MAX_TITLES))
        // Ретрай: при сетевом сбое первой пачки пробуем следующую порцию тайтлов.
        if (learned.genres.isEmpty() && recentIds.size > ENRICH_MAX_TITLES) {
            learned = learnTastes(recentIds.drop(ENRICH_MAX_TITLES).take(ENRICH_MAX_TITLES))
        }
        // Пустой результат НЕ кэшируем на полдня: следующий вход попробует снова.
        if (learned.isEmpty()) {
            Log.w(TAG, "enrichment produced no signals; will retry next open")
            return@withContext
        }
        interests.applyLearned(learned.genres)
        interests.applyLearnedCountries(learned.countries)
        interests.applyLearnedDecades(learned.decades)
        interests.setEnrichedAt(now)
        Log.i(TAG, "interests enriched: ${learned.genres.size}g/${learned.countries.size}c/${learned.decades.size}d signals")
    }

    /** Выученные вкусы одной пачки тайтлов: жанры + страны + десятилетия сразу. */
    private data class TasteSignals(
        val genres: Map<String, Double> = emptyMap(),
        val countries: Map<String, Double> = emptyMap(),
        val decades: Map<String, Double> = emptyMap()
    ) {
        fun isEmpty(): Boolean = genres.isEmpty() && countries.isEmpty() && decades.isEmpty()
    }

    /** Параллельная пачка: детали тайтлов тянутся одновременно (конкурсность 4). */
    private suspend fun learnTastes(ids: List<Int>): TasteSignals = kotlinx.coroutines.coroutineScope {
        val mutex = Mutex()
        val genres = HashMap<String, Double>()
        val countries = HashMap<String, Double>()
        val decades = HashMap<String, Double>()
        ids.chunked(ENRICH_CONCURRENCY).forEach { chunk ->
            chunk.map { id ->
                launch(Dispatchers.IO) {
                    val profile = userState.getProfile(id)
                    val weight = profile?.let { statusWeight(it.status, it.userRating) } ?: HISTORY_BASE_WEIGHT
                    val ctx = runCatching { tasteContextFor(id) }
                        .getOrElse { e ->
                            Log.w(TAG, "tasteContext($id) failed: ${e.javaClass.simpleName}")
                            null
                        } ?: return@launch
                    mutex.withLock {
                        ctx.genres.forEach { g -> genres[g] = (genres[g] ?: 0.0) + weight }
                        ctx.countries.forEach { c -> countries[c] = (countries[c] ?: 0.0) + weight * 0.8 }
                        interests.decadeOf(ctx.year)?.let { d ->
                            decades[d] = (decades[d] ?: 0.0) + weight * 0.6
                        }
                    }
                }
            }.joinAll()
        }
        TasteSignals(genres, countries, decades)
    }

    private fun statusWeight(status: UserFilmStatus?, userRating: Int?): Double {
        val base = when (status) {
            UserFilmStatus.WATCHING -> 1.0
            UserFilmStatus.REWATCHING -> 0.9
            UserFilmStatus.COMPLETED -> 0.8
            UserFilmStatus.PLANNED -> 0.3
            UserFilmStatus.ON_HOLD -> 0.1
            UserFilmStatus.DROPPED -> -1.2
            null -> HISTORY_BASE_WEIGHT
        }
        // Оценка 1–10: 5 нейтрально; 9-10 заметно тянут жанры вверх, 1-2 — вниз.
        val ratingBonus = userRating?.let { ((it - 5) * 0.4).coerceIn(-1.5, 1.5) } ?: 0.0
        return base + ratingBonus
    }

    /** Жанры/страны/год тайтла: KP details или Shikimori details для аниме-id. */
    data class TasteContext(val genres: List<String>, val countries: List<String>, val year: Int?)

    suspend fun tasteContextFor(id: Int): TasteContext = runCatching {
        if (isAnimeId(id)) {
            val d = anime.details(id - hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
            TasteContext(
                genres = d.genres.mapNotNull { it.russian?.trim()?.lowercase() },
                countries = listOf("япония"),
                year = d.airedOn?.take(4)?.toIntOrNull()
            )
        } else {
            val d = films.details(id)
            TasteContext(
                genres = d.genres.mapNotNull { it.genre?.trim()?.lowercase() },
                countries = d.countries.mapNotNull { it.country?.trim()?.lowercase() },
                year = d.year
            )
        }
    }.getOrDefault(TasteContext(emptyList(), emptyList(), null))

    /** Публичный доступ для обратной связи лайков: жанры конкретного тайтла. */
    suspend fun genresFor(id: Int): List<String> = withContext(Dispatchers.IO) { tasteContextFor(id).genres }

    // ============================ страницы ленты ============================

    /** Диагностическая метка «почему в ленте» на все карточки пачки. */
    private fun List<FeedItem>.withReason(reason: String): List<FeedItem> =
        map { it.copy(reason = reason) }

    /**
     * Страница кандидатов для чипса. [pageIndex] начинается с 1; каждая страница — новая
     * комбинация (жанр × сортировка × годы), поэтому добор не упирается в одну выдачу.
     * Показанное во фиде исключается ВСЕГДА — повторов нет вплоть до честного исчерпания.
     * [seedIds] — id недавно просмотренного для «похожих», подмешиваются в первые страницы.
     */
    suspend fun page(chip: FeedChip, pageIndex: Int, seedIds: List<Int> = emptyList()): List<FeedItem> =
        when (chip) {
            // «Всё» — общий показ партий всех разделов, каждый со своим рейтингом.
            FeedChip.ALL -> allMergedPage(pageIndex, seedIds)
            else -> withContext(Dispatchers.IO) {
                val raw = runCatching { loadPage(chip, pageIndex) }.getOrElse { e ->
                    Log.w(TAG, "page($chip,$pageIndex) failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
                val items = filterCandidates(raw)

                // Похожие к недавнему — в первые две страницы, поверх основного пула.
                if (pageIndex <= SEED_PAGES && seedIds.isNotEmpty()) {
                    val existing = items.map { it.kinopoiskId }.toSet()
                    val excluded = excludedIds()
                    val seeds = runCatching { seedItems(seedIds) }.getOrDefault(emptyList())
                        .asSequence()
                        .filter { it.isShowable(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) }
                        .filter { it.kinopoiskId !in excluded && it.kinopoiskId !in existing }
                        .take(if (pageIndex == 1) SEEDED_COUNT else SEEDED_COUNT / 2)
                        .toList()
                        .withReason("сид по похожим к недавнему")
                    // Франшизы сидов резервируются: основной пул не подсовывает их же сезоны.
                    val reserved = seeds.mapNotNull { franchiseKeyOf(it.title) }.toSet()
                    return@withContext seeds + rankBatch(chip, items, reserved)
                }
                rankBatch(chip, items)
            }
        }

    /**
     * Общая страница «Всё»: сырые партии разделов (кроме хентая) грузятся ПАРАЛЛЕЛЬНО,
     * каждая ранжируется вектором СВОЕГО раздела, затем очереди чередуются по кругу —
     * порядок внутри раздела сохраняется, а список выглядит единым. Сиды «похожих»
     * (только первые страницы) раскладываются по своим разделам.
     */
    private suspend fun allMergedPage(pageIndex: Int, seedIds: List<Int>): List<FeedItem> =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                val buckets = FeedChip.ALL_MIX.associateWith { chip ->
                    async {
                        runCatching { rawSectionPage(chip, pageIndex) }.getOrElse { e ->
                            Log.w(TAG, "all page($chip,$pageIndex) failed: ${e.javaClass.simpleName}")
                            emptyList()
                        }
                    }.await()
                }.mapValues { (_, raw) -> filterCandidates(raw) }

                var prepared = buckets
                val reserved = mutableSetOf<String>()
                val seedBudget = if (pageIndex <= SEED_PAGES && seedIds.isNotEmpty()) {
                    if (pageIndex == 1) SEEDED_COUNT else SEEDED_COUNT / 2
                } else 0
                if (seedBudget > 0) {
                    val excluded = excludedIds()
                    val seeds = runCatching { seedItems(seedIds) }.getOrDefault(emptyList())
                        .asSequence()
                        .filter { it.isShowable(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) }
                        .filter { it.kinopoiskId !in excluded }
                        .take(seedBudget)
                        .toList()
                    reserved += seeds.mapNotNull { franchiseKeyOf(it.title) }
                    val seedIdsSet = seeds.map { it.kinopoiskId }.toSet()
                    val bySection = seeds.groupBy { sectionOf(it) }
                    prepared = buckets.mapValues { (chip, items) ->
                        (bySection[chip].orEmpty() + items.filterNot { it.kinopoiskId in seedIdsSet })
                            .distinctBy { it.kinopoiskId }
                    }
                }

                val ranked = prepared.mapValues { (chip, items) -> rankBatch(chip, items, reserved) }
                val queues = FeedChip.ALL_MIX.map { ArrayDeque(ranked[it].orEmpty()) }
                val merged = mutableListOf<FeedItem>()
                while (queues.any { it.isNotEmpty() }) {
                    queues.forEach { q -> q.removeFirstOrNull()?.let { merged += it } }
                }
                merged
            }
        }

    /** Сырая страница одного раздела (без фильтров и ранжирования) — источник для «Всё». */
    private suspend fun rawSectionPage(chip: FeedChip, pageIndex: Int): List<FeedItem> = when (chip) {
        FeedChip.FILMS -> kpInterestPage(pageIndex - 1, type = "MOVIE", label = "Фильмы", section = chip)
        FeedChip.SERIES -> kpInterestPage(pageIndex - 1, type = "TV_SERIES", label = "Сериалы", section = chip)
        FeedChip.CARTOONS -> kpCartoonsPage(pageIndex)
        FeedChip.ANIME -> shikiRankedPage(pageIndex)
        else -> emptyList()
    }

    /**
     * Раздел тайтла для маршрутизации голосов: явный section, иначе вывод по данным
     * карточки (сидам «похожих» и спас-популярному section не проставляется).
     * Метка 18+ всегда означает хентай, аниме-id — аниме.
     */
    fun sectionOf(item: FeedItem): FeedChip =
        item.section ?: when {
            item.isAdultContent -> FeedChip.HENTAI
            item.isAnime -> FeedChip.ANIME
            item.isSeriesLike -> FeedChip.SERIES
            else -> FeedChip.FILMS
        }

    /** Общие отсечения партии: битые id, история/виденное, без постера/анонсы, нелюбимые страны. */
    private suspend fun filterCandidates(raw: List<FeedItem>): List<FeedItem> {
        val excluded = excludedIds()
        val hideRussian = runCatching { userState.getUserPreferences().hideRussianContent }.getOrDefault(false)
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val dislikedCountries = runCatching { dislikedCountryNames() }.getOrDefault(emptySet())
        return raw
            .filter { it.kinopoiskId > 0 && it.kinopoiskId !in excluded }
            .filter { it.isShowable(currentYear) } // без постера/инфо и невышедшие — мимо
            .distinctBy { it.kinopoiskId }
            // Страна, которую стабильно листаешь мимо (вес < порога), больше не приходит.
            .filterNot { item -> item.countries.any { it in dislikedCountries } }
            .filterNot { hideRussian && it.isRussian }
    }

    /**
     * Ранжирование партии: скор вкуса (центроид + SAR-похожесть на конкретные лайки +
     * приоритет рейтинга + шум Томпсона), затем MMR-отбор (λ=0.7) — релевантность против
     * похожести на уже отобранные. Второй тайтл франшизы в партию не проходит вовсе:
     * «4 ванпанчмена подряд» больше невозможно.
     */
    private fun rankBatch(
        chip: FeedChip,
        items: List<FeedItem>,
        reservedFranchises: Set<String> = emptySet()
    ): List<FeedItem> {
        if (items.size <= 1) return items
        val taste = tasteOf(chip)
        val feats = items.associate { it.kinopoiskId to tasteFeaturesOf(it) }
        val scores = feats.mapValues { (_, f) -> taste.scoreOf(f) }
        val rest = ArrayDeque(items.sortedByDescending { scores[it.kinopoiskId]!!.total })
        val selectedDims = mutableListOf<Set<String>>()
        val usedFranchises = reservedFranchises.toMutableSet()
        val ranked = mutableListOf<FeedItem>()

        while (rest.isNotEmpty()) {
            var bestIdx = -1
            var bestVal = Double.NEGATIVE_INFINITY
            for ((i, cand) in rest.withIndex()) {
                val f = feats[cand.kinopoiskId]!!
                if (f.franchiseKey != null && f.franchiseKey in usedFranchises && rest.size > 1) continue
                val rel = scores[cand.kinopoiskId]!!.total
                val maxSim = selectedDims.maxOfOrNull { taste.jaccard(f.dims, it) } ?: 0.0
                val mmr = MMR_LAMBDA * rel - (1 - MMR_LAMBDA) * maxSim
                if (mmr > bestVal) {
                    bestVal = mmr
                    bestIdx = i
                }
            }
            val picked = rest.removeAt(if (bestIdx >= 0) bestIdx else 0)
            val pf = feats[picked.kinopoiskId]!!
            pf.franchiseKey?.let { usedFranchises += it }
            selectedDims += pf.dims
            val sc = scores[picked.kinopoiskId]!!
            ranked += picked.copy(
                reason = listOfNotNull(
                    picked.reason,
                    "score=%.2f".format(sc.total),
                    "исследование".takeIf { sc.explored }
                ).joinToString(" · ")
            )
        }
        return ranked
    }

    /**
     * Спасательная страница: чистое популярное KP с теми же отсечениями (история,
     * виденное во фиде, нелюбимые страны). Добирает хвост, когда ротация источников
     * упёрлась в виденное и партия выходит тощей.
     */
    suspend fun rescuePopular(pageIndex: Int): List<FeedItem> = withContext(Dispatchers.IO) {
        val raw = runCatching { kpPopularPage(pageIndex) }.getOrElse { emptyList() }
        filterCandidates(raw)
    }

    private suspend fun loadPage(chip: FeedChip, pageIndex: Int): List<FeedItem> {
        val slot = (pageIndex - 1).coerceAtLeast(0)
        return when (chip) {
            FeedChip.FILMS -> kpInterestPage(slot, type = "MOVIE", label = "Фильмы", section = chip)
            FeedChip.SERIES -> kpInterestPage(slot, type = "TV_SERIES", label = "Сериалы", section = chip)
            FeedChip.CARTOONS -> kpCartoonsPage(pageIndex)
            FeedChip.ANIME -> shikiRankedPage(pageIndex)
            // Аниме-вкладка разрешает этти (censored=false); хентай вычищает каталог-гард.
            FeedChip.HENTAI -> shikiHentaiPage(pageIndex)
            // «Всё» строится allMergedPage — сюда не доходит (см. page()).
            FeedChip.ALL -> emptyList()
        }
    }

    /**
     * Интерес-страница KP: слот раскладывается в (apiPage × жанр × порядок × окно годов),
     * каждый чётный слот дополнительно зажимает выдачу любимой страной. Ранние слоты дают
     * лучшие жанры с RATING, дальше — разнообразие без потери качества.
     * [label] — имя чипса для диагностической метки, [section] — проставляемый раздел.
     */
    private suspend fun kpInterestPage(
        slot: Int,
        type: String,
        label: String,
        section: FeedChip
    ): List<FeedItem> {
        val genres = interestGenreIds()
        if (genres.isEmpty()) {
            // Даже справочник фильтров недоступен — популярное как последний рубеж.
            return if (slot < 6) kpPopularPage(slot + 1) else emptyList()
        }
        val g = genres.size
        val apiPage = slot / g + 1
        // Умный выбор жанра: не механическая ротация, а взвешенное сэмплирование по
        // центрированным вкусам (экспоненциальная температура 0.8); каждый 5-й слот —
        // управляемая разведка равномерно по пулу.
        val genrePair = if (slot % EXPLORATION_MODULO == EXPLORATION_MODULO - 1) {
            genres[Random.nextInt(genres.size)]
        } else {
            val weightsMap = interests.centeredWeights()
            val entries = genres.map { g2 -> g2 to (weightsMap[g2.second] ?: 0.15) }
            weightedPick(entries) ?: genres[slot % g]
        }
        val (genreId, genreName) = genrePair
        val order = ORDERS[slot % ORDERS.size]
        val windows = orderedYearWindows()
        val years = windows[(slot / (g * ORDERS.size)) % windows.size]
        // Каждый нечётный слот уточняет страну — выбор тоже взвешенный по вкусам.
        val topCountry = if (slot % 2 == 1) sampledPositiveCountry() else null
        val page = kpSearchTyped(
            order = order,
            typeHint = type,
            genreId = genreId,
            yearFrom = years.first,
            yearTo = years.second,
            page = apiPage,
            adult = false,
            countryId = topCountry?.first
        )
        // Узкое окно/нишевый жанр могут дать пусто — страховка популярным.
        return page.ifEmpty { kpPopularPage(apiPage) }
            .map { it.copy(sourceGenre = genreName, section = section) }
            .withReason(
                buildList {
                    add("$label · жанр $genreName")
                    interests.weights()[genreName]?.let { add("w=${"%.1f".format(it)}") }
                    add(order)
                    add("${years.first}–${years.second}")
                    topCountry?.let { add("страна ${it.second}") }
                }.joinToString(" · ")
            )
    }

    /** Чипс «Мультики»: жанр «мультфильм» Кинопоиска — полнометражка и мульт-сериалы вместе. */
    private suspend fun kpCartoonsPage(pageIndex: Int): List<FeedItem> {
        val genreId = cartoonGenreId() ?: return emptyList()
        val slot = pageIndex - 1
        val order = ORDERS[slot % ORDERS.size]
        val windows = orderedYearWindows()
        val years = windows[(slot / ORDERS.size) % windows.size]
        return kpSearchTyped(
            order = order,
            typeHint = null,
            genreId = genreId,
            yearFrom = years.first,
            yearTo = years.second,
            page = pageIndex,
            adult = false
        ).withReason("Мультики · $order · ${years.first}–${years.second}")
            .map { it.copy(sourceGenre = "мультфильм", section = FeedChip.CARTOONS) }
    }

    /**
     * Поиск по /films v2.2 с самолечением параметра type: значение «MOVIE» на текущем API
     * отвечает 400, тогда как TV_SERIES проходит (проверено логами устройства). При 400
     * перебираем кандидатов MOVIE → FILM → без типа; рабочий вариант живёт весь процесс.
     */
    private suspend fun kpSearchTyped(
        order: String,
        typeHint: String?,
        genreId: Int,
        yearFrom: Int,
        yearTo: Int,
        page: Int,
        adult: Boolean,
        countryId: Int? = null
    ): List<FeedItem> {
        var attempt = 0
        while (true) {
            // Пробник только для «MOVIE» (единственное значение, которое текущий API
            // отвечает 400); «TV_SERIES» проходит и обязан идти как есть — иначе запросы
            // фильмов и сериалов схлопываются в один и тот же URL.
            val typeValue: String? = when (typeHint) {
                null -> null
                "MOVIE" -> KpTypeProbe.current()
                else -> typeHint
            }
            try {
                return films.search(
                    order = order,
                    type = typeValue,
                    genreId = genreId,
                    countryId = countryId,
                    yearFrom = yearFrom,
                    yearTo = yearTo,
                    page = page
                ).map { it.toFeedItem(adult) }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 400 && attempt < 3) {
                    Log.w(TAG, "kp search rejected type=${typeValue ?: "<none>"}, trying next candidate")
                    KpTypeProbe.report400()
                    attempt++
                    continue
                }
                throw e
            }
        }
    }

    private object KpTypeProbe {
        private val CANDIDATES = listOf<String?>("MOVIE", "FILM", null)
        @Volatile private var index = 0

        fun current(): String? = CANDIDATES[index]

        fun report400() {
            synchronized(this) { if (index < CANDIDATES.lastIndex) index++ }
        }
    }

    /** Взрослые жанры в ключах весов — в обычную подачу им делать нечего. */
    private fun isAdultGenreKey(key: String): Boolean {
        val n = key.trim().lowercase()
        return n.contains("хентай") || n.contains("эротик") || n.contains("для взрослых") ||
            n == "этти" || n.contains("ecchi")
    }

    /**
     * Жанры для интерес-подборки: (id, имя) по центрированным весам профиля, а если он
     * пуст (первый запуск или сбой обогащения) — проверенный набор массовых жанров из
     * справочника KP. Аниме и мультики исключены: у них собственные чипсы; взрослые
     * жанры исключены всегда: у них собственные чипсы 18+/Хентай.
     */
    private suspend fun interestGenreIds(): List<Pair<Int, String>> {
        val weights = interests.centeredWeights().filterValues { it > 0 }
            .filterKeys { it != "аниме" && it != "мультфильм" && !isAdultGenreKey(it) }
        val filters = runCatching { films.filters() }.getOrNull() ?: return emptyList()
        if (weights.isEmpty()) {
            val preferred = DEFAULT_GENRE_NAMES
            val matched = filters.genres.filter { it.genre?.trim()?.lowercase() in preferred }.map { (it.id to it.genre!!.trim().lowercase()) }
            return matched.ifEmpty { filters.genres.take(TOP_GENRES).map { (it.id to (it.genre ?: "жанр").trim().lowercase()) } }
        }
        return weights.entries
            .sortedByDescending { it.value }
            .mapNotNull { w ->
                filters.genres.firstOrNull { it.genre?.trim()?.lowercase() == w.key }
                    ?.let { f -> f.id to w.key }
            }
            .distinct()
            .take(TOP_GENRES)
    }

    /** Id жанра «мультфильм» для одноимённого чипса. */
    private suspend fun cartoonGenreId(): Int? {
        val filters = runCatching { films.filters() }.getOrNull() ?: return null
        return filters.genres.firstOrNull { it.genre?.trim()?.lowercase() == "мультфильм" }?.id
    }

    /** Взвешенный выбор страны среди позитивных вкусов — разнообразие вместо одного фаворита. */
    private suspend fun sampledPositiveCountry(): Pair<Int, String>? {
        val positive = interests.centeredCountryWeights().filterValues { it > 0 }
            .filterKeys { !it.contains("россия") }
            .map { it.key to it.value }
        val chosen = weightedPick(positive) ?: return null
        val filters = runCatching { films.filters() }.getOrNull() ?: return null
        val match = filters.countries.firstOrNull { it.country?.trim()?.lowercase() == chosen } ?: return null
        return match.id to chosen
    }

    /**
     * Взвешенное сэмплирование: вероятность ∝ exp(вес / температура). Тёплые вкусы
     * выпадают часто, холодные — редко, но не никогда.
     */
    private fun <T> weightedPick(entries: List<Pair<T, Double>>): T? {
        if (entries.isEmpty()) return null
        fun weight(w: Double) = kotlin.math.exp(w / SAMPLING_TEMP).coerceAtLeast(1e-6)
        var roll = Random.nextDouble(entries.sumOf { weight(it.second) })
        for ((value, w) in entries) {
            roll -= weight(w)
            if (roll <= 0) return value
        }
        return entries.last().first
    }

    /** Страна заметно ниже среднего вкуса → стабильно листаешь мимо, выкидываем из выдачи. */
    private suspend fun dislikedCountryNames(): Set<String> =
        interests.centeredCountryWeights().filterValues { it < COUNTRY_DISLIKE_THRESHOLD }.keys

    /**
     * Окна годов, упорядоченные по десятилетним весам профиля (косвенный возраст):
     * смотрит классику — классика поднимается наверх, молодёжь — новинки.
     */
    private suspend fun orderedYearWindows(): List<Pair<Int, Int>> {
        val decades = interests.centeredDecadeWeights()
        if (decades.isEmpty()) return YEAR_WINDOWS
        fun score(window: Pair<Int, Int>): Double {
            var s = 0.0
            var decadeStart = window.first / 10 * 10
            while (decadeStart <= window.second) {
                s += decades["${decadeStart}s"] ?: 0.0
                decadeStart += 10
            }
            return s
        }
        return YEAR_WINDOWS.sortedByDescending(::score)
    }

    private suspend fun kpPopularPage(pageIndex: Int): List<FeedItem> =
        films.popular(collectionType = "TOP_POPULAR_ALL", page = pageIndex)
            .map { it.toFeedItem(false) }
            .withReason("KP популярное")

    /**
     * Shikimori ranked (качество вместо голой популярности). Обычная аниме-подача:
     * этти разрешён (censored=false), хентай отсекается каталогом на валидации.
     */
    private suspend fun shikiRankedPage(pageIndex: Int): List<FeedItem> {
        val loaded = anime.search(
            order = "ranked",
            scoreFrom = SHIKI_MIN_SCORE,
            genreId = null,
            censored = false,
            page = pageIndex
        )
        return loaded.map { it.toFeedItem(adult = false) }
            .ifEmpty {
                // ranked+score может быть пуст для узкой страницы — повторяем по популярности.
                anime.search(order = "popularity", censored = false, page = pageIndex)
                    .map { it.toFeedItem(adult = false) }
            }
            .map { it.copy(section = FeedChip.ANIME) }
            .withReason("Shikimori ranked · score≥$SHIKI_MIN_SCORE · этти разрешён")
    }

    private suspend fun shikiHentaiPage(pageIndex: Int): List<FeedItem> {
        val genreId = resolveShikimoriGenre("hentai") ?: SHIKI_HENTAI_FALLBACK
        val loaded = anime.search(order = "ranked", genreId = genreId, censored = false, page = pageIndex)
        return loaded.map { it.toFeedItem(adult = true) }
            .ifEmpty {
                anime.search(order = "popularity", genreId = genreId, censored = false, page = pageIndex)
                    .map { it.toFeedItem(adult = true) }
            }
            // Штамп жанра: вектор хентай-раздела учится на своём измерении.
            .map { it.copy(sourceGenre = "хентай", section = FeedChip.HENTAI) }
            // Теги каталога hanime: показ на карточке + измерения вкуса 18+-раздела.
            .map { item ->
                val tags = runCatching {
                    hd.kinoshka.app.data.source.HentaiStreamResolver.hentaiTags(item.originalTitle, item.title)
                }.getOrDefault(emptyList())
                item.copy(tags = tags)
            }
            .withReason("Shikimori hentai")
    }

    /** «Похожие» для сидирования первых страниц: KP similars / Shikimori related по недавнему.
     *  На каждый источник-сид берём не больше SEED_PER_SOURCE тайтлов — иначе франшиза
     *  просмотренного заваливает первые страницы всеми своими сезонами. */
    private suspend fun seedItems(seedIds: List<Int>): List<FeedItem> {
        val result = mutableListOf<FeedItem>()
        for (id in seedIds) {
            if (result.size >= SEEDED_COUNT * 2) break
            runCatching {
                if (isAnimeId(id)) {
                    anime.related(id - hd.kinoshka.app.data.model.ANIME_ID_OFFSET)
                        .mapNotNull { it.anime }
                        .filter { it.kind != null }
                        .map { it.toFeedItem(adult = false) }
                        .take(SEED_PER_SOURCE)
                } else {
                    films.similars(id).map { link ->
                        FeedItem(
                            kinopoiskId = link.id,
                            title = link.nameRu ?: link.nameOriginal ?: "Фильм",
                            originalTitle = link.nameOriginal,
                            posterUrl = link.posterUrlPreview ?: link.posterUrl,
                            year = link.year,
                            rating = null,
                            genres = emptyList(),
                            shortDescription = link.relationType,
                            isAnime = false,
                            isAdultContent = false,
                            contentType = link.type
                        )
                    }.take(SEED_PER_SOURCE)
                }
            }.getOrNull()?.let { result += it }
        }
        return result.distinctBy { it.kinopoiskId }
    }

    // ============================ вспомогательное ============================

    private suspend fun excludedIds(): Set<Int> = runCatching {
        userState.getHistory().map { it.kinopoiskId }.toSet() +
            userState.getProfiles().map { it.kinopoiskId }.toSet() +
            interests.seenFeedIds()
    }.getOrDefault(emptySet())

    /**
     * Id жанра Shikimori по латинскому имени (ecchi/hentai). Резолвится один раз через
     * открытый GET /api/genres; при сбое — известные константы.
     */
    private var shikimoriGenreCache: Map<String, Int>? = null

    private suspend fun resolveShikimoriGenre(latinName: String): Int? = withContext(Dispatchers.IO) {
        shikimoriGenreCache?.get(latinName)?.let { return@withContext it }
        val body = runCatching {
            val client = java.net.URL(SHIKI_GENRES_URL).openConnection() as java.net.HttpURLConnection
            client.connectTimeout = 8000
            client.readTimeout = 8000
            client.setRequestProperty("User-Agent", SHIKI_UA)
            try {
                client.inputStream.bufferedReader().use { it.readText() }
            } finally {
                client.disconnect()
            }
        }.getOrNull() ?: return@withContext FALLBACK_GENRES[latinName]

        val parsed = runCatching {
            val array = org.json.JSONArray(body)
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim().lowercase()
                    val id = obj.optInt("id", -1)
                    if (name.isNotEmpty() && id > 0) put(name, id)
                }
            }
        }.getOrDefault(emptyMap())
        if (parsed.isNotEmpty()) shikimoriGenreCache = parsed
        parsed[latinName] ?: FALLBACK_GENRES[latinName]
    }

    private fun FilmItem.toFeedItem(adult: Boolean): FeedItem {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return FeedItem(
            kinopoiskId = kinopoiskId,
            title = nameRu ?: nameOriginal ?: "Фильм",
            originalTitle = nameOriginal?.takeIf { it != (nameRu ?: nameOriginal) },
            posterUrl = posterUrlPreview,
            year = year,
            rating = ratingKinopoisk,
            genres = emptyList(),
            shortDescription = null,
            isAnime = false,
            isAdultContent = adult,
            isRussian = countries.any { it.country.equals("Россия", ignoreCase = true) },
            contentType = null,
            // Страны в нижнем регистре — для авто-фильтра «не смотрю эту страну».
            countries = countries.mapNotNull { it.country?.trim()?.lowercase() },
            // Анонс: год в будущем или совсем свежий год без оценки и без данных.
            upcoming = (year != null && year > currentYear) || (year == currentYear && ratingKinopoisk == null && posterUrlPreview.isNullOrBlank())
        )
    }

    private fun ShikimoriAnimeItem.toFeedItem(adult: Boolean): FeedItem {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val itemYear = airedOn?.take(4)?.toIntOrNull()
        return FeedItem(
            kinopoiskId = id + hd.kinoshka.app.data.model.ANIME_ID_OFFSET,
            title = displayTitle,
            originalTitle = name?.takeIf { it != displayTitle },
            posterUrl = posterUrl,
            year = itemYear,
            rating = score?.toDoubleOrNull(),
            genres = emptyList(),
            shortDescription = null,
            isAnime = true,
            isAdultContent = adult,
            isRussian = false,
            contentType = when (kind?.lowercase()) {
                "movie" -> "MOVIE"
                "tv" -> "TV_SERIES"
                else -> kind?.uppercase()
            },
            // Аниме — почти всегда япония: даём профилю стран сигнал и для аниме.
            countries = listOf("япония"),
            // Shikimori честно помечает анонсы статусом anons; плюс будущий год.
            upcoming = status.equals("anons", ignoreCase = true) || (itemYear != null && itemYear > currentYear)
        )
    }

    companion object {
        private const val TAG = "FeedRepository"
        private const val ENRICH_TTL_MS = 6L * 60L * 60L * 1000L
        private const val ENRICH_MAX_TITLES = 10
        private const val ENRICH_CONCURRENCY = 4
        private const val HISTORY_BASE_WEIGHT = 0.5
        private const val SEEDED_COUNT = 6
        private const val SEED_PAGES = 2
        private const val TOP_GENRES = 4
        private const val SHIKI_MIN_SCORE = 7

        /** Массовые жанры-фолбэки, когда профиль интересов ещё пуст. */
        private val DEFAULT_GENRE_NAMES = setOf(
            "фантастика", "боевик", "триллер", "комедия", "драма", "приключения", "мультфильм"
        )
        private const val SHIKI_GENRES_URL = "https://shikimori.io/api/genres"
        private const val SHIKI_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val SHIKI_ECCHI_FALLBACK = 24
        private const val SHIKI_HENTAI_FALLBACK = 25
        private val FALLBACK_GENRES = mapOf("ecchi" to SHIKI_ECCHI_FALLBACK, "hentai" to SHIKI_HENTAI_FALLBACK)

        /** Ротация сортировок KP: качество → народность → свежесть. */
        private val ORDERS = listOf("RATING", "NUM_VOTE", "YEAR")

        /** Порог нелюбимой страны: центрированный вес ниже него — стабильно не смотрит. */
        private const val COUNTRY_DISLIKE_THRESHOLD = -2.0

        /** MMR λ: 0.7 = релевантность доминирует, но разнообразие режет монотонность. */
        private const val MMR_LAMBDA = 0.7

        /** Максимум тайтлов одного сида («похожих») в партии. */
        private const val SEED_PER_SOURCE = 2

        /** Температура взвешенного сэмплирования запросов: <1 = сильный перекос к любимому. */
        private const val SAMPLING_TEMP = 0.8

        /** Каждый N-й слот выдачи — разведка равномерно по пулу жанров. */
        private const val EXPLORATION_MODULO = 5

        /** Окна годов: новые → нулевые → классика. */
        private val YEAR_WINDOWS = listOf(
            2018 to 2026,
            2005 to 2017,
            1990 to 2004
        )
    }
}
