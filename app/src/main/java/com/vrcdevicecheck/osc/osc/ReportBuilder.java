package com.vrcdevicecheck.osc.osc;

import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把设备快照拼成 Chatbox 文本。纯逻辑，可单元测试。
 *
 * 硬约束：VRChat Chatbox 单条上限 144 字符（VRChat Wiki「Chatbox」，2026-04-15）。
 * 超长时按 {@link ReportField} 的声明顺序从末尾开始丢字段（先丢「前台应用」，再丢 GPU 相关项），
 * 并把被丢掉的字段回传给界面如实提示，绝不静默截断。
 */
public final class ReportBuilder {

    public static final int CHATBOX_LIMIT = 144;
    private static final String NA = "不可用";

    private ReportBuilder() {
    }

    /** 一次渲染结果 */
    public static final class Report {
        public final String text;
        public final List<ReportField> included;
        public final List<ReportField> dropped;
        public final boolean hardTruncated;

        Report(String text, List<ReportField> included, List<ReportField> dropped, boolean hardTruncated) {
            this.text = text;
            this.included = included;
            this.dropped = dropped;
            this.hardTruncated = hardTruncated;
        }

        public int charCount() {
            return text.length();
        }

        public boolean overLimit() {
            return hardTruncated || !dropped.isEmpty();
        }

        /** 界面提示文案 */
        public String limitHint() {
            if (hardTruncated) return "已超过 144 字符，末尾被强制截断";
            if (dropped.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("超过 144 字符，已自动省略：");
            for (int i = 0; i < dropped.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(dropped.get(i).label);
            }
            return sb.toString();
        }
    }

    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly) {
        return build(snapshot, fields, asciiOnly, true);
    }

    /**
     * @param useProductName true 时「设备型号」用设备表里的产品名称（HUAWEI Pura 80），
     *                       false 时用真实型号（HUAWEI HED-AL00）
     */
    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly,
                               boolean useProductName) {
        List<ReportField> ordered = new ArrayList<>();
        for (ReportField field : ReportField.values()) {
            if (fields != null && fields.contains(field)) ordered.add(field);
        }
        if (ordered.isEmpty()) return new Report("", ordered, ordered, false);

        List<ReportField> kept = new ArrayList<>(ordered);
        String text = render(snapshot, kept, asciiOnly, useProductName);

        while (text.length() > CHATBOX_LIMIT && kept.size() > 1) {
            kept.remove(kept.size() - 1);
            text = render(snapshot, kept, asciiOnly, useProductName);
        }

        boolean hard = false;
        if (text.length() > CHATBOX_LIMIT) {
            text = text.substring(0, CHATBOX_LIMIT - 1) + "…";
            hard = true;
        }

        List<ReportField> dropped = new ArrayList<>();
        for (ReportField field : ordered) {
            if (!kept.contains(field)) dropped.add(field);
        }
        return new Report(text, kept, dropped, hard);
    }

    /** 分组渲染：同组字段共用一个标签，既省字符也更好读 */
    public static String render(DeviceSnapshot snapshot, List<ReportField> fields, boolean asciiOnly) {
        return render(snapshot, fields, asciiOnly, true);
    }

    public static String render(DeviceSnapshot snapshot, List<ReportField> fields, boolean asciiOnly,
                                boolean useProductName) {
        List<String> chunks = new ArrayList<>(6);

        // 设备：品牌 + 型号（可选真实型号或产品名称）
        List<String> device = new ArrayList<>(2);
        if (fields.contains(ReportField.BRAND)) device.add(orNa(snapshot.brand));
        if (fields.contains(ReportField.MODEL)) {
            String modelText = useProductName && snapshot.seriesName != null
                    ? snapshot.seriesName
                    : snapshot.model;
            device.add(orNa(modelText));
        }
        if (!device.isEmpty()) chunks.add(join(device, " "));

        // CPU
        List<String> cpu = new ArrayList<>(5);
        boolean anyCpu = false;
        if (fields.contains(ReportField.CPU_NAME)) {
            anyCpu = true;
            cpu.add(orNa(snapshot.cpuName));
        }
        if (fields.contains(ReportField.CPU_FREQ)) {
            anyCpu = true;
            cpu.add(freq(snapshot.cpuFreqMHz));
        }
        if (fields.contains(ReportField.CPU_TEMP)) {
            anyCpu = true;
            cpu.add(temp(snapshot.cpuTempC, asciiOnly));
        }
        if (fields.contains(ReportField.CPU_USAGE)) {
            anyCpu = true;
            cpu.add(pct(snapshot.cpuUsagePct));
        }
        if (anyCpu) {
            cpu.add(0, "CPU");
            chunks.add(join(cpu, " "));
        }

        // GPU
        List<String> gpu = new ArrayList<>(5);
        boolean anyGpu = false;
        if (fields.contains(ReportField.GPU_NAME)) {
            anyGpu = true;
            gpu.add(orNa(snapshot.gpuName));
        }
        if (fields.contains(ReportField.GPU_FREQ)) {
            anyGpu = true;
            gpu.add(freq(snapshot.gpuFreqMHz));
        }
        if (fields.contains(ReportField.GPU_TEMP)) {
            anyGpu = true;
            gpu.add(temp(snapshot.gpuTempC, asciiOnly));
        }
        if (fields.contains(ReportField.GPU_USAGE)) {
            anyGpu = true;
            gpu.add(pct(snapshot.gpuUsagePct));
        }
        if (anyGpu) {
            gpu.add(0, "GPU");
            chunks.add(join(gpu, " "));
        }

        if (fields.contains(ReportField.RAM)) {
            chunks.add(ram(snapshot.ramUsedMb, snapshot.ramTotalMb));
        }

        if (fields.contains(ReportField.BATTERY)) {
            String battery = snapshot.batteryPct == null ? NA : snapshot.batteryPct + "%";
            // 严格按需求：只有真正在充电时才追加"充电中"，充满或未充电都不显示
            if (snapshot.charging) {
                battery += asciiOnly ? " Charging" : " 充电中";
            }
            chunks.add(battery);
        }

        if (fields.contains(ReportField.FOREGROUND_APP)) {
            chunks.add((asciiOnly ? "App " : "前台 ") + orNa(snapshot.foregroundApp));
        }

        return join(chunks, " ").trim();
    }

    private static String orNa(String value) {
        return value == null || value.trim().isEmpty() ? NA : value.trim();
    }

    private static String freq(Integer mhz) {
        if (mhz == null) return NA;
        if (mhz >= 1000) return String.format(Locale.US, "%.2fGHz", mhz / 1000f);
        return mhz + "MHz";
    }

    private static String temp(Float celsius, boolean asciiOnly) {
        if (celsius == null) return NA;
        return String.format(Locale.US, asciiOnly ? "%.1fC" : "%.1f℃", celsius);
    }

    private static String pct(Float value) {
        if (value == null) return NA;
        return String.format(Locale.US, "%.0f%%", value);
    }

    private static String ram(Long usedMb, Long totalMb) {
        if (usedMb == null || totalMb == null || totalMb <= 0) return "RAM " + NA;
        return String.format(Locale.US, "RAM %.1f/%.1fGB", usedMb / 1024f, totalMb / 1024f);
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
