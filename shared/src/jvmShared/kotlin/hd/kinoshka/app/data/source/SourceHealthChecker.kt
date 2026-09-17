package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Проверка работоспособности источников для экрана «Настройки → Источники».
 * Каждая проверка — один лёгкий сетевой запрос с жёстким таймаутом: полный резолв
 * потока здесь не нужен, важно лишь «хост отвечает и отдаёт ожидаемую форму».
 */
data class SourceHealth(
    val id: String,
    val ok: Boolean,
    val latencyMs: Long,
    val message: String
)

object SourceHealthChecker {
    private const val TAG = "SourceHealthChecker"

    /**
     * Свои источники для проб: приложение ставит лямбду на живой стор при старте
     * (мостом как DdbbHarvestBridge) — чекер prefs не трогает.
     */
    var customSourceProvider: () -> List<CustomSource> = { emptyList() }

    /** Известный тайтл для проб: Матрица (kp=301) есть почти в каждом кино-каталоге. */
    const val PROBE_KINOPOISK_ID = 301

    /** Аниме-проба Shikimori id=1 (Cowboy Bebop): плейлист может быть пустым (лицензия),
     *  важен сам факт ответа API. */
    private const val PROBE_SHIKIMORI_ID = 1

    const val DEFAULT_TIMEOUT_MS = 12_000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .proxySelector(StreamProxySelector())
            .proxyAuthenticator(StreamProxyConfig.okHttpProxyAuthenticator())
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun check(id: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SourceHealth =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val result = withTimeoutOrNull(timeoutMs) {
                runCatching { probe(PlaybackSources.canonical(id)) }.getOrElse { e ->
                    KLog.w(TAG, "health $id failed: ${e.javaClass.simpleName}")
                    false to "Ошибка: ${e.javaClass.simpleName}"
                }
            } ?: (false to "Превышено время ожидания (${timeoutMs / 1000} c)")
            SourceHealth(
                id = PlaybackSources.canonical(id),
                ok = result.first,
                latencyMs = System.currentTimeMillis() - start,
                message = result.second
            )
        }

    suspend fun checkAll(
        ids: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Map<String, SourceHealth> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            ids.distinct().map { id ->
                async { check(id, timeoutMs) }
            }.awaitAll().associateBy { it.id }
        }
    }

    /** true + пояснение при живом источнике, false + причина иначе. */
    private suspend fun probe(id: String): Pair<Boolean, String> = when (id) {
        PlaybackSources.KODIK -> {
            val result = AnimeStreamResolver.kodikSearchMovieByKinopoiskId(PROBE_KINOPOISK_ID)
            when (result.failure) {
                AnimeStreamResolver.KodikSearchFailure.NONE ->
                    if (result.items.isNotEmpty()) true to "OK: найдено ${result.items.size}"
                    else true to "API отвечает, результатов нет"
                AnimeStreamResolver.KodikSearchFailure.NETWORK -> false to "Ошибка сети"
                AnimeStreamResolver.KodikSearchFailure.PROVIDER -> false to "Провайдер недоступен"
            }
        }
        PlaybackSources.SHIKIMORI -> {
            val rows = ShikimoriVideoApi.loadPlaylist(PROBE_SHIKIMORI_ID)
            true to if (rows.isEmpty()) "API отвечает (плейлист пуст)" else "OK: дорожек ${rows.size}"
        }
        PlaybackSources.ANILIBERTY -> {
            // Тот же путь, что у резолвера: поиск по всем трём базам. Живым считается
            // любой ответ в форме поиска релизов — пустая выдача тоже ответ, а не авария.
            probeAniLiberty()
        }
        PlaybackSources.ANILIB -> {
            // Тот же эндпоинт каталога, что у резолвера.
            val body = httpGet(
                "https://api.animelib.org/api/anime?q=naruto", "https://animelib.org/"
            ) ?: return false to "Нет ответа API"
            val trimmed = body.trim()
            if (body.contains("\"data\"") || body.contains("\"items\"") || trimmed.startsWith("[") ||
                (trimmed.startsWith("{") && body.contains("\"id\""))
            ) {
                true to "OK: API отвечает"
            } else {
                KLog.w(TAG, "health ANILIB unexpected: ${body.take(200).replace(Regex("\\s+"), " ")}")
                false to "Неожиданный ответ API"
            }
        }
        PlaybackSources.ANISTAR -> {
            // Сначала каталог (несколько популярных тайтлов), затем живость сайта:
            // отсутствие конкретной статьи не означает мёртвый источник.
            val episodes = AniStarResolver.findEpisodes(listOf("наруто", "naruto", "ван пис", "one piece"))
            if (!episodes.isNullOrEmpty()) true to "OK: серий ${episodes.size}"
            else if (AniStarResolver.ping()) true to "Сайт отвечает, проба не найдена"
            else false to "Сайт не отвечает"
        }
        PlaybackSources.ANIXART -> {
            // Проба — живость API каталога, а не наличие тайтла: пустая выдача тоже ответ.
            if (AnixartVideoResolver.ping()) true to "OK: API отвечает"
            else false to "API не отвечает"
        }
        PlaybackSources.SMARTHARD -> {
            val records = SmarthardApi.loadRecords(PROBE_SHIKIMORI_ID)
            true to if (records.isEmpty()) "API отвечает (записей нет)" else "OK: записей ${records.size}"
        }
        PlaybackSources.TURBO, PlaybackSources.ALLOHA,
        PlaybackSources.VEOVEO, PlaybackSources.COLLAPS -> {
            // ddbb-эмбеды и стендалон-Collaps делят имя: проверяем оба пути.
            val players = DdbbStreamResolver.fetchPlayersSnapshot(PROBE_KINOPOISK_ID)
            if (players.isEmpty()) {
                if (id == PlaybackSources.COLLAPS) probeCollapsDirect()
                else false to "Список плееров пуст"
            } else {
                val hasType = players.any { it.first.equals(PlaybackSources.idToDdbbSourceName(id), ignoreCase = true) }
                when {
                    hasType -> true to "OK: ${players.size} плееров"
                    id == PlaybackSources.COLLAPS -> probeCollapsDirect()
                    else -> true to "ddbb отвечает, типа ${id.lowercase()} нет (${players.map { it.first }})"
                }
            }
        }
        PlaybackSources.VIDEOCDN -> {
            val parse = WebmasterStreamSources.resolveVideoCdn(PROBE_KINOPOISK_ID)
            if (parse != null) true to "OK: ${parse.voiceRows.size} озвучек, ${parse.tracks.size} серий"
            else false to "Ничего не найдено для kp=$PROBE_KINOPOISK_ID"
        }
        PlaybackSources.VOIDBOOST -> {
            val parse = WebmasterStreamSources.resolveVoidboost(PROBE_KINOPOISK_ID)
            if (parse != null) true to "OK: ${parse.voiceRows.size} озвучек, ${parse.tracks.size} серий"
            else false to "Ничего не найдено для kp=$PROBE_KINOPOISK_ID"
        }
        PlaybackSources.HENTAI_ALLHENTAI -> probeHttpHost("https://allhentai.fun/")
        PlaybackSources.HENTAI_HENTAIDREAM -> probeHttpHost("https://hentaidream.fun/")
        PlaybackSources.HENTAI_HENTAIZ -> probeHentaiz()
        PlaybackSources.HENTAI_HANIME1 -> probeHttpHost("https://hanime1.me/")
        PlaybackSources.HENTAI_OPPAI -> probeHttpHost("https://oppai.stream/")
        else -> probeCustom(id)
    }

    /**
     * Проба своего источника: шаблон с kp=301 → fetch → extractFromEmbed. Извлеклось —
     * зелёный «прямой поток», embed живой без потока — зелёный «только веб-режим»
     * (как Alloha/Veoveo), иначе красный с причиной. Шаблонам только под {imdb}
     * честно отвечаем, что проба невозможна. Публична: диалог добавления проверяет
     * ещё не сохранённый черновик.
     */
    suspend fun checkCustomSource(
        source: CustomSource,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): SourceHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) {
            runCatching { probeCustomSource(source) }.getOrElse { e ->
                KLog.w(TAG, "health custom ${source.id} failed: ${e.javaClass.simpleName}")
                false to "Ошибка: ${e.javaClass.simpleName}"
            }
        } ?: (false to "Превышено время ожидания (${timeoutMs / 1000} c)")
        SourceHealth(
            id = source.id,
            ok = result.first,
            latencyMs = System.currentTimeMillis() - start,
            message = result.second
        )
    }

    private fun probeCustomSource(source: CustomSource): Pair<Boolean, String> {
        val url = source.buildUrl(PROBE_KINOPOISK_ID, null)
            ?: return false to "Шаблон без {kp}: проба невозможна"
        val html = httpGet(url, source.effectiveReferer(url)) ?: return false to "Embed не загрузился"
        val extracted = DdbbStreamResolver.extractFromEmbed(html, url)
        return when {
            extracted != null && extracted.second.isNotEmpty() ->
                true to "OK: прямой поток извлекается"
            else -> true to "Embed отвечает (только веб-режим)"
        }
    }

    private suspend fun probeCustom(id: String): Pair<Boolean, String> {
        if (!CustomSource.isCustomId(id)) return false to "Неизвестный источник"
        val custom = customSourceProvider().firstOrNull { it.id == id }
            ?: return false to "Источник удалён"
        return probeCustomSource(custom)
    }

    /**
     * Тот же DLE-поиск, что выполняет резолвер: страница поиска рендерится всегда
     * (даже с нулём результатов), поэтому HTTP 200 — честный сигнал живости.
     */
    private fun probeHentaiz(): Pair<Boolean, String> {
        val base = "https://ru.hentaiiz.org"
        val query = runCatching {
            java.net.URLEncoder.encode("наруто", "UTF-8")
        }.getOrDefault("naruto")
        val body = httpGet("$base/index.php?do=search&subaction=search&story=$query", "$base/")
            ?: return false to "Хост не отвечает"
        return if (body.isNotBlank()) true to "OK: поиск отвечает"
        else false to "Пустой ответ поиска"
    }

    /**
     * Поиск релизов по всем базам AniLiberty (тот же каскад, что у резолвера):
     * одна мёртвая база больше не красит весь источник в красный.
     */
    private suspend fun probeAniLiberty(): Pair<Boolean, String> {
        val bases = listOf(
            "https://anilibria.top",
            "https://api.anilibria.pro",
            "https://api.anilibria.tv"
        )
        var answered = 0
        for (base in bases) {
            val body = httpGet(
                "$base/api/v1/app/search/releases?query=naruto",
                "https://anilibria.top/"
            ) ?: continue
            answered++
            val trimmed = body.trim()
            // Формы, которые понимает парсер резолвера: объект с data/items/results
            // либо топ-уровневый массив; пустая выдача — тоже ответ.
            if (body.contains("\"data\"") || body.contains("\"items\"") ||
                body.contains("\"results\"") || trimmed.startsWith("[")
            ) {
                return true to "OK: API отвечает ($base)"
            }
            KLog.w(TAG, "health ANILIBERTY $base unexpected: ${body.take(200).replace(Regex("\\s+"), " ")}")
        }
        return if (answered > 0) false to "Неожиданный ответ API"
        else false to "Нет ответа API"
    }

    private suspend fun probeCollapsDirect(): Pair<Boolean, String> {
        val parse = WebmasterStreamSources.resolveCollaps(PROBE_KINOPOISK_ID)
        return if (parse != null) true to "OK (стендалон): ${parse.tracks.size} серий"
        else false to "ddbb и стендалон не ответили"
    }

    private fun probeHttpHost(url: String): Pair<Boolean, String> {
        val code = httpCode(url) ?: return false to "Хост не отвечает"
        return if (code < 400) true to "OK: HTTP $code"
        else false to "HTTP $code"
    }

    private fun httpGet(url: String, referer: String?): String? = runCatching {
        val req = Request.Builder().url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .apply {
                referer?.let {
                    addHeader("Referer", it)
                    // Ряд WAF (DDoS-Guard и др.) требует Origin — резолвер шлёт его всегда.
                    originOf(it)?.let { origin -> addHeader("Origin", origin) }
                }
            }
            .build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                KLog.w(TAG, "GET $url -> HTTP ${response.code}")
                return null
            }
            response.body.string().takeIf { it.isNotEmpty() }
        }
    }.onFailure {
        KLog.w(TAG, "GET $url failed: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun originOf(referer: String): String? = runCatching {
        val uri = java.net.URI(referer)
        "${uri.scheme}://${uri.host}"
    }.getOrNull()

    private fun httpCode(url: String): Int? = runCatching {
        val req = Request.Builder().url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(req).execute().use { it.code }
    }.getOrNull()
}
