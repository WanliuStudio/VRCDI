# VRCDI

> 把安卓设备的实时状态送进 VRChat 的 Chatbox。

VRCDI 是一个纯 Java 的 Android 小工具：通过 **Shizuku**（或 Root，或完全无特权）读取设备的 CPU / GPU / 内存 / 电量等信息，按你勾选的字段拼成一行文本，用 **UDP OSC** 的 `/chatbox/input` 发送给 VRChat，显示在聊天框（Chatbox）里。

- 包名：`com.vrcdevicecheck.osc`
- 当前版本：`2.0.1 Omega (Build 101)`
- 最低系统：Android 10（API 29）
- 语言：Java 8；唯一的第三方依赖是 Shizuku
- 构建：Android Studio 4.2 / AGP 4.2.2 / Gradle 6.7.1 / compileSdk 30

---

## 功能

**数据采集**

- 13 个可勾选字段：设备品牌、设备型号、CPU 名称 / 频率 / 温度 / 使用率、GPU 名称 / 频率 / 温度 / 使用率、RAM 占用 / 总量、电量与充电状态、前台应用。
- 输出是**分段多行**的，每类信息一行，例如：
  ```
  OPPO PCAT00
  CPU: Qualcomm SM8550 2.21GHz 46.1℃ 10%
  GPU: Adreno 740 不可用 41.3℃ 不可用
  RAM: 3.9/5.5GB 71%
  电量: 45% 充电中
  App: VRCDI（本应用）
  ```
- 所有 sysfs / procfs 节点在**一个 shell 脚本**里一次读完（`assets/snapshot.sh`），每个采集周期只起一次子进程。
- 读不到的指标显示「不可用」，绝不编造数值。

**三种权限通道**

| 通道 | 说明 |
| --- | --- |
| Shizuku | 以 shell（uid 2000）身份执行，需要设备已安装并启动 Shizuku |
| Root | 直接 `su` 执行，不引入任何 root 库；能识别 KernelSU / APatch / Magisk 系并引导授权 |
| 无特权 | 只读系统对普通应用开放的节点，CPU 温度 / 频率大概率不可用 |

**OSC 发送**

- 目标可以是**本机**（`127.0.0.1`，VRChat 与 VRCDI 在同一台设备时选这个）或**自定义 IP**（手机 → PC 上的 VRChat）。
- 支持「立即发送」与「提示音」两个 `/chatbox/input` 参数。
- Chatbox 单条上限 **144 字符**，超长时按优先级从末尾丢弃字段，并在预览区如实提示「已自动省略：xxx」。

**设备表（型号 → 产品名称）**

- 按品牌导入设备表 JSON，复制到应用私有目录 `files/device_tables/<品牌>.json`，不需要重新编译。
- 「上报型号类型」可切换 **设备真实型号**（如 `HUAWEI HED-AL00`）或 **设备产品名称**（如 `HUAWEI Pura 80`）。

**主题**

- 导入 `.zip` / `.tar.gz` 主题包，主题自动复制到 `files/themes/<主题名>/`。
- 主题包内可放背景图、按钮图（含按下态）和配色 JSON；切换主题后自动重启应用生效。

**应用表（包名 → 应用名）**

- 内置常见应用（QQ、微信、VRChat 等），也可以导入自己的 JSON 覆盖。
- 「前台应用」显示时优先查应用表，查不到再用系统应用名，最后才显示包名。

**应用内更新**

- 设置 → 软件更新设置，可选 **Gitee** 或 **GitHub** 作为更新源。
- 读取发行版接口拿到最新 tag，与当前版本号比较；有新版则下载附件并调起系统安装器。
- 下载的 APK 保存在 `cache/update/`，若附件名是 `.apk.zip` 会自动按 `.apk` 处理。

**其他**

- 前台服务常驻上报，通知栏可一键停止；日志只在内存保留最近 50 条。
- 「当前前台应用」检测（需要系统「使用情况访问」权限）。

---

## 安装与使用

1. 安装 APK（Release 页面或自行构建）。
2. 打开应用，右上角**齿轮**进入设置：
   - **权限申请方式**：选择 Shizuku / Root / 免授权。
   - **设备表设置**：按品牌导入型号表（可选）。
   - **应用表设置**：导入包名 → 应用名映射（可选）。
   - **主题**：导入并切换主题（可选）。
   - **软件更新设置**：选择更新源并检查/安装更新。
   - **关于VRCDI**：查看版本、作者与设备信息（含 CPU / GPU）。
3. 在「连接设置」里选择目标：
   - VRChat 与 VRCDI 在同一台设备 → 选 **本机IP**；
   - 手机发送给 PC 上的 VRChat → 选 **自定义...** 并填写 PC 的局域网 IP。
4. 在「上报字段」勾选要展示的内容（设备型号右侧可切换真实型号 / 产品名称）。
5. 点「启动上报」。通知栏会出现前台服务通知，可就地停止。

### VRChat 端（发送到 PC 时必做）

1. VRChat 动作菜单 → Options → OSC → **Enabled**。
2. VRChat 默认只监听 `127.0.0.1`，跨设备发送需要给 VRChat 加启动参数（Steam → 属性 → 启动选项）：

   ```
   --osc=9000:<PC 的局域网 IP>:9001
   ```

   应用主界面会把这一行拼好显示，直接复制即可。
3. PC 防火墙放行 UDP 9000，并确认该端口没有被面捕 / 体感软件占用。

> 说明：OSC 发送是单向的，UDP 没有送达确认，日志里的「已发送」只代表报文交给了系统协议栈。是否真的收到，以 VRChat 动作菜单里的 OSC Debug 面板为准。

---

## 设备表格式

`设备表设置 → 按品牌创建设备表`：填写品牌名称，选择 JSON 文件，确认后文件会被原样复制到 `files/device_tables/<品牌>.json`。

```json
{
  "devices": [
    { "model": "HED-AL00", "match": "exact",  "series": "Pura 80",     "verified": true },
    { "model": "LMR-AL",  "match": "prefix", "series": "Pura 80 Pro", "verified": false }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `model` | 是 | 型号代码，对应 `ro.product.model` / `Build.MODEL`，比较时忽略大小写 |
| `series` | 是 | 产品名称（选「设备产品名称」时显示） |
| `match` | 否 | `exact`（默认，完全相等）或 `prefix`（型号以它开头，适合同机型变体） |
| `verified` | 否 | 仅作标注，不参与显示 |

- 顶层也可以直接写数组；文件用 UTF-8（带 BOM 也能读）。
- 加载顺序：`files/device_tables/*.json` → 旧版 `files/device_models.json` → 内置 `assets/device_models.json`，先匹配到的优先。
- 产品名称优先取系统属性（`ro.product.marketname` 等），取不到才查设备表。

---

## 主题包格式

支持 `.zip` 与 `.tar.gz` / `.tgz`，解压后必须能找到 `theme.json`：

```
mytheme.zip
├── theme.json                 # 必需
└── images/
    ├── background.png         # 可选，窗口背景
    ├── button.png             # 可选，按钮常态
    └── button_pressed.png     # 可选，按钮按下态
```

```json
{
  "name": "夜航",
  "colors": {
    "background": "#0F1216",
    "card": "#1A1F24",
    "text": "#E8EAED",
    "textSecondary": "#A0A8B0"
  },
  "images": {
    "background": "images/background.png",
    "button": "images/button.png",
    "buttonPressed": "images/button_pressed.png"
  }
}
```

- 除 `name` 外全部可选；颜色支持 `#RRGGBB` / `#AARRGGBB`；图片路径相对主题目录。
- 主题名会作为目录名，同名主题再次导入会覆盖。
- 切换主题后应用会自动重启；设置页里长按可删除主题。

---

## 从源码构建

```bash
./gradlew assembleDebug      # 生成 app/build/outputs/apk/debug/app-debug.apk
./gradlew test               # 运行 JVM 单元测试（OSC 编码、解析、文本渲染等）
```

- 需要 JDK 11 与 Android SDK 30（build-tools 30.0.3）。
- 工程刻意保持「纯 Java + 不引入 AndroidX 界面组件」，界面全部基于 `android.app.Activity` 与原生 View，方便在旧环境（如 Android Studio 4.2）里构建。
- 唯一第三方依赖是 `dev.rikka.shizuku:api` / `provider`（12.1.0），它传递依赖 `androidx.annotation`，因此 `gradle.properties` 里需要 `android.useAndroidX=true`（不会引入 AppCompat，界面仍是原生 Activity）。

### 应用图标

仓库不包含图标原图。拿到原图后执行一次：

```powershell
powershell -ExecutionPolicy Bypass -File tools\make_icons.ps1 -Source "图片路径"
```

脚本会生成各密度的 `ic_launcher.png` 与自适应图标前景，并把 `mipmap-anydpi-v26` 下的图标 XML 接到新前景。

---

## 目录结构

```
VRCDI/
├─ build.gradle / settings.gradle / gradle.properties
├─ gradle/wrapper/                  Gradle 6.7.1
├─ tools/make_icons.ps1             图标生成脚本
├─ zte_device_table.json            中兴设备表示例（可导入）
└─ app/
   ├─ build.gradle
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml
      │  ├─ assets/snapshot.sh          一次读完全部节点的采集脚本
      │  ├─ assets/device_models.json   内置型号表
      │  └─ java/com/vrcdevicecheck/osc/
      │     ├─ MainActivity.java
      │     ├─ SettingsActivity.java
      │     ├─ DeviceTableSettingsActivity.java
      │     ├─ ThemeSettingsActivity.java
      │     ├─ data/      设置、字段枚举、权限模式
      │     ├─ device/    采集、解析、折算、设备表、前台应用
      │     ├─ osc/       OSC 编码 / 发送 / 文本拼装
      │     ├─ priv/      Shizuku / Root / 无特权通道
      │     ├─ service/   前台上报服务
      │     └─ theme/     主题安装与应用
      └─ test/            JVM 单元测试
```

---

## 已知限制

- **GPU 数据最不可靠**：安卓没有公开 API 获取 GPU 频率 / 温度 / 占用，全靠厂商各自的 sysfs 节点；展锐、部分 MediaTek 机型上即使有 root 也读不到，界面会显示「不可用」。GPU 名称优先用 GL `GL_RENDERER`（无需权限），读不到才退回 sysfs。
- **Chatbox 限频**：社区记录约为「5 秒内 5 条」，超限触发冷却；默认 10 秒间隔是保守值。
- **中文显示**：传输层始终 UTF-8，但不同 VRChat 版本对非 ASCII 的支持有差异，可打开「仅发送 ASCII」保证显示。
- **Quest 一体机**：可以安装运行，但 Quest 上没有 root、Shizuku 非官方支持，温度 / 频率基本拿不到；且 VRChat Quest 版对 OSC 输入的支持依赖系统版本，需自行实测。
- **前台应用检测**需要系统的「使用情况访问」权限，未授权时该字段显示「不可用」。

---

## 作者

- WanliuStudio
- Qwen 3.8 Pro
- DeepSeek-V4.1-Flash

## 许可证

[MIT](LICENSE)

## 免责声明

本应用只读取设备公开信息，不收集、不上传任何账号凭据；所有网络行为仅限于向你填写的 IP 发送 UDP 报文。使用 Shizuku / Root 带来的风险由使用者自行承担。VRChat 是 VRChat Inc. 的商标，本项目与其无关联。
