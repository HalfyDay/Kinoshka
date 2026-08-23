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
    fun `returns null for unrecognized embed`() {
        assertNull(DdbbStreamResolver.extractFromEmbed("<html><body>hello</body></html>", "https://example.com/x"))
    }
}
