package hd.kinoshka.app.data.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Пользовательский источник фильмов/сериалов.
 *
 * Вариант A (EMBED): только шаблон embed-ссылки с плейсхолдерами, без кода. Резолв идёт
 * по тем же рельсам, что встроенные прямые (fetch embed → host-agnostic
 * [DdbbStreamResolver.extractFromEmbed] → SourceParse; не извлеклось — веб-режим).
 *
 * Вариант B (STREMIO): Stremio-совместимый JSON-аддон (свой или чужой публичный):
 * manifest.json описывает ресурсы, потоки фильма тянутся с
 * `{base}/stream/movie/{imdb}.json`. Только фильмы с IMDb ID (сериалам нужен
 * поэпизодный запрос, аниме/18+ мост им не положен — см. диалог).
 */
@Serializable
enum class CustomSourceKind(val title: String) {
    EMBED("Embed-ссылка"),
    STREMIO("Stremio JSON")
}

@Serializable
data class CustomSource(
    /** CUSTOM_<slug>, uppercase. */
    val id: String,
    val name: String,
    /** EMBED: https://host/embed/kp/{kp} — плейсхолдеры [KP_PLACEHOLDER]/[IMDB_PLACEHOLDER]. */
    val urlTemplate: String,
    /** Пусто = origin embed-хоста. */
    val referer: String = "",
    val useProxy: Boolean = false,
    /** true = извлечение не пытаться, сразу веб-режим. */
    val webOnly: Boolean = false,
    /**
     * Разделы источника. Дефолт FILMS — старые записи (без поля) остаются киношными;
     * пустой сет трактуется так же (защита от битых правок). STREMIO всегда FILMS.
     */
    val categories: Set<SourceCategory> = setOf(SourceCategory.FILMS),
    /** Вариант источника (дефолт EMBED — старые записи без поля). */
    val kind: CustomSourceKind = CustomSourceKind.EMBED,
    /** STREMIO: transport URL аддона (https://host[:port][/path], хвост /manifest.json необязателен). */
    val endpoint: String = ""
) {
    companion object {
        const val ID_PREFIX = "CUSTOM_"
        const val KP_PLACEHOLDER = "{kp}"
        const val IMDB_PLACEHOLDER = "{imdb}"

        /** Тестовые id для валидации шаблона (Матрица). */
        const val PROBE_KP = 301
        const val PROBE_IMDB = "tt0133093"

        fun isCustomId(id: String): Boolean =
            id.trim().uppercase().startsWith(ID_PREFIX)
    }
}

sealed interface CustomSourceCheck {
    data class Ok(val warnings: List<String> = emptyList()) : CustomSourceCheck
    data class Failed(val message: String) : CustomSourceCheck
}

/**
 * Проверка полей перед сохранением. [existing] — все кастомные (включая редактируемый),
 * [selfId] — id редактируемого (самому себе не конфликтовать), [builtInNames] —
 * отображаемые имена встроенных ([PlaybackSources.ALL.map { it.displayName }]).
 */
fun validateCustomSource(
    name: String,
    urlTemplate: String,
    existing: List<CustomSource>,
    selfId: String? = null,
    builtInNames: List<String> = emptyList(),
    categories: Set<SourceCategory> = setOf(SourceCategory.FILMS),
    webOnly: Boolean = false,
    kind: CustomSourceKind = CustomSourceKind.EMBED,
    endpoint: String = ""
): CustomSourceCheck {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return CustomSourceCheck.Failed("Укажите название источника")
    if (cleanName.length > 40) return CustomSourceCheck.Failed("Название длиннее 40 символов")
    val others = existing.filter { it.id != selfId }
    if (others.any { it.name.trim().equals(cleanName, ignoreCase = true) }) {
        return CustomSourceCheck.Failed("Источник с таким названием уже есть")
    }
    if (builtInNames.any { it.equals(cleanName, ignoreCase = true) }) {
        return CustomSourceCheck.Failed("Такое имя занято встроенным источником")
    }
    if (kind == CustomSourceKind.STREMIO) {
        return validateStremioEndpoint(endpoint, others)
    }
    val template = urlTemplate.trim()
    if (template.isEmpty()) return CustomSourceCheck.Failed("Укажите шаблон ссылки")
    if (CustomSource.KP_PLACEHOLDER !in template && CustomSource.IMDB_PLACEHOLDER !in template) {
        return CustomSourceCheck.Failed("Шаблон должен содержать {kp} или {imdb}")
    }
    val probe = substitutePlaceholders(template, CustomSource.PROBE_KP, CustomSource.PROBE_IMDB)
        ?: return CustomSourceCheck.Failed("Некорректная ссылка после подстановки")
    val scheme = probe.substringBefore("://", "")
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
        return CustomSourceCheck.Failed("Только http(s)-ссылки")
    }
    val host = urlHost(probe)
    if (host == null || '.' !in host) return CustomSourceCheck.Failed("Некорректный хост в шаблоне")
    val normalized = normalizeTemplate(template)
    if (others.any { normalizeTemplate(it.urlTemplate) == normalized }) {
        return CustomSourceCheck.Failed("Такой шаблон уже добавлен")
    }
    val warnings = mutableListOf<String>()
    if (SourceCategory.ANIME in categories || SourceCategory.ADULT in categories) {
        // У аниме/18+ нет imdb, а kinopoisk id берётся из прямого id пикера
        // (у аниме — ещё и через мост Kodik, у 18+ моста нет).
        if (CustomSource.KP_PLACEHOLDER !in template) {
            warnings += "Разделы «Аниме» и «18+» работают только с {kp} — без него источник там пропускается"
        }
        if (webOnly) {
            warnings += "«Только веб-плеер» в разделах «Аниме» и «18+» не поддерживается — там источник пропускается"
        }
    }
    if (builtInNames.isNotEmpty()) {
        // Совпадение хоста со встроенным — не блокер, но почти наверняка дубликат.
        if (host.equals("p2.ddbb.lol", true) || host.equals("ddbb.lol", true) ||
            host.equals("kodik-api.com", true) || host.equals("rezka.ag", true)
        ) {
            warnings += "Похоже на встроенный провайдер — проверьте, что это не дубликат"
        }
    }
    return CustomSourceCheck.Ok(warnings)
}

/** Нормализация transport URL Stremio-аддона: без хвостового /manifest.json и /; null — кривой адрес. */
fun normalizeStremioEndpoint(endpoint: String): String? {
    var s = endpoint.trim().trimEnd('/')
    if (s.isEmpty()) return null
    if ("{" in s || "}" in s) return null
    val tail = "/manifest.json"
    if (s.length > tail.length && s.substring(s.length - tail.length).equals(tail, ignoreCase = true)) {
        s = s.substring(0, s.length - tail.length).trimEnd('/')
    }
    if (s.isEmpty()) return null
    val scheme = s.substringBefore("://", "")
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
    val host = urlHost(s)
    if (host == null || '.' !in host) return null
    return s
}

/** Проверка адреса Stremio-аддона перед сохранением (без сетевых запросов — они в «Проверить»). */
fun validateStremioEndpoint(endpoint: String, existing: List<CustomSource>): CustomSourceCheck {
    val raw = endpoint.trim()
    if (raw.isEmpty()) return CustomSourceCheck.Failed("Укажите адрес Stremio-аддона")
    if ("{" in raw || "}" in raw) {
        return CustomSourceCheck.Failed("Адрес аддона — без плейсхолдеров {kp}/{imdb}")
    }
    val base = normalizeStremioEndpoint(raw)
        ?: return CustomSourceCheck.Failed("Некорректный адрес: нужен http(s)-URL с доменом")
    if (existing.any {
            it.kind == CustomSourceKind.STREMIO && normalizeStremioEndpoint(it.endpoint) == base
        }
    ) {
        return CustomSourceCheck.Failed("Такой аддон уже добавлен")
    }
    // Двухшаговый гейт диалога: первое «Сохранить» показывает это, второе — сохраняет.
    return CustomSourceCheck.Ok(
        listOf("Stremio: только фильмы с IMDb ID; сериалы и разделы Аниме/18+ не поддерживаются")
    )
}

/** Подстановка плейсхолдеров; null, когда нужного id нет или шаблон кривой. */
fun substitutePlaceholders(template: String, kinopoiskId: Int?, imdbId: String?): String? {
    var url = template.trim()
    if (url.isEmpty()) return null
    if (CustomSource.KP_PLACEHOLDER in url) {
        if (kinopoiskId == null || kinopoiskId <= 0) return null
        url = url.replace(CustomSource.KP_PLACEHOLDER, kinopoiskId.toString())
    }
    if (CustomSource.IMDB_PLACEHOLDER in url) {
        if (imdbId.isNullOrBlank()) return null
        url = url.replace(CustomSource.IMDB_PLACEHOLDER, imdbId.trim())
    }
    if ("{" in url || "}" in url) return null
    return url
}

/** Готовая embed-ссылка под тайтл; null — у тайтла нет нужного id. */
fun CustomSource.buildUrl(kinopoiskId: Int?, imdbId: String?): String? =
    substitutePlaceholders(urlTemplate, kinopoiskId, imdbId)

/** Хост шаблона (плейсхолдеры в хосте не поддерживаются — валидация это гарантирует). */
fun CustomSource.embedHost(): String? = urlHost(urlTemplate.trim())

/** Transport base Stremio-аддона; null — адрес не задан/кривой. */
fun CustomSource.stremioBase(): String? =
    if (kind != CustomSourceKind.STREMIO) null else normalizeStremioEndpoint(endpoint)

/** Хост Stremio-аддона (для подписей и прокси-регистрации). */
fun CustomSource.stremioHost(): String? = urlHost(stremioBase() ?: return null)

/** Referer для запросов/плеера: явный либо origin embed-ссылки. */
fun CustomSource.effectiveReferer(embedUrl: String): String {
    val clean = referer.trim()
    if (clean.isNotEmpty()) return clean
    val scheme = embedUrl.substringBefore("://", "https")
    val host = urlHost(embedUrl) ?: return "https://ddbb.lol/"
    return "$scheme://$host/"
}

/** Нормализация шаблона для детекта дубликатов: scheme+host в нижнем, без query/fragment. */
fun normalizeTemplate(template: String): String {
    val s = template.trim()
    val schemeEnd = s.indexOf("://")
    if (schemeEnd < 0) return s.lowercase()
    val scheme = s.substring(0, schemeEnd).lowercase()
    val rest = s.substring(schemeEnd + 3)
    val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val host = (if (hostEnd < 0) rest else rest.substring(0, hostEnd)).lowercase()
    var path = if (hostEnd < 0) "" else rest.substring(hostEnd)
    path = path.substringBefore('?').substringBefore('#')
    return "$scheme://$host$path"
}

fun urlHost(url: String): String? {
    val s = url.trim()
    val schemeEnd = s.indexOf("://")
    if (schemeEnd < 0) return null
    val rest = s.substring(schemeEnd + 3)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    return (if (end < 0) rest else rest.substring(0, end))
        .substringBefore(':').lowercase()
        .takeIf { it.isNotEmpty() }
}

/** CUSTOM_<slug> из имени; [existingIds] (uppercase) — для уникальности (суффикс -2…). */
fun buildCustomId(name: String, existingIds: Set<String>): String {
    val slug = slugifyCustomName(name).ifEmpty { "src" }
    var candidate = CustomSource.ID_PREFIX + slug.uppercase()
    var n = 2
    while (candidate in existingIds) {
        candidate = "${CustomSource.ID_PREFIX}${slug.uppercase()}-$n"
        n++
    }
    return candidate
}

fun slugifyCustomName(name: String): String {
    val out = StringBuilder()
    for (ch in name.trim().lowercase()) {
        when {
            ch in 'a'..'z' || ch in '0'..'9' -> out.append(ch)
            ch in TRANSLIT -> out.append(TRANSLIT[ch])
            ch == '-' || ch == '_' || ch == ' ' -> out.append('-')
            // остальное (пунктуация, эмодзи) — дропаем
        }
    }
    return out.toString().replace(Regex("-{2,}"), "-").trim('-').take(24)
}

private val TRANSLIT: Map<Char, String> = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
    'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
    'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
    'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
    'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
    'э' to "e", 'ю' to "yu", 'я' to "ya"
)

/**
 * Синк рантайма под сохранённые свои источники: реестр имен, прокси-хосты и
 * health-провайдер. Вызывать при старте приложения (Android/desktop);
 * сохранения/удаления через UserStateStore синкают реестр и прокси сами.
 */
fun syncCustomSourceRuntime(getSources: () -> List<CustomSource>) {
    val customs = runCatching { getSources() }.getOrDefault(emptyList())
    PlaybackSources.setCustomSourceInfos(customs.map { PlaybackSources.customInfo(it) })
    customs.filter { it.useProxy }.mapNotNull { it.embedHost() }
        .forEach { StreamProxyConfig.registerCustomHost(it) }
    SourceHealthChecker.customSourceProvider = getSources
    AnimeStreamResolver.customSourceProvider = getSources
}

private val customSourcesJson = Json { ignoreUnknownKeys = true }

/** Терпимое чтение: битые записи скипаются, а не роняют весь список. */
fun parseCustomSources(raw: String?): List<CustomSource> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = customSourcesJson.parseToJsonElement(raw)
            .let { it as? kotlinx.serialization.json.JsonArray }
            ?: return emptyList()
        array.mapNotNull { el ->
            val parsed = runCatching {
                customSourcesJson.decodeFromJsonElement(CustomSource.serializer(), el)
            }.getOrNull()
            parsed
                ?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                ?.takeIf {
                    it.urlTemplate.isNotBlank() ||
                        (it.kind == CustomSourceKind.STREMIO && it.endpoint.isNotBlank())
                }
                ?.let { ok -> ok.copy(id = ok.id.trim().uppercase()) }
        }
    }.getOrDefault(emptyList())
}

fun customSourcesToJson(sources: List<CustomSource>): String =
    customSourcesJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(CustomSource.serializer()),
        sources
    )
