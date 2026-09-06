package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class StreamProxyConfigTest {

    @Test
    fun `parses http socks and bare proxies`() {
        val http = StreamProxyConfig.parseProxy("http://1.2.3.4:8080")!!
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals("1.2.3.4", (http.address() as InetSocketAddress).hostName)
        assertEquals(8080, (http.address() as InetSocketAddress).port)

        val socks = StreamProxyConfig.parseProxy("socks5://127.0.0.1:1080")!!
        assertEquals(Proxy.Type.SOCKS, socks.type())

        val bare = StreamProxyConfig.parseProxy("proxy.local:3128")!!
        assertEquals(Proxy.Type.HTTP, bare.type())
        assertEquals(3128, (bare.address() as InetSocketAddress).port)

        val creds = StreamProxyConfig.parseProxy("http://user:secret@10.0.0.2:8888")!!
        assertEquals("10.0.0.2", (creds.address() as InetSocketAddress).hostName)
        assertEquals(8888, (creds.address() as InetSocketAddress).port)

        assertNull(StreamProxyConfig.parseProxy(""))
        assertNull(StreamProxyConfig.parseProxy("http://"))
    }

    @Test
    fun `needsProxy matches blocked families and their subdomains only`() {
        assertTrue(StreamProxyConfig.needsProxy("https://api.delivembd.ws/embed/kp/1"))
        assertTrue(StreamProxyConfig.needsProxy("https://cdn.allhentai.fun/x"))
        assertTrue(StreamProxyConfig.needsProxy("https://r1---sn-x.googlevideo.com/videoplayback"))
        assertFalse(StreamProxyConfig.needsProxy("https://cdn-64650e1b.obrut.show/stream/x"))
        assertFalse(StreamProxyConfig.needsProxy("https://kinopoiskapiunofficial.tech/api"))
        // Суффикс матчится по границе метки домена, не по подстроке.
        assertFalse(StreamProxyConfig.needsProxy("https://evildelivembd.ws/"))
    }

    @Test
    fun `proxy selection gates on config and host`() {
        StreamProxyConfig.proxyUrl = "http://10.0.0.1:8080"
        assertNotNull(StreamProxyConfig.okHttpProxy("https://voidboost.net/embed/1"))
        assertNull(StreamProxyConfig.okHttpProxy("https://obrut.show/stream"))
        assertEquals(
            "http://10.0.0.1:8080",
            StreamProxyConfig.mpvProxyFor("https://www.youtube.com/youtubei/v1/player")
        )
        assertEquals("", StreamProxyConfig.mpvProxyFor("https://obrut.show/stream"))
        StreamProxyConfig.proxyUrl = null
        assertNull(StreamProxyConfig.okHttpProxy("https://voidboost.net/embed/1"))
        assertEquals("", StreamProxyConfig.mpvProxyFor("https://voidboost.net/embed/1"))
    }
}
