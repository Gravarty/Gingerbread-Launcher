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

package com.android.magicsmoke;

import static com.gravarty.wallpaper.gl.RsLib.*;

import com.android.magicsmoke.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.MotionEvent;

import com.gravarty.wallpaper.gl.GlScene;
import com.gravarty.wallpaper.gl.RsMatrix;

/**
 * Port: MagicSmokeRS and res/raw/clouds.rs (AOSP packages/wallpapers/MagicSmoke,
 * android-2.3.7_r1) on GlScene instead of RenderScript.
 */
class MagicSmokeRS extends GlScene implements OnSharedPreferenceChangeListener {

    static class WorldState {
        public float mXOffset;
        public float mTilt;
        public int   mPreset;
        public int   mTextureMask;
        public int   mRotate;
        public int   mTextureSwap;
        public int   mProcessTextureMode;
        public int   mBackCol;
        public int   mLowCol;
        public int   mHighCol;
        public float mAlphaMul;
        public int   mPreMul;
        public int   mBlendFunc;
    }
    WorldState mWorldState = new WorldState();

    private ProgramStore mPfsBackgroundOne;
    private ProgramStore mPfsBackgroundSrc;
    private ProgramFragment mPfBackground;
    private Sampler mSampler;
    private int[][] mSourceTextures;
    private Texture[] mRealTextures;
    private int[][] mRealTexturesData;
    private ProgramVertex mPVBackground;
    private MatrixAllocation mPVAlloc;

    private Context mContext;
    private SharedPreferences mSharedPref;

    static class Preset {
        Preset(int processmode, int backcol, int locol, int hicol, float mul, int mask,
                 boolean rot, int blend, boolean texswap, boolean premul) {
            mProcessTextureMode = processmode;
            mBackColor = backcol;
            mLowColor = locol;
            mHighColor = hicol;
            mAlphaMul = mul;
            mTextureMask = mask;
            mRotate = rot;
            mBlendFunc = blend;
            mTextureSwap = texswap;
            mPreMul = premul;
        }
        public int mProcessTextureMode;
        public int mBackColor;
        public int mLowColor;
        public int mHighColor;
        public float mAlphaMul;
        public int mTextureMask;
        public boolean mRotate;
        public int mBlendFunc;
        public boolean mTextureSwap;
        public boolean mPreMul;
    }

    public static final int DEFAULT_PRESET = 4;
    public static final Preset [] mPreset = new Preset[] {
        //       proc    back     low       high     alph  mask  rot  blend  swap premul
        new Preset(1,  0x000000, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, false, false),
        new Preset(1,  0x0000ff, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, false, false),
        new Preset(1,  0x00ff00, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, false, false),
        new Preset(1,  0x00ff00, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, false, true),
        new Preset(1,  0x00ff00, 0x00ff00, 0xffffff, 2.5f, 0x1f, true,  0, true, true),
        new Preset(1,  0x800000, 0xff0000, 0xffffff, 2.5f, 0x1f, true,  0, true,  false),
        new Preset(0,  0x000000, 0x000000, 0xffffff, 0.0f, 0x1f, true,  0, false, false),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 2.0f, 0x1f, true,  0, true,  false),
        new Preset(1,  0x008000, 0x00ff00, 0xffffff, 2.5f, 0x1f, true,  0, true,  false),
        new Preset(1,  0x800000, 0xff0000, 0xffffff, 2.5f, 0x1f, true,  0, true, true),
        new Preset(1,  0x808080, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, false, true),
        new Preset(1,  0x0000ff, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, false, true),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 1.5f, 0x1f, false, 0, false, true),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 2.0f, 0x1f, true,  0, true, true),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 1.5f, 0x1f, true,  0, true, true),
        new Preset(1,  0x808080, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, false, false),
        new Preset(1,  0x000000, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, true, false),
        new Preset(2,  0x000000, 0x000070, 0xff2020, 2.5f, 0x1f, true,  0, false, false),
        new Preset(2,  0x6060ff, 0x000070, 0xffffff, 2.5f, 0x1f, true,  0, false, false),
        new Preset(3,  0x0000f0, 0x000000, 0xffffff, 2.0f, 0x0f, true,  0, true, false),
    };

    private float mTouchY;

    MagicSmokeRS(Context context, int width, int height) {
        super(width, height);
        mWidth = width;
        mHeight = height;
        mWorldState.mTilt = 0;
        mContext = context;
        mSharedPref = mContext.getSharedPreferences("magicsmoke", Context.MODE_PRIVATE);
        makeNewState();
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Port: the state goes to the render thread like mState.data() did
        queue(new Runnable() {
            public void run() {
                makeNewState();
            }
        });
    }

    void makeNewState() {
        int p = mSharedPref.getInt("preset", DEFAULT_PRESET);
        if (p >= mPreset.length) {
            p = 0;
        }
        mWorldState.mPreset = p;
        mWorldState.mTextureMask = mPreset[p].mTextureMask;
        mWorldState.mRotate = mPreset[p].mRotate ? 1 : 0;
        mWorldState.mTextureSwap = mPreset[p].mTextureSwap ? 1 : 0;
        mWorldState.mProcessTextureMode = mPreset[p].mProcessTextureMode;
        mWorldState.mBackCol = mPreset[p].mBackColor;
        mWorldState.mLowCol = mPreset[p].mLowColor;
        mWorldState.mHighCol = mPreset[p].mHighColor;
        mWorldState.mAlphaMul = mPreset[p].mAlphaMul;
        mWorldState.mPreMul = mPreset[p].mPreMul ? 1 : 0;
        mWorldState.mBlendFunc = mPreset[p].mBlendFunc;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mPVAlloc != null) {
            mPVAlloc.setupProjectionNormalized(width, height);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        // Port: the movement in pixels of the original screen, whose short side was 480
        final float scale = 480.0f / Math.min(mWidth, mHeight);
        switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mTouchY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - mTouchY;
                mTouchY += dy;
                dy *= scale;
                dy /= 20;
                dy += mWorldState.mTilt;
                if (dy > 4) {
                    dy = 4;
                } else if (dy < -4) {
                    dy = -4;
                }
                mWorldState.mTilt = dy;
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, float xStep, float yStep,
            int xPixels, int yPixels) {
        // update our state, then push it to the renderscript
        mWorldState.mXOffset = xOffset;
    }

    @Override
    public void stop() {
        mSharedPref.unregisterOnSharedPreferenceChangeListener(this);
        super.stop();
    }

    @Override
    public void start() {
        super.start();
        mSharedPref.registerOnSharedPreferenceChangeListener(this);
        queue(new Runnable() {
            public void run() {
                makeNewState();
            }
        });
    }

    float alphafactor;

    void loadBitmap(int id, int index, String name, float alphamul, int lowcol, int highcol) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inScaled = false; // Port: 256x256 on any density, not only on hdpi
        Bitmap in = BitmapFactory.decodeResource(mResources, id, opts);

        int pixels[] = new int[65536];
        in.getPixels(pixels, 0, 256, 0, 0, 256, 256);
        mRealTextures[index] = createRgba(256, 256);
        mRealTexturesData[index] = new int[65536];
        mSourceTextures[index] = pixels;
        in.recycle();
    }

    void loadBitmaps() {
        alphafactor = 1f;
        float alphamul = mPreset[mWorldState.mPreset].mAlphaMul;
        int lowcol = mPreset[mWorldState.mPreset].mLowColor;
        int highcol = mPreset[mWorldState.mPreset].mHighColor;
        //Log.i("@@@@", "preset " + mWorldState.mPreset + ", mul: " + alphamul +
        //        ", colors: " + Integer.toHexString(lowcol) + "/" + Integer.toHexString(highcol));

        // TODO: using different high and low colors for each layer offers some cool effects too
        loadBitmap(R.drawable.noise1, 0, "Tnoise1", alphamul, lowcol, highcol);
        loadBitmap(R.drawable.noise2, 1, "Tnoise2", alphamul, lowcol, highcol);
        loadBitmap(R.drawable.noise3, 2, "Tnoise3", alphamul, lowcol, highcol);
        loadBitmap(R.drawable.noise4, 3, "Tnoise4", alphamul, lowcol, highcol);
        loadBitmap(R.drawable.noise5, 4, "Tnoise5", alphamul, lowcol, highcol);
    }

    @Override
    protected void createScript() {
        // First set up the coordinate system and such
        mPVBackground = new ProgramVertex();
        mPVAlloc = new MatrixAllocation();
        mPVBackground.bindAllocation(mPVAlloc);
        mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

        mSourceTextures = new int[5][];
        mRealTextures = new Texture[5];
        mRealTexturesData = new int[5][];

        loadBitmaps();

        mSampler = new Sampler(LINEAR, LINEAR, WRAP, WRAP);

        {
            mPfBackground = new ProgramFragment(REPLACE, RGBA, false);
            mPfBackground.bindSampler(mSampler, 0);
        }

        {
            // DepthFunc.EQUAL, no depth mask: no depth buffer, so no effect
            mPfsBackgroundOne = new ProgramStore(ONE, ONE_MINUS_SRC_ALPHA, true);
            mPfsBackgroundSrc = new ProgramStore(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, true);
        }

        // #pragma stateVertex(PVBackground), stateFragment(PFBackground)
        setRootState(mPVBackground, mPfBackground, null);
        setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        init();
    }

    // ------------------------------------------------------------------ clouds.rs

    private final float[] xshift = new float[5];
    private final float[] rotation = new float[5];
    private final float[] scale = new float[5];
    private float alphafactor_rs;
    private int currentpreset;
    private int lastuptime;
    private float timedelta;

    private void drawCloud(float[] ident, Texture id, int idx) {
        final WorldState State = mWorldState;
        float[] mat1 = new float[16];
        float z = -8.f * idx;
        RsMatrix.load(mat1, ident);
        RsMatrix.translate(mat1, -State.mXOffset * 8.f * idx, -State.mTilt * idx / 3.f, 0.f);
        RsMatrix.rotate(mat1, rotation[idx], 0.f, 0.f, 1.f);
        vpLoadModelMatrix(mat1);

        bindTexture(mPfBackground, 0, id);
        drawQuadTexCoords(
                -1200.0f, -1200.0f, z,        // space
                    0.f + xshift[idx], 0.f,        // texture
                1200, -1200.0f, z,            // space
                    scale[idx] + xshift[idx], 0.f,         // texture
                1200, 1200.0f, z,            // space
                    scale[idx] + xshift[idx], scale[idx],         // texture
                -1200.0f, 1200.0f, z,        // space
                    0.f + xshift[idx], scale[idx]);       // texture
    }

    private void drawClouds(float[] ident) {
        final WorldState State = mWorldState;

        int i;

        float[] mat1 = new float[16];

        RsMatrix.load(mat1, ident);

        if (State.mRotate != 0) {
            rotation[0] += 0.10 * timedelta;
            rotation[1] += 0.102f * timedelta;
            rotation[2] += 0.106f * timedelta;
            rotation[3] += 0.114f * timedelta;
            rotation[4] += 0.123f * timedelta;
        }

        int mask = State.mTextureMask;
        if ((mask & 1) != 0) {
            xshift[0] += 0.0010f * timedelta;
            if (State.mTextureSwap != 0) {
                drawCloud(mat1, mRealTextures[4], 0);
            } else {
                drawCloud(mat1, mRealTextures[0], 0);
            }
        }

        if ((mask & 2) != 0) {
            xshift[1] += 0.00106 * timedelta;
            drawCloud(mat1, mRealTextures[1], 1);
        }

        if ((mask & 4) != 0) {
            xshift[2] += 0.00114f * timedelta;
            drawCloud(mat1, mRealTextures[2], 2);
        }

        if ((mask & 8) != 0) {
            xshift[3] += 0.00118f * timedelta;
            drawCloud(mat1, mRealTextures[3], 3);
        }

        if ((mask & 16) != 0) {
            xshift[4] += 0.00127f * timedelta;
            drawCloud(mat1, mRealTextures[4], 4);
        }

        // Make sure the texture coordinates don't continuously increase
        for(i = 0; i < 5; i++) {
            while (xshift[i] >= 1.f) {
                xshift[i] -= 1.f;
            }
        }
        // Make sure the rotation angles don't continuously increase
        for(i = 0; i < 5; i++) {
            while (rotation[i] >= 360.f) {
                rotation[i] -= 360.f;
            }
        }
    }

    private static int premul(int rgb, int a) {
        int r = (rgb >> 16) * a + 1;
        r = (r + (r >> 8)) >> 8;
        int g = ((rgb >> 8) & 0xff) * a + 1;
        g = (g + (g >> 8)) >> 8;
        int b = (rgb & 0xff) * a + 1;
        b = (b + (b >> 8)) >> 8;
        return r << 16 | g << 8 | b;
    }

    private void makeTexture(int[] src, int[] dst, Texture rsid) {
        final WorldState State = mWorldState;

        int x;
        int y;
        int pm = State.mPreMul;

        if (State.mProcessTextureMode == 1) {
            int lowcol = State.mLowCol;
            int highcol = State.mHighCol;

            for (y=0;y<256;y++) {
                for (x=0;x<256;x++) {
                    int pix = src[y*256+x];
                    int lum = pix & 0x00ff;
                    int newpix;
                    if (lum < 128) {
                        newpix = lowcol;
                        int newalpha = 255 - (lum * 2);
                        newalpha /= alphafactor_rs;
                        if (pm != 0) newpix = premul(newpix, newalpha);
                        newpix = newpix | (newalpha << 24);
                    } else {
                        newpix = highcol;
                        int newalpha = (lum - 128) * 2;
                        newalpha /= alphafactor_rs;
                        if (pm != 0) newpix = premul(newpix, newalpha);
                        newpix = newpix | (newalpha << 24);
                    }
                    // have ARGB, need ABGR
                    newpix = (newpix & 0xff00ff00) | ((newpix & 0xff) << 16) | ((newpix >> 16) & 0xff);
                    dst[y*256+x] = newpix;
                }
            }
            alphafactor_rs *= State.mAlphaMul;
        } else if (State.mProcessTextureMode == 2) {
            int lowcol = State.mLowCol;
            int highcol = State.mHighCol;
            float scale = 255.f / (255.f - lowcol);

            for (y=0;y<256;y++) {
                for (x=0;x<256;x++) {
                    int pix = src[y*256+x];
                    int alpha = pix & 0x00ff;
                    if (alpha < lowcol) {
                        alpha = 0;
                    } else {
                        alpha = (int) ((alpha - lowcol) * scale);
                    }
                    alpha /= alphafactor_rs;
                    int newpix = highcol;
                    if (pm != 0) newpix = premul(newpix, alpha);
                    newpix = newpix | (alpha << 24);
                    // have ARGB, need ABGR
                    newpix = (newpix & 0xff00ff00) | ((newpix & 0xff) << 16) | ((newpix >> 16) & 0xff);
                    dst[y*256+x] = newpix;
                }
            }
            alphafactor_rs *= State.mAlphaMul;
        } else if (State.mProcessTextureMode == 3) {
            int lowcol = State.mLowCol;
            int highcol = State.mHighCol;
            float scale = 255.f / (255.f - lowcol);

            for (y=0;y<256;y++) {
                for (x=0;x<256;x++) {
                    int pix = src[y*256+x];
                    int lum = pix & 0x00ff;
                    int newpix;
                    if (lum < 128) lum *= 2;
                    else lum = (255 - (lum - 128) * 2);
                    if (lum < 128) {
                        newpix = lowcol;
                        int newalpha = 255 - (lum * 2);
                        newalpha /= alphafactor_rs;
                        if (pm != 0) newpix = premul(newpix, newalpha);
                        newpix = newpix | (newalpha << 24);
                    } else {
                        newpix = highcol;
                        int newalpha = (lum - 128) * 2;
                        newalpha /= alphafactor_rs;
                        if (pm != 0) newpix = premul(newpix, newalpha);
                        newpix = newpix | (newalpha << 24);
                    }
                    // have ARGB, need ABGR
                    newpix = (newpix & 0xff00ff00) | ((newpix & 0xff) << 16) | ((newpix >> 16) & 0xff);
                    dst[y*256+x] = newpix;
                }
            }
            alphafactor_rs *= State.mAlphaMul;
        } else {

            int i = 0;
            for (y=0;y<256;y++) {
                for (x=0;x<256;x++) {
                    int rgb = src[i];
                    int a = (rgb >> 24) & 0xff;
                    rgb &= 0x00ffffff;
                    rgb = premul(rgb, a);
                    int newpix = (a << 24) | rgb;
                    newpix = (newpix & 0xff00ff00) | ((newpix & 0xff) << 16) | ((newpix >> 16) & 0xff);
                    dst[i++] = newpix;
                }
            }
        }
        uploadToTexture(rsid, dst);
    }

    private void makeTextures() {
        alphafactor_rs = 1.f;
        for (int i = 0; i < 5; i++) {
            makeTexture(mSourceTextures[i], mRealTexturesData[i], mRealTextures[i]);
        }
    }

    private void init() {
        int i;
        for (i=0;i<4;i++) {
            xshift[i] = 0.f;
            rotation[i] = 360.f * i / 5.f;
        }
        scale[0] = 4.0f; // changed below based on preset
        scale[1] = 3.0f;
        scale[2] = 3.4f;
        scale[3] = 3.8f;
        scale[4] = 4.2f;

        currentpreset = -1;
        lastuptime = uptimeMillis();
        timedelta = 0;
    }

    @Override
    protected int main() {
        final WorldState State = mWorldState;

        int i;
        float[] ident = new float[16];
        float masterscale = 0.0041f;// / (State->mXOffset * 4.f + 1.f);
        RsMatrix.loadIdentity(ident);
        RsMatrix.translate(ident, -State.mXOffset, 0.f, 0.f);
        RsMatrix.scale(ident, masterscale, masterscale, masterscale);
        //matrixRotate(ident, 0.f, 0.f, 0.f, 1.f);
        RsMatrix.rotate(ident, -State.mTilt, 1.f, 0.f, 0.f);

        if (State.mBlendFunc != 0) {
            bindProgramStore(mPfsBackgroundOne);
        } else {
            bindProgramStore(mPfsBackgroundSrc);
        }

        int now = uptimeMillis();
        timedelta = ((float)(now - lastuptime)) / 44.f;
        lastuptime = now;
        if (timedelta > 3) {
            // Limit the step adjustment factor to 3, so we don't get a sudden jump
            // after coming back from sleep.
            timedelta = 3;
        }

        i = State.mPreset;
        if (i != currentpreset) {
            currentpreset = i;
            int rgb = State.mBackCol;
            pfClearColor(
                ((float)((rgb >> 16)  & 0xff)) / 255.0f,
                ((float)((rgb >> 8)  & 0xff)) / 255.0f,
                ((float)(rgb & 0xff)) / 255.0f,
                1.0f);
            makeTextures();
        }

        if (State.mTextureSwap != 0) {
            scale[0] = .25f;
        } else {
            scale[0] = 4.f;
        }
        drawClouds(ident);

        return 55;
    }
}
