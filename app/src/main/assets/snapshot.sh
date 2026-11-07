#!/system/bin/sh
# VRCDI 设备信息采集脚本
# 由 Shizuku(shell uid 2000) 或 root 执行，一次调用输出全部指标，尽量降低开销。
# 输出格式：每行 key|value，无值的键直接不输出（由调用方判定为"不可用"）。
# 说明：sysfs 节点路径因厂商/内核差异很大，这里一次性把所有已知候选路径都尝试一遍，
#      由 Java 侧按优先级挑选，读不到的自然不会出现。

r() {
    if [ -r "$2" ]; then
        printf '%s|' "$1"
        cat "$2" 2>/dev/null | tr -d '\r\n'
        printf '\n'
    fi
}

g() {
    printf '%s|' "$1"
    getprop "$2" 2>/dev/null | tr -d '\r\n'
    printf '\n'
}

echo "@BEGIN"

# ---------- 设备与 SoC 标识 ----------
g prop.soc.model ro.soc.model
g prop.soc.manufacturer ro.soc.manufacturer
g prop.board.platform ro.board.platform
g prop.hardware ro.hardware
g prop.hardware.egl ro.hardware.egl
g prop.model ro.product.model
g prop.brand ro.product.brand
g prop.manufacturer ro.product.manufacturer
g prop.device ro.product.device
# 厂商市场名（各厂商属性名不同，能读到哪个用哪个）
g prop.marketname ro.product.marketname
g prop.vendor.marketname ro.product.vendor.marketname
g prop.odm.marketname ro.product.odm.marketname
g prop.oppo.marketname ro.product.oppo.marketname
g prop.vivo.marketname ro.product.vivo.marketname
g prop.marketing_name ro.config.marketing_name

# ---------- CPU 使用率原始计数（两次采样做差）----------
printf 'stat.cpu|'
head -1 /proc/stat 2>/dev/null | tr -d '\r\n'
printf '\n'

# ---------- 内存 ----------
if [ -r /proc/meminfo ]; then
    grep -E '^Mem(Total|Available|Free):' /proc/meminfo 2>/dev/null | while read -r k v u; do
        printf 'mem.%s|%s\n' "${k%:}" "$v"
    done
fi

# ---------- CPU 频率 ----------
maxcur=0
for c in /sys/devices/system/cpu/cpu[0-9]*; do
    f="$c/cpufreq/scaling_cur_freq"
    [ -r "$f" ] || continue
    v=$(cat "$f" 2>/dev/null | tr -dc '0-9')
    [ -n "$v" ] || continue
    if [ "$v" -gt "$maxcur" ]; then maxcur=$v; fi
done
if [ "$maxcur" -gt 0 ]; then printf 'cpufreq.cur_max|%s\n' "$maxcur"; fi
r cpufreq.cpu0_max /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq

# ---------- 温度（thermal zone 全量导出，Java 侧按名称匹配）----------
for z in /sys/class/thermal/thermal_zone*; do
    [ -e "$z/type" ] || continue
    t=$(cat "$z/type" 2>/dev/null | tr -d '\r\n')
    v=$(cat "$z/temp" 2>/dev/null | tr -d '\r\n')
    if [ -n "$t" ] && [ -n "$v" ]; then printf 'thermal.%s|%s\n' "$t" "$v"; fi
done

# ---------- GPU：高通 Adreno (kgsl) ----------
r gpu.kgsl.model /sys/class/kgsl/kgsl-3d0/gpu_model
r gpu.kgsl.gpuclk /sys/class/kgsl/kgsl-3d0/gpuclk
r gpu.kgsl.clock_mhz /sys/class/kgsl/kgsl-3d0/clock_mhz
r gpu.kgsl.busy_pct /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
r gpu.kgsl.gpubusy /sys/class/kgsl/kgsl-3d0/gpubusy
r gpu.kgsl.temp /sys/class/kgsl/kgsl-3d0/temp
r gpu.kgsl.df.cur /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
r gpu.kgsl.df.max /sys/class/kgsl/kgsl-3d0/devfreq/max_freq

# ---------- GPU：通用 devfreq（Mali / Rockchip 等）----------
found=""
for d in /sys/class/devfreq/*; do
    [ -e "$d/name" ] || continue
    n=$(cat "$d/name" 2>/dev/null | tr -d '\r\n')
    case "$n" in
        *mali* | *Mali* | *gpu* | *GPU*) ;;
        *) continue ;;
    esac
    [ -z "$found" ] || continue
    found="$n"
    printf 'gpu.df.name|%s\n' "$n"
    r gpu.df.cur "$d/cur_freq"
    r gpu.df.max "$d/max_freq"
    r gpu.df.load "$d/load"
done

# ---------- GPU：三星 Exynos / 联发科 / Mali kbase ----------
r gpu.exynos.clock /sys/kernel/gpu/gpu_clock
r gpu.exynos.clock_actual /sys/kernel/gpu/gpu_clock_actual
r gpu.mtk.opp_dump /proc/gpufreq/gpufreq_opp_dump
r gpu.kbase.util /sys/module/mali_kbase/parameters/mali_gpu_utilization
r gpu.ged.util /d/ged/hal/gpu_utilization

# ---------- GPU 型号（sysfs 兜底；主力是 Java 侧读 GL_RENDERER）----------
# Mali kbase 会给出 "Mali-G57 r0p1 1.1" 这类字符串，MTK 会给 "Mali-G57 MC2"
r gpu.mali.gpuinfo /sys/class/misc/mali0/device/gpuinfo
r gpu.mtk.model /sys/kernel/gpu/gpu_model

# ---------- SoC 型号兜底（高通 soc0 / 联发科）----------
r soc.machine /sys/devices/soc0/machine
r soc.family /sys/devices/soc0/family
r soc.platform /sys/devices/system/soc/soc0/machine

# ---------- VRChat 帧率（需要 dumpsys，仅 Shizuku/Root 通道可用）----------
# SurfaceFlinger --latency 的第 2~130 行是每帧的 present 时间戳（纳秒），
# 取第二列（实际显示时间）交给 Java 侧算平均 FPS。
for vpkg in com.vrchat.mobile.playstore com.vrchat.oculus.quest; do
    vlayer=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -i "$vpkg" | head -n 1)
    [ -n "$vlayer" ] || continue
    vframes=$(dumpsys SurfaceFlinger --latency "$vlayer" 2>/dev/null | sed -n '2,130p' | awk '$2 > 0 { printf "%s;", $2 }')
    if [ -n "$vframes" ]; then printf 'vrchat.frames|%s\n' "$vframes"; fi
    break
done

# ---------- root 环境探测 ----------
printf 'root.uid|%s\n' "$(id -u 2>/dev/null | tr -dc '0-9')"
for p in /data/adb/ksu /data/adb/ksud /data/adb/ap /data/adb/apd /data/adb/magisk /data/adb/magisk.db /sbin/.magisk; do
    [ -e "$p" ] && printf 'root.path|%s\n' "$p"
done

echo "@END"
