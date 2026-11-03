package com.vrcdevicecheck.osc.priv;

/**
 * 已知的 root 管理器。
 *
 * kernelLevel = true 的（KernelSU / APatch）由内核直接发权限，无法用 su 弹窗申请，
 * 需要用户自己去管理器的超级用户列表里手动授权；
 * kernelLevel = false 的（Magisk 及其分支）可以直接执行 su 触发授权弹窗。
 *
 * 包名是各管理器的默认包名，用户可能改名或隐藏，所以检测时同时结合 root 侧路径探测。
 */
public enum RootManager {

    KERNEL_SU("KernelSU", true, new String[]{"me.weishu.kernelsu"}),
    APATCH("APatch", true, new String[]{"me.bmax.apatch"}),
    MAGISK("Magisk", false, new String[]{"com.topjohnwu.magisk"}),
    MAGISK_ALPHA("Magisk Alpha", false, new String[]{"io.github.vvb2060.magisk"}),
    KITSUNE("Kitsune Mask", false, new String[]{"io.github.huskydg.magisk"}),
    UNKNOWN("未知 root 方案", false, new String[0]);

    public final String label;
    public final boolean kernelLevel;
    public final String[] packages;

    RootManager(String label, boolean kernelLevel, String[] packages) {
        this.label = label;
        this.kernelLevel = kernelLevel;
        this.packages = packages;
    }
}
