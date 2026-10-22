/*
 * Port: WidgetProvider of YouTube 1.6.19 (the 4x1 widget), same structure as the original:
 * setupWidget (logo, search, camera, the video flipper), populateVideoFlipper (a new video
 * every 8 seconds, or a message with "touch to try again"), getVideoView (source, thumbnail,
 * title; a tap plays the video), REFRESH_TEASER_ACTION and refreshFlipper.
 *
 * Differences, because the YouTube app and Google's feeds are gone:
 *  - the videos are the newest ones of your subscriptions (Feed / TeaserModel, loaded by FeedJob)
 *  - logo, search and video open youtube.com (the YouTube app, if it is installed)
 *  - the camera button is hidden, as the original does when no uploader is found
 *  - without channels the widget asks for them (tap opens the launcher settings)
 *  - from a width of 340dp on (5 columns, set by resizing) the thumbnail is 16:9 instead of 4:3
 *  - refreshFlipper rebuilds the whole widget, as updateAppWidget replaces the RemoteViews
 *    on current Android (the original relied on the old merge behaviour for its click handlers)
 */
package com.google.android.youtube.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.RemoteViews;

import com.android.launcher.R;

public class WidgetProvider extends AppWidgetProvider {
    public static final String REFRESH_TEASER_ACTION = "com.google.android.youtube.REFRESH_TEASER_ACTION";

    /** From this width (dp, as the launcher reports it) the thumbnail is 16:9, below it the original's 4:3. */
    private static final int WIDE_FROM_DP = 340;

    /** The layout (4:3 or 16:9) each widget shows now: a resize inside the same one needs no rebuild. */
    private static final SparseBooleanArray sWide = new SparseBooleanArray();

    private static final String URL_HOME = "https://www.youtube.com/";
    private static final String URL_SEARCH = "https://www.youtube.com/results?search_query=";
    private static final String URL_WATCH = "https://www.youtube.com/watch?v=";

    @Override
    public void onEnabled(Context context) {
        FeedJob.schedule(context);
        FeedJob.schedulePeriodic(context);
    }

    @Override
    public void onDisabled(Context context) {
        FeedJob.cancel(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] ids) {
        // fetchTeasers(): the cached videos at once, new ones as soon as there is a network
        for (int id : ids) {
            final boolean wide = wide(appWidgetManager.getAppWidgetOptions(id));
            sWide.put(id, wide);
            com.android.launcher2.LauncherOnly.update(context, appWidgetManager, id,
                    setupWidget(context, wide));
        }
        FeedJob.schedule(context);
        FeedJob.schedulePeriodic(context);
    }

    /** The widget was resized (any launcher reports this through the standard widget options). */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
            int id, Bundle newOptions) {
        final boolean wide = wide(newOptions);
        if (sWide.indexOfKey(id) >= 0 && sWide.get(id) == wide) return; // same layout: nothing to do
        sWide.put(id, wide);
        com.android.launcher2.LauncherOnly.update(context, appWidgetManager, id,
                    setupWidget(context, wide));
    }

    private static boolean wide(Bundle options) {
        return options != null
                && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) >= WIDE_FROM_DP;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (widgetsAreActive(context) && REFRESH_TEASER_ACTION.equals(intent.getAction())) {
            Feed.prefs(context).edit().putString(Feed.KEY_STATE, Feed.STATE_LOADING).apply();
            refreshFlipper(context);
            FeedJob.scheduleNow(context);
            return;
        }
        super.onReceive(context, intent);
    }

    private static RemoteViews setupWidget(Context context, boolean wide) {
        final RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.yt_widget);
        remoteViews.setOnClickPendingIntent(R.id.yt_widget_logo, view(context, URL_HOME));
        remoteViews.setOnClickPendingIntent(R.id.yt_search_icon, view(context, URL_SEARCH));
        // "Uploader not found: Turning off widget camera button."
        remoteViews.setViewVisibility(R.id.yt_camera_icon, View.GONE);
        remoteViews.setViewVisibility(R.id.yt_camera_divider, View.GONE);
        populateVideoFlipper(context, remoteViews, new TeaserModel(context), wide);
        return remoteViews;
    }

    private static void populateVideoFlipper(Context context, RemoteViews remoteViews,
            TeaserModel teaserModel, boolean wide) {
        remoteViews.removeAllViews(R.id.yt_video_flipper);
        if (teaserModel.getNumberOfVideos() == 0) {
            remoteViews.setViewVisibility(R.id.yt_video_flipper, View.INVISIBLE);
            remoteViews.setViewVisibility(R.id.yt_video_flipper_alt, View.VISIBLE);
            if (!teaserModel.hasChannels()) {
                remoteViews.setTextViewText(R.id.yt_widget_message,
                        context.getString(R.string.yt_no_channels_hint));
                remoteViews.setOnClickPendingIntent(R.id.yt_video_flipper_alt, configure(context));
            } else if (teaserModel.isFetching()) {
                remoteViews.setTextViewText(R.id.yt_widget_message,
                        context.getString(R.string.yt_loading));
            } else {
                remoteViews.setTextViewText(R.id.yt_widget_message,
                        context.getString(R.string.yt_widget_try_again));
                remoteViews.setOnClickPendingIntent(R.id.yt_video_flipper_alt, refresh(context));
            }
            return;
        }
        remoteViews.setViewVisibility(R.id.yt_video_flipper, View.VISIBLE);
        remoteViews.setViewVisibility(R.id.yt_video_flipper_alt, View.INVISIBLE);
        for (int i = 0; i < teaserModel.getNumberOfVideos(); i++) {
            remoteViews.addView(R.id.yt_video_flipper,
                    getVideoView(context, teaserModel.getVideo(i), teaserModel.getThumbnail(i), wide));
        }
    }

    private static RemoteViews getVideoView(Context context, Feed.Video video, byte[] thumbnail,
            boolean wide) {
        final RemoteViews remoteViews = new RemoteViews(context.getPackageName(),
                wide ? R.layout.yt_widget_teaser_wide : R.layout.yt_widget_teaser);
        remoteViews.setTextViewText(R.id.yt_title, video.title);
        // the JPEG's bytes as an Icon: the launcher decodes it, nothing big is sent to it
        remoteViews.setImageViewIcon(R.id.yt_thumbnail,
                Icon.createWithData(thumbnail, 0, thumbnail.length));
        remoteViews.setOnClickPendingIntent(R.id.yt_teaser_video, view(context, URL_WATCH + video.id));
        // the logo in the teaser (the one in yt_widget.xml is only shown with the message)
        remoteViews.setOnClickPendingIntent(R.id.yt_widget_logo, view(context, URL_HOME));
        remoteViews.setTextViewText(R.id.yt_source,
                context.getString(R.string.yt_widget_subscription_update));
        return remoteViews;
    }

    /** refreshFlipper(): all widgets of this provider show the current videos. */
    static void refreshFlipper(Context context) {
        final AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : widgetIds(context)) {
            final boolean wide = wide(manager.getAppWidgetOptions(id));
            sWide.put(id, wide);
            com.android.launcher2.LauncherOnly.update(context, manager, id,
                    setupWidget(context, wide));
        }
    }

    /** Called by FeedJob and YouTubeSettings when the videos or channels changed. */
    static void updateAll(Context context) {
        refreshFlipper(context);
    }

    private static boolean widgetsAreActive(Context context) {
        return widgetIds(context).length > 0;
    }

    private static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, WidgetProvider.class));
    }

    private static PendingIntent view(Context context, String url) {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent refresh(Context context) {
        // explicit (the receiver is not exported, and implicit broadcasts are not allowed here)
        return PendingIntent.getBroadcast(context, 1,
                new Intent(REFRESH_TEASER_ACTION).setClass(context, WidgetProvider.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent configure(Context context) {
        return PendingIntent.getActivity(context, 2,
                com.android.launcher2.Launcher.Settings.widgetsIntent(context),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
