package com.liam.homescreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.EditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen in the style of RetroArch's XMB. Left/Right change category, Up/Down move through the
 * items, OK opens or changes, holding OK (or Menu) opens the item's quick menu, Back leaves a submenu.
 * Every setting lives in the Settings category; long text can be entered on the TV or from a phone.
 */
public class LauncherActivity extends Activity {
    // Seeds the Favorites list on first run. Everything else is in Apps, alphabetically.
    private static final String[] PINNED = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv",
            "com.plexapp.android",
            "com.vt.tv.tvcenter",
    };
    private static final String SETTINGS = "com.android.tv.settings";
    // Hisense system apps hidden from the lists (prefix match), except the ones allowed below.
    private static final String HIDDEN_PREFIX = "com.vt.";
    private static final String[] ALLOWED_VT = {"com.vt.tv.tvcenter"};

    private static final long WEATHER_EVERY_MS = 15 * 60 * 1000L;
    private static final long CALENDAR_EVERY_MS = 10 * 60 * 1000L;
    private static final long IPTV_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final long AMBIENT_IDLE_MS = 3 * 60 * 1000L;
    private static final long AMBIENT_CYCLE_MS = 20 * 1000L;
    private static final int CALENDAR_DAYS = 14;
    private static final int RECENT_MAX = 12;
    private static final String[] AMBIENT_KINDS = {"clock", "weather", "calendar"};

    // setting choices: stored value -> label
    private static final String[] SIZE_VALUES = {"1.0", "1.2", "1.4", "1.6"};
    private static final String[] SIZE_LABELS = {"Small", "Medium", "Large", "Extra large"};
    private static final String[] ANIM_VALUES = {"0", "110", "180", "300"};
    private static final String[] ANIM_LABELS = {"Off", "Fast", "Normal", "Slow"};
    private static final String[] ICON_VALUES = {"auto", "white", "black"};
    private static final String[] ICON_LABELS = {"Automatic", "White", "Black"};
    private static final String[] BG_LABELS = {"Ribbon", "Simple ribbon", "Snow", "Bokeh", "None"};

    private XmbView xmb;
    private FrameLayout root;
    private WidgetView ambient;
    private boolean searching;
    private final StringBuilder searchQuery = new StringBuilder();
    private boolean ambientOn;
    private int ambientKind;
    private final Runnable showAmbient = new Runnable() {
        @Override
        public void run() {
            enterAmbient();
        }
    };
    private final Runnable cycleAmbient = new Runnable() {
        @Override
        public void run() {
            if (!ambientOn || ambient == null) return;
            ambientKind = (ambientKind + 1) % AMBIENT_KINDS.length;
            ambient.setClock24(store.bool("clock24", false));
            ambient.showWidget(AMBIENT_KINDS[ambientKind]);
            ui.postDelayed(this, AMBIENT_CYCLE_MS);
        }
    };
    private Store store;
    private Sfx sfx;
    private PhoneSetup phone;
    private TvInputManager tvInputs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            reload();
        }

        @Override
        public void onInputAdded(String inputId) {
            reload();
        }

        @Override
        public void onInputRemoved(String inputId) {
            reload();
        }
    };

    // Fetched data, kept between refreshes
    private String dataPrint = "";
    private WeatherClient.Report weather;
    private String weatherError;
    private long weatherAt;
    private List<IcsCalendar.Event> events = new ArrayList<>();
    private boolean calendarLoaded;
    private long calendarAt;
    private List<IptvPlaylist.Channel> channels = new ArrayList<>();
    private String iptvError;
    private long iptvAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        xmb = new XmbView(this);
        phone = new PhoneSetup(store, new Runnable() {
            @Override
            public void run() { // saved from the phone page, or switched off
                settingsChanged();
                refreshData(true);
                xmb.notice(phone.running() ? "Settings updated from your phone" : "Phone setup is off");
            }
        });
        xmb.setEvents(new XmbView.Events() {
            @Override
            public void onMove() {
                if (sfx != null) sfx.move();
            }

            @Override
            public void onSelect() {
                if (sfx != null) sfx.ok();
            }

            @Override
            public void onBack() {
                if (sfx != null) sfx.back();
            }

            @Override
            public void onSubmenu(boolean open) {
                if (!open) searching = false;
            }
        });
        applyAppearance();
        root = new FrameLayout(this);
        root.addView(xmb, new FrameLayout.LayoutParams(-1, -1));
        ambient = new WidgetView(this);
        ambient.setVisibility(View.GONE);
        ambient.setClickable(true);
        ambient.setFocusable(false);
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        tvInputs = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, ui);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        ui.removeCallbacks(showAmbient);
        ui.removeCallbacks(cycleAmbient);
        exitAmbient();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAppearance();
        refreshData(false);
        reload();
        bumpIdle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        phone.stop();
        if (sfx != null) sfx.release();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        xmb.goHome(); // Home pressed while already here: back to the start
    }

    @Override
    public void onBackPressed() {
        if (ambientOn) {
            exitAmbient();
            bumpIdle();
            return;
        }
        if (searching) {
            if (searchQuery.length() > 0) {
                searchQuery.setLength(searchQuery.length() - 1);
                refreshSearch();
                return;
            }
            searching = false;
            xmb.back();
            return;
        }
        if (xmb.back()) return;
    }

    /** Push every appearance setting into the view. */
    private void applyAppearance() {
        xmb.setTheme(store.get("theme", "electric_blue"));
        xmb.setBackgroundStyle(store.get("bg", "ribbon"));
        xmb.setBackgroundSpeed(Float.parseFloat(store.get("bgspeed", "0.45")));
        xmb.setScaleFactor(Float.parseFloat(store.get("size", "1.2")));
        xmb.setIconMode(store.get("icons", "auto"));
        xmb.setShadows(store.bool("shadows", true));
        xmb.setFrosted(store.bool("frost", true));
        xmb.setHints(store.bool("hints", true));
        xmb.setAnimMs(Integer.parseInt(store.get("anim", "180")));
        xmb.setClock24(store.bool("clock24", false));
        if (store.bool("sounds", false)) {
            if (sfx == null) sfx = new Sfx();
        } else if (sfx != null) {
            sfx.release();
            sfx = null;
        }
    }

    // ---------------------------------------------------------------- Remote keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (ambientOn) {
            exitAmbient();
            bumpIdle();
            // swallow this key so it only wakes the menu
            return true;
        }
        bumpIdle();
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            openSearch('\0');
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            resumeLive(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            resumeLive(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            resumeLive(0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            if (searching && searchQuery.length() > 0) {
                searchQuery.setLength(searchQuery.length() - 1);
                refreshSearch();
                return true;
            }
        }
        char typed = typeChar(keyCode, event);
        if (typed != 0) {
            if (searching) {
                if (searchQuery.length() < 40) searchQuery.append(typed);
                refreshSearch();
                return true;
            }
            if (!xmb.inSubmenu()) {
                openSearch(typed);
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                xmb.moveCat(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                xmb.moveCat(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                xmb.moveItem(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                xmb.moveItem(1);
                return true;
            case KeyEvent.KEYCODE_MENU:
                xmb.longActivate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                event.startTracking(); // so a long press can be told apart from a press
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (ambientOn) return true;
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && (event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
            xmb.activate();
            bumpIdle();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (ambientOn) return true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            xmb.longActivate();
            bumpIdle();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private static char typeChar(int keyCode, KeyEvent event) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return (char) ('a' + (keyCode - KeyEvent.KEYCODE_A));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return (char) ('0' + (keyCode - KeyEvent.KEYCODE_0));
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) return ' ';
        int u = event.getUnicodeChar();
        if (u >= 'A' && u <= 'Z') return (char) (u + 32);
        if ((u >= 'a' && u <= 'z') || (u >= '0' && u <= '9') || u == ' ') return (char) u;
        return 0;
    }

    private void bumpIdle() {
        ui.removeCallbacks(showAmbient);
        if (!ambientOn) ui.postDelayed(showAmbient, AMBIENT_IDLE_MS);
    }

    // ---------------------------------------------------------------- Background data

    /** Fetch weather, calendar and IPTV in the background when stale (or when the settings changed). */
    private void refreshData(boolean forceAll) {
        String print = store.dataFingerprint();
        boolean force = forceAll || !print.equals(dataPrint);
        dataPrint = print;
        long now = System.currentTimeMillis();

        final String city = store.weatherCity();
        final boolean f = store.fahrenheit();
        if (city.isEmpty()) {
            weather = null;
        } else if (force || now - weatherAt > WEATHER_EVERY_MS) {
            weatherAt = now;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final WeatherClient.Report r = WeatherClient.fetch(city, f);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                weather = r;
                                weatherError = null;
                                reload();
                            }
                        });
                    } catch (final Exception e) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                weather = null;
                                weatherError = e.getMessage();
                                weatherAt = 0; // try again soon
                                reload();
                            }
                        });
                    }
                }
            });
        }

        final List<String> calendars = store.calendarUrls();
        if (calendars.isEmpty()) {
            events = new ArrayList<>();
            calendarLoaded = false;
        } else if (force || now - calendarAt > CALENDAR_EVERY_MS) {
            calendarAt = now;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    Calendar startOfToday = Calendar.getInstance();
                    startOfToday.set(Calendar.HOUR_OF_DAY, 0);
                    startOfToday.set(Calendar.MINUTE, 0);
                    startOfToday.set(Calendar.SECOND, 0);
                    final List<IcsCalendar.Event> list = IcsCalendar.load(calendars,
                            startOfToday.getTimeInMillis(), System.currentTimeMillis() + CALENDAR_DAYS * 86400000L);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            events = list;
                            calendarLoaded = true;
                            reload();
                        }
                    });
                }
            });
        }

        final List<String> playlists = store.iptvUrls();
        if (playlists.isEmpty()) {
            channels = new ArrayList<>();
            iptvError = null;
        } else if (force || now - iptvAt > IPTV_EVERY_MS) {
            iptvAt = now;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    final List<IptvPlaylist.Channel> all = new ArrayList<>();
                    String err = null;
                    for (String url : playlists) {
                        try {
                            all.addAll(IptvPlaylist.parse(Net.get(url)));
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                    }
                    final String error = all.isEmpty() ? err : null;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            channels = all;
                            iptvError = error;
                            if (all.isEmpty()) iptvAt = 0;
                            reload();
                        }
                    });
                }
            });
        }
    }

    // ---------------------------------------------------------------- Content

    private void reload() {
        List<App> apps = loadApps();
        List<XmbView.Cat> cats = new ArrayList<>();
        cats.add(favoritesCat(apps));
        cats.add(recentCat(apps));
        XmbView.Cat all = new XmbView.Cat("Apps", XmbView.G_GRID);
        for (App a : apps) all.items.add(appItem(a));
        cats.add(all);
        cats.add(liveCat());
        cats.add(multiviewCat());
        cats.add(calendarCat());
        cats.add(weatherCat());
        cats.add(inputsCat());
        cats.add(settingsCat());
        xmb.setCategories(cats);
        xmb.refreshLevels();
        Shared.weather = weather;
        Shared.events = events;
        Shared.channels = channels;

        // keep the phone page's lists current, and show on screen while it is switched on
        List<String[]> inputList = new ArrayList<>();
        for (TvInputInfo i : connectedInputs()) inputList.add(new String[]{i.getId(), inputName(i)});
        List<String[]> appList = new ArrayList<>();
        for (App a : apps) appList.add(new String[]{a.pkg, a.name});
        phone.setCatalog(inputList, appList);
        xmb.setBadge(phone.running()
                ? "Phone setup on  ·  " + phone.address().replace("http://", "") + "  ·  PIN " + phone.pin() : null);

        if (weather != null) {
            xmb.setWeather(WeatherClient.glyph(weather.code), weather.temp + "°" + (weather.fahrenheit ? "F" : "C"),
                    WeatherClient.describe(weather.code));
        } else {
            xmb.setWeather(-1, "", "");
        }
    }

    /** After a setting changed: rebuild the lists and any open submenu. */
    private void settingsChanged() {
        applyAppearance();
        refreshData(false);
        reload();
    }

    // ---- Favorites and Recent

    private List<String> favorites(List<App> apps) {
        if (!store.has("favs")) {
            List<String> seed = new ArrayList<>();
            for (String p : PINNED) for (App a : apps) if (a.pkg.equals(p)) seed.add(p);
            store.putLines("favs", seed);
        }
        return store.lines("favs");
    }

    private XmbView.Cat favoritesCat(List<App> apps) {
        XmbView.Cat cat = new XmbView.Cat("Favorites", XmbView.G_STAR);
        for (String pkg : favorites(apps)) {
            for (App a : apps) if (a.pkg.equals(pkg)) cat.items.add(appItem(a));
        }
        for (final IptvPlaylist.Channel ch : starredChannels()) {
            cat.items.add(channelItem(ch, channels));
        }
        if (cat.items.isEmpty()) {
            cat.items.add(info("No favorites yet", "Hold OK on any app or channel to add it", glyph(XmbView.G_STAR)));
        }
        return cat;
    }

    private List<IptvPlaylist.Channel> starredChannels() {
        List<IptvPlaylist.Channel> out = new ArrayList<>();
        List<String> keys = store.lines("ch_favs");
        if (keys.isEmpty() || channels.isEmpty()) return out;
        for (String key : keys) {
            for (IptvPlaylist.Channel ch : channels) {
                if (chKey(ch).equals(key)) {
                    out.add(ch);
                    break;
                }
            }
        }
        return out;
    }

    private static String chKey(IptvPlaylist.Channel ch) {
        return ch.url + "|" + ch.name;
    }

    private XmbView.Item channelItem(final IptvPlaylist.Channel ch, final List<IptvPlaylist.Channel> list) {
        int at = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).url.equals(ch.url) && list.get(i).name.equals(ch.name)) {
                at = i;
                break;
            }
        }
        final int index = at;
        return new XmbView.Item(ch.name, ch.group, glyph(XmbView.G_LIVE), new Runnable() {
            @Override
            public void run() {
                playChannels(list.isEmpty() ? channels : list, index);
            }
        }, new Runnable() {
            @Override
            public void run() {
                channelMenu(list.isEmpty() ? channels : list, index);
            }
        });
    }

    private XmbView.Cat recentCat(List<App> apps) {
        XmbView.Cat cat = new XmbView.Cat("Recent", XmbView.G_CLOCK);
        long now = System.currentTimeMillis();
        for (String line : store.lines("recent")) {
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;
            for (App a : apps) {
                if (a.pkg.equals(parts[0])) {
                    long ago = Math.max(0, now - Long.parseLong(parts[1]));
                    XmbView.Item it = appItem(a);
                    cat.items.add(new XmbView.Item(it.title, "Opened " + ago(ago), it.icon, it.action, it.longAction));
                }
            }
        }
        if (cat.items.isEmpty()) cat.items.add(info("Nothing yet", "Apps you open show up here", glyph(XmbView.G_CLOCK)));
        return cat;
    }

    private static String ago(long ms) {
        long m = ms / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        return d + (d == 1 ? " day ago" : " days ago");
    }

    private void addRecent(String pkg) {
        List<String> out = new ArrayList<>();
        out.add(pkg + "|" + System.currentTimeMillis());
        for (String line : store.lines("recent")) {
            if (!line.startsWith(pkg + "|") && out.size() < RECENT_MAX) out.add(line);
        }
        store.putLines("recent", out);
    }

    // ---- Apps

    private XmbView.Item appItem(final App a) {
        return new XmbView.Item(a.name, null, a.icon, new Runnable() {
            @Override
            public void run() {
                addRecent(a.pkg);
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(a.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            }
        }, new Runnable() {
            @Override
            public void run() {
                appMenu(a);
            }
        });
    }

    /** Quick menu for an app (hold OK): open, favorite, info. */
    private void appMenu(final App a) {
        xmb.pushList(a.name, XmbView.G_GRID, new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                l.add(new XmbView.Item("Open", null, glyph(XmbView.G_PLAY), appItem(a).action, null));
                final List<String> favs = store.lines("favs");
                final boolean isFav = favs.contains(a.pkg);
                l.add(new XmbView.Item(isFav ? "Remove from favorites" : "Add to favorites", null,
                        glyph(XmbView.G_STAR), new Runnable() {
                    @Override
                    public void run() {
                        List<String> now = new ArrayList<>(favs);
                        if (isFav) now.remove(a.pkg);
                        else now.add(a.pkg);
                        store.putLines("favs", now);
                        xmb.notice(isFav ? a.name + " removed from favorites" : a.name + " added to favorites");
                        reload();
                    }
                }, null));
                if (isFav) {
                    final int at = favs.indexOf(a.pkg);
                    if (at > 0) {
                        l.add(new XmbView.Item("Move up", null, glyph(XmbView.G_UP), new Runnable() {
                            @Override
                            public void run() {
                                List<String> now = new ArrayList<>(favs);
                                Collections.swap(now, at, at - 1);
                                store.putLines("favs", now);
                                xmb.notice(a.name + " moved up");
                                reload();
                            }
                        }, null));
                        l.add(new XmbView.Item("Move to top", null, glyph(XmbView.G_STAR), new Runnable() {
                            @Override
                            public void run() {
                                List<String> now = new ArrayList<>(favs);
                                now.remove(a.pkg);
                                now.add(0, a.pkg);
                                store.putLines("favs", now);
                                xmb.notice(a.name + " moved to the top");
                                reload();
                            }
                        }, null));
                    }
                    if (at >= 0 && at < favs.size() - 1) {
                        l.add(new XmbView.Item("Move down", null, glyph(XmbView.G_DOWN), new Runnable() {
                            @Override
                            public void run() {
                                List<String> now = new ArrayList<>(favs);
                                Collections.swap(now, at, at + 1);
                                store.putLines("favs", now);
                                xmb.notice(a.name + " moved down");
                                reload();
                            }
                        }, null));
                    }
                }
                l.add(new XmbView.Item("App info", "Uninstall, disable, permissions", glyph(XmbView.G_INFO), new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }, null));
                return l;
            }
        });
    }

    // ---- Live (IPTV)

    private XmbView.Cat liveCat() {
        XmbView.Cat cat = new XmbView.Cat("Live", XmbView.G_LIVE);
        Drawable icon = glyph(XmbView.G_LIVE);
        if (store.iptvUrls().isEmpty()) {
            cat.items.add(new XmbView.Item("IPTV not set up", "Press OK to add a playlist", icon, new Runnable() {
                @Override
                public void run() {
                    xmb.pushList("Live TV playlists", XmbView.G_LIVE, playlistBuilder());
                }
            }, null));
            return cat;
        }
        if (channels.isEmpty()) {
            cat.items.add(info(iptvError == null ? "Loading channels..." : "Couldn't load channels",
                    iptvError == null ? "One moment" : iptvError, icon));
            return cat;
        }
        cat.items.add(new XmbView.Item("TV Guide", channels.size() + " channels  ·  browse and tune",
                icon, new Runnable() {
            @Override
            public void run() {
                openGuide(channels, -1);
            }
        }, null));
        final String lastUrl = store.get("last_channel_url", null);
        final String lastName = store.get("last_channel_name", null);
        if (lastUrl != null) {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).url.equals(lastUrl)) {
                    final int at = i;
                    cat.items.add(new XmbView.Item("Watch now", lastName == null ? "Last channel" : lastName,
                            glyph(XmbView.G_PLAY), new Runnable() {
                        @Override
                        public void run() {
                            playChannels(channels, at);
                        }
                    }, null));
                    break;
                }
            }
        }
        List<IptvPlaylist.Channel> starred = starredChannels();
        if (!starred.isEmpty()) {
            cat.items.add(new XmbView.Item("Favorites", starred.size() + " channels in the guide",
                    glyph(XmbView.G_STAR), new Runnable() {
                @Override
                public void run() {
                    openGuide(starred, 0);
                }
            }, null));
        }
        return cat;
    }

    private void openGuide(List<IptvPlaylist.Channel> list, int index) {
        GuideActivity.channels = list;
        GuideActivity.startIndex = index;
        startActivity(new Intent(this, GuideActivity.class));
    }

    private void playChannels(List<IptvPlaylist.Channel> list, int index) {
        PlayerActivity.channels = list;
        PlayerActivity.startIndex = index;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    /** Channel± / Play on Home: resume last IPTV channel (optionally step by dir). */
    private void resumeLive(int dir) {
        if (channels.isEmpty()) {
            xmb.notice(store.iptvUrls().isEmpty() ? "Set up Live TV playlists in Settings" : "Channels still loading");
            return;
        }
        String lastUrl = store.get("last_channel_url", null);
        int at = 0;
        if (lastUrl != null) {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).url.equals(lastUrl)) {
                    at = i;
                    break;
                }
            }
        }
        at = (at + dir + channels.size()) % channels.size();
        playChannels(channels, at);
    }

    // ---- Search

    private void openSearch(char first) {
        searching = true;
        searchQuery.setLength(0);
        if (first != 0) searchQuery.append(first);
        xmb.closeAll();
        xmb.pushList(searchHeading(), XmbView.G_SEARCH, new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                return buildSearchResults();
            }
        });
    }

    private void refreshSearch() {
        if (!searching) return;
        xmb.setTopTitle(searchHeading());
        xmb.refreshLevels();
    }

    private String searchHeading() {
        if (searchQuery.length() == 0) return "Search";
        return "Search: " + searchQuery;
    }

    private List<XmbView.Item> buildSearchResults() {
        List<XmbView.Item> l = new ArrayList<>();
        String q = searchQuery.toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            l.add(info("Type to search", "Apps, channels, inputs — Search key or letters", glyph(XmbView.G_SEARCH)));
            return l;
        }
        List<App> apps = loadApps();
        List<String> favPkgs = favorites(apps);
        boolean anyFav = false;
        for (String pkg : favPkgs) {
            for (App a : apps) {
                if (a.pkg.equals(pkg) && a.name.toLowerCase(Locale.ROOT).contains(q)) {
                    if (!anyFav) {
                        l.add(info("Favorites", null, glyph(XmbView.G_STAR)));
                        anyFav = true;
                    }
                    l.add(appItem(a));
                }
            }
        }
        boolean anyApp = false;
        for (App a : apps) {
            if (a.name.toLowerCase(Locale.ROOT).contains(q)) {
                if (!anyApp) {
                    l.add(info("Apps", null, glyph(XmbView.G_GRID)));
                    anyApp = true;
                }
                l.add(appItem(a));
            }
        }
        boolean anyCh = false;
        for (int i = 0; i < channels.size(); i++) {
            IptvPlaylist.Channel ch = channels.get(i);
            if (ch.name.toLowerCase(Locale.ROOT).contains(q)
                    || (ch.group != null && ch.group.toLowerCase(Locale.ROOT).contains(q))) {
                if (!anyCh) {
                    l.add(info("Live", null, glyph(XmbView.G_LIVE)));
                    anyCh = true;
                }
                final int at = i;
                l.add(new XmbView.Item(ch.name, ch.group, glyph(XmbView.G_LIVE), new Runnable() {
                    @Override
                    public void run() {
                        playChannels(channels, at);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        channelMenu(channels, at);
                    }
                }));
            }
        }
        boolean anyIn = false;
        for (final TvInputInfo tin : connectedInputs()) {
            String name = inputName(tin);
            if (name.toLowerCase(Locale.ROOT).contains(q)) {
                if (!anyIn) {
                    l.add(info("Inputs", null, glyph(XmbView.G_INPUT)));
                    anyIn = true;
                }
                final String id = tin.getId();
                l.add(new XmbView.Item(name, stateText(id), glyph(XmbView.G_INPUT), new Runnable() {
                    @Override
                    public void run() {
                        switchInput(id);
                    }
                }, null));
            }
        }
        if (l.isEmpty()) l.add(info("No matches", "Try another word", glyph(XmbView.G_SEARCH)));
        return l;
    }

    // ---- Idle ambient

    private void enterAmbient() {
        if (ambientOn || ambient == null) return;
        ambientOn = true;
        searching = false;
        ambientKind = 0;
        ambient.setClock24(store.bool("clock24", false));
        ambient.showWidget(AMBIENT_KINDS[ambientKind]);
        ambient.setVisibility(View.VISIBLE);
        ambient.bringToFront();
        ui.removeCallbacks(cycleAmbient);
        ui.postDelayed(cycleAmbient, AMBIENT_CYCLE_MS);
    }

    private void exitAmbient() {
        ambientOn = false;
        ui.removeCallbacks(cycleAmbient);
        if (ambient != null) ambient.setVisibility(View.GONE);
    }

    // ---- Calendar

    private XmbView.Cat calendarCat() {
        XmbView.Cat cat = new XmbView.Cat("Calendar", XmbView.G_CAL);
        Drawable icon = glyph(XmbView.G_CAL);
        if (store.calendarUrls().isEmpty()) {
            cat.items.add(new XmbView.Item("Add your calendar", "Press OK to set it up", icon, new Runnable() {
                @Override
                public void run() {
                    xmb.pushList("Calendar", XmbView.G_CAL, calendarBuilder());
                }
            }, null));
            return cat;
        }
        if (!calendarLoaded) {
            cat.items.add(info("Loading calendar...", "One moment", icon));
            return cat;
        }
        long now = System.currentTimeMillis();
        SimpleDateFormat time = new SimpleDateFormat(store.bool("clock24", false) ? "HH:mm" : "h:mm a", Locale.getDefault());
        SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
        int shown = 0;
        for (final IcsCalendar.Event e : events) {
            if (e.end < now && !e.allDay) continue; // already finished today
            if (shown++ >= 40) break;
            String when = dayLabel(e.start, day);
            String sub = e.allDay ? when + "  ·  All day"
                    : when + "  ·  " + time.format(new Date(e.start)) + " - " + time.format(new Date(e.end));
            if (!e.where.isEmpty()) sub += "  ·  " + e.where;
            cat.items.add(info(e.title.isEmpty() ? "(No title)" : e.title, sub, icon));
        }
        if (shown == 0) cat.items.add(info("Nothing coming up", "The next " + CALENDAR_DAYS + " days are clear", icon));
        return cat;
    }

    private static String dayLabel(long millis, SimpleDateFormat fallback) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(millis);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) {
            return "Today";
        }
        a.add(Calendar.DAY_OF_YEAR, 1);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) {
            return "Tomorrow";
        }
        return fallback.format(new Date(millis));
    }

    // ---- Weather

    private XmbView.Cat weatherCat() {
        XmbView.Cat cat = new XmbView.Cat("Weather", XmbView.G_SUN);
        Drawable icon = glyph(XmbView.G_SUN);
        if (store.weatherCity().isEmpty()) {
            cat.items.add(new XmbView.Item("Add your city", "Press OK to set it up", icon, new Runnable() {
                @Override
                public void run() {
                    xmb.pushList("Weather", XmbView.G_SUN, weatherBuilder());
                }
            }, null));
            return cat;
        }
        if (weather == null) {
            cat.items.add(info(weatherError == null ? "Loading weather..." : "Couldn't load weather",
                    weatherError == null ? "One moment" : weatherError, icon));
            return cat;
        }
        String unit = weather.fahrenheit ? "°F" : "°C";
        cat.items.add(info(weather.place + "  " + weather.temp + unit,
                WeatherClient.describe(weather.code) + "  ·  feels " + weather.feels + "°  ·  wind " + weather.wind
                        + (weather.fahrenheit ? " mph" : " km/h"), glyph(WeatherClient.glyph(weather.code))));
        for (WeatherClient.Day d : weather.days) {
            cat.items.add(info(d.label, "High " + d.hi + "°   Low " + d.lo + "°   " + WeatherClient.describe(d.code),
                    glyph(WeatherClient.glyph(d.code))));
        }
        return cat;
    }

    private static XmbView.Item info(String title, String sub, Drawable icon) {
        return new XmbView.Item(title, sub, icon, null, null);
    }

    // ---- Multiview

    private XmbView.Cat multiviewCat() {
        XmbView.Cat cat = new XmbView.Cat("Multiview", XmbView.G_MULTI);
        cat.items.add(new XmbView.Item("Open", "Up to four live windows at once",
                glyph(XmbView.G_MULTI), new Runnable() {
            @Override
            public void run() {
                Intent mv = new Intent(LauncherActivity.this, MultiviewActivity.class);
                mv.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(mv);
            }
        }, null));
        return cat;
    }

    // ---- Inputs

    private XmbView.Cat inputsCat() {
        XmbView.Cat cat = new XmbView.Cat("Inputs", XmbView.G_INPUT);
        final List<TvInputInfo> inputs = connectedInputs();

        for (final TvInputInfo info : inputs) {
            final String id = info.getId();
            cat.items.add(new XmbView.Item(inputName(info), stateText(id) + "  ·  hold OK for more",
                    glyph(XmbView.G_INPUT), new Runnable() {
                @Override
                public void run() {
                    switchInput(id);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    inputMenu(info);
                }
            }));
        }
        return cat;
    }

    // ---------------------------------------------------------------- Settings

    private XmbView.Cat settingsCat() {
        XmbView.Cat cat = new XmbView.Cat("Settings", XmbView.G_GEAR);
        cat.items.add(settingItem("Picture", "Mode, brightness, HDR", XmbView.G_PICTURE, Settings.ACTION_DISPLAY_SETTINGS));
        cat.items.add(settingItem("Sound", "Output, equaliser", XmbView.G_SOUND, "android.settings.SOUND_SETTINGS"));
        cat.items.add(settingItem("Network", "Wi-Fi and internet", XmbView.G_WIFI, Settings.ACTION_WIFI_SETTINGS));
        cat.items.add(settingItem("All TV settings", "Everything else", XmbView.G_GEAR, Settings.ACTION_SETTINGS));
        cat.items.add(submenu("Home screen", "Theme, size, background, sounds", XmbView.G_BRUSH, appearanceBuilder()));
        cat.items.add(submenu("Weather", "City and units", XmbView.G_SUN, weatherBuilder()));
        cat.items.add(submenu("Calendar", "Calendar links", XmbView.G_CAL, calendarBuilder()));
        cat.items.add(submenu("Live TV playlists", "IPTV playlist links", XmbView.G_LIVE, playlistBuilder()));
        cat.items.add(submenu("Web pages", "Sites to show in Multiview", XmbView.G_MULTI, webPagesBuilder()));
        cat.items.add(submenu("Phone setup", "Type settings on a phone or computer", XmbView.G_PHONE, phoneBuilder()));
        cat.items.add(submenu("About", "Credits and status", XmbView.G_INFO, aboutBuilder()));
        return cat;
    }

    private XmbView.Item settingItem(String title, String sub, int g, final String action) {
        return new XmbView.Item(title, sub, glyph(g), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }, null);
    }

    private XmbView.Item submenu(final String title, String sub, final int g, final XmbView.ListBuilder b) {
        return new XmbView.Item(title, sub, glyph(g), new Runnable() {
            @Override
            public void run() {
                xmb.pushList(title, g, b);
            }
        }, null).submenu();
    }

    /** A setting that steps through its choices each time OK is pressed. */
    private XmbView.Item cycle(final String key, final String title, final String[] values, final String[] labels,
                               final String def, int g) {
        String cur = store.get(key, def);
        int idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
        return new XmbView.Item(title, null, glyph(g), new Runnable() {
            @Override
            public void run() {
                String now = store.get(key, def);
                int at = 0;
                for (int i = 0; i < values.length; i++) if (values[i].equals(now)) at = i;
                int n = (at + 1) % values.length;
                store.put(key, values[n]);
                settingsChanged();
                xmb.notice(title + ": " + labels[n]);
            }
        }, null).value(labels[idx]);
    }

    private XmbView.Item toggle(final String key, final String title, final boolean def, int g) {
        return new XmbView.Item(title, null, glyph(g), new Runnable() {
            @Override
            public void run() {
                boolean now = !store.bool(key, def);
                store.putBool(key, now);
                settingsChanged();
                xmb.notice(title + ": " + (now ? "On" : "Off"));
            }
        }, null).toggle(store.bool(key, def));
    }

    private XmbView.ListBuilder appearanceBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                String[] themeLabels = new String[XmbView.THEME_ORDER.length];
                for (int i = 0; i < themeLabels.length; i++) themeLabels[i] = XmbView.themeLabel(XmbView.THEME_ORDER[i]);
                l.add(cycle("theme", "Menu colour theme", XmbView.THEME_ORDER, themeLabels, "electric_blue", XmbView.G_BRUSH));
                l.add(cycle("bg", "Background", XmbView.BG_ORDER, BG_LABELS, "ribbon", XmbView.G_BRUSH));
                l.add(cycle("bgspeed", "Background speed", Choices.BGSPEED_VALUES, Choices.BGSPEED_LABELS, "0.45", XmbView.G_PLAY));
                l.add(cycle("size", "Menu size", SIZE_VALUES, SIZE_LABELS, "1.2", XmbView.G_PICTURE));
                l.add(cycle("icons", "Icon and text colour", ICON_VALUES, ICON_LABELS, "auto", XmbView.G_BRUSH));
                l.add(toggle("shadows", "Shadows", true, XmbView.G_BRUSH));
                l.add(toggle("frost", "Frosted glass top edge", true, XmbView.G_BRUSH));
                l.add(toggle("hints", "Button hints", true, XmbView.G_INFO));
                l.add(cycle("anim", "Animation speed", ANIM_VALUES, ANIM_LABELS, "180", XmbView.G_PLAY));
                l.add(toggle("sounds", "Menu sounds", false, XmbView.G_SOUND));
                l.add(toggle("clock24", "24-hour clock", false, XmbView.G_CLOCK));
                l.add(new XmbView.Item("Reset home screen options", "Back to the defaults", glyph(XmbView.G_GEAR), new Runnable() {
                    @Override
                    public void run() {
                        for (String k : new String[]{"theme", "bg", "bgspeed", "size", "icons", "shadows", "frost", "hints", "anim", "sounds", "clock24"}) {
                            store.remove(k);
                        }
                        settingsChanged();
                        xmb.notice("Home screen options reset");
                    }
                }, null));
                return l;
            }
        };
    }

    private XmbView.ListBuilder weatherBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                l.add(new XmbView.Item("City", "e.g. Seattle, WA", glyph(XmbView.G_SUN), new Runnable() {
                    @Override
                    public void run() {
                        askText("Weather city", store.weatherCity(), "Seattle, WA", new TextResult() {
                            @Override
                            public void ok(String text) {
                                store.put("weather_city", text.trim());
                                weatherError = null;
                                settingsChanged();
                                xmb.notice(text.trim().isEmpty() ? "Weather turned off" : "Weather city: " + text.trim());
                            }
                        });
                    }
                }, null).value(store.weatherCity().isEmpty() ? "Not set" : store.weatherCity()));
                l.add(cycle("units", "Temperature", new String[]{"f", "c"}, new String[]{"Fahrenheit", "Celsius"}, "f", XmbView.G_SUN));
                l.add(new XmbView.Item("Refresh now", null, glyph(XmbView.G_PLAY), new Runnable() {
                    @Override
                    public void run() {
                        refreshData(true);
                        xmb.notice("Refreshing weather");
                    }
                }, null));
                return l;
            }
        };
    }

    private XmbView.ListBuilder calendarBuilder() {
        return linksBuilder("calendar_urls", "Calendar links", "Add a calendar link",
                "Paste a public ICS link (Apple Calendar: Share Calendar > Public Calendar)", XmbView.G_CAL);
    }

    private XmbView.ListBuilder webPagesBuilder() {
        return linksBuilder("web_sites", "Web pages", "Add a web page",
                "An address like https://example.com, or Name|https://example.com", XmbView.G_MULTI);
    }

    private XmbView.ListBuilder playlistBuilder() {
        return linksBuilder("iptv_urls", "Playlist links", "Add a playlist link",
                "An M3U link, e.g. https://iptv-org.github.io/iptv/countries/us.m3u", XmbView.G_LIVE);
    }

    /** A list of saved links with Add, Remove (OK on a link) and Refresh. */
    private XmbView.ListBuilder linksBuilder(final String key, String title, final String addTitle, final String hint,
                                             final int g) {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                l.add(new XmbView.Item(addTitle, "Type it here, or use Phone setup", glyph(g), new Runnable() {
                    @Override
                    public void run() {
                        askText(addTitle, "", hint, new TextResult() {
                            @Override
                            public void ok(String text) {
                                List<String> now = key.equals("iptv_urls") ? store.iptvUrls() : store.lines(key);
                                now = new ArrayList<>(now);
                                now.add(text.trim());
                                store.putLines(key, now);
                                settingsChanged();
                                xmb.notice("Link added");
                            }
                        });
                    }
                }, null));
                List<String> urls = key.equals("iptv_urls") ? store.iptvUrls() : store.lines(key);
                for (final String url : urls) {
                    l.add(new XmbView.Item(shorten(url), "Press OK to remove", glyph(XmbView.G_PIN), new Runnable() {
                        @Override
                        public void run() {
                            confirm("Remove this link?", url, new Runnable() {
                                @Override
                                public void run() {
                                    List<String> now = new ArrayList<>(key.equals("iptv_urls") ? store.iptvUrls() : store.lines(key));
                                    now.remove(url);
                                    store.putLines(key, now);
                                    settingsChanged();
                                    xmb.notice("Link removed");
                                }
                            });
                        }
                    }, null));
                }
                l.add(new XmbView.Item("Refresh now", null, glyph(XmbView.G_PLAY), new Runnable() {
                    @Override
                    public void run() {
                        refreshData(true);
                        xmb.notice("Refreshing");
                    }
                }, null));
                return l;
            }
        };
    }

    private static String shorten(String url) {
        String s = url.replaceFirst("^(https?|webcal)://", "");
        return s.length() > 48 ? s.substring(0, 45) + "..." : s;
    }

    private XmbView.ListBuilder phoneBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                l.add(new XmbView.Item("Phone setup", "Change every setting from a phone or computer", glyph(XmbView.G_PHONE), new Runnable() {
                    @Override
                    public void run() {
                        if (phone.running()) {
                            phone.stop();
                            xmb.notice("Phone setup off");
                        } else {
                            phone.start();
                            xmb.notice(phone.running() ? "Phone setup on" : "Couldn't start phone setup");
                        }
                        reload();
                    }
                }, null).value(phone.running() ? "On" : "Off"));
                if (phone.running()) {
                    l.add(info("Open this on your phone or computer", "Use the same Wi-Fi network", glyph(XmbView.G_INFO))
                            .value(phone.address()));
                    l.add(info("PIN", "Enter it on the page", glyph(XmbView.G_INFO)).value(phone.pin()));
                    l.add(info("Turns off in", "Or switch it off above", glyph(XmbView.G_CLOCK))
                            .value(phone.minutesLeft() + " min"));
                }
                return l;
            }
        };
    }

    private XmbView.ListBuilder aboutBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                Drawable i = glyph(XmbView.G_INFO);
                l.add(info("Home", "A cross media bar launcher for this TV", i));
                l.add(info("Design", "Layout, colour themes and ribbon follow RetroArch's XMB (libretro/RetroArch)", i));
                l.add(info("Weather data", "Open-Meteo", i));
                l.add(info("Channels loaded", null, i).value(String.valueOf(channels.size())));
                l.add(info("Calendar events", null, i).value(String.valueOf(events.size())));
                l.add(info("Tips", "Hold OK (or Menu) on an app, input or channel for its options. Back leaves a submenu.", i));
                return l;
            }
        };
    }

    /** Hold OK on an input: switch to it full screen. */
    private void inputMenu(final TvInputInfo info) {
        final String id = info.getId();
        xmb.pushList(inputName(info), XmbView.G_INPUT, new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                l.add(new XmbView.Item("Switch to this input", "Full screen", glyph(XmbView.G_INPUT), new Runnable() {
                    @Override
                    public void run() {
                        switchInput(id);
                    }
                }, null));
                return l;
            }
        });
    }

    /** Hold OK on a channel: play it or favorite it. */
    private void channelMenu(final List<IptvPlaylist.Channel> list, final int at) {
        final IptvPlaylist.Channel ch = list.get(at);
        xmb.pushList(ch.name, XmbView.G_LIVE, new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                l.add(new XmbView.Item("Play full screen", null, glyph(XmbView.G_PLAY), new Runnable() {
                    @Override
                    public void run() {
                        playChannels(list, at);
                    }
                }, null));
                final String key = chKey(ch);
                final List<String> favs = store.lines("ch_favs");
                final boolean isFav = favs.contains(key);
                l.add(new XmbView.Item(isFav ? "Remove from favorites" : "Add to favorites", null,
                        glyph(XmbView.G_STAR), new Runnable() {
                    @Override
                    public void run() {
                        List<String> now = new ArrayList<>(favs);
                        if (isFav) now.remove(key);
                        else now.add(key);
                        store.putLines("ch_favs", now);
                        xmb.notice(isFav ? ch.name + " removed from favorites" : ch.name + " added to favorites");
                        reload();
                    }
                }, null));
                return l;
            }
        });
    }

    // ---------------------------------------------------------------- Dialogs

    private interface TextResult {
        void ok(String text);
    }

    /** Ask for a line of text with the TV's on-screen keyboard. */
    private void askText(String title, String current, String hint, final TextResult result) {
        XmbDialog.text(this, title, current, hint, new XmbDialog.Text() {
            @Override
            public void onText(String text) {
                result.ok(text);
            }
        });
    }

    private void confirm(String title, String message, final Runnable yes) {
        XmbDialog.confirm(this, title, message, "Remove", yes);
    }

    // ---------------------------------------------------------------- Inputs helpers

    private static Drawable glyph(int g) {
        return new XmbView.GlyphDrawable(g, 0);
    }

    private List<TvInputInfo> connectedInputs() {
        List<TvInputInfo> list = new ArrayList<>();
        if (tvInputs == null) return list;
        for (TvInputInfo info : tvInputs.getTvInputList()) {
            if (!info.isPassthroughInput()) continue; // skip tuner / channel based inputs
            if (tvInputs.getInputState(info.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED) continue;
            list.add(info);
        }
        Collections.sort(list, new Comparator<TvInputInfo>() {
            @Override
            public int compare(TvInputInfo a, TvInputInfo b) {
                return inputName(a).compareToIgnoreCase(inputName(b));
            }
        });
        return list;
    }

    private String inputName(TvInputInfo info) {
        if (info.getId().endsWith(MultiviewActivity.APPLE_TV_SUFFIX)) return "Apple TV";
        CharSequence custom = info.loadCustomLabel(this);
        CharSequence name = custom != null && custom.length() > 0 ? custom : info.loadLabel(this);
        return String.valueOf(name);
    }

    private String stateText(String id) {
        switch (tvInputs.getInputState(id)) {
            case TvInputManager.INPUT_STATE_CONNECTED:
                return "Connected";
            case TvInputManager.INPUT_STATE_CONNECTED_STANDBY:
                return "Nothing playing";
            default:
                return "No device";
        }
    }

    private void switchInput(String inputId) {
        startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- Apps

    private static class App {
        final String pkg;
        final String name;
        final ComponentName component;
        final Drawable icon;

        App(String pkg, String name, ComponentName component, Drawable icon) {
            this.pkg = pkg;
            this.name = name;
            this.component = component;
            this.icon = icon;
        }
    }

    private List<App> loadApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || seen.contains(pkg) || isHidden(pkg)) continue;
            seen.add(pkg);
            apps.add(new App(pkg, String.valueOf(ri.loadLabel(pm)),
                    new ComponentName(pkg, ri.activityInfo.name), ri.loadIcon(pm)));
        }
        Collections.sort(apps, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                int ra = rank(a.pkg);
                int rb = rank(b.pkg);
                if (ra != rb) return ra - rb;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return apps;
    }

    private static boolean isHidden(String pkg) {
        if (!pkg.startsWith(HIDDEN_PREFIX)) return false;
        for (String ok : ALLOWED_VT) if (ok.equals(pkg)) return false;
        return true;
    }

    private static int rank(String pkg) {
        if (pkg.equals(SETTINGS)) return 10000;
        return 1000;
    }
}
