package com.aicode.app.pages

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.app.data.ServerConfig
import kotlinx.coroutines.delay

/**
 * AI 中枢：内置 WebView 打开 AI Hub 控制台。
 *
 * 挂载方式和「对话」页一致（remember 建一次、AndroidView 复用）。
 * 额外加两道保险，因为这台机器上首次加载出现过黑屏：
 *   1. 延迟 400ms 再 loadUrl，等 WebView 真正 attach 到窗口；
 *   2. 加载完成若页面高度异常小（白屏/黑屏），自动重载一次。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HubScreen() {
    val ctx = LocalContext.current
    val url = remember { buildHubUrl(ctx) }
    var retried by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(ctx).apply {
            // 页面本身是深色（#0a0a0a）：WebView 默认白底，加载/换页时会闪白，
            // 也会在页面右侧滚动条区域漏出一条白边。
            setBackgroundColor(android.graphics.Color.rgb(10, 10, 10))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.textZoom = 100
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            @Suppress("DEPRECATION")
            settings.mixedContentMode =
                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    // 高度太小说明只画了个空壳，重载一次自愈
                    view?.evaluateJavascript("document.body ? document.body.scrollHeight : 0") { raw ->
                        val h = raw?.trim()?.trim('"')?.toIntOrNull() ?: 0
                        if (h < 200 && !retried) {
                            retried = true
                            view.postDelayed({
                                if (url.isNotEmpty()) view.loadUrl(url)
                            }, 600)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(url) {
        if (url.isEmpty()) return@LaunchedEffect
        // 等 WebView attach 到窗口再加载，首次加载时立刻 loadUrl 会白屏
        delay(400)
        webView.loadUrl(url)
    }

    // 离开页面必须释放：之前每次进页面都 new 一个 WebView，
    // 旧实例不销毁会一直占着到服务器的连接（实测堆积到 6~7 条），
    // 后面的实例就抢不到资源、加载不出来。
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
            }
        }
    }

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

/**
 * 同步拼出中枢地址：设置里填的优先；否则用当前服务器的 host + 8310。
 * 已保存的令牌直接带上，没保存就是登录页。
 */
private fun buildHubUrl(ctx: android.content.Context): String {
    val configured = ServerConfig.hubUrl(ctx).trim().trimEnd('/')
    val hubBase = if (configured.isNotEmpty()) {
        if (configured.startsWith("http")) configured else "http://$configured"
    } else {
        val host = runCatching { java.net.URI(ServerConfig.base(ctx)).host }.getOrNull()
        if (host.isNullOrBlank()) "" else "http://$host:8310"
    }
    if (hubBase.isEmpty()) return ""
    val token = ServerConfig.hubToken(ctx).trim()
    // app=1：告诉页面「在 App WebView 里」——保留页面自己的顶栏、按手机排版，
    // 底部只补 22px（App 已经为 Dock 预留 74dp，合计 96px）。
    return if (token.isNotEmpty()) "$hubBase/?token=$token&app=1" else "$hubBase/?app=1"
}
