package hd.kinoshka.app.data.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import hd.kinoshka.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-сервис, который держит живой процесс на время скачивания и показывает
 * системное уведомление с прогрессом текущей серии и числом в очереди. Скачивание
 * само по себе ведёт EpisodeDownloadManager в собственном scope — сервис только
 * наблюдатель: очередь пуста → уходит с экрана и останавливается.
 */
class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!watching) {
            watching = true
            scope.launch {
                EpisodeDownloadManager.tasks.collect { tasks ->
                    val active = tasks.values.filter { it.phase != DownloadPhase.FAILED }
                    if (active.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        DownloadNotifications.post(this@DownloadForegroundService, active)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = DownloadNotifications.build(
            this,
            EpisodeDownloadManager.tasks.value.values.filter { it.phase != DownloadPhase.FAILED }
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                DownloadNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotifications.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadForeground"

        /** Ставит сервис на паузу очереди: вызывается менеджером при старте каждой задачи. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, DownloadForegroundService::class.java)
                context.startForegroundService(intent)
            }.onFailure { Log.w(TAG, "startForegroundService failed: ${it.message}") }
        }
    }
}

/** Канал и сборка прогресс-уведомления скачивания. */
object DownloadNotifications {
    const val CHANNEL_ID = "video_downloads"
    const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Загрузка видео",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Прогресс скачивания серий в офлайн-библиотеку"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, activeTasks: List<DownloadTaskState>) {
        runCatching {
            ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, build(context, activeTasks))
        }
    }

    fun build(context: Context, activeTasks: List<DownloadTaskState>): Notification {
        ensureChannel(context)
        val current = activeTasks.firstOrNull { it.phase == DownloadPhase.DOWNLOADING }
            ?: activeTasks.first()
        val queued = activeTasks.count { it.phase == DownloadPhase.QUEUED }

        val phaseText = when (current.phase) {
            DownloadPhase.QUEUED -> "в очереди"
            DownloadPhase.RESOLVING -> "поиск ссылки…"
            DownloadPhase.DOWNLOADING -> when {
                current.segmentsTotal > 0 -> "сегменты ${current.segmentsDone}/${current.segmentsTotal}"
                current.bytesTotal > 0 -> "${formatBytes(current.bytesDone)} / ${formatBytes(current.bytesTotal)}"
                else -> formatBytes(current.bytesDone)
            }
            else -> ""
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("Скачивание «${current.title}»")
            .setContentText("${current.episodeLabel} · ${current.translationTitle} · $phaseText")
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        if (queued > 0) builder.setSubText("В очереди: $queued")

        when {
            current.phase == DownloadPhase.DOWNLOADING && current.segmentsTotal > 0 ->
                builder.setProgress(current.segmentsTotal, current.segmentsDone, false)
            current.phase == DownloadPhase.DOWNLOADING && current.bytesTotal > 0 ->
                builder.setProgress(100, ((current.bytesDone * 100) / current.bytesTotal).toInt().coerceIn(0, 100), false)
            current.phase == DownloadPhase.DOWNLOADING ->
                builder.setProgress(0, 0, true)
            else -> Unit
        }
        return builder.build()
    }
}

/**
 * Запрос POST_NOTIFICATIONS из UI-точек, ставящих серию в очередь (API 33+). Даже без
 * разрешения скачивание идёт — просто не видно уведомления. Вызывается «впролёт» перед enqueue.
 */
fun Context.tryRequestNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
    var activity: android.app.Activity? = null
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is android.app.Activity) { activity = ctx; break }
        ctx = ctx.baseContext
    }
    return activity?.let {
        runCatching {
            it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8123)
        }
        false
    } ?: false
}
