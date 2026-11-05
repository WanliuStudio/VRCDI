package com.vrcdevicecheck.osc.util;

/**
 * 对用户手写的 JSON 做一点容错。
 *
 * 最常见的两种错误：
 *   1. 文件开头带 BOM（Windows 记事本保存 UTF-8 时会有）；
 *   2. 对象/数组最后一项多了一个逗号，例如 {"a":1,} 或 ["a",]。
 * 这两种在标准 JSON 里都是非法的，org.json 会直接抛异常，
 * 但用户肉眼几乎看不出来——主题导入报"theme.json 解析失败"很多就是这个原因。
 */
public final class JsonRepair {

    private JsonRepair() {
    }

    public static String repair(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            value = value.substring(1).trim();
        }
        // 去掉紧挨着 } 或 ] 之前的多余逗号
        return value.replaceAll(",\\s*([}\\]])", "$1");
    }
}
