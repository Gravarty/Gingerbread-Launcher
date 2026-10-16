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

import android.widget.TextView;
import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;

import com.android.launcher.R;

/**
 * TextView that draws a bubble behind the text. We cannot use a LineBackgroundSpan
 * because we want to make the bubble taller than the text and TextView's clip is
 * too aggressive.
 */
public class BubbleTextView extends TextView {
    static final float CORNER_RADIUS = 8.0f;
    static final float PADDING_H = 5.0f;
    static final float PADDING_V = 1.0f;

    private final RectF mRect = new RectF();
    private Paint mPaint;

    private boolean mBackgroundSizeChanged;
    private Drawable mBackground;
    private float mCornerRadius;
    private float mPaddingH;
    private float mPaddingV;

    public BubbleTextView(Context context) {
        super(context);
        init();
    }

    public BubbleTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BubbleTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    /**
     * Port: icons are larger on today's displays (Utilities.iconSizeDp(), like Launcher3).
     * Drawables that aren't icon bitmaps (folders) are scaled to match. The cell height is
     * Gingerbread's, so the view grows upwards into the cell's top margin by the extra icon
     * height; the label and its bubble keep their place and the gap to the icon.
     */
    @Override
    public void setCompoundDrawables(android.graphics.drawable.Drawable left,
            android.graphics.drawable.Drawable top, android.graphics.drawable.Drawable right,
            android.graphics.drawable.Drawable bottom) {
        int extra = 0;
        if (top != null && top.getIntrinsicHeight() > 0) {
            final int texture = Utilities.iconTextureSize(getContext());
            final float scale = Utilities.iconScale(getContext());
            final int base = Math.round(texture / scale);
            if (top.getIntrinsicHeight() < texture * 0.95f && scale != 1f) {
                top.setBounds(0, 0, Math.round(top.getIntrinsicWidth() * scale),
                        Math.round(top.getIntrinsicHeight() * scale));
            }
            extra = Math.max(0, Math.min(top.getBounds().height() - base,
                    Math.round(13 * getResources().getDisplayMetrics().density)));
        }
        super.setCompoundDrawables(left, top, right, bottom);
        if (extra != mIconExtra) {
            mIconExtra = extra;
            requestLayout();
        }
    }

    private int mIconExtra;

    @Override
    public void layout(int l, int t, int r, int b) {
        super.layout(l, t - mIconExtra, r, b);
    }

    private void init() {
        setFocusable(true);
        mBackground = getBackground();
        setBackgroundDrawable(null);

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setColor(getContext().getResources().getColor(R.color.bubble_dark_background));

        final float scale = getContext().getResources().getDisplayMetrics().density;
        mCornerRadius = CORNER_RADIUS * scale;
        mPaddingH = PADDING_H * scale;
        //noinspection PointlessArithmeticExpression
        mPaddingV = PADDING_V * scale;
    }

    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        if (getLeft() != left || getRight() != right || getTop() != top || getBottom() != bottom) {
            mBackgroundSizeChanged = true;
        }
        return super.setFrame(left, top, right, bottom);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mBackground || super.verifyDrawable(who);
    }

    @Override
    protected void drawableStateChanged() {
        Drawable d = mBackground;
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
        super.drawableStateChanged();
    }

    @Override
    public void draw(Canvas canvas) {
        final Drawable background = mBackground;
        if (background != null) {
            final int scrollX = getScrollX();
            final int scrollY = getScrollY();

            if (mBackgroundSizeChanged) {
                background.setBounds(0, 0,  getRight() - getLeft(), getBottom() - getTop());
                mBackgroundSizeChanged = false;
            }

            if ((scrollX | scrollY) == 0) {
                background.draw(canvas);
            } else {
                canvas.translate(scrollX, scrollY);
                background.draw(canvas);
                canvas.translate(-scrollX, -scrollY);
            }
        }

        final Layout layout = getLayout();
        final RectF rect = mRect;
        final int left = getCompoundPaddingLeft();
        final int top = getExtendedPaddingTop();

        rect.set(left + layout.getLineLeft(0) - mPaddingH,
                top + layout.getLineTop(0) -  mPaddingV,
                Math.min(left + layout.getLineRight(0) + mPaddingH, getScrollX() + getRight() - getLeft()),
                top + layout.getLineBottom(0) + mPaddingV);
        canvas.drawRoundRect(rect, mCornerRadius, mCornerRadius, mPaint);

        super.draw(canvas);
        if (Launcher.BADGES) drawBadge(canvas); // Tweaks
    }

    // Tweaks: notification badge of TouchWiz 3.0 (Galaxy S II, 2.3), BubbleTextView.drawBadge()
    // of TouchWiz30Launcher with its resources: homescreen_menu_noti_bg, badge_fontSizeNormal
    // 16dp, badge_fontSizeSmall 12dp (100 and more), badge_widthCenterToCenterOffset 21dp,
    // badge_topToTopOffset -1.34dp. The offset from the center follows the icon size
    // (Utilities.iconScale(), TouchWiz's icons were 48dp).
    private static Drawable sBadgeDrawable;
    private static Paint sBadgeTextPaint;
    private static final Rect sBadgePadding = new Rect();
    private static final Rect sBadgeTextBounds = new Rect();

    private void drawBadge(Canvas canvas) {
        final Object tag = getTag();
        if (!(tag instanceof ShortcutInfo)) return;
        final android.content.Intent intent = ((ShortcutInfo) tag).intent;
        if (intent == null) return;
        final String pkg = intent.getComponent() != null
                ? intent.getComponent().getPackageName() : intent.getPackage();
        final int count = BadgeListener.getCount(pkg);
        if (count <= 0) return;
        drawBadge(getContext(), canvas, getPaint().getTypeface(), count, getScrollX(),
                getWidth(), getScrollY() + getPaddingTop(), Utilities.iconScale(getContext()));
    }

    /**
     * Tweaks: the badge for an icon whose top is iconTop and which is centered in the
     * width baseWidth from baseLeft; centerOffsetScale scales the offset from the center.
     */
    static void drawBadge(android.content.Context context, Canvas canvas,
            android.graphics.Typeface typeface, int count, int baseLeft, int baseWidth,
            int iconTop, float centerOffsetScale) {
        final android.content.res.Resources res = context.getResources();
        final float density = res.getDisplayMetrics().density;
        if (sBadgeDrawable == null) {
            sBadgeDrawable = res.getDrawable(R.drawable.tw_badge_bg);
            sBadgeTextPaint = new Paint();
            sBadgeTextPaint.setAntiAlias(true);
            sBadgeTextPaint.setColor(0xffffffff);
            sBadgeTextPaint.setTextAlign(Paint.Align.CENTER);
        }
        final Drawable badge = sBadgeDrawable;
        final Paint paint = sBadgeTextPaint;
        paint.setTypeface(typeface);
        paint.setTextSize(Math.round((count >= 100 ? 12 : 16) * density));

        final int iwidth = badge.getIntrinsicWidth();
        final int iheight = badge.getIntrinsicHeight();
        badge.getPadding(sBadgePadding);
        final Rect p = sBadgePadding;
        final int defaultContentsWidth = iwidth - p.left - p.right;
        final int defaultContentsHeight = iheight - p.top - p.bottom;
        final String badgeText = Integer.toString(count);
        paint.getTextBounds(badgeText, 0, badgeText.length(), sBadgeTextBounds);
        final int contentsWidth = Math.max(sBadgeTextBounds.width(), defaultContentsWidth);
        final int contentsHeight = Math.max(sBadgeTextBounds.height(), defaultContentsHeight);
        final int width = contentsWidth + p.left + p.right;
        final int height = contentsHeight + p.top + p.bottom;
        final int left = baseLeft + (baseWidth - width) / 2
                + Math.round(21 * density * centerOffsetScale);
        final int top = iconTop + Math.round(-1.34f * density);
        badge.setBounds(left, top, left + width, top + height);
        badge.draw(canvas);
        canvas.drawText(badgeText, width / 2 + left + 1,
                height / 2 + top - (paint.ascent() + paint.descent()) / 2f - 1f, paint);
    }

    /**
     * Tweaks: the badge on a dock button, scaled with its icon (TouchWiz's badge is sized for
     * 48dp icons, the dock's are smaller); drawn in the button's overlay.
     */
    static final class DockBadge extends Drawable {
        private final android.widget.ImageView mButton;
        private final java.util.function.Supplier<String> mPackage;
        private final android.graphics.Typeface mTypeface;

        DockBadge(android.widget.ImageView button, java.util.function.Supplier<String> pkg,
                android.graphics.Typeface typeface) {
            mButton = button;
            mPackage = pkg;
            mTypeface = typeface;
        }

        @Override
        public void draw(Canvas canvas) {
            if (!Launcher.BADGES) return;
            final int count = BadgeListener.getCount(mPackage.get());
            final Drawable icon = mButton.getDrawable();
            if (count <= 0 || icon == null) return;
            final android.view.View v = mButton;
            final int contentLeft = v.getPaddingLeft();
            final int contentWidth = v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
            final int contentHeight = v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
            final int iconTop = v.getPaddingTop()
                    + (contentHeight - icon.getIntrinsicHeight()) / 2; // scaleType center
            final float scale = icon.getIntrinsicWidth()
                    / (48 * v.getResources().getDisplayMetrics().density);
            final int count2 = canvas.save();
            canvas.scale(scale, scale, contentLeft + contentWidth / 2f, iconTop);
            drawBadge(v.getContext(), canvas, mTypeface, count, contentLeft, contentWidth,
                    iconTop, 1f);
            canvas.restoreToCount(count2);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBackground.setCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBackground.setCallback(null);
    }
}
