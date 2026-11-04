package com.liam.homescreen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads M3U / M3U8 IPTV playlists. */
final class IptvPlaylist {
    static final class Channel {
        final String name;
        final String group;
        final String url;

        Channel(String name, String group, String url) {
            this.name = name;
            this.group = group;
            this.url = url;
        }
    }

    private static final Pattern GROUP = Pattern.compile("group-title=\"([^\"]*)\"");

    private IptvPlaylist() {
    }

    static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        String name = null;
        String group = "Other";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 ? line.substring(comma + 1).trim() : "Channel";
                Matcher m = GROUP.matcher(line);
                group = m.find() && !m.group(1).isEmpty() ? m.group(1) : "Other";
                if (group.contains(";")) group = group.substring(0, group.indexOf(';')); // first of several
            } else if (!line.isEmpty() && !line.startsWith("#") && name != null) {
                if (line.startsWith("http")) out.add(new Channel(name, group, line));
                name = null;
            }
        }
        return out;
    }
}
