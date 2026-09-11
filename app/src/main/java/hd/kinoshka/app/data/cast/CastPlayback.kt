package hd.kinoshka.app.data.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * Сессия каста: подключение/отключение, загрузка текущего потока на ТВ.
 * Локальный mpv на время трансляции ставится на паузу (включает PlayerActivity).
 */
object CastPlayback {
    private const val TAG = "CastPlayback"

    private var sessionManager: com.google.android.gms.cast.framework.SessionManager? = null
    private var listener: SessionManagerListener<CastSession>? = null
    // Владелец текущего слушателя (плеер или пульт): чужой release слушатель
    // не снимает, иначе у пульта глохнут события сессии и транспорт слепнет.
    private var owner: Any? = null

    @Volatile
    private var lastRemotePositionSec: Long = 0

    val isCasting: Boolean
        get() = try {
            sessionManager?.currentCastSession?.isConnected == true
        } catch (_: Exception) {
            false
        }

    /**
     * Есть объект сессии (включая стартующую/подключающуюся). Для владения реле:
     * гасить сервер можно только когда сессии нет вообще — isCasting в момент
     * запуска приёмника ещё false, и ранний stop ронял in-flight LOAD (2100).
     */
    fun hasSession(): Boolean = try {
        sessionManager?.currentCastSession != null
    } catch (_: Exception) {
        false
    }

    fun remoteClient(): RemoteMediaClient? = try {
        sessionManager?.currentCastSession?.remoteMediaClient
    } catch (_: Exception) {
        null
    }

    /**
     * Подписка на сессии. Без Play Services — тихий no-op, кнопка каста просто
     * откроет пустой диалог выбора.
     */
    fun init(context: Context, owner: Any, onSessionStarted: () -> Unit, onSessionEnded: () -> Unit) {
        val mgr = try {
            CastContext.getSharedInstance(context.applicationContext).sessionManager
        } catch (e: Exception) {
            Log.w(TAG, "Cast unavailable: ${e.message}")
            return
        }
        sessionManager = mgr
        // Слушатель один на всех: владельцы меняются (плеер → пульт),
        // синглтон-менеджер один. sessionManager НЕ nullим в release:
        // иначе hasSession/remoteClient глохнут у нового владельца.
        try {
            listener?.let { mgr.removeSessionManagerListener(it, CastSession::class.java) }
        } catch (_: Exception) {
        }
        this.owner = owner
        listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStarted(session: CastSession, sessionId: String) = onSessionStarted()
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) {
                lastRemotePositionSec = try {
                    (session.remoteMediaClient?.approximateStreamPosition ?: 0) / 1000
                } catch (_: Exception) {
                    0
                }
            }

            override fun onSessionEnded(session: CastSession, error: Int) = onSessionEnded()
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onSessionStarted()
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        mgr.addSessionManagerListener(listener as SessionManagerListener<CastSession>, CastSession::class.java)
    }

    /** Позиция ТВ на момент отключения (сек) — продолжить локально. */
    fun lastPositionSec(): Long = lastRemotePositionSec

    /** Льёт HLS-URL (уже через LAN-реле) на ТВ с текущей позиции. */
    fun load(url: String, title: String, positionSec: Long, durationSec: Long?) {
        loadInternal(url, title, positionSec, durationSec, attempt = 0)
    }

    /**
     * LOAD с автоповтором: сеть приёмник↔телефон плавающая (первые SYNs иногда
     * уходят в таймаут) — повтор через 2.5с, пока сессия жива. Максимум 2 повтора.
     */
    private fun loadInternal(url: String, title: String, positionSec: Long, durationSec: Long?, attempt: Int) {
        val client = remoteClient() ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
            .apply { putString(MediaMetadata.KEY_TITLE, title) }
        val infoBuilder = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("application/x-mpegURL")
            .setMetadata(metadata)
        if (durationSec != null && durationSec > 0) {
            infoBuilder.setStreamDuration(durationSec * 1000L)
        }
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(infoBuilder.build())
            .setAutoplay(true)
            .setCurrentTime(positionSec.coerceAtLeast(0) * 1000L)
            .build()
        try {
            client.load(request)?.setResultCallback { result ->
                val code = result.status.statusCode
                Log.i(TAG, "load result: code=$code msg=${result.status.statusMessage} " +
                    "attempt=$attempt url=${url.take(120)}")
                if (code != 0 && attempt < 2 && isCasting) {
                    Log.i(TAG, "load failed ($code), retrying in 2.5s")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isCasting) loadInternal(url, title, positionSec, durationSec, attempt + 1)
                    }, 2500)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            remoteClient()?.stop()
        } catch (_: Exception) {
        }
    }

    // ---- Пульт на телефоне ----

    /** Имя ТВ для шапки пульта. */
    fun deviceName(): String? = try {
        sessionManager?.currentCastSession?.castDevice?.friendlyName
    } catch (_: Exception) {
        null
    }

    fun toggle() {
        try {
            remoteClient()?.togglePlayback()
        } catch (_: Exception) {
        }
    }

    fun seek(positionMs: Long) {
        try {
            val c = remoteClient() ?: return
            // Seek мимо длительности/по пустому плееру приёмник режет Invalid Request.
            val dur = try {
                c.streamDuration
            } catch (_: Exception) {
                0L
            }
            val state = try {
                c.playerState
            } catch (_: Exception) {
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_UNKNOWN
            }
            if (state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE) return
            val target = if (dur > 0) positionMs.coerceIn(0, (dur - 2000).coerceAtLeast(0)) else positionMs.coerceAtLeast(0)
            c.seek(
                com.google.android.gms.cast.MediaSeekOptions.Builder()
                    .setPosition(target)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    /** Отключиться от ТВ (локальный плеер продолжит с позиции ТВ — см. PlayerActivity). */
    fun endSession(context: Context) {
        try {
            CastContext.getSharedInstance(context.applicationContext)
                .sessionManager.endCurrentSession(true)
        } catch (_: Exception) {
        }
        // Явный разрыв: реле больше никому не нужно (пульт/плеер уходят с экрана).
        CastRelayServer.stopInstance()
    }

    /**
     * Снять слушателя, но только своего: умирающий плеер не должен глушить
     * только что открывшийся пульт. Менеджер не трогаем — он глобальный.
     */
    fun release(owner: Any) {
        if (owner !== this.owner) return
        val mgr = sessionManager
        try {
            listener?.let { mgr?.removeSessionManagerListener(it, CastSession::class.java) }
        } catch (_: Exception) {
        }
        listener = null
        this.owner = null
    }
}
