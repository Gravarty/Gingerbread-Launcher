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

import com.android.launcher.R;

/**
 * A ViewGroup that coordinated dragging across its dscendants
 */
public class DragLayer extends FrameLayout {
    DragController mDragController;

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
    private Rect mAllAppsBasePadding;

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        final Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout());
        mInsets.set(i.left, i.top, i.right, i.bottom);
        setPadding(i.left, i.top, i.right, i.bottom);

        final View allApps = findViewById(R.id.all_apps_view);
        if (allApps != null) {
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
        final View allApps = findViewById(R.id.all_apps_view);
        if (allApps != null && allApps.getVisibility() != GONE) {
            allApps.layout(0, 0, right - left, bottom - top);
        }
    }

    public void setDragController(DragController controller) {
        mDragController = controller;
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mDragController.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mDragController.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mDragController.onTouchEvent(ev);
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        return mDragController.dispatchUnhandledMove(focused, direction);
    }
}
