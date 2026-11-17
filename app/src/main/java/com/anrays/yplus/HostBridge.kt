package com.anrays.yplus

import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore

/**
 * 宿主 ↔ 沙箱业务的桥（POC）。
 *
 * 当前实现：
 *  - 系统重绑宿主通知监听时，拉起沙箱里的业务 APK（验证「保活复活」命脉）；
 *  - 通知事件先记录（下一步用 AIDL / ContentProvider 真正转发给沙箱业务）。
 */
object HostBridge {

    private const val TAG = "HostBridge"

    const val USER_ID = 0

    /**
     * 沙箱里**唯一的业务包**（用户 2026-10-28："业务源不再认包名，因为只有一个了"）。
     *
     * 沙箱只允许装一个 APK，所以直接取那唯一的包 —— 业务 APK 改包名时宿主不用同步改。
     * null = 沙箱里还没装任何包。
     */
    fun businessPackage(): String? = BoxRepository.singleInstalledPackage()

    /** 业务的保活前台服务（见 SourcePlus 端 core/KeepAliveService.kt，静默恢复的落脚点）。
     *  类名在业务包名下，故随包名动态拼 —— 不能再写死 `com.anrays.sourceplus.…`。 */
    private fun keepAliveService(pkg: String) = "$pkg.core.KeepAliveService"

    private const val PREFS = "host_bridge"
    private const val KEY_LAST_REBIND = "last_rebind"
    private const val REBIND_THROTTLE_MS = 5_000L

    /** 系统绑定/重绑宿主的通知监听时调用 */
    fun onSystemRebound(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_REBIND, 0L) < REBIND_THROTTLE_MS) {
            Log.d(TAG, "rebound throttled")
            return
        }
        prefs.edit().putLong(KEY_LAST_REBIND, now).apply()
        // 🔴 静默：重绑**不是用户操作**，不能因为系统重绑就把沙箱界面拉到前台
        //    （实测这条路径正是 force-stop 后 ProxyActivity$P0 被 START 到前台的原因）。
        //    进程/服务照常恢复，界面留给用户自己点图标打开。
        reviveBusinessQuietly()
    }

    /**
     * root 守护脚本拉活（[ReviveReceiver]）触发的恢复：**静默**，不启动任何 Activity。
     *
     * 🔴 用户 2026-10-26 明确："拉活还是会把 app 打开到前台" —— 所以这里不走启动 Activity 的
     *    [launchBusiness]，改成「只拉沙箱进程 + 启动业务的前台保活服务」：
     *      · `initProcess` 只创建 `宿主:pN` 桩进程并初始化 ActivityThread，不碰 ActivityStack；
     *      · `startService` 让业务自己的保活服务（SourcePlus 端 KeepAliveService）跑起来，
     *        由它按开关恢复悬浮窗/心跳；服务内部有 `shouldKeepAliveServiceRun` 门控，尊重用户设置。
     * 不复用 [onSystemRebound] 的另一个原因：那条路径有持久化节流，force-stop 前刚重绑过一次
     * 就会被挡掉 —— 而 force-stop 恰恰是最需要恢复的场景。
     */
    fun onReviveBroadcast() {
        reviveBusinessQuietly()
    }

    /** 静默复活沙箱业务：拉进程 + 起服务，全程无 Activity（失败按 800ms 递增重试 3 次） */
    private fun reviveBusinessQuietly() {
        Thread({
            val manager = BlackBoxCore.getBActivityManager()
            for (attempt in 1..3) {
                try {
                    Thread.sleep(800L * attempt)
                    val pkg = businessPackage()
                    if (pkg == null) {
                        Log.w(TAG, "business not installed in sandbox (empty box)")
                        return@Thread
                    }
                    val config = manager.initProcess(pkg, pkg, USER_ID)
                    if (config == null) {
                        Log.w(TAG, "quiet revive: initProcess failed, attempt=$attempt")
                        continue
                    }
                    manager.startService(
                        Intent().setClassName(pkg, keepAliveService(pkg)),
                        null,
                        true,
                        USER_ID
                    )
                    Log.i(TAG, "quiet revive ok (process + keepalive service), attempt=$attempt")
                    return@Thread
                } catch (t: Throwable) {
                    Log.e(TAG, "quiet revive attempt $attempt failed", t)
                }
            }
            Log.e(TAG, "quiet revive failed after 3 attempts")
        }, "host-quiet-revive").start()
    }

    /**
     * 通知到达：**只记日志，不做转发**。
     *
     * 沙箱业务拿通知的正道是 BlackBox 的 Binder 代理（把业务的调用换成宿主身份去问系统），
     * 而不是宿主收完再用 JSON 转一遍 —— 后者会让业务侧多出一条只有沙箱才有的代码路径，
     * 与独立运行时的行为不一致。
     */
    fun onNotification(sbn: StatusBarNotification) {
        Log.d(TAG, "notification pkg=${sbn.packageName} id=${sbn.id}")
    }

    /**
     * 通知被移除（播放器停止 / 通知被清 / 音乐 App 被杀）。
     *
     * 这条**必须转发**：业务自己的 `MediaNotificationListener` 在盒子里收不到任何回调（系统只绑真实组件），
     * 于是"停止"信号缺失 → 车辆 API 一直显示"播放中"。转发到业务后由现成的
     * `MusicCenter.onMediaRemoved(pkg)` 处理，与独立安装走完全相同的入口。
     */
    fun onNotificationRemoved(pkg: String) {
        Log.d(TAG, "notification removed pkg=$pkg")
        runCatching { FrameBusinessLink.pushMediaRemoved(pkg) }
            .onFailure { Log.w(TAG, "push media_removed failed", it) }
    }

    /** 拉起沙箱里的业务 APK（唯一的那个包），返回是否成功 */
    fun launchBusiness(): Boolean {
        return try {
            val pkg = businessPackage()
            if (pkg == null) {
                Log.w(TAG, "business not installed in sandbox (empty box)")
                return false
            }
            val ok = BlackBoxCore.get().launchApk(pkg, USER_ID)
            Log.i(TAG, "launchApk($pkg) -> $ok")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "launchBusiness failed", t)
            false
        }
    }
}
