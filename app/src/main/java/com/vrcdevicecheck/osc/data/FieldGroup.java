package com.vrcdevicecheck.osc.data;

import android.content.Context;

import com.vrcdevicecheck.osc.R;

import java.util.Set;

/**
 * 上报字段的二级分组：主页只显示分组入口，点开后再选具体字段。
 * 枚举声明顺序 = 主页显示顺序。
 */
public enum FieldGroup {

    HARDWARE("hardware", R.string.group_hardware,
            ReportField.BRAND, ReportField.MODEL,
            ReportField.CPU_NAME, ReportField.CPU_FREQ, ReportField.CPU_TEMP, ReportField.CPU_USAGE,
            ReportField.GPU_NAME, ReportField.GPU_FREQ, ReportField.GPU_TEMP, ReportField.GPU_USAGE,
            ReportField.RAM, ReportField.BATTERY),
    WEATHER("weather", R.string.group_weather,
            ReportField.WEATHER, ReportField.TEMPERATURE, ReportField.HUMIDITY,
            ReportField.PRESSURE, ReportField.WIND, ReportField.UV, ReportField.SUNSET),
    MUSIC("music", R.string.group_music,
            ReportField.MUSIC_TITLE, ReportField.MUSIC_ARTIST,
            ReportField.MUSIC_ALBUM, ReportField.MUSIC_PROGRESS,
            ReportField.MUSIC_LYRIC),
    HEALTH("health", R.string.group_health,
            ReportField.HEART_RATE),
    OTHER("other", R.string.group_other,
            ReportField.FOREGROUND_APP, ReportField.VRCHAT_FPS);

    public final String key;
    public final int labelRes;
    private final ReportField[] fields;

    FieldGroup(String key, int labelRes, ReportField... fields) {
        this.key = key;
        this.labelRes = labelRes;
        this.fields = fields;
    }

    public String label(Context context) {
        return context == null ? "" : context.getString(labelRes);
    }

    public ReportField[] fields() {
        return fields.clone();
    }

    public int fieldCount() {
        return fields.length;
    }

    public boolean contains(ReportField field) {
        if (field == null) return false;
        for (ReportField item : fields) {
            if (item == field) return true;
        }
        return false;
    }

    public int selectedCount(Set<ReportField> enabled) {
        if (enabled == null) return 0;
        int count = 0;
        for (ReportField field : fields) {
            if (enabled.contains(field)) count++;
        }
        return count;
    }

    public static FieldGroup fromKey(String key) {
        for (FieldGroup group : values()) {
            if (group.key.equals(key)) return group;
        }
        return null;
    }
}