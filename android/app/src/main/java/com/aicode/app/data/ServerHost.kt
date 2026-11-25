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

    /** 形如 `http://1.2.3.4:3300`，不带尾斜杠；未配置返回 `""`。 */
    fun httpBase(): String {
        val ctx = appCtx ?: return ""
        return ServerConfig.base(ctx)
    }

    /** 发往 token 服务的鉴权头值；没有密钥时返回 `""`。 */
    fun authHeader(): String {
        val ctx = appCtx ?: return ""
        val secret = ServerConfig.secret(ctx)
        return if (secret.isEmpty()) "" else "Bearer $secret"
    }

    /** 从 [httpBase] 里解析主机名；解析失败或未配置返回 `""`。 */
    fun host(): String = runCatching { java.net.URI(httpBase()).host ?: "" }.getOrDefault("")

    /** 兼容旧调用点：地址改为运行时配置后不再做网络探测，这里无事可做。 */
    fun warmUp() = Unit

    /** 兼容旧调用点：不再有探测缓存，这里无事可做。 */
    fun invalidate() = Unit
}
