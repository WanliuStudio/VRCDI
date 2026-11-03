package com.vrcdevicecheck.osc.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class DeviceModelTableTest {

    private static DeviceModelTable sampleTable() {
        return new DeviceModelTable(Arrays.asList(
                new DeviceModelTable.Entry("HED-AL00", DeviceModelTable.MatchType.EXACT, "Pura 80", true),
                new DeviceModelTable.Entry("LMR-AL", DeviceModelTable.MatchType.PREFIX, "Pura 80 Pro", false),
                new DeviceModelTable.Entry("LMR-AL10", DeviceModelTable.MatchType.EXACT, "Pura 80 Ultra", true)));
    }

    @Test
    public void exactMatchWins() {
        assertEquals("Pura 80", sampleTable().lookup("HED-AL00"));
    }

    @Test
    public void exactMatchBeatsPrefixMatch() {
        assertEquals("Pura 80 Ultra", sampleTable().lookup("LMR-AL10"));
    }

    @Test
    public void prefixMatchCoversVariants() {
        assertEquals("Pura 80 Pro", sampleTable().lookup("LMR-AL00"));
    }

    @Test
    public void lookupIsCaseInsensitiveAndTrimmed() {
        assertEquals("Pura 80", sampleTable().lookup("  hed-al00 "));
    }

    @Test
    public void unknownCodeReturnsNullInsteadOfGuessing() {
        assertNull(sampleTable().lookup("W203DS"));
        assertNull(sampleTable().lookup(null));
        assertNull(sampleTable().lookup("   "));
    }

    @Test
    public void longestPatternWinsWithinSameMatchType() {
        DeviceModelTable table = new DeviceModelTable(Arrays.asList(
                new DeviceModelTable.Entry("LMR", DeviceModelTable.MatchType.PREFIX, "宽匹配", false),
                new DeviceModelTable.Entry("LMR-AL", DeviceModelTable.MatchType.PREFIX, "窄匹配", false)));
        assertEquals("窄匹配", table.lookup("LMR-AL00"));
    }

    @Test
    public void emptyTableAlwaysReturnsNull() {
        assertNull(DeviceModelTable.EMPTY.lookup("HED-AL00"));
        assertEquals(0, DeviceModelTable.EMPTY.size());
    }
}
