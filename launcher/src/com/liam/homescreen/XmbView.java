package com.liam.homescreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * A cross media bar in the style of RetroArch's XMB menu (itself a PS3 XMB clone): categories
 * across the bar, the selected category's items running down beneath it, previous items above the
 * bar, submenus that take over the column, value columns for settings, on-screen notices, and a
 * choice of ribbon / simple ribbon / snow / bokeh backgrounds. Metrics, zoom and alpha factors,
 * colour themes and the ribbon formula follow RetroArch's menu/drivers/xmb.c; animations are
 * time-based with easing. Everything is drawn on one canvas.
 */
final class XmbView extends View {
    static final int G_PLAY = 0, G_GRID = 1, G_INPUT = 2, G_GEAR = 3, G_PICTURE = 4,
            G_SOUND = 5, G_WIFI = 6, G_MULTI = 7, G_PIN = 8, G_SUN = 9, G_CLOUD = 10, G_RAIN = 11,
            G_SNOW = 12, G_STORM = 13, G_FOG = 14, G_CAL = 15, G_LIVE = 16, G_STAR = 17, G_CLOCK = 18,
            G_PHONE = 19, G_INFO = 20, G_BRUSH = 21,
            G_SEARCH = 22, G_PAUSE = 23, G_STOP = 24, G_REW = 25, G_FF = 26, G_MUTE = 27,
            G_UP = 28, G_DOWN = 29, G_TRASH = 30, G_PLUS = 31, G_LIST = 32, G_MOON = 33;

    interface ListBuilder {
        List<Item> build();
    }

    interface Events {
        void onMove();

        void onSelect();

        void onBack();

        void onSubmenu(boolean open);
    }

    static final class Item {
        final String title;
        final String sub;
        final Drawable icon;
        final Runnable action;
        final Runnable longAction;
        String value;
        boolean submenu;
        int toggle = -1; // -1: not a switch, 0: off, 1: on

        Item(String title, String sub, Drawable icon, Runnable action, Runnable longAction) {
            this.title = title;
            this.sub = sub;
            this.icon = icon;
            this.action = action;
            this.longAction = longAction;
        }

        Item value(String v) {
            value = v;
            return this;
        }

        Item submenu() {
            submenu = true;
            return this;
        }

        Item toggle(boolean on) {
            toggle = on ? 1 : 0;
            return this;
        }

        private final Clip titleFit = new Clip();
        private final Clip valueFit = new Clip();
        private final Clip subFit = new Clip();

        /** Ellipsize once per text and width, not on every frame of a move. */
        String fitTitle(TextPaint p, String text, float maxW) {
            return titleFit.of(p, text, maxW);
        }

        String fitValue(TextPaint p, String text, float maxW) {
            return valueFit.of(p, text, maxW);
        }

        String fitSub(TextPaint p, String text, float maxW) {
            return subFit.of(p, text, maxW);
        }
    }

    /** Remembers the last ellipsized string so a moving list does not allocate every frame. */
    private static final class Clip {
        private String from;
        private String out;
        private float width = Float.NaN;

        String of(TextPaint p, String text, float maxW) {
            if (text == null) return "";
            if (text.equals(from) && width == maxW && out != null) return out;
            from = text;
            width = maxW;
            out = maxW <= 1f ? "" : TextUtils.ellipsize(text, p, maxW, TextUtils.TruncateAt.END).toString();
            return out;
        }
    }

    static final class Cat {
        final String title;
        final int glyph;
        final List<Item> items = new ArrayList<>();
        int sel;
        final Pos selPos = new Pos();

        Cat(String title, int glyph) {
            this.title = title;
            this.glyph = glyph;
        }
    }

    private static final class Level {
        String title;
        final int glyph;
        final ListBuilder builder;
        List<Item> items;
        int sel;
        final Pos selPos = new Pos();

        Level(String title, int glyph, ListBuilder builder) {
            this.title = title;
            this.glyph = glyph;
            this.builder = builder;
            this.items = builder.build();
        }
    }

    /**
     * Follows its target with a smooth glide. A new target (the next item, the next category, a submenu)
     * keeps the current speed instead of restarting, so holding a direction does not stutter.
     */
    private static final class Pos {
        float cur, to;
        int dur = 180;
        long last;

        void jump(float v) {
            cur = to = v;
            last = 0;
        }

        void go(float target, int ms, long now) {
            to = target;
            if (ms <= 0) {
                cur = to;
                last = now;
                return;
            }
            dur = ms;
            if (last == 0) last = now;
        }

        float update(long now) {
            if (cur == to) {
                last = now;
                return cur;
            }
            float dt = last == 0 ? 16f : Math.min(32f, (float) (now - last));
            last = now;
            float tau = Math.max(32f, dur / 3f); // most of the way there in about dur
            cur += (to - cur) * (1f - (float) Math.exp(-dt / tau));
            if (Math.abs(to - cur) < 0.002f) cur = to;
            return cur;
        }
    }

    // RetroArch colour themes: four corner colours (top-left, top-right, bottom-left, bottom-right).
    static final String[] THEME_ORDER = {"electric_blue", "dark_purple", "midnight_blue", "golden", "legacy_red",
            "apple_green", "undersea", "volcanic_red", "dark", "gray_dark", "ice_cold", "gamecube_purple",
            "flaming_hot", "pikachu_yellow", "famicom_red"};
    static final String[] BG_ORDER = {"ribbon", "simple_ribbon", "snow", "bokeh", "none"};
    private static final Map<String, int[]> THEMES = new HashMap<>();

    static {
        THEMES.put("electric_blue", new int[]{rgb(1, 2, 67), rgb(1, 73, 183), rgb(1, 93, 194), rgb(3, 162, 254)});
        THEMES.put("dark_purple", new int[]{rgb(20, 13, 20), rgb(20, 13, 20), rgb(92, 44, 92), rgb(148, 90, 148)});
        THEMES.put("midnight_blue", new int[]{rgb(44, 62, 80), rgb(44, 62, 80), rgb(44, 62, 80), rgb(44, 62, 80)});
        THEMES.put("golden", new int[]{rgb(174, 123, 44), rgb(205, 174, 84), rgb(58, 43, 24), rgb(58, 43, 24)});
        THEMES.put("legacy_red", new int[]{rgb(171, 70, 59), rgb(171, 70, 59), rgb(190, 80, 69), rgb(190, 80, 69)});
        THEMES.put("apple_green", new int[]{rgb(102, 134, 58), rgb(122, 131, 52), rgb(82, 101, 35), rgb(63, 95, 30)});
        THEMES.put("undersea", new int[]{rgb(23, 18, 41), rgb(30, 72, 114), rgb(52, 88, 110), rgb(69, 125, 140)});
        THEMES.put("volcanic_red", new int[]{rgb(255, 0, 26), rgb(255, 26, 0), rgb(26, 0, 26), rgb(26, 0, 26)});
        THEMES.put("dark", new int[]{rgb(13, 13, 13), rgb(13, 13, 13), rgb(13, 13, 13), rgb(13, 13, 13)});
        THEMES.put("gray_dark", new int[]{rgb(16, 16, 16), rgb(16, 16, 16), rgb(16, 16, 16), rgb(16, 16, 16)});
        THEMES.put("ice_cold", new int[]{rgb(66, 183, 229), rgb(29, 164, 255), rgb(176, 255, 247), rgb(174, 240, 255)});
        THEMES.put("gamecube_purple", new int[]{rgb(40, 20, 91), rgb(160, 140, 211), rgb(107, 92, 177), rgb(84, 71, 132)});
        THEMES.put("flaming_hot", new int[]{rgb(231, 53, 53), rgb(242, 138, 97), rgb(236, 97, 76), rgb(255, 125, 3)});
        THEMES.put("pikachu_yellow", new int[]{rgb(63, 63, 1), rgb(174, 174, 1), rgb(191, 194, 1), rgb(254, 221, 3)});
        THEMES.put("famicom_red", new int[]{rgb(255, 191, 171), rgb(119, 49, 28), rgb(148, 10, 36), rgb(206, 126, 110)});
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static String themeLabel(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Colour used by glyph drawables (kept in step with the view's icon mode). */
    static volatile int iconColor = 0xFFFFFFFF;
    /** Soft drop shadows on glyphs / widget text (kept in step with the view's Shadows setting). */
    static volatile boolean drawShadows = true;
    /** The active theme's four corner colours, shared with widgets, dialogs and the phone page. */
    static volatile int[] currentCorners = THEMES.get("electric_blue");

    static int blend(int a, int b, float t) {
        return lerp(a, b, t);
    }

    private final List<Cat> cats = new ArrayList<>();
    private final List<Level> stack = new ArrayList<>();
    private int catIndex;
    private final Pos catPos = new Pos();
    private final Pos depth = new Pos();
    private final Pos intro = new Pos();
    private Events events;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private Bitmap background;
    private String themeName = "electric_blue";
    private int[] corners = THEMES.get("electric_blue");

    // settings applied from the launcher
    private String bgStyle = "ribbon";
    private float menuScale = 1.2f;
    private boolean shadows = true;
    private String iconMode = "auto";
    private int animMs = 180;
    private boolean clock24;
    private int textColor = 0xFFFFFFFF;
    private boolean frosted = true;
    private boolean hints = true;
    private boolean backdropOnly;
    private float rightInset;
    private float animT;
    private long lastInput = SystemClock.uptimeMillis();
    private final Pos press = new Pos();
    private RadialGradient vignetteShader;
    private final Paint silPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** Soft shadow/highlight bitmaps keyed by drawable identity + size bucket (LRU). */
    private static final int SIL_CACHE_MAX = 48;
    private final Map<Long, Silhouette> silCache = new LinkedHashMap<Long, Silhouette>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Silhouette> eldest) {
            if (size() <= SIL_CACHE_MAX) return false;
            Silhouette s = eldest.getValue();
            if (s != null) s.recycle();
            return true;
        }
    };
    // Status header: soft theme glass over the top of the scene (API 31+ blur)
    private RenderNode blurNode;
    private float frostH;
    private final Paint maskPaint = new Paint();
    private final Paint tintPaint = new Paint();

    private SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());
    private long clockStamp;
    private String timeText = "";
    private String dateText = "";
    private int wGlyph = -1;
    private String wTemp = "";
    private String wDesc = "";

    private String notice;
    private String badge;
    private long noticeAt;

    // ribbon mesh and particles
    private static final int RIB_ROWS = 30;
    private static final int RIB_COLS = 56;
    private final float[][] ribY = new float[RIB_ROWS + 1][RIB_COLS + 1];
    private final float[] ribVerts = new float[(RIB_ROWS + 1) * (RIB_COLS + 1) * 2];
    private final int[] ribColors = new int[(RIB_ROWS + 1) * (RIB_COLS + 1)];
    private final short[] ribIndices = new short[RIB_ROWS * RIB_COLS * 6];
    private final Paint ribPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // parts of the ribbon formula that never change over time, worked out once
    private static final int RIB_N = (RIB_ROWS + 1) * (RIB_COLS + 1);
    private final float[] tAc = new float[RIB_N];   // cos(z + x), sin(z + x)
    private final float[] tAs = new float[RIB_N];
    private final float[] t1c = new float[RIB_N];   // cos / sin of (2.1 x + z)
    private final float[] t1s = new float[RIB_N];
    private final float[] t2c = new float[RIB_N];   // cos / sin of (3.3 z + 1.3 x)
    private final float[] t2s = new float[RIB_N];
    private final float[] t3c = new float[RIB_N];   // cos / sin of (1.1 x + 0.7 z)
    private final float[] t3s = new float[RIB_N];
    private final float[] cos4z = new float[RIB_ROWS + 1];
    private final float[] cos2x = new float[RIB_COLS + 1];
    private final float[] sin2x = new float[RIB_COLS + 1];
    private final float[] ribX0 = new float[RIB_N];
    // background time: advances at the chosen speed, so changing speed never makes the motion jump
    private float bgSpeed = 0.45f;
    private float bgPhase;
    private long lastFrame = SystemClock.uptimeMillis();
    private final float[] seedX = new float[90];
    private final float[] seedY = new float[90];
    private final float[] seedS = new float[90];
    private final long start = SystemClock.uptimeMillis();

    // layout (RetroArch PS3 layout, scaled): set in onSizeChanged
    private float scale, iconSize, spacingH, rowH, barY, anchorX;

    XmbView(Context c) {
        super(c);
        Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        if (thin == null) thin = Typeface.create("sans-serif-light", Typeface.NORMAL);
        titlePaint.setTypeface(medium);
        subPaint.setTypeface(sans);
        valuePaint.setTypeface(medium);
        labelPaint.setTypeface(medium);
        timePaint.setTypeface(thin);
        smallPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setTextAlign(Paint.Align.RIGHT);
        smallPaint.setTextAlign(Paint.Align.RIGHT);
        for (int r = 0; r <= RIB_ROWS; r++) {
            float vz = -1f + 2f * r / RIB_ROWS;
            cos4z[r] = (float) Math.cos(vz * 4);
            for (int k = 0; k <= RIB_COLS; k++) {
                float vx = -1f + 2f * k / RIB_COLS;
                int i = r * (RIB_COLS + 1) + k;
                tAc[i] = (float) Math.cos(vz + vx);
                tAs[i] = (float) Math.sin(vz + vx);
                t1c[i] = (float) Math.cos(vx * 2.1f + vz);
                t1s[i] = (float) Math.sin(vx * 2.1f + vz);
                t2c[i] = (float) Math.cos(vz * 3.3f + vx * 1.3f);
                t2s[i] = (float) Math.sin(vz * 3.3f + vx * 1.3f);
                t3c[i] = (float) Math.cos(vx * 1.1f + vz * 0.7f);
                t3s[i] = (float) Math.sin(vx * 1.1f + vz * 0.7f);
                cos2x[k] = (float) Math.cos(2f * vx);
                sin2x[k] = (float) Math.sin(2f * vx);
                ribX0[i] = (vx + 1f) * 0.5f;
            }
        }
        int idx = 0;
        for (int r = 0; r < RIB_ROWS; r++) {
            for (int k = 0; k < RIB_COLS; k++) {
                int i0 = r * (RIB_COLS + 1) + k;
                int i1 = i0 + 1;
                int i2 = i0 + RIB_COLS + 1;
                int i3 = i2 + 1;
                ribIndices[idx++] = (short) i0;
                ribIndices[idx++] = (short) i1;
                ribIndices[idx++] = (short) i2;
                ribIndices[idx++] = (short) i1;
                ribIndices[idx++] = (short) i3;
                ribIndices[idx++] = (short) i2;
            }
        }
        Random r = new Random(7);
        for (int i = 0; i < seedX.length; i++) {
            seedX[i] = r.nextFloat();
            seedY[i] = r.nextFloat();
            seedS[i] = 0.4f + r.nextFloat();
        }
        press.jump(1);
        catPos.jump(0);
        depth.jump(0);
        intro.jump(0);
        intro.go(1, 700, SystemClock.uptimeMillis());
        setFocusable(false);
        rebuildBackground();
    }

    // ------------------------------------------------------------------ appearance settings

    void setEvents(Events e) {
        events = e;
    }

    void setTheme(String name) {
        String key = name == null ? "electric_blue" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!THEMES.containsKey(key)) key = "electric_blue";
        if (key.equals(themeName) && background != null) return;
        themeName = key;
        corners = THEMES.get(key);
        currentCorners = corners;
        rebuildBackground();
        applyIconMode();
        buildFrost(getWidth(), getHeight());
    }

    void setBackgroundStyle(String s) {
        bgStyle = s;
    }

    /** Draw only the animated background, with no menu, clock, or button hints. */
    void setBackdropOnly(boolean on) {
        backdropOnly = on;
        setFocusable(false);
    }

    /** How fast the background moves: 1.0 is RetroArch's own pace; the default is gentler. */
    void setBackgroundSpeed(float speed) {
        bgSpeed = speed;
    }

    void setScaleFactor(float f) {
        menuScale = f;
        layout();
    }

    void setShadows(boolean on) {
        shadows = on;
        drawShadows = on;
    }

    /** "white", "black", or "auto" (black on light themes). */
    void setIconMode(String m) {
        iconMode = m;
        applyIconMode();
    }

    void setAnimMs(int ms) {
        animMs = ms;
    }

    /** Keep this many pixels at the right edge free of list text. */
    void setRightInset(float px) {
        rightInset = px;
    }

    void setHints(boolean on) {
        hints = on;
    }

    void setFrosted(boolean on) {
        frosted = on;
        buildFrost(getWidth(), getHeight());
        invalidate();
    }

    void setClock24(boolean on) {
        clock24 = on;
        timeFmt = new SimpleDateFormat(on ? "HH:mm" : "h:mm a", Locale.getDefault());
        clockStamp = 0;
    }

    private void applyIconMode() {
        silCache.clear();
        boolean dark;
        if (iconMode.equals("black")) dark = true;
        else if (iconMode.equals("white")) dark = false;
        else {
            float lum = 0;
            for (int c : corners) lum += (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
            dark = lum / 4f > 0.6f;
        }
        textColor = dark ? 0xFF000000 : 0xFFFFFFFF;
        iconColor = textColor;
        buildFrost(getWidth(), getHeight());
    }

    private void rebuildBackground() {
        int bw = 64;
        int bh = 36;
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < bh; y++) {
            float ty = y / (bh - 1f);
            for (int x = 0; x < bw; x++) {
                float tx = x / (bw - 1f);
                b.setPixel(x, y, lerp(lerp(corners[0], corners[1], tx), lerp(corners[2], corners[3], tx), ty));
            }
        }
        background = b;
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ data & navigation

    void setCategories(List<Cat> fresh) {
        Map<String, Integer> sels = new HashMap<>();
        Map<String, Float> place = new HashMap<>();
        String current = catIndex < cats.size() ? cats.get(catIndex).title : null;
        float visualCat = catPos.cur;
        for (Cat old : cats) {
            sels.put(old.title, old.sel);
            place.put(old.title, old.selPos.cur);
        }
        cats.clear();
        cats.addAll(fresh);
        long now = SystemClock.uptimeMillis();
        for (Cat c : cats) {
            Integer s = sels.get(c.title);
            c.sel = s == null ? 0 : Math.max(0, Math.min(s, c.items.size() - 1));
            Float at = place.get(c.title);
            if (at == null) c.selPos.jump(c.sel);
            else {
                c.selPos.cur = at;
                c.selPos.go(c.sel, animMs, now);
            }
        }
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).title.equals(current)) catIndex = i;
        catIndex = Math.max(0, Math.min(catIndex, cats.size() - 1));
        if (cats.isEmpty()) catPos.jump(0);
        else {
            catPos.cur = visualCat;
            catPos.go(catIndex, animMs, now);
        }
        invalidate();
    }

    /** Open a submenu in place of the category's items. The builder is re-run by refreshLevels(). */
    void pushList(String title, int glyph, ListBuilder builder) {
        Level l = new Level(title, glyph, builder);
        l.selPos.jump(0);
        stack.add(l);
        depth.go(1, Math.max(animMs, 0) + 60, SystemClock.uptimeMillis());
        if (events != null && stack.size() == 1) events.onSubmenu(true);
    }

    /** Close every open submenu and return to the category list. */
    void closeAll() {
        if (stack.isEmpty()) return;
        stack.clear();
        depth.go(0, Math.max(animMs, 0) + 60, SystemClock.uptimeMillis());
        if (events != null) events.onSubmenu(false);
    }

    /** Re-run the open submenu's builder (e.g. after a setting changed), keeping the selection. */
    void refreshLevels() {
        long now = SystemClock.uptimeMillis();
        for (Level l : stack) {
            l.items = l.builder.build();
            int n = l.items.isEmpty() ? 0 : Math.max(0, Math.min(l.sel, l.items.size() - 1));
            if (n != l.sel) l.selPos.go(n, animMs, now);
            l.sel = n;
        }
        invalidate();
    }

    /** Rename the topmost open submenu (used while typing a Search query). */
    void setTopTitle(String title) {
        if (stack.isEmpty() || title == null) return;
        stack.get(stack.size() - 1).title = title;
        invalidate();
    }

    /** Title of the top submenu, or null. */
    String topTitle() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1).title;
    }

    boolean inSubmenu() {
        return !stack.isEmpty();
    }

    /** Back: close the top submenu. Returns false when already at the top level. */
    boolean back() {
        touch();
        if (stack.isEmpty()) return false;
        stack.remove(stack.size() - 1);
        if (stack.isEmpty()) {
            depth.go(0, Math.max(animMs, 0) + 60, SystemClock.uptimeMillis());
            if (events != null) events.onSubmenu(false);
        }
        if (events != null) events.onBack();
        return true;
    }

    /** A small status line under the date (e.g. while Phone setup is on). Pass null to clear it. */
    void setBadge(String text) {
        badge = text;
    }

    /** A short message at the bottom of the screen, like RetroArch's on-screen notifications. */
    void notice(String message) {
        notice = message;
        noticeAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Shown beside the clock. Pass a negative glyph to hide it. */
    void setWeather(int glyph, String temp, String desc) {
        wGlyph = glyph;
        wTemp = temp == null ? "" : temp;
        wDesc = desc == null ? "" : desc;
    }

    void moveCat(int dir) {
        touch();
        if (cats.isEmpty() || !stack.isEmpty()) return;
        int n = Math.max(0, Math.min(cats.size() - 1, catIndex + dir));
        if (n == catIndex) return;
        catIndex = n;
        catPos.go(catIndex, animMs, SystemClock.uptimeMillis());
        if (events != null) events.onMove();
    }

    void moveItem(int dir) {
        touch();
        long now = SystemClock.uptimeMillis();
        if (!stack.isEmpty()) {
            Level l = stack.get(stack.size() - 1);
            int n = Math.max(0, Math.min(l.items.size() - 1, l.sel + dir));
            if (n == l.sel) return;
            l.sel = n;
            l.selPos.go(n, (int) (animMs * 0.85f), now);
        } else {
            if (cats.isEmpty()) return;
            Cat c = cats.get(catIndex);
            int n = Math.max(0, Math.min(c.items.size() - 1, c.sel + dir));
            if (n == c.sel) return;
            c.sel = n;
            c.selPos.go(n, (int) (animMs * 0.85f), now);
        }
        if (events != null) events.onMove();
    }

    void goHome() {
        closeAll();
        if (cats.isEmpty()) return;
        catIndex = 0;
        catPos.go(0, animMs, SystemClock.uptimeMillis());
        for (Cat c : cats) {
            c.sel = 0;
            c.selPos.go(0, animMs, SystemClock.uptimeMillis());
        }
    }

    private void touch() {
        lastInput = SystemClock.uptimeMillis();
        invalidate();
    }

    private void pulse() {
        press.jump(0);
        press.go(1, 220, SystemClock.uptimeMillis());
    }

    void activate() {
        touch();
        Item it = current();
        if (it != null && it.action != null) {
            pulse();
            if (events != null) events.onSelect();
            it.action.run();
        }
    }

    void longActivate() {
        touch();
        Item it = current();
        if (it != null && it.longAction != null) {
            pulse();
            if (events != null) events.onSelect();
            it.longAction.run();
        }
    }

    private Item current() {
        if (!stack.isEmpty()) {
            Level l = stack.get(stack.size() - 1);
            return l.items.isEmpty() ? null : l.items.get(l.sel);
        }
        if (cats.isEmpty()) return null;
        Cat c = cats.get(catIndex);
        return c.items.isEmpty() ? null : c.items.get(c.sel);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layout();
    }

    private void layout() {
        float h = getHeight();
        if (h <= 0) return;
        // RetroArch's PS3 layout is defined for a 1080p screen at menu scale 1.0.
        scale = h / 1080f * menuScale;
        iconSize = 128 * scale;
        spacingH = 192 * scale;
        rowH = 64 * scale;
        barY = 272 * scale;
        anchorX = 336 * scale;
        // Shared type scale (1080p units × scale) — status / menu / chrome stay in one family.
        titlePaint.setTextSize(42 * scale);   // list titles, greeting
        subPaint.setTextSize(28 * scale);     // list subtitles
        valuePaint.setTextSize(36 * scale);   // setting values
        labelPaint.setTextSize(32 * scale);   // category labels
        timePaint.setTextSize(64 * scale);    // clock only
        smallPaint.setTextSize(22 * scale);   // date, quote, meta, weather detail
        buildFrost(getWidth(), h);
        float w = getWidth();
        vignetteShader = new RadialGradient(w / 2f, h * 0.5f, Math.max(w, h) * 0.85f,
                new int[]{0x00000000, 0x00000000, 0x2A000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
    }

    /**
     * Quiet dark depth above the bar so status text stays readable — denser at the top so
     * list wordmarks can't read through the greeting / clock.
     */
    private void buildFrost(float w, float h) {
        frostH = Math.max(barY * 0.82f, 130f * scale);
        if (frostH < 40 || w <= 0) return;
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        maskPaint.setShader(new LinearGradient(0, 0, 0, frostH,
                new int[]{0xFF000000, 0xCC000000, 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        int base = blend(corners[0], corners[1], 0.4f);
        boolean lightUi = iconColor == 0xFF000000;
        // Stronger top band — status text sits here; list icons must not show through.
        int topVeil = lightUi
                ? ((blend(base, 0xFFFFFFFF, 0.35f) & 0x00FFFFFF) | 0x66000000)
                : ((blend(base, 0xFF000000, 0.62f) & 0x00FFFFFF) | 0x99000000);
        int midVeil = lightUi
                ? ((blend(base, 0xFFFFFFFF, 0.2f) & 0x00FFFFFF) | 0x22000000)
                : ((blend(base, 0xFF000000, 0.35f) & 0x00FFFFFF) | 0x44000000);
        tintPaint.setShader(new LinearGradient(0, 0, 0, frostH,
                new int[]{topVeil, midVeil, 0x00000000},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        if (Build.VERSION.SDK_INT >= 31) {
            if (blurNode == null) blurNode = new RenderNode("frost");
            float r = Math.max(8f, 28f * scale);
            blurNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        long now = SystemClock.uptimeMillis();
        float t = (now - start) / 1000f;

        animT = t;
        float dt = Math.min((now - lastFrame) / 1000f, 0.1f);
        lastFrame = now;
        bgPhase += dt * bgSpeed;
        press.update(now);
        float cp = catPos.update(now);
        float dp = depth.update(now);
        float in = intro.update(now);
        for (Cat cat : cats) cat.selPos.update(now);
        for (Level l : stack) l.selPos.update(now);

        if (bgStyle.equals("ribbon")) updateRibbon(w, h, bgPhase);
        drawBackground(c, w, h, cp);
        if (backdropOnly) {
            scheduleNextFrame(now, false);
            return;
        }
        drawScene(c, w, h, t, cp, dp, in);
        // Glass sits over the scene so list items dissolve under the status header.
        boolean glass = frosted && frostH > 40 && Build.VERSION.SDK_INT >= 31 && blurNode != null
                && c.isHardwareAccelerated();
        if (glass) compositeFrost(c, w, h, cp);
        else if (frosted && frostH > 40) c.drawRect(0, 0, w, frostH, tintPaint);
        // Opaque status scrub — kills any leftover wordmarks under greeting / clock.
        drawStatusScrub(c, w, in);
        drawClock(c, w, h, in);
        drawGreeting(c, w, h, in);
        drawNotice(c, w, h, now);
        scheduleNextFrame(now, true);
    }

    /** Keep animating only while something is moving; otherwise tick for the clock. */
    private void scheduleNextFrame(long now, boolean menu) {
        if (needsContinuousFrames(menu)) {
            postInvalidateOnAnimation();
            return;
        }
        long toMinute = 60000L - (System.currentTimeMillis() % 60000L);
        postInvalidateDelayed(Math.min(30000L, Math.max(1000L, toMinute)));
    }

    private boolean needsContinuousFrames(boolean menu) {
        if (!bgStyle.equals("none") && bgSpeed > 0.01f) return true;
        if (backdropOnly) return false;
        if (!menu) return false;
        if (press.cur != press.to || catPos.cur != catPos.to || depth.cur != depth.to || intro.cur != intro.to) {
            return true;
        }
        for (Cat cat : cats) if (cat.selPos.cur != cat.selPos.to) return true;
        for (Level l : stack) if (l.selPos.cur != l.selPos.to) return true;
        if (notice != null) return true;
        if (hintLevel() > 0.01f && hintLevel() < 0.99f) return true;
        // Selection glow breathes — only while a pulse or near-idle fade is active.
        if (press.cur < 1f) return true;
        return hasLoadingDots();
    }

    private boolean hasLoadingDots() {
        List<Item> items = currentItems();
        if (items == null) return false;
        for (Item it : items) if (it.title != null && it.title.endsWith("...")) return true;
        return false;
    }

    private List<Item> currentItems() {
        if (!stack.isEmpty()) return stack.get(stack.size() - 1).items;
        if (catIndex >= 0 && catIndex < cats.size()) return cats.get(catIndex).items;
        return null;
    }

    /** Theme gradient, drifting backdrop and vignette. */
    private void drawBackground(Canvas c, float w, float h, float cp) {
        rect.set(0, 0, w, h);
        if (background != null) c.drawBitmap(background, null, rect, bgPaint);
        c.save();
        float drift = -(cp - (cats.size() - 1) / 2f) * 20 * scale;
        c.translate(drift, 0);
        c.scale(1.12f, 1f, w / 2f, h / 2f);
        drawBackdrop(c, w, h, bgPhase);
        c.restore();
        if (vignetteShader != null) {
            paint.reset();
            paint.setShader(vignetteShader);
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    /**
     * Theme glass over the status zone: blur the live background, veil with theme colour, dissolve
     * into the menu. Clock / weather draw afterwards so they stay sharp.
     */
    private void compositeFrost(Canvas c, float w, float h, float cp) {
        int iw = Math.max(1, (int) w);
        int fh = Math.max(1, (int) frostH);
        blurNode.setPosition(0, 0, iw, fh);
        Canvas bc = blurNode.beginRecording(iw, fh);
        bc.clipRect(0, 0, iw, fh);
        drawBackground(bc, w, h, cp);
        blurNode.endRecording();
        int save = c.saveLayer(0, 0, w, frostH, null);
        c.drawRenderNode(blurNode);
        c.drawRect(0, 0, w, frostH, maskPaint);
        c.restoreToCount(save);
        c.drawRect(0, 0, w, frostH, tintPaint);
    }

    /** Categories, the open submenu and the button hints. */
    private void drawScene(Canvas c, float w, float h, float t, float cp, float dp, float in) {
        float slide = (1f - in) * 40 * scale; // start-up: the menu slides up into place
        c.save();
        c.translate(0, slide);
        // category items fade out as a submenu takes over, and back in again
        if (cats.size() > 0) {
            for (int i = 0; i < cats.size(); i++) {
                Cat cat = cats.get(i);
                float dx = i - cp;
                if (Math.abs(dx) >= 1f) continue;
                drawColumn(c, w, h, cat.items, cat.selPos.cur, anchorX + dx * spacingH,
                        (1f - Math.abs(dx)) * (1f - dp) * in, true);
            }
        }
        if (!stack.isEmpty() || dp > 0.01f) {
            Level l = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            if (l != null) drawColumn(c, w, h, l.items, l.selPos.cur, anchorX, dp * in, true);
        }
        drawBar(c, w, h, cp, dp, in);
        c.restore();
        drawFooter(c, w, h, dp, in);
    }

    // ---- background

    private void drawBackdrop(Canvas c, float w, float h, float t) {
        if (bgStyle.equals("ribbon")) drawRibbon(c);
        else if (bgStyle.equals("simple_ribbon")) drawSimpleRibbon(c, w, h, t);
        else if (bgStyle.equals("snow")) drawSnow(c, w, h, t);
        else if (bgStyle.equals("bokeh")) drawBokeh(c, w, h, t);
    }

    /**
     * RetroArch's ribbon: a mesh whose vertices are displaced by
     * y = cos(z*4)*cos(z + t/10 + x)/8 - noise/15 - cos(2x - t/2)/5 + 0.3, lit so stretched areas stay
     * dark and compressed (steep) areas catch light. The noise is approximated with a few slow sines,
     * and the lighting is a smooth per-vertex colour blended by the GPU, so it flows without banding.
     */
    private void updateRibbon(float w, float h, float t) {
        // angle-sum identities turn the per-vertex sin/cos into a few multiplies; only these need trig each frame
        float ct = (float) Math.cos(t / 10f);
        float st = (float) Math.sin(t / 10f);
        float c1 = (float) Math.cos(0.22f * t);
        float s1 = (float) Math.sin(0.22f * t);
        float c2 = (float) Math.cos(0.17f * t);
        float s2 = (float) Math.sin(0.17f * t);
        float c3 = (float) Math.cos(0.11f * t);
        float s3 = (float) Math.sin(0.11f * t);
        float ch = (float) Math.cos(t / 2f);
        float sh = (float) Math.sin(t / 2f);
        for (int r = 0; r <= RIB_ROWS; r++) {
            float c4 = cos4z[r];
            for (int k = 0; k <= RIB_COLS; k++) {
                int i = r * (RIB_COLS + 1) + k;
                float base = c4 * (tAc[i] * ct - tAs[i] * st) * 0.125f;                 // cos(z*4) * cos(z + t/10 + x) / 8
                float n = ((t1s[i] * c1 - t1c[i] * s1)                          // sin(2.1x + z - 0.22t)
                        + (t2s[i] * c2 + t2c[i] * s2)                                    // sin(3.3z + 1.3x + 0.17t)
                        + 0.5f * (t3s[i] * c3 - t3c[i] * s3)) * 0.4f;                    // sin(1.1x + 0.7z - 0.11t)
                float wave = cos2x[k] * ch + sin2x[k] * sh;                              // cos(2x - t/2)
                float vy = base - n * (1f / 15f) - wave * 0.2f + 0.3f;
                ribVerts[i * 2] = ribX0[i] * w;
                float y = h * (0.5f + vy * 0.95f);
                ribVerts[i * 2 + 1] = y;
                ribY[r][k] = y;
            }
        }
        float avgGap = h * 0.5f / RIB_ROWS;
        for (int r = 0; r <= RIB_ROWS; r++) {
            for (int k = 0; k <= RIB_COLS; k++) {
                float up = ribY[Math.max(r - 1, 0)][k];
                float down = ribY[Math.min(r + 1, RIB_ROWS)][k];
                float gap = Math.abs(down - up) * 0.5f;
                float lit = Math.max(0f, Math.min(1f, 1.35f - gap / avgGap));
                int alpha = (int) (74f * lit * lit + 12f * lit); // soft where flat, brighter where the ribbon folds
                ribColors[r * (RIB_COLS + 1) + k] = (alpha << 24) | 0x00FFFFFF;
            }
        }
    }

    private void drawRibbon(Canvas c) {
        ribPaint.reset();
        ribPaint.setAntiAlias(true);
        c.drawVertices(Canvas.VertexMode.TRIANGLES, ribVerts.length, ribVerts, 0, null, 0, ribColors, 0,
                ribIndices, 0, ribIndices.length, ribPaint);
    }

    /** RetroArch's "simple ribbon": a single soft wave sheet. */
    private void drawSimpleRibbon(Canvas c, float w, float h, float t) {
        paint.reset();
        paint.setAntiAlias(true);
        for (int layer = 0; layer < 3; layer++) {
            path.reset();
            int steps = 48;
            for (int i = 0; i <= steps; i++) {
                float x = w * i / steps;
                float u = i / (float) steps * 2f - 1f;
                float y = h * (0.72f - 0.07f * layer) + h * 0.07f * (float) Math.cos((u + t / (6f + layer)) * 3f + layer);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(26 + layer * 8);
            c.drawPath(path, paint);
        }
    }

    private void drawSnow(Canvas c, float w, float h, float t) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(0xFFFFFFFF);
        for (int i = 0; i < seedX.length; i++) {
            float speed = 18f + 36f * seedS[i];
            float x = (seedX[i] * w + (float) Math.sin(t * 0.6f + i) * 24 * scale) % w;
            float y = (seedY[i] * h + t * speed * scale) % h;
            paint.setAlpha((int) (70 + 110 * seedS[i] / 1.4f));
            c.drawCircle(x < 0 ? x + w : x, y, (1.5f + 3.5f * seedS[i]) * scale, paint);
        }
    }

    private void drawBokeh(Canvas c, float w, float h, float t) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(0xFFFFFFFF);
        for (int i = 0; i < 26; i++) {
            float x = ((seedX[i] + t * 0.004f * (0.5f + seedS[i])) % 1f) * w;
            float y = (seedY[i] * h + (float) Math.sin(t * 0.3f + i) * 30 * scale);
            float r = (28f + 70f * seedS[i]) * scale;
            paint.setAlpha((int) (14 + 18 * seedS[i]));
            c.drawCircle(x, y, r, paint);
        }
    }

    // ---- clock, weather, greeting, notice

    private static final String[] QUOTES = {
            "The only thing we have to fear is fear itself. — Franklin D. Roosevelt",
            "I think, therefore I am. — René Descartes",
            "Be the change you wish to see in the world. — Mahatma Gandhi",
            "Imagination is more important than knowledge. — Albert Einstein",
            "To be, or not to be, that is the question. — William Shakespeare",
            "I have a dream. — Martin Luther King Jr.",
            "The unexamined life is not worth living. — Socrates",
            "Knowledge is power. — Francis Bacon",
            "Carpe diem. Seize the day. — Horace",
            "That which does not kill us makes us stronger. — Friedrich Nietzsche",
            "In the middle of difficulty lies opportunity. — Albert Einstein",
            "The journey of a thousand miles begins with one step. — Lao Tzu",
            "Fortune favors the bold. — Virgil",
            "No one can make you feel inferior without your consent. — Eleanor Roosevelt",
            "It always seems impossible until it's done. — Nelson Mandela",
            "We are what we repeatedly do. Excellence, then, is not an act but a habit. — Aristotle",
            "The greatest glory in living lies not in never falling, but in rising every time we fall. — Nelson Mandela",
            "Do what you can, with what you have, where you are. — Theodore Roosevelt",
            "Stay hungry. Stay foolish. — Steve Jobs",
            "Not all those who wander are lost. — J.R.R. Tolkien",
            "The only true wisdom is in knowing you know nothing. — Socrates",
            "Life is what happens when you're busy making other plans. — John Lennon",
            "Two roads diverged in a wood, and I— I took the one less traveled by. — Robert Frost",
            "If you want to go fast, go alone. If you want to go far, go together. — African proverb",
            "What we know is a drop, what we don't know is an ocean. — Isaac Newton",
            "The best way to predict the future is to invent it. — Alan Kay",
            "Speak softly and carry a big stick. — Theodore Roosevelt",
            "An eye for an eye only ends up making the whole world blind. — Mahatma Gandhi",
            "I wandered lonely as a cloud. — William Wordsworth",
            "Well done is better than well said. — Benjamin Franklin",
            "The pen is mightier than the sword. — Edward Bulwer-Lytton",
            "All that glitters is not gold. — William Shakespeare",
            "Happiness depends upon ourselves. — Aristotle",
            "Dare to know. — Immanuel Kant",
            "One small step for man, one giant leap for mankind. — Neil Armstrong",
            "Never, never, never give up. — Winston Churchill",
            "Simplicity is the ultimate sophistication. — Leonardo da Vinci",
            "The only impossible journey is the one you never begin. — Tony Robbins",
            "Time is the wisest counselor of all. — Pericles",
            "A room without books is like a body without a soul. — Cicero",
            "Injustice anywhere is a threat to justice everywhere. — Martin Luther King Jr.",
            "I am the master of my fate: I am the captain of my soul. — William Ernest Henley",
            "The future belongs to those who believe in the beauty of their dreams. — Eleanor Roosevelt",
            "Whatever you are, be a good one. — Abraham Lincoln",
            "Education is the most powerful weapon which you can use to change the world. — Nelson Mandela",
            "Ask not what your country can do for you—ask what you can do for your country. — John F. Kennedy",
            "The mind is everything. What you think you become. — Buddha",
            "Art is never finished, only abandoned. — Leonardo da Vinci",
            "There is nothing permanent except change. — Heraclitus",
            "Give me liberty, or give me death! — Patrick Henry",
            "I disapprove of what you say, but I will defend to the death your right to say it. — Evelyn Beatrice Hall",
            "The only way to do great work is to love what you do. — Steve Jobs",
            "Doubt is not a pleasant condition, but certainty is absurd. — Voltaire",
            "You must be the change you wish to see. — Mahatma Gandhi",
            "History will be kind to me for I intend to write it. — Winston Churchill",
            "The cosmos is within us. We are made of star-stuff. — Carl Sagan",
            "Courage is not the absence of fear, but the triumph over it. — Nelson Mandela",
            "To thine own self be true. — William Shakespeare",
            "Act as if what you do makes a difference. It does. — William James",
            "The secret of getting ahead is getting started. — Mark Twain",
            "What is wanted is not the will to believe, but the will to find out. — Bertrand Russell",
            "I never lose. I either win or learn. — Nelson Mandela",
            "Float like a butterfly, sting like a bee. — Muhammad Ali",
            "The important thing is not to stop questioning. — Albert Einstein",
            "Hope is a good breakfast, but it is a bad supper. — Francis Bacon"
    };

    private static String greetingForHour(int hour) {
        if (hour >= 5 && hour < 12) return "Good morning, Liam";
        if (hour >= 12 && hour < 17) return "Good afternoon, Liam";
        if (hour >= 17 && hour < 22) return "Good evening, Liam";
        return "Good night, Liam";
    }

    private static String quoteOfDay(long nowMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        int day = cal.get(Calendar.DAY_OF_YEAR);
        int year = cal.get(Calendar.YEAR);
        int idx = Math.floorMod(day * 31 + year * 17, QUOTES.length);
        return QUOTES[idx];
    }

    /**
     * Solid-ish band behind greeting + clock so list wordmarks never read through the header
     * text, even if frost is light or off.
     */
    private void drawStatusScrub(Canvas c, float w, float a) {
        if (a < 0.02f) return;
        float band = Math.max(128f * scale, frostH * 0.55f);
        int base = blend(corners[0], corners[1], 0.45f);
        boolean lightUi = iconColor == 0xFF000000;
        int top = lightUi
                ? ((blend(base, 0xFFFFFFFF, 0.25f) & 0x00FFFFFF) | 0xEE000000)
                : ((blend(base, 0xFF000000, 0.7f) & 0x00FFFFFF) | 0xF2000000);
        int bot = 0x00000000;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setShader(new LinearGradient(0, 0, 0, band,
                new int[]{top, top, bot},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        int save = c.saveLayerAlpha(0, 0, w, band, Math.max(0, Math.min(255, (int) (255 * a))));
        c.drawRect(0, 0, w, band, paint);
        c.restoreToCount(save);
        paint.setShader(null);
    }

    /** Top-left: time-of-day hello + quote of the day (floating XMB text, no glass plate). */
    private void drawGreeting(Canvas c, float w, float h, float a) {
        if (a < 0.02f) return;
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        String greet = greetingForHour(cal.get(Calendar.HOUR_OF_DAY));
        String quote = quoteOfDay(now);

        float left = 56 * scale;
        float timeY = 88 * scale;
        float maxW = Math.min(w * 0.42f, 640 * scale);

        Paint.Align titleAlign = titlePaint.getTextAlign();
        Paint.Align smallAlign = smallPaint.getTextAlign();
        titlePaint.setTextAlign(Paint.Align.LEFT);
        smallPaint.setTextAlign(Paint.Align.LEFT);

        // Same sizes as list title + date/meta (titlePaint 42, smallPaint 22).
        String greetShown = TextUtils.ellipsize(greet, titlePaint, maxW, TextUtils.TruncateAt.END).toString();
        String quoteShown = TextUtils.ellipsize(quote, smallPaint, maxW, TextUtils.TruncateAt.END).toString();

        titlePaint.setColor(textColor);
        titlePaint.setAlpha((int) (255 * a));
        softText(titlePaint, a);
        c.drawText(greetShown, left, timeY, titlePaint);

        smallPaint.setColor(textColor);
        smallPaint.setAlpha((int) (155 * a));
        softText(smallPaint, a * 0.7f);
        c.drawText(quoteShown, left, timeY + 34 * scale, smallPaint);

        titlePaint.setTextAlign(titleAlign);
        smallPaint.setTextAlign(smallAlign);
    }

    /** Status cluster in the top-right: time leads, date and weather sit quietly beside it. */
    private void drawClock(Canvas c, float w, float h, float a) {
        long now = System.currentTimeMillis();
        if (now - clockStamp > 1000) {
            clockStamp = now;
            timeText = timeFmt.format(now);
            dateText = dateFmt.format(now);
        }
        int al = (int) (255 * a);
        float right = w - 56 * scale;
        float timeY = 88 * scale;

        timePaint.setTextAlign(Paint.Align.RIGHT);
        timePaint.setTextSize(64 * scale);
        smallPaint.setTextAlign(Paint.Align.RIGHT);
        smallPaint.setTextSize(22 * scale);

        timePaint.setColor(textColor);
        timePaint.setAlpha(al);
        softText(timePaint, a);
        c.drawText(timeText, right, timeY, timePaint);

        smallPaint.setColor(textColor);
        smallPaint.setAlpha((int) (160 * a));
        softText(smallPaint, a * 0.7f);
        c.drawText(dateText, right, timeY + 34 * scale, smallPaint);

        float stackBottom = timeY + 34 * scale;
        if (badge != null) {
            smallPaint.setAlpha((int) (200 * a));
            float by = stackBottom + 30 * scale;
            c.drawText(badge, right, by, smallPaint);
            float tw = smallPaint.measureText(badge);
            int accent = blend(corners[2], corners[3], 0.5f);
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(accent);
            paint.setAlpha((int) ((140 + 50 * Math.sin(animT * 2.2f)) * a));
            c.drawCircle(right - tw - 18 * scale, by - 7 * scale, 4.5f * scale, paint);
            stackBottom = by;
        }

        if (wGlyph >= 0) {
            float clockW = Math.max(timePaint.measureText(timeText), smallPaint.measureText(dateText));
            float gap = 36 * scale;
            float ruleX = right - clockW - gap;
            // Theme hairline separates weather from the clock — one composition, not two stickers.
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStrokeWidth(Math.max(1.5f, 1.5f * scale));
            paint.setColor(textColor);
            paint.setAlpha((int) (55 * a));
            c.drawLine(ruleX, timeY - 36 * scale, ruleX, timeY + 28 * scale, paint);

            float weatherRight = ruleX - 20 * scale;
            // Weather uses the same meta size as date/quote (22); temp slightly stronger via alpha only.
            float tempW = smallPaint.measureText(wTemp);
            float descW = smallPaint.measureText(wDesc);
            float textBlock = Math.max(tempW, descW);
            float gs = 52 * scale;
            float gx = weatherRight - textBlock - 16 * scale - gs / 2f;
            paint.reset();
            paint.setAntiAlias(true);
            drawGlyph(c, wGlyph, gx, timeY - 4 * scale, gs, textColor, (int) (al * 0.92f), paint, false);
            smallPaint.setAlpha((int) (220 * a));
            c.drawText(wTemp, weatherRight, timeY - 8 * scale, smallPaint);
            smallPaint.setAlpha((int) (150 * a));
            c.drawText(wDesc, weatherRight, timeY + 22 * scale, smallPaint);
        }
    }

    /** Quiet depth under type — never a heavy drop shadow. */
    private void softText(TextPaint p, float a) {
        if (shadows) {
            int ink = iconColor == 0xFF000000 ? 0x28FFFFFF : 0x66000000;
            p.setShadowLayer(2f * scale, 0f, 1.2f * scale, (ink & 0x00FFFFFF)
                    | ((int) (((ink >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a))) << 24));
        } else {
            p.clearShadowLayer();
        }
    }

    /** Theme accent for focus ticks and Multiview chrome. */
    static int accentColor() {
        int[] t = currentCorners;
        return blend(t[1], t[3], 0.55f);
    }

    private int focusAccent() {
        return accentColor();
    }

    /** A small note in the bottom-right corner. It fades in, then leaves on its own. */
    private void drawNotice(Canvas c, float w, float h, long now) {
        if (notice == null) return;
        long age = now - noticeAt;
        if (age > 3800) {
            notice = null;
            return;
        }
        float a = age < 200 ? age / 200f : age > 3200 ? (3800 - age) / 600f : 1f;
        float rise = (age < 260 ? 1f - age / 260f : 0f) * 10 * scale;
        float padX = 16 * scale;
        float padY = 10 * scale;
        smallPaint.setTextAlign(Paint.Align.RIGHT);
        smallPaint.setTextSize(22 * scale);
        float maxW = Math.min(w * 0.32f, 400 * scale);
        String text = TextUtils.ellipsize(notice, smallPaint, maxW, TextUtils.TruncateAt.END).toString();
        float textW = smallPaint.measureText(text);
        float right = w - 28 * scale;
        float bottom = h - 22 * scale + rise;
        paint.reset();
        paint.setAntiAlias(true);
        silPaint.reset();
        silPaint.setAntiAlias(true);
        rect.set(right - textW - padX, bottom - smallPaint.getTextSize() - padY * 0.35f,
                right + padX * 0.35f, bottom + padY * 0.55f);
        int save = c.saveLayerAlpha(rect.left - 4, rect.top - 4, rect.right + 4, rect.bottom + 4,
                Math.max(0, Math.min(255, (int) (255 * a))));
        Glass.panel(c, rect, 8 * scale, paint, silPaint, 0x99000000);
        c.restoreToCount(save);
        smallPaint.setColor(iconColor);
        smallPaint.setAlpha((int) (245 * a));
        softText(smallPaint, a);
        c.drawText(text, right, bottom, smallPaint);
    }

    // ---- bar and items

    private void drawBar(Canvas c, float w, float h, float cp, float dp, float in) {
        for (int i = 0; i < cats.size(); i++) {
            Cat cat = cats.get(i);
            float dx = i - cp;
            float dist = Math.min(1f, Math.abs(dx));
            float x = anchorX + dx * spacingH;
            float zoom = 1f - 0.42f * dist;          // active 1.0, passive ~0.58
            float alpha = (1f - 0.22f * dist) * in;
            if (Math.abs(dx) > 0.5f) alpha *= (1f - dp);
            // Focus tick under the active category — theme colour, not a glow halo.
            if (dist < 0.55f && dp < 0.95f) {
                float tick = (1f - dist / 0.55f) * in * (1f - dp);
                paint.reset();
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(focusAccent());
                paint.setAlpha((int) (200 * tick));
                float tw = iconSize * zoom * (0.22f + 0.28f * tick);
                float ty = barY + iconSize * zoom * 0.58f;
                rect.set(x - tw, ty, x + tw, ty + Math.max(2.5f, 3f * scale));
                c.drawRoundRect(rect, 2f * scale, 2f * scale, paint);
            }
            paint.reset();
            paint.setAntiAlias(true);
            drawGlyph(c, cat.glyph, x, barY, iconSize * zoom, textColor, (int) (255 * alpha), paint, false);

            float la = Math.max(0f, 1f - Math.abs(dx) * 1.5f) * in;
            if (la > 0f && dp < 0.99f) drawLabel(c, cat.title, x, la * (1f - dp));
        }
        if (!stack.isEmpty() || dp > 0.01f) {
            Level l = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            if (l != null) drawLabel(c, l.title, anchorX, dp * in);
        }
    }

    private void drawLabel(Canvas c, String text, float x, float a) {
        labelPaint.setColor(textColor);
        labelPaint.setAlpha((int) (255 * a));
        softText(labelPaint, a);
        c.drawText(text, x, barY + iconSize * 0.5f + 40 * scale, labelPaint);
    }

    /**
     * Row offset (in rows below the bar) of an item at 'rel' places from the selection, following
     * RetroArch: previous items sit above the bar, the selected one three rows below it, and the next
     * one two rows further to leave room for the selected item's sub-label.
     */
    private static float rows(float rel) {
        if (rel <= -1f) return -2f + (rel + 1f);
        if (rel <= 0f) return -2f + (rel + 1f) * 5f;
        if (rel <= 1f) return 3f + rel * 2f;
        return 5f + (rel - 1f);
    }

    /**
     * Line glyphs draw as themselves. App icons keep adaptive foregrounds (no system circle).
     * Colourful brand marks keep their colours and cast a soft shadow for depth. Near-mono
     * silhouettes tint to the menu ink. Dark plates with a light mark (Apple TV) become a white
     * silhouette with a soft opposite halo so they stay visible on frost and light themes.
     */
    private void drawIcon(Canvas c, Item it, float x, float y, float size, int alpha) {
        if (it.icon == null) return;
        if (it.icon instanceof GlyphDrawable) {
            int l = (int) (x - size / 2);
            int t = (int) (y - size / 2);
            int r = (int) (x + size / 2);
            int b = (int) (y + size / 2);
            it.icon.setBounds(l, t, r, b);
            it.icon.setAlpha(alpha);
            it.icon.draw(c);
            return;
        }
        Silhouette sil = silhouette(it.icon, size);
        if (shadows && sil.shadow != null) {
            float depth = sil.branded ? 0.34f : 0.24f;
            silPaint.setColorFilter(null);
            silPaint.setAlpha((int) (alpha * depth));
            float sw = sil.shadow.getWidth() * (size / sil.px);
            float sh = sil.shadow.getHeight() * (size / sil.px);
            // Tight contact shadow — slight offset, not a wide soft wash.
            float ox = size * 0.012f;
            float oy = size * 0.022f;
            rect.set(x - sw / 2f + ox, y - sh / 2f + oy, x + sw / 2f + ox, y + sh / 2f + oy);
            c.drawBitmap(sil.shadow, null, rect, silPaint);
        }
        // Thin opposite edge so white mono marks stay readable on bright frost.
        if (!sil.branded && sil.tint && shadows) {
            int halo = iconColor == 0xFF000000 ? 0x40FFFFFF : 0x50000000;
            silPaint.setColorFilter(new PorterDuffColorFilter(halo, PorterDuff.Mode.SRC_IN));
            silPaint.setAlpha(alpha);
            float pad = size * 0.018f;
            rect.set(x - size / 2f - pad, y - size / 2f - pad, x + size / 2f + pad, y + size / 2f + pad);
            c.drawBitmap(sil.art, null, rect, silPaint);
            silPaint.setColorFilter(null);
        }
        silPaint.setColorFilter(sil.tint
                ? new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN) : null);
        silPaint.setAlpha(alpha);
        rect.set(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f);
        c.drawBitmap(sil.art, null, rect, silPaint);
        silPaint.setColorFilter(null);
        silPaint.setAlpha(255);
    }

    /**
     * Artwork to analyse and draw. Adaptive icons use the foreground mark only (Disney+ plate
     * stays out) so streaming logos become white silhouettes like Netflix / YouTube. Falls back
     * to bg+fg only when the foreground is empty.
     */
    private static Drawable appArtwork(Drawable icon) {
        if (Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
            Drawable fg = ad.getForeground();
            Drawable bg = ad.getBackground();
            if (fg != null) {
                // Foreground alone — plate/gradient backgrounds become grey squares when tinted.
                return fg.mutate();
            }
            if (bg != null) return bg.mutate();
        }
        return icon.mutate();
    }

    private static int silBucket(float size) {
        int px = Math.max(24, Math.min(96, Math.round(size)));
        if (px <= 40) return 32;
        if (px <= 56) return 48;
        if (px <= 72) return 64;
        if (px <= 88) return 80;
        return 96;
    }

    private Silhouette silhouette(Drawable icon, float size) {
        int px = silBucket(size);
        long key = (((long) System.identityHashCode(icon)) << 32) | (px & 0xffffffffL);
        Silhouette sil = silCache.get(key);
        if (sil != null) return sil;
        sil = buildSilhouette(icon, px);
        silCache.put(key, sil);
        return sil;
    }

    private Silhouette buildSilhouette(Drawable icon, int px) {
        Bitmap src = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(src);
        Drawable drawn = appArtwork(icon);
        drawn.setBounds(0, 0, px, px);
        drawn.setAlpha(255);
        drawn.setColorFilter(null);
        drawn.draw(sc);

        int n = px * px;
        int[] pix = new int[n];
        src.getPixels(pix, 0, px, 0, 0, px, px);
        int opaque = 0;
        for (int p : pix) if (((p >>> 24) & 0xFF) > 40) opaque++;
        // Rounded TV badges (Disney+, Paramount+) leave transparent corners — treat ~70%+ as a plate.
        boolean opaquePlate = opaque * 10 >= n * 7;

        Bitmap art;
        boolean tint;
        boolean branded;

        // Netflix / YouTube / Disney+ / Paramount+: plate + mark → knock out plate, white silhouette.
        if (opaquePlate) {
            int bg = samplePlateBackground(pix, px);
            int logo = 0;
            for (int p : pix) {
                int a = (p >>> 24) & 0xFF;
                if (a < 40) continue;
                if (colorDist(p, bg) < 48) continue;
                logo++;
            }
            float logoFrac = opaque == 0 ? 0f : logo / (float) opaque;
            Bitmap mark = null;
            if (logoFrac > 0.01f && logoFrac < 0.88f) {
                mark = knockoutPlate(pix, px, bg);
            }
            if (mark == null || contentFill(mark) > 0.82f) {
                if (mark != null) mark.recycle();
                // Light logo on dark plate (Disney+) or dark logo on light plate.
                float avgLuma = 0;
                int bright = 0, dark = 0;
                for (int p : pix) {
                    int a = (p >>> 24) & 0xFF;
                    if (a < 40) continue;
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                    avgLuma += luma;
                    if (luma > 160) bright++;
                    if (luma < 80) dark++;
                }
                avgLuma = opaque == 0 ? 0f : avgLuma / opaque;
                float brightFrac = opaque == 0 ? 0f : bright / (float) opaque;
                float darkFrac = opaque == 0 ? 0f : dark / (float) opaque;
                if (brightFrac > 0.02f && brightFrac < 0.55f && avgLuma < 140) {
                    mark = inkFromLuma(pix, px, true);
                } else if (darkFrac > 0.02f && darkFrac < 0.55f && avgLuma > 120) {
                    mark = inkFromLuma(pix, px, false);
                } else {
                    mark = inkFromInvert(pix, px);
                }
            }
            Bitmap cropped = cropToContent(mark, px);
            if (cropped != mark) mark.recycle();
            art = inkToWhite(cropped);
            if (art != cropped) cropped.recycle();
            tint = true;
            branded = false;
            src.recycle();
        } else {
            // Soft / transparent icons: still prefer a white silhouette unless clearly multi-hue art.
            int[] hueBuckets = new int[12];
            int counted = 0;
            for (int p : pix) {
                int a = (p >>> 24) & 0xFF;
                if (a < 40) continue;
                counted++;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (chroma > 28) {
                    Color.RGBToHSV(r, g, b, TMP_HSV);
                    hueBuckets[Math.min(11, Math.max(0, (int) (TMP_HSV[0] / 30f)))]++;
                }
            }
            int hueKinds = 0;
            int hueFloor = Math.max(1, counted / 40);
            for (int h : hueBuckets) if (h > hueFloor) hueKinds++;
            // Only keep full-colour for busy multi-hue marks (e.g. Play Store). Streaming
            // wordmarks / mountains become menu-ink like Netflix and YouTube.
            branded = hueKinds >= 4;
            if (branded) {
                art = src;
                tint = false;
            } else {
                Bitmap cropped = cropToContent(src, px);
                art = inkToWhite(cropped);
                if (cropped != src) cropped.recycle();
                src.recycle();
                tint = true;
            }
        }

        Bitmap alpha = art.extractAlpha();
        // Narrow blur so the shadow hugs the mark instead of a wide fog around the edges.
        float blur = Math.max(1.2f, px * (branded ? 0.06f : 0.05f));
        float grow = branded ? 1.06f : 1.04f;
        Bitmap shadow = blurAlpha(alpha, blur, grow);
        alpha.recycle();
        return new Silhouette(px, shadow, art, tint, branded);
    }

    /** Alpha-only white mask from any bitmap (for menu-ink tinting). */
    private static Bitmap inkToWhite(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        for (int i = 0; i < pix.length; i++) {
            int a = (pix[i] >>> 24) & 0xFF;
            pix[i] = (a << 24) | 0x00FFFFFF;
        }
        out.setPixels(pix, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * Scale the opaque content up to fill {@code px}×{@code px} with a little padding, so a
     * small centred Netflix N / YouTube play mark isn't lost in empty plate space.
     */
    private static Bitmap cropToContent(Bitmap src, int px) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((pix[y * w + x] >>> 24) & 0xFF) < 40) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX) return src;
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        // Already fills most of the tile — leave it.
        if (bw * 10 >= w * 7 && bh * 10 >= h * 7) return src;
        int side = Math.max(bw, bh);
        int pad = Math.max(2, Math.round(side * 0.12f));
        side += pad * 2;
        Bitmap box = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(box);
        int dx = (side - bw) / 2;
        int dy = (side - bh) / 2;
        c.drawBitmap(src, new android.graphics.Rect(minX, minY, maxX + 1, maxY + 1),
                new android.graphics.Rect(dx, dy, dx + bw, dy + bh),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        Bitmap out = Bitmap.createScaledBitmap(box, px, px, true);
        if (out != box) box.recycle();
        return out;
    }

    private static final float[] TMP_HSV = new float[3];

    /** Average of the icon's edge pixels — the plate colour for Netflix/YouTube-style badges. */
    private static int samplePlateBackground(int[] pix, int px) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int i = 0; i < px; i++) {
            int[] edges = {pix[i], pix[(px - 1) * px + i], pix[i * px], pix[i * px + (px - 1)]};
            for (int p : edges) {
                if (((p >>> 24) & 0xFF) < 200) continue;
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0xFFFFFFFF;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    private static int colorDist(int p, int bg) {
        int dr = ((p >> 16) & 0xFF) - ((bg >> 16) & 0xFF);
        int dg = ((p >> 8) & 0xFF) - ((bg >> 8) & 0xFF);
        int db = (p & 0xFF) - (bg & 0xFF);
        return Math.abs(dr) + Math.abs(dg) + Math.abs(db);
    }

    /** Fraction of the bitmap that still has visible pixels (after knockout). */
    private static float contentFill(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int on = 0;
        for (int p : pix) if (((p >>> 24) & 0xFF) > 40) on++;
        return on / (float) (w * h);
    }

    /** Drop plate pixels matching the background; keep the brand mark. */
    private static Bitmap knockoutPlate(int[] pix, int px, int bg) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        int[] dst = new int[pix.length];
        for (int i = 0; i < pix.length; i++) {
            int p = pix[i];
            int a = (p >>> 24) & 0xFF;
            if (a < 40 || colorDist(p, bg) < 48) {
                dst[i] = 0;
                continue;
            }
            // Soft edge where the mark meets the plate.
            int d = colorDist(p, bg);
            if (d < 88) {
                int aa = Math.min(a, (d - 48) * 255 / 40);
                dst[i] = (Math.max(0, aa) << 24) | (p & 0x00FFFFFF);
            } else {
                dst[i] = p;
            }
        }
        out.setPixels(dst, 0, px, 0, 0, px, px);
        return out;
    }

    /** Build a silhouette mask from bright (or dark) pixels on an opaque plate. */
    private static Bitmap inkFromLuma(int[] pix, int px, boolean keepBright) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        int[] dst = new int[pix.length];
        for (int i = 0; i < pix.length; i++) {
            int p = pix[i];
            int a = (p >>> 24) & 0xFF;
            if (a < 40) {
                dst[i] = 0;
                continue;
            }
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            float luma = 0.299f * r + 0.587f * g + 0.114f * b;
            float t = keepBright ? (luma - 90f) / 100f : (160f - luma) / 100f;
            int aa = Math.max(0, Math.min(255, Math.round(t * 255f)));
            dst[i] = (aa << 24) | 0x00FFFFFF;
        }
        out.setPixels(dst, 0, px, 0, 0, px, px);
        return out;
    }

    /** Soft invert of a dark plate into a white-friendly alpha mask. */
    private static Bitmap inkFromInvert(int[] pix, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        int[] dst = new int[pix.length];
        for (int i = 0; i < pix.length; i++) {
            int p = pix[i];
            int a = (p >>> 24) & 0xFF;
            if (a < 40) {
                dst[i] = 0;
                continue;
            }
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            float luma = 0.299f * r + 0.587f * g + 0.114f * b;
            int aa = Math.max(0, Math.min(255, Math.round((255f - luma) * a / 255f)));
            dst[i] = (aa << 24) | 0x00FFFFFF;
        }
        out.setPixels(dst, 0, px, 0, 0, px, px);
        return out;
    }

    /** Soften an alpha mask into a padded bitmap the logo's own outline can cast. */
    private static Bitmap blurAlpha(Bitmap alpha, float radius, float grow) {
        int pad = Math.max(1, (int) Math.ceil(radius * 1.15f));
        int w = Math.round(alpha.getWidth() * grow) + pad * 2;
        int h = Math.round(alpha.getHeight() * grow) + pad * 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        float dw = alpha.getWidth() * grow;
        float dh = alpha.getHeight() * grow;
        RectF dst = new RectF(pad, pad, pad + dw, pad + dh);
        c.drawBitmap(alpha, null, dst, p);
        return out;
    }

    private static final class Silhouette {
        final int px;
        final Bitmap shadow;
        /** Pre-rasterised icon (knocked-out brand mark, colour art, or white alpha mask). */
        final Bitmap art;
        /** When true, {@link #art} is tinted with the menu ink colour. */
        final boolean tint;
        /** Colourful brand mark — depth shadow, no mono tint. */
        final boolean branded;

        void recycle() {
            if (shadow != null && !shadow.isRecycled()) shadow.recycle();
            if (art != null && !art.isRecycled()) art.recycle();
        }

        Silhouette(int px, Bitmap shadow, Bitmap art, boolean tint, boolean branded) {
            this.px = px;
            this.shadow = shadow;
            this.art = art;
            this.tint = tint;
            this.branded = branded;
        }
    }

    private void drawSwitch(Canvas c, float x, float y, boolean on, int alpha) {
        float sw = 84 * scale;
        float sh = 42 * scale;
        paint.reset();
        paint.setAntiAlias(true);
        rect.set(x, y - sh / 2, x + sw, y + sh / 2);
        paint.setColor(textColor);
        if (on) {
            paint.setAlpha((int) (alpha * 0.92f));
            c.drawRoundRect(rect, sh / 2, sh / 2, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, 3 * scale));
            paint.setAlpha((int) (alpha * 0.6f));
            c.drawRoundRect(rect, sh / 2, sh / 2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float knobR = sh / 2 - 6 * scale;
        float kx = on ? x + sw - sh / 2 : x + sh / 2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? corners[3] : textColor);
        if (on && (textColor == 0xFF000000)) paint.setColor(0xFFFFFFFF);
        paint.setAlpha(alpha);
        c.drawCircle(kx, y, knobR, paint);
    }

    private void drawChevron(Canvas c, float x, float y, int alpha) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2.5f, 4 * scale));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(textColor);
        paint.setAlpha(alpha);
        float s = 15 * scale;
        path.reset();
        path.moveTo(x, y - s);
        path.lineTo(x + s, y);
        path.lineTo(x, y + s);
        c.drawPath(path, paint);
    }

    private void drawColumn(Canvas c, float w, float h, List<Item> items, float selPos, float x, float colAlpha,
                            boolean values) {
        if (colAlpha <= 0.01f) return;
        float pulseP = press.cur;                                       // 0..1 after a press
        float pulse = pulseP < 1f ? (float) Math.sin(Math.PI * pulseP) : 0f;
        float hintLvl = hintLevel();
        for (int j = 0; j < items.size(); j++) {
            Item it = items.get(j);
            float rel = j - selPos;
            float y = barY + rows(rel) * rowH;
            if (y < -rowH || y > h + rowH) continue;
            float near = Math.min(1f, Math.abs(rel));
            float zoom = 1f - 0.42f * near;
            float a = colAlpha * (1f - 0.22f * near);
            a *= Math.max(0f, Math.min(1f, y / (rowH * 2f)));           // fade at the top edge
            float fromBar = Math.abs(y - barY) / (rowH * 1.4f);          // and while sliding past the bar
            if (fromBar < 1f && rel != 0f) a *= fromBar * fromBar;
            // Status header is reserved for greeting / clock — hide list icons & titles there
            // so Disney+ wordmarks can't jumble with the header text.
            float clearY = Math.max(160f * scale, frostH * 0.88f);
            if (y < clearY) {
                // Hard cut with a short fade into the menu, not a soft bleed through the quote.
                float t = (y - clearY + rowH * 0.85f) / (rowH * 0.85f);
                if (t <= 0f) continue;
                a *= Math.min(1f, t * t);
            }
            if (hintLvl > 0f) { // rows fade out before they reach the button hints
                float bf = Math.max(0f, Math.min(1f, (h - 175 * scale - y) / (rowH * 1.1f)));
                a *= 1f - hintLvl * (1f - bf);
            }
            if (a <= 0.01f) continue;
            int alpha = (int) (255 * a);

            float size = iconSize * 0.75f * zoom * (1f - 0.06f * pulse * (1f - near));
            drawIcon(c, it, x, y, size, alpha);

            float tx = x + iconSize * 0.42f + 20 * scale;
            boolean marker = values && (it.value != null || it.toggle >= 0 || it.submenu);
            float valueX = w * 0.56f;
            float maxW = (marker ? valueX - 40 * scale : w - 80 * scale - rightInset) - tx;
            titlePaint.setColor(textColor);
            titlePaint.setAlpha(alpha);
            softText(titlePaint, a);
            String shown = it.title;
            if (shown.endsWith("...")) { // animated "loading" dots
                int dots = 1 + ((int) (animT * 2.5f)) % 3;
                shown = shown.substring(0, shown.length() - 3) + "...".substring(0, dots);
            }
            String title = it.fitTitle(titlePaint, shown, maxW);
            c.drawText(title, tx, y + titlePaint.getTextSize() * 0.34f, titlePaint);
            if (values && it.value != null) {
                valuePaint.setColor(textColor);
                valuePaint.setAlpha(alpha);
                softText(valuePaint, a);
                String v = it.fitValue(valuePaint, it.value, w - valueX - 70 * scale);
                c.drawText(v, valueX, y + valuePaint.getTextSize() * 0.34f, valuePaint);
            } else if (values && it.toggle >= 0) {
                drawSwitch(c, valueX, y, it.toggle == 1, alpha);
            } else if (values && it.submenu) {
                drawChevron(c, valueX + 6 * scale, y, (int) (alpha * 0.85f));
            }
            if (rel > -0.5f && rel < 0.5f && it.sub != null && !it.sub.isEmpty()) {
                float sa = a * (1f - Math.abs(rel) * 2f);
                subPaint.setColor(textColor);
                subPaint.setAlpha((int) (170 * sa));
                softText(subPaint, sa);
                String sub = it.fitSub(subPaint, it.sub, w - tx - 80 * scale);
                c.drawText(sub, tx, y + rowH * 0.95f + subPaint.getTextSize() * 0.2f, subPaint);
            }
        }
    }

    /** 1 while the button hints are on screen, fading to 0 once the remote has been idle for a while. */
    private float hintLevel() {
        if (!hints) return 0f;
        long idle = SystemClock.uptimeMillis() - lastInput;
        return Math.max(0f, Math.min(1f, (9000 - idle) / 900f)) * intro.cur;
    }

    /** Position counter and auto-hiding button hints along the bottom edge. */
    private void drawFooter(Canvas c, float w, float h, float dp, float in) {
        List<Item> items;
        int sel;
        if (!stack.isEmpty()) {
            Level l = stack.get(stack.size() - 1);
            items = l.items;
            sel = l.sel;
        } else if (!cats.isEmpty()) {
            Cat cat = cats.get(catIndex);
            items = cat.items;
            sel = cat.sel;
        } else {
            return;
        }
        if (items.size() > 8) {
            smallPaint.setColor(textColor);
            smallPaint.setAlpha((int) (170 * in));
            shadow(smallPaint, 2 * scale, 0x80);
            c.drawText((sel + 1) + " / " + items.size(), w - 70 * scale, h - 60 * scale, smallPaint);
        }
        if (!hints) return;
        float ha = hintLevel();
        if (ha <= 0.01f) return;
        Item it = current();
        String[] keys;
        String[] labels;
        if (!stack.isEmpty()) {
            keys = new String[]{"OK", "Back"};
            labels = new String[]{"Select", "Return"};
        } else if (it != null && it.longAction != null) {
            keys = new String[]{"OK", "Hold OK", "◀ ▶"};
            labels = new String[]{"Open", "Options", "Category"};
        } else {
            keys = new String[]{"OK", "◀ ▶"};
            labels = new String[]{"Open", "Category"};
        }
        // Floating XMB text — same type + soft shadow as titles/date; no dark pills.
        float total = 0;
        TextPaint kp = labelPaint;
        float old = kp.getTextSize();
        Paint.Align oldAlign = kp.getTextAlign();
        kp.setTextSize(22 * scale);
        kp.setTextAlign(Paint.Align.LEFT);
        smallPaint.setTextAlign(Paint.Align.LEFT);
        smallPaint.setTextSize(22 * scale);
        float gap = 36 * scale;
        float[] kw = new float[keys.length];
        float[] lw = new float[keys.length];
        for (int i = 0; i < keys.length; i++) {
            kw[i] = kp.measureText(keys[i]);
            lw[i] = smallPaint.measureText(labels[i]);
            total += kw[i] + 10 * scale + lw[i] + (i < keys.length - 1 ? gap : 0);
        }
        float x = w - 56 * scale - total;
        float baseline = h - 52 * scale;
        for (int i = 0; i < keys.length; i++) {
            kp.setColor(textColor);
            kp.setAlpha((int) (230 * ha));
            softText(kp, ha);
            c.drawText(keys[i], x, baseline, kp);
            smallPaint.setColor(textColor);
            smallPaint.setAlpha((int) (155 * ha));
            softText(smallPaint, ha * 0.7f);
            c.drawText(labels[i], x + kw[i] + 10 * scale, baseline, smallPaint);
            x += kw[i] + 10 * scale + lw[i] + gap;
        }
        kp.setTextSize(old);
        kp.setTextAlign(oldAlign);
        smallPaint.setTextAlign(Paint.Align.RIGHT);
        smallPaint.setTextSize(22 * scale);
    }

    private void shadow(TextPaint p, float off, int alpha) {
        softText(p, alpha / 255f);
    }

    // ------------------------------------------------------------------ glyphs

    /** Line icons like RetroArch's monochrome set: thin strokes, shapes inside a round grid. */
    private static final RectF glyphRect = new RectF();
    private static final Path glyphPath = new Path();
    private static final Path cloudA = new Path();
    private static final Path cloudB = new Path();
    private static final Path cloudC = new Path();
    private static final Path cloudD = new Path();

    static void drawGlyph(Canvas c, int g, float cx, float cy, float s, int color, int alpha, Paint p, boolean shadow) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.05f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        p.setAlpha(alpha);
        if (shadow) p.setShadowLayer(0.6f, s * 0.012f, s * 0.014f, ((int) (0x55 * alpha / 255f) << 24));
        float h = s / 2f * 0.9f;
        RectF r = glyphRect;
        Path path = glyphPath;
        path.rewind();
        switch (g) {
            case G_PLAY:
                r.set(cx - h, cy - h * 0.75f, cx + h, cy + h * 0.75f);
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, p);
                p.setStyle(Paint.Style.FILL);
                path.moveTo(cx - h * 0.25f, cy - h * 0.38f);
                path.lineTo(cx + h * 0.38f, cy);
                path.lineTo(cx - h * 0.25f, cy + h * 0.38f);
                path.close();
                c.drawPath(path, p);
                break;
            case G_GRID: {
                float q = s * 0.34f;
                float gap = s * 0.1f;
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 2; col++) {
                        float l = cx - q - gap / 2 + col * (q + gap);
                        float tp = cy - q - gap / 2 + row * (q + gap);
                        r.set(l, tp, l + q, tp + q);
                        c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                    }
                }
                break;
            }
            case G_MULTI: {
                float q = s * 0.34f;
                float gap = s * 0.1f;
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 2; col++) {
                        float l = cx - q - gap / 2 + col * (q + gap);
                        float tp = cy - q - gap / 2 + row * (q + gap);
                        r.set(l, tp, l + q, tp + q);
                        p.setStyle(row == 0 && col == 0 ? Paint.Style.FILL : Paint.Style.STROKE);
                        c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                    }
                }
                break;
            }
            case G_INPUT:
                r.set(cx - h, cy - h * 0.62f, cx + h, cy + h * 0.32f);
                c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                c.drawLine(cx, cy + h * 0.32f, cx, cy + h * 0.7f, p);
                c.drawLine(cx - h * 0.45f, cy + h * 0.7f, cx + h * 0.45f, cy + h * 0.7f, p);
                break;
            case G_GEAR:
                c.drawCircle(cx, cy, h * 0.46f, p);
                c.drawCircle(cx, cy, h * 0.2f, p);
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    c.drawLine(cx + (float) Math.cos(a) * h * 0.62f, cy + (float) Math.sin(a) * h * 0.62f,
                            cx + (float) Math.cos(a) * h * 0.92f, cy + (float) Math.sin(a) * h * 0.92f, p);
                }
                break;
            case G_PICTURE:
                r.set(cx - h, cy - h * 0.72f, cx + h, cy + h * 0.72f);
                c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                path.moveTo(cx - h * 0.8f, cy + h * 0.5f);
                path.lineTo(cx - h * 0.2f, cy - h * 0.1f);
                path.lineTo(cx + h * 0.15f, cy + h * 0.3f);
                path.lineTo(cx + h * 0.4f, cy + h * 0.05f);
                path.lineTo(cx + h * 0.8f, cy + h * 0.5f);
                c.drawPath(path, p);
                c.drawCircle(cx + h * 0.45f, cy - h * 0.35f, h * 0.15f, p);
                break;
            case G_SOUND:
                path.moveTo(cx - h * 0.9f, cy - h * 0.3f);
                path.lineTo(cx - h * 0.5f, cy - h * 0.3f);
                path.lineTo(cx - h * 0.1f, cy - h * 0.7f);
                path.lineTo(cx - h * 0.1f, cy + h * 0.7f);
                path.lineTo(cx - h * 0.5f, cy + h * 0.3f);
                path.lineTo(cx - h * 0.9f, cy + h * 0.3f);
                path.close();
                c.drawPath(path, p);
                for (int k = 1; k <= 2; k++) {
                    float rad = h * (0.3f + 0.4f * k);
                    r.set(cx - h * 0.1f - rad, cy - rad, cx - h * 0.1f + rad, cy + rad);
                    c.drawArc(r, -40, 80, false, p);
                }
                break;
            case G_WIFI: {
                float oy = cy + h * 0.55f;
                for (int k = 1; k <= 3; k++) {
                    float rad = h * 0.5f * k;
                    r.set(cx - rad, oy - rad, cx + rad, oy + rad);
                    c.drawArc(r, -135, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, oy, h * 0.1f, p);
                break;
            }
            case G_SUN:
                c.drawCircle(cx, cy, h * 0.38f, p);
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    c.drawLine(cx + (float) Math.cos(a) * h * 0.58f, cy + (float) Math.sin(a) * h * 0.58f,
                            cx + (float) Math.cos(a) * h * 0.88f, cy + (float) Math.sin(a) * h * 0.88f, p);
                }
                break;
            case G_CLOUD:
                cloud(c, cx, cy, h, p);
                break;
            case G_RAIN:
                cloud(c, cx, cy - h * 0.25f, h, p);
                for (int k = -1; k <= 1; k++) {
                    c.drawLine(cx + k * h * 0.4f, cy + h * 0.35f, cx + k * h * 0.4f - h * 0.12f, cy + h * 0.75f, p);
                }
                break;
            case G_SNOW:
                cloud(c, cx, cy - h * 0.25f, h, p);
                p.setStyle(Paint.Style.FILL);
                for (int k = -1; k <= 1; k++) c.drawCircle(cx + k * h * 0.4f, cy + h * 0.58f, h * 0.07f, p);
                break;
            case G_STORM:
                cloud(c, cx, cy - h * 0.25f, h, p);
                path.moveTo(cx + h * 0.05f, cy + h * 0.25f);
                path.lineTo(cx - h * 0.2f, cy + h * 0.6f);
                path.lineTo(cx + h * 0.1f, cy + h * 0.6f);
                path.lineTo(cx - h * 0.1f, cy + h * 0.95f);
                c.drawPath(path, p);
                break;
            case G_FOG:
                for (int k = -1; k <= 1; k++) {
                    c.drawLine(cx - h * 0.8f + Math.abs(k) * h * 0.1f, cy + k * h * 0.4f,
                            cx + h * 0.8f - Math.abs(k) * h * 0.1f, cy + k * h * 0.4f, p);
                }
                break;
            case G_CAL:
                r.set(cx - h * 0.85f, cy - h * 0.7f, cx + h * 0.85f, cy + h * 0.85f);
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                c.drawLine(cx - h * 0.85f, cy - h * 0.25f, cx + h * 0.85f, cy - h * 0.25f, p);
                c.drawLine(cx - h * 0.4f, cy - h * 0.95f, cx - h * 0.4f, cy - h * 0.5f, p);
                c.drawLine(cx + h * 0.4f, cy - h * 0.95f, cx + h * 0.4f, cy - h * 0.5f, p);
                p.setStyle(Paint.Style.FILL);
                for (int row = 0; row < 2; row++) {
                    for (int col = -1; col <= 1; col++) {
                        c.drawCircle(cx + col * h * 0.45f, cy + h * 0.1f + row * h * 0.4f, h * 0.07f, p);
                    }
                }
                break;
            case G_LIVE:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, h * 0.14f, p);
                p.setStyle(Paint.Style.STROKE);
                for (int k = 1; k <= 3; k++) {
                    float rad = h * 0.32f * k;
                    r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    c.drawArc(r, -50, 100, false, p);
                    c.drawArc(r, 130, 100, false, p);
                }
                break;
            case G_STAR: {
                for (int k = 0; k < 10; k++) {
                    double a = -Math.PI / 2 + k * Math.PI / 5;
                    float rad = (k % 2 == 0 ? 0.95f : 0.4f) * h;
                    float px = cx + (float) Math.cos(a) * rad;
                    float py = cy + (float) Math.sin(a) * rad;
                    if (k == 0) path.moveTo(px, py);
                    else path.lineTo(px, py);
                }
                path.close();
                c.drawPath(path, p);
                break;
            }
            case G_CLOCK:
                c.drawCircle(cx, cy, h * 0.9f, p);
                c.drawLine(cx, cy, cx, cy - h * 0.55f, p);
                c.drawLine(cx, cy, cx + h * 0.4f, cy + h * 0.15f, p);
                break;
            case G_PHONE:
                r.set(cx - h * 0.45f, cy - h * 0.9f, cx + h * 0.45f, cy + h * 0.9f);
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                c.drawLine(cx - h * 0.15f, cy + h * 0.68f, cx + h * 0.15f, cy + h * 0.68f, p);
                break;
            case G_INFO:
                c.drawCircle(cx, cy, h * 0.9f, p);
                c.drawLine(cx, cy - h * 0.1f, cx, cy + h * 0.5f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - h * 0.42f, h * 0.07f, p);
                break;
            case G_BRUSH:
                c.drawCircle(cx, cy, h * 0.9f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - h * 0.35f, cy - h * 0.2f, h * 0.12f, p);
                c.drawCircle(cx + h * 0.05f, cy - h * 0.45f, h * 0.12f, p);
                c.drawCircle(cx + h * 0.4f, cy - h * 0.05f, h * 0.12f, p);
                c.drawCircle(cx - h * 0.1f, cy + h * 0.35f, h * 0.12f, p);
                break;
            case G_PIN:
                c.drawCircle(cx, cy - h * 0.3f, h * 0.45f, p);
                c.drawLine(cx, cy + h * 0.15f, cx, cy + h * 0.9f, p);
                break;
            case G_SEARCH:
                c.drawCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.55f, p);
                c.drawLine(cx + h * 0.25f, cy + h * 0.25f, cx + h * 0.85f, cy + h * 0.85f, p);
                break;
            case G_PAUSE:
                c.drawLine(cx - h * 0.35f, cy - h * 0.7f, cx - h * 0.35f, cy + h * 0.7f, p);
                c.drawLine(cx + h * 0.35f, cy - h * 0.7f, cx + h * 0.35f, cy + h * 0.7f, p);
                break;
            case G_STOP:
                r.set(cx - h * 0.55f, cy - h * 0.55f, cx + h * 0.55f, cy + h * 0.55f);
                c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                break;
            case G_REW:
                path.moveTo(cx + h * 0.15f, cy - h * 0.7f);
                path.lineTo(cx - h * 0.45f, cy);
                path.lineTo(cx + h * 0.15f, cy + h * 0.7f);
                c.drawPath(path, p);
                path.rewind();
                path.moveTo(cx + h * 0.75f, cy - h * 0.7f);
                path.lineTo(cx + h * 0.15f, cy);
                path.lineTo(cx + h * 0.75f, cy + h * 0.7f);
                c.drawPath(path, p);
                break;
            case G_FF:
                path.moveTo(cx - h * 0.15f, cy - h * 0.7f);
                path.lineTo(cx + h * 0.45f, cy);
                path.lineTo(cx - h * 0.15f, cy + h * 0.7f);
                c.drawPath(path, p);
                path.rewind();
                path.moveTo(cx - h * 0.75f, cy - h * 0.7f);
                path.lineTo(cx - h * 0.15f, cy);
                path.lineTo(cx - h * 0.75f, cy + h * 0.7f);
                c.drawPath(path, p);
                break;
            case G_MUTE:
                path.moveTo(cx - h * 0.9f, cy - h * 0.3f);
                path.lineTo(cx - h * 0.5f, cy - h * 0.3f);
                path.lineTo(cx - h * 0.1f, cy - h * 0.7f);
                path.lineTo(cx - h * 0.1f, cy + h * 0.7f);
                path.lineTo(cx - h * 0.5f, cy + h * 0.3f);
                path.lineTo(cx - h * 0.9f, cy + h * 0.3f);
                path.close();
                c.drawPath(path, p);
                c.drawLine(cx + h * 0.2f, cy - h * 0.45f, cx + h * 0.85f, cy + h * 0.45f, p);
                c.drawLine(cx + h * 0.85f, cy - h * 0.45f, cx + h * 0.2f, cy + h * 0.45f, p);
                break;
            case G_UP:
                path.moveTo(cx - h * 0.55f, cy + h * 0.25f);
                path.lineTo(cx, cy - h * 0.55f);
                path.lineTo(cx + h * 0.55f, cy + h * 0.25f);
                c.drawPath(path, p);
                break;
            case G_DOWN:
                path.moveTo(cx - h * 0.55f, cy - h * 0.25f);
                path.lineTo(cx, cy + h * 0.55f);
                path.lineTo(cx + h * 0.55f, cy - h * 0.25f);
                c.drawPath(path, p);
                break;
            case G_TRASH:
                r.set(cx - h * 0.55f, cy - h * 0.25f, cx + h * 0.55f, cy + h * 0.85f);
                c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                c.drawLine(cx - h * 0.7f, cy - h * 0.25f, cx + h * 0.7f, cy - h * 0.25f, p);
                c.drawLine(cx - h * 0.25f, cy - h * 0.55f, cx + h * 0.25f, cy - h * 0.55f, p);
                c.drawLine(cx - h * 0.2f, cy - h * 0.05f, cx - h * 0.2f, cy + h * 0.55f, p);
                c.drawLine(cx + h * 0.2f, cy - h * 0.05f, cx + h * 0.2f, cy + h * 0.55f, p);
                break;
            case G_PLUS:
                c.drawLine(cx - h * 0.6f, cy, cx + h * 0.6f, cy, p);
                c.drawLine(cx, cy - h * 0.6f, cx, cy + h * 0.6f, p);
                break;
            case G_LIST:
                for (int k = -1; k <= 1; k++) {
                    float y = cy + k * h * 0.45f;
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx - h * 0.7f, y, h * 0.1f, p);
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx - h * 0.4f, y, cx + h * 0.75f, y, p);
                }
                break;
            case G_MOON:
                r.set(cx - h * 0.7f, cy - h * 0.7f, cx + h * 0.7f, cy + h * 0.7f);
                c.drawArc(r, 50, 260, false, p);
                r.set(cx - h * 0.15f, cy - h * 0.55f, cx + h * 0.85f, cy + h * 0.45f);
                c.drawArc(r, 110, 200, false, p);
                break;
            default:
                break;
        }
    }

    private static void cloud(Canvas c, float cx, float cy, float h, Paint p) {
        cloudA.rewind();
        cloudB.rewind();
        cloudC.rewind();
        cloudD.rewind();
        cloudA.addCircle(cx - h * 0.4f, cy + h * 0.1f, h * 0.35f, Path.Direction.CW);
        cloudB.addCircle(cx + h * 0.05f, cy - h * 0.2f, h * 0.5f, Path.Direction.CW);
        cloudA.op(cloudB, Path.Op.UNION);
        cloudC.addCircle(cx + h * 0.5f, cy + h * 0.1f, h * 0.32f, Path.Direction.CW);
        cloudA.op(cloudC, Path.Op.UNION);
        cloudD.addRect(cx - h * 0.4f, cy + h * 0.1f, cx + h * 0.5f, cy + h * 0.42f, Path.Direction.CW);
        cloudA.op(cloudD, Path.Op.UNION);
        c.drawPath(cloudA, p);
    }

    /** Small glyph usable as an item icon; follows the view's icon colour (white / black). */
    static final class GlyphDrawable extends Drawable {
        private final int glyph;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;

        GlyphDrawable(int glyph, int ignoredColor) {
            this.glyph = glyph;
        }

        @Override
        public void draw(Canvas c) {
            p.reset();
            p.setAntiAlias(true);
            float s = Math.min(getBounds().width(), getBounds().height());
            drawGlyph(c, glyph, getBounds().exactCenterX(), getBounds().exactCenterY(), s * 0.95f, iconColor, alpha, p, drawShadows);
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
