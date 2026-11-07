package com.vrcdevicecheck.osc.osc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.device.TestSnapshots;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public class ReportBuilderTest {

    @Test
    public void rendersGroupsInSeparateLines() {
        DeviceSnapshot snapshot = TestSnapshots.full();
        Set<ReportField> fields = EnumSet.of(
                ReportField.BRAND, ReportField.MODEL,
                ReportField.CPU_NAME, ReportField.CPU_FREQ, ReportField.CPU_TEMP, ReportField.CPU_USAGE,
                ReportField.RAM, ReportField.BATTERY);
        String text = ReportBuilder.build(snapshot, fields, false).text;
        assertEquals("HUAWEI Pura 80\n"
                + "CPU: Kirin 9010S 1.80GHz 42.3℃ 23%\n"
                + "RAM: 6.1/11.7GB 52%\n"
                + "电量: 78% 充电中", text);
    }

    @Test
    public void chargingLabelOnlyAppearsWhenActuallyCharging() {
        DeviceSnapshot charging = TestSnapshots.full();
        assertTrue(ReportBuilder.build(charging, EnumSet.of(ReportField.BATTERY), false).text.endsWith("充电中"));

        DeviceSnapshot notCharging = TestSnapshots.notCharging(66);
        String text = ReportBuilder.build(notCharging, EnumSet.of(ReportField.BATTERY), false).text;
        assertEquals("电量: 66%", text);
        assertFalse(text.contains("充电中"));
    }

    @Test
    public void asciiOnlyReplacesNonAsciiLabels() {
        DeviceSnapshot snapshot = TestSnapshots.full();
        String text = ReportBuilder.build(snapshot, EnumSet.of(ReportField.CPU_TEMP, ReportField.BATTERY), true).text;
        assertTrue(text.contains("42.3C"));
        assertTrue(text.contains("Charging"));
        assertFalse(text.contains("℃"));
        assertFalse(text.contains("充电中"));
    }

    @Test
    public void unavailableValuesRenderPlaceholderNeverFakeNumbers() {
        String text = ReportBuilder.build(TestSnapshots.empty(), EnumSet.allOf(ReportField.class), false).text;
        assertTrue(text.contains("不可用"));
        assertFalse(text.contains("0%"));
    }

    @Test
    public void noFieldsSelectedProducesEmptyText() {
        ReportBuilder.Report report =
                ReportBuilder.build(TestSnapshots.full(), EnumSet.noneOf(ReportField.class), false);
        assertEquals("", report.text);
    }

    @Test
    public void longTextDropsWholeLinesFromTheEnd() {
        DeviceSnapshot snapshot = new DeviceSnapshot(
                "HUAWEI", "HED-AL00", "Pura 80",
                "Kirin 9010S 超高性能旗舰版移动处理器平台", 1800, 42.3f, 23f,
                "Maleoon 910 超高性能图形处理器单元", 750, 40.1f, 12f,
                6200L, 12000L,
                78, true,
                2000, new java.util.ArrayList<String>(), "Shizuku");

        ReportBuilder.Report report = ReportBuilder.build(snapshot, EnumSet.allOf(ReportField.class), false);

        assertTrue("长度必须不超过 144，实际 " + report.charCount(),
                report.charCount() <= ReportBuilder.CHATBOX_LIMIT);
        assertFalse(report.dropped.isEmpty());
        // 第一行永远保留
        assertTrue(report.included.contains(ReportField.BRAND));
        // 最后一段（VRChat 帧率）最先被整段丢掉
        assertTrue(report.dropped.contains(ReportField.VRCHAT_FPS));
        assertTrue(report.overLimit());
        assertTrue(report.limitHint().contains("省略"));
    }

    @Test
    public void shortTextReportsNothingDropped() {
        ReportBuilder.Report report = ReportBuilder.build(
                TestSnapshots.notCharging(50), EnumSet.of(ReportField.BATTERY), false);
        assertTrue(report.dropped.isEmpty());
        assertNull(report.limitHint());
        assertFalse(report.overLimit());
    }

    @Test
    public void gpuGroupGetsItsOwnLabel() {
        String text = ReportBuilder.render(TestSnapshots.full(),
                Arrays.asList(ReportField.GPU_NAME, ReportField.GPU_USAGE), false);
        assertEquals("GPU: Maleoon 910 12%", text);
    }
}
