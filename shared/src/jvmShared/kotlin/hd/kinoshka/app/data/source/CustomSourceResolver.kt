package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Резолв пользовательских embed-источников (вариант A): шаблон → embed-страница →
 * host-agnostic [DdbbStreamResolver.parseGenericEmbed] → SourceParse. Не извлеклось —
 * embed живой, но потока нет: отдаётся webOnly-парс (в нативном пикере источник пуст,
 * зато доступен в веб-плеере как Alloha/Veoveo).
 */
object CustomSourceResolver {
    private const val TAG = "CustomSourceResolver"

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

    suspend fun resolveParses(
        kinopoiskId: Int?,
        imdbId: String?,
        customs: List<CustomSource>
    ): List<DdbbStreamResolver.SourceParse> = withContext(Dispatchers.IO) {
        if (customs.isEmpty()) return@withContext emptyList()
        coroutineScope {
            customs.map { custom ->
                async { resolveOne(custom, kinopoiskId, imdbId) }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun resolveOne(
        custom: CustomSource,
        kinopoiskId: Int?,
        imdbId: String?,
        isSeries: Boolean = false
    ): DdbbStreamResolver.SourceParse? = withContext(Dispatchers.IO) {
        if (custom.kind == CustomSourceKind.STREMIO) {
            return@withContext StremioAddonResolver.resolveMovieParse(custom, imdbId, isSeries)
        }
        val url = custom.buildUrl(kinopoiskId, imdbId)
        if (url == null) {
            KLog.i(TAG, "${custom.id}: skipped (title has no required id for template)")
            return@withContext null
        }
        val headers = mapOf(
            "Referer" to custom.effectiveReferer(url),
            "User-Agent" to USER_AGENT
        )
        if (custom.webOnly) {
            return@withContext webOnlyParse(custom, url, headers)
        }
        val html = fetchHtml(url, headers) ?: return@withContext null
        val parsed = DdbbStreamResolver.parseGenericEmbed(
            sourceName = custom.id,
            dubIdPrefix = "custom|${custom.id}|",
            embedUrl = url,
            html = html,
            headers = headers
        )
        if (parsed != null) return@withContext parsed
        // Диагностика по ТЗ п.10: embed живой, потока нет — голова страницы в лог.
        KLog.w(TAG, "${custom.id}: embed fetched, no stream extracted; page head: " +
            html.take(240).replace(Regex("\\s+"), " "))
        webOnlyParse(custom, url, headers)
    }

    /**
     * WebOnly-парс: пустые voiceRows/tracks — в нативном пикере источник пуст (Empty),
     * запуск идёт через веб-плеер. qualities non-empty, чтобы парс не отбрасывался
     * как битый на ранних фильтрах.
     */
    fun webOnlyParse(
        custom: CustomSource,
        embedUrl: String,
        headers: Map<String, String>
    ): DdbbStreamResolver.SourceParse = DdbbStreamResolver.SourceParse(
        sourceName = custom.id,
        url = embedUrl,
        headers = headers,
        qualities = mapOf("Auto" to embedUrl)
    )

    private fun fetchHtml(url: String, headers: Map<String, String>): String? {
        val host = HostCooldown.hostOf(url)
        if (host.isNotEmpty() && HostCooldown.shouldSkip(host)) {
            KLog.i(TAG, "custom ${host.take(40)} embed skipped (cooldown)")
            return null
        }
        return try {
            val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) ->
                if (!k.equals("User-Agent", ignoreCase = true)) {
                    try { builder.header(k, v) } catch (_: IllegalArgumentException) { }
                }
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "custom $url -> HTTP ${response.code}")
                    return null
                }
                response.body.string().takeIf { it.isNotEmpty() }?.also {
                    if (host.isNotEmpty()) HostCooldown.recordSuccess(host)
                }
            }
        } catch (e: Exception) {
            KLog.w(TAG, "custom embed fetch failed: ${e.javaClass.simpleName}")
            if (host.isNotEmpty() && HostCooldown.isConnectivityFailure(e)) HostCooldown.recordFailure(host)
            null
        }
    }
}
