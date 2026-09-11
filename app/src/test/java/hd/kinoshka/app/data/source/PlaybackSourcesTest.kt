package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourcesTest {

    @Test
    fun `all ids are unique uppercase`() {
        val ids = PlaybackSources.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it == it.uppercase() })
    }

    @Test
    fun `picker lists only reference known sources`() {
        val known = PlaybackSources.ALL.map { it.id }.toSet()
        assertTrue(known.containsAll(PlaybackSources.ANIME_IDS))
        assertTrue(known.containsAll(PlaybackSources.MOVIE_IDS))
        assertTrue(known.containsAll(PlaybackSources.ADULT_IDS))
    }

    @Test
    fun `movie picker covers kodik and all direct providers`() {
        assertTrue(PlaybackSources.MOVIE_IDS.contains(PlaybackSources.KODIK))
        listOf(
            PlaybackSources.TURBO,
            PlaybackSources.VIDEOCDN,
            PlaybackSources.COLLAPS,
            PlaybackSources.VOIDBOOST,
            PlaybackSources.ALLOHA,
            PlaybackSources.VEOVEO
        ).forEach { assertTrue(PlaybackSources.MOVIE_IDS.contains(it)) }
    }

    @Test
    fun `ddbb source names map to registry ids`() {
        assertEquals(PlaybackSources.TURBO, PlaybackSources.ddbbSourceNameToId("Turbo"))
        assertEquals(PlaybackSources.TURBO, PlaybackSources.ddbbSourceNameToId("turbo"))
        assertEquals(PlaybackSources.VIDEOCDN, PlaybackSources.ddbbSourceNameToId("VideoCDN"))
        assertEquals(PlaybackSources.COLLAPS, PlaybackSources.ddbbSourceNameToId("Collaps"))
        assertEquals(PlaybackSources.VOIDBOOST, PlaybackSources.ddbbSourceNameToId("Voidboost"))
        assertEquals(PlaybackSources.ALLOHA, PlaybackSources.ddbbSourceNameToId("Alloha"))
        assertEquals(PlaybackSources.VEOVEO, PlaybackSources.ddbbSourceNameToId("Veoveo"))
        assertEquals(null, PlaybackSources.ddbbSourceNameToId("unknown-host"))
    }

    @Test
    fun `anime picker covers anixart`() {
        assertTrue(PlaybackSources.ANIME_IDS.contains(PlaybackSources.ANIXART))
        assertNotNull(PlaybackSources.info(PlaybackSources.ANIXART))
        assertEquals("Anixart", PlaybackSources.displayName("anixart"))
    }

    @Test
    fun `display names fall back to raw id`() {
        assertEquals("Kodik", PlaybackSources.displayName("kodik"))
        assertEquals("Turbo", PlaybackSources.displayName("TURBO"))
        assertNotNull(PlaybackSources.info(PlaybackSources.SHIKIMORI))
    }
}
