/*
 * Weather widget settings, shown in the launcher settings (Widgets), for all weather widgets:
 * OpenWeatherMap API key, location (city or postal code, entered like in the original's
 * "Set location"), refresh interval and the weather app the widget opens. Applied at once.
 */
package com.google.android.apps.genie.geniewidget;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher.R;
import com.android.launcher2.GbDialog;
import com.android.launcher2.GbSettingsList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class WeatherSettings {
    private final Activity mActivity;
    private final SharedPreferences mPrefs;
    private TextView mKeySummary, mLocationSummary, mIntervalSummary, mAppSummary;

    public static void add(Activity activity, GbSettingsList page) {
        new WeatherSettings(activity, page);
    }

    private WeatherSettings(Activity activity, GbSettingsList page) {
        mActivity = activity;
        mPrefs = activity.getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        WeatherWidgetProvider.migrate(mPrefs);

        page.addCategory(R.string.genie_widget_weather);
        mKeySummary = item(page, R.string.genie_api_key, v -> askText(R.string.genie_api_key,
                R.string.genie_api_key_hint, get("apikey"), InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, s -> {
                    mPrefs.edit().putString("cfg_apikey", s).apply();
                    updateSummaries();
                    lookup();
                }));
        mLocationSummary = item(page, R.string.genie_location, v -> askText(
                R.string.genie_location, R.string.genie_enterlocation, get("query"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, s -> {
                    mPrefs.edit().putString("cfg_query", s).apply();
                    updateSummaries();
                    lookup();
                }));
        mIntervalSummary = item(page, R.string.genie_refresh_interval, v -> chooseInterval());
        mAppSummary = item(page, R.string.genie_weather_app, v -> chooseApp());
        updateSummaries();
    }

    private String get(String name) {
        return mPrefs.getString("cfg_" + name, "");
    }

    private TextView item(GbSettingsList page, int title, View.OnClickListener click) {
        final View v = page.addItem(title, null);
        v.setOnClickListener(click);
        return (TextView) v.findViewById(R.id.settings_item_summary);
    }

    private void updateSummaries() {
        final String key = get("apikey"), loc = get("query");
        GbSettingsList.setSummary(mKeySummary, key.isEmpty()
                ? mActivity.getString(R.string.genie_not_set)
                : "••••••••" + key.substring(Math.max(0, key.length() - 4)));
        GbSettingsList.setSummary(mLocationSummary, loc.isEmpty()
                ? mActivity.getString(R.string.genie_not_set) : loc);
        CharSequence app = mActivity.getString(R.string.genie_not_set);
        final String pkg = mPrefs.getString("cfg_app", null);
        if (pkg != null) {
            try {
                final PackageManager pm = mActivity.getPackageManager();
                app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                mPrefs.edit().remove("cfg_app").apply();
            }
        }
        GbSettingsList.setSummary(mAppSummary, app);
        GbSettingsList.setSummary(mIntervalSummary, intervalLabel(
                mPrefs.getInt("cfg_interval", WeatherWidgetProvider.DEFAULT_INTERVAL)));
    }

    private interface TextResult { void set(String s); }

    private void askText(int title, int message, String value, int inputType, TextResult r) {
        final EditText input = GbDialog.newEditText(mActivity);
        input.setTextColor(0xff000000); // the dialog background is light
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        input.setSelection(input.getText().length());
        new GbDialog.Builder(mActivity)
                .setTitle(title)
                .setMessage(message)
                .setView(GbDialog.wrapField(mActivity, input))
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> r.set(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String intervalLabel(int minutes) {
        return minutes < 60 ? mActivity.getString(R.string.genie_minutes, minutes)
                : mActivity.getResources().getQuantityString(R.plurals.genie_hours,
                        minutes / 60, minutes / 60);
    }

    private void chooseInterval() {
        final int[] values = WeatherWidgetProvider.INTERVALS;
        final int current = mPrefs.getInt("cfg_interval", WeatherWidgetProvider.DEFAULT_INTERVAL);
        final CharSequence[] labels = new CharSequence[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = intervalLabel(values[i]);
            if (values[i] == current) checked = i;
        }
        new GbDialog.Builder(mActivity)
                .setTitle(R.string.genie_refresh_interval)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    mPrefs.edit().putInt("cfg_interval", values[which]).apply();
                    updateSummaries();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Weather apps first (name or package mentions weather), then all other apps. */
    private void chooseApp() {
        final PackageManager pm = mActivity.getPackageManager();
        final List<ResolveInfo> all = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collections.sort(all, new ResolveInfo.DisplayNameComparator(pm));
        final ArrayList<String> pkgs = new ArrayList<String>();
        final ArrayList<CharSequence> labels = new ArrayList<CharSequence>();
        for (int pass = 0; pass < 2; pass++) {
            for (ResolveInfo ri : all) {
                final String pkg = ri.activityInfo.packageName;
                final String label = ri.loadLabel(pm).toString();
                final String l = (label + " " + pkg).toLowerCase(Locale.ROOT);
                final boolean weather = l.contains("weather") || l.contains("wetter");
                if ((pass == 0) != weather || pkgs.contains(pkg)) continue;
                pkgs.add(pkg);
                labels.add(label);
            }
        }
        new GbDialog.Builder(mActivity)
                .setTitle(R.string.genie_weather_app)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]),
                        pkgs.indexOf(mPrefs.getString("cfg_app", null)), (d, which) -> {
                            mPrefs.edit().putString("cfg_app", pkgs.get(which)).apply();
                            updateSummaries();
                            WeatherWidgetProvider.applySettings(mActivity, false);
                            d.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** With API key and location set: looks the location up (original: "Set location"). */
    private void lookup() {
        final String key = get("apikey"), query = get("query");
        if (key.isEmpty() || query.isEmpty()) return;
        final GbDialog progress = GbDialog.showProgress(mActivity,
                mActivity.getString(R.string.genie_searching));
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = null;
            double lat = 0, lon = 0;
            String name = null;
            try {
                final String geo = "https://api.openweathermap.org/geo/1.0/";
                final String apiKey = "&appid=" + Uri.encode(key);
                JSONObject place = null;
                if (query.matches("\\d{4,6}(,\\s*\\w+)?")) { // postal code, country optional
                    final String zip = query.contains(",") ? query.replace(" ", "")
                            : query + "," + Locale.getDefault().getCountry();
                    try {
                        place = new JSONObject(WeatherWidgetProvider.get(
                                geo + "zip?zip=" + Uri.encode(zip) + apiKey));
                    } catch (Exception e) {
                        place = null;
                    }
                }
                if (place == null) {
                    final JSONArray found = new JSONArray(WeatherWidgetProvider.get(
                            geo + "direct?q=" + Uri.encode(query) + "&limit=1" + apiKey));
                    if (found.length() > 0) place = found.getJSONObject(0);
                }
                if (place == null) {
                    error = mActivity.getString(R.string.genie_setlocationfailed, query);
                } else {
                    lat = place.getDouble("lat");
                    lon = place.getDouble("lon");
                    name = place.optString("name", query);
                    final JSONObject local = place.optJSONObject("local_names");
                    if (local != null) {
                        name = local.optString(WeatherWidgetProvider.lang(), name);
                    }
                }
            } catch (java.io.IOException e) {
                error = e.getMessage() != null && e.getMessage().startsWith("HTTP 401")
                        ? mActivity.getString(R.string.genie_invalid_key)
                        : mActivity.getString(R.string.genie_networkfailure);
            } catch (Exception e) {
                error = mActivity.getString(R.string.genie_networkfailure);
            }
            final String err = error, placeName = name;
            final double la = lat, lo = lon;
            main.post(() -> {
                if (!mActivity.isFinishing()) progress.dismiss();
                if (err != null) {
                    Toast.makeText(mActivity, err, Toast.LENGTH_LONG).show();
                    return;
                }
                mPrefs.edit()
                        .putString("cfg_name", placeName)
                        .putFloat("cfg_lat", (float) la)
                        .putFloat("cfg_lon", (float) lo)
                        .apply();
                WeatherWidgetProvider.applySettings(mActivity, true);
            });
        }).start();
    }
}
