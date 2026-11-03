package com.vrcdevicecheck.osc.priv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * root 环境检测与授权动作。
 *
 * 检测顺序（用户选择 Root 后自动执行）：
 * 1. 已有 root 授权 -> 直接选中 Root 通道，不再打扰用户；
 * 2. 没有授权 -> 探测设备上装的是哪种面具：
 *    - 内核级（KernelSU / APatch）：跳转到管理器，提示用户去超级用户列表手动授权；
 *    - Magisk / Alpha / Kitsune：直接执行 su 触发系统授权弹窗。
 */
public final class RootController {

    private static final List<String> KSU_PATHS = Arrays.asList("/data/adb/ksu", "/data/adb/ksud");
    private static final List<String> APATCH_PATHS = Arrays.asList("/data/adb/ap", "/data/adb/apd");
    private static final List<String> MAGISK_PATHS =
            Arrays.asList("/data/adb/magisk", "/data/adb/magisk.db", "/sbin/.magisk");

    /** 用于探测面具类型的 shell 片段（在有 shell 权限时执行） */
    public static final String DETECT_SCRIPT =
            "for p in /data/adb/ksu /data/adb/ksud /data/adb/ap /data/adb/apd "
                    + "/data/adb/magisk /data/adb/magisk.db /sbin/.magisk; do "
                    + "[ -e $p ] && echo $p; done";

    private RootController() {
    }

    /** 一次检测的结果 */
    public static class RootState {
        public final boolean granted;
        public final RootManager manager;
        public final List<String> detectedPaths;
        public final String detail;

        public RootState(boolean granted, RootManager manager, List<String> detectedPaths, String detail) {
            this.granted = granted;
            this.manager = manager;
            this.detectedPaths = detectedPaths;
            this.detail = detail;
        }
    }

    /** 用已有采集结果判断，不额外起进程 */
    public static RootState stateFrom(Integer uid, List<String> rootPaths, Context context) {
        boolean granted = uid != null && uid == 0;
        List<String> paths = rootPaths == null ? new ArrayList<String>() : rootPaths;
        RootManager manager = detectManager(paths, context);

        String detail;
        if (granted) {
            detail = "已获得 root 授权（uid=0）";
        } else if (manager == RootManager.UNKNOWN && paths.isEmpty()) {
            detail = "未探测到 root 环境：/data/adb 下没有已知的面具目录（可能未 root，或路径被隐藏）";
        } else if (manager.kernelLevel) {
            detail = manager.label + " 是内核级方案，需要在它的超级用户列表里手动给 VRCDI 授权";
        } else {
            detail = "检测到 " + manager.label + "，可以直接申请 root 授权";
        }
        return new RootState(granted, manager, paths, detail);
    }

    /** 路径探测优先（更可靠），其次按已安装的管理器包名判断 */
    public static RootManager detectManager(List<String> rootPaths, Context context) {
        List<String> paths = rootPaths == null ? new ArrayList<String>() : rootPaths;

        if (containsAny(paths, KSU_PATHS)) return RootManager.KERNEL_SU;
        if (containsAny(paths, APATCH_PATHS)) return RootManager.APATCH;

        List<RootManager> installed = new ArrayList<>();
        for (RootManager manager : RootManager.values()) {
            for (String pkg : manager.packages) {
                if (isInstalled(context, pkg)) {
                    installed.add(manager);
                    break;
                }
            }
        }

        if (containsAny(paths, MAGISK_PATHS)) {
            // 已确认是 Magisk 系，再按已安装的分支细分
            for (RootManager manager : installed) {
                if (!manager.kernelLevel) return manager;
            }
            return RootManager.MAGISK;
        }
        return installed.isEmpty() ? RootManager.UNKNOWN : installed.get(0);
    }

    private static boolean containsAny(List<String> paths, List<String> candidates) {
        for (String path : paths) {
            for (String candidate : candidates) {
                if (path.startsWith(candidate)) return true;
            }
        }
        return false;
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 直接执行 su 触发授权弹窗（适用于 Magisk 系），返回是否已授权。会阻塞数秒，必须在子线程调用。 */
    public static boolean requestRootBlocking() {
        return RootShell.probe() != null;
    }

    /**
     * 打开 root 管理器，让用户去超级用户列表手动授权。
     * 各管理器版本差异大、内部 Activity 名称不稳定，所以先试候选组件，
     * 失败就退回启动管理器主界面，再配合 {@link #manualGrantHint} 的文案告诉用户点哪里。
     */
    public static boolean openSuperuserPage(Context context, RootManager manager) {
        String pkg = null;
        for (String candidate : manager.packages) {
            if (isInstalled(context, candidate)) {
                pkg = candidate;
                break;
            }
        }
        if (pkg == null && manager.packages.length > 0) pkg = manager.packages[0];
        if (pkg == null) return false;

        // 下列 Activity 名称是各管理器主界面的常见取值，跨版本可能变化；打不开会自动退回启动主界面
        List<ComponentName> candidates = new ArrayList<>();
        switch (manager) {
            case KERNEL_SU:
                candidates.add(new ComponentName(pkg, "me.weishu.kernelsu.ui.MainActivity"));
                break;
            case APATCH:
                candidates.add(new ComponentName(pkg, "me.bmax.apatch.ui.MainActivity"));
                break;
            case MAGISK:
            case MAGISK_ALPHA:
            case KITSUNE:
                candidates.add(new ComponentName(pkg, "com.topjohnwu.magisk.ui.MainActivity"));
                break;
            default:
                break;
        }

        for (ComponentName component : candidates) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }

        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 授权后用户该做什么，直接显示在界面上 */
    public static String manualGrantHint(RootManager manager) {
        switch (manager) {
            case KERNEL_SU:
                return "在 KernelSU 里进入「超级用户」，找到 VRCDI 并打开开关，然后回到本应用点「重新检测」";
            case APATCH:
                return "在 APatch 里进入「超级用户」，找到 VRCDI 并打开开关，然后回到本应用点「重新检测」";
            case MAGISK:
            case MAGISK_ALPHA:
            case KITSUNE:
                return "在 " + manager.label + " 里进入「超级用户」，确认 VRCDI 已授权，然后回到本应用点「重新检测」";
            default:
                return "在你的 root 管理器里给 VRCDI 授予超级用户权限，然后回到本应用点「重新检测」";
        }
    }
}
