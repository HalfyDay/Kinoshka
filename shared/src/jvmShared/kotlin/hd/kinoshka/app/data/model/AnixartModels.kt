package hd.kinoshka.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Модели неофициального API Anixart (спека AniX-org, v9): все поля nullable/
 * с дефолтами — схема меняется между бетами, парсинг падать не должен.
 */

data class AnixartToken(
    @SerializedName("token") val token: String? = null
)

data class AnixartProfile(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("login") val login: String? = null,
    @SerializedName("avatar") val avatar: String? = null
)

data class AnixartLoginResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("profile") val profile: AnixartProfile? = null,
    // Референсный AnixartJS читает profileToken (camelCase), остальной API — snake_case:
    // принимаем оба написания, чтобы смена регистра зеркалом не роняла вход.
    @SerializedName(value = "profile_token", alternate = ["profileToken"])
    val profileToken: AnixartToken? = null
)

/**
 * Ответ регистрации / восстановления (auth/signUp, auth/restore):
 * code 0 — код отправлен на почту, hash — для verify-шага.
 * codeTimestampExpires — время жизни кода (не используем, только диагностика).
 */
data class AnixartSignUpResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("codeTimestampExpires") val codeTimestampExpires: Long = 0L
)

data class AnixartDefaultResponse(
    @SerializedName("code") val code: Int = -1
)

/**
 * Релиз в списке пользователя. shikimori_id есть не всегда — тогда матчим
 * с библиотекой по названию (см. TitleMatching).
 */
data class AnixartRelease(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title_ru") val titleRu: String? = null,
    @SerializedName("title_original") val titleOriginal: String? = null,
    @SerializedName("title_en") val titleEn: String? = null,
    @SerializedName("shikimori_id") val shikimoriId: Int? = null,
    /**
     * Статус релиза в списках юзера (0 — ни в одном). Есть в полном объекте релиза
     * (release/{id}) и, возможно, в списках — точечная сверка карточки без полного пула.
     */
    @SerializedName("profile_list_status") val profileListStatus: Int = 0,
    // Зонд метаданных для дизамбигуации сезонов (спека: year/season/episodes есть
    // у релиза; типы скачут между endpoint'ами — JsonElement не роняет парсинг).
    @SerializedName("year") val yearRaw: com.google.gson.JsonElement? = null,
    @SerializedName("season") val seasonRaw: com.google.gson.JsonElement? = null,
    @SerializedName("episodes_total") val episodesTotalRaw: com.google.gson.JsonElement? = null,
    @SerializedName("country") val countryRaw: com.google.gson.JsonElement? = null
) {
    /**
     * Год релиза (17/17 сверенных — год выхода оригинала, не добавления).
     * Спека врёт типами (строка vs число) — парсим терпимо, иначе null.
     * Anixart `season` для склейки НЕ годится (мусорные значения) — только year.
     */
    fun releaseYear(): Int? = try {
        val el = yearRaw ?: return null
        if (el.isJsonNull) return null
        val p = el.asJsonPrimitive
        if (p.isNumber) p.asInt else p.asString.filter { it.isDigit() }.take(4).toIntOrNull()
    } catch (_: Exception) {
        null
    }
}

data class AnixartReleaseInfoResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("release") val release: AnixartRelease? = null
)

data class AnixartListResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("content") val content: List<AnixartRelease>? = null,
    @SerializedName("total_count") val totalCount: Int = 0
)

/**
 * Поиск по каталогу релизов (POST search/releases/{page}, спека AniX-org v2):
 * нужен для пуша локальных/Shikimori-тайтлов, которых нет в списках юзера, —
 * addToList требует именно id релиза Anixart. searchBy=0 — по названию.
 */
data class AnixartSearchRequest(
    @SerializedName("query") val query: String,
    @SerializedName("searchBy") val searchBy: Int = 0
)

data class AnixartSearchResponse(
    @SerializedName("code") val code: Int = -1,
    // В отличие от списков поле называется releases, а не content.
    @SerializedName("releases") val releases: List<AnixartRelease>? = null,
    @SerializedName("total_count") val totalCount: Int = 0
)

/** Списки Anixart (path-параметр {list}): 0 — без списка, дальше как наши статусы. */
object AnixartLists {
    const val NOT_WATCHING = 0
    const val WATCHING = 1
    const val PLANNED = 2
    const val COMPLETED = 3
    const val ON_HOLD = 4
    const val DROPPED = 5
}

/**
 * Видео-цепочка Anixart (сверено с AnixartJS, гостевой доступ без токена работает):
 * release/{id} -> episode/{id} (озвучки) -> episode/{id}/{dubber} (источники) ->
 * episode/{id}/{dubber}/{source}?sort=1 (серии) -> episode/target/{id}/{source}/{pos}
 * (подписанная ссылка, ?d &s &ip живут минуты — резолвить в момент воспроизведения).
 * Все поля nullable/с дефолтами — схема плавает между бетами.
 */

/** Озвучка релиза (GET episode/{releaseId} -> types[]). */
data class AnixartDubber(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("workers") val workers: String? = null,
    @SerializedName("is_sub") val isSub: Boolean = false,
    @SerializedName("episode_count") val episodeCount: Int = 0,
    @SerializedName("view_count") val viewCount: Int = 0,
    @SerializedName("pinned") val pinned: Boolean = false
)

/** Источник озвучки (GET episode/{releaseId}/{dubberId} -> sources[]). */
data class AnixartVideoSource(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("episode_count") val episodeCount: Int = 0
)

/**
 * Серия (GET episode/{releaseId}/{dubberId}/{sourceId} и episode/target/...).
 * url — либо прямая ссылка, либо embed (iframe=true: Kodik-плеер, Libria iframe.php);
 * Sibnet shell.php приходит с iframe=false, но играется только после скрапа в MP4.
 */
data class AnixartEpisode(
    @SerializedName("position") val position: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("iframe") val iframe: Boolean = false,
    @SerializedName("addedDate") val addedDate: Long = 0L,
    @SerializedName("is_watched") val isWatched: Boolean = false
)

data class AnixartDubbersResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("types") val types: List<AnixartDubber>? = null
)

data class AnixartSourcesResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("sources") val sources: List<AnixartVideoSource>? = null
)

data class AnixartEpisodesResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("episodes") val episodes: List<AnixartEpisode>? = null
)

data class AnixartEpisodeTargetResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("episode") val episode: AnixartEpisode? = null
)

/**
 * Строка апдейта серий (GET episode/updates/{releaseId}/{page}): запасной источник
 * id источников, когда официальный episode/{release}/{dubber} пуст (Anixart прячет
 * мёртвые «Источник N (не работает)»).
 */
data class AnixartEpisodeUpdate(
    @SerializedName("last_episode_type_update_id") val dubberId: Int = 0,
    @SerializedName("last_episode_source_update_id") val sourceId: Int = 0,
    @SerializedName("last_episode_source_update_name") val sourceName: String? = null
)

data class AnixartEpisodeUpdatesResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("content") val content: List<AnixartEpisodeUpdate>? = null,
    @SerializedName("total_page_count") val totalPageCount: Int = 0
)
