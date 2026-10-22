package com.liam.homescreen;

import java.util.ArrayList;
import java.util.List;

/** What a Multiview quadrant can show, and how a source is written down as a string. */
final class Sources {
    static final String WEB = "web:";
    static final String IPTV = "iptv:";
    static final String WIDGET = "widget:";
    static final String[][] BUILT_IN_SITES = {
            {"YouTube", "https://www.youtube.com/tv"},
            {"Twitch", "https://www.twitch.tv"},
    };
    static final String[] WIDGETS = {"clock", "weather", "calendar"};
    static final String[] WIDGET_NAMES = {"Clock", "Weather", "Calendar"};

    private Sources() {
    }

    static boolean isWeb(String id) {
        return id != null && id.startsWith(WEB);
    }

    static boolean isIptv(String id) {
        return id != null && id.startsWith(IPTV);
    }

    static boolean isWidget(String id) {
        return id != null && id.startsWith(WIDGET);
    }

    /** Built-in sites plus the pages saved in Settings (a line is an address, or Name|address). */
    static List<String[]> sites(Store store) {
        List<String[]> all = new ArrayList<>();
        for (String[] s : BUILT_IN_SITES) all.add(s);
        for (String line : store.lines("web_sites")) {
            String name;
            String url;
            int bar = line.indexOf('|');
            if (bar > 0) {
                name = line.substring(0, bar).trim();
                url = line.substring(bar + 1).trim();
            } else {
                url = line;
                name = hostOf(line);
            }
            if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) url = "https://" + url;
            all.add(new String[]{name, url});
        }
        return all;
    }

    static String hostOf(String address) {
        return address.replaceFirst("^(https?://)?(www\\.)?", "").replaceFirst("[/?#].*$", "");
    }

    static String siteUrl(Store store, String id) {
        for (String[] s : sites(store)) if ((WEB + s[0]).equals(id)) return s[1];
        return null;
    }

    static String iptvUrl(String id) {
        int bar = id.indexOf('|');
        return id.substring(IPTV.length(), bar > 0 ? bar : id.length());
    }

    static String iptvName(String id) {
        int bar = id.indexOf('|');
        return bar > 0 ? id.substring(bar + 1) : "Channel";
    }

    static String widgetName(String id) {
        String w = id.substring(WIDGET.length());
        for (int i = 0; i < WIDGETS.length; i++) if (WIDGETS[i].equals(w)) return WIDGET_NAMES[i];
        return w;
    }
}
