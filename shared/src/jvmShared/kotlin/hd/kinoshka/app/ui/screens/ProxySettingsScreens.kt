package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Прокси в стиле Telegram: отдельными страницами, а не окном.
 *
 * - [ProxyListScreen] («Настройки прокси»): тумблер «Использовать прокси»,
 *   карточка «Подключения» со статусами и строкой «Добавить прокси».
 * - [ProxyEditScreen] («Прокси-сервер»): выбор типа, Сервер/Порт/Логин/Пароль,
 *   «Поделиться», сохранение галочкой в шапке.
 *
 * Типы — только те, что реально умеет приложение (HTTP/SOCKS5 через
 * StreamProxyConfig). MTProto из Telegram здесь сознательно нет: это протокол
 * только для Telegram, видеотрафик через него не проксируется.
 */

// ------------------------------------------------------------------
// Модель
// ------------------------------------------------------------------

enum class ProxyType(val title: String, val scheme: String) {
    SOCKS5("Прокси SOCKS5", "socks5"),
    HTTP("Прокси HTTP", "http");

    companion object {
        fun of(name: String?): ProxyType =
            values().firstOrNull { it.name == name } ?: SOCKS5
    }
}

data class ProxyConnection(
    val id: String,
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "",
    val port: Int = 1080,
    val login: String = "",
    val password: String = ""
) {
    fun displayAddress(): String =
        if (host.isBlank()) "Новый прокси" else "${host.trim()}:$port"

    /** URL для StreamProxyConfig / шаринга: `socks5://[login:pass@]host:port`. */
    fun toProxyUrl(): String {
        val h = host.trim()
        val auth = if (login.isNotBlank()) "${login.trim()}:${password}@" else ""
        return "${type.scheme}://$auth$h:$port".replace(" ", "")
    }

    /** Пустой хост — черновик, сохранять нельзя. */
    fun isValid(): Boolean {
        if (host.isBlank()) return false
        if (port !in 1..65535) return false
        if (host.contains(' ') || host.contains('@') || host.contains('/')) return false
        return true
    }
}

/** Статус подключения для подписи под адресом (как в Telegram). */
sealed interface ProxyCheckStatus {
    data object Unknown : ProxyCheckStatus
    data object Checking : ProxyCheckStatus
    data class Available(val detail: String? = null) : ProxyCheckStatus
    data class Unavailable(val detail: String? = null) : ProxyCheckStatus
}

fun proxyStatusText(status: ProxyCheckStatus): String = when (status) {
    is ProxyCheckStatus.Available -> "Доступен"
    is ProxyCheckStatus.Unavailable -> "Недоступен"
    is ProxyCheckStatus.Checking -> "Проверка…"
    is ProxyCheckStatus.Unknown -> "Не проверен"
}

@Composable
fun proxyStatusColor(status: ProxyCheckStatus): Color = when (status) {
    is ProxyCheckStatus.Available -> Color(0xFF34C759)
    is ProxyCheckStatus.Unavailable -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ------------------------------------------------------------------
// JSON-персист без новых зависимостей (ключи лежат в app-модуле).
// ------------------------------------------------------------------

fun generateProxyId(): String =
    "${System.currentTimeMillis().toString(36)}-${(0..9999).random().toString(36)}"

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

private fun String.unescapeJson(): String =
    replace("\\\"", "\"").replace("\\\\", "\\")

fun encodeProxiesToJson(list: List<ProxyConnection>): String = buildString {
    append('[')
    list.forEachIndexed { index, p ->
        if (index > 0) append(',')
        append(
            """{"id":"${p.id.escapeJson()}","type":"${p.type.name}","host":"${p.host.escapeJson()}", """ +
                """"port":${p.port},"login":"${p.login.escapeJson()}","password":"${p.password.escapeJson()}"}"""
        )
    }
    append(']')
}

private val proxyObjectRegex = Regex("""\{[^}]*\}""")
private fun proxyField(src: String, key: String): String? {
    val stringRe = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    stringRe.find(src)?.let { return it.groupValues[1].unescapeJson() }
    val numRe = Regex(""""$key"\s*:\s*(\d+)""")
    numRe.find(src)?.let { return it.groupValues[1] }
    return null
}

fun decodeProxiesFromJson(json: String): List<ProxyConnection> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        proxyObjectRegex.findAll(json).mapNotNull { match ->
            val src = match.value
            val id = proxyField(src, "id") ?: return@mapNotNull null
            val host = proxyField(src, "host").orEmpty()
            if (host.isBlank() && proxyField(src, "port") == null) return@mapNotNull null
            ProxyConnection(
                id = id,
                type = ProxyType.of(proxyField(src, "type")),
                host = host,
                port = proxyField(src, "port")?.toIntOrNull()?.coerceIn(1, 65535) ?: 1080,
                login = proxyField(src, "login").orEmpty(),
                password = proxyField(src, "password").orEmpty()
            )
        }.toList()
    }.getOrElse { emptyList() }
}

/** Миграция со старого одиночного `stream_proxy_url` в список. */
fun proxyFromUrl(raw: String): ProxyConnection? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    val type = if (lower.startsWith("socks5://") || lower.startsWith("socks://")) {
        ProxyType.SOCKS5
    } else {
        ProxyType.HTTP
    }
    val afterScheme = trimmed.substringAfter("://", trimmed)
    val authAndHost = afterScheme.substringBefore('/').substringBefore('?')
    val hostPort = authAndHost.substringAfterLast('@')
    val creds = if ('@' in authAndHost) authAndHost.substringBeforeLast('@') else ""
    val port = hostPort.substringAfterLast(':', "").toIntOrNull()
    val host = if (port != null) hostPort.substringBeforeLast(':') else hostPort
    if (host.isBlank()) return null
    val login = creds.substringBefore(':', "").takeIf { creds.contains(':') || creds.isNotEmpty() }
        ?.substringBefore(':').orEmpty()
    val password = if (':' in creds) creds.substringAfter(':', "") else ""
    return ProxyConnection(
        id = generateProxyId(),
        type = type,
        host = host,
        port = port ?: 1080,
        login = login,
        password = password
    )
}

// ------------------------------------------------------------------
// Страница 1: «Настройки прокси» (как первый скриншот Telegram)
// ------------------------------------------------------------------

@Composable
fun ProxyListScreen(
    onBack: () -> Unit,
    proxies: List<ProxyConnection>,
    activeId: String?,
    proxyEnabled: Boolean,
    statusMap: Map<String, ProxyCheckStatus> = emptyMap(),
    onToggleEnabled: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenDetails: (String) -> Unit
) {
    PinnedHeaderPage(
        title = "Настройки прокси",
        subtitle = if (proxies.isEmpty()) "Нет подключений" else "Подключений: ${proxies.size}",
        onBack = onBack
    ) { topPad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
        ) {
            item {
                KinoSettingsCard {
                    KinoSettingsRow(
                        title = "Использовать прокси",
                        trailing = {
                            Switch(checked = proxyEnabled, onCheckedChange = onToggleEnabled)
                        }
                    )
                }
            }
            item {
                KinoSettingsCard {
                    Text(
                        text = "Подключения",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    if (proxies.isEmpty()) {
                        Text(
                            text = "Пока пусто — добавьте прокси ниже",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    } else {
                        proxies.forEachIndexed { index, proxy ->
                            if (index > 0) KinoSettingsDivider()
                            ProxyConnectionRow(
                                proxy = proxy,
                                selected = proxy.id == activeId,
                                status = statusMap[proxy.id] ?: ProxyCheckStatus.Unknown,
                                onSelect = { onSelect(proxy.id) },
                                onOpenDetails = { onOpenDetails(proxy.id) }
                            )
                        }
                    }
                    KinoSettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAdd)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Добавить прокси",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyConnectionRow(
    proxy: ProxyConnection,
    selected: Boolean,
    status: ProxyCheckStatus,
    onSelect: () -> Unit,
    onOpenDetails: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = proxy.displayAddress(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = proxyStatusColor(status),
                        modifier = Modifier.padding(0.dp)
                    )
                }
                Text(
                    text = proxyStatusText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = proxyStatusColor(status)
                )
            }
        }
        IconButton(onClick = onOpenDetails) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Параметры прокси",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------
// Страница 2: «Прокси-сервер» (как второй/третий скриншоты Telegram)
// ------------------------------------------------------------------

@Composable
fun ProxyEditScreen(
    onBack: () -> Unit,
    initial: ProxyConnection?,
    onSave: (ProxyConnection) -> Unit,
    onDelete: (() -> Unit)? = null,
    onShare: ((String) -> Unit)? = null
) {
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: ProxyType.SOCKS5) }
    var host by remember(initial?.id) { mutableStateOf(initial?.host.orEmpty()) }
    var portText by remember(initial?.id) { mutableStateOf((initial?.port ?: 1080).toString()) }
    var login by remember(initial?.id) { mutableStateOf(initial?.login.orEmpty()) }
    var password by remember(initial?.id) { mutableStateOf(initial?.password.orEmpty()) }

    val port = portText.trim().toIntOrNull()
    val draft = ProxyConnection(
        id = initial?.id ?: "draft",
        type = type,
        host = host.trim(),
        port = port ?: 0,
        login = login.trim(),
        password = password
    )
    val valid = draft.isValid()

    PinnedHeaderPage(
        title = "Прокси-сервер",
        subtitle = if (initial == null) "Новое подключение" else "Параметры подключения",
        onBack = onBack,
        headerActions = {
            IconButton(onClick = { onSave(draft.copy(id = initial?.id ?: generateProxyId())) }, enabled = valid) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Сохранить",
                    tint = if (valid) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    ) { topPad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
        ) {
            // Тип прокси — радиокнопки, как в Telegram.
            item {
                KinoSettingsCard {
                    ProxyTypeRow(
                        title = ProxyType.SOCKS5.title,
                        selected = type == ProxyType.SOCKS5,
                        onClick = { type = ProxyType.SOCKS5 }
                    )
                    KinoSettingsDivider()
                    ProxyTypeRow(
                        title = ProxyType.HTTP.title,
                        selected = type == ProxyType.HTTP,
                        onClick = { type = ProxyType.HTTP }
                    )
                }
            }
            // Сервер / порт / логин / пароль — подчёркнутые поля, как в Telegram.
            item {
                KinoSettingsCard {
                    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        TelegramField(
                            label = "Сервер",
                            value = host,
                            onValueChange = { host = it },
                            singleLine = true
                        )
                        TelegramField(
                            label = "Порт",
                            value = portText,
                            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                            keyboardType = KeyboardType.Number,
                            singleLine = true
                        )
                        TelegramField(
                            label = "Логин",
                            value = login,
                            onValueChange = { login = it },
                            singleLine = true
                        )
                        TelegramField(
                            label = "Пароль",
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            isPassword = true
                        )
                    }
                }
            }
            item {
                Text(
                    text = if (type == ProxyType.SOCKS5) "Настройки SOCKS5-прокси."
                    else "Настройки HTTP-прокси.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
                )
            }
            item {
                KinoSettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = valid) {
                                if (valid) {
                                    val url = draft.copy(id = initial?.id ?: "draft").toProxyUrl()
                                    onShare?.invoke(url)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Поделиться",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (valid) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    if (initial != null && onDelete != null) {
                        KinoSettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onDelete)
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Удалить прокси",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyTypeRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun TelegramField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
