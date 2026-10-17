/*
 * Port: the weather widget of Google's "News and Weather" 1.3.01 (the Gingerbread era
 * "Genie" widget). Layout, graphics and the rendering (MiniWidgetView.renderWeather(),
 * renderWeatherExtra(), renderViewState()) follow the original; Google's weather service is
 * gone, so the data comes from OpenWeatherMap with the user's own API key and a location set
 * in WeatherWidgetConfigure. Tapping the widget opens the weather app chosen there.
 */
package com.google.android.apps.genie.geniewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Html;
import android.view.View;
import android.widget.RemoteViews;

import com.android.launcher.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class WeatherWidgetProvider extends AppWidgetProvider {
    static final String PREFS = "genie_weather_widget";
    static final String ACTION_RETRY = "com.android.launcher.genie.WEATHER_RETRY";

    static String key(int id, String name) {
        return id + "_" + name;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        // Android updates every 30 min (updatePeriodMillis); longer intervals skip updates
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        final long now = System.currentTimeMillis();
        final java.util.ArrayList<Integer> due = new java.util.ArrayList<Integer>();
        for (int id : ids) {
            final long interval = prefs.getInt(key(id, "interval"), DEFAULT_INTERVAL) * 60000L;
            // 5 min tolerance, the 30 min updates aren't exact
            if (now - prefs.getLong(key(id, "updated"), 0) >= interval - 5 * 60000L) {
                due.add(id);
            } else if (prefs.contains(key(id, "temp"))) {
                // not due: show the last data again (after an app update or a reboot the
                // widget starts with its initial layout, "Loading...")
                manager.updateAppWidget(id, build(context, id, State.READY));
            }
        }
        final int[] dueIds = new int[due.size()];
        for (int i = 0; i < dueIds.length; i++) dueIds[i] = due.get(i);
        refresh(context, dueIds, goAsync());
    }

    /** Refresh intervals (original: "Refresh interval"), in minutes; 30 is the default. */
    static final int[] INTERVALS = { 30, 60, 120 };
    static final int DEFAULT_INTERVAL = 30;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_RETRY.equals(intent.getAction())) {
            final int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
            refresh(context, new int[] { id }, goAsync());
            return;
        }
        super.onReceive(context, intent);
    }

    /** Port: Android 11, the launcher reported a new size: the layout for it (see build()) */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id,
            android.os.Bundle newOptions) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) return;
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        if (!prefs.contains(key(id, "temp"))) return; // still the initial layout
        manager.updateAppWidget(id, build(context, id, State.READY));
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        final SharedPreferences.Editor e = prefs.edit();
        for (int id : ids) {
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith(id + "_")) e.remove(k);
            }
        }
        e.apply();
    }

    /** Loads the weather for the widgets off the main thread and renders them. */
    static void refresh(final Context context, final int[] ids, final PendingResult result) {
        final Context app = context.getApplicationContext();
        final AppWidgetManager manager = AppWidgetManager.getInstance(app);
        new Thread(() -> {
            try {
                for (int id : ids) {
                    final SharedPreferences prefs = app.getSharedPreferences(PREFS, 0);
                    if (!prefs.contains(key(id, "apikey"))) continue; // not configured yet
                    // loading state while there is no data yet (renderViewState())
                    if (!prefs.contains(key(id, "temp"))) {
                        manager.updateAppWidget(id, build(app, id, State.LOADING));
                    }
                    State state;
                    try {
                        load(app, id);
                        state = State.READY;
                    } catch (Exception e) {
                        android.util.Log.w("WeatherWidget", "update failed: " + e);
                        state = State.FAILED;
                    }
                    manager.updateAppWidget(id, build(app, id, state));
                }
            } finally {
                if (result != null) result.finish();
            }
        }).start();
    }

    enum State { LOADING, READY, FAILED }

    /**
     * Port: the original layout up to the width of a Gingerbread 4x1 (Nexus S: 320dp), the
     * wide layout beyond (responsive RemoteViews, Android 12+: the host view picks the one
     * that fits its size, in any launcher).
     */
    static RemoteViews build(Context context, int id, State state) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            // Android 11: no responsive RemoteViews; the layout for the width the launcher
            // reported (OPTION_APPWIDGET_MIN_WIDTH: portrait), the normal one without it
            final int width = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            return build(context, id, state, width >= WIDE_MIN_WIDTH
                    ? R.layout.genie_weather_widget_wide : R.layout.genie_weather_widget);
        }
        final java.util.Map<android.util.SizeF, RemoteViews> sizes =
                new java.util.HashMap<android.util.SizeF, RemoteViews>();
        sizes.put(new android.util.SizeF(294, 72),
                build(context, id, state, R.layout.genie_weather_widget));
        sizes.put(new android.util.SizeF(WIDE_MIN_WIDTH, 72),
                build(context, id, state, R.layout.genie_weather_widget_wide));
        return new RemoteViews(sizes);
    }

    /** Port: wider than a Gingerbread 4x1 (4 x 80dp cells on the Nexus S) */
    private static final float WIDE_MIN_WIDTH = 330;

    private static RemoteViews build(Context context, int id, State state, int layout) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        final RemoteViews v = new RemoteViews(context.getPackageName(), layout);
        final boolean hasData = prefs.contains(key(id, "temp"));

        // renderViewState()
        final int section = state == State.LOADING ? (hasData ? R.id.content : R.id.loading)
                : state == State.FAILED ? (hasData ? R.id.content : R.id.retry) : R.id.content;
        v.setViewVisibility(R.id.loading, section == R.id.loading ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.retry, section == R.id.retry ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.content, section == R.id.content ? View.VISIBLE : View.GONE);

        if (hasData) {
            // renderWeather(): icon ("l" size for the weather widget) and temperature
            v.setViewVisibility(R.id.weather_icon, View.VISIBLE);
            v.setViewVisibility(R.id.weather_temp, View.VISIBLE);
            v.setViewVisibility(R.id.weather_unavailable, View.GONE);
            v.setTextViewText(R.id.weather_temp, temp(prefs.getInt(key(id, "temp"), 0)));
            final Integer icon = ICONS.get(prefs.getString(key(id, "icon"), "unknown"));
            if (icon != null) {
                v.setImageViewResource(R.id.weather_icon, icon);
            } else {
                v.setViewVisibility(R.id.weather_icon, View.INVISIBLE);
            }
            // renderWeatherExtra()
            v.setViewVisibility(R.id.weather_extra, View.VISIBLE);
            v.setTextViewText(R.id.weather_city, prefs.getString(key(id, "name"), ""));
            final String condition = prefs.getString(key(id, "condition"), null);
            if (condition != null) {
                v.setViewVisibility(R.id.weather_condition, View.VISIBLE);
                v.setTextViewText(R.id.weather_condition, condition);
            } else {
                v.setViewVisibility(R.id.weather_condition, View.GONE);
            }
            v.setTextViewText(R.id.weather_high, prefs.contains(key(id, "high"))
                    ? temp(prefs.getInt(key(id, "high"), 0)) : Html.fromHtml("--&deg;"));
            v.setTextViewText(R.id.weather_low, prefs.contains(key(id, "low"))
                    ? temp(prefs.getInt(key(id, "low"), 0)) : Html.fromHtml("--&deg;"));
        } else if (state == State.FAILED) {
            v.setTextViewText(R.id.retrying, context.getString(R.string.genie_retrying));
        }

        // Tap: the chosen weather app (or the settings if there's none)
        Intent open = null;
        final String app = prefs.getString(key(id, "app"), null);
        if (app != null) {
            open = context.getPackageManager().getLaunchIntentForPackage(app);
        }
        if (open == null) {
            open = new Intent(context, WeatherWidgetConfigure.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("genie:" + id));
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pi = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.weather_widget, pi);
        v.setOnClickPendingIntent(R.id.weather, pi);
        v.setOnClickPendingIntent(R.id.weather_unavailable, pi);
        v.setOnClickPendingIntent(R.id.loading, pi);
        v.setOnClickPendingIntent(R.id.retrynow, PendingIntent.getBroadcast(context, id,
                new Intent(ACTION_RETRY).setClass(context, WeatherWidgetProvider.class)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    /** localizeTemp(), metric */
    private static CharSequence temp(int t) {
        return Html.fromHtml(t + "&deg;");
    }

    // ------------------------------------------------------------------ OpenWeatherMap

    static String get(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        try {
            final int code = c.getResponseCode();
            final InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            for (int n; in != null && (n = in.read(buf)) > 0;) out.write(buf, 0, n);
            if (code >= 400) throw new java.io.IOException("HTTP " + code + ": " + out);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String lang() {
        return Locale.getDefault().getLanguage();
    }

    private static void load(Context context, int id) throws Exception {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        final String apiKey = Uri.encode(prefs.getString(key(id, "apikey"), ""));
        final String loc = "lat=" + prefs.getFloat(key(id, "lat"), 0)
                + "&lon=" + prefs.getFloat(key(id, "lon"), 0);
        final String base = "https://api.openweathermap.org/data/2.5/";

        final JSONObject now = new JSONObject(get(base + "weather?" + loc
                + "&units=metric&lang=" + lang() + "&appid=" + apiKey));
        final JSONObject w = now.getJSONArray("weather").getJSONObject(0);
        final int temp = (int) Math.round(now.getJSONObject("main").getDouble("temp"));
        final int tz = now.optInt("timezone", 0);
        String condition = w.optString("description", null);
        if (condition != null && condition.length() > 0) {
            condition = condition.substring(0, 1).toUpperCase(Locale.getDefault())
                    + condition.substring(1);
        }
        final boolean night = w.optString("icon", "d").endsWith("n");

        // today's high and low from the 3-hourly forecast (and the current temperature)
        int high = temp, low = temp;
        try {
            final JSONObject f = new JSONObject(get(base + "forecast?" + loc
                    + "&units=metric&appid=" + apiKey));
            final JSONArray list = f.getJSONArray("list");
            final long today = Math.floorDiv(System.currentTimeMillis() / 1000 + tz, 86400);
            for (int i = 0; i < list.length(); i++) {
                final JSONObject e = list.getJSONObject(i);
                if (Math.floorDiv(e.getLong("dt") + tz, 86400) != today) continue;
                final JSONObject m = e.getJSONObject("main");
                high = Math.max(high, (int) Math.round(m.getDouble("temp_max")));
                low = Math.min(low, (int) Math.round(m.getDouble("temp_min")));
            }
        } catch (Exception e) {
            android.util.Log.w("WeatherWidget", "forecast: " + e);
        }

        prefs.edit()
                .putInt(key(id, "temp"), temp)
                .putInt(key(id, "high"), high)
                .putInt(key(id, "low"), low)
                .putString(key(id, "condition"), condition)
                .putString(key(id, "icon"), icon(w.optInt("id", 0), night))
                .putLong(key(id, "updated"), System.currentTimeMillis())
                .apply();
    }

    /** The widget's (Google Weather) icons, "l" size. */
    static final java.util.HashMap<String, Integer> ICONS = new java.util.HashMap<>();
    static {
        ICONS.put("chance_of_rain", R.drawable.genie_ic_weather_chance_of_rain_l);
        ICONS.put("chance_of_rain_night", R.drawable.genie_ic_weather_chance_of_rain_night_l);
        ICONS.put("chance_snow", R.drawable.genie_ic_weather_chance_snow_l);
        ICONS.put("chance_storm", R.drawable.genie_ic_weather_chance_storm_l);
        ICONS.put("clear_day", R.drawable.genie_ic_weather_clear_day_l);
        ICONS.put("clear", R.drawable.genie_ic_weather_clear_l);
        ICONS.put("clear_night", R.drawable.genie_ic_weather_clear_night_l);
        ICONS.put("cloudy", R.drawable.genie_ic_weather_cloudy_l);
        ICONS.put("flurries", R.drawable.genie_ic_weather_flurries_l);
        ICONS.put("fog", R.drawable.genie_ic_weather_fog_l);
        ICONS.put("heavy_rain", R.drawable.genie_ic_weather_heavy_rain_l);
        ICONS.put("icy_sleet", R.drawable.genie_ic_weather_icy_sleet_l);
        ICONS.put("mist", R.drawable.genie_ic_weather_mist_l);
        ICONS.put("partly_cloudy", R.drawable.genie_ic_weather_partly_cloudy_l);
        ICONS.put("rain_day", R.drawable.genie_ic_weather_rain_day_l);
        ICONS.put("rain", R.drawable.genie_ic_weather_rain_l);
        ICONS.put("rain_night", R.drawable.genie_ic_weather_rain_night_l);
        ICONS.put("snow", R.drawable.genie_ic_weather_snow_l);
        ICONS.put("snow_rain", R.drawable.genie_ic_weather_snow_rain_l);
        ICONS.put("sunny", R.drawable.genie_ic_weather_sunny_l);
        ICONS.put("thunderstorm", R.drawable.genie_ic_weather_thunderstorm_l);
        ICONS.put("unknown", R.drawable.genie_ic_weather_unknown_l);
        ICONS.put("windy", R.drawable.genie_ic_weather_windy_l);
    }

    /** OpenWeatherMap condition codes to the widget's (Google Weather) icons. */
    static String icon(int code, boolean night) {
        if (code >= 200 && code < 300) {
            return code == 200 || code == 210 || code == 230 ? "chance_storm" : "thunderstorm";
        }
        if (code >= 300 && code < 400) return "mist";                      // drizzle
        if (code == 500) return night ? "chance_of_rain_night" : "chance_of_rain";
        if (code == 501) return "rain";
        if (code >= 502 && code <= 504) return "heavy_rain";
        if (code == 511) return "icy_sleet";
        if (code >= 520 && code < 600) return night ? "rain_night" : "rain_day"; // showers
        if (code == 600) return "chance_snow";
        if (code == 601 || code == 602) return "snow";
        if (code >= 611 && code <= 613) return "icy_sleet";
        if (code == 615 || code == 616) return "snow_rain";
        if (code >= 620 && code < 700) return "flurries";
        if (code == 701) return "mist";
        if (code == 771 || code == 781 || code == 731 || code == 751 || code == 761) {
            return "windy";
        }
        if (code >= 700 && code < 800) return "fog";
        if (code == 800) return night ? "clear_night" : "sunny";
        if (code == 801 || code == 802) return night ? "clear_night" : "partly_cloudy";
        if (code == 803 || code == 804) return "cloudy";
        return "unknown";
    }

    /** Updates one widget now (after configuring it). */
    static void update(Context context, int id) {
        refresh(context, new int[] { id }, null);
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, WeatherWidgetProvider.class);
    }
}
