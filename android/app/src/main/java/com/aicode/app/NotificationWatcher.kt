package com.aicode.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.aicode.app.data.EngineClient
import com.aicode.app.data.TokenService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val WATCH_TAG = "aicode-watch"
private const val CH = "aicode-watch"
private const val PREFS = "aicode-watch"

private fun nowHms(): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())

private fun saveStatus(ctx: Context, text: String) {
    runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", text)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }
}

/**
 * 前台轮询：发现某个会话从「运行中」变成「结束」就发一条通知（回合完成）。
 * 纯 Compose 协程实现，不依赖 Service（部分 ROM 会限制后台服务）。
 * 请求失败（引擎重启 token 变化）会重置 cookie 下一轮重新取，避免监听静默失效。
 */
@Composable
fun NotificationWatcher(ctx: Context) {
    LaunchedEffect(Unit) {
        ensureChannel(ctx)
        // session/list 的 running 在回合运行时为 true（实测），用它判断"回合完成"。
        val running = mutableMapOf<String, Pair<String, Long>>()
        val titles = mutableMapOf<String, String>()
        var cookie = ""
        var base = ""
        var rpc = 0
        while (true) {
            try {
                if (cookie.isEmpty()) {
                    val ep = withContext(Dispatchers.IO) { TokenService.fetch() }
                    if (ep == null) {
                        Log.w(WATCH_TAG, "token 服务没连上")
                    } else {
                        base = ep.base
                        cookie = withContext(Dispatchers.IO) { EngineClient(ep.base).exchange(ep.token) }
                        Log.i(WATCH_TAG, "取到 cookie len=${cookie.length}")
                    }
                }
                if (cookie.isNotEmpty()) {
                    val payload = JSONObject()
                        .put("type", "client-request")
                        .put("rpcId", "watch-${rpc++}")
                        .put("method", "session/list")
                        .put("payload", JSONObject().put("args", JSONObject().put("_request", JSONObject())))
                    val res = withContext(Dispatchers.IO) {
                        EngineClient(base).post(cookie, "/api/session/list", payload)
                    }
                    val ok = res?.optJSONObject("result")?.optBoolean("ok") == true
                    if (!ok) {
                        Log.w(WATCH_TAG, "session/list 失败，重置 cookie 下轮重取")
                        saveStatus(ctx, "轮询 ${nowHms()} 失败（token 失效？重试中）")
                        cookie = ""
                    } else {
                        val items = res.optJSONObject("result")?.optJSONObject("value")?.optJSONArray("items")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val o = items.optJSONObject(i) ?: continue
                                val id = o.optString("sessionId")
                                if (id.isEmpty()) continue
                                // 子会话（子智能体）不通知：它们在侧边栏里没有独立会话行，
                                // 点了也跳不过去，而且一次任务能起几十个，通知会刷屏
                                if (!o.isNull("parentSessionId")) continue
                                val t = o.optJSONObject("projections")?.optJSONObject("values")
                                    ?.optString("title").orEmpty().ifEmpty { id.take(10) }
                                titles[id] = t
                                if (o.optBoolean("running", false)) {
                                    if (!running.containsKey(id)) running[id] = t to System.currentTimeMillis()
                                } else if (running.containsKey(id)) {
                                    val entry = running.remove(id) ?: ("" to 0L)
                                    val title0 = entry.first
                                    val startAt = entry.second
                                    val name = titles[id] ?: title0.ifEmpty { t }
                                    val dur = System.currentTimeMillis() - startAt
                                    // 短回合（<20 秒）不打扰；App 在前台时也不发（用户正看着）
                                    if (dur < 20_000L) {
                                        Log.i(WATCH_TAG, "回合完成（短，跳过通知）：$name ${dur}ms")
                                    } else if (com.aicode.app.MainActivity.isForeground) {
                                        Log.i(WATCH_TAG, "回合完成（前台，跳过通知）：$name")
                                    } else {
                                        Log.i(WATCH_TAG, "回合完成：$name 用时 ${dur / 1000}s")
                                        notifyDone(ctx, name, id, dur)
                                        saveStatus(ctx, "上次通知 ${nowHms()}：$name")
                                    }
                                }
                            }
                        }
                        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString("status", "").orEmpty()
                        if (!last.startsWith("上次通知")) {
                            saveStatus(ctx, "监听中 · 轮询 ${nowHms()} · 运行中 ${running.size} 个会话")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(WATCH_TAG, "轮询异常：${e.message}")
                saveStatus(ctx, "监听异常 ${nowHms()}：${e.message ?: e.javaClass.simpleName}")
                cookie = ""
            }
            delay(20_000)
        }
    }
}

private fun ensureChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val ch = NotificationChannel(CH, "回合完成", NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
}

private fun notifyDone(ctx: Context, title: String, sessionId: String, durationMs: Long) {
    val intent = Intent(ctx, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra("session", sessionId)
    }
    val flags = if (Build.VERSION.SDK_INT >= 23) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
    // 每个会话一个 PendingIntent，点旧通知不会被后发的覆盖掉 extras
    val pi = PendingIntent.getActivity(ctx, sessionId.hashCode(), intent, flags)
    val b = if (Build.VERSION.SDK_INT >= 26) {
        android.app.Notification.Builder(ctx, CH)
    } else {
        @Suppress("DEPRECATION")
        android.app.Notification.Builder(ctx)
    }
    val secs = durationMs / 1000
    val durText = if (secs < 60) "${secs} 秒" else "${secs / 60} 分 ${secs % 60} 秒"
    val n = b.setContentTitle("回合完成：$title")
        .setContentText("用时 $durText · 点开直接到这个会话")
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentIntent(pi)
        .setAutoCancel(true)
        .build()
    ctx.getSystemService(NotificationManager::class.java)?.notify(title.hashCode(), n)
}
