package com.anrays.yplus

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import androidx.core.view.WindowCompat

/**
 * 框架自身的显示样式（参考业务应用 core/DisplayMode.kt 的"标准"做法）。
 *
 * 关键：**[不能在主题层开](android:fitsSystemWindows)** —— 它会落在 DecorView 上，
 * 在视图树最顶层就把窗口 inset 消费成内边距，导致内容永远画不进系统栏后方，
 * 沉浸式与标准肉眼无差别。正确做法是 `WindowCompat.setDecorFitsSystemWindows(window, false)`
 * 关掉 DecorView 的自动 inset，再对**内容视图** `android.R.id.content` 显式开关
 * `fitsSystemWindows`：
 *   - [applyStandard] → true：内容上下避开系统栏，系统栏黑底（"标准"）。
 *
 * 系统栏保持黑底（与主题原始样式一致，且保证时钟/导航键始终可读）。
 */
object FrameDisplayMode {

    /** 标准（参考业务应用 "标准"）：内容视图上下避开系统栏，系统栏黑底。 */
    fun applyStandard(activity: Activity) {
        val window = activity.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        activity.findViewById<ViewGroup>(android.R.id.content)?.fitsSystemWindows = true
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }
}
