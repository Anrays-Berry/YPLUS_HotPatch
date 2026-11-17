package com.anrays.yplus

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「发现新版本」右上角提示条（系统 overlay）。
 *
 * 形态对齐源PLUS 的 notice（尺寸比它略小一点）：胶囊圆角、半透底 + 主色描边、左侧圆形云下载
 * 图标、标题「发现新版本」+ 副文 vX.Y.Z、右侧关闭；**不自动消失**，用户自己点 ✕ 或点进更新窗口。
 *
 * 🔴 只在本 App 前台显示（用户 2026-10-27 定稿）：
 *    和 [BoxSwitchOverlay] 一样，**不能用 Activity 生命周期判断前后台** —— 沙箱里虚拟 App 有自己的
 *    Activity 栈，`ProxyActivity` 会反复 pause/resume（实测 0.2 秒内显示/移除来回切）。
 *    这里同样每秒问系统一次：本进程 importance 是不是 FOREGROUND；后台直接移除视图，
 *    pending 留着，回到前台自动再显示。
 *
 * 为什么必须是 overlay：默认入口是**直接进沙箱里的源PLUS**，宿主自己的 Activity 一进去就
 * `finish()`（见 [MainActivity]），没有视图能承载这条提示；沙箱页面是 `ProxyActivity`，
 * 也没法往它的 decorView 里加 View。
 *
 * 没有悬浮窗权限时 [show] 返回 false，调用方直接打开更新页面。
 */
object UpdateNoticeOverlay {

    private const val TAG = "UpdateNotice"

    /** 前后台轮询周期（与 BoxSwitchOverlay 一致，系统查询本身很轻） */
    private const val POLL_MS = 500L

    /**
     * 显示满这么久**临时收起**（用户 2026-10-30：显示 5 秒后消失）。
     * 注意是"收起"不是"关闭"：pending 还留着，重新进入 App（从后台回到前台）会再显示一轮；
     * 重启当然也会显示（新进程 shownThisForeground=false）。
     */
    private const val AUTO_HIDE_MS = 5000L

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 上一轮是否在前台（用于识别"重新进入 App"这个边沿） */
    private var wasForeground = false

    /** 本次前台是否已经显示过一轮（5 秒收起后不再自动冒出来，直到再次进入前台） */
    private var shownThisForeground = false

    /** 5 秒到点自动收起 */
    private val autoHide = Runnable {
        Log.i(TAG, "显示已满 ${AUTO_HIDE_MS}ms → 临时收起（重进 App 再显示）")
        removeView()
    }

    /** 待提示的更新；用户关掉/装完才清空 */
    private var pending: HotUpdateManager.PendingUpdate? = null
    private var polling = false

    /**
     * @return true 表示提示条由这里接管（可能暂时因后台不显示，回前台会显示）；
     *         false 表示没有悬浮窗权限，调用方应直接打开更新页面。
     */
    fun show(context: Context, pendingUpdate: HotUpdateManager.PendingUpdate): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "无悬浮窗权限 → 交给调用方直接进更新页面")
            return false
        }
        appContext = app
        pending = pendingUpdate
        startPolling()
        // 🔴 这里**不要**直接 render()：显示/收起的状态（wasForeground、shownThisForeground）
        // 只由 ticker 维护，直接渲染会让 shownThisForeground 还是 false → 5 秒收起后 ticker
        // 又把它画出来，变成无限循环。ticker 500ms 内就会处理这一轮。
        Log.i(
            TAG,
            if (HotUpdateManager.isForeground(app)) "在前台 → 本轮显示 ${AUTO_HIDE_MS}ms"
            else "在后台 → 回前台再显示"
        )
        return true
    }

    /** 用户关掉 / 点进更新窗口 / 装完：彻底收起，不再自动出现 */
    fun dismiss() {
        pending = null
        removeView()
        stopPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(ticker)
    }

    private fun stopPolling() {
        if (!polling) return
        polling = false
        handler.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val context = appContext
            if (context == null || pending == null) {
                removeView()
                stopPolling()
                return
            }
            // 只在"App 真的在眼前"时显示：顶层是宿主/业务包，且没在后台管理界面里
            val visible = HotUpdateManager.isForeground(context) && !BoxUiState.boxVisible
            if (!visible) {
                if (view != null) {
                    Log.i(TAG, if (BoxUiState.boxVisible) "后台界面出现 → 收起提示条" else "退到后台 → 移除提示条")
                    removeView()
                }
                // 离开前台：下次重新进入 App 时再给一轮 5 秒
                wasForeground = false
                shownThisForeground = false
            } else {
                if (!wasForeground) {
                    // 进入前台的边沿（首次启动 / 从后台回来 / 重启后）：允许再显示一轮
                    wasForeground = true
                    shownThisForeground = false
                }
                // 本次前台还没显示过 → 显示；5 秒收起后 shownThisForeground 已是 true，
                // 不会再自动冒出来（这正是"临时消失，重进才再显示"）
                if (!shownThisForeground && view == null) {
                    render()
                    shownThisForeground = true
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun render() {
        val app = appContext ?: return
        val pendingUpdate = pending ?: return
        if (view != null) return
        if (!Settings.canDrawOverlays(app)) return
        // 后台管理界面正开着 → 暂时不叠这条提示条：页面顶部已经有「立即更新」高亮按钮，
        // 提示条正好压在它上面（2026-10-30 实测截图）。用户离开后台界面后 ticker 会自动补显示。
        if (BoxUiState.boxVisible) {
            Log.i(TAG, "后台界面可见 → 暂不显示提示条（离开后自动补）")
            return
        }

        val metrics = app.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val accent = app.getColor(R.color.accent)
        val textPrimary = app.getColor(R.color.text_primary)
        val textSecondary = app.getColor(R.color.text_secondary)

        // 全部尺寸按屏幕宽度取比例；2026-10-30 再整体 ×0.75（用户："更新 notice 大小小一点，*0.75"）
        val scale = 0.75f
        val barWidth = (w * 0.36f * scale).toInt()
        val barMinHeight = (w * 0.047f * scale).toInt()
        val iconSize = (w * 0.022f * scale).toInt()
        val padH = (w * 0.017f * scale).toInt()
        val padV = (w * 0.009f * scale).toInt()
        val gap = (w * 0.010f * scale).toInt()
        val titlePx = w * 0.0145f * scale
        val messagePx = w * 0.011f * scale
        val iconPx = w * 0.0135f * scale
        val closePx = w * 0.0145f * scale
        val radius = barMinHeight / 2f

        // 胶囊底：半透卡片色 + 主色 28% 描边
        val capsule = object : FrameLayout(app) {}
        capsule.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.argb(235, 0x17, 0x1C, 0x23))
            setStroke((w * 0.0012f).toInt().coerceAtLeast(1), withAlpha(accent, 0.28f))
        }
        capsule.clipToOutline = true
        capsule.isClickable = true
        capsule.minimumHeight = barMinHeight

        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH / 2, padV)
        }

        // 圆形图标底（主色 13%）+ 云下载箭头
        val icon = TextView(app).apply {
            text = "↓"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, iconPx)
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(accent, 0.13f))
            }
        }
        row.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply { rightMargin = gap })

        val texts = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(app).apply {
            text = app.getString(R.string.update_notice_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx)
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val message = TextView(app).apply {
            text = pendingUpdate.version.takeIf { it.isNotEmpty() }?.let { "v$it" }
                ?: app.getString(R.string.update_notice_new)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, messagePx)
            setTextColor(textSecondary)
        }
        texts.addView(title)
        texts.addView(message)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(app).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, closePx)
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
            setPadding(gap, padV, gap / 2, padV)
            contentDescription = app.getString(R.string.action_update_later)
            setOnClickListener { dismiss() }
        }
        row.addView(close)

        capsule.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        capsule.setOnClickListener {
            dismiss()
            openUpdateWindow(app)
        }

        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            barWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 右上角；END 对齐下 x 就是距右边的留白
            gravity = Gravity.TOP or Gravity.END
            x = (w * 0.014f).toInt()
            y = (w * 0.014f).toInt()
        }

        runCatching { manager.addView(capsule, lp) }
            .onFailure { Log.w(TAG, "addView failed", it); return }
        windowManager = manager
        view = capsule
        Log.i(TAG, "提示条已显示（v${pendingUpdate.version}，${AUTO_HIDE_MS}ms 后临时收起）")

        // 入场：从右侧滑进来
        capsule.alpha = 0f
        capsule.translationX = w * 0.015f
        capsule.animate().alpha(1f).translationX(0f).setDuration(220L).start()

        // 5 秒后临时收起（pending 保留，重新进入 App 会再显示一轮）
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun removeView() {
        handler.removeCallbacks(autoHide)
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeView(current) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        windowManager = null
    }

    private fun openUpdateWindow(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, UpdateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open update window failed", it) }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((255 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
