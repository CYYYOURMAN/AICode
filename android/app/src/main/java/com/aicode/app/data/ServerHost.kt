package com.aicode.app.data

/**
 * token / 终端服务（`/engine`、`/exec`、`/app`、`/phone` 系列接口）的访问入口。
 *
 * 地址和密钥都来自运行时配置 [ServerConfig]（由「连接服务器」向导写入）：
 * 开源版不再内置局域网 / Tailscale 地址，也不再往 APK 里塞密钥。
 * 未配置时 [httpBase] / [authHeader] 返回 `""`，各调用点的 try/catch 会拿到失败结果。
 */
object ServerHost {
    /** 配对向导里默认建议的端口（token / exec 服务）；实际以配置里的 base 为准。 */
    const val PORT = 3300

    @Volatile
    private var appCtx: android.content.Context? = null

    fun init(ctx: android.content.Context) {
        appCtx = ctx.applicationContext
    }

    /** 配置状态（诊断用，显示在设置页）：「已配置」/「未配置」。 */
    val lastProbe: String
        get() {
            val ctx = appCtx ?: return "未配置"
            return if (ServerConfig.isConfigured(ctx)) "已配置" else "未配置"
        }

    /** 探测结果缓存：选中的地址 + 选中时间 + 是否正在探测。 */
    @Volatile private var chosen: String? = null
    @Volatile private var chosenAt = 0L
    @Volatile private var probing = false

    private const val CACHE_MS = 60_000L
    private const val PROBE_TIMEOUT_MS = 400

    /**
     * 形如 `http://1.2.3.4:3300`，不带尾斜杠；未配置返回 `""`。
     *
     * 配了备用地址时按「主地址 → 备用地址」探测，用能连上的那个；结果缓存 60 秒。
     * 探测在后台线程做，这里只返回上次的结果（首次是主地址），不阻塞调用方。
     */
    fun httpBase(): String {
        val ctx = appCtx ?: return ""
        val primary = ServerConfig.base(ctx)
        val alt = ServerConfig.baseAlt(ctx)
        if (alt.isEmpty() || alt == primary) return primary

        val now = System.currentTimeMillis()
        if (chosen != null && now - chosenAt < CACHE_MS) return chosen ?: primary
        if (!probing) {
            probing = true
            Thread {
                try {
                    val pick = when {
                        reachable(primary) -> primary
                        reachable(alt) -> alt
                        else -> primary // 都不通：先留主地址，让上层显示离线
                    }
                    chosen = pick
                    chosenAt = System.currentTimeMillis()
                } finally {
                    probing = false
                }
            }.apply { isDaemon = true }.start()
        }
        return chosen ?: primary
    }

    /** 只做 TCP 连接，够判断「这台机器在不在这个地址上」。 */
    private fun reachable(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        val host = uri.host ?: return false
        val port = if (uri.port > 0) uri.port else 80
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /** 发往 token 服务的鉴权头值；没有密钥时返回 `""`。 */
    fun authHeader(): String {
        val ctx = appCtx ?: return ""
        val secret = ServerConfig.secret(ctx)
        return if (secret.isEmpty()) "" else "Bearer $secret"
    }

    /** 从 [httpBase] 里解析主机名；解析失败或未配置返回 `""`。 */
    fun host(): String = runCatching { java.net.URI(httpBase()).host ?: "" }.getOrDefault("")

    /** 让下一次 [httpBase] 重新探测（切网络、连上/断开 Tailscale 后调用）。 */
    fun invalidate() {
        chosen = null
        chosenAt = 0L
    }

    /** 兼容旧调用点。 */
    fun warmUp() = invalidate()
}
