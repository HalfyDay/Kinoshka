package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.displaySourceName
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeCustomSourceTest {

    private fun animeCustom(
        id: String = "CUSTOM_X",
        name: String = "Тест",
        template: String = "https://example.com/embed/kp/{kp}"
    ) = CustomSource(
        id = id, name = name, urlTemplate = template,
        categories = setOf(SourceCategory.ANIME)
    )

    private fun track(
        dubId: String,
        dubTitle: String,
        season: Int,
        episode: Int,
        url: String
    ) = hd.kinoshka.app.data.model.DdbbEpisodeTrack(
        dubId = dubId, dubTitle = dubTitle, seasonNumber = season,
        episodeNumber = episode, title = null, playerUrl = url
    )

    private fun seriesParse() = DdbbStreamResolver.SourceParse(
        sourceName = "CUSTOM_X",
        url = "https://cdn.example/e1.m3u8",
        headers = mapOf("Referer" to "https://example.com/"),
        qualities = mapOf("Auto" to "https://cdn.example/e1.m3u8"),
        voiceRows = emptyList(),
        tracks = listOf(
            track("custom|CUSTOM_X|dub-a", "Дубляж A", 1, 1, "https://cdn.example/a1.m3u8"),
            track("custom|CUSTOM_X|dub-a", "Дубляж A", 1, 2, "https://cdn.example/a2.m3u8"),
            track("custom|CUSTOM_X|dub-b", "Дубляж B", 2, 1, "https://cdn.example/b1.m3u8")
        ),
        ladders = mapOf("https://cdn.example/a1.m3u8" to mapOf("Auto" to "https://cdn.example/a1.m3u8"))
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

    // --- Неймспейс translationId ---

    @Test
    fun `custom translation id round-trips`() {
        val id = AnimeStreamResolver.customTranslationId("CUSTOM_X", "dub-a")
        assertEquals("custom|CUSTOM_X|dub-a", id)
        assertEquals("CUSTOM_X", AnimeStreamResolver.customIdOfTranslation(id))
        assertEquals("dub-a", AnimeStreamResolver.customDubSlugOf(id))
        assertNull(AnimeStreamResolver.customIdOfTranslation("609"))
        assertNull(AnimeStreamResolver.customDubSlugOf("anistar"))
    }

    // --- Настоящий kp id ---

    @Test
    fun `realKinopoiskId rejects synthetic offset ids`() {
        assertEquals(301, AnimeStreamResolver.realKinopoiskId(301))
        assertNull(AnimeStreamResolver.realKinopoiskId(0))
        assertNull(AnimeStreamResolver.realKinopoiskId(-5))
        assertNull(AnimeStreamResolver.realKinopoiskId(ANIME_ID_OFFSET))
        assertNull(AnimeStreamResolver.realKinopoiskId(ANIME_ID_OFFSET + 5))
    }

    // --- Мост Kodik → kp ---

    @Test
    fun `pickAnimeKpId takes first positive id`() {
        val results = listOf(
            JSONObject("""{"shikimori_id":"1"}"""),
            JSONObject("""{"kinopoisk_id":"23455"}"""),
            JSONObject("""{"kinopoisk_id":34567}""")
        )
        assertEquals(23455, AnimeStreamResolver.pickAnimeKpId(results))
        assertNull(AnimeStreamResolver.pickAnimeKpId(emptyList()))
        assertNull(AnimeStreamResolver.pickAnimeKpId(listOf(JSONObject("""{"kinopoisk_id":null}"""))))
        assertNull(AnimeStreamResolver.pickAnimeKpId(listOf(JSONObject("""{"kinopoisk_id":"abc"}"""))))
    }

    // --- Маппинг парса в строки пикера ---

    @Test
    fun `series tracks group by dub with namespaced ids`() {
        val rows = AnimeStreamResolver.customParseToTranslations(animeCustom(), seriesParse())
        assertEquals(2, rows.size)
        val a = rows.first { it.translationId == "custom|CUSTOM_X|dub-a" }
        assertEquals(AnimeSourceType.CUSTOM, a.source)
        assertEquals("Дубляж A", a.title)
        assertEquals("Тест", a.sourceLabel)
        assertEquals("Тест", a.displaySourceName())
        assertEquals(listOf(1, 2), a.episodes.map { it.number })
        assertEquals("https://cdn.example/a1.m3u8", a.episodes[0].link)
        assertEquals(1, a.episodes[0].season)
        val b = rows.first { it.translationId == "custom|CUSTOM_X|dub-b" }
        assertEquals(2, b.episodes[0].season)
    }

    @Test
    fun `movie voice row becomes one single-episode row`() {
        val rows = AnimeStreamResolver.customParseToTranslations(animeCustom(), movieParse())
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(AnimeSourceType.CUSTOM, row.source)
        assertEquals("Рус. Дублированный", row.title)
        assertEquals(
            "custom|CUSTOM_X|" + slugifyCustomName("Рус. Дублированный"),
            row.translationId
        )
        assertEquals(1, row.episodes.size)
        assertEquals(1, row.episodes[0].number)
        assertEquals("https://cdn.example/movie.m3u8", row.episodes[0].link)
        assertEquals("Тест", row.displaySourceName())
    }

    @Test
    fun `empty parse maps to no rows`() {
        val empty = DdbbStreamResolver.SourceParse(
            sourceName = "CUSTOM_X", url = "", headers = emptyMap(),
            qualities = emptyMap(), voiceRows = emptyList(),
            tracks = emptyList(), ladders = emptyMap()
        )
        assertTrue(AnimeStreamResolver.customParseToTranslations(animeCustom(), empty).isEmpty())
    }

    // --- Выбор URL серии ---

    @Test
    fun `pickCustomEpisodeUrl hits exact episode then nearest`() {
        val parse = seriesParse()
        val (exact, ladder) = AnimeStreamResolver.pickCustomEpisodeUrl(parse, "custom|CUSTOM_X|dub-a", 2)!!
        assertEquals("https://cdn.example/a2.m3u8", exact)
        // Лестницы нет — дефолт парса.
        assertEquals(mapOf("Auto" to "https://cdn.example/e1.m3u8"), ladder)
        val (first, _) = AnimeStreamResolver.pickCustomEpisodeUrl(parse, "custom|CUSTOM_X|dub-a", 1)!!
        assertEquals("https://cdn.example/a1.m3u8", first)
        // Нет такой серии — ближайшая.
        val (nearest, _) = AnimeStreamResolver.pickCustomEpisodeUrl(parse, "custom|CUSTOM_X|dub-a", 9)!!
        assertEquals("https://cdn.example/a2.m3u8", nearest)
        // Чужой даб — null.
        assertNull(AnimeStreamResolver.pickCustomEpisodeUrl(parse, "custom|CUSTOM_X|nope", 1))
    }

    @Test
    fun `pickCustomEpisodeUrl matches movie row by slug`() {
        val parse = movieParse()
        val slug = slugifyCustomName("Рус. Дублированный")
        val (url, qualities) = AnimeStreamResolver.pickCustomEpisodeUrl(parse, "custom|CUSTOM_X|$slug", 1)!!
        assertEquals("https://cdn.example/movie.m3u8", url)
        assertEquals(mapOf("Auto" to "https://cdn.example/movie.m3u8"), qualities)
    }

    // --- Сквозной фетч с инжектом резолва (без сети) ---

    @Test
    fun `fetchCustomAnimeTranslations maps injected parse`() = runBlocking {
        var seenKp: Int? = null
        val rows = AnimeStreamResolver.fetchCustomAnimeTranslations(
            custom = animeCustom(),
            shikimoriId = 1,
            animeTitle = "Матрица",
            kinopoiskId = 301,
            resolve = { _, kp -> seenKp = kp; movieParse() }
        )
        assertEquals(301, seenKp)
        assertEquals(1, rows.size)
        assertEquals("Рус. Дублированный", rows.single().title)
    }

    @Test
    fun `fetchCustomAnimeTranslations empty when resolve misses`() = runBlocking {
        val rows = AnimeStreamResolver.fetchCustomAnimeTranslations(
            custom = animeCustom(),
            shikimoriId = 1,
            animeTitle = "Матрица",
            kinopoiskId = 301,
            resolve = { _, _ -> null }
        )
        assertTrue(rows.isEmpty())
    }

    // --- Валидация ANIME-раздела ---

    @Test
    fun `validation warns on anime without kp and on webOnly anime`() {
        val noKp = validateCustomSource(
            "X", "https://example.com/{imdb}", emptyList(),
            categories = setOf(SourceCategory.ANIME)
        )
        assertTrue(noKp is CustomSourceCheck.Ok)
        assertTrue((noKp as CustomSourceCheck.Ok).warnings.any { "{kp}" in it })

        val webOnly = validateCustomSource(
            "X", "https://example.com/{kp}", emptyList(),
            categories = setOf(SourceCategory.ANIME), webOnly = true
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
    fun `displaySourceName falls back to type name`() {
        val plain = hd.kinoshka.app.data.model.FlatTranslation(
            source = AnimeSourceType.KODIK, translationId = "1", title = "T"
        )
        assertEquals("Kodik", plain.displaySourceName())
        assertNull(plain.sourceLabel)
    }
}
