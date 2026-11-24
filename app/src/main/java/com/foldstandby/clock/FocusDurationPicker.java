package com.foldstandby.clock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;
import java.util.Locale;

/** Alarm-style left-right hours:minutes scroll-wheel picker with quick preset chips. */
final class FocusDurationPicker extends LinearLayout {
    private final WheelColumn hoursWheel;
    private final WheelColumn minutesWheel;
    private final TextView summary;
    private final TextView[] presetChips;

    FocusDurationPicker(Context c, int initialMinutes) {
        super(c);
        setOrientation(VERTICAL);
        setTag("focus:duration-picker");

        int rowHeight = Math.min(
            UiKit.dp(c, 58),
            Math.max(
                UiKit.dp(c, 42),
                Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    UiKit.sp(34),
                    c.getResources().getDisplayMetrics()
                ))
            )
        );

        LinearLayout header = UiKit.row(c);
        header.setPadding(UiKit.dp(c, 8), 0, UiKit.dp(c, 8), UiKit.dp(c, 6));
        TextView hourHeader = UiKit.text(c, "小时", 11.5f, UiKit.MUTED);
        hourHeader.setGravity(Gravity.CENTER);
        header.addView(hourHeader, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        header.addView(new View(c), new LayoutParams(UiKit.dp(c, 24), 1));
        TextView minuteHeader = UiKit.text(c, "分钟", 11.5f, UiKit.MUTED);
        minuteHeader.setGravity(Gravity.CENTER);
        header.addView(minuteHeader, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        FrameLayout stage = new FrameLayout(c);
        stage.setBackground(UiKit.surface(c, UiKit.BG, 16, UiKit.PANEL_BORDER, 1f));
        stage.setPadding(UiKit.dp(c, 8), UiKit.dp(c, 6), UiKit.dp(c, 8), UiKit.dp(c, 6));

        View band = new View(c);
        band.setBackground(UiKit.surface(c, UiKit.ACTIVE_BG, 12, UiKit.ACTIVE_BORDER, 1f));
        FrameLayout.LayoutParams bandLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, Gravity.CENTER_VERTICAL
        );
        bandLp.leftMargin = UiKit.dp(c, 6);
        bandLp.rightMargin = UiKit.dp(c, 6);
        stage.addView(band, bandLp);

        LinearLayout columns = UiKit.row(c);
        columns.setGravity(Gravity.CENTER_VERTICAL);

        hoursWheel = new WheelColumn(c, 24, "小时", rowHeight, this::updateSummaryAndChips);
        hoursWheel.setTag("focus:wheel-hours");
        hoursWheel.setContentDescription("小时");
        columns.addView(hoursWheel, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView colon = UiKit.text(c, ":", 26, UiKit.ACCENT);
        colon.setTypeface(null, Typeface.BOLD);
        colon.setGravity(Gravity.CENTER);
        colon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        columns.addView(colon, new LayoutParams(UiKit.dp(c, 24), LayoutParams.WRAP_CONTENT));

        minutesWheel = new WheelColumn(c, 60, "分钟", rowHeight, this::updateSummaryAndChips);
        minutesWheel.setTag("focus:wheel-minutes");
        minutesWheel.setContentDescription("分钟");
        columns.addView(minutesWheel, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        stage.addView(
            columns,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            )
        );
        addView(stage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        summary = UiKit.text(c, "", 12.5f, UiKit.ACCENT);
        summary.setTag("focus:duration-summary");
        summary.setGravity(Gravity.CENTER);
        summary.setTypeface(null, Typeface.BOLD);
        summary.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        LayoutParams summaryLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = UiKit.dp(c, 10);
        addView(summary, summaryLp);

        UiKit.WrapRow presetsRow = new UiKit.WrapRow(c, 6, 6);
        presetChips = new TextView[FocusDuration.PRESETS.length];
        for (int i = 0; i < FocusDuration.PRESETS.length; i++) {
            final int presetMinutes = FocusDuration.PRESETS[i];
            TextView chip = UiKit.styledButton(
                c,
                FocusDuration.PRESET_LABELS[i],
                UiKit.ROW,
                UiKit.ROW_BORDER,
                UiKit.TEXT,
                () -> setDuration(presetMinutes, true)
            );
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, UiKit.sp(11f));
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(UiKit.dp(c, 34));
            chip.setPadding(UiKit.dp(c, 10), UiKit.dp(c, 4), UiKit.dp(c, 10), UiKit.dp(c, 4));
            presetChips[i] = chip;
            presetsRow.addView(chip);
        }
        LayoutParams presetsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        presetsLp.topMargin = UiKit.dp(c, 10);
        addView(presetsRow, presetsLp);

        int clamped = FocusDuration.clamp(initialMinutes);
        setHoursMinutes(FocusDuration.hoursOf(clamped), FocusDuration.minutesOf(clamped), false);
    }

    int hours() {
        return hoursWheel.value();
    }

    int minutes() {
        return minutesWheel.value();
    }

    int totalMinutes() {
        return FocusDuration.toMinutes(hoursWheel.value(), minutesWheel.value());
    }

    void setDuration(int totalMinutes, boolean animate) {
        int clamped = FocusDuration.clamp(totalMinutes);
        setHoursMinutes(FocusDuration.hoursOf(clamped), FocusDuration.minutesOf(clamped), animate);
    }

    void setHoursMinutes(int hours, int minutes, boolean animate) {
        int h = Math.max(0, Math.min(FocusDuration.MAX_HOURS, hours));
        int m = Math.max(0, Math.min(59, minutes));
        hoursWheel.setValue(h, animate);
        minutesWheel.setValue(m, animate);
        updateSummaryAndChips();
    }

    void showZeroError() {
        summary.setText("专注时长至少为 1 分钟");
        summary.setTextColor(UiKit.DANGER_TEXT);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, UiKit.sp(12f));
        summary.announceForAccessibility("专注时长至少为 1 分钟");
        performHapticFeedback(HapticFeedbackConstants.REJECT);
    }

    private void updateSummaryAndChips() {
        int total = totalMinutes();
        if (total > 0) {
            summary.setText("共 " + FocusDuration.label(total));
            summary.setTextColor(UiKit.ACCENT);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, UiKit.sp(12.5f));
        } else {
            summary.setText("专注时长至少为 1 分钟");
            summary.setTextColor(UiKit.DANGER_TEXT);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, UiKit.sp(12f));
        }
        Context c = getContext();
        for (int i = 0; i < FocusDuration.PRESETS.length; i++) {
            boolean active = total == FocusDuration.PRESETS[i];
            TextView chip = presetChips[i];
            chip.setBackground(UiKit.surface(
                c,
                active ? UiKit.ACTIVE_BG : UiKit.ROW,
                8,
                active ? UiKit.ACTIVE_BORDER : UiKit.ROW_BORDER,
                1f
            ));
            chip.setTextColor(active ? UiKit.ACCENT : UiKit.TEXT);
            chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    static final class WheelColumn extends View {
        private static final int VISIBLE_ROWS = 5;

        private final int count;
        private final String unit;
        private final int rowHeight;
        private final Runnable onChange;
        private final OverScroller scroller;
        private final int touchSlop;
        private final int minFlingVelocity;
        private final int maxFlingVelocity;
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface regularTypeface = Typeface.DEFAULT;
        private final Typeface boldTypeface = Typeface.DEFAULT_BOLD;

        private float scrollOffset;
        private int value;
        private boolean animatingToTarget;
        private boolean dragging;
        private float downY;
        private float lastY;
        private VelocityTracker velocityTracker;

        WheelColumn(Context c, int count, String unit, int rowHeight, Runnable onChange) {
            super(c);
            this.count = count;
            this.unit = unit;
            this.rowHeight = rowHeight;
            this.onChange = onChange;
            this.scroller = new OverScroller(c, new DecelerateInterpolator(2.0f));
            ViewConfiguration vc = ViewConfiguration.get(c);
            this.touchSlop = vc.getScaledTouchSlop();
            this.minFlingVelocity = vc.getScaledMinimumFlingVelocity();
            this.maxFlingVelocity = vc.getScaledMaximumFlingVelocity();

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFontFeatureSettings("tnum");
            setFocusable(true);
            setClickable(true);
        }

        int value() {
            return value;
        }

        void setValue(int target, boolean animate) {
            int clamped = wrap(target);
            int previous = value;
            if (!scroller.isFinished()) scroller.abortAnimation();
            value = clamped;

            int currentIdx = Math.round(scrollOffset / rowHeight);
            int currentWrapped = wrap(currentIdx);
            int delta = clamped - currentWrapped;
            if (delta > count / 2) delta -= count;
            else if (delta < -count / 2) delta += count;
            int targetY = (currentIdx + delta) * rowHeight;

            if (animate && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow()) {
                int startY = Math.round(scrollOffset);
                if (startY != targetY) {
                    animatingToTarget = true;
                    scroller.startScroll(0, startY, 0, targetY - startY, 240);
                    postInvalidateOnAnimation();
                } else {
                    animatingToTarget = false;
                    scrollOffset = clamped * rowHeight;
                    invalidate();
                }
            } else {
                animatingToTarget = false;
                scrollOffset = clamped * rowHeight;
                invalidate();
            }

            if (clamped != previous && onChange != null) {
                onChange.run();
            }
        }

        private int wrap(int index) {
            int r = index % count;
            return r < 0 ? r + count : r;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = resolveSize(UiKit.dp(getContext(), 96), widthMeasureSpec);
            int height = resolveSize(rowHeight * VISIBLE_ROWS, heightMeasureSpec);
            setMeasuredDimension(width, height);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (!scroller.isFinished()) scroller.abortAnimation();
                    animatingToTarget = false;
                    if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                    else velocityTracker.clear();
                    velocityTracker.addMovement(event);
                    downY = event.getY();
                    lastY = downY;
                    dragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(event);
                    float y = event.getY();
                    if (!dragging && Math.abs(y - downY) > touchSlop) dragging = true;
                    if (dragging) {
                        scrollOffset -= (y - lastY);
                        int nextValue = wrap(Math.round(scrollOffset / rowHeight));
                        if (nextValue != value) {
                            value = nextValue;
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            if (onChange != null) onChange.run();
                        }
                        invalidate();
                    }
                    lastY = y;
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (!dragging) {
                        recycleVelocityTracker();
                        int rowDelta = Math.max(
                            -2,
                            Math.min(2, Math.round((event.getY() - getHeight() / 2f) / rowHeight))
                        );
                        if (rowDelta != 0) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            setValue(value + rowDelta, true);
                        }
                        performClick();
                        return true;
                    }
                    dragging = false;
                    float vy = 0f;
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                        vy = velocityTracker.getYVelocity();
                    }
                    recycleVelocityTracker();

                    int startY = Math.round(scrollOffset);
                    if (Math.abs(-vy) >= minFlingVelocity) {
                        scroller.fling(
                            0,
                            startY,
                            0,
                            Math.round(-vy),
                            0,
                            0,
                            Integer.MIN_VALUE / 2,
                            Integer.MAX_VALUE / 2
                        );
                        int rawFinal = scroller.getFinalY();
                        int snappedFinal = Math.round(rawFinal / (float) rowHeight) * rowHeight;
                        int rows = Math.abs(snappedFinal - startY) / Math.max(1, rowHeight);
                        int duration = Math.max(180, Math.min(900, 180 + rows * 45));
                        scroller.abortAnimation();
                        scroller.startScroll(0, startY, 0, snappedFinal - startY, duration);
                        postInvalidateOnAnimation();
                    } else {
                        snapToNearest();
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    dragging = false;
                    recycleVelocityTracker();
                    snapToNearest();
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void recycleVelocityTracker() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        private void snapToNearest() {
            int startY = Math.round(scrollOffset);
            int targetY = Math.round(scrollOffset / rowHeight) * rowHeight;
            if (startY != targetY) {
                scroller.startScroll(0, startY, 0, targetY - startY, 160);
                postInvalidateOnAnimation();
            } else {
                scrollOffset = value * rowHeight;
                invalidate();
            }
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollOffset = scroller.getCurrY();
                if (!animatingToTarget) {
                    int nextValue = wrap(Math.round(scrollOffset / rowHeight));
                    if (nextValue != value) {
                        value = nextValue;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (onChange != null) onChange.run();
                    }
                }
                postInvalidateOnAnimation();
            } else if (animatingToTarget) {
                animatingToTarget = false;
                scrollOffset = value * rowHeight;
                invalidate();
            } else if (!dragging && Math.abs(scrollOffset) > (float) count * rowHeight * 10f) {
                scrollOffset = value * rowHeight;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float exactIndex = scrollOffset / rowHeight;
            int baseIndex = (int) Math.floor(exactIndex);
            float maxTextWidth = getWidth() * 0.84f;

            for (int i = baseIndex - 3; i <= baseIndex + 3; i++) {
                float dist = i - exactIndex;
                if (Math.abs(dist) > 2.5f) continue;

                float y = centerY + dist * rowHeight;
                float focus = Math.max(0f, 1f - Math.abs(dist) / 2.4f);
                float baseSp = 17f + 11f * focus;
                float sizePx = Math.min(
                    rowHeight * 0.78f,
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        UiKit.sp(baseSp),
                        getResources().getDisplayMetrics()
                    )
                );

                boolean centered = Math.abs(dist) < 0.45f;
                textPaint.setTypeface(centered ? boldTypeface : regularTypeface);
                textPaint.setTextSize(sizePx);

                String label = String.format(Locale.ROOT, "%02d", wrap(i));
                if (maxTextWidth > 0f) {
                    float measured = textPaint.measureText(label);
                    if (measured > maxTextWidth) {
                        textPaint.setTextSize(sizePx * (maxTextWidth / measured));
                    }
                }

                textPaint.setColor(centered ? UiKit.TEXT : UiKit.MUTED);
                textPaint.setAlpha(Math.round(255f * (0.18f + 0.82f * focus * focus)));

                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float baseline = y - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(label, centerX, baseline, textPaint);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(android.widget.NumberPicker.class.getName());
            info.setScrollable(true);
            info.setText(String.format(Locale.ROOT, "%02d %s", value, unit));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (super.performAccessibilityAction(action, arguments)) return true;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                setValue(value + 1, true);
                announceForAccessibility(String.format(Locale.ROOT, "%02d %s", value, unit));
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                setValue(value - 1, true);
                announceForAccessibility(String.format(Locale.ROOT, "%02d %s", value, unit));
                return true;
            }
            return false;
        }
    }
}
