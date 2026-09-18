package hd.kinoshka.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Cache
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class KinoApplication : Application(), ImageLoaderFactory {

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    override fun onCreate() {
        super.onCreate()

        // Диагностика (краш-хендлер + буфер событий + mpv-логи) — как можно раньше,
        // чтобы ловить даже сбои ранней инициализации.
        hd.kinoshka.app.data.diagnostics.AppDiagnostics.init(this)

        // HTTP-логи OkHttp (каждая строка запроса/ответа в logcat) — только в дебаге.
        // Должны выставиться до первого построения ApiClient-синглтонов.
        hd.kinoshka.app.data.api.ApiClient.httpLoggingEnabled = BuildConfig.DEBUG

        // Прокси для заблокированных источников (вебмастер-трио, хентай, YouTube). Глобальная
        // настройка: клиенты читают её на каждый запрос через StreamProxySelector, mpv — при
        // каждом loadfile, так что применится без перезапуска после правки в настройках.
        hd.kinoshka.app.data.source.StreamProxyConfig.proxyUrl = getSharedPreferences(
            "kinoshka_app_settings", MODE_PRIVATE
        ).getString("stream_proxy_url", null)

        // Свои источники (вариант A): реестр имён, прокси-хосты и health-провайдер
        // поднимаются из стора; дальше сохранения/удаления синкают рантайм сами.
        runCatching {
            hd.kinoshka.app.data.source.syncCustomSourceRuntime {
                hd.kinoshka.app.data.local.UserStateStore(this).getCustomSources()
            }
        }

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
        // Мосты общих (shared) резолверов к Android-механике: ddbb-харвест и события диагностики.
        // Маппим app-тип Harvested в тип моста: у каждого модуля свой data class (одинаковые поля).
        hd.kinoshka.app.data.source.DdbbHarvestBridge.harvester =
            { embedUrl, pageReferer, timeoutMs ->
                hd.kinoshka.app.data.source.WebViewStreamHarvester.harvest(embedUrl, pageReferer, timeoutMs)
                    ?.let { hd.kinoshka.app.data.source.DdbbHarvestBridge.Harvested(it.url, it.referer) }
            }
        hd.kinoshka.app.data.diagnostics.SharedDiag.sink =
            { message -> hd.kinoshka.app.data.diagnostics.AppDiagnostics.event(message) }

        // Кадры «Кадров» из видео и дисковой кэш каталога 18+ пишутся в кэш приложения.
        hd.kinoshka.app.data.source.HentaiStreamResolver.init(cacheDir)
        // Код JS-плагинов (вариант C) живёт в файлах (персистентно, не в кэше).
        hd.kinoshka.app.data.source.JsPluginStore.init(filesDir)
        // Кэш витрины каталога плагинов — там же.
        hd.kinoshka.app.data.source.PluginCatalog.init(filesDir)
        // Каталог hanime (теги/трейлер/кадры 18+) прогревается фоном со старта.
        hd.kinoshka.app.data.source.HentaiStreamResolver.warmCatalogAsync()

        // Офлайн-библиотека: подхват персистентного списка скачанных серий.
        hd.kinoshka.app.data.download.EpisodeDownloadManager.init(this)

        // Rutracker: восстановление сессии трекера для шита «Торренты» (без входа
        // раздачи Rutracker не ищутся; пароль не хранится — только кука сессии).
        hd.kinoshka.app.data.source.RutrackerResolver.attachPrefs(
            hd.kinoshka.app.data.local.KinoPrefs.from(this)
        )

        // Авточистка временных файлов по лимитам из «Память и хранилище» (фоном, no-op при выкл. тумблере).
        appScope.launch {
            runCatching { hd.kinoshka.app.data.storage.StorageUsageManager.enforceLimits(this@KinoApplication) }
        }

        // Офлайн-индекс Shikimori для импорта Anixart: ридер бандла
        // (ленивый — читается только при первом catch-up; в APK лежит
        // распакованным shiki_index.json — пайплайн ассетов сам разжал .gz).
        hd.kinoshka.app.data.source.ShikiIndexBridge.provider = {
            runCatching { assets.open("shiki_index.json").readBytes() }.getOrNull()
        }

        // Импорт Anixart: детерминированный прогресс catch-up (total>0; фаза
        // «Добираем обложки» неопределённая и сервиса не требует) поднимает
        // foreground-сервис с системным уведомлением. Гасится сервис сам —
        // по опустевшей шине, с итоговым «завершено».
        appScope.launch {
            hd.kinoshka.app.ui.screens.AnixartImportBus.flow.collect { p ->
                if (p != null && p.total > 0) {
                    hd.kinoshka.app.data.sync.AnixartImportService.start(this@KinoApplication)
                }
            }
        }

        // Initialize FastThumbnails from mpv-android-lib
        `is`.xyz.mpv.FastThumbnails.initialize(this)
    }

    override fun newImageLoader(): ImageLoader {
        // Лимит дискового кэша изображений выбирается в «Настройки → Память и хранилище»
        // (применяется после перезапуска: Coil строит синглтон один раз на процесс).
        val imageLimitMb = hd.kinoshka.app.data.storage.StorageUsageManager.getImageLimitMb(this)
        val imageHttpCache = Cache(cacheDir.resolve("http_image_cache"), 32L * 1024L * 1024L)
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
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(imageLimitMb * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}

