package com.vrcdevicecheck.osc.device;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一次采集的设备快照。字段为 null 表示"读不到"，界面显示"不可用"，绝不填假数据。
 * 数值统一用包装类型以区分 null 与 0。
 */
public class DeviceSnapshot {

    public final String brand;
    public final String model;
    /** 市场名/系列名，例如 HED-AL00 -> Pura 80 */
    public final String seriesName;

    public final String cpuName;
    public final Integer cpuFreqMHz;
    public final Float cpuTempC;
    public final Float cpuUsagePct;

    public final String gpuName;
    public final Integer gpuFreqMHz;
    public final Float gpuTempC;
    public final Float gpuUsagePct;

    public final Long ramUsedMb;
    public final Long ramTotalMb;

    public final Integer batteryPct;
    public final boolean charging;

    /** 执行采集时的 uid：0=root，2000=shell(Shizuku)，null=无特权 */
    public final Integer uid;
    public final List<String> rootPaths;
    /** 本次数据来自哪个通道，用于界面提示 */
    public final String source;
    /** 采集那一刻的前台应用名；没有「使用情况访问」权限时为 null（界面显示"不可用"） */
    public final String foregroundApp;

    public DeviceSnapshot(String brand, String model, String seriesName,
                          String cpuName, Integer cpuFreqMHz, Float cpuTempC, Float cpuUsagePct,
                          String gpuName, Integer gpuFreqMHz, Float gpuTempC, Float gpuUsagePct,
                          Long ramUsedMb, Long ramTotalMb,
                          Integer batteryPct, boolean charging,
                          Integer uid, List<String> rootPaths, String source) {
        this(brand, model, seriesName,
                cpuName, cpuFreqMHz, cpuTempC, cpuUsagePct,
                gpuName, gpuFreqMHz, gpuTempC, gpuUsagePct,
                ramUsedMb, ramTotalMb,
                batteryPct, charging,
                uid, rootPaths, source, null);
    }

    public DeviceSnapshot(String brand, String model, String seriesName,
                          String cpuName, Integer cpuFreqMHz, Float cpuTempC, Float cpuUsagePct,
                          String gpuName, Integer gpuFreqMHz, Float gpuTempC, Float gpuUsagePct,
                          Long ramUsedMb, Long ramTotalMb,
                          Integer batteryPct, boolean charging,
                          Integer uid, List<String> rootPaths, String source,
                          String foregroundApp) {
        this.brand = brand;
        this.model = model;
        this.seriesName = seriesName;
        this.cpuName = cpuName;
        this.cpuFreqMHz = cpuFreqMHz;
        this.cpuTempC = cpuTempC;
        this.cpuUsagePct = cpuUsagePct;
        this.gpuName = gpuName;
        this.gpuFreqMHz = gpuFreqMHz;
        this.gpuTempC = gpuTempC;
        this.gpuUsagePct = gpuUsagePct;
        this.ramUsedMb = ramUsedMb;
        this.ramTotalMb = ramTotalMb;
        this.batteryPct = batteryPct;
        this.charging = charging;
        this.uid = uid;
        this.rootPaths = rootPaths == null ? Collections.<String>emptyList() : rootPaths;
        this.source = source;
        this.foregroundApp = foregroundApp;
    }

    public boolean hasPrivilegedData() {
        return uid != null;
    }
}
