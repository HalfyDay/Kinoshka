package hd.kinoshka.app.data.download

import android.content.Context
import android.util.Log
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Офлайн-библиотека и очередь скачивания серий. Единая точка для UI (щёлк/прогресс/отмена),
 * плеера (local-first проигрывание) и хранилища (SharedPreferences+Gson, паттерн UserStateStore).
 *
 * Очередь строго последовательная: одна серия качается за раз (порядок = порядок постановки).
 * Активные задачи живут в памяти процесса; завершённые — персистентны. При перезапуске
 * процесса недокачанные эпизоды просто пропадают из очереди (не ломают библиотеку).
 */
object EpisodeDownloadManager {
    private const val TAG = "EpisodeDownloadManager"
    private const val PREFS = "kino_offline_downloads"
    private const val LIBRARY_KEY = "library_json"

    private lateinit var appContext: Context
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTaskState>> = _tasks.asStateFlow()

    private val _library = MutableStateFlow<List<OfflineEpisode>>(emptyList())
    val library: StateFlow<List<OfflineEpisode>> = _library.asStateFlow()

    private val pending = MutableStateFlow<List<EpisodeDownloadRequest>>(emptyList())
    /** Исходные запросы упавших задач — для кнопки «Повторить». */
    private val failedRequests = MutableStateFlow<Map<String, EpisodeDownloadRequest>>(emptyMap())
    private val workerMutex = Mutex()
    private val queueMutex = Mutex()
    @Volatile private var workerActive = false
    @Volatile private var currentKey: String? = null
    @Volatile private var currentJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _library.value = loadLibrary()
    }

    // ------------------------------------------------------------------
    // Запрос на скачивание
    // ------------------------------------------------------------------

    /**
     * Единица очереди. [resolve] лениво достаёт играбельную ссылку в момент, когда до задачи
     * дошла очередь (резолв HLS — сетевая работа, держать ссылки заранее нельзя: подписи живут часы).
     */
    data class EpisodeDownloadRequest(
        val itemKey: String,
        val title: String,
        val source: String,
        val translationId: String,
        val translationTitle: String,
        val episodeNumber: Int,
        val episodeLabel: String,
        val resolve: suspend () -> MediaDownloader.MediaSource?
    )

    // ------------------------------------------------------------------
    // Постановка в очередь / отмена
    // ------------------------------------------------------------------

    /** Ставит серию в очередь. Возвращает true, если задача реально поставлена. */
    fun enqueue(request: EpisodeDownloadRequest): Boolean {
        if (!this::appContext.isInitialized) return false
        val key = offlineKey(request.itemKey, request.source, request.translationId, request.episodeNumber)
        if (findLibraryEntry(key) != null) return false
        scope.launch {
            queueMutex.withLock {
                if (findLibraryEntry(key) != null) return@withLock
                // Упавшую задачу можно поставить заново — она заменяется новой.
                if (_tasks.value[key]?.let { it.phase != DownloadPhase.FAILED } == true) return@withLock
                if (pending.value.any { itKey(it) == key }) return@withLock
                _tasks.value = _tasks.value - key
                failedRequests.value = failedRequests.value - key
                pending.value = pending.value + request
                _tasks.value = _tasks.value + (key to taskState(request, DownloadPhase.QUEUED))
                ensureWorker()
            }
        }
        return true
    }

    /** Ставит список серий в очередь в исходном порядке (серии подряд идут по номеру). */
    fun enqueueAll(requests: List<EpisodeDownloadRequest>) {
        requests.forEach { enqueue(it) }
    }

    fun cancel(key: String) {
        scope.launch {
            queueMutex.withLock {
                pending.value = pending.value.filter { itKey(it) != key }
                if (currentKey == key) {
                    currentJob?.cancel()
                } else {
                    _tasks.value = _tasks.value - key
                    failedRequests.value = failedRequests.value - key
                }
            }
        }
    }

    /** Повтор упавшей задачи тем же запросом. */
    fun retry(key: String) {
        val request = failedRequests.value[key] ?: return
        enqueue(request)
    }

    /** Убирает упавшую задачу из списка (файлы не трогает — их нет). */
    fun dismissFailed(key: String) {
        scope.launch {
            queueMutex.withLock {
                val task = _tasks.value[key] ?: return@withLock
                if (task.phase == DownloadPhase.FAILED) {
                    _tasks.value = _tasks.value - key
                    failedRequests.value = failedRequests.value - key
                }
            }
        }
    }

    private fun itKey(request: EpisodeDownloadRequest): String =
        offlineKey(request.itemKey, request.source, request.translationId, request.episodeNumber)

    private fun taskState(request: EpisodeDownloadRequest, phase: DownloadPhase) = DownloadTaskState(
        key = itKey(request),
        itemKey = request.itemKey,
        title = request.title,
        source = request.source,
        translationId = request.translationId,
        translationTitle = request.translationTitle,
        episodeNumber = request.episodeNumber,
        episodeLabel = request.episodeLabel,
        phase = phase
    )

    private fun ensureWorker() {
        if (workerActive) return
        workerActive = true
        scope.launch {
            try {
                while (true) {
                    val next = queueMutex.withLock {
                        val head = pending.value.firstOrNull()
                        if (head == null) {
                            workerActive = false
                            null
                        } else {
                            pending.value = pending.value - head
                            head
                        }
                    } ?: return@launch
                    runTask(next)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                workerActive = false
            } catch (e: Exception) {
                Log.e(TAG, "worker crashed", e)
                workerActive = false
            }
        }
    }

    private suspend fun runTask(request: EpisodeDownloadRequest) {
        val key = itKey(request)
        currentKey = key
        // Foreground-сервис держит процесс и показывает прогресс-уведомление, пока очередь жива.
        DownloadForegroundService.start(appContext)
        val job = scope.launch {
            val myJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            fun update(transform: (DownloadTaskState) -> DownloadTaskState) {
                // Прогресс приходит из блокирующего цикла MediaDownloader: ensureActive здесь
                // превращает cancel() в немедленный CancellationException внутри скачивания.
                myJob?.ensureActive()
                _tasks.value = _tasks.value + (key to (transform(_tasks.value[key] ?: taskState(request, DownloadPhase.RESOLVING))))
            }

            update { it.copy(phase = DownloadPhase.RESOLVING) }
            val media = try {
                request.resolve() ?: throw MediaDownloader.DownloadException("Не удалось получить ссылку на видео")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resolve failed for $key: ${e.message}")
                failedRequests.value = failedRequests.value + (key to request)
                update { it.copy(phase = DownloadPhase.FAILED, error = e.message ?: "Ошибка резолва") }
                currentKey = null
                return@launch
            }

            update { it.copy(phase = DownloadPhase.DOWNLOADING) }
            try {
                val dir = MediaDownloader.episodeDir(
                    appContext, request.itemKey, request.source, request.translationId, request.episodeNumber
                )
                // Скорость — EMA по дельтам bytesDone (direct обновляет по 64КБ-чанкам, HLS — по
                // завершённым сегментам). Сброс дельт (перезапуск попытки) обнуляет замер.
                var speedEma = 0.0
                var sampleMs = 0L
                var sampleBytes = 0L
                val file = MediaDownloader.download(media, dir, "episode") { progress ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (sampleMs > 0 && now > sampleMs) {
                        val delta = progress.bytesDone - sampleBytes
                        if (delta >= 0) {
                            val inst = delta * 1000.0 / (now - sampleMs)
                            speedEma = if (speedEma == 0.0) inst else 0.35 * inst + 0.65 * speedEma
                        } else {
                            speedEma = 0.0
                        }
                    }
                    sampleMs = now
                    sampleBytes = progress.bytesDone
                    // Для HLS сервер не отдаёт общий размер: оцениваем по среднему сегменту.
                    val (total, estimated) = when {
                        progress.bytesTotal > 0 -> progress.bytesTotal to false
                        progress.segmentsDone > 0 && progress.segmentsTotal > 0 ->
                            (progress.bytesDone * progress.segmentsTotal / progress.segmentsDone) to true
                        else -> -1L to false
                    }
                    update {
                        it.copy(
                            phase = DownloadPhase.DOWNLOADING,
                            bytesDone = progress.bytesDone,
                            bytesTotal = total,
                            sizeEstimated = estimated,
                            segmentsDone = progress.segmentsDone,
                            segmentsTotal = progress.segmentsTotal,
                            speedBytesPerSec = speedEma.toLong()
                        )
                    }
                }
                val entry = OfflineEpisode(
                    itemKey = request.itemKey,
                    title = request.title,
                    source = request.source,
                    translationId = request.translationId,
                    translationTitle = request.translationTitle,
                    episodeNumber = request.episodeNumber,
                    episodeLabel = request.episodeLabel,
                    dirPath = file.dirPath,
                    filePath = file.filePath,
                    sizeBytes = file.sizeBytes,
                    downloadedAt = System.currentTimeMillis(),
                    isHls = file.isHls
                )
                _tasks.value = _tasks.value - key
                _library.value = (_library.value.filter { it.key != key } + entry).sortedBy { it.key }
                saveLibrary(_library.value)
                Log.i(TAG, "downloaded $key (${formatBytes(file.sizeBytes)})")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Отмена: подчистить недокачанный каталог и убрать задачу.
                runCatching {
                    MediaDownloader.episodeDir(appContext, request.itemKey, request.source, request.translationId, request.episodeNumber)
                        .deleteRecursively()
                }
                _tasks.value = _tasks.value - key
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed for $key: ${e.message}")
                failedRequests.value = failedRequests.value + (key to request)
                update { it.copy(phase = DownloadPhase.FAILED, error = e.message ?: "Ошибка скачивания") }
            }
            currentKey = null
        }
        currentJob = job
        job.join()
    }

    // ------------------------------------------------------------------
    // Удаление
    // ------------------------------------------------------------------

    fun delete(key: String) {
        val entry = findLibraryEntry(key) ?: return
        runCatching { java.io.File(entry.dirPath).deleteRecursively() }
        _library.value = _library.value.filter { it.key != key }
        saveLibrary(_library.value)
    }

    /** Удаляет все серии тайтла. */
    fun deleteItem(itemKey: String) {
        val doomed = _library.value.filter { it.itemKey == itemKey }
        if (doomed.isEmpty()) return
        doomed.forEach { runCatching { java.io.File(it.dirPath).deleteRecursively() } }
        _library.value = _library.value.filter { it.itemKey != itemKey }
        saveLibrary(_library.value)
    }

    fun clearAll() {
        runCatching { MediaDownloader.offlineRoot(appContext).deleteRecursively() }
        _library.value = emptyList()
        saveLibrary(_library.value)
    }

    // ------------------------------------------------------------------
    // Поиск
    // ------------------------------------------------------------------

    fun findLibraryEntry(key: String): OfflineEpisode? = _library.value.firstOrNull { it.key == key }

    /**
     * Local-first поиск скачанной серии. Пробует оба ключа тайтла (shikimori/kinopoisk),
     * потому что один и тот же тайтл открывается с разными ключами из разных мест.
     */
    fun findLocal(
        shikimoriId: Int,
        kinopoiskId: Int,
        source: String,
        translationId: String,
        episodeNumber: Int
    ): OfflineEpisode? {
        val candidates = buildList {
            if (shikimoriId > 0) add(animeItemKey(shikimoriId, 0))
            if (kinopoiskId > 0) add(animeItemKey(0, kinopoiskId))
        }
        candidates.forEach { itemKey ->
            val key = offlineKey(itemKey, source, translationId, episodeNumber)
            findLibraryEntry(key)?.let { return it }
        }
        return null
    }

    /** Скачанные серии тайтла. */
    fun offlineEpisodesFor(itemKey: String): List<OfflineEpisode> =
        _library.value.filter { it.itemKey == itemKey }

    /**
     * Офлайн-озвучки для пикера: группирует скачанные серии в FlatTranslation-ы с исходными
     * (source, translationId) — local-first резолв подхватывает их без префиксов. Показываются
     * всегда, даже когда сеть лежит. Заголовок помечается «(офлайн)», чтобы в списке озвучек
     * и в шите плеера было видно, что дорожка играет из скачивания.
     */
    fun offlineTranslations(itemKey: String, fallbackTitle: String): List<FlatTranslation> {
        val episodes = offlineEpisodesFor(itemKey)
        if (episodes.isEmpty()) return emptyList()
        return episodes
            .groupBy { it.source to it.translationId }
            .map { (groupKey, eps) ->
                val (source, translationId) = groupKey
                val sorted = eps.sortedBy { it.episodeNumber }
                val baseTitle = sorted.first().translationTitle.ifBlank { "Офлайн" }
                FlatTranslation(
                    source = runCatching { AnimeSourceType.valueOf(source) }
                        .getOrElse { AnimeSourceType.KODIK },
                    translationId = translationId,
                    title = if (baseTitle.contains("офлайн", ignoreCase = true)) baseTitle else "$baseTitle (офлайн)",
                    type = "voice",
                    episodes = sorted.map { ep ->
                        AnimeEpisode(
                            number = ep.episodeNumber,
                            title = ep.episodeLabel,
                            link = ep.filePath
                        )
                    }
                )
            }
    }

    /** Суммарный размер библиотеки. */
    fun totalSizeBytes(): Long = _library.value.sumOf { it.sizeBytes }

    // ------------------------------------------------------------------
    // Персистентность
    // ------------------------------------------------------------------

    private fun loadLibrary(): List<OfflineEpisode> {
        if (!this::appContext.isInitialized) return emptyList()
        return runCatching {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(LIBRARY_KEY, null) ?: return emptyList()
            val type = object : TypeToken<List<OfflineEpisode>>() {}.type
            gson.fromJson<List<OfflineEpisode>>(json, type).orEmpty()
        }.onFailure { Log.w(TAG, "library load failed", it) }.getOrDefault(emptyList())
    }

    private fun saveLibrary(list: List<OfflineEpisode>) {
        if (!this::appContext.isInitialized) return
        runCatching {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(LIBRARY_KEY, gson.toJson(list))
                .apply()
        }.onFailure { Log.w(TAG, "library save failed", it) }
    }
}
