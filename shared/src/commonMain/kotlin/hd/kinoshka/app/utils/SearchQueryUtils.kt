package hd.kinoshka.app.utils

object SearchQueryUtils {
    private const val EN_CHARS = "qwertyuiop[]asdfghjkl;'zxcvbnm,."
    private const val RU_CHARS = "йцукенгшщзхъфывапролджэячсмитьбю"

    private val enToRuMap = buildMap {
        for (i in EN_CHARS.indices) {
            if (i < RU_CHARS.length) {
                put(EN_CHARS[i], RU_CHARS[i])
            }
        }
    }

    private val ruToEnMap = buildMap {
        for (i in RU_CHARS.indices) {
            if (i < EN_CHARS.length) {
                put(RU_CHARS[i], EN_CHARS[i])
            }
        }
    }

    fun fixKeyboardLayout(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            val lower = ch.lowercaseChar()
            val replaced = when {
                enToRuMap.containsKey(lower) -> enToRuMap[lower]
                ruToEnMap.containsKey(lower) -> ruToEnMap[lower]
                else -> ch
            }
            if (replaced != null) {
                sb.append(if (ch.isUpperCase()) replaced.uppercaseChar() else replaced)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Relevance score for a search result against the typed query. Higher is better.
     * Order of priority: exact title match > title starts with query > title contains query >
     * substring/other. Ties broken by rating then year (passed in for context).
     *
     * This makes search "smarter" — a query like "one piece" surfaces the exact-titled series
     * above merely-rating-ordered results, instead of returning whatever the API ranked first.
     */
    fun relevanceScore(
        query: String,
        nameRu: String?,
        nameOriginal: String?,
        rating: Double?,
        year: Int?
    ): Int {
        val q = query.trim().lowercase()
        if (q.isBlank()) return 0
        val candidates = listOfNotNull(nameRu, nameOriginal).map { it.lowercase() }
        var best = 0
        for (name in candidates) {
            best = maxOf(best, when {
                name == q -> 1000
                name.startsWith(q) -> 700
                name.contains(" $q") || name.contains(" $q ") -> 500
                name.contains(q) -> 300
                else -> 0
            })
        }
        // Tie-breakers: rating (0..10 → 0..100), then recency.
        best += ((rating ?: 0.0) * 10).toInt().coerceIn(0, 100)
        best += (year ?: 0).coerceAtLeast(1900) - 1900
        return best
    }
}
