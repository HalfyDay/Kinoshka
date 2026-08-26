package hd.kinoshka.app.data.feed

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диагностика рекомендательного движка: журнал решений + снапшот вкусов в JSON.
 * Файл пишется в getExternalFilesDir — снимается adb pull'ом без root, кнопка
 * «Поделиться диагностикой» отдаёт тот же текст через ACTION_SEND.
 */
object FeedDiagnostics {
    private const val TAG = "FeedDiagnostics"
    private const val FILE_NAME = "feed_diagnostics.json"
    private const val EVENTS_MAX = 60
    private const val WRITE_THROTTLE_MS = 30_000L
    private const val SEEN_SAMPLE_MAX = 25

    @Volatile private var lastWriteAt = 0L
    @Volatile private var dirty = false
    private val events = ArrayDeque<String>()

    /** Событие решения: реакция пользователя, гард раздела, состав загруженной партии. */
    @Synchronized
    fun record(event: String) {
        val ts = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
        events.addLast("$ts · $event")
        while (events.size > EVENTS_MAX) events.removeFirst()
        dirty = true
    }

    fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    /**
     * Полный отчёт текстом (и запись в файл). Вызывается по кнопке и троттлированно
     * из ViewModel; тяжёлый JSON строится только когда кто-то реально попросил.
     */
    @Synchronized
    fun buildReport(context: Context, interests: InterestProfileStore): String {
        val text = toJson(interests).toString(2)
        runCatching { file(context).writeText(text) }
            .onFailure { Log.w(TAG, "write failed: ${it.javaClass.simpleName}") }
        lastWriteAt = System.currentTimeMillis()
        dirty = false
        return text
    }

    /** Автозапись не чаще раза в 30с и только если были новые события. */
    @Synchronized
    fun maybeAutoWrite(context: Context, interests: InterestProfileStore) {
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (now - lastWriteAt < WRITE_THROTTLE_MS) return
        lastWriteAt = now
        dirty = false
        runCatching { file(context).writeText(toJson(interests).toString(2)) }
            .onFailure { Log.w(TAG, "auto write failed: ${it.javaClass.simpleName}") }
    }

    private fun toJson(interests: InterestProfileStore): JSONObject {
        val root = JSONObject()
        root.put("generatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("lastEnrichedAt", interests.lastEnrichedAt().let {
            if (it == 0L) "никогда" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it))
        })
        root.put("adultConfirmed", interests.isAdultConfirmed())
        root.put("onboardedChips", JSONArray(interests.onboardedChips().sorted()))

        val weights = JSONObject()
        weights.put("genres", sortedJson(interests.weights()))
        weights.put("countries", sortedJson(interests.countryWeights()))
        weights.put("decades", sortedJson(interests.decadeWeights()))
        root.put("weights", weights)

        val seen = interests.seenFeedIds()
        root.put("seenFeedCount", seen.size)
        root.put("seenFeedSample", JSONArray(seen.sortedDescending().take(SEEN_SAMPLE_MAX)))

        synchronized(this) {
            root.put("events", JSONArray(events.toList()))
        }
        return root
    }

    private fun sortedJson(map: Map<String, Double>): JSONObject {
        val obj = JSONObject()
        // Сильнейшие вкусы сверху — отчёт читается как рейтинг.
        map.entries.sortedByDescending { it.value }.forEach { (k, v) -> obj.put(k, v) }
        return obj
    }
}
