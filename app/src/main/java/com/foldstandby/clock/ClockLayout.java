package com.foldstandby.clock;

import java.util.ArrayList;
import java.util.List;

/** Lays out a scene in logical landscape coordinates, without changing its saved slots. */
final class ClockLayout {
    static final class Area {
        final float left, top, right, bottom;

        Area(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float width() { return right - left; }
        float height() { return bottom - top; }

        Area inset(float amount) {
            return new Area(left + amount, top + amount, right - amount, bottom - amount);
        }
    }

    private static final float MIN_WIDTH = 128f;
    private static final float MIN_HEIGHT = 102f;

    static Area landscapeFold(Area fold, float width, float height) {
        if (fold == null || width >= height) return fold;
        // Convert window coordinates to those of the clockwise-rotated clock canvas.
        return new Area(fold.top, width - fold.right, fold.bottom, width - fold.left);
    }

    static List<Area[]> arrange(float width, float height, Area fold, int layout) {
        float gap = Math.min(width, height) * .025f;
        Area viewport = new Area(0, 0, width, height);
        Area[] regions = regions(viewport, fold, gap);
        int slots = layout == 0 ? 1 : layout == 3 ? 4 : 2;
        List<Area[]> pages = new ArrayList<>();
        for (int start = 0; start < slots;) {
            int count = slots - start;
            Area[] cells = fit(regions, count, layout, gap);
            while (count > 1 && quality(cells) < 1f) {
                cells = fit(regions, --count, layout, gap);
            }
            Area[] page = new Area[slots];
            System.arraycopy(cells, 0, page, start, count);
            pages.add(page);
            start += count;
        }
        return pages;
    }

    private static Area[] regions(Area window, Area fold, float gap) {
        if (fold == null) return new Area[]{window};
        boolean horizontal = fold.width() > fold.height();
        float start = horizontal ? fold.top : fold.left;
        float end = horizontal ? fold.bottom : fold.right;
        float extent = horizontal ? window.height() : window.width();
        float crossExtent = horizontal ? window.width() : window.height();
        float crossStart = horizontal ? fold.left : fold.top;
        float crossEnd = horizontal ? fold.right : fold.bottom;
        if (end < 0 || start > extent || crossEnd <= 0 || crossStart >= crossExtent) {
            return new Area[]{window};
        }
        float firstEdge = start - gap * .5f;
        float secondEdge = end + gap * .5f;
        Area first = horizontal
                ? new Area(0, 0, window.width(), firstEdge)
                : new Area(0, 0, firstEdge, window.height());
        Area second = horizontal
                ? new Area(0, secondEdge, window.width(), window.height())
                : new Area(secondEdge, 0, window.width(), window.height());
        if (firstEdge <= 0) return new Area[]{secondEdge < extent ? second : window};
        if (secondEdge >= extent) return new Area[]{first};
        return new Area[]{first, second};
    }

    private static Area[] fit(Area[] regions, int count, int layout, float gap) {
        if (regions.length == 1) return inRegion(regions[0], count, layout, gap);
        Area[] best = null;
        float bestScore = -1f;
        for (int firstCount = count; firstCount >= 0; firstCount--) {
            int secondCount = count - firstCount;
            Area[] first = inRegion(regions[0], firstCount, layout, gap);
            Area[] second = inRegion(regions[1], secondCount, layout, gap);
            float score = Math.min(quality(first), quality(second));
            // Use both halves once the components fit, and keep scene order across the fold.
            if (score >= 1f && firstCount > 0 && secondCount > 0) score += 1000f;
            if (score > bestScore) {
                bestScore = score;
                best = new Area[count];
                System.arraycopy(first, 0, best, 0, firstCount);
                System.arraycopy(second, 0, best, firstCount, secondCount);
            }
        }
        return best;
    }

    private static Area[] inRegion(Area region, int count, int layout, float gap) {
        if (count == 0) return new Area[0];
        float padding = Math.min(gap * 1.4f, Math.min(region.width(), region.height()) * .07f);
        Area bounds = region.inset(padding);
        if (count == 1) return new Area[]{bounds};
        float cellGap = Math.min(gap, Math.min(bounds.width(), bounds.height()) / (count * 2f));
        if (count == 2) {
            float firstShare = layout == 2 ? .62f : .5f;
            boolean horizontal = bounds.width() >= bounds.height();
            Area[] preferred = pair(bounds, cellGap, firstShare, horizontal);
            if (quality(preferred) >= 1f) return preferred;
            Area[] alternate = pair(bounds, cellGap, firstShare, !horizontal);
            return quality(alternate) > quality(preferred) ? alternate : preferred;
        }
        Area[] grid = grid(bounds, count, 2, cellGap);
        Area[] row = grid(bounds, count, count, cellGap);
        if (bounds.width() >= bounds.height() * 2f && quality(row) >= 1f) return row;
        return quality(grid) >= quality(row) ? grid : row;
    }

    private static Area[] pair(Area b, float gap, float firstShare, boolean horizontal) {
        if (horizontal) {
            float split = b.left + (b.width() - gap) * firstShare;
            return new Area[]{new Area(b.left, b.top, split, b.bottom),
                    new Area(split + gap, b.top, b.right, b.bottom)};
        }
        float split = b.top + (b.height() - gap) * firstShare;
        return new Area[]{new Area(b.left, b.top, b.right, split),
                new Area(b.left, split + gap, b.right, b.bottom)};
    }

    private static Area[] grid(Area b, int count, int columns, float gap) {
        int rows = (count + columns - 1) / columns;
        float width = (b.width() - (columns - 1) * gap) / columns;
        float height = (b.height() - (rows - 1) * gap) / rows;
        Area[] cells = new Area[count];
        for (int i = 0; i < count; i++) {
            float left = b.left + (i % columns) * (width + gap);
            float top = b.top + (i / columns) * (height + gap);
            cells[i] = new Area(left, top, left + width, top + height);
        }
        return cells;
    }

    private static float quality(Area[] cells) {
        float score = Float.POSITIVE_INFINITY;
        for (Area cell : cells) {
            score = Math.min(score, Math.min(cell.width() / MIN_WIDTH, cell.height() / MIN_HEIGHT));
        }
        return score;
    }

    private ClockLayout() {}
}
