package com.vrcdevicecheck.osc;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.vrcdevicecheck.osc.data.AppSettings;
import com.vrcdevicecheck.osc.data.FieldGroup;
import com.vrcdevicecheck.osc.data.ModelDisplayMode;
import com.vrcdevicecheck.osc.data.PrivilegeMode;
import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceInfoRepository;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.device.ForegroundApp;
import com.vrcdevicecheck.osc.device.VrSupport;
import com.vrcdevicecheck.osc.media.HeartRateManager;
import com.vrcdevicecheck.osc.media.MusicInfo;
import com.vrcdevicecheck.osc.media.NowPlayingRepository;
import com.vrcdevicecheck.osc.net.LanScanner;
import com.vrcdevicecheck.osc.osc.ReportBuilder;
import com.vrcdevicecheck.osc.priv.PrivilegeController;
import com.vrcdevicecheck.osc.priv.RootController;
import com.vrcdevicecheck.osc.priv.RootManager;
import com.vrcdevicecheck.osc.priv.RootShell;
import com.vrcdevicecheck.osc.priv.ShellResult;
import com.vrcdevicecheck.osc.priv.ShizukuShell;
import com.vrcdevicecheck.osc.service.ReporterService;
import com.vrcdevicecheck.osc.theme.ThemeManager;
import com.vrcdevicecheck.osc.util.AboutLinks;
import com.vrcdevicecheck.osc.util.LocaleHelper;
import com.vrcdevicecheck.osc.update.UpdateChecker;
import com.vrcdevicecheck.osc.weather.WeatherInfo;
import com.vrcdevicecheck.osc.weather.WeatherRepository;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * VRCDI 主界面。
 *
 * 使用 android.app.Activity 而非 AppCompatActivity：本工程不引入 AndroidX，
 * 这样在 Android Studio 4.2 + compileSdk 30 环境下依赖冲突面最小。
 */
public class MainActivity extends BaseActivity implements ReporterService.Listener {

    /** API 33 才有通知运行时权限；compileSdk 30 里没有这个常量，所以用字面量 */
    private static final String PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int REQUEST_CODE_NOTIFICATION = 33;

    /** 每个进程只做一次启动更新检查 */
    private static boolean autoUpdateChecked;

    private AppSettings settings;
    private DeviceInfoRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread background;

    /** 程序化回填界面时置 true，避免 Spinner 回调把默认值写回配置 */
    private boolean applyingSettings;

    /** 界面可见时每 2 秒刷新一次「前台应用」 */
    private boolean foregroundTicking;

    /** 本次界面生命周期内只提示一次「先选天气城市」 */
    private boolean weatherPromptShown;

    private final Runnable foregroundTick = new Runnable() {
        @Override
        public void run() {
            refreshForegroundApp();
            // 界面可见时顺带刷新音乐/心率预览
            Set<ReportField> enabled = settings.getEnabledFields();
            if (ReportBuilder.hasMusicFields(enabled)
                    || enabled.contains(ReportField.HEART_RATE)) {
                refreshPreview();
            }
            refreshHeartRateHint();
            // 界面已经不可见时不再续命，避免退到后台还在轮询
            if (foregroundTicking) main.postDelayed(this, 2000L);
        }
    };

    private Spinner spinnerIpMode;
    private Spinner spinnerModelMode;
    private EditText inputIp;
    private EditText inputPort;
    private EditText inputInterval;
    private CheckBox checkAscii;
    private CheckBox checkSendImmediately;
    private CheckBox checkNotificationSound;
    private CheckBox checkHideUnavailable;
    private TextView textLocalIp;
    private TextView textLaunchArg;
    private TextView textPrivilegeMode;
    private TextView textPrivilegeStatus;
    private TextView textRootHint;
    private LinearLayout containerFields;
    private TextView textRunningState;
    private Button btnToggle;
    private TextView textPreview;
    private TextView textPreviewCount;
    private TextView textPreviewHint;
    private TextView textWarning;
    private TextView textLog;
    private TextView textIpModeHint;
    private TextView textForegroundApp;
    private TextView textWeatherHint;
    private TextView textMusicHint;
    private TextView textHeartRateHint;

    private DeviceSnapshot localSnapshot;
    private final List<CheckBox> fieldBoxes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 新装第一次打开：先选语言，再看一遍怎么在 VRChat 里打开 OSC（老用户升级不会被打扰）
        if (new AppSettings(this).needsOnboarding()) {
            startActivity(WelcomeActivity.intentForOnboarding(this));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        settings = new AppSettings(this);
        repository = new DeviceInfoRepository(this);

        bindViews();
        setupIpModeSpinner();
        buildFieldGroups();
        applySettingsToViews();
        attachWatchers();
        attachClicks();

        refreshPrivilegeTexts();
        refreshFromService();
        loadLocalIp();
        requestNotificationPermissionIfNeeded();
        ensureBatteryWhitelistHint();

        // 等界面挂载完再弹，避免在 onCreate 里 show 对话框的窗口 token 问题
        textPreview.post(new Runnable() {
            @Override
            public void run() {
                maybePromptWeatherCity();
            }
        });

        // 放在所有控件都创建完之后，动态生成的勾选框也能一起套上主题
        ThemeManager.apply(this);

        // 第一次安装时问一次要不要自动检查更新，之后每次启动按开关来
        if (!settings.hasAskedAutoUpdate()) {
            maybeAskAutoUpdate();
        } else {
            checkUpdateOnLaunch();
        }
    }

    /** 首次安装询问自动检查更新（只问一次） */
    private void maybeAskAutoUpdate() {
        settings.markAutoUpdateAsked();
        new AlertDialog.Builder(this)
                .setTitle("自动检查更新")
                .setMessage("是否开启自动检查更新？\n开启后每次打开应用都会静默检查新版本，"
                        + "发现更新时才会弹窗提示。")
                .setPositiveButton("开启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        settings.setAutoUpdateCheck(true);
                        checkUpdateOnLaunch();
                    }
                })
                .setNegativeButton("暂不", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        settings.setAutoUpdateCheck(false);
                    }
                })
                .show();
    }

    /**
     * 启动时的静默更新检查：
     * 只有用户在「软件更新设置」里打开了开关才会执行；
     * 检查失败、没有新版本都完全不打扰用户；发现新版本才弹窗。
     * 每次进程启动最多检查一次（旋转屏幕重建 Activity 不会重复请求）。
     */
    private void checkUpdateOnLaunch() {
        if (autoUpdateChecked) return;
        if (!settings.isAutoUpdateCheck()) return;
        autoUpdateChecked = true;

        final String source = settings.getUpdateSource();
        runInBackground(new Runnable() {
            @Override
            public void run() {
                UpdateChecker.Release release;
                try {
                    release = UpdateChecker.fetchLatest(MainActivity.this, source,
                            settings.isBetaChannel());
                } catch (Throwable t) {
                    return;
                }
                if (release == null) return;
                if (!UpdateChecker.isNewer(release.tag, currentVersionName())) return;

                final UpdateChecker.Release found = release;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        showUpdateAvailableDialog(found);
                    }
                });
            }
        });
    }

    private void showUpdateAvailableDialog(final UpdateChecker.Release release) {
        String body = release.body == null ? "" : release.body.trim();
        if (body.length() > 800) body = body.substring(0, 800) + "…";
        String message = body.isEmpty()
                ? getString(R.string.update_dialog_body, release.tag)
                : body;
        if (!BuildConfig.RELEASE_SIGNED) {
            message = message + "\n\n———\n"
                    + "当前安装包使用测试签名，后续 Beta 与正式版统一使用发布私钥签名。"
                    + "安装新版本前需要先卸载 VRCDI，否则会提示安装失败；卸载会清除已导入的表与主题。";
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_dialog_title, release.tag))
                .setMessage(message)
                .setPositiveButton(R.string.update_go, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(MainActivity.this, UpdateSettingsActivity.class);
                        intent.putExtra(UpdateSettingsActivity.EXTRA_AUTO_UPDATE, true);
                        startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "4.9.0 Release";
        }
    }

    private void bindViews() {
        spinnerIpMode = findViewById(R.id.spinner_ip_mode);
        inputIp = findViewById(R.id.input_ip);
        inputPort = findViewById(R.id.input_port);
        inputInterval = findViewById(R.id.input_interval);
        checkAscii = findViewById(R.id.check_ascii);
        checkSendImmediately = findViewById(R.id.check_send_immediately);
        checkNotificationSound = findViewById(R.id.check_notification_sound);
        textLocalIp = findViewById(R.id.text_local_ip);
        textLaunchArg = findViewById(R.id.text_launch_arg);
        textPrivilegeMode = findViewById(R.id.text_privilege_mode);
        textPrivilegeStatus = findViewById(R.id.text_privilege_status);
        textRootHint = findViewById(R.id.text_root_hint);
        containerFields = findViewById(R.id.container_fields);
        textRunningState = findViewById(R.id.text_running_state);
        btnToggle = findViewById(R.id.btn_toggle);
        textPreview = findViewById(R.id.text_preview);
        textPreviewCount = findViewById(R.id.text_preview_count);
        textPreviewHint = findViewById(R.id.text_preview_hint);
        textWarning = findViewById(R.id.text_warning);
        textLog = findViewById(R.id.text_log);
        textIpModeHint = findViewById(R.id.text_ip_mode_hint);
        textForegroundApp = findViewById(R.id.text_foreground_app);
    }

    /** 目标设备下拉菜单：本机IP（127.0.0.1）/ 自定义... */
    private void setupIpModeSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.ip_mode_local), getString(R.string.ip_mode_custom)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerIpMode.setAdapter(adapter);
        spinnerIpMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (applyingSettings) return;
                settings.setLocalTarget(position == 0);
                updateIpModeUi();
                refreshLaunchArg();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 下拉菜单没有"取消选中"，无需处理
            }
        });
    }

    /** 按"本机 / 自定义"切换输入框与两侧提示 */
    private void updateIpModeUi() {
        boolean local = settings.isLocalTarget();
        inputIp.setVisibility(local ? View.GONE : View.VISIBLE);
        // 本机模式下，局域网 IP 与 PC 启动参数都没有意义
        textLocalIp.setVisibility(local ? View.GONE : View.VISIBLE);
        textLaunchArg.setVisibility(local ? View.GONE : View.VISIBLE);
        textIpModeHint.setText(local
                ? getString(R.string.ip_mode_hint_local)
                : getString(R.string.ip_mode_hint_custom));
    }

    /**
     * 按分组平铺：组标题紧靠左边，子选项整体缩进，组与组之间留空隙。
     * 结构像 Word 大纲（标题 + Tab 缩进的子段落），所有选项都直接可见。
     */
    private void buildFieldGroups() {
        containerFields.removeAllViews();
        fieldBoxes.clear();
        float density = getResources().getDisplayMetrics().density;
        int indent = Math.round(density * 20);
        boolean firstGroup = true;

        for (FieldGroup group : FieldGroup.values()) {
            TextView heading = new TextView(this);
            heading.setText(group.label(this));
            heading.setTextSize(15f);
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            heading.setTextColor(getColor(R.color.text_primary));
            int top = firstGroup ? Math.round(density * 2) : Math.round(density * 18);
            heading.setPadding(0, top, 0, Math.round(density * 4));
            containerFields.addView(heading);
            firstGroup = false;

            for (final ReportField field : group.fields()) {
                CheckBox box = new CheckBox(this);
                box.setText(field.label(this));
                box.setTag(field);
                box.setTextColor(getColor(R.color.text_primary));
                box.setTextSize(14f);
                int vertical = Math.round(density * 2);
                box.setPadding(indent, vertical, 0, vertical);
                // VR 一体机用手柄射线点按，行高太小容易点空
                if (VrSupport.isVrDevice()) {
                    box.setMinHeight(Math.round(density * 48));
                    int vrVertical = Math.round(density * 6);
                    box.setPadding(indent, vrVertical, 0, vrVertical);
                }
                box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        // 代码回填（applySettingsToViews）时不写回配置，只响应真实点击
                        if (!buttonView.isPressed()) return;
                        Set<ReportField> current = EnumSet.noneOf(ReportField.class);
                        current.addAll(settings.getEnabledFields());
                        if (isChecked) current.add(field);
                        else current.remove(field);
                        settings.setEnabledFields(current);
                        refreshPreview();
                        refreshMusicHint();
                        ReporterService.wake();
                        if (isChecked && field.isWeather() && settings.getWeatherCity().isEmpty()) {
                            promptWeatherCity();
                        }
                        if (isChecked && field.isMusic()
                                && !NowPlayingRepository.hasAccess(MainActivity.this)) {
                            promptMusicAccess();
                        }
                        if (isChecked && field == ReportField.HEART_RATE
                                && !HeartRateManager.hasSelectedDevice(MainActivity.this)) {
                            promptHeartRate();
                        }
                        refreshHeartRateHint();
                    }
                });
                fieldBoxes.add(box);
                containerFields.addView(box);
                if (field == ReportField.MODEL) {
                    addModelModeRow(density);
                }
            }
        }

        textWeatherHint = new TextView(this);
        textWeatherHint.setTextSize(12f);
        textWeatherHint.setTextColor(getColor(R.color.text_secondary));
        int pad = Math.round(density * 4);
        textWeatherHint.setPadding(0, pad, 0, pad);
        containerFields.addView(textWeatherHint);

        checkHideUnavailable = new CheckBox(this);
        checkHideUnavailable.setText(R.string.check_hide_unavailable);
        checkHideUnavailable.setTextSize(13f);
        checkHideUnavailable.setTextColor(getColor(R.color.text_secondary));
        checkHideUnavailable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!buttonView.isPressed()) return;
                settings.setHideUnavailable(isChecked);
                refreshPreview();
            }
        });
        containerFields.addView(checkHideUnavailable);

        textMusicHint = new TextView(this);
        textMusicHint.setTextSize(12f);
        int musicPad = Math.round(density * 4);
        textMusicHint.setPadding(0, musicPad, 0, musicPad);
        textMusicHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNotificationAccess();
            }
        });
        containerFields.addView(textMusicHint);

        textHeartRateHint = new TextView(this);
        textHeartRateHint.setTextSize(12f);
        int heartPad = Math.round(density * 4);
        textHeartRateHint.setPadding(0, heartPad, 0, heartPad);
        textHeartRateHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HeartRateActivity.class));
            }
        });
        containerFields.addView(textHeartRateHint);

        refreshMusicHint();
        refreshHeartRateHint();
    }

    private void refreshHeartRateHint() {
        if (textHeartRateHint == null || settings == null) return;
        boolean enabled = settings.getEnabledFields().contains(ReportField.HEART_RATE);
        textHeartRateHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;

        String name = HeartRateManager.getSelectedName(this);
        if (name.isEmpty()) {
            textHeartRateHint.setText(R.string.hint_heart_rate_no_device);
            textHeartRateHint.setTextColor(getColor(R.color.warn));
            return;
        }
        int bpm = HeartRateManager.getFreshBpm();
        if (bpm > 0) {
            textHeartRateHint.setText(
                    getString(R.string.heart_rate_status_connected, name, bpm));
            textHeartRateHint.setTextColor(getColor(R.color.text_secondary));
        } else {
            textHeartRateHint.setText(
                    getString(R.string.heart_rate_status_idle, name));
            textHeartRateHint.setTextColor(getColor(R.color.warn));
        }
    }

    /** 「上报型号类型」下拉：挂在设备型号下面，比子选项再缩进一级 */
    private void addModelModeRow(float density) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Math.round(density * 44), Math.round(density * 2),
                0, Math.round(density * 6));

        TextView label = new TextView(this);
        label.setText(R.string.label_model_mode);
        label.setTextSize(11f);
        label.setTextColor(getColor(R.color.text_secondary));
        wrap.addView(label);

        spinnerModelMode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{ModelDisplayMode.REAL_MODEL.label(this),
                        ModelDisplayMode.PRODUCT_NAME.label(this)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModelMode.setAdapter(adapter);
        spinnerModelMode.setContentDescription(getString(R.string.label_model_mode));
        spinnerModelMode.setSelection(
                settings.getModelDisplayMode() == ModelDisplayMode.REAL_MODEL ? 0 : 1);
        spinnerModelMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (applyingSettings) return;
                settings.setModelDisplayMode(position == 0
                        ? ModelDisplayMode.REAL_MODEL
                        : ModelDisplayMode.PRODUCT_NAME);
                refreshPreview();
                ReporterService.wake();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 无需处理
            }
        });
        wrap.addView(spinnerModelMode, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        containerFields.addView(wrap);
    }

    private void applyModelModeToSpinner() {
        if (spinnerModelMode == null) return;
        spinnerModelMode.setSelection(
                settings.getModelDisplayMode() == ModelDisplayMode.REAL_MODEL ? 0 : 1);
    }

    /** 音乐字段下方的权限提示：未授权时高亮，点按跳系统设置 */
    private void refreshMusicHint() {
        if (textMusicHint == null || settings == null) return;
        boolean enabled = ReportBuilder.hasMusicFields(settings.getEnabledFields());
        textMusicHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        boolean granted = NowPlayingRepository.hasAccess(this);
        textMusicHint.setText(granted
                ? R.string.music_access_granted_hint
                : R.string.music_access_hint);
        textMusicHint.setTextColor(getColor(granted ? R.color.text_secondary : R.color.warn));
    }

    private void promptMusicAccess() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.music_access_dialog_title)
                .setMessage(R.string.music_access_dialog_message)
                .setPositiveButton(R.string.music_access_go, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openNotificationAccess();
                    }
                })
                .setNegativeButton(R.string.common_later, null)
                .show();
    }

    private void promptHeartRate() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.heart_rate_prompt_title)
                .setMessage(R.string.heart_rate_prompt_message)
                .setPositiveButton(R.string.heart_rate_prompt_go,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                startActivity(new Intent(MainActivity.this,
                                        HeartRateActivity.class));
                            }
                        })
                .setNegativeButton(R.string.common_later, null)
                .show();
    }

    private void openNotificationAccess() {
        if (NowPlayingRepository.hasAccess(this)) {
            toast(getString(R.string.music_access_granted_hint));
            refreshMusicHint();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable t) {
            toast(getString(R.string.music_access_failed));
        }
    }

    private void applySettingsToViews() {
        applyingSettings = true;
        try {
            spinnerIpMode.setSelection(settings.isLocalTarget() ? 0 : 1);
            inputIp.setText(settings.getCustomIp());
            inputPort.setText(String.valueOf(settings.getTargetPort()));
            inputInterval.setText(String.valueOf(settings.getIntervalSec()));
            checkAscii.setChecked(settings.isAsciiOnly());
            checkSendImmediately.setChecked(settings.isSendImmediately());
            checkNotificationSound.setChecked(settings.isNotificationSound());

            Set<ReportField> enabled = settings.getEnabledFields();
            for (CheckBox box : fieldBoxes) {
                box.setChecked(enabled.contains(box.getTag()));
            }
            applyModelModeToSpinner();
            if (textWeatherHint != null) {
                String weatherCity = settings.getWeatherCityLabel();
                textWeatherHint.setText(weatherCity.isEmpty()
                        ? getString(R.string.weather_hint_no_city)
                        : getString(R.string.weather_hint_city, weatherCity));
            }
            if (checkHideUnavailable != null) {
                checkHideUnavailable.setChecked(settings.isHideUnavailable());
            }
        } finally {
            applyingSettings = false;
        }
        updateIpModeUi();
        refreshLaunchArg();
    }

    private void attachWatchers() {
        inputIp.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (settings.isLocalTarget()) return;
                settings.setTargetIp(s.toString());
                refreshLaunchArg();
            }
        });
        inputPort.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Integer value = parseIntOrNull(s.toString());
                if (value != null) settings.setTargetPort(value);
            }
        });
        inputInterval.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Integer value = parseIntOrNull(s.toString());
                if (value == null) return;
                int normalized = Math.max(AppSettings.MIN_INTERVAL,
                        Math.min(AppSettings.MAX_INTERVAL, value));
                if (normalized == settings.getIntervalSec()) return;
                settings.setIntervalSec(value);
                // 服务正在跑就按新间隔立即重排，不用等旧周期
                ReporterService.onIntervalChanged();
            }
        });
        checkAscii.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.setAsciiOnly(isChecked);
                refreshPreview();
            }
        });
        checkSendImmediately.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.setSendImmediately(isChecked);
            }
        });
        checkNotificationSound.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.setNotificationSound(isChecked);
            }
        });
    }

    private void attachClicks() {
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ReporterService.isRunning()) ReporterService.stop(MainActivity.this);
                else ReporterService.start(MainActivity.this);
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshRunningState();
                    }
                }, 300);
            }
        });

        findViewById(R.id.btn_send_now).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ReporterService.isRunning()) {
                    ReporterService.sendNow(MainActivity.this);
                    toast(getString(R.string.toast_sent_now));
                } else {
                    collectOnce();
                    toast(getString(R.string.toast_collected_not_running));
                }
            }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectOnce();
            }
        });

        findViewById(R.id.btn_privilege).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPrivilegeModeDialog();
            }
        });

        findViewById(R.id.btn_recheck).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshPrivilegeTexts();
                recheckPrivilege();
            }
        });

        findViewById(R.id.btn_battery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatteryWhitelist();
            }
        });

        findViewById(R.id.btn_all_on).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.setEnabledFields(EnumSet.allOf(ReportField.class));
                applySettingsToViews();
                refreshPreview();
                refreshMusicHint();
                refreshHeartRateHint();
                ReporterService.wake();
                if (!NowPlayingRepository.hasAccess(MainActivity.this)) {
                    promptMusicAccess();
                }
            }
        });

        findViewById(R.id.btn_all_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.setEnabledFields(EnumSet.noneOf(ReportField.class));
                applySettingsToViews();
                refreshPreview();
                refreshMusicHint();
                refreshHeartRateHint();
                ReporterService.wake();
            }
        });

        findViewById(R.id.btn_clear_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ReporterService.clearLogs();
                refreshLog();
            }
        });

        textForegroundApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUsageAccessSettings();
            }
        });

        findViewById(R.id.btn_scan_vrchat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanForVrchat();
            }
        });
    }

    // ---------------- 局域网扫描：找 9000 端口的 VRChat ----------------

    private boolean scanning;

    private void scanForVrchat() {
        if (scanning) return;
        scanning = true;
        toast(getString(R.string.scanning_toast));

        runInBackground(new Runnable() {
            @Override
            public void run() {
                List<String> found;
                try {
                    found = LanScanner.scan(MainActivity.this, null);
                } catch (Throwable t) {
                    found = new ArrayList<>();
                }
                final List<String> result = found;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        showScanResult(result);
                    }
                });
            }
        });
    }

    private void showScanResult(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            toast(getString(R.string.scan_none));
            return;
        }
        if (candidates.size() == 1) {
            confirmConnect(candidates.get(0));
            return;
        }
        final String[] items = candidates.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.scan_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmConnect(items[which]);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void confirmConnect(final String ip) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.connect_title)
                .setMessage(getString(R.string.connect_message, ip))
                .setPositiveButton(R.string.connect_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        connectTo(ip);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void connectTo(String ip) {
        settings.setLocalTarget(false);
        settings.setTargetIp(ip);
        settings.setTargetPort(9000);
        applySettingsToViews();
        refreshLaunchArg();
        if (ReporterService.isRunning()) {
            ReporterService.sendNow(this);
        } else {
            ReporterService.start(this);
        }
        toast(getString(R.string.toast_connected, ip));
    }

    // ---------------- 菜单：设置 ----------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 权限申请方式：Shizuku / Root / 免授权 */
    private void showPrivilegeModeDialog() {
        final PrivilegeMode[] modes = new PrivilegeMode[]{
                PrivilegeMode.SHIZUKU, PrivilegeMode.ROOT, PrivilegeMode.NONE};
        String[] labels = new String[]{
                modes[0].label(this), modes[1].label(this),
                getString(R.string.privilege_none_dialog)};
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == settings.getPrivilegeMode()) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_privilege))
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        applyPrivilegeMode(modes[which]);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void showAboutDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        TextView device = view.findViewById(R.id.text_about_device);
        TextView version = view.findViewById(R.id.text_about_version);
        AboutLinks.bind(view.findViewById(R.id.text_about_links));

        DeviceSnapshot snapshot = ReporterService.getLatestSnapshot();
        if (snapshot == null) snapshot = localSnapshot;

        String brand = snapshot != null && snapshot.brand != null ? snapshot.brand : Build.BRAND;
        String model = snapshot != null && snapshot.model != null ? snapshot.model : Build.MODEL;
        String series = snapshot == null ? null : snapshot.seriesName;
        String cpu = snapshot == null ? null : snapshot.cpuName;
        String gpu = snapshot == null ? null : snapshot.gpuName;

        String unknown = getString(R.string.common_unknown);
        String na = getString(R.string.not_available);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.about_brand, brand == null ? unknown : brand)).append('\n');
        if (series != null && !series.trim().isEmpty()) {
            sb.append(getString(R.string.about_model_series,
                    model == null ? unknown : model, series));
        } else {
            sb.append(getString(R.string.about_model, model == null ? unknown : model));
        }
        sb.append('\n');
        sb.append(getString(R.string.about_cpu,
                cpu == null || cpu.trim().isEmpty() ? na : cpu)).append('\n');
        sb.append(getString(R.string.about_gpu,
                gpu == null || gpu.trim().isEmpty() ? na : gpu)).append('\n');
        sb.append(getString(R.string.about_api, Build.VERSION.SDK_INT));
        device.setText(sb.toString());

        String versionName = "4.9.0 Release";
        int versionCode = 0;
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = info.versionName;
            versionCode = info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 取不到就用默认值
        }
        version.setText(getString(R.string.about_version_value, versionName, versionCode));

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.about_title))
                .setView(view)
                .setPositiveButton(R.string.common_close, null)
                .show();
    }

    // ---------------- 权限流程 ----------------

    private void applyPrivilegeMode(PrivilegeMode mode) {
        settings.setPrivilegeMode(mode);
        refreshPrivilegeTexts();
        textRootHint.setVisibility(View.GONE);

        if (mode == PrivilegeMode.ROOT) {
            detectAndRequestRoot();
            return;
        }
        if (mode == PrivilegeMode.SHIZUKU) {
            if (!ShizukuShell.ping()) {
                textPrivilegeStatus.setText(R.string.privilege_shizuku_not_running);
                return;
            }
            if (!ShizukuShell.granted()) {
                textPrivilegeStatus.setText(R.string.privilege_shizuku_requesting);
                ShizukuShell.requestPermission();
                return;
            }
        }
        collectOnce();
    }

    /**
     * 选择 Root 后的自动流程：
     * 已授权 -> 直接用；未授权 -> 判断面具类型，内核级跳转超级用户列表，Magisk 系直接申请。
     */
    private void detectAndRequestRoot() {
        textPrivilegeStatus.setText(R.string.privilege_root_detecting);
        runInBackground(new Runnable() {
            @Override
            public void run() {
                Integer uid = RootShell.probe(MainActivity.this);
                if (uid != null) {
                    postStatus(getString(R.string.privilege_root_detected_uid), null);
                    collectOnce();
                    return;
                }

                List<String> paths = new ArrayList<>();
                if (ShizukuShell.granted()) {
                    ShellResult result = new ShizukuShell().run(MainActivity.this, RootController.DETECT_SCRIPT, 3000L);
                    if (result != null && result.isOk()) {
                        for (String line : result.stdout.split("\n")) {
                            String trimmed = line.trim();
                            if (!trimmed.isEmpty()) paths.add(trimmed);
                        }
                    }
                }

                final RootController.RootState state = RootController.stateFrom(null, paths, MainActivity.this);
                final RootManager manager = state.manager;

                if (manager.kernelLevel) {
                    // 内核级面具（KernelSU / APatch）：跳转到管理器让用户手动授权
                    final boolean opened = RootController.openSuperuserPage(MainActivity.this, manager);
                    postStatus(state.detail, opened
                            ? RootController.manualGrantHint(MainActivity.this, manager)
                            : getString(R.string.root_manager_missing, RootController.label(MainActivity.this, manager)));
                    return;
                }

                // Magisk / Alpha / Kitsune：直接执行 su，触发系统授权弹窗（会阻塞几秒）
                postStatus(getString(R.string.privilege_root_requesting), null);
                boolean granted = RootController.requestRootBlocking(MainActivity.this);
                postStatus(granted
                                ? getString(R.string.privilege_root_granted)
                                : getString(R.string.privilege_root_timeout),
                        granted ? null : RootController.manualGrantHint(MainActivity.this, manager));
                if (granted) collectOnce();
            }
        });
    }

    private void recheckPrivilege() {
        final PrivilegeMode mode = settings.getPrivilegeMode();
        if (mode == PrivilegeMode.SHIZUKU) {
            refreshPrivilegeTexts();
            if (ShizukuShell.granted()) collectOnce();
            return;
        }
        if (mode == PrivilegeMode.ROOT) {
            detectAndRequestRoot();
            return;
        }
        collectOnce();
    }

    private void refreshPrivilegeTexts() {
        PrivilegeMode mode = settings.getPrivilegeMode();
        textPrivilegeMode.setText(getString(R.string.privilege_current, mode.label(this)));
        String status = PrivilegeController.shellFor(mode).statusText(this);
        if (VrSupport.isVrDevice()) {
            status = status + "\n（VR 一体机：Shizuku / Root 通常不可用，建议选「免授权」；"
                    + "温度、帧率等依赖特权通道的数据可能读不到）";
        }
        textPrivilegeStatus.setText(status);
    }

    private void postStatus(final String status, final String hint) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (status != null) textPrivilegeStatus.setText(status);
                if (hint == null) {
                    textRootHint.setVisibility(View.GONE);
                } else {
                    textRootHint.setText(hint);
                    textRootHint.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    // ---------------- 采集与预览 ----------------

    private void collectOnce() {
        runInBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    final DeviceInfoRepository.CollectResult result =
                            repository.collect(PrivilegeController.shellFor(settings.getPrivilegeMode()));
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            localSnapshot = result.snapshot;
                            refreshPreview();
                            refreshPrivilegeTexts();
                        }
                    });
                } catch (Throwable t) {
                    toast(getString(R.string.toast_collect_failed, t.getClass().getSimpleName()));
                }
            }
        });
    }

    /** 启动/勾选天气字段时，如果没有选城市就引导一次 */
    private void maybePromptWeatherCity() {
        if (weatherPromptShown) return;
        boolean anyWeather = false;
        for (ReportField field : settings.getEnabledFields()) {
            if (field.isWeather()) {
                anyWeather = true;
                break;
            }
        }
        if (anyWeather && settings.getWeatherCity().isEmpty()) {
            promptWeatherCity();
        }
    }

    private void promptWeatherCity() {
        if (isFinishing() || weatherPromptShown) return;
        weatherPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.weather_prompt_title)
                .setMessage(R.string.weather_prompt_message)
                .setPositiveButton(R.string.go_choose, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(MainActivity.this, WeatherCityActivity.class));
                    }
                })
                .setNegativeButton(R.string.common_later, null)
                .show();
    }

    private void refreshPreview() {
        DeviceSnapshot snapshot = ReporterService.getLatestSnapshot();
        if (snapshot == null) snapshot = localSnapshot;
        if (snapshot == null) {
            textPreview.setText(R.string.preview_empty);
            textPreviewCount.setText(getString(R.string.preview_count,
                    0, ReportBuilder.CHATBOX_LIMIT));
            textPreviewHint.setVisibility(View.GONE);
            return;
        }
        Set<ReportField> enabledFields = settings.getEnabledFields();
        WeatherInfo cachedWeather = WeatherRepository.getCached(this);
        MusicInfo music = ReportBuilder.hasMusicFields(enabledFields)
                ? NowPlayingRepository.snapshot(this) : null;
        int bpm = enabledFields.contains(ReportField.HEART_RATE)
                ? HeartRateManager.getFreshBpm() : 0;
        Integer heartRate = bpm > 0 ? bpm : null;
        ReportBuilder.Report report = ReportBuilder.build(
                snapshot,
                enabledFields,
                settings.isAsciiOnly(),
                settings.getModelDisplayMode() == ModelDisplayMode.PRODUCT_NAME,
                cachedWeather,
                settings.isHideUnavailable(),
                LocaleHelper.getLanguage(this),
                music,
                heartRate);
        textPreview.setText(report.text);
        textPreviewCount.setText(getString(R.string.preview_count,
                report.charCount(), ReportBuilder.CHATBOX_LIMIT));
        String hint = report.limitHint(this);
        if (enabledFields.contains(ReportField.VRCHAT_FPS)
                && report.text.contains("FPS: 不可用")) {
            String extra = getString(R.string.hint_fps_need_foreground);
            hint = hint == null ? extra : hint + "\n" + extra;
        }
        boolean weatherEnabled = false;
        for (ReportField field : enabledFields) {
            if (field.isWeather()) weatherEnabled = true;
        }
        if (weatherEnabled && settings.getWeatherCity().isEmpty()) {
            String extra = getString(R.string.hint_weather_need_city);
            hint = hint == null ? extra : hint + "\n" + extra;
        } else if (weatherEnabled && cachedWeather == null) {
            String extra = getString(R.string.hint_weather_no_data);
            hint = hint == null ? extra : hint + "\n" + extra;
        }
        boolean musicEnabled = ReportBuilder.hasMusicFields(enabledFields);
        if (musicEnabled && !NowPlayingRepository.hasAccess(this)) {
            String extra = getString(R.string.hint_music_need_access);
            hint = hint == null ? extra : hint + "\n" + extra;
        } else if (musicEnabled && music == null) {
            String extra = getString(R.string.hint_music_not_playing);
            hint = hint == null ? extra : hint + "\n" + extra;
        }
        if (enabledFields.contains(ReportField.HEART_RATE) && heartRate == null) {
            String extra = HeartRateManager.hasSelectedDevice(this)
                    ? getString(R.string.hint_heart_rate_no_data)
                    : getString(R.string.hint_heart_rate_no_device);
            hint = hint == null ? extra : hint + "\n" + extra;
        }
        if (settings.isHideUnavailable() && !report.hidden.isEmpty()) {
            String extra = getString(R.string.hint_hidden_count, report.hidden.size());
            hint = hint == null ? extra : hint + "\n" + extra;
        }
        if (report.text.isEmpty() && !enabledFields.isEmpty()) {
            hint = getString(R.string.hint_all_hidden);
        }
        if (hint == null) {
            textPreviewHint.setVisibility(View.GONE);
        } else {
            textPreviewHint.setText(hint);
            textPreviewHint.setVisibility(View.VISIBLE);
        }
    }

    // ---------------- 界面刷新 ----------------

    private void refreshFromService() {
        refreshRunningState();
        refreshPreview();
        refreshLog();
        String serviceWarning = ReporterService.getWarning();
        if (serviceWarning != null) {
            textWarning.setText(serviceWarning);
            textWarning.setVisibility(View.VISIBLE);
        } else {
            textWarning.setVisibility(View.GONE);
        }
    }

    private void refreshRunningState() {
        boolean running = ReporterService.isRunning();
        textRunningState.setText(running
                ? getString(R.string.running_state, settings.getIntervalSec())
                : getString(R.string.not_running));
        btnToggle.setText(running ? getString(R.string.action_stop) : getString(R.string.action_start));
    }

    private void refreshLog() {
        List<ReporterService.LogEntry> logs = ReporterService.getLogs();
        if (logs.isEmpty()) {
            textLog.setText(R.string.log_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(logs.size(), 8);
        for (int i = 0; i < shown; i++) {
            ReporterService.LogEntry entry = logs.get(i);
            sb.append(entry.timeText()).append(entry.ok ? " ✓ " : " ✗ ").append(entry.text);
            if (entry.detail != null) sb.append("\n    ").append(entry.detail);
            sb.append('\n');
        }
        textLog.setText(sb.toString());
    }

    // ---------------- 前台应用检测 ----------------

    /**
     * 刷新「当前前台应用」。
     * 未授权时只提示并引导去系统设置，不猜测、不缓存旧结果。
     */
    private void refreshForegroundApp() {
        if (!ForegroundApp.hasUsageAccess(this)) {
            textForegroundApp.setText(R.string.foreground_unauthed);
            textForegroundApp.setTextColor(getColor(R.color.warn));
            return;
        }
        String pkg = ForegroundApp.current(this);
        if (pkg == null) {
            textForegroundApp.setText(R.string.foreground_unknown);
            textForegroundApp.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        boolean own = getPackageName().equals(pkg);
        String name = ForegroundApp.displayLabel(this, pkg);
        StringBuilder sb = new StringBuilder();
        if (!own && !pkg.equals(name)) {
            sb.append(getString(R.string.foreground_value_pkg, name, pkg));
        } else {
            sb.append(getString(R.string.foreground_value, name));
        }
        if (own) {
            // 界面自己在前台时，顺便告诉用户此前用的是什么，避免只看到"VRCDI"以为没生效
            String other = ForegroundApp.lastOther(this);
            if (other != null) {
                sb.append(getString(R.string.foreground_previous,
                        ForegroundApp.displayLabel(this, other)));
            }
        }
        textForegroundApp.setText(sb.toString());
        textForegroundApp.setTextColor(getColor(R.color.text_secondary));
    }

    private void openUsageAccessSettings() {
        if (ForegroundApp.hasUsageAccess(this)) {
            toast(getString(R.string.toast_usage_granted));
            refreshForegroundApp();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            toast(getString(R.string.toast_usage_failed));
        }
    }

    private void loadLocalIp() {
        runInBackground(new Runnable() {
            @Override
            public void run() {
                final List<String> ips = new ArrayList<>();
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        NetworkInterface nif = interfaces.nextElement();
                        Enumeration<InetAddress> addresses = nif.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            InetAddress address = addresses.nextElement();
                            if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                                String host = address.getHostAddress();
                                if (host != null && !ips.contains(host)) ips.add(host);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 取不到就不显示
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        textLocalIp.setText(ips.isEmpty()
                                ? getString(R.string.local_ip_none)
                                : getString(R.string.local_ip_value,
                                        join(ips, getString(R.string.list_separator))));
                        refreshLaunchArg();
                    }
                });
            }
        });
    }

    /**
     * VRChat 侧启动参数提示。
     * VRChat 默认只在 127.0.0.1 上接收 OSC，手机在同一局域网也发不进去，
     * 必须用 --osc=inPort:senderIP:outPort 把绑定地址换成 PC 自己的局域网 IP。
     */
    private void refreshLaunchArg() {
        String ip = settings.getTargetIp();
        String target = (ip == null || ip.trim().isEmpty())
                ? getString(R.string.launch_arg_placeholder) : ip.trim();
        textLaunchArg.setText(getString(R.string.launch_arg, target));
    }

    // ---------------- 系统交互 ----------------

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(PERMISSION_POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{PERMISSION_POST_NOTIFICATIONS}, REQUEST_CODE_NOTIFICATION);
    }

    private void ensureBatteryWhitelistHint() {
        if (settings.isBatteryHintShown()) return;
        settings.setBatteryHintShown(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_hint_title)
                .setMessage(R.string.battery_hint_message)
                .setPositiveButton(R.string.go_settings, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openBatteryWhitelist();
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private void openBatteryWhitelist() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast(getString(R.string.toast_battery_already));
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                toast(getString(R.string.toast_battery_failed));
            }
        }
    }

    // ---------------- Shizuku 授权回调 ----------------

    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuListener =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != ShizukuShell.REQUEST_CODE_PERMISSION) return;
                    boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    textPrivilegeStatus.setText(granted
                            ? getString(R.string.privilege_shizuku_granted)
                            : getString(R.string.privilege_shizuku_denied));
                    if (granted) collectOnce();
                }
            };

    @Override
    protected void onStart() {
        super.onStart();
        ReporterService.addListener(this);
        ShizukuShell.addResultListener(shizukuListener);
        refreshFromService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页回来时可能刚导入了品牌设备表，重新加载一次
        repository.reloadModelTable();
        applySettingsToViews();
        refreshPrivilegeTexts();
        refreshFromService();
        refreshForegroundApp();
        refreshMusicHint();
        refreshHeartRateHint();
        foregroundTicking = true;
        main.removeCallbacks(foregroundTick);
        main.postDelayed(foregroundTick, 2000L);
    }

    @Override
    protected void onPause() {
        foregroundTicking = false;
        main.removeCallbacks(foregroundTick);
        super.onPause();
    }

    @Override
    protected void onStop() {
        ReporterService.removeListener(this);
        ShizukuShell.removeResultListener(shizukuListener);
        super.onStop();
    }

    @Override
    public void onReporterUpdate() {
        refreshFromService();
    }

    // ---------------- 工具 ----------------

    private void runInBackground(Runnable task) {
        Thread thread = new Thread(task, "vrcdi-ui-worker");
        thread.setDaemon(true);
        background = thread;
        thread.start();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String join(List<String> items, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static Integer parseIntOrNull(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 只关心 afterTextChanged 的简化实现 */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
