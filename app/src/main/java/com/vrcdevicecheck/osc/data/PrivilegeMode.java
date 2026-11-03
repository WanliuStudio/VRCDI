package com.vrcdevicecheck.osc.data;

/** 权限申请方式 */
public enum PrivilegeMode {

    SHIZUKU("shizuku", "Shizuku"),
    ROOT("root", "Root"),
    NONE("none", "无特权（仅读取系统开放的信息）");

    public final String key;
    public final String label;

    PrivilegeMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public static PrivilegeMode fromKey(String key) {
        for (PrivilegeMode mode : values()) {
            if (mode.key.equals(key)) return mode;
        }
        return SHIZUKU;
    }
}
