package com.aicode.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 屏幕顶部的悬浮胶囊：AI 操控手机时显示「AI 操作中 · 正在点击…」。
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）；没权限时静默跳过。
 */
object OverlayCapsule {
    private const val HIDE_DELAY_MS = 2600L

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private val hideRunnable = Runnable { hide() }

    fun canDraw(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

    fun show(ctx: Context, text: String) {
        if (!canDraw(ctx)) return
        handler.post {
            try {
                ensureView(ctx)
                label?.text = text
                val v = root ?: return@post
                if (!attached) {
                    windowManager?.addView(v, params)
                    attached = true
                }
                v.animate().cancel()
                v.animate().alpha(1f).setDuration(140).start()
                handler.removeCallbacks(hideRunnable)
                handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
            } catch (_: Throwable) {
            }
        }
    }

    fun hide() {
        handler.post {
            try {
                val v = root ?: return@post
                v.animate().cancel()
                v.animate().alpha(0f).setDuration(220).withEndAction {
                    try {
                        if (attached) {
                            windowManager?.removeView(v)
                            attached = false
                        }
                    } catch (_: Throwable) {
                    }
                }.start()
            } catch (_: Throwable) {
            }
        }
    }

    private fun ensureView(ctx: Context) {
        if (root != null) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#EE1B1B1F"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(14), dp(9), dp(18), dp(9))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5B8CFF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(9) }
        }
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            includeFontPadding = false
        }
        box.addView(dot)
        box.addView(tv)
        box.alpha = 0f
        root = box
        label = tv
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(64)
        }
    }
}
