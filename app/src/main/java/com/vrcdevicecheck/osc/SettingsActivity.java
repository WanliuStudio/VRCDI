package com.vrcdevicecheck.osc;

import android.app.Activity;
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

import rikka.shizuku.Shizuku;

/**
 * 设置一级菜单。
 *
 * 会弹窗的条目（权限申请方式、关于VRCDI）直接在设置页里弹，
 * 不返回首页，弹完仍然停在设置页。
 */
public class SettingsActivity extends Activity {

    private static final String[] ITEMS = new String[]{
            "权限申请方式",
            "设备表设置",
            "应用表设置",
            "CPU 映射表设置",
            "主题",
            "软件更新设置",
            "关于VRCDI",
            "退出"
    };

    private AppSettings settings;

    /** Shizuku 授权结果直接在本页提示 */
    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != ShizukuShell.REQUEST_CODE_PERMISSION) return;
                    toast(grantResult == PackageManager.PERMISSION_GRANTED
                            ? "Shizuku 已授权"
                            : "Shizuku 授权被拒绝");
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("设置");
        settings = new AppSettings(this);

        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, ITEMS));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        showPrivilegeDialog();
                        break;
                    case 1:
                        startActivity(new Intent(SettingsActivity.this, DeviceTableSettingsActivity.class));
                        break;
                    case 2:
                        startActivity(new Intent(SettingsActivity.this, AppTableSettingsActivity.class));
                        break;
                    case 3:
                        startActivity(new Intent(SettingsActivity.this, CpuMapSettingsActivity.class));
                        break;
                    case 4:
                        startActivity(new Intent(SettingsActivity.this, ThemeSettingsActivity.class));
                        break;
                    case 5:
                        startActivity(new Intent(SettingsActivity.this, UpdateSettingsActivity.class));
                        break;
                    case 6:
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

    /** 权限申请方式：Shizuku / Root / 免授权 */
    private void showPrivilegeDialog() {
        final PrivilegeMode[] modes = new PrivilegeMode[]{
                PrivilegeMode.SHIZUKU, PrivilegeMode.ROOT, PrivilegeMode.NONE};
        String[] labels = new String[]{
                modes[0].label, modes[1].label, "免授权（仅读系统开放信息）"};

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
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 只做"保存选择 + 触发授权"，具体采集仍由主界面负责；
     * 主界面 onResume 时会重新读取这个设置。
     */
    private void applyPrivilegeMode(PrivilegeMode mode) {
        settings.setPrivilegeMode(mode);

        if (mode == PrivilegeMode.NONE) {
            toast("已切换为免授权：只能读取系统开放的信息");
            return;
        }

        if (mode == PrivilegeMode.ROOT) {
            toast("正在检测 root 授权…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean granted = RootShell.probe() != null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(granted
                                    ? "Root 已授权"
                                    : "未获得 root 授权，请在 root 管理器里给 VRCDI 授权");
                        }
                    });
                }
            }, "vrcdi-root-probe").start();
            return;
        }

        if (!ShizukuShell.ping()) {
            toast("Shizuku 未运行：请先打开 Shizuku 应用并启动服务");
            return;
        }
        if (ShizukuShell.granted()) {
            toast("Shizuku 已授权");
            return;
        }
        toast("正在请求 Shizuku 授权…");
        ShizukuShell.requestPermission();
    }

    /** 退出：确认后停止上报服务并结束进程 */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("退出")
                .setMessage("确定要退出 VRCDI 吗？\n退出后上报会停止，后台服务也会一并关闭。")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        exitApp();
                    }
                })
                .setNegativeButton("取消", null)
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

        DeviceSnapshot snapshot = ReporterService.getLatestSnapshot();
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

        String versionName = "2.3.8 Omega";
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
