/*
 * Port addition (not part of AOSP 2.3.7 Launcher2).
 *
 * Android 8.0+ replaced the INSTALL_SHORTCUT broadcast with ShortcutManager pin requests,
 * which are delivered to the default home app via ACTION_CONFIRM_PIN_SHORTCUT. Gingerbread
 * had no confirmation UI for INSTALL_SHORTCUT, so the request is accepted directly and handed
 * to the original InstallShortcutReceiver code path.
 */
package com.android.launcher2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

public class PinShortcutActivity extends Activity {
    private static final String TAG = "Launcher.PinShortcut";

    static final String ACTION_DEEP_SHORTCUT = "com.gravarty.launcher2.action.DEEP_SHORTCUT";
    static final String EXTRA_SHORTCUT_ID = "shortcut_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent data = acceptPinRequest(this, getIntent());
        if (data != null) {
            data.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
            new InstallShortcutReceiver().onReceive(this, data);
        }
        finish();
    }

    /**
     * If the intent carries a shortcut pin request, accepts it and returns a legacy
     * EXTRA_SHORTCUT_* intent describing it, otherwise returns null.
     */
    static Intent acceptPinRequest(Context context, Intent intent) {
        if (intent == null) {
            return null;
        }
        final LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        final LauncherApps.PinItemRequest request;
        try {
            request = launcherApps.getPinItemRequest(intent);
        } catch (RuntimeException e) {
            return null;
        }
        if (request == null || !request.isValid()
                || request.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            return null;
        }

        final ShortcutInfo shortcut = request.getShortcutInfo();
        if (shortcut == null || !request.accept()) {
            Log.w(TAG, "Pin request could not be accepted: " + shortcut);
            return null;
        }

        final Intent launch = new Intent(ACTION_DEEP_SHORTCUT)
                .setPackage(shortcut.getPackage())
                .putExtra(EXTRA_SHORTCUT_ID, shortcut.getId());

        CharSequence label = shortcut.getShortLabel();
        if (label == null) {
            label = shortcut.getLongLabel();
        }

        final Intent data = new Intent();
        data.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
        data.putExtra(Intent.EXTRA_SHORTCUT_NAME, label != null ? label.toString() : "");

        final Drawable icon = launcherApps.getShortcutIconDrawable(shortcut,
                context.getResources().getDisplayMetrics().densityDpi);
        if (icon != null) {
            data.putExtra(Intent.EXTRA_SHORTCUT_ICON, drawableToBitmap(icon));
        }
        return data;
    }

    static boolean isDeepShortcut(Intent intent) {
        return intent != null && ACTION_DEEP_SHORTCUT.equals(intent.getAction());
    }

    /** Starts a pinned shortcut. Throws like startActivity() if it can't be launched. */
    static void startDeepShortcut(Context context, Intent intent) {
        final LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        try {
            launcherApps.startShortcut(intent.getPackage(),
                    intent.getStringExtra(EXTRA_SHORTCUT_ID), intent.getSourceBounds(), null,
                    Process.myUserHandle());
        } catch (IllegalStateException e) {
            // Shortcut disabled, removed, or we're not the default home app.
            throw new android.content.ActivityNotFoundException(e.getMessage());
        }
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        final int w = Math.max(1, d.getIntrinsicWidth());
        final int h = Math.max(1, d.getIntrinsicHeight());
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }
}
