package com.anrays.yplus

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * 宿主的**守护进程**（对齐源PLUS「设置 → 应用服务 → 守护进程」那一项，即 `accKeepAlive`）。
 *
 * 源PLUS 那边守护的是它自己的边缘手势服务，宿主这边守护的是 [HostAccessibilityService] ——
 * 沙箱里的业务组件系统根本不认，所有 `performGlobalAction`（回桌面 / 返回 / 最近任务）都得靠
 * 宿主这个真实组件代执行，它一旦被摘掉，业务侧「无障碍=不可用」。
 *
 * 机制（与源PLUS 的 [AccessibilityKeepAlive] 同构，参考 MacroDroid 的
 * KeepAccessibilityServicesRunning）：某些 ROM 会在重启 / 系统回收 / 省电策略时把某个
 * 无障碍服务从 `Settings.Secure.enabled_accessibility_services` 里移除，发现不在列表里就用 `:`
 * 拼回去写回，系统随即重新绑定服务。
 *
 * 三层保障（按可靠性排序）：
 *   ① root 守护脚本 [HostKeepAlive]：跑在宿主进程之外，宿主被 force-stop 也照样捞回（最强，杀后台靠它）；
 *   ② 本类的 ContentObserver：宿主进程存活时秒级恢复（重启/被摘除靠它）；
 *   ③ 进程内 60s 心跳兜底补一次（[CarBoxApp] 的 heartbeat）。
 *
 * ⚠️ **固定开启**：没有开关、没有 `*_disabled` 标记文件 —— 宿主是业务的载体，
 *    它的无障碍不是"用户可选功能"，掉了业务就瘫，所以永远补回。
 */
object HostAccGuard {

    private const val TAG = "HostAccGuard"

    /** 宿主无障碍服务的组件 id（= `enabled_accessibility_services` 里的写法，包名/类全名） */
    const val SERVICE_ID = "com.anrays.yplus/com.anrays.yplus.HostAccessibilityService"

    /** 服务在列表里的"短名"匹配串（类名部分）：有些 ROM 会写成 `包名/.HostAccessibilityService` */
    private val SERVICE_SHORT = SERVICE_ID.substringAfterLast('/')

    private const val SEP = ":"

    @Volatile
    private var observer: ContentObserver? = null

    /** 是否已注册监听（幂等判定） */
    fun isInstalled(): Boolean = observer != null

    /** 读取系统当前启用的无障碍服务列表（多个以 `:` 分隔） */
    fun currentList(cr: ContentResolver): String = try {
        Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    } catch (t: Throwable) {
        Log.w(TAG, "读取 enabled_accessibility_services 失败", t)
        ""
    }

    /** 宿主无障碍服务当前是否在启用列表里 */
    fun isServiceEnabled(cr: ContentResolver): Boolean {
        val list = currentList(cr)
        if (list.isEmpty()) return false
        return list.split(SEP).any {
            val s = it.trim()
            s == SERVICE_ID || s.endsWith("/$SERVICE_SHORT")
        }
    }

    /**
     * 核心：宿主无障碍被摘除就补回。
     *
     * 顺序（与源PLUS 一致：先 App 层写、失败再 root）：
     *   1. `Settings.Secure.putString` —— 需要 WRITE_SECURE_SETTINGS（adb / root `pm grant` 后有）；
     *   2. 抛 SecurityException（没授到权）→ root 执行 `settings put secure`。
     *
     * @return true = 列表里已有 / 已写回
     */
    fun ensure(context: Context): Boolean {
        val app = context.applicationContext
        val cr = app.contentResolver
        if (isServiceEnabled(cr)) {
            // 列表里有但没绑上：进程刚起、或系统还没重绑，不做动作（写同值不触发重新绑定，只会白改设置）
            if (!HostAccessibilityService.isConnected()) {
                Log.d(TAG, "无障碍在列表内但未连接（等待系统绑定）")
            }
            return true
        }

        val old = currentList(cr)
        val newList = if (old.isEmpty() || old == "null") SERVICE_ID else "$old$SEP$SERVICE_ID"
        Log.i(TAG, "宿主无障碍被摘除，尝试补回：$newList")

        // ① App 层直接写（需要 WRITE_SECURE_SETTINGS）
        val ok = try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newList)
            true
        } catch (se: SecurityException) {
            false
        } catch (t: Throwable) {
            Log.w(TAG, "App 层写回失败", t)
            false
        }
        if (ok) {
            ensureMasterSwitch(cr)
            Log.i(TAG, "已补回宿主无障碍（App 层写）")
            return true
        }

        // ② 兜底：root 直接改系统设置（HostKeepAlive.shell 依次尝试 su → sh）
        //    root 未开启时**不主动跑 su**（宿主铁律：绝不自己弹「超级用户请求」）。
        if (!RootPrefs.enabled(app)) {
            Log.i(TAG, "无 WRITE_SECURE_SETTINGS 且 root 未开启 → 跳过 su 兜底（需 adb 授权或开启 root）")
            return false
        }
        val out = HostKeepAlive.shell(
            "settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} \"$newList\"; " +
                "settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1"
        )
        val done = out != null
        Log.i(TAG, if (done) "已补回宿主无障碍（root 兜底）" else "补回失败：既无 WRITE_SECURE_SETTINGS 也无 root")
        return done
    }

    /** 无障碍总开关 `accessibility_enabled` 被关时打开它（写不动不致命） */
    private fun ensureMasterSwitch(cr: ContentResolver) {
        try {
            val on = Settings.Secure.getString(cr, Settings.Secure.ACCESSIBILITY_ENABLED)
            if (on != "1") Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        } catch (_: Throwable) {
        }
    }

    /**
     * 注册 ContentObserver：系统一改动 `enabled_accessibility_services` 就补回。
     * 幂等：重复调用不会重复注册。装上先补一次（重启后服务可能已经是掉的）。
     */
    fun install(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val obs = object : ContentObserver(handler) {
            override fun deliverSelfNotifications(): Boolean = true
            override fun onChange(selfChange: Boolean) {
                // 延迟 1s 避开系统改写那一瞬间，防止与系统互相覆盖
                handler.postDelayed({ ensure(app) }, 1000)
            }
        }
        try {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                obs
            )
        } catch (t: Throwable) {
            Log.w(TAG, "注册监听失败", t)
            return
        }
        observer = obs
        ensure(app)
        Log.i(TAG, "宿主无障碍守护已注册（固定开启）service=$SERVICE_ID")
    }
}
