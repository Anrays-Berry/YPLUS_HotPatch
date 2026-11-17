package com.anrays.yplus

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

// 业务 App（沙箱里的源PLUS）→ 宿主的命令入口。
//
// 通道：业务在虚拟环境里 startService 到宿主真实组件时，BlackBox 的 IActivityManagerProxy
// 在虚拟包管理器里查不到该组件 → 直接放行给真系统 AMS；两者同 UID，非 exported 也能起。
//
// 命令（EXTRA_CMD）：
//   · install  —— 用户点了立即更新：确认 pending（宿主进程可能被回收过，用 seed 重新 check 一次）
//                 → 下载（进度实时回推业务更新页）→ 沙箱覆盖安装 → 复活业务 → 报 done/failed；
//   · dismiss  —— 用户放弃本次更新；
//   · open_box —— 消费端要求打开宿主的 BlackBox后台界面（DEBUG 页「BlackBox后台」入口）；
//   · overlay  —— 消费端要求显示/隐藏 ⇄ 悬浮按钮（源PLUS 自己不要，别的 App 默认显示）。
class FrameUpdateHostService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra(EXTRA_CMD).orEmpty()
        val seed = intent?.getStringExtra(EXTRA_SEED).orEmpty()
        when (cmd) {
            CMD_INSTALL -> installAsync(seed)
            CMD_DISMISS -> dismiss()
            CMD_OPEN_BOX -> openBoxUi()
            CMD_OVERLAY -> setOverlayVisible(intent?.getBooleanExtra(EXTRA_VISIBLE, true) ?: true)
            CMD_QUERY_PERMS -> pushPerms()
            FrameApi.CMD_API -> intent?.let { FrameApiHost.dispatch(this, it) }
            else -> Log.w(TAG, "unknown cmd: $cmd")
        }
        return START_NOT_STICKY
    }

    /**
     * 消费端（源PLUS）控制 ⇄ 悬浮按钮显隐（用户 2026-10-28）。
     *
     * 正常路径下消费端会用反射直接改**业务进程**里的状态（同进程，立即生效）；
     * 这条 startService 是兜底：主进程里的实例也同步成同一状态，通道日志便于排查。
     */
    private fun setOverlayVisible(visible: Boolean) {
        // 内存状态（本进程）+ 跨进程标记文件（业务进程每秒 stat 它）
        BoxSwitchOverlay.setBusinessHidden(!visible)
        BoxSwitchOverlay.writeHiddenFlag(this, !visible)
        Log.i(TAG, "overlay visible=$visible")
    }

    /** 业务侧请求刷新权限快照：宿主现查自己权限后推回去（见 FrameBusinessLink.pushHostPerms） */
    private fun pushPerms() {
        runCatching { FrameBusinessLink.pushHostPerms(this) }
            .onFailure { Log.w(TAG, "push perms failed", it) }
    }

    /**
     * 消费端 → BlackBox后台：拉起宿主 [MainActivity] 并打上 [MainActivity.EXTRA_SHOW_BOX]。
     *
     * 为什么不能由消费端自己 startActivity：它在沙箱里跑，Activity 启动会被 BlackBox 代理，
     * 目标是宿主真实组件时行为不受控；这条 startService 通道已在 install/dismiss 上跑通。
     * MainActivity 是 singleTask：已在栈里就走 onNewIntent（那边同样认 EXTRA_SHOW_BOX）。
     */
    private fun openBoxUi() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SHOW_BOX, true)
            )
            Log.i(TAG, "open BlackBox box ui")
        }.onFailure { Log.w(TAG, "open box ui failed", it) }
    }

    private fun installAsync(seed: String) {
        Thread({
            val pending = resolvePending(seed)
            if (pending == null) {
                FrameBusinessLink.pushFailed("没有待安装的更新，请重新检查")
                return@Thread
            }
            // 与「清单 mode=force 自动装」**共用** HotUpdateManager.installWithHostFeedback：
            // 推 installing → 下载（进度回推）→ 装 → 推 done/failed → 复活（装完回到业务界面）。
            // 两条入口只走这一个实现，不各写一份。
            val result = HotUpdateManager.installWithHostFeedback(this, pending)
            if (result is HotUpdateManager.UpdateResult.Failed) {
                Log.w(TAG, "install failed: ${result.message}")
            }
        }, "frame-update-install").start()
    }

    /** 用户放弃：宿主清掉 pending，业务侧状态也一起清（侧栏入口消失） */
    private fun dismiss() {
        HotUpdateManager.dismissPending()
        FrameBusinessLink.clear()
    }

    /**
     * 确认这次要装的是哪一版：
     *  · 宿主进程没被回收过 → 直接用内存里的 pending（校验 seed，防止业务点了旧提示）；
     *  · 被回收过（pending 没了）→ 按当前清单重新 check 一次，seed 对得上才继续。
     */
    private fun resolvePending(seed: String): HotUpdateManager.PendingUpdate? {
        val cached = HotUpdateManager.pendingUpdate
        if (cached != null && (seed.isEmpty() || cached.seed == seed)) return cached
        val result = HotUpdateManager.check(this, HotUpdateManager.manifestUrl(this))
        val fresh = (result as? HotUpdateManager.CheckResult.Available)?.pending ?: return null
        if (seed.isNotEmpty() && fresh.seed != seed) return null
        HotUpdateManager.remember(fresh)
        return fresh
    }

    companion object {
        private const val TAG = "FrameUpdateHost"

        const val EXTRA_CMD = "cmd"
        const val EXTRA_SEED = "seed"

        const val CMD_INSTALL = "install"
        const val CMD_DISMISS = "dismiss"

        /** 消费端请求进入 BlackBox后台（与 SourcePlus 的 FrameUpdateClient.requestOpenBox 对齐） */
        const val CMD_OPEN_BOX = "open_box"

        /** 消费端控制 ⇄ 悬浮按钮显隐（extra: visible） */
        const val CMD_OVERLAY = "overlay"
        const val EXTRA_VISIBLE = "visible"

        /** 消费端请求刷新宿主权限快照（宿主回推 FrameBusinessLink.pushHostPerms） */
        const val CMD_QUERY_PERMS = "query_perms"
    }
}
