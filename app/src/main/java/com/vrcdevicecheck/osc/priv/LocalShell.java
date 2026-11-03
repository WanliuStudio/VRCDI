package com.vrcdevicecheck.osc.priv;

import android.content.Context;

import java.io.File;

/**
 * 无特权通道：只读那些恰好对普通应用开放的节点，其余一律"不可用"。
 *
 * Android 10+ 上绝大多数 sysfs / procfs 节点被 SELinux 挡住，
 * 所以这条通道能力有限，仅作兜底（RAM、电量走 Android API，与通道无关）。
 */
public class LocalShell implements PrivilegedShell {

    @Override
    public boolean available(Context context) {
        return true;
    }

    @Override
    public String statusText(Context context) {
        return "无特权模式：仅能读取系统开放的信息，CPU/GPU 频率与温度大概率不可用";
    }

    @Override
    public String label() {
        return "无特权";
    }

    @Override
    public ShellResult run(String script, long timeoutMs) {
        // 不执行脚本，改为直接尝试读几个常见节点，输出与脚本一致的 key|value 格式
        StringBuilder out = new StringBuilder();
        out.append("@BEGIN\n");

        File thermalRoot = new File("/sys/class/thermal");
        File[] zones = thermalRoot.listFiles();
        if (zones != null) {
            for (File zone : zones) {
                if (!zone.getName().startsWith("thermal_zone")) continue;
                String type = readOrNull(new File(zone, "type"));
                String temp = readOrNull(new File(zone, "temp"));
                if (type == null || temp == null) continue;
                out.append("thermal.").append(type).append('|').append(temp).append('\n');
            }
        }

        long maxFreq = 0L;
        File cpuRoot = new File("/sys/devices/system/cpu");
        File[] cpus = cpuRoot.listFiles();
        if (cpus != null) {
            for (File cpu : cpus) {
                String name = cpu.getName();
                if (!name.startsWith("cpu") || !isAllDigits(name.substring(3))) continue;
                String freq = readOrNull(new File(cpu, "cpufreq/scaling_cur_freq"));
                if (freq == null) continue;
                try {
                    long value = Long.parseLong(freq.trim());
                    if (value > maxFreq) maxFreq = value;
                } catch (NumberFormatException ignored) {
                    // 非数字直接跳过
                }
            }
        }
        if (maxFreq > 0) out.append("cpufreq.cur_max|").append(maxFreq).append('\n');

        out.append("@END\n");
        return new ShellResult(0, out.toString(), "");
    }

    private static boolean isAllDigits(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }

    private static String readOrNull(File file) {
        try {
            if (!file.canRead()) return null;
            byte[] buffer = new byte[256];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int read = in.read(buffer);
                if (read <= 0) return null;
                String text = new String(buffer, 0, read, "UTF-8").trim();
                return text.isEmpty() ? null : text;
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
