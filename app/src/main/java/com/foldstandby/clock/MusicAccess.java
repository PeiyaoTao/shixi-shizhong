package com.foldstandby.clock;

import android.app.Notification;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Reads MediaSession and active media player lyric ticker notifications only;
 * unrelated app notifications are ignored.
 */
public class MusicAccess extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        MusicLyrics.setServiceConnected(getApplicationContext(), true);
    }

    @Override
    public void onListenerDisconnected() {
        MusicLyrics.setServiceConnected(getApplicationContext(), false);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        boolean hasLyricExtra = extras != null
                && (extras.containsKey("ticker_icon")
                || extras.containsKey("ticker_icon_switch")
                || extras.containsKey("status_bar_lyric"));
        int effectiveFlags = notification.flags | (hasLyricExtra ? MusicLyrics.FLAG_ALWAYS_SHOW_TICKER : 0);
        if ((effectiveFlags & MusicLyrics.LYRIC_TICKER_FLAGS) == 0 && notification.tickerText == null) return;
        if (notification.tickerText == null) {
            MusicLyrics.clearNotificationLyrics(sbn.getPackageName(), effectiveFlags);
            return;
        }
        boolean hasSession = extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        MediaController active = DeskData.media;
        String activePkg = active == null ? null : active.getPackageName();
        MediaMetadata activeMeta = active == null ? null : active.getMetadata();
        MusicLyrics.ingestNotification(sbn.getPackageName(), effectiveFlags,
                notification.tickerText, hasSession, activePkg, activeMeta);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification notification = sbn.getNotification();
        if (notification != null) {
            Bundle extras = notification.extras;
            boolean hasLyricExtra = extras != null
                    && (extras.containsKey("ticker_icon")
                    || extras.containsKey("ticker_icon_switch")
                    || extras.containsKey("status_bar_lyric"));
            int effectiveFlags = notification.flags | (hasLyricExtra ? MusicLyrics.FLAG_ALWAYS_SHOW_TICKER : 0);
            MusicLyrics.clearNotificationLyrics(sbn.getPackageName(), effectiveFlags);
        }
    }
}
