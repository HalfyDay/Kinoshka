package hd.kinoshka.app.data.repo

import hd.kinoshka.app.data.model.FilmItem
import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.delay

/** Лимит API Кинопоиска: page максимум 20, выдача — не более 400 тайтлов. */
internal const val KP_MAX_SEARCH_PAGE = 20

/**
 * Есть ли ещё страницы после загруженной: по счётчику сервера, а не по пустоте
 * страницы. Пустая страница после клиентских фильтров (аниме/мультижанр/
 * мультфильмы) — НЕ конец выдачи. totalPages неизвестен (0) — старый фолбэк.
 * Верхняя граница — лимит API (дальше сервер отвечает 400).
 */
fun hasMorePages(loadedPage: Int, totalPages: Int, loadedNonEmpty: Boolean): Boolean {
    if (totalPages <= 0) return loadedNonEmpty
    return loadedPage < minOf(totalPages, KP_MAX_SEARCH_PAGE)
}

/** Диапазон лет одного ведра fan-out (null — без границы). */
data class YearBucket(val yearFrom: Int?, val yearTo: Int?)

/**
 * Десятилетия для добора мимо по-запросного лимита API (~100 тайтлов на запрос
 * с жанром при потолке 400): каждое ведро — свой подзапрос со своим лимитом.
 * Фильмы без года ни в одно ведро не попадают (см. комментарий в nextChunk).
 */
val DEFAULT_FANOUT_BUCKETS = listOf(
    YearBucket(null, 1969),
    YearBucket(1970, 1979),
    YearBucket(1980, 1989),
    YearBucket(1990, 1999),
    YearBucket(2000, 2009),
    YearBucket(2010, 2019),
    YearBucket(2020, null)
)

/** Пауза между запросами вёдер: лимит API — 5 запросов в секунду. */
private const val FANOUT_REQUEST_GAP_MS = 250L

/** Сколько подряд провалов ведра терпим, прежде чем списать его. */
private const val FANOUT_MAX_BUCKET_FAILURES = 3

/**
 * Добор выдачи по десятилетиям со слиянием в общий порядок.
 *
 * Один запрос с жанром отдаёт лишь ~сотню тайтлов, а жанр велик: опрашиваем
 * каждое десятилетие отдельно (у каждого свой лимит) и склеиваем раунды.
 * Порядок внутри чанка — честный по [order] (RATING/YEAR; NUM_VOTE клиентом
 * не склеить — в выдаче нет числа голосов, см. gate в VM).
 *
 * Стабильность списка: чанки только дописываются в хвост, показанные позиции
 * не ездят (идеальный глобальный порядок на стыках не гарантируется —
 * плата за отсутствие полного выкачивания).
 *
 * [fetch] — одна страница одного ведра (VM замыкает сюда searchPaged
 * с параметрами поиска); тесты подсовывают фейк.
 */
class GenreFanoutPaginator(
    private val order: String,
    private val buckets: List<YearBucket> = DEFAULT_FANOUT_BUCKETS,
    private val fetch: suspend (yearFrom: Int?, yearTo: Int?, page: Int) -> FilmSearchPage
) {
    private data class Source(
        val bucket: YearBucket,
        var page: Int = 0,
        var totalPages: Int = -1, // -1 — ещё не спрашивали
        var failures: Int = 0
    ) {
        fun hasMore(): Boolean {
            if (failures >= FANOUT_MAX_BUCKET_FAILURES) return false
            if (totalPages < 0) return true
            return hasMorePages(page, totalPages, true)
        }
    }

    private val sources = buckets.map(::Source)

    /** Подпись параметров поиска: VM сверяет перед использованием чужого пагинатора. */
    val signature: String = "$order|$buckets"

    val hasMore: Boolean get() = sources.any { it.hasMore() }

    /**
     * Следующий чанк (раунд страниц по вёдрам, отсортированный по [order]).
     * Пустой чанк при hasMore=true — все вёдра в этом раунде дали пусто
     * (разреженные клиентские фильтры): вызывающий добирает следующий раунд сам.
     */
    suspend fun nextChunk(): List<FilmItem> {
        val fetched = mutableListOf<FilmItem>()
        sources.forEachIndexed { index, source ->
            if (!source.hasMore()) return@forEachIndexed
            if (index > 0) delay(FANOUT_REQUEST_GAP_MS)
            val nextPage = source.page + 1
            val result = runCatching {
                fetch(source.bucket.yearFrom, source.bucket.yearTo, nextPage)
            }.getOrNull()
            if (result == null) {
                source.failures++
                KLog.w("FilmSearch", "fanout bucket ${source.bucket} page $nextPage failed (${source.failures})")
            } else {
                source.page = nextPage
                source.totalPages = result.totalPages
                source.failures = 0
                fetched += result.items
            }
        }
        // Фильмы без года выдачи (year == null) ни в одно ведро yearFrom/yearTo
        // не входят: сервер их отрезает границами. Это меньшинство (бездатные —
        // обычно малоизвестное), NUM_VOTE-путь без fan-out их по-прежнему показывает.
        KLog.i("FilmSearch", "fanout round done: +${fetched.size}, hasMore=$hasMore")
        return fetched.sortForOrder(order)
    }
}

/**
 * Порядок слияния чанка: RATING — по рейтингу КП, YEAR — по году, null —
 * в хвост. Тайбрейк по id для детерминированности.
 */
fun List<FilmItem>.sortForOrder(order: String): List<FilmItem> = when (order) {
    "YEAR" -> sortedWith(
        compareByDescending<FilmItem> { it.year ?: Int.MIN_VALUE }.thenBy { it.kinopoiskId }
    )
    else -> sortedWith(
        compareByDescending<FilmItem> { it.ratingKinopoisk ?: Double.MIN_VALUE }.thenBy { it.kinopoiskId }
    )
}
