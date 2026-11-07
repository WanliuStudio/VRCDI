package com.vrcdevicecheck.osc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vrcdevicecheck.osc.data.AppSettings;
import com.vrcdevicecheck.osc.theme.ThemeManager;
import com.vrcdevicecheck.osc.update.UpdateChecker;
import com.vrcdevicecheck.osc.update.UpdateManager;

import java.io.File;

/**
 * 软件更新设置。
 *
 * 检测原理：
 *   1. 通过 GitHub / Gitee 的「最新发行版」API 拿到 tag_name 与附件列表；
 *   2. 把 tag 里的版本号（如 2.0.0-Omega -> 2.0.0）与当前 versionName 比较，
 *      远端更大才认为有更新；
 *   3. 选附件（优先 .apk，其次 .apk.zip / .zip），用自建 UA 直接下载到
 *      cache/update/VRCDI-update.apk；
 *   4. 通过自带的 ApkProvider 以 content:// 交给系统安装器。
 */
public class UpdateSettingsActivity extends Activity {

    private AppSettings settings;
    private TextView statusView;
    private TextView detailView;
    private Button checkButton;
    private Button installButton;
    private RadioGroup sourceGroup;

    private UpdateChecker.Release latest;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("软件更新设置");
        settings = new AppSettings(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(14);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView current = new TextView(this);
        current.setTextSize(15f);
        current.setText("当前版本：" + versionName() + "（Build " + versionCode() + "）");
        root.addView(current);

        CheckBox autoCheck = new CheckBox(this);
        autoCheck.setText("打开应用时自动检查更新（静默，默认关闭）");
        autoCheck.setTextSize(14f);
        autoCheck.setPadding(0, dp(10), 0, 0);
        autoCheck.setChecked(settings.isAutoUpdateCheck());
        autoCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.setAutoUpdateCheck(isChecked);
            }
        });
        root.addView(autoCheck);

        TextView sourceLabel = new TextView(this);
        sourceLabel.setText("更新源");
        sourceLabel.setTextSize(14f);
        sourceLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(sourceLabel);

        sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton gitee = new RadioButton(this);
        gitee.setText("Gitee（国内）");
        gitee.setId(1001);
        RadioButton github = new RadioButton(this);
        github.setText("GitHub");
        github.setId(1002);
        sourceGroup.addView(gitee);
        sourceGroup.addView(github);
        if (UpdateChecker.SOURCE_GITHUB.equals(settings.getUpdateSource())) {
            sourceGroup.check(1002);
        } else {
            sourceGroup.check(1001);
        }
        sourceGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                settings.setUpdateSource(checkedId == 1002
                        ? UpdateChecker.SOURCE_GITHUB : UpdateChecker.SOURCE_GITEE);
            }
        });
        root.addView(sourceGroup);

        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setPadding(0, dp(14), 0, dp(6));
        statusView.setText("还没有检查更新");
        root.addView(statusView);

        detailView = new TextView(this);
        detailView.setTextSize(12f);
        detailView.setPadding(0, 0, 0, dp(10));
        root.addView(detailView);

        checkButton = new Button(this);
        checkButton.setText("检查更新");
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate();
            }
        });
        root.addView(checkButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        installButton = new Button(this);
        installButton.setText("下载并安装");
        installButton.setEnabled(false);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAndInstall();
            }
        });
        root.addView(installButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setTextSize(12f);
        note.setPadding(0, dp(16), 0, 0);
        note.setText("说明：更新包会下载到应用缓存目录，下载完成后由系统安装器安装。"
                + "首次更新需要允许「安装未知应用」。Gitee 偶尔会把 APK 命名成 .apk.zip，"
                + "下载后会自动按 .apk 处理。");
        root.addView(note);

        setContentView(scroll);
        ThemeManager.apply(this);
    }

    private void checkForUpdate() {
        if (busy) return;
        busy = true;
        checkButton.setEnabled(false);
        installButton.setEnabled(false);
        statusView.setText("正在检查更新…");
        detailView.setText("");

        final String source = settings.getUpdateSource();
        new Thread(new Runnable() {
            @Override
            public void run() {
                UpdateChecker.Release release = null;
                String error = null;
                try {
                    release = UpdateChecker.fetchLatest(source);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final UpdateChecker.Release result = release;
                final String failure = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        checkButton.setEnabled(true);
                        if (result == null) {
                            statusView.setText("检查失败：" + failure);
                            return;
                        }
                        latest = result;
                        detailView.setText("最新发行版：" + result.tag
                                + (result.name.isEmpty() ? "" : " · " + result.name));
                        if (UpdateChecker.isNewer(result.tag, versionName())) {
                            statusView.setText("发现新版本：" + result.tag);
                            installButton.setEnabled(result.apkAsset() != null);
                            if (result.apkAsset() == null) {
                                statusView.setText("发现新版本，但发行版里没有 APK 附件");
                            }
                            showReleaseNotes(result);
                        } else {
                            statusView.setText("已是最新版本");
                            installButton.setEnabled(false);
                        }
                    }
                });
            }
        }, "vrcdi-update-check").start();
    }

    private void showReleaseNotes(UpdateChecker.Release release) {
        if (release.body.trim().isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("更新内容 · " + release.tag)
                .setMessage(release.body)
                .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void downloadAndInstall() {
        if (busy || latest == null) return;
        final UpdateChecker.Asset asset = latest.apkAsset();
        if (asset == null) {
            toast("发行版里没有可下载的 APK");
            return;
        }

        busy = true;
        checkButton.setEnabled(false);
        installButton.setEnabled(false);
        statusView.setText("正在下载… 0%");

        new Thread(new Runnable() {
            @Override
            public void run() {
                File apk = null;
                String error = null;
                try {
                    apk = UpdateManager.download(UpdateSettingsActivity.this,
                            asset, new UpdateManager.Progress() {
                                @Override
                                public void onProgress(final int percent) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            statusView.setText("正在下载… " + percent + "%");
                                        }
                                    });
                                }
                            });
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }

                final File file = apk;
                final String failure = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        checkButton.setEnabled(true);
                        if (file == null) {
                            statusView.setText("下载失败：" + failure);
                            installButton.setEnabled(true);
                            return;
                        }
                        statusView.setText("下载完成，正在打开安装器…");
                        try {
                            UpdateManager.install(UpdateSettingsActivity.this, file);
                        } catch (Throwable t) {
                            statusView.setText("安装失败："
                                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                            installButton.setEnabled(true);
                        }
                    }
                });
            }
        }, "vrcdi-update-download").start();
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (Throwable t) {
            return "2.3.5 Omega";
        }
    }

    private int versionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
