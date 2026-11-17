package com.anrays.yplus

import android.app.ActivityManager
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import kotlin.math.abs

/**
 * 「源PLUS ⇄ BlackBox后台」切换按钮。
 *
 * 🔴 显隐规则（用户 2026-10-30 拍板，最终口径）：
 *    · 业务 APK manifest 里有 `frame.consumer = true`（消费端声明）→ **一律不显示**；
 *    · 没有这条声明 → 一律显示（别的沙箱 App 靠它回后台）。
 *
 * 为什么不再用"消费端发命令 → 宿主写标记文件 → 业务进程每秒 stat"：
 *    ① 源PLUS 从进程起来到发命令要几百毫秒，而 launch 动画（系统 starting window，只显示图标）
 *       期间按钮（TYPE_APPLICATION_OVERLAY）**已经盖上去了** —— 用户 2026-10-30 报的正是这个；
 *    ② 判定还得跨进程绕一圈。现在改成读业务 APK 的 meta-data，进进程那一刻就成立。
 * 仍然保留两条与声明无关的物理约束：必须真的在前台（2026-10-27「只在前台显示」）、
 * 前台满 [SHOW_DELAY_MS] 才画（把启动动画那 1~2 秒盖掉）。
 *
 * 实现要点（历史坑，别踩回去）：
 * 1. **不能往 Activity 的 decorView 里加 View**：源PLUS 界面是黑盒的 `ProxyActivity$Px`，
 *    走虚拟 WindowManager，加进去点不动 → 只能用系统 overlay。
 * 2. **不能用 Activity 生命周期判断前后台**：沙箱里虚拟 App 有自己的 Activity 栈，
 *    ProxyActivity 会反复 pause/resume（实测 0.2 秒内显示/移除来回切，最后停在"已移除"）。
 *    改成问系统：自己这个进程的 importance 是不是 FOREGROUND —— 系统级判定，沙箱栈怎么折腾都不影响。
 * 3. 沙箱业务进程的 ClassLoader 是隔离的（业务侧反射 `com.anrays.yplus.BoxSwitchOverlay`
 *    直接 `ClassNotFoundException`），所以每个进程各自独立判定；声明式判定不需要任何跨进程通信。
 */
object BoxSwitchOverlay {

    private const val TAG = "BoxSwitchOverlay"

    /**
     * 前后台轮询周期（500ms）。
     * ⚠️ 别调回 1 秒：前台去抖 [SHOW_DELAY_MS] 是按 tick 采样的，1 秒 tick 会让去抖实际变成"2 秒才显示"。
     */
    private const val POLL_MS = 500L

    /**
     * 前台去抖：进程连续前台满这么久才显示按钮。
     * 作用 = 盖掉沙箱 App 的 launch 动画（图标启动画面）那 1~2 秒，避免按钮浮在启动图上。
     */
    private const val SHOW_DELAY_MS = 1200L

    private const val PREFS = "box_switch_overlay"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    /**
     * 默认位置：左上角。
     * y 取 64 是为了避开状态栏（24px）和后台界面的标题行 —— 实测 36 会正好压在「BlackBox后台」字样上。
     */
    private const val DEFAULT_X_DP = 12
    private const val DEFAULT_Y_DP = 64

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    /** 本进程连续前台 > [SHOW_DELAY_MS] 的起点（0 = 当前不在前台） */
    private var fgSince = 0L

    /** App 启动时调用一次（幂等） */
    fun start(context: Context) {
        if (polling) return
        polling = true
        appContext = context.applicationContext
        handler.post(ticker)
    }

    /**
     * 兼容老业务包发来的 `overlay.setVisible(false)` 命令（宿主 `FrameApiHost.overlayVisible`
     * 与老 `SourcePlus/core/HostBoxOverlay` 都会调）。
     * 🔴 2026-10-30 起显隐只认 manifest 声明（见 [isConsumerBusiness]），这条命令**不再改变任何行为**，
     *    只留一行日志，便于排查"还有老包在发命令"。
     */
    fun setBusinessHidden(hidden: Boolean) {
        Log.i(TAG, "setBusinessHidden($hidden) ignored（显隐已改为 frame.consumer 声明）pid=${Process.myPid()}")
        handler.post(ticker)
    }

    /**
     * 兼容老业务包写"隐藏"标记文件的调用。该文件已不参与判定，这里什么也不做。
     */
    @Suppress("UNUSED_PARAMETER")
    fun writeHiddenFlag(context: Context, hidden: Boolean) {
        Log.i(TAG, "writeHiddenFlag($hidden) ignored（显隐已改为 frame.consumer 声明）")
        handler.post(ticker)
    }

    /**
     * 宿主"打开 app"时调用。原来写 3.5 秒抑制文件防宿主进程闪按钮 ——
     * 现在源PLUS 有 `frame.consumer` 声明（全局一律不显示），其他 App 有前台去抖兜住，不再需要。
     */
    @Suppress("UNUSED_PARAMETER")
    fun markLaunchingBusiness(context: Context) {
        Log.i(TAG, "markLaunchingBusiness: 已由 frame.consumer 声明 + 前台去抖取代")
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val context = appContext
            if (context != null) {
                if (shouldShow(context)) show(context) else hide()
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** 本进程当前是否该显示按钮 */
    private fun shouldShow(context: Context): Boolean {
        // ① 有 `frame.consumer` 声明 = 消费端 → **一律不显示**（用户 2026-10-30 拍板）。
        //    不分进程、不看前台、不等任何命令：宿主主进程 / 辅助进程 / 业务进程全都不画。
        //    这同时根治了"launch 动画（只显示图标的启动画面）上冒出按钮"—— 按钮是
        //    TYPE_APPLICATION_OVERLAY，必然盖在系统 starting window 之上，而 importance 判定
        //    在动画期间就已经是 FOREGROUND（界面一帧没画）。声明式判定在进程起来那一刻就成立。
        if (isConsumerBusiness(context)) return false
        // ② 没有声明 = 一律显示（别的沙箱 App 靠它回后台）。只保留两条与"声明"无关的物理约束：
        //    · 必须真的在前台（否则按钮会飘在桌面/别的应用上，违背 2026-10-27「只在前台显示」）；
        //    · 前台去抖 [SHOW_DELAY_MS]（盖掉启动动画那 1~2 秒，避免同样的"浮在图标上"）。
        if (!isForeground(context)) {
            fgSince = 0L
            return false
        }
        val now = System.currentTimeMillis()
        if (fgSince == 0L) {
            fgSince = now
            return false
        }
        return now - fgSince >= SHOW_DELAY_MS
    }

    /**
     * 当前业务包是否**消费端**（= 不需要宿主悬浮按钮）。
     * 判定统一走 [BusinessManifest]（业务 APK 的 `frame.consumer` 声明，唯一出处，带 TTL 缓存）。
     */
    private fun isConsumerBusiness(context: Context): Boolean = BusinessManifest.isConsumer(context)

    /** 业务包换了/升级了 → 下次判定重新读 manifest（宿主装包后调用） */
    fun invalidateConsumer() {
        BusinessManifest.invalidate()
    }

    // 注：原 `isBusinessProcess()`（用 /proc/self/cmdline 判断"我是业务包进程"）已删除 ——
    // 显隐现在只认 manifest 声明，任何进程读到同一个业务 APK 都会得到同一个结论，不需要再区分进程身份。

    /**
     * 按钮只在本 App 界面**真的在眼前**时显示（用户 2026-10-30："app 不在前台就不要显示悬浮窗"）。
     *
     * 两个条件都要满足：
     *   · [isSelfForeground]：本进程自己承载着界面 —— 否则宿主主进程 + `:p0` + 业务进程会**各画一个球**；
     *   · [HotUpdateManager.isForeground]：顶层包名确实是宿主/业务包 —— 否则沙箱进程退到后台后
     *     仍会被 BlackBox 标成前台，球就飘在桌面/别的应用上了。
     */
    private fun isForeground(context: Context): Boolean =
        isSelfForeground(context) && HotUpdateManager.isForeground(context)

    /** 本进程当前是否前台（有可见 Activity）——只管"我该不该画这一个"，不代表 App 在眼前 */
    private fun isSelfForeground(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val self = am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() } ?: return false
            // 排除 BlackBox 的辅助/私有进程（进程名带 ":"，如 :black / :p0）：它们没有真实界面，
            // 却被系统误判为前台，否则会多冒出一个悬浮按钮。
            if (self.processName.contains(":")) return false
            self.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (_: Exception) {
            false
        }
    }

    private fun show(context: Context) {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "无悬浮窗权限 → 不显示切换按钮（不影响源PLUS）")
            return
        }
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        val size = (40 * density).toInt()

        val button = TextView(context).apply {
            text = "⇄"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1E88E5.toInt())
            }
            alpha = 0.85f
            contentDescription = "拖动移动，点击切换源PLUS与BlackBox后台"
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
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
            // 左上角（用户 2026-10-28）；拖动后存的是相对这个 gravity 的偏移
            gravity = Gravity.TOP or Gravity.START
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            x = prefs.getInt(KEY_X, (DEFAULT_X_DP * density).toInt())
            y = prefs.getInt(KEY_Y, (DEFAULT_Y_DP * density).toInt())
        }

        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - size).coerceAtLeast(0)
        attachDrag(context, button, manager, lp, maxX, maxY)

        runCatching { manager.addView(button, lp) }
            .onFailure { Log.w(TAG, "addView failed", it); return }
        windowManager = manager
        view = button
        Log.i(TAG, "按钮已显示（前台）x=${lp.x} y=${lp.y}")
    }

    /**
     * 拖动 + 点击：位移超过 touchSlop 才算拖动（否则松手就是"切换"）。
     * 拖动过程直接 updateViewLayout；松手时记住位置，下次启动原样恢复。
     */
    private fun attachDrag(
        context: Context,
        button: View,
        manager: WindowManager,
        lp: WindowManager.LayoutParams,
        maxX: Int,
        maxY: Int
    ) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).coerceIn(0, maxX)
                        lp.y = (startY + dy).coerceIn(0, maxY)
                        runCatching { manager.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // 拖动结束：位置落盘（用户 2026-10-28 要求可拖动）
                        runCatching {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                        }
                        Log.i(TAG, "按钮位置已保存 x=${lp.x} y=${lp.y}")
                    } else {
                        toggle(context)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun hide() {
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        view = null
        Log.i(TAG, "按钮已移除（后台）")
    }

    /** 管理界面 → 源PLUS；源PLUS → 管理界面 */
    private fun toggle(context: Context) {
        if (BoxUiState.boxVisible) {
            BoxUiState.boxVisible = false
            BoxUiState.activity()?.let { runCatching { it.finish() } }
            Thread({
                val ok = BoxRepository.singleInstalledPackage()
                    ?.let { BoxRepository.launch(it) } ?: false
                Log.i(TAG, "切换 → 业务 ok=$ok")
            }, "box-switch-to-business").start()
        } else {
            runCatching {
                context.startActivity(
                    android.content.Intent(context, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_SHOW_BOX, true)
                )
                Log.i(TAG, "切换 → BlackBox后台界面")
            }.onFailure { Log.w(TAG, "open box ui failed", it) }
        }
    }
}
