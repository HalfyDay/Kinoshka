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
        assertEquals("1.0.0", manifest.version)
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
}
