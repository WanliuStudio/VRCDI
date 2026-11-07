package com.vrcdevicecheck.osc.device;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import com.vrcdevicecheck.osc.priv.PrivilegedShell;
import com.vrcdevicecheck.osc.priv.SnapshotScript;

import java.util.List;

/**
 * 设备信息仓库：把「特权脚本读数」与「Android API 读数」合并成一份快照。
 *
 * 低占用设计：
 * - 每个采集周期只起一次子进程，所有节点在一个脚本里读完，不做逐指标多次 exec；
 * - 必须在子线程调用（collect 里有文件与进程 IO）；
 * - 读不到的节点一律为 null，界面显示"不可用"，不做任何猜测填充。
 */
public class DeviceInfoRepository {

    /** 首次采集时补一次采样的间隔，用于算出 CPU 使用率 */
    private static final long CPU_PRIME_DELAY_MS = 500L;
    private static final long SCRIPT_TIMEOUT_MS = 6000L;

    /** 一次采集的完整结果 */
    public static class CollectResult {
        public final DeviceSnapshot snapshot;
        /** 通道不可用、脚本无输出等提示，null 表示一切正常 */
        public final String warning;

        public CollectResult(DeviceSnapshot snapshot, String warning) {
            this.snapshot = snapshot;
            this.warning = warning;
        }
    }

    private final Context context;
    private DeviceModelTable modelTable;
    private final String script;

    /** 上一次 /proc/stat 采样，用于计算 CPU 使用率 */
    private List<Long> previousCpuStat;

    public DeviceInfoRepository(Context context) {
        this.context = context.getApplicationContext();
        this.modelTable = DeviceModelTables.load(this.context);
        CpuNames.init(this.context);
        this.script = SnapshotScript.get(this.context);
    }

    public DeviceModelTable getModelTable() {
        return modelTable;
    }

    /** 用户导入新设备表后调用：重新从私有目录/内置 assets 加载 */
    public DeviceModelTable reloadModelTable() {
        this.modelTable = DeviceModelTables.load(context);
        return modelTable;
    }

    /** 不需要任何权限就能拿到的信息 */
    public JavaSideInfo javaSideInfo() {
        Long totalMb = null;
        Long usedMb = null;
        Object service = context.getSystemService(Context.ACTIVITY_SERVICE);
        if (service instanceof ActivityManager) {
            ActivityManager am = (ActivityManager) service;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            if (info.totalMem > 0) {
                totalMb = info.totalMem / (1024L * 1024L);
                usedMb = (info.totalMem - info.availMem) / (1024L * 1024L);
            }
        }

        // Build.SOC_MODEL / SOC_MANUFACTURER 需要 API 31，本工程 compileSdk 30 拿不到，
        // 改用系统属性反射读取；属性没有时再反射读 Build 字段
        String socModel = SystemProps.get("ro.soc.model");
        if (socModel == null) socModel = SystemProps.socModelFromBuild();
        String socManufacturer = SystemProps.get("ro.soc.manufacturer");
        if (socManufacturer == null) socManufacturer = SystemProps.socManufacturerFromBuild();

        return new JavaSideInfo(
                Build.BRAND,
                Build.MODEL,
                Build.HARDWARE,
                socModel,
                socManufacturer,
                totalMb,
                usedMb,
                batteryLevel(),
                isCharging());
    }

    private Intent batteryIntent() {
        try {
            return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            return null;
        }
    }

    private Integer batteryLevel() {
        Intent intent = batteryIntent();
        if (intent == null) return null;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return null;
        int pct = level * 100 / scale;
        return Math.max(0, Math.min(100, pct));
    }

    /** 严格按需求：只有真正在充电时才显示"充电中"，充满或未充电都不显示 */
    private boolean isCharging() {
        Intent intent = batteryIntent();
        if (intent == null) return false;
        return intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                == BatteryManager.BATTERY_STATUS_CHARGING;
    }

    /** 采集一次。必须在子线程调用。 */
    public CollectResult collect(PrivilegedShell shell) {
        JavaSideInfo java = javaSideInfo();
        String channel = shell.label();

        com.vrcdevicecheck.osc.priv.ShellResult result;
        try {
            result = shell.run(script, SCRIPT_TIMEOUT_MS);
        } catch (Throwable t) {
            result = null;
        }

        if (result == null || !result.isOk()) {
            String warning;
            if (result == null) {
                warning = channel + " 通道不可用，当前仅显示免特权信息";
            } else if (result.stdout.trim().isEmpty() && !result.stderr.trim().isEmpty()) {
                warning = channel + " 执行失败：" + firstLine(result.stderr);
            } else {
                warning = channel + " 无输出，当前仅显示免特权信息";
            }
            RawFacts degradedFacts = RawFacts.empty().withGpuRenderer(GpuInfo.renderer());
            DeviceSnapshot degraded = SnapshotMapper.map(
                    degradedFacts, java, null, modelTable, channel + "（降级）", foregroundAppName());
            return new CollectResult(degraded, warning);
        }

        RawFacts facts = SnapshotParser.parse(result.stdout).withGpuRenderer(GpuInfo.renderer());
        List<Long> baseline = previousCpuStat;

        // CPU 使用率需要两次采样。首次采集时补一次短间隔采样，避免第一条消息缺这一项。
        if (baseline == null && !facts.cpuStat.isEmpty()) {
            try {
                Thread.sleep(CPU_PRIME_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            com.vrcdevicecheck.osc.priv.ShellResult again = null;
            try {
                again = shell.run("head -1 /proc/stat 2>/dev/null", 3000L);
            } catch (Throwable ignored) {
                // 补采样失败就跳过，下一轮自然会有基准
            }
            if (again != null && again.isOk()) {
                List<Long> second = SnapshotParser.parseCpuStat(again.stdout.trim());
                baseline = facts.cpuStat;
                facts = facts.withCpuStat(second.isEmpty() ? facts.cpuStat : second);
            }
        }

        DeviceSnapshot snapshot = SnapshotMapper.map(facts, java, baseline, modelTable, channel, foregroundAppName());
        if (!facts.cpuStat.isEmpty()) previousCpuStat = facts.cpuStat;
        return new CollectResult(snapshot, null);
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text.trim() : text.substring(0, index).trim();
    }

    /**
     * 采集瞬间的前台应用名，作为可上报的「前台应用」字段。
     * 没授予「使用情况访问」时返回 null，界面与 Chatbox 都显示"不可用"，不猜。
     */
    private String foregroundAppName() {
        try {
            if (!ForegroundApp.hasUsageAccess(context)) return null;
            return ForegroundApp.label(context, ForegroundApp.current(context));
        } catch (Throwable t) {
            return null;
        }
    }
}
