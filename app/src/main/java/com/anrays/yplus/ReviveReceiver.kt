package com.anrays.yplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * root 守护脚本的拉活落点（见 [HostKeepAlive]）。
 *
 * 脚本只发一条显式广播（不带任何 Activity），所以宿主被唤醒后**不会跳到前台**；
 * 这里把后续恢复交回 [HostBridge]：重新拉起沙箱里的业务 APK。
 *
 * ⚠️ 必须是 exported=true：调用方是 shell/root（uid 2000/0），非导出组件会被系统拒绝；
 *    广播是显式指定组件的，所以不需要 intent-filter。
 */
class ReviveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != HostKeepAlive.ACTION_REVIVE) return
        Log.i(TAG, "收到守护脚本拉活广播，进程已就位 → 恢复沙箱业务")
        // 宿主进程此时刚起、BlackBox 尚未完成 doCreate 的情况：HostBridge 内部有延迟重试
        runCatching { HostBridge.onReviveBroadcast() }
            .onFailure { Log.w(TAG, "恢复沙箱业务失败", it) }
        // 进程被拉活后，宿主无障碍服务需要重新绑定（force-stop 会把它摘掉），这里补一次
        runCatching { HostAccGuard.ensure(context) }
            .onFailure { Log.w(TAG, "拉活后无障碍保活失败", it) }
    }

    companion object {
        const val TAG = "HostRevive"
    }
}
