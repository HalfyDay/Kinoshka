package hd.kinoshka.app.data.local

import android.content.Context
import android.content.SharedPreferences

actual class KinoPrefs(private val prefs: SharedPreferences) {
    // prefs.edit() каждый раз возвращает НОВЫЙ Editor: put*() обязаны накапливать правки
    // на одном общем editor, иначе apply()/commit() записывают пустой набор (настройки
    // молча не сохраняются).
    private val editor: SharedPreferences.Editor = prefs.edit()

    actual fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    actual fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    actual fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    actual fun putString(key: String, value: String?): KinoPrefs =
        editor.putString(key, value).let { this }
    actual fun putBoolean(key: String, value: Boolean): KinoPrefs =
        editor.putBoolean(key, value).let { this }
    actual fun putLong(key: String, value: Long): KinoPrefs =
        editor.putLong(key, value).let { this }
    actual fun remove(key: String): KinoPrefs = editor.remove(key).let { this }
    actual fun apply() = editor.apply()
    actual fun commit(): Boolean = editor.commit()

    companion object {
        /** Тот же файл, что использовал UserStateStore до миграции. */
        fun from(context: Context): KinoPrefs =
            KinoPrefs(context.applicationContext.getSharedPreferences("kino_user_state", Context.MODE_PRIVATE))
    }
}
