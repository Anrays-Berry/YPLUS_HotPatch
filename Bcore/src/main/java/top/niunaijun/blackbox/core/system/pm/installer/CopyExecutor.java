package top.niunaijun.blackbox.core.system.pm.installer;


import java.io.File;
import java.io.IOException;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.utils.Slog;


public class CopyExecutor implements Executor {

    private static final String TAG = "CopyExecutor";

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        try {
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                NativeUtils.copyNativeLib(new File(ps.pkg.baseCodePath), BEnvironment.getAppLibDir(ps.pkg.packageName));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
        if (option.isFlag(InstallOption.FLAG_STORAGE)) {
            
            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(ps.pkg.packageName);
            try {
                // 覆盖安装/热更兜底：目标 base.apk 可能是上次安装留下的只读文件。
                // CreatePackageExecutor 通常已清空 data/app/<pkg>，这里再删一次，
                // 避免 FileOutputStream 打开只读旧文件抛 EACCES 导致更新失败。
                if (newFile.exists() && !isSameFile(origFile, newFile)) {
                    if (!newFile.delete()) {
                        newFile.setWritable(true);
                        if (!newFile.delete()) {
                            Slog.w(TAG, "failed to remove old base.apk: " + newFile.getAbsolutePath());
                        }
                    }
                }

                boolean moved = false;
                if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                    moved = FileUtils.renameTo(origFile, newFile);
                }
                if (!moved) {
                    FileUtils.copyFile(origFile, newFile);
                }
                newFile.setReadOnly();

                ps.pkg.baseCodePath = newFile.getAbsolutePath();
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        } else if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
            // system app：直接使用系统已安装的 APK，无需拷贝
        }
        return 0;
    }

    /** 判断源文件与目标文件是否指向同一文件，避免自覆盖时误删源文件 */
    private static boolean isSameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }
}
