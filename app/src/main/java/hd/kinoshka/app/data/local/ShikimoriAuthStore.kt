@file:Suppress("DEPRECATION")
// androidx.security.crypto (EncryptedSharedPreferences) deprecated целиком; миграция на другой
// механизм хранения - отдельная задача, здесь легаси-API используется осознанно.
package hd.kinoshka.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class ShikimoriAuthState(
    val isLoggedIn: Boolean = false,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: Int = 0,
    val nickname: String? = null,
    val avatarUrl: String? = null
)

class ShikimoriAuthStore(context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "shikimori_auth_encrypted_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences("shikimori_auth_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getAuthState(): ShikimoriAuthState {
        val token = prefs.getString("access_token", null)
        val refresh = prefs.getString("refresh_token", null)
        val id = prefs.getInt("user_id", 0)
        val nick = prefs.getString("nickname", null)
        val avatar = prefs.getString("avatar_url", null)

        return ShikimoriAuthState(
            isLoggedIn = !token.isNull_or_blank(),
            accessToken = token,
            refreshToken = refresh,
            userId = id,
            nickname = nick,
            avatarUrl = avatar
        )
    }

    fun saveSession(token: String, refresh: String?, userId: Int, nickname: String?, avatarUrl: String?) {
        prefs.edit()
            .putString("access_token", token)
            .putString("refresh_token", refresh)
            .putInt("user_id", userId)
            .putString("nickname", nickname)
            .putString("avatar_url", avatarUrl)
            .apply()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
