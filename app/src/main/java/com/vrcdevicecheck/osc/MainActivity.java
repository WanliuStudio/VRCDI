package com.vrcdevicecheck.osc;

import android.app.Activity;
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
import com.vrcdevicecheck.osc.data.ModelDisplayMode;
import com.vrcdevicecheck.osc.data.PrivilegeMode;
import com.vrcdevicecheck.osc.data.ReportField;
import com.vrcdevicecheck.osc.device.DeviceInfoRepository;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.device.ForegroundApp;
import com.vrcdevicecheck.osc.osc.ReportBuilder;
import com.vrcdevicecheck.osc.priv.PrivilegeController;
import com.vrcdevicecheck.osc.priv.RootController;
import com.vrcdevicecheck.osc.priv.RootManager;
import com.vrcdevicecheck.osc.priv.RootShell;
import com.vrcdevicecheck.osc.priv.ShellResult;
import com.vrcdevicecheck.osc.priv.ShizukuShell;
import com.vrcdevicecheck.osc.service.ReporterService;
import com.vrcdevicecheck.osc.theme.ThemeManager;

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
public class MainActivity extends Activity implements ReporterService.Listener {

    /** API 33 才有通知运行时权限；compileSdk 30 里没有这个常量，所以用字面量 */
    private static final String PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int REQUEST_CODE_NOTIFICATION = 33;
    private AppSettings settings;
    private DeviceInfoRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread background;

    /** 程序化回填界面时置 true，避免 Spinner 回调把默认值写回配置 */
    private boolean applyingSettings;

    /** 界面可见时每 2 秒刷新一次「前台应用」 */
    private boolean foregroundTicking;

    private final Runnable foregroundTick = new Runnable() {
        @Override
        public void run() {
            refreshForegroundApp();
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

    private DeviceSnapshot localSnapshot;
    private final List<CheckBox> fieldBoxes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new AppSettings(this);
        repository = new DeviceInfoRepository(this);

        bindViews();
        setupIpModeSpinner();
        buildFieldCheckboxes();
        applySettingsToViews();
        attachWatchers();
        attachClicks();

        refreshPrivilegeTexts();
        refreshFromService();
        loadLocalIp();
        requestNotificationPermissionIfNeeded();
        ensureBatteryWhitelistHint();

        // 放在所有控件都创建完之后，动态生成的勾选框也能一起套上主题
        ThemeManager.apply(this);
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
                ? "发送到 127.0.0.1：VRChat 与 VRCDI 在同一台设备（Quest 独立运行选这个）"
                : "填写 VRChat 所在设备的 IP，例如 PC 的局域网地址 192.168.1.10");
    }

    /** 13 个字段用代码生成勾选框，避免 13 段重复 XML */
    private void buildFieldCheckboxes() {
        containerFields.removeAllViews();
        fieldBoxes.clear();
        for (final ReportField field : ReportField.values()) {
            CheckBox box = new CheckBox(this);
            box.setText(field.label);
            box.setTag(field);
            box.setTextColor(getColor(R.color.text_primary));
            box.setTextSize(14f);
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
                }
            });
            fieldBoxes.add(box);
            if (field == ReportField.MODEL) {
                // 设备型号这一行右边挂「上报型号类型」下拉菜单
                containerFields.addView(buildModelRow(box));
            } else {
                containerFields.addView(box);
            }
        }
    }

    /** 「设备型号」勾选框 + 右侧「上报型号类型」下拉菜单 */
    private View buildModelRow(CheckBox modelBox) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("上报型号类型");
        label.setTextSize(11f);
        label.setTextColor(getColor(R.color.text_secondary));
        container.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(modelBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        spinnerModelMode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{ModelDisplayMode.REAL_MODEL.label, ModelDisplayMode.PRODUCT_NAME.label});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModelMode.setAdapter(adapter);
        spinnerModelMode.setContentDescription("上报型号类型");
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
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 无需处理
            }
        });
        row.addView(spinnerModelMode, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private void applyModelModeToSpinner() {
        if (spinnerModelMode == null) return;
        spinnerModelMode.setSelection(
                settings.getModelDisplayMode() == ModelDisplayMode.REAL_MODEL ? 0 : 1);
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
                if (value != null) settings.setIntervalSec(value);
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
                    toast("已触发一次立即发送");
                } else {
                    collectOnce();
                    toast("当前未在上报，已采集一次；填写 IP 后启动上报即会自动发送");
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
            }
        });

        findViewById(R.id.btn_all_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.setEnabledFields(EnumSet.noneOf(ReportField.class));
                applySettingsToViews();
                refreshPreview();
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
                modes[0].label, modes[1].label, "免授权（仅读系统开放信息）"};
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
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAboutDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        TextView device = view.findViewById(R.id.text_about_device);
        TextView version = view.findViewById(R.id.text_about_version);

        DeviceSnapshot snapshot = ReporterService.getLatestSnapshot();
        if (snapshot == null) snapshot = localSnapshot;

        String brand = snapshot != null && snapshot.brand != null ? snapshot.brand : Build.BRAND;
        String model = snapshot != null && snapshot.model != null ? snapshot.model : Build.MODEL;
        String series = snapshot == null ? null : snapshot.seriesName;
        String cpu = snapshot == null ? null : snapshot.cpuName;
        String gpu = snapshot == null ? null : snapshot.gpuName;

        StringBuilder sb = new StringBuilder();
        sb.append("品牌：").append(brand == null ? "未知" : brand).append('\n');
        sb.append("型号：").append(model == null ? "未知" : model);
        if (series != null && !series.trim().isEmpty()) sb.append("（").append(series).append("）");
        sb.append('\n');
        sb.append("CPU：").append(cpu == null || cpu.trim().isEmpty() ? "不可用" : cpu).append('\n');
        sb.append("GPU：").append(gpu == null || gpu.trim().isEmpty() ? "不可用" : gpu).append('\n');
        sb.append("安卓API版本：API").append(Build.VERSION.SDK_INT);
        device.setText(sb.toString());

        String versionName = "2.0.1 Omega";
        int versionCode = 0;
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = info.versionName;
            versionCode = info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 取不到就用默认值
        }
        version.setText(versionName + " (Build " + versionCode + ")");

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.about_title))
                .setView(view)
                .setPositiveButton("关闭", null)
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
                textPrivilegeStatus.setText("Shizuku 未运行：请打开 Shizuku 应用启动服务（Android 11+ 可用无线调试）");
                return;
            }
            if (!ShizukuShell.granted()) {
                textPrivilegeStatus.setText("正在请求 Shizuku 授权…");
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
        textPrivilegeStatus.setText("正在检测 root 环境…");
        runInBackground(new Runnable() {
            @Override
            public void run() {
                Integer uid = RootShell.probe();
                if (uid != null) {
                    postStatus("Root 已授权（uid=0）", null);
                    collectOnce();
                    return;
                }

                List<String> paths = new ArrayList<>();
                if (ShizukuShell.granted()) {
                    ShellResult result = new ShizukuShell().run(RootController.DETECT_SCRIPT, 3000L);
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
                            ? RootController.manualGrantHint(manager)
                            : "没有检测到 " + manager.label + " 应用，请手动打开它的超级用户列表给 VRCDI 授权");
                    return;
                }

                // Magisk / Alpha / Kitsune：直接执行 su，触发系统授权弹窗（会阻塞几秒）
                postStatus("正在申请 root 授权，请在弹窗中点允许…", null);
                boolean granted = RootController.requestRootBlocking();
                postStatus(granted ? "Root 已授权" : "root 授权被拒绝或超时",
                        granted ? null : RootController.manualGrantHint(manager));
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
        textPrivilegeMode.setText("当前方式：" + mode.label);
        textPrivilegeStatus.setText(PrivilegeController.shellFor(mode).statusText(this));
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
                    toast("采集失败：" + t.getClass().getSimpleName());
                }
            }
        });
    }

    private void refreshPreview() {
        DeviceSnapshot snapshot = ReporterService.getLatestSnapshot();
        if (snapshot == null) snapshot = localSnapshot;
        if (snapshot == null) {
            textPreview.setText("尚未采集，点「重新采集」或启动上报");
            textPreviewCount.setText("0/144");
            textPreviewHint.setVisibility(View.GONE);
            return;
        }
        ReportBuilder.Report report = ReportBuilder.build(
                snapshot,
                settings.getEnabledFields(),
                settings.isAsciiOnly(),
                settings.getModelDisplayMode() == ModelDisplayMode.PRODUCT_NAME);
        textPreview.setText(report.text);
        textPreviewCount.setText(report.charCount() + "/" + ReportBuilder.CHATBOX_LIMIT);
        String hint = report.limitHint();
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
                ? "正在上报：每 " + settings.getIntervalSec() + " 秒一次"
                : "未在上报");
        btnToggle.setText(running ? getString(R.string.action_stop) : getString(R.string.action_start));
    }

    private void refreshLog() {
        List<ReporterService.LogEntry> logs = ReporterService.getLogs();
        if (logs.isEmpty()) {
            textLog.setText("暂无记录");
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
            textForegroundApp.setText("前台应用：未授权「使用情况访问」，点这里去开启");
            textForegroundApp.setTextColor(getColor(R.color.warn));
            return;
        }
        String pkg = ForegroundApp.current(this);
        if (pkg == null) {
            textForegroundApp.setText("前台应用：未检测到（最近没有切换记录）");
            textForegroundApp.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        boolean own = getPackageName().equals(pkg);
        String name = ForegroundApp.label(this, pkg);
        StringBuilder sb = new StringBuilder("前台应用：").append(name);
        if (!own && !pkg.equals(name)) sb.append("（").append(pkg).append("）");
        if (own) {
            // 界面自己在前台时，顺便告诉用户此前用的是什么，避免只看到"VRCDI"以为没生效
            String other = ForegroundApp.lastOther(this);
            if (other != null) sb.append(" · 此前：").append(ForegroundApp.label(this, other));
        }
        textForegroundApp.setText(sb.toString());
        textForegroundApp.setTextColor(getColor(R.color.text_secondary));
    }

    private void openUsageAccessSettings() {
        if (ForegroundApp.hasUsageAccess(this)) {
            toast("已获得「使用情况访问」权限");
            refreshForegroundApp();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            toast("无法打开「使用情况访问」设置，请在系统设置里手动授予");
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
                                ? "本机局域网 IP：未获取到（请确认已连 Wi-Fi）"
                                : "本机局域网 IP：" + join(ips, "、"));
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
        String target = (ip == null || ip.trim().isEmpty()) ? "<PC的局域网IP>" : ip.trim();
        textLaunchArg.setText("VRChat 启动参数（Steam 里加）：--osc=9000:" + target + ":9001\n"
                + "同一局域网 + PC 防火墙放行 UDP 9000，然后在动作菜单里开启 OSC");
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
                .setTitle("建议加入电池优化白名单")
                .setMessage("前台服务能保证大部分场景下不被杀，但国产系统的省电策略更激进。"
                        + "建议在接下来的系统页面里把 VRCDI 设为「不优化」，否则息屏后可能停止上报。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openBatteryWhitelist();
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void openBatteryWhitelist() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已在电池优化白名单中");
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
                toast("无法打开电池优化设置，请在系统设置里手动把 VRCDI 设为不优化");
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
                            ? "Shizuku 已授权（shell 权限，uid=2000）"
                            : "Shizuku 授权被拒绝");
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
