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
 * 会弹窗的两个条目（权限申请方式、关于VRCDI）直接在设置页里弹，
 * 不再返回首页去弹，弹完仍然停在设置页。
 */
public class SettingsActivity extends Activity {

    private static final String[] ITEMS = new String[]{
            "权限申请方式",
            "设备表设置",
            "主题",
            "关于VRCDI"
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
                        startActivity(new Intent(SettingsActivity.this, ThemeSettingsActivity.class));
                        break;
                    default:
                        showAboutDialog();
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

    /** 权限申请方式：Shizuku / Root，弹窗后仍停留在设置页 */
    private void showPrivilegeDialog() {
        final PrivilegeMode[] modes = new PrivilegeMode[]{PrivilegeMode.SHIZUKU, PrivilegeMode.ROOT};
        String[] labels = new String[]{modes[0].label, modes[1].label};
        int checked = settings.getPrivilegeMode() == PrivilegeMode.ROOT ? 1 : 0;

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

    private void showAboutDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        TextView device = view.findViewById(R.id.text_about_device);
        TextView version = view.findViewById(R.id.text_about_version);

        DeviceSnapshot snapshot = ReporterService.getLatestSnapshot();
        String brand = snapshot != null && snapshot.brand != null ? snapshot.brand : Build.BRAND;
        String model = snapshot != null && snapshot.model != null ? snapshot.model : Build.MODEL;
        String series = snapshot == null ? null : snapshot.seriesName;

        StringBuilder sb = new StringBuilder();
        sb.append("品牌：").append(brand == null ? "未知" : brand).append('\n');
        sb.append("型号：").append(model == null ? "未知" : model);
        if (series != null && !series.trim().isEmpty()) sb.append("（").append(series).append("）");
        sb.append('\n');
        sb.append("安卓API版本：API").append(Build.VERSION.SDK_INT);
        device.setText(sb.toString());

        String versionName = "0.1.1 Alpha";
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
