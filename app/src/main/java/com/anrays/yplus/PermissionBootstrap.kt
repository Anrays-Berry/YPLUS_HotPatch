package com.anrays.yplus

import android.content.Context
import android.os.Process
import android.util.Log

/**
 * 宿主启动时补齐沙箱业务运行所需的「系统级能力」。
 *
 * 沙箱里的虚拟 app 共享宿主 UID，系统在真正执行（窗口叠加、麦克风采集）时按
 * **宿主 UID** 校验权限，BlackBox 只骗过了 checkPermission，必须由宿主真正持有：
 *  - SYSTEM_ALERT_WINDOW：悬浮窗（只能 appops，不能用 pm grant）
 *  - RECORD_AUDIO：对讲录音（运行时权限 + appop）
 *  - READ_EXTERNAL_STORAGE：从 /sdcard 选 APK 导入（旧 targetSdk 兼容）
 *
 * 设备有 root 时通过 su 静默授予；无 root 时静默失败，需在系统设置里手动授权。
 */
object PermissionBootstrap {

    private const val TAG = "PermBootstrap"

    fun ensure(context: Context) {
        // root 默认关闭：绝不主动执行 su 静默授权，避免弹「超级用户请求」。
        // 无 root 的车机本就靠用户在系统设置里手动授予悬浮窗/录音等权限。
        if (!RootPrefs.enabled(context)) {
            Log.i(TAG, "root 未开启 → 跳过 su 静默授权（不主动申请超级用户）")
            return
        }
        val pkg = context.packageName
        val uid = Process.myUid()
        Thread({
            // 悬浮窗：包名级 + UID 级都设，兼容系统按 UID / 包名两种校验路径
            exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            exec("appops set --uid $uid SYSTEM_ALERT_WINDOW allow")
            // 录音：运行时权限 + appop
            exec("pm grant $pkg android.permission.RECORD_AUDIO")
            exec("appops set --uid $uid RECORD_AUDIO allow")
            // 导入 APK 读 /sdcard
            exec("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
            // 蓝牙扫描/连接：沙箱内业务（业务应用的设备管理）按**宿主 UID** 校验，
            // 宿主没授这些权限，沙箱里声明了也扫不到设备。
            // 宿主 targetSdk=28 → Android 12+ 走旧权限模型，关键是定位权限。
            exec("pm grant $pkg android.permission.ACCESS_FINE_LOCATION")
            exec("pm grant $pkg android.permission.BLUETOOTH_SCAN")
            exec("pm grant $pkg android.permission.BLUETOOTH_CONNECT")
            exec("appops set $pkg BLUETOOTH_SCAN allow")
            exec("appops set $pkg BLUETOOTH_CONNECT allow")
            // 「守护进程」写回被系统摘掉的无障碍服务需要它（机制参考 MacroDroid；
            // 拿不到时 App 层写回会失败、降级走 root `settings put secure`，所以这里 || true 不阻断）
            exec("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS || true")
            Log.i(TAG, "permission bootstrap done for $pkg (uid=$uid)")
        }, "perm-bootstrap").start()
    }

    private fun exec(cmd: String) {
        try {
            val code = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
            Log.d(TAG, "su -c \"$cmd\" -> $code")
        } catch (t: Throwable) {
            Log.w(TAG, "su failed: $cmd", t)
        }
    }
}
