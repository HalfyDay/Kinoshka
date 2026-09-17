package hd.kinoshka.app.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioCustomSourceTest {

    private fun stremio(
        id: String = "CUSTOM_S",
        name: String = "Стримио",
        endpoint: String = "https://addons.example.com/stremio"
    ) = CustomSource(
        id = id, name = name, urlTemplate = "",
        kind = CustomSourceKind.STREMIO, endpoint = endpoint,
        categories = setOf(SourceCategory.FILMS)
    )

    private val manifestStrings = """
        {"id":"com.example.streams","version":"1.0.0","name":"Example Streams",
         "resources":["stream"],"types":["movie"],"idPrefixes":["tt"]}
    """.trimIndent()

    private val manifestObjects = """
        {"id":"com.example.series","version":"2.1.0","name":"Series Only",
         "resources":[{"name":"stream","types":["series"],"idPrefixes":["tt"]}],
         "types":["movie","series"]}
    """.trimIndent()

    private val manifestCatalogOnly = """
        {"id":"org.cinemeta","version":"1.0.0","name":"Cinemeta",
         "resources":["catalog","meta"],"types":["movie","series"],"idPrefixes":["tt"]}
    """.trimIndent()

    private val streamsMixed = """
        {"streams":[
          {"url":"https://cdn.example.com/matrix_1080p.mp4","name":"Example",
           "title":"1080p • Дубляж",
           "behaviorHints":{"notWebReady":true,"proxyHeaders":{"request":{"Referer":"https://example.com/","X-Empty":""}}}},
          {"url":"https://cdn.example.com/matrix_720p.mp4","name":"Example"},
          {"infoHash":"abcdef0123456789","name":"Torrent"},
          {"ytId":"dQw4w9WgXcQ","title":"YouTube"},
          {"externalUrl":"https://example.com/watch","title":"External"},
          {"name":"Broken"},
          {"url":"https://cdn.example.com/matrix_1080p.mp4","title":"дубль ссылки"}
        ]}
    """.trimIndent()

    // --- Манифест ---

    @Test
    fun `string resources with movie type match`() {
        val m = StremioAddonResolver.parseManifest(manifestStrings)!!
        assertEquals("com.example.streams", m.id)
        assertEquals("Example Streams", m.name)
        assertTrue(m.hasMovieStream)
    }

    @Test
    fun `object resources filter by type`() {
        val m = StremioAddonResolver.parseManifest(manifestObjects)!!
        assertFalse(m.hasMovieStream)
    }

    @Test
    fun `catalog-only manifest has no movie stream`() {
        val m = StremioAddonResolver.parseManifest(manifestCatalogOnly)!!
        assertFalse(m.hasMovieStream)
    }

    @Test
    fun `manifest without id is rejected`() {
        assertNull(StremioAddonResolver.parseManifest("""{"name":"NoId","resources":["stream"]}"""))
        assertNull(StremioAddonResolver.parseManifest("not json"))
    }

    // --- Потоки ---

    @Test
    fun `streams map with labels and proxy headers`() {
        val (streams, skipped) = StremioAddonResolver.parseStreams(streamsMixed)
        // 2 уникальных http + дубль (дедуп — на уровне парса в SourceParse, тут все 3).
        assertEquals(3, streams.size)
        assertEquals(4, skipped)
        assertEquals("Example • 1080p • Дубляж", streams[0].label)
        assertEquals(mapOf("Referer" to "https://example.com/"), streams[0].headers)
        assertEquals("Example", streams[1].label)
        assertTrue(streams[1].headers.isEmpty())
    }

    @Test
    fun `empty streams response parses`() {
        val (streams, skipped) = StremioAddonResolver.parseStreams("""{"streams":[]}""")
        assertTrue(streams.isEmpty())
        assertEquals(0, skipped)
        val (broken, _) = StremioAddonResolver.parseStreams("""{"nope":[]}""")
        assertTrue(broken.isEmpty())
    }

    // --- URL ---

    @Test
    fun `manifest url normalizes tail`() {
        assertEquals(
            "https://h.com/addon/manifest.json",
            StremioAddonResolver.manifestUrl("https://h.com/addon/")
        )
        assertEquals(
            "https://h.com/addon/stream/movie/tt0133093.json",
            StremioAddonResolver.movieStreamUrl("https://h.com/addon", "tt0133093")
        )
    }

    @Test
    fun `endpoint normalization strips manifest tail`() {
        assertEquals("https://h.com/a", normalizeStremioEndpoint("https://h.com/a/manifest.json"))
        assertEquals("https://h.com/a", normalizeStremioEndpoint("https://h.com/a/MANIFEST.JSON/"))
        assertEquals("https://h.com/a", normalizeStremioEndpoint("https://h.com/a///"))
        assertNull(normalizeStremioEndpoint("https://h.com/a/{kp}"))
        assertNull(normalizeStremioEndpoint("ftp://h.com/a"))
        assertNull(normalizeStremioEndpoint("https://localhost/a"))
        assertNull(normalizeStremioEndpoint(""))
    }

    // --- Валидация ---

    @Test
    fun `stremio validation branches`() {
        assertTrue(
            validateCustomSource("X", "", emptyList(), kind = CustomSourceKind.STREMIO, endpoint = "")
                is CustomSourceCheck.Failed
        )
        assertTrue(
            validateCustomSource("X", "", emptyList(), kind = CustomSourceKind.STREMIO, endpoint = "https://h.com/{kp}")
                is CustomSourceCheck.Failed
        )
        val dup = listOf(stremio(endpoint = "https://h.com/a/manifest.json"))
        assertTrue(
            validateCustomSource("Y", "", dup, kind = CustomSourceKind.STREMIO, endpoint = "https://h.com/a/")
                is CustomSourceCheck.Failed
        )
        val ok = validateCustomSource("Y", "", emptyList(), kind = CustomSourceKind.STREMIO, endpoint = "https://h.com/a")
        assertTrue(ok is CustomSourceCheck.Ok)
        assertTrue((ok as CustomSourceCheck.Ok).warnings.any { "IMDb" in it })
        // Дубликат имени ловится до ветки kind.
        assertTrue(
            validateCustomSource("Стримио", "", listOf(stremio()), kind = CustomSourceKind.STREMIO, endpoint = "https://h.com/b")
                is CustomSourceCheck.Failed
        )
    }

    // --- Реестр и стор ---

    @Test
    fun `customInfo pins stremio to films`() {
        val info = PlaybackSources.customInfo(stremio().copy(categories = setOf(SourceCategory.ANIME)))
        assertEquals(setOf(SourceCategory.FILMS), info.categories)
        assertTrue(info.description.contains("addons.example.com"))
        assertTrue(info.description.contains("Фильмы"))
    }

    @Test
    fun `store round-trips stremio without template`() {
        val json = customSourcesToJson(listOf(stremio()))
        val back = parseCustomSources(json)
        assertEquals(1, back.size)
        assertEquals(CustomSourceKind.STREMIO, back.single().kind)
        assertEquals("https://addons.example.com/stremio", back.single().endpoint)
        // Пустой шаблон у EMBED по-прежнему отбрасывается.
        val bad = parseCustomSources(
            """[{"id":"CUSTOM_E","name":"E","urlTemplate":"","kind":"EMBED"}]"""
        )
        assertTrue(bad.isEmpty())
    }

    // --- Сквозной резолв с инжектом сети ---

    @Test
    fun `resolveMovieParse maps streams to voice rows`() = runBlocking {
        val fetch: suspend (String) -> String? = { url ->
            when {
                url.endsWith("/manifest.json") -> manifestStrings
                "/stream/movie/" in url -> streamsMixed
                else -> null
            }
        }
        val parse = StremioAddonResolver.resolveMovieParse(
            stremio(endpoint = "https://streams.example.com/ok"), "tt0133093", isSeries = false, fetch = fetch
        )!!
        assertEquals("CUSTOM_S", parse.sourceName)
        // Дубль ссылки схлопывается (distinctBy url).
        assertEquals(2, parse.voiceRows.size)
        assertEquals("https://cdn.example.com/matrix_1080p.mp4", parse.url)
        assertEquals(
            mapOf("Referer" to "https://example.com/"),
            parse.headersByUrl["https://cdn.example.com/matrix_1080p.mp4"]
        )
    }

    @Test
    fun `resolveMovieParse skips series and missing ids`() = runBlocking {
        var called = false
        val fetch: suspend (String) -> String? = { called = true; manifestStrings }
        assertNull(StremioAddonResolver.resolveMovieParse(stremio(), "tt0133093", isSeries = true, fetch = fetch))
        assertNull(StremioAddonResolver.resolveMovieParse(stremio(), null, isSeries = false, fetch = fetch))
        assertNull(StremioAddonResolver.resolveMovieParse(stremio(), "  ", isSeries = false, fetch = fetch))
        assertTrue(!called)
    }

    @Test
    fun `resolveMovieParse null on manifest and stream misses`() = runBlocking {
        val noManifest: suspend (String) -> String? = { null }
        assertNull(StremioAddonResolver.resolveMovieParse(stremio(endpoint = "https://streams.example.com/none"), "tt0133093", isSeries = false, fetch = noManifest))
        val catalogOnly: suspend (String) -> String? = { url ->
            if (url.endsWith("/manifest.json")) manifestCatalogOnly else streamsMixed
        }
        assertNull(StremioAddonResolver.resolveMovieParse(stremio(endpoint = "https://streams.example.com/cat"), "tt0133093", isSeries = false, fetch = catalogOnly))
        val noStreams: suspend (String) -> String? = { url ->
            if (url.endsWith("/manifest.json")) manifestStrings else """{"streams":[]}"""
        }
        assertNull(StremioAddonResolver.resolveMovieParse(stremio(endpoint = "https://streams.example.com/empty"), "tt0133093", isSeries = false, fetch = noStreams))
    }
}
