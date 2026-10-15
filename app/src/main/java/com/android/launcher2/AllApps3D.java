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

/*
 * Port notes (AOSP 2.3.7 -> modern Android):
 *
 * The original rendered through RenderScriptGL (RSSurfaceView + res/raw/allapps.rs), which
 * was removed from Android. This version is a GLSurfaceView with OpenGL ES:
 *  - RolloRS keeps its API; the RS allocations/invokables became commands queued to the GL
 *    thread in the same order, and Script is a line-by-line port of allapps.rs.
 *  - The vertex shader (PVCurve), the fixed function fragment programs, the program store
 *    and RS's setupProjectionNormalized() projection are reproduced exactly.
 *  - All layout constants are in pixels of the original 480px wide hdpi screen. They are
 *    kept and drawn in a "virtual" coordinate space at the original physical size
 *    (1 virtual px = 1 hdpi px), inside the area not covered by system bars.
 *  - Only the parts that depended on the Nexus One screen are generalised: the 4 (6 in
 *    landscape) columns are spread over the width, rows follow the screen height (4 at
 *    the original 480x762), the bottom bend sits at the same distance from the edge, and
 *    touch drag is normalised to the original screen height so scrolling still follows
 *    the finger. Texture magnification is linear since textures are no longer drawn 1:1.
 */

package com.android.launcher2;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.SurfaceHolder;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.android.launcher.R;

public class AllApps3D extends GLSurfaceView
        implements AllAppsView, View.OnClickListener, View.OnLongClickListener, DragSource {
    private static final String TAG = "Launcher.AllApps3D";

    /** Bit for mLocks for when there are icons being loaded. */
    private static final int LOCK_ICONS_PENDING = 1;

    private static final int TRACKING_NONE = 0;
    private static final int TRACKING_FLING = 1;
    private static final int TRACKING_HOME = 2;

    private static final int SELECTED_NONE = 0;
    private static final int SELECTED_FOCUSED = 1;
    private static final int SELECTED_PRESSED = 2;

    private static final int SELECTION_NONE = 0;
    private static final int SELECTION_ICONS = 1;
    private static final int SELECTION_HOME = 2;

    private Launcher mLauncher;
    private DragController mDragController;

    /** When this is 0, modifications are allowed, when it's not, they're not.
     * TODO: What about scrolling? */
    private int mLocks = LOCK_ICONS_PENDING;

    private int mSlop;
    private int mMaxFlingVelocity;

    private ArrayList<ApplicationInfo> mAllAppsList;

    // Port: was static and shared across activity instances (RS context reuse).
    private RolloRS sRollo;

    private static boolean sZoomDirty = false;
    private static boolean sAnimateNextZoom;
    private static float sNextZoom;

    /**
     * True when we are using arrow keys or trackball to drive navigation
     */
    private boolean mArrowNavigation = false;
    private boolean mStartedScrolling;

    /**
     * Used to keep track of the selection when AllAppsView loses window focus.
     * One of the SELECTION_ constants.
     */
    private int mLastSelection;

    /**
     * Used to keep track of the selection when AllAppsView loses window focus
     */
    private int mLastSelectedIcon;

    private VelocityTracker mVelocityTracker;
    private int mTouchTracking;
    private int mMotionDownRawX;
    private int mMotionDownRawY;
    private int mDownIconIndex = -1;
    private int mCurrentIconIndex = -1;
    private int[] mTouchYBorders;
    private int[] mTouchXBorders;

    private boolean mShouldGainFocus;

    private boolean mHaveSurface = false;
    private float mZoom;
    private float mVelocity;

    private int mColumnsPerPage;
    private int mRowsPerPage;
    private boolean mSurrendered;

    private int mRestoreFocusIndex = -1;

    /** Port: system bar insets; the drawer is drawn inside them. */
    private final Rect mContentInsets = new Rect();
    /** Port: current layout, see {@link Layout}. */
    private Layout mLayout;

    @SuppressWarnings({"UnusedDeclaration"})
    static class Defines {
        public static final int COLUMNS_PER_PAGE_PORTRAIT = 4;
        public static final int ROWS_PER_PAGE_PORTRAIT = 4;

        public static final int COLUMNS_PER_PAGE_LANDSCAPE = 6;
        public static final int ROWS_PER_PAGE_LANDSCAPE = 3;

        public static final int ICON_WIDTH_PX = 64;
        public static final int ICON_TEXTURE_WIDTH_PX = 74;
        public static final int SELECTION_TEXTURE_WIDTH_PX = 74 + 20;

        public static final int ICON_HEIGHT_PX = 64;
        public static final int ICON_TEXTURE_HEIGHT_PX = 74;
        public static final int SELECTION_TEXTURE_HEIGHT_PX = 74 + 20;
    }

    /**
     * Port: geometry of the drawer in the original 480px based coordinates ("virtual px"),
     * plus where that space sits on this screen.
     */
    static final class Layout {
        // Original Nexus One drawer height below the status bar, used to scale touch drags.
        private static final float REF_HEIGHT_PORTRAIT = 762f;
        private static final float REF_HEIGHT_LANDSCAPE = 442f;

        final boolean portrait;
        final float k;              // screen px per virtual px
        final float w, h;           // virtual size of the icon grid area
        final Rect content;         // drawer content area in view px
        final Rect grid;            // icon grid area in view px
        final float contentW, contentH; // virtual size of the content area (home button)
        final int surfaceHeight;    // view px
        final int cols, rows;
        final float colWidth;       // virtual px, 120 on the original screen
        final float rowHeight;
        final float bendY1, bendY2;
        final float yoff;           // drawFrontGrid() row 0 position
        final float touchCenterY;   // initTouchState() center, virtual px from the top
        final float refHeightPx;    // touch drag normalisation

        Layout(int viewWidth, int viewHeight, Rect insets, float density,
                int tweakCols, int tweakRows) {
            content = new Rect(insets.left, insets.top,
                    Math.max(insets.left + 1, viewWidth - insets.right),
                    Math.max(insets.top + 1, viewHeight - insets.bottom));
            surfaceHeight = viewHeight;
            final int cw = content.width();
            final int ch = content.height();
            portrait = cw < ch;

            // One virtual px is one px at the Nexus One's hdpi density (1.5), so icons and
            // labels keep their original physical size; the columns fill the width.
            float kk = density / 1.5f;
            grid = new Rect(content);

            if (portrait) {
                // 4 columns spread over the width (colWidth = getWidth() / 4, as the
                // original's commented out alternative to 120), icons at original size.
                final int c = tweakCols > 0 ? tweakCols // Tweaks
                        : Defines.COLUMNS_PER_PAGE_PORTRAIT;
                // Tweaks: scale down if the columns would be narrower than the original 120
                kk = Math.min(kk, cw / (120.f * c));
                int gh = ch;
                if (tweakRows > 0) {
                    // Tweaks: fixed rows; h = rows * 145 + 180 keeps the original bends
                    // (120 / h - 80, 760 for 4 rows). Scaled down to fit, centered.
                    kk = Math.min(kk, ch / (tweakRows * 145.f + 180.f));
                    gh = Math.round((tweakRows * 145.f + 180.f) * kk);
                    grid.top = content.top + (ch - gh) / 2;
                    grid.bottom = grid.top + gh;
                }
                k = kk;
                cols = c;
                w = cw / k;
                h = gh / k;
                colWidth = w / cols;
                rowHeight = 120.f + 25.f;
                bendY1 = 120.f;
                bendY2 = h - 80.f;                      // 680 at the original 760
                touchCenterY = h * 0.5f;
                refHeightPx = REF_HEIGHT_PORTRAIT * k;
            } else {
                k = kk;
                w = cw / k;
                h = ch / k;
                cols = Defines.COLUMNS_PER_PAGE_LANDSCAPE;
                colWidth = (w - 80.f) / cols;           // 120 at the original 800
                rowHeight = 120.f + 25.f - 12.f;
                bendY1 = 40.f;
                bendY2 = h - 40.f;
                touchCenterY = h * 0.47f;
                refHeightPx = REF_HEIGHT_LANDSCAPE * k;
            }
            contentW = cw / k;
            contentH = ch / k;
            // 4 rows portrait / 3 rows landscape on the original screen
            rows = (portrait && tweakRows > 0) ? tweakRows // Tweaks
                    : Math.max(1, Math.round((bendY2 - bendY1) / rowHeight));
            // 0.5h + 1.5 rows portrait, 0.47h + 1.0 row landscape on the original screen
            yoff = (portrait ? 0.5f : 0.47f) * h + ((rows - 1) * 0.5f) * rowHeight;
        }
    }

    public AllApps3D(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setSoundEffectsEnabled(false);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        final ViewConfiguration config = ViewConfiguration.get(context);
        mSlop = config.getScaledTouchSlop();
        mMaxFlingVelocity = config.getScaledMaximumFlingVelocity();

        setOnClickListener(this);
        setOnLongClickListener(this);
        setZOrderOnTop(true);

        // Port: replaces createRenderScript(true)
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setPreserveEGLContextOnPause(true);
        sRollo = new RolloRS(this);
        setRenderer(sRollo.mRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        final android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        final boolean isPortrait = metrics.widthPixels < metrics.heightPixels;
        mColumnsPerPage = isPortrait ? Defines.COLUMNS_PER_PAGE_PORTRAIT :
                Defines.COLUMNS_PER_PAGE_LANDSCAPE;
        mRowsPerPage = isPortrait ? Defines.ROWS_PER_PAGE_PORTRAIT :
                Defines.ROWS_PER_PAGE_LANDSCAPE;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public AllApps3D(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs);
    }

    public void surrender() {
        mSurrendered = true;
    }

    /** Port: system bar insets from DragLayer. */
    void setContentInsets(Rect insets) {
        if (!mContentInsets.equals(insets)) {
            mContentInsets.set(insets);
            if (mHaveSurface && getWidth() > 0) {
                updateLayout(getWidth(), getHeight());
            }
        }
    }

    private void updateLayout(int w, int h) {
        // Tweaks: drawer grid from the launcher settings (0 = automatic, stock)
        final android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                Launcher.TWEAKS_PREFERENCES, Context.MODE_PRIVATE);
        mLayout = new Layout(w, h, mContentInsets,
                getResources().getDisplayMetrics().density,
                prefs.getInt(Launcher.TWEAK_DRAWER_COLUMNS, 0),
                prefs.getInt(Launcher.TWEAK_DRAWER_ROWS, 0));
        mColumnsPerPage = mLayout.cols;
        mRowsPerPage = mLayout.rows;
        initTouchState(w, h);
        sRollo.resize(mLayout);
    }

    /**
     * If you have an attached click listener, View always plays the click sound!?!?
     * Deal with sound effects by hand.
     */
    public void reallyPlaySoundEffect(int sound) {
        boolean old = isSoundEffectsEnabled();
        setSoundEffectsEnabled(true);
        playSoundEffect(sound);
        setSoundEffectsEnabled(old);
    }

    public void setLauncher(Launcher launcher) {
        mLauncher = launcher;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        super.surfaceDestroyed(holder);
        // We may lose any callbacks that are pending, so make sure that we re-sync that
        // on the next surfaceChanged.
        sZoomDirty = true;
        mHaveSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);

        if (mSurrendered) return;

        mHaveSurface = true;

        if (!sRollo.mInitialized) {
            sRollo.init(getResources(), w, h);
            if (mAllAppsList != null) {
                sRollo.setApps(mAllAppsList);
            }
            if (mShouldGainFocus) {
                gainFocus();
                mShouldGainFocus = false;
            }
        }

        updateLayout(w, h);

        sRollo.dirtyCheck();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (mSurrendered) return;

        if (mArrowNavigation) {
            if (!hasWindowFocus) {
                // Clear selection when we lose window focus
                mLastSelectedIcon = sRollo.mState.selectedIconIndex;
                sRollo.setHomeSelected(SELECTED_NONE);
                sRollo.clearSelectedIcon();
                sRollo.mState.save();
            } else {
                if (sRollo.mState.iconCount > 0) {
                    if (mLastSelection == SELECTION_ICONS) {
                        int selection = mLastSelectedIcon;
                        final int firstIcon = Math.round(sRollo.mScrollPos) * mColumnsPerPage;
                        if (selection < 0 || // No selection
                                selection < firstIcon || // off the top of the screen
                                selection >= sRollo.mState.iconCount || // past last icon
                                selection >= firstIcon + // past last icon on screen
                                    (mColumnsPerPage * mRowsPerPage)) {
                            selection = firstIcon;
                        }

                        // Select the first icon when we gain window focus
                        sRollo.selectIcon(selection, SELECTED_FOCUSED);
                        sRollo.mState.save();
                    } else if (mLastSelection == SELECTION_HOME) {
                        sRollo.setHomeSelected(SELECTED_FOCUSED);
                        sRollo.mState.save();
                    }
                }
            }
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);

        if (!isVisible() || mSurrendered) {
            return;
        }

        if (gainFocus) {
            if (sRollo.mInitialized) {
                gainFocus();
            } else {
                mShouldGainFocus = true;
            }
        } else {
            if (sRollo.mInitialized) {
                if (mArrowNavigation) {
                    // Clear selection when we lose focus
                    sRollo.clearSelectedIcon();
                    sRollo.setHomeSelected(SELECTED_NONE);
                    sRollo.mState.save();
                    mArrowNavigation = false;
                }
            } else {
                mShouldGainFocus = false;
            }
        }
    }

    private void gainFocus() {
        if (!mArrowNavigation && sRollo.mState.iconCount > 0) {
            // Select the first icon when we gain keyboard focus
            mArrowNavigation = true;
            sRollo.selectIcon(Math.round(sRollo.mScrollPos) * mColumnsPerPage, SELECTED_FOCUSED);
            sRollo.mState.save();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        boolean handled = false;

        if (!isVisible()) {
            return false;
        }
        final int iconCount = sRollo.mState.iconCount;

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (mArrowNavigation) {
                if (mLastSelection == SELECTION_HOME) {
                    reallyPlaySoundEffect(SoundEffectConstants.CLICK);
                    mLauncher.closeAllApps(true);
                } else {
                    int whichApp = sRollo.mState.selectedIconIndex;
                    if (whichApp >= 0) {
                        ApplicationInfo app = mAllAppsList.get(whichApp);
                        mLauncher.startActivitySafely(app.intent, app);
                        handled = true;
                    }
                }
            }
        }

        if (iconCount > 0) {
            final boolean isPortrait = getWidth() < getHeight();

            mArrowNavigation = true;

            int currentSelection = sRollo.mState.selectedIconIndex;
            int currentTopRow = Math.round(sRollo.mScrollPos);

            // The column of the current selection, in the range 0..COLUMNS_PER_PAGE_PORTRAIT-1
            final int currentPageCol = currentSelection % mColumnsPerPage;

            // The row of the current selection, in the range 0..ROWS_PER_PAGE_PORTRAIT-1
            final int currentPageRow = (currentSelection - (currentTopRow * mColumnsPerPage))
                    / mRowsPerPage;

            int newSelection = currentSelection;

            switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mLastSelection == SELECTION_HOME) {
                    if (isPortrait) {
                        sRollo.setHomeSelected(SELECTED_NONE);
                        int lastRowCount = iconCount % mColumnsPerPage;
                        if (lastRowCount == 0) {
                            lastRowCount = mColumnsPerPage;
                        }
                        newSelection = iconCount - lastRowCount + (mColumnsPerPage / 2);
                        if (newSelection >= iconCount) {
                            newSelection = iconCount-1;
                        }
                        int target = (newSelection / mColumnsPerPage) - (mRowsPerPage - 1);
                        if (target < 0) {
                            target = 0;
                        }
                        if (currentTopRow != target) {
                            sRollo.moveTo(target);
                        }
                    }
                } else {
                    if (currentPageRow > 0) {
                        newSelection = currentSelection - mColumnsPerPage;
                        if (currentTopRow > newSelection / mColumnsPerPage) {
                            sRollo.moveTo(newSelection / mColumnsPerPage);
                        }
                    } else if (currentTopRow > 0) {
                        newSelection = currentSelection - mColumnsPerPage;
                        sRollo.moveTo(newSelection / mColumnsPerPage);
                    } else if (currentPageRow != 0) {
                        newSelection = currentTopRow * mRowsPerPage;
                    }
                }
                handled = true;
                break;

            case KeyEvent.KEYCODE_DPAD_DOWN: {
                final int rowCount = iconCount / mColumnsPerPage
                        + (iconCount % mColumnsPerPage == 0 ? 0 : 1);
                final int currentRow = currentSelection / mColumnsPerPage;
                if (mLastSelection != SELECTION_HOME) {
                    if (currentRow < rowCount-1) {
                        sRollo.setHomeSelected(SELECTED_NONE);
                        if (currentSelection < 0) {
                            newSelection = 0;
                        } else {
                            newSelection = currentSelection + mColumnsPerPage;
                        }
                        if (newSelection >= iconCount) {
                            // Go from D to G in this arrangement:
                            //     A B C D
                            //     E F G
                            newSelection = iconCount - 1;
                        }
                        if (currentPageRow >= mRowsPerPage - 1) {
                            sRollo.moveTo((newSelection / mColumnsPerPage) - mRowsPerPage + 1);
                        }
                    } else if (isPortrait) {
                        newSelection = -1;
                        sRollo.setHomeSelected(SELECTED_FOCUSED);
                    }
                }
                handled = true;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mLastSelection != SELECTION_HOME) {
                    if (currentPageCol > 0) {
                        newSelection = currentSelection - 1;
                    }
                } else if (!isPortrait) {
                    newSelection = ((int) (sRollo.mScrollPos) * mColumnsPerPage) +
                            (mRowsPerPage / 2 * mColumnsPerPage) + mColumnsPerPage - 1;
                    sRollo.setHomeSelected(SELECTED_NONE);
                }
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mLastSelection != SELECTION_HOME) {
                    if (!isPortrait && (currentPageCol == mColumnsPerPage - 1 ||
                            currentSelection == iconCount - 1)) {
                        newSelection = -1;
                        sRollo.setHomeSelected(SELECTED_FOCUSED);
                    } else if ((currentPageCol < mColumnsPerPage - 1) &&
                            (currentSelection < iconCount - 1)) {
                        newSelection = currentSelection + 1;
                    }
                }
                handled = true;
                break;
            }
            if (newSelection != currentSelection) {
                sRollo.selectIcon(newSelection, SELECTED_FOCUSED);
                sRollo.mState.save();
            }
        }
        return handled;
    }

    void initTouchState(int width, int height) {
        // Port: the original computed the borders in its view coordinates and then
        // subtracted the view's screen position, i.e. the 38px hdpi status bar above it.
        // That shift (38 virtual px) is kept, relative to the drawer content area.
        final Layout l = mLayout;
        final float statusBarShift = 38.f;

        mTouchXBorders = new int[mColumnsPerPage + 1];
        mTouchYBorders = new int[mRowsPerPage + 1];

        // TODO: Put this in a config file/define
        float cellHeight = l.rowHeight;
        float top = l.touchCenterY - (mRowsPerPage * cellHeight) / 2;
        for (int i = 0; i <= mRowsPerPage; i++) {
            mTouchYBorders[i] = l.grid.top
                    + (int) ((top + (i * cellHeight) - statusBarShift) * l.k);
        }

        float x = 0;
        // TODO: Put this in a config file/define
        float columnWidth = l.colWidth; // Port: was 120
        for (int i = 0; i < mColumnsPerPage + 1; i++) {
            mTouchXBorders[i] = l.grid.left + (int) (x * l.k);
            x += columnWidth;
        }
    }

    int chooseTappedIcon(int x, int y) {
        float pos = sRollo != null ? sRollo.mScrollPos : 0;

        int oldY = y;

        // Adjust for scroll position if not zero.
        y += (pos - ((int)pos)) * (mTouchYBorders[1] - mTouchYBorders[0]);

        int col = -1;
        int row = -1;
        final int columnsCount = mColumnsPerPage;
        for (int i=0; i< columnsCount; i++) {
            if (x >= mTouchXBorders[i] && x < mTouchXBorders[i+1]) {
                col = i;
                break;
            }
        }
        final int rowsCount = mRowsPerPage;
        for (int i=0; i< rowsCount; i++) {
            if (y >= mTouchYBorders[i] && y < mTouchYBorders[i+1]) {
                row = i;
                break;
            }
        }

        if (row < 0 || col < 0) {
            return -1;
        }

        int index = (((int) pos) * columnsCount) + (row * columnsCount) + col;

        if (index >= mAllAppsList.size()) {
            return -1;
        } else {
            return index;
        }
    }

    /** Port: the original divided by getHeight(), i.e. the Nexus One's 762px. */
    private float touchHeight() {
        return mLayout != null ? mLayout.refHeightPx : getHeight();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev)
    {
        mArrowNavigation = false;

        if (!isVisible()) {
            return true;
        }

        if (mLocks != 0 || mTouchYBorders == null) {
            return true;
        }

        if (handleCloseDrag(ev)) { // Tweaks
            return true;
        }

        super.onTouchEvent(ev);

        int x = (int)ev.getX();
        int y = (int)ev.getY();

        final boolean isPortrait = getWidth() < getHeight();
        int action = ev.getAction();
        switch (action) {
        case MotionEvent.ACTION_DOWN:
            if ((isPortrait && y > mTouchYBorders[mTouchYBorders.length-1]) ||
                    (!isPortrait && x > mTouchXBorders[mTouchXBorders.length-1])) {
                mTouchTracking = TRACKING_HOME;
                sRollo.setHomeSelected(SELECTED_PRESSED);
                sRollo.mState.save();
                mCurrentIconIndex = -1;
            } else {
                mTouchTracking = TRACKING_FLING;

                mMotionDownRawX = (int)ev.getRawX();
                mMotionDownRawY = (int)ev.getRawY();

                sRollo.mState.newPositionX = ev.getRawY() / touchHeight();
                sRollo.mState.newTouchDown = 1;

                if (!sRollo.checkClickOK()) {
                    sRollo.clearSelectedIcon();
                } else {
                    mDownIconIndex = mCurrentIconIndex
                            = sRollo.selectIcon(x, y, SELECTED_PRESSED);
                    if (mDownIconIndex < 0) {
                        // if nothing was selected, no long press.
                        cancelLongPress();
                    }
                }
                sRollo.mState.save();
                sRollo.move();
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(ev);
                mStartedScrolling = false;
            }
            break;
        case MotionEvent.ACTION_MOVE:
        case MotionEvent.ACTION_OUTSIDE:
            if (mTouchTracking == TRACKING_HOME) {
                sRollo.setHomeSelected((isPortrait &&
                        y > mTouchYBorders[mTouchYBorders.length-1]) || (!isPortrait
                        && x > mTouchXBorders[mTouchXBorders.length-1])
                        ? SELECTED_PRESSED : SELECTED_NONE);
                sRollo.mState.save();
            } else if (mTouchTracking == TRACKING_FLING) {
                int rawY = (int)ev.getRawY();
                int slop;
                slop = Math.abs(rawY - mMotionDownRawY);

                if (!mStartedScrolling && slop < mSlop) {
                    // don't update anything so when we do start scrolling
                    // below, we get the right delta.
                    mCurrentIconIndex = chooseTappedIcon(x, y);
                    if (mDownIconIndex != mCurrentIconIndex) {
                        // If a different icon is selected, don't allow it to be picked up.
                        // This handles off-axis dragging.
                        cancelLongPress();
                        mCurrentIconIndex = -1;
                    }
                } else {
                    if (!mStartedScrolling) {
                        cancelLongPress();
                        mCurrentIconIndex = -1;
                    }
                    sRollo.mState.newPositionX = ev.getRawY() / touchHeight();
                    sRollo.mState.newTouchDown = 1;
                    sRollo.move();

                    mStartedScrolling = true;
                    sRollo.clearSelectedIcon();
                    mVelocityTracker.addMovement(ev);
                    sRollo.mState.save();
                }
            }
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            if (mTouchTracking == TRACKING_HOME) {
                if (action == MotionEvent.ACTION_UP) {
                    if ((isPortrait && y > mTouchYBorders[mTouchYBorders.length-1]) ||
                        (!isPortrait && x > mTouchXBorders[mTouchXBorders.length-1])) {
                        reallyPlaySoundEffect(SoundEffectConstants.CLICK);
                        mLauncher.closeAllApps(true);
                    }
                    sRollo.setHomeSelected(SELECTED_NONE);
                    sRollo.mState.save();
                }
                mCurrentIconIndex = -1;
            } else if (mTouchTracking == TRACKING_FLING) {
                sRollo.mState.newTouchDown = 0;
                sRollo.mState.newPositionX = ev.getRawY() / touchHeight();

                mVelocityTracker.computeCurrentVelocity(1000 /* px/sec */, mMaxFlingVelocity);
                sRollo.mState.flingVelocity = mVelocityTracker.getYVelocity() / touchHeight();
                sRollo.clearSelectedIcon();
                sRollo.mState.save();
                sRollo.fling();

                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
            }
            mTouchTracking = TRACKING_NONE;
            break;
        }

        return true;
    }

    public void onClick(View v) {
        if (mLocks != 0 || !isVisible()) {
            return;
        }
        if (sRollo.checkClickOK() && mCurrentIconIndex == mDownIconIndex
                && mCurrentIconIndex >= 0 && mCurrentIconIndex < mAllAppsList.size()) {
            reallyPlaySoundEffect(SoundEffectConstants.CLICK);
            ApplicationInfo app = mAllAppsList.get(mCurrentIconIndex);
            mLauncher.startActivitySafely(app.intent, app);
        }
    }

    public boolean onLongClick(View v) {
        // We don't accept long click events in these cases
        // - If the workspace isn't ready to accept a drop
        // - If we're not done loading (because we might be confused about which item
        //   to pick up
        // - If we're not visible
        if (!isVisible() || mLauncher.isWorkspaceLocked() || mLocks != 0) {
            return true;
        }
        if (sRollo.checkClickOK() && mCurrentIconIndex == mDownIconIndex
                && mCurrentIconIndex >= 0 && mCurrentIconIndex < mAllAppsList.size()) {
            ApplicationInfo app = mAllAppsList.get(mCurrentIconIndex);

            Bitmap bmp = app.iconBitmap;
            final int w = bmp.getWidth();
            final int h = bmp.getHeight();

            // We don't really have an accurate location to use.  This will do.
            int screenX = mMotionDownRawX - (w / 2);
            int screenY = mMotionDownRawY - h;

            mDragController.startDrag(bmp, screenX, screenY,
                    0, 0, w, h, this, app, DragController.DRAG_ACTION_COPY);

            mLauncher.closeAllApps(true);
        }
        return true;
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            if (!isVisible()) {
                return false;
            }
            String text = null;
            int index;
            int count = mAllAppsList.size() + 1; // +1 is home
            int pos = -1;
            switch (mLastSelection) {
            case SELECTION_ICONS:
                index = sRollo.mState.selectedIconIndex;
                if (index >= 0) {
                    ApplicationInfo info = mAllAppsList.get(index);
                    if (info.title != null) {
                        text = info.title.toString();
                        pos = index;
                    }
                }
                break;
            case SELECTION_HOME:
                text = getContext().getString(R.string.all_apps_home_button_label);
                pos = count;
                break;
            }
            if (text != null) {
                event.setEnabled(true);
                event.getText().add(text);
                //event.setContentDescription(text);
                event.setItemCount(count);
                event.setCurrentItemIndex(pos);
            }
        }
        return false;
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
        // Tweaks: speed of this zoom (a gesture continues at the finger's speed)
        sZoomSpeed = mNextZoomSpeed;
        mNextZoomSpeed = ZOOM_SPEED;
        cancelLongPress();
        sNextZoom = zoom;
        sAnimateNextZoom = animate;
        // if we do setZoom while we don't have a surface, we won't
        // get the callbacks that actually set mZoom.
        if (!sRollo.mInitialized || !mHaveSurface) {
            sZoomDirty = true;
            mZoom = zoom;
        } else {
            sRollo.setZoom(zoom, animate);
        }
    }

    /**
     * If sRollo is null, then we're not visible.  This is also used to guard against
     * sRollo being null.
     */
    public boolean isVisible() {
        return sRollo != null && mZoom > 0.001f;
    }

    /*
     * Tweaks: the drawer following a finger. The whole open animation depends on the zoom
     * (0..1), so the finger sets it directly; on release it continues to open or closed at
     * least as fast as the finger moved.
     */
    static final float ZOOM_SPEED = 1.7f; // zoom per second of the original animation
    static volatile float sZoomSpeed = ZOOM_SPEED;
    private float mNextZoomSpeed = ZOOM_SPEED;

    /** Sets the zoom directly (while a finger drags). */
    void dragZoom(float zoom) {
        zoom = Math.max(0, Math.min(1, zoom));
        cancelLongPress();
        sNextZoom = zoom;
        sAnimateNextZoom = false;
        mZoom = zoom;
        if (!sRollo.mInitialized || !mHaveSurface) {
            sZoomDirty = true;
        } else {
            sRollo.setZoom(zoom, false);
        }
    }

    /** Finger released: open or close, continuing with speed (zoom per second). */
    void settleZoom(boolean open, float speed) {
        mNextZoomSpeed = Math.max(ZOOM_SPEED, Math.min(speed, 12.f));
        if (open) {
            mLauncher.showAllApps(true);
        } else {
            mLauncher.closeAllApps(true);
        }
        mNextZoomSpeed = ZOOM_SPEED;
    }

    /** Distance in px for a full open/close gesture. */
    static float gestureDistance(View v) {
        return v.getHeight() * 0.5f;
    }

    // Tweaks: swipe down at the top of the list closes all apps
    private boolean mCloseDragging;
    private float mCloseDownY;
    private boolean mCloseAllowed;

    private boolean handleCloseDrag(MotionEvent ev) {
        if (!Launcher.SWIPE_UP_ALL_APPS) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mCloseDragging = false;
                mCloseDownY = ev.getRawY();
                // only at the top of the list and when it isn't moving
                mCloseAllowed = sRollo.mScrollPos <= 0.01f && Math.abs(mVelocity) < 0.01f;
                return false;
            case MotionEvent.ACTION_MOVE: {
                final float dy = ev.getRawY() - mCloseDownY;
                if (!mCloseDragging) {
                    if (!mCloseAllowed || dy < mSlop) return false;
                    // take over from the list
                    mCloseDragging = true;
                    cancelLongPress();
                    mCurrentIconIndex = -1;
                    sRollo.clearSelectedIcon();
                    // end the list's touch like a release without velocity; only setting
                    // newTouchDown would leave the script thinking the finger is still
                    // down, and the next touch would jump the list by the distance
                    // between the two touches
                    sRollo.mState.newTouchDown = 0;
                    sRollo.mState.flingVelocity = 0;
                    sRollo.mState.save();
                    sRollo.fling();
                    mTouchTracking = TRACKING_NONE;
                    if (mVelocityTracker != null) mVelocityTracker.recycle();
                    mVelocityTracker = VelocityTracker.obtain();
                    mCloseDownY += mSlop;
                }
                mVelocityTracker.addMovement(ev);
                dragZoom(1.f - Math.max(0, ev.getRawY() - mCloseDownY) / gestureDistance(this));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!mCloseDragging) return false;
                mCloseDragging = false;
                mVelocityTracker.addMovement(ev);
                mVelocityTracker.computeCurrentVelocity(1000);
                final float v = mVelocityTracker.getYVelocity(); // px/s, down > 0
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                final float minFling =
                        ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity() * 4;
                final boolean close = ev.getActionMasked() == MotionEvent.ACTION_UP
                        && (v > minFling || (v > -minFling && mZoom < 0.5f));
                settleZoom(!close, Math.abs(v) / gestureDistance(this));
                return true;
            }
        }
        return mCloseDragging;
    }

    public boolean isOpaque() {
        return mZoom > 0.999f;
    }

    public void setApps(ArrayList<ApplicationInfo> list) {
        if (list != null) {
            Collections.sort(list, LauncherModel.APP_NAME_COMPARATOR);
        }

        boolean reload = false;
        if (mAllAppsList == null) {
            reload = true;
        } else if (list.size() != mAllAppsList.size()) {
            reload = true;
        } else {
            final int count = list.size();
            for (int i = 0; i < count; i++) {
                if (list.get(i) != mAllAppsList.get(i)) {
                    reload = true;
                    break;
                }
            }
        }

        mAllAppsList = list;
        if (sRollo.mInitialized && reload) {
            sRollo.setApps(list);
        }

        if (hasFocus() && mRestoreFocusIndex != -1) {
            sRollo.selectIcon(mRestoreFocusIndex, SELECTED_FOCUSED);
            sRollo.mState.save();
            mRestoreFocusIndex = -1;
        }

        mLocks &= ~LOCK_ICONS_PENDING;
    }

    public void addApps(ArrayList<ApplicationInfo> list) {
        if (mAllAppsList == null) {
            // Not done loading yet.  We'll find out about it later.
            return;
        }

        final int N = list.size();
        if (sRollo.mInitialized) {
            sRollo.pause();
            sRollo.reallocAppsList(sRollo.mState.iconCount + N);
        }

        for (int i=0; i<N; i++) {
            final ApplicationInfo item = list.get(i);
            int index = Collections.binarySearch(mAllAppsList, item,
                    LauncherModel.APP_NAME_COMPARATOR);
            if (index < 0) {
                index = -(index+1);
            }
            mAllAppsList.add(index, item);
            if (sRollo.mInitialized) {
                sRollo.addApp(index, item);
            }
        }

        if (sRollo.mInitialized) {
            sRollo.saveAppsList();
            sRollo.resume();
        }
    }

    public void removeApps(ArrayList<ApplicationInfo> list) {
        if (mAllAppsList == null) {
            // Not done loading yet.  We'll find out about it later.
            return;
        }

        if (sRollo.mInitialized) {
            sRollo.pause();
        }
        final int N = list.size();
        for (int i=0; i<N; i++) {
            final ApplicationInfo item = list.get(i);
            int index = findAppByComponent(mAllAppsList, item);
            if (index >= 0) {
                mAllAppsList.remove(index);
                if (sRollo.mInitialized) {
                    sRollo.removeApp(index);
                }
            } else {
                Log.w(TAG, "couldn't find a match for item \"" + item + "\"");
                // Try to recover.  This should keep us from crashing for now.
            }
        }

        if (sRollo.mInitialized) {
            sRollo.saveAppsList();
            sRollo.resume();
        }
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

    /**
     * Port: replaces AAMessage, the RS readback (sendToClient) of position, velocity and
     * zoom. Runs on the UI thread.
     */
    private void onReadback(float scrollPos, float velocity, float zoom) {
        sRollo.mScrollPos = scrollPos;
        mVelocity = velocity;

        // The original showed/hid the surface here when visibility changed; the surface
        // simply stays transparent while the drawer is closed.
        mZoom = zoom;

        sZoomDirty = false;
    }

    public class RolloRS {
        private Resources mRes;

        private Bitmap mHomeButtonNormal;
        private Bitmap mHomeButtonFocused;
        private Bitmap mHomeButtonPressed;

        private Bitmap mSelectionBitmap;
        private Canvas mSelectionCanvas;

        private float mScrollPos;

        Params mParams;
        State mState;

        AllApps3D mAllApps;
        boolean mInitialized;

        final Renderer mRenderer = new Renderer();

        private boolean checkClickOK() {
            return (Math.abs(mAllApps.mVelocity) < 0.4f) &&
                   (Math.abs(mScrollPos - Math.round(mScrollPos)) < 0.4f);
        }

        // Port: RS unbound/rebound the root script around list edits. Commands are
        // processed in order on the GL thread, so this is not needed anymore.
        void pause() {
        }

        void resume() {
        }

        class Params {
            public int bubbleWidth;
            public int bubbleHeight;
            public int bubbleBitmapWidth;
            public int bubbleBitmapHeight;

            public int homeButtonWidth;
            public int homeButtonHeight;
            public int homeButtonTextureWidth;
            public int homeButtonTextureHeight;

            void save() {
                final Params copy = new Params();
                copy.bubbleWidth = bubbleWidth;
                copy.bubbleHeight = bubbleHeight;
                copy.bubbleBitmapWidth = bubbleBitmapWidth;
                copy.bubbleBitmapHeight = bubbleBitmapHeight;
                copy.homeButtonWidth = homeButtonWidth;
                copy.homeButtonHeight = homeButtonHeight;
                copy.homeButtonTextureWidth = homeButtonTextureWidth;
                copy.homeButtonTextureHeight = homeButtonTextureHeight;
                queue(new Runnable() {
                    public void run() {
                        mRenderer.params = copy;
                    }
                });
            }
        }

        class State {
            public float newPositionX;
            public int newTouchDown;
            public float flingVelocity;
            public int iconCount;
            public int selectedIconIndex = -1;
            public int selectedIconTexture;
            public float zoomTarget;
            public int homeButtonId;
            public float targetPos;

            /** Port: was mAlloc.data(this), copying the struct to the script. */
            void save() {
                final State copy = new State();
                copy.newPositionX = newPositionX;
                copy.newTouchDown = newTouchDown;
                copy.flingVelocity = flingVelocity;
                copy.iconCount = iconCount;
                copy.selectedIconIndex = selectedIconIndex;
                copy.selectedIconTexture = selectedIconTexture;
                copy.zoomTarget = zoomTarget;
                copy.homeButtonId = homeButtonId;
                copy.targetPos = targetPos;
                queue(new Runnable() {
                    public void run() {
                        mRenderer.state = copy;
                    }
                });
            }
        }

        public RolloRS(AllApps3D allApps) {
            mAllApps = allApps;
        }

        /** Port: runs on the GL thread before the next frame, and starts drawing. */
        void queue(Runnable r) {
            queueEvent(r);
            requestRender();
        }

        public void init(Resources res, int width, int height) {
            mRes = res;
            initData();
            mInitialized = true;
        }

        void resize(final Layout layout) {
            queue(new Runnable() {
                public void run() {
                    mRenderer.layout = layout;
                }
            });
        }

        private void initData() {
            mParams = new Params();
            mState = new State();

            final Utilities.BubbleText bubble = new Utilities.BubbleText(mAllApps.getContext());

            mParams.bubbleWidth = bubble.getBubbleWidth();
            mParams.bubbleHeight = bubble.getMaxBubbleHeight();
            mParams.bubbleBitmapWidth = bubble.getBitmapWidth();
            mParams.bubbleBitmapHeight = bubble.getBitmapHeight();

            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            mHomeButtonNormal = BitmapFactory.decodeResource(mRes,
                    R.drawable.home_button_normal, opts);
            mHomeButtonFocused = BitmapFactory.decodeResource(mRes,
                    R.drawable.home_button_focused, opts);
            mHomeButtonPressed = BitmapFactory.decodeResource(mRes,
                    R.drawable.home_button_pressed, opts);
            final Bitmap normal = mHomeButtonNormal;
            final Bitmap focused = mHomeButtonFocused;
            final Bitmap pressed = mHomeButtonPressed;
            queue(new Runnable() {
                public void run() {
                    mRenderer.homeButtons = new Bitmap[] { normal, focused, pressed };
                    mRenderer.homeTextures = null;
                }
            });
            mParams.homeButtonWidth = 76;
            mParams.homeButtonHeight = 68;
            mParams.homeButtonTextureWidth = 128;
            mParams.homeButtonTextureHeight = 128;

            mState.homeButtonId = SELECTED_NONE;

            mParams.save();
            mState.save();

            // Port: the selection texture follows the icon texture size
            // (SELECTION_TEXTURE_WIDTH_PX / ICON_TEXTURE_WIDTH_PX at hdpi).
            final int iconTexture = Utilities.createIconBitmap(
                    mRes.getDrawable(R.drawable.ic_launcher_application),
                    mAllApps.getContext()).getWidth();
            final int selection = Math.round(iconTexture
                    * (float) Defines.SELECTION_TEXTURE_WIDTH_PX
                    / Defines.ICON_TEXTURE_WIDTH_PX);
            mSelectionBitmap = Bitmap.createBitmap(selection, selection,
                    Bitmap.Config.ARGB_8888);
            mSelectionCanvas = new Canvas(mSelectionBitmap);

            setApps(null);
        }

        void dirtyCheck() {
            if (sZoomDirty) {
                setZoom(mAllApps.sNextZoom, mAllApps.sAnimateNextZoom);
            }
        }

        @SuppressWarnings({"ConstantConditions"})
        private void setApps(ArrayList<ApplicationInfo> list) {
            final int count = list != null ? list.size() : 0;

            final Bitmap[] icons = new Bitmap[count];
            final Bitmap[] labels = new Bitmap[count];

            mState.iconCount = count;
            for (int i=0; i < mState.iconCount; i++) {
                icons[i] = list.get(i).iconBitmap;
                labels[i] = list.get(i).titleBitmap;
            }
            queue(new Runnable() {
                public void run() {
                    mRenderer.setApps(icons, labels);
                }
            });
            saveAppsList();
        }

        private void setZoom(float zoom, boolean animate) {
            if (animate) {
                clearSelectedIcon();
                setHomeSelected(SELECTED_NONE);
            }
            if (zoom > 0.001f) {
                mState.zoomTarget = zoom;
            } else {
                mState.zoomTarget = 0;
            }
            mState.save();
            if (!animate) {
                queue(new Runnable() {
                    public void run() {
                        mRenderer.script.setZoom();
                    }
                });
            }
        }

        /**
         * Puts the empty spaces at the end.  Updates mState.iconCount.  You must
         * fill in the values and call saveAppsList().
         */
        private void reallocAppsList(int count) {
            // Port: the texture lists on the GL thread grow as needed.
        }

        /**
         * Handle the allocations for the new app.  Make sure you call saveAppsList when done.
         */
        private void addApp(final int index, ApplicationInfo item) {
            final Bitmap icon = item.iconBitmap;
            final Bitmap label = item.titleBitmap;
            queue(new Runnable() {
                public void run() {
                    mRenderer.addApp(index, icon, label);
                }
            });
            mState.iconCount++;
        }

        /**
         * Handle the allocations for the removed app.  Make sure you call saveAppsList when done.
         */
        private void removeApp(final int index) {
            queue(new Runnable() {
                public void run() {
                    mRenderer.removeApp(index);
                }
            });
            mState.iconCount--;
        }

        /**
         * Send the apps list structures to RS.
         */
        private void saveAppsList() {
            mState.save();
            queue(new Runnable() {
                public void run() {
                    mRenderer.script.resetHWWar();
                }
            });
        }

        void fling() {
            queue(new Runnable() {
                public void run() {
                    mRenderer.script.fling();
                }
            });
        }

        void move() {
            queue(new Runnable() {
                public void run() {
                    mRenderer.script.move();
                }
            });
        }

        void moveTo(float row) {
            mState.targetPos = row;
            mState.save();
            queue(new Runnable() {
                public void run() {
                    mRenderer.script.moveTo();
                }
            });
        }

        /**
         * You need to call save() on mState on your own after calling this.
         *
         * @return the index of the icon that was selected.
         */
        int selectIcon(int x, int y, int pressed) {
            if (mAllApps != null) {
                final int index = mAllApps.chooseTappedIcon(x, y);
                selectIcon(index, pressed);
                return index;
            } else {
                return -1;
            }
        }

        /**
         * Select the icon at the given index.
         *
         * @param index The index.
         * @param pressed one of SELECTED_PRESSED or SELECTED_FOCUSED
         */
        void selectIcon(int index, int pressed) {
            final ArrayList<ApplicationInfo> appsList = mAllApps.mAllAppsList;
            if (appsList == null || index < 0 || index >= appsList.size()) {
                if (mAllApps != null) {
                    mAllApps.mRestoreFocusIndex = index;
                }
                mState.selectedIconIndex = -1;
                if (mAllApps.mLastSelection == SELECTION_ICONS) {
                    mAllApps.mLastSelection = SELECTION_NONE;
                }
            } else {
                if (pressed == SELECTED_FOCUSED) {
                    mAllApps.mLastSelection = SELECTION_ICONS;
                }

                int prev = mState.selectedIconIndex;
                mState.selectedIconIndex = index;

                ApplicationInfo info = appsList.get(index);
                Bitmap selectionBitmap = mSelectionBitmap;

                Utilities.drawSelectedAllAppsBitmap(mSelectionCanvas,
                        selectionBitmap.getWidth(), selectionBitmap.getHeight(),
                        pressed == SELECTED_PRESSED, info.iconBitmap);

                final Bitmap upload = selectionBitmap.copy(Bitmap.Config.ARGB_8888, false);
                queue(new Runnable() {
                    public void run() {
                        mRenderer.setSelectionBitmap(upload);
                    }
                });
                mState.selectedIconTexture++;

                if (prev != index) {
                    if (info.title != null && info.title.length() > 0) {
                        //setContentDescription(info.title);
                        mAllApps.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
                    }
                }
            }
        }

        /**
         * You need to call save() on mState on your own after calling this.
         */
        void clearSelectedIcon() {
            mState.selectedIconIndex = -1;
        }

        void setHomeSelected(int mode) {
            final int prev = mAllApps.mLastSelection;
            switch (mode) {
            case SELECTED_NONE:
                mState.homeButtonId = SELECTED_NONE;
                break;
            case SELECTED_FOCUSED:
                mAllApps.mLastSelection = SELECTION_HOME;
                mState.homeButtonId = SELECTED_FOCUSED;
                if (prev != SELECTION_HOME) {
                    mAllApps.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
                }
                break;
            case SELECTED_PRESSED:
                mState.homeButtonId = SELECTED_PRESSED;
                break;
            }
        }

        public void dumpState() {
            Log.d(TAG, "sRollo.mState.newPositionX=" + mState.newPositionX);
            Log.d(TAG, "sRollo.mState.newTouchDown=" + mState.newTouchDown);
            Log.d(TAG, "sRollo.mState.flingVelocity=" + mState.flingVelocity);
            Log.d(TAG, "sRollo.mState.iconCount=" + mState.iconCount);
            Log.d(TAG, "sRollo.mState.selectedIconIndex=" + mState.selectedIconIndex);
            Log.d(TAG, "sRollo.mState.zoomTarget=" + mState.zoomTarget);
            Log.d(TAG, "sRollo.mState.homeButtonId=" + mState.homeButtonId);
            Log.d(TAG, "sRollo.mState.targetPos=" + mState.targetPos);
            Log.d(TAG, "sRollo.mParams.bubbleWidth=" + mParams.bubbleWidth);
            Log.d(TAG, "sRollo.mParams.bubbleHeight=" + mParams.bubbleHeight);
            Log.d(TAG, "sRollo.mParams.bubbleBitmapWidth=" + mParams.bubbleBitmapWidth);
            Log.d(TAG, "sRollo.mParams.bubbleBitmapHeight=" + mParams.bubbleBitmapHeight);
            Log.d(TAG, "sRollo.mParams.homeButtonWidth=" + mParams.homeButtonWidth);
            Log.d(TAG, "sRollo.mParams.homeButtonHeight=" + mParams.homeButtonHeight);
            Log.d(TAG, "sRollo.mParams.homeButtonTextureWidth=" + mParams.homeButtonTextureWidth);
            Log.d(TAG, "sRollo.mParams.homeButtonTextureHeight=" + mParams.homeButtonTextureHeight);
        }

        // ================================================================================
        // Port: GL thread side. Everything below runs on the GLSurfaceView render thread.
        // ================================================================================

        /** Port of res/raw/allapps.rs (RenderScript root script). */
        final class Script {
            static final float PI = 3.14159f;

            int g_SpecialHWWar;

            // Attraction to center values from page edge to page center.
            final float[] g_AttractionTable = new float[9];
            final float[] g_FrictionTable = new float[9];
            float g_PhysicsTableSize;

            float g_PosPage;
            float g_PosVelocity;
            float g_LastPositionX;
            int g_LastTouchDown;
            float g_DT;
            float g_AnimScale = 1.f; // Port
            float g_PhysicsDT;       // Port: g_DT at the system animation speed
            long g_LastTime;
            int g_PosMax;
            float g_Zoom;
            float g_Animation;
            float g_OldPosPage;
            float g_OldPosVelocity;
            float g_OldZoom;
            float g_MoveToTotalTime;
            float g_MoveToTime;
            float g_MoveToOldPos;

            int g_Cols;
            int g_Rows;

            int g_DrawLastFrame;

            /** pfClearColor(): RS applied it when clearing for the next frame. */
            float clearR, clearG, clearB, clearA;

            int lastFrame(int draw) {
                // We draw one extra frame to work around the last frame post bug.
                // We also need to track if we drew the last frame to deal with large DT
                // in the physics.
                int ret = g_DrawLastFrame | draw;
                g_DrawLastFrame = draw;
                return ret;  // should return draw instead.
            }

            void updateReadback() {
                if ((g_OldPosPage != g_PosPage) ||
                    (g_OldPosVelocity != g_PosVelocity) ||
                    (g_OldZoom != g_Zoom)) {

                    g_OldPosPage = g_PosPage;
                    g_OldPosVelocity = g_PosVelocity;
                    g_OldZoom = g_Zoom;

                    final int i0 = (int) (g_PosPage * (1 << 16));
                    final int i1 = (int) (g_PosVelocity * (1 << 16));
                    final int i2 = (int) (g_OldZoom * (1 << 16));
                    post(new Runnable() {
                        public void run() {
                            onReadback(((float) i0) / (1 << 16), ((float) i1) / (1 << 16),
                                    ((float) i2) / (1 << 16));
                        }
                    });
                }
            }

            void setColor(float r, float g, float b, float a) {
                if (g_SpecialHWWar != 0) {
                    mRenderer.color(0, 0, 0, 0.001f);
                } else {
                    mRenderer.color(r, g, b, a);
                }
            }

            void init() {
                g_AttractionTable[0] = 20.0f;
                g_AttractionTable[1] = 20.0f;
                g_AttractionTable[2] = 20.0f;
                g_AttractionTable[3] = 10.0f;
                g_AttractionTable[4] = -10.0f;
                g_AttractionTable[5] = -20.0f;
                g_AttractionTable[6] = -20.0f;
                g_AttractionTable[7] = -20.0f;
                g_AttractionTable[8] = -20.0f;  // dup 7 to avoid a clamp later
                g_FrictionTable[0] = 10.0f;
                g_FrictionTable[1] = 10.0f;
                g_FrictionTable[2] = 11.0f;
                g_FrictionTable[3] = 15.0f;
                g_FrictionTable[4] = 15.0f;
                g_FrictionTable[5] = 11.0f;
                g_FrictionTable[6] = 10.0f;
                g_FrictionTable[7] = 10.0f;
                g_FrictionTable[8] = 10.0f;  // dup 7 to avoid a clamp later
                g_PhysicsTableSize = 7;

                g_PosVelocity = 0;
                g_PosPage = 0;
                g_LastTouchDown = 0;
                g_LastPositionX = 0;
                g_Zoom = 0;
                g_Animation = 1.f;
                g_SpecialHWWar = 1;
                g_MoveToTime = 0;
                g_MoveToOldPos = 0;
                g_MoveToTotalTime = 0.2f; // Duration of scrolling 1 line
            }

            void resetHWWar() {
                g_SpecialHWWar = 1;
            }

            void move() {
                final State state = mRenderer.state;
                if (g_LastTouchDown != 0) {
                    float dx = -(state.newPositionX - g_LastPositionX);
                    g_PosVelocity = 0;
                    g_PosPage += dx * 5.2f;

                    float pmin = -0.49f;
                    float pmax = g_PosMax + 0.49f;
                    g_PosPage = clampf(g_PosPage, pmin, pmax);
                }
                g_LastTouchDown = state.newTouchDown;
                g_LastPositionX = state.newPositionX;
                g_MoveToTime = 0;
                //debugF("Move P", g_PosPage);
            }

            void moveTo() {
                g_MoveToTime = g_MoveToTotalTime;
                g_PosVelocity = 0;
                g_MoveToOldPos = g_PosPage;

                // debugF("======= moveTo", state->targetPos);
            }

            void setZoom() {
                g_Zoom = mRenderer.state.zoomTarget;
                g_DrawLastFrame = 1;
                updateReadback();
            }

            void fling() {
                final State state = mRenderer.state;
                g_LastTouchDown = 0;
                g_PosVelocity = -state.flingVelocity * 4;
                float av = Math.abs(g_PosVelocity);
                float minVel = 3.5f;

                minVel *= 1.f - (Math.abs(fracf(g_PosPage + 0.5f) - 0.5f) * 0.45f);

                if (av < minVel && av > 0.2f) {
                    if (g_PosVelocity > 0) {
                        g_PosVelocity = minVel;
                    } else {
                        g_PosVelocity = -minVel;
                    }
                }

                if (g_PosPage <= 0) {
                    g_PosVelocity = Math.max(0, g_PosVelocity);
                }
                if (g_PosPage > g_PosMax) {
                    g_PosVelocity = Math.min(0, g_PosVelocity);
                }
            }

            /*
             * Interpolates values in the range 0..1 to a curve that eases in
             * and out.
             */
            float getInterpolation(float input) {
                return ((float) Math.cos((input + 1) * PI) / 2.0f) + 0.5f;
            }

            void updatePos() {
                final State state = mRenderer.state;
                if (g_LastTouchDown != 0) {
                    return;
                }

                float tablePosNorm = fracf(g_PosPage + 0.5f);
                float tablePosF = tablePosNorm * g_PhysicsTableSize;
                int tablePosI = (int) tablePosF;
                float tablePosFrac = tablePosF - tablePosI;
                float accel = lerpf(g_AttractionTable[tablePosI],
                                    g_AttractionTable[tablePosI + 1],
                                    tablePosFrac) * g_PhysicsDT;
                float friction = lerpf(g_FrictionTable[tablePosI],
                                    g_FrictionTable[tablePosI + 1],
                                    tablePosFrac) * g_PhysicsDT;

                if (g_MoveToTime != 0) {
                    // New position is old posiition + (total distance) * (interpolated time)
                    g_PosPage = g_MoveToOldPos + (state.targetPos - g_MoveToOldPos)
                            * getInterpolation((g_MoveToTotalTime - g_MoveToTime)
                            / g_MoveToTotalTime);
                    g_MoveToTime = g_AnimScale > 0 ? g_MoveToTime - g_PhysicsDT : 0;
                    if (g_MoveToTime <= 0) {
                        g_MoveToTime = 0;
                        g_PosPage = state.targetPos;
                    }
                    return;
                }

                // If our velocity is low OR acceleration is opposing it, apply it.
                if (Math.abs(g_PosVelocity) < 4.0f || (g_PosVelocity * accel) < 0) {
                    g_PosVelocity += accel;
                }

                // Normal physics
                if (g_PosVelocity > 0) {
                    g_PosVelocity -= friction;
                    g_PosVelocity = Math.max(g_PosVelocity, 0);
                } else {
                    g_PosVelocity += friction;
                    g_PosVelocity = Math.min(g_PosVelocity, 0);
                }

                if ((friction > Math.abs(g_PosVelocity)) && (friction > Math.abs(accel))) {
                    // Special get back to center and overcome friction physics.
                    float t = tablePosNorm - 0.5f;
                    if (Math.abs(t) < (friction * g_PhysicsDT)) {
                        // really close, just snap
                        g_PosPage = roundf(g_PosPage);
                        g_PosVelocity = 0;
                    } else {
                        if (t > 0) {
                            g_PosVelocity = -friction;
                        } else {
                            g_PosVelocity = friction;
                        }
                    }
                }

                // Check for out of boundry conditions.
                if (g_PosPage < 0 && g_PosVelocity < 0) {
                    float damp = 1.0f + (g_PosPage * 4);
                    damp = clampf(damp, 0.f, 0.9f);
                    g_PosVelocity *= damp;
                }
                if (g_PosPage > g_PosMax && g_PosVelocity > 0) {
                    float damp = 1.0f - ((g_PosPage - g_PosMax) * 4);
                    damp = clampf(damp, 0.f, 0.9f);
                    g_PosVelocity *= damp;
                }

                g_PosPage += g_PosVelocity * g_PhysicsDT;
                g_PosPage = clampf(g_PosPage, -0.49f, g_PosMax + 0.49f);
            }

            void draw_home_button() {
                final State state = mRenderer.state;
                final Params params = mRenderer.params;
                setColor(1.0f, 1.0f, 1.0f, 1.0f);

                // Port: the home button uses the whole content area, the icons the grid area.
                float w = mRenderer.layout.contentW;
                float h = mRenderer.layout.contentH;

                float x;
                float y;
                if (w > h) {
                    x = w - (params.homeButtonTextureWidth * (1 - g_Animation)) + 20;
                    y = (h - params.homeButtonTextureHeight) * 0.5f;
                } else {
                    x = (w - params.homeButtonTextureWidth) / 2;
                    y = -g_Animation * params.homeButtonTextureHeight;
                    y -= 30; // move the house to the edge of the screen as it doesn't fill the texture.
                }

                mRenderer.drawSpriteScreenspace(state.homeButtonId, x, y, 0,
                        params.homeButtonTextureWidth, params.homeButtonTextureHeight);
            }

            void drawFrontGrid(float rowOffset, float p) {
                final State state = mRenderer.state;
                final Layout l = mRenderer.layout;
                float h = getHeight();
                float w = getWidth();

                int intRowOffset = (int) rowOffset;
                float rowFrac = rowOffset - intRowOffset;
                float colWidth = mRenderer.layout.colWidth; // Port: was 120.f;//getWidth() / 4;
                // Port: rowHeight (145 / 133 in landscape), yoff and colCount come from
                // Layout, see its comments for the original values.
                float rowHeight = l.rowHeight;
                float yoff = l.yoff;

                int row, col;
                int colCount = l.cols;

                int iconNum = (intRowOffset - 5) * colCount;

                mRenderer.bindProgramVertexCurve();

                mRenderer.vpPosition[2] = p;

                setColor(1.0f, 1.0f, 1.0f, 1.0f);
                for (row = -5; row < 15; row++) {
                    float y = yoff - ((-rowFrac + row) * rowHeight);

                    for (col=0; col < colCount; col++) {
                        if (iconNum >= state.iconCount) {
                            return;
                        }

                        if (iconNum >= 0) {
                            float x = colWidth * col + (colWidth / 2);
                            mRenderer.vpPosition[0] = x + 0.2f;

                            if (state.selectedIconIndex == iconNum && p == 0) {
                                mRenderer.vpImgSize[0] = Defines.SELECTION_TEXTURE_WIDTH_PX;
                                mRenderer.vpImgSize[1] = Defines.SELECTION_TEXTURE_HEIGHT_PX;
                                mRenderer.vpPosition[1] = y - (Defines.SELECTION_TEXTURE_HEIGHT_PX
                                        - Defines.ICON_TEXTURE_HEIGHT_PX) * 0.5f;
                                mRenderer.drawCell(mRenderer.selectionTexture, false);
                            }

                            mRenderer.vpImgSize[0] = Defines.ICON_TEXTURE_WIDTH_PX;
                            mRenderer.vpImgSize[1] = Defines.ICON_TEXTURE_HEIGHT_PX;
                            mRenderer.vpPosition[1] = y - 0.2f;
                            mRenderer.drawCell(mRenderer.iconTextures.get(iconNum), false);

                            mRenderer.vpImgSize[0] = 120.f;
                            mRenderer.vpImgSize[1] = 64.f;
                            mRenderer.vpPosition[1] = y - 64.f - 0.2f;
                            mRenderer.drawCell(mRenderer.labelTextures.get(iconNum), true);
                        }
                        iconNum++;
                    }
                }
            }

            /** RS root script. Returns non-zero to keep rendering. */
            int main() {
                final State state = mRenderer.state;
                final Layout l = mRenderer.layout;

                // Compute dt in seconds.
                long newTime = SystemClock.uptimeMillis();
                g_DT = (newTime - g_LastTime) / 1000.f;
                g_LastTime = newTime;

                if (g_DrawLastFrame == 0) {
                    // If we stopped rendering we cannot use DT.
                    // assume 30fps in this case.
                    g_DT = 0.033f;
                }
                // physics may break if DT is large.
                g_DT = Math.min(g_DT, 0.2f);

                // Port: the system animation speed (animator duration scale) also applies
                // to the scrolling after a fling, the snapping/bounce and moveTo(), as a
                // time step of its own. Animations off: moveTo() jumps, the scrolling
                // physics run 10x faster.
                g_AnimScale = android.animation.ValueAnimator.getDurationScale();
                g_PhysicsDT = g_DT / Math.max(g_AnimScale, 0.1f);

                if (g_Zoom != state.zoomTarget) {
                    // Port: follows the system animation speed (animator duration scale)
                    float dz = g_AnimScale > 0 ? g_DT * sZoomSpeed / g_AnimScale : 1.f;
                    if (state.zoomTarget < 0.5f) {
                        dz = -dz;
                    }
                    if (Math.abs(g_Zoom - state.zoomTarget) < Math.abs(dz)) {
                        g_Zoom = state.zoomTarget;
                    } else {
                        g_Zoom += dz;
                    }
                    // Port: the clear color set now is used by the next frame, so make
                    // sure there is one. With animations off the zoom reaches its target
                    // in a single frame and rendering would stop before the background
                    // (open: no dim) or the transparent clear (close: black screen) shows.
                    g_DrawLastFrame = 1;
                    updateReadback();
                }
                g_Animation = (float) Math.pow(1-g_Zoom, 3);

                // Set clear value to dim the background based on the zoom position.
                if ((g_Zoom < 0.001f) && (state.zoomTarget < 0.001f) && g_SpecialHWWar == 0) {
                    pfClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                    // When we're zoomed out and not tracking motion events, reset the pos to 0.
                    if (g_LastTouchDown == 0) {
                        g_PosPage = 0;
                    }
                    return lastFrame(0);
                } else {
                    pfClearColor(0.0f, 0.0f, 0.0f, g_Zoom);
                }

                // icons & labels
                int iconCount = state.iconCount;
                // Port: 6x3 / 4x4 on the original screen, see Layout.
                g_Cols = l.cols;
                g_Rows = l.rows;
                g_PosMax = ((iconCount + (g_Cols-1)) / g_Cols) - g_Rows;
                if (g_PosMax < 0) g_PosMax = 0;

                // Port: faster animation speed -> larger time step; split it into steps
                // no larger than a 30 fps frame so the physics stay as they were.
                final float physicsDT = g_PhysicsDT;
                final int steps = Math.max(1, (int) Math.ceil(physicsDT / 0.033f));
                g_PhysicsDT = physicsDT / steps;
                for (int i = 0; i < steps; i++) {
                    updatePos();
                }
                updateReadback();

                //debugF("    draw g_PosPage", g_PosPage);

                // Draw the icons ========================================
                drawFrontGrid(g_PosPage, g_Animation);

                draw_home_button();

                // This is a WAR to do a rendering pass without drawing during init to
                // force the driver to preload and compile its shaders.
                // Without this the first animation does not appear due to the time it
                // takes to init the driver state.
                if (g_SpecialHWWar != 0) {
                    g_SpecialHWWar = 0;
                    return 1;
                }

                // Bug workaround where the last frame is not always displayed
                // So we keep rendering until the bug is fixed.
                return lastFrame(((g_PosVelocity != 0) || (fracf(g_PosPage) != 0)
                        || g_Zoom != state.zoomTarget || (g_MoveToTime != 0)) ? 1 : 0);
            }

            void pfClearColor(float r, float g, float b, float a) {
                clearR = r;
                clearG = g;
                clearB = b;
                clearA = a;
            }

            float getWidth() {
                return mRenderer.layout.w;
            }

            float getHeight() {
                return mRenderer.layout.h;
            }
        }

        // RS script library equivalents
        float clampf(float v, float min, float max) {
            return Math.max(min, Math.min(v, max));
        }

        float fracf(float v) {
            return v - (float) Math.floor(v);
        }

        float lerpf(float start, float stop, float amount) {
            return start + (stop - start) * amount;
        }

        float roundf(float v) {
            return (float) Math.floor(v + 0.5f);
        }

        /** Port: replaces the RS context, programs, allocations and mesh. */
        final class Renderer implements GLSurfaceView.Renderer {
            final Script script = new Script();
            State state = new State();
            Params params = new Params();
            Layout layout;
            Bitmap[] homeButtons;
            int[] homeTextures;

            final ArrayList<Bitmap> iconBitmaps = new ArrayList<Bitmap>();
            final ArrayList<Bitmap> labelBitmaps = new ArrayList<Bitmap>();
            final ArrayList<Integer> iconTextures = new ArrayList<Integer>();
            final ArrayList<Integer> labelTextures = new ArrayList<Integer>();
            Bitmap selectionBitmap;
            int selectionTexture;

            // vpConstants (UNI_ImgSize, UNI_Position, UNI_BendPos, UNI_ScaleOffset)
            final float[] vpImgSize = new float[2];
            final float[] vpPosition = new float[4];
            final float[] vpBendPos = new float[2];
            final float[] vpScaleOffset = new float[4];

            private final float[] mProjection = new float[16];
            private final float[] mOrtho = new float[16];
            private final float[] mColor = new float[] { 1, 1, 1, 1 };

            private int mCurveProgram;
            private int mSpriteProgram;
            private int mFragRgba;     // PFTexMip / PFTexNearest
            private int mFragAlpha;    // PFTexMipAlpha
            private int mCurveRgbaProgram;
            private int mCurveAlphaProgram;
            private int mSpriteRgbaProgram;
            private FloatBuffer mMeshVertices;
            private ShortBuffer mMeshIndices;
            private int mMeshIndexCount;
            private FloatBuffer mQuad;
            private int mSurfaceWidth;
            private int mSurfaceHeight;
            private boolean mContextReady;
            private Layout mUniformLayout;

            Renderer() {
                script.init();
                script.pfClearColor(0.0f, 0.0f, 0.0f, 0.0f); // mScript.setClearColor()
            }

            // --------------------------------------------------------------- GL thread

            public void onSurfaceCreated(GL10 unused, EGLConfig config) {
                // New EGL context: everything has to be created/uploaded again.
                initPrograms();
                initMesh();
                homeTextures = null;
                selectionTexture = 0;
                mPendingDeletes.clear(); // ids of the old context
                for (int i = 0; i < iconTextures.size(); i++) {
                    iconTextures.set(i, upload(iconBitmaps.get(i), true));
                    labelTextures.set(i, upload(labelBitmaps.get(i), true));
                }
                if (selectionBitmap != null) {
                    selectionTexture = upload(selectionBitmap, false);
                }
                mContextReady = true;
            }

            public void onSurfaceChanged(GL10 unused, int width, int height) {
                mSurfaceWidth = width;
                mUniformLayout = null; // Tweaks: setupLayout() depends on the surface size
                mSurfaceHeight = height;
            }

            public void onDrawFrame(GL10 unused) {
                final Layout l = layout;

                // RS: the clear uses the color the script set on the previous frame.
                GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
                GLES20.glColorMask(true, true, true, true);
                GLES20.glClearColor(script.clearR, script.clearG, script.clearB, script.clearA);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                if (l == null || !mContextReady) {
                    return;
                }
                syncTextures();
                if (homeTextures == null && homeButtons != null) {
                    homeTextures = new int[3];
                    for (int i = 0; i < 3; i++) {
                        homeTextures[i] = upload(homeButtons[i], false);
                    }
                }
                if (l != mUniformLayout) {
                    setupLayout(l);
                }

                if (Launcher.NO_BAR_BACKGROUNDS) {
                    // Tweaks: without the black bars the grid is drawn over the whole
                    // surface (placed by setupLayout()), so the icons aren't cut off at the
                    // status bar while they fly in and out.
                    GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
                } else {
                    // Draw inside the area not covered by system bars (GL origin bottom left).
                    GLES20.glViewport(l.grid.left, l.surfaceHeight - l.grid.bottom,
                            l.grid.width(), l.grid.height());
                }

                // mPSIcons
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);   // DepthFunc.ALWAYS
                GLES20.glColorMask(true, true, true, false);
                GLES20.glEnable(GLES20.GL_DITHER);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

                if (script.main() != 0) {
                    requestRender();
                }
            }

            /** surfaceChanged()'s vpConstants and resize()'s projection. */
            private void setupLayout(Layout l) {
                mUniformLayout = l;
                final float w = l.w;
                final float h = l.h;
                // tf = {72, 72,  120, 120, 0, 0,  120, 680,  2/480, 0, -w/2-0.25, -380.25}
                vpImgSize[0] = 72.f;
                vpImgSize[1] = 72.f;
                vpPosition[0] = 120.f;
                vpPosition[1] = 120.f;
                vpPosition[2] = 0.f;
                vpPosition[3] = 0.f;
                vpBendPos[0] = l.bendY1;
                vpBendPos[1] = l.bendY2;
                // 2/480 in the original: maps the 480px wide screen onto the projection's
                // [-1, 1]. The virtual grid width/height varies with the screen now.
                vpScaleOffset[0] = 2.f / (l.portrait ? w : h);
                vpScaleOffset[1] = l.portrait ? 0 : 1.f;
                vpScaleOffset[2] = -(w / 2) - 0.25f;
                vpScaleOffset[3] = -(h / 2) - 0.25f;  // -380.25 at the original 760

                setupProjectionNormalized(w, h);
                Matrix.orthoM(mOrtho, 0, 0, l.contentW, 0, l.contentH, -1, 1);
                if (Launcher.NO_BAR_BACKGROUNDS) {
                    // Tweaks: place the grid / content area inside the full surface
                    // viewport instead of clipping to them with the viewport.
                    placeInSurface(mProjection, l.grid, mSurfaceWidth, mSurfaceHeight);
                    placeInSurface(mOrtho, l.content, mSurfaceWidth, mSurfaceHeight);
                }
            }

            /** Tweaks: m = (clip space of the whole surface <- clip space of r) * m */
            private void placeInSurface(float[] m, Rect r, int sw, int sh) {
                final float[] place = new float[16];
                Matrix.setIdentityM(place, 0);
                place[0] = (float) r.width() / sw;
                place[5] = (float) r.height() / sh;
                place[12] = (2.f * r.left + r.width()) / sw - 1.f;
                place[13] = (2.f * (sh - r.bottom) + r.height()) / sh - 1.f;
                final float[] tmp = new float[16];
                Matrix.multiplyMM(tmp, 0, place, 0, m, 0);
                System.arraycopy(tmp, 0, m, 0, 16);
            }

            /** ProgramVertex.MatrixAllocation.setupProjectionNormalized(w, h). */
            private void setupProjectionNormalized(float w, float h) {
                final float[] m1 = new float[16];
                final float[] m2 = new float[16];
                final float[] tmp = new float[16];

                if (w > h) {
                    float aspect = w / h;
                    Matrix.frustumM(m1, 0, -aspect, aspect, -1, 1, 1, 100);
                } else {
                    float aspect = h / w;
                    Matrix.frustumM(m1, 0, -1, 1, -aspect, aspect, 1, 100);
                }

                Matrix.setRotateM(m2, 0, 180, 0, 1, 0);
                Matrix.multiplyMM(tmp, 0, m1, 0, m2, 0);
                System.arraycopy(tmp, 0, m1, 0, 16);

                Matrix.setIdentityM(m2, 0);
                Matrix.scaleM(m2, 0, -2, 2, 1);
                Matrix.multiplyMM(tmp, 0, m1, 0, m2, 0);
                System.arraycopy(tmp, 0, m1, 0, 16);

                Matrix.setIdentityM(m2, 0);
                Matrix.translateM(m2, 0, 0, 0, 2);
                Matrix.multiplyMM(tmp, 0, m1, 0, m2, 0);

                // UNI_MVP = projection * model (identity)
                System.arraycopy(tmp, 0, mProjection, 0, 16);
            }

            void color(float r, float g, float b, float a) {
                mColor[0] = r;
                mColor[1] = g;
                mColor[2] = b;
                mColor[3] = a;
            }

            void bindProgramVertexCurve() {
                // Uniforms are set per draw in drawCell().
            }

            /** drawSimpleMesh(NAMED_SMCell) with PVCurve and PFTexMip(Alpha)/PFTexNearest. */
            void drawCell(int texture, boolean alpha) {
                final int program = alpha ? mCurveAlphaProgram : mCurveRgbaProgram;
                GLES20.glUseProgram(program);
                GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "UNI_ImgSize"),
                        1, vpImgSize, 0);
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "UNI_Position"),
                        1, vpPosition, 0);
                GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "UNI_BendPos"),
                        1, vpBendPos, 0);
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "UNI_ScaleOffset"),
                        1, vpScaleOffset, 0);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "UNI_MVP"),
                        1, false, mProjection, 0);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "UNI_Tex0"), 0);

                final int pos = GLES20.glGetAttribLocation(program, "ATTRIB_position");
                GLES20.glEnableVertexAttribArray(pos);
                mMeshVertices.position(0);
                GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, mMeshVertices);
                mMeshIndices.position(0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, mMeshIndexCount,
                        GLES20.GL_UNSIGNED_SHORT, mMeshIndices);
                GLES20.glDisableVertexAttribArray(pos);
            }

            /**
             * drawSpriteScreenspace(): default program vertex, color from setColor(), y up
             * from the bottom of the drawing area.
             */
            void drawSpriteScreenspace(int which, float x, float y, float z, float w, float h) {
                if (homeTextures == null) {
                    return;
                }
                final float[] q = new float[] {
                    // x, y, s, t
                    x,     y,     0, 1,
                    x + w, y,     1, 1,
                    x + w, y + h, 1, 0,
                    x,     y + h, 0, 0,
                };
                mQuad.clear();
                mQuad.put(q).position(0);

                final Layout l = layout;
                if (Launcher.NO_BAR_BACKGROUNDS) { // Tweaks: see setupLayout()
                    GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
                } else {
                    GLES20.glViewport(l.content.left, l.surfaceHeight - l.content.bottom,
                            l.content.width(), l.content.height());
                }

                final int program = mSpriteRgbaProgram;
                GLES20.glUseProgram(program);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "UNI_MVP"),
                        1, false, mOrtho, 0);
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "UNI_Color"),
                        1, mColor, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, homeTextures[which]);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "UNI_Tex0"), 0);

                final int pos = GLES20.glGetAttribLocation(program, "ATTRIB_position");
                final int tex = GLES20.glGetAttribLocation(program, "ATTRIB_texture0");
                GLES20.glEnableVertexAttribArray(pos);
                GLES20.glEnableVertexAttribArray(tex);
                mQuad.position(0);
                GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, mQuad);
                mQuad.position(2);
                GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, mQuad);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
                GLES20.glDisableVertexAttribArray(pos);
                GLES20.glDisableVertexAttribArray(tex);
            }

            /*
             * Port: these run as queued events, and GLSurfaceView also runs queued events
             * while the surface is gone. The context is preserved then, but not current:
             * GL calls do nothing and every texture would stay 0 (white label boxes, no
             * icons) until the context is lost. So the queued events only change the lists
             * and remember what to delete; onDrawFrame() (context always current) uploads
             * the missing textures and deletes the old ones.
             */
            private final ArrayList<Integer> mPendingDeletes = new ArrayList<Integer>();

            void setApps(Bitmap[] icons, Bitmap[] labels) {
                deleteTextures(iconTextures);
                deleteTextures(labelTextures);
                iconBitmaps.clear();
                labelBitmaps.clear();
                for (int i = 0; i < icons.length; i++) {
                    iconBitmaps.add(icons[i]);
                    labelBitmaps.add(labels[i]);
                    iconTextures.add(0);
                    labelTextures.add(0);
                }
                requestRender();
            }

            void addApp(int index, Bitmap icon, Bitmap label) {
                iconBitmaps.add(index, icon);
                labelBitmaps.add(index, label);
                iconTextures.add(index, 0);
                labelTextures.add(index, 0);
                requestRender();
            }

            void removeApp(int index) {
                deleteLater(iconTextures.remove(index));
                deleteLater(labelTextures.remove(index));
                iconBitmaps.remove(index);
                labelBitmaps.remove(index);
            }

            void setSelectionBitmap(Bitmap b) {
                deleteLater(selectionTexture);
                selectionBitmap = b;
                selectionTexture = 0;
                requestRender();
            }

            private void deleteTextures(ArrayList<Integer> textures) {
                for (int i = 0; i < textures.size(); i++) {
                    deleteLater(textures.get(i));
                }
                textures.clear();
            }

            private void deleteLater(int id) {
                if (id != 0) mPendingDeletes.add(id);
            }

            /** GL thread, context current: apply the queued texture changes. */
            private void syncTextures() {
                if (!mPendingDeletes.isEmpty()) {
                    final int[] ids = new int[mPendingDeletes.size()];
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = mPendingDeletes.get(i);
                    }
                    GLES20.glDeleteTextures(ids.length, ids, 0);
                    mPendingDeletes.clear();
                }
                for (int i = 0; i < iconTextures.size(); i++) {
                    if (iconTextures.get(i) == 0) {
                        iconTextures.set(i, upload(iconBitmaps.get(i), true));
                    }
                    if (labelTextures.get(i) == 0) {
                        labelTextures.set(i, upload(labelBitmaps.get(i), true));
                    }
                }
                if (selectionTexture == 0 && selectionBitmap != null) {
                    selectionTexture = upload(selectionBitmap, false);
                }
            }

            /**
             * Allocation.createFromBitmap() + uploadToTexture(). mip: icons and labels
             * (PFTexMip, LINEAR_MIP_LINEAR); otherwise PFTexNearest.
             */
            private int upload(Bitmap bitmap, boolean mip) {
                if (bitmap == null || bitmap.isRecycled()) {
                    return 0;
                }
                final int[] id = new int[1];
                GLES20.glGenTextures(1, id, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                if (mip) {
                    GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                            GLES20.GL_LINEAR_MIPMAP_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                            GLES20.GL_LINEAR);
                } else {
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                            GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                            GLES20.GL_LINEAR);
                }
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                        GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                        GLES20.GL_CLAMP_TO_EDGE);
                return id[0];
            }

            /** initMesh(): SMCell, a 2x16 vertex strip from (0,0) to (1,1). */
            private void initMesh() {
                final float[] v = new float[16 * 2 * 2];
                int n = 0;
                for (int ct=0; ct < 16; ct++) {
                    float pos = (1.f / (16.f - 1)) * ct;
                    v[n++] = 0.0f; v[n++] = pos;
                    v[n++] = 1.0f; v[n++] = pos;
                }
                final short[] idx = new short[(16 * 2 - 2) / 2 * 6];
                n = 0;
                for (int ct=0; ct < (16 * 2 - 2); ct+= 2) {
                    idx[n++] = (short) ct; idx[n++] = (short) (ct + 1); idx[n++] = (short) (ct + 2);
                    idx[n++] = (short) (ct + 1); idx[n++] = (short) (ct + 3);
                    idx[n++] = (short) (ct + 2);
                }
                mMeshIndexCount = n;
                mMeshVertices = ByteBuffer.allocateDirect(v.length * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                mMeshVertices.put(v).position(0);
                mMeshIndices = ByteBuffer.allocateDirect(idx.length * 2)
                        .order(ByteOrder.nativeOrder()).asShortBuffer();
                mMeshIndices.put(idx).position(0);
                mQuad = ByteBuffer.allocateDirect(16 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
            }

            private void initPrograms() {
                // PVCurve: the original vertex shader, verbatim. RS prepended these
                // declarations for its constants, input and varyings.
                final String curve =
                        "uniform mat4 UNI_MVP;\n" +
                        "uniform vec2 UNI_ImgSize;\n" +
                        "uniform vec4 UNI_Position;\n" +
                        "uniform vec2 UNI_BendPos;\n" +
                        "uniform vec4 UNI_ScaleOffset;\n" +
                        "attribute vec2 ATTRIB_position;\n" +
                        "varying vec4 varColor;\n" +
                        "varying vec4 varTex0;\n" +
                        "void main() {\n" +
                        // Animation
                        "  float ani = UNI_Position.z;\n" +

                        "  float bendY1 = UNI_BendPos.x;\n" +
                        "  float bendY2 = UNI_BendPos.y;\n" +
                        "  float bendAngle = 47.0 * (3.14 / 180.0);\n" +
                        "  float bendDistance = bendY1 * 0.4;\n" +
                        "  float distanceDimLevel = 0.6;\n" +

                        "  float bendStep = (bendAngle / bendDistance) * (bendAngle * 0.5);\n" +
                        "  float aDy = cos(bendAngle);\n" +
                        "  float aDz = sin(bendAngle);\n" +

                        "  float scale = (2.0 / 480.0);\n" +
                        "  float x = UNI_Position.x + UNI_ImgSize.x * (1.0 - ani) * (ATTRIB_position.x - 0.5);\n" +
                        "  float ys= UNI_Position.y + UNI_ImgSize.y * (1.0 - ani) * ATTRIB_position.y;\n" +
                        "  float y = 0.0;\n" +
                        "  float z = 0.0;\n" +
                        "  float lum = 1.0;\n" +

                        "  float cv = min(ys, bendY1 - bendDistance) - (bendY1 - bendDistance);\n" +
                        "  y += cv * aDy;\n" +
                        "  z += -cv * aDz;\n" +
                        "  cv = clamp(ys, bendY1 - bendDistance, bendY1) - bendY1;\n" +  // curve range
                        "  lum += cv / bendDistance * distanceDimLevel;\n" +
                        "  y += cv * cos(cv * bendStep);\n" +
                        "  z += cv * sin(cv * bendStep);\n" +

                        "  cv = max(ys, bendY2 + bendDistance) - (bendY2 + bendDistance);\n" +
                        "  y += cv * aDy;\n" +
                        "  z += cv * aDz;\n" +
                        "  cv = clamp(ys, bendY2, bendY2 + bendDistance) - bendY2;\n" +
                        "  lum -= cv / bendDistance * distanceDimLevel;\n" +
                        "  y += cv * cos(cv * bendStep);\n" +
                        "  z += cv * sin(cv * bendStep);\n" +

                        "  y += clamp(ys, bendY1, bendY2);\n" +

                        "  vec4 pos;\n" +
                        "  pos.x = (x + UNI_ScaleOffset.z) * UNI_ScaleOffset.x;\n" +
                        "  pos.y = (y + UNI_ScaleOffset.w) * UNI_ScaleOffset.x;\n" +
                        "  pos.z = z * UNI_ScaleOffset.x;\n" +
                        "  pos.w = 1.0;\n" +

                        "  pos.x *= 1.0 + ani * 4.0;\n" +
                        "  pos.y *= 1.0 + ani * 4.0;\n" +
                        "  pos.z -= ani * 1.5;\n" +
                        "  lum *= 1.0 - ani;\n" +

                        "  gl_Position = UNI_MVP * pos;\n" +
                        "  varColor.rgba = vec4(lum, lum, lum, 1.0);\n" +
                        "  varTex0.xy = ATTRIB_position;\n" +
                        "  varTex0.y = 1.0 - varTex0.y;\n" +
                        "  varTex0.zw = vec2(0.0, 0.0);\n" +
                        "}\n";

                // Default program vertex (fixed function: constant color, texture coords).
                final String sprite =
                        "uniform mat4 UNI_MVP;\n" +
                        "uniform vec4 UNI_Color;\n" +
                        "attribute vec2 ATTRIB_position;\n" +
                        "attribute vec2 ATTRIB_texture0;\n" +
                        "varying vec4 varColor;\n" +
                        "varying vec4 varTex0;\n" +
                        "void main() {\n" +
                        "  gl_Position = UNI_MVP * vec4(ATTRIB_position, 0.0, 1.0);\n" +
                        "  varColor = UNI_Color;\n" +
                        "  varTex0 = vec4(ATTRIB_texture0, 0.0, 0.0);\n" +
                        "}\n";

                // ProgramFragment EnvMode.MODULATE, Format.RGBA / Format.ALPHA as RS
                // generated them.
                final String fragRgba =
                        "precision mediump float;\n" +
                        "uniform sampler2D UNI_Tex0;\n" +
                        "varying lowp vec4 varColor;\n" +
                        "varying vec4 varTex0;\n" +
                        "void main() {\n" +
                        "  lowp vec4 col = varColor;\n" +
                        "  col.rgba *= texture2D(UNI_Tex0, varTex0.xy).rgba;\n" +
                        "  gl_FragColor = col;\n" +
                        "}\n";
                final String fragAlpha =
                        "precision mediump float;\n" +
                        "uniform sampler2D UNI_Tex0;\n" +
                        "varying lowp vec4 varColor;\n" +
                        "varying vec4 varTex0;\n" +
                        "void main() {\n" +
                        "  lowp vec4 col = varColor;\n" +
                        "  col.a *= texture2D(UNI_Tex0, varTex0.xy).a;\n" +
                        "  gl_FragColor = col;\n" +
                        "}\n";

                final int vCurve = compile(GLES20.GL_VERTEX_SHADER, curve);
                final int vSprite = compile(GLES20.GL_VERTEX_SHADER, sprite);
                final int fRgba = compile(GLES20.GL_FRAGMENT_SHADER, fragRgba);
                final int fAlpha = compile(GLES20.GL_FRAGMENT_SHADER, fragAlpha);
                mCurveRgbaProgram = link(vCurve, fRgba);
                mCurveAlphaProgram = link(vCurve, fAlpha);
                mSpriteRgbaProgram = link(vSprite, fRgba);
            }

            private int compile(int type, String source) {
                final int shader = GLES20.glCreateShader(type);
                GLES20.glShaderSource(shader, source);
                GLES20.glCompileShader(shader);
                final int[] ok = new int[1];
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
                if (ok[0] == 0) {
                    Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
                }
                return shader;
            }

            private int link(int vertex, int fragment) {
                final int program = GLES20.glCreateProgram();
                GLES20.glAttachShader(program, vertex);
                GLES20.glAttachShader(program, fragment);
                GLES20.glLinkProgram(program);
                final int[] ok = new int[1];
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
                if (ok[0] == 0) {
                    Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
                }
                return program;
            }
        }
    }

    public void dumpState() {
        Log.d(TAG, "sRollo=" + sRollo);
        ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList", mAllAppsList);
        Log.d(TAG, "mTouchXBorders=" +  Arrays.toString(mTouchXBorders));
        Log.d(TAG, "mTouchYBorders=" +  Arrays.toString(mTouchYBorders));
        Log.d(TAG, "mArrowNavigation=" + mArrowNavigation);
        Log.d(TAG, "mStartedScrolling=" + mStartedScrolling);
        Log.d(TAG, "mLastSelection=" + mLastSelection);
        Log.d(TAG, "mLastSelectedIcon=" + mLastSelectedIcon);
        Log.d(TAG, "mVelocityTracker=" + mVelocityTracker);
        Log.d(TAG, "mTouchTracking=" + mTouchTracking);
        Log.d(TAG, "mShouldGainFocus=" + mShouldGainFocus);
        Log.d(TAG, "sZoomDirty=" + sZoomDirty);
        Log.d(TAG, "sAnimateNextZoom=" + sAnimateNextZoom);
        Log.d(TAG, "mZoom=" + mZoom);
        Log.d(TAG, "mScrollPos=" + sRollo.mScrollPos);
        Log.d(TAG, "mVelocity=" + mVelocity);
        if (sRollo.mInitialized) {
            sRollo.dumpState();
        }
    }
}
