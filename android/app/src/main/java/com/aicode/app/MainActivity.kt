package com.aicode.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.graphics.Bitmap
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.aicode.app.data.EngineClient
import com.aicode.app.data.EngineEndpoint
import com.aicode.app.data.ServerConfig
import com.aicode.app.data.TokenService
import com.aicode.app.pages.ConnectScreen
import com.aicode.app.pages.FilesScreen
import com.aicode.app.pages.FishScreen
import com.aicode.app.pages.SettingsScreen
import com.aicode.app.pages.TerminalScreen
import com.aicode.app.ui.AicodeTheme
import com.aicode.app.ui.EmptyState
import com.aicode.app.ui.LoadingState
import com.aicode.app.ui.appBackgroundBrush
import com.aicode.app.ui.appCardColor
import com.aicode.app.ui.appLineColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        /** App 是否在前台：前台时回合完成不弹通知（用户正看着） */
        @Volatile
        var isForeground = false
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    override fun onStart() {
        super.onStart()
        lifeLog("onStart")
    }

    override fun onStop() {
        super.onStop()
        lifeLog("onStop finishing=" + isFinishing)
    }

    override fun onDestroy() {
        lifeLog("onDestroy finishing=" + isFinishing + " changingConfig=" + isChangingConfigurations)
        super.onDestroy()
    }

    /** 生命周期流水：查「回桌面再点进来像冷启动」时，能看到到底是进程重启、Activity 重建还是页面被重载。 */
    private fun lifeLog(msg: String) {
        runCatching {
            val f = java.io.File(getExternalFilesDir(null), "lifecycle.log")
            // 只留最近 256KB：这日志纯排障用，长期不清理会无限增长
            if (f.length() > 256 * 1024) {
                runCatching { f.delete() }
            }
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f.appendText(ts + " " + msg + " pid=" + android.os.Process.myPid() + "\n")
        }
    }

    /** 从「回合完成」通知带进来的会话 id；处理完就清掉。 */
    private val pendingSession = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("session")?.let { pendingSession.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifeLog("onCreate saved=" + (savedInstanceState != null))
        CrashReporter.install(this)
        pendingSession.value = intent?.getStringExtra("session")
        // 系统栏必须真透明：默认参数会给导航栏垫一层 90% 白遮罩，导致底部手势条区域发白、
        // 和页面的雾蓝/墨绿渐变对不上（2026-11-26 WebView 页实测）。
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        // ServerHost 先初始化：探测当前网络该走局域网还是 Tailscale。
        // 本地回环转发（LocalProxy）不在这里起——loadEngine() 拿到真实地址后在 IO 线程启动，
        // 这里如果固定用 ServerHost.host()，离家后会指向错误目标。
        runCatching { com.aicode.app.data.ServerHost.init(this@MainActivity) }
        runCatching { com.aicode.app.data.ServerHost.warmUp() }
        val prefs = getSharedPreferences("aicode", MODE_PRIVATE)
        // 后台保活：前台服务把进程钉住，避免切出去一会儿就被系统清掉（网页会话会断、回来重新加载）
        // 只有稳定版挂前台服务保活（测试版是验证用的，平时不跑，别在通知栏留一条常驻通知）
        if (!packageName.endsWith(".beta") && prefs.getBoolean(KeepAliveService.KEY, true)) KeepAliveService.start(this)
        // edge-to-edge 之后状态栏/导航栏区域露出来的是窗口背景，默认是白的——
        // 先按当前主题铺上，不然深色页面上下会各挂一条白边。
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (prefs.getBoolean("dark", true)) 0xFF151517.toInt() else 0xFFFFFFFF.toInt(),
            ),
        )
        // POST_NOTIFICATIONS 不在这里弹窗：设置页「回合完成通知」点击进系统通知设置
        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
            // 外观自定义（2026-11-26）：跟随系统深浅 + 主题色
            var followSystem by remember { mutableStateOf(prefs.getBoolean("followSystem", false)) }
            var accent by remember {
                mutableStateOf(com.aicode.app.ui.AppAccent.of(prefs.getString("accent", "teal")))
            }
            var fontSize by remember {
                mutableStateOf(com.aicode.app.ui.AppFontSize.of(prefs.getString("fontSize", "medium")))
            }
            var density by remember {
                mutableStateOf(com.aicode.app.ui.AppDensity.of(prefs.getString("density", "standard")))
            }
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val effectiveDark = if (followSystem) systemDark else dark
            // 还没配置服务器（首次安装，或设置页点了「重新配对」）时先走连接向导；
            // 配置好之后才进主界面，AppShell 的 LaunchedEffect 会自动重新 loadEngine。
            var configured by remember { mutableStateOf(ServerConfig.isConfigured(applicationContext)) }
            if (!configured) {
                ConnectScreen(onConnected = { configured = true })
            } else {
                AicodeTheme(dark = effectiveDark, accent = accent, fontSize = fontSize, density = density) {
                    val view = LocalView.current
                    SideEffect {
                        val window = (view.context as? Activity)?.window ?: return@SideEffect
                        val controller = WindowCompat.getInsetsController(window, view)
                        controller.isAppearanceLightStatusBars = !effectiveDark
                        controller.isAppearanceLightNavigationBars = !effectiveDark
                        window.setBackgroundDrawable(
                            android.graphics.drawable.ColorDrawable(
                                if (effectiveDark) 0xFF151517.toInt() else 0xFFFFFFFF.toInt(),
                            ),
                        )
                    }
                    AppShell(
                        dark = effectiveDark,
                        followSystem = followSystem,
                        onFollowSystemChange = { v ->
                            followSystem = v
                            prefs.edit().putBoolean("followSystem", v).apply()
                        },
                        accent = accent,
                        onAccentChange = { v ->
                            accent = v
                            prefs.edit().putString("accent", v.key).apply()
                        },
                        fontSize = fontSize,
                        onFontSizeChange = { v ->
                            fontSize = v
                            prefs.edit().putString("fontSize", v.key).apply()
                        },
                        density = density,
                        onDensityChange = { v ->
                            density = v
                            prefs.edit().putString("density", v.key).apply()
                        },
                        onThemeChange = { value ->
                            dark = value
                            prefs.edit().putBoolean("dark", value).apply()
                        },
                        pendingSession = pendingSession.value,
                        startTab = intent?.getStringExtra("tab")
                            ?.takeIf { it in listOf("web", "terminal", "files", "settings", "fish") },
                        onSessionHandled = { pendingSession.value = null },
                        onReconnect = { configured = false },
                    )
                }
            }
        }
    }
}

private fun localizeBase(base: String): String {
    return try {
        val uri = java.net.URI(base)
        val port = if (uri.port > 0) uri.port else 80
        "http://127.0.0.1:$port"
    } catch (_: Throwable) {
        "http://127.0.0.1:3080"
    }
}

private data class TabSpec(val id: String, val label: String, val icon: Int)

// 底部只留两个标签（2026-11-26 文案改版）：终端 / 文件 / 鱼世界收进「我的 → 更多功能」。
// 页面对应的分支还在 AppShell 的 when(page) 里，只是入口变了。
private val AppTabs = listOf(
    TabSpec("web", "对话", R.drawable.ic_tab_web),
    TabSpec("settings", "我的", R.drawable.ic_tab_settings),
)

@Composable
private fun AppShell(
    dark: Boolean,
    onThemeChange: (Boolean) -> Unit,
    followSystem: Boolean = false,
    onFollowSystemChange: (Boolean) -> Unit = {},
    accent: com.aicode.app.ui.AppAccent = com.aicode.app.ui.AppAccent.Teal,
    onAccentChange: (com.aicode.app.ui.AppAccent) -> Unit = {},
    fontSize: com.aicode.app.ui.AppFontSize = com.aicode.app.ui.AppFontSize.Medium,
    onFontSizeChange: (com.aicode.app.ui.AppFontSize) -> Unit = {},
    density: com.aicode.app.ui.AppDensity = com.aicode.app.ui.AppDensity.Standard,
    onDensityChange: (com.aicode.app.ui.AppDensity) -> Unit = {},
    pendingSession: String?,
    startTab: String?,
    onSessionHandled: () -> Unit,
    onReconnect: () -> Unit,
) {
    val ctx = LocalContext.current
    // 退出时在哪个页面，下次进来就恢复到哪个页面（首次安装/没记录时落对话页）。
    // 用 prefs 作唯一来源：避免 remembered/saved 状态与 prefs 不一致导致启动时闪一下默认页。
    val pagePrefs = ctx.getSharedPreferences("aicode", android.content.Context.MODE_PRIVATE)
    // 长按图标的快捷方式会带 tab（startTab），优先于上次停留的页面
    var page by remember {
        mutableStateOf(startTab ?: (pagePrefs.getString("page", "web") ?: "web"))
    }
    LaunchedEffect(page) {
        pagePrefs.edit().putString("page", page).apply()
    }
    // 「我的」页的二级菜单回到一级：点底栏同一个标签时递增，SettingsScreen 监听它重置
    var settingsKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 非网页页按返回：先回网页页（不然一按就退出 App）；网页页保持系统默认（退出）。
    // 返回键 / 侧滑手势：非网页页先回网页页；**在网页页不退出 App，而是退到后台**
    // （退出会 finish 掉 Activity，点图标回来就得重建 = 看起来像冷启动/重载）。
    // 真想退出就从最近任务里划掉。
    BackHandler(enabled = true) {
        if (page == "web") {
            (ctx as? android.app.Activity)?.moveTaskToBack(true)
        } else {
            page = "web"
        }
    }

    NotificationWatcher(ctx)
    // 手机控制轮询已挪到 ControlService（无障碍服务常驻，App 切后台/Activity 销毁后仍能收命令）

    var webBackground by remember { mutableStateOf<androidx.compose.ui.graphics.Color?>(null) }
    var webLoading by remember { mutableStateOf(true) }
    // 最近一次从后台回到前台的时刻：区分「真断网」和「切后台被系统掐了一下连接」。
    var lastResumeAt by remember { mutableStateOf(0L) }
    var webEverLoaded by remember { mutableStateOf(false) }
    var reconnectTries by remember { mutableIntStateOf(0) }
    var webError by remember { mutableStateOf<String?>(null) }
    var webRetryKey by remember { mutableIntStateOf(0) }
    var webAuthRetry by remember { mutableIntStateOf(0) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var popupView by remember { mutableStateOf<WebView?>(null) }
    // 渲染进程崩溃后重建 WebView 用：递增会让 remember 换一个新实例（见 onRenderProcessGone）
    var webGeneration by remember { mutableIntStateOf(0) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }
    val multiFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    // WebView 常驻：切页面不重建、不重新加载，回来还是原来那页
    @SuppressLint("SetJavaScriptEnabled")
    val webView = remember(webGeneration) {
        WebView(ctx).apply {
            // 手机控制（AI 操作手机）要往这个 WebView 的输入框里打字：注册给 WebInputBridge
            WebInputBridge.attach(this)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 页面只需要网络访问：禁掉本地文件和 content:// 访问，避免网页读到 App 私有文件
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(true)
            // 只关 WebView 自带的竖滚动条（用户嫌右下角那条灰条碍眼）
            setVerticalScrollBarEnabled(false)
            setHorizontalScrollBarEnabled(false)
            // 网页是实时会话：默认策略（有网就走网络，没网才回缓存），不要「缓存优先」，
            // 否则引擎/前端更新后页面可能还是旧的。
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 网页里的下载：http(s) 交给系统下载器；blob: 由页面转 base64 交给 DownloadBridge 落盘。
            addJavascriptInterface(DownloadBridge(ctx), "AndroidDownload")
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    downloadToDevice(ctx, url, contentDisposition, mimeType)
                } else if (url.startsWith("blob:")) {
                    saveBlobFromPage(this, url, guessFileName(url, contentDisposition, mimeType))
                } else {
                    android.widget.Toast.makeText(ctx, "这个链接不能下载", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            webViewClient = object : WebViewClient() {
                // 外链（不是引擎的 127.0.0.1）交给系统浏览器：不然 DSH 页面会被顶掉、回不来，
                // 外站页面也没按 App 内嵌做状态栏适配（DeepSeek 开放平台就是这样重叠的）。
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return false
                    if (request?.isForMainFrame != true) return false
                    val host = url.host ?: return false
                    if (host == "127.0.0.1" || host == "localhost") return false
                    runCatching {
                        view?.context?.startActivity(
                            Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    webEverLoaded = true
                    reconnectTries = 0
                    view?.postDelayed({ webLoading = false }, 450)
                    view?.evaluateJavascript(
                        "(function(){try{var c=getComputedStyle(document.body).backgroundColor;return c||''}catch(e){return ''}})()",
                    ) { value -> parseCssColor(value)?.let { webBackground = it } }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        // 从后台切回来的一瞬间，系统可能先掐断再恢复连接，WebView 会误报一次网络错误；
                        // 这时页面本身是好的，静默重新加载一次，别显示「断网重试」吓人。
                        if (lastResumeAt > 0 && System.currentTimeMillis() - lastResumeAt < 3000 && view?.url != null) {
                            view.postDelayed({ view.reload() }, 400)
                            return
                        }
                        webLoading = false
                        if (reconnectTries < 5) {
                            reconnectTries++
                            webError = "网络不稳，正在重试（$reconnectTries/5）…"
                            view?.postDelayed({ webRetryKey++ }, 4000)
                        } else {
                            webError = "网络断了。检查 Wi-Fi 或流量。"
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame != true) return
                    val code = errorResponse?.statusCode ?: 0
                    // 引擎重启会换 token，页面拿到 401/403 时自动重新取 token 再加载（最多 3 次，防循环）
                    if (code == 401 || code == 403) {
                        if (webAuthRetry < 3) {
                            webAuthRetry++
                            webRetryKey++
                        } else {
                            webLoading = false
                            webError = "登录过期了，点右上角刷新一下"
                        }
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    // 渲染进程被系统回收/崩溃：默认会让 App 一起退出（闪退）。
                    // 返回 true 接管：丢掉旧 WebView，递增 webGeneration 换新实例，再重新取 token 加载。
                    runCatching {
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.destroy()
                    }
                    webGeneration++
                    webRetryKey++
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {

                // 登录等 window.open 弹窗：用一个覆盖层 WebView 承载，回调完成后自动关闭
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    if (view == null) return false
                    val popup = WebView(view.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 弹窗同样只给网络访问，别绕开主 WebView 的安全限制
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        // 弹窗里的下载（会话里点外链文件、导出的 zip 等）：一样交给系统下载器，
                        // 并把这个弹窗收掉——WebView 渲染不了 zip/二进制，不收就是全屏黑。
                        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                            popupView = null
                            postDelayed({ runCatching { destroy() } }, 80)
                            when {
                                url.startsWith("http") -> downloadToDevice(view.context, url, contentDisposition, mimeType)
                                url.startsWith("blob:") -> saveBlobFromPage(this, url, guessFileName(url, contentDisposition, mimeType))
                                else -> android.widget.Toast.makeText(view.context, "这个链接不能下载", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                val u = request?.url ?: return false
                                if (request?.isForMainFrame != true) return false
                                val h = u.host ?: return false
                                // OAuth 回调 / 引擎地址留在弹窗里，其它外链交给系统浏览器
                                if (h == "127.0.0.1" || h == "localhost" || u.toString().contains("/oauth/callback")) {
                                    return false
                                }
                                popupView = null
                                runCatching { v?.destroy() }
                                runCatching {
                                    v?.context?.startActivity(
                                        Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                return true
                            }

                            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                if (url != null && url.contains("/oauth/callback")) {
                                    // 回调请求已到服务端，认证在那边完成；这个页面本身会渲染报错，直接关掉
                                    v?.postDelayed({
                                        popupView = null
                                        runCatching { v.destroy() }
                                        view.reload()
                                    }, 400)
                                }
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                if (url != null && url.contains("/oauth/callback")) {
                                    v?.postDelayed({
                                        popupView = null
                                        view.reload()
                                    }, 400)
                                }
                            }
                        }
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    popupView = popup
                    return true
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val mimes = params?.acceptTypes?.filter { it.contains("/") }?.toTypedArray()
                    val launchMimes = if (mimes.isNullOrEmpty()) arrayOf("*/*") else mimes
                    val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                    return try {
                        if (multiple) multiFilePicker.launch(launchMimes) else filePicker.launch(launchMimes)
                        true
                    } catch (_: Throwable) {
                        fileCallback = null
                        false
                    }
                }
            }
        }
    }

    // 网页页离开超过 10 分钟再回来：自动重载一次，把页面里累积的消息 DOM 清掉。
    // 会话越长、往上翻得越多，页面越沉（越用越卡）；DSH 前端会记住当前会话，重载后自动回到同一个会话。
    // 只在「已经加载过页面、且当前不在加载中、且确实离开了超过 10 分钟」时触发，平时切页不受影响。
    var webLeftAt by remember { mutableStateOf(0L) }
    LaunchedEffect(page) {
        if (page == "web") {
            val left = webLeftAt
            val idle = if (left > 0) System.currentTimeMillis() - left else 0L
            // 重载阈值可配置（设置页「网页自动重载」；0 = 关闭）
            val reloadMin = ctx.getSharedPreferences("aicode", android.content.Context.MODE_PRIVATE)
                .getInt("webReloadMinutes", 10)
            if (reloadMin > 0 && left > 0 && idle > reloadMin * 60_000L && webView.url != null && !webLoading) {
                webView.reload()
            }
            webLeftAt = 0L
        } else {
            webLeftAt = System.currentTimeMillis()
        }
    }

    // App 退到后台超过 10 分钟再回来：网页页也自动重载一次（跟「切到别的页再回来」同一个目的）。
    // 少于 10 分钟（回个消息、看个通知）不动，避免每次切出去回来都重载。
    val pageNow = rememberUpdatedState(page)
    val webLoadingNow = rememberUpdatedState(webLoading)
    var bgLeftAt by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        val owner = ctx as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    bgLeftAt = System.currentTimeMillis()
                    // WebView 挂后台要显式 pause，否则 JS 定时器/动画在后台继续跑，回来后容易卡住没反应
                    runCatching { webView.onPause() }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    runCatching { webView.onResume() }
                    lastResumeAt = System.currentTimeMillis()
                    val left = bgLeftAt
                    bgLeftAt = 0L
                    val reloadMin = ctx.getSharedPreferences("aicode", android.content.Context.MODE_PRIVATE)
                        .getInt("webReloadMinutes", 10)
                    if (reloadMin > 0 && left > 0 && System.currentTimeMillis() - left > reloadMin * 60_000L &&
                        pageNow.value == "web" && !webLoadingNow.value && webView.url != null
                    ) {
                        webView.reload()
                    }
                }
                else -> Unit
            }
        }
        if (owner != null) owner.lifecycle.addObserver(obs)
        onDispose { if (owner != null) owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(webRetryKey) {
        webLoading = true
        webError = null
        val url = withContext(Dispatchers.IO) {
            val ep = TokenService.fetch()
            if (ep == null) null else "${localizeBase(ep.base)}/?token=${ep.token}"
        }
        if (url == null) {
            webError = "连不上服务器了，检查网络"
            webLoading = false
        } else {
            // 明确切主线程：Compose 测试的 continuation interceptor 可能让这里回到 worker 线程
            withContext(Dispatchers.Main) { webView.loadUrl(url) }
        }
    }

    // 从「回合完成」通知进来：切到网页，替用户点开对应会话。
    // 网页本身没有会话链接，但侧边栏的会话行带稳定标记 data-row-key="session:<id>"，
    // 侧边栏收起时先点 [data-sidebar-opener] 把它打开，再点那一行。
    LaunchedEffect(pendingSession) {
        val sid = pendingSession ?: return@LaunchedEffect
        page = "web"
        var waited = 0
        while (webLoading && waited < 8000) {
            delay(200)
            waited += 200
        }
        val quoted = org.json.JSONObject.quote(sid)
        val js = "(function(){var key='session:'+" + quoted + ";" +
            "var opener=0,tries=0;" +
            "function hit(){var el=document.querySelector('[data-row-key=\"'+key+'\"]');" +
            "if(el){try{el.scrollIntoView({block:'center'})}catch(e){}el.click();return true}return false}" +
            "function open(){var b=document.querySelector('[data-sidebar-opener]');if(b){b.click();return true}return false}" +
            "var t=setInterval(function(){if(hit()){clearInterval(t);return}" +
            "if(opener<5){open();opener++}tries++;if(tries>30)clearInterval(t)},250)})();"
        withContext(Dispatchers.Main) { webView.evaluateJavascript(js, null) }
        onSessionHandled()
    }

    LaunchedEffect(dark) {
        // 切主题先把底色按主题铺上，网页换色有延迟，不铺就会闪一下旧底色
        webBackground = if (dark) androidx.compose.ui.graphics.Color(0xFF151517) else androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        delay(1200)
        webView.evaluateJavascript(
            "(function(){try{var c=getComputedStyle(document.body).backgroundColor;return c||''}catch(e){return ''}})()",
        ) { value -> parseCssColor(value)?.let { webBackground = it } }
    }

    val themeBackground = MaterialTheme.colorScheme.background
    val pageBackground = if (page == "web") webBackground ?: themeBackground else themeBackground

    var endpoint by remember { mutableStateOf<EngineEndpoint?>(null) }
    var client by remember { mutableStateOf<EngineClient?>(null) }
    var cookie by remember { mutableStateOf<String?>(null) }
    var engineLoaded by remember { mutableStateOf(false) }
    var engineVersion by remember { mutableStateOf<com.aicode.app.data.EngineVersion?>(null) }

    suspend fun loadEngine() {
        val ep = withContext(Dispatchers.IO) { TokenService.fetch() }
        endpoint = ep
        // 老版本把鱼世界密钥放在 DSH token 服务里下发；首次升级时迁移到本地 MCP 配置
        if (ep != null && ServerConfig.mcpToken(ctx).isEmpty() && ep.fishToken.isNotEmpty()) {
            ServerConfig.saveMcp(ctx, ServerConfig.FISH_WORLD_MCP_URL, ep.fishToken, true)
        }
        if (ep != null) {
            // 起本地回环转发：127.0.0.1:3080 -> 引擎，让页面 origin 是回环地址（OAuth 回调需要）。
            // 放 IO 线程：start() 会等端口真正 bind 完再返回，紧跟其后的 token 交换才不会失败。
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = java.net.URI(ep.base)
                    // ep.base 已经过 ServerHost 探测；这里再兜底一次也走同一入口，不再写死 IP
                    val host = uri.host ?: com.aicode.app.data.ServerHost.host()
                    val port = if (uri.port > 0) uri.port else 3080
                    LocalProxy.start(host, port)
                }
            }
            client = EngineClient(localizeBase(ep.base))
            // 冷启动第一次交换偶尔失败（回环转发/内嵌 VPN 刚起来）：失败就重试几次，别拿空 cookie 用到底——
            // 空 cookie 会让文件页空白、鱼世界发送报「没送出去：未知错误」，得用户手点「重新获取 token」才好。
            cookie = withContext(Dispatchers.IO) {
                var ck = client?.exchange(ep.token).orEmpty()
                var tries = 0
                while (ck.isEmpty() && tries < 5) {
                    Thread.sleep(600)
                    ck = client?.exchange(ep.token).orEmpty()
                    tries++
                }
                ck
            }
            engineVersion = withContext(Dispatchers.IO) { TokenService.fetchEngineVersion() }
        }
        engineLoaded = true
    }

    suspend fun syncDshTheme(darkNow: Boolean) {
        val c = client ?: return
        val ck = cookie ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val args = org.json.JSONObject()
                    .put("ns", "ui-theme")
                    .put("patch", org.json.JSONObject().put("preference", if (darkNow) "dark" else "light"))
                val payload = org.json.JSONObject()
                    .put("type", "client-request")
                    .put("rpcId", "theme-sync")
                    .put("method", "settings/update")
                    .put("payload", org.json.JSONObject().put("args", args))
                c.post(ck, "/api/settings/update", payload)
            }
        }
    }

    LaunchedEffect(Unit) {
        loadEngine()
        syncDshTheme(dark)
    }

    // 全屏底色渐变：状态栏、导航栏区域也铺同一层底色，不让系统栏和内容区出现色差。
    Box(Modifier.fillMaxSize().background(appBackgroundBrush())) {
    // 顶部渐晕从屏幕最顶端开始（含状态栏区域），和下面各页的顶栏连成一片。
    // 原来由 AicodeTopBar 自己画，但顶栏从状态栏下方开始，两者之间会出现一条色差断层（2026-11-26 用户反馈）。
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.035f),
                        androidx.compose.ui.graphics.Color.Transparent,
                    ),
                ),
            ),
    )
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val fromIdx = AppTabs.indexOfFirst { it.id == initialState }
                        val toIdx = AppTabs.indexOfFirst { it.id == targetState }
                        val dir = if (toIdx >= fromIdx) 1 else -1
                        (slideInHorizontally(tween(260)) { width -> width * dir / 5 } +
                            fadeIn(tween(210)) +
                            scaleIn(tween(260), initialScale = 0.96f)) togetherWith
                            (slideOutHorizontally(tween(200)) { width -> -width * dir / 8 } +
                                fadeOut(tween(150)) +
                                scaleOut(tween(200), targetScale = 0.98f))
                    },
                    label = "page",
                ) { current ->
                    when (current) {
                        "web" -> WebPage(
                            webView = webView,
                            generation = webGeneration,
                            loading = webLoading,
                            loaded = webEverLoaded,
                            error = webError,
                            onRetry = { reconnectTries = 0; webRetryKey++ },
                        )
                        "terminal" -> TerminalScreen(onOpenWeb = { page = "web" })
                        "files" -> FilesScreen(client = client, cookie = cookie)
                        "settings" -> SettingsScreen(
                            endpoint = endpoint,
                            engineLoaded = engineLoaded,
                            engineVersion = engineVersion,
                            onRefresh = { scope.launch { loadEngine() } },
                            onThemeChange = { value ->
                                onThemeChange(value)
                                scope.launch { syncDshTheme(value) }
                            },
                            onReconnect = onReconnect,
                            onNavigate = { page = it },
                            resetSignal = settingsKey,
                            followSystem = followSystem,
                            onFollowSystemChange = onFollowSystemChange,
                            accent = accent,
                            onAccentChange = onAccentChange,
                            fontSize = fontSize,
                            onFontSizeChange = onFontSizeChange,
                            density = density,
                            onDensityChange = onDensityChange,
                        )
                        "fish" -> FishScreen(
                            // 设置页的启用开关关闭时，视为未配置（鱼世界页显示配置提示）
                            mcpUrl = if (ServerConfig.mcpEnabled(ctx)) ServerConfig.mcpUrl(ctx) else "",
                            mcpToken = ServerConfig.mcpToken(ctx),
                            client = client,
                            cookie = cookie,
                        )
                        else -> EmptyState("页面不存在")
                    }
                }
            }
            // 重复点「我的」= 回到一级列表（在二级页时点底栏能退出去，2026-11-26）
            PageSwitcherBar(
                current = page,
                onSelect = {
                    if (it == "settings" && page == "settings") settingsKey++ else page = it
                },
            )
        }
    }
    val popup = popupView
    if (popup != null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(appBackgroundBrush())
                .imePadding(),
        ) {
            AndroidView(factory = { popup }, modifier = Modifier.fillMaxSize())
            androidx.compose.material3.IconButton(
                onClick = {
                    popupView = null
                    runCatching { popup.destroy() }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 36.dp, end = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        }
    }
    }
}

@Composable
private fun PageSwitcherBar(current: String, onSelect: (String) -> Unit) {
    // 底部 Dock：离屏边浮起的大圆角胶囊，选中项主题色光晕 + 图标轻微弹跳，（现在两个标签：对话 / 我的）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(appCardColor())
            .border(1.dp, appLineColor(), RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp),
    ) {
        AppTabs.forEach { item ->
            val selected = item.id == current
            val tint by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
                animationSpec = tween(180),
                label = "tabTint",
            )
            val pill by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else androidx.compose.ui.graphics.Color.Transparent,
                animationSpec = tween(200),
                label = "tabPill",
            )
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.12f else 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
                label = "tabScale",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(pill)
                    .clickable { onSelect(item.id) }
                    .padding(vertical = 7.dp)
                    .semantics { contentDescription = "切换到${item.label}页面" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .size(21.dp)
                            .scale(iconScale),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun WebPage(
    webView: WebView,
    generation: Int,
    loading: Boolean,
    loaded: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        key(generation) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
        // 冷启动：第一次连上之前先画一个启动页，别让用户对着白屏
        if (!loaded && error == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.17f),
                                androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            center = Offset(0f, 0f),
                            radius = 1100f,
                        ),
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "A",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "AIcode",
                    style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-0.8).sp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (loading) "正在连接…" else "正在准备…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.width(120.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }
        }
        if (error != null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("连不上，正在重试…", error)
                androidx.compose.material3.Button(onClick = onRetry) {
                    androidx.compose.material3.Text("重试")
                }
            }
        }
        if (loading && error == null) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
        }
    }
}

private fun parseCssColor(raw: String): androidx.compose.ui.graphics.Color? {
    val text = raw.trim().removeSurrounding("\"")
    if (text.isEmpty() || text == "null") return null
    if (text.startsWith("#")) {
        return runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(text)) }.getOrNull()
    }
    val match = Regex("rgba?\\(([^)]+)\\)").find(text) ?: return null
    val parts = match.groupValues[1].split(",").map { it.trim().toFloatOrNull() ?: return null }
    if (parts.size < 3) return null
    // 背景是渐变的页面，body 的 background-color 是透明的：不能把 rgba(0,0,0,0) 当成黑色，
    // 否则状态栏/页面底色会被画成纯黑（2026-11-26 实测：枳生主题 body 用渐变后状态栏变黑）。
    if (parts.size >= 4 && parts[3] <= 0f) return null
    return androidx.compose.ui.graphics.Color(
        parts[0].toInt().coerceIn(0, 255),
        parts[1].toInt().coerceIn(0, 255),
        parts[2].toInt().coerceIn(0, 255),
    )
}

// ---- 网页下载 -------------------------------------------------------------

/** 页面里的 blob 下载：JS 侧读成 base64 后回调这里，由原生写进「下载」目录。 */
private class DownloadBridge(private val ctx: android.content.Context) {
    @android.webkit.JavascriptInterface
    fun onBlob(base64: String, mime: String, name: String) {
        saveBase64ToDownloads(ctx, base64, mime, name)
    }
}

private fun guessFileName(url: String, disposition: String?, mime: String?): String {
    val fromDisposition = disposition?.let { cd ->
        Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(cd)?.groupValues?.getOrNull(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }
    }
    val fromUrl = runCatching { android.net.Uri.parse(url).lastPathSegment }.getOrNull()
    var base = (fromDisposition ?: fromUrl ?: "download").substringAfterLast('/')
    base = base.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
    if (base.isEmpty()) base = "download"
    if (base.length > 80) base = base.take(80)
    if (!base.contains('.')) {
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: "")
        if (!ext.isNullOrEmpty()) base = "$base.$ext"
    }
    return base
}

/** http(s) 下载走系统下载器（进度通知、存「下载」目录、点通知能打开）。 */
private fun downloadToDevice(ctx: android.content.Context, url: String, disposition: String?, mime: String?) {
    runCatching {
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
        mime?.takeIf { it.isNotBlank() }?.let { req.setMimeType(it) }
        req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        req.setDestinationInExternalPublicDir(
            android.os.Environment.DIRECTORY_DOWNLOADS,
            guessFileName(url, disposition, mime),
        )
        ctx.getSystemService(android.app.DownloadManager::class.java)?.enqueue(req)
        android.widget.Toast.makeText(ctx, "开始下载", android.widget.Toast.LENGTH_SHORT).show()
    }.onFailure {
        android.widget.Toast.makeText(ctx, "下载失败：${it.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** blob: 链接 DownloadManager 认不了，让页面自己 fetch 出来再回传。 */
private fun saveBlobFromPage(webView: android.webkit.WebView, url: String, name: String) {
    val js = "(function(){var u=" + org.json.JSONObject.quote(url) + ";" +
        "fetch(u).then(function(r){return r.blob()}).then(function(b){" +
        "var fr=new FileReader();fr.onload=function(){try{" +
        "window.AndroidDownload.onBlob(String(fr.result).split(',')[1]||'',b.type||''," +
        org.json.JSONObject.quote(name) + ")}catch(e){}};fr.readAsDataURL(b)}).catch(function(e){})})();"
    webView.evaluateJavascript(js, null)
}

@android.annotation.SuppressLint("NewApi") // MediaStore.Downloads 是 API 29+，运行时已判断
private fun saveBase64ToDownloads(ctx: android.content.Context, base64: String, mime: String, name: String) {
    if (android.os.Build.VERSION.SDK_INT < 29) {
        android.widget.Toast.makeText(ctx, "系统版本太低，暂不支持这类下载", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(
                android.provider.MediaStore.MediaColumns.MIME_TYPE,
                mime.ifBlank { "application/octet-stream" },
            )
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("建不了文件")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        android.widget.Toast.makeText(ctx, "已保存到「下载」：$name", android.widget.Toast.LENGTH_LONG).show()
    }.onFailure {
        android.widget.Toast.makeText(ctx, "保存失败：${it.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}
