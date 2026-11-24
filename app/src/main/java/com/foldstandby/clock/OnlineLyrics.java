package com.foldstandby.clock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Searches the public LRCLIB catalog only after the user enables online lyrics. */
final class OnlineLyrics {
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, SearchResult> RECENT = new LruCache<>(5);
    private static final LruCache<String, Long> ATTEMPTED = new LruCache<>(100);
    private static final int MAX_CACHE_TRACKS = 100;
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private static final long RETRY_MILLIS = 15 * 60 * 1000L;
    private static final String TIME_PREFIX = "saved/";
    private static volatile long blockedUntil;
    private static boolean automaticBusy;

    private OnlineLyrics() {}

    static SharedPreferences cache(Context context) {
        return context.getSharedPreferences("online_music_lyrics", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("clock", Context.MODE_PRIVATE).getBoolean(MusicLyrics.ONLINE, false);
    }

    static String selected(Context context, MusicLyrics.Track track) {
        String value = MusicLyrics.library(context).getString(MusicLyrics.selectedOnlineKey(track.key), null);
        String prefix = track.signature + "\n";
        return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : null;
    }

    static void select(Context context, MusicLyrics.Track track, Candidate candidate) {
        MusicLyrics.library(context).edit().putString(MusicLyrics.selectedOnlineKey(track.key),
                track.signature + "\n" + candidate.lrc).apply();
    }

    static void automatic(Context context, MusicLyrics.Track track, Runnable redraw) {
        if (automaticBusy || !track.searchable() || track.duration <= 0
                || SystemClock.elapsedRealtime() < blockedUntil) return;
        Long previous = ATTEMPTED.get(track.signature);
        if (previous != null && SystemClock.elapsedRealtime() - previous < RETRY_MILLIS) return;
        automaticBusy = true;
        ATTEMPTED.put(track.signature, SystemClock.elapsedRealtime());
        search(context, track, result -> {
            automaticBusy = false;
            String matched = confidentMatch(track, result.candidates);
            if (matched != null && enabled(context)) cacheMatch(context, track, matched);
            redraw.run();
        });
    }

    static void search(Context context, MusicLyrics.Track track, Consumer<SearchResult> complete) {
        SearchResult recent = RECENT.get(track.signature);
        if (recent != null && SystemClock.elapsedRealtime() - recent.receivedAt < RETRY_MILLIS) {
            MAIN.post(() -> complete.accept(recent));
            return;
        }
        Context app = context.getApplicationContext();
        NETWORK.execute(() -> {
            SearchResult result = enabled(app) ? fetch(track) : SearchResult.unavailable();
            MAIN.post(() -> {
                if (!result.unavailable && enabled(app)) RECENT.put(track.signature, result);
                complete.accept(result);
            });
        });
    }

    private static void cacheMatch(Context context, MusicLyrics.Track track, String lrc) {
        SharedPreferences preferences = cache(context);
        Map<String, ?> all = preferences.getAll();
        SharedPreferences.Editor edit = preferences.edit();
        if (!all.containsKey(track.signature)) {
            TreeMap<Long, List<String>> byAge = new TreeMap<>();
            int cached = 0;
            for (Map.Entry<String, ?> item : all.entrySet()) {
                if (item.getKey().startsWith(TIME_PREFIX) && item.getValue() instanceof Long) {
                    byAge.computeIfAbsent((Long) item.getValue(), time -> new ArrayList<>()).add(item.getKey());
                    cached++;
                }
            }
            for (List<String> keys : byAge.values()) {
                for (String key : keys) {
                    if (cached < MAX_CACHE_TRACKS) break;
                    edit.remove(key).remove(key.substring(TIME_PREFIX.length()));
                    cached--;
                }
                if (cached < MAX_CACHE_TRACKS) break;
            }
        }
        edit.putString(track.signature, lrc).putLong(TIME_PREFIX + track.signature, System.currentTimeMillis()).apply();
    }

    private static SearchResult fetch(MusicLyrics.Track track) {
        if (SystemClock.elapsedRealtime() < blockedUntil) return SearchResult.unavailable();
        Uri uri = Uri.parse("https://lrclib.net/api/search").buildUpon()
                .appendQueryParameter("track_name", track.title)
                .appendQueryParameter("artist_name", track.artist).build();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "ShiqiClock/" + BuildConfig.VERSION_NAME
                    + " (https://github.com/PeiyaoTao/shixi-shizhong)");
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == 404) return new SearchResult(Collections.emptyList(), false);
            if (status == 429 || status == 503) {
                long seconds = 60;
                try {
                    seconds = Math.max(1, Math.min(86400, Long.parseLong(connection.getHeaderField("Retry-After"))));
                } catch (NumberFormatException ignored) { }
                blockedUntil = SystemClock.elapsedRealtime() + seconds * 1000;
            }
            if (status != 200) return SearchResult.unavailable();
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (data.size() + length > MAX_RESPONSE) return SearchResult.unavailable();
                    data.write(buffer, 0, length);
                }
                return new SearchResult(candidates(new JSONArray(new String(data.toByteArray(), StandardCharsets.UTF_8))), false);
            }
        } catch (IOException | JSONException unavailable) {
            return SearchResult.unavailable();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static List<Candidate> candidates(JSONArray records) {
        List<Candidate> matches = new ArrayList<>();
        for (int i = 0; i < Math.min(20, records.length()); i++) {
            JSONObject row = records.optJSONObject(i);
            if (row == null || row.isNull("syncedLyrics") || row.optBoolean("instrumental")) continue;
            String lrc = row.optString("syncedLyrics").replace("\r\n", "\n").trim();
            if (lrc.length() > LrcLyrics.MAX_BYTES || !LrcLyrics.parse(lrc).hasLines()) continue;
            matches.add(new Candidate(row.optString("trackName", row.optString("name")),
                    row.optString("artistName"), row.optString("albumName"),
                    Math.round(row.optDouble("duration", 0) * 1000), lrc));
        }
        return matches;
    }

    static String confidentMatch(MusicLyrics.Track track, List<Candidate> candidates) {
        if (!track.searchable() || track.duration <= 0) return null;
        String lyrics = null;
        for (Candidate candidate : candidates) {
            if (!match(track.title, candidate.title) || !match(track.artist, candidate.artist)
                    || candidate.duration <= 0 || Math.abs(candidate.duration - track.duration) > 2000
                    || !track.album.isEmpty() && !match(track.album, candidate.album)) continue;
            if (lyrics != null && !lyrics.equals(candidate.lrc)) return null;
            lyrics = candidate.lrc;
        }
        return lyrics;
    }

    private static boolean match(String a, String b) {
        return Normalizer.normalize(a.trim(), Normalizer.Form.NFKC).equalsIgnoreCase(
                Normalizer.normalize(b.trim(), Normalizer.Form.NFKC));
    }

    static final class Candidate {
        final String title, artist, album, lrc;
        final long duration;

        Candidate(String title, String artist, String album, long duration, String lrc) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
            this.lrc = lrc;
        }

        String label() {
            return title + " · " + artist + "\n" + album + (duration > 0
                    ? String.format(Locale.ROOT, " · %d:%02d", duration / 60000, duration / 1000 % 60) : "");
        }
    }

    static final class SearchResult {
        final List<Candidate> candidates;
        final boolean unavailable;
        final long receivedAt = SystemClock.elapsedRealtime();

        SearchResult(List<Candidate> candidates, boolean unavailable) {
            this.candidates = candidates;
            this.unavailable = unavailable;
        }

        static SearchResult unavailable() {
            return new SearchResult(Collections.emptyList(), true);
        }
    }
}
