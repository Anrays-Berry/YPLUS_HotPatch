package com.anrays.yplus

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 宿主侧通知监听服务。
 *
 * 系统只会把 NotificationListenerService 绑定到「真实安装的宿主包」
 * （沙箱里的虚拟 app 组件系统不认识），所以这个能力必须放在宿主。
 *
 * 作用：
 *  1) onListenerConnected：系统绑定/重绑时触发 —— 借机拉起沙箱里的业务 APK（保活复活）；
 *  2) onNotificationPosted：把通知事件交给 HostBridge（后续转发给沙箱业务）；
 *  3) onNotificationRemoved：**转发给沙箱业务**（见 [HostBridge.onNotificationRemoved]）。
 *     业务里的 `MediaNotificationListener.onNotificationRemoved` 在盒子里永远收不到（系统不绑虚拟组件），
 *     导致"音乐 App 被杀/通知被清"这个停止信号到不了业务，车辆 API 会一直显示"播放中"。
 */
class HostNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected: system bound host listener")
        HostBridge.onSystemRebound(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        HostBridge.onNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        HostBridge.onNotificationRemoved(sbn.packageName)
    }

    companion object {
        const val TAG = "HostNotifListener"
    }
}
