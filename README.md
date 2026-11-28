**English** | [简体中文](README.zh.md) | [日本語](README.ja.md) | [Русский](README.ru.md) | [한국어](README.ko.md)

# VRCDI

> Full name: **VRChat Device Inspection** — pipe your Android device's live status into the VRChat chatbox.

VRCDI is a small pure-Java Android app: it reads CPU / GPU / RAM / battery and more through **Shizuku** (or Root, or with no privilege at all), assembles the fields you tick into a multi-line text, and sends it to VRChat over **UDP OSC** (`/chatbox/input`) so it shows up in the Chatbox above your avatar's head.

- Package: `com.vrcdevicecheck.osc`
- Version: `5.3.0 Beta4 (Build 451)`
- Signing: from **4.9.0 Release** on, Beta and release builds share the release key. Builds from 4.4.4 Beta and earlier are debug-signed, so upgrading them requires an uninstall first (app data will be wiped).
- Minimum: Android 10 (API 29)
- Language: Java 8; the only third-party dependency is Shizuku
- Build: Android Studio 4.2 / AGP 4.2.2 / Gradle 6.7.1 / compileSdk 30

> **Official site: https://wanliustudio.github.io/VRCDI/**
>
> Please download only from the official site, [GitHub Releases](https://github.com/WanliuStudio/VRCDI/releases),
> or the [Gitee releases](https://gitee.com/WanliuStudio/VRCDI/releases).
> Third-party "download sites" / app-directory mirrors often ship modified or outdated APKs, and their
> descriptions are frequently wrong (some call it a VR headset/controller checker). Do not install
> builds from unknown sources.

---

## Features

**Data collection**

- 21 selectable fields: device brand, device model, CPU name / frequency / temperature / usage, GPU name / frequency / temperature / usage, RAM used / total, battery and charging state, weather / temperature / humidity / pressure / wind / UV index / sunset, foreground app, frame rate.
- Output is **segmented into lines**, one category per line, for example:
  ```
  OPPO PCAT00
  CPU: Qualcomm SM8550 2.21GHz 46.1℃ 10%
  GPU: Adreno 740 N/A 41.3℃ N/A
  RAM: 3.9/5.5GB 71%
  Battery: 45% Charging
  App: VRCDI (this app)
  ```
- Every sysfs / procfs node is read in **one shell script** (`assets/snapshot.sh`), so each report cycle spawns a single child process.
- Anything that cannot be read shows "N/A" — numbers are never made up.

**Three privilege channels**

| Channel | Description |
| --- | --- |
| Shizuku | Runs as shell (uid 2000); Shizuku must be installed and running |
| Root | Runs through `su` directly, no root library bundled; detects KernelSU / APatch / Magisk families and guides you through granting |
| No privilege | Only reads nodes the system exposes to normal apps; CPU temperature / frequency will mostly be N/A |

**OSC sending**

- The target can be **this device** (`127.0.0.1`, when VRChat and VRCDI run on the same device) or a **custom IP** (phone → VRChat on a PC).
- Both `/chatbox/input` options are supported: "send immediately" and "play notification sound".
- A chatbox message is capped at **144 characters**. When the text is too long, fields are dropped from the end by priority, and the preview honestly reports "omitted: xxx".

**Report interval**

- Settings (gear icon, top right) → **Report interval**: 2 / 5 / 10 / 30 / 60 seconds, or a custom **2–3600 seconds**.
- "Send once immediately when reporting starts" is on by default: tapping Start collects and sends one report right away, then loops at the interval.
- Saving while the service is running **re-schedules immediately** instead of waiting for the old cycle; the notification's "every Xs" updates too.
- Shorter intervals refresh faster but drain more battery. VRChat currently allows about **5 messages per 5 seconds** (older limits were stricter) — if the chatbox stops refreshing, raise the interval.

**Auto-hide unavailable**

- There is a "Hide unavailable fields automatically" switch at the bottom of the fields card, **on by default**.
- Any field that reads "N/A" is left out of the text and does not consume the 144-character budget; the UI reports "hid N unavailable fields".
- Turning it off restores the old behaviour: unreadable values show as "N/A".
- Hidden fields and fields dropped for exceeding 144 characters are counted separately, so over-length warnings are never wrong.

**Weather**

- Settings (gear icon) → **Weather city**: inside China you pick province → city, with 34 provinces and 346 cities built in.
- **Cities outside China are supported**:
  - The first list item, "Search city", searches any city worldwide by name (Chinese or English, e.g. `Tokyo`);
  - The last item, "Browse by country", lets you browse **country → city** with 240 countries / 2100+ cities built in, no network needed;
  - Both paths store latitude and longitude directly, and all three data sources query by coordinates instead of resolving city names.
- Search results are sorted by population; Chinese queries also retry with traditional characters (东京 → 東京) so you don't only match small domestic places with the same name.
- New fields: **weather / temperature / humidity / pressure / wind / UV index / sunset**; they are off by default, and if you enable them without a city, the app prompts you to pick one.
- Weather spans two lines: weather / temperature / humidity on the first, pressure / wind / UV / sunset on the second. When over length, the second line is dropped first so the basics survive.
- Wind is shown in 8 compass directions (e.g. "SW wind 3.2m/s"), pressure in hPa, UV as a 0–11+ index, sunset as local time HH:mm.
- Primary source is **Open-Meteo** (free, no key); when `api.open-meteo.com` is unreachable it falls back to **wttr.in** (also free, no key) so weather keeps working on restricted networks.
- **30-minute cache**: a successful fetch writes a timestamp to SharedPreferences, and the same city is never requested again within 30 minutes — surviving process death and reboots. After a failure it waits 10 minutes before retrying, so request volume cannot explode.
- The manual "Fetch now" is bound by the same 30 minutes: tapping again for the same city tells you how many minutes are left; **switching cities clears the cache**, so you can fetch immediately after switching.
- No location permission is needed — only the city you pick is queried, and weather data stays on the device.

**Device table (model → product name)**

- Import a device table JSON per brand; it is copied into the app's private directory `files/device_tables/<brand>.json`, no rebuild needed.
- "Model name type" switches between the **real device model** (e.g. `HUAWEI HED-AL00`) and the **marketing product name** (e.g. `HUAWEI Pura 80`).

**Themes**

- **Built-in palettes**: Settings → Theme, pick one directly — Crimson / Mist blue / Sakura / Pine / Amber / Violet. Only background, card, body text and secondary text change, effective immediately.
- Built-in palettes are all light. A dark look needs matching button and input artwork, which is what the import path below is for.
- Import a `.zip` / `.tar.gz` theme pack; it is copied to `files/themes/<theme name>/`.
- A theme pack can carry a background image, button images (including pressed state) and a colour JSON — for complex looks that need artwork.
- The app restarts after switching; long-press an imported theme in the settings page to delete it (built-in palettes cannot be deleted).

**App table (package → app name)**

- Common apps are built in (QQ, WeChat, VRChat, …), and you can import your own JSON to override them.
- "Foreground app" looks up the app table first, then the system app label, and finally falls back to the package name.

**In-app update**

- Settings → Software update, choose **Gitee** or **GitHub** as the update source.
- The app reads the release API, compares the newest tag with the current version, downloads the asset and hands it to the system installer.
- Downloaded APKs stay in `cache/update/`; if an asset is named `.apk.zip` it is treated as `.apk` automatically.

**Interface language**

- Settings → **Language / 语言**: 简体中文 (default), English, 日本語, Русский, 한국어. The app restarts after switching.
- **The text sent to the VRChat chatbox follows the interface language** too — Chinese, English, Japanese, Russian or Korean; the 144-character cap is unchanged.
- "Send ASCII only" overrides the language: when enabled it forces English ASCII spelling (`℃` → `C`) so VRChat never shows question marks.
- Chinese is the default and lives in `values/`; the others are in `values-en` / `values-ja` / `values-ru` / `values-ko`, with one-to-one keys across all five.
- In Japanese / Russian / Korean UI the place names and weather text use English data (the built-in city database only has Chinese and English names).

**First-run onboarding**

- On a fresh install the app first asks you to pick a language (each language listed in its own script), then switches immediately.
- Next comes an illustrated "turn on OSC in VRChat" tutorial: 5 real screenshots with one caption each, one set per language (Chinese / English / Japanese / Russian / Korean).
- Users upgrading from an older version are not interrupted (existing configuration counts as already onboarded).
- You can re-read it any time in Settings → **OSC setup guide**.

**Other**

- Reporting runs in a foreground service; the notification lets you stop it in place. The log keeps the last 50 entries in memory only.
- "Foreground app" detection (requires the system "Usage access" permission).

---

## Install and use

1. Install the APK (from the Releases page, or build it yourself).
2. Open the app; on first launch it asks for a language and shows the "how to turn on OSC in VRChat" tutorial (re-openable via Settings → **OSC setup guide**).
3. Tap the **gear icon** in the top right to open Settings:
   - **Report interval**: pick 2–3600 seconds and whether to send once immediately on start.
   - **Weather city**: choose the location by province → city.
   - **Privilege method**: Shizuku / Root / no authorization.
   - **Device model tables**: import per-brand tables (optional).
   - **App name tables**: import package → app name mappings (optional).
   - **Theme**: import and switch themes (optional).
   - **Software update**: choose a source and check/install updates.
   - **About VRCDI**: version, authors and device info (including CPU / GPU).
4. In **Connection**, choose the target:
   - VRChat and VRCDI on the same device → **This device**;
   - Phone sending to VRChat on a PC → **Custom...** and enter the PC's LAN IP.
5. Tick the fields you want under **Reported fields** (the model row can switch between real model and product name).
6. Tap **Start reporting**. A foreground-service notification appears, from which you can stop it.

### On the VRChat side (required when sending to a PC)

1. VRChat Action Menu → Options → OSC → **Enabled**.
2. VRChat only listens on `127.0.0.1` by default; to send from another device you must add a launch option (Steam → Properties → Launch Options):

   ```
   --osc=9000:<PC LAN IP>:9001
   ```

   The main screen assembles this line for you — just copy it.
3. Allow UDP 9000 through the PC firewall, and make sure no face-tracking / body-tracking software is already using that port.

> Note: OSC is one-way and UDP has no delivery confirmation. "Sent" in the log only means the datagram reached the OS network stack. Whether VRChat received it is best checked in the OSC Debug panel of the Action Menu.

---

## Device table format

`Device model tables → Create a brand table`: enter a brand name, pick a JSON file, and the file is copied verbatim to `files/device_tables/<brand>.json`.

```json
{
  "devices": [
    { "model": "HED-AL00", "match": "exact",  "series": "Pura 80",     "verified": true },
    { "model": "LMR-AL",  "match": "prefix", "series": "Pura 80 Pro", "verified": false }
  ]
}
```

| Field | Required | Description |
| --- | --- | --- |
| `model` | yes | Model code matching `ro.product.model` / `Build.MODEL`, compared case-insensitively |
| `series` | yes | Product name shown when "Device product name" is selected |
| `match` | no | `exact` (default, full equality) or `prefix` (model starts with it — handy for variants) |
| `verified` | no | Annotation only, never displayed |

- The top level may also be a plain array; the file is read as UTF-8 (BOM tolerated).
- Load order: `files/device_tables/*.json` → legacy `files/device_models.json` → built-in `assets/device_models.json`; first match wins.
- The product name is taken from system properties (`ro.product.marketname` etc.) first, and only then from the device table.

---

## Theme pack format

`.zip` and `.tar.gz` / `.tgz` are supported; after extraction a `theme.json` must exist:

```
mytheme.zip
├── theme.json                 # required
└── images/
    ├── background.png         # optional, window background
    ├── button.png             # optional, normal button
    └── button_pressed.png     # optional, pressed button
```

```json
{
  "name": "Night flight",
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

- Everything except `name` is optional; colours accept `#RRGGBB` / `#AARRGGBB`; image paths are relative to the theme directory.
- The theme name becomes the directory name; importing the same name again overwrites it.
- The app restarts after switching; long-press a theme in the settings page to delete it.

---

## Build from source

```bash
./gradlew assembleDebug      # outputs app/build/outputs/apk/debug/app-debug.apk
./gradlew test               # runs JVM unit tests (OSC encoding, parsing, text rendering, …)
```

- Requires JDK 11 and Android SDK 30 (build-tools 30.0.3).
- The project deliberately stays "pure Java, no AndroidX widgets": every screen is built on `android.app.Activity` and native views, so it still builds in old environments (e.g. Android Studio 4.2).
- The only third-party dependency is `dev.rikka.shizuku:api` / `provider` (12.1.0); it transitively needs `androidx.annotation`, hence `android.useAndroidX=true` in `gradle.properties` (AppCompat is still not pulled in — all screens remain native Activities).

### App icon

The repository does not contain the source icon. Once you have it, run this once:

```powershell
powershell -ExecutionPolicy Bypass -File tools\make_icons.ps1 -Source "<path to image>"
```

The script generates `ic_launcher.png` for every density plus the adaptive-icon foreground, and re-points the icon XML under `mipmap-anydpi-v26` at the new foreground.

---

## Project layout

```
VRCDI/
├─ build.gradle / settings.gradle / gradle.properties
├─ gradle/wrapper/                  Gradle 6.7.1
├─ tools/make_icons.ps1             icon generator
├─ zte_device_table.json            sample ZTE device table (importable)
└─ app/
   ├─ build.gradle
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml
      │  ├─ assets/snapshot.sh          one-shot collection script
      │  ├─ assets/device_models.json   built-in model table
      │  └─ java/com/vrcdevicecheck/osc/
      │     ├─ MainActivity.java
      │     ├─ SettingsActivity.java
      │     ├─ DeviceTableSettingsActivity.java
      │     ├─ ThemeSettingsActivity.java
      │     ├─ data/      settings, field enum, privilege modes
      │     ├─ device/    collection, parsing, mapping, device tables, foreground app
      │     ├─ osc/       OSC encoding / sending / text assembly
      │     ├─ priv/      Shizuku / Root / unprivileged channels
      │     ├─ service/   foreground reporting service
      │     └─ theme/     theme installation and application
      └─ test/           JVM unit tests
```

---

## Known limitations

- **GPU data is the least reliable**: Android has no public API for GPU frequency / temperature / usage; everything relies on vendor-specific sysfs nodes. On Unisoc and some MediaTek devices even root cannot read them and the UI shows "N/A". The GPU name prefers GL `GL_RENDERER` (no permission needed) and only falls back to sysfs.
- **Chatbox rate limit**: the community-measured limit is about "5 messages per 5 seconds"; exceeding it triggers a cooldown. The default 10-second interval is deliberately conservative.
- **Non-ASCII display**: transport is always UTF-8, but VRChat versions differ in non-ASCII support; enable "Send ASCII only" to guarantee something shows up.
- **Quest standalone**: it installs and runs, but Quest has no root and Shizuku is not officially supported, so temperature / frequency are mostly unavailable; whether VRChat on Quest accepts OSC input depends on the system version — test it yourself.
- **Foreground app detection** needs the system "Usage access" permission; without it the field reads "N/A".

---

## Authors

- WanliuStudio
- 海盗船长暗金 (SkipTopic)
- Qwen 3.8 Pro
- DeepSeek-V4.1-Flash

## License

[MIT](LICENSE)

## Disclaimer

This app only reads public device information; it never collects or uploads any account credentials, and all network activity is limited to UDP datagrams sent to the IP you configure. Risks from using Shizuku / Root are borne by the user. VRChat is a trademark of VRChat Inc.; this project is not affiliated with it.
