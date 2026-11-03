package com.vrcdevicecheck.osc.osc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OSC 1.0 报文编码器。纯逻辑、零 Android 依赖，可跑 JVM 单元测试。
 *
 * 字节基准与技能自带的 osc_packet_tool.py 输出一致，例如：
 * chatboxInput("hi", true, false) ==
 *   2f63686174626f782f696e70757400002c7354460000000068690000
 *
 * 编码规则：
 * - 所有多字节整数大端序；
 * - 字符串 = UTF-8 字节 + 一个 0x00，再补 0x00 到 4 的整数倍（已对齐则不额外补）；
 * - Message = 地址串 + 类型标签串（以 ',' 开头）+ 各参数字节；
 * - 布尔 T/F 只体现在类型标签里，不占数据位。
 */
public final class OscEncoder {

    public static final String ADDRESS_CHATBOX_INPUT = "/chatbox/input";
    public static final String ADDRESS_CHATBOX_TYPING = "/chatbox/typing";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private OscEncoder() {
    }

    /** 补齐到 4 字节整数倍；已对齐时不额外填充 */
    private static void writePadded(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
        int remainder = data.length % 4;
        if (remainder != 0) {
            for (int i = 0; i < 4 - remainder; i++) out.write(0);
        }
    }

    /** OSC 字符串：UTF-8 字节 + 一个 0x00 结束符，再补齐到 4 字节 */
    public static void writeOscString(ByteArrayOutputStream out, String text) {
        byte[] raw = (text == null ? "" : text).getBytes(UTF8);
        byte[] withNull = new byte[raw.length + 1];
        System.arraycopy(raw, 0, withNull, 0, raw.length);
        writePadded(out, withNull);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeFloat32(ByteArrayOutputStream out, float value) {
        writeInt32(out, Float.floatToIntBits(value));
    }

    public static byte[] encodeMessage(String address, List<OscArg> args) {
        if (address == null || !address.startsWith("/")) {
            throw new IllegalArgumentException("OSC 地址必须以 / 开头：" + address);
        }
        List<OscArg> arguments = args == null ? new ArrayList<OscArg>() : args;
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        writeOscString(out, address);

        StringBuilder tags = new StringBuilder(",");
        for (OscArg arg : arguments) tags.append(arg.tag());
        writeOscString(out, tags.toString());

        for (OscArg arg : arguments) {
            switch (arg.type) {
                case STRING:
                    writeOscString(out, arg.stringValue());
                    break;
                case INT32:
                    writeInt32(out, arg.intValue());
                    break;
                case FLOAT32:
                    writeFloat32(out, arg.floatValue());
                    break;
                case BOOL:
                    // 不占数据位
                    break;
                case BLOB:
                    byte[] blob = arg.blobValue();
                    writeInt32(out, blob.length);
                    writePadded(out, blob);
                    break;
            }
        }
        return out.toByteArray();
    }

    public static byte[] encodeMessage(String address) {
        return encodeMessage(address, new ArrayList<OscArg>());
    }

    /**
     * VRChat Chatbox 发送：/chatbox/input (string 文本, bool 立即发送, bool 提示音)
     *
     * @param sendImmediately true 直接发出；false 只把文本填进 VRChat 输入框
     * @param notification    是否播放提示音
     */
    public static byte[] chatboxInput(String text, boolean sendImmediately, boolean notification) {
        return encodeMessage(ADDRESS_CHATBOX_INPUT, Arrays.asList(
                OscArg.of(text),
                OscArg.of(sendImmediately),
                OscArg.of(notification)
        ));
    }

    /** VRChat Chatbox 输入状态：/chatbox/typing (bool) */
    public static byte[] chatboxTyping(boolean typing) {
        return encodeMessage(ADDRESS_CHATBOX_TYPING, Arrays.asList(OscArg.of(typing)));
    }

    /** VRChat 形象参数：/avatar/parameters/&lt;name&gt; + 单个参数 */
    public static byte[] avatarParameter(String name, OscArg arg) {
        return encodeMessage("/avatar/parameters/" + name, Arrays.asList(arg));
    }

    /** 调试用：字节转十六进制小写字符串 */
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(java.util.Locale.US, "%02x", b));
        return sb.toString();
    }
}
