package com.liam.homescreen;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextPaint;
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
 * A wall of one to four live IPTV channels. The home screen's background shows in the gaps.
 * Focusing a window overlays Sound / Channel / Full on the picture. Floating chips set how many
 * windows are on screen. Hold OK to rearrange; OK hears the focused channel; Channel opens the
 * tray. Choices are remembered. Back leaves an action, then full screen, then the wall.
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
    /** Focused IPTV pane unmuted after this dwell (sound pass-through). */
    private static final long LISTEN_HOVER_MS = 3000;

    private static final String WEB = "web:";
    private static final String IPTV = "iptv:";
    static final String IPTV_PREFIX = IPTV;
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
    private final FrameLayout[] stacks = new FrameLayout[CELLS];
    private final FrameLayout.LayoutParams[] holderLps = new FrameLayout.LayoutParams[CELLS];
    private final LinearLayout[] cells = new LinearLayout[CELLS];
    private final LinearLayout[] actions = new LinearLayout[CELLS];
    private final View[] pictures = new View[CELLS];
    private final View[] soundBtns = new View[CELLS];
    private final View[] sourceBtns = new View[CELLS];
    private final View[] fullBtns = new View[CELLS];
    private LinearLayout grid;
    private LinearLayout countBar;
    private FrameLayout root;
    private LinearLayout column;
    private ChannelTray tray;
    private boolean trayOpen;
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
    /** Focused cell's overlay dock is hidden (GONE) after idle — tiles stay equal. */
    private boolean actionsParked;
    private PopupWindow tipPopup;
    private PopupWindow sourcePopup;
    private TextView tipLabel;
    private int tipGen;
    private HintBar footerHints;
    private long lastInput = SystemClock.uptimeMillis();
    private final WebView[] webs = new WebView[CELLS];
    private final VideoView[] videos = new VideoView[CELLS];
    private final MediaPlayer[] players = new MediaPlayer[CELLS];
    private final String[] ids = new String[CELLS];
    private final List<TvInputInfo> inputs = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Store store;
    private TvInputManager tim;
    private int listening = -1;
    /** Cell waiting for hover-listen; -1 when none armed. */
    private int hoverListenCell = -1;
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
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Physical inputs only. The AirPlay input blanked the TV when shown in a quadrant.
                if (!info.isPassthroughInput()) continue;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED) continue;
                inputs.add(info);
            }
        }
        final Context ctx = this;
        Collections.sort(inputs, new Comparator<TvInputInfo>() {
            @Override
            public int compare(TvInputInfo a, TvInputInfo b) {
                return inputName(ctx, a).compareToIgnoreCase(inputName(ctx, b));
            }
        });

        root = new FrameLayout(this);
        root.addView(look, new FrameLayout.LayoutParams(-1, -1));

        loadLayout();
        ensureChannels(null);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);

        // Equal tiles: picture fills the cell; chrome overlays the picture (never steals height).
        for (int i = 0; i < CELLS; i++) {
            final int idx = i;
            ids[i] = store.has("mvx" + i) ? emptyToNull(store.get("mvx" + i, "")) : defaultFor(i);
            // IPTV-only wall — drop HDMI / web / widget leftovers from older builds.
            if (ids[i] != null && !isIptv(ids[i])) {
                ids[i] = null;
                store.put("mvx" + i, "");
            }

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
            // Ring draws outside the surface; inset only while focused so tiles stay equal.
            FrameLayout stack = new FrameLayout(this);
            stack.setClipChildren(false);
            FrameLayout.LayoutParams holderLp = new FrameLayout.LayoutParams(-1, -1);
            stack.addView(holder, holderLp);
            View ring = new View(this);
            ring.setBackground(focusRingDrawable(false));
            ring.setVisibility(View.INVISIBLE);
            ring.setFocusable(false);
            ring.setClickable(false);
            stack.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            focusRings[i] = ring;
            stacks[i] = stack;
            holderLps[i] = holderLp;
            holder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    long now = SystemClock.uptimeMillis();
                    boolean doubleOk = lastClickCell == idx && now - lastClickAt < DOUBLE_CLICK_MS;
                    lastClickCell = idx;
                    lastClickAt = now;
                    if (doubleOk || ids[idx] == null || !isIptv(ids[idx])) {
                        openTray(idx);
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
                    applyFocusRing(idx, has);
                    if (has) {
                        focusedCell = idx;
                        revealChrome(idx);
                        clearTip();
                        armHoverListen(idx);
                    } else if (hoverListenCell == idx) {
                        cancelHoverListen();
                    }
                }
            });

            // Soft overscan status on the picture (XMB type, not dock body copy).
            TextView status = overscanLabel();
            status.setVisibility(View.GONE);
            FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            statusLp.setMargins(dp(10), dp(8), dp(10), 0);
            stack.addView(status, statusLp);
            labels[i] = status;

            // Icon-only dock overlaid on the picture bottom — never steals tile height.
            LinearLayout menu = new LinearLayout(this);
            menu.setOrientation(LinearLayout.HORIZONTAL);
            menu.setGravity(Gravity.CENTER);
            menu.setVisibility(View.GONE);
            menu.setBackground(chromeDock());
            menu.setPadding(dp(6), dp(4), dp(6), dp(4));
            View sound = action(idx, "Sound", XmbView.G_SOUND, new Runnable() {
                @Override
                public void run() {
                    listen(idx);
                }
            });
            View source = action(idx, "Channel", XmbView.G_LIVE, new Runnable() {
                @Override
                public void run() {
                    openTray(idx);
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
            FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            menuLp.setMargins(dp(8), dp(8), dp(8), dp(8));
            stack.addView(menu, menuLp);

            cell.addView(stack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            tvs[i] = tv;
            panels[i] = panel;
            holders[i] = holder;
            pictures[i] = holder;
            cells[i] = cell;

            setStatus(i, ids[i] == null ? "empty" : "starting...");
        }
        countBar = buildCountBar();
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        root.setClipChildren(false);
        // Grid fills the screen; Windows chips float over it (no reserved footer strip).
        column.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        countLp.setMargins(0, 0, 0, dp(20));
        root.addView(countBar, countLp);
        footerHints = new HintBar(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hlp.setMargins(dp(24), dp(24), dp(24), dp(20));
        root.addView(footerHints, hlp);
        tray = new ChannelTray(this, new ChannelTray.Host() {
            @Override
            public int[] windows() {
                int[] w = new int[quadCount];
                for (int s = 0; s < quadCount; s++) w[s] = order[s];
                return w;
            }

            @Override
            public String idOf(int cell) {
                return ids[cell];
            }

            @Override
            public void load(int cell, String id) {
                assign(cell, id);
            }

            @Override
            public void target(int cell) {
                focusedCell = cell;
                pictures[cell].requestFocus();
            }

            @Override
            public void close() {
                closeTray();
            }
        });
        tray.setVisibility(View.GONE);
        root.addView(tray, new FrameLayout.LayoutParams(-1, trayHeight(), Gravity.BOTTOM));
        layoutQuadrants();
        setContentView(root);
        ui.post(hintTick);
    }

    private final Runnable hintTick = new Runnable() {
        @Override
        public void run() {
            if (footerHints != null) footerHints.invalidate();
            if (!isFinishing()) ui.postDelayed(this, 200);
        }
    };

    private float hintLevel() {
        long idle = SystemClock.uptimeMillis() - lastInput;
        return Math.max(0f, Math.min(1f, (9000 - idle) / 900f));
    }

    /** Floating Multiview remote hints (design-system: not dark pills). */
    private final class HintBar extends View {
        private final TextPaint key = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint lab = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        HintBar(Activity a) {
            super(a);
            density = a.getResources().getDisplayMetrics().density;
            key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            lab.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            Hints.prepare(key, lab, density);
            setFocusable(false);
            setClickable(false);
        }

        private String[] keys() {
            if (expanded >= 0) return new String[]{"▲▼", "Back"};
            if (moving >= 0) return new String[]{"OK", "Pad", "Back"};
            return new String[]{"OK", "Hold OK", "▲▼", "Back"};
        }

        private String[] labs() {
            if (expanded >= 0) return new String[]{"Dock", "Wall"};
            if (moving >= 0) return new String[]{"Drop", "Aim", "Cancel"};
            return new String[]{"Sound / Channel", "Move", "Dock", "Leave"};
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            Hints.prepare(key, lab, density);
            String[] k = keys();
            String[] l = labs();
            float w = Hints.measure(key, lab, k, l, density);
            setMeasuredDimension(Math.round(w), Math.round(24f * density));
        }

        @Override
        protected void onDraw(Canvas c) {
            float ha = hintLevel();
            if (ha <= 0.01f) return;
            Hints.prepare(key, lab, density);
            String[] k = keys();
            String[] l = labs();
            Hints.draw(c, key, lab, k, l, 0, 16f * density, density, ha, XmbView.iconColor);
        }
    }

    private LinearLayout buildCountBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setVisibility(View.GONE);
        bar.setBackground(chromeDock());
        bar.setAlpha(0.88f);
        // Slim chips only — no "Windows" title plate (picture-first wall).
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
            lp.setMargins(dp(3), 0, dp(3), 0);
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
        t.setTextSize(14);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(XmbView.iconColor);
        // Floating text — soft shadow only, no dark pill (design-system hints).
        int ink = XmbView.iconColor;
        int shadow = ink == 0xFF000000 ? 0x44FFFFFF : 0x99000000;
        t.setShadowLayer(2f * getResources().getDisplayMetrics().density, 0,
                getResources().getDisplayMetrics().density, shadow);
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
        t.setBackground(null);
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

    /** Soft overscan identity on the picture (name · state) with text shadow. */
    private TextView overscanLabel() {
        TextView label = new TextView(this);
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        label.setTextColor(XmbView.iconColor);
        int ink = XmbView.iconColor;
        int shadow = ink == 0xFF000000 ? 0x44FFFFFF : 0x99000000;
        float d = getResources().getDisplayMetrics().density;
        label.setShadowLayer(2.5f * d, 0, d, shadow);
        label.setAlpha(0.92f);
        label.setPadding(0, 0, 0, 0);
        return label;
    }

    /** Thin accent stroke; inset the surface only while focused so idle tiles stay equal. */
    private void applyFocusRing(int idx, boolean on) {
        if (focusRings[idx] == null || holderLps[idx] == null) return;
        focusRings[idx].setBackground(focusRingDrawable(on));
        focusRings[idx].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        int inset = on ? Math.max(2, dp(1.5f)) : 0;
        holderLps[idx].setMargins(inset, inset, inset, inset);
        stacks[idx].requestLayout();
    }

    /** Quiet Glass dock under overlay chrome / Windows chips. */
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

    /** Channel tray height — keep windows readable (~38% of screen). */
    private int trayHeight() {
        return Math.round(getResources().getDisplayMetrics().heightPixels * 0.38f);
    }

    /** Dock the channel tray under the wall; the windows shrink to the space above it. */
    private void openTray(final int cell) {
        if (tray == null) return;
        if (expanded >= 0) {
            hideFullDockNow(true);
            expanded = -1;
            layoutQuadrants();
        }
        if (moving >= 0) cancelMove();
        if (webActive >= 0) leaveWeb();
        dismissSourcePopup();
        clearTip();
        cancelHoverListen();
        trayOpen = true;
        ui.removeCallbacks(hideChrome);
        column.setPadding(0, 0, 0, trayHeight());
        applyActionVisibility(-1);
        if (countBar != null) countBar.setVisibility(View.GONE);
        footerHints.setVisibility(View.GONE);
        tray.setVisibility(View.VISIBLE);
        boolean empty = Shared.channels == null || Shared.channels.isEmpty();
        tray.setLoading(empty);
        tray.open(cell);
        if (empty) {
            ensureChannels(new Runnable() {
                @Override
                public void run() {
                    if (!trayOpen || tray == null || isFinishing()) return;
                    tray.refresh();
                }
            });
        }
    }

    private void closeTray() {
        if (!trayOpen) return;
        trayOpen = false;
        tray.setVisibility(View.GONE);
        column.setPadding(0, 0, 0, 0);
        footerHints.setVisibility(View.VISIBLE);
        if (countBar != null) countBar.setVisibility(View.VISIBLE);
        pictures[focusedCell].requestFocus();
        revealChrome(focusedCell);
    }

    private void revealChrome(int idx) {
        if (trayOpen) return;
        actionsParked = false;
        applyActionVisibility(idx);
        revealCountBar();
        ui.removeCallbacks(hideChrome);
        if (expanded < 0 && idx >= 0) ui.postDelayed(hideChrome, CHROME_HIDE_MS);
        armHoverListen(idx);
    }

    /** Unmute an IPTV pane after dwelling on it (Sound still works immediately). */
    private void armHoverListen(int idx) {
        cancelHoverListen();
        if (idx < 0 || expanded >= 0 || trayOpen) return;
        if (!isIptv(ids[idx])) return;
        if (listening == idx) return;
        hoverListenCell = idx;
        ui.postDelayed(hoverListen, LISTEN_HOVER_MS);
    }

    private void cancelHoverListen() {
        hoverListenCell = -1;
        ui.removeCallbacks(hoverListen);
    }

    private final Runnable hoverListen = new Runnable() {
        @Override
        public void run() {
            int idx = hoverListenCell;
            hoverListenCell = -1;
            if (idx < 0 || isFinishing() || expanded >= 0 || trayOpen) return;
            if (!isIptv(ids[idx])) return;
            // Still on this window (picture or its overlay dock).
            if (cellOf(getCurrentFocus()) != idx && focusedCell != idx) return;
            listen(idx);
            applyActionVisibility(focusedCell);
        }
    };

    /**
     * Overlay chrome on the focused window only. GONE when idle so every tile keeps equal size —
     * docks sit on the picture, they never steal layout height.
     */
    private void applyActionVisibility(int current) {
        for (int i = 0; i < CELLS; i++) {
            if (actions[i] == null) continue;
            boolean show = expanded < 0 && current >= 0 && i == current && !actionsParked;
            actions[i].setVisibility(show ? View.VISIBLE : View.GONE);
            if (labels[i] != null) {
                // Status on focused pane while chrome is up; listening cell keeps a quiet accent cue.
                boolean listenCue = i == listening && expanded < 0;
                boolean statusOn = show || listenCue;
                labels[i].setVisibility(statusOn ? View.VISIBLE : View.GONE);
                labels[i].setAlpha(listenCue && !show ? 0.72f : 0.92f);
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
                countBar.setVisibility(View.GONE);
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
        cancelHoverListen();
        expanded = idx;
        layoutQuadrants();
        pictures[idx].requestFocus();
        showFullDock(); // so the way back is visible as soon as the window fills the screen
        if (isIptv(ids[idx])) listen(idx);
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
        // One shared ~8dp seam between panes (gap only on trailing/leading edge), thin outer pad.
        int gap = full ? 0 : dp(8);
        grid.setPadding(full ? 0 : dp(8), full ? 0 : dp(8), full ? 0 : dp(8), full ? 0 : dp(8));
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
            LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            stackLp.setMargins(gap, 0, 0, 0);
            split.addView(stack, stackLp);
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
        for (int i = 0; i < cellsInRow.length; i++) {
            place(row, cellsInRow[i], gap, false, i > 0, false);
        }
        // Vertical seam between rows: top margin on every row after the first.
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (grid.getChildCount() > 0 && gap > 0) rowLp.topMargin = gap;
        grid.addView(row, rowLp);
    }

    private void place(LinearLayout row, int idx, int gap, boolean vertical) {
        place(row, idx, gap, vertical, row.getChildCount() > 0, vertical && row.getChildCount() > 0);
    }

    /** Half-gap margins so adjacent edges share one seam (not gap+gap). */
    private void place(LinearLayout row, int idx, int gap, boolean vertical, boolean afterSibling, boolean belowSibling) {
        cells[idx].setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = vertical
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        int left = (!vertical && afterSibling) ? gap : 0;
        int top = (vertical && belowSibling) ? gap : 0;
        lp.setMargins(left, top, 0, 0);
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
        if (trayOpen) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) lastInput = SystemClock.uptimeMillis();
            return tray.onKey(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && expanded < 0 && webActive < 0 && moving < 0
                && (mapped == KeyEvent.KEYCODE_CHANNEL_UP || mapped == KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            int cell = cellOf(getCurrentFocus());
            openTray(cell >= 0 ? cell : focusedCell);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            lastInput = SystemClock.uptimeMillis();
            if (footerHints != null) {
                footerHints.requestLayout();
                footerHints.invalidate();
            }
        }
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

    /** Icon-only full-screen dock control — same language as the wall overlay. */
    private LinearLayout dockButton(final String tip, int glyph, final Runnable run) {
        final LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setFocusable(false);
        b.setClickable(true);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new XmbView.GlyphDrawable(glyph, 0));
        b.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                touchFullDock();
                run.run();
            }
        });
        b.setTag(tip);
        return b;
    }

    private void showFullDock() {
        if (expanded < 0 || isFinishing()) return;
        if (fullDockRow == null) {
            fullDockRow = new LinearLayout(this);
            fullDockRow.setOrientation(LinearLayout.HORIZONTAL);
            fullDockRow.setGravity(Gravity.CENTER);
            fullDockRow.setBackground(chromeDock());
            fullDockRow.setPadding(dp(6), dp(4), dp(6), dp(4));
            fullDockBtns[0] = dockButton("Back", XmbView.G_MULTI, new Runnable() {
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
            fullDockBtns[2] = dockButton("Channel", XmbView.G_LIVE, new Runnable() {
                @Override
                public void run() {
                    if (expanded >= 0) openTray(expanded);
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
        renderFullDock();
        // Tip needs laid-out icon coords after the popup is on screen.
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (fullDockShown) renderFullDock();
            }
        });
        touchFullDock();
    }

    private void touchFullDock() {
        ui.removeCallbacks(hideFullDock);
        ui.postDelayed(hideFullDock, CHROME_HIDE_MS);
    }

    private void hideFullDockNow(boolean immediate) {
        ui.removeCallbacks(hideFullDock);
        fullDockShown = false;
        clearTip();
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
        // Floating tip above the selected icon (dock is not focusable — show immediately).
        if (fullDockShown && fullDockSel >= 0 && fullDockSel < fullDockBtns.length) {
            View b = fullDockBtns[fullDockSel];
            Object tip = b.getTag();
            if (tip instanceof String) showTipNow(b, (String) tip);
        } else {
            clearTip();
        }
    }

    /** Show the floating tip immediately (full-screen dock selection). */
    private void showTipNow(View button, String name) {
        tipGen++;
        dismissTip();
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
        // The list is as wide as its anchor, so use the whole dock under the window, not the small icon button.
        if (actions[idx] != null && actions[idx].getWidth() > 0) return actions[idx];
        return cells[idx];
    }

    private boolean okArmed;
    private final Runnable okLong = new Runnable() {
        @Override
        public void run() {
            if (!okArmed) return;
            okArmed = false;
            // Only rearrange when a cell (not Windows bar / dock) holds focus.
            int idx = cellOf(getCurrentFocus());
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
        cancelHoverListen();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hintTick);
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
        if (trayOpen) {
            closeTray();
            return;
        }
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

    private String defaultFor(int i) {
        List<IptvPlaylist.Channel> all = Shared.channels;
        if (all == null || i < 0 || i >= all.size()) return null;
        IptvPlaylist.Channel ch = all.get(i);
        return IPTV + ch.url + "|" + ch.name;
    }

    private String nameOf(String id) {
        if (isIptv(id)) {
            int bar = id.indexOf('|');
            return bar > 0 ? id.substring(bar + 1) : "Channel";
        }
        return id == null ? "Empty" : id;
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
        if (id != null && !isIptv(id)) {
            ids[idx] = null;
            store.put("mvx" + idx, "");
            id = null;
        }
        if (id == null) {
            showOnly(idx, false, true, false, false);
            showText(idx, "Empty", "Press OK to pick a channel");
            setStatus(idx, "empty");
        } else {
            showIptv(idx);
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
        if (tim == null) return "No device";
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

    /**
     * Pull playable channels into {@link Shared#channels} from the on-device library (and fetch
     * playlists once if the library is empty). Safe to call often; {@code after} runs on the UI thread.
     */
    private void ensureChannels(final Runnable after) {
        if (Shared.channels != null && !Shared.channels.isEmpty()) {
            if (after != null) after.run();
            return;
        }
        final Store prefs = store;
        new Thread(new Runnable() {
            @Override
            public void run() {
                ChannelDb db = ChannelDb.get(MultiviewActivity.this);
                List<IptvPlaylist.Channel> playable = playableChannels(db.loadAll(), prefs);
                if (playable.isEmpty()) {
                    List<String> urls = prefs.iptvUrls();
                    if (!urls.isEmpty()) {
                        try {
                            IptvFetch.sync(db, prefs, urls);
                        } catch (Exception e) {
                            Log.w(TAG, "playlist fetch failed", e);
                        }
                    }
                    playable = playableChannels(db.loadAll(), prefs);
                }
                final List<IptvPlaylist.Channel> ready = playable;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (ready != null) Shared.channels = ready;
                        if (after != null) after.run();
                    }
                });
            }
        }, "mv-iptv").start();
    }

    /** Same filter as the home Live row: live only, working, or unchecked when Settings allows. */
    private static List<IptvPlaylist.Channel> playableChannels(List<IptvPlaylist.Channel> all, Store prefs) {
        boolean unchecked = prefs.bool("ch_show_unchecked", true);
        List<IptvPlaylist.Channel> out = new ArrayList<>();
        for (IptvPlaylist.Channel ch : all) {
            if (!IptvPlaylist.isLiveChannel(ch)) continue;
            if (ch.status == ChannelDb.WORKING || (ch.status == ChannelDb.UNKNOWN && unchecked)) {
                out.add(ch);
            }
        }
        return out;
    }

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
                    Log.w(TAG, "q" + idx + " IPTV error what=" + what + " extra=" + extra);
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
        players[idx] = null;
        setVideoUri(v, Uri.parse(url));
        // Hover sound: if this pane already has focus, (re)arm the 3s pass-through.
        if (focusedCell == idx) armHoverListen(idx);
    }

    /** Prefer the hidden headers overload so IPTV streams get a stable User-Agent. */
    private static void setVideoUri(VideoView v, Uri uri) {
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12) HomeLauncher/1.0");
            java.lang.reflect.Method m = VideoView.class.getMethod("setVideoURI", Uri.class, java.util.Map.class);
            m.invoke(v, uri, headers);
            return;
        } catch (Throwable ignored) {
            // fall through to the public API
        }
        v.setVideoURI(uri);
    }

    // ---- widgets

    private void renderWidget(int idx) {
        panels[idx].setClock24(store.bool("clock24", false));
        panels[idx].showWidget(ids[idx].substring(WIDGET.length()));
    }

    // ---------------------------------------------------------------- Choosing a source

    private void assign(int idx, String id) {
        if (id != null && !isIptv(id)) id = null;
        ids[idx] = id;
        store.put("mvx" + idx, id == null ? "" : id);
        hdmiOn[idx] = false;
        if (webActive == idx) leaveWeb();
        tvs[idx].reset();
        if (isIptv(id)) {
            // Remember for Live preview / Player resume.
            int bar = id.indexOf('|');
            String url = id.substring(IPTV.length(), bar > 0 ? bar : id.length());
            String name = bar > 0 ? id.substring(bar + 1) : "Channel";
            store.put("last_channel_url", url);
            store.put("last_channel_name", name);
        }
        tuneCell(idx);
        if (isIptv(id) && focusedCell == idx) armHoverListen(idx);
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
            if (id != null && !isWeb(id) && !isWidget(id)
                    && (isIptv(id) || (tim != null && tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED))) {
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
