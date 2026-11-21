package com.vrcdevicecheck.osc.media;

/**
 * 上报文本里用到的音乐信息快照。纯数据，方便 {@code ReportBuilder} 单测。
 *
 * 字段都可能为 null：不同音乐应用给出的元数据完整度不一样
 * （YouTube Music 经常没有专辑，网易云一般三样都有）。
 */
public final class MusicInfo {

    public final String title;
    public final String artist;
    public final String album;
    /** 当前播放到的那一行歌词；没有歌词、还没取到或匹配失败时为 null */
    public final String lyricLine;
    /** 读取时的播放位置与总时长（毫秒）；拿不到时长时为 0 */
    public final long positionMs;
    public final long durationMs;

    public MusicInfo(String title, String artist, String album, String lyricLine) {
        this(title, artist, album, lyricLine, 0L, 0L);
    }

    public MusicInfo(String title, String artist, String album, String lyricLine,
                     long positionMs, long durationMs) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.lyricLine = lyricLine;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
    }

    public boolean hasTitle() {
        return title != null && !title.trim().isEmpty();
    }

    public boolean hasArtist() {
        return artist != null && !artist.trim().isEmpty();
    }

    public boolean hasAlbum() {
        return album != null && !album.trim().isEmpty();
    }

    public boolean hasTrack() {
        return hasTitle() || hasArtist() || hasAlbum();
    }

    public boolean hasLyric() {
        return lyricLine != null && !lyricLine.trim().isEmpty();
    }

    public boolean hasProgress() {
        return durationMs > 0;
    }
}