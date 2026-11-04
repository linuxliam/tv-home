package com.liam.homescreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads M3U / M3U8 IPTV playlists and keeps live TV channels — not movies or series episodes
 * that many Xtream-style lists mix into the same file.
 */
final class IptvPlaylist {
    static final class Channel {
        final String name;
        final String group;
        final String url;
        /** Library row id and last check result ({@link ChannelDb}); 0 for channels not from the library. */
        long id;
        int status;

        Channel(String name, String group, String url) {
            this.name = name;
            this.group = group;
            this.url = url;
        }
    }

    enum Kind {
        LIVE, MOVIE, SERIES, RADIO
    }

    /** Live channels plus nested playlist links found while reading an M3U. */
    static final class ParseResult {
        final List<Channel> channels = new ArrayList<>();
        final List<String> nestedPlaylists = new ArrayList<>();
    }

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)(?:^|[\\s.\\-_])(?:s\\d{1,2}[ .\\-_]?e\\d{1,3}|season\\s*\\d{1,2}(?:\\s*episode\\s*\\d{1,3})?|\\d{1,2}x\\d{2,3}|e(?:p(?:isode)?)?[ .\\-_]?\\d{1,3})(?:$|[\\s.\\-_])");
    private static final Pattern YEAR_TITLE = Pattern.compile("(?i)\\(\\s*(?:19|20)\\d{2}\\s*\\)\\s*$");
    private static final Pattern QUALITY_TAIL = Pattern.compile(
            "(?i)[\\s\\[_-]+(?:fhd|uhd|4k|1080p|720p|576p|480p|hevc|h\\.?265|h\\.?264|hdr|50fps|60fps)\\]?\\s*$");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private IptvPlaylist() {
    }

    /** Live channels only — movies / series / radio are dropped. */
    static List<Channel> parse(String text) {
        return parseFull(text).channels;
    }

    /**
     * Full parse: live channels, and nested {@code .m3u} playlist links (indexes that point at more
     * channel lists) so the fetcher can discover new sources automatically.
     */
    static ParseResult parseFull(String text) {
        ParseResult out = new ParseResult();
        String name = null;
        String group = "Other";
        String type = null;
        String tvgName = null;
        boolean hasSeason = false;
        boolean hasEpisode = false;
        boolean hasSerieId = false;
        boolean typePlaylist = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) {
                type = null;
                tvgName = null;
                hasSeason = false;
                hasEpisode = false;
                hasSerieId = false;
                typePlaylist = false;
                int comma = line.lastIndexOf(',');
                String attrs = comma >= 0 ? line.substring(0, comma) : line;
                name = comma >= 0 ? line.substring(comma + 1).trim() : "Channel";
                group = "Other";
                Matcher m = ATTR.matcher(attrs);
                while (m.find()) {
                    String key = m.group(1).toLowerCase(Locale.US);
                    String val = m.group(2).trim();
                    if (val.isEmpty()) continue;
                    if (key.equals("group-title")) {
                        group = val.contains(";") ? val.substring(0, val.indexOf(';')).trim() : val;
                        if (group.isEmpty()) group = "Other";
                    } else if (key.equals("tvg-name")) {
                        tvgName = val;
                    } else if (key.equals("tvg-type") || key.equals("type") || key.equals("content-type")) {
                        type = val;
                        String tn = val.toLowerCase(Locale.US);
                        if (tn.equals("playlist") || tn.equals("m3u") || tn.equals("list")) typePlaylist = true;
                    } else if (key.equals("tvg-season") || key.equals("season")) {
                        hasSeason = true;
                    } else if (key.equals("tvg-episode") || key.equals("episode")) {
                        hasEpisode = true;
                    } else if (key.equals("tvg-serie") || key.equals("tvg-series") || key.equals("serie-title")) {
                        hasSerieId = true;
                    }
                }
                name = displayName(name, tvgName);
            } else if (!line.isEmpty() && !line.startsWith("#") && name != null) {
                if (isStreamUrl(line)) {
                    if (typePlaylist || isNestedPlaylistUrl(line)) {
                        if (!out.nestedPlaylists.contains(line)) out.nestedPlaylists.add(line);
                    } else {
                        Kind kind = classify(name, group, line, type, hasSeason, hasEpisode, hasSerieId);
                        if (kind == Kind.LIVE) {
                            out.channels.add(new Channel(name, cleanGroup(group), line));
                        }
                    }
                }
                name = null;
            }
        }
        return out;
    }

    /**
     * Channel-list URLs (not live HLS). {@code .m3u8} is treated as a stream; plain {@code .m3u}
     * and IPTV index paths are followed for discovery.
     */
    static boolean isNestedPlaylistUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        if (path.endsWith(".m3u8")) return false;
        if (path.endsWith(".m3u")) return true;
        // iptv-org / similar index files without an extension sometimes end in /country/xx
        if (u.contains("iptv-org.github.io") && path.contains("/iptv/") && !path.contains(".m3u8")) {
            return path.endsWith(".m3u") || path.contains("/countries/") || path.contains("/categories/")
                    || path.contains("/languages/") || path.contains("/regions/");
        }
        return false;
    }

    /** True when this library row still looks like a live channel (scrubs already-imported VOD). */
    static boolean isLiveChannel(Channel ch) {
        if (ch == null) return false;
        return classify(ch.name, ch.group, ch.url, null, false, false, false) == Kind.LIVE;
    }

    static Kind classify(String name, String group, String url,
                         String typeAttr, boolean hasSeason, boolean hasEpisode, boolean hasSerieId) {
        if (hasSerieId || (hasSeason && hasEpisode) || hasSeason || hasEpisode) {
            // Season/episode tags are series even when group-title is vague.
            if (hasSerieId || hasSeason || hasEpisode) {
                String t = norm(typeAttr);
                if (t != null && (t.equals("movie") || t.equals("film") || t.equals("vod"))) return Kind.MOVIE;
                return Kind.SERIES;
            }
        }

        String t = norm(typeAttr);
        if (t != null) {
            if (isOneOf(t, "movie", "movies", "film", "films", "vod", "video")) return Kind.MOVIE;
            if (isOneOf(t, "serie", "series", "show", "shows", "episode", "tvshow", "tv-show")) return Kind.SERIES;
            if (isOneOf(t, "radio", "audio")) return Kind.RADIO;
            if (isOneOf(t, "live", "tv", "channel", "channels", "linear")) return Kind.LIVE;
        }

        String u = url == null ? "" : url.toLowerCase(Locale.US);
        if (pathHas(u, "/series/", "/serie/", "/show/", "/shows/", "/tvshow/", "/tvshows/", "/episod")) {
            return Kind.SERIES;
        }
        if (pathHas(u, "/movie/", "/movies/", "/film/", "/films/", "/vod/", "/cinema/", "/on-demand/", "/ondemand/")) {
            return Kind.MOVIE;
        }
        if (u.matches("(?i).*\\.(mp4|mkv|avi|mov|m4v|wmv|flv|ts|mpg|mpeg)(\\?.*)?$")
                && !u.contains(".m3u8") && !u.contains("/live/") && !u.contains("/play/")) {
            // File VOD — episode markers decide series vs movie.
            return EPISODE.matcher(safe(name)).find() ? Kind.SERIES : Kind.MOVIE;
        }

        String g = safe(group).toLowerCase(Locale.US);
        if (vodGroup(g)) {
            if (seriesGroup(g) || EPISODE.matcher(safe(name)).find()) return Kind.SERIES;
            if (movieGroup(g)) return Kind.MOVIE;
            // Generic VOD / Netflix dumps: treat as series if the title looks like a show episode,
            // otherwise movie — either way, not a live channel.
            return EPISODE.matcher(safe(name)).find() ? Kind.SERIES : Kind.MOVIE;
        }
        if (seriesGroup(g)) return Kind.SERIES;
        if (movieGroup(g)) return Kind.MOVIE;
        if (radioGroup(g)) return Kind.RADIO;

        String n = safe(name);
        if (EPISODE.matcher(n).find()) return Kind.SERIES;
        // "Title (2019)" with no live/channel cues is almost always a film entry.
        if (YEAR_TITLE.matcher(n).find() && !looksLikeChannelBrand(n)) return Kind.MOVIE;

        return Kind.LIVE;
    }

    private static String displayName(String extinfTitle, String tvgName) {
        String title = cleanName(extinfTitle);
        String tagged = cleanName(tvgName);
        if (tagged.isEmpty()) return title.isEmpty() ? "Channel" : title;
        if (title.isEmpty() || title.equalsIgnoreCase("Channel")) return tagged;
        // Prefer the human title when tvg-name is an ugly id (no spaces, looks like a slug).
        if (!tagged.contains(" ") && tagged.length() <= 6 && title.length() > tagged.length()) return title;
        // Prefer tvg-name when EXTINF is a long episode line and tvg-name is the show/channel.
        if (EPISODE.matcher(title).find() && !EPISODE.matcher(tagged).find()) return tagged;
        return title;
    }

    private static String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        // Drop a single layer of wrapping quotes.
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        s = QUALITY_TAIL.matcher(s).replaceAll("").trim();
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        return s;
    }

    private static String cleanGroup(String group) {
        String g = cleanName(group);
        return g.isEmpty() ? "Other" : g;
    }

    private static boolean vodGroup(String g) {
        if (containsWord(g, "live") || containsWord(g, "channel") || containsWord(g, "channels")) {
            return false;
        }
        return containsWord(g, "vod")
                || containsWord(g, "on demand")
                || containsWord(g, "ondemand")
                || containsWord(g, "catchup")
                || containsWord(g, "catch up")
                || containsWord(g, "replay")
                || containsWord(g, "netflix")
                || containsWord(g, "disney")
                || containsWord(g, "prime video")
                || containsWord(g, "amazon prime")
                || containsWord(g, "hulu")
                || containsWord(g, "apple tv+")
                || containsWord(g, "apple tv plus")
                || containsWord(g, "paramount+")
                || containsWord(g, "peacock")
                || containsWord(g, "crunchyroll");
    }

    private static boolean seriesGroup(String g) {
        return containsWord(g, "series")
                || containsWord(g, "serie")
                || containsWord(g, "séries")
                || containsWord(g, "tv show")
                || containsWord(g, "tv shows")
                || containsWord(g, "tvshows")
                || containsWord(g, "shows")
                || containsWord(g, "episodes")
                || containsWord(g, "episode")
                || containsWord(g, "temporada")
                || containsWord(g, "stagione")
                || g.contains("|series")
                || g.contains("series|")
                || g.endsWith(" series")
                || g.startsWith("series ");
    }

    private static boolean movieGroup(String g) {
        return containsWord(g, "movie")
                || containsWord(g, "movies")
                || containsWord(g, "film")
                || containsWord(g, "films")
                || containsWord(g, "cinema")
                || containsWord(g, "peliculas")
                || containsWord(g, "películas")
                || containsWord(g, "filme")
                || g.contains("|movie")
                || g.contains("movies|")
                || g.endsWith(" movies")
                || g.startsWith("movies ");
    }

    private static boolean radioGroup(String g) {
        return containsWord(g, "radio") || containsWord(g, "radios");
    }

    /** Brands like "CNN (US)" should stay live even with parentheses. */
    private static boolean looksLikeChannelBrand(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.contains("news") || n.contains("sport") || n.contains("tv") || n.contains("channel")
                || n.contains("radio") || n.contains("espn") || n.contains("bbc") || n.contains("cnn")
                || n.contains("fox") || n.contains("nbc") || n.contains("abc") || n.contains("cbs")
                || n.contains("hbo") || n.contains("sky") || n.contains("mtv") || n.contains("discovery");
    }

    private static boolean containsWord(String hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty()) return false;
        int i = hay.indexOf(needle);
        while (i >= 0) {
            boolean left = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
            int end = i + needle.length();
            boolean right = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
            if (left && right) return true;
            i = hay.indexOf(needle, i + 1);
        }
        return false;
    }

    private static boolean pathHas(String url, String... parts) {
        for (String p : parts) if (url.contains(p)) return true;
        return false;
    }

    private static boolean isOneOf(String v, String... opts) {
        for (String o : opts) if (v.equals(o)) return true;
        return false;
    }

    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.US);
        return t.isEmpty() ? null : t;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** HTTP(S) and common live schemes found in M3U playlists. */
    private static boolean isStreamUrl(String line) {
        String s = line.toLowerCase(Locale.US);
        return s.startsWith("http://") || s.startsWith("https://")
                || s.startsWith("rtsp://") || s.startsWith("rtsps://")
                || s.startsWith("rtp://") || s.startsWith("udp://")
                || s.startsWith("rtmp://") || s.startsWith("mms://");
    }
}
