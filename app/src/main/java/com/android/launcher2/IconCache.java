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

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.util.HashMap;

/**
 * Cache of application icons.  Icons can be made from any thread.
 */
public class IconCache {
    private static final String TAG = "Launcher.IconCache";

    private static final int INITIAL_ICON_CACHE_CAPACITY = 50;

    private static class CacheEntry {
        public Bitmap packIcon; // Tweaks: icon from the icon pack (or the normal one)
        public boolean packLoaded;
        public Bitmap icon;
        public String title;
        public Bitmap titleBitmap;
    }

    private final Bitmap mDefaultIcon;
    private final LauncherApplication mContext;
    private final PackageManager mPackageManager;
    private Utilities.BubbleText mBubble; // Tweaks: not final, the font can change
    private final HashMap<ComponentName, CacheEntry> mCache =
            new HashMap<ComponentName, CacheEntry>(INITIAL_ICON_CACHE_CAPACITY);

    public IconCache(LauncherApplication context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mBubble = new Utilities.BubbleText(context);
        mDefaultIcon = makeDefaultIcon();
    }

    private Bitmap makeDefaultIcon() {
        Drawable d = mPackageManager.getDefaultActivityIcon();
        Bitmap b = Bitmap.createBitmap(Math.max(d.getIntrinsicWidth(), 1),
                Math.max(d.getIntrinsicHeight(), 1),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, b.getWidth(), b.getHeight());
        d.draw(c);
        return b;
    }

    /**
     * Remove any records for the supplied ComponentName.
     */
    public void remove(ComponentName componentName) {
        synchronized (mCache) {
            mCache.remove(componentName);
        }
    }

    /**
     * Empty out the cache.
     */
    public void flush() {
        synchronized (mCache) {
            mCache.clear();
            mBubble = new Utilities.BubbleText(mContext); // Tweaks: font setting
            mPack = null; // Tweaks: icon pack setting
        }
    }

    /**
     * Fill in "application" with the icon and label for "info."
     */
    public void getTitleAndIcon(ApplicationInfo application, ResolveInfo info) {
        synchronized (mCache) {
            CacheEntry entry = cacheLocked(application.componentName, info);
            if (entry.titleBitmap == null) {
                entry.titleBitmap = mBubble.createTextBitmap(entry.title);
            }

            application.title = entry.title;
            application.titleBitmap = entry.titleBitmap;
            application.iconBitmap = Launcher.ICON_PACK_EVERYWHERE // Tweaks
                    ? packIconLocked(application.componentName, entry) : entry.icon;
        }
    }

    public Bitmap getIcon(Intent intent) {
        synchronized (mCache) {
            final ResolveInfo resolveInfo = mPackageManager.resolveActivity(intent, 0);
            ComponentName component = intent.getComponent();

            if (resolveInfo == null || component == null) {
                return mDefaultIcon;
            }

            CacheEntry entry = cacheLocked(component, resolveInfo);
            return packIconLocked(component, entry); // Tweaks: home screen icon
        }
    }

    public Bitmap getIcon(ComponentName component, ResolveInfo resolveInfo) {
        synchronized (mCache) {
            if (resolveInfo == null || component == null) {
                return null;
            }

            CacheEntry entry = cacheLocked(component, resolveInfo);
            return packIconLocked(component, entry); // Tweaks: home screen icon
        }
    }

    /*
     * Tweaks: icon packs in the common ADW/Nova format (appfilter.xml mapping components
     * to drawables), e.g. Lawnicons. Apps the pack doesn't cover keep their icon. Used for
     * the home screen, and with "everywhere" for all apps too.
     */
    private HashMap<String, String> mPack; // flattened component -> drawable name
    private android.content.Context mPackContext;

    static final String[] ICON_PACK_ACTIONS = { "org.adw.launcher.THEMES",
            "com.novalauncher.THEME", "com.gau.go.launcherex.theme" };

    private Bitmap packIconLocked(ComponentName component, CacheEntry entry) {
        if (Launcher.ICON_PACK == null || component == null) {
            return entry.icon;
        }
        if (!entry.packLoaded) {
            entry.packLoaded = true;
            entry.packIcon = entry.icon;
            final Drawable d = loadPackDrawable(component);
            if (d != null) {
                entry.packIcon = Utilities.createIconBitmap(d, mContext);
            }
        }
        return entry.packIcon;
    }

    private Drawable loadPackDrawable(ComponentName component) {
        if (mPack == null) {
            mPack = new HashMap<String, String>();
            try {
                mPackContext = mContext.createPackageContext(Launcher.ICON_PACK, 0);
                readAppFilter(mPackContext, mPack);
            } catch (Exception e) {
                android.util.Log.w("Launcher.IconCache", "icon pack: " + e);
            }
        }
        final String name = mPack.get(component.flattenToString());
        if (name == null || mPackContext == null) return null;
        final android.content.res.Resources res = mPackContext.getResources();
        final int id = res.getIdentifier(name, "drawable", Launcher.ICON_PACK);
        if (id == 0) return null;
        try {
            return mPackContext.getDrawable(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static void readAppFilter(android.content.Context pack,
            HashMap<String, String> out) throws Exception {
        final android.content.res.Resources res = pack.getResources();
        final int id = res.getIdentifier("appfilter", "xml", pack.getPackageName());
        org.xmlpull.v1.XmlPullParser parser;
        java.io.InputStream in = null;
        if (id != 0) {
            parser = res.getXml(id);
        } else {
            in = res.getAssets().open("appfilter.xml");
            parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(in, null);
        }
        try {
            for (int type = parser.getEventType(); type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
                    type = parser.next()) {
                if (type != org.xmlpull.v1.XmlPullParser.START_TAG
                        || !"item".equals(parser.getName())) {
                    continue;
                }
                final String component = parser.getAttributeValue(null, "component");
                final String drawable = parser.getAttributeValue(null, "drawable");
                if (component == null || drawable == null
                        || !component.startsWith("ComponentInfo{")) {
                    continue;
                }
                final ComponentName cn = ComponentName.unflattenFromString(
                        component.substring(14, component.length() - 1));
                if (cn != null && !out.containsKey(cn.flattenToString())) {
                    out.put(cn.flattenToString(), drawable);
                }
            }
        } finally {
            if (in != null) in.close();
        }
    }

    public boolean isDefaultIcon(Bitmap icon) {
        return mDefaultIcon == icon;
    }

    private CacheEntry cacheLocked(ComponentName componentName, ResolveInfo info) {
        CacheEntry entry = mCache.get(componentName);
        if (entry == null) {
            entry = new CacheEntry();

            mCache.put(componentName, entry);

            entry.title = info.loadLabel(mPackageManager).toString();
            if (entry.title == null) {
                entry.title = info.activityInfo.name;
            }
            entry.icon = Utilities.createIconBitmap(
                    info.activityInfo.loadIcon(mPackageManager), mContext);
        }
        return entry;
    }
}
