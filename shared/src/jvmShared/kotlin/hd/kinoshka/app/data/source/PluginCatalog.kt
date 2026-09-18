package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Каталог JS-плагинов (вариант C): витрина `index.json` в публичном
 * GitHub-репозитории (по умолчанию — kinoshka-plugins) + установка в один тап.
 *
 * Установка идёт по тем же рельсам, что ручная (скачивание → manifest →
 * проба → [JsPluginStore.saveCode][UserStateStoreBase.savePluginSource]),
 * плюс сверка `sha256` кода с витриной: подменённый на хостинге код
 * не встанет. Свой URL витрины — в настройках (тот же формат).
 */
object PluginCatalog {
    private const val TAG = "PluginCatalog"

    const val OFFICIAL_INDEX_URL =
        "https://raw.githubusercontent.com/HalfyDay/kinoshka-plugins/main/index.json"
    const val CACHE_FILE_NAME = "catalog_index.json"
    const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    data class Cached(val index: Index, val fresh: Boolean)

    @Volatile
    private var filesDir: java.io.File? = null

    /** Инициализация — как у [JsPluginStore.init] (тот же каталог приложения). */
    fun init(filesDir: java.io.File?) {
        this.filesDir = filesDir
    }

    private fun cacheFile(): java.io.File? {
        val root = filesDir ?: return null
        return java.io.File(java.io.File(root, "js_plugins").apply { mkdirs() }, CACHE_FILE_NAME)
    }

    /**
     * Последний скачанный индекс (офлайн-подложка). Протухший отдаём
     * с fresh=false (показываем с пометкой «офлайн»).
     */
    fun loadCachedIndex(maxAgeMs: Long = CACHE_TTL_MS): Cached? {
        val file = cacheFile()?.takeIf { it.isFile } ?: return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val index = parseIndex(raw) ?: return null
        val fresh = System.currentTimeMillis() - file.lastModified() < maxAgeMs
        return Cached(index, fresh)
    }

    fun saveCachedIndex(raw: String) {
        val file = cacheFile() ?: return
        runCatching { file.writeText(raw.take(512 * 1024), Charsets.UTF_8) }
            .onFailure { KLog.w(TAG, "cache save failed: ${it.javaClass.simpleName}") }
    }

    fun evictCache() {
        runCatching { cacheFile()?.takeIf { it.isFile }?.delete() }
    }

    data class Entry(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val version: String,
        val codeUrl: String,
        val sha256: String,
        val sections: Set<SourceCategory>,
        val minAppVersion: String,
        val verified: Boolean
    )

    data class Index(
        val format: Int,
        val updatedAt: String,
        val entries: List<Entry>
    )

    /** Терпимый парс витрины: битые записи скипаются, а не роняют весь список. */
    fun parseIndex(raw: String?): Index? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("format", 1) != 1) {
                KLog.w(TAG, "unsupported catalog format ${root.optInt("format")}")
                return null
            }
            val arr = root.optJSONArray("plugins") ?: return null
            val entries = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                entryOrNull(arr.optJSONObject(i))?.let { entries += it }
            }
            Index(1, root.optString("updatedAt"), entries)
        }.getOrNull()
    }

    private fun entryOrNull(o: JSONObject?): Entry? {
        if (o == null) return null
        val id = o.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
        if (!id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return null
        val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        val version = o.optString("version").trim().takeIf { it.isNotEmpty() } ?: return null
        val codeUrl = o.optString("codeUrl").trim().takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: return null
        return Entry(
            id = id,
            name = name,
            author = o.optString("author").trim(),
            description = o.optString("description").trim(),
            version = version,
            codeUrl = codeUrl,
            sha256 = o.optString("sha256").trim().lowercase(),
            sections = sectionsOf(o),
            minAppVersion = o.optString("minAppVersion").trim(),
            verified = o.optBoolean("verified", false)
        )
    }

    private fun sectionsOf(o: JSONObject): Set<SourceCategory> {
        val arr = o.optJSONArray("sections") ?: return setOf(SourceCategory.FILMS)
        val out = arr.let { a ->
            (0 until a.length()).mapNotNull { i ->
                runCatching { SourceCategory.valueOf(a.optString(i).trim().uppercase()) }.getOrNull()
            }.toSet()
        }
        return out.ifEmpty { setOf(SourceCategory.FILMS) }
    }

    /**
     * Стабильный id реестра для записи каталога: переустановка и обновления
     * попадают в ту же строку (код/версия перезаписываются, очередь не плодится).
     */
    fun registryIdFor(catalogId: String): String {
        val slug = slugifyCustomName(catalogId).ifEmpty { "plugin" }
        return CustomSource.ID_PREFIX + slug.uppercase()
    }

    /**
     * Слот установки/обновления: уже стоящая строка той же записи (перезапись
     * кода и версии) либо свежий id. Отдельно от [entryToCustomSource], чтобы
     * обновление не плодило строки-дубликаты.
     */
    fun targetId(entry: Entry, customs: List<CustomSource>): String {
        customs.firstOrNull { matchesEntry(it, entry) }?.let { return it.id }
        return entryToCustomSource(entry, customs.map { it.id }.toSet()).id
    }

    /** Запись витрины → строка реестра (вид PLUGIN, разделы и версия из витрины). */
    fun entryToCustomSource(entry: Entry, existingIds: Set<String>): CustomSource {
        val base = registryIdFor(entry.id)
        var id = base
        var n = 2
        // Слот уже занят чужим источником — не затираем, уходим на суффикс.
        while (id in existingIds) {
            id = "$base-$n"
            n++
        }
        return CustomSource(
            id = id,
            name = entry.name,
            urlTemplate = "",
            categories = entry.sections.ifEmpty { setOf(SourceCategory.FILMS) },
            kind = CustomSourceKind.PLUGIN,
            endpoint = entry.codeUrl,
            pluginVersion = entry.version
        )
    }

    /** Совпадение записи реестра с записью витрины (тот же слот обновления). */
    fun matchesEntry(source: CustomSource, entry: Entry): Boolean =
        source.kind == CustomSourceKind.PLUGIN &&
            (source.id == registryIdFor(entry.id) || source.endpoint.trim() == entry.codeUrl.trim())

    /** Есть обновление: строка стоит, а версия в витрине новее (строки сравниваем как есть). */
    fun hasUpdate(installed: CustomSource?, entry: Entry): Boolean {
        if (installed == null) return false
        if (!matchesEntry(installed, entry)) return false
        return installed.pluginVersion != entry.version
    }

    fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Годится ли витринная запись под версию приложения: скрываем/блочим
     * установку, когда приложение старее требуемого (сравнение по числам).
     */
    fun isAppVersionOk(entry: Entry, appVersion: String): Boolean {
        val min = entry.minAppVersion.trim().takeIf { it.isNotEmpty() } ?: return true
        return compareVersions(appVersion, min) >= 0
    }

    /** Сравнение версий по числовым кускам ("1.1.5" < "1.1.15"); мусор = 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-", "_").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val pb = b.split(".", "-", "_").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Скачивание витрины тем же http-стеком (прокси/кулдаун/таймауты). null — сеть/HTTP битые. */
    suspend fun fetchIndex(
        url: String,
        http: JsSandbox.HostHttp = JsSandbox.appHttp
    ): String? = withContext(Dispatchers.IO) {
        val clean = url.trim().takeIf { it.isNotEmpty() } ?: return@withContext null
        runCatching {
            val r = http.get(clean, emptyMap())
            if (r.status != 200 || r.body.isBlank()) {
                KLog.w(TAG, "index ${clean.take(90)} -> status=${r.status} err=${r.error}")
                return@runCatching null
            }
            r.body
        }.getOrNull()
    }

    /**
     * Установка/обновление из витрины одной операцией: код с хешем → manifest →
     * живая проба Матрицы → запись в слот [targetId]. [save] — стор платформы
     * (возвращает false, когда код не записался). Совпадение версии кода
     * с витриной обязательно: рассинхрон = витрина протухла, ставить нельзя.
     */
    sealed interface InstallResult {
        data class Installed(val version: String, val updated: Boolean) : InstallResult
        data class Rejected(val message: String) : InstallResult
    }

    suspend fun installFromEntry(
        entry: Entry,
        customs: List<CustomSource>,
        save: (CustomSource, String) -> Boolean,
        http: JsSandbox.HostHttp = JsSandbox.appHttp
    ): InstallResult = withContext(Dispatchers.IO) {
        val code = downloadVerified(entry, http)
            ?: return@withContext InstallResult.Rejected("Код не скачался или хеш не сошёлся")
        val rawManifest = JsSandbox.readManifest(code)
        if (rawManifest is JsSandbox.JsCallResult.Failed) {
            return@withContext InstallResult.Rejected("Манифест: ${rawManifest.reason}")
        }
        val manifest = JsSandbox.parseManifest((rawManifest as JsSandbox.JsCallResult.Ok).json)
            ?: return@withContext InstallResult.Rejected("Манифест не распознан")
        if (manifest.version != entry.version) {
            return@withContext InstallResult.Rejected(
                "Версия кода v${manifest.version} ≠ витрины v${entry.version}"
            )
        }
        val probe = JsPluginResolver.probeWithCode(
            CustomSource(id = "CUSTOM_DRAFT", name = entry.name, urlTemplate = ""),
            code
        )
        if (!probe.first) return@withContext InstallResult.Rejected(probe.second)
        val id = targetId(entry, customs)
        val updated = customs.any { matchesEntry(it, entry) }
        val src = CustomSource(
            id = id,
            name = entry.name,
            urlTemplate = "",
            categories = entry.sections.ifEmpty { setOf(SourceCategory.FILMS) },
            kind = CustomSourceKind.PLUGIN,
            endpoint = entry.codeUrl,
            pluginVersion = manifest.version
        )
        if (!save(src, code)) {
            return@withContext InstallResult.Rejected("Код не записался на устройство")
        }
        InstallResult.Installed(manifest.version, updated)
    }

    /**
     * Скачивание кода записи + сверка хеша витрины. Пустой sha256 в записи —
     * только для своих (custom URL) витрин: проверка пропускается.
     */
    suspend fun downloadVerified(
        entry: Entry,
        http: JsSandbox.HostHttp = JsSandbox.appHttp
    ): String? = withContext(Dispatchers.IO) {
        val code = JsPluginStore.downloadCode(entry.codeUrl, fetch = { url ->
            val r = http.get(url, emptyMap())
            if (r.status != 200 || r.body.isBlank()) null else r.body
        }) ?: return@withContext null
        if (entry.sha256.isNotBlank() && sha256Hex(code) != entry.sha256) {
            KLog.w(TAG, "${entry.id}: sha256 mismatch, code rejected")
            return@withContext null
        }
        code
    }
}
