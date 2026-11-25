package com.aicode.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 后台保活：前台服务 + 常驻通知。
 *
 * 手机系统（尤其 ZTE 这类 ROM）会把后台 App 清掉，网页会话就断了、回来还得重新加载。
 * 挂一个前台服务把进程钉住，代价是通知栏常驻一条「AIcode 正在保持连接」。
 *
 * Android 14+ 前台服务必须声明类型：这里用 specialUse（自托管引擎的会话保持，不是上架应用，不需要审核）。
 */
class KeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "后台保活", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            nm?.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AIcode 正在保持连接")
            .setContentText("会话在后台继续跑，回来不用重新加载")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "keepalive"
        const val PREFS = "aicode"
        const val KEY = "keepalive"

        fun start(ctx: android.content.Context) {
            runCatching {
                val i = Intent(ctx, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: android.content.Context) {
            runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
        }
    }
}
