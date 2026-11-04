package com.liam.homescreen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Current weather and a short forecast from Open-Meteo (free, no account or key needed). */
final class WeatherClient {
    static final class Day {
        String label;
        int code;
        int hi;
        int lo;
    }

    static final class Report {
        String place;
        int temp;
        int feels;
        int code;
        int wind;
        boolean fahrenheit;
        final List<Day> days = new ArrayList<>();
    }

    private WeatherClient() {
    }

    static Report fetch(String cityQuery, boolean fahrenheit) throws Exception {
        String[] parts = cityQuery.split(",", 2);
        String name = parts[0].trim();
        String hint = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
        JSONObject geo = new JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(name, "UTF-8") + "&count=8&language=en&format=json"));
        JSONArray results = geo.optJSONArray("results");
        if (results == null || results.length() == 0) throw new Exception("City not found: " + cityQuery);
        JSONObject pick = results.getJSONObject(0);
        if (!hint.isEmpty()) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String hay = (r.optString("admin1") + " " + r.optString("country") + " "
                        + r.optString("country_code")).toLowerCase(Locale.ROOT);
                if (hay.contains(hint)) {
                    pick = r;
                    break;
                }
            }
        }
        JSONObject w = new JSONObject(Net.get("https://api.open-meteo.com/v1/forecast?latitude="
                + pick.getDouble("latitude") + "&longitude=" + pick.getDouble("longitude")
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=" + (fahrenheit ? "fahrenheit" : "celsius")
                + "&wind_speed_unit=" + (fahrenheit ? "mph" : "kmh")
                + "&timezone=auto&forecast_days=6"));
        Report r = new Report();
        r.fahrenheit = fahrenheit;
        r.place = pick.optString("name");
        JSONObject cur = w.getJSONObject("current");
        r.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        r.feels = (int) Math.round(cur.getDouble("apparent_temperature"));
        r.code = cur.getInt("weather_code");
        r.wind = (int) Math.round(cur.getDouble("wind_speed_10m"));
        JSONObject daily = w.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat out = new SimpleDateFormat("EEEE", Locale.getDefault());
        for (int i = 0; i < dates.length(); i++) {
            Day d = new Day();
            Calendar cal = Calendar.getInstance();
            cal.setTime(in.parse(dates.getString(i)));
            d.label = i == 0 ? "Today" : i == 1 ? "Tomorrow" : out.format(cal.getTime());
            d.code = daily.getJSONArray("weather_code").getInt(i);
            d.hi = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i));
            d.lo = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i));
            r.days.add(d);
        }
        return r;
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    static int glyph(int code) {
        if (code <= 1) return XmbView.G_SUN;
        if (code <= 3) return XmbView.G_CLOUD;
        if (code == 45 || code == 48) return XmbView.G_FOG;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return XmbView.G_RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return XmbView.G_SNOW;
        if (code >= 95) return XmbView.G_STORM;
        return XmbView.G_CLOUD;
    }
}
