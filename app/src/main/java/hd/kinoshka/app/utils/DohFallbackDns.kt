package hd.kinoshka.app.utils

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS resolver that prefers DNS-over-HTTPS for streaming domains.
 *
 * System DNS on RU networks poisons or times out for many streaming hosts. Even when it
 * returns a plausible IP, connections to that IP often hang because of DPI filtering at the
 * ISP level. DoH via raw-IP endpoints bypasses both DNS poisoning and DPI routing because
 * the resolver itself is reached by bare IP (no hostname to block) over TLS 443.
 *
 * Strategy per hostname:
 *  1. DoH via multiple resolvers (Cloudflare → Google → Quad9) — most reliable path
 *  2. System DNS fallback — works for hosts not blocked by ISP
 */
object DohFallbackDns : Dns {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Raw-IP endpoints on purpose: private-DNS blockers also sink well-known resolver
    // HOSTNAMES (cloudflare-dns.com, dns.google) to keep their filtering in place, but they
    // cannot hostname-block a bare IP.
    private val dohEndpoints = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
        "https://9.9.9.9/dns-query?name=%s&type=A"
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    private val CACHE_TTL_MS = 120_000L // 2 min — stale IPs break VPN tunnel routing
    /** Failed lookups retry after this TTL — lets a freshly-enabled VPN start resolving again. */
    private val NEGATIVE_TTL_MS = 30_000L
    private val negativeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun lookup(hostname: String): List<InetAddress> {
        negativeCache[hostname]?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < NEGATIVE_TTL_MS) throw UnknownHostException(hostname)
            negativeCache.remove(hostname)
        }
        cache[hostname]?.let { (ips, cachedAt) ->
            if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return ips
            cache.remove(hostname)
        }

        val lower = hostname.lowercase()

        // DoH first — most reliable for streaming domains whose system DNS is poisoned,
        // timed out, or routed through a broken VPN tunnel.
        val viaDoh = dohLookup(lower)
        if (viaDoh.isNotEmpty()) {
            cache[hostname] = viaDoh to System.currentTimeMillis()
            return viaDoh
        }

        // System DNS fallback for non-blocked hosts.
        val system = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            null
        }
        val plausible = system?.filter { isPlausible(it) }
        if (!plausible.isNullOrEmpty()) {
            cache[hostname] = plausible to System.currentTimeMillis()
            return plausible
        }

        negativeCache[hostname] = System.currentTimeMillis()
        throw UnknownHostException("DoH and system DNS both failed for $hostname")
    }

    /** Filters out ad-blocker sinkhole answers (loopback/private/CGNAT/multicast/zero). */
    private fun isPlausible(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        // Carrier-grade NAT range 100.64.0.0/10 — also used as a sinkhole by some blockers.
        if (bytes.size == 4 && bytes[0].toInt() == 100 &&
            (bytes[1].toInt() and 0xFF) in 64..127
        ) return false
        return true
    }

    private fun dohLookup(hostname: String): List<InetAddress> {
        for (template in dohEndpoints) {
            runCatching {
                val request = Request.Builder()
                    .url(template.format(java.net.URLEncoder.encode(hostname, "UTF-8")))
                    .header("accept", "application/dns-json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching
                    val body = response.body?.string() ?: return@runCatching
                    val root = org.json.JSONObject(body)
                    val answers = root.optJSONArray("Answer") ?: return@runCatching
                    val addresses = mutableListOf<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val answer = answers.optJSONObject(i) ?: continue
                        // Type 1 = A record; skip CNAME chains (type 5) — the resolver already followed them.
                        if (answer.optInt("type") != 1) continue
                        runCatching { InetAddress.getByName(answer.optString("data")) }
                            .getOrNull()
                            ?.let(addresses::add)
                    }
                    if (addresses.isNotEmpty()) return addresses
                }
            }.onFailure {
                android.util.Log.w("DohDns", "doh lookup failed for $hostname: ${it.javaClass.simpleName}")
            }
        }
        return emptyList()
    }
}
