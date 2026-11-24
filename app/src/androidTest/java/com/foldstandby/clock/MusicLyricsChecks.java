package com.foldstandby.clock;

import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class MusicLyricsChecks {
    static Bundle run() {
        Bundle result = new Bundle();
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
            OnlineLyrics.Candidate ambiguous = new OnlineLyrics.Candidate("Song", "Artist", "Studio", 180000, live);
            require(OnlineLyrics.confidentMatch(track, Arrays.asList(correct, ambiguous)) == null,
                    "different timelines with equally strong metadata require selection");
            require(studio.equals(OnlineLyrics.confidentMatch(track, Arrays.asList(correct, correct))),
                    "identical timelines are definitive");
            MusicLyrics.Track noDuration = new MusicLyrics.Track("test", "Song", "Artist", "Studio", 0);
            require(OnlineLyrics.confidentMatch(noDuration, Collections.singletonList(correct)) == null,
                    "automatic match requires duration");

            JSONArray records = new JSONArray().put(new JSONObject().put("syncedLyrics", "plain text"))
                    .put(new JSONObject().put("instrumental", true).put("syncedLyrics", studio))
                    .put(new JSONObject().put("trackName", "Song").put("artistName", "Artist")
                            .put("albumName", "Studio").put("duration", 179.25).put("syncedLyrics", studio));
            List<OnlineLyrics.Candidate> parsed = OnlineLyrics.candidates(records);
            require(parsed.size() == 1 && parsed.get(0).duration == 179250, "only timed lyrics are usable");
            LrcLyrics timeline = LrcLyrics.parse(parsed.get(0).lrc);
            require("Second line".equals(timeline.lineAt(timeline.indexAt(4500 + 500))), "positive adjustment advances cue");
            require("First line".equals(timeline.lineAt(timeline.indexAt(5000 - 500))), "negative adjustment delays cue");
            result.putBoolean("passed", true);
            result.putString("result", "PASS: lyric version matching, ambiguity, response parsing and timing offsets");
        } catch (Exception | AssertionError failure) {
            result.putString("result", android.util.Log.getStackTraceString(failure));
        }
        return result;
    }

    private static void require(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
    }
}
