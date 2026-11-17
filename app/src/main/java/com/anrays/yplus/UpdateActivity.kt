package com.anrays.yplus

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

/**
 * 更新内容窗口：形态对齐业务应用的更新页 —— 版本行（当前 → 新）+ 更新说明 html（业务应用用
 * `<iframe src>` 加载，这里用 WebView 承载同一份 html）+ 底部动作按钮。
 *
 * 与业务应用唯一的差别：业务应用只有「浏览器打开」（去浏览器下 APK），宿主是**直接装**，
 * 所以这里的按钮是「立即更新」——下载、校验、覆盖安装、装完自己把业务应用拉回来
 * （见 [HotUpdateManager.install]）。
 */
class UpdateActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { DpiScale.wrap(it) } ?: newBase)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var card: LinearLayout
    private lateinit var tvSub: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var notes: WebView
    private lateinit var loadingArea: View
    private lateinit var tvLoading: TextView
    private lateinit var btnNow: MaterialButton
    private lateinit var btnLater: MaterialButton
    private lateinit var tvUpdateTime: TextView

    private var installing = false

    /** 自动安装模式：拿到更新信息后无需点「立即更新」直接装（无 app 恢复场景用） */
    private var autoInstall = false

    /** 恢复模式（无业务 app）：隐藏「稍后 / ✕」，失败也不允许关闭，只能更新 */
    private var recoverMode = false

    /** 当前窗口要装的更新（从通知点进来时可能是进程重启后现场查出来的） */
    private var pending: HotUpdateManager.PendingUpdate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOpen = true
        setContentView(R.layout.activity_update)
        card = findViewById(R.id.updateCard)
        tvSub = findViewById(R.id.tvUpdateSub)
        tvStatus = findViewById(R.id.tvUpdateStatus)
        progress = findViewById(R.id.updateProgress)
        notes = findViewById(R.id.updateNotes)
        loadingArea = findViewById(R.id.updateLoading)
        tvLoading = findViewById(R.id.tvLoadingText)
        btnNow = findViewById(R.id.btnUpdateNow)
        btnLater = findViewById(R.id.btnUpdateLater)
        tvUpdateTime = findViewById(R.id.tvUpdateTime)

        autoInstall = intent.getBooleanExtra(EXTRA_AUTO_INSTALL, false)
        recoverMode = intent.getBooleanExtra(EXTRA_RECOVER_MODE, false)
        if (recoverMode) {
            // 没有业务 app 可回退，只能更新：隐藏「稍后」和「✕」
            findViewById<TextView>(R.id.btnUpdateClose).visibility = View.GONE
            btnLater.visibility = View.GONE
            findViewById<TextView>(R.id.tvUpdateTitle).setText(R.string.update_recover_title)
        } else {
            findViewById<TextView>(R.id.btnUpdateClose).setOnClickListener { dismissToBusiness() }
            btnLater.setOnClickListener { dismissToBusiness() }
        }

        sizeCard()
        prepareWebView()

        // 无业务 app（首次运行 / 丢包）的自动恢复：**不渲染卡片、不等用户点「立即更新」**，
        // 直接进「更新中」全屏状态 —— 循环加载动画 + 底部进度条 + 百分比（用户 2026-10-29）。
        // 清单在后台拉，拉到就装；进度/百分比由 [startInstall] 一路驱动。
        if (recoverMode && autoInstall) {
            enterLoadingState(getString(R.string.update_loading))
            tvStatus.text = getString(R.string.update_recover_checking)
            progress.isIndeterminate = false
            progress.setProgressCompat(0, false)
            startRecover()
            return
        }

        val known = HotUpdateManager.pendingUpdate
        when {
            known != null -> bindPending(known)
            recoverMode -> startRecover()   // 直接连线上清单拉取并安装业务 app
            else -> resolvePendingOnTheSpot()  // 通知横幅进来：进程被回收，现场再查一次
        }
    }

    private fun bindPending(value: HotUpdateManager.PendingUpdate) {
        pending = value
        progress.visibility = View.GONE
        tvStatus.visibility = View.GONE
        renderVersion(value)
        loadNotes(value)
        btnNow.isEnabled = true
        btnNow.setOnClickListener { startInstall(value) }
        Log.i(CarBoxApp.TAG, "更新窗口打开：${value.url} autoInstall=$autoInstall recover=$recoverMode")
        if (autoInstall) {
            // 自动更新恢复：拿到更新信息直接装，不等用户点
            startInstall(value)
        }
    }

    private fun resolvePendingOnTheSpot() {
        btnNow.isEnabled = false
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.update_checking)
        tvSub.text = ""
        worker.execute {
            val result = HotUpdateManager.check(this, HotUpdateManager.manifestUrl(this))
            main.post {
                if (isFinishing || isDestroyed) return@post
                when (result) {
                    is HotUpdateManager.CheckResult.Available -> {
                        HotUpdateManager.remember(result.pending)
                        bindPending(result.pending)
                    }
                    is HotUpdateManager.CheckResult.UpToDate -> {
                        toast(getString(R.string.update_no_update_short))
                        finish()
                    }
                    is HotUpdateManager.CheckResult.Failed -> {
                        toast(getString(R.string.update_fail, result.message))
                        finish()
                    }
                }
            }
        }
    }

    /**
     * 恢复模式（无业务 app）：从线上清单直接取更新信息并自动安装业务 app。
     * 不比对 seed —— 只要能从线上拉到 APK 就装，用于首次运行与「出 bug 丢包」恢复。
     */
    private fun startRecover() {
        btnNow.isEnabled = false
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.update_checking)
        tvSub.text = ""
        worker.execute {
            val p = HotUpdateManager.onlinePending(this)
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (p == null) {
                    showRecoverError(getString(R.string.update_fail, "无法读取线上清单"))
                    return@post
                }
                bindPending(p)
            }
        }
    }

    /**
     * 恢复模式下更新失败：没有业务 app 可回退，不能关闭窗口。
     * 改为显示错误并让「立即更新」按钮变成「重试」。
     */
    private fun showRecoverError(message: String) {
        installing = false
        exitLoadingState()
        progress.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = message
        btnNow.visibility = View.VISIBLE
        btnNow.isEnabled = true
        btnNow.text = getString(R.string.action_retry)
        btnNow.setOnClickListener { startRecover() }
    }

    /**
     * 进入「更新中」状态：隐藏整个卡片窗口，全屏显示循环「加载中」动画 + 文案；
     * 底部进度条（[progress]）与状态文字（[tvStatus]）由 [startInstall] 继续驱动。
     * 窗口不关闭，直到安装成功 [finish] 或失败 [exitLoadingState]。
     */
    private fun enterLoadingState(text: String) {
        card.visibility = View.GONE
        loadingArea.visibility = View.VISIBLE
        tvLoading.text = text
        tvStatus.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        btnNow.visibility = View.GONE
        btnLater.visibility = View.GONE
        findViewById<TextView>(R.id.btnUpdateClose).visibility = View.GONE
    }

    /** 退出「更新中」状态，恢复卡片与操作按钮（用于安装失败需重试）。 */
    private fun exitLoadingState() {
        loadingArea.visibility = View.GONE
        card.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        isOpen = false
        runCatching { notes.destroy() }
        worker.shutdownNow()
    }

    // ---------------- 界面 ----------------

    /**
     * 卡片按屏幕比例取，不用固定 dp：车机分辨率/密度差异很大（实测这台模拟器 density=1，
     * 2560×1440 全是 dp），按 dp 卡上限会让窗口显得只有一小块。
     */
    private fun sizeCard() {
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 0.64f).toInt()
        val height = (metrics.heightPixels * 0.76f).toInt()
        card.layoutParams = (card.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = height
        }
    }

    private fun renderVersion(pending: HotUpdateManager.PendingUpdate) {
        val current = HotUpdateManager.installedBusinessVersionName()
        val target = pending.version.takeIf { it.isNotEmpty() }
        tvSub.text = when {
            current != null && target != null ->
                getString(R.string.update_window_sub, current, target)
            target != null -> getString(R.string.update_window_sub_new, target)
            current != null -> getString(R.string.update_window_sub_current, current)
            else -> getString(R.string.update_notice_new)
        }
        // 推送时间：update.json 没有 pushTime 键时不显示
        if (pending.pushTime.isNotEmpty()) {
            tvUpdateTime.visibility = View.VISIBLE
            tvUpdateTime.text = getString(R.string.update_push_time, pending.pushTime)
        } else {
            tvUpdateTime.visibility = View.GONE
        }
    }

    private fun prepareWebView() {
        notes.setBackgroundColor(Color.TRANSPARENT)
        notes.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            // html 自带 <meta name="viewport" content="width=device-width">，要 device-width 生效
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        notes.isVerticalScrollBarEnabled = false
    }

    private fun loadNotes(pending: HotUpdateManager.PendingUpdate) {
        // 无业务 app（恢复/首次运行）的页面优先用「首次运行 html」键（用户协议等）；
        // 该键缺失则回退到更新说明 html（notesSource），再缺失才用内置兜底文案。
        val source = if (recoverMode) {
            pending.firstRunNotesSource ?: pending.notesSource
        } else {
            pending.notesSource
        }
        worker.execute {
            val url = if (source != null) {
                HotUpdateManager.notesHtmlFile(this, pending.copy(notesSource = source))
            } else {
                null
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (url != null) {
                    notes.loadUrl(url)
                } else {
                    notes.loadDataWithBaseURL(
                        null,
                        fallbackHtml(),
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            }
        }
    }

    private fun fallbackHtml(): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body style="margin:0;font-family:sans-serif;color:#8A94A0;background:transparent">
        <p style="font-size:14px;line-height:1.7">${getString(R.string.update_notes_fallback)}</p>
        </body></html>
    """.trimIndent()

    // ---------------- 立即更新 ----------------

    private fun startInstall(pending: HotUpdateManager.PendingUpdate) {
        if (installing) return
        installing = true
        // 进入「更新中」状态：循环加载动画 + 底部进度条（窗口不关闭，装完/失败再处理）
        enterLoadingState(getString(R.string.update_loading))
        tvStatus.text = getString(R.string.update_downloading_pct, 0)
        progress.isIndeterminate = false
        progress.setProgressCompat(0, false)

        var lastPercent = -1
        var indeterminate = false
        worker.execute {
            val result = HotUpdateManager.install(this, pending) { done, total ->
                // total 未知（chunked 响应）→ 走不确定进度，只切一次
                if (total <= 0) {
                    if (!indeterminate) {
                        indeterminate = true
                        main.post {
                            if (!isFinishing && !isDestroyed) progress.isIndeterminate = true
                        }
                    }
                    return@install
                }
                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                if (percent == lastPercent) return@install
                lastPercent = percent
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    if (progress.isIndeterminate) progress.isIndeterminate = false
                    progress.setProgressCompat(percent, true)
                    // 100% 之后还在装（解包 + 落盘）：文案切成"正在安装"
                    if (percent >= 100) {
                        tvStatus.text = getString(R.string.update_installing_hint)
                        tvLoading.text = getString(R.string.update_installing_short)
                    } else {
                        tvStatus.text = getString(R.string.update_downloading_pct, percent)
                    }
                }
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                val message = when (result) {
                    is HotUpdateManager.UpdateResult.Success -> when {
                        !result.relaunched ->
                            getString(R.string.update_ok_no_relaunch, result.remote)
                        result.inForeground -> getString(R.string.update_ok, result.remote)
                        else -> getString(R.string.update_ok_background, result.remote)
                    }
                    is HotUpdateManager.UpdateResult.Failed ->
                        if (recoverMode) {
                            if (result.rolledBack) {
                                getString(R.string.update_fail_rolled_back, result.message)
                            } else {
                                getString(R.string.update_fail, result.message)
                            }
                        } else if (result.rolledBack) {
                            getString(R.string.update_fail_rolled_back, result.message)
                        } else {
                            getString(R.string.update_fail, result.message)
                        }
                }
                toast(message)
                if (result is HotUpdateManager.UpdateResult.Success) {
                    finish()
                } else if (recoverMode) {
                    // 恢复模式：失败不关窗（没有业务 app 可回），转「重试」
                    showRecoverError(message)
                } else {
                    // 失败不关窗口：还原说明 html 与按钮，用户可直接再点一次
                    installing = false
                    exitLoadingState()
                    btnNow.visibility = View.VISIBLE
                    btnLater.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.btnUpdateClose).visibility = View.VISIBLE
                    btnNow.isEnabled = true
                    btnLater.isEnabled = true
                    progress.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 「稍后」/ ✕：关掉窗口并**回到业务应用**。
     *
     * 不能只 `finish()`：从通知横幅进来时是冷启动，业务应用可能根本没在跑（或者已经被装包杀掉），
     * 一 finish 就只剩个空桌面。这里显式把它拉起来 —— 已在后台就只是切到前台。
     */
    private fun dismissToBusiness() {
        if (installing || recoverMode) return
        worker.execute {
            runCatching {
                BoxRepository.singleInstalledPackage()?.let { BoxRepository.launch(it) }
            }
                .onFailure { Log.w(CarBoxApp.TAG, "dismiss → launch business failed", it) }
        }
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** 自动安装模式：打开即开始安装（无 app 恢复场景） */
        const val EXTRA_AUTO_INSTALL = "extra_auto_install"

        /** 恢复模式（无 app）：隐藏「稍后/✕」，失败不关闭 */
        const val EXTRA_RECOVER_MODE = "extra_recover_mode"

        /** 当前是否已有更新页在前台（防止 checkOnLaunch 重复开页） */
        @Volatile
        var isOpen = false
    }
}
