package com.anrays.yplus

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 宿主（宿主）的 root 守护脚本。
 *
 * 为什么需要：宿主被 force-stop（内存清理 / 一键清理 / 系统强停）后，Android 会按 UID 杀进程、
 * 停止派发 manifest 广播、冻结 stopped 包 —— 宿主里所有自保手段（前台服务、AlarmManager、
 * JobScheduler）全部失效。此前唯一兜底是「系统重绑宿主通知监听」（[HostNotificationListener] →
 * [HostBridge.onSystemRebound]），而这次重绑是**系统行为、时有时无**，所以表现为
 * "有时候宿主复活失败"（用户 2026-10-26 报障）。
 *
 * 本对象的做法与业务应用端 `AutostartDaemon` 同构：把脚本以 root 写进 /data/local/tmp 常驻，
 * 脚本 uid=0，force-stop 只杀宿主 UID 的进程、杀不到它；5 秒轮询 `pidof 宿主包名`，
 * 宿主不在就发一条**显式广播**（`-f 0x20` = FLAG_INCLUDE_STOPPED_PACKAGES，stopped 包也能收到）。
 * 广播只唤醒进程，**不启动任何 Activity**，宿主不会被切到前台；进程起来后由 [ReviveReceiver]
 * 交回 [HostBridge] 恢复沙箱业务。
 */
object HostKeepAlive {

    private const val TAG = "HostKeepAlive"

    /** 宿主的真实包名（applicationId，见 app/build.gradle） */
    private const val HOST_PKG = "com.anrays.yplus"

    /** 拉活广播：脚本 → [ReviveReceiver] */
    const val ACTION_REVIVE = "$HOST_PKG.action.HOST_REVIVE"

    /** 显式广播目标（包名/类全名） */
    private const val REVIVE_TARGET = "$HOST_PKG/com.anrays.yplus.ReviveReceiver"

    /** 宿主的 microphone 前台服务（见 [HostMicService]） */
    private const val MIC_SERVICE_TARGET = "$HOST_PKG/com.anrays.yplus.HostMicService"

    private const val SCRIPT_SH = "/data/local/tmp/yplus_box_watch.sh"
    private const val SCRIPT_PID = "/data/local/tmp/yplus_box_watch.pid"
    private const val SCRIPT_NAME = "yplus_box_watch.sh"

    /** 自愈检查节流：进程每次启动只实测一次脚本存活，避免反复跑 su */
    private const val ENSURE_INTERVAL_MS = 30_000L

    @Volatile
    private var lastEnsureMs = 0L

    /** 脚本版本号：内容一改就要 +1，否则已在跑的老脚本不会退场（见 [isActive]） */
    private const val SCRIPT_VERSION = 3

    /**
     * 要守护的无障碍服务 id（宿主自己的 [HostAccessibilityService]，
     * = `Settings.Secure.enabled_accessibility_services` 里的写法）—— 见 [HostAccGuard]。
     */
    private val ACC_SERVICE = HostAccGuard.SERVICE_ID

    /**
     * 脚本内容：pid 防重入 + 亮屏才拉活（熄屏时拉活会被系统判成后台启动、且白耗电）+ pidof 检查。
     * ⚠️ `am broadcast` 不用 `am start`：后者即使 NoDisplay 也会创建 Activity 把宿主切到前台。
     * ⚠️ 宿主起来后还要补一条 `am start-foreground-service`：microphone 前台服务是沙箱内
     *    后台录音的前提（见 [HostMicService]）。root 身份能启动 exported=false 的组件。
     */
    private val SCRIPT: String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# V=$SCRIPT_VERSION")
        appendLine("if [ -f $SCRIPT_PID ]; then")
        appendLine("  OLD_PID=\$(cat $SCRIPT_PID 2>/dev/null)")
        appendLine("  if [ -n \"\$OLD_PID\" ] && kill -0 \$OLD_PID 2>/dev/null; then exit 0; fi")
        appendLine("fi")
        appendLine("echo \$\$ > $SCRIPT_PID")
        // 计数器：宿主进程一直在时也每 30 秒补一次 microphone FGS（服务被系统回收后能自愈）
        appendLine("MICTICK=6")
        appendLine("while true; do")
        // ① 无障碍保活（**固定开启，无开关**：宿主是业务载体，无障碍掉了业务就瘫）
        //    机制同业务应用的「守护进程」（MacroDroid 手法）：系统/ROM 把宿主无障碍从
        //    enabled_accessibility_services 摘掉后写回，系统随即重新绑定。
        //    跑在宿主进程之外 → 宿主被 force-stop（杀后台）也照样捞回，这是最强的一层。
        appendLine("  ACC=\$(settings get secure enabled_accessibility_services 2>/dev/null)")
        appendLine("  if ! echo \"\$ACC\" | grep -q \"$ACC_SERVICE\"; then")
        appendLine("    if [ -z \"\$ACC\" ] || [ \"\$ACC\" = \"null\" ]; then")
        appendLine("      settings put secure enabled_accessibility_services \"$ACC_SERVICE\" >/dev/null 2>&1")
        appendLine("    else")
        appendLine("      settings put secure enabled_accessibility_services \"\$ACC:$ACC_SERVICE\" >/dev/null 2>&1")
        appendLine("    fi")
        appendLine("    settings put secure accessibility_enabled 1 >/dev/null 2>&1")
        appendLine("  fi")
        // ② 进程拉活
        appendLine("  WF=\$(dumpsys power 2>/dev/null | grep mWakefulness | head -1)")
        appendLine("  if echo \"\$WF\" | grep -q Awake; then")
        appendLine("    PID=\$(pidof $HOST_PKG 2>/dev/null)")
        appendLine("    if [ -z \"\$PID\" ]; then")
        appendLine("      am broadcast -a $ACTION_REVIVE -n $REVIVE_TARGET -f 0x20 >/dev/null 2>&1")
        appendLine("      sleep 3")
        appendLine("      am start-foreground-service -n $MIC_SERVICE_TARGET >/dev/null 2>&1")
        appendLine("      MICTICK=0")
        appendLine("    else")
        appendLine("      MICTICK=\$((MICTICK + 1))")
        appendLine("      if [ \$MICTICK -ge 6 ]; then")
        appendLine("        am start-foreground-service -n $MIC_SERVICE_TARGET >/dev/null 2>&1")
        appendLine("        MICTICK=0")
        appendLine("      fi")
        appendLine("    fi")
        appendLine("    sleep 5")
        appendLine("  else")
        appendLine("    sleep 2")
        appendLine("  fi")
        appendLine("done")
    }

    /**
     * 自愈入口：宿主每次启动（[CarBoxApp.onCreate]）调用一次。
     * 脚本若已不在（设备重启后没人重启它、被清理工具干掉、上次 su 未授权没部署成功），这里补上。
     * ⚠️ 异步执行：要跑 su + shell，不能占住主线程。
     */
    fun ensureRunning(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnsureMs < ENSURE_INTERVAL_MS) return
        lastEnsureMs = now
        // root 默认关闭：绝不主动执行 su（连 hasRoot 探测都不做，否则探测本身就会弹「超级用户请求」），
        // 直接跳过 root 守护脚本。车机无 root 时这套脚本本就无意义，复活靠开机广播 + 通知监听重绑。
        if (!RootPrefs.enabled(context)) {
            Log.i(TAG, "root 未开启 → 跳过 root 守护脚本（不主动申请超级用户）")
            return
        }
        Thread({
            // 无 root 的车机上这套脚本本来就没有意义（force-stop 会按 UID 把它一起杀掉），
            // 且降级到 `sh -c` 会在 App UID 下留一个无效守护进程 —— 直接跳过，别制造"有守护"的假象。
            if (!hasRoot()) {
                Log.i(TAG, "无 root，跳过 root 守护脚本（改用开机广播 + 通知监听重绑）")
                return@Thread
            }
            if (isActive()) return@Thread
            Log.i(TAG, "守护脚本不在运行 → 重新部署")
            deploy()
        }, "host-keepalive-ensure").start()
    }

    /** 是否具备 root（su 且返回 uid=0）；只探测一次 su，不走降级链 */
    private fun hasRoot(): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "su -c id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
        out.contains("uid=0")
    } catch (_: Exception) {
        false
    }

    /** 强制部署并启动脚本（阻塞，调用方须为后台线程） */
    private fun deploy() {
        try {
            // 先把老脚本进程停掉：脚本开头的 pid 防重入会让新脚本直接 exit 0，
            // 改了内容（版本号）却永远不生效（实测踩到）。
            shell("OLD=\$(cat $SCRIPT_PID 2>/dev/null); [ -n \"\$OLD\" ] && kill \$OLD 2>/dev/null; rm -f $SCRIPT_PID")
            val b64 = Base64.encodeToString(SCRIPT.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            shell("echo \"$b64\" | base64 -d > $SCRIPT_SH && chmod 755 $SCRIPT_SH")
            // 末尾 echo 用于判定成功：runShell 以「输出非空」认定成功，否则会降级成非 root 的
            // `sh -c` 再起一份 —— 那份 uid 是宿主，force-stop 会连它一起杀，守护就白做了。
            val started = shell("sh $SCRIPT_SH >/dev/null 2>&1 & echo STARTED")
            Log.i(TAG, "守护脚本部署完成 started=${started != null} pkg=$HOST_PKG")
        } catch (t: Throwable) {
            Log.w(TAG, "守护脚本部署失败", t)
        }
    }

    /** 卸载脚本（调试用：pkill + 清文件） */
    fun uninstall() {
        Thread({
            shell("pkill -f $SCRIPT_NAME; rm -f $SCRIPT_SH $SCRIPT_PID")
            Log.i(TAG, "守护脚本已卸载")
        }, "host-keepalive-uninstall").start()
    }

    /** 脚本是否**在跑且版本一致**（版本不一致 = 老脚本，需要重新部署替换） */
    fun isActive(): Boolean {
        // ⚠️ 这里**不能用单引号**：整条命令会被 `su -c '...'` 的单引号包住，
        //    内层再出现单引号会把命令截断（grep 参数变成残片，判定恒为 false 或恒为 true）。
        val out = shell(
            "if [ \"\$(grep -c \"# V=$SCRIPT_VERSION\" $SCRIPT_SH 2>/dev/null)\" = \"1\" ]; then " +
                "PID=\$(cat $SCRIPT_PID 2>/dev/null); " +
                "if [ -n \"\$PID\" ] && kill -0 \$PID 2>/dev/null; then echo RUNNING; fi; fi"
        )
        return out?.contains("RUNNING") == true
    }

    /**
     * 尽力执行 shell（root 优先）：依次尝试 `su -c` / `sh -c` / 裸命令。
     * 输出非空且不含 not found / denied 才认成功 —— 与业务应用端 `AutostartDaemon.runShell` 一致。
     */
    internal fun shell(command: String): String? {
        // ⚠️ 用**单引号**包住 su 的参数：命令体里本来就带双引号（`[ -n "$PID" ]`），
        //    再套一层双引号会被 shell 提前截断 → 语法错误 → 输出非空 → 被误判成"执行成功"，
        //    于是 isActive() 永远拿不到 RUNNING、每次启动都重复部署（2026-10-26 实测踩到）。
        val attempts = listOf("su -c '$command'", "sh -c '$command'", command)
        for (a in attempts) {
            val out = try {
                val p = ProcessBuilder("sh", "-c", a).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
                text.trim()
            } catch (_: Exception) {
                null
            }
            if (out != null && out.isNotEmpty() &&
                !out.contains("not found") && !out.contains("denied", ignoreCase = true)
            ) {
                return out
            }
        }
        return null
    }
}
