package com.vrcdevicecheck.osc.priv;

import android.content.Context;

/**
 * 特权命令通道的统一抽象：Shizuku / Root / 无特权 三种实现。
 * 所有方法都必须在子线程调用。
 */
public interface PrivilegedShell {

    /** 通道当前是否可用（已安装、已授权） */
    boolean available(Context context);

    /** 面向用户的一句话状态说明 */
    String statusText(Context context);

    /** 通道名称，写进日志与数据来源标记 */
    String label();

    /**
     * 执行脚本。
     *
     * @param timeoutMs 超时后强制销毁进程，避免卡死上报循环
     * @return 执行结果；通道不可用时返回 null
     */
    ShellResult run(String script, long timeoutMs);
}
