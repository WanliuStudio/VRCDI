package com.vrcdevicecheck.osc.device;

import java.util.ArrayList;

/** 测试用的快照构造器，避免在每条用例里写 18 个参数 */
public final class TestSnapshots {

    private TestSnapshots() {
    }

    public static DeviceSnapshot full() {
        return new DeviceSnapshot(
                "HUAWEI", "HED-AL00", "Pura 80",
                "Kirin 9010S", 1800, 42.3f, 23f,
                "Maleoon 910", 750, 40.1f, 12f,
                6200L, 12000L,
                78, true,
                2000, new ArrayList<String>(), "Shizuku");
    }

    public static DeviceSnapshot empty() {
        return new DeviceSnapshot(
                null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null,
                null, false,
                null, new ArrayList<String>(), "无特权");
    }

    public static DeviceSnapshot notCharging(Integer batteryPct) {
        return new DeviceSnapshot(
                "zte", "W203DS", null,
                null, null, null, null,
                null, null, null, null,
                null, null,
                batteryPct, false,
                null, new ArrayList<String>(), "无特权");
    }
}
