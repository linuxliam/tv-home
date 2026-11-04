package com.liam.homescreen;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-bleed live preview with a translucent EPG dock in front.
 * Scrolling the channel list follows into the preview after a short settle; OK tunes immediately.
 * Digits jump to a channel number. Play opens fullscreen; hold OK favorites.
 * Broken channels are purged on open (with a progress bar) and when playback fails — never listed as Unavailable.
 */
public class GuideActivity extends Activity {
    static List<IptvPlaylist.Channel> channels;
    static int startIndex;
    /** Set when Guide is opened from Player — Play/fullscreen should return there, not stack another Player. */
    static boolean openedFromPlayer;

    private VideoView video;
    private GuideView guide;
    private TextView nowPlaying;
    private CleanBar cleanBar;
    private FrameLayout root;
    private List<IptvPlaylist.Channel> list;
    private int tuned;
    private boolean cleaning;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        list = channels != null ? new ArrayList<>(channels)
                : (Shared.channels != null ? new ArrayList<>(Shared.channels) : new ArrayList<IptvPlaylist.Channel>());
        if (list.isEmpty()) {
            finish();
            return;
        }
        tuned = startIndex;
        if (tuned < 0 || tuned >= list.size()) {
            String last = getSharedPreferences("home", MODE_PRIVATE).getString("last_channel_url", null);
            tuned = 0;
            if (last != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).url.equals(last)) {
                        tuned = i;
                        break;
                    }
                }
            }
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        // Full-width preview; compact translucent EPG sits in front (not a corner PIP).
        int panelW = screenW - dp(48);
        int panelH = Math.min(dp(360), Math.round(screenH * 0.46f));

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05060A);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        nowPlaying = new TextView(this);
        nowPlaying.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nowPlaying.setTextSize(17);
        nowPlaying.setTextColor(XmbView.iconColor);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.END);
        nowPlaying.setShadowLayer(3f * dm.density, 0, dm.density,
                XmbView.iconColor == 0xFF000000 ? 0x44FFFFFF : 0xCC000000);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        nlp.topMargin = dp(28);
        nlp.leftMargin = dp(32);
        nlp.rightMargin = dp(32);
        root.addView(nowPlaying, nlp);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setVolume(1f, 1f);
                mp.start();
                if (tuned >= 0 && tuned < list.size()) {
                    DeadChannels.clear(GuideActivity.this, list.get(tuned));
                }
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (cleaning) return true;
                if (tuned >= 0 && tuned < list.size()) {
                    removeBrokenChannel(tuned, "Removed — won't play");
                }
                return true;
            }
        });

        guide = new GuideView(this, list, tuned, ui);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                panelW, panelH, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        glp.bottomMargin = dp(20);
        root.addView(guide, glp);

        cleanBar = new CleanBar(this);
        cleanBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(dp(520), screenW - dp(80)), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(cleanBar, clp);

        setContentView(root);
        startCleanOnOpen();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Purge known-broken channels from the EPG before the guide is usable. */
    private void startCleanOnOpen() {
        cleaning = true;
        if (guide != null) {
            guide.setFocusable(false);
            guide.setFocusableInTouchMode(false);
        }
        cleanBar.setVisibility(View.VISIBLE);
        cleanBar.setProgress(0, 1, "Cleaning broken channels\u2026");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Map<String, Long> deadMap = DeadChannels.load(GuideActivity.this);
                final List<IptvPlaylist.Channel> snapshot = new ArrayList<>(list);
                final String keepUrl = (tuned >= 0 && tuned < snapshot.size()) ? snapshot.get(tuned).url : null;
                final List<Integer> removeAt = new ArrayList<>();
                for (int i = 0; i < snapshot.size(); i++) {
                    IptvPlaylist.Channel ch = snapshot.get(i);
                    if (deadMap.containsKey(DeadChannels.key(ch)) || ch.status == ChannelDb.DEAD) {
                        removeAt.add(i);
                    }
                }
                final int total = Math.max(1, removeAt.size());
                final Set<String> urls = new HashSet<>();
                if (removeAt.isEmpty()) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) cleanBar.setProgress(1, 1, "Channels look good");
                        }
                    });
                }
                for (int step = 0; step < removeAt.size(); step++) {
                    IptvPlaylist.Channel ch = snapshot.get(removeAt.get(step));
                    urls.add(ch.url);
                    final int done = step + 1;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            cleanBar.setProgress(done, total,
                                    "Removing broken channels\u2026  " + done + " / " + total);
                        }
                    });
                }
                ChannelDb db = ChannelDb.get(GuideActivity.this);
                if (!urls.isEmpty()) db.deleteUrls(urls);
                db.deleteDead();
                // Drop stale pref flags so they don't linger after DB purge.
                getSharedPreferences("home", MODE_PRIVATE).edit().remove("ch_dead").apply();

                final int removed = removeAt.size();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (removed > 0) {
                            // Remove highest indices first so positions stay valid.
                            for (int i = removeAt.size() - 1; i >= 0; i--) {
                                int at = removeAt.get(i);
                                if (at >= 0 && at < list.size()) list.remove(at);
                            }
                            tuned = 0;
                            if (keepUrl != null) {
                                for (int i = 0; i < list.size(); i++) {
                                    if (list.get(i).url.equals(keepUrl)) {
                                        tuned = i;
                                        break;
                                    }
                                }
                            }
                        }
                        publishChannelLists();
                        cleaning = false;
                        cleanBar.setVisibility(View.GONE);
                        if (list.isEmpty()) {
                            finish();
                            return;
                        }
                        if (guide != null) {
                            guide.rebuildAfterClean(tuned);
                            guide.setFocusable(true);
                            guide.setFocusableInTouchMode(true);
                            guide.requestFocus();
                            if (removed > 0) {
                                guide.notice(removed == 1 ? "Removed 1 broken channel"
                                        : "Removed " + removed + " broken channels");
                            }
                        }
                        playTuned();
                    }
                });
            }
        }, "guide-clean").start();
    }

    /** Throw away a channel that failed in-guide and retune something else. */
    void removeBrokenChannel(int index, String message) {
        if (index < 0 || index >= list.size()) return;
        IptvPlaylist.Channel ch = list.get(index);
        DeadChannels.mark(this, ch);
        Set<String> urls = new HashSet<>();
        urls.add(ch.url);
        ChannelDb.get(this).deleteUrls(urls);
        list.remove(index);
        if (tuned > index) tuned--;
        else if (tuned >= list.size()) tuned = Math.max(0, list.size() - 1);
        publishChannelLists();
        if (list.isEmpty()) {
            finish();
            return;
        }
        if (guide != null) {
            guide.rebuildAfterClean(tuned);
            if (message != null) guide.notice(message);
        }
        playTuned();
    }

    private void publishChannelLists() {
        channels = list;
        Shared.channels = new ArrayList<>(list);
        PlayerActivity.channels = list;
        startIndex = tuned;
    }

    private void playTuned() {
        if (cleaning || tuned < 0 || tuned >= list.size()) return;
        IptvPlaylist.Channel ch = list.get(tuned);
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putString("last_channel_url", ch.url)
                .putString("last_channel_name", ch.name)
                .apply();
        recordRecent(ch);
        // So Player can resume the channel the guide left on.
        startIndex = tuned;
        channels = list;
        video.stopPlayback();
        video.setVideoURI(Uri.parse(ch.url));
        refreshNowPlaying();
        if (guide != null) {
            guide.setTuned(tuned);
            guide.invalidate();
        }
    }

    /** Big identity over the preview — number, name, group — so the dock can stay compact. */
    private void refreshNowPlaying() {
        if (nowPlaying == null || tuned < 0 || tuned >= list.size()) return;
        IptvPlaylist.Channel ch = list.get(tuned);
        String group = ch.group == null || ch.group.isEmpty() ? "" : "  ·  " + ch.group;
        nowPlaying.setText((tuned + 1) + "  " + ch.name + group);
    }

    private static final int MAX_RECENTS = 30;

    private void recordRecent(IptvPlaylist.Channel ch) {
        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        String key = ch.url + "|" + ch.name;
        List<String> out = new ArrayList<>();
        out.add(key);
        String raw = prefs.getString("ch_recents", "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                if (!line.isEmpty() && !line.equals(key) && out.size() < MAX_RECENTS) out.add(line);
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(out.get(i));
        }
        prefs.edit().putString("ch_recents", b.toString()).apply();
    }

    void tuneInPlace(int index) {
        if (index < 0 || index >= list.size()) return;
        tuned = index;
        playTuned();
    }

    void watchFullscreen(int index) {
        if (index < 0 || index >= list.size()) return;
        tuned = index;
        startIndex = index;
        channels = list;
        PlayerActivity.channels = list;
        PlayerActivity.startIndex = index;
        if (openedFromPlayer) {
            // Player is still under us — just leave so it resumes the tuned channel.
            finish();
            return;
        }
        startActivity(new Intent(this, PlayerActivity.class));
        finish();
    }

    void toggleFavorite(int index) {
        if (index < 0 || index >= list.size()) return;
        IptvPlaylist.Channel ch = list.get(index);
        String key = ch.url + "|" + ch.name;
        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        String raw = prefs.getString("ch_favs", "");
        List<String> favs = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                if (!line.isEmpty()) favs.add(line);
            }
        }
        boolean was = favs.contains(key);
        if (was) favs.remove(key);
        else favs.add(key);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < favs.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(favs.get(i));
        }
        prefs.edit().putString("ch_favs", b.toString()).apply();
        guide.setFavorites(favs);
        guide.notice(was ? ch.name + " removed from favorites" : ch.name + " added to favorites");
    }

    private boolean okHeld;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = NavKeys.navCode(event.getKeyCode(), false);
        if (cleaning) return true; // progress card owns the screen until purge finishes
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        // The remote has no Menu key: holding OK on a channel favorites it.
        if (ok && guide != null && !(getCurrentFocus() instanceof EditText)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    okHeld = false;
                } else if (!okHeld && event.getEventTime() - event.getDownTime() >= 500) {
                    okHeld = true;
                    guide.onGuideKey(KeyEvent.KEYCODE_MENU);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean held = okHeld;
                okHeld = false;
                if (!held) guide.onGuideKey(KeyEvent.KEYCODE_DPAD_CENTER);
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
            if (guide != null && guide.onGuideKey(keyCode)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        openedFromPlayer = false;
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        if (video != null) video.stopPlayback();
    }

    /** Compact dual dock over the full-bleed preview. Now-playing identity floats above. */
    private static final class GuideView extends View {
        /** Plate alpha — translucent enough for the live picture to read through. */
        private static final int PANEL_ALPHA = 0x99000000;
        private static final int MAX_VISIBLE = 6;
        /** Wait for the highlight to settle before retuning the full-bleed preview. */
        private static final long PREVIEW_DWELL_MS = 420;
        private int visible = MAX_VISIBLE;
        private int gVisible = MAX_VISIBLE;

        final List<IptvPlaylist.Channel> channels;
        private final List<String> favs = new ArrayList<>();
        private final List<String> groupNames = new ArrayList<>();
        private static final int G_ALL = 0;
        private static final int G_SEARCH = 1;
        private static final int G_FAVS = 2;
        private static final int G_RECENT = 3;
        private static final int G_FIRST = 4;

        private final List<String> recents = new ArrayList<>();
        private final java.util.HashMap<String, Integer> keyIndex = new java.util.HashMap<>();
        private String query = "";
        private int[] groupCount = new int[0];
        private int[] view = new int[0];
        private final Handler ui;
        private final StringBuilder digits = new StringBuilder();
        /** 0 = categories, 1 = channels. */
        private int pane = 1;
        private int group;
        private int groupFirst;
        /** Position inside {@link #view}. */
        private int sel;
        private int first;
        /** Index into {@link #channels}. */
        private int tuned;
        private String notice;
        private long noticeAt;
        private float panelX;
        private float chanTop;
        private float chanRowH;
        private float groupTop;
        private float groupRowH;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint thin = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint hintKey = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint hintLab = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
        private final DisplayMetrics dm;

        private final Runnable commitDigits = new Runnable() {
            @Override
            public void run() {
                if (digits.length() == 0) return;
                try {
                    int n = Integer.parseInt(digits.toString());
                    if (n >= 1 && n <= channels.size()) {
                        jumpTo(n - 1, true);
                    } else {
                        notice("No channel " + n);
                    }
                } catch (NumberFormatException ignored) {
                }
                digits.setLength(0);
                invalidate();
            }
        };

        private final Runnable previewFollow = new Runnable() {
            @Override
            public void run() {
                int ch = current();
                if (ch >= 0 && ch != tuned) {
                    ((GuideActivity) getContext()).tuneInPlace(ch);
                }
            }
        };

        GuideView(GuideActivity host, List<IptvPlaylist.Channel> channels, int start, Handler ui) {
            super(host);
            this.channels = channels;
            this.ui = ui;
            this.tuned = Math.max(0, Math.min(start, channels.size() - 1));
            this.dm = getResources().getDisplayMetrics();
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setWillNotDraw(false);
            loadFavorites();
            buildGroups();
            setGroup(G_ALL);
            sel = Math.max(0, positionOf(tuned));
            ensureVisible();
            Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            Typeface thinFace = Typeface.create("sans-serif-thin", Typeface.NORMAL);
            if (thinFace == null) thinFace = light;
            title.setTypeface(thinFace);
            body.setTypeface(medium);
            small.setTypeface(light);
            thin.setTypeface(light);
            hintKey.setTypeface(medium);
            hintLab.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            stroke.setStyle(Paint.Style.STROKE);
            Hints.prepare(hintKey, hintLab, dm.density);
        }

        void setTuned(int index) {
            tuned = index;
            loadRecents();
            if (group != G_RECENT) groupCount[G_RECENT] = recents.size();
        }

        private void loadRecents() {
            recents.clear();
            String raw = ((Activity) getContext()).getSharedPreferences("home", Activity.MODE_PRIVATE)
                    .getString("ch_recents", "");
            if (raw == null || raw.isEmpty()) return;
            for (String line : raw.split("\n")) {
                if (!line.isEmpty() && keyIndex.containsKey(line)) recents.add(line);
            }
        }

        private void loadFavorites() {
            favs.clear();
            String raw = ((Activity) getContext()).getSharedPreferences("home", Activity.MODE_PRIVATE)
                    .getString("ch_favs", "");
            if (raw == null || raw.isEmpty()) return;
            for (String line : raw.split("\n")) if (!line.isEmpty()) favs.add(line);
        }

        void setFavorites(List<String> next) {
            favs.clear();
            favs.addAll(next);
            refreshFavCount();
            if (group == G_FAVS) {
                rebuildView();
                sel = Math.max(0, Math.min(sel, view.length - 1));
                ensureVisible();
                if (view.length == 0) pane = 0;
            }
            invalidate();
        }

        void notice(String text) {
            notice = text;
            noticeAt = SystemClock.uptimeMillis();
            invalidate();
        }

        private float dp(float v) {
            return v * dm.density;
        }

        private boolean isFav(int channel) {
            IptvPlaylist.Channel ch = channels.get(channel);
            return favs.contains(ch.url + "|" + ch.name);
        }

        private void buildGroups() {
            java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            keyIndex.clear();
            for (int i = 0; i < channels.size(); i++) {
                IptvPlaylist.Channel ch = channels.get(i);
                keyIndex.put(ch.url + "|" + ch.name, i);
                String g = ch.group == null || ch.group.isEmpty() ? "Other" : ch.group;
                Integer n = counts.get(g);
                counts.put(g, n == null ? 1 : n + 1);
            }
            groupNames.clear();
            groupNames.add("All channels");
            groupNames.add("Search");
            groupNames.add("Favorites");
            groupNames.add("Recent");
            groupNames.addAll(counts.keySet());
            groupCount = new int[groupNames.size()];
            loadRecents();
            refreshCounts();
        }

        private String groupOf(int channel) {
            String g = channels.get(channel).group;
            return g == null || g.isEmpty() ? "Other" : g;
        }

        private void refreshCounts() {
            java.util.HashMap<String, Integer> slot = new java.util.HashMap<>();
            for (int i = G_FIRST; i < groupNames.size(); i++) {
                slot.put(groupNames.get(i).toLowerCase(Locale.ROOT), i);
                groupCount[i] = 0;
            }
            for (int i = 0; i < channels.size(); i++) {
                Integer g = slot.get(groupOf(i).toLowerCase(Locale.ROOT));
                if (g != null) groupCount[g]++;
            }
            groupCount[G_ALL] = channels.size();
            groupCount[G_RECENT] = recents.size();
            refreshFavCount();
        }

        /** After broken channels are deleted from the shared list. */
        void rebuildAfterClean(int newTuned) {
            tuned = Math.max(0, Math.min(newTuned, Math.max(0, channels.size() - 1)));
            buildGroups();
            setGroup(G_ALL);
            sel = Math.max(0, positionOf(tuned));
            ensureVisible();
            pane = 1;
            invalidate();
        }

        private void refreshFavCount() {
            int n = 0;
            for (int i = 0; i < channels.size(); i++) if (isFav(i)) n++;
            if (groupCount.length > G_FAVS) groupCount[G_FAVS] = n;
        }

        private boolean inGroup(int channel) {
            if (group == G_ALL) return true;
            if (group == G_FAVS) return isFav(channel);
            if (group == G_SEARCH) {
                String name = channels.get(channel).name.toLowerCase(Locale.getDefault());
                for (String term : query.toLowerCase(Locale.getDefault()).split("\\s+")) {
                    if (!term.isEmpty() && !name.contains(term)) return false;
                }
                return !query.isEmpty();
            }
            return groupOf(channel).equalsIgnoreCase(groupNames.get(group));
        }

        private void rebuildView() {
            if (group == G_RECENT) {
                int[] tmp = new int[recents.size()];
                int n = 0;
                for (String key : recents) {
                    Integer idx = keyIndex.get(key);
                    if (idx != null) tmp[n++] = idx;
                }
                view = java.util.Arrays.copyOf(tmp, n);
                groupCount[G_RECENT] = n;
                return;
            }
            int n = 0;
            int[] tmp = new int[channels.size()];
            for (int i = 0; i < channels.size(); i++) if (inGroup(i)) tmp[n++] = i;
            view = java.util.Arrays.copyOf(tmp, n);
            if (group == G_SEARCH) groupCount[G_SEARCH] = n;
        }

        private void setGroup(int g) {
            group = Math.max(0, Math.min(g, groupNames.size() - 1));
            ensureGroupVisible();
            rebuildView();
            sel = 0;
            first = 0;
        }

        private int current() {
            return view.length == 0 ? -1 : view[Math.min(sel, view.length - 1)];
        }

        private int positionOf(int channel) {
            for (int i = 0; i < view.length; i++) if (view[i] == channel) return i;
            return -1;
        }

        private void ensureVisible() {
            if (sel < first) first = sel;
            if (sel >= first + visible) first = sel - visible + 1;
            first = Math.max(0, Math.min(first, Math.max(0, view.length - visible)));
        }

        private void ensureGroupVisible() {
            if (group < groupFirst) groupFirst = group;
            if (group >= groupFirst + gVisible) groupFirst = group - gVisible + 1;
            groupFirst = Math.max(0, Math.min(groupFirst, Math.max(0, groupNames.size() - gVisible)));
        }

        private void focusChannel(int channel) {
            if (positionOf(channel) < 0) setGroup(G_ALL);
            sel = positionOf(channel);
            pane = 1;
            ensureVisible();
        }

        private void jumpTo(int channel, boolean tune) {
            if (channel < 0 || channel >= channels.size()) return;
            focusChannel(channel);
            if (tune) {
                cancelPreviewFollow();
                ((GuideActivity) getContext()).tuneInPlace(channel);
            } else {
                schedulePreviewFollow();
            }
            invalidate();
        }

        private void cancelPreviewFollow() {
            ui.removeCallbacks(previewFollow);
        }

        /** Preview the highlighted channel once scrolling settles — skip if already tuned. */
        private void schedulePreviewFollow() {
            cancelPreviewFollow();
            int ch = current();
            if (ch < 0 || ch == tuned) return;
            ui.postDelayed(previewFollow, PREVIEW_DWELL_MS);
        }

        private void digit(char d) {
            if (digits.length() >= 4) digits.setLength(0);
            digits.append(d);
            try {
                int n = Integer.parseInt(digits.toString());
                if (n >= 1 && n <= channels.size()) focusChannel(n - 1);
            } catch (NumberFormatException ignored) {
            }
            ui.removeCallbacks(commitDigits);
            ui.postDelayed(commitDigits, 1500);
            invalidate();
        }

        private void clearDigits() {
            digits.setLength(0);
            ui.removeCallbacks(commitDigits);
        }

        private void openSearch() {
            final Activity host = (Activity) getContext();
            final EditText input = new EditText(host);
            input.setSingleLine(true);
            input.setHint("Channel name");
            input.setText(query);
            input.setSelection(query.length());
            input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            final AlertDialog dialog = new AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Search channels")
                    .setView(input)
                    .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            applySearch(input.getText().toString());
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create();
            input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                    applySearch(input.getText().toString());
                    dialog.dismiss();
                    return true;
                }
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
        }

        private void applySearch(String text) {
            query = text.trim();
            groupNames.set(G_SEARCH, query.isEmpty() ? "Search" : "Search: " + query);
            setGroup(G_SEARCH);
            pane = view.length > 0 ? 1 : 0;
            invalidate();
        }

        private void moveChannel(int delta) {
            clearDigits();
            int next = Math.max(0, Math.min(view.length - 1, sel + delta));
            if (view.length > 0 && next != sel) {
                sel = next;
                ensureVisible();
                schedulePreviewFollow();
            }
            invalidate();
        }

        private void moveGroup(int delta) {
            int next = Math.max(0, Math.min(groupNames.size() - 1, group + delta));
            if (next != group) {
                setGroup(next);
                if (pane == 1) schedulePreviewFollow();
            }
            invalidate();
        }

        boolean onGuideKey(int keyCode) {
            GuideActivity host = (GuideActivity) getContext();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (pane == 0) moveGroup(-1); else moveChannel(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (pane == 0) moveGroup(1); else moveChannel(1);
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    pane = 1;
                    moveChannel(-1);
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    pane = 1;
                    moveChannel(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (pane == 1) {
                        clearDigits();
                        pane = 0;
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (pane == 0 && view.length > 0) {
                        pane = 1;
                        schedulePreviewFollow();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    clearDigits();
                    if (pane == 0) {
                        if (group == G_SEARCH) openSearch();
                        else if (view.length > 0) {
                            pane = 1;
                            schedulePreviewFollow();
                        } else notice("Nothing here yet");
                        invalidate();
                    } else if (current() >= 0) {
                        cancelPreviewFollow();
                        host.tuneInPlace(current());
                    }
                    return true;
                case KeyEvent.KEYCODE_SEARCH:
                    clearDigits();
                    openSearch();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    clearDigits();
                    cancelPreviewFollow();
                    if (current() >= 0) host.watchFullscreen(current());
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_INFO:
                    if (current() >= 0) host.toggleFavorite(current());
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                    if (pane == 0) moveGroup(-gVisible); else moveChannel(-visible);
                    return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    if (pane == 0) moveGroup(gVisible); else moveChannel(visible);
                    return true;
                case KeyEvent.KEYCODE_0:
                case KeyEvent.KEYCODE_1:
                case KeyEvent.KEYCODE_2:
                case KeyEvent.KEYCODE_3:
                case KeyEvent.KEYCODE_4:
                case KeyEvent.KEYCODE_5:
                case KeyEvent.KEYCODE_6:
                case KeyEvent.KEYCODE_7:
                case KeyEvent.KEYCODE_8:
                case KeyEvent.KEYCODE_9:
                    digit((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = event.getX();
                float y = event.getY();
                if (x < panelX) {
                    int row = groupFirst + (int) ((y - groupTop) / groupRowH);
                    if (y >= groupTop && row >= 0 && row < groupNames.size()) {
                        pane = 0;
                        setGroup(row);
                        invalidate();
                        return true;
                    }
                } else {
                    int row = first + (int) ((y - chanTop) / chanRowH);
                    if (y >= chanTop && row >= 0 && row < view.length) {
                        pane = 1;
                        sel = row;
                        ensureVisible();
                        invalidate();
                        cancelPreviewFollow();
                        ((GuideActivity) getContext()).tuneInPlace(view[row]);
                        return true;
                    }
                }
            }
            return super.onTouchEvent(event);
        }

        private float rowHeight(float infoH, float h) {
            float usable = h - infoH - dp(40) - dp(10);
            if (usable <= 0) return dp(40);
            visible = Math.max(3, Math.min(MAX_VISIBLE, (int) (usable / dp(36))));
            return usable / visible;
        }

        private void softShadow(TextPaint p, float a) {
            int ink = XmbView.iconColor;
            int shadow = ink == 0xFF000000 ? 0x44FFFFFF : 0x99000000;
            int sa = ((int) (((shadow >>> 24) & 0xFF) * a)) << 24;
            p.setShadowLayer(2f * dm.density, 0, dm.density, (shadow & 0x00FFFFFF) | sa);
            p.setColor(ink);
            p.setAlpha((int) (255 * a));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float leftW = Math.min(dp(148), w * 0.26f);
            panelX = leftW + dp(8);
            drawGroups(c, leftW, h);
            c.save();
            c.translate(panelX, 0);
            drawChannels(c, w - panelX, h);
            c.restore();
        }

        private void drawGroups(Canvas c, float w, float h) {
            int ink = XmbView.iconColor;
            boolean darkInk = ink == 0xFF000000;
            int accent = XmbView.accentColor();
            boolean focused = pane == 0;

            rect.set(0, 0, w, h);
            fill.setColor(0xFF000000);
            Glass.panel(c, rect, dp(16), fill, stroke, PANEL_ALPHA);

            float pad = dp(12);
            small.setTextSize(dp(11));
            softShadow(small, 0.55f);
            small.setLetterSpacing(0.12f);
            c.drawText("GUIDE", pad, dp(28), small);
            small.setLetterSpacing(0f);

            groupTop = dp(40);
            groupRowH = dp(36);
            gVisible = Math.max(3, (int) ((h - groupTop - dp(36)) / groupRowH));
            ensureGroupVisible();
            int last = Math.min(groupNames.size(), groupFirst + gVisible);
            for (int i = groupFirst; i < last; i++) {
                float y = groupTop + (i - groupFirst) * groupRowH;
                boolean on = i == group;
                if (on) {
                    rect.set(dp(6), y + dp(1), w - dp(6), y + groupRowH - dp(1));
                    fill.setStyle(Paint.Style.FILL);
                    fill.setShader(null);
                    fill.setColor(focused ? (darkInk ? 0x22000000 : 0x2AFFFFFF) : (darkInk ? 0x10000000 : 0x16FFFFFF));
                    c.drawRoundRect(rect, dp(8), dp(8), fill);
                    if (focused) {
                        stroke.setStyle(Paint.Style.STROKE);
                        stroke.setStrokeWidth(dp(2.5f));
                        stroke.setColor((accent & 0x00FFFFFF) | 0xEE000000);
                        c.drawLine(dp(6), y + dp(8), dp(6), y + groupRowH - dp(8), stroke);
                    }
                }
                String count = String.valueOf(groupCount[i]);
                small.setTextSize(dp(11));
                softShadow(small, on ? 0.75f : 0.45f);
                float cw = small.measureText(count);
                c.drawText(count, w - pad - cw, y + groupRowH * 0.64f, small);
                body.setTextSize(dp(13));
                softShadow(body, on ? 1f : 0.72f);
                c.drawText(TextUtils.ellipsize(groupNames.get(i), body, w - pad * 2 - cw - dp(6), TextUtils.TruncateAt.END).toString(),
                        pad, y + groupRowH * 0.64f, body);
            }

            if (groupFirst > 0 || last < groupNames.size()) {
                small.setTextSize(dp(10));
                softShadow(small, 0.45f);
                if (last < groupNames.size()) c.drawText("▾", w / 2f - dp(4), h - dp(28), small);
            }

            small.setTextSize(dp(11));
            softShadow(small, 0.5f);
            int shown = view.length == 0 ? 0 : sel + 1;
            c.drawText(shown + " / " + view.length, pad, h - dp(14), small);
        }

        private void drawChannels(Canvas c, float w, float h) {
            float pad = dp(16);
            // Slim focus strip — full identity floats over the preview above.
            float infoH = dp(40);
            float footY = h - dp(22);
            float top = infoH + dp(8);
            float rowH = rowHeight(infoH, h);
            chanTop = top;
            chanRowH = rowH;
            ensureVisible();
            int ink = XmbView.iconColor;
            boolean darkInk = ink == 0xFF000000;
            int accent = XmbView.accentColor();
            boolean focused = pane == 1;

            rect.set(0, 0, w, h);
            fill.setColor(0xFF000000);
            Glass.panel(c, rect, dp(16), fill, stroke, PANEL_ALPHA);

            int cur = current();
            String clockText = timeFmt.format(new Date());
            small.setTextSize(dp(12));
            softShadow(small, 0.55f);
            float clockW = small.measureText(clockText);
            c.drawText(clockText, w - pad - clockW, dp(26), small);

            if (cur >= 0) {
                IptvPlaylist.Channel focus = channels.get(cur);
                boolean watching = cur == tuned;
                body.setTextSize(dp(15));
                softShadow(body, 1f);
                String num = String.valueOf(cur + 1);
                float badgeW = Math.max(dp(36), body.measureText(num) + dp(16));
                rect.set(pad, dp(8), pad + badgeW, dp(34));
                fill.setStyle(Paint.Style.FILL);
                fill.setShader(null);
                fill.setColor((accent & 0x00FFFFFF) | (watching ? 0x88000000 : 0x55000000));
                c.drawRoundRect(rect, dp(8), dp(8), fill);
                body.setTextAlign(Paint.Align.CENTER);
                c.drawText(num, rect.centerX(), rect.top + dp(18), body);
                body.setTextAlign(Paint.Align.LEFT);

                float nameX0 = pad + badgeW + dp(10);
                float rightLimit = w - pad - clockW - dp(12);
                String mark = watching ? "  ·  LIVE" : "";
                body.setTextSize(dp(16));
                softShadow(body, 1f);
                float markW = watching ? body.measureText(mark) : 0f;
                String name = TextUtils.ellipsize(focus.name, body, rightLimit - nameX0 - markW, TextUtils.TruncateAt.END).toString();
                c.drawText(name, nameX0, dp(26), body);
                if (watching) {
                    body.setColor((accent & 0x00FFFFFF) | 0xFF000000);
                    c.drawText(mark, nameX0 + body.measureText(name), dp(26), body);
                }
            } else {
                body.setTextSize(dp(16));
                softShadow(body, 1f);
                c.drawText(groupNames.get(group), pad, dp(26), body);
            }

            if (digits.length() > 0) {
                thin.setTextSize(dp(18));
                thin.setColor(ink);
                softShadow(thin, 1f);
                String entry = digits.toString();
                String entryLabel = "Enter channel";
                small.setTextSize(dp(11));
                float ew = Math.max(thin.measureText(entry), small.measureText(entryLabel)) + dp(22);
                float eh = dp(36);
                rect.set(w - pad - ew, dp(42), w - pad, dp(42) + eh);
                Glass.panel(c, rect, dp(8), fill, stroke, PANEL_ALPHA);
                thin.setTextAlign(Paint.Align.CENTER);
                c.drawText(entry, rect.centerX(), rect.top + dp(16), thin);
                thin.setTextAlign(Paint.Align.LEFT);
                softShadow(small, 0.6f);
                small.setTextAlign(Paint.Align.CENTER);
                c.drawText(entryLabel, rect.centerX(), rect.top + dp(30), small);
                small.setTextAlign(Paint.Align.LEFT);
            }

            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1f);
            stroke.setColor(darkInk ? 0x22000000 : 0x28FFFFFF);
            c.drawLine(pad, infoH, w - pad, infoH, stroke);

            if (view.length == 0) {
                body.setTextSize(dp(17));
                softShadow(body, 0.8f);
                body.setTextAlign(Paint.Align.CENTER);
                String msg = "No channels";
                String hint = null;
                if (group == G_FAVS) {
                    msg = "No favorites yet";
                    hint = "Hold OK on a channel to add it";
                } else if (group == G_RECENT) {
                    msg = "Nothing watched yet";
                    hint = "Channels you tune appear here";
                } else if (group == G_SEARCH) {
                    msg = query.isEmpty() ? "Search channels" : "No matches";
                    hint = query.isEmpty() ? "Press OK to type a name" : "Press OK to search again";
                }
                c.drawText(msg, w / 2f, h / 2f, body);
                small.setTextSize(dp(13));
                softShadow(small, 0.55f);
                small.setTextAlign(Paint.Align.CENTER);
                if (hint != null) c.drawText(hint, w / 2f, h / 2f + dp(26), small);
                body.setTextAlign(Paint.Align.LEFT);
                small.setTextAlign(Paint.Align.LEFT);
            }

            int last = Math.min(view.length, first + visible);
            for (int i = first; i < last; i++) {
                int idx = view[i];
                IptvPlaylist.Channel ch = channels.get(idx);
                float y = top + (i - first) * rowH;
                boolean on = i == sel;
                boolean isTuned = idx == tuned;

                if (on) {
                    rect.set(pad - dp(4), y + dp(1), w - pad + dp(4), y + rowH - dp(1));
                    fill.setStyle(Paint.Style.FILL);
                    fill.setShader(null);
                    fill.setColor(focused ? (darkInk ? 0x22000000 : 0x2AFFFFFF) : (darkInk ? 0x10000000 : 0x14FFFFFF));
                    c.drawRoundRect(rect, dp(6), dp(6), fill);
                    if (focused) {
                        stroke.setStyle(Paint.Style.STROKE);
                        stroke.setStrokeWidth(dp(2.5f));
                        stroke.setColor((accent & 0x00FFFFFF) | 0xEE000000);
                        c.drawLine(pad - dp(4), y + dp(7), pad - dp(4), y + rowH - dp(7), stroke);
                    }
                }

                // Number + name (+ group) — no monogram tile; denser rows, clearer skim.
                float numRight = pad + dp(34);
                thin.setTextSize(dp(14));
                softShadow(thin, isTuned || on ? 1f : 0.6f);
                thin.setTextAlign(Paint.Align.RIGHT);
                c.drawText(String.valueOf(idx + 1), numRight, y + rowH * 0.62f, thin);
                thin.setTextAlign(Paint.Align.LEFT);

                boolean starred = isFav(idx);
                float nameX = numRight + dp(12);
                float nameMax = w - pad - nameX - dp(18) - (starred ? dp(18) : 0) - (isTuned ? dp(12) : 0);
                body.setTextSize(dp(15));
                softShadow(body, on || isTuned ? 1f : 0.85f);
                String shown = TextUtils.ellipsize(ch.name, body, nameMax, TextUtils.TruncateAt.END).toString();
                float baseline = rowH >= dp(40) ? y + rowH * 0.42f : y + rowH * 0.62f;
                c.drawText(shown, nameX, baseline, body);
                if (starred) {
                    body.setColor((accent & 0x00FFFFFF) | 0xFF000000);
                    c.drawText("★", nameX + body.measureText(shown) + dp(6), baseline, body);
                }
                if (rowH >= dp(40)) {
                    small.setTextSize(dp(11));
                    softShadow(small, on ? 0.7f : 0.42f);
                    String grp = ch.group == null ? "" : ch.group;
                    c.drawText(TextUtils.ellipsize(grp, small, nameMax, TextUtils.TruncateAt.END).toString(),
                            nameX, y + rowH * 0.78f, small);
                }

                if (isTuned) {
                    fill.setColor((accent & 0x00FFFFFF) | 0xFF000000);
                    c.drawCircle(w - pad - dp(4), y + rowH / 2f, dp(3.5f), fill);
                } else if (!on && i + 1 < last && i + 1 != sel) {
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(1f);
                    stroke.setColor(darkInk ? 0x14000000 : 0x14FFFFFF);
                    c.drawLine(nameX, y + rowH, w - pad, y + rowH, stroke);
                }
            }

            if (view.length > visible) {
                float trackH = rowH * visible;
                float tx2 = w - dp(14);
                rect.set(tx2, top, tx2 + dp(3), top + trackH);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(darkInk ? 0x16000000 : 0x1EFFFFFF);
                c.drawRoundRect(rect, dp(2), dp(2), fill);
                float thumbH = Math.max(dp(24), trackH * visible / view.length);
                float frac = (float) first / Math.max(1, view.length - visible);
                float thumbY = top + (trackH - thumbH) * frac;
                rect.set(tx2, thumbY, tx2 + dp(3), thumbY + thumbH);
                fill.setColor((accent & 0x00FFFFFF) | 0xCC000000);
                c.drawRoundRect(rect, dp(2), dp(2), fill);
            }

            String[][] keySets;
            String[][] labSets;
            if (focused) {
                keySets = new String[][]{
                        {"OK", "Play", "Hold OK", "◄"},
                        {"OK", "Play", "◄"},
                        {"OK", "◄"},
                        {"OK"}};
                labSets = new String[][]{
                        {"Tune", "Watch", "Favorite", "Categories"},
                        {"Tune", "Watch", "Categories"},
                        {"Tune", "Categories"},
                        {"Tune"}};
            } else {
                if (group == G_SEARCH) {
                    keySets = new String[][]{{"OK", "►"}, {"OK"}};
                    labSets = new String[][]{{"Search", "Channels"}, {"Search"}};
                } else {
                    keySets = new String[][]{{"►", "Back"}, {"►"}};
                    labSets = new String[][]{{"Channels", "Leave"}, {"Channels"}};
                }
            }
            Hints.prepare(hintKey, hintLab, dm.density);
            int pick = keySets.length - 1;
            for (int k = 0; k < keySets.length; k++) {
                if (Hints.measure(hintKey, hintLab, keySets[k], labSets[k], dm.density) <= w - pad * 2) {
                    pick = k;
                    break;
                }
            }
            Hints.draw(c, hintKey, hintLab, keySets[pick], labSets[pick], pad, footY, dm.density, 1f, ink);

            if (notice != null && SystemClock.uptimeMillis() - noticeAt < 2800) {
                body.setTextSize(dp(15));
                softShadow(body, 1f);
                float nw = Math.min(w - pad * 2, body.measureText(notice) + dp(28));
                float nh = dp(34);
                float ny = footY - dp(18) - nh;
                rect.set(w / 2f - nw / 2f, ny, w / 2f + nw / 2f, ny + nh);
                Glass.panel(c, rect, dp(8), fill, stroke, PANEL_ALPHA);
                body.setTextAlign(Paint.Align.CENTER);
                String msg = TextUtils.ellipsize(notice, body, nw - dp(20), TextUtils.TruncateAt.END).toString();
                c.drawText(msg, w / 2f, ny + dp(22), body);
                body.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    /** Centered glass progress card shown while broken channels are purged on open. */
    private static final class CleanBar extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final DisplayMetrics dm;
        private String text = "Cleaning\u2026";
        private float progress;

        CleanBar(Activity a) {
            super(a);
            dm = a.getResources().getDisplayMetrics();
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setTextSize(15f * dm.density);
            label.setColor(XmbView.iconColor);
            setFocusable(false);
            setClickable(false);
            setWillNotDraw(false);
        }

        void setProgress(int done, int total, String msg) {
            text = msg == null ? "Cleaning\u2026" : msg;
            progress = total <= 0 ? 0f : Math.max(0f, Math.min(1f, done / (float) total));
            invalidate();
        }

        private float dp(float v) {
            return v * dm.density;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            if (w <= 0) w = Math.round(400 * dm.density);
            setMeasuredDimension(w, Math.round(88 * dm.density));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            rect.set(0, 0, w, h);
            Glass.panel(c, rect, dp(14), fill, stroke, 0xCC000000);

            label.setTextAlign(Paint.Align.CENTER);
            int ink = XmbView.iconColor;
            int shadow = ink == 0xFF000000 ? 0x44FFFFFF : 0x99000000;
            label.setShadowLayer(2f * dm.density, 0, dm.density, shadow);
            label.setColor(ink);
            c.drawText(text, w / 2f, dp(34), label);

            float pad = dp(22);
            float barTop = dp(48);
            float barH = dp(10);
            rect.set(pad, barTop, w - pad, barTop + barH);
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(ink == 0xFF000000 ? 0x22000000 : 0x33FFFFFF);
            c.drawRoundRect(rect, dp(5), dp(5), fill);
            float filled = pad + (w - pad * 2) * progress;
            if (filled > pad) {
                rect.set(pad, barTop, filled, barTop + barH);
                fill.setColor((XmbView.accentColor() & 0x00FFFFFF) | 0xEE000000);
                c.drawRoundRect(rect, dp(5), dp(5), fill);
            }
        }
    }
}
