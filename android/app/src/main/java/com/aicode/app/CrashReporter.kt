package com.aicode.app

import android.content.Context
import com.aicode.app.data.ServerHost
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/**
 * 崩溃上报：把未捕获异常 POST 到 token 服务（/app/crash），服务器写 app/crash.log。
 * 方便事后排查「App 突然退出但没人知道原因」的问题。
 */
object CrashReporter {
    fun install(ctx: Context) {
        // 尽早把 applicationContext 交给 ServerHost：onCreate 早期崩溃时也能读到配置的服务器地址。
        runCatching { ServerHost.init(ctx) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                report(ctx, thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun report(ctx: Context, thread: Thread, throwable: Throwable) {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrDefault("?")
        val body = JSONObject()
            .put("package", ctx.packageName)
            .put("version", version)
            .put("thread", thread.name)
            .put("message", throwable.toString())
            .put("stack", writer.toString().take(8000))
            .put("time", System.currentTimeMillis())
        // 必须在独立线程发请求：崩溃线程上做同步网络会抛 NetworkOnMainThreadException
        val worker = Thread {
            try {
                // 未配置服务器时不发请求：空 base 拼出来的 URL 会被 OkHttp 抛 IllegalArgumentException。
                val base = ServerHost.httpBase()
                if (base.isNotEmpty()) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url(base + "/app/crash")
                        .header("Authorization", ServerHost.authHeader())
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(request).execute().close()
                }
            } catch (_: Throwable) {
            }
        }
        worker.start()
        runCatching { worker.join(2500) }
    }
}
