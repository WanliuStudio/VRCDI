package com.vrcdevicecheck.osc.priv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.vrcdevicecheck.osc.R;
import com.vrcdevicecheck.osc.util.LocaleHelper;

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
        if (!ping()) return LocaleHelper.str(context, R.string.privilege_shizuku_raw_not_running);
        if (!granted()) return LocaleHelper.str(context, R.string.privilege_shizuku_unauthorized);
        return LocaleHelper.str(context, R.string.privilege_shizuku_granted);
    }

    @Override
    public String label(Context context) {
        return "Shizuku";
    }

    @Override
    public ShellResult run(Context context, String script, long timeoutMs) {
        if (!granted()) return null;
        try {
            // 注意：若升级 Shizuku 后此行报类型不兼容，把声明类型改成 rikka.shizuku.ShizukuRemoteProcess
            Process process = Shizuku.newProcess(new String[]{"sh", "-c", script}, null, null);
            return ProcessRunner.read(context, process, timeoutMs);
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

    /**
     * 定时唤醒采集前调用：确认 binder 还在；如果已经断开，尝试让 ShizukuProvider
     * 重新绑定，并短暂等待 binder 回来（最多约 1 秒）。
     *
     * 注意：这个方法会阻塞调用线程，只能在后台线程调用。
     */
    public static boolean ensureBound(Context context) {
        if (context == null) return false;
        if (ping()) return true;

        // 触发 ShizukuProvider 重新建立 binder 连接（provider 已在 Manifest 注册）
        try {
            Uri uri = Uri.parse("content://" + context.getPackageName() + ".shizuku");
            context.getContentResolver().call(uri, "requestBinder", null, null);
        } catch (Throwable ignored) {
            // 旧版本 Shizuku 或 provider 未就绪时忽略，继续轮询
        }

        for (int i = 0; i < 10; i++) {
            if (ping()) return true;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ping();
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
