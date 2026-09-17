package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.AnimeSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSourceTest {

    private fun src(
        id: String = "CUSTOM_TEST",
        name: String = "Тест",
        template: String = "https://example.com/embed/kp/{kp}"
    ) = CustomSource(id = id, name = name, urlTemplate = template)

    // --- Плейсхолдеры ---

    @Test
    fun `buildUrl substitutes kp and imdb`() {
        val both = src(template = "https://example.com/e/{kp}/{imdb}")
        assertEquals("https://example.com/e/301/tt0133093", both.buildUrl(301, "tt0133093"))
        assertEquals("https://example.com/embed/kp/301", src().buildUrl(301, null))
        val imdbOnly = src(template = "https://example.com/e/{imdb}")
        assertEquals("https://example.com/e/tt0133093", imdbOnly.buildUrl(null, "tt0133093"))
    }

    @Test
    fun `buildUrl returns null without required id`() {
        assertNull(src().buildUrl(null, null))
        assertNull(src().buildUrl(0, "tt0133093"))
        val imdbOnly = src(template = "https://example.com/e/{imdb}")
        assertNull(imdbOnly.buildUrl(301, null))
        assertNull(imdbOnly.buildUrl(301, " "))
    }

    // --- Валидация ---

    @Test
    fun `validation accepts a good source`() {
        val check = validateCustomSource("Мой источник", "https://example.com/embed/kp/{kp}", emptyList())
        assertTrue(check is CustomSourceCheck.Ok)
    }

    @Test
    fun `validation rejects empty and long names`() {
        assertTrue(validateCustomSource("  ", "https://example.com/{kp}", emptyList()) is CustomSourceCheck.Failed)
        assertTrue(validateCustomSource("x".repeat(41), "https://example.com/{kp}", emptyList()) is CustomSourceCheck.Failed)
    }

    @Test
    fun `validation rejects duplicate names case-insensitively`() {
        val existing = listOf(src(name = "Мой источник"))
        val check = validateCustomSource("мой ИСТОЧНИК", "https://other.com/{kp}", existing)
        assertTrue(check is CustomSourceCheck.Failed)
        // Самому себе при правке не конфликтовать.
        val selfOk = validateCustomSource(
            "Мой источник", "https://other.com/{kp}", existing, selfId = "CUSTOM_TEST"
        )
        assertTrue(selfOk is CustomSourceCheck.Ok)
    }

    @Test
    fun `validation rejects builtin names and bad templates`() {
        assertTrue(
            validateCustomSource("Turbo", "https://example.com/{kp}", emptyList(), builtInNames = listOf("Turbo"))
                is CustomSourceCheck.Failed
        )
        // Без плейсхолдера.
        assertTrue(
            validateCustomSource("X", "https://example.com/embed/kp/301", emptyList())
                is CustomSourceCheck.Failed
        )
        // Не http(s).
        assertTrue(
            validateCustomSource("X", "ftp://example.com/{kp}", emptyList())
                is CustomSourceCheck.Failed
        )
        // Хост без точки.
        assertTrue(
            validateCustomSource("X", "https://localhost/{kp}", emptyList())
                is CustomSourceCheck.Failed
        )
    }

    @Test
    fun `validation rejects duplicate templates normalized`() {
        val existing = listOf(src(template = "https://example.com/embed/kp/{kp}?foo=1"))
        // Тот же шаблон другим регистром хоста и без query — дубликат.
        val check = validateCustomSource("Другой", "HTTPS://EXAMPLE.COM/embed/kp/{kp}", existing)
        assertTrue(check is CustomSourceCheck.Failed)
    }

    // --- Id и сериализация ---

    @Test
    fun `buildCustomId slugifies cyrillic and keeps unique`() {
        assertEquals("CUSTOM_MOY-ISTOCHNIK", buildCustomId("Мой источник!", emptySet()))
        assertEquals(
            "CUSTOM_MOY-ISTOCHNIK-2",
            buildCustomId("Мой источник", setOf("CUSTOM_MOY-ISTOCHNIK"))
        )
        assertEquals("CUSTOM_SRC", buildCustomId("!!!", emptySet()))
    }

    @Test
    fun `json round-trip keeps sources and skips broken entries`() {
        val sources = listOf(
            src(),
            src(id = "CUSTOM_TWO", name = "Два", template = "https://two.com/{imdb}")
        )
        val parsed = parseCustomSources(customSourcesToJson(sources))
        assertEquals(sources, parsed)
        // Битая запись скипается, хорошая выживает; id каноникализируется.
        val mixed = """[
            {"id":"custom_three","name":"Три","urlTemplate":"https://three.com/{kp}"},
            {"id":"broken","name":"","urlTemplate":""},
            {"id":"junk","name":42}
        ]"""
        val mixedParsed = parseCustomSources(mixed)
        assertEquals(1, mixedParsed.size)
        assertEquals("CUSTOM_THREE", mixedParsed.first().id)
        assertEquals(emptyList<CustomSource>(), parseCustomSources("not json"))
        assertEquals(emptyList<CustomSource>(), parseCustomSources(null))
    }

    @Test
    fun `effectiveReferer prefers explicit then embed origin`() {
        assertEquals("https://example.com/", src().effectiveReferer("https://example.com/embed/kp/301"))
        val explicit = src().copy(referer = "https://agg.example/")
        assertEquals("https://agg.example/", explicit.effectiveReferer("https://example.com/x"))
    }

    // --- Маппинги реестра ---

    @Test
    fun `customInfo carries custom type and films category`() {
        val info = PlaybackSources.customInfo(src())
        assertEquals("CUSTOM_TEST", info.id)
        assertEquals("Тест", info.displayName)
        assertEquals(AnimeSourceType.CUSTOM, info.animeSourceType)
        assertTrue(SourceCategory.FILMS in info.categories)
        assertEquals(AnimeSourceType.CUSTOM, PlaybackSources.animeSourceTypeFor("custom_test"))
        assertEquals(AnimeSourceType.CUSTOM, PlaybackSources.animeSourceTypeForDubId("custom|CUSTOM_TEST|turbo|dub"))
    }

    @Test
    fun `old json without categories decodes as films`() {
        val parsed = parseCustomSources(
            """[{"id":"CUSTOM_OLD","name":"Старый","urlTemplate":"https://old.com/{kp}"}]"""
        )
        assertEquals(1, parsed.size)
        assertEquals(setOf(SourceCategory.FILMS), parsed.first().categories)
    }

    @Test
    fun `categories round-trip and drive registry info`() {
        val multi = src().copy(categories = setOf(SourceCategory.FILMS, SourceCategory.ANIME))
        val parsed = parseCustomSources(customSourcesToJson(listOf(multi)))
        assertEquals(setOf(SourceCategory.FILMS, SourceCategory.ANIME), parsed.first().categories)
        val info = PlaybackSources.customInfo(multi)
        assertEquals(setOf(SourceCategory.FILMS, SourceCategory.ANIME), info.categories)
        assertTrue(info.description.contains("Аниме"))
        // Только киношный — без суффикса разделов.
        assertEquals("Свой источник: example.com", PlaybackSources.customInfo(src()).description)
    }

    @Test
    fun `webOnlyParse exposes embed url without rows`() {
        val custom = src()
        val parse = CustomSourceResolver.webOnlyParse(
            custom,
            "https://example.com/embed/kp/301",
            mapOf("Referer" to "https://example.com/")
        )
        assertEquals("CUSTOM_TEST", parse.sourceName)
        assertEquals("https://example.com/embed/kp/301", parse.url)
        assertTrue(parse.voiceRows.isEmpty())
        assertTrue(parse.tracks.isEmpty())
    }

    // --- Реальный Venom-embed (delivembd, «Матрица» kp=301, снят 2026-09-16) ---

    private fun venomHtml(): String =
        javaClass.classLoader.getResourceAsStream("custom/delivembd_matrix_301.html")!!
            .bufferedReader(Charsets.UTF_8).readText()

    @Test
    fun `venom embed exposes direct hls`() {
        val html = venomHtml()
        assertTrue("fixture must carry the venom player config", html.contains("makePlayer({"))
        val extraction = DdbbStreamResolver.extractFromEmbed(html, "https://api.delivembd.ws/embed/kp/301")
        assertNotNull("venom hls: url must be recognized", extraction)
        val (_, qualities) = extraction!!
        val hls = qualities["Auto"]
        assertNotNull(hls)
        assertTrue(hls!!.startsWith("https://"))
        assertTrue(hls.contains("master.m3u8"))
    }

    @Test
    fun `generic parse of venom movie yields dubbed voice row`() {
        val html = venomHtml()
        val url = "https://api.delivembd.ws/embed/kp/301"
        val headers = mapOf("Referer" to "https://api.delivembd.ws/", "User-Agent" to "UA")
        val parse = DdbbStreamResolver.parseGenericEmbed(
            sourceName = "CUSTOM_TEST",
            dubIdPrefix = "custom|CUSTOM_TEST|",
            embedUrl = url,
            html = html,
            headers = headers
        )
        assertNotNull("venom movie must parse into a SourceParse", parse)
        val p = parse!!
        assertEquals("CUSTOM_TEST", p.sourceName)
        assertTrue(p.url.contains("master.m3u8"))
        // Фильм: строк серий нет, одна строка озвучки из audio.names.
        assertTrue(p.tracks.isEmpty())
        assertEquals(1, p.voiceRows.size)
        assertEquals("Рус. Дублированный", p.voiceRows[0].first)
        assertEquals(p.url, p.voiceRows[0].second)
        // Атрибуция строки — кастомный id, как в translationSources.
        val sources = DdbbStreamResolver.translationSources(listOf(p))
        assertEquals("CUSTOM_TEST", sources["Рус. Дублированный"])
    }
}
