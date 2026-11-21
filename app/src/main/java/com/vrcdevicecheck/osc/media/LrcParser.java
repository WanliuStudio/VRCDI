package com.vrcdevicecheck.osc.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析（纯 Java，可单测）。
 *
 * 支持：
 * - 一行多个时间标签：[00:12.34][01:20.00]歌词
 * - 百分秒 / 毫秒：[00:12.3] / [00:12.34] / [00:12.345]
 * - 全局偏移：[offset:+500] / [offset:-200]
 * - 逐字时间标签：<00:12.34>字<00:12.56>幕，解析时去掉
 *
 * 作词 / 作曲 / 编曲等制作人员行在开头很常见，统一丢弃，
 * 否则副歌还没开始，Chatbox 会先显示「作词 : 方文山」。
 */
public final class LrcParser {

    public static final class Line {
        public final long timeMs;
        public final String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET_TAG =
            Pattern.compile("\\[offset\\s*:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TAG =
            Pattern.compile("<\\d{1,4}:\\d{1,2}(?:[.:]\\d{1,3})?>");
    private static final Pattern CREDIT_TAG =
            Pattern.compile("^(作词|作曲|编曲|制作人?|词|曲|Lyricist|Lyrics?|Composer|Arranger)"
                    + "\\s*[:：].*", Pattern.CASE_INSENSITIVE);

    private LrcParser() {
    }

    public static List<Line> parse(String lrc) {
        List<Line> result = new ArrayList<>();
        if (lrc == null || lrc.trim().isEmpty()) return result;

        long offsetMs = 0;
        String[] rawLines = lrc.split("\\r?\\n");
        for (String raw : rawLines) {
            if (raw == null) continue;
            Matcher offsetMatcher = OFFSET_TAG.matcher(raw);
            if (offsetMatcher.find()) {
                try {
                    offsetMs = Long.parseLong(offsetMatcher.group(1));
                } catch (NumberFormatException ignored) {
                    // 偏移量写坏了就当没有
                }
            }

            Matcher matcher = TIME_TAG.matcher(raw);
            List<Long> times = new ArrayList<>(2);
            int lastEnd = 0;
            while (matcher.find()) {
                long minute = Long.parseLong(matcher.group(1));
                long second = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long millis = 0;
                if (fraction != null) {
                    if (fraction.length() == 1) millis = Long.parseLong(fraction) * 100L;
                    else if (fraction.length() == 2) millis = Long.parseLong(fraction) * 10L;
                    else millis = Long.parseLong(fraction);
                }
                times.add(minute * 60000L + second * 1000L + millis);
                lastEnd = matcher.end();
            }
            if (times.isEmpty()) continue;

            String text = raw.substring(lastEnd).trim();
            if (text.isEmpty()) continue;
            text = WORD_TAG.matcher(text).replaceAll("").trim();
            if (text.isEmpty()) continue;
            if (CREDIT_TAG.matcher(text).matches()) continue;

            for (long time : times) {
                result.add(new Line(Math.max(0L, time + offsetMs), text));
            }
        }

        Collections.sort(result, new Comparator<Line>() {
            @Override
            public int compare(Line left, Line right) {
                if (left.timeMs < right.timeMs) return -1;
                if (left.timeMs > right.timeMs) return 1;
                return 0;
            }
        });
        return result;
    }

    /** 返回 position 时刻正在唱的那一句；还没到第一句 / 已经唱完时返回 null */
    public static String lineAt(List<Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return null;
        String current = null;
        for (Line line : lines) {
            if (line.timeMs > positionMs) break;
            current = line.text;
        }
        return current;
    }
}