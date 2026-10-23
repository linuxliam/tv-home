package com.liam.homescreen;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A wall of one to four windows. Each can show a live HDMI input, a web page, an IPTV channel, or a
 * widget (clock, weather, calendar). The home screen's background shows in the gaps. Focusing a
 * window opens its quick actions (Sound, Source, Full screen). A bar under those choices sets how
 * many windows are on screen. Hold OK on a window to pick it up, aim with the pad, then OK to drop
 * it. OK hears it, or enters a web page. Choices are remembered. Back leaves an action, then full
 * screen, then the wall.
 */
public class MultiviewActivity extends Activity {
    private static final String TAG = "Multiview";
    private static final int CELLS = 4;
    /** The port the Apple TV is on, used for the default layout. */
    static final String APPLE_TV_SUFFIX = "/HW3";
    private static final long DOUBLE_CLICK_MS = 900;
    /** How long a button must stay focused before its name appears. */
    private static final long TIP_DELAY_MS = 700;
    /** Quick actions and the window-count bar share one idle timer. */
    private static final long CHROME_HIDE_MS = 8000;

    private static final String WEB = "web:";
    private static final String IPTV = "iptv:";
    private static final String WIDGET = "widget:";
    private static final String[][] BUILT_IN_SITES = {
            {"YouTube", "https://www.youtube.com/tv"},
            {"Twitch", "https://www.twitch.tv"},
    };
    private static final String[] WIDGETS = {"clock", "weather", "calendar"};
    private static final String[] WIDGET_NAMES = {"Clock", "Weather", "Calendar"};
    private static final String TV_UA = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36";

    private final TvView[] tvs = new TvView[CELLS];
    private final WidgetView[] panels = new WidgetView[CELLS];
    private final TextView[] labels = new TextView[CELLS];
    private final FrameLayout[] holders = new FrameLayout[CELLS];
    private final View[] focusRings = new View[CELLS];
    private final LinearLayout[] cells = new LinearLayout[CELLS];
    private final LinearLayout[] actions = new LinearLayout[CELLS];
    private final View[] pictures = new View[CELLS];
    private final View[] soundBtns = new View[CELLS];
    private final View[] sourceBtns = new View[CELLS];
    private final View[] fullBtns = new View[CELLS];
    private LinearLayout grid;
    private LinearLayout countBar;
    private final TextView[] countBtns = new TextView[CELLS];
    private int expanded = -1;
    private int quadCount = 4;
    /** Visual slot to cell. Slot 0 is the top-left window. */
    private final int[] order = {0, 1, 2, 3};
    private final boolean[] live = new boolean[CELLS];
    private boolean started;
    private int focusedCell;
    private int moving = -1;
    private int moveDir = -1;
    private long moveClock;
    private float shakeAmp;
    private float leanAmp;
    /** Focused cell's action row is kept in-layout (invisible) so the video does not resize. */
    private boolean actionsParked;
    private PopupWindow tipPopup;
    private PopupWindow sourcePopup;
    private TextView tipLabel;
    private int tipGen;
    private final WebView[] webs = new WebView[CELLS];
    private final VideoView[] videos = new VideoView[CELLS];
    private final MediaPlayer[] players = new MediaPlayer[CELLS];
    private final String[] ids = new String[CELLS];
    private final List<TvInputInfo> inputs = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Store store;
    private TvInputManager tim;
    private int listening = -1;
    private int webActive = -1;
    private int lastClickCell = -1;
    private long lastClickAt;
    private int tuneGen;
    /** True when the user pressed Back or Home — not when CEC briefly covers us. */
    private boolean leaveWhenStopped;
    /** CEC covered us after an HDMI tune; keep sessions and come back instead of tearing down. */
    private boolean cecCover;
    private int reclaimTries;
    /** HDMI ports stay dark until OK — tuning them auto-selects that input on this TV. */
    private final boolean[] hdmiOn = new boolean[CELLS];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < CELLS; i++) {
                if (live[i] && isWidget(ids[i])) renderWidget(i);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new Store(this);
        // The same backdrop as the home screen, showing through the gaps between quadrants.
        XmbView look = new XmbView(this);
        look.setTheme(store.get("theme", "electric_blue"));
        look.setBackgroundStyle("none");
        look.setBackgroundSpeed(0f);
        look.setIconMode(store.get("icons", "auto"));
        look.setShadows(store.bool("shadows", true));
        look.setBackdropOnly(true);

        tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        for (TvInputInfo info : tim.getTvInputList()) {
            // Physical inputs only. The AirPlay input blanked the TV when shown in a quadrant.
            if (!info.isPassthroughInput()) continue;
            if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED) continue;
            inputs.add(info);
        }
        final Context ctx = this;
        Collections.sort(inputs, new Comparator<TvInputInfo>() {
            @Override
            public int compare(TvInputInfo a, TvInputInfo b) {
                return inputName(ctx, a).compareToIgnoreCase(inputName(ctx, b));
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(look, new FrameLayout.LayoutParams(-1, -1));

        loadLayout();
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);

        // Windows with a little padding so the background shows between them.
        for (int i = 0; i < CELLS; i++) {
            final int idx = i;
            ids[i] = store.has("mvx" + i) ? emptyToNull(store.get("mvx" + i, "")) : defaultFor(i);
            if (ids[i] != null && !known(ids[i])) ids[i] = null;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);

            TvView tv = new TvView(this);
            // Media overlay stays under window chrome / focus ring (unlike ZOrderOnTop).
            tv.setZOrderMediaOverlay(true);
            tv.setFocusable(false);
            tv.setStreamVolume(0f);
            tv.setCallback(new TvView.TvInputCallback() {
                @Override
                public void onConnectionFailed(String inputId) {
                    Log.i(TAG, "q" + idx + " CONNECTION FAILED");
                    setStatus(idx, "failed to start");
                }

                @Override
                public void onVideoAvailable(String inputId) {
                    Log.i(TAG, "q" + idx + " VIDEO AVAILABLE");
                    setStatus(idx, idx == listening ? "live, sound on" : "live");
                    // Only reclaim if CEC already stole focus — don't fight Home.
                    if (hdmiOn[idx] && !hasWindowFocus()) reclaimWall();
                }

                @Override
                public void onVideoUnavailable(String inputId, int reason) {
                    Log.i(TAG, "q" + idx + " video unavailable reason=" + reason);
                    setStatus(idx, reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
                            ? "no signal" : "starting...");
                }
            });
            WidgetView panel = new WidgetView(this);
            panel.setFocusable(false);
            FrameLayout holder = new FrameLayout(this);
            holder.setId(View.generateViewId());
            holder.setFocusable(true);
            holder.setClickable(true);
            holder.setDefaultFocusHighlightEnabled(false);
            holder.addView(tv, new FrameLayout.LayoutParams(-1, -1));
            holder.addView(panel, new FrameLayout.LayoutParams(-1, -1));
            // Ring sits in a margin around the holder so SurfaceView / WebView can't cover the stroke.
            FrameLayout stack = new FrameLayout(this);
            int ringPad = dp(3);
            FrameLayout.LayoutParams holderLp = new FrameLayout.LayoutParams(-1, -1);
            holderLp.setMargins(ringPad, ringPad, ringPad, ringPad);
            stack.addView(holder, holderLp);
            View ring = new View(this);
            ring.setBackground(focusRingDrawable(false));
            ring.setVisibility(View.INVISIBLE);
            ring.setFocusable(false);
            ring.setClickable(false);
            stack.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            focusRings[i] = ring;
            holder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    long now = SystemClock.uptimeMillis();
                    boolean doubleOk = lastClickCell == idx && now - lastClickAt < DOUBLE_CLICK_MS;
                    if (doubleOk) {
                        lastClickCell = -1;
                        chooseType(idx);
                        return;
                    }
                    lastClickCell = idx;
                    lastClickAt = now;
                    if (isWeb(ids[idx])) {
                        enterWeb(idx);
                        return;
                    }
                    if (isHdmi(ids[idx]) && !hdmiOn[idx]) {
                        startHdmi(idx);
                        return;
                    }
                    listen(idx);
                }
            });
            holder.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    beginMove(idx);
                    return true;
                }
            });
            holder.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    focusRings[idx].setVisibility(has ? View.VISIBLE : View.INVISIBLE);
                    focusRings[idx].setBackground(focusRingDrawable(has));
                    if (has) {
                        focusedCell = idx;
                        revealChrome(idx);
                        clearTip();
                    }
                }
            });
            cell.addView(stack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            tvs[i] = tv;
            panels[i] = panel;
            holders[i] = holder;
            pictures[i] = holder;
            cells[i] = cell;

            LinearLayout menu = new LinearLayout(this);
            menu.setOrientation(LinearLayout.HORIZONTAL);
            menu.setGravity(Gravity.CENTER_VERTICAL);
            menu.setVisibility(View.INVISIBLE);
            menu.setBackground(chromeDock());
            menu.setPadding(dp(12), dp(6), dp(8), dp(6));
            TextView label = menuTitle();
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelLp.gravity = Gravity.CENTER_VERTICAL;
            menu.addView(label, labelLp);
            labels[i] = label;
            View sound = action(idx, "Sound", XmbView.G_SOUND, new Runnable() {
                @Override
                public void run() {
                    // Sound never arms HDMI — OK on the picture does that.
                    listen(idx);
                }
            });
            View source = action(idx, "Source", XmbView.G_INPUT, new Runnable() {
                @Override
                public void run() {
                    chooseType(idx);
                }
            });
            View full = action(idx, "Full screen", XmbView.G_PLAY, new Runnable() {
                @Override
                public void run() {
                    expand(idx);
                }
            });
            soundBtns[i] = sound;
            sourceBtns[i] = source;
            fullBtns[i] = full;
            menu.addView(sound);
            menu.addView(source);
            menu.addView(full);
            actions[i] = menu;
            LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            menuLp.setMargins(dp(6), dp(4), dp(6), dp(6));
            cell.addView(menu, menuLp);

            setStatus(i, ids[i] == null ? "empty" : "starting...");
        }
        countBar = buildCountBar();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        root.setClipChildren(false);
        column.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.gravity = Gravity.CENTER_HORIZONTAL;
        countLp.setMargins(0, dp(2), 0, dp(12));
        column.addView(countBar, countLp);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        layoutQuadrants();
        setContentView(root);
    }

    private LinearLayout buildCountBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(14), dp(8), dp(14), dp(10));
        bar.setVisibility(View.INVISIBLE);
        bar.setBackground(chromeDock());
        bar.setAlpha(0.88f);
        TextView title = menuTitle();
        title.setText("Windows");
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setTextSize(12);
        title.setAlpha(0.62f);
        title.setPadding(dp(4), 0, dp(10), 0);
        bar.addView(title);
        for (int n = 1; n <= CELLS; n++) {
            final int count = n;
            TextView b = new TextView(this);
            b.setId(View.generateViewId());
            b.setText(String.valueOf(n));
            b.setTextSize(14);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setTextColor(XmbView.iconColor);
            b.setShadowLayer(0f, 0, 0, 0);
            b.setFocusable(true);
            b.setClickable(true);
            b.setDefaultFocusHighlightEnabled(false);
            b.setBackground(countBg());
            b.setPadding(dp(12), dp(4), dp(12), dp(4));
            b.setMinWidth(dp(34));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setQuadCount(count);
                }
            });
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    if (has) retargetCountUp();
                }
            });
            countBtns[n - 1] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), 0, dp(4), 0);
            bar.addView(b, lp);
        }
        for (int i = 0; i < CELLS; i++) {
            countBtns[i].setNextFocusLeftId(countBtns[i == 0 ? 0 : i - 1].getId());
            countBtns[i].setNextFocusRightId(countBtns[i == CELLS - 1 ? CELLS - 1 : i + 1].getId());
            // Down returns to the focused window (wired in retargetCountUp / revealChrome).
            countBtns[i].setNextFocusDownId(View.NO_ID);
        }
        markCount();
        return bar;
    }

    /** An icon button. Its name appears above it only after it has kept focus for a moment. */
    private LinearLayout action(final int idx, final String name, int glyph, final Runnable run) {
        final LinearLayout b = new LinearLayout(this);
        b.setId(View.generateViewId());
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setDefaultFocusHighlightEnabled(false);
        b.setBackground(actionBg());
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new XmbView.GlyphDrawable(glyph, 0));
        int s = dp(24);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
        ilp.setMargins(dp(2), 0, dp(2), 0);
        b.addView(icon, ilp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (moving >= 0) return;
                run.run();
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                beginMove(idx);
                return true;
            }
        });
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (has) {
                    revealChrome(idx);
                    armTip(b, name);
                } else clearTip();
            }
        });
        return b;
    }

    private TextView tipView() {
        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(XmbView.iconColor);
        t.setShadowLayer(0f, 0, 0, 0);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setBackground(Glass.dock(dp(10)));
        return t;
    }

    /** The name floats over the picture. It is its own window so the video surface cannot cover it. */
    private void armTip(final View button, final String name) {
        tipGen++;
        final int gen = tipGen;
        dismissTip();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != tipGen || !button.hasFocus() || isFinishing()) return;
                if (tipLabel == null) tipLabel = tipView();
                tipLabel.setText(name);
                tipLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int w = tipLabel.getMeasuredWidth();
                int h = tipLabel.getMeasuredHeight();
                int[] at = new int[2];
                button.getLocationInWindow(at);
                int x = at[0] + button.getWidth() / 2 - w / 2;
                int y = at[1] - h - dp(4);
                DisplayMetrics dm = getResources().getDisplayMetrics();
                if (x < dp(8)) x = dp(8);
                if (x + w > dm.widthPixels - dp(8)) x = dm.widthPixels - dp(8) - w;
                if (y < dp(8)) y = at[1] + button.getHeight() + dp(4);
                if (tipPopup == null) {
                    tipPopup = new PopupWindow(tipLabel, w, h, false);
                    tipPopup.setFocusable(false);
                    tipPopup.setTouchable(false);
                    tipPopup.setClippingEnabled(true);
                    tipPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                } else {
                    tipPopup.setWidth(w);
                    tipPopup.setHeight(h);
                }
                if (tipPopup.isShowing()) tipPopup.update(x, y, w, h);
                else tipPopup.showAtLocation(button, Gravity.NO_GRAVITY, x, y);
            }
        }, TIP_DELAY_MS);
    }

    private void clearTip() {
        tipGen++;
        dismissTip();
    }

    private void dismissTip() {
        if (tipPopup != null && tipPopup.isShowing()) tipPopup.dismiss();
    }

    private void wireActions(int idx) {
        View picture = pictures[idx];
        View sound = soundBtns[idx];
        View source = sourceBtns[idx];
        View full = fullBtns[idx];
        if (expanded >= 0) {
            picture.setNextFocusLeftId(picture.getId());
            picture.setNextFocusRightId(picture.getId());
            picture.setNextFocusUpId(picture.getId());
            picture.setNextFocusDownId(picture.getId());
            return;
        }
        int left = pictures[neighbor(idx, KeyEvent.KEYCODE_DPAD_LEFT)].getId();
        int right = pictures[neighbor(idx, KeyEvent.KEYCODE_DPAD_RIGHT)].getId();
        int above = pictures[neighbor(idx, KeyEvent.KEYCODE_DPAD_UP)].getId();
        int belowCell = focusBelow(idx);
        int below = belowCell == idx ? countBtns[Math.max(0, quadCount - 1)].getId() : pictures[belowCell].getId();
        picture.setNextFocusLeftId(left);
        picture.setNextFocusRightId(right);
        picture.setNextFocusUpId(above);
        picture.setNextFocusDownId(sound.getId());
        sound.setNextFocusUpId(picture.getId());
        sound.setNextFocusDownId(below);
        sound.setNextFocusLeftId(left);
        sound.setNextFocusRightId(source.getId());
        source.setNextFocusUpId(picture.getId());
        source.setNextFocusDownId(below);
        source.setNextFocusLeftId(sound.getId());
        source.setNextFocusRightId(full.getId());
        full.setNextFocusUpId(picture.getId());
        full.setNextFocusDownId(below);
        full.setNextFocusLeftId(source.getId());
        full.setNextFocusRightId(right);
    }

    private void wireAll() {
        int shown = expanded >= 0 ? 1 : quadCount;
        for (int s = 0; s < shown; s++) {
            wireActions(expanded >= 0 ? expanded : order[s]);
        }
    }

    private TextView menuTitle() {
        TextView label = new TextView(this);
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        label.setTextColor(XmbView.iconColor);
        label.setShadowLayer(0f, 0, 0, 0);
        label.setAlpha(0.88f);
        label.setPadding(dp(6), 0, dp(10), dp(2));
        return label;
    }

    /** Dark theme dock under a window / along the Windows bar. */
    private GradientDrawable chromeDock() {
        GradientDrawable g = Glass.dock(dp(10));
        g.setAlpha(200); // calmer Multiview chrome (Superdesign)
        return g;
    }

    private GradientDrawable focusRingDrawable(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(4));
        if (on) {
            // Superdesign calm: thin bright stroke outside the surface.
            g.setStroke(Math.max(2, dp(1.5f)), Glass.accentStroke(0xF2000000));
        } else {
            g.setStroke(0, Color.TRANSPARENT);
        }
        return g;
    }

    private StateListDrawable actionBg() {
        return Glass.focusSelector(dp(10));
    }

    private StateListDrawable countBg() {
        return Glass.focusSelector(dp(10));
    }

    /** Bring the focused window's chrome back and restart the shared idle timer. */
    private void revealChrome(int idx) {
        actionsParked = false;
        applyActionVisibility(idx);
        revealCountBar();
        ui.removeCallbacks(hideChrome);
        if (expanded < 0 && idx >= 0) ui.postDelayed(hideChrome, CHROME_HIDE_MS);
    }

    /**
     * Only the focused window keeps an action strip. When idle-hidden it stays INVISIBLE so the
     * video does not resize (which flashed a black bar). Every other window uses GONE.
     */
    private void applyActionVisibility(int current) {
        for (int i = 0; i < CELLS; i++) {
            if (actions[i] == null) continue;
            if (expanded >= 0 || current < 0 || i != current) {
                actions[i].setVisibility(View.GONE);
            } else {
                actions[i].setVisibility(actionsParked ? View.INVISIBLE : View.VISIBLE);
            }
        }
        retargetCountUp();
    }

    private final Runnable hideChrome = new Runnable() {
        @Override
        public void run() {
            if (expanded >= 0 || moving >= 0) return;
            int cell = cellOf(getCurrentFocus());
            if (cell < 0) cell = focusedCell;
            View focus = getCurrentFocus();
            // Park chrome without stealing focus from the count bar or picture.
            if (webActive < 0 && cell >= 0 && focus != null && focus != pictures[cell]
                    && !countFocused(focus)) {
                pictures[cell].requestFocus();
            }
            clearTip();
            dismissSourcePopup();
            actionsParked = true;
            applyActionVisibility(cell);
            if (countBar != null && expanded < 0) {
                if (countFocused(getCurrentFocus()) && slotOf(focusedCell) >= 0) {
                    pictures[focusedCell].requestFocus();
                }
                countBar.setVisibility(View.INVISIBLE);
            }
        }
    };

    private int cellOf(View v) {
        while (v != null) {
            for (int i = 0; i < CELLS; i++) if (v == cells[i]) return i;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return -1;
    }

    private void expand(int idx) {
        if (moving >= 0) cancelMove();
        expanded = idx;
        layoutQuadrants();
        pictures[idx].requestFocus();
        showFullDock(); // so the way back is visible as soon as the window fills the screen
    }

    private void collapse() {
        int idx = expanded;
        hideFullDockNow(true);
        expanded = -1;
        layoutQuadrants();
        if (idx >= 0) pictures[idx].requestFocus();
        revealCountBar();
    }

    /** Place the visible windows. 1 and 2 share a row, 3 is a tall window beside two stacked, 4 is a grid. */
    private void layoutQuadrants() {
        for (int i = 0; i < CELLS; i++) {
            cells[i].setVisibility(View.GONE);
            if (cells[i].getParent() instanceof ViewGroup) ((ViewGroup) cells[i].getParent()).removeView(cells[i]);
        }
        grid.removeAllViews();
        boolean full = expanded >= 0;
        int gap = full ? 0 : dp(8);
        grid.setPadding(full ? 0 : dp(14), full ? 0 : dp(12), full ? 0 : dp(14), full ? 0 : dp(6));
        if (full) {
            addRow(new int[]{expanded}, 0);
            if (countBar != null) {
                countBar.setVisibility(View.GONE);
                ui.removeCallbacks(hideChrome);
            }
            actionsParked = false;
            applyActionVisibility(-1);
        } else if (quadCount == 3) {
            LinearLayout split = new LinearLayout(this);
            split.setOrientation(LinearLayout.HORIZONTAL);
            split.setClipChildren(false);
            place(split, order[0], gap, false);
            LinearLayout stack = new LinearLayout(this);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setClipChildren(false);
            place(stack, order[1], gap, true);
            place(stack, order[2], gap, true);
            split.addView(stack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            grid.addView(split, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else if (quadCount == 4) {
            addRow(new int[]{order[0], order[1]}, gap);
            addRow(new int[]{order[2], order[3]}, gap);
        } else {
            int[] slots = new int[quadCount];
            for (int s = 0; s < quadCount; s++) slots[s] = order[s];
            addRow(slots, gap);
        }
        syncLive();
        wireAll();
        markCount();
        if (!full) applyActionVisibility(focusedCell);
        else retargetCountUp();
    }

    private void addRow(int[] cellsInRow, int gap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (int idx : cellsInRow) place(row, idx, gap, false);
        grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void place(LinearLayout row, int idx, int gap, boolean vertical) {
        cells[idx].setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = vertical
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(gap, gap, gap, gap);
        row.addView(cells[idx], lp);
    }

    private void syncLive() {
        if (!started) return;
        boolean[] want = new boolean[CELLS];
        if (expanded >= 0) want[expanded] = true;
        else for (int s = 0; s < quadCount; s++) want[order[s]] = true;
        for (int i = 0; i < CELLS; i++) {
            if (want[i] == live[i]) continue;
            live[i] = want[i];
            if (want[i]) tuneCell(i);
            else showOnly(i, false, false, false, false);
        }
    }

    private int gridCols() {
        return quadCount == 1 ? 1 : 2;
    }

    private int gridRows() {
        return quadCount == 4 ? 2 : 1;
    }

    /** The cell in that direction, or the same cell when the window is already on that edge. */
    private int neighbor(int cell, int key) {
        int slot = slotOf(cell);
        if (slot < 0 || expanded >= 0) return cell;
        if (quadCount == 3) {
            int dest = slot;
            if (slot == 0 && key == KeyEvent.KEYCODE_DPAD_RIGHT) dest = 1;
            else if (slot == 0 && key == KeyEvent.KEYCODE_DPAD_DOWN) dest = 2;
            else if (slot == 1 && key == KeyEvent.KEYCODE_DPAD_LEFT) dest = 0;
            else if (slot == 1 && key == KeyEvent.KEYCODE_DPAD_DOWN) dest = 2;
            else if (slot == 2 && key == KeyEvent.KEYCODE_DPAD_LEFT) dest = 0;
            else if (slot == 2 && key == KeyEvent.KEYCODE_DPAD_UP) dest = 1;
            return dest == slot ? cell : order[dest];
        }
        int cols = gridCols();
        int col = slot % cols;
        int row = slot / cols;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) col--;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) col++;
        else if (key == KeyEvent.KEYCODE_DPAD_UP) row--;
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) row++;
        if (col < 0 || row < 0 || col >= cols || row >= gridRows()) return cell;
        int dest = row * cols + col;
        if (dest < 0 || dest >= quadCount) return cell;
        return order[dest];
    }

    /** Where Down leaves a window's actions: the picture below, or the same window when the count bar is next. */
    private int focusBelow(int idx) {
        if (quadCount == 3 && slotOf(idx) == 0) return idx;
        return neighbor(idx, KeyEvent.KEYCODE_DPAD_DOWN);
    }

    private int slotOf(int cell) {
        int n = expanded >= 0 ? 1 : quadCount;
        for (int s = 0; s < n; s++) if ((expanded >= 0 ? expanded : order[s]) == cell) return s;
        return -1;
    }

    private void setQuadCount(int n) {
        if (n < 1 || n > CELLS || n == quadCount) return;
        if (moving >= 0) cancelMove();
        if (webActive >= 0) {
            int webSlot = -1;
            for (int s = 0; s < n; s++) if (order[s] == webActive) webSlot = s;
            if (webSlot < 0) leaveWeb();
        }
        quadCount = n;
        if (expanded >= 0) expanded = -1;
        saveLayout();
        layoutQuadrants();
        revealCountBar();
        if (slotOf(focusedCell) < 0) {
            focusedCell = order[0];
            pictures[focusedCell].requestFocus();
        }
    }

    private void markCount() {
        for (int i = 0; i < CELLS; i++) if (countBtns[i] != null) countBtns[i].setSelected(i + 1 == quadCount);
    }

    private void retargetCountUp() {
        if (countBtns[0] == null || focusedCell < 0 || pictures[focusedCell] == null) return;
        int up = pictures[focusedCell].getId();
        if (actions[focusedCell] != null && actions[focusedCell].getVisibility() == View.VISIBLE) {
            up = soundBtns[focusedCell].getId();
        }
        int down = pictures[focusedCell].getId();
        for (int i = 0; i < CELLS; i++) {
            countBtns[i].setNextFocusUpId(up);
            countBtns[i].setNextFocusDownId(down);
        }
    }

    private void revealCountBar() {
        if (expanded >= 0 || countBar == null) return;
        countBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideChrome);
        if (expanded < 0 && focusedCell >= 0) ui.postDelayed(hideChrome, CHROME_HIDE_MS);
    }

    private boolean countFocused(View v) {
        while (v != null) {
            if (v == countBar) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    private void loadLayout() {
        int n = 4;
        try {
            n = Integer.parseInt(store.get("mvcount", "4"));
        } catch (NumberFormatException ignored) {
            n = 4;
        }
        if (n < 1 || n > CELLS) n = 4;
        quadCount = n;
        String raw = store.get("mvorder", "");
        if (raw.isEmpty()) return;
        String[] parts = raw.split(",");
        if (parts.length != CELLS) return;
        int[] next = new int[CELLS];
        boolean[] seen = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) {
            try {
                next[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return;
            }
            if (next[i] < 0 || next[i] >= CELLS || seen[next[i]]) return;
            seen[next[i]] = true;
        }
        System.arraycopy(next, 0, order, 0, CELLS);
    }

    private void saveLayout() {
        store.put("mvcount", String.valueOf(quadCount));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CELLS; i++) {
            if (i > 0) sb.append(',');
            sb.append(order[i]);
        }
        store.put("mvorder", sb.toString());
    }

    /** Hold OK: the window shakes until a direction is chosen and OK drops it there. */
    private void beginMove(int idx) {
        if (expanded >= 0 || quadCount < 2 || slotOf(idx) < 0) return;
        if (moving == idx) return;
        if (moving >= 0) cancelMove();
        clearTip();
        ui.removeCallbacks(hideChrome);
        actionsParked = true;
        applyActionVisibility(idx);
        moving = idx;
        moveDir = -1;
        moveClock = SystemClock.uptimeMillis();
        shakeAmp = dp(4);
        leanAmp = dp(12);
        ui.removeCallbacks(shake);
        ui.post(shake);
    }

    private final Runnable shake = new Runnable() {
        @Override
        public void run() {
            if (moving < 0 || cells[moving] == null) return;
            float wave = (float) Math.sin((SystemClock.uptimeMillis() - moveClock) / 150.0 * Math.PI);
            float x = wave * shakeAmp;
            float y = 0f;
            if (moveDir == KeyEvent.KEYCODE_DPAD_LEFT) x -= leanAmp;
            else if (moveDir == KeyEvent.KEYCODE_DPAD_RIGHT) x += leanAmp;
            else if (moveDir == KeyEvent.KEYCODE_DPAD_UP) y = -leanAmp;
            else if (moveDir == KeyEvent.KEYCODE_DPAD_DOWN) y = leanAmp;
            cells[moving].setTranslationX(x);
            cells[moving].setTranslationY(y);
            ui.postDelayed(this, 32);
        }
    };

    private void commitMove() {
        int dest = neighbor(moving, moveDir);
        int a = slotOf(moving);
        int b = slotOf(dest);
        if (a < 0 || b < 0 || a == b) {
            cancelMove();
            return;
        }
        int focus = moving;
        endMoveVisual();
        int tmp = order[a];
        order[a] = order[b];
        order[b] = tmp;
        moving = -1;
        moveDir = -1;
        saveLayout();
        layoutQuadrants();
        pictures[focus].requestFocus();
        revealChrome(focus);
    }

    private void cancelMove() {
        endMoveVisual();
        moving = -1;
        moveDir = -1;
    }

    private void endMoveVisual() {
        ui.removeCallbacks(shake);
        if (moving >= 0 && cells[moving] != null) {
            cells[moving].setTranslationX(0f);
            cells[moving].setTranslationY(0f);
        }
    }

    private boolean handleMoveKey(KeyEvent event) {
        int key = NavKeys.navCode(event.getKeyCode(), false);
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (neighbor(moving, key) != moving) moveDir = key;
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (moveDir >= 0) {
                commitMove();
            } else {
                int was = moving;
                cancelMove();
                if (was >= 0) {
                    setStatus(was, ids[was] == null ? "empty" : nameOf(ids[was]));
                    revealChrome(was);
                    pictures[was].requestFocus();
                }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            int was = moving;
            cancelMove();
            if (was >= 0) revealChrome(was);
            return true;
        }
        return false;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        leaveWhenStopped = false;
        started = true;
        if (cecCover) {
            // Already tuned — CEC stole the screen. Don't retune (that would steal again).
            cecCover = false;
            if (slotOf(focusedCell) < 0) focusedCell = order[0];
            pictures[focusedCell].requestFocus();
            revealCountBar();
            ui.postDelayed(tick, 1000);
            return;
        }
        final int gen = ++tuneGen;
        // Start the visible windows one after another so a failure points at the one that hit the limit.
        int shown = expanded >= 0 ? 1 : quadCount;
        for (int s = 0; s < shown; s++) {
            final int idx = expanded >= 0 ? expanded : order[s];
            live[idx] = true;
            final int delay = s;
            cells[idx].postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (gen != tuneGen || !live[idx]) return;
                    tuneCell(idx);
                }
            }, 600L * delay);
        }
        if (slotOf(focusedCell) < 0) focusedCell = order[0];
        pictures[focusedCell].requestFocus();
        revealCountBar();
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Home / app switch — always leave. CEC steal does not send this hint.
        leaveWhenStopped = true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int mapped = NavKeys.navCode(event.getKeyCode(), false);
        if (moving >= 0 && event.getAction() == KeyEvent.ACTION_DOWN && handleMoveKey(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mapped == KeyEvent.KEYCODE_BACK) {
                onBackPressed();
                return true;
            }
            if (expanded >= 0 && moving < 0 && handleFullDockKey(mapped)) return true;
            if (expanded < 0 && webActive < 0) {
                int cell = cellOf(getCurrentFocus());
                if (cell >= 0) focusedCell = cell;
                if (cell >= 0 || countFocused(getCurrentFocus())) revealChrome(focusedCell);
            }
            if (moving < 0 && webActive < 0 && NavKeys.isActivate(mapped)) {
                okArmed = true;
                ui.removeCallbacks(okLong);
                ui.postDelayed(okLong, android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP && fullDockSwallowUp && NavKeys.isActivate(mapped)) {
            fullDockSwallowUp = false; // the OK that pressed a dock button must not also click the picture
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && moving < 0 && webActive < 0 && NavKeys.isActivate(mapped)) {
            ui.removeCallbacks(okLong);
            if (okArmed) {
                okArmed = false;
                View focus = getCurrentFocus();
                if (focus != null) {
                    focus.performClick();
                    return true;
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- Full-screen dock

    /**
     * While one window fills the screen the wall's action strips are hidden, so a small floating dock
     * (its own window, so the video cannot cover it) offers Back to Multiview, Sound and Source. It shows
     * when a window goes full screen, then fades after the same idle time as the other chrome; Down, Up
     * or Menu bring it back, Left / Right choose, OK does it. Back also returns to the wall.
     */
    private PopupWindow fullDock;
    private LinearLayout fullDockRow;
    private final View[] fullDockBtns = new View[3];
    private int fullDockSel;
    private boolean fullDockShown;
    private boolean fullDockSwallowUp;

    private final Runnable hideFullDock = new Runnable() {
        @Override
        public void run() {
            hideFullDockNow(false);
        }
    };

    private LinearLayout dockButton(String label, int glyph, final Runnable run) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(14), dp(9), dp(18), dp(9));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new XmbView.GlyphDrawable(glyph, 0));
        b.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(XmbView.iconColor);
        t.setPadding(dp(8), 0, 0, 0);
        b.addView(t);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                touchFullDock();
                run.run();
            }
        });
        return b;
    }

    private void showFullDock() {
        if (expanded < 0 || isFinishing()) return;
        if (fullDockRow == null) {
            fullDockRow = new LinearLayout(this);
            fullDockRow.setOrientation(LinearLayout.HORIZONTAL);
            fullDockRow.setBackground(Glass.dock(dp(14)));
            fullDockRow.setPadding(dp(8), dp(6), dp(8), dp(6));
            fullDockBtns[0] = dockButton("Back to Multiview", XmbView.G_MULTI, new Runnable() {
                @Override
                public void run() {
                    collapse();
                }
            });
            fullDockBtns[1] = dockButton("Sound", XmbView.G_SOUND, new Runnable() {
                @Override
                public void run() {
                    if (expanded >= 0) listen(expanded);
                }
            });
            fullDockBtns[2] = dockButton("Source", XmbView.G_INPUT, new Runnable() {
                @Override
                public void run() {
                    if (expanded >= 0) chooseType(expanded);
                }
            });
            for (View b : fullDockBtns) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(2), 0, dp(2), 0);
                fullDockRow.addView(b, lp);
            }
        }
        fullDockSel = 0;
        renderFullDock();
        if (fullDock == null) {
            fullDock = new PopupWindow(fullDockRow, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, false);
            fullDock.setFocusable(false);
            fullDock.setTouchable(true);
            fullDock.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        fullDockRow.animate().cancel();
        if (!fullDock.isShowing()) {
            fullDockRow.setAlpha(0f);
            fullDock.showAtLocation(getWindow().getDecorView(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(40));
        }
        fullDockRow.animate().alpha(1f).setDuration(160).start();
        fullDockShown = true;
        touchFullDock();
    }

    private void touchFullDock() {
        ui.removeCallbacks(hideFullDock);
        ui.postDelayed(hideFullDock, CHROME_HIDE_MS);
    }

    private void hideFullDockNow(boolean immediate) {
        ui.removeCallbacks(hideFullDock);
        fullDockShown = false;
        if (fullDock == null || fullDockRow == null || !fullDock.isShowing()) return;
        fullDockRow.animate().cancel();
        if (immediate) {
            fullDock.dismiss();
            return;
        }
        fullDockRow.animate().alpha(0f).setDuration(450).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!fullDockShown && fullDock != null && fullDock.isShowing()) fullDock.dismiss();
            }
        }).start();
    }

    private void renderFullDock() {
        for (int i = 0; i < fullDockBtns.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(i == fullDockSel ? 0x55FFFFFF : 0x00000000);
            fullDockBtns[i].setBackground(g);
        }
    }

    /** Keys for the full-screen dock. Returns true when the key was used. */
    private boolean handleFullDockKey(int mapped) {
        if (mapped == KeyEvent.KEYCODE_MENU) {
            if (fullDockShown) hideFullDockNow(false);
            else showFullDock();
            return true;
        }
        if (fullDockShown) {
            if (mapped == KeyEvent.KEYCODE_DPAD_LEFT || mapped == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int next = fullDockSel + (mapped == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                fullDockSel = Math.max(0, Math.min(fullDockBtns.length - 1, next));
                renderFullDock();
                touchFullDock();
                return true;
            }
            if (mapped == KeyEvent.KEYCODE_DPAD_UP || mapped == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideFullDockNow(false); // up / down tuck it away
                return true;
            }
            if (NavKeys.isActivate(mapped)) {
                fullDockSwallowUp = true;
                fullDockBtns[fullDockSel].performClick();
                return true;
            }
            return false;
        }
        if (webActive < 0 && (mapped == KeyEvent.KEYCODE_DPAD_DOWN || mapped == KeyEvent.KEYCODE_DPAD_UP)) {
            showFullDock();
            return true;
        }
        return false;
    }

    /** The Source list opens under the Source button, or under the dock while a window is full screen. */
    private View sourceAnchor(int idx) {
        if (expanded >= 0 && fullDockRow != null && fullDockRow.isAttachedToWindow()) return fullDockRow;
        return sourceBtns[idx];
    }

    private boolean okArmed;
    private final Runnable okLong = new Runnable() {
        @Override
        public void run() {
            if (!okArmed) return;
            okArmed = false;
            int idx = cellOf(getCurrentFocus());
            if (idx < 0) idx = focusedCell;
            if (idx >= 0 && slotOf(idx) >= 0) beginMove(idx);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!leaveWhenStopped && hasHdmiOn()) {
            // CEC covered Multiview — keep the HDMI sessions and come straight back.
            cecCover = true;
            started = false;
            reclaimWall();
            return;
        }
        tuneGen++;
        started = false;
        cecCover = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideChrome);
        ui.removeCallbacks(reclaimWall);
        cancelMove();
        clearTip();
        dismissSourcePopup();
        hideFullDockNow(true);
        for (int i = 0; i < CELLS; i++) {
            live[i] = false;
            hdmiOn[i] = false;
            tvs[i].reset();
            if (videos[i] != null) videos[i].stopPlayback();
        }
        if (leaveWhenStopped) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (int i = 0; i < CELLS; i++) {
            if (webs[i] != null) {
                webs[i].destroy();
                webs[i] = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (moving >= 0) {
            int was = moving;
            cancelMove();
            revealChrome(was);
            return;
        }
        if (webActive >= 0) {
            leaveWeb();
            return;
        }
        View focus = getCurrentFocus();
        int idx = cellOf(focus);
        if (idx >= 0 && focus != pictures[idx]) {
            pictures[idx].requestFocus();
            return;
        }
        if (expanded >= 0) {
            collapse();
            return;
        }
        leaveWhenStopped = true;
        finish();
    }

    // ---------------------------------------------------------------- Sources

    private static boolean isWeb(String id) {
        return id != null && id.startsWith(WEB);
    }

    private static boolean isIptv(String id) {
        return id != null && id.startsWith(IPTV);
    }

    private static boolean isWidget(String id) {
        return id != null && id.startsWith(WIDGET);
    }

    private static boolean isHdmi(String id) {
        return id != null && !isWeb(id) && !isIptv(id) && !isWidget(id);
    }

    /** Built-in sites plus the pages saved in Settings (a line is an address, or Name|address). */
    private List<String[]> sites() {
        List<String[]> all = new ArrayList<>();
        for (String[] s : BUILT_IN_SITES) all.add(s);
        for (String line : store.lines("web_sites")) {
            String name;
            String url;
            int bar = line.indexOf('|');
            if (bar > 0) {
                name = line.substring(0, bar).trim();
                url = line.substring(bar + 1).trim();
            } else {
                url = line;
                name = line.replaceFirst("^(https?://)?(www\\.)?", "").replaceFirst("[/?#].*$", "");
            }
            if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) url = "https://" + url;
            all.add(new String[]{name, url});
        }
        return all;
    }

    private String siteUrl(String id) {
        if (!isWeb(id)) return null;
        String name = id.substring(WEB.length());
        for (String[] s : BUILT_IN_SITES) if (s[0].equals(name)) return s[1];
        for (String line : store.lines("web_sites")) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                if (line.substring(0, bar).trim().equals(name)) {
                    String url = line.substring(bar + 1).trim();
                    if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) url = "https://" + url;
                    return url;
                }
            } else {
                String host = line.replaceFirst("^(https?://)?(www\\.)?", "").replaceFirst("[/?#].*$", "");
                if (host.equals(name)) {
                    String url = line;
                    if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) url = "https://" + url;
                    return url;
                }
            }
        }
        return null;
    }

    private boolean known(String id) {
        if (isWeb(id)) return siteUrl(id) != null;
        if (isIptv(id) || isWidget(id)) return true;
        for (TvInputInfo info : inputs) if (info.getId().equals(id)) return true;
        return false;
    }

    private String defaultFor(int i) {
        if (i == 0) {
            for (TvInputInfo info : inputs) if (info.getId().endsWith(APPLE_TV_SUFFIX)) return info.getId();
            return inputs.isEmpty() ? null : inputs.get(0).getId();
        }
        if (i == 1) return WEB + "YouTube";
        if (i == 2) return WIDGET + "clock";
        return WIDGET + "weather";
    }

    private String nameOf(String id) {
        if (isWeb(id)) return id.substring(WEB.length());
        if (isIptv(id)) {
            int bar = id.indexOf('|');
            return bar > 0 ? id.substring(bar + 1) : "Channel";
        }
        if (isWidget(id)) {
            String w = id.substring(WIDGET.length());
            for (int i = 0; i < WIDGETS.length; i++) if (WIDGETS[i].equals(w)) return WIDGET_NAMES[i];
            return w;
        }
        for (TvInputInfo info : inputs) if (info.getId().equals(id)) return inputName(this, info);
        return id;
    }

    // ---------------------------------------------------------------- Showing a source

    private void showOnly(int idx, boolean tv, boolean panel, boolean web, boolean video) {
        if (!tv) tvs[idx].reset();
        tvs[idx].setVisibility(tv ? View.VISIBLE : View.GONE);
        panels[idx].setVisibility(panel ? View.VISIBLE : View.GONE);
        if (webs[idx] != null) webs[idx].setVisibility(web ? View.VISIBLE : View.GONE);
        if (videos[idx] != null) {
            if (!video) videos[idx].stopPlayback();
            videos[idx].setVisibility(video ? View.VISIBLE : View.GONE);
        }
    }

    private void tuneCell(int idx) {
        String id = ids[idx];
        if (id == null) {
            showOnly(idx, false, true, false, false);
            showText(idx, "Empty", "Press OK twice to choose");
            setStatus(idx, "empty");
        } else if (isWeb(id)) {
            showWeb(idx);
        } else if (isIptv(id)) {
            showIptv(idx);
        } else if (isWidget(id)) {
            showOnly(idx, false, true, false, false);
            renderWidget(idx);
            setStatus(idx, "widget");
        } else if (isHdmi(id) && !hdmiOn[idx]) {
            // Do not tune yet — opening an HDMI session makes this TV switch to that input.
            showOnly(idx, false, true, false, false);
            showText(idx, nameOf(id), "Press OK to show here");
            setStatus(idx, "ready");
        } else if (tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED) {
            showOnly(idx, true, false, false, false);
            setStatus(idx, "starting...");
            tvs[idx].tune(id, TvContract.buildChannelUriForPassthroughInput(id));
        } else { // an input with nothing playing: text only, no video session
            showOnly(idx, false, true, false, false);
            showText(idx, nameOf(id), stateText(id));
            setStatus(idx, "no signal");
        }
    }

    /** Start an HDMI port in this window, then pull Multiview back if CEC steals the screen. */
    private void startHdmi(int idx) {
        if (!isHdmi(ids[idx])) return;
        hdmiOn[idx] = true;
        leaveWhenStopped = false;
        listen(idx);
        tuneCell(idx);
    }

    private boolean hasHdmiOn() {
        for (int i = 0; i < CELLS; i++) if (hdmiOn[i]) return true;
        return false;
    }

    private final Runnable reclaimWall = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || leaveWhenStopped) return;
            if (hasWindowFocus()) {
                reclaimTries = 0;
                return;
            }
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    am.moveTaskToFront(getTaskId(), ActivityManager.MOVE_TASK_NO_USER_ACTION);
                }
            } catch (Throwable ignored) {
                // permission may be denied on this build
            }
            Intent i = new Intent(MultiviewActivity.this, MultiviewActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                startActivity(i);
            } catch (Throwable ignored) {
            }
            reclaimTries++;
            if (reclaimTries < 3) ui.postDelayed(this, 400);
        }
    };

    private void reclaimWall() {
        reclaimTries = 0;
        ui.removeCallbacks(reclaimWall);
        ui.postDelayed(reclaimWall, 350);
    }

    private void dismissSourcePopup() {
        if (sourcePopup != null) {
            try {
                if (sourcePopup.isShowing()) sourcePopup.dismiss();
            } catch (Throwable ignored) {
            }
            sourcePopup = null;
        }
    }

    private void showText(int idx, String title, String detail) {
        panels[idx].showMessage(title, detail);
    }

    private String stateText(String id) {
        switch (tim.getInputState(id)) {
            case TvInputManager.INPUT_STATE_CONNECTED:
                return "Connected";
            case TvInputManager.INPUT_STATE_CONNECTED_STANDBY:
                return "Nothing playing";
            default:
                return "No device";
        }
    }

    // ---- web

    private void showWeb(int idx) {
        showOnly(idx, false, false, true, false);
        WebView w = webs[idx];
        if (w == null) {
            w = new WebView(this);
            WebSettings ws = w.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setUserAgentString(TV_UA);
            w.setWebViewClient(new WebViewClient());
            w.setFocusable(false);
            holders[idx].addView(w, new FrameLayout.LayoutParams(-1, -1));
            webs[idx] = w;
        }
        w.setVisibility(View.VISIBLE);
        String url = siteUrl(ids[idx]);
        if (url != null && !url.equals(w.getUrl())) w.loadUrl(url);
        setStatus(idx, "web  ·  OK to use, Back to exit");
    }

    private void enterWeb(int idx) {
        WebView w = webs[idx];
        if (w == null) return;
        webActive = idx;
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);
        w.requestFocus();
        setStatus(idx, "web  ·  Back to leave");
    }

    private void leaveWeb() {
        int idx = webActive;
        WebView w = webs[idx];
        webActive = -1;
        if (w != null) w.setFocusable(false);
        pictures[idx].requestFocus();
        setStatus(idx, "web  ·  OK to use, Back to exit");
    }

    // ---- IPTV channel

    private void showIptv(final int idx) {
        showOnly(idx, false, false, false, true);
        VideoView v = videos[idx];
        if (v == null) {
            v = new VideoView(this);
            v.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    players[idx] = mp;
                    float vol = listening == idx ? 1f : 0f;
                    mp.setVolume(vol, vol);
                    setStatus(idx, listening == idx ? "live, sound on" : "live");
                    mp.start();
                }
            });
            v.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    setStatus(idx, "this channel won't play");
                    return true;
                }
            });
            v.setZOrderMediaOverlay(true);
            holders[idx].addView(v, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            videos[idx] = v;
        }
        v.setVisibility(View.VISIBLE);
        String id = ids[idx];
        int bar = id.indexOf('|');
        String url = id.substring(IPTV.length(), bar > 0 ? bar : id.length());
        setStatus(idx, "loading...");
        v.stopPlayback();
        v.setVideoURI(Uri.parse(url));
    }

    // ---- widgets

    private void renderWidget(int idx) {
        panels[idx].setClock24(store.bool("clock24", false));
        panels[idx].showWidget(ids[idx].substring(WIDGET.length()));
    }

    // ---------------------------------------------------------------- Choosing a source

    /** The source lists sit under the quadrant and stay up while you move through them. */
    private void sourceList(int idx, String title, String[] items, XmbDialog.Pick pick) {
        dismissSourcePopup();
        sourcePopup = XmbDialog.popup(this, sourceAnchor(idx), title, items, 8, pick);
    }

    /** Step 1: what kind of thing should this quadrant show? */
    private void chooseType(final int idx) {
        final String[] types = {"Input", "Web page", "Live channel", "Widget", "Empty"};
        sourceList(idx, "Quadrant " + (idx + 1) + " shows...", types, new XmbDialog.Pick() {
            @Override
            public void onPick(int which) {
                switch (which) {
                    case 0:
                        chooseInput(idx);
                        break;
                    case 1:
                        chooseWeb(idx);
                        break;
                    case 2:
                        chooseChannelGroup(idx);
                        break;
                    case 3:
                        chooseWidget(idx);
                        break;
                    default:
                        assign(idx, null);
                }
            }
        });
    }

    private void chooseInput(final int idx) {
        if (inputs.isEmpty()) {
            assignMessage(idx, "No inputs found");
            return;
        }
        final String[] names = new String[inputs.size()];
        for (int i = 0; i < names.length; i++) {
            TvInputInfo info = inputs.get(i);
            names[i] = inputName(this, info) + "  (" + stateText(info.getId()) + ")";
        }
        sourceList(idx, "Input", names, new XmbDialog.Pick() {
            @Override
            public void onPick(int which) {
                assign(idx, inputs.get(which).getId());
            }
        });
    }

    private void chooseWeb(final int idx) {
        final List<String[]> sites = sites();
        final String[] names = new String[sites.size() + 1];
        for (int i = 0; i < sites.size(); i++) names[i] = sites.get(i)[0];
        names[sites.size()] = "Add a web address...";
        sourceList(idx, "Web page", names, new XmbDialog.Pick() {
            @Override
            public void onPick(int which) {
                if (which < sites.size()) assign(idx, WEB + sites.get(which)[0]);
                else askWebAddress(idx);
            }
        });
    }

    private void askWebAddress(final int idx) {
        XmbDialog.text(this, "Web address", "", "https://example.com", new XmbDialog.Text() {
            @Override
            public void onText(String entered) {
                String text = entered.trim();
                if (text.isEmpty()) return;
                List<String> saved = store.lines("web_sites");
                if (!saved.contains(text)) saved.add(text);
                store.putLines("web_sites", saved);
                int bar = text.indexOf('|');
                String name = bar > 0 ? text.substring(0, bar).trim() : Sources.hostOf(text);
                assign(idx, WEB + name);
            }
        });
    }

    private void chooseChannelGroup(final int idx) {
        final List<IptvPlaylist.Channel> all = Shared.channels;
        if (all == null || all.isEmpty()) {
            assignMessage(idx, "No channels loaded yet. Set up Live TV in Settings first.");
            return;
        }
        final Map<String, List<IptvPlaylist.Channel>> groups = new LinkedHashMap<>();
        for (IptvPlaylist.Channel ch : all) {
            if (!groups.containsKey(ch.group)) groups.put(ch.group, new ArrayList<IptvPlaylist.Channel>());
            groups.get(ch.group).add(ch);
        }
        final List<String> names = new ArrayList<>(groups.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        final String[] labelsArr = new String[names.size()];
        for (int i = 0; i < labelsArr.length; i++) labelsArr[i] = names.get(i) + "  (" + groups.get(names.get(i)).size() + ")";
        sourceList(idx, "Channel group", labelsArr, new XmbDialog.Pick() {
            @Override
            public void onPick(int which) {
                chooseChannel(idx, groups.get(names.get(which)));
            }
        });
    }

    private void chooseChannel(final int idx, final List<IptvPlaylist.Channel> list) {
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).name;
        sourceList(idx, "Channel", names, new XmbDialog.Pick() {
            @Override
            public void onPick(int which) {
                IptvPlaylist.Channel ch = list.get(which);
                assign(idx, IPTV + ch.url + "|" + ch.name);
            }
        });
    }

    private void chooseWidget(final int idx) {
        sourceList(idx, "Widget", WIDGET_NAMES, new XmbDialog.Pick() {
            @Override
            public void onPick(int which) {
                assign(idx, WIDGET + WIDGETS[which]);
            }
        });
    }

    private void assignMessage(int idx, String message) {
        showOnly(idx, false, true, false, false);
        showText(idx, "Can't do that", message);
    }

    private void assign(int idx, String id) {
        ids[idx] = id;
        store.put("mvx" + idx, id == null ? "" : id);
        hdmiOn[idx] = false;
        if (webActive == idx) leaveWeb();
        if (isHdmi(id)) tvs[idx].reset();
        tuneCell(idx);
    }

    // ---------------------------------------------------------------- Sound, labels

    private void listen(int idx) {
        listening = idx;
        for (int i = 0; i < CELLS; i++) {
            float v = i == idx ? 1f : 0f;
            tvs[i].setStreamVolume(v);
            if (players[i] != null) {
                try {
                    players[i].setVolume(v, v);
                } catch (IllegalStateException ignored) {
                    // player released
                }
            }
        }
        for (int i = 0; i < CELLS; i++) {
            String id = ids[i];
            if (id != null && !isWeb(id) && !isWidget(id) && (isIptv(id) || tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED)) {
                setStatus(i, i == idx ? "live, sound on" : "live");
            }
            if (soundBtns[i] != null) soundBtns[i].setSelected(i == idx);
        }
    }

    private void setStatus(int idx, String status) {
        if (labels[idx] == null) return;
        labels[idx].setText(ids[idx] == null ? status : nameOf(ids[idx]) + "  ·  " + status);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String inputName(Context c, TvInputInfo info) {
        if (info.getId().endsWith(APPLE_TV_SUFFIX)) return "Apple TV";
        CharSequence custom = info.loadCustomLabel(c);
        return String.valueOf(custom != null && custom.length() > 0 ? custom : info.loadLabel(c));
    }
}
