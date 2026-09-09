package hd.kinoshka.app.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import hd.kinoshka.app.MainActivity
import hd.kinoshka.app.ui.screens.AnixartImportBus
import hd.kinoshka.app.ui.screens.AnixartImportProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-сервис импорта библиотеки Anixart (catch-up после входа/вайпа):
 * держит процесс живым, пока ~800 поисков Shikimori идут в фоне, и показывает
 * системное уведомление с живой фазой. Сам импорт ведёт FilmsViewModel —
 * сервис только зеркалит [AnixartImportBus] в шторку: шина пуста → итог
 * «завершено» и остановка.
 */
class AnixartImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false
    private var finished = false
    private var lastDeterminate: AnixartImportProgress? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!watching) {
            watching = true
            scope.launch {
                AnixartImportBus.flow.collect { p ->
                    if (p == null) {
                        finishImport()
                    } else {
                        if (p.total > 0) lastDeterminate = p
                        AnixartImportNotifications.post(this@AnixartImportService, p)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val current = AnixartImportBus.flow.value
        val notification = if (current != null) {
            if (current.total > 0) lastDeterminate = current
            AnixartImportNotifications.build(this, current)
        } else {
            AnixartImportNotifications.buildIdle(this)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                AnixartImportNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AnixartImportNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun finishImport() {
        if (finished) return
        finished = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        AnixartImportNotifications.postCompleted(this, lastDeterminate)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AnixartImport"

        /** Поднимает сервис из коллектора KinoApplication при старте catch-up. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, AnixartImportService::class.java)
                context.startForegroundService(intent)
            }.onFailure { Log.w(TAG, "startForegroundService failed: ${it.message}") }
        }
    }
}

/** Канал и сборка уведомлений импорта Anixart. */
object AnixartImportNotifications {
    const val CHANNEL_ID = "anixart_import"
    const val NOTIFICATION_ID = 4202

    /** Апдейты прогресса приходят на каждый хит — шину уведомлений будим не чаще 1 Гц. */
    private const val MIN_POST_INTERVAL_MS = 1000L

    @Volatile private var lastPostMs = 0L

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Импорт Anixart",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Прогресс сопоставления библиотеки Anixart с Shikimori"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun buildIdle(context: Context): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("Импорт библиотеки Anixart")
            .setContentText("Подготовка…")
            .setContentIntent(contentIntent(context))
            .setProgress(0, 0, true)
            .build()
    }

    fun build(context: Context, p: AnixartImportProgress): Notification {
        ensureChannel(context)
        val text = if (p.total > 0) "${p.phase} · ${p.done}/${p.total}" else p.phase
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("Импорт библиотеки Anixart")
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setStyle(Notification.BigTextStyle().bigText(text))
        if (p.total > 0) builder.setProgress(p.total, p.done.coerceAtMost(p.total), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    fun post(context: Context, p: AnixartImportProgress) {
        runCatching {
            ensureChannel(context)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPostMs < MIN_POST_INTERVAL_MS) return
            lastPostMs = now
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, build(context, p))
        }
    }

    /** Одноразовый итог: шторка гаснет прогрессом, остаётся тихое «готово». */
    fun postCompleted(context: Context, last: AnixartImportProgress?) {
        runCatching {
            ensureChannel(context)
            val text = last?.takeIf { it.total > 0 }
                ?.let { "Сопоставлено ${it.done} из ${it.total}" }
                ?: "Библиотека обновлена"
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setContentTitle("Импорт Anixart завершён")
                .setContentText(text)
                .setContentIntent(contentIntent(context))
                .setStyle(Notification.BigTextStyle().bigText(text))
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID + 1, notification)
        }
    }
}
