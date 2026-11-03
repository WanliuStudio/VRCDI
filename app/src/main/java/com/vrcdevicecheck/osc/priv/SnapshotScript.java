package com.vrcdevicecheck.osc.priv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/** 读取并缓存 assets/snapshot.sh，避免每个采集周期都做一次 IO */
public final class SnapshotScript {

    private static final String ASSET_NAME = "snapshot.sh";
    private static volatile String cached;

    private SnapshotScript() {
    }

    public static String get(Context context) {
        String local = cached;
        if (local != null) return local;
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_NAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            local = sb.toString();
        } catch (Throwable t) {
            // 脚本读不到时返回一个只输出结束标记的空脚本，让上层走"仅免特权信息"的降级路径
            local = "@BEGIN\n@END\n";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        cached = local;
        return local;
    }
}
