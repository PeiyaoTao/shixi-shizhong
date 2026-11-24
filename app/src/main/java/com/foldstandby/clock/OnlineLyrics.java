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
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Searches the public LRCLIB catalog with metadata normalization and best-version scoring. */
final class OnlineLyrics {
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, SearchResult> RECENT = new LruCache<>(5);
    private static final LruCache<String, Attempt> ATTEMPTED = new LruCache<>(100);
    private static final Map<String, PendingSearch> IN_FLIGHT = new HashMap<>();
    private static final int MAX_CACHE_TRACKS = 100;
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private static final long RETRY_MILLIS = 15 * 60 * 1000L;
    private static final long SEARCH_TIMEOUT_MILLIS = 10_000L;
    private static final String TIME_PREFIX = "saved/";

    private static final int FLAG_LIVE = 1;
    private static final int FLAG_REMIX = 1 << 1;
    private static final int FLAG_ACOUSTIC = 1 << 2;
    private static final int FLAG_INST = 1 << 3;
    private static final int FLAG_SHORT = 1 << 4;
    private static final int FLAG_SPEED = 1 << 5;

    private static final Pattern TOPIC_SUFFIX = Pattern.compile("(?i)\\s+-\\s+topic$");
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "(?i)\\s*(?:,|&|/|、|;|\\s+(?:feat\\.?|ft\\.?|featuring|with|x|×)\\s+)\\s*");
    private static final Pattern TRAILING_FEAT = Pattern.compile("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.+$");
    private static final Pattern BRACKET_SEGMENT = Pattern.compile("\\(([^()]*)\\)|\\[([^\\[\\]]*)\\]|【([^【】]*)】|（([^（）]*)）");
    private static final Pattern DASH_SUFFIX = Pattern.compile("\\s+-\\s+([^\\-]+)$");
    private static final Pattern NOISE_CONTENT = Pattern.compile(
            "(?i)^(?:official\\s+(?:music\\s+|lyric\\s+)?video|official\\s+audio|official\\s+mv|official\\s+pv"
                    + "|lyric\\s+video|music\\s+video|visualizer|performance\\s+video|audio|video|mv|pv|hd|hq|4k"
                    + "|explicit|clean|single|ep|deluxe(?:\\s+(?:edition|version))?|expanded\\s+edition"
                    + "|special\\s+edition|standard\\s+edition|bonus\\s+track(?:\\s+version)?"
                    + "|(?:(?:19|20)\\d{2}\\s+)?remaster(?:ed)?(?:\\s+(?:19|20)\\d{2})?"
                    + "|(?:feat\\.?|ft\\.?|featuring)\\s+.+|官方\\s*mv)$");
    private static final Pattern VIDEO_TAG = Pattern.compile(
            "(?i)\\b(?:official\\s+(?:music\\s+|lyric\\s+)?video|lyric\\s*video|music\\s*video|visualizer|performance\\s*video)\\b"
                    + "|[\\[(【（]\\s*(?:mv|pv)\\s*[\\])】）]|官方\\s*mv");
    private static final Pattern LIVE_TAG = Pattern.compile("(?i)\\b(?:live|concert)\\b|现场|演唱会");
    private static final Pattern REMIX_TAG = Pattern.compile("(?i)\\b(?:remix|rework|bootleg|vip\\s+mix)\\b|混音");
    private static final Pattern ACOUSTIC_TAG = Pattern.compile("(?i)\\b(?:acoustic|unplugged)\\b|不插电");
    private static final Pattern INST_TAG = Pattern.compile(
            "(?i)\\b(?:inst|instrumental|karaoke|off\\s*vocal|backing\\s*track)\\b|伴奏|纯音乐|卡拉OK");
    private static final Pattern SHORT_TAG = Pattern.compile("(?i)\\b(?:tv\\s*size|short\\s*ver(?:sion)?|movie\\s*ver(?:sion)?)\\b");
    private static final Pattern SPEED_TAG = Pattern.compile("(?i)\\b(?:sped\\s*up|slowed|nightcore)\\b");

    private static volatile long blockedUntil;
    private static boolean automaticBusy;

    private OnlineLyrics() {}

    static SharedPreferences cache(Context context) {
        return context.getSharedPreferences("online_music_lyrics", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return enabled(context.getSharedPreferences("clock", Context.MODE_PRIVATE));
    }

    static boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean(MusicLyrics.ONLINE, MusicLyrics.ONLINE_DEFAULT);
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

    static Status automatic(Context context, MusicLyrics.Track track, boolean start, Runnable redraw) {
        if (!track.searchable() || (track.duration <= 0 && track.neteaseSongId() == null)) {
            return Status.INCOMPLETE_TRACK;
        }
        Attempt previous = ATTEMPTED.get(track.signature);
        if (previous != null && (!start || SystemClock.elapsedRealtime() - previous.at < RETRY_MILLIS)) {
            return previous.status;
        }
        if (!start) return Status.IDLE;
        if (SystemClock.elapsedRealtime() < blockedUntil) return Status.UNAVAILABLE;
        if (automaticBusy) return Status.WAITING;
        automaticBusy = true;
        ATTEMPTED.put(track.signature, new Attempt(Status.SEARCHING));
        Context app = context.getApplicationContext();
        search(app, track, result -> {
            automaticBusy = false;
            if (enabled(app)) {
                String matched = confidentMatch(track, result.candidates);
                if (matched != null) cacheMatch(app, track, matched);
                ATTEMPTED.put(track.signature, new Attempt(result.status(matched)));
            } else ATTEMPTED.remove(track.signature);
            redraw.run();
        });
        return Status.SEARCHING;
    }

    static void search(Context context, MusicLyrics.Track track, Consumer<SearchResult> complete) {
        SearchResult recent = RECENT.get(track.signature);
        if (recent != null && SystemClock.elapsedRealtime() - recent.receivedAt < RETRY_MILLIS) {
            MAIN.post(() -> complete.accept(recent));
            return;
        }
        PendingSearch current = IN_FLIGHT.get(track.signature);
        if (current != null) {
            current.listeners.add(complete);
            return;
        }
        Context app = context.getApplicationContext();
        PendingSearch pending = new PendingSearch();
        pending.listeners.add(complete);
        IN_FLIGHT.put(track.signature, pending);
        pending.timeout = () -> {
            pending.work.cancel(true);
            finishSearch(app, track, pending, SearchResult.failure(Status.TIMED_OUT));
        };
        MAIN.postDelayed(pending.timeout, SEARCH_TIMEOUT_MILLIS);
        pending.work = NETWORK.submit(() -> {
            SearchResult result = enabled(app) ? fetch(track) : SearchResult.unavailable();
            MAIN.post(() -> finishSearch(app, track, pending, result));
        });
    }

    private static void finishSearch(Context context, MusicLyrics.Track track, PendingSearch pending, SearchResult result) {
        if (IN_FLIGHT.get(track.signature) != pending) return;
        IN_FLIGHT.remove(track.signature);
        MAIN.removeCallbacks(pending.timeout);
        if (result.failure == null && enabled(context)) RECENT.put(track.signature, result);
        for (Consumer<SearchResult> listener : pending.listeners) listener.accept(result);
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
        String neteaseId = track.neteaseSongId();
        if (neteaseId != null) {
            SearchResult direct = fetchNeteaseById(track, neteaseId);
            if (direct != null && direct.failure == null && !direct.candidates.isEmpty()) return direct;
            if (track.title.isEmpty() || track.artist.isEmpty()) {
                return direct != null ? direct : new SearchResult(Collections.emptyList(), null);
            }
        }
        if (SystemClock.elapsedRealtime() < blockedUntil) return SearchResult.unavailable();
        String cleanedArtist = cleanArtist(track.artist);
        List<String> tokens = artistTokens(cleanedArtist);
        String primaryArtist = tokens.isEmpty() ? cleanedArtist : tokens.get(0);
        String cleanedTitle = cleanTitle(track.title, cleanedArtist, primaryArtist);
        String queryTitle = cleanedTitle.isEmpty() ? track.title : cleanedTitle;
        String queryArtist = primaryArtist.isEmpty() ? track.artist : primaryArtist;

        Uri uri = Uri.parse("https://lrclib.net/api/search").buildUpon()
                .appendQueryParameter("track_name", queryTitle)
                .appendQueryParameter("artist_name", queryArtist).build();
        SearchResult result = executeSearch(uri);
        if (result.failure == null && result.candidates.isEmpty()
                && SystemClock.elapsedRealtime() >= blockedUntil
                && !Thread.currentThread().isInterrupted()
                && tokens.size() > 1) {
            Uri secondary = Uri.parse("https://lrclib.net/api/search").buildUpon()
                    .appendQueryParameter("q", (queryTitle + " " + tokens.get(1)).trim()).build();
            SearchResult secondResult = executeSearch(secondary);
            if (secondResult.failure == null && !secondResult.candidates.isEmpty()) return secondResult;
        }
        if (result.failure == null && result.candidates.isEmpty()
                && SystemClock.elapsedRealtime() >= blockedUntil
                && !Thread.currentThread().isInterrupted()
                && !queryTitle.isEmpty()) {
            Uri titleOnly = Uri.parse("https://lrclib.net/api/search").buildUpon()
                    .appendQueryParameter("track_name", queryTitle).build();
            SearchResult broad = executeSearch(titleOnly);
            if (broad.failure == null && !broad.candidates.isEmpty()) return broad;
        }
        return result;
    }

    private static SearchResult fetchNeteaseById(MusicLyrics.Track track, String songId) {
        Uri uri = Uri.parse("https://music.163.com/api/song/lyric").buildUpon()
                .appendQueryParameter("id", songId)
                .appendQueryParameter("lv", "1")
                .appendQueryParameter("kv", "1")
                .appendQueryParameter("tv", "-1").build();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
            connection.setRequestProperty("Referer", "https://music.163.com/");
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (data.size() + length > MAX_RESPONSE) return null;
                    data.write(buffer, 0, length);
                }
                Candidate candidate = parseNeteaseLyric(track, new String(data.toByteArray(), StandardCharsets.UTF_8));
                return candidate == null ? null : new SearchResult(Collections.singletonList(candidate), null);
            }
        } catch (IOException unavailable) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static Candidate parseNeteaseLyric(MusicLyrics.Track track, String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(body);
            if (root.optInt("code", 200) != 200 || root.optBoolean("nolyric") || root.optBoolean("uncollected")) {
                return null;
            }
            JSONObject lrcNode = root.optJSONObject("lrc");
            if (lrcNode == null) return null;
            String lrc = lrcNode.optString("lyric", "").replace("\r\n", "\n").trim();
            if (lrc.isEmpty() || lrc.length() > LrcLyrics.MAX_BYTES || !LrcLyrics.parse(lrc).hasLines()) {
                return null;
            }
            String title = track == null ? "" : track.title;
            String artist = track == null ? "" : track.artist;
            String album = track == null ? "" : track.album;
            long duration = track == null ? 0L : track.duration;
            return new Candidate(title, artist, album, duration, lrc, true);
        } catch (JSONException invalid) {
            return null;
        }
    }

    private static SearchResult executeSearch(Uri uri) {
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
            if (status == 404) return new SearchResult(Collections.emptyList(), null);
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
                return new SearchResult(candidates(new JSONArray(new String(data.toByteArray(), StandardCharsets.UTF_8))), null);
            }
        } catch (SocketTimeoutException timeout) {
            return SearchResult.failure(Status.TIMED_OUT);
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
        if (track == null || !track.searchable() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.exactId && candidate.lrc != null && !candidate.lrc.isEmpty()) {
                return candidate.lrc;
            }
        }
        if (track.duration <= 0) return null;
        String trackCleanArtist = cleanArtist(track.artist);
        List<String> trackArtistTokens = artistTokens(trackCleanArtist);
        String trackPrimaryArtist = trackArtistTokens.isEmpty() ? trackCleanArtist : trackArtistTokens.get(0);
        String trackCleanTitle = cleanTitle(track.title, trackCleanArtist, trackPrimaryArtist);
        String trackBaseTitle = stripVersionSegments(trackCleanTitle);
        String trackCleanAlbum = cleanAlbum(track.album);
        int trackVersion = versionFlags(track.title, track.album);
        long maxDelta = hasVideoTag(track.title) ? 8000L : 3500L;

        int bestScore = Integer.MIN_VALUE;
        String bestLyrics = null;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.duration <= 0) continue;
            long delta = Math.abs(candidate.duration - track.duration);
            if (delta > maxDelta) continue;
            if (versionFlags(candidate.title, candidate.album) != trackVersion) continue;

            String candCleanArtist = cleanArtist(candidate.artist);
            List<String> candArtistTokens = artistTokens(candCleanArtist);
            String candPrimaryArtist = candArtistTokens.isEmpty() ? candCleanArtist : candArtistTokens.get(0);
            String candCleanTitle = cleanTitle(candidate.title, candCleanArtist, candPrimaryArtist);

            boolean exactCleanTitle = !trackCleanTitle.isEmpty() && match(trackCleanTitle, candCleanTitle);
            boolean titleMatched = match(track.title, candidate.title)
                    || exactCleanTitle
                    || (trackVersion != 0 && !trackBaseTitle.isEmpty()
                    && match(trackBaseTitle, stripVersionSegments(candCleanTitle)));
            if (!titleMatched) continue;

            String candCleanAlbum = cleanAlbum(candidate.album);
            boolean directArtist = artistMatches(track.artist, trackCleanArtist, trackPrimaryArtist, trackArtistTokens,
                    candidate.artist, candCleanArtist, candPrimaryArtist, candArtistTokens);
            boolean crossScriptArtist = !directArtist
                    && delta <= 1500L
                    && hasCjkOrKana(trackCleanArtist) != hasCjkOrKana(candCleanArtist)
                    && !trackCleanAlbum.isEmpty() && !candCleanAlbum.isEmpty()
                    && trackCleanAlbum.length() >= 3
                    && match(trackCleanAlbum, candCleanAlbum);
            if (!directArtist && !crossScriptArtist) {
                continue;
            }

            int score = 10000;
            if (match(track.title, candidate.title)) score += 400;
            else if (exactCleanTitle) score += 200;
            score += !directArtist ? 100 : (match(trackCleanArtist, candCleanArtist) ? 500 : 250);

            if (!trackCleanAlbum.isEmpty() && !candCleanAlbum.isEmpty()) {
                if (match(trackCleanAlbum, candCleanAlbum)) score += 800;
                else if (match(trackCleanAlbum, trackCleanTitle) || match(candCleanAlbum, trackCleanTitle)) score += 200;
            } else {
                score += 300;
            }

            score -= (int) (delta / 10L);
            score += Math.min(80, LrcLyrics.parse(candidate.lrc).lineCount());

            if (score > bestScore) {
                bestScore = score;
                bestLyrics = candidate.lrc;
            }
        }
        return bestLyrics;
    }

    static String cleanArtist(String artist) {
        if (artist == null) return "";
        return TOPIC_SUFFIX.matcher(artist.trim()).replaceAll("").trim();
    }

    static List<String> artistTokens(String cleanedArtist) {
        if (cleanedArtist == null || cleanedArtist.trim().isEmpty()) return Collections.emptyList();
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : ARTIST_SPLIT.split(cleanedArtist.trim())) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) tokens.add(trimmed);
        }
        return new ArrayList<>(tokens);
    }

    static String cleanTitle(String rawTitle, String cleanedArtist, String primaryArtist) {
        if (rawTitle == null) return "";
        String result = stripTitleNoise(rawTitle);
        int sep = result.indexOf(" - ");
        if (sep > 0) {
            String prefix = result.substring(0, sep).trim();
            String rest = result.substring(sep + 3).trim();
            if (!rest.isEmpty() && ((!cleanedArtist.isEmpty() && match(prefix, cleanedArtist))
                    || (!primaryArtist.isEmpty() && match(prefix, primaryArtist)))) {
                result = stripTitleNoise(rest);
            }
        }
        return result.isEmpty() ? rawTitle.trim() : result;
    }

    static String cleanAlbum(String rawAlbum) {
        if (rawAlbum == null || rawAlbum.trim().isEmpty()) return "";
        String stripped = stripNoiseSegments(rawAlbum.trim());
        return stripped.isEmpty() ? rawAlbum.trim() : stripped;
    }

    private static String stripTitleNoise(String input) {
        String value = stripNoiseSegments(input.trim());
        value = TRAILING_FEAT.matcher(value).replaceAll("").trim();
        return stripNoiseSegments(value);
    }

    private static String stripNoiseSegments(String input) {
        String value = input == null ? "" : input.trim();
        boolean changed;
        do {
            changed = false;
            Matcher brackets = BRACKET_SEGMENT.matcher(value);
            StringBuffer sb = new StringBuffer();
            while (brackets.find()) {
                String inner = firstNonEmpty(brackets.group(1), brackets.group(2), brackets.group(3), brackets.group(4));
                if (inner != null && NOISE_CONTENT.matcher(inner.trim()).matches()) {
                    brackets.appendReplacement(sb, "");
                    changed = true;
                } else {
                    brackets.appendReplacement(sb, Matcher.quoteReplacement(brackets.group(0)));
                }
            }
            brackets.appendTail(sb);
            value = sb.toString().replaceAll("\\s+", " ").trim();

            Matcher dash = DASH_SUFFIX.matcher(value);
            if (dash.find() && NOISE_CONTENT.matcher(dash.group(1).trim()).matches()) {
                value = value.substring(0, dash.start()).trim();
                changed = true;
            }
        } while (changed);
        return value;
    }

    private static String stripVersionSegments(String input) {
        String value = input == null ? "" : input.trim();
        boolean changed;
        do {
            changed = false;
            Matcher brackets = BRACKET_SEGMENT.matcher(value);
            StringBuffer sb = new StringBuffer();
            while (brackets.find()) {
                String inner = firstNonEmpty(brackets.group(1), brackets.group(2), brackets.group(3), brackets.group(4));
                if (inner != null && versionFlags(inner, "") != 0) {
                    brackets.appendReplacement(sb, "");
                    changed = true;
                } else {
                    brackets.appendReplacement(sb, Matcher.quoteReplacement(brackets.group(0)));
                }
            }
            brackets.appendTail(sb);
            value = sb.toString().replaceAll("\\s+", " ").trim();

            Matcher dash = DASH_SUFFIX.matcher(value);
            if (dash.find() && versionFlags(dash.group(1), "") != 0) {
                value = value.substring(0, dash.start()).trim();
                changed = true;
            }
        } while (changed);
        return value.isEmpty() ? (input == null ? "" : input.trim()) : value;
    }

    static int versionFlags(String title, String album) {
        String combined = (title == null ? "" : title) + "\n" + (album == null ? "" : album);
        int flags = 0;
        if (LIVE_TAG.matcher(combined).find()) flags |= FLAG_LIVE;
        if (REMIX_TAG.matcher(combined).find()) flags |= FLAG_REMIX;
        if (ACOUSTIC_TAG.matcher(combined).find()) flags |= FLAG_ACOUSTIC;
        if (INST_TAG.matcher(combined).find()) flags |= FLAG_INST;
        if (SHORT_TAG.matcher(combined).find()) flags |= FLAG_SHORT;
        if (SPEED_TAG.matcher(combined).find()) flags |= FLAG_SPEED;
        return flags;
    }

    static boolean hasVideoTag(String title) {
        return title != null && VIDEO_TAG.matcher(title).find();
    }

    private static boolean hasCjkOrKana(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    private static boolean artistMatches(String rawTrack, String cleanTrack, String primaryTrack, List<String> trackTokens,
                                         String rawCand, String cleanCand, String primaryCand, List<String> candTokens) {
        if (match(rawTrack, rawCand) || match(cleanTrack, cleanCand)) return true;
        if (!primaryTrack.isEmpty() && !primaryCand.isEmpty() && match(primaryTrack, primaryCand)) return true;
        if (trackTokens.isEmpty() || candTokens.isEmpty()) return false;
        Set<String> trackSet = normalizeTokens(trackTokens);
        Set<String> candSet = normalizeTokens(candTokens);
        Set<String> smaller = trackSet.size() <= candSet.size() ? trackSet : candSet;
        Set<String> larger = trackSet.size() <= candSet.size() ? candSet : trackSet;
        for (String token : smaller) {
            if (token.length() < 2 || !larger.contains(token)) return false;
        }
        return true;
    }

    private static Set<String> normalizeTokens(List<String> tokens) {
        Set<String> normalized = new LinkedHashSet<>(tokens.size());
        for (String token : tokens) {
            String norm = normalize(token);
            if (!norm.isEmpty()) normalized.add(norm);
        }
        return normalized;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String collapsed = value.trim().replaceAll("\\s+", " ");
        return Normalizer.normalize(collapsed, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean match(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    static final class Candidate {
        final String title, artist, album, lrc;
        final long duration;
        final boolean exactId;

        Candidate(String title, String artist, String album, long duration, String lrc) {
            this(title, artist, album, duration, lrc, false);
        }

        Candidate(String title, String artist, String album, long duration, String lrc, boolean exactId) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album == null ? "" : album;
            this.duration = duration;
            this.lrc = lrc;
            this.exactId = exactId;
        }

        String label() {
            return title + " · " + artist + "\n" + album + (duration > 0
                    ? String.format(Locale.ROOT, " · %d:%02d", duration / 60000, duration / 1000 % 60) : "");
        }
    }

    enum Status {
        IDLE(null), WAITING("等待查找歌词…"), SEARCHING("正在查找歌词…"),
        NOT_FOUND("未找到同步歌词"), CHOOSE_VERSION("有候选歌词，请选版本"),
        INCOMPLETE_TRACK("曲目信息不足"), UNAVAILABLE("歌词库暂不可用"), TIMED_OUT("查找歌词超时");

        final String label;

        Status(String label) { this.label = label; }
    }

    private static final class Attempt {
        final Status status;
        final long at = SystemClock.elapsedRealtime();

        Attempt(Status status) { this.status = status; }
    }

    private static final class PendingSearch {
        final List<Consumer<SearchResult>> listeners = new ArrayList<>();
        Future<?> work;
        Runnable timeout;
    }

    static final class SearchResult {
        final List<Candidate> candidates;
        final Status failure;
        final long receivedAt = SystemClock.elapsedRealtime();

        SearchResult(List<Candidate> candidates, Status failure) {
            this.candidates = candidates;
            this.failure = failure;
        }

        Status status(String matched) {
            if (failure != null) return failure;
            if (matched != null) return Status.IDLE;
            return candidates.isEmpty() ? Status.NOT_FOUND : Status.CHOOSE_VERSION;
        }

        static SearchResult failure(Status status) {
            return new SearchResult(Collections.emptyList(), status);
        }

        static SearchResult unavailable() {
            return failure(Status.UNAVAILABLE);
        }
    }
}
