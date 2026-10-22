/*
 * Tweaks: the bundled widgets work in this launcher only. The launcher marks every widget it
 * shows as its own (widget option); in any other launcher the widget shows a notice instead.
 * (Android 12+ launchers don't even list them, see widgetFeatures="hide_from_picker".)
 */
package com.android.launcher2;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

import com.android.launcher.R;

public final class LauncherOnly {
    private static final String KEY = "gb_launcher_owned";
    private static final java.util.HashSet<Integer> sOwned = new java.util.HashSet<Integer>();

    private LauncherOnly() {}

    /** Called by the launcher's host for every widget of this app it shows. */
    static void claim(Context context, int id, ComponentName provider) {
        if (sOwned.contains(id)) return;
        final AppWidgetManager m = AppWidgetManager.getInstance(context);
        final Bundle o = m.getAppWidgetOptions(id);
        sOwned.add(id);
        if (o != null && o.getBoolean(KEY, false)) return;
        final Bundle b = new Bundle();
        b.putBoolean(KEY, true);
        m.updateAppWidgetOptions(id, b);
        // the provider may have drawn the notice already: draw the widget again
        context.sendBroadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { id }));
    }

    public static boolean owned(AppWidgetManager m, int id) {
        final Bundle o = m.getAppWidgetOptions(id);
        return o != null && o.getBoolean(KEY, false);
    }

    /** updateAppWidget(): the widget's views in this launcher, the notice anywhere else. */
    public static void update(Context context, AppWidgetManager m, int id, RemoteViews views) {
        m.updateAppWidget(id, owned(m, id) ? views
                : new RemoteViews(context.getPackageName(), R.layout.launcher_only_notice));
    }
}
