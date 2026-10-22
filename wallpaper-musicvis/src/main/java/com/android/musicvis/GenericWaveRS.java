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

package com.android.musicvis;

import static com.gravarty.wallpaper.gl.RsLib.*;

import android.os.Handler;
import android.os.SystemClock;

import com.gravarty.wallpaper.gl.GlScene;
import com.gravarty.wallpaper.gl.RsMatrix;

/**
 * Port: GenericWaveRS and res/raw/waveform.rs (AOSP packages/wallpapers/MusicVisualization,
 * android-2.3.7_r1) on GlScene instead of RenderScript. One line per pixel of the original
 * screen (see GlScene.virtualSize()), at most the 1024 lines of the mesh.
 */
public class GenericWaveRS extends GlScene {

    private final Handler mHandler = new Handler();
    private final Runnable mDrawCube = new Runnable() {
        public void run() {
            updateWave();
        }
    };
    private boolean mVisible;
    private int mTexId;

    protected static class WorldState {
        public float yRotation;
        public int idle;
        public int waveCounter;
        public int width;
    }
    protected WorldState mWorldState = new WorldState();

    private SimpleMesh mCubeMesh;

    // 1024 lines, with 4 points per line (2 space, 2 texture) each consisting of x and y,
    // so 8 floats per line.
    protected float [] mPointData = new float[1024*8];

    // 2 indices per line
    private short [] mIndexData = new short[1024*2];

    private ProgramVertex mPVBackground;
    private MatrixAllocation mPVAlloc;

    protected AudioCapture mAudioCapture = null;
    protected int [] mVizData = new int[1024];

    private ProgramFragment mPfBackground;
    private Sampler mSampler;
    private Texture mTexture;

    /** Port: the lines drawn, the screen width in pixels of the original screen */
    protected int mLines;

    protected GenericWaveRS(int width, int height, int texid) {
        super(width, height);
        mTexId = texid;
        mWidth = width;
        mHeight = height;
        mLines = lines(width, height);
        // the x, s and t coordinates don't change, so set those now
        int outlen = mPointData.length / 8;
        int half = outlen / 2;
        for(int i = 0; i < outlen; i++) {
            mPointData[i*8]   = i - half;          // start point X (Y set later)
            mPointData[i*8+2] = 0;                 // start point S
            mPointData[i*8+3] = 0;                 // start point T
            mPointData[i*8+4]   = i - half;        // end point X (Y set later)
            mPointData[i*8+6] = 1.0f;                 // end point S
            mPointData[i*8+7] = 0f;              // end point T
        }
    }

    /** Port: the screen width in pixels of the original screen, at most 1024 */
    private int lines(int width, int height) {
        return Math.min(virtualSize(width, height)[0], 1024);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mLines = lines(width, height);
        mWorldState.width = mLines;
        if (mPVAlloc != null) {
            mPVAlloc.setupProjectionNormalized(mWidth, mHeight);
        }
    }

    @Override
    protected void createScript() {
        // set our java object as the data for the renderscript allocation
        mWorldState.yRotation = 0.0f;
        mWorldState.width = mLines;

        /*
         *  Now put our model in to a form that renderscript can work with:
         *  - create a buffer of floats that are the coordinates for the points that define the cube
         *  - create a buffer of integers that are the indices of the points that form lines
         *  - combine the two in to a mesh
         */

        // First set up the coordinate system and such
        mPVBackground = new ProgramVertex();
        mPVAlloc = new MatrixAllocation();
        mPVBackground.bindAllocation(mPVAlloc);
        mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

        // Start creating the mesh
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

        /*
         * load the texture
         */
        mTexture = createFromBitmapResourceBoxed(mTexId, false, false);

        /*
         * create a program fragment to use the texture
         */
        mSampler = new Sampler(LINEAR, LINEAR, WRAP, WRAP);

        mPfBackground = new ProgramFragment(REPLACE, RGBA, false);
        mPfBackground.bindSampler(mSampler, 0);

        // #pragma stateVertex(PVBackground), stateFragment(PFBackground)
        setRootState(mPVBackground, mPfBackground, null);
        setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    /** Port: mPointAlloc.data(mPointData), a copy for the render thread */
    protected void pointData() {
        final float[] data = mPointData.clone();
        queue(new Runnable() {
            public void run() {
                mCubeMesh.data(data);
            }
        });
    }

    @Override
    public void setOffset(float xOffset, float yOffset,
            float xStep, float yStep, int xPixels, int yPixels) {
        // update our state, then push it to the renderscript

        if (xStep <= 0.0f) {
            xStep = xOffset / 2; // originator didn't set step size, assume we're halfway
        }
        // rotate 180 degrees per screen
        mWorldState.yRotation = xStep == 0.f ? 0.f : (xOffset / xStep) * 180;
    }

    @Override
    public void start() {
        super.start();
        mVisible = true;
        if (mAudioCapture != null) {
            mAudioCapture.start();
        }
        SystemClock.sleep(200);
        updateWave();
    }

    @Override
    public void stop() {
        super.stop();
        mVisible = false;
        if (mAudioCapture != null) {
            mAudioCapture.stop();
        }
        updateWave();
    }

    public void update() {
    }

    void updateWave() {
        mHandler.removeCallbacks(mDrawCube);
        if (!mVisible) {
            return;
        }
        mHandler.postDelayed(mDrawCube, 20);
        update();
        mWorldState.waveCounter++;
    }

    // ------------------------------------------------------------------ waveform.rs

    private static final int FADEOUT_LENGTH = 100;
    private static final float FADEOUT_FACTOR = 0.95f;
    private static final int FADEIN_LENGTH = 15;

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
    private final float[] idle = new float[8192];
    private int waveCounter = 0;

    private void makeIdleWave(float[] points) {
        int i;
        // show a number of superimposed moving sinewaves
        float amp1 = (float) Math.sin(0.007 * wave1amp) * 120;
        float amp2 = (float) Math.sin(0.023 * wave2amp) * 80;
        float amp3 = (float) Math.sin(0.011 * wave3amp) * 40;
        float amp4 = (float) Math.sin(0.031 * wave4amp) * 20;
        // calculate how many invisible lines there are on each side
        int skip = (1024 - mWorldState.width) / 2;
        int end = 1024 - skip;
        for (i = skip; i < end; i++) {
            float val = (float) Math.sin(0.013 * (wave1pos + i)) * amp1
                      + (float) Math.sin(0.029 * (wave2pos + i)) * amp2;
            float off = (float) Math.sin(0.005 * (wave3pos + i)) * amp3
                      + (float) Math.sin(0.017 * (wave4pos + i)) * amp4;
            if (val < 2.f && val > -2.f) val = 2.f;
            points[i*8+1] = val + off;
            points[i*8+5] = -val + off;
        }
        wave1pos++;
        wave1amp++;
        wave2pos--;
        wave2amp++;
        wave3pos++;
        wave3amp++;
        wave4pos++;
        wave4amp++;
    }

    private final float[] mPoints = new float[1024 * 8];
    private final float[] mat1 = new float[16];

    @Override
    protected int main() {
        final WorldState State = mWorldState;

        int i;

        // calculate how many invisible lines there are on each side
        int width = State.width;
        int skip = (1024 - width) / 2;
        int end = 1024 - skip;

        if (State.idle != 0) {

            // idle state animation
            final float[] points = loadArrayF();
            if (fadeoutcounter > 0) {
                // fade waveform to 0
                for (i = skip; i < end; i++) {
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
            storeArrayF(points);
            fadeincounter = FADEIN_LENGTH;
        } else {
            if (fadeincounter > 0 && fadeoutcounter == 0) {
                // morph from idle animation back to waveform
                makeIdleWave(idle);
                if (waveCounter != State.waveCounter) {
                    waveCounter = State.waveCounter;
                    final float[] points = loadArrayF();
                    for (i = skip; i < end; i++) {
                        float val = absf(points[i*8+1]);
                        points[i*8+1] = (val * (FADEIN_LENGTH - fadeincounter) + idle[i*8+1] * fadeincounter) / FADEIN_LENGTH;
                        points[i*8+5] = (-val * (FADEIN_LENGTH - fadeincounter) + idle[i*8+5] * fadeincounter) / FADEIN_LENGTH;
                    }
                    storeArrayF(points);
                }
                fadeincounter--;
                if (fadeincounter == 0) {
                    fadeoutcounter = FADEOUT_LENGTH;
                }
            } else {
                fadeoutcounter = FADEOUT_LENGTH;
            }
        }

        float yrot = State.yRotation;
        float scale = 0.004165f * (1.0f + 2.f * absf((float) Math.sin(radf(yrot))));

        // Change the model matrix to account for the large model
        // and to do the necessary rotations.
        RsMatrix.loadRotate(mat1, yrot, 0.f, 0.f, 1.f);
        RsMatrix.scale(mat1, scale, scale, scale);
        vpLoadModelMatrix(mat1);

        // Draw the visualizer.
        mCubeMesh.uploadToBufferObject();
        bindTexture(mPfBackground, 0, mTexture);
        lineWidth(); // Port
        drawSimpleMeshRange(mCubeMesh, skip * 2, width * 2);

        return 1;
    }

    /** loadArrayF(RSID_POINTS, 0): the points Allocation */
    private float[] loadArrayF() {
        mCubeMesh.floats().get(mPoints);
        return mPoints;
    }

    private void storeArrayF(float[] points) {
        mCubeMesh.data(points);
    }
}
