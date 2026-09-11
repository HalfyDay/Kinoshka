package hd.kinoshka.app.data.download

import hd.kinoshka.app.data.model.pickCappedQualityKey
import hd.kinoshka.app.data.model.pickCappedQualityUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Потолок качества из диалога «Качество загрузки»: null = максимум лестницы,
 * иначе лучший доступный ранг не выше выбранного.
 */
class DownloadQualityCapTest {

    private val ladder = linkedMapOf(
        "1080p" to "https://cdn/x/1080.m3u8",
        "720p" to "https://cdn/x/720.m3u8",
        "480p" to "https://cdn/x/480.m3u8"
    )

    @Test
    fun `null picks best rung`() {
        assertEquals("https://cdn/x/1080.m3u8", pickCappedQualityUrl(ladder, null))
    }

    @Test
    fun `exact cap rank is honored`() {
        assertEquals("https://cdn/x/720.m3u8", pickCappedQualityUrl(ladder, "720p"))
    }

    @Test
    fun `cap above ladder max falls back to ladder max`() {
        assertEquals("https://cdn/x/1080.m3u8", pickCappedQualityUrl(ladder, "2160p"))
    }

    @Test
    fun `cap below ladder min picks nearest available above`() {
        val hdOnly = linkedMapOf(
            "1080p" to "https://cdn/x/1080.m3u8",
            "720p" to "https://cdn/x/720.m3u8"
        )
        assertEquals("https://cdn/x/720.m3u8", pickCappedQualityUrl(hdOnly, "480p"))
    }

    @Test
    fun `empty ladder returns null`() {
        assertNull(pickCappedQualityUrl(emptyMap(), "720p"))
    }

    @Test
    fun `unknown cap label behaves like max`() {
        assertEquals("https://cdn/x/1080.m3u8", pickCappedQualityUrl(ladder, "Auto"))
    }

    @Test
    fun `key variant mirrors url variant`() {
        assertEquals("1080p", pickCappedQualityKey(ladder, null))
        assertEquals("720p", pickCappedQualityKey(ladder, "720p"))
        assertEquals("1080p", pickCappedQualityKey(ladder, "2160p"))
        assertEquals("480p", pickCappedQualityKey(ladder, "480p"))
        assertEquals(null, pickCappedQualityKey(emptyMap(), "720p"))
    }
}
