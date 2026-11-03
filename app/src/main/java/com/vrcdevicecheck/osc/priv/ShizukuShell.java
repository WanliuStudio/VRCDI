package com.vrcdevicecheck.osc.priv;

import android.content.Context;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道：以 shell(uid 2000) 身份执行，能读到普通应用读不到的 sysfs / procfs 节点。
 *
 * 版本说明：本工程依赖 dev.rikka.shizuku:api:12.1.0，Shizuku.newProcess 自 v11 起提供。
 * 社区曾讨论过移除 newProcess（Shizuku-API issue #276，2025-10），若将来升级到更高版本后
 * 这里编译不过，需要改用 UserService + AIDL 方案；届时只需重写本类，其余代码不受影响。
 */
public class ShizukuShell implements PrivilegedShell {

    public static final int REQUEST_CODE_PERMISSION = 0x514B;

    @Override
    public boolean available(Context context) {
        return granted();
    }

    @Override
    public String statusText(Context context) {
        if (!ping()) return "Shizuku 未运行：请打开 Shizuku 应用并启动服务（Android 11+ 可用无线调试启动）";
        if (!granted()) return "Shizuku 已运行，但尚未授权给 VRCDI";
        return "Shizuku 已授权（shell 权限，uid=2000）";
    }

    @Override
    public String label() {
        return "Shizuku";
    }

    @Override
    public ShellResult run(String script, long timeoutMs) {
        if (!granted()) return null;
        try {
            // 注意：若升级 Shizuku 后此行报类型不兼容，把声明类型改成 rikka.shizuku.ShizukuRemoteProcess
            Process process = Shizuku.newProcess(new String[]{"sh", "-c", script}, null, null);
            return ProcessRunner.read(process, timeoutMs);
        } catch (Throwable t) {
            // Shizuku 的方法在失败时抛 RuntimeException（例如 binder 尚未收到）
            String message = t.getMessage();
            return new ShellResult(-1, "", message == null ? t.getClass().getSimpleName() : message);
        }
    }

    /** Shizuku 服务是否在运行 */
    public static boolean ping() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已授权给本应用 */
    public static boolean granted() {
        try {
            return ping() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹出 Shizuku 授权框 */
    public static void requestPermission() {
        try {
            if (!ping()) return;
            if (Shizuku.isPreV11()) return;
            if (granted()) return;
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION);
        } catch (Throwable ignored) {
            // 授权失败由界面重新检测状态时提示
        }
    }

    public static void addResultListener(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener);
        } catch (Throwable ignored) {
            // 服务未就绪时忽略
        }
    }

    public static void removeResultListener(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener);
        } catch (Throwable ignored) {
            // 服务未就绪时忽略
        }
    }
}
