/*
 * Port: news data of the "News and Weather" 1.3.01 widget (the Gingerbread era "Genie"
 * widget). Google's news service the original used is gone, so the stories come from RSS /
 * Atom feeds the user adds in the launcher settings (NewsSettings): the newest stories of all
 * feeds, newest first, loaded by NewsFeedJob. The widget shows the headline and the feed's
 * name as the story's source, like the original (headline and source).
 */
package com.google.android.apps.genie.geniewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Html;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
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

final class NewsFeed {
    static final String PREFS = "genie_news_widget";
    static final String KEY_FEEDS = "feeds";         // feed URLs, one per line
    static final String KEY_STATE = "state";         // STATE_*
    static final String KEY_UPDATED = "updated";
    static final String KEY_INTERVAL = "interval";   // minutes between refreshes

    static final String STATE_LOADING = "loading";
    static final String STATE_OK = "ok";
    static final String STATE_ERROR = "error";

    /** The widget flips through this many stories (original: getNewsStories(20)). */
    static final int MAX_STORIES = 20;
    /** At most this many per feed, so a feed posting a lot doesn't take them all. */
    static final int MAX_PER_FEED = 8;
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;

    /** Refresh intervals (minutes) to choose from; 1 h is the default. */
    static final int[] INTERVALS = { 30, 60, 120, 240, 480, 720 };
    static final int DEFAULT_INTERVAL = 60;

    static final class Story {
        String title;
        String source;
        String link;
        long published;
    }

    private static final class Parsed {
        String title;
        final ArrayList<Story> stories = new ArrayList<Story>();
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    static int intervalMinutes(Context context) {
        return prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
    }

    // ------------------------------------------------------------------ feeds

    /**
     * The feed links in the text (separated by blanks or line breaks). "http://" and "feed://"
     * become "https://" (cleartext traffic is off), a link without a scheme gets "https://".
     */
    static LinkedHashSet<String> parseFeeds(String text) {
        final LinkedHashSet<String> urls = new LinkedHashSet<String>();
        if (text == null) return urls;
        for (String token : text.split("[\\s,;]+")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            final String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("https://")) {
                // as it is
            } else if (lower.startsWith("http://")) {
                t = "https://" + t.substring(7);
            } else if (lower.startsWith("feed://")) {
                t = "https://" + t.substring(7);
            } else if (t.indexOf('.') > 0 && !t.contains("://")) {
                t = "https://" + t;
            } else {
                continue;
            }
            if (Uri.parse(t).getHost() == null) continue;
            urls.add(t);
        }
        return urls;
    }

    static LinkedHashSet<String> feeds(Context context) {
        return parseFeeds(prefs(context).getString(KEY_FEEDS, ""));
    }

    // ------------------------------------------------------------------ cache

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "genie_news_widget.json");
    }

    static synchronized List<Story> loadCache(Context context) {
        final ArrayList<Story> list = new ArrayList<Story>();
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
                final Story s = new Story();
                s.title = o.getString("title");
                s.source = o.optString("source", null);
                s.link = o.optString("link", null);
                s.published = o.optLong("published");
                list.add(s);
            }
        } catch (Exception e) {
            android.util.Log.w("NewsWidget", "cache: " + e);
            list.clear();
        }
        return list;
    }

    private static synchronized void saveCache(Context context, List<Story> stories)
            throws Exception {
        final JSONArray a = new JSONArray();
        for (Story s : stories) {
            a.put(new JSONObject()
                    .put("title", s.title).put("source", s.source)
                    .put("link", s.link).put("published", s.published));
        }
        final File tmp = new File(context.getFilesDir(), "genie_news_widget.tmp");
        final FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(a.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        tmp.renameTo(cacheFile(context));
    }

    /** The feeds changed: the stories of the old ones must not stay. */
    static synchronized void clearCache(Context context) {
        cacheFile(context).delete();
        prefs(context).edit().remove(KEY_UPDATED).apply();
    }

    static String readFully(InputStream in) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    // ------------------------------------------------------------------ refresh

    /**
     * A periodic job runs once per period, somewhere in its flex window (NewsFeedJob), so two
     * runs can be a little closer together than the interval: data that is only this much
     * younger than the interval is already due, or the run would skip it and the next one
     * would come a whole period later.
     */
    static long toleranceMs(int intervalMinutes) {
        // the system's minimum flex is 5 minutes or 5 % of the period, whichever is more
        return Math.max(5 * 60000L, intervalMinutes * 3000L) + 2 * 60000L;
    }

    /** True if the stories were loaded recently and without an error: no need to use the network again. */
    static boolean isFresh(Context context) {
        final SharedPreferences p = prefs(context);
        final long updated = p.getLong(KEY_UPDATED, 0);
        return STATE_OK.equals(p.getString(KEY_STATE, STATE_OK))
                && updated > 0
                && System.currentTimeMillis() - updated < intervalMinutes(context) * 60000L
                        - toleranceMs(intervalMinutes(context))
                && System.currentTimeMillis() >= updated;
    }

    /**
     * Loads all feeds, keeps the newest stories and saves them. Blocking: runs in the
     * NewsFeedJob. Returns false if no feed could be loaded.
     */
    static boolean refresh(Context context) {
        final Context app = context.getApplicationContext();
        final LinkedHashSet<String> urls = feeds(app);
        if (urls.isEmpty()) return true;

        final ExecutorService pool = Executors.newFixedThreadPool(4);
        final ArrayList<Future<List<Story>>> futures = new ArrayList<Future<List<Story>>>();
        for (final String url : urls) {
            futures.add(pool.submit(() -> load(url)));
        }
        final ArrayList<Story> all = new ArrayList<Story>();
        int loaded = 0;
        for (Future<List<Story>> f : futures) {
            try {
                all.addAll(f.get());
                loaded++;
            } catch (Exception e) {
                android.util.Log.w("NewsWidget", "feed: " + e);
            }
        }
        pool.shutdown();
        if (loaded == 0) return false;

        // newest first; the same story in two feeds only once
        Collections.sort(all, new Comparator<Story>() {
            @Override
            public int compare(Story a, Story b) {
                return a.published < b.published ? 1 : a.published > b.published ? -1 : 0;
            }
        });
        final HashSet<String> seen = new HashSet<String>();
        final ArrayList<Story> top = new ArrayList<Story>();
        for (Story s : all) {
            if (top.size() >= MAX_STORIES) break;
            if (seen.add(s.link != null ? s.link : s.title)) top.add(s);
        }
        try {
            saveCache(app, top);
        } catch (Exception e) {
            android.util.Log.w("NewsWidget", "save: " + e);
            return false;
        }
        prefs(app).edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
        return true;
    }

    /** One feed: its newest stories, with the feed's name as their source. */
    private static List<Story> load(String url) throws Exception {
        final HttpURLConnection c = open(url);
        final Parsed parsed;
        try {
            parsed = parse(new LimitedInputStream(c.getInputStream(), MAX_FEED_BYTES));
        } finally {
            c.disconnect();
        }
        String source = parsed.title;
        if (source == null) {
            source = Uri.parse(url).getHost();
            if (source != null && source.startsWith("www.")) source = source.substring(4);
        }
        final long now = System.currentTimeMillis();
        final ArrayList<Story> list = new ArrayList<Story>();
        for (int i = 0; i < parsed.stories.size() && i < MAX_PER_FEED; i++) {
            final Story s = parsed.stories.get(i);
            s.source = source;
            // no date: the feed's order (newest first) decides, as if they were new
            if (s.published <= 0) s.published = now - i * 60000L;
            list.add(s);
        }
        return list;
    }

    /** Opens the feed; follows redirects (also from http to https, which HttpURLConnection doesn't). */
    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 5; i++) {
            final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GingerbreadLauncher");
            c.setRequestProperty("Accept",
                    "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
            final int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                final String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new java.io.IOException("redirect without target");
                url = new URL(new URL(url), location).toString();
                if (!url.startsWith("https://")) {
                    throw new java.io.IOException("redirect to cleartext: " + url);
                }
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
            return c;
        }
        throw new java.io.IOException("too many redirects");
    }

    // ------------------------------------------------------------------ RSS / Atom

    /** RSS 2.0, RSS 1.0 (RDF) and Atom: the feed's title and the stories, in the feed's order. */
    private static Parsed parse(InputStream in) throws Exception {
        final XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, null);
        final Parsed out = new Parsed();
        final ArrayList<String> stack = new ArrayList<String>();
        Story cur = null;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                final String n = p.getName();
                final String parent = stack.isEmpty() ? "" : stack.get(stack.size() - 1);
                if ("title".equals(n) || "link".equals(n) || "pubDate".equals(n)
                        || "published".equals(n) || "updated".equals(n) || "date".equals(n)) {
                    // a leaf: read it completely (this also consumes its end tag)
                    String href = null;
                    boolean other = false; // Atom: rel="self", "enclosure", ...
                    if ("link".equals(n)) {
                        final String rel = p.getAttributeValue(null, "rel");
                        other = rel != null && !"alternate".equals(rel);
                        href = p.getAttributeValue(null, "href");
                    }
                    final String text = leafText(p);
                    final boolean inItem = "item".equals(parent) || "entry".equals(parent);
                    if (inItem && cur != null) {
                        if ("title".equals(n)) {
                            if (cur.title == null) cur.title = clean(text);
                        } else if ("link".equals(n)) {
                            final String link = (href != null ? href : text).trim();
                            if (!other && cur.link == null && link.toLowerCase(Locale.ROOT)
                                    .startsWith("http")) {
                                cur.link = link;
                            }
                        } else if (cur.published <= 0) {
                            cur.published = parseDate(text);
                        }
                    } else if ("title".equals(n)
                            && ("channel".equals(parent) || "feed".equals(parent))) {
                        if (out.title == null) out.title = clean(text);
                    }
                } else {
                    stack.add(n);
                    if ("item".equals(n) || "entry".equals(n)) cur = new Story();
                }
            } else if (ev == XmlPullParser.END_TAG) {
                final String n = p.getName();
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                if (("item".equals(n) || "entry".equals(n)) && cur != null) {
                    if (cur.title != null) out.stories.add(cur);
                    cur = null;
                }
            }
        }
        return out;
    }

    /** The text of the element the parser just entered (nested tags skipped); ends after its end tag. */
    private static String leafText(XmlPullParser p) throws Exception {
        final StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            final int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            } else if (ev == XmlPullParser.TEXT && depth == 1) {
                sb.append(p.getText());
            } else if (ev == XmlPullParser.END_DOCUMENT) {
                break;
            }
        }
        return sb.toString();
    }

    /** Titles are plain text, but many feeds put HTML entities (or tags) in them. */
    private static String clean(String s) {
        if (s == null) return null;
        final String t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    private static final String[] DATE_FORMATS = {
            "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm Z", "d MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ssXX",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd" };

    /** RSS (RFC 822) and Atom / Dublin Core (ISO 8601) dates; 0 if it is none of them. */
    private static long parseDate(String s) {
        if (s == null) return 0;
        // ISO 8601: the fraction of the seconds isn't part of the patterns
        final String t = s.trim().replaceFirst("(\\d\\d:\\d\\d:\\d\\d)\\.\\d+", "$1");
        if (t.isEmpty()) return 0;
        for (String format : DATE_FORMATS) {
            try {
                return new SimpleDateFormat(format, Locale.ENGLISH).parse(t).getTime();
            } catch (Exception e) {
                // the next one
            }
        }
        return 0;
    }

    /** Stops reading after a limit, so a wrong (huge) page can't fill the memory. */
    private static final class LimitedInputStream extends FilterInputStream {
        private int mLeft;
        LimitedInputStream(InputStream in, int limit) {
            super(in);
            mLeft = limit;
        }
        @Override
        public int read() throws java.io.IOException {
            if (mLeft <= 0) return -1;
            final int b = super.read();
            if (b >= 0) mLeft--;
            return b;
        }
        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            if (mLeft <= 0) return -1;
            final int n = super.read(buf, off, Math.min(len, mLeft));
            if (n > 0) mLeft -= n;
            return n;
        }
    }

    private NewsFeed() {}
}
