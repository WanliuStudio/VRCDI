package com.vrcdevicecheck.osc.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把原始读数折算成展示值。纯逻辑，可单元测试。
 *
 * 重要：sysfs 节点路径与单位因厂商和内核版本差异极大，这里一律按
 * 「多候选路径 + 读不到就返回 null」处理，界面显示"不可用"，不编造数值。
 * 标注「启发式」的地方是社区通行做法，需要在真机上核对。
 */
public final class SnapshotMapper {

    public static final String NA = "不可用";

    /** 设备树地址式的 devfreq 名字，例如 23140000.gpu / 1c00000.mali / gpu / mali0 */
    private static final Pattern DEVICE_TREE_NAME =
            Pattern.compile("^(gpu|mali\\d*|[0-9a-f]+\\.[a-z0-9_.]+)$", Pattern.CASE_INSENSITIVE);

    private SnapshotMapper() {
    }

    public static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    /** 温度读数归一化：>1000 视为毫摄氏度，否则视为摄氏度（启发式）。超出合理范围判为无效。 */
    public static Float normalizeTemperature(Long raw) {
        if (raw == null) return null;
        float celsius = raw > 1000L ? raw / 1000f : raw.floatValue();
        if (celsius < -20f || celsius > 150f) return null;
        return celsius;
    }

    /** 按名称关键字挑 thermal zone，取匹配项里的最高温（启发式：多传感器时最热的最能代表负载） */
    public static Float pickThermal(Map<String, Long> thermal, String[] primary, String[] fallback) {
        Float value = matchThermal(thermal, primary);
        if (value != null) return value;
        return fallback == null ? null : matchThermal(thermal, fallback);
    }

    private static Float matchThermal(Map<String, Long> thermal, String[] keywords) {
        if (thermal == null || keywords == null) return null;
        Float max = null;
        for (Map.Entry<String, Long> entry : thermal.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.US);
            boolean hit = false;
            for (String keyword : keywords) {
                if (name.contains(keyword)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;
            Float value = normalizeTemperature(entry.getValue());
            if (value == null) continue;
            if (max == null || value > max) max = value;
        }
        return max;
    }

    public static Float cpuTemperature(Map<String, Long> thermal) {
        return pickThermal(thermal, new String[]{"cpu"}, new String[]{"soc", "tsens", "cluster"});
    }

    public static Float gpuTemperature(Map<String, Long> thermal, Map<String, String> kv) {
        Long kgsl = parseLong(kv, "gpu.kgsl.temp");
        Float fromKgsl = normalizeTemperature(kgsl);
        if (fromKgsl != null) return fromKgsl;
        return pickThermal(thermal, new String[]{"gpu", "gpuss"}, null);
    }

    /** CPU 当前频率：脚本已取各核心 scaling_cur_freq 的最大值，单位 kHz */
    public static Integer cpuFreqMHz(Map<String, String> kv) {
        Long khz = parseLong(kv, "cpufreq.cur_max");
        if (khz == null) return null;
        return (int) (khz / 1000L);
    }

    /**
     * GPU 频率，统一折算成 MHz。不同节点单位不同，逐个按来源处理。
     * 注意：MTK 的 /proc/gpufreq/gpufreq_opp_dump 是一张表而非单值，解析易错，这里不采用，
     * 读不到就显示"不可用"。
     */
    public static Integer gpuFreqMHz(Map<String, String> kv) {
        Long hz;
        // Adreno：gpuclk 与 devfreq/cur_freq 单位为 Hz
        hz = parseLong(kv, "gpu.kgsl.gpuclk");
        if (hz != null) return (int) (hz / 1_000_000L);
        hz = parseLong(kv, "gpu.kgsl.df.cur");
        if (hz != null) return (int) (hz / 1_000_000L);
        // 通用 devfreq（Mali / Rockchip 等）：Hz
        hz = parseLong(kv, "gpu.df.cur");
        if (hz != null) return (int) (hz / 1_000_000L);
        // Adreno 直接给 MHz 的节点
        Integer mhz = parseInt(kv, "gpu.kgsl.clock_mhz");
        if (mhz != null) return mhz;
        // 三星 Exynos：通常以 MHz 表示（厂商实现差异，按 MHz 处理）
        mhz = parseInt(kv, "gpu.exynos.clock_actual");
        if (mhz != null) return mhz;
        mhz = parseInt(kv, "gpu.exynos.clock");
        if (mhz != null) return mhz;
        return null;
    }

    /** GPU 使用率，返回 0..100 */
    public static Float gpuUsagePct(Map<String, String> kv) {
        // Adreno 直接给百分比
        Float busyPct = parseFloat(kv, "gpu.kgsl.busy_pct");
        if (busyPct != null && busyPct >= 0f && busyPct <= 100f) return busyPct;

        // Adreno gpubusy: "忙碌时间 总时间"
        String busyRaw = kv == null ? null : kv.get("gpu.kgsl.gpubusy");
        if (busyRaw != null) {
            String[] parts = busyRaw.trim().split("\\s+");
            if (parts.length >= 2) {
                Long busy = parseLongText(parts[0]);
                Long total = parseLongText(parts[1]);
                if (busy != null && total != null && total > 0) {
                    float pct = busy * 100f / total;
                    return Math.max(0f, Math.min(100f, pct));
                }
            }
        }

        // devfreq load：形如 "47@200000000Hz"，取前导数字
        String load = kv == null ? null : kv.get("gpu.df.load");
        if (load != null) {
            StringBuilder digits = new StringBuilder();
            for (char c : load.trim().toCharArray()) {
                if (Character.isDigit(c)) digits.append(c);
                else break;
            }
            Integer value = parseIntText(digits.toString());
            if (value != null && value >= 0 && value <= 100) return value.floatValue();
        }

        // Mali kbase / MTK GED 的利用率节点
        for (String key : new String[]{"gpu.kbase.util", "gpu.ged.util"}) {
            Float value = parseFloat(kv, key);
            if (value != null && value >= 0f && value <= 100f) return value;
        }
        return null;
    }

    /**
     * CPU 使用率：两次 /proc/stat 采样做差。
     * 返回 null 表示这是第一次采样，还没有基准。
     * 字段顺序：user nice system idle iowait irq softirq steal ...
     */
    public static Float cpuUsagePct(List<Long> previous, List<Long> current) {
        if (previous == null || current == null || previous.size() < 4 || current.size() < 4) {
            return null;
        }
        long prevTotal = sum(previous);
        long curTotal = sum(current);
        long prevIdle = previous.get(3) + getOrZero(previous, 4);
        long curIdle = current.get(3) + getOrZero(current, 4);
        long deltaTotal = curTotal - prevTotal;
        long deltaIdle = curIdle - prevIdle;
        if (deltaTotal <= 0) return null;
        float pct = (deltaTotal - deltaIdle) * 100f / deltaTotal;
        return Math.max(0f, Math.min(100f, pct));
    }

    private static long sum(List<Long> values) {
        long total = 0;
        for (Long value : values) total += value;
        return total;
    }

    private static long getOrZero(List<Long> values, int index) {
        return index < values.size() ? values.get(index) : 0L;
    }

    public static String gpuName(Map<String, String> kv, Map<String, String> props) {
        // 1) GL_RENDERER：由 GPU 驱动直接返回真实型号（Mali-G57 / Adreno 750），
        //    不需要 root、Shizuku 或任何权限，优先用这个。
        String renderer = kv == null ? null : kv.get("gpu.gl.renderer");
        if (renderer != null && !renderer.trim().isEmpty()) return renderer.trim();

        // 2) 厂商 sysfs 节点里的型号（Mali kbase / MTK）
        String maliInfo = kv == null ? null : kv.get("gpu.mali.gpuinfo");
        if (maliInfo != null && !maliInfo.trim().isEmpty()) return cleanMaliInfo(maliInfo);
        String name = firstNonBlank(
                kv == null ? null : kv.get("gpu.mtk.model"),
                kv == null ? null : kv.get("gpu.kgsl.model"));
        if (name != null) return name;

        // 3) devfreq 的 name 常常只是设备树地址（如 23140000.gpu），不能当型号用
        String dfName = kv == null ? null : kv.get("gpu.df.name");
        if (isRealGpuName(dfName)) return dfName.trim();

        // 4) 退而求其次：用 EGL 实现名推断 GPU 家族（如 adreno / mali）
        String egl = props == null ? null : props.get("ro.hardware.egl");
        if (egl != null && !egl.trim().isEmpty()) {
            String family = egl.split(":")[0].trim();
            if (family.isEmpty()) return null;
            return family.substring(0, 1).toUpperCase(Locale.US) + family.substring(1);
        }
        return null;
    }

    /** "Mali-G57 r0p1 1.1" -> "Mali-G57"；"Mali-G57 MC2 r0p1" -> "Mali-G57 MC2" */
    private static String cleanMaliInfo(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        text = text.replaceAll("\\s+r\\d+p\\d+.*$", "").trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * devfreq 的 name 在 Mali / 展锐 / 瑞芯微设备上经常是设备树地址，例如
     * "23140000.gpu"、"1c00000.mali"，或者干脆就是 "gpu"/"mali0"，
     * 这些都不是型号，必须过滤掉，否则 Chatbox 里会显示成一串地址。
     */
    static boolean isRealGpuName(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.isEmpty()) return false;
        return !DEVICE_TREE_NAME.matcher(text).matches();
    }

    /** 返回 [已用MB, 总量MB]，任一项为 null 表示不可用 */
    public static Long[] ramUsedTotalMb(RawFacts facts, JavaSideInfo java) {
        Long total = facts.memKb.get("MemTotal");
        Long available = facts.memKb.get("MemAvailable");
        if (available == null) available = facts.memKb.get("MemFree");
        if (total != null && available != null && total > 0) {
            return new Long[]{(total - available) / 1024L, total / 1024L};
        }
        return new Long[]{java.ramUsedMb, java.ramTotalMb};
    }

    public static String cpuName(RawFacts facts, JavaSideInfo java) {
        String name = firstNonBlank(
                facts.props.get("ro.soc.model"),
                java == null ? null : java.socModel,
                facts.props.get("ro.board.platform"),
                facts.props.get("ro.hardware"),
                java == null ? null : java.hardware);
        if (name == null) return null;
        // Build.HARDWARE / ro.hardware 在高通机型上只会给 "qcom"，给用户看没有意义
        if ("qcom".equalsIgnoreCase(name)) name = "Qualcomm";
        else if ("mtk".equalsIgnoreCase(name)) name = "MediaTek";

        // 型号前面补上厂商，例如 Unisoc T760 / Qualcomm SM8550
        String vendor = firstNonBlank(
                facts.props.get("ro.soc.manufacturer"),
                java == null ? null : java.socManufacturer);
        if (vendor != null) {
            String lowerName = name.toLowerCase(Locale.US);
            String lowerVendor = vendor.toLowerCase(Locale.US);
            if (!lowerName.equals(lowerVendor) && !lowerName.startsWith(lowerVendor + " ")) {
                return vendor + " " + name;
            }
        }
        return name;
    }

    public static String brand(RawFacts facts, JavaSideInfo java) {
        return brand(facts, java, null);
    }

    /**
     * 品牌：设备表里显式写了 brand 的优先（例如 TYH212U -> 天翼一号），
     * 否则用系统属性，再退回 Build.BRAND。
     */
    public static String brand(RawFacts facts, JavaSideInfo java, DeviceModelTable table) {
        String code = firstNonBlank(facts.props.get("ro.product.model"),
                java == null ? null : java.model);
        String fromTable = (code == null || table == null) ? null : table.lookupBrand(code);
        return firstNonBlank(fromTable,
                facts.props.get("ro.product.brand"),
                facts.props.get("ro.product.manufacturer"),
                java == null ? null : java.brand);
    }

    public static String model(RawFacts facts, JavaSideInfo java) {
        return firstNonBlank(facts.props.get("ro.product.model"), java.model);
    }

    /** 市场名/系列名：优先系统属性，其次本地型号表 */
    public static String seriesName(RawFacts facts, DeviceModelTable table) {
        return seriesName(facts, null, table);
    }

    /**
     * @param java 可以传 null；传了就用 Build.MODEL 兜底，这样没有特权通道时
     *             也能从设备表里查产品名称
     */
    public static String seriesName(RawFacts facts, JavaSideInfo java, DeviceModelTable table) {
        String fromProp = firstNonBlank(
                facts.props.get("ro.product.marketname"),
                facts.props.get("ro.product.vendor.marketname"),
                facts.props.get("ro.product.odm.marketname"),
                facts.props.get("ro.product.oppo.marketname"),
                facts.props.get("ro.product.vivo.marketname"),
                facts.props.get("ro.config.marketing_name"));
        if (fromProp != null) return fromProp;
        String code = firstNonBlank(facts.props.get("ro.product.model"),
                java == null ? null : java.model);
        return (code == null || table == null) ? null : table.lookup(code);
    }

    public static DeviceSnapshot map(RawFacts facts, JavaSideInfo java, List<Long> previousCpuStat,
                                     DeviceModelTable table, String source) {
        return map(facts, java, previousCpuStat, table, source, null);
    }

    public static DeviceSnapshot map(RawFacts facts, JavaSideInfo java, List<Long> previousCpuStat,
                                     DeviceModelTable table, String source, String foregroundApp) {
        Long[] ram = ramUsedTotalMb(facts, java);
        return new DeviceSnapshot(
                brand(facts, java, table),
                model(facts, java),
                seriesName(facts, java, table),
                cpuName(facts, java),
                cpuFreqMHz(facts.kv),
                cpuTemperature(facts.thermal),
                cpuUsagePct(previousCpuStat, facts.cpuStat),
                gpuName(facts.kv, facts.props),
                gpuFreqMHz(facts.kv),
                gpuTemperature(facts.thermal, facts.kv),
                gpuUsagePct(facts.kv),
                ram[0],
                ram[1],
                java.batteryPct,
                java.charging,
                facts.uid,
                new ArrayList<>(facts.rootPaths),
                source,
                foregroundApp);
    }

    private static Long parseLong(Map<String, String> kv, String key) {
        return kv == null ? null : parseLongText(kv.get(key));
    }

    private static Integer parseInt(Map<String, String> kv, String key) {
        return kv == null ? null : parseIntText(kv.get(key));
    }

    private static Float parseFloat(Map<String, String> kv, String key) {
        String raw = kv == null ? null : kv.get(key);
        if (raw == null) return null;
        String text = raw.trim().replace("%", "");
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongText(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
