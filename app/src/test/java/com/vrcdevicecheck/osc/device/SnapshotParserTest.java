package com.vrcdevicecheck.osc.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class SnapshotParserTest {

    private static final String SAMPLE =
            "@BEGIN\n"
                    + "prop.ro.soc.model|Kirin 9010S\n"
                    + "prop.ro.product.model|HED-AL00\n"
                    + "prop.ro.product.brand|HUAWEI\n"
                    + "prop.ro.hardware.egl|maleoon\n"
                    + "stat.cpu|cpu  1000 20 300 5000 40 0 0 0 0 0\n"
                    + "mem.MemTotal|12000000\n"
                    + "mem.MemAvailable|6000000\n"
                    + "thermal.cpu-0-0-usr|42300\n"
                    + "thermal.gpuss-0-usr|40100\n"
                    + "cpufreq.cur_max|1800000\n"
                    + "gpu.kgsl.model|Adreno 750\n"
                    + "gpu.kgsl.busy_pct|37\n"
                    + "root.uid|2000\n"
                    + "root.path|/data/adb/magisk\n"
                    + "@END\n";

    @Test
    public void parsesPropsWithoutPropPrefix() {
        RawFacts facts = SnapshotParser.parse(SAMPLE);
        assertEquals("Kirin 9010S", facts.props.get("ro.soc.model"));
        assertEquals("HED-AL00", facts.props.get("ro.product.model"));
        assertEquals("maleoon", facts.props.get("ro.hardware.egl"));
    }

    @Test
    public void parsesThermalZonesAsLong() {
        RawFacts facts = SnapshotParser.parse(SAMPLE);
        assertEquals(Long.valueOf(42300L), facts.thermal.get("cpu-0-0-usr"));
        assertEquals(Long.valueOf(40100L), facts.thermal.get("gpuss-0-usr"));
    }

    @Test
    public void parsesMeminfoInKb() {
        RawFacts facts = SnapshotParser.parse(SAMPLE);
        assertEquals(Long.valueOf(12000000L), facts.memKb.get("MemTotal"));
        assertEquals(Long.valueOf(6000000L), facts.memKb.get("MemAvailable"));
    }

    @Test
    public void parsesCpuStatWithoutLabel() {
        RawFacts facts = SnapshotParser.parse(SAMPLE);
        assertEquals(Arrays.asList(1000L, 20L, 300L, 5000L, 40L, 0L, 0L, 0L, 0L, 0L), facts.cpuStat);
    }

    @Test
    public void putsUnknownKeysIntoKv() {
        RawFacts facts = SnapshotParser.parse(SAMPLE);
        assertEquals("1800000", facts.kv.get("cpufreq.cur_max"));
        assertEquals("Adreno 750", facts.kv.get("gpu.kgsl.model"));
        assertEquals("37", facts.kv.get("gpu.kgsl.busy_pct"));
    }

    @Test
    public void parsesUidAndRootPaths() {
        RawFacts facts = SnapshotParser.parse(SAMPLE);
        assertEquals(Integer.valueOf(2000), facts.uid);
        assertEquals(Arrays.asList("/data/adb/magisk"), facts.rootPaths);
    }

    @Test
    public void skipsSectionMarkersAndMalformedLines() {
        RawFacts facts = SnapshotParser.parse("@BEGIN\nnodelimiter\n|novaluekey\n@END\n");
        assertTrue(facts.props.isEmpty());
        assertTrue(facts.kv.isEmpty());
        assertNull(facts.uid);
    }

    @Test
    public void nullOutputYieldsEmptyFacts() {
        RawFacts facts = SnapshotParser.parse(null);
        assertTrue(facts.props.isEmpty());
        assertTrue(facts.cpuStat.isEmpty());
    }

    @Test
    public void valueMayContainPipeCharacter() {
        RawFacts facts = SnapshotParser.parse("gpu.some|a|b\n");
        assertEquals("a|b", facts.kv.get("gpu.some"));
    }
}
