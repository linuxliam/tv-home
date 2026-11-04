package com.liam.homescreen;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On-device channel library. Playlists are imported here once, so channels are browsable offline and can be
 * checked in the background; a channel keeps its row (and number) across playlist refreshes.
 */
final class ChannelDb extends SQLiteOpenHelper {
    static final int UNKNOWN = 0;
    static final int WORKING = 1;
    static final int DEAD = 2;

    private static ChannelDb instance;
    /** Bumped on every write so screens know when to re-read. */
    private static volatile int version;

    static synchronized ChannelDb get(Context c) {
        if (instance == null) instance = new ChannelDb(c.getApplicationContext());
        return instance;
    }

    static int version() {
        return version;
    }

    private ChannelDb(Context c) {
        super(c, "channels.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE channel (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, "
                + "name TEXT NOT NULL, grp TEXT NOT NULL, source TEXT NOT NULL, "
                + "status INTEGER NOT NULL DEFAULT 0, checked_at INTEGER NOT NULL DEFAULT 0, "
                + "seen INTEGER NOT NULL DEFAULT 1, UNIQUE(source, url))");
        db.execSQL("CREATE INDEX channel_status ON channel(status)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
    }

    /** Result of merging one playlist into the library. */
    static final class ImportResult {
        int added;
        int updated;
        int removed;
        int total;
    }

    /** Add new channels, update names and groups of known ones, drop ones the playlist no longer lists. */
    synchronized ImportResult importSource(String source, List<IptvPlaylist.Channel> list) {
        ImportResult result = new ImportResult();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE channel SET seen = 0 WHERE source = ?", new Object[]{source});
            for (IptvPlaylist.Channel ch : list) {
                ContentValues v = new ContentValues();
                v.put("name", ch.name);
                v.put("grp", ch.group == null || ch.group.isEmpty() ? "Other" : ch.group);
                v.put("seen", 1);
                int n = db.update("channel", v, "source = ? AND url = ?", new String[]{source, ch.url});
                if (n == 0) {
                    v.put("source", source);
                    v.put("url", ch.url);
                    db.insert("channel", null, v);
                    result.added++;
                } else {
                    result.updated++;
                }
            }
            result.removed = db.delete("channel", "source = ? AND seen = 0", new String[]{source});
            result.total = list.size();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        version++;
        return result;
    }

    /** How many rows are attributed to this playlist URL. */
    synchronized int countForSource(String source) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM channel WHERE source = ?", new String[]{source});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Remove channels from playlists that are no longer configured. */
    synchronized void retainSources(List<String> sources) {
        SQLiteDatabase db = getWritableDatabase();
        Set<String> keep = new HashSet<>(sources);
        List<String> drop = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT DISTINCT source FROM channel", null);
        try {
            while (c.moveToNext()) if (!keep.contains(c.getString(0))) drop.add(c.getString(0));
        } finally {
            c.close();
        }
        for (String s : drop) db.delete("channel", "source = ?", new String[]{s});
        if (!drop.isEmpty()) version++;
    }

    /** Every channel, in the order they were first imported (so numbers stay put). */
    synchronized List<IptvPlaylist.Channel> loadAll() {
        List<IptvPlaylist.Channel> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, grp, url, status FROM channel ORDER BY id", null);
        try {
            while (c.moveToNext()) {
                IptvPlaylist.Channel ch = new IptvPlaylist.Channel(c.getString(1), c.getString(2), c.getString(3));
                ch.id = c.getLong(0);
                ch.status = c.getInt(4);
                out.add(ch);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Channels due a check: never checked, or last checked before {@code staleBefore}. */
    synchronized List<IptvPlaylist.Channel> dueForCheck(long staleBefore) {
        List<IptvPlaylist.Channel> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, grp, url, status FROM channel WHERE status = 0 OR checked_at < ? "
                        + "ORDER BY status, id", new String[]{String.valueOf(staleBefore)});
        try {
            while (c.moveToNext()) {
                IptvPlaylist.Channel ch = new IptvPlaylist.Channel(c.getString(1), c.getString(2), c.getString(3));
                ch.id = c.getLong(0);
                ch.status = c.getInt(4);
                out.add(ch);
            }
        } finally {
            c.close();
        }
        return out;
    }

    synchronized void setResult(long id, boolean working) {
        ContentValues v = new ContentValues();
        v.put("status", working ? WORKING : DEAD);
        v.put("checked_at", System.currentTimeMillis());
        getWritableDatabase().update("channel", v, "id = ?", new String[]{String.valueOf(id)});
        version++;
    }

    /** Playback outcome from the guide or player — updates every row with that stream URL. */
    synchronized void setResultForUrl(String url, boolean working) {
        ContentValues v = new ContentValues();
        v.put("status", working ? WORKING : DEAD);
        v.put("checked_at", System.currentTimeMillis());
        int n = getWritableDatabase().update("channel", v, "url = ?", new String[]{url});
        if (n > 0) version++;
    }

    /** Keys ({@link DeadChannels#key}) of every channel currently marked dead. */
    synchronized Set<String> deadKeys() {
        Set<String> out = new HashSet<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT url, name FROM channel WHERE status = 2", null);
        try {
            while (c.moveToNext()) out.add(c.getString(0) + "|" + c.getString(1));
        } finally {
            c.close();
        }
        return out;
    }

    /** {unchecked, working, dead}. */
    synchronized int[] counts() {
        int[] n = new int[3];
        Cursor c = getReadableDatabase().rawQuery("SELECT status, COUNT(*) FROM channel GROUP BY status", null);
        try {
            while (c.moveToNext()) {
                int s = c.getInt(0);
                if (s >= 0 && s < 3) n[s] = c.getInt(1);
            }
        } finally {
            c.close();
        }
        return n;
    }

    synchronized int deleteDead() {
        int n = getWritableDatabase().delete("channel", "status = 2", null);
        if (n > 0) version++;
        return n;
    }

    /** Permanently drop channels by stream URL (EPG auto-clean). */
    synchronized int deleteUrls(java.util.Collection<String> urls) {
        if (urls == null || urls.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        int n = 0;
        for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            n += db.delete("channel", "url = ?", new String[]{url});
        }
        if (n > 0) version++;
        return n;
    }

    /** Drop movies / series that were imported before live-only filtering. */
    synchronized int scrubNonLive() {
        List<IptvPlaylist.Channel> all = loadAll();
        SQLiteDatabase db = getWritableDatabase();
        int n = 0;
        for (IptvPlaylist.Channel ch : all) {
            if (IptvPlaylist.isLiveChannel(ch)) continue;
            n += db.delete("channel", "id = ?", new String[]{String.valueOf(ch.id)});
        }
        if (n > 0) version++;
        return n;
    }

    /** Put dead channels back in the queue for another check. */
    synchronized int resetDead() {
        ContentValues v = new ContentValues();
        v.put("status", UNKNOWN);
        v.put("checked_at", 0);
        int n = getWritableDatabase().update("channel", v, "status = 2", null);
        if (n > 0) version++;
        return n;
    }

    /** Source playlist URL -> channel count, in import order. */
    synchronized Map<String, Integer> sourceCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT source, COUNT(*) FROM channel GROUP BY source ORDER BY MIN(id)", null);
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        } finally {
            c.close();
        }
        return out;
    }
}
