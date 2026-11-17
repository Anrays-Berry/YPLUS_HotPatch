package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * 宿主通知监听组件的**身份替换**工具。
 *
 * 背景（用户 2026-10-29）：系统的通知使用权是**按组件名**授权的，不是按 App/UID 授权的。
 * 沙箱里的业务 app 是"虚拟安装"的，系统看不见它的 NotificationListenerService：
 *   · 系统设置里只能看到宿主的开关；
 *   · 业务直接调 `getActiveSessions(自己的listener)` / `registerListener(...)` 会被系统拒绝
 *     （真实授权列表里没有那个虚拟组件）。
 *
 * 所以 BlackBox 的代理层要在这两个调用把**组件名换成宿主已授权的那个**，
 * 于是"宿主开了通知使用权"就等于"盒子里的 app 有了"——业务侧代码完全无感，
 * 与独立安装时走同一条路径（歌词 / 歌名 / 进度 / 播放状态都能拿到）。
 */
public class HostNotifyCompat {

    /** 宿主里真实声明、且能在系统设置里被授权的监听组件 */
    private static final String HOST_LISTENER_CLASS = "com.anrays.yplus.HostNotificationListener";

    /** 宿主已授权的监听组件（用于替换调用里的虚拟组件） */
    public static ComponentName listenerComponent() {
        return new ComponentName(BlackBoxCore.getHostPkg(), HOST_LISTENER_CLASS);
    }

    /** 宿主包名 */
    public static String hostPkg() {
        return BlackBoxCore.getHostPkg();
    }
}
