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
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.ViewConfiguration;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.RelativeLayout;

import java.util.ArrayList;
import java.util.Collections;

import com.android.launcher.R;

public class AllApps2D
        extends RelativeLayout
        implements AllAppsView,
                   AdapterView.OnItemClickListener,
                   AdapterView.OnItemLongClickListener,
                   View.OnKeyListener,
                   DragSource {

    private static final String TAG = "Launcher.AllApps2D";
    private static final boolean DEBUG = false;

    private Launcher mLauncher;
    private DragController mDragController;

    private GridView mGrid;

    private ArrayList<ApplicationInfo> mAllAppsList = new ArrayList<ApplicationInfo>();

    // preserve compatibility with 3D all apps:
    //    0.0 -> hidden
    //    1.0 -> shown and opaque
    //    intermediate values -> partially shown & partially opaque
    private float mZoom;

    private AppsAdapter mAppsAdapter;

    // ------------------------------------------------------------
    
    public static class HomeButton extends ImageButton {
        public HomeButton(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
        @Override
        public View focusSearch(int direction) {
            if (direction == FOCUS_UP) return super.focusSearch(direction);
            return null;
        }
    }

    public class AppsAdapter extends ArrayAdapter<ApplicationInfo> {
        private final LayoutInflater mInflater;

        public AppsAdapter(Context context, ArrayList<ApplicationInfo> apps) {
            super(context, 0, apps);
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ApplicationInfo info = getItem(position);

            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.application_boxed, parent, false);
                // Port: the icons are larger today (Utilities.iconSizeDp()): the cell grows
                // by the extra icon height, the label keeps its room
                final int texture = Utilities.iconTextureSize(getContext());
                final int extra = texture
                        - Math.round(texture / Utilities.iconScale(getContext()));
                convertView.getLayoutParams().height += Math.max(0, extra);
            }

//            if (!info.filtered) {
//                info.icon = Utilities.createIconThumbnail(info.icon, getContext());
//                info.filtered = true;
//            }

            final TextView textView = (TextView) convertView;
            if (DEBUG) {
                Log.d(TAG, "icon bitmap = " + info.iconBitmap 
                    + " density = " + info.iconBitmap.getDensity());
            }
            info.iconBitmap.setDensity(Bitmap.DENSITY_NONE);
            textView.setCompoundDrawablesWithIntrinsicBounds(null, new BitmapDrawable(info.iconBitmap), null, null);
            textView.setText(info.title);

            return convertView;
        }
    }

    public AllApps2D(Context context, AttributeSet attrs) {
        super(context, attrs);
        setVisibility(View.GONE);
        setSoundEffectsEnabled(false);

        mAppsAdapter = new AppsAdapter(getContext(), mAllAppsList);
        mAppsAdapter.setNotifyOnChange(false);
    }

    @Override
    protected void onFinishInflate() {
        setBackgroundColor(Color.BLACK);

        try {
            mGrid = (GridView)findViewWithTag("all_apps_2d_grid");
            if (mGrid == null) throw new Resources.NotFoundException();
            mGrid.setOnItemClickListener(this);
            mGrid.setOnItemLongClickListener(this);
            mGrid.setBackgroundColor(Color.BLACK);
            mGrid.setCacheColorHint(Color.BLACK);
            // Tweaks: columns from the launcher settings (0 = the layout's)
            final int columns = getContext().getSharedPreferences(
                    Launcher.TWEAKS_PREFERENCES, Context.MODE_PRIVATE)
                    .getInt(Launcher.TWEAK_DRAWER_COLUMNS, 0);
            if (columns > 0) mGrid.setNumColumns(columns);
            
            ImageButton homeButton = (ImageButton) findViewWithTag("all_apps_2d_home");
            if (homeButton == null) throw new Resources.NotFoundException();
            mHomeButton = homeButton; // Tweaks
            homeButton.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        mLauncher.closeAllApps(true);
                    }
                });
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Can't find necessary layout elements for AllApps2D");
        }

        setOnKeyListener(this);
    }

    public AllApps2D(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs);
    }

    public void setLauncher(Launcher launcher) {
        mLauncher = launcher;
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (!isVisible()) return false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                mLauncher.closeAllApps(true);
                break;
            default:
                return false;
        }

        return true;
    }

    public void onItemClick(AdapterView parent, View v, int position, long id) {
        ApplicationInfo app = (ApplicationInfo) parent.getItemAtPosition(position);
        mLauncher.startActivitySafely(app.intent, app);
    }

    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (!view.isInTouchMode()) {
            return false;
        }

        ApplicationInfo app = (ApplicationInfo) parent.getItemAtPosition(position);
        app = new ApplicationInfo(app);

        mDragController.startDrag(view, this, app, DragController.DRAG_ACTION_COPY);
        mLauncher.closeAllApps(true);

        return true;
    }

    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect prev) {
        if (gainFocus) {
            mGrid.requestFocus();
        }
    }

    public void setDragController(DragController dragger) {
        mDragController = dragger;
    }

    public void onDropCompleted(View target, boolean success) {
    }

    /**
     * Zoom to the specifed level.
     *
     * @param zoom [0..1] 0 is hidden, 1 is open
     */
    public void zoom(float zoom, boolean animate) {
//        Log.d(TAG, "zooming " + ((zoom == 1.0) ? "open" : "closed"));
        cancelLongPress();

        if (mDragged) { // Tweaks: a gesture moved the drawer, continue from there
            settle(zoom, animate);
            return;
        }

        mZoom = zoom;

        if (isVisible()) {
            getParent().bringChildToFront(this);
            setVisibility(View.VISIBLE);
            updateHomeButton(); // Tweaks
            mGrid.setAdapter(mAppsAdapter);
            if (animate) {
                startFade(R.anim.all_apps_2d_fade_in); // Tweaks: system animation speed
            } else {
                onAnimationEnd();
            }
        } else {
            if (animate) {
                startFade(R.anim.all_apps_2d_fade_out); // Tweaks: system animation speed
            } else {
                onAnimationEnd();
            }
        }
    }

    /**
     * Tweaks: the stock fade with the system's animation speed (developer options), like the
     * 3D drawer (Utilities.scaleDuration()); view animations don't follow it on their own,
     * the settling after a gesture (ViewPropertyAnimator) does.
     */
    private void startFade(int anim) {
        final android.view.animation.Animation a =
                AnimationUtils.loadAnimation(getContext(), anim);
        a.setDuration(Utilities.scaleDuration((int) a.getDuration()));
        startAnimation(a);
    }

    // Tweaks: gestures like the 3D drawer (swipe up on the home screen opens the drawer,
    // swipe down at the top of the list closes it, both following the finger). The 2D drawer
    // fades in and out (all_apps_2d_fade_in/out), so a gesture moves its alpha.
    private View mHomeButton;
    private int[] mGridMargins; // the layout's: right, bottom
    private boolean mDragged;
    private float mNextSpeed; // zoom per second for the settling after a gesture

    /** Tweaks: no home button when the dock has no all apps button either. */
    private void updateHomeButton() {
        if (mHomeButton != null) {
            mHomeButton.setVisibility(Launcher.sDockAllAppsHidden ? View.GONE : View.VISIBLE);
        }
        // without it the grid takes the room the layout keeps free for it: below it in
        // portrait (layout_marginBottom), right of it in landscape (layout_marginRight)
        if (mGrid != null) {
            final ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mGrid.getLayoutParams();
            if (mGridMargins == null) {
                mGridMargins = new int[] { lp.rightMargin, lp.bottomMargin };
            }
            final boolean hidden = Launcher.sDockAllAppsHidden;
            final int right = hidden ? 0 : mGridMargins[0];
            final int bottom = hidden ? 0 : mGridMargins[1];
            if (lp.rightMargin != right || lp.bottomMargin != bottom) {
                lp.rightMargin = right;
                lp.bottomMargin = bottom;
                mGrid.setLayoutParams(lp);
            }
        }
    }

    /** Tweaks: the drawer at the zoom of a gesture (0 hidden, 1 open). */
    void dragZoom(float zoom) {
        zoom = Math.max(0, Math.min(1, zoom));
        cancelLongPress();
        animate().cancel();
        clearAnimation();
        if (!mDragged && !isVisible()) {
            getParent().bringChildToFront(this);
            setVisibility(View.VISIBLE);
            updateHomeButton();
            mGrid.setAdapter(mAppsAdapter);
        }
        mDragged = true;
        // stays "visible" at 0 so that closeAllApps() still closes it (isVisible())
        mZoom = Math.max(zoom, 0.002f);
        setAlpha(zoom);
    }

    /** Tweaks: finger released: open or close, continuing with speed (zoom per second). */
    void settleZoom(boolean open, float speed) {
        mNextSpeed = speed;
        if (open) {
            mLauncher.showAllApps(true);
        } else {
            mLauncher.closeAllApps(true);
        }
        mNextSpeed = 0;
    }

    private void settle(final float zoom, boolean animate) {
        mZoom = zoom;
        final float target = zoom > 0.5f ? 1f : 0f;
        // at least as fast as the stock fade (all_apps_2d_fade_in/out)
        final float fadeTime = getResources().getInteger(target > 0
                ? R.integer.config_allAppsFadeInTime : R.integer.config_allAppsFadeOutTime);
        final float speed = Math.max(1000f / fadeTime, mNextSpeed);
        final long duration = animate
                ? (long) (Math.abs(target - getAlpha()) / speed * 1000) : 0;
        animate().alpha(target).setDuration(duration)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    mDragged = false;
                    setAlpha(1f); // the stock fades animate on top of a full alpha
                    onAnimationEnd();
                });
    }

    private boolean mCloseDragging;
    private boolean mCloseAllowed;
    private float mCloseDownX, mCloseDownY;
    private android.view.VelocityTracker mCloseVelocity;

    /** Tweaks: swipe down at the top of the list closes the drawer (see AllApps3D). */
    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        if (!Launcher.SWIPE_UP_ALL_APPS || !isVisible() || mDragged) return false;
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                mCloseDragging = false;
                mCloseDownX = ev.getRawX();
                mCloseDownY = ev.getRawY();
                // only at the top of the list
                final View first = mGrid.getChildAt(0);
                mCloseAllowed = first == null || (mGrid.getFirstVisiblePosition() == 0
                        && first.getTop() >= mGrid.getPaddingTop());
                return false;
            }
            case android.view.MotionEvent.ACTION_MOVE: {
                final float dy = ev.getRawY() - mCloseDownY;
                final float dx = Math.abs(ev.getRawX() - mCloseDownX);
                final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (mCloseAllowed && dy > slop && dy > dx) {
                    mCloseDragging = true;
                    mCloseDownY += slop; // start at 0 from here on
                    mCloseVelocity = android.view.VelocityTracker.obtain();
                    mCloseVelocity.addMovement(ev);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (!mCloseDragging) return super.onTouchEvent(ev);
        mCloseVelocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_MOVE:
                dragZoom(1f - Math.max(0, ev.getRawY() - mCloseDownY)
                        / AllApps3D.gestureDistance(this));
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                mCloseDragging = false;
                mCloseVelocity.computeCurrentVelocity(1000);
                final float v = mCloseVelocity.getYVelocity(); // px/s, down > 0
                mCloseVelocity.recycle();
                mCloseVelocity = null;
                final float minFling =
                        ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity() * 4;
                final boolean close = ev.getActionMasked() == android.view.MotionEvent.ACTION_UP
                        && (v > minFling || (v > -minFling && mZoom < 0.5f));
                if (!mDragged) break; // never moved past the slop
                settleZoom(!close, Math.abs(v) / AllApps3D.gestureDistance(this));
                break;
            }
        }
        return true;
    }

    protected void onAnimationEnd() {
        if (!isVisible()) {
            setVisibility(View.GONE);
            mGrid.setAdapter(null);
            mZoom = 0.0f;
        } else {
            mZoom = 1.0f;
        }

        mLauncher.zoomed(mZoom);
    }

    public boolean isVisible() {
        return mZoom > 0.001f;
    }

    @Override
    public boolean isOpaque() {
        return mZoom > 0.999f;
    }

    public void setApps(ArrayList<ApplicationInfo> list) {
        mAllAppsList.clear();
        addApps(list);
    }

    public void addApps(ArrayList<ApplicationInfo> list) {
//        Log.d(TAG, "addApps: " + list.size() + " apps: " + list.toString());

        final int N = list.size();

        for (int i=0; i<N; i++) {
            final ApplicationInfo item = list.get(i);
            int index = Collections.binarySearch(mAllAppsList, item,
                    LauncherModel.APP_NAME_COMPARATOR);
            if (index < 0) {
                index = -(index+1);
            }
            mAllAppsList.add(index, item);
        }
        mAppsAdapter.notifyDataSetChanged();
    }

    public void removeApps(ArrayList<ApplicationInfo> list) {
        final int N = list.size();
        for (int i=0; i<N; i++) {
            final ApplicationInfo item = list.get(i);
            int index = findAppByComponent(mAllAppsList, item);
            if (index >= 0) {
                mAllAppsList.remove(index);
            } else {
                Log.w(TAG, "couldn't find a match for item \"" + item + "\"");
                // Try to recover.  This should keep us from crashing for now.
            }
        }
        mAppsAdapter.notifyDataSetChanged();
    }

    public void updateApps(ArrayList<ApplicationInfo> list) {
        // Just remove and add, because they may need to be re-sorted.
        removeApps(list);
        addApps(list);
    }

    private static int findAppByComponent(ArrayList<ApplicationInfo> list, ApplicationInfo item) {
        ComponentName component = item.intent.getComponent();
        final int N = list.size();
        for (int i=0; i<N; i++) {
            ApplicationInfo x = list.get(i);
            if (x.intent.getComponent().equals(component)) {
                return i;
            }
        }
        return -1;
    }

    public void dumpState() {
        ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList", mAllAppsList);
    }
    
    public void surrender() {
    }
}


