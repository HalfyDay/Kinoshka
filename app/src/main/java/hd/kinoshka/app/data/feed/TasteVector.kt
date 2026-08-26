package hd.kinoshka.app.data.feed

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Признаки тайтла в пространстве вкуса: разреженный набор измерений
 * «жанр / страна / десятилетие / тип» + приоритет качества по рейтингу.
 */
data class TasteFeatures(
    val dims: Set<String>,
    val franchiseKey: String? = null,
    val ratingPrior: Double = 0.0
)

/** Разложение скора карточки — целиком уходит в диагностику. */
data class TasteScore(
    val total: Double,
    val centroidPart: Double,
    val sarPart: Double,
    val noise: Double,
    /** Карточка добыта исследованием: шум заметно повлиял на исход. */
    val explored: Boolean
)

fun tasteFeaturesOf(item: FeedItem, genresOverride: List<String>? = null): TasteFeatures {
    val dims = mutableSetOf<String>()
    val genres = genresOverride ?: item.sourceGenre?.let { listOf(it) } ?: emptyList()
    genres.forEach { g ->
        val k = g.trim().lowercase()
        if (k.isNotBlank()) dims += "g:$k"
    }
    item.countries.forEach { dims += "c:$it" }
    item.year?.let { dims += "d:${it / 10 * 10}s" }
    dims += when {
        item.isAnime -> "t:ANIME"
        item.contentType == "MOVIE" -> "t:MOVIE"
        else -> "t:SERIES"
    }
    return TasteFeatures(
        dims = dims,
        franchiseKey = franchiseKeyOf(item.title),
        ratingPrior = item.rating?.let { ((it - 6.5) / 5.0).coerceIn(-0.4, 0.4) } ?: 0.0
    )
}

/**
 * Вектор вкуса пользователя + SAR-память последних голосов.
 *
 * Два члена скора:
 *  - центроид (глобальный вкус): косинусная близость к взвешенной сумме измерений;
 *  - SAR (Microsoft Smart Adaptive Recommendations): сумма «похожесть(кандидат,
 *    лайкнутый тайтл) × вес голоса» по последним голосам — сравнение с КОНКРЕТНЫМИ
 *    тайтлами ловит сочетания признаков, которые усреднённый жанр теряет.
 *
 * Исследование по мотивам Thompson Sampling: шум N(0, σ) с σ=0.35·e^(−n/40) —
 * доля «рискованных» карточек естественно стремится к ~20% и падает с опытом.
 */
class TasteVectorStore(context: Context, scope: String = "GLOBAL") {
    private val prefs = context.getSharedPreferences("$PREFS_NAME-$scope", Context.MODE_PRIVATE)

    private var dims: MutableMap<String, Double> = LinkedHashMap()
    private val votes = ArrayDeque<Vote>()
    private var feedbackCount: Int = 0

    /** Один голос в SAR-памяти: с id тайтла, чтобы переголосование могло его вычеркнуть. */
    private data class Vote(val itemId: Int, val dims: Set<String>, val weight: Double)

    init {
        runCatching { load() }
            .onFailure { Log.w(TAG, "load failed: ${it.javaClass.simpleName}") }
    }

    fun feedbackCount(): Int = feedbackCount

    /** Топ измерений вкуса по модулю — для просмотра «что система про меня поняла». */
    fun centroidTop(): List<Pair<String, Double>> =
        dims.entries.sortedByDescending { kotlin.math.abs(it.value) }.take(14).map { it.key to it.value }

    fun applyVote(itemId: Int, features: TasteFeatures, liked: Boolean) {
        val delta = if (liked) LIKE_DELTA else DISLIKE_DELTA
        // Затухание старых измерений, затем вклад голоса.
        for (k in dims.keys) dims[k] = dims[k]!! * DECAY
        features.dims.forEach { d -> dims[d] = (dims[d] ?: 0.0) + delta * 0.25 }
        votes.addLast(Vote(itemId, features.dims, delta))
        while (votes.size > VOTES_MAX) votes.removeFirst()
        feedbackCount++
        persist()
    }

    /** Отмена голоса: вычёркивает из SAR-памяти и возвращает точный вклад измерений. */
    fun undoVote(itemId: Int, features: TasteFeatures, wasLiked: Boolean) {
        val delta = if (wasLiked) LIKE_DELTA else DISLIKE_DELTA
        // Вклад вносился с затуханием истории; при отмене возвращаем ровно внесённое.
        features.dims.forEach { d -> dims[d] = (dims[d] ?: 0.0) - delta * 0.25 }
        for (i in votes.indices.reversed()) {
            if (votes[i].itemId == itemId) {
                votes.removeAt(i)
                break
            }
        }
        persist()
    }

    fun scoreOf(features: TasteFeatures): TasteScore {
        val n = feedbackCount.coerceAtLeast(1)
        val sigma = 0.35 * exp(-n / 40.0)
        val noise = if (sigma > 1e-4) Random.nextDouble(-2.0, 2.0) * sigma else 0.0

        val centroidPart = cosineWithCentroid(features.dims)
        val sarPart = sarSum(features.dims)

        val total = 0.9 * centroidPart + 1.3 * sarPart + features.ratingPrior + noise
        return TasteScore(
            total = total,
            centroidPart = centroidPart,
            sarPart = sarPart,
            noise = noise,
            explored = kotlin.math.abs(noise) > 0.10
        )
    }

    /** Жаккар по измерениям: |A∩B| / |A∪B|; пустые наборы считаем непохожими. */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }.toDouble()
        return inter / (a.size + b.size - inter)
    }

    private fun cosineWithCentroid(itemDims: Set<String>): Double {
        if (itemDims.isEmpty() || dims.isEmpty()) return 0.0
        var dot = 0.0
        var normSq = 0.0
        for (v in dims.values) normSq += v * v
        for (d in itemDims) dot += dims[d] ?: 0.0
        val norm = sqrt(normSq)
        if (norm < 1e-6) return 0.0
        return dot / norm / sqrt(itemDims.size.toDouble())
    }

    /** SAR: Σ вес_голоса × жаккар(кандидат, голосованный), нормированный числом голосов. */
    private fun sarSum(itemDims: Set<String>): Double {
        if (votes.isEmpty()) return 0.0
        var sum = 0.0
        for (vote in votes) sum += vote.weight * jaccard(itemDims, vote.dims)
        return (sum / votes.size).coerceIn(-1.5, 1.5)
    }

    @Synchronized
    private fun persist() {
        val root = JSONObject()
        val dObj = JSONObject()
        dims.forEach { (k, v) -> dObj.put(k, v) }
        root.put("dims", dObj)
        root.put("n", feedbackCount)
        val arr = JSONArray()
        votes.forEach { v ->
            arr.put(JSONObject().put("i", v.itemId).put("d", JSONArray(v.dims)).put("w", v.weight))
        }
        root.put("votes", arr)
        prefs.edit().putString(KEY_STATE, root.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY_STATE, null) ?: return
        val root = JSONObject(raw)
        val dObj = root.optJSONObject("dims") ?: JSONObject()
        for (k in dObj.keys()) dims[k] = dObj.optDouble(k, 0.0)
        feedbackCount = root.optInt("n", 0)
        root.optJSONArray("votes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val set = mutableSetOf<String>()
                val dArr = o.optJSONArray("d") ?: JSONArray()
                for (j in 0 until dArr.length()) set.add(dArr.getString(j))
                votes.addLast(Vote(o.optInt("i", 0), set, o.optDouble("w", 1.0)))
            }
        }
    }

    companion object {
        private const val TAG = "TasteVector"
        private const val PREFS_NAME = "feed_taste_vector"
        private const val KEY_STATE = "state"
        private const val VOTES_MAX = 60

        /** Вклад голоса: лайк +1, дизлайк −1.5 (дизлайк информативнее лайка). */
        const val LIKE_DELTA = 1.0
        const val DISLIKE_DELTA = -1.5

        /** Затухание старых измерений на каждый новый голос. */
        private const val DECAY = 0.98
    }
}
