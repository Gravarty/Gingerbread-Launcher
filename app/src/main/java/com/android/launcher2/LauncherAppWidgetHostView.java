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

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.ViewAnimator;

import com.android.launcher.R;

/**
 * {@inheritDoc}
 */
public class LauncherAppWidgetHostView extends AppWidgetHostView {
    private boolean mHasPerformedLongPress;
    
    private CheckForLongPress mPendingCheckForLongPress;
    
    private LayoutInflater mInflater;
    
    public LauncherAppWidgetHostView(Context context) {
        super(context);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }
    
    /**
     * Port: since ICS the host view adds a default padding around every widget (8dp on
     * phones). Gingerbread had none, its widgets bring their margins in their graphics, so
     * the padding made them too small for their content.
     */
    @Override
    public void setAppWidget(int appWidgetId, android.appwidget.AppWidgetProviderInfo info) {
        super.setAppWidget(appWidgetId, info);
        setPadding(0, 0, 0, 0);
    }

    /**
     * Port: a widget that is rebuilt (e.g. its layout changes when it is resized across a
     * size class) would start its ViewFlipper at the first child again. Keep the position.
     */
    @Override
    public void updateAppWidget(RemoteViews remoteViews) {
        final ViewAnimator before = findAnimator(this);
        final int position = before == null ? -1 : before.getDisplayedChild();
        // Fix: stale fade animations kept removed stories drawn on top of the new ones
        if (before != null) clearStaleAnimations(before, -1);
        super.updateAppWidget(remoteViews);
        final ViewAnimator after = findAnimator(this);
        if (after != null && position > 0 && position < after.getChildCount()) {
            after.setDisplayedChild(position);
        }
        if (after != null) clearStaleAnimations(after, after.getDisplayedChild());
    }

    /**
     * Fix: while the launcher is in the background the ViewFlipper's fade animations don't run
     * to the end. When the launcher returns, the old story is still drawn over the current one.
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            final ViewAnimator a = findAnimator(this);
            if (a != null) clearStaleAnimations(a, a.getDisplayedChild());
        }
    }

    /** Clears the animations of all children but keep (-1: all); the others are hidden. */
    private static void clearStaleAnimations(ViewAnimator a, int keep) {
        for (int i = 0; i < a.getChildCount(); i++) {
            if (i == keep) continue;
            final View c = a.getChildAt(i);
            c.clearAnimation();
            if (keep >= 0) c.setVisibility(View.GONE);
        }
    }

    private static ViewAnimator findAnimator(View v) {
        if (v instanceof ViewAnimator) return (ViewAnimator) v;
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                final ViewAnimator a = findAnimator(g.getChildAt(i));
                if (a != null) return a;
            }
        }
        return null;
    }

    @Override
    protected View getErrorView() {
        return mInflater.inflate(R.layout.appwidget_error, this, false);
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Consume any touch events for ourselves after longpress is triggered
        if (mHasPerformedLongPress) {
            mHasPerformedLongPress = false;
            return true;
        }
            
        // Watch for longpress events at this level to make sure
        // users can always pick up this widget
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                postCheckForLongClick();
                break;
            }
            
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHasPerformedLongPress = false;
                if (mPendingCheckForLongPress != null) {
                    removeCallbacks(mPendingCheckForLongPress);
                }
                break;
        }
        
        // Otherwise continue letting touch events fall through to children
        return false;
    }
    
    class CheckForLongPress implements Runnable {
        private int mOriginalWindowAttachCount;

        public void run() {
            if ((getParent() != null) && hasWindowFocus()
                    && mOriginalWindowAttachCount == getWindowAttachCount()
                    && !mHasPerformedLongPress) {
                if (performLongClick()) {
                    mHasPerformedLongPress = true;
                }
            }
        }

        public void rememberWindowAttachCount() {
            mOriginalWindowAttachCount = getWindowAttachCount();
        }
    }

    private void postCheckForLongClick() {
        mHasPerformedLongPress = false;

        if (mPendingCheckForLongPress == null) {
            mPendingCheckForLongPress = new CheckForLongPress();
        }
        mPendingCheckForLongPress.rememberWindowAttachCount();
        postDelayed(mPendingCheckForLongPress, ViewConfiguration.getLongPressTimeout());
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();

        mHasPerformedLongPress = false;
        if (mPendingCheckForLongPress != null) {
            removeCallbacks(mPendingCheckForLongPress);
        }
    }
}
