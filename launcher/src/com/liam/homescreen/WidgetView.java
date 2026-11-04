package com.liam.homescreen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A card drawn in the XMB's style: the active theme's colours, a soft glass sheen, white (or black)
 * text in the same fonts and shadows, and the same line icons. Shows a clock, the weather, the
 * calendar, or a simple message. Used by Multiview and the idle ambient screen.
 */
final class WidgetView extends View {
    private String kind = "message";
    private String title = "";
    private String detail = "";
    private boolean h24;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint light = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint medium = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint regular = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;

    WidgetView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        light.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        medium.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        regular.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    void showMessage(String t, String s) {
        kind = "message";
        title = t == null ? "" : t;
        detail = s == null ? "" : s;
        invalidate();
    }

    /** "clock", "weather" or "calendar". */
    void showWidget(String k) {
        kind = k;
        invalidate();
    }

    void setClock24(boolean on) {
        h24 = on;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        int ink = XmbView.iconColor;
        boolean dark = ink == 0xFF000000;

        // Theme-tinted panel (XMB card, not frosted glass).
        Glass.field(c, w, h, paint);

        for (TextPaint p : new TextPaint[]{light, medium, regular}) {
            p.setColor(ink);
            if (XmbView.drawShadows) {
                p.setShadowLayer(2f * d, 0f, d, dark ? 0x28FFFFFF : 0x55000000);
            } else {
                p.clearShadowLayer();
            }
        }
        if (kind.equals("clock")) drawClock(c, w, h);
        else if (kind.equals("weather")) drawWeather(c, w, h, ink);
        else if (kind.equals("calendar")) drawCalendar(c, w, h, ink);
        else drawMessage(c, w, h);
    }

    private static void fit(TextPaint p, String s, float size, float maxW) {
        p.setTextSize(size);
        float width = p.measureText(s);
        if (width > maxW) p.setTextSize(size * maxW / width);
    }

    private void drawMessage(Canvas c, float w, float h) {
        medium.setTextAlign(Paint.Align.CENTER);
        regular.setTextAlign(Paint.Align.CENTER);
        fit(medium, title, h * 0.13f, w * 0.88f);
        c.drawText(title, w / 2, h * (detail.isEmpty() ? 0.56f : 0.47f), medium);
        if (!detail.isEmpty()) {
            regular.setAlpha(210);
            fit(regular, detail, h * 0.085f, w * 0.88f);
            c.drawText(detail, w / 2, h * 0.62f, regular);
        }
    }

    private void drawClock(Canvas c, float w, float h) {
        long now = System.currentTimeMillis();
        String time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(now));
        String date = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(new Date(now));
        light.setTextAlign(Paint.Align.CENTER);
        regular.setTextAlign(Paint.Align.CENTER);
        fit(light, time, h * 0.34f, w * 0.88f);
        c.drawText(time, w / 2, h * 0.56f, light);
        regular.setAlpha(220);
        fit(regular, date, h * 0.09f, w * 0.88f);
        c.drawText(date, w / 2, h * 0.73f, regular);
    }

    private void drawWeather(Canvas c, float w, float h, int ink) {
        WeatherClient.Report r = Shared.weather;
        if (r == null) {
            drawMessageText(c, w, h, "Weather isn't set up", "Settings > Weather");
            return;
        }
        paint.reset();
        paint.setAntiAlias(true);
        XmbView.drawGlyph(c, WeatherClient.glyph(r.code), w * 0.21f, h * 0.4f, h * 0.42f, ink, 255, paint, XmbView.drawShadows);
        float x = w * 0.40f;
        light.setTextAlign(Paint.Align.LEFT);
        medium.setTextAlign(Paint.Align.LEFT);
        regular.setTextAlign(Paint.Align.LEFT);
        String temp = r.temp + "°" + (r.fahrenheit ? "F" : "C");
        fit(light, temp, h * 0.30f, w * 0.56f);
        c.drawText(temp, x, h * 0.42f, light);
        String place = r.place + "  ·  " + WeatherClient.describe(r.code);
        fit(medium, place, h * 0.085f, w * 0.57f);
        c.drawText(place, x, h * 0.55f, medium);
        regular.setAlpha(225);
        float y = h * 0.69f;
        for (int i = 0; i < Math.min(2, r.days.size()); i++) {
            WeatherClient.Day day = r.days.get(i);
            String line = day.label + "   " + day.hi + "° / " + day.lo + "°";
            fit(regular, line, h * 0.075f, w * 0.57f);
            c.drawText(line, x, y, regular);
            y += h * 0.11f;
        }
    }

    private void drawMessageText(Canvas c, float w, float h, String t, String s) {
        medium.setTextAlign(Paint.Align.CENTER);
        regular.setTextAlign(Paint.Align.CENTER);
        fit(medium, t, h * 0.11f, w * 0.88f);
        c.drawText(t, w / 2, h * 0.47f, medium);
        regular.setAlpha(210);
        fit(regular, s, h * 0.08f, w * 0.88f);
        c.drawText(s, w / 2, h * 0.6f, regular);
    }

    private void drawCalendar(Canvas c, float w, float h, int ink) {
        List<IcsCalendar.Event> events = Shared.events;
        long now = System.currentTimeMillis();
        SimpleDateFormat time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.getDefault());
        SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
        paint.reset();
        paint.setAntiAlias(true);
        XmbView.drawGlyph(c, XmbView.G_CAL, w * 0.09f, h * 0.15f, h * 0.15f, ink, 255, paint, XmbView.drawShadows);
        medium.setTextAlign(Paint.Align.LEFT);
        regular.setTextAlign(Paint.Align.LEFT);
        medium.setTextSize(h * 0.095f);
        c.drawText("Coming up", w * 0.17f, h * 0.185f, medium);
        float y = h * 0.34f;
        int shown = 0;
        for (IcsCalendar.Event e : events) {
            if (e.end < now && !e.allDay) continue;
            if (shown++ >= 3) break;
            String name = e.title.isEmpty() ? "(No title)" : e.title;
            medium.setTextSize(h * 0.085f);
            c.drawText(TextUtils.ellipsize(name, medium, w * 0.84f, TextUtils.TruncateAt.END).toString(), w * 0.07f, y, medium);
            regular.setAlpha(210);
            regular.setTextSize(h * 0.068f);
            String when = day.format(new Date(e.start)) + (e.allDay ? "  ·  All day" : "  ·  " + time.format(new Date(e.start)));
            c.drawText(when, w * 0.07f, y + h * 0.075f, regular);
            y += h * 0.215f;
        }
        if (shown == 0) {
            regular.setAlpha(220);
            regular.setTextSize(h * 0.085f);
            c.drawText("Nothing coming up, or no calendar set up", w * 0.07f, h * 0.45f, regular);
        }
    }
}
