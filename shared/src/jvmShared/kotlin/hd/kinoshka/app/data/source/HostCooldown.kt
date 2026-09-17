package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog

/**
 * Короткая память о мёртвых хостах: connect/DNS-таймаут помечает хост на [COOLDOWN_MS],
 * повторные резолвы в это окно пропускаются сразу вместо сжигания таймаутов.
 * Без этого каждое открытие пикера ждало svetacdn/delivembd/voidboost/rezka по 6–10 c
 * на запрос (до минуты суммарно на телефоне без VPN).
 *
 * Только для резолверов: ручная проверка на странице «Источники» идёт мимо cooldown
 * (у неё свой таймаут и явный запуск). Успешный ответ хоста снимает пометку сразу.
 */
object HostCooldown {
    private const val TAG = "HostCooldown"

    /** Окно пометки (можно крутить из тестов). Public for unit tests. */
    var cooldownMs: Long = 3 * 60_000L

    private val downSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun shouldSkip(host: String): Boolean {
        val since = downSince[host] ?: return false
        if (System.currentTimeMillis() - since > cooldownMs) {
            downSince.remove(host, since)
            return false
        }
        return true
    }

    fun recordFailure(host: String) {
        if (downSince.putIfAbsent(host, System.currentTimeMillis()) == null) {
            KLog.i(TAG, "$host marked down for ${cooldownMs / 1000}s")
        }
    }

    fun recordSuccess(host: String) {
        downSince.remove(host)
    }

    /** True для сетевых (а не HTTP-) ошибок: таймаут коннекта/чтения, DNS, обрыв. */
    fun isConnectivityFailure(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.net.SocketTimeoutException ||
                cause is java.net.UnknownHostException ||
                cause is java.net.ConnectException ||
                cause is java.net.NoRouteToHostException
            ) return true
            cause = cause.cause
        }
        return false
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: url.substringAfter("://", "").substringBefore('/').substringBefore(':')
                .takeIf { it.isNotEmpty() }.orEmpty()
}
