package com.vrcdevicecheck.osc.device;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import java.util.Locale;

/**
 * 通过 EGL/GLES 读 GPU 真实型号（GL_RENDERER）。
 *
 * 为什么不用 sysfs：Mali 设备的 devfreq 名字往往只是设备树地址（例如 23140000.gpu），
 * 拿它当型号会显示成 "23140000.gpu"；kbase 的 gpuinfo 又常被 SELinux 挡住。
 * GL_RENDERER 由驱动直接返回，典型值是 "Mali-G57"、"Adreno 750"，
 * 不需要任何权限、不需要 root/Shizuku，在所有正常设备上都能取到。
 *
 * 实现：建一个 1x1 的 pbuffer 离屏上下文，读一次 GL_RENDERER 后立刻销毁；
 * 成功后缓存，失败不缓存（下次采集再试），全程 try/catch，绝不因为取不到而崩。
 */
public final class GpuInfo {

    private static volatile String cached;

    private GpuInfo() {
    }

    /** 返回 GPU 型号；取不到返回 null（调用方显示"不可用"） */
    public static String renderer() {
        String local = cached;
        if (local != null) return local;
        synchronized (GpuInfo.class) {
            if (cached != null) return cached;
            cached = readRenderer();
            return cached;
        }
    }

    private static String readRenderer() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return null;
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null;

            int[] configAttribs = new int[]{
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, configCount, 0)
                    || configCount[0] <= 0) {
                return null;
            }

            int[] contextAttribs = new int[]{
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) return null;

            int[] surfaceAttribs = new int[]{
                    EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) return null;

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null;

            return clean(GLES20.glGetString(GLES20.GL_RENDERER));
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT);
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                    EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
                // 清理失败不影响已经读到的结果
            }
        }
    }

    /** 去掉 "(TM)" 之类的商标后缀；软件渲染/模拟器不算真实 GPU 型号 */
    private static String clean(String raw) {
        if (raw == null) return null;
        String text = raw.replace("(TM)", " ").replace("(R)", " ")
                .replace("(tm)", " ").replace("(r)", " ");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("swiftshader") || lower.contains("emulator") || lower.contains("llvmpipe")) {
            return null;
        }
        return text;
    }
}
