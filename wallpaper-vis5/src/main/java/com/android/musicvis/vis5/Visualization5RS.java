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

package com.android.musicvis.vis5;

import static com.gravarty.wallpaper.gl.RsLib.*;

import com.android.musicvis.R;
import com.android.musicvis.AudioCapture;

import android.os.Handler;
import android.view.MotionEvent;

import com.gravarty.wallpaper.gl.GlScene;
import com.gravarty.wallpaper.gl.RsMatrix;

/**
 * Port: Visualization5RS and res/raw/many.rs (AOSP packages/wallpapers/MusicVisualization,
 * android-2.3.7_r1) on GlScene instead of RenderScript.
 */
class Visualization5RS extends GlScene {

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
        public float mRotate;
        public float mTilt;
        public int   mIdle;
        public int   mWaveCounter;
    }
    WorldState mWorldState = new WorldState();

    private ProgramStore mPfsBackground;
    private ProgramFragment mPfBackgroundMip;
    private ProgramFragment mPfBackgroundNoMip;
    private Sampler mSamplerMip;
    private Sampler mSamplerNoMip;
    private Texture[] mTextures;

    private ProgramVertex mPVBackground;
    private MatrixAllocation mPVAlloc;

    private SimpleMesh mCubeMesh;

    protected float [] mPointData = new float[256*8];
    private short [] mIndexData = new short[256*2];

    private AudioCapture mAudioCapture = null;
    private int [] mVizData = new int[1024];

    private float mTouchY;

    Visualization5RS(int width, int height) {
        super(width, height);
        mWidth = width;
        mHeight = height;
        // the x, s and t coordinates don't change, so set those now
        int outlen = mPointData.length / 8;
        int half = outlen / 2;
        for(int i = 0; i < outlen; i++) {
            mPointData[i*8]   = i - half;          // start point X (Y set later)
            mPointData[i*8+2] = 0;                 // start point S
            mPointData[i*8+3] = 0;                 // start point T
            mPointData[i*8+4] = i - half;          // end point X (Y set later)
            mPointData[i*8+6] = 1.0f;              // end point S
            mPointData[i*8+7] = 0f;                // end point T
        }
        virtualSize(width, height); // Port: for the line width
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mPVAlloc != null) {
            mPVAlloc.setupProjectionNormalized(width, height);
        }
        mWorldState.mTilt = -20;
        virtualSize(width, height); // Port: for the line width
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
                dy /= 10;
                dy += mWorldState.mTilt;
                if (dy > 0) {
                    dy = 0;
                } else if (dy < -45) {
                    dy = -45;
                }
                mWorldState.mTilt = dy;
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset,
            float xStep, float yStep, int xPixels, int yPixels) {
        // update our state, then push it to the renderscript
        mWorldState.mRotate = (xOffset - 0.5f) * 90;
    }

    @Override
    protected void createScript() {
        // First set up the coordinate system and such
        mPVBackground = new ProgramVertex();
        mPVAlloc = new MatrixAllocation();
        mPVBackground.bindAllocation(mPVAlloc);
        mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

        mTextures = new Texture[8];
        mTextures[0] = createFromBitmapResourceBoxed(R.drawable.background, true, true);
        mTextures[1] = createFromBitmapResourceBoxed(R.drawable.frame, true, true);
        mTextures[2] = createFromBitmapResourceBoxed(R.drawable.peak_on, true, true);
        mTextures[3] = createFromBitmapResourceBoxed(R.drawable.peak_off, true, true);
        mTextures[4] = createFromBitmapResourceBoxed(R.drawable.needle, true, true);
        mTextures[5] = createFromBitmapResourceBoxed(R.drawable.black, false, false);
        mTextures[6] = createFromBitmapResource(R.drawable.albumart, true, true);
        mTextures[7] = createFromBitmapResource(R.drawable.fire, false, false);

        mSamplerNoMip = new Sampler(LINEAR, LINEAR, WRAP, WRAP);
        mSamplerMip = new Sampler(LINEAR_MIP_LINEAR, LINEAR, WRAP, WRAP);

        {
            mPfBackgroundNoMip = new ProgramFragment(REPLACE, RGBA, false);
            mPfBackgroundNoMip.bindSampler(mSamplerNoMip, 0);
        }

        {
            mPfBackgroundMip = new ProgramFragment(REPLACE, RGBA, false);
            mPfBackgroundMip.bindSampler(mSamplerMip, 0);
        }

        {
            // DepthFunc.EQUAL, no depth mask: no depth buffer, so no effect
            // without dithering there is severe banding
            mPfsBackground = new ProgramStore(ONE, ONE_MINUS_SRC_ALPHA, true);
        }

        mCubeMesh = new SimpleMesh(new Field[] {
            new Field("position", KIND_POSITION, 2),
            new Field("texture", KIND_TEXTURE, 2),
        }, mPointData.length / 4, mIndexData.length, LINE);

        /*
         *  put the vertex and index data in their respective buffers
         */
        updateWave();
        for(int i = 0; i < mIndexData.length; i ++) {
            mIndexData[i] = (short) i;
        }

        /*
         *  upload the vertex and index data
         */
        mCubeMesh.data(mPointData);
        mCubeMesh.uploadToBufferObject();
        mCubeMesh.indices(mIndexData);

        // #pragma stateVertex(PVBackground), stateStore(PFSBackground)
        setRootState(mPVBackground, null, mPfsBackground);
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

        // downsample 1024 samples in to 256

        if (len == 0) {
            if (mWorldState.mIdle == 0) {
                mWorldState.mIdle = 1;
            }
        } else {
            if (mWorldState.mIdle != 0) {
                mWorldState.mIdle = 0;
            }
            // TODO: might be more efficient to push this in to renderscript
            int outlen = mPointData.length / 8;
            len /= 4;
            if (len > outlen) len = outlen;
            for(int i = 0; i < len; i++) {
                int amp = (mVizData[i*4]  + mVizData[i*4+1] + mVizData[i*4+2] + mVizData[i*4+3]);
                mPointData[i*8+1] = amp;
                mPointData[i*8+5] = -amp;
            }
            // Port: mPointAlloc.data(mPointData), a copy for the render thread
            final float[] data = mPointData.clone();
            queue(new Runnable() {
                public void run() {
                    mCubeMesh.data(data);
                }
            });
            mWorldState.mWaveCounter++;
        }
    }

    // ------------------------------------------------------------------ many.rs

    private void drawVU(float[] ident) {
        final WorldState State = mWorldState;

        float[] mat1 = new float[16];
        float scale = 0.0041f;

        RsMatrix.load(mat1, ident);
        RsMatrix.rotate(mat1, 0.f, 0.f, 0.f, 1.f);
        RsMatrix.scale(mat1, scale, scale, scale);
        vpLoadModelMatrix(mat1);

        bindProgramFragment(mPfBackgroundMip);

        // draw the background image (416x233)
        bindTexture(mPfBackgroundMip, 0, mTextures[0]);
        drawQuadTexCoords(
                -208.0f, -33.0f, 600.0f,        // space
                    0.09375f, 0.9551f,        // texture
                208, -33.0f, 600.0f,            // space
                    0.90625f, 0.9551f,         // texture
                208, 200.0f, 600.0f,            // space
                    0.90625f, 0.0449f,         // texture
                -208.0f, 200.0f, 600.0f,        // space
                    0.09375f, 0.0449f);       // texture

        // draw the peak indicator light (56x58)
        if (State.mPeak > 0) {
            bindTexture(mPfBackgroundMip, 0, mTextures[2]);
        } else {
            bindTexture(mPfBackgroundMip, 0, mTextures[3]);
        }
        drawQuadTexCoords(
                140.0f, 70.0f, 600.0f,         // space
                    0.0625f, 0.953125f,        // texture
                196, 70.0f, 600.0f,            // space
                    0.9375f, 0.953125f,        // texture
                196, 128.0f, 600.0f,           // space
                    0.9375f, 0.046875f,        // texture
                140.0f, 128.0f, 600.0f,        // space
                    0.0625f, 0.046875f);       // texture



        // Draw the needle (88x262, center of rotation at 44,217 from top left)

        // set matrix so point of rotation becomes origin
        RsMatrix.load(mat1, ident);
        RsMatrix.translate(mat1, 0.f, -57.0f * scale, 0.f);
        RsMatrix.rotate(mat1, State.mAngle - 90.f, 0.f, 0.f, 1.f);
        RsMatrix.scale(mat1, scale, scale, scale);
        vpLoadModelMatrix(mat1);
        bindTexture(mPfBackgroundMip, 0, mTextures[4]);
        drawQuadTexCoords(
                -44.0f, -102.0f+57.f, 600.0f,         // space
                    .15625f, 0.755859375f,  // texture
                44.0f, -102.0f+57.f, 600.0f,             // space
                    0.84375f, 0.755859375f,  // texture
                44.0f, 160.0f+57.f, 600.0f,             // space
                    0.84375f, 0.244140625f,  // texture
                -44.0f, 160.0f+57.f, 600.0f,         // space
                    0.15625f, 0.244140625f); // texture


        // restore matrix
        RsMatrix.load(mat1, ident);
        RsMatrix.rotate(mat1, 0.f, 0.f, 0.f, 1.f);
        RsMatrix.scale(mat1, scale, scale, scale);
        vpLoadModelMatrix(mat1);

        // erase the part of the needle we don't want to show
        bindTexture(mPfBackgroundMip, 0, mTextures[5]);
        drawQuad(-100.f, -55.f, 600.f,
                 -100.f, -105.f, 600.f,
                  100.f, -105.f, 600.f,
                  100.f, -55.f, 600.f);


        // draw the frame (472x290)
        bindTexture(mPfBackgroundMip, 0, mTextures[1]);
        drawQuadTexCoords(
                -236.0f, -60.0f, 600.0f,           // space
                    0.0390625f, 0.783203125f,    // texture
                236, -60.0f, 600.0f,               // space
                    0.9609375f, 0.783203125f,    // texture
                236, 230.0f, 600.0f,               // space
                    0.9609375f, 0.216796875f,    // texture
                -236.0f, 230.0f, 600.0f,           // space
                    0.0390625f, 0.216796875f);   // texture
    }

    private int fadeoutcounter = 0;
    private int fadeincounter = 0;
    private int wave1pos = 0;
    private int wave1amp = 0;
    private int wave2pos = 0;
    private int wave2amp= 0;
    private int wave3pos = 0;
    private int wave3amp= 0;
    private int wave4pos = 0;
    private int wave4amp= 0;
    private final float[] idle = new float[4096];
    private int waveCounter = 0;
    private int lastuptime = 0;
    private float autorotation = 0;

    private static final int FADEOUT_LENGTH = 100;
    private static final float FADEOUT_FACTOR = 0.95f;
    private static final int FADEIN_LENGTH = 15;

    private void makeIdleWave(float[] points) {
        int i;
        // show a number of superimposed moving sinewaves
        float amp1 = (float) Math.sin(0.007 * wave1amp) * 120 * 1024;
        float amp2 = (float) Math.sin(0.023 * wave2amp) * 80 * 1024;
        float amp3 = (float) Math.sin(0.011 * wave3amp) * 40 * 1024;
        float amp4 = (float) Math.sin(0.031 * wave4amp) * 20 * 1024;
        for (i = 0; i < 256; i++) {
            float val = (float) Math.sin(0.013 * (wave1pos + i * 4)) * amp1
                      + (float) Math.sin(0.029 * (wave2pos + i * 4)) * amp2;
            float off = (float) Math.sin(0.005 * (wave3pos + i * 4)) * amp3
                      + (float) Math.sin(0.017 * (wave4pos + i * 4)) * amp4;
            if (val < 2.f && val > -2.f) val = 2.f;
            points[i*8+1] = val + off;
            points[i*8+5] = -val + off;
        }
    }

    private final float[] mPoints = new float[256 * 8];

    private void drawWave(float[] ident) {
        final WorldState State = mWorldState;
        float scale = .008f;
        float[] mat1 = new float[16];
        RsMatrix.load(mat1, ident);
        RsMatrix.scale(mat1, scale, scale / 2048.f, scale);
        RsMatrix.translate(mat1, 0.f, 81920.f, 350.f);
        vpLoadModelMatrix(mat1);
        int i;

        if (State.mIdle != 0) {

            // idle state animation
            final float[] points = loadArrayF();
            if (fadeoutcounter > 0) {
                // fade waveform to 0
                for (i = 0; i < 256; i++) {
                    float val = absf(points[i*8+1]);
                    val = val * FADEOUT_FACTOR;
                    if (val < 2.f) val = 2.f;
                    points[i*8+1] = val;
                    points[i*8+5] = -val;
                }
                fadeoutcounter--;
                if (fadeoutcounter == 0) {
                    wave1amp = 0;
                    wave2amp = 0;
                    wave3amp = 0;
                    wave4amp = 0;
                }
            } else {
                // idle animation
                makeIdleWave(points);
            }
            mCubeMesh.data(points);
            fadeincounter = FADEIN_LENGTH;
        } else {
            if (fadeincounter > 0 && fadeoutcounter == 0) {
                // morph from idle animation back to waveform
                makeIdleWave(idle);
                if (waveCounter != State.mWaveCounter) {
                    waveCounter = State.mWaveCounter;
                    final float[] points = loadArrayF();
                    for (i = 0; i < 256; i++) {
                        float val = absf(points[i*8+1]);
                        points[i*8+1] = (val * (FADEIN_LENGTH - fadeincounter) + idle[i*8+1] * fadeincounter) / FADEIN_LENGTH;
                        points[i*8+5] = (-val * (FADEIN_LENGTH - fadeincounter) + idle[i*8+5] * fadeincounter) / FADEIN_LENGTH;
                    }
                    mCubeMesh.data(points);
                }
                fadeincounter--;
                if (fadeincounter == 0) {
                    fadeoutcounter = FADEOUT_LENGTH;
                }
            } else {
                fadeoutcounter = FADEOUT_LENGTH;
            }
        }

        mCubeMesh.uploadToBufferObject();
        bindProgramFragment(mPfBackgroundNoMip);
        bindTexture(mPfBackgroundNoMip, 0, mTextures[7]);
        drawSimpleMesh(mCubeMesh);
    }

    /** loadArrayF(RSID_POINTS, 0): the points Allocation */
    private float[] loadArrayF() {
        mCubeMesh.floats().get(mPoints);
        return mPoints;
    }

    private void drawVizLayer(float[] ident) {
        int i;

        for (i = 0; i < 6; i++) {
            if ((i & 1) != 0) {
                drawVU(ident);
            } else {
                drawWave(ident);
            }

            RsMatrix.rotate(ident, 60.f, 0.f, 1.f, 0.f);
        }
    }

    @Override
    protected int main() {
        final WorldState State = mWorldState;

        float[] ident = new float[16];
        int now = uptimeMillis();
        int delta = now - lastuptime;
        lastuptime = now;
        if (delta > 80) {
            // Limit the delta to avoid jumps when coming back from sleep.
            // A value of 80 will make the rotation keep the same speed
            // until the frame rate drops to 12.5 fps, at which point it
            // will start slowing down.
            delta = 80;
        }
        autorotation += .3 * delta / 35;
        while (autorotation > 360.f) autorotation -= 360.f;

        lineWidth(); // Port

        RsMatrix.loadIdentity(ident);
        RsMatrix.rotate(ident, State.mTilt, 1.f, 0.f, 0.f);
        RsMatrix.rotate(ident, autorotation + State.mRotate, 0.f, 1.f, 0.f);

        // draw the reflections
        RsMatrix.translate(ident, 0.f, -1.f, 0.f);
        RsMatrix.scale(ident, 1.f, -1.f, 1.f);
        drawVizLayer(ident);

        // draw the reflecting plane
        bindProgramFragment(mPfBackgroundMip);
        bindTexture(mPfBackgroundMip, 0, mTextures[6]);
        drawQuadTexCoords(
                -1500.0f, -60.0f, 1500.0f,           // space
                    0.f, 1.f,    // texture
                1500, -60.0f, 1500.0f,               // space
                    1.f, 1.f,    // texture
                1500, -60.0f, -1500.0f,               // space
                    1.f, 0.f,    // texture
                -1500.0f, -60.0f, -1500.0f,           // space
                    0.f, 0.f);   // texture

        // draw the visualizer
        RsMatrix.scale(ident, 1.f, -1.f, 1.f);
        RsMatrix.translate(ident, 0.f, 1.f, 0.f);

        drawVizLayer(ident);

        wave1pos++;
        wave1amp++;
        wave2pos--;
        wave2amp++;
        wave3pos++;
        wave3amp++;
        wave4pos++;
        wave4amp++;

        return 1;
    }
}
