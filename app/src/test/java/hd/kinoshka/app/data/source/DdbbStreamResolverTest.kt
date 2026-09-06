package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DdbbStreamResolverTest {

    /** Builds an obfuscated turbo blob the way the embed does: random prefix + junk comments inside. */
    private fun obfuscate(json: String): String {
        val prefix = "TU7OIX3m1TZWcJbYJa2VBWKETvEJCLzkwvf6qPVMqnvvwzRcUhGajjnQPbCYWIBNlKeKWX1w"
        val payload = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val withJunk = StringBuilder()
        // Insert a junk comment after the first 40 chars, mimicking "//dS91L3U=F" style salt.
        withJunk.append(payload.substring(0, 40))
        withJunk.append("//dS91L3U=F")
        withJunk.append(payload.substring(40))
        return prefix + withJunk
    }

    private fun sampleConfig() = """
        {"lang":"en","id":"player","file":[
            {"title":"(RU) DUB","t1":"","poster":"","duration":11706,
             "file":"[240p]https://cdn.example/stream/a240.mp4,[720p]https://cdn.example/stream/a720.mp4,[Auto]https://cdn.example/stream/a/index.m3u8",
             "subtitle":""},
            {"title":"(RU) MVO","t1":"","poster":"","duration":11712,
             "file":"[720p]https://cdn.example/stream/b720.mp4,[Auto]https://cdn.example/stream/b/index.m3u8",
             "subtitle":""}
        ]}
    """.trimIndent()

    @Test
    fun `decodes obfuscated turbo config`() {
        val blob = obfuscate(sampleConfig())
        val decoded = DdbbStreamResolver.decodeTurboConfig(blob)
        assertNotNull("config should decode", decoded)
        assertTrue(decoded!!.contains("\"file\""))
        assertTrue(decoded.contains("[Auto]"))
    }

    @Test
    fun `decodes plain base64 config without junk`() {
        val blob = Base64.getEncoder().encodeToString(sampleConfig().toByteArray(Charsets.UTF_8))
        assertNotNull(DdbbStreamResolver.decodeTurboConfig(blob))
    }

    @Test
    fun `rejects garbage blob`() {
        assertNull(DdbbStreamResolver.decodeTurboConfig("not-a-valid-blob-at-all"))
    }

    @Test
    fun `extracts collaps hls from embed html`() {
        val html = """
            <html><body><script>
            var opts = { src: { hls: "https://cdn.interkh.com/video/master.m3u8?sig=abc&exp=1" } };
            </script></body></html>
        """.trimIndent()
        val (headers, qualities) = DdbbStreamResolver.extractFromEmbed(html, "https://api.ortified.ws/embed/movie/165")!!
        assertTrue(headers.isEmpty())
        assertEquals("https://cdn.interkh.com/video/master.m3u8?sig=abc&exp=1", qualities["Auto"])
    }

    @Test
    fun `extracts turbo qualities with referer header`() {
        val config = sampleConfig()
        val blob = obfuscate(config)
        val html = """<html><body><script>new Player("$blob");</script></body></html>"""
        val (headers, qualities) = DdbbStreamResolver.extractFromEmbed(html, "https://64650e1b.obrut.show/embed/UTN/content/gzNyETN")!!

        assertEquals("https://64650e1b.obrut.show/", headers["Referer"])
        assertEquals("https://cdn.example/stream/a720.mp4", qualities["720p"])
        assertEquals("https://cdn.example/stream/a/index.m3u8", qualities["Auto"])
        assertEquals("https://cdn.example/stream/a240.mp4", qualities["240p"])
    }

    @Test
    fun `extracts turbo qualities when file is a plain string`() {
        val config = """
            {"lang":"en","id":"player",
             "file":"[720p]https://cdn.example/single/720.mp4,[Auto]https://cdn.example/single/index.m3u8"}
        """.trimIndent()
        val blob = obfuscate(config)
        val html = """<html><body><script>new Player("$blob");</script></body></html>"""
        val (headers, qualities) = DdbbStreamResolver.extractFromEmbed(html, "https://abc.obrut.show/embed/x")!!

        assertEquals("https://abc.obrut.show/", headers["Referer"])
        assertEquals("https://cdn.example/single/720.mp4", qualities["720p"])
        assertEquals("https://cdn.example/single/index.m3u8", qualities["Auto"])
    }

    @Test
    fun `returns null for unrecognized embed`() {
        assertNull(DdbbStreamResolver.extractFromEmbed("<html><body>hello</body></html>", "https://example.com/x"))
    }

    // --- Cross-source merge (all sources resolve concurrently, dropdown lists every dub) ---

    private fun parse(
        name: String,
        url: String,
        headers: Map<String, String>,
        voiceRows: List<Pair<String, String>> = emptyList(),
        tracks: List<hd.kinoshka.app.data.model.DdbbEpisodeTrack> = emptyList(),
        ladders: Map<String, Map<String, String>> = emptyMap(),
    ) = DdbbStreamResolver.SourceParse(
        sourceName = name, url = url, headers = headers,
        qualities = ladders[url] ?: mapOf("Auto" to url),
        voiceRows = voiceRows, tracks = tracks, ladders = ladders
    )

    @Test
    fun `merge keeps winner stream and unions dub rows winner-first`() {
        val turbo = parse(
            "Turbo", "https://t/a1080.mp4", mapOf("Referer" to "https://t/"),
            voiceRows = listOf("Дубляж" to "https://t/a1080.mp4", "МVO" to "https://t/b.mp4")
        )
        val videocdn = parse(
            "VideoCDN", "https://v/x1080.mp4", mapOf("User-Agent" to "UA"),
            voiceRows = listOf("Дубляж" to "https://v/x1080.mp4", "Original" to "https://v/o.mp4")
        )
        val merged = DdbbStreamResolver.mergeSourceParses(listOf(turbo, videocdn))

        assertEquals(listOf("Дубляж" to "https://t/a1080.mp4", "МVO" to "https://t/b.mp4", "Original" to "https://v/o.mp4"), merged.voiceRows)
        assertEquals("https://t/a1080.mp4", merged.url)
        assertEquals(mapOf("Referer" to "https://t/"), merged.headers)
    }

    @Test
    fun `merge unions episode tracks and ladders across sources`() {
        val turbo = parse(
            "Turbo", "https://t/s1e1.mp4", mapOf("Referer" to "https://t/"),
            tracks = listOf(
                hd.kinoshka.app.data.model.DdbbEpisodeTrack("turbo|dub", "Дубляж", 1, 1, null, "https://t/s1e1.mp4"),
                hd.kinoshka.app.data.model.DdbbEpisodeTrack("turbo|dub", "Дубляж", 1, 2, null, "https://t/s1e2.mp4")
            ),
            ladders = mapOf("https://t/s1e1.mp4" to mapOf("1080p" to "https://t/s1e1.mp4"))
        )
        val videocdn = parse(
            "VideoCDN", "https://v/s1e1.mp4", mapOf("User-Agent" to "UA"),
            tracks = listOf(
                hd.kinoshka.app.data.model.DdbbEpisodeTrack("videocdn|615", "Netflix", 1, 1, null, "https://v/s1e1.mp4"),
                // Same dub+episode as turbo's first row → first (winner) parse wins.
                hd.kinoshka.app.data.model.DdbbEpisodeTrack("turbo|dub", "Дубляж", 1, 1, null, "https://other/s1e1.mp4")
            ),
            ladders = mapOf("https://v/s1e1.mp4" to mapOf("1080p" to "https://v/s1e1.mp4"))
        )
        val merged = DdbbStreamResolver.mergeSourceParses(listOf(turbo, videocdn))

        assertEquals(3, merged.tracks.size)
        assertEquals("https://t/s1e1.mp4", merged.tracks.first { it.dubId == "turbo|dub" && it.episodeNumber == 1 }.playerUrl)
        assertEquals(2, merged.ladders.size)
        assertEquals(mapOf("1080p" to "https://t/s1e1.mp4"), merged.ladders["https://t/s1e1.mp4"])
    }

    @Test
    fun `merge indexes per-source headers by url`() {
        val turbo = parse("Turbo", "https://t/a.mp4", mapOf("Referer" to "https://t/"), ladders = mapOf("https://t/a.mp4" to mapOf("1080p" to "https://t/a.mp4")))
        val videocdn = parse("VideoCDN", "https://v/x.mp4", mapOf("User-Agent" to "UA"), ladders = mapOf("https://v/x.mp4" to mapOf("1080p" to "https://v/x.mp4")))
        val merged = DdbbStreamResolver.mergeSourceParses(listOf(turbo, videocdn))

        assertEquals(mapOf("Referer" to "https://t/"), merged.headersByUrl["https://t/a.mp4"])
        assertEquals(mapOf("User-Agent" to "UA"), merged.headersByUrl["https://v/x.mp4"])
    }

    @Test
    fun `sourceRank orders turbo then webmaster trio then the rest`() {
        val names = listOf("Veoveo", "Voidboost", "Collaps", "Alloha", "VideoCDN", "Turbo")
        assertEquals(listOf("Turbo", "VideoCDN", "Collaps", "Voidboost", "Veoveo", "Alloha"), names.sortedBy { DdbbStreamResolver.sourceRank(it) })
    }
}
