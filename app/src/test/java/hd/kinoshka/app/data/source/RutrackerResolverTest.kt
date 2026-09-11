package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RutrackerResolverTest {

    private val trackerPage = """
        <table id="tor-tbl"><tbody>
        <tr class="hl-tr">
          <td class="row1"><a class="tLink" href="viewtopic.php?t=111">Матрица / The Matrix (1999) [1080p, HEVC]</a></td>
          <td class="row1"><a href="dl.php?t=111">2.45&nbsp;GB</a></td>
          <td class="row1"><b class="seedmed">123</b></td>
          <td class="row1"><b class="leechmed">4</b></td>
        </tr>
        <tr class="hl-tr">
          <td class="row1"><a class="tLink" href="./viewtopic.php?t=222"><span>Интерстеллар</span> (2014) 720p</a></td>
          <td class="row1"><a href="./dl.php?t=222">1.4 GB</a></td>
          <td class="row1 seedmed"><span class="seedmed">7</span></td>
          <td class="row1"><span class="leechmed">0</span></td>
        </tr>
        <tr class="hl-tr">
          <td class="row1">битая строка без ссылки</td>
        </tr>
        </tbody></table>
    """.trimIndent()

    @Test
    fun `parses tracker rows into torrent links`() {
        val links = RutrackerResolver.parseTrackerPage(trackerPage, "https://rutracker.org")

        assertEquals(2, links.size)

        val first = links[0]
        assertEquals("1080p", first.quality)
        assertEquals("2.45 GB", first.size)
        assertEquals(123, first.seeders)
        assertEquals(4, first.leechers)
        assertNull(first.magnet)
        assertEquals("https://rutracker.org/forum/dl.php?t=111", first.torrentUrl)
        assertEquals("Rutracker", first.source)
        assertEquals("HEVC", first.codec)
        assertTrue(first.label!!.contains("Матрица"))

        val second = links[1]
        assertEquals("720p", second.quality)
        assertEquals("1.4 GB", second.size)
        assertEquals(7, second.seeders)
        assertEquals(0, second.leechers)
        assertEquals("https://rutracker.org/forum/dl.php?t=222", second.torrentUrl)
        assertTrue(second.label!!.contains("Интерстеллар"))
    }

    @Test
    fun `empty page gives empty list`() {
        assertTrue(RutrackerResolver.parseTrackerPage("<html></html>", "https://rutracker.org").isEmpty())
        assertTrue(RutrackerResolver.parseTrackerPage("", "https://rutracker.org").isEmpty())
    }

    @Test
    fun `detects login page`() {
        val loginForm = """<form action="login.php" method="post">
            <input type="text" name="login_username" />
            <input type="password" name="login_password" /></form>"""
        assertTrue(RutrackerResolver.isLoginPage(loginForm))
        assertFalse(RutrackerResolver.isLoginPage(trackerPage))
    }

    @Test
    fun `unescapes html entities`() {
        assertEquals("Матрица & Ко", RutrackerResolver.unescapeHtml("Матрица &amp; Ко"))
        assertEquals("Тётя", RutrackerResolver.unescapeHtml("Т&#1105;тя"))
        assertEquals("1.4 GB", RutrackerResolver.unescapeHtml("1.4&nbsp;GB"))
    }
}
