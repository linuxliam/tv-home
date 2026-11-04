package com.liam.homescreen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.util.List;

/** Full screen IPTV player. Up/Down change channel, Guide opens the TV guide, digits jump, media keys pause. */
public class PlayerActivity extends Activity {
    // Set by the launcher before starting this activity.
    static List<IptvPlaylist.Channel> channels;
    static int startIndex;

    private VideoView video;
    private ChannelBanner info;
    private StatusLabel status;
    private HintBar hints;
    private int index;
    private boolean paused;
    /** True while Guide is open on top — do not finish in onStop. */
    private boolean leavingForGuide;
    private long lastInput = SystemClock.uptimeMillis();
    private final StringBuilder digits = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tickHints = new Runnable() {
        @Override
        public void run() {
            if (hints != null) hints.invalidate();
            ui.postDelayed(this, 200);
        }
    };
    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            info.animate().alpha(0f).setDuration(280).withEndAction(new Runnable() {
                @Override
                public void run() {
                    info.setVisibility(View.GONE);
                    info.setAlpha(1f);
                }
            }).start();
        }
    };
    private final Runnable commitDigits = new Runnable() {
        @Override
        public void run() {
            if (digits.length() == 0 || channels == null || channels.isEmpty()) {
                digits.setLength(0);
                return;
            }
            try {
                int n = Integer.parseInt(digits.toString());
                if (n >= 1 && n <= channels.size()) {
                    index = n - 1;
                    play();
                } else {
                    showInfo("No channel " + n, null);
                }
            } catch (NumberFormatException ignored) {
            }
            digits.setLength(0);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        index = startIndex;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        int pad = dp(28);
        info = new ChannelBanner(this);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ilp.setMargins(pad, pad, pad, pad);
        root.addView(info, ilp);

        status = new StatusLabel(this);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        hints = new HintBar(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hlp.setMargins(pad, pad, pad, pad);
        root.addView(hints, hlp);

        setContentView(root);
        ui.post(tickHints);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                status.setText("");
                paused = false;
                autoSkips = 0;
                if (channels != null && index >= 0 && index < channels.size()) {
                    DeadChannels.clear(PlayerActivity.this, channels.get(index));
                }
                mp.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (channels != null && index >= 0 && index < channels.size()) {
                    DeadChannels.mark(PlayerActivity.this, channels.get(index));
                }
                if (autoSkips++ < MAX_AUTO_SKIPS) {
                    status.setText("This channel won't play.\nSkipping to the next one...");
                    ui.removeCallbacks(skipDead);
                    ui.postDelayed(skipDead, 1200);
                } else {
                    status.setText("This channel won't play.\nPress Up or Down for another.");
                }
                return true;
            }
        });
        play();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final int MAX_AUTO_SKIPS = 3;
    private int dir = 1;
    private int autoSkips;
    private final Runnable skipDead = new Runnable() {
        @Override
        public void run() {
            step(dir);
        }
    };

    /** Move to the next channel in {@code d}, passing over channels flagged as dead. */
    private void step(int d) {
        dir = d;
        java.util.Map<String, Long> dead = DeadChannels.load(this);
        int n = channels.size();
        for (int tries = 0; tries < n; tries++) {
            index = (index + d + n) % n;
            if (!dead.containsKey(DeadChannels.key(channels.get(index)))) break;
        }
        play();
    }

    private void play() {
        if (channels == null || channels.isEmpty()) {
            finish();
            return;
        }
        index = (index + channels.size()) % channels.size();
        IptvPlaylist.Channel ch = channels.get(index);
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putString("last_channel_url", ch.url).putString("last_channel_name", ch.name).apply();
        paused = false;
        status.setText("Loading...");
        showInfo((index + 1) + "  " + ch.name, ch.group);
        video.stopPlayback();
        video.setVideoURI(Uri.parse(ch.url));
    }

    private void togglePause() {
        if (paused) {
            video.start();
            paused = false;
            status.setText("");
            showInfo((index + 1) + "  " + channels.get(index).name, "Playing");
        } else {
            video.pause();
            paused = true;
            status.setText("Paused");
            showInfo((index + 1) + "  " + channels.get(index).name, "Paused");
        }
    }

    private void showInfo(String title, String sub) {
        info.setContent(title, sub);
        info.setVisibility(View.VISIBLE);
        info.setAlpha(1f);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 4000);
    }

    private void digit(char d) {
        if (digits.length() >= 4) digits.setLength(0);
        digits.append(d);
        showInfo(digits.toString(), "Enter channel");
        ui.removeCallbacks(commitDigits);
        ui.postDelayed(commitDigits, 1500);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        lastInput = SystemClock.uptimeMillis();
        if (hints != null) hints.invalidate();
        int raw = event.getKeyCode();
        int keyCode = NavKeys.navCode(raw, false);
        // Space = play/pause on the player (not OK).
        if (raw == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePause();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                autoSkips = 0;
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                autoSkips = 0;
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showInfo((index + 1) + "  " + channels.get(index).name, channels.get(index).group);
                return true;
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_MENU:
                leavingForGuide = true;
                GuideActivity.channels = channels;
                GuideActivity.startIndex = index;
                GuideActivity.openedFromPlayer = true;
                startActivity(new Intent(this, GuideActivity.class));
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (paused) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!paused) togglePause();
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
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        leavingForGuide = false;
        // Guide may have tuned a different channel while we were paused underneath.
        if (channels != null && !channels.isEmpty()
                && GuideActivity.channels != null
                && GuideActivity.startIndex >= 0
                && GuideActivity.startIndex < GuideActivity.channels.size()) {
            IptvPlaylist.Channel gch = GuideActivity.channels.get(GuideActivity.startIndex);
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).url.equals(gch.url)) {
                    index = i;
                    break;
                }
            }
        }
        if (channels != null && !channels.isEmpty() && !paused) {
            if (!video.isPlaying()) play();
        }
        ui.removeCallbacks(tickHints);
        ui.post(tickHints);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(commitDigits);
        ui.removeCallbacks(hideInfo);
        ui.removeCallbacks(tickHints);
        video.stopPlayback();
        if (!leavingForGuide) finish();
    }

    private float hintLevel() {
        long idle = SystemClock.uptimeMillis() - lastInput;
        return Math.max(0f, Math.min(1f, (9000 - idle) / 900f));
    }

    /**
     * Channel banner — dark theme plate over the picture.
     */
    private static final class ChannelBanner extends View {
        private String title = "";
        private String sub = "";
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        ChannelBanner(Activity a) {
            super(a);
            density = a.getResources().getDisplayMetrics().density;
            setVisibility(GONE);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setColor(XmbView.iconColor);
            text.setTextSize(20f * density);
            subPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            subPaint.setColor(XmbView.iconColor);
            subPaint.setTextSize(14f * density);
            subPaint.setAlpha(180);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, density));
        }

        void setContent(String title, String sub) {
            this.title = title == null ? "" : title;
            this.sub = sub == null ? "" : sub;
            requestLayout();
            invalidate();
        }

        private float dp(float v) {
            return v * density;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float padX = dp(22);
            float padY = dp(14);
            float tw = text.measureText(title);
            float sw = sub.isEmpty() ? 0f : subPaint.measureText(sub);
            float w = Math.max(tw, sw) + padX * 2;
            float h = padY * 2 + dp(22) + (sub.isEmpty() ? 0f : dp(20));
            DisplayMetrics dm = getResources().getDisplayMetrics();
            w = Math.min(w, dm.widthPixels * 0.62f);
            setMeasuredDimension(Math.round(w), Math.round(h));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = dp(10);
            rect.set(0, 0, w, h);
            Glass.panel(c, rect, r, fill, stroke, 0xCC000000);

            float padX = dp(22);
            float maxText = Math.max(0f, w - padX * 2);
            text.setColor(XmbView.iconColor);
            text.setAlpha(255);
            float tx = padX;
            float ty = dp(14) + dp(18);
            int ink = XmbView.iconColor;
            int shadow = ink == 0xFF000000 ? 0x44FFFFFF : 0x99000000;
            text.setShadowLayer(2f * density, 0, density, shadow);
            String titleShown = TextUtils.ellipsize(title, text, maxText, TextUtils.TruncateAt.END).toString();
            c.drawText(titleShown, tx, ty, text);
            if (!sub.isEmpty()) {
                subPaint.setColor(XmbView.iconColor);
                subPaint.setAlpha(175);
                subPaint.setShadowLayer(2f * density, 0, density, shadow);
                String subShown = TextUtils.ellipsize(sub, subPaint, maxText, TextUtils.TruncateAt.END).toString();
                c.drawText(subShown, tx, ty + dp(20), subPaint);
            }
        }
    }

    /** Floating XMB-style key/label hints (matches home / guide language). */
    private final class HintBar extends View {
        private final TextPaint key = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint lab = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final String[] keys = {"▲▼", "Pause", "0–9", "Guide", "Back"};
        private final String[] labs = {"Channel", "Play", "Jump", "Browse", "Leave"};

        HintBar(Activity a) {
            super(a);
            density = a.getResources().getDisplayMetrics().density;
            key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            lab.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            Hints.prepare(key, lab, density);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            Hints.prepare(key, lab, density);
            float w = Hints.measure(key, lab, keys, labs, density);
            setMeasuredDimension(Math.round(w), Math.round(24f * density));
        }

        @Override
        protected void onDraw(Canvas c) {
            float ha = hintLevel();
            if (ha <= 0.01f) return;
            Hints.prepare(key, lab, density);
            Hints.draw(c, key, lab, keys, labs, 0, 16f * density, density, ha, XmbView.iconColor);
        }
    }

    private static final class StatusLabel extends View {
        private String text = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        StatusLabel(Activity a) {
            super(a);
            density = a.getResources().getDisplayMetrics().density;
            p.setTextSize(18f * density);
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextAlign(Paint.Align.CENTER);
        }

        void setText(String t) {
            text = t == null ? "" : t;
            setVisibility(text.isEmpty() ? GONE : VISIBLE);
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (text.isEmpty()) {
                setMeasuredDimension(0, 0);
                return;
            }
            String[] lines = text.split("\n");
            float w = 0;
            for (String line : lines) w = Math.max(w, p.measureText(line));
            setMeasuredDimension(Math.round(w + 24 * density), Math.round(lines.length * 26 * density));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (text.isEmpty()) return;
            p.setColor(XmbView.iconColor);
            int ink = XmbView.iconColor;
            int shadow = ink == 0xFF000000 ? 0x44FFFFFF : 0x99000000;
            p.setShadowLayer(2f * density, 0, density, shadow);
            String[] lines = text.split("\n");
            float y = 20 * density;
            float cx = getWidth() / 2f;
            for (String line : lines) {
                c.drawText(line, cx, y, p);
                y += 26 * density;
            }
        }
    }
}
