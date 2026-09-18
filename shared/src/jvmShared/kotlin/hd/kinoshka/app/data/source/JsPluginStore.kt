package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Файловое хранилище кода JS-плагинов (вариант C): `js_plugins/<CUSTOM_ID>.js`
 * под каталогом приложения. В префсах — только строка реестра [CustomSource]
 * (вид PLUGIN + версия из manifest); сам код в префсы не пишем, чтобы не
 * раздувать блоб настроек. Инициализация — как у [HentaiStreamResolver.init].
 */
object JsPluginStore {
    private const val TAG = "JsPluginStore"
    private const val DIR_NAME = "js_plugins"
    const val MAX_CODE_CHARS = 512 * 1024

    @Volatile
    private var filesDir: java.io.File? = null

    fun init(filesDir: java.io.File?) {
        this.filesDir = filesDir
    }

    /** In-memory кэш кода (id → код); инвалидируется записью/удалением. */
    private val codeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun codeFile(id: String): java.io.File? {
        val root = filesDir ?: return null
        // id реестра — CUSTOM_<slug> (латиница/цифры/дефис): безопасен как имя файла.
        val safe = id.trim().uppercase()
            .takeIf { it.isNotBlank() && CustomSource.isCustomId(it) }
            ?: return null
        return java.io.File(java.io.File(root, DIR_NAME).apply { mkdirs() }, "$safe.js")
    }

    fun saveCode(id: String, code: String): Boolean {
        val file = codeFile(id) ?: return false
        return runCatching {
            file.writeText(code.take(MAX_CODE_CHARS), Charsets.UTF_8)
            codeCache[id.trim().uppercase()] = code.take(MAX_CODE_CHARS)
            true
        }.onFailure {
            KLog.w(TAG, "save $id failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    fun loadCode(id: String): String? {
        val key = id.trim().uppercase()
        codeCache[key]?.let { return it }
        val file = codeFile(key) ?: return null
        return runCatching {
            file.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        }.onFailure {
            KLog.w(TAG, "load $key failed: ${it.javaClass.simpleName}")
        }.getOrNull()?.also { codeCache[key] = it }
    }

    fun deleteCode(id: String) {
        val key = id.trim().uppercase()
        codeCache.remove(key)
        runCatching { codeFile(key)?.takeIf { it.isFile }?.delete() }
    }

    fun evictMemoryCache() {
        codeCache.clear()
    }

    /** Скачивание кода по URL (http(s), как у валидации endpoint). */
    suspend fun downloadCode(
        url: String,
        fetch: (suspend (String) -> String?)? = null
    ): String? = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (clean.isEmpty()) return@withContext null
        if (fetch != null) return@withContext fetch(clean)?.take(MAX_CODE_CHARS)?.takeIf { it.isNotBlank() }
        val result = JsSandbox.appHttp.get(clean, emptyMap())
        if (result.status != 200 || result.body.isBlank()) {
            KLog.w(TAG, "download ${clean.take(90)} -> status=${result.status} err=${result.error}")
            return@withContext null
        }
        result.body.take(MAX_CODE_CHARS)
    }

    data class InstallDraft(val manifest: JsSandbox.JsManifest, val code: String)

    /**
     * Черновик установки: код + распарсенный manifest (без записи).
     * [fetch] инжектится ради тестов. null — URL/скачивание/manifest битые.
     */
    suspend fun installDraft(
        url: String,
        fetch: (suspend (String) -> String?)? = null
    ): InstallDraft? = withContext(Dispatchers.IO) {
        val code = downloadCode(url, fetch) ?: return@withContext null
        val raw = JsSandbox.readManifest(code)
        if (raw is JsSandbox.JsCallResult.Failed) {
            KLog.w(TAG, "manifest failed for ${url.take(90)}: ${raw.reason}")
            return@withContext null
        }
        val manifest = JsSandbox.parseManifest((raw as JsSandbox.JsCallResult.Ok).json)
            ?: return@withContext null
        InstallDraft(manifest, code)
    }
}
