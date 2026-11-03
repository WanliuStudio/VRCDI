package com.vrcdevicecheck.osc.osc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * OSC 编码器字节级测试。
 *
 * 断言基准全部来自技能自带的 scripts/osc_packet_tool.py 实际输出，
 * 不是手写猜测的字节：
 *   python3 scripts/osc_packet_tool.py --selftest
 */
public class OscEncoderTest {

    @Test
    public void chatboxInputShortTextMatchesGoldenBytes() {
        byte[] packet = OscEncoder.chatboxInput("hi", true, false);
        assertEquals("2f63686174626f782f696e70757400002c7354460000000068690000",
                OscEncoder.toHex(packet));
    }

    @Test
    public void chatboxInputLongerTextMatchesGoldenBytes() {
        byte[] packet = OscEncoder.chatboxInput("hello vrchat", true, false);
        assertEquals("2f63686174626f782f696e70757400002c7354460000000068656c6c6f2076726368617400000000",
                OscEncoder.toHex(packet));
    }

    @Test
    public void chatboxInputUtf8TextMatchesGoldenBytes() {
        byte[] packet = OscEncoder.chatboxInput("你好", true, false);
        assertEquals("2f63686174626f782f696e70757400002c73544600000000e4bda0e5a5bd0000",
                OscEncoder.toHex(packet));
    }

    @Test
    public void chatboxTypingMatchesGoldenBytes() {
        byte[] packet = OscEncoder.chatboxTyping(true);
        assertEquals("2f63686174626f782f747970696e67002c540000", OscEncoder.toHex(packet));
    }

    @Test
    public void avatarParameterFloatUsesBigEndianIeee754() {
        byte[] packet = OscEncoder.avatarParameter("Voice", OscArg.of(0.75f));
        assertEquals("2f6176617461722f706172616d65746572732f566f696365000000002c6600003f400000",
                OscEncoder.toHex(packet));
    }

    @Test
    public void messageWithoutArgsPadsTypeTagToFourBytes() {
        byte[] packet = OscEncoder.encodeMessage("/test", Collections.<OscArg>emptyList());
        assertEquals("2f746573740000002c000000", OscEncoder.toHex(packet));
    }

    @Test
    public void everyPacketIsFourByteAligned() {
        assertEquals(0, OscEncoder.chatboxInput("hi", true, false).length % 4);
        assertEquals(0, OscEncoder.chatboxInput("123456789012345", false, true).length % 4);
        assertEquals(0, OscEncoder.encodeMessage("/test").length % 4);
        assertEquals(0, OscEncoder.avatarParameter("X", OscArg.of(1)).length % 4);
    }

    @Test
    public void booleanArgsTakeNoDataBytes() {
        byte[] withBools = OscEncoder.encodeMessage("/b",
                Arrays.asList(OscArg.of(true), OscArg.of(false)));
        // 地址 4 字节 + 类型标签 ",TF" 4 字节，布尔不占数据位
        assertEquals(8, withBools.length);
    }

    @Test
    public void addressMustStartWithSlash() {
        try {
            OscEncoder.encodeMessage("chatbox/input");
            fail("地址不以 / 开头时必须抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("/"));
        }
    }

    @Test
    public void blobCarriesLengthPrefixAndPadding() {
        byte[] packet = OscEncoder.encodeMessage("/b",
                Arrays.asList(OscArg.ofBlob(new byte[]{1, 2, 3})));
        // 地址 4 + 类型标签 ",b" 4 + 长度前缀 4 + 数据 3 + 填充 1 = 16
        assertEquals(16, packet.length);
        assertEquals(0, packet.length % 4);
    }
}
