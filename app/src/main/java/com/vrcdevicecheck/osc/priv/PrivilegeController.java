package com.vrcdevicecheck.osc.priv;

import android.content.Context;

import com.vrcdevicecheck.osc.data.PrivilegeMode;

/** 按用户选择的权限申请方式返回实际命令通道 */
public final class PrivilegeController {

    private static final ShizukuShell SHIZUKU = new ShizukuShell();
    private static final RootShell ROOT = new RootShell();
    private static final LocalShell LOCAL = new LocalShell();

    private PrivilegeController() {
    }

    public static PrivilegedShell shellFor(PrivilegeMode mode) {
        if (mode == null) return LOCAL;
        switch (mode) {
            case SHIZUKU:
                return SHIZUKU;
            case ROOT:
                return ROOT;
            case NONE:
            default:
                return LOCAL;
        }
    }

    public static PrivilegedShell shellFor(PrivilegeMode mode, Context context) {
        return shellFor(mode);
    }
}
