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
