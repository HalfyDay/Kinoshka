@file:Suppress("DEPRECATION")
// Как ShikimoriAuthStore: токен в EncryptedSharedPreferences. Пароль Anixart
// нигде не сохраняется — только токен сессии из /auth/signIn.
package hd.kinoshka.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class AnixartAuthStore(context: Context) : AnixartAuthProvider {
    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "anixart_auth_encrypted_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("anixart_auth_prefs", Context.MODE_PRIVATE)
        }
    }

    override fun getAuthState(): AnixartAuthState {
        val token = prefs.getString("token", null)
        return AnixartAuthState(
            isLoggedIn = !token.isNullOrBlank(),
            token = token,
            userId = prefs.getInt("user_id", 0),
            nickname = prefs.getString("nickname", null)
        )
    }

    override fun saveSession(token: String, userId: Int, nickname: String?) {
        prefs.edit()
            .putString("token", token)
            .putInt("user_id", userId)
            .putString("nickname", nickname)
            .apply()
    }

    override fun clearSession() {
        prefs.edit().clear().apply()
    }
}
