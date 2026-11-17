package com.anrays.yplus

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { DpiScale.wrap(it) } ?: newBase)
    }

    private lateinit var rvApps: RecyclerView
    private lateinit var emptyView: View
    private lateinit var tvCount: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnImport: MaterialButton
    private lateinit var btnClone: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnNotif: MaterialButton

    /** 有可用更新时才显示的高亮按钮（2026-10-30：点击直接进更新页） */
    private lateinit var btnUpdateNow: MaterialButton

    private lateinit var adapter: AppsAdapter

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    /** 管理界面是否已经展示过（默认入口是直接进源PLUS，界面可能从未被创建） */
    private var boxUiShown = false

    /** 点了「扫描本地 APK」但还没拿到存储权限：授权回调回来后自动继续扫描 */
    private var pendingScanAfterPerm = false

    private val pickApk =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importApk(uri)
        }

    /** 运行时权限请求（无 root 车机上，PermissionBootstrap 的 su 授权全部失败，只能走这里） */
    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            Log.i(CarBoxApp.TAG, "runtime perms: $result")
            // 「扫描本地 APK」碰到的权限请求：授权回来接着扫，用户不用再点一次
            if (pendingScanAfterPerm) {
                pendingScanAfterPerm = false
                if (hasStorageAccess()) {
                    scanLocalApks()
                } else {
                    toast(getString(R.string.update_scan_no_permission))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 记一份当前 Activity 引用：业务经 FrameApi `perms.request` 让宿主代申请运行时权限时，
        // 只有前台 Activity 才能弹系统授权框（没有就退回"打开应用详情页"）。
        current = this
        requestMissingPermissions()
        // 业务 APK 热更只在这里触发：用户主动点开 App（或从悬浮按钮切回宿主）才算"在用"，
        // 对齐源PLUS —— 它的 updateWatch 也是界面挂载时才 android('checkForUpdate')。
        // 开机广播 / 后台进程启动都不查，避免无人时悄悄覆盖安装把业务进程杀掉。
        runCatching { HotUpdateManager.checkOnLaunch(this) }
            .onFailure { Log.w(CarBoxApp.TAG, "hot update check failed", it) }
        // 默认入口：**直接进沙箱里的源PLUS**，任何情况下都不显示宿主界面
        // （用户 2026-10-27）。只有两种情况才展示管理界面：
        //   · 右上角悬浮按钮切换（EXTRA_SHOW_BOX）
        //   · 文件管理器里「用源PLUS 打开」APK（ACTION_VIEW，要导入）
        if (!shouldShowBoxUi(intent) && enterBusinessDirectly()) return
        showBoxUi()
    }

    /** 是否需要显示 BlackBox后台界面（应用列表 / 安装 APK） */
    private fun shouldShowBoxUi(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_SHOW_BOX, false) == true || intent?.action == Intent.ACTION_VIEW

    /**
     * 直接进入源PLUS：已装业务包就拉起并结束本界面。
     * @return true 表示已接管（界面不会再显示）；false 表示前置条件不满足（如还没导入业务包）
     */
    private fun enterBusinessDirectly(): Boolean {
        worker.execute {
            // 沙箱只允许装一个包，业务源 = 那唯一的包（不再按包名识别，用户 2026-10-28）
            val pkg = BoxRepository.singleInstalledPackage()
            if (pkg == null) {
                // 没有业务 app（首次运行 / 出 bug 丢失）→ **后台直接强制安装**，
                // 不进更新页、不等用户点「立即更新」（用户 2026-10-29）
                main.post { recoverInstallBusiness() }
                return@execute
            }
            // 业务可能在后台运行（从桌面二次进入）：优先把它的 task 直接带回前台，
            // 避免 BlackBox 对已运行进程 re-launch 又建一个 Activity 实例导致卡死。
            val resumed = bringBusinessToFront(this, pkg)
            val ok = if (resumed) {
                Log.i(CarBoxApp.TAG, "默认入口 → 源PLUS 已在运行，带回前台")
                true
            } else {
                BoxSwitchOverlay.markLaunchingBusiness(this)
                BoxRepository.launch(pkg).also {
                    Log.i(CarBoxApp.TAG, "默认入口 → launchApk 源PLUS ok=$it")
                }
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (ok) {
                    Log.i(CarBoxApp.TAG, "默认入口 → 关闭宿主界面")
                    finish()
                } else {
                    Log.i(CarBoxApp.TAG, "业务包拉起失败 → 显示 BlackBox后台界面")
                    showBoxUi()
                }
            }
        }
        return true
    }

    /**
     * 把已在后台运行的业务 app task 带回前台（不重新创建 Activity）。
     *
     * 用于「从桌面二次进入」：业务本来就在跑，直接 moveTaskToFront 即可，
     * 避免 BlackBox 对已运行进程 re-launch（再建一个 Activity 实例 / 触发重建）导致卡死。
     * 找不到业务 task（业务确实没在跑）时返回 false，调用方回退到 launchApk 冷启动。
     *
     * 宿主与沙箱业务同 UID，getRunningTasks / moveTaskToFront 能枚举并操作业务 task（不会被匿名化）。
     */
    @Suppress("DEPRECATION")
    private fun bringBusinessToFront(context: Context, pkg: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(64)
            // 🔴 业务 app 跑在 BlackBox 的**代理 Activity**（ProxyActivity$Px）里：那个 task 的
            //    包名仍然是宿主（com.anrays.yplus）、affinity 是 top.niunaijun.blackbox.task_affinity，
            //    **业务包名根本不会出现在 task 列表里** —— 所以不能按业务包名匹配，只能按承载它的
            //    ProxyActivity 类名识别（实测：getRunningTasks 只返回「宿主 task」+「ProxyActivity task」）。
            val task = tasks.firstOrNull { t ->
                val topCls = t.topActivity?.className.orEmpty()
                val baseCls = t.baseActivity?.className.orEmpty()
                topCls.contains("ProxyActivity") || baseCls.contains("ProxyActivity")
            }
            if (task == null) {
                Log.i(CarBoxApp.TAG, "bringBusinessToFront: 无 ProxyActivity task（业务未运行）pkg=$pkg → 回退 launchApk")
                return false
            }
            am.moveTaskToFront(task.id, 0)
            Log.i(CarBoxApp.TAG, "bringBusinessToFront: moveTaskToFront task=${task.id} top=${task.topActivity?.className}")
            true
        } catch (t: Throwable) {
            Log.w(CarBoxApp.TAG, "bringBusinessToFront failed", t)
            false
        }
    }

    /**
     * 无业务 app（首次运行 / 出 bug 丢包）时：直接进更新页的**「更新中」全屏状态**
     * （循环动画 + 进度条 + 百分比）并自动安装，不弹卡片、不等用户确认（用户 2026-10-29）。
     *
     * 装失败也不退回空桌面：更新页在恢复模式下自己会把按钮变成「重试」。
     */
    private fun recoverInstallBusiness() {
        Log.i(CarBoxApp.TAG, "无业务 app → 直接进入更新状态并自动安装")
        val ok = runCatching {
            startActivity(Intent(this, UpdateActivity::class.java).apply {
                putExtra(UpdateActivity.EXTRA_RECOVER_MODE, true)
                putExtra(UpdateActivity.EXTRA_AUTO_INSTALL, true)
            })
            finish()
        }.isSuccess
        // 极端情况（页面起不来）退回后台界面，至少用户还能手动导入 APK
        if (!ok) showBoxUi()
    }

    /** BlackBox后台界面（只有悬浮按钮切换、导入 APK、或业务包缺失时才出现） */
    private fun showBoxUi() {
        if (boxUiShown || isFinishing) return
        boxUiShown = true
        setContentView(R.layout.activity_main)
        // 标准显示样式（参考源PLUS）：内容视图上下避开系统栏，不跑到通知栏下方
        FrameDisplayMode.applyStandard(this)
        bindViews()

        adapter = AppsAdapter(
            onClick = { item -> launch(item) },
            onLongClick = { item -> showAppMenu(item) }
        )
        rvApps.layoutManager = GridLayoutManager(this, gridSpanCount())
        rvApps.adapter = adapter

        // 「安装应用」= 原「安装 APK」的位置 + 「更新应用」的功能（2026-10-30 合并，两者本来就冲突）
        btnImport.setOnClickListener { showUpdateSourceDialog() }
        btnClone.setOnClickListener { pickFromDevice() }
        btnRefresh.setOnClickListener { refresh() }
        btnNotif.setOnClickListener { openNotificationAccessSettings() }
        // 「立即更新」只做一件事：进更新页（与右上角 Notice 点击落点一致）
        btnUpdateNow.setOnClickListener {
            if (HotUpdateManager.pendingUpdate == null) {
                toast(getString(R.string.update_none_tip))
                return@setOnClickListener
            }
            runCatching { startActivity(Intent(this, UpdateActivity::class.java)) }
                .onFailure { Log.w(CarBoxApp.TAG, "open UpdateActivity failed", it) }
        }
        // 更新检查是**延迟**跑的（打开 App 后 15 秒），界面早就显示完了 ——
        // 只能靠 pending 变化回调补刷，否则按钮永远不出现。
        HotUpdateManager.onPendingChanged = { syncUpdateButton() }
        syncUpdateButton()
        // ACTION_VIEW（用源PLUS 打开 APK）进来时直接导入
        handleExternalApk(intent)

        BoxUiState.boxVisible = true
        BoxUiState.attach(this)

        // 首次启动时虚拟服务进程初始化需要一点时间，稍后自动再刷新一次
        main.postDelayed({ refresh() }, 1200)
        main.postDelayed({ refresh() }, 4000)
    }

    /**
     * 补齐沙箱业务需要的系统权限。
     *
     * 车机上**没有 root**，[PermissionBootstrap] 里那套 su 授权在真机一律失败；
     * 而沙箱内业务（SourcePlus）扫描/连接车机设备、后台对讲都按**宿主 UID** 校验 ——
     * 所以必须在宿主这边真正拿到权限。这里只请求运行时权限（悬浮窗是特殊权限，另行引导）。
     */
    private fun requestMissingPermissions() {
        // 2026-10-30（用户："框架 app 多申请权限，root/系统权限不要"）：把沙箱业务用得上的危险权限
        // 一次要齐。宿主 targetSdk=28 → 走旧权限模型，所以这里只要"旧模型下真会弹框"的那几条：
        //   · 蓝牙扫描：ACCESS_FINE_LOCATION 即扫描权限（BLUETOOTH_SCAN 在 targetSdk≤30 不弹框）
        //   · 通知：POST_NOTIFICATIONS 在 targetSdk≤32 装包即给，不必申请
        //   · 媒体：READ_MEDIA_* 在 targetSdk≤32 由 READ_EXTERNAL_STORAGE 覆盖
        // 🔴 一条 root / 系统签名权限都不放在这里（车机给不了，申请只会永远失败）。
        val wanted = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            wanted += android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            wanted += android.Manifest.permission.READ_PHONE_STATE    // 4G 模块 / SIM 信息
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 后台定位必须与前台定位同批申请，系统才会给"始终允许"选项
            wanted += android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        val need = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) return
        Log.i(CarBoxApp.TAG, "request missing perms: ${need.map { it.substringAfterLast('.') }}")
        runCatching { requestPerms.launch(need.toTypedArray()) }
            .onFailure { Log.w(CarBoxApp.TAG, "request perms failed", it) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldShowBoxUi(intent)) {
            // 悬浮按钮切换 / 用源PLUS 打开 APK：展示管理界面
            if (boxUiShown) {
                handleExternalApk(intent)
                if (!busy) refresh()
            } else {
                showBoxUi()
            }
            return
        }
        // 桌面图标再次点进来：不显示管理界面，直接退回源PLUS
        handleExternalApk(intent)
        Thread({
            val pkg = BoxRepository.singleInstalledPackage()
            if (pkg == null) {
                main.post { recoverInstallBusiness() }
                return@Thread
            }
            // 业务可能已在后台运行：优先带回前台，避免 re-launch 卡死
            if (!bringBusinessToFront(this, pkg)) {
                BoxSwitchOverlay.markLaunchingBusiness(this)
                BoxRepository.launch(pkg)
            }
        }, "relaunch-business").start()
        finish()
    }

    override fun onResume() {
        super.onResume()
        // 把宿主当前权限状态（通知使用权等）同步给沙箱业务：源plus 直接采信，不用自己猜。
        // 放在 boxUiShown 早退之前，保证"直接进源PLUS"的默认入口也会推。
        runCatching { FrameBusinessLink.pushHostPerms(this) }
            .onFailure { Log.w(CarBoxApp.TAG, "push host perms failed", it) }
        // 默认入口（直接进源PLUS）时视图从未创建，这里必须提前返回，否则 lateinit 崩溃
        if (!boxUiShown) return
        BoxUiState.boxVisible = true
        btnNotif.text = if (hasNotificationAccess()) {
            getString(R.string.btn_notif_on)
        } else {
            getString(R.string.btn_notif)
        }
        if (!busy) refresh()
    }

    override fun onPause() {
        BoxUiState.boxVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        BoxUiState.boxVisible = false
        BoxUiState.detach()
        if (current === this) current = null
        super.onDestroy()
        worker.shutdownNow()
    }

    /**
     * 业务经 FrameApi `perms.request` 触发的授权结果回执（宿主自己的 requestMissingPermissions
     * 走 ActivityResultLauncher，不走这里；两者靠 requestCode 区分，互不干扰）。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == FrameApiHost.REQ_PERMS_FRAME) FrameApiHost.onPermissionsResult()
    }

    companion object {
        /** 右上角悬浮按钮切换进来时带这个标记，才会显示 BlackBox后台界面 */
        const val EXTRA_SHOW_BOX = "extra_show_box"

        /** 「更新应用」上次填写的 APK 地址（存在 hot_update 这个 prefs 里） */
        private const val KEY_APK_URL = "apk_url"

        /**
         * 当前存活的管理界面（默认入口"直接进源PLUS"时恒为 null —— 那是正常的，
         * 此时 FrameApi 的权限代申请会退回"打开应用详情页"）。
         */
        @Volatile
        var current: MainActivity? = null
            private set
    }

    // ---------------- 视图 ----------------

    private fun bindViews() {
        rvApps = findViewById(R.id.rvApps)
        emptyView = findViewById(R.id.emptyView)
        tvCount = findViewById(R.id.tvCount)
        tvStatus = findViewById(R.id.tvStatus)
        btnImport = findViewById(R.id.btnImport)
        btnClone = findViewById(R.id.btnClone)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnNotif = findViewById(R.id.btnNotif)
        btnUpdateNow = findViewById(R.id.btnUpdateNow)
    }

    /** 后台界面「立即更新」按钮：有可用更新才高亮显示（2026-10-30） */
    private fun syncUpdateButton() {
        if (!boxUiShown) return
        val has = HotUpdateManager.pendingUpdate != null
        btnUpdateNow.visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 150).coerceIn(3, 7)
    }

    private fun setBusy(value: Boolean, status: String? = null) {
        busy = value
        btnImport.isEnabled = !value
        btnClone.isEnabled = !value
        btnRefresh.isEnabled = !value
        btnNotif.isEnabled = !value
        btnUpdateNow.isEnabled = !value
        tvStatus.visibility = if (value) View.VISIBLE else View.GONE
        if (status != null) tvStatus.text = status
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 文件管理器里点“用源PLUS 打开”时进入的路径 */
    private fun handleExternalApk(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        importApk(uri)
    }

    // ---------------- 列表 ----------------

    private fun applyApps(apps: List<VirtualAppItem>) {
        adapter.submit(apps)
        tvCount.text = getString(R.string.app_count, apps.size)
        emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        rvApps.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refresh() {
        // ⚠️ 界面可能已被 onDestroy 收掉（默认入口是"直接进源PLUS"，进去就 finish），
        //    此时 worker 已 shutdown，延迟的 refresh 再提交会抛 RejectedExecutionException 把进程搞崩。
        if (isFinishing || isDestroyed) return
        runCatching {
            worker.execute {
                val apps = BoxRepository.listVirtualApps()
                main.post {
                    if (!isFinishing && !isDestroyed) applyApps(apps)
                }
            }
        }.onFailure { Log.w(CarBoxApp.TAG, "refresh skipped: ${it.javaClass.simpleName}") }
    }

    // ---------------- 安装 ----------------

    private fun openPicker() {
        if (busy) return
        runCatching {
            pickApk.launch(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }.onFailure {
            Log.e(CarBoxApp.TAG, "open document picker failed", it)
            toast(getString(R.string.no_file_picker))
        }
    }

    /**
     * SAF 选中文件后安装（「安装 APK」与「更新应用 → 选择文件导入」共用）。
     * 统一走 [HotUpdateManager.installLocalApk]：业务包会备份/回滚/静默复活，其它包直接进沙箱。
     */
    private fun importApk(uri: Uri) {
        if (busy) return
        setBusy(true, getString(R.string.installing))
        worker.execute {
            val file = BoxRepository.copyUriToCache(this, uri)
            val result = file?.let { HotUpdateManager.installLocalApk(this, it) }
            runCatching { file?.delete() }
            val apps = BoxRepository.listVirtualApps()
            main.post {
                if (isFinishing || isDestroyed) return@post
                setBusy(false)
                applyApps(apps)
                when (result) {
                    null -> toast(getString(R.string.update_pick_failed))
                    is HotUpdateManager.UpdateResult.Success ->
                        toast(getString(R.string.update_ok, result.remote))
                    is HotUpdateManager.UpdateResult.Failed -> toast(updateFailText(result))
                }
            }
        }
    }

    /** 安装失败的统一文案：回滚过就明说，免得用户以为还是旧版本正常 */
    private fun updateFailText(result: HotUpdateManager.UpdateResult.Failed): String =
        if (result.rolledBack) {
            getString(R.string.update_fail_rolled_back, result.message)
        } else {
            getString(R.string.update_fail, result.message)
        }

    private fun pickFromDevice() {
        if (busy) return
        setBusy(true, getString(R.string.loading_device_apps))
        worker.execute {
            val apps = BoxRepository.listDeviceApps(this)
            main.post {
                setBusy(false)
                if (apps.isEmpty()) {
                    toast(getString(R.string.no_device_apps))
                } else {
                    showDeviceAppsDialog(apps)
                }
            }
        }
    }

    private fun showDeviceAppsDialog(apps: List<DeviceAppItem>) {
        val listView = ListView(this)
        listView.adapter = DeviceAppsAdapter(this, apps)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.clone_title))
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            cloneDeviceApp(apps[position])
        }
        dialog.show()
    }

    private fun cloneDeviceApp(item: DeviceAppItem) {
        if (busy) return
        setBusy(true, getString(R.string.installing))
        worker.execute {
            val error = BoxRepository.cloneDeviceApp(item.packageName)
            val apps = BoxRepository.listVirtualApps()
            main.post {
                setBusy(false)
                toast(if (error == null) getString(R.string.install_ok) else getString(R.string.install_fail, error))
                applyApps(apps)
            }
        }
    }

    // ---------------- 更新应用 ----------------

    /**
     * 「更新应用」先选 APK 来源（用户 2026-10-28 指定）：
     *   ① 扫描本地 APK 文件（常见目录里自动找）；
     *   ② 调用原生文件导入（系统文件选择器 SAF）；
     *   ③ 提供下载链接（Gitee 仓库文件地址 / 外站直链）；
     *   ④ 同步线上版本（内置 Gitee 清单里的 APK，点一下直接装，不比对 seed）。
     */
    private fun showUpdateSourceDialog() {
        if (busy) return
        val items = arrayOf(
            getString(R.string.update_src_scan),
            getString(R.string.update_src_pick),
            getString(R.string.update_src_url),
            getString(R.string.update_src_online)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> scanLocalApks()
                    1 -> openPicker()
                    2 -> showUpdateUrlDialog()
                    3 -> syncOnlineVersion()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** ④ 同步线上版本：直接从内置 Gitee 清单取 APK 覆盖安装（不比对 seed，用户 2026-10-28） */
    private fun syncOnlineVersion() {
        if (busy) return
        setBusy(true, getString(R.string.update_checking))
        worker.execute {
            var lastPct = -1
            val result = HotUpdateManager.syncOnline(this) { downloaded, total ->
                val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                if (pct != lastPct) {
                    lastPct = pct
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        tvStatus.text = if (pct >= 0) {
                            getString(R.string.update_downloading_pct, pct)
                        } else {
                            getString(R.string.update_checking)
                        }
                    }
                }
            }
            val apps = BoxRepository.listVirtualApps()
            main.post {
                if (isFinishing || isDestroyed) return@post
                setBusy(false)
                applyApps(apps)
                when (result) {
                    is HotUpdateManager.UpdateResult.Success ->
                        toast(getString(R.string.update_ok, result.remote))
                    is HotUpdateManager.UpdateResult.Failed -> toast(updateFailText(result))
                }
            }
        }
    }

    /** legacy 存储（宿主 targetSdk=28）下扫描公共目录要的权限 */
    private fun hasStorageAccess(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** ① 扫描本地 APK：没权限先要权限，拿到后自动继续 */
    private fun scanLocalApks() {
        if (busy) return
        if (!hasStorageAccess()) {
            pendingScanAfterPerm = true
            runCatching {
                requestPerms.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
            }.onFailure {
                pendingScanAfterPerm = false
                toast(getString(R.string.update_scan_no_permission))
            }
            return
        }
        setBusy(true, getString(R.string.update_scanning))
        worker.execute {
            val apks = LocalApkScanner.scan(this)
            main.post {
                if (isFinishing || isDestroyed) return@post
                setBusy(false)
                if (apks.isEmpty()) {
                    toast(getString(R.string.update_scan_empty))
                } else {
                    showLocalApksDialog(apks)
                }
            }
        }
    }

    private fun showLocalApksDialog(apks: List<LocalApkItem>) {
        val listView = ListView(this)
        listView.adapter = LocalApksAdapter(this, apks)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_scan_title, apks.size))
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            installLocalApk(apks[position])
        }
        dialog.show()
    }

    /** 扫描列表里选中一个本地 APK → 强制安装 */
    private fun installLocalApk(item: LocalApkItem) {
        if (busy) return
        setBusy(true, getString(R.string.installing))
        worker.execute {
            val result = HotUpdateManager.installLocalApk(this, item.file)
            val apps = BoxRepository.listVirtualApps()
            main.post {
                if (isFinishing || isDestroyed) return@post
                setBusy(false)
                applyApps(apps)
                when (result) {
                    is HotUpdateManager.UpdateResult.Success ->
                        toast(getString(R.string.update_ok, result.remote))
                    is HotUpdateManager.UpdateResult.Failed -> toast(updateFailText(result))
                }
            }
        }
    }

    /**
     * ③ 下载链接：输入 APK 地址，**强制**下载并覆盖安装进沙箱。
     * 不读 update.json、不比对版本 —— 填什么地址就装什么（同一版也照装）。
     */
    private fun showUpdateUrlDialog() {
        if (busy) return
        val prefs = getSharedPreferences("hot_update", MODE_PRIVATE)
        val input = EditText(this).apply {
            // 记住上次填的地址，重复测同一版时不用重新粘贴
            setText(prefs.getString(KEY_APK_URL, ""))
            hint = getString(R.string.update_url_hint)
            setSingleLine(false)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_url_title))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_start) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) {
                    toast(getString(R.string.update_url_empty))
                } else {
                    prefs.edit().putString(KEY_APK_URL, url).apply()
                    forceUpdate(url)
                }
            }
            .show()
    }

    /** 下载 + 强制安装；进度只更新顶部状态行，不弹窗打断。 */
    private fun forceUpdate(url: String) {
        if (busy) return
        setBusy(true, getString(R.string.update_checking))
        worker.execute {
            var lastPct = -1
            val result = HotUpdateManager.forceInstall(this, url) { downloaded, total ->
                val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                if (pct != lastPct) {
                    lastPct = pct
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        tvStatus.text = if (pct >= 0) {
                            getString(R.string.update_downloading_pct, pct)
                        } else {
                            getString(R.string.update_checking)
                        }
                    }
                }
            }
            val apps = BoxRepository.listVirtualApps()
            main.post {
                if (isFinishing || isDestroyed) return@post
                setBusy(false)
                applyApps(apps)
                when (result) {
                    is HotUpdateManager.UpdateResult.Success ->
                        toast(getString(R.string.update_ok, result.remote))
                    is HotUpdateManager.UpdateResult.Failed -> toast(updateFailText(result))
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---------------- 通知使用权 ----------------

    private fun openNotificationAccessSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            Log.e(CarBoxApp.TAG, "open notification listener settings failed", it)
            toast(getString(R.string.notif_settings_fail))
        }
    }

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    // ---------------- 应用操作 ----------------

    private fun launch(item: VirtualAppItem) {
        setBusy(true)
        worker.execute {
            val ok = BoxRepository.launch(item.packageName)
            main.post {
                setBusy(false)
                if (!ok) toast(getString(R.string.launch_fail, item.label))
            }
        }
    }

    private fun showAppMenu(item: VirtualAppItem) {
        val actions = arrayOf(
            getString(R.string.action_launch),
            getString(R.string.action_clear_data),
            getString(R.string.action_uninstall)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.label)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> launch(item)
                    1 -> clearData(item)
                    2 -> confirmUninstall(item)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearData(item: VirtualAppItem) {
        setBusy(true)
        worker.execute {
            val error = BoxRepository.clearData(item.packageName)
            main.post {
                setBusy(false)
                toast(if (error == null) getString(R.string.clear_ok) else getString(R.string.clear_fail, error))
            }
        }
    }

    private fun confirmUninstall(item: VirtualAppItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_uninstall))
            .setMessage(getString(R.string.uninstall_confirm, item.label))
            .setPositiveButton(R.string.confirm) { _, _ -> uninstall(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uninstall(item: VirtualAppItem) {
        setBusy(true, getString(R.string.uninstalling))
        worker.execute {
            val error = BoxRepository.uninstall(item.packageName)
            val apps = BoxRepository.listVirtualApps()
            main.post {
                setBusy(false)
                toast(if (error == null) getString(R.string.uninstall_ok) else getString(R.string.uninstall_fail, error))
                applyApps(apps)
            }
        }
    }
}
