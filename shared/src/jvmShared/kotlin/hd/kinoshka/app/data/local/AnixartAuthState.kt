package hd.kinoshka.app.data.local

data class AnixartAuthState(
    val isLoggedIn: Boolean = false,
    val token: String? = null,
    val userId: Int = 0,
    val nickname: String? = null
)

/**
 * Аутентификация Anixart без привязки к платформе. Android-реализация
 * ([hd.kinoshka.app.data.local.AnixartAuthStore]) хранит токен в
 * EncryptedSharedPreferences; пароль нигде не сохраняется.
 */
interface AnixartAuthProvider {
    fun getAuthState(): AnixartAuthState
    fun saveSession(token: String, userId: Int, nickname: String?)
    fun clearSession()
}
