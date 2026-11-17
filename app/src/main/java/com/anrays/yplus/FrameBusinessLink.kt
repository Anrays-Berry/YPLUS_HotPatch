package com.anrays.yplus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import top.niunaijun.blackbox.BlackBoxCore

// 宿主（宿主）→ 沙箱内业务 App（源PLUS）的「更新数据」通道。
//
// 为什么需要：业务的更新提示（Notice 长条 + 侧栏「更新」入口 + 更新页）本来就是现成的，
// 而真正的下载/校验/沙箱安装只有宿主会做。于是把宿主查到的「有新版本 / 下载进度 /
// 正在安装 / 完成 / 失败」推给业务，让用户在自己的更新页里看进度，宿主不再弹自己那套 UI。
//
// 通道：BlackBox 的 BActivityManager.startService 到虚拟包内组件
// （SendBroadcast / ContentProvider 都要额外包 stub，startService 这条链路在
// HostBridge 拉 KeepAliveService 时已经跑通，intent extras 完整保留）。
//
// 能力探测：读业务 APK 的 AndroidManifest meta-data `frame.update.api`。
// 老版本业务包没有这条 → 返回 false，调用方回退到宿主自己的悬浮提示条 / 更新页面。
object FrameBusinessLink {

    private const val TAG = "FrameBizLink"

    /** 业务 APK 里声明的能力标记（SourcePlus AndroidManifest 同名 meta-data） */
    private const val API_META = "frame.update.api"

    /** 业务侧接收更新指令的服务（SourcePlus core/FrameUpdateService.kt）。
     *  类名在业务包名下，故只存后缀、运行时拼 —— 业务 APK 改包名也不用动宿主（用户 2026-10-28）。 */
    private const val BUSINESS_SERVICE_SUFFIX = ".core.FrameUpdateService"

    /** 与业务侧 FrameUpdateService.EXTRA_PAYLOAD 一致 */
    private const val EXTRA_PAYLOAD = "frame_update_payload"

    /** 支持的最低协议版本 */
    private const val API_VERSION = 1

    /** 进度推送节流：下载回调每 ~48KB 一次，原样跨进程推太频繁（300ms 一次足够跟手） */
    private const val PROGRESS_MIN_MS = 300L

    /** 能力探测结果缓存（业务包被覆盖安装后要 invalidate） */
    @Volatile
    private var supported: Boolean? = null

    @Volatile
    private var lastProgressAt = 0L

    /** 业务 App 是否支持宿主驱动更新（结果缓存；业务包升级后调用 [invalidate]） */
    fun supports(): Boolean {
        supported?.let { return it }
        val value = detect()
        supported = value
        return value
    }

    fun invalidate() {
        supported = null
        // 业务包被覆盖安装了：manifest 里的 `frame.consumer` 声明要重读（否则最多延迟一个 TTL
        // 才切换到"更新归业务自己/宿主承接"的正确分支）。见 BusinessManifest。
        runCatching { BusinessManifest.invalidate() }
    }

    private fun detect(): Boolean {
        val path = HotUpdateManager.businessApkPath()?.takeIf { it.isNotEmpty() } ?: return false
        val archive = runCatching {
            @Suppress("DEPRECATION")
            CarBoxApp.instance.packageManager
                .getPackageArchiveInfo(path, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return false
        val raw = archive.applicationInfo?.metaData?.get(API_META)
        val api = when (raw) {
            is Int -> raw
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
        // 顺带把业务包 label 打出来：宿主的桌面展示名（strings 的 app_name）是编译期资源，
        // 运行时改不了，只能手工与消费端保持一致 —— 这条日志就是用来核对有没有写错的。
        val label = runCatching {
            archive.applicationInfo?.loadLabel(CarBoxApp.instance.packageManager)?.toString()
        }.getOrNull()
        Log.i(TAG, "business $API_META=$api (pkg=${archive.packageName}, label=$label)")
        return api >= API_VERSION
    }

    /** 发现新版本：版本号 + 种子 + 更新说明 html 原文（业务侧落盘后用 file:// 加载） */
    fun pushAvailable(pending: HotUpdateManager.PendingUpdate, notesHtml: String?): Boolean {
        lastProgressAt = 0L
        val json = JSONObject().apply {
            put("action", "available")
            put("version", pending.version)
            put("seed", pending.seed)
            put("notesHtml", notesHtml.orEmpty())
            put("pushTime", pending.pushTime)
        }
        return push(json)
    }

    /** 下载进度（总长为 0 表示还没解析出总量，业务侧画不确定进度条） */
    fun pushProgress(downloaded: Long, total: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (total > 0 && downloaded < total && now - lastProgressAt < PROGRESS_MIN_MS) return true
        lastProgressAt = now
        val json = JSONObject().apply {
            put("action", "progress")
            put("downloaded", downloaded)
            put("total", total)
        }
        return push(json)
    }

    /** 下载完成、开始覆盖安装（安装会 kill 掉业务进程，所以这一帧必须提前推） */
    fun pushInstalling(): Boolean = push(JSONObject().put("action", "installing"))

    /** 安装成功（业务进程已被重启，这次 startService 会把它重新拉起并送达） */
    fun pushDone(): Boolean = push(JSONObject().put("action", "done"))

    fun pushFailed(message: String): Boolean = push(
        JSONObject().apply {
            put("action", "failed")
            put("message", message)
        }
    )

    /** 明确的「这次更新不做了」：业务侧状态清空、侧栏入口消失 */
    fun clear(): Boolean = push(JSONObject().put("action", "clear"))

    /**
     * 权限快照：宿主把自己持有的权限状态推给沙箱业务（目前只同步「通知使用权」）。
     * 业务侧据此直接采信，不再去系统设置里猜 —— 因为沙箱里真正的授权在宿主包上。
     * （用户 2026-10-28：改成"信息传递"，并且源plus 独立运行时有本地回退。）
     */
    /**
     * 把宿主持有的权限快照推给业务（**整包推**，不是一个权限一个字段）。
     *
     * 为什么要由宿主来报：这些能力的**真正校验**发生在 BlackBox 代理之外
     * （系统音频/定位服务按真实 UID 查；通知监听、无障碍按真实组件绑定），
     * 沙箱里的业务 `checkSelfPermission` 会被 BlackBox 骗成"有"，真用时却被系统按 UID 拒 ——
     * 表现就是"显示有权限、实际用不了"。业务只能信宿主这份。
     *
     * 加新权限只改 [hostPermSnapshot] 一处，业务侧自动可见。
     */
    fun pushHostPerms(context: Context): Boolean {
        val json = JSONObject().apply {
            put("action", "host_perms")
            put("perms", hostPermSnapshot(context))
        }
        return push(json)
    }

    /** 宿主权限快照：能力名 → 是否已开启 */
    fun hostPermSnapshot(context: Context): JSONObject {
        fun runtime(p: String) = runCatching {
            context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        // 🔴 运行时权限的"模型"跟**本进程 targetSdk** 走，而盒子业务借用宿主 UID = 借用宿主的模型。
        //    宿主 targetSdk=28：Android 12/13 上系统按旧模型给 BLUETOOTH / READ_EXTERNAL_STORAGE，
        //    BLUETOOTH_SCAN / READ_MEDIA_AUDIO 会一直 DENIED —— 拿新权限名去查会永远报 false。
        val target = runCatching { context.applicationInfo.targetSdkVersion }.getOrDefault(28)
        fun bluetooth(): Boolean = if (target >= 31) {
            runtime(android.Manifest.permission.BLUETOOTH_SCAN) &&
                runtime(android.Manifest.permission.BLUETOOTH_CONNECT) &&
                runtime(android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            runtime(android.Manifest.permission.BLUETOOTH) &&
                runtime(android.Manifest.permission.BLUETOOTH_ADMIN) &&
                runtime(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        fun mediaAudio(): Boolean = if (target >= 33) {
            runtime(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            runtime(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        // 与业务侧 LogcatPermission.hasReadLogsPermission 同口径（权限 + AppOps 双判）
        fun readLogs(): Boolean {
            val perm = runtime(android.Manifest.permission.READ_LOGS)
            val opAllowed = runCatching {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    "android:read_logs", android.os.Process.myUid(), context.packageName
                ) == android.app.AppOpsManager.MODE_ALLOWED
            }.getOrDefault(true)
            return perm && opAllowed
        }
        fun allFiles(): Boolean = if (android.os.Build.VERSION.SDK_INT < 30) true
        else runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        return JSONObject()
            // 这两个必须宿主持有：系统按 UID 校验，BlackBox 代理不到（见 requestMissingPermissions）
            .put("recordAudio", runtime(android.Manifest.permission.RECORD_AUDIO))
            .put("location", runtime(android.Manifest.permission.ACCESS_FINE_LOCATION))
            // 这两个必须宿主持有：系统只绑定真实安装包的组件
            .put("notificationAccess", HostNotifyHub.granted(context))
            // 🔴 用系统设置判定（跨进程真值），不能用 isConnected —— 处理推送的进程未必是
            //    服务绑定的那个进程，那边 instance 恒为 null，会把"已开启"报成关闭。
            .put("accessibility", HostAccessibilityService.isEnabled(context))
            .put("overlay", runCatching { android.provider.Settings.canDrawOverlays(context) }.getOrDefault(false))
            // 2026-10-29：盒子业务的诊断页要用（本地 checkSelfPermission 被 BlackBox 伪造为已授予）
            .put("bluetooth", bluetooth())
            .put("mediaAudio", mediaAudio())
            .put("readLogs", readLogs())
            .put("allFiles", allFiles())
    }

    /** 宿主自己的通知使用权是否开启（enabled_notification_listeners 里含本包监听组件） */
    fun hostNotificationAccess(context: Context): Boolean = try {
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        )
        if (flat.isNullOrEmpty()) false
        else {
            val pkg = context.packageName
            flat.split(":").any { it.substringBefore('/').equals(pkg, ignoreCase = true) }
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 某 App 的通知被移除（播放器停止 / 通知被清 / 音乐 App 被杀）。
     *
     * 为什么要宿主代转：业务自己的 `MediaNotificationListener` 在盒子里**一条回调都收不到**
     * （系统只绑定真实安装的组件），而"通知移除"正是业务判定"音乐停止"的既有入口。
     * 转发过去后业务侧只是把现成的 `MusicCenter.onMediaRemoved(pkg)` 调一遍，无新增逻辑。
     */
    fun pushMediaRemoved(pkg: String): Boolean = push(
        JSONObject().apply {
            put("action", "media_removed")
            put("package", pkg)
        }
    )

    /** API 响应：{action:"api_result", id, ok, data|error, message}（见 FrameApi/FrameApiHost） */
    fun pushApiResult(id: String, ok: Boolean, data: String?, error: String?, message: String?): Boolean {
        val json = JSONObject().apply {
            put("action", "api_result")
            put("id", id)
            put("ok", ok)
            if (data != null) put("data", data)
            if (error != null) put("error", error)
            if (message != null) put("message", message)
        }
        return push(json)
    }

    /** API 长任务事件：{action:"api_event", id, event, data} */
    fun pushApiEvent(id: String, event: String, data: String?): Boolean {
        val json = JSONObject().apply {
            put("action", "api_event")
            put("id", id)
            put("event", event)
            if (data != null) put("data", data)
        }
        return push(json)
    }

    private fun push(json: JSONObject): Boolean {
        val pkg = HostBridge.businessPackage() ?: return false
        return try {
            val intent = Intent().apply {
                setClassName(pkg, pkg + BUSINESS_SERVICE_SUFFIX)
                putExtra(EXTRA_PAYLOAD, json.toString())
            }
            BlackBoxCore.getBActivityManager()
                .startService(intent, null, true, HostBridge.USER_ID)
            Log.i(TAG, "→ business ${json.optString("action")}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "push failed: $json", t)
            false
        }
    }
}
