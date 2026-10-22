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

package com.android.wallpaper.galaxy;

import static com.gravarty.wallpaper.gl.RsLib.*;

import android.opengl.GLES20;

import com.android.wallpaper.R;
import com.gravarty.wallpaper.gl.GlScene;
import com.gravarty.wallpaper.gl.RsMatrix;

import java.nio.ByteBuffer;

/**
 * Port: GalaxyRS and res/raw/galaxy.rs (AOSP packages/wallpapers/Basic, android-2.3.7_r1)
 * on GlScene instead of RenderScript. The script works in pixels of the original screen
 * (see GlScene.virtualSize()); the stars' point size is scaled to this screen.
 */
class GalaxyRS extends GlScene {
    private static final int GALAXY_RADIUS = 300;
    private static final int PARTICLES_COUNT = 12000;

    private ProgramFragment mPfBackground;
    private ProgramFragment mPfStars;
    private ProgramStore mPfsBackground;
    private ProgramStore mPfsLights;
    private ProgramVertex mPvBkOrtho;
    private ProgramVertex mPvBkProj;
    private ProgramVertex mPvStars;
    private Sampler mSampler;
    private Sampler mStarSampler;
    private MatrixAllocation mPvOrthoAlloc;
    private MatrixAllocation mPvProjectionAlloc;

    private Texture mTSpace;
    private Texture mTLight1;
    private Texture mTFlares;

    private final GalaxyState mGalaxyState = new GalaxyState();

    private SimpleMesh mParticlesMesh;

    GalaxyRS(int width, int height) {
        super(width, height);
    }

    @Override
    protected void createScript() {
        createScriptStructures();
        createProgramVertex();
        createProgramRaster();
        createProgramFragmentStore();
        createProgramFragment();
        loadTextures();

        // #pragma stateVertex(PVBkOrtho), stateFragment(PFBackground),
        // stateStore(PFSBackground)
        setRootState(mPvBkOrtho, mPfBackground, mPfsBackground);
        setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        init();
        initParticles();
    }

    private void createScriptStructures() {
        createState();
        createParticlesMesh();
    }

    private void createParticlesMesh() {
        mParticlesMesh = new SimpleMesh(new Field[] {
            new Field("color", KIND_USER, 4, GLES20.GL_UNSIGNED_BYTE),
            new Field("position", KIND_USER, 3),
        }, PARTICLES_COUNT, 0, POINT);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, float xStep, float yStep,
            int xPixels, int yPixels) {
        mGalaxyState.xOffset = xOffset;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        final int[] size = virtualSize(width, height); // Port
        mGalaxyState.width = size[0];
        mGalaxyState.height = size[1];
        mGalaxyState.scale = width > height ? 1 : 0;

        mPvOrthoAlloc.setupOrthoWindow(size[0], size[1]);
        mPvProjectionAlloc.setupProjectionNormalized(size[0], size[1]);

        initParticles();
    }

    static class GalaxyState {
        public int width;
        public int height;
        public int particlesCount;
        public int galaxyRadius;
        public float xOffset;
        public int isPreview;
        public int scale;
    }

    private void createState() {
        boolean isPreview = isPreview();

        final int[] size = virtualSize(mWidth, mHeight); // Port
        mGalaxyState.width = size[0];
        mGalaxyState.height = size[1];
        mGalaxyState.scale = mWidth > mHeight ? 1 : 0;
        mGalaxyState.particlesCount = PARTICLES_COUNT;
        mGalaxyState.galaxyRadius = GALAXY_RADIUS;
        mGalaxyState.isPreview = isPreview ? 1 : 0;
        if (isPreview) {
            mGalaxyState.xOffset = 0.5f;
        }
    }

    private void loadTextures() {
        mTSpace = createFromBitmapResource(R.drawable.space, false, false);
        mTLight1 = createFromBitmapResource(R.drawable.light1, false, false);
        mTFlares = createFromBitmapResource(R.drawable.flares, true, false);
    }

    private void createProgramFragment() {
        mSampler = new Sampler(NEAREST, NEAREST, WRAP, WRAP);

        mPfBackground = new ProgramFragment(REPLACE, RGB, false);
        mPfBackground.bindSampler(mSampler, 0);

        mStarSampler = new Sampler(LINEAR, LINEAR, WRAP, WRAP);

        mPfStars = new ProgramFragment(MODULATE, RGBA, true);
        // As in the original: the star sampler goes to PFBackground, PFStars has none
        mPfBackground.bindSampler(mStarSampler, 0);
    }

    private void createProgramFragmentStore() {
        mPfsBackground = new ProgramStore(ONE, ZERO, false);
        mPfsLights = new ProgramStore(SRC_ALPHA, ONE, false);
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new MatrixAllocation();
        mPvOrthoAlloc.setupOrthoWindow(mGalaxyState.width, mGalaxyState.height);

        mPvBkOrtho = new ProgramVertex();
        mPvBkOrtho.bindAllocation(mPvOrthoAlloc);

        mPvProjectionAlloc = new MatrixAllocation();
        mPvProjectionAlloc.setupProjectionNormalized(mGalaxyState.width, mGalaxyState.height);

        mPvBkProj = new ProgramVertex();
        mPvBkProj.bindAllocation(mPvProjectionAlloc);

        String t = "void main() {\n" +
                    "  float dist = ATTRIB_position.y;\n" +
                    "  float angle = ATTRIB_position.x;\n" +
                    "  float x = dist * sin(angle);\n" +
                    "  float y = dist * cos(angle) * 0.892;\n" +
                    "  float p = dist * 5.5;\n" +
                    "  float s = cos(p);\n" +
                    "  float t = sin(p);\n" +
                    "  vec4 pos;\n" +
                    "  pos.x = t * x + s * y;\n" +
                    "  pos.y = s * x - t * y;\n" +
                    "  pos.z = ATTRIB_position.z;\n" +
                    "  pos.w = 1.0;\n" +
                    "  gl_Position = UNI_MVP * pos;\n" +
                    // Port: in pixels of the original screen
                    "  gl_PointSize = ATTRIB_color.a * 10.0 * UNI_PortPixelScale;\n" +
                    "  varColor.rgb = ATTRIB_color.rgb;\n" +
                    "  varColor.a = 1.0;\n" +
                    "}\n";
        mPvStars = new ProgramVertex(t, new Object[] { "color", 4, "position", 3 }, null);
        mPvStars.bindAllocation(mPvProjectionAlloc);
    }

    private void createProgramRaster() {
        // Point smoothing and sprites: point sprites are always on in OpenGL ES 2.0
    }

    // ------------------------------------------------------------------ galaxy.rs

    private static final float ELLIPSE_RATIO = 0.892f;

    private static final float PI = 3.1415f;
    private static final float TWO_PI = 6.283f;
    private static final float ELLIPSE_TWIST = 0.023333333f;

    private float angle;

    /**
     * Script initialization. Called automatically.
     */
    private void init() {
        angle = 50.0f;
    }

    /**
     * Helper function to generate the stars.
     */
    private float randomGauss() {
        float x1;
        float x2;
        float w = 2.f;

        do {
            x1 = 2.0f * randf2(0.0f, 1.0f) - 1.0f;
            x2 = 2.0f * randf2(0.0f, 1.0f) - 1.0f;
            w = x1 * x1 + x2 * x2;
        } while (w >= 1.0f);

        w = (float) Math.sqrt(-2.0 * Math.log(w) / w);
        return x1 * w;
    }

    private final float[] gSpeed = new float[12000];

    /**
     * Generates the properties for a given star.
     */
    private void createParticle(ByteBuffer part, int idx, float scale) {
        final GalaxyState State = mGalaxyState;
        float d = Math.abs(randomGauss()) * State.galaxyRadius * 0.5f + randf(64.0f);
        float id = d / State.galaxyRadius;
        float z = randomGauss() * 0.4f * (1.0f - id);
        float p = -d * ELLIPSE_TWIST;

        int r, g, b, a;
        if (d < State.galaxyRadius * 0.33f) {
            r = (int) (220 + id * 35);
            g = 220;
            b = 220;
        } else {
            r = 180;
            g = 180;
            b = (int) clampf(140.f + id * 115.f, 140.f, 255.f);
        }
        // Stash point size * 10 in Alpha
        a = (int) (randf2(1.2f, 2.1f) * 60);
        final int base = idx * 16;
        part.putInt(base, r | g << 8 | b << 16 | a << 24);

        if (d > State.galaxyRadius * 0.15f) {
            z *= 0.6f * (1.0f - id);
        } else {
            z *= 0.72f;
        }

        // Map to the projection coordinates (viewport.x = -1.0 -> 1.0)
        d = mapf(-4.0f, State.galaxyRadius + 4.0f, 0.0f, scale, d);

        part.putFloat(base + 4, randf(TWO_PI));
        part.putFloat(base + 8, d);
        gSpeed[idx] = randf2(0.0015f, 0.0025f) * (0.5f + (scale / d)) * 0.8f;

        part.putFloat(base + 12, z / 5.0f);
    }

    /**
     * Initialize all the stars. Called from Java.
     */
    private void initParticles() {
        final GalaxyState State = mGalaxyState;
        if (State.isPreview == 1) {
            angle = 0.0f;
        }

        final ByteBuffer part = mParticlesMesh.data();
        int particlesCount = State.particlesCount;
        float scale = State.galaxyRadius / (State.width * 0.5f);

        int i;
        for (i = 0; i < particlesCount; i++) {
            createParticle(part, i, scale);
        }
        mParticlesMesh.uploadToBufferObject();
    }

    private void drawSpace(float xOffset, int width, int height) {
        bindTexture(mPfBackground, 0, mTSpace);
        drawQuadTexCoords(
                0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                width, 0.0f, 0.0f, 2.0f, 1.0f,
                width, height, 0.0f, 2.0f, 0.0f,
                0.0f, height, 0.0f, 0.0f, 0.0f);
    }

    private void drawLights(float xOffset, int width, int height) {
        bindProgramVertex(mPvBkProj);
        bindProgramFragment(mPfBackground);
        bindTexture(mPfBackground, 0, mTLight1);

        float scale = 512.0f / width;
        float x = -scale + xOffset - scale * 0.05f;
        float y = -scale;

        scale *= 2.0f;

        drawQuad(x, y, 0.0f,
                 x + scale * 1.1f, y, 0.0f,
                 x + scale * 1.1f, y + scale, 0.0f,
                 x, y + scale, 0.0f);
    }

    private final float[] matrix = new float[16];

    private void drawParticles(float xOffset, float offset, int width, int height) {
        final GalaxyState State = mGalaxyState;
        bindProgramVertex(mPvStars);
        bindProgramFragment(mPfStars);
        bindProgramFragmentStore(mPfsLights);
        bindTexture(mPfStars, 0, mTFlares);

        float a = offset * angle;
        float absoluteAngle = Math.abs(a);

        RsMatrix.loadTranslate(matrix, 0.0f, 0.0f, 10.0f - 6.0f * absoluteAngle / 50.0f);
        if (State.scale == 0) {
            RsMatrix.scale(matrix, 6.6f, 6.0f, 1.0f);
        } else {
            RsMatrix.scale(matrix, 12.6f, 12.0f, 1.0f);
        }
        RsMatrix.rotate(matrix, absoluteAngle, 1.0f, 0.0f, 0.0f);
        RsMatrix.rotate(matrix, a, 0.0f, 0.4f, 0.1f);
        vpLoadModelMatrix(matrix);

        // quadratic attenuation
        //pointAttenuation(0.1f + 0.3f * fabsf(offset), 0.0f, 0.06f  + 0.1f *  fabsf(offset));

        int particlesCount = State.particlesCount;

        final ByteBuffer vtx = mParticlesMesh.data();

        int i = 0;
        for ( ; i < particlesCount; i++) {
            final int x = i * 16 + 4;
            vtx.putFloat(x, vtx.getFloat(x) + gSpeed[i]);
        }

        mParticlesMesh.uploadToBufferObject();
        drawSimpleMeshRange(mParticlesMesh, 0, particlesCount);
    }

    @Override
    protected int main() {
        final GalaxyState State = mGalaxyState;
        int width = State.width;
        int height = State.height;

        float x = 0.0f;
        float offset = lerpf(-1.0f, 1.0f, State.xOffset);

        drawSpace(x, width, height);

        drawParticles(x, offset, width, height);
        drawLights(x, width, height);

        return 45;
    }
}
