package hd.kinoshka.app.data.local

import java.io.File
import java.util.Properties

/**
 * Properties-файл ~/.kino-desktop/kino_user_state.properties как аналог
 * SharedPreferences-хранилища Android. Значения — строки; булевы/числовые
 * сериализуются текстом. apply() пишет синхронно (файл маленький).
 */
actual class KinoPrefs private constructor(private val file: File) {
    private val properties = Properties()

    init {
        runCatching {
            if (file.exists()) file.inputStream().use { properties.load(it) }
        }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { properties.store(it, "Kino desktop user state") }
        }
    }

    actual fun getString(key: String, def: String?): String? = synchronized(this) {
        properties.getProperty(key, def)
    }

    actual fun getBoolean(key: String, def: Boolean): Boolean = synchronized(this) {
        properties.getProperty(key)?.toBooleanStrictOrNull() ?: def
    }

    actual fun getLong(key: String, def: Long): Long = synchronized(this) {
        properties.getProperty(key)?.toLongOrNull() ?: def
    }

    private fun stage(key: String, value: String): KinoPrefs = synchronized(this) {
        properties.setProperty(key, value)
        this
    }

    actual fun putString(key: String, value: String?): KinoPrefs =
        if (value == null) remove(key) else stage(key, value)

    actual fun putBoolean(key: String, value: Boolean): KinoPrefs = stage(key, value.toString())

    actual fun putLong(key: String, value: Long): KinoPrefs = stage(key, value.toString())

    actual fun remove(key: String): KinoPrefs = synchronized(this) {
        properties.remove(key)
        this
    }

    actual fun apply() = synchronized(this) { save() }

    actual fun commit(): Boolean {
        apply()
        return true
    }

    companion object {
        fun createDefault(): KinoPrefs =
            KinoPrefs(File(System.getProperty("user.home"), ".kino-desktop/kino_user_state.properties"))
    }
}
