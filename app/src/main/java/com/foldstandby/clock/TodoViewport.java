package com.foldstandby.clock;

/** Positions either the list and navigation or the two editor columns around a separating fold. */
final class TodoViewport {
    static final class Area {
        final int left, top, width, height;

        Area(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        Area centered(int maximum) {
            int size = Math.min(width, maximum);
            return new Area(left + (width - size) / 2, top, size, height);
        }
    }

    final Area navigation, content;

    private TodoViewport(Area navigation, Area content) {
        this.navigation = navigation;
        this.content = content;
    }

    static TodoViewport fit(int width, int height, Area fold, float density, float fontScale, boolean landscape,
                            boolean editing) {
        int minNavigation = size((editing ? 234 : 178) * fontScale, density);
        int minContent = size((editing ? 258 : 390) * fontScale, density);
        int leftMaximum = size(editing ? 430 : 270, density);
        int rightMaximum = size(editing ? 620 : 980, density);
        int maximum = size(editing ? 720 : 980, density);
        Area available = new Area(0, 0, width, height);
        if (fold != null && fold.left < width && fold.left + fold.width > 0
                && fold.top < height && fold.top + fold.height > 0) {
            if (fold.height > fold.width) {
                int left = Math.max(0, fold.left);
                int right = Math.min(width, fold.left + fold.width);
                Area before = new Area(0, 0, left, height);
                Area after = new Area(right, 0, width - right, height);
                if (before.width >= minNavigation && after.width >= minContent) {
                    int leftWidth = Math.min(before.width, leftMaximum);
                    return new TodoViewport(new Area(left - leftWidth, 0, leftWidth, height), after.centered(rightMaximum));
                }
                available = before.width >= after.width ? before : after;
                // Each physical pane already has a distinct reading area.
                return new TodoViewport(null, available.centered(maximum));
            }
            int top = Math.max(0, fold.top);
            int bottom = Math.min(height, fold.top + fold.height);
            available = top >= height - bottom
                    ? new Area(0, 0, width, top) : new Area(0, bottom, width, height - bottom);
        }
        int gap = size(14, density);
        if (landscape && available.width >= minNavigation + gap + minContent) {
            int preferred = editing ? Math.min(leftMaximum, Math.round(available.width * .43f)) : size(226 * fontScale, density);
            int rail = Math.min(Math.max(preferred, minNavigation), available.width - gap - minContent);
            Area group = available.centered(rail + gap + rightMaximum);
            Area navigation = new Area(group.left, group.top, rail, group.height);
            Area content = new Area(group.left + rail + gap, group.top, group.width - rail - gap, group.height);
            return new TodoViewport(navigation, content);
        }
        return new TodoViewport(null, available.centered(maximum));
    }

    private static int size(float dp, float density) {
        return Math.round(dp * density);
    }
}
