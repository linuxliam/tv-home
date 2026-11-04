package com.liam.homescreen;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal blocking HTTP GET. Call from a background thread. */
final class Net {
    static final class Response {
        final String body;
        final String etag;
        final long lastModified;
        final boolean notModified;

        Response(String body, String etag, long lastModified, boolean notModified) {
            this.body = body;
            this.etag = etag;
            this.lastModified = lastModified;
            this.notModified = notModified;
        }
    }

    private Net() {
    }

    static String get(String url) throws Exception {
        Response r = getConditional(url, null, 0);
        if (r.notModified || r.body == null) throw new Exception("Empty response");
        return r.body;
    }

    /**
     * GET with optional validators so playlist polls stay cheap when nothing changed.
     * {@code etag} / {@code ifModifiedSince} may be null / 0.
     */
    static Response getConditional(String url, String etag, long ifModifiedSince) throws Exception {
        if (url.startsWith("webcal://")) url = "https://" + url.substring("webcal://".length());
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(45000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "HomeLauncher/1.0");
        c.setRequestProperty("Accept", "*/*");
        if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-None-Match", etag);
        if (ifModifiedSince > 0) c.setIfModifiedSince(ifModifiedSince);
        try {
            int code = c.getResponseCode();
            String nextEtag = c.getHeaderField("ETag");
            long nextMod = c.getLastModified();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new Response(null, nextEtag != null ? nextEtag : etag, nextMod > 0 ? nextMod : ifModifiedSince, true);
            }
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new Response(out.toString("UTF-8"), nextEtag, nextMod, false);
        } finally {
            c.disconnect();
        }
    }
}
