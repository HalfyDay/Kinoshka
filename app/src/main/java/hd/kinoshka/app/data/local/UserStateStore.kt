package hd.kinoshka.app.data.local

import android.content.Context

/**
 * Android-фасад: тот же конструктор (Context), что был до миграции, — все
 * существующие места создания продолжают работать. Логика в shared
 * (UserStateStoreBase поверх KinoPrefs, файл prefs не изменился).
 */
class UserStateStore(context: Context) : UserStateStoreBase(KinoPrefs.from(context))
