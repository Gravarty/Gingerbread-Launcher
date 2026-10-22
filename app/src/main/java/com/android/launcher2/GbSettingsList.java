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
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.launcher.R;

/**
 * Tweaks: the Gingerbread settings screen (title bar, categories, list rows with divider) for
 * the launcher settings and the weather widget settings.
 */
public final class GbSettingsList {
    private final Activity mActivity;
    private final LinearLayout mList;
    private boolean mLastWasItem;

    public GbSettingsList(Activity activity, int title) {
        mActivity = activity;
        activity.setContentView(R.layout.settings);
        setTitle(title);
        mList = (LinearLayout) activity.findViewById(R.id.settings_list);
    }

    public void setTitle(int title) {
        ((TextView) mActivity.findViewById(R.id.settings_title)).setText(title);
    }

    /** The screen's vertical root, e.g. to put a button bar below the list. */
    public LinearLayout getRoot() {
        return (LinearLayout) mList.getParent().getParent().getParent();
    }

    public void addCategory(int title) {
        final TextView v = (TextView) mActivity.getLayoutInflater().inflate(
                R.layout.settings_category, mList, false);
        v.setText(title);
        mList.addView(v);
        mLastWasItem = false;
    }

    /** Row with divider (no divider next to a category), the summary stays hidden when empty. */
    public View addItem(int title, CharSequence summary) {
        if (mLastWasItem) {
            final View divider = new View(mActivity);
            divider.setBackgroundResource(R.drawable.gb_divider_horizontal_dark_opaque);
            mList.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        final View v = createItem(mList, mActivity.getString(title), summary);
        mList.addView(v);
        mLastWasItem = true;
        return v;
    }

    /** A number setting as a slider with one step per label (the value shows right of the title). */
    public void addSlider(int title, final CharSequence[] labels, int index,
            final java.util.function.IntConsumer onChange) {
        if (mLastWasItem) {
            final View divider = new View(mActivity);
            divider.setBackgroundResource(R.drawable.gb_divider_horizontal_dark_opaque);
            mList.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        final View v = mActivity.getLayoutInflater().inflate(R.layout.settings_slider, mList,
                false);
        ((TextView) v.findViewById(R.id.settings_slider_title)).setText(title);
        final TextView value = (TextView) v.findViewById(R.id.settings_slider_value);
        value.setText(labels[index]);
        final SeekBar bar = (SeekBar) v.findViewById(R.id.settings_slider_bar);
        bar.setMax(labels.length - 1);
        bar.setProgress(index);
        // step markers where the thumb stops (the original seek bar has none): the thumb
        // centre moves between half a thumb minus the thumb offset inside the track ends
        final Drawable track = bar.getProgressDrawable().mutate();
        if (track instanceof LayerDrawable && labels.length > 1) {
            ((LayerDrawable) track).addLayer(new StepDots(labels.length,
                    2 * mActivity.getResources().getDisplayMetrics().density,
                    bar.getThumb().getIntrinsicWidth() / 2 - bar.getThumbOffset()));
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(labels[progress]);
                if (fromUser) onChange.accept(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        mList.addView(v);
        mLastWasItem = true;
    }

    /** A group of rows that brings its own dividers (the dock list). */
    public void addGroup(View group) {
        mList.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        mLastWasItem = true;
    }

    /** A row that isn't added yet. */
    public View createItem(ViewGroup parent, CharSequence title, CharSequence summary) {
        final View v = mActivity.getLayoutInflater().inflate(R.layout.settings_preference,
                parent, false);
        ((TextView) v.findViewById(R.id.settings_item_title)).setText(title);
        setSummary((TextView) v.findViewById(R.id.settings_item_summary), summary);
        return v;
    }

    public static void setSummary(TextView view, CharSequence summary) {
        view.setText(summary);
        view.setVisibility(summary == null || summary.length() == 0
                ? View.GONE : View.VISIBLE);
    }

    /** Dots at the steps of the slider, on top of the seek bar track. */
    private static final class StepDots extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mSteps;
        private final float mRadius;
        private final int mInset;

        StepDots(int steps, float radius, int inset) {
            mSteps = steps;
            mRadius = radius;
            mInset = inset;
            mPaint.setColor(0x66000000);
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect b = getBounds();
            final float cy = b.exactCenterY();
            final float width = b.width() - 2 * mInset;
            for (int i = 0; i < mSteps; i++) {
                canvas.drawCircle(b.left + mInset + width * i / (float) (mSteps - 1), cy, mRadius,
                        mPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha * 0x66 / 255);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
