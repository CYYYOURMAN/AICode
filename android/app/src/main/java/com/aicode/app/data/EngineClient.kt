package com.aicode.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EngineClient(private val base: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** 最近一次换 cookie 用的 token；cookie 失效时拿它重换。 */
    @Volatile
    private var lastToken: String = ""

    /**
     * 最近一次换到的 cookie。
     * 冷启动时 exchange 偶尔赶在回环转发/隧道就绪前失败，调用方拿到的会是空 cookie；
     * 这里留一份兜底，调用方传空时用它，避免文件页/鱼世界整页空白。
     */
    @Volatile
    private var cookie: String = ""

    fun exchange(token: String): String {
        if (token.isNotEmpty()) lastToken = token
        val req = Request.Builder().url("$base/?token=$token").get().build()
        val fresh = try {
            client.newCall(req).execute().use { r ->
                r.headers("set-cookie").map { it.substringBefore(';') }.joinToString("; ")
            }
        } catch (_: Exception) {
            ""
        }
        if (fresh.isNotEmpty()) cookie = fresh
        return fresh
    }

    /** cookie 过期或为空时重新换一次；换不到返回空。 */
    private fun refresh(): String {
        if (lastToken.isEmpty()) return ""
        return exchange(lastToken)
    }

    /**
     * 请求失败（cookie 过期时引擎返回纯文本 unauthorized，解析不成 JSON 也是 null）会重换一次 cookie 再试，
     * 这样引擎重启换 token、冷启动没换到 cookie 这两种情况都能自愈，不用用户去点「重新获取 token」。
     */
    fun post(cookieArg: String?, path: String, payload: JSONObject = JSONObject()): JSONObject? {
        val first = cookieArg.orEmpty().ifEmpty { cookie }
        if (first.isNotEmpty()) {
            doPost(first, path, payload)?.let { return it }
        }
        val fresh = refresh()
        if (fresh.isEmpty() || fresh == first) return null
        return doPost(fresh, path, payload)
    }

    fun get(cookieArg: String?, path: String): JSONObject? {
        val first = cookieArg.orEmpty().ifEmpty { cookie }
        if (first.isNotEmpty()) {
            doGet(first, path)?.let { return it }
        }
        val fresh = refresh()
        if (fresh.isEmpty() || fresh == first) return null
        return doGet(fresh, path)
    }

    private fun doPost(cookie: String, path: String, payload: JSONObject): JSONObject? {
        val req = Request.Builder()
            .url("$base$path")
            .header("Cookie", cookie)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (body.isEmpty()) null else JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun doGet(cookie: String, path: String): JSONObject? {
        val req = Request.Builder()
            .url("$base$path")
            .header("Cookie", cookie)
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (body.isEmpty()) null else JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }
}
