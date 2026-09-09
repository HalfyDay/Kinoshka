package hd.kinoshka.app.data.api

import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object ApiClient {
    @Volatile
    private var kinopoiskApiInstance: KinopoiskApi? = null
    @Volatile
    private var shikimoriApiInstance: ShikimoriApi? = null
    @Volatile
    private var anixartApiInstance: AnixartApi? = null
    private const val API_CACHE_MAX_AGE_SECONDS = 3L * 24L * 60L * 60L

    /** HTTP-коды, при которых пробуем следующее зеркало Anixart. */
    private val RETRYABLE_ANIXART_CODES = setOf(403, 404, 408, 429, 500, 502, 503, 504)

    private val REDACT_TOKEN_REGEX = Regex("([?&]token=)[^&\\s]*")

    /**
     * Выключатель HTTP-логов (OkHttp BASIC пишет каждую строку запроса/ответа в logcat —
     * десятки INFO-строк на сессию). Дефолт true, приложение гасит в релизе из
     * KinoApplication.onCreate (BuildConfig.DEBUG). Читается один раз при построении
     * клиентов-синглтонов, которые создаются уже после onCreate.
     */
    @Volatile
    var httpLoggingEnabled: Boolean = true

    private fun loggingInterceptor(redactToken: Boolean = false): HttpLoggingInterceptor {
        val logger = if (redactToken) {
            // Anixart возит токен сессии в query (?token=) — BASIC-лог печатал бы его
            // в logcat целиком. Режем до звёздочек, остальное как есть.
            HttpLoggingInterceptor.Logger { message ->
                println(REDACT_TOKEN_REGEX.replace(message, "$1***"))
            }
        } else {
            HttpLoggingInterceptor.Logger.DEFAULT
        }
        return HttpLoggingInterceptor(logger).apply {
            level = if (httpLoggingEnabled) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
    }

    private fun authInterceptor(apiKey: String) = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("X-API-KEY", apiKey)
            .build()
        chain.proceed(request)
    }

    private val shikimoriHeaderInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        chain.proceed(request)
    }

    private val shikimoriFallbackInterceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: java.io.IOException) {
            val host = request.url.host
            val nextHost = when (host) {
                "shikimori.io" -> "shikimori.one"
                "shikimori.one" -> "shikimori.me"
                else -> "shikimori.io"
            }
            val newUrl = request.url.newBuilder().host(nextHost).build()
            val newRequest = request.newBuilder().url(newUrl).build()
            chain.proceed(newRequest)
        }
    }

    private val rateLimitRetryInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (!request.method.equals("GET", ignoreCase = true)) {
            return@Interceptor chain.proceed(request)
        }

        val maxRetries = 2
        var attempt = 0
        var response = chain.proceed(request)

        while (response.code == 429 && attempt < maxRetries) {
            val retryAfterMs = response.header("Retry-After")
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?.times(1000L)
                ?: (1000L * (attempt + 1))

            response.close()
            try {
                Thread.sleep(retryAfterMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }

            attempt++
            response = chain.proceed(request)
        }

        response
    }

    fun kinopoiskApi(cacheDir: File, apiKey: String): KinopoiskApi {
        kinopoiskApiInstance?.let { return it }
        return synchronized(this) {
            kinopoiskApiInstance ?: buildApi(cacheDir, apiKey).also {
                kinopoiskApiInstance = it
            }
        }
    }

    fun shikimoriApi(cacheDir: File): ShikimoriApi {
        shikimoriApiInstance?.let { return it }
        return synchronized(this) {
            shikimoriApiInstance ?: buildShikimoriApi(cacheDir).also {
                shikimoriApiInstance = it
            }
        }
    }

    /**
     * Неофициальный API Anixart: пользовательские списки — только сеть, без
     * дискового кэша. Основной хост api-s.anixsekai.com (как в приложении и
     * AnixartJS-дефолте), дальше по цепочке зеркал. baproxy-demo.ds1nc.ru
     * (09.09.2026 мёртв — таймаут) оставлен последним шансом: если оживёт,
     * подхватится без обновления приложения.
     */
    fun anixartApi(cacheDir: File): AnixartApi {
        anixartApiInstance?.let { return it }
        return synchronized(this) {
            anixartApiInstance ?: buildAnixartApi(cacheDir).also {
                anixartApiInstance = it
            }
        }
    }

    /** Зеркала Anixart по приоритету (порядок важен и для AnixartVideoResolver). */
    val ANIXART_HOSTS = listOf(
        "api-s.anixsekai.com", // основной (приложение, AnixartJS-дефолт)
        "api.anixsekai.com",
        "api.anixart.app",
        "api.anixart.tv", // заблокирован в РФ — только через VPN/DNS
        "baproxy-demo.ds1nc.ru" // прокси, 09.09.2026 мёртв — последний шанс
    )

    private fun buildAnixartApi(cacheDir: File): AnixartApi {
        // Ротация по цепочке зеркал: IOException (DNS/таймаут) ИЛИ retryable HTTP
        // (429/5xx/403/404 — зеркала отдают их при геоблоке и рассинхроне бет).
        // POST (поиск, auth) тоже безопасно повторять: тело маленькое, идемпотентное
        // для чтения, а auth-verify идёт после редиректа крайне редко.
        val fallbackInterceptor = Interceptor { chain ->
            val request = chain.request()
            var nextHostIndex = ANIXART_HOSTS.indexOf(request.url.host).let { if (it < 0) 0 else it + 1 }
            try {
                val response = chain.proceed(request)
                if (response.code !in RETRYABLE_ANIXART_CODES || nextHostIndex >= ANIXART_HOSTS.size) {
                    return@Interceptor response
                }
                response.close()
            } catch (e: java.io.IOException) {
                if (nextHostIndex >= ANIXART_HOSTS.size) throw e
            }
            var lastError: java.io.IOException? = null
            while (nextHostIndex < ANIXART_HOSTS.size) {
                val retry = request.newBuilder()
                    .url(request.url.newBuilder().host(ANIXART_HOSTS[nextHostIndex]).build())
                    .build()
                nextHostIndex++
                try {
                    val response = chain.proceed(retry)
                    if (response.code in RETRYABLE_ANIXART_CODES && nextHostIndex < ANIXART_HOSTS.size) {
                        response.close()
                        continue
                    }
                    return@Interceptor response
                } catch (e: java.io.IOException) {
                    lastError = e
                }
            }
            throw lastError ?: java.io.IOException("All Anixart mirrors failed")
        }
        // UA официального приложения (как у референсного AnixartJS): зеркало дружелюбнее
        // отвечает клиентам, притворяющимся приложением, а не безликому okhttp.
        val anixartHeaderInterceptor = Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "AnixartApp/9.0 BETA 7-25082901 (Android 9; SDK 28; x86_64; ROG ASUS AI2201_B; ru)"
                    )
                    .header("Accept", "application/json")
                    .build()
            )
        }
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(anixartHeaderInterceptor)
            .addInterceptor(fallbackInterceptor)
            .addInterceptor(rateLimitRetryInterceptor)
            .addInterceptor(loggingInterceptor(redactToken = true))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api-s.anixsekai.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnixartApi::class.java)
    }

    private fun buildApi(cacheDir: File, apiKey: String): KinopoiskApi {
        val cacheSizeBytes = 50L * 1024L * 1024L
        val cache = Cache(cacheDir.resolve("http_api_cache"), cacheSizeBytes)

        val requestCacheInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (!request.method.equals("GET", ignoreCase = true)) {
                return@Interceptor chain.proceed(request)
            }
            val cachedRequest: Request = request.newBuilder()
                .header("Cache-Control", "public, max-age=$API_CACHE_MAX_AGE_SECONDS")
                .build()
            chain.proceed(cachedRequest)
        }

        val responseCacheInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.method.equals("GET", ignoreCase = true)) {
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=$API_CACHE_MAX_AGE_SECONDS")
                    .removeHeader("Pragma")
                    .build()
            } else {
                response
            }
        }

        val client: OkHttpClient = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(requestCacheInterceptor)
            .addInterceptor(authInterceptor(apiKey))
            .addInterceptor(rateLimitRetryInterceptor)
            .addNetworkInterceptor(responseCacheInterceptor)
            .addInterceptor(loggingInterceptor())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://kinopoiskapiunofficial.tech/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KinopoiskApi::class.java)
    }

    /**
     * GET-пути Shikimori, которые безопасно кэшировать на диск (публичный каталог).
     * Пользовательские данные (rates, whoami) и oauth — только сеть, иначе повторный
     * старт показывал бы чужие/протухшие списки. Без этих заголовков OkHttp-выдача
     * Shikimori не кэшировалась вообще — каждый рестарт бил ~16 запросами заново.
     */
    private fun shikimoriCacheMaxAge(path: String): Long? {
        val p = path.trimStart('/')
        return when {
            p.startsWith("api/animes") || p == "api/genres" -> 24L * 60L * 60L
            p == "api/calendar" || p == "api/topics" -> 15L * 60L
            p.startsWith("api/characters") -> 24L * 60L * 60L
            else -> null
        }
    }

    private fun buildShikimoriApi(cacheDir: File): ShikimoriApi {
        val cacheSizeBytes = 50L * 1024L * 1024L
        val cache = Cache(cacheDir.resolve("http_shikimori_cache"), cacheSizeBytes)

        val shikimoriCacheInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (!request.method.equals("GET", ignoreCase = true)) {
                return@Interceptor chain.proceed(request)
            }
            val maxAge = shikimoriCacheMaxAge(request.url.encodedPath) ?: return@Interceptor chain.proceed(request)
            val cachedRequest: Request = request.newBuilder()
                .header("Cache-Control", "public, max-age=$maxAge")
                .build()
            chain.proceed(cachedRequest)
        }

        val shikimoriCacheResponseInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val maxAge = if (request.method.equals("GET", ignoreCase = true)) {
                shikimoriCacheMaxAge(request.url.encodedPath)
            } else null
            if (maxAge != null) {
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=$maxAge")
                    .removeHeader("Pragma")
                    .build()
            } else {
                response
            }
        }

        val client: OkHttpClient = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(shikimoriHeaderInterceptor)
            .addInterceptor(shikimoriCacheInterceptor)
            .addInterceptor(shikimoriFallbackInterceptor)
            .addInterceptor(rateLimitRetryInterceptor)
            .addNetworkInterceptor(shikimoriCacheResponseInterceptor)
            .addInterceptor(loggingInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://shikimori.io/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ShikimoriApi::class.java)
    }
}
