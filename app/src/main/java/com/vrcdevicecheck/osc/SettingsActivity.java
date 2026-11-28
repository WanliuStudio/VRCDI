package com.vrcdevicecheck.osc;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.vrcdevicecheck.osc.data.AppSettings;
import com.vrcdevicecheck.osc.data.PrivilegeMode;
import com.vrcdevicecheck.osc.device.DeviceSnapshot;
import com.vrcdevicecheck.osc.priv.RootShell;
import com.vrcdevicecheck.osc.priv.ShizukuShell;
import com.vrcdevicecheck.osc.service.ReporterService;
import com.vrcdevicecheck.osc.theme.ThemeManager;
import com.vrcdevicecheck.osc.util.AboutLinks;
import com.vrcdevicecheck.osc.util.LocaleHelper;

import rikka.shizuku.Shizuku;

/**
 * 设置一级菜单。
 *
 * 会弹窗的条目（权限申请方式、语言、关于VRCDI）直接在设置页里弹，
 * 不返回首页，弹完仍然停在设置页。
 */
public class SettingsActivity extends BaseActivity {

    private String[] buildItems() {
        String weatherLabel = settings.getWeatherCityLabel();
        String weatherItem = weatherLabel.isEmpty()
                ? getString(R.string.settings_weather_city)
                : getString(R.string.settings_weather_city_with, weatherLabel);
        return new String[]{
                getString(R.string.settings_interval),
                getString(R.string.settings_keep_alive),
                weatherItem,
                getString(R.string.settings_privilege),
                getString(R.string.settings_device_table),
                getString(R.string.settings_app_table),
                getString(R.string.settings_cpu_map),
                getString(R.string.settings_theme),
                getString(R.string.settings_language),
                getString(R.string.settings_tutorial),
                getString(R.string.settings_update),
                getString(R.string.settings_about),
                getString(R.string.settings_exit)
        };
    }

    private AppSettings settings;

    /** Shizuku 授权结果直接在本页提示 */
    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != ShizukuShell.REQUEST_CODE_PERMISSION) return;
                    toast(grantResult == PackageManager.PERMISSION_GRANTED
                            ? getString(R.string.privilege_shizuku_granted_short)
                            : getString(R.string.privilege_shizuku_denied));
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings_title);
        settings = new AppSettings(this);

        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, buildItems()));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        startActivity(new Intent(SettingsActivity.this, ReportIntervalActivity.class));
                        break;
                    case 1:
                        startActivity(new Intent(SettingsActivity.this, KeepAliveSettingsActivity.class));
                        break;
                    case 2:
                        startActivity(new Intent(SettingsActivity.this, WeatherCityActivity.class));
                        break;
                    case 3:
                        showPrivilegeDialog();
                        break;
                    case 4:
                        startActivity(new Intent(SettingsActivity.this, DeviceTableSettingsActivity.class));
                        break;
                    case 5:
                        startActivity(new Intent(SettingsActivity.this, AppTableSettingsActivity.class));
                        break;
                    case 6:
                        startActivity(new Intent(SettingsActivity.this, CpuMapSettingsActivity.class));
                        break;
                    case 7:
                        startActivity(new Intent(SettingsActivity.this, ThemeSettingsActivity.class));
                        break;
                    case 8:
                        showLanguageDialog();
                        break;
                    case 9:
                        // 只开教学页，不动「已引导」标记
                        startActivity(WelcomeActivity.intentForGuide(SettingsActivity.this));
                        break;
                    case 10:
                        startActivity(new Intent(SettingsActivity.this, UpdateSettingsActivity.class));
                        break;
                    case 11:
                        showAboutDialog();
                        break;
                    default:
                        confirmExit();
                        break;
                }
            }
        });

        setContentView(list);
        ThemeManager.apply(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShizukuShell.addResultListener(shizukuListener);
    }

    @Override
    protected void onStop() {
        ShizukuShell.removeResultListener(shizukuListener);
        super.onStop();
    }

    /** 语言：简体中文 / English / 日本語 / Русский / 한국어。默认中文，切换后立刻重启让所有界面重建 */
    private void showLanguageDialog() {
        final String[] values = LocaleHelper.ALL;
        final String[] labels = {
                getString(R.string.language_zh),
                getString(R.string.language_en),
                getString(R.string.language_ja),
                getString(R.string.language_ru),
                getString(R.string.language_ko)};
        String current = LocaleHelper.getLanguage(this);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.language_dialog_title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        String current = LocaleHelper.getLanguage(SettingsActivity.this);
                        if (current.equals(values[which])) return;
                        LocaleHelper.setLanguage(SettingsActivity.this, values[which]);
                        toast(getString(R.string.language_restarting));
                        LocaleHelper.applyAndRestart(SettingsActivity.this);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 权限申请方式：Shizuku / Root / 免授权 */
    private void showPrivilegeDialog() {
        final PrivilegeMode[] modes = new PrivilegeMode[]{
                PrivilegeMode.SHIZUKU, PrivilegeMode.ROOT, PrivilegeMode.NONE};
        String[] labels = new String[]{
                modes[0].label(this), modes[1].label(this),
                getString(R.string.privilege_none_dialog)};

        int checked = 0;
        PrivilegeMode current = settings.getPrivilegeMode();
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == current) checked = i;
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

    /**
     * 只做"保存选择 + 触发授权"，具体采集仍由主界面负责；
     * 主界面 onResume 时会重新读取这个设置。
     */
    private void applyPrivilegeMode(PrivilegeMode mode) {
        settings.setPrivilegeMode(mode);

        if (mode == PrivilegeMode.NONE) {
            toast(getString(R.string.privilege_switch_none));
            return;
        }

        if (mode == PrivilegeMode.ROOT) {
            toast(getString(R.string.privilege_detect_root_toast));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean granted = RootShell.probe(SettingsActivity.this) != null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(granted
                                    ? getString(R.string.privilege_root_granted)
                                    : getString(R.string.privilege_root_denied_hint));
                        }
                    });
                }
            }, "vrcdi-root-probe").start();
            return;
        }

        if (!ShizukuShell.ping()) {
            toast(getString(R.string.privilege_shizuku_need_start));
            return;
        }
        if (ShizukuShell.granted()) {
            toast(getString(R.string.privilege_shizuku_granted_short));
            return;
        }
        toast(getString(R.string.privilege_shizuku_requesting));
        ShizukuShell.requestPermission();
    }

    /** 退出：确认后停止上报服务并结束进程 */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_confirm_title)
                .setMessage(R.string.exit_confirm_message)
                .setPositiveButton(R.string.common_exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        exitApp();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void exitApp() {
        try {
            startService(new Intent(this, ReporterService.class).setAction(ReporterService.ACTION_STOP));
        } catch (Throwable ignored) {
            // 服务没在跑就算了
        }
        try {
            stopService(new Intent(this, ReporterService.class));
        } catch (Throwable ignored) {
            // 忽略
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    private void showAboutDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        TextView device = view.findViewById(R.id.text_about_device);
        TextView version = view.findViewById(R.id.text_about_version);
        AboutLinks.bind(view.findViewById(R.id.text_about_links));

        DeviceSnapshot snapshot = ReporterService.getLatestSnapshot();
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

        String versionName = "5.3.0 Beta4";
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
                .setNeutralButton(R.string.join_us, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        com.vrcdevicecheck.osc.util.JoinUs.show(SettingsActivity.this);
                    }
                })
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
