[English](README.md) | **简体中文** | [日本語](README.ja.md) | [Русский](README.ru.md) | [한국어](README.ko.md)

# VRCDI

> 把安卓设备的实时状态送进 VRChat 的 Chatbox。

VRCDI 是一个纯 Java 的 Android 小工具：通过 **Shizuku**（或 Root，或完全无特权）读取设备的 CPU / GPU / 内存 / 电量等信息，按你勾选的字段拼成多行文本，用 **UDP OSC** 的 `/chatbox/input` 发送给 VRChat，显示在聊天框（Chatbox）里。

- 包名：`com.vrcdevicecheck.osc`
- 当前版本：`5.1.0 Beta2 (Build 431)`
- 签名：从 **4.9.0 Release** 起，Beta 与正式版统一使用发布私钥签名；4.4.4 Beta 及更早的安装包仍是 debug 签名，升级时需要先卸载（应用数据会清空）。
- 最低系统：Android 10（API 29）
- 语言：Java 8；唯一的第三方依赖是 Shizuku
- 构建：Android Studio 4.2 / AGP 4.2.2 / Gradle 6.7.1 / compileSdk 30

> **唯一官网：https://wanliustudio.github.io/VRCDI/**
>
> 请只从以下官方渠道下载：本官网、[GitHub Releases](https://github.com/WanliuStudio/VRCDI/releases)、
> [Gitee 发行版](https://gitee.com/WanliuStudio/VRCDI/releases)。
> 第三方下载站（如各类“软件下载站”“应用大全”收录页）提供的安装包可能被修改、版本落后，
> 其页面介绍也常与软件实际功能不符（例如把它描述成 VR 头显/手柄检测工具），请勿从来源不明的下载站安装。

---

## 功能

**数据采集**

- 21 个可勾选字段：设备品牌、设备型号、CPU 名称 / 频率 / 温度 / 使用率、GPU 名称 / 频率 / 温度 / 使用率、RAM 占用 / 总量、电量与充电状态、天气 / 气温 / 湿度 / 气压 / 风向风速 / 紫外线 / 日落、前台应用、帧率。
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

**上报节奏**

- 设置（右上角齿轮）→ **上报时间设置**：可选 2 / 5 / 10 / 30 / 60 秒，或自定义 **2–3600 秒**。
- 「启动上报时立即发送一次」默认开启：点「启动上报」会马上采集并发送一条，再按间隔循环。
- 保存后若服务正在上报，**立即按新间隔重排**，不必等旧周期走完；通知栏的「每 Xs」同步更新。
- 间隔越短刷新越快，但更耗电。VRChat 目前限制 **5 秒内最多 5 条**（旧版限频更严）；遇到 Chatbox 不刷新就适当调大间隔。

**自动屏蔽不可用**

- 上报字段卡片底部有「自动屏蔽不可用字段」开关，**默认开启**。
- 任何字段读到「不可用」时，不写进上报文本，也不占 144 字符；界面会提示「已自动隐藏 N 个不可用字段」。
- 关掉开关后恢复旧行为：读不到的值显示「不可用」。
- 被隐藏的字段与「超过 144 字符被省略」的字段分开统计，不会误报为超长。

**天气**

- 设置（右上角齿轮）→ **天气城市设置**：国内走省份 → 城市两级选择，内置 34 个省级行政区、346 个城市。
- **支持国外城市**：
  - 列表第一项「搜索城市」按名字搜全球任意城市（中英文都行，例如 `东京` / `Tokyo`）；
  - 列表最后一项「选择国外」按**国家 → 城市**浏览，内置 240 个国家 / 2100+ 个城市，不联网也能翻；
  - 两条路选中后都直接记录经纬度，三个数据源都按坐标查询，不依赖城市名解析。
- 搜索结果按人口降序；汉字查询会自动补一次繁体写法（东京 → 東京、纽约 → 紐約），避免只匹配到国内同名小地方。
- 上报字段新增 **天气 / 气温 / 湿度 / 气压 / 风向风速 / 紫外线 / 日落**；默认不勾选，勾选后如果还没选城市，会提示去设置里选择。
- 天气分两行：第一行天气 / 气温 / 湿度；第二行气压 / 风向风速 / 紫外线 / 日落。超长时先丢第二行，保留基础天气。
- 风向按 8 方位显示（如「西南风 3.2m/s」），气压用 hPa，紫外线为 0–11+ 指数，日落为当地时间 HH:mm。
- 数据源优先 **Open-Meteo**（免费、无需 key）；`api.open-meteo.com` 不可达时自动切换 **wttr.in**（同样免费、无需 key），保证国内网络也能出天气。
- **30 分钟缓存**：成功获取后把时间戳写进 SharedPreferences，同一城市 30 分钟内绝不重复请求；杀进程、重启应用也不会丢。失败后 10 分钟内不重试，避免请求量爆炸。
- 手动「立即获取」同样受这 30 分钟限制：同一个城市再点会提示还剩多少分钟；**切换城市会清空缓存**，切完立刻可以获取。
- 不需要定位权限，只按手动选择的城市查询；天气数据只缓存在本机。

**设备表（型号 → 产品名称）**

- 按品牌导入设备表 JSON，复制到应用私有目录 `files/device_tables/<品牌>.json`，不需要重新编译。
- 「上报型号类型」可切换 **设备真实型号**（如 `HUAWEI HED-AL00`）或 **设备产品名称**（如 `HUAWEI Pura 80`）。

**主题**

- **内置配色**：设置 → 主题里直接选，胭脂红 / 雾蓝 / 樱粉 / 松绿 / 暖橙 / 黛紫，只替换背景、卡片、正文、次要文字四个颜色，选完立即生效。
- 内置配色都是浅色系。深色外观需要配套的按钮、输入框素材，走下面的导入主题。
- 导入 `.zip` / `.tar.gz` 主题包，主题自动复制到 `files/themes/<主题名>/`。
- 主题包内可放背景图、按钮图（含按下态）和配色 JSON；适合需要底图、按钮素材的复杂外观。
- 切换主题后自动重启应用生效；设置页里长按可删除导入的主题（内置配色不可删除）。

**应用表（包名 → 应用名）**

- 内置常见应用（QQ、微信、VRChat 等），也可以导入自己的 JSON 覆盖。
- 「前台应用」显示时优先查应用表，查不到再用系统应用名，最后才显示包名。

**应用内更新**

- 设置 → 软件更新设置，可选 **Gitee** 或 **GitHub** 作为更新源。
- 读取发行版接口拿到最新 tag，与当前版本号比较；有新版则下载附件并调起系统安装器。
- 下载的 APK 保存在 `cache/update/`，若附件名是 `.apk.zip` 会自动按 `.apk` 处理。

**界面语言**

- 设置 → **语言 / Language**：可选 **简体中文**（默认）、**English**、**日本語**、**Русский**、**한국어**，切换后应用自动重启。
- **发往 VRChat Chatbox 的文本也跟着界面语言走**：中文界面中文、English 界面英文、日本語界面日文、Русский 界面俄文、한국어 界面韩文；144 字符上限不变。
- 「仅发送 ASCII」优先于语言：打开后强制英文 ASCII 写法（℃ → C），避免 VRChat 显示成问号。
- 中文是默认语言，走 `values/`；其余在 `values-en` / `values-ja` / `values-ru` / `values-ko`，五套文案键一一对应。
- 日文、俄文、韩文界面下地名与天气文案使用英文数据（内置城市库只有中英文名）。

**首次启动引导**

- 新安装第一次打开时先选语言（五种语言各按自己的写法列出），选完立刻切成该语言。
- 接着是「在 VRChat 里打开 OSC」的图文教程：5 步实拍截图 + 一句说明，中文 / English / 日本語 / Русский / 한국어 各一套图。
- 老版本升级上来的用户不会被打断（配置里已有值就视为已引导）。
- 随时可以在 设置 → **OSC 设置教程** 里再看一遍。

**其他**

- 前台服务常驻上报，通知栏可一键停止；日志只在内存保留最近 50 条。
- 「当前前台应用」检测（需要系统「使用情况访问」权限）。

---

## 安装与使用

1. 安装 APK（Release 页面或自行构建）。
2. 打开应用，第一次会先让你选语言，再看一遍「怎么在 VRChat 里打开 OSC」的图文教程（设置 → **OSC 设置教程** 可重看）。
3. 右上角**齿轮**进入设置：
   - **上报时间设置**：选择上报间隔（2–3600 秒），以及启动时是否立即发送一次。
   - **天气城市设置**：按省份 → 城市选择天气查询位置。
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
- 海盗船长暗金（SkipTopic）
- Qwen 3.8 Pro
- DeepSeek-V4.1-Flash

## 许可证

[MIT](LICENSE)

## 免责声明

本应用只读取设备公开信息，不收集、不上传任何账号凭据；所有网络行为仅限于向你填写的 IP 发送 UDP 报文。使用 Shizuku / Root 带来的风险由使用者自行承担。VRChat 是 VRChat Inc. 的商标，本项目与其无关联。
