package hd.kinoshka.app.data.source

/**
 * Скоринг названий для выбора лучшего совпадения среди результатов поиска провайдеров.
 * Дублирует приватные хелперы HentaiStreamResolver (app) — общий код не может ссылаться
 * на app-модуль; при изменении скоринга там синхронизируйте и эту копию.
 */
object TitleMatching {
    /** Normalized title view of a search hit, keyed for picking. */
    data class CandidateView(val key: String, val title: String)

    fun slugWords(path: String): String =
        path.substringAfterLast('/').substringBefore(".html").replace('-', ' ')

    fun normalizeTitle(raw: String): String =
        raw.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /**
     * Scores every hit and returns the key of the best one (null when nothing is credible).
     *
     * Ranking guards against the failure mode "first result wins": exact equality > prefix with
     * a small episode number ("Bible Black 1" beats "Bible Black 5" and any spinoff) > weak
     * containment. Containment additionally requires the wanted phrase to be long enough
     * (≥6 latin chars or ≥3 CJK chars) so generic words can never produce a match.
     */
    fun pickBest(items: List<CandidateView>, query: String): String? {
        val wanted = normalizeTitle(query)
        if (wanted.isEmpty()) return null
        val cjkWanted = wanted.any { it.code >= 0x2E80 }
        val minContain = if (cjkWanted) 3 else 6
        var bestKey: String? = null
        var bestScore = 0
        var bestLength = Int.MAX_VALUE
        var bestEpisode = Int.MAX_VALUE
        for (item in items) {
            val candidate = normalizeTitle(item.title)
            if (candidate.isEmpty()) continue
            val score = scoreCandidate(candidate, wanted, minContain)
            if (score <= 0) continue
            // Equal-score ties (a whole episode list) go to the lowest trailing episode number,
            // then to the shortest title — "… 1" must beat "… 3" regardless of site ordering.
            val episode = trailingEpisode(candidate)
            if (score > bestScore ||
                (score == bestScore && (episode < bestEpisode || (episode == bestEpisode && candidate.length < bestLength)))
            ) {
                bestScore = score
                bestLength = candidate.length
                bestEpisode = episode
                bestKey = item.key
            }
        }
        return bestKey
    }

    private fun trailingEpisode(normalizedTitle: String): Int =
        Regex("(^| )(\\d{1,3})$").find(normalizedTitle)?.groupValues?.get(2)?.toIntOrNull()
            ?: Int.MAX_VALUE

    private fun scoreCandidate(candidate: String, wanted: String, minContain: Int): Int {
        if (candidate == wanted) return 100
        // Candidate starts with the whole wanted phrase → series entry; prefer low episode numbers.
        if (candidate.startsWith(wanted)) {
            val rest = candidate.removePrefix(wanted)
            if (rest.isEmpty()) return 95
            if (rest.startsWith(" ")) {
                val tail = rest.trim()
                val episode = tail.toIntOrNull()
                return when {
                    episode != null && episode in 1..99 -> 90 - episode.coerceAtMost(20)
                    tail.length <= 12 -> 78
                    else -> 70
                }
            }
        }
        // Wanted extends the candidate ("Kowaku no Toki" vs earlier franchise entry) — usable.
        if (wanted.startsWith("$candidate ")) return 55
        // Whole-word containment, only for distinctive phrases.
        if (wanted.length >= minContain &&
            Regex("(^| )${Regex.escape(wanted)}( |$)").containsMatchIn(candidate)
        ) return 60
        // Ромадзи-тире: Shikimori «Oneechan» против каталога «Onee-chan» — после нормализации
        // это одно слово против двух, фразовые проверки выше рвутся. Сравниваем без пробелов.
        val solidWanted = wanted.replace(" ", "")
        if (solidWanted.length >= minContain && candidate.replace(" ", "").contains(solidWanted)) return 50
        return -1
    }
}
