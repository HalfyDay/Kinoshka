package hd.kinoshka.app.data.diagnostics

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity
import hd.kinoshka.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сбор диагностики «на любом устройстве»: кольцевой буфер событий (плеер/приложение),
 * перехват любых неперехваченных крашей и текстовый отчёт, который уходит через системное
 * меню «Поделиться» — Telegram, почта, проводник, что угодно, без особых разрешений.
 *
 * Отчёт = устройство + события из буфера + последний сохранённый краш (если был) + logcat
 * собственного процесса (свои строки читаются без READ_LOGS). Файлы лежат в
 * files/diagnostics и шарятся через уже настроенный FileProvider (authority ".provider").
 */
object AppDiagnostics {
    private const val TAG = "AppDiagnostics"
    private const val MAX_EVENTS = 600
    private const val MAX_CRASH_FILES = 8

    private val events = ArrayDeque<String>()
    private val eventsLock = Any()
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val lineStamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fullStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Один раз из KinoApplication.onCreate — до остальной инициализации. */
    fun init(app: Context) {
        installCrashHandler(app)
        // Логи mpv в общий буфер: при дефолтном msg-level=warn сюда падают именно ошибки
        // (404 CDN, TLS-обрывы), то есть то, что нужно в отчёте. Обсервер глобальный —
        // переживает пересоздание mpv-ядра внутри плеера.
        runCatching {
            `is`.xyz.mpv.MPVLib.addLogObserver(object : `is`.xyz.mpv.MPVLib.LogObserver {
                override fun logMessage(prefix: String, level: Int, text: String) {
                    mpvLog(prefix, level, text)
                }
            })
        }
        event("diagnostics initialized (v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}))")
    }

    /** Короткое событие в кольцевой буфер: попадает в любой отчёт вместе со временем. */
    fun event(message: String) {
        synchronized(eventsLock) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(lineStamp.format(Date()) + " " + message.take(400))
        }
    }

    private fun mpvLog(prefix: String, level: Int, message: String) {
        // V/I отсекаются: даже при включённом verbose-логе буфер не должен тонуть в шуме.
        if (level < Log.WARN) return
        event("mpv/$prefix: ${message.trim().take(300)}")
    }

    fun snapshotEvents(): String = synchronized(eventsLock) { events.joinToString("\n") }

    private fun diagDir(ctx: Context): File = File(ctx.filesDir, "diagnostics").apply { mkdirs() }

    fun savedCrashFiles(ctx: Context): List<File> =
        diagDir(ctx).listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name } ?: emptyList()

    /**
     * Полный отчёт. [throwable] добавляет секцию текущего краша; последней инлайнится
     * содержимое последнего сохранённого crash-файла — одно отправленное сообщение несёт
     * всю картину.
     */
    fun buildReport(ctx: Context, throwable: Throwable? = null): String = buildString {
        appendLine("Киношка ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — отчёт о проблеме")
        appendLine(fullStamp.format(Date()))
        appendLine("(внутри — адреса потоков и события плеера; отправляйте только тому, кому доверяете)")
        appendLine()
        appendLine("=== Устройство ===")
        appendLine(runCatching { CrashActivity.collectDeviceInfo() }.getOrDefault("(сбор информации об устройстве не удался)"))
        appendLine()
        appendLine("=== События приложения и плеера (кольцевой буфер) ===")
        appendLine(snapshotEvents().ifBlank { "(нет событий)" })
        appendLine()
        val crashes = savedCrashFiles(ctx)
        if (crashes.isNotEmpty()) {
            appendLine("=== Сохранённые краш-отчёты ===")
            appendLine(crashes.joinToString("\n") { it.name })
            appendLine()
            appendLine("--- содержимое последнего (${crashes.last().name}) ---")
            runCatching { append(crashes.last().readText()) }
                .onFailure { appendLine("(не удалось прочитать: ${it.message})") }
            appendLine()
        }
        if (throwable != null) {
            appendLine("=== Текущий краш ===")
            appendLine("thread: ${Thread.currentThread().name}")
            appendLine(stackTraceOf(throwable))
            appendLine()
        }
        appendLine("=== Logcat процесса ===")
        appendLine(runCatching { CrashActivity.collectLogcat() }.getOrDefault("(logcat недоступен на этом устройстве)"))
    }

    /** Пишет отчёт в files/diagnostics; краш-файлы дополнительно ротируются. */
    fun saveReportFile(ctx: Context, throwable: Throwable? = null): File {
        val file = File(
            diagDir(ctx),
            (if (throwable != null) "crash-" else "report-") + fileStamp.format(Date()) + ".txt",
        )
        file.writeText(buildReport(ctx, throwable))
        val crashes = savedCrashFiles(ctx)
        if (crashes.size > MAX_CRASH_FILES) {
            crashes.take(crashes.size - MAX_CRASH_FILES).forEach { it.delete() }
        }
        return file
    }

    /**
     * Сбор и отправка: логкат может занять сотни мс, поэтому всё в фоне; из фонового потока
     * только строим файл, сам chooser — на UI-потоке.
     */
    fun shareReport(activity: Activity) {
        val app = activity.applicationContext
        Thread {
            val file = runCatching { saveReportFile(app, null) }.getOrElse {
                Log.e(TAG, "report build failed", it)
                return@Thread
            }
            activity.runOnUiThread {
                runCatching {
                    val uri = FileProvider.getUriForFile(app, app.packageName + ".provider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Киношка: отчёт о проблеме (${file.name})")
                        // ClipData дублирует право чтения — часть целей шаринга читает только его.
                        clipData = android.content.ClipData.newRawUri(null, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(send, "Отправить отчёт"))
                }.onFailure { e ->
                    Log.e(TAG, "share failed", e)
                    Toast.makeText(app, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun stackTraceOf(throwable: Throwable): String =
        StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

    /**
     * Любой неперехваченный краш: полный отчёт сохраняется на диск (переживает смерть
     * процесса), открывается готовый экран сбоя с кнопкой «Поделиться», затем стандартный
     * обработчик завершает процесс как обычно.
     */
    private fun installCrashHandler(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val file = saveReportFile(app, throwable)
                event("CRASH: ${throwable.javaClass.name}: ${throwable.message?.take(160)} → ${file.name}")
                val intent = Intent(app, CrashActivity::class.java)
                    .putExtra("exception", stackTraceOf(throwable))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                app.startActivity(intent)
                // ActivityManager должен успеть принять старт до того, как стандартный
                // обработчик прибьёт процесс.
                Thread.sleep(400)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
