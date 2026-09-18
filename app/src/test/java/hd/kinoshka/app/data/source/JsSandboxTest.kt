package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSandboxTest {

    private val stubHttp = object : JsSandbox.HostHttp {
        override fun get(url: String, headers: Map<String, String>): JsSandbox.HttpResult {
            assertTrue("UA" in headers.values.joinToString())
            return JsSandbox.HttpResult(200, mapOf("X-Stub" to "1"), """{"ok":true,"url":"$url"}""")
        }

        override fun post(url: String, body: String, headers: Map<String, String>): JsSandbox.HttpResult =
            JsSandbox.HttpResult(200, emptyMap(), """{"echo":$body}""")
    }

    private fun bindings(logs: MutableList<String>) =
        JsSandbox.HostBindings(http = stubHttp, onLog = { logs += it })

    @Test
    fun `json round-trips through a function`() {
        val code = "function echo(arg) { return arg; }"
        val out = JsSandbox.callJsonFunction(code, "echo", """{"a":1}""", bindings(mutableListOf()))
        assertTrue(out is JsSandbox.JsCallResult.Ok)
        assertEquals("""{"a":1}""", (out as JsSandbox.JsCallResult.Ok).json)
    }

    @Test
    fun `missing function is a clean failure`() {
        val out = JsSandbox.callJsonFunction("var x = 1;", "nope", "{}")
        assertTrue(out is JsSandbox.JsCallResult.Failed)
        assertTrue((out as JsSandbox.JsCallResult.Failed).reason.contains("no function nope"))
    }

    @Test
    fun `java access is blocked`() {
        val code = """function f() { return java.lang.System.getProperty("os.name"); }"""
        val out = JsSandbox.callJsonFunction(code, "f", "{}")
        assertTrue(out is JsSandbox.JsCallResult.Failed)
        val reason = (out as JsSandbox.JsCallResult.Failed).reason
        assertTrue("JS error" in reason)
    }

    @Test
    fun `packages backdoor is blocked`() {
        val code = """function f() { return Packages.java.lang.System.out; }"""
        val out = JsSandbox.callJsonFunction(code, "f", "{}")
        assertTrue(out is JsSandbox.JsCallResult.Failed)
    }

    @Test
    fun `infinite loop dies on cpu limit`() {
        val code = """function f() { while (true) { var x = 1 + 2; } return "x"; }"""
        val out = JsSandbox.callJsonFunction(
            code, "f", "{}",
            instructionLimit = 200_000L, timeoutMs = 10_000L
        )
        assertTrue(out is JsSandbox.JsCallResult.Failed)
        assertTrue((out as JsSandbox.JsCallResult.Failed).reason.contains("CPU limit"))
    }

    @Test
    fun `host log and http work from script`() {
        val logs = mutableListOf<String>()
        val code = """
            function f(arg) {
                log("hello from js");
                var res = httpGet("https://example.com/e", {"X-A": "UA"});
                if (res.status !== 200) return JSON.stringify({fail: res.error});
                return JSON.stringify({logged: true, stub: res.headers["X-Stub"], url: JSON.parse(res.body).url});
            }
        """.trimIndent()
        val out = JsSandbox.callJsonFunction(code, "f", "{}", bindings(logs))
        assertTrue(out is JsSandbox.JsCallResult.Ok)
        val json = org.json.JSONObject((out as JsSandbox.JsCallResult.Ok).json)
        assertTrue(json.getBoolean("logged"))
        assertEquals("1", json.getString("stub"))
        assertEquals("https://example.com/e", json.getString("url"))
        assertEquals(listOf("hello from js"), logs)
    }

    @Test
    fun `non-string return is a clean failure`() {
        val code = """function f() { return {a: 1}; }"""
        val out = JsSandbox.callJsonFunction(code, "f", "{}")
        assertTrue(out is JsSandbox.JsCallResult.Failed)
        assertTrue((out as JsSandbox.JsCallResult.Failed).reason.contains("JSON string"))
    }

    @Test
    fun `manifest parses and validates`() {
        val code = """
            function manifest() {
                return JSON.stringify({name: "Мой", version: "1.2.0",
                    author: "@n", description: "d", kinoshkaApi: 1});
            }
        """.trimIndent()
        val raw = JsSandbox.readManifest(code)
        assertTrue(raw is JsSandbox.JsCallResult.Ok)
        val m = JsSandbox.parseManifest((raw as JsSandbox.JsCallResult.Ok).json)!!
        assertEquals("Мой", m.name)
        assertEquals("1.2.0", m.version)
        assertEquals("@n", m.author)
        assertEquals(1, m.api)
    }

    @Test
    fun `manifest rejects bad api and bad name`() {
        val badApi = """function manifest() { return JSON.stringify({name: "X", version: "1", kinoshkaApi: 2}); }"""
        val r1 = JsSandbox.readManifest(badApi)
        assertTrue(r1 is JsSandbox.JsCallResult.Failed)
        assertTrue((r1 as JsSandbox.JsCallResult.Failed).reason.contains("kinoshkaApi"))

        val badName = """function manifest() { return JSON.stringify({name: "", version: "1", kinoshkaApi: 1}); }"""
        assertTrue(JsSandbox.readManifest(badName) is JsSandbox.JsCallResult.Failed)

        val notJson = """function manifest() { return "nope"; }"""
        assertTrue(JsSandbox.readManifest(notJson) is JsSandbox.JsCallResult.Failed)
    }

    @Test
    fun `resolveMovie fixture maps ctx to voices`() {
        val code = """
            function resolveMovie(arg) {
                var ctx = JSON.parse(arg);
                return JSON.stringify({voices: [{title: "Дубляж", url: "https://cdn.example.com/" + ctx.kinopoiskId + ".m3u8"}]});
            }
        """.trimIndent()
        val out = JsSandbox.callJsonFunction(code, "resolveMovie", """{"kinopoiskId":301,"imdbId":null}""")
        assertTrue(out is JsSandbox.JsCallResult.Ok)
        val json = org.json.JSONObject((out as JsSandbox.JsCallResult.Ok).json)
        val voices = json.getJSONArray("voices")
        assertEquals(1, voices.length())
        assertEquals("Дубляж", voices.getJSONObject(0).getString("title"))
        assertEquals("https://cdn.example.com/301.m3u8", voices.getJSONObject(0).getString("url"))
    }
}
