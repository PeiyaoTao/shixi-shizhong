package com.foldstandby.clock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;

final class MusicLyrics {
    static final String SHOW = "showLyrics";
    static final String ONLINE = "onlineMusicLyrics";
    static final int MAX_OFFSET = 10000;
    private static final String QQ_LYRICS = "ucar.media.metadata.LYRICS_WHOLE";

    private MusicLyrics() {}

    static SharedPreferences library(Context context) {
        return context.getSharedPreferences("music_lyrics", Context.MODE_PRIVATE);
    }

    static String key(MediaController player, MediaMetadata metadata) {
        if (player == null || metadata == null) return null;
        String id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id != null && !id.isEmpty()) return player.getPackageName() + "/id/" + field(id);
        String name = title(metadata);
        if (name == null || name.trim().isEmpty()) return null;
        return player.getPackageName() + "/track/" + field(name)
                + field(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                + field(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
    }

    static String title(MediaMetadata metadata) {
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        return title != null && !title.trim().isEmpty() ? title : metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    // QQ Music publishes this custom string in addition to Android's standard metadata keys.
    @SuppressLint("WrongConstant")
    static String playerLyrics(MediaController player, MediaMetadata metadata) {
        if (player == null || metadata == null || !"com.tencent.qqmusic".equals(player.getPackageName())) return null;
        CharSequence text = metadata.getText(QQ_LYRICS);
        return text != null && text.length() > 0 && text.length() <= LrcLyrics.MAX_BYTES
                ? text.toString() : null;
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
        if (key == null) return null;
        return new Track(key, title(metadata), metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    private static String field(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() + ":" + text;
    }
}
