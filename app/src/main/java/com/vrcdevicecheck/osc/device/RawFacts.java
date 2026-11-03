package com.vrcdevicecheck.osc.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集脚本输出的结构化结果。解析是纯字符串处理，可在 JVM 单元测试中验证。
 */
public class RawFacts {

    /** 系统属性，key 已去掉 "prop." 前缀，例如 ro.soc.model */
    public final Map<String, String> props;
    /** thermal zone 名称 -> 原始读数（通常是毫摄氏度） */
    public final Map<String, Long> thermal;
    /** /proc/meminfo 项 -> kB */
    public final Map<String, Long> memKb;
    /** /proc/stat 首行去掉 "cpu" 标签后的各时间片计数 */
    public final List<Long> cpuStat;
    /** 其余 key|value 原样保留 */
    public final Map<String, String> kv;
    public final List<String> rootPaths;
    public final Integer uid;

    public RawFacts(Map<String, String> props,
                    Map<String, Long> thermal,
                    Map<String, Long> memKb,
                    List<Long> cpuStat,
                    Map<String, String> kv,
                    List<String> rootPaths,
                    Integer uid) {
        this.props = props == null ? Collections.<String, String>emptyMap() : props;
        this.thermal = thermal == null ? Collections.<String, Long>emptyMap() : thermal;
        this.memKb = memKb == null ? Collections.<String, Long>emptyMap() : memKb;
        this.cpuStat = cpuStat == null ? Collections.<Long>emptyList() : cpuStat;
        this.kv = kv == null ? Collections.<String, String>emptyMap() : kv;
        this.rootPaths = rootPaths == null ? Collections.<String>emptyList() : rootPaths;
        this.uid = uid;
    }

    public static RawFacts empty() {
        return new RawFacts(
                new LinkedHashMap<String, String>(),
                new LinkedHashMap<String, Long>(),
                new LinkedHashMap<String, Long>(),
                new ArrayList<Long>(),
                new LinkedHashMap<String, String>(),
                new ArrayList<String>(),
                null);
    }

    public RawFacts withCpuStat(List<Long> newCpuStat) {
        return new RawFacts(props, thermal, memKb, newCpuStat, kv, rootPaths, uid);
    }

    /**
     * 把 EGL/GLES 读到的 GPU 型号塞进 kv（键 gpu.gl.renderer）。
     * 放在这一层而不是 SnapshotMapper 里，是为了让 SnapshotMapper 保持纯函数、可被 JVM 单测。
     */
    public RawFacts withGpuRenderer(String renderer) {
        if (renderer == null || renderer.trim().isEmpty()) return this;
        Map<String, String> merged = new LinkedHashMap<>(kv);
        merged.put("gpu.gl.renderer", renderer.trim());
        return new RawFacts(props, thermal, memKb, cpuStat, merged, rootPaths, uid);
    }
}
