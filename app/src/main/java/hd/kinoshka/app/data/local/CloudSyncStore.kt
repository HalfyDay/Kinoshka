@file:Suppress("DEPRECATION")
// androidx.security.crypto (EncryptedSharedPreferences) deprecated целиком; миграция на другой
// механизм хранения - отдельная задача, здесь легаси-API используется осознанно.
package hd.kinoshka.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Where the library backup lives. */
enum class CloudSyncType { NONE, YANDEX, WEBDAV }

data class CloudSyncConfig(
    val type: CloudSyncType = CloudSyncType.NONE,
    val yandexToken: String? = null,
    val yandexRefresh: String? = null,
    val webDavUrl: String? = null,
    val webDavUser: String? = null,
    val webDavPassword: String? = null,
    val autoSync: Boolean = false
) {
    val isConnected: Boolean
        get() = when (type) {
            CloudSyncType.YANDEX -> !yandexToken.isNullOrBlank()
            CloudSyncType.WEBDAV -> !webDavUrl.isNullOrBlank()
            CloudSyncType.NONE -> false
        }
}

class CloudSyncStore(context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "cloud_sync_encrypted_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("cloud_sync_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getConfig(): CloudSyncConfig = CloudSyncConfig(
        type = runCatching {
            CloudSyncType.valueOf(prefs.getString("type", CloudSyncType.NONE.name).orEmpty())
        }.getOrDefault(CloudSyncType.NONE),
        yandexToken = prefs.getString("yandex_token", null),
        yandexRefresh = prefs.getString("yandex_refresh", null),
        webDavUrl = prefs.getString("webdav_url", null),
        webDavUser = prefs.getString("webdav_user", null),
        webDavPassword = prefs.getString("webdav_password", null),
        autoSync = prefs.getBoolean("auto_sync", false)
    )

    fun saveYandex(token: String, refresh: String?) {
        prefs.edit()
            .putString("type", CloudSyncType.YANDEX.name)
            .putString("yandex_token", token)
            .putString("yandex_refresh", refresh)
            .apply()
    }

    fun saveWebDav(url: String, user: String, password: String) {
        prefs.edit()
            .putString("type", CloudSyncType.WEBDAV.name)
            .putString("webdav_url", url)
            .putString("webdav_user", user)
            .putString("webdav_password", password)
            .apply()
    }

    fun updateYandexToken(token: String, refresh: String?) {
        if (getConfig().type != CloudSyncType.YANDEX) return
        saveYandex(token, refresh)
    }

    fun setAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean("auto_sync", enabled).apply()
    }

    fun setLastSync(at: Long, result: String?) {
        prefs.edit().putLong("last_sync_at", at).putString("last_sync_result", result).apply()
    }

    fun getLastSyncAt(): Long = prefs.getLong("last_sync_at", 0L)

    fun getLastSyncResult(): String? = prefs.getString("last_sync_result", null)

    fun clear() {
        prefs.edit().clear().apply()
    }
}
