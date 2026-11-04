package com.vrcdevicecheck.osc.data;

import android.content.Context;
import android.content.SharedPreferences;

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

    private static final String PREF_NAME = "vrcdi_settings";
    private static final String KEY_IP = "target_ip";
    private static final String KEY_LOCAL_TARGET = "target_is_local";
    private static final String KEY_PORT = "target_port";
    private static final String KEY_INTERVAL = "interval_sec";
    private static final String KEY_MODE = "privilege_mode";
    private static final String KEY_ASCII_ONLY = "ascii_only";
    private static final String KEY_SEND_IMMEDIATELY = "send_immediately";
    private static final String KEY_NOTIFICATION_SOUND = "notification_sound";
    private static final String KEY_FIELDS = "enabled_fields";
    private static final String KEY_MODEL_MODE = "model_display_mode";
    private static final String KEY_UPDATE_SOURCE = "update_source";
    private static final String KEY_BATTERY_HINT = "battery_hint_shown";

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
        return sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
    }

    public void setIntervalSec(int value) {
        sp.edit().putInt(KEY_INTERVAL, Math.max(MIN_INTERVAL, Math.min(3600, value))).apply();
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

    /** 已勾选的字段；首次运行默认全选 */
    public Set<ReportField> getEnabledFields() {
        Set<String> stored = sp.getStringSet(KEY_FIELDS, null);
        if (stored == null) return EnumSet.allOf(ReportField.class);
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
}
