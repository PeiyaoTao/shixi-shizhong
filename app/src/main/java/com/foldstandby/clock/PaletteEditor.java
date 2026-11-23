package com.foldstandby.clock;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.function.*;

/** Compact in-pane palette using color-filled pills so preview visibility and workspace height stay maximized. */
final class PaletteEditor extends LinearLayout {
    static final class State { int mode, role = 1; String hex; }

    private final JSONObject theme;
    private final IntUnaryOperator resolved;
    private final Runnable changed;
    private final Consumer<String> recent;
    private final State state;
    private final String recentColors;
    private EditText hex;
    private MainActivity.HsvPicker picker;
    private View sample;
    private boolean fromPicker;
    private final Scene.Component component;
    private final boolean night;
    private final String resetLabel;
    private boolean hexTransaction;

    private final ViewTreeObserver.OnGlobalLayoutListener keepInputVisible = () -> post(this::ensureInputVisible);

    private void ensureInputVisible() {
        if (hex == null || !hex.hasFocus() || hex.getHeight() == 0) return;
        ViewParent parent = getParent();
        while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
        if (!(parent instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) parent;
        Rect bounds = new Rect();
        hex.getDrawingRect(bounds);
        scroll.offsetDescendantRectToMyCoords(hex, bounds);
        int top = scroll.getScrollY() + scroll.getPaddingTop();
        int bottom = scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom();
        if (bounds.bottom > bottom) scroll.scrollBy(0, bounds.bottom - bottom + dp(8));
        else if (bounds.top < top) scroll.scrollBy(0, bounds.top - top - dp(8));
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(keepInputVisible);
    }

    protected void onDetachedFromWindow() {
        finishInput();
        getViewTreeObserver().removeOnGlobalLayoutListener(keepInputVisible);
        super.onDetachedFromWindow();
    }

    PaletteEditor(Context context, JSONObject theme, IntUnaryOperator resolved, State state, String recents, Runnable changed, Consumer<String> recent) {
        this(context,theme,resolved,state,recents,changed,recent,null,false,"恢复跟随");
    }
    PaletteEditor(Context context, JSONObject theme, IntUnaryOperator resolved, State state, String recents, Runnable changed, Consumer<String> recent,
                  Scene.Component component, boolean night, String resetLabel) {
        super(context);
        setOrientation(VERTICAL);
        this.theme = theme;
        this.resolved = resolved;
        this.state = state;
        state.mode = state.mode == 1 ? 1 : 0;
        state.role = state.role > 0 && state.role < Scene.ROLES.length ? state.role : 1;
        this.changed = changed;
        this.recent = recent;
        this.recentColors = recents;
        this.component=component;this.night=night;this.resetLabel=resetLabel;
        if(!visibleRole(state.role)){state.role=component!=null&&(component.type.equals("clock")||component.type.equals("world")||component.type.equals("focus"))?1:3;}
        build();
    }
    private boolean visibleRole(int role){
        if(component==null)return role>0;
        boolean clock=component.type.equals("clock"),analog=clock&&(component.style.equals("analog")||component.style.equals("industrial"));
        if(role==1)return clock||component.type.equals("world")||component.type.equals("focus");
        if(role==2)return true;
        if(role==10)return !clock||analog||component.style.equals("flip")||component.bool("card",false);
        if(role==3)return !clock;
        if(role>=4&&role<=9)return analog;
        return false;
    }
    private void finishInput(){if(hexTransaction){hexTransaction=false;UiKit.endEdit(getContext());}}

    private int dp(float value) { return UiKit.dp(getContext(), value); }

    private GradientDrawable background(int color, int border) {
        return UiKit.surface(getContext(), color, 8, border, border != 0 ? 1f : 0f);
    }

    private TextView label(String text, float size, int color) {
        return UiKit.text(getContext(), text, size, color);
    }

    private TextView button(String text, Runnable action) {
        return UiKit.compactButton(getContext(), text, UiKit.ROW, UiKit.ROW_BORDER, UiKit.TEXT, action);
    }

    private void gap(int height) { UiKit.gap(getContext(), this, height); }

    private void build() {
        finishInput();
        removeAllViews();
        hex = null;
        picker = null;
        sample = null;

        LinearLayout modes = UiKit.row(getContext());
        String[] names = { "预设搭配", "自定义" };
        for (int i = 0; i < 2; i++) {
            final int n = i;
            boolean active = i == state.mode;
            TextView b = UiKit.compactButton(
                getContext(),
                names[i],
                active ? UiKit.ACTIVE_BG : UiKit.ROW,
                active ? UiKit.ACTIVE_BORDER : UiKit.ROW_BORDER,
                active ? UiKit.ACCENT : UiKit.TEXT,
                () -> { remember(); state.mode = n; build(); }
            );
            if (active) b.setTypeface(null, Typeface.BOLD);
            LayoutParams lp = new LayoutParams(0, -2, 1);
            lp.setMargins(0, 0, i == 0 ? dp(5) : 0, 0);
            modes.addView(b, lp);
        }
        addView(modes);
        gap(6);

        if (state.mode == 0) {


            PillGrid grid = new PillGrid(getContext());
            for (int i = 0; i < ColorThemes.NAMES.length; i++) {
                final int n = i;
                boolean active = ColorThemes.matches(theme, i);
                int cMain = ColorThemes.color(i, 1);
                int cAccent = ColorThemes.color(i, 3);
                TextView pill = UiKit.colorPill(
                    getContext(),
                    ColorThemes.NAMES[i],
                    new int[] { cMain, cAccent },
                    active,
                    () -> { finishInput(); ColorThemes.apply(theme, n); if(night)theme.remove("timeGradient"); state.hex = null; changed.run(); build(); }
                );
                pill.setContentDescription("配色搭配 · " + ColorThemes.NAMES[i]);
                grid.addView(pill);
            }
            addView(grid);
            gradients();
            return;
        }

        gradients();

        TextView roleLabel = label(theme.has(Scene.ROLES[state.role])?"颜色 · 已单独设置":"颜色 · 跟随上层", 10.5f, UiKit.MUTED);
        roleLabel.setPadding(0, dp(2), 0, dp(4));
        addView(roleLabel);

        HorizontalScrollView roles = new HorizontalScrollView(getContext());
        roles.setHorizontalScrollBarEnabled(true);
        LinearLayout roleRow = UiKit.row(getContext());
        for (int i = 1; i < Scene.ROLES.length; i++) {
            if(!visibleRole(i))continue;
            final int n = i;
            boolean overridden = theme.has(Scene.ROLES[i]);
            boolean active = i == state.role;
            String title = (overridden ? "● " : "") + Scene.ROLE_NAMES[i];
            int colorValue = resolved.applyAsInt(i);
            TextView pill = UiKit.colorPill(
                getContext(),
                title,
                new int[] { colorValue },
                active,
                () -> { remember(); state.role = n; state.hex = null; build(); }
            );
            pill.setTag("color-role:" + i);
            pill.setMinimumWidth(dp(54));
            LayoutParams rp = new LayoutParams(-2, -2);
            rp.rightMargin = dp(5);
            roleRow.addView(pill, rp);
        }
        roles.addView(roleRow);
        addView(roles);
        roles.post(() -> {
            View target = roleRow.findViewWithTag("color-role:" + state.role);
            if (target != null) roles.smoothScrollTo(Math.max(0, target.getLeft() - dp(8)), 0);
        });
        gap(6);

        LinearLayout inputRow = UiKit.row(getContext());
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        sample = new View(getContext());
        inputRow.addView(sample, new LayoutParams(dp(28), dp(28)));

        hex = new EditText(getContext());
        UiKit.styleInput(hex, true);
        hex.setSingleLine();
        hex.setSelectAllOnFocus(true);
        hex.setContentDescription("HEX 颜色");
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        UiKit.inputAction(hex, android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LayoutParams hp = new LayoutParams(0, -2, 1);
        hp.setMargins(dp(7), 0, dp(6), 0);
        inputRow.addView(hex, hp);

        TextView inherit = button(resetLabel, () -> {
            finishInput();
            theme.remove(Scene.ROLES[state.role]);
            if (state.role == 1) theme.remove("timeGradient");
            state.hex = null;
            changed.run();
            build();
        });
        inputRow.addView(inherit);
        addView(inputRow);
        gap(5);

        int current = theme.optInt(Scene.ROLES[state.role], resolved.applyAsInt(state.role));
        sample.setBackground(background(current, 0xff52606c));
        hex.setText(state.hex == null ? format(current) : state.hex);

        picker = new MainActivity.HsvPicker(getContext(), current, value -> {
            fromPicker = true;
            hex.setText(format(value));
            fromPicker = false;
        });
        addView(picker, new LayoutParams(-1, dp(96)));
        gap(5);

        hex.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                state.hex = s.toString();
                if (!valid(state.hex)) {
                    hex.setActivated(true);
                    hex.setError("请输入六位 HEX 颜色");
                    return;
                }
                hex.setError(null);
                hex.setActivated(false);
                int color = parse(state.hex);
                Scene.put(theme, Scene.ROLES[state.role], color);
                if (state.role == 1) Scene.put(theme, "timeGradient", 0);
                sample.setBackground(background(color, 0xff52606c));
                if (!fromPicker) picker.setColor(color);
                changed.run();
            }
            public void afterTextChanged(Editable e) {}
        });
        hex.setOnFocusChangeListener((view,focused)->{
            if(focused&&!hexTransaction){hexTransaction=true;UiKit.beginEdit(getContext());postDelayed(this::ensureInputVisible,350);}
            else if(!focused)finishInput();
        });
        hex.setOnEditorActionListener((v, action, event) -> {
            if (validateInput()) {
                remember();
                ((android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(hex.getWindowToken(), 0);
                hex.clearFocus();
            }
            return true;
        });

        if (!recentColors.isEmpty()) {
            TextView caption = label("最近使用", 10.5f, UiKit.MUTED);
            caption.setPadding(0, 0, 0, dp(3));
            addView(caption);
            HorizontalScrollView recentScroll = new HorizontalScrollView(getContext());
            LinearLayout recentRow = UiKit.row(getContext());
            for (String value : recentColors.split(",")) {
                if (!valid(value)) continue;
                int parsed = parse(value);
                TextView pill = UiKit.colorPill(getContext(), value, new int[] { parsed }, false, () -> hex.setText(value));
                LayoutParams rp = new LayoutParams(-2, -2);
                rp.rightMargin = dp(5);
                recentRow.addView(pill, rp);
            }
            recentScroll.addView(recentRow);
            addView(recentScroll);
        }
    }

    boolean validateInput() {
        if (hex == null || valid(hex.getText().toString())) return true;
        hex.setError("请输入六位 HEX 颜色");
        hex.requestFocus();
        return false;
    }

    void remember() {
        if (hex != null && valid(hex.getText().toString())) {
            recent.accept(format(parse(hex.getText().toString())));
        }
    }

    EditText hexInput() { return hex; }

    private void gradients() {
        if(night)return;
        TextView title = label("组件主题渐变", 12, UiKit.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(7), 0, dp(4));
        addView(title);

        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        LinearLayout row = UiKit.row(getContext());
        int current = theme.optInt("timeGradient", 0);
        for (int i = 0; i < TextGradients.NAMES.length; i++) {
            final int n = i;
            TextView pill = UiKit.colorPill(
                getContext(),
                TextGradients.NAMES[i],
                TextGradients.COLORS[i],
                current == i,
                () -> { finishInput(); Scene.put(theme, "timeGradient", n); changed.run(); build(); }
            );
            pill.setMinimumWidth(dp(56));
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.rightMargin = dp(5);
            row.addView(pill, lp);
        }
        scroll.addView(row);
        addView(scroll);


    }

    static boolean valid(String value) { return value != null && value.trim().matches("#?[0-9a-fA-F]{6}"); }
    static int parse(String value) { String s = value.trim(); return Color.parseColor(s.startsWith("#") ? s : "#" + s); }
    static String format(int color) { return String.format(java.util.Locale.ROOT, "#%06X", color & 0xffffff); }

    /** Compact color-pill grid that wraps using pane width and custom/system font scale. */
    private static final class PillGrid extends ViewGroup {
        private int columns, cell, gap;
        PillGrid(Context c) { super(c); gap = Math.round(5 * c.getResources().getDisplayMetrics().density); }
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            float scale = Math.max(1f, getResources().getConfiguration().fontScale * UiKit.customFontScale * 0.85f);
            int minimum = Math.round(68 * getResources().getDisplayMetrics().density * scale);
            columns = Math.max(1, Math.min(4, (width + gap) / (minimum + gap)));
            cell = (width - gap * (columns - 1)) / columns;
            int total = 0;
            for (int start = 0; start < getChildCount(); start += columns) {
                int row = 0;
                for (int i = start; i < Math.min(start + columns, getChildCount()); i++) {
                    View child = getChildAt(i);
                    child.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    row = Math.max(row, child.getMeasuredHeight());
                }
                total += row + gap;
            }
            setMeasuredDimension(width, Math.max(0, total - gap));
        }
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int y = 0;
            for (int start = 0; start < getChildCount(); start += columns) {
                int row = 0;
                for (int i = start; i < Math.min(start + columns, getChildCount()); i++) row = Math.max(row, getChildAt(i).getMeasuredHeight());
                for (int i = start; i < Math.min(start + columns, getChildCount()); i++) {
                    View child = getChildAt(i);
                    int x = (i - start) * (cell + gap);
                    child.layout(x, y, x + cell, y + row);
                }
                y += row + gap;
            }
        }
    }
}
