package com.vrcdevicecheck.osc.device;

import android.content.Context;

import com.vrcdevicecheck.osc.util.JsonRepair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 设备表的读写。
 *
 * 存储位置（都在应用私有目录，不需要任何存储权限）：
 *   files/device_tables/&lt;品牌&gt;.json   按品牌导入的设备表（优先级最高，排在最前）
 *   files/device_models.json            旧版本的单表，仍兼容读取
 *   assets/device_models.json           内置表（优先级最低）
 *
 * 三部分在加载时按上面的顺序合并，精确/前缀匹配逻辑见 {@link DeviceModelTable}。
 */
public final class DeviceModelTables {

    private static final String TABLES_DIR = "device_tables";
    private static final String LEGACY_FILE = "device_models.json";

    /** 品牌设备表列表项 */
    public static final class BrandTableInfo {
        public final String brand;
        /** -1 表示文件存在但解析失败 */
        public final int count;

        public BrandTableInfo(String brand, int count) {
            this.brand = brand;
            this.count = count;
        }
    }

    /** 一次按品牌导入的结果 */
    public static final class ImportedBrand {
        public final String brand;
        public final int count;

        public ImportedBrand(String brand, int count) {
            this.brand = brand;
            this.count = count;
        }
    }

    private DeviceModelTables() {
    }

    public static File tablesDir(Context context) {
        File dir = new File(context.getFilesDir(), TABLES_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static DeviceModelTable load(Context context) {
        List<DeviceModelTable.Entry> merged = new ArrayList<>();

        File[] files = tablesDir(context).listFiles();
        if (files != null) {
            Arrays.sort(files, BY_NAME);
            for (File file : files) {
                if (!isJsonFile(file)) continue;
                DeviceModelTable table = parse(readFile(file));
                if (table != null) addEntries(merged, table.entries());
            }
        }

        // 兼容旧版本写在 files/device_models.json 的单表
        File legacy = new File(context.getFilesDir(), LEGACY_FILE);
        if (legacy.isFile()) {
            DeviceModelTable table = parse(readFile(legacy));
            if (table != null) addEntries(merged, table.entries());
        }

        DeviceModelTable asset = parse(readAsset(context));
        if (asset != null) addEntries(merged, asset.entries());

        return merged.isEmpty() ? DeviceModelTable.EMPTY : new DeviceModelTable(merged);
    }

    /** 已有的品牌设备表（按文件名排序） */
    public static List<BrandTableInfo> listBrandTables(Context context) {
        List<BrandTableInfo> result = new ArrayList<>();
        File[] files = tablesDir(context).listFiles();
        if (files == null) return result;
        Arrays.sort(files, BY_NAME);
        for (File file : files) {
            if (!isJsonFile(file)) continue;
            String brand = file.getName().substring(0, file.getName().length() - ".json".length());
            DeviceModelTable table = parse(readFile(file));
            result.add(new BrandTableInfo(brand, table == null ? -1 : table.size()));
        }
        return result;
    }

    /**
     * 按品牌导入设备表：原样复制用户选的文件到 files/device_tables/&lt;品牌&gt;.json。
     * 解析失败（或品牌为空）返回 null，不落盘。
     */
    public static ImportedBrand importBrand(Context context, String brand, String json) {
        if (brand == null || brand.trim().isEmpty()) return null;
        DeviceModelTable table = parse(json);
        if (table == null) return null;

        String name = sanitizeBrand(brand);
        if (name.isEmpty()) return null;

        FileOutputStream out = null;
        try {
            File file = new File(tablesDir(context), name + ".json");
            out = new FileOutputStream(file);
            out.write(json.getBytes(Charset.forName("UTF-8")));
            out.flush();
            return new ImportedBrand(name, table.size());
        } catch (Throwable t) {
            return null;
        } finally {
            close(out);
        }
    }

    public static void deleteBrand(Context context, String brand) {
        if (brand == null || brand.trim().isEmpty()) return;
        File file = new File(tablesDir(context), brand + ".json");
        if (file.isFile()) file.delete();
    }

    private static boolean isJsonFile(File file) {
        return file.isFile() && file.getName().toLowerCase().endsWith(".json");
    }

    private static void addEntries(List<DeviceModelTable.Entry> target, List<DeviceModelTable.Entry> source) {
        for (DeviceModelTable.Entry entry : source) {
            if (!containsEntry(target, entry)) target.add(entry);
        }
    }

    private static boolean containsEntry(List<DeviceModelTable.Entry> list, DeviceModelTable.Entry candidate) {
        for (DeviceModelTable.Entry entry : list) {
            if (entry.match == candidate.match && entry.model.equalsIgnoreCase(candidate.model)) {
                return true;
            }
        }
        return false;
    }

    /** 只去掉不能用作文件名的字符；中文、空格都保留 */
    private static String sanitizeBrand(String brand) {
        String text = brand.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < 40; i++) {
            char c = text.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                continue;
            }
            if (Character.isISOControl(c)) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String readAsset(Context context) {
        InputStream in = null;
        try {
            in = context.getAssets().open(LEGACY_FILE);
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
            // 忽略关闭异常
        }
    }

    private static void close(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (Throwable ignored) {
            // 忽略关闭异常
        }
    }

    /**
     * 解析设备表 JSON。顶层可以是 {"devices":[...]}，也可以直接是数组。
     * 返回 null 表示格式不对（不是"空表"）。
     */
    public static DeviceModelTable parse(String json) {
        if (json == null) return null;
        String text = JsonRepair.repair(json);
        if (text == null || text.isEmpty()) return null;
        try {
            JSONArray devices = null;
            if (text.startsWith("{")) {
                devices = new JSONObject(text).optJSONArray("devices");
            } else if (text.startsWith("[")) {
                // 也接受直接一个数组的写法
                devices = new JSONArray(text);
            }
            if (devices == null) return null;

            List<DeviceModelTable.Entry> list = new ArrayList<>();
            for (int i = 0; i < devices.length(); i++) {
                JSONObject obj = devices.optJSONObject(i);
                if (obj == null) continue;
                String model = obj.optString("model").trim();
                String series = obj.optString("series").trim();
                String brand = obj.optString("brand").trim();
                if (model.isEmpty() || series.isEmpty()) continue;
                DeviceModelTable.MatchType match =
                        "prefix".equalsIgnoreCase(obj.optString("match"))
                                ? DeviceModelTable.MatchType.PREFIX
                                : DeviceModelTable.MatchType.EXACT;
                list.add(new DeviceModelTable.Entry(
                        model,
                        brand.isEmpty() ? null : brand,
                        match,
                        series,
                        obj.optBoolean("verified", false)));
            }
            return new DeviceModelTable(list);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Comparator<File> BY_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };
}
