package com.vrcdevicecheck.osc.device;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

/**
 * 读取"当前位于前台的包名"。
 *
 * 为什么只能用 UsageStatsManager：从 Android 5.1 起，普通应用通过
 * getRunningTasks / getRunningAppProcesses 都看不到别的应用了；不用 root、不用无障碍服务
 * 的官方途径只剩 UsageStatsManager，而它要求在系统设置里由用户手动授予"使用情况访问"
 * （PACKAGE_USAGE_STATS 是签名级权限，代码申请不到，只能引导用户去开）。
 * 未授权时 {@link #current(Context)} 返回 null，界面如实显示"未授权"，绝不假装检测到了。
 */
public final class ForegroundApp {

    /** VRChat 一体机版包名；写进 AndroidManifest 的 queries 后，Android 11+ 才解析得到它 */
    public static final String PKG_VRCHAT_QUEST = "com.vrchat.oculus.quest";

    /** 允许事件往前看多久：覆盖切出去看两眼再回来的情况 */
    private static final long LOOK_BACK_MS = 120000L;

    private ForegroundApp() {
    }

    /** 用户是否已在系统设置里授予"使用情况访问" */
    public static boolean hasUsageAccess(Context context) {
        if (context == null) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            // minSdk 29，unsafeCheckOpNoThrow 自 API 29 起可用
            int mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最近一个被切到前台的包名；没有记录或没权限时返回 null */
    public static String current(Context context) {
        return scan(context, null);
    }

    /** 最近一个"不是本应用"的前台包名：界面自己在前台时，用它告诉用户之前用的是什么 */
    public static String lastOther(Context context) {
        return scan(context, context == null ? null : context.getPackageName());
    }

    private static String scan(Context context, String excludePackage) {
        if (context == null) return null;
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - LOOK_BACK_MS, now);
            if (events == null) return null;

            UsageEvents.Event event = new UsageEvents.Event();
            String latest = null;
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) break;
                // ACTIVITY_RESUMED 自 API 29 起（旧名 MOVE_TO_FOREGROUND 同值）
                if (event.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
                String pkg = event.getPackageName();
                if (pkg == null || pkg.isEmpty()) continue;
                if (excludePackage != null && excludePackage.equals(pkg)) continue;
                latest = pkg;
            }
            return latest;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 包名 -> 便于阅读的名字；解析不到（包可见性限制、已卸载）就原样返回包名 */
    public static String label(Context context, String pkg) {
        if (pkg == null) return null;
        if (context != null && pkg.equals(context.getPackageName())) return "VRCDI（本应用）";
        if (PKG_VRCHAT_QUEST.equals(pkg)) return "VRChat（Quest）";
        if (context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                CharSequence name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                if (name != null && name.length() > 0) return name.toString();
            } catch (Throwable ignored) {
                // Android 11+ 包可见性限制、应用已卸载等，都退回包名
            }
        }
        return pkg;
    }
}
