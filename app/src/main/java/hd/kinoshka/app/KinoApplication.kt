package hd.kinoshka.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class KinoApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // Диагностика (краш-хендлер + буфер событий + mpv-логи) — как можно раньше,
        // чтобы ловить даже сбои ранней инициализации.
        hd.kinoshka.app.data.diagnostics.AppDiagnostics.init(this)

        // Initialize Koin for mpvEx
        startKoin {
            androidContext(this@KinoApplication)
            modules(
                app.marlboroadvance.mpvex.di.PreferencesModule,
                app.marlboroadvance.mpvex.di.DatabaseModule,
                app.marlboroadvance.mpvex.di.FileManagerModule,
                app.marlboroadvance.mpvex.di.domainModule,
            )
        }

        // Headless-WebView stream extractor needs an application context.
        hd.kinoshka.app.data.source.WebViewStreamHarvester.init(this)

        // Кадры «Кадров» из видео и дисковой кэш каталога 18+ пишутся в кэш приложения.
        hd.kinoshka.app.data.source.HentaiStreamResolver.init(cacheDir)
        // Каталог hanime (теги/трейлер/кадры 18+) прогревается фоном со старта.
        hd.kinoshka.app.data.source.HentaiStreamResolver.warmCatalogAsync()

        // Офлайн-библиотека: подхват персистентного списка скачанных серий.
        hd.kinoshka.app.data.download.EpisodeDownloadManager.init(this)

        // Initialize FastThumbnails from mpv-android-lib
        `is`.xyz.mpv.FastThumbnails.initialize(this)
    }

    override fun newImageLoader(): ImageLoader {
        val imageHttpCache = Cache(cacheDir.resolve("http_image_cache"), 80L * 1024L * 1024L)
        val imageClient = OkHttpClient.Builder()
            // Тот же DNS, что у стрим-резолверов: на РФ-сетях системный DNS отравлен
            // для части хостов с картинками (зеркала каталогов, shikimori-CDN) — без DoH
            // найденные резолвером кадры/постеры не скачивались самим загрузчиком.
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
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

