package com.liam.homescreen;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;

/**
 * Floating XMB key/label hint pairs — shared size and spacing for home, guide, player, Multiview.
 */
final class Hints {
    /** Design-system hint size at 1× density / scale. */
    static final float SIZE = 18f;

    private Hints() {
    }

    static float measure(TextPaint key, TextPaint lab, String[] keys, String[] labs, float density) {
        float pairGap = 8f * density;
        float groupGap = 40f * density;
        float total = 0;
        for (int i = 0; i < keys.length; i++) {
            total += key.measureText(keys[i]) + pairGap + lab.measureText(labs[i]);
            if (i < keys.length - 1) total += groupGap;
        }
        return total;
    }

    /**
     * Draw floating key/label pairs left-to-right starting at {@code x}, baseline {@code y}.
     * {@code alpha} is 0..1 overall opacity.
     */
    static float draw(Canvas c, TextPaint key, TextPaint lab, String[] keys, String[] labs,
                      float x, float y, float density, float alpha, int ink) {
        float pairGap = 8f * density;
        float groupGap = 40f * density;
        float a = Math.max(0f, Math.min(1f, alpha));
        for (int i = 0; i < keys.length; i++) {
            key.setColor(ink);
            key.setAlpha((int) (230 * a));
            softShadow(key, a, ink);
            c.drawText(keys[i], x, y, key);
            x += key.measureText(keys[i]) + pairGap;
            lab.setColor(ink);
            lab.setAlpha((int) (140 * a));
            softShadow(lab, a * 0.65f, ink);
            c.drawText(labs[i], x, y, lab);
            x += lab.measureText(labs[i]) + groupGap;
        }
        return x;
    }

    static void prepare(TextPaint key, TextPaint lab, float density) {
        key.setTextSize(SIZE * density);
        lab.setTextSize(SIZE * density);
        key.setTextAlign(Paint.Align.LEFT);
        lab.setTextAlign(Paint.Align.LEFT);
    }

    private static void softShadow(TextPaint p, float strength, int ink) {
        int shadow = ink == 0xFF000000 ? 0x44FFFFFF : 0x99000000;
        float a = Math.max(0f, Math.min(1f, strength));
        int sa = ((int) (((shadow >>> 24) & 0xFF) * a)) << 24;
        p.setShadowLayer(2f, 0, 1f, (shadow & 0x00FFFFFF) | sa);
    }
}
