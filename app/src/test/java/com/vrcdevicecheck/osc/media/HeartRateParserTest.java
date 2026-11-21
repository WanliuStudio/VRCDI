package com.vrcdevicecheck.osc.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeartRateParserTest {

    @Test
    public void parsesEightBitHeartRate() {
        assertEquals(72, HeartRateParser.parse(new byte[]{(byte) 0x00, (byte) 72}));
    }

    @Test
    public void parsesSixteenBitHeartRate() {
        assertEquals(300, HeartRateParser.parse(new byte[]{
                (byte) 0x01, (byte) 0x2C, (byte) 0x01}));
    }

    @Test
    public void rejectsShortOrOutOfRangeData() {
        assertEquals(-1, HeartRateParser.parse(null));
        assertEquals(-1, HeartRateParser.parse(new byte[]{(byte) 0x00}));
        assertEquals(-1, HeartRateParser.parse(new byte[]{(byte) 0x01, (byte) 0x2C}));
        assertEquals(-1, HeartRateParser.parse(new byte[]{(byte) 0x00, (byte) 0x00}));
        assertEquals(-1, HeartRateParser.parse(new byte[]{(byte) 0x00, (byte) 0x0A}));
        assertEquals(-1, HeartRateParser.parse(new byte[]{
                (byte) 0x01, (byte) 0xFF, (byte) 0xFF}));
    }
}