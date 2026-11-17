package com.anrays.yplus

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Environment
import android.util.Log
import java.io.File
import java.util.Locale

/** 本地存储里的一个 APK 文件（「更新应用 → 扫描本地 APK」的列表项） */
data class LocalApkItem(
    val file: File,
    /** 能解析出来就用应用名，否则退回文件名 */
    val label: String,
    /** 大小 · 版本 · 所在目录 */
    val subtitle: String,
    val icon: Drawable?
)

/**
 * 扫描本机存储里的 APK（「更新应用」的第一个来源，用户 2026-10-28）。
 *
 * 为什么不全盘遍历：车机存储里 `Android/data`、相册视频动辄上万个文件，扫一次要几十秒，
 * 用户点完按钮只会以为卡死。这里只扫**放 APK 的常见位置**，并且有深度/数量/时间三重上限。
 *
 * 目录口径：
 *  · 应用自己的缓存与备份（下载链接、SAF、热更备份都落在这几处）；
 *  · 外部存储的 Download / Documents / 根目录（深度 3，跳过 Android、DCIM、音乐视频等）；
 *  · `Download/SourcePlus` 与 `SourcePlus` 根目录（配置备份、音效库所在的同级目录）。
 *
 * ⚠️ 宿主 targetSdk=28 → 走 legacy 存储，拿到 READ_EXTERNAL_STORAGE 就能 File 遍历；
 *    没授权时公共目录会 `listFiles() == null`，这里静默跳过（由界面提示去授权/换来源）。
 */
object LocalApkScanner {

    private const val TAG = "LocalApkScanner"

    /** 从每个根目录往下最多几层 */
    private const val MAX_DEPTH = 3

    /** 最多收集多少个 APK（再多也没人翻） */
    private const val MAX_FILES = 120

    /** 整个扫描的时间上限：超时就带着已找到的结果返回，不把界面卡死 */
    private const val TIME_BUDGET_MS = 4000L

    /** 明显不会放 APK 的目录直接跳过（能省掉绝大部分遍历） */
    private val SKIP_DIRS = setOf(
        "Android", "DCIM", "Pictures", "Movies", "Music", "Podcasts",
        "Ringtones", "Alarms", "Notifications", "Tencent", "WeChat"
    )

    fun scan(context: Context): List<LocalApkItem> {
        val startedAt = System.currentTimeMillis()
        val found = ArrayList<File>()
        val seen = HashSet<String>()
        val canContinue = { System.currentTimeMillis() - startedAt < TIME_BUDGET_MS }

        for (root in roots(context)) {
            if (found.size >= MAX_FILES || !canContinue()) break
            walk(root, 0, found, seen, canContinue)
        }
        Log.i(TAG, "scan done: ${found.size} apk in ${System.currentTimeMillis() - startedAt}ms")

        return found
            .sortedByDescending { it.lastModified() }
            .take(MAX_FILES)
            .map { describe(context, it) }
    }

    private fun roots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        // 应用自己的目录：下载链接 / SAF / 热更备份都在这几处
        roots += File(context.cacheDir, "import")
        roots += File(context.cacheDir, "hot_update")
        roots += File(context.filesDir, "hot_update_backup")
        context.getExternalFilesDir(null)?.let { roots += it }

        @Suppress("DEPRECATION")
        val ext = Environment.getExternalStorageDirectory()
        roots += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        roots += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        roots += File(ext, "Download/SourcePlus")
        roots += File(ext, "SourcePlus")
        roots += ext

        return roots.filter { runCatching { it.isDirectory }.getOrDefault(false) }
    }

    private fun walk(
        dir: File,
        depth: Int,
        out: MutableList<File>,
        seen: MutableSet<String>,
        canContinue: () -> Boolean
    ) {
        if (out.size >= MAX_FILES || !canContinue()) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (out.size >= MAX_FILES || !canContinue()) return
            if (child.isDirectory) {
                if (depth + 1 > MAX_DEPTH) continue
                val name = child.name
                if (name.startsWith(".") || name in SKIP_DIRS) continue
                walk(child, depth + 1, out, seen, canContinue)
            } else if (child.name.endsWith(".apk", ignoreCase = true) && child.length() > 0L) {
                val path = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                if (seen.add(path)) out += child
            }
        }
    }

    private fun describe(context: Context, file: File): LocalApkItem {
        val pm = context.packageManager
        val archive = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull()

        var label = file.name.substringBeforeLast('.')
        var icon: Drawable? = null
        val info = archive?.applicationInfo
        if (info != null) {
            // 未安装的 APK 必须补上这两个路径，图标/名称才解析得出来
            info.sourceDir = file.absolutePath
            info.publicSourceDir = file.absolutePath
            label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(label)
            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
        }

        val parts = ArrayList<String>(3)
        parts += sizeText(file.length())
        // versionName 为空的包（源PLUS 故意留空）用 versionCode 逐位换算，与宿主的版本口径一致
        HotUpdateManager.versionLabel(archive).takeIf { it.isNotEmpty() }?.let { parts += "v$it" }
        file.parentFile?.absolutePath?.let { parts += it }
        return LocalApkItem(file, label, parts.joinToString(" · "), icon)
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
