package com.vrcdevicecheck.osc.osc;

import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把设备快照拼成 Chatbox 文本。纯逻辑，可单元测试。
 *
 * 输出是分段的（每段一行），形如：
 *
 *   OPPO PCAT00
 *   CPU: Qualcomm SM8550 2.21GHz 46.1℃ 10%
 *   GPU: Adreno 740 不可用 41.3℃ 不可用
 *   RAM: 3.9/5.5GB 71%
 *   电量: 45% 充电中
 *   App: VRCDI（本应用）
 *
 * 硬约束：VRChat Chatbox 单条上限 144 字符（含换行）。超长时从最后一段开始整段丢弃，
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

    /** 一行文本 + 这一行包含的字段（用来算"丢了什么"） */
    private static final class Line {
        final String text;
        final List<ReportField> fields;

        Line(String text, List<ReportField> fields) {
            this.text = text;
            this.fields = fields;
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

        List<Line> lines = buildLines(snapshot, ordered, asciiOnly, useProductName);
        while (lineText(lines).length() > CHATBOX_LIMIT && lines.size() > 1) {
            lines.remove(lines.size() - 1);
        }

        String text = lineText(lines);
        boolean hard = false;
        if (text.length() > CHATBOX_LIMIT) {
            text = text.substring(0, CHATBOX_LIMIT - 1) + "…";
            hard = true;
        }

        Set<ReportField> includedSet = new LinkedHashSet<>();
        for (Line line : lines) includedSet.addAll(line.fields);

        List<ReportField> kept = new ArrayList<>();
        List<ReportField> dropped = new ArrayList<>();
        for (ReportField field : ordered) {
            if (includedSet.contains(field)) kept.add(field);
            else dropped.add(field);
        }
        return new Report(text, kept, dropped, hard);
    }

    /** 分段渲染，返回用换行连接的多行文本 */
    public static String render(DeviceSnapshot snapshot, List<ReportField> fields, boolean asciiOnly) {
        return render(snapshot, fields, asciiOnly, true);
    }

    public static String render(DeviceSnapshot snapshot, List<ReportField> fields, boolean asciiOnly,
                                boolean useProductName) {
        return lineText(buildLines(snapshot, fields, asciiOnly, useProductName));
    }

    private static List<Line> buildLines(DeviceSnapshot snapshot, List<ReportField> fields,
                                         boolean asciiOnly, boolean useProductName) {
        List<Line> lines = new ArrayList<>(6);

        // 第一行：品牌 + 型号（真实型号或产品名称）
        List<ReportField> deviceFields = new ArrayList<>(2);
        StringBuilder device = new StringBuilder();
        if (fields.contains(ReportField.BRAND)) {
            append(device, orNa(snapshot.brand));
            deviceFields.add(ReportField.BRAND);
        }
        if (fields.contains(ReportField.MODEL)) {
            String modelText = useProductName && snapshot.seriesName != null
                    ? snapshot.seriesName
                    : snapshot.model;
            append(device, orNa(modelText));
            deviceFields.add(ReportField.MODEL);
        }
        if (device.length() > 0) lines.add(new Line(device.toString(), deviceFields));

        // CPU 行
        List<ReportField> cpuFields = new ArrayList<>(4);
        StringBuilder cpu = new StringBuilder();
        if (fields.contains(ReportField.CPU_NAME)) {
            append(cpu, orNa(snapshot.cpuName));
            cpuFields.add(ReportField.CPU_NAME);
        }
        if (fields.contains(ReportField.CPU_FREQ)) {
            append(cpu, freq(snapshot.cpuFreqMHz));
            cpuFields.add(ReportField.CPU_FREQ);
        }
        if (fields.contains(ReportField.CPU_TEMP)) {
            append(cpu, temp(snapshot.cpuTempC, asciiOnly));
            cpuFields.add(ReportField.CPU_TEMP);
        }
        if (fields.contains(ReportField.CPU_USAGE)) {
            append(cpu, pct(snapshot.cpuUsagePct));
            cpuFields.add(ReportField.CPU_USAGE);
        }
        if (!cpuFields.isEmpty()) lines.add(new Line("CPU: " + cpu, cpuFields));

        // GPU 行
        List<ReportField> gpuFields = new ArrayList<>(4);
        StringBuilder gpu = new StringBuilder();
        if (fields.contains(ReportField.GPU_NAME)) {
            append(gpu, orNa(snapshot.gpuName));
            gpuFields.add(ReportField.GPU_NAME);
        }
        if (fields.contains(ReportField.GPU_FREQ)) {
            append(gpu, freq(snapshot.gpuFreqMHz));
            gpuFields.add(ReportField.GPU_FREQ);
        }
        if (fields.contains(ReportField.GPU_TEMP)) {
            append(gpu, temp(snapshot.gpuTempC, asciiOnly));
            gpuFields.add(ReportField.GPU_TEMP);
        }
        if (fields.contains(ReportField.GPU_USAGE)) {
            append(gpu, pct(snapshot.gpuUsagePct));
            gpuFields.add(ReportField.GPU_USAGE);
        }
        if (!gpuFields.isEmpty()) lines.add(new Line("GPU: " + gpu, gpuFields));

        // RAM 行
        if (fields.contains(ReportField.RAM)) {
            List<ReportField> ramFields = new ArrayList<>(1);
            ramFields.add(ReportField.RAM);
            lines.add(new Line(ramLine(snapshot.ramUsedMb, snapshot.ramTotalMb), ramFields));
        }

        // 电量行
        if (fields.contains(ReportField.BATTERY)) {
            String battery = snapshot.batteryPct == null ? NA : snapshot.batteryPct + "%";
            if (snapshot.charging) {
                battery += asciiOnly ? " Charging" : " 充电中";
            }
            List<ReportField> batteryFields = new ArrayList<>(1);
            batteryFields.add(ReportField.BATTERY);
            lines.add(new Line((asciiOnly ? "Battery: " : "电量: ") + battery, batteryFields));
        }

        // 前台应用行
        if (fields.contains(ReportField.FOREGROUND_APP)) {
            List<ReportField> appFields = new ArrayList<>(1);
            appFields.add(ReportField.FOREGROUND_APP);
            lines.add(new Line("App: " + orNa(snapshot.foregroundApp), appFields));
        }

        // 帧率行
        if (fields.contains(ReportField.VRCHAT_FPS)) {
            List<ReportField> fpsFields = new ArrayList<>(1);
            fpsFields.add(ReportField.VRCHAT_FPS);
            String fps = snapshot.vrchatFps == null
                    ? NA
                    : String.format(Locale.US, "%.0f", snapshot.vrchatFps);
            lines.add(new Line("FPS: " + fps, fpsFields));
        }

        return lines;
    }

    private static String lineText(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).text);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(part);
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

    private static String ramLine(Long usedMb, Long totalMb) {
        if (usedMb == null || totalMb == null || totalMb <= 0) return "RAM: " + NA;
        int pct = Math.round(usedMb * 100f / totalMb);
        return String.format(Locale.US, "RAM: %.1f/%.1fGB %d%%",
                usedMb / 1024f, totalMb / 1024f, pct);
    }
}
