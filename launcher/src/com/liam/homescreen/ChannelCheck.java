package com.liam.homescreen;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background channel checker. A channel passes if its stream answers with real media (or, for HLS, a playlist
 * whose first segment answers). Non-HTTP streams can't be probed from here and are left alone. A channel is only
 * marked dead after two failed probes.
 */
final class ChannelCheck {
    /** Re-check channels that last passed more than this long ago. */
    static final long STALE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int THREADS = 10;
    private static final int MAX_HLS_DEPTH = 3;

    interface Listener {
        /** Called from a worker thread, throttled. */
        void progress(int done, int total);

        void finished(int working, int dead, boolean cancelled);
    }

    private static volatile boolean running;
    private static volatile boolean cancel;
    private static volatile int done;
    private static volatile int total;

    private ChannelCheck() {
    }

    static boolean running() {
        return running;
    }

    static int done() {
        return done;
    }

    static int total() {
        return total;
    }

    static void cancel() {
        cancel = true;
    }

    /** Check everything unchecked or stale. Returns false if a run is already going. */
    static synchronized boolean start(final Context ctx, final Listener l) {
        if (running) return false;
        running = true;
        cancel = false;
        done = 0;
        total = 0;
        final ChannelDb db = ChannelDb.get(ctx);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runAll(db, l);
            }
        }, "channel-check");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void runAll(final ChannelDb db, final Listener l) {
        final List<IptvPlaylist.Channel> due = db.dueForCheck(System.currentTimeMillis() - STALE_MS);
        total = due.size();
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger working = new AtomicInteger();
        final AtomicInteger dead = new AtomicInteger();
        Thread[] pool = new Thread[THREADS];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    int at;
                    while (!cancel && (at = next.getAndIncrement()) < due.size()) {
                        IptvPlaylist.Channel ch = due.get(at);
                        boolean ok = probe(ch.url) || (!cancel && sleepThenProbe(ch.url));
                        db.setResult(ch.id, ok);
                        (ok ? working : dead).incrementAndGet();
                        int d = ++done;
                        if (l != null && (d % 25 == 0 || d == total)) l.progress(d, total);
                    }
                }
            }, "channel-check-" + i);
            pool[i].setDaemon(true);
            pool[i].start();
        }
        for (Thread t : pool) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        boolean wasCancelled = cancel;
        running = false;
        if (l != null) l.finished(working.get(), dead.get(), wasCancelled);
    }

    private static boolean sleepThenProbe(String url) {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
        return probe(url);
    }

    /** True if the stream looks playable. Blocking. */
    static boolean probe(String url) {
        String low = url.toLowerCase();
        if (!low.startsWith("http://") && !low.startsWith("https://")) return true; // can't verify
        try {
            return probeHttp(url, 0);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeHttp(String url, int depth) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) return false;
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            InputStream in = c.getInputStream();
            byte[] head = readUpTo(in, 4096);
            if (head.length == 0) return false;
            String text = new String(head, "UTF-8").trim();
            if (text.startsWith("﻿")) text = text.substring(1);
            if (text.startsWith("#EXTM3U")) {
                if (depth >= MAX_HLS_DEPTH) return true;
                String all = text;
                if (head.length >= 4096) all = new String(concat(head, readUpTo(in, 60000)), "UTF-8");
                String target = hlsTarget(all);
                if (target == null) return false;
                return probeHttp(new URL(new URL(c.getURL().toString()), target).toString(), depth + 1);
            }
            if (type.contains("text/html") || type.contains("json") || text.startsWith("<")) return false;
            return true;
        } finally {
            c.disconnect();
        }
    }

    /** First variant of a master playlist, or the newest segment of a media playlist. */
    private static String hlsTarget(String playlist) {
        boolean master = playlist.contains("#EXT-X-STREAM-INF");
        String pick = null;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            pick = line;
            if (master) break;
        }
        return pick;
    }

    private static HttpURLConnection open(String url) throws Exception {
        for (int hops = 0; hops < 6; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) HomeLauncher/1.0");
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect without location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            return c;
        }
        throw new Exception("too many redirects");
    }

    private static byte[] readUpTo(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (out.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
