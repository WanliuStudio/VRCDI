package com.vrcdevicecheck.osc.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class LrcParserTest {

    @Test
    public void parsesTimedLinesAndPicksCurrentOne() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:01.00]第一句\n[00:03.50]第二句");

        assertEquals(2, lines.size());
        assertEquals(1000, lines.get(0).timeMs);
        assertEquals(3500, lines.get(1).timeMs);
        assertNull(LrcParser.lineAt(lines, 500));
        assertEquals("第一句", LrcParser.lineAt(lines, 1500));
        assertEquals("第一句", LrcParser.lineAt(lines, 3499));
        assertEquals("第二句", LrcParser.lineAt(lines, 3500));
    }

    @Test
    public void supportsMultipleTimestampsOnOneLine() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:10.00][01:20.00]副歌");

        assertEquals(2, lines.size());
        assertEquals("副歌", LrcParser.lineAt(lines, 80000));
        assertEquals("副歌", LrcParser.lineAt(lines, 85000));
    }

    @Test
    public void appliesGlobalOffset() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[offset:-500]\n[00:01.00]歌词");

        assertEquals(1, lines.size());
        assertEquals(500, lines.get(0).timeMs);
    }

    @Test
    public void stripsWordTimingTags() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:01.00]<00:01.00>你<00:01.50>好");

        assertEquals("你好", lines.get(0).text);
    }

    @Test
    public void ignoresCreditsAndEmptyLines() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:00.00]作词 : 方文山\n[00:00.50]作曲：周杰伦\n[00:01.00]\n[00:05.00]正文");

        assertEquals(1, lines.size());
        assertEquals("正文", lines.get(0).text);
    }

    @Test
    public void parsesHundredthsAndMillisFractions() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:12.3]甲\n[00:12.34]乙\n[00:12.345]丙");

        assertEquals(12300, lines.get(0).timeMs);
        assertEquals(12340, lines.get(1).timeMs);
        assertEquals(12345, lines.get(2).timeMs);
    }
}