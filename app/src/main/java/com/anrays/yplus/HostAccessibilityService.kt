package com.anrays.yplus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 宿主的**无障碍**服务（对应沙箱业务用不了的那一套系统能力）。
 *
 * 与通知监听同理：系统只会绑定真实安装包的 AccessibilityService，沙箱里的虚拟组件它不认识，
 * 所以边缘手势那种 `performGlobalAction`（回桌面 / 最近任务 / 返回）必须由宿主代执行 ——
 * 业务经 FrameApi 的 `access.perform` 发命令过来，这里执行后回结果。
 *
 * ⚠️ 本服务**不读窗口内容**（canRetrieveWindowContent=false）、也不在事件流里猜动作，
 *    只在业务显式请求时执行，避免无障碍常驻带来的隐私与性能问题。
 */
class HostAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理事件流：动作全部由业务显式下发（见 FrameApiHost.accessPerform）
    }

    override fun onInterrupt() {
        Log.w(TAG, "accessibility interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HostA11yService"

        @Volatile
        private var instance: HostAccessibilityService? = null

        /** 本进程内服务实例是否存在（只有服务所绑定的那个进程为 true）—— 用于判断能否执行全局动作 */
        fun isConnected(): Boolean = instance != null

        /**
         * 用户在系统「无障碍」里是否开启了本服务。
         *
         * 🔴 不能只用 [isConnected]：宿主是多进程的（主进程 + BlackBox 的 `:black` 辅助进程），
         *    处理消费端 `access.state` / 权限快照请求的往往**不是**服务所绑定的那个进程，
         *    那边的 instance 恒为 null → 表现为"宿主明明开着，消费端却显示关闭"。
         *    `enabled_accessibility_services` 是系统级全局设置，任何进程读到的都是同一个真值。
         */
        fun isEnabled(context: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val expected = ComponentName(context, HostAccessibilityService::class.java).flattenToString()
                flat.split(':').any { it.equals(expected, ignoreCase = true) }
            } catch (t: Throwable) {
                Log.w(TAG, "read enabled accessibility services failed", t)
                false
            }
        }

        /**
         * 执行一个全局动作。
         * @param action [AccessibilityService.GLOBAL_ACTION_*] 的名字，如 HOME / BACK / RECENTS
         * @return null = 成功，否则是失败原因
         */
        fun perform(action: String): String? {
            val svc = instance
                ?: return "宿主无障碍服务未开启（请在系统「无障碍」里启用本应用）"
            val code = when (action.uppercase()) {
                "HOME" -> GLOBAL_ACTION_HOME
                "BACK" -> GLOBAL_ACTION_BACK
                "RECENTS", "RECENT_APPS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                "POWER_DIALOG" -> GLOBAL_ACTION_POWER_DIALOG
                "LOCK_SCREEN" -> GLOBAL_ACTION_LOCK_SCREEN
                "SPLIT_SCREEN" -> if (android.os.Build.VERSION.SDK_INT >= 24) GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN else null
                    ?: return "当前系统不支持该动作"
                else -> return "不支持的动作：$action"
            }
            val ok = runCatching { svc.performGlobalAction(code) }.getOrDefault(false)
            return if (ok) null else "执行失败（系统拒绝或当前不可执行）"
        }

        /**
         * 注入**坐标手势**（v2，`access.gesture`）。单位是屏幕像素，消费端自己乘 density。
         *   · `tap`  = 原地按下（再抬起）；
         *   · `swipe` = 从 (x1,y1) 拖到 (x2,y2)。
         *
         * 🔴 为什么必须宿主做：沙箱里的业务包自带的 AccessibilityService 永远不会被系统绑定
         *    （系统只认真实安装包），所以它连 `dispatchGesture` 的资格都没有。
         * @return null = 已下发，否则是失败原因
         */
        fun gesture(op: String, x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): String? {
            val svc = instance
                ?: return "宿主无障碍服务未开启（请在系统「无障碍」里启用本应用）"
            if (Build.VERSION.SDK_INT < 24) return "当前系统不支持手势注入（需要 Android 7.0+）"
            return try {
                val path = Path().apply {
                    moveTo(x1, y1)
                    if (op != "tap") lineTo(x2, y2)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(1L))
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                if (svc.dispatchGesture(gesture, null, null)) null
                else "手势注入失败（系统拒绝或当前不可注入）"
            } catch (t: Throwable) {
                Log.w(TAG, "gesture failed", t)
                t.message ?: t.javaClass.simpleName
            }
        }
    }
}
