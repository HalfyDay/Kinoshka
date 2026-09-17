package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class HostCooldownTest {

    @Test
    fun `fresh host is not skipped`() {
        assertFalse(HostCooldown.shouldSkip("fresh.invalid"))
    }

    @Test
    fun `failure marks host skipped until success`() {
        val host = "flaky.invalid"
        HostCooldown.recordFailure(host)
        assertTrue(HostCooldown.shouldSkip(host))
        HostCooldown.recordSuccess(host)
        assertFalse(HostCooldown.shouldSkip(host))
    }

    @Test
    fun `cooldown expires`() {
        val old = HostCooldown.cooldownMs
        HostCooldown.cooldownMs = 1L
        try {
            val host = "expiring.invalid"
            HostCooldown.recordFailure(host)
            assertTrue(HostCooldown.shouldSkip(host))
            Thread.sleep(5L)
            assertFalse(HostCooldown.shouldSkip(host))
        } finally {
            HostCooldown.cooldownMs = old
        }
    }

    @Test
    fun `connectivity failures detected`() {
        assertTrue(HostCooldown.isConnectivityFailure(SocketTimeoutException()))
        assertTrue(HostCooldown.isConnectivityFailure(UnknownHostException()))
        assertTrue(HostCooldown.isConnectivityFailure(RuntimeException(SocketTimeoutException())))
        assertFalse(HostCooldown.isConnectivityFailure(RuntimeException("http 403")))
    }

    @Test
    fun `hostOf extracts host`() {
        assertEquals("cdn.svetacdn.in", HostCooldown.hostOf("https://cdn.svetacdn.in/api/x?q=1"))
        assertEquals("", HostCooldown.hostOf("not a url"))
    }
}
