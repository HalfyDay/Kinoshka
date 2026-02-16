package hd.kinoshka.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Cache
import okhttp3.OkHttpClient

class KinoApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val imageHttpCache = Cache(cacheDir.resolve("http_image_cache"), 80L * 1024L * 1024L)
        val imageClient = OkHttpClient.Builder()
            .cache(imageHttpCache)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(120L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
