package com.foldstandby.clock;

import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/** Player and lyrics geometry in pixels relative to the center of a music slot. */
final class MusicLayout {
    private static final float PLAYER_LEFT = 20f, PLAYER_TOP = 14f;
    private static final float PLAYER_WIDTH = 460f, PLAYER_HEIGHT = 294f;

    final RectF lyrics = new RectF();
    final RectF frame = new RectF();
    final Line current = new Line();
    final ContextLines before = new ContextLines(), after = new ContextLines();
    boolean compact, sideBySide, lyricsFirst;
    float playerLeft, playerTop, playerScale, lyricSize;

    void arrange(float width, float height, float density, float zoom, MusicLyrics.Position position,
                 int fontSize, int lyricLines) {
        compact = true;
        if (position == MusicLyrics.Position.AUTO) {
            position = width >= height * 1.55f
                    ? MusicLyrics.Position.RIGHT : MusicLyrics.Position.BOTTOM;
        }
        sideBySide = position == MusicLyrics.Position.LEFT || position == MusicLyrics.Position.RIGHT;
        lyricsFirst = position == MusicLyrics.Position.LEFT || position == MusicLyrics.Position.TOP;
        float baseScale = Math.min(width / 540f, height / 432f);
        float left = -width / 2f + Math.max(20f * baseScale, Math.min(16f * density, width * .045f));
        float right = -left;
        float top = -height / 2f + Math.max(16f * baseScale, Math.min(14f * density, height * .045f));
        float bottom = -top;
        frame.set(-width * .46f, -height * .46f, width * .46f, height * .46f);
        lyrics.setEmpty();
        if (lyricLines > 0) {
            float start = sideBySide ? left : top;
            float end = sideBySide ? right : bottom;
            float playerShare = (sideBySide ? .54f : .57f) + .18f * (zoom - 1f);
            if (lyricLines <= 2) playerShare += sideBySide ? .08f : .10f;
            float split = start + (end - start) * (lyricsFirst ? 1f - playerShare : playerShare);
            float halfGap = Math.min(8f * density, Math.min(width, height) * .021f);
            float lyricStart = lyricsFirst ? start : split + halfGap;
            float lyricEnd = lyricsFirst ? split - halfGap : end;
            float playerStart = lyricsFirst ? split + halfGap : start;
            float playerEnd = lyricsFirst ? end : split - halfGap;
            if (sideBySide) {
                lyrics.set(lyricStart, top, lyricEnd, bottom);
                left = playerStart;
                right = playerEnd;
            } else {
                lyrics.set(left, lyricStart, right, lyricEnd);
                top = playerStart;
                bottom = playerEnd;
            }
        }
        playerScale = Math.min((right - left) / PLAYER_WIDTH, (bottom - top) / PLAYER_HEIGHT)
                * Math.min(1f, .97f * zoom);
        float playerWidth = PLAYER_WIDTH * playerScale;
        float playerHeight = PLAYER_HEIGHT * playerScale;
        float contentLeft = (left + right - playerWidth) / 2f;
        float contentTop = (top + bottom - playerHeight) / 2f;
        if (!lyrics.isEmpty()) {
            if (sideBySide) contentLeft = lyricsFirst ? left : right - playerWidth;
            else contentTop = lyricsFirst ? top : bottom - playerHeight;
        }
        playerLeft = contentLeft - PLAYER_LEFT * playerScale;
        playerTop = contentTop - PLAYER_TOP * playerScale;
        float defaultSize = Math.min(Math.min(42f * density * zoom, lyrics.width() * .11f), lyrics.height() * .26f);
        lyricSize = Math.min(defaultSize * fontSize / 100f, lyrics.height() * .33f);
    }

    Layout.Alignment lyricAlignment() {
        if (!sideBySide) return Layout.Alignment.ALIGN_CENTER;
        return lyricsFirst ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
    }

    int hit(float x, float y, float density) {
        if (lyrics.contains(x, y)) return -1;
        return MediaControls.hit((x - playerLeft) / playerScale, (y - playerTop) / playerScale,
                MediaControls.COMPACT_Y, Math.min(56f, Math.max(44f, 24f * density / playerScale)));
    }

    static final class ContextLines {
        private final Line[] cache = new Line[8];
        final StaticLayout[] rows = new StaticLayout[cache.length];
        int count;
        float height;

        void prepare(LrcLyrics timeline, int index, int direction, float space, float size, float gap,
                     int width, Typeface typeface, Layout.Alignment alignment) {
            count = 0;
            height = 0;
            for (int cue = index + direction; cue >= 0 && cue < timeline.lineCount() && count < rows.length; cue += direction) {
                String line = timeline.lineAt(cue);
                if (line.isEmpty()) continue;
                if (cache[count] == null) cache[count] = new Line();
                StaticLayout text = cache[count].layout(line, size, width, typeface, alignment, 1);
                float rowHeight = text.getHeight() + gap;
                if (height + rowHeight > space) break;
                rows[count++] = text;
                height += rowHeight;
            }
        }

        void removeFarthest(float gap) {
            height -= rows[--count].getHeight() + gap;
        }
    }

    static final class Line {
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private String text;
        private StaticLayout content;
        private float size;
        private int width, lines;
        private Layout.Alignment alignment;

        StaticLayout layout(String value, float textSize, int availableWidth, Typeface typeface,
                            Layout.Alignment align, int maxLines) {
            if (value.isEmpty()) return null;
            if (!value.equals(text) || textSize != size || availableWidth != width
                    || align != alignment || maxLines != lines) {
                text = value;
                size = textSize;
                width = availableWidth;
                alignment = align;
                lines = maxLines;
                paint.setTypeface(typeface);
                paint.setTextSize(size);
                content = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                        .setAlignment(alignment)
                        .setIncludePad(false)
                        .setBreakStrategy(lines > 1 ? LineBreaker.BREAK_STRATEGY_BALANCED : LineBreaker.BREAK_STRATEGY_SIMPLE)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setMaxLines(lines)
                        .build();
            }
            return content;
        }
    }
}
