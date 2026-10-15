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

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout.Alignment;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.Log;
import android.content.res.Resources;
import android.content.Context;

import com.android.launcher.R;

/**
 * Various utilities shared amongst the Launcher's classes.
 */
final class Utilities {
    private static final String TAG = "Launcher.Utilities";

    private static final boolean TEXT_BURN = false;

    private static int sIconWidth = -1;
    private static int sIconHeight = -1;
    private static int sIconTextureWidth = -1;
    private static int sIconTextureHeight = -1;

    private static final Paint sPaint = new Paint();
    private static final Paint sBlurPaint = new Paint();
    private static final Paint sGlowColorPressedPaint = new Paint();
    private static final Paint sGlowColorFocusedPaint = new Paint();
    private static final Paint sDisabledPaint = new Paint();
    private static final Rect sBounds = new Rect();
    private static final Rect sOldBounds = new Rect();
    private static final Canvas sCanvas = new Canvas();

    static {
        sCanvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.DITHER_FLAG,
                Paint.FILTER_BITMAP_FLAG));
    }

    static Bitmap centerToFit(Bitmap bitmap, int width, int height, Context context) {
        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();

        if (bitmapWidth < width || bitmapHeight < height) {
            int color = context.getResources().getColor(R.color.window_background);

            Bitmap centered = Bitmap.createBitmap(bitmapWidth < width ? width : bitmapWidth,
                    bitmapHeight < height ? height : bitmapHeight, Bitmap.Config.RGB_565);
            centered.setDensity(bitmap.getDensity());
            Canvas canvas = new Canvas(centered);
            canvas.drawColor(color);
            canvas.drawBitmap(bitmap, (width - bitmapWidth) / 2.0f, (height - bitmapHeight) / 2.0f,
                    null);

            bitmap = centered;
        }

        return bitmap;
    }

    static int sColors[] = { 0xffff0000, 0xff00ff00, 0xff0000ff };
    static int sColorIndex = 0;

    /**
     * Returns a bitmap suitable for the all apps view.  The bitmap will be a power
     * of two sized ARGB_8888 bitmap that can be used as a gl texture.
     */
    static Bitmap createIconBitmap(Drawable icon, Context context) {
        synchronized (sCanvas) { // we share the statics :-(
            if (sIconWidth == -1) {
                initStatics(context);
            }

            int width = sIconWidth;
            int height = sIconHeight;

            if (icon instanceof PaintDrawable) {
                PaintDrawable painter = (PaintDrawable) icon;
                painter.setIntrinsicWidth(width);
                painter.setIntrinsicHeight(height);
            } else if (icon instanceof BitmapDrawable) {
                // Ensure the bitmap has a density.
                BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
                Bitmap bitmap = bitmapDrawable.getBitmap();
                if (bitmap.getDensity() == Bitmap.DENSITY_NONE) {
                    bitmapDrawable.setTargetDensity(context.getResources().getDisplayMetrics());
                }
            }
            int sourceWidth = icon.getIntrinsicWidth();
            int sourceHeight = icon.getIntrinsicHeight();

            if (sourceWidth > 0 && sourceWidth > 0) {
                // There are intrinsic sizes.
                if (width < sourceWidth || height < sourceHeight) {
                    // It's too big, scale it down.
                    final float ratio = (float) sourceWidth / sourceHeight;
                    if (sourceWidth > sourceHeight) {
                        height = (int) (width / ratio);
                    } else if (sourceHeight > sourceWidth) {
                        width = (int) (height * ratio);
                    }
                } else if (sourceWidth < width && sourceHeight < height) {
                    // It's small, use the size they gave us.
                    width = sourceWidth;
                    height = sourceHeight;
                }
            }

            // no intrinsic size --> use default size
            int textureWidth = sIconTextureWidth;
            int textureHeight = sIconTextureHeight;

            final Bitmap bitmap = Bitmap.createBitmap(textureWidth, textureHeight,
                    Bitmap.Config.ARGB_8888);
            final Canvas canvas = sCanvas;
            canvas.setBitmap(bitmap);

            final int left = (textureWidth-width) / 2;
            final int top = (textureHeight-height) / 2;

            if (false) {
                // draw a big box for the icon for debugging
                canvas.drawColor(sColors[sColorIndex]);
                if (++sColorIndex >= sColors.length) sColorIndex = 0;
                Paint debugPaint = new Paint();
                debugPaint.setColor(0xffcccc00);
                canvas.drawRect(left, top, left+width, top+height, debugPaint);
            }

            sOldBounds.set(icon.getBounds());
            if (Launcher.ICON_SHAPE != 0
                    && icon instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                drawAdaptiveIcon((android.graphics.drawable.AdaptiveIconDrawable) icon,
                        canvas, left, top, width, height); // Tweaks: icon shape
            } else {
                icon.setBounds(left, top, left+width, top+height);
                icon.draw(canvas);
            }
            icon.setBounds(sOldBounds);

            return bitmap;
        }
    }

    /*
     * Tweaks: icon shapes. The masks of AOSP's icon shape overlays (config_icon_mask,
     * 100x100 viewport); circle is the AOSP default. 0 = the system's shape.
     */
    static final String[] ICON_SHAPE_MASKS = {
        null,
        "M50,0A50,50,0,1,1,50,100A50,50,0,1,1,50,0Z", // circle (default)
        "M50,0 C10,0 0,10 0,50 0,90 10,100 50,100 90,100 100,90 100,50 100,10 90,0 50,0 Z",
        "M50,0L88,0 C94.4,0 100,5.4 100 12 L100,88 C100,94.6 94.6 100 88 100 L12,100 "
                + "C5.4,100 0,94.6 0,88 L0 12 C0 5.4 5.4 0 12 0 L50,0 Z", // rounded rect
        "M50,0 C77.6,0 100,22.4 100,50 L100,88 C100,94.6 94.6,100 88,100 L50,100 "
                + "C22.4 100 0 77.6 0 50C0 22.4 22.4 0 50 0 Z", // teardrop
        "M50,0L100,0 100,100 0,100 0,0z", // square
    };

    /** Tweaks: like AdaptiveIconDrawable.draw(), with the chosen mask instead. */
    private static void drawAdaptiveIcon(android.graphics.drawable.AdaptiveIconDrawable icon,
            Canvas canvas, int left, int top, int width, int height) {
        final Bitmap layers = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(layers);
        // The layers are 1.5x the icon (25% extra on each side), centered
        final int ox = width / 4, oy = height / 4;
        for (Drawable d : new Drawable[] { icon.getBackground(), icon.getForeground() }) {
            if (d == null) continue;
            d.setBounds(-ox, -oy, width + ox, height + oy);
            d.draw(c);
        }
        final android.graphics.Path mask = parseMask(ICON_SHAPE_MASKS[Launcher.ICON_SHAPE]);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(width / 100.f, height / 100.f);
        mask.transform(m);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawPath(mask, p);
        canvas.drawBitmap(layers, left, top, null);
        layers.recycle();
    }

    /** Tweaks: absolute M/L/C/A/Z path data, enough for the masks above. */
    private static android.graphics.Path parseMask(String data) {
        final android.graphics.Path path = new android.graphics.Path();
        final java.util.regex.Matcher t = java.util.regex.Pattern
                .compile("[MLCAZmlcaz]|-?[0-9]*\\.?[0-9]+").matcher(data);
        final java.util.ArrayList<String> tokens = new java.util.ArrayList<String>();
        while (t.find()) tokens.add(t.group());
        char cmd = 'M';
        float x = 0, y = 0;
        for (int i = 0; i < tokens.size();) {
            final String tok = tokens.get(i);
            if (Character.isLetter(tok.charAt(0))) {
                cmd = Character.toUpperCase(tok.charAt(0));
                i++;
                if (cmd == 'Z') {
                    path.close();
                    continue;
                }
            }
            switch (cmd) {
                case 'M':
                    x = Float.parseFloat(tokens.get(i)); y = Float.parseFloat(tokens.get(i + 1));
                    path.moveTo(x, y);
                    i += 2;
                    cmd = 'L'; // following pairs are line-tos
                    break;
                case 'L':
                    x = Float.parseFloat(tokens.get(i)); y = Float.parseFloat(tokens.get(i + 1));
                    path.lineTo(x, y);
                    i += 2;
                    break;
                case 'C':
                    final float x1 = Float.parseFloat(tokens.get(i));
                    final float y1 = Float.parseFloat(tokens.get(i + 1));
                    final float x2 = Float.parseFloat(tokens.get(i + 2));
                    final float y2 = Float.parseFloat(tokens.get(i + 3));
                    x = Float.parseFloat(tokens.get(i + 4)); y = Float.parseFloat(tokens.get(i + 5));
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    i += 6;
                    break;
                case 'A': {
                    // only the half circles of the circle mask: rx,ry,rot,large,sweep,x,y
                    final float r = Float.parseFloat(tokens.get(i));
                    final float nx = Float.parseFloat(tokens.get(i + 5));
                    final float ny = Float.parseFloat(tokens.get(i + 6));
                    final float cx = (x + nx) / 2, cy = (y + ny) / 2;
                    final float start = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                    path.arcTo(new RectF(cx - r, cy - r, cx + r, cy + r), start, 180);
                    x = nx; y = ny;
                    i += 7;
                    break;
                }
                default:
                    i++;
            }
        }
        return path;
    }

    static void drawSelectedAllAppsBitmap(Canvas dest, int destWidth, int destHeight,
            boolean pressed, Bitmap src) {
        synchronized (sCanvas) { // we share the statics :-(
            if (sIconWidth == -1) {
                // We can't have gotten to here without src being initialized, which
                // comes from this file already.  So just assert.
                //initStatics(context);
                throw new RuntimeException("Assertion failed: Utilities not initialized");
            }

            dest.drawColor(0, PorterDuff.Mode.CLEAR);

            int[] xy = new int[2];
            Bitmap mask = src.extractAlpha(sBlurPaint, xy);

            float px = (destWidth - src.getWidth()) / 2;
            float py = (destHeight - src.getHeight()) / 2;
            dest.drawBitmap(mask, px + xy[0], py + xy[1],
                    pressed ? sGlowColorPressedPaint : sGlowColorFocusedPaint);

            mask.recycle();
        }
    }

    /**
     * Returns a Bitmap representing the thumbnail of the specified Bitmap.
     * The size of the thumbnail is defined by the dimension
     * android.R.dimen.launcher_application_icon_size.
     *
     * @param bitmap The bitmap to get a thumbnail of.
     * @param context The application's context.
     *
     * @return A thumbnail for the specified bitmap or the bitmap itself if the
     *         thumbnail could not be created.
     */
    static Bitmap resampleIconBitmap(Bitmap bitmap, Context context) {
        synchronized (sCanvas) { // we share the statics :-(
            if (sIconWidth == -1) {
                initStatics(context);
            }

            if (bitmap.getWidth() == sIconWidth && bitmap.getHeight() == sIconHeight) {
                return bitmap;
            } else {
                return createIconBitmap(new BitmapDrawable(bitmap), context);
            }
        }
    }

    static Bitmap drawDisabledBitmap(Bitmap bitmap, Context context) {
        synchronized (sCanvas) { // we share the statics :-(
            if (sIconWidth == -1) {
                initStatics(context);
            }
            final Bitmap disabled = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            final Canvas canvas = sCanvas;
            canvas.setBitmap(disabled);
            
            canvas.drawBitmap(bitmap, 0.0f, 0.0f, sDisabledPaint);

            return disabled;
        }
    }

    private static void initStatics(Context context) {
        final Resources resources = context.getResources();
        final DisplayMetrics metrics = resources.getDisplayMetrics();
        final float density = metrics.density;

        sIconWidth = sIconHeight = (int) resources.getDimension(android.R.dimen.app_icon_size);
        sIconTextureWidth = sIconTextureHeight = sIconWidth + 2;

        sBlurPaint.setMaskFilter(new BlurMaskFilter(5 * density, BlurMaskFilter.Blur.NORMAL));
        sGlowColorPressedPaint.setColor(0xffffc300);
        // TableMaskFilter.CreateClipTable(0, 30) is hidden API: alpha 0..30 -> 0..255 (clamped)
        sGlowColorPressedPaint.setColorFilter(createClipTableFilter(0, 30));
        sGlowColorFocusedPaint.setColor(0xffff8e00);
        sGlowColorFocusedPaint.setColorFilter(createClipTableFilter(0, 30));

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.2f);
        sDisabledPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        sDisabledPaint.setAlpha(0x88);
    }

    private static ColorMatrixColorFilter createClipTableFilter(int min, int max) {
        final float scale = 255f / (max - min);
        return new ColorMatrixColorFilter(new float[] {
                1, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, scale, -min * scale });
    }

    static class BubbleText {
        private static final int MAX_LINES = 2;

        private final TextPaint mTextPaint;

        private final RectF mBubbleRect = new RectF();

        private final float mTextWidth;
        private final int mLeading;
        private final int mFirstLineY;
        private final int mLineHeight;

        private final int mBitmapWidth;
        private final int mBitmapHeight;
        private final int mDensity;

        BubbleText(Context context) {
            final Resources resources = context.getResources();

            final DisplayMetrics metrics = resources.getDisplayMetrics();
            // Port: the labels are textures for AllApps3D, laid out in hdpi px
            // (title_texture_width is 120px). AllApps3D draws them at their original
            // physical size, so scale the px values from hdpi to this density.
            final float scale = metrics.density;
            final float k = scale / 1.5f;
            mDensity = metrics.densityDpi;

            final float paddingLeft = 2.0f * scale;
            final float paddingRight = 2.0f * scale;
            final float cellWidth = resources.getDimension(R.dimen.title_texture_width) * k;

            RectF bubbleRect = mBubbleRect;
            bubbleRect.left = 0;
            bubbleRect.top = 0;
            bubbleRect.right = (int) cellWidth;

            mTextWidth = cellWidth - paddingLeft - paddingRight;

            TextPaint textPaint = mTextPaint = new TextPaint();
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(13*scale);
            textPaint.setColor(0xffffffff);
            textPaint.setAntiAlias(true);
            if (TEXT_BURN) {
                textPaint.setShadowLayer(8, 0, 0, 0xff000000);
            }

            float ascent = -textPaint.ascent();
            float descent = textPaint.descent();
            float leading = 0.0f;//(ascent+descent) * 0.1f;
            mLeading = (int)(leading + 0.5f);
            mFirstLineY = (int)(leading + ascent + 0.5f);
            mLineHeight = (int)(leading + ascent + descent + 0.5f);

            mBitmapWidth = (int)(mBubbleRect.width() + 0.5f);
            // Port: power-of-two height at the original scale, then scaled, so the texture
            // keeps the 120x64 proportions AllApps3D draws it with.
            mBitmapHeight = (int) (roundToPow2(
                    (int)((((MAX_LINES * mLineHeight) + leading) / k) + 0.5f)) * k + 0.5f);

            mBubbleRect.offsetTo((mBitmapWidth-mBubbleRect.width())/2, 0);

            if (false) {
                Log.d(TAG, "mBitmapWidth=" + mBitmapWidth + " mBitmapHeight="
                        + mBitmapHeight + " w=" + ((int)(mBubbleRect.width() + 0.5f))
                        + " h=" + ((int)((MAX_LINES * mLineHeight) + leading + 0.5f)));
            }
        }

        /** You own the bitmap after this and you must call recycle on it. */
        Bitmap createTextBitmap(String text) {
            Bitmap b = Bitmap.createBitmap(mBitmapWidth, mBitmapHeight, Bitmap.Config.ALPHA_8);
            b.setDensity(mDensity);
            Canvas c = new Canvas(b);

            StaticLayout layout = new StaticLayout(text, mTextPaint, (int)mTextWidth,
                    Alignment.ALIGN_CENTER, 1, 0, true);
            int lineCount = layout.getLineCount();
            if (lineCount > MAX_LINES) {
                lineCount = MAX_LINES;
            }
            //if (!TEXT_BURN && lineCount > 0) {
                //RectF bubbleRect = mBubbleRect;
                //bubbleRect.bottom = height(lineCount);
                //c.drawRoundRect(bubbleRect, mCornerRadius, mCornerRadius, mRectPaint);
            //}
            for (int i=0; i<lineCount; i++) {
                //int x = (int)((mBubbleRect.width() - layout.getLineMax(i)) / 2.0f);
                //int y = mFirstLineY + (i * mLineHeight);
                final String lineText = text.substring(layout.getLineStart(i), layout.getLineEnd(i));
                int x = (int)(mBubbleRect.left
                        + ((mBubbleRect.width() - mTextPaint.measureText(lineText)) * 0.5f));
                int y = mFirstLineY + (i * mLineHeight);
                c.drawText(lineText, x, y, mTextPaint);
            }

            return b;
        }

        private int height(int lineCount) {
            return (int)((lineCount * mLineHeight) + mLeading + mLeading + 0.0f);
        }

        int getBubbleWidth() {
            return (int)(mBubbleRect.width() + 0.5f);
        }

        int getMaxBubbleHeight() {
            return height(MAX_LINES);
        }

        int getBitmapWidth() {
            return mBitmapWidth;
        }

        int getBitmapHeight() {
            return mBitmapHeight;
        }
    }

    /** Only works for positive numbers. */
    static int roundToPow2(int n) {
        int orig = n;
        n >>= 1;
        int mask = 0x8000000;
        while (mask != 0 && (n & mask) == 0) {
            mask >>= 1;
        }
        while (mask != 0) {
            n |= mask;
            mask >>= 1;
        }
        n += 1;
        if (n != orig) {
            n <<= 1;
        }
        return n;
    }

    /**
     * Port: Gingerbread's animations use fixed durations (Scroller, view animations,
     * tweens), which modern Android doesn't scale. Applies the system animation speed
     * (animator duration scale, developer options); at least 1 ms when animations are off.
     */
    static int scaleDuration(int duration) {
        return Math.max(1, Math.round(duration
                * android.animation.ValueAnimator.getDurationScale()));
    }
}
