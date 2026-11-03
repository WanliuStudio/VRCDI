package com.vrcdevicecheck.osc.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SnapshotMapperTest {

    private static Map<String, String> kv(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return map;
    }

    private static Map<String, Long> thermal(String... typeValues) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < typeValues.length; i += 2) {
            map.put(typeValues[i], Long.parseLong(typeValues[i + 1]));
        }
        return map;
    }

    // ---------------- CPU 使用率 ----------------

    @Test
    public void cpuUsageIsDeltaOfNonIdleOverDeltaTotal() {
        List<Long> previous = Arrays.asList(100L, 0L, 100L, 800L, 0L, 0L, 0L, 0L);
        List<Long> current = Arrays.asList(200L, 0L, 100L, 850L, 0L, 0L, 0L, 0L);
        Float usage = SnapshotMapper.cpuUsagePct(previous, current);
        // deltaTotal=150, deltaIdle=50 -> (150-50)/150 = 66.7%
        assertEquals(66.7f, usage, 0.1f);
    }

    @Test
    public void cpuUsageIsNullOnFirstSample() {
        assertNull(SnapshotMapper.cpuUsagePct(null, Arrays.asList(1L, 2L, 3L, 4L)));
    }

    @Test
    public void cpuUsageIsNullWhenCountersDidNotAdvance() {
        List<Long> same = Arrays.asList(10L, 0L, 10L, 80L);
        assertNull(SnapshotMapper.cpuUsagePct(same, same));
    }

    // ---------------- 温度 ----------------

    @Test
    public void temperatureNormalizesMilliDegrees() {
        assertEquals(42.3f, SnapshotMapper.normalizeTemperature(42300L), 0.001f);
    }

    @Test
    public void temperatureAcceptsPlainDegrees() {
        assertEquals(42f, SnapshotMapper.normalizeTemperature(42L), 0.001f);
    }

    @Test
    public void temperatureRejectsImplausibleValues() {
        assertNull(SnapshotMapper.normalizeTemperature(200000L));
        assertNull(SnapshotMapper.normalizeTemperature(-50000L));
        assertNull(SnapshotMapper.normalizeTemperature(null));
    }

    @Test
    public void cpuTemperatureFallsBackToSocZone() {
        Float fromCpu = SnapshotMapper.cpuTemperature(thermal("cpu-0-0-usr", "42300"));
        assertEquals(42.3f, fromCpu, 0.001f);

        Float fromSoc = SnapshotMapper.cpuTemperature(thermal("soc_thermal", "38000"));
        assertEquals(38f, fromSoc, 0.001f);

        assertNull(SnapshotMapper.cpuTemperature(thermal("battery", "30000")));
    }

    @Test
    public void gpuTemperaturePrefersKgslNode() {
        Float value = SnapshotMapper.gpuTemperature(thermal("gpuss-0-usr", "40100"),
                kv("gpu.kgsl.temp", "41500"));
        assertEquals(41.5f, value, 0.001f);
    }

    // ---------------- GPU ----------------

    @Test
    public void gpuUsageFromBusyPercentage() {
        assertEquals(37f, SnapshotMapper.gpuUsagePct(kv("gpu.kgsl.busy_pct", "37")), 0.001f);
    }

    @Test
    public void gpuUsageFromGpubusyRatio() {
        assertEquals(25f, SnapshotMapper.gpuUsagePct(kv("gpu.kgsl.gpubusy", "50 200")), 0.001f);
    }

    @Test
    public void gpuUsageFromDevfreqLoadWithFrequencySuffix() {
        assertEquals(47f, SnapshotMapper.gpuUsagePct(kv("gpu.df.load", "47@200000000Hz")), 0.001f);
    }

    @Test
    public void gpuUsageIsNullWhenNoKnownNodeExists() {
        assertNull(SnapshotMapper.gpuUsagePct(kv("gpu.unknown", "1")));
        assertNull(SnapshotMapper.gpuUsagePct(new LinkedHashMap<String, String>()));
    }

    @Test
    public void gpuFrequencyNormalizesHertzNodesToMHz() {
        assertEquals(Integer.valueOf(750), SnapshotMapper.gpuFreqMHz(kv("gpu.kgsl.gpuclk", "750000000")));
        assertEquals(Integer.valueOf(200), SnapshotMapper.gpuFreqMHz(kv("gpu.df.cur", "200000000")));
        assertEquals(Integer.valueOf(750), SnapshotMapper.gpuFreqMHz(kv("gpu.kgsl.clock_mhz", "750")));
        assertNull(SnapshotMapper.gpuFreqMHz(kv("gpu.mtk.opp_dump", "一堆表格文本")));
    }

    @Test
    public void gpuNameFallsBackToEglFamily() {
        assertEquals("Adreno",
                SnapshotMapper.gpuName(new LinkedHashMap<String, String>(), kv("ro.hardware.egl", "adreno")));
        assertEquals("Adreno 750",
                SnapshotMapper.gpuName(kv("gpu.kgsl.model", "Adreno 750"), kv("ro.hardware.egl", "adreno")));
    }

    // ---------------- CPU 频率 / 内存 ----------------

    @Test
    public void cpuFrequencyConvertsKhzToMhz() {
        assertEquals(Integer.valueOf(1800), SnapshotMapper.cpuFreqMHz(kv("cpufreq.cur_max", "1800000")));
        assertNull(SnapshotMapper.cpuFreqMHz(kv()));
    }

    @Test
    public void ramUsesProcMeminfoWhenAvailable() {
        RawFacts facts = new RawFacts(new LinkedHashMap<String, String>(),
                new LinkedHashMap<String, Long>(),
                kvLong("MemTotal", 12288000L, "MemAvailable", 6144000L),
                new ArrayList<Long>(), new LinkedHashMap<String, String>(), new ArrayList<String>(), null);
        JavaSideInfo java = new JavaSideInfo(null, null, null, null, 999L, 888L, 50, false);
        Long[] ram = SnapshotMapper.ramUsedTotalMb(facts, java);
        assertEquals(Long.valueOf(6000L), ram[0]);
        assertEquals(Long.valueOf(12000L), ram[1]);
    }

    @Test
    public void ramFallsBackToAndroidApiValues() {
        RawFacts facts = RawFacts.empty();
        JavaSideInfo java = new JavaSideInfo(null, null, null, null, 12000L, 6000L, 50, false);
        Long[] ram = SnapshotMapper.ramUsedTotalMb(facts, java);
        assertEquals(Long.valueOf(6000L), ram[0]);
        assertEquals(Long.valueOf(12000L), ram[1]);
    }

    // ---------------- 型号名 ----------------

    @Test
    public void seriesNamePrefersSystemPropertyOverLocalTable() {
        RawFacts facts = new RawFacts(kv("ro.product.model", "HED-AL00", "ro.product.marketname", "HUAWEI Pura 80"),
                new LinkedHashMap<String, Long>(), new LinkedHashMap<String, Long>(), new ArrayList<Long>(),
                new LinkedHashMap<String, String>(), new ArrayList<String>(), null);
        DeviceModelTable table = new DeviceModelTable(Arrays.asList(
                new DeviceModelTable.Entry("HED-AL00", DeviceModelTable.MatchType.EXACT, "表里的名字", true)));
        assertEquals("HUAWEI Pura 80", SnapshotMapper.seriesName(facts, table));
    }

    @Test
    public void seriesNameFallsBackToLocalTable() {
        RawFacts facts = new RawFacts(kv("ro.product.model", "HED-AL00"),
                new LinkedHashMap<String, Long>(), new LinkedHashMap<String, Long>(), new ArrayList<Long>(),
                new LinkedHashMap<String, String>(), new ArrayList<String>(), null);
        DeviceModelTable table = new DeviceModelTable(Arrays.asList(
                new DeviceModelTable.Entry("HED-AL00", DeviceModelTable.MatchType.EXACT, "Pura 80", true)));
        assertEquals("Pura 80", SnapshotMapper.seriesName(facts, table));
    }

    @Test
    public void firstNonBlankSkipsNullAndWhitespace() {
        assertEquals("b", SnapshotMapper.firstNonBlank(null, "  ", "b", "c"));
        assertNull(SnapshotMapper.firstNonBlank(null, "   "));
    }

    private static Map<String, Long> kvLong(String k1, Long v1, String k2, Long v2) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    @Test
    public void mapperNeverInventsValuesForMissingNodes() {
        DeviceSnapshot snapshot = SnapshotMapper.map(
                RawFacts.empty(),
                new JavaSideInfo("zte", "W203DS", null, null, 4096L, 2048L, 33, false),
                null,
                DeviceModelTable.EMPTY,
                "无特权");
        assertNull(snapshot.cpuFreqMHz);
        assertNull(snapshot.cpuTempC);
        assertNull(snapshot.cpuUsagePct);
        assertNull(snapshot.gpuName);
        assertEquals("zte", snapshot.brand);
        assertEquals("W203DS", snapshot.model);
        assertTrue(snapshot.ramTotalMb == 4096L);
    }
}
