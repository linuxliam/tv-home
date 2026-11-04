package com.liam.homescreen;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Reads iCalendar (.ics) feeds, such as an Apple Calendar public link, and lists the events in a
 * time window. Handles daily, weekly (with weekdays), monthly and yearly repeats, COUNT, UNTIL
 * and excluded dates; more exotic repeat rules are shown on their first date only.
 */
final class IcsCalendar {
    static final class Event {
        String title = "";
        String where = "";
        long start;
        long end;
        boolean allDay;
    }

    private static final String[] DAYS = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    private IcsCalendar() {
    }

    static List<Event> load(List<String> urls, long from, long to) {
        List<Event> all = new ArrayList<>();
        for (String url : urls) {
            try {
                all.addAll(parse(Net.get(url), from, to));
            } catch (Exception ignored) {
                // one bad calendar shouldn't hide the others
            }
        }
        Collections.sort(all, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                return Long.compare(a.start, b.start);
            }
        });
        return all;
    }

    static List<Event> parse(String ics, long from, long to) {
        List<String> lines = unfold(ics);
        List<Event> out = new ArrayList<>();
        Map<String, String> props = null;
        Map<String, String> params = null;
        Set<Long> exdates = null;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                props = new HashMap<>();
                params = new HashMap<>();
                exdates = new HashSet<>();
            } else if (line.equals("END:VEVENT") && props != null) {
                expand(props, params, exdates, from, to, out);
                props = null;
            } else if (props != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String head = line.substring(0, colon);
                String value = line.substring(colon + 1);
                String name = head;
                String param = "";
                int semi = head.indexOf(';');
                if (semi >= 0) {
                    name = head.substring(0, semi);
                    param = head.substring(semi + 1);
                }
                name = name.toUpperCase();
                if (name.equals("EXDATE")) {
                    for (String v : value.split(",")) {
                        Long t = parseTime(v.trim(), tzid(param));
                        if (t != null) exdates.add(t);
                    }
                } else {
                    props.put(name, value);
                    params.put(name, param);
                }
            }
        }
        return out;
    }

    private static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        for (String raw : ics.split("\\r?\\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + raw.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }

    private static String tzid(String param) {
        for (String p : param.split(";")) if (p.toUpperCase().startsWith("TZID=")) return p.substring(5).replace("\"", "");
        return null;
    }

    /** Date-only values (all-day) have 8 characters; others are date-times, UTC when they end in Z. */
    private static Long parseTime(String v, String tzid) {
        try {
            TimeZone tz = v.endsWith("Z") ? TimeZone.getTimeZone("UTC")
                    : tzid != null ? TimeZone.getTimeZone(tzid) : TimeZone.getDefault();
            Calendar c = Calendar.getInstance(tz);
            c.clear();
            int y = Integer.parseInt(v.substring(0, 4));
            int mo = Integer.parseInt(v.substring(4, 6)) - 1;
            int d = Integer.parseInt(v.substring(6, 8));
            if (v.length() >= 15) {
                c.set(y, mo, d, Integer.parseInt(v.substring(9, 11)), Integer.parseInt(v.substring(11, 13)),
                        Integer.parseInt(v.substring(13, 15)));
            } else {
                c.set(y, mo, d);
            }
            return c.getTimeInMillis();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void expand(Map<String, String> p, Map<String, String> params, Set<Long> ex,
                               long from, long to, List<Event> out) {
        String ds = p.get("DTSTART");
        if (ds == null) return;
        String dsParam = params.get("DTSTART") == null ? "" : params.get("DTSTART");
        boolean allDay = ds.trim().length() == 8;
        String tzid = tzid(dsParam);
        Long start = parseTime(ds.trim(), tzid);
        if (start == null) return;
        long end;
        Long e = p.get("DTEND") == null ? null : parseTime(p.get("DTEND").trim(),
                tzid(params.get("DTEND") == null ? "" : params.get("DTEND")));
        if (e != null) end = e;
        else end = start + (allDay ? 86400000L : 3600000L);
        long dur = Math.max(0, end - start);
        TimeZone tz = ds.trim().endsWith("Z") ? TimeZone.getTimeZone("UTC")
                : tzid != null ? TimeZone.getTimeZone(tzid) : TimeZone.getDefault();

        String rrule = p.get("RRULE");
        if (rrule == null) {
            add(out, p, start, dur, allDay, from, to);
            return;
        }
        Map<String, String> r = new HashMap<>();
        for (String kv : rrule.split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0) r.put(kv.substring(0, eq).toUpperCase(), kv.substring(eq + 1));
        }
        String freq = r.containsKey("FREQ") ? r.get("FREQ") : "";
        int interval = r.containsKey("INTERVAL") ? Math.max(1, Integer.parseInt(r.get("INTERVAL"))) : 1;
        int count = r.containsKey("COUNT") ? Integer.parseInt(r.get("COUNT")) : Integer.MAX_VALUE;
        long until = Long.MAX_VALUE;
        if (r.containsKey("UNTIL")) {
            Long u = parseTime(r.get("UNTIL"), tzid);
            if (u != null) until = r.get("UNTIL").length() == 8 ? u + 86400000L : u;
        }
        Set<String> byDay = new HashSet<>();
        if (r.containsKey("BYDAY")) {
            for (String d : r.get("BYDAY").split(",")) byDay.add(d.replaceAll("[^A-Z]", ""));
        }
        if (!freq.equals("DAILY") && !freq.equals("WEEKLY") && !freq.equals("MONTHLY") && !freq.equals("YEARLY")) {
            add(out, p, start, dur, allDay, from, to);
            return;
        }

        Calendar first = Calendar.getInstance(tz);
        first.setTimeInMillis(start);
        Calendar day = (Calendar) first.clone();
        int produced = 0;
        for (int i = 0; i < 9000 && produced < count; i++, day.add(Calendar.DAY_OF_YEAR, 1)) {
            long occ = day.getTimeInMillis();
            if (occ > to || occ > until) break;
            long daysSince = Math.round((occ - start) / 86400000.0);
            boolean match;
            switch (freq) {
                case "DAILY":
                    match = daysSince % interval == 0;
                    break;
                case "WEEKLY": {
                    String dow = DAYS[day.get(Calendar.DAY_OF_WEEK) - 1];
                    boolean dayOk = byDay.isEmpty() ? day.get(Calendar.DAY_OF_WEEK) == first.get(Calendar.DAY_OF_WEEK)
                            : byDay.contains(dow);
                    long weeks = Math.floorDiv(daysSince + (first.get(Calendar.DAY_OF_WEEK) + 5) % 7, 7);
                    match = dayOk && weeks % interval == 0;
                    break;
                }
                case "MONTHLY": {
                    int months = (day.get(Calendar.YEAR) - first.get(Calendar.YEAR)) * 12
                            + day.get(Calendar.MONTH) - first.get(Calendar.MONTH);
                    match = day.get(Calendar.DAY_OF_MONTH) == first.get(Calendar.DAY_OF_MONTH) && months % interval == 0;
                    break;
                }
                default: { // YEARLY
                    int years = day.get(Calendar.YEAR) - first.get(Calendar.YEAR);
                    match = day.get(Calendar.MONTH) == first.get(Calendar.MONTH)
                            && day.get(Calendar.DAY_OF_MONTH) == first.get(Calendar.DAY_OF_MONTH)
                            && years % interval == 0;
                }
            }
            if (!match) continue;
            produced++;
            if (!ex.contains(occ)) add(out, p, occ, dur, allDay, from, to);
        }
    }

    private static void add(List<Event> out, Map<String, String> p, long start, long dur, boolean allDay,
                            long from, long to) {
        long end = start + dur;
        if (end < from || start > to) return;
        Event ev = new Event();
        ev.title = unescape(p.get("SUMMARY"));
        ev.where = unescape(p.get("LOCATION"));
        ev.start = start;
        ev.end = end;
        ev.allDay = allDay;
        out.add(ev);
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\n", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }
}
