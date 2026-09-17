package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.displaySourceName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HentaiCustomSourceTest {

    private fun adultCustom(
        id: String = "CUSTOM_X",
        name: String = "Тест",
        template: String = "https://example.com/embed/kp/{kp}"
    ) = CustomSource(
        id = id, name = name, urlTemplate = template,
        categories = setOf(SourceCategory.ADULT)
    )

    private fun track(
        dubId: String,
        dubTitle: String,
        episode: Int,
        url: String
    ) = hd.kinoshka.app.data.model.DdbbEpisodeTrack(
        dubId = dubId, dubTitle = dubTitle, seasonNumber = 1,
        episodeNumber = episode, title = null, playerUrl = url
    )

    private fun seriesParse() = DdbbStreamResolver.SourceParse(
        sourceName = "CUSTOM_X",
        url = "https://cdn.example/e1.m3u8",
        headers = mapOf("Referer" to "https://example.com/"),
        qualities = mapOf("Auto" to "https://cdn.example/e1.m3u8"),
        voiceRows = emptyList(),
        tracks = listOf(
            track("custom|CUSTOM_X|dub-a", "Дубляж A", 1, "https://cdn.example/a1.m3u8"),
            track("custom|CUSTOM_X|dub-a", "Дубляж A", 2, "https://cdn.example/a2.m3u8")
        ),
        ladders = mapOf(
            "https://cdn.example/a1.m3u8" to mapOf("720p" to "https://cdn.example/a1.m3u8"),
            "https://cdn.example/a2.m3u8" to mapOf("Auto" to "https://cdn.example/a2.m3u8")
        )
    )

    private fun multiDubParse() = DdbbStreamResolver.SourceParse(
        sourceName = "CUSTOM_X",
        url = "https://cdn.example/a1.m3u8",
        headers = mapOf("Referer" to "https://example.com/"),
        qualities = mapOf("Auto" to "https://cdn.example/a1.m3u8"),
        voiceRows = emptyList(),
        tracks = listOf(
            track("custom|CUSTOM_X|dub-a", "Дубляж A", 1, "https://cdn.example/a1.m3u8"),
            track("custom|CUSTOM_X|dub-b", "Дубляж B", 1, "https://cdn.example/b1.m3u8")
        ),
        ladders = emptyMap()
    )

    private fun movieParse() = DdbbStreamResolver.SourceParse(
        sourceName = "CUSTOM_X",
        url = "https://cdn.example/movie.m3u8",
        headers = mapOf("Referer" to "https://example.com/"),
        qualities = mapOf("Auto" to "https://cdn.example/movie.m3u8"),
        voiceRows = listOf("Рус. Дублированный" to "https://cdn.example/movie.m3u8"),
        tracks = emptyList(),
        ladders = emptyMap()
    )

    private fun emptyParse() = DdbbStreamResolver.SourceParse(
        sourceName = "CUSTOM_X", url = "", headers = emptyMap(),
        qualities = emptyMap(), voiceRows = emptyList(),
        tracks = emptyList(), ladders = emptyMap()
    )

    // --- Неймспейс translationId ---

    @Test
    fun `custom hentai translation id mirrors builtin format`() {
        assertEquals("hentai:custom:CUSTOM_X", HentaiStreamResolver.customHentaiTranslationId("CUSTOM_X", "Фильм"))
        assertEquals("hentai:custom:CUSTOM_X", HentaiStreamResolver.customHentaiTranslationId("CUSTOM_X", null))
        assertEquals("hentai:custom:CUSTOM_X", HentaiStreamResolver.customHentaiTranslationId("CUSTOM_X", "  "))
        assertEquals(
            "hentai:custom:CUSTOM_X:Серия 1",
            HentaiStreamResolver.customHentaiTranslationId("CUSTOM_X", "Серия 1")
        )
    }

    @Test
    fun `custom id of hentai translation round-trips`() {
        assertEquals("CUSTOM_X", HentaiStreamResolver.customIdOfHentaiTranslation("hentai:custom:CUSTOM_X"))
        assertEquals(
            "CUSTOM_X",
            HentaiStreamResolver.customIdOfHentaiTranslation("hentai:custom:CUSTOM_X:Серия 1")
        )
        assertNull(HentaiStreamResolver.customIdOfHentaiTranslation("hentai:ALLHENTAI"))
        assertNull(HentaiStreamResolver.customIdOfHentaiTranslation("hentai:ALLHENTAI:Серия 1"))
        assertNull(HentaiStreamResolver.customIdOfHentaiTranslation("609"))
    }

    // --- Маппинг парса в HentaiStream ---

    @Test
    fun `series tracks map to plain episode labels`() {
        val stream = HentaiStreamResolver.customParseToHentai(adultCustom(), seriesParse())!!
        assertEquals("https://cdn.example/a1.m3u8", stream.url)
        assertEquals("Тест", stream.title)
        assertEquals(mapOf("Referer" to "https://example.com/"), stream.headers)
        assertEquals(listOf("Серия 1", "Серия 2"), stream.episodes.map { it.label })
        assertEquals("https://cdn.example/a2.m3u8", stream.episodes[1].url)
        // Лестница первой серии пробрасывается, бейдж — лучший ранг.
        assertEquals(mapOf("720p" to "https://cdn.example/a1.m3u8"), stream.qualities)
        assertEquals("720p", stream.quality)
        assertEquals("720p", stream.episodes[0].maxQuality)
    }

    @Test
    fun `multi-dub tracks get prefixed labels`() {
        val stream = HentaiStreamResolver.customParseToHentai(adultCustom(), multiDubParse())!!
        assertEquals(2, stream.episodes.size)
        assertEquals("Дубляж A • Серия 1", stream.episodes[0].label)
        assertEquals("Дубляж B • Серия 1", stream.episodes[1].label)
    }

    @Test
    fun `movie voice row becomes direct stream`() {
        val stream = HentaiStreamResolver.customParseToHentai(adultCustom(), movieParse())!!
        assertEquals("https://cdn.example/movie.m3u8", stream.url)
        assertTrue(stream.episodes.isEmpty())
        assertEquals(mapOf("Auto" to "https://cdn.example/movie.m3u8"), stream.qualities)
    }

    @Test
    fun `multi voice rows become per-dub episodes`() {
        val parse = DdbbStreamResolver.SourceParse(
            sourceName = "CUSTOM_X",
            url = "https://cdn.example/m1.m3u8",
            headers = emptyMap(),
            qualities = mapOf("Auto" to "https://cdn.example/m1.m3u8"),
            voiceRows = listOf(
                "Дубляж A" to "https://cdn.example/m1.m3u8",
                "Дубляж B" to "https://cdn.example/m2.m3u8"
            ),
            tracks = emptyList(),
            ladders = emptyMap()
        )
        val stream = HentaiStreamResolver.customParseToHentai(adultCustom(), parse)!!
        assertEquals("https://cdn.example/m1.m3u8", stream.url)
        assertEquals(listOf("Дубляж A", "Дубляж B"), stream.episodes.map { it.label })
    }

    @Test
    fun `empty parse maps to null`() {
        assertNull(HentaiStreamResolver.customParseToHentai(adultCustom(), emptyParse()))
    }

    // --- Сквозной фетч с инжектом резолва (без сети) ---

    @Test
    fun `fetchCustomHentai maps injected parse`() = runBlocking {
        var seenKp: Int? = null
        val stream = HentaiStreamResolver.fetchCustomHentai(
            custom = adultCustom(),
            kinopoiskId = 301,
            resolve = { _, kp -> seenKp = kp; movieParse() }
        )
        assertEquals(301, seenKp)
        assertEquals("https://cdn.example/movie.m3u8", stream!!.url)
    }

    @Test
    fun `fetchCustomHentai skips synthetic anime ids`() = runBlocking {
        var called = false
        val stream = HentaiStreamResolver.fetchCustomHentai(
            custom = adultCustom(),
            kinopoiskId = hd.kinoshka.app.data.model.ANIME_ID_OFFSET + 5,
            resolve = { _, _ -> called = true; movieParse() }
        )
        assertNull(stream)
        assertTrue(!called)
    }

    @Test
    fun `fetchCustomHentai null when resolve misses`() = runBlocking {
        val stream = HentaiStreamResolver.fetchCustomHentai(
            custom = adultCustom(),
            kinopoiskId = 301,
            resolve = { _, _ -> null }
        )
        assertNull(stream)
    }

    // --- Stremio-ветка с инжектом сети ---

    private fun adultStremio() = CustomSource(
        id = "CUSTOM_ADULT_S", name = "Стримио 18+",
        urlTemplate = "", kind = CustomSourceKind.STREMIO,
        endpoint = "https://adult.example.com/addon",
        categories = setOf(SourceCategory.ADULT)
    )

    private fun stremioMovieFetch(): suspend (String) -> String? = { url ->
        when {
            url.endsWith("/manifest.json") -> """
                {"id":"com.ex.adult","version":"1.0.0","name":"Adult Addon",
                 "resources":["stream"],"types":["movie"],"idPrefixes":["tt"]}
            """.trimIndent()
            "/stream/movie/" in url -> """{"streams":[
                {"url":"https://cdn.example.com/h_1080.mp4","name":"Adult","title":"1080p"}]}"""
            else -> """{"streams":[]}"""
        }
    }

    @Test
    fun `fetchCustomHentai maps stremio movie to direct stream`() = runBlocking {
        var embedCalled = false
        val stream = HentaiStreamResolver.fetchCustomHentai(
            custom = adultStremio(),
            kinopoiskId = 301,
            imdbId = "tt1234567",
            resolve = { _, _ -> embedCalled = true; movieParse() },
            stremioFetch = stremioMovieFetch()
        )!!
        assertTrue(!embedCalled)
        assertEquals("https://cdn.example.com/h_1080.mp4", stream.url)
        assertEquals("1080p", stream.quality)
        assertEquals("Стримио 18+", stream.title)
    }

    @Test
    fun `fetchCustomHentai skips stremio without imdb`() = runBlocking {
        var networkCalled = false
        val spy: suspend (String) -> String? = { networkCalled = true; "{}" }
        assertNull(
            HentaiStreamResolver.fetchCustomHentai(
                custom = adultStremio(),
                kinopoiskId = 301,
                imdbId = null,
                stremioFetch = spy
            )
        )
        assertTrue(!networkCalled)
    }

    // --- Валидация ADULT-раздела ---

    @Test
    fun `validation warns on adult without kp and on webOnly adult`() {
        val noKp = validateCustomSource(
            "X", "https://example.com/{imdb}", emptyList(),
            categories = setOf(SourceCategory.ADULT)
        )
        assertTrue(noKp is CustomSourceCheck.Ok)
        assertTrue((noKp as CustomSourceCheck.Ok).warnings.any { "{kp}" in it })

        val webOnly = validateCustomSource(
            "X", "https://example.com/{kp}", emptyList(),
            categories = setOf(SourceCategory.ADULT), webOnly = true
        )
        assertTrue(webOnly is CustomSourceCheck.Ok)
        assertTrue((webOnly as CustomSourceCheck.Ok).warnings.any { "еб-плеер" in it })

        val filmsOnly = validateCustomSource(
            "X", "https://example.com/{imdb}", emptyList(),
            categories = setOf(SourceCategory.FILMS)
        )
        assertTrue((filmsOnly as CustomSourceCheck.Ok).warnings.isEmpty())
    }

    @Test
    fun `custom hentai rows attribute to custom source type`() {
        // Плеер различает строки по source: свои едут общим типом CUSTOM,
        // имя — в sourceLabel (как у аниме-кастомов).
        val row = hd.kinoshka.app.data.model.FlatTranslation(
            source = AnimeSourceType.CUSTOM,
            translationId = HentaiStreamResolver.customHentaiTranslationId("CUSTOM_X", "Серия 1"),
            title = "Серия 1",
            sourceLabel = "Тест"
        )
        assertEquals(AnimeSourceType.CUSTOM, row.source)
        assertEquals("Тест", row.displaySourceName())
        assertEquals("CUSTOM_X", HentaiStreamResolver.customIdOfHentaiTranslation(row.translationId))
    }
}
