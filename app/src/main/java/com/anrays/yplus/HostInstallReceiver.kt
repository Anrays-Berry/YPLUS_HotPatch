package com.anrays.yplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * 宿主的**调试安装通道**：把 APK 直接装进沙箱（业务包），不用点界面。
 *
 * 为什么需要：业务应用跑在沙箱里，盒子里的包没法从外面直接覆盖；
 * 而宿主读 `/sdcard` 会撞分区存储（EACCES）、非 exported 组件 adb 也起不来 ——
 * 于是留这么一条显式广播出口，配合 http 地址下载（下载到宿主 cache，**不需要任何存储权限**）。
 *
 * 用法（PC 上起个静态服务，把 APK 放进去）：
 *   adb shell am broadcast -n com.anrays.yplus/.HostInstallReceiver \
 *        -a com.anrays.yplus.action.INSTALL \
 *        --es url http://192.168.1.10:8000/business.apk
 * 也可以给本机文件（前提宿主有权限读到）：--es path /sdcard/Download/x.apk
 * 额外开关：--ez clear true  → 装前清数据
 *
 * ⚠️ 只在 **debug 构建**生效（release 里直接 return），避免留后门。
 */
class HostInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 只在 debuggable 构建生效（AGP8 没开 buildConfig，这里直接查 debuggable 标志）
        val debuggable =
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "debug receiver works on debuggable builds only, ignored")
            return
        }
        when (intent?.action) {
            ACTION -> handleInstall(context, intent)
            ACTION_ROOT -> handleRoot(context, intent)
            else -> return
        }
    }

    /** 调试安装通道：把业务 APK 直接装进沙箱（见文件头注释） */
    private fun handleInstall(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty().trim()
        val clear = intent.getBooleanExtra(EXTRA_CLEAR, false)
        if (url.isEmpty() && path.isEmpty()) {
            Log.w(TAG, "missing url/path")
            return
        }
        Log.i(TAG, "install requested url=$url path=$path clear=$clear")
        val pending = goAsync()
        Thread({
            var msg: String
            try {
                val result = if (path.isNotEmpty()) {
                    val f = File(path)
                    if (!f.isFile) HotUpdateManager.UpdateResult.Failed("文件不存在或读不到：$path", false)
                    else HotUpdateManager.installLocalApk(context, f, clear)
                } else {
                    // 下载到宿主 cache 再装，全程不需要存储权限
                    HotUpdateManager.forceInstall(context, url, clear)
                }
                msg = when (result) {
                    is HotUpdateManager.UpdateResult.Success -> "已安装并拉起"
                    is HotUpdateManager.UpdateResult.Failed -> "安装失败：${result.message}"
                }
                Log.i(TAG, "install result: $msg")
            } catch (t: Throwable) {
                msg = "安装异常：${t.message}"
                Log.e(TAG, "install failed", t)
            }
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(context, "应用盒：$msg", Toast.LENGTH_LONG).show() }
            }
            pending.finish()
        }, "host-install").start()
    }

    /**
     * 调试用 root 总开关：有些能力（root 守护脚本、su 兜底授权）只在 [RootPrefs] 开启后才会跑，
     * 宿主铁律是「绝不主动弹超级用户请求」，所以默认关；调试时按需通过这条广播打开。
     *   adb shell am broadcast -n com.anrays.yplus/.HostInstallReceiver \
     *        -a com.anrays.yplus.action.SET_ROOT --ez on true
     */
    private fun handleRoot(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra(EXTRA_ON, false)
        RootPrefs.setEnabled(context, on)
        Log.i(TAG, "root prefs set on=$on")
        if (on) {
            // 立刻补一下无障碍保活（固定开启），并重新部署 root 守护脚本
            runCatching { HostAccGuard.ensure(context) }
            runCatching { HostKeepAlive.ensureRunning(context) }
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(context, "应用盒：root ${if (on) "已开启" else "已关闭"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "HostInstallReceiver"

        const val ACTION = "com.anrays.yplus.action.INSTALL"
        const val ACTION_ROOT = "com.anrays.yplus.action.SET_ROOT"
        const val EXTRA_URL = "url"
        const val EXTRA_PATH = "path"
        const val EXTRA_CLEAR = "clear"
        const val EXTRA_ON = "on"
    }
}
