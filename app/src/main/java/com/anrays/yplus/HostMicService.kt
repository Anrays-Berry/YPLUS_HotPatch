package com.anrays.yplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 宿主的「麦克风前台服务」占位。
 *
 * 为什么必须由宿主持有：沙箱内的虚拟 app 与宿主**同 UID**，系统在真正采集音频时按 UID 判断
 * "这个进程有没有 microphone 类型的前台服务"（Android 14 的 while-in-use 限制）。
 * 虚拟 app 自己起的 FGS，在系统眼里是宿主的 `ProxyService$Px`，类型不是 microphone ——
 * 于是 force-stop 复活后（进程在后台、没有可见界面）AudioRecord 只能采到静音，
 * 表现为"悬浮窗在、按下去没声音，手动打开 App 才正常"（用户 2026-10-26 报障）。
 *
 * 启动时机：
 *  · 前台时机 —— [CarBoxApp.onCreate]（App 打开时，FGS 一定允许）；
 *  · 复活时机 —— root 守护脚本（[HostKeepAlive] 的脚本）用
 *    `am start-foreground-service` 以 root 身份补起（shell 身份会被 exported=false 拦住，
 *    root 可以绕过，实测 `isForeground=true types=00000080`）。
 */
class HostMicService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { ensureChannel() }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFY_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFY_ID, buildNotification())
            }
            Log.i(TAG, "microphone FGS ready")
        } catch (t: Throwable) {
            // microphone 类型被拒（RECORD_AUDIO 缺失/厂商校验差异）：退化成普通前台服务，
            // 保底不崩、不残留悬空的前台服务态。
            Log.w(TAG, "startForeground(microphone) failed", t)
            try {
                @Suppress("DEPRECATION")
                startForeground(NOTIFY_ID, buildNotification())
            } catch (_: Throwable) {
                runCatching { stopSelf() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "业务应用后台服务", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("业务应用")
            .setContentText("沙箱应用后台运行中")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HostMic"
        private const val CHANNEL_ID = "host_mic"
        private const val NOTIFY_ID = 2001

        /**
         * 前台时机启动（App 打开时调用，幂等）。
         * ⚠️ 后台/复活场景不要走这里（Android 12+ 后台起 FGS 会被拦），交给 root 脚本的 am。
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, HostMicService::class.java))
            }.onFailure { Log.w(TAG, "startForegroundService failed", it) }
        }
    }
}
