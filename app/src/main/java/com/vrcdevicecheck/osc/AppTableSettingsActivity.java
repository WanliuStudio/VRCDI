package com.vrcdevicecheck.osc;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vrcdevicecheck.osc.device.AppNames;
import com.vrcdevicecheck.osc.theme.ThemeManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 应用表设置：把包名映射成应用名，用于「前台应用」的显示。
 *
 * JSON 格式与设备表不同，是"包名 -> 名字"的映射：
 * {
 *   "apps": {
 *     "com.tencent.mm": "微信",
 *     "com.tencent.mobileqq": "QQ"
 *   }
 * }
 * 也可以直接省略 "apps" 这一层。导入后写入 files/app_names.json，覆盖内置同名条目。
 */
public class AppTableSettingsActivity extends Activity {

    private static final int REQUEST_CODE_APP_TABLE = 52;

    private TextView countView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("应用表设置");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(14);
        root.setPadding(padding, padding, padding, padding);

        TextView hint = new TextView(this);
        hint.setTextSize(13f);
        hint.setText("应用表把包名映射成应用名，例如 com.tencent.mm = 微信。\n"
                + "导入后写入应用私有目录，覆盖内置同名条目；没有收录的应用显示包名。");
        root.addView(hint);

        countView = new TextView(this);
        countView.setTextSize(15f);
        countView.setPadding(0, dp(14), 0, dp(14));
        root.addView(countView);

        Button importButton = new Button(this);
        importButton.setText("导入应用表（JSON）");
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        root.addView(importButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView format = new TextView(this);
        format.setTextSize(12f);
        format.setPadding(0, dp(14), 0, 0);
        format.setText("格式示例：\n{\n  \"apps\": {\n"
                + "    \"com.tencent.mm\": \"微信\",\n"
                + "    \"com.vrchat.mobile.playstore\": \"VRChat\"\n"
                + "  }\n}");
        root.addView(format);

        setContentView(root);
        ThemeManager.apply(this);
        refresh();
    }

    private void refresh() {
        countView.setText("当前已收录 " + AppNames.count(this) + " 条应用名（内置 + 导入）");
    }

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "text/plain", "text/*", "application/octet-stream"});
            startActivityForResult(Intent.createChooser(intent, "选择应用表 JSON 文件"),
                    REQUEST_CODE_APP_TABLE);
        } catch (Throwable t) {
            toast("没有找到可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_APP_TABLE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importTable(data.getData());
    }

    private void importTable(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                try {
                    text = readText(uri);
                } catch (Throwable ignored) {
                    // 读不到按失败处理
                }
                final int count = text == null ? -1 : AppNames.importJson(AppTableSettingsActivity.this, text);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (count < 0) {
                            toast("导入失败：不是有效的应用表 JSON");
                            return;
                        }
                        toast("已导入 " + count + " 条应用名");
                        refresh();
                    }
                });
            }
        }, "vrcdi-app-table").start();
    }

    private String readText(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private String displayName(Uri uri) {
        try {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0 && cursor.moveToFirst()) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isEmpty()) return name;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "已选择文件";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
