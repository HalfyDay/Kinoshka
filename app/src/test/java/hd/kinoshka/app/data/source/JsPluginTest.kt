package hd.kinoshka.app.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsPluginTest {

    private fun plugin(
        id: String = "CUSTOM_P",
        name: String = "Плагин",
        endpoint: String = "https://plugins.example.com/stub.js"
    ) = CustomSource(
        id = id, name = name, urlTemplate = "",
        kind = CustomSourceKind.PLUGIN, endpoint = endpoint,
        categories = setOf(SourceCategory.FILMS)
    )

    private val stubCode = """
        function manifest() {
            return JSON.stringify({name: "Stub", version: "2.0.0",
                author: "@t", description: "d", kinoshkaApi: 1});
        }
        function resolveMovie(arg) {
            var ctx = JSON.parse(arg);
            return JSON.stringify({voices: [
                {title: "Дубляж", url: "https://cdn.example.com/" + ctx.kinopoiskId + ".m3u8",
                 headers: {"Referer": "https://example.com/"}}
            ]});
        }
    """.trimIndent()

    private val seriesCode = """
        function manifest() {
            return JSON.stringify({name: "Stub", version: "1.0.0", kinoshkaApi: 1});
        }
        function resolveMovie(arg) {
            return JSON.stringify({tracks: [
                {season: 1, episode: 1, title: "Первая", url: "https://cdn.example.com/s1.mp4",
                 dub: "a", dubTitle: "Дубляж A",
                 qualities: {"720p": "https://cdn.example.com/s1_720.mp4",
                             "1080p": "https://cdn.example.com/s1_1080.mp4"}},
                {season: 1, episode: 2, url: "https://cdn.example.com/s2.mp4"}
            ]});
        }
    """.trimIndent()

    // --- Маппинг JSON в SourceParse ---

    @Test
    fun `voices map to rows with headers`() {
        val parse = JsPluginResolver.pluginJsonToParse(
            plugin(),
            """{"voices":[{"title":"Дубляж","url":"https://cdn.example.com/m.m3u8","headers":{"Referer":"https://example.com/"}}]}"""
        )!!
        assertEquals("CUSTOM_P", parse.sourceName)
        assertEquals(1, parse.voiceRows.size)
        assertEquals("Дубляж", parse.voiceRows[0].first)
        assertEquals("https://cdn.example.com/m.m3u8", parse.url)
        assertEquals(
            mapOf("Referer" to "https://example.com/"),
            parse.headersByUrl["https://cdn.example.com/m.m3u8"]
        )
    }

    @Test
    fun `tracks map to dubs with ladders and best quality`() {
        val parse = JsPluginResolver.pluginJsonToParse(
            plugin(),
            """{"tracks":[
                {"season":1,"episode":1,"title":"Первая","url":"https://cdn.example.com/s1.mp4",
                 "dub":"a","dubTitle":"Дубляж A",
                 "qualities":{"720p":"https://cdn.example.com/s1_720.mp4","1080p":"https://cdn.example.com/s1_1080.mp4"}},
                {"season":1,"episode":2,"url":"https://cdn.example.com/s2.mp4"}
            ]}"""
        )!!
        assertEquals(2, parse.tracks.size)
        val ep1 = parse.tracks.first { it.episodeNumber == 1 }
        assertEquals("custom|CUSTOM_P|a", ep1.dubId)
        assertEquals("Дубляж A", ep1.dubTitle)
        // Лучший ранг играет.
        assertEquals("https://cdn.example.com/s1_1080.mp4", ep1.playerUrl)
        assertEquals(
            mapOf("720p" to "https://cdn.example.com/s1_720.mp4", "1080p" to "https://cdn.example.com/s1_1080.mp4"),
            parse.ladders[ep1.playerUrl]
        )
        val ep2 = parse.tracks.first { it.episodeNumber == 2 }
        assertEquals("custom|CUSTOM_P|plugin", ep2.dubId)
        assertEquals(mapOf("Auto" to "https://cdn.example.com/s2.mp4"), parse.ladders[ep2.playerUrl])
    }

    @Test
    fun `garbage and empty resolve to null`() {
        assertNull(JsPluginResolver.pluginJsonToParse(plugin(), "junk"))
        assertNull(JsPluginResolver.pluginJsonToParse(plugin(), """{"voices":[]}"""))
        assertNull(
            JsPluginResolver.pluginJsonToParse(
                plugin(), """{"voices":[{"title":"X","url":"ftp://bad/file"}]}"""
            )
        )
    }

    // --- Сквозной резолв с инжектом кода ---

    @Test
    fun `resolveOne maps stub voices`() = runBlocking {
        val parse = JsPluginResolver.resolveOne(plugin(), 301, "tt0133093", stubCode)!!
        assertEquals(1, parse.voiceRows.size)
        assertEquals("https://cdn.example.com/301.m3u8", parse.url)
    }

    @Test
    fun `resolveOne null for non-plugin and missing resolveMovie`() = runBlocking {
        val embed = CustomSource(id = "CUSTOM_E", name = "E", urlTemplate = "https://e.example.com/{kp}")
        assertNull(JsPluginResolver.resolveOne(embed, 301, null, stubCode))
        val noFunc = "function manifest() { return '{}'; }"
        assertNull(JsPluginResolver.resolveOne(plugin(), 301, null, noFunc))
    }

    @Test
    fun `anime fetch routes plugin by kind`() = runBlocking {
        val rows = AnimeStreamResolver.fetchCustomAnimeTranslations(
            custom = plugin().copy(categories = setOf(SourceCategory.ANIME)),
            shikimoriId = 0,
            animeTitle = "Наруто",
            kinopoiskId = 301,
            imdbId = "tt0409591",
            resolve = { _, _ -> throw AssertionError("embed resolve must not run") }
        )
        // Без кода в сторе — пусто, но embed-ветка не вызвана (иначе бы упали).
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `fetchPluginAnimeTranslations maps injected series`() = runBlocking {
        val rows = AnimeStreamResolver.fetchPluginAnimeTranslations(
            custom = plugin().copy(categories = setOf(SourceCategory.ANIME)),
            shikimoriId = 0,
            animeTitle = "Наруто",
            kinopoiskId = 301,
            imdbId = "tt0409591",
            code = seriesCode
        )
        assertEquals(2, rows.size)
        val a = rows.first { it.translationId == "custom|CUSTOM_P|a" }
        assertEquals(listOf(1), a.episodes.map { it.number })
        assertEquals("https://cdn.example.com/s1_1080.mp4", a.episodes[0].link)
    }

    @Test
    fun `hentai fetch maps injected plugin movie`() = runBlocking {
        val stream = HentaiStreamResolver.fetchCustomHentai(
            custom = plugin().copy(categories = setOf(SourceCategory.ADULT)),
            kinopoiskId = 301,
            imdbId = "tt1234567",
            resolve = { _, _ -> throw AssertionError("embed resolve must not run") },
            pluginCode = stubCode
        )!!
        assertEquals("https://cdn.example.com/301.m3u8", stream.url)
    }

    // --- Валидация, реестр, merge ---

    @Test
    fun `plugin endpoint validation branches`() {
        assertTrue(validateCustomSource("X", "", emptyList(), kind = CustomSourceKind.PLUGIN, endpoint = "") is CustomSourceCheck.Failed)
        assertTrue(
            validateCustomSource("X", "", emptyList(), kind = CustomSourceKind.PLUGIN, endpoint = "https://h.com/{kp}")
                is CustomSourceCheck.Failed
        )
        assertTrue(
            validateCustomSource("X", "", emptyList(), kind = CustomSourceKind.PLUGIN, endpoint = "ftp://h.com/p.js")
                is CustomSourceCheck.Failed
        )
        val dup = listOf(plugin(endpoint = "https://h.com/p.js"))
        assertTrue(
            validateCustomSource("Y", "", dup, kind = CustomSourceKind.PLUGIN, endpoint = "https://h.com/p.js?x=1")
                is CustomSourceCheck.Failed
        )
        val ok = validateCustomSource("Y", "", emptyList(), kind = CustomSourceKind.PLUGIN, endpoint = "https://h.com/p.js")
        assertTrue(ok is CustomSourceCheck.Ok)
        assertTrue((ok as CustomSourceCheck.Ok).warnings.any { "песочнице" in it })
    }

    @Test
    fun `customInfo labels plugin with code host`() {
        val info = PlaybackSources.customInfo(plugin())
        assertEquals(setOf(SourceCategory.FILMS), info.categories)
        assertTrue(info.description.contains("JS-плагин"))
        assertTrue(info.description.contains("plugins.example.com"))
    }

    @Test
    fun `merge dedups plugin by code url`() {
        val (merged, report) = mergeCustomSources(
            listOf(plugin()),
            listOf(plugin(id = "CUSTOM_P2", name = "Дубль", endpoint = "https://plugins.example.com/stub.js"))
        )
        assertEquals(0, report.added)
        assertEquals(1, report.skipped.size)
        assertEquals(1, merged.size)
    }

    @Test
    fun `proxyHost prefers code host for plugins`() {
        assertEquals("plugins.example.com", plugin().proxyHost())
        assertEquals(
            "example.com",
            CustomSource(id = "CUSTOM_E", name = "E", urlTemplate = "https://example.com/{kp}").proxyHost()
        )
    }

    // --- Файловый стор кода ---

    @Test
    fun `plugin store round-trips code files`() {
        val dir = java.nio.file.Files.createTempDirectory("jsplug").toFile()
        JsPluginStore.init(dir)
        try {
            assertNull(JsPluginStore.loadCode("CUSTOM_P"))
            assertTrue(JsPluginStore.saveCode("CUSTOM_P", stubCode))
            assertEquals(stubCode, JsPluginStore.loadCode("CUSTOM_P"))
            JsPluginStore.deleteCode("CUSTOM_P")
            assertNull(JsPluginStore.loadCode("CUSTOM_P"))
            // Чужой id не пишет за пределы каталога.
            assertTrue(!JsPluginStore.saveCode("../evil", "x"))
        } finally {
            JsPluginStore.evictMemoryCache()
            JsPluginStore.init(null)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `installDraft parses manifest without saving`() = runBlocking {
        val fetch: suspend (String) -> String? = { stubCode }
        val draft = JsPluginStore.installDraft("https://plugins.example.com/stub.js", fetch)!!
        assertEquals("Stub", draft.manifest.name)
        assertEquals("2.0.0", draft.manifest.version)
        assertTrue(draft.code.contains("resolveMovie"))
    }

    @Test
    fun `probeWithCode counts matrix streams`() = runBlocking {
        val (ok, message) = JsPluginResolver.probeWithCode(plugin(), stubCode)
        assertTrue(ok)
        assertTrue("Stub" in message)
        assertTrue("2.0.0" in message)
        assertTrue("потоков: 1" in message)
    }

    @Test
    fun `probeWithCode fails without resolveMovie`() = runBlocking {
        val code = """function manifest() { return JSON.stringify({name: "S", version: "1", kinoshkaApi: 1}); }"""
        val (ok, message) = JsPluginResolver.probeWithCode(plugin(), code)
        assertTrue(!ok)
        assertTrue("S" in message)
    }
}
