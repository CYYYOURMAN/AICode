package com.aicode.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 引擎地址 + 最新 token。 */
data class EngineEndpoint(val token: String, val base: String, val fishToken: String = "")

/**
 * 从服务器上的 token 服务拿「引擎地址 + 最新 token」。
 * token 每次引擎重启都会变，所以每次要打开网页版前都重新取一次。
 */
data class EngineVersion(
    val current: String,
    val latest: String,
    val node: String = "",
    val npm: String = "",
)

object TokenService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun fetchEngineVersion(): EngineVersion? {
        // 未配置服务器：空 base 拼出来的 URL 会被 OkHttp 抛 IllegalArgumentException，直接返回。
        val base = ServerHost.httpBase()
        if (base.isEmpty()) return null
        return try {
            client.newCall(
                Request.Builder()
                    .url(base + "/engine")
                    .header("Authorization", ServerHost.authHeader())
                    .build()
            ).execute().use { r ->
                val body = if (r.isSuccessful) r.body?.string() else null
                if (body == null) {
                    null
                } else {
                    val o = JSONObject(body)
                    val current = o.optString("current", "")
                    val latest = o.optString("latest", "")
                    if (current.isEmpty()) {
                        null
                    } else {
                        EngineVersion(current, latest, o.optString("node", ""), o.optString("npm", ""))
                    }
                }
            }
        } catch (_: Exception) {
            ServerHost.invalidate()
            null
        }
    }

    fun fetch(): EngineEndpoint? {
        val base = ServerHost.httpBase()
        if (base.isEmpty()) return null
        return try {
            client.newCall(
                Request.Builder()
                    .url(base + "/")
                    .header("Authorization", ServerHost.authHeader())
                    .build()
            ).execute().use { r ->
                val body = if (r.isSuccessful) r.body?.string() else null
                if (body == null) {
                    null
                } else {
                    val o = JSONObject(body)
                    val token = o.optString("token", "")
                    val engineBase = o.optString("base", "")
                    val fish = o.optString("fishToken", "")
                    if (token.isEmpty() || engineBase.isEmpty()) null else EngineEndpoint(token, engineBase, fish)
                }
            }
        } catch (_: Exception) {
            ServerHost.invalidate()
            null
        }
    }
}
