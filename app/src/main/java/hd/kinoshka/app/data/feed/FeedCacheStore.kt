package hd.kinoshka.app.data.feed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Дисковый кэш собранной ленты по разделам: последний список карточек с их extras
 * и позиция ротации страниц. Партия кладётся в кэш уже провалидированной и
 * обогащённой, поэтому повторный запуск и возврат на раздел показывают ленту
 * мгновенно (как в коротких видео-лентах) — без скелетона и сетевого шквала;
 * свежие партии добираются фоном и перезаписывают снимок. Полностью автономен
 * (свой SharedPreferences-файл), как InterestProfileStore.
 */
class FeedCacheStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Снимок ленты раздела: карточки, их extras и продолжение ротации страниц. */
    data class Snapshot(
        val items: List<FeedItem>,
        val extras: Map<Int, FeedItemExtras>,
        val pageIndex: Int
    )

    fun load(chip: FeedChip): Snapshot? {
        val raw = prefs.getString(key(chip), null) ?: return null
        val snapshot = runCatching { parse(JSONObject(raw)) }.getOrNull()
        return snapshot?.takeIf { it.items.isNotEmpty() }
    }

    fun save(chip: FeedChip, items: List<FeedItem>, extras: Map<Int, FeedItemExtras>, pageIndex: Int) {
        if (items.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            items.forEach { item -> arr.put(itemJson(item)) }
            val extrasObj = JSONObject()
            items.forEach { item ->
                extras[item.kinopoiskId]?.let { extrasObj.put(item.kinopoiskId.toString(), extrasJson(it)) }
            }
            val obj = JSONObject()
                .put("items", arr)
                .put("extras", extrasObj)
                .put("page", pageIndex)
                .put("savedAt", System.currentTimeMillis())
            prefs.edit().putString(key(chip), obj.toString()).apply()
        }
    }

    fun clear(chip: FeedChip) {
        prefs.edit().remove(key(chip)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun key(chip: FeedChip) = "feed_cache_${chip.name}"

    private fun parse(obj: JSONObject): Snapshot {
        if (System.currentTimeMillis() - obj.optLong("savedAt", 0L) > TTL_MS) {
            return Snapshot(emptyList(), emptyMap(), 0)
        }
        val arr = obj.optJSONArray("items") ?: return Snapshot(emptyList(), emptyMap(), 0)
        val extrasObj = obj.optJSONObject("extras") ?: JSONObject()
        val items = mutableListOf<FeedItem>()
        val extras = mutableMapOf<Int, FeedItemExtras>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("i", 0)
            if (id <= 0) continue
            items += FeedItem(
                kinopoiskId = id,
                title = o.optString("t", "Фильм"),
                originalTitle = o.optString("o").takeIf { it.isNotBlank() },
                posterUrl = o.optString("p").takeIf { it.isNotBlank() },
                year = o.optInt("y", 0).takeIf { it > 0 },
                rating = o.optDouble("r", Double.NaN).takeIf { !it.isNaN() },
                genres = o.strList("g"),
                shortDescription = o.optString("d").takeIf { it.isNotBlank() },
                isAnime = o.optBoolean("a"),
                isAdultContent = o.optBoolean("x"),
                isRussian = o.optBoolean("ru"),
                contentType = o.optString("ct").takeIf { it.isNotBlank() },
                countries = o.strList("c"),
                upcoming = o.optBoolean("u"),
                sourceGenre = o.optString("sg").takeIf { it.isNotBlank() },
                section = FeedChip.entries.firstOrNull { it.name == o.optString("s") },
                tags = o.strList("tg")
            )
            extrasObj.optJSONObject(id.toString())?.let { e ->
                extras[id] = FeedItemExtras(
                    genres = e.strList("g"),
                    description = e.optString("d").takeIf { it.isNotBlank() },
                    stills = e.strList("st"),
                    fullPosterUrl = e.optString("fp").takeIf { it.isNotBlank() },
                    hentaiTags = e.strList("tg"),
                    stillsPending = e.optBoolean("sp")
                )
            }
        }
        return Snapshot(items, extras, obj.optInt("page", 0))
    }

    private fun itemJson(item: FeedItem): JSONObject = JSONObject()
        .put("i", item.kinopoiskId)
        .put("t", item.title)
        .put("o", item.originalTitle ?: "")
        .put("p", item.posterUrl ?: "")
        .put("y", item.year ?: 0)
        .put("r", item.rating ?: JSONObject.NULL)
        .put("g", JSONArray(item.genres))
        .put("d", item.shortDescription ?: "")
        .put("a", item.isAnime)
        .put("x", item.isAdultContent)
        .put("ru", item.isRussian)
        .put("ct", item.contentType ?: "")
        .put("c", JSONArray(item.countries))
        .put("u", item.upcoming)
        .put("sg", item.sourceGenre ?: "")
        .put("s", item.section?.name ?: "")
        .put("tg", JSONArray(item.tags))

    private fun extrasJson(e: FeedItemExtras): JSONObject = JSONObject()
        .put("g", JSONArray(e.genres))
        .put("d", e.description ?: "")
        .put("st", JSONArray(e.stills))
        .put("fp", e.fullPosterUrl ?: "")
        .put("tg", JSONArray(e.hentaiTags))
        .put("sp", e.stillsPending)

    private fun JSONObject.strList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { k -> arr.optString(k).takeIf { it.isNotBlank() } }
    }

    companion object {
        const val PREFS_NAME = "feed_cache_prefs"

        /** Кэш старше двух недель не показываем: вкусы и выдача за это время устаревают. */
        const val TTL_MS = 14L * 24L * 60L * 60L * 1000L
    }
}
