package com.foldstandby.clock;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;

final class MusicLyrics {
    static final String SHOW = "showLyrics";

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

    private static String field(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() + ":" + text;
    }
}
