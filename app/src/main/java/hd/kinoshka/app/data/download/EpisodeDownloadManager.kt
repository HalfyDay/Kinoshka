package hd.kinoshka.app.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Запросы одиночно приостановленных задач (кнопка паузы на каждой загрузке). */
    private val pausedRequests = MutableStateFlow<Map<String, EpisodeDownloadRequest>>(emptyMap())
    private val workerMutex = Mutex()
    private val queueMutex = Mutex()
    @Volatile private var workerActive = false
    @Volatile private var currentKey: String? = null
    @Volatile private var currentJob: Job? = null
    @Volatile private var currentRequest: EpisodeDownloadRequest? = null
    /** Глобальная пауза очереди («Остановить все»): воркер не берёт новые задачи. */
    @Volatile private var pausedAll = false
    private val _paused = MutableStateFlow(false)
    /** true — очередь остановлена кнопкой «Остановить все», ждёт «Продолжить все». */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Повторные попытки внутри задачи: обрыв сети не роняет загрузку сразу. */
    private const val MAX_ATTEMPTS = 5
    private const val BASE_RETRY_DELAY_MS = 2_000L
    private const val MAX_RETRY_DELAY_MS = 15_000L

    /** Ключи, по которым уведомление о завершении уже показано (одно на задачу). */
    private val notifiedDone = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var networkWatching = false

    fun init(context: Context) {
        appContext = context.applicationContext
        _library.value = loadLibrary()
        watchConnectivity()
    }

    // ------------------------------------------------------------------
    // Сеть: авто-retry при восстановлении
    // ------------------------------------------------------------------

    /** Слушатель смены connectivity: при появлении сети упавшие задачи встают в очередь заново. */
    private fun watchConnectivity() {
        if (networkWatching) return
        networkWatching = true
        runCatching {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        val failed = failedRequests.value.keys.toList()
                        if (failed.isEmpty()) return@launch
                        Log.i(TAG, "network back, retrying ${failed.size} failed")
                        failed.forEach { retry(it) }
                    }
                }
            })
        }.onFailure { Log.w(TAG, "network callback failed: ${it.message}") }
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
        /** Обложка тайтла для экрана загрузок; null — подтянется из аниме-кэша/плейсхолдер. */
        val posterUrl: String? = null,
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
                notifiedDone.remove(key)
                pending.value = pending.value + request
                // Новая задача во время глобальной паузы сразу встаёт «на паузу».
                val phase = if (pausedAll) DownloadPhase.PAUSED else DownloadPhase.QUEUED
                _tasks.value = _tasks.value + (key to taskState(request, phase))
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
            val toWipe = queueMutex.withLock {
                val isCurrent = currentKey == key
                // Запрос из очереди/одиночной паузы — для чистки недокачанного каталога.
                // Текущую чистит handler отмены в runTask, её пропускаем (гонка с перепостановкой).
                val req = if (isCurrent) null
                else pending.value.firstOrNull { itKey(it) == key } ?: pausedRequests.value[key]
                pending.value = pending.value.filter { itKey(it) != key }
                pausedRequests.value = pausedRequests.value - key
                if (isCurrent) {
                    currentJob?.cancel()
                } else {
                    _tasks.value = _tasks.value - key
                    failedRequests.value = failedRequests.value - key
                    notifiedDone.remove(key)
                }
                // Последняя задача ушла одиночными отменами — снять зависшую паузу.
                resetPauseIfIdle()
                req
            }
            toWipe?.let { req ->
                runCatching {
                    MediaDownloader.episodeDir(
                        appContext, req.itemKey, req.source, req.translationId, req.episodeNumber
                    ).deleteRecursively()
                }
            }
        }
    }

    /**
     * Приостанавливает одну загрузку, сохраняя недокачанные файлы для докачки:
     * очередь продолжает качать остальные. Повторный вызов — no-op.
     */
    fun pause(key: String) {
        scope.launch {
            queueMutex.withLock {
                val task = _tasks.value[key] ?: return@withLock
                if (task.phase != DownloadPhase.QUEUED &&
                    task.phase != DownloadPhase.RESOLVING &&
                    task.phase != DownloadPhase.DOWNLOADING
                ) return@withLock
                if (pausedRequests.value.containsKey(key)) return@withLock
                if (currentKey == key) {
                    val req = currentRequest ?: return@withLock
                    pausedRequests.value = pausedRequests.value + (key to req)
                    _tasks.value = _tasks.value + (key to task.copy(phase = DownloadPhase.PAUSED))
                    currentJob?.cancel()
                } else {
                    val req = pending.value.firstOrNull { itKey(it) == key } ?: return@withLock
                    pending.value = pending.value.filter { itKey(it) != key }
                    pausedRequests.value = pausedRequests.value + (key to req)
                    _tasks.value = _tasks.value + (key to task.copy(phase = DownloadPhase.PAUSED))
                }
            }
        }
    }

    /**
     * Продолжает одиночно приостановленную задачу: встаёт в конец очереди.
     * Во время глобальной паузы («Остановить все») остаётся на паузе до «Продолжить все».
     */
    fun resume(key: String) {
        scope.launch {
            queueMutex.withLock {
                val req = pausedRequests.value[key] ?: return@withLock
                pausedRequests.value = pausedRequests.value - key
                if (pending.value.none { itKey(it) == key }) {
                    pending.value = pending.value + req
                }
                val phase = if (pausedAll) DownloadPhase.PAUSED else DownloadPhase.QUEUED
                _tasks.value = _tasks.value + (key to (
                    _tasks.value[key]?.copy(phase = phase, error = null)
                        ?: taskState(req, phase)
                    ))
                notifiedDone.remove(key)
                ensureWorker()
            }
        }
    }

    /**
     * Отменяет все активные загрузки: чистит очередь, снимает упавшие задачи,
     * останавливает текущую и удаляет недокачанные каталоги. Скачанная библиотека не трогается.
     */
    fun cancelAll() {
        scope.launch {
            val toWipe: List<EpisodeDownloadRequest>
            queueMutex.withLock {
                toWipe = pending.value.toList() +
                    failedRequests.value.values.toList() +
                    pausedRequests.value.values.toList()
                val cancelledKeys = _tasks.value.keys.toList()
                val curKey = currentKey
                pending.value = emptyList()
                failedRequests.value = emptyMap()
                pausedRequests.value = emptyMap()
                if (curKey != null) {
                    // Текущую задачу уберёт handler отмены в runTask (там же чистится её каталог).
                    _tasks.value = _tasks.value.filterKeys { it == curKey }
                } else {
                    _tasks.value = emptyMap()
                }
                cancelledKeys.forEach { notifiedDone.remove(it) }
                // cancelAll снимает и паузу: очередь пуста, продолжать нечего.
                pausedAll = false
                _paused.value = false
                currentJob?.cancel()
            }
            // Partial-каталоги очереди — вне мьютекса (IO). Каталог текущей задачи
            // дочистит handler отмены; повторный deleteRecursively безвреден.
            toWipe.forEach { req ->
                runCatching {
                    MediaDownloader.episodeDir(
                        appContext, req.itemKey, req.source, req.translationId, req.episodeNumber
                    ).deleteRecursively()
                }
            }
        }
    }

    /**
     * Останавливает все загрузки, сохраняя очередь и недокачанные файлы:
     * текущая докачка прервётся, partial-файлы останутся для докачки по «Продолжить все».
     */
    fun pauseAll() {
        scope.launch {
            queueMutex.withLock {
                val hasActive = currentKey != null ||
                    pending.value.isNotEmpty() ||
                    _tasks.value.values.any {
                        it.phase == DownloadPhase.QUEUED ||
                            it.phase == DownloadPhase.RESOLVING ||
                            it.phase == DownloadPhase.DOWNLOADING
                    }
                if (!hasActive || pausedAll) return@withLock
                pausedAll = true
                _paused.value = true
                // Текущий запрос — в голову очереди, чтобы «Продолжить все» подхватило его первым.
                // Задача в одиночной паузе уже хранит запрос в pausedRequests — дубль не кладём.
                val curKey = currentKey
                val curReq = currentRequest
                if (curKey != null && curReq != null &&
                    !pausedRequests.value.containsKey(curKey) &&
                    pending.value.none { itKey(it) == curKey }
                ) {
                    pending.value = listOf(curReq) + pending.value
                }
                _tasks.value = _tasks.value.mapValues { (_, t) ->
                    if (t.phase == DownloadPhase.QUEUED ||
                        t.phase == DownloadPhase.RESOLVING ||
                        t.phase == DownloadPhase.DOWNLOADING
                    ) t.copy(phase = DownloadPhase.PAUSED) else t
                }
                currentJob?.cancel()
            }
        }
    }

    /** Продолжает очередь после [pauseAll]: задачи встают обратно в очередь, воркер перезапускается. */
    fun resumeAll() {
        scope.launch {
            queueMutex.withLock {
                if (!pausedAll) return@withLock
                pausedAll = false
                _paused.value = false
                // Одиночно приостановленные (запросы в pausedRequests) остаются на паузе.
                val singlePaused = pausedRequests.value.keys
                _tasks.value = _tasks.value.mapValues { (k, t) ->
                    if (t.phase == DownloadPhase.PAUSED && !singlePaused.contains(k)) {
                        t.copy(phase = DownloadPhase.QUEUED, error = null)
                    } else t
                }
                ensureWorker()
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
                    notifiedDone.remove(key)
                }
            }
        }
    }

    private fun itKey(request: EpisodeDownloadRequest): String =
        offlineKey(request.itemKey, request.source, request.translationId, request.episodeNumber)

    /**
     * Снимает зависший флаг паузы, когда очереди и активных/приостановленных задач не осталось
     * (гонка «пауза + отмена последней задачи»). Без очереди флаг невидим, но следующая
     * постановка тогда ошибочно встала бы сразу «на паузу».
     */
    private fun resetPauseIfIdle() {
        if (!pausedAll) return
        if (pending.value.isNotEmpty()) return
        val busy = _tasks.value.values.any {
            it.phase == DownloadPhase.QUEUED ||
                it.phase == DownloadPhase.RESOLVING ||
                it.phase == DownloadPhase.DOWNLOADING ||
                it.phase == DownloadPhase.PAUSED
        }
        if (!busy && currentKey == null) {
            pausedAll = false
            _paused.value = false
        }
    }

    private fun taskState(request: EpisodeDownloadRequest, phase: DownloadPhase) = DownloadTaskState(
        key = itKey(request),
        itemKey = request.itemKey,
        title = request.title,
        source = request.source,
        translationId = request.translationId,
        translationTitle = request.translationTitle,
        episodeNumber = request.episodeNumber,
        episodeLabel = request.episodeLabel,
        phase = phase,
        posterUrl = request.posterUrl ?: cachedAnimePoster(request.itemKey)
    )

    private fun ensureWorker() {
        if (workerActive) return
        workerActive = true
        scope.launch {
            try {
                while (true) {
                    if (pausedAll) {
                        workerActive = false
                        return@launch
                    }
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
                    try {
                        runTask(next)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Одиночная отмена текущей — очередь продолжается со следующей;
                        // глобальная пауза/отмена всех — останавливаем воркер.
                        if (pausedAll) {
                            workerActive = false
                            return@launch
                        }
                        // pending пуст — следующая итерация сама остановит воркер.
                    }
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
        // Гонка «пауза в момент завершения»: серия уже докачалась — пропускаем дубль.
        if (findLibraryEntry(key) != null) {
            _tasks.value = _tasks.value - key
            return
        }
        currentKey = key
        currentRequest = request
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

            // Повторные попытки с backoff: короткий разрыв сети переживается внутри задачи,
            // partial-файлы при этом сохраняются — следующая попытка докачивает по Range/HLS-сегментам.
            // Скорость — EMA по дельтам bytesDone (direct обновляет по 64КБ-чанкам, HLS — по
            // завершённым сегментам). Сброс дельт (перезапуск попытки) обнуляет замер.
            var speedEma = 0.0
            var sampleMs = 0L
            var sampleBytes = 0L
            fun onProgress(progress: MediaDownloader.MediaProgress) {
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
            var attempt = 0
            var downloaded: MediaDownloader.MediaFile? = null
            while (attempt < MAX_ATTEMPTS) {
                attempt += 1
                // Новая попытка после обрыва: замер скорости начинаем заново.
                speedEma = 0.0
                sampleMs = 0L
                sampleBytes = 0L
                try {
                    update { it.copy(phase = DownloadPhase.RESOLVING) }
                    val media = request.resolve()
                        ?: throw MediaDownloader.DownloadException("Не удалось получить ссылку на видео")
                    update { it.copy(phase = DownloadPhase.DOWNLOADING) }
                    val dir = MediaDownloader.episodeDir(
                        appContext, request.itemKey, request.source, request.translationId, request.episodeNumber
                    )
                    downloaded = MediaDownloader.download(media, dir, "episode", ::onProgress)
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Пауза (глобальная «Остановить все» либо одиночная на задаче): недокачанные
                    // файлы и задачу сохраняем — фаза PAUSED уже выставлена, запрос ждёт в очереди.
                    // Проверка очереди защищает от гонки «пауза + отмена»: если запрос уже
                    // убрали из очереди одиночной отменой/cancelAll — это отмена, чистим.
                    val keepForResume = pausedRequests.value.containsKey(key) ||
                        (pausedAll && pending.value.any { itKey(it) == key })
                    if (keepForResume) {
                        currentKey = null
                        currentRequest = null
                        throw e
                    }
                    // Отмена пользователем: подчистить недокачанный каталог и убрать задачу.
                    runCatching {
                        MediaDownloader.episodeDir(appContext, request.itemKey, request.source, request.translationId, request.episodeNumber)
                            .deleteRecursively()
                    }
                    _tasks.value = _tasks.value - key
                    notifiedDone.remove(key)
                    currentKey = null
                    currentRequest = null
                    resetPauseIfIdle()
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "attempt $attempt/$MAX_ATTEMPTS failed for $key: ${e.message}")
                    if (attempt >= MAX_ATTEMPTS) {
                        failedRequests.value = failedRequests.value + (key to request)
                        update { it.copy(phase = DownloadPhase.FAILED, error = e.message ?: "Ошибка скачивания") }
                    } else {
                        // Фазу не меняем (прогресс/очередь живы), только hint — следующий retry докачает.
                        update { it.copy(error = "Повтор $attempt/$MAX_ATTEMPTS: ${e.message ?: "обрыв сети"}") }
                        try {
                            delay((BASE_RETRY_DELAY_MS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MS))
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            // Пауза в окне backoff: partial-файлы сохраняем для докачки
                            // (см. проверку очереди выше — защита от гонки с отменой).
                            val keepForResume = pausedRequests.value.containsKey(key) ||
                                (pausedAll && pending.value.any { itKey(it) == key })
                            if (keepForResume) {
                                currentKey = null
                                currentRequest = null
                                throw ce
                            }
                            // Отмена в окне backoff: чистим partial как при обычной отмене.
                            runCatching {
                                MediaDownloader.episodeDir(appContext, request.itemKey, request.source, request.translationId, request.episodeNumber)
                                    .deleteRecursively()
                            }
                            _tasks.value = _tasks.value - key
                            notifiedDone.remove(key)
                            currentKey = null
                            currentRequest = null
                            resetPauseIfIdle()
                            throw ce
                        }
                    }
                }
            }
            val file = downloaded ?: run { currentKey = null; currentRequest = null; return@launch }
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
                isHls = file.isHls,
                posterUrl = request.posterUrl ?: cachedAnimePoster(request.itemKey)
            )
            _tasks.value = _tasks.value - key
            _library.value = (_library.value.filter { it.key != key } + entry).sortedBy { it.key }
            saveLibrary(_library.value)
            Log.i(TAG, "downloaded $key (${formatBytes(file.sizeBytes)})")
            // Отдельное завершающееся уведомление (не foreground-прогресс): одно на задачу.
            if (notifiedDone.add(key)) {
                DownloadNotifications.postCompleted(appContext, key, request.title, request.episodeLabel)
            }
            currentKey = null
            currentRequest = null
        }
        currentJob = job
        try {
            job.join()
        } finally {
            currentJob = null
            // Страховка от зависшего currentKey после отмены (handler внутри уже чистит).
            if (job.isCancelled && currentKey == key) {
                currentKey = null
                currentRequest = null
            }
        }
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

    /**
     * Обложка аниме из локального кэша приложения (ключ «a<shikimoriId>»): запросы из
     * шитов/пикеров постер не несут, а кэш деталей обычно уже прогрет просмотром тайтла.
     * Чистый read префов, сети нет.
     */
    private val userStore by lazy { UserStateStore(appContext) }

    private fun cachedAnimePoster(itemKey: String): String? {
        val shikimoriId = itemKey.removePrefix("a").toIntOrNull()?.takeIf { itemKey.startsWith("a") }
            ?: return null
        return runCatching { userStore.getShikimoriAnimeCache()[shikimoriId]?.posterUrl }
            .getOrNull()
    }

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
