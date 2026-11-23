package com.foldstandby.clock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Shared visual design system and control primitives for the Studio Workspace. */
final class UiKit {
    interface EditTransactions { void beginEdit(); void endEdit(); }
    static void beginEdit(Context c) { if(c instanceof EditTransactions)((EditTransactions)c).beginEdit(); }
    static void endEdit(Context c) { if(c instanceof EditTransactions)((EditTransactions)c).endEdit(); }
    static float customFontScale = 1.0f;

    static final int BG = 0xff090d12;
    static final int PANEL = 0xff10161f;
    static final int PANEL_BORDER = 0xff1d2838;
    static final int CARD = 0xff141c27;
    static final int CARD_BORDER = 0xff233042;
    static final int ROW = 0xff1a2433;
    static final int ROW_BORDER = 0xff29374a;
    static final int SELECTOR_BG = 0xff182231;
    static final int SELECTOR_BORDER = 0xff31435a;
    static final int ACTIVE_BG = 0xff1f3b32;
    static final int ACTIVE_BORDER = 0xff4d8a72;
    static final int ACCENT = 0xffb8e5c7;
    static final int WARN_BG = 0xff2b2013;
    static final int WARN_BORDER = 0xff6e5028;
    static final int WARN_TEXT = 0xffffd594;
    static final int DANGER_BG = 0xff2c151a;
    static final int DANGER_BORDER = 0xff6e2d3a;
    static final int DANGER_TEXT = 0xffffa1ab;
    static final int TEXT = 0xffecf1f8;
    static final int MUTED = 0xff94a3b8;
    static final int DIVIDER = 0xff222e3e;

    private UiKit() {}

    static int dp(Context c, float n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }

    static float sp(float baseSp) {
        return baseSp * customFontScale;
    }

    static GradientDrawable surface(Context c, int fill, int radiusDp) {
        return surface(c, fill, radiusDp, 0, 0f);
    }

    static GradientDrawable surface(Context c, int fill, int radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0 && strokeDp > 0f) {
            d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        }
        return d;
    }

    static TextView text(Context c, String value, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(sizeSp));
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    static void styleInput(EditText field, boolean compact) {
        Context c = field.getContext();
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(compact ? 12.5f : 15));
        field.setMinHeight(dp(c, compact ? 36 : 50));
        field.setPadding(dp(c, compact ? 10 : 14), dp(c, compact ? 5 : 11), dp(c, compact ? 10 : 14), dp(c, compact ? 5 : 11));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] { android.R.attr.state_activated }, surface(c, BG, 12, DANGER_TEXT, 1.5f));
        background.addState(new int[] { android.R.attr.state_focused }, surface(c, SELECTOR_BG, 12, ACCENT, 1.5f));
        background.addState(new int[] {}, surface(c, BG, 12, SELECTOR_BORDER, 1));
        field.setBackground(background);
        field.setHighlightColor(0x554d8a72);
        if (field.getTextCursorDrawable() != null) field.getTextCursorDrawable().setTint(ACCENT);
    }

    static void inputAction(EditText field, int action) {
        field.setImeOptions(action | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    static void gap(Context c, LinearLayout host, int heightDp) {
        host.addView(new View(c), new LinearLayout.LayoutParams(1, dp(c, heightDp)));
    }

    static void divider(Context c, LinearLayout host) {
        View line = new View(c);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 4);
        lp.bottomMargin = dp(c, 6);
        host.addView(line, lp);
    }

    static void title(Context c, LinearLayout host, String label, String caption) {
        TextView t = text(c, label, 15, TEXT);
        t.setTypeface(null, Typeface.BOLD);
        host.addView(t);
        if (caption != null && !caption.isEmpty()) {
            TextView s = text(c, caption, 10.5f, MUTED);
            s.setPadding(0, dp(c, 2), 0, dp(c, 6));
            host.addView(s);
        } else {
            gap(c, host, 6);
        }
    }

    static void note(Context c, LinearLayout host, String label) {
        TextView t = text(c, label, 10.5f, MUTED);
        t.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 4));
        host.addView(t);
    }

    /** Creates a structured section card with compact padding, subtle stroke, and optional header. */
    static LinearLayout card(Context c, LinearLayout host, String heading, String caption) {
        LinearLayout box = column(c);
        box.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        box.setBackground(surface(c, CARD, 12, CARD_BORDER, 1f));
        if (heading != null && !heading.isEmpty()) {
            TextView h = text(c, heading, 13, TEXT);
            h.setTypeface(null, Typeface.BOLD);
            box.addView(h);
            if (caption != null && !caption.isEmpty()) {
                TextView sub = text(c, caption, 10.5f, MUTED);
                sub.setPadding(0, dp(c, 2), 0, dp(c, 6));
                box.addView(sub);
            } else {
                gap(c, box, 6);
            }
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 8);
        host.addView(box, lp);
        return box;
    }

    static TextView button(Context c, String label, Runnable click) {
        return styledButton(c, label, ROW, ROW_BORDER, TEXT, click);
    }

    static TextView styledButton(Context c, String label, int bg, int border, int textColor, Runnable click) {
        TextView v = text(c, label, 12, textColor);
        v.setMinHeight(dp(c, 36));
        v.setPadding(dp(c, 9), dp(c, 5), dp(c, 9), dp(c, 5));
        v.setBackground(surface(c, bg, 8, border, 1f));
        v.setOnClickListener(x -> click.run());
        return v;
    }

    static TextView compactButton(Context c, String label, int bg, int border, int textColor, Runnable click) {
        TextView v = text(c, label, 11, textColor);
        v.setMinHeight(dp(c, 40));
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(2);
        v.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        v.setBackground(surface(c, bg, 8, border, 1f));
        v.setOnClickListener(x -> click.run());
        return v;
    }

    static int contrastText(int[] colors) {
        if (colors == null || colors.length == 0) return TEXT;
        double sum = 0;
        for (int color : colors) sum += Color.luminance(color);
        return (sum / colors.length) > 0.36 ? 0xff101820 : 0xfff8fafc;
    }

    static TextView colorPill(Context c, String label, int[] colors, boolean active, Runnable click) {
        TextView v = text(c, label, 11, contrastText(colors));
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(1);
        v.setMinHeight(dp(c, 32));
        v.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        if (active) v.setTypeface(null, Typeface.BOLD);

        GradientDrawable fill;
        if (colors.length <= 1) {
            fill = new GradientDrawable();
            fill.setColor(colors.length == 1 ? colors[0] : ROW);
        } else {
            fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        }
        fill.setCornerRadius(dp(c, 8));
        fill.setStroke(active ? Math.max(2, dp(c, 2)) : Math.max(1, dp(c, 1)), active ? 0xffffffff : 0x44ffffff);
        v.setBackground(fill);
        v.setOnClickListener(x -> click.run());
        return v;
    }

    static TextView action(Context c, LinearLayout host, String label, Runnable click) {
        TextView b = button(c, label, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 5);
        host.addView(b, lp);
        return b;
    }

    static TextView dangerAction(Context c, LinearLayout host, String label, Runnable click) {
        TextView b = styledButton(c, label, DANGER_BG, DANGER_BORDER, DANGER_TEXT, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 5);
        host.addView(b, lp);
        return b;
    }

    static TextView statusAction(Context c, LinearLayout host, String label, boolean active, Runnable click) {
        TextView b = styledButton(
            c,
            label,
            active ? ACTIVE_BG : WARN_BG,
            active ? ACTIVE_BORDER : WARN_BORDER,
            active ? ACCENT : WARN_TEXT,
            click
        );
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 5);
        host.addView(b, lp);
        return b;
    }

    static TextView badge(Context c, String label, int bg, int border, int textColor) {
        TextView v = text(c, label, 10, textColor);
        v.setMaxLines(1);
        v.setPadding(dp(c, 7), dp(c, 2), dp(c, 7), dp(c, 2));
        v.setBackground(surface(c, bg, 7, border, 1f));
        return v;
    }

    static TextView choice(Context c, LinearLayout host, String label, String[] options, int current, IntConsumer cb) {
        int at = Math.max(0, Math.min(options.length - 1, current));
        TextView b = styledButton(
            c,
            label + "  ·  " + options[at] + "  ▾",
            SELECTOR_BG,
            SELECTOR_BORDER,
            TEXT,
            () -> AppDialogs.builder(c)
                .setTitle(label)
                .setSingleChoiceItems(options, at, (d, n) -> {
                    d.dismiss();
                    cb.accept(n);
                })
                .setNegativeButton("取消", null)
                .show()
        );
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 5);
        host.addView(b, lp);
        return b;
    }

    static void toggle(Context c, LinearLayout host, String label, boolean value, Consumer<Boolean> onChange) {
        Switch s = new Switch(c);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12));
        s.setTextColor(TEXT);
        s.setMinHeight(dp(c, 36));
        s.setPadding(dp(c, 2), dp(c, 3), dp(c, 2), dp(c, 3));
        s.setChecked(value);
        int[][] states = new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} };
        s.setThumbTintList(new ColorStateList(states, new int[] { ACCENT, 0xffcbd5e1 }));
        s.setTrackTintList(new ColorStateList(states, new int[] { 0xff3b6e5a, 0xff2a3646 }));
        host.addView(s, new LinearLayout.LayoutParams(-1, -2));
        s.setOnCheckedChangeListener((v, checked) -> onChange.accept(checked));
    }

    static void slider(Context c, LinearLayout host, String label, int value, int min, int max, String unit, IntConsumer change) {
        buildSlider(c, host, label, value + unit, value, min, max, n -> n + unit, change);
    }

    /** Continuous 0.1%..0.9% ultra-low range (steps 0..8) followed by 1%..30% (steps 9..38). */
    static void nightBrightnessSlider(Context c, LinearLayout host, String label, float value, Consumer<Float> change) {
        float clamped = Math.max(0.001f, Math.min(0.30f, value));
        int initial = clamped < 0.0095f
            ? Math.max(1, Math.min(9, Math.round(clamped * 1000f))) - 1
            : Math.max(1, Math.min(30, Math.round(clamped * 100f))) + 8;
        buildSlider(c, host, label, formatNightBrightness(initial), initial, 0, 38,
            UiKit::formatNightBrightness, n -> change.accept(nightBrightnessFraction(n)));
    }

    private static String formatNightBrightness(int step) {
        return step <= 8 ? ("0." + (step + 1) + "% · 超低") : ((step - 8) + "%");
    }

    private static float nightBrightnessFraction(int step) {
        return step <= 8 ? (step + 1) * 0.001f : (step - 8) / 100f;
    }

    private static void buildSlider(Context c, LinearLayout host, String label, String initialText, int initial,
            int min, int max, java.util.function.IntFunction<String> formatter, IntConsumer change) {
        LinearLayout row = row(c);
        TextView name = text(c, label, 12, TEXT);
        TextView number = text(c, initialText, 11, ACCENT);
        name.setMinHeight(dp(c, 26));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(number);
        host.addView(row);

        SeekBar bar = new SeekBar(c);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(initial);
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff2c3a4d));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(c, 28));
        blp.bottomMargin = dp(c, 3);
        host.addView(bar, blp);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int n, boolean user) {
                if (user) {
                    number.setText(formatter.apply(n));
                    change.accept(n);
                }
            }
            public void onStartTrackingTouch(SeekBar b) { beginEdit(c); }
            public void onStopTrackingTouch(SeekBar b) { endEdit(c); }
        });
    }

    static void chips(Context c, LinearLayout host, int availableWidthDp, String[] labels, int selected, IntConsumer onSelect) {
        int longest = 0;
        for (String label : labels) longest = Math.max(longest, label.length());
        float fontScale = Math.max(1f, c.getResources().getConfiguration().fontScale * customFontScale * 0.85f);
        int baseMin = longest <= 2 ? 62 : longest <= 4 ? 86 : 114;
        int minimum = Math.round(baseMin * fontScale);
        int cols = Math.min(labels.length, Math.max(1, Math.max(1, availableWidthDp) / minimum));
        for (int start = 0; start < labels.length; start += cols) {
            LinearLayout row = row(c);
            for (int i = start; i < Math.min(start + cols, labels.length); i++) {
                final int index = i;
                boolean isSelected = i == selected;
                TextView b = styledButton(
                    c,
                    labels[i],
                    isSelected ? ACTIVE_BG : ROW,
                    isSelected ? ACTIVE_BORDER : ROW_BORDER,
                    isSelected ? ACCENT : TEXT,
                    () -> onSelect.accept(index)
                );
                b.setGravity(Gravity.CENTER);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(11));
                b.setMinHeight(dp(c, 40));
                b.setPadding(dp(c, 5), dp(c, 4), dp(c, 5), dp(c, 4));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                int rightGap = (i + 1 < Math.min(start + cols, labels.length)) ? dp(c, 4) : 0;
                p.setMargins(0, 0, rightGap, dp(c, 4));
                row.addView(b, p);
            }
            host.addView(row);
        }
    }

    /** Flow container that keeps compact controls on one line and wraps cleanly on narrow screens or large font scales. */
    static final class WrapRow extends ViewGroup {
        private final int hGap, vGap;
        WrapRow(Context c, int hGapDp, int vGapDp) {
            super(c);
            this.hGap = dp(c, hGapDp);
            this.vGap = dp(c, vGapDp);
        }
        protected void onMeasure(int widthSpec, int heightSpec) {
            int padL = getPaddingLeft(), padT = getPaddingTop();
            int padR = getPaddingRight(), padB = getPaddingBottom();
            int outerWidth = MeasureSpec.getSize(widthSpec);
            int widthMode = MeasureSpec.getMode(widthSpec);
            int maxWidth = (outerWidth <= 0 || widthMode == MeasureSpec.UNSPECIFIED)
                ? Integer.MAX_VALUE
                : Math.max(0, outerWidth - padL - padR);
            int x = 0, y = 0, rowHeight = 0, maxUsed = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int childWSpec = maxWidth == Integer.MAX_VALUE
                    ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    : MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
                child.measure(childWSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > maxWidth) {
                    y += rowHeight + vGap;
                    x = 0;
                    rowHeight = 0;
                }
                x += cw + hGap;
                maxUsed = Math.max(maxUsed, x - hGap);
                rowHeight = Math.max(rowHeight, ch);
            }
            int finalW = widthMode == MeasureSpec.EXACTLY ? outerWidth : Math.min(outerWidth > 0 ? outerWidth : Integer.MAX_VALUE, maxUsed + padL + padR);
            setMeasuredDimension(finalW, y + rowHeight + padT + padB);
        }
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int padL = getPaddingLeft(), padT = getPaddingTop();
            int maxWidth = Math.max(0, (r - l) - padL - getPaddingRight());
            int x = 0, y = 0, rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > maxWidth) {
                    y += rowHeight + vGap;
                    x = 0;
                    rowHeight = 0;
                }
                child.layout(padL + x, padT + y, padL + x + cw, padT + y + ch);
                x += cw + hGap;
                rowHeight = Math.max(rowHeight, ch);
            }
        }
    }
}
