package com.anrays.yplus

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * 宿主的**通知使用权状态**查询（就这一件事）。
 *
 * 为什么要由宿主来答：沙箱里的业务读 `Settings.Secure.enabled_notification_listeners`
 * 拿到的是 Bcore `SystemProviderStub` 伪造的值 —— 它会让业务"以为"自己有权限，
 * 但系统侧的真实授权里并没有那个虚拟组件，所以数据一条都进不来。
 * 宿主是真实安装、能真正被系统授权的那一方，这里的结果才可信。
 *
 * 至于通知/媒体会话数据本身：由 BlackBox 的 Binder 代理透传
 * （`IMediaSessionManagerProxy` / `INotificationManagerProxy` 把调用换成宿主身份），
 * **宿主不再做 JSON 转发** —— 业务侧代码与独立运行时完全一致。
 */
object HostNotifyHub {

    private const val TAG = "HostNotifyHub"

    /** 宿主是否已被授予通知使用权（读系统设置里 enabled_notification_listeners） */
    fun granted(context: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull() ?: return false
        if (flat.isBlank()) return false
        val self = context.packageName
        return flat.split(":").any { it.startsWith("$self/") }
    }

    fun state(context: Context): JSONObject = JSONObject()
        .put("granted", granted(context))
}
