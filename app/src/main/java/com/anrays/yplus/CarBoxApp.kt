package com.anrays.yplus

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File

/**
 * 宿主 Application：必须在 attachBaseContext 阶段完成 BlackBox 的注入，
 * 之后才能创建虚拟环境（doCreate）。
 */
class CarBoxApp : Application() {

    private val clientConfiguration = object : ClientConfiguration() {
        override fun getHostPackageName(): String = packageName

        override fun isHideRoot(): Boolean = false

        override fun isEnableDaemonService(): Boolean = true

        override fun isUseVpnNetwork(): Boolean = false

        override fun isDisableFlagSecure(): Boolean = false

        /** 沙箱内应用发起“安装另一个 APK”时是否由宿主接管，这里暂不接管 */
        override fun requestInstallPackage(file: File?, userId: Int): Boolean = false

        /** 置空：禁止把崩溃日志上传到第三方 Telegram 频道 */
        override fun getLogSenderChatId(): String = ""
    }

    override fun attachBaseContext(base: Context?) {
        // 原生 UI 密度缩放（参考源PLUS 默认 1.5×）：用 createConfigurationContext 改写 densityDpi，
        // 让缩放抗配置变更稳定生效；在 BlackBox 注入之前包装，BlackBox 仍以原始 base 工作，互不干扰。
        super.attachBaseContext(base?.let { DpiScale.wrap(it) } ?: base)
        if (base == null) return

        val core = BlackBoxCore.get()
        runCatching { core.onBeforeMainApplicationAttach(this, base) }
            .onFailure { Log.e(TAG, "onBeforeMainApplicationAttach failed", it) }
        runCatching { core.doAttachBaseContext(base, clientConfiguration) }
            .onFailure { Log.e(TAG, "doAttachBaseContext failed", it) }
        runCatching { core.onAfterMainApplicationAttach(this, base) }
            .onFailure { Log.e(TAG, "onAfterMainApplicationAttach failed", it) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        runCatching { BlackBoxCore.get().doCreate() }
            .onFailure { Log.e(TAG, "BlackBoxCore.doCreate failed", it) }
        // 宿主真实持有悬浮窗/录音等系统能力，沙箱业务才能正常用（有 root 时静默授予）
        runCatching { PermissionBootstrap.ensure(this) }
            .onFailure { Log.e(TAG, "permission bootstrap failed", it) }
        // root 守护脚本自愈（异步）：宿主被 force-stop 后系统不会派发广播、不重启服务，
        // 只有跑在宿主进程之外的 root 脚本能把进程拉回来（见 HostKeepAlive）。
        // 每次进程启动都补一次 —— 脚本若已不在（设备重启/被清理/su 上次未授权）就重新部署。
        runCatching { HostKeepAlive.ensureRunning(this) }
            .onFailure { Log.e(TAG, "host keepalive ensure failed", it) }
        // 沙箱内虚拟 app 与宿主同 UID：必须由宿主持有一个 microphone 前台服务，
        // 后台录音（悬浮窗对讲）才不被系统静音（见 HostMicService）。
        // 此刻是 App 启动的前台时机，起 FGS 一定被允许；复活场景由 root 脚本补起。
        runCatching { HostMicService.start(this) }
            .onFailure { Log.e(TAG, "host mic fgs start failed", it) }
        // 「守护进程」：宿主无障碍保活（固定开启）。开机/重启后系统可能已经把服务摘掉，
        // 装上监听会立刻补一次；进程存活期间由 ContentObserver 秒级捞回（见 HostAccGuard）。
        runCatching { HostAccGuard.install(this) }
            .onFailure { Log.e(TAG, "host acc guard install failed", it) }
        // 右上角「源PLUS ⇄ 宿主」切换按钮：只在本 App 前台时显示（退到后台自动移除）。
        // 显隐靠每秒查询本进程 importance，不用 Activity 生命周期 —— 沙箱里 ProxyActivity
        // 的 pause/resume 不受控，按生命周期会把按钮闪没（见 BoxSwitchOverlay 注释）。
        runCatching { BoxSwitchOverlay.start(this) }
            .onFailure { Log.e(TAG, "switch overlay start failed", it) }

        // 60 秒心跳自愈：对齐源PLUS 的 KeepAliveService 心跳，沙箱业务被系统回收后能自己回来
        heartbeatHandler.postDelayed(heartbeat, HEARTBEAT_MS)
        // ⚠️ 业务 APK 热更**不在这里**检查：进程可能是被开机广播/保活脚本拉起来的，
        //    用户并没有在用 App。检查只由「用户主动打开 App」触发，见 HotUpdateManager.checkOnLaunch。
    }

    /** 60 秒一次静默确认沙箱业务还在（initProcess / startService 幂等，开销可忽略） */
    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching { HostBridge.onReviveBroadcast() }
                .onFailure { Log.w(TAG, "heartbeat revive failed", it) }
            // 进程存活期间的第三层兜底：若宿主无障碍被摘除，这里补回一次（前两层是
            // root 守护脚本 + ContentObserver，见 HostAccGuard）。
            runCatching { HostAccGuard.ensure(this@CarBoxApp) }
                .onFailure { Log.w(TAG, "heartbeat acc guard failed", it) }
            heartbeatHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TAG = "CarBox"

        /** 心跳周期，对齐源PLUS 的 KeepAliveService（60 秒一次恢复） */
        private const val HEARTBEAT_MS = 60_000L

        lateinit var instance: CarBoxApp
            private set
    }
}
