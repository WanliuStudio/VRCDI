package com.vrcdevicecheck.osc.data;

/**
 * 可勾选上报的字段。
 *
 * 枚举声明顺序 = 优先级顺序：VRChat Chatbox 单条上限 144 字符，
 * 超长时从末尾开始丢弃字段（先是"前台应用"，再是 GPU 相关项），
 * 界面上会如实告知丢了哪些。
 */
public enum ReportField {

    BRAND("brand", "设备品牌"),
    MODEL("model", "设备型号"),
    CPU_NAME("cpu_name", "CPU 名称"),
    CPU_FREQ("cpu_freq", "CPU 频率"),
    CPU_TEMP("cpu_temp", "CPU 温度"),
    CPU_USAGE("cpu_usage", "CPU 使用率"),
    GPU_NAME("gpu_name", "GPU 名称"),
    GPU_FREQ("gpu_freq", "GPU 频率"),
    GPU_TEMP("gpu_temp", "GPU 温度"),
    GPU_USAGE("gpu_usage", "GPU 使用率"),
    RAM("ram", "RAM 占用/总量"),
    BATTERY("battery", "电量与充电状态"),
    FOREGROUND_APP("foreground_app", "前台应用"),
    VRCHAT_FPS("vrchat_fps", "VRChat 帧率");

    public final String key;
    public final String label;

    ReportField(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public static ReportField fromKey(String key) {
        for (ReportField field : values()) {
            if (field.key.equals(key)) return field;
        }
        return null;
    }
}
