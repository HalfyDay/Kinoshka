package hd.kinoshka.app.player.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.io.File

/**
 * Тонкая JNA-обёртка libmpv — фундамент desktop-плеера.
 * Вывод картинки: mpv рендерит в native-дочернее окно через опцию wid (HWND из Compose-иерархии).
 */
interface LibMpv : Library {
    fun mpv_client_api_version(): NativeLong
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(handle: Pointer, args: Array<String?>): Int
    fun mpv_terminate_destroy(handle: Pointer)
}

object MpvNative {
    @Volatile
    private var lib: LibMpv? = null

    fun load(): LibMpv {
        lib?.let { return it }
        synchronized(this) {
            lib?.let { return it }
            searchDir()?.let { System.setProperty("jna.library.path", it) }
            val loaded = try {
                Native.load("mpv", LibMpv::class.java)
            } catch (_: UnsatisfiedLinkError) {
                Native.load("libmpv-2", LibMpv::class.java)
            }
            lib = loaded
            return loaded
        }
    }

    /** Ищем libmpv в MPV_PATH или в папке mpv/ рядом с рабочей директорией; иначе — системный PATH. */
    private fun searchDir(): String? {
        val env = System.getenv("MPV_PATH")?.let(::File)
        val candidates = listOfNotNull(env, File("mpv"), File("../mpv"))
        for (candidate in candidates) {
            when {
                candidate.isDirectory -> return candidate.absolutePath
                candidate.isFile -> return candidate.parentFile?.absolutePath
            }
        }
        return null
    }

    /** Дымовая проверка: грузится ли libmpv и какая у неё версия client API. */
    fun probe(): String = try {
        val version = load().mpv_client_api_version().toLong()
        "libmpv подключён — client API ${version shr 16}.${version and 0xFFFF}"
    } catch (t: Throwable) {
        "libmpv не найден (${t.message}). Запустите tools/fetch-libmpv.ps1 или задайте MPV_PATH."
    }
}

/**
 * Одна mpv-сессия, привязанная к native-окну (wid). Создание — после того как
 * целевой HWND реально существует (канвас показан на экране).
 */
class MpvPlayer private constructor(
    private val lib: LibMpv,
    private val handle: Pointer,
) {
    companion object {
        fun create(wid: Long): MpvPlayer {
            val lib = MpvNative.load()
            val handle = lib.mpv_create() ?: error("mpv_create() == null")
            lib.mpv_set_property_string(handle, "wid", wid.toString())
            lib.mpv_set_property_string(handle, "hwdec", "auto-safe")
            lib.mpv_set_property_string(handle, "keep-open", "always")
            lib.mpv_set_property_string(handle, "input-default-bindings", "no")
            lib.mpv_set_property_string(handle, "osc", "no")
            // Диагностика: пишем лог mpv в stderr (попадает в лог gradle run).
            lib.mpv_set_property_string(handle, "terminal", "yes")
            lib.mpv_set_property_string(handle, "msg-level", "cplayer=v,file=v,vo=v")
            val rc = lib.mpv_initialize(handle)
            if (rc != 0) {
                lib.mpv_terminate_destroy(handle)
                error("mpv_initialize() = $rc")
            }
            return MpvPlayer(lib, handle)
        }
    }

    fun load(url: String): Int = command("loadfile", url, "replace")

    fun setPaused(paused: Boolean) = setProperty("pause", if (paused) "yes" else "no")

    fun isPaused(): Boolean = getProperty("pause") == "yes"

    fun togglePause(): Boolean {
        val next = !isPaused()
        setPaused(next)
        return next
    }

    fun positionSeconds(): Double? = getProperty("time-pos")?.toDoubleOrNull()

    fun durationSeconds(): Double? = getProperty("duration")?.toDoubleOrNull()

    fun seekTo(seconds: Double) = setProperty("time-pos", seconds.toString())

    fun setVolume(percent: Int) = setProperty("volume", percent.coerceIn(0, 130).toString())

    fun stop() = command("stop")

    fun close() {
        runCatching { lib.mpv_terminate_destroy(handle) }
    }

    private fun command(vararg args: String): Int {
        val argv = arrayOfNulls<String>(args.size + 1)
        args.forEachIndexed { index, arg -> argv[index] = arg }
        return lib.mpv_command(handle, argv)
    }

    private fun setProperty(name: String, value: String) {
        lib.mpv_set_property_string(handle, name, value)
    }

    fun getProperty(name: String): String? {
        val data = lib.mpv_get_property_string(handle, name) ?: return null
        val value = data.getString(0, "UTF-8")
        lib.mpv_free(data)
        return value
    }
}
