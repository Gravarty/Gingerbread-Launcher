/*
 * Port: replaces NewsWidgetConfigurationActivity of "News and Weather" 1.3.01 for the
 * weather widget: OpenWeatherMap API key, location (city or postal code, entered like in
 * the original's "Set location") and the weather app the widget opens. Laid out like a
 * Gingerbread settings screen.
 */
package com.google.android.apps.genie.geniewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class WeatherWidgetConfigure extends Activity {
    private int mWidgetId;
    private SharedPreferences mPrefs;
    private LinearLayout mList;
    private boolean mLastWasItem;

    private String mApiKey;
    private String mLocation;
    private String mApp;
    private int mInterval;
    private TextView mIntervalSummary;
    private TextView mKeySummary, mLocationSummary, mAppSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (com.android.launcher2.Launcher.useDroidSans(this)) {
            getTheme().applyStyle(R.style.DroidSansFont, true);
        }
        setResult(RESULT_CANCELED);
        mWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (mWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        mPrefs = getSharedPreferences(WeatherWidgetProvider.PREFS, 0);
        final String k = WeatherWidgetProvider.key(mWidgetId, "");
        mApiKey = mPrefs.getString(k + "apikey", mPrefs.getString("last_apikey", ""));
        mLocation = mPrefs.getString(k + "query", "");
        mApp = mPrefs.getString(k + "app", null);
        mInterval = mPrefs.getInt(k + "interval", WeatherWidgetProvider.DEFAULT_INTERVAL);

        setContentView(R.layout.settings);
        ((TextView) findViewById(R.id.settings_title)).setText(R.string.genie_widget_weather);
        mList = (LinearLayout) findViewById(R.id.settings_list);

        addCategory(R.string.genie_category_weather);
        mKeySummary = addItem(R.string.genie_api_key, v -> askText(R.string.genie_api_key,
                R.string.genie_api_key_hint, mApiKey, InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, s -> {
                    mApiKey = s;
                    updateSummaries();
                }));
        mLocationSummary = addItem(R.string.genie_location, v -> askText(
                R.string.genie_location, R.string.genie_enterlocation, mLocation,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, s -> {
                    mLocation = s;
                    updateSummaries();
                }));
        mIntervalSummary = addItem(R.string.genie_refresh_interval, v -> chooseInterval());
        addCategory(R.string.genie_category_app);
        mAppSummary = addItem(R.string.genie_weather_app, v -> chooseApp());
        updateSummaries();

        // Gingerbread style button bar below the list
        final LinearLayout root = (LinearLayout) mList.getParent().getParent().getParent();
        final LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0xff404040);
        final int pad = (int) (4 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad + pad, pad, pad);
        final Button save = new Button(this);
        save.setText(R.string.genie_save);
        save.setOnClickListener(v -> save());
        bar.addView(save, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);
    }

    private void updateSummaries() {
        setSummary(mKeySummary, mApiKey.isEmpty() ? getString(R.string.genie_not_set)
                : "••••••••" + mApiKey.substring(Math.max(0, mApiKey.length() - 4)));
        setSummary(mLocationSummary, mLocation.isEmpty() ? getString(R.string.genie_not_set)
                : mLocation);
        CharSequence app = getString(R.string.genie_not_set);
        if (mApp != null) {
            try {
                app = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(mApp, 0));
            } catch (PackageManager.NameNotFoundException e) {
                mApp = null;
            }
        }
        setSummary(mAppSummary, app);
        setSummary(mIntervalSummary, intervalLabel(mInterval));
    }

    private static void setSummary(TextView view, CharSequence s) {
        view.setText(s);
        view.setVisibility(View.VISIBLE);
    }

    private void addCategory(int title) {
        final TextView v = (TextView) getLayoutInflater().inflate(
                R.layout.settings_category, mList, false);
        v.setText(title);
        mList.addView(v);
        mLastWasItem = false;
    }

    private TextView addItem(int title, View.OnClickListener click) {
        if (mLastWasItem) {
            final View divider = new View(this);
            divider.setBackgroundResource(R.drawable.gb_divider_horizontal_dark_opaque);
            mList.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        final View v = getLayoutInflater().inflate(R.layout.settings_preference, mList, false);
        ((TextView) v.findViewById(R.id.settings_item_title)).setText(title);
        v.setOnClickListener(click);
        mList.addView(v);
        mLastWasItem = true;
        return (TextView) v.findViewById(R.id.settings_item_summary);
    }

    private interface TextResult { void set(String s); }

    private void askText(int title, int message, String value, int inputType, TextResult r) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        input.setSelection(input.getText().length());
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);
        final FrameLayout frame = new FrameLayout(this);
        frame.setPadding(pad, 0, pad, 0);
        frame.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(frame)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> r.set(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String intervalLabel(int minutes) {
        return minutes < 60 ? getString(R.string.genie_minutes, minutes)
                : getResources().getQuantityString(R.plurals.genie_hours, minutes / 60,
                        minutes / 60);
    }

    private void chooseInterval() {
        final int[] values = WeatherWidgetProvider.INTERVALS;
        final CharSequence[] labels = new CharSequence[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = intervalLabel(values[i]);
            if (values[i] == mInterval) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.genie_refresh_interval)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    mInterval = values[which];
                    updateSummaries();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Weather apps first (name or package mentions weather), then all other apps. */
    private void chooseApp() {
        final PackageManager pm = getPackageManager();
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.genie_weather_app)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]),
                        pkgs.indexOf(mApp), (d, which) -> {
                            mApp = pkgs.get(which);
                            updateSummaries();
                            d.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Looks the location up (original: "Set location"), saves and updates the widget. */
    private void save() {
        if (mApiKey.isEmpty() || mLocation.isEmpty()) {
            Toast.makeText(this, R.string.genie_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        final ProgressDialog progress = ProgressDialog.show(this, null,
                getString(R.string.genie_searching), true, false);
        final Handler main = new Handler(Looper.getMainLooper());
        final String key = mApiKey, query = mLocation;
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
                    error = getString(R.string.genie_setlocationfailed, query);
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
                        ? getString(R.string.genie_invalid_key)
                        : getString(R.string.genie_networkfailure);
            } catch (Exception e) {
                error = getString(R.string.genie_networkfailure);
            }
            final String err = error, placeName = name;
            final double la = lat, lo = lon;
            main.post(() -> {
                progress.dismiss();
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    return;
                }
                final String k = WeatherWidgetProvider.key(mWidgetId, "");
                final SharedPreferences.Editor e = mPrefs.edit()
                        .putString("last_apikey", key)
                        .putString(k + "apikey", key)
                        .putString(k + "query", query)
                        .putString(k + "name", placeName)
                        .putFloat(k + "lat", (float) la)
                        .putFloat(k + "lon", (float) lo)
                        .putInt(k + "interval", mInterval)
                        .remove(k + "temp"); // new location: show loading
                if (mApp != null) e.putString(k + "app", mApp); else e.remove(k + "app");
                e.apply();
                WeatherWidgetProvider.update(this, mWidgetId);
                setResult(RESULT_OK, new Intent()
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId));
                finish();
            });
        }).start();
    }
}
