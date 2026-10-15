/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.settings.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.provider.Settings;
import android.util.Log;
import android.widget.RemoteViews;
import com.android.launcher.R;

/**
 * Provides control of power-related settings from a widget.
 *
 * Port notes (AOSP Settings android-2.3.7_r1, bundled with the launcher):
 *  - Apps can't switch Wi-Fi (since Android 10), Bluetooth (since 13) or GPS anymore. Their
 *    buttons open the system's Wi-Fi panel, the Bluetooth settings and the location
 *    settings; the indicators still show the real state.
 *  - Sync works as before (background data no longer exists).
 *  - Brightness works as before, but needs "Modify system settings"; without it the button
 *    opens that permission screen.
 *  - Manifest receivers get no implicit state broadcasts anymore; LauncherApplication
 *    forwards them while the launcher runs (see {@link #onStateChanged}).
 */
public class SettingsAppWidgetProvider extends AppWidgetProvider {
    static final String TAG = "SettingsAppWidgetProvider";

    private static final int BUTTON_WIFI = 0;
    private static final int BUTTON_BRIGHTNESS = 1;
    private static final int BUTTON_SYNC = 2;
    private static final int BUTTON_GPS = 3;
    private static final int BUTTON_BLUETOOTH = 4;

    // This widget keeps track of two sets of states:
    // "3-state": STATE_DISABLED, STATE_ENABLED, STATE_INTERMEDIATE
    // "5-state": STATE_DISABLED, STATE_ENABLED, STATE_TURNING_ON, STATE_TURNING_OFF, STATE_UNKNOWN
    private static final int STATE_DISABLED = 0;
    private static final int STATE_ENABLED = 1;
    private static final int STATE_TURNING_ON = 2;
    private static final int STATE_TURNING_OFF = 3;
    private static final int STATE_UNKNOWN = 4;
    private static final int STATE_INTERMEDIATE = 5;

    // Position in the widget bar, to enable different graphics for left, center and right buttons
    private static final int POS_LEFT = 0;
    private static final int POS_CENTER = 1;
    private static final int POS_RIGHT = 2;

    private static final int[] IND_DRAWABLE_OFF = {
        R.drawable.appwidget_settings_ind_off_l,
        R.drawable.appwidget_settings_ind_off_c,
        R.drawable.appwidget_settings_ind_off_r
    };

    private static final int[] IND_DRAWABLE_MID = {
        R.drawable.appwidget_settings_ind_mid_l,
        R.drawable.appwidget_settings_ind_mid_c,
        R.drawable.appwidget_settings_ind_mid_r
    };

    private static final int[] IND_DRAWABLE_ON = {
        R.drawable.appwidget_settings_ind_on_l,
        R.drawable.appwidget_settings_ind_on_c,
        R.drawable.appwidget_settings_ind_on_r
    };

    /**
     * Minimum and maximum brightnesses.  Don't go to 0 since that makes the display unusable
     */
    // Port: android.os.Power.BRIGHTNESS_DIM (20) + 10, BRIGHTNESS_ON (255)
    private static final int MINIMUM_BACKLIGHT = 20 + 10;
    private static final int MAXIMUM_BACKLIGHT = 255;
    private static final int DEFAULT_BACKLIGHT = (int) (255 * 0.4f);

    private static final StateTracker sWifiState = new WifiStateTracker();
    private static final StateTracker sBluetoothState = new BluetoothStateTracker();
    private static final StateTracker sGpsState = new GpsStateTracker();
    private static final StateTracker sSyncState = new SyncStateTracker();

    /**
     * The state machine for a setting's toggling, tracking reality
     * versus the user's intent.
     *
     * This is necessary because reality moves relatively slowly
     * (turning on &amp; off radio drivers), compared to user's
     * expectations.
     */
    private abstract static class StateTracker {
        // Is the state in the process of changing?
        private boolean mInTransition = false;
        private Boolean mActualState = null;  // initially not set
        private Boolean mIntendedState = null;  // initially not set

        // Did a toggle request arrive while a state update was
        // already in-flight?  If so, the mIntendedState needs to be
        // requested when the other one is done, unless we happened to
        // arrive at that state already.
        private boolean mDeferredStateChangeRequestNeeded = false;

        /**
         * User pressed a button to change the state.  Something
         * should immediately appear to the user afterwards, even if
         * we effectively do nothing.  Their press must be heard.
         */
        public final void toggleState(Context context) {
            int currentState = getTriState(context);
            boolean newState = false;
            switch (currentState) {
                case STATE_ENABLED:
                    newState = false;
                    break;
                case STATE_DISABLED:
                    newState = true;
                    break;
                case STATE_INTERMEDIATE:
                    if (mIntendedState != null) {
                        newState = !mIntendedState;
                    }
                    break;
            }
            mIntendedState = newState;
            if (mInTransition) {
                // We don't send off a transition request if we're
                // already transitioning.  Makes our state tracking
                // easier, and is probably nicer on lower levels.
                // (even though they should be able to take it...)
                mDeferredStateChangeRequestNeeded = true;
            } else {
                mInTransition = true;
                requestStateChange(context, newState);
            }
        }

        /**
         * Return the ID of the main large image button for the setting.
         */
        public abstract int getButtonId();

        /**
         * Returns the small indicator image ID underneath the setting.
         */
        public abstract int getIndicatorId();

        /**
         * Returns the resource ID of the image to show as a function of
         * the on-vs-off state.
         */
        public abstract int getButtonImageId(boolean on);

        /**
         * Returns the position in the button bar - either POS_LEFT, POS_RIGHT or POS_CENTER.
         */
        public int getPosition() { return POS_CENTER; }

        /**
         * Updates the remote views depending on the state (off, on,
         * turning off, turning on) of the setting.
         */
        public final void setImageViewResources(Context context, RemoteViews views) {
            int buttonId = getButtonId();
            int indicatorId = getIndicatorId();
            int pos = getPosition();
            switch (getTriState(context)) {
                case STATE_DISABLED:
                    views.setImageViewResource(buttonId, getButtonImageId(false));
                    views.setImageViewResource(
                        indicatorId, IND_DRAWABLE_OFF[pos]);
                    break;
                case STATE_ENABLED:
                    views.setImageViewResource(buttonId, getButtonImageId(true));
                    views.setImageViewResource(
                        indicatorId, IND_DRAWABLE_ON[pos]);
                    break;
                case STATE_INTERMEDIATE:
                    // In the transitional state, the bottom green bar
                    // shows the tri-state (on, off, transitioning), but
                    // the top dark-gray-or-bright-white logo shows the
                    // user's intent.  This is much easier to see in
                    // sunlight.
                    if (isTurningOn()) {
                        views.setImageViewResource(buttonId, getButtonImageId(true));
                        views.setImageViewResource(
                            indicatorId, IND_DRAWABLE_MID[pos]);
                    } else {
                        views.setImageViewResource(buttonId, getButtonImageId(false));
                        views.setImageViewResource(
                            indicatorId, IND_DRAWABLE_OFF[pos]);
                    }
                    break;
            }
        }

        /**
         * Update internal state from a broadcast state change.
         */
        public abstract void onActualStateChange(Context context, Intent intent);

        /**
         * Sets the value that we're now in.  To be called from onActualStateChange.
         *
         * @param newState one of STATE_DISABLED, STATE_ENABLED, STATE_TURNING_ON,
         *                 STATE_TURNING_OFF, STATE_UNKNOWN
         */
        protected final void setCurrentState(Context context, int newState) {
            final boolean wasInTransition = mInTransition;
            switch (newState) {
                case STATE_DISABLED:
                    mInTransition = false;
                    mActualState = false;
                    break;
                case STATE_ENABLED:
                    mInTransition = false;
                    mActualState = true;
                    break;
                case STATE_TURNING_ON:
                    mInTransition = true;
                    mActualState = false;
                    break;
                case STATE_TURNING_OFF:
                    mInTransition = true;
                    mActualState = true;
                    break;
            }

            if (wasInTransition && !mInTransition) {
                if (mDeferredStateChangeRequestNeeded) {
                    Log.v(TAG, "processing deferred state change");
                    if (mActualState != null && mIntendedState != null &&
                        mIntendedState.equals(mActualState)) {
                        Log.v(TAG, "... but intended state matches, so no changes.");
                    } else if (mIntendedState != null) {
                        mInTransition = true;
                        requestStateChange(context, mIntendedState);
                    }
                    mDeferredStateChangeRequestNeeded = false;
                }
            }
        }


        /**
         * If we're in a transition mode, this returns true if we're
         * transitioning towards being enabled.
         */
        public final boolean isTurningOn() {
            return mIntendedState != null && mIntendedState;
        }

        /**
         * Returns simplified 3-state value from underlying 5-state.
         *
         * @param context
         * @return STATE_ENABLED, STATE_DISABLED, or STATE_INTERMEDIATE
         */
        public final int getTriState(Context context) {
            if (mInTransition) {
                // If we know we just got a toggle request recently
                // (which set mInTransition), don't even ask the
                // underlying interface for its state.  We know we're
                // changing.  This avoids blocking the UI thread
                // during UI refresh post-toggle if the underlying
                // service state accessor has coarse locking on its
                // state (to be fixed separately).
                return STATE_INTERMEDIATE;
            }
            switch (getActualState(context)) {
                case STATE_DISABLED:
                    return STATE_DISABLED;
                case STATE_ENABLED:
                    return STATE_ENABLED;
                default:
                    return STATE_INTERMEDIATE;
            }
        }

        /**
         * Gets underlying actual state.
         *
         * @param context
         * @return STATE_ENABLED, STATE_DISABLED, STATE_ENABLING, STATE_DISABLING,
         *         or or STATE_UNKNOWN.
         */
        public abstract int getActualState(Context context);

        /**
         * Actually make the desired change to the underlying radio
         * API.
         */
        protected abstract void requestStateChange(Context context, boolean desiredState);
    }

    /**
     * Subclass of StateTracker to get/set Wifi state.
     */
    private static final class WifiStateTracker extends StateTracker {
        public int getButtonId() { return R.id.img_wifi; }
        public int getIndicatorId() { return R.id.ind_wifi; }
        public int getButtonImageId(boolean on) {
            return on ? R.drawable.ic_appwidget_settings_wifi_on
                    : R.drawable.ic_appwidget_settings_wifi_off;
        }

        @Override
        public int getPosition() { return POS_LEFT; }

        @Override
        public int getActualState(Context context) {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext().getSystemService(
                            Context.WIFI_SERVICE);
            if (wifiManager != null) {
                return wifiStateToFiveState(wifiManager.getWifiState());
            }
            return STATE_UNKNOWN;
        }

        @Override
        protected void requestStateChange(Context context, final boolean desiredState) {
            // Port: not possible for apps anymore, the button opens the Wi-Fi panel
        }

        @Override
        public void onActualStateChange(Context context, Intent intent) {
            if (!WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                return;
            }
            int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
            setCurrentState(context, wifiStateToFiveState(wifiState));
        }

        /**
         * Converts WifiManager's state values into our
         * Wifi/Bluetooth-common state values.
         */
        private static int wifiStateToFiveState(int wifiState) {
            switch (wifiState) {
                case WifiManager.WIFI_STATE_DISABLED:
                    return STATE_DISABLED;
                case WifiManager.WIFI_STATE_ENABLED:
                    return STATE_ENABLED;
                case WifiManager.WIFI_STATE_DISABLING:
                    return STATE_TURNING_OFF;
                case WifiManager.WIFI_STATE_ENABLING:
                    return STATE_TURNING_ON;
                default:
                    return STATE_UNKNOWN;
            }
        }
    }

    /**
     * Subclass of StateTracker to get/set Bluetooth state.
     */
    private static final class BluetoothStateTracker extends StateTracker {
        public int getButtonId() { return R.id.img_bluetooth; }
        public int getIndicatorId() { return R.id.ind_bluetooth; }
        public int getButtonImageId(boolean on) {
            return on ? R.drawable.ic_appwidget_settings_bluetooth_on
                    : R.drawable.ic_appwidget_settings_bluetooth_off;
        }

        @Override
        public int getActualState(Context context) {
            // Port: LocalBluetoothManager -> BluetoothAdapter
            final BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            if (adapter == null) {
                return STATE_UNKNOWN;  // On emulator?
            }
            return bluetoothStateToFiveState(adapter.getState());
        }

        @Override
        protected void requestStateChange(Context context, final boolean desiredState) {
            // Port: not possible for apps anymore, the button opens the Bluetooth settings
        }

        @Override
        public void onActualStateChange(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int bluetoothState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            setCurrentState(context, bluetoothStateToFiveState(bluetoothState));
        }

        /**
         * Converts BluetoothAdapter's state values into our
         * Wifi/Bluetooth-common state values.
         */
        private static int bluetoothStateToFiveState(int bluetoothState) {
            switch (bluetoothState) {
                case BluetoothAdapter.STATE_OFF:
                    return STATE_DISABLED;
                case BluetoothAdapter.STATE_ON:
                    return STATE_ENABLED;
                case BluetoothAdapter.STATE_TURNING_ON:
                    return STATE_TURNING_ON;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    return STATE_TURNING_OFF;
                default:
                    return STATE_UNKNOWN;
            }
        }
    }

    /**
     * Subclass of StateTracker for GPS state.
     */
    private static final class GpsStateTracker extends StateTracker {
        public int getButtonId() { return R.id.img_gps; }
        public int getIndicatorId() { return R.id.ind_gps; }
        public int getButtonImageId(boolean on) {
            return on ? R.drawable.ic_appwidget_settings_gps_on
                    : R.drawable.ic_appwidget_settings_gps_off;
        }

        @Override
        public int getActualState(Context context) {
            // Port: Settings.Secure.isLocationProviderEnabled(GPS) -> location on/off
            final LocationManager lm =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            boolean on = lm != null && lm.isLocationEnabled();
            return on ? STATE_ENABLED : STATE_DISABLED;
        }

        @Override
        public void onActualStateChange(Context context, Intent unused) {
            // Note: the broadcast location providers changed intent
            // doesn't include an extras bundles saying what the new value is.
            setCurrentState(context, getActualState(context));
        }

        @Override
        public void requestStateChange(final Context context, final boolean desiredState) {
            // Port: not possible for apps anymore, the button opens the location settings
        }
    }

    /**
     * Subclass of StateTracker for sync state.
     */
    private static final class SyncStateTracker extends StateTracker {
        public int getButtonId() { return R.id.img_sync; }
        public int getIndicatorId() { return R.id.ind_sync; }
        public int getButtonImageId(boolean on) {
            return on ? R.drawable.ic_appwidget_settings_sync_on
                    : R.drawable.ic_appwidget_settings_sync_off;
        }

        @Override
        public int getActualState(Context context) {
            // Port: background data doesn't exist anymore (always on)
            boolean on = ContentResolver.getMasterSyncAutomatically();
            return on ? STATE_ENABLED : STATE_DISABLED;
        }

        @Override
        public void onActualStateChange(Context context, Intent unused) {
            setCurrentState(context, getActualState(context));
        }

        @Override
        public void requestStateChange(final Context context, final boolean desiredState) {
            final boolean sync = ContentResolver.getMasterSyncAutomatically();

            new AsyncTask<Void, Void, Boolean>() {
                @Override
                protected Boolean doInBackground(Void... args) {
                    // Turning sync on.
                    if (desiredState) {
                        if (!sync) {
                            ContentResolver.setMasterSyncAutomatically(true);
                        }
                        return true;
                    }

                    // Turning sync off
                    if (sync) {
                        ContentResolver.setMasterSyncAutomatically(false);
                    }
                    return false;
                }

                @Override
                protected void onPostExecute(Boolean result) {
                    setCurrentState(
                        context,
                        result ? STATE_ENABLED : STATE_DISABLED);
                    updateWidget(context);
                }
            }.execute();
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager,
            int[] appWidgetIds) {
        // Update each requested appWidgetId
        RemoteViews view = buildUpdate(context, -1);

        for (int i = 0; i < appWidgetIds.length; i++) {
            appWidgetManager.updateAppWidget(appWidgetIds[i], view);
        }
    }

    // Port: onEnabled()/onDisabled() enabled/disabled this receiver; it also forwards
    // nothing else here, so it stays enabled.

    /**
     * Load image for given widget and build {@link RemoteViews} for it.
     */
    static RemoteViews buildUpdate(Context context, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.power_widget);
        // Port: Wi-Fi, GPS and Bluetooth open the system settings for them
        views.setOnClickPendingIntent(R.id.btn_wifi, getSettingsPendingIntent(context,
                BUTTON_WIFI, new Intent(Settings.Panel.ACTION_WIFI)));
        views.setOnClickPendingIntent(R.id.btn_brightness,
                getLaunchPendingIntent(context,
                        appWidgetId, BUTTON_BRIGHTNESS));
        views.setOnClickPendingIntent(R.id.btn_sync,
                getLaunchPendingIntent(context,
                        appWidgetId, BUTTON_SYNC));
        views.setOnClickPendingIntent(R.id.btn_gps, getSettingsPendingIntent(context,
                BUTTON_GPS, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        views.setOnClickPendingIntent(R.id.btn_bluetooth, getSettingsPendingIntent(context,
                BUTTON_BLUETOOTH, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));

        updateButtons(views, context);
        return views;
    }

    /**
     * Updates the widget when something changes, or when a button is pushed.
     *
     * @param context
     */
    public static void updateWidget(Context context) {
        RemoteViews views = buildUpdate(context, -1);
        // Update specific list of appWidgetIds if given, otherwise default to all
        final AppWidgetManager gm = AppWidgetManager.getInstance(context);
        gm.updateAppWidget(new ComponentName(context, SettingsAppWidgetProvider.class), views);
    }

    /**
     * Port: the state broadcasts (and sync/brightness changes), forwarded by
     * LauncherApplication. Only does something if the widget is placed.
     */
    public static void onStateChanged(Context context, Intent intent) {
        final AppWidgetManager gm = AppWidgetManager.getInstance(context);
        if (gm.getAppWidgetIds(new ComponentName(context, SettingsAppWidgetProvider.class))
                .length == 0) {
            return;
        }
        final String action = intent != null ? intent.getAction() : null;
        if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
            sWifiState.onActualStateChange(context, intent);
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            sBluetoothState.onActualStateChange(context, intent);
        } else if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(action)
                || LocationManager.MODE_CHANGED_ACTION.equals(action)) {
            sGpsState.onActualStateChange(context, intent);
        } else {
            // sync settings or brightness changed
            sSyncState.onActualStateChange(context, null);
        }
        updateWidget(context);
    }

    /**
     * Updates the buttons based on the underlying states of wifi, etc.
     *
     * @param views   The RemoteViews to update.
     * @param context
     */
    private static void updateButtons(RemoteViews views, Context context) {
        sWifiState.setImageViewResources(context, views);
        sBluetoothState.setImageViewResources(context, views);
        sGpsState.setImageViewResources(context, views);
        sSyncState.setImageViewResources(context, views);

        if (getBrightnessMode(context)) {
            views.setImageViewResource(R.id.img_brightness,
                                       R.drawable.ic_appwidget_settings_brightness_auto);
            views.setImageViewResource(R.id.ind_brightness,
                                       R.drawable.appwidget_settings_ind_on_r);
        } else if (getBrightness(context)) {
            views.setImageViewResource(R.id.img_brightness,
                                       R.drawable.ic_appwidget_settings_brightness_on);
            views.setImageViewResource(R.id.ind_brightness,
                                       R.drawable.appwidget_settings_ind_on_r);
        } else {
            views.setImageViewResource(R.id.img_brightness,
                                       R.drawable.ic_appwidget_settings_brightness_off);
            views.setImageViewResource(R.id.ind_brightness,
                                       R.drawable.appwidget_settings_ind_off_r);
        }
    }

    /**
     * Creates PendingIntent to notify the widget of a button click.
     *
     * @param context
     * @param appWidgetId
     * @return
     */
    private static PendingIntent getLaunchPendingIntent(Context context, int appWidgetId,
            int buttonId) {
        Intent launchIntent = new Intent();
        launchIntent.setClass(context, SettingsAppWidgetProvider.class);
        launchIntent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        launchIntent.setData(Uri.parse("custom:" + buttonId));
        PendingIntent pi = PendingIntent.getBroadcast(context, 0 /* no requestCode */,
                launchIntent, PendingIntent.FLAG_IMMUTABLE); // Port: FLAG_IMMUTABLE
        return pi;
    }

    /** Port: opens the system settings for what can't be switched by apps anymore. */
    private static PendingIntent getSettingsPendingIntent(Context context, int buttonId,
            Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, buttonId, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Receives and processes a button pressed intent or state change.
     *
     * @param context
     * @param intent  Indicates the pressed button.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent.hasCategory(Intent.CATEGORY_ALTERNATIVE)) {
            Uri data = intent.getData();
            int buttonId = Integer.parseInt(data.getSchemeSpecificPart());
            if (buttonId == BUTTON_BRIGHTNESS) {
                toggleBrightness(context);
            } else if (buttonId == BUTTON_SYNC) {
                sSyncState.toggleState(context);
            }
        } else {
            // Don't fall-through to updating the widget.  The Intent
            // was something unrelated or that our super class took
            // care of.
            return;
        }

        // State changes fall through
        updateWidget(context);
    }

    /**
     * Gets state of brightness.
     *
     * @param context
     * @return true if more than moderately bright.
     */
    private static boolean getBrightness(Context context) {
        try {
            int brightness = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
            return brightness > 100;
        } catch (Exception e) {
            Log.d(TAG, "getBrightness: " + e);
        }
        return false;
    }

    /**
     * Gets state of brightness mode.
     *
     * @param context
     * @return true if auto brightness is on.
     */
    private static boolean getBrightnessMode(Context context) {
        try {
            int brightnessMode = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE);
            return brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            Log.d(TAG, "getBrightnessMode: " + e);
        }
        return false;
    }

    /** Port: com.android.internal.R.bool.config_automatic_brightness_available */
    private static boolean isAutomaticBrightnessAvailable() {
        final Resources res = Resources.getSystem();
        final int id = res.getIdentifier("config_automatic_brightness_available", "bool",
                "android");
        return id == 0 || res.getBoolean(id);
    }

    /**
     * Increases or decreases the brightness.
     *
     * @param context
     */
    private void toggleBrightness(Context context) {
        // Port: needs "Modify system settings" now; ask for it first
        if (!Settings.System.canWrite(context)) {
            context.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        try {
            ContentResolver cr = context.getContentResolver();
            int brightness = Settings.System.getInt(cr,
                    Settings.System.SCREEN_BRIGHTNESS);
            int brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
            //Only get brightness setting if available
            if (isAutomaticBrightnessAvailable()) {
                brightnessMode = Settings.System.getInt(cr,
                        Settings.System.SCREEN_BRIGHTNESS_MODE);
            }

            // Rotate AUTO -> MINIMUM -> DEFAULT -> MAXIMUM
            // Technically, not a toggle...
            if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                brightness = MINIMUM_BACKLIGHT;
                brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
            } else if (brightness < DEFAULT_BACKLIGHT) {
                brightness = DEFAULT_BACKLIGHT;
            } else if (brightness < MAXIMUM_BACKLIGHT) {
                brightness = MAXIMUM_BACKLIGHT;
            } else {
                brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
                brightness = MINIMUM_BACKLIGHT;
            }

            if (isAutomaticBrightnessAvailable()) {
                // Set screen brightness mode (automatic or manual)
                Settings.System.putInt(context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        brightnessMode);
            } else {
                // Make sure we set the brightness if automatic mode isn't available
                brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
            }
            if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                // Port: power.setBacklightBrightness() - the setting applies it now
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, brightness);
            }
        } catch (Settings.SettingNotFoundException e) {
            Log.d(TAG, "toggleBrightness: " + e);
        }
    }
}
