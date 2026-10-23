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
    private FrostChip info;
    private StatusLabel status;
    private int index;
    private boolean paused;
    private final StringBuilder digits = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
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
        info = new FrostChip(this);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ilp.setMargins(pad, pad, pad, pad);
        root.addView(info, ilp);

        status = new StatusLabel(this);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        HintBar hints = new HintBar(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hlp.setMargins(pad, pad, pad, pad);
        root.addView(hints, hlp);

        setContentView(root);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                status.setText("");
                paused = false;
                mp.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                status.setText("This channel won't play.\nPress Up or Down for another.");
                return true;
            }
        });
        play();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
                index--;
                play();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                index++;
                play();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showInfo((index + 1) + "  " + channels.get(index).name, channels.get(index).group);
                return true;
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_MENU:
                GuideActivity.channels = channels;
                GuideActivity.startIndex = index;
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
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(commitDigits);
        ui.removeCallbacks(hideInfo);
        video.stopPlayback();
        finish();
    }

    /**
     * Channel banner — dark theme plate over the picture.
     */
    private static final class FrostChip extends View {
        private String title = "";
        private String sub = "";
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        FrostChip(Activity a) {
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
            String titleShown = TextUtils.ellipsize(title, text, maxText, TextUtils.TruncateAt.END).toString();
            c.drawText(titleShown, tx, ty, text);
            if (!sub.isEmpty()) {
                subPaint.setColor(XmbView.iconColor);
                subPaint.setAlpha(175);
                String subShown = TextUtils.ellipsize(sub, subPaint, maxText, TextUtils.TruncateAt.END).toString();
                c.drawText(subShown, tx, ty + dp(20), subPaint);
            }
        }
    }

    /** Floating XMB-style key/label hints (matches home / guide language). */
    private static final class HintBar extends View {
        private final TextPaint key = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint lab = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final String[] keys = {"▲▼", "0–9", "Guide", "Back"};
        private final String[] labs = {"Channel", "Jump", "Browse", "Leave"};

        HintBar(Activity a) {
            super(a);
            density = a.getResources().getDisplayMetrics().density;
            key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            key.setTextSize(14f * density);
            lab.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            lab.setTextSize(14f * density);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float pairGap = 6f * density;
            float groupGap = 28f * density;
            float w = 0;
            for (int i = 0; i < keys.length; i++) {
                w += key.measureText(keys[i]) + pairGap + lab.measureText(labs[i]);
                if (i < keys.length - 1) w += groupGap;
            }
            setMeasuredDimension(Math.round(w), Math.round(22f * density));
        }

        @Override
        protected void onDraw(Canvas c) {
            int ink = XmbView.iconColor;
            float pairGap = 6f * density;
            float groupGap = 28f * density;
            float x = 0;
            float y = 16f * density;
            for (int i = 0; i < keys.length; i++) {
                key.setColor(ink);
                key.setAlpha(230);
                c.drawText(keys[i], x, y, key);
                x += key.measureText(keys[i]) + pairGap;
                lab.setColor(ink);
                lab.setAlpha(140);
                c.drawText(labs[i], x, y, lab);
                x += lab.measureText(labs[i]) + groupGap;
            }
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
