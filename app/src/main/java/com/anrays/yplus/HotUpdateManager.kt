package com.anrays.yplus

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 业务 APK（业务应用）热更。
 *
 * 判定口径（用户 2026-10-27 最终定稿）：**只看 update.json 里的 `seed`（版本种子）**。
 *   · 种子与上次装成功时记下的不一致 → 更新；一致 → 什么都不做；
 *   · `apkUrl` 只管"从哪下载"，不参与判定 —— 文件一个字不动、只改 seed 也能测更新；
 *   · 装成功才记种子，失败不记（下次重试）。
 * `versionCode` 不用动，`apkUrl` 也可以长期不变。
 *
 * 为什么盒子内可以不涨版本号：沙箱安装走
 * [BlackBoxCore.installPackageAsUser] → `BPackageManagerService.installPackageAsUserLocked`，
 * 整条路径没有 versionCode 比较（真实 PackageManager 那一套 INSTALL_FAILED_VERSION_DOWNGRADE 不存在），
 * 且 `CreatePackageExecutor` / `CreateUserExecutor` 只删 `data/app/<pkg>` 与 lib 目录，
 * `data/user/0/<pkg>` 下的 prefs / files / databases 原地保留。
 *
 * 🔴 取文件必须走 Gitee **API**，不能走 raw 直链（2026-10-27 实测）：
 *   仓库 Anrays/a06 是私有的，`/raw/<branch>/<path>?access_token=...` 对只读观察者令牌一律
 *   **403**（带 Authorization 头、换 UA 也一样），而 `/api/v5/repos/...` 同一枚令牌 200。
 *   于是：
 *     · 清单：`GET /contents/<path>?ref=<branch>` → base64 解出 JSON（清单很小）；
 *     · 下载：先查**父目录列表**拿 `sha`，再 `GET /git/blobs/<sha>` → **流式**解 base64 写盘
 *       （contents 接口对 >10MB 的文件会截断 content，blob 接口不会；流式解码是为了不在车机上
 *       把上百 MB 的 base64 全塞进内存）；总长度从 blob 响应的 JSON 头 `"size"` 读，给进度条用。
 *   非 Gitee 的绝对地址（自家服务器 / 公开直链）直接普通 GET。
 *
 * 安装时的行为：沙箱安装会 `killPackageAsUser` 杀掉业务进程（悬浮窗/服务短暂中断）。
 * 装完如果 App 在前台就 `launchApk` 拉回界面；在后台则走 [HostBridge.onReviveBroadcast]
 * 静默恢复（只拉进程 + 起保活服务），不会把界面弹到前台。
 */
object HotUpdateManager {

    private const val TAG = "HotUpdate"
    const val USER_ID = 0

    /**
     * 业务包所在的远程仓库（示例占位符，使用前请替换成你自己的仓库）。
     *
     *   · REPO_OWNER / REPO_NAME / REPO_BRANCH：Gitee（或其他同形态 API）仓库坐标；
     *   · MANIFEST_PATH：该仓库里 update.json 的相对路径；
     *   · 公开仓库无需令牌；私有仓库请给 GITEE_TOKEN 填**只读**令牌。
     *
     * 注意：占位符状态下检查会直接失败（拉不到 update.json），属预期行为 ——
     * 想跑通热更新，三处常量必须换成真实值。
     */
    private const val REPO_OWNER = "your-gitee-owner"
    private const val REPO_NAME = "your-business-repo"
    private const val REPO_BRANCH = "master"

    /** 清单在仓库中的相对路径（示例；业务包名不固定，换成你自己的业务包名目录） */
    private const val MANIFEST_PATH = "com.example.business/config/update.json"

    /** 默认的更新说明页：业务更新页的同一份 html（`version.releaseHtml` 指向的也是它） */
    private const val DEFAULT_NOTES_PATH = "com.example.business/resources/web/info/update.html"

    private const val PREFS = "hot_update"
    private const val KEY_MANIFEST_URL = "manifest_url"

    /** 回退路径的通知（业务不支持宿主驱动更新时用） */
    private const val CHANNEL_ID = "hot_update"
    private const val NOTIFY_ID = 1001

    /** 上次**装成功**时的版本种子；它变了就认为有新版本 */
    private const val KEY_SEED = "pushed_seed"
    private const val KEY_LAST_CHECK = "last_auto_check_ms"

    /**
     * 打开 App 时的检查节流：5 秒。
     * 业务应用是 30 秒，但那边是页面内自觉行为；这边主人为了测试经常反复开 App，
     * 30 秒会把"改了 seed 再看"挡掉，5 秒既够去重又不影响手感。
     */
    private const val CHECK_THROTTLE_MS = 5_000L

    /**
     * 打开 App 后延后多久才去查。
     * 原来 15 秒是为了避开"业务正在被拉起"的窗口，但实测体感很差（业务应用是界面一挂载就查）；
     * 现在只查不装，不会杀进程，0.8 秒足够让界面先起来。
     */
    private const val LAUNCH_DELAY_MS = 800L

    /**
     * Gitee 只读访问令牌（access_token）。
     *
     * 🔓 开源版默认**留空** —— 仓库为 public 时不填即可正常拉取清单/APK。
     *    只有从**私有**仓库拉取时才需要在这里填入你自己的**只读**令牌；
     *    🔴 可读写令牌严禁打进 APK。留空时请求 URL 不会追加 `access_token` 参数。
     */
    private const val GITEE_TOKEN = ""   // ← 在此填写你的 Gitee 只读令牌（public 仓库可留空）

    /** 实际使用的令牌（去首尾空白；空串 = 匿名访问公开仓库） */
    private val readOnlyToken: String
        get() = GITEE_TOKEN.trim()

    private val apiRoot: String
        get() = "https://gitee.com/api/v5/repos/$REPO_OWNER/$REPO_NAME"

    /**
     * 默认清单地址（contents 接口；界面里可以改成 `file://` 做本地验证，
     * 也可以填 `/raw/<branch>/<path>` 形式 —— 内部会自动换成 API 通道）。
     */
    val DEFAULT_MANIFEST_URL: String
        get() {
            val base = "$apiRoot/contents/$MANIFEST_PATH?ref=$REPO_BRANCH"
            return if (readOnlyToken.isEmpty()) base else "$base&access_token=$readOnlyToken"
        }

    private val checking = AtomicBoolean(false)

    /**
     * 安装进行中（**进程内**唯一性保护）。
     *
     * 有了 `mode=force` 之后会出现"宿主在自动装 + 用户同时点了「立即更新」"，
     * 两条下载会互删 `incoming.apk`，装出来的包是谁的都不知道 —— 后来者直接失败返回。
     */
    private val installRunning = AtomicBoolean(false)

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 一个 Gitee 文件的三要素。
     * @param path 仓库内相对路径（不带前导斜杠）
     */
    private data class GiteeFile(val owner: String, val repo: String, val ref: String, val path: String) {
        val root: String get() = "https://gitee.com/api/v5/repos/$owner/$repo"

        fun contentsUrl(): String {
            val base = "$root/contents/$path?ref=$ref"
            return if (readOnlyToken.isEmpty()) base else "$base&access_token=$readOnlyToken"
        }

        fun blobUrl(sha: String): String {
            val base = "$root/git/blobs/$sha"
            return if (readOnlyToken.isEmpty()) base else "$base?access_token=$readOnlyToken"
        }
    }

    /**
     * 一次"发现新版本"。
     *
     * 判定口径（用户 2026-10-27 三次定稿）：**只看 update.json 里的 `seed`（版本种子）**。
     * 种子与上次装成功时记下的不一致就算新版本；`apkUrl` 只管"从哪下载"，不参与判定，
     * 于是同一个文件不动、只改种子也能测更新。
     */
    data class PendingUpdate(
        /** update.json 里的 `seed`，唯一判定依据 */
        val seed: String,
        /** update.json 里 apkUrl 的原文（下载入口） */
        val url: String,
        /** 解析后的实际下载地址（Gitee API contents / http(s) / file://） */
        val downloadUrl: String,
        /** update.json 里的 `version`（可空；空时界面用它自己的兜底文案） */
        val version: String,
        /** 更新说明 html 的地址（Gitee API / file:// / http(s)）；null 表示没有 */
        val notesSource: String?,
        /**
         * 首次运行（无业务 app）页面用的 html 地址（对应 update.json 的 `firstRunHtml` 键）。
         * null = 该键缺失，界面回退到上面的更新说明 html。
         */
        val firstRunNotesSource: String?,
        /**
         * update.json 里的 `pushTime`（本次推送时间，可空；空时框架/业务更新页都不显示）。
         * 与业务应用前端 updateTimeText 约定的键名一致。
         */
        val pushTime: String,
        /**
         * `mode` 里的 force 标记：**检测到更新就直接下载安装**，不等用户点「立即更新」。
         * 默认 false（普通更新：只提示）。见 [parseMode]。
         */
        val force: Boolean = false,
        /**
         * `mode` 里的 clear 标记：装包前清掉业务数据（**不保留原数据**）。
         * 默认 false（保留数据）。见 [parseMode]。
         */
        val clearData: Boolean = false,
        /**
         * `mode` 里的 auto 标记：**按包名自动决定清不清数据**（用户 2026-10-30）——
         * 同包名 = 覆盖升级，**保留数据**；换包名 = 换了一个业务，**直接清数据更新**。
         * 与 [clearData] 同时出现时以 [clearData] 为准（显式清除优先）。
         * 见 [parseMode]、[Mode]。
         */
        val autoClear: Boolean = false
    )

    /**
     * `mode` 解析结果（一个键管三个开关，**不区分大小写**）。
     *  · [force]：`1` = 检测到更新直接下载安装；`0`/不写 = 只提示
     *  · [clear]：显式清除 —— 装包前清掉业务数据（不可恢复）
     *  · [auto]：自动判断 —— **同包名不清（保留数据/后台），换包名直接清**
     * 两者同时出现时 [clear] 优先（显式更明确）。
     */
    data class Mode(
        val force: Boolean = false,
        val clear: Boolean = false,
        val auto: Boolean = false
    )

    /**
     * 解析 update.json 的 **`mode`**（**不区分大小写**，出现即生效）：
     *   · `1` / `0`  → 强制自动装 / 只提示
     *   · `clear`    → 装包前清掉业务数据（`0clear` / `CLEAR` / `1clear` 都算）
     *   · `auto`     → 按包名自动判断：**同包名不清（保留后台数据）、不同包名直接清数据更新**（2026-10-30）
     *
     * 例：`"0"` 标准+保留｜`"0clear"` 标准+清除｜`"1"` 强制+保留｜`"1clear"` 强制+清除
     *     ｜`"1auto"` 强制+按包名自动｜`"auto"` 标准+按包名自动
     *
     * ⚠️ 能不能清**要等下载完 APK、读到新包包名**才知道，所以 auto 只能在安装阶段落地（见 [install]）。
     */
    fun parseMode(manifest: JSONObject): Mode {
        val raw = manifest.optString("mode", "")
        val mode = raw.trim().lowercase()
        val force = mode.contains("1")
        val clear = mode.contains("clear")
        // clear 存在时 auto 不再单独生效（显式清除优先，避免两个键互相打架）
        val auto = mode.contains("auto") && !clear
        Log.i(TAG, "mode=$raw → force=$force clear=$clear auto=$auto")
        return Mode(force = force, clear = clear, auto = auto)
    }

    sealed class CheckResult {
        /** 地址没变 → 不更新 */
        object UpToDate : CheckResult()

        data class Available(val pending: PendingUpdate) : CheckResult()

        data class Failed(val message: String) : CheckResult()
    }

    sealed class UpdateResult {
        data class Success(
            val remote: String,
            val relaunched: Boolean,
            val inForeground: Boolean
        ) : UpdateResult()

        data class Failed(val message: String, val rolledBack: Boolean) : UpdateResult()
    }

    /** 最近一次检查到的可用更新（更新窗口从这里取；装成功后清掉） */
    @Volatile
    var pendingUpdate: PendingUpdate? = null
        private set(value) {
            field = value
            // 后台界面的「立即更新」高亮按钮据此显隐（2026-10-30）：检查是**延迟**跑的
            // （打开 App 后 15 秒），界面可能早就显示了，所以必须回调而不是只在 onCreate 读一次。
            onPendingChanged?.let { cb -> mainHandler.post { runCatching { cb() } } }
        }

    /**
     * pending 变化回调（主线程）：宿主后台界面用它刷新「立即更新」按钮。
     * 只保留一个监听者（宿主只有一个 MainActivity），onDestroy 记得置 null。
     */
    @Volatile
    var onPendingChanged: (() -> Unit)? = null

    /** 自动检查（打开 App 时）查出更新后交给更新窗口 */
    fun remember(pending: PendingUpdate) {
        pendingUpdate = pending
    }

    /** 当前生效的清单地址（界面里可改；没改过用内置默认） */
    fun manifestUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST_URL, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_MANIFEST_URL

    /**
     * 只检查、不下载（**阻塞**，子线程调用）。
     *
     * @param manifestUrl update.json 地址（Gitee API / Gitee raw / 普通 http(s) / file://）
     */
    fun check(context: Context, manifestUrl: String): CheckResult {
        return try {
            val manifestFile = parseGitee(manifestUrl)
            val manifest = JSONObject(readText(manifestUrl))
            val seed = manifest.optString("seed", "").trim()
            if (seed.isEmpty()) throw IOException("update.json 里没有 seed（版本种子）")
            val rawUrl = manifest.optString("apkUrl", "").trim()
            if (rawUrl.isEmpty()) throw IOException("update.json 里没有 apkUrl")

            // 判定就这一句：种子与上次装成功时记下的不一致 → 有新版本
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_SEED, null) == seed) return CheckResult.UpToDate

            val notes = manifest.optString("releaseHtml", "").trim()
                .ifEmpty { DEFAULT_NOTES_PATH }
            val firstRunHtml = manifest.optString("firstRunHtml", "").trim()
            val mode = parseMode(manifest)
            CheckResult.Available(
                PendingUpdate(
                    seed = seed,
                    url = rawUrl,
                    downloadUrl = resolveApkUrl(rawUrl, manifestFile),
                    version = manifest.optString("version", "").trim(),
                    notesSource = resolveRepoUrl(notes, manifestFile),
                    firstRunNotesSource = resolveRepoUrl(firstRunHtml, manifestFile),
                    pushTime = manifest.optString("pushTime", "").trim(),
                    force = mode.force,
                    clearData = mode.clear,
                    autoClear = mode.auto
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "check failed", t)
            CheckResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 直接读**内置 Gitee 清单**构造一个 PendingUpdate（不比对 seed、不判断 UpToDate）。
     * 用于「无业务 app 恢复模式」：首次运行 / 出 bug 丢包时，先拿它渲染更新说明，再自动装线上最新。
     */
    fun onlinePending(context: Context): PendingUpdate? {
        return try {
        val manifestFile = parseGitee(DEFAULT_MANIFEST_URL)
        val manifest = JSONObject(readText(DEFAULT_MANIFEST_URL))
        val seed = manifest.optString("seed", "").trim().ifEmpty { "online" }
        val rawUrl = manifest.optString("apkUrl", "").trim()
        if (rawUrl.isEmpty()) return null
        val notes = manifest.optString("releaseHtml", "").trim().ifEmpty { DEFAULT_NOTES_PATH }
        val firstRunHtml = manifest.optString("firstRunHtml", "").trim()
        val mode = parseMode(manifest)
        PendingUpdate(
            seed = seed,
            url = rawUrl,
            downloadUrl = resolveApkUrl(rawUrl, manifestFile),
            version = manifest.optString("version", "").trim(),
            notesSource = resolveRepoUrl(notes, manifestFile),
            firstRunNotesSource = resolveRepoUrl(firstRunHtml, manifestFile),
            pushTime = manifest.optString("pushTime", "").trim(),
            force = mode.force,
            clearData = mode.clear,
            autoClear = mode.auto
        )
    } catch (t: Throwable) {
        Log.e(TAG, "onlinePending failed", t)
        null
    }
}

    /**
     * 下载并覆盖安装（**阻塞**，子线程调用；界面上的「立即更新」走这里）。
     *
     * @param onProgress 下载进度（已下载字节 / 总字节）
     * @param relaunchForeground 装完**无条件**把业务界面重新拉到前台。
     *        用户在业务自己的更新页点了「立即更新」时必须为 true —— 业务跑在沙箱子进程
     *        （宿主:pN）里，宿主的 isForeground 判定不到它，否则会走静默复活，
     *        用户看到的就是"点更新后 App 直接消失"（2026-10-28 实测）。
     */
    fun install(
        context: Context,
        pending: PendingUpdate,
        relaunchForeground: Boolean = false,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): UpdateResult {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val dir = File(context.cacheDir, "hot_update").apply { mkdirs() }
            val apk = File(dir, "incoming.apk")
            if (apk.exists() && !apk.delete()) {
                return UpdateResult.Failed("无法清理旧的下载文件", false)
            }

            download(pending, apk, onProgress)

            // 完整性只认"能当 APK 解出来"（下面 verifyBusinessApk），不额外算 sha256（用户明确不要求）
            if (apk.length() <= 0L) {
                apk.delete()
                return UpdateResult.Failed("下载到的文件是空的", false)
            }

            // 光有字节数还不够：仓库里换错文件（或下到错误页）会以"大小对得上"的样子装进去。
            // 这里再确认一次它确实是业务包本身，包名不对一律不装。
            val packageError = verifyBusinessApk(context, apk)
            if (packageError != null) {
                apk.delete()
                return UpdateResult.Failed(packageError, false)
            }

            // 覆盖安装前先备份当前沙箱内的 APK，安装失败时用于回滚
            val backup = backupCurrent(context)

            // 清不清数据（也放在下载 + 校验 + 备份**之后**、装包**之前** —— 下到坏包不该白白清数据；
            // 装失败仍能回滚 APK，但数据回不来，文案会说明）：
            //   · clear = 显式清除，无条件清；
            //   · auto  = **同包名保留（覆盖升级），换包名才清**（用户 2026-10-30）。
            val targetPkg = archivePackageName(context, apk)
            val currentPkg = BoxRepository.singleInstalledPackage()
            val packageChanged = targetPkg != null && currentPkg != null && currentPkg != targetPkg
            if (packageChanged) {
                Log.i(TAG, "换包名：$currentPkg → $targetPkg（auto 模式下清数据）")
            }
            val shouldClear = pending.clearData || (pending.autoClear && packageChanged)
            if (shouldClear) {
                // 换包名时旧包随后会被 uninstallAllExcept 卸掉，这里显式清一次更明确（不依赖卸载副作用）；
                // 同包名则清目标包自己的数据。
                val clearTarget = if (packageChanged) currentPkg else targetPkg
                val clearError = clearTarget?.let { BoxRepository.clearData(it) }
                Log.i(
                    TAG,
                    "清除业务数据 pkg=$clearTarget err=$clearError " +
                        "(clear=${pending.clearData} auto=${pending.autoClear} 换包名=$packageChanged)"
                )
            }

            // 只保留一个包：装之前清掉与目标包名不同的旧包（更新成别的包名 = 换新业务）
            BoxRepository.uninstallAllExcept(targetPkg)

            val installError = install(apk)
            if (installError != null) {
                val rolledBack = if (backup != null) install(backup) == null else false
                apk.delete()
                Log.w(TAG, "install failed: $installError, rolledBack=$rolledBack")
                return UpdateResult.Failed("安装失败：$installError", rolledBack)
            }

            // 装成功才记种子：装失败下次还会重试
            prefs.edit().putString(KEY_SEED, pending.seed).apply()
            // 业务包已换成新 APK：能力标记可能变化，清掉探测缓存
            FrameBusinessLink.invalidate()

            val revived = reviveAfterInstall(context, relaunchForeground)
            apk.delete()
            pendingUpdate = null
            UpdateNoticeOverlay.dismiss()
            Log.i(TAG, "updated to seed=${pending.seed}, revived=$revived")
            return UpdateResult.Success(pending.version, revived.ok, revived.foreground)
        } catch (t: Throwable) {
            Log.e(TAG, "install pending failed", t)
            return UpdateResult.Failed(t.message ?: t.javaClass.simpleName, false)
        }
    }

    /**
     * 「业务点「立即更新」」与「清单 mode=force 自动装」**共用**的那一套动作：
     * 推 installing → 下载（进度回推业务更新页）→ 覆盖安装 → 成功推 done / 失败推 failed → 复活。
     *
     * 两条入口只走这一个实现（不各写一份，免得行为分叉）。**阻塞**，子线程调用。
     *
     * @param relaunchForeground 装完**无条件**把业务界面拉回前台：业务跑在沙箱子进程
     *        （宿主:pN），宿主的 isForeground 判定不到它，不显式拉会表现成"App 直接消失"。
     */
    fun installWithHostFeedback(
        context: Context,
        pending: PendingUpdate,
        relaunchForeground: Boolean = true
    ): UpdateResult {
        // force 自动装与用户「立即更新」可能同时发生：两条下载会互删 incoming.apk，后来者直接拒
        if (!installRunning.compareAndSet(false, true)) {
            Log.w(TAG, "install already running, skip seed=${pending.seed}")
            return UpdateResult.Failed("已有更新正在进行", false)
        }
        return try {
            // 下载阶段给业务页一个明确的"开始"；安装前必须已经推到「正在安装」——
            // 覆盖安装会 killPackageAsUser 杀掉业务进程，之后再推就晚了。
            FrameBusinessLink.pushInstalling()
            val result = install(
                context = context,
                pending = pending,
                relaunchForeground = relaunchForeground,
                onProgress = { downloaded, total ->
                    runCatching { FrameBusinessLink.pushProgress(downloaded, total) }
                }
            )
            when (result) {
                is UpdateResult.Success -> {
                    // 业务进程刚被重启：startService 会重新拉起并送达；多试几次防它还没初始化完
                    repeat(3) { attempt ->
                        if (attempt > 0) Thread.sleep(1000L * attempt)
                        FrameBusinessLink.pushDone()
                    }
                    Log.i(TAG, "install ok: ${result.remote}")
                }
                is UpdateResult.Failed -> {
                    FrameBusinessLink.pushFailed(result.message)
                    Log.w(TAG, "install failed: ${result.message}")
                }
            }
            result
        } finally {
            installRunning.set(false)
        }
    }

    /**
     * 无业务 app（首次运行 / 出 bug 丢包）：打开更新页并**直接进「更新中」状态自动安装**
     * —— 全屏循环动画 + 底部进度条 + 百分比，不弹卡片、不等用户确认（用户 2026-10-29）。
     * 页面里装失败会自己把按钮变成「重试」，不会退回空桌面。
     */
    private fun openRecoverWindow(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(context, UpdateActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(UpdateActivity.EXTRA_RECOVER_MODE, true)
                    .putExtra(UpdateActivity.EXTRA_AUTO_INSTALL, true)
            )
            Log.i(TAG, "opened recover window (no business app)")
        }.onFailure { Log.w(TAG, "open recover window failed", it) }
    }

    /**
     * 「更新应用」→「下载链接」：用户直接给一个 APK 地址，**跳过清单与版本比较**强制装进沙箱。
     *
     * 与 [install] 的区别（用户 2026-10-28 要求"可以输入 apk 地址强制更新 apk"）：
     *  · 不读 update.json、不比对 seed —— 地址能下到 APK 就装，重复装同一版也照装；
     *  · 允许装**任意包**（后台本身就是沙箱管理器），只有业务包才走备份/回滚/复活那条链路。
     *
     * Gitee 的 raw / API contents 地址与外站 http(s) 直链都可用（内部走同一条下载通道）。
     * **阻塞**，子线程调用。
     */
    fun forceInstall(
        context: Context,
        apkUrl: String,
        clear: Boolean = false,
        relaunchForeground: Boolean = false,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): UpdateResult {
        val url = apkUrl.trim()
        if (url.isEmpty()) return UpdateResult.Failed("APK 地址为空", false)
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            return UpdateResult.Failed("不支持的 APK 地址（只认 http/https/file://）", false)
        }
        val dir = File(context.cacheDir, "hot_update").apply { mkdirs() }
        val apk = File(dir, "force.apk")
        if (apk.exists() && !apk.delete()) {
            return UpdateResult.Failed("无法清理旧的下载文件", false)
        }
        return try {
            // 复用清单更新那条下载通道（Gitee blob / 普通 http(s) / file:// 都支持）
            val pending = PendingUpdate(
                seed = "force",
                url = url,
                downloadUrl = url,
                version = "",
                notesSource = null,
                firstRunNotesSource = null,
                pushTime = ""
            )
            download(pending, apk, onProgress)
            installLocalApk(context, apk, clear, relaunchForeground)
        } catch (t: Throwable) {
            Log.e(TAG, "force install failed", t)
            UpdateResult.Failed(t.message ?: t.javaClass.simpleName, false)
        } finally {
            apk.delete()
        }
    }

    /**
     * 「更新应用」→「同步线上版本」：直接从**内置 Gitee 清单**取 `apkUrl` 并强制安装。
     *
     * 与 [check] / [install] 的区别（用户 2026-10-28："点击后立刻更新为 gitee 上的 apk"）：
     *  · **不比对 seed** —— 线上是什么就装什么（同一版也照装），不做"已是最新"判断；
     *  · 固定读 [DEFAULT_MANIFEST_URL]，**不使用**界面里改过的清单地址（"线上"= 官方仓库）；
     *  · 走 [installLocalApk]，因此同样是"只保留一个包"的替换式安装。
     * **阻塞**，子线程调用。
     */
    fun syncOnline(
        context: Context,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): UpdateResult {
        val dir = File(context.cacheDir, "hot_update").apply { mkdirs() }
        val apk = File(dir, "online.apk")
        return try {
            val manifestFile = parseGitee(DEFAULT_MANIFEST_URL)
            val manifest = JSONObject(readText(DEFAULT_MANIFEST_URL))
            val rawUrl = manifest.optString("apkUrl", "").trim()
            if (rawUrl.isEmpty()) return UpdateResult.Failed("线上清单里没有 apkUrl", false)
            val pending = PendingUpdate(
                seed = manifest.optString("seed", "").trim().ifEmpty { "online" },
                url = rawUrl,
                downloadUrl = resolveApkUrl(rawUrl, manifestFile),
                version = manifest.optString("version", "").trim(),
                notesSource = null,
                firstRunNotesSource = null,
                pushTime = manifest.optString("pushTime", "").trim()
            )
            if (apk.exists() && !apk.delete()) {
                return UpdateResult.Failed("无法清理旧的下载文件", false)
            }
            download(pending, apk, onProgress)
            installLocalApk(context, apk)
        } catch (t: Throwable) {
            Log.e(TAG, "sync online failed", t)
            UpdateResult.Failed(t.message ?: t.javaClass.simpleName, false)
        } finally {
            runCatching { apk.delete() }
        }
    }

    /**
     * 强制安装一个**本地 APK 文件**。
     *
     * 「更新应用」的三条来源共用这里：下载链接（[forceInstall] 下完调本方法）、
     * 扫描本地 APK、系统文件选择器（SAF）。因此这里不删源文件，调用方自己管生命周期。
     *
     * 🔴 沙箱只保留一个包（用户 2026-10-28）：不管装的是哪个包名，统一当作"换掉当前业务"，
     * 走「备份当前 → 清掉其它包 → 覆盖安装 → 失败回滚 → 静默复活」同一条链路。
     * 因此这里不区分"业务包 / 其它包"—— 装什么都会成为唯一的那个业务包。
     * **阻塞**，子线程调用。
     */
    fun installLocalApk(
        context: Context,
        source: File,
        clear: Boolean = false,
        relaunchForeground: Boolean = false
    ): UpdateResult {
        if (!source.isFile) return UpdateResult.Failed("APK 文件不存在：${source.name}", false)
        if (source.length() <= 0L) return UpdateResult.Failed("APK 文件是空的：${source.name}", false)
        val dir = File(context.cacheDir, "hot_update").apply { mkdirs() }
        // 扫描到的 / SAF 选中的文件先落到缓存再装：
        // 外部存储与 content Uri 在安装器读文件时未必还有权限，缓存目录始终可读。
        val staged = try {
            if (source.parentFile?.canonicalPath == dir.canonicalPath) {
                source
            } else {
                File(dir, "stage.apk").also { target ->
                    source.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "stage apk failed", t)
            return UpdateResult.Failed("读取 APK 失败：${t.message ?: t.javaClass.simpleName}", false)
        }
        try {
            val archive = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(staged.absolutePath, 0)
            }.getOrNull() ?: return UpdateResult.Failed("不是有效的 APK：${source.name}", false)
            val pkg = archive.packageName
            if (pkg.isNullOrBlank()) return UpdateResult.Failed("APK 里没有包名", false)
            val remote = versionLabel(archive).ifEmpty { pkg }
            Log.i(TAG, "install local apk: $pkg $remote ← ${source.absolutePath}")

            // clear（DEBUG「清除数据安装」/ 清单 mode 含 clear）：装之前清掉该包的沙箱数据。
            // 放在备份之后、装包之前 —— 装失败仍能回滚 APK（数据回不来，调用方文案会说明）。
            if (clear) {
                val clearError = BoxRepository.clearData(pkg)
                Log.i(TAG, "clear data before install: $pkg err=$clearError")
            }

            // 统一走"换掉当前业务"：备份 → 清掉其它包 → 覆盖安装 → 失败回滚 → 静默复活
            val backup = backupCurrent(context)
            BoxRepository.uninstallAllExcept(pkg)
            val error = install(staged)
            if (error != null) {
                val rolledBack = if (backup != null) install(backup) == null else false
                Log.w(TAG, "install business apk failed: $error, rolledBack=$rolledBack")
                return UpdateResult.Failed("安装失败：$error", rolledBack)
            }
            // 能力标记可能随包变化；「有新版本」提示也一并收掉（用户已经换到指定版本）
            FrameBusinessLink.invalidate()
            pendingUpdate = null
            UpdateNoticeOverlay.dismiss()
            // 🔴 业务点「立即更新」走的是 app.install → 这里：业务进程在沙箱子进程（宿主:pN）里，
            // 宿主的 isForeground 判不到它，原来只做"静默复活"（onReviveBroadcast），用户看到的
            // 就是"装完直接回桌面、不重启"（2026-10-30 实测）。relaunchForeground=true 时无条件拉界面。
            val revived = reviveAfterInstall(context, relaunchForeground)
            return UpdateResult.Success(remote, revived.ok, revived.foreground)
        } finally {
            if (staged != source) runCatching { staged.delete() }
        }
    }

    /**
     * **用户主动打开 App 时**检查热更（对齐业务应用的做法：前端 `ui/updateWatch.js` 在界面挂载时
     * 调一次 `android('checkForUpdate')`，原生侧只有 30 秒节流 + in-flight 去重，不挂开机广播、
     * 不后台轮询）。
     *
     * 调用点：只有 [MainActivity.onCreate]（点图标进 App / 悬浮按钮切回宿主）。开机广播与
     * 后台进程启动**不再**触发 —— 车主没上车时不该悄悄装包把业务进程杀掉。
     *
     * 查出新版本后**不自动装**，只弹顶部提示条（[UpdateNoticeOverlay]），
     * 用户点开更新窗口再自己按「立即更新」。
     */
    fun checkOnLaunch(context: Context) {
        val app = context.applicationContext
        // 延后 15 秒：刚打开这几秒业务正在被拉起，此刻弹提示条容易和启动动画打架
        mainHandler.postDelayed({ checkOnLaunchNow(app) }, LAUNCH_DELAY_MS)
    }

    private fun checkOnLaunchNow(context: Context) {
        // 🔴 宿主有多个进程都会跑 CarBoxApp.onCreate（主进程 + :black + 沙箱业务进程），
        //    checking 这个 AtomicBoolean 只在单进程内有效：两个进程同时检查会互删下载缓存。只让主进程干。
        if (currentProcessName() != context.packageName) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_THROTTLE_MS) return
        if (!checking.compareAndSet(false, true)) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        Thread({
            try {
                if (installedApkPath() == null) {
                    // 无业务 app（首次运行 / 出 bug 丢包）→ 进更新页的「更新中」状态并自动安装
                    Log.i(TAG, "业务包未安装 → 进入更新状态并强制安装")
                    mainHandler.post { openRecoverWindow(context) }
                    return@Thread
                }
                // 🔴 消费端（业务 APK 声明 `frame.consumer`）→ **连清单都不查**：
                //    更新完全归业务自己（它读自己的 config/manifest.json），宿主再抓一次 update.json
                //    只会白花流量、并在日志里留下 `check on launch: available seed=…` 这种误导线索
                //    （用户 2026-10-30：清单检查也要跳过）。判定带 30s TTL，开销可忽略。
                if (BusinessManifest.isConsumer(context)) {
                    Log.i(TAG, "check on launch: 业务声明 frame.consumer → 宿主不查清单（更新归业务自己）")
                    return@Thread
                }
                val manifest = manifestUrl(context)
                when (val result = check(context, manifest)) {
                    is CheckResult.Available -> {
                        pendingUpdate = result.pending
                        Log.i(TAG, "check on launch: available seed=${result.pending.seed} ${result.pending.url}")
                        // 业务支持宿主驱动更新 → 提示/进度全交给它自己的更新页（用户 2026-10-28）；
                        // 老业务包 → 回退宿主自己的悬浮提示条 / 直接进更新页。
                        val handedOver = deliverToBusiness(context, result.pending)
                        // mode=force：不等用户点「立即更新」，直接下载 + 覆盖安装
                        // （进度照样回推业务更新页，业务页显示"正在下载/安装"而不是"待更新"）
                        // 消费端的 `mode=force` 也不由宿主执行（mode 归业务自己，2026-10-30 拍板）
                        if (result.pending.force && !BusinessManifest.isConsumer(context)) {
                            Log.i(TAG, "mode=force → 自动安装 seed=${result.pending.seed} clear=${result.pending.clearData}")
                            installWithHostFeedback(context, result.pending)
                            return@Thread
                        }
                        mainHandler.post {
                            if (handedOver) return@post
                            // 只弹右上角 Notice，**不主动跳更新页**（用户 2026-10-30："用户点击 notice 就行了"）。
                            // 点击落点：Notice → UpdateActivity；后台界面另有「立即更新」高亮按钮。
                            UpdateNoticeOverlay.show(context, result.pending)
                            // 宿主不在前台时 Notice 会被"退到后台"自检移除 → 用系统通知兜底，
                            // 点通知才进更新页（仍然不是自动跳）。
                            mainHandler.postDelayed({
                                if (!isForeground(context)) notifyUpdate(context, result.pending)
                            }, 2500L)
                        }
                    }
                    is CheckResult.UpToDate ->
                        Log.i(TAG, "check on launch: up to date（seed 没变）")
                    is CheckResult.Failed ->
                        Log.w(TAG, "check on launch failed: ${result.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "check on launch failed", t)
            } finally {
                checking.set(false)
            }
        }, "hot-update-launch").start()
    }

    /**
     * 回退路径的入口：发一条「发现新版本」通知，点击进宿主更新页。
     * 宿主主进程在业务界面里是后台进程，既弹不出可靠的前台 UI，也不能直接 startActivity。
     */
    private fun notifyUpdate(context: Context, pending: PendingUpdate) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "更新提醒", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val intent = android.content.Intent(context, UpdateActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, NOTIFY_ID, intent, flags)
            val title = if (pending.version.isNotEmpty()) "发现新版本 v${pending.version}" else "发现新版本"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_box)
                .setContentTitle(title)
                .setContentText("点击查看更新内容并安装")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
            Log.i(TAG, "已发更新通知：$title")
        }.onFailure { Log.w(TAG, "notify update failed", it) }
    }

    /** 直接打开更新页面（主线程调用；宿主在前台时用） */
    private fun openUpdateWindow(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(context, UpdateActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open update window failed", it) }
    }

    /**
     * 更新说明 html 落到本地缓存，返回可给 WebView 直接加载的 `file://` 地址。
     *
     * 为什么不用 http(s) 直接加载：仓库是私有的，WebView 发不出 `access_token`
     * （raw 直链实测 403），只能先由我们走 API 取回文本再落盘。
     *
     * @return null 表示没取到（界面退回内置兜底文案）
     */
    fun notesHtmlFile(context: Context, pending: PendingUpdate): String? {
        val source = pending.notesSource ?: return null
        return try {
            val html = readText(source)
            val dir = File(context.filesDir, "hot_update").apply { mkdirs() }
            val file = File(dir, "notes_${Integer.toHexString(pending.url.hashCode())}.html")
            file.writeText(html)
            // 顺手清掉上一版留下的说明文件，别把 files 目录越堆越大
            runCatching {
                dir.listFiles { f -> f.name.startsWith("notes_") && f.name != file.name }
                    ?.forEach { it.delete() }
            }
            "file://${file.absolutePath}"
        } catch (t: Throwable) {
            Log.w(TAG, "notes html fetch failed", t)
            null
        }
    }

    /** 沙箱内业务 APK 的真实路径（能力探测 / 版本读取用） */
    fun businessApkPath(): String? = installedApkPath()

    /**
     * 更新说明 html 原文（转交给业务更新页用）。
     * **阻塞**：走 Gitee API 取 base64 解文本，必须在子线程调用。
     */
    fun notesHtmlText(pending: PendingUpdate): String? {
        val source = pending.notesSource ?: return null
        return runCatching { readText(source) }
            .onFailure { Log.w(TAG, "notes text fetch failed", it) }
            .getOrNull()
    }

    /**
     * 把「有新版本」交给业务 App 自己的更新提示机制（Notice 长条 + 侧栏更新项 + 更新页进度）。
     * 阻塞（要先把说明 html 取回来），子线程调用。
     *
     * @return false = 业务不支持宿主驱动更新（老版本业务包 / 业务未装），调用方回退到
     *         宿主自己的悬浮提示条或更新页面。
     */
    fun deliverToBusiness(context: Context, pending: PendingUpdate): Boolean {
        // 🔴 消费端（业务 APK 声明 `frame.consumer`）→ **更新归业务自己**（它读自己的 manifest.json）：
        // 宿主不推 available、不弹提示条、不显示「立即更新」、也不执行 mode=force（mode 也归业务）。
        // 顺手推一条 clear 把业务侧可能残留的宿主推送状态清掉 —— 不清的话业务仍会走 UpdateFramePage
        // 显示"宿主推送的版本"，与它自己的更新系统打架（用户 2026-10-30）。
        if (BusinessManifest.isConsumer(context)) {
            Log.i(TAG, "business 声明 frame.consumer → 更新归业务自己，宿主不推 seed=${pending.seed}")
            runCatching { FrameBusinessLink.clear() }
            pendingUpdate = null
            return true // 已处理：调用方跳过宿主提示条 / 更新页
        }
        // 非消费端 → **宿主自己承接**：前台弹右上角 Notice 提示更新，后台界面显示「立即更新」，
        // 不再把 available 推给业务（用户 2026-10-30：业务自己取自己的清单，宿主不越俎代庖）。
        // ⚠️ 悬浮切换按钮（BoxSwitchOverlay）与更新无关，只是共用同一条 `frame.consumer` 声明控制显隐。
        Log.i(TAG, "非消费端 → 宿主用自己的 Notice 提示更新 seed=${pending.seed}")
        pendingUpdate = pending
        return false
    }

    /** 业务侧点了「放弃更新」：清掉内存 pending（seed 不动，下次开 App 仍会提示） */
    fun dismissPending() {
        pendingUpdate = null
        UpdateNoticeOverlay.dismiss()
    }

    /**
     * APK 的可读版本号。
     *
     * 部分业务包会把 versionName 置空（靠 versionCode 逐位换算展示），直接读 versionName 会得到空串，
     * 界面上就变成"更新完成：com.example.business"。
     * 这里对齐同一口径：有 versionName 用它，没有就逐位点分。
     */
    @Suppress("DEPRECATION")
    fun versionLabel(info: android.content.pm.PackageInfo?): String {
        info ?: return ""
        info.versionName?.takeIf { it.isNotBlank() }?.let { return it }
        val code = info.versionCode
        return if (code <= 0) "" else code.toString().toList().joinToString(".")
    }

    /** 沙箱里当前业务包的 versionName（更新窗口显示"当前 x → 新 y"用） */
    fun installedBusinessVersionName(): String? {
        val path = installedApkPath()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            CarBoxApp.instance.packageManager.getPackageArchiveInfo(path, 0)
                ?.versionName
        }.getOrNull()
    }

    // ---------------- 清单 ----------------

    /**
     * `apkUrl` 允许两种写法：
     *   · 完整地址（Gitee 直链 / contents 地址 / 任意 http(s)）—— 原样用；
     *   · 仓库内相对路径（推荐，如 `com.example.business/resources/app/business-latest.apk`）—— 按清单所在仓库补全。
     * 用相对路径的好处：换 APK 时只替换那一个文件，update.json 一个字都不用改。
     */
    private fun resolveApkUrl(apkUrl: String, manifestFile: GiteeFile?): String {
        if (apkUrl.startsWith("http://") || apkUrl.startsWith("https://") ||
            apkUrl.startsWith("file://")
        ) {
            return apkUrl
        }
        val base = manifestFile
            ?: throw IOException("apkUrl 是相对路径，但清单不在 Gitee 仓库里")
        // 允许往地址后面加 ?121234 这种"假参数"来强制触发更新（方便测试）：
        // 路径部分拿去拼 API，额外参数原样带到后面，不影响下载。
        val trimmed = apkUrl.trimStart('/')
        val path = trimmed.substringBefore('?')
        val extra = trimmed.substringAfter('?', "")
        val url = "${base.root}/contents/$path?ref=${base.ref}"
        return if (extra.isEmpty()) url else "$url&$extra"
    }

    /**
     * 与 [resolveApkUrl] 同一套规则：完整地址原样、仓库内相对路径按清单所在仓库补成 API 地址。
     * 用于更新说明 html（`releaseHtml`）。
     */
    private fun resolveRepoUrl(path: String, manifestFile: GiteeFile?): String? {
        if (path.isEmpty()) return null
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file://")) {
            return path
        }
        val base = manifestFile ?: return null
        return "${base.root}/contents/${path.trimStart('/')}?ref=${base.ref}"
    }

    private fun readText(url: String): String {
        val gitee = parseGitee(url)
        if (gitee != null) {
            val json = readGiteeJson(gitee, "清单")
            val content = json.optString("content", "")
            if (content.isEmpty()) throw IOException("远端清单为空：${gitee.path}")
            return String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        }
        if (url.startsWith("file://")) {
            return File(URI(url)).readText()
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IOException("不支持的清单地址：$url")
        }
        return readPlainText(url, "清单")
    }

    /**
     * 取 Gitee contents 接口的 JSON。
     *
     * 文件不存在时接口不一定给 404：对一个还没上传的深层路径，实测会返回 `[...]`（目录列表），
     * 直接丢给 [JSONObject] 只会得到 "Value [] of type JSONArray cannot be converted"，
     * 界面上看不出到底缺了什么 —— 这里翻译成能照着做的提示。
     */
    private fun readGiteeJson(file: GiteeFile, what: String): JSONObject {
        val body = readPlainText(file.contentsUrl(), what)
        if (body.trimStart().startsWith("[")) {
            throw IOException("远端${what}不存在：${file.path}（还没上传？）")
        }
        return runCatching { JSONObject(body) }.getOrElse {
            throw IOException("远端${what}格式不对：${file.path}")
        }
    }

    /** 普通 GET 取整段文本（Gitee API 的 JSON / 任意文本） */
    private fun readPlainText(url: String, what: String): String {
        val conn = openHttp(url, readTimeout = 20000)
        try {
            val code = conn.responseCode
            if (code == 404) throw IOException("远端${what}不存在（HTTP 404）")
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 下载 ----------------

    /**
     * 下载到本地临时文件。全程只做"能不能装"的校验，不比对指纹。
     *
     * Gitee 源仍然要按 blob 取（contents 接口对 >10MB 的文件会截断 content），
     * 下载前查一次**父目录列表**拿 `sha` + `size`（几百字节，顺手给进度条提供总长度）。
     */
    private fun download(
        pending: PendingUpdate,
        target: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val url = pending.downloadUrl
        val gitee = parseGitee(url)
        if (gitee != null) {
            val sha = giteeBlobSha(gitee)
            // 总长度不用提前算：blob 响应的 JSON 头里就带 "size"，下载器会读出来给进度条用
            downloadBlob(gitee.blobUrl(sha), target, 0L, onProgress)
            return
        }
        if (url.startsWith("file://")) {
            val src = File(URI(url))
            if (!src.exists()) throw IOException("APK 文件不存在：$src")
            src.inputStream().use { copyStream(it, target, src.length(), onProgress) }
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IOException("不支持的 APK 地址：$url")
        }
        val conn = openHttp(url, readTimeout = 60000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { copyStream(it, target, conn.contentLength.toLong(), onProgress) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 查一个仓库文件的 `sha`（下载要用它去取 blob）。
     *
     * 走**父目录**的 contents 列表（一次请求、几百字节）：直接查文件本身会连整个文件的 base64
     * 一起返回（几 MB），而这里只要 sha。
     */
    private fun giteeBlobSha(file: GiteeFile): String {
        val dir = file.path.substringBeforeLast('/', "")
        val name = file.path.substringAfterLast('/')
        val listBase = "${file.root}/contents/$dir?ref=${file.ref}"
        val listUrl = if (readOnlyToken.isEmpty()) listBase else "$listBase&access_token=$readOnlyToken"
        val body = readPlainText(listUrl, "APK")
        val list = runCatching { org.json.JSONArray(body) }.getOrNull()
            ?: throw IOException("远端 APK 不存在：${file.path}（还没上传？）")
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("name") == name) {
                val sha = item.optString("sha", "")
                if (sha.isNotEmpty()) return sha
            }
        }
        throw IOException("远端 APK 不存在：${file.path}（还没上传？）")
    }

    private fun openHttp(url: String, readTimeout: Int): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            this.readTimeout = readTimeout
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun copyStream(
        input: InputStream,
        target: File,
        total: Long,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var sum = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                sum += read
                onProgress?.invoke(sum, total)
            }
            output.flush()
        }
    }

    /**
     * 下载 Gitee blob：响应是 `{"content":"<base64>","encoding":"base64"}`。
     *
     * 必须**流式**解：APK 动辄上百 MB，base64 再涨 1/3，整段读进字符串在车机上直接 OOM；
     * 这里边读字符边按 4 字符一组解码，内存占用只有几十 KB。
     */
    private fun downloadBlob(
        url: String,
        target: File,
        totalHint: Long,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val conn = openHttp(url, readTimeout = 120000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                // JSON 头是 {"sha":…,"size":N,…,"content":"<base64>"}，size 在 content 前面，
                // 扫到 content 之前顺手把它读出来，进度条才有百分比（目录列表接口不给 size）。
                val head = StringBuilder()
                skipToJsonString(reader, "content", head)
                val declared = Regex("\"size\"\\s*:\\s*(\\d+)")
                    .find(head)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val total = if (totalHint > 0) totalHint else declared
                target.outputStream().use { output ->
                    val outBuf = ByteArray(48 * 1024)
                    var outLen = 0
                    val quad = IntArray(4)
                    var quadLen = 0
                    var sum = 0L
                    while (true) {
                        val c = reader.read()
                        if (c < 0 || c == QUOTE) break
                        val value = base64Value(c)
                        if (value == -1) continue // 换行/空白
                        quad[quadLen++] = value
                        if (quadLen < 4) continue
                        quadLen = 0
                        if (outLen + 3 > outBuf.size) {
                            output.write(outBuf, 0, outLen)
                            outLen = 0
                            // 进度按 48KB 报一次：每个 quartet 都回调会有上百万次，
                            // 界面层再 post 到主线程就把 UI 卡死了（实测会停在 0% 不动）。
                            onProgress?.invoke(if (total > 0) sum.coerceAtMost(total) else sum, total)
                        }
                        outBuf[outLen++] = ((quad[0] shl 2) or (quad[1] shr 4)).toByte()
                        var produced = 1
                        if (quad[2] >= 0) {
                            outBuf[outLen++] = (((quad[1] and 0x0F) shl 4) or (quad[2] shr 2)).toByte()
                            produced++
                        }
                        if (quad[3] >= 0) {
                            outBuf[outLen++] = (((quad[2] and 0x03) shl 6) or quad[3]).toByte()
                            produced++
                        }
                        sum += produced
                    }
                    if (outLen > 0) output.write(outBuf, 0, outLen)
                    output.flush()
                    onProgress?.invoke(if (total > 0) sum.coerceAtMost(total) else sum, total)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private const val QUOTE = '"'.code

    private fun base64Value(c: Int): Int = when (c) {
        in 'A'.code..'Z'.code -> c - 'A'.code
        in 'a'.code..'z'.code -> c - 'a'.code + 26
        in '0'.code..'9'.code -> c - '0'.code + 52
        '+'.code -> 62
        '/'.code -> 63
        '='.code -> -2 // 填充：这一组少输出字节
        else -> -1
    }

    /** 读到 `"<key>" : "` 之后的第一个内容字符之前停住；途中读到的字符（JSON 头）收进 [sink] */
    private fun skipToJsonString(reader: Reader, key: String, sink: StringBuilder? = null) {
        val needle = "\"$key\""
        val window = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) throw IOException("响应里没有 $key 字段")
            window.append(c.toChar())
            sink?.append(c.toChar())
            if (window.length > needle.length) window.deleteCharAt(0)
            if (window.length == needle.length && window.toString() == needle) break
        }
        while (true) {
            val c = reader.read()
            if (c < 0) throw IOException("响应格式异常")
            sink?.append(c.toChar())
            if (c == ':'.code) break
        }
        while (true) {
            val c = reader.read()
            if (c < 0) throw IOException("响应格式异常")
            if (c == QUOTE) return
            if (!c.toChar().isWhitespace()) throw IOException("响应格式异常")
            sink?.append(c.toChar())
        }
    }

    // ---------------- Gitee 地址解析 ----------------

    private val rawUrlRegex =
        Regex("""^https?://gitee\.com/([^/]+)/([^/]+)/raw/([^/?]+)/(.+?)(?:\?.*)?$""")

    private val apiContentsRegex =
        Regex("""^https?://gitee\.com/api/v5/repos/([^/]+)/([^/]+)/contents/(.+?)(?:\?.*)?$""")

    private val refQueryRegex = Regex("""[?&]ref=([^&]+)""")

    /**
     * 把 Gitee 的两种写法都收敛成 [GiteeFile]：
     *   · `/raw/<branch>/<path>`（对外好看，但私有仓库 + 只读令牌实测 403，内部自动换 API）；
     *   · `/api/v5/repos/<owner>/<repo>/contents/<path>?ref=<branch>`。
     * 不是 Gitee 地址返回 null。
     */
    private fun parseGitee(url: String): GiteeFile? {
        rawUrlRegex.find(url)?.let { m ->
            return GiteeFile(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }
        apiContentsRegex.find(url)?.let { m ->
            val ref = refQueryRegex.find(url)?.groupValues?.get(1) ?: REPO_BRANCH
            return GiteeFile(m.groupValues[1], m.groupValues[2], ref, m.groupValues[3])
        }
        return null
    }

    // ---------------- 沙箱 ----------------

    /**
     * 当前进程名（`com.anrays.yplus` / `com.anrays.yplus:black` / 沙箱业务的 `com.example.business`）。
     * 用 /proc 而不是 `Application.getProcessName()`：后者要 API 28，宿主 minSdk 更低。
     */
    private fun currentProcessName(): String? = runCatching {
        File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
    }.getOrNull()

    /**
     * @return null 表示是合法业务包；否则返回不能安装的原因。
     *
     * 🔴 不再校验包名（用户 2026-10-28："业务源不再认包名…方便我随便改 apk 包名"）：
     * 沙箱只留一个包，换成别的包名也算"换新业务"。这里只确认它是个能解析出包名的 APK。
     */
    private fun verifyBusinessApk(context: Context, apk: File): String? {
        val archive = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull() ?: return "下载到的不是有效 APK"
        return if (archive.packageName.isNullOrBlank()) "APK 里没有包名" else null
    }

    /** 沙箱里唯一那个业务包的 APK 路径（只允许装一个包，不再按包名找） */
    private fun installedApkPath(): String? = runCatching {
        BlackBoxCore.get().getInstalledApplications(0, USER_ID).firstOrNull()?.sourceDir
    }.onFailure { Log.e(TAG, "getInstalledApplications failed", it) }.getOrNull()

    /** 从 APK 文件读包名（解析失败为 null） */
    @Suppress("DEPRECATION")
    private fun archivePackageName(context: Context, apk: File): String? = runCatching {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    }.getOrNull()

    private fun backupCurrent(context: Context): File? {
        val path = installedApkPath()?.takeIf { it.isNotEmpty() } ?: return null
        val source = File(path)
        if (!source.exists()) return null
        val dir = File(context.filesDir, "hot_update_backup").apply { mkdirs() }
        val backup = File(dir, "current.apk")
        return try {
            source.inputStream().use { input ->
                backup.outputStream().use { output -> input.copyTo(output) }
            }
            backup
        } catch (t: Throwable) {
            Log.e(TAG, "backup current apk failed", t)
            null
        }
    }

    private fun install(apk: File): String? {
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(apk, USER_ID)
            if (result == null) "无返回结果"
            else if (result.success) null
            else (result.msg ?: "未知错误")
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    /** 复活结果：在哪条车道复活 + 是否成功（安装会 killPackageAsUser，必须补一刀） */
    private data class ReviveOutcome(val foreground: Boolean, val ok: Boolean)

    /** 装完把业务拉回来：前台拉界面，后台静默（不弹前台）；force=true 时无条件拉界面 */
    private fun reviveAfterInstall(context: Context, forceForeground: Boolean): ReviveOutcome {
        return if (forceForeground || isForeground(context)) {
            ReviveOutcome(
                foreground = true,
                ok = runCatching {
                    HostBridge.businessPackage()
                        ?.let { BlackBoxCore.get().launchApk(it, USER_ID) } ?: false
                }.getOrDefault(false)
            )
        } else {
            ReviveOutcome(
                foreground = false,
                ok = runCatching { HostBridge.onReviveBroadcast() }.isSuccess
            )
        }
    }

    /**
     * 宿主/业务界面是否**真的在用户眼前**（[BoxSwitchOverlay]、[UpdateNoticeOverlay] 共用）。
     *
     * 🔴 用户 2026-10-30："app 不在前台的情况下，不要显示悬浮窗和 Notice"。
     * 只看进程 importance 是不够的：沙箱业务退到后台后，进程仍可能被 BlackBox 标成前台，
     * 提示条就会飘在桌面/别的应用上。这里优先用**顶层界面包名**判定：
     *   top == 宿主包名（管理界面） 或 top == 业务包名（业务应用界面）→ 才算前台；
     *   桌面、别的 App、系统弹窗在顶层 → 一律 false。
     * 拿不到顶层包名（个别 ROM 拒绝 getTasks）时退回 importance 判定，至少不会更差。
     */
    fun isForeground(context: Context): Boolean {
        topPackageName()?.let { top ->
            return top == context.packageName || top == HostBridge.businessPackage()
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.any { info ->
                info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    !info.processName.endsWith(":black")
            } == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 当前最顶层 Activity 的包名（反射 `ActivityTaskManager.getTasks(1, 0)`）。
     * 拿不到（ROM 限制 / 反射失败）返回 null —— 调用方自行退回 importance 判定。
     */
    fun topPackageName(): String? = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val tasks = atm!!.javaClass
            .getMethod("getTasks", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(atm, 1, 0) as? List<*>
        val task = tasks?.firstOrNull() ?: return@runCatching null
        val component = task.javaClass.methods.firstOrNull { it.name == "getTopResumedActivity" }?.invoke(task)
            ?: task.javaClass.fields.firstOrNull { it.name == "topActivity" }?.get(task)
            ?: task.javaClass.fields.firstOrNull { it.name == "baseActivity" }?.get(task)
        (component as? android.content.ComponentName)?.packageName
    }.getOrNull()
}
