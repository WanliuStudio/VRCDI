package com.vrcdevicecheck.osc.priv;

import android.content.Context;

/**
 * Root 通道：直接 su 执行，不引入第三方 root 库，依赖更少、行为更可控。
 * 本类不依赖 Shizuku，即使去掉 Shizuku 依赖也能独立工作。
 */
public class RootShell implements PrivilegedShell {

    private static final long PROBE_TIMEOUT_MS = 3000L;

    @Override
    public boolean available(Context context) {
        return probe() != null;
    }

    @Override
    public String statusText(Context context) {
        Integer uid = probe();
        return uid != null ? "Root 已授权（uid=" + uid + "）" : "未检测到可用的 root 授权";
    }

    @Override
    public String label() {
        return "Root";
    }

    @Override
    public ShellResult run(String script, long timeoutMs) {
        try {
            Process process = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(false)
                    .start();
            return ProcessRunner.read(process, timeoutMs);
        } catch (Throwable t) {
            String message = t.getMessage();
            return new ShellResult(-1, "", message == null ? t.getClass().getSimpleName() : message);
        }
    }

    /**
     * 探测是否已经授予 root；返回 uid（0 表示成功），失败返回 null。
     * 超时 3 秒，避免卡在授权弹窗上阻塞界面。
     */
    public static Integer probe() {
        ShellResult result = new RootShell().run("id -u", PROBE_TIMEOUT_MS);
        if (result == null || result.stdout == null) return null;
        try {
            int uid = Integer.parseInt(result.stdout.trim());
            return uid == 0 ? uid : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
