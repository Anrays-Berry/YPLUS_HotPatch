package com.anrays.yplus

import android.content.Context
import android.content.res.Configuration
import android.util.Log

/**
 * 原生 UI 密度缩放（参考源PLUS：默认 1.5×，范围 0.8~1.5）。
 *
 * 车机常见「系统 density=1（mdpi）但分辨率很高（如 2560×1440）」：按 dp/sp 设计的原生页面
 * 会显得又小又糙。这里把宿主自己的密度整体放大，让 1dp 占更多像素 —— 与源PLUS
 * 用 `dpi.js` 放大网页根字号是同一个意图（源PLUS 默认 120%）。
 *
 * ⚠️ 不能用「直接改 `DisplayMetrics.density`」的方式：Android 在配置更新
 * （[android.content.res.Resources] 的 `updateConfiguration`）时会按
 * `Configuration.densityDpi` 重新算出 density，把手动改的值覆盖掉，缩放瞬间失效。
 * 必须把缩放写进 Configuration 的 densityDpi，再经 `createConfigurationContext`
 * 生成新 Context —— 这样配置变更后仍由该 densityDpi 推导 density，缩放稳定生效。
 * 缩放相对「系统原始 density」（[Resources.getSystem] 永不被 app 改动）计算，幂等。
 *
 * 作用范围：宿主原生 UI（更新页 / 后台管理界面 / 悬浮按钮 / 提示条）。
 * 沙箱内的业务 app 跑在独立进程，density 各自独立，不会被这里扰动。
 */
object DpiScale {

    /** 默认 150%（框架原生页面整体放大到 1.5×） */
    const val DEFAULT = 1.5f
    const val MIN = 0.8f
    const val MAX = 1.5f

    private const val PREFS = "frame_ui"
    private const val KEY = "dpi_scale"
    private const val TAG = "DpiScale"

    fun get(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY, DEFAULT)
            .coerceIn(MIN, MAX)

    /**
     * 返回带缩放 densityDpi 的新 Context（用 [Context.createConfigurationContext]，
     * 缩放可抗配置变更稳定生效）。在 [android.content.ContextWrapper.attachBaseContext]
     * 里调用并把返回值传给 `super.attachBaseContext(...)`.
     */
    fun wrap(base: Context): Context {
        val scale = get(base)
        val config = Configuration(base.resources.configuration)
        val target = (config.densityDpi * scale).toInt().coerceAtLeast(1)
        config.densityDpi = target
        Log.d(TAG, "wrap scale=$scale -> densityDpi=$target (orig=${base.resources.configuration.densityDpi})")
        return base.createConfigurationContext(config)
    }
}
