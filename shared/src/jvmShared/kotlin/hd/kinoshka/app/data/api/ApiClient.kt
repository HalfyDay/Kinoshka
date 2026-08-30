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
    private const val API_CACHE_MAX_AGE_SECONDS = 3L * 24L * 60L * 60L

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
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
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

    private fun buildShikimoriApi(cacheDir: File): ShikimoriApi {
        val cacheSizeBytes = 50L * 1024L * 1024L
        val cache = Cache(cacheDir.resolve("http_shikimori_cache"), cacheSizeBytes)

        val client: OkHttpClient = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(shikimoriHeaderInterceptor)
            .addInterceptor(shikimoriFallbackInterceptor)
            .addInterceptor(rateLimitRetryInterceptor)
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
