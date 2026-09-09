package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnixartVideoResolverTest {

    @Test
    fun `translation id roundtrips`() {
        val encoded = AnixartVideoResolver.encodeTranslationId(19081, 2)
        assertEquals("19081:2", encoded)
        val ref = AnixartVideoResolver.decodeTranslationId(encoded)
        assertEquals(19081, ref?.releaseId)
        assertEquals(2, ref?.dubberId)
        assertNull(ref?.sourceId)
    }

    @Test
    fun `translation id reads legacy host-pinned format`() {
        val ref = AnixartVideoResolver.decodeTranslationId("19081:2:7")
        assertEquals(19081, ref?.releaseId)
        assertEquals(2, ref?.dubberId)
        assertEquals(7, ref?.sourceId)
    }

    @Test
    fun `translation id rejects garbage`() {
        assertNull(AnixartVideoResolver.decodeTranslationId(""))
        assertNull(AnixartVideoResolver.decodeTranslationId("2"))
        assertNull(AnixartVideoResolver.decodeTranslationId("a:b"))
        assertNull(AnixartVideoResolver.decodeTranslationId("0:2"))
        assertNull(AnixartVideoResolver.decodeTranslationId("-1:2"))
        assertNull(AnixartVideoResolver.decodeTranslationId("1:2:3:4"))
    }

    @Test
    fun `dead sources are filtered by name`() {
        assertTrue(AnixartVideoResolver.isUnusableSourceName("Источник 3 (не работает)"))
        assertTrue(AnixartVideoResolver.isUnusableSourceName("НЕ РАБОТАЕТ"))
        assertFalse(AnixartVideoResolver.isUnusableSourceName("Kodik"))
        assertFalse(AnixartVideoResolver.isUnusableSourceName("Sibnet"))
        assertFalse(AnixartVideoResolver.isUnusableSourceName(null))
        assertFalse(AnixartVideoResolver.isUnusableSourceName(""))
    }

    @Test
    fun `dubber type maps sub flag`() {
        assertEquals("sub", AnixartVideoResolver.dubberType(true))
        assertEquals("voice", AnixartVideoResolver.dubberType(false))
    }

    @Test
    fun `kodik hosts resolve last`() {
        val sources = listOf(
            AnixartVideoResolver.VideoSource(7, "Kodik"),
            AnixartVideoResolver.VideoSource(1, "Sibnet"),
            AnixartVideoResolver.VideoSource(218, "Libria")
        )
        val ordered = AnixartVideoResolver.orderSourcesForResolve(sources)
        assertEquals(listOf(1, 218, 7), ordered.map { it.id })
    }

    @Test
    fun `union takes richest list and best quality per position`() {
        val kodik = listOf(
            AnixartVideoResolver.Episode(1, "Серия", "https://kodikplayer.com/seria/1/abc/720p", true),
            AnixartVideoResolver.Episode(2, "Серия", "https://kodikplayer.com/seria/2/def/720p", true)
        )
        val sibnet = listOf(
            AnixartVideoResolver.Episode(1, "1 серия", "https://video.sibnet.ru/shell.php?videoid=1", false)
        )
        val united = AnixartVideoResolver.unionEpisodes(listOf(kodik, sibnet))
        assertEquals(listOf(1, 2), united.episodes.map { it.position })
        assertEquals("1 серия", united.episodes.first { it.position == 1 }.name)
        assertEquals("720p", united.bestQuality[1])
    }

    @Test
    fun `union of nothing is empty`() {
        assertTrue(AnixartVideoResolver.unionEpisodes(emptyList()).episodes.isEmpty())
    }
}
