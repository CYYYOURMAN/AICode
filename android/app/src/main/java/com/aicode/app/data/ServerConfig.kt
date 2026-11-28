package com.aicode.app.data

import android.content.Context

/**
 * 服务器地址 + 配对密钥的运行时配置。
 *
 * 开源版不再把「服务器地址 / 密钥」编进 APK：第一次启动由「连接服务器」向导
 * （扫码带上地址和密钥，或手工填）调用 [save] 写进来，之后所有请求都从这里读。
 *
 * 存储：SharedPreferences 文件名 `aicode-server`，key `base` / `secret`；
 * [base] 写入前规范化成 `http://host:port` 形式（无尾斜杠）。
 *
 * MCP（鱼世界等外部工具服务）也走同一个文件：key `mcp_url` / `mcp_token` / `mcp_enabled`。
 * 地址是完整 URL（鱼世界模板见 [FISH_WORLD_MCP_URL]），按用户输入原样存、不补协议。
 */
object ServerConfig {
    private const val PREFS = "aicode-server"
    private const val KEY_BASE = "base"
    private const val KEY_BASE_ALT = "base_alt"
    private const val KEY_SECRET = "secret"
    private const val KEY_HUB_URL = "hub_url"
    private const val KEY_HUB_TOKEN = "hub_token"
    private const val KEY_MCP_URL = "mcp_url"
    private const val KEY_MCP_TOKEN = "mcp_token"
    private const val KEY_MCP_ENABLED = "mcp_enabled"

    /** 鱼世界 MCP 地址模板（设置页「填入鱼世界模板」用）。 */
    const val FISH_WORLD_MCP_URL = "https://api.yuyuworld.app/v1/mcp"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** base 与 secret 都有值才算配置完成。 */
    fun isConfigured(ctx: Context): Boolean = base(ctx).isNotEmpty() && secret(ctx).isNotEmpty()

    /** 形如 `http://1.2.3.4:3300`；未配置返回 ""。 */
    fun base(ctx: Context): String = prefs(ctx).getString(KEY_BASE, "") ?: ""

    /** 配对得到的共享密钥；未配置返回 ""。 */
    fun secret(ctx: Context): String = prefs(ctx).getString(KEY_SECRET, "") ?: ""

    /**
     * 备用地址（可选）：主地址连不上时用它。
     * 典型配置是「主 = 局域网（在家快）、备用 = Tailscale（在外面）」。
     */
    fun baseAlt(ctx: Context): String = prefs(ctx).getString(KEY_BASE_ALT, "") ?: ""

    fun saveAlt(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_BASE_ALT, url.trim()).apply()
    }

    fun save(ctx: Context, base: String, secret: String) {
        prefs(ctx).edit()
            .putString(KEY_BASE, normalize(base))
            .putString(KEY_SECRET, secret.trim())
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_BASE).remove(KEY_SECRET).apply()
    }

    /** AI 中枢地址；未配置返回 ""（App 按服务器 host 推 8310）。 */
    fun hubUrl(ctx: Context): String = prefs(ctx).getString(KEY_HUB_URL, "") ?: ""

    /** AI 中枢令牌；未配置返回 ""（App 会尝试用 DSH 令牌换）。 */
    fun hubToken(ctx: Context): String = prefs(ctx).getString(KEY_HUB_TOKEN, "") ?: ""

    fun saveHub(ctx: Context, url: String, token: String) {
        prefs(ctx).edit()
            .putString(KEY_HUB_URL, url.trim())
            .putString(KEY_HUB_TOKEN, token.trim())
            .apply()
    }

    /** MCP 地址；未配置返回 ""。原样返回，不补协议。 */
    fun mcpUrl(ctx: Context): String = prefs(ctx).getString(KEY_MCP_URL, "") ?: ""

    /** MCP 密钥（Bearer token）；未配置返回 ""。 */
    fun mcpToken(ctx: Context): String = prefs(ctx).getString(KEY_MCP_TOKEN, "") ?: ""

    /** MCP 开关；默认 false（开源版不预置任何 MCP）。 */
    fun mcpEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MCP_ENABLED, false)

    /** 保存 MCP 配置：地址/密钥只 trim，不补协议（用户填什么就存什么）。 */
    fun saveMcp(ctx: Context, url: String, token: String, enabled: Boolean) {
        prefs(ctx).edit()
            .putString(KEY_MCP_URL, url.trim())
            .putString(KEY_MCP_TOKEN, token.trim())
            .putBoolean(KEY_MCP_ENABLED, enabled)
            .apply()
    }

    fun clearMcp(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_MCP_URL)
            .remove(KEY_MCP_TOKEN)
            .remove(KEY_MCP_ENABLED)
            .apply()
    }

    /** 去首尾空格 → 没写协议就补 `http://` → 去尾部 `/`；空串原样返回。 */
    private fun normalize(raw: String): String {
        var v = raw.trim()
        if (v.isEmpty()) return ""
        val lower = v.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) v = "http://$v"
        return v.trimEnd('/')
    }
}
