package com.vrcdevicecheck.osc.device;

/** 不需要任何特权、由 Android API 直接拿到的信息 */
public class JavaSideInfo {

    public final String brand;
    public final String model;
    public final String hardware;
    public final String socModel;
    /** SoC 厂商，例如 Unisoc / Qualcomm / MediaTek；取不到为 null */
    public final String socManufacturer;
    public final Long ramTotalMb;
    public final Long ramUsedMb;
    public final Integer batteryPct;
    public final boolean charging;

    public JavaSideInfo(String brand, String model, String hardware, String socModel,
                        Long ramTotalMb, Long ramUsedMb,
                        Integer batteryPct, boolean charging) {
        this(brand, model, hardware, socModel, null, ramTotalMb, ramUsedMb, batteryPct, charging);
    }

    public JavaSideInfo(String brand, String model, String hardware, String socModel,
                        String socManufacturer,
                        Long ramTotalMb, Long ramUsedMb,
                        Integer batteryPct, boolean charging) {
        this.brand = brand;
        this.model = model;
        this.hardware = hardware;
        this.socModel = socModel;
        this.socManufacturer = socManufacturer;
        this.ramTotalMb = ramTotalMb;
        this.ramUsedMb = ramUsedMb;
        this.batteryPct = batteryPct;
        this.charging = charging;
    }
}
