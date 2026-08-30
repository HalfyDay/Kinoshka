package hd.kinoshka.app.data.local

/**
 * Кроссплатформенное key-value хранилище пользователя (замена SharedPreferences
 * в общем коде). Android-actual оборачивает SharedPreferences, desktop —
 * Properties-файл в ~/.kino-desktop. Цепочка: prefs.putString(..).putBoolean(..).apply().
 */
expect class KinoPrefs {
    fun getString(key: String, def: String?): String?
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getLong(key: String, def: Long): Long
    fun putString(key: String, value: String?): KinoPrefs
    fun putBoolean(key: String, value: Boolean): KinoPrefs
    fun putLong(key: String, value: Long): KinoPrefs
    fun remove(key: String): KinoPrefs
    /** Асинхронная запись (аналог SharedPreferences.apply()). */
    fun apply()
    /** Синхронная запись; true при успехе (аналог commit()). */
    fun commit(): Boolean
}
