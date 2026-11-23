package com.foldstandby.clock;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Shared, keyboard-aware form surface for local tasks, names, and data searches. */
final class InputDialog extends Dialog {
    private final Activity owner;
    private final LinearLayout body, footer;
    private final ScrollView scroll;
    private final ArrayList<Field> fields = new ArrayList<>();
    private int maxHeight=620;
    private Rect lastArea;
    private Runnable submit;
    private int submitAction = EditorInfo.IME_ACTION_DONE;

    InputDialog(Activity activity, String title, String caption) {
        super(activity);
        owner = activity;
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(activity) {
            protected void onMeasure(int width, int height) {
                int limit = Math.min(UiKit.dp(activity, maxHeight), MeasureSpec.getSize(height));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(UiKit.surface(activity, UiKit.PANEL, 24, UiKit.SELECTOR_BORDER, 1));
        LinearLayout content=UiKit.column(activity);
        TextView heading = UiKit.text(activity, title, 17, UiKit.TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        content.addView(heading);
        if (caption != null && !caption.isEmpty()) {
            TextView description = UiKit.text(activity, caption, 11.5f, UiKit.MUTED);
            description.setPadding(0, dp(4), 0, dp(2));
            content.addView(description);
        }

        scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setSmoothScrollingEnabled(false);
        body = UiKit.column(activity);
        body.setPadding(0, dp(14), 0, dp(4));
        content.addView(body);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(()->{resize();ensureInputVisible();});

        footer = UiKit.row(activity);
        footer.setPadding(0, dp(14), 0, 0);
        footer.addView(button("取消", false, this::dismiss), new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(footer);
        setContentView(panel);
        setCanceledOnTouchOutside(true);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.62f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setNavigationBarColor(UiKit.BG);
    }

    private int dp(float value) { return UiKit.dp(owner, value); }

    private void ensureInputVisible() {
        View focused = getCurrentFocus();
        if (!(focused instanceof EditText) || scroll.getHeight() == 0) return;
        android.graphics.Rect visible = new android.graphics.Rect();
        if (!focused.getGlobalVisibleRect(visible) || visible.height() < focused.getHeight()) {
            focused.requestRectangleOnScreen(new android.graphics.Rect(0, 0, focused.getWidth(), focused.getHeight()), true);
        }
    }

    final class Field {
        final EditText input;
        private final TextView error;

        Field(String label, String hint, String initial, int type) {
            TextView name = UiKit.text(owner, label, 11.5f, UiKit.MUTED);
            body.addView(name);
            input = new EditText(owner);
            input.setId(View.generateViewId());
            name.setLabelFor(input.getId());
            input.setContentDescription(label);
            input.setSingleLine();
            input.setInputType(type);
            UiKit.styleInput(input, false);
            input.setHint(hint);
            input.setText(initial);
            input.setSelection(input.length());
            UiKit.inputAction(input, submitAction);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            body.addView(input, lp);
            error = UiKit.text(owner, "", 11, UiKit.DANGER_TEXT);
            error.setPadding(dp(2), dp(5), 0, 0);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            error.setVisibility(View.GONE);
            body.addView(error);
            input.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    error.setVisibility(View.GONE);
                    input.setActivated(false);
                }
                public void afterTextChanged(Editable value) {}
            });
            input.setOnEditorActionListener((view, action, event) -> {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (submit != null && (action == submitAction || (enter && (input.getImeOptions() & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NEXT))) {
                    if (!enter || event.getAction() == KeyEvent.ACTION_UP) submit.run();
                    return true;
                }
                return false;
            });
        }

        String value() { return input.getText().toString().trim(); }

        boolean require(String message) {
            if (!value().isEmpty()) return true;
            error(message);
            return false;
        }

        void error(String message) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
            input.setActivated(true);
            input.requestFocus();
            input.post(() -> input.requestRectangleOnScreen(new android.graphics.Rect(0, 0, input.getWidth(), input.getHeight() + dp(28)), false));
        }
    }

    Field field(String label, String hint, String initial, int type) {
        if (!fields.isEmpty()) UiKit.gap(owner, body, 12);
        Field field = new Field(label, hint, initial, type);
        if (!fields.isEmpty()) {
            EditText previous = fields.get(fields.size() - 1).input;
            UiKit.inputAction(previous, EditorInfo.IME_ACTION_NEXT);
            previous.setNextFocusForwardId(field.input.getId());
        }
        fields.add(field);
        return field;
    }

    void primary(String label, int action, Runnable onSubmit) {
        submit = onSubmit;
        submitAction = action;
        if (!fields.isEmpty()) UiKit.inputAction(fields.get(fields.size() - 1).input, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.4f);
        lp.leftMargin = dp(10);
        footer.addView(button(label, true, onSubmit), lp);
    }

    void secondary(String label, Runnable action) {
        TextView link = button(label, false, () -> { dismiss(); action.run(); });
        link.setTextColor(UiKit.ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        body.addView(link, lp);
    }

    ListView list(List<String> values) {
        ListView list = new ListView(owner);
        list.setBackground(UiKit.surface(owner, UiKit.BG, 12));
        list.setDivider(new ColorDrawable(UiKit.DIVIDER));
        list.setDividerHeight(dp(1));
        list.setAdapter(new ArrayAdapter<String>(owner, android.R.layout.simple_list_item_1, values) {
            public View getView(int position, View convert, ViewGroup parent) {
                TextView row = convert instanceof TextView ? (TextView) convert : UiKit.text(owner, "", 13, UiKit.TEXT);
                row.setText(getItem(position));
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setMinHeight(dp(48));
                return row;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(Math.min(188, Math.max(48, values.size() * 49))));
        lp.topMargin = dp(12);
        body.addView(list, lp);
        return list;
    }

    private TextView button(String label, boolean primary, Runnable action) {
        TextView button = UiKit.text(owner, label, 13, primary ? UiKit.BG : UiKit.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(null, Typeface.BOLD);
        button.setMinHeight(dp(46));
        button.setPadding(dp(10), dp(9), dp(10), dp(9));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),
            UiKit.surface(owner, primary ? UiKit.ACCENT : UiKit.ROW, 12, primary ? UiKit.ACCENT : UiKit.ROW_BORDER, 1), null));
        button.setFocusable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        button.setOnClickListener(v -> action.run());
        return button;
    }

    LinearLayout content() { return body; }
    void resize() {
        WindowMetrics metrics=owner.getWindowManager().getCurrentWindowMetrics();
        Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        Rect area=owner instanceof MainActivity?((MainActivity)owner).dialogSafeBounds(getWindow().getDecorView().getRootWindowInsets()):new Rect(bars.left,bars.top,metrics.getBounds().width()-bars.right,metrics.getBounds().height()-bars.bottom);
        if(area.equals(lastArea))return;lastArea=new Rect(area);
        maxHeight=Math.max(60,Math.round((area.height()-dp(20))/owner.getResources().getDisplayMetrics().density));
        int width=Math.min(dp(480),Math.max(1,area.width()-dp(20)));
        WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.gravity=Gravity.TOP|Gravity.LEFT;
        attributes.x=area.left-bars.left+(area.width()-width)/2;attributes.y=Math.max(0,area.top-bars.top+dp(10));
        attributes.width=width;attributes.height=WindowManager.LayoutParams.WRAP_CONTENT;getWindow().setAttributes(attributes);scroll.requestLayout();scroll.post(this::ensureInputVisible);
    }

    public void show() {
        super.show();
        resize();
        if (!fields.isEmpty()) {
            EditText first = fields.get(0).input;
            first.requestFocus();
            first.postDelayed(() -> {
                View focused = getCurrentFocus();
                if (isShowing() && focused instanceof EditText) owner.getSystemService(InputMethodManager.class).showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT);
            }, 160);
        }
    }

    public void dismiss() {
        View focused = getCurrentFocus();
        if (focused != null) owner.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
        super.dismiss();
    }
}
