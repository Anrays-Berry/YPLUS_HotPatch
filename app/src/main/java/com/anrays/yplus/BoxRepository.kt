package com.anrays.yplus

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.AbiUtils
import java.io.File
import java.util.UUID

data class VirtualAppItem(
    val label: String,
    val packageName: String,
    val apkPath: String,
    val icon: Drawable?
)

data class DeviceAppItem(
    val label: String,
    val packageName: String,
    val apkPath: String,
    val icon: Drawable?
)

/**
 * 沙箱（虚拟空间）操作统一入口。所有方法都是阻塞的，请在子线程调用。
 */
object BoxRepository {

    private const val TAG = "BoxRepository"

    /** 单用户模式，0 号用户即默认空间 */
    const val USER_ID = 0

    // ---------------- 沙箱内应用 ----------------

    fun listVirtualApps(): List<VirtualAppItem> {
        val result = ArrayList<VirtualAppItem>()
        val apps: List<ApplicationInfo> = runCatching {
            BlackBoxCore.get().getInstalledApplications(0, USER_ID)
        }.onFailure { Log.e(TAG, "getInstalledApplications failed", it) }.getOrDefault(emptyList())

        for (info in apps) {
            val pkg = info.packageName ?: continue
            val path = info.sourceDir ?: ""
            val icon = loadArchiveIcon(path)
            val label = loadArchiveLabel(path, pkg)
            result.add(VirtualAppItem(label, pkg, path, icon))
        }
        result.sortBy { it.label.lowercase() }
        return result
    }

    /**
     * 安装本地 APK 文件，返回 null 表示成功，否则返回失败原因。
     *
     * 🔴 沙箱**只保留一个包**（用户 2026-10-28："框架只允许安装一个 apk，移除无关 apk"）：
     * 装成功后把其余已装包全部卸掉；失败时不动旧包，避免"装失败还把能用的删了"。
     */
    fun installApk(file: File): String? {
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(file, USER_ID)
            if (result == null) "安装失败：无返回结果"
            else if (!result.success) (result.msg ?: "未知错误")
            else {
                uninstallAllExcept(result.packageName ?: archivePackageName(file))
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "installApk failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * 把 SAF / 文件管理器选中的 Uri 复制到缓存目录，返回落地文件。
     * 失败返回 null（打不开、读不到、0 字节）。
     */
    fun copyUriToCache(context: Context, uri: Uri): File? {
        return try {
            val name = queryDisplayName(context, uri)?.takeIf { it.endsWith(".apk", true) }
                ?: ("import_" + UUID.randomUUID().toString().take(8) + ".apk")
            val dir = File(context.cacheDir, "import").apply { mkdirs() }
            val target = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.takeIf { it.length() > 0L }
        } catch (t: Throwable) {
            Log.e(TAG, "copyUriToCache failed", t)
            null
        }
    }

    /** 从内容 Uri 拷贝到缓存目录后安装 */
    @Suppress("DEPRECATION")
    fun installFromUri(context: Context, uri: Uri): String? {
        val target = copyUriToCache(context, uri) ?: return "无法读取所选文件"
        return try {
            context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                ?: return "所选文件不是有效的 APK"
            val error = installApk(target)
            if (error == null) {
                // 安装成功后清理导入缓存
                runCatching { target.delete() }
            }
            error
        } catch (t: Throwable) {
            Log.e(TAG, "installFromUri failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    fun uninstall(packageName: String): String? {
        return try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, USER_ID)
            null
        } catch (t: Throwable) {
            Log.e(TAG, "uninstall failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    fun clearData(packageName: String): String? {
        return try {
            BlackBoxCore.get().clearPackage(packageName, USER_ID)
            null
        } catch (t: Throwable) {
            Log.e(TAG, "clearPackage failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    fun launch(packageName: String): Boolean {
        return try {
            BlackBoxCore.get().launchApk(packageName, USER_ID)
        } catch (t: Throwable) {
            Log.e(TAG, "launchApk failed", t)
            false
        }
    }

    // ---------------- 沙箱唯一包（不认包名） ----------------

    /**
     * 沙箱里已装包的**包名**（轻量：不解码图标）。
     *
     * 只允许装一个 APK 之后，"业务源"就是这里唯一的那个包 —— 因此宿主**不再按包名识别**
     * 业务（用户 2026-10-28："业务源不再认包名，因为只有一个了，方便我随便改 apk 包名"）。
     */
    fun installedPackageNames(): List<String> = runCatching {
        BlackBoxCore.get().getInstalledApplications(0, USER_ID)
            .mapNotNull { it.packageName }
    }.onFailure { Log.e(TAG, "getInstalledApplications(names) failed", it) }
        .getOrDefault(emptyList())

    /** 沙箱里唯一的包（没有则 null）。业务源一律以它为准。 */
    fun singleInstalledPackage(): String? = installedPackageNames().firstOrNull()

    /** 卸掉除 [keep] 之外的全部已装包（keep=null 即清空）。返回卸载个数。 */
    fun uninstallAllExcept(keep: String?): Int {
        var removed = 0
        for (pkg in installedPackageNames()) {
            if (pkg == keep) continue
            runCatching { BlackBoxCore.get().uninstallPackageAsUser(pkg, USER_ID) }
                .onSuccess { removed++ }
                .onFailure { Log.w(TAG, "uninstall $pkg failed", it) }
        }
        if (removed > 0) Log.i(TAG, "cleaned $removed package(s), keep=$keep")
        return removed
    }

    /** 从 APK 文件读包名（解析失败为 null） */
    private fun archivePackageName(file: File): String? = runCatching {
        @Suppress("DEPRECATION")
        CarBoxApp.instance.packageManager
            .getPackageArchiveInfo(file.absolutePath, 0)?.packageName
    }.getOrNull()

    // ---------------- 本机已安装应用 ----------------

    fun listDeviceApps(context: Context): List<DeviceAppItem> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val result = ArrayList<DeviceAppItem>()
        val apps = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.onFailure { Log.e(TAG, "getInstalledApplications(device) failed", it) }
            .getOrDefault(emptyList())

        for (info in apps) {
            if (info.packageName == selfPkg) continue
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val path = info.sourceDir ?: continue
            if (!runCatching { AbiUtils.isSupport(File(path)) }.getOrDefault(true)) continue
            if (pm.getLaunchIntentForPackage(info.packageName) == null) continue

            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName)
            val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
            result.add(DeviceAppItem(label, info.packageName, path, icon))
        }
        result.sortBy { it.label.lowercase() }
        return result
    }

    /** 克隆一个本机已安装应用（按包名安装到沙箱）。同样只保留这一个包。 */
    fun cloneDeviceApp(packageName: String): String? {
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(packageName, USER_ID)
            if (result == null) "安装失败：无返回结果"
            else if (!result.success) (result.msg ?: "未知错误")
            else {
                uninstallAllExcept(packageName)
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cloneDeviceApp failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 直接从 APK 文件解析图标，未安装到系统的 APK 也能拿到图标。
     */
    private fun loadArchiveIcon(path: String): Drawable? {
        if (path.isEmpty()) return null
        return runCatching {
            val pm = CarBoxApp.instance.packageManager
            val archive = pm.getPackageArchiveInfo(path, 0) ?: return null
            val appInfo = archive.applicationInfo ?: return null
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            pm.getApplicationIcon(appInfo)
        }.getOrNull()
    }

    private fun loadArchiveLabel(path: String, fallback: String): String {
        if (path.isEmpty()) return fallback
        return runCatching {
            val pm = CarBoxApp.instance.packageManager
            val archive = pm.getPackageArchiveInfo(path, 0) ?: return fallback
            val appInfo = archive.applicationInfo ?: return fallback
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(fallback)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return null
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    }
}
