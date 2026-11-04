package com.vrcdevicecheck.osc.update;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 检查更新：读 GitHub / Gitee 的「最新发行版」接口，得到 tag、说明和附件列表。
 *
 * 两家接口结构几乎一样：
 *   GitHub  https://api.github.com/repos/WanliuStudio/VRCDI/releases/latest
 *   Gitee   https://gitee.com/api/v5/repos/WanliuStudio/VRCDI/releases/latest
 * 都返回 { tag_name, name, body, html_url, assets: [{ name, browser_download_url }] }。
 */
public final class UpdateChecker {

    public static final String SOURCE_GITHUB = "github";
    public static final String SOURCE_GITEE = "gitee";

    /** 自报家门的 UA，避免被当成脚本请求 */
    public static final String USER_AGENT =
            "VRCDI-Updater/2.0 (Android; +https://github.com/WanliuStudio/VRCDI)";

    private static final String GITHUB_API =
            "https://api.github.com/repos/WanliuStudio/VRCDI/releases/latest";
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/WanliuStudio/VRCDI/releases/latest";

    private UpdateChecker() {
    }

    public static final class Asset {
        public final String name;
        public final String url;

        Asset(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    public static final class Release {
        public final String tag;
        public final String name;
        public final String body;
        public final String pageUrl;
        public final List<Asset> assets;

        Release(String tag, String name, String body, String pageUrl, List<Asset> assets) {
            this.tag = tag == null ? "" : tag;
            this.name = name == null ? "" : name;
            this.body = body == null ? "" : body;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.assets = assets;
        }

        /** 优先选 .apk，其次 .apk.zip / .zip；都没有返回 null */
        public Asset apkAsset() {
            Asset zip = null;
            for (Asset asset : assets) {
                String lower = asset.name.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".apk")) return asset;
                if (zip == null && (lower.endsWith(".apk.zip") || lower.endsWith(".zip"))) zip = asset;
            }
            return zip;
        }
    }

    public static Release fetchLatest(String source) throws Exception {
        String url = SOURCE_GITEE.equals(source) ? GITEE_API : GITHUB_API;
        String json = httpGet(url);
        return parse(json);
    }

    static Release parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        List<Asset> list = new ArrayList<>();
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject obj = assets.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "");
                String url = obj.optString("browser_download_url", obj.optString("download_url", ""));
                if (!name.isEmpty() && !url.isEmpty()) list.add(new Asset(name, url));
            }
        }
        return new Release(
                root.optString("tag_name", ""),
                root.optString("name", ""),
                root.optString("body", ""),
                root.optString("html_url", ""),
                list);
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("服务器返回 HTTP " + code);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            } finally {
                reader.close();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** tag 里的版本号比当前版本大，才算有更新 */
    public static boolean isNewer(String tag, String currentVersion) {
        int[] remote = numbers(tag);
        int[] local = numbers(currentVersion);
        if (remote == null || local == null) return false;
        int count = Math.max(remote.length, local.length);
        for (int i = 0; i < count; i++) {
            int a = i < remote.length ? remote[i] : 0;
            int b = i < local.length ? local[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    /** 从 "2.0.0-Omega" / "v2.0.0" / "2.0.0 Omega" 里取出 [2,0,0] */
    private static int[] numbers(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").matcher(text);
        if (!matcher.find()) return null;
        int major = Integer.parseInt(matcher.group(1));
        int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new int[]{major, minor, patch};
    }
}
