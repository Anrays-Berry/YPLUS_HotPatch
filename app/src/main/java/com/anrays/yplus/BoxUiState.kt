package com.anrays.yplus

import java.lang.ref.WeakReference

/**
 * 「BlackBox后台界面是否正在前台」的全局标记。
 *
 * 用途只有一个：[BoxSwitchService] 的悬浮按钮据此决定点一下该切到哪边：
 *   · 管理界面在前台 → 切回业务应用；
 *   · 业务应用在前台（管理界面已退场）→ 打开管理界面。
 *
 * 由 [MainActivity] 的 onResume/onPause 维护；弱引用避免持有 Activity 泄漏。
 */
object BoxUiState {

    /** 管理界面（应用列表 / 安装 APK）是否正在前台 */
    @Volatile
    var boxVisible: Boolean = false

    /** 当前管理界面实例（悬浮按钮切回业务应用时用它 finish） */
    private var ref: WeakReference<MainActivity>? = null

    fun attach(activity: MainActivity) {
        ref = WeakReference(activity)
    }

    fun detach() {
        ref = null
    }

    fun activity(): MainActivity? = ref?.get()
}
