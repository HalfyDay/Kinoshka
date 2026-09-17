package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Parser tests for the HDRezka source. Fixtures mirror the live rezka.ag markup
 * (search cards, translators list, initCDN*Events args, season/episode tabs).
 */
class HdrezkaApiTest {

    @Test
    fun `search hits parse titles years and kinds`() {
        val html = """
            <div class="b-content__inline_item" data-id="646" data-url="https://rezka.ag/series/thriller/646-vo-vse-tyazhkie-2008-latest.html">
            <div class="b-content__inline_item-cover"><a href="https://rezka.ag/series/thriller/646-vo-vse-tyazhkie-2008-latest.html"><img alt="Во все тяжкие" /></a></div>
            <div class="b-content__inline_item-link"><a href="https://rezka.ag/series/thriller/646-vo-vse-tyazhkie-2008-latest.html">Во все тяжкие</a><div>2008, США, Триллер</div></div></div>
            <div class="b-content__inline_item" data-id="32282" data-url="https://rezka.ag/films/drama/32282-el-camino-vo-vse-tyazhkie-2019-latest.html">
            <div class="b-content__inline_item-link"><a href="https://rezka.ag/films/drama/32282-el-camino-vo-vse-tyazhkie-2019-latest.html">El Camino: Во все тяжкие</a><div>2019, США, Драма</div></div></div>
            <ul><li><a href="https://rezka.ag/films/western/">Вестерны</a></li></ul>
        """.trimIndent()
        val hits = HdrezkaApi.parseSearchHits("https://rezka.ag", html)

        assertEquals(2, hits.size)
        assertEquals("Во все тяжкие", hits[0].title)
        assertEquals("https://rezka.ag/series/thriller/646-vo-vse-tyazhkie-2008-latest.html", hits[0].url)
        assertEquals(2008, hits[0].year)
        assertEquals(hd.kinoshka.app.data.model.MovieContentKind.SERIES, hits[0].kind)
        assertEquals("El Camino: Во все тяжкие", hits[1].title)
        assertEquals(2019, hits[1].year)
        assertEquals(hd.kinoshka.app.data.model.MovieContentKind.MOVIE, hits[1].kind)
    }

    @Test
    fun `translators parse ids and names`() {
        val html = """<ul id="translators-list" class="b-translators__list">""" +
            """<li><a title="Дубляж" class="b-translator__items" data-translator_id="56" href="x">Дубляж</a></li>""" +
            """<li><a title="лостфильм (LostFilm)" data-translator_id="1" href="y">лостфильм (LostFilm)</a></li></ul>"""
        val translators = HdrezkaApi.parseTranslators(html)

        assertEquals(2, translators.size)
        assertEquals("56", translators[0].id)
        assertEquals("Дубляж", translators[0].name)
        assertEquals("1", translators[1].id)
    }

    @Test
    fun `post id and default translator parse`() {
        val html = """<div data-post_id="646">""" +
            """<script>sof.tv.initCDNSeriesEvents(646, 565, 1, 1, false, 'rezka.ag', false, true, {});</script>"""
        assertEquals("646", HdrezkaApi.parsePostId(html))
        assertEquals("565", HdrezkaApi.parseDefaultTranslator(html))
    }

    @Test
    fun `seasons and episodes parse`() {
        val html = """<ul class="b-simple_season__list" id="simple-seasons-tabs">""" +
            """<a class="b-simple_season__item active" data-tab_id="1" href="s1">Сезон 1</a>""" +
            """<a class="b-simple_season__item" data-tab_id="2" href="s2">Сезон 2</a></ul>""" +
            """<div class="simple-episodes-tabs"><ul id="simple-episodes-list-1">""" +
            """<a class="b-simple_episode__item active" data-episode_id="1" href="e1">Серия 1</a>""" +
            """<a class="b-simple_episode__item" data-episode_id="2" href="e2">Серия 2</a></ul></div>"""
        assertEquals(listOf(1, 2), HdrezkaApi.parseSeasons(html))
        val episodes = HdrezkaApi.parseEpisodes(html, 1)
        assertEquals(2, episodes.size)
        assertEquals(1, episodes[0].number)
        assertEquals("Серия 1", episodes[0].title)
        assertTrue(HdrezkaApi.parseEpisodes(html, 2).isEmpty())
    }

    @Test
    fun `anubis challenge detected and parsed`() {
        val html = """<html><script id="anubis_base_prefix" type="application/json"></script>""" +
            """<script id="anubis_challenge" type="application/json">""" +
            """{"rules":{"algorithm":"fast","difficulty":2},"challenge":{""" +
            """"id":"01a0a5ec-74b2","randomData":"a7d06021fe48","difficulty":2,"spent":false}}""" +
            """</script></html>"""
        assertTrue(HdrezkaApi.isAnubisChallenge(html))
        assertFalse(HdrezkaApi.isAnubisChallenge("<html><body>Во все тяжкие</body></html>"))
        val challenge = HdrezkaApi.parseAnubisChallenge(html)!!
        assertEquals("01a0a5ec-74b2", challenge.id)
        assertEquals("a7d06021fe48", challenge.randomData)
        assertEquals(2, challenge.difficulty)
    }

    @Test
    fun `anubis nonce validates against worker rules`() {
        val solved = HdrezkaApi.solveAnubisNonce("a7d06021fe4835a3cbe62ab7c81d2e1", 2)!!
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("a7d06021fe4835a3cbe62ab7c81d2e1" + solved.first.toString()).toByteArray(Charsets.UTF_8))
        // difficulty 2 = first byte zero (see sha256-webcrypto.mjs: full bytes + half nibble).
        assertEquals(0.toByte(), digest[0])
        assertNotNull(solved.second)
    }

    @Test
    fun `anubis response hex is exactly 64 lowercase chars`() {
        // Регрессия: "%02x".format(signedByte) давал "ffffffab" вместо "ab" —
        // сервер сверяет response побайтово и резал все доказательства.
        val solved = HdrezkaApi.solveAnubisNonce("a7d06021fe4835a3cbe62ab7c81d2e1", 2)!!
        val expected = java.math.BigInteger(1, MessageDigest.getInstance("SHA-256")
            .digest(("a7d06021fe4835a3cbe62ab7c81d2e1" + solved.first.toString()).toByteArray(Charsets.UTF_8)))
            .toString(16).padStart(64, '0')
        assertEquals(64, solved.second.length)
        assertEquals(expected, solved.second)
        assertTrue(solved.second.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `anubis nonce rejects absurd difficulty fast`() {
        assertNull(HdrezkaApi.solveAnubisNonce("abc", 2, maxAttempts = 0))
    }

    @Test
    fun `anubis pass url carries proof params`() {
        val url = HdrezkaApi.anubisPassUrl(
            "https://rezka.ag", "", "99", "deadbeef", 412,
            "https://rezka.ag/films/x.html", 7
        )
        assertTrue(url.startsWith("https://rezka.ag/.within.website/x/cmd/anubis/api/pass-challenge?"))
        assertTrue("id=99" in url)
        assertTrue("response=deadbeef" in url)
        assertTrue("nonce=412" in url)
        assertTrue("redir=" in url)
    }

    @Test
    fun `ajax plain quality list parses directly without decoding`() {
        // Форма ответа /ajax/get_cdn_series (живой rezka.ag): готовый список,
        // decodeVoidboostFile здесь обязан НЕ применяться (он съедает "[3" и гибнет).
        val raw = "[360p]https://s1/x/360.mp4:hls:manifest.m3u8 or https://s1/x/360b.mp4," +
            "[720p]https://s1/x/720.mp4:hls:manifest.m3u8,[1080p]https://s1/x/1080.mp4:hls:manifest.m3u8"
        val (url, ladder) = HdrezkaApi.ladderFromUrl(raw)!!
        assertEquals("https://s1/x/1080.mp4:hls:manifest.m3u8", url)
        assertEquals(3, ladder.size)
        assertEquals("https://s1/x/360b.mp4", ladder["360p"])
    }

    @Test
    fun `ladder rejects garbage`() {
        assertNull(HdrezkaApi.ladderFromUrl(""))
        assertNull(HdrezkaApi.ladderFromUrl("false"))
    }
}
