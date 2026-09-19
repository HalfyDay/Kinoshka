package hd.kinoshka.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import hd.kinoshka.app.data.download.DownloadBridges
import hd.kinoshka.app.data.download.DownloadPhase
import hd.kinoshka.app.data.download.DownloadTaskState
import hd.kinoshka.app.data.download.EpisodeDownloadManager
import hd.kinoshka.app.data.download.MediaDownloader
import hd.kinoshka.app.data.download.animeItemKey
import hd.kinoshka.app.data.download.downloadProgressText
import hd.kinoshka.app.data.download.formatBytes
import hd.kinoshka.app.data.download.offlineKey
import hd.kinoshka.app.data.download.progressPercent
import hd.kinoshka.app.data.download.tryRequestNotificationPermission
import hd.kinoshka.app.data.model.ANIME_ID_OFFSET
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import hd.kinoshka.app.data.playback.MovieNativeLauncher
import hd.kinoshka.app.data.playback.MovieNativeLauncher.NativeLaunchPayload
import hd.kinoshka.app.data.source.AniStarResolver
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.data.source.KodikMovieParser
import hd.kinoshka.app.data.source.RutrackerResolver
import hd.kinoshka.app.data.source.StreamProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Шит загрузки на странице тайтла. Две вкладки:
 *  «Торренты» — агрегатор раздач (AniLiberty + AniStar + Rutor для аниме,
 *  Rutor + Rutracker для фильмов/сериалов; Rutracker требует входа — вход
 *  через WebView прямо в шите (капча решается там же), пароль не хранится)
 *  с подробной информацией (диапазон серий, качество, вес, сиды, дата) и отдачей magnet/.torrent
 *  во внешний клиент;
 *  «В приложение» — скачивание серий/озвучек в офлайн-библиотеку приложения (EpisodeDownloadManager).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleDownloadSheet(
    item: FilmDetails,
    isAnime: Boolean,
    onDismiss: () -> Unit
) {
    val shikimoriId = if (isAnime && item.kinopoiskId > ANIME_ID_OFFSET) item.kinopoiskId - ANIME_ID_OFFSET else 0
    val itemKey = animeItemKey(shikimoriId, item.kinopoiskId)
    val displayTitle = item.nameRu ?: item.nameOriginal ?: item.nameEn ?: "Без названия"

    var tab by remember { mutableStateOf(0) }
    // Отложенное действие скачивания, ждущее выбора качества в диалоге.
    var qualityAsk by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val askQuality: ((String?) -> Unit) -> Unit = { action -> qualityAsk = action }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = "Загрузка",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryTabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Торренты") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("В приложение") })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(460.dp)) {
                if (tab == 0) {
                    TorrentsTab(item = item, isAnime = isAnime, shikimoriId = shikimoriId, displayTitle = displayTitle)
                } else {
                    OfflineTab(item = item, isAnime = isAnime, shikimoriId = shikimoriId, itemKey = itemKey, displayTitle = displayTitle, askQuality = askQuality)
                }
            }
        }
    }
    qualityAsk?.let { ask ->
        DownloadQualityDialog(
            onDismiss = { qualityAsk = null },
            onConfirm = { quality ->
                qualityAsk = null
                ask(quality)
            }
        )
    }
}

// ======================================================================
// Вкладка «Торренты»
// ======================================================================

@Composable
private fun TorrentsTab(
    item: FilmDetails,
    isAnime: Boolean,
    shikimoriId: Int,
    displayTitle: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var anilibertyLinks by remember { mutableStateOf<List<AnimeStreamResolver.TorrentLink>>(emptyList()) }
    var anistarLinks by remember { mutableStateOf<List<AnimeStreamResolver.TorrentLink>>(emptyList()) }
    var rutorLinks by remember { mutableStateOf<List<AnimeStreamResolver.TorrentLink>>(emptyList()) }
    var rutrackerLinks by remember { mutableStateOf<List<AnimeStreamResolver.TorrentLink>>(emptyList()) }
    var anilibertyDone by remember { mutableStateOf(false) }
    var anistarDone by remember { mutableStateOf(false) }
    var rutorDone by remember { mutableStateOf(false) }
    var rutrackerDone by remember { mutableStateOf(false) }
    var rutrackerLoggedIn by remember { mutableStateOf(RutrackerResolver.isLoggedIn()) }
    var rutrackerUser by remember { mutableStateOf(RutrackerResolver.savedUsername()) }
    var showRutrackerLogin by remember { mutableStateOf(false) }

    // Результаты показываются по мере готовности источников, как в пикере озвучек.
    LaunchedEffect(item.kinopoiskId, isAnime) {
        withContext(Dispatchers.IO) {
            if (isAnime) {
                launch {
                    anilibertyLinks = AnimeStreamResolver.fetchTorrents(
                        shikimoriId,
                        item.nameRu ?: item.nameOriginal ?: ""
                    )
                    anilibertyDone = true
                }
                launch {
                    anistarLinks = AniStarResolver
                        .fetchTorrents(AnimeStreamResolver.buildAnimeSearchQueries(displayTitle))
                        .map { t ->
                            AnimeStreamResolver.TorrentLink(
                                quality = "TV",
                                size = "?",
                                seeders = 0,
                                leechers = 0,
                                magnet = t.magnet,
                                torrentUrl = t.torrentUrl,
                                label = t.label,
                                source = "AniStar"
                            )
                        }
                    anistarDone = true
                }
                launch {
                    rutorLinks = AnimeStreamResolver.fetchFilmTorrents(displayTitle, null)
                    rutorDone = true
                }
            } else {
                launch {
                    rutorLinks = AnimeStreamResolver.fetchFilmTorrents(displayTitle, item.year?.toString())
                    rutorDone = true
                }
            }
        }
    }

    // Rutracker отдельно: требует логин, перезапускается после входа/выхода
    // без дёргания остальных источников.
    LaunchedEffect(item.kinopoiskId, isAnime, rutrackerLoggedIn) {
        rutrackerDone = false
        withContext(Dispatchers.IO) {
            if (RutrackerResolver.isLoggedIn()) {
                val queries = if (isAnime) {
                    AnimeStreamResolver.buildAnimeSearchQueries(displayTitle)
                } else {
                    val yearQuery = item.year?.let { "$displayTitle $it" }
                    listOfNotNull(yearQuery, displayTitle)
                }
                rutrackerLinks = RutrackerResolver.searchAll(queries)
            } else {
                rutrackerLinks = emptyList()
            }
            rutrackerDone = true
        }
    }

    val links = if (isAnime) anilibertyLinks + anistarLinks + rutorLinks + rutrackerLinks
    else rutorLinks + rutrackerLinks
    val settled = if (isAnime) anilibertyDone && anistarDone && rutorDone && rutrackerDone
    else rutorDone && rutrackerDone

    Column(modifier = Modifier.fillMaxSize()) {
        if (rutrackerLoggedIn) {
            RutrackerStatusRow(
                username = rutrackerUser,
                onLogout = {
                    scope.launch(Dispatchers.IO) { RutrackerResolver.logout() }
                    rutrackerLoggedIn = false
                    rutrackerUser = null
                    rutrackerLinks = emptyList()
                }
            )
        } else if (rutrackerDone) {
            RutrackerLoginHint(onLoginClick = { showRutrackerLogin = true })
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (links.isEmpty()) {
                item {
                    EmptyTorrentsHint(
                        isAnime = isAnime,
                        settled = settled,
                        rutrackerLoggedIn = rutrackerLoggedIn
                    )
                }
            } else {
                items(
                    links,
                    key = { "${it.source}:${it.torrentUrl ?: it.magnet}:${it.quality}:${it.size}" }
                ) { link ->
                    TorrentRow(
                        link = link,
                        onOpen = { uri ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showRutrackerLogin) {
        RutrackerLoginDialog(
            onDismiss = { showRutrackerLogin = false },
            onLoggedIn = { username ->
                showRutrackerLogin = false
                rutrackerLoggedIn = true
                rutrackerUser = username
            }
        )
    }
}

@Composable
private fun RutrackerLoginHint(onLoginClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Rutracker — раздачи после входа",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onLoginClick) { Text("Войти") }
        }
    }
}

@Composable
private fun RutrackerStatusRow(username: String?, onLogout: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Rutracker · ${username ?: "вход выполнен"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onLogout) { Text("Выйти") }
    }
}

@Composable
private fun RutrackerLoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var proxyNote by remember { mutableStateOf<String?>(null) }
    // onReceivedError приходит с параметром error — он затеняет состояние,
    // поэтому сеттер выносим в лямбду.
    val setLoginError: (String?) -> Unit = { error = it }
    // Авто-проверка — один раз: когда вместо формы логина открылась страница
    // без неё (успешный вход с капчей), подтверждаем сессию сами.
    var autoChecked by remember { mutableStateOf(false) }

    fun checkLogin() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val cookies = collectWebRutrackerCookies()
            if (cookies.none { it.name == "bb_session" }) {
                busy = false
                error = "Сессия не найдена — войдите на странице выше (включая капчу) и нажмите ещё раз"
                return@launch
            }
            RutrackerResolver.putWebCookies(cookies)
            val result = withContext(Dispatchers.IO) { RutrackerResolver.confirmWebSession() }
            busy = false
            if (result.ok) onLoggedIn(RutrackerResolver.savedUsername() ?: "") else error = result.message
        }
    }

    // WebView игнорирует OkHttp-прокси резолвера: при настроенном прокси заворачиваем
    // трекерные хосты через ProxyController (process-wide — снимаем при закрытии диалога).
    // Загрузка страницы стартует только после применения оверрайда.
    LaunchedEffect(Unit) {
        val wv = webViewRef ?: return@LaunchedEffect
        val executor = ContextCompat.getMainExecutor(context)
        val raw = StreamProxyConfig.proxyUrl?.trim().takeUnless { it.isNullOrEmpty() }
        if (raw == null) {
            wv.loadUrl(RUTRACKER_LOGIN_URL)
            return@LaunchedEffect
        }
        val parsed = StreamProxyConfig.parseProxy(raw)
        val addr = parsed?.address() as? InetSocketAddress
        if (parsed?.type() != Proxy.Type.HTTP || addr == null) {
            proxyNote = "SOCKS-прокси WebView не поддерживает — страница может не открыться. " +
                "Задайте HTTP-прокси или включите VPN"
            wv.loadUrl(RUTRACKER_LOGIN_URL)
            return@LaunchedEffect
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyNote = "Этот WebView не поддерживает прокси — включите системный VPN"
            wv.loadUrl(RUTRACKER_LOGIN_URL)
            return@LaunchedEffect
        }
        val authNote = if ('@' in raw) " (логин/пароль прокси WebView не использует)" else ""
        proxyNote = "Вход идёт через прокси ${addr.hostString}:${addr.port}$authNote"
        val config = ProxyConfig.Builder()
            .addProxyRule("http://${addr.hostString}:${addr.port}")
            .setReverseBypassEnabled(true)
            .apply { RUTRACKER_PROXY_HOSTS.forEach { addBypassRule(it) } }
            .build()
        runCatching {
            ProxyController.getInstance().setProxyOverride(config, executor) {
                webViewRef?.loadUrl(RUTRACKER_LOGIN_URL)
            }
        }.onFailure {
            wv.loadUrl(RUTRACKER_LOGIN_URL)
        }
    }
    DisposableEffect(context) {
        onDispose {
            runCatching {
                ProxyController.getInstance()
                    .clearProxyOverride(ContextCompat.getMainExecutor(context)) {}
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Вход в Rutracker",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { if (!busy) onDismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
                Text(
                    text = "Войдите как обычно — капча решается здесь же. Пароль не хранится, сохраняется лишь сессия.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                proxyNote?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                // Десктопный UA — те же страницы, что видит резолвер.
                                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                                cm.setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?
                                    ) {
                                        isLoading = true
                                        // Новая страница — прошлая ошибка проверки уже неактуальна.
                                        if (error != null) error = null
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        if (autoChecked || busy || view == null) return
                                        // Формы логина больше нет — похоже, вход выполнен: проверяем раз.
                                        view.evaluateJavascript(
                                            "(function(){return document.querySelector(" +
                                                "'[name=login_username]')?'login':'maybe-in';})();"
                                        ) { r ->
                                            if (r?.contains("maybe-in") == true && !autoChecked && !busy) {
                                                autoChecked = true
                                                checkLogin()
                                            }
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean = false

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        if (request?.isForMainFrame != true) return
                                        isLoading = false
                                        setLoginError(
                                            "Страница не загрузилась " +
                                                "(${error?.description ?: "ошибка сети"}). " +
                                                "Если трекер блокирует провайдер — " +
                                                "задайте прокси в Настройки → Сеть"
                                        )
                                    }
                                }
                                // loadUrl — из LaunchedEffect после применения прокси-оверайда.
                                webViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { wv ->
                            if (webViewRef === wv) webViewRef = null
                            wv.stopLoading()
                            wv.destroy()
                        }
                    )
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(28.dp)
                        )
                    }
                }
                error?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { webViewRef?.reload() }, enabled = !busy) { Text("Обновить") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text("Отмена") }
                    Button(onClick = ::checkLogin, enabled = !busy) {
                        Text(if (busy) "Проверяем…" else "Я вошёл")
                    }
                }
            }
        }
    }
}

private const val RUTRACKER_LOGIN_URL = "https://rutracker.org/forum/login.php"

/** Хосты, которые WebView-вход гонит через прокси (reverse-bypass: только они). */
private val RUTRACKER_PROXY_HOSTS = listOf(
    "rutracker.org", "*.rutracker.org",
    "rutracker.net", "*.rutracker.net",
    "rutracker.nl", "*.rutracker.nl"
)

/** Куки WebView → OkHttp: CookieManager срок жизни не отдаёт, ставим +365 дней. */
private fun collectWebRutrackerCookies(): List<Cookie> {
    val cm = CookieManager.getInstance()
    val expiry = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
    val out = mutableListOf<Cookie>()
    RutrackerResolver.MIRRORS.forEach { mirror ->
        val host = runCatching { Uri.parse(mirror).host }.getOrNull() ?: return@forEach
        val raw = runCatching { cm.getCookie("$mirror/forum/") ?: cm.getCookie(mirror) }.getOrNull()
            ?: return@forEach
        raw.split(";").forEach { part ->
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq <= 0) return@forEach
            val name = p.substring(0, eq).trim()
            val value = p.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty() || value == "\"\"") return@forEach
            if (name.startsWith("$")) return@forEach
            runCatching {
                Cookie.Builder().name(name).value(value).domain(host).path("/").expiresAt(expiry).build()
            }.getOrNull()?.let { out.add(it) }
        }
    }
    return out
}

@Composable
private fun EmptyTorrentsHint(isAnime: Boolean, settled: Boolean, rutrackerLoggedIn: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!settled) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Ищем раздачи…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Торренты не найдены для этого тайтла",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isAnime) {
                    "Источники: AniLiberty, AniStar, Rutor" +
                        if (rutrackerLoggedIn) ", Rutracker" else " · Rutracker — нужен вход выше"
                } else {
                    "Источники: Rutor" +
                        if (rutrackerLoggedIn) ", Rutracker" else " · Rutracker — нужен вход выше"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TorrentRow(
    link: AnimeStreamResolver.TorrentLink,
    onOpen: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = link.source,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = link.quality,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (link.codec == "HEVC") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "HEVC",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (!link.label.isNullOrBlank() && link.label != link.quality) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = link.label!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append(link.size)
                        if (link.seeders > 0 || link.leechers > 0) {
                            append("  •  ↑").append(link.seeders)
                            append(" ↓").append(link.leechers)
                        }
                        link.uploadedAt?.let { append("  •  ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                link.magnet?.let { magnet ->
                    IconButton(onClick = { onOpen(magnet) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Magnet-ссылка",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                link.torrentUrl?.let { url ->
                    IconButton(onClick = { onOpen(url) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Скачать .torrent",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ======================================================================
// Вкладка «В приложение»
// ======================================================================

@Composable
private fun OfflineTab(
    item: FilmDetails,
    isAnime: Boolean,
    shikimoriId: Int,
    itemKey: String,
    displayTitle: String,
    askQuality: ((String?) -> Unit) -> Unit
) {
    val tasks by EpisodeDownloadManager.tasks.collectAsState()
    val itemTasks = tasks.values.filter { it.itemKey == itemKey }.sortedBy { it.episodeNumber }

    Column(modifier = Modifier.fillMaxSize()) {
        if (itemTasks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemTasks.forEach { task ->
                    DownloadTaskRow(task = task)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (isAnime) {
            AnimeOfflineSection(
                shikimoriId = shikimoriId,
                kinopoiskId = item.kinopoiskId,
                itemKey = itemKey,
                displayTitle = displayTitle,
                askQuality = askQuality
            )
        } else {
            MovieOfflineSection(
                item = item,
                itemKey = itemKey,
                displayTitle = displayTitle,
                askQuality = askQuality
            )
        }
    }
}

/** Строка активной задачи: прогресс/ошибка + отмена/повтор. */
@Composable
private fun DownloadTaskRow(task: DownloadTaskState) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (task.phase) {
                        DownloadPhase.QUEUED -> "В очереди"
                        DownloadPhase.RESOLVING -> "Поиск ссылки…"
                        DownloadPhase.DOWNLOADING -> "Скачивание"
                        DownloadPhase.PAUSED -> "На паузе"
                        DownloadPhase.DONE -> "Готово"
                        DownloadPhase.FAILED -> "Ошибка"
                    } + " · ${task.episodeLabel} · ${task.translationTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                when (task.phase) {
                    DownloadPhase.FAILED -> {
                        IconButton(onClick = { EpisodeDownloadManager.retry(task.key) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Повторить", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { EpisodeDownloadManager.dismissFailed(task.key) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Скрыть", modifier = Modifier.size(18.dp))
                        }
                    }
                    else -> {
                        if (task.phase == DownloadPhase.PAUSED) {
                            IconButton(onClick = { EpisodeDownloadManager.resume(task.key) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Продолжить", modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick = { EpisodeDownloadManager.pause(task.key) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Pause, contentDescription = "Приостановить", modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = { EpisodeDownloadManager.cancel(task.key) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Отменить", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            when (task.phase) {
                DownloadPhase.DOWNLOADING -> {
                    val fraction = task.progressPercent?.let { it / 100f }
                    LinearProgressIndicator(
                        progress = { fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = downloadProgressText(task),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadPhase.FAILED -> {
                    Text(
                        text = task.error ?: "Не удалось скачать",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> Unit
            }
        }
    }
}

// ---------------------------- аниме ----------------------------

private data class AnimeOfflineSourceState(
    val loading: Boolean = false,
    val translations: List<FlatTranslation> = emptyList(),
    val error: String? = null
)

@Composable
private fun AnimeOfflineSection(
    shikimoriId: Int,
    kinopoiskId: Int,
    itemKey: String,
    displayTitle: String,
    askQuality: ((String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceStates by remember(shikimoriId) {
        mutableStateOf<Map<AnimeSourceType, AnimeOfflineSourceState>>(emptyMap())
    }
    var expandedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shikimoriId) {
        hd.kinoshka.app.data.model.ANIME_PICKER_SOURCES.forEach { source ->
            sourceStates = sourceStates + (source to AnimeOfflineSourceState(loading = true))
            launch(Dispatchers.IO) {
                val result = runCatching {
                    AnimeStreamResolver.fetchSourceMedia(shikimoriId, displayTitle, source)
                        .filter { it.episodes.isNotEmpty() }
                }
                sourceStates = sourceStates + (source to AnimeOfflineSourceState(
                    loading = false,
                    translations = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.localizedMessage
                ))
            }
        }
    }

    val tasks by EpisodeDownloadManager.tasks.collectAsState()
    val library by EpisodeDownloadManager.library.collectAsState()
    val downloadedKeys = library.filter { it.itemKey == itemKey }.map { it.key }.toSet()
    val downloadedTotal = downloadedKeys.size
    val downloadedSize = library.filter { it.itemKey == itemKey }.sumOf { it.sizeBytes }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (downloadedTotal > 0) {
            item(key = "downloaded-banner") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Скачано: $downloadedTotal сер. · ${formatBytes(downloadedSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        hd.kinoshka.app.data.model.ANIME_PICKER_SOURCES.forEach { source ->
            val state = sourceStates[source] ?: return@forEach
            item(key = "src:${source.name}") {
                Row(
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = source.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.loading) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                }
            }
            if (state.error != null && state.translations.isEmpty()) {
                item(key = "srcfail:${source.name}") {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            items(
                state.translations,
                key = { "tr:${source.name}:${it.translationId}" }
            ) { tr ->
                val trKey = "${source.name}:${tr.translationId}"
                val downloaded = tr.episodes.count { ep ->
                    offlineKey(itemKey, source.name, tr.translationId, ep.number) in downloadedKeys
                }
                val activeForTr = tasks.values.count {
                    it.itemKey == itemKey && it.translationId == tr.translationId &&
                        it.phase != DownloadPhase.FAILED
                }
                VoiceoverDownloadRow(
                    title = tr.title,
                    sourceLabel = source.displayName,
                    episodeCount = tr.episodes.size,
                    downloadedCount = downloaded,
                    activeCount = activeForTr,
                    expanded = expandedKey == trKey,
                    onToggleExpand = { expandedKey = if (expandedKey == trKey) null else trKey },
                    onDownloadAll = {
                        askQuality { quality ->
                            context.tryRequestNotificationPermission()
                            EpisodeDownloadManager.enqueueAll(
                                DownloadBridges.animeRequests(shikimoriId, kinopoiskId, displayTitle, tr, preferredQuality = quality)
                            )
                        }
                    },
                    episodes = tr.episodes,
                    itemKey = itemKey,
                    sourceName = source.name,
                    translationId = tr.translationId,
                    shikimoriId = shikimoriId,
                    kinopoiskId = kinopoiskId,
                    animeTitle = displayTitle,
                    translationTitle = tr.title,
                    tasks = tasks,
                    downloadedKeys = downloadedKeys,
                    askQuality = askQuality
                )
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/** Строка озвучки: «скачать все серии» + раскрытие списка серий с кнопками на каждой. */
@Composable
private fun VoiceoverDownloadRow(
    title: String,
    sourceLabel: String,
    episodeCount: Int,
    downloadedCount: Int,
    activeCount: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDownloadAll: () -> Unit,
    episodes: List<AnimeEpisode>,
    itemKey: String,
    sourceName: String,
    translationId: String,
    shikimoriId: Int,
    kinopoiskId: Int,
    animeTitle: String,
    translationTitle: String,
    tasks: Map<String, DownloadTaskState>,
    downloadedKeys: Set<String>,
    askQuality: ((String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val allDownloaded = episodeCount in 1..downloadedCount

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val status = buildString {
                        append("$episodeCount сер.")
                        if (downloadedCount > 0) append(" · скачано $downloadedCount")
                        if (activeCount > 0) append(" · в загрузке $activeCount")
                    }
                    Text(
                        text = "$sourceLabel · $status",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (allDownloaded) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Всё скачано",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    IconButton(onClick = onDownloadAll, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = "Скачать все серии",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                val sourceType = runCatching { AnimeSourceType.valueOf(sourceName) }
                    .getOrDefault(AnimeSourceType.KODIK)
                episodes.sortedBy { it.number }.forEach { ep ->
                    val key = offlineKey(itemKey, sourceName, translationId, ep.number)
                    EpisodeDownloadMiniRow(
                        label = "Серия ${ep.number}",
                        downloaded = key in downloadedKeys,
                        task = tasks[key],
                        onDownload = {
                            askQuality { quality ->
                                context.tryRequestNotificationPermission()
                                EpisodeDownloadManager.enqueue(
                                    EpisodeDownloadManager.EpisodeDownloadRequest(
                                        itemKey = itemKey,
                                        title = animeTitle,
                                        source = sourceName,
                                        translationId = translationId,
                                        translationTitle = translationTitle,
                                        episodeNumber = ep.number,
                                        episodeLabel = ep.title?.takeIf { it.isNotBlank() } ?: "Серия ${ep.number}",
                                        resolve = {
                                            AnimeStreamResolver.resolveStream(
                                                shikimoriId, animeTitle, sourceType, translationId, ep.number
                                            )?.let { DownloadBridges.mediaSource(it, quality) }
                                        }
                                    )
                                )
                            }
                        },
                        onCancel = { EpisodeDownloadManager.cancel(key) },
                        onRetry = { EpisodeDownloadManager.retry(key) }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun EpisodeDownloadMiniRow(
    label: String,
    downloaded: Boolean,
    task: DownloadTaskState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 50.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        when {
            downloaded -> Icon(
                Icons.Rounded.DownloadDone,
                contentDescription = "Скачано",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            task != null && task.phase == DownloadPhase.FAILED -> {
                IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Повторить",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { EpisodeDownloadManager.dismissFailed(task.key) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Скрыть", modifier = Modifier.size(20.dp))
                }
            }
            task != null -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Отменить", modifier = Modifier.size(20.dp))
                }
            }
            else -> IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Скачать серию",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

// ---------------------------- фильмы и сериалы ----------------------------

private sealed interface MovieOfflineState {
    data object Loading : MovieOfflineState
    data class Ready(val payload: NativeLaunchPayload) : MovieOfflineState
    data class Failed(val message: String) : MovieOfflineState
}

private fun buildMovieRequest(item: FilmDetails): MoviePlaybackRequest {
    val normalizedType = item.type.orEmpty().trim().uppercase().replace('-', '_').replace(' ', '_')
    return MoviePlaybackRequest(
        kinopoiskId = item.kinopoiskId.takeIf { it > 0 },
        imdbId = KodikMovieParser.normalizeImdb(item.imdbId),
        titles = listOfNotNull(item.nameRu, item.nameEn, item.nameOriginal)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(KodikMovieParser::normalizeTitle),
        year = item.year ?: item.startYear,
        kind = when {
            item.serial == true || normalizedType in setOf("TV_SERIES", "MINI_SERIES", "TV_SHOW", "SERIES") -> MovieContentKind.SERIES
            else -> MovieContentKind.MOVIE
        }
    )
}

@Composable
private fun MovieOfflineSection(
    item: FilmDetails,
    itemKey: String,
    displayTitle: String,
    askQuality: ((String?) -> Unit) -> Unit
) {
    val uiContext = LocalContext.current
    var state by remember(item.kinopoiskId) { mutableStateOf<MovieOfflineState>(MovieOfflineState.Loading) }
    var expandedSeriesKey by remember(item.kinopoiskId) { mutableStateOf<String?>(null) }
    val library by EpisodeDownloadManager.library.collectAsState()
    val tasks by EpisodeDownloadManager.tasks.collectAsState()

    LaunchedEffect(item.kinopoiskId) {
        withContext(Dispatchers.IO) {
            val result = runCatching {
                MovieNativeLauncher.resolve(buildMovieRequest(item), profile = null, stateStore = null)
            }
            state = result.fold(
                onSuccess = { MovieOfflineState.Ready(it) },
                onFailure = { MovieOfflineState.Failed(it.localizedMessage ?: "Не удалось загрузить каталог") }
            )
        }
    }

    when (val s = state) {
        is MovieOfflineState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        is MovieOfflineState.Failed -> Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Не удалось получить список озвучек: ${s.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is MovieOfflineState.Ready -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val payload = s.payload) {
                is NativeLaunchPayload.QualityOnlyMovie -> {
                    val titles = payload.translations.associate { it.translationId to it.title }
                    if (payload.preparedStreams.isEmpty()) {
                        item(key = "qom-empty") {
                            Text(
                                text = "Озвучки не найдены. Попробуйте торренты.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    items(payload.preparedStreams.keys.toList(), key = { "qom:$it" }) { trId ->
                        val key = offlineKey(itemKey, AnimeSourceType.KODIK.name, trId, 1)
                        MovieVoiceoverRow(
                            title = titles[trId] ?: trId,
                            subtitle = "Фильм",
                            downloaded = library.any { it.itemKey == itemKey && it.translationId == trId },
                            task = tasks[key],
                            onDownload = {
                                askQuality { quality ->
                                    uiContext.tryRequestNotificationPermission()
                                    // Строго выбранная озвучка: каждый ряд качает свой поток, а не
                                    // весь каталог (фильм — не «озвучка×серия»).
                                    EpisodeDownloadManager.enqueue(
                                        DownloadBridges.qomRequest(
                                            item.kinopoiskId, displayTitle, trId,
                                            payload.preparedStreams.getValue(trId), titles[trId] ?: trId,
                                            posterUrl = item.posterUrlPreview ?: item.posterUrl,
                                            preferredQuality = quality
                                        )
                                    )
                                }
                            },
                            onCancel = { EpisodeDownloadManager.cancel(key) },
                            onRetry = { EpisodeDownloadManager.retry(key) }
                        )
                    }
                }
                is NativeLaunchPayload.MovieSeries -> {
                    val context = payload.context
                    val voiceovers = context.candidates.distinctBy { it.translationId ?: it.translationTitle }
                    item(key = "ser-hint") {
                        Text(
                            text = "Серии скачиваются по озвучкам (Kodik-каталог)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    items(voiceovers, key = { "ser:${it.translationId ?: it.translationTitle}" }) { candidate ->
                        val trId = candidate.translationId ?: candidate.translationTitle ?: "default"
                        val eps = context.candidates
                            .filter { it.translationId == candidate.translationId }
                            .flatMap { c -> c.episodes }
                            .distinctBy { it.seasonNumber to it.episodeNumber }
                            .sortedBy { it.seasonNumber * 1000 + it.episodeNumber }
                        val downloadedEpNumbers = library
                            .filter { it.itemKey == itemKey && it.translationId == trId }
                            .map { it.episodeNumber }
                            .toSet()
                        SeriesVoiceoverRow(
                            title = candidate.translationTitle ?: "Озвучка",
                            episodeCount = eps.size,
                            downloadedCount = library.count { it.itemKey == itemKey && it.translationId == trId },
                            activeLabel = tasks.values
                                .firstOrNull { it.itemKey == itemKey && it.translationId == trId }
                                ?.episodeLabel,
                            expanded = expandedSeriesKey == trId,
                            onToggleExpand = { expandedSeriesKey = if (expandedSeriesKey == trId) null else trId },
                            onDownloadAll = {
                                if (eps.isNotEmpty()) {
                                    askQuality { quality ->
                                        uiContext.tryRequestNotificationPermission()
                                        EpisodeDownloadManager.enqueueAll(
                                            DownloadBridges.seriesRequests(
                                                item.kinopoiskId, displayTitle, context.request,
                                                context.candidates, trId,
                                                candidate.translationTitle ?: "Озвучка", eps,
                                                isDirectSource = context.isDirectSource,
                                                directHeaders = context.directHeaders,
                                                posterUrl = item.posterUrlPreview ?: item.posterUrl,
                                                preferredQuality = quality
                                            )
                                        )
                                    }
                                }
                            },
                            episodes = eps,
                            downloadedEpNumbers = downloadedEpNumbers,
                            tasks = tasks,
                            itemKey = itemKey,
                            trId = trId,
                            onDownloadEpisode = { ep ->
                                askQuality { quality ->
                                    uiContext.tryRequestNotificationPermission()
                                    DownloadBridges.seriesRequests(
                                        item.kinopoiskId, displayTitle, context.request,
                                        context.candidates, trId,
                                        candidate.translationTitle ?: "Озвучка", listOf(ep),
                                        isDirectSource = context.isDirectSource,
                                        directHeaders = context.directHeaders,
                                        posterUrl = item.posterUrlPreview ?: item.posterUrl,
                                        preferredQuality = quality
                                    ).firstOrNull()?.let { EpisodeDownloadManager.enqueue(it) }
                                }
                            }
                        )
                    }
                }
                is NativeLaunchPayload.Failed -> item(key = "fail") {
                    Text(
                        text = "Источники недоступны: ${payload.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** Строка сериальной озвучки: «скачать все серии» + раскрытие списка серий. */
@Composable
private fun SeriesVoiceoverRow(
    title: String,
    episodeCount: Int,
    downloadedCount: Int,
    activeLabel: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDownloadAll: () -> Unit,
    episodes: List<MovieEpisodeRef>,
    downloadedEpNumbers: Set<Int>,
    tasks: Map<String, DownloadTaskState>,
    itemKey: String,
    trId: String,
    onDownloadEpisode: (MovieEpisodeRef) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val status = buildString {
                        append("$episodeCount сер.")
                        if (downloadedCount > 0) append(" · скачано $downloadedCount")
                        activeLabel?.let { append(" · загрузка: $it") }
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val allDone = episodeCount in 1..downloadedCount
                if (allDone) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Всё скачано",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    IconButton(onClick = onDownloadAll, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = "Скачать все серии",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                episodes.forEach { ep ->
                    val epNumber = DownloadBridges.offlineEpisodeNumber(ep)
                    val key = offlineKey(itemKey, AnimeSourceType.KODIK.name, trId, epNumber)
                    EpisodeDownloadMiniRow(
                        label = DownloadBridges.seriesEpisodeLabel(ep),
                        downloaded = epNumber in downloadedEpNumbers,
                        task = tasks[key],
                        onDownload = { onDownloadEpisode(ep) },
                        onCancel = { EpisodeDownloadManager.cancel(key) },
                        onRetry = { EpisodeDownloadManager.retry(key) }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

/** QOM-строка озвучки (фильм одним файлом). */
@Composable
private fun MovieVoiceoverRow(
    title: String,
    subtitle: String,
    downloaded: Boolean,
    task: DownloadTaskState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (task != null && task.phase == DownloadPhase.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
            when {
                downloaded -> Icon(
                    Icons.Rounded.DownloadDone,
                    contentDescription = "Скачано",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                task != null && task.phase == DownloadPhase.FAILED -> IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Повторить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
                task != null -> IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Отменить", modifier = Modifier.size(18.dp))
                }
                else -> IconButton(onClick = onDownload, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Download, contentDescription = "Скачать", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
