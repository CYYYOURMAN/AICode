package com.aicode.app

import android.content.Context
import com.aicode.app.data.ServerHost
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手机 UI 桥：把当前屏幕快照推给服务器（GET /phone/ui 可读），
 * 同时轮询服务器下发的命令（GET /phone/cmd → 执行 → POST /phone/result）。
 *
 * 服务器地址走 [ServerHost.httpBase()]（运行时配置，见 ServerConfig）。
 * 没配置服务器时整条桥静默跳过，不会发请求也不会崩。
 * 命令走「按 ref / 按文字」而不是坐标，动作时顶部弹「AI 操作中」悬浮胶囊。
 */
object PhoneBridge {
    private var lastPushAt = 0L

    /**
     * 由 ControlService 定时调用（约 1 秒一次）。
     *
     * 谁能控制手机：**两个包都轮询，谁先取到命令谁执行**（2026-11-26 改）。
     *
     * 为什么不再按包名排除：网关的 /phone/cmd 是 `PHONE_CMDS.pop(0)`，命令取一次就删，
     * 两个包同时轮询也不会重复执行。而「按无障碍开关判断谁能干」并不可靠——
     * 系统设置里显示「已开启」，进程被省电冻结时服务其实没在跑，结果两个都不干活。
     * 现在谁活着谁接，发版/冻结/装包撤授权都不会让控制断掉。
     */
    fun tick(ctx: Context) {
        // 无障碍服务可能先于 MainActivity 被系统拉起：这里补一次 init，否则读不到运行时配置。
        ServerHost.init(ctx)
        pollCmd(ctx)
        val now = System.currentTimeMillis()
        if (now - lastPushAt > 60_000) {
            lastPushAt = now
            pushUi(ctx)
        }
    }

    fun pushUi(ctx: Context) {
        // 未配置服务器：没有地址可推，直接跳过（空 URL 会抛 MalformedURLException）。
        val base = ServerHost.httpBase()
        if (base.isEmpty()) return
        val svc = ControlService.instance ?: return
        val snap = try {
            svc.snapshotJson()
        } catch (_: Throwable) {
            return
        }
        snap.put("a11y", true)
        snap.put("overlay", OverlayCapsule.canDraw(ctx))
        snap.put("ts", System.currentTimeMillis())
        post(base + "/phone/ui", snap.toString())
    }

    fun pollCmd(ctx: Context) {
        val base = ServerHost.httpBase()
        if (base.isEmpty()) return
        val body = get(base + "/phone/cmd") ?: return
        val id = body.optLong("id", 0L)
        val cmd = body.optJSONObject("cmd") ?: return
        val result = JSONObject().put("id", id)
        try {
            result.put("ok", exec(ctx, cmd, result))
        } catch (e: Throwable) {
            result.put("ok", false).put("error", e.message ?: e.toString())
        }
        post(base + "/phone/result", result.toString())
    }

    private fun exec(ctx: Context, cmd: JSONObject, out: JSONObject): Boolean {
        val svc = ControlService.instance ?: run {
            out.put("error", "无障碍服务未开启")
            return false
        }
        return when (val action = cmd.optString("action")) {
            // 批量动作：一次往返里连着做多步，每步之间有短暂停顿等界面稳定。
            // 某步失败默认停下（optional=true 的步失败则继续），结果整包一次性回报。
            "seq" -> {
                val steps = cmd.optJSONArray("steps")
                if (steps == null || steps.length() == 0) {
                    out.put("error", "seq 没有步骤")
                    false
                } else {
                    val results = org.json.JSONArray()
                    var allOk = true
                    val total = steps.length()
                    OverlayCapsule.show(ctx, "AI 操作中 · $total 步")
                    for (i in 0 until total) {
                        val step = steps.optJSONObject(i)
                        if (step == null) continue
                        val stepAction = step.optString("action")
                        val one = JSONObject().put("i", i).put("action", stepAction)
                        val ok = try {
                            exec(ctx, step, one)
                        } catch (e: Throwable) {
                            one.put("error", e.message ?: e.toString())
                            false
                        }
                        one.put("ok", ok)
                        results.put(one)
                        if (!ok && !step.optBoolean("optional", false)) {
                            allOk = false
                            break
                        }
                        if (i < total - 1) {
                            val pause = when (stepAction) {
                                "sleep" -> 0L
                                "wait" -> 100L
                                else -> step.optLong("pause", 260L).coerceIn(0L, 5000L)
                            }
                            if (pause > 0) Thread.sleep(pause)
                        }
                    }
                    out.put("results", results)
                    out.put("done", results.length())
                    out.put("total", total)
                    allOk
                }
            }
            "sleep" -> {
                Thread.sleep(cmd.optLong("ms", 500L).coerceIn(0L, 10_000L))
                true
            }
            "snapshot" -> {
                out.put("data", svc.snapshotJson())
                true
            }
            "shot" -> {
                val b64 = screenshotBase64(svc)
                if (b64 != null) {
                    out.put("image", b64)
                    out.put("format", "png")
                    true
                } else {
                    out.put("error", "截图失败（需要给 App 授予 root 权限）")
                    false
                }
            }
            "state" -> {
                val snap = svc.snapshotJson()
                out.put("package", snap.optString("package"))
                out.put("screen", snap.optJSONArray("screen"))
                out.put("elements", snap.optJSONArray("elements")?.length() ?: 0)
                true
            }
            "tap" -> {
                val ref = cmd.optString("ref")
                val text = cmd.optString("text")
                val label = when {
                    ref.isNotEmpty() -> svc.tapRef(ref)
                    text.isNotEmpty() -> svc.tapText(text)
                    else -> {
                        val x = cmd.optDouble("x", -1.0).toFloat()
                        val y = cmd.optDouble("y", -1.0).toFloat()
                        if (x >= 0 && y >= 0 && ControlService.tap(x, y)) "坐标" else null
                    }
                }
                if (label != null) {
                    OverlayCapsule.show(ctx, "AI 操作中 · 点击「$label」")
                    out.put("label", label)
                    true
                } else {
                    out.put("error", "没找到可点击的元素")
                    false
                }
            }
            "type" -> {
                val label = svc.typeText(cmd.optString("ref"), cmd.optString("text"))
                if (label != null) {
                    OverlayCapsule.show(ctx, "AI 操作中 · 输入文字")
                    true
                } else {
                    out.put("error", "没有可输入的输入框")
                    false
                }
            }
            "scroll" -> {
                val dir = cmd.optString("dir", "forward")
                val done = svc.scrollDir(dir) != null
                if (done) OverlayCapsule.show(ctx, "AI 操作中 · 滑动")
                done
            }
            "swipe" -> {
                val x1 = cmd.optDouble("x1", 0.0).toFloat()
                val y1 = cmd.optDouble("y1", 0.0).toFloat()
                val x2 = cmd.optDouble("x2", 0.0).toFloat()
                val y2 = cmd.optDouble("y2", 0.0).toFloat()
                val dur = cmd.optInt("duration", 300)
                val done = if (x1 != 0f || y1 != 0f || x2 != 0f || y2 != 0f) {
                    svc.swipePath(x1, y1, x2, y2, dur)
                } else {
                    svc.swipeDir(cmd.optString("dir", "up"))
                }
                if (done) OverlayCapsule.show(ctx, "AI 操作中 · 滑动")
                done
            }
            "back", "home", "recents", "notifications" -> {
                val done = when (action) {
                    "back" -> ControlService.back()
                    "home" -> ControlService.home()
                    "recents" -> ControlService.recents()
                    else -> ControlService.notifications()
                }
                if (done) OverlayCapsule.show(ctx, "AI 操作中 · ${actionCn(action)}")
                done
            }
            "open" -> {
                val pkg = cmd.optString("package")
                val done = pkg.isNotEmpty() && svc.openPackage(pkg) != null
                if (done) OverlayCapsule.show(ctx, "AI 操作中 · 打开应用")
                done
            }
            "wait" -> {
                val text = cmd.optString("text")
                val timeout = cmd.optLong("timeout", 8000L).coerceIn(500L, 20000L)
                val found = waitText(svc, text, timeout)
                out.put("found", found)
                found
            }
            "notify" -> {
                val t = cmd.optString("title").ifEmpty { "管家" }
                val body = cmd.optString("text")
                notifyAlert(ctx, t, body)
                out.put("notified", true)
                true
            }
            else -> {
                out.put("error", "未知动作 $action")
                false
            }
        }
    }

    /** 无障碍截图，只在内存里转 base64，手机上不留文件。 */
    private fun screenshotBase64(svc: ControlService): String? {
        val bmp = svc.screenshot() ?: return null
        return try {
            val bytes = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
            bmp.recycle()
            android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    private fun waitText(svc: ControlService, text: String, timeoutMs: Long): Boolean {
        if (text.isEmpty()) return false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (svc.existsText(text)) return true
            try {
                Thread.sleep(220)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun actionCn(action: String): String = when (action) {
        "back" -> "返回"
        "home" -> "回主页"
        "recents" -> "打开最近任务"
        "notifications" -> "下拉通知栏"
        else -> action
    }

    /**
     * 服务器主动推一条通知到手机（管家告警用）。
     * 渠道复用 NotificationWatcher 的 aicode-watch；不是无障碍动作，但轮询靠 ControlService 驱动。
     */
    private fun notifyAlert(ctx: Context, title: String, text: String) {
        val ch = "aicode-watch"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(ch, "管家告警", android.app.NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx,
            title.hashCode(),
            android.content.Intent(ctx, MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            flags
        )
        val b = if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(ctx, ch)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(ctx)
        }
        val n = b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(("butler-" + title).hashCode(), n)
    }

    private fun post(url: String, body: String) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", ServerHost.authHeader())
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
            c.disconnect()
        } catch (_: Throwable) {
        }
    }

    private fun get(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", ServerHost.authHeader())
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val s = c.inputStream.bufferedReader().readText()
        c.disconnect()
        JSONObject(s)
    } catch (_: Throwable) {
        null
    }
}
