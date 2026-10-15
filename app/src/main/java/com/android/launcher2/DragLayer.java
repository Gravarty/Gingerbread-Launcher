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

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.util.ArrayList;

import com.android.launcher.R;

/**
 * A ViewGroup that coordinated dragging across its dscendants
 */
public class DragLayer extends FrameLayout {
    DragController mDragController;

    // Tweaks (from ICS): variables relating to resizing widgets
    private final ArrayList<AppWidgetResizeFrame> mResizeFrames =
            new ArrayList<AppWidgetResizeFrame>();
    private AppWidgetResizeFrame mCurrentResizeFrame;
    private int mXDown, mYDown;

    /**
     * Used to create a new DragLayer from XML.
     *
     * @param context The application's context.
     * @param attrs The attribtues set containing the Workspace's customization values.
     */
    public DragLayer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Port addition: apps are drawn edge-to-edge since Android 15, so keep the launcher
     * content (hotseat, workspace) clear of the status bar, navigation bar and cutouts.
     * The all apps view still fills the whole window (like the opaque drawer under the
     * black Gingerbread status bar) and only pads its content.
     */
    private final Rect mInsets = new Rect();

    /*
     * Tweaks: swipe up on the home screen (workspace or dock) opens all apps, the open
     * animation following the finger; swiping back down closes it again.
     */
    private boolean mAllAppsSwiping;
    private boolean mAllAppsSwipeCandidate;
    private float mSwipeDownX, mSwipeDownY;
    private android.view.VelocityTracker mSwipeVelocity;

    private boolean interceptAllAppsSwipe(MotionEvent ev) {
        if (!Launcher.SWIPE_UP_ALL_APPS) return false;
        final Launcher launcher = (Launcher) getContext();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mAllAppsSwiping = false;
                mAllAppsSwipeCandidate = launcher.getAllApps3D() != null
                        && !launcher.isAllAppsVisible() && !mDragController.isDragging()
                        && mCurrentResizeFrame == null;
                mSwipeDownX = ev.getX();
                mSwipeDownY = ev.getY();
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (!mAllAppsSwipeCandidate || mDragController.isDragging()) return false;
                final float dx = Math.abs(ev.getX() - mSwipeDownX);
                final float dy = mSwipeDownY - ev.getY();
                final int slop = android.view.ViewConfiguration.get(getContext())
                        .getScaledTouchSlop();
                if (dy > slop && dy > dx) {
                    mAllAppsSwiping = true;
                    mAllAppsSwipeCandidate = false;
                    mSwipeDownY -= slop; // start at 0 from here on
                    mSwipeVelocity = android.view.VelocityTracker.obtain();
                    mSwipeVelocity.addMovement(ev);
                    return true;
                }
                if (dx > slop || -dy > slop) mAllAppsSwipeCandidate = false;
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mAllAppsSwipeCandidate = false;
                return false;
        }
        return false;
    }

    private void allAppsSwipe(MotionEvent ev) {
        final AllApps3D allApps = ((Launcher) getContext()).getAllApps3D();
        if (allApps == null) return;
        mSwipeVelocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                allApps.dragZoom((mSwipeDownY - ev.getY()) / AllApps3D.gestureDistance(this));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mAllAppsSwiping = false;
                mSwipeVelocity.computeCurrentVelocity(1000);
                final float v = -mSwipeVelocity.getYVelocity(); // px/s, up > 0
                mSwipeVelocity.recycle();
                mSwipeVelocity = null;
                final float minFling = android.view.ViewConfiguration.get(getContext())
                        .getScaledMinimumFlingVelocity() * 4;
                final float zoom = (mSwipeDownY - ev.getY()) / AllApps3D.gestureDistance(this);
                final boolean open = ev.getActionMasked() == MotionEvent.ACTION_UP
                        && (v > minFling || (v > -minFling && zoom > 0.5f));
                allApps.settleZoom(open, Math.abs(v) / AllApps3D.gestureDistance(this));
                break;
            }
        }
    }
    private Rect mAllAppsBasePadding;

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        final Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout());
        mInsets.set(i.left, i.top, i.right, i.bottom);
        setPadding(i.left, i.top, i.right, i.bottom);

        final View allApps = findViewById(R.id.all_apps_view);
        if (allApps instanceof AllApps3D) {
            ((AllApps3D) allApps).setContentInsets(mInsets);
        } else if (allApps != null) {
            if (mAllAppsBasePadding == null) {
                mAllAppsBasePadding = new Rect(allApps.getPaddingLeft(), allApps.getPaddingTop(),
                        allApps.getPaddingRight(), allApps.getPaddingBottom());
            }
            allApps.setPadding(mAllAppsBasePadding.left + i.left,
                    mAllAppsBasePadding.top + i.top,
                    mAllAppsBasePadding.right + i.right,
                    mAllAppsBasePadding.bottom + i.bottom);
        }
        return WindowInsets.CONSUMED;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final View allApps = findViewById(R.id.all_apps_view);
        if (allApps != null && allApps.getVisibility() != GONE) {
            allApps.measure(
                    MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Tweaks (from ICS): custom positioned children (widget resize frames)
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            final FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) child.getLayoutParams();
            if (flp instanceof LayoutParams) {
                final LayoutParams lp = (LayoutParams) flp;
                if (lp.customPosition) {
                    child.layout(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height);
                }
            }
        }
        updateGestureExclusion(); // Port
        final View allApps = findViewById(R.id.all_apps_view);
        if (allApps != null && allApps.getVisibility() != GONE) {
            allApps.layout(0, 0, right - left, bottom - top);
        }
    }

    /**
     * Port: on the home screen the back gesture has nothing to do (back did nothing on
     * Gingerbread's home screen either), so keep it off like Launcher3's
     * LauncherRootView.setDisallowBackGesture(); it stays on for all apps and folders.
     */
    private boolean mDisallowBackGesture;

    void setDisallowBackGesture(boolean disallow) {
        if (mDisallowBackGesture != disallow) {
            mDisallowBackGesture = disallow;
            updateGestureExclusion();
        }
    }

    private void updateGestureExclusion() {
        setSystemGestureExclusionRects(mDisallowBackGesture
                ? java.util.Collections.singletonList(new Rect(0, 0, getWidth(), getHeight()))
                : java.util.Collections.<Rect>emptyList());
    }

    public void setDragController(DragController controller) {
        mDragController = controller;
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mDragController.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    // Tweaks (from ICS)
    private boolean handleTouchDown(MotionEvent ev) {
        Rect hitRect = new Rect();
        int x = (int) ev.getX();
        int y = (int) ev.getY();

        for (AppWidgetResizeFrame child: mResizeFrames) {
            child.getHitRect(hitRect);
            if (hitRect.contains(x, y)) {
                if (child.beginResizeIfPointInRegion(x - child.getLeft(), y - child.getTop())) {
                    mCurrentResizeFrame = child;
                    mXDown = x;
                    mYDown = y;
                    requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (interceptAllAppsSwipe(ev)) { // Tweaks
            return true;
        }
        // Tweaks (from ICS)
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (handleTouchDown(ev)) {
                return true;
            }
        }
        clearAllResizeFrames();
        return mDragController.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mAllAppsSwiping) { // Tweaks
            allAppsSwipe(ev);
            return true;
        }
        // Tweaks (from ICS)
        boolean handled = false;
        int action = ev.getAction();

        int x = (int) ev.getX();
        int y = (int) ev.getY();

        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (handleTouchDown(ev)) {
                return true;
            }
        }

        if (mCurrentResizeFrame != null) {
            handled = true;
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    mCurrentResizeFrame.visualizeResizeForDelta(x - mXDown, y - mYDown);
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    mCurrentResizeFrame.commitResizeForDelta(x - mXDown, y - mYDown);
                    mCurrentResizeFrame = null;
            }
        }
        if (handled) return true;
        return mDragController.onTouchEvent(ev);
    }

    // Tweaks (from ICS)
    public static class LayoutParams extends FrameLayout.LayoutParams {
        public int x, y;
        public boolean customPosition = false;

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getWidth() {
            return width;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public int getHeight() {
            return height;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getX() {
            return x;
        }

        public void setY(int y) {
            this.y = y;
        }

        public int getY() {
            return y;
        }
    }

    // Tweaks (from ICS)
    public void clearAllResizeFrames() {
        if (mResizeFrames.size() > 0) {
            for (AppWidgetResizeFrame frame: mResizeFrames) {
                removeView(frame);
            }
            mResizeFrames.clear();
        }
    }

    public void addResizeFrame(ItemInfo itemInfo, LauncherAppWidgetHostView widget,
            CellLayout cellLayout) {
        AppWidgetResizeFrame resizeFrame = new AppWidgetResizeFrame(getContext(),
                itemInfo, widget, cellLayout, this);

        LayoutParams lp = new LayoutParams(-1, -1);
        lp.customPosition = true;

        addView(resizeFrame, lp);
        mResizeFrames.add(resizeFrame);

        resizeFrame.snapToWidget(false);
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        return mDragController.dispatchUnhandledMove(focused, direction);
    }
}
