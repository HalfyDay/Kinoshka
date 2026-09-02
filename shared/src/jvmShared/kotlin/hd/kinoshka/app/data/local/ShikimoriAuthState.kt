package hd.kinoshka.app.data.local

data class ShikimoriAuthState(
    val isLoggedIn: Boolean = false,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: Int = 0,
    val nickname: String? = null,
    val avatarUrl: String? = null
)

/**
 * Аутентификация Shikimori без привязки к платформе. Android-реализация ([hd.kinoshka.app.data.local.ShikimoriAuthStore])
 * хранит токены в EncryptedSharedPreferences; общий код видит только этот контракт.
 */
interface ShikimoriAuthProvider {
    fun getAuthState(): ShikimoriAuthState
    fun saveSession(token: String, refresh: String?, userId: Int, nickname: String?, avatarUrl: String?)
    fun clearSession()
}
