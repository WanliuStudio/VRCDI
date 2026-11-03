package com.vrcdevicecheck.osc.priv;

/** 一次特权命令执行的结果 */
public class ShellResult {

    public final int exitCode;
    public final String stdout;
    public final String stderr;

    public ShellResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    /** 命令跑通且有输出才算成功 */
    public boolean isOk() {
        return exitCode == 0 && !stdout.trim().isEmpty();
    }
}
