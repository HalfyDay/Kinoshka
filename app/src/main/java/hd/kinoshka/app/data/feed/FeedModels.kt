package hd.kinoshka.app.data.feed

import hd.kinoshka.app.data.model.ANIME_ID_OFFSET

/**
 * Верхние чипсы фида. Хентай скрыт за гейтом 18+ до подтверждения возраста.
 *
 * Рекомендации строятся по разделам отдельно: у FILMS/SERIES/CARTOONS/ANIME/HENTAI
 * свой вектор вкуса и свои источники. ALL — не самостоятельный раздел, а общий показ:
 * контент разделов (кроме хентая) в одном списке, каждый тайтл ранжируется вектором
 * СВОЕГО раздела; свой рейтинг у ALL отсутствует.
 */
enum class FeedChip(val title: String) {
    ALL("Всё"),
    FILMS("Фильмы"),
    SERIES("Сериалы"),
    CARTOONS("Мультики"),
    ANIME("Аниме"),
    HENTAI("Хентай");

    /** Только хентай — «взрослый» раздел с подтверждением возраста. */
    val isAdultChip: Boolean get() = this == HENTAI

    /** Разделы с «взрослой» подачей — муль туда не прорывается. */
    val excludesAnimation: Boolean get() = this == FILMS || this == SERIES

    /** Разделы, входящие в общий показ «Всё». Хентай всегда показывается отдельно. */
    val isInAllMix: Boolean get() = this in setOf(FILMS, SERIES, CARTOONS, ANIME)

    companion object {
        /** Разделы-источники общего показа, в порядке чередования в ленте. */
        val ALL_MIX: List<FeedChip> = FeedChip.entries.filter { it.isInAllMix }
    }
}

/**
 * Один элемент ленты. [kinopoiskId] уже с учётом ANIME_ID_OFFSET для аниме, так что
 * переход "details/{id}" работает без дополнительных преобразований.
 */
data class FeedItem(
    val kinopoiskId: Int,
    val title: String,
    val originalTitle: String?,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val shortDescription: String?,
    val isAnime: Boolean,
    val isAdultContent: Boolean,
    val isRussian: Boolean = false,
    /** Тип контента по данным источника: "MOVIE"/"TV_SERIES"/"TV_SHOW"; null = неизвестен. */
    val contentType: String? = null,
    /** Страны производства (lowercase) — для авто-фильтра «не смотрю эту страну». */
    val countries: List<String> = emptyList(),
    /** Анонс/невышедший тайтл — такие в ленту не попадают. */
    val upcoming: Boolean = false,
    /**
     * Почему карточка попала в ленту: «жанр боевик w=+2.0», «страна япония»,
     * «сид по похожим», «окно 1990–2004». Только для диагностики.
     */
    val reason: String? = null,
    /** Жанр поискового слота, которым кандидат был добыт (у KP выдача жанров не отдаёт). */
    val sourceGenre: String? = null,
    /**
     * Раздел-источник карточки. Голос в «Всё» уходит в вектор СВОЕГО раздела,
     * а не в общий — у ALL собственного рейтинга нет.
     */
    val section: FeedChip? = null,
    /** Теги хентая (RU, из каталога hanime) — показ на карточке и измерения вкуса 18+. */
    val tags: List<String> = emptyList()
) {
    /** Фильм ли это с точки зрения мгновенного запуска (сериалы ведут на страницу тайтла). */
    val isLikelyMovie: Boolean
        get() = !isAnime && contentType?.let { it == "MOVIE" } == true

    /** Сериал/сериальный тип — для таких «Смотреть» открывает страницу тайтла. */
    val isSeriesLike: Boolean
        get() = contentType in setOf("TV_SERIES", "TV_SHOW", "MINI_SERIES")

    /**
     * Карточка достойна показа: есть постер и название, тайтл уже вышел.
     * Тайтлы «вообще без информации» (ни года, ни рейтинга, ни описания) тоже мимо.
     */
    fun isShowable(currentYear: Int): Boolean =
        !title.isBlank() &&
            !posterUrl.isNullOrBlank() &&
            !upcoming &&
            (year == null || year <= currentYear) &&
            !(rating == null && year == null && shortDescription == null)
}

/** Дообогащённые по текущему тайтлу данные: жанры, описание, кадры, полный постер. */
data class FeedItemExtras(
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val stills: List<String> = emptyList(),
    /** Полноразмерный постер из details(): подменяет превью в фоне карточки. */
    val fullPosterUrl: String? = null,
    /** Теги хентая (RU, каталог hanime) — чипы 18+-карточки и измерения вкуса раздела. */
    val hentaiTags: List<String> = emptyList(),
    /**
     * Кадры ещё не добраны: валидация экономит трафик и грузит только детали,
     * кадры приходят лениво — когда карточку показывают (ensureExtras).
     */
    val stillsPending: Boolean = false
)

/** Готовый к запуску плеера результат быстрого воспроизведения фильма из фида. */
data class FeedPlaybackPayload(
    val kinopoiskId: Int,
    val title: String,
    val streamUrl: String,
    val headers: Map<String, String>,
    val qualities: Map<String, String>,
    val sourceType: String,
    val translations: List<hd.kinoshka.app.data.model.FlatTranslation>
)

/** Состояние видео-слоя карточки: постер → Rutube HLS → YouTube-трейлер. */
sealed interface FeedClipState {
    data object Idle : FeedClipState
    data object Loading : FeedClipState
    /** Нативный Rutube-поток (m3u8), играется в ExoPlayer. */
    data class RutubeReady(val hlsUrl: String, val thumbnailUrl: String?) : FeedClipState
    /** YouTube-трейлер из KP /videos, играется во встроенном WebView iframe. */
    data class YouTubeReady(val videoKey: String) : FeedClipState
    /** Ничего не нашлось — остаётся анимированный постер + кадры. */
    data object PosterOnly : FeedClipState
}

// RutubeClip переехал в shared (jvmShared) вместе с RutubeClipSource — пакет тот же,
// использования резолвятся.

fun isAnimeId(id: Int): Boolean = id >= ANIME_ID_OFFSET

/**
 * Пункт sheet жанров ленты. key — id жанра справочника (KP/Shikimori) или
 * локализованный тег хентай-каталога; интерпретация зависит от раздела.
 */
data class GenreOption(val key: String, val title: String)
fun animeShikimoriId(feedItemId: Int): Int? =
    if (isAnimeId(feedItemId)) feedItemId - ANIME_ID_OFFSET else null

/** Хвостовые маркеры продолжений: «часть 2», «сезон 3», «II», «2nd», «финал». */
private val SEQUEL_TAIL = Regex(
    "\\s*(?:часть|сезон|season|part|финал|final|фильм|movie|серия|story)?\\s*" +
        "(?:[0-9]{1,3}|[ivxlcdm]{1,6}|1st|2nd|3rd|4th|5th)$",
    RegexOption.IGNORE_CASE
)

/**
 * Ключ франшизы: нижний регистр, без пунктуации и подзаголовка после «:»,
 * с многократным срезанием хвостовых номеров сезонов/частей. «Ванпанчмен 2» и
 * «Ванпанчмен: дорога героя» схлопываются в один ключ — в партии им не место рядом.
 */
fun franchiseKeyOf(rawTitle: String?): String? {
    if (rawTitle.isNullOrBlank()) return null
    var t = rawTitle.lowercase()
        .substringBefore(':')
        .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    var prev: String
    do {
        prev = t
        t = SEQUEL_TAIL.replace(t, "").trim()
    } while (t.isNotEmpty() && t != prev)
    return t.ifBlank { null }
}
