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
 */
object ServerConfig {
    private const val PREFS = "aicode-server"
    private const val KEY_BASE = "base"
    private const val KEY_SECRET = "secret"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** base 与 secret 都有值才算配置完成。 */
    fun isConfigured(ctx: Context): Boolean = base(ctx).isNotEmpty() && secret(ctx).isNotEmpty()

    /** 形如 `http://1.2.3.4:3300`；未配置返回 ""。 */
    fun base(ctx: Context): String = prefs(ctx).getString(KEY_BASE, "") ?: ""

    /** 配对得到的共享密钥；未配置返回 ""。 */
    fun secret(ctx: Context): String = prefs(ctx).getString(KEY_SECRET, "") ?: ""

    fun save(ctx: Context, base: String, secret: String) {
        prefs(ctx).edit()
            .putString(KEY_BASE, normalize(base))
            .putString(KEY_SECRET, secret.trim())
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_BASE).remove(KEY_SECRET).apply()
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
