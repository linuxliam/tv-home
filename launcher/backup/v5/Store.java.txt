package com.liam.homescreen;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** All launcher settings, kept on the TV and edited from the Settings category (or the phone page). */
final class Store {
    static final String DEFAULT_IPTV = "https://iptv-org.github.io/iptv/countries/us.m3u";

    private final SharedPreferences p;

    Store(Context c) {
        p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    String get(String key, String def) {
        String v = p.getString(key, null);
        return v == null ? def : v;
    }

    void put(String key, String value) {
        p.edit().putString(key, value).apply();
    }

    boolean bool(String key, boolean def) {
        return p.getBoolean(key, def);
    }

    void putBool(String key, boolean value) {
        p.edit().putBoolean(key, value).apply();
    }

    boolean has(String key) {
        return p.contains(key);
    }

    void remove(String key) {
        p.edit().remove(key).apply();
    }

    /** Lists are stored one entry per line. */
    List<String> lines(String key) {
        List<String> out = new ArrayList<>();
        String v = get(key, null);
        if (v == null) return out;
        for (String s : v.split("\\r?\\n")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    void putLines(String key, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        put(key, sb.toString());
    }

    List<String> calendarUrls() {
        return lines("calendar_urls");
    }

    List<String> iptvUrls() {
        if (!has("iptv_urls")) {
            List<String> d = new ArrayList<>();
            d.add(DEFAULT_IPTV);
            return d;
        }
        return lines("iptv_urls");
    }

    String weatherCity() {
        return get("weather_city", "").trim();
    }

    boolean fahrenheit() {
        return !get("units", "f").equals("c");
    }

    /** Changes when anything that needs a refetch changes. */
    String dataFingerprint() {
        return weatherCity() + "|" + fahrenheit() + "|" + get("calendar_urls", "") + "|" + String.valueOf(iptvUrls());
    }
}
