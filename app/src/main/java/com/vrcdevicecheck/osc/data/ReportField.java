package com.vrcdevicecheck.osc.data;

import android.content.Context;

import com.vrcdevicecheck.osc.R;

/**
 * 可勾选上报的字段。
 *
 * 枚举声明顺序 = 优先级顺序：VRChat Chatbox 单条上限 144 字符，
 * 超长时从末尾开始丢弃字段（先是"前台应用"，再是 GPU 相关项），
 * 界面上会如实告知丢了哪些。
 *
 * label 只用于界面，跟着界面语言走；发往 Chatbox 的文本另有格式（见 ReportBuilder）。
 */
public enum ReportField {

    BRAND("brand", R.string.field_brand),
    MODEL("model", R.string.field_model),
    CPU_NAME("cpu_name", R.string.field_cpu_name),
    CPU_FREQ("cpu_freq", R.string.field_cpu_freq),
    CPU_TEMP("cpu_temp", R.string.field_cpu_temp),
    CPU_USAGE("cpu_usage", R.string.field_cpu_usage),
    GPU_NAME("gpu_name", R.string.field_gpu_name),
    GPU_FREQ("gpu_freq", R.string.field_gpu_freq),
    GPU_TEMP("gpu_temp", R.string.field_gpu_temp),
    GPU_USAGE("gpu_usage", R.string.field_gpu_usage),
    RAM("ram", R.string.field_ram),
    BATTERY("battery", R.string.field_battery),
    HEART_RATE("heart_rate", R.string.field_heart_rate),
    WEATHER("weather", R.string.field_weather),
    TEMPERATURE("temperature", R.string.field_temperature),
    HUMIDITY("humidity", R.string.field_humidity),
    PRESSURE("pressure", R.string.field_pressure),
    WIND("wind", R.string.field_wind),
    UV("uv", R.string.field_uv),
    SUNSET("sunset", R.string.field_sunset),
    MUSIC_TITLE("music_title", R.string.field_music_title),
    MUSIC_ARTIST("music_artist", R.string.field_music_artist),
    MUSIC_ALBUM("music_album", R.string.field_music_album),
    MUSIC_PROGRESS("music_progress", R.string.field_music_progress),
    MUSIC_LYRIC("music_lyric", R.string.field_music_lyric),
    FOREGROUND_APP("foreground_app", R.string.field_foreground_app),
    VRCHAT_FPS("vrchat_fps", R.string.field_vrchat_fps);

    public final String key;
    /** 界面显示用的文案资源 */
    public final int labelRes;

    ReportField(String key, int labelRes) {
        this.key = key;
        this.labelRes = labelRes;
    }

    /** 已按当前界面语言本地化的名字 */
    public String label(Context context) {
        return context == null ? "" : context.getString(labelRes);
    }

    public static ReportField fromKey(String key) {
        for (ReportField field : values()) {
            if (field.key.equals(key)) return field;
        }
        return null;
    }

    /** 天气类字段需要先选城市，且共用天气行 */
    public boolean isWeather() {
        return this == WEATHER || this == TEMPERATURE || this == HUMIDITY
                || this == PRESSURE || this == WIND || this == UV || this == SUNSET;
    }

    /** 音乐类字段需要「通知使用权」，且共用歌曲行 / 歌词行 */
    public boolean isMusic() {
        return this == MUSIC_TITLE || this == MUSIC_ARTIST
                || this == MUSIC_ALBUM || this == MUSIC_PROGRESS
                || this == MUSIC_LYRIC;
    }

    /** 健康类字段需要额外权限 / 外部设备，默认不勾选 */
    public boolean isHealth() {
        return this == HEART_RATE;
    }
}
