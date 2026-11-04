package com.liam.homescreen;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pulls configured IPTV playlists, follows nested channel-list links, and merges new live channels
 * into {@link ChannelDb}. Uses conditional HTTP so frequent polls stay cheap.
 */
final class IptvFetch {
    private static final String TAG = "IptvFetch";
    private static final int MAX_NESTED = 24;
    private static final int MAX_DEPTH = 2;

    static final class Result {
        int added;
        int updated;
        int removed;
        int sources;
        int nested;
        boolean anyFetched;
        String error;

        boolean foundNew() {
            return added > 0;
        }
    }

    private IptvFetch() {
    }

    /**
     * Sync every root playlist (and discovered nested lists) into the library.
     * Nested lists are stored under their own source URL so future polls can update them independently.
     */
    static Result sync(ChannelDb db, Store store, List<String> roots) {
        Result out = new Result();
        if (roots == null || roots.isEmpty()) {
            db.retainSources(new ArrayList<String>());
            db.scrubNonLive();
            return out;
        }

        Set<String> rootSet = new LinkedHashSet<>();
        ArrayDeque<Job> queue = new ArrayDeque<>();
        for (String root : roots) {
            if (root == null || root.trim().isEmpty()) continue;
            String u = root.trim();
            rootSet.add(u);
            queue.add(new Job(u, 0));
        }
        // Resume nested lists found on earlier syncs so 304 roots don't drop discovered channels.
        for (String prev : store.lines("iptv_discovered")) {
            if (prev == null || prev.isEmpty() || rootSet.contains(prev)) continue;
            queue.add(new Job(prev, 1));
        }

        Set<String> keepSources = new LinkedHashSet<>();
        Set<String> seenUrl = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            Job job = queue.removeFirst();
            if (!seenUrl.add(job.url)) continue;
            if (job.depth > MAX_DEPTH) continue;
            if (job.depth > 0 && keepSources.size() - rootSet.size() >= MAX_NESTED) continue;

            keepSources.add(job.url);
            try {
                Pull pull = pullPlaylist(store, job.url);
                if (pull.notModified) {
                    out.sources++;
                    if (job.depth > 0) out.nested++;
                    continue;
                }
                out.anyFetched = true;
                out.sources++;
                if (job.depth > 0) out.nested++;

                IptvPlaylist.ParseResult parsed = IptvPlaylist.parseFull(pull.body);
                ChannelDb.ImportResult imp = db.importSource(job.url, parsed.channels);
                out.added += imp.added;
                out.updated += imp.updated;
                out.removed += imp.removed;

                if (job.depth < MAX_DEPTH) {
                    for (String nested : parsed.nestedPlaylists) {
                        if (seenUrl.contains(nested) || rootSet.contains(nested)) continue;
                        if (keepSources.size() - rootSet.size() + queue.size() >= MAX_NESTED) break;
                        queue.add(new Job(nested, job.depth + 1));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "fetch failed: " + job.url, e);
                if (out.error == null) out.error = e.getMessage();
                // Keep prior rows for this source when the network blips.
                keepSources.add(job.url);
            }
        }

        List<String> discovered = new ArrayList<>();
        for (String s : keepSources) if (!rootSet.contains(s)) discovered.add(s);
        store.putLines("iptv_discovered", discovered);
        db.retainSources(new ArrayList<>(keepSources));
        db.scrubNonLive();
        store.put("iptv_last_sync", String.valueOf(System.currentTimeMillis()));
        return out;
    }

    private static final class Job {
        final String url;
        final int depth;

        Job(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }

    private static final class Pull {
        final String body;
        final boolean notModified;

        Pull(String body, boolean notModified) {
            this.body = body;
            this.notModified = notModified;
        }
    }

    private static Pull pullPlaylist(Store store, String url) throws Exception {
        String metaKey = metaKey(url);
        String raw = store.get(metaKey, "");
        String etag = null;
        long modified = 0;
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split("\\|", 3);
            if (parts.length >= 1 && !parts[0].isEmpty()) etag = parts[0];
            if (parts.length >= 2) {
                try {
                    modified = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Net.Response r = Net.getConditional(url, etag, modified);
        if (r.notModified) return new Pull(null, true);
        String nextEtag = r.etag == null ? "" : r.etag;
        long nextMod = r.lastModified > 0 ? r.lastModified : System.currentTimeMillis();
        store.put(metaKey, nextEtag + "|" + nextMod + "|" + System.currentTimeMillis());
        if (r.body == null || r.body.isEmpty()) throw new Exception("Empty playlist");
        return new Pull(r.body, false);
    }

    private static String metaKey(String url) {
        return "iptv_etag_" + Integer.toHexString(url.hashCode());
    }

    /** Human summary for a notice chip. */
    static String summary(Result r) {
        if (r == null) return "";
        if (r.foundNew()) {
            String s = r.added == 1 ? "1 new channel" : r.added + " new channels";
            if (r.nested > 0) s += "  ·  " + r.nested + " lists";
            return "Found " + s;
        }
        if (r.error != null && r.sources == 0) return "Couldn't refresh channels";
        if (r.anyFetched) return "Channels up to date";
        return "Channels checked";
    }
}
