package com.foldstandby.clock;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import cn.lyric.getter.api.data.LyricData;

final class MusicLyrics {
    static final String SHOW = "showLyrics";
    static final String ONLINE = "onlineMusicLyrics";
    static final boolean ONLINE_DEFAULT = true;
    static final String FONT_SIZE = "lyricsFontSize";
    static final String POSITION = "lyricsPosition";
    static final int MIN_FONT_SIZE = 60;
    static final int MAX_FONT_SIZE = 160;
    static final int MAX_OFFSET = 10000;

    static final int FLAG_ALWAYS_SHOW_TICKER = 0x01000000;
    static final int FLAG_ONLY_UPDATE_TICKER = 0x02000000;
    static final int LYRIC_TICKER_FLAGS = FLAG_ALWAYS_SHOW_TICKER | FLAG_ONLY_UPDATE_TICKER;

    private static final int MAX_LIVE_LINES = 32;
    private static final int MAX_LIVE_LINE_CHARS = 300;
    private static final long TRACK_CHANGE_PRUNE_MS = 250L;
    private static final long LIVE_SOURCE_TTL_MS = 30 * 60_000L;

    static final String[] LYRIC_BROADCAST_ACTIONS = {
            "Lyric_Data",
            "statusbar.lyric.update",
            "com.statusbar.lyric",
            "com.netease.cloudmusic.UPDATE_STATUS_LYRIC",
            "cn.kuwo.player.im.lyric",
            "com.kugou.android.music.lyric"
    };

    private static final String[] WHOLE_META_KEYS = {
            "ucar.media.metadata.LYRICS_WHOLE",
            "android.media.metadata.LYRIC",
            "android.media.metadata.LYRICS",
            "vivomusicmix.extra.key.lyric",
            "com.lge.media.metadata.LYRIC",
            "lyric",
            "lyrics",
            "lrc",
            "lyricInfo"
    };
    private static final String[] WHOLE_EXTRAS_KEYS = {
            "ucar.media.metadata.LYRICS_WHOLE",
            "lyric",
            "lyrics",
            "lrc",
            "lyricInfo"
    };
    private static final String[] SINGLE_META_KEYS = {
            "ucar.media.metadata.LYRICS_LINE",
            "lyric_line",
            "current_lyric"
    };
    private static final String[] SINGLE_FALLBACK_META_KEYS = {
            "android.media.metadata.LYRIC",
            "lyric"
    };
    private static final String[] SINGLE_EXTRAS_KEYS = {
            "ucar.media.metadata.LYRICS_LINE",
            "lyric_line",
            "current_lyric",
            "lyric"
    };
    private static final String[] PRIORITY_BROADCAST_KEYS = {
            "com.netease.cloudmusic.STATUS_LYRIC_TEXT",
            "lyric",
            "status_lyric",
            "extra_lyric",
            "text",
            "content",
            "line",
            "lyricContent",
            "Data"
    };
    private static final Set<String> IGNORED_EXTRA_KEYS = new HashSet<>(Arrays.asList(
            "packageName", "package", "pkg", "type", "action", "id", "songId", "musicId",
            "trackId", "color", "icon", "base64Icon", "title", "artist", "album", "need_hide"
    ));
    private static final Pattern STATUS_BANNER_PATTERN = Pattern.compile(
            "(?i).*(?:正在播放|后台播放|暂停播放|准备播放|恢复播放|播放音乐|正在缓冲|now\\s+playing|is\\s+playing).*");
    private static final Set<String> PLAYER_APP_NAMES = new HashSet<>(Arrays.asList(
            "网易云音乐", "qq音乐", "酷狗音乐", "酷我音乐", "汽水音乐", "波点音乐", "咪咕音乐",
            "椒盐音乐", "洛雪音乐", "apple music", "spotify", "youtube music", "amazon music"
    ));
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

    private static final Map<String, SessionState> SESSIONS = new HashMap<>();
    private static final Set<Runnable> LIVE_OBSERVERS = new HashSet<>();
    private static final AtomicBoolean NOTIFY_PENDING = new AtomicBoolean(false);
    private static final Object RECEIVER_LOCK = new Object();
    private static boolean serviceConnected;
    private static BroadcastReceiver lyricReceiver;
    private static Context registeredContext;

    private MusicLyrics() {}

    enum Position {
        AUTO("auto", "自动"), TOP("top", "上方"), BOTTOM("bottom", "下方"), LEFT("left", "左侧"), RIGHT("right", "右侧");

        static final Position[] OPTIONS = values();
        final String id, label;

        Position(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    static Position position(Scene.Component component) {
        String saved = component.options.optString(POSITION);
        for (Position value : Position.OPTIONS) if (value.id.equals(saved)) return value;
        return Position.AUTO;
    }

    static int fontSize(Scene.Component component) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, component.options.optInt(FONT_SIZE, 100)));
    }

    static SharedPreferences library(Context context) {
        return context.getSharedPreferences("music_lyrics", Context.MODE_PRIVATE);
    }

    static String key(MediaController player, MediaMetadata metadata) {
        if (player == null || metadata == null) return null;
        String id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id != null && !id.isEmpty()) return player.getPackageName() + "/id/" + field(id);
        String name = title(player, metadata);
        if (name == null || name.trim().isEmpty()) return null;
        return player.getPackageName() + "/track/" + field(name)
                + field(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                + field(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
    }

    static String title(MediaMetadata metadata) {
        if (metadata == null) return null;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        return title != null && !title.trim().isEmpty() ? title : metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    static String title(MediaController player, MediaMetadata metadata) {
        if (metadata == null) return null;
        if (player == null) return title(metadata);
        String stabilized = syncSession(player, metadata);
        return !isBlank(stabilized) ? stabilized : title(metadata);
    }

    /** Probes standard, infotainment, and any lyric or lrc metadata/extras across all player packages. */
    @SuppressLint("WrongConstant")
    static String playerLyrics(MediaController player, MediaMetadata metadata) {
        if (metadata != null) {
            for (String key : WHOLE_META_KEYS) {
                CharSequence value = metadata.getText(key);
                if (value == null) value = metadata.getString(key);
                String extracted = extractWholeLrc(value == null ? null : value.toString());
                if (extracted != null) return extracted;
            }
            for (String key : metadata.keySet()) {
                if (!isLyricKeyName(key)) continue;
                CharSequence value = metadata.getText(key);
                if (value == null) value = metadata.getString(key);
                String extracted = extractWholeLrc(value == null ? null : value.toString());
                if (extracted != null) return extracted;
            }
        }
        Bundle extras = player == null ? null : player.getExtras();
        if (extras != null) {
            for (String key : WHOLE_EXTRAS_KEYS) {
                CharSequence value = extras.getCharSequence(key);
                if (value == null) value = extras.getString(key);
                String extracted = extractWholeLrc(value == null ? null : value.toString());
                if (extracted != null) return extracted;
            }
            for (String key : extras.keySet()) {
                if (!isLyricKeyName(key)) continue;
                CharSequence value = extras.getCharSequence(key);
                if (value == null) value = extras.getString(key);
                String extracted = extractWholeLrc(value == null ? null : value.toString());
                if (extracted != null) return extracted;
            }
        }
        return null;
    }

    private static boolean isLyricKeyName(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("lyric") || lower.contains("lrc");
    }

    static String extractWholeLrc(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > LrcLyrics.MAX_BYTES) return null;
        String candidate = raw.trim();
        if (candidate.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(candidate);
                candidate = firstNonEmpty(json.optString("lyric"), json.optString("lyrics"));
                if (candidate == null) return null;
                candidate = candidate.trim();
            } catch (JSONException ignored) {
                return null;
            }
        }
        if (candidate.isEmpty() || candidate.length() > LrcLyrics.MAX_BYTES) return null;
        return LrcLyrics.parse(candidate).hasLines() ? candidate : null;
    }

    private static String syncSession(MediaController player, MediaMetadata metadata) {
        if (player == null || metadata == null) return null;
        PlaybackState state = player.getPlaybackState();
        long position = state == null ? -1L : state.getPosition();
        return syncMetadata(player.getPackageName(), metadata, player.getExtras(), position);
    }

    static String syncMetadata(String pkg, MediaMetadata metadata, long playbackPosition) {
        return syncMetadata(pkg, metadata, null, playbackPosition);
    }

    @SuppressLint("WrongConstant")
    static String syncMetadata(String pkg, MediaMetadata metadata, Bundle extras, long playbackPosition) {
        if (isBlank(pkg) || metadata == null) return title(metadata);
        String rawTitle = trimOrNull(title(metadata));
        String artist = trimOrEmpty(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        String album = trimOrEmpty(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String mediaId = trimOrEmpty(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        String identity = trackIdentity(pkg, mediaId, artist, album, duration);
        long now = SystemClock.elapsedRealtime();

        String canonical;
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.computeIfAbsent(pkg, key -> new SessionState());
            if (identity == null) {
                if (!Objects.equals(rawTitle, state.canonicalTitle) || state.identity != null) {
                    state.identity = null;
                    state.canonicalTitle = rawTitle;
                    state.artist = artist;
                    state.album = album;
                    state.duration = duration;
                    state.bluetoothLyrics = false;
                    state.initialUnflaggedTicker = null;
                    state.lastPosition = playbackPosition;
                    pruneOldLines(state, now);
                }
            } else if (!identity.equals(state.identity)
                    || (mediaId.isEmpty() && playbackPosition >= 0 && playbackPosition < 3000
                    && state.lastPosition > 15000 && !Objects.equals(rawTitle, state.canonicalTitle))) {
                state.identity = identity;
                state.canonicalTitle = rawTitle;
                state.artist = artist;
                state.album = album;
                state.duration = duration;
                state.bluetoothLyrics = false;
                state.initialUnflaggedTicker = null;
                state.lastPosition = playbackPosition;
                pruneOldLines(state, now);
            } else {
                if (playbackPosition >= 0) state.lastPosition = playbackPosition;
                if (isBlank(state.canonicalTitle) && !isBlank(rawTitle)) {
                    state.canonicalTitle = rawTitle;
                } else if (duration > 0 && !isBlank(rawTitle) && !rawTitle.equals(state.canonicalTitle)) {
                    state.bluetoothLyrics = true;
                    if (!isMetadataBanner(rawTitle, state.canonicalTitle, artist, album)) {
                        appendLineLocked(state, rawTitle, now);
                    }
                }
            }

            String singleLine = extractSingleLine(metadata, extras, state.canonicalTitle, artist, album);
            if (singleLine != null) {
                appendLineLocked(state, singleLine, now);
            }
            canonical = state.canonicalTitle;
        }
        return canonical;
    }

    @SuppressLint("WrongConstant")
    private static String extractSingleLine(MediaMetadata metadata, Bundle extras,
                                            String knownTitle, String artist, String album) {
        for (String key : SINGLE_META_KEYS) {
            CharSequence value = metadata.getText(key);
            if (value == null) value = metadata.getString(key);
            String line = validSingleLine(value, knownTitle, artist, album);
            if (line != null) return line;
        }
        for (String key : SINGLE_FALLBACK_META_KEYS) {
            CharSequence value = metadata.getText(key);
            if (value == null) value = metadata.getString(key);
            String line = validSingleLine(value, knownTitle, artist, album);
            if (line != null) return line;
        }
        for (String key : metadata.keySet()) {
            if (!isLyricKeyName(key)) continue;
            CharSequence value = metadata.getText(key);
            if (value == null) value = metadata.getString(key);
            String line = validSingleLine(value, knownTitle, artist, album);
            if (line != null) return line;
        }
        if (extras != null) {
            for (String key : SINGLE_EXTRAS_KEYS) {
                CharSequence value = extras.getCharSequence(key);
                if (value == null) value = extras.getString(key);
                String line = validSingleLine(value, knownTitle, artist, album);
                if (line != null) return line;
            }
            for (String key : extras.keySet()) {
                if (!isLyricKeyName(key)) continue;
                CharSequence value = extras.getCharSequence(key);
                if (value == null) value = extras.getString(key);
                String line = validSingleLine(value, knownTitle, artist, album);
                if (line != null) return line;
            }
        }
        return null;
    }

    private static String validSingleLine(CharSequence raw, String knownTitle, String artist, String album) {
        if (raw == null) return null;
        String text = raw.toString().trim();
        if (text.isEmpty() || text.length() > 240) return null;
        if (isNonLyricToken(text)) return null;
        if (LrcLyrics.parse(text).hasLines()) return null;
        return isMetadataBanner(text, knownTitle, artist, album) ? null : text;
    }

    private static boolean isNonLyricToken(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "false".equals(lower) || "null".equals(lower)) return true;
        if (DIGITS_ONLY.matcher(lower).matches()) return true;
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("content://") || lower.startsWith("file://")
                || (lower.startsWith("com.") && !lower.contains(" "));
    }

    private static String trackIdentity(String pkg, String mediaId, String artist, String album, long duration) {
        if (!mediaId.isEmpty()) return pkg + "|id|" + mediaId;
        if (duration > 0 && (!artist.isEmpty() || !album.isEmpty())) {
            return pkg + "|meta|" + artist + "|" + album + "|" + duration;
        }
        return null;
    }

    static boolean ingestNotification(String pkg, int flags, CharSequence ticker,
                                      boolean hasMediaSession, String activePkg, MediaMetadata activeMeta) {
        if (isBlank(pkg) || ticker == null) return false;
        boolean flagged = (flags & LYRIC_TICKER_FLAGS) != 0;
        boolean activeMedia = pkg.equals(activePkg) || hasMediaSession;
        if (!flagged && !activeMedia) return false;

        String line = ticker.toString().trim();
        if (line.isEmpty() || line.length() > MAX_LIVE_LINE_CHARS || isNonLyricToken(line)) return false;

        long now = SystemClock.elapsedRealtime();
        boolean changed;
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.get(pkg);
            String knownTitle = state == null ? null : state.canonicalTitle;
            String knownArtist = state == null ? null : state.artist;
            String knownAlbum = state == null ? null : state.album;
            if (pkg.equals(activePkg) && activeMeta != null) {
                if (isBlank(knownTitle)) knownTitle = title(activeMeta);
                if (isBlank(knownArtist)) knownArtist = activeMeta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (isBlank(knownAlbum)) knownAlbum = activeMeta.getString(MediaMetadata.METADATA_KEY_ALBUM);
            }
            if (isMetadataBanner(line, knownTitle, knownArtist, knownAlbum)) return false;
            if (state == null) {
                state = new SessionState();
                SESSIONS.put(pkg, state);
            }
            if (!flagged) {
                if (state.initialUnflaggedTicker == null) {
                    state.initialUnflaggedTicker = line;
                    return false;
                }
                if (line.equals(state.initialUnflaggedTicker)) {
                    return false;
                }
            }
            state.externalSourceAt = now;
            changed = appendLineLocked(state, line, now);
        }
        if (changed) notifyLiveObservers();
        return true;
    }

    static void clearNotificationLyrics(String pkg, int flags) {
        if (isBlank(pkg) || (flags & LYRIC_TICKER_FLAGS) == 0) return;
        boolean changed = false;
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.get(pkg);
            if (state != null && !state.lines.isEmpty()) {
                state.lines.clear();
                changed = true;
            }
        }
        if (changed) notifyLiveObservers();
    }

    @SuppressWarnings("deprecation")
    static boolean ingestBroadcast(Intent intent, String fallbackPkg) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!isSupportedBroadcastAction(action)) return false;

        if ("Lyric_Data".equals(action)) {
            LyricData data = null;
            try {
                intent.setExtrasClassLoader(LyricData.class.getClassLoader());
                data = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? intent.getParcelableExtra("Data", LyricData.class)
                        : intent.getParcelableExtra("Data");
            } catch (RuntimeException ignored) { }
            if (data != null) {
                String pkg = firstNonEmpty(data.packageName(), fallbackPkg);
                if (isBlank(pkg)) return false;
                if (data.type == LyricData.TYPE_STOP || isBlank(data.lyric)) {
                    clearPackageLines(pkg);
                    return true;
                }
                return appendExternalLine(pkg, data.lyric);
            }
        }

        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (RuntimeException badParcel) {
            return false;
        }
        String inferredPkg = inferPackageFromAction(action);
        String pkg = firstNonEmpty(
                getCharSequenceString(extras, "packageName"),
                getCharSequenceString(extras, "package"),
                inferredPkg,
                fallbackPkg);
        if (isBlank(pkg)) return false;

        if (extras != null && (extras.getBoolean("stop", false)
                || "STOP".equalsIgnoreCase(getCharSequenceString(extras, "type")))) {
            clearPackageLines(pkg);
            return true;
        }

        if (extras != null && extras.getBoolean("need_hide", false)) {
            return false;
        }
        String line = extractBroadcastLyric(extras, pkg);
        return line != null && appendExternalLine(pkg, line);
    }

    private static boolean isSupportedBroadcastAction(String action) {
        if (action == null) return false;
        for (String supported : LYRIC_BROADCAST_ACTIONS) {
            if (supported.equals(action)) return true;
        }
        return false;
    }

    private static String inferPackageFromAction(String action) {
        if (action == null) return null;
        if (action.startsWith("com.netease.cloudmusic")) return "com.netease.cloudmusic";
        if (action.startsWith("cn.kuwo.player")) return "cn.kuwo.player";
        if (action.startsWith("com.kugou.android")) return "com.kugou.android";
        return null;
    }

    private static String extractBroadcastLyric(Bundle extras, String pkg) {
        if (extras == null) return null;
        String knownTitle, knownArtist, knownAlbum;
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.get(pkg);
            knownTitle = state == null ? null : state.canonicalTitle;
            knownArtist = state == null ? null : state.artist;
            knownAlbum = state == null ? null : state.album;
        }
        for (String key : PRIORITY_BROADCAST_KEYS) {
            String candidate = getCharSequenceString(extras, key);
            if (validBroadcastLine(candidate, knownTitle, knownArtist, knownAlbum)) return candidate.trim();
        }
        for (String key : extras.keySet()) {
            if (key == null || IGNORED_EXTRA_KEYS.contains(key)) continue;
            Object value = extras.get(key);
            if (value instanceof CharSequence) {
                String candidate = value.toString();
                if (validBroadcastLine(candidate, knownTitle, knownArtist, knownAlbum)) return candidate.trim();
            } else if (value instanceof Bundle) {
                Bundle nested = (Bundle) value;
                for (String nestedKey : nested.keySet()) {
                    if (nestedKey == null || IGNORED_EXTRA_KEYS.contains(nestedKey)) continue;
                    Object nestedVal = nested.get(nestedKey);
                    if (nestedVal instanceof CharSequence) {
                        String candidate = nestedVal.toString();
                        if (validBroadcastLine(candidate, knownTitle, knownArtist, knownAlbum)) return candidate.trim();
                    }
                }
            }
        }
        return null;
    }

    private static boolean validBroadcastLine(String raw, String knownTitle, String knownArtist, String knownAlbum) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LIVE_LINE_CHARS) return false;
        if (isNonLyricToken(trimmed)) return false;
        return !isMetadataBanner(trimmed, knownTitle, knownArtist, knownAlbum);
    }

    private static String getCharSequenceString(Bundle extras, String key) {
        if (extras == null || key == null) return null;
        try {
            CharSequence cs = extras.getCharSequence(key);
            return cs == null ? null : cs.toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void clearPackageLines(String pkg) {
        boolean changed = false;
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.get(pkg);
            if (state != null && !state.lines.isEmpty()) {
                state.lines.clear();
                changed = true;
            }
        }
        if (changed) notifyLiveObservers();
    }

    private static boolean appendExternalLine(String pkg, String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.length() > MAX_LIVE_LINE_CHARS || isNonLyricToken(line)) return false;
        long now = SystemClock.elapsedRealtime();
        boolean changed;
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.computeIfAbsent(pkg, key -> new SessionState());
            if (isMetadataBanner(line, state.canonicalTitle, state.artist, state.album)) return false;
            state.externalSourceAt = now;
            changed = appendLineLocked(state, line, now);
        }
        if (changed) notifyLiveObservers();
        return true;
    }

    static LrcLyrics liveLyrics(MediaController player, MediaMetadata metadata) {
        if (player == null || metadata == null) return null;
        syncSession(player, metadata);
        return liveLyricsForPackage(player.getPackageName());
    }

    static LrcLyrics liveLyricsForPackage(String pkg) {
        if (isBlank(pkg)) return null;
        List<String> snapshot;
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.get(pkg);
            if (state == null || state.lines.isEmpty()) return null;
            snapshot = new ArrayList<>(state.lines.size());
            for (LineEntry entry : state.lines) snapshot.add(entry.text);
        }
        LrcLyrics live = LrcLyrics.live(snapshot);
        return live.hasLines() ? live : null;
    }

    static boolean hasRecentLiveSource(String pkg) {
        if (isBlank(pkg)) return false;
        long now = SystemClock.elapsedRealtime();
        synchronized (SESSIONS) {
            SessionState state = SESSIONS.get(pkg);
            if (state == null) return false;
            return state.bluetoothLyrics
                    || (state.externalSourceAt > 0 && now - state.externalSourceAt < LIVE_SOURCE_TTL_MS);
        }
    }

    static boolean isMetadataBanner(String candidate, String title, String artist, String album) {
        String normCandidate = normalizeBanner(candidate);
        if (normCandidate.isEmpty() || candidate.trim().length() > MAX_LIVE_LINE_CHARS) return true;
        if (PLAYER_APP_NAMES.contains(normCandidate) || STATUS_BANNER_PATTERN.matcher(normCandidate).matches()) {
            return true;
        }
        String normTitle = normalizeBanner(title);
        String normArtist = normalizeBanner(artist);
        String normAlbum = normalizeBanner(album);
        if (!normTitle.isEmpty() && normCandidate.equals(normTitle)) return true;
        if (!normArtist.isEmpty() && normCandidate.equals(normArtist)) return true;
        if (!normAlbum.isEmpty() && normCandidate.equals(normAlbum)) return true;
        if (!normTitle.isEmpty() && !normArtist.isEmpty()) {
            return normCandidate.equals(normTitle + " - " + normArtist)
                    || normCandidate.equals(normArtist + " - " + normTitle)
                    || normCandidate.equals(normTitle + "-" + normArtist)
                    || normCandidate.equals(normArtist + "-" + normTitle)
                    || normCandidate.equals(normTitle + " / " + normArtist);
        }
        return false;
    }

    private static String normalizeBanner(String value) {
        if (value == null) return "";
        String collapsed = value.trim().replaceAll("\\s+", " ");
        return Normalizer.normalize(collapsed, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean appendLineLocked(SessionState state, String line, long now) {
        if (!state.lines.isEmpty() && state.lines.get(state.lines.size() - 1).text.equals(line)) {
            return false;
        }
        state.lines.add(new LineEntry(line, now));
        while (state.lines.size() > MAX_LIVE_LINES) state.lines.remove(0);
        return true;
    }

    private static void pruneOldLines(SessionState state, long now) {
        Iterator<LineEntry> iterator = state.lines.iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().at > TRACK_CHANGE_PRUNE_MS) iterator.remove();
        }
    }

    static void observeLive(Context context, Runnable observer) {
        if (context == null || observer == null) return;
        synchronized (RECEIVER_LOCK) {
            LIVE_OBSERVERS.add(observer);
            updateReceiverRegistrationLocked(context.getApplicationContext());
        }
    }

    static void forgetLive(Context context, Runnable observer) {
        if (observer == null) return;
        synchronized (RECEIVER_LOCK) {
            LIVE_OBSERVERS.remove(observer);
            updateReceiverRegistrationLocked(context == null ? null : context.getApplicationContext());
        }
    }

    static void setServiceConnected(Context context, boolean connected) {
        synchronized (RECEIVER_LOCK) {
            serviceConnected = connected;
            updateReceiverRegistrationLocked(context == null ? null : context.getApplicationContext());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void updateReceiverRegistrationLocked(Context appContext) {
        boolean needed = serviceConnected || !LIVE_OBSERVERS.isEmpty();
        if (needed && lyricReceiver == null && appContext != null) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    MediaController active = DeskData.media;
                    String activePkg = active == null ? null : active.getPackageName();
                    ingestBroadcast(intent, activePkg);
                }
            };
            IntentFilter filter = new IntentFilter();
            for (String action : LYRIC_BROADCAST_ACTIONS) filter.addAction(action);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appContext.registerReceiver(receiver, filter);
                }
                lyricReceiver = receiver;
                registeredContext = appContext;
            } catch (RuntimeException ignored) { }
        } else if (!needed && lyricReceiver != null && registeredContext != null) {
            try {
                registeredContext.unregisterReceiver(lyricReceiver);
            } catch (IllegalArgumentException ignored) { }
            lyricReceiver = null;
            registeredContext = null;
        }
    }

    private static void notifyLiveObservers() {
        if (!NOTIFY_PENDING.compareAndSet(false, true)) return;
        DeskData.MAIN.post(() -> {
            NOTIFY_PENDING.set(false);
            List<Runnable> observers;
            synchronized (RECEIVER_LOCK) {
                observers = new ArrayList<>(LIVE_OBSERVERS);
            }
            for (Runnable observer : observers) observer.run();
        });
    }

    static void resetForChecks() {
        synchronized (SESSIONS) {
            SESSIONS.clear();
        }
    }

    static int offset(Context context, String key) {
        return key == null ? 0 : library(context).getInt("offset/" + key, 0);
    }

    static void setOffset(Context context, String key, int offset) {
        library(context).edit().putInt("offset/" + key, offset).apply();
    }

    static String selectedOnlineKey(String key) {
        return "selectedOnline/" + key;
    }

    static final class Track {
        final String key, title, artist, album, signature;
        final long duration;

        Track(String key, String title, String artist, String album, long duration) {
            this.key = key;
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
            this.album = album == null ? "" : album.trim();
            this.duration = duration;
            this.signature = field(key) + field(this.title) + field(this.artist) + field(this.album) + duration;
        }

        boolean searchable() {
            return !title.isEmpty() && !artist.isEmpty() && title.length() <= 250 && artist.length() <= 250;
        }
    }

    static Track track(MediaMetadata metadata, String key) {
        return track(null, metadata, key);
    }

    static Track track(MediaController player, MediaMetadata metadata, String key) {
        if (key == null || metadata == null) return null;
        return new Track(key, title(player, metadata), metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    private static String field(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() + ":" + text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimOrNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return null;
    }

    private static final class LineEntry {
        final String text;
        final long at;

        LineEntry(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    private static final class SessionState {
        String identity, canonicalTitle, artist, album, initialUnflaggedTicker;
        long duration, lastPosition = -1L, externalSourceAt;
        boolean bluetoothLyrics;
        final List<LineEntry> lines = new ArrayList<>();
    }
}
