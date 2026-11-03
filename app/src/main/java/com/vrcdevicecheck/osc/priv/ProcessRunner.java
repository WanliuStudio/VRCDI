package com.vrcdevicecheck.osc.priv;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/** 统一读取进程输出，带超时与强制销毁。必须在子线程调用。 */
final class ProcessRunner {

    private ProcessRunner() {
    }

    static ShellResult read(Process process, long timeoutMs) {
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();

        Thread outReader = drain(process.getInputStream(), stdout);
        Thread errReader = drain(process.getErrorStream(), stderr);

        Integer exitCode = pollExit(process, timeoutMs);
        if (exitCode == null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
                // 销毁失败无需处理
            }
            return new ShellResult(-1, stdout.toString(), "读取超时（" + timeoutMs + "ms）");
        }

        join(outReader);
        join(errReader);
        return new ShellResult(exitCode, stdout.toString(), stderr.toString());
    }

    /**
     * 自己轮询 exitValue，不用 Process.waitFor(timeout, TimeUnit)。
     *
     * 原因：Shizuku 的 ShizukuRemoteProcess 把 exitValue() 直接转给 binder，服务端发现进程
     * 还在跑时抛的是 IllegalThreadStateException("process hasn't exited")，而 binder 跨进程
     * 只能还原成 IllegalStateException；Process.waitFor(long, TimeUnit) 的默认实现只 catch
     * IllegalThreadStateException，于是这个异常会一路冒到上层，变成
     * "Shizuku 执行失败：process hasn't exited"，整个采集降级、CPU 全部显示不可用。
     * 这里对所有"还没退出"的异常一律当作运行中处理，超时由 deadline 兜底。
     */
    private static Integer pollExit(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        while (true) {
            try {
                return process.exitValue();
            } catch (Throwable t) {
                if (!isStillRunning(t)) return -1;
                if (System.currentTimeMillis() >= deadline) return null;
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private static boolean isStillRunning(Throwable t) {
        if (t instanceof IllegalThreadStateException) return true;
        String message = t.getMessage();
        if (message == null) return true;
        return message.contains("hasn't exited") || message.contains("has not exited");
    }

    private static Thread drain(final InputStream in, final StringBuilder target) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        target.append(line).append('\n');
                    }
                } catch (Throwable ignored) {
                    // 进程被销毁时读流会抛异常，属正常情况
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
