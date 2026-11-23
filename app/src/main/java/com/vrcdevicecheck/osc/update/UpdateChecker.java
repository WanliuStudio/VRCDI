package com.vrcdevicecheck.osc.update;

import android.content.Context;

import com.vrcdevicecheck.osc.R;
import com.vrcdevicecheck.osc.util.LocaleHelper;

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
            "https://api.github.com/repos/WanliuStudio/VRCDI/releases";
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/WanliuStudio/VRCDI/releases";

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
        /** 是否勾了「pre-release」 */
        public final boolean prerelease;

        Release(String tag, String name, String body, String pageUrl, List<Asset> assets,
                boolean prerelease) {
            this.tag = tag == null ? "" : tag;
            this.name = name == null ? "" : name;
            this.body = body == null ? "" : body;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.assets = assets;
            this.prerelease = prerelease;
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

    public static Release fetchLatest(Context context, String source) throws Exception {
        return fetchLatest(context, source, false);
    }

    /**
     * @param includePrerelease true 表示「测试版」频道（含预发布）；
     *                          false 表示「正式版」频道（只接受正式发行版）。
     *
     * 注意：Gitee 的 /latest 和列表顺序都不可靠（列表可能按创建时间升序，最先返回的是
     * 最早的 0.1.1 Alpha），所以 Gitee 源统一取「列表里版本号最大的那个」。
     */
    public static Release fetchLatest(Context context, String source,
                                      boolean includePrerelease) throws Exception {
        boolean gitee = SOURCE_GITEE.equals(source);
        String base = gitee ? GITEE_API : GITHUB_API;

        if (gitee || includePrerelease) {
            Release best = fetchBestFromList(context, base, !includePrerelease);
            if (best != null) return best;
            throw new Exception(LocaleHelper.str(context, R.string.update_err_no_release,
                    includePrerelease ? "pre-release" : "stable"));
        }

        try {
            // GitHub 正式版：/latest 本身就会跳过 pre-release
            return parse(httpGet(context, base + "/latest"));
        } catch (Exception latestError) {
            Release best = fetchBestFromList(context, base, true);
            if (best != null) return best;
            throw new Exception(LocaleHelper.str(context, R.string.update_err_no_release,
                    latestError.getMessage()));
        }
    }

    /**
     * GET /releases?per_page=50，取版本号最大的非草稿发行版；失败返回 null。
     * 不依赖接口返回顺序——Gitee 可能是升序，GitHub 是降序。
     *
     * @param skipPrerelease true 时跳过 pre-release（正式版频道）
     */
    private static Release fetchBestFromList(Context context, String base, boolean skipPrerelease) {
        try {
            String json = httpGet(context, base + "?per_page=50");
            JSONArray array = new JSONArray(json);
            Release best = null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                if (obj.optBoolean("draft", false)) continue;
                if (skipPrerelease && obj.optBoolean("prerelease", false)) continue;
                Release candidate = parseObject(obj);
                if (best == null || isNewer(candidate.tag, best.tag)) {
                    best = candidate;
                }
            }
            return best;
        } catch (Throwable ignored) {
            // 列表也拿不到就交给上层报错
        }
        return null;
    }

    static Release parse(String json) throws Exception {
        return parseObject(new JSONObject(json));
    }

    private static Release parseObject(JSONObject root) {
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
                list,
                root.optBoolean("prerelease", false));
    }

    private static String httpGet(Context context, String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception(LocaleHelper.str(context, R.string.update_err_http, code));
            }
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

    /** 版本号主版本：4.4.2 Beta -> 4；解析失败返回 -1 */
    public static int majorVersion(String version) {
        if (version == null) return -1;
        String text = version.trim();
        int i = 0;
        while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        if (i == 0) return -1;
        try {
            return Integer.parseInt(text.substring(0, i));
        } catch (Throwable t) {
            return -1;
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
