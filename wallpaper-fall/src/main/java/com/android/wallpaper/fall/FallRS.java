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

package com.android.wallpaper.fall;

import static com.gravarty.wallpaper.gl.RsLib.*;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Bundle;

import com.android.wallpaper.R;
import com.gravarty.wallpaper.gl.GlScene;
import com.gravarty.wallpaper.gl.RsMatrix;

import java.util.ArrayList;

/**
 * Port: FallRS and res/raw/fall.rs (AOSP packages/wallpapers/Basic, android-2.3.7_r1)
 * on GlScene instead of RenderScript. Fall works in normalized coordinates, so it is not
 * scaled to the original screen like the others.
 */
class FallRS extends GlScene {
    private static final int MESH_RESOLUTION = 48;

    private ProgramFragment mPfBackground;
    private ProgramFragment mPfSky;
    private ProgramStore mPfsBackground;
    private ProgramStore mPfsLeaf;
    private ProgramVertex mPvSky;
    private ProgramVertex mPvWater;
    private MatrixAllocation mPvOrthoAlloc;
    private Sampler mSampler;

    private Texture mTRiverbed;
    private Texture mTLeaves;

    private DropState mDrop;

    private int mMeshWidth;
    // Constants: Drop01 .. Drop10, Offset (vec4 each), Rotate (float)
    private final float[] mConstants = new float[11 * 4 + 1];
    private static final int CONSTANTS_OFFSET = 10 * 4;
    private static final int CONSTANTS_ROTATE = 11 * 4;

    private int mMeshHeight;
    private float[] mMeshVertices;
    private short[] mMeshIndices;
    private SimpleMesh mMesh;
    private WorldState mWorldState;

    private float mGlHeight;

    public FallRS(int width, int height) {
        super(width, height);
    }

    @Override
    public void init(Context context, boolean isPreview) {
        super.init(context, isPreview);
        // Port: the Java side state is made here, before start(); GL in createScript()
        createMesh();
        createState();
        init();
        initLeaves();
    }

    @Override
    public void setOffset(float xOffset, float yOffset, float xStep, float yStep,
            int xPixels, int yPixels) {
        mWorldState.xOffset = xOffset;
    }

    @Override
    public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
            boolean resultRequested) {
        if (WallpaperManager.COMMAND_TAP.equals(action)) {
            addDrop(x + (mWorldState.rotate == 0 ? (mWorldState.width * mWorldState.xOffset) : 0), y);
        } else if (WallpaperManager.COMMAND_DROP.equals(action)) {
            addDrop(x + (mWorldState.rotate == 0 ? (mWorldState.width * mWorldState.xOffset) : 0), y);
        }
        return null;
    }

    @Override
    public void start() {
        super.start();
        final WorldState worldState = mWorldState;
        final int width = worldState.width;
        final int x = width / 4 + (int)(Math.random() * (width / 2));
        final int y = worldState.height / 4 + (int)(Math.random() * (worldState.height / 2));
        addDrop(x + (mWorldState.rotate == 0 ? (width * worldState.xOffset) : 0), y);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        mWorldState.width = width;
        mWorldState.height = height;
        mWorldState.rotate = width > height ? 1 : 0;

        mPvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);
    }

    @Override
    protected void createScript() {
        mMesh = new SimpleMesh(new Field[] {
            new Field("position", KIND_POSITION, 2),
        }, mMeshVertices.length / 2, mMeshIndices.length, TRIANGLE);
        mMesh.data(mMeshVertices);
        mMesh.indices(mMeshIndices);

        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        loadTextures();

        // #pragma stateVertex(PVSky), stateFragment(PFBackground), stateStore(PFSBackground)
        setRootState(mPvSky, mPfBackground, mPfsBackground);
        setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    /** SimpleMesh.TriangleMeshBuilder(mRS, 2, 0): "position" only */
    private void createMesh() {
        final ArrayList<Float> vertices = new ArrayList<Float>();
        final ArrayList<Short> indices = new ArrayList<Short>();

        final int width = mWidth > mHeight ? mHeight : mWidth;
        final int height = mWidth > mHeight ? mWidth : mHeight;

        int wResolution = MESH_RESOLUTION;
        int hResolution = (int) (MESH_RESOLUTION * height / (float) width);

        mGlHeight = 2.0f * height / (float) width;

        wResolution += 2;
        hResolution += 2;

        for (int y = 0; y <= hResolution; y++) {
            final float yOffset = (((float)y / hResolution) * 2.f - 1.f) * height / width;
            for (int x = 0; x <= wResolution; x++) {
                vertices.add(((float)x / wResolution) * 2.f - 1.f);
                vertices.add(yOffset);
            }
        }

        for (int y = 0; y < hResolution; y++) {
            final boolean shift = (y & 0x1) == 0;
            final int yOffset = y * (wResolution + 1);
            for (int x = 0; x < wResolution; x++) {
                final int index = yOffset + x;
                final int iWR1 = index + wResolution + 1;
                if (shift) {
                    addTriangle(indices, index, index + 1, iWR1);
                    addTriangle(indices, index + 1, iWR1 + 1, iWR1);
                } else {
                    addTriangle(indices, index, iWR1 + 1, iWR1);
                    addTriangle(indices, index, index + 1, iWR1 + 1);
                }
            }
        }

        mMeshVertices = new float[vertices.size()];
        for (int i = 0; i < mMeshVertices.length; i++) mMeshVertices[i] = vertices.get(i);
        mMeshIndices = new short[indices.size()];
        for (int i = 0; i < mMeshIndices.length; i++) mMeshIndices[i] = indices.get(i);

        mMeshWidth = wResolution + 1;
        mMeshHeight = hResolution + 1;
    }

    private static void addTriangle(ArrayList<Short> indices, int idx1, int idx2, int idx3) {
        indices.add((short) idx1);
        indices.add((short) idx2);
        indices.add((short) idx3);
    }

    static class WorldState {
        public int frameCount;
        public int width;
        public int height;
        public int meshWidth;
        public int meshHeight;
        public int rippleIndex;
        public float glWidth;
        public float glHeight;
        public float skySpeedX;
        public float skySpeedY;
        public int rotate;
        public int isPreview;
        public float xOffset;
    }

    static class DropState {
        public int dropX;
        public int dropY;
    }

    private void createState() {
        mWorldState = new WorldState();
        mWorldState.width = mWidth;
        mWorldState.height = mHeight;
        mWorldState.meshWidth = mMeshWidth;
        mWorldState.meshHeight = mMeshHeight;
        mWorldState.rippleIndex = 0;
        mWorldState.glWidth = 2.0f;
        mWorldState.glHeight = mGlHeight;
        mWorldState.skySpeedX = random(-0.001f, 0.001f);
        mWorldState.skySpeedY = random(0.00008f, 0.0002f);
        mWorldState.rotate = mWidth > mHeight ? 1 : 0;
        mWorldState.isPreview = isPreview() ? 1 : 0;

        mDrop = new DropState();
        mDrop.dropX = -1;
        mDrop.dropY = -1;
    }

    // Port: android.util.MathUtils.random() (hidden API now)
    private static float random(float howsmall, float howbig) {
        if (howsmall >= howbig) return howsmall;
        return (float) (Math.random() * (howbig - howsmall) + howsmall);
    }

    private void loadTextures() {
        mTRiverbed = createFromBitmapResource(R.drawable.pond, false, false);
        mTLeaves = createFromBitmapResource(R.drawable.leaves, true, false);
    }

    private void createProgramFragment() {
        mSampler = new Sampler(LINEAR, LINEAR, CLAMP, CLAMP);

        mPfBackground = new ProgramFragment(REPLACE, RGBA, false);
        mPfBackground.bindSampler(mSampler, 0);

        mPfSky = new ProgramFragment(MODULATE, RGBA, false);
        mPfSky.bindSampler(mSampler, 0);
    }

    private void createProgramFragmentStore() {
        mPfsBackground = new ProgramStore(ONE, ONE, false);
        mPfsLeaf = new ProgramStore(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, false);
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new MatrixAllocation();
        mPvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);

        mPvSky = new ProgramVertex();
        mPvSky.bindAllocation(mPvOrthoAlloc);

        String t = "\n" +
                "vec2 addDrop(vec4 d, vec2 pos, float dxMul) {\n" +
                "  vec2 ret = vec2(0.0, 0.0);\n" +
                "  vec2 delta = d.xy - pos;\n" +
                "  delta.x *= dxMul;\n" +
                "  float dist = length(delta);\n" +
                "  if (dist < d.w) { \n" +
                "    float amp = d.z * dist;\n" +
                "    amp /= d.w * d.w;\n" +
                "    amp *= sin(d.w - dist);\n" +
                "    ret = delta * amp;\n" +
                "  }\n" +
                "  return ret;\n" +
                "}\n" +

                "void main() {\n" +
                "  vec2 pos = ATTRIB_position.xy;\n" +
                "  gl_Position = vec4(pos.x, pos.y, 0.0, 1.0);\n" +
                "  float dxMul = 1.0;\n" +

                "  varTex0 = vec4((pos.x + 1.0), (pos.y + 1.6666), 0.0, 0.0);\n" +

                "  if (UNI_Rotate < 0.9) {\n" +
                "    varTex0.xy *= vec2(0.25, 0.33);\n" +
                "    varTex0.x += UNI_Offset.x * 0.5;\n" +
                "    pos.x += UNI_Offset.x * 2.0;\n" +
                "  } else {\n" +
                "    varTex0.xy *= vec2(0.5, 0.3125);\n" +
                "    dxMul = 2.5;\n" +
                "  }\n" +

                "  varColor = vec4(1.0, 1.0, 1.0, 1.0);\n" +
                "  pos.xy += vec2(1.0, 1.0);\n" +
                "  pos.xy *= vec2(25.0, 42.0);\n" +

                "  varTex0.xy += addDrop(UNI_Drop01, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop02, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop03, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop04, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop05, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop06, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop07, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop08, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop09, pos, dxMul);\n" +
                "  varTex0.xy += addDrop(UNI_Drop10, pos, dxMul);\n" +
                "}\n";
        mPvWater = new ProgramVertex(t, new Object[] { "position", 2 }, new Object[] {
            "Drop01", 4, "Drop02", 4, "Drop03", 4, "Drop04", 4, "Drop05", 4,
            "Drop06", 4, "Drop07", 4, "Drop08", 4, "Drop09", 4, "Drop10", 4,
            "Offset", 4, "Rotate", 1,
        });
        mPvWater.bindAllocation(mPvOrthoAlloc);
        mPvWater.bindConstants(mConstants);
    }

    void addDrop(float x, float y) {
        mDrop.dropX = (int) ((x / mWidth) * mMeshWidth);
        mDrop.dropY = (int) ((y / mHeight) * mMeshHeight);
    }

    // ------------------------------------------------------------------ fall.rs

    private static final int LEAVES_TEXTURES_COUNT = 8;
    private static final float LEAF_SIZE = 0.55f;
    private static final int LEAVES_COUNT = 14;

    private float skyOffsetX;
    private float skyOffsetY;
    private float g_DT;
    private int g_LastTime;

    private static final class drop_s {
        float ampS;
        float ampE;
        float spread;
        float x;
        float y;
    }
    private final drop_s[] gDrops = new drop_s[10];
    private int gMaxDrops;

    private static final class Leaves_s {
        float x;
        float y;
        float scale;
        float angle;
        float spin;
        float u1;
        float u2;
        float altitude;
        float rippled;
        float deltaX;
        float deltaY;
        int newLeaf;
    }

    private final Leaves_s[] gLeavesStore = new Leaves_s[LEAVES_COUNT];
    private final Leaves_s[] gLeaves = new Leaves_s[LEAVES_COUNT];
    private final Leaves_s[] gNextLeaves = new Leaves_s[LEAVES_COUNT];

    private void init() {
        int ct;
        gMaxDrops = 10;
        for (ct=0; ct<gMaxDrops; ct++) {
            gDrops[ct] = new drop_s();
            gDrops[ct].ampS = 0;
            gDrops[ct].ampE = 0;
            gDrops[ct].spread = 1;
        }
        for (ct = 0; ct < LEAVES_COUNT; ct++) {
            gLeavesStore[ct] = new Leaves_s();
        }
    }

    private void initLeaves() {
        final WorldState State = mWorldState;
        float width = State.glWidth * 2;
        float height = State.glHeight;

        int i;
        for (i = 0; i < LEAVES_COUNT; i ++) {
            final Leaves_s leaf = gLeavesStore[i];
            gLeaves[i] = leaf;
            int sprite = (int) randf(LEAVES_TEXTURES_COUNT);
            leaf.x = randf2(-width * 0.5f, width * 0.5f);
            leaf.y = randf2(-height * 0.5f, height * 0.5f);
            leaf.scale = randf2(0.4f, 0.5f);
            leaf.angle = randf2(0.0f, 360.0f);
            leaf.spin = degf(randf2(-0.02f, 0.02f)) * 0.25f;
            leaf.u1 = sprite / (float) LEAVES_TEXTURES_COUNT;
            leaf.u2 = (sprite + 1) / (float) LEAVES_TEXTURES_COUNT;
            leaf.altitude = -1.0f;
            leaf.rippled = 1.0f;
            leaf.deltaX = randf2(-0.02f, 0.02f) / 2.0f;
            leaf.deltaY = -0.08f * randf2(0.9f, 1.1f) / 2.0f;
        }
    }

    private void updateDrop(int ct) {
        gDrops[ct].spread += 30.f * g_DT;
        gDrops[ct].ampE = gDrops[ct].ampS / gDrops[ct].spread;
    }

    private void drop(int x, int y, float s) {
        int ct;
        int iMin = 0;
        float minAmp = 10000.f;
        for (ct = 0; ct < gMaxDrops; ct++) {
            if (gDrops[ct].ampE < minAmp) {
                iMin = ct;
                minAmp = gDrops[ct].ampE;
            }
        }
        gDrops[iMin].ampS = s;
        gDrops[iMin].spread = 0;
        gDrops[iMin].x = x;
        gDrops[iMin].y = mWorldState.meshHeight - y - 1;
        updateDrop(iMin);
    }

    private void generateRipples() {
        int ct;
        for (ct = 0; ct < gMaxDrops; ct++) {
            final drop_s d = gDrops[ct];
            final int v = ct * 4; // &Constants->Drop01 + ct
            mConstants[v] = d.x;
            mConstants[v + 1] = d.y;
            mConstants[v + 2] = d.ampE * 0.12f;
            mConstants[v + 3] = d.spread;
        }
        mConstants[CONSTANTS_OFFSET] = mWorldState.xOffset; // Constants->Offset.x

        for (ct = 0; ct < gMaxDrops; ct++) {
            updateDrop(ct);
        }
    }

    private void genLeafDrop(Leaves_s leaf, float amp) {
        final WorldState State = mWorldState;
        float nx = (leaf.x + State.glWidth * 0.5f) / State.glWidth;
        float ny = (leaf.y + State.glHeight * 0.5f) / State.glHeight;
        drop((int) (nx * State.meshWidth), (int) (State.meshHeight - ny * State.meshHeight),
                amp);
    }

    private final float[] matrix = new float[16];

    private int drawLeaf(Leaves_s leaf) {
        final WorldState State = mWorldState;

        float x = leaf.x;
        float y = leaf.y;

        float u1 = leaf.u1;
        float u2 = leaf.u2;

        float a = leaf.altitude;
        float s = leaf.scale;
        float r = leaf.angle;

        float tz = 0.0f;
        if (a > 0.0f) {
            tz = -a;
        }

        if (a > 0.0f) {

            float alpha = 1.0f;
            if (a >= 0.4f) alpha = 1.0f - (a - 0.4f) / 0.1f;

            color(0.0f, 0.0f, 0.0f, alpha * 0.15f);

            RsMatrix.loadIdentity(matrix);
            if (State.rotate == 0) {
                RsMatrix.translate(matrix, x - State.xOffset * 2, y, tz);
            } else {
                RsMatrix.translate(matrix, x, y, tz);
                RsMatrix.rotate(matrix, 90.0f, 0.0f, 0.0f, 1.0f);
            }

            float shadowOffet = a / 5;

            RsMatrix.scale(matrix, s, s, 1.0f);
            RsMatrix.rotate(matrix, r, 0.0f, 0.0f, 1.0f);
            vpLoadModelMatrix(matrix);

            drawQuadTexCoords(-LEAF_SIZE, -LEAF_SIZE, 0, u1, 1.0f,
                               LEAF_SIZE, -LEAF_SIZE, 0, u2, 1.0f,
                               LEAF_SIZE,  LEAF_SIZE, 0, u2, 0.0f,
                              -LEAF_SIZE,  LEAF_SIZE, 0, u1, 0.0f);

            color(1.0f, 1.0f, 1.0f, alpha);
        } else {
            color(1.0f, 1.0f, 1.0f, 1.0f);
        }

        RsMatrix.loadIdentity(matrix);
        if (State.rotate == 0) {
            RsMatrix.translate(matrix, x - State.xOffset * 2, y, tz);
        } else {
            RsMatrix.translate(matrix, x, y, tz);
            RsMatrix.rotate(matrix, 90.0f, 0.0f, 0.0f, 1.0f);
        }
        RsMatrix.scale(matrix, s, s, 1.0f);
        RsMatrix.rotate(matrix, r, 0.0f, 0.0f, 1.0f);
        vpLoadModelMatrix(matrix);

        drawQuadTexCoords(-LEAF_SIZE, -LEAF_SIZE, 0, u1, 1.0f,
                           LEAF_SIZE, -LEAF_SIZE, 0, u2, 1.0f,
                           LEAF_SIZE,  LEAF_SIZE, 0, u2, 0.0f,
                          -LEAF_SIZE,  LEAF_SIZE, 0, u1, 0.0f);

        float spin = leaf.spin;
        if (a <= 0.0f) {
            float rippled = leaf.rippled;
            if (rippled < 0.0f) {
                genLeafDrop(leaf, 1.5f);
                //drop(((x + State->glWidth * 0.5f) / State->glWidth) * meshWidth,
                //     meshHeight - ((y + State->glHeight * 0.5f) / State->glHeight) * meshHeight, 1);
                spin /= 4.0f;
                leaf.spin = spin;
                leaf.rippled = 1.0f;
            }
            leaf.x = x + leaf.deltaX * g_DT;
            leaf.y = y + leaf.deltaY * g_DT;
            r += spin;
            leaf.angle = r;
        } else {
            a -= 0.15f * g_DT;
            leaf.altitude = a;
            r += spin * 2.0f;
            leaf.angle = r;
        }

        int newLeaf = 0;
        if (-LEAF_SIZE * s + x > State.glWidth || LEAF_SIZE * s + x < -State.glWidth ||
                LEAF_SIZE * s + y < -State.glHeight / 2.0f) {

            int sprite = (int) randf(LEAVES_TEXTURES_COUNT);
            leaf.x = randf2(-State.glWidth, State.glWidth);
            leaf.y = randf2(-State.glHeight * 0.5f, State.glHeight * 0.5f);

            leaf.scale = randf2(0.4f, 0.5f);
            leaf.spin = degf(randf2(-0.02f, 0.02f)) * 0.35f;
            leaf.u1 = sprite / (float) LEAVES_TEXTURES_COUNT;
            leaf.u2 = (sprite + 1) / (float) LEAVES_TEXTURES_COUNT;
            leaf.altitude = 0.7f;
            leaf.rippled = -1.0f;
            leaf.deltaX = randf2(-0.02f, 0.02f) / 2.0f;
            leaf.deltaY = -0.08f * randf2(0.9f, 1.1f) / 2.0f;
            leaf.newLeaf = 1;
            newLeaf = 1;
        }
        return newLeaf;
    }

    private void drawLeaves() {
        bindProgramFragment(mPfSky);
        bindProgramFragmentStore(mPfsLeaf);
        bindProgramVertex(mPvSky);
        bindTexture(mPfSky, 0, mTLeaves);

        color(1.0f, 1.0f, 1.0f, 1.0f);

        int newLeaves = 0;
        int i = 0;
        for ( ; i < LEAVES_COUNT; i += 1) {
            if (drawLeaf(gLeaves[i]) != 0) {
                newLeaves = 1;

            }
        }

        if (newLeaves > 0) {
            int index = 0;

            // Copy all the old leaves to the beginning of gNextLeaves
            for (i=0; i < LEAVES_COUNT; i++) {
                if (gLeaves[i].newLeaf == 0) {
                    gNextLeaves[index] = gLeaves[i];
                    index++;
                }
            }

            // Now copy all the newly falling leaves to the end of gNextLeaves
            for (i=0; i < LEAVES_COUNT; i++) {
                if (gLeaves[i].newLeaf > 0) {
                    gNextLeaves[index] = gLeaves[i];
                    gNextLeaves[index].newLeaf = 0;
                    index++;
                }
            }

            // And move everything in gNextLeaves back to gLeaves
            for (i=0; i < LEAVES_COUNT; i++) {
                gLeaves[i] = gNextLeaves[i];
            }
        }

        RsMatrix.loadIdentity(matrix);
        vpLoadModelMatrix(matrix);
    }

    private void drawRiverbed() {
        bindTexture(mPfBackground, 0, mTRiverbed);
        drawSimpleMesh(mMesh);
    }

    @Override
    protected int main() {
        final WorldState State = mWorldState;
        final DropState Drop = mDrop;

        // Compute dt in seconds.
        int newTime = uptimeMillis();
        g_DT = (newTime - g_LastTime) / 1000.f;
        g_LastTime = newTime;
        g_DT = minf(g_DT, 0.2f);

        mConstants[CONSTANTS_ROTATE] = (float) State.rotate; // Constants->Rotate

        if (Drop.dropX != -1) {
            drop(Drop.dropX, Drop.dropY, 2);
            Drop.dropX = -1;
            Drop.dropY = -1;
        }

        int ct;
        int add = 0;
        for (ct = 0; ct < gMaxDrops; ct++) {
            if (gDrops[ct].ampE < 0.005f) {
                add = 1;
            }
        }

        if (add != 0) {
            int i = (int)randf(LEAVES_COUNT);
            genLeafDrop(gLeaves[i], randf(0.3f) + 0.1f);
        }

        bindProgramVertex(mPvWater);
        generateRipples();
        drawRiverbed();

        bindProgramVertex(mPvSky);
        drawLeaves();

        return 30;
    }
}
