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

package com.android.launcher2;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.android.launcher.R;

/**
 * Tweaks: Gingerbread-style replacement for the system pickers (ACTION_PICK_ACTIVITY,
 * ACTION_APPWIDGET_PICK, the wallpaper chooser). Those are drawn by the system with today's
 * design and can't be themed by the launcher. This is the same list dialog as "Add to Home"
 * (same layout, same inverse background), filled with icon + label rows.
 */
final class PickerDialog {

    /** Loads an icon when its row is shown for the first time (an app list can be long). */
    interface IconSource {
        Drawable load();
    }

    /** What happens when a row was clicked. */
    interface Listener {
        void onPicked(Entry entry);
    }

    static final class Entry {
        final CharSequence label;
        /** What the caller needs to continue: a ComponentName, an AppWidgetProviderInfo, ... */
        final Object tag;
        private final IconSource mIconSource;
        private Drawable mIcon;
        private boolean mIconLoaded;

        Entry(CharSequence label, Drawable icon, Object tag) {
            this.label = label;
            this.tag = tag;
            mIconSource = null;
            mIcon = icon;
            mIconLoaded = true;
        }

        Entry(CharSequence label, IconSource iconSource, Object tag) {
            this.label = label;
            this.tag = tag;
            mIconSource = iconSource;
        }

        Drawable getIcon() {
            if (!mIconLoaded) {
                mIconLoaded = true;
                try {
                    mIcon = mIconSource != null ? mIconSource.load() : null;
                } catch (RuntimeException e) {
                    mIcon = null;
                }
            }
            return mIcon;
        }
    }

    private PickerDialog() {
    }

    /** Sorts by label (case-insensitive), like the system pickers do. */
    static void sortByLabel(List<Entry> entries) {
        Collections.sort(entries, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                return String.CASE_INSENSITIVE_ORDER.compare(
                        String.valueOf(a.label), String.valueOf(b.label));
            }
        });
    }

    /**
     * Creates the dialog, it isn't shown yet. The caller sets the cancel/dismiss listeners.
     * The dialog closes itself after a click, then {@link Listener#onPicked} is called.
     */
    static GbDialog create(Context context, CharSequence title, List<Entry> entries,
            final Listener listener) {
        final ArrayList<Entry> items = new ArrayList<Entry>(entries);
        final EntryAdapter adapter = new EntryAdapter(context, items);

        final GbDialog.Builder builder = new GbDialog.Builder(context);
        builder.setTitle(title);
        builder.setAdapter(adapter, (d, which) -> {
            if (which >= 0 && which < items.size()) {
                listener.onPicked(items.get(which));
            }
        });
        return builder.create();
    }

    private static final class EntryAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;
        private final List<Entry> mItems;

        EntryAdapter(Context context, List<Entry> items) {
            mInflater = LayoutInflater.from(GbDialog.themed(context));
            mItems = items;
        }

        public int getCount() {
            return mItems.size();
        }

        public Object getItem(int position) {
            return mItems.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.gb_picker_item, parent, false);
            }
            final Entry entry = mItems.get(position);
            ((TextView) convertView.findViewById(R.id.picker_text)).setText(entry.label);
            // 48dip, fitCenter (see gb_picker_item.xml): app icons come in all sizes
            ((ImageView) convertView.findViewById(R.id.picker_icon)).setImageDrawable(
                    entry.getIcon());
            return convertView;
        }
    }
}
