package hd.kinoshka.app.utils

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS resolver with a DNS-over-HTTPS fallback.
 *
 * Private-DNS ad blockers (AdGuard, NextDNS...) sink whole streaming domains — kodik.info,
 * aniqit.com and friends resolve to NXDOMAIN system-wide, which instantly killed find-player
 * scrapes and HLS extraction for anyone running them. System lookup is tried first; on
 * UnknownHost we re-ask via DoH (Cloudflare, then Google), whose resolvers do not apply those
 * blocklists. WebView traffic cannot use this — it keeps the system stack.
 */
object DohFallbackDns : Dns {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Raw-IP endpoints on purpose: private-DNS blockers also sink well-known resolver
    // HOSTNAMES (cloudflare-dns.com, dns.google) to keep their filtering in place, but they
    // cannot hostname-block a bare IP. Both certs are valid for these IPs.
    private val dohEndpoints = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A"
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<InetAddress>>()
    private val negativeCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Hosts that private-DNS ad blockers poison (they answer with a sinkhole IP instead of
    // NXDOMAIN, so a successful system lookup means nothing). For these we go straight to DoH,
    // whose answers come from resolvers that don't apply such blocklists.
    private val forcedDohHosts = setOf(
        "kodik.info", "aniqit.com", "kodik.cc", "kodikplayer.com", "kodi.my",
        "kodikapi.com", "kodik-api.com", "vsh.my", "w.kdkonl.com"
    )

    override fun lookup(hostname: String): List<InetAddress> {
        if (negativeCache.contains(hostname)) throw UnknownHostException(hostname)
        cache[hostname]?.let { return it }

        val lower = hostname.lowercase()
        if (forcedDohHosts.any { lower == it || lower.endsWith(".$it") }) {
            val viaDoh = dohLookup(lower)
            if (viaDoh.isNotEmpty()) {
                cache[hostname] = viaDoh
                return viaDoh
            }
        }

        val system = try {
            Dns.SYSTEM.lookup(hostname).also { if (it.isNotEmpty()) cache[hostname] = it }
        } catch (e: UnknownHostException) {
            null
        }
        if (!system.isNullOrEmpty()) return system

        val viaDoh = dohLookup(hostname)
        if (viaDoh.isNotEmpty()) {
            cache[hostname] = viaDoh
            return viaDoh
        }
        negativeCache.add(hostname)
        throw UnknownHostException("Broken system DNS and DoH fallback failed for $hostname")
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
