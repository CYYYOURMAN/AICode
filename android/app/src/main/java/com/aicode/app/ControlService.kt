package com.aicode.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机控制：无障碍服务。
 *
 * 思路照 GitHub 上 DroidRun Portal / agent-droid 那套：
 * 先把当前屏幕的可交互元素做成带编号（ref）的快照，再按 ref / 文字去点，而不是硬算坐标；
 * 每个动作都会在屏幕顶部弹一个「AI 操作中 · …」的悬浮胶囊，让人一眼看出手机正在被操作。
 */
class ControlService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 无障碍服务常驻：手机控制轮询放这里，App 切后台/Activity 销毁后命令通道不断。 */
    private val loopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        loopScope.launch {
            while (isActive) {
                runCatching { PhoneBridge.tick(applicationContext) }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        loopScope.cancel()
        instance = null
        super.onDestroy()
    }

    /** ref -> 节点信息，快照后生效；动作前会 refresh，失效了就按文字重新找。 */
    private data class CachedNode(
        val node: AccessibilityNodeInfo,
        val text: String,
        val desc: String,
        val bounds: Rect,
    )

    private val nodeCache = HashMap<String, CachedNode>()

    // ---------------- 快照 ----------------

    fun snapshotJson(): JSONObject {
        val out = JSONObject()
        val elements = JSONArray()
        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        }
        out.put("package", root?.packageName?.toString() ?: "")
        val screen = Rect()
        if (root != null) root.getBoundsInScreen(screen)
        if (screen.isEmpty) {
            val dm = resources.displayMetrics
            screen.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        out.put("screen", JSONArray().put(screen.right).put(screen.bottom))
        nodeCache.clear()
        var seq = 0
        if (root != null) {
            walk(root, 0) { node ->
                if (seq < 220 && isInteractive(node)) {
                    val ref = "e${++seq}"
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    nodeCache[ref] = CachedNode(node, textOf(node), node.contentDescription?.toString() ?: "", b)
                    elements.put(nodeJson(ref, node))
                }
            }
        }
        out.put("elements", elements)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 32) return
        visit(node)
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            walk(child, depth + 1, visit)
        }
    }

    private fun isInteractive(n: AccessibilityNodeInfo): Boolean = when {
        n.isVisibleToUser.not() -> false
        else -> n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable || n.isScrollable
    }

    private fun roleOf(n: AccessibilityNodeInfo): String = when {
        n.isEditable -> "input"
        n.isCheckable -> "switch"
        n.isClickable -> "button"
        n.isScrollable -> "scroll"
        else -> "text"
    }

    private fun textOf(n: AccessibilityNodeInfo): String {
        val own = n.text?.toString()?.trim().orEmpty()
        if (own.isNotEmpty()) return own
        val buf = StringBuilder()
        collectText(n, 0, buf)
        return buf.toString().trim()
    }

    private fun collectText(n: AccessibilityNodeInfo, depth: Int, buf: StringBuilder) {
        if (depth > 3 || buf.length > 80) return
        for (i in 0 until n.childCount) {
            val c = try {
                n.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            val t = c.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(t)
                if (buf.length > 80) return
            } else {
                collectText(c, depth + 1, buf)
            }
        }
    }

    private fun nodeJson(ref: String, n: AccessibilityNodeInfo): JSONObject {
        val r = Rect()
        n.getBoundsInScreen(r)
        val o = JSONObject()
        o.put("ref", ref)
        o.put("role", roleOf(n))
        o.put("text", textOf(n))
        o.put("desc", n.contentDescription?.toString() ?: "")
        o.put("bounds", JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
        o.put("clickable", n.isClickable)
        o.put("editable", n.isEditable)
        o.put("scrollable", n.isScrollable)
        if (n.isCheckable) o.put("checked", n.isChecked)
        return o
    }

    // ---------------- 动作 ----------------

    fun tapRef(ref: String): String? {
        val entry = nodeCache[ref] ?: return null
        val label = entry.text.ifEmpty { entry.desc }.ifEmpty { ref }
        val node = resolveNode(entry) ?: return null
        val target = clickableAncestor(node) ?: node
        if (safeClick(target)) return label
        return if (tapBounds(target)) label else null
    }

    private fun resolveNode(entry: CachedNode): AccessibilityNodeInfo? {
        val cached = entry.node
        if (cached.refresh() && sameBounds(cached, entry.bounds)) return cached
        val needle = entry.text.ifEmpty { entry.desc }
        if (needle.isEmpty()) return null
        return findByTextOrDesc(needle).firstOrNull()
    }

    private fun sameBounds(n: AccessibilityNodeInfo, b: Rect): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r == b
    }

    private fun findByTextOrDesc(text: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val root = activeRoot() ?: return out
        val byText = try {
            root.findAccessibilityNodeInfosByText(text)
        } catch (_: Throwable) {
            emptyList()
        }
        out.addAll(byText)
        if (out.isEmpty()) {
            walk(root, 0) { n ->
                if (out.size >= 30) return@walk
                val t = n.text?.toString() ?: ""
                val d = n.contentDescription?.toString() ?: ""
                if (t.contains(text, ignoreCase = true) || d.contains(text, ignoreCase = true)) out.add(n)
            }
        }
        return out
    }

    fun tapText(text: String): String? {
        val matches = findByTextOrDesc(text)
        for (n in matches) {
            val target = clickableAncestor(n) ?: n
            if (safeClick(target)) return textOf(n).ifEmpty { text }
            if (tapBounds(target)) return textOf(n).ifEmpty { text }
        }
        return null
    }

    fun typeText(target: String?, text: String): String? {
        var node: AccessibilityNodeInfo? = null
        if (!target.isNullOrEmpty()) {
            node = nodeCache[target]?.let { resolveNode(it) }
            if (node == null) {
                node = findByTextOrDesc(target).firstOrNull { it.isEditable }
            }
        }
        if (node == null) node = activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val n = node ?: return null
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return text
        if (tapBounds(n)) {
            val focused = activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return text
        }
        return null
    }

    fun scrollDir(dir: String): String? {
        // 手势滑动比 performAction(SCROLL_*) 可靠：很多列表（系统设置等）会返回 true 但完全不移动。
        // 滑动距离按屏幕自适应（四分之一屏），不会滑过头。
        if (swipeDir(dir)) return dir
        val root = activeRoot()
        val target = root?.let { findScrollable(it, 0) } ?: return null
        val forward = dir != "back" && dir != "down"
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return if (target.performAction(action)) dir else null
    }

    fun swipeDir(dir: String): Boolean {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val half = dm.heightPixels / 4f
        return when (dir) {
            "up" -> swipePath(cx, cy + half, cx, cy - half, 300)
            "down" -> swipePath(cx, cy - half, cx, cy + half, 300)
            "left" -> swipePath(cx + half, cy, cx - half, cy, 300)
            "right" -> swipePath(cx - half, cy, cx + half, cy, 300)
            else -> false
        }
    }

    fun swipePath(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.toLong().coerceIn(100L, 1500L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 无障碍直接截屏（Android 11+，不用 root、不落盘）。 */
    fun screenshot(): android.graphics.Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var bitmap: android.graphics.Bitmap? = null
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            hb.close()
                            bitmap = bmp?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        } catch (_: Throwable) {
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
        return bitmap
    }

    fun existsText(text: String): Boolean {
        if (text.isEmpty()) return false
        return findByTextOrDesc(text).isNotEmpty()
    }

    fun openPackage(pkg: String): String? {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return null
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            pkg
        } catch (_: Throwable) {
            null
        }
    }

    private fun activeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: Throwable) {
        null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = try {
                cur.parent
            } catch (_: Throwable) {
                null
            }
            depth++
        }
        return null
    }

    private fun safeClick(node: AccessibilityNodeInfo): Boolean = try {
        node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (_: Throwable) {
        false
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 20) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val c = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            findScrollable(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun tapBounds(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    companion object {
        @Volatile
        var instance: ControlService? = null

        fun tap(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 24) return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }

        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        fun home(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        fun notifications(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false

        /** 系统里无障碍服务是否真的开着。
         *  instance 只是「本进程里的服务实例」，进程被杀后为空但系统授权还在——不能拿它判断。
         */
        fun isEnabledInSystem(ctx: android.content.Context): Boolean {
            val am = runCatching {
                ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager
            }.getOrNull() ?: return false
            return runCatching {
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                ).any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }
            }.getOrDefault(false)
        }

        /** 一键修复：App 更新/重启后系统可能把无障碍服务关掉，用 root 重新写回设置。 */
        fun repairAccessibility(ctx: android.content.Context): Boolean {
            return try {
                val pkg = ctx.packageName
                val service = "$pkg/$pkg.ControlService"
                val proc = Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "settings put secure enabled_accessibility_services $service; settings put secure accessibility_enabled 1",
                    ),
                )
                val done = proc.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                done && proc.exitValue() == 0
            } catch (_: Throwable) {
                false
            }
        }
    }
}
