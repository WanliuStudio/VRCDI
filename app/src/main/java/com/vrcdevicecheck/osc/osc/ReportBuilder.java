package com.vrcdevicecheck.osc.osc;

import android.content.Context;

import com.vrcdevicecheck.osc.R;
import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.media.MusicInfo;
import com.vrcdevicecheck.osc.weather.WeatherInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把设备快照拼成 Chatbox 文本。纯逻辑，可单元测试。
 *
 * 文本语言跟界面语言走（zh / en / ja / ru / ko）；
 * 打开「仅发送 ASCII」时强制用英文 ASCII 写法，避免 VRChat 显示成问号。
 *
 * 输出是分段的（每段一行），形如：
 *
 *   OPPO PCAT00
 *   CPU: Qualcomm SM8550 2.21GHz 46.1℃ 10%
 *   GPU: Adreno 740 不可用 41.3℃ 不可用
 *   RAM: 3.9/5.5GB 71%
 *   电量: 45% 充电中
 *   天气: 多云 17.3℃ 湿度 86%
 *   气压 1018hPa 西南风 3.2m/s 紫外线 5 日落 17:14
 *   App: VRCDI（本应用）
 *
 * 硬约束：VRChat Chatbox 单条上限 144 字符（含换行）。超长时从最后一段开始整段丢弃，
 * 并把被丢掉的字段回传给界面如实提示，绝不静默截断。
 *
 * 「自动屏蔽不可用」开启时，值为「不可用」的字段不进入文本，也不占 144 字符；
 * 这些字段记在 {@link Report#hidden}，与「超长被省略」的 {@link Report#dropped} 区分开。
 */
public final class ReportBuilder {

    public static final int CHATBOX_LIMIT = 144;

    /**
     * 内部占位符：表示「这项没读到」。
     * 它只是逻辑标记，真正写进文本时按语言换成 N/A / 不可用 / 利用不可 / Недоступно。
     */
    private static final String NA = "不可用";

    /** 一次渲染结果 */
    public static final class Report {
        public final String text;
        public final List<ReportField> included;
        /** 因超过 144 字符而被整行丢弃的字段 */
        public final List<ReportField> dropped;
        /** 因值为「不可用」而被自动隐藏的字段（不占字符） */
        public final List<ReportField> hidden;
        public final boolean hardTruncated;

        Report(String text, List<ReportField> included, List<ReportField> dropped,
               List<ReportField> hidden, boolean hardTruncated) {
            this.text = text;
            this.included = included;
            this.dropped = dropped;
            this.hidden = hidden;
            this.hardTruncated = hardTruncated;
        }

        public int charCount() {
            return text.length();
        }

        public boolean overLimit() {
            return hardTruncated || !dropped.isEmpty();
        }

        /**
         * 界面提示文案：只提示「超长被省略」，不把自动隐藏算进去。
         * 这是界面文字，跟着界面语言走（和上报正文的语言是两回事）。
         */
        public String limitHint(Context context) {
            if (hardTruncated) return context.getString(R.string.hint_hard_truncated);
            if (dropped.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dropped.size(); i++) {
                if (i > 0) sb.append(context.getString(R.string.list_separator));
                sb.append(dropped.get(i).label(context));
            }
            return context.getString(R.string.hint_dropped, sb.toString());
        }
    }

    /**
     * 上报正文用的词表。中文那套与改造前逐字一致，不要随意改。
     */
    private static final class Words {
        final String na;
        final String battery;
        final String heartRate;
        final String weather;
        final String pressure;
        final String wind;
        final String uv;
        final String sunset;
        final String speedOnly;
        final String charging;
        final String humidityFormat;
        final String tempFormat;
        final String[] directions;

        Words(String na, String battery, String heartRate, String weather, String pressure,
              String wind, String uv, String sunset, String speedOnly, String charging,
              String humidityFormat, String tempFormat, String[] directions) {
            this.na = na;
            this.battery = battery;
            this.heartRate = heartRate;
            this.weather = weather;
            this.pressure = pressure;
            this.wind = wind;
            this.uv = uv;
            this.sunset = sunset;
            this.speedOnly = speedOnly;
            this.charging = charging;
            this.humidityFormat = humidityFormat;
            this.tempFormat = tempFormat;
            this.directions = directions;
        }
    }

    private static final Words WORDS_ZH = new Words(
            "不可用", "电量: ", "心率: ", "天气: ", "气压 ", "风向风速 ", "紫外线 ", "日落 ",
            "风速 ", "充电中", "湿度 %d%%", "%.1f℃",
            new String[]{"北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风"});

    private static final Words WORDS_EN = new Words(
            "N/A", "Battery: ", "Heart rate: ", "Weather: ", "Pressure ", "Wind ",
            "UV ", "Sunset ", "Wind ", "Charging", "Humidity %d%%", "%.1fC",
            new String[]{"N wind", "NE wind", "E wind", "SE wind",
                    "S wind", "SW wind", "W wind", "NW wind"});

    private static final Words WORDS_JA = new Words(
            "利用不可", "バッテリー: ", "心拍数: ", "天気: ", "気圧 ", "風 ", "紫外線 ",
            "日の入り ", "風速 ", "充電中", "湿度 %d%%", "%.1f℃",
            new String[]{"北の風", "北東の風", "東の風", "南東の風",
                    "南の風", "南西の風", "西の風", "北西の風"});

    private static final Words WORDS_RU = new Words(
            "Недоступно", "Батарея: ", "Пульс: ", "Погода: ", "Давление ", "Ветер ",
            "УФ ", "Закат ", "Скорость ", "Заряжается", "Влажность %d%%", "%.1f°C",
            new String[]{"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"});

    private static final Words WORDS_KO = new Words(
            "사용 불가", "배터리: ", "심박수: ", "날씨: ", "기압 ", "바람 ", "자외선 ",
            "일몰 ", "풍속 ", "충전 중", "습도 %d%%", "%.1f℃",
            new String[]{"북풍", "북동풍", "동풍", "남동풍",
                    "남풍", "남서풍", "서풍", "북서풍"});

    /** 一行文本 + 这一行包含的字段（用来算"丢了什么"） */
    private static final class Line {
        final String text;
        final List<ReportField> fields;

        Line(String text, List<ReportField> fields) {
            this.text = text;
            this.fields = fields;
        }
    }

    /**
     * 一行文本的构造器：值为不可用时，按开关决定是写「不可用」还是直接隐藏。
     */
    private static final class LineBuilder {
        final StringBuilder text = new StringBuilder();
        final List<ReportField> fields = new ArrayList<>(4);
        final Set<ReportField> hidden;
        final boolean hideUnavailable;
        final Words words;

        LineBuilder(Set<ReportField> hidden, boolean hideUnavailable, Words words) {
            this.hidden = hidden;
            this.hideUnavailable = hideUnavailable;
            this.words = words;
        }

        void add(ReportField field, String value) {
            if (isAvailable(value)) {
                append(text, value);
                fields.add(field);
            } else if (!hideUnavailable) {
                append(text, words.na);
                fields.add(field);
            } else {
                hidden.add(field);
            }
        }

        /** 带标签的不可用项，例如「气压 不可用」 */
        void addUnavailable(ReportField field, String label) {
            if (hideUnavailable) {
                hidden.add(field);
            } else {
                append(text, label + words.na);
                fields.add(field);
            }
        }

        /** 追加不属于独立字段的后缀，例如「充电中」 */
        void suffix(String value) {
            append(text, value);
        }

        boolean isEmpty() {
            return fields.isEmpty();
        }
    }

    private ReportBuilder() {
    }

    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly) {
        return build(snapshot, fields, asciiOnly, true, null, false, "zh");
    }

    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly,
                               boolean useProductName) {
        return build(snapshot, fields, asciiOnly, useProductName, null, false, "zh");
    }

    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly,
                               boolean useProductName, WeatherInfo weather) {
        return build(snapshot, fields, asciiOnly, useProductName, weather, false, "zh");
    }

    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly,
                               boolean useProductName, WeatherInfo weather, boolean hideUnavailable) {
        return build(snapshot, fields, asciiOnly, useProductName, weather, hideUnavailable, "zh");
    }

    /**
     * @param weather         可为 null；为 null 时天气行显示「不可用」或被隐藏
     * @param hideUnavailable true 时，任何读到「不可用」的字段都不进文本，节省 144 字符
     * @param lang            上报文本语言：zh / en / ja / ru / ko；asciiOnly 时强制英文
     */
    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly,
                               boolean useProductName, WeatherInfo weather, boolean hideUnavailable,
                               String lang) {
        return build(snapshot, fields, asciiOnly, useProductName, weather, hideUnavailable,
                lang, null);
    }

    /**
     * @param music 当前正在播放的音乐；为 null 时音乐字段按「不可用」处理
     */
    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly,
                               boolean useProductName, WeatherInfo weather, boolean hideUnavailable,
                               String lang, MusicInfo music) {
        return build(snapshot, fields, asciiOnly, useProductName, weather, hideUnavailable,
                lang, music, null);
    }

    /**
     * @param heartRateBpm 最近一次读到的静息/实时心率；没有数据时传 null
     */
    public static Report build(DeviceSnapshot snapshot, Set<ReportField> fields, boolean asciiOnly,
                               boolean useProductName, WeatherInfo weather, boolean hideUnavailable,
                               String lang, MusicInfo music, Integer heartRateBpm) {
        List<ReportField> ordered = new ArrayList<>();
        for (ReportField field : ReportField.values()) {
            if (fields != null && fields.contains(field)) ordered.add(field);
        }
        if (ordered.isEmpty()) {
            return new Report("", ordered, ordered, Collections.<ReportField>emptyList(), false);
        }

        String reportLang = asciiOnly ? "en" : (lang == null ? "zh" : lang);
        Words words = wordsFor(reportLang);

        Set<ReportField> hidden = EnumSet.noneOf(ReportField.class);
        List<Line> lines = buildLines(snapshot, ordered, reportLang, words, useProductName,
                weather, hideUnavailable, asciiOnly, music, heartRateBpm, hidden);
        while (lineText(lines).length() > CHATBOX_LIMIT && lines.size() > 1) {
            lines.remove(lines.size() - 1);
        }

        String text = lineText(lines);
        boolean hard = false;
        if (text.length() > CHATBOX_LIMIT) {
            text = text.substring(0, CHATBOX_LIMIT - 1) + "…";
            hard = true;
        }

        Set<ReportField> includedSet = new LinkedHashSet<>();
        for (Line line : lines) includedSet.addAll(line.fields);

        List<ReportField> kept = new ArrayList<>();
        List<ReportField> dropped = new ArrayList<>();
        for (ReportField field : ordered) {
            if (includedSet.contains(field)) kept.add(field);
            else if (!hidden.contains(field)) dropped.add(field);
        }
        List<ReportField> hiddenList = new ArrayList<>(hidden);
        return new Report(text, kept, dropped, hiddenList, hard);
    }

    private static Words wordsFor(String lang) {
        if ("en".equals(lang)) return WORDS_EN;
        if ("ja".equals(lang)) return WORDS_JA;
        if ("ru".equals(lang)) return WORDS_RU;
        if ("ko".equals(lang)) return WORDS_KO;
        return WORDS_ZH;
    }

    /** 分段渲染，返回用换行连接的多行文本 */
    public static String render(DeviceSnapshot snapshot, List<ReportField> fields, boolean asciiOnly) {
        return render(snapshot, fields, asciiOnly, true, "zh");
    }

    public static String render(DeviceSnapshot snapshot, List<ReportField> fields, boolean asciiOnly,
                                boolean useProductName) {
        return render(snapshot, fields, asciiOnly, useProductName, "zh");
    }

    public static String render(DeviceSnapshot snapshot, List<ReportField> fields, boolean asciiOnly,
                                boolean useProductName, String lang) {
        Set<ReportField> hidden = EnumSet.noneOf(ReportField.class);
        String reportLang = asciiOnly ? "en" : (lang == null ? "zh" : lang);
        return lineText(buildLines(snapshot, fields, reportLang, wordsFor(reportLang),
                useProductName, null, false, asciiOnly, null, null, hidden));
    }

    /** 是否勾选了任意音乐字段 */
    public static boolean hasMusicFields(Set<ReportField> fields) {
        if (fields == null) return false;
        for (ReportField field : fields) {
            if (field.isMusic()) return true;
        }
        return false;
    }

    private static List<Line> buildLines(DeviceSnapshot snapshot, List<ReportField> fields,
                                         String lang, Words words, boolean useProductName,
                                         WeatherInfo weather, boolean hideUnavailable,
                                         boolean asciiOnly, MusicInfo music,
                                         Integer heartRateBpm, Set<ReportField> hidden) {
        List<Line> lines = new ArrayList<>(7);

        // 第一行：品牌 + 型号（真实型号或产品名称）
        LineBuilder device = new LineBuilder(hidden, hideUnavailable, words);
        if (fields.contains(ReportField.BRAND)) {
            device.add(ReportField.BRAND, orNa(snapshot.brand));
        }
        if (fields.contains(ReportField.MODEL)) {
            String modelText = useProductName && snapshot.seriesName != null
                    ? snapshot.seriesName
                    : snapshot.model;
            device.add(ReportField.MODEL, orNa(modelText));
        }
        if (!device.isEmpty()) {
            lines.add(new Line(device.text.toString(), device.fields));
        }

        // CPU 行
        LineBuilder cpu = new LineBuilder(hidden, hideUnavailable, words);
        if (fields.contains(ReportField.CPU_NAME)) {
            cpu.add(ReportField.CPU_NAME, orNa(snapshot.cpuName));
        }
        if (fields.contains(ReportField.CPU_FREQ)) {
            cpu.add(ReportField.CPU_FREQ, freq(snapshot.cpuFreqMHz));
        }
        if (fields.contains(ReportField.CPU_TEMP)) {
            cpu.add(ReportField.CPU_TEMP, temp(snapshot.cpuTempC, words));
        }
        if (fields.contains(ReportField.CPU_USAGE)) {
            cpu.add(ReportField.CPU_USAGE, pct(snapshot.cpuUsagePct));
        }
        if (!cpu.isEmpty()) {
            lines.add(new Line("CPU: " + cpu.text, cpu.fields));
        }

        // GPU 行
        LineBuilder gpu = new LineBuilder(hidden, hideUnavailable, words);
        if (fields.contains(ReportField.GPU_NAME)) {
            gpu.add(ReportField.GPU_NAME, orNa(snapshot.gpuName));
        }
        if (fields.contains(ReportField.GPU_FREQ)) {
            gpu.add(ReportField.GPU_FREQ, freq(snapshot.gpuFreqMHz));
        }
        if (fields.contains(ReportField.GPU_TEMP)) {
            gpu.add(ReportField.GPU_TEMP, temp(snapshot.gpuTempC, words));
        }
        if (fields.contains(ReportField.GPU_USAGE)) {
            gpu.add(ReportField.GPU_USAGE, pct(snapshot.gpuUsagePct));
        }
        if (!gpu.isEmpty()) {
            lines.add(new Line("GPU: " + gpu.text, gpu.fields));
        }

        // RAM 行
        if (fields.contains(ReportField.RAM)) {
            if (snapshot.ramUsedMb == null || snapshot.ramTotalMb == null || snapshot.ramTotalMb <= 0) {
                if (hideUnavailable) hidden.add(ReportField.RAM);
                else lines.add(new Line("RAM: " + words.na, Collections.singletonList(ReportField.RAM)));
            } else {
                lines.add(new Line(ramLine(snapshot.ramUsedMb, snapshot.ramTotalMb),
                        Collections.singletonList(ReportField.RAM)));
            }
        }

        // 电量行
        if (fields.contains(ReportField.BATTERY)) {
            LineBuilder battery = new LineBuilder(hidden, hideUnavailable, words);
            if (snapshot.batteryPct != null) {
                battery.add(ReportField.BATTERY, snapshot.batteryPct + "%");
                if (snapshot.charging) battery.suffix(words.charging);
            } else if (snapshot.charging) {
                battery.add(ReportField.BATTERY, words.charging);
            } else {
                battery.add(ReportField.BATTERY, NA);
            }
            if (!battery.isEmpty()) {
                lines.add(new Line(words.battery + battery.text, battery.fields));
            }
        }

        // 心率行（手环近 60 秒内有数据才显示）
        if (fields.contains(ReportField.HEART_RATE)) {
            LineBuilder heart = new LineBuilder(hidden, hideUnavailable, words);
            heart.add(ReportField.HEART_RATE,
                    heartRateBpm == null || heartRateBpm <= 0 ? NA : heartRateBpm + "bpm");
            if (!heart.isEmpty()) {
                lines.add(new Line(words.heartRate + heart.text, heart.fields));
            }
        }

        // 天气第一行：天气 / 气温 / 湿度
        boolean weatherFirst = fields.contains(ReportField.WEATHER)
                || fields.contains(ReportField.TEMPERATURE)
                || fields.contains(ReportField.HUMIDITY);
        if (weatherFirst) {
            List<ReportField> selected = new ArrayList<>(3);
            if (fields.contains(ReportField.WEATHER)) selected.add(ReportField.WEATHER);
            if (fields.contains(ReportField.TEMPERATURE)) selected.add(ReportField.TEMPERATURE);
            if (fields.contains(ReportField.HUMIDITY)) selected.add(ReportField.HUMIDITY);

            if (weather == null) {
                if (hideUnavailable) {
                    hidden.addAll(selected);
                } else {
                    lines.add(new Line(words.weather + words.na, selected));
                }
            } else {
                LineBuilder w = new LineBuilder(hidden, hideUnavailable, words);
                if (fields.contains(ReportField.WEATHER)) {
                    w.add(ReportField.WEATHER, weather.condition(lang));
                }
                if (fields.contains(ReportField.TEMPERATURE)) {
                    w.add(ReportField.TEMPERATURE, temp(weather.temperatureC, words));
                }
                if (fields.contains(ReportField.HUMIDITY)) {
                    w.add(ReportField.HUMIDITY, humidity(weather.humidityPct, words));
                }
                if (!w.isEmpty()) {
                    lines.add(new Line(words.weather + w.text, w.fields));
                }
            }
        }

        // 天气第二行：气压 / 风向风速 / 紫外线 / 日落
        boolean weatherSecond = fields.contains(ReportField.PRESSURE)
                || fields.contains(ReportField.WIND)
                || fields.contains(ReportField.UV)
                || fields.contains(ReportField.SUNSET);
        if (weatherSecond) {
            List<ReportField> selected = new ArrayList<>(4);
            if (fields.contains(ReportField.PRESSURE)) selected.add(ReportField.PRESSURE);
            if (fields.contains(ReportField.WIND)) selected.add(ReportField.WIND);
            if (fields.contains(ReportField.UV)) selected.add(ReportField.UV);
            if (fields.contains(ReportField.SUNSET)) selected.add(ReportField.SUNSET);

            if (weather == null) {
                if (hideUnavailable) {
                    hidden.addAll(selected);
                } else {
                    LineBuilder w = new LineBuilder(hidden, false, words);
                    if (fields.contains(ReportField.PRESSURE)) {
                        w.addUnavailable(ReportField.PRESSURE, words.pressure);
                    }
                    if (fields.contains(ReportField.WIND)) {
                        w.addUnavailable(ReportField.WIND, words.wind);
                    }
                    if (fields.contains(ReportField.UV)) {
                        w.addUnavailable(ReportField.UV, words.uv);
                    }
                    if (fields.contains(ReportField.SUNSET)) {
                        w.addUnavailable(ReportField.SUNSET, words.sunset);
                    }
                    if (!w.isEmpty()) lines.add(new Line(w.text.toString(), w.fields));
                }
            } else {
                LineBuilder w = new LineBuilder(hidden, hideUnavailable, words);
                if (fields.contains(ReportField.PRESSURE)) {
                    if (weather.pressureHpa != null) {
                        w.add(ReportField.PRESSURE, pressureText(weather, words));
                    } else {
                        w.addUnavailable(ReportField.PRESSURE, words.pressure);
                    }
                }
                if (fields.contains(ReportField.WIND)) {
                    String wind = windText(weather, words);
                    if (wind != null) {
                        w.add(ReportField.WIND, wind);
                    } else {
                        w.addUnavailable(ReportField.WIND, words.wind);
                    }
                }
                if (fields.contains(ReportField.UV)) {
                    if (weather.uvIndex != null) {
                        w.add(ReportField.UV, uvText(weather, words));
                    } else {
                        w.addUnavailable(ReportField.UV, words.uv);
                    }
                }
                if (fields.contains(ReportField.SUNSET)) {
                    if (weather.sunset != null && !weather.sunset.trim().isEmpty()) {
                        w.add(ReportField.SUNSET, sunsetText(weather, words));
                    } else {
                        w.addUnavailable(ReportField.SUNSET, words.sunset);
                    }
                }
                if (!w.isEmpty()) {
                    lines.add(new Line(w.text.toString(), w.fields));
                }
            }
        }

        // 音乐行：歌名 - 歌手 (专辑)
        boolean musicTrackSelected = fields.contains(ReportField.MUSIC_TITLE)
                || fields.contains(ReportField.MUSIC_ARTIST)
                || fields.contains(ReportField.MUSIC_ALBUM)
                || fields.contains(ReportField.MUSIC_PROGRESS);
        if (musicTrackSelected) {
            List<ReportField> selected = new ArrayList<>(4);
            if (fields.contains(ReportField.MUSIC_TITLE)) selected.add(ReportField.MUSIC_TITLE);
            if (fields.contains(ReportField.MUSIC_ARTIST)) selected.add(ReportField.MUSIC_ARTIST);
            if (fields.contains(ReportField.MUSIC_ALBUM)) selected.add(ReportField.MUSIC_ALBUM);
            if (fields.contains(ReportField.MUSIC_PROGRESS)) {
                selected.add(ReportField.MUSIC_PROGRESS);
            }

            StringBuilder trackText = new StringBuilder();
            List<ReportField> used = new ArrayList<>(4);
            if (music != null) {
                if (fields.contains(ReportField.MUSIC_TITLE) && music.hasTitle()) {
                    trackText.append(music.title.trim());
                    used.add(ReportField.MUSIC_TITLE);
                }
                if (fields.contains(ReportField.MUSIC_ARTIST) && music.hasArtist()) {
                    if (trackText.length() > 0) trackText.append(" - ");
                    trackText.append(music.artist.trim());
                    used.add(ReportField.MUSIC_ARTIST);
                }
                if (fields.contains(ReportField.MUSIC_ALBUM) && music.hasAlbum()) {
                    if (trackText.length() > 0) {
                        trackText.append(" (").append(music.album.trim()).append(')');
                    } else {
                        trackText.append(music.album.trim());
                    }
                    used.add(ReportField.MUSIC_ALBUM);
                }
                if (fields.contains(ReportField.MUSIC_PROGRESS) && music.hasProgress()) {
                    if (trackText.length() > 0) trackText.append(' ');
                    trackText.append(formatTime(music.positionMs))
                            .append(" / ")
                            .append(formatTime(music.durationMs));
                    used.add(ReportField.MUSIC_PROGRESS);
                }
            }
            if (trackText.length() > 0) {
                lines.add(new Line(musicPrefix(asciiOnly) + trackText, used));
            } else if (hideUnavailable) {
                hidden.addAll(selected);
            } else {
                lines.add(new Line(musicPrefix(asciiOnly) + words.na, selected));
            }
        }

        // 当前播放到的歌词行
        if (fields.contains(ReportField.MUSIC_LYRIC)) {
            LineBuilder lyric = new LineBuilder(hidden, hideUnavailable, words);
            lyric.add(ReportField.MUSIC_LYRIC,
                    music != null && music.hasLyric() ? music.lyricLine.trim() : NA);
            if (!lyric.isEmpty()) {
                lines.add(new Line(lyricPrefix(asciiOnly) + lyric.text, lyric.fields));
            }
        }

        // 前台应用行
        if (fields.contains(ReportField.FOREGROUND_APP)) {
            LineBuilder app = new LineBuilder(hidden, hideUnavailable, words);
            app.add(ReportField.FOREGROUND_APP, orNa(snapshot.foregroundApp));
            if (!app.isEmpty()) {
                lines.add(new Line("App: " + app.text, app.fields));
            }
        }

        // 帧率行
        if (fields.contains(ReportField.VRCHAT_FPS)) {
            String fps = snapshot.vrchatFps == null
                    ? NA
                    : String.format(Locale.US, "%.0f", snapshot.vrchatFps);
            LineBuilder fpsLine = new LineBuilder(hidden, hideUnavailable, words);
            fpsLine.add(ReportField.VRCHAT_FPS, fps);
            if (!fpsLine.isEmpty()) {
                lines.add(new Line("FPS: " + fpsLine.text, fpsLine.fields));
            }
        }

        return lines;
    }

    /** 播放位置 / 总时长：1:20 / 4:20；超过一小时用 1:02:03 */
    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** 非 ASCII 模式下用音符前缀；「仅发送 ASCII」时退回纯英文标签 */
    private static String musicPrefix(boolean asciiOnly) {
        return asciiOnly ? "Music: " : "♪ ";
    }

    private static String lyricPrefix(boolean asciiOnly) {
        return asciiOnly ? "Lyric: " : "♪ ";
    }

    private static String lineText(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).text);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(part);
    }

    private static boolean isAvailable(String value) {
        return value != null && !value.trim().isEmpty() && !NA.equals(value.trim());
    }

    private static String orNa(String value) {
        return value == null || value.trim().isEmpty() ? NA : value.trim();
    }

    private static String freq(Integer mhz) {
        if (mhz == null) return NA;
        if (mhz >= 1000) return String.format(Locale.US, "%.2fGHz", mhz / 1000f);
        return mhz + "MHz";
    }

    private static String temp(Float celsius, Words words) {
        if (celsius == null) return NA;
        return String.format(Locale.US, words.tempFormat, celsius);
    }

    private static String pct(Float value) {
        if (value == null) return NA;
        return String.format(Locale.US, "%.0f%%", value);
    }

    private static String humidity(Integer value, Words words) {
        if (value == null) return NA;
        return String.format(Locale.US, words.humidityFormat, value);
    }

    private static String pressureText(WeatherInfo weather, Words words) {
        if (weather.pressureHpa == null) return words.pressure + words.na;
        return String.format(Locale.US, words.pressure + "%.0fhPa", weather.pressureHpa);
    }

    private static String uvText(WeatherInfo weather, Words words) {
        if (weather.uvIndex == null) return words.uv + words.na;
        return words.uv + weather.uvIndex;
    }

    private static String sunsetText(WeatherInfo weather, Words words) {
        if (weather.sunset == null || weather.sunset.trim().isEmpty()) {
            return words.sunset + words.na;
        }
        return words.sunset + weather.sunset.trim();
    }

    /** 风向转 8 方位；风速用 m/s，保留 1 位小数 */
    private static String windText(WeatherInfo weather, Words words) {
        boolean hasDirection = weather.windDirectionDeg != null;
        boolean hasSpeed = weather.windSpeedMs != null;
        if (!hasDirection && !hasSpeed) return null;

        StringBuilder sb = new StringBuilder();
        if (hasDirection) {
            sb.append(directionText(weather.windDirectionDeg, words));
        }
        if (hasSpeed) {
            if (sb.length() > 0) sb.append(' ');
            else sb.append(words.speedOnly);
            sb.append(String.format(Locale.US, "%.1fm/s", weather.windSpeedMs));
        }
        return sb.toString();
    }

    private static String directionText(int degrees, Words words) {
        int normalized = ((degrees % 360) + 360) % 360;
        int index = (int) Math.floor((normalized + 22.5) / 45.0) % 8;
        return words.directions[index];
    }

    private static String ramLine(Long usedMb, Long totalMb) {
        if (usedMb == null || totalMb == null || totalMb <= 0) return "RAM: " + NA;
        int pct = Math.round(usedMb * 100f / totalMb);
        return String.format(Locale.US, "RAM: %.1f/%.1fGB %d%%",
                usedMb / 1024f, totalMb / 1024f, pct);
    }
}
