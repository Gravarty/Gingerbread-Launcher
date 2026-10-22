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

package com.android.musicvis.vis4;

import com.android.musicvis.R;
import com.android.musicvis.AudioCapture;

import android.os.Handler;

import com.gravarty.wallpaper.gl.GlScene;
import com.gravarty.wallpaper.gl.RsMatrix;

/**
 * Port: Visualization4RS and res/raw/vu.rs (AOSP packages/wallpapers/MusicVisualization,
 * android-2.3.7_r1) on GlScene instead of RenderScript.
 */
class Visualization4RS extends GlScene {

    private final Handler mHandler = new Handler();
    private final Runnable mDrawCube = new Runnable() {
        public void run() {
            updateWave();
        }
    };
    private boolean mVisible;

    private int mNeedlePos = 0;
    private int mNeedleSpeed = 0;
    // tweak this to get quicker/slower response
    private int mNeedleMass = 10;
    private int mSpringForceAtOrigin = 200;

    static class WorldState {
        public float mAngle;
        public int   mPeak;
    }
    WorldState mWorldState = new WorldState();

    private ProgramStore mPfsBackground;
    private ProgramFragment mPfBackground;
    private Sampler mSampler;
    private Texture[] mTextures;

    private ProgramVertex mPVBackground;
    private MatrixAllocation mPVAlloc;

    private AudioCapture mAudioCapture = null;
    private int [] mVizData = new int[1024];

    Visualization4RS(int width, int height) {
        super(width, height);
        mWidth = width;
        mHeight = height;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mPVAlloc != null) {
            mPVAlloc.setupProjectionNormalized(width, height);
        }
    }

    @Override
    protected void createScript() {
        // First set up the coordinate system and such
        mPVBackground = new ProgramVertex();
        mPVAlloc = new MatrixAllocation();
        mPVBackground.bindAllocation(mPVAlloc);
        mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

        updateWave();

        mTextures = new Texture[6];
        mTextures[0] = createFromBitmapResourceBoxed(R.drawable.background, true, false);
        mTextures[1] = createFromBitmapResourceBoxed(R.drawable.frame, true, false);
        mTextures[2] = createFromBitmapResourceBoxed(R.drawable.peak_on, true, false);
        mTextures[3] = createFromBitmapResourceBoxed(R.drawable.peak_off, true, false);
        mTextures[4] = createFromBitmapResourceBoxed(R.drawable.needle, true, false);
        mTextures[5] = createFromBitmapResourceBoxed(R.drawable.black, false, false);

        mSampler = new Sampler(LINEAR, LINEAR, WRAP, WRAP);

        {
            mPfBackground = new ProgramFragment(REPLACE, RGBA, false);
            mPfBackground.bindSampler(mSampler, 0);
        }

        {
            //builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            // without dithering there is severe banding
            mPfsBackground = new ProgramStore(ONE, ONE_MINUS_SRC_ALPHA, true);
        }

        // #pragma stateVertex(PVBackground), stateFragment(PFBackground),
        // stateStore(PFSBackground)
        setRootState(mPVBackground, mPfBackground, mPfsBackground);
        setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    @Override
    public void start() {
        super.start();
        mVisible = true;
        if (mAudioCapture == null) {
            mAudioCapture = new AudioCapture(AudioCapture.TYPE_PCM, 1024);
        }
        mAudioCapture.start();
        updateWave();
    }

    @Override
    public void stop() {
        super.stop();
        mVisible = false;
        if (mAudioCapture != null) {
            mAudioCapture.stop();
            mAudioCapture.release();
            mAudioCapture = null;
        }
    }

    void updateWave() {
        mHandler.removeCallbacks(mDrawCube);
        if (!mVisible) {
            return;
        }
        mHandler.postDelayed(mDrawCube, 20);

        int len = 0;
        if (mAudioCapture != null) {
            // arbitrary scalar to get better range: 512 = 2 * 256 (256 for 8 to 16 bit)
            mVizData = mAudioCapture.getFormattedData(512, 1);
            len = mVizData.length;
        }

        // Simulate running the signal through a rectifier by
        // taking the average of the absolute sample values.
        int volt = 0;
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                int val = mVizData[i];
                if (val < 0) {
                    val = -val;
                }
                volt += val;
            }
            volt = volt / len;
        }
        // There are several forces working on the needle: a force applied by the
        // electromagnet, a force applied by the spring,  and friction.
        // The force from the magnet is proportional to the current flowing
        // through its coil. We have to take in to account that the coil is an
        // inductive load, which means that an immediate change in applied voltage
        // will result in a gradual change in current, but also that current will
        // be induced by the movement of the needle.
        // The force from the spring is proportional to the position of the needle.
        // The friction force is a function of the speed of the needle, but so is
        // the current induced by the movement of the needle, so we can combine
        // them.


        // Add up the various forces, with some multipliers to make the movement
        // of the needle more realistic
        // 'volt' is for the applied voltage, which causes a current to flow through the coil
        // mNeedleSpeed * 3 is for the movement of the needle, which induces an opposite current
        // in the coil, and is also proportional to the friction
        // mNeedlePos + mSpringForceAtOrigin is for the force of the spring pushing the needle back
        int netforce = volt - mNeedleSpeed * 3 - (mNeedlePos + mSpringForceAtOrigin) ;
        int acceleration = netforce / mNeedleMass;
        mNeedleSpeed += acceleration;
        mNeedlePos += mNeedleSpeed;
        if (mNeedlePos < 0) {
            mNeedlePos = 0;
            mNeedleSpeed = 0;
        } else if (mNeedlePos > 32767) {
            if (mNeedlePos > 33333) {
                 mWorldState.mPeak = 10;
            }
            mNeedlePos = 32767;
            mNeedleSpeed = 0;
        }
        if (mWorldState.mPeak > 0) {
            mWorldState.mPeak--;
        }

        mWorldState.mAngle = 131f - (mNeedlePos / 410f); // ~80 degree range
    }

    // ------------------------------------------------------------------ vu.rs

    private final float[] mat1 = new float[16];

    @Override
    protected int main() {
        final WorldState State = mWorldState;

        float scale = 0.0041f;
        RsMatrix.loadRotate(mat1, 0.f, 0.f, 0.f, 1.f);
        RsMatrix.scale(mat1, scale, scale, scale);
        vpLoadModelMatrix(mat1);

        // draw the background image (416x233)
        bindTexture(mPfBackground, 0, mTextures[0]);
        drawQuadTexCoords(
                -208.0f, -33.0f, 0.0f,        // space
                    0.09375f, 0.9551f,        // texture
                208, -33.0f, 0.0f,            // space
                    0.90625f, 0.9551f,         // texture
                208, 200.0f, 0.0f,            // space
                    0.90625f, 0.0449f,         // texture
                -208.0f, 200.0f, 0.0f,        // space
                    0.09375f, 0.0449f);       // texture

        // draw the peak indicator light (56x58)
        if (State.mPeak > 0) {
            bindTexture(mPfBackground, 0, mTextures[2]);
        } else {
            bindTexture(mPfBackground, 0, mTextures[3]);
        }
        drawQuadTexCoords(
                140.0f, 70.0f, -1.0f,         // space
                    0.0625f, 0.953125f,        // texture
                196, 70.0f, -1.0f,            // space
                    0.9375f, 0.953125f,        // texture
                196, 128.0f, -1.0f,           // space
                    0.9375f, 0.046875f,        // texture
                140.0f, 128.0f, -1.0f,        // space
                    0.0625f, 0.046875f);       // texture



        // Draw the needle (88x262, center of rotation at 44,217 from top left)

        // set matrix so point of rotation becomes origin
        RsMatrix.loadTranslate(mat1, 0.f, -57.0f * scale, 0.f);
        RsMatrix.rotate(mat1, State.mAngle - 90.f, 0.f, 0.f, 1.f);
        RsMatrix.scale(mat1, scale, scale, scale);
        vpLoadModelMatrix(mat1);
        bindTexture(mPfBackground, 0, mTextures[4]);
        drawQuadTexCoords(
                -44.0f, -102.0f+57.f, 0.0f,         // space
                    .15625f, 0.755859375f,  // texture
                44.0f, -102.0f+57.f, 0.0f,             // space
                    0.84375f, 0.755859375f,  // texture
                44.0f, 160.0f+57.f, 0.0f,             // space
                    0.84375f, 0.244140625f,  // texture
                -44.0f, 160.0f+57.f, 0.0f,         // space
                    0.15625f, 0.244140625f); // texture


        // restore matrix
        RsMatrix.loadRotate(mat1, 0.f, 0.f, 0.f, 1.f);
        RsMatrix.scale(mat1, scale, scale, scale);
        vpLoadModelMatrix(mat1);

        // erase the part of the needle we don't want to show
        bindTexture(mPfBackground, 0, mTextures[5]);
        drawQuad(-100.f, -55.f, 0.f,
                 -100.f, -105.f, 0.f,
                  100.f, -105.f, 0.f,
                  100.f, -55.f, 0.f);


        // draw the frame (472x290)
        bindTexture(mPfBackground, 0, mTextures[1]);
        drawQuadTexCoords(
                -236.0f, -60.0f, 0.0f,           // space
                    0.0390625f, 0.783203125f,    // texture
                236, -60.0f, 0.0f,               // space
                    0.9609375f, 0.783203125f,    // texture
                236, 230.0f, 0.0f,               // space
                    0.9609375f, 0.216796875f,    // texture
                -236.0f, 230.0f, 0.0f,           // space
                    0.0390625f, 0.216796875f);   // texture

        return 1;
    }
}
