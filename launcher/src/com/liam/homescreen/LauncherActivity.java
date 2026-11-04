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
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;
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
 * items, OK opens or changes, holding OK opens the item's quick menu, Back leaves a submenu.
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
    /** OEM junk / telemetry packages to keep out of Apps (useful Hisense apps stay visible). */
    private static final String[] HIDDEN_PACKAGES = {
            "com.vt.tv.evservice",
            "com.vt.tv.smartservice",
    };

    private static final long WEATHER_EVERY_MS = 15 * 60 * 1000L;
    private static final long CALENDAR_EVERY_MS = 10 * 60 * 1000L;
    /** How often playlists are re-fetched to pick up new channels. */
    private static final long IPTV_EVERY_MS = 60 * 60 * 1000L;
    /** While Home is open, re-check the IPTV timer this often. */
    private static final long IPTV_TICK_MS = 15 * 60 * 1000L;
    /** Wait this long with no remote input before the sleep clock. */
    private static final long AMBIENT_IDLE_MS = 5 * 60 * 1000L;
    /** Menu → clock swap (must finish before the long brightness dim). */
    private static final long AMBIENT_UI_MS = 700L;
    /** Slow panel brightness fade after the clock owns the screen. */
    private static final long AMBIENT_DIM_MS = 5 * 60 * 1000L;
    private static final long AMBIENT_WAKE_MS = 500L;
    /** Don't burn a vsync tick for five minutes — brightness steps are fine at ~8 Hz. */
    private static final long AMBIENT_DIM_TICK_MS = 125L;
    private static final long AMBIENT_CLOCK_TICK_MS = 30 * 1000L;
    /** Dim target as a fraction of full brightness (0 = black, 1 = max). */
    private static final float AMBIENT_BRIGHTNESS = 0.08f;
    private static final int CALENDAR_DAYS = 14;
    private static final int RECENT_MAX = 12;

    private XmbView xmb;
    private FrameLayout root;
    private View ambientScrim;
    private WidgetView ambient;
    private EmuPad emuPad;
    private boolean searching;
    private final StringBuilder searchQuery = new StringBuilder();
    private boolean ambientOn;
    private boolean ambientAnimating;
    /** True while OK/Enter is held and a long-press Options callback is pending. */
    private boolean okLongArmed;
    private final Runnable okLongPress = new Runnable() {
        @Override
        public void run() {
            if (!okLongArmed || ambientOn) return;
            okLongArmed = false;
            xmb.longActivate();
            bumpIdle();
        }
    };
    /** Hold Back to jump all the way out of nested menus. */
    private boolean backLongArmed;
    private final Runnable backLongPress = new Runnable() {
        @Override
        public void run() {
            if (!backLongArmed || ambientOn) return;
            backLongArmed = false;
            searching = false;
            xmb.closeAll();
            xmb.notice("Back to main menu");
            bumpIdle();
        }
    };
    private float ambientBrightFrom = 1f;
    private float ambientBrightTo = AMBIENT_BRIGHTNESS;
    private int ambientSystemBrightSaved = -1;
    private int ambientSystemModeSaved = -1;
    private boolean ambientSystemBrightActive;
    private long ambientAnimStart;
    private long ambientAnimMs = AMBIENT_DIM_MS;
    private final Runnable showAmbient = new Runnable() {
        @Override
        public void run() {
            enterAmbient();
        }
    };
    private final Runnable ambientAnimTick = new Runnable() {
        @Override
        public void run() {
            if (!ambientAnimating) return;
            long elapsed = SystemClock.uptimeMillis() - ambientAnimStart;
            if (ambientOn) {
                float uiT = smoothstep(Math.min(1f, elapsed / (float) AMBIENT_UI_MS));
                float dimT = smoothstep(Math.min(1f, elapsed / (float) AMBIENT_DIM_MS));
                applyAmbientSleep(uiT, dimT);
                if (elapsed < AMBIENT_DIM_MS) {
                    long tick = elapsed < AMBIENT_UI_MS ? 16L : AMBIENT_DIM_TICK_MS;
                    ui.postDelayed(this, tick);
                    return;
                }
                ambientAnimating = false;
                return;
            }
            float t = smoothstep(Math.min(1f, elapsed / (float) ambientAnimMs));
            applyAmbientWake(t);
            if (elapsed < ambientAnimMs) {
                ui.postDelayed(this, 16L);
                return;
            }
            ambientAnimating = false;
            finishExitAmbient();
        }
    };
    private final Runnable ambientClockTick = new Runnable() {
        @Override
        public void run() {
            if (!ambientOn || ambient == null) return;
            ambient.invalidate();
            ui.postDelayed(this, AMBIENT_CLOCK_TICK_MS);
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
    /** Every library channel, including dead and unchecked; {@link #channels} is what's worth showing. */
    private List<IptvPlaylist.Channel> allChannels = new ArrayList<>();
    private int dbSeen = -1;
    private long iptvAt;
    private final Runnable iptvTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || !resumed) return;
            refreshData(false);
            ui.postDelayed(this, IPTV_TICK_MS);
        }
    };

    // Live preview: a muted channel card on Home while the Live category is focused.
    private FrameLayout previewCard;
    private VideoView previewVideo;
    private TextView previewLabel;
    private boolean previewShown;
    private boolean previewPrepared;
    private int previewDwell;
    private String previewUrl;
    private boolean resumed;
    private final Runnable previewTick = new Runnable() {
        @Override
        public void run() {
            updatePreview();
            if (resumed) ui.postDelayed(this, 500);
        }
    };

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
        buildPreview();
        ambientScrim = new View(this);
        ambientScrim.setBackgroundColor(0xFF000000);
        ambientScrim.setAlpha(0f);
        ambientScrim.setVisibility(View.GONE);
        ambientScrim.setClickable(false);
        ambientScrim.setFocusable(false);
        root.addView(ambientScrim, new FrameLayout.LayoutParams(-1, -1));
        ambient = new WidgetView(this);
        ambient.setVisibility(View.GONE);
        ambient.setAlpha(0f);
        ambient.setClickable(true);
        ambient.setFocusable(false);
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        // Do not make root focusable — a focused ViewGroup eats DPAD for focus-search and
        // keyboard/emulator arrows never reach the XMB handlers.
        if (EmuPad.needed()) {
            emuPad = new EmuPad(this);
            emuPad.setListener(new EmuPad.Listener() {
                @Override
                public void onPad(int keyCode) {
                    KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
                    KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
                    dispatchKeyEvent(down);
                    dispatchKeyEvent(up);
                }
            });
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            plp.setMargins(dp(24), 0, 0, dp(24));
            root.addView(emuPad, plp);
        }
        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
        resumed = false;
        ui.removeCallbacks(previewTick);
        ui.removeCallbacks(iptvTick);
        stopPreview();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        ui.removeCallbacks(showAmbient);
        ui.removeCallbacks(ambientAnimTick);
        ui.removeCallbacks(ambientClockTick);
        exitAmbientImmediate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(previewTick);
        ui.postDelayed(previewTick, 500);
        ui.removeCallbacks(iptvTick);
        ui.postDelayed(iptvTick, IPTV_TICK_MS);
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
        xmb.setBackgroundSpeed(parseFloatPref("bgspeed", 0.45f));
        xmb.setScaleFactor(parseFloatPref("size", 1.2f));
        xmb.setIconMode(store.get("icons", "auto"));
        xmb.setShadows(store.bool("shadows", true));
        xmb.setFrosted(store.bool("frost", true));
        xmb.setHints(store.bool("hints", true));
        xmb.setAnimMs(parseIntPref("anim", 180));
        xmb.setClock24(store.bool("clock24", false));
        if (store.bool("sounds", false)) {
            if (sfx == null) sfx = new Sfx();
        } else if (sfx != null) {
            sfx.release();
            sfx = null;
        }
        layoutPreview();
    }

    private float parseFloatPref(String key, float fallback) {
        try {
            return Float.parseFloat(store.get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int parseIntPref(String key, int fallback) {
        try {
            return Integer.parseInt(store.get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- Remote / keyboard keys

    /**
     * Handle nav at the activity gate so focused children (and emulator DPAD focus-search)
     * cannot swallow arrows before the XMB sees them.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleNavDown(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_UP && handleNavUp(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handleNavDown(KeyEvent event) {
        int raw = event.getKeyCode();
        int keyCode = NavKeys.navCode(raw, searching);
        if (ambientOn) {
            exitAmbient();
            bumpIdle();
            return true;
        }
        bumpIdle();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Single Back steps out one menu; hold Back clears the whole stack.
            backLongArmed = true;
            ui.removeCallbacks(backLongPress);
            ui.postDelayed(backLongPress, android.view.ViewConfiguration.getLongPressTimeout());
            return true;
        }
        // Launcher-only shortcuts (not remapped globally — would surprise other screens).
        if (!searching && raw == KeyEvent.KEYCODE_H) {
            searching = false;
            xmb.goHome();
            return true;
        }
        if (!searching && raw == KeyEvent.KEYCODE_G) {
            openGuideFromKey();
            return true;
        }
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
        // While typing a search, Backspace edits the query (not menu-back).
        if (searching && (raw == KeyEvent.KEYCODE_DEL || raw == KeyEvent.KEYCODE_FORWARD_DEL)) {
            if (searchQuery.length() > 0) {
                searchQuery.setLength(searchQuery.length() - 1);
                refreshSearch();
                return true;
            }
            searching = false;
            bumpIdle();
            return true;
        }
        if (raw == keyCode || searching) {
            char typed = typeChar(raw, event);
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
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // In a submenu, Left steps back a level (classic XMB).
                if (xmb.inSubmenu() || searching) {
                    onBackPressed();
                    return true;
                }
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
                // Own long-press timer — we consume keys before Activity.onKeyDown tracking.
                okLongArmed = true;
                ui.removeCallbacks(okLongPress);
                ui.postDelayed(okLongPress, android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            default:
                return false;
        }
    }

    private boolean handleNavUp(KeyEvent event) {
        int keyCode = NavKeys.navCode(event.getKeyCode(), searching);
        if (ambientOn) return true;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            ui.removeCallbacks(backLongPress);
            if (backLongArmed) {
                backLongArmed = false;
                onBackPressed();
                bumpIdle();
            }
            return true;
        }
        if (NavKeys.isActivate(keyCode)) {
            ui.removeCallbacks(okLongPress);
            if (okLongArmed) {
                okLongArmed = false;
                xmb.activate();
                bumpIdle();
            }
            return true;
        }
        return false;
    }

    /** Keyboard G → open the live guide without hunting the Live column. */
    private void openGuideFromKey() {
        if (channels == null || channels.isEmpty()) {
            xmb.notice("No channels loaded yet");
            return;
        }
        openGuide(channels, -1);
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

    // ---------------------------------------------------------------- Live preview

    private void buildPreview() {
        previewCard = new FrameLayout(this);
        previewCard.setVisibility(View.GONE);
        previewCard.setAlpha(0f);
        previewCard.setClickable(false);
        previewCard.setFocusable(false);
        previewVideo = new VideoView(this);
        previewCard.addView(previewVideo, new FrameLayout.LayoutParams(-1, -1));
        previewVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setVolume(0f, 0f);
                mp.start();
                previewPrepared = true;
                if (previewShown) previewCard.animate().alpha(1f).setDuration(350).start();
            }
        });
        previewVideo.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                stopPreview();
                previewDwell = -20; // don't retry straight away
                return true;
            }
        });
        View border = new View(this);
        border.setTag("preview-border");
        previewCard.addView(border, new FrameLayout.LayoutParams(-1, -1));
        previewLabel = new TextView(this);
        previewLabel.setTextColor(XmbView.iconColor);
        previewLabel.setTextSize(13);
        previewLabel.setShadowLayer(4, 0, 1, 0xCC000000);
        previewLabel.setSingleLine(true);
        previewLabel.setPadding(dpi(12), 0, dpi(12), dpi(10));
        previewCard.addView(previewLabel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        root.addView(previewCard, new FrameLayout.LayoutParams(dpi(300), dpi(169), Gravity.TOP | Gravity.END));
        layoutPreview();
    }

    /** Keep the Live preview card aligned with XMB scale and Glass chrome. */
    private void layoutPreview() {
        if (previewCard == null) return;
        float unit = xmb != null && xmb.menuUnit() > 0.01f
                ? xmb.menuUnit()
                : getResources().getDisplayMetrics().heightPixels / 1080f * parseFloatPref("size", 1.2f);
        int pipW = Math.round(290 * unit);
        int pipH = Math.round(pipW * 9f / 16f);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewCard.getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(pipW, pipH, Gravity.TOP | Gravity.END);
        lp.width = pipW;
        lp.height = pipH;
        lp.gravity = Gravity.TOP | Gravity.END;
        // Sit in the open sky above the ribbon, clear of greeting / clock.
        float ribbon = xmb != null ? xmb.ribbonY() : 272 * unit;
        lp.topMargin = Math.max(dpi(24), Math.round(ribbon - pipH - 28 * unit));
        lp.rightMargin = Math.round(36 * unit);
        previewCard.setLayoutParams(lp);

        View border = previewCard.findViewWithTag("preview-border");
        if (border != null) {
            GradientDrawable stroke = new GradientDrawable();
            stroke.setColor(0);
            stroke.setCornerRadius(Math.max(4, 6 * unit));
            stroke.setStroke(Math.max(1, Math.round(1.5f * unit)), Glass.accentStroke(0x99000000));
            border.setBackground(stroke);
        }
        if (previewLabel != null) {
            previewLabel.setTextColor(XmbView.iconColor);
            previewLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * unit);
            int pad = Math.round(10 * unit);
            previewLabel.setPadding(pad, 0, pad, pad);
        }
    }

    private int dpi(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The channel to preview: the last one watched, else the first that works. */
    private IptvPlaylist.Channel previewChannel() {
        if (channels.isEmpty()) return null;
        String last = store.get("last_channel_url", null);
        if (last != null) {
            for (IptvPlaylist.Channel ch : channels) if (ch.url.equals(last)) return ch;
        }
        for (IptvPlaylist.Channel ch : channels) if (ch.status == ChannelDb.WORKING) return ch;
        return channels.get(0);
    }

    private void updatePreview() {
        boolean want = resumed && !ambientOn && store.bool("live_preview", true)
                && xmb != null && xmb.liveFocused() && !channels.isEmpty();
        if (!want) {
            if (previewDwell > 0) previewDwell = 0;
            if (previewShown) stopPreview();
            return;
        }
        if (previewShown) return;
        if (++previewDwell < 2) return; // let it settle on Live first
        IptvPlaylist.Channel ch = previewChannel();
        if (ch == null) return;
        previewShown = true;
        previewPrepared = false;
        previewUrl = ch.url;
        layoutPreview();
        previewLabel.setText("\u25CF  " + ch.name);
        previewCard.setVisibility(View.VISIBLE);
        previewCard.animate().cancel();
        previewCard.setAlpha(0f);
        previewVideo.setVideoURI(Uri.parse(ch.url));
    }

    private void stopPreview() {
        if (previewCard == null) return;
        previewShown = false;
        previewPrepared = false;
        previewCard.animate().cancel();
        previewCard.setAlpha(0f);
        previewCard.setVisibility(View.GONE);
        previewVideo.stopPlayback();
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
                                if (isFinishing()) return;
                                weather = r;
                                weatherError = null;
                                reload();
                            }
                        });
                    } catch (final Exception e) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
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
                            if (isFinishing()) return;
                            events = list;
                            calendarLoaded = true;
                            reload();
                        }
                    });
                }
            });
        }

        final List<String> playlists = store.iptvUrls();
        final ChannelDb db = ChannelDb.get(this);
        // One-shot: re-fetch playlists so series/movies leave the library under live-only rules.
        if (!store.bool("iptv_live_only_v1", false)) {
            store.putBool("iptv_live_only_v1", true);
            iptvAt = 0;
            force = true;
        }
        if (iptvAt <= 0) {
            try {
                iptvAt = Long.parseLong(store.get("iptv_last_sync", "0"));
            } catch (NumberFormatException ignored) {
                iptvAt = 0;
            }
        }
        if (playlists.isEmpty()) {
            channels = new ArrayList<>();
            allChannels = new ArrayList<>();
            iptvError = null;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    store.putLines("iptv_discovered", new ArrayList<String>());
                    db.retainSources(playlists);
                    db.scrubNonLive();
                }
            });
        } else {
            final boolean fetch = force || now - iptvAt > IPTV_EVERY_MS;
            if (fetch) iptvAt = now;
            if (fetch || ChannelDb.version() != dbSeen) {
                final boolean doFetch = fetch;
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        IptvFetch.Result sync = null;
                        String err = null;
                        if (doFetch) {
                            if (channels.isEmpty()) postLibrary(db, null); // show what's stored while we fetch
                            sync = IptvFetch.sync(db, store, playlists);
                            err = sync.error;
                            if (sync.foundNew() || sync.anyFetched || sync.error == null) {
                                iptvAt = System.currentTimeMillis();
                            } else {
                                iptvAt = 0; // try again soon after a total failure
                            }
                        } else {
                            db.scrubNonLive();
                        }
                        postLibrary(db, err);
                        final IptvFetch.Result noticed = sync;
                        if (doFetch && noticed != null && noticed.foundNew()) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isFinishing() && xmb != null) xmb.notice(IptvFetch.summary(noticed));
                                }
                            });
                        }
                        if (doFetch && !ChannelCheck.running()) startCheck();
                    }
                });
            }
        }
    }

    /** Read the library on a background thread and publish it. */
    private void postLibrary(ChannelDb db, final String err) {
        final int v = ChannelDb.version();
        final List<IptvPlaylist.Channel> loaded = db.loadAll();
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                dbSeen = v;
                allChannels = loaded;
                channels = playable(loaded);
                iptvError = channels.isEmpty() ? err : null;
                if (loaded.isEmpty() && err != null) iptvAt = 0;
                reload();
            }
        });
    }

    /** Channels worth showing: live only, not known dead, and (if chosen) only ones that passed a check. */
    private List<IptvPlaylist.Channel> playable(List<IptvPlaylist.Channel> all) {
        boolean unchecked = store.bool("ch_show_unchecked", true);
        List<IptvPlaylist.Channel> out = new ArrayList<>();
        for (IptvPlaylist.Channel ch : all) {
            if (!IptvPlaylist.isLiveChannel(ch)) continue;
            if (ch.status == ChannelDb.WORKING || (ch.status == ChannelDb.UNKNOWN && unchecked)) out.add(ch);
        }
        return out;
    }

    private void startCheck() {
        ChannelCheck.start(this, new ChannelCheck.Listener() {
            @Override
            public void progress(int done, int total) {
                if (done % 100 == 0) ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) refreshData(false);
                    }
                });
            }

            @Override
            public void finished(final int working, final int dead, final boolean cancelled) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        refreshData(false);
                        if (!cancelled) {
                            xmb.notice(dead == 0 ? "Channel check done" : dead + " channels hidden \u2014 they don't play");
                        }
                    }
                });
            }
        });
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
        applyStoredBrightness();
        refreshData(false);
        reload();
    }

    /** Apply panel brightness saved from Picture or Phone setup (no-op if unset / no permission). */
    private void applyStoredBrightness() {
        Choices.Choice c = Choices.brightness();
        if (!store.has(c.key)) return;
        try {
            applyBrightnessPercent(Integer.parseInt(store.get(c.key, c.def)), false);
        } catch (NumberFormatException ignored) {
        }
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
        cat.items.add(new XmbView.Item("Channels", "Browse by group", glyph(XmbView.G_LIVE), new Runnable() {
            @Override
            public void run() {
                xmb.pushList("Channels", XmbView.G_LIVE, channelGroupsBuilder());
            }
        }, null));
        return cat;
    }

    private XmbView.ListBuilder libraryBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                final ChannelDb db = ChannelDb.get(LauncherActivity.this);
                final int[] n = db.counts();
                int total = n[0] + n[1] + n[2];
                List<XmbView.Item> l = new ArrayList<>();
                Drawable icon = glyph(XmbView.G_LIVE);
                l.add(info("Library", n[1] + " working  \u00b7  " + n[0] + " not checked  \u00b7  " + n[2] + " unavailable",
                        glyph(XmbView.G_INFO)).value(total + " channels"));
                if (ChannelCheck.running()) {
                    l.add(new XmbView.Item("Checking channels...", ChannelCheck.done() + " of " + ChannelCheck.total()
                            + "  \u00b7  OK to stop", glyph(XmbView.G_PLAY), new Runnable() {
                        @Override
                        public void run() {
                            ChannelCheck.cancel();
                            xmb.notice("Stopping the check");
                            reload();
                        }
                    }, null));
                } else {
                    l.add(new XmbView.Item("Check channels", n[0] > 0 ? n[0] + " not checked yet"
                            : "Test anything not checked this week", glyph(XmbView.G_PLAY), new Runnable() {
                        @Override
                        public void run() {
                            xmb.notice(ChannelCheck.running() ? "Already checking" : "Checking in the background");
                            startCheck();
                            reload();
                        }
                    }, null));
                }
                l.add(new XmbView.Item("Find new channels", "Re-download playlists and nested lists now",
                        glyph(XmbView.G_LIVE), new Runnable() {
                    @Override
                    public void run() {
                        iptvAt = 0;
                        xmb.notice("Looking for new channels...");
                        refreshData(true);
                    }
                }, null));
                if (n[2] > 0) {
                    l.add(new XmbView.Item("Check unavailable again", n[2] + " channels", glyph(XmbView.G_PLAY), new Runnable() {
                        @Override
                        public void run() {
                            io.execute(new Runnable() {
                                @Override
                                public void run() {
                                    db.resetDead();
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            startCheck();
                                            refreshData(false);
                                            xmb.notice("Checking again");
                                        }
                                    });
                                }
                            });
                        }
                    }, null));
                    l.add(new XmbView.Item("Remove unavailable", "Delete them from the library", glyph(XmbView.G_PIN), new Runnable() {
                        @Override
                        public void run() {
                            confirm("Remove " + n[2] + " channels?", "They didn't play when checked.", new Runnable() {
                                @Override
                                public void run() {
                                    io.execute(new Runnable() {
                                        @Override
                                        public void run() {
                                            db.deleteDead();
                                            ui.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    refreshData(false);
                                                    xmb.notice("Removed");
                                                }
                                            });
                                        }
                                    });
                                }
                            });
                        }
                    }, null));
                }
                l.add(new XmbView.Item("Live preview on Home", "Muted channel card while Live is selected",
                        icon, new Runnable() {
                    @Override
                    public void run() {
                        store.putBool("live_preview", !store.bool("live_preview", true));
                        if (!store.bool("live_preview", true)) stopPreview();
                        reload();
                    }
                }, null).value(store.bool("live_preview", true) ? "On" : "Off"));
                l.add(new XmbView.Item("Show unchecked channels", "Off hides channels until they pass a check",
                        icon, new Runnable() {
                    @Override
                    public void run() {
                        store.putBool("ch_show_unchecked", !store.bool("ch_show_unchecked", true));
                        refreshData(false);
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                postLibrary(db, null);
                            }
                        });
                    }
                }, null).value(store.bool("ch_show_unchecked", true) ? "On" : "Off"));
                l.add(new XmbView.Item("Browse library", "Every stored channel, with its check result", icon, new Runnable() {
                    @Override
                    public void run() {
                        xmb.pushList("Library", XmbView.G_LIVE, libraryBrowseBuilder());
                    }
                }, null));
                for (Map.Entry<String, Integer> e : db.sourceCounts().entrySet()) {
                    l.add(info(shorten(e.getKey()), "Playlist", glyph(XmbView.G_PIN)).value(e.getValue() + " channels"));
                }
                return l;
            }
        };
    }

    private XmbView.ListBuilder libraryBrowseBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                String[] names = {"Not checked", "Working", "Unavailable"};
                int[] order = {ChannelDb.WORKING, ChannelDb.UNKNOWN, ChannelDb.DEAD};
                for (final int status : order) {
                    final List<IptvPlaylist.Channel> bucket = new ArrayList<>();
                    for (IptvPlaylist.Channel ch : allChannels) if (ch.status == status) bucket.add(ch);
                    if (bucket.isEmpty()) continue;
                    final String title = names[status];
                    l.add(new XmbView.Item(title, bucket.size() + " channels", glyph(XmbView.G_LIVE), new Runnable() {
                        @Override
                        public void run() {
                            xmb.pushList(title, XmbView.G_LIVE, channelGroupsBuilder(bucket, true));
                        }
                    }, null));
                }
                return l;
            }
        };
    }

    private XmbView.ListBuilder channelGroupsBuilder() {
        return channelGroupsBuilder(channels, false);
    }

    private XmbView.ListBuilder channelGroupsBuilder(final List<IptvPlaylist.Channel> source, final boolean showStatus) {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                Map<String, List<IptvPlaylist.Channel>> groups = new LinkedHashMap<>();
                for (IptvPlaylist.Channel ch : source) {
                    String g = ch.group == null || ch.group.isEmpty() ? "Other" : ch.group;
                    List<IptvPlaylist.Channel> bucket = groups.get(g);
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        groups.put(g, bucket);
                    }
                    bucket.add(ch);
                }
                if (groups.size() <= 1) {
                    for (IptvPlaylist.Channel ch : source) l.add(statusItem(ch, source, showStatus));
                    return l;
                }
                for (final Map.Entry<String, List<IptvPlaylist.Channel>> e : groups.entrySet()) {
                    final List<IptvPlaylist.Channel> list = e.getValue();
                    l.add(new XmbView.Item(e.getKey(), list.size() + " channels", glyph(XmbView.G_LIVE), new Runnable() {
                        @Override
                        public void run() {
                            xmb.pushList(e.getKey(), XmbView.G_LIVE, new XmbView.ListBuilder() {
                                @Override
                                public List<XmbView.Item> build() {
                                    List<XmbView.Item> items = new ArrayList<>();
                                    for (IptvPlaylist.Channel ch : list) items.add(statusItem(ch, list, showStatus));
                                    return items;
                                }
                            });
                        }
                    }, null));
                }
                return l;
            }
        };
    }

    private XmbView.Item statusItem(IptvPlaylist.Channel ch, List<IptvPlaylist.Channel> list, boolean showStatus) {
        XmbView.Item item = channelItem(ch, list);
        if (showStatus) {
            item.value(ch.status == ChannelDb.WORKING ? "Working" : ch.status == ChannelDb.DEAD ? "Unavailable" : "Not checked");
        }
        return item;
    }

    private void openGuide(List<IptvPlaylist.Channel> list, int index) {
        if (list == channels && !allChannels.isEmpty() && store.bool("ch_show_unchecked", true)) {
            int mapped = index >= 0 && index < list.size() ? allChannels.indexOf(list.get(index)) : -1;
            list = allChannels;
            index = mapped;
        }
        GuideActivity.channels = list;
        GuideActivity.startIndex = index;
        GuideActivity.openedFromPlayer = false;
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

    // ---- Idle ambient (clock only, gradual dim)

    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private void enterAmbient() {
        if (ambientOn || ambient == null || ambientScrim == null) return;
        ambientOn = true;
        searching = false;
        ambient.setClock24(store.bool("clock24", false));
        ambient.showWidget("clock");
        ambient.setAlpha(0f);
        ambient.setVisibility(View.VISIBLE);
        ambientScrim.setBackgroundColor(0xFF000000);
        ambientScrim.setAlpha(0f);
        ambientScrim.setVisibility(View.VISIBLE);
        ambientScrim.bringToFront();
        ambient.bringToFront();
        // Keep the emulator D-pad above the ambient clock so navigation still works.
        if (emuPad != null) emuPad.bringToFront();

        captureBrightnessForAmbient();
        ambientBrightTo = AMBIENT_BRIGHTNESS;
        ambientAnimMs = AMBIENT_DIM_MS;
        ambientAnimStart = SystemClock.uptimeMillis();
        ambientAnimating = true;
        ui.removeCallbacks(ambientAnimTick);
        ui.removeCallbacks(ambientClockTick);
        ui.post(ambientAnimTick);
        ui.postDelayed(ambientClockTick, AMBIENT_CLOCK_TICK_MS);
    }

    private void exitAmbient() {
        if (!ambientOn && !ambientAnimating) return;
        ambientOn = false;
        ui.removeCallbacks(ambientClockTick);
        // Bring the menu back under the clock before fading the overlay out.
        if (xmb != null) {
            xmb.setVisibility(View.VISIBLE);
            xmb.setAlpha(0f);
        }
        float cur = readCurrentBrightnessFraction();
        ambientBrightFrom = cur;
        ambientBrightTo = ambientSystemBrightSaved >= 0
                ? ambientSystemBrightSaved / 255f
                : 1f;
        ambientAnimMs = AMBIENT_WAKE_MS;
        ambientAnimStart = SystemClock.uptimeMillis();
        ambientAnimating = true;
        ui.removeCallbacks(ambientAnimTick);
        ui.post(ambientAnimTick);
    }

    /** Instant restore (activity stop / teardown) — no wake animation. */
    private void exitAmbientImmediate() {
        ambientOn = false;
        ambientAnimating = false;
        ui.removeCallbacks(ambientAnimTick);
        ui.removeCallbacks(ambientClockTick);
        finishExitAmbient();
    }

    private void finishExitAmbient() {
        if (ambient != null) {
            ambient.setVisibility(View.GONE);
            ambient.setAlpha(0f);
        }
        if (ambientScrim != null) {
            ambientScrim.setVisibility(View.GONE);
            ambientScrim.setAlpha(0f);
        }
        if (xmb != null) {
            xmb.setVisibility(View.VISIBLE);
            xmb.setAlpha(1f);
        }
        clearWindowBrightnessOverride();
        restoreSystemBrightness();
    }

    /** Never use window brightness — that only dims this app. Drive the panel via Settings.System. */
    private void clearWindowBrightnessOverride() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        }
    }

    private void captureBrightnessForAmbient() {
        clearWindowBrightnessOverride();
        ambientSystemBrightActive = false;
        ambientSystemBrightSaved = -1;
        ambientSystemModeSaved = -1;
        ambientBrightFrom = 1f;
        try {
            ambientSystemModeSaved = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            ambientSystemBrightSaved = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            ambientBrightFrom = Math.max(0.05f, Math.min(1f, ambientSystemBrightSaved / 255f));
            if (ensureWriteSettings()) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                ambientSystemBrightActive = true;
            }
        } catch (Exception ignored) {
        }
    }

    private float readCurrentBrightnessFraction() {
        try {
            int v = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            return Math.max(0.01f, Math.min(1f, v / 255f));
        } catch (Exception ignored) {
            return ambientBrightTo;
        }
    }

    private boolean ensureWriteSettings() {
        try {
            if (Settings.System.canWrite(this)) return true;
        } catch (Exception ignored) {
        }
        // One-shot prompt so the TV can accept system brightness changes from Home.
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Set the TV panel brightness (0..1 → Settings.System 1..255). */
    private void setPanelBrightnessFraction(float fraction) {
        if (!ambientSystemBrightActive) return;
        try {
            float b = Math.max(0.01f, Math.min(1f, fraction));
            int level = Math.max(1, Math.min(255, Math.round(b * 255f)));
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
        } catch (Exception ignored) {
        }
    }

    private void restoreSystemBrightness() {
        clearWindowBrightnessOverride();
        if (!ambientSystemBrightActive) {
            ambientSystemBrightSaved = -1;
            ambientSystemModeSaved = -1;
            return;
        }
        try {
            if (ambientSystemBrightSaved >= 0) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, ambientSystemBrightSaved);
            }
            if (ambientSystemModeSaved >= 0) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE, ambientSystemModeSaved);
            }
        } catch (Exception ignored) {
        }
        ambientSystemBrightActive = false;
        ambientSystemBrightSaved = -1;
        ambientSystemModeSaved = -1;
    }

    /** Swap to the clock; panel brightness eases down separately over AMBIENT_DIM_MS. */
    private void applyAmbientSleep(float uiT, float dimT) {
        if (ambientScrim != null) ambientScrim.setAlpha(uiT);
        if (ambient != null) ambient.setAlpha(uiT);
        if (xmb != null) {
            if (uiT >= 0.98f) {
                xmb.setVisibility(View.GONE);
                xmb.setAlpha(1f);
            } else {
                xmb.setVisibility(View.VISIBLE);
                xmb.setAlpha(1f - uiT);
            }
        }
        float b = ambientBrightFrom + (ambientBrightTo - ambientBrightFrom) * dimT;
        setPanelBrightnessFraction(b);
    }

    /** Fast wake: restore panel brightness and crossfade back to the menu. */
    private void applyAmbientWake(float t) {
        if (ambientScrim != null) ambientScrim.setAlpha(1f - t);
        if (ambient != null) ambient.setAlpha(1f - t);
        if (xmb != null) {
            xmb.setVisibility(View.VISIBLE);
            xmb.setAlpha(t);
        }
        float b = ambientBrightFrom + (ambientBrightTo - ambientBrightFrom) * t;
        setPanelBrightnessFraction(b);
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
        cat.items.add(new XmbView.Item("Open", "Up to four IPTV channels at once",
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
        if (cat.items.isEmpty()) {
            cat.items.add(info("No inputs", "Plug in HDMI to switch inputs here",
                    glyph(XmbView.G_INPUT)));
        }
        return cat;
    }

    // ---------------------------------------------------------------- Settings

    /**
     * System settings deep-links. Each row lists action fallbacks; only the first that resolves
     * on this TV is shown (Google TV / Hisense omit many phone-style screens).
     */
    private static final String[][] TV_SETTING_ROWS = {
            {"Network", "Wi-Fi and internet", String.valueOf(XmbView.G_WIFI),
                    Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS},
            {"Bluetooth", "Remotes, speakers, accessories", String.valueOf(XmbView.G_SOUND),
                    Settings.ACTION_BLUETOOTH_SETTINGS},
            {"Apps", "Installed apps and permissions", String.valueOf(XmbView.G_GRID),
                    Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS},
            {"Accounts", "Google and other accounts", String.valueOf(XmbView.G_PHONE),
                    Settings.ACTION_SYNC_SETTINGS},
            {"Date and time", "Clock and time zone", String.valueOf(XmbView.G_CLOCK),
                    Settings.ACTION_DATE_SETTINGS},
            {"Language", "Language and region", String.valueOf(XmbView.G_INFO),
                    Settings.ACTION_LOCALE_SETTINGS},
            {"Accessibility", "Captions and accessibility", String.valueOf(XmbView.G_INFO),
                    Settings.ACTION_ACCESSIBILITY_SETTINGS},
            {"Storage", "Free space and storage", String.valueOf(XmbView.G_LIST),
                    Settings.ACTION_INTERNAL_STORAGE_SETTINGS},
            {"Location", "Location services", String.valueOf(XmbView.G_PIN),
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS},
            {"Security", "Security and privacy", String.valueOf(XmbView.G_GEAR),
                    Settings.ACTION_SECURITY_SETTINGS},
            {"Casting", "Cast and display", String.valueOf(XmbView.G_MULTI),
                    Settings.ACTION_CAST_SETTINGS},
            {"Keyboard", "Keyboard and input", String.valueOf(XmbView.G_INFO),
                    Settings.ACTION_INPUT_METHOD_SETTINGS},
            {"Screensaver", "Ambient and screensaver", String.valueOf(XmbView.G_MOON),
                    Settings.ACTION_DREAM_SETTINGS},
            {"Device", "About this TV", String.valueOf(XmbView.G_INFO),
                    Settings.ACTION_DEVICE_INFO_SETTINGS},
            {"Home app", "Default launcher", String.valueOf(XmbView.G_GEAR),
                    Settings.ACTION_HOME_SETTINGS},
            {"Search", "Search settings", String.valueOf(XmbView.G_SEARCH),
                    Settings.ACTION_SEARCH_SETTINGS},
    };

    private XmbView.Cat settingsCat() {
        XmbView.Cat cat = new XmbView.Cat("Settings", XmbView.G_GEAR);

        Intent all = allTvSettingsIntent();
        if (all != null) {
            cat.items.add(settingItem("All TV settings", "Open the system settings app", XmbView.G_GEAR, all));
        }

        cat.items.add(submenu("Picture", "Brightness and display", XmbView.G_PICTURE, pictureBuilder()));
        cat.items.add(submenu("Sound", "Volume and output", XmbView.G_SOUND, soundBuilder()));

        for (String[] row : TV_SETTING_ROWS) {
            String title = row[0];
            String sub = row[1];
            int glyph = Integer.parseInt(row[2]);
            String[] actions = new String[row.length - 3];
            System.arraycopy(row, 3, actions, 0, actions.length);
            Intent intent = resolveSetting(actions);
            if (intent != null) cat.items.add(settingItem(title, sub, glyph, intent));
        }

        cat.items.add(submenu("Home screen", "Theme, size, background, sounds", XmbView.G_BRUSH, appearanceBuilder()));
        cat.items.add(submenu("Weather", "City and units", XmbView.G_SUN, weatherBuilder()));
        cat.items.add(submenu("Calendar", "Calendar links", XmbView.G_CAL, calendarBuilder()));
        cat.items.add(submenu("Live TV playlists", "IPTV playlist links", XmbView.G_LIVE, playlistBuilder()));
        cat.items.add(submenu("Channel library", "Stored channels and checks", XmbView.G_LIVE, libraryBuilder()));
        cat.items.add(submenu("Web pages", "Saved site list", XmbView.G_MULTI, webPagesBuilder()));
        cat.items.add(submenu("Phone setup", "Type settings on a phone or computer", XmbView.G_PHONE, phoneBuilder()));
        cat.items.add(submenu("About", "Credits and status", XmbView.G_INFO, aboutBuilder()));
        return cat;
    }

    /** Leanback Settings app first, then generic ACTION_SETTINGS. */
    private Intent allTvSettingsIntent() {
        PackageManager pm = getPackageManager();
        Intent leanback = pm.getLeanbackLaunchIntentForPackage(SETTINGS);
        if (leanback != null) return leanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent launch = pm.getLaunchIntentForPackage(SETTINGS);
        if (launch != null) return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return resolveSetting(Settings.ACTION_SETTINGS);
    }

    /** First action that an activity on this device can handle, or null. */
    private Intent resolveSetting(String... actions) {
        PackageManager pm = getPackageManager();
        for (String action : actions) {
            if (action == null || action.isEmpty()) continue;
            Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(pm) != null) return intent;
        }
        return null;
    }

    private void openSetting(Intent intent) {
        if (intent == null) {
            xmb.notice("That setting isn't available");
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            xmb.notice("That setting isn't available");
        }
    }

    private XmbView.Item settingItem(String title, String sub, int g, final Intent intent) {
        return new XmbView.Item(title, sub, glyph(g), new Runnable() {
            @Override
            public void run() {
                openSetting(intent);
            }
        }, null);
    }

    /** In-launcher Picture controls plus a deep link when the TV exposes one. */
    private XmbView.ListBuilder pictureBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                Choices.Choice bright = Choices.brightness();
                String cur = nearestBrightnessValue();
                String label = bright.labels[0];
                for (int i = 0; i < bright.values.length; i++) {
                    if (bright.values[i].equals(cur)) label = bright.labels[i];
                }
                l.add(new XmbView.Item("Brightness", "Panel level", glyph(XmbView.G_PICTURE), new Runnable() {
                    @Override
                    public void run() {
                        stepBrightness();
                        settingsChanged();
                    }
                }, null).value(label));
                Intent more = resolveSetting(Settings.ACTION_DISPLAY_SETTINGS);
                if (more != null) {
                    l.add(settingItem("More picture settings", "Mode, HDR, and the rest", XmbView.G_GEAR, more));
                }
                return l;
            }
        };
    }

    /** In-launcher Sound controls plus a deep link when the TV exposes one. */
    private XmbView.ListBuilder soundBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int vol = 0;
                int max = 15;
                boolean muted = false;
                if (am != null) {
                    max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
                }
                final int maxVol = max;
                l.add(new XmbView.Item("Volume", "OK raises · hold OK lowers", glyph(XmbView.G_SOUND), new Runnable() {
                    @Override
                    public void run() {
                        adjustVolume(+1);
                        settingsChanged();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        adjustVolume(-1);
                        settingsChanged();
                    }
                }).value(muted ? "Muted" : (vol + " / " + maxVol)));
                l.add(new XmbView.Item(muted ? "Unmute" : "Mute", null, glyph(XmbView.G_MUTE), new Runnable() {
                    @Override
                    public void run() {
                        toggleMute();
                        settingsChanged();
                    }
                }, null));
                Intent more = resolveSetting(Settings.ACTION_SOUND_SETTINGS);
                if (more != null) {
                    l.add(settingItem("More sound settings", "Output and equaliser", XmbView.G_GEAR, more));
                }
                return l;
            }
        };
    }

    private String nearestBrightnessValue() {
        int level = 191;
        try {
            level = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 191);
        } catch (Exception ignored) {
        }
        int pct = Math.round(level * 100f / 255f);
        String[] vals = Choices.BRIGHTNESS_VALUES;
        String best = vals[vals.length / 2];
        int bestDist = 999;
        for (String v : vals) {
            int d = Math.abs(Integer.parseInt(v) - pct);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }

    private void stepBrightness() {
        Choices.Choice c = Choices.brightness();
        String cur = nearestBrightnessValue();
        int at = 0;
        for (int i = 0; i < c.values.length; i++) if (c.values[i].equals(cur)) at = i;
        int n = (at + 1) % c.values.length;
        if (applyBrightnessPercent(Integer.parseInt(c.values[n]), true)) {
            store.put(c.key, c.values[n]);
            xmb.notice("Brightness: " + c.labels[n]);
        }
    }

    /**
     * Apply panel brightness percent (1–100) via Settings.System.
     * @param prompt if true, open the write-settings permission screen when needed
     */
    private boolean applyBrightnessPercent(int percent, boolean prompt) {
        boolean canWrite = false;
        try {
            canWrite = Settings.System.canWrite(this);
        } catch (Exception ignored) {
        }
        if (!canWrite) {
            if (prompt) {
                ensureWriteSettings();
                xmb.notice("Allow modify system settings for brightness");
            }
            return false;
        }
        try {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            int level = Math.max(1, Math.min(255, Math.round(percent * 255f / 100f)));
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
            return true;
        } catch (Exception e) {
            if (prompt) xmb.notice("Couldn't change brightness");
            return false;
        }
    }

    private void adjustVolume(int dir) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            xmb.notice("Volume isn't available");
            return;
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
        int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        xmb.notice("Volume: " + vol + " / " + max);
    }

    private void toggleMute() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            xmb.notice("Volume isn't available");
            return;
        }
        boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                muted ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI);
        xmb.notice(muted ? "Unmuted" : "Muted");
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
                // Shared with Phone setup via Choices so both UIs stay in sync.
                for (Choices.Choice c : Choices.appearance()) {
                    int g = "anim".equals(c.key) || "bgspeed".equals(c.key) ? XmbView.G_PLAY
                            : "size".equals(c.key) ? XmbView.G_PICTURE : XmbView.G_BRUSH;
                    l.add(cycle(c.key, c.title, c.values, c.labels, c.def, g));
                }
                for (Choices.Toggle t : Choices.toggles()) {
                    int g = "sounds".equals(t.key) ? XmbView.G_SOUND
                            : "clock24".equals(t.key) ? XmbView.G_CLOCK
                            : "hints".equals(t.key) ? XmbView.G_INFO : XmbView.G_BRUSH;
                    l.add(toggle(t.key, t.title, t.def, g));
                }
                l.add(new XmbView.Item("Reset home screen options", "Back to the defaults", glyph(XmbView.G_GEAR), new Runnable() {
                    @Override
                    public void run() {
                        for (Choices.Choice c : Choices.appearance()) store.remove(c.key);
                        for (Choices.Toggle t : Choices.toggles()) store.remove(t.key);
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

    /** The version set in the manifest (shown in About). */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private XmbView.ListBuilder aboutBuilder() {
        return new XmbView.ListBuilder() {
            @Override
            public List<XmbView.Item> build() {
                List<XmbView.Item> l = new ArrayList<>();
                Drawable i = glyph(XmbView.G_INFO);
                l.add(info("Home", "A cross media bar launcher for this TV", i).value("Version " + versionName()));
                l.add(info("Design","Layout, colour themes and ribbon follow RetroArch's XMB (libretro/RetroArch)", i));
                l.add(info("Weather data", "Open-Meteo", i));
                l.add(info("Channels loaded", null, i).value(String.valueOf(channels.size())));
                l.add(info("Calendar events", null, i).value(String.valueOf(events.size())));
                l.add(info("Tips", "Hold OK on an app, input or channel for its options. Back leaves a submenu.", i));
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
        for (String bad : HIDDEN_PACKAGES) if (bad.equals(pkg)) return true;
        return false;
    }

    private static int rank(String pkg) {
        if (pkg.equals(SETTINGS)) return 10000;
        return 1000;
    }
}
