package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Синк-test доки: docs/js-plugins/example.js обязан быть рабочим плагином
 * (manifest + резолв Матрицы через стаб-сеть). Протухший пример роняет сборку.
 */
class JsPluginDocTest {

    private fun exampleJs(): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = java.io.File(dir, "docs/js-plugins/example.js")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            dir = dir.parentFile ?: return@repeat
        }
        error("docs/js-plugins/example.js not found from ${System.getProperty("user.dir")}")
    }

    private val stubHttp = object : JsSandbox.HostHttp {
        override fun get(url: String, headers: Map<String, String>): JsSandbox.HttpResult {
            if ("/embed/serial/kp/" in url) {
                val kp = url.substringAfterLast("/")
                return JsSandbox.HttpResult(
                    200, emptyMap(),
                    """<div class="ep" data-n="1" data-title="Pilot" data-file="https://cdn.example.com/s${kp}e1.m3u8" data-1080="https://cdn.example.com/s${kp}e1_1080.m3u8" data-720="https://cdn.example.com/s${kp}e1_720.m3u8"></div>""" +
                    """<div class="ep" data-n="2" data-title="Second" data-file="https://cdn.example.com/s${kp}e2.m3u8"></div>""" +
                    """<div class="ep" data-n="3" data-title="Third" data-file="https://cdn.example.com/s${kp}e3.m3u8"></div>"""
                )
            }
            if ("/embed/kp/" in url) {
                val kp = url.substringAfterLast("/")
                return JsSandbox.HttpResult(
                    200, emptyMap(),
                    """<html><script>var player = {file:"https://cdn.example.com/$kp/master.m3u8"};</script></html>"""
                )
            }
            return JsSandbox.HttpResult(404, error = "HTTP 404")
        }

        override fun post(url: String, body: String, headers: Map<String, String>): JsSandbox.HttpResult =
            JsSandbox.HttpResult(404, error = "HTTP 404")
    }

    private val custom = CustomSource(
        id = "CUSTOM_DOC_EXAMPLE",
        name = "Example Embed",
        urlTemplate = "",
        kind = CustomSourceKind.PLUGIN,
        endpoint = "https://example.com/plugin.js"
    )

    @Test
    fun `doc example manifest is valid`() {
        val raw = JsSandbox.readManifest(exampleJs())
        assertTrue(raw is JsSandbox.JsCallResult.Ok)
        val manifest = JsSandbox.parseManifest((raw as JsSandbox.JsCallResult.Ok).json)!!
        assertEquals("Example Embed", manifest.name)
        assertEquals("1.1.0", manifest.version)
        assertEquals(JsSandbox.JS_API_VERSION, manifest.api)
    }

    @Test
    fun `doc example resolves matrix to one voice`() {
        val code = exampleJs()
        val bindings = JsSandbox.HostBindings(http = stubHttp, onLog = {})
        val call = JsSandbox.callJsonFunction(
            code, "resolveMovie",
            """{"kinopoiskId":${CustomSource.PROBE_KP},"imdbId":"${CustomSource.PROBE_IMDB}"}""",
            bindings
        )
        assertTrue(call is JsSandbox.JsCallResult.Ok)
        val parse = JsPluginResolver.pluginJsonToParse(
            custom, (call as JsSandbox.JsCallResult.Ok).json
        )!!
        assertEquals(1, parse.voiceRows.size)
        assertEquals(
            "https://cdn.example.com/${CustomSource.PROBE_KP}/master.m3u8",
            parse.voiceRows.first().second
        )
    }

    @Test
    fun `doc example skips title without kinopoisk id`() {
        val code = exampleJs()
        val bindings = JsSandbox.HostBindings(http = stubHttp, onLog = {})
        val call = JsSandbox.callJsonFunction(
            code, "resolveMovie", """{"kinopoiskId":null,"imdbId":"tt0133093"}""", bindings
        )
        assertTrue(call is JsSandbox.JsCallResult.Ok)
        assertTrue(
            JsPluginResolver.pluginJsonToParse(custom, (call as JsSandbox.JsCallResult.Ok).json) == null
        )
    }

    @Test
    fun `doc example resolves series to three tracks with ladder`() {
        val code = exampleJs()
        val bindings = JsSandbox.HostBindings(http = stubHttp, onLog = {})
        val call = JsSandbox.callJsonFunction(
            code, "resolveMovie",
            """{"kinopoiskId":${CustomSource.PROBE_KP},"imdbId":"${CustomSource.PROBE_IMDB}"}""",
            bindings
        )
        assertTrue(call is JsSandbox.JsCallResult.Ok)
        val parse = JsPluginResolver.pluginJsonToParse(
            custom, (call as JsSandbox.JsCallResult.Ok).json
        )!!
        assertEquals(3, parse.tracks.size)
        assertEquals(listOf(1, 2, 3), parse.tracks.map { it.episodeNumber }.sorted())
        // Первая серия — с лестницей: играет лучшее (1080p), Auto в запасе.
        val first = parse.tracks.first { it.episodeNumber == 1 }
        assertEquals("https://cdn.example.com/s${CustomSource.PROBE_KP}e1_1080.m3u8", first.playerUrl)
        val ladder = parse.ladders[first.playerUrl]!!
        assertEquals("https://cdn.example.com/s${CustomSource.PROBE_KP}e1_720.m3u8", ladder["720p"])
        // Вторая — без лестницы: одиночный Auto.
        val second = parse.tracks.first { it.episodeNumber == 2 }
        assertEquals(mapOf("Auto" to second.playerUrl), parse.ladders[second.playerUrl])
    }

    @Test
    fun `doc example tracks become one anime dub with three episodes`() {
        val code = exampleJs()
        val bindings = JsSandbox.HostBindings(http = stubHttp, onLog = {})
        val call = JsSandbox.callJsonFunction(
            code, "resolveMovie",
            """{"kinopoiskId":${CustomSource.PROBE_KP},"imdbId":"${CustomSource.PROBE_IMDB}"}""",
            bindings
        )
        assertTrue(call is JsSandbox.JsCallResult.Ok)
        val parse = JsPluginResolver.pluginJsonToParse(
            custom, (call as JsSandbox.JsCallResult.Ok).json
        )!!
        val translations = AnimeStreamResolver.customParseToTranslations(custom, parse)
        assertEquals(1, translations.size)
        assertEquals("Студия Пример", translations.first().title)
        assertEquals(
            listOf(1, 2, 3),
            translations.first().episodes.map { it.number }
        )
        assertEquals("Pilot", translations.first().episodes.first().title)
    }
}
