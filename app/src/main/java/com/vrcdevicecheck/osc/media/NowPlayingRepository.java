package com.vrcdevicecheck.osc.media;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import java.util.List;

/**
 * 读取系统里「正在播放」的媒体会话。
 *
 * 网易云音乐、QQ 音乐、Apple Music、YouTube Music、Spotify 在 Android 上
 * 都会通过 MediaSession 向系统暴露歌名 / 歌手 / 专辑 / 播放进度，
 * 所以不需要逐个应用适配。
 *
 * 前提：VRCDI 必须拿到「通知使用权」——这是系统允许第三方读取媒体会话的
 * 唯一公开途径，由用户手动开启。微信、短信等通知内容不会被读取。
 */
public final class NowPlayingRepository {

    /** 一次读取到的播放信息，位置信息同时带上读取时刻，便于随后推算 */
    public static final class Track {
        public final String packageName;
        public final String title;
        public final String artist;
        public final String album;
        public final long positionMs;
        public final long durationMs;

        Track(String packageName, String title, String artist, String album,
              long positionMs, long durationMs) {
            this.packageName = packageName;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    private NowPlayingRepository() {
    }

    private static ComponentName listener(Context context) {
        return new ComponentName(context.getApplicationContext(),
                MediaNotificationListener.class);
    }

    /** 是否已拿到通知使用权（读取媒体会话的前提） */
    public static boolean hasAccess(Context context) {
        if (context == null) return false;
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null
                    && manager.isNotificationListenerAccessGranted(listener(context));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 取当前正在播放的会话；没有播放中的音乐返回 null。
     * 多个应用同时播放时取系统优先级最高的那个（最近活动的在前）。
     */
    public static Track read(Context context) {
        if (context == null || !hasAccess(context)) return null;
        try {
            MediaSessionManager manager = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return null;
            List<MediaController> sessions = manager.getActiveSessions(listener(context));
            if (sessions == null) return null;
            String self = context.getPackageName();

            for (MediaController controller : sessions) {
                if (controller == null || self.equals(controller.getPackageName())) continue;

                MediaMetadata metadata = controller.getMetadata();
                if (metadata == null) continue;

                String title = text(metadata, MediaMetadata.METADATA_KEY_TITLE);
                if (title.isEmpty()) {
                    title = text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                }
                if (title.isEmpty()) continue;

                PlaybackState state = controller.getPlaybackState();
                if (state == null || state.getState() != PlaybackState.STATE_PLAYING) continue;

                float speed = state.getPlaybackSpeed();
                if (speed <= 0f) continue;

                String artist = text(metadata, MediaMetadata.METADATA_KEY_ARTIST);
                if (artist.isEmpty()) {
                    artist = text(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                }
                String album = text(metadata, MediaMetadata.METADATA_KEY_ALBUM);
                long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

                long position = state.getPosition();
                if (position < 0) position = 0;
                long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
                if (elapsed > 0) position += (long) (elapsed * speed);
                if (duration > 0 && position > duration) position = duration;

                return new Track(controller.getPackageName(), title, artist, album,
                        position, duration);
            }
        } catch (Throwable ignored) {
            // 通知使用权被撤销、系统服务异常等，按"没有在播放"处理
        }
        return null;
    }

    /**
     * 给上报文本用的快照：歌名 / 歌手 / 专辑 + 当前歌词行。
     * 歌词第一次需要时会在后台拉取，本次取不到就只显示歌曲信息。
     */
    public static MusicInfo snapshot(Context context) {
        Track track = read(context);
        if (track == null) return null;
        String lyric = LyricRepository.currentLine(track);
        return new MusicInfo(track.title, track.artist, track.album, lyric,
                track.positionMs, track.durationMs);
    }

    private static String text(MediaMetadata metadata, String key) {
        try {
            CharSequence value = metadata.getText(key);
            if (value == null) return "";
            // 元数据里偶尔带换行/制表符，会破坏 Chatbox 的分行，统一压成空格
            return value.toString().replace('\n', ' ').replace('\r', ' ')
                    .replace('\t', ' ').trim();
        } catch (Throwable t) {
            return "";
        }
    }
}