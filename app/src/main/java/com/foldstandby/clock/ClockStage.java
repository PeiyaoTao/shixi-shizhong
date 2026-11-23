package com.foldstandby.clock;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Fits the landscape clock even if the system supplies a portrait window. */
final class ClockStage extends FrameLayout {
    final FrameLayout content;
    private final ClockFace face;
    private final int[] windowPosition = new int[2];
    private Insets insets = Insets.NONE;
    private Rect fold;

    ClockStage(Context context, ClockFace face) {
        super(context);
        this.face = face;
        setClipChildren(false);
        content = new FrameLayout(context);
        content.addView(face, new FrameLayout.LayoutParams(-1, -1));
        addView(content);
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets value) {
        Insets safe = Insets.max(value.getInsets(WindowInsets.Type.systemBars()),
                value.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout()));
        if (!safe.equals(insets)) {
            insets = safe;
            requestLayout();
        }
        return value;
    }

    void setFold(Rect bounds) {
        fold = bounds == null ? null : new Rect(bounds);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        int safeWidth = Math.max(1, width - insets.left - insets.right);
        int safeHeight = Math.max(1, height - insets.top - insets.bottom);
        content.measure(MeasureSpec.makeMeasureSpec(Math.max(safeWidth, safeHeight), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.min(safeWidth, safeHeight), MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = Math.max(1, r - l - insets.left - insets.right);
        int height = Math.max(1, b - t - insets.top - insets.bottom);
        int contentWidth = content.getMeasuredWidth(), contentHeight = content.getMeasuredHeight();
        int left = insets.left + (width - contentWidth) / 2;
        int top = insets.top + (height - contentHeight) / 2;
        content.layout(left, top, left + contentWidth, top + contentHeight);
        content.setRotation(width < height ? 90f : 0f);
        ClockLayout.Area localFold = null;
        float density = getResources().getDisplayMetrics().density;
        if (fold != null) {
            getLocationInWindow(windowPosition);
            int x = windowPosition[0] + insets.left, y = windowPosition[1] + insets.top;
            localFold = new ClockLayout.Area((fold.left - x) / density, (fold.top - y) / density,
                    (fold.right - x) / density, (fold.bottom - y) / density);
        }
        face.setLayoutTarget(contentWidth / density, contentHeight / density,
                ClockLayout.landscapeFold(localFold, width / density, height / density));
    }
}
