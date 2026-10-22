package com.liam.homescreen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * PlayStation XMB–style chrome: dark theme-tinted panels, soft corners, thin light edge.
 * Not frosted / milky glass — overlays sit as quiet dark plates over the ribbon.
 */
final class Glass {
    private Glass() {
    }

    static final int ALPHA_DOCK = 0xCC000000;
    static final int ALPHA_CARD = 0xE6000000;
    static final int ALPHA_SHEET = 0xE0000000;
    static final int ALPHA_FOCUS = 0x33FFFFFF;
    static final int ALPHA_SELECTED = 0x44FFFFFF;

    static int baseColor() {
        int[] t = XmbView.currentCorners;
        return XmbView.blend(XmbView.blend(t[0], t[2], 0.45f), 0xFF000000, 0.55f);
    }

    static int veil(int alpha) {
        return (baseColor() & 0x00FFFFFF) | alpha;
    }

    static int edge() {
        return XmbView.iconColor == 0xFF000000 ? 0x33000000 : 0x55FFFFFF;
    }

    static int accentStroke(int alpha) {
        return (XmbView.accentColor() & 0x00FFFFFF) | alpha;
    }

    /** Menu dock / tip strip. */
    static GradientDrawable dock(float cornerPx) {
        int[] t = XmbView.currentCorners;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{
                        (XmbView.blend(t[0], t[1], 0.5f) & 0x00FFFFFF) | 0xD9000000,
                        (XmbView.blend(t[2], t[3], 0.5f) & 0x00FFFFFF) | 0xE6000000
                });
        g.setCornerRadius(cornerPx);
        g.setStroke(1, edge());
        return g;
    }

    /** Dialog / popup card. */
    static GradientDrawable card(float cornerPx) {
        int[] t = XmbView.currentCorners;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{
                        XmbView.blend(t[0], t[1], 0.5f) | 0xF2000000,
                        XmbView.blend(t[2], t[3], 0.5f) | 0xF2000000
                });
        g.setCornerRadius(cornerPx);
        g.setStroke(Math.max(1, Math.round(cornerPx / 14f)), 0x66FFFFFF);
        return g;
    }

    static GradientDrawable chip(float cornerPx) {
        return dock(cornerPx);
    }

    static GradientDrawable focusWash(float cornerPx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(cornerPx);
        g.setColor(XmbView.iconColor == 0xFF000000 ? 0x30000000 : 0x40FFFFFF);
        return g;
    }

    static GradientDrawable selectedWash(float cornerPx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(cornerPx);
        g.setColor(ALPHA_SELECTED);
        return g;
    }

    static StateListDrawable focusSelector(float cornerPx) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable sel = selectedWash(cornerPx);
        GradientDrawable focus = focusWash(cornerPx);
        s.addState(new int[]{android.R.attr.state_selected, android.R.attr.state_focused}, sel);
        s.addState(new int[]{android.R.attr.state_selected}, sel);
        s.addState(new int[]{android.R.attr.state_focused}, focus);
        s.addState(new int[]{android.R.attr.state_pressed}, focus);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    static void panel(Canvas c, RectF r, float radius, Paint fill, Paint stroke) {
        panel(c, r, radius, fill, stroke, ALPHA_DOCK);
    }

    /** Dark translucent plate (hints, notices, guide rows). */
    static void panel(Canvas c, RectF r, float radius, Paint fill, Paint stroke, int bodyAlpha) {
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
                (baseColor() & 0x00FFFFFF) | Math.min(0xEE000000, bodyAlpha | 0x22000000),
                (baseColor() & 0x00FFFFFF) | bodyAlpha,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, fill);
        fill.setShader(null);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        stroke.setColor(edge());
        float inset = 0.5f;
        RectF edge = new RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
        c.drawRoundRect(edge, Math.max(0f, radius - inset), Math.max(0f, radius - inset), stroke);
    }

    /** Full-width bottom guide panel — dark fade into live video, not an inset milky sheet. */
    static void sheet(Canvas c, float w, float h, float fade, Paint fill, Paint stroke) {
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(0, 0, 0, Math.max(fade, 1f),
                0x00000000, veil(ALPHA_SHEET), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, fade, fill);
        fill.setShader(null);
        fill.setColor(veil(ALPHA_SHEET));
        c.drawRect(0, fade, w, h, fill);
    }

    /** Widget plate — solid-ish theme panel. */
    static void field(Canvas c, float w, float h, Paint paint) {
        paint.setStyle(Paint.Style.FILL);
        int[] t = XmbView.currentCorners;
        paint.setShader(new LinearGradient(0, 0, w, h,
                XmbView.blend(t[0], 0xFF000000, 0.35f) | 0xF0000000,
                XmbView.blend(t[2], 0xFF000000, 0.45f) | 0xF4000000,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    static void separator(Canvas c, float left, float right, float y, Paint stroke) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        stroke.setColor(edge());
        c.drawLine(left, y, right, y, stroke);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
