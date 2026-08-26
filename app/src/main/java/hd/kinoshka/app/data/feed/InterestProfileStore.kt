package hd.kinoshka.app.data.feed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Локальный профиль интересов: веса жанров + флаг подтверждения возраста.
 * Полностью автономен (свой SharedPreferences-файл), чтобы тестовую функцию фида
 * можно было удалить, не трогая UserStateStore.
 */
/** Лайкнутый тайтл: id, название и жанры на момент голоса — для списка «мои лайки». */
data class LikedTitle(val id: Int, val title: String, val genres: List<String> = emptyList())

/**
 * Профиль интересов фида: веса жанров/стран/десятилетий + служебные ключи.
 * Хранится в SharedPreferences одним JSON-объектом на ось вкуса.
 */
class InterestProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAdultConfirmed(): Boolean = prefs.getBoolean(KEY_ADULT_CONFIRMED, false)

    fun setAdultConfirmed() {
        prefs.edit().putBoolean(KEY_ADULT_CONFIRMED, true).apply()
    }

    // ==================== онбординг вкусов (по разделам) ====================

    /** Разделы (имена FeedChip), для которых первичный опрос уже пройден. */
    fun onboardedChips(): Set<String> =
        prefs.getStringSet(KEY_ONBOARDED_CHIPS, emptySet()) ?: emptySet()

    fun isChipOnboarded(chipKey: String): Boolean = onboardedChips().contains(chipKey)

    fun markChipOnboarded(chipKey: String) {
        val updated = onboardedChips().toHashSet().apply { add(chipKey) }
        prefs.edit().putStringSet(KEY_ONBOARDED_CHIPS, updated).apply()
    }

    /** Когда в последний раз прогоняли обогащение истории (мс эпохи), 0 = никогда. */
    fun lastEnrichedAt(): Long = prefs.getLong(KEY_ENRICHED_AT, 0L)

    fun setEnrichedAt(timestamp: Long) {
        prefs.edit().putLong(KEY_ENRICHED_AT, timestamp).apply()
    }

    // ==================== просмотренное во фиде (TTL 7 дней) ====================

    /** Id карточек, уже показанных/просмотренных во фиде за последние [SEEN_TTL_MS]. */
    fun seenFeedIds(): Set<Int> {
        val raw = prefs.getString(KEY_SEEN_FEED, null) ?: return emptySet()
        val now = System.currentTimeMillis()
        return runCatching {
            val obj = JSONObject(raw)
            buildSet {
                for (key in obj.keys()) {
                    val ts = obj.optLong(key, 0L)
                    if (now - ts < SEEN_TTL_MS) add(key.toIntOrNull() ?: continue)
                }
            }
        }.getOrDefault(emptySet())
    }

    /** Отмечает карточки показанными; хранит не больше [SEEN_MAX] свежайших. */
    fun markSeenInFeed(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            val obj = JSONObject(prefs.getString(KEY_SEEN_FEED, null) ?: "{}")
            ids.forEach { id -> if (id > 0) obj.put(id.toString(), now) }

            // Чистка по TTL + кап: убираем сначала самые старые.
            val entries = mutableListOf<Pair<Int, Long>>()
            for (key in obj.keys()) {
                val id = key.toIntOrNull() ?: continue
                val ts = obj.optLong(key, 0L)
                if (now - ts < SEEN_TTL_MS) entries.add(id to ts)
            }
            val trimmed = entries.sortedByDescending { it.second }.take(SEEN_MAX)
            val fresh = JSONObject()
            trimmed.forEach { (id, ts) -> fresh.put(id.toString(), ts) }
            prefs.edit().putString(KEY_SEEN_FEED, fresh.toString()).apply()
        }
    }

    /** Полная амнистия «виденного во фиде» — когда лента зациклилась, начинаем её заново. */
    fun clearSeenFeed() {
        prefs.edit().remove(KEY_SEEN_FEED).apply()
    }

    // ==================== обобщённые весовые карты (жанры/страны/десятилетия) ====================

    private fun readMap(key: String): Map<String, Double> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                for (k in obj.keys()) {
                    val v = obj.optDouble(k, Double.NaN)
                    if (!v.isNaN()) put(k.lowercase(), v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeMap(key: String, map: Map<String, Double>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    /**
     * Единая математика сигнала: затухание всех старых весов + сдвиг выбранных ключей.
     * Используется для лайков/дизлайков по любому измерению (жанр, страна, декада).
     */
    private fun bump(mapKey: String, keys: List<String>, delta: Double) {
        if (keys.isEmpty()) return
        val current = readMap(mapKey).toMutableMap()
        for (k in current.keys) current[k] = (current[k]!! * DECAY).coerceIn(MIN_W, MAX_W)
        for (raw in keys) {
            val k = raw.trim().lowercase()
            if (k.isBlank()) continue
            current[k] = ((current[k] ?: 0.0) + delta).coerceIn(MIN_W, MAX_W)
        }
        writeMap(mapKey, current)
    }

    /** Массовое применение выученных из истории весов (без затухания остальных). */
    private fun learnInto(mapKey: String, learned: Map<String, Double>) {
        if (learned.isEmpty()) return
        val current = readMap(mapKey).toMutableMap()
        for ((raw, delta) in learned) {
            val k = raw.trim().lowercase()
            if (k.isBlank()) continue
            current[k] = ((current[k] ?: 0.0) + delta).coerceIn(MIN_W, MAX_W)
        }
        writeMap(mapKey, current)
    }

    // ---- жанры ----
    fun weights(): Map<String, Double> = readMap(KEY_WEIGHTS)
    fun weightOf(genre: String): Double = weights()[genre.trim().lowercase()] ?: 0.0
    fun applyFeedback(genres: List<String>, liked: Boolean) =
        bump(KEY_WEIGHTS, genres, if (liked) LIKE_DELTA else DISLIKE_DELTA)
    fun applyLearned(learned: Map<String, Double>) = learnInto(KEY_WEIGHTS, learned)

    // ---- страны (названия как в справочнике Кинопоиска, lowercase) ----
    fun countryWeights(): Map<String, Double> = readMap(KEY_COUNTRIES)
    fun applyCountryFeedback(countries: List<String>, liked: Boolean) =
        bump(KEY_COUNTRIES, countries, if (liked) LIKE_DELTA else DISLIKE_DELTA)
    fun applyLearnedCountries(learned: Map<String, Double>) = learnInto(KEY_COUNTRIES, learned)

    // ---- десятилетия ("1980s", "1990s", ...) — косвенный сигнал возраста ----
    fun decadeWeights(): Map<String, Double> = readMap(KEY_DECADES)
    fun applyDecadeFeedback(decades: List<String>, liked: Boolean) =
        bump(KEY_DECADES, decades, if (liked) LIKE_DELTA else DISLIKE_DELTA)
    fun applyLearnedDecades(learned: Map<String, Double>) = learnInto(KEY_DECADES, learned)

    /**
     * Центрированные веса: из каждого вычитается среднее по оси. Абсолютный дрейф
     * (много дизлайков утянули всё в минус) больше не душит выбор — сравнение идёт
     * «насколько лучше/хуже среднего вкуса», а не относительно нуля.
     */
    fun centeredWeights(): Map<String, Double> = readMap(KEY_WEIGHTS).centered()
    fun centeredCountryWeights(): Map<String, Double> = readMap(KEY_COUNTRIES).centered()
    fun centeredDecadeWeights(): Map<String, Double> = readMap(KEY_DECADES).centered()

    private fun Map<String, Double>.centered(): Map<String, Double> {
        if (size < 2) return this
        val mean = values.average()
        return mapValues { it.value - mean }
    }

    /**
     * Точный вклад голоса по всем осям сразу с произвольной дельтой. Служит для
     * переголосования: отмена старого лайка = applyVoteDelta(..., -0.5), перенос на
     * дизлайк = реверс + новый вклад. Дельты совпадают с apply*Feedback по модулю.
     */
    fun applyVoteDelta(genres: List<String>, countries: List<String>, decade: String?, delta: Double) {
        if (delta == 0.0) return
        bump(KEY_WEIGHTS, genres, delta)
        bump(KEY_COUNTRIES, countries, delta)
        if (decade != null) bump(KEY_DECADES, listOf(decade), delta)
    }

    fun decadeOf(year: Int?): String? {
        val y = year ?: return null
        return "${y / 10 * 10}s"
    }

    // ---- лайкнутые тайтлы как источники «похожих» и список для просмотра ----

    fun likedTitles(): List<LikedTitle> {
        val raw = prefs.getString(KEY_LIKED_SEEDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull { e ->
                val o = arr.optJSONObject(e) ?: return@mapNotNull null
                val id = o.optInt("i", 0)
                if (id <= 0) return@mapNotNull null
                val gArr = o.optJSONArray("g")
                LikedTitle(
                    id = id,
                    title = o.optString("t", "Тайтл"),
                    genres = (0 until (gArr?.length() ?: 0)).mapNotNull { gArr?.optString(it) }
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Лайк: в начало списка (свежие сверху), кап по длине. */
    fun addLikedTitle(entry: LikedTitle) {
        val updated = listOf(entry) + likedTitles().filterNot { it.id == entry.id }
        writeLiked(updated.take(LIKED_SEEDS_MAX))
    }

    fun removeLikedTitle(id: Int) {
        writeLiked(likedTitles().filterNot { it.id == id })
    }

    private fun writeLiked(items: List<LikedTitle>) {
        val arr = JSONArray()
        items.forEach { t ->
            arr.put(JSONObject().put("i", t.id).put("t", t.title).put("g", JSONArray(t.genres)))
        }
        prefs.edit().putString(KEY_LIKED_SEEDS, JSONObject().put("items", arr).toString()).apply()
    }

    /** Совместимость со старым API сидов. */
    fun likedSeedIds(): List<Int> = likedTitles().map { it.id }

    companion object {
        const val PREFS_NAME = "feed_recommendation_prefs"
        const val KEY_WEIGHTS = "genre_weights"
        const val KEY_ADULT_CONFIRMED = "adult_confirmed"
        const val KEY_ENRICHED_AT = "enriched_at"
        const val KEY_SEEN_FEED = "seen_feed_ids"
        const val KEY_ONBOARDED_CHIPS = "onboarded_chips"
        const val KEY_COUNTRIES = "country_weights"
        const val KEY_DECADES = "decade_weights"
        const val KEY_LIKED_SEEDS = "liked_seed_ids"
        const val MAX_W = 6.0
        const val MIN_W = -6.0

        /** Вклад голоса: лайк и его реверс, дизлайк и его реверс (см. applyVoteDelta). */
        const val LIKE_DELTA = 0.5
        const val DISLIKE_DELTA = -0.8
        const val DECAY = 0.98
        const val SEEN_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val SEEN_MAX = 400
        const val LIKED_SEEDS_MAX = 10
    }
}
