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
                val snap = svc.snapshotJson()
                // App 自己的网页页：无障碍 bounds 在长会话页会塌缩（实测 [x,116,x,116]），
                // 补一份 DOM 控件列表，phone 里显示为「DOM」。别的 App 不碰后台网页。
                val own = svc.rootPackage()
                val hasView = WebDomBridge.hasWebView()
                val vis = WebDomBridge.visible()
                val dom = if (own == ctx.packageName && vis) WebDomBridge.snapshot() else null
                if (dom != null && dom.length() > 0) snap.put("dom", dom)
                // 诊断字段：DOM 桥没生效时，一眼看出卡在哪一步（rootPackage / attach / 可见性 / JS）
                snap.put(
                    "domDebug",
                    JSONObject()
                        .put("own", own)
                        .put("self", ctx.packageName)
                        .put("hasView", hasView)
                        .put("visible", vis)
                        .put("domN", dom?.length() ?: -1),
                )
                out.put("data", snap)
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
                val selector = cmd.optString("selector")
                var label: String? = null
                var via = ""
                var fallback = ""
                var error: String? = null
                // 1) App 自己的网页页优先走 DOM：按选择器/可见文字点，不碰坐标，
                //    不受分辨率、滚动位置、键盘动画影响（2026-11-28 长会话页实测）。
                //    DOM「明确失败」（选择器不存在 / 元素 disabled / 不可见）时直接报错、不回退：
                //    无障碍是按「包含」匹配文字，空输入时点「发送消息」会点到同名的历史消息
                //    （2026-11-28 独立验证实测）。只有「不是 App 网页页」才回退无障碍。
                val domTried = selector.isNotEmpty() || text.isNotEmpty()
                val domPage = domTried && svc.rootPackage() == ctx.packageName && WebDomBridge.visible()
                var domReason = ""
                if (domPage) {
                    val res = if (selector.isNotEmpty()) {
                        WebDomBridge.clickSelector(selector)
                    } else {
                        WebDomBridge.clickText(text)
                    }
                    if (res == null) {
                        domReason = "bridge-unavailable"
                    } else if (res.optBoolean("ok")) {
                        label = res.optString("label")
                            .ifEmpty { res.optString("sel") }
                            .ifEmpty { if (selector.isNotEmpty()) selector else text }
                        // 元素不在视口里：JS 先 scrollIntoView 再点（DSH 的 WebView 物理滑动不生效）
                        via = if (res.optBoolean("scrolled")) "dom-scroll" else "dom"
                    } else {
                        domReason = res.optString("reason").ifEmpty { "unknown" }
                    }
                    if (label == null) {
                        // 例外：网页 DOM 里根本没有这段文字，目标可能是网页外的原生控件（标题栏/底部 tab）。
                        // 只允许「精确同名」的无障碍回退，宁可报错也不做包含匹配。
                        if (domReason == "not-found" && text.isNotEmpty()) {
                            label = svc.tapTextExact(text)
                            if (label != null) {
                                via = "a11y"
                                fallback = "dom-not-found"
                            }
                        }
                        if (label == null) error = domError(domReason, selector, text)
                    }
                } else if (domTried) {
                    // 2) 不是 DOM 页面（别的 App / App 的设置页 / 网页不可见）：回退无障碍，标明原因
                    fallback = "not-dom-page"
                }
                // 3) 无障碍回退：ref 永远走这里；文字/坐标兜底
                if (label == null && !domPage) {
                    when {
                        ref.isNotEmpty() -> {
                            label = svc.tapRef(ref)
                            if (label != null) via = "a11y"
                        }
                        text.isNotEmpty() -> {
                            label = svc.tapText(text)
                            if (label != null) via = "a11y"
                        }
                        selector.isNotEmpty() -> {
                            // 选择器只对网页有意义；不在网页页时明确说清楚，不拿无障碍去猜
                            error = "不在 App 网页页（WebView 不可见），选择器无法使用"
                        }
                        else -> {
                            val x = cmd.optDouble("x", -1.0).toFloat()
                            val y = cmd.optDouble("y", -1.0).toFloat()
                            if (x >= 0 && y >= 0 && ControlService.tap(x, y)) {
                                label = "坐标"
                                via = "coord"
                            }
                        }
                    }
                    if (label == null && error == null) error = "没找到可点击的元素"
                }
                if (label != null) {
                    OverlayCapsule.show(ctx, "AI 操作中 · 点击「$label」")
                    out.put("label", label)
                    out.put("via", via)
                    if (fallback.isNotEmpty()) out.put("fallback", fallback)
                    true
                } else {
                    // 失败也带来源：DOM 页失败标 DOM，无障碍失败标 a11y，选择器不在网页页标 unavailable。
                    // 旧版 App 根本不回 via，phone 会显示「未知/旧版本」。
                    out.put(
                        "via",
                        when {
                            domPage -> "dom"
                            ref.isNotEmpty() || text.isNotEmpty() -> "a11y"
                            selector.isNotEmpty() -> "unavailable"
                            else -> ""
                        },
                    )
                    if (fallback.isNotEmpty()) out.put("fallback", fallback)
                    out.put("error", error ?: "没找到可点击的元素")
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

    /**
     * DOM 明确失败 → 中文原因。三种原因分开报（未匹配到元素 / disabled / 不可见），
     * 排障时能直接定位，不再一律「没找到可点击的元素」（2026-11-28 独立验证要求）。
     */
    private fun domError(reason: String, selector: String, text: String): String {
        val target = if (selector.isNotEmpty()) "选择器 $selector" else "文字「$text」"
        return when (reason) {
            "selector-not-found" -> "未匹配到元素：网页里找不到$target"
            "not-found" -> "未匹配到元素：网页里找不到$target"
            "disabled" -> "匹配到$target，但 disabled（不可点）"
            "not-visible" -> "匹配到$target，但不可见"
            "bad-selector" -> "选择器/文字为空或写法不对：$target"
            "click-error" -> "匹配到$target，但点击抛错"
            "bridge-unavailable" -> "DOM 桥未响应（网页可能还在加载）"
            else -> "DOM 点击失败（$reason）"
        }
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
