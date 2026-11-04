package com.liam.homescreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Multiview's channel tray: docked under the wall, it loads IPTV channels into a chosen window.
 * Three rows — which window to load into, the category, then the channel cards. All keys come in
 * through {@link #onKey}; the remote only needs the d-pad, OK, hold OK and Back.
 */
final class ChannelTray extends View {
    interface Host {
        /** Cells in the order they appear on the wall (slot 0 first). */
        int[] windows();

        /** The source id currently in that cell, or null. */
        String idOf(int cell);

        void load(int cell, String id);

        /** The tray wants the window highlighted on the wall. */
        void target(int cell);

        void close();
    }

    private static final int ROW_WINDOW = 0;
    private static final int ROW_CATEGORY = 1;
    private static final int ROW_CHANNEL = 2;
    private static final long HOLD_MS = 500;

    private final Host host;
    private final SharedPreferences prefs;
    private final float d;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintKey = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintLab = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final GradientDrawable plate;

    private final List<String> cats = new ArrayList<>();
    private final List<IptvPlaylist.Channel> list = new ArrayList<>();
    private final List<Integer> numbers = new ArrayList<>();
    private int slot;
    private int cat = 2;
    private int sel;
    private int row = ROW_CHANNEL;
    private boolean held;
    private boolean loading;

    ChannelTray(Context c, Host host) {
        super(c);
        this.host = host;
        prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        d = c.getResources().getDisplayMetrics().density * 0.8f;
        plate = Glass.dock(16 * d);
        plate.setAlpha(235);
        setFocusable(false);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    /** Fresh data each time it opens: the library may have loaded or changed since. */
    void open(int targetCell) {
        int[] w = host.windows();
        slot = 0;
        for (int i = 0; i < w.length; i++) if (w[i] == targetCell) slot = i;
        cats.clear();
        cats.add("Favorites");
        cats.add("Recent");
        cats.add("All");
        Map<String, Integer> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<IptvPlaylist.Channel> shared = Shared.channels;
        if (shared != null) {
            for (IptvPlaylist.Channel ch : shared) groups.put(ch.group, 1);
        }
        cats.addAll(groups.keySet());
        // Start where the window already is, so a channel in use is found at once.
        cat = 2;
        sel = 0;
        row = ROW_CHANNEL;
        held = false;
        rebuild();
        String cur = host.idOf(targetCell);
        for (int i = 0; cur != null && i < list.size(); i++) {
            if (idOf(list.get(i)).equals(cur)) sel = i;
        }
        host.target(targetCell);
        invalidate();
    }

    /** Re-read Shared.channels after Multiview finishes loading the library. */
    void refresh() {
        loading = false;
        int[] w = host.windows();
        if (w.length == 0) return;
        open(w[Math.max(0, Math.min(slot, w.length - 1))]);
    }

    void setLoading(boolean on) {
        loading = on;
        invalidate();
    }

    private static String idOf(IptvPlaylist.Channel ch) {
        return MultiviewActivity.IPTV_PREFIX + ch.url + "|" + ch.name;
    }

    private static String keyOf(IptvPlaylist.Channel ch) {
        return ch.url + "|" + ch.name;
    }

    private List<String> stored(String pref) {
        List<String> out = new ArrayList<>();
        for (String line : prefs.getString(pref, "").split("\n")) if (!line.isEmpty()) out.add(line);
        return out;
    }

    private void rebuild() {
        list.clear();
        numbers.clear();
        String name = cats.get(cat);
        List<String> favs = stored("ch_favs");
        List<String> recents = stored("ch_recents");
        List<IptvPlaylist.Channel> all = Shared.channels;
        if (all == null) all = Collections.emptyList();
        if (name.equals("Recent")) {
            for (String key : recents) {
                for (int i = 0; i < all.size(); i++) {
                    if (keyOf(all.get(i)).equals(key)) {
                        list.add(all.get(i));
                        numbers.add(i + 1);
                        break;
                    }
                }
            }
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            IptvPlaylist.Channel ch = all.get(i);
            boolean keep;
            if (name.equals("Favorites")) keep = favs.contains(keyOf(ch));
            else if (name.equals("All")) keep = true;
            else keep = ch.group.equals(name);
            if (keep) {
                list.add(ch);
                numbers.add(i + 1);
            }
        }
    }

    private void toggleFavorite() {
        if (sel >= list.size()) return;
        String key = keyOf(list.get(sel));
        List<String> favs = stored("ch_favs");
        if (!favs.remove(key)) favs.add(key);
        prefs.edit().putString("ch_favs", TextUtils.join("\n", favs)).apply();
        if (cats.get(cat).equals("Favorites")) {
            rebuild();
            sel = Math.max(0, Math.min(sel, list.size() - 1));
        }
        invalidate();
    }

    private void retarget(int delta) {
        int[] w = host.windows();
        if (w.length == 0) return;
        slot = (slot + delta + w.length) % w.length;
        host.target(w[slot]);
        invalidate();
    }

    /** Handles every key while the tray is open. Always consumes. */
    boolean onKey(KeyEvent e) {
        int code = NavKeys.navCode(e.getKeyCode(), false);
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    held = false;
                } else if (!held && e.getEventTime() - e.getDownTime() >= HOLD_MS) {
                    held = true;
                    if (row == ROW_CHANNEL) toggleFavorite();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                boolean wasHeld = held;
                held = false;
                if (!wasHeld && row == ROW_CHANNEL && sel < list.size()) {
                    int[] w = host.windows();
                    if (slot < w.length) host.load(w[slot], idOf(list.get(sel)));
                    invalidate();
                }
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                host.close();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row > ROW_WINDOW) row--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < ROW_CHANNEL) row++;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1);
                break;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                row = ROW_CHANNEL;
                move(5);
                break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
                row = ROW_CHANNEL;
                move(-5);
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void move(int delta) {
        if (row == ROW_WINDOW) {
            retarget(delta);
        } else if (row == ROW_CATEGORY) {
            int next = Math.max(0, Math.min(cats.size() - 1, cat + delta));
            if (next != cat) {
                cat = next;
                sel = 0;
                rebuild();
            }
        } else if (!list.isEmpty()) {
            sel = Math.max(0, Math.min(list.size() - 1, sel + delta));
        }
    }

    // ------------------------------------------------------------------ drawing

    private int ink() {
        return XmbView.iconColor;
    }

    private int withAlpha(int color, float a) {
        return (color & 0x00FFFFFF) | ((int) (255 * a) << 24);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        plate.setBounds(0, 0, (int) w, (int) (h + 40 * d));
        plate.draw(c);
        float pad = 32 * d;
        float y = 18 * d;

        // Row 0: where the channel goes.
        float rowH = 38 * d;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(17 * d);
        text.setColor(withAlpha(ink(), row == ROW_WINDOW ? 0.95f : 0.55f));
        c.drawText("Load into", pad, y + rowH / 2 + 6 * d, text);
        float x = pad + text.measureText("Load into") + 20 * d;
        int[] wins = host.windows();
        for (int i = 0; i < wins.length; i++) {
            boolean on = i == slot;
            rect.set(x, y + 3 * d, x + 44 * d, y + rowH - 3 * d);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(on ? Glass.accentStroke(row == ROW_WINDOW ? 0xCC000000 : 0x77000000) : withAlpha(ink(), 0.10f));
            c.drawRoundRect(rect, 10 * d, 10 * d, fill);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(withAlpha(ink() == 0xFF000000 || !on ? ink() : 0xFFFFFFFF, on ? 1f : 0.7f));
            c.drawText(String.valueOf(i + 1), rect.centerX(), rect.centerY() + 6 * d, text);
            x += 52 * d;
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(withAlpha(ink(), 0.55f));
        String here = slot < wins.length ? nowIn(wins[slot]) : "";
        c.drawText(here, x + 12 * d, y + rowH / 2 + 6 * d, text);
        y += rowH + 8 * d;

        // Row 1: categories.
        float chipsY = y;
        float cx = pad;
        float firstShown = categoryOffset(w - 2 * pad);
        cx -= firstShown;
        c.save();
        c.clipRect(pad - 8 * d, y, w - pad + 8 * d, y + rowH);
        for (int i = 0; i < cats.size(); i++) {
            String name = cats.get(i);
            text.setTextSize(17 * d);
            float cw = text.measureText(name) + 36 * d;
            boolean on = i == cat;
            rect.set(cx, chipsY + 3 * d, cx + cw, chipsY + rowH - 3 * d);
            if (on) {
                fill.setColor(row == ROW_CATEGORY ? Glass.accentStroke(0xCC000000) : withAlpha(ink(), 0.20f));
                c.drawRoundRect(rect, 18 * d, 18 * d, fill);
            }
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(withAlpha(on && row == ROW_CATEGORY && ink() != 0xFF000000 ? 0xFFFFFFFF : ink(), on ? 1f : 0.6f));
            c.drawText(name, rect.centerX(), rect.centerY() + 6 * d, text);
            cx += cw + 6 * d;
        }
        c.restore();
        y += rowH + 12 * d;

        // Row 2: channel cards.
        float footer = 40 * d;
        float cardH = h - y - footer - 6 * d;
        if (list.isEmpty()) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(19 * d);
            text.setColor(withAlpha(ink(), 0.6f));
            boolean none = Shared.channels == null || Shared.channels.isEmpty();
            String msg = none
                    ? (loading ? "Loading channels..." : "No channels loaded yet. Set up Live TV in Settings first.")
                    : cats.get(cat).equals("Favorites") ? "No favorites yet. Hold OK on a channel to add it."
                    : "Nothing here yet.";
            c.drawText(msg, pad, y + cardH / 2, text);
        } else {
            float cardW = 230 * d;
            float gap = 14 * d;
            float scroll = Math.max(0, sel * (cardW + gap) - (w / 2 - cardW / 2));
            c.save();
            c.clipRect(0, y - 14 * d, w, y + cardH + 4 * d);
            for (int i = 0; i < list.size(); i++) {
                float left = pad + i * (cardW + gap) - scroll;
                if (left > w || left + cardW < 0) continue;
                drawCard(c, i, left, y, cardW, cardH);
            }
            c.restore();
            text.setTextSize(15 * d);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(withAlpha(ink(), 0.45f));
            c.drawText((sel + 1) + " / " + list.size(), w - pad, h - 14 * d, text);
        }

        // Footer hints.
        Hints.prepare(hintKey, hintLab, d);
        String[] keys = {"OK", "Hold OK", "◀ ▶", "▲▼", "Back"};
        String[] labs = {"Load", "Favorite", "Browse", "Rows", "Close"};
        Hints.draw(c, hintKey, hintLab, keys, labs, pad, h - 14 * d, d, 1f, ink());
    }

    private float categoryOffset(float viewW) {
        text.setTextSize(17 * d);
        float before = 0;
        float selW = 0;
        for (int i = 0; i < cats.size(); i++) {
            float cw = text.measureText(cats.get(i)) + 36 * d + 6 * d;
            if (i < cat) before += cw;
            if (i == cat) selW = cw;
        }
        return Math.max(0, before + selW - viewW);
    }

    private String nowIn(int cell) {
        String id = host.idOf(cell);
        if (id == null) return "empty";
        if (id.startsWith(MultiviewActivity.IPTV_PREFIX)) {
            int bar = id.indexOf('|');
            return bar > 0 ? id.substring(bar + 1) : "channel";
        }
        return "showing something else";
    }

    private int windowPlaying(String id) {
        int[] wins = host.windows();
        for (int i = 0; i < wins.length; i++) if (id.equals(host.idOf(wins[i]))) return i + 1;
        return 0;
    }

    private void drawCard(Canvas c, int i, float left, float top, float cw, float ch) {
        IptvPlaylist.Channel item = list.get(i);
        boolean focused = row == ROW_CHANNEL && i == sel;
        boolean chosen = i == sel;
        float lift = focused ? -8 * d : 0;
        rect.set(left, top + lift, left + cw, top + ch + lift);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withAlpha(ink(), focused ? 0.20f : chosen ? 0.12f : 0.07f));
        c.drawRoundRect(rect, 12 * d, 12 * d, fill);
        if (focused) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2 * d);
            fill.setColor(Glass.accentStroke(0xF2000000));
            c.drawRoundRect(rect, 12 * d, 12 * d, fill);
            fill.setStyle(Paint.Style.FILL);
        }
        // Monogram where a thumbnail would go.
        float thumbH = ch * 0.52f;
        rect.set(left + 10 * d, top + lift + 10 * d, left + cw - 10 * d, top + lift + 10 * d + thumbH);
        fill.setColor(withAlpha(ink(), 0.08f));
        c.drawRoundRect(rect, 8 * d, 8 * d, fill);
        String name = item.name.trim();
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.min(thumbH * 0.5f, 40 * d));
        text.setColor(withAlpha(ink(), 0.55f));
        c.drawText(initial, rect.centerX(), rect.centerY() + text.getTextSize() * 0.35f, text);

        int playing = windowPlaying(idOf(item));
        if (playing > 0) {
            text.setTextSize(13 * d);
            String badge = "In " + playing;
            float bw = text.measureText(badge) + 14 * d;
            RectF b = new RectF(rect.right - bw - 6 * d, rect.top + 6 * d, rect.right - 6 * d, rect.top + 26 * d);
            fill.setColor(Glass.accentStroke(0xDD000000));
            c.drawRoundRect(b, 10 * d, 10 * d, fill);
            text.setColor(0xFFFFFFFF);
            c.drawText(badge, b.centerX(), b.centerY() + 5 * d, text);
        }

        // Number, name, state.
        float ty = rect.bottom + 24 * d;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(15 * d);
        text.setColor(withAlpha(ink(), 0.55f));
        String num = String.valueOf(numbers.get(i));
        c.drawText(num, left + 12 * d, ty, text);
        float nx = left + 12 * d + 40 * d;
        text.setTextSize(18 * d);
        text.setColor(withAlpha(ink(), focused ? 1f : 0.85f));
        float room = left + cw - 12 * d - nx - 22 * d;
        c.drawText(TextUtils.ellipsize(name, text, room, TextUtils.TruncateAt.END).toString(), nx, ty, text);
        float dot = left + cw - 16 * d;
        if (item.status == ChannelDb.DEAD) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(16 * d);
            text.setColor(0xFFFFB84D);
            c.drawText("!", dot, ty, text);
        } else if (item.status == ChannelDb.WORKING) {
            fill.setColor(0xFF4CD964);
            c.drawCircle(dot, ty - 5 * d, 4 * d, fill);
        }
        String group = item.group;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(13 * d);
        text.setColor(withAlpha(ink(), 0.4f));
        c.drawText(TextUtils.ellipsize(group, text, cw - 24 * d, TextUtils.TruncateAt.END).toString(),
                left + 12 * d, ty + 20 * d, text);
    }
}
