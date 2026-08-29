package hd.kinoshka.app.data.cloud

import android.content.Context
import android.util.Log
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.data.local.CloudSyncStore
import hd.kinoshka.app.data.local.UserStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud backup/restore of the whole library (profiles, watched episodes, ratings, notes,
 * history) — the reinstall-survival path for watch progress. Uses whatever storage the user
 * already has:
 *  - Yandex Disk REST API (self-service OAuth app, Russian, free);
 *  - any generic WebDAV (Nextcloud, self-hosted, …).
 *
 * The payload is the same JSON the app's manual export/import produces, so a cloud backup is
 * always readable by hand.
 */
object CloudBackupManager {

    private const val TAG = "CloudBackup"
    private const val BACKUP_DIR = "Kinoshka"
    private const val BACKUP_FILE = "library_backup.json"
    private const val AUTO_SYNC_DELAY_MS = 60_000L

    data class SyncStatus(
        val busy: Boolean = false,
        val message: String? = null,
        val lastSyncAt: Long = 0,
        val lastResult: String? = null
    )

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var autoJob: Job? = null

    fun init(context: Context) {
        val store = CloudSyncStore(context.applicationContext)
        if (_status.value.lastSyncAt == 0L) {
            _status.value = _status.value.copy(
                lastSyncAt = store.getLastSyncAt(),
                lastResult = store.getLastSyncResult()
            )
        }
    }

    // ------------------------------------------------------------------
    // Configuration (called from Profile UI)
    // ------------------------------------------------------------------

    fun yandexConfigured(): Boolean =
        BuildConfig.YANDEX_DISK_CLIENT_ID.isNotBlank() && BuildConfig.YANDEX_DISK_CLIENT_SECRET.isNotBlank()

    suspend fun loginYandex(context: Context, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = exchangeYandexCode(code) ?: error("Яндекс OAuth не вернул access_token")
            CloudSyncStore(context.applicationContext).saveYandex(
                token = token.getString("access_token"),
                refresh = token.optString("refresh_token").ifBlank { null }
            )
        }.onFailure { Log.e(TAG, "Yandex login failed", it) }
    }

    fun saveWebDav(context: Context, url: String, user: String, password: String) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("http")) { "URL должен начинаться с http(s)://" }
        CloudSyncStore(context.applicationContext).saveWebDav("$normalized/", user.trim(), password)
    }

    fun setAutoSync(context: Context, enabled: Boolean) {
        CloudSyncStore(context.applicationContext).setAutoSync(enabled)
    }

    fun disconnect(context: Context) {
        CloudSyncStore(context.applicationContext).clear()
        autoJob?.cancel()
        _status.value = _status.value.copy(message = "Отключено")
    }

    // ------------------------------------------------------------------
    // Public operations
    // ------------------------------------------------------------------

    /** Manual upload from the profile card. */
    fun uploadBackup(context: Context) {
        val app = context.applicationContext
        appScope.launch { runUpload(app, silent = false) }
    }

    /**
     * Manual restore: downloads the cloud JSON and REPLACES the local library
     * (the app's own importLibraryJson — the reinstall path).
     */
    fun restoreFromCloud(context: Context) {
        val app = context.applicationContext
        appScope.launch { runRestore(app) }
    }

    /**
     * Debounced auto-upload after library mutations (episode watched, title completed).
     * Called fire-and-forget from the player; no-op unless auto-sync is on.
     */
    fun onLibraryChanged(context: Context) {
        val app = context.applicationContext
        val cfg = CloudSyncStore(app).getConfig()
        if (!cfg.autoSync || !cfg.isConnected) return
        autoJob?.cancel()
        autoJob = appScope.launch {
            delay(AUTO_SYNC_DELAY_MS)
            if (_status.value.busy) return@launch
            runUpload(app, silent = true)
        }
    }

    // ------------------------------------------------------------------
    // Workers
    // ------------------------------------------------------------------

    private suspend fun runUpload(app: Context, silent: Boolean) {
        if (_status.value.busy) return
        val store = CloudSyncStore(app)
        val cfg = store.getConfig()
        if (!cfg.isConnected) {
            if (!silent) _status.value = _status.value.copy(message = "Облако не подключено")
            return
        }
        _status.value = _status.value.copy(busy = true, message = null)
        val startedAt = System.currentTimeMillis()
        val outcome = runCatching {
            val json = UserStateStore(app).exportLibraryJson()
            when (cfg.type) {
                hd.kinoshka.app.data.local.CloudSyncType.YANDEX ->
                    uploadYandex(app, cfg.yandexToken!!, json)
                hd.kinoshka.app.data.local.CloudSyncType.WEBDAV ->
                    uploadWebDav(cfg, json)
                hd.kinoshka.app.data.local.CloudSyncType.NONE -> error("Облако не подключено")
            }
        }
        val now = System.currentTimeMillis()
        outcome.fold(
            onSuccess = {
                store.setLastSync(now, "Выгружено")
                _status.value = _status.value.copy(busy = false, lastSyncAt = now, lastResult = "Выгружено")
            },
            onFailure = { e ->
                val msg = "Ошибка выгрузки: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, "upload failed", e)
                store.setLastSync(startedAt, msg)
                _status.value = _status.value.copy(busy = false, message = msg, lastResult = msg, lastSyncAt = startedAt)
            }
        )
    }

    private suspend fun runRestore(app: Context) {
        if (_status.value.busy) return
        val store = CloudSyncStore(app)
        val cfg = store.getConfig()
        if (!cfg.isConnected) {
            _status.value = _status.value.copy(message = "Облако не подключено")
            return
        }
        _status.value = _status.value.copy(busy = true, message = null)
        val outcome = runCatching {
            val json = when (cfg.type) {
                hd.kinoshka.app.data.local.CloudSyncType.YANDEX ->
                    downloadYandex(app, cfg.yandexToken!!)
                hd.kinoshka.app.data.local.CloudSyncType.WEBDAV ->
                    downloadWebDav(cfg)
                hd.kinoshka.app.data.local.CloudSyncType.NONE -> error("Облако не подключено")
            } ?: error("В облаке ещё нет резервной копии")
            val result = UserStateStore(app).importLibraryJson(json)
            if (result.isFailure) error("Повреждённый файл копии: ${result.exceptionOrNull()?.message}")
            val profiles = JSONObject(json).optJSONArray("profiles")?.length() ?: 0
            "Восстановлено тайтлов: $profiles"
        }
        val now = System.currentTimeMillis()
        outcome.fold(
            onSuccess = { msg ->
                store.setLastSync(now, msg)
                _status.value = _status.value.copy(busy = false, message = msg, lastResult = msg, lastSyncAt = now)
            },
            onFailure = { e ->
                val msg = "Ошибка восстановления: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, "restore failed", e)
                _status.value = _status.value.copy(busy = false, message = msg)
            }
        )
    }

    // ------------------------------------------------------------------
    // Yandex Disk REST
    // ------------------------------------------------------------------

    private fun yandexPath(): String = "app:/$BACKUP_DIR/$BACKUP_FILE"

    private suspend fun uploadYandex(app: Context, token: String, json: String) {
        // Parent folder is created once; 409 (already exists) is fine.
        // yandexCall refreshes the token internally on 401/403 and retries.
        yandexCall(app, token) { t ->
            val mk = http.newCall(Request.Builder()
                .url("https://cloud-api.yandex.net/v1/disk/resources?path=${enc("app:/$BACKUP_DIR")}")
                .header("Authorization", "OAuth $t")
                .put(byteArrayOf().toRequestBody(null))
                .build()).execute()
            mk.body?.close()
            if (mk.code in 401..403) null else true
        }
        val uploadHref = yandexCall(app, token) { t ->
            val resp = http.newCall(Request.Builder()
                .url("https://cloud-api.yandex.net/v1/disk/resources/upload?path=${enc(yandexPath())}&overwrite=true")
                .header("Authorization", "OAuth $t")
                .get().build()).execute()
            resp.use {
                if (it.code == 401 || it.code == 403) return@yandexCall null
                val body = it.body?.string().orEmpty()
                if (it.code != 200) error("Яндекс Диск (upload link): HTTP ${it.code} ${body.take(200)}")
                JSONObject(body).getString("href")
            }
        } ?: error("Яндекс Диск: ошибка авторизации")
        // The storage href is pre-signed; no Authorization header on this hop.
        val put = http.newCall(Request.Builder()
            .url(uploadHref)
            .put(json.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()).execute()
        put.body?.close()
        if (!put.isSuccessful) error("Яндекс Диск (upload): HTTP ${put.code}")
    }

    private suspend fun downloadYandex(app: Context, token: String): String? {
        val href = yandexCall(app, token) { t ->
            val resp = http.newCall(Request.Builder()
                .url("https://cloud-api.yandex.net/v1/disk/resources/download?path=${enc(yandexPath())}")
                .header("Authorization", "OAuth $t")
                .get().build()).execute()
            resp.use {
                if (it.code == 401 || it.code == 403) return@yandexCall null
                if (it.code == 404) return@yandexCall ""
                val body = it.body?.string().orEmpty()
                if (it.code != 200) error("Яндекс Диск (download link): HTTP ${it.code} ${body.take(200)}")
                JSONObject(body).optString("href").ifBlank { "" }
            }
        }.let { it ?: return null }
        if (href.isEmpty()) return null
        val get = http.newCall(Request.Builder().url(href).get().build()).execute()
        return get.use {
            if (it.code == 404) null
            else if (!it.isSuccessful) error("Яндекс Диск (download): HTTP ${it.code}")
            else it.body?.string()
        }
    }

    /**
     * Runs the block with a valid token; on 401/403 refreshes once and retries.
     * The block returns null only when it hit an auth error itself.
     */
    private suspend fun <T> yandexCall(app: Context, token: String, block: suspend (String) -> T): T {
        val first = block(token)
        val authFailed = first == null
        if (!authFailed) return first
        val refreshed = refreshYandexToken(app) ?: error("Токен Яндекса истёк — подключите Яндекс Диск заново")
        return block(refreshed) ?: error("Яндекс Диск: ошибка авторизации после обновления токена")
    }

    private suspend fun exchangeYandexCode(code: String): JSONObject? = withContext(Dispatchers.IO) {
        val form = okhttp3.FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", BuildConfig.YANDEX_DISK_CLIENT_ID)
            .add("client_secret", BuildConfig.YANDEX_DISK_CLIENT_SECRET)
            .build()
        val resp = http.newCall(Request.Builder().url("https://oauth.yandex.ru/token").post(form).build()).execute()
        resp.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("OAuth Яндекс: HTTP ${it.code} ${body.take(200)}")
            JSONObject(body).takeIf { json -> json.optString("access_token").isNotBlank() }
        }
    }

    private suspend fun refreshYandexToken(app: Context): String? = withContext(Dispatchers.IO) {
        val store = CloudSyncStore(app)
        val refresh = store.getConfig().yandexRefresh ?: return@withContext null
        runCatching {
            val form = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", BuildConfig.YANDEX_DISK_CLIENT_ID)
                .add("client_secret", BuildConfig.YANDEX_DISK_CLIENT_SECRET)
                .build()
            val resp = http.newCall(Request.Builder().url("https://oauth.yandex.ru/token").post(form).build()).execute()
            resp.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) return@withContext null
                val json = JSONObject(body)
                val token = json.optString("access_token").ifBlank { return@withContext null }
                store.updateYandexToken(token, json.optString("refresh_token").ifBlank { refresh })
                token
            }
        }.onFailure { Log.e(TAG, "Yandex refresh failed", it) }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Generic WebDAV
    // ------------------------------------------------------------------

    private suspend fun uploadWebDav(cfg: hd.kinoshka.app.data.local.CloudSyncConfig, json: String) {
        val url = cfg.webDavUrl ?: error("WebDAV не настроен")
        val auth = Credentials.basic(cfg.webDavUser.orEmpty(), cfg.webDavPassword.orEmpty())
        val fileUrl = url + BACKUP_FILE
        val parentUrl = url

        // Best-effort collection creation — servers answer 405/409 when it already exists.
        val mkcol = http.newCall(Request.Builder()
            .url(parentUrl).method("MKCOL", byteArrayOf().toRequestBody(null))
            .header("Authorization", auth).build()).execute()
        mkcol.body?.close()

        val put = http.newCall(Request.Builder()
            .url(fileUrl)
            .put(json.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .header("Authorization", auth).build()).execute()
        put.body?.close()
        if (!put.isSuccessful) error("WebDAV (upload): HTTP ${put.code}")
    }

    private suspend fun downloadWebDav(cfg: hd.kinoshka.app.data.local.CloudSyncConfig): String? {
        val url = cfg.webDavUrl ?: error("WebDAV не настроен")
        val auth = Credentials.basic(cfg.webDavUser.orEmpty(), cfg.webDavPassword.orEmpty())
        val get = http.newCall(Request.Builder()
            .url(url + BACKUP_FILE)
            .header("Authorization", auth).get().build()).execute()
        return get.use {
            if (it.code == 404) null
            else if (!it.isSuccessful) error("WebDAV (download): HTTP ${it.code}")
            else it.body?.string()
        }
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
