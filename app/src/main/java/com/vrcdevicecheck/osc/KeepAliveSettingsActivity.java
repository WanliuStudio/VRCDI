package com.vrcdevicecheck.osc;

import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.vrcdevicecheck.osc.data.AppSettings;
import com.vrcdevicecheck.osc.service.ReporterService;
import com.vrcdevicecheck.osc.theme.ThemeManager;

/**
 * 后台保活设置。
 *
 * 开启后：
 *   - 前台服务持有 Wi-Fi 锁（WIFI_MODE_FULL_HIGH_PERF）；
 *   - 用 AlarmManager.setExactAndAllowWhileIdle 定时唤醒，而不是线程 sleep；
 *   - 每次唤醒短暂持有 PARTIAL_WAKE_LOCK，采集 + 发送完成后立即释放；
 *   - 定时器在息屏 / Doze 下也能把 CPU 叫醒。
 *
 * 代价是更耗电，所以做成开关，默认开启（解决非 root 机型后台十几秒被回收的问题）。
 */
public class KeepAliveSettingsActivity extends BaseActivity {

    private AppSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings_keep_alive);
        settings = new AppSettings(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        CheckBox toggle = new CheckBox(this);
        toggle.setText(R.string.keep_alive_switch);
        toggle.setTextSize(15f);
        toggle.setChecked(settings.isKeepAlive());
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.setKeepAlive(isChecked);
                ReporterService.onKeepAliveChanged();
            }
        });
        root.addView(toggle);

        TextView benefit = new TextView(this);
        benefit.setTextSize(13f);
        benefit.setLineSpacing(dp(3), 1f);
        benefit.setPadding(0, dp(14), 0, dp(6));
        benefit.setText(R.string.keep_alive_benefit);
        root.addView(benefit);

        TextView cost = new TextView(this);
        cost.setTextSize(13f);
        cost.setLineSpacing(dp(3), 1f);
        cost.setText(R.string.keep_alive_cost);
        root.addView(cost);

        setContentView(scroll);
        ThemeManager.apply(this);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
