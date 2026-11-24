package com.foldstandby.clock;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Parcel;
import android.text.SpannableString;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import cn.lyric.getter.api.data.LyricData;

final class MusicLyricsChecks {
    static Bundle run() {
        return run(null);
    }

    static Bundle run(Context context) {
        Bundle result = new Bundle();
        MusicLyrics.resetForChecks();
        MediaSession session = null;
        try {
            String studio = "[00:01.00]First line\n[00:05.00]Second line";
            String live = "[00:04.00]First line\n[00:10.00]Second line";
            MusicLyrics.Track track = new MusicLyrics.Track("test", "Song", "Artist", "Studio", 180000);
            OnlineLyrics.Candidate correct = new OnlineLyrics.Candidate("song", "Artist", "Studio", 179000, studio);
            OnlineLyrics.Candidate wrongAlbum = new OnlineLyrics.Candidate("Song", "Artist", "Live", 180000, live);
            OnlineLyrics.Candidate wrongDuration = new OnlineLyrics.Candidate("Song", "Artist", "Studio", 175000, live);
            OnlineLyrics.Candidate wrongArtist = new OnlineLyrics.Candidate("Song", "Cover Artist", "Studio", 180000, live);
            require(studio.equals(OnlineLyrics.confidentMatch(track,
                    Arrays.asList(wrongAlbum, wrongDuration, wrongArtist, correct))), "exact version wins");
            require(OnlineLyrics.confidentMatch(track, Arrays.asList(wrongAlbum, wrongDuration, wrongArtist)) == null,
                    "wrong album, duration and artist rejected");
            OnlineLyrics.Candidate closer = new OnlineLyrics.Candidate("Song", "Artist", "Studio", 180000, live);
            require(live.equals(OnlineLyrics.confidentMatch(track, Arrays.asList(correct, closer))),
                    "best-scoring candidate automatically wins when multiple match");
            require(studio.equals(OnlineLyrics.confidentMatch(track, Arrays.asList(correct, correct))),
                    "identical timelines are definitive");
            MusicLyrics.Track noDuration = new MusicLyrics.Track("test", "Song", "Artist", "Studio", 0);
            require(OnlineLyrics.confidentMatch(noDuration, Collections.singletonList(correct)) == null,
                    "automatic match requires duration");

            // YouTube Music metadata normalization and MV duration tolerance
            MusicLyrics.Track ytMv = new MusicLyrics.Track("yt1", "アイドル (Official Music Video)",
                    "YOASOBI - Topic", "アイドル", 219000);
            OnlineLyrics.Candidate ytCand = new OnlineLyrics.Candidate("アイドル", "YOASOBI",
                    "THE BOOK 3", 213000, studio);
            require(studio.equals(OnlineLyrics.confidentMatch(ytMv, Collections.singletonList(ytCand))),
                    "YouTube Music Topic artist and Official Music Video duration match");

            MusicLyrics.Track ytAudio = new MusicLyrics.Track("yt2", "Artist - Song (Official Audio)",
                    "Artist - Topic", "Song (Official Audio)", 180000);
            OnlineLyrics.Candidate singleCand = new OnlineLyrics.Candidate("Song", "Artist", "Single", 180000, studio);
            require(studio.equals(OnlineLyrics.confidentMatch(ytAudio, Collections.singletonList(singleCand))),
                    "YouTube Music Artist - Title prefix and Official Audio match");

            MusicLyrics.Track ytLive = new MusicLyrics.Track("yt3", "Song (Live) (Official Video)",
                    "Artist - Topic", "", 180000);
            require(OnlineLyrics.confidentMatch(ytLive, Collections.singletonList(correct)) == null,
                    "Live video must not match studio candidate");
            require(live.equals(OnlineLyrics.confidentMatch(ytLive, Arrays.asList(correct, wrongAlbum))),
                    "Live video matches Live candidate");

            // Amazon Music / Apple Music / Spotify metadata normalization & multi-artist collaboration
            MusicLyrics.Track amazonTrack = new MusicLyrics.Track("amz", "Song [Explicit]",
                    "Lady Gaga, Bruno Mars", "Album (Deluxe Edition) [Explicit]", 180000);
            OnlineLyrics.Candidate spotifyCand = new OnlineLyrics.Candidate("Song - 2021 Remaster",
                    "Lady Gaga & Bruno Mars", "Album", 180000, studio);
            require(studio.equals(OnlineLyrics.confidentMatch(amazonTrack, Collections.singletonList(spotifyCand))),
                    "Explicit, Deluxe, Remaster and multi-artist collaboration match");

            MusicLyrics.Track singleTrack = new MusicLyrics.Track("apl", "Song (feat. Guest)",
                    "Artist", "Album - Single", 180000);
            OnlineLyrics.Candidate featCand = new OnlineLyrics.Candidate("Song", "Artist", "Album", 180000, studio);
            require(studio.equals(OnlineLyrics.confidentMatch(singleTrack, Collections.singletonList(featCand))),
                    "feat. suffix and - Single album suffix normalize cleanly");

            // NetEase song ID extraction & direct lyric JSON parsing
            MusicLyrics.Track neteaseIdTrack = new MusicLyrics.Track("ne/id/2003299229",
                    "com.netease.cloudmusic", "2003299229", "Number One - Bankai", "鷺巣詩郎", "", 0);
            require("2003299229".equals(neteaseIdTrack.neteaseSongId()) && neteaseIdTrack.searchable(),
                    "valid NetEase numeric MEDIA_ID is detected and searchable");
            MusicLyrics.Track nonNeteaseIdTrack = new MusicLyrics.Track("sp/id/2003299229",
                    "com.spotify.music", "2003299229", "Song", "Artist", "", 180000);
            require(nonNeteaseIdTrack.neteaseSongId() == null,
                    "non-NetEase package does not use NetEase direct song ID");
            String neteaseJson = new JSONObject().put("code", 200)
                    .put("lrc", new JSONObject().put("lyric", studio)).toString();
            OnlineLyrics.Candidate neteaseCand = OnlineLyrics.parseNeteaseLyric(neteaseIdTrack, neteaseJson);
            require(neteaseCand != null && neteaseCand.exactId && studio.equals(neteaseCand.lrc),
                    "NetEase JSON lrc payload parses as exactId candidate");
            require(studio.equals(OnlineLyrics.confidentMatch(neteaseIdTrack, Collections.singletonList(neteaseCand))),
                    "exactId NetEase candidate matches immediately even without duration");
            String nolyricJson = new JSONObject().put("code", 200).put("nolyric", true)
                    .put("lrc", new JSONObject().put("lyric", studio)).toString();
            require(OnlineLyrics.parseNeteaseLyric(neteaseIdTrack, nolyricJson) == null,
                    "instrumental nolyric NetEase response is ignored");

            // Cross-script artist + exact album + tight duration match (e.g. 鷺巣詩郎 vs Shiro Sagisu)
            MusicLyrics.Track bankaiTrack = new MusicLyrics.Track("ne1", "Number One - Bankai",
                    "鷺巣詩郎/Hazel Fernandes", "Number One - Bankai", 277000);
            OnlineLyrics.Candidate bankaiCand = new OnlineLyrics.Candidate("Number One - Bankai",
                    "Shiro Sagisu", "Number One - Bankai", 277000, studio);
            require(studio.equals(OnlineLyrics.confidentMatch(bankaiTrack, Collections.singletonList(bankaiCand))),
                    "cross-script artist matches when title, album and tight duration all match");
            require(OnlineLyrics.confidentMatch(track, Collections.singletonList(wrongArtist)) == null,
                    "same-script wrong artist with matching album and duration remains rejected");

            // Unlocked multi-key & dynamic keySet() whole-LRC metadata extraction
            MediaMetadata standardLyricMeta = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Demo")
                    .putString("android.media.metadata.LYRIC", studio)
                    .build();
            require(studio.equals(MusicLyrics.playerLyrics(null, standardLyricMeta)),
                    "standard android.media.metadata.LYRIC is extracted without QQ package lock");

            MediaMetadata dynamicKeyMeta = new MediaMetadata.Builder()
                    .putString("com.vendor.custom_lyric_lrc", studio)
                    .build();
            require(studio.equals(MusicLyrics.playerLyrics(null, dynamicKeyMeta)),
                    "dynamic *lyric*/*lrc* metadata key is discovered via keySet()");

            String jsonInfo = new JSONObject().put("songName", "Demo").put("lyric", studio).toString();
            MediaMetadata jsonLyricMeta = new MediaMetadata.Builder()
                    .putString("lyricInfo", jsonInfo)
                    .build();
            require(studio.equals(MusicLyrics.playerLyrics(null, jsonLyricMeta)),
                    "JSON lyricInfo metadata is extracted");

            if (context != null) {
                session = new MediaSession(context, "LyricsChecks");
                Bundle extras = new Bundle();
                extras.putString("ucar.media.metadata.LYRICS_WHOLE", studio);
                session.setExtras(extras);
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "晴天")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "周杰伦")
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "叶惠美")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, 269000)
                        .build());
                session.setPlaybackState(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 12000, 1f).build());
                MediaController controller = new MediaController(context, session.getSessionToken());
                require(studio.equals(MusicLyrics.playerLyrics(controller, controller.getMetadata())),
                        "unlocked UCar whole lyrics work on any package MediaController");

                // Bluetooth AVRCP dynamic title stabilization
                require("晴天".equals(MusicLyrics.title(controller, controller.getMetadata())),
                        "initial title locks canonical song title");
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "故事的小黄花")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "周杰伦")
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "叶惠美")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, 269000)
                        .build());
                require("晴天".equals(MusicLyrics.title(controller, controller.getMetadata())),
                        "canonical title stays locked when Bluetooth lyrics update METADATA_KEY_TITLE");
                LrcLyrics btLive = MusicLyrics.liveLyrics(controller, controller.getMetadata());
                require(btLive != null && btLive.isLive()
                        && "故事的小黄花".equals(btLive.lineAt(btLive.lineCount() - 1)),
                        "Bluetooth dynamic title emits live lyric line");
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "从前从前有个人爱你很久")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "周杰伦")
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "叶惠美")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, 269000)
                        .build());
                btLive = MusicLyrics.liveLyrics(controller, controller.getMetadata());
                require(btLive != null && btLive.lineCount() == 2
                        && "故事的小黄花".equals(btLive.lineAt(0))
                        && "从前从前有个人爱你很久".equals(btLive.lineAt(1)),
                        "Bluetooth lyric stream retains prior line context");
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "track-2")
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "七里香")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "周杰伦")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, 299000)
                        .build());
                require("七里香".equals(MusicLyrics.title(controller, controller.getMetadata())),
                        "new track unlocks and updates canonical title");
            }

            // Notification ticker ingestion, banner rejection & unflagged two-step guard
            String netease = "com.netease.cloudmusic";
            MediaMetadata neteaseMeta = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "夜曲")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "周杰伦")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 226000)
                    .build();
            MusicLyrics.syncMetadata(netease, neteaseMeta, 1000);
            require(!MusicLyrics.ingestNotification("com.tencent.mm", 0, "微信消息", false, netease, neteaseMeta),
                    "unrelated app notifications are strictly ignored");
            require(!MusicLyrics.ingestNotification(netease, 0, "网易云音乐正在播放", true, netease, neteaseMeta),
                    "NetEase static '正在播放' foreground ticker is strictly rejected");
            require(!MusicLyrics.ingestNotification(netease, 0, "QQ音乐正在播放", true, netease, neteaseMeta),
                    "QQ static '正在播放' foreground ticker is strictly rejected");
            require(!MusicLyrics.ingestNotification(netease, 0, "夜曲 - 周杰伦", true, netease, neteaseMeta),
                    "unflagged track banner ticker is ignored");
            require(MusicLyrics.liveLyricsForPackage(netease) == null,
                    "rejected status banners never pollute live lyrics");
            require(!MusicLyrics.ingestNotification(netease, 0, "初始通知提示", true, netease, neteaseMeta),
                    "first unflagged non-banner ticker is held back until dynamic change is proven");
            require(MusicLyrics.ingestNotification(netease, 0, "为你弹奏肖邦的夜曲", true, netease, neteaseMeta),
                    "second changing unflagged ticker on active session is accepted as live lyric");
            require(MusicLyrics.ingestNotification(netease, MusicLyrics.FLAG_ALWAYS_SHOW_TICKER,
                    "一群嗜血的蚂蚁", false, netease, neteaseMeta),
                    "Flyme ticker-flagged notification lyric is captured immediately");
            LrcLyrics tickerLyrics = MusicLyrics.liveLyricsForPackage(netease);
            require(tickerLyrics != null && "一群嗜血的蚂蚁".equals(tickerLyrics.lineAt(tickerLyrics.lineCount() - 1)),
                    "captured ticker lyric populates live stream");

            // NetEase UPDATE_STATUS_LYRIC real payload ("com.netease.cloudmusic.STATUS_LYRIC_TEXT" + "need_hide")
            MusicLyrics.resetForChecks();
            Intent neteaseBroadcast = new Intent("com.netease.cloudmusic.UPDATE_STATUS_LYRIC")
                    .putExtra("com.netease.cloudmusic.STATUS_LYRIC_TEXT", new SpannableString("Living out your fantasy"))
                    .putExtra("need_hide", false);
            require(MusicLyrics.ingestBroadcast(neteaseBroadcast, null),
                    "NetEase UPDATE_STATUS_LYRIC broadcast with STATUS_LYRIC_TEXT accepted");
            LrcLyrics neBroadcastLyrics = MusicLyrics.liveLyricsForPackage(netease);
            require(neBroadcastLyrics != null && "Living out your fantasy".equals(neBroadcastLyrics.lineAt(0)),
                    "NetEase broadcast infers package and extracts STATUS_LYRIC_TEXT");
            Intent neteaseHidden = new Intent("com.netease.cloudmusic.UPDATE_STATUS_LYRIC")
                    .putExtra("com.netease.cloudmusic.STATUS_LYRIC_TEXT", "Ignored when hidden")
                    .putExtra("need_hide", true);
            require(!MusicLyrics.ingestBroadcast(neteaseHidden, null),
                    "NetEase need_hide=true broadcast does not append hidden line");

            // LyricData Parcelable round-trip & broadcast ingestion
            Bundle extra = new Bundle();
            extra.putString("packageName", "com.spotify.music");
            LyricData sourceData = new LyricData(LyricData.TYPE_UPDATE, "Is this the real life?", extra);
            Parcel parcel = Parcel.obtain();
            sourceData.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            LyricData restored = LyricData.CREATOR.createFromParcel(parcel);
            parcel.recycle();
            require(restored.type == LyricData.TYPE_UPDATE
                    && "Is this the real life?".equals(restored.lyric)
                    && "com.spotify.music".equals(restored.packageName()),
                    "LyricData Parcelable round-trips identically");
            Intent updateIntent = new Intent("Lyric_Data").putExtra("Data", restored);
            require(MusicLyrics.ingestBroadcast(updateIntent, null), "Lyric_Data update broadcast accepted");
            LrcLyrics broadcastLyrics = MusicLyrics.liveLyricsForPackage("com.spotify.music");
            require(broadcastLyrics != null && "Is this the real life?".equals(broadcastLyrics.lineAt(0)),
                    "Lyric_Data broadcast populates target package live lyrics");
            Intent stopIntent = new Intent("Lyric_Data")
                    .putExtra("Data", new LyricData(LyricData.TYPE_STOP, "", extra));
            require(MusicLyrics.ingestBroadcast(stopIntent, null), "Lyric_Data stop broadcast accepted");
            require(MusicLyrics.liveLyricsForPackage("com.spotify.music") == null,
                    "Lyric_Data stop broadcast clears live lyrics");

            JSONArray records = new JSONArray().put(new JSONObject().put("syncedLyrics", "plain text"))
                    .put(new JSONObject().put("instrumental", true).put("syncedLyrics", studio))
                    .put(new JSONObject().put("trackName", "Song").put("artistName", "Artist")
                            .put("albumName", "Studio").put("duration", 179.25).put("syncedLyrics", studio));
            List<OnlineLyrics.Candidate> parsed = OnlineLyrics.candidates(records);
            require(parsed.size() == 1 && parsed.get(0).duration == 179250, "only timed lyrics are usable");
            OnlineLyrics.SearchResult found = new OnlineLyrics.SearchResult(parsed, null);
            require(found.status(studio) == OnlineLyrics.Status.IDLE, "a match is displayed in place of search status");
            require(found.status(null) == OnlineLyrics.Status.CHOOSE_VERSION, "unconfirmed matches offer a version choice");
            require(new OnlineLyrics.SearchResult(Collections.emptyList(), null).status(null) == OnlineLyrics.Status.NOT_FOUND,
                    "missing lyrics are distinct from service failure");
            require(OnlineLyrics.SearchResult.unavailable().status(null) == OnlineLyrics.Status.UNAVAILABLE,
                    "service failure has its own status");
            require(OnlineLyrics.SearchResult.failure(OnlineLyrics.Status.TIMED_OUT).status(null) == OnlineLyrics.Status.TIMED_OUT,
                    "a timed-out lookup reports the timeout");
            LrcLyrics timeline = LrcLyrics.parse(parsed.get(0).lrc);
            require("Second line".equals(timeline.lineAt(timeline.indexAt(4500 + 500))), "positive adjustment advances cue");
            require("First line".equals(timeline.lineAt(timeline.indexAt(5000 - 500))), "negative adjustment delays cue");
            result.putBoolean("passed", true);
            result.putString("result", "PASS: multi-channel lyrics, banner filtering, NetEase/LyricData broadcasts, cross-script matching, and timing offsets");
        } catch (Exception | AssertionError failure) {
            result.putString("result", android.util.Log.getStackTraceString(failure));
        } finally {
            if (session != null) session.release();
            MusicLyrics.resetForChecks();
        }
        return result;
    }

    private static void require(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
    }
}
