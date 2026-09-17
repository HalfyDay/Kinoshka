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

    @Test
    fun `manifest exposes series stream and meta flags`() {
        val m = StremioAddonResolver.parseManifest(
            """{"id":"com.ex.both","version":"1","name":"Both",
                "resources":[{"name":"stream","types":["movie","series"],"idPrefixes":["tt"]},
                             {"name":"meta","types":["series"],"idPrefixes":["tt"]}]}"""
        )!!
        assertTrue(m.hasMovieStream)
        assertTrue(m.hasSeriesStream)
        assertTrue(m.hasSeriesMeta)
        val movieOnly = StremioAddonResolver.parseManifest(manifestStrings)!!
        assertTrue(movieOnly.hasMovieStream)
        assertFalse(movieOnly.hasSeriesStream)
        assertFalse(movieOnly.hasSeriesMeta)
    }

    // --- Meta ---

    @Test
    fun `meta videos filter specials and sort`() {
        val raw = """{"meta":{"videos":[
            {"season":1,"episode":2,"title":"Вторая"},
            {"season":0,"episode":1,"title":"Спешл"},
            {"season":1,"episode":1,"title":"Первая"},
            {"season":1,"episode":1,"title":"Дубль"},
            {"season":1,"episode":0},
            {"title":"Без номеров"}
        ]}}"""
        val videos = StremioAddonResolver.parseMetaVideos(raw)
        assertEquals(
            listOf(
                StremioAddonResolver.MetaEpisode(1, 1, "Первая"),
                StremioAddonResolver.MetaEpisode(1, 2, "Вторая")
            ),
            videos
        )
        assertTrue(StremioAddonResolver.parseMetaVideos("""{"meta":{}}""").isEmpty())
        assertTrue(StremioAddonResolver.parseMetaVideos("junk").isEmpty())
    }

    @Test
    fun `quality rank picks max rendition`() {
        assertEquals("1080p", StremioAddonResolver.qualityOf("Дубляж", "Addon • 720p • 1080p"))
        assertNull(StremioAddonResolver.qualityOf("Серия 1", "NoQuality"))
        assertNull(StremioAddonResolver.qualityOf("", ""))
    }

    // --- Сериальный резолв с инжектом сети ---

    private val manifestSeries = """
        {"id":"com.ex.series","version":"1.0.0","name":"Series Addon",
         "resources":[{"name":"stream","types":["movie","series"],"idPrefixes":["tt"]},
                      {"name":"meta","types":["series"],"idPrefixes":["tt"]}],
         "types":["movie","series"]}
    """.trimIndent()

    private val metaThree = """
        {"meta":{"videos":[
            {"season":1,"episode":1,"title":"Пилот"},
            {"season":1,"episode":2,"title":"Вторая"},
            {"season":1,"episode":3,"title":"Пустая"}
        ]}}
    """.trimIndent()

    private fun seriesFetch(): suspend (String) -> String? = { url ->
        when {
            url.endsWith("/manifest.json") -> manifestSeries
            "/meta/series/" in url -> metaThree
            url.endsWith(":1:1.json") -> """{"streams":[
                {"url":"https://cdn.example.com/s1_720.mp4","name":"A","title":"720p"},
                {"url":"https://cdn.example.com/s1_1080.mp4","name":"A","title":"1080p"}]}"""
            url.endsWith(":1:2.json") -> """{"streams":[
                {"url":"https://cdn.example.com/s2.mp4","name":"A"}]}"""
            else -> """{"streams":[]}"""
        }
    }

    @Test
    fun `resolveSeriesParse builds tracks with ladders`() = runBlocking {
        val parse = StremioAddonResolver.resolveSeriesParse(
            stremio(endpoint = "https://series.example.com/x"), "tt0903747", fetch = seriesFetch()
        )!!
        assertEquals("CUSTOM_S", parse.sourceName)
        // Третья серия без потоков пропущена.
        assertEquals(2, parse.tracks.size)
        val ep1 = parse.tracks.first { it.episodeNumber == 1 }
        assertEquals(1, ep1.seasonNumber)
        assertEquals("custom|CUSTOM_S|stremio", ep1.dubId)
        assertEquals("Series Addon", ep1.dubTitle)
        assertEquals("Пилот", ep1.title)
        // Лучший ранг играет, лестница полная.
        assertEquals("https://cdn.example.com/s1_1080.mp4", ep1.playerUrl)
        assertEquals(
            mapOf("720p" to "https://cdn.example.com/s1_720.mp4", "1080p" to "https://cdn.example.com/s1_1080.mp4"),
            parse.ladders[ep1.playerUrl]
        )
        assertEquals("https://cdn.example.com/s2.mp4", parse.tracks.first { it.episodeNumber == 2 }.playerUrl)
        assertEquals(parse.tracks.first().playerUrl, parse.url)
    }

    @Test
    fun `resolveSeriesParse null without series resources or ids`() = runBlocking {
        val fetch: suspend (String) -> String? = { manifestStrings }
        // Манифест только под фильмы.
        assertNull(
            StremioAddonResolver.resolveSeriesParse(
                stremio(endpoint = "https://series.example.com/movie-only"), "tt0903747", fetch = fetch
            )
        )
        var called = false
        val spy: suspend (String) -> String? = { called = true; manifestSeries }
        assertNull(
            StremioAddonResolver.resolveSeriesParse(
                stremio(endpoint = "https://series.example.com/noid"), null, fetch = spy
            )
        )
        assertNull(
            StremioAddonResolver.resolveSeriesParse(
                stremio(endpoint = "https://series.example.com/blank"), "  ", fetch = spy
            )
        )
        assertTrue(!called)
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

    @Test
    fun `imdb id sanitized`() {
        assertEquals("tt0133093", StremioAddonResolver.cleanImdbId("tt0133093"))
        assertEquals("tt0133093", StremioAddonResolver.cleanImdbId("  tt0133093\n"))
        assertEquals("tt0133093", StremioAddonResolver.cleanImdbId("imdb:tt0133093 (1999)"))
        assertNull(StremioAddonResolver.cleanImdbId(null))
        assertNull(StremioAddonResolver.cleanImdbId("  "))
        assertNull(StremioAddonResolver.cleanImdbId("kp301"))
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
    fun `resolveMovieParse skips missing ids without network`() = runBlocking {
        var called = false
        val fetch: suspend (String) -> String? = { called = true; manifestStrings }
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
