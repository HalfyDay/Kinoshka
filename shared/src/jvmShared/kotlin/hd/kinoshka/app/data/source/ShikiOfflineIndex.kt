package hd.kinoshka.app.data.source

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Мост к бандлу индекса: app-модуль кладёт сюда ридер
 * assets/shiki_index.json.gz (лениво, без цены холодного старта).
 */
object ShikiIndexBridge {
    var provider: (() -> ByteArray?)? = null
}

/**
 * Офлайн-индекс Shikimori (id/name/russian/english/synonyms/kind/year/score):
 * выяснение shikimoriId по названию без единого поискового запроса.
 * Живой поиск упирался в лимит 5rps/90rpm (~9 мин на catch-up); индекс +
 * батч-briefs (50/chunk) укладывают тот же объём в ~1 минуту.
 *
 * Каскад уровней — тот же, что у searchShikimoriForAnixart (full →
 * season-alias → spaceless → reverse-prefix → quoted → core → prefix),
 * сверки (год/сезон-вид) НЕ здесь: их делает общий tryPullHit уже по
 * brief-записям, как и для живого поиска. Здесь только кандидаты
 * (до 3 лучших по score на первом сработавшем уровне).
 */
class ShikiOfflineIndex private constructor(
    private val byId: Map<Int, Entry>,
    private val normMap: Map<String, List<Int>>,
    private val solidMap: Map<String, List<Int>>,
    private val coreMap: Map<String, List<Int>>,
    private val quotedMap: Map<String, List<Int>>,
    private val sortedNorms: List<String>
) {
    data class Entry(
        val id: Int,
        val scoreBp: Int
    )

    data class Match(
        val entryIds: List<Int>,
        val level: String
    )

    /** До 3 лучших (score, затем id) из кандидатов. */
    private fun top(cands: Collection<Int>): List<Int> {
        if (cands.isEmpty()) return emptyList()
        return cands.distinct()
            .mapNotNull { byId[it] }
            .sortedWith(compareByDescending<Entry> { it.scoreBp }.thenBy { it.id })
            .take(3)
            .map { it.id }
    }

    /**
     * Каскад по преднормализованным запросам релиза. Возвращает null,
     * если ни один уровень не дал кандидатов (релиз уйдёт в живой поиск).
     */
    fun match(
        norms: List<String>,
        solids: Set<String>,
        cores: Set<String>
    ): Match? {
        val matcher = TitleMatching
        // L1: полное точное (по запросам, по порядку — как проход 1 живого).
        for (q in norms) {
            val hit = top(normMap[q].orEmpty())
            if (hit.isNotEmpty()) return Match(hit, "full")
        }
        // L2: алиасы сезонных хвостов (сезон сохраняют).
        for (q in norms) {
            val cands = mutableSetOf<Int>()
            for (a in matcher.seasonAliases(q)) {
                normMap[a]?.let { cands.addAll(it) }
            }
            val hit = top(cands)
            if (hit.isNotEmpty()) return Match(hit, "alias")
        }
        // L3: беспробельное точное.
        mutableSetOf<Int>().let { cands ->
            for (s in solids) solidMap[s]?.let { cands.addAll(it) }
            val hit = top(cands)
            if (hit.isNotEmpty()) return Match(hit, "spaceless")
        }
        // L4: обратный префикс (запрос — начало имени; хвост-продолжение — отказ).
        for (q in norms) {
            val prefix = "$q "
            var lo = sortedNorms.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
            val cands = mutableSetOf<Int>()
            while (lo < sortedNorms.size) {
                val t = sortedNorms[lo]
                if (!t.startsWith(prefix)) break
                val tail = t.removePrefix(prefix).trim()
                if (tail.isNotEmpty() && !matcher.isContinuationTail(tail)) {
                    normMap[t]?.let { cands.addAll(it) }
                }
                lo++
            }
            val hit = top(cands)
            if (hit.isNotEmpty()) return Match(hit, "reverse")
        }
        // L5: алиас в кавычках.
        mutableSetOf<Int>().let { cands ->
            for (q in norms) quotedMap[q]?.let { cands.addAll(it) }
            val hit = top(cands)
            if (hit.isNotEmpty()) return Match(hit, "quoted")
        }
        // L6: ядро без декоративных маркеров.
        mutableSetOf<Int>().let { cands ->
            for (c in cores) {
                if (c.isEmpty()) continue
                coreMap[c]?.let { cands.addAll(it) }
            }
            val hit = top(cands)
            if (hit.isNotEmpty()) return Match(hit, "core")
        }
        // L7: префикс (кандидат — начало запроса; короткие ядра запрещены).
        for (q in norms) {
            val words = q.split(' ')
            val cands = mutableSetOf<Int>()
            for (i in 1 until words.size) {
                val t = words.take(i).joinToString(" ")
                if (!(t.length >= 6 || t.any { c -> c.code >= 0x2E80 })) continue
                normMap[t]?.let { cands.addAll(it) }
            }
            val hit = top(cands)
            if (hit.isNotEmpty()) return Match(hit, "prefix")
        }
        return null
    }

    companion object {
        /** Разбор бандла [id, year, kind, scoreBp, name, ru, en, [syn], [quoted]]. */
        fun parse(gzippedJson: ByteArray): ShikiOfflineIndex? = runCatching {
            val json = GZIPInputStream(ByteArrayInputStream(gzippedJson))
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JsonParser.parseString(json).asJsonObject
            val arr = root.getAsJsonArray("entries") ?: return null
            val matcher = TitleMatching
            val byId = HashMap<Int, Entry>(34000)
            val normMap = HashMap<String, MutableList<Int>>(90000)
            val solidMap = HashMap<String, MutableList<Int>>(90000)
            val coreMap = HashMap<String, MutableList<Int>>(60000)
            val quotedMap = HashMap<String, MutableList<Int>>(512)
            fun add(map: MutableMap<String, MutableList<Int>>, key: String, id: Int) {
                if (key.isEmpty()) return
                map.getOrPut(key) { ArrayList(2) }.add(id)
            }
            for (el in arr) {
                val a = el.asJsonArray
                if (a.size() < 9) continue
                val id = a[0].asInt
                if (id <= 0) continue
                val scoreBp = a[3].asInt
                byId[id] = Entry(id, scoreBp)
                val titles = LinkedHashSet<String>()
                for (i in intArrayOf(4, 5, 6)) {
                    val t = matcher.normalizeTitle(a[i].asString)
                    if (t.isNotEmpty()) titles.add(t)
                }
                for (s in a[7].asJsonArray) {
                    val t = matcher.normalizeTitle(s.asString)
                    if (t.isNotEmpty()) titles.add(t)
                }
                for (t in titles) {
                    add(normMap, t, id)
                    val solid = t.replace(" ", "")
                    if (solid.isNotEmpty()) add(solidMap, solid, id)
                    val core = matcher.stripDecorativeMarkers(t)
                    if (core.isNotEmpty()) add(coreMap, core, id)
                }
                for (q in a[8].asJsonArray) {
                    val t = matcher.normalizeTitle(q.asString)
                    if (t.isNotEmpty()) add(quotedMap, t, id)
                }
            }
            val sortedNorms = normMap.keys.sorted()
            ShikiOfflineIndex(byId, normMap, solidMap, coreMap, quotedMap, sortedNorms)
        }.getOrNull()
    }
}
