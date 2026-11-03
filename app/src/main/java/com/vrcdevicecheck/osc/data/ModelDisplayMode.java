package com.vrcdevicecheck.osc.data;

/**
 * 上报的「设备型号」用哪一种写法。
 *
 * REAL_MODEL   —— 设备真实型号，例如 HUAWEI HED-AL00（取自 ro.product.model / Build.MODEL）
 * PRODUCT_NAME —— 设备产品名称，例如 HUAWEI Pura 80（取自设备表 series，读不到时退回真实型号）
 */
public enum ModelDisplayMode {

    REAL_MODEL("real", "设备真实型号"),
    PRODUCT_NAME("product", "设备产品名称");

    public final String key;
    public final String label;

    ModelDisplayMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public static ModelDisplayMode fromKey(String key) {
        for (ModelDisplayMode mode : values()) {
            if (mode.key.equals(key)) return mode;
        }
        // 默认沿用老版本行为：有产品名就用产品名
        return PRODUCT_NAME;
    }
}
