package com.vrcdevicecheck.osc.util;

import android.app.Activity;
import android.app.AlertDialog;

import com.vrcdevicecheck.osc.R;

/**
 * 「加入我们」弹窗：Discord 服务器与 QQ 群。
 *
 * 中文界面显示 Discord + QQ 群；其他语言只显示 Discord（国外基本不用 QQ）。
 */
public final class JoinUs {

    public static final String DISCORD = "https://discord.gg/ayjFK4KwC";
    public static final String QQ_GROUP = "1134428559";

    private JoinUs() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String language = LocaleHelper.getLanguage(activity);
        String message = "zh".equals(language)
                ? activity.getString(R.string.join_us_body, DISCORD, QQ_GROUP)
                : activity.getString(R.string.join_us_body_no_qq, DISCORD);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.join_us_title)
                .setMessage(message)
                .setPositiveButton(R.string.join_us_ok, null)
                .show();
    }
}
