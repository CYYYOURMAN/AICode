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

/** 一次回合结束的判定：failed = 要不要发通知；reason = 通知正文里写的失败原因。 */
private data class TurnOutcome(val failed: Boolean, val reason: String)

/** 从一条消息的 content 里取第一段纯文本（工具结果用）。 */
private fun textOf(message: JSONObject): String {
    val content = message.optJSONArray("content") ?: return ""
    for (i in 0 until content.length()) {
        val block = content.optJSONObject(i) ?: continue
        if (block.optString("type") == "text") return block.optString("text").trim()
    }
    return ""
}

/**
 * 回合结束后再查一次这个会话最近的事件，判断刚结束的一轮是正常完成还是失败。
 *
 * 为什么不能只看 session/list（2026-11-28 实测）：items 里只有
 * sessionId / updatedAt / agentAvailable / formatStatus / running / blank / cwd /
 * parentSessionId / origin / projections，**没有** status / error / finishReason。
 * 失败标记只存在原始事件的 turn/end 里：
 *   turn/end.data.reason = { kind: completed|aborted|blocked|error|max-tokens|interrupted|forked,
 *                            error?: { message, code, status? } }
 * 所以用 session/events 拉最后几条消息（覆盖到本回合的 turn/end）再判断。
 *
 * 返回 null = 这次没查到（网络或接口失败），调用方不报，避免误报。
 */
private fun inspectLastTurn(base: String, cookie: String, sessionId: String): TurnOutcome? {
    val request = JSONObject()
        .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
        .put("maxMessages", 4)
    val payload = JSONObject()
        .put("type", "client-request")
        .put("rpcId", "watch-ev-${System.nanoTime()}")
        .put("method", "session/events")
        .put("payload", JSONObject().put("args", JSONObject().put("request", request)))
    val res = EngineClient(base).post(cookie, "/api/session/events", payload) ?: return null
    if (res.optJSONObject("result")?.optBoolean("ok") != true) return null
    val records = res.optJSONObject("result")?.optJSONObject("value")?.optJSONArray("records") ?: return null

    // 从新到旧找本回合的 turn/end，同时收集这一轮里有没有工具执行失败。
    var reason: JSONObject? = null
    var toolError = ""
    var stop = false
    for (i in records.length() - 1 downTo 0) {
        if (stop) break
        val event = records.optJSONObject(i)?.optJSONObject("event") ?: continue
        val data = event.optJSONObject("data") ?: JSONObject()
        when (event.optString("type")) {
            "turn/end" -> if (reason == null) reason = data.optJSONObject("reason") ?: JSONObject()
            "turn/start" -> if (reason != null) stop = true
            "tool/result" -> if (reason != null && toolError.isEmpty()) {
                val message = data.optJSONObject("message")
                if (message?.optBoolean("isError") == true) {
                    toolError = textOf(message).ifEmpty { "未知工具错误" }
                }
            }
        }
    }

    if (reason == null) {
        // 没拿到结束标记（可能还没落盘）。为了不误报，只有工具明确报错时才报。
        return if (toolError.isNotEmpty()) TurnOutcome(true, "工具执行失败：${toolError.take(120)}")
        else TurnOutcome(false, "")
    }
    return when (reason.optString("kind")) {
        "error" -> {
            val error = reason.optJSONObject("error")
            val message = error?.optString("message").orEmpty().ifEmpty { "未知错误" }
            val code = error?.optString("code").orEmpty()
            val brief = if (code.isNotEmpty() && code != "UNKNOWN") "$message（$code）" else message
            TurnOutcome(true, "回合出错：${brief.take(160)}")
        }
        "max-tokens" -> TurnOutcome(true, "回合输出达到上限，被截断")
        "interrupted" -> TurnOutcome(true, "回合被中断（进程异常退出）")
        "completed" ->
            if (toolError.isNotEmpty()) TurnOutcome(true, "工具执行失败：${toolError.take(120)}")
            else TurnOutcome(false, "")
        // aborted = 用户自己停止；blocked = 等输入；forked = 分支种子。都不算失败。
        else -> TurnOutcome(false, "")
    }
}

/**
 * 前台轮询：只发现「会话从运行中变成结束」，失败与否再去查一次事件。
 * 正常完成不发通知，只有失败才提醒（用户要求：以前每轮都报，太频繁）。
 * 纯 Compose 协程实现，不依赖 Service（部分 ROM 会限制后台服务）。
 * 请求失败（引擎重启 token 变化）会重置 cookie 下一轮重新取，避免监听静默失效。
 */
@Composable
fun NotificationWatcher(ctx: Context) {
    LaunchedEffect(Unit) {
        ensureChannel(ctx)
        // session/list 的 running 在回合运行时为 true（实测），用它判断「回合结束」。
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
                                    // 只改「发不发」：正常结束一律不报，失败才报。
                                    val outcome = withContext(Dispatchers.IO) {
                                        inspectLastTurn(base, cookie, id)
                                    }
                                    if (outcome == null) {
                                        Log.w(WATCH_TAG, "回合结束但事件查询失败，跳过：$name")
                                        saveStatus(ctx, "查询失败 ${nowHms()}：$name")
                                    } else if (!outcome.failed) {
                                        Log.i(WATCH_TAG, "回合正常结束（不通知）：$name ${dur / 1000}s")
                                    } else if (com.aicode.app.MainActivity.isForeground) {
                                        Log.i(WATCH_TAG, "回合失败（前台，跳过通知）：$name")
                                    } else {
                                        Log.i(WATCH_TAG, "回合失败：$name ${outcome.reason}")
                                        notifyFailed(ctx, name, id, dur, outcome.reason)
                                        saveStatus(ctx, "上次失败通知 ${nowHms()}：$name")
                                    }
                                }
                            }
                        }
                        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString("status", "").orEmpty()
                        if (!last.startsWith("上次失败通知")) {
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
        // 渠道 id 沿用 aicode-watch（管家告警也复用它），只把名字改成中性说法；
        // 老用户已建好的渠道名不会变，但通知开关不会被重置。
        val ch = NotificationChannel(CH, "回合提醒", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "只在回合失败时提醒"
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
}

private fun notifyFailed(ctx: Context, title: String, sessionId: String, durationMs: Long, reason: String) {
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
    val text = "$reason · 用时 $durText · 点开直接到这个会话"
    val n = b.setContentTitle("回合失败：$title")
        .setContentText(text)
        .setStyle(android.app.Notification.BigTextStyle().bigText(text))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(pi)
        .setAutoCancel(true)
        .build()
    ctx.getSystemService(NotificationManager::class.java)?.notify(sessionId.hashCode(), n)
}
