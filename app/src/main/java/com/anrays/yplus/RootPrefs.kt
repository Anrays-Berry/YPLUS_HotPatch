package com.anrays.yplus

import android.content.Context

/**
 * root 能力总开关。
 *
 * 默认关闭：宿主**绝不主动**执行 `su`（不探测、不静默授权、不部署 root 守护脚本），
 * 因此不会弹出「超级用户请求」。车机本就多数无 root，即使有 root，也只在用户显式
 * 打开后才走 su 路径（守护脚本 / 静默授权）。
 *
 * 开启方式（按需，二选一）：
 *  · adb shell： `am broadcast -a com.anrays.yplus.action.SET_ROOT --es on true`
 *    （如日后需要可接一个 BroadcastReceiver；当前仅提供 API，默认关即可满足「不主动申请」）
 *  · 或在设置页里加一个开关调用 [setEnabled]。
 */
object RootPrefs {

    private const val PREFS = "yplus_box_root"
    private const val KEY_ENABLED = "enabled"

    /** 是否允许宿主走 su（默认 false） */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
