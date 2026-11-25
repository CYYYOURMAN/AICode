package com.aicode.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    /** 服务器 version.json 里的 APK SHA-256（小写十六进制），下载后用来校验完整性。 */
    val sha256: String = "",
)

/**
 * App 自更新：从 token 服务检查版本、下载 APK、拉起系统安装。
 * 服务器地址走 [ServerHost.httpBase()]（来自运行时配置，见 [ServerConfig]）。
 * 服务器上的 APK 由 app-release 脚本发布（构建 → 装机 → 上传 → 写 version.json）。
 */
object AppUpdater {
    private const val TAG = "AppUpdater"

    /** 稳定版（com.aicode.app）走 stable 通道；测试版（com.aicode.app.beta）走 beta 通道。 */
    private fun channelOf(ctx: Context): String =
        if (ctx.packageName.endsWith(".beta")) "beta" else "stable"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun check(ctx: Context): AppUpdateInfo? {
        // 还没配置服务器：空 base 拼出来的 URL 会被 OkHttp 抛 IllegalArgumentException，直接跳过。
        val base = ServerHost.httpBase()
        if (base.isEmpty()) {
            Log.w(TAG, "未配置服务器地址，跳过检查更新")
            return null
        }
        return try {
            val request = Request.Builder()
                .url(base + "/app/version?channel=" + channelOf(ctx))
                .header("Authorization", ServerHost.authHeader())
                .build()
            client.newCall(request).execute().use { resp ->
                val body = if (resp.isSuccessful) resp.body?.string() else null
                if (body.isNullOrEmpty()) {
                    Log.w(TAG, "检查更新失败：HTTP " + resp.code + "，响应为空")
                    null
                } else {
                    val o = JSONObject(body)
                    val code = o.optInt("versionCode", 0)
                    if (code <= 0) {
                        Log.w(TAG, "检查更新失败：versionCode 无效")
                        null
                    } else {
                        AppUpdateInfo(
                            code,
                            o.optString("versionName", ""),
                            o.optLong("size", 0),
                            o.optString("sha256", ""),
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "检查更新失败：" + (e.message ?: e.toString()))
            null
        }
    }

    fun download(ctx: Context, onProgress: (Int) -> Unit = {}): File? {
        val base = ServerHost.httpBase()
        if (base.isEmpty()) {
            Log.w(TAG, "未配置服务器地址，跳过下载")
            return null
        }
        val target = File(ctx.cacheDir, "aicode-update.apk")
        // 下载前先取服务器声明的 sha256；取不到就跳过校验（记日志），不因为一次抖动让更新彻底失败。
        val expected = (check(ctx)?.sha256 ?: "").trim().lowercase()
        if (expected.isEmpty()) {
            Log.w(TAG, "下载前未取到 sha256，跳过完整性校验")
        }
        return try {
            if (target.exists()) target.delete()
            val request = Request.Builder()
                .url(base + "/app/download?channel=" + channelOf(ctx))
                .header("Authorization", ServerHost.authHeader())
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "下载失败：HTTP " + resp.code)
                    return null
                }
                val total = resp.body?.contentLength() ?: -1L
                val src = resp.body?.byteStream()
                if (src == null) {
                    Log.w(TAG, "下载失败：响应体为空")
                    return null
                }
                src.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(16384)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(((done * 100) / total).toInt())
                        }
                    }
                }
            }
            if (target.length() <= 0L) {
                Log.w(TAG, "下载失败：文件为空")
                target.delete()
                return null
            }
            if (expected.isNotEmpty()) {
                val actual = sha256Of(target)
                if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                    Log.w(TAG, "APK 校验失败：期望 " + expected + "，实际 " + actual + "，已删除下载文件")
                    target.delete()
                    return null
                }
                Log.i(TAG, "APK 校验通过：" + actual)
            }
            target
        } catch (e: Throwable) {
            Log.w(TAG, "下载异常：" + (e.message ?: e.toString()))
            runCatching { target.delete() }
            null
        }
    }

    /** 整文件 SHA-256，小写十六进制；读文件失败返回 null。 */
    private fun sha256Of(file: File): String? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    } catch (_: Throwable) {
        null
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}
