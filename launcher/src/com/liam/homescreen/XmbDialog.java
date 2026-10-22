package com.liam.homescreen;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ListView;
import android.widget.TextView;

/** Dialogs drawn in the XMB's style (theme colours, glass card, the same fonts), instead of Android's defaults. */
final class XmbDialog {
    /** A compact list closes itself after this long with no remote input, so a source list does not sit forever. */
    private static final long POPUP_IDLE_MS = 3 * 60 * 1000L;
    interface Pick {
        void onPick(int which);
    }

    interface Text {
        void onText(String text);
    }

    private XmbDialog() {
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static boolean darkInk() {
        return XmbView.iconColor == 0xFF000000;
    }

    private static GradientDrawable cardBackground(Context c) {
        return Glass.card(dp(c, 14));
    }

    private static StateListDrawable selector(Context c) {
        return Glass.focusSelector(dp(c, 10));
    }

    private static TextView styledText(Context c, String text, float sp, String family) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(XmbView.iconColor);
        t.setShadowLayer(0f, 0, 0, 0);
        return t;
    }

    private static Dialog frame(Context c, String title, View body, View buttons) {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        d.getWindow().setDimAmount(0.48f);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground(c));
        card.setPadding(dp(c, 22), dp(c, 18), dp(c, 22), dp(c, 14));
        TextView tt = styledText(c, title, 20, "sans-serif-medium");
        tt.setPadding(dp(c, 8), 0, dp(c, 8), dp(c, 10));
        card.addView(tt);
        card.addView(body);
        if (buttons != null) card.addView(buttons);
        d.setContentView(card);

        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        d.getWindow().setLayout((int) (dm.widthPixels * 0.5f), ViewGroup.LayoutParams.WRAP_CONTENT);
        d.getWindow().setGravity(Gravity.CENTER);
        return d;
    }

    /** A list to pick from; the remote's up/down moves, OK picks, Back cancels. */
    static void list(final Context c, String title, String[] items, final Pick pick) {
        final Dialog[] holder = new Dialog[1];
        ListView lv = new ListView(c);
        lv.setDivider(null);
        lv.setSelector(selector(c));
        lv.setVerticalScrollBarEnabled(false);
        lv.setAdapter(new ArrayAdapter<String>(c, 0, items) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                TextView t = convert instanceof TextView ? (TextView) convert : styledText(c, "", 17, "sans-serif");
                t.setText(getItem(position));
                t.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return t;
            }
        });
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                holder[0].dismiss();
                pick.onPick(position);
            }
        });
        int rowPx = dp(c, 44);
        int max = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.55f);
        lv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(items.length * rowPx, max)));
        holder[0] = frame(c, title, lv, null);
        holder[0].show();
    }

    /**
     * A compact list in a small window under {@code anchor} (for example the strip under one Multiview
     * quadrant), instead of a dialog that takes over the screen. The list is as wide as the anchor.
     * Up/down moves, OK picks, Back closes it. If the list would leave the screen, it opens above the anchor.
     * Any key starts a three-minute wait over; with no input it closes itself.
     */
    static PopupWindow popup(final android.app.Activity a, View anchor, String title, String[] items, int maxRows,
                             final Pick pick) {
        final Context c = a;
        int width = anchor.getWidth();
        if (width <= 0) width = dp(c, 280);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground(c));
        card.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        TextView tt = styledText(c, title, 13, "sans-serif-medium");
        tt.setPadding(dp(c, 8), 0, dp(c, 8), dp(c, 4));
        card.addView(tt);

        final ListView lv = new ListView(c);
        lv.setDivider(null);
        lv.setSelector(selector(c));
        lv.setVerticalScrollBarEnabled(false);
        lv.setFocusable(true);
        lv.setFocusableInTouchMode(true);
        lv.setItemsCanFocus(false);
        lv.setAdapter(new ArrayAdapter<String>(c, 0, items) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                TextView t = convert instanceof TextView ? (TextView) convert : styledText(c, "", 14, "sans-serif");
                t.setText(getItem(position));
                t.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return t;
            }
        });
        int rowPx = dp(c, 34);
        int rows = Math.min(items.length, Math.max(maxRows, 1));
        lv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rows * rowPx));
        card.addView(lv);

        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = card.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int x = loc[0];
        int below = loc[1] + anchor.getHeight();
        int y = below + height > dm.heightPixels ? loc[1] - height : below;
        if (y < 0) y = 0;
        if (x + width > dm.widthPixels) x = dm.widthPixels - width;
        if (x < 0) x = 0;

        final PopupWindow pw = new PopupWindow(card, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        final Handler ui = new Handler(Looper.getMainLooper());
        final Runnable idleClose = new Runnable() {
            @Override
            public void run() {
                pw.dismiss();
            }
        };
        final Runnable arm = new Runnable() {
            @Override
            public void run() {
                ui.removeCallbacks(idleClose);
                ui.postDelayed(idleClose, POPUP_IDLE_MS);
            }
        };
        pw.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                ui.removeCallbacks(idleClose);
            }
        });
        lv.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) arm.run();
                return false;
            }
        });
        lv.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                arm.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                pw.dismiss();
                pick.onPick(position);
            }
        });
        pw.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
        arm.run();
        lv.post(new Runnable() {
            @Override
            public void run() {
                lv.requestFocus();
                lv.setSelection(0);
            }
        });
        return pw;
    }

    private static TextView button(Context c, String label) {
        TextView b = styledText(c, label, 17, "sans-serif-medium");
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 22), dp(c, 9), dp(c, 22), dp(c, 9));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(selector(c));
        return b;
    }

    private static LinearLayout buttonRow(Context c, TextView... buttons) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(c, 10), 0, 0);
        for (TextView b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 10);
            row.addView(b, lp);
        }
        return row;
    }

    /** Ask for a line of text with the TV's on-screen keyboard. */
    static void text(final Context c, String title, String current, String hint, final Text result) {
        final EditText input = new EditText(c);
        input.setText(current);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(XmbView.iconColor);
        input.setHintTextColor((XmbView.iconColor & 0x00FFFFFF) | 0x88000000);
        input.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        input.setTextSize(18);
        input.setBackground(underline(c));
        input.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        input.setSelection(input.getText().length());
        TextView save = button(c, "Save");
        TextView cancel = button(c, "Cancel");
        final Dialog d = frame(c, title, input, buttonRow(c, cancel, save));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                result.onText(input.getText().toString());
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private static GradientDrawable underline(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(Math.max(2, dp(c, 1)), (XmbView.iconColor & 0x00FFFFFF) | 0x99000000);
        g.setCornerRadius(dp(c, 8));
        return g;
    }

    /** A yes / no question. */
    static void confirm(Context c, String title, String message, String yesLabel, final Runnable yes) {
        TextView body = styledText(c, message, 15, "sans-serif");
        body.setPadding(dp(c, 8), 0, dp(c, 8), dp(c, 4));
        TextView ok = button(c, yesLabel);
        TextView cancel = button(c, "Cancel");
        final Dialog d = frame(c, title, body, buttonRow(c, cancel, ok));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                yes.run();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.show();
        cancel.requestFocus();
    }
}
