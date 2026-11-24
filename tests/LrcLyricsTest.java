package com.foldstandby.clock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class LrcLyricsTest {
    public static void main(String[] args) throws Exception {
        LrcLyrics lyrics = LrcLyrics.parse("\ufeff[ti:新曲]\r\n[00:04.100][00:10:200]<00:04.100>副歌\r\n"
                + "[00:01.25]开场\r\n[00:07.4]\r\n[00:07.4]桥段\r\n"
                + "[00:12]\r\n[offset:+250]");
        require(lyrics.hasLines(), "valid LRC");
        require(lyrics.lineAt(lyrics.indexAt(999)).isEmpty(), "no text before the first cue");
        require(lyrics.lineAt(lyrics.indexAt(1000)).equals("开场"), "centiseconds and positive offset");
        require(lyrics.nextTime(lyrics.indexAt(1000)) == 3850, "next wake-up time");
        require(lyrics.lineAt(lyrics.indexAt(3850)).equals("副歌"), "out-of-order and word tags");
        require(lyrics.lineAt(lyrics.indexAt(7150)).equals("桥段"), "tenths and duplicate timestamps");
        require(lyrics.lineAt(lyrics.indexAt(9950)).equals("副歌"), "repeated timestamp");
        require(lyrics.lineAt(lyrics.indexAt(11750)).isEmpty(), "blank cue clears lyric");
        require(lyrics.nextTime(lyrics.indexAt(11750)) == Long.MAX_VALUE, "no tick after the final cue");
        LrcLyrics gaps = LrcLyrics.parse("[00:01]\n[00:05]首句\n[00:08]\n[00:10]\n[00:12]末句\n[00:14]");
        require(gaps.lineAt(gaps.visibleIndex(gaps.indexAt(0))).equals("首句"), "preview the first line before its cue");
        require(gaps.lineAt(gaps.visibleIndex(gaps.indexAt(1000))).equals("首句"), "skip leading blank markers");
        require(gaps.lineAt(gaps.visibleIndex(gaps.indexAt(11000))).equals("首句"), "keep the last line through consecutive blank cues");
        require(gaps.nextTime(gaps.indexAt(11000)) == 12000, "resume matching at the next timed lyric");
        require(gaps.lineAt(gaps.visibleIndex(gaps.indexAt(18000))).equals("末句"), "keep the final line through the outro");
        require(lyrics.lineAt(lyrics.indexAt(1000)).equals("开场"), "seeking backward");
        require(!LrcLyrics.parse("无时间轴\n[00:00]").hasLines(), "require timed text");
        require(LrcLyrics.parse("[00:00]起句\n[offset:-500]").nextTime(-1) == 500, "negative offset");
        LrcLyrics live = LrcLyrics.live(java.util.Arrays.asList("", "<00:01.00>第一句", "  ", "第二句"));
        require(live.hasLines() && live.isLive(), "live stream creates live timeline");
        require(live.lineCount() == 2 && live.lineAt(live.lineCount() - 1).equals("第二句"), "latest live line is last");
        require(live.lineAt(0).equals("第一句"), "live stream strips inline word tags and keeps history");
        require(!LrcLyrics.live(java.util.Collections.singletonList("   ")).hasLines(), "blank live lines are empty");
        for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16, Charset.forName("GB18030")}) {
            String line = "[00:00.00]初夏";
            require(LrcLyrics.read(new ByteArrayInputStream(line.getBytes(encoding))).equals(line), "encoding " + encoding);
        }
        try {
            LrcLyrics.read(new ByteArrayInputStream(new byte[LrcLyrics.MAX_BYTES + 1]));
            throw new AssertionError("reject oversized source");
        } catch (IOException expected) {
            System.out.println("PASS: LRC timings, seeks, repeat cues, encodings, and import size bound.");
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
