package com.vrcdevicecheck.osc.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 型号代码 -> 品牌 / 传播名（系列名）映射。
 *
 * 查找逻辑是纯函数，可单元测试；JSON 加载放在 {@link DeviceModelTables}，
 * 因为 org.json 在 JVM 单元测试里不可用。
 *
 * 关于表数据：内置表里华为 Pura 80 系列三条来自华为官方产品页，
 * 其余条目在 assets/device_models.json 中标了 verified=false，需自行核实后再依赖。
 * 想覆盖自己的机型，把同名 JSON 放到应用私有目录 files/device_models.json 即可，
 * 该文件优先级高于内置表，无需重新编译。
 */
public class DeviceModelTable {

    public enum MatchType { EXACT, PREFIX }

    public static final class Entry {
        public final String model;
        /** 设备表里写的品牌；为空表示不覆盖系统品牌 */
        public final String brand;
        public final MatchType match;
        public final String series;
        public final boolean verified;

        public Entry(String model, MatchType match, String series, boolean verified) {
            this(model, null, match, series, verified);
        }

        public Entry(String model, String brand, MatchType match, String series, boolean verified) {
            this.model = model;
            this.brand = brand;
            this.match = match;
            this.series = series;
            this.verified = verified;
        }
    }

    public static final DeviceModelTable EMPTY =
            new DeviceModelTable(Collections.<Entry>emptyList());

    private final List<Entry> entries;

    public DeviceModelTable(List<Entry> entries) {
        this.entries = entries == null
                ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** 精确匹配优先于前缀匹配；同类里取模式串最长的一条（更具体的优先） */
    public String lookup(String modelCode) {
        Entry entry = find(modelCode);
        return entry == null ? null : entry.series;
    }

    /** 只查品牌；没写品牌的条目返回 null，调用方继续用系统品牌 */
    public String lookupBrand(String modelCode) {
        Entry entry = find(modelCode);
        return entry == null ? null : entry.brand;
    }

    private Entry find(String modelCode) {
        if (modelCode == null) return null;
        String code = modelCode.trim().toUpperCase(Locale.US);
        if (code.isEmpty()) return null;

        Entry bestExact = null;
        for (Entry entry : entries) {
            if (entry.match != MatchType.EXACT) continue;
            if (!entry.model.toUpperCase(Locale.US).equals(code)) continue;
            if (bestExact == null || entry.model.length() > bestExact.model.length()) bestExact = entry;
        }
        if (bestExact != null) return bestExact;

        Entry bestPrefix = null;
        for (Entry entry : entries) {
            if (entry.match != MatchType.PREFIX) continue;
            if (!code.startsWith(entry.model.toUpperCase(Locale.US))) continue;
            if (bestPrefix == null || entry.model.length() > bestPrefix.model.length()) bestPrefix = entry;
        }
        return bestPrefix;
    }

    public int size() {
        return entries.size();
    }

    /** 只读视图；导入设备表时用来做合并 */
    public List<Entry> entries() {
        return entries;
    }
}
