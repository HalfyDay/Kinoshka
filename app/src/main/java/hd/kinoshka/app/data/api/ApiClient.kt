package hd.kinoshka.app.data.api

import android.content.Context
import hd.kinoshka.app.BuildConfig
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    @Volatile
    private var kinopoiskApiInstance: KinopoiskApi? = null
    private const val API_CACHE_MAX_AGE_SECONDS = 3L * 24L * 60L * 60L

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("X-API-KEY", BuildConfig.KP_API_KEY)
            .build()
        chain.proceed(request)
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

    fun kinopoiskApi(context: Context): KinopoiskApi {
        kinopoiskApiInstance?.let { return it }
        return synchronized(this) {
            kinopoiskApiInstance ?: buildApi(context.applicationContext).also {
                kinopoiskApiInstance = it
            }
        }
    }

    private fun buildApi(context: Context): KinopoiskApi {
        val cacheSizeBytes = 50L * 1024L * 1024L
        val cache = Cache(context.cacheDir.resolve("http_api_cache"), cacheSizeBytes)

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
            .addInterceptor(authInterceptor)
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
}
