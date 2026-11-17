package com.anrays.yplus

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 业务 APK 的 manifest 声明（**业务写、宿主读**，唯一出处）。
 *
 * 目前只有一条 [META_CONSUMER] = `frame.consumer`：
 *   有 → 业务是**消费端**：更新归它自己（读自己的 manifest.json），宿主不推更新、不画悬浮切换按钮；
 *   无 → 宿主承接（宿主清单更新 + 悬浮按钮回后台）。
 *
 * 为什么用 meta-data 而不是运行时命令：命令要等业务进程起来才发得出去（几百毫秒），
 * 而 launch 动画 / 启动检查就发生在这一段；存档声明在装包那一刻就生效，没有时序问题。
 *
 * ⚠️ 与业务 `AndroidManifest.xml` 里的同名 meta-data 一一对应，改名要两端同步。
 */
object BusinessManifest {

    private const val TAG = "BusinessManifest"

    /** 消费端声明 */
    const val META_CONSUMER = "frame.consumer"

    /** 解析结果缓存时长（业务包被覆盖安装后最迟这么久生效；[invalidate] 可立即失效） */
    private const val TTL_MS = 30_000L

    private var consumerCached = false

    @Volatile
    private var checkedAt = 0L

    /** 业务包是否声明 `frame.consumer`（= 消费端，宿主不接管更新/悬浮按钮） */
    fun isConsumer(context: Context): Boolean {
        val app = context.applicationContext
        val at = checkedAt
        if (at != 0L && System.currentTimeMillis() - at < TTL_MS) return consumerCached
        val value = runCatching {
            val path = HotUpdateManager.businessApkPath()?.takeIf { it.isNotEmpty() }
                ?: return@runCatching false
            @Suppress("DEPRECATION")
            val info = app.packageManager.getPackageArchiveInfo(path, PackageManager.GET_META_DATA)
            // 🔴 getPackageArchiveInfo 返回 PackageInfo，meta-data 挂在 applicationInfo 上
            info?.applicationInfo?.metaData?.getBoolean(META_CONSUMER, false) ?: false
        }.getOrDefault(false)
        consumerCached = value
        checkedAt = System.currentTimeMillis()
        Log.i(TAG, "$META_CONSUMER=$value")
        return value
    }

    /** 业务包换了/升级了 → 下次判定重新读 manifest */
    fun invalidate() {
        checkedAt = 0L
    }
}
