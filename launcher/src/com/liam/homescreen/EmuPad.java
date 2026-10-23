package com.liam.homescreen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

/**
 * On-screen D-pad for the Android emulator / desktop, where the host keyboard often never
 * reaches the guest. Hidden on real devices.
 */
final class EmuPad extends View {
    interface Listener {
        void onPad(int keyCode);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;
    private Listener listener;
    private int pressed = -1;

    EmuPad(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(false);
    }

    void setListener(Listener l) {
        listener = l;
    }

    static boolean needed() {
        String fp = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String prod = Build.PRODUCT == null ? "" : Build.PRODUCT;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        return fp.contains("generic") || fp.contains("emulator") || prod.contains("sdk")
                || prod.contains("emulator") || model.contains("sdk") || model.contains("Emulator")
                || "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // Extra room on the right for a separate Back chip beside the pad.
        int pad = Math.round(152 * d);
        int back = Math.round(60 * d);
        setMeasuredDimension(pad + back + Math.round(12 * d), pad);
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = Math.min(getHeight(), 152 * d);
        float cx = pad / 2f;
        float cy = getHeight() / 2f;
        float r = pad * 0.42f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x88000000);
        c.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, 1.5f * d));
        paint.setColor(0x99FFFFFF);
        c.drawCircle(cx, cy, r, paint);

        String[] labels = {"▲", "◀", "●", "▶", "▼"};
        float[][] pos = {
                {cx, cy - r * 0.52f},
                {cx - r * 0.52f, cy},
                {cx, cy},
                {cx + r * 0.52f, cy},
                {cx, cy + r * 0.52f},
        };
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(17 * d);
        for (int i = 0; i < labels.length; i++) {
            paint.setColor(pressed == i ? 0xFFFFFFFF : 0xDDFFFFFF);
            c.drawText(labels[i], pos[i][0], pos[i][1] + 6 * d, paint);
        }

        // Separate Back chip (Superdesign: 60×44, quieter plate beside the pad).
        float bx = pad + 12 * d;
        float by = cy - 22 * d;
        float bw = 60 * d;
        float bh = 44 * d;
        rect.set(bx, by, bx + bw, by + bh);
        paint.setColor(pressed == 5 ? 0xAAFFFFFF : 0x80000000);
        c.drawRoundRect(rect, 12 * d, 12 * d, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, d));
        paint.setColor(0x99FFFFFF);
        c.drawRoundRect(rect, 12 * d, 12 * d, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed == 5 ? 0xFF000000 : 0xEEFFFFFF);
        paint.setTextSize(14 * d);
        c.drawText("Back", bx + bw / 2f, cy + 5 * d, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int hit = hit(e.getX(), e.getY());
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (pressed != hit) {
                pressed = hit;
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && hit >= 0 && listener != null) {
                int[] codes = {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_BACK,
                };
                listener.onPad(codes[hit]);
            }
            pressed = -1;
            invalidate();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private int hit(float x, float y) {
        float pad = Math.min(getHeight(), 152 * d);
        float cx = pad / 2f;
        float cy = getHeight() / 2f;
        float r = pad * 0.42f;

        float bx = pad + 12 * d;
        float by = cy - 22 * d;
        float bw = 60 * d;
        float bh = 44 * d;
        if (x >= bx && x <= bx + bw && y >= by && y <= by + bh) return 5;

        float dx = x - cx;
        float dy = y - cy;
        if (dist(x, y, cx, cy) > r) return -1;
        if (dist(x, y, cx, cy) < 22 * d) return 2;
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx < 0 ? 1 : 3;
        }
        return dy < 0 ? 0 : 4;
    }

    private static float dist(float x, float y, float ax, float ay) {
        float dx = x - ax;
        float dy = y - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
