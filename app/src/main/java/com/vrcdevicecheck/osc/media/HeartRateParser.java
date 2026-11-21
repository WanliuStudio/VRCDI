package com.vrcdevicecheck.osc.media;

/**
 * 蓝牙标准心率服务（HRS 0x180D / Heart Rate Measurement 0x2A37）数据解析。
 * 纯逻辑，方便单测。
 *
 * 第 1 字节是标志位：bit0=0 时心率是 uint8，=1 时是 uint16（小端）。
 */
public final class HeartRateParser {

    private HeartRateParser() {
    }

    /** @return 心率 bpm；数据非法或明显超出人体范围时返回 -1 */
    public static int parse(byte[] data) {
        if (data == null || data.length < 2) return -1;
        int flags = data[0] & 0xFF;
        int bpm;
        if ((flags & 0x01) != 0) {
            if (data.length < 3) return -1;
            bpm = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        } else {
            bpm = data[1] & 0xFF;
        }
        return (bpm >= 15 && bpm <= 300) ? bpm : -1;
    }
}