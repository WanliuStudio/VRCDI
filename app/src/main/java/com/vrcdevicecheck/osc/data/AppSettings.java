package com.vrcdevicecheck.osc.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.vrcdevicecheck.osc.BuildConfig;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 应用配置，全部存本地 SharedPreferences。
 * 不含任何账号凭据；IP、端口、间隔均可改，代码里不硬编码任何地址。
 */
public class AppSettings {

    public static final int DEFAULT_PORT = 9000;
    public static final int DEFAULT_INTERVAL = 10;
    public static final int MIN_INTERVAL = 2;
    public static final int MAX_INTERVAL = 3600;

    private static final String PREF_NAME = "vrcdi_settings";
    private static final String KEY_IP = "target_ip";
    private static final String KEY_LOCAL_TARGET = "target_is_local";
    private static final String KEY_PORT = "target_port";
    private static final String KEY_INTERVAL = "interval_sec";
    private static final String KEY_SEND_ON_START = "send_on_start";
    private static final String KEY_WEATHER_PROVINCE = "weather_province";
    private static final String KEY_WEATHER_CITY = "weather_city";
    /** 国外城市才有：国家名与国家代码，国内城市为空 */
    private static final String KEY_WEATHER_COUNTRY = "weather_country";
    private static final String KEY_WEATHER_COUNTRY_CODE = "weather_country_code";
    /** 搜索选中的城市直接存经纬度，省掉一次地理编码，顺带解决同名城市 */
    private static final String KEY_WEATHER_LAT = "weather_lat";
    private static final String KEY_WEATHER_LON = "weather_lon";
    private static final String KEY_QWEATHER_HOST = "qweather_host";
    private static final String KEY_QWEATHER_KEY = "qweather_key";
    private static final String KEY_HIDE_UNAVAILABLE = "hide_unavailable";
    private static final String KEY_MODE = "privilege_mode";
    private static final String KEY_ASCII_ONLY = "ascii_only";
    private static final String KEY_SEND_IMMEDIATELY = "send_immediately";
    private static final String KEY_NOTIFICATION_SOUND = "notification_sound";
    private static final String KEY_FIELDS = "enabled_fields";
    private static final String KEY_MODEL_MODE = "model_display_mode";
    private static final String KEY_UPDATE_SOURCE = "update_source";
    private static final String KEY_AUTO_UPDATE = "auto_update_check";
    private static final String KEY_AUTO_UPDATE_ASKED = "auto_update_asked";
    private static final String KEY_UPDATE_CHANNEL = "update_channel";
    private static final String KEY_BATTERY_HINT = "battery_hint_shown";
    /** 首次启动引导（选语言 + OSC 教学）是否已经走过 */
    private static final String KEY_ONBOARDED = "onboarded";

    private final SharedPreferences sp;

    public AppSettings(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** "本机"模式的固定目标：VRChat 与 VRCDI 运行在同一台设备（一体机 / 手机自连） */
    public static final String LOCALHOST = "127.0.0.1";

    /**
     * 目标是否设为"本机"（127.0.0.1）。
     * 老版本只存过 target_ip：如果里面正好是 127.0.0.1 / localhost，就按"本机"迁移过来，
     * 没存过则默认"自定义"，不改变原有行为。
     */
    public boolean isLocalTarget() {
        if (sp.contains(KEY_LOCAL_TARGET)) {
            return sp.getBoolean(KEY_LOCAL_TARGET, false);
        }
        String stored = getCustomIp();
        return LOCALHOST.equals(stored) || "localhost".equalsIgnoreCase(stored);
    }

    public void setLocalTarget(boolean local) {
        sp.edit().putBoolean(KEY_LOCAL_TARGET, local).apply();
    }

    /** 用户手填的 IP，与"本机"模式互不影响 */
    public String getCustomIp() {
        String value = sp.getString(KEY_IP, "");
        return value == null ? "" : value;
    }

    public void setTargetIp(String value) {
        sp.edit().putString(KEY_IP, value == null ? "" : value.trim()).apply();
    }

    /** 实际发送目标：本机模式固定 127.0.0.1，否则用手填的 IP */
    public String getTargetIp() {
        return isLocalTarget() ? LOCALHOST : getCustomIp();
    }

    /** VRChat 的 OSC 接收端口，默认 9000 */
    public int getTargetPort() {
        return sp.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setTargetPort(int value) {
        sp.edit().putInt(KEY_PORT, Math.max(1, Math.min(65535, value))).apply();
    }

    /** 上报间隔（秒）。间隔越大越省电，也越不容易触发 Chatbox 限频 */
    public int getIntervalSec() {
        int value = sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
        return Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, value));
    }

    public void setIntervalSec(int value) {
        sp.edit().putInt(KEY_INTERVAL,
                Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, value))).apply();
    }

    /** 点「启动上报」时是否立刻采集并发送一次（默认开） */
    public boolean isSendOnStart() {
        return sp.getBoolean(KEY_SEND_ON_START, true);
    }

    public void setSendOnStart(boolean value) {
        sp.edit().putBoolean(KEY_SEND_ON_START, value).apply();
    }

    // ---------------- 天气城市 ----------------

    public String getWeatherProvince() {
        String value = sp.getString(KEY_WEATHER_PROVINCE, "");
        return value == null ? "" : value;
    }

    public String getWeatherCity() {
        String value = sp.getString(KEY_WEATHER_CITY, "");
        return value == null ? "" : value;
    }

    /** 国外城市所在国家（本地化名称，例如「日本」/「Japan」）；国内城市为空 */
    public String getWeatherCountry() {
        String value = sp.getString(KEY_WEATHER_COUNTRY, "");
        return value == null ? "" : value;
    }

    /** ISO 国家代码，例如 JP / US；国内城市为空 */
    public String getWeatherCountryCode() {
        String value = sp.getString(KEY_WEATHER_COUNTRY_CODE, "");
        return value == null ? "" : value.trim();
    }

    /** 选城市时已经知道的经纬度；没存过返回 null，交给地理编码去解析 */
    public Float[] getWeatherCoords() {
        if (!sp.contains(KEY_WEATHER_LAT) || !sp.contains(KEY_WEATHER_LON)) return null;
        return new Float[]{sp.getFloat(KEY_WEATHER_LAT, 0f), sp.getFloat(KEY_WEATHER_LON, 0f)};
    }

    /** "江西省 上饶市"；国外城市显示成「东京, 日本」；未选择时返回空串 */
    public String getWeatherCityLabel() {
        String province = getWeatherProvince();
        String city = getWeatherCity();
        if (city.isEmpty()) return "";
        String country = getWeatherCountry();
        if (!country.isEmpty() && !isChina(country)) {
            // 同名城市很多（比如好几个 Springfield），带上国家才分得清
            return city + " · " + country;
        }
        if (province.isEmpty() || province.equals(city)) return city;
        return province + " " + city;
    }

    /** 缓存键：带上国家代码，避免不同国家的同名城市共用一份缓存 */
    public String getWeatherPlaceKey() {
        return getCountryCodeForKey() + "/" + getWeatherProvince() + "/" + getWeatherCity();
    }

    private String getCountryCodeForKey() {
        String code = getWeatherCountryCode();
        if (!code.isEmpty()) return code;
        String country = getWeatherCountry();
        return isChina(country) ? "CN" : country;
    }

    private static boolean isChina(String country) {
        if (country == null) return false;
        String value = country.trim();
        return "中国".equals(value) || "China".equalsIgnoreCase(value)
                || "中华人民共和国".equals(value);
    }

    public void setWeatherCity(String province, String city) {
        setWeatherPlace(province, city, "", "", null, null);
    }

    /**
     * 记录选中的城市。国内外共用一套字段：
     *
     * @param province    国内是省，国外是行政区（可能为空）
     * @param city        城市名，保留接口返回的写法
     * @param country     国家名，国内留空
     * @param countryCode ISO 国家代码，例如 JP
     * @param lat         纬度，未知传 null
     * @param lon         经度，未知传 null
     */
    public void setWeatherPlace(String province, String city, String country,
                                String countryCode, Float lat, Float lon) {
        SharedPreferences.Editor editor = sp.edit()
                .putString(KEY_WEATHER_PROVINCE, province == null ? "" : province.trim())
                .putString(KEY_WEATHER_CITY, city == null ? "" : city.trim())
                .putString(KEY_WEATHER_COUNTRY, country == null ? "" : country.trim())
                .putString(KEY_WEATHER_COUNTRY_CODE, countryCode == null ? "" : countryCode.trim());
        if (lat != null && lon != null) {
            editor.putFloat(KEY_WEATHER_LAT, lat).putFloat(KEY_WEATHER_LON, lon);
        } else {
            editor.remove(KEY_WEATHER_LAT).remove(KEY_WEATHER_LON);
        }
        editor.apply();
    }

    public void clearWeatherCity() {
        sp.edit()
                .remove(KEY_WEATHER_PROVINCE)
                .remove(KEY_WEATHER_CITY)
                .remove(KEY_WEATHER_COUNTRY)
                .remove(KEY_WEATHER_COUNTRY_CODE)
                .remove(KEY_WEATHER_LAT)
                .remove(KEY_WEATHER_LON)
                .apply();
    }

    // ---------------- 和风天气兜底 ----------------

    /**
     * 和风天气 2026 年起逐步停用公共地址（devapi.qweather.com 等），
     * 改为每个账号独立的 API Host，形如 h2a9cf3mhs.xy.qweatherapi.com。
     * 没填时退回旧公共地址，只为兼容老配置，新用户应该填自己的 Host。
     */
    public static final String QWEATHER_LEGACY_HOST = "devapi.qweather.com";

    /** 用户可能把 https:// 和结尾的斜杠一起粘进来，这里统一清洗 */
    public String getQWeatherHost() {
        String stored = sp.getString(KEY_QWEATHER_HOST, "");
        String host = stored == null ? "" : stored.trim();
        if (host.isEmpty()) {
            // 设置页没填，用编译进 APK 的内置值（如果有）
            host = BuildConfig.QWEATHER_HOST == null ? "" : BuildConfig.QWEATHER_HOST.trim();
        }
        if (host.isEmpty()) return QWEATHER_LEGACY_HOST;
        if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        } else if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        return host.isEmpty() ? QWEATHER_LEGACY_HOST : host;
    }

    public void setQWeatherHost(String value) {
        sp.edit().putString(KEY_QWEATHER_HOST, value == null ? "" : value.trim()).apply();
    }

    /** 实际生效的 Key：用户填的优先，没填就用内置的 */
    public String getQWeatherKey() {
        String stored = getUserQWeatherKey();
        if (!stored.isEmpty()) return stored;
        String builtIn = BuildConfig.QWEATHER_KEY == null ? "" : BuildConfig.QWEATHER_KEY.trim();
        return builtIn;
    }

    /** 只返回用户在界面上填过的 Key，不含内置凭据（设置页要用它，避免把内置 Key 显示出来） */
    public String getUserQWeatherKey() {
        String value = sp.getString(KEY_QWEATHER_KEY, "");
        return value == null ? "" : value.trim();
    }

    public void setQWeatherKey(String value) {
        sp.edit().putString(KEY_QWEATHER_KEY, value == null ? "" : value.trim()).apply();
    }

    /** 这个安装包里是否编译进了兜底凭据 */
    public static boolean hasBuiltInQWeather() {
        return BuildConfig.QWEATHER_KEY != null && !BuildConfig.QWEATHER_KEY.trim().isEmpty();
    }

    /** 填了 Key（自己填的或内置的）才让和风天气参与兜底 */
    public boolean isQWeatherConfigured() {
        return !getQWeatherKey().isEmpty();
    }

    /** 上报时是否把读到「不可用」的字段整项删掉，节省 144 字符（默认开） */
    public boolean isHideUnavailable() {
        return sp.getBoolean(KEY_HIDE_UNAVAILABLE, true);
    }

    public void setHideUnavailable(boolean value) {
        sp.edit().putBoolean(KEY_HIDE_UNAVAILABLE, value).apply();
    }

    public PrivilegeMode getPrivilegeMode() {
        return PrivilegeMode.fromKey(sp.getString(KEY_MODE, PrivilegeMode.SHIZUKU.key));
    }

    public void setPrivilegeMode(PrivilegeMode mode) {
        sp.edit().putString(KEY_MODE, mode.key).apply();
    }

    /** 「设备型号」这一项上报真实型号，还是上报设备表里的产品名称 */
    public ModelDisplayMode getModelDisplayMode() {
        return ModelDisplayMode.fromKey(
                sp.getString(KEY_MODEL_MODE, ModelDisplayMode.PRODUCT_NAME.key));
    }

    public void setModelDisplayMode(ModelDisplayMode mode) {
        ModelDisplayMode value = mode == null ? ModelDisplayMode.PRODUCT_NAME : mode;
        sp.edit().putString(KEY_MODEL_MODE, value.key).apply();
    }

    /** 检查更新用的源：gitee / github */
    public String getUpdateSource() {
        String value = sp.getString(KEY_UPDATE_SOURCE, "gitee");
        if (value == null || value.isEmpty()) value = "gitee";
        return value;
    }

    public void setUpdateSource(String source) {
        String value = "github".equalsIgnoreCase(source) ? "github" : "gitee";
        sp.edit().putString(KEY_UPDATE_SOURCE, value).apply();
    }

    /** 启动时是否静默检查更新（默认关） */
    public boolean isAutoUpdateCheck() {
        return sp.getBoolean(KEY_AUTO_UPDATE, false);
    }

    public void setAutoUpdateCheck(boolean enabled) {
        sp.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply();
    }

    /** 第一次安装时问过「要不要开自动更新」没有 */
    public boolean hasAskedAutoUpdate() {
        return sp.getBoolean(KEY_AUTO_UPDATE_ASKED, false);
    }

    public void markAutoUpdateAsked() {
        sp.edit().putBoolean(KEY_AUTO_UPDATE_ASKED, true).apply();
    }

    /** 更新频道：stable（正式版）/ beta（测试版，含预发布） */
    public String getUpdateChannel() {
        String value = sp.getString(KEY_UPDATE_CHANNEL, "stable");
        return "beta".equalsIgnoreCase(value) ? "beta" : "stable";
    }

    public void setUpdateChannel(String channel) {
        sp.edit().putString(KEY_UPDATE_CHANNEL,
                "beta".equalsIgnoreCase(channel) ? "beta" : "stable").apply();
    }

    public boolean isBetaChannel() {
        return "beta".equals(getUpdateChannel());
    }

    /**
     * 仅发送 ASCII。
     * VRChat 的 OSC 输入端点对非 ASCII 文本的支持存在版本差异（早期官方说明只支持 ASCII，
     * 后续计划支持 UTF-8，Chatbox 本身支持 UTF-8）。打开本开关可确保一定显示，代价是中文变问号。
     */
    public boolean isAsciiOnly() {
        return sp.getBoolean(KEY_ASCII_ONLY, false);
    }

    public void setAsciiOnly(boolean value) {
        sp.edit().putBoolean(KEY_ASCII_ONLY, value).apply();
    }

    /** /chatbox/input 第二个参数：true 直接发出，false 只填进输入框 */
    public boolean isSendImmediately() {
        return sp.getBoolean(KEY_SEND_IMMEDIATELY, true);
    }

    public void setSendImmediately(boolean value) {
        sp.edit().putBoolean(KEY_SEND_IMMEDIATELY, value).apply();
    }

    /** /chatbox/input 第三个参数：是否播放提示音 */
    public boolean isNotificationSound() {
        return sp.getBoolean(KEY_NOTIFICATION_SOUND, false);
    }

    public void setNotificationSound(boolean value) {
        sp.edit().putBoolean(KEY_NOTIFICATION_SOUND, value).apply();
    }

    /** 已勾选的字段；首次运行默认全选（天气类除外，需要先选城市再手动勾选） */
    public Set<ReportField> getEnabledFields() {
        Set<String> stored = sp.getStringSet(KEY_FIELDS, null);
        if (stored == null) {
            Set<ReportField> defaults = EnumSet.allOf(ReportField.class);
            for (ReportField field : ReportField.values()) {
                // 天气要选城市、音乐要开通知使用权、心率要连手环，都不适合默认勾选
                if (field.isWeather() || field.isMusic() || field.isHealth()) {
                    defaults.remove(field);
                }
            }
            return defaults;
        }
        Set<ReportField> result = EnumSet.noneOf(ReportField.class);
        for (ReportField field : ReportField.values()) {
            if (stored.contains(field.key)) result.add(field);
        }
        return result;
    }

    public void setEnabledFields(Set<ReportField> fields) {
        Set<String> keys = new HashSet<>();
        for (ReportField field : fields) keys.add(field.key);
        // SharedPreferences 的 StringSet 会被内部缓存，必须传新集合
        sp.edit().putStringSet(KEY_FIELDS, keys).apply();
    }

    /** 是否已提示过「关闭电池优化」，避免反复打扰 */
    public boolean isBatteryHintShown() {
        return sp.getBoolean(KEY_BATTERY_HINT, false);
    }

    public void setBatteryHintShown(boolean value) {
        sp.edit().putBoolean(KEY_BATTERY_HINT, value).apply();
    }

    // ---------------- 首次启动引导 ----------------

    /**
     * 是否需要弹首次启动引导（选语言 → OSC 教学）。
     *
     * 老版本升级上来的用户不该被强行塞一遍引导：只要配置里已经有值，
     * 就当作「用过了」，顺手补上标记。真正的新装（一个配置都没有）才返回 true。
     */
    public boolean needsOnboarding() {
        if (sp.getBoolean(KEY_ONBOARDED, false)) return false;
        if (sp.contains(KEY_INTERVAL) || sp.contains(KEY_FIELDS)
                || sp.contains(KEY_MODE) || sp.contains(KEY_PORT)) {
            sp.edit().putBoolean(KEY_ONBOARDED, true).apply();
            return false;
        }
        return true;
    }

    public void setOnboarded() {
        sp.edit().putBoolean(KEY_ONBOARDED, true).apply();
    }
}
