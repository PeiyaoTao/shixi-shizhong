package com.foldstandby.clock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

/** Shared app-owned dialogs, including create().show() and generic choice controls. */
final class AppDialogs {
    private AppDialogs() {}

    static AlertDialog.Builder builder(Context context) { return new Builder(context); }

    private static Activity owner(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    static final class Builder extends AlertDialog.Builder {
        private final Context source;
        Builder(Context context) { super(context, R.style.FoldDialogTheme); source = context; }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog.setOnShowListener(ignored -> prepare(dialog, source));
            return dialog;
        }
        @Override public AlertDialog show() {
            AlertDialog dialog = super.show();
            prepare(dialog, source);
            return dialog;
        }
    }

    private static void prepare(AlertDialog dialog, Context context) {
        View decor = dialog.getWindow().getDecorView();
        if (Boolean.TRUE.equals(decor.getTag(R.id.app_dialog_prepared))) return;
        decor.setTag(R.id.app_dialog_prepared, true);
        dialog.getWindow().setBackgroundDrawable(UiKit.surface(context, UiKit.PANEL, 24, UiKit.SELECTOR_BORDER, 1));
        dialog.getWindow().setDimAmount(.62f);
        dialog.getWindow().setNavigationBarColor(UiKit.BG);
        int titleId = context.getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = dialog.findViewById(titleId);
        if (title != null) {
            title.setTextColor(UiKit.TEXT);
            title.setTextSize(UiKit.sp(17));
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(3);
            title.setAccessibilityHeading(true);
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextColor(UiKit.MUTED);
            message.setTextSize(UiKit.sp(13));
            message.setLineSpacing(UiKit.dp(context, 3), 1);
        }
        ListView list = dialog.getListView();
        if (list != null) {
            list.setBackgroundColor(Color.TRANSPARENT);
            list.setDivider(new ColorDrawable(UiKit.DIVIDER));
            list.setDividerHeight(UiKit.dp(context, 1));
            list.setPadding(UiKit.dp(context, 6), 0, UiKit.dp(context, 6), 0);
            list.setClipToPadding(false);
            list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                public void onChildViewAdded(View parent, View child) { styleChoice(context, child); }
                public void onChildViewRemoved(View parent, View child) {}
            });
            for (int i = 0; i < list.getChildCount(); i++) styleChoice(context, list.getChildAt(i));
        }
        for (int role : new int[] { DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL }) {
            Button button = dialog.getButton(role);
            if (button == null) continue;
            boolean primary = role == DialogInterface.BUTTON_POSITIVE;
            button.setAllCaps(false);
            button.setTextColor(primary ? UiKit.BG : UiKit.ACCENT);
            button.setTextSize(UiKit.sp(12));
            button.setMinHeight(UiKit.dp(context, 44));
            button.setMaxLines(2);
            button.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 6), UiKit.dp(context, 12), UiKit.dp(context, 6));
            button.setBackground(UiKit.surface(context, primary ? UiKit.ACCENT : UiKit.ROW, 12));
        }
        Activity activity = owner(context);
        if (activity instanceof MainActivity) ((MainActivity) activity).onDialogShown(dialog);
    }

    private static void styleChoice(Context context, View view) {
        if (view instanceof TextView) {
            TextView row = (TextView) view;
            row.setTextColor(UiKit.TEXT);
            row.setTextSize(UiKit.sp(13));
            row.setMinHeight(UiKit.dp(context, 48));
            row.setPadding(UiKit.dp(context, 16), UiKit.dp(context, 10), UiKit.dp(context, 16), UiKit.dp(context, 10));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleChoice(context, group.getChildAt(i));
        }
    }
}
