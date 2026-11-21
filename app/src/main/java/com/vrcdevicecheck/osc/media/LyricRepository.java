package com.vrcdevicecheck.osc.media;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按「歌名 + 歌手 + 时长」找同步歌词，并在本地按播放进度算当前行。
 *
 * 媒体会话本身不带歌词，所以只能去歌词源匹配：
 * 网易云 → QQ 音乐 → LRCLIB 依次尝试，任一成功即缓存。
 * 全部失败会记 10 分钟冷却，避免每个上报周期都去请求。
 *
 * 只在内存里缓存最近 12 首歌的歌词，不落盘、不上传任何播放数据之外的元数据。
 */
public final class LyricRepository {

    private static final int CACHE_MAX = 12;
    private static final long FAIL_RETRY_MS = 10L * 60L * 1000L;
    private static final long DURATION_TOLERANCE_MS = 15L * 1000L;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 4000;
    private static final int HTTP_READ_TIMEOUT_MS = 5000;
    private static final int HTTP_MAX_BYTES = 512 * 1024;

    private static final String UA = "Mozilla/5.0 (Linux; Android 10; VRCDI) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, List<LrcParser.Line>> CACHE =
            new LinkedHashMap<String, List<LrcParser.Line>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<LrcParser.Line>> eldest) {
                    return size() > CACHE_MAX;
                }
            };
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "vrcdi-lyrics");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private LyricRepository() {
    }

    /**
     * 当前歌词行。歌词还没准备好时返回 null，并在后台开始抓取；
     * 下一次轮询（服务每 2 秒一次）就能拿到。
     */
    public static String currentLine(NowPlayingRepository.Track track) {
        if (track == null || isBlank(track.title)) return null;
        String key = cacheKey(track);

        List<LrcParser.Line> cached;
        synchronized (LOCK) {
            cached = CACHE.get(key);
            if (cached == null) {
                if (IN_FLIGHT.contains(key)) return null;
                Long failedAt = FAILED.get(key);
                if (failedAt != null
                        && System.currentTimeMillis() - failedAt < FAIL_RETRY_MS) {
                    return null;
                }
                IN_FLIGHT.add(key);
            }
        }
        if (cached != null) return LrcParser.lineAt(cached, track.positionMs);

        try {
            EXECUTOR.execute(new FetchTask(track, key));
        } catch (Throwable t) {
            synchronized (LOCK) {
                IN_FLIGHT.remove(key);
            }
        }
        return null;
    }

    private static final class FetchTask implements Runnable {
        private final NowPlayingRepository.Track track;
        private final String key;

        FetchTask(NowPlayingRepository.Track track, String key) {
            this.track = track;
            this.key = key;
        }

        @Override
        public void run() {
            List<LrcParser.Line> lines = null;
            try {
                lines = fetch(track);
            } catch (Throwable ignored) {
                // 网络不可用、接口改版等，统一按没找到处理
            }
            synchronized (LOCK) {
                IN_FLIGHT.remove(key);
                if (lines != null && !lines.isEmpty()) {
                    CACHE.put(key, lines);
                    FAILED.remove(key);
                } else {
                    FAILED.put(key, System.currentTimeMillis());
                    trimFailed();
                }
            }
        }
    }

    private static List<LrcParser.Line> fetch(NowPlayingRepository.Track track) {
        List<LrcParser.Line> lines = fetchNetEase(track);
        if (lines == null || lines.isEmpty()) lines = fetchQq(track);
        if (lines == null || lines.isEmpty()) lines = fetchLrclib(track);
        return lines;
    }

    // ---------------- 网易云音乐 ----------------

    private static List<LrcParser.Line> fetchNetEase(NowPlayingRepository.Track track) {
        try {
            String query = track.title
                    + (isBlank(track.artist) ? "" : " " + track.artist);
            String search = httpGet("https://music.163.com/api/search/get/web"
                    + "?type=1&offset=0&total=true&limit=5&s=" + encode(query),
                    "https://music.163.com/");
            if (search == null) return null;

            JSONObject result = new JSONObject(search).optJSONObject("result");
            JSONArray songs = result == null ? null : result.optJSONArray("songs");
            if (songs == null) return null;

            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song == null) continue;
                String name = song.optString("name");
                String artists = joinArtists(song.optJSONArray("artists"));
                long duration = song.optLong("duration");
                if (!titleMatches(track.title, name)
                        || !artistMatches(track.artist, artists)
                        || !durationMatches(track.durationMs, duration)) {
                    continue;
                }
                long id = song.optLong("id");
                if (id <= 0) continue;

                String body = httpGet("https://music.163.com/api/song/lyric"
                        + "?id=" + id + "&lv=-1&kv=-1&tv=-1", "https://music.163.com/");
                if (body == null) continue;
                JSONObject lrc = new JSONObject(body).optJSONObject("lrc");
                if (lrc == null) continue;
                List<LrcParser.Line> lines = LrcParser.parse(lrc.optString("lyric"));
                if (!lines.isEmpty()) return lines;
            }
        } catch (Throwable ignored) {
            // 接口不可用时交给下一个源
        }
        return null;
    }

    private static String joinArtists(JSONArray artists) {
        if (artists == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = artist.optString("name");
            if (isBlank(name)) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(name);
        }
        return sb.toString();
    }

    // ---------------- QQ 音乐 ----------------

    private static List<LrcParser.Line> fetchQq(NowPlayingRepository.Track track) {
        try {
            String query = track.title
                    + (isBlank(track.artist) ? "" : " " + track.artist);
            String search = httpGet("https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                    + "?w=" + encode(query)
                    + "&format=json&n=5&p=1&cr=1&g_tk=5381&loginUin=0&hostUin=0"
                    + "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0",
                    "https://y.qq.com/");
            search = jsonObjectBody(search);
            if (search == null) return null;

            JSONObject root = new JSONObject(search);
            JSONObject data = root.optJSONObject("data");
            JSONObject songContainer = data == null ? null : data.optJSONObject("song");
            JSONArray songs = songContainer == null ? null : songContainer.optJSONArray("list");
            if (songs == null) return null;

            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song == null) continue;
                String name = song.optString("songname");
                String artists = joinQqSingers(song.optJSONArray("singer"));
                long duration = song.optLong("interval") * 1000L;
                if (!titleMatches(track.title, name)
                        || !artistMatches(track.artist, artists)
                        || !durationMatches(track.durationMs, duration)) {
                    continue;
                }
                String mid = song.optString("songmid");
                if (isBlank(mid)) continue;

                String body = httpGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                        + "?songmid=" + encode(mid) + "&format=json&nobase64=1&g_tk=5381",
                        "https://y.qq.com/portal/player.html");
                body = jsonObjectBody(body);
                if (body == null) continue;
                String lyric = new JSONObject(body).optString("lyric");
                List<LrcParser.Line> lines = LrcParser.parse(unescapeHtml(lyric));
                if (!lines.isEmpty()) return lines;
            }
        } catch (Throwable ignored) {
            // 接口不可用时交给下一个源
        }
        return null;
    }

    private static String joinQqSingers(JSONArray singers) {
        if (singers == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < singers.length(); i++) {
            JSONObject singer = singers.optJSONObject(i);
            if (singer == null) continue;
            String name = singer.optString("name");
            if (isBlank(name)) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(name);
        }
        return sb.toString();
    }

    // ---------------- LRCLIB ----------------

    private static List<LrcParser.Line> fetchLrclib(NowPlayingRepository.Track track) {
        try {
            String url = "https://lrclib.net/api/search?track_name=" + encode(track.title);
            if (!isBlank(track.artist)) {
                url += "&artist_name=" + encode(track.artist);
            }
            String body = httpGet(url, "https://lrclib.net/");
            if (body == null) return null;

            JSONArray results = new JSONArray(body);
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("trackName");
                String artists = item.optString("artistName");
                long duration = (long) (item.optDouble("duration", 0) * 1000);
                if (!titleMatches(track.title, name)
                        || !artistMatches(track.artist, artists)
                        || !durationMatches(track.durationMs, duration)) {
                    continue;
                }
                List<LrcParser.Line> lines = LrcParser.parse(item.optString("syncedLyrics"));
                if (!lines.isEmpty()) return lines;
            }
        } catch (Throwable ignored) {
            // 没有结果或服务不可用
        }
        return null;
    }

    // ---------------- 匹配与工具 ----------------

    private static String cacheKey(NowPlayingRepository.Track track) {
        return normalize(track.title) + "|" + normalize(track.artist) + "|"
                + (track.durationMs > 0 ? track.durationMs / 1000 : 0);
    }

    /**
     * 歌名比对：去掉括号里的版本说明（Live / 现场版 / Remaster）后，
     * 完全相同，或一方包含另一方且长度足够，才算匹配。
     */
    static boolean titleMatches(String media, String candidate) {
        String a = normalize(media);
        String b = normalize(candidate);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        int min = Math.min(a.length(), b.length());
        return min >= 4 && (a.contains(b) || b.contains(a));
    }

    /**
     * 歌手比对：一方包含另一方即可（「周杰伦」对「周杰伦/Jay Chou」），
     * 媒体元数据没有歌手时不做限制。
     */
    static boolean artistMatches(String media, String candidate) {
        if (isBlank(media)) return true;
        String a = normalize(media);
        String b = normalize(candidate);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;

        // 「A feat. B」「A / B」这类写法，拆开逐段再比一次
        String[] parts = media.split("/|、|,|&|;|\\bfeat\\.?\\b|\\bft\\.?\\b",
                -1);
        for (String part : parts) {
            String normalized = normalize(part);
            if (normalized.isEmpty()) continue;
            if (b.contains(normalized) || normalized.contains(b)) return true;
        }
        return false;
    }

    static boolean durationMatches(long mediaMs, long candidateMs) {
        if (mediaMs <= 0 || candidateMs <= 0) return true;
        return Math.abs(mediaMs - candidateMs) <= DURATION_TOLERANCE_MS;
    }

    /** 小写、去掉括号注释、只保留字母和数字（汉字算字母），去掉所有标点空格 */
    static String normalize(String value) {
        if (value == null) return "";
        String text = value.toLowerCase(Locale.ROOT);
        text = text.replaceAll("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*\\]|【[^】]*】", "");
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static String httpGet(String url, String referer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            if (referer != null) connection.setRequestProperty("Referer", referer);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) > 0) {
                total += read;
                if (total > HTTP_MAX_BYTES) break;
                output.write(buffer, 0, read);
            }
            input.close();
            return new String(output.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** QQ 接口有时包一层 JSONP，去掉回调外壳只留 JSON 对象 */
    private static String jsonObjectBody(String body) {
        if (body == null) return null;
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return body.substring(start, end + 1);
    }

    private static String unescapeHtml(String text) {
        if (text == null) return null;
        String value = text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
        Matcher matcher = Pattern.compile("&#(x?[0-9a-fA-F]+);").matcher(value);
        StringBuffer sb = new StringBuffer(value.length());
        while (matcher.find()) {
            String group = matcher.group(1);
            try {
                int codePoint = (group.startsWith("x") || group.startsWith("X"))
                        ? Integer.parseInt(group.substring(1), 16)
                        : Integer.parseInt(group);
                matcher.appendReplacement(sb,
                        Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
            } catch (Throwable t) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 失败缓存超过 50 条时清掉最早写入的一批，避免长跑后无限增长 */
    private static void trimFailed() {
        while (FAILED.size() > 50) {
            Iterator<Map.Entry<String, Long>> iterator = FAILED.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}