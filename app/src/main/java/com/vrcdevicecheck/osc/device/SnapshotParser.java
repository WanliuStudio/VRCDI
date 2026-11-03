package com.vrcdevicecheck.osc.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析采集脚本的输出。
 *
 * 脚本每行输出 key|value，分节标记（@BEGIN/@END）忽略；
 * 只在第一个 '|' 处切分，value 里允许再出现 '|'。
 */
public final class SnapshotParser {

    private SnapshotParser() {
    }

    public static RawFacts parse(String output) {
        Map<String, String> props = new LinkedHashMap<>();
        Map<String, Long> thermal = new LinkedHashMap<>();
        Map<String, Long> mem = new LinkedHashMap<>();
        Map<String, String> kv = new LinkedHashMap<>();
        List<String> rootPaths = new ArrayList<>();
        List<Long> cpuStat = new ArrayList<>();
        Integer uid = null;

        if (output == null) {
            return new RawFacts(props, thermal, mem, cpuStat, kv, rootPaths, null);
        }

        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("@")) continue;
            int sep = line.indexOf('|');
            if (sep <= 0) continue;
            String key = line.substring(0, sep);
            String value = line.substring(sep + 1);

            if (key.startsWith("prop.")) {
                props.put(key.substring("prop.".length()), value);
            } else if (key.startsWith("thermal.")) {
                Long parsed = parseLong(value);
                if (parsed != null) thermal.put(key.substring("thermal.".length()), parsed);
            } else if (key.startsWith("mem.")) {
                Long parsed = parseLong(value);
                if (parsed != null) mem.put(key.substring("mem.".length()), parsed);
            } else if ("stat.cpu".equals(key)) {
                cpuStat = parseCpuStat(value);
            } else if ("root.uid".equals(key)) {
                uid = parseInt(value.trim());
            } else if ("root.path".equals(key)) {
                if (!value.trim().isEmpty()) rootPaths.add(value.trim());
            } else {
                kv.put(key, value);
            }
        }

        return new RawFacts(props, thermal, mem, cpuStat, kv, rootPaths, uid);
    }

    /** /proc/stat 首行形如 "cpu  1234 56 789 ..."，丢掉标签后取各时间片计数 */
    static List<Long> parseCpuStat(String value) {
        List<Long> result = new ArrayList<>();
        if (value == null) return result;
        for (String token : value.trim().split("\\s+")) {
            Long parsed = parseLong(token);
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    private static Long parseLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
