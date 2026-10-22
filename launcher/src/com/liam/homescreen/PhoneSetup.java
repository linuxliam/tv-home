package com.liam.homescreen;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small web page, served only while switched on in the launcher's Settings, so every setting can be
 * changed from a phone or computer instead of the remote. Protected by a PIN shown on the TV, it
 * stops itself after 15 minutes, and it only ever accepts the launcher's own settings.
 */
final class PhoneSetup {
    static final int PORT = 8765;
    private static final long AUTO_OFF_MS = 15 * 60 * 1000L;
    private static final int MAX_BODY = 80000;

    private final Store store;
    private final Runnable onChanged;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private ServerSocket server;
    private String pin = "";
    private int failures;
    private long offAt;

    // what the launcher knows about this TV, handed over by the launcher (pairs of id, name)
    private volatile List<String[]> inputs = new ArrayList<>();
    private volatile List<String[]> apps = new ArrayList<>();

    private final Runnable autoOff = new Runnable() {
        @Override
        public void run() {
            stop();
            onChanged.run();
        }
    };

    PhoneSetup(Store store, Runnable onChanged) {
        this.store = store;
        this.onChanged = onChanged;
    }

    void setCatalog(List<String[]> inputs, List<String[]> apps) {
        this.inputs = inputs;
        this.apps = apps;
    }

    boolean running() {
        return server != null;
    }

    String pin() {
        return pin;
    }

    int minutesLeft() {
        return (int) Math.max(0, (offAt - System.currentTimeMillis() + 59999) / 60000);
    }

    String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return "http://" + a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "http://<this TV>:" + PORT;
    }

    void start() {
        if (server != null) return;
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            server = null;
            return;
        }
        pin = String.format("%04d", new SecureRandom().nextInt(10000));
        failures = 0;
        offAt = System.currentTimeMillis() + AUTO_OFF_MS;
        pool = Executors.newFixedThreadPool(3);
        final ServerSocket s = server;
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try {
                        final Socket sock = s.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(sock);
                            }
                        });
                    } catch (IOException e) {
                        break;
                    } catch (RuntimeException e) {
                        break; // pool shut down
                    }
                }
            }
        }, "phone-setup");
        acceptor.setDaemon(true);
        acceptor.start();
        ui.removeCallbacks(autoOff);
        ui.postDelayed(autoOff, AUTO_OFF_MS);
    }

    void stop() {
        ui.removeCallbacks(autoOff);
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // closing
            }
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        return c < 0 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            String request = readLine(in);
            if (request == null) return;
            int length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            }
            byte[] body = new byte[Math.min(Math.max(length, 0), MAX_BODY)];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String path = request.split(" ").length > 1 ? request.split(" ")[1] : "/";
            Map<String, String> params = parse(path.contains("?") ? path.substring(path.indexOf('?') + 1) : "");
            Map<String, String> form = parse(new String(body, 0, got, "UTF-8"));
            params.putAll(form);

            String html;
            String given = params.get("pin");
            if (given == null || !given.equals(pin)) {
                if (given != null) failures++;
                html = failures >= 5 ? page("Locked", "<p>Too many wrong PINs. Turn Phone setup off and on again on the TV.</p>")
                        : pinPage(given != null);
            } else if (request.startsWith("POST")) {
                if ("off".equals(params.get("action"))) {
                    html = page("Phone setup is off", "<p>You can close this page. Turn it on again from the TV's Settings.</p>");
                    send(sock, html);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            stop();
                            onChanged.run();
                        }
                    });
                    return;
                }
                save(form);
                ui.post(onChanged);
                html = page("Saved", "<p>Saved. Your TV is updating.</p><p><a href=\"/?pin=" + pin + "\">Back to settings</a></p>");
            } else {
                html = formPage();
            }
            send(sock, html);
        } catch (Exception ignored) {
            // a bad request just closes
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    private static void send(Socket sock, String html) throws IOException {
        byte[] out = html.getBytes("UTF-8");
        OutputStream os = sock.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + out.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
                .getBytes("UTF-8"));
        os.write(out);
        os.flush();
    }

    // ---------------------------------------------------------------- Saving (only known settings, only valid values)

    private void save(Map<String, String> f) {
        for (Choices.Choice c : Choices.appearance()) apply(c, f);
        apply(Choices.units(), f);
        for (Choices.Toggle t : Choices.toggles()) {
            if (f.containsKey(t.key)) store.putBool(t.key, "1".equals(f.get(t.key)));
        }
        if (f.containsKey("city")) store.put("weather_city", f.get("city").trim());
        if (f.containsKey("calendars")) store.putLines("calendar_urls", split(f.get("calendars")));
        if (f.containsKey("iptv")) store.putLines("iptv_urls", split(f.get("iptv")));
        if (f.containsKey("web")) store.putLines("web_sites", split(f.get("web")));
        if (f.containsKey("favs_present")) { // the favorites checklist: keep the order, add new ones at the end
            Set<String> known = new HashSet<>();
            for (String[] a : apps) known.add(a[0]);
            Set<String> checked = new HashSet<>();
            for (String p : f.getOrDefault("fav", "").split("\n")) if (!p.trim().isEmpty()) checked.add(p.trim());
            List<String> next = new ArrayList<>();
            for (String old : store.lines("favs")) {
                if (!known.contains(old) || checked.contains(old)) next.add(old);
            }
            for (String p : checked) if (known.contains(p) && !next.contains(p)) next.add(p);
            store.putLines("favs", next);
        }
    }

    private void apply(Choices.Choice c, Map<String, String> f) {
        String v = f.get(c.key);
        if (v != null && Choices.allowed(c, v)) store.put(c.key, v);
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split("[\\r\\n]+")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    /** The form posts repeated "fav" fields; fold them into one newline-separated value. */
    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(kv.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                if (k.equals("fav") && m.containsKey("fav")) v = m.get("fav") + "\n" + v;
                m.put(k, v);
            } catch (Exception ignored) {
                // skip malformed pair
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- Pages

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String hex(int c) {
        return String.format("#%06x", c & 0xFFFFFF);
    }

    /** The page wears the same theme as the TV: its gradient, fonts, glass fields. */
    private static String page(String title, String body) {
        int[] t = XmbView.currentCorners;
        boolean dark = XmbView.iconColor == 0xFF000000;
        String ink = dark ? "#000" : "#fff";
        String glass = dark ? "rgba(0,0,0,.10)" : "rgba(255,255,255,.18)";
        String edge = dark ? "rgba(0,0,0,.35)" : "rgba(255,255,255,.45)";
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>TV setup</title><style>"
                + "body{margin:0;min-height:100vh;font:17px Roboto,'Helvetica Neue',Arial,sans-serif;color:" + ink
                + ";background:linear-gradient(135deg," + hex(XmbView.blend(t[0], t[1], 0.5f)) + "," + hex(XmbView.blend(t[2], t[3], 0.5f)) + ");background-attachment:fixed}"
                + ".card{max-width:640px;margin:0 auto;padding:28px 18px 60px}"
                + "h1{font-weight:300;font-size:34px;letter-spacing:.5px;margin:6px 0 6px;text-shadow:0 2px 4px rgba(0,0,0,.35)}"
                + "h2{font-weight:500;font-size:20px;margin:34px 0 4px;padding-top:14px;border-top:1.5px solid " + edge + ";text-shadow:0 1px 3px rgba(0,0,0,.35)}"
                + "label{display:block;margin:16px 0 6px;font-weight:500;text-shadow:0 1px 3px rgba(0,0,0,.35)}"
                + "input,textarea,select{width:100%;box-sizing:border-box;padding:12px 14px;border-radius:12px;border:1.5px solid " + edge
                + ";background:" + glass + ";color:" + ink + ";font:inherit;backdrop-filter:blur(8px)}"
                + "select option{color:#000}"
                + "textarea{min-height:104px}"
                + ".row{display:flex;align-items:center;justify-content:space-between;gap:14px;margin:12px 0}"
                + ".row input{width:auto;transform:scale(1.5);margin-right:6px}"
                + ".apps label{display:flex;align-items:center;gap:12px;margin:6px 0;font-weight:400}"
                + ".apps input{width:auto;transform:scale(1.4)}"
                + "button{margin-top:26px;padding:13px 28px;border:1.5px solid " + edge + ";border-radius:999px;background:" + glass
                + ";color:" + ink + ";font:inherit;font-weight:500;backdrop-filter:blur(8px)}"
                + "small,.hint{opacity:.8;font-weight:400}a{color:" + ink + "}"
                + "</style></head><body><div class=card><h1>" + esc(title) + "</h1>" + body + "</div></body></html>";
    }

    private String pinPage(boolean wrong) {
        return page("TV setup", (wrong ? "<p>Wrong PIN.</p>" : "") + "<form><label>PIN shown on your TV</label>"
                + "<input name=pin inputmode=numeric autofocus><button>Continue</button></form>");
    }

    private static String select(Choices.Choice c, String current) {
        StringBuilder sb = new StringBuilder("<label>" + esc(c.title) + "</label><select name=" + c.key + ">");
        for (int i = 0; i < c.values.length; i++) {
            sb.append("<option value=\"").append(esc(c.values[i])).append("\"")
                    .append(c.values[i].equals(current) ? " selected" : "").append(">").append(esc(c.labels[i])).append("</option>");
        }
        return sb.append("</select>").toString();
    }

    private String formPage() {
        StringBuilder b = new StringBuilder();
        b.append("<form method=post><input type=hidden name=pin value=\"").append(pin).append("\">");
        b.append("<p class=hint>Changes appear on the TV as soon as you press Save.</p>");

        b.append("<h2>Home screen</h2>");
        for (Choices.Choice c : Choices.appearance()) b.append(select(c, store.get(c.key, c.def)));
        for (Choices.Toggle t : Choices.toggles()) {
            boolean on = store.bool(t.key, t.def);
            b.append("<div class=row><span>").append(esc(t.title)).append("</span><span><input type=hidden name=").append(t.key)
                    .append(" value=0><input type=checkbox name=").append(t.key).append(" value=1").append(on ? " checked" : "")
                    .append("></span></div>");
        }

        b.append("<h2>Weather</h2><label>City <small>(e.g. Seattle, WA)</small></label><input name=city value=\"")
                .append(esc(store.weatherCity())).append("\">");
        b.append(select(Choices.units(), store.get("units", "f")));

        b.append("<h2>Calendar</h2><label>Calendar links, one per line <small>(Apple Calendar: Share Calendar &gt; Public Calendar)</small></label><textarea name=calendars>");
        for (String s : store.calendarUrls()) b.append(esc(s)).append('\n');
        b.append("</textarea>");

        b.append("<h2>Live TV</h2><label>IPTV playlist links (M3U), one per line</label><textarea name=iptv>");
        for (String s : store.iptvUrls()) b.append(esc(s)).append('\n');
        b.append("</textarea>");

        b.append("<h2>Web pages</h2><label>For Multiview, one per line <small>(an address, or Name|address)</small></label><textarea name=web>");
        for (String s : store.lines("web_sites")) b.append(esc(s)).append('\n');
        b.append("</textarea>");

        List<String[]> appList = apps;
        if (!appList.isEmpty()) {
            Set<String> favs = new HashSet<>(store.lines("favs"));
            b.append("<h2>Favorites</h2><p class=hint>The apps in the Favorites category. The order is set on the TV (hold OK on an app).</p>")
                    .append("<input type=hidden name=favs_present value=1><div class=apps>");
            for (String[] a : appList) {
                b.append("<label><input type=checkbox name=fav value=\"").append(esc(a[0])).append("\"")
                        .append(favs.contains(a[0]) ? " checked" : "").append(">").append(esc(a[1])).append("</label>");
            }
            b.append("</div>");
        }
        b.append("<button>Save to TV</button></form>");
        b.append("<form method=post><input type=hidden name=pin value=\"").append(pin)
                .append("\"><input type=hidden name=action value=off><button>Turn phone setup off</button></form>");
        return page("TV setup", b.toString());
    }
}
