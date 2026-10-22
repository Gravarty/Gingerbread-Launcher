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

package com.android.wallpaper.grass;

import static com.gravarty.wallpaper.gl.RsLib.*;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.opengl.GLES20;
import android.os.Bundle;
import android.text.format.Time;

import com.android.wallpaper.R;
import com.gravarty.wallpaper.gl.GlScene;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Calendar;
import java.util.Random;

/**
 * Port: GrassRS and res/raw/grass.rs (AOSP packages/wallpapers/Basic, android-2.3.7_r1)
 * on GlScene instead of RenderScript. The script works in pixels of the original screen
 * (see GlScene.virtualSize()).
 */
class GrassRS extends GlScene {
    @SuppressWarnings({"UnusedDeclaration"})
    private static final String LOG_TAG = "Grass";
    private static final boolean DEBUG = false;

    private static final int LOCATION_UPDATE_MIN_TIME = DEBUG ? 5 * 60 * 1000 : 60 * 60 * 1000; // 1 hour
    private static final int LOCATION_UPDATE_MIN_DISTANCE = DEBUG ? 10 : 150 * 1000; // 150 km

    private static final float TESSELATION = 0.5f;

    private static final int BLADES_COUNT = 200;

    class BladesStruct {
        public float angle;
        public int size;
        public float xPos;
        public float yPos;
        public float offset;
        public float scale;
        public float lengthX;
        public float lengthY;
        public float hardness;
        public float h;
        public float s;
        public float b;
        public float turbulencex;
    };

    private ProgramFragment mPfBackground;
    private ProgramFragment mPfGrass;
    private ProgramStore mPfsBackground;
    private ProgramVertex mPvBackground;
    private MatrixAllocation mPvOrthoAlloc;

    private Texture mTNight;
    private Texture mTSunrise;
    private Texture mTSky;
    private Texture mTSunset;
    private Texture mTAa;

    private BladesStruct[] mBlades;

    private SimpleMesh mBladesMesh;

    private int mVerticies;
    private int mIndicies;
    private int[] mBladeSizes;

    private final float[] mFloatData5 = new float[5];

    private WorldState mWorldState;

    private final Context mContext;
    private final LocationManager mLocationManager;

    private LocationUpdater mLocationUpdater;
    private GrassRS.TimezoneTracker mTimezoneTracker;

    GrassRS(Context context, int width, int height) {
        super(width, height);

        mContext = context;
        mLocationManager = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void init(Context context, boolean isPreview) {
        super.init(context, isPreview);
        // Port: the Java side state is made here, before start(); GL in createScript()
        final int[] size = virtualSize(mWidth, mHeight);
        mVirtualWidth = size[0];
        mVirtualHeight = size[1];
        createScriptStructures();
    }

    // Port: the screen in pixels of the original one
    private int mVirtualWidth;
    private int mVirtualHeight;

    @Override
    public void start() {
        super.start();

        if (mTimezoneTracker == null) {
            mTimezoneTracker = new TimezoneTracker();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

            mContext.registerReceiver(mTimezoneTracker, filter);
        }

        if (mLocationUpdater == null && hasLocationPermission()) {
            mLocationUpdater = new LocationUpdater();
            try {
                mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        LOCATION_UPDATE_MIN_TIME, LOCATION_UPDATE_MIN_DISTANCE,
                        mLocationUpdater);
            } catch (IllegalArgumentException e) {
                // Port: no network location provider on this device
                mLocationUpdater = null;
            }
        }

        updateLocation();
    }

    @Override
    public void stop() {
        super.stop();

        if (mTimezoneTracker != null) {
            mContext.unregisterReceiver(mTimezoneTracker);
            mTimezoneTracker = null;
        }

        if (mLocationUpdater != null) {
            mLocationManager.removeUpdates(mLocationUpdater);
            mLocationUpdater = null;
        }
    }

    /**
     * Port: location is a runtime permission now, which a wallpaper can't ask for. Without
     * it (granted in the app's settings) the original's fallback times are used.
     */
    private boolean hasLocationPermission() {
        return mContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        final int[] size = virtualSize(width, height); // Port
        mWorldState.width = size[0];
        mWorldState.height = size[1];
        updateBlades();

        mPvOrthoAlloc.setupOrthoWindow(size[0], size[1]);
    }

    @Override
    protected void createScript() {
        createProgramVertex();
        createProgramFragmentStore();
        loadTextures();
        createProgramFragment();
        createMesh();

        // #pragma stateVertex(PVBackground), stateFragment(PFBackground),
        // stateStore(PFSBackground)
        setRootState(mPvBackground, mPfBackground, mPfsBackground);
        setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    private void createScriptStructures() {
        createBlades();
        createState();
    }

    @Override
    public void setOffset(float xOffset, float yOffset, float xStep, float yStep,
            int xPixels, int yPixels) {
        mWorldState.xOffset = xOffset;
    }

    static class WorldState {
        public int bladesCount;
        public int indexCount;
        public int width;
        public int height;
        public float xOffset;
        public float dawn;
        public float morning;
        public float afternoon;
        public float dusk;
        public int isPreview;
    }

    private void createState() {
        final boolean isPreview = isPreview();

        mWorldState = new WorldState();
        mWorldState.width = mVirtualWidth;
        mWorldState.height = mVirtualHeight;
        mWorldState.bladesCount = BLADES_COUNT;
        mWorldState.indexCount = mIndicies;
        mWorldState.isPreview = isPreview ? 1 : 0;
        if (isPreview) {
            mWorldState.xOffset = 0.5f;
        }
    }

    private void createBlades() {
        mVerticies = 0;
        mIndicies = 0;

        mBlades = new BladesStruct[BLADES_COUNT];
        mBladeSizes = new int[BLADES_COUNT];
        for (int i = 0; i < BLADES_COUNT; i++) {
            BladesStruct bs = new BladesStruct();
            createBlade(bs);
            mIndicies += bs.size * 2 * 3;
            mVerticies += bs.size + 2;
            mBlades[i] = bs;
            mBladeSizes[i] = bs.size;
        }
    }

    private void createMesh() {
        mBladesMesh = new SimpleMesh(new Field[] {
            new Field("color", KIND_COLOR, 4, GLES20.GL_UNSIGNED_BYTE),
            new Field("position", KIND_POSITION, 2),
            new Field("texture", KIND_TEXTURE, 2),
        }, mVerticies * 2, mIndicies, TRIANGLE);

        final float[] floatData = mFloatData5;
        final FloatBuffer buffer = mBladesMesh.floats();

        short[] idx = new short[mIndicies];
        int bufferIndex = 0;
        int i2 = 0;
        for (int i = 0; i < mVerticies; i +=2) {
            floatData[3] = 0.0f;
            floatData[4] = 0.0f;
            buffer.position(bufferIndex++ * 5);
            buffer.put(floatData);

            floatData[3] = 1.0f;
            floatData[4] = 0.0f;
            buffer.position(bufferIndex++ * 5);
            buffer.put(floatData);
        }

        int idxIdx = 0;
        int vtxIdx = 0;
        for (int i = 0; i < mBladeSizes.length; i++) {
            for (int ct = 0; ct < mBladeSizes[i]; ct ++) {
                idx[idxIdx + 0] = (short)(vtxIdx + 0);
                idx[idxIdx + 1] = (short)(vtxIdx + 1);
                idx[idxIdx + 2] = (short)(vtxIdx + 2);
                idx[idxIdx + 3] = (short)(vtxIdx + 1);
                idx[idxIdx + 4] = (short)(vtxIdx + 3);
                idx[idxIdx + 5] = (short)(vtxIdx + 2);
                idxIdx += 6;
                vtxIdx += 2;
            }
            vtxIdx += 2;
        }

        mBladesMesh.indices(idx);
        mBladesMesh.uploadToBufferObject();
    }

    private void createBlade(BladesStruct blades) {
        final float size = random(4.0f) + 4.0f;
        final int xpos = random(-mVirtualWidth, mVirtualWidth);

        //noinspection PointlessArithmeticExpression
        blades.angle = 0.0f;
        blades.size = (int)(size / TESSELATION);
        blades.xPos = xpos;
        blades.yPos = mVirtualHeight;
        blades.offset = random(0.2f) - 0.1f;
        blades.scale = 4.0f / (size / TESSELATION) + (random(0.6f) + 0.2f) * TESSELATION;
        blades.lengthX = (random(4.5f) + 3.0f) * TESSELATION * size;
        blades.lengthY = (random(5.5f) + 2.0f) * TESSELATION * size;
        blades.hardness = (random(1.0f) + 0.2f) * TESSELATION;
        blades.h = random(0.02f) + 0.2f;
        blades.s = random(0.22f) + 0.78f;
        blades.b = random(0.65f) + 0.35f;
        blades.turbulencex = xpos * 0.006f;
    }

    // Port: android.util.MathUtils.random() (hidden API now)
    private static final Random sRandom = new Random();

    private static float random(float howbig) {
        return sRandom.nextFloat() * howbig;
    }

    private static int random(int howsmall, int howbig) {
        if (howsmall >= howbig) return howsmall;
        return (int) (sRandom.nextFloat() * (howbig - howsmall) + howsmall);
    }

    private void loadTextures() {
        mTNight = loadTexture(R.drawable.night);
        mTSunrise = loadTexture(R.drawable.sunrise);
        mTSky = loadTexture(R.drawable.sky);
        mTSunset = loadTexture(R.drawable.sunset);
        mTAa = generateTextureAlpha(4, 1, new int[] { 0x00FFFF00 });
    }

    private Texture generateTextureAlpha(int width, int height, int[] data) {
        int[] grey1 = new int[] {0x3f3f3f3f};
        int[] grey2 = new int[] {0x00000000};
        // LOD 0: 4x1 of data, LOD 1: 2x1 of grey1, LOD 2: 1x1 of grey2 (bytes in memory order)
        return createAlpha(width, height, bytes(data, 4), bytes(grey1, 2), bytes(grey2, 1));
    }

    private static byte[] bytes(int[] data, int count) {
        final ByteBuffer b = ByteBuffer.allocate(data.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.asIntBuffer().put(data);
        final byte[] out = new byte[count];
        b.get(out);
        return out;
    }

    private Texture loadTexture(int id) {
        return createFromBitmapResource(id, false, false);
    }

    private void createProgramFragment() {
        Sampler sl = new Sampler(LINEAR_MIP_LINEAR, LINEAR, WRAP, WRAP);
        Sampler sn = new Sampler(NEAREST, NEAREST, WRAP, WRAP);

        mPfGrass = new ProgramFragment(REPLACE, ALPHA, false);
        mPfGrass.bindSampler(sl, 0);

        mPfBackground = new ProgramFragment(REPLACE, RGB, false);
        mPfBackground.bindSampler(sn, 0);
    }

    private void createProgramFragmentStore() {
        mPfsBackground = new ProgramStore(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, false);
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new MatrixAllocation();
        mPvOrthoAlloc.setupOrthoWindow(mWorldState.width, mWorldState.height);

        mPvBackground = new ProgramVertex();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
    }

    private void updateLocation() {
        Location location = null;
        if (hasLocationPermission()) { // Port
            location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        updateLocation(location);
    }

    private void updateLocation(Location location) {
        float dawn = 0.3f;
        float dusk = 0.75f;

        if (location != null) {
            final String timeZone = Time.getCurrentTimezone();
            final SunCalculator calculator = new SunCalculator(location, timeZone);
            final Calendar now = Calendar.getInstance();

            final double sunrise = calculator.computeSunriseTime(SunCalculator.ZENITH_CIVIL, now);
            dawn = SunCalculator.timeToDayFraction(sunrise);

            final double sunset = calculator.computeSunsetTime(SunCalculator.ZENITH_CIVIL, now);
            dusk = SunCalculator.timeToDayFraction(sunset);
        }

        mWorldState.dawn = dawn;
        mWorldState.morning = dawn + 1.0f / 12.0f; // 2 hours for sunrise
        mWorldState.afternoon = dusk - 1.0f / 12.0f; // 2 hours for sunset
        mWorldState.dusk = dusk;
    }

    private class LocationUpdater implements LocationListener {
        public void onLocationChanged(Location location) {
            updateLocation(location);
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onProviderDisabled(String provider) {
        }
    }

    private class TimezoneTracker extends BroadcastReceiver {

        public void onReceive(Context context, Intent intent) {
            updateLocation();
        }
    }

    // ------------------------------------------------------------------ grass.rs

    private static final float HALF_TESSELATION = 0.25f;

    private static final float MAX_BEND = 0.09f;

    private static final float SECONDS_IN_DAY = 86400.0f;

    private static final float HALF_PI = 1.570796326f;

    private static final boolean REAL_TIME = true;

    private void updateBlades() {
        final WorldState State = mWorldState;
        int bladesCount = State.bladesCount;

        int i;
        for (i = 0; i < bladesCount; i ++) {
            final BladesStruct bladeStruct = mBlades[i];
            float xpos = randf2(-State.width, State.width);
            bladeStruct.xPos = xpos;
            bladeStruct.turbulencex = xpos * 0.006f;
            bladeStruct.yPos = State.height;
        }
    }

    private float time(int isPreview) {
        if (REAL_TIME && isPreview == 0) {
            return (hour() * 3600.0f + minute() * 60.0f + second()) / SECONDS_IN_DAY;
        }
        float t = uptimeMillis() / 30000.0f;
        return t - (int) t;
    }

    private void alpha(float a) {
        color(1.0f, 1.0f, 1.0f, a);
    }

    private void drawNight(int width, int height) {
        bindTexture(mPfBackground, 0, mTNight);
        drawQuadTexCoords(
                0.0f, -32.0f, 0.0f,
                0.0f, 1.0f,
                width, -32.0f, 0.0f,
                2.0f, 1.0f,
                width, 1024.0f - 32.0f, 0.0f,
                2.0f, 0.0f,
                0.0f, 1024.0f - 32.0f, 0.0f,
                0.0f, 0.0f);
    }

    private void drawSunrise(int width, int height) {
        bindTexture(mPfBackground, 0, mTSunrise);
        drawRect(0.0f, 0.0f, width, height, 0.0f);
    }

    private void drawNoon(int width, int height) {
        bindTexture(mPfBackground, 0, mTSky);
        drawRect(0.0f, 0.0f, width, height, 0.0f);
    }

    private void drawSunset(int width, int height) {
        bindTexture(mPfBackground, 0, mTSunset);
        drawRect(0.0f, 0.0f, width, height, 0.0f);
    }

    /** bladeBuffer and bladeColor: the same vertex memory, as floats and as ints */
    private int drawBlade(BladesStruct bladeStruct, FloatBuffer bladeBuffer,
            IntBuffer bladeColor, int index, float brightness, float xOffset, float now) {

        float scale = bladeStruct.scale;
        float angle = bladeStruct.angle;
        float xpos = bladeStruct.xPos + xOffset;
        int size = bladeStruct.size;

        int color = hsbToAbgr(bladeStruct.h, bladeStruct.s,
                              lerpf(0, bladeStruct.b, brightness), 1.0f);

        float newAngle = (turbulencef2(bladeStruct.turbulencex, now, 4.0f) - 0.5f) * 0.5f;
        angle = clampf(angle + (newAngle + bladeStruct.offset - angle) * 0.15f, -MAX_BEND, MAX_BEND);

        float currentAngle = HALF_PI;

        float bottomX = xpos;
        float bottomY = bladeStruct.yPos;

        float d = angle * bladeStruct.hardness;


        float si = size * scale;
        float bottomLeft = bottomX - si;
        float bottomRight = bottomX + si;
        float bottom = bottomY + HALF_TESSELATION;

        bladeColor.put(index + 0, color);               // V1.ABGR
        bladeBuffer.put(index + 1, bottomLeft);         // V1.X
        bladeBuffer.put(index + 2, bottom);             // V1.Y
        bladeBuffer.put(index + 3, 0.f);                // V1.s
        bladeBuffer.put(index + 4, 0.f);                // V1.t
                                                        //
        bladeColor.put(index + 5, color);               // V2.ABGR
        bladeBuffer.put(index + 6, bottomRight);        // V2.X
        bladeBuffer.put(index + 7, bottom);             // V2.Y
        bladeBuffer.put(index + 8, 1.f);                // V2.s
        bladeBuffer.put(index + 9, 0.f);                // V2.t
        index += 10;

        for ( ; size > 0; size -= 1) {
            float topX = bottomX - cosf_fast(currentAngle) * bladeStruct.lengthX;
            float topY = bottomY - sinf_fast(currentAngle) * bladeStruct.lengthY;

            si = (float)size * scale;
            float spi = si - scale;

            float topLeft = topX - spi;
            float topRight = topX + spi;

            bladeColor.put(index + 0, color);               // V1.ABGR
            bladeBuffer.put(index + 1, topLeft);            // V1.X
            bladeBuffer.put(index + 2, topY);               // V1.Y
            bladeBuffer.put(index + 3, 0.f);                // V1.s
            bladeBuffer.put(index + 4, 0.f);                // V1.t

            bladeColor.put(index + 5, color);               // V2.ABGR
            bladeBuffer.put(index + 6, topRight);           // V2.X
            bladeBuffer.put(index + 7, topY);               // V2.Y
            bladeBuffer.put(index + 8, 1.f);                // V2.s
            bladeBuffer.put(index + 9, 0.f);                // V2.t

            index += 10;

            bottomX = topX;
            bottomY = topY;

            currentAngle += d;
        }

        bladeStruct.angle = angle;

        // 2 vertices per triangle, 5 properties per vertex (RGBA, X, Y, S, T)
        return bladeStruct.size * 10 + 10;
    }

    private void drawBlades(float brightness, float xOffset) {
        // For anti-aliasing
        bindTexture(mPfGrass, 0, mTAa);

        int bladesCount = mWorldState.bladesCount;

        int i = 0;
        final FloatBuffer bladeBuffer = mBladesMesh.floats();
        final IntBuffer bladeColor = mBladesMesh.ints();
        int index = 0;

        float now = uptimeMillis() * 0.00004f;

        for ( ; i < bladesCount; i += 1) {
            int offset = drawBlade(mBlades[i], bladeBuffer, bladeColor, index, brightness,
                    xOffset, now);
            index += offset;
        }

        mBladesMesh.uploadToBufferObject();
        drawSimpleMeshRange(mBladesMesh, 0, mWorldState.indexCount);
    }

    @Override
    protected int main() {
        final WorldState State = mWorldState;
        int width = State.width;
        int height = State.height;

        float x = lerpf(width, 0, State.xOffset);

        float now = time(State.isPreview);
        alpha(1.0f);

        float newB = 1.0f;
        float dawn = State.dawn;
        float morning = State.morning;
        float afternoon = State.afternoon;
        float dusk = State.dusk;

        if (now >= 0.0f && now < dawn) {                    // Draw night
            drawNight(width, height);
            newB = 0.0f;
        } else if (now >= dawn && now <= morning) {         // Draw sunrise
            float half = dawn + (morning - dawn) * 0.5f;
            if (now <= half) {                              // Draw night->sunrise
                drawNight(width, height);
                newB = normf(dawn, half, now);
                alpha(newB);
                drawSunrise(width, height);
            } else {                                        // Draw sunrise->day
                drawSunrise(width, height);
                alpha(normf(half, morning, now));
                drawNoon(width, height);
            }
        } else if (now > morning && now < afternoon) {      // Draw day
            drawNoon(width, height);
        } else if (now >= afternoon && now <= dusk) {       // Draw sunset
            float half = afternoon + (dusk - afternoon) * 0.5f;
            if (now <= half) {                              // Draw day->sunset
                drawNoon(width, height);
                newB = normf(afternoon, half, now);
                alpha(newB);
                newB = 1.0f - newB;
                drawSunset(width, height);
            } else {                                        // Draw sunset->night
                drawSunset(width, height);
                alpha(normf(half, dusk, now));
                drawNight(width, height);
                newB = 0.0f;
            }
        } else if (now > dusk) {                            // Draw night
            drawNight(width, height);
            newB = 0.0f;
        }

        bindProgramFragment(mPfGrass);
        drawBlades(newB, x);

        return 50;
    }
}
