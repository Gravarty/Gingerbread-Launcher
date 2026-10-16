/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher2;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;

public class LauncherApplication extends Application {
    public LauncherModel mModel;
    public IconCache mIconCache;

    @Override
    public void onCreate() {
        super.onCreate();

        Launcher.loadTweaks(this); // Tweaks

        mIconCache = new IconCache(this);
        mModel = new LauncherModel(this, mIconCache);

        // Register intent receivers
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(mModel, filter, RECEIVER_EXPORTED);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        registerReceiver(mModel, filter, RECEIVER_EXPORTED);

        // Register for changes to the favorites
        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(LauncherSettings.Favorites.CONTENT_URI, true,
                mFavoritesObserver);

        updateOwnWidgets();

        // Tweaks: the search widget shows Google's icon while the Google app is installed
        filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(
                com.android.quicksearchbox.SearchWidgetProvider.GOOGLE_PACKAGE, 0);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                com.android.quicksearchbox.SearchWidgetProvider.updateSearchWidgets(context);
            }
        }, filter, RECEIVER_EXPORTED);

        // Port: the "Power Control" widget no longer gets these as a manifest receiver
        final android.content.BroadcastReceiver powerWidget =
                new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                com.android.settings.widget.SettingsAppWidgetProvider.onStateChanged(
                        context, intent);
            }
        };
        filter = new IntentFilter(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION);
        filter.addAction(android.location.LocationManager.MODE_CHANGED_ACTION);
        registerReceiver(powerWidget, filter, RECEIVER_EXPORTED);
        final Handler handler = new Handler();
        ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
                which -> handler.post(() -> com.android.settings.widget
                        .SettingsAppWidgetProvider.onStateChanged(this, null)));
        final ContentObserver brightness = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                com.android.settings.widget.SettingsAppWidgetProvider.onStateChanged(
                        LauncherApplication.this, null);
            }
        };
        resolver.registerContentObserver(android.provider.Settings.System.getUriFor(
                android.provider.Settings.System.SCREEN_BRIGHTNESS), false, brightness);
        resolver.registerContentObserver(android.provider.Settings.System.getUriFor(
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightness);
    }

    /**
     * Port: an app update force-stops the package and the system drops the views of its
     * widgets; the hosts show the initial layouts (no click actions, "Loading..."). The
     * update broadcast that should refill them doesn't reliably arrive, so the widgets
     * bundled with the launcher (search, clock, power control, weather) are updated
     * whenever its process starts.
     */
    private void updateOwnWidgets() {
        final android.appwidget.AppWidgetManager manager =
                android.appwidget.AppWidgetManager.getInstance(this);
        final java.util.List<android.appwidget.AppWidgetProviderInfo> providers =
                manager.getInstalledProviders();
        for (android.appwidget.AppWidgetProviderInfo info : providers) {
            if (!getPackageName().equals(info.provider.getPackageName())) continue;
            final int[] ids = manager.getAppWidgetIds(info.provider);
            if (ids.length == 0) continue;
            sendBroadcast(new Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(info.provider)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        }
    }

    /**
     * There's no guarantee that this function is ever called.
     */
    @Override
    public void onTerminate() {
        super.onTerminate();

        unregisterReceiver(mModel);

        ContentResolver resolver = getContentResolver();
        resolver.unregisterContentObserver(mFavoritesObserver);
    }

    /**
     * Receives notifications whenever the user favorites have changed.
     */
    private final ContentObserver mFavoritesObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            mModel.startLoader(LauncherApplication.this, false);
        }
    };

    LauncherModel setLauncher(Launcher launcher) {
        mModel.initialize(launcher);
        return mModel;
    }

    IconCache getIconCache() {
        return mIconCache;
    }

    LauncherModel getModel() {
        return mModel;
    }
}
