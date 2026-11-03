package com.vrcdevicecheck.osc.device;

import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通过反射读取系统属性。
 *
 * 为什么用反射：compileSdk 30 下 Build.SOC_MODEL（API 31）不存在，
 * 直接引用会编译失败；而 ro.* 属性用 SystemProperties.get 读取不需要任何权限。
 */
public final class SystemProps {

    private static Method getMethod;
    private static boolean unavailable;

    private SystemProps() {
    }

    public static String get(String key) {
        if (unavailable || key == null) return null;
        try {
            if (getMethod == null) {
                Class<?> clazz = Class.forName("android.os.SystemProperties");
                getMethod = clazz.getMethod("get", String.class);
            }
            Object value = getMethod.invoke(null, key);
            if (value == null) return null;
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable t) {
            unavailable = true;
            return null;
        }
    }

    /**
     * Build.SOC_MODEL 是 API 31 才有的常量，compileSdk 30 直接引用编译不过，所以反射读。
     * 高通机型上它通常给出 "SM8550-AB" 这类可读的 SoC 型号，
     * 比 ro.hardware（只会返回 "qcom"）有意义得多。
     */
    public static String socModelFromBuild() {
        try {
            Field field = Build.class.getField("SOC_MODEL");
            Object value = field.get(null);
            if (value instanceof String) {
                String text = ((String) value).trim();
                return text.isEmpty() ? null : text;
            }
        } catch (Throwable ignored) {
            // API 30 及以下没有这个字段，忽略
        }
        return null;
    }
}
