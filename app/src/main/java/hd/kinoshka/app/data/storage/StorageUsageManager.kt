package hd.kinoshka.app.data.storage

import android.content.Context
import android.webkit.WebView
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailRepository
import coil.imageLoader
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.MediaDownloader
import hd.kinoshka.app.data.feed.FeedCacheStore
import hd.kinoshka.app.data.feed.InterestProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import java.io.File

/**
 * Замер и очистка места, занимаемого приложением — экран «Память и хранилище»
 * в настройках (модель как в Telegram: разбивка по разделам + общий график).
 *
 * Категории:
 * - offline: скачанные серии (externalFiles/offline) — главный вес, чистится отдельно с подтверждением;
 * - images: Coil (image_cache + http_image_cache);
 * - api: OkHttp-кэши каталогов (http_api_cache + http_shikimori_cache);
 * - hentai: кадры и каталог 18+ (hentai_frames + hanime_catalog.json);
 * - updates: APK обновлений (updates);
 * - thumbs: миниатюры локальных видео (filesDir/thumbnails);
 * - webview: данные WebView (app_webview);
 * - data: регенерируемые данные (кэш карточек/обложек/ленты, история плеера).
 *
 * Библиотека, история просмотров библиотеки, лайки и вкусовые веса НЕ удаляются:
 * очистка «data» трогает только то, что докачается само (details/anime/overview/
 * voiceover/search-кэши, снапшоты ленты, метаданные mpvEx).
 */
object StorageUsageManager {
    const val CAT_OFFLINE = "offline"
    const val CAT_IMAGES = "images"
    const val CAT_API = "api"
    const val CAT_HENTAI = "hentai"
    const val CAT_UPDATES = "updates"
    const val CAT_THUMBS = "thumbs"
    const val CAT_WEBVIEW = "webview"
    const val CAT_DATA = "data"

    /** Порядок показа строк в настройках. */
    val CATEGORY_ORDER = listOf(
        CAT_OFFLINE, CAT_IMAGES, CAT_API, CAT_HENTAI,
        CAT_UPDATES, CAT_THUMBS, CAT_WEBVIEW, CAT_DATA
    )

    data class CategoryUsage(val key: String, val bytes: Long)
    data class Breakdown(val categories: List<CategoryUsage>, val totalBytes: Long) {
        fun bytesOf(key: String): Long = categories.firstOrNull { it.key == key }?.bytes ?: 0L
    }

    // ---- Лимиты (kinoshka_app_settings) ----

    private const val PREFS = "kinoshka_app_settings"
    const val KEY_IMAGE_LIMIT_MB = "storage_image_limit_mb"
    const val KEY_RETENTION_DAYS = "storage_retention_days"
    const val KEY_AUTO_CLEANUP = "storage_auto_cleanup"

    const val DEFAULT_IMAGE_LIMIT_MB = 80
    /** 0 = хранить бессрочно. */
    const val DEFAULT_RETENTION_DAYS = 30

    val IMAGE_LIMIT_OPTIONS_MB = listOf(32, 80, 150, 300, 500)
    /** 0 в конце = «Бессрочно». */
    val RETENTION_OPTIONS_DAYS = listOf(7, 30, 0)

    /** Жёсткий кап миниатюр mpvEx (LRU по mtime при авточистке). */
    private const val THUMBS_MAX_BYTES = 150L * 1024L * 1024L

    fun getImageLimitMb(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_IMAGE_LIMIT_MB, DEFAULT_IMAGE_LIMIT_MB)
            .takeIf { it in IMAGE_LIMIT_OPTIONS_MB } ?: DEFAULT_IMAGE_LIMIT_MB

    fun setImageLimitMb(context: Context, mb: Int) {
        if (mb !in IMAGE_LIMIT_OPTIONS_MB) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_IMAGE_LIMIT_MB, mb).apply()
    }

    fun getRetentionDays(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            .takeIf { it in RETENTION_OPTIONS_DAYS } ?: DEFAULT_RETENTION_DAYS

    fun setRetentionDays(context: Context, days: Int) {
        if (days !in RETENTION_OPTIONS_DAYS) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    fun isAutoCleanup(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CLEANUP, true)

    fun setAutoCleanup(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CLEANUP, enabled).apply()
    }

    // ---- Замер ----

    suspend fun scan(context: Context): Breakdown = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cacheDir = app.cacheDir
        val filesDir = app.filesDir
        val dataDir = File(app.applicationInfo.dataDir)
        val map = linkedMapOf<String, Long>()
        map[CAT_OFFLINE] = dirSize(runCatching { MediaDownloader.offlineRoot(app) }.getOrNull())
        map[CAT_IMAGES] = dirSize(File(cacheDir, "image_cache")) +
            dirSize(File(cacheDir, "http_image_cache"))
        map[CAT_API] = dirSize(File(cacheDir, "http_api_cache")) +
            dirSize(File(cacheDir, "http_shikimori_cache"))
        map[CAT_HENTAI] = dirSize(File(cacheDir, "hentai_frames")) +
            File(cacheDir, "hanime_catalog.json").length().takeIf { it > 0 }.orZero()
        map[CAT_UPDATES] = dirSize(File(cacheDir, "updates"))
        map[CAT_THUMBS] = dirSize(File(filesDir, "thumbnails"))
        map[CAT_WEBVIEW] = dirSize(File(dataDir, "app_webview"))
        map[CAT_DATA] = prefsFileSize(dataDir, "kino_user_state") +
            prefsFileSize(dataDir, FeedCacheStore.PREFS_NAME) +
            prefsFileSize(dataDir, InterestProfileStore.PREFS_NAME) +
            tasteVectorSize(dataDir) +
            dirSize(File(filesDir, "diagnostics")) +
            dbSize(dataDir)
        val categories = CATEGORY_ORDER.map { CategoryUsage(it, map[it] ?: 0L) }
        Breakdown(categories, categories.sumOf { it.bytes })
    }

    // ---- Очистка ----

    /** Всё, кроме офлайн-библиотеки (она — отдельным пунктом с подтверждением). */
    suspend fun clearAllCaches(context: Context) = withContext(Dispatchers.IO) {
        CATEGORY_ORDER.filter { it != CAT_OFFLINE }.forEach { clearCategory(context, it) }
    }

    suspend fun clearCategory(context: Context, key: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cacheDir = app.cacheDir
        val filesDir = app.filesDir
        when (key) {
            CAT_OFFLINE -> EpisodeDownloadManager.clearAll()
            CAT_IMAGES -> {
                runCatching {
                    Cache(File(cacheDir, "http_image_cache"), Long.MAX_VALUE).evictAll()
                }
                runCatching { app.imageLoader.diskCache?.clear() }
                runCatching { app.imageLoader.memoryCache?.clear() }
            }
            CAT_API -> {
                runCatching { Cache(File(cacheDir, "http_api_cache"), Long.MAX_VALUE).evictAll() }
                runCatching { Cache(File(cacheDir, "http_shikimori_cache"), Long.MAX_VALUE).evictAll() }
            }
            CAT_HENTAI -> {
                deleteContents(File(cacheDir, "hentai_frames"))
                runCatching { File(cacheDir, "hanime_catalog.json").delete() }
                runCatching { hd.kinoshka.app.data.source.HentaiStreamResolver.evictMemoryCaches() }
            }
            CAT_UPDATES -> deleteContents(File(cacheDir, "updates"))
            CAT_THUMBS -> {
                runCatching {
                    val repo: ThumbnailRepository = org.koin.java.KoinJavaComponent.get(
                        ThumbnailRepository::class.java
                    )
                    repo.clearThumbnailCache()
                }
                // Пояс на всякий случай: репозиторий чистит сам, добьём остатки.
                deleteContents(File(filesDir, "thumbnails"))
            }
            CAT_WEBVIEW -> {
                withContext(Dispatchers.Main) {
                    runCatching {
                        val wv = WebView(app)
                        wv.clearCache(true)
                        wv.destroy()
                    }
                }
            }
            CAT_DATA -> clearRegenerableData(app)        }
    }

    /**
     * Только регенерируемое: кэш карточек/аниме/обзора/озвучек/поиска (kino_user_state),
     * снапшоты и «виденное» ленты, диагностика-репорты, метаданные и история mpvEx.
     * Библиотека (profiles/history_json), лайки и вкусовые веса не трогаем.
     */
    private suspend fun clearRegenerableData(app: Context) {
        runCatching { FeedCacheStore(app).clearAll() }
        runCatching { InterestProfileStore(app).clearSeenFeed() }
        runCatching {
            val prefs = app.getSharedPreferences("kino_user_state", Context.MODE_PRIVATE)
            val doomed = prefs.all.keys.filter { key ->
                key.startsWith("details_cache_") ||
                    key.startsWith("movie_voiceovers_") ||
                    key == "overview_film_cache_json" ||
                    key == "overview_anime_cache_json" ||
                    key == "shikimori_anime_cache" ||
                    key == "search_history_json"
            }
            if (doomed.isNotEmpty()) {
                prefs.edit().apply { doomed.forEach { remove(it) } }.apply()
            }
        }
        // Репорты диагностики (crash-* оставляем — они нужны для отчёта о проблеме).
        runCatching {
            File(app.filesDir, "diagnostics").listFiles { f -> f.isFile && f.name.startsWith("report-") }
                ?.forEach { it.delete() }
        }
        runCatching {
            val db: MpvExDatabase = org.koin.java.KoinJavaComponent.get(
                MpvExDatabase::class.java
            )
            runCatching { db.videoDataDao().clearAllPlaybackStates() }
            runCatching { db.recentlyPlayedDao().clearAll() }
            runCatching { db.videoMetadataDao().clearAll() }
        }
    }

    // ---- Авточистка на старте ----

    /**
     * Вызывается из Application.onCreate (фоном). При выключенном тумблере — no-op.
     * Режет: файлы старше срока хранения (кадры 18+, APK, репорты), image_cache
     * до выбранного лимита и thumbnails до жёсткого капа — всё LRU по mtime.
     */
    suspend fun enforceLimits(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!isAutoCleanup(app)) return@withContext
        val retentionDays = getRetentionDays(app)
        if (retentionDays > 0) {
            val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
            deleteOlderThan(File(app.cacheDir, "hentai_frames"), cutoff)
            deleteOlderThan(File(app.cacheDir, "updates"), cutoff)
            runCatching {
                File(app.filesDir, "diagnostics")
                    .listFiles { f -> f.isFile && f.name.startsWith("report-") && f.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            }
            val catalog = File(app.cacheDir, "hanime_catalog.json")
            runCatching { if (catalog.isFile && catalog.lastModified() < cutoff) catalog.delete() }
        }
        trimDirLru(File(app.cacheDir, "image_cache"), getImageLimitMb(app) * 1024L * 1024L)
        trimDirLru(File(app.filesDir, "thumbnails"), THUMBS_MAX_BYTES)
    }

    // ---- Файловые помощники ----

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length().coerceAtLeast(0L)
        var total = 0L
        dir.listFiles()?.forEach { child ->
            total += if (child.isDirectory) dirSize(child) else child.length().coerceAtLeast(0L)
        }
        return total
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun prefsFileSize(dataDir: File, prefsName: String): Long {
        val xml = File(dataDir, "shared_prefs/$prefsName.xml")
        val bak = File(dataDir, "shared_prefs/$prefsName.xml.bak")
        return (if (xml.isFile) xml.length() else 0L) + (if (bak.isFile) bak.length() else 0L)
    }

    private fun tasteVectorSize(dataDir: File): Long {
        val dir = File(dataDir, "shared_prefs")
        if (!dir.isDirectory) return 0L
        return dir.listFiles { f -> f.isFile && f.name.startsWith("feed_taste_vector") }
            ?.sumOf { it.length().coerceAtLeast(0L) } ?: 0L
    }

    private fun dbSize(dataDir: File): Long = dirSize(File(dataDir, "databases"))

    private fun deleteContents(dir: File) {
        if (!dir.exists()) return
        if (dir.isFile) {
            runCatching { dir.delete() }
            return
        }
        dir.listFiles()?.forEach { child ->
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }

    private fun deleteOlderThan(dir: File, cutoffMs: Long) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            runCatching {
                if (child.lastModified() < cutoffMs) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
        }
    }

    private fun trimDirLru(dir: File, maxBytes: Long) {
        if (maxBytes <= 0 || !dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length().coerceAtLeast(0L) }
        for (file in files) {
            if (total <= maxBytes) break
            val size = file.length().coerceAtLeast(0L)
            if (runCatching { file.delete() }.getOrDefault(false)) total -= size
        }
    }

}
