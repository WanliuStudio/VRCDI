package com.vrcdevicecheck.osc.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * 一个已安装的主题。
 *
 * 目录结构（解压后必须能找到 theme.json）：
 *   theme.json
 *   images/background.png
 *   images/button.png
 *   images/button_pressed.png
 *
 * theme.json 见 ThemeManager 的注释。
 */
public final class Theme {

    public final String id;
    public final String name;
    public final File dir;

    /** 0 表示 theme.json 里没写，沿用应用默认配色 */
    public final int background;
    public final int card;
    public final int text;
    public final int textSecondary;

    /** 相对于主题目录的图片路径，没有则为 null */
    public final String backgroundImage;
    public final String buttonImage;
    public final String buttonPressedImage;

    private Theme(String id, String name, File dir,
                  int background, int card, int text, int textSecondary,
                  String backgroundImage, String buttonImage, String buttonPressedImage) {
        this.id = id;
        this.name = name;
        this.dir = dir;
        this.background = background;
        this.card = card;
        this.text = text;
        this.textSecondary = textSecondary;
        this.backgroundImage = backgroundImage;
        this.buttonImage = buttonImage;
        this.buttonPressedImage = buttonPressedImage;
    }

    static Theme load(File dir, String id) {
        if (dir == null || !dir.isDirectory()) return null;
        File jsonFile = new File(dir, "theme.json");
        if (!jsonFile.isFile()) return null;
        try {
            JSONObject root = new JSONObject(readText(jsonFile));
            String name = root.optString("name", "").trim();
            if (name.isEmpty()) name = id;

            JSONObject colors = root.optJSONObject("colors");
            JSONObject images = root.optJSONObject("images");

            return new Theme(
                    id,
                    name,
                    dir,
                    color(colors, "background"),
                    color(colors, "card"),
                    color(colors, "text"),
                    color(colors, "textSecondary"),
                    image(images, "background"),
                    image(images, "button"),
                    image(images, "buttonPressed"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static int color(JSONObject colors, String key) {
        if (colors == null) return 0;
        String value = colors.optString(key, "").trim();
        if (value.isEmpty()) return 0;
        try {
            return Color.parseColor(value);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String image(JSONObject images, String key) {
        if (images == null) return null;
        String value = images.optString(key, "").trim().replace('\\', '/');
        return value.isEmpty() ? null : value;
    }

    /** 取一张主题图片，路径不合法/解码失败都返回 null */
    public Drawable drawable(Context context, String relativePath) {
        File file = imageFile(relativePath);
        if (file == null) return null;
        Bitmap bitmap = decode(file);
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    private File imageFile(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) return null;
        try {
            File file = new File(dir, relativePath.trim());
            String base = dir.getCanonicalPath();
            if (!file.getCanonicalPath().startsWith(base + File.separator)) return null;
            return file.isFile() ? file : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decode(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(File file) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            try {
                reader.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
