package com.anrays.yplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import top.niunaijun.blackbox.BlackBoxCore

/**
 * 框架能力 API 的宿主实现：`FrameUpdateHostService` 收到 `cmd="api"` 后转到这里。
 * 协议见 [FrameApi]；结果/事件经 [FrameBusinessLink] 推回消费端。
 */
object FrameApiHost {

    private const val TAG = "FrameApiHost"
    private const val PREFS = "frame_api"
    private const val KEY_LAST_INSTALL = "last_install"

    /** 框架通知渠道（`notify.post` 用；同名渠道反复 createNotificationChannel 是幂等的） */
    private const val CHANNEL_ID = "frame_api"

    /** 同一时刻只允许一个安装任务 */
    private val installing = AtomicBoolean(false)

    fun dispatch(context: Context, intent: Intent) {
        val id = intent.getStringExtra(FrameApi.EXTRA_ID).orEmpty()
        val api = intent.getStringExtra(FrameApi.EXTRA_API).orEmpty()
        val params = runCatching {
            JSONObject(intent.getStringExtra(FrameApi.EXTRA_PARAMS).orEmpty())
        }.getOrDefault(JSONObject())
        if (id.isEmpty() || api.isEmpty()) {
            Log.w(TAG, "bad request id=$id api=$api")
            return
        }
        Log.i(TAG, "api=$api id=$id")
        try {
            when (api) {
                FrameApi.API_CAPABILITIES -> ok(id, capabilities(context))
                FrameApi.API_APP_INSTALL -> appInstall(context, id, params)
                FrameApi.API_APP_INSTALL_STATUS -> ok(id, lastInstall(context))
                FrameApi.API_APP_LIST -> ok(id, JSONObject().put("apps", appList(context)))
                FrameApi.API_APP_LAUNCH -> appLaunch(context, id, params)
                FrameApi.API_APP_UNINSTALL -> appUninstall(context, id, params)
                FrameApi.API_PERMS_GET -> ok(id, permsGet(context))
                FrameApi.API_PERMS_OPEN_SETTINGS -> permsOpen(context, id, params)
                FrameApi.API_OPEN_BOX -> {
                    openBox(context)
                    ok(id, JSONObject().put("ok", true))
                }
                FrameApi.API_OVERLAY_VISIBLE -> overlayVisible(context, id, params)
                FrameApi.API_NOTIFY_TOAST -> {
                    toast(context, params.optString("text"))
                    ok(id, JSONObject().put("ok", true))
                }
                FrameApi.API_NOTIFY_POST -> {
                    // 返回真实通知 id：后续要用 notify.cancel 撤销 / 用同一个 id 更新进度
                    val nid = postNotification(context, params)
                    ok(id, JSONObject().put("ok", true).put("id", nid))
                }
                FrameApi.API_NOTIFY_CANCEL -> {
                    notifyCancel(context, params)
                    ok(id, JSONObject().put("ok", true))
                }
                FrameApi.API_UPDATE_CHECK -> updateCheck(context, id)
                FrameApi.API_UPDATE_APPLY -> updateApply(context, id)
                FrameApi.API_DEVICE_INFO -> ok(id, deviceInfo(context))
                // 通知使用权：系统只把通知给真实安装的宿主，业务在沙箱里只能从这里拿
                FrameApi.API_NOTIFY_STATE -> ok(id, HostNotifyHub.state(context))
                // 无障碍：业务自己的 AccessibilityService 在沙箱里绑不上，全局动作由宿主代执行
                FrameApi.API_ACCESS_STATE -> ok(id, accessState(context))
                FrameApi.API_ACCESS_PERFORM -> accessPerform(id, params)
                // ---- v2（2026-10-29 一批）：全部是"盒子里业务按宿主 uid/包名校验必然做不到"的能力 ----
                FrameApi.API_FRAME_PING -> ok(id, JSONObject().put("ok", true).put("v", FrameApi.VERSION))
                FrameApi.API_FRAME_LOG -> frameLog(id, params)
                FrameApi.API_APP_INFO -> appInfo(context, id, params)
                FrameApi.API_APP_CLEAR_DATA -> appClearData(id, params)
                FrameApi.API_PERMS_REQUEST -> permsRequest(context, id, params)
                FrameApi.API_ACCESS_GESTURE -> accessGesture(id, params)
                FrameApi.API_POWER_WHITELIST -> powerWhitelist(context, id)
                FrameApi.API_CLIPBOARD_GET -> clipboardGet(context, id)
                FrameApi.API_CLIPBOARD_SET -> clipboardSet(context, id, params)
                // ---- v3（2026-10-30）：通用底座，业务自服务，不再为具体需求加 api ----
                FrameApi.API_FRAME_KV -> frameKv(context, id, params)
                else -> err(id, FrameApi.ERR_UNSUPPORTED, "unsupported api: $api")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "api failed: $api", t)
            err(id, FrameApi.ERR_INTERNAL, t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------------- 基本信息 ----------------

    private fun frameVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun capabilities(context: Context): JSONObject = JSONObject().apply {
        put("v", FrameApi.VERSION)
        // v2 起 apis = [{name, since}]：消费端不仅能判"有没有"，还能判"这条 api 的哪个参数能用"
        // （老 v1 消费端读到的是对象数组，必须按 v 切解析方式 —— 业务应用的 FrameApiClient 已支持两种）
        put("apis", JSONArray().apply {
            FrameApi.CATALOG.forEach { e ->
                put(JSONObject().put("name", e.name).put("since", e.since))
            }
        })
        // 只给人看/日志用，不参与判定
        put("names", JSONArray(FrameApi.SUPPORTED))
        put("frameVersion", frameVersion(context))
        put("hostPkg", context.packageName)
    }

    private fun deviceInfo(context: Context): JSONObject = JSONObject().apply {
        put("frameVersion", frameVersion(context))
        put("hostPkg", context.packageName)
        put("userId", HotUpdateManager.USER_ID)
        put("sdkInt", Build.VERSION.SDK_INT)
    }

    // ---------------- 通知使用权 / 无障碍（沙箱业务拿不到，宿主代劳） ----------------

    /**
     * 宿主无障碍服务是否已开启。
     * 🔴 用 [HostAccessibilityService.isEnabled]（读系统设置）而不是 isConnected（进程内 instance）：
     *    处理本请求的进程可能不是服务绑定的进程，那边 instance 恒为 null，会把"已开启"报成关闭。
     */
    private fun accessState(context: Context): JSONObject {
        val enabled = HostAccessibilityService.isEnabled(context)
        Log.i(
            TAG,
            "access.state: proc=${processName(context)} connected=${HostAccessibilityService.isConnected()} enabled=$enabled"
        )
        return JSONObject().put("granted", enabled)
    }

    /** 当前进程名（诊断用：确认处理 API 的进程是不是服务绑定的那个） */
    private fun processName(context: Context): String = runCatching {
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName.orEmpty()
    }.getOrDefault("?")

    /**
     * 让宿主代执行一次全局动作（回桌面 / 返回 / 最近任务…）；
     * params.action = HOME | BACK | RECENTS | NOTIFICATIONS | QUICK_SETTINGS | POWER_DIALOG | LOCK_SCREEN
     */
    private fun accessPerform(id: String, params: JSONObject) {
        val action = params.optString("action")
        if (action.isEmpty()) {
            err(id, FrameApi.ERR_BAD_PARAMS, "缺少 action")
            return
        }
        val error = HostAccessibilityService.perform(action)
        if (error == null) {
            ok(id, JSONObject().put("ok", true).put("action", action))
        } else {
            err(id, FrameApi.ERR_PERMISSION, error)
        }
    }

    /**
     * 权限查询：**统一走 [FrameBusinessLink.hostPermSnapshot] 那份清单**（perms 对象），
     * 不再一个个字段往外铺 —— 宿主加新权限只改一处，业务侧自动可见。
     * 语义特殊的（batteryOptimized = 被优化，值越大越糟）单独放，不混进清单。
     */
    private fun permsGet(context: Context): JSONObject = JSONObject().apply {
        put("perms", FrameBusinessLink.hostPermSnapshot(context))
        put("overlay", runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false))
        put("batteryOptimized", isBatteryOptimized(context))
    }

    private fun isBatteryOptimized(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) false
        else {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 打开系统设置页。
     * `which`：`notification` / `battery` / `overlay`（v1）
     *        ＋ `allFiles`（全部文件访问）/ `appDetails` / `exactAlarm` / `writeSettings`（v2）
     *
     * 这些都是**特殊权限**（只能用户自己点），宿主只能把页面开到正确的深度 —— 别试图代授权。
     */
    private fun permsOpen(context: Context, id: String, params: JSONObject) {
        val which = params.optString("which", "overlay")
        val pkgUri = Uri.parse("package:" + context.packageName)
        val base = when (which) {
            "notification" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            // 全部文件访问：API30+ 才有"直接跳到本应用这一页"，低版本退应用详情
            "allFiles" -> if (Build.VERSION.SDK_INT >= 30)
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            "appDetails" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            "exactAlarm" -> if (Build.VERSION.SDK_INT >= 31)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            "writeSettings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
            else -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
        }
        runCatching { context.startActivity(base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "open settings failed: $which", it) }
        ok(id, JSONObject().put("ok", true).put("which", which))
    }

    // ---------------- 权限代申请（v2） ----------------

    /** 待回执的运行时权限请求：id + 申请的权限清单 + 应用 context（Activity 可能已销毁） */
    private val pendingPerms = AtomicReference<Triple<String, List<String>, Context>?>(null)

    /** 请求码：与宿主自己 `requestMissingPermissions`（ActivityResultLauncher）不冲突即可 */
    const val REQ_PERMS_FRAME = 0x5F01

    /**
     * 宿主代申请运行时权限。
     * 🔴 为什么必须宿主做：沙箱里业务的 `requestPermissions` 是 BlackBox 伪造的，录音/定位/READ_LOGS
     *    实际按**宿主 uid** 校验（宿主 `requestMissingPermissions` 的注释也是这个原因）。
     *
     * 三种结局：
     *   ① 全都已授予 → 直接回 `granted`；
     *   ② 宿主界面在前台 → 拉系统授权框，结果经 [onPermissionsResult] 回执；
     *   ③ 宿主没有前台界面 → 打开"应用详情"页，回 `opened=true`（业务据此引导用户手动开）。
     */
    private fun permsRequest(context: Context, id: String, params: JSONObject) {
        val arr = params.optJSONArray("perms") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { p -> p.isNotEmpty() } }
        if (list.isEmpty()) {
            err(id, FrameApi.ERR_BAD_PARAMS, "perms 不能为空")
            return
        }
        val missing = list.filter { !granted(context, it) }
        val act = MainActivity.current
        if (missing.isEmpty() || act == null || act.isFinishing) {
            if (missing.isNotEmpty()) openAppDetails(context)
            ok(id, permReport(context, list, asked = false, opened = missing.isNotEmpty()))
            return
        }
        pendingPerms.set(Triple(id, missing, context.applicationContext))
        act.runOnUiThread {
            runCatching { ActivityCompat.requestPermissions(act, missing.toTypedArray(), REQ_PERMS_FRAME) }
                .onFailure {
                    Log.w(TAG, "request perms failed", it)
                    val (rid, rlist, rctx) = pendingPerms.getAndSet(null) ?: return@onFailure
                    ok(rid, permReport(rctx, rlist, asked = false, opened = true))
                }
        }
    }

    /**
     * 由 `MainActivity.onRequestPermissionsResult` 转发进来（用户点了允许/拒绝之后）。
     * 没有待回执的请求时什么都不做 —— 宿主自己那套 launcher 的请求也会走到这里。
     */
    fun onPermissionsResult() {
        val (id, list, ctx) = pendingPerms.getAndSet(null) ?: return
        ok(id, permReport(ctx, list, asked = true, opened = false))
    }

    private fun granted(context: Context, perm: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 23) true
        else context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun permReport(context: Context, list: List<String>, asked: Boolean, opened: Boolean): JSONObject {
        val grantedMap = JSONObject()
        val missing = JSONArray()
        list.forEach { p ->
            val ok = granted(context, p)
            grantedMap.put(p, ok)
            if (!ok) missing.put(p)
        }
        return JSONObject()
            .put("granted", grantedMap)
            .put("missing", missing)
            .put("asked", asked)
            .put("opened", opened)
    }

    private fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open app details failed", it) }
    }

    // ---------------- 坐标手势（v2） ----------------

    /**
     * 注入坐标手势：`{op:"tap"|"swipe", x1,y1,x2,y2, duration}`
     * 单位是**屏幕像素**（消费端自己乘 density；这里不做 dp 换算，避免两端口径打架）。
     */
    private fun accessGesture(id: String, params: JSONObject) {
        val op = params.optString("op", "tap").trim().lowercase()
        if (op != "tap" && op != "swipe") {
            err(id, FrameApi.ERR_BAD_PARAMS, "op 只支持 tap / swipe"); return
        }
        val x1 = params.optDouble("x1", 0.0).toFloat()
        val y1 = params.optDouble("y1", 0.0).toFloat()
        val x2 = params.optDouble("x2", x1.toDouble()).toFloat()
        val y2 = params.optDouble("y2", y1.toDouble()).toFloat()
        val duration = params.optLong("duration", if (op == "tap") 50L else 300L)
        val error = HostAccessibilityService.gesture(op, x1, y1, x2, y2, duration)
        if (error == null) ok(id, JSONObject().put("ok", true).put("op", op))
        else err(id, FrameApi.ERR_PERMISSION, error)
    }

    // ---------------- 电池白名单 / 剪贴板 / 日志（v2） ----------------

    /** 申请电池优化白名单（doze 下定时/保活的前提）。已白名单 → 不再弹任何界面。 */
    private fun powerWhitelist(context: Context, id: String) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val whitelisted = runCatching {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        var requested = false
        if (!whitelisted) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                requested = true
            }.onFailure {
                Log.w(TAG, "request battery whitelist failed", it)
                // 没有 DIRECT BOOT 权限的设备会抛：退到设置页列表，让用户自己选
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        ok(id, JSONObject().put("whitelisted", whitelisted).put("requested", requested))
    }

    private fun clipboardGet(context: Context, id: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = runCatching { cm?.primaryClip }.getOrNull()
        val text = runCatching {
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        }.getOrDefault("")
        ok(id, JSONObject().put("text", text))
    }

    private fun clipboardSet(context: Context, id: String, params: JSONObject) {
        val text = params.optString("text")
        val label = params.optString("label").ifEmpty { "业务应用" }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        Handler(Looper.getMainLooper()).post {
            runCatching { cm?.setPrimaryClip(ClipData.newPlainText(label, text)) }
                .onFailure { Log.w(TAG, "clipboard set failed", it) }
        }
        ok(id, JSONObject().put("ok", true))
    }

    /** 业务日志写进宿主 logcat：跨进程排查时两边的日志在同一个 tag 附近，`adb logcat -s FrameApiHost` 就能看全 */
    private fun frameLog(id: String, params: JSONObject) {
        val text = params.optString("text").ifEmpty { params.toString() }
        Log.i(TAG, "[business] $text")
        ok(id, JSONObject().put("ok", true))
    }

    private fun openBox(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SHOW_BOX, true)
            )
        }.onFailure { Log.w(TAG, "open box failed", it) }
    }

    private fun overlayVisible(context: Context, id: String, params: JSONObject) {
        val visible = params.optBoolean("visible", true)
        BoxSwitchOverlay.setBusinessHidden(!visible)
        BoxSwitchOverlay.writeHiddenFlag(context, !visible)
        ok(id, JSONObject().put("ok", true))
    }

    private fun toast(context: Context, text: String) {
        if (text.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * 发通知，返回真实 id（v2 起）。
     * params：`title` / `body` / `id`（固定 id = 更新同一条通知）/ `ongoing` / `progress:{cur,max,indeterminate}`。
     * 老调用（只给 title/body）行为不变：随机 id + 可点掉。
     */
    private fun postNotification(context: Context, params: JSONObject): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "框架通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val nid = params.optInt("id", 0).takeIf { it != 0 }
            ?: (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val ongoing = params.optBoolean("ongoing", false)
        val progress = params.optJSONObject("progress")
        builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(params.optString("title", "业务应用"))
            .setContentText(params.optString("body"))
            // 固定 id 反复 post = 原地更新，不能再响一次/再弹一次
            .setOnlyAlertOnce(ongoing || progress != null)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (progress != null) {
            val max = progress.optInt("max", 100).coerceAtLeast(1)
            val cur = progress.optInt("cur", 0).coerceIn(0, max)
            builder.setProgress(max, cur, progress.optBoolean("indeterminate", false))
        }
        val n = builder.build()
        nm.notify(nid, n)
        return nid
    }

    /** 撤销通知：`{id}`（须与 notify.post 的 id 一致） */
    private fun notifyCancel(context: Context, params: JSONObject) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val nid = params.optInt("id", -1)
        if (nid < 0) {
            Log.w(TAG, "notify.cancel 缺少 id")
            return
        }
        runCatching { nm.cancel(nid) }.onFailure { Log.w(TAG, "notify cancel failed", it) }
    }

    // ---------------- 沙箱应用 ----------------

    private fun appList(context: Context): JSONArray {
        val out = JSONArray()
        runCatching {
            BlackBoxCore.get().getInstalledApplications(0, HotUpdateManager.USER_ID).forEach { ai ->
                val jo = JSONObject().apply {
                    put("packageName", ai.packageName)
                    put("label", runCatching { ai.loadLabel(context.packageManager).toString() }.getOrDefault(ai.packageName))
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    val pi = context.packageManager.getPackageArchiveInfo(ai.sourceDir, 0)
                    if (pi != null) {
                        jo.put("versionCode", pi.versionCode)
                        jo.put("versionName", pi.versionName ?: "")
                    }
                }
                out.put(jo)
            }
        }.onFailure { Log.w(TAG, "app list failed", it) }
        return out
    }

    private fun appLaunch(context: Context, id: String, params: JSONObject) {
        val pkg = params.optString("packageName").ifEmpty { params.optString("pkg") }
        if (pkg.isEmpty()) {
            err(id, FrameApi.ERR_BAD_PARAMS, "packageName required"); return
        }
        val launched = runCatching { BlackBoxCore.get().launchApk(pkg, HotUpdateManager.USER_ID) }.getOrDefault(false)
        if (launched) ok(id, JSONObject().put("ok", true))
        else err(id, FrameApi.ERR_INTERNAL, "launch failed: $pkg")
    }

    private fun appUninstall(context: Context, id: String, params: JSONObject) {
        val pkg = params.optString("packageName").ifEmpty { params.optString("pkg") }
        if (pkg.isEmpty()) {
            err(id, FrameApi.ERR_BAD_PARAMS, "packageName required"); return
        }
        runCatching { BlackBoxCore.get().uninstallPackage(pkg) }
        ok(id, JSONObject().put("ok", true))
    }

    /**
     * 单个包详情（v2）：`{packageName}` → `{installed, label, versionCode, versionName}`。
     * 与 `app.list` 同一份数据源（BlackBox 已装应用 + `PackageArchiveInfo` 读包），只是不必每次拉全表。
     */
    private fun appInfo(context: Context, id: String, params: JSONObject) {
        val pkg = params.optString("packageName").ifEmpty { params.optString("pkg") }
        if (pkg.isEmpty()) {
            err(id, FrameApi.ERR_BAD_PARAMS, "packageName required"); return
        }
        Thread({
            val jo = JSONObject().put("packageName", pkg)
            runCatching {
                val ai = BlackBoxCore.get().getInstalledApplications(0, HotUpdateManager.USER_ID)
                    .firstOrNull { it.packageName == pkg }
                if (ai == null) {
                    jo.put("installed", false)
                } else {
                    jo.put("installed", true)
                    jo.put("label", runCatching { ai.loadLabel(context.packageManager).toString() }
                        .getOrDefault(pkg))
                    @Suppress("DEPRECATION")
                    val pi = context.packageManager.getPackageArchiveInfo(ai.sourceDir, 0)
                    // ⚠️ 别写成 `if (pi != null) { … }` 收尾：这是 runCatching 的 lambda 最后一条表达式，
                    //    Kotlin 会把 if 当表达式要求 else 分支（编译报 "'if' must have both main and 'else'"）
                    pi?.let {
                        jo.put("versionCode", it.versionCode)
                        jo.put("versionName", it.versionName ?: "")
                    }
                }
            }.onFailure { Log.w(TAG, "app.info failed: $pkg", it) }
            ok(id, jo)
        }, "frame-api-appinfo").start()
    }

    /**
     * 只清数据、不装包（v2）：`{packageName}` → `{ok}`。
     * ⚠️ 清单 `mode=clear` 的"清数据安装"**不要**拆成 clearData + install 两步（中间失败会留下
     *    "数据没了、包还是旧的"），那条路径继续用 `app.install` 的 `clear` 参数。
     */
    private fun appClearData(id: String, params: JSONObject) {
        val pkg = params.optString("packageName").ifEmpty { params.optString("pkg") }
        if (pkg.isEmpty()) {
            err(id, FrameApi.ERR_BAD_PARAMS, "packageName required"); return
        }
        Thread({
            val error = BoxRepository.clearData(pkg)
            if (error == null) ok(id, JSONObject().put("ok", true).put("packageName", pkg))
            else err(id, FrameApi.ERR_INTERNAL, error)
        }, "frame-api-cleardata").start()
    }

    // ---------------- 安装（强制指定 APK） ----------------

    /**
     * app.install：强制安装指定 APK。
     * params: { source: "url"|"path", url?, path?, clear?, extra? }
     *
     * `extra`（v3）：业务自带的不透明 JSON，宿主**不理解**，原样回显进回执的 `data.extra`。
     * 于是"装完之后怎么把结果和当初那次请求对上号"完全归业务自己表达，宿主不必认识 seed 之类的概念。
     *
     * 事件：started / progress / done / failed；结果另通过 installStatus 可查（安装自身会被 kill）。
     */
    private fun appInstall(context: Context, id: String, params: JSONObject) {
        if (!installing.compareAndSet(false, true)) {
            err(id, FrameApi.ERR_BUSY, "another install is running"); return
        }
        val source = params.optString("source", "url")
        val url = params.optString("url")
        val path = params.optString("path")
        // clear = 「清除数据安装」（DEBUG 气泡里那个红色按钮）：装包前清掉该包的沙箱数据
        val clear = params.optBoolean("clear", false)
        // 回声板：原样带回，宿主只当字符串搬运（认识的只有 JSON 结构，不认识里面的语义）
        val extraEcho = echoOf(params)
        pushEvent(id, "started", JSONObject().put("source", source).put("clear", clear))
        Thread({
            val result = try {
                when (source) {
                    "path" -> {
                        val f = File(path)
                        if (!f.isFile) HotUpdateManager.UpdateResult.Failed("APK 文件不存在：$path", false)
                        // 业务自己发起的安装（消费端更新）→ 装完必须把业务界面拉回前台，
                        // 否则用户看到"装完直接回桌面"（2026-10-30）
                        else HotUpdateManager.installLocalApk(context, f, clear, relaunchForeground = true)
                    }
                    "content" -> HotUpdateManager.UpdateResult.Failed("暂不支持 content://，请用 url 或 path", false)
                    else -> {
                        if (url.isEmpty()) HotUpdateManager.UpdateResult.Failed("url 为空", false)
                        else HotUpdateManager.forceInstall(
                            context, url, clear, relaunchForeground = true
                        ) { d, t ->
                            pushEvent(id, "progress", JSONObject().put("downloaded", d).put("total", t))
                        }
                    }
                }
            } catch (t: Throwable) {
                HotUpdateManager.UpdateResult.Failed(t.message ?: t.javaClass.simpleName, false)
            } finally {
                installing.set(false)
            }
            when (result) {
                is HotUpdateManager.UpdateResult.Success -> {
                    val data = JSONObject().apply {
                        put("ok", true)
                        put("remote", result.remote)
                        put("version", HotUpdateManager.installedBusinessVersionName().orEmpty())
                        put("pkg", installedBusinessPackage(context))   // 装成了哪个包（业务侧可核对）
                        put("extra", extraEcho)                         // v3：这次请求自带的标记原样带回
                    }
                    saveLastInstall(context, true, data, "")
                    pushEvent(id, "done", data)
                    ok(id, data)
                }
                is HotUpdateManager.UpdateResult.Failed -> {
                    val data = JSONObject().apply {
                        put("ok", false)
                        put("message", result.message)
                        put("extra", extraEcho)
                    }
                    saveLastInstall(context, false, data, result.message)
                    pushEvent(id, "failed", data)
                    err(id, FrameApi.ERR_INSTALL_FAILED, result.message)
                }
            }
        }, "frame-api-install").start()
    }

    private fun lastInstall(context: Context): JSONObject = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_INSTALL, null)
        if (raw.isNullOrEmpty()) JSONObject().put("state", "none") else JSONObject(raw)
    }.getOrDefault(JSONObject().put("state", "none"))

    private fun saveLastInstall(context: Context, ok: Boolean, data: JSONObject, message: String) {
        runCatching {
            val jo = JSONObject().apply {
                put("state", if (ok) "done" else "failed")
                put("ok", ok)
                put("data", data)
                put("message", message)
                put("ts", System.currentTimeMillis())
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_INSTALL, jo.toString()).apply()
        }
    }

    /** 沙箱里当前业务包的包名（只允许装一个包）；拿不到给空串 */
    private fun installedBusinessPackage(context: Context): String = runCatching {
        BlackBoxCore.get().getInstalledApplications(0, HotUpdateManager.USER_ID)
            .firstOrNull()?.packageName
    }.getOrNull().orEmpty()

    /**
     * v3 `extra` 的回声处理：JSON 对象原样搬，字符串尝试解析成对象，其它塞进 `raw`。
     * **只保证"你给什么、回什么"**，宿主不读里面的任何键。
     */
    private fun echoOf(params: JSONObject): JSONObject = when (val e = params.opt("extra")) {
        is JSONObject -> e
        is String -> if (e.isEmpty()) JSONObject()
            else runCatching { JSONObject(e) }.getOrElse { JSONObject().put("raw", e) }
        else -> JSONObject()
    }

    // ---------------- frame.kv（v3）：宿主侧命名空间 KV ----------------

    /** 单值上限 32KB、键数上限 256 —— 它是"小状态"，不是第二数据库 */
    private const val KV_MAX_VALUE_BYTES = 32 * 1024
    private const val KV_MAX_ENTRIES = 256

    /**
     * 命名空间 = 当前业务包名：业务之间互不可见，换业务包自然换命名空间。
     * 宿主**不理解** key/value 语义，只保证这份数据不跟着业务的沙箱数据一起被清掉。
     */
    private fun kvPrefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences("frame_kv_${installedBusinessPackage(context).ifEmpty { "business" }}",
            Context.MODE_PRIVATE)

    private fun frameKv(context: Context, id: String, params: JSONObject) {
        val kv = kvPrefs(context)
        val op = params.optString("op")
        val key = params.optString("key")
        if (op != "list" && op != "clear" && key.isEmpty()) {
            err(id, FrameApi.ERR_BAD_PARAMS, "key 为空"); return
        }
        when (op) {
            "set" -> {
                val value = params.optString("value")
                val bytes = value.toByteArray(Charsets.UTF_8).size
                if (bytes > KV_MAX_VALUE_BYTES) {
                    err(id, FrameApi.ERR_BAD_PARAMS, "value 太大：$bytes > $KV_MAX_VALUE_BYTES"); return
                }
                if (!kv.contains(key) && kv.all.size >= KV_MAX_ENTRIES) {
                    err(id, FrameApi.ERR_BAD_PARAMS, "键太多：$KV_MAX_ENTRIES"); return
                }
                kv.edit().putString(key, value).apply()
                ok(id, JSONObject().put("ok", true).put("bytes", bytes).put("entries", kv.all.size))
            }
            "get" -> ok(id, JSONObject().apply {
                put("ok", true)
                put("exists", kv.contains(key))
                put("value", kv.getString(key, "").orEmpty())
            })
            "del" -> {
                kv.edit().remove(key).apply()
                ok(id, JSONObject().put("ok", true))
            }
            "list" -> ok(id, JSONObject().put("ok", true).put("keys", JSONArray(kv.all.keys.sorted())))
            "clear" -> {
                kv.edit().clear().apply()
                ok(id, JSONObject().put("ok", true))
            }
            else -> err(id, FrameApi.ERR_BAD_PARAMS, "op 必须是 get/set/del/list/clear：$op")
        }
    }

    // ---------------- 清单更新 ----------------

    private fun updateCheck(context: Context, id: String) {
        Thread({
            runCatching {
                when (val r = HotUpdateManager.check(context, HotUpdateManager.manifestUrl(context))) {
                    is HotUpdateManager.CheckResult.Available -> {
                        HotUpdateManager.remember(r.pending)
                        ok(id, JSONObject().apply {
                            put("available", true)
                            put("version", r.pending.version)
                            put("seed", r.pending.seed)
                        })
                    }
                    is HotUpdateManager.CheckResult.UpToDate ->
                        ok(id, JSONObject().put("available", false))
                    is HotUpdateManager.CheckResult.Failed ->
                        err(id, FrameApi.ERR_IO, r.message)
                }
            }.onFailure { err(id, FrameApi.ERR_INTERNAL, it.message ?: it.javaClass.simpleName) }
        }, "frame-api-check").start()
    }

    private fun updateApply(context: Context, id: String) {
        val pending = HotUpdateManager.pendingUpdate
        if (pending == null) {
            err(id, FrameApi.ERR_BAD_PARAMS, "没有待安装的更新，请先 update.check"); return
        }
        if (!installing.compareAndSet(false, true)) {
            err(id, FrameApi.ERR_BUSY, "another install is running"); return
        }
        pushEvent(id, "started", JSONObject().put("source", "manifest"))
        Thread({
            val result = try {
                HotUpdateManager.install(
                    context = context,
                    pending = pending,
                    onProgress = { d, t -> pushEvent(id, "progress", JSONObject().put("downloaded", d).put("total", t)) },
                    relaunchForeground = true
                )
            } catch (t: Throwable) {
                HotUpdateManager.UpdateResult.Failed(t.message ?: t.javaClass.simpleName, false)
            } finally {
                installing.set(false)
            }
            when (result) {
                is HotUpdateManager.UpdateResult.Success -> {
                    FrameBusinessLink.invalidate()
                    val data = JSONObject().apply { put("ok", true); put("remote", result.remote) }
                    saveLastInstall(context, true, data, "")
                    pushEvent(id, "done", data)
                    ok(id, data)
                }
                is HotUpdateManager.UpdateResult.Failed -> {
                    val data = JSONObject().apply { put("ok", false); put("message", result.message) }
                    saveLastInstall(context, false, data, result.message)
                    pushEvent(id, "failed", data)
                    err(id, FrameApi.ERR_INSTALL_FAILED, result.message)
                }
            }
        }, "frame-api-apply").start()
    }

    // ---------------- 推送 ----------------

    private fun ok(id: String, data: JSONObject) {
        FrameBusinessLink.pushApiResult(id, true, data.toString(), null, null)
    }

    private fun err(id: String, code: String, message: String) {
        FrameBusinessLink.pushApiResult(id, false, null, code, message)
    }

    private fun pushEvent(id: String, event: String, data: JSONObject) {
        FrameBusinessLink.pushApiEvent(id, event, data.toString())
    }
}
