package com.vrcdevicecheck.osc.device;

import android.content.Context;

import com.vrcdevicecheck.osc.util.JsonRepair;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CPU 名称映射：把系统里读到的平台代号（ro.board.platform / soc0 machine 等）
 * 换成人类可读的型号，例如 kona -> Snapdragon 865。
 *
 * 高通手机大多不设置 ro.soc.model，只留 ro.board.platform（kona / taro / kalama 这种代号），
 * 甚至只剩 ro.hardware = qcom。有了这张表就能显示成 "Qualcomm Snapdragon 865"。
 *
 * 存储：
 *   assets/cpu_map.json   内置表
 *   files/cpu_map.json    用户导入（覆盖同名条目）
 *
 * 格式：
 * {
 *   "cpus": {
 *     "kona": "Snapdragon 865",
 *     "taro": "Snapdragon 8 Gen 1"
 *   }
 * }
 * 也可以省略 "cpus" 这一层，直接写 "代号": "名称"。
 */
public final class CpuNames {

    private static final String ASSET_FILE = "cpu_map.json";
    private static final String USER_FILE = "cpu_map.json";

    private static Map<String, String> map = Collections.emptyMap();

    private CpuNames() {
    }

    /** 应用启动/导入后调用，把映射表加载到内存 */
    public static synchronized void init(Context context) {
        if (context == null) return;
        Map<String, String> merged = new LinkedHashMap<>();
        parseInto(merged, readAsset(context));
        File userFile = new File(context.getFilesDir(), USER_FILE);
        if (userFile.isFile()) parseInto(merged, readFile(userFile));

        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            normalized.put(entry.getKey().trim().toLowerCase(Locale.US), entry.getValue());
        }
        map = normalized;
    }

    /** 查映射；没有收录返回 null */
    public static String lookup(String raw) {
        if (raw == null) return null;
        return map.get(raw.trim().toLowerCase(Locale.US));
    }

    public static int count() {
        return map.size();
    }

    /**
     * 导入用户映射表，成功返回条目数，失败返回 -1。
     * 导入后会自动重新加载。
     */
    public static int importJson(Context context, String json) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (!parseInto(parsed, json)) return -1;
        if (parsed.isEmpty()) return -1;

        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(context.getFilesDir(), USER_FILE));
            out.write(json.getBytes(Charset.forName("UTF-8")));
            out.flush();
            init(context);
            return parsed.size();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    private static boolean parseInto(Map<String, String> target, String json) {
        if (json == null) return false;
        String text = JsonRepair.repair(json);
        if (text == null || text.isEmpty()) return false;
        if (!text.startsWith("{")) return false;
        try {
            JSONObject root = new JSONObject(text);
            JSONObject cpus = root.optJSONObject("cpus");
            JSONObject source = cpus != null ? cpus : root;
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("_")) continue;
                String value = source.optString(key, "").trim();
                if (key.trim().isEmpty() || value.isEmpty()) continue;
                target.put(key, value);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readAsset(Context context) {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_FILE);
            return readAll(in);
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static String readFile(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return readAll(in);
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
