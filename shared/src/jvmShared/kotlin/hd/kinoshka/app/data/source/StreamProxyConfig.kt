package hd.kinoshka.app.data.source

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Единый пользовательский прокси для источников, недоступных напрямую у части провайдеров
 * (подтверждено логами: svetacdn — connect timeout, delivembd/voidboost — молчаливый обрыв
 * после рукопожатия, cdnmovies — HTTP 403):
 *
 *  - вебмастер-трио (VideoCDN / Collaps / Voidboost),
 *  - хентай-каталоги (AllHentai / HentaiDream / HentaiZ / Hanime / Oppai),
 *  - YouTube-трейлеры (InnerTube-запросы + googlevideo-поток).
 *
 * Формат: `http://host:port`, `http://user:pass@host:port`, `https://…`, `socks5://host:port`
 * или голый `host:port` (трактуется как http). Пусто — прокси выключен.
 *
 * Хосты вне списка (turbo/kodik/kinopoisk и их CDN) ходят напрямую — гонять их через прокси
 * значит замедлить то, что и так работает. Проводка в два стека: OkHttp-клиенты ставят
 * [StreamProxySelector] (прокси выбирается по хосту каждого запроса), mpv получает http-proxy
 * строку из [mpvProxyFor] перед loadfile. Проверено живьём: api.delivembd.ws через
 * http-прокси отвечает 200 там, где напрямую — таймаут.
 */
object StreamProxyConfig {
    @Volatile
    var proxyUrl: String? = null

    // Суффиксы хостов, которые ходят только через прокси. voidboost.cc мёртв по DNS, оставлен
    // на случай оживления; sibnet.ru не включён — российский хост, не блокируется.
    private val PROXIED_HOST_SUFFIXES = listOf(
        // вебмастер-источники
        "delivembd.ws", "voidboost.net", "voidboost.cc", "svetacdn.in", "cdnmovies.net",
        // хентай-каталоги
        "allhentai.fun", "freeanimehentai.net", "hanime.tv", "hanime1.me", "hentaidream.fun",
        "oppai.stream", "hentaiiz.org",
        // YouTube-трейлеры
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com",
    )

    /** java.net.Proxy для запроса по [url] (okHttp-семейство), null — напрямую. */
    fun okHttpProxy(url: String): Proxy? {
        val raw = proxyUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        if (!needsProxy(url)) return null
        return parseProxy(raw)
    }

    /** Значение mpv-свойства http-proxy для потока [url], "" — напрямую (сброс предыдущего). */
    fun mpvProxyFor(url: String): String {
        val raw = proxyUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: return ""
        return if (needsProxy(url)) raw else ""
    }

    fun needsProxy(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return PROXIED_HOST_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }
    }

    /** Public for unit tests. `http(s)://…` → HTTP-прокси, `socks5://…`/`socks://…` → SOCKS,
     *  голый `host:port` → HTTP. user:pass@ отрезается (креды берёт okHttpProxyAuthenticator). */
    fun parseProxy(raw: String): Proxy? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val isSocks = trimmed.startsWith("socks5://", true) || trimmed.startsWith("socks://", true)
        val authority = when {
            isSocks -> trimmed.substringAfter("://")
            trimmed.startsWith("http://", true) -> trimmed.substring(7)
            trimmed.startsWith("https://", true) -> trimmed.substring(8)
            else -> trimmed
        }.substringBefore('/').substringBefore('?').substringAfterLast('@')
        val port = authority.substringAfterLast(':', "").toIntOrNull()
        val host = if (port != null) authority.substringBeforeLast(':') else authority
        if (host.isEmpty()) return null
        val address = InetSocketAddress.createUnresolved(host, port ?: 80)
        return Proxy(if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP, address)
    }

    /** Proxy-Authorization из user:pass@ части настройки; null без кред. */
    fun okHttpProxyAuthenticator(): okhttp3.Authenticator = okhttp3.Authenticator { _, response ->
        val cred = proxyUrl?.trim()
            ?.takeIf { it.contains('@') }
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.substringBeforeLast('@')
            ?.takeIf { it.contains(':') }
            ?.split(':', limit = 2)
            ?: return@Authenticator null
        response.request.newBuilder()
            .header("Proxy-Authorization", okhttp3.Credentials.basic(cred[0], cred[1]))
            .build()
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: url.substringAfter("://", "").substringBefore('/').substringBefore(':')
                .takeIf { it.isNotEmpty() && it.contains('.') }
}

/**
 * OkHttp-селектор: запросы к блокируемым хостам идут через настроенный прокси, остальные —
 * напрямую, в рамках одного клиента. Ставится через `.proxySelector(...)`.
 */
class StreamProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val url = uri?.toString() ?: return listOf(Proxy.NO_PROXY)
        return StreamProxyConfig.okHttpProxy(url)?.let { listOf(it) } ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // Следующий запрос пере-выберет прокси; лог писать некуда — клиенты без KLog-тега.
    }
}
