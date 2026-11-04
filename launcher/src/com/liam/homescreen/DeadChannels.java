package com.liam.homescreen;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * Channels known not to play: failed a library check, or failed when tuned. A channel that plays again clears
 * its own flag. Flags live in {@link ChannelDb}; the old per-device list is still read until it expires.
 */
final class DeadChannels {
    private static final String PREF = "ch_dead";
    private static final long EXPIRY_MS = 3L * 24 * 60 * 60 * 1000;

    private DeadChannels() {
    }

    static String key(IptvPlaylist.Channel ch) {
        return ch.url + "|" + ch.name;
    }

    /** Live flags only, keyed by {@link #key}. */
    static Map<String, Long> load(Context c) {
        Map<String, Long> out = new HashMap<>();
        for (String k : ChannelDb.get(c).deadKeys()) out.put(k, System.currentTimeMillis());
        String raw = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString(PREF, "");
        if (raw == null || raw.isEmpty()) return out;
        long now = System.currentTimeMillis();
        for (String line : raw.split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            try {
                long at = Long.parseLong(line.substring(tab + 1));
                if (now - at < EXPIRY_MS) out.put(line.substring(0, tab), at);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void mark(Context c, IptvPlaylist.Channel ch) {
        ChannelDb.get(c).setResultForUrl(ch.url, false);
    }

    static void clear(Context c, IptvPlaylist.Channel ch) {
        ChannelDb.get(c).setResultForUrl(ch.url, true);
    }
}
