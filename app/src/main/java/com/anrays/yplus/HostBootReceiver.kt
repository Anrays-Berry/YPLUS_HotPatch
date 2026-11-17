package com.anrays.yplus

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 开机 / 上电 / 车机就绪自启。
 *
 * 时序与事件表**逐条对齐业务应用**（`core/StartupRecovery.kt` + `core/AppBootReceiver`）：
 * 宿主只是业务应用的载体 —— 业务应用靠这些广播能起来，就说明这套事件在这台车机上确实会到；
 * 那盒子也必须接同样的广播、用同样的延迟节奏把沙箱里的业务拉起来，否则盒子自己起不来，
 * 业务应用再能自启也没用。
 *
 * 两档触发（与业务应用一致）：
 *   · FULL（开机/ACC/电源/系统就绪）—— 10 秒冷却，按 0 / 6 / 20 / 60 秒恢复四次，
 *     因为沙箱的 :black 服务进程和业务进程是分几批起来的，越早起越可能抢不到；
 *   · QUIET（亮屏/蓝牙）—— 120 秒节流，只压 0 / 10 秒两次，避免亮屏频繁拉起。
 *
 * 车机上**没有 root**，root 守护脚本（[HostKeepAlive]）在真机不参与，这条广播链才是主力。
 */
class HostBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val quiet = QUIET_ACTIONS.contains(action)
        val now = System.currentTimeMillis()

        if (quiet) {
            val elapsed = SystemClock.elapsedRealtime()
            if (elapsed - lastQuietMs < QUIET_THROTTLE_MS) return
            lastQuietMs = elapsed
        } else {
            if (now - lastFullMs < COOLDOWN_MS) return
            lastFullMs = now
        }

        val delays = if (quiet) QUIET_DELAYS_MS else FULL_DELAYS_MS
        Log.i(TAG, "trigger=$action quiet=$quiet → 恢复计划 ${delays.joinToString("/")}s")
        val app = context.applicationContext
        for (delay in delays) {
            if (delay <= 0L) {
                revive(app, action)
            } else {
                main.postDelayed({ revive(app, action) }, delay)
            }
        }
    }

    /** 一次恢复：麦克风前台服务 + 静默拉起沙箱业务（都不启动 Activity） */
    private fun revive(app: Context, action: String) {
        // ① microphone FGS：沙箱内后台录音的前提（按 UID 判定，见 HostMicService）
        runCatching { HostMicService.start(app) }
            .onFailure { Log.w(TAG, "start HostMicService failed", it) }
        // ② 沙箱业务：只拉进程 + 起业务保活服务，不开界面
        runCatching { HostBridge.onReviveBroadcast() }
            .onFailure { Log.w(TAG, "revive business failed", it) }
        // ③ root 守护脚本自愈：无 root（车机）时内部直接跳过
        runCatching { HostKeepAlive.ensureRunning(app) }
            .onFailure { Log.w(TAG, "keepalive ensure failed", it) }
        // ④ 宿主无障碍保活：开机/上电后系统可能已把服务摘掉，先补一次（固定开启，见 HostAccGuard）
        runCatching { HostAccGuard.ensure(app) }
            .onFailure { Log.w(TAG, "acc guard ensure failed", it) }
        // ⚠️ 这里**不做**业务 APK 热更：开机/上电无人操作，装包会把刚拉起来的业务进程杀掉。
        //    热更只由用户主动打开 App 触发（HotUpdateManager.checkOnLaunch）。
    }

    companion object {
        const val TAG = "HostBoot"

        /** 业务应用的恢复节奏（StartupRecovery.FULL_DELAYS_MS / QUIET_DELAYS_MS） */
        private val FULL_DELAYS_MS = longArrayOf(0, 6000, 20000, 60000)
        private val QUIET_DELAYS_MS = longArrayOf(0, 10000)

        private const val COOLDOWN_MS = 10_000L
        private const val QUIET_THROTTLE_MS = 120_000L

        /** 亮屏/蓝牙类：只做节流恢复，不当成"刚开机" */
        private val QUIET_ACTIONS = setOf(
            Intent.ACTION_SCREEN_ON,
            "ecarx.intent.action.DISPLAY_ON",
            BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED
        )

        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var lastFullMs = 0L

        @Volatile
        private var lastQuietMs = 0L
    }
}
