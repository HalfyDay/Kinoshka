package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Parser tests for the webmaster fallback sources (VideoCDN / Collaps / Voidboost).
 * Formats are mirrored from the Lampa web app plugins (yumata/lampa-source).
 */
class WebmasterStreamSourcesTest {

    // --- VideoCDN ---------------------------------------------------------------------------

    @Test
    fun `videocdn quality list keeps first alternative and pins https`() {
        val ladder = WebmasterStreamSources.parseVideocdnQualityList(
            "[1080p]//h/x/a.mp4 or //mirror/x/a.mp4,[720p]//h/x/b.mp4,[480p]//h/x/c.mp4"
        )
        assertEquals(3, ladder.size)
        assertEquals("https://h/x/a.mp4", ladder["1080p"])
        assertEquals("https://h/x/b.mp4", ladder["720p"])
        assertEquals("https://h/x/c.mp4", ladder["480p"])
    }

    @Test
    fun `videocdn files movie payload yields one ladder per translation`() {
        // The embed's id="files" value: HTML-escaped JSON whose values are escaped-JSON strings.
        val filesValue =
            """{&quot;0&quot;:&quot;&quot;,&quot;615&quot;:&quot;[{\&quot;file\&quot;:\&quot;[1080p]//h/x/a.mp4,[720p]//h/x/b.mp4\&quot;}]&quot;,&quot;616&quot;:&quot;[{\&quot;file\&quot;:\&quot;[720p]//h/x/c.mp4\&quot;}]&quot;}"""
        val files = WebmasterStreamSources.parseVideocdnFiles(filesValue)

        val by615 = files.perTranslation["615"]
        assertNotNull(by615)
        assertEquals(
            linkedMapOf("1080p" to "https://h/x/a.mp4", "720p" to "https://h/x/b.mp4"),
            by615!![null]
        )
        assertEquals(
            linkedMapOf("720p" to "https://h/x/c.mp4"),
            files.perTranslation["616"]!![null]
        )
    }

    @Test
    fun `videocdn files series payload keys ladders by s_e folders`() {
        val filesValue =
            """{&quot;615&quot;:&quot;[{\&quot;folder\&quot;:[{\&quot;id\&quot;:\&quot;1_1\&quot;,\&quot;file\&quot;:\&quot;[1080p]//h/s1e1.mp4\&quot;},{\&quot;id\&quot;:\&quot;1_2\&quot;,\&quot;file\&quot;:\&quot;[720p]//h/s1e2.mp4\&quot;}]}]&quot;}"""
        val files = WebmasterStreamSources.parseVideocdnFiles(filesValue)

        val by615 = files.perTranslation["615"]!!
        assertNull(by615[null])
        assertEquals(mapOf("1080p" to "https://h/s1e1.mp4"), by615["1_1"])
        assertEquals(mapOf("720p" to "https://h/s1e2.mp4"), by615["1_2"])
    }

    @Test
    fun `videocdn files skips the service 0 key and empty translations`() {
        val filesValue = "{&quot;0&quot;:&quot;not-a-ladder&quot;,&quot;615&quot;:&quot;[]&quot;}"
        assertTrue(WebmasterStreamSources.parseVideocdnFiles(filesValue).perTranslation.isEmpty())
    }

    @Test
    fun `videocdn rows accept array and object data shapes`() {
        // svetacdn returns data as an array; the cdnmovies short API returns an id-keyed
        // object (Lampa converts it with `for key in json.data`).
        val arrayRoot = org.json.JSONObject("""{"data":[{"id":1,"iframe_src":"//a/e"},{"id":2}]}""")
        val objectRoot = org.json.JSONObject(
            """{"data":{"1":{"id":1,"iframe_src":"//a/e"},"2":{"id":2}},"result":false}"""
        )
        assertEquals(2, WebmasterStreamSources.videocdnRows(arrayRoot).size)
        assertEquals("//a/e", WebmasterStreamSources.videocdnRows(objectRoot).first().optString("iframe_src"))
        assertTrue(WebmasterStreamSources.videocdnRows(org.json.JSONObject("""{"status":true}""")).isEmpty())
    }

    // --- Collaps ----------------------------------------------------------------------------

    @Test
    fun `collaps makePlayer series config parses unquoted keys`() {
        val html = "<html><script>\nmakePlayer({title:\"Шоу\",playlist:{seasons:[{season:1,episodes:[" +
            "{episode:1,hls:\"https://c/e1.m3u8\",title:\"Начало\",audio:{names:[\"Дубляж\"]},cc:[]}," +
            "{episode:2,hls:\"https://c/e2.m3u8\",title:\"Второй\",audio:{names:[\"Дубляж\"]}}]}]}});\n</script>"
        val parse = WebmasterStreamSources.parseCollapsMakePlayer(html)

        assertNotNull(parse)
        assertEquals(1, parse!!.seasons.size)
        assertEquals(1, parse.seasons[0].number)
        assertEquals(2, parse.seasons[0].episodes.size)
        assertEquals(1, parse.seasons[0].episodes[0].number)
        assertEquals("https://c/e1.m3u8", parse.seasons[0].episodes[0].hls)
        assertEquals("Начало", parse.seasons[0].episodes[0].title)
        assertEquals(listOf("Дубляж"), parse.seasons[0].episodes[0].audioNames)
        assertNull(parse.movieHls)
    }

    @Test
    fun `collaps makePlayer movie config yields source hls`() {
        val html = "makePlayer({title:\"Фильм\",source:{hls:\"https://c/master.m3u8\",audio:{names:[\"Многоголосый\"]}}});"
        val parse = WebmasterStreamSources.parseCollapsMakePlayer(html)

        assertNotNull(parse)
        assertEquals("https://c/master.m3u8", parse!!.movieHls)
        assertEquals(listOf("Многоголосый"), parse.movieAudio)
        assertTrue(parse.seasons.isEmpty())
    }

    @Test
    fun `collaps parse returns null for foreign embeds`() {
        assertNull(WebmasterStreamSources.parseCollapsMakePlayer("<html>no player here</html>"))
    }

    @Test
    fun `collaps 2026 config with embedded js functions parses seasons and episode audio`() {
        // Mirrors the live api.delivembd.ws embed (kp=460586): the makePlayer argument carries
        // raw JS (tracker function, arithmetic) next to the data, so only the seasons subtree parses.
        val html = "<script>function makePlayer(opts) { var s; if (!window.VenomPlayer) { throw 'PLAYER_LOAD_FAIL_' } }</script>" +
            "<script>makePlayer({" +
            "poster: 'data:image/gif;base64,R0lGODlhAQABAICTAEAOw=='," +
            "ui: { prevNext:  true  }," +
            "playlist: { open: true , autoNext: true , id: 5780 , current: { season:  5 , episode: \"1\" }," +
            "seasons:[{\"season\":1,\"blocked\":false,\"episodes\":[{\"episode\":\"1\",\"hls\":\"https://c/e1.m3u8\"," +
            "\"title\":\"Пацаны (1 сезон) - 1 серия\",\"audio\":{\"order\":[0,1],\"names\":[\"LostFilm\",\"Кубик в кубе\"]},\"cc\":[],\"duration\":3609}]}]," +
            "tracker: function() { var t = false; if (lastTracker + 3e5 < now) t = !t; return (t) ?\"wss://t5.zcvh.net/v1/ws\" :\"wss://t6.zcvh.net/v1/ws\" }," +
            "longDownload: 30 * 1000 }" +
            "});</script>"
        val parse = WebmasterStreamSources.parseCollapsMakePlayer(html)

        assertNotNull(parse)
        assertEquals(1, parse!!.seasons.size)
        assertEquals(1, parse.seasons[0].number)
        assertEquals(1, parse.seasons[0].episodes[0].number)
        assertEquals("https://c/e1.m3u8", parse.seasons[0].episodes[0].hls)
        assertEquals("Пацаны (1 сезон) - 1 серия", parse.seasons[0].episodes[0].title)
        assertEquals(listOf("LostFilm", "Кубик в кубе"), parse.seasons[0].episodes[0].audioNames)
        assertNull(parse.movieHls)
    }

    @Test
    fun `redirect target resolves relative locations and upgrades cleartext`() {
        assertEquals(
            "https://mirror.example.net/embed/460586?s=1",
            WebmasterStreamSources.redirectTargetUrl(
                "https://voidboost.net/embed/460586?s=1", "http://mirror.example.net/embed/460586?s=1"
            )
        )
        assertEquals(
            "https://voidboost.net/embed/460586?s=1",
            WebmasterStreamSources.redirectTargetUrl("https://voidboost.net/embed/460586", "/embed/460586?s=1")
        )
        assertEquals(
            "https://cdn.example.org/embed/x",
            WebmasterStreamSources.redirectTargetUrl("https://a.example.org/e", "https://cdn.example.org/embed/x")
        )
        assertNull(WebmasterStreamSources.redirectTargetUrl("https://a.example.org/e", "http://[::bad::/"))
    }

    // --- Voidboost --------------------------------------------------------------------------

    @Test
    fun `voidboost decode strips separators junk and prefix phase`() {
        val decoded = "[1080p]//s1.voidboost.tv/p/1/1/1080.mp4,[720p]//s1.voidboost.tv/p/1/1/720.mp4"
        val payload = "xx" + Base64.getEncoder().encodeToString(decoded.toByteArray(Charsets.UTF_8))
        val junk = Base64.getEncoder().encodeToString("@#".toByteArray(Charsets.UTF_8))
        val raw = "#h" + payload.substring(0, 12) + "//_//" + junk + "//_//" + payload.substring(12)

        assertEquals(decoded, WebmasterStreamSources.decodeVoidboostFile(raw))
    }

    @Test
    fun `voidboost decode returns empty on garbage`() {
        assertEquals("", WebmasterStreamSources.decodeVoidboostFile("not base64 at all"))
    }

    @Test
    fun `voidboost quality chunks keep last alternative and fold ultra labels`() {
        val ladder = WebmasterStreamSources.parseVoidboostQualityChunks(
            "[1080p]//s1/x/1080.mp4 or //s2/x/1080.mp4,[720p]//s1/x/720.mp4,[1080p Ultra]//s1/x/1080u.mp4"
        )
        // Lampa's rezka massage takes the LAST or-alternative; "1080p Ultra" folds onto "1080p".
        assertEquals("https://s2/x/1080.mp4", ladder["1080p"])
        assertEquals("https://s1/x/720.mp4", ladder["720p"])
        assertEquals(2, ladder.size)
    }

    @Test
    fun `voidboost embed parse extracts voices seasons and episodes`() {
        val html = "<select name=\"translator\" onchange=\"x()\">" +
            "<option value=\"0\" data-token=\"abc123\">Дубляж</option>" +
            "<option value=\"1\" data-token=\"def456\">Многоголосый</option>" +
            "</select>" +
            "<select name=\"season\"><option value=\"1\">1 сезон</option><option value=\"2\">2 сезон</option></select>" +
            "<select name=\"episode\"><option value=\"1\">Серия 1</option><option value=\"2\">Серия 2</option></select>"
        val embed = WebmasterStreamSources.parseVoidboostEmbed(html)

        assertEquals(2, embed.voices.size)
        assertEquals("abc123", embed.voices[0].token)
        assertEquals("Дубляж", embed.voices[0].name)
        assertEquals("def456", embed.voices[1].token)
        assertEquals(listOf(1, 2), embed.seasons)
        assertEquals(2, embed.episodes.size)
        assertEquals(1, embed.episodes[0].number)
        assertEquals("Серия 1", embed.episodes[0].title)
    }

    @Test
    fun `voidboost embed parse ignores options without tokens`() {
        val html = "<select name=\"translator\"><option value=\"0\">Выберите перевод</option>" +
            "<option value=\"1\" data-token=\"tok\">Дубляж</option></select>"
        val embed = WebmasterStreamSources.parseVoidboostEmbed(html)

        assertEquals(1, embed.voices.size)
        assertEquals("tok", embed.voices[0].token)
        assertTrue(embed.seasons.isEmpty())
    }
}
