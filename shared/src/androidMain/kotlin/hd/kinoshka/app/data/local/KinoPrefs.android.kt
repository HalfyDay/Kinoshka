package hd.kinoshka.app.data.local

import android.content.Context
import android.content.SharedPreferences

actual class KinoPrefs(private val prefs: SharedPreferences) {
    actual fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    actual fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    actual fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    actual fun putString(key: String, value: String?): KinoPrefs =
        prefs.edit().putString(key, value).let { this }
    actual fun putBoolean(key: String, value: Boolean): KinoPrefs =
        prefs.edit().putBoolean(key, value).let { this }
    actual fun putLong(key: String, value: Long): KinoPrefs =
        prefs.edit().putLong(key, value).let { this }
    actual fun remove(key: String): KinoPrefs = prefs.edit().remove(key).let { this }
    actual fun apply() = prefs.edit().apply()
    actual fun commit(): Boolean = prefs.edit().commit()

    companion object {
        /** Тот же файл, что использовал UserStateStore до миграции. */
        fun from(context: Context): KinoPrefs =
            KinoPrefs(context.applicationContext.getSharedPreferences("kino_user_state", Context.MODE_PRIVATE))
    }
}
