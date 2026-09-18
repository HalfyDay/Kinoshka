package hd.kinoshka.app.data.source

import hd.kinoshka.app.util.log.KLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextAction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Песочница JS-плагинов (вариант C) на Rhino (чистая Java — Android и desktop).
 *
 * Скрипт видит только host-функции ([log], [httpGet], [httpPost]); весь Java-мир
 * закрыт deny-all ClassShutter + удалением LiveConnect-имён из scope. Защита от
 * зависших/жадных скриптов двойная: лимит инструкций и wall-timeout вызова.
 * Каждый вызов — свежие Context и scope (состояние между вызовами не течёт).
 */
object JsSandbox {
    private const val TAG = "JsSandbox"

    /** Версия контракта, которую обязан заявить manifest() плагина. */
    const val JS_API_VERSION = 1

    const val DEFAULT_INSTRUCTION_LIMIT = 20_000_000L
    const val DEFAULT_TIMEOUT_MS = 25_000L
    const val MAX_HTTP_BODY_CHARS = 512 * 1024

    /** Ошибка-прерыватель лимита CPU (ловится рантаймом вызова, не скриптом). */
    private class CpuLimitError : Error("JS CPU limit exceeded")

    data class HttpResult(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
        val error: String? = null
    )

    /** Сетевой host (продакшн — через стек приложения, в тестах — стаб). */
    interface HostHttp {
        fun get(url: String, headers: Map<String, String>): HttpResult
        fun post(url: String, body: String, headers: Map<String, String>): HttpResult
    }

    data class HostBindings(
        val http: HostHttp,
        val onLog: (String) -> Unit = { KLog.i(TAG, it) }
    )

    sealed interface JsCallResult {
        data class Ok(val json: String) : JsCallResult
        data class Failed(val reason: String) : JsCallResult
    }

    data class JsManifest(
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val api: Int
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hd.kinoshka.app.utils.DohFallbackDns)
            .proxySelector(StreamProxySelector())
            .proxyAuthenticator(StreamProxyConfig.okHttpProxyAuthenticator())
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Продакшн-сеть: таймауты/прокси/кулдаун как у резолва своих источников. */
    val appHttp: HostHttp = object : HostHttp {
        override fun get(url: String, headers: Map<String, String>): HttpResult =
            request(url, headers, null)

        override fun post(url: String, body: String, headers: Map<String, String>): HttpResult =
            request(url, headers, body)

        private fun request(url: String, headers: Map<String, String>, body: String?): HttpResult {
            val host = HostCooldown.hostOf(url)
            if (host.isNotEmpty() && HostCooldown.shouldSkip(host)) {
                return HttpResult(0, error = "host in cooldown")
            }
            return try {
                val builder = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Kinoshka/1.0")
                headers.forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true) && v.isNotBlank()) {
                        try { builder.header(k, v) } catch (_: IllegalArgumentException) { }
                    }
                }
                if (body != null) {
                    builder.post(body.toByteArray(Charsets.UTF_8).toRequestBody(null))
                }
                httpClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return HttpResult(response.code, error = "HTTP ${response.code}")
                    }
                    val text = response.body.string().take(MAX_HTTP_BODY_CHARS)
                    if (host.isNotEmpty()) HostCooldown.recordSuccess(host)
                    HttpResult(response.code, emptyMap(), text)
                }
            } catch (e: Exception) {
                if (host.isNotEmpty() && HostCooldown.isConnectivityFailure(e)) {
                    HostCooldown.recordFailure(host)
                }
                HttpResult(0, error = e.javaClass.simpleName)
            }
        }
    }

    private val timeoutPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "js-sandbox").apply { isDaemon = true }
    }

    /** Фабрика с deny-all shutter и счётчиком инструкций (инстанс на вызов — потокобезопасно). */
    private fun sandboxFactory(instructionLimit: Long): ContextFactory =
        object : ContextFactory() {
            private var used = 0L

            override fun makeContext(): Context =
                super.makeContext().apply {
                    // ART не грузит байткод, который Rhino генерит в compiled-режиме
                    // («can't load this type of class file»): только интерпретатор.
                    // Бонус: observeInstructionCount срабатывает надёжно именно в нём.
                    optimizationLevel = -1
                    setClassShutter { _ -> false }
                    instructionObserverThreshold = 10_000
                }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                used += instructionCount
                if (used > instructionLimit) throw CpuLimitError()
            }
        }

    /** Имена LiveConnect, удаляемые из scope поверх shutter (оборона в глубину). */
    private val BLOCKED_GLOBALS = arrayOf(
        "Packages", "java", "javax", "org", "com", "edu", "net",
        "JavaImporter", "JavaAdapter", "about"
    )

    private abstract class HostFunction(private val arity: Int) : BaseFunction() {
        override fun getArity(): Int = arity
        override fun getLength(): Int = arity
    }    private fun installHost(scope: ScriptableObject, bindings: HostBindings) {
        for (name in BLOCKED_GLOBALS) {
            ScriptableObject.deleteProperty(scope, name)
        }
        ScriptableObject.putProperty(scope, "log", object : HostFunction(1) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                bindings.onLog(args.firstOrNull()?.toString().orEmpty().take(500))
                return Context.getUndefinedValue()
            }
        })
        fun headersOf(value: Any?): Map<String, String> {
            val obj = value as? NativeObject ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (id in obj.ids) {
                val key = id.toString()
                val v = ScriptableObject.getProperty(obj, key)?.toString()
                if (key.isNotBlank() && !v.isNullOrBlank()) out[key] = v
            }
            return out
        }
        fun resultObject(cx: Context, scope: Scriptable, r: HttpResult): Scriptable {
            val o = cx.newObject(scope)
            ScriptableObject.putProperty(o, "status", r.status)
            ScriptableObject.putProperty(o, "body", r.body)
            ScriptableObject.putProperty(o, "error", r.error ?: "")
            val headers = cx.newObject(scope)
            r.headers.forEach { (k, v) -> ScriptableObject.putProperty(headers, k, v) }
            ScriptableObject.putProperty(o, "headers", headers)
            return o
        }
        ScriptableObject.putProperty(scope, "httpGet", object : HostFunction(2) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                val url = args.getOrNull(0)?.toString().orEmpty()
                if (url.isBlank()) {
                    return resultObject(cx, scope, HttpResult(0, error = "empty url"))
                }
                return resultObject(cx, scope, bindings.http.get(url, headersOf(args.getOrNull(1))))
            }
        })
        ScriptableObject.putProperty(scope, "httpPost", object : HostFunction(3) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                val url = args.getOrNull(0)?.toString().orEmpty()
                if (url.isBlank()) {
                    return resultObject(cx, scope, HttpResult(0, error = "empty url"))
                }
                val body = args.getOrNull(1)?.toString().orEmpty()
                return resultObject(cx, scope, bindings.http.post(url, body, headersOf(args.getOrNull(2))))
            }
        })
    }

    private fun runGuarded(
        timeoutMs: Long,
        instructionLimit: Long,
        bindings: HostBindings,
        block: (Context, Scriptable) -> Any?
    ): JsCallResult {
        val future = timeoutPool.submit<JsCallResult> {
            try {
                sandboxFactory(instructionLimit).call(ContextAction { cx: Context ->
                    val scope = cx.initStandardObjects()
                    installHost(scope, bindings)
                    when (val raw = block(cx, scope)) {
                        is JsCallResult -> raw
                        is String -> JsCallResult.Ok(raw)
                        else -> JsCallResult.Failed("function must return a JSON string")
                    }
                })
            } catch (e: CpuLimitError) {
                JsCallResult.Failed("CPU limit exceeded")
            } catch (e: RhinoException) {
                JsCallResult.Failed("JS error: ${(e.message ?: e.javaClass.simpleName).take(240)}")
            } catch (e: Exception) {
                JsCallResult.Failed("error: ${(e.message ?: e.javaClass.simpleName).take(240)}")
            }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            JsCallResult.Failed("timeout ${timeoutMs}ms")
        } catch (e: Exception) {
            JsCallResult.Failed("error: ${(e.message ?: e.javaClass.simpleName).take(240)}")
        }
    }

    /**
     * Вызов `funcName(argsJson)` из кода плагина. Аргументы и возврат — JSON-строки
     * (контракт прост и не зависит от маппинга Rhino-объектов).
     */
    fun callJsonFunction(
        code: String,
        funcName: String,
        argsJson: String,
        bindings: HostBindings = HostBindings(appHttp),
        instructionLimit: Long = DEFAULT_INSTRUCTION_LIMIT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): JsCallResult {
        if (code.length > 2 * 1024 * 1024) return JsCallResult.Failed("code too large")
        return runGuarded(timeoutMs, instructionLimit, bindings) { cx, scope ->
            cx.evaluateString(scope, code, "<plugin>", 1, null)
            val fn = scope.get(funcName, scope)
            if (fn !is org.mozilla.javascript.Function) {
                JsCallResult.Failed("no function $funcName")
            } else {
                fn.call(cx, scope, scope, arrayOf(argsJson))
            }
        }
    }

    /** manifest() плагина + валидация (имя 1–40, версия непустая, api == [JS_API_VERSION]). */
    fun readManifest(
        code: String,
        bindings: HostBindings = HostBindings(appHttp),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): JsCallResult {
        val raw = callJsonFunction(code, "manifest", "{}", bindings, timeoutMs = timeoutMs)
        if (raw is JsCallResult.Failed) return raw
        val json = (raw as JsCallResult.Ok).json
        return try {
            val o = org.json.JSONObject(json)
            val name = o.optString("name").trim()
            if (name.isEmpty() || name.length > 40) {
                return JsCallResult.Failed("bad manifest name")
            }
            val version = o.optString("version").trim()
            if (version.isEmpty()) return JsCallResult.Failed("bad manifest version")
            val api = o.optInt("kinoshkaApi", -1)
            if (api != JS_API_VERSION) {
                return JsCallResult.Failed("unsupported kinoshkaApi $api (need $JS_API_VERSION)")
            }
            // Возврат в каноническом виде для вызывающего (парсит тем же ключом).
            JsCallResult.Ok(
                org.json.JSONObject()
                    .put("name", name)
                    .put("version", version)
                    .put("author", o.optString("author").trim())
                    .put("description", o.optString("description").trim())
                    .put("kinoshkaApi", api)
                    .toString()
            )
        } catch (_: Exception) {
            JsCallResult.Failed("manifest is not JSON")
        }
    }

    /** Канонический парс возврата [readManifest] (null — не парсится). */
    fun parseManifest(json: String): JsManifest? = try {
        val o = org.json.JSONObject(json)
        JsManifest(
            name = o.getString("name"),
            version = o.getString("version"),
            author = o.optString("author"),
            description = o.optString("description"),
            api = o.getInt("kinoshkaApi")
        )
    } catch (_: Exception) {
        null
    }
}
