package com.liam.homescreen;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Spectrum-style guide: live video in the top sky; dark XMB panel along the bottom.
 * Digits jump to a channel number (same as the player). OK tunes in-place; Play opens
 * fullscreen; Back watches the tuned channel.
 */
public class GuideActivity extends Activity {
    static List<IptvPlaylist.Channel> channels;
    static int startIndex;

    /** Bottom panel height as a fraction of the screen (video keeps the rest). */
    private static final float GUIDE_FRAC = 0.58f;

    private VideoView video;
    private GuideView guide;
    private List<IptvPlaylist.Channel> list;
    private int tuned;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        list = channels != null ? new ArrayList<>(channels)
                : (Shared.channels != null ? new ArrayList<>(Shared.channels) : new ArrayList<IptvPlaylist.Channel>());
        if (list.isEmpty()) {
            finish();
            return;
        }
        tuned = startIndex;
        if (tuned < 0 || tuned >= list.size()) {
            String last = getSharedPreferences("home", MODE_PRIVATE).getString("last_channel_url", null);
            tuned = 0;
            if (last != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).url.equals(last)) {
                        tuned = i;
                        break;
                    }
                }
            }
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;
        int screenW = dm.widthPixels;
        int guideH = Math.round(screenH * GUIDE_FRAC);
        int videoAreaH = screenH - guideH;
        int margin = dp(20);
        int vh = Math.max(dp(120), videoAreaH - margin * 2);
        int vw = vh * 16 / 9;
        if (vw > screenW - margin * 2) {
            vw = screenW - margin * 2;
            vh = vw * 9 / 16;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0C10);

        video = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(vw, vh, Gravity.TOP | Gravity.START);
        vlp.setMargins(margin, margin, 0, 0);
        root.addView(video, vlp);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setVolume(1f, 1f);
                mp.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (guide != null) guide.notice("This channel won't play");
                return true;
            }
        });

        guide = new GuideView(this, list, tuned, ui);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, guideH, Gravity.BOTTOM);
        root.addView(guide, glp);

        setContentView(root);
        guide.requestFocus();
        playTuned();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void playTuned() {
        if (tuned < 0 || tuned >= list.size()) return;
        IptvPlaylist.Channel ch = list.get(tuned);
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putString("last_channel_url", ch.url)
                .putString("last_channel_name", ch.name)
                .apply();
        video.stopPlayback();
        video.setVideoURI(Uri.parse(ch.url));
        if (guide != null) {
            guide.setTuned(tuned);
            guide.invalidate();
        }
    }

    void tuneInPlace(int index) {
        if (index < 0 || index >= list.size()) return;
        tuned = index;
        playTuned();
    }

    void watchFullscreen(int index) {
        PlayerActivity.channels = list;
        PlayerActivity.startIndex = index;
        startActivity(new Intent(this, PlayerActivity.class));
        finish();
    }

    void toggleFavorite(int index) {
        if (index < 0 || index >= list.size()) return;
        IptvPlaylist.Channel ch = list.get(index);
        String key = ch.url + "|" + ch.name;
        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        String raw = prefs.getString("ch_favs", "");
        List<String> favs = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                if (!line.isEmpty()) favs.add(line);
            }
        }
        boolean was = favs.contains(key);
        if (was) favs.remove(key);
        else favs.add(key);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < favs.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(favs.get(i));
        }
        prefs.edit().putString("ch_favs", b.toString()).apply();
        guide.setFavorites(favs);
        guide.notice(was ? ch.name + " removed from favorites" : ch.name + " added to favorites");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            watchFullscreen(tuned);
            return true;
        }
        if (guide != null && guide.onGuideKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        if (video != null) video.stopPlayback();
    }

    private static final class GuideView extends View {
        private static final int SLOTS = 5;
        private static final long SLOT_MS = 30L * 60L * 1000L;
        private static final int VISIBLE = 6;

        final List<IptvPlaylist.Channel> channels;
        private final List<String> favs = new ArrayList<>();
        private final Handler ui;
        private final StringBuilder digits = new StringBuilder();
        private int sel;
        private int tuned;
        private int slot;
        private int first;
        private long gridStart;
        private String notice;
        private long noticeAt;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint thin = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
        private final SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE d MMM", Locale.getDefault());
        private final DisplayMetrics dm;

        private final Runnable commitDigits = new Runnable() {
            @Override
            public void run() {
                if (digits.length() == 0) return;
                try {
                    int n = Integer.parseInt(digits.toString());
                    if (n >= 1 && n <= channels.size()) {
                        jumpTo(n - 1, true);
                    } else {
                        notice("No channel " + n);
                    }
                } catch (NumberFormatException ignored) {
                }
                digits.setLength(0);
                invalidate();
            }
        };

        GuideView(GuideActivity host, List<IptvPlaylist.Channel> channels, int start, Handler ui) {
            super(host);
            this.channels = channels;
            this.ui = ui;
            this.sel = Math.max(0, Math.min(start, channels.size() - 1));
            this.tuned = this.sel;
            this.dm = getResources().getDisplayMetrics();
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setWillNotDraw(false);
            snapGrid();
            loadFavorites();
            ensureVisible();
            Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
            Typeface thinFace = Typeface.create("sans-serif-thin", Typeface.NORMAL);
            if (thinFace == null) thinFace = light;
            title.setTypeface(thinFace);
            body.setTypeface(medium);
            small.setTypeface(light);
            thin.setTypeface(light);
            stroke.setStyle(Paint.Style.STROKE);
        }

        void setTuned(int index) {
            tuned = index;
        }

        private void loadFavorites() {
            favs.clear();
            String raw = ((Activity) getContext()).getSharedPreferences("home", Activity.MODE_PRIVATE)
                    .getString("ch_favs", "");
            if (raw == null || raw.isEmpty()) return;
            for (String line : raw.split("\n")) if (!line.isEmpty()) favs.add(line);
        }

        void setFavorites(List<String> next) {
            favs.clear();
            favs.addAll(next);
            invalidate();
        }

        void notice(String text) {
            notice = text;
            noticeAt = SystemClock.uptimeMillis();
            invalidate();
        }

        private void snapGrid() {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            int minute = c.get(Calendar.MINUTE);
            c.add(Calendar.MINUTE, -(minute % 30));
            gridStart = c.getTimeInMillis();
        }

        private float dp(float v) {
            return v * dm.density;
        }

        private void ensureVisible() {
            if (sel < first) first = sel;
            if (sel >= first + VISIBLE) first = sel - VISIBLE + 1;
            first = Math.max(0, Math.min(first, Math.max(0, channels.size() - VISIBLE)));
        }

        private void jumpTo(int index, boolean tune) {
            if (index < 0 || index >= channels.size()) return;
            sel = index;
            ensureVisible();
            if (tune) ((GuideActivity) getContext()).tuneInPlace(sel);
            invalidate();
        }

        private void digit(char d) {
            if (digits.length() >= 4) digits.setLength(0);
            digits.append(d);
            try {
                int n = Integer.parseInt(digits.toString());
                if (n >= 1 && n <= channels.size()) {
                    sel = n - 1;
                    ensureVisible();
                }
            } catch (NumberFormatException ignored) {
            }
            ui.removeCallbacks(commitDigits);
            ui.postDelayed(commitDigits, 1500);
            invalidate();
        }

        boolean onGuideKey(int keyCode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    if (sel > 0) {
                        sel--;
                        ensureVisible();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    if (sel < channels.size() - 1) {
                        sel++;
                        ensureVisible();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    if (slot > 0) slot--;
                    else gridStart -= SLOT_MS;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    if (slot < SLOTS - 1) slot++;
                    else gridStart += SLOT_MS;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    ((GuideActivity) getContext()).tuneInPlace(sel);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    ((GuideActivity) getContext()).watchFullscreen(sel);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_INFO:
                    ((GuideActivity) getContext()).toggleFavorite(sel);
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    sel = Math.max(0, sel - VISIBLE);
                    ensureVisible();
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    digits.setLength(0);
                    ui.removeCallbacks(commitDigits);
                    sel = Math.min(channels.size() - 1, sel + VISIBLE);
                    ensureVisible();
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_0:
                case KeyEvent.KEYCODE_1:
                case KeyEvent.KEYCODE_2:
                case KeyEvent.KEYCODE_3:
                case KeyEvent.KEYCODE_4:
                case KeyEvent.KEYCODE_5:
                case KeyEvent.KEYCODE_6:
                case KeyEvent.KEYCODE_7:
                case KeyEvent.KEYCODE_8:
                case KeyEvent.KEYCODE_9:
                    digit((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float y = event.getY();
                float infoH = dp(78);
                float header = infoH + dp(32);
                float rowH = rowHeight(infoH);
                int row = first + (int) ((y - header) / rowH);
                if (row >= 0 && row < channels.size()) {
                    sel = row;
                    ensureVisible();
                    invalidate();
                    ((GuideActivity) getContext()).tuneInPlace(sel);
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        private float rowHeight(float infoH) {
            float usable = getHeight() - infoH - dp(48) - dp(16);
            return Math.max(dp(44), usable / VISIBLE);
        }

        private void softShadow(TextPaint p, float a) {
            if (XmbView.drawShadows) {
                boolean dark = XmbView.iconColor == 0xFF000000;
                p.setShadowLayer(2f * dm.density, 0f, dm.density,
                        dark ? 0x28FFFFFF : 0x66000000);
            } else {
                p.clearShadowLayer();
            }
            p.setAlpha(Math.max(0, Math.min(255, (int) (255 * a))));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            int ink = XmbView.iconColor;
            int accent = XmbView.accentColor();
            boolean darkInk = ink == 0xFF000000;

            Glass.sheet(c, w, h, dp(40), fill, stroke);

            float pad = dp(22);
            float infoH = dp(78);
            float numW = dp(52);
            float chW = Math.min(dp(260), w * 0.28f);
            float gridLeft = pad + numW + chW;
            float gridRight = w - pad;
            float slotW = (gridRight - gridLeft) / SLOTS;
            float rowH = rowHeight(infoH);
            float top = infoH + dp(30);
            float footY = h - dp(18);

            IptvPlaylist.Channel focus = channels.get(sel);

            // —— Header ——
            title.setColor(ink);
            title.setTextSize(dp(28));
            softShadow(title, 1f);
            String head = (sel + 1) + "  " + focus.name;
            float headMax = w - pad * 2 - dp(200);
            c.drawText(TextUtils.ellipsize(head, title, headMax, TextUtils.TruncateAt.END).toString(),
                    pad, dp(38), title);

            small.setColor(ink);
            small.setTextSize(dp(14));
            softShadow(small, 0.65f);
            String sub = focus.group + "  ·  On now";
            if (sel == tuned) sub += "  ·  watching";
            c.drawText(sub, pad, dp(62), small);

            small.setTextSize(dp(14));
            softShadow(small, 0.55f);
            String clock = dayFmt.format(new Date()) + "  ·  " + timeFmt.format(new Date());
            c.drawText(clock, w - pad - small.measureText(clock), dp(38), small);

            // Number-entry chip (same language as the player).
            if (digits.length() > 0) {
                thin.setTextSize(dp(22));
                thin.setColor(ink);
                softShadow(thin, 1f);
                String entry = digits.toString();
                String entryLabel = "Enter channel";
                float ew = Math.max(thin.measureText(entry), small.measureText(entryLabel)) + dp(28);
                float eh = dp(44);
                rect.set(w - pad - ew, dp(48), w - pad, dp(48) + eh);
                Glass.panel(c, rect, dp(8), fill, stroke, 0xCC000000);
                thin.setTextAlign(Paint.Align.CENTER);
                c.drawText(entry, rect.centerX(), rect.top + dp(22), thin);
                thin.setTextAlign(Paint.Align.LEFT);
                small.setTextSize(dp(12));
                softShadow(small, 0.6f);
                small.setTextAlign(Paint.Align.CENTER);
                c.drawText(entryLabel, rect.centerX(), rect.top + dp(38), small);
                small.setTextAlign(Paint.Align.LEFT);
            }

            // Hairline under header.
            stroke.setStrokeWidth(1f);
            stroke.setColor(darkInk ? 0x22000000 : 0x28FFFFFF);
            c.drawLine(pad, infoH, w - pad, infoH, stroke);

            // —— Time axis ——
            body.setColor(ink);
            body.setTextSize(dp(13));
            softShadow(body, 0.7f);
            long now = System.currentTimeMillis();
            for (int s = 0; s < SLOTS; s++) {
                long t = gridStart + s * SLOT_MS;
                c.drawText(timeFmt.format(new Date(t)), gridLeft + s * slotW + dp(8), top - dp(12), body);
                if (now >= t && now < t + SLOT_MS) {
                    float frac = (now - t) / (float) SLOT_MS;
                    float nx = gridLeft + s * slotW + frac * slotW;
                    stroke.setStrokeWidth(dp(2));
                    stroke.setColor((accent & 0x00FFFFFF) | 0xDD000000);
                    c.drawLine(nx, top - dp(6), nx, footY - dp(8), stroke);
                }
            }
            small.setTextSize(dp(12));
            softShadow(small, 0.5f);
            c.drawText("CH", pad, top - dp(12), small);

            // —— Rows ——
            int last = Math.min(channels.size(), first + VISIBLE);
            for (int i = first; i < last; i++) {
                IptvPlaylist.Channel ch = channels.get(i);
                float y = top + (i - first) * rowH;
                boolean on = i == sel;
                boolean isTuned = i == tuned;

                if (on) {
                    rect.set(pad - dp(6), y + dp(2), gridRight + dp(2), y + rowH - dp(2));
                    fill.setStyle(Paint.Style.FILL);
                    fill.setShader(null);
                    fill.setColor(darkInk ? 0x22000000 : 0x2AFFFFFF);
                    c.drawRoundRect(rect, dp(8), dp(8), fill);
                }

                // Channel number — fixed column, XMB light type.
                thin.setTextSize(dp(16));
                thin.setColor(ink);
                softShadow(thin, isTuned || on ? 1f : 0.72f);
                String num = String.valueOf(i + 1);
                c.drawText(num, pad, y + rowH * 0.55f, thin);

                boolean starred = favs.contains(ch.url + "|" + ch.name);
                float nameX = pad + numW;
                body.setTextSize(dp(16));
                softShadow(body, 1f);
                String name = (starred ? "★ " : "") + (isTuned ? "● " : "") + ch.name;
                c.drawText(TextUtils.ellipsize(name, body, chW - dp(8), TextUtils.TruncateAt.END).toString(),
                        nameX, y + rowH * 0.42f, body);
                small.setTextSize(dp(12));
                softShadow(small, on ? 0.7f : 0.45f);
                c.drawText(TextUtils.ellipsize(ch.group, small, chW - dp(8), TextUtils.TruncateAt.END).toString(),
                        nameX, y + rowH * 0.72f, small);

                float barTop = y + dp(8);
                float barBot = y + rowH - dp(8);
                float left = gridLeft + dp(4);
                float right = gridRight - dp(2);
                for (int s = 1; s < SLOTS; s++) {
                    float tx = gridLeft + s * slotW;
                    stroke.setStrokeWidth(1f);
                    stroke.setColor(darkInk ? 0x12000000 : 0x18FFFFFF);
                    c.drawLine(tx, barTop + dp(4), tx, barBot - dp(4), stroke);
                }
                rect.set(left, barTop, right, barBot);
                fill.setStyle(Paint.Style.FILL);
                fill.setShader(null);
                if (on) {
                    fill.setColor(darkInk ? 0x2A000000 : 0x38FFFFFF);
                } else if (isTuned) {
                    fill.setColor(0x33000000);
                } else {
                    fill.setColor(darkInk ? 0x10000000 : 0x14FFFFFF);
                }
                c.drawRoundRect(rect, dp(6), dp(6), fill);

                small.setTextSize(dp(13));
                softShadow(small, on ? 1f : 0.75f);
                String label = on
                        ? (sel == tuned ? "Watching  ·  Play for fullscreen" : "On now  ·  OK to tune")
                        : ("On now  ·  " + ch.name);
                c.drawText(TextUtils.ellipsize(label, small, right - left - dp(16), TextUtils.TruncateAt.END).toString(),
                        left + dp(12), y + rowH * 0.62f, small);
            }

            // —— Footer: position left, hints right (XMB text, no pills) ——
            small.setTextSize(dp(13));
            softShadow(small, 0.5f);
            String pos = (sel + 1) + " / " + channels.size();
            c.drawText(pos, pad, footY, small);

            String hints = "OK tune   Play watch   0–9 channel   Back fullscreen";
            softShadow(small, 0.55f);
            c.drawText(hints, w - pad - small.measureText(hints), footY, small);

            if (notice != null && SystemClock.uptimeMillis() - noticeAt < 2800) {
                body.setTextSize(dp(15));
                softShadow(body, 1f);
                float nw = body.measureText(notice) + dp(28);
                float nh = dp(34);
                rect.set(w / 2f - nw / 2f, h - dp(56), w / 2f + nw / 2f, h - dp(56) + nh);
                Glass.panel(c, rect, dp(8), fill, stroke, 0xCC000000);
                body.setColor(ink);
                body.clearShadowLayer();
                body.setAlpha(255);
                c.drawText(notice, w / 2f - body.measureText(notice) / 2f, h - dp(56) + dp(23), body);
                postInvalidateOnAnimation();
            }

            if (digits.length() > 0) postInvalidateOnAnimation();
        }
    }
}
