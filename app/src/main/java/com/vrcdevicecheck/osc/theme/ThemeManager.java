package com.vrcdevicecheck.osc.theme;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.vrcdevicecheck.osc.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 主题的安装、切换与运行时应用。
 *
 * theme.json 格式（所有字段都可省略，省略就沿用应用默认值）：
 * {
 *   "name": "My Theme",
 *   "colors": {
 *     "background": "#0F1216",
 *     "card": "#1A1F24",
 *     "text": "#E8EAED",
 *     "textSecondary": "#A0A8B0"
 *   },
 *   "images": {
 *     "background": "images/background.png",
 *     "button": "images/button.png",
 *     "buttonPressed": "images/button_pressed.png"
 *   }
 * }
 *
 * 安装后的主题放在 files/themes/<id>/ 下，当前主题 id 记在 SharedPreferences。
 */
public final class ThemeManager {

    private static final String PREFS = "vrcdi_theme";
    private static final String KEY_CURRENT = "current_id";
    private static final String THEMES_DIR = "themes";
    private static final String THEME_JSON = "theme.json";

    private ThemeManager() {
    }

    public static File themesDir(Context context) {
        File dir = new File(context.getFilesDir(), THEMES_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** 当前主题；返回 null 表示使用应用默认外观 */
    public static Theme current(Context context) {
        String id = currentId(context);
        if (id.isEmpty()) return null;
        return Theme.load(new File(themesDir(context), id), id);
    }

    public static String currentId(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_CURRENT, "");
        return id == null ? "" : id;
    }

    public static void setCurrent(Context context, String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CURRENT, id == null ? "" : id).apply();
    }

    public static List<Theme> installed(Context context) {
        List<Theme> result = new ArrayList<>();
        File[] dirs = themesDir(context).listFiles();
        if (dirs == null) return result;
        Arrays.sort(dirs, BY_NAME);
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            Theme theme = Theme.load(dir, dir.getName());
            if (theme != null) result.add(theme);
        }
        return result;
    }

    /**
     * 安装一个 zip / tar.gz 主题包。
     * 解压到缓存目录 -> 找到 theme.json -> 以主题名作为目录名保存到 files/themes/<id>。
     */
    public static Theme install(Context context, InputStream input) throws IOException {
        File temp = new File(context.getCacheDir(), "theme_import_" + System.currentTimeMillis());
        deleteRecursive(temp);
        if (!temp.mkdirs()) throw new IOException("无法创建临时目录");
        try {
            ArchiveExtractor.extract(input, temp);
            File root = findThemeRoot(temp);
            if (root == null) throw new IOException("压缩包里没有 theme.json");
            Theme parsed = Theme.load(root, "temp");
            if (parsed == null) throw new IOException("theme.json 解析失败");

            String id = sanitize(parsed.name);
            if (id.isEmpty()) id = "theme_" + System.currentTimeMillis();

            File dest = new File(themesDir(context), id);
            deleteRecursive(dest);
            if (!root.renameTo(dest)) {
                copyRecursive(root, dest);
            }
            Theme saved = Theme.load(dest, id);
            if (saved == null) throw new IOException("主题保存失败");
            return saved;
        } finally {
            deleteRecursive(temp);
        }
    }

    public static void delete(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        deleteRecursive(new File(themesDir(context), id));
        if (id.equals(currentId(context))) setCurrent(context, "");
    }

    /** 换主题后重启应用，让所有界面重新按新主题绘制 */
    public static void restart(Activity activity) {
        try {
            Intent intent = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            if (intent == null) return;
            // CLEAR_TOP 会把主界面之上的设置页面全部关掉并重建主界面，主题因此重新生效
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            activity.finish();
        } catch (Throwable ignored) {
            // 重启失败就留在当前页面，不影响应用继续使用
        }
    }

    // ---------------- 运行时应用 ----------------

    /** 把当前主题应用到已经 setContentView 的 Activity 上 */
    public static void apply(Activity activity) {
        Theme theme = current(activity);
        if (theme == null) return;
        View root = activity.getWindow().getDecorView();

        Drawable background = theme.drawable(activity, theme.backgroundImage);
        if (background != null) {
            root.setBackground(background);
        } else if (theme.background != 0) {
            root.setBackgroundColor(theme.background);
        }

        applyCards(activity, root, theme);
        applyButtons(activity, root, theme);
        applyTextColors(activity, root, theme);
    }

    private static void applyCards(Activity activity, View root, Theme theme) {
        if (theme.card == 0) return;
        List<View> cards = new ArrayList<>();
        collectTagged(root, "card", cards);
        float radius = dp(activity, 10);
        for (View card : cards) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(theme.card);
            drawable.setCornerRadius(radius);
            card.setBackground(drawable);
        }
    }

    private static void applyButtons(Activity activity, View root, Theme theme) {
        Drawable normal = theme.drawable(activity, theme.buttonImage);
        if (normal == null) return;
        Drawable pressed = theme.drawable(activity, theme.buttonPressedImage);
        List<View> buttons = new ArrayList<>();
        collectViews(root, Button.class, buttons);
        for (View view : buttons) {
            if (pressed != null) {
                StateListDrawable states = new StateListDrawable();
                states.addState(new int[]{android.R.attr.state_pressed}, pressed);
                states.addState(new int[]{}, normal);
                view.setBackground(states);
            } else {
                view.setBackground(normal);
            }
        }
    }

    private static void applyTextColors(Activity activity, View root, Theme theme) {
        if (theme.text == 0 && theme.textSecondary == 0) return;
        int warn = activity.getColor(R.color.warn);
        int error = activity.getColor(R.color.err);
        List<View> textViews = new ArrayList<>();
        collectViews(root, TextView.class, textViews);
        for (View view : textViews) {
            TextView textView = (TextView) view;
            int current = textView.getCurrentTextColor();
            // 状态色（警告/错误）保留，避免主题把语义色盖掉
            if (current == warn || current == error) continue;
            if (theme.text != 0) textView.setTextColor(theme.text);
        }
    }

    // ---------------- 工具 ----------------

    private static void collectTagged(View root, String tag, List<View> out) {
        Object value = root.getTag();
        if (value instanceof String && tag.equals(value)) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTagged(group.getChildAt(i), tag, out);
            }
        }
    }

    private static void collectViews(View root, Class<?> type, List<View> out) {
        if (type.isInstance(root)) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectViews(group.getChildAt(i), type, out);
            }
        }
    }

    private static float dp(Context context, float value) {
        Resources resources = context.getResources();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                resources.getDisplayMetrics());
    }

    private static File findThemeRoot(File dir) {
        if (new File(dir, THEME_JSON).isFile()) return dir;
        File[] children = dir.listFiles();
        if (children == null) return null;
        File found = null;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            if (!new File(child, THEME_JSON).isFile()) continue;
            if (found != null) return null; // 多个候选，无法判断，按失败处理
            found = child;
        }
        return found;
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 40; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
            else if (c == ' ') sb.append('_');
        }
        return sb.toString();
    }

    private static void copyRecursive(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("无法创建目录");
            File[] children = from.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(to, child.getName()));
                }
            }
            return;
        }
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                out.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private static final Comparator<File> BY_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };
}
