package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Резолв JS-плагинов (вариант C): код из [JsPluginStore], контракт [JsSandbox]
 * (`resolveMovie(ctx)` → `{voices[]/tracks[]}`), маппинг в [DdbbStreamResolver.SourceParse].
 *
 * Неймспейс строк — общий `custom|<id>|…` (CUSTOM_-id уникальны между видами):
 * пикер/плеер/скачивание/память идут существующим custom-трактом без изменений,
 * вид источника известен из записи реестра в момент резолва.
 */
object JsPluginResolver {
    private const val TAG = "JsPluginResolver"

    /**
     * Возврат resolveMovie → SourceParse. Пусто (нет валидных ссылок) — null.
     * Лестница трека: явные `qualities` либо одиночный Auto; заголовки — как отдал
     * плагин (дефолтов не подставляем: Referer чужого CDN угадать нельзя).
     */
    fun pluginJsonToParse(custom: CustomSource, json: String): DdbbStreamResolver.SourceParse? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val headersByUrl = LinkedHashMap<String, Map<String, String>>()
        val tracks = mutableListOf<hd.kinoshka.app.data.model.DdbbEpisodeTrack>()
        val ladders = LinkedHashMap<String, Map<String, String>>()
        val voices = mutableListOf<Pair<String, String>>()

        val voicesArr = root.optJSONArray("voices")
        if (voicesArr != null) {
            for (i in 0 until voicesArr.length()) {
                val item = voicesArr.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                val title = item.optString("title").trim().ifEmpty { custom.name }
                val headers = stringMap(item.optJSONObject("headers"))
                headersByUrl.putIfAbsent(url, headers)
                if (voices.none { it.second == url }) voices += title to url
            }
        }
        val tracksArr = root.optJSONArray("tracks")
        if (tracksArr != null) {
            for (i in 0 until tracksArr.length()) {
                val item = tracksArr.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                val season = item.optInt("season", 1).takeIf { it > 0 } ?: 1
                val episode = item.optInt("episode", -1)
                if (episode < 1) continue
                val dubSlug = slugifyCustomName(item.optString("dub").trim()).ifEmpty { "plugin" }
                val dubTitle = item.optString("dubTitle").trim().ifEmpty { custom.name }
                val ladder = LinkedHashMap<String, String>()
                val qualities = item.optJSONObject("qualities")
                if (qualities != null) {
                    for (key in qualities.keys()) {
                        val qUrl = qualities.optString(key)?.trim().orEmpty()
                        if (qUrl.startsWith("http://") || qUrl.startsWith("https://")) {
                            ladder.putIfAbsent(key.trim().takeIf { it.isNotEmpty() } ?: "Auto", qUrl)
                            headersByUrl.putIfAbsent(qUrl, stringMap(item.optJSONObject("headers")))
                        }
                    }
                }
                if (ladder.isEmpty()) {
                    ladder["Auto"] = url
                    headersByUrl.putIfAbsent(url, stringMap(item.optJSONObject("headers")))
                }
                val best = ladder.entries.firstOrNull { it.key != "Auto" } ?: ladder.entries.first()
                ladders[best.value] = ladder.toMap()
                tracks += hd.kinoshka.app.data.model.DdbbEpisodeTrack(
                    dubId = "custom|${custom.id}|$dubSlug",
                    dubTitle = dubTitle,
                    seasonNumber = season,
                    episodeNumber = episode,
                    title = item.optString("title").trim().takeIf { it.isNotEmpty() },
                    playerUrl = best.value
                )
            }
        }
        if (tracks.isEmpty() && voices.isEmpty()) return null
        val firstUrl = tracks.firstOrNull()?.playerUrl ?: voices.first().second
        val firstHeaders = headersByUrl[firstUrl].orEmpty()
        val firstLadder = ladders[firstUrl] ?: mapOf("Auto" to firstUrl)
        return DdbbStreamResolver.SourceParse(
            sourceName = custom.id,
            url = firstUrl,
            headers = firstHeaders,
            qualities = firstLadder,
            voiceRows = voices,
            tracks = tracks.distinctBy { it.dubId to it.episodeNumber }.sortedWith(
                compareBy({ it.dubId }, { it.episodeNumber })
            ),
            ladders = ladders,
            headersByUrl = headersByUrl
        )
    }

    private fun stringMap(obj: org.json.JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            val value = obj.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun ctxJson(kinopoiskId: Int?, imdbId: String?): String =
        JSONObject()
            .put("kinopoiskId", kinopoiskId ?: JSONObject.NULL)
            .put("imdbId", imdbId?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .toString()

    /**
     * Резолв ОДНОГО плагина. [code] инжектится ради тестов (дефолт — из стора).
     * null — не PLUGIN, нет кода, контракт провален или потоков нет.
     */
    suspend fun resolveOne(
        custom: CustomSource,
        kinopoiskId: Int?,
        imdbId: String?,
        code: String? = null
    ): DdbbStreamResolver.SourceParse? = withContext(Dispatchers.IO) {
        if (custom.kind != CustomSourceKind.PLUGIN) return@withContext null
        val pluginCode = code ?: JsPluginStore.loadCode(custom.id)
        if (pluginCode.isNullOrBlank()) {
            KLog.w(TAG, "${custom.id}: no plugin code (not installed?)")
            return@withContext null
        }
        val call = JsSandbox.callJsonFunction(
            pluginCode, "resolveMovie", ctxJson(kinopoiskId, imdbId)
        )
        if (call is JsSandbox.JsCallResult.Failed) {
            KLog.w(TAG, "${custom.id}: resolveMovie failed: ${call.reason}")
            return@withContext null
        }
        pluginJsonToParse(custom, (call as JsSandbox.JsCallResult.Ok).json)?.also {
            KLog.i(TAG, "${custom.id}: ${it.tracks.size} track(s), ${it.voiceRows.size} voice row(s)")
        }
    }

    /**
     * Проба плагина по коду (для черновика из диалога — сохранять не нужно):
     * manifest валиден + resolveMovie «Матрицы» отдал потоки.
     */
    suspend fun probeWithCode(
        custom: CustomSource,
        code: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val rawManifest = JsSandbox.readManifest(code)
        if (rawManifest is JsSandbox.JsCallResult.Failed) {
            return@withContext false to "Манифест: ${rawManifest.reason}"
        }
        val manifest = JsSandbox.parseManifest((rawManifest as JsSandbox.JsCallResult.Ok).json)
            ?: return@withContext false to "Манифест не распознан"
        val call = JsSandbox.callJsonFunction(
            code, "resolveMovie",
            ctxJson(CustomSource.PROBE_KP, CustomSource.PROBE_IMDB)
        )
        if (call is JsSandbox.JsCallResult.Failed) {
            return@withContext false to "«${manifest.name}»: ${call.reason}"
        }
        val parse = pluginJsonToParse(custom, (call as JsSandbox.JsCallResult.Ok).json)
        val count = (parse?.tracks?.size ?: 0) + (parse?.voiceRows?.size ?: 0)
        if (parse == null || count == 0) {
            return@withContext false to "«${manifest.name}»: потоков для Матрицы нет"
        }
        true to "«${manifest.name}» v${manifest.version}, потоков: $count (Матрица)"
    }

    /** Проба сохранённого плагина (код из стора). */
    suspend fun probeSaved(custom: CustomSource): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val code = JsPluginStore.loadCode(custom.id)
            ?: return@withContext false to "Код плагина не найден — переустановите"
        probeWithCode(custom, code)
    }
}
