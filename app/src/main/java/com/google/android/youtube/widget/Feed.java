/*
 * Port: data of the YouTube widget (YouTube 2.5.16, "Froyo" widget). Google's GData API the
 * original used is gone, so the widget shows the newest videos of the user's subscriptions
 * from the channels' public RSS feeds (no API key, no login). The channel IDs come from
 * the launcher settings (YouTubeSettings; typed in, or imported from the subscriptions.csv of Google Takeout).
 */
package com.google.android.youtube.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Feed {
    static final String PREFS = "youtube_widget";
    static final String KEY_CHANNELS = "channels";   // channel IDs, one per line
    static final String KEY_STATE = "state";         // STATE_*
    static final String KEY_UPDATED = "updated";

    static final String STATE_LOADING = "loading";
    static final String STATE_OK = "ok";
    static final String STATE_ERROR = "error";

    /** The flipper shows this many videos (the newest ones of all channels). */
    static final int MAX_VIDEOS = 10;
    /** At most this many per channel, so a channel posting a lot doesn't take them all. */
    static final int MAX_PER_CHANNEL = 3;
    private static final int MAX_THUMB_BYTES = 256 * 1024;
    static final String KEY_INTERVAL = "interval";   // minutes between refreshes
    /** Refresh intervals (minutes) to choose from; 2 h is the default. */
    static final int[] INTERVALS = { 30, 60, 120, 240, 480, 720 };
    static final int DEFAULT_INTERVAL = 120;

    static int intervalMinutes(Context context) {
        return prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
    }

    private static final Pattern CHANNEL_ID = Pattern.compile("UC[0-9A-Za-z_-]{22}");
    private static final String ATOM = "http://www.w3.org/2005/Atom";

    static final class Video {
        String id;
        String title;
        String author;
        long published;
        long views = -1;
        String channel;
    }

    // ------------------------------------------------------------------ channels

    /** All channel IDs in the text (IDs, channel links or the lines of subscriptions.csv). */
    static LinkedHashSet<String> parseChannels(String text) {
        final LinkedHashSet<String> ids = new LinkedHashSet<String>();
        final Matcher m = CHANNEL_ID.matcher(text == null ? "" : text);
        while (m.find()) ids.add(m.group());
        return ids;
    }

    static LinkedHashSet<String> channels(Context context) {
        return parseChannels(prefs(context).getString(KEY_CHANNELS, ""));
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    // ------------------------------------------------------------------ cache

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "youtube_widget_feed.json");
    }

    private static File thumbDir(Context context) {
        final File dir = new File(context.getCacheDir(), "youtube_widget");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    static File thumbFile(Context context, String videoId) {
        // "_wide": 16:9 files (older versions stored 4:3 ones)
        return new File(thumbDir(context), videoId + "_wide.jpg");
    }

    static synchronized List<Video> loadCache(Context context) {
        final ArrayList<Video> list = new ArrayList<Video>();
        try {
            final File f = cacheFile(context);
            if (!f.isFile()) return list;
            final java.io.FileInputStream in = new java.io.FileInputStream(f);
            final String json;
            try {
                json = readFully(in);
            } finally {
                in.close();
            }
            final JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                final Video v = new Video();
                v.id = o.getString("id");
                v.title = o.optString("title");
                v.author = o.optString("author");
                v.published = o.optLong("published");
                v.views = o.optLong("views", -1);
                v.channel = o.optString("channel");
                list.add(v);
            }
        } catch (Exception e) {
            android.util.Log.w("YouTubeWidget", "cache: " + e);
            list.clear();
        }
        return list;
    }

    private static synchronized void saveCache(Context context, List<Video> videos)
            throws Exception {
        final JSONArray a = new JSONArray();
        for (Video v : videos) {
            a.put(new JSONObject()
                    .put("id", v.id).put("title", v.title).put("author", v.author)
                    .put("published", v.published).put("views", v.views)
                    .put("channel", v.channel));
        }
        final File tmp = new File(context.getFilesDir(), "youtube_widget_feed.tmp");
        final FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(a.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        tmp.renameTo(cacheFile(context));
    }

    static String readFully(InputStream in) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    // ------------------------------------------------------------------ refresh

    /** True if the videos were loaded recently and without an error: no need to use the network again. */
    static boolean isFresh(Context context) {
        final SharedPreferences p = prefs(context);
        final long updated = p.getLong(KEY_UPDATED, 0);
        return STATE_OK.equals(p.getString(KEY_STATE, STATE_OK))
                && updated > 0
                && System.currentTimeMillis() - updated < intervalMinutes(context) * 60000L
                && System.currentTimeMillis() >= updated;
    }

    /**
     * Loads the feeds of all channels, keeps the newest videos, fetches their thumbnails and
     * saves it all. Blocking: runs in the FeedJob. Returns false if no feed could be loaded.
     */
    static boolean refresh(Context context) {
        final Context app = context.getApplicationContext();
        final LinkedHashSet<String> ids = channels(app);
        if (ids.isEmpty()) return true;

        final ExecutorService pool = Executors.newFixedThreadPool(6);
        final ArrayList<Future<List<Video>>> futures = new ArrayList<Future<List<Video>>>();
        for (final String id : ids) {
            futures.add(pool.submit(() -> {
                // UULF + the channel ID without its "UC" is the channel's automatic "Videos"
                // playlist: regular uploads only, no Shorts and no live streams
                final HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://www.youtube.com/feeds/videos.xml?playlist_id=UULF"
                        + id.substring(2))
                        .openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                try {
                    if (c.getResponseCode() != 200) {
                        throw new java.io.IOException("HTTP " + c.getResponseCode());
                    }
                    final List<Video> list = parse(c.getInputStream());
                    for (Video v : list) v.channel = id;
                    return list;
                } finally {
                    c.disconnect();
                }
            }));
        }
        final ArrayList<Video> all = new ArrayList<Video>();
        int loaded = 0;
        for (Future<List<Video>> f : futures) {
            try {
                final List<Video> list = f.get();
                loaded++;
                // the feed is newest first
                all.addAll(list.subList(0, Math.min(MAX_PER_CHANNEL, list.size())));
            } catch (Exception e) {
                android.util.Log.w("YouTubeWidget", "feed: " + e);
            }
        }
        pool.shutdown();
        if (loaded == 0) return false;

        Collections.sort(all, new Comparator<Video>() {
            @Override
            public int compare(Video a, Video b) {
                return a.published < b.published ? 1 : a.published > b.published ? -1 : 0;
            }
        });
        final ArrayList<Video> top = new ArrayList<Video>(
                all.subList(0, Math.min(MAX_VIDEOS, all.size())));

        // thumbnails (the ones already loaded stay); the ones no longer shown are removed
        final HashSet<String> keep = new HashSet<String>();
        for (Video v : top) {
            keep.add(thumbFile(app, v.id).getName());
            try {
                if (!thumbFile(app, v.id).isFile()) fetchThumbnail(app, v.id);
            } catch (Exception e) {
                android.util.Log.w("YouTubeWidget", "thumbnail " + v.id + ": " + e);
            }
        }
        final File[] old = thumbDir(app).listFiles();
        if (old != null) {
            for (File f : old) if (!keep.contains(f.getName())) f.delete();
        }
        try {
            saveCache(app, top);
        } catch (Exception e) {
            android.util.Log.w("YouTubeWidget", "save: " + e);
            return false;
        }
        prefs(app).edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
        return true;
    }

    /**
     * The thumbnail (mqdefault.jpg, 320x180, 16:9) stored as the JPEG it is. The widget hands
     * the bytes to the launcher as an Icon, so its update stays small (decoded bitmaps of ten
     * videos would exceed the Binder limit).
     */
    private static void fetchThumbnail(Context context, String videoId) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(
                "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        final byte[] data;
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            data = readBytes(c.getInputStream(), MAX_THUMB_BYTES);
        } finally {
            c.disconnect();
        }
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) throw new java.io.IOException("not an image");

        final File tmp = new File(thumbDir(context), videoId + ".tmp");
        final FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(data);
        } finally {
            fo.close();
        }
        tmp.renameTo(thumbFile(context, videoId));
    }

    /** The stored thumbnail of a video (a JPEG), or null. */
    static byte[] thumbBytes(Context context, String videoId) {
        try {
            final File f = thumbFile(context, videoId);
            if (!f.isFile()) return null;
            final java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                return readBytes(in, MAX_THUMB_BYTES);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readBytes(InputStream in, int max) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0;) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new java.io.IOException("too large");
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ Atom

    /** The entries of a channel's Atom feed. */
    static List<Video> parse(InputStream in) throws Exception {
        final ArrayList<Video> list = new ArrayList<Video>();
        final XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, null);
        final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
                Locale.ROOT);
        boolean inEntry = false, inAuthor = false;
        Video cur = null;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                final String n = p.getName();
                if ("entry".equals(n)) {
                    inEntry = true;
                    cur = new Video();
                } else if (!inEntry) {
                    continue;
                } else if ("author".equals(n)) {
                    inAuthor = true;
                } else if ("name".equals(n) && inAuthor) {
                    cur.author = p.nextText();
                } else if ("videoId".equals(n)) {
                    cur.id = p.nextText();
                } else if ("title".equals(n) && ATOM.equals(p.getNamespace())) {
                    cur.title = p.nextText();
                } else if ("published".equals(n)) {
                    try {
                        cur.published = date.parse(p.nextText().trim()).getTime();
                    } catch (Exception e) {
                        cur.published = 0;
                    }
                } else if ("statistics".equals(n)) {
                    try {
                        cur.views = Long.parseLong(p.getAttributeValue(null, "views"));
                    } catch (Exception e) {
                        cur.views = -1;
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                final String n = p.getName();
                if ("author".equals(n)) {
                    inAuthor = false;
                } else if ("entry".equals(n)) {
                    inEntry = false;
                    if (cur != null && cur.id != null && cur.title != null) list.add(cur);
                    cur = null;
                }
            }
        }
        return list;
    }

    private Feed() {}

}
