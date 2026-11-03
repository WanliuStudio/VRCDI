package com.vrcdevicecheck.osc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.vrcdevicecheck.osc.theme.Theme;
import com.vrcdevicecheck.osc.theme.ThemeManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 主题页面：列出已安装主题、导入 zip/tar.gz、点击切换（切换后自动重启应用）。
 *
 * 主题包结构见 ThemeManager 的注释与代码仓库说明。
 */
public class ThemeSettingsActivity extends Activity {

    private static final int REQUEST_CODE_THEME = 61;

    private ArrayAdapter<String> adapter;
    private final List<Theme> themes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("主题");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        root.setPadding(padding, padding, padding, padding);

        TextView hint = new TextView(this);
        hint.setText("主题包支持 .zip 与 .tar.gz，里面需要有 theme.json 和图片。点击主题切换，切换后会自动重启应用。");
        hint.setTextSize(12f);
        root.addView(hint);

        final ListView list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button importButton = new Button(this);
        importButton.setText("导入主题（zip / tar.gz）");
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickThemeFile();
            }
        });
        root.addView(importButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        ThemeManager.apply(this);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                applyTheme(position);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(position);
                return true;
            }
        });

        refresh();
    }

    private void refresh() {
        themes.clear();
        themes.addAll(ThemeManager.installed(this));
        String currentId = ThemeManager.currentId(this);

        List<String> lines = new ArrayList<>();
        lines.add(currentId.isEmpty() ? "默认主题（当前）" : "默认主题");
        for (Theme theme : themes) {
            lines.add(theme.name + (theme.id.equals(currentId) ? "（当前）" : ""));
        }
        adapter.clear();
        adapter.addAll(lines);
        adapter.notifyDataSetChanged();
    }

    private void applyTheme(int position) {
        if (position == 0) {
            ThemeManager.setCurrent(this, "");
            ThemeManager.restart(this);
            return;
        }
        int index = position - 1;
        if (index < 0 || index >= themes.size()) return;
        ThemeManager.setCurrent(this, themes.get(index).id);
        ThemeManager.restart(this);
    }

    private void confirmDelete(final int position) {
        if (position <= 0) {
            toast("默认主题不能删除");
            return;
        }
        final int index = position - 1;
        if (index < 0 || index >= themes.size()) return;
        final Theme theme = themes.get(index);
        new AlertDialog.Builder(this)
                .setTitle("删除主题")
                .setMessage("确定删除主题「" + theme.name + "」吗？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ThemeManager.delete(ThemeSettingsActivity.this, theme.id);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickThemeFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed",
                    "application/gzip", "application/x-gzip",
                    "application/x-tar", "application/octet-stream"});
            startActivityForResult(Intent.createChooser(intent, "选择主题包"), REQUEST_CODE_THEME);
        } catch (Throwable t) {
            toast("没有找到可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_THEME) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        installTheme(data.getData());
    }

    private void installTheme(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Theme installed = null;
                String error = null;
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("无法读取文件");
                    installed = ThemeManager.install(ThemeSettingsActivity.this, in);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }

                final Theme result = installed;
                final String failure = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result == null) {
                            toast("导入失败：" + (failure == null ? "未知错误" : failure));
                            return;
                        }
                        toast("已导入主题：" + result.name);
                        refresh();
                    }
                });
            }
        }, "vrcdi-theme-import").start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
