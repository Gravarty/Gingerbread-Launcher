/*
 * Port: the "News and Weather" widget of Google's "News and Weather" 1.3.01 (MiniWidgetProvider,
 * layout/miniwidget, MiniWidgetView.render()): the weather on the left, the news on the right,
 * a new story every 8 seconds (ViewFlipper). The original has no "read more" button: a tap on
 * the story opens it, a tap on the weather opens the weather.
 *
 * Differences, because Google's news and weather services are gone:
 *  - the weather is the one of WeatherWidgetProvider (OpenWeatherMap, same settings, same data)
 *  - the news are the stories of the RSS / Atom feeds from the launcher settings (NewsFeed,
 *    loaded by NewsFeedJob); a tap on a story opens its link
 *  - like in the original, the stories have no pictures while the weather is shown
 *  - without feeds the widget asks for them (a tap opens the launcher settings)
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

import java.util.HashMap;
import java.util.List;

public class NewsWidgetProvider extends AppWidgetProvider {
    static final String ACTION_RETRY = "com.android.launcher.genie.NEWS_RETRY";

    private static final String STATE_LOADING = "loading";
    private static final String STATE_OK = "ok";
    private static final String STATE_ERROR = "error";

    @Override
    public void onEnabled(Context context) {
        NewsFeedJob.schedule(context);
        NewsFeedJob.schedulePeriodic(context);
    }

    @Override
    public void onDisabled(Context context) {
        NewsFeedJob.cancel(context);
    }

    /**
     * Energy: no update timer (updatePeriodMillis is 0, an alarm would wake the device). The
     * system calls this when a widget is added and after a boot or an app update: the cached
     * data is drawn at once, and NewsFeedJob loads what is missing or due (news and weather)
     * when there is a network and the battery isn't low.
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        WeatherWidgetProvider.migrate(context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0));
        for (int id : ids) {
            com.android.launcher2.LauncherOnly.update(context, manager, id, build(context, id));
        }
        NewsFeedJob.schedule(context);
        NewsFeedJob.schedulePeriodic(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_RETRY.equals(intent.getAction())) {
            // "Retry now": the job loads news and weather
            if (!NewsFeed.feeds(context).isEmpty()) {
                NewsFeed.prefs(context).edit()
                        .putString(NewsFeed.KEY_STATE, NewsFeed.STATE_LOADING).apply();
            }
            final SharedPreferences.Editor e = context
                    .getSharedPreferences(WeatherWidgetProvider.PREFS, 0).edit();
            for (int id : widgetIds(context)) e.remove(WeatherWidgetProvider.key(id, "wstate"));
            e.apply();
            updateAll(context);
            NewsFeedJob.scheduleNow(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        final SharedPreferences prefs = context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        final SharedPreferences.Editor e = prefs.edit();
        for (int id : ids) {
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith(id + "_")) e.remove(k);
            }
        }
        e.apply();
    }

    // ------------------------------------------------------------------ called by others

    /** NewsFeedJob and NewsSettings: the stories or feeds changed, all widgets show the current ones. */
    static void updateAll(Context context) {
        final Context app = context.getApplicationContext();
        final AppWidgetManager manager = AppWidgetManager.getInstance(app);
        for (int id : widgetIds(app)) {
            com.android.launcher2.LauncherOnly.update(app, manager, id, build(app, id));
        }
    }

    /**
     * WeatherWidgetProvider.applySettings(): the weather settings (key, location, app) are the
     * same for all weather, so these widgets show the new data (reload) or are drawn again.
     */
    static void weatherSettingsChanged(Context context, boolean reload) {
        final int[] ids = widgetIds(context);
        if (ids.length == 0) return;
        NewsFeedJob.schedulePeriodic(context); // the weather interval may be the shorter one
        if (reload) {
            final SharedPreferences.Editor e = context
                    .getSharedPreferences(WeatherWidgetProvider.PREFS, 0).edit();
            for (int id : ids) {
                e.remove(WeatherWidgetProvider.key(id, "temp"))
                        .remove(WeatherWidgetProvider.key(id, "updated"))
                        .remove(WeatherWidgetProvider.key(id, "wstate"));
            }
            e.apply();
            refreshWeather(context, ids, null);
        } else {
            updateAll(context);
        }
    }

    // ------------------------------------------------------------------ for NewsFeedJob

    private static long sLastDueCheck;

    /**
     * Called when the launcher comes to the front: if the news or the weather are older than
     * their interval (the periodic job was late: Doze, battery saver, no network), the job is
     * started now instead of at its next slot. Only preferences are read here; the job itself
     * decides again and starts nothing if all is fresh. At most once a minute.
     */
    public static void refreshIfDue(Context context) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (sLastDueCheck != 0 && now - sLastDueCheck < 60000L) return;
        sLastDueCheck = now;
        final Context app = context.getApplicationContext();
        if (widgetIds(app).length == 0) return;
        if ((!NewsFeed.feeds(app).isEmpty() && !NewsFeed.isFresh(app))
                || weatherIds(app, false).length > 0) {
            NewsFeedJob.schedule(app);
        }
    }

    /** Weather refresh interval of the weather settings, in minutes. */
    private static int weatherMinutes(SharedPreferences p) {
        return p.getInt("cfg_interval", WeatherWidgetProvider.DEFAULT_INTERVAL);
    }

    /**
     * How often NewsFeedJob has to run: the news interval, or the weather interval if that is
     * shorter. One job for both, so the radio is woken once and not twice.
     */
    static int periodMinutes(Context context) {
        final SharedPreferences p = context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        WeatherWidgetProvider.migrate(p);
        final boolean weather = widgetIds(context).length > 0 && p.contains("cfg_apikey");
        final int news = NewsFeed.intervalMinutes(context);
        if (!weather) return news;
        return NewsFeed.feeds(context).isEmpty() ? weatherMinutes(p)
                : Math.min(news, weatherMinutes(p));
    }

    /** The widgets whose weather has to be loaded: set up and due (or all set up ones if forced). */
    static int[] weatherIds(Context context, boolean force) {
        final SharedPreferences p = context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        WeatherWidgetProvider.migrate(p);
        final long now = System.currentTimeMillis();
        final long interval = weatherMinutes(p) * 60000L;
        final java.util.ArrayList<Integer> due = new java.util.ArrayList<Integer>();
        for (int id : widgetIds(context)) {
            if (!p.contains(WeatherWidgetProvider.key(id, "apikey"))) continue; // not set up
            final long updated = p.getLong(WeatherWidgetProvider.key(id, "updated"), 0);
            // tolerance: the job's timing isn't exact (NewsFeed.toleranceMs())
            if (force || updated > now || now - updated
                    >= interval - NewsFeed.toleranceMs(weatherMinutes(p))) {
                due.add(id);
            }
        }
        final int[] ids = new int[due.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = due.get(i);
        return ids;
    }

    /** Marks the widgets that have no weather yet as loading; true if there was one. */
    static boolean markWeatherLoading(Context context, int[] ids) {
        final SharedPreferences p = context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        final SharedPreferences.Editor e = p.edit();
        boolean any = false;
        for (int id : ids) {
            if (p.contains(WeatherWidgetProvider.key(id, "temp"))) continue;
            e.putString(WeatherWidgetProvider.key(id, "wstate"), STATE_LOADING);
            any = true;
        }
        e.apply();
        return any;
    }

    /** Loads the weather (blocking, in the job); false if one failed. Draws nothing. */
    static boolean loadWeather(Context context, int[] ids) {
        boolean ok = true;
        for (int id : ids) {
            String state;
            try {
                WeatherWidgetProvider.load(context, id);
                state = STATE_OK;
            } catch (Exception e) {
                android.util.Log.w("NewsWidget", "weather failed: " + e);
                state = STATE_ERROR;
                ok = false;
            }
            context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0).edit()
                    .putString(WeatherWidgetProvider.key(id, "wstate"), state).apply();
        }
        return ok;
    }

    /**
     * Everything the widgets show (stories, temperature, icon, states): every redraw restarts
     * the flipper at the first story and costs the launcher a layout pass, so the job only
     * draws when this changed.
     */
    static String signature(Context context) {
        final SharedPreferences p = context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        final StringBuilder sb = new StringBuilder(
                NewsFeed.prefs(context).getString(NewsFeed.KEY_STATE, NewsFeed.STATE_OK))
                .append('\n');
        for (NewsFeed.Story s : NewsFeed.loadCache(context)) {
            sb.append(s.title).append('|').append(s.source).append('|').append(s.link).append('\n');
        }
        for (int id : widgetIds(context)) {
            sb.append(id).append(':')
                    .append(p.getInt(WeatherWidgetProvider.key(id, "temp"), -999)).append(':')
                    .append(p.getString(WeatherWidgetProvider.key(id, "icon"), "")).append(':')
                    .append(p.getString(WeatherWidgetProvider.key(id, "wstate"), STATE_OK))
                    .append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ weather (after a change of the settings)

    /** Loads the weather for the widgets off the main thread and draws them. */
    private static void refreshWeather(final Context context, final int[] ids,
            final PendingResult result) {
        final Context app = context.getApplicationContext();
        WeatherWidgetProvider.migrate(app.getSharedPreferences(WeatherWidgetProvider.PREFS, 0));
        final AppWidgetManager manager = AppWidgetManager.getInstance(app);
        new Thread(() -> {
            try {
                for (int id : ids) {
                    final SharedPreferences prefs = app
                            .getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
                    if (!prefs.contains(WeatherWidgetProvider.key(id, "apikey"))) continue;
                    // loading state while there is no data yet (renderViewState())
                    if (!prefs.contains(WeatherWidgetProvider.key(id, "temp"))) {
                        prefs.edit().putString(WeatherWidgetProvider.key(id, "wstate"),
                                STATE_LOADING).apply();
                        com.android.launcher2.LauncherOnly.update(app, manager, id,
                                build(app, id));
                    }
                    String state;
                    try {
                        WeatherWidgetProvider.load(app, id);
                        state = STATE_OK;
                    } catch (Exception e) {
                        android.util.Log.w("NewsWidget", "weather failed: " + e);
                        state = STATE_ERROR;
                    }
                    prefs.edit().putString(WeatherWidgetProvider.key(id, "wstate"), state).apply();
                    com.android.launcher2.LauncherOnly.update(app, manager, id, build(app, id));
                }
            } finally {
                if (result != null) result.finish();
            }
        }).start();
    }

    // ------------------------------------------------------------------ rendering

    /** MiniWidgetView.render(): view state, weather and news. */
    static RemoteViews build(Context context, int id) {
        final SharedPreferences wp = context.getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        final SharedPreferences np = NewsFeed.prefs(context);
        final List<NewsFeed.Story> stories = NewsFeed.loadCache(context);
        final boolean weatherSet = wp.contains(WeatherWidgetProvider.key(id, "apikey"));
        final boolean newsSet = !NewsFeed.feeds(context).isEmpty();
        final boolean hasWeather = wp.contains(WeatherWidgetProvider.key(id, "temp"));
        final boolean hasNews = !stories.isEmpty();
        final String weatherState = wp.getString(WeatherWidgetProvider.key(id, "wstate"), STATE_OK);
        final String newsState = np.getString(NewsFeed.KEY_STATE, NewsFeed.STATE_OK);
        final boolean loading = (weatherSet && STATE_LOADING.equals(weatherState))
                || (newsSet && NewsFeed.STATE_LOADING.equals(newsState));
        final boolean failed = (weatherSet && STATE_ERROR.equals(weatherState))
                || (newsSet && NewsFeed.STATE_ERROR.equals(newsState));

        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.genie_news_widget);

        // renderViewState(): the spinner or "retry" while there is nothing to show yet
        int section = R.id.content;
        if (!hasWeather && !hasNews && (weatherSet || newsSet)) {
            if (loading) {
                section = R.id.loading;
            } else if (failed) {
                section = R.id.retry;
            }
        }
        v.setViewVisibility(R.id.loading, section == R.id.loading ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.retry, section == R.id.retry ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.content, section == R.id.content ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.retrying, context.getString(R.string.genie_retrying));

        // renderWeather(): icon ("m" size, as the original does next to the news) and temperature
        v.setViewVisibility(R.id.weather, View.VISIBLE);
        if (hasWeather) {
            v.setViewVisibility(R.id.weather_icon, View.VISIBLE);
            v.setViewVisibility(R.id.weather_temp, View.VISIBLE);
            v.setViewVisibility(R.id.weather_unavailable, View.GONE);
            v.setTextViewText(R.id.weather_temp, Html.fromHtml(
                    wp.getInt(WeatherWidgetProvider.key(id, "temp"), 0) + "&deg;"));
            final Integer icon = ICONS.get(wp.getString(WeatherWidgetProvider.key(id, "icon"),
                    "unknown"));
            if (icon != null) {
                v.setImageViewResource(R.id.weather_icon, icon);
            } else {
                v.setViewVisibility(R.id.weather_icon, View.INVISIBLE);
            }
        } else {
            v.setViewVisibility(R.id.weather_icon, View.GONE);
            v.setViewVisibility(R.id.weather_temp, View.GONE);
            v.setViewVisibility(R.id.weather_unavailable, View.VISIBLE);
        }

        // renderNews(): a story per flipper page, or a message. A message is not put into the
        // flipper: a ViewFlipper plays its transition even when it has a single child.
        v.removeAllViews(R.id.news_flip);
        v.setViewVisibility(R.id.news_flip, hasNews ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.news_empty_box, hasNews ? View.GONE : View.VISIBLE);
        if (hasNews) {
            for (int i = 0; i < stories.size(); i++) {
                v.addView(R.id.news_flip, story(context, stories.get(i), i));
            }
        } else if (!newsSet) {
            message(context, v, R.string.genie_news_hint, settings(context));
        } else if (NewsFeed.STATE_LOADING.equals(newsState)) {
            message(context, v, R.string.genie_loading, null);
        } else if (NewsFeed.STATE_ERROR.equals(newsState)) {
            message(context, v, R.string.genie_news_try_again, retry(context));
        } else {
            message(context, v, R.string.genie_news_unavailable, retry(context));
        }

        // wireupClickIntents(): the weather opens the chosen weather app (or the settings)
        Intent open = null;
        final String app = wp.getString(WeatherWidgetProvider.key(id, "app"), null);
        if (app != null) open = context.getPackageManager().getLaunchIntentForPackage(app);
        if (open == null) open = com.android.launcher2.Launcher.Settings.widgetsIntent(context);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent weather = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.weather, weather);
        v.setOnClickPendingIntent(R.id.weather_unavailable, weather);
        v.setOnClickPendingIntent(R.id.loading, weather);
        v.setOnClickPendingIntent(R.id.retrynow, retry(context));
        return v;
    }

    /** buildStory(): headline and source; a tap opens the story's link. */
    private static RemoteViews story(Context context, NewsFeed.Story story, int index) {
        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.genie_news_story);
        v.setTextViewText(R.id.news_headline, story.title);
        if (story.source != null) {
            v.setViewVisibility(R.id.news_source, View.VISIBLE);
            v.setTextViewText(R.id.news_source, story.source);
        } else {
            v.setViewVisibility(R.id.news_source, View.GONE);
        }
        if (story.link != null) {
            final Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(story.link))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.news, PendingIntent.getActivity(context, 1000 + index,
                    view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return v;
    }

    /** buildStoryEmpty(): a message instead of the stories (outside the flipper, no transition). */
    private static void message(Context context, RemoteViews v, int text, PendingIntent click) {
        v.setTextViewText(R.id.news_message, context.getString(text));
        if (click != null) {
            v.setOnClickPendingIntent(R.id.news_empty, click);
        } else {
            v.setOnClickPendingIntent(R.id.news_empty, null);
        }
    }

    private static PendingIntent settings(Context context) {
        return PendingIntent.getActivity(context, 2,
                com.android.launcher2.Launcher.Settings.widgetsIntent(context),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent retry(Context context) {
        // explicit (the receiver is not exported, and implicit broadcasts are not allowed here)
        return PendingIntent.getBroadcast(context, 1,
                new Intent(ACTION_RETRY).setClass(context, NewsWidgetProvider.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean hasWidgets(Context context) {
        return widgetIds(context).length > 0;
    }

    private static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, NewsWidgetProvider.class));
    }

    /** The widget's (Google Weather) icons, "m" size: the same names as WeatherWidgetProvider.ICONS. */
    private static final HashMap<String, Integer> ICONS = new HashMap<String, Integer>();
    static {
        ICONS.put("chance_of_rain", R.drawable.genie_ic_weather_chance_of_rain_m);
        ICONS.put("chance_of_rain_night", R.drawable.genie_ic_weather_chance_of_rain_night_m);
        ICONS.put("chance_snow", R.drawable.genie_ic_weather_chance_snow_m);
        ICONS.put("chance_storm", R.drawable.genie_ic_weather_chance_storm_m);
        ICONS.put("clear_day", R.drawable.genie_ic_weather_clear_day_m);
        ICONS.put("clear", R.drawable.genie_ic_weather_clear_m);
        ICONS.put("clear_night", R.drawable.genie_ic_weather_clear_night_m);
        ICONS.put("cloudy", R.drawable.genie_ic_weather_cloudy_m);
        ICONS.put("flurries", R.drawable.genie_ic_weather_flurries_m);
        ICONS.put("fog", R.drawable.genie_ic_weather_fog_m);
        ICONS.put("heavy_rain", R.drawable.genie_ic_weather_heavy_rain_m);
        ICONS.put("icy_sleet", R.drawable.genie_ic_weather_icy_sleet_m);
        ICONS.put("mist", R.drawable.genie_ic_weather_mist_m);
        ICONS.put("partly_cloudy", R.drawable.genie_ic_weather_partly_cloudy_m);
        ICONS.put("rain_day", R.drawable.genie_ic_weather_rain_day_m);
        ICONS.put("rain", R.drawable.genie_ic_weather_rain_m);
        ICONS.put("rain_night", R.drawable.genie_ic_weather_rain_night_m);
        ICONS.put("snow", R.drawable.genie_ic_weather_snow_m);
        ICONS.put("snow_rain", R.drawable.genie_ic_weather_snow_rain_m);
        ICONS.put("sunny", R.drawable.genie_ic_weather_sunny_m);
        ICONS.put("thunderstorm", R.drawable.genie_ic_weather_thunderstorm_m);
        ICONS.put("unknown", R.drawable.genie_ic_weather_unknown_m);
        ICONS.put("windy", R.drawable.genie_ic_weather_windy_m);
    }
}
