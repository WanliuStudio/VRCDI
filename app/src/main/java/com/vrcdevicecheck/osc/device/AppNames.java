package com.vrcdevicecheck.osc.device;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用包名 -> 应用显示名。
 *
 * 存储：
 *   assets/app_names.json        内置表
 *   files/app_names.json         用户导入（覆盖内置同名条目）
 *
 * JSON 格式（与设备表不同，这里是"包名: 名字"的映射）：
 * {
 *   "apps": {
 *     "com.tencent.mm": "微信",
 *     "com.tencent.mobileqq": "QQ",
 *     "com.vrchat.mobile.playstore": "VRChat"
 *   }
 * }
 * 也可以直接省略 "apps" 这一层，把包名映射写在最外层。
 */
public final class AppNames {

    private static final String ASSET_FILE = "app_names.json";
    private static final String USER_FILE = "app_names.json";

    private static Map<String, String> cached;
    private static long cachedStamp = -1L;

    private AppNames() {
    }

    /** 查包名对应的显示名；没有收录返回 null */
    public static String lookup(Context context, String pkg) {
        if (context == null || pkg == null) return null;
        Map<String, String> map = load(context);
        return map.get(pkg);
    }

    public static synchronized Map<String, String> load(Context context) {
        File userFile = new File(context.getFilesDir(), USER_FILE);
        long stamp = userFile.isFile() ? userFile.lastModified() : 0L;
        if (cached != null && stamp == cachedStamp) return cached;

        Map<String, String> map = new LinkedHashMap<>();
        parseInto(map, readAsset(context));
        if (userFile.isFile()) parseInto(map, readFile(userFile));
        cached = map;
        cachedStamp = stamp;
        return map;
    }

    /** 内置 + 用户导入的条目总数 */
    public static int count(Context context) {
        return load(context).size();
    }

    /**
     * 导入用户应用表：校验通过后原样写入 files/app_names.json。
     * @return 导入的条目数；解析失败返回 -1
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
            cached = null;
            cachedStamp = -1L;
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

    /** @return 解析是否成功（空表也算成功） */
    private static boolean parseInto(Map<String, String> target, String json) {
        if (json == null) return false;
        String text = json.trim();
        if (text.isEmpty()) return false;
        if (text.charAt(0) == '\uFEFF') text = text.substring(1).trim();
        if (!text.startsWith("{")) return false;
        try {
            JSONObject root = new JSONObject(text);
            JSONObject apps = root.optJSONObject("apps");
            JSONObject source = apps != null ? apps : root;
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("_")) continue;
                if (!key.contains(".")) continue;   // 包名一定带点，过滤说明字段
                String value = source.optString(key, "").trim();
                if (value.isEmpty()) continue;
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
