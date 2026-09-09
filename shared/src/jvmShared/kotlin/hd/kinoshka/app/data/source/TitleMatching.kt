package hd.kinoshka.app.data.source

/**
 * Скоринг названий для выбора лучшего совпадения среди результатов поиска провайдеров.
 * Дублирует приватные хелперы HentaiStreamResolver (app) — общий код не может ссылаться
 * на app-модуль; при изменении скоринга там синхронизируйте и эту копию.
 */
object TitleMatching {
    /** Normalized title view of a search hit, keyed for picking. */
    data class CandidateView(val key: String, val title: String)

    // Предкомпилированные паттерны: раньше каждый normalizeTitle/scoreCandidate/trailingEpisode
    // компилировал регэкспы заново — пул Anixart (500 релизов × 873 кандидата на Main) уходил
    // в ANR. Горячий путь регэкспов больше не создаёт.
    private val PARENS_REGEX = Regex("\\([^)]*\\)")
    private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{Nd}]+")
    private val SPACES_REGEX = Regex("\\s+")
    private val TRAILING_EPISODE_REGEX = Regex("(^| )(\\d{1,3})$")

    /**
     * Декоративные маркеры Anixart, которых нет в канонических именах Shikimori:
     * хвостовые сезонные («K-On! 2», «Chaika TV-1», «Oregairu Zoku 2») и ведущие
     * «киношные» («Gekijouban …», «Eiga …»). Применяется к НОРМАЛИЗОВАННОМУ
     * названию (там уже нижний регистр, «TV-1»→«tv 1»).
     * Урезанное ядро используется только фолбэком после полного точного
     * (см. searchShikimoriForAnixart): голое число («Steins;Gate 0») и ядро S2,
     * совпавшее с именем S1 в каталоге, — известные оговорки; импорт чужого
     * тайтла всё равно хуже пропуска, поэтому ядро матчится лишь против
     * результатов поиска ПОЛНОГО запроса.
     * Двоеточия-субтитры («Hyouka: ...») НЕ трогаем осознанно: там full-match
     * уже не спасёт от схлопывания разных записей («Naruto: Shippuuden»→«Naruto»).
     */
    private val TRAILING_SEASON_MARKER_REGEX =
        Regex("\\s+(tv\\s*\\d+|s\\d+|\\d+(?:nd|rd|th)?\\s+season|season\\s+\\d+|part\\s+\\d+|тв\\s*\\d+|\\d+)$")

    /**
     * Ядро названия без декоративных маркеров; без маркеров возвращает вход как есть.
     * Строго один слой с каждого конца: цикл «до упора» съедал бы число в имени
     * («Mob Psycho 100 TV-2»→«Mob Psycho» вместо «Mob Psycho 100»).
     */
    fun stripDecorativeMarkers(normalized: String): String {
        var current = TRAILING_SEASON_MARKER_REGEX.replace(normalized, "").trim()
        current = LEADING_MOVIE_MARKER_REGEX.replace(current, "").trim()
        return current.ifEmpty { normalized }
    }

    /** Ведущие «киношные» маркеры («Gekijouban Steins;Gate …» — полнометражка). */
    private val LEADING_MOVIE_MARKER_REGEX = Regex("^(gekijouban|eiga)\\s+")

    /** Хвост «продолжения» (сиквел-маркер), а не субтитр: таким записям reverse-prefix не матчится. */
    private val SEQUEL_TAIL_REGEX =
        Regex("^(tv\\s*\\d+|s\\d+|\\d+(?:nd|rd|th)?\\s+season|season\\s+\\d+|part\\s+\\d+|ii|iii|iv|\\d+)$")

    /**
     * Обратный префикс: запрос — начало имени кандидата («Maou Gakuin» vs полное
     * «Maou Gakuin no Futekigousha: Shijou Saikyou …»). Хвост-«продолжение»
     * (сезон/сиквел-маркер) — отказ: «Log Horizon» не должен матчить «Log Horizon
     * 2nd Season», когда самого «Log Horizon» в выдаче нет. Хвост-субтитр — матч.
     * Хвост, начинающийся с номера («4 Nikushimi …» — номерной инсталлмент),
     * — тоже отказ: какой именно фильм нужен, по запросу не понять.
     */
    fun isSequelTail(tail: String): Boolean = SEQUEL_TAIL_REGEX.matches(tail)

    /** Хвост начинается с номера/римской цифры: номерной инсталлмент, не субтитр. */
    private val NUMBERED_TAIL_REGEX = Regex("^(\\d+|ii|iii|iv)\\b.*")

    /** Продолжение любого вида: полный сиквел-маркер или номерной хвост. */
    fun isContinuationTail(tail: String): Boolean =
        isSequelTail(tail) || NUMBERED_TAIL_REGEX.containsMatchIn(tail)

    private val TRAILING_TV_SEASON_REGEX = Regex("\\s+tv\\s*(\\d+)$")
    private val TRAILING_S_SEASON_REGEX = Regex("\\s+s(\\d+)$")
    private val TRAILING_BARE_SEASON_REGEX = Regex("\\s+(\\d+)$")
    private val QUOTED_ALIAS_REGEX = Regex("\"([^\"]+)\"")

    /**
     * Алиасы в кавычках из официального названия («… "Shomin Sample" …»):
     * кавычки в названиях аниме почти всегда маркируют обиходное имя.
     * Возвращает нормализованные сегменты; пусто — если кавычек нет.
     */
    fun quotedAliases(normalizedCandidate: String): List<String> =
        QUOTED_ALIAS_REGEX.findAll(normalizedCandidate)
            .map { normalizeTitle(it.groupValues[1]) }
            .filter { it.isNotEmpty() }
            .toList()

    private fun ordinal(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }

    /**
     * Алиасы сезонных хвостов под конвенции Shikimori («TV-2»→«2nd Season»,
     * «S2»→«Season 2», «K-On! 2»→«K-On 2nd Season»). В отличие от [stripSeasonMarkers]
     * сезон СОХРАНЯЮТ: матчинг алиасов не может схлопнуть S2 в S1. Без маркера —
     * пусто.
     */
    fun seasonAliases(normalized: String): List<String> {
        TRAILING_TV_SEASON_REGEX.find(normalized)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let
            return listOf(
                normalized.substring(0, it.range.first) + " " + ordinal(n) + " season",
                normalized.substring(0, it.range.first) + " season " + n
            )
        }
        TRAILING_S_SEASON_REGEX.find(normalized)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let
            return listOf(
                normalized.substring(0, it.range.first) + " " + ordinal(n) + " season",
                normalized.substring(0, it.range.first) + " season " + n
            )
        }
        TRAILING_BARE_SEASON_REGEX.find(normalized)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let
            if (n in 2..9) {
                return listOf(
                    normalized.substring(0, it.range.first) + " " + ordinal(n) + " season",
                    normalized.substring(0, it.range.first) + " season " + n
                )
            }
        }
        return emptyList()
    }

    fun slugWords(path: String): String =
        path.substringAfterLast('/').substringBefore(".html").replace('-', ' ')

    fun normalizeTitle(raw: String): String =
        raw.lowercase()
            // ё/е, э/е — одна и та же транслитерация в разных каталогах.
            // Кириллица/латиница-омоглифы («Magiсa» с русской «с», «Psі» с
            // украинской «і» в данных Anixart): сводим до сравнения. Свёртка
            // применяется к обеим сторонам, равенство истинных пар сохраняется;
            // ложное равенство требует двух РАЗНЫХ тайтлов в одной выдаче,
            // различающихся только этими буквами, — такого не встречается.
            // НЕ сводим: иа/еа («Гиас»/«Геас»), en-vs-romaji («Lelouch of the
            // Rebellion» vs «Hangyaku no Lelouch») — там нужен словарь синонимов.
            .replace('ё', 'е')
            // э/е — та же история («Кэйон»/«Кейон», «Геас» не ловится, там иа/еа —
            // это уже другой класс, см. выше).
            .replace('э', 'е')
            // Ψ в названиях («Saiki Kusuo no Ψ-nan»): Anixart пишет «Psi»/«Sainan»,
            // Shikimori держит символ. Других греческих букв в названиях нет.
            .replace("ψ", "psi")
            .replace("Ψ", "psi")
            .replace('а', 'a')
            .replace('е', 'e')
            .replace('о', 'o')
            .replace('р', 'p')
            .replace('с', 'c')
            .replace('х', 'x')
            .replace('і', 'i')
            .replace('н', 'h')
            .replace('м', 'm')
            .replace('т', 't')
            .replace('к', 'k')
            .replace('в', 'b')
            .replace('у', 'y')
            .replace(PARENS_REGEX, " ")
            .replace(NON_ALNUM_REGEX, " ")
            .trim()
            .replace(SPACES_REGEX, " ")

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
        return pickBestNormalized(items.map { normalizeTitle(it.title) to it.key }, wanted)
    }

    /**
     * Тот же скоринг по преднормализованным кандидатам (normalizedTitle to key):
     * вызывающая сторона готовит список один раз на весь пул, а не нормализует
     * 873 названия на каждый из 500 релизов.
     */
    fun pickBestNormalized(normed: List<Pair<String, String>>, query: String): String? {
        val wanted = normalizeTitle(query)
        if (wanted.isEmpty()) return null
        val cjkWanted = wanted.any { it.code >= 0x2E80 }
        val minContain = if (cjkWanted) 3 else 6
        val containRegex =
            if (wanted.length >= minContain) Regex("(^| )${Regex.escape(wanted)}( |$)") else null
        val solidWanted = wanted.replace(" ", "")
        var bestKey: String? = null
        var bestScore = 0
        var bestLength = Int.MAX_VALUE
        var bestEpisode = Int.MAX_VALUE
        for ((candidate, key) in normed) {
            if (candidate.isEmpty()) continue
            val score = scoreCandidate(candidate, wanted, minContain, containRegex, solidWanted)
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
                bestKey = key
            }
        }
        return bestKey
    }

    private fun trailingEpisode(normalizedTitle: String): Int =
        TRAILING_EPISODE_REGEX.find(normalizedTitle)?.groupValues?.get(2)?.toIntOrNull()
            ?: Int.MAX_VALUE

    private fun scoreCandidate(
        candidate: String,
        wanted: String,
        minContain: Int,
        containRegex: Regex?,
        solidWanted: String
    ): Int {
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
        if (containRegex != null && containRegex.containsMatchIn(candidate)) return 60
        // Ромадзи-тире: Shikimori «Oneechan» против каталога «Onee-chan» — после нормализации
        // это одно слово против двух, фразовые проверки выше рвутся. Сравниваем без пробелов.
        if (solidWanted.length >= minContain && candidate.replace(" ", "").contains(solidWanted)) return 50
        return -1
    }
}
