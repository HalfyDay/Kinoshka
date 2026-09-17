package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.AnimeSourceType

/**
 * Единый реестр всех источников видео: аниме-пикер, прямые ссылки для кино/сериалов
 * и 18+-провайдеры. Идентификаторы стабильны (пишутся в настройки и кэши):
 * для аниме/хентая совпадают с [AnimeSourceType.name], для прямых — верхнерегистрные
 * имена провайдеров ("TURBO", "VIDEOCDN", ...).
 */
/**
 * Категории страницы «Источники»: один источник может обслуживать несколько
 * разделов (Kodik — и кино, и аниме; AniStar/Smarthard — аниме и 18+).
 */
enum class SourceCategory(val title: String) {
    FILMS("Фильмы"),
    ANIME("Аниме"),
    ADULT("18+")
}

data class PlaybackSourceInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val categories: Set<SourceCategory>,
    val needsVpn: Boolean = false,
    val animeSourceType: AnimeSourceType? = null
)

object PlaybackSources {
    const val KODIK = "KODIK"
    const val SHIKIMORI = "SHIKIMORI"
    const val ANILIBERTY = "ANILIBERTY"
    const val ANILIB = "ANILIB"
    const val ANISTAR = "ANISTAR"
    const val ANIXART = "ANIXART"
    const val SMARTHARD = "SMARTHARD"

    const val TURBO = "TURBO"
    const val VIDEOCDN = "VIDEOCDN"
    const val COLLAPS = "COLLAPS"
    const val VOIDBOOST = "VOIDBOOST"
    const val ALLOHA = "ALLOHA"
    const val VEOVEO = "VEOVEO"
    const val HDREZKA = "HDREZKA"

    const val HENTAI_ALLHENTAI = "HENTAI_ALLHENTAI"
    const val HENTAI_HENTAIDREAM = "HENTAI_HENTAIDREAM"
    const val HENTAI_HENTAIZ = "HENTAI_HENTAIZ"
    const val HENTAI_HANIME1 = "HENTAI_HANIME1"
    const val HENTAI_OPPAI = "HENTAI_OPPAI"

    val ALL: List<PlaybackSourceInfo> = listOf(
        PlaybackSourceInfo(
            KODIK, "Kodik",
            "Большой каталог озвучек и субтитров — аниме, фильмы и сериалы",
            setOf(SourceCategory.FILMS, SourceCategory.ANIME), animeSourceType = AnimeSourceType.KODIK
        ),
        PlaybackSourceInfo(
            SHIKIMORI, "Shikimori",
            "Плеер Shikimori: озвучки и субтитры, HLS до 1080p",
            setOf(SourceCategory.ANIME), animeSourceType = AnimeSourceType.SHIKIMORI
        ),
        PlaybackSourceInfo(
            ANILIBERTY, "AniLiberty",
            "Релизы AniLiberty с качествами 1080p/720p/480p",
            setOf(SourceCategory.ANIME), animeSourceType = AnimeSourceType.ANILIBERTY
        ),
        PlaybackSourceInfo(
            ANILIB, "AnimeLib",
            "Каталог AnimeLib (animelib.org), озвучки по командам",
            setOf(SourceCategory.ANIME), animeSourceType = AnimeSourceType.ANILIB
        ),
        PlaybackSourceInfo(
            ANISTAR, "AniStar",
            "Свои озвучки AniStar, MP4/HLS 360–720p",
            setOf(SourceCategory.ANIME, SourceCategory.ADULT), animeSourceType = AnimeSourceType.ANISTAR
        ),
        PlaybackSourceInfo(
            ANIXART, "Anixart",
            "Озвучки Anixart: Kodik, Sibnet, Libria и другие",
            setOf(SourceCategory.ANIME), animeSourceType = AnimeSourceType.ANIXART
        ),
        PlaybackSourceInfo(
            SMARTHARD, "Smarthard",
            "Архив shikicinema для 18+: озвучки и субтитры; часть ссылок требует VPN",
            setOf(SourceCategory.ADULT), needsVpn = true,
            animeSourceType = AnimeSourceType.SMARTHARD
        ),
        PlaybackSourceInfo(
            TURBO, "Turbo",
            "Прямые ссылки Turbo (ddbb): MP4 до 1080p, много озвучек",
            setOf(SourceCategory.FILMS), animeSourceType = AnimeSourceType.TURBO
        ),
        PlaybackSourceInfo(
            VIDEOCDN, "VideoCDN",
            "Каталог VideoCDN: фильмы и сериалы по kinopoisk id",
            setOf(SourceCategory.FILMS), animeSourceType = AnimeSourceType.VIDEOCDN
        ),
        PlaybackSourceInfo(
            COLLAPS, "Collaps",
            "Встраиваемый плеер Collaps: HLS на серию/фильм",
            setOf(SourceCategory.FILMS), animeSourceType = AnimeSourceType.COLLAPS
        ),
        PlaybackSourceInfo(
            VOIDBOOST, "Voidboost",
            "Бэкенд Rezka: озвучки Voidboost",
            setOf(SourceCategory.FILMS), animeSourceType = AnimeSourceType.VOIDBOOST
        ),
        PlaybackSourceInfo(
            ALLOHA, "Alloha",
            "Alloha (ddbb): iframe-плеер, только веб-режим",
            setOf(SourceCategory.FILMS)
        ),
        PlaybackSourceInfo(
            VEOVEO, "Veoveo",
            "Veoveo (ddbb): iframe-плеер, только веб-режим",
            setOf(SourceCategory.FILMS)
        ),
        PlaybackSourceInfo(
            HDREZKA, "HDRezka",
            "Прямые ссылки HDRezka: фильмы и сериалы, много озвучек",
            setOf(SourceCategory.FILMS), animeSourceType = AnimeSourceType.HDREZKA
        ),
        PlaybackSourceInfo(
            HENTAI_ALLHENTAI, "AllHentai",
            "Хентай-источник: русские озвучки",
            setOf(SourceCategory.ADULT), needsVpn = true, animeSourceType = AnimeSourceType.HENTAI_ALLHENTAI
        ),
        PlaybackSourceInfo(
            HENTAI_HENTAIDREAM, "HentaiDream",
            "Хентай-источник: русские озвучки",
            setOf(SourceCategory.ADULT), animeSourceType = AnimeSourceType.HENTAI_HENTAIDREAM
        ),
        PlaybackSourceInfo(
            HENTAI_HENTAIZ, "HentaiZ",
            "Хентай-источник: оригинал и озвучки",
            setOf(SourceCategory.ADULT), animeSourceType = AnimeSourceType.HENTAI_HENTAIZ
        ),
        PlaybackSourceInfo(
            HENTAI_HANIME1, "Hanime1.me",
            "Хентай-источник: оригинал с японскими титрами",
            setOf(SourceCategory.ADULT), animeSourceType = AnimeSourceType.HENTAI_HANIME1
        ),
        PlaybackSourceInfo(
            HENTAI_OPPAI, "Oppai.Stream",
            "Хентай-источник: MP4 720/1080p",
            setOf(SourceCategory.ADULT), needsVpn = true, animeSourceType = AnimeSourceType.HENTAI_OPPAI
        )
    )

    /** Источники аниме-страницы выбора (порядок как в ANIME_PICKER_SOURCES). */
    val ANIME_IDS: List<String> = listOf(KODIK, SHIKIMORI, ANILIBERTY, ANILIB, ANISTAR, ANIXART)

    /** Источники кино-страницы выбора: Kodik + все прямые. */
    val MOVIE_IDS: List<String> =
        listOf(KODIK, HDREZKA, TURBO, VIDEOCDN, COLLAPS, VOIDBOOST, ALLOHA, VEOVEO)

    val ADULT_IDS: List<String> = listOf(
        HENTAI_ALLHENTAI, HENTAI_HENTAIDREAM, HENTAI_HENTAIZ, HENTAI_HANIME1, HENTAI_OPPAI
    )

    private val byId: Map<String, PlaybackSourceInfo> = ALL.associateBy { it.id }

    /**
     * Свои источники (вариант A) поверх реестра: обновляется при старте приложения и
     * при каждом сохранении/удалении ([setCustomSourceInfos]). Volatile — читается
     * резолверами с IO-потоков без синхронизации.
     */
    @Volatile
    var customSourceInfos: List<PlaybackSourceInfo> = emptyList()
        private set

    fun setCustomSourceInfos(infos: List<PlaybackSourceInfo>) {
        customSourceInfos = infos.toList()
    }

    /** Встроенные + свои — для списков UI (настройки, пикер). */
    fun allInfos(): List<PlaybackSourceInfo> = ALL + customSourceInfos

    fun customInfo(source: CustomSource): PlaybackSourceInfo = PlaybackSourceInfo(
        id = source.id,
        displayName = source.name,
        description = buildString {
            if (source.kind == CustomSourceKind.STREMIO) {
                append("Свой источник (Stremio JSON): ${source.stremioHost() ?: source.endpoint}")
            } else {
                append("Свой источник: ${source.embedHost() ?: source.urlTemplate}")
            }
            val cats = source.categories.ifEmpty { setOf(SourceCategory.FILMS) }
            if (cats != setOf(SourceCategory.FILMS)) {
                append(" · ")
                append(cats.sortedBy { it.ordinal }.joinToString { it.title })
            }
        },
        categories = source.categories.ifEmpty { setOf(SourceCategory.FILMS) },
        animeSourceType = AnimeSourceType.CUSTOM
    )

    fun info(id: String): PlaybackSourceInfo? {
        val key = id.uppercase()
        return byId[key] ?: customSourceInfos.firstOrNull { it.id == key }
    }

    fun displayName(id: String): String = info(id)?.displayName ?: id

    fun isKnown(id: String): Boolean {
        val key = id.uppercase()
        return byId.containsKey(key) || customSourceInfos.any { it.id == key }
    }

    /** Нормализация пользовательского/сериализованного id к каноническому (верхний регистр). */
    fun canonical(id: String): String = id.trim().uppercase()

    /**
     * Имя источника из [DdbbStreamResolver.SourceParse]/[WebmasterStreamSources]
     * ("Turbo", "VideoCDN", "Voidboost", ...) → id реестра. Null для неизвестных.
     */
    fun ddbbSourceNameToId(sourceName: String): String? = when (sourceName.trim().lowercase()) {
        "turbo" -> TURBO
        "videocdn" -> VIDEOCDN
        "collaps" -> COLLAPS
        "voidboost" -> VOIDBOOST
        "alloha" -> ALLOHA
        "veoveo" -> VEOVEO
        "hdrezka" -> HDREZKA
        "kodik" -> KODIK
        else -> null
    }

    /** Обратное отображение: id реестра → имя для [DdbbStreamResolver.sourceRank]. */
    fun idToDdbbSourceName(id: String): String = when (canonical(id)) {
        VIDEOCDN -> "videocdn"
        else -> canonical(id).lowercase()
    }

    /**
     * Тип строки плеера для id реестра кино-источника: прямые провайдеры несут свой
     * собственный тип (Turbo/HDRezka/VideoCDN/Collaps/Voidboost), а не общий DDBB —
     * иначе в дропдауне озвучек плеера все строки кино помечены «DDBB» (как у аниме
     * каждая строка несёт свой источник). Неизвестные id → [AnimeSourceType.DDBB].
     */
    fun animeSourceTypeFor(id: String): AnimeSourceType {
        if (CustomSource.isCustomId(id)) return AnimeSourceType.CUSTOM
        return info(id)?.animeSourceType
            ?: runCatching { AnimeSourceType.valueOf(canonical(id)) }.getOrDefault(AnimeSourceType.DDBB)
    }

    /**
     * Тип строки плеера по сырому dubId прямого каталога. DubId уже несут пространство
     * имён провайдера (turbo|…, videocdn|…, voidboost|…, hdrezka, collaps) — см. продюсеры
     * [DdbbStreamResolver]/[WebmasterStreamSources]/[HdrezkaApi]. Без префикса → DDBB.
     */
    fun animeSourceTypeForDubId(dubId: String): AnimeSourceType {
        val lower = dubId.trim().lowercase()
        return when {
            lower == "hdrezka" || lower == "collaps" -> animeSourceTypeFor(lower)
            lower.startsWith("turbo|") -> AnimeSourceType.TURBO
            lower.startsWith("videocdn|") -> AnimeSourceType.VIDEOCDN
            lower.startsWith("voidboost|") -> AnimeSourceType.VOIDBOOST
            lower.startsWith("custom|") -> AnimeSourceType.CUSTOM
            else -> AnimeSourceType.DDBB
        }
    }

    /** «1 источник», «3 источника», «8 источников» — подпись строки озвучки кино-пикера. */
    fun sourcesCountLabel(count: Int): String {
        val word = when {
            count % 10 == 1 && count % 100 != 11 -> "источник"
            count % 10 in 2..4 && (count % 100 < 12 || count % 100 > 14) -> "источника"
            else -> "источников"
        }
        return "$count $word"
    }
}
