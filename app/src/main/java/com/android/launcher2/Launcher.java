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

import android.app.Activity;
import android.app.Dialog;
import android.app.SearchManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.provider.LiveFolders;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.TextKeyListener;
import android.util.Log;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnLongClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.LinearLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.DataInputStream;

import com.android.launcher.R;

/**
 * Default launcher application.
 */
public final class Launcher extends Activity
        implements View.OnClickListener, OnLongClickListener, LauncherModel.Callbacks, AllAppsView.Watcher {
    static final String TAG = "Launcher";
    static final boolean LOGD = false;

    static final boolean PROFILE_STARTUP = false;
    static final boolean DEBUG_WIDGETS = false;
    static final boolean DEBUG_USER_INTERFACE = false;

    private static final int WALLPAPER_SCREENS_SPAN = 2;

    private static final int MENU_GROUP_ADD = 1;
    private static final int MENU_GROUP_WALLPAPER = MENU_GROUP_ADD + 1;

    private static final int MENU_ADD = Menu.FIRST + 1;
    private static final int MENU_MANAGE_APPS = MENU_ADD + 1;
    private static final int MENU_WALLPAPER_SETTINGS = MENU_MANAGE_APPS + 1;
    private static final int MENU_SEARCH = MENU_WALLPAPER_SETTINGS + 1;
    private static final int MENU_NOTIFICATIONS = MENU_SEARCH + 1;
    private static final int MENU_SETTINGS = MENU_NOTIFICATIONS + 1;

    private static final int REQUEST_CREATE_SHORTCUT = 1;
    private static final int REQUEST_CREATE_LIVE_FOLDER = 4;
    private static final int REQUEST_CREATE_APPWIDGET = 5;
    private static final int REQUEST_PICK_APPLICATION = 6;
    private static final int REQUEST_PICK_SHORTCUT = 7;
    private static final int REQUEST_PICK_LIVE_FOLDER = 8;
    private static final int REQUEST_PICK_APPWIDGET = 9;
    private static final int REQUEST_PICK_WALLPAPER = 10;
    // Port: permission to bind the default search widget (see LauncherProvider)
    private static final int REQUEST_BIND_SEARCH_WIDGET = 11;
    static final String PENDING_SEARCH_WIDGET = "launcher.pending_search_widget";
    private int mPendingSearchWidgetId = -1;
    // Tweaks: own widget picker, the system may ask for the permission to bind the widget
    private static final int REQUEST_BIND_APPWIDGET = 12;
    private int mPendingWidgetId = -1;
    // Tweaks: Gingerbread-style pickers (widgets, shortcuts, applications, folders, wallpapers)
    private GbDialog mPickerDialog;

    static final String EXTRA_SHORTCUT_DUPLICATE = "duplicate";

    // Tweaks: number of home screens is configurable (5 is the stock value, max. 5)
    static int SCREEN_COUNT = 5;
    static int DEFAULT_SCREEN = 2;
    // Tweaks: grid size is configurable (4x4 is the stock value)
    static int NUMBER_CELLS_X = 4;
    static int NUMBER_CELLS_Y = 4;

    // Tweaks
    static final String TWEAKS_PREFERENCES = "launcher.tweaks";
    static final String TWEAK_HOME_COLUMNS = "home_columns";
    static final String TWEAK_HOME_ROWS = "home_rows";
    static final String TWEAK_DRAWER_COLUMNS = "drawer_columns";
    static final String TWEAK_DRAWER_ROWS = "drawer_rows";
    // Tweaks: app drawer style, 0 = 3D (stock), 1 = 2D (AllApps2D, the Gingerbread drawer for
    // devices without RenderScript)
    static final String TWEAK_DRAWER_STYLE = "drawer_style";
    static boolean DRAWER_2D = false;
    static final String TWEAK_HOME_SCREENS = "home_screens";
    static final String TWEAK_SWIPE_DOWN_NOTIFICATIONS = "swipe_down_notifications";
    // Tweaks: extra dock slots, added in the order
    // phone, [contacts], [messaging], (all apps), browser, [email], [camera]
    static final String[] DOCK_KEYS = { "dock_contacts", "dock_messaging", "dock_mail",
            "dock_camera" };
    static final String[] DOCK_INTENTS = {
        "intent:#Intent;action=android.intent.action.MAIN;"
                + "category=android.intent.category.APP_CONTACTS;end",
        "intent:#Intent;action=android.intent.action.MAIN;"
                + "category=android.intent.category.APP_MESSAGING;end",
        "intent:#Intent;action=android.intent.action.MAIN;"
                + "category=android.intent.category.APP_EMAIL;end",
        "intent:#Intent;action=android.media.action.STILL_IMAGE_CAMERA;end",
    };
    static final int[] DOCK_ICONS = { R.drawable.hotseat_contacts, R.drawable.hotseat_messaging,
            R.drawable.hotseat_mail, R.drawable.hotseat_camera };
    static final int[] DOCK_LABELS = { R.string.tweaks_dock_contacts,
            R.string.tweaks_dock_messaging, R.string.tweaks_dock_mail,
            R.string.tweaks_dock_camera };

    // Tweaks: dock order and visibility, "id:1,id:0,..." (logical order: left to right in
    // portrait, bottom to top in landscape). Default: the stock dock, phone, all apps, browser.
    static final String TWEAK_DOCK = "dock";
    static final String DOCK_PHONE = "phone", DOCK_ALL_APPS = "all_apps",
            DOCK_BROWSER = "browser";
    static final String[] DOCK_EXTRA_IDS = { "contacts", "messaging", "mail", "camera" };
    private static final String[] DOCK_STOCK = { DOCK_PHONE, DOCK_ALL_APPS, DOCK_BROWSER };
    // Tweaks: the all apps button is hidden: the drawer doesn't draw its home button either
    static volatile boolean sDockAllAppsHidden;

    /** Tweaks: the dock items in order, each "id" or "-id" when it's hidden. */
    static java.util.ArrayList<String> loadDock(android.content.SharedPreferences prefs) {
        final java.util.ArrayList<String> items = new java.util.ArrayList<String>();
        final String saved = prefs.getString(TWEAK_DOCK, null);
        if (saved != null) {
            for (String item : saved.split(",")) {
                if (item.length() > 1) items.add(item);
            }
        } else {
            // Order of the former extra slots: phone, [contacts], [messaging], (all apps),
            // browser, [email], [camera]
            items.add(DOCK_PHONE);
            for (int i = 0; i < 2; i++) {
                items.add((prefs.getBoolean(DOCK_KEYS[i], false) ? "" : "-") + DOCK_EXTRA_IDS[i]);
            }
            items.add(DOCK_ALL_APPS);
            items.add(DOCK_BROWSER);
            for (int i = 2; i < 4; i++) {
                items.add((prefs.getBoolean(DOCK_KEYS[i], false) ? "" : "-") + DOCK_EXTRA_IDS[i]);
            }
        }
        // items added later or missing: hidden at the end
        for (String id : allDockIds()) {
            if (!items.contains(id) && !items.contains("-" + id)) items.add("-" + id);
        }
        return items;
    }

    static String[] allDockIds() {
        return new String[] { DOCK_PHONE, DOCK_EXTRA_IDS[0], DOCK_EXTRA_IDS[1], DOCK_ALL_APPS,
                DOCK_BROWSER, DOCK_EXTRA_IDS[2], DOCK_EXTRA_IDS[3] };
    }

    static int dockLabel(String id) {
        if (DOCK_PHONE.equals(id)) return R.string.tweaks_dock_phone;
        if (DOCK_ALL_APPS.equals(id)) return R.string.tweaks_dock_all_apps;
        if (DOCK_BROWSER.equals(id)) return R.string.tweaks_dock_browser;
        for (int i = 0; i < DOCK_EXTRA_IDS.length; i++) {
            if (DOCK_EXTRA_IDS[i].equals(id)) return DOCK_LABELS[i];
        }
        return 0;
    }

    /**
     * Tweaks: the dock in the chosen order. With the default (phone, all apps, browser)
     * nothing is touched. Otherwise the buttons are put into a centered row: the outermost
     * ones get the stock left and right backgrounds and margins, the others the center
     * background (the HotseatButton styles); new buttons are inflated with those styles.
     */
    private void setupDock(ImageView phone, ImageView browser) {
        final java.util.ArrayList<String> shown = new java.util.ArrayList<String>();
        for (String item : loadDock(getSharedPreferences(TWEAKS_PREFERENCES, MODE_PRIVATE))) {
            if (!item.startsWith("-")) shown.add(item);
        }
        sDockAllAppsHidden = !shown.contains(DOCK_ALL_APPS);
        if (shown.equals(java.util.Arrays.asList(DOCK_STOCK))) return;

        final android.widget.RelativeLayout cluster =
                (android.widget.RelativeLayout) findViewById(R.id.all_apps_button_cluster);
        final View allApps = findViewById(R.id.all_apps_button);
        final boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        // the stock looks: phone is the left button, browser the right one
        final ViewGroup.MarginLayoutParams leftLp =
                (ViewGroup.MarginLayoutParams) phone.getLayoutParams();
        final ViewGroup.MarginLayoutParams rightLp =
                (ViewGroup.MarginLayoutParams) browser.getLayoutParams();
        final Drawable.ConstantState leftBg = phone.getBackground().getConstantState();
        final Drawable.ConstantState rightBg = browser.getBackground().getConstantState();
        final Drawable.ConstantState centerBg = getLayoutInflater().inflate(
                R.layout.hotseat_extra, cluster, false).getBackground().getConstantState();
        final int[] leftMargins = { leftLp.leftMargin, leftLp.topMargin, leftLp.rightMargin,
                leftLp.bottomMargin };
        final int[] rightMargins = { rightLp.leftMargin, rightLp.topMargin,
                rightLp.rightMargin, rightLp.bottomMargin };

        cluster.removeView(phone);
        cluster.removeView(browser);
        if (!shown.contains(DOCK_ALL_APPS)) {
            allApps.setVisibility(View.GONE); // stays for the code that refers to it
        } else {
            cluster.removeView(allApps);
        }

        // While the drawer is open its home button lies over the dock: in stock the all apps
        // button below it takes the taps. Here the buttons may sit anywhere, so the row lets
        // the touches through to the drawer, which handles its home button itself.
        final LinearLayout row = new LinearLayout(this) {
            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                return !isAllAppsVisible() && super.dispatchTouchEvent(ev);
            }
        };
        row.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        final android.widget.RelativeLayout.LayoutParams rowLp;
        if (landscape) {
            rowLp = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        } else {
            rowLp = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            rowLp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
            rowLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
        }
        cluster.addView(row, rowLp);

        final java.util.ArrayList<View> buttons = new java.util.ArrayList<View>();
        for (int i = 0; i < shown.size(); i++) {
            final String id = shown.get(i);
            final View b;
            if (DOCK_PHONE.equals(id)) {
                b = phone;
            } else if (DOCK_BROWSER.equals(id)) {
                b = browser;
            } else if (DOCK_ALL_APPS.equals(id)) {
                b = allApps;
            } else {
                b = newDockButton(cluster, java.util.Arrays.asList(DOCK_EXTRA_IDS).indexOf(id));
            }
            final boolean first = i == 0, last = i == shown.size() - 1;
            // setBackground() would replace the style's padding with the 9-patch padding
            final int pl = b.getPaddingLeft(), pt = b.getPaddingTop(),
                    pr = b.getPaddingRight(), pb = b.getPaddingBottom();
            b.setBackground((first ? leftBg : last ? rightBg : centerBg).newDrawable());
            b.setPadding(pl, pt, pr, pb);
            final LinearLayout.LayoutParams lp = landscape
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
            final int[] m = first ? leftMargins : last ? rightMargins : new int[4];
            lp.setMargins(m[0], m[1], m[2], m[3]);
            b.setLayoutParams(lp);
            buttons.add(b);
        }
        // landscape: the logical order runs from the bottom up
        for (int i = 0; i < buttons.size(); i++) {
            row.addView(buttons.get(landscape ? buttons.size() - 1 - i : i));
        }

        // Only if the buttons don't fit: shrink their side padding evenly
        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int available = landscape ? dm.heightPixels : dm.widthPixels;
        int needed = 0;
        int paddings = 0;
        for (View v : buttons) {
            final Drawable d = ((ImageView) v).getDrawable();
            final int content = d == null ? 0
                    : (landscape ? d.getIntrinsicHeight() : d.getIntrinsicWidth());
            final int pad = landscape ? v.getPaddingTop() + v.getPaddingBottom()
                    : v.getPaddingLeft() + v.getPaddingRight();
            final ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            needed += content + pad + (landscape ? lp.topMargin + lp.bottomMargin
                    : lp.leftMargin + lp.rightMargin);
            paddings += pad;
        }
        if (needed > available && paddings > 0) {
            final float scale = Math.max(0f, 1f - (float) (needed - available) / paddings);
            for (View v : buttons) {
                if (landscape) {
                    v.setPadding(v.getPaddingLeft(), (int) (v.getPaddingTop() * scale),
                            v.getPaddingRight(), (int) (v.getPaddingBottom() * scale));
                } else {
                    v.setPadding((int) (v.getPaddingLeft() * scale), v.getPaddingTop(),
                            (int) (v.getPaddingRight() * scale), v.getPaddingBottom());
                }
            }
        }

        // A wider dock can reach the page indicators: hide them where they'd overlap
        cluster.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                hideOverlappedIndicators(cluster));
    }

    // Tweaks: dock buttons vs. the visible part of the page indicators (scaleType center)
    private void hideOverlappedIndicators(ViewGroup cluster) {
        if (mPreviousView == null || mNextView == null) return;
        final android.graphics.Rect dock = new android.graphics.Rect();
        for (int i = 0; i < cluster.getChildCount(); i++) {
            final View c = cluster.getChildAt(i);
            if (c.getVisibility() == View.GONE) continue;
            dock.union(cluster.getLeft() + c.getLeft(), cluster.getTop() + c.getTop(),
                    cluster.getLeft() + c.getRight(), cluster.getTop() + c.getBottom());
        }
        for (ImageView indicator : new ImageView[] { mPreviousView, mNextView }) {
            final Drawable d = indicator.getDrawable();
            final int w = d != null ? d.getIntrinsicWidth() : indicator.getWidth();
            final int h = d != null ? d.getIntrinsicHeight() : indicator.getHeight();
            final int cx = (indicator.getLeft() + indicator.getRight()) / 2;
            final int cy = (indicator.getTop() + indicator.getBottom()) / 2;
            final boolean overlaps = android.graphics.Rect.intersects(dock,
                    new android.graphics.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2));
            indicator.setVisibility(overlaps ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private ImageView newDockButton(ViewGroup cluster, int slot) {
        final ImageView b = (ImageView) getLayoutInflater().inflate(
                R.layout.hotseat_extra, cluster, false);
        b.setId(View.generateViewId());
        b.setTag(slot + 2); // index in mHotseats
        b.setImageDrawable(mHotseatIcons[slot + 2]);
        b.setContentDescription(mHotseatLabels[slot + 2]);
        return b;
    }
    static boolean SWIPE_DOWN_NOTIFICATIONS = false;
    // Tweaks: swipe up opens all apps, following the finger
    static final String TWEAK_SWIPE_UP_ALL_APPS = "swipe_up_all_apps";
    // Tweaks: Google feed left of the first screen (FeedOverlay.PROVIDER_*)
    static final String TWEAK_FEED = "feed";
    // Tweaks: notification badges (TouchWiz 3.0)
    static final String TWEAK_BADGES = "badges";
    static boolean BADGES = false;
    static int FEED = FeedOverlay.PROVIDER_OFF;
    static boolean SWIPE_UP_ALL_APPS = false;
    // Tweaks: no black system bars, the wallpaper shows behind them
    // (since Android 15 the system draws no bar backgrounds any more: the covered bar is
    // drawn by DragLayer itself, each bar on its own)
    static final String TWEAK_NO_BAR_BACKGROUNDS = "no_bar_backgrounds"; // old, both bars
    static final String TWEAK_TRANSPARENT_STATUS_BAR = "transparent_status_bar";
    static final String TWEAK_TRANSPARENT_NAV_BAR = "transparent_nav_bar";
    static boolean TRANSPARENT_STATUS_BAR = false;
    static boolean TRANSPARENT_NAV_BAR = false;
    /** At least one bar is transparent (the drawer then draws over the whole surface). */
    static boolean NO_BAR_BACKGROUNDS = false;
    // Tweaks: shape of adaptive icons, index into Utilities.ICON_SHAPE_MASKS (0 = system)
    static final String TWEAK_ICON_SHAPE = "icon_shape";
    static int ICON_SHAPE = 0;
    // Tweaks: icon pack (package name, null = none), on the home screen or everywhere
    static final String TWEAK_ICON_PACK = "icon_pack";
    static final String TWEAK_ICON_PACK_EVERYWHERE = "icon_pack_everywhere";
    static String ICON_PACK = null;
    static boolean ICON_PACK_EVERYWHERE = false;
    // Tweaks: own colors for the icon pack's icons (default: clock hand green on dock grey)
    static final String TWEAK_ICON_PACK_COLORS = "icon_pack_colors";
    static final String TWEAK_ICON_PACK_FG = "icon_pack_fg";
    static final String TWEAK_ICON_PACK_BG = "icon_pack_bg";
    static final int DEFAULT_ICON_PACK_FG = 0xff8fbc46; // dock green
    static final int DEFAULT_ICON_PACK_BG = 0xff2a2a2a;
    static boolean ICON_PACK_COLORS = false;
    static int ICON_PACK_FG = DEFAULT_ICON_PACK_FG;
    static int ICON_PACK_BG = DEFAULT_ICON_PACK_BG;
    static final String TWEAK_HIDDEN_APPS = "hidden_apps"; // flattened ComponentNames

    // Tweaks: automatic home grid as Launcher3 picks it on Android 11
    // (android11-release InvariantDeviceProfile.invDistWeightedInterpolate + device_profiles.xml):
    // the grid of the display-option closest to the display size in dp
    private static final float[][] AUTO_GRID_PROFILES = {
            // minWidthDps, minHeightDps, numColumns, numRows
            { 255, 300, 3, 3 },     // 3_by_3: Super Short Stubby
            { 255, 400, 3, 3 },     // 3_by_3: Shorter Stubby
            { 275, 420, 4, 4 },     // 4_by_4: Short Stubby
            { 255, 450, 4, 4 },     // 4_by_4: Stubby
            { 296, 491.33f, 4, 4 }, // 4_by_4: Nexus S
            { 359, 567, 4, 4 },     // 4_by_4: Nexus 4
            { 335, 567, 4, 4 },     // 4_by_4: Nexus 5
            { 406, 694, 5, 5 },     // 5_by_5: Large Phone
            { 255, 400, 5, 5 },     // 5_by_5: Shorter Stubby
    };

    static int[] autoGrid(Context context) {
        final Display display = context.getSystemService(
                android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        final android.graphics.Point smallest = new android.graphics.Point();
        final android.graphics.Point largest = new android.graphics.Point();
        display.getCurrentSizeRange(smallest, largest);
        final float density = context.getResources().getDisplayMetrics().densityDpi
                / (float) android.util.DisplayMetrics.DENSITY_DEFAULT;
        // This guarantees that width < height
        final float width = Math.min(smallest.x, smallest.y) / density;
        final float height = Math.min(largest.x, largest.y) / density;
        float[] closest = AUTO_GRID_PROFILES[0];
        float best = Float.MAX_VALUE;
        for (float[] p : AUTO_GRID_PROFILES) {
            final float d = (float) Math.hypot(p[0] - width, p[1] - height);
            if (d < best) { // first one wins a tie, like the stable sort there
                best = d;
                closest = p;
            }
        }
        return new int[] { (int) closest[2], (int) closest[3] };
    }

    // Tweaks
    static void loadTweaks(Context context) {
        final android.content.SharedPreferences prefs =
                context.getSharedPreferences(TWEAKS_PREFERENCES, MODE_PRIVATE);
        final int columns = prefs.getInt(TWEAK_HOME_COLUMNS, 0); // 0 = automatic
        final int rows = prefs.getInt(TWEAK_HOME_ROWS, 0);
        final int[] auto = (columns == 0 || rows == 0) ? autoGrid(context) : null;
        NUMBER_CELLS_X = columns != 0 ? columns : auto[0];
        NUMBER_CELLS_Y = rows != 0 ? rows : auto[1];
        SCREEN_COUNT = Math.max(1, Math.min(5, prefs.getInt(TWEAK_HOME_SCREENS, 5)));
        DEFAULT_SCREEN = (SCREEN_COUNT - 1) / 2; // the middle one, 2 of 5 as in stock
        SWIPE_DOWN_NOTIFICATIONS = prefs.getBoolean(TWEAK_SWIPE_DOWN_NOTIFICATIONS, false);
        SWIPE_UP_ALL_APPS = prefs.getBoolean(TWEAK_SWIPE_UP_ALL_APPS, false);
        DRAWER_2D = prefs.getInt(TWEAK_DRAWER_STYLE, 0) == 1;
        FEED = prefs.getInt(TWEAK_FEED, FeedOverlay.PROVIDER_OFF);
        BADGES = prefs.getBoolean(TWEAK_BADGES, false);
        if (prefs.contains(TWEAK_NO_BAR_BACKGROUNDS)) { // one setting for both bars before
            final boolean both = prefs.getBoolean(TWEAK_NO_BAR_BACKGROUNDS, false);
            prefs.edit().remove(TWEAK_NO_BAR_BACKGROUNDS)
                    .putBoolean(TWEAK_TRANSPARENT_STATUS_BAR, both)
                    .putBoolean(TWEAK_TRANSPARENT_NAV_BAR, both).apply();
        }
        TRANSPARENT_STATUS_BAR = prefs.getBoolean(TWEAK_TRANSPARENT_STATUS_BAR, false);
        TRANSPARENT_NAV_BAR = prefs.getBoolean(TWEAK_TRANSPARENT_NAV_BAR, false);
        NO_BAR_BACKGROUNDS = TRANSPARENT_STATUS_BAR || TRANSPARENT_NAV_BAR;
        ICON_SHAPE = Math.max(0, Math.min(Utilities.ICON_SHAPE_MASKS.length - 1,
                prefs.getInt(TWEAK_ICON_SHAPE, 0)));
        ICON_PACK = prefs.getString(TWEAK_ICON_PACK, null);
        ICON_PACK_EVERYWHERE = prefs.getInt(TWEAK_ICON_PACK_EVERYWHERE, 0) == 1;
        ICON_PACK_COLORS = prefs.getInt(TWEAK_ICON_PACK_COLORS, 0) == 1;
        ICON_PACK_FG = iconPackColor(context, prefs, TWEAK_ICON_PACK_FG, DEFAULT_ICON_PACK_FG);
        ICON_PACK_BG = iconPackColor(context, prefs, TWEAK_ICON_PACK_BG, DEFAULT_ICON_PACK_BG);
        synchronized (sLock) {
            sScreen = Math.max(0, Math.min(sScreen, SCREEN_COUNT - 1));
        }
    }

    // Tweaks: "from the wallpaper" for a color (key + COLOR_FROM_WALLPAPER)
    static final String COLOR_FROM_WALLPAPER = "_wallpaper";

    /**
     * Tweaks: icon pack color; from the wallpaper it's the system's palette (Monet), as
     * Launcher3's themed icons in dark mode (ThemedIconDrawable.getColors(): background
     * system_neutral1_800, icon system_accent1_100).
     */
    private static int iconPackColor(Context context, android.content.SharedPreferences prefs,
            String key, int def) {
        if (prefs.getBoolean(key + COLOR_FROM_WALLPAPER, false)) {
            return context.getResources().getColor(TWEAK_ICON_PACK_BG.equals(key)
                    ? android.R.color.system_neutral1_800
                    : android.R.color.system_accent1_100, null);
        }
        return prefs.getInt(key, def);
    }

    /** Port: Gingerbread's system font (Droid Sans) instead of today's system font. */
    public static boolean useDroidSans(Context context) {
        // Tweaks: or the system font
        return !context.getSharedPreferences(TWEAKS_PREFERENCES, MODE_PRIVATE)
                .getBoolean(TWEAK_SYSTEM_FONT, false);
    }
    static final String TWEAK_SYSTEM_FONT = "system_font";

    static final int DIALOG_CREATE_SHORTCUT = 1;
    static final int DIALOG_RENAME_FOLDER = 2;

    private static final String PREFERENCES = "launcher.preferences";

    // Type: int
    private static final String RUNTIME_STATE_CURRENT_SCREEN = "launcher.current_screen";
    // Type: boolean
    private static final String RUNTIME_STATE_ALL_APPS_FOLDER = "launcher.all_apps_folder";
    // Type: long
    private static final String RUNTIME_STATE_USER_FOLDERS = "launcher.user_folder";
    // Type: int
    private static final String RUNTIME_STATE_PENDING_ADD_SCREEN = "launcher.add_screen";
    // Type: int
    private static final String RUNTIME_STATE_PENDING_ADD_CELL_X = "launcher.add_cellX";
    // Type: int
    private static final String RUNTIME_STATE_PENDING_ADD_CELL_Y = "launcher.add_cellY";
    // Type: int
    private static final String RUNTIME_STATE_PENDING_ADD_SPAN_X = "launcher.add_spanX";
    // Type: int
    private static final String RUNTIME_STATE_PENDING_ADD_SPAN_Y = "launcher.add_spanY";
    // Type: int
    private static final String RUNTIME_STATE_PENDING_ADD_COUNT_X = "launcher.add_countX";
    // Type: int
    private static final String RUNTIME_STATE_PENDING_ADD_COUNT_Y = "launcher.add_countY";
    // Type: int[]
    private static final String RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS = "launcher.add_occupied_cells";
    // Type: boolean
    private static final String RUNTIME_STATE_PENDING_FOLDER_RENAME = "launcher.rename_folder";
    // Type: long
    private static final String RUNTIME_STATE_PENDING_FOLDER_RENAME_ID = "launcher.rename_folder_id";

    static final int APPWIDGET_HOST_ID = 1024;

    private static final Object sLock = new Object();
    private static int sScreen = DEFAULT_SCREEN;

    private final BroadcastReceiver mCloseSystemDialogsReceiver
            = new CloseSystemDialogsIntentReceiver();
    private final ContentObserver mWidgetObserver = new AppWidgetResetObserver();
    // Typed as Object so the class still loads below API 33.
    private final Object mBackCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? new OnBackInvokedCallback() {
                public void onBackInvoked() {
                    onBackPressed();
                }
            }
            : null;

    private LayoutInflater mInflater;

    private DragController mDragController;
    private Workspace mWorkspace;

    private AppWidgetManager mAppWidgetManager;
    private LauncherAppWidgetHost mAppWidgetHost;

    private CellLayout.CellInfo mAddItemCellInfo;
    private CellLayout.CellInfo mMenuAddInfo;
    private final int[] mCellCoordinates = new int[2];
    private FolderInfo mFolderInfo;

    private DeleteZone mDeleteZone;
    private HandleView mHandleView;
    private AllAppsView mAllAppsGrid;

    private Bundle mSavedState;

    private SpannableStringBuilder mDefaultKeySsb = null;

    private boolean mWorkspaceLoading = true;

    private boolean mPaused = true;
    private boolean mRestoring;
    private boolean mWaitingForResult;
    private boolean mOnResumeNeedsLoad;

    private Bundle mSavedInstanceState;

    private LauncherModel mModel;
    private IconCache mIconCache;

    private static LocaleConfiguration sLocaleConfiguration = null;

    private ArrayList<ItemInfo> mDesktopItems = new ArrayList<ItemInfo>();
    private static HashMap<Long, FolderInfo> sFolders = new HashMap<Long, FolderInfo>();

    private ImageView mPreviousView;
    private ImageView mNextView;

    // Hotseats (quick-launch icons next to AllApps)
    private static final int NUM_HOTSEATS = 2;
    private String[] mHotseatConfig = null;
    private Intent[] mHotseats = null;
    private Drawable[] mHotseatIcons = null;
    private CharSequence[] mHotseatLabels = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (useDroidSans(this)) { // Port: Gingerbread's font for all launcher texts
            getTheme().applyStyle(R.style.DroidSansFont, true);
        }

        LauncherApplication app = ((LauncherApplication)getApplication());
        mModel = app.setLauncher(this);
        mIconCache = app.getIconCache();
        mDragController = new DragController(this);
        mInflater = getLayoutInflater();

        mAppWidgetManager = AppWidgetManager.getInstance(this);
        mAppWidgetHost = new LauncherAppWidgetHost(this, APPWIDGET_HOST_ID);
        mAppWidgetHost.startListening();

        if (PROFILE_STARTUP) {
            android.os.Debug.startMethodTracing("/sdcard/launcher");
        }

        final int oldFg = ICON_PACK_FG, oldBg = ICON_PACK_BG;
        loadTweaks(this); // Tweaks
        if (ICON_PACK != null && ICON_PACK_COLORS
                && (oldFg != ICON_PACK_FG || oldBg != ICON_PACK_BG)) {
            // Tweaks: the wallpaper's colors changed (the system recreates the launcher)
            ((LauncherApplication) getApplication()).getIconCache().flush();
            ((LauncherApplication) getApplication()).getModel().forceReload();
        }
        loadHotseats();
        checkForLocaleChange();
        setWallpaperDimension();

        {
            // Tweaks: edge-to-edge on every Android version (forced only since 15) with
            // transparent system bars. A bar that should be covered is drawn by DragLayer
            // (the window's bar colors are ignored since Android 15), so all versions match.
            final android.view.Window window = getWindow();
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }
        setContentView(R.layout.launcher);
        setupViews();
        if (FEED != FeedOverlay.PROVIDER_OFF) mFeed = new FeedOverlay(this, FEED); // Tweaks
        if (BADGES) BadgeListener.setOnChange(() -> { // Tweaks
            invalidateShortcuts(mWorkspace);
            for (Drawable d : mDockBadges) d.invalidateSelf();
        });

        registerContentObservers();

        lockAllApps();

        mSavedState = savedInstanceState;
        restoreState(mSavedState);

        if (PROFILE_STARTUP) {
            android.os.Debug.stopMethodTracing();
        }

        if (!mRestoring) {
            mModel.startLoader(this, true);
        }

        // For handling default keys
        mDefaultKeySsb = new SpannableStringBuilder();
        Selection.setSelection(mDefaultKeySsb, 0);

        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        registerReceiver(mCloseSystemDialogsReceiver, filter, RECEIVER_EXPORTED);

        // Android 13+ predictive back: KEYCODE_BACK/onBackPressed() are no longer delivered
        // when targeting API 36+, so route the back gesture to the original handler.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) mBackCallback);
        }
    }

    private void checkForLocaleChange() {
        if (sLocaleConfiguration == null) {
            new AsyncTask<Void, Void, LocaleConfiguration>() {
                @Override
                protected LocaleConfiguration doInBackground(Void... unused) {
                    LocaleConfiguration localeConfiguration = new LocaleConfiguration();
                    readConfiguration(Launcher.this, localeConfiguration);
                    return localeConfiguration;
                }

                @Override
                protected void onPostExecute(LocaleConfiguration result) {
                    sLocaleConfiguration = result;
                    checkForLocaleChange();  // recursive, but now with a locale configuration
                }
            }.execute();
            return;
        }

        final Configuration configuration = getResources().getConfiguration();

        final String previousLocale = sLocaleConfiguration.locale;
        final String locale = configuration.locale.toString();

        final int previousMcc = sLocaleConfiguration.mcc;
        final int mcc = configuration.mcc;

        final int previousMnc = sLocaleConfiguration.mnc;
        final int mnc = configuration.mnc;

        boolean localeChanged = !locale.equals(previousLocale) || mcc != previousMcc || mnc != previousMnc;

        if (localeChanged) {
            sLocaleConfiguration.locale = locale;
            sLocaleConfiguration.mcc = mcc;
            sLocaleConfiguration.mnc = mnc;

            mIconCache.flush();
            loadHotseats();

            final LocaleConfiguration localeConfiguration = sLocaleConfiguration;
            new Thread("WriteLocaleConfiguration") {
                public void run() {
                    writeConfiguration(Launcher.this, localeConfiguration);
                }
            }.start();
        }
    }

    private static class LocaleConfiguration {
        public String locale;
        public int mcc = -1;
        public int mnc = -1;
    }

    private static void readConfiguration(Context context, LocaleConfiguration configuration) {
        DataInputStream in = null;
        try {
            in = new DataInputStream(context.openFileInput(PREFERENCES));
            configuration.locale = in.readUTF();
            configuration.mcc = in.readInt();
            configuration.mnc = in.readInt();
        } catch (FileNotFoundException e) {
            // Ignore
        } catch (IOException e) {
            // Ignore
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    private static void writeConfiguration(Context context, LocaleConfiguration configuration) {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(context.openFileOutput(PREFERENCES, MODE_PRIVATE));
            out.writeUTF(configuration.locale);
            out.writeInt(configuration.mcc);
            out.writeInt(configuration.mnc);
            out.flush();
        } catch (FileNotFoundException e) {
            // Ignore
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            context.getFileStreamPath(PREFERENCES).delete();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    static int getScreen() {
        synchronized (sLock) {
            return sScreen;
        }
    }

    static void setScreen(int screen) {
        synchronized (sLock) {
            sScreen = screen;
        }
    }

    private void setWallpaperDimension() {
        WallpaperManager wpm = (WallpaperManager)getSystemService(WALLPAPER_SERVICE);

        Display display = getWindowManager().getDefaultDisplay();
        boolean isPortrait = display.getWidth() < display.getHeight();

        final int width = isPortrait ? display.getWidth() : display.getHeight();
        final int height = isPortrait ? display.getHeight() : display.getWidth();
        wpm.suggestDesiredDimensions(width * WALLPAPER_SCREENS_SPAN, height);
    }

    // Note: This doesn't do all the client-id magic that BrowserProvider does
    // in Browser. (http://b/2425179)
    private Uri getDefaultBrowserUri() {
        String url = getString(R.string.default_browser_url);
        if (url.indexOf("{CID}") != -1) {
            url = url.replace("{CID}", "android-google");
        }
        return Uri.parse(url);
    }

    // Load the Intent templates from arrays.xml to populate the hotseats. For
    // each Intent, if it resolves to a single app, use that as the launch
    // intent & use that app's label as the contentDescription. Otherwise,
    // retain the ResolveActivity so the user can pick an app.
    private void loadHotseats() {
        if (mHotseatConfig == null) {
            mHotseatConfig = getResources().getStringArray(R.array.hotseats);
            // Tweaks: optional extra dock slots after the two stock ones
            if (mHotseatConfig.length == 2) {
                mHotseatConfig = new String[] { mHotseatConfig[0], mHotseatConfig[1],
                        DOCK_INTENTS[0], DOCK_INTENTS[1], DOCK_INTENTS[2], DOCK_INTENTS[3] };
            }
            if (mHotseatConfig.length > 0) {
                mHotseats = new Intent[mHotseatConfig.length];
                mHotseatLabels = new CharSequence[mHotseatConfig.length];
                mHotseatIcons = new Drawable[mHotseatConfig.length];
            } else {
                mHotseats = null;
                mHotseatIcons = null;
                mHotseatLabels = null;
            }

            TypedArray hotseatIconDrawables = getResources().obtainTypedArray(R.array.hotseat_icons);
            for (int i=0; i<mHotseatConfig.length; i++) {
                // load icon for this slot; currently unrelated to the actual activity
                try {
                    mHotseatIcons[i] = i >= 2 // Tweaks: extra dock slots
                            ? getResources().getDrawable(DOCK_ICONS[i - 2])
                            : hotseatIconDrawables.getDrawable(i);
                } catch (ArrayIndexOutOfBoundsException ex) {
                    Log.w(TAG, "Missing hotseat_icons array item #" + i);
                    mHotseatIcons[i] = null;
                }
            }
            hotseatIconDrawables.recycle();
        }

        PackageManager pm = getPackageManager();
        for (int i=0; i<mHotseatConfig.length; i++) {
            Intent intent = null;
            if (mHotseatConfig[i].equals("*BROWSER*")) {
                // magic value meaning "launch user's default web browser"
                // replace it with a generic web request so we can see if there is indeed a default
                String defaultUri = getString(R.string.default_browser_url);
                intent = new Intent(
                        Intent.ACTION_VIEW,
                        ((defaultUri != null)
                            ? Uri.parse(defaultUri)
                            : getDefaultBrowserUri())
                    ).addCategory(Intent.CATEGORY_BROWSABLE);
                // note: if the user launches this without a default set, she
                // will always be taken to the default URL above; this is
                // unavoidable as we must specify a valid URL in order for the
                // chooser to appear, and once the user selects something, that 
                // URL is unavoidably sent to the chosen app.
            } else {
                try {
                    intent = Intent.parseUri(mHotseatConfig[i], 0);
                } catch (java.net.URISyntaxException ex) {
                    Log.w(TAG, "Invalid hotseat intent: " + mHotseatConfig[i]);
                    // bogus; leave intent=null
                }
            }
            
            if (intent == null) {
                mHotseats[i] = null;
                mHotseatLabels[i] = getText(R.string.activity_not_found);
                continue;
            }

            if (LOGD) {
                Log.d(TAG, "loadHotseats: hotseat " + i 
                    + " initial intent=[" 
                    + intent.toUri(Intent.URI_INTENT_SCHEME)
                    + "]");
            }

            ResolveInfo bestMatch = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            List<ResolveInfo> allMatches = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (LOGD) { 
                Log.d(TAG, "Best match for intent: " + bestMatch);
                Log.d(TAG, "All matches: ");
                for (ResolveInfo ri : allMatches) {
                    Log.d(TAG, "  --> " + ri);
                }
            }
            // did this resolve to a single app, or the resolver?
            if (allMatches.size() == 0 || bestMatch == null) {
                // can't find any activity to handle this. let's leave the 
                // intent as-is and let Launcher show a toast when it fails 
                // to launch.
                mHotseats[i] = intent;

                // set accessibility text to "Not installed"
                mHotseatLabels[i] = getText(R.string.activity_not_found);
            } else {
                boolean found = false;
                for (ResolveInfo ri : allMatches) {
                    if (bestMatch.activityInfo.name.equals(ri.activityInfo.name)
                        && bestMatch.activityInfo.applicationInfo.packageName
                            .equals(ri.activityInfo.applicationInfo.packageName)) {
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    if (LOGD) Log.d(TAG, "Multiple options, no default yet");
                    // the bestMatch is probably the ResolveActivity, meaning the
                    // user has not yet selected a default
                    // so: we'll keep the original intent for now
                    mHotseats[i] = intent;

                    // set the accessibility text to "Select shortcut"
                    mHotseatLabels[i] = getText(R.string.title_select_shortcut);
                } else {
                    // we have an app!
                    // now reconstruct the intent to launch it through the front
                    // door
                    ComponentName com = new ComponentName(
                        bestMatch.activityInfo.applicationInfo.packageName,
                        bestMatch.activityInfo.name);
                    mHotseats[i] = i == 5
                            // Tweaks: the camera slot keeps its action (the activity that
                            // takes STILL_IMAGE_CAMERA closes again on a plain MAIN intent)
                            ? new Intent(intent).setComponent(com)
                            : new Intent(Intent.ACTION_MAIN).setComponent(com);

                    // load the app label for accessibility
                    mHotseatLabels[i] = bestMatch.activityInfo.loadLabel(pm);
                }
            }

            if (LOGD) {
                Log.d(TAG, "loadHotseats: hotseat " + i 
                    + " final intent=[" 
                    + ((mHotseats[i] == null)
                        ? "null"
                        : mHotseats[i].toUri(Intent.URI_INTENT_SCHEME))
                    + "] label=[" + mHotseatLabels[i]
                    + "]"
                    );
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mWaitingForResult = false;

        if (requestCode == REQUEST_BIND_SEARCH_WIDGET) {
            completeBindSearchWidget(resultCode == RESULT_OK);
            return;
        }

        if (requestCode == REQUEST_BIND_APPWIDGET) { // Tweaks: own widget picker
            final int appWidgetId = mPendingWidgetId;
            mPendingWidgetId = -1;
            if (appWidgetId == -1) return;
            if (resultCode == RESULT_OK && mAddItemCellInfo != null) {
                addAppWidget(new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                        appWidgetId));
            } else {
                mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            }
            return;
        }

        // The pattern used here is that a user PICKs a specific application,
        // which, depending on the target, might need to CREATE the actual target.

        // For example, the user would PICK_SHORTCUT for "Music playlist", and we
        // launch over to the Music app to actually CREATE_SHORTCUT.

        if (resultCode == RESULT_OK && mAddItemCellInfo != null) {
            switch (requestCode) {
                case REQUEST_PICK_APPLICATION:
                    completeAddApplication(this, data, mAddItemCellInfo);
                    break;
                case REQUEST_PICK_SHORTCUT:
                    processShortcut(data);
                    break;
                case REQUEST_CREATE_SHORTCUT:
                    completeAddShortcut(data, mAddItemCellInfo);
                    break;
                case REQUEST_PICK_LIVE_FOLDER:
                    addLiveFolder(data);
                    break;
                case REQUEST_CREATE_LIVE_FOLDER:
                    completeAddLiveFolder(data, mAddItemCellInfo);
                    break;
                case REQUEST_PICK_APPWIDGET:
                    addAppWidget(data);
                    break;
                case REQUEST_CREATE_APPWIDGET:
                    completeAddAppWidget(data, mAddItemCellInfo);
                    break;
                case REQUEST_PICK_WALLPAPER:
                    // We just wanted the activity result here so we can clear mWaitingForResult
                    break;
            }
        } else if ((requestCode == REQUEST_PICK_APPWIDGET ||
                requestCode == REQUEST_CREATE_APPWIDGET) && resultCode == RESULT_CANCELED &&
                data != null) {
            // Clean up the appWidgetId if we canceled
            int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (appWidgetId != -1) {
                mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPaused = false;
        if (mFeed != null) mFeed.onResume(); // Tweaks
        com.google.android.apps.genie.geniewidget.NewsWidgetProvider.refreshIfDue(this); // Tweaks
        if (Settings.sChanged) { // Tweaks: settings were changed
            Settings.sChanged = false;
            if (Settings.sIconsChanged) { // icon shape: create all icons again
                Settings.sIconsChanged = false;
                loadTweaks(this);
                ((LauncherApplication) getApplication()).getIconCache().flush();
                mModel.forceReload();
            }
            applyTweaks();
            return;
        }
        if (mAllAppsGrid instanceof android.opengl.GLSurfaceView) {
            ((android.opengl.GLSurfaceView) mAllAppsGrid).onResume();
        }
        if (mRestoring || mOnResumeNeedsLoad) {
            mWorkspaceLoading = true;
            mModel.startLoader(this, true);
            mRestoring = false;
            mOnResumeNeedsLoad = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPaused = true;
        if (mFeed != null) mFeed.onPause(); // Tweaks
        getDragLayer().clearAllResizeFrames(); // Tweaks
        if (mAllAppsGrid instanceof android.opengl.GLSurfaceView) {
            ((android.opengl.GLSurfaceView) mAllAppsGrid).onPause();
        }
        dismissPreview(mPreviousView);
        dismissPreview(mNextView);
        mDragController.cancelDrag();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        // Flag the loader to stop early before switching
        mModel.stopLoader();
        mAllAppsGrid.surrender();
        return Boolean.TRUE;
    }

    // We can't hide the IME if it was forced open.  So don't bother
    /*
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            final InputMethodManager inputManager = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            inputManager.hideSoftInputFromWindow(lp.token, 0, new android.os.ResultReceiver(new
                        android.os.Handler()) {
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            Log.d(TAG, "ResultReceiver got resultCode=" + resultCode);
                        }
                    });
            Log.d(TAG, "called hideSoftInputFromWindow from onWindowFocusChanged");
        }
    }
    */

    private boolean acceptFilter() {
        final InputMethodManager inputManager = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        return !inputManager.isFullscreenMode();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean handled = super.onKeyDown(keyCode, event);
        if (!handled && acceptFilter() && keyCode != KeyEvent.KEYCODE_ENTER) {
            boolean gotKey = TextKeyListener.getInstance().onKeyDown(mWorkspace, mDefaultKeySsb,
                    keyCode, event);
            if (gotKey && mDefaultKeySsb != null && mDefaultKeySsb.length() > 0) {
                // something usable has been typed - start a search
                // the typed text will be retrieved and cleared by
                // showSearchDialog()
                // If there are multiple keystrokes before the search dialog takes focus,
                // onSearchRequested() will be called for every keystroke,
                // but it is idempotent, so it's fine.
                return onSearchRequested();
            }
        }

        // Eat the long press event so the keyboard doesn't come up.
        if (keyCode == KeyEvent.KEYCODE_MENU && event.isLongPress()) {
            return true;
        }

        return handled;
    }

    private String getTypedText() {
        return mDefaultKeySsb.toString();
    }

    private void clearTypedText() {
        mDefaultKeySsb.clear();
        mDefaultKeySsb.clearSpans();
        Selection.setSelection(mDefaultKeySsb, 0);
    }

    /**
     * Restores the previous state, if it exists.
     *
     * @param savedState The previous state.
     */
    private void restoreState(Bundle savedState) {
        if (savedState == null) {
            return;
        }

        final boolean allApps = savedState.getBoolean(RUNTIME_STATE_ALL_APPS_FOLDER, false);
        if (allApps) {
            showAllApps(false);
        }

        final int currentScreen = savedState.getInt(RUNTIME_STATE_CURRENT_SCREEN, -1);
        if (currentScreen > -1) {
            mWorkspace.setCurrentScreen(currentScreen);
        }

        final int addScreen = savedState.getInt(RUNTIME_STATE_PENDING_ADD_SCREEN, -1);
        if (addScreen > -1) {
            mAddItemCellInfo = new CellLayout.CellInfo();
            final CellLayout.CellInfo addItemCellInfo = mAddItemCellInfo;
            addItemCellInfo.valid = true;
            addItemCellInfo.screen = addScreen;
            addItemCellInfo.cellX = savedState.getInt(RUNTIME_STATE_PENDING_ADD_CELL_X);
            addItemCellInfo.cellY = savedState.getInt(RUNTIME_STATE_PENDING_ADD_CELL_Y);
            addItemCellInfo.spanX = savedState.getInt(RUNTIME_STATE_PENDING_ADD_SPAN_X);
            addItemCellInfo.spanY = savedState.getInt(RUNTIME_STATE_PENDING_ADD_SPAN_Y);
            addItemCellInfo.findVacantCellsFromOccupied(
                    savedState.getBooleanArray(RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS),
                    savedState.getInt(RUNTIME_STATE_PENDING_ADD_COUNT_X),
                    savedState.getInt(RUNTIME_STATE_PENDING_ADD_COUNT_Y));
            mRestoring = true;
        }

        boolean renameFolder = savedState.getBoolean(RUNTIME_STATE_PENDING_FOLDER_RENAME, false);
        if (renameFolder) {
            long id = savedState.getLong(RUNTIME_STATE_PENDING_FOLDER_RENAME_ID);
            mFolderInfo = mModel.getFolderById(this, sFolders, id);
            mRestoring = true;
        }
    }

    /**
     * Finds all the views we need and configure them properly.
     */
    private void setupViews() {
        DragController dragController = mDragController;

        DragLayer dragLayer = (DragLayer) findViewById(R.id.drag_layer);
        dragLayer.setDragController(dragController);

        if (DRAWER_2D) { // Tweaks: the 2D drawer in place of the 3D one (all_apps.xml)
            final View allApps3D = dragLayer.findViewById(R.id.all_apps_view);
            final ViewGroup parent = (ViewGroup) allApps3D.getParent();
            final int index = parent.indexOfChild(allApps3D);
            parent.removeViewAt(index);
            parent.addView(getLayoutInflater().inflate(R.layout.all_apps_2d, parent, false),
                    index);
            // The DragLayer's padding are the system bars (onApplyWindowInsets()); the 2D
            // drawer is laid out over the whole screen like the 3D one, but as a normal view
            // it was clipped to that padding (the 3D drawer is a SurfaceView): its black
            // background has to reach under the bars too
            dragLayer.setClipToPadding(false);
        }
        mAllAppsGrid = (AllAppsView)dragLayer.findViewById(R.id.all_apps_view);
        mAllAppsGrid.setLauncher(this);
        mAllAppsGrid.setDragController(dragController);
        ((View) mAllAppsGrid).setWillNotDraw(false); // We don't want a hole punched in our window.
        // Manage focusability manually since this thing is always visible
        ((View) mAllAppsGrid).setFocusable(false); 

        mWorkspace = (Workspace) dragLayer.findViewById(R.id.workspace);
        final Workspace workspace = mWorkspace;
        // Tweaks: drop the screens beyond the configured count
        while (workspace.getChildCount() > SCREEN_COUNT) {
            workspace.removeViewAt(workspace.getChildCount() - 1);
        }
        workspace.setHapticFeedbackEnabled(false);

        DeleteZone deleteZone = (DeleteZone) dragLayer.findViewById(R.id.delete_zone);
        mDeleteZone = deleteZone;

        mHandleView = (HandleView) findViewById(R.id.all_apps_button);
        mHandleView.setLauncher(this);
        mHandleView.setOnClickListener(this);
        mHandleView.setOnLongClickListener(this);

        ImageView hotseatLeft = (ImageView) findViewById(R.id.hotseat_left);
        hotseatLeft.setContentDescription(mHotseatLabels[0]);
        hotseatLeft.setImageDrawable(mHotseatIcons[0]);
        ImageView hotseatRight = (ImageView) findViewById(R.id.hotseat_right);
        hotseatRight.setContentDescription(mHotseatLabels[1]);
        hotseatRight.setImageDrawable(mHotseatIcons[1]);
        setupDock(hotseatLeft, hotseatRight); // Tweaks
        if (BADGES) addDockBadges(hotseatLeft, hotseatRight); // Tweaks

        mPreviousView = (ImageView) dragLayer.findViewById(R.id.previous_screen);
        mNextView = (ImageView) dragLayer.findViewById(R.id.next_screen);

        Drawable previous = mPreviousView.getDrawable();
        Drawable next = mNextView.getDrawable();
        mWorkspace.setIndicators(previous, next);

        mPreviousView.setHapticFeedbackEnabled(false);
        mPreviousView.setOnLongClickListener(this);
        mNextView.setHapticFeedbackEnabled(false);
        mNextView.setOnLongClickListener(this);

        workspace.setOnLongClickListener(this);
        workspace.setDragController(dragController);
        workspace.setLauncher(this);

        deleteZone.setLauncher(this);
        deleteZone.setDragController(dragController);
        deleteZone.setHandle(findViewById(R.id.all_apps_button_cluster));

        dragController.setDragScoller(workspace);
        dragController.setDragListener(deleteZone);
        dragController.setScrollView(dragLayer);
        dragController.setMoveTarget(workspace);

        // The order here is bottom to top.
        dragController.addDropTarget(workspace);
        dragController.addDropTarget(deleteZone);

        updateBackGesture(); // Port
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void previousScreen(View v) {
        if (!isAllAppsVisible()) {
            mWorkspace.scrollLeft();
        }
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void nextScreen(View v) {
        if (!isAllAppsVisible()) {
            mWorkspace.scrollRight();
        }
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void launchHotSeat(View v) {
        if (isAllAppsVisible()) return;

        int index = -1;
        if (v.getTag() instanceof Integer) { // Tweaks: extra dock slot
            index = (Integer) v.getTag();
        } else if (v.getId() == R.id.hotseat_left) {
            index = 0;
        } else if (v.getId() == R.id.hotseat_right) {
            index = 1;
        }

        // reload these every tap; you never know when they might change
        loadHotseats();
        if (index >= 0 && index < mHotseats.length && mHotseats[index] != null) {
            Intent intent = mHotseats[index];
            startActivitySafely(
                mHotseats[index],
                "hotseat"
            );
        }
    }
    
    /**
     * Creates a view representing a shortcut.
     *
     * @param info The data structure describing the shortcut.
     *
     * @return A View inflated from R.layout.application.
     */
    View createShortcut(ShortcutInfo info) {
        return createShortcut(R.layout.application,
                (ViewGroup) mWorkspace.getChildAt(mWorkspace.getCurrentScreen()), info);
    }

    /**
     * Creates a view representing a shortcut inflated from the specified resource.
     *
     * @param layoutResId The id of the XML layout used to create the shortcut.
     * @param parent The group the shortcut belongs to.
     * @param info The data structure describing the shortcut.
     *
     * @return A View inflated from layoutResId.
     */
    View createShortcut(int layoutResId, ViewGroup parent, ShortcutInfo info) {
        TextView favorite = (TextView) mInflater.inflate(layoutResId, parent, false);

        favorite.setCompoundDrawablesWithIntrinsicBounds(null,
                new FastBitmapDrawable(info.getIcon(mIconCache)),
                null, null);
        favorite.setText(info.title);
        favorite.setTag(info);
        favorite.setOnClickListener(this);

        return favorite;
    }

    /**
     * Add an application shortcut to the workspace.
     *
     * @param data The intent describing the application.
     * @param cellInfo The position on screen where to create the shortcut.
     */
    void completeAddApplication(Context context, Intent data, CellLayout.CellInfo cellInfo) {
        cellInfo.screen = mWorkspace.getCurrentScreen();
        if (!findSingleSlot(cellInfo)) return;

        final ShortcutInfo info = mModel.getShortcutInfo(context.getPackageManager(),
                data, context);

        if (info != null) {
            info.setActivity(data.getComponent(), Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            info.container = ItemInfo.NO_ID;
            mWorkspace.addApplicationShortcut(info, cellInfo, isWorkspaceLocked());
        } else {
            Log.e(TAG, "Couldn't find ActivityInfo for selected application: " + data);
        }
    }

    /**
     * Add a shortcut to the workspace.
     *
     * @param data The intent describing the shortcut.
     * @param cellInfo The position on screen where to create the shortcut.
     */
    private void completeAddShortcut(Intent data, CellLayout.CellInfo cellInfo) {
        cellInfo.screen = mWorkspace.getCurrentScreen();
        if (!findSingleSlot(cellInfo)) return;

        // Android 8.0+: shortcut config activities return a pin request instead of the
        // legacy EXTRA_SHORTCUT_* extras when the home app supports pinning.
        final Intent pinned = PinShortcutActivity.acceptPinRequest(this, data);
        if (pinned != null) {
            data = pinned;
        }

        final ShortcutInfo info = mModel.addShortcut(this, data, cellInfo, false);

        if (!mRestoring) {
            final View view = createShortcut(info);
            mWorkspace.addInCurrentScreen(view, cellInfo.cellX, cellInfo.cellY, 1, 1,
                    isWorkspaceLocked());
        }
    }


    /**
     * Add a widget to the workspace.
     *
     * @param data The intent describing the appWidgetId.
     * @param cellInfo The position on screen where to create the widget.
     */
    private void completeAddAppWidget(Intent data, CellLayout.CellInfo cellInfo) {
        Bundle extras = data.getExtras();
        int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);

        if (LOGD) Log.d(TAG, "dumping extras content=" + extras.toString());

        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);

        // Calculate the grid spans needed to fit this widget
        CellLayout layout = (CellLayout) mWorkspace.getChildAt(cellInfo.screen);
        int[] spans = layout.rectToCell(appWidgetInfo.minWidth, appWidgetInfo.minHeight);

        // Try finding open space on Launcher screen
        final int[] xy = mCellCoordinates;
        if (!findSlot(cellInfo, xy, spans[0], spans[1])) {
            if (appWidgetId != -1) mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            return;
        }

        // Build Launcher-specific widget info and save to database
        LauncherAppWidgetInfo launcherInfo = new LauncherAppWidgetInfo(appWidgetId);
        launcherInfo.spanX = spans[0];
        launcherInfo.spanY = spans[1];

        LauncherModel.addItemToDatabase(this, launcherInfo,
                LauncherSettings.Favorites.CONTAINER_DESKTOP,
                mWorkspace.getCurrentScreen(), xy[0], xy[1], false);

        if (!mRestoring) {
            mDesktopItems.add(launcherInfo);

            // Perform actual inflation because we're live
            launcherInfo.hostView = mAppWidgetHost.createView(this, appWidgetId, appWidgetInfo);

            launcherInfo.hostView.setAppWidget(appWidgetId, appWidgetInfo);
            launcherInfo.hostView.setTag(launcherInfo);

            mWorkspace.addInCurrentScreen(launcherInfo.hostView, xy[0], xy[1],
                    launcherInfo.spanX, launcherInfo.spanY, isWorkspaceLocked());
        }

        // Tweaks: the weather, news and YouTube widgets are set up in the launcher settings
        final String provider = appWidgetInfo.provider.getClassName();
        if (getPackageName().equals(appWidgetInfo.provider.getPackageName())
                && ("com.google.android.apps.genie.geniewidget.WeatherWidgetProvider".equals(provider)
                || "com.google.android.apps.genie.geniewidget.NewsWidgetProvider".equals(provider)
                || "com.google.android.youtube.widget.WidgetProvider".equals(provider))) {
            startActivity(Settings.widgetsIntent(this));
        }
    }

    public void removeAppWidget(LauncherAppWidgetInfo launcherInfo) {
        mDesktopItems.remove(launcherInfo);
        launcherInfo.hostView = null;
    }

    public LauncherAppWidgetHost getAppWidgetHost() {
        return mAppWidgetHost;
    }

    void closeSystemDialogs() {
        getWindow().closeAllPanels();
        dismissPicker();

        try {
            dismissDialog(DIALOG_CREATE_SHORTCUT);
            // Unlock the workspace if the dialog was showing
        } catch (Exception e) {
            // An exception is thrown if the dialog is not visible, which is fine
        }

        try {
            dismissDialog(DIALOG_RENAME_FOLDER);
            // Unlock the workspace if the dialog was showing
        } catch (Exception e) {
            // An exception is thrown if the dialog is not visible, which is fine
        }

        // Whatever we were doing is hereby canceled.
        mWaitingForResult = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Close the menu
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            // also will cancel mWaitingForResult.
            closeSystemDialogs();

            boolean alreadyOnHome = ((intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                        != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            boolean allAppsVisible = isAllAppsVisible();
            if (mFeed != null && mFeed.isShowing()) { // Tweaks: home closes the feed
                mFeed.hide(alreadyOnHome);
                if (alreadyOnHome) return;
            }
            if (!mWorkspace.isDefaultScreenShowing()) {
                mWorkspace.moveToDefaultScreen(alreadyOnHome && !allAppsVisible);
            }
            closeAllApps(alreadyOnHome && allAppsVisible);

            final View v = getWindow().peekDecorView();
            if (v != null && v.getWindowToken() != null) {
                InputMethodManager imm = (InputMethodManager)getSystemService(
                        INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        // Do not call super here
        mSavedInstanceState = savedInstanceState;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(RUNTIME_STATE_CURRENT_SCREEN, mWorkspace.getCurrentScreen());

        final ArrayList<Folder> folders = mWorkspace.getOpenFolders();
        if (folders.size() > 0) {
            final int count = folders.size();
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) {
                final FolderInfo info = folders.get(i).getInfo();
                ids[i] = info.id;
            }
            outState.putLongArray(RUNTIME_STATE_USER_FOLDERS, ids);
        } else {
            super.onSaveInstanceState(outState);
        }

        // TODO should not do this if the drawer is currently closing.
        if (isAllAppsVisible()) {
            outState.putBoolean(RUNTIME_STATE_ALL_APPS_FOLDER, true);
        }

        if (mAddItemCellInfo != null && mAddItemCellInfo.valid && mWaitingForResult) {
            final CellLayout.CellInfo addItemCellInfo = mAddItemCellInfo;
            final CellLayout layout = (CellLayout) mWorkspace.getChildAt(addItemCellInfo.screen);

            outState.putInt(RUNTIME_STATE_PENDING_ADD_SCREEN, addItemCellInfo.screen);
            outState.putInt(RUNTIME_STATE_PENDING_ADD_CELL_X, addItemCellInfo.cellX);
            outState.putInt(RUNTIME_STATE_PENDING_ADD_CELL_Y, addItemCellInfo.cellY);
            outState.putInt(RUNTIME_STATE_PENDING_ADD_SPAN_X, addItemCellInfo.spanX);
            outState.putInt(RUNTIME_STATE_PENDING_ADD_SPAN_Y, addItemCellInfo.spanY);
            outState.putInt(RUNTIME_STATE_PENDING_ADD_COUNT_X, layout.getCountX());
            outState.putInt(RUNTIME_STATE_PENDING_ADD_COUNT_Y, layout.getCountY());
            outState.putBooleanArray(RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS,
                   layout.getOccupiedCells());
        }

        if (mFolderInfo != null && mWaitingForResult) {
            outState.putBoolean(RUNTIME_STATE_PENDING_FOLDER_RENAME, true);
            outState.putLong(RUNTIME_STATE_PENDING_FOLDER_RENAME_ID, mFolderInfo.id);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dismissPicker(); // Tweaks
        if (mFeed != null) mFeed.onDestroy(); // Tweaks

        try {
            mAppWidgetHost.stopListening();
        } catch (NullPointerException ex) {
            Log.w(TAG, "problem while stopping AppWidgetHost during Launcher destruction", ex);
        }

        TextKeyListener.getInstance().release();

        mModel.stopLoader();

        unbindDesktopItems();

        getContentResolver().unregisterContentObserver(mWidgetObserver);
        
        dismissPreview(mPreviousView);
        dismissPreview(mNextView);

        unregisterReceiver(mCloseSystemDialogsReceiver);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) mBackCallback);
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        if (requestCode >= 0) mWaitingForResult = true;
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    public void startSearch(String initialQuery, boolean selectInitialQuery,
            Bundle appSearchData, boolean globalSearch) {

        closeAllApps(true);

        if (initialQuery == null) {
            // Use any text typed in the launcher as the initial query
            initialQuery = getTypedText();
            clearTypedText();
        }
        if (appSearchData == null) {
            appSearchData = new Bundle();
            appSearchData.putString("source" /* com.android.common.Search.SOURCE */, "launcher-search");
        }

        final SearchManager searchManager =
                (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        searchManager.startSearch(initialQuery, selectInitialQuery, getComponentName(),
            appSearchData, globalSearch);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (isWorkspaceLocked()) {
            return false;
        }

        super.onCreateOptionsMenu(menu);

        menu.add(MENU_GROUP_ADD, MENU_ADD, 0, R.string.menu_add)
                .setIcon(android.R.drawable.ic_menu_add)
                .setAlphabeticShortcut('A');
        menu.add(0, MENU_MANAGE_APPS, 0, R.string.menu_manage_apps)
                .setIcon(android.R.drawable.ic_menu_manage)
                .setAlphabeticShortcut('M');
        menu.add(MENU_GROUP_WALLPAPER, MENU_WALLPAPER_SETTINGS, 0, R.string.menu_wallpaper)
                 .setIcon(android.R.drawable.ic_menu_gallery)
                 .setAlphabeticShortcut('W');
        menu.add(0, MENU_SEARCH, 0, R.string.menu_search)
                .setIcon(android.R.drawable.ic_search_category_default)
                .setAlphabeticShortcut(SearchManager.MENU_KEY);
        menu.add(0, MENU_NOTIFICATIONS, 0, R.string.menu_notifications)
                .setIcon(getResources().getIdentifier("ic_menu_notifications", "drawable", "android"))
                .setAlphabeticShortcut('N');

        final Intent settings = new Intent(android.provider.Settings.ACTION_SETTINGS);
        settings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        menu.add(0, MENU_SETTINGS, 0, R.string.menu_settings)
                .setIcon(android.R.drawable.ic_menu_preferences).setAlphabeticShortcut('P')
                .setIntent(settings);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        // If all apps is animating, don't show the menu, because we don't know
        // which one to show.
        if (mAllAppsGrid.isVisible() && !mAllAppsGrid.isOpaque()) {
            return false;
        }

        // Only show the add and wallpaper options when we're not in all apps.
        boolean visible = !mAllAppsGrid.isOpaque();
        menu.setGroupVisible(MENU_GROUP_ADD, visible);
        menu.setGroupVisible(MENU_GROUP_WALLPAPER, visible);

        // Disable add if the workspace is full.
        if (visible) {
            mMenuAddInfo = mWorkspace.findAllVacantCells(null);
            menu.setGroupEnabled(MENU_GROUP_ADD, mMenuAddInfo != null && mMenuAddInfo.valid);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD:
                addItems();
                return true;
            case MENU_MANAGE_APPS:
                manageApps();
                return true;
            case MENU_WALLPAPER_SETTINGS:
                startWallpaper();
                return true;
            case MENU_SEARCH:
                onSearchRequested();
                return true;
            case MENU_NOTIFICATIONS:
                showNotifications();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Indicates that we want global search for this activity by setting the globalSearch
     * argument for {@link #startSearch} to true.
     */

    @Override
    public boolean onSearchRequested() {
        startSearch(null, false, null, true);
        return true;
    }

    public boolean isWorkspaceLocked() {
        return mWorkspaceLoading || mWaitingForResult;
    }

    private void addItems() {
        closeAllApps(true);
        showAddDialog(mMenuAddInfo);
    }

    private void manageApps() {
        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS));
    }

    void addAppWidget(Intent data) {
        // TODO: catch bad widget exception when sent
        int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        AppWidgetProviderInfo appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId);

        if (appWidget == null) { // Tweaks: the widget couldn't be bound
            if (appWidgetId != -1) mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        if (appWidget.configure != null) {
            // Launch over to configure widget, if needed
            // Android 5.0+: configure activities aren't exported, the host has to start them.
            try {
                mWaitingForResult = true;
                mAppWidgetHost.startAppWidgetConfigureActivityForResult(this, appWidgetId, 0,
                        REQUEST_CREATE_APPWIDGET, null);
            } catch (ActivityNotFoundException e) {
                mWaitingForResult = false;
                mAppWidgetHost.deleteAppWidgetId(appWidgetId);
                Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            }
        } else {
            // Otherwise just add it
            onActivityResult(REQUEST_CREATE_APPWIDGET, Activity.RESULT_OK, data);
        }
    }

    void processShortcut(Intent intent) {
        // Handle case where user selected "Applications"
        String applicationName = getResources().getString(R.string.group_applications);
        String shortcutName = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);

        if (applicationName != null && applicationName.equals(shortcutName)) {
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            Intent pickIntent = new Intent(Intent.ACTION_PICK_ACTIVITY);
            pickIntent.putExtra(Intent.EXTRA_INTENT, mainIntent);
            pickIntent.putExtra(Intent.EXTRA_TITLE, getText(R.string.title_select_application));
            startActivityForResultSafely(pickIntent, REQUEST_PICK_APPLICATION);
        } else {
            startActivityForResultSafely(intent, REQUEST_CREATE_SHORTCUT);
        }
    }

    void addLiveFolder(Intent intent) {
        // Handle case where user selected "Folder"
        String folderName = getResources().getString(R.string.group_folder);
        String shortcutName = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);

        if (folderName != null && folderName.equals(shortcutName)) {
            addFolder();
        } else {
            startActivityForResultSafely(intent, REQUEST_CREATE_LIVE_FOLDER);
        }
    }

    void addFolder() {
        UserFolderInfo folderInfo = new UserFolderInfo();
        folderInfo.title = getText(R.string.folder_name);

        CellLayout.CellInfo cellInfo = mAddItemCellInfo;
        cellInfo.screen = mWorkspace.getCurrentScreen();
        if (!findSingleSlot(cellInfo)) return;

        // Update the model
        LauncherModel.addItemToDatabase(this, folderInfo,
                LauncherSettings.Favorites.CONTAINER_DESKTOP,
                mWorkspace.getCurrentScreen(), cellInfo.cellX, cellInfo.cellY, false);
        sFolders.put(folderInfo.id, folderInfo);

        // Create the view
        FolderIcon newFolder = FolderIcon.fromXml(R.layout.folder_icon, this,
                (ViewGroup) mWorkspace.getChildAt(mWorkspace.getCurrentScreen()), folderInfo);
        mWorkspace.addInCurrentScreen(newFolder,
                cellInfo.cellX, cellInfo.cellY, 1, 1, isWorkspaceLocked());
    }

    void removeFolder(FolderInfo folder) {
        sFolders.remove(folder.id);
    }

    private void completeAddLiveFolder(Intent data, CellLayout.CellInfo cellInfo) {
        cellInfo.screen = mWorkspace.getCurrentScreen();
        if (!findSingleSlot(cellInfo)) return;

        final LiveFolderInfo info = addLiveFolder(this, data, cellInfo, false);

        if (!mRestoring) {
            final View view = LiveFolderIcon.fromXml(R.layout.live_folder_icon, this,
                (ViewGroup) mWorkspace.getChildAt(mWorkspace.getCurrentScreen()), info);
            mWorkspace.addInCurrentScreen(view, cellInfo.cellX, cellInfo.cellY, 1, 1,
                    isWorkspaceLocked());
        }
    }

    static LiveFolderInfo addLiveFolder(Context context, Intent data,
            CellLayout.CellInfo cellInfo, boolean notify) {

        Intent baseIntent = data.getParcelableExtra(LiveFolders.EXTRA_LIVE_FOLDER_BASE_INTENT);
        String name = data.getStringExtra(LiveFolders.EXTRA_LIVE_FOLDER_NAME);

        Drawable icon = null;
        Intent.ShortcutIconResource iconResource = null;

        Parcelable extra = data.getParcelableExtra(LiveFolders.EXTRA_LIVE_FOLDER_ICON);
        if (extra != null && extra instanceof Intent.ShortcutIconResource) {
            try {
                iconResource = (Intent.ShortcutIconResource) extra;
                final PackageManager packageManager = context.getPackageManager();
                Resources resources = packageManager.getResourcesForApplication(
                        iconResource.packageName);
                final int id = resources.getIdentifier(iconResource.resourceName, null, null);
                icon = resources.getDrawable(id);
            } catch (Exception e) {
                Log.w(TAG, "Could not load live folder icon: " + extra);
            }
        }

        if (icon == null) {
            icon = context.getResources().getDrawable(R.drawable.ic_launcher_folder);
        }

        final LiveFolderInfo info = new LiveFolderInfo();
        info.icon = Utilities.createIconBitmap(icon, context);
        info.title = name;
        info.iconResource = iconResource;
        info.uri = data.getData();
        info.baseIntent = baseIntent;
        info.displayMode = data.getIntExtra(LiveFolders.EXTRA_LIVE_FOLDER_DISPLAY_MODE,
                LiveFolders.DISPLAY_MODE_GRID);

        LauncherModel.addItemToDatabase(context, info, LauncherSettings.Favorites.CONTAINER_DESKTOP,
                cellInfo.screen, cellInfo.cellX, cellInfo.cellY, notify);
        sFolders.put(info.id, info);

        return info;
    }

    private boolean findSingleSlot(CellLayout.CellInfo cellInfo) {
        final int[] xy = new int[2];
        if (findSlot(cellInfo, xy, 1, 1)) {
            cellInfo.cellX = xy[0];
            cellInfo.cellY = xy[1];
            return true;
        }
        return false;
    }

    private boolean findSlot(CellLayout.CellInfo cellInfo, int[] xy, int spanX, int spanY) {
        if (!cellInfo.findCellForSpan(xy, spanX, spanY)) {
            boolean[] occupied = mSavedState != null ?
                    mSavedState.getBooleanArray(RUNTIME_STATE_PENDING_ADD_OCCUPIED_CELLS) : null;
            cellInfo = mWorkspace.findAllVacantCells(occupied);
            if (!cellInfo.findCellForSpan(xy, spanX, spanY)) {
                Toast.makeText(this, getString(R.string.out_of_space), Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return true;
    }

    void showNotifications() { // Tweaks: was private (swipe down on the workspace)
        // StatusBarManager is hidden API; expand() became expandNotificationsPanel() in API 17.
        final Object statusBar = getSystemService("statusbar");
        if (statusBar != null) {
            try {
                statusBar.getClass().getMethod("expandNotificationsPanel").invoke(statusBar);
            } catch (Exception e) {
                Log.w(TAG, "Unable to expand the notification panel", e);
            }
        }
    }

    private void startWallpaper() {
        closeAllApps(true);
        showWallpaperPicker();
    }

    /**
     * Registers various content observers. The current implementation registers
     * only a favorites observer to keep track of the favorites applications.
     */
    private void registerContentObservers() {
        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(LauncherProvider.CONTENT_APPWIDGET_RESET_URI,
                true, mWidgetObserver);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_HOME:
                    return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_HOME:
                    return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (isAllAppsVisible()) {
            closeAllApps(true);
        } else {
            closeFolder();
        }
        dismissPreview(mPreviousView);
        dismissPreview(mNextView);
    }

    private void closeFolder() {
        Folder folder = mWorkspace.getOpenFolder();
        if (folder != null) {
            closeFolder(folder);
        }
    }

    /** Port: back gesture only where back does something (see DragLayer). */
    private boolean mAllAppsShown;

    void updateBackGesture() {
        final DragLayer dragLayer = (DragLayer) findViewById(R.id.drag_layer);
        if (dragLayer != null) {
            dragLayer.setDisallowBackGesture(!mAllAppsShown
                    && (mWorkspace == null || mWorkspace.getOpenFolder() == null));
        }
    }

    void closeFolder(Folder folder) {
        folder.getInfo().opened = false;
        ViewGroup parent = (ViewGroup) folder.getParent();
        if (parent != null) {
            parent.removeView(folder);
            if (folder instanceof DropTarget) {
                // Live folders aren't DropTargets.
                mDragController.removeDropTarget((DropTarget)folder);
            }
        }
        folder.onClose();
        updateBackGesture(); // Port
    }

    /**
     * Re-listen when widgets are reset.
     */
    private void onAppWidgetReset() {
        mAppWidgetHost.startListening();
    }

    /**
     * Go through the and disconnect any of the callbacks in the drawables and the views or we
     * leak the previous Home screen on orientation change.
     */
    private void unbindDesktopItems() {
        for (ItemInfo item: mDesktopItems) {
            item.unbind();
        }
    }

    /**
     * Launches the intent referred by the clicked shortcut.
     *
     * @param v The view representing the clicked shortcut.
     */
    public void onClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof ShortcutInfo) {
            // Open shortcut
            final Intent intent = ((ShortcutInfo) tag).intent;
            int[] pos = new int[2];
            v.getLocationOnScreen(pos);
            intent.setSourceBounds(new Rect(pos[0], pos[1],
                    pos[0] + v.getWidth(), pos[1] + v.getHeight()));
            startActivitySafely(intent, tag);
        } else if (tag instanceof FolderInfo) {
            handleFolderClick((FolderInfo) tag);
        } else if (v == mHandleView) {
            if (isAllAppsVisible()) {
                closeAllApps(true);
            } else {
                showAllApps(true);
            }
        }
    }

    void startActivitySafely(Intent intent, Object tag) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (PinShortcutActivity.isDeepShortcut(intent)) {
                // Pinned shortcut (Android 8.0+), launched through LauncherApps
                PinShortcutActivity.startDeepShortcut(this, intent);
                return;
            }
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Unable to launch. tag=" + tag + " intent=" + intent, e);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Launcher does not have the permission to launch " + intent +
                    ". Make sure to create a MAIN intent-filter for the corresponding activity " +
                    "or use the exported attribute for this activity. "
                    + "tag="+ tag + " intent=" + intent, e);
        }
    }
    
    void startActivityForResultSafely(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            mWaitingForResult = false;
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            mWaitingForResult = false;
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Launcher does not have the permission to launch " + intent +
                    ". Make sure to create a MAIN intent-filter for the corresponding activity " +
                    "or use the exported attribute for this activity.", e);
        }
    }

    private void handleFolderClick(FolderInfo folderInfo) {
        if (!folderInfo.opened) {
            // Close any open folder
            closeFolder();
            // Open the requested folder
            openFolder(folderInfo);
        } else {
            // Find the open folder...
            Folder openFolder = mWorkspace.getFolderForTag(folderInfo);
            int folderScreen;
            if (openFolder != null) {
                folderScreen = mWorkspace.getScreenForView(openFolder);
                // .. and close it
                closeFolder(openFolder);
                if (folderScreen != mWorkspace.getCurrentScreen()) {
                    // Close any folder open on the current screen
                    closeFolder();
                    // Pull the folder onto this screen
                    openFolder(folderInfo);
                }
            }
        }
    }

    /**
     * Opens the user fodler described by the specified tag. The opening of the folder
     * is animated relative to the specified View. If the View is null, no animation
     * is played.
     *
     * @param folderInfo The FolderInfo describing the folder to open.
     */
    private void openFolder(FolderInfo folderInfo) {
        Folder openFolder;

        if (folderInfo instanceof UserFolderInfo) {
            openFolder = UserFolder.fromXml(this);
        } else if (folderInfo instanceof LiveFolderInfo) {
            openFolder = com.android.launcher2.LiveFolder.fromXml(this, folderInfo);
        } else {
            return;
        }

        openFolder.setDragController(mDragController);
        openFolder.setLauncher(this);

        openFolder.bind(folderInfo);
        folderInfo.opened = true;

        mWorkspace.addInScreen(openFolder, folderInfo.screen, 0, 0,
                NUMBER_CELLS_X, NUMBER_CELLS_Y); // Tweaks: was 4, 4
        openFolder.onOpen();
        updateBackGesture(); // Port
    }

    public boolean onLongClick(View v) {
        getDragLayer().cancelAllAppsSwipe(); // Tweaks: a long press is no swipe up
        // R.id values are no longer compile-time constants, so this can't be a switch.
        final int id = v.getId();
        if (id == R.id.previous_screen || id == R.id.next_screen || id == R.id.all_apps_button) {
            if (!isAllAppsVisible()) {
                mWorkspace.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                showPreviews(v);
            }
            return true;
        }

        if (isWorkspaceLocked()) {
            return false;
        }

        if (!(v instanceof CellLayout)) {
            v = (View) v.getParent();
        }

        CellLayout.CellInfo cellInfo = (CellLayout.CellInfo) v.getTag();

        // This happens when long clicking an item with the dpad/trackball
        if (cellInfo == null) {
            return true;
        }

        if (mWorkspace.allowLongPress()) {
            if (cellInfo.cell == null) {
                if (cellInfo.valid) {
                    // User long pressed on empty space
                    mWorkspace.setAllowLongPress(false);
                    mWorkspace.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                    showAddDialog(cellInfo);
                }
            } else {
                if (!(cellInfo.cell instanceof Folder)) {
                    // User long pressed on an item
                    mWorkspace.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                    mWorkspace.startDrag(cellInfo);
                }
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked"})
    private void dismissPreview(final View v) {
        final PopupWindow window = (PopupWindow) v.getTag();
        if (window != null) {
            window.setOnDismissListener(new PopupWindow.OnDismissListener() {
                public void onDismiss() {
                    ViewGroup group = (ViewGroup) v.getTag(R.id.workspace);
                    int count = group.getChildCount();
                    for (int i = 0; i < count; i++) {
                        ((ImageView) group.getChildAt(i)).setImageDrawable(null);
                    }
                    ArrayList<Bitmap> bitmaps = (ArrayList<Bitmap>) v.getTag(R.id.icon);
                    for (Bitmap bitmap : bitmaps) bitmap.recycle();

                    v.setTag(R.id.workspace, null);
                    v.setTag(R.id.icon, null);
                    window.setOnDismissListener(null);
                }
            });
            window.dismiss();
        }
        v.setTag(null);
    }

    private void showPreviews(View anchor) {
        // Tweaks: with a single screen there's nothing to jump to
        if (mWorkspace.getChildCount() < 2) return;
        showPreviews(anchor, 0, mWorkspace.getChildCount());
    }

    private void showPreviews(final View anchor, int start, int end) {
        final Resources resources = getResources();
        final Workspace workspace = mWorkspace;

        CellLayout cell = ((CellLayout) workspace.getChildAt(start));
        
        // Tweaks: thumbnails as large as with the stock 5 screens, also with fewer screens
        float max = Math.max(workspace.getChildCount(), 5);
        
        final Rect r = new Rect();
        resources.getDrawable(R.drawable.preview_background).getPadding(r);
        int extraW = (int) ((r.left + r.right) * max);
        int extraH = r.top + r.bottom;

        int aW = cell.getWidth() - extraW;
        float w = aW / max;

        int width = cell.getWidth();
        int height = cell.getHeight();
        int x = cell.getLeftPadding();
        int y = cell.getTopPadding();
        width -= (x + cell.getRightPadding());
        height -= (y + cell.getBottomPadding());

        float scale = w / width;

        int count = end - start;

        final float sWidth = width * scale;
        float sHeight = height * scale;

        LinearLayout preview = new LinearLayout(this);

        PreviewTouchHandler handler = new PreviewTouchHandler(anchor);
        ArrayList<Bitmap> bitmaps = new ArrayList<Bitmap>(count);

        for (int i = start; i < end; i++) {
            ImageView image = new ImageView(this);
            cell = (CellLayout) workspace.getChildAt(i);

            final Bitmap bitmap = Bitmap.createBitmap((int) sWidth, (int) sHeight,
                    Bitmap.Config.ARGB_8888);

            final Canvas c = new Canvas(bitmap);
            c.scale(scale, scale);
            c.translate(-cell.getLeftPadding(), -cell.getTopPadding());
            cell.dispatchDraw(c);

            image.setBackgroundDrawable(resources.getDrawable(R.drawable.preview_background));
            image.setImageBitmap(bitmap);
            image.setTag(i);
            image.setOnClickListener(handler);
            image.setOnFocusChangeListener(handler);
            image.setFocusable(true);
            if (i == mWorkspace.getCurrentScreen()) image.requestFocus();

            preview.addView(image,
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

            bitmaps.add(bitmap);            
        }

        final PopupWindow p = new PopupWindow(this);
        p.setContentView(preview);
        p.setWidth((int) (sWidth * count + (r.left + r.right) * count)); // Tweaks: count
        p.setHeight((int) (sHeight + extraH));
        p.setAnimationStyle(R.style.AnimationPreview);
        p.setOutsideTouchable(true);
        p.setFocusable(true);
        p.setBackgroundDrawable(new ColorDrawable(0));
        p.showAsDropDown(anchor, 0, 0);

        p.setOnDismissListener(new PopupWindow.OnDismissListener() {
            public void onDismiss() {
                dismissPreview(anchor);
            }
        });

        anchor.setTag(p);
        anchor.setTag(R.id.workspace, preview);
        anchor.setTag(R.id.icon, bitmaps);        
    }

    class PreviewTouchHandler implements View.OnClickListener, Runnable, View.OnFocusChangeListener {
        private final View mAnchor;

        public PreviewTouchHandler(View anchor) {
            mAnchor = anchor;
        }

        public void onClick(View v) {
            mWorkspace.snapToScreen((Integer) v.getTag());
            v.post(this);
        }

        public void run() {
            dismissPreview(mAnchor);            
        }

        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                mWorkspace.snapToScreen((Integer) v.getTag());
            }
        }
    }

    Workspace getWorkspace() {
        return mWorkspace;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_CREATE_SHORTCUT:
                return new CreateShortcut().createDialog();
            case DIALOG_RENAME_FOLDER:
                return new RenameFolder().createDialog();
        }

        return super.onCreateDialog(id);
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
            case DIALOG_CREATE_SHORTCUT:
                break;
            case DIALOG_RENAME_FOLDER:
                if (mFolderInfo != null) {
                    EditText input = (EditText) dialog.findViewById(R.id.folder_name);
                    final CharSequence text = mFolderInfo.title;
                    input.setText(text);
                    input.setSelection(0, text.length());
                }
                break;
        }
    }

    void showRenameDialog(FolderInfo info) {
        mFolderInfo = info;
        mWaitingForResult = true;
        showDialog(DIALOG_RENAME_FOLDER);
    }

    private void showAddDialog(CellLayout.CellInfo cellInfo) {
        mAddItemCellInfo = cellInfo;
        mWaitingForResult = true;
        showDialog(DIALOG_CREATE_SHORTCUT);
    }

    private void pickShortcut() {
        showShortcutPicker();
    }

    // ---------------------------------------------------------------------------------
    // Tweaks: Gingerbread-style pickers. The system pickers (ACTION_APPWIDGET_PICK,
    // ACTION_PICK_ACTIVITY, the wallpaper chooser) are drawn with today's design and can't
    // be themed, so the launcher lists the choices itself, in the "Add to Home" dialog style.
    // ---------------------------------------------------------------------------------

    private void dismissPicker() {
        final GbDialog dialog = mPickerDialog;
        mPickerDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void showPicker(CharSequence title, ArrayList<PickerDialog.Entry> entries,
            final PickerDialog.Listener listener) {
        dismissPicker();
        final GbDialog dialog = PickerDialog.create(this, title, entries, entry -> {
            mWaitingForResult = false;
            listener.onPicked(entry);
        });
        dialog.setOnCancelListener(d -> mWaitingForResult = false);
        dialog.setOnDismissListener(d -> {
            if (mPickerDialog == d) mPickerDialog = null;
        });
        mWaitingForResult = true;
        mPickerDialog = dialog;
        dialog.show();
    }

    /** The activities that can handle the intent, sorted by label. Tag: the ComponentName. */
    private ArrayList<PickerDialog.Entry> queryPickerEntries(Intent intent) {
        final PackageManager pm = getPackageManager();
        final ArrayList<PickerDialog.Entry> entries = new ArrayList<PickerDialog.Entry>();
        for (final ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            CharSequence label = ri.loadLabel(pm);
            if (TextUtils.isEmpty(label)) label = ri.activityInfo.packageName;
            entries.add(new PickerDialog.Entry(label, () -> ri.loadIcon(pm),
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
        }
        PickerDialog.sortByLabel(entries);
        return entries;
    }

    private void showWidgetPicker() {
        final PackageManager pm = getPackageManager();
        final int density = getResources().getDisplayMetrics().densityDpi;
        final android.os.UserHandle me = android.os.Process.myUserHandle();
        final ArrayList<PickerDialog.Entry> entries = new ArrayList<PickerDialog.Entry>();
        for (final AppWidgetProviderInfo info : mAppWidgetManager.getInstalledProviders()) {
            if ((info.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0) {
                continue; // lock screen widgets
            }
            if (!me.equals(info.getProfile())) continue; // work profile
            CharSequence label = info.loadLabel(pm);
            if (TextUtils.isEmpty(label)) label = info.provider.getPackageName();
            entries.add(new PickerDialog.Entry(label, () -> info.loadIcon(this, density), info));
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        PickerDialog.sortByLabel(entries);
        showPicker(getText(R.string.title_select_widget), entries,
                entry -> bindAppWidget((AppWidgetProviderInfo) entry.tag));
    }

    private void bindAppWidget(AppWidgetProviderInfo info) {
        final int appWidgetId = mAppWidgetHost.allocateAppWidgetId();
        if (mAppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)) {
            addAppWidget(new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
            return;
        }
        // A launcher that isn't the system's needs the user's permission to bind the widget
        final Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        mPendingWidgetId = appWidgetId;
        try {
            startActivityForResult(intent, REQUEST_BIND_APPWIDGET);
        } catch (ActivityNotFoundException | SecurityException e) {
            mWaitingForResult = false;
            mPendingWidgetId = -1;
            mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void showLiveFolderPicker() {
        final ArrayList<PickerDialog.Entry> entries =
                queryPickerEntries(new Intent(LiveFolders.ACTION_CREATE_LIVE_FOLDER));
        // First item: the launcher's own folder (tag: null)
        entries.add(0, new PickerDialog.Entry(getText(R.string.group_folder),
                getResources().getDrawable(R.drawable.ic_launcher_folder), null));
        showPicker(getText(R.string.title_select_live_folder), entries, entry -> {
            if (entry.tag == null) {
                if (mAddItemCellInfo != null) addFolder();
            } else {
                final Intent intent = new Intent(LiveFolders.ACTION_CREATE_LIVE_FOLDER);
                intent.setComponent((ComponentName) entry.tag);
                startActivityForResultSafely(intent, REQUEST_CREATE_LIVE_FOLDER);
            }
        });
    }

    private void showShortcutPicker() {
        final ArrayList<PickerDialog.Entry> entries =
                queryPickerEntries(new Intent(Intent.ACTION_CREATE_SHORTCUT));
        // First item: the installed applications (tag: null)
        entries.add(0, new PickerDialog.Entry(getText(R.string.group_applications),
                getResources().getDrawable(R.drawable.ic_launcher_application), null));
        showPicker(getText(R.string.title_select_shortcut), entries, entry -> {
            if (entry.tag == null) {
                showApplicationPicker();
            } else {
                final Intent intent = new Intent(Intent.ACTION_CREATE_SHORTCUT);
                intent.setComponent((ComponentName) entry.tag);
                startActivityForResultSafely(intent, REQUEST_CREATE_SHORTCUT);
            }
        });
    }

    private void showApplicationPicker() {
        final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        showPicker(getText(R.string.title_select_application), queryPickerEntries(mainIntent),
                entry -> {
                    if (mAddItemCellInfo == null) return;
                    final Intent data = new Intent(Intent.ACTION_MAIN, null);
                    data.addCategory(Intent.CATEGORY_LAUNCHER);
                    data.setComponent((ComponentName) entry.tag);
                    completeAddApplication(this, data, mAddItemCellInfo);
                });
    }

    private void showWallpaperPicker() {
        final Intent base = new Intent(Intent.ACTION_SET_WALLPAPER);
        final ArrayList<PickerDialog.Entry> entries = queryPickerEntries(base);
        if (entries.isEmpty()) {
            startActivityForResultSafely(
                    Intent.createChooser(base, getText(R.string.chooser_wallpaper)),
                    REQUEST_PICK_WALLPAPER);
            return;
        }
        showPicker(getText(R.string.chooser_wallpaper), entries, entry -> {
            final Intent intent = new Intent(base);
            intent.setComponent((ComponentName) entry.tag);
            startActivityForResultSafely(intent, REQUEST_PICK_WALLPAPER);
        });
    }

    private class RenameFolder {
        private EditText mInput;

        Dialog createDialog() {
            final View layout = View.inflate(GbDialog.themed(Launcher.this), R.layout.rename_folder, null);
            mInput = (EditText) layout.findViewById(R.id.folder_name);

            GbDialog.Builder builder = new GbDialog.Builder(Launcher.this);
            builder.setIcon(0);
            builder.setTitle(getString(R.string.rename_folder_title));
            builder.setCancelable(true);
            builder.setOnCancelListener(new Dialog.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    cleanup();
                }
            });
            builder.setNegativeButton(getString(R.string.cancel_action),
                new Dialog.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        cleanup();
                    }
                }
            );
            builder.setPositiveButton(getString(R.string.rename_action),
                new Dialog.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        changeFolderName();
                    }
                }
            );
            builder.setView(layout);

            final GbDialog dialog = builder.create();
            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                public void onShow(DialogInterface dialog) {
                    mWaitingForResult = true;
                    mInput.requestFocus();
                    InputMethodManager inputManager = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputManager.showSoftInput(mInput, 0);
                }
            });

            return dialog;
        }

        private void changeFolderName() {
            final String name = mInput.getText().toString();
            if (!TextUtils.isEmpty(name)) {
                // Make sure we have the right folder info
                mFolderInfo = sFolders.get(mFolderInfo.id);
                mFolderInfo.title = name;
                LauncherModel.updateItemInDatabase(Launcher.this, mFolderInfo);

                if (mWorkspaceLoading) {
                    lockAllApps();
                    mModel.startLoader(Launcher.this, false);
                } else {
                    final FolderIcon folderIcon = (FolderIcon)
                            mWorkspace.getViewForTag(mFolderInfo);
                    if (folderIcon != null) {
                        folderIcon.setText(name);
                        getWorkspace().requestLayout();
                    } else {
                        lockAllApps();
                        mWorkspaceLoading = true;
                        mModel.startLoader(Launcher.this, false);
                    }
                }
            }
            cleanup();
        }

        private void cleanup() {
            dismissDialog(DIALOG_RENAME_FOLDER);
            mWaitingForResult = false;
            mFolderInfo = null;
        }
    }

    // Now a part of LauncherModel.Callbacks. Used to reorder loading steps.
    public boolean isAllAppsVisible() {
        return (mAllAppsGrid != null) ? mAllAppsGrid.isVisible() : false;
    }

    // AllAppsView.Watcher
    public void zoomed(float zoom) {
        if (zoom == 1.0f) {
            mWorkspace.setVisibility(View.GONE);
        }
    }

    // Tweaks: the 2D drawer is chosen in the settings
    AllApps2D getAllApps2D() {
        return mAllAppsGrid instanceof AllApps2D ? (AllApps2D) mAllAppsGrid : null;
    }

    // Tweaks: all apps following a finger (swipe up on the home screen, down in all apps)
    AllApps3D getAllApps3D() {
        return mAllAppsGrid instanceof AllApps3D ? (AllApps3D) mAllAppsGrid : null;
    }

    void showAllApps(boolean animated) {
        getDragLayer().clearAllResizeFrames(); // Tweaks
        mAllAppsShown = true; // Port
        updateBackGesture();
        mAllAppsGrid.zoom(1.0f, animated);

        ((View) mAllAppsGrid).setFocusable(true);
        ((View) mAllAppsGrid).requestFocus();
        
        // TODO: fade these two too
        mDeleteZone.setVisibility(View.GONE);
    }

    /**
     * Things to test when changing this code.
     *   - Home from workspace
     *          - from center screen
     *          - from other screens
     *   - Home from all apps
     *          - from center screen
     *          - from other screens
     *   - Back from all apps
     *          - from center screen
     *          - from other screens
     *   - Launch app from workspace and quit
     *          - with back
     *          - with home
     *   - Launch app from all apps and quit
     *          - with back
     *          - with home
     *   - Go to a screen that's not the default, then all
     *     apps, and launch and app, and go back
     *          - with back
     *          -with home
     *   - On workspace, long press power and go back
     *          - with back
     *          - with home
     *   - On all apps, long press power and go back
     *          - with back
     *          - with home
     *   - On workspace, power off
     *   - On all apps, power off
     *   - Launch an app and turn off the screen while in that app
     *          - Go back with home key
     *          - Go back with back key  TODO: make this not go to workspace
     *          - From all apps
     *          - From workspace
     *   - Enter and exit car mode (becuase it causes an extra configuration changed)
     *          - From all apps
     *          - From the center workspace
     *          - From another workspace
     */
    void closeAllApps(boolean animated) {
        mAllAppsShown = false; // Port
        updateBackGesture();
        if (mAllAppsGrid.isVisible()) {
            mWorkspace.setVisibility(View.VISIBLE);
            mAllAppsGrid.zoom(0.0f, animated);
            ((View)mAllAppsGrid).setFocusable(false);
            mWorkspace.getChildAt(mWorkspace.getCurrentScreen()).requestFocus();
        }
    }

    void lockAllApps() {
        // TODO
    }

    void unlockAllApps() {
        // TODO
    }

    /**
     * Displays the shortcut creation dialog and launches, if necessary, the
     * appropriate activity.
     */
    private class CreateShortcut implements DialogInterface.OnClickListener,
            DialogInterface.OnCancelListener, DialogInterface.OnDismissListener,
            DialogInterface.OnShowListener {

        private AddAdapter mAdapter;

        Dialog createDialog() {
            mAdapter = new AddAdapter(Launcher.this);

            final GbDialog.Builder builder = new GbDialog.Builder(Launcher.this);
            builder.setTitle(getString(R.string.menu_item_add_item));
            builder.setAdapter(mAdapter, this);

            builder.setInverseBackgroundForced(true);

            GbDialog dialog = builder.create();
            dialog.setOnCancelListener(this);
            dialog.setOnDismissListener(this);
            dialog.setOnShowListener(this);

            return dialog;
        }

        public void onCancel(DialogInterface dialog) {
            mWaitingForResult = false;
            cleanup();
        }

        public void onDismiss(DialogInterface dialog) {
        }

        private void cleanup() {
            try {
                dismissDialog(DIALOG_CREATE_SHORTCUT);
            } catch (Exception e) {
                // An exception is thrown if the dialog is not visible, which is fine
            }
        }

        /**
         * Handle the action clicked in the "Add to home" dialog.
         */
        public void onClick(DialogInterface dialog, int which) {
            Resources res = getResources();
            cleanup();

            switch (which) {
                case AddAdapter.ITEM_SHORTCUT: {
                    // Insert extra item to handle picking application
                    pickShortcut();
                    break;
                }

                case AddAdapter.ITEM_APPWIDGET: {
                    showWidgetPicker();
                    break;
                }

                case AddAdapter.ITEM_LIVE_FOLDER: {
                    showLiveFolderPicker();
                    break;
                }

                case AddAdapter.ITEM_WALLPAPER: {
                    startWallpaper();
                    break;
                }

                case AddAdapter.ITEM_SETTINGS: { // Tweaks
                    mWaitingForResult = false;
                    // own task: the settings (text fields, keyboard) must not run inside the home task
                    startActivity(new Intent(Launcher.this, Settings.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                }
            }
        }

        public void onShow(DialogInterface dialog) {
            mWaitingForResult = true;            
        }
    }

    // Tweaks: widget resizing (from ICS)
    // Same span calculation as completeAddAppWidget() (Gingerbread: no widget padding).
    // ICS added the default widget padding here and when placing widgets; adding it only
    // here made the minimum larger than the placed size, so resizing forced the widget
    // to grow and it could never shrink back.
    int[] getMinResizeSpanForWidget(AppWidgetProviderInfo info, int[] spanXY) {
        CellLayout layout = (CellLayout) mWorkspace.getChildAt(mWorkspace.getCurrentScreen());
        return layout.rectToCell(info.minResizeWidth, info.minResizeHeight);
    }

    // Tweaks: Google feed
    private FeedOverlay mFeed;

    // Tweaks: notification badges on the dock buttons (phone, browser and the extra slots)
    private final java.util.ArrayList<Drawable> mDockBadges = new java.util.ArrayList<Drawable>();

    private void addDockBadges(ImageView phone, ImageView browser) {
        // the labels' font (Droid Sans or the system font, see useDroidSans())
        final android.graphics.Typeface typeface = new TextView(this).getTypeface();
        final java.util.ArrayList<ImageView> buttons = new java.util.ArrayList<ImageView>();
        buttons.add(phone);
        buttons.add(browser);
        collectDockExtras((ViewGroup) findViewById(R.id.all_apps_button_cluster), buttons);
        for (final ImageView b : buttons) {
            final int index = b == phone ? 0 : b == browser ? 1 : (Integer) b.getTag();
            final Drawable badge = new BubbleTextView.DockBadge(b, () -> {
                final Intent intent = mHotseats != null && index < mHotseats.length
                        ? mHotseats[index] : null;
                if (intent == null) return null;
                return intent.getComponent() != null
                        ? intent.getComponent().getPackageName() : intent.getPackage();
            }, typeface);
            b.getOverlay().add(badge);
            b.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) ->
                    badge.setBounds(0, 0, r - l, bo - t));
            mDockBadges.add(badge);
        }
    }

    private static void collectDockExtras(ViewGroup group, java.util.List<ImageView> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View v = group.getChildAt(i);
            if (v instanceof ImageView && v.getTag() instanceof Integer) out.add((ImageView) v);
            else if (v instanceof ViewGroup) collectDockExtras((ViewGroup) v, out);
        }
    }

    // Tweaks: badges changed
    private static void invalidateShortcuts(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            final View v = group.getChildAt(i);
            if (v instanceof BubbleTextView) v.invalidate();
            else if (v instanceof ViewGroup) invalidateShortcuts((ViewGroup) v);
        }
    }

    FeedOverlay getFeed() {
        return mFeed != null && mFeed.isAttached() ? mFeed : null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mFeed != null) mFeed.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mFeed != null) mFeed.onStop();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mFeed != null) mFeed.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mFeed != null) mFeed.onDetachedFromWindow();
    }

    // Tweaks
    DragLayer getDragLayer() {
        return (DragLayer) findViewById(R.id.drag_layer);
    }

    // Tweaks: launcher settings, in their own window laid out like a Gingerbread
    // settings screen (title bar, list separators, list selector and check boxes of 2.3.7).
    // Changes are saved right away and applied when the launcher comes back.
    public static class Settings extends Activity {
        static boolean sChanged;
        static boolean sIconsChanged;
        private static final String EXTRA_PAGE = "page";

        private android.content.SharedPreferences mPrefs;
        private GbSettingsList mPage;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (useDroidSans(this)) {
                getTheme().applyStyle(R.style.DroidSansFont, true);
            }
            mPage = new GbSettingsList(this, R.string.group_tweaks);
            mPrefs = getSharedPreferences(TWEAKS_PREFERENCES, MODE_PRIVATE);

            // Main page lists the sections, each section has its own page (like Lawnchair)
            final int page = getIntent().getIntExtra(EXTRA_PAGE, 0);
            if (page != 0) {
                mPage.setTitle(page);
            }
            if (page == R.string.tweaks_category_home) {
                mPage.addCategory(R.string.tweaks_category_grid);
                addNumber(R.string.tweaks_columns, TWEAK_HOME_COLUMNS, 3, 7, 0, true);
                addNumber(R.string.tweaks_rows, TWEAK_HOME_ROWS, 3, 8, 0, true);
                mPage.addCategory(R.string.tweaks_category_screens);
                addNumber(R.string.tweaks_screens, TWEAK_HOME_SCREENS, 1, 5, 5, false);
                mPage.addCategory(R.string.tweaks_category_gestures);
                addCheck(R.string.tweaks_swipe_down_notifications,
                        R.string.tweaks_swipe_down_notifications_summary,
                        TWEAK_SWIPE_DOWN_NOTIFICATIONS);
                addCheck(R.string.tweaks_swipe_up_all_apps,
                        R.string.tweaks_swipe_up_all_apps_summary, TWEAK_SWIPE_UP_ALL_APPS);
                mPage.addCategory(R.string.tweaks_category_feed);
                addChoice(R.string.tweaks_feed, TWEAK_FEED, new int[] {
                        R.string.tweaks_feed_off, R.string.tweaks_feed_google,
                        R.string.tweaks_feed_aidl_bridge });
            } else if (page == R.string.tweaks_category_drawer) {
                mPage.addCategory(R.string.tweaks_category_drawer_style);
                addChoice(R.string.tweaks_drawer_style, TWEAK_DRAWER_STYLE, new int[] {
                        R.string.tweaks_drawer_style_3d, R.string.tweaks_drawer_style_2d });
                mPage.addCategory(R.string.tweaks_category_grid);
                addNumber(R.string.tweaks_columns, TWEAK_DRAWER_COLUMNS, 2, 7, 0, true);
                addNumber(R.string.tweaks_rows, TWEAK_DRAWER_ROWS, 2, 8, 0, true);
                mPage.addCategory(R.string.tweaks_category_apps);
                final View hidden = addItem(R.string.tweaks_hidden_apps, null);
                final TextView hiddenSummary =
                        (TextView) hidden.findViewById(R.id.settings_item_summary);
                updateHiddenSummary(hiddenSummary);
                hidden.setOnClickListener(v -> showHiddenAppsDialog(hiddenSummary));
            } else if (page == R.string.tweaks_category_display) {
                mPage.addCategory(R.string.tweaks_category_system_bars);
                addCheck(R.string.tweaks_transparent_status_bar,
                        R.string.tweaks_transparent_bar_summary, TWEAK_TRANSPARENT_STATUS_BAR);
                addCheck(R.string.tweaks_transparent_nav_bar,
                        R.string.tweaks_transparent_bar_summary, TWEAK_TRANSPARENT_NAV_BAR);
                mPage.addCategory(R.string.tweaks_category_font);
                addCheck(R.string.tweaks_system_font, R.string.tweaks_system_font_summary,
                        TWEAK_SYSTEM_FONT);
                mPage.addCategory(R.string.tweaks_category_badges);
                addCheck(R.string.tweaks_badges, R.string.tweaks_badges_summary, TWEAK_BADGES);
                final View access = addItem(R.string.tweaks_badges_access, null);
                mBadgeAccess = (TextView) access.findViewById(R.id.settings_item_summary);
                access.setOnClickListener(v -> startActivity(new Intent(
                        android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            } else if (page == R.string.tweaks_category_icons) {
                mPage.addCategory(R.string.tweaks_category_icons);
                addChoice(R.string.tweaks_icon_shape, TWEAK_ICON_SHAPE, new int[] {
                        R.string.tweaks_icon_shape_system, R.string.tweaks_icon_shape_circle,
                        R.string.tweaks_icon_shape_squircle,
                        R.string.tweaks_icon_shape_rounded_rect,
                        R.string.tweaks_icon_shape_teardrop, R.string.tweaks_icon_shape_square });
                mPage.addCategory(R.string.tweaks_icon_pack);
                addIconPack();
                addChoice(R.string.tweaks_icon_pack_where, TWEAK_ICON_PACK_EVERYWHERE,
                        new int[] { R.string.tweaks_icon_pack_home,
                                R.string.tweaks_icon_pack_everywhere });
                addChoice(R.string.tweaks_icon_pack_colors, TWEAK_ICON_PACK_COLORS,
                        new int[] { R.string.tweaks_icon_shape_system,
                                R.string.tweaks_icon_pack_colors_own });
                addColor(R.string.tweaks_icon_pack_fg, TWEAK_ICON_PACK_FG,
                        DEFAULT_ICON_PACK_FG);
                addColor(R.string.tweaks_icon_pack_bg, TWEAK_ICON_PACK_BG,
                        DEFAULT_ICON_PACK_BG);
            } else if (page == R.string.tweaks_category_widgets) {
                com.google.android.apps.genie.geniewidget.WeatherSettings.add(this, mPage);
                com.google.android.apps.genie.geniewidget.NewsSettings.add(this, mPage);
                mYouTube = com.google.android.youtube.widget.YouTubeSettings.add(this, mPage);
            } else if (page == R.string.tweaks_dock) {
                mPage.addCategory(R.string.tweaks_category_dock_apps);
                addDockList();
            } else {
                addPage(R.string.tweaks_category_display, R.string.tweaks_display_summary);
                addPage(R.string.tweaks_category_icons, R.string.tweaks_icons_summary);
                addPage(R.string.tweaks_category_home, R.string.tweaks_home_summary);
                addPage(R.string.tweaks_category_drawer, R.string.tweaks_drawer_summary);
                addPage(R.string.tweaks_dock, R.string.tweaks_dock_summary);
                addPage(R.string.tweaks_category_widgets, R.string.tweaks_widgets_summary);
                addItem(R.string.tweaks_reset_title, getString(R.string.tweaks_reset_summary))
                        .setOnClickListener(v -> confirmReset());
            }
        }

        private TextView mBadgeAccess; // Tweaks: notification access for the badges
        private com.google.android.youtube.widget.YouTubeSettings mYouTube; // Widgets page

        /** The widget settings page (the widgets open it when they aren't set up yet). */
        public static Intent widgetsIntent(Context context) {
            return new Intent(context, Settings.class)
                    .putExtra(EXTRA_PAGE, R.string.tweaks_category_widgets)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (mYouTube != null) mYouTube.onActivityResult(requestCode, resultCode, data);
        }

        @Override
        protected void onResume() {
            super.onResume();
            if (mBadgeAccess != null) {
                GbSettingsList.setSummary(mBadgeAccess, getString(BadgeListener.isEnabled(this)
                        ? R.string.tweaks_badges_access_on : R.string.tweaks_badges_access_off));
            }
        }

        /**
         * Tweaks: the dock items, shown with a check box and sorted with the handle on the
         * right (dragged up or down).
         */
        private void addDockList() {
            final LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setDividerDrawable(getResources().getDrawable(
                    R.drawable.gb_divider_horizontal_dark_opaque));
            list.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            mPage.addGroup(list);
            final int dividerHeight = list.getDividerDrawable().getIntrinsicHeight();
            final float density = getResources().getDisplayMetrics().density;

            for (String item : loadDock(mPrefs)) {
                final String id = item.startsWith("-") ? item.substring(1) : item;
                final View row = mPage.createItem(list, getString(dockLabel(id)), null);
                row.setTag(id);
                final android.widget.CheckBox box =
                        (android.widget.CheckBox) row.findViewById(R.id.settings_item_checkbox);
                box.setVisibility(View.VISIBLE);
                box.setChecked(!item.startsWith("-"));
                row.setOnClickListener(v -> {
                    box.setChecked(!box.isChecked());
                    saveDock(list);
                });

                final android.widget.ImageView handle = new android.widget.ImageView(this);
                handle.setImageResource(R.drawable.tweaks_dock_grabber);
                handle.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                final int pad = (int) (12 * density);
                handle.setPadding(pad, pad, (int) (4 * density), pad);
                ((LinearLayout) row).addView(handle, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT));
                final float[] startY = new float[1];
                handle.setOnTouchListener((v, ev) -> {
                    switch (ev.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            startY[0] = ev.getRawY();
                            row.setTranslationZ(8 * density);
                            row.setPressed(true);
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            int index = list.indexOfChild(row);
                            float dy = ev.getRawY() - startY[0];
                            // past the middle of a neighbor: the neighbor moves to the other side
                            while (index < list.getChildCount() - 1
                                    && dy > list.getChildAt(index + 1).getHeight() / 2f) {
                                final View next = list.getChildAt(index + 1);
                                list.removeView(next);
                                list.addView(next, index);
                                index++;
                                startY[0] += next.getHeight() + dividerHeight;
                                dy = ev.getRawY() - startY[0];
                            }
                            while (index > 0
                                    && -dy > list.getChildAt(index - 1).getHeight() / 2f) {
                                final View previous = list.getChildAt(index - 1);
                                list.removeView(previous);
                                list.addView(previous, index);
                                index--;
                                startY[0] -= previous.getHeight() + dividerHeight;
                                dy = ev.getRawY() - startY[0];
                            }
                            row.setTranslationY(dy);
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL:
                            row.setTranslationY(0);
                            row.setTranslationZ(0);
                            row.setPressed(false);
                            saveDock(list);
                            return true;
                    }
                    return false;
                });
                list.addView(row);
            }
        }

        private void saveDock(LinearLayout list) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.getChildCount(); i++) {
                final View row = list.getChildAt(i);
                final boolean on = ((android.widget.CheckBox) row.findViewById(
                        R.id.settings_item_checkbox)).isChecked();
                if (sb.length() > 0) sb.append(',');
                sb.append(on ? "" : "-").append(row.getTag());
            }
            mPrefs.edit().putString(TWEAK_DOCK, sb.toString()).apply();
            sChanged = true;
        }

        private void addPage(final int title, int summary) {
            addItem(title, getString(summary)).setOnClickListener(v -> startActivity(
                    new Intent(this, Settings.class).putExtra(EXTRA_PAGE, title)));
        }

        private View addItem(int title, CharSequence summary) {
            return mPage.addItem(title, summary);
        }

        private void addCheck(int title, int summary, final String key) {
            final View v = addItem(title, summary == 0 ? null : getString(summary));
            final android.widget.CheckBox box =
                    (android.widget.CheckBox) v.findViewById(R.id.settings_item_checkbox);
            box.setVisibility(View.VISIBLE);
            box.setChecked(mPrefs.getBoolean(key, false));
            v.setOnClickListener(view -> {
                box.setChecked(!box.isChecked());
                mPrefs.edit().putBoolean(key, box.isChecked()).apply();
                sChanged = true;
                if (TWEAK_SYSTEM_FONT.equals(key)) {
                    sIconsChanged = true; // the drawer labels are drawn with the font
                }
            });
        }

        // Color: suggestions from the Gingerbread graphics, or an own HEX value
        private static final int[] COLOR_VALUES = { 0xff8fbc46, 0xffffb819,
                0xff2a2a2a, 0xffffffff, 0xff000000 };
        private static final int[] COLOR_NAMES = { R.string.tweaks_color_dock_green, R.string.tweaks_color_orange,
                R.string.tweaks_color_dock_grey, R.string.tweaks_color_white,
                R.string.tweaks_color_black };

        /** Summary of a color: its name, "from the wallpaper" or "custom color". */
        private String colorName(int color, boolean fromWallpaper) {
            if (fromWallpaper) return getString(R.string.tweaks_color_wallpaper);
            for (int i = 0; i < COLOR_VALUES.length; i++) {
                if (COLOR_VALUES[i] == color) return getString(COLOR_NAMES[i]);
            }
            return getString(R.string.tweaks_color_own);
        }

        private static String hex(int color) {
            return String.format("#%06X", color & 0xffffff);
        }

        private void addColor(final int title, final String key, int def) {
            final int[] color = { mPrefs.getInt(key, def) };
            final String wallpaperKey = key + COLOR_FROM_WALLPAPER;
            final View v = addItem(title, colorName(color[0], mPrefs.getBoolean(wallpaperKey,
                    false)));
            final TextView summary = (TextView) v.findViewById(R.id.settings_item_summary);
            final CharSequence[] labels = new CharSequence[COLOR_VALUES.length + 2];
            for (int i = 0; i < COLOR_VALUES.length; i++) {
                labels[i] = getString(COLOR_NAMES[i]);
            }
            labels[COLOR_VALUES.length] = getString(R.string.tweaks_color_wallpaper);
            labels[COLOR_VALUES.length + 1] = getString(R.string.tweaks_color_custom);
            final java.util.function.IntConsumer set = c -> {
                color[0] = c;
                mPrefs.edit().putInt(key, c).putBoolean(wallpaperKey, false).apply();
                sChanged = true;
                sIconsChanged = true;
                GbSettingsList.setSummary(summary, colorName(c, false));
            };
            v.setOnClickListener(view -> {
                int checked = -1;
                for (int i = 0; i < COLOR_VALUES.length; i++) {
                    if (COLOR_VALUES[i] == color[0]) checked = i;
                }
                if (mPrefs.getBoolean(wallpaperKey, false)) checked = COLOR_VALUES.length;
                new GbDialog.Builder(this)
                        .setTitle(title)
                        .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                            dialog.dismiss();
                            if (which < COLOR_VALUES.length) {
                                set.accept(COLOR_VALUES[which]);
                            } else if (which == COLOR_VALUES.length) {
                                mPrefs.edit().putBoolean(wallpaperKey, true).apply();
                                sChanged = true;
                                sIconsChanged = true;
                                GbSettingsList.setSummary(summary, colorName(color[0], true));
                            } else {
                                askHexColor(title, color[0], set);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

        private void askHexColor(int title, int current, java.util.function.IntConsumer set) {
            final android.widget.EditText input = GbDialog.newEditText(this);
            input.setSingleLine(true);
            input.setText(hex(current));
            input.setSelection(input.getText().length());
            final View frame = GbDialog.wrapField(this, input);
            new GbDialog.Builder(this)
                    .setTitle(title)
                    .setView(frame)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String s = input.getText().toString().trim();
                        if (!s.startsWith("#")) s = "#" + s;
                        try {
                            set.accept(android.graphics.Color.parseColor(s) | 0xff000000);
                        } catch (IllegalArgumentException e) {
                            android.widget.Toast.makeText(this, R.string.tweaks_color_invalid,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        // Icon packs: the apps that offer one (ADW/Nova/GO format)
        private void addIconPack() {
            final PackageManager pm = getPackageManager();
            final java.util.LinkedHashMap<String, CharSequence> packs =
                    new java.util.LinkedHashMap<String, CharSequence>();
            for (String action : IconCache.ICON_PACK_ACTIONS) {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), 0)) {
                    if (!packs.containsKey(ri.activityInfo.packageName)) {
                        packs.put(ri.activityInfo.packageName,
                                ri.activityInfo.applicationInfo.loadLabel(pm));
                    }
                }
            }
            final String[] pkgs = new String[packs.size() + 1];
            final CharSequence[] labels = new CharSequence[packs.size() + 1];
            labels[0] = getString(R.string.tweaks_icon_pack_none);
            int i = 1;
            for (java.util.Map.Entry<String, CharSequence> e : packs.entrySet()) {
                pkgs[i] = e.getKey();
                labels[i++] = e.getValue();
            }
            final String current = mPrefs.getString(TWEAK_ICON_PACK, null);
            final int[] selected = { 0 };
            for (int j = 1; j < pkgs.length; j++) {
                if (pkgs[j].equals(current)) selected[0] = j;
            }
            final View v = addItem(R.string.tweaks_icon_pack, labels[selected[0]]);
            final TextView summary = (TextView) v.findViewById(R.id.settings_item_summary);
            v.setOnClickListener(view -> new GbDialog.Builder(this)
                    .setTitle(R.string.tweaks_icon_pack)
                    .setSingleChoiceItems(labels, selected[0], (dialog, which) -> {
                        selected[0] = which;
                        mPrefs.edit().putString(TWEAK_ICON_PACK, pkgs[which]).apply();
                        sChanged = true;
                        sIconsChanged = true;
                        GbSettingsList.setSummary(summary, labels[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }

        // ListPreference style with named entries; the stored value is the index
        private void addChoice(final int title, final String key, int[] entries) {
            final String[] labels = new String[entries.length];
            for (int i = 0; i < entries.length; i++) labels[i] = getString(entries[i]);
            final int[] selected = { Math.max(0, Math.min(labels.length - 1,
                    mPrefs.getInt(key, 0))) };
            final View v = addItem(title, labels[selected[0]]);
            final TextView summary = (TextView) v.findViewById(R.id.settings_item_summary);
            v.setOnClickListener(view -> new GbDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, selected[0], (dialog, which) -> {
                        selected[0] = which;
                        mPrefs.edit().putInt(key, which).apply();
                        sChanged = true;
                        sIconsChanged = true;
                        GbSettingsList.setSummary(summary, labels[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }

        // ListPreference style: the summary shows the value, a dialog picks a new one.
        // "Auto" (0) is the stock drawer layout.
        private void addNumber(final int title, final String key, int min, int max,
                int def, boolean auto) {
            final int count = max - min + 1 + (auto ? 1 : 0);
            final String[] labels = new String[count];
            final int[] values = new int[count];
            int i = 0;
            if (auto) {
                labels[i] = getString(R.string.tweaks_auto);
                values[i++] = 0;
            }
            for (int n = min; n <= max; n++, i++) {
                labels[i] = String.valueOf(n);
                values[i] = n;
            }
            final int stored = mPrefs.getInt(key, def);
            int index = 0;
            for (int j = 0; j < count; j++) {
                if (values[j] == stored) index = j;
                else if (stored > max && values[j] == max) index = j;
            }
            final int[] selected = { index };
            mPage.addSlider(title, labels, index, which -> {
                if (which == selected[0]) return;
                selected[0] = which;
                mPrefs.edit().putInt(key, values[which]).apply();
                sChanged = true;
            });
        }

        private void confirmReset() {
            new GbDialog.Builder(this)
                    .setIcon(R.drawable.gb_ic_dialog_alert)
                    .setTitle(R.string.tweaks_reset_title)
                    .setMessage(R.string.tweaks_reset_confirm)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        mPrefs.edit().clear().apply();
                        sChanged = true;
                        sIconsChanged = true;
                        recreate();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void updateHiddenSummary(TextView summary) {
            final int n = mPrefs.getStringSet(TWEAK_HIDDEN_APPS,
                    new java.util.HashSet<String>()).size();
            GbSettingsList.setSummary(summary, n == 0 ? null
                    : getResources().getQuantityString(R.plurals.tweaks_hidden_count, n, n));
        }

        // Choose apps to hide from the app drawer
        private void showHiddenAppsDialog(final TextView summary) {
            final java.util.HashSet<String> hidden = new java.util.HashSet<String>(
                    mPrefs.getStringSet(TWEAK_HIDDEN_APPS, new java.util.HashSet<String>()));

            final PackageManager pm = getPackageManager();
            final Intent main = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            final List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
            java.util.Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));

            final int count = apps.size();
            final CharSequence[] labels = new CharSequence[count];
            final String[] components = new String[count];
            final boolean[] checked = new boolean[count];
            for (int i = 0; i < count; i++) {
                final ResolveInfo ri = apps.get(i);
                labels[i] = ri.loadLabel(pm);
                components[i] = new ComponentName(ri.activityInfo.packageName,
                        ri.activityInfo.name).flattenToString();
                checked[i] = hidden.contains(components[i]);
            }

            new GbDialog.Builder(this)
                    .setTitle(R.string.tweaks_hidden_apps)
                    .setMultiChoiceItems(labels, checked,
                            (dialog, which, isChecked) -> checked[which] = isChecked)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        final java.util.HashSet<String> result =
                                new java.util.HashSet<String>();
                        for (int i = 0; i < count; i++) {
                            if (checked[i]) result.add(components[i]);
                        }
                        mPrefs.edit().putStringSet(TWEAK_HIDDEN_APPS, result).apply();
                        sChanged = true;
                        updateHiddenSummary(summary);
                    })
                    .show();
        }
    }

    // Tweaks: drawer list without the hidden apps
    private ArrayList<ApplicationInfo> filterHiddenApps(ArrayList<ApplicationInfo> apps) {
        final java.util.Set<String> hidden = getSharedPreferences(TWEAKS_PREFERENCES,
                MODE_PRIVATE).getStringSet(TWEAK_HIDDEN_APPS, null);
        if (hidden == null || hidden.isEmpty() || apps == null) {
            return apps;
        }
        final ArrayList<ApplicationInfo> result = new ArrayList<ApplicationInfo>(apps.size());
        for (ApplicationInfo app : apps) {
            final ComponentName cn = app.intent.getComponent();
            if (cn == null || !hidden.contains(cn.flattenToString())) {
                result.add(app);
            }
        }
        return result;
    }

    // Tweaks: rebuild the workspace and drawer with the new grid
    private void applyTweaks() {
        loadTweaks(this);
        recreate();
    }

    /**
     * Receives notifications when applications are added/removed.
     */
    private class CloseSystemDialogsIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            closeSystemDialogs();
            String reason = intent.getStringExtra("reason");
            if (!"homekey".equals(reason)) {
                boolean animate = true;
                if (mPaused || "lock".equals(reason)) {
                    animate = false;
                }
                closeAllApps(animate);
            }
        }
    }

    /**
     * Receives notifications whenever the appwidgets are reset.
     */
    private class AppWidgetResetObserver extends ContentObserver {
        public AppWidgetResetObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            onAppWidgetReset();
        }
    }

    /**
     * If the activity is currently paused, signal that we need to re-run the loader
     * in onResume.
     *
     * This needs to be called from incoming places where resources might have been loaded
     * while we are paused.  That is becaues the Configuration might be wrong
     * when we're not running, and if it comes back to what it was when we
     * were paused, we are not restarted.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     *
     * @return true if we are currently paused.  The caller might be able to
     * skip some work in that case since we will come back again.
     */
    public boolean setLoadOnResume() {
        if (mPaused) {
            Log.i(TAG, "setLoadOnResume");
            mOnResumeNeedsLoad = true;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public int getCurrentWorkspaceScreen() {
        if (mWorkspace != null) {
            return mWorkspace.getCurrentScreen();
        } else {
            return SCREEN_COUNT / 2;
        }
    }

    /**
     * Refreshes the shortcuts shown on the workspace.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void startBinding() {
        final Workspace workspace = mWorkspace;
        int count = workspace.getChildCount();
        for (int i = 0; i < count; i++) {
            // Use removeAllViewsInLayout() to avoid an extra requestLayout() and invalidate().
            ((ViewGroup) workspace.getChildAt(i)).removeAllViewsInLayout();
        }

        if (DEBUG_USER_INTERFACE) {
            android.widget.Button finishButton = new android.widget.Button(this);
            finishButton.setText("Finish");
            workspace.addInScreen(finishButton, 1, 0, 0, 1, 1);

            finishButton.setOnClickListener(new android.widget.Button.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });
        }
    }

    /**
     * Bind the items start-end from the list.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void bindItems(ArrayList<ItemInfo> shortcuts, int start, int end) {

        setLoadOnResume();

        final Workspace workspace = mWorkspace;

        for (int i=start; i<end; i++) {
            final ItemInfo item = shortcuts.get(i);
            mDesktopItems.add(item);
            switch (item.itemType) {
                case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
                case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                    final View shortcut = createShortcut((ShortcutInfo)item);
                    workspace.addInScreen(shortcut, item.screen, item.cellX, item.cellY, 1, 1,
                            false);
                    break;
                case LauncherSettings.Favorites.ITEM_TYPE_USER_FOLDER:
                    final FolderIcon newFolder = FolderIcon.fromXml(R.layout.folder_icon, this,
                            (ViewGroup) workspace.getChildAt(workspace.getCurrentScreen()),
                            (UserFolderInfo) item);
                    workspace.addInScreen(newFolder, item.screen, item.cellX, item.cellY, 1, 1,
                            false);
                    break;
                case LauncherSettings.Favorites.ITEM_TYPE_LIVE_FOLDER:
                    final FolderIcon newLiveFolder = LiveFolderIcon.fromXml(
                            R.layout.live_folder_icon, this,
                            (ViewGroup) workspace.getChildAt(workspace.getCurrentScreen()),
                            (LiveFolderInfo) item);
                    workspace.addInScreen(newLiveFolder, item.screen, item.cellX, item.cellY, 1, 1,
                            false);
                    break;
            }
        }

        workspace.requestLayout();
    }

    /**
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void bindFolders(HashMap<Long, FolderInfo> folders) {
        setLoadOnResume();
        sFolders.clear();
        sFolders.putAll(folders);
    }

    /**
     * Add the views for a widget to the workspace.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void bindAppWidget(LauncherAppWidgetInfo item) {
        setLoadOnResume();

        final long start = DEBUG_WIDGETS ? SystemClock.uptimeMillis() : 0;
        if (DEBUG_WIDGETS) {
            Log.d(TAG, "bindAppWidget: " + item);
        }
        final Workspace workspace = mWorkspace;

        final int appWidgetId = item.appWidgetId;
        final AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
        if (DEBUG_WIDGETS) {
            Log.d(TAG, "bindAppWidget: id=" + item.appWidgetId + " belongs to component " + appWidgetInfo.provider);
        }

        item.hostView = mAppWidgetHost.createView(this, appWidgetId, appWidgetInfo);

        item.hostView.setAppWidget(appWidgetId, appWidgetInfo);
        item.hostView.setTag(item);

        workspace.addInScreen(item.hostView, item.screen, item.cellX,
                item.cellY, item.spanX, item.spanY, false);

        workspace.requestLayout();

        mDesktopItems.add(item);

        if (DEBUG_WIDGETS) {
            Log.d(TAG, "bound widget id="+item.appWidgetId+" in "
                    + (SystemClock.uptimeMillis()-start) + "ms");
        }
    }

    /**
     * Callback saying that there aren't any more items to bind.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void finishBindingItems() {
        setLoadOnResume();

        if (mSavedState != null) {
            if (!mWorkspace.hasFocus()) {
                mWorkspace.getChildAt(mWorkspace.getCurrentScreen()).requestFocus();
            }

            final long[] userFolders = mSavedState.getLongArray(RUNTIME_STATE_USER_FOLDERS);
            if (userFolders != null) {
                for (long folderId : userFolders) {
                    final FolderInfo info = sFolders.get(folderId);
                    if (info != null) {
                        openFolder(info);
                    }
                }
                final Folder openFolder = mWorkspace.getOpenFolder();
                if (openFolder != null) {
                    openFolder.requestFocus();
                }
            }

            mSavedState = null;
        }

        if (mSavedInstanceState != null) {
            super.onRestoreInstanceState(mSavedInstanceState);
            mSavedInstanceState = null;
        }

        mWorkspaceLoading = false;

        bindPendingSearchWidget();
    }

    /**
     * Port: the default search widget couldn't be bound when the default workspace was
     * created, because a non-system launcher needs the user's permission. Ask for it once.
     */
    private void bindPendingSearchWidget() {
        final android.content.SharedPreferences prefs =
                getSharedPreferences(PENDING_SEARCH_WIDGET, MODE_PRIVATE);
        if (!prefs.contains("screen") || mPendingSearchWidgetId != -1 || mWaitingForResult) {
            return;
        }
        final ComponentName provider = new ComponentName(this,
                com.android.quicksearchbox.SearchWidgetProvider.class);
        mPendingSearchWidgetId = mAppWidgetHost.allocateAppWidgetId();
        if (mAppWidgetManager.bindAppWidgetIdIfAllowed(mPendingSearchWidgetId, provider)) {
            completeBindSearchWidget(true);
            return;
        }
        final Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mPendingSearchWidgetId);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
        try {
            startActivityForResult(intent, REQUEST_BIND_SEARCH_WIDGET);
        } catch (ActivityNotFoundException e) {
            completeBindSearchWidget(false);
        }
    }

    private void completeBindSearchWidget(boolean bound) {
        final android.content.SharedPreferences prefs =
                getSharedPreferences(PENDING_SEARCH_WIDGET, MODE_PRIVATE);
        final int appWidgetId = mPendingSearchWidgetId;
        mPendingSearchWidgetId = -1;
        if (bound && appWidgetId != -1) {
            final LauncherAppWidgetInfo info = new LauncherAppWidgetInfo(appWidgetId);
            info.spanX = 4;
            info.spanY = 1;
            // notify: the favorites observer reloads and binds the workspace
            LauncherModel.addItemToDatabase(this, info,
                    LauncherSettings.Favorites.CONTAINER_DESKTOP,
                    prefs.getInt("screen", 2) < SCREEN_COUNT // Tweaks: fewer screens
                            ? prefs.getInt("screen", 2) : DEFAULT_SCREEN,
                    prefs.getInt("x", 0), prefs.getInt("y", 0), true);
        } else if (appWidgetId != -1) {
            mAppWidgetHost.deleteAppWidgetId(appWidgetId);
        }
        prefs.edit().clear().apply();
    }

    /**
     * Add the icons for all apps.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void bindAllApplications(ArrayList<ApplicationInfo> apps) {
        mAllAppsGrid.setApps(filterHiddenApps(apps)); // Tweaks: filterHiddenApps
    }

    /**
     * A package was installed.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void bindAppsAdded(ArrayList<ApplicationInfo> apps) {
        setLoadOnResume();
        removeDialog(DIALOG_CREATE_SHORTCUT);
        mAllAppsGrid.addApps(filterHiddenApps(apps)); // Tweaks: filterHiddenApps
    }

    /**
     * A package was updated.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void bindAppsUpdated(ArrayList<ApplicationInfo> apps) {
        setLoadOnResume();
        removeDialog(DIALOG_CREATE_SHORTCUT);
        mWorkspace.updateShortcuts(apps);
        mAllAppsGrid.updateApps(filterHiddenApps(apps)); // Tweaks: filterHiddenApps
    }

    /**
     * A package was uninstalled.
     *
     * Implementation of the method from LauncherModel.Callbacks.
     */
    public void bindAppsRemoved(ArrayList<ApplicationInfo> apps, boolean permanent) {
        removeDialog(DIALOG_CREATE_SHORTCUT);
        if (permanent) {
            mWorkspace.removeItems(apps);
        }
        mAllAppsGrid.removeApps(apps);
    }

    /**
     * Prints out out state for debugging.
     */
    public void dumpState() {
        Log.d(TAG, "BEGIN launcher2 dump state for launcher " + this);
        Log.d(TAG, "mSavedState=" + mSavedState);
        Log.d(TAG, "mWorkspaceLoading=" + mWorkspaceLoading);
        Log.d(TAG, "mRestoring=" + mRestoring);
        Log.d(TAG, "mWaitingForResult=" + mWaitingForResult);
        Log.d(TAG, "mSavedInstanceState=" + mSavedInstanceState);
        Log.d(TAG, "mDesktopItems.size=" + mDesktopItems.size());
        Log.d(TAG, "sFolders.size=" + sFolders.size());
        mModel.dumpState();
        mAllAppsGrid.dumpState();
        Log.d(TAG, "END launcher2 dump state");
    }
}
