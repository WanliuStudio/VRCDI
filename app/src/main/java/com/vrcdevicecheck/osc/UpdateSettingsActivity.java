package com.vrcdevicecheck.osc;

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
public class UpdateSettingsActivity extends BaseActivity {

    /** 从「发现新版本 → 去更新」进来时带上，自动检查并直接下载安装 */
    public static final String EXTRA_AUTO_UPDATE = "auto_update";

    private AppSettings settings;
    private TextView statusView;
    private TextView detailView;
    private Button checkButton;
    private Button installButton;
    private RadioGroup sourceGroup;

    private UpdateChecker.Release latest;
    private boolean busy;
    /** 已经下载好的 APK，等用户授予安装权限后直接安装，避免重新下载 */
    private File pendingApk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.update_title);
        settings = new AppSettings(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(14);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView current = new TextView(this);
        current.setTextSize(15f);
        current.setText(getString(R.string.update_current, versionName(), versionCode()));
        root.addView(current);

        CheckBox autoCheck = new CheckBox(this);
        autoCheck.setText(R.string.update_auto_check);
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

        TextView channelLabel = new TextView(this);
        channelLabel.setText(R.string.update_channel_label);
        channelLabel.setTextSize(14f);
        channelLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(channelLabel);

        RadioGroup channelGroup = new RadioGroup(this);
        channelGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton stableChannel = new RadioButton(this);
        stableChannel.setText(R.string.update_channel_stable);
        stableChannel.setId(2001);
        RadioButton betaChannel = new RadioButton(this);
        betaChannel.setText(R.string.update_channel_beta);
        betaChannel.setId(2002);
        channelGroup.addView(stableChannel);
        channelGroup.addView(betaChannel);
        channelGroup.check(settings.isBetaChannel() ? 2002 : 2001);
        channelGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                settings.setUpdateChannel(checkedId == 2002 ? "beta" : "stable");
            }
        });
        root.addView(channelGroup);

        TextView sourceLabel = new TextView(this);
        sourceLabel.setText(R.string.update_source_label);
        sourceLabel.setTextSize(14f);
        sourceLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(sourceLabel);

        sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton gitee = new RadioButton(this);
        gitee.setText(R.string.update_source_gitee);
        gitee.setId(1001);
        RadioButton github = new RadioButton(this);
        github.setText(R.string.update_source_github);
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
        statusView.setText(R.string.update_not_checked);
        root.addView(statusView);

        detailView = new TextView(this);
        detailView.setTextSize(12f);
        detailView.setPadding(0, 0, 0, dp(10));
        root.addView(detailView);

        checkButton = new Button(this);
        checkButton.setText(R.string.update_check_button);
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate();
            }
        });
        root.addView(checkButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        installButton = new Button(this);
        installButton.setText(R.string.update_install_button);
        installButton.setEnabled(false);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAndInstall();
            }
        });
        root.addView(installButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button joinButton = new Button(this);
        joinButton.setText(R.string.join_us);
        joinButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.vrcdevicecheck.osc.util.JoinUs.show(UpdateSettingsActivity.this);
            }
        });
        root.addView(joinButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setTextSize(12f);
        note.setPadding(0, dp(16), 0, 0);
        note.setText(R.string.update_note);
        root.addView(note);

        setContentView(scroll);
        ThemeManager.apply(this);

        // 从「去更新」直接进来：自动检查，发现新版本后直接下载安装
        if (getIntent().getBooleanExtra(EXTRA_AUTO_UPDATE, false)) {
            checkButton.post(new Runnable() {
                @Override
                public void run() {
                    checkForUpdate(true);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户从「允许安装未知应用」设置页返回：直接安装已下载的包，不再重新检查/下载
        if (pendingApk != null && UpdateManager.canInstall(this)) {
            installPending();
        }
    }

    private void checkForUpdate() {
        checkForUpdate(false);
    }

    private void checkForUpdate(final boolean autoDownload) {
        if (busy) return;
        busy = true;
        checkButton.setEnabled(false);
        installButton.setEnabled(false);
        statusView.setText(R.string.update_checking);
        detailView.setText("");

        final String source = settings.getUpdateSource();
        new Thread(new Runnable() {
            @Override
            public void run() {
                UpdateChecker.Release release = null;
                String error = null;
                try {
                    release = UpdateChecker.fetchLatest(
                            getApplicationContext(), source, settings.isBetaChannel());
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
                            statusView.setText(getString(R.string.update_check_failed, failure));
                            return;
                        }
                        latest = result;
                        String header = result.name.isEmpty()
                                ? getString(R.string.update_latest_release, result.tag)
                                : getString(R.string.update_latest_release_named,
                                        result.tag, result.name);
                        if (UpdateChecker.isNewer(result.tag, versionName())) {
                            statusView.setText(getString(R.string.update_found, result.tag));
                            installButton.setEnabled(result.apkAsset() != null);
                            if (result.apkAsset() == null) {
                                statusView.setText(R.string.update_found_no_apk);
                            }
                            showNotesInline(header, result);
                            if (autoDownload && result.apkAsset() != null) {
                                downloadAndInstall();
                            }
                        } else {
                            statusView.setText(R.string.update_is_latest);
                            installButton.setEnabled(false);
                            detailView.setText(header);
                        }
                    }
                });
            }
        }, "vrcdi-update-check").start();
    }

    /** 更新日志直接显示在页面里（Markdown 纯文本），不再弹窗 */
    private void showNotesInline(String header, UpdateChecker.Release release) {
        StringBuilder sb = new StringBuilder(header);
        String body = release.body == null ? "" : release.body.trim();
        if (!body.isEmpty()) {
            sb.append("\n\n").append(body);
        }
        if (needsSignatureMigration(release)) {
            sb.append("\n\n").append(getString(R.string.update_require_uninstall_note));
        }
        detailView.setText(sb.toString());
    }

    /**
     * 需要卸载重装的情况：当前安装包不是发布私钥签名。
     * 4.4.x 正式版起，Beta 与正式版统一用发布私钥签名；旧 debug 包（含 4.4.4 Beta 及更早）
     * 覆盖安装会签名冲突，必须先卸载。
     */
    private boolean needsSignatureMigration(UpdateChecker.Release release) {
        return release != null && !BuildConfig.RELEASE_SIGNED;
    }

    private void downloadAndInstall() {
        if (busy || latest == null) return;
        final UpdateChecker.Asset asset = latest.apkAsset();
        if (asset == null) {
            toast(getString(R.string.update_no_apk));
            return;
        }
        if (needsSignatureMigration(latest)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_migration_title)
                    .setMessage(R.string.update_migration_message)
                    .setPositiveButton(R.string.update_download, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startDownload(asset);
                        }
                    })
                    .setNegativeButton(R.string.common_cancel, null)
                    .show();
            return;
        }
        startDownload(asset);
    }

    private void startDownload(final UpdateChecker.Asset asset) {
        busy = true;
        checkButton.setEnabled(false);
        installButton.setEnabled(false);
        statusView.setText(getString(R.string.update_downloading, 0));

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
                                            statusView.setText(getString(
                                                    R.string.update_downloading, percent));
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
                            statusView.setText(getString(R.string.update_download_failed, failure));
                            installButton.setEnabled(true);
                            return;
                        }
                        statusView.setText(R.string.update_downloaded);
                        pendingApk = file;
                        attemptInstall();
                    }
                });
            }
        }, "vrcdi-update-download").start();
    }

    /** 权限已授予就立刻安装；否则引导去授权，回来后在 onResume 里继续安装 */
    private void attemptInstall() {
        if (pendingApk == null) return;
        if (!UpdateManager.canInstall(this)) {
            statusView.setText(R.string.update_install_permission_hint);
            UpdateManager.openInstallPermission(this);
            return;
        }
        installPending();
    }

    private void installPending() {
        final File apk = pendingApk;
        if (apk == null) return;
        pendingApk = null;
        try {
            UpdateManager.installNow(this, apk);
        } catch (Throwable t) {
            statusView.setText(getString(R.string.update_install_failed,
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            installButton.setEnabled(true);
        }
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (Throwable t) {
            return "5.3.0 Beta4";
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
